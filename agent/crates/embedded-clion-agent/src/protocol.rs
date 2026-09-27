//! JSON 行协议封包：请求/响应/事件。
//!
//! 客户端 → agent：`{"id":1,"method":"connect","params":{...}}\n`
//! agent → 客户端：`{"id":1,"ok":true,"result":...}\n` / `{"id":1,"ok":false,"error":"..."}\n`
//! agent → 客户端（事件）：`{"event":"engine","data":{...引擎 Event 原样...}}\n`

use serde_json::{Map, Value};

pub fn response_ok(id: u64, result: Value) -> String {
    let mut m = Map::new();
    m.insert("id".into(), Value::from(id));
    m.insert("ok".into(), Value::Bool(true));
    m.insert("result".into(), result);
    let mut s = Value::Object(m).to_string();
    s.push('\n');
    s
}

pub fn response_err(id: u64, error: impl Into<String>) -> String {
    let mut m = Map::new();
    m.insert("id".into(), Value::from(id));
    m.insert("ok".into(), Value::Bool(false));
    m.insert("error".into(), Value::String(error.into()));
    let mut s = Value::Object(m).to_string();
    s.push('\n');
    s
}

pub fn event(name: &str, data: Value) -> String {
    let mut m = Map::new();
    m.insert("event".into(), Value::String(name.into()));
    m.insert("data".into(), data);
    let mut s = Value::Object(m).to_string();
    s.push('\n');
    s
}

pub struct Request {
    pub id: u64,
    pub method: String,
    pub params: Value,
}

/// 解析一行请求；非法行返回 None（调用方回一条错误由这里统一处理不了，返回 Err 描述）。
pub fn parse_request(line: &str) -> Result<Request, String> {
    let v: Value = serde_json::from_str(line).map_err(|e| format!("JSON 解析失败: {e}"))?;
    let obj = v.as_object().ok_or("请求必须是 JSON 对象")?;
    let id = obj
        .get("id")
        .and_then(Value::as_u64)
        .ok_or("缺少数字字段 id")?;
    let method = obj
        .get("method")
        .and_then(Value::as_str)
        .ok_or("缺少字符串字段 method")?
        .to_string();
    let params = obj.get("params").cloned().unwrap_or(Value::Null);
    Ok(Request { id, method, params })
}
