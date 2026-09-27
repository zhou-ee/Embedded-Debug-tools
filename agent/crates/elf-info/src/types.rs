use serde::{Deserialize, Serialize};

/// 值编码（前端按此解码字节）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum ValueEncoding {
    #[default]
    Unsigned,
    Signed,
    Float,
    Bool,
    /// 指针（32 位）
    Pointer,
    /// 复合类型（结构体/联合体），无标量值
    Composite,
}

/// 变量 / 成员类型树节点（对照原版 SymbolInfo）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SymbolNode {
    pub name: String,
    pub type_name: String,
    /// 绝对地址（成员为根地址+offset）
    pub address: u64,
    pub size: u32,
    pub encoding: ValueEncoding,
    #[serde(default)]
    pub is_pointer: bool,
    /// 指针指向类型的大小（展开用），0 未知
    #[serde(default)]
    pub pointee_size: u32,
    #[serde(default)]
    pub pointee_type: String,
    /// 指针指向类型的标量编码（STL/数组展开时解码用）
    #[serde(default)]
    pub pointee_encoding: ValueEncoding,
    /// 枚举值 → 名称
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub enum_values: Vec<(i64, String)>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub members: Vec<SymbolNode>,
    /// 声明位置
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub decl_file: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub decl_line: Option<u32>,
}

/// 函数符号。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FunctionInfo {
    pub name: String,
    pub raw_name: String,
    pub start: u64,
    pub end: u64,
    #[serde(default)]
    pub file: Option<String>,
    #[serde(default)]
    pub line: Option<u32>,
}
