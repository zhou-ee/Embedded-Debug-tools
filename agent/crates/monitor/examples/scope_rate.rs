//! 验证：示波 1kHz 实际达成率（probe-rs 与 OpenOCD 两后端对比）。
//! 用法: scope_rate [backend: pr|ocd] [秒数]
use debug_core::{BackendKind, ConnectParams};
use monitor::{Command, Event, ScopeTarget};
use std::time::{Duration, Instant};

fn connect_params(backend: &str) -> ConnectParams {
    ConnectParams {
        kind: if backend == "ocd" {
            BackendKind::Openocd
        } else {
            BackendKind::ProbeRs
        },
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
    }
}

fn main() {
    let backend = std::env::args().nth(1).unwrap_or_else(|| "pr".into());
    let secs: u64 = std::env::args().nth(2).and_then(|s| s.parse().ok()).unwrap_or(3);
    if backend == "ocd" {
        let cfg = std::env::temp_dir().join("scope_rate.cfg");
        std::fs::write(
            &cfg,
            "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
        )
        .unwrap();
    }

    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params(&backend))).unwrap();
    let t_connect = Instant::now();
    let mut connected = false;
    while t_connect.elapsed() < Duration::from_secs(15) {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => {
                connected = true;
                println!("[{backend}] {description}");
                break;
            }
            Ok(Event::Log { message }) => println!("    {message}"),
            _ => {}
        }
    }
    assert!(connected, "连接失败");

    // g4_tool_test 新固件的 sin_5hz @ 0x20000094（nm 实测）
    tx.send(Command::UpdateScopeTargets(vec![ScopeTarget {
        addr: 0x2000_0094,
        size: 4,
    }]))
    .unwrap();
    tx.send(Command::SetScopeFreq(1000.0)).unwrap();

    let deadline = Instant::now() + Duration::from_secs(secs);
    let mut stamps: Vec<f64> = Vec::new();
    while Instant::now() < deadline {
        if let Ok(Event::ScopeData { samples }) = rx.recv_timeout(Duration::from_millis(100)) {
            stamps.extend(samples.iter().map(|s| s.t));
        }
    }
    let wall = secs as f64;
    let rate = stamps.len() as f64 / wall;
    println!(
        "[{backend}] {secs}s 采集 {} 样本 → {rate:.0} Hz（达成 {:.1}%）",
        stamps.len(),
        rate / 10.0
    );
    // 两种口径必须分开看，否则会得出互相矛盾的"实际采样率"：
    //   · 到达率 = 行数 / 真实墙钟时间   ← 界面"实际 xxHz"用的是这个
    //   · 时间戳率 = 行数 / 时间戳跨度   ← 导出 CSV 后按 time 列算出来的是这个
    // 两者之差 = 批与批之间的时间戳空档（每批内部时间戳是理想等间隔的）。
    if stamps.len() > 2 {
        let span = stamps[stamps.len() - 1] - stamps[0];
        println!(
            "    时间戳跨度 {span:.3}s（墙钟 {wall:.3}s，差 {:.3}s）",
            wall - span
        );
        println!(
            "    到达率 {:.0} Hz  vs  时间戳率 {:.0} Hz",
            stamps.len() as f64 / wall,
            (stamps.len() - 1) as f64 / span
        );
        let mut gaps: Vec<f64> = stamps.windows(2).map(|w| w[1] - w[0]).collect();
        gaps.sort_by(|a, b| a.partial_cmp(b).unwrap());
        println!(
            "    间隔: 中位 {:.2}ms / p95 {:.2}ms / 最大 {:.2}ms",
            gaps[gaps.len() / 2] * 1000.0,
            gaps[gaps.len() * 95 / 100] * 1000.0,
            gaps.last().unwrap() * 1000.0
        );
    }
    handle.shutdown();
}
