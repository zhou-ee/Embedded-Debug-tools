//! 诊断：OpenOCD 后端单次 read_bytes 的耗时基线。
use debug_core::openocd::OpenOcdBackend;
use debug_core::DebugBackend;
use std::time::Instant;

fn main() {
    let openocd = std::env::var("OPENOCD_BIN")
        .unwrap_or_else(|_| "openocd".to_string());
    let scripts = std::env::var("OPENOCD_SCRIPTS").ok().map(std::path::PathBuf::from);
    let cfg = std::env::temp_dir().join("openocd_bench.cfg");
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();

    let mut be = OpenOcdBackend::new(openocd, cfg.to_string_lossy().into_owned(), scripts, 2_000_000);
    be.connect().expect("OpenOCD 连接失败");

    let n = 1000;
    for _ in 0..50 {
        be.read_bytes(0x2000_0028, 4).unwrap();
    }
    let t0 = Instant::now();
    for _ in 0..n {
        be.read_bytes(0x2000_0028, 4).unwrap();
    }
    let us = t0.elapsed().as_micros() as f64 / n as f64;
    println!("read_bytes(4B): {us:.1} µs/次 → {:.0} Hz 上限", 1e6 / us);

    for _ in 0..20 {
        be.read_bytes(0x2000_0028, 64).unwrap();
    }
    let t0 = Instant::now();
    for _ in 0..200 {
        be.read_bytes(0x2000_0028, 64).unwrap();
    }
    let us = t0.elapsed().as_micros() as f64 / 200.0;
    println!("read_bytes(64B): {us:.1} µs/次 → {:.0} Hz 上限", 1e6 / us);

    // is_halted（引擎 poll_state 用）
    for _ in 0..20 {
        be.is_halted().unwrap();
    }
    let t0 = Instant::now();
    for _ in 0..200 {
        be.is_halted().unwrap();
    }
    let us = t0.elapsed().as_micros() as f64 / 200.0;
    println!("is_halted: {us:.1} µs/次");
}
