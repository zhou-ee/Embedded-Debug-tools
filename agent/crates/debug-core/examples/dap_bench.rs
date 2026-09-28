//! 诊断：单次 4 字节 DAP 读的耗时分解（确定示波 1kHz 的可达性）。
//! 用法: dap_bench [speed_hz]
use probe_rs::{MemoryInterface, Permissions};
use std::time::Instant;

const UW_TICK: u64 = 0x2000_0028;
const N: usize = 2000;

fn bench(name: &str, mut f: impl FnMut()) {
    // 预热
    for _ in 0..50 {
        f();
    }
    let t0 = Instant::now();
    for _ in 0..N {
        f();
    }
    let d = t0.elapsed();
    let us = d.as_micros() as f64 / N as f64;
    println!("{name}: {us:.1} µs/次 → {max:.0} Hz 上限", max = 1e6 / us);
}

fn main() {
    let speed: u32 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(2_000_000);
    println!("SWD {speed} Hz");

    let (mut probe, ident) = debug_core::probe::open_first_available(None).expect("探针");
    println!("探针 {ident}");
    let _ = probe.set_speed(speed / 1000);
    let mut session = probe
        .attach("STM32G431CBTx", Permissions::default().allow_erase_all())
        .expect("附加");

    // 1. 缓存 Core：纯读路径下限
    {
        let mut core = session.core(0).unwrap();
        bench("缓存 Core read_8(4B)", || {
            let mut b = [0u8; 4];
            core.read_8(UW_TICK, &mut b).unwrap();
        });
        // 大块读：验证固定延迟 vs 载荷
        bench("缓存 Core read_8(64B)", || {
            let mut b = [0u8; 64];
            core.read_8(UW_TICK, &mut b).unwrap();
        });
    }

    // 2. 每次调用 core()（模拟 ProbeRsBackend::read_bytes 的引擎路径）
    bench("每次 session.core(0) + read_8(4B)", || {
        let mut core = session.core(0).unwrap();
        let mut b = [0u8; 4];
        core.read_8(UW_TICK, &mut b).unwrap();
    });
}
