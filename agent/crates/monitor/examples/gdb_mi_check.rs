//! 验证：GDB/MI 表达式求值（**仅 OpenOCD 后端**）在真机上工作。
//! 用法: gdb_mi_check [elf]
//!
//! 这是 `docs/parity-checklist.md`「待硬件验证清单」里最后一条。链路：引擎 →
//! `ensure_gdb`（spawn `arm-none-eabi-gdb --interpreter=mi2` → `-file-exec-and-symbols`
//! 载入 ELF → `-target-select extended-remote 127.0.0.1:3333`）→ halt 时
//! `-data-evaluate-expression` 求值 → `ExprData` 事件。
//!
//! 校验点：
//!   1. 四个表达式都返回、且都不含 `<error>`；
//!   2. **交叉校验**：同一符号 `uwTick` 同时用 Watch（引擎自己读内存）与 GDB（MI 求值）取，
//!      两者必须一致 —— 否则说明 GDB 路径拿到的不是同一个目标状态；
//!   3. 复杂表达式确实超出自带求值器能力：成员链 `g_motor.position.x`、带类型转换的
//!      `(int)(sin_5hz * 10)`（`conditions.rs` 只认寄存器/简单符号，做不了这些）。

use debug_core::{BackendKind, ConnectParams, TargetState};
use monitor::{Command, Event, ExprTarget, MemTarget};
use std::time::{Duration, Instant};

const UW_TICK: u64 = 0x2000_008c;
fn ocd_path() -> String {
    std::env::var("OPENOCD_BIN").unwrap_or_else(|_| "openocd".to_string())
}
fn ocd_scripts() -> Option<String> {
    std::env::var("OPENOCD_SCRIPTS").ok()
}
fn gdb_path() -> String {
    std::env::var("GDB_BIN").unwrap_or_else(|_| "arm-none-eabi-gdb".to_string())
}
fn elf_default() -> String {
    std::env::var("TEST_ELF_PATH").unwrap_or_else(|_| "g4_tool_test.elf".to_string())
}

fn temp_cfg() -> String {
    let cfg = std::env::temp_dir().join("gdb_mi_check.cfg");
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();
    cfg.to_string_lossy().into_owned()
}

fn main() {
    let elf = std::env::args()
        .nth(1)
        .unwrap_or_else(|| elf_default().into());
    println!("[ELF] {elf}");

    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(ConnectParams {
        kind: BackendKind::Openocd,
        target: Some("STM32G431CBTx".into()),
        cfg_file: Some(temp_cfg()),
        openocd_path: Some(ocd_path()),
        scripts_dir: ocd_scripts(),
        speed_hz: 2_000_000,
        ..Default::default()
    }))
    .unwrap();

    // 连上后：配 GDB + 下发表达式 + 挂一个 uwTick 的 Watch（用于交叉校验）
    let deadline = Instant::now() + Duration::from_secs(20);
    let mut connected = false;
    while Instant::now() < deadline && !connected {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => {
                println!("[Connected] {description}");
                connected = true;
            }
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            Ok(Event::Error { message }) => println!("[Error] {message}"),
            _ => {}
        }
    }
    assert!(connected, "OpenOCD 连接失败");

    tx.send(Command::ConfigureGdb {
        gdb_path: gdb_path(),
        elf_path: elf.clone(),
    })
    .unwrap();
    tx.send(Command::UpdateExprTargets(vec![
        ExprTarget {
            id: "tick".into(),
            expr: "uwTick".into(),
        },
        ExprTarget {
            id: "pos_x".into(),
            expr: "g_motor.position.x".into(),
        },
        ExprTarget {
            id: "scaled".into(),
            expr: "(int)(sin_5hz * 10)".into(),
        },
        ExprTarget {
            id: "state".into(),
            expr: "g_state".into(),
        },
    ]))
    .unwrap();
    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
        id: "tick_raw".into(),
        addr: UW_TICK,
        size: 4,
        auto_refresh: false,
    }]))
    .unwrap();

    // halt 触发求值（引擎在"停住"时才会 eval；GDB attach 到 :3333 本身也会 halt 目标）
    tx.send(Command::Halt).unwrap();

    let mut exprs: Option<std::collections::HashMap<String, String>> = None;
    let mut tick_raw: Option<u32> = None;
    let deadline = Instant::now() + Duration::from_secs(30);
    // 两条数据流（ExprData / WatchData）到达先后不定，都要等到，不能拿到一条就走
    while Instant::now() < deadline && (exprs.is_none() || tick_raw.is_none()) {
        match rx.recv_timeout(Duration::from_millis(300)) {
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            Ok(Event::Error { message }) => println!("[Error] {message}"),
            Ok(Event::State { state }) => {
                println!("[State] {state:?}");
                if state == TargetState::Halted {
                    // halt 后补一次 Watch 目标下发，触发一次读取（停止态下 auto_refresh=false 也会读）
                    tx.send(Command::UpdateWatchTargets(vec![MemTarget {
                        id: "tick_raw".into(),
                        addr: UW_TICK,
                        size: 4,
                        auto_refresh: false,
                    }]))
                    .unwrap();
                }
            }
            Ok(Event::WatchData { values, .. }) => {
                if let Some(b) = values.get("tick_raw") {
                    if b.len() >= 4 {
                        tick_raw = Some(u32::from_le_bytes([b[0], b[1], b[2], b[3]]));
                    }
                }
            }
            Ok(Event::ExprData { values }) => {
                exprs = Some(values);
            }
            _ => {}
        }
    }

    let Some(values) = exprs else {
        println!("\n✘ 未收到 ExprData —— GDB 求值没跑起来");
        handle.shutdown();
        std::process::exit(1);
    };

    println!("\n== GDB/MI 求值结果 ==");
    let mut ok = true;
    for (id, v) in &values {
        println!("  {id:<8} = {v}");
        if v.contains("<error") {
            ok = false;
        }
    }
    println!("  四个表达式全部返回且无 error: {}", if ok { "✔" } else { "✘" });

    // 交叉校验：GDB 的 uwTick 必须与 Watch 读到的内存一致
    let gdb_tick = values.get("tick").and_then(|v| v.trim().parse::<u32>().ok());
    println!("\n== 交叉校验 uwTick（{UW_TICK:#x}）==");
    println!("  Watch 直读内存 = {tick_raw:?}");
    println!("  GDB 求值       = {gdb_tick:?}");
    let cross = matches!((tick_raw, gdb_tick), (Some(a), Some(b)) if a == b);
    println!(
        "  两者一致: {}",
        if cross {
            "✔ 是".to_string()
        } else {
            "✘ 否".to_string()
        }
    );

    // 合理性：sin_5hz ∈ ±50 → (int)(sin_5hz*10) ∈ ±500
    if let Some(v) = values.get("scaled").and_then(|v| v.trim().parse::<i64>().ok()) {
        println!("\n  (int)(sin_5hz*10) = {v}（应在 ±500 内）: {}", if v.abs() <= 500 { "✔" } else { "✘" });
        ok &= v.abs() <= 500;
    } else {
        println!("\n  ✘ scaled 不是整数");
        ok = false;
    }

    tx.send(Command::Resume).unwrap();
    std::thread::sleep(Duration::from_millis(300));
    handle.shutdown();

    let pass = ok && cross;
    println!("\n===== 结论：GDB/MI 表达式求值 {} =====", if pass { "✔ 通过" } else { "✘ 未通过" });
    if !pass {
        std::process::exit(1);
    }
}
