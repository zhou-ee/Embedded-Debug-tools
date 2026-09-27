//! 诊断/验证：OpenOCD 后端断点命中全流程 + 进程释放。
//! 场景：连接 → 断点@main → 复位运行 → 命中 → 寄存器/栈（sp 贴 RAM 顶的
//! 越界投机读应静默重试，不产生 error 事件）→ 断开 → openocd 进程应退出。
use debug_core::{BackendKind, ConnectParams};
use monitor::{BreakpointDef, Command, Event};
use std::time::{Duration, Instant};

fn main() {
    let openocd = std::env::var("OPENOCD_BIN")
        .unwrap_or_else(|_| "openocd".to_string());
    let scripts = std::env::var("OPENOCD_SCRIPTS").ok();
    let cfg = std::env::temp_dir().join("openocd_engine_test.cfg");
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();

    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(ConnectParams {
        kind: BackendKind::Openocd,
        target: Some("STM32G431CBTx".into()),
        cfg_file: Some(cfg.to_string_lossy().into_owned()),
        openocd_path: Some(openocd),
        scripts_dir: scripts,
        speed_hz: 2_000_000,
        ..Default::default()
    }))
    .unwrap();

    let main_addr = 0x0800_1134u64; // g4_tool_test 当前构建的 main 入口
    let mut errors: Vec<String> = Vec::new();
    let mut got_hit = false;
    let mut got_regs = false;
    let mut got_connected = false;

    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => {
                got_connected = true;
                println!("[Connected] {description}");
                tx.send(Command::SetBreakpoints(vec![BreakpointDef {
                    addr: main_addr,
                    condition: String::new(),
                }]))
                .unwrap();
                tx.send(Command::Reset).unwrap();
            }
            Ok(Event::BreakpointHit { pc, condition_error }) => {
                got_hit = true;
                println!("[BreakpointHit] pc={pc:#x} err={condition_error:?}");
                tx.send(Command::RequestRegsAndStack).unwrap();
            }
            Ok(Event::RegsAndStack { regs, stack_base: _, stack }) => {
                got_regs = true;
                println!(
                    "[RegsAndStack] pc={:#x} sp={:#x} stack={}B",
                    regs.get("pc").copied().unwrap_or(0),
                    regs.get("sp").copied().unwrap_or(0),
                    stack.len()
                );
                println!("[完成] 断点命中 + 寄存器/栈就绪");
                break;
            }
            Ok(Event::Error { message }) => {
                println!("[Error] {message}");
                errors.push(message);
            }
            Ok(Event::Log { message }) => println!("[Log] {message}"),
            _ => {}
        }
    }

    tx.send(Command::Disconnect).unwrap();
    handle.shutdown();
    std::thread::sleep(Duration::from_millis(500)); // 给 openocd 退出时间

    println!("connected={got_connected} hit={got_hit} regs={got_regs}");
    println!(
        "read_memory 类错误: {}",
        errors.iter().filter(|e| e.contains("read_memory")).count()
    );
    let bad = errors.iter().any(|e| e.contains("failed to read memory"));
    std::process::exit(if got_connected && got_hit && got_regs && !bad { 0 } else { 1 });
}
