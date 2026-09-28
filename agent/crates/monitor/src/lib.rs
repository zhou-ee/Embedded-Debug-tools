//! 监视引擎：独立线程驱动调试后端，双通道采样（Watch 5Hz / Scope 高频），
//! 移植原版 workers/proc_worker.py 的 MonitorProcess（无 GIL、无进程序列化开销）。

pub mod bandwidth;
pub mod conditions;
pub mod thumb;

use crossbeam_channel::{unbounded, Receiver, Sender, TryRecvError};
use debug_core::openocd::OpenOcdBackend;
use debug_core::probers_backend::ProbeRsBackend;
use debug_core::{BackendError, BackendKind, ConnectParams, DebugBackend, TargetState};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Watch 内存目标（由 UI 侧 ELF 解析得出地址）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemTarget {
    pub id: String,
    pub addr: u64,
    pub size: u32,
    /// 运行中是否自动刷新（5Hz）；false 仅在 halt 时读取
    #[serde(default)]
    pub auto_refresh: bool,
}

/// 示波器目标。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScopeTarget {
    pub addr: u64,
    pub size: u32,
}

/// 断点定义（含条件表达式源码，求值上下文由引擎组装）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BreakpointDef {
    pub addr: u64,
    #[serde(default)]
    pub condition: String,
}

/// 条件断点可引用的符号（名称 → 地址/大小/是否有符号）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SymbolRef {
    pub name: String,
    pub addr: u64,
    pub size: u32,
    #[serde(default)]
    pub signed: bool,
}

#[derive(Debug)]
pub enum Command {
    Connect(ConnectParams),
    Disconnect,
    UpdateWatchTargets(Vec<MemTarget>),
    UpdateScopeTargets(Vec<ScopeTarget>),
    SetScopeFreq(f64),
    SetWatchFreq(f64),
    SetBreakpoints(Vec<BreakpointDef>),
    SetSymbolRefs(Vec<SymbolRef>),
    Halt,
    Resume,
    Step,
    /// Step Over：优先下一源码行临时断点（frontend 由 ELF 行表计算），
    /// 无行表信息时按 Thumb 调用指令决定（call → 落点临时断点，否则单步）。
    StepOver { next_line_addr: Option<u64> },
    /// Step Out：LR 位置临时断点。
    StepOut,
    Reset,
    /// 设置数据观察点（DWT 比较器，读/写访问时停机；最多 4 个）
    SetWatchpoints(Vec<u64>),
    /// 读回**硬件里实际武装着**的观察点地址（DWT_COMPn + FUNCTIONn 真值）。
    /// 用于让 UI 状态与硬件对齐 —— UI 自己记的列表可能与硬件脱节
    /// （下发失败、外部改寄存器、会话重建），只看本地状态会出现"取消了却还在停"
    /// 且界面上再也找不到清除入口的死局。
    ReadWatchpoints {
        reply: Sender<Vec<u64>>,
    },
    /// 同步写内存：写入结果必须回传调用方——此前返回值被丢弃，
    /// 探针未连接/地址不可写时插件也收到 ok，用户误以为已改成功
    WriteMemSync {
        addr: u64,
        data: Vec<u8>,
        reply: Sender<Result<(), String>>,
    },
    /// 同步读内存（指针追踪 / STL 展开 / 数组视图）
    ReadMemSync {
        addr: u64,
        size: usize,
        reply: Sender<Result<Vec<u8>, String>>,
    },
    RequestRegsAndStack,
    Shutdown,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", rename_all_fields = "camelCase", tag = "kind")]
pub enum Event {
    Connected {
        description: String,
    },
    Disconnected {
        reason: String,
    },
    State {
        state: TargetState,
    },
    /// Watch 数据：id → 字节
    WatchData {
        values: HashMap<String, Vec<u8>>,
        halted: bool,
    },
    /// 示波器批量采样：[(秒时间戳, addrHex → 字节)]
    ScopeData {
        samples: Vec<ScopeSample>,
    },
    BreakpointHit {
        pc: u64,
        #[serde(skip_serializing_if = "Option::is_none")]
        condition_error: Option<String>,
    },
    RegsAndStack {
        regs: HashMap<String, u64>,
        stack_base: u64,
        stack: Vec<u8>,
    },
    Error {
        message: String,
    },
    Log {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScopeSample {
    pub t: f64,
    pub values: HashMap<String, Vec<u8>>,
}

pub struct MonitorHandle {
    pub cmd_tx: Sender<Command>,
    pub event_rx: Receiver<Event>,
    join: Option<std::thread::JoinHandle<()>>,
}

impl MonitorHandle {
    pub fn shutdown(&mut self) {
        let _ = self.cmd_tx.send(Command::Shutdown);
        if let Some(handle) = self.join.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for MonitorHandle {
    fn drop(&mut self) {
        self.shutdown();
    }
}

pub fn spawn_engine() -> MonitorHandle {
    let (cmd_tx, cmd_rx) = unbounded::<Command>();
    let (event_tx, event_rx) = unbounded::<Event>();
    let join = std::thread::Builder::new()
        .name("monitor-engine".into())
        .spawn(move || {
            // panic 隔离：引擎线程若因后端库 panic 而死，此前所有 send 静默失败、
            // 事件通道无任何告知，UI 表现为"连接还在但永远无响应"。捕获后至少
            // 发一条 Error 事件让用户看到，且 unwind 过程中 Engine 字段正常 drop
            // （probe-rs Session 析构会释放探针）。
            let panic_tx = event_tx.clone();
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                let mut engine = Engine::new(cmd_rx, event_tx);
                engine.run();
            }));
            if let Err(payload) = result {
                let msg = if let Some(s) = payload.downcast_ref::<&str>() {
                    (*s).to_string()
                } else if let Some(s) = payload.downcast_ref::<String>() {
                    s.clone()
                } else {
                    "未知 panic".to_string()
                };
                let _ = panic_tx.send(Event::Error {
                    message: format!("监视引擎线程异常退出（panic）: {msg}；请重新连接"),
                });
            }
        })
        .expect("spawn monitor engine");
    MonitorHandle {
        cmd_tx,
        event_rx,
        join: Some(join),
    }
}

// Cortex-M 调试寄存器（内存映射，**必须 32 位访问**——字节访问会被硬件静默
// 忽略，真机实测 G431：经 write_8 写 DWT_COMP0 0xDEADBEEF 只落地最高字节。
// 故一律经 `DebugBackend::read_u32`/`write_u32`，不要用 read_bytes/write_bytes）
const DEMCR: u64 = 0xE000_EDFC; // bit24 TRCENA
const DWT_COMP: u64 = 0xE000_1020; // COMP[n] = +0x10*n
const DWT_MASK: u64 = 0xE000_1024;
const DWT_FUNCTION: u64 = 0xE000_1028; // bit24 MATCHED
/// DATAVSIZE=0b10（4 字节），FUNCTION=0b0110（数据地址观察点，读或写触发停机）。
/// 真机实测（G431）：0b0110 读写都停机；原用的 0b0111 只对写触发，且按
/// ARMv7-M 属保留编码；0b0011/0b0100 只置 MATCHED 不停机（ITM 数据追踪语义）；
/// 0b0101 只对写停机。
const DWT_FN_DATA_RW_4B: u32 = (0b10 << 10) | 0b0110;
const DFSR: u64 = 0xE000_ED30; // bit2 DWTTRAP（写 1 清除）
const CFSR: u64 = 0xE000_ED28; // 粘滞故障标志（写 1 清除）
const HFSR: u64 = 0xE000_ED2C;
const BFAR: u64 = 0xE000_ED38; // 总线错误地址寄存器
const MAX_WATCHPOINTS: usize = 4;
pub const DEFAULT_WATCH_FREQ: f64 = 5.0;
#[allow(dead_code)]
pub const WATCH_INTERVAL: Duration = Duration::from_millis(200); // 5 Hz (默认)
const STATE_POLL_INTERVAL: Duration = Duration::from_millis(150);
const SCOPE_BATCH_INTERVAL: Duration = Duration::from_millis(33);
const RECONNECT_INTERVAL: Duration = Duration::from_secs(1);
/// 栈读取门槛（对照原版 sp > 0x10000000）
const STACK_MIN_SP: u64 = 0x1000_0000;
const STACK_BYTES: usize = 512;

struct Engine {
    cmd_rx: Receiver<Command>,
    event_tx: Sender<Event>,
    backend: Option<Box<dyn DebugBackend>>,
    params: Option<ConnectParams>,
    watch_targets: Vec<MemTarget>,
    watch_freq: f64,
    scope_targets: Vec<ScopeTarget>,
    scope_freq: f64,
    breakpoints: Vec<BreakpointDef>,
    symbol_refs: Vec<SymbolRef>,
    /// 已写入目标的断点地址
    applied_bps: Vec<u64>,
    /// 硬件断点配额（已用, 总数）；连接后查询一次
    bp_quota: Option<(usize, usize)>,
    /// DWT 数据观察点地址（最多 4 个）
    watchpoints: Vec<u64>,
    /// 上次回报给用户的"硬件实际武装列表"，避免重复刷同一条日志
    last_watchpoints_reported: Vec<u64>,
    /// 停住瞬间取样的 DWT 匹配状态 `(DFSR, FUNCTION0..3)`。
    ///
    /// **必须在 `poll_state` 入口、任何其他 PPB 访问之前取样**：真机实测（2026-09-16）
    /// 这两个标志会被随后的 PPB 访问清掉 —— 进 `poll_state` 时 `DFSR` 还是 `0x5`，
    /// 读完 DHCSR（`is_halted()`）就变成 0；`MATCHED` 更早，在武装期的读回之后就没了。
    /// 结果就是"武装后立刻命中"的那次停住拿不到归属（只报 `已停住 @ …`）。
    /// 详见 `docs/HANDOFF-20260915.md` §11.5。
    pending_dwt: Option<(u32, Vec<u32>)>,
    /// 上一次停住时刻：短时间反复停住 = 热点，用来判定是否给出解释性告警
    last_halt_at: Option<Instant>,
    /// 热点告警每次武装只提示一次，避免刷屏
    hot_warned: bool,
    /// 观察点语义说明（地址匹配 / 非精确停止位置）每个会话只提示一次
    watch_hint_shown: bool,
    last_state: Option<bool>, // Some(halted)
    epoch: Instant,
    last_watch_sample: Option<Instant>,
    next_watch: Instant,
    next_scope: Instant,
    next_state_poll: Instant,
    next_reconnect: Instant,
    scope_buffer: Vec<ScopeSample>,
    next_scope_flush: Instant,
    /// 示波读块缓存（targets/freq 变化时重建，避免每次采样重算合并）
    scope_blocks: Vec<bandwidth::MemBlock>,
    /// 命中停住后挂起的断点地址（防重复上报）
    held_break_addr: Option<u64>,
    /// 步进用临时断点（停住后清除）
    temp_bps: Vec<u64>,
    /// 非致命错误限流（同文本 1 秒内只上报一条）
    last_error_text: String,
    last_error_at: Instant,
    shutdown: bool,
}

impl Engine {
    fn new(cmd_rx: Receiver<Command>, event_tx: Sender<Event>) -> Self {
        let now = Instant::now();
        Self {
            cmd_rx,
            event_tx,
            backend: None,
            params: None,
            watch_targets: Vec::new(),
            watch_freq: 5.0,
            scope_targets: Vec::new(),
            scope_freq: 50.0,
            breakpoints: Vec::new(),
            symbol_refs: Vec::new(),
            applied_bps: Vec::new(),
            bp_quota: None,
            watchpoints: Vec::new(),
            last_watchpoints_reported: Vec::new(),
            pending_dwt: None,
            last_halt_at: None,
            hot_warned: false,
            watch_hint_shown: false,
            last_state: None,
            epoch: now,
            last_watch_sample: None,
            next_watch: now,
            next_scope: now,
            next_state_poll: now,
            next_reconnect: now,
            scope_buffer: Vec::new(),
            next_scope_flush: now,
            scope_blocks: Vec::new(),
            held_break_addr: None,
            temp_bps: Vec::new(),
            // 空文本与任何错误都不同，首条错误必然放行
            last_error_text: String::new(),
            last_error_at: now,
            shutdown: false,
        }
    }

    fn emit(&self, ev: Event) {
        let _ = self.event_tx.send(ev);
    }

    fn run(&mut self) {
        // Windows 默认定时器粒度 ~15.6ms，高频采样必须提高到 1ms
        #[cfg(windows)]
        unsafe {
            windows_sys::Win32::Media::timeBeginPeriod(1);
        }

        while !self.shutdown {
            self.drain_commands();
            if self.shutdown {
                break;
            }

            if self.backend.is_none() && self.params.is_some() {
                self.try_reconnect();
            }

            let mut next_due: Option<Instant> = None;
            let track = |t: Instant, cond: bool, due: &mut Option<Instant>| {
                if cond {
                    *due = Some(due.map_or(t, |d: Instant| d.min(t)));
                }
            };

            if self.backend.is_some() {
                let scope_active = !self.scope_targets.is_empty();

                // 1. 变量监视（2/5/10/15Hz）：单次读取开销极小（~1-2ms），优先按时执行，
                //    避免被 20~40ms 的示波突发阻塞窗口抢先占用导致监视节拍被强制推迟数十毫秒。
                let now = Instant::now();
                let watch_interval = Duration::from_secs_f64(1.0 / self.watch_freq.clamp(1.0, 50.0));
                let cooldown_margin = (watch_interval / 5).max(Duration::from_millis(5));
                let min_cooldown = watch_interval.saturating_sub(cooldown_margin);
                let cooled_down = match self.last_watch_sample {
                    Some(last) => now.duration_since(last) >= min_cooldown,
                    None => true,
                };
                if !self.watch_targets.is_empty() && now >= self.next_watch && cooled_down {
                    self.last_watch_sample = Some(now);
                    self.next_watch = if now > self.next_watch + watch_interval {
                        now + watch_interval
                    } else {
                        self.next_watch + watch_interval
                    };
                    self.sample_watch();
                }

                // 2. 状态轮询（10Hz）：及时探测目标 halt/running 与断点命中
                let now = Instant::now();
                if now >= self.next_state_poll {
                    self.next_state_poll = now + STATE_POLL_INTERVAL;
                    self.poll_state();
                }

                // 3. 示波采样：无漂移节拍突发连读
                let now = Instant::now();
                if scope_active && now >= self.next_scope {
                    self.sample_scope();
                }

                // 4. 批量推送（33ms）——仅在示波激活或缓冲区存在未推送样本时调度，杜绝空转打断监视节拍
                let scope_flush_active = scope_active || !self.scope_buffer.is_empty();
                let now = Instant::now();
                if scope_flush_active && now >= self.next_scope_flush {
                    self.next_scope_flush = now + SCOPE_BATCH_INTERVAL;
                    self.flush_scope();
                }

                track(self.next_scope, scope_active, &mut next_due);
                track(self.next_scope_flush, scope_flush_active, &mut next_due);
                track(self.next_state_poll, true, &mut next_due);
                track(self.next_watch, !self.watch_targets.is_empty(), &mut next_due);
            }

            // 4. 自适应等待：粗睡到临近，活跃采样（示波或变量监视）忙等收尾保证采样精度
            let active_sampling = !self.scope_targets.is_empty() || !self.watch_targets.is_empty();
            let wait_cap = if active_sampling {
                Duration::from_millis(100)
            } else {
                Duration::from_millis(20)
            };
            match next_due {
                Some(due) => {
                    let now = Instant::now();
                    if due > now {
                        let remain = (due - now).min(wait_cap);
                        if remain > Duration::from_micros(2500) {
                            std::thread::sleep(remain - Duration::from_micros(1800));
                        } else if active_sampling {
                            // 活跃采样（高频示波或监视）最后 <= 2.5ms 忙等到点，彻底杜绝 Windows 毫秒睡眠超期
                            while Instant::now() < due && !self.shutdown {
                                std::hint::spin_loop();
                            }
                        } else {
                            std::thread::sleep(remain);
                        }
                    }
                }
                None => std::thread::sleep(Duration::from_millis(5)),
            }
        }

        // 退出前清理（顺序重要）：
        // 1) 清除本会话下发的全部硬件断点——FPB 比较器不随会话结束而消失，
        //    遗留会导致目标复位后在断点处莫名停止、调试器状态灯异常；
        // 2) halt 态则恢复运行——停止监视后设备应继续工作而非冻结；
        // 3) 断开后端连接。
        // 清除 DWT 观察点比较器（FUNCTION=0 停用）
        for n in 0..MAX_WATCHPOINTS {
            let _ = self
                .backend
                .as_mut()
                .map(|b| b.write_u32(DWT_FUNCTION + 0x10 * n as u64, 0));
        }
        let leftover_bps: Vec<u64> = self
            .applied_bps
            .iter()
            .chain(self.temp_bps.iter())
            .copied()
            .collect();
        if let Some(b) = self.backend.as_mut() {
            for addr in &leftover_bps {
                let _ = b.clear_breakpoint(*addr);
            }
            if b.is_halted().unwrap_or(false) {
                let _ = b.resume();
            }
        }
        self.applied_bps.clear();
        self.temp_bps.clear();
        if let Some(mut b) = self.backend.take() {
            b.disconnect();
        }
        #[cfg(windows)]
        unsafe {
            windows_sys::Win32::Media::timeEndPeriod(1);
        }
    }

    fn drain_commands(&mut self) {
        loop {
            match self.cmd_rx.try_recv() {
                Ok(cmd) => self.handle_command(cmd),
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    self.shutdown = true;
                    break;
                }
            }
        }
    }

    fn handle_command(&mut self, cmd: Command) {
        match cmd {
            Command::Connect(params) => {
                self.params = Some(params);
                self.backend = None;
                self.bp_quota = None;
                self.next_reconnect = Instant::now();
                self.try_reconnect();
            }
            Command::Disconnect => {
                if let Some(mut b) = self.backend.take() {
                    b.disconnect();
                }
                self.params = None;
                self.last_state = None;
                self.emit(Event::Disconnected {
                    reason: "用户断开".into(),
                });
            }
            Command::UpdateWatchTargets(targets) => {
                let was_empty = self.watch_targets.is_empty();
                self.watch_targets = targets;
                if was_empty && !self.watch_targets.is_empty() {
                    self.next_watch = Instant::now();
                }
            }
            Command::UpdateScopeTargets(targets) => {
                self.scope_targets = targets;
                self.rebuild_scope_blocks();
                self.next_scope = Instant::now();
            }
            Command::SetScopeFreq(freq) => {
                self.scope_freq = freq.clamp(1.0, 5000.0);
                self.rebuild_scope_blocks();
                self.next_scope = Instant::now();
            }
            Command::SetWatchFreq(freq) => {
                // clamp 对 NaN 返回 NaN → Duration::from_secs_f64 panic（库直调可达，
                // 引擎线程死亡且上层静默）；非有限值一律回落默认频率
                let clamped = if freq.is_finite() {
                    freq.clamp(1.0, 50.0)
                } else {
                    DEFAULT_WATCH_FREQ
                };
                self.watch_freq = clamped;
                let watch_interval = Duration::from_secs_f64(1.0 / clamped);
                let now = Instant::now();
                if let Some(last) = self.last_watch_sample {
                    let next_allowed = last + watch_interval;
                    self.next_watch = if next_allowed > now { next_allowed } else { now };
                } else {
                    self.next_watch = now;
                }
            }
            Command::SetBreakpoints(bps) => {
                self.breakpoints = bps;
                self.apply_breakpoints();
            }
            Command::SetWatchpoints(addrs) => {
                self.program_watchpoints(&addrs);
                // 回报硬件**实际**状态（可能因比较器不可用而少于请求）：
                // 让"我点了清除"与"硬件里还剩什么"在控制台里一目了然
                let armed = self.read_watchpoints();
                if armed != self.last_watchpoints_reported {
                    self.last_watchpoints_reported = armed.clone();
                    self.emit(Event::Log {
                        message: if armed.is_empty() {
                            "数据观察点：已清除".to_string()
                        } else {
                            format!(
                                "数据观察点：已武装 [{}]",
                                armed
                                    .iter()
                                    .map(|a| format!("0x{a:08x}"))
                                    .collect::<Vec<_>>()
                                    .join(", ")
                            )
                        },
                    });
                }
            }
            Command::ReadWatchpoints { reply } => {
                let _ = reply.send(self.read_watchpoints());
            }
            Command::SetSymbolRefs(refs) => {
                self.symbol_refs = refs;
            }
            Command::Halt => {
                self.with_backend(|b| b.halt());
                self.poll_state();
            }
            Command::Resume => {
                self.held_break_addr = None;
                // 只有 resume 实际成功才置 running 边沿：Transfer 失败时
                // 目标仍停着，发假 Running 会让下次 poll 出假边沿重报同一次停住
                if self.resume_over_breakpoint() {
                    self.mark_running();
                }
                self.poll_state();
            }
            Command::Step => {
                self.step_over_breakpoint();
                // 单步落点恰为另一断点时（断点行只剩一条指令常见），
                // halted→halted 无边沿会漏报命中：与 step_over/step_out 一致，
                // 强制置 running 边沿让 poll_state 走完整上报。
                // 注意 landed_on_breakpoint 必须在 poll_state 之前调用（置边沿），
                // 两条路径都要 poll_state，差别只在是否补发快照。
                let landed = self.landed_on_breakpoint(None);
                self.poll_state();
                if !landed {
                    self.send_regs_and_stack();
                }
            }
            Command::StepOver { next_line_addr } => {
                self.step_over(next_line_addr);
            }
            Command::StepOut => {
                self.step_out();
            }
            Command::Reset => {
                self.held_break_addr = None;
                self.clear_temp_bps();
                // 标准调试器复位流程：reset halt → 已知停住状态下重下断点
                // （系统复位清空 FPB，且复位进行中写 FPB 会竞态丢失）→ 恢复运行
                self.with_backend(|b| b.reset_and_halt());
                self.applied_bps.clear();
                self.apply_breakpoints();
                if self.backend.is_some()
                    && self.with_backend(|b| b.resume()).is_some() {
                        self.mark_running();
                    }
                self.poll_state();
            }
            Command::WriteMemSync { addr, data, reply } => {
                let result = match self.with_backend(|b| b.write_bytes(addr, &data)) {
                    Some(()) => Ok(()),
                    // None = 未连接，或写入出错（on_backend_error 已限流上报）
                    None => Err("写内存失败（目标未连接或写入报错，详见引擎错误日志）".to_string()),
                };
                // 写入后刷新一次 watch（写的是被监视变量时立刻反映）
                self.next_watch = Instant::now();
                let _ = reply.send(result);
            }
            Command::ReadMemSync { addr, size, reply } => {
                // 这是"请求-应答"语义：错误应当交回**调用方**判断 —— 调用栈回溯把
                // 越界读当作"到达栈顶"从而就地停止扫描，属于预期内的正常终止。
                // 因此不要再走 on_backend_error 发全局错误事件，否则每次回溯都会往
                // 控制台刷一条"已拒绝下发"，用户会以为出故障了。
                // 只有致命错误（连接丢失）仍需上报并处理。
                let outcome = match self.backend.as_mut() {
                    None => Err(BackendError::NotConnected),
                    Some(b) => b.read_bytes(addr, size),
                };
                let result = match outcome {
                    Ok(v) => Ok(v),
                    Err(e) if e.is_fatal() => {
                        let msg = e.to_string();
                        self.on_backend_error(e);
                        Err(msg)
                    }
                    Err(e) => Err(e.to_string()),
                };
                let _ = reply.send(result);
            }
            Command::RequestRegsAndStack => {
                self.send_regs_and_stack();
            }
            Command::Shutdown => {
                self.shutdown = true;
            }
        }
    }

    fn try_reconnect(&mut self) {
        let now = Instant::now();
        if now < self.next_reconnect {
            return;
        }
        self.next_reconnect = now + RECONNECT_INTERVAL;
        let Some(params) = self.params.clone() else { return };

        let mut backend: Box<dyn DebugBackend> = match params.kind {
            BackendKind::ProbeRs => Box::new(
                ProbeRsBackend::new(
                    params.target.clone().unwrap_or_default(),
                    params.speed_hz,
                )
                .with_serial(params.probe_serial.clone()),
            ),
            BackendKind::Openocd => {
                let openocd = params.openocd_path.clone().unwrap_or_else(|| "openocd".into());
                let cfg = params.cfg_file.clone().unwrap_or_default();
                let scripts = params.scripts_dir.clone().map(std::path::PathBuf::from);
                Box::new(
                    OpenOcdBackend::new(openocd, cfg, scripts, params.speed_hz)
                        .with_attach_only(params.attach_only)
                        .with_tcl_port(params.tcl_port),
                )
            }
            BackendKind::Sim => Box::new(debug_core::sim::SimBackend::new()),
        };

        match backend.connect() {
            Ok(()) => {
                self.backend = Some(backend);
                self.last_state = None;
                self.emit(Event::Connected {
                    description: match params.kind {
                        // 带上实际生效的 SWD 时钟：用户改了速度配置后能在此核对
                        BackendKind::ProbeRs => format!(
                            "probe-rs 已连接 {} @{}kHz",
                            params.target.as_deref().unwrap_or("auto"),
                            params.speed_hz / 1000
                        ),
                        BackendKind::Openocd => {
                            format!("OpenOCD 已连接 (Tcl {}) @{}kHz", params.tcl_port, params.speed_hz / 1000)
                        }
                        BackendKind::Sim => {
                            "仿真后端已连接（信号区 0x20000000-0x200000FF）".into()
                        }
                    },
                });
                self.apply_breakpoints();
                // 重连后一并把已配置的数据观察点重新编程（与断点同等待遇，
                // 否则探针掉线重连后 UI 显示观察点仍在、硬件里其实已丢）
                let wps = self.watchpoints.clone();
                if !wps.is_empty() {
                    self.program_watchpoints(&wps);
                }
            }
            Err(e) => {
                if params.attach_only {
                    // attach-only 不自动重试（探针可能被调试器占用），但必须发事件：
                    // 此前静默清空 params，UI 永远等不到 Disconnected，只能靠超时猜
                    self.emit(Event::Disconnected {
                        reason: format!("连接失败（attach-only 模式不自动重试）: {e}"),
                    });
                    self.params = None;
                } else {
                    self.emit(Event::Log {
                        message: format!("连接失败，1 秒后重试: {e}"),
                    });
                }
            }
        }
    }

    fn on_backend_error(&mut self, err: BackendError) {
        if err.is_fatal() {
            if let Some(mut b) = self.backend.take() {
                b.disconnect();
            }
            self.applied_bps.clear();
            self.last_state = None;
            if self.params.as_ref().is_some_and(|p| p.attach_only) {
                self.params = None;
            }
            self.emit(Event::State {
                state: TargetState::Disconnected,
            });
            self.emit(Event::Disconnected {
                reason: err.to_string(),
            });
        } else {
            // 同文本限流：坏地址的采样目标会以采样频率（最高 1kHz）持续失败，
            // 不限流会刷爆事件通道和前端控制台渲染
            let message = err.to_string();
            let now = Instant::now();
            if message != self.last_error_text
                || now >= self.last_error_at + Duration::from_secs(1)
            {
                self.last_error_text = message.clone();
                self.last_error_at = now;
                self.emit(Event::Error { message });
            }
        }
    }

    fn with_backend<T>(
        &mut self,
        f: impl FnOnce(&mut Box<dyn DebugBackend>) -> Result<T, BackendError>,
    ) -> Option<T> {
        match f(self.backend.as_mut()?) {
            Ok(v) => Some(v),
            Err(e) => {
                self.on_backend_error(e);
                None
            }
        }
    }

    fn apply_breakpoints(&mut self) {
        if self.backend.is_none() {
            return;
        }
        // 硬件断点配额（每会话查询一次；probe-rs 可查，OpenOCD 无法可靠查询）
        if self.bp_quota.is_none() {
            self.bp_quota = self.backend.as_mut().and_then(|b| b.hw_breakpoint_quota());
        }
        let desired: Vec<u64> = self.breakpoints.iter().map(|b| b.addr & !1).collect();
        let applied = self.applied_bps.clone();
        for addr in &applied {
            if !desired.contains(addr) {
                self.with_backend(|b| b.clear_breakpoint(*addr));
            }
        }
        // 只把实际写入成功的地址记入 applied_bps：
        // 失败项留在账外等下次重试；fatal 断线时 on_backend_error 已清空
        // applied_bps，此处直接返回避免用旧列表覆写（否则重连后断点静默丢失）
        let mut next_applied: Vec<u64> = Vec::with_capacity(desired.len());
        for addr in &desired {
            if self.backend.is_none() {
                return;
            }
            if applied.contains(addr) {
                next_applied.push(*addr);
                continue;
            }
            // 配额已用尽：后续断点必然失败，提示并停止尝试
            if let Some((used, total)) = self.bp_quota {
                if next_applied.len() >= total {
                    self.emit(Event::Log {
                        message: format!(
                            "硬件断点配额用尽（{used}/{total}）：0x{addr:08x} 未设置，已停用"
                        ),
                    });
                    break;
                }
            }
            if self.with_backend(|b| b.set_breakpoint(*addr)).is_some() {
                next_applied.push(*addr);
            } else {
                let note = self
                    .bp_quota
                    .map(|(used, total)| format!("（硬件断点已用 {used}/{total}）"))
                    .unwrap_or_default();
                self.emit(Event::Log {
                    message: format!("设置断点 0x{addr:08x} 失败{note}"),
                });
            }
        }
        if self.backend.is_none() {
            return;
        }
        self.applied_bps = next_applied;
    }

    fn poll_state(&mut self) {
        // DWT 匹配状态取样：**必须在 is_halted() 之前**（见 pending_dwt 字段注释）——
        // 读 DHCSR / 重新 attach 访问端口会把标志清掉。
        // 只在武装了观察点时取样，避免给普通会话增加 PPB 访问。
        if self.watchpoints.is_empty() {
            self.pending_dwt = None;
        } else {
            let fresh = self.sample_dwt();
            // 仅当"新取样也有命中信号"或"手上还没有取样"时覆盖：武装期（更早）那次取样
            // 若已经看到标志，它的价值高于这里可能已被清干净的读数。
            if Self::dwt_looks_hit(fresh.0, &fresh.1) || self.pending_dwt.is_none() {
                self.pending_dwt = Some(fresh);
            }
        }
        let Some(halted) = self.with_backend(|b| b.is_halted()) else { return };
        let changed = self.last_state != Some(halted);
        if changed {
            self.last_state = Some(halted);
            self.emit(Event::State {
                state: if halted {
                    TargetState::Halted
                } else {
                    TargetState::Running
                },
            });
            if halted {
                self.clear_temp_bps();
                self.on_halted();
            } else {
                self.held_break_addr = None;
            }
        }
    }

    /// 读取并解码 CFSR/HFSR 故障状态（非零时输出分析，读后写 1 清除，
    /// 下次 halt 只报新故障）。SCB 寄存器为内存映射，两后端一致可用。
    fn report_fault_status(&mut self) {
        let Some(cfsr) = self.with_backend(|b| b.read_u32(CFSR)) else {
            return;
        };
        if cfsr == 0 {
            return;
        }
        let mmfsr = cfsr & 0xFF;
        let bfsr = (cfsr >> 8) & 0xFF;
        let ufsr = (cfsr >> 16) & 0xFFFF;
        let mut lines: Vec<String> = Vec::new();
        if mmfsr & (1 << 0) != 0 {
            lines.push("存储器管理错误：指令访问违例".into());
        }
        if mmfsr & (1 << 1) != 0 {
            lines.push("存储器管理错误：数据访问违例".into());
        }
        if mmfsr & (1 << 3) != 0 {
            lines.push("存储器管理错误：出栈错误".into());
        }
        if mmfsr & (1 << 4) != 0 {
            lines.push("存储器管理错误：入栈错误".into());
        }
        if bfsr & (1 << 0) != 0 {
            lines.push("总线错误：指令取指".into());
        }
        if bfsr & (1 << 1) != 0 {
            lines.push("总线错误：精确数据访问".into());
        }
        if bfsr & (1 << 2) != 0 {
            lines.push("总线错误：不精确数据访问".into());
        }
        if bfsr & (1 << 3) != 0 {
            lines.push("总线错误：出栈错误".into());
        }
        if bfsr & (1 << 4) != 0 {
            lines.push("总线错误：入栈错误".into());
        }
        if bfsr & (1 << 7) != 0 {
            if let Some(a) = self.with_backend(|b| b.read_u32(BFAR)) {
                lines.push(format!("总线错误出错地址：0x{a:08x}"));
            }
        }
        if ufsr & (1 << 0) != 0 {
            lines.push("用法错误：未定义指令".into());
        }
        if ufsr & (1 << 1) != 0 {
            lines.push("用法错误：无效状态（EPSR）".into());
        }
        if ufsr & (1 << 2) != 0 {
            lines.push("用法错误：无效 PC 加载（INVPC）".into());
        }
        if ufsr & (1 << 3) != 0 {
            lines.push("用法错误：协处理器访问（NOCP）".into());
        }
        if ufsr & (1 << 8) != 0 {
            lines.push("用法错误：非对齐访问".into());
        }
        if ufsr & (1 << 9) != 0 {
            lines.push("用法错误：除零".into());
        }
        self.emit(Event::Log {
            message: format!("〖故障分析〗{}", lines.join("；")),
        });
        // 写 1 清除粘滞标志（含 HFSR.FORCED），下次 halt 只报新故障
        self.with_backend(|b| b.write_u32(CFSR, cfsr));
        if let Some(hfsr) = self.with_backend(|b| b.read_u32(HFSR)) {
            self.with_backend(|b| b.write_u32(HFSR, hfsr));
        }
    }

    /// 取样 DWT 匹配状态：`(DFSR, FUNCTION0..3)` 的**原值**。
    /// 调用时机很关键 —— 越早越好，因为标志会被后续 PPB 访问清掉（见 `pending_dwt` 字段注释）。
    fn sample_dwt(&mut self) -> (u32, Vec<u32>) {
        let dfsr = self.with_backend(|b| b.read_u32(DFSR)).unwrap_or(0);
        let mut fns = Vec::with_capacity(MAX_WATCHPOINTS);
        for n in 0..MAX_WATCHPOINTS {
            fns.push(
                self.with_backend(|b| b.read_u32(DWT_FUNCTION + 0x10 * n as u64))
                    .unwrap_or(0),
            );
        }
        (dfsr, fns)
    }

    /// `DFSR.DWTTRAP != 0 || 任一 FUNCTION.MATCHED != 0` 即视为"看起来有命中"
    /// （两个信号语义不对称，必须都算，见 `on_halted` 的说明）。
    fn dwt_looks_hit(dfsr: u32, fns: &[u32]) -> bool {
        dfsr & (1 << 2) != 0 || fns.iter().any(|v| v & (1 << 24) != 0)
    }

    /// 读回硬件里实际武装着的观察点地址。
    /// 判断依据是 FUNCTION 段（bit3:0）非 0；**不能**用"整寄存器非 0" ——
    /// bit9 是只读的 LNK1ENA，停用状态也可能读到 0x200。
    fn read_watchpoints(&mut self) -> Vec<u64> {
        let mut out = Vec::new();
        for n in 0..MAX_WATCHPOINTS {
            let func = self.with_backend(|b| b.read_u32(DWT_FUNCTION + 0x10 * n as u64));
            let comp = self.with_backend(|b| b.read_u32(DWT_COMP + 0x10 * n as u64));
            if let (Some(f), Some(c)) = (func, comp) {
                if f & 0xF != 0 {
                    out.push(c as u64);
                }
            }
        }
        out
    }

    /// 编程 DWT 观察点比较器（经通用内存访问写调试寄存器——两后端一致）。
    /// addr 需 4 字节对齐；观察读写访问；未占用的比较器停用。
    fn program_watchpoints(&mut self, addrs: &[u64]) {
        // 硬件只有 MAX_WATCHPOINTS 个比较器：入口先截断，让**本地列表长度与硬件
        // 实际能力一致**。否则超出部分既不会生效，又会让 on_halted 按 index 映射到
        // `DWT_FUNCTION + 0x10*n` 时去读根本不存在的比较器寄存器。
        // 前端已限制 4 个，此处是防直接调用 IPC 绕过。
        let addrs = if addrs.len() > MAX_WATCHPOINTS {
            let dropped = addrs.len() - MAX_WATCHPOINTS;
            self.emit(Event::Log {
                message: format!(
                    "数据观察点最多 {MAX_WATCHPOINTS} 个（DWT 比较器数量限制），已忽略多余的 {dropped} 个"
                ),
            });
            &addrs[..MAX_WATCHPOINTS]
        } else {
            addrs
        };
        // 列表一变就重置热点判定状态，避免用旧状态误报；取样值同样作废
        // （旧值对应的是改动前的武装，拿来归属会张冠李戴）
        self.hot_warned = false;
        self.last_halt_at = None;
        self.pending_dwt = None;
        // 清掉上次命中留下的 DFSR.DWTTRAP（写 1 清除），否则刚武装后的第一次停机
        // 可能被这个陈旧标志误判成"观察点命中"
        if let Some(dfsr) = self.with_backend(|b| b.read_u32(DFSR)) {
            if dfsr & (1 << 2) != 0 {
                self.with_backend(|b| b.write_u32(DFSR, dfsr));
            }
        }
        // TRCENA：DWT 需要 DEMCR.TRCENA 才工作
        if let Some(demcr) = self.with_backend(|b| b.read_u32(DEMCR)) {
            self.with_backend(|b| b.write_u32(DEMCR, demcr | (1 << 24)));
        }
        for n in 0..MAX_WATCHPOINTS {
            let comp = DWT_COMP + 0x10 * n as u64;
            let mask = DWT_MASK + 0x10 * n as u64;
            let func = DWT_FUNCTION + 0x10 * n as u64;
            // 先关比较器再改参数，避免重编程过程中产生假匹配
            self.with_backend(|b| b.write_u32(func, 0));
            if let Some(&addr) = addrs.get(n) {
                self.with_backend(|b| b.write_u32(comp, addr as u32));
                // MASK=2：匹配 4 字节区域
                self.with_backend(|b| b.write_u32(mask, 2));
                // 最后写 FUNCTION 使能（DATAVSIZE=4B，读或写触发停机）
                self.with_backend(|b| b.write_u32(func, DWT_FN_DATA_RW_4B));
            }
        }
        self.watchpoints = addrs.to_vec();
        // 武装结束立刻取样：**这里比 poll_state 更早**，而且越早越可能还带着匹配标志 ——
        // OpenOCD 走 Tcl，每条 PPB 访问 ~1ms，随后的 `read_watchpoints()`（8 次读）
        // 足以把标志清掉（真机实测：probe-rs 能撑到 poll_state，OpenOCD 撑不到）。
        // 没有武装任何观察点时清空，避免留下无关的取样值。
        self.pending_dwt = if addrs.is_empty() {
            None
        } else {
            Some(self.sample_dwt())
        };
    }

    /// 停住时的断点命中判定 + 条件求值（对照 _service_breakpoint_state）。
    fn on_halted(&mut self) {
        // 热点观察点提醒：同一次武装期间"短时间内反复停住"说明被观察的地址每圈都会被
        // 访问，点「继续」会在毫秒级内再次命中 —— 用户极易误以为"观察点取消了却还在停"。
        // 判定**不能只靠 FUNCTION.MATCHED**：实测部分停机不带该位，会漏报（踩过）。
        let now = Instant::now();
        if !self.watchpoints.is_empty() && !self.hot_warned {
            if let Some(prev) = self.last_halt_at {
                if now.duration_since(prev) < Duration::from_millis(1500) {
                    self.hot_warned = true;
                    let list = self
                        .watchpoints
                        .iter()
                        .map(|a| format!("0x{a:08x}"))
                        .collect::<Vec<_>>()
                        .join(", ");
                    self.emit(Event::Log {
                        message: format!(
                            "⚠ {list} 每圈都会被访问：点「继续」会立刻再停。\
                             要恢复自由运行请先清除观察点（右键「清除数据观察点」，\
                             或点监视面板标题上的观察点计数）"
                        ),
                    });
                }
            }
        }
        self.last_halt_at = Some(now);

        // 数据观察点命中检测。**两个信号必须都看**（实测 G431，0b0110）：
        //   · 写访问命中 → DFSR.DWTTRAP=1，但 FUNCTION.MATCHED 常为 0
        //   · 读访问命中 → FUNCTION.MATCHED=1，但 DFSR.DWTTRAP 为 0
        // 只看 MATCHED 会把"写命中"误报成"未命中"（用户就踩到这个），
        // 只看 DFSR 又会漏掉读命中。MATCHED 的额外价值是能定位**是哪个比较器**命中；
        // MATCHED 的时效性靠"命中后关→开比较器"维持（写 0 到 FUNCTION 清除该位）。
        // 这里只**记录**命中描述，日志统一在拿到 PC 之后发一行。
        let watchpoints = self.watchpoints.clone();
        let mut watch_hit: Option<String> = None;
        if !watchpoints.is_empty() {
            // 优先用 `poll_state` 入口取样的原值（最早时刻，标志还没被 PPB 访问清掉）；
            // 取样里看不出命中时，回退就地面读一次 —— 覆盖"取样时还在跑、之后才停住"
            // 的场景，与修复前行为一致，不引入回归。
            let (dfsr, fns) = match self.pending_dwt.take() {
                Some((d, f)) if Self::dwt_looks_hit(d, &f) => (d, f),
                _ => self.sample_dwt(),
            };
            let dwt_trap = dfsr & (1 << 2) != 0;
            for (n, &addr) in watchpoints.iter().enumerate() {
                let func = DWT_FUNCTION + 0x10 * n as u64;
                if fns.get(n).is_some_and(|fv| fv & (1 << 24) != 0) {
                    watch_hit = Some(format!("{addr:#010x}"));
                    self.with_backend(|b| b.write_u32(func, 0));
                    self.with_backend(|b| b.write_u32(func, DWT_FN_DATA_RW_4B));
                }
            }
            // MATCHED 没能定位时，用 DFSR 兜底（写命中走这条路）
            if watch_hit.is_none() && dwt_trap {
                watch_hit = Some(if watchpoints.len() == 1 {
                    format!("{:#010x}", watchpoints[0])
                } else {
                    format!("（已武装 {} 个之一）", watchpoints.len())
                });
            }
            // 写 1 清除全部调试事件标志
            if dwt_trap {
                self.with_backend(|b| b.write_u32(DFSR, dfsr));
            }
        }
        // 故障分析：CFSR 非零说明发生过故障（粘滞标志）——解码原因并清除，
        // 下次 halt 只报新故障
        self.report_fault_status();
        let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) else {
            self.send_regs_and_stack();
            return;
        };
        let pc = pc & !1;

        // 匹配断点：PC、PC-2、PC-4（硬件断点偏移）
        let hit = self
            .breakpoints
            .iter()
            .find(|bp| {
                let a = bp.addr & !1;
                a == pc || a == pc.wrapping_sub(2) || a == pc.wrapping_sub(4)
            })
            .cloned();

        let Some(bp) = hit else {
            // 非用户断点触发。控制台保持**每次停住一行**：
            //   命中数据观察点 → 报命中地址 + 停住 PC
            //   否则           → 只报停住 PC
            // 刻意不再补"（数据观察点未命中）"：实测那个分支对应的是**武装之前就已存在**
            // 的停住状态（两个匹配标志都还是 0），报出来只会让人误以为"观察点失效"。
            let msg = match &watch_hit {
                Some(desc) => format!("数据观察点命中 {desc} → 停住 @ 0x{pc:08x}"),
                None => format!("已停住 @ 0x{pc:08x}"),
            };
            self.emit(Event::Log { message: msg });

            // 观察点语义说明只在**每个会话提示一次**：地址匹配（值不变也停）+
            // Cortex-M 非精确事件（停止位置在访问之后）。长期说明留在
            // Watch 面板的提示与 docs，不逐次刷屏。
            if watch_hit.is_some() && !self.watch_hint_shown {
                self.watch_hint_shown = true;
                self.emit(Event::Log {
                    message: "说明：数据观察点按地址匹配（值不变也会停），且停止位置会落在触发访问之后\
                              若干条指令（Cortex-M 非精确调试事件）—— 本条仅提示一次"
                        .to_string(),
                });
            }
            self.send_regs_and_stack();
            return;
        };

        if self.held_break_addr == Some(bp.addr & !1) {
            return; // 已上报过
        }

        // 条件求值
        let mut condition_error = None;
        if !bp.condition.trim().is_empty() {
            match self.eval_condition(&bp.condition) {
                Ok(true) => {}
                Ok(false) => {
                    // 条件为假：跳过断点继续跑
                    self.step_past_breakpoint(bp.addr & !1);
                    // 与 Resume/Reset/step_out 路径对齐：只有 resume 实际成功才置
                    // running 边沿——Transfer 失败时目标仍停着，发假 Running 会让
                    // 下次 poll 出假边沿重报同一次停住
                    if self.with_backend(|b| b.resume()).is_some() {
                        self.mark_running();
                    }
                    return;
                }
                Err(e) => {
                    // 条件出错：按命中处理并附带错误（偏安全）
                    condition_error = Some(e);
                }
            }
        }

        self.held_break_addr = Some(bp.addr & !1);
        self.emit(Event::BreakpointHit {
            pc,
            condition_error,
        });
        self.send_regs_and_stack();
    }

    fn eval_condition(&mut self, source: &str) -> Result<bool, String> {
        let mut ctx: HashMap<String, f64> = HashMap::new();
        // 寄存器上下文
        for reg in [
            "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12",
            "sp", "lr", "pc", "xpsr",
        ] {
            if let Some(v) = self.with_backend(|b| b.read_core_register(reg)) {
                ctx.insert(reg.to_string(), v as f64);
                ctx.insert(reg.to_uppercase(), v as f64);
            }
        }
        // 符号上下文
        let refs = self.symbol_refs.clone();
        for sym in refs {
            if let Some(bytes) = self.with_backend(|b| b.read_bytes(sym.addr, sym.size as usize)) {
                let value = conditions::decode_scalar(&bytes, sym.signed);
                ctx.insert(sym.name.clone(), value);
            }
        }
        conditions::evaluate(source, &ctx)
    }

    /// 跳过当前地址的断点单步（remove → step → re-set）。
    fn step_past_breakpoint(&mut self, addr: u64) {
        self.with_backend(|b| b.clear_breakpoint(addr));
        self.with_backend(|b| b.step());
        self.with_backend(|b| b.set_breakpoint(addr));
    }

    fn clear_temp_bps(&mut self) {
        let bps = std::mem::take(&mut self.temp_bps);
        for addr in bps {
            // 与用户断点重合的不清（apply_breakpoints 维护）
            if !self.applied_bps.contains(&addr) {
                self.with_backend(|b| b.clear_breakpoint(addr));
            }
        }
    }

    /// Step Over：临时断点在下一源码行（或调用指令落点），resume 后停住。
    fn step_over(&mut self, next_line_addr: Option<u64>) {
        let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) else { return };
        let pc = pc & !1;

        // 目标临时断点地址
        let target = next_line_addr.map(|a| a & !1).or_else(|| {
            // 无行表：读指令判断是否 call
            let bytes = self.with_backend(|b| b.read_bytes(pc, 4))?;
            thumb::call_instruction_len(&bytes).map(|len| pc + len as u64)
        });

        match target {
            Some(addr) if addr != pc => {
                // 若停在用户/临时断点上先跳过一步再继续
                if self.applied_bps.contains(&pc) || self.temp_bps.contains(&pc) {
                    self.step_past_breakpoint(pc);
                    // 单步落点可能恰是目标行/其它断点（断点行只剩一条指令时常见）。
                    // 此时不能 resume：OpenOCD 会把 resume_pc 上的断点摘掉跑过去
                    // （步进静默变自由跑）→ 按"已到达"处理，置边沿走完整上报
                    if self.landed_on_breakpoint(Some(addr)) {
                        self.held_break_addr = None;
                        self.poll_state();
                        return;
                    }
                }
                if !self.applied_bps.contains(&addr) && !self.temp_bps.contains(&addr) {
                    self.with_backend(|b| b.set_breakpoint(addr));
                    self.temp_bps.push(addr);
                }
                self.held_break_addr = None;
                if self.with_backend(|b| b.resume()).is_some() {
                    self.mark_running();
                }
                self.poll_state();
            }
            _ => {
                // 非调用指令：普通单步
                self.step_over_breakpoint();
                self.poll_state();
                self.send_regs_and_stack();
            }
        }
    }

    /// Step Out：LR 临时断点后 resume。
    fn step_out(&mut self) {
        let Some(lr) = self.with_backend(|b| b.read_core_register("lr")) else { return };
        let lr = lr & !1;
        if lr < 0x100 {
            self.emit(Event::Log {
                message: "LR 无效，无法 Step Out".into(),
            });
            return;
        }
        if let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) {
            let pc = pc & !1;
            if self.applied_bps.contains(&pc) || self.temp_bps.contains(&pc) {
                self.step_past_breakpoint(pc);
                // 断点指令是函数末条时，单步落点恰为 LR → 已到达，不能 resume
                if self.landed_on_breakpoint(Some(lr)) {
                    self.held_break_addr = None;
                    self.poll_state();
                    return;
                }
            }
        }
        if !self.applied_bps.contains(&lr) && !self.temp_bps.contains(&lr) {
            self.with_backend(|b| b.set_breakpoint(lr));
            self.temp_bps.push(lr);
        }
        self.held_break_addr = None;
        if self.with_backend(|b| b.resume()).is_some() {
            self.mark_running();
        }
        self.poll_state();
    }

    /// resume 发出后强制记为 running 并发事件。
    /// 目标可能在下一次 poll 之前就再次停住（临时断点几微秒内命中），
    /// 若不强制置边沿，poll_state 会看到 halted→halted 而漏掉整个停住流程
    /// （不发 State/RegsAndStack、不清临时断点 → 步进"卡死"）。
    fn mark_running(&mut self) {
        if self.backend.is_none() {
            return; // resume 失败已断线，不发假 Running
        }
        self.last_state = Some(false);
        self.held_break_addr = None;
        self.emit(Event::State {
            state: TargetState::Running,
        });
    }

    /// step_past 后检查落点是否已在断点上（`extra` 额外视为目标地址）。
    /// 是则置 running 边沿并返回 true——调用方应直接 poll_state 完成上报，
    /// 不得再 resume（OpenOCD 的 resume 会摘掉 resume_pc 上的断点跑过去，
    /// 静默吞掉本次命中，步进会变成自由跑）。
    fn landed_on_breakpoint(&mut self, extra: Option<u64>) -> bool {
        let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) else {
            return false;
        };
        let pc = pc & !1;
        let on_bp = extra == Some(pc)
            || self.applied_bps.contains(&pc)
            || self.temp_bps.contains(&pc);
        if on_bp {
            self.last_state = Some(false);
        }
        on_bp
    }

    /// 返回 true 表示已成功发出 resume；false 表示未 resume
    /// （resume 失败，或 step_past 后落点即另一断点——此时已置边沿待上报）。
    fn resume_over_breakpoint(&mut self) -> bool {
        // 若当前 PC 停在用户/临时断点上，需要先跳过再 resume
        if let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) {
            let pc = pc & !1;
            let on_bp = self.applied_bps.contains(&pc) || self.temp_bps.contains(&pc);
            if on_bp {
                self.step_past_breakpoint(pc);
                if self.landed_on_breakpoint(None) {
                    return false;
                }
            }
        }
        self.with_backend(|b| b.resume()).is_some()
    }

    fn step_over_breakpoint(&mut self) {
        if let Some(pc) = self.with_backend(|b| b.read_core_register("pc")) {
            let pc = pc & !1;
            if self.applied_bps.contains(&pc) || self.temp_bps.contains(&pc) {
                self.step_past_breakpoint(pc);
                return;
            }
        }
        self.with_backend(|b| b.step());
    }

    fn sample_watch(&mut self) {
        let halted = self.last_state.unwrap_or(false);
        let targets: Vec<MemTarget> = self
            .watch_targets
            .iter()
            .filter(|t| halted || t.auto_refresh)
            .cloned()
            .collect();
        if targets.is_empty() {
            return;
        }
        let pairs: Vec<(u64, u64)> = targets.iter().map(|t| (t.addr, t.size as u64)).collect();
        let blocks = bandwidth::merge_blocks(&pairs, self.watch_freq);

        let mut block_data: Vec<(u64, Vec<u8>)> = Vec::new();
        for block in &blocks {
            if self.backend.is_none() {
                return;
            }
            match self.with_backend(|b| b.read_bytes(block.start, block.size as usize)) {
                Some(bytes) => block_data.push((block.start, bytes)),
                None => continue, // 单块失败（如非法指针展开目标）不阻断其他有效块的采样与推送
            }
        }

        let mut values = HashMap::new();
        for t in &targets {
            if let Some(bytes) = extract_from_blocks(&block_data, t.addr, t.size as usize) {
                values.insert(t.id.clone(), bytes);
            }
        }
        if !values.is_empty() {
            self.emit(Event::WatchData { values, halted });
        }
    }

    fn rebuild_scope_blocks(&mut self) {
        let pairs: Vec<(u64, u64)> = self
            .scope_targets
            .iter()
            .map(|t| (t.addr, t.size as u64))
            .collect();
        self.scope_blocks = bandwidth::merge_blocks(&pairs, self.scope_freq);
    }

    fn sample_scope(&mut self) {
        if self.scope_targets.is_empty() {
            return;
        }
        let interval = Duration::from_secs_f64(1.0 / self.scope_freq.max(1.0));
        let blocks: Vec<(u64, usize)> = self
            .scope_blocks
            .iter()
            .map(|b| (b.start, b.size as usize))
            .collect();

        // 突发采样：单次调用连读一批帧，节拍在后端内部精确控制。
        // - probe-rs：Core 仅建一次（重建开销 ~1.7ms 摊薄到一批），单帧 ~895µs
        //   < 1ms 节拍 → 1kHz 达成（实测 993Hz）；
        // - OpenOCD：逐帧走 Tcl（每命令 ~1ms 往返）。真机实测同样可达成
        //   960–1000Hz；早期文档写的"~870Hz 架构上限"出自 bench 推算，
        //   与实测不符，已作废。
        // 时间戳一律取实际读时刻，无时间轴失真。
        // 批量时长：当同时开启高频监视（>=10Hz）时，将突发窗口从 40ms 降至 20ms；
        // 且若下一次变量监视即将在本次突发内到期，进一步将突发时长裁剪至剩余时间，
        // 确保变量监视准时在批间切入，杜绝 15Hz 监视被 40ms 示波突发阻塞死锁在 ~12Hz。
        let mut burst_target_us = if !self.watch_targets.is_empty() && self.watch_freq >= 10.0 {
            20_000u64
        } else {
            40_000u64
        };
        if !self.watch_targets.is_empty() {
            let now = Instant::now();
            if self.next_watch > now {
                let time_to_watch = (self.next_watch - now).as_micros() as u64;
                if time_to_watch < burst_target_us {
                    burst_target_us = time_to_watch.max(interval.as_micros() as u64);
                }
            }
        }
        let count = (burst_target_us
            .div_ceil(interval.as_micros().max(1) as u64))
        .clamp(1, 48) as usize;

        let burst_start = Instant::now();
        let burst_span = interval * count as u32;
        let t0 = self.epoch.elapsed().as_secs_f64();
        match self.with_backend(|bk| bk.scope_burst(&blocks, count, interval)) {
            Some(frames) => {
                for (offset, frame) in frames {
                    let t = t0 + offset.as_secs_f64();
                    // 帧 = 与 blocks 同序的各块数据 → 配回块地址供 extract 使用
                    let mut block_data: Vec<(u64, Vec<u8>)> = Vec::with_capacity(frame.len());
                    for ((addr, _size), data) in blocks.iter().zip(frame) {
                        block_data.push((*addr, data));
                    }
                    let mut values = HashMap::with_capacity(self.scope_targets.len());
                    for target in &self.scope_targets {
                        if let Some(bytes) =
                            extract_from_blocks(&block_data, target.addr, target.size as usize)
                        {
                            values.insert(format!("0x{:08x}", target.addr), bytes);
                        }
                    }
                    if !values.is_empty() {
                        self.scope_buffer.push(ScopeSample { t, values });
                    }
                }
                // 一批覆盖 count 拍且批内节拍精确：下一批在 burst_span 之后或立即开始
                let scheduled = burst_start + burst_span;
                self.next_scope = if scheduled > Instant::now() {
                    scheduled
                } else {
                    Instant::now()
                };
            }
            None => {
                // 读失败时避让 50ms，避免紧凑空转刷爆日志
                self.next_scope = Instant::now() + Duration::from_millis(50);
            }
        }
    }

    fn flush_scope(&mut self) {
        if self.scope_buffer.is_empty() {
            return;
        }
        let samples = std::mem::take(&mut self.scope_buffer);
        self.emit(Event::ScopeData { samples });
    }

    fn send_regs_and_stack(&mut self) {
        if self.backend.is_none() {
            return;
        }
        let mut regs = HashMap::new();
        for reg in [
            "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "r12",
            "sp", "lr", "pc", "xpsr",
        ] {
            if let Some(v) = self.with_backend(|b| b.read_core_register(reg)) {
                regs.insert(reg.to_string(), v);
            }
        }
        let sp = regs.get("sp").copied().unwrap_or(0);
        let mut stack = Vec::new();
        let mut stack_base = 0;
        if sp > STACK_MIN_SP {
            // sp 常紧贴 RAM 顶端（如 0x20007ff8，距边界仅 488B）：固定 512 字节
            // 会跨出 SRAM 边界导致读失败——逐级缩短重试。这是**预期内的**投机
            // 读，失败必须静默（with_backend 会限流上报，用户会在每次断点
            // 命中时看到一条 "read_memory: failed to read memory"）；
            // 致命错误（断线）才走 on_backend_error。
            for len in [STACK_BYTES, 256, 128, 64, 32, 16, 8] {
                let r = self.backend.as_mut().map(|b| b.read_bytes(sp, len));
                match r {
                    None => break, // 无后端（断线已处理）
                    Some(Ok(bytes)) => {
                        if !bytes.is_empty() {
                            stack = bytes;
                            stack_base = sp;
                            break;
                        }
                    }
                    Some(Err(e)) => {
                        if e.is_fatal() {
                            self.on_backend_error(e);
                            break;
                        }
                        // 瞬时失败（边界越界）：静默，缩短长度重试
                    }
                }
            }
        }
        if !regs.is_empty() {
            self.emit(Event::RegsAndStack {
                regs,
                stack_base,
                stack,
            });
        }
    }
}

/// 从合并块中切出目标字节。
fn extract_from_blocks(blocks: &[(u64, Vec<u8>)], addr: u64, size: usize) -> Option<Vec<u8>> {
    for (start, bytes) in blocks {
        // checked 算术：入口校验之外再兜底，防止异常 addr 让比较回绕/切片 panic
        let end = start.checked_add(bytes.len() as u64)?;
        let want_end = addr.checked_add(size as u64)?;
        if addr >= *start && want_end <= end {
            let offset = (addr - start) as usize;
            return Some(bytes[offset..offset + size].to_vec());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::{extract_from_blocks, Event};
    use std::collections::HashMap;

    #[test]
    fn extract_middle() {
        let blocks = vec![(0x100u64, vec![1u8, 2, 3, 4, 5, 6, 7, 8])];
        assert_eq!(extract_from_blocks(&blocks, 0x102, 2), Some(vec![3, 4]));
    }

    #[test]
    fn extract_out_of_range() {
        let blocks = vec![(0x100u64, vec![1u8, 2, 3, 4])];
        assert_eq!(extract_from_blocks(&blocks, 0x103, 4), None);
    }

    /// DWT 数据观察点编码：必须是 ARMv7-M 的 0b0110（数据地址，读或写触发停机）。
    /// 曾用 0b0111 —— 那是保留编码，真机实测只对写触发、读不触发。
    #[test]
    fn dwt_function_encoding_is_data_rw() {
        let f = super::DWT_FN_DATA_RW_4B;
        assert_eq!(f & 0xF, 0b0110, "FUNCTION 段应为 0b0110（读或写）");
        assert_eq!((f >> 10) & 0b11, 0b10, "DATAVSIZE 应为字（4 字节）");
    }

    /// 前端契约：变体名与字段名都必须是 camelCase（历史上踩过两次坑）。
    #[test]
    fn event_serialization_contract() {
        let hit = serde_json::to_string(&Event::BreakpointHit {
            pc: 0x8000000,
            condition_error: Some("boom".into()),
        })
        .unwrap();
        assert!(hit.contains("\"kind\":\"breakpointHit\""), "{hit}");
        assert!(hit.contains("\"conditionError\""), "{hit}");

        let regs = serde_json::to_string(&Event::RegsAndStack {
            regs: HashMap::new(),
            stack_base: 0x20001000,
            stack: vec![],
        })
        .unwrap();
        assert!(regs.contains("\"kind\":\"regsAndStack\""), "{regs}");
        assert!(regs.contains("\"stackBase\""), "{regs}");
    }
}
