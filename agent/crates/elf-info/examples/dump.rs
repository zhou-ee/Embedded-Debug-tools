//! 开发调试工具：解析 ELF 并打印索引摘要。
//! 用法：cargo run -p elf-info --example dump -- <elf路径>

use elf_info::ElfIndex;
use std::path::Path;

fn main() {
    let path = std::env::args().nth(1).expect("用法: dump <elf>");
    let index = ElfIndex::load(Path::new(&path)).expect("解析失败");

    println!("== 函数 ({}) ==", index.functions.len());
    for f in index.functions.iter().take(20) {
        println!(
            "  {:<28} 0x{:08x}..0x{:08x}  {}:{}",
            f.name,
            f.start,
            f.end,
            f.file.as_deref().unwrap_or("?"),
            f.line.unwrap_or(0)
        );
    }

    println!("== 变量 ({}) ==", index.variables.len());
    for v in index.variables.iter().take(20) {
        println!(
            "  {:<20} {:<16} 0x{:08x} size={} enc={:?} members={}",
            v.name,
            v.type_name,
            v.address,
            v.size,
            v.encoding,
            v.members.len()
        );
        for m in v.members.iter().take(6) {
            println!(
                "      .{:<14} {:<12} 0x{:08x} size={} enc={:?}",
                m.name, m.type_name, m.address, m.size, m.encoding
            );
        }
    }

    let mut unknown_count = 0;
    let mut void_ptr_count = 0;
    let mut zero_size_count = 0;
    for v in &index.variables {
        if v.type_name == "unknown" || v.type_name.contains("unknown") {
            unknown_count += 1;
            println!("  [AUDIT-UNKNOWN] name={} addr=0x{:08x} type={}", v.name, v.address, v.type_name);
        }
        if v.type_name == "void *" || v.type_name == "void*" {
            void_ptr_count += 1;
            println!("  [AUDIT-VOID-PTR] name={} addr=0x{:08x}", v.name, v.address);
        }
        if v.size == 0 {
            zero_size_count += 1;
            println!("  [AUDIT-ZERO-SIZE] name={} addr=0x{:08x} type={}", v.name, v.address, v.type_name);
        }
    }
    println!(
        "== AUDIT: total={}, unknown={}, void_ptr={}, zero_size={} ==",
        index.variables.len(), unknown_count, void_ptr_count, zero_size_count
    );

    if let Some(v) = index.variables.iter().find(|v| v.name == "xTickCount") {
        println!(
            "== xTickCount: name={} type={} size={} is_ptr={} p_size={} enc={:?} file={:?}:{:?} ==",
            v.name, v.type_name, v.size, v.is_pointer, v.pointee_size, v.encoding, v.decl_file, v.decl_line
        );
    }

    if let Some(v) = index.variables.iter().find(|v| v.name == "g_imu_data") {
        println!(
            "== g_imu_data: name={} type={} size={} is_ptr={} p_size={} p_type={} members={} ==",
            v.name, v.type_name, v.size, v.is_pointer, v.pointee_size, v.pointee_type, v.members.len()
        );
        for m in v.members.iter().take(10) {
            println!(
                "      .{:<20} {:<30} offset=0x{:04x} size={} enc={:?}",
                m.name, m.type_name, m.address, m.size, m.encoding
            );
        }
    }

    if let Some(v) = index.variables.iter().find(|v| v.name == "wl_chassis_ptr") {
        println!(
            "== wl_chassis_ptr: name={} type={} size={} is_ptr={} p_size={} p_type={} members={} ==",
            v.name, v.type_name, v.size, v.is_pointer, v.pointee_size, v.pointee_type, v.members.len()
        );
        for m in v.members.iter().take(15) {
            println!(
                "      .{:<20} {:<30} offset=0x{:04x} size={} enc={:?}",
                m.name, m.type_name, m.address, m.size, m.encoding
            );
        }
    }



    // 行表往返验证
    if let Some(main_fn) = index.functions.iter().find(|f| f.name == "main") {
        println!("== main() 行表验证 ==");
        println!("  main @ 0x{:08x}", main_fn.start);
        if let Some((file, line)) = index.line_for_addr(main_fn.start) {
            println!("  line_for_addr -> {file}:{line}");
            if let Some((addr, resolved)) = index.addr_for_line(&file, line) {
                println!("  addr_for_line({line}) -> 0x{addr:08x} (line {resolved})");
            }
        }
        if let Some(next) = index.next_line_addr_after(main_fn.start) {
            println!("  next_line_addr -> 0x{next:08x}");
        }
    }

    // 成员链验证
    for expr in [
        "g_motor.position.x",
        "g_motor.position.y",
        "g_motor.raw[0]",
        "g_counter.acc_",
        "wl_chassis_ptr._head",
        "wl_chassis_ptr->_head",
        "g_imu_data.TempWhenCali",
        "g_imu_data->TempWhenCali",
    ] {
        match index.resolve_member_chain(expr) {
            Some(node) => println!(
                "resolve {expr} -> 0x{:08x} {} size={}",
                node.address, node.type_name, node.size
            ),
            None => println!("resolve {expr} -> 未找到"),
        }
    }

    // STL 类型树检查（stl_demo.elf 用）
    fn find_deep<'a>(
        n: &'a elf_info::SymbolNode,
        name: &str,
    ) -> Option<&'a elf_info::SymbolNode> {
        for m in &n.members {
            if m.name == name {
                return Some(m);
            }
            if let Some(f) = find_deep(m, name) {
                return Some(f);
            }
        }
        None
    }
    for var in &index.variables {
        if !var.name.starts_with("g_") {
            continue;
        }
        println!("== STL 检查: {} : {} ==", var.name, var.type_name);
        for member_name in ["_M_start", "_M_finish", "_M_p", "_M_string_length", "_M_node", "_M_header", "_M_node_count"] {
            if let Some(m) = find_deep(var, member_name) {
                println!(
                    "   {member_name} @ 0x{:08x} ptr={} pointee={} ({} B, {:?})",
                    m.address, m.is_pointer, m.pointee_type, m.pointee_size, m.pointee_encoding
                );
            }
        }
    }
}
