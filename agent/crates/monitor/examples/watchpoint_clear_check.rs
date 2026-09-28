//! 数据观察点行为核查（实机，四段）。
//!
//! 回答四个实际问题：
//! 1. **命中位置**：DWT 数据观察点是 ARMv7-M 的非精确（imprecise）调试事件
//!    （DFSR.DWTTRAP），命中时 PC 落在触发访问**之后**若干条指令 —— 本工具打印
//!    实测 PC，配合 `arm-none-eabi-addr2line` 可看它落在源码哪一行。
//! 2. **多观察点并存**：同时武装 A、B 两个地址。
//! 3. **部分清除**：只把列表改成 `[B]`，之后应只在 B 命中（证明 A 已撤、B 仍在）——
//!    前端"删除某条目只撤它的观察点"依赖这个语义。
//! 4. **全部清除**：`SetWatchpoints([])` 后静默观察，应不再停下。
//!
//! 用法: watchpoint_clear_check [pr|ocd] [addrA] [speed_khz] [target]
//!   默认 pr / 0x200000e0（固件 g4_tool_test：g_motor.run_ms）/ 2000 kHz / STM32G431CBTx
//!   B = A + 4（g_motor.count）。两者都是主循环每圈必写的地址，
//!   所以"武装即命中"是可预期的。
//!   第 3、4 个参数用来复现速率/型号相关问题：克隆探针 4MHz 下会出
//!   "An ARM specific error occurred."，2MHz 正常；目标名不同则内存映射可能不同。

use debug_core::{BackendKind, ConnectParams, TargetState};
use monitor::{Command, Event};
use std::time::{Duration, Instant};

/// 从 `数据观察点命中 0x???????? → 停住 @ 0x…` 日志里取出命中的地址。
/// 只认这个前缀 —— 引擎另有 `已停住 @ 0x…` 日志，泛化的 `0x` 匹配会把
/// 停住 PC 误当成命中地址（踩过一次）。
/// **前缀本身也踩过一次**：引擎把文案从 `命中：0x` 精简成 `命中 0x`（去掉冒号）后，
/// 本函数没跟上，于是每段都解析不出地址、段 3 恒报"命中地址不对"（2026-09-16 真机复测发现）。
/// 多个观察点并存且 MATCHED 未能定位时，引擎写 `（已武装 N 个之一）`，此时返回 None。
fn parse_hit_addr(msg: &str) -> Option<u64> {
    const TAG: &str = "数据观察点命中 ";
    let rest = &msg[msg.find(TAG)? + TAG.len()..];
    let tok = rest.split_whitespace().next()?;
    u64::from_str_radix(tok.strip_prefix("0x")?, 16).ok()
}

struct Ctx {
    tx: crossbeam_channel::Sender<Command>,
    rx: crossbeam_channel::Receiver<Event>,
}

impl Ctx {
    fn set_watchpoints(&self, addrs: &[u64]) {
        self.tx.send(Command::SetWatchpoints(addrs.to_vec())).unwrap();
    }
    fn resume(&self) {
        let _ = self.tx.send(Command::Resume);
    }
    /// 等到一次"停下"并返回 (pc, 命中的观察点地址)
    fn wait_halt(&self, timeout: Duration) -> Option<(u64, Option<u64>)> {
        let deadline = Instant::now() + timeout;
        let mut hit: Option<u64> = None;
        while Instant::now() < deadline {
            match self.rx.recv_timeout(Duration::from_millis(200)) {
                Ok(Event::Log { message }) => {
                    println!("  [Log] {message}");
                    if let Some(a) = parse_hit_addr(&message) {
                        hit = Some(a);
                    }
                }
                Ok(Event::Error { message }) => println!("  [Error] {message}"),
                Ok(Event::State { state: TargetState::Halted }) => {
                    self.tx.send(Command::RequestRegsAndStack).unwrap();
                }
                Ok(Event::RegsAndStack { regs, .. }) => {
                    return Some((regs.get("pc").copied().unwrap_or(0) & !1, hit));
                }
                _ => {}
            }
        }
        None
    }
    /// 让引擎读回硬件里实际武装着的观察点（UI 走的就是这条命令）
    fn read_watchpoints(&self) -> Option<Vec<u64>> {
        let (tx, rx) = crossbeam_channel::bounded(1);
        self.tx.send(Command::ReadWatchpoints { reply: tx }).ok()?;
        rx.recv_timeout(Duration::from_secs(2)).ok()
    }
    /// 段边界清场：每次停住会同时产生 `on_halted` 的 RegsAndStack 和我们主动
    /// `RequestRegsAndStack` 的那个，`wait_halt` 只看第一个就返回，队列里会剩一个。
    /// 不排掉的话下一段的 `wait_halt` 会立刻返回上一段的残留，把旧停机当成新的。
    fn settle(&self) {
        std::thread::sleep(Duration::from_millis(150));
        let left = self.drain_pending();
        if !left.is_empty() {
            println!("  （清场：排掉残留事件 {} 条）", left.len());
        }
    }
    /// 非阻塞排空事件队列，返回排到的摘要（用来分辨"残留事件"与"新发生的停机"）
    fn drain_pending(&self) -> Vec<String> {
        let mut out = Vec::new();
        while let Ok(ev) = self.rx.try_recv() {
            let s = match ev {
                Event::State { state } => format!("State({state:?})"),
                Event::Log { message } => format!("Log({})", message.chars().take(40).collect::<String>()),
                Event::RegsAndStack { .. } => "RegsAndStack".to_string(),
                Event::BreakpointHit { .. } => "BreakpointHit".to_string(),
                other => format!("{other:?}"),
            };
            out.push(s);
        }
        out
    }
    /// resume 后静默观察，返回期间停下的次数
    fn count_halts_after_resume(&self, window: Duration) -> usize {
        self.resume();
        let deadline = Instant::now() + window;
        let mut n = 0usize;
        while Instant::now() < deadline {
            match self.rx.recv_timeout(Duration::from_millis(200)) {
                Ok(Event::State { state: TargetState::Halted }) => {
                    n += 1;
                    self.resume(); // 清除了还停的话必须续跑，否则卡在停住态
                }
                Ok(Event::Log { message }) => println!("  [Log] {message}"),
                _ => {}
            }
        }
        n
    }
}

fn main() {
    let backend = std::env::args().nth(1).unwrap_or_else(|| "pr".into());
    let a: u64 = std::env::args()
        .nth(2)
        .map(|s| u64::from_str_radix(s.trim_start_matches("0x"), 16).expect("地址需为 hex"))
        .unwrap_or(0x2000_00e0);
    let b = a + 4;
    let speed_khz: u32 = std::env::args()
        .nth(3)
        .and_then(|s| s.parse().ok())
        .unwrap_or(2000);
    let target = std::env::args()
        .nth(4)
        .unwrap_or_else(|| "STM32G431CBTx".into());
    let ocd = backend == "ocd";
    println!("[SWD 时钟 {speed_khz}kHz] [目标 {target}]");

    let mut handle = monitor::spawn_engine();
    let ctx = Ctx {
        tx: handle.cmd_tx.clone(),
        rx: handle.event_rx.clone(),
    };

    ctx.tx
        .send(Command::Connect(ConnectParams {
            kind: if ocd { BackendKind::Openocd } else { BackendKind::ProbeRs },
            target: Some(target.clone()),
            cfg_file: ocd.then(|| {
                std::env::temp_dir().join("scope_rate.cfg").to_string_lossy().into_owned()
            }),
            openocd_path: ocd.then(|| {
                std::env::var("OPENOCD_BIN").unwrap_or_else(|_| "openocd".to_string())
            }),
            scripts_dir: ocd.then(|| {
                std::env::var("OPENOCD_SCRIPTS").ok()
            }).flatten(),
            speed_hz: speed_khz * 1000,
            ..Default::default()
        }))
        .unwrap();

    // 等连接 + 运行态
    let deadline = Instant::now() + Duration::from_secs(20);
    let mut running = false;
    while Instant::now() < deadline && !running {
        match ctx.rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => println!("[Connected] {description}"),
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            Ok(Event::State { state }) => match state {
                TargetState::Running => running = true,
                // 目标可能被上一次会话留在停住态（或上一次跑完没恢复）：续跑再继续等
                TargetState::Halted => {
                    println!("[目标处于停住态 → resume 后继续等]");
                    ctx.resume();
                }
                _ => {}
            },
            _ => {}
        }
    }
    assert!(running, "未进入运行态");

    // ---- 段 1：单个观察点的命中位置 ----
    println!("\n=== 段 1：观察点 {a:#010x} 的命中位置 ===");
    ctx.set_watchpoints(&[a]);
    let (pc, hit) = ctx
        .wait_halt(Duration::from_secs(8))
        .expect("8 秒内未命中");
    println!("  pc = {pc:#010x}   命中观察点 = {}", hit.map_or("?".into(), |x| format!("{x:#010x}")));
    ctx.settle();

    // ---- 段 2：两个观察点并存 ----
    println!("\n=== 段 2：同时武装 {a:#010x} 与 {b:#010x} ===");
    ctx.set_watchpoints(&[a, b]);
    println!(
        "  引擎读回（应为 2 个）：{:?}",
        ctx.read_watchpoints()
            .unwrap_or_default()
            .iter()
            .map(|x| format!("{x:#x}"))
            .collect::<Vec<_>>()
    );
    ctx.resume();
    match ctx.wait_halt(Duration::from_secs(8)) {
        Some((pc2, hit2)) => println!(
            "  停下 pc={pc2:#010x}  命中 = {}  ✔ 多观察点可用",
            hit2.map_or("?".into(), |x| format!("{x:#010x}"))
        ),
        None => println!("  未停下 ✘"),
    }
    ctx.settle();

    // ---- 段 3：部分清除（只留 B）----
    println!("\n=== 段 3：列表改为 [{b:#010x}]（撤 A、留 B）===");
    ctx.set_watchpoints(&[b]);
    ctx.resume();
    match ctx.wait_halt(Duration::from_secs(8)) {
        Some((pc3, hit3)) => {
            let ok = hit3 == Some(b);
            println!(
                "  停下 pc={pc3:#010x}  命中 = {}  {}",
                hit3.map_or("?".into(), |x| format!("{x:#010x}")),
                if ok { "✔ A 已撤、B 仍在" } else { "✘ 命中地址不对" }
            );
        }
        None => println!("  未停下 ✘（B 未被武装？）"),
    }
    ctx.settle();

    // ---- 段 4：全部清除 ----
    println!("\n=== 段 4：SetWatchpoints([]) 后静默观察 4 秒 ===");
    ctx.set_watchpoints(&[]);
    std::thread::sleep(Duration::from_millis(500));
    let pending = ctx.drain_pending();
    println!(
        "  清除后、resume 前队列里的残留事件：{}",
        if pending.is_empty() {
            "（无）".to_string()
        } else {
            pending.join(" / ")
        }
    );
    let n = ctx.count_halts_after_resume(Duration::from_secs(4));
    println!(
        "  期间停下 {n} 次  {}",
        if n == 0 { "✔ 清除生效" } else { "✘ 清除未生效" }
    );
    println!(
        "  引擎读回（应为空）：{:?}",
        ctx.read_watchpoints()
            .unwrap_or_default()
            .iter()
            .map(|x| format!("{x:#x}"))
            .collect::<Vec<_>>()
    );

    // ---- 段 6：武装状态下静默观察状态跳变（复现"闪运行"）----
    // 全程只 resume 一次，之后不再动它：若还能看到 Running，就说明有东西
    // 在自行恢复目标运行（用户看到的"进入 Halt 后还闪运行"）。
    println!("\n=== 段 6：武装 {a:#010x} 后静默观察 4 秒（只 resume 一次）===");
    ctx.set_watchpoints(&[a]);
    ctx.settle();
    ctx.resume();
    let t0 = Instant::now();
    let deadline = t0 + Duration::from_secs(4);
    let (mut runs, mut halts) = (0usize, 0usize);
    // 我们那次 resume 生效前，目标本来就停着（上一段留下的），那不算跳变
    let mut started = false;
    // 热点告警每次武装只提示一次，所以要在整个观察期内累积
    let mut warned = false;
    while Instant::now() < deadline {
        match ctx.rx.recv_timeout(Duration::from_millis(100)) {
            Ok(Event::State { state }) => {
                let ms = t0.elapsed().as_millis();
                match state {
                    TargetState::Running => {
                        started = true;
                        runs += 1;
                        println!("  [{ms:>5}ms] State = Running");
                    }
                    TargetState::Halted => {
                        if started {
                            halts += 1;
                            println!("  [{ms:>5}ms] State = Halted");
                        } else {
                            println!("  [{ms:>5}ms] State = Halted（resume 生效前，不计）");
                        }
                    }
                    TargetState::Disconnected => println!("  [{ms:>5}ms] State = Disconnected"),
                }
            }
            Ok(Event::Log { message }) => {
                println!("  [Log] {message}");
            if message.contains("每圈都会被访问") {
                warned = true;
            }
            }
            _ => {}
        }
    }
    println!(
        "  4 秒内：resume {runs} 次 / 命中停住 {halts} 次 —— {}",
        if runs <= 1 && halts <= 1 {
            "✔ 无自发跳变（1 次 resume + 1 次命中，之后稳定停住）"
        } else {
            "✘ 存在自发跳变：有东西在反复恢复运行"
        }
    );
    // 热点告警的触发条件是**同一次武装期内两次停住间隔 <1.5s**。上面只 resume 了一次，
    // 静默窗又已跑满 4 秒 —— 结构上不可能满足，原先恒报"未出现"（2026-09-16 复测发现）。
    // 这里再连点「继续」把它逼出来；只影响告警判定，"无自发跳变"的结论已在上方结算完。
    if !warned {
        println!("  连点「继续」以触发热点告警（需两次停住间隔 <1.5s）…");
        for _ in 0..3 {
            if warned {
                break;
            }
            ctx.resume();
            let d = Instant::now() + Duration::from_millis(400);
            while Instant::now() < d && !warned {
                if let Ok(Event::Log { message }) = ctx.rx.recv_timeout(Duration::from_millis(80)) {
                    println!("  [Log] {message}");
                    if message.contains("每圈都会被访问") {
                        warned = true;
                    }
                }
            }
        }
    }
    println!(
        "  热点告警（提示用户'必须先清除观察点'）{}",
        if warned { "✔ 已给出" } else { "✘ 未出现" }
    );

    ctx.set_watchpoints(&[]);
    ctx.settle();

    let _ = ctx.tx.send(Command::SetWatchpoints(vec![]));
    ctx.resume();
    drop(ctx);
    handle.shutdown();

    // ---- 段 5：引擎关闭后直接读回 DWT 寄存器，确认硬件真的被停用 ----
    // 引擎 shutdown 时会把 4 个比较器的 FUNCTION 写 0；这里用独立后端直接读 PPB 核对，
    // 排除"引擎以为自己清了、硬件其实没清"的情况。
    println!("\n=== 段 5：直接读回 PPB（独立连接）===");
    {
        use debug_core::probers_backend::ProbeRsBackend;
        use debug_core::DebugBackend;
        const DWT_FUNCTION: u64 = 0xE000_1028;
        const DFSR: u64 = 0xE000_ED30;
        let mut be = ProbeRsBackend::new("STM32G431CBTx".into(), 2_000_000);
        if be.connect().is_ok() {
            let mut all_off = true;
            for n in 0..4u64 {
                let v = be.read_u32(DWT_FUNCTION + 0x10 * n).unwrap_or(0xFFFF_FFFF);
                // FUNCTION 段（bit3:0）=0 即停用；bit9 是只读的 LNK1ENA，不能参与判断
                if v & 0xF != 0 {
                    all_off = false;
                }
                println!("  DWT_FUNCTION{n} = 0x{v:08x}（FUNCTION 段 = 0b{:04b}）", v & 0xF);
            }
            println!("  DFSR = 0x{:08x}", be.read_u32(DFSR).unwrap_or(0xFFFF_FFFF));
            println!(
                "  → 引擎关闭后比较器全部停用：{}",
                if all_off { "✔ 是" } else { "✘ 否" }
            );
            be.disconnect();
        } else {
            println!("  （探针被占用，跳过读回）");
        }
    }
}
