//! ELF/DWARF 索引（移植原版 core/elf.py，pyelftools → gimli/object）。
//!
//! 提取：函数符号表（Thumb 位清除、demangle）、DWARF 行号表（双向索引）、
//! 全局/静态变量类型树（结构体成员、数组、指针、枚举，DW_OP_addr 绝对地址）。

mod types;
mod dwarf;

pub use types::*;

use object::{Object, ObjectSection, ObjectSymbol};
use std::borrow::Cow;
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum ElfError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("object: {0}")]
    Object(#[from] object::Error),
    #[error("gimli: {0}")]
    Gimli(#[from] gimli::Error),
}

pub struct ElfIndex {
    pub functions: Vec<FunctionInfo>,
    /// 顶层全局变量类型树
    pub variables: Vec<SymbolNode>,
    /// 代码段范围：[(start, end)]，供回溯与跳转判别
    pub code_ranges: Vec<(u64, u64)>,
    /// 有序 (addr, line_ref)，按地址二分
    line_rows: Vec<LineRow>,
    /// (小写路径, 行) → 地址；同键取最小地址
    file_line_index: HashMap<(String, u32), u64>,
    /// basename 备用索引
    basename_line_index: HashMap<(String, u32), u64>,
    /// 文件表
    files: Vec<String>,
    /// 展平的 (addr → 叶子节点 meta) 供裸地址反查类型
    flat_addr_index: Vec<(u64, FlatLeaf)>,
}

#[derive(Debug, Clone)]
pub(crate) struct LineRow {
    pub(crate) addr: u64,
    pub(crate) file_idx: u32,
    pub(crate) line: u32,
}

#[derive(Debug, Clone)]
struct FlatLeaf {
    type_name: String,
    size: u32,
    encoding: ValueEncoding,
}

impl ElfIndex {
    pub fn load(path: &Path) -> Result<Self, ElfError> {
        let data = std::fs::read(path)?;
        let file = object::File::parse(&*data)?;

        // ---- 函数符号（.symtab）----
        let mut functions = Vec::new();
        for sym in file.symbols() {
            if sym.kind() == object::SymbolKind::Text && sym.size() > 0 {
                let raw_name = sym.name().unwrap_or("");
                if raw_name.is_empty() || raw_name.starts_with("$") {
                    continue;
                }
                let name = demangle(raw_name);
                // 过滤 guard/vtable/typeinfo 噪音
                if name.starts_with("guard variable")
                    || name.starts_with("vtable for")
                    || name.starts_with("typeinfo")
                {
                    continue;
                }
                let start = sym.address() & !1;
                functions.push(FunctionInfo {
                    name,
                    raw_name: raw_name.to_string(),
                    start,
                    end: start + sym.size(),
                    file: None,
                    line: None,
                });
            }
        }
        functions.sort_by_key(|f| f.start);

        // ---- DWARF ----
        let endian = if file.is_little_endian() {
            gimli::RunTimeEndian::Little
        } else {
            gimli::RunTimeEndian::Big
        };
        let load_section = |id: gimli::SectionId| -> Result<Cow<'_, [u8]>, gimli::Error> {
            Ok(file
                .section_by_name(id.name())
                .and_then(|s| s.uncompressed_data().ok())
                .unwrap_or(Cow::Borrowed(&[][..])))
        };
        let dwarf_sections = gimli::DwarfSections::load(&load_section)?;
        let dwarf = dwarf_sections.borrow(|section| gimli::EndianSlice::new(section, endian));

        let mut variables = Vec::new();
        let mut line_rows = Vec::new();
        let mut files: Vec<String> = Vec::new();
        let mut file_key_cache: HashMap<String, u32> = HashMap::new();
        let mut func_decls: HashMap<u64, (String, u32)> = HashMap::new();

        dwarf::index_dwarf(
            &dwarf,
            &mut variables,
            &mut func_decls,
            &mut line_rows,
            &mut files,
            &mut file_key_cache,
        )?;


        // 补充函数声明位置
        for f in &mut functions {
            if let Some((file, line)) = func_decls.get(&f.start) {
                f.file = Some(file.clone());
                f.line = Some(*line);
            }
        }

        // 排序 & 建索引
        line_rows.sort_by_key(|r| r.addr);
        let mut file_line_index = HashMap::new();
        let mut basename_line_index = HashMap::new();
        for row in &line_rows {
            let path = files
                .get(row.file_idx as usize)
                .cloned()
                .unwrap_or_default();
            let norm = normalize_path(&path);
            let key = (norm.clone(), row.line);
            file_line_index
                .entry(key)
                .and_modify(|a: &mut u64| {
                    if row.addr < *a {
                        *a = row.addr;
                    }
                })
                .or_insert(row.addr);
            if let Some(base) = norm.rsplit('\\').next() {
                let bkey = (base.to_string(), row.line);
                basename_line_index
                    .entry(bkey)
                    .and_modify(|a: &mut u64| {
                        if row.addr < *a {
                            *a = row.addr;
                        }
                    })
                    .or_insert(row.addr);
            }
        }

        // 变量按名称排序，地址 < 0x1000 的丢弃（对照原版）
        variables.retain(|v| v.address >= 0x1000);
        variables.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

        // 展平地址索引
        let mut flat_addr_index = Vec::new();
        for var in &variables {
            flatten_leaves(var, &mut flat_addr_index);
        }
        flat_addr_index.sort_by_key(|(addr, _)| *addr);

        // 收集代码段范围（ELF Text 段 + 函数符号范围），合并重叠与邻接区间
        let mut raw_ranges = Vec::new();
        for sec in file.sections() {
            if sec.kind() == object::SectionKind::Text && sec.size() > 0 {
                let start = sec.address();
                raw_ranges.push((start, start + sec.size()));
            }
        }
        for f in &functions {
            raw_ranges.push((f.start, f.end));
        }
        raw_ranges.sort_by_key(|r| r.0);
        let mut code_ranges: Vec<(u64, u64)> = Vec::new();
        for (start, end) in raw_ranges {
            if let Some(last) = code_ranges.last_mut() {
                if start <= last.1 {
                    last.1 = last.1.max(end);
                    continue;
                }
            }
            code_ranges.push((start, end));
        }

        Ok(Self {
            functions,
            variables,
            code_ranges,
            line_rows,
            file_line_index,
            basename_line_index,
            files,
            flat_addr_index,
        })
    }

    /// 判断给定地址是否落在代码段（Text section 或已知函数符号）范围内。
    pub fn contains_code_addr(&self, addr: u64) -> bool {
        let addr = addr & !1;
        for (start, end) in &self.code_ranges {
            if addr >= *start && addr < *end {
                return true;
            }
        }
        self.function_at(addr).is_some()
    }

    /// file:line → 目标地址（找不到精确行则向下找同文件最近的后续行，容差 40 行）。
    pub fn addr_for_line(&self, file: &str, line: u32) -> Option<(u64, u32)> {
        let norm = normalize_path(file);
        if let Some(addr) = self.file_line_index.get(&(norm.clone(), line)) {
            return Some((*addr, line));
        }
        let base = norm.rsplit('\\').next().unwrap_or(&norm).to_string();
        if let Some(addr) = self.basename_line_index.get(&(base.clone(), line)) {
            return Some((*addr, line));
        }
        // 向后寻找最近可断点行（饱和加法：u32::MAX 附近的畸形行号不再 panic/回绕）
        for probe in line.saturating_add(1)..line.saturating_add(40) {
            if let Some(addr) = self.file_line_index.get(&(norm.clone(), probe)) {
                return Some((*addr, probe));
            }
            if let Some(addr) = self.basename_line_index.get(&(base.clone(), probe)) {
                return Some((*addr, probe));
            }
        }
        None
    }

    /// 地址 → (文件, 行)。容差 0x2000（对照原版 get_line_by_pc）。
    pub fn line_for_addr(&self, addr: u64) -> Option<(String, u32)> {
        let addr = addr & !1;
        let idx = self
            .line_rows
            .partition_point(|r| r.addr <= addr)
            .checked_sub(1)?;
        let row = &self.line_rows[idx];
        if addr - row.addr >= 0x2000 {
            return None;
        }
        let file = self.files.get(row.file_idx as usize)?.clone();
        Some((file, row.line))
    }

    /// PC 所在函数。
    pub fn function_at(&self, addr: u64) -> Option<&FunctionInfo> {
        let addr = addr & !1;
        let idx = self
            .functions
            .partition_point(|f| f.start <= addr)
            .checked_sub(1)?;
        let f = &self.functions[idx];
        (addr < f.end).then_some(f)
    }

    /// 当前地址之后的下一源码行地址（同函数内，供 step-over）。
    pub fn next_line_addr_after(&self, addr: u64) -> Option<u64> {
        let addr = addr & !1;
        let func = self.function_at(addr);
        let start = self.line_rows.partition_point(|r| r.addr <= addr);
        for row in &self.line_rows[start..] {
            if let Some(f) = func {
                if row.addr >= f.end {
                    return None;
                }
            }
            if row.addr > addr {
                return Some(row.addr);
            }
        }
        None
    }

    /// 裸地址反查类型（member 级别索引）。
    pub fn type_at_addr(&self, addr: u64) -> Option<(String, u32, ValueEncoding)> {
        let idx = self
            .flat_addr_index
            .partition_point(|(a, _)| *a <= addr)
            .checked_sub(1)?;
        let (leaf_addr, leaf) = &self.flat_addr_index[idx];
        if *leaf_addr == addr {
            Some((leaf.type_name.clone(), leaf.size, leaf.encoding))
        } else {
            None
        }
    }

    /// 按名称查找顶层变量或成员链（a.b.c 或 ptr->member 或 arr[0]，主机解析）。
    pub fn resolve_member_chain(&self, expr: &str) -> Option<SymbolNode> {
        let normalized = expr.replace("->", ".").replace('[', ".").replace(']', "");
        let parts: Vec<&str> = normalized.split('.').filter(|s| !s.is_empty()).collect();
        if parts.is_empty() {
            return None;
        }
        // 最长前缀匹配根符号（原版行为：符号名本身可能含 :: 等）
        let mut root: Option<&SymbolNode> = None;
        let mut consumed = 0;
        for take in (1..=parts.len()).rev() {
            let candidate = parts[..take].join(".");
            if let Some(v) = self.variables.iter().find(|v| v.name == candidate) {
                root = Some(v);
                consumed = take;
                break;
            }
        }
        let mut node = root?;
        for part in &parts[consumed..] {
            node = find_child_member(node, part)?;
        }
        Some(node.clone())
    }
}

fn find_child_member<'a>(node: &'a SymbolNode, name: &str) -> Option<&'a SymbolNode> {
    let bracketed = if !name.starts_with('[') && name.chars().all(|c| c.is_ascii_digit()) {
        Some(format!("[{name}]"))
    } else {
        None
    };
    let unbracketed = if name.starts_with('[') && name.ends_with(']') {
        Some(&name[1..name.len() - 1])
    } else {
        None
    };

    if let Some(m) = node.members.iter().find(|m| {
        m.name == name
            || bracketed.as_deref() == Some(m.name.as_str())
            || unbracketed == Some(m.name.as_str())
    }) {
        return Some(m);
    }
    // 透明穿透匿名 union / struct
    for m in &node.members {
        if m.name.is_empty() || m.name == "<anon>" || m.name == "anon" {
            if let Some(found) = find_child_member(m, name) {
                return Some(found);
            }
        }
    }
    None
}

fn flatten_leaves(node: &SymbolNode, out: &mut Vec<(u64, FlatLeaf)>) {
    if node.is_pointer || node.members.is_empty() {

        if node.address > 0 && node.size > 0 {
            out.push((
                node.address,
                FlatLeaf {
                    type_name: node.type_name.clone(),
                    size: node.size,
                    encoding: node.encoding,
                },
            ));
        }
    } else {
        for m in &node.members {
            flatten_leaves(m, out);
        }
    }
}

/// 词法级路径解析：统一反斜杠并消解 `..`/`.`（保留大小写）。
/// CubeMX 等工程的 DWARF 路径常含 `../`，不解析会导致行表索引 miss、
/// 打开文件时同一文件出现两种路径标识。
pub fn lexical_resolve(p: &str) -> String {
    let unified = p.replace('/', "\\");
    let mut parts: Vec<&str> = Vec::new();
    for comp in unified.split('\\') {
        match comp {
            "" | "." => {
                if parts.is_empty() && comp.is_empty() {
                    parts.push(comp);
                }
            }
            ".." => {
                if matches!(parts.last(), Some(&last) if last != ".." && !last.ends_with(':')) {
                    parts.pop();
                } else {
                    parts.push("..");
                }
            }
            other => parts.push(other),
        }
    }
    parts.join("\\")
}

/// 路径规范化（索引键）：词法解析 + 小写化。
pub fn normalize_path(p: &str) -> String {
    lexical_resolve(p).to_lowercase()
}

#[cfg(test)]
mod path_tests {
    use super::{lexical_resolve, normalize_path};

    #[test]
    fn resolves_dotdot() {
        assert_eq!(
            normalize_path("E:/proj/cmake/stm32cubemx/../../Drivers/x.h"),
            r"e:\proj\drivers\x.h"
        );
        assert_eq!(
            normalize_path(r"D:\A B\src\.\main.c"),
            r"d:\a b\src\main.c"
        );
        assert_eq!(normalize_path("Core/Src/main.c"), r"core\src\main.c");
    }

    #[test]
    fn lexical_keeps_case() {
        assert_eq!(
            lexical_resolve("E:/Proj/cmake/../Core/Src/Main.c"),
            r"E:\Proj\Core\Src\Main.c"
        );
    }

    #[test]
    fn test_contains_code_addr() {
        let index = super::ElfIndex {
            functions: vec![],
            variables: vec![],
            code_ranges: vec![(0x0800_0000, 0x0804_0000), (0x2000_0000, 0x2000_1000)],
            line_rows: vec![],
            file_line_index: std::collections::HashMap::new(),
            basename_line_index: std::collections::HashMap::new(),
            files: vec![],
            flat_addr_index: vec![],
        };

        // Flash 代码段 (包含 Thumb LSB 清除)
        assert!(index.contains_code_addr(0x0800_0000));
        assert!(index.contains_code_addr(0x0800_0001));
        assert!(index.contains_code_addr(0x0803_FFFE));
        assert!(!index.contains_code_addr(0x0804_0000));

        // RAM 执行代码段
        assert!(index.contains_code_addr(0x2000_0001));
        assert!(index.contains_code_addr(0x2000_0500));
        assert!(!index.contains_code_addr(0x2000_1000));

        // 外部范围
        assert!(!index.contains_code_addr(0x0000_0000));
        assert!(!index.contains_code_addr(0x1FFF_0000));
    }

    #[test]
    fn test_resolve_member_chain() {
        use crate::types::{SymbolNode, ValueEncoding};

        let make_leaf = |name: &str, type_name: &str, addr: u64, size: u32| SymbolNode {
            name: name.to_string(),
            type_name: type_name.to_string(),
            address: addr,
            size,
            encoding: ValueEncoding::Unsigned,
            is_pointer: false,
            pointee_size: 0,
            pointee_type: String::new(),
            pointee_encoding: ValueEncoding::Unsigned,
            enum_values: vec![],
            members: vec![],
            decl_file: None,
            decl_line: None,
        };

        let var = SymbolNode {
            name: "g_motor".to_string(),
            type_name: "MotorState".to_string(),
            address: 0x2000_0000,
            size: 32,
            encoding: ValueEncoding::Composite,
            is_pointer: false,
            pointee_size: 0,
            pointee_type: String::new(),
            pointee_encoding: ValueEncoding::Unsigned,
            enum_values: vec![],
            members: vec![
                make_leaf("run_ms", "uint32_t", 0x2000_0000, 4),
                SymbolNode {
                    name: "position".to_string(),
                    type_name: "Point".to_string(),
                    address: 0x2000_0004,
                    size: 8,
                    encoding: ValueEncoding::Composite,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: vec![],
                    members: vec![
                        make_leaf("x", "float", 0x2000_0004, 4),
                        make_leaf("y", "float", 0x2000_0008, 4),
                    ],
                    decl_file: None,
                    decl_line: None,
                },
                SymbolNode {
                    name: "raw".to_string(),
                    type_name: "int16_t[4]".to_string(),
                    address: 0x2000_000c,
                    size: 8,
                    encoding: ValueEncoding::Composite,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: vec![],
                    members: vec![
                        make_leaf("[0]", "int16_t", 0x2000_000c, 2),
                        make_leaf("[1]", "int16_t", 0x2000_000e, 2),
                    ],
                    decl_file: None,
                    decl_line: None,
                },
                SymbolNode {
                    name: "<anon>".to_string(),
                    type_name: "union".to_string(),
                    address: 0x2000_0014,
                    size: 4,
                    encoding: ValueEncoding::Composite,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: vec![],
                    members: vec![
                        make_leaf("flag", "uint32_t", 0x2000_0014, 4),
                    ],
                    decl_file: None,
                    decl_line: None,
                },
            ],
            decl_file: None,
            decl_line: None,
        };

        let ptr_var = SymbolNode {
            name: "wl_chassis_ptr".to_string(),
            type_name: "wl_chassis_t *".to_string(),
            address: 0x2000_1000,
            size: 4,
            encoding: ValueEncoding::Pointer,
            is_pointer: true,
            pointee_size: 100,
            pointee_type: "wl_chassis_t".to_string(),
            pointee_encoding: ValueEncoding::Composite,
            enum_values: vec![],
            members: vec![
                make_leaf("_head", "uint8_t", 0x0000_0010, 1),
            ],
            decl_file: None,
            decl_line: None,
        };

        let index = super::ElfIndex {
            functions: vec![],
            variables: vec![var, ptr_var],
            code_ranges: vec![],
            line_rows: vec![],
            file_line_index: std::collections::HashMap::new(),
            basename_line_index: std::collections::HashMap::new(),
            files: vec![],
            flat_addr_index: vec![],
        };

        // 1. 基本字段解析
        let run_ms = index.resolve_member_chain("g_motor.run_ms").expect("find run_ms");
        assert_eq!(run_ms.address, 0x2000_0000);
        assert_eq!(run_ms.type_name, "uint32_t");

        // 2. 嵌套结构体字段解析
        let pos_x = index.resolve_member_chain("g_motor.position.x").expect("find position.x");
        assert_eq!(pos_x.address, 0x2000_0004);
        assert_eq!(pos_x.type_name, "float");

        // 3. 数组索引解析：raw[0] 与 raw[1]
        let raw_0 = index.resolve_member_chain("g_motor.raw[0]").expect("find raw[0]");
        assert_eq!(raw_0.address, 0x2000_000c);
        assert_eq!(raw_0.type_name, "int16_t");

        let raw_1 = index.resolve_member_chain("g_motor.raw[1]").expect("find raw[1]");
        assert_eq!(raw_1.address, 0x2000_000e);

        // 4. 匿名 union 穿透
        let flag = index.resolve_member_chain("g_motor.flag").expect("find flag through anon");
        assert_eq!(flag.address, 0x2000_0014);

        // 5. 指针解引用与箭头操作符
        let head_arrow = index.resolve_member_chain("wl_chassis_ptr->_head").expect("find ptr->_head");
        assert_eq!(head_arrow.address, 0x0000_0010);
        let head_dot = index.resolve_member_chain("wl_chassis_ptr._head").expect("find ptr._head");
        assert_eq!(head_dot.address, 0x0000_0010);
    }

    #[test]
    fn test_g4_tool_test_array_offsets() {
        let elf_path = std::env::var("TEST_ELF_PATH")
            .unwrap_or_else(|_| r"E:\Software\Develop\Embeded\Pack\g4_tool_test\cmake-build-debug-stm32\g4_tool_test.elf".to_string());
        if !std::path::Path::new(&elf_path).exists() {
            return;
        }
        let index = crate::ElfIndex::load(std::path::Path::new(&elf_path)).expect("parse elf");
        let d0 = index.resolve_member_chain("g_chassis_ptr._ctx.data[0].vx");
        let d1 = index.resolve_member_chain("g_chassis_ptr._ctx.data[1].vx");
        if let (Some(n0), Some(n1)) = (d0, d1) {
            assert_eq!(n0.address, 104);
            assert_eq!(n1.address, 124);
            assert_ne!(n0.address, n1.address, "Array elements vx must have different offsets/addresses!");
        } else {
            panic!("Could not resolve g_chassis_ptr._ctx.data[0].vx or [1].vx");
        }

        // 全局结构体数组成员测试（非指针变量直接使用绝对物理地址）
        let a0 = index.resolve_member_chain("g_ctx_alias.data[0].vx");
        let a1 = index.resolve_member_chain("g_ctx_alias.data[1].vx");
        if let (Some(n0), Some(n1)) = (a0, a1) {
            assert_ne!(n0.address, n1.address, "Global array elements vx must have different physical addresses!");
            assert_eq!(n1.address - n0.address, 20); // 每个 wl_chassis_data_ctx_t 大小为 20 字节
        } else {
            panic!("Could not resolve g_ctx_alias.data[0].vx or [1].vx");
        }
    }
}


pub fn demangle(name: &str) -> String {
    if name.starts_with("_Z") {
        if let Ok(sym) = cpp_demangle::Symbol::new(name) {
            let s = sym.to_string();
            // GCC 双 ABI 后缀：剥掉 "[abi:cxx11]"，让 Watch 能按源码名（g_str）匹配
            let trimmed = match s.find("[abi:") {
                Some(i) => s[..i].trim_end().to_string(),
                None => s,
            };
            return trimmed;
        }
    }
    name.to_string()
}
