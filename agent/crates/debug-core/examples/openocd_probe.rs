//! 诊断：探测 OpenOCD Tcl 服务可用命令与时钟源（为示波突发采样选方案）。
use debug_core::openocd::OpenOcdBackend;
use debug_core::DebugBackend;
use std::time::Instant;

fn main() {
    let openocd = std::env::var("OPENOCD_BIN")
        .unwrap_or_else(|_| "openocd".to_string());
    let scripts = std::env::var("OPENOCD_SCRIPTS").ok().map(std::path::PathBuf::from);
    let cfg = std::env::temp_dir().join("openocd_probe.cfg");
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();

    let mut be = OpenOcdBackend::new(openocd, cfg.to_string_lossy().into_owned(), scripts, 2_000_000);
    be.connect().expect("连接失败");

    // 1. 时钟源探测
    for cmd in ["ms", "clock milliseconds", "get_ticks", "time"] {
        match be.tcl(cmd) {
            Ok(resp) => println!("[{cmd}] → {:.60}", resp.trim()),
            Err(e) => println!("[{cmd}] → 错误: {e}"),
        }
    }

    // 2. 内联突发：一条命令内循环读 64 次，测单次成本（无节拍税）
    let burst = "proc bench_burst {addr} { \
        set o \"\"; \
        for {set i 0} {$i < 64} {incr i} { \
            append o [read_memory $addr 8 4]; append o \" \" \
        }; \
        return $o }";
    match be.tcl(burst) {
        Ok(r) => println!("[proc 定义] → {:.40}", r.trim()),
        Err(e) => println!("[proc 定义] 错误: {e}"),
    }
    // 预热
    let _ = be.tcl("bench_burst 0x20000028");
    let t0 = Instant::now();
    let resp = be.tcl("bench_burst 0x20000028").expect("burst 失败");
    let d = t0.elapsed();
    let vals: Vec<&str> = resp.split_whitespace().collect();
    println!(
        "内联突发 64 样本: {:.1} ms 总耗时 → {:.0} µs/样本 → {:.0} Hz（无节拍税）",
        d.as_secs_f64() * 1000.0,
        d.as_secs_f64() * 1e6 / 64.0,
        64.0 / d.as_secs_f64()
    );
    println!("前 8 个值: {:?}…", &vals[..vals.len().min(8)]);

    // 2b. 不同宽度/命令的成本对比（内联循环 ×64）
    for (name, cmd_fmt) in [
        ("read_memory w8  x4", "read_memory $addr 8 4"),
        ("read_memory w32 x1", "read_memory $addr 32 1"),
        ("mdw $addr", "mdw $addr"),
        ("read_memory w32 x16", "read_memory $addr 32 16"),
    ] {
        let def = format!(
            "proc bench2 {{addr}} {{ set o \"\"; for {{set i 0}} {{$i < 64}} {{incr i}} {{ append o [{cmd_fmt}]; append o \" \" }}; return $o }}"
        );
        if be.tcl(&def).is_err() {
            println!("[{name}] 定义失败");
            continue;
        }
        let _ = be.tcl("bench2 0x20000028");
        let t0 = Instant::now();
        if be.tcl("bench2 0x20000028").is_err() {
            println!("[{name}] 执行失败");
            continue;
        }
        let us = t0.elapsed().as_micros() as f64 / 64.0;
        println!("[{name}] {us:.0} µs/次 → {:.0} Hz", 1e6 / us);
    }

    // 3. 时钟存在时的节拍突发：1ms 间隔 × 64
    let paced = "proc bench_paced {addr} { \
        set o \"\"; \
        for {set i 0} {$i < 64} {incr i} { \
            set t0 [clock milliseconds]; \
            append o [read_memory $addr 8 4]; append o \" \"; \
            while {[clock milliseconds] <= $t0} {} ; \
            set t0 [clock milliseconds] \
        }; \
        return $o }";
    match be.tcl(paced) {
        Ok(_) => {
            let _ = be.tcl("bench_paced 0x20000028");
            let t0 = Instant::now();
            let resp = be.tcl("bench_paced 0x20000028").expect("paced 失败");
            let d = t0.elapsed();
            let vals: Vec<&str> = resp.split_whitespace().collect();
            println!(
                "时钟节拍突发 64 样本: {:.1} ms → {:.0} Hz",
                d.as_secs_f64() * 1000.0,
                64.0 / d.as_secs_f64()
            );
            println!("样本数: {}", vals.len());
        }
        Err(e) => println!("[paced 定义/执行] 错误: {e}"),
    }
}
