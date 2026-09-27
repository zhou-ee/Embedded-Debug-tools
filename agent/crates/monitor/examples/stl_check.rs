//! 真机验证：g4_tool_test 新增测试代码的调试特性。
//! - 成员链监视：g_motor.position.x / g_counter.acc_（struct/类私有成员）
//! - STL 展开：g_vec(vector) / g_str(string) / g_list(list) / g_map(map 计数)
//! - 多频率正弦波：运行中示波采样 sin_1hz/sin_5hz/sin_20hz 的幅值与变化
//!
//! 用法：cargo run -p monitor --example stl_check [-- --skip-flash]

use debug_core::{BackendKind, ConnectParams, TargetState};
use elf_info::ElfIndex;
use monitor::{Command, Event, ScopeTarget};
use std::time::{Duration, Instant};

const ELF: &str = r"E:\Software\Develop\Embeded\Pack\g4_tool_test\build\g4_tool_test.elf";
const TARGET: &str = "STM32G431CBTx";

fn wait_for(
    rx: &crossbeam_channel::Receiver<Event>,
    timeout: Duration,
    mut pred: impl FnMut(&Event) -> bool,
) -> Option<Event> {
    let deadline = Instant::now() + timeout;
    loop {
        let remain = deadline.saturating_duration_since(Instant::now());
        if remain.is_zero() {
            return None;
        }
        match rx.recv_timeout(remain) {
            Ok(ev) => {
                if let Event::Error { message } | Event::Log { message } = &ev {
                    eprintln!("      [engine] {message}");
                }
                if pred(&ev) {
                    return Some(ev);
                }
            }
            Err(_) => return None,
        }
    }
}

fn read_u32(tx: &crossbeam_channel::Sender<Command>, addr: u64) -> Option<u32> {
    let (rtx, rrx) = crossbeam_channel::bounded(1);
    tx.send(Command::ReadMemSync {
        addr,
        size: 4,
        reply: rtx,
    })
    .ok()?;
    let bytes = rrx.recv_timeout(Duration::from_secs(2)).ok()?.ok()?;
    if bytes.len() < 4 {
        return None;
    }
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

fn read_bytes(
    tx: &crossbeam_channel::Sender<Command>,
    addr: u64,
    size: usize,
) -> Option<Vec<u8>> {
    let (rtx, rrx) = crossbeam_channel::bounded(1);
    tx.send(Command::ReadMemSync {
        addr,
        size,
        reply: rtx,
    })
    .ok()?;
    rrx.recv_timeout(Duration::from_secs(2)).ok()?.ok()
}

fn main() {
    let skip_flash = std::env::args().any(|a| a == "--skip-flash");

    println!("== A. ELF 索引 ==");
    let elf = ElfIndex::load(std::path::Path::new(ELF)).expect("加载 ELF");
    let mut pass = 0usize;
    let mut fail = 0usize;
    let mut check = |ok: bool, name: &str, detail: &str| {
        if ok {
            pass += 1;
            println!("  ✔ {name}  {detail}");
        } else {
            fail += 1;
            println!("  ✘ {name}  {detail}");
        }
    };

    let var = |name: &str| elf.variables.iter().find(|v| v.name == name).cloned();
    for n in ["sin_1hz", "sin_5hz", "sin_20hz", "g_motor", "g_counter", "g_vec", "g_str", "g_list", "g_map"] {
        check(var(n).is_some(), &format!("符号 {n}"), "");
    }

    if !skip_flash {
        println!("== B. 烧录 ==");
        let r = debug_core::flash::flash_firmware(TARGET, 2_000_000, std::path::Path::new(ELF), debug_core::flash::FlashMode::Run, |_| {});
        check(r.is_ok(), "烧录", &format!("{:?}", r.err()));
    }

    println!("== C. 真机验证 ==");
    let mut handle = monitor::spawn_engine();
    let tx = handle.cmd_tx.clone();
    let rx = handle.event_rx.clone();
    tx.send(Command::Connect(ConnectParams {
        kind: BackendKind::ProbeRs,
        target: Some(TARGET.into()),
        cfg_file: None,
        openocd_path: None,
        scripts_dir: None,
        speed_hz: 2_000_000,
        ..Default::default()
    }))
    .unwrap();
    wait_for(&rx, Duration::from_secs(10), |e| matches!(e, Event::Connected { .. }))
        .expect("连接失败");
    println!("  ✔ 连接");

    tx.send(Command::Halt).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::State { state: TargetState::Halted })
    })
    .expect("halt 失败");

    // 1. 成员链：g_motor.position.x（float）
    if let Some(node) = elf.resolve_member_chain("g_motor.position.x") {
        let v = read_bytes(&tx, node.address, 4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]));
        check(
            v.map(|v| v.abs() <= 100.0).unwrap_or(false),
            "成员链 g_motor.position.x",
            &format!("= {:?}（|值|≤100）", v),
        );
    } else {
        check(false, "成员链 g_motor.position.x", "解析失败");
    }

    // 2. 类私有成员：g_counter.ticks_（方法已调用过，应 >0）
    if let Some(node) = elf.resolve_member_chain("g_counter.ticks_") {
        let v = read_u32(&tx, node.address);
        check(
            v.map(|v| v > 0).unwrap_or(false),
            "类成员 g_counter.ticks_（方法调用生效）",
            &format!("= {:?}", v),
        );
    } else {
        check(false, "类成员 g_counter.ticks_", "解析失败");
    }

    // 3. vector 展开：_M_start/_M_finish
    let start_a = elf
        .resolve_member_chain("g_vec._M_impl._M_start")
        .map(|n| n.address);
    let finish_a = elf
        .resolve_member_chain("g_vec._M_impl._M_finish")
        .map(|n| n.address);
    if let (Some(sa), Some(fa)) = (start_a, finish_a) {
        if let (Some(s), Some(f)) = (read_u32(&tx, sa), read_u32(&tx, fa)) {
            let n = if f >= s { ((f - s) / 4) as usize } else { 0 };
            let elems: Vec<i32> = (0..n.min(32))
                .filter_map(|i| {
                    read_bytes(&tx, s as u64 + (i * 4) as u64, 4).map(|b| {
                        i32::from_le_bytes([b[0], b[1], b[2], b[3]])
                    })
                })
                .collect();
            check(
                n > 0 && !elems.is_empty(),
                "vector<int> g_vec 展开",
                &format!("size={n} 内容={:?}（截断到 32）", elems),
            );
        } else {
            check(false, "vector<int> g_vec 展开", "_M_start/_M_finish 读取失败");
        }
    } else {
        check(false, "vector<int> g_vec 展开", "_M_start/_M_finish 解析失败");
    }

    // 4. string 展开：_M_p/_M_string_length
    if let (Some(pa), Some(la)) = (
        elf.resolve_member_chain("g_str._M_dataplus._M_p").map(|n| n.address),
        elf.resolve_member_chain("g_str._M_string_length").map(|n| n.address),
    ) {
        let (ptr, len) = (read_u32(&tx, pa), read_u32(&tx, la));
        let text = match (ptr, len) {
            (Some(p), Some(l)) if p > 0 && l > 0 => {
                let take = l.min(24) as usize;
                read_bytes(&tx, p as u64, take)
                    .map(|b| b.iter().map(|&c| if (0x20..0x7f).contains(&c) { c as char } else { '.' }).collect::<String>())
                    .unwrap_or_default()
            }
            _ => String::new(),
        };
        check(
            !text.is_empty(),
            "string g_str 展开",
            &format!("len={len:?} 内容=\"{text}\""),
        );
    } else {
        check(false, "string g_str 展开", "_M_p/_M_string_length 解析失败");
    }

    // 5. list 展开：哨兵遍历
    if let Some(sa) = elf
        .resolve_member_chain("g_list._M_impl._M_node")
        .map(|n| n.address)
    {
        let mut cur = read_u32(&tx, sa).unwrap_or(0) as u64;
        let mut vals = Vec::new();
        let mut guard = 0;
        while cur != 0 && cur != sa && guard < 16 {
            if let Some(b) = read_bytes(&tx, cur + 8, 4) {
                vals.push(f32::from_le_bytes([b[0], b[1], b[2], b[3]]));
            }
            cur = read_u32(&tx, cur).unwrap_or(0) as u64;
            guard += 1;
        }
        check(
            !vals.is_empty(),
            "list<float> g_list 展开",
            &format!("size={} 内容={:?}", vals.len(), vals),
        );
    } else {
        check(false, "list<float> g_list 展开", "哨兵解析失败");
    }

    // 6. map：node_count
    if let Some(ca) = elf
        .resolve_member_chain("g_map._M_t._M_impl._M_node_count")
        .map(|n| n.address)
    {
        let n = read_u32(&tx, ca);
        check(
            n.map(|v| v > 0 && v <= 8).unwrap_or(false),
            "map g_map node_count",
            &format!("= {:?}（键 0..7）", n),
        );
    } else {
        check(false, "map g_map node_count", "解析失败");
    }

    // 7. 运行中示波：三路正弦
    tx.send(Command::Resume).unwrap();
    wait_for(&rx, Duration::from_secs(3), |e| {
        matches!(e, Event::State { state: TargetState::Running })
    })
    .expect("恢复运行失败");
    let scope: Vec<ScopeTarget> = ["sin_1hz", "sin_5hz", "sin_20hz"]
        .iter()
        .filter_map(|n| var(n).map(|v| ScopeTarget { addr: v.address, size: v.size }))
        .collect();
    tx.send(Command::UpdateScopeTargets(scope)).unwrap();
    tx.send(Command::SetScopeFreq(100.0)).unwrap();

    let deadline = Instant::now() + Duration::from_millis(1500);
    let mut minmax = [(f32::MAX, f32::MIN); 3]; // 按 key 排序：1hz/5hz/20hz 的地址顺序
    let mut counts = [0usize; 3];
    let keys: Vec<String> = ["sin_1hz", "sin_5hz", "sin_20hz"]
        .iter()
        .filter_map(|n| var(n).map(|v| format!("0x{:08x}", v.address)))
        .collect();
    while Instant::now() < deadline {
        if let Ok(Event::ScopeData { samples }) =
            rx.recv_timeout(Duration::from_millis(100))
        {
            for s in samples {
                for (k, bytes) in &s.values {
                    if let Some(i) = keys.iter().position(|key| key == k) {
                        if bytes.len() >= 4 {
                            let v = f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                            counts[i] += 1;
                            if v < minmax[i].0 {
                                minmax[i].0 = v;
                            }
                            if v > minmax[i].1 {
                                minmax[i].1 = v;
                            }
                        }
                    }
                }
            }
        }
    }
    // 1hz 幅值 ±100、5hz ±50、20hz ±20（1.5s 窗口内 1hz 至少走到 1.5 个相位）
    let limits = [100.0f32, 50.0, 20.0];
    for (i, name) in ["sin_1hz", "sin_5hz", "sin_20hz"].iter().enumerate() {
        check(
            counts[i] >= 30 && minmax[i].1 <= limits[i] + 1.0 && minmax[i].0 >= -limits[i] - 1.0,
            &format!("示波 {name} @100Hz"),
            &format!("{} 样本，幅值 [{:.1}, {:.1}]（限 ±{}）", counts[i], minmax[i].0, minmax[i].1, limits[i]),
        );
    }

    tx.send(Command::Disconnect).unwrap();
    handle.shutdown();
    println!("\n===== 结果：{pass} 通过，{fail} 失败 =====");
    std::process::exit(if fail == 0 { 0 } else { 1 });
}
