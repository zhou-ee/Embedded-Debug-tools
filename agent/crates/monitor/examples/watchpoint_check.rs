//! 验证：DWT 数据观察点（对 sin_5hz 的写访问停机）。
//! 用法: watchpoint_check [backend: pr|ocd]
use debug_core::{BackendKind, ConnectParams};
use monitor::{Command, Event};
use std::time::{Duration, Instant};

fn main() {
    let backend = std::env::args().nth(1).unwrap_or_else(|| "pr".into());
    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(ConnectParams {
        kind: if backend == "ocd" { BackendKind::Openocd } else { BackendKind::ProbeRs },
        target: Some("STM32G431CBTx".into()),
        cfg_file: if backend == "ocd" {
            Some(std::env::temp_dir().join("scope_rate.cfg").to_string_lossy().into_owned())
        } else {
            None
        },
        openocd_path: if backend == "ocd" {
            Some(std::env::var("OPENOCD_BIN").unwrap_or_else(|_| "openocd".to_string()))
        } else {
            None
        },
        scripts_dir: if backend == "ocd" {
            std::env::var("OPENOCD_SCRIPTS").ok()
        } else {
            None
        },
        speed_hz: 2_000_000,
        ..Default::default()
    }))
    .unwrap();

    let mut halted = false;
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => println!("[Connected] {description}"),
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            Ok(Event::Error { message }) => println!("[Error] {message}"),
            Ok(Event::State { state }) => {
                println!("[State] {state:?}");
                if state == debug_core::TargetState::Running {
                    // 运行后设置观察点：sin_5hz 读写时停机
                    tx.send(Command::SetWatchpoints(vec![0x2000_0094])).unwrap();
                    println!("[已设观察点] 0x20000094，等待命中…");
                    halted = true;
                    break;
                }
            }
            _ => {}
        }
    }
    assert!(halted, "未进入运行态");

    // 等待观察点命中（主循环每圈都写 sin_5hz）
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut hit = false;
    let mut pc = 0u64;
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::State { state }) => {
                println!("[State] {state:?}");
                if state == debug_core::TargetState::Halted {
                    tx.send(Command::RequestRegsAndStack).unwrap();
                }
            }
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            Ok(Event::RegsAndStack { regs, .. }) => {
                pc = regs.get("pc").copied().unwrap_or(0) & !1;
                hit = true;
                println!("[命中停机] pc={pc:#x}");
                break;
            }
            _ => {}
        }
    }

    // 恢复运行 + 清除观察点
    tx.send(Command::SetWatchpoints(vec![])).unwrap();
    tx.send(Command::Resume).unwrap();
    println!("观察点命中验证: {hit}（pc={pc:#x}，应在 app_test_tick/sin_5hz 写入处附近）");
    handle.shutdown();
}
