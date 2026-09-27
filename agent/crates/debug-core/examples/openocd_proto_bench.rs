//! 诊断：OpenOCD Tcl 通道的协议变体成本对比（mem2array / 拼包流水线 / dap 底层）。
use debug_core::openocd::OpenOcdBackend;
use debug_core::DebugBackend;
use std::io::Write;
use std::time::Instant;

const ADDR: u64 = 0x2000_0094; // g4_tool_test sin_5hz
const N: usize = 128;

fn main() {
    let openocd = std::env::var("OPENOCD_BIN")
        .unwrap_or_else(|_| "openocd".to_string());
    let scripts = std::env::var("OPENOCD_SCRIPTS").ok().map(std::path::PathBuf::from);
    let cfg = std::env::temp_dir().join("openocd_proto.cfg");
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();

    let mut be = OpenOcdBackend::new(openocd, cfg.to_string_lossy().into_owned(), scripts, 2_000_000);
    be.connect().expect("连接失败");

    // 1. read_memory 基线（当前实现）
    for _ in 0..20 {
        be.read_bytes(ADDR, 4).unwrap();
    }
    let t0 = Instant::now();
    for _ in 0..N {
        be.read_bytes(ADDR, 4).unwrap();
    }
    println!(
        "read_memory w8 x4（现状）: {:.0} µs/次 → {:.0} Hz",
        t0.elapsed().as_micros() as f64 / N as f64,
        N as f64 / t0.elapsed().as_secs_f64()
    );

    // 2. mem2array + 取值（一条帧内完成）
    let _ = be.tcl("mem2array pa 32 0x20000094 1");
    for _ in 0..20 {
        be.tcl("mem2array pa 32 0x20000094 1; set pa(0)").unwrap();
    }
    let t0 = Instant::now();
    for _ in 0..N {
        be.tcl("mem2array pa 32 0x20000094 1; set pa(0)").unwrap();
    }
    println!(
        "mem2array+set（单帧）: {:.0} µs/次 → {:.0} Hz",
        t0.elapsed().as_micros() as f64 / N as f64,
        N as f64 / t0.elapsed().as_secs_f64()
    );

    // 3. 拼包流水线：8 条 read_memory 拼成一个 TCP 包发送，再收 8 帧
    for _ in 0..2 {
        be.tcl("read_memory 0x20000094 8 4").unwrap();
    }
    let mut packed = Vec::new();
    for _ in 0..8 {
        packed.extend_from_slice(format!("read_memory 0x{ADDR:x} 8 4").as_bytes());
        packed.push(0x1a);
    }
    let rounds = N / 8;
    let t0 = Instant::now();
    for _ in 0..rounds {
        // 用底层写入接口一次性发送 8 条；这里通过公开 tcl 接口不可行，
        // 直接经由 DebugBackend 不暴露原始 socket —— 用连续 8 次 tcl_send 语义
        // 由 read_bytes 模拟不可行，改为：一次性 write 由 OpenOcdBackend 不支持，
        // 故此段仅测"连续 enqueue 后统一读"等效路径：
        for _ in 0..8 {
            be.tcl("read_memory 0x20000094 8 4").unwrap();
        }
    }
    let us = t0.elapsed().as_micros() as f64 / (rounds * 8) as f64;
    println!(
        "连续 8 命令（逐条 write）: {us:.0} µs/次 → {:.0} Hz",
        1e6 / us
    );

    // 4. dap 底层寄存器命令可用性与成本
    match be.tcl("dap apreg 0 0x0c") {
        Ok(r) => {
            for _ in 0..20 {
                be.tcl("dap apreg 0 0x0c").unwrap();
            }
            let t0 = Instant::now();
            for _ in 0..200 {
                be.tcl("dap apreg 0 0x0c").unwrap();
            }
            println!(
                "[dap apreg] 可用，{:.0} µs/次（响应 {:.20}）",
                t0.elapsed().as_micros() as f64 / 200.0,
                r.trim()
            );
        }
        Err(e) => println!("[dap apreg] 不可用: {e:.60}"),
    }
    let _ = std::io::stdout().flush();
}
