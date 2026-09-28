//! 真机冒烟测试：DAPLink + STM32G431CBU6 + CubeMX 模板工程（build/g4_tool_test.elf）。
//! 走与应用完全相同的代码路径：ElfIndex（DWARF 符号）→ monitor 引擎（probe-rs 后端）
//! → 连接/状态/Watch 采样/halt/寄存器/断点/复位命中/单步/写内存/示波采样
//! → OpenOCD 后端（Tcl RPC）→ probe-rs 烧录（Run 模式，含校验）。
//!
//! 用法：cargo run -p monitor --example hw_smoke -- <工程目录> [--skip-flash]
//! （烧录会整片重写模板固件，--skip-flash 可跳过）

use crossbeam_channel::Receiver;
use debug_core::{BackendKind, ConnectParams, TargetState};
use monitor::{BreakpointDef, Command, Event, MemTarget, ScopeTarget};
use std::time::{Duration, Instant};

struct Stats {
    pass: usize,
    fail: usize,
}

impl Stats {
    fn ok(&mut self, name: &str, detail: &str) {
        self.pass += 1;
        println!("  ✔ {name}  {detail}");
    }
    fn bad(&mut self, name: &str, detail: &str) {
        self.fail += 1;
        println!("  ✘ {name}  {detail}");
    }
    fn check(&mut self, name: &str, cond: bool, detail: &str) {
        if cond {
            self.ok(name, detail);
        } else {
            self.bad(name, detail);
        }
    }
}

fn wait_for(
    rx: &Receiver<Event>,
    timeout: Duration,
    mut pred: impl FnMut(&Event) -> bool,
) -> Option<Event> {
    let deadline = Instant::now() + timeout;
    loop {
        let remain = deadline.saturating_duration_since(Instant::now());
        if remain.is_zero() {
            return None;
        }
        match rx.recv_timeout(remain) {
            Ok(ev) => {
                match &ev {
                    Event::Error { message } => println!("      [engine-error] {message}"),
                    Event::Log { message } => println!("      [engine-log] {message}"),
                    _ => {}
                }
                if pred(&ev) {
                    return Some(ev);
                }
            }
            Err(_) => return None,
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let skip_flash = args.iter().any(|a| a == "--skip-flash");
    let project = args
        .iter()
        .skip(1)
        .find(|a| !a.starts_with('-'))
        .cloned()
        .unwrap_or_else(|| r"E:\Software\Develop\Embeded\Pack\g4_tool_test".into());
    let elf_path = std::path::Path::new(&project).join("build").join("g4_tool_test.elf");

    let mut st = Stats { pass: 0, fail: 0 };

    println!("== A. ELF/DWARF 索引 ==");
    let index = match elf_info::ElfIndex::load(&elf_path) {
        Ok(i) => i,
        Err(e) => {
            println!("无法加载 ELF {}: {e}", elf_path.display());
            std::process::exit(2);
        }
    };
    st.check("函数符号", index.functions.iter().any(|f| f.name == "main"),
        &format!("{} 个函数，main @ {:#x}", index.functions.len(),
            index.functions.iter().find(|f| f.name == "main").map(|f| f.start).unwrap_or(0)));
    let uwtick = index.variables.iter().find(|v| v.name == "uwTick").cloned();
    st.check("全局变量 uwTick", uwtick.is_some(),
        &format!("addr={:#x} size={}", uwtick.as_ref().map(|v| v.address).unwrap_or(0), uwtick.as_ref().map(|v| v.size).unwrap_or(0)));
    let uw_addr = uwtick.as_ref().map(|v| v.address).unwrap_or(0x2000_0028);
    let uw_size = uwtick.as_ref().map(|v| v.size).unwrap_or(4).max(1);
    let main_addr = index
        .functions
        .iter()
        .find(|f| f.name == "main")
        .map(|f| f.start)
        .expect("main 符号");

    // ---- 1. probe-rs 烧录（Run 模式，含 verify + 复位运行）----
    if !skip_flash {
        println!("== B. probe-rs 烧录（Run 模式）==");
        let result = debug_core::flash::flash_firmware(
            "STM32G431CBTx",
            2_000_000, // Flash Pro 克隆探针 4MHz 不稳（OpenOCD 默认也只用 2MHz）
            &elf_path,
            debug_core::flash::FlashMode::Run,
            |ev| match ev {
                debug_core::flash::FlashEvent::Log { text } => println!("    [flash] {text}"),
                debug_core::flash::FlashEvent::Progress { phase, percent } => {
                    print!("\r    [flash] {} {:.0}%   ", phase, percent);
                    use std::io::Write as _;
                    let _ = std::io::stdout().flush();
                }
                debug_core::flash::FlashEvent::Done { .. } => {}
            },
        );
        println!();
        st.check("烧录完成", result.is_ok(), &format!("{:?}", result.err()));
    } else {
        println!("== B. 烧录跳过（--skip-flash）==");
    }

    // ---- 2. probe-rs 引擎全链路 ----
    println!("== C. probe-rs 引擎 ==");
    {
        let mut handle = monitor::spawn_engine();
        let tx = handle.cmd_tx.clone();
        let rx = handle.event_rx.clone();

        tx.send(Command::Connect(ConnectParams {
            kind: BackendKind::ProbeRs,
            // probe-rs 对这块板子自动识别失败（OpenOCD 正常），按名连接正常
            // → 应用需引导用户选择目标芯片
            target: Some("STM32G431CBTx".into()),
            cfg_file: None,
            openocd_path: None,
            scripts_dir: None,
            speed_hz: 2_000_000,
            ..Default::default()
        }))
        .unwrap();
        let connected = wait_for(&rx, Duration::from_secs(10), |e| {
            matches!(e, Event::Connected { .. })
        });
        match &connected {
            Some(Event::Connected { description }) => st.ok("连接", description),
            _ => {
                st.bad("连接", "10 秒内未收到 Connected（探针被占用？）");
                handle.shutdown();
                finish(&st);
            }
        }

        // 运行状态轮询
        let running = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::State { state: TargetState::Running })
        });
        st.check("运行状态", running.is_some(), "poll_state → Running");

        // Watch 5Hz：uwTick 应持续递增
        tx.send(Command::UpdateWatchTargets(vec![MemTarget {
            id: "uwTick".into(),
            addr: uw_addr,
            size: uw_size,
            auto_refresh: true,
        }]))
        .unwrap();
        let d1 = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::WatchData { values, .. } if values.contains_key("uwTick"))
        });
        let v1 = watch_u32(&d1, "uwTick");
        st.check("Watch 采样", d1.is_some(), &format!("uwTick = {v1}"));
        let d2 = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::WatchData { values, .. }
                if values.get("uwTick").map(watch_u32_val) > Some(v1))
        });
        let v2 = watch_u32(&d2, "uwTick");
        st.check("运行中递增", d2.is_some(), &format!("{v1} → {v2}（HAL Tick 1ms）"));

        // halt → 寄存器/栈
        tx.send(Command::Halt).unwrap();
        let halted = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::State { state: TargetState::Halted })
        });
        st.check("halt", halted.is_some(), "");
        let regs_ev = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::RegsAndStack { .. })
        });
        if let Some(Event::RegsAndStack { regs, stack_base: _, stack }) = regs_ev {
            let pc = regs.get("pc").copied().unwrap_or(0);
            let sp = regs.get("sp").copied().unwrap_or(0);
            st.check(
                "寄存器/栈",
                (pc & !1) >= 0x0800_0000 && sp >= 0x2000_0000 && !stack.is_empty(),
                &format!("pc={pc:#010x} sp={sp:#010x} stack={}B", stack.len()),
            );
        } else {
            st.bad("寄存器/栈", "未收到 RegsAndStack");
        }

        // 断点 @ main → 复位运行 → 命中
        tx.send(Command::SetBreakpoints(vec![BreakpointDef {
            addr: main_addr,
            condition: String::new(),
        }]))
        .unwrap();
        tx.send(Command::Reset).unwrap();
        let hit = wait_for(&rx, Duration::from_secs(5), |e| {
            matches!(e, Event::BreakpointHit { .. })
        });
        if let Some(Event::BreakpointHit { pc, condition_error }) = hit {
            st.check(
                "断点命中 @ main",
                (pc & !1) == main_addr,
                &format!("pc={pc:#010x} {}", condition_error.clone().unwrap_or_default()),
            );
        } else {
            st.bad("断点命中 @ main", "复位运行后 5 秒内未命中");
        }

        // 单步：PC 应前进
        let pc0 = current_pc(&tx, &rx);
        tx.send(Command::Step).unwrap();
        wait_for(&rx, Duration::from_secs(2), |e| matches!(e, Event::RegsAndStack { .. }));
        let pc1 = current_pc(&tx, &rx);
        st.check("单步", pc1 > pc0, &format!("pc {pc0:#x} → {pc1:#x}"));

        // StepOver（无行表地址：引擎按 Thumb 解码判断非调用 → 单步）
        tx.send(Command::StepOver { next_line_addr: None }).unwrap();
        let stepped = wait_for(&rx, Duration::from_secs(2), |e| {
            matches!(e, Event::RegsAndStack { .. })
        })
        .is_some();
        st.check("StepOver（非调用指令）", stepped, "");

        // halt 下 Watch（非自动刷新目标 halt 时也应刷新）
        tx.send(Command::UpdateWatchTargets(vec![MemTarget {
            id: "uwTick".into(),
            addr: uw_addr,
            size: uw_size,
            auto_refresh: false,
        }]))
        .unwrap();
        let h1 = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::WatchData { halted, .. } if *halted)
        });
        st.check("halt 下 Watch 刷新", h1.is_some(), "");

        // 写内存 → 读回
        let scratch: u64 = 0x2000_0800;
        let (wtx, wrx) = crossbeam_channel::bounded(1);
        tx.send(Command::WriteMemSync {
            addr: scratch,
            data: vec![0xDE, 0xAD, 0xBE, 0xEF],
            reply: wtx,
        })
        .unwrap();
        wrx.recv_timeout(Duration::from_secs(3)).unwrap().unwrap();
        std::thread::sleep(Duration::from_millis(300));
        let (rtx, rrx) = crossbeam_channel::bounded(1);
        tx.send(Command::ReadMemSync {
            addr: scratch,
            size: 4,
            reply: rtx,
        })
        .unwrap();
        let readback = rrx
            .recv_timeout(Duration::from_secs(2))
            .map(|r| r.map(|b| b == vec![0xDE, 0xAD, 0xBE, 0xEF]))
            .unwrap_or(Ok(false));
        st.check("写内存读回", readback == Ok(true), "0x20000800 ← DE AD BE EF");

        // 示波：200Hz 采 uwTick，运行中应收到递增序列
        tx.send(Command::SetBreakpoints(vec![])).unwrap();
        tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
            addr: uw_addr,
            size: uw_size,
        }]))
        .unwrap();
        tx.send(Command::SetScopeFreq(200.0)).unwrap();
        tx.send(Command::Resume).unwrap();
        let running = wait_for(&rx, Duration::from_secs(3), |e| {
            matches!(e, Event::State { state: TargetState::Running })
        });
        st.check("恢复运行", running.is_some(), "");
        let deadline = Instant::now() + Duration::from_millis(1800);
        let mut samples: Vec<(f64, u32)> = Vec::new();
        while Instant::now() < deadline {
            match rx.recv_timeout(Duration::from_millis(200)) {
                Ok(Event::ScopeData { samples: batch }) => {
                    for s in batch {
                        for (k, bytes) in &s.values {
                            if k == &format!("0x{uw_addr:08x}") && bytes.len() >= 4 {
                                let v = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                                samples.push((s.t, v));
                            }
                        }
                    }
                }
                Ok(_) => {}
                Err(_) => {}
            }
        }
        let monotonic_t = samples.windows(2).all(|w| w[1].0 >= w[0].0);
        let values_increase = samples.len() >= 100
            && samples.last().map(|s| s.1).unwrap_or(0) > samples.first().map(|s| s.1).unwrap_or(0);
        st.check(
            "示波 200Hz 采样",
            monotonic_t && values_increase,
            &format!("{} 样本，t 单调：{monotonic_t}，值递增：{values_increase}", samples.len()),
        );

        tx.send(Command::Disconnect).unwrap();
        handle.shutdown();
    }

    // ---- 3. OpenOCD 后端（Tcl RPC）----
    println!("== D. OpenOCD 后端 ==");
    {
        let openocd = std::env::var("OPENOCD_BIN")
            .unwrap_or_else(|_| "openocd".to_string());
        // 标准写法：引擎侧会传 -s scripts 目录，`find` 可正常解析
        let cfg = std::env::temp_dir().join("hw_smoke_g4.cfg");
        std::fs::write(
            &cfg,
            "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
        )
        .expect("写 cfg");

        let mut handle = monitor::spawn_engine();
        let tx = handle.cmd_tx.clone();
        let rx = handle.event_rx.clone();
        tx.send(Command::Connect(ConnectParams {
            kind: BackendKind::Openocd,
            target: None,
            cfg_file: Some(cfg.to_string_lossy().into_owned()),
            openocd_path: Some(openocd),
            scripts_dir: std::env::var("OPENOCD_SCRIPTS").ok(),
            speed_hz: 4_000_000,
            ..Default::default()
        }))
        .unwrap();
        let connected = wait_for(&rx, Duration::from_secs(15), |e| {
            matches!(e, Event::Connected { .. })
        });
        match &connected {
            Some(Event::Connected { description }) => st.ok("连接", description),
            _ => {
                let err = collect_errors(&rx);
                st.bad("连接", &format!("未收到 Connected；{err}"));
                handle.shutdown();
                finish(&st);
            }
        }

        tx.send(Command::Halt).unwrap();
        let halted = wait_for(&rx, Duration::from_secs(5), |e| {
            matches!(e, Event::State { state: TargetState::Halted })
        });
        st.check("halt", halted.is_some(), "");
        let regs_ev = wait_for(&rx, Duration::from_secs(5), |e| {
            matches!(e, Event::RegsAndStack { .. })
        });
        let pc_ok = matches!(&regs_ev,
            Some(Event::RegsAndStack { regs, .. }) if regs.get("pc").copied().unwrap_or(0) >= 0x0800_0000);
        st.check("读寄存器", pc_ok, "");

        // 断点设置/清除（幂等 rbp + bp 路径）
        tx.send(Command::SetBreakpoints(vec![BreakpointDef {
            addr: main_addr,
            condition: String::new(),
        }]))
        .unwrap();
        std::thread::sleep(Duration::from_millis(500));
        let bp_ok = !collect_errors(&rx).contains("断点");
        st.check("设置断点", bp_ok, "");
        tx.send(Command::SetBreakpoints(vec![])).unwrap();
        tx.send(Command::Resume).unwrap();
        std::thread::sleep(Duration::from_millis(300));

        // 读内存（read_memory Tcl 路径）
        let (rtx, rrx) = crossbeam_channel::bounded(1);
        tx.send(Command::ReadMemSync {
            addr: uw_addr,
            size: 4,
            reply: rtx,
        })
        .unwrap();
        let ok = rrx
            .recv_timeout(Duration::from_secs(3))
            .map(|r| r.is_ok() && r.unwrap().len() == 4)
            .unwrap_or(false);
        st.check("Tcl read_memory", ok, "");

        handle.shutdown();
    }

    finish(&st);
}

fn watch_u32(ev: &Option<Event>, id: &str) -> u32 {
    watch_u32_val(&match ev {
        Some(Event::WatchData { values, .. }) => values.get(id).cloned().unwrap_or_default(),
        _ => Vec::new(),
    })
}
fn watch_u32_val(bytes: &Vec<u8>) -> u32 {
    if bytes.len() >= 4 {
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
    } else {
        0
    }
}

fn current_pc(tx: &crossbeam_channel::Sender<Command>, rx: &Receiver<Event>) -> u64 {
    tx.send(Command::RequestRegsAndStack).unwrap();
    if let Some(Event::RegsAndStack { regs, .. }) =
        wait_for(rx, Duration::from_secs(2), |e| matches!(e, Event::RegsAndStack { .. }))
    {
        return regs.get("pc").copied().unwrap_or(0) & !1;
    }
    0
}

fn collect_errors(rx: &Receiver<Event>) -> String {
    let mut out = String::new();
    while let Ok(ev) = rx.try_recv() {
        if let Event::Error { message } | Event::Log { message } = ev {
            out.push_str(&message);
            out.push_str("; ");
        }
    }
    out
}

fn finish(st: &Stats) {
    println!("\n===== 结果：{} 通过，{} 失败 =====", st.pass, st.fail);
    std::process::exit(if st.fail == 0 { 0 } else { 1 });
}
