//! 诊断：probe-rs 探针枚举 + 逐个尝试打开（复合设备可能存在打不开的实例）。
fn main() {
    println!("枚举探针…");
    let lister = probe_rs::probe::list::Lister::new();
    let probes = lister.list_all();
    println!("找到 {} 个探针", probes.len());
    for (i, p) in probes.iter().enumerate() {
        eprintln!(
            "  - [{}] {} vid={:#06x} pid={:#06x} type={:?}",
            i,
            p.identifier,
            p.vendor_id,
            p.product_id,
            p.probe_type()
        );
        match p.open() {
            Ok(mut probe) => {
                let _ = probe.detach();
                eprintln!("    可打开 ✔");
            }
            Err(e) => eprintln!("    打开失败 ✘: {e}"),
        }
    }
}
