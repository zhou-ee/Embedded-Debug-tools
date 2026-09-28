//! DWARF 全局遍历：行号表、全局变量类型树、函数声明位置。
//! 支持跨 CU 类型解析、前向声明关联、规范追溯 (DW_AT_specification / DW_AT_abstract_origin)。

use crate::types::{SymbolNode, ValueEncoding};
use crate::{demangle, LineRow};
use gimli::{
    AttributeValue, DebuggingInformationEntry, Dwarf, EndianSlice, Reader, RunTimeEndian, Unit,
    UnitOffset, UnitSectionOffset,
};

use std::collections::HashMap;

type R<'a> = EndianSlice<'a, RunTimeEndian>;
type Die<'a, 'u> = DebuggingInformationEntry<'a, 'u, R<'a>>;

const MAX_DEPTH: usize = 64;
const MAX_ARRAY_EXPAND: u64 = 256;
const MAX_MEMBERS: usize = 512;

/// 跨 CU 的全局 DIE 引用
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct DieRef {
    pub unit_idx: usize,
    pub offset: UnitOffset,
}

fn type_def_score<'a>(units: &[Unit<R<'a>>], die_ref: DieRef) -> (u8, u32) {
    let unit = &units[die_ref.unit_idx];
    let Ok(entry) = unit.entry(die_ref.offset) else {
        return (0, 0);
    };
    let tag = entry.tag();
    let is_decl = matches!(
        entry.attr_value(gimli::DW_AT_declaration).ok().flatten(),
        Some(AttributeValue::Flag(true))
    );
    let byte_size = entry
        .attr_value(gimli::DW_AT_byte_size)
        .ok()
        .flatten()
        .and_then(|a| a.udata_value())
        .unwrap_or(0) as u32;

    if is_decl {
        return (0, 0);
    }
    match tag {
        gimli::DW_TAG_structure_type | gimli::DW_TAG_class_type | gimli::DW_TAG_union_type => {
            if byte_size > 0 || entry.has_children() {
                (3, byte_size)
            } else {
                (1, 0)
            }
        }
        gimli::DW_TAG_enumeration_type => (2, byte_size),
        gimli::DW_TAG_typedef => (1, byte_size),
        _ => (0, 0),
    }
}

fn insert_global_type<'a>(
    global_type_defs: &mut HashMap<String, DieRef>,
    units: &[Unit<R<'a>>],
    key: String,
    new_ref: DieRef,
) {
    if let Some(&existing_ref) = global_type_defs.get(&key) {
        let (existing_prio, existing_size) = type_def_score(units, existing_ref);
        let (new_prio, new_size) = type_def_score(units, new_ref);
        if new_prio > existing_prio || (new_prio == existing_prio && new_size > existing_size) {
            global_type_defs.insert(key, new_ref);
        }
    } else {
        global_type_defs.insert(key, new_ref);
    }
}

pub(crate) fn index_dwarf<'a>(
    dwarf: &Dwarf<R<'a>>,
    variables: &mut Vec<SymbolNode>,
    func_decls: &mut HashMap<u64, (String, u32)>,
    line_rows: &mut Vec<LineRow>,
    files: &mut Vec<String>,
    file_key_cache: &mut HashMap<String, u32>,
) -> Result<(), gimli::Error> {
    // 1. 加载所有编译单元及在 .debug_info 中的全局偏移区间
    let mut units = Vec::new();
    let mut unit_ranges = Vec::new();
    let mut iter = dwarf.units();
    while let Some(header) = iter.next()? {
        let unit = dwarf.unit(header)?;
        let start = match unit.header.offset() {
            UnitSectionOffset::DebugInfoOffset(o) => o.0,
            UnitSectionOffset::DebugTypesOffset(o) => o.0,
        };
        let end = start + unit.header.length_including_self();
        unit_ranges.push((start, end));
        units.push(unit);
    }

    // 2. Pass 1: 行号表、每 CU 本地文件映射、全局类型图纸预扫描、函数声明位置
    let mut cu_local_files: Vec<HashMap<u64, u32>> = Vec::with_capacity(units.len());
    let mut die_qualified_names: HashMap<DieRef, String> = HashMap::new();
    let mut global_type_defs: HashMap<String, DieRef> = HashMap::new();

    for (unit_idx, unit) in units.iter().enumerate() {
        // 行号表及文件映射
        let mut local_files: HashMap<u64, u32> = HashMap::new();
        if let Some(program) = unit.line_program.clone() {
            {
                let header = program.header();
                for (i, _file) in header.file_names().iter().enumerate() {
                    let idx = i as u64 + if header.version() >= 5 { 0 } else { 1 };
                    let path = resolve_file_path(dwarf, unit, &program, idx);
                    if let Some(path) = path {
                        let path = crate::lexical_resolve(&path);
                        let global = *file_key_cache.entry(path.clone()).or_insert_with(|| {
                            files.push(path.clone());
                            (files.len() - 1) as u32
                        });
                        local_files.insert(idx, global);
                    }
                }
                // V4-：file_index 0 是"编译单元主源文件"约定（文件表从 1 开始），
                // 不映射则引用它的行被静默丢弃，line_for_addr 对这些 PC 返回 None
                if header.version() < 5 {
                    if let Some(cu_name) = unit.name {
                        // unit.name 是 DW_AT_name 的原始 Reader（可能相对 comp_dir）
                        let name = cu_name.to_string_lossy().into_owned();
                        if !name.is_empty() {
                            let path = if is_absolute(&name) {
                                name
                            } else {
                                let mut parts: Vec<String> = unit
                                    .comp_dir
                                    .as_ref()
                                    .map(|d| d.to_string_lossy().into_owned())
                                    .into_iter()
                                    .collect();
                                parts.push(name);
                                parts.join("/")
                            };
                            let path = crate::lexical_resolve(&path);
                            let global = *file_key_cache.entry(path.clone()).or_insert_with(|| {
                                files.push(path.clone());
                                (files.len() - 1) as u32
                            });
                            local_files.insert(0, global);
                        }
                    }
                }
            }
            let mut rows = program.rows();
            while let Some((_header, row)) = rows.next_row()? {
                if row.end_sequence() || !row.is_stmt() {
                    continue;
                }
                let Some(line) = row.line() else { continue };
                let file_idx = row.file_index();
                if let Some(global_idx) = local_files.get(&file_idx) {
                    line_rows.push(LineRow {
                        addr: row.address() & !1,
                        file_idx: *global_idx,
                        line: line.get() as u32,
                    });
                }
            }
        }
        cu_local_files.push(local_files);

        // DIE 预扫描（维护作用域栈，记录类型限定名及完整定义）
        let mut entries = unit.entries();
        let mut depth: isize = 0;
        let mut scope_stack: Vec<(isize, String)> = Vec::new();

        while let Some((delta, entry)) = entries.next_dfs()? {
            depth += delta;
            while let Some(&(d, _)) = scope_stack.last() {
                if depth <= d {
                    scope_stack.pop();
                } else {
                    break;
                }
            }

            let tag = entry.tag();
            let die_ref = DieRef {
                unit_idx,
                offset: entry.offset(),
            };

            match tag {
                gimli::DW_TAG_namespace => {
                    if entry.has_children() {
                        if let Some(name) = die_name(dwarf, unit, entry) {
                            if !name.is_empty() {
                                scope_stack.push((depth, name));
                            }
                        }
                    }
                }
                gimli::DW_TAG_structure_type
                | gimli::DW_TAG_class_type
                | gimli::DW_TAG_union_type
                | gimli::DW_TAG_enumeration_type
                | gimli::DW_TAG_typedef => {
                    let is_decl = matches!(
                        entry.attr_value(gimli::DW_AT_declaration).ok().flatten(),
                        Some(AttributeValue::Flag(true))
                    );
                    let raw_name = die_name(dwarf, unit, entry).or_else(|| {
                        die_name_resolved(dwarf, &units, &unit_ranges, die_ref)
                    });
                    let mut qualified_name = None;
                    if let Some(ref name) = raw_name {
                        if !name.is_empty() {
                            let prefix = scope_stack
                                .iter()
                                .map(|(_, s)| s.as_str())
                                .collect::<Vec<_>>()
                                .join("::");
                            let qname = if prefix.is_empty() {
                                name.clone()
                            } else {
                                format!("{prefix}::{name}")
                            };
                            qualified_name = Some(qname);
                        }
                    }

                    if let Some(ref qname) = qualified_name {
                        die_qualified_names.insert(die_ref, qname.clone());
                    }

                    if !is_decl {
                        // 完整图纸
                        if let Some(ref qname) = qualified_name {
                            insert_global_type(&mut global_type_defs, &units, qname.clone(), die_ref);
                        }
                        if let Some(ref sname) = raw_name {
                            insert_global_type(&mut global_type_defs, &units, sname.clone(), die_ref);
                        }
                        if let Some(attr) = entry.attr_value(gimli::DW_AT_linkage_name).ok().flatten() {
                            if let Ok(s) = dwarf.attr_string(unit, attr) {
                                let lname = s.to_string_lossy();
                                let demangled = demangle(&lname);
                                if !demangled.is_empty() {
                                    insert_global_type(&mut global_type_defs, &units, demangled, die_ref);
                                }
                            }
                        }
                    }

                    // 嵌套结构体/类/联合体压入作用域栈（仅当有子 DIE 时）
                    if tag != gimli::DW_TAG_typedef && tag != gimli::DW_TAG_enumeration_type
                        && entry.has_children() {
                            if let Some(ref name) = raw_name {
                                if !name.is_empty() {
                                    scope_stack.push((depth, name.clone()));
                                }
                            }
                        }
                }
                gimli::DW_TAG_subprogram => {
                    let low_pc = match entry.attr_value(gimli::DW_AT_low_pc)? {
                        Some(AttributeValue::Addr(a)) => Some(a),
                        _ => None,
                    };
                    if let Some(low_pc) = low_pc {
                        let fn_ref = DieRef {
                            unit_idx,
                            offset: entry.offset(),
                        };
                        let chain = resolve_specification_chain(&units, &unit_ranges, fn_ref);
                        for &dref in &chain {
                            let u = &units[dref.unit_idx];
                            if let Ok(e) = u.entry(dref.offset) {
                                if let Ok(Some(attr)) = e.attr_value(gimli::DW_AT_decl_file) {
                                    let fi = match attr {
                                        AttributeValue::FileIndex(fi) => Some(fi),
                                        _ => attr.udata_value(),
                                    };
                                    if let Some(fi) = fi {
                                        if let Some(global_idx) = cu_local_files[dref.unit_idx].get(&fi) {
                                            if let Some(path) = files.get(*global_idx as usize) {
                                                let line = e
                                                    .attr_value(gimli::DW_AT_decl_line)
                                                    .ok()
                                                    .flatten()
                                                    .and_then(|a| a.udata_value())
                                                    .unwrap_or(0) as u32;
                                                func_decls.insert(low_pc & !1, (path.clone(), line));
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }

    // 3. Pass 2: 变量解析与跨 CU 类型展开
    let mut var_dedup: HashMap<(u64, String), usize> = HashMap::new();
    let mut global_type_cache: HashMap<DieRef, SymbolNode> = HashMap::new();

    for (unit_idx, unit) in units.iter().enumerate() {
        let mut entries = unit.entries();
        let mut depth: isize = 0;
        while let Some((delta, entry)) = entries.next_dfs()? {
            depth += delta;
            if entry.tag() == gimli::DW_TAG_variable {
                let Some(addr) = static_address(entry, unit) else { continue };

                // 纯前向声明跳过
                if matches!(
                    entry.attr_value(gimli::DW_AT_declaration).ok().flatten(),
                    Some(AttributeValue::Flag(true))
                ) {
                    continue;
                }

                let var_ref = DieRef {
                    unit_idx,
                    offset: entry.offset(),
                };
                let chain = resolve_specification_chain(&units, &unit_ranges, var_ref);

                let raw_name = chain.iter().find_map(|&dref| {
                    let u = &units[dref.unit_idx];
                    let e = u.entry(dref.offset).ok()?;
                    die_name(dwarf, u, &e)
                });
                let Some(name) = raw_name else { continue };
                if name.starts_with("__") && depth > 1 {
                    continue;
                }

                let type_die_ref = chain.iter().find_map(|&dref| {
                    let u = &units[dref.unit_idx];
                    let e = u.entry(dref.offset).ok()?;
                    type_ref_resolved(&units, &unit_ranges, dref.unit_idx, &e)
                });

                let node = if let Some(tref) = type_die_ref {
                    build_type_node(
                        dwarf,
                        &units,
                        &unit_ranges,
                        &global_type_defs,
                        &die_qualified_names,
                        &mut global_type_cache,
                        tref,
                        0,
                    )
                } else {
                    None
                };

                let mut node = node.unwrap_or_else(|| SymbolNode {
                    name: String::new(),
                    type_name: "unknown".into(),
                    address: 0,
                    size: 4,
                    encoding: ValueEncoding::Unsigned,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: Vec::new(),
                    members: Vec::new(),
                    decl_file: None,
                    decl_line: None,
                });

                node.name = demangle(&name);
                rebase_addresses(&mut node, addr);

                // 声明位置（沿 specification 追溯，并在对应 CU 的文件表中查找）
                for &dref in &chain {
                    let u = &units[dref.unit_idx];
                    if let Ok(e) = u.entry(dref.offset) {
                        if node.decl_file.is_none() {
                            if let Ok(Some(attr)) = e.attr_value(gimli::DW_AT_decl_file) {
                                let fi = match attr {
                                    AttributeValue::FileIndex(fi) => Some(fi),
                                    _ => attr.udata_value(),
                                };
                                if let Some(fi) = fi {
                                    if let Some(global_idx) = cu_local_files[dref.unit_idx].get(&fi) {
                                        node.decl_file = files.get(*global_idx as usize).cloned();
                                    }
                                }
                            }
                        }
                        if node.decl_line.is_none() {
                            if let Ok(Some(attr)) = e.attr_value(gimli::DW_AT_decl_line) {
                                node.decl_line = attr.udata_value().map(|v| v as u32);
                            }
                        }
                        if node.decl_file.is_some() && node.decl_line.is_some() {
                            break;
                        }
                    }
                }

                // 同址同名去重：优先保留已知类型
                match var_dedup.entry((node.address, node.name.clone())) {
                    std::collections::hash_map::Entry::Occupied(occ) => {
                        let existing = &mut variables[*occ.get()];
                        if existing.type_name == "unknown" && node.type_name != "unknown" {
                            *existing = node;
                        }
                    }
                    std::collections::hash_map::Entry::Vacant(vac) => {
                        vac.insert(variables.len());
                        variables.push(node);
                    }
                }
            }
        }
    }

    Ok(())
}

fn resolve_file_path<'a>(
    dwarf: &Dwarf<R<'a>>,
    unit: &Unit<R<'a>>,
    program: &gimli::IncompleteLineProgram<R<'a>>,
    index: u64,
) -> Option<String> {
    let header = program.header();
    let file = header.file(index)?;
    let mut parts: Vec<String> = Vec::new();
    // comp_dir
    if let Some(comp_dir) = &unit.comp_dir {
        parts.push(comp_dir.to_string_lossy().into_owned());
    }
    // directory
    if let Some(dir) = file.directory(header) {
        if let Ok(dir) = dwarf.attr_string(unit, dir) {
            let d = dir.to_string_lossy().into_owned();
            if is_absolute(&d) {
                parts.clear();
            }
            parts.push(d);
        }
    }
    let name = dwarf
        .attr_string(unit, file.path_name())
        .ok()?
        .to_string_lossy()
        .into_owned();
    if is_absolute(&name) {
        return Some(name);
    }
    parts.push(name);
    Some(parts.join("/"))
}

fn is_absolute(p: &str) -> bool {
    p.starts_with('/') || (p.len() >= 2 && p.as_bytes()[1] == b':')
}

/// 提取 DW_OP_addr 静态地址（其余 location 表达式跳过，对照原版）。
fn static_address(entry: &Die<'_, '_>, unit: &Unit<R<'_>>) -> Option<u64> {
    let attr = entry.attr_value(gimli::DW_AT_location).ok()??;
    let AttributeValue::Exprloc(expr) = attr else { return None };
    let bytes = expr.0.slice();
    if bytes.is_empty() || bytes[0] != gimli::DW_OP_addr.0 {
        return None;
    }
    let addr_size = unit.header.address_size() as usize;
    if bytes.len() < 1 + addr_size {
        return None;
    }
    let mut addr: u64 = 0;
    for (i, b) in bytes[1..1 + addr_size].iter().enumerate() {
        addr |= (*b as u64) << (8 * i);
    }
    Some(addr)
}

/// 将属性值转换为跨 CU 全局 DIE 引用（支持 UnitRef 和 DebugInfoRef）。
fn attr_to_die_ref<'a>(
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    cur_unit_idx: usize,
    attr: AttributeValue<R<'a>>,
) -> Option<DieRef> {
    match attr {
        AttributeValue::UnitRef(uoff) => Some(DieRef {
            unit_idx: cur_unit_idx,
            offset: uoff,
        }),
        AttributeValue::DebugInfoRef(doff) => {
            let off = doff.0;
            let idx = unit_ranges.partition_point(|&(start, _)| start <= off);
            if idx > 0 {
                let (_start, end) = unit_ranges[idx - 1];
                if off < end {

                    let sec_off = UnitSectionOffset::DebugInfoOffset(doff);
                    if let Some(uoff) = sec_off.to_unit_offset(&units[idx - 1]) {
                        return Some(DieRef {
                            unit_idx: idx - 1,
                            offset: uoff,
                        });
                    }
                }
            }
            None
        }
        _ => None,
    }
}

/// 提取 DW_AT_type 属性并转换为 DieRef。
fn type_ref_resolved<'a>(
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    cur_unit_idx: usize,
    entry: &Die<'a, '_>,
) -> Option<DieRef> {
    let attr = entry.attr_value(gimli::DW_AT_type).ok()??;
    attr_to_die_ref(units, unit_ranges, cur_unit_idx, attr)
}

/// 追溯 DW_AT_specification / DW_AT_abstract_origin 链。
fn resolve_specification_chain<'a>(
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    start_ref: DieRef,
) -> Vec<DieRef> {
    let mut chain = Vec::with_capacity(4);
    let mut cur = start_ref;
    chain.push(cur);
    for _ in 0..6 {
        let unit = &units[cur.unit_idx];
        let Ok(entry) = unit.entry(cur.offset) else { break };
        let spec_attr = entry
            .attr_value(gimli::DW_AT_specification)
            .ok()
            .flatten()
            .or_else(|| entry.attr_value(gimli::DW_AT_abstract_origin).ok().flatten());
        let Some(attr) = spec_attr else { break };
        let Some(target_ref) = attr_to_die_ref(units, unit_ranges, cur.unit_idx, attr) else { break };
        if chain.contains(&target_ref) {
            break;
        }
        chain.push(target_ref);
        cur = target_ref;
    }
    chain
}

/// 取单 entry 名字（DW_AT_name 优先，次选 demangle 的 DW_AT_linkage_name）。
fn die_name<'a>(
    dwarf: &Dwarf<R<'a>>,
    unit: &Unit<R<'a>>,
    entry: &Die<'a, '_>,
) -> Option<String> {
    if let Some(attr) = entry.attr_value(gimli::DW_AT_name).ok().flatten() {
        if let Ok(s) = dwarf.attr_string(unit, attr) {
            return Some(s.to_string_lossy().into_owned());
        }
    }
    if let Some(attr) = entry.attr_value(gimli::DW_AT_linkage_name).ok().flatten() {
        if let Ok(s) = dwarf.attr_string(unit, attr) {
            let lname = s.to_string_lossy();
            return Some(demangle(&lname));
        }
    }
    None
}

/// 追溯 specification 链取 DIE 名字。
fn die_name_resolved<'a>(
    dwarf: &Dwarf<R<'a>>,
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    die_ref: DieRef,
) -> Option<String> {
    let chain = resolve_specification_chain(units, unit_ranges, die_ref);
    for dref in chain {
        let unit = &units[dref.unit_idx];
        if let Ok(entry) = unit.entry(dref.offset) {
            if let Some(name) = die_name(dwarf, unit, &entry) {
                return Some(name);
            }
        }
    }
    None
}

/// 递归构建类型节点（地址为相对 0 的偏移，最后由外层 rebase）。
#[allow(clippy::too_many_arguments)]
fn build_type_node<'a>(
    dwarf: &Dwarf<R<'a>>,
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    global_type_defs: &HashMap<String, DieRef>,
    die_qualified_names: &HashMap<DieRef, String>,
    cache: &mut HashMap<DieRef, SymbolNode>,
    die_ref: DieRef,
    depth: usize,
) -> Option<SymbolNode> {
    if depth > MAX_DEPTH {
        return None;
    }
    if let Some(cached) = cache.get(&die_ref) {
        return Some(cached.clone());
    }

    let unit = &units[die_ref.unit_idx];
    let entry = match unit.entry(die_ref.offset) {
        Ok(e) => e,
        Err(_) => return None,
    };
    let tag = entry.tag();
    let spec_chain = resolve_specification_chain(units, unit_ranges, die_ref);
    let byte_size = spec_chain
        .iter()
        .find_map(|&dref| {
            let u = &units[dref.unit_idx];
            let e = u.entry(dref.offset).ok()?;
            e.attr_value(gimli::DW_AT_byte_size)
                .ok()
                .flatten()
                .and_then(|a| a.udata_value())
        })
        .unwrap_or(0) as u32;
    let name = die_name_resolved(dwarf, units, unit_ranges, die_ref);

    let node = match tag {
        gimli::DW_TAG_base_type => {
            let encoding = match entry.attr_value(gimli::DW_AT_encoding).ok().flatten() {
                Some(AttributeValue::Encoding(e)) => match e {
                    gimli::DW_ATE_signed | gimli::DW_ATE_signed_char => ValueEncoding::Signed,
                    gimli::DW_ATE_float => ValueEncoding::Float,
                    gimli::DW_ATE_boolean => ValueEncoding::Bool,
                    _ => ValueEncoding::Unsigned,
                },
                _ => ValueEncoding::Unsigned,
            };
            Some(SymbolNode {
                name: String::new(),
                type_name: name.unwrap_or_else(|| "base".into()),
                address: 0,
                size: byte_size.max(1),
                encoding,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values: Vec::new(),
                members: Vec::new(),
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_typedef
        | gimli::DW_TAG_const_type
        | gimli::DW_TAG_volatile_type
        | gimli::DW_TAG_restrict_type => {
            let tref_opt = type_ref_resolved(units, unit_ranges, die_ref.unit_idx, &entry);
            let inner = tref_opt
                .and_then(|tref| {
                    build_type_node(
                        dwarf,
                        units,
                        unit_ranges,
                        global_type_defs,
                        die_qualified_names,
                        cache,
                        tref,
                        depth + 1,
                    )
                });
            match inner {
                Some(mut node) => {
                    if tag == gimli::DW_TAG_typedef {
                        if let Some(n) = die_qualified_names.get(&die_ref).cloned().or(name) {
                            node.type_name = n;
                        }
                    }
                    Some(node)
                }
                None => {
                    // 若存在 DW_AT_type 但 inner 解析失败（例如超出最大深度），
                    // 不要返回伪造的 size=0 void 节点污染全局类型缓存。
                    if tref_opt.is_some() {
                        None
                    } else {
                        Some(SymbolNode {
                            name: String::new(),
                            type_name: name.unwrap_or_else(|| "void".into()),
                            address: 0,
                            size: byte_size,
                            encoding: ValueEncoding::Unsigned,
                            is_pointer: false,
                            pointee_size: 0,
                            pointee_type: String::new(),
                            pointee_encoding: ValueEncoding::Unsigned,
                            enum_values: Vec::new(),
                            members: Vec::new(),
                            decl_file: None,
                            decl_line: None,
                        })
                    }
                }
            }
        }
        gimli::DW_TAG_pointer_type | gimli::DW_TAG_reference_type | gimli::DW_TAG_rvalue_reference_type => {
            let pointee = type_ref_resolved(units, unit_ranges, die_ref.unit_idx, &entry)
                .and_then(|tref| {
                    build_type_node(
                        dwarf,
                        units,
                        unit_ranges,
                        global_type_defs,
                        die_qualified_names,
                        cache,
                        tref,
                        depth + 1,
                    )
                });
            let (ptype, psize, penc, members) = match pointee {
                Some(p) => (p.type_name, p.size, p.encoding, p.members),
                None => ("void".into(), 0, ValueEncoding::Unsigned, Vec::new()),
            };
            Some(SymbolNode {
                name: String::new(),
                type_name: format!("{ptype} *"),
                address: 0,
                size: 4, // 32 位 Cortex-M 假设（对照原版）
                encoding: ValueEncoding::Pointer,
                is_pointer: true,
                pointee_size: psize,
                pointee_type: ptype,
                pointee_encoding: penc,
                enum_values: Vec::new(),
                members,
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_array_type => {
            let elem = type_ref_resolved(units, unit_ranges, die_ref.unit_idx, &entry)
                .and_then(|tref| {
                    build_type_node(
                        dwarf,
                        units,
                        unit_ranges,
                        global_type_defs,
                        die_qualified_names,
                        cache,
                        tref,
                        depth + 1,
                    )
                })?;
            let counts = array_counts(unit, &entry);
            // 畸形 DWARF 防护：upper+1 / 多维计数相乘在 u64::MAX 附近会溢出
            // （debug panic 杀 elf_load 线程、release 回绕出荒谬 size）。
            // 溢出按"未知大小"处理（total_count=0 → size=0），展开上限不受影响
            let total_count: u64 = if counts.is_empty() {
                0
            } else {
                counts
                    .iter()
                    .try_fold(1u64, |acc, &c| acc.checked_mul(c))
                    .unwrap_or(0)
            };
            let expand_count = total_count.min(MAX_ARRAY_EXPAND);
            let total = if byte_size > 0 {
                byte_size
            } else {
                // size 用真实元素数（饱和乘 + 结果钳到 u32）；截断只影响展开
                (elem.size as u64)
                    .saturating_mul(total_count)
                    .min(u32::MAX as u64) as u32
            };
            let mut members = Vec::new();
            for i in 0..expand_count {
                let mut m = elem.clone();
                m.name = format!("[{i}]");
                let elem_offset = i * elem.size as u64;
                shift_addresses(&mut m, elem_offset);
                members.push(m);
            }
            let type_suffix = if counts.is_empty() {
                "[]".into()
            } else {
                counts.iter().map(|c| format!("[{c}]")).collect::<String>()
            };
            Some(SymbolNode {
                name: String::new(),
                type_name: format!("{}{}", elem.type_name, type_suffix),
                address: 0,
                size: total,
                encoding: ValueEncoding::Composite,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values: Vec::new(),
                members,
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_structure_type | gimli::DW_TAG_class_type | gimli::DW_TAG_union_type => {
            let is_decl = matches!(
                entry.attr_value(gimli::DW_AT_declaration).ok().flatten(),
                Some(AttributeValue::Flag(true))
            ) || (!entry.has_children() && byte_size == 0);
            if is_decl {
                let target_def = die_qualified_names
                    .get(&die_ref)
                    .and_then(|qn| global_type_defs.get(qn))
                    .or_else(|| name.as_ref().and_then(|sn| global_type_defs.get(sn)));
                if let Some(&def_ref) = target_def {
                    if def_ref != die_ref {
                        if let Some(node) = build_type_node(
                            dwarf,
                            units,
                            unit_ranges,
                            global_type_defs,
                            die_qualified_names,
                            cache,
                            def_ref,
                            depth + 1,
                        ) {
                            cache.insert(die_ref, node.clone());
                            return Some(node);
                        }
                    }
                }
                // 降级保护：保留声明名，不退化为 void
                let decl_name = die_qualified_names
                    .get(&die_ref)
                    .cloned()
                    .or(name)
                    .unwrap_or_else(|| {
                        if tag == gimli::DW_TAG_union_type {
                            "union".into()
                        } else {
                            "struct".into()
                        }
                    });
                return Some(SymbolNode {
                    name: String::new(),
                    type_name: decl_name,
                    address: 0,
                    size: 0,
                    encoding: ValueEncoding::Composite,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: Vec::new(),
                    members: Vec::new(),
                    decl_file: None,
                    decl_line: None,
                });
            }

            let type_name = die_qualified_names
                .get(&die_ref)
                .cloned()
                .or(name)
                .unwrap_or_else(|| {
                    if tag == gimli::DW_TAG_union_type {
                        "union".into()
                    } else {
                        "struct".into()
                    }
                });
            cache.insert(
                die_ref,
                SymbolNode {
                    name: String::new(),
                    type_name: type_name.clone(),
                    address: 0,
                    size: byte_size,
                    encoding: ValueEncoding::Composite,
                    is_pointer: false,
                    pointee_size: 0,
                    pointee_type: String::new(),
                    pointee_encoding: ValueEncoding::Unsigned,
                    enum_values: Vec::new(),
                    members: Vec::new(),
                    decl_file: None,
                    decl_line: None,
                },
            );
            let members = collect_members(
                dwarf,
                units,
                unit_ranges,
                global_type_defs,
                die_qualified_names,
                cache,
                die_ref,
                depth,
            ).unwrap_or_default();
            Some(SymbolNode {
                name: String::new(),
                type_name,
                address: 0,
                size: byte_size,
                encoding: ValueEncoding::Composite,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values: Vec::new(),
                members,
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_enumeration_type => {
            let mut enum_values = Vec::new();
            if let Ok(mut tree) = unit.entries_tree(Some(die_ref.offset)) {
                if let Ok(root) = tree.root() {
                    let mut children = root.children();
                    while let Ok(Some(child)) = children.next() {
                        let e = child.entry();
                        if e.tag() == gimli::DW_TAG_enumerator {
                            let ename = die_name(dwarf, unit, e).unwrap_or_default();
                            let val = e
                                .attr_value(gimli::DW_AT_const_value)
                                .ok()
                                .flatten()
                                .and_then(|a| {
                                    a.sdata_value()
                                        .or_else(|| a.udata_value().map(|u| u as i64))
                                })
                                .unwrap_or(0);
                            enum_values.push((val, ename));
                        }
                    }
                }
            }
            let type_name = die_qualified_names
                .get(&die_ref)
                .cloned()
                .or(name.map(|n| format!("enum {n}")))
                .unwrap_or_else(|| "enum".into());
            Some(SymbolNode {
                name: String::new(),
                type_name,
                address: 0,
                size: byte_size.max(1),
                encoding: ValueEncoding::Unsigned,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values,
                members: Vec::new(),
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_subroutine_type => {
            let ret_type = type_ref_resolved(units, unit_ranges, die_ref.unit_idx, &entry)
                .and_then(|tref| {
                    build_type_node(
                        dwarf,
                        units,
                        unit_ranges,
                        global_type_defs,
                        die_qualified_names,
                        cache,
                        tref,
                        depth + 1,
                    )
                })
                .map(|n| n.type_name)
                .unwrap_or_else(|| "void".into());
            Some(SymbolNode {
                name: String::new(),
                type_name: format!("{ret_type}()"),
                address: 0,
                size: 0,
                encoding: ValueEncoding::Unsigned,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values: Vec::new(),
                members: Vec::new(),
                decl_file: None,
                decl_line: None,
            })
        }
        gimli::DW_TAG_unspecified_type => {
            Some(SymbolNode {
                name: String::new(),
                type_name: name.unwrap_or_else(|| "void".into()),
                address: 0,
                size: 0,
                encoding: ValueEncoding::Unsigned,
                is_pointer: false,
                pointee_size: 0,
                pointee_type: String::new(),
                pointee_encoding: ValueEncoding::Unsigned,
                enum_values: Vec::new(),
                members: Vec::new(),
                decl_file: None,
                decl_line: None,
            })
        }
        _ => {
            type_ref_resolved(units, unit_ranges, die_ref.unit_idx, &entry)
                .and_then(|tref| {
                    build_type_node(
                        dwarf,
                        units,
                        unit_ranges,
                        global_type_defs,
                        die_qualified_names,
                        cache,
                        tref,
                        depth + 1,
                    )
                })
        }
    };

    if let Some(n) = &node {
        cache.insert(die_ref, n.clone());
    }
    node
}

/// 提取成员 DIE 的 DW_AT_type，支持沿 specification 追溯。
fn member_type_ref<'a>(
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    cur_unit_idx: usize,
    entry: &Die<'a, '_>,
) -> Option<DieRef> {
    if let Some(tref) = type_ref_resolved(units, unit_ranges, cur_unit_idx, entry) {
        return Some(tref);
    }
    let m_ref = DieRef {
        unit_idx: cur_unit_idx,
        offset: entry.offset(),
    };
    let chain = resolve_specification_chain(units, unit_ranges, m_ref);
    chain.iter().find_map(|&dref| {
        let u = &units[dref.unit_idx];
        let e = u.entry(dref.offset).ok()?;
        type_ref_resolved(units, unit_ranges, dref.unit_idx, &e)
    })
}

#[allow(clippy::too_many_arguments)]
fn collect_members<'a>(
    dwarf: &Dwarf<R<'a>>,
    units: &[Unit<R<'a>>],
    unit_ranges: &[(usize, usize)],
    global_type_defs: &HashMap<String, DieRef>,
    die_qualified_names: &HashMap<DieRef, String>,
    cache: &mut HashMap<DieRef, SymbolNode>,
    die_ref: DieRef,
    depth: usize,
) -> Option<Vec<SymbolNode>> {
    let mut members = Vec::new();
    let unit = &units[die_ref.unit_idx];
    let mut tree = unit.entries_tree(Some(die_ref.offset)).ok()?;
    let root = tree.root().ok()?;
    let mut children = root.children();
    while let Ok(Some(child)) = children.next() {
        let entry = child.entry();
        let tag = entry.tag();
        if tag != gimli::DW_TAG_member && tag != gimli::DW_TAG_inheritance {
            continue;
        }
        // 静态成员跳过（external 或 declaration）
        if matches!(
            entry.attr_value(gimli::DW_AT_declaration).ok().flatten(),
            Some(AttributeValue::Flag(true))
        ) || matches!(
            entry.attr_value(gimli::DW_AT_external).ok().flatten(),
            Some(AttributeValue::Flag(true))
        ) {
            continue;
        }

        let mem_offset = member_offset(entry);
        let Some(type_ref) = member_type_ref(units, unit_ranges, die_ref.unit_idx, entry) else {
            continue;
        };
        let Some(mut node) = build_type_node(
            dwarf,
            units,
            unit_ranges,
            global_type_defs,
            die_qualified_names,
            cache,
            type_ref,
            depth + 1,
        ) else {
            continue;
        };

        if tag == gimli::DW_TAG_inheritance {
            shift_addresses(&mut node, mem_offset);
            for m in node.members {
                members.push(m);
                if members.len() >= MAX_MEMBERS {
                    return Some(members);
                }
            }
            continue;
        }

        let name = die_name(dwarf, &units[die_ref.unit_idx], entry)
            .unwrap_or_else(|| "<anon>".into());
        node.name = name;
        shift_addresses(&mut node, mem_offset);
        members.push(node);
        if members.len() >= MAX_MEMBERS {
            break;
        }
    }
    Some(members)
}

fn member_offset(entry: &Die<'_, '_>) -> u64 {
    if let Ok(Some(attr)) = entry.attr_value(gimli::DW_AT_data_member_location) {
        if let Some(v) = attr.udata_value() {
            return v;
        }
        if let Some(v) = attr.sdata_value() {
            return v as u64;
        }
        if let AttributeValue::Exprloc(expr) = attr {
            let bytes = expr.0.slice();
            if !bytes.is_empty() {
                let op = bytes[0];
                if op == gimli::DW_OP_plus_uconst.0 {
                    let slice = &bytes[1..];
                    let mut reader = gimli::EndianSlice::new(slice, gimli::RunTimeEndian::Little);
                    if let Ok(val) = reader.read_uleb128() {
                        return val;
                    }
                } else if (gimli::DW_OP_lit0.0..=gimli::DW_OP_lit31.0).contains(&op) {
                    return (op - gimli::DW_OP_lit0.0) as u64;
                } else if op == gimli::DW_OP_const1u.0 && bytes.len() >= 2 {
                    return bytes[1] as u64;
                } else if op == gimli::DW_OP_const2u.0 && bytes.len() >= 3 {
                    return u16::from_le_bytes([bytes[1], bytes[2]]) as u64;
                } else if op == gimli::DW_OP_const4u.0 && bytes.len() >= 5 {
                    return u32::from_le_bytes([bytes[1], bytes[2], bytes[3], bytes[4]]) as u64;
                } else if op == gimli::DW_OP_constu.0 {
                    let slice = &bytes[1..];
                    let mut reader = gimli::EndianSlice::new(slice, gimli::RunTimeEndian::Little);
                    if let Ok(val) = reader.read_uleb128() {
                        return val;
                    }
                }
            }
        }
    }
    0
}

fn array_counts(unit: &Unit<R<'_>>, entry: &Die<'_, '_>) -> Vec<u64> {
    let mut counts = Vec::new();
    let Ok(mut tree) = unit.entries_tree(Some(entry.offset())) else { return counts };
    let Ok(root) = tree.root() else { return counts };
    let mut children = root.children();
    while let Ok(Some(child)) = children.next() {
        let e = child.entry();
        if e.tag() == gimli::DW_TAG_subrange_type {
            if let Some(count) = e
                .attr_value(gimli::DW_AT_count)
                .ok()
                .flatten()
                .and_then(|a| a.udata_value())
            {
                counts.push(count);
            } else if let Some(upper) = e
                .attr_value(gimli::DW_AT_upper_bound)
                .ok()
                .flatten()
                .and_then(|a| a.udata_value())
            {
                // u64::MAX 的 upper_bound（畸形 DWARF）+1 会溢出：饱和处理
                counts.push(upper.saturating_add(1));
            }
        }
    }
    counts
}

/// 类型树中的 address 字段是相对偏移；成员再加自身偏移。
/// 指针成员的子字段地址代表其指向对象内部的相对偏移，不累加指针本身的父级基址。
fn shift_addresses(node: &mut SymbolNode, offset: u64) {
    node.address += offset;
    if !node.is_pointer {
        for m in &mut node.members {
            shift_addresses(m, offset);
        }
    }
}

/// 顶层变量：把相对偏移改为绝对地址。
/// 指针的子成员地址代表其指向对象内部的相对偏移，不被指针自身的变量绝对地址污染。
pub(crate) fn rebase_addresses(node: &mut SymbolNode, base: u64) {
    node.address += base;
    if !node.is_pointer {
        for m in &mut node.members {
            rebase_addresses(m, base);
        }
    }
}
