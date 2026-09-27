//! 客户端会话：读请求 → 分发 → 写响应；引擎事件泵转发到 writer。
//!
//! 所有权模型：引擎 MonitorHandle 由事件泵线程独占持有（事件泵消费
//! event_rx，Drop 时负责引擎收尾）；会话只持有命令通道的 Sender 副本。
//! 一个 agent 进程只服务一个客户端连接；连接断开后进程退出、引擎
//! Shutdown、probe 释放，重启由插件负责。

use crate::elfcache::ElfCache;
use crate::protocol::{event, parse_request, response_err, response_ok, Request};
use crossbeam_channel::{bounded, unbounded, Receiver, Sender};
use debug_core::probe;
use debug_core::{BackendKind, ConnectParams, TargetState};
use monitor::{bandwidth, Command, Event, MemTarget, MonitorHandle, ScopeTarget};
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

/// 同步读内存的超时与上限（对齐 src-tauri 的 monitor_read_mem）
const READ_MEM_TIMEOUT: Duration = Duration::from_secs(2);
const READ_MEM_MAX: usize = 64 * 1024;

pub struct SharedState {
    /// 最近已知目标状态
    pub target_state: Mutex<TargetState>,
    pub elf_cache: ElfCache,
}

pub struct Session {
    writer: Sender<String>,
    shared: Arc<SharedState>,
    /// 当前引擎的命令通道（None = 引擎未启动/已关闭）
    cmd_slot: Mutex<Option<Sender<Command>>>,
    watch_freq: Mutex<Option<f64>>,
    scope_freq: Mutex<Option<f64>>,
    watch_targets: Mutex<Option<Vec<MemTarget>>>,
    scope_targets: Mutex<Option<Vec<ScopeTarget>>>,
}

impl Session {
    pub fn new(writer: Sender<String>) -> Self {
        Self {
            writer,
            shared: Arc::new(SharedState {
                target_state: Mutex::new(TargetState::Disconnected),
                elf_cache: ElfCache::default(),
            }),
            cmd_slot: Mutex::new(None),
            watch_freq: Mutex::new(None),
            scope_freq: Mutex::new(None),
            watch_targets: Mutex::new(None),
            scope_targets: Mutex::new(None),
        }
    }

    /// connect：关闭旧引擎，重启新引擎并派泵线程（对齐 src-tauri monitor_start 行为）。
    fn engine_connect(&self, params: ConnectParams) {
        self.shutdown_engine();
        let handle = monitor::spawn_engine();
        let cmd_tx = handle.cmd_tx.clone();
        *self.cmd_slot.lock() = Some(cmd_tx.clone());
        let writer = self.writer.clone();
        let shared = self.shared.clone();
        std::thread::Builder::new()
            .name("agent-event-pump".into())
            .spawn(move || {
                pump_loop(handle, &writer, &shared);
            })
            .expect("spawn event pump");
        if let Some(freq) = *self.watch_freq.lock() {
            let _ = cmd_tx.send(Command::SetWatchFreq(freq));
        }
        if let Some(freq) = *self.scope_freq.lock() {
            let _ = cmd_tx.send(Command::SetScopeFreq(freq));
        }
        if let Some(targets) = self.watch_targets.lock().clone() {
            let _ = cmd_tx.send(Command::UpdateWatchTargets(targets));
        }
        if let Some(targets) = self.scope_targets.lock().clone() {
            let _ = cmd_tx.send(Command::UpdateScopeTargets(targets));
        }
        let _ = cmd_tx.send(Command::Connect(params));
    }

    pub fn shutdown_engine(&self) {
        if let Some(cmd_tx) = self.cmd_slot.lock().take() {
            let _ = cmd_tx.send(Command::Shutdown);
            // 引擎线程退出 → 事件通道关闭 → 泵线程随之退出并 Drop MonitorHandle
        }
    }

    pub fn run(&self, reader: BufReader<TcpStream>) {
        for line in reader.lines() {
            let Ok(line) = line else { break };
            eprintln!("[agent] recv: {}", line.trim().chars().take(80).collect::<String>());
            let line = line.trim().to_string();
            if line.is_empty() {
                continue;
            }
            let req = match parse_request(&line) {
                Ok(r) => r,
                Err(e) => {
                    let _ = self.writer.send(response_err(0, e));
                    continue;
                }
            };
            if let Err(e) = self.dispatch(&req) {
                // 同步失败（参数/前置条件）：回错误响应
                let _ = self.writer.send(response_err(req.id, e));
            }
        }
    }

    /// 同步方法分发；慢操作（elf_load）在内部子线程自行回响应。
    fn dispatch(&self, req: &Request) -> Result<(), String> {
        let id = req.id;
        let p = &req.params;
        match req.method.as_str() {
            "ping" => {
                self.send(response_ok(
                    id,
                    json!({"name": "embedded-clion-agent", "version": env!("CARGO_PKG_VERSION")}),
                ));
            }
            "list_probes" => {
                let probes = probe::list_probes();
                self.send(response_ok(id, serde_json::to_value(probes).unwrap_or(Value::Null)));
            }
            "list_targets" => {
                let filter = p.get("filter").and_then(Value::as_str).unwrap_or("");
                let targets = probe::list_targets(filter);
                self.send(response_ok(id, serde_json::to_value(targets).unwrap_or(Value::Null)));
            }
            "connect" => {
                let params = parse_connect_params(p)?;
                if params.kind == BackendKind::Openocd && params.attach_only {
                    check_openocd_attachable(params.tcl_port)?;
                }
                self.engine_connect(params);
                self.send(response_ok(id, Value::Null));
            }
            "disconnect" => {
                if let Some(cmd_tx) = self.cmd_slot.lock().as_ref() {
                    let _ = cmd_tx.send(Command::Disconnect);
                }
                *self.shared.target_state.lock() = TargetState::Disconnected;
                self.send(response_ok(id, Value::Null));
            }
            "status" => {
                let st = serde_json::to_value(*self.shared.target_state.lock())
                    .unwrap_or(Value::Null);
                self.send(response_ok(id, json!({ "state": st })));
            }
            "set_watch_targets" => {
                let targets: Vec<MemTarget> = serde_json::from_value(
                    p.get("targets").cloned().unwrap_or(Value::Array(vec![])),
                )
                .map_err(|e| format!("targets 解析失败: {e}"))?;
                *self.watch_targets.lock() = Some(targets.clone());
                let _ = self.send_cmd(Command::UpdateWatchTargets(targets));
                self.send(response_ok(id, Value::Null));
            }
            "set_scope_targets" => {
                let targets: Vec<ScopeTarget> = serde_json::from_value(
                    p.get("targets").cloned().unwrap_or(Value::Array(vec![])),
                )
                .map_err(|e| format!("targets 解析失败: {e}"))?;
                *self.scope_targets.lock() = Some(targets.clone());
                let _ = self.send_cmd(Command::UpdateScopeTargets(targets));
                self.send(response_ok(id, Value::Null));
            }
            "set_scope_freq" => {
                let freq = p.get("freq").and_then(Value::as_f64).ok_or("缺少 freq")?;
                *self.scope_freq.lock() = Some(freq);
                let _ = self.send_cmd(Command::SetScopeFreq(freq));
                self.send(response_ok(id, Value::Null));
            }
            "set_watch_freq" => {
                let freq = p.get("freq").and_then(Value::as_f64).ok_or("缺少 freq")?;
                *self.watch_freq.lock() = Some(freq);
                let _ = self.send_cmd(Command::SetWatchFreq(freq));
                self.send(response_ok(id, Value::Null));
            }
            "read_mem" => {
                let addr = p.get("addr").and_then(Value::as_u64).ok_or("缺少 addr")?;
                let size = p.get("size").and_then(Value::as_u64).ok_or("缺少 size")? as usize;
                if size == 0 || size > READ_MEM_MAX {
                    return Err(format!("size 超出范围 1..{READ_MEM_MAX}"));
                }
                self.read_mem_sync(id, addr, size)?;
            }
            "write_mem" => {
                let addr = p.get("addr").and_then(Value::as_u64).ok_or("缺少 addr")?;
                let data: Vec<u8> =
                    serde_json::from_value(p.get("data").cloned().ok_or("缺少 data")?)
                        .map_err(|e| format!("data 解析失败: {e}"))?;
                self.send_cmd(Command::WriteMem { addr, data })?;
                self.send(response_ok(id, Value::Null));
            }
            "check_bandwidth" => {
                let targets: Vec<(u64, u64)> = serde_json::from_value(
                    p.get("targets").cloned().ok_or("缺少 targets")?,
                )
                .map_err(|e| format!("targets 解析失败（需 [[addr,size],..]）: {e}"))?;
                let freq = p.get("freq").and_then(Value::as_f64).ok_or("缺少 freq")?;
                let (fits, bytes_per_second) = bandwidth::check_feasibility(&targets, freq);
                self.send(response_ok(
                    id,
                    json!({ "fits": fits, "bytesPerSecond": bytes_per_second }),
                ));
            }
            "elf_load" => {
                let path = p.get("path").and_then(Value::as_str).ok_or("缺少 path")?;
                self.elf_load_async(id, PathBuf::from(path));
            }
            "elf_resolve" => {
                let expr = p.get("expr").and_then(Value::as_str).ok_or("缺少 expr")?;
                let cache = &self.shared.elf_cache;
                let path = cache.loaded_path().ok_or("尚未加载 ELF，先调用 elf_load")?;
                let index = cache.get(&path)?;
                let node = index.resolve_member_chain(expr);
                self.send(response_ok(id, serde_json::to_value(node).unwrap_or(Value::Null)));
            }
            "elf_type_at_addr" => {
                let addr = p.get("addr").and_then(Value::as_u64).ok_or("缺少 addr")?;
                let cache = &self.shared.elf_cache;
                let path = cache.loaded_path().ok_or("尚未加载 ELF，先调用 elf_load")?;
                let index = cache.get(&path)?;
                let t = index.type_at_addr(addr).map(|(type_name, size, encoding)| {
                    json!({"typeName": type_name, "size": size, "encoding": encoding})
                });
                self.send(response_ok(id, serde_json::to_value(t).unwrap_or(Value::Null)));
            }
            other => {
                return Err(format!("未知方法: {other}"));
            }
        }
        Ok(())
    }

    fn send(&self, line: String) {
        let _ = self.writer.send(line);
    }

    fn send_cmd(&self, cmd: Command) -> Result<(), String> {
        let cmd_tx = self.cmd_slot.lock().clone();
        match cmd_tx {
            Some(tx) => {
                let _ = tx.send(cmd);
                Ok(())
            }
            None => Err("引擎未启动，先调用 connect".into()),
        }
    }

    fn read_mem_sync(&self, id: u64, addr: u64, size: usize) -> Result<(), String> {
        let cmd_tx = self.cmd_slot.lock().clone().ok_or("引擎未启动，先调用 connect")?;
        let (reply_tx, reply_rx) = bounded(1);
        let _ = cmd_tx.send(Command::ReadMemSync { addr, size, reply: reply_tx });
        match reply_rx.recv_timeout(READ_MEM_TIMEOUT) {
            Ok(Ok(bytes)) => {
                self.send(response_ok(id, serde_json::to_value(bytes).unwrap_or(Value::Null)));
                Ok(())
            }
            Ok(Err(e)) => Err(e),
            Err(_) => Err("读内存超时（引擎忙或未连接）".into()),
        }
    }

    /// ELF 解析较慢（数百毫秒），放子线程，完成后自行回响应。
    fn elf_load_async(&self, id: u64, path: PathBuf) {
        let writer = self.writer.clone();
        let shared = self.shared.clone();
        std::thread::Builder::new()
            .name("agent-elf-load".into())
            .spawn(move || {
                let resp = match shared.elf_cache.get(&path) {
                    Ok(index) => response_ok(
                        id,
                        json!({
                            "path": path.display().to_string(),
                            "variableCount": index.variables.len(),
                            "functionCount": index.functions.len(),
                            // 变量树全量下发（含成员），供插件建选择树
                            "variables": index.variables,
                        }),
                    ),
                    Err(e) => response_err(id, e),
                };
                let _ = writer.send(resp);
            })
            .expect("spawn elf load");
    }
}

/// 事件泵：消费引擎事件 → 更新状态缓存 → 转发到 writer。
/// 引擎线程退出（Shutdown/命令通道关闭）后自然结束；MonitorHandle 在此 Drop。
fn pump_loop(handle: MonitorHandle, writer: &Sender<String>, shared: &Arc<SharedState>) {
    let event_rx: &Receiver<Event> = &handle.event_rx;
    loop {
        match event_rx.recv_timeout(Duration::from_millis(100)) {
            Ok(ev) => {
                match &ev {
                    Event::Connected { .. } => {
                        *shared.target_state.lock() = TargetState::Running;
                    }
                    Event::State { state } => {
                        *shared.target_state.lock() = *state;
                    }
                    Event::Disconnected { .. } => {
                        *shared.target_state.lock() = TargetState::Disconnected;
                    }
                    _ => {}
                }
                let line = event("engine", serde_json::to_value(&ev).unwrap_or(Value::Null));
                if writer.send(line).is_err() {
                    return; // 客户端已断开
                }
            }
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => return, // 引擎已关
        }
    }
}

/// 解析 connect 参数（camelCase JSON）。
fn parse_connect_params(p: &Value) -> Result<ConnectParams, String> {
    let backend = p
        .get("backend")
        .and_then(Value::as_str)
        .ok_or("缺少 backend")?;
    let kind = match backend {
        "probe-rs" => BackendKind::ProbeRs,
        "openocd" => BackendKind::Openocd,
        "sim" => BackendKind::Sim,
        other => return Err(format!("未知 backend: {other}（可选 probe-rs / openocd / sim）")),
    };
    Ok(ConnectParams {
        kind,
        target: p.get("target").and_then(Value::as_str).map(str::to_string),
        cfg_file: p.get("cfgFile").and_then(Value::as_str).map(str::to_string),
        openocd_path: p.get("openocdPath").and_then(Value::as_str).map(str::to_string),
        scripts_dir: p.get("scriptsDir").and_then(Value::as_str).map(str::to_string),
        speed_hz: p.get("speedHz").and_then(Value::as_u64).unwrap_or(4_000_000) as u32,
        attach_only: p.get("attachOnly").and_then(Value::as_bool).unwrap_or(false),
        tcl_port: p.get("tclPort").and_then(Value::as_u64).unwrap_or(6666) as u16,
    })
}

/// attach-only 预检：OpenOCD Tcl RPC 端口是否已监听（避免引擎退化为 spawn 抢占 probe）。
fn check_openocd_attachable(tcl_port: u16) -> Result<(), String> {
    let addr = format!("127.0.0.1:{tcl_port}");
    let sa: std::net::SocketAddr = addr.parse().map_err(|_| format!("非法地址 {addr}"))?;
    match TcpStream::connect_timeout(&sa, Duration::from_millis(500)) {
        Ok(_) => Ok(()),
        Err(e) => {
            // 快速探测常用 GDB (3333) 与 Telnet (4444) 端口，判定 OpenOCD 是否已在运行但被 CLion 禁用了 Tcl 端口
            let gdb_addr: std::net::SocketAddr = "127.0.0.1:3333".parse().unwrap();
            let telnet_addr: std::net::SocketAddr = "127.0.0.1:4444".parse().unwrap();
            let gdb_up = TcpStream::connect_timeout(&gdb_addr, Duration::from_millis(150)).is_ok();
            let telnet_up = TcpStream::connect_timeout(&telnet_addr, Duration::from_millis(150)).is_ok();
            if gdb_up || telnet_up {
                Err(format!(
                    "OpenOCD 正在运行（GDB 3333 / Telnet 4444 已就绪），但 Tcl RPC 端口 {addr} 未开放（CLion 默认启动参数传入了 'tcl_port disabled'）。\
                     请在您的 OpenOCD 板级配置文件（.cfg）末尾添加一行 'tcl_port 6666' 并重新启动调试。"
                ))
            } else {
                Err(format!(
                    "OpenOCD Tcl RPC 端口 {addr} 不可达（{e}）：请先在 CLion 运行 OpenOCD 下载并运行配置，\
                     或在插件设置中关闭 attach-only。"
                ))
            }
        }
    }
}

/// 服务一个客户端直到断开；返回后由调用方收尾。
pub fn serve_one(listener: &TcpListener) {
    let (stream, _peer) = listener.accept().expect("accept first client");
    let _ = stream.set_nodelay(true);
    let reader = BufReader::new(match stream.try_clone() {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[agent] try_clone 失败: {e}");
            return;
        }
    });
    let (writer_tx, writer_rx) = unbounded::<String>();
    let session = Arc::new(Session::new(writer_tx.clone()));

    // writer 线程：独占 socket 写端
    let wstream = stream;
    std::thread::Builder::new()
        .name("agent-writer".into())
        .spawn(move || {
            let mut out = std::io::BufWriter::new(wstream);
            for line in writer_rx {
                if out.write_all(line.as_bytes()).is_err() || out.flush().is_err() {
                    break;
                }
            }
        })
        .expect("spawn writer");

    eprintln!("[agent] 客户端已接入");
    session.run(reader);
    eprintln!("[agent] 客户端断开，关闭引擎");
    session.shutdown_engine();
}
