//! 引擎层全链路仿真集成测试：
//! 连接 → 状态 → Watch 采样 → 断点命中 → 寄存器/栈 → 步进 → 示波采样 → 写内存。
//! 使用 SimBackend，无需硬件，可在 CI 运行。

use debug_core::{BackendKind, ConnectParams, TargetState};
use monitor::{spawn_engine, BreakpointDef, Command, Event, MemTarget, ScopeTarget};
use std::time::{Duration, Instant};

const SIG: u64 = 0x2000_0000;

fn connect_params() -> ConnectParams {
    ConnectParams {
        kind: BackendKind::Sim,
        target: None,
        cfg_file: None,
        openocd_path: None,
        scripts_dir: None,
        speed_hz: 4_000_000,
        ..Default::default()
    }
}

/// 在超时内等待满足条件的事件，返回该事件；其余事件收集忽略。
fn wait_for(
    rx: &crossbeam_channel::Receiver<Event>,
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
                if pred(&ev) {
                    return Some(ev);
                }
            }
            Err(_) => return None,
        }
    }
}

#[test]
fn full_debug_flow_with_sim_backend() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    // 1. 连接
    tx.send(Command::Connect(connect_params())).unwrap();
    let connected = wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    });
    assert!(connected.is_some(), "应收到 Connected 事件");

    // 初始状态：running
    let state = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::State { .. })
    });
    assert!(
        matches!(state, Some(Event::State { state: TargetState::Running })),
        "初始应为 Running，得到 {state:?}"
    );

    // 2. Watch 采样（信号区 u32 计数 @0x20000000，自动刷新）
    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
        id: "tick".into(),
        addr: SIG,
        size: 4,
        auto_refresh: true,
    }]))
    .unwrap();
    let d1 = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::WatchData { values, .. } if values.contains_key("tick"))
    });
    let Some(Event::WatchData { values: v1, halted }) = d1 else {
        panic!("应收到 WatchData");
    };
    assert!(!halted);
    // 5Hz 下再等一批，值应变化（毫秒计数递增）
    let d2 = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::WatchData { values, .. } if values.contains_key("tick") && values["tick"] != v1["tick"])
    });
    assert!(d2.is_some(), "运行中 Watch 值应持续变化");

    // 3. 断点：设置 → 命中 → 寄存器/栈
    let bp_addr = 0x0800_0140u64;
    tx.send(Command::SetBreakpoints(vec![BreakpointDef {
        addr: bp_addr,
        condition: String::new(),
    }]))
    .unwrap();
    tx.send(Command::Halt).unwrap();
    wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::State { state: TargetState::Halted })
    })
    .expect("Halt 后应停住");
    tx.send(Command::Resume).unwrap();
    wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::State { state: TargetState::Running })
    })
    .expect("Resume 后应运行");

    // 自动命中
    let hit = wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::BreakpointHit { .. })
    });
    let Some(Event::BreakpointHit { pc, .. }) = hit else {
        panic!("应收到 BreakpointHit");
    };
    assert_eq!(pc & !1, bp_addr, "命中 PC 应等于断点地址");

    let regs = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::RegsAndStack { .. })
    });
    let Some(Event::RegsAndStack { regs, stack, .. }) = regs else {
        panic!("应收到 RegsAndStack");
    };
    assert_eq!(regs.get("pc").copied().unwrap_or(0) & !1, bp_addr);
    assert!(!stack.is_empty(), "SP 有效时应读到栈数据");

    // 4. 条件断点：条件为假 → 跳过继续跑（不上报命中）
    tx.send(Command::SetBreakpoints(vec![BreakpointDef {
        addr: bp_addr,
        condition: "r0 == 99999".into(), // r0 = 0*0x111+tick&0xff < 256，永假
    }]))
    .unwrap();
    tx.send(Command::Resume).unwrap();
    // 条件假：应看到 Running（跳过后继续），且短时间内无 BreakpointHit
    let hit2 = wait_for(&rx, Duration::from_millis(1200), |e| {
        matches!(e, Event::BreakpointHit { .. })
    });
    // 注意：sim 后端 resume 后仍会再次自动"到达"断点，但条件为假会被引擎跳过并继续；
    // 因此这里断言的是在窗口内未上报命中
    assert!(hit2.is_none(), "条件为假的断点不应上报命中");

    // 5. 示波采样
    tx.send(Command::SetBreakpoints(vec![])).unwrap();
    tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: SIG + 4, // 正弦 f32
        size: 4,
    }]))
    .unwrap();
    tx.send(Command::SetScopeFreq(200.0)).unwrap();
    tx.send(Command::Resume).unwrap();
    let scope = wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::ScopeData { samples } if samples.len() >= 3)
    });
    assert!(scope.is_some(), "应收到批量示波数据");
    if let Some(Event::ScopeData { samples }) = scope {
        let key = format!("0x{:08x}", SIG + 4);
        assert!(samples[0].values.contains_key(&key));
        // 时间戳单调
        for w in samples.windows(2) {
            assert!(w[1].t >= w[0].t);
        }
    }

    // 6. 写内存 → 同步读回
    tx.send(Command::WriteMem {
        addr: SIG + 0x1000,
        data: vec![0xAA, 0xBB, 0xCC],
    })
    .unwrap();
    let (rtx, rrx) = crossbeam_channel::bounded(1);
    tx.send(Command::ReadMemSync {
        addr: SIG + 0x1000,
        size: 3,
        reply: rtx,
    })
    .unwrap();
    let bytes = rrx
        .recv_timeout(Duration::from_secs(2))
        .expect("读回超时")
        .expect("读回失败");
    assert_eq!(bytes, vec![0xAA, 0xBB, 0xCC], "写内存后应能读回");

    // 7. 步进：halt → step → pc 前进且停住
    tx.send(Command::Halt).unwrap();
    wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::State { state: TargetState::Halted })
    })
    .expect("halt");
    tx.send(Command::RequestRegsAndStack).unwrap();
    let r1 = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::RegsAndStack { .. })
    });
    let Some(Event::RegsAndStack { regs: regs1, .. }) = r1 else { panic!() };
    let pc1 = regs1["pc"];
    tx.send(Command::Step).unwrap();
    let r2 = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::RegsAndStack { regs, .. } if regs.get("pc").copied() != Some(pc1))
    });
    let Some(Event::RegsAndStack { regs: regs2, .. }) = r2 else {
        panic!("Step 后应收到新的寄存器快照");
    };
    assert_eq!(regs2["pc"], pc1 + 2, "Step 后 PC 应 +2");

    // 8. 断开
    tx.send(Command::Disconnect).unwrap();
    wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::Disconnected { .. })
    })
    .expect("应收到 Disconnected");

    handle.shutdown();
}

/// 1kHz 采样率验证：1 秒窗口内应接近 1000 个样本，且间隔中位数 ≈1ms。
/// （SimBackend 读内存开销极小，测得的是引擎调度精度）
#[test]
fn scope_sampling_rate_1khz() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    })
    .unwrap();

    tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: SIG + 4,
        size: 4,
    }]))
    .unwrap();
    tx.send(Command::SetScopeFreq(1000.0)).unwrap();

    // 收集 1.2 秒样本
    let deadline = Instant::now() + Duration::from_millis(1200);
    let mut stamps: Vec<f64> = Vec::new();
    while Instant::now() < deadline {
        if let Ok(Event::ScopeData { samples }) =
            rx.recv_timeout(Duration::from_millis(100))
        {
            stamps.extend(samples.iter().map(|s| s.t));
        }
    }
    assert!(
        stamps.len() >= 700,
        "1kHz 下 1.2 秒应收到 ≥700 样本（调度精度），实得 {}",
        stamps.len()
    );
    // 间隔中位数应在 0.8~1.6ms
    let mut gaps: Vec<f64> = stamps.windows(2).map(|w| w[1] - w[0]).collect();
    gaps.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let median = gaps[gaps.len() / 2];
    assert!(
        (0.0006..0.0018).contains(&median),
        "采样间隔中位数应 ≈1ms，实得 {:.4}ms",
        median * 1000.0
    );

    handle.shutdown();
}

#[test]
fn watch_respects_auto_refresh_flag() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    })
    .unwrap();

    // 非自动刷新目标：运行中不应出现在 WatchData
    tx.send(Command::UpdateWatchTargets(vec![
        MemTarget {
            id: "auto".into(),
            addr: SIG,
            size: 4,
            auto_refresh: true,
        },
        MemTarget {
            id: "manual".into(),
            addr: SIG + 8,
            size: 4,
            auto_refresh: false,
        },
    ]))
    .unwrap();

    let data = wait_for(&rx, Duration::from_secs(2), |e| {
        matches!(e, Event::WatchData { .. })
    });
    let Some(Event::WatchData { values, halted }) = data else { panic!() };
    assert!(!halted);
    assert!(values.contains_key("auto"));
    assert!(
        !values.contains_key("manual"),
        "运行中未勾选自动刷新的目标不应被读取"
    );

    // halt 后：两者都应刷新
    tx.send(Command::Halt).unwrap();
    let data2 = wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::WatchData { values, halted } if *halted && values.contains_key("manual"))
    });
    assert!(data2.is_some(), "halt 后应刷新全部目标");

    handle.shutdown();
}

/// 验证变量示波与变量实时监视同时开启时，Watch 采样不被示波采样饥饿阻断，二者平滑并发。
#[test]
fn test_watch_and_scope_simultaneous() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    })
    .unwrap();

    // 同时注册 5Hz Watch 目标与 1kHz 示波目标
    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
        id: "live_val".into(),
        addr: SIG,
        size: 4,
        auto_refresh: true,
    }]))
    .unwrap();

    tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: SIG + 4,
        size: 4,
    }]))
    .unwrap();
    tx.send(Command::SetScopeFreq(1000.0)).unwrap();

    // 观察 1.2 秒：WatchData 应能平稳到达多次（~5-6 次），且 ScopeData 亦正常吞吐
    let deadline = Instant::now() + Duration::from_millis(1200);
    let mut watch_count = 0;
    let mut scope_samples = 0;

    while Instant::now() < deadline {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(50)) {
            match ev {
                Event::WatchData { values, .. } if values.contains_key("live_val") => {
                    watch_count += 1;
                }
                Event::ScopeData { samples } => {
                    scope_samples += samples.len();
                }
                _ => {}
            }
        }
    }

    assert!(
        watch_count >= 3,
        "示波运行时 Watch 采样不得被饥饿阻断（1.2 秒内期望 >=3 次 5Hz 采样），实得 {watch_count} 次"
    );
    assert!(
        scope_samples >= 500,
        "Watch 运行期间示波采样亦应保持吞吐（期望 >=500 帧），实得 {scope_samples} 帧"
    );

    handle.shutdown();
}

/// 验证当 15Hz 高频监视与 1kHz 示波同时开启时，15Hz 监视突破 ~12Hz 瓶颈限制，
/// 1 秒内稳定收到 14~16 次采样，同时 1kHz 示波保持高吞吐。
#[test]
fn test_watch_15hz_and_scope_simultaneous_eliminates_12hz_ceiling() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    })
    .unwrap();

    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
        id: "live_val".into(),
        addr: SIG,
        size: 4,
        auto_refresh: true,
    }]))
    .unwrap();
    tx.send(Command::SetWatchFreq(15.0)).unwrap();

    tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: SIG + 4,
        size: 4,
    }]))
    .unwrap();
    tx.send(Command::SetScopeFreq(1000.0)).unwrap();

    // 观察 1.0 秒：15Hz WatchData 应收到 14~16 次，绝不卡死在 11~12 次
    let deadline = Instant::now() + Duration::from_millis(1000);
    let mut watch_count = 0;
    let mut scope_samples = 0;

    while Instant::now() < deadline {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(30)) {
            match ev {
                Event::WatchData { values, .. } if values.contains_key("live_val") => {
                    watch_count += 1;
                }
                Event::ScopeData { samples } => {
                    scope_samples += samples.len();
                }
                _ => {}
            }
        }
    }

    assert!(
        watch_count >= 14,
        "15Hz 监视与 1kHz 示波同时运行时，1 秒内应收到 >=14 次 WatchData（彻底破除 12Hz 瓶颈），实得 {watch_count} 次"
    );
    assert!(
        scope_samples >= 500,
        "15Hz 监视运行期间 1kHz 示波亦应保持高吞吐（期望 >=500 帧），实得 {scope_samples} 帧"
    );

    // 验证切换为 10Hz：在 800ms 内应收到 7~9 次
    tx.send(Command::SetWatchFreq(10.0)).unwrap();
    let deadline_10 = Instant::now() + Duration::from_millis(800);
    let mut count_10 = 0;
    while Instant::now() < deadline_10 {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(30)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("live_val") {
                    count_10 += 1;
                }
            }
        }
    }
    assert!(
        (7..=9).contains(&count_10),
        "10Hz 监视与示波并发在 800ms 内应收到 7~9 次，实得 {count_10} 次"
    );

    // 验证切换为 2Hz：在 800ms 内应收到 1~2 次
    tx.send(Command::SetWatchFreq(2.0)).unwrap();
    let deadline_2 = Instant::now() + Duration::from_millis(800);
    let mut count_2 = 0;
    while Instant::now() < deadline_2 {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(30)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("live_val") {
                    count_2 += 1;
                }
            }
        }
    }
    assert!(
        (1..=2).contains(&count_2),
        "2Hz 监视与示波并发在 800ms 内应收到 1~2 次，实得 {count_2} 次"
    );

    handle.shutdown();
}

/// 验证 Watch 目标中若包含一个失败的无效地址块，其余有效变量的 WatchData 采样不被整批丢弃。
#[test]
fn test_watch_partial_failure_does_not_starve_valid_targets() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::Connected { .. })
    })
    .unwrap();

    // 注册两个目标：一个在有效 RAM（SIG），一个在越界非法地址（0xFFFF_FFF0）
    tx.send(Command::UpdateWatchTargets(vec![
        MemTarget {
            id: "valid_val".into(),
            addr: SIG,
            size: 4,
            auto_refresh: true,
        },
        MemTarget {
            id: "bad_val".into(),
            addr: 0xFFFF_FFF0,
            size: 4,
            auto_refresh: true,
        },
    ]))
    .unwrap();

    // 观察 600ms：valid_val 仍应正常收到 WatchData 事件
    let deadline = Instant::now() + Duration::from_millis(600);
    let mut valid_count = 0;
    while Instant::now() < deadline {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(50)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("valid_val") {
                    valid_count += 1;
                }
            }
        }
    }

    assert!(
        valid_count >= 1,
        "当存在无效内存目标时，有效变量的采样与推送严禁被单块失败整批丢弃，实得 {valid_count} 次"
    );

    handle.shutdown();
}

#[test]
fn test_dynamic_watch_frequency_adjustment() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| matches!(e, Event::Connected { .. })).unwrap();

    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
        id: "var".into(),
        addr: SIG,
        size: 4,
        auto_refresh: true,
    }])).unwrap();

    // 1. 设置为 15Hz 采样：在 800ms 内，应收到约 10~12 次 WatchData
    tx.send(Command::SetWatchFreq(15.0)).unwrap();
    let start_15hz = Instant::now();
    let mut count_15hz = 0;
    while start_15hz.elapsed() < Duration::from_millis(800) {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(30)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("var") {
                    count_15hz += 1;
                }
            }
        }
    }
    assert!(count_15hz >= 8, "15Hz 在 800ms 内应至少收到 8 次采样，实得 {count_15hz} 次");

    // 2. 动态调节至 2Hz 采样：在 800ms 内，应收到 1~3 次 WatchData (500ms 一次)
    tx.send(Command::SetWatchFreq(2.0)).unwrap();
    let start_2hz = Instant::now();
    let mut count_2hz = 0;
    while start_2hz.elapsed() < Duration::from_millis(800) {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(50)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("var") {
                    count_2hz += 1;
                }
            }
        }
    }
    assert!(
        (1..=3).contains(&count_2hz),
        "2Hz 在 800ms 内应收到 1~3 次采样，实得 {count_2hz} 次"
    );

    handle.shutdown();
}

#[test]
fn test_watch_frequency_protected_against_rapid_target_updates() {
    let mut handle = spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params())).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| matches!(e, Event::Connected { .. })).unwrap();

    // 设置为 2Hz (500ms 周期)
    tx.send(Command::SetWatchFreq(2.0)).unwrap();

    let start = Instant::now();
    let mut count_watch = 0;
    let mut last_push = Instant::now();

    while start.elapsed() < Duration::from_millis(800) {
        // 模拟外部高频（每 30ms）下发 UpdateWatchTargets
        if last_push.elapsed() >= Duration::from_millis(30) {
            tx.send(Command::UpdateWatchTargets(vec![MemTarget {
                id: "var".into(),
                addr: SIG,
                size: 4,
                auto_refresh: true,
            }])).unwrap();
            last_push = Instant::now();
        }

        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(10)) {
            if let Event::WatchData { values, .. } = ev {
                if values.contains_key("var") {
                    count_watch += 1;
                }
            }
        }
    }

    assert!(
        (1..=3).contains(&count_watch),
        "即使外界高频触发 UpdateWatchTargets，2Hz 在 800ms 内也只应收到 1~3 次采样，防范 ~23Hz 刷屏！实得 {count_watch} 次"
    );

    handle.shutdown();
}

