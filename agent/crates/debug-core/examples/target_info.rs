//! 目标型号与内存映射诊断。
//!
//! 存在的意义：**probe-rs 的型号名会影响内存映射**，而 `ProbeRsBackend::read_bytes`
//! 的越界读钳制完全依赖 `memory_regions()`。名字选错（例如填 `STM32G431CB` 这种
//! 非变体名）就可能拿到与实物不符的 RAM 边界，进而让"贴 RAM 顶的栈读"越界，
//! 在探针上触发总线错误（"An ARM specific error occurred."）。
//!
//! 用法:
//!   target_info                列出所有含 "STM32G431" 的型号名
//!   target_info <needle>       按关键字列出型号名
//!   target_info <型号名> --map  解析该名字并打印内存映射（不可解析会明确报错）

use probe_rs::config::Registry;

/// 实物：STM32G431CBU6 → RAM 应为 0x2000_0000..0x2000_8000（32KB）
const EXPECTED_RAM_TOP: u64 = 0x2000_8000;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let registry = Registry::from_builtin_families();

    // 形态 1：打印某个名字解析后的内存映射
    if let Some(pos) = args.iter().position(|a| a == "--map") {
        let name = args
            .iter()
            .enumerate()
            .find(|(i, a)| *i != pos && !a.starts_with("--"))
            .map(|(_, a)| a.clone())
            .expect("用法: target_info <型号名> --map");
        match registry.get_target_by_name(&name) {
            Ok(t) => {
                println!("型号名 {name:?} 解析成功（实际 name = {:?}）", t.name);
                println!("核心数 = {}", t.cores.len());
                println!("内存映射：");
                for r in &t.memory_map {
                    println!("  {r:?}");
                }
                // RAM 顶 = 0x2000_xxxx 窗口内所有区域的**最大** end。
                // 不能只看 start == 0x20000000 的那一段：G431 的映射是
                // SRAM1 / SRAM2 / CCMRAM_DCODE 三段拼起来的，只看第一段会误判。
                let mut ram_count = 0usize;
                let mut ram_top = 0u64;
                for r in &t.memory_map {
                    let rg = r.address_range();
                    if (0x2000_0000..0x2001_0000).contains(&rg.start) {
                        ram_count += 1;
                        ram_top = ram_top.max(rg.end);
                    }
                }
                match ram_count {
                    0 => println!(
                        "→ 注意：没有 0x20000000 起始的 RAM 区 ✘（越界读钳制完全失效）"
                    ),
                    _ if ram_top == EXPECTED_RAM_TOP => {
                        println!("→ RAM 顶 = {ram_top:#x}（与 G431CBU6 实物一致）✔");
                    }
                    _ => println!(
                        "→ 注意：RAM 顶 = {ram_top:#x}，与实物预期 {EXPECTED_RAM_TOP:#x} **不符** ✘ \
                         （越界读钳制会失效，贴 RAM 顶的栈读将越界）"
                    ),
                }
            }
            Err(e) => println!("型号名 {name:?} **无法解析**：{e}"),
        }
        return;
    }

    // 形态 2：列型号名
    let needle = args
        .first()
        .cloned()
        .unwrap_or_else(|| "STM32G431".into())
        .to_lowercase();
    let mut hits = Vec::new();
    for family in registry.families() {
        for v in &family.variants {
            if v.name.to_lowercase().contains(&needle) {
                hits.push(format!("{}  （family {}）", v.name, family.name));
            }
        }
    }
    hits.sort();
    println!("含 {needle:?} 的型号名共 {} 个：", hits.len());
    for h in hits {
        println!("  {h}");
    }
}
