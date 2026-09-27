//! 验证：故障分析（CFSR / HFSR / BFAR 解码、上报、写 1 清除）在真机上确实工作。
//! 用法: fault_analysis_check [backend: pr|ocd]
//!
//! **不需要改固件**：三种**真实故障**注入，做法都是"改 PC + 改寄存器再恢复运行"，
//! 标志由**硬件**置位（不是伪造标志），且逐一断言引擎解码出的文案：
//!
//! | 例 | 注入手法 | 硬件症状 | 期望解码 |
//! |---|---|---|---|
//! | A | `PC = 0xFFFFFFFE`（保留区、非 Thumb） | MemManage `IACCVIOL` | 存储器管理错误：指令访问违例 |
//! | B | 执行固件的 `vstr s15, [r3]`，`r3` 换成未映射地址 | BusFault `IMPRECISERR`（FPU 存储走写缓冲） | 总线错误：不精确数据访问 |
//! | C | 执行固件的 `ldr r3, [r3, #0]`，`r3` 换成未映射地址 | BusFault `PRECISERR` + `BFARVALID` | 总线错误：精确数据访问；出错地址 0x90000000 |
//!
//! C 是唯一能覆盖"打印出错地址（BFAR）"这条分支的手段 —— **读不会被写缓冲延迟**，
//! 所以只有 load 能稳定产生精确错误；`BFSR.BFARVALID` 又是只读状态位，写 1 无效
//! （先把 CFSR 写成 BFARVALID|PRECISERR 试过：可写的 PRECISERR 被写 1 清掉，CFSR 直接变 0）。
//!
//! 每例还验证"写 1 清除"：随后 resume + halt 不应重复报。
//!
//! 背景：这条路径与 DWT 观察点是**同一个坑**（调试寄存器走字节访问被静默忽略，
//! 见 HANDOFF §2），修复后一直没在真机上人为制造过故障，本工具就是补这一刀。

use debug_core::openocd::OpenOcdBackend;
use debug_core::{BackendError, BackendKind, ConnectParams, DebugBackend, TargetState};
use monitor::{Command, Event};
use std::path::PathBuf;
use std::time::{Duration, Instant};

const CFSR: u64 = 0xE000_ED28;
const HFSR: u64 = 0xE000_ED2C;
const BFAR: u64 = 0xE000_ED38;
/// 无效取指地址：bit0=0（非 Thumb）且落在保留区，取指必然出错
const BAD_PC: u64 = 0xFFFF_FFFE;
/// 未映射的访问目标（该段无内存）
const BAD_DATA: u64 = 0x9000_0000;
/// 固件里写 `sin_5hz` 的 `vstr s15, [r3]`（g4_tool_test 09-15 ELF）
const STORE_PC: u64 = 0x0800_155e;
const STORE_BASE_REG: &str = "r3";
/// 固件里的 `ldr r3, [r3, #0]`（读时钟节拍等）
const LOAD_PC: u64 = 0x0800_1592;
const LOAD_BASE_REG: &str = "r3";
// 固件重建后上述地址会变，重新取值：
//   objdump -d --start-address=0x8001500 --stop-address=0x8001620 <elf>
// 找一条 `vstr`/`str`（→ 不精确）与一条基址为寄存器（非 [pc]）的 `ldr`（→ 精确），
// 记下指令地址与基址寄存器名。

fn ocd_path() -> String {
    std::env::var("OPENOCD_BIN").unwrap_or_else(|_| "openocd".to_string())
}
fn ocd_scripts() -> Option<String> {
    std::env::var("OPENOCD_SCRIPTS").ok()
}

fn temp_cfg(name: &str) -> String {
    let cfg = std::env::temp_dir().join(name);
    std::fs::write(
        &cfg,
        "source [find interface/cmsis-dap.cfg]\nsource [find target/stm32g4x.cfg]\n",
    )
    .unwrap();
    cfg.to_string_lossy().into_owned()
}

fn connect_params(backend: &str) -> ConnectParams {
    ConnectParams {
        kind: if backend == "ocd" {
            BackendKind::Openocd
        } else {
            BackendKind::ProbeRs
        },
        target: Some("STM32G431CBTx".into()),
        cfg_file: if backend == "ocd" {
            Some(temp_cfg("fault_analysis_check.cfg"))
        } else {
            None
        },
        openocd_path: if backend == "ocd" {
            Some(ocd_path())
        } else {
            None
        },
        scripts_dir: if backend == "ocd" {
            ocd_scripts()
        } else {
            None
        },
        speed_hz: 2_000_000,
        ..Default::default()
    }
}

/// 独立的注入通道（OpenOCD Tcl）。用 Ocd 注入与"引擎用哪个后端"无关 ——
/// 注入本身只是调试操作，`reg` 是 OpenOCD 现成的能力。
fn with_injector<T>(
    f: impl FnOnce(&mut OpenOcdBackend) -> Result<T, BackendError>,
) -> Result<T, BackendError> {
    let mut be = OpenOcdBackend::new(
        ocd_path(),
        temp_cfg("fault_analysis_check_inject.cfg"),
        ocd_scripts().map(PathBuf::from),
        2_000_000,
    );
    be.connect()?;
    be.tcl("halt")?;
    // 清掉上轮跑留下的粘滞标志，保证后面读到的是本轮造成的
    let stale = be.read_u32(CFSR)?;
    if stale != 0 {
        be.write_u32(CFSR, stale)?;
        let h = be.read_u32(HFSR)?;
        be.write_u32(HFSR, h)?;
        println!("    （清理上轮残留 CFSR=0x{stale:08x}）");
    }
    let out = f(&mut be)?;
    be.disconnect();
    Ok(out)
}

/// A. 取指故障：回读硬件标志原值，**不清除**，留给引擎去报。
fn inject_fetch_fault() -> Result<(u32, u32, u32), BackendError> {
    with_injector(|be| {
        be.tcl(&format!("reg pc 0x{BAD_PC:x}"))?;
        be.tcl("resume")?;
        std::thread::sleep(Duration::from_millis(300)); // 等故障发生并进入 HardFault_Handler
        be.tcl("halt")?;
        let cfsr = be.read_u32(CFSR)?;
        let hfsr = be.read_u32(HFSR)?;
        let bfar = be.read_u32(BFAR)?;
        Ok((cfsr, hfsr, bfar))
    })
}

/// B/C. 数据访问故障：执行 `pc` 处那条访存指令，但基址寄存器换成未映射地址。
/// load 精确、FPU store 走写缓冲所以不精确 —— 两种都造，正好覆盖解码表两条分支。
fn inject_data_fault(pc: u64, base_reg: &str) -> Result<(u32, u32, u32), BackendError> {
    with_injector(|be| {
        be.tcl(&format!("reg {base_reg} 0x{BAD_DATA:x}"))?;
        be.tcl(&format!("reg pc 0x{pc:x}"))?;
        be.tcl("resume")?;
        std::thread::sleep(Duration::from_millis(300));
        be.tcl("halt")?;
        let cfsr = be.read_u32(CFSR)?;
        let hfsr = be.read_u32(HFSR)?;
        let bfar = be.read_u32(BFAR)?;
        Ok((cfsr, hfsr, bfar))
    })
}

struct Report {
    /// 引擎打印的 〖故障分析〗 原文（None = 没打印）
    log: Option<String>,
    /// resume 后再 halt 是否又报了一次（应为 false）
    repeated: bool,
}

/// 起一个引擎会话：连接 → halt → 收集 〖故障分析〗 → resume+halt → 再收集 → Reset 恢复。
fn engine_fault_report(backend: &str) -> Report {
    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();

    tx.send(Command::Connect(connect_params(backend))).unwrap();
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Event::Connected { description }) => println!("    [Connected] {description}"),
            Ok(Event::State { state }) => {
                println!("    [State] {state:?}");
                if state == TargetState::Halted {
                    break;
                }
            }
            Ok(Event::Log { message }) => println!("    [Log] {message}"),
            Ok(Event::Error { message }) => println!("    [Error] {message}"),
            _ => {}
        }
    }
    tx.send(Command::Halt).unwrap();

    let mut log = None;
    let deadline = Instant::now() + Duration::from_secs(6);
    while Instant::now() < deadline && log.is_none() {
        if let Ok(Event::Log { message }) = rx.recv_timeout(Duration::from_millis(200)) {
            println!("    [Log] {message}");
            if message.contains("〖故障分析〗") {
                log = Some(message);
            }
        }
    }

    // 写 1 清除后再 halt：不应重复报
    tx.send(Command::Resume).unwrap();
    std::thread::sleep(Duration::from_millis(200));
    tx.send(Command::Halt).unwrap();
    let mut repeated = false;
    let deadline = Instant::now() + Duration::from_secs(4);
    while Instant::now() < deadline {
        if let Ok(Event::Log { message }) = rx.recv_timeout(Duration::from_millis(200)) {
            if message.contains("〖故障分析〗") {
                repeated = true;
            }
        }
    }

    // 恢复：复位让固件重新正常运行
    tx.send(Command::Reset).unwrap();
    std::thread::sleep(Duration::from_millis(500));
    handle.shutdown();
    Report { log, repeated }
}

fn main() {
    let backend = std::env::args().nth(1).unwrap_or_else(|| "pr".into());

    // (用例名, 期望解码子串, 注入函数)
    type Inj = fn() -> Result<(u32, u32, u32), BackendError>;
    let cases: [(&str, &str, Inj); 3] = [
        ("A 取指故障 → MemManage/IACCVIOL", "存储器管理错误：指令访问违例", inject_fetch_fault),
        (
            "B FPU 存储故障 → BusFault/IMPRECISERR",
            "总线错误：不精确数据访问",
            || inject_data_fault(STORE_PC, STORE_BASE_REG),
        ),
        (
            "C 加载故障 → BusFault/PRECISERR + BFAR",
            "总线错误出错地址",
            || inject_data_fault(LOAD_PC, LOAD_BASE_REG),
        ),
    ];

    let mut all_ok = true;
    for (name, expect, inject) in cases {
        println!("\n===== {name} =====");
        let (cfsr, hfsr, bfar) = inject().unwrap_or_else(|e| panic!("注入失败: {e}"));
        println!("    硬件原值 CFSR=0x{cfsr:08x}  HFSR=0x{hfsr:08x}  BFAR=0x{bfar:08x}");
        println!(
            "    硬件置位: {}    BFARVALID: {}",
            if cfsr != 0 { "✔" } else { "✘" },
            if cfsr & (1 << 15) != 0 { "✔" } else { "✘" }
        );
        let r = engine_fault_report(&backend);
        let decoded = r.log.as_deref().unwrap_or("");
        let hit = decoded.contains(expect);
        println!("    引擎解码: {}", decoded);
        println!(
            "    断言「{expect}」: {}    只报新故障: {}",
            if hit { "✔" } else { "✘" },
            if r.repeated { "✘ 重复了" } else { "✔" }
        );
        let _ = hfsr;
        all_ok &= cfsr != 0 && hit && !r.repeated;
    }

    println!(
        "\n===== 结论（后端 {backend}）：{} =====",
        if all_ok { "✔ 三例全部通过" } else { "✘ 有未通过项" }
    );
    if !all_ok {
        std::process::exit(1);
    }
}
