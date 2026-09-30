//! probe-rs 后端：进程内直接操作调试探针（替代原版 PyOCD 库调用）。

use probe_rs::{CoreInterface, MemoryInterface, Permissions, Session};
use std::time::{Duration, Instant};

use crate::{BackendError, BurstFrames, DebugBackend};

/// `session.core()` 连续失败到该次数才升级为"连接丢失"。
/// 单次失败按瞬时错误处理（该方法每次都会重新 attach 访问端口，失败未必是断线）。
const CORE_FAIL_STREAK_LIMIT: u32 = 5;

/// 校验 [addr, addr+len) 落在可访问范围内（读/写共用）。
/// - 地址不在任何已映射区域/白名单内 → Err（越界访问会把探针留在 FAULT 态）；
/// - 请求跨出区域末尾 → Err（截断会让上层拿到"短了却当完整"的数据）。
fn checked_access_len(core: &probe_rs::Core, addr: u64, len: usize) -> Result<usize, BackendError> {
    let limit = if let Some(r) = core.memory_regions().find(|r| r.contains(addr)) {
        Some(r.address_range().end)
    } else if (PPB_LO..PPB_HI).contains(&addr) {
        // PPB / SCS（调试寄存器区）不在 target 的 memory_regions 里，但必须可访问
        Some(PPB_HI)
    } else if (PERIPH_LO..PERIPH_HI).contains(&addr) {
        // 外设寄存器区（MMIO，SVD 读寄存器）
        Some(PERIPH_HI)
    } else if (EXT_RAM_LO..EXT_RAM_HI).contains(&addr) {
        // 外扩设备 / RAM 区（FMC/FSMC）
        Some(EXT_RAM_HI)
    } else {
        None
    };
    let Some(limit) = limit else {
        return Err(BackendError::Transfer(format!(
            "地址 0x{addr:08x} 不在任何已映射内存区域内，已拒绝下发（越界访问会让探针进故障态）"
        )));
    };
    let avail = (limit - addr) as usize;
    if len > avail {
        return Err(BackendError::Transfer(format!(
            "访问请求 0x{addr:08x}+{len}B 跨出区域末尾（上界 0x{limit:08x}），已拒绝下发"
        )));
    }
    Ok(len)
}

/// PPB / SCS（调试寄存器区）：不在 target 的 memory_regions 里，但必须允许访问
const PPB_LO: u64 = 0xE000_0000;
const PPB_HI: u64 = 0xE010_0000;

/// ARM Cortex-M 外设寄存器区（MMIO，SVD / 硬件外设）：不在 target 的 memory_regions 里，必须允许访问
const PERIPH_LO: u64 = 0x4000_0000;
const PERIPH_HI: u64 = 0x6000_0000;

/// 外部设备与 RAM 区（FMC/FSMC，如外扩 SRAM/NOR/NAND）：
const EXT_RAM_LO: u64 = 0x6000_0000;
const EXT_RAM_HI: u64 = 0xA000_0000;

/// 示波读诊断计数（scope_perf 暴露）
pub static CORE_ACQUIRE_US: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
pub static SCOPE_READ32_RETRIES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
pub static SCOPE_BLOCKS_DEGRADED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub struct ProbeRsBackend {
    target: String,
    speed_hz: u32,
    /// 按序列号选择探针（多探针系统；None = 第一个可用）
    probe_serial: Option<String>,
    session: Option<Session>,
    /// core() 连续失败计数
    core_fail_streak: u32,
}

impl ProbeRsBackend {
    pub fn new(target: String, speed_hz: u32) -> Self {
        Self {
            target,
            speed_hz,
            probe_serial: None,
            session: None,
            core_fail_streak: 0,
        }
    }

    /// 按序列号选择探针（多探针系统；None = 第一个可用）。
    pub fn with_serial(mut self, serial: Option<String>) -> Self {
        self.probe_serial = serial.filter(|s| !s.trim().is_empty());
        self
    }

    /// 直接接管一个已存在的会话（烧录后复用连接等场景）。
    pub fn with_session(target: String, speed_hz: u32, session: Session) -> Self {
        Self {
            target,
            speed_hz,
            probe_serial: None,
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
        let t0 = std::time::Instant::now();
        match session.core(0) {
            Ok(core) => {
                CORE_ACQUIRE_US.fetch_add(t0.elapsed().as_micros() as u64, std::sync::atomic::Ordering::Relaxed);
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
        let (mut probe, ident) = crate::probe::open_first_available(self.probe_serial.as_deref())
            .map_err(BackendError::ConnectionLost)?;
        // 亚 kHz 输入整除得 0，set_speed(0) 无效且错误被吞，最终静默用探针默认
        // 速率、与 Connected 事件报告的 "@0kHz" 自相矛盾——钳到 1kHz
        let _ = probe.set_speed((self.speed_hz / 1000).max(1));
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
        // **地址不在任何区域内时拒绝下发**，请求跨出区域末尾时显式报错。
        //
        // 越界访问不会干净地返回错误：实测 G431 + CMSIS-DAP 克隆，读一次 SRAM 之外
        // 的地址会让目标回 FAULT 响应，**把访问端口留在故障态** —— 之后连
        // `session.core()` 都失败（Arm(Dap(FaultResponse))），引擎遂判"连接丢失"，
        // 断线重连后又触发同样的越界读，形成无限重连。
        // 跨界静默截断同样有害：上层会拿"短了却当完整"的缓冲继续解码（如
        // conditions::decode_scalar 按实际短长度解出错误数值），宁可显式失败。
        let len = checked_access_len(&core, addr, len)?;
        if len == 0 {
            return Ok(Vec::new());
        }
        if addr.is_multiple_of(4) && len % 4 == 0 {
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
        // 写路径与读路径同源校验：越界写同样会把探针留在 FAULT 态且可能写坏目标
        checked_access_len(&core, addr, data.len())?;
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
    ) -> Result<BurstFrames, BackendError> {
        let start = Instant::now();
        let mut core = self.core()?;
        let mut out = Vec::with_capacity(count);
        // 帧节拍：读耗时超过间隔时，截止时刻重锚到"当前 + 间隔"，不追赶旧截止——
        // 旧实现超期后沿用旧截止连续快速读取，批内间隔忽快忽慢
        let mut due = start;
        for _i in 0..count {
            if Instant::now() < due {
                std::thread::sleep(due - Instant::now());
            }
            // 帧时间戳 = 本帧读取开始时刻（样本窗口起点）。旧实现取全部块读完
            // 后的时刻，回包等待与调度延迟被混进时间戳（真机实测：Agent 时间戳
            // 间隔与 MCU tick 间隔严重错位 56ms/6ms）
            let frame_ts = start.elapsed();
            let mut frame = Vec::with_capacity(blocks.len());
            for (addr, len) in blocks {
                // 同 read_bytes：地址不在任何区域内就**不要下发**，
                // 否则会让访问端口进故障态（示波目标地址配错时尤其致命）
                let len = match core.memory_regions().find(|r| r.contains(*addr)) {
                    Some(r) => {
                        let avail = (r.address_range().end - addr) as usize;
                        if avail < *len {
                            // 与 read_bytes 的显式报错策略一致：静默截断会让
                            // extract_from_blocks 因数据不足把该目标从所有样本
                            // 中静默丢弃（示波目标贴 RAM 顶端时正是配错地址，
                            // 最需要报错的场景）
                            return Err(BackendError::Transfer(format!(
                                "采样范围 0x{addr:08x}+{len}B 越出区域末尾（仅剩 {avail}B），已拒绝下发"
                            )));
                        }
                        *len
                    }
                    // 白名单区同样做末端校验（与 read_bytes 策略一致）：跨出上界
                    // 会让访问端口进故障态，宁可显式报错走避让
                    None if (PPB_LO..PPB_HI).contains(addr) => {
                        let avail = (PPB_HI - addr) as usize;
                        if avail < *len {
                            return Err(BackendError::Transfer(format!(
                                "采样范围 0x{addr:08x}+{len}B 越出 PPB 区末尾（仅剩 {avail}B），已拒绝下发"
                            )));
                        }
                        *len
                    }
                    None if (PERIPH_LO..PERIPH_HI).contains(addr) => {
                        let avail = (PERIPH_HI - addr) as usize;
                        if avail < *len {
                            return Err(BackendError::Transfer(format!(
                                "采样范围 0x{addr:08x}+{len}B 越出外设区末尾（仅剩 {avail}B），已拒绝下发"
                            )));
                        }
                        *len
                    }
                    None if (EXT_RAM_LO..EXT_RAM_HI).contains(addr) => {
                        let avail = (EXT_RAM_HI - addr) as usize;
                        if avail < *len {
                            return Err(BackendError::Transfer(format!(
                                "采样范围 0x{addr:08x}+{len}B 越出外扩 RAM 末尾（仅剩 {avail}B），已拒绝下发"
                            )));
                        }
                        *len
                    }
                    None => {
                        return Err(BackendError::Transfer(format!(
                            "采样地址 0x{addr:08x} 不在任何已映射内存区域内，已拒绝下发"
                        )))
                    }
                };
                // 32-bit 字读在总线层面原子（无撕裂）；失败多为瞬态（USB 忙/超时），
                // 重试一次。**不回退 read_8**：逐字节访问非原子，固件在字节间写入
                // 会产生撕裂值（真机实测 sin_20hz 坑洼：符号位翻转的假值）。
                // 重试仍失败 → 本块降级为空（该块目标本帧缺值，其它块不受影响）。
                if *addr % 4 == 0 && len % 4 == 0 && len > 0 {
                    let mut words = vec![0u32; len / 4];
                    let mut ok = core.read_32(*addr, &mut words).is_ok();
                    if !ok {
                        SCOPE_READ32_RETRIES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        // 冲刺读消耗 DAP 可能残留的队列结果：失败后直接重试，
                        // DAP 会把上一次排队未取走的数据字重复返回
                        let mut dummy = vec![0u32; len / 4];
                        let _ = core.read_32(*addr, &mut dummy);
                        ok = core.read_32(*addr, &mut words).is_ok();
                    }
                    if ok {
                        let mut buf = Vec::with_capacity(len);
                        for w in words {
                            buf.extend_from_slice(&w.to_le_bytes());
                        }
                        frame.push(buf);
                        continue;
                    }
                    SCOPE_BLOCKS_DEGRADED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    frame.push(Vec::new());
                    continue;
                }
                // 非 4 对齐块（理论不出现，merge_blocks 按 4 对齐合并）：保留 8-bit
                let mut buf = vec![0u8; len];
                if len > 0 {
                    core.read_8(*addr, &mut buf).map_err(Self::map_err)?;
                }
                frame.push(buf);
            }
            out.push((frame_ts, frame));
            // 节拍推进：读取在截止前完成 → 沿用原节拍（间隔严格均匀）；
            // 超期（读耗时超过间隔）→ 重锚到"当前 + 间隔"，不追赶旧截止
            let now = Instant::now();
            due = if now > due { now + interval } else { due + interval };
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

        fn by_role(
            registers: &probe_rs::CoreRegisters,
            role: RegisterRole,
        ) -> Option<&probe_rs::CoreRegister> {
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
