//! 读越界行为核查（实机）。
//!
//! 背景：`ProbeRsBackend::read_bytes` 只在"地址落在某个已映射区域内"时按区域上界
//! 截断；地址**完全不在任何区域内**时走 `unwrap_or(len)` 原样下发。而 probe-rs 对
//! STM32G431 的内存映射（SRAM1 16KB + SRAM2 4KB + CCM 别名）**比实物小** ——
//! 实物 G431CBU6 的 SRAM 一直到 0x2000_8000。于是：
//!
//!   调用栈回溯沿栈向上读 sp+256 → 越过 0x20008000 → 越界读直达 DAP
//!   → "An ARM specific error occurred." → 会话被判连接丢失 → 无限重连
//!
//! 本工具把这条链路的每一步都打出来，作为修复依据。
//!
//! 用法: read_bounds_check [target]    默认 STM32G431CB（实物 CBU6 的 probe-rs 名）
//! 只做读操作，不写内存、不烧录。

use debug_core::{probers_backend::ProbeRsBackend, DebugBackend};
use probe_rs::MemoryInterface;

/// 实物 STM32G431CBU6 的 SRAM 范围（32KB）
const REAL_SRAM_LO: u64 = 0x2000_0000;
const REAL_SRAM_HI: u64 = 0x2000_8000;

fn main() {
    let target = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "STM32G431CB".into());

    let mut be = ProbeRsBackend::new(target.clone(), 2_000_000);
    be.connect().expect("连接失败");
    println!("已连接 probe-rs / {target} @2MHz\n");

    // ---- 1. probe-rs 眼里的内存映射 ----
    // G431 的 RAM 是 SRAM1 / SRAM2 / CCMRAM_DCODE 三段拼起来的，要看**最大 end**。
    {
        let core = be.session_mut().expect("无会话").core(0).expect("取核心失败");
        println!("probe-rs 的 memory_regions（0x2000_xxxx 段）：");
        let mut ram_top = 0u64;
        for r in core.memory_regions() {
            let rg = r.address_range();
            if (REAL_SRAM_LO..0x2001_0000).contains(&rg.start) {
                println!("  0x{:08x}..0x{:08x}  {:?}", rg.start, rg.end, r);
                ram_top = ram_top.max(rg.end);
            }
        }
        println!("→ 映射 RAM 上界 = 0x{ram_top:08x} / 实物 SRAM 上界 = 0x{REAL_SRAM_HI:08x}");
        if ram_top < REAL_SRAM_HI {
            println!("  ⚠ 映射比实物小，钳制会按偏小的上界截断（数据可能少读）");
        } else {
            println!("  ✔ 映射与实物一致：区域内读会被正确截断到区域上界");
        }
        println!(
            "  但注意：**地址落在所有区域之外**时，修复前的 read_bytes 走 unwrap_or(len)\n  \
             原样下发 → 越界读直达 DAP → 目标回 FAULT → 访问端口进故障态。"
        );
    }

    // ---- 2. 逐档读，看越界会怎么报错 ----
    println!("\n─ 读越界行为（经 ProbeRsBackend::read_bytes，即引擎走的路径）─");
    let cases: [(&str, u64, usize); 6] = [
        ("RAM 中部 0x20001000 +4B", 0x2000_1000, 4),
        ("RAM 顶-8   0x20007ff8 +8B", 0x2000_7ff8, 8),
        ("RAM 顶-8   0x20007ff8 +512B（越界 504B）", 0x2000_7ff8, 512),
        ("RAM 顶+0xf8 0x200080f8 +256B（栈扫描 sp+256 的典型落点）", 0x2000_80f8, 256),
        ("更远      0x2000c000 +256B", 0x2000_c000, 256),
        ("PPB      0xE000edfc +4B（DEMCR，调试寄存器）", 0xE000_EDFC, 4),
    ];
    for (label, addr, len) in cases {
        match be.read_bytes(addr, len) {
            Ok(v) => println!("  {label}\n      → Ok({} 字节)", v.len()),
            Err(e) => println!("  {label}\n      → Err: {e}"),
        }
    }

    // ---- 3. 同一个越界地址用裸 core.read_8 ----
    println!("\n─ 同一越界地址的裸 probe-rs 访问 ─");
    {
        let mut core = be.session_mut().expect("无会话").core(0).expect("取核心失败");
        let mut buf = [0u8; 4];
        match core.read_8(0x2000_80f8, &mut buf) {
            Ok(()) => println!("  core.read_8(0x200080f8) → Ok({buf:?})"),
            Err(e) => println!("  core.read_8(0x200080f8) → Err: {e}"),
        }
    }

    be.disconnect();
    println!("\n完成。");
}
