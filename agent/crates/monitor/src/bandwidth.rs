//! 内存读取块合并与带宽模型（移植原版 workers/proc_worker.py 与 core/bandwidth.py）。
//!
//! 经验常数：有效带宽 750 KB/s，每个读块固定开销折合 375 字节。

pub const EFFECTIVE_BANDWIDTH_BPS: u64 = 750_000;
pub const READ_OVERHEAD_BYTES: u64 = 375;
pub const MIN_MERGE_GAP: u64 = 16;
pub const MAX_MERGE_GAP: u64 = 512;
pub const MAX_BLOCK_BYTES: u64 = 4096;
/// UI 可行性预检用的固定合并间隙
pub const UI_MERGE_GAP: u64 = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemBlock {
    pub start: u64,
    pub size: u64,
}

/// 运行时合并：按采样频率动态调整 gap 与块上限（对照 _build_memory_blocks）。
pub fn merge_blocks(targets: &[(u64, u64)], freq_hz: f64) -> Vec<MemBlock> {
    if targets.is_empty() {
        return Vec::new();
    }
    let bytes_per_sample = if freq_hz > 0.0 {
        (EFFECTIVE_BANDWIDTH_BPS as f64 / freq_hz) as u64
    } else {
        EFFECTIVE_BANDWIDTH_BPS
    };
    let gap_limit = (READ_OVERHEAD_BYTES.min((bytes_per_sample as f64 * 0.10) as u64))
        .clamp(MIN_MERGE_GAP, MAX_MERGE_GAP);
    let max_block = ((bytes_per_sample as f64 * 0.50) as u64).clamp(64, MAX_BLOCK_BYTES);

    let mut sorted: Vec<(u64, u64)> = targets
        .iter()
        .filter(|(_, size)| *size > 0)
        .copied()
        .collect();
    sorted.sort_by_key(|(addr, _)| *addr);

    let mut blocks: Vec<MemBlock> = Vec::new();
    for (addr, size) in sorted {
        // 兜底过滤：协议入口已校验 32 位范围，这里再防一道溢出/异常项，
        // 保证下面的地址算术（addr + size + 3）不会回绕
        if size == 0 || addr.checked_add(size).is_none_or(|end| end > 0x1_0000_0000) {
            continue;
        }
        let aligned_addr = addr & !3;
        let aligned_end = (addr + size + 3) & !3;
        let aligned_size = aligned_end - aligned_addr;
        match blocks.last_mut() {
            // 入参已排序 → 当前 addr 必然 >= 上一块的 start。addr 落在上一块内
            // （gap=0）或与上一块足够近时并入，大小取并集。
            // 对齐到 4 字节边界，保障下层探针采用 32 位原子字访问，消除非原子字节撕裂。
            Some(last) => {
                let last_end = last.start + last.size;
                let gap = aligned_addr.saturating_sub(last_end);
                let merged_size = aligned_end.max(last_end) - last.start;
                if gap <= gap_limit && merged_size <= max_block {
                    last.size = merged_size;
                    continue;
                }
                blocks.push(MemBlock { start: aligned_addr, size: aligned_size });
            }
            None => blocks.push(MemBlock { start: aligned_addr, size: aligned_size }),
        }
    }
    blocks
}

/// UI 侧可行性预检（对照 core/bandwidth.py：gap 固定 256，虚拟字节含块开销）。
/// 返回 (是否可行, 预计带宽占用 bytes/s)。
pub fn check_feasibility(targets: &[(u64, u64)], freq_hz: f64) -> (bool, u64) {
    if targets.is_empty() || freq_hz <= 0.0 {
        return (true, 0);
    }
    let mut sorted: Vec<(u64, u64)> = targets.to_vec();
    sorted.sort_by_key(|(addr, _)| *addr);

    let mut blocks: Vec<MemBlock> = Vec::new();
    for (addr, size) in sorted {
        // 兜底过滤：协议入口已校验 32 位范围，这里再防一道溢出/异常项
        if size == 0 || addr.checked_add(size).is_none_or(|end| end > 0x1_0000_0000) {
            continue;
        }
        match blocks.last_mut() {
            Some(last) => {
                let last_end = last.start + last.size;
                if addr.saturating_sub(last_end) <= UI_MERGE_GAP {
                    let end = (addr + size).max(last_end);
                    last.size = end - last.start;
                } else {
                    blocks.push(MemBlock { start: addr, size });
                }
            }
            None => blocks.push(MemBlock { start: addr, size }),
        }
    }
    let span: u64 = blocks.iter().map(|b| b.size).sum();
    let virtual_bytes = span + blocks.len() as u64 * READ_OVERHEAD_BYTES;
    let usage = (virtual_bytes as f64 * freq_hz) as u64;
    (usage <= EFFECTIVE_BANDWIDTH_BPS, usage)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_adjacent() {
        let blocks = merge_blocks(&[(0x2000_0000, 4), (0x2000_0004, 4)], 5.0);
        assert_eq!(blocks, vec![MemBlock { start: 0x2000_0000, size: 8 }]);
    }

    #[test]
    fn merge_with_small_gap() {
        let blocks = merge_blocks(&[(0x2000_0000, 4), (0x2000_0010, 4)], 5.0);
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].size, 0x14);
    }

    #[test]
    fn no_merge_large_gap() {
        let blocks = merge_blocks(&[(0x2000_0000, 4), (0x2000_f000, 4)], 5.0);
        assert_eq!(blocks.len(), 2);
    }

    #[test]
    fn overlapping_targets() {
        let blocks = merge_blocks(&[(0x2000_0000, 8), (0x2000_0004, 8)], 5.0);
        assert_eq!(blocks, vec![MemBlock { start: 0x2000_0000, size: 12 }]);
    }

    #[test]
    fn feasibility_low_freq_ok() {
        let (ok, _) = check_feasibility(&[(0x2000_0000, 4)], 50.0);
        assert!(ok);
    }

    #[test]
    fn feasibility_high_freq_fail() {
        // 单块虚拟 379 字节 * 5000Hz ≈ 1.9MB/s > 750KB/s
        let (ok, usage) = check_feasibility(&[(0x2000_0000, 4)], 5000.0);
        assert!(!ok);
        assert!(usage > EFFECTIVE_BANDWIDTH_BPS);
    }
}
