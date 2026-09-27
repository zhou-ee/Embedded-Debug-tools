//! 诊断：主线程直跑烧录（panic 直接可见），打印全部事件。
//! 用法: flash_test [elf] [speed_hz] [target_name（空=自动）]
fn main() {
    let elf = std::env::args()
        .nth(1)
        .unwrap_or_else(|| r"E:\Software\Develop\Embeded\Pack\g4_tool_test\build\g4_tool_test.elf".into());
    let speed: u32 = std::env::args()
        .nth(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(2_000_000);
    let target = std::env::args().nth(3).unwrap_or_default();
    println!(
        "烧录 {elf}，SWD {speed} Hz，目标: {}",
        if target.is_empty() { "(自动)".to_string() } else { target.clone() }
    );
    let result = debug_core::flash::flash_firmware(
        &target,
        speed,
        std::path::Path::new(&elf),
        debug_core::flash::FlashMode::Run,
        |ev| match ev {
            debug_core::flash::FlashEvent::Log { text } => println!("[log] {text}"),
            debug_core::flash::FlashEvent::Progress { phase, percent } => {
                println!("[progress] {phase} {:.0}%", percent)
            }
            debug_core::flash::FlashEvent::Done { success, message } => {
                println!("[done] success={success} {message}")
            }
        },
    );
    println!("结果: {result:?}");
}
