//! DWT 数据观察点诊断（实机）。
//!
//! 背景：引擎 `program_watchpoints` 用 FUNCTION=0b0111，2026-09-15 在 G431
//! （Cortex-M4）实测永不命中。两轮定位过程保留在本工具里：
//!   1. 试遍 5 个候选 FUNCTION 编码，全部不触发 → 根因不在编码；
//!   2. 加寄存器写回验证，发现 **读通路正常、写通路不落地**
//!      （DWT_COMP0 写 0x20000094 读回 0x20000000）。
//!
//! 本工具的决定性实验：同一寄存器分别用 8 位与 32 位两种访问宽度写入并读回。
//! ARMv7-M 的 CoreSight 调试寄存器**只接受字（32 位）访问**，字节/半字访问被
//! 忽略 —— 若 32 位路径落地而 8 位不落地，根因即确认。
//!
//! 纯诊断：只写调试寄存器，不烧录、不改写目标内存，结束后恢复运行。
//!
//! 用法: watchpoint_diag [addr]     默认 0x20000094（固件 g4_tool_test 的 sin_5hz）

use debug_core::{probers_backend::ProbeRsBackend, DebugBackend};
use probe_rs::MemoryInterface;
use std::time::{Duration, Instant};

const CPUID: u64 = 0xE000_ED00;
const DEMCR: u64 = 0xE000_EDFC;
const DWT_CTRL: u64 = 0xE000_1000;
const DWT_COMP0: u64 = 0xE000_1020;
const DWT_MASK0: u64 = 0xE000_1024;
const DWT_FUNCTION0: u64 = 0xE000_1028;
const DFSR: u64 = 0xE000_ED30;
const DHCSR: u64 = 0xE000_EDF0;
/// DHCSR 写入需在高 16 位带密钥 0xA05F；C_DEBUGEN=bit0，C_HALT=bit1
const DHCSR_KEY_RUN: u32 = 0xA05F_0001;
/// g4_tool_test 的 uwTick（每毫秒 +1，用作固件存活探针）
const UWTICK: u64 = 0x2000_008C;
/// DWT_FUNCTION.DATAVSIZE = 0b10（字，4 字节），位 11:10
const DATAVSIZE_WORD: u32 = 0b10 << 10;

fn wait_ms(ms: u64) {
    let t = Instant::now();
    while t.elapsed() < Duration::from_millis(ms) {}
}

fn main() {
    let addr: u64 = std::env::args()
        .nth(1)
        .map(|s| u64::from_str_radix(s.trim_start_matches("0x"), 16).expect("地址需为 hex"))
        .unwrap_or(0x2000_0094);

    let mut be = ProbeRsBackend::new("STM32G431CBTx".into(), 2_000_000);
    be.connect().expect("连接探针失败");
    println!("已连接 probe-rs / STM32G431CBTx @2MHz\n");

    let mut core = be.session_mut().expect("无会话").core(0).expect("取核心失败");

    // ---- 1. 读通路基线 ----
    let cpuid = core.read_word_32(CPUID).expect("读 CPUID 失败");
    let ctrl = core.read_word_32(DWT_CTRL).expect("读 DWT_CTRL 失败");
    println!("CPUID    = 0x{cpuid:08x}（Cortex-M4 期望 0x410fc241）");
    println!(
        "DWT_CTRL = 0x{ctrl:08x}  NUMCOMP={}  {}",
        (ctrl >> 28) & 0xF,
        if (ctrl >> 28) & 0xF > 0 {
            "DWT 比较器存在"
        } else {
            "无比较器 ✘"
        }
    );
    println!("DFSR     = 0x{:08x}", core.read_word_32(DFSR).unwrap());
    println!("DEMCR    = 0x{:08x}", core.read_word_32(DEMCR).unwrap());
    let dhcsr = core.read_word_32(DHCSR).unwrap();
    println!(
        "DHCSR    = 0x{dhcsr:08x}  C_DEBUGEN={}  S_HALT={}",
        dhcsr & 1,
        (dhcsr >> 17) & 1
    );
    if (dhcsr >> 17) & 1 == 1 {
        core.write_word_32(DHCSR, DHCSR_KEY_RUN).expect("resume 失败");
        wait_ms(200);
        println!("目标原本停住 → 已 resume");
    }

    // ---- 2. 固件存活 ----
    let t0 = core.read_word_32(UWTICK).unwrap();
    wait_ms(300);
    let t1 = core.read_word_32(UWTICK).unwrap();
    println!(
        "\nuwTick   {t0} -> {t1}（Δ{}）  {}",
        t1.wrapping_sub(t0),
        if t1 != t0 { "固件在运行 ✔" } else { "固件未推进 ✘" }
    );
    println!("[{addr:#010x}] = 0x{:08x}", core.read_word_32(addr).unwrap());

    // ---- 3. 决定性实验：8 位 vs 32 位写 ----
    println!("\n── 访问宽度对比（写 DWT_COMP0 = 0xDEADBEEF）──");
    core.write_8(DWT_COMP0, &0xDEAD_BEEFu32.to_le_bytes())
        .expect("write_8 失败");
    let via8 = core.read_word_32(DWT_COMP0).unwrap();
    println!("  write_8（引擎现状）→ read = 0x{via8:08x}");

    core.write_word_32(DWT_COMP0, 0xDEAD_BEEF)
        .expect("write_word_32 失败");
    let via32 = core.read_word_32(DWT_COMP0).unwrap();
    println!("  write_word_32       → read = 0x{via32:08x}");

    let width_bug = via8 != 0xDEAD_BEEF && via32 == 0xDEAD_BEEF;
    println!(
        "  → {}",
        if width_bug {
            "确认：8 位访问对调试寄存器无效，必须用 32 位 ✔ 根因锁定"
        } else if via32 != 0xDEAD_BEEF {
            "32 位也写不进 → 问题在别处（AP/PPB 通路）"
        } else {
            "8 位也能写进 → 宽度不是根因"
        }
    );
    if !width_bug {
        core.write_word_32(DWT_COMP0, 0).ok();
        return;
    }

    // ---- 4. 用 32 位访问逐编码找有效值 ----
    let demcr = core.read_word_32(DEMCR).unwrap();
    core.write_word_32(DEMCR, demcr | (1 << 24)).unwrap();
    println!(
        "\n{:<12} {:<12} {:<10} {:<12} {:<8}",
        "FUNCTION", "读回", "MATCHED", "DFSR", "S_HALT"
    );
    let mut valid = Vec::new();
    for f in [0b0011u32, 0b0100, 0b0101, 0b0110, 0b0111] {
        core.write_word_32(DFSR, 0x0F).unwrap();
        core.write_word_32(DWT_COMP0, addr as u32).unwrap();
        core.write_word_32(DWT_MASK0, 2).unwrap(); // 匹配 4 字节
        core.write_word_32(DWT_FUNCTION0, DATAVSIZE_WORD | f).unwrap();

        wait_ms(1200);

        let rb = core.read_word_32(DWT_FUNCTION0).unwrap();
        let dfsr = core.read_word_32(DFSR).unwrap();
        let halted = (core.read_word_32(DHCSR).unwrap() >> 17) & 1 == 1;
        let matched = (rb >> 24) & 1 == 1;
        println!(
            "{:<12} 0x{rb:08x}   {:<10} 0x{dfsr:08x}   {}",
            format!("0b{f:04b}"),
            u8::from(matched),
            u8::from(halted)
        );
        if matched || dfsr & (1 << 2) != 0 || halted {
            valid.push(f);
        }

        core.write_word_32(DWT_FUNCTION0, 0).unwrap();
        if halted {
            core.write_word_32(DHCSR, DHCSR_KEY_RUN).unwrap();
            wait_ms(150);
        }
    }

    // ---- 5. 清理 ----
    core.write_word_32(DWT_FUNCTION0, 0).ok();
    core.write_word_32(DFSR, 0x0F).ok();
    if (core.read_word_32(DHCSR).unwrap() >> 17) & 1 == 1 {
        core.write_word_32(DHCSR, DHCSR_KEY_RUN).ok();
    }
    drop(core);

    println!("\n── 结论 ──");
    if valid.is_empty() {
        println!("32 位访问落地，但 5 个编码仍未触发 → 需继续查 DWT 使能条件");
    } else {
        println!("有效编码 {:?}（0b{:04b} 为首选）", valid, valid[0]);
    }
    be.disconnect();
}
