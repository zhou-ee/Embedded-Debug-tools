//! probe-rs 探针与目标 registry 查询（替代原版 pyocd ListGenerator 目标选择）。

use probe_rs::probe::list::Lister;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeInfo {
    pub name: String,
    pub serial: Option<String>,
    pub vid: u16,
    pub pid: u16,
    pub probe_type: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TargetInfo {
    pub name: String,
    pub vendor: String,
    pub family: String,
    pub cores: usize,
}

/// 枚举已连接调试探针。
pub fn list_probes() -> Vec<ProbeInfo> {
    let lister = Lister::new();
    lister
        .list_all()
        .into_iter()
        .map(|p| ProbeInfo {
            name: p.identifier.clone(),
            serial: p.serial_number.clone(),
            vid: p.vendor_id,
            pid: p.product_id,
            probe_type: format!("{:?}", p.probe_type()),
        })
        .collect()
}

/// 依序尝试打开列表中的探针，返回第一个可用实例（探针 + 标识）。
/// 复合设备（如实测的 ATK-HS-V3）会枚举出多个实例且首个可能打不开，
/// 盲选 probes[0] 会导致这类探针完全不可用。
pub fn open_first_available() -> Result<(probe_rs::probe::Probe, String), String> {
    let lister = Lister::new();
    let probes = lister.list_all();
    if probes.is_empty() {
        return Err("未检测到调试探针（CMSIS-DAP / ST-Link / J-Link）".into());
    }
    let mut errors = Vec::new();
    for p in &probes {
        match p.open() {
            Ok(probe) => return Ok((probe, p.identifier.clone())),
            Err(e) => errors.push(format!("{}: {e}", p.identifier)),
        }
    }
    Err(format!(
        "检测到 {} 个探针但全部无法打开: {}",
        probes.len(),
        errors.join("; ")
    ))
}

/// 列出 registry 内目标芯片（filter 为不区分大小写子串；空返回全部）。
pub fn list_targets(filter: &str) -> Vec<TargetInfo> {
    let needle = filter.to_lowercase();
    let registry = probe_rs::config::Registry::from_builtin_families();
    let mut out = Vec::new();
    for family in registry.families() {
        let vendor = family
            .manufacturer
            .as_ref()
            .map(|m| m.to_string())
            .unwrap_or_default();
        for variant in &family.variants {
            if needle.is_empty()
                || variant.name.to_lowercase().contains(&needle)
                || family.name.to_lowercase().contains(&needle)
            {
                out.push(TargetInfo {
                    name: variant.name.clone(),
                    vendor: vendor.clone(),
                    family: family.name.clone(),
                    cores: variant.cores.len(),
                });
            }
        }
    }
    out
}
