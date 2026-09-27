//! Thumb 指令解码（step-over 用，对照原版 proc_worker 的 BL/BLX 识别）。

/// 判断 pc 处指令是否为函数调用（BL / BLX），返回指令长度（2 或 4）。
/// bytes 为从 pc 读取的至少 4 字节（小端）。
pub fn call_instruction_len(bytes: &[u8]) -> Option<u32> {
    if bytes.len() < 2 {
        return None;
    }
    let h1 = u16::from_le_bytes([bytes[0], bytes[1]]);

    // BLX Rm: 0100 0111 1xxx x000
    if (h1 & 0xFF87) == 0x4780 {
        return Some(2);
    }

    // 32-bit BL/BLX: 首半字 0xF000-0xF7FF
    if (h1 & 0xF800) == 0xF000 && bytes.len() >= 4 {
        let h2 = u16::from_le_bytes([bytes[2], bytes[3]]);
        // 第二半字 bits15:14=11 即为 BL/BLX（J1 位可变，BL 取 0xD/0xF 段、
        // BLX 取 0xC/0xE 段，四段并起来恰为 bits15:14=11）
        if (h2 & 0xC000) == 0xC000 {
            return Some(4);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detect_bl() {
        // BL <imm>: F0 00 F8 00 编码（小端字节序: 00 F0 00 F8）
        assert_eq!(call_instruction_len(&[0x00, 0xF0, 0x00, 0xF8]), Some(4));
    }

    #[test]
    fn detect_blx_reg() {
        // BLX r3: 0x4798（小端: 98 47）
        assert_eq!(call_instruction_len(&[0x98, 0x47]), Some(2));
    }

    #[test]
    fn non_call() {
        // MOV r0, #0: 0x2000
        assert_eq!(call_instruction_len(&[0x00, 0x20, 0x00, 0x00]), None);
    }
}
