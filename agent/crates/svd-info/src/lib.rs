//! SVD 解析（svd-parser expand 特性：自动展开 derivedFrom 与 dim 数组），
//! 输出前端展示树（对照原版 core/svd.py 的 SvdNode/SvdField）。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SvdFieldView {
    pub name: String,
    pub bit_offset: u32,
    pub bit_width: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub access: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// 枚举值 → 名称
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub enums: Vec<(u64, String)>,
}

impl SvdFieldView {
    /// "[n]" 或 "[msb:lsb]"
    pub fn bits_text(&self) -> String {
        if self.bit_width == 1 {
            format!("[{}]", self.bit_offset)
        } else {
            format!("[{}:{}]", self.bit_offset + self.bit_width - 1, self.bit_offset)
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SvdNodeView {
    /// "peripheral" | "cluster" | "register"
    pub kind: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub absolute_address: u64,
    /// 字节大小（寄存器一般为 4）
    pub size: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub access: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reset_value: Option<u64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub fields: Vec<SvdFieldView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub children: Vec<SvdNodeView>,
    /// 树路径，如 "USART1/CR1"
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SvdDeviceView {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub peripherals: Vec<SvdNodeView>,
}

#[derive(Debug, thiserror::Error)]
pub enum SvdError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("parse: {0}")]
    Parse(String),
}

pub fn parse_file(path: &std::path::Path) -> Result<SvdDeviceView, SvdError> {
    let xml = std::fs::read_to_string(path)?;
    parse_str(&xml)
}

pub fn parse_str(xml: &str) -> Result<SvdDeviceView, SvdError> {
    let device = svd_parser::parse(xml).map_err(|e| SvdError::Parse(e.to_string()))?;
    // 展开 derivedFrom / dim 数组
    let device = svd_parser::expand(&device).map_err(|e| SvdError::Parse(e.to_string()))?;

    let default_reg_size_bits = device.default_register_properties.size.unwrap_or(32);

    let mut peripherals = Vec::new();
    for p in &device.peripherals {
        let base = p.base_address;
        let mut children = Vec::new();
        if let Some(regs) = &p.registers {
            for rc in regs {
                collect_register_cluster(
                    rc,
                    base,
                    &p.name,
                    default_reg_size_bits,
                    &mut children,
                );
            }
        }
        children.sort_by_key(|c| c.absolute_address);
        peripherals.push(SvdNodeView {
            kind: "peripheral".into(),
            name: p.name.clone(),
            description: p.description.clone(),
            absolute_address: base,
            size: 0,
            access: None,
            reset_value: None,
            fields: Vec::new(),
            children,
            path: p.name.clone(),
        });
    }
    peripherals.sort_by(|a, b| a.name.cmp(&b.name));

    Ok(SvdDeviceView {
        name: device.name.clone(),
        version: Some(device.version.clone()),
        description: Some(device.description.clone()),
        peripherals,
    })
}

fn collect_register_cluster(
    rc: &svd_rs::RegisterCluster,
    base: u64,
    parent_path: &str,
    default_size_bits: u32,
    out: &mut Vec<SvdNodeView>,
) {
    match rc {
        svd_rs::RegisterCluster::Register(reg) => {
            let addr = base + reg.address_offset as u64;
            let size_bits = reg.properties.size.unwrap_or(default_size_bits);
            let path = format!("{parent_path}/{}", reg.name);
            let fields = reg
                .fields
                .as_deref()
                .unwrap_or(&[])
                .iter()
                .map(|f| {
                    let mut enums = Vec::new();
                    for evs in &f.enumerated_values {
                        for ev in &evs.values {
                            if let Some(v) = ev.value {
                                enums.push((v, ev.name.clone()));
                            }
                        }
                    }
                    SvdFieldView {
                        name: f.name.clone(),
                        bit_offset: f.bit_range.offset,
                        bit_width: f.bit_range.width,
                        access: f.access.map(access_text),
                        description: f.description.clone(),
                        enums,
                    }
                })
                .collect();
            out.push(SvdNodeView {
                kind: "register".into(),
                name: reg.name.clone(),
                description: reg.description.clone(),
                absolute_address: addr,
                size: (size_bits / 8).max(1),
                access: reg.properties.access.map(access_text),
                reset_value: reg.properties.reset_value,
                fields,
                children: Vec::new(),
                path,
            });
        }
        svd_rs::RegisterCluster::Cluster(cluster) => {
            let cluster_base = base + cluster.address_offset as u64;
            let path = format!("{parent_path}/{}", cluster.name);
            let mut children = Vec::new();
            for child in &cluster.children {
                collect_register_cluster(child, cluster_base, &path, default_size_bits, &mut children);
            }
            children.sort_by_key(|c| c.absolute_address);
            out.push(SvdNodeView {
                kind: "cluster".into(),
                name: cluster.name.clone(),
                description: cluster.description.clone(),
                absolute_address: cluster_base,
                size: 0,
                access: None,
                reset_value: None,
                fields: Vec::new(),
                children,
                path,
            });
        }
    }
}

fn access_text(a: svd_rs::Access) -> String {
    match a {
        svd_rs::Access::ReadOnly => "RO".into(),
        svd_rs::Access::WriteOnly => "WO".into(),
        svd_rs::Access::ReadWrite => "RW".into(),
        svd_rs::Access::WriteOnce => "W1".into(),
        svd_rs::Access::ReadWriteOnce => "RW1".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<device schemaVersion="1.1" xmlns:xs="http://www.w3.org/2001/XMLSchema-instance">
  <name>TESTDEV</name>
  <version>1.0</version>
  <description>Test</description>
  <addressUnitBits>8</addressUnitBits>
  <width>32</width>
  <size>32</size>
  <peripherals>
    <peripheral>
      <name>GPIOA</name>
      <baseAddress>0x48000000</baseAddress>
      <registers>
        <register>
          <name>MODER</name>
          <addressOffset>0x0</addressOffset>
          <resetValue>0xABFFFFFF</resetValue>
          <fields>
            <field>
              <name>MODER0</name>
              <bitOffset>0</bitOffset>
              <bitWidth>2</bitWidth>
              <enumeratedValues>
                <enumeratedValue><name>Input</name><value>0</value></enumeratedValue>
                <enumeratedValue><name>Output</name><value>1</value></enumeratedValue>
              </enumeratedValues>
            </field>
          </fields>
        </register>
        <register>
          <dim>2</dim>
          <dimIncrement>4</dimIncrement>
          <dimIndex>0,1</dimIndex>
          <name>AFR%s</name>
          <addressOffset>0x20</addressOffset>
        </register>
      </registers>
    </peripheral>
    <peripheral derivedFrom="GPIOA">
      <name>GPIOB</name>
      <baseAddress>0x48000400</baseAddress>
    </peripheral>
  </peripherals>
</device>"#;

    #[test]
    fn parse_expand_derive_and_dim() {
        let dev = parse_str(SAMPLE).expect("parse");
        assert_eq!(dev.name, "TESTDEV");
        assert_eq!(dev.peripherals.len(), 2);

        let gpioa = dev.peripherals.iter().find(|p| p.name == "GPIOA").unwrap();
        // MODER + AFR0 + AFR1（dim 展开）
        assert_eq!(gpioa.children.len(), 3);
        let moder = &gpioa.children[0];
        assert_eq!(moder.name, "MODER");
        assert_eq!(moder.absolute_address, 0x4800_0000);
        assert_eq!(moder.size, 4);
        assert_eq!(moder.reset_value, Some(0xABFF_FFFF));
        assert_eq!(moder.fields.len(), 1);
        assert_eq!(moder.fields[0].bits_text(), "[1:0]");
        assert_eq!(moder.fields[0].enums.len(), 2);

        let afr0 = gpioa.children.iter().find(|r| r.name == "AFR0").unwrap();
        assert_eq!(afr0.absolute_address, 0x4800_0020);
        let afr1 = gpioa.children.iter().find(|r| r.name == "AFR1").unwrap();
        assert_eq!(afr1.absolute_address, 0x4800_0024);

        // derivedFrom 展开
        let gpiob = dev.peripherals.iter().find(|p| p.name == "GPIOB").unwrap();
        assert_eq!(gpiob.absolute_address, 0x4800_0400);
        assert_eq!(gpiob.children.len(), 3);
        let b_moder = &gpiob.children[0];
        assert_eq!(b_moder.absolute_address, 0x4800_0400);
    }
}
