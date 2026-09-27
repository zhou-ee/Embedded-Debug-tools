//! probe-rs 后端：进程内直接操作调试探针（替代原版 PyOCD 库调用）。

use probe_rs::{CoreInterface, MemoryInterface, Permissions, Session};
use std::time::{Duration, Instant};

use crate::{BackendError, DebugBackend};

/// `session.core()` 连续失败到该次数才升级为"连接丢失"。
/// 单次失败按瞬时错误处理（该方法每次都会重新 attach 访问端口，失败未必是断线）。
const CORE_FAIL_STREAK_LIMIT: u32 = 5;

/// PPB / SCS（调试寄存器区）：不在 target 的 memory_regions 里，但必须允许访问
const PPB_LO: u64 = 0xE000_0000;
const PPB_HI: u64 = 0xE010_0000;

/// ARM Cortex-M 外设寄存器区（MMIO，SVD / 硬件外设）：不在 target 的 memory_regions 里，必须允许访问
const PERIPH_LO: u64 = 0x4000_0000;
const PERIPH_HI: u64 = 0x6000_0000;

/// 外部设备与 RAM 区（FMC/FSMC，如外扩 SRAM/NOR/NAND）：
const EXT_RAM_LO: u64 = 0x6000_0000;
const EXT_RAM_HI: u64 = 0xA000_0000;

pub struct ProbeRsBackend {
    target: String,
    speed_hz: u32,
    session: Option<Session>,
    /// core() 连续失败计数
    core_fail_streak: u32,
}

impl ProbeRsBackend {
    pub fn new(target: String, speed_hz: u32) -> Self {
        Self {
            target,
            speed_hz,
            session: None,
            core_fail_streak: 0,
        }
    }

    /// 直接接管一个已存在的会话（烧录后复用连接等场景）。
    pub fn with_session(target: String, speed_hz: u32, session: Session) -> Self {
        Self {
            target,
            speed_hz,
            session: Some(session),
            core_fail_streak: 0,
        }
    }

    pub fn session_mut(&mut self) -> Option<&mut Session> {
        self.session.as_mut()
    }

    fn core(&mut self) -> Result<probe_rs::Core<'_>, BackendError> {
        let session = self.session.as_mut().ok_or(BackendError::NotConnected)?;
        // `session.core()` **每次调用都会重新 attach 访问端口**，克隆探针在较高 SWD
        // 速率下偶发失败很常见 —— 这不等于连接丢失。原先一律判 ConnectionLost 会让
        // 引擎 disconnect → 1 秒后重连 → 再失败，陷入无限重连（用户实测日志就是这个
        // 循环：连接 / 停住 / "An ARM specific error" / 断连，往复不断）。
        // 这里改为：按错误文本归类，只有**连续**失败到阈值才升级为连接丢失；
        // 单次失败降级为瞬时错误，交给引擎的轮询周期自然重试。
        match session.core(0) {
            Ok(core) => {
                self.core_fail_streak = 0;
                Ok(core)
            }
            Err(e) => {
                self.core_fail_streak += 1;
                let mapped = Self::map_err(e);
                if self.core_fail_streak >= CORE_FAIL_STREAK_LIMIT && !mapped.is_fatal() {
                    return Err(BackendError::ConnectionLost(format!(
                        "连续 {} 次无法访问核心（最后一次: {mapped}）",
                        self.core_fail_streak
                    )));
                }
                Err(mapped)
            }
        }
    }

    /// probe-rs 的 `Error::Arm(_)` Display 只有一句 "An ARM specific error occurred."，
    /// 真正原因（AccessPort / CoreNotHalted / Timeout …）藏在 `source()` 链里。
    /// 不展开的话所有 ARM 错误在日志里长得一模一样，根本没法诊断。
    fn describe(e: &probe_rs::Error) -> String {
        use std::error::Error as _;
        let mut out = e.to_string();
        let mut src = e.source();
        while let Some(s) = src {
            out.push_str(" ← ");
            out.push_str(&s.to_string());
            src = s.source();
        }
        out
    }

    /// USB 级错误视为连接丢失，其余视为瞬时传输错误。
    fn map_err(e: probe_rs::Error) -> BackendError {
        let text = Self::describe(&e);
        let lower = text.to_lowercase();
        if lower.contains("usb") || lower.contains("disconnect") || lower.contains("no probe") {
            BackendError::ConnectionLost(text)
        } else {
            BackendError::Transfer(text)
        }
    }
}

impl DebugBackend for ProbeRsBackend {
    fn connect(&mut self) -> Result<(), BackendError> {
        if self.session.is_some() {
            return Ok(());
        }
        // 复合设备（如 ATK-HS-V3）会枚举出多个实例且首个可能打不开，
        // 必须逐个尝试，不能盲选 probes[0]
        let (mut probe, ident) = crate::probe::open_first_available()
            .map_err(BackendError::ConnectionLost)?;
        let _ = probe.set_speed(self.speed_hz / 1000);
        tracing::debug!("使用探针 {ident}");

        // attach（非复位附加，对照原版 connect_mode="attach"）
        let session = if self.target.trim().is_empty() {
            probe.attach(probe_rs::config::TargetSelector::Auto, Permissions::default())
        } else {
            probe.attach(self.target.as_str(), Permissions::default())
        }
        .map_err(|e| BackendError::ConnectionLost(format!("附加目标失败: {e}")))?;

        self.session = Some(session);
        self.core_fail_streak = 0;
        Ok(())
    }

    fn disconnect(&mut self) {
        self.session = None;
        self.core_fail_streak = 0;
    }

    fn is_connected(&self) -> bool {
        self.session.is_some()
    }

    fn read_bytes(&mut self, addr: u64, len: usize) -> Result<Vec<u8>, BackendError> {
        let mut core = self.core()?;
        // 长度按"所在区域上界"截断；**地址不在任何区域内时拒绝下发**。
        //
        // 越界访问不会干净地返回错误：实测 G431 + CMSIS-DAP 克隆，读一次 SRAM 之外
        // 的地址会让目标回 FAULT 响应，**把访问端口留在故障态** —— 之后连
        // `session.core()` 都失败（Arm(Dap(FaultResponse))），引擎遂判"连接丢失"，
        // 断线重连后又触发同样的越界读，形成无限重连。
        // 调用方（栈扫描 / 调用栈回溯 / STL 展开 / SVD）本就是"读失败即停止或缩短"
        // 的语义，返回干净的 Transfer 错误即可，绝不能真的把访问发出去。
        let len = match core.memory_regions().find(|r| r.contains(addr)) {
            Some(r) => {
                let avail = (r.address_range().end - addr) as usize;
                len.min(avail)
            }
            // PPB / SCS（调试寄存器区）不在 target 的 memory_regions 里，但必须可访问
            None if (PPB_LO..PPB_HI).contains(&addr) => len,
            // 外设寄存器区（MMIO，SVD 读寄存器）
            None if (PERIPH_LO..PERIPH_HI).contains(&addr) => len,
            // 外扩设备 / RAM 区（FMC/FSMC）
            None if (EXT_RAM_LO..EXT_RAM_HI).contains(&addr) => len,
            None => {
                return Err(BackendError::Transfer(format!(
                    "地址 0x{addr:08x} 不在任何已映射内存区域内，已拒绝下发（越界访问会让探针进故障态）"
                )))
            }
        };
        if len == 0 {
            return Ok(Vec::new());
        }
        if addr % 4 == 0 && len % 4 == 0 {
            let mut words = vec![0u32; len / 4];
            if core.read_32(addr, &mut words).is_ok() {
                let mut bytes = Vec::with_capacity(len);
                for w in words {
                    bytes.extend_from_slice(&w.to_le_bytes());
                }
                return Ok(bytes);
            }
        }
        let mut buf = vec![0u8; len];
        core.read_8(addr, &mut buf).map_err(Self::map_err)?;
        Ok(buf)
    }

    fn write_bytes(&mut self, addr: u64, data: &[u8]) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.write_8(addr, data).map_err(Self::map_err)?;
        Ok(())
    }

    /// CoreSight 调试寄存器只接受字访问 —— 用 `read_word_32`/`write_word_32`
    /// 而非 `read_8`/`write_8`（真机实测：字节访问写 DWT_COMP0 只落地一个字节）。
    fn read_u32(&mut self, addr: u64) -> Result<u32, BackendError> {
        let mut core = self.core()?;
        core.read_word_32(addr).map_err(Self::map_err)
    }

    fn write_u32(&mut self, addr: u64, value: u32) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.write_word_32(addr, value).map_err(Self::map_err)
    }

    fn halt(&mut self) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.halt(Duration::from_millis(500))
            .map(|_| ())
            .map_err(Self::map_err)
    }

    fn resume(&mut self) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.run().map_err(Self::map_err)
    }

    fn step(&mut self) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.step().map(|_| ()).map_err(Self::map_err)
    }

    fn reset(&mut self) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.reset().map_err(Self::map_err)
    }

    fn reset_and_halt(&mut self) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.reset_and_halt(Duration::from_millis(500))
            .map(|_| ())
            .map_err(Self::map_err)
    }

    fn is_halted(&mut self) -> Result<bool, BackendError> {
        let mut core = self.core()?;
        core.core_halted().map_err(Self::map_err)
    }

    fn set_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.set_hw_breakpoint(addr & !1).map_err(Self::map_err)
    }

    fn clear_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        let mut core = self.core()?;
        core.clear_hw_breakpoint(addr & !1).map_err(Self::map_err)
    }

    /// 示波突发：Core 只创建一次（重建开销 ~1.7ms 摊薄到每帧），节拍由
    /// 本方法内精确控制——单帧读 ~895µs < 1ms，1kHz 可严格达成。
    fn scope_burst(
        &mut self,
        blocks: &[(u64, usize)],
        count: usize,
        interval: Duration,
    ) -> Result<Vec<(Duration, Vec<Vec<u8>>)>, BackendError> {
        let start = Instant::now();
        let mut core = self.core()?;
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            let mut frame = Vec::with_capacity(blocks.len());
            for (addr, len) in blocks {
                // 同 read_bytes：地址不在任何区域内就**不要下发**，
                // 否则会让访问端口进故障态（示波目标地址配错时尤其致命）
                let len = match core.memory_regions().find(|r| r.contains(*addr)) {
                    Some(r) => {
                        let avail = (r.address_range().end - addr) as usize;
                        (*len).min(avail)
                    }
                    None if (PPB_LO..PPB_HI).contains(addr) => *len,
                    None if (PERIPH_LO..PERIPH_HI).contains(addr) => *len,
                    None if (EXT_RAM_LO..EXT_RAM_HI).contains(addr) => *len,
                    None => {
                        return Err(BackendError::Transfer(format!(
                            "采样地址 0x{addr:08x} 不在任何已映射内存区域内，已拒绝下发"
                        )))
                    }
                };
                if *addr % 4 == 0 && len % 4 == 0 && len > 0 {
                    let mut words = vec![0u32; len / 4];
                    if core.read_32(*addr, &mut words).is_ok() {
                        let mut buf = Vec::with_capacity(len);
                        for w in words {
                            buf.extend_from_slice(&w.to_le_bytes());
                        }
                        frame.push(buf);
                        continue;
                    }
                }
                let mut buf = vec![0u8; len];
                if len > 0 {
                    core.read_8(*addr, &mut buf).map_err(Self::map_err)?;
                }
                frame.push(buf);
            }
            out.push((start.elapsed(), frame));
            let due = start + interval * (i as u32 + 1);
            let now = Instant::now();
            if now < due {
                std::thread::sleep(due - now);
            }
        }
        Ok(out)
    }

    fn hw_breakpoint_quota(&mut self) -> Option<(usize, usize)> {
        let mut core = self.core().ok()?;
        let total = core.available_breakpoint_units().ok()? as usize;
        let used = core.hw_breakpoints().ok()?.iter().filter(|b| b.is_some()).count();
        Some((used, total))
    }

    fn read_core_register(&mut self, name: &str) -> Result<u64, BackendError> {
        use probe_rs::RegisterRole;
        let mut core = self.core()?;
        let registers = core.registers();
        let lower = name.to_lowercase();

        fn by_role<'r>(
            registers: &'r probe_rs::CoreRegisters,
            role: RegisterRole,
        ) -> Option<&'r probe_rs::CoreRegister> {
            registers
                .all_registers()
                .find(|r| r.register_has_role(role))
        }
        let reg = registers
            .core_registers()
            .find(|r| r.name().eq_ignore_ascii_case(&lower))
            .or_else(|| match lower.as_str() {
                "pc" => registers.pc(),
                "sp" => {
                    by_role(registers, RegisterRole::StackPointer).or_else(|| registers.msp())
                }
                "lr" => by_role(registers, RegisterRole::ReturnAddress),
                "xpsr" | "psr" => by_role(registers, RegisterRole::ProcessorStatus),
                _ => None,
            })
            .or_else(|| registers.other_by_name(&lower))
            .or_else(|| {
                registers
                    .all_registers()
                    .find(|r| r.name().eq_ignore_ascii_case(&lower))
            });

        let reg = reg.ok_or_else(|| BackendError::Unsupported(format!("未知寄存器 {name}")))?;
        let value: u64 = core
            .read_core_reg(reg.id())
            .map_err(Self::map_err)?;
        Ok(value)
    }
}
