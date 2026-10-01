//! 块大小扫描：probe-rs BlockTransfer 在 Flash Pro 克隆探针上的 NoAck 阈值
use probe_rs::{MemoryInterface, Permissions};
use std::time::Instant;

fn main() {
    let speed: u32 = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(4_000_000);
    println!("SWD {speed} Hz");
    let (mut probe, _) = debug_core::probe::open_first_available(None).expect("探针");
    probe.set_speed(speed).unwrap();
    let mut session = probe.attach("STM32G431CBTx", Permissions::default()).expect("attach");
    let mut core = session.core(0).expect("core");
    const ADDR: u64 = 0x2000_0094;
    for &size in &[4usize, 8, 12, 16, 24, 32, 48, 64, 96, 128] {
        let mut words = vec![0u32; size / 4];
        let mut ok = 0usize;
        let t0 = Instant::now();
        let rounds = 30;
        for _ in 0..rounds {
            match core.read_32(ADDR, &mut words) {
                Ok(_) => ok += 1,
                Err(_) => { let _ = core.read_32(ADDR, &mut words); }
            }
        }
        let us = t0.elapsed().as_micros() as f64 / rounds as f64;
        println!("{size:4}B: 成功 {ok}/{rounds}  平均 {us:.0} µs/次");
    }
}
