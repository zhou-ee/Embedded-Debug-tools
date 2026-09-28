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
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

/// 同步读内存的超时与上限（对齐 src-tauri 的 monitor_read_mem）
const READ_MEM_TIMEOUT: Duration = Duration::from_secs(2);
const READ_MEM_MAX: usize = 64 * 1024;
/// 同步写内存超时：正常 <10ms；给满 2s 覆盖引擎忙于示波突发的窗口
const WRITE_MEM_TIMEOUT: Duration = Duration::from_secs(2);
/// 单行请求长度上限：超限视为协议错误并断开。elf_load 的变量树下发由
/// agent → 插件方向承载，上行请求（targets 列表等）远小于此值
const MAX_LINE_LEN: usize = 4 * 1024 * 1024;
/// 引擎收尾（清 DWT/断点、resume、断开 probe）的等待上限
const ENGINE_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);
/// 僵尸泵线程的再等待上限（engine_connect 拉起新引擎前）。3s + 7s = 10s，
/// 仍在插件的 connect 请求超时（12s）之内
const ZOMBIE_REAP_TIMEOUT: Duration = Duration::from_secs(7);

pub struct SharedState {
    /// 最近已知目标状态
    pub target_state: Mutex<TargetState>,
    pub elf_cache: ElfCache,
}

pub struct Session {
    writer: Sender<String>,
    shared: Arc<SharedState>,
    /// 连接令牌（main 生成、经就绪行交给插件，hello 握手校验）
    token: String,
    /// 当前引擎的命令通道（None = 引擎未启动/已关闭）
    cmd_slot: Mutex<Option<Sender<Command>>>,
    /// 引擎事件泵线程句柄：shutdown 时 join，保证引擎硬件收尾执行完再退进程
    pump_join: Mutex<Option<std::thread::JoinHandle<()>>>,
    watch_freq: Mutex<Option<f64>>,
    scope_freq: Mutex<Option<f64>>,
    watch_targets: Mutex<Option<Vec<MemTarget>>>,
    scope_targets: Mutex<Option<Vec<ScopeTarget>>>,
    /// 收尾超时未能退出的旧泵线程句柄：engine_connect 拉起新引擎前再等，
    /// 此前超时即 detach 后照常 spawn，新旧引擎并存抢占同一 USB probe，
    /// 且旧引擎"清 DWT/断点并 resume"的收尾被整段丢弃
    zombie_pumps: Mutex<Vec<std::thread::JoinHandle<()>>>,
}

impl Session {
    pub fn new(writer: Sender<String>, token: String) -> Self {
        Self {
            writer,
            shared: Arc::new(SharedState {
                target_state: Mutex::new(TargetState::Disconnected),
                elf_cache: ElfCache::default(),
            }),
            token,
            cmd_slot: Mutex::new(None),
            pump_join: Mutex::new(None),
            watch_freq: Mutex::new(None),
            scope_freq: Mutex::new(None),
            watch_targets: Mutex::new(None),
            scope_targets: Mutex::new(None),
            zombie_pumps: Mutex::new(Vec::new()),
        }
    }

    /// connect：关闭旧引擎，重启新引擎并派泵线程（对齐 src-tauri monitor_start 行为）。
    /// Err = 旧引擎尚未退出（被慢操作阻塞）——此时新引擎必然抢不到 probe，
    /// 明确报错由插件侧监督重连稍后重试，绝不带病双开。
    fn engine_connect(&self, params: ConnectParams) -> Result<(), String> {
        // 先等旧引擎完全收尾（shutdown_engine 内 join 泵线程），避免新旧引擎
        // 短暂并存抢占同一 USB probe
        self.shutdown_engine();
        self.reap_zombie_pumps()?;
        let handle = monitor::spawn_engine();
        let cmd_tx = handle.cmd_tx.clone();
        *self.cmd_slot.lock() = Some(cmd_tx.clone());
        let writer = self.writer.clone();
        let shared = self.shared.clone();
        let pump = std::thread::Builder::new()
            .name("agent-event-pump".into())
            .spawn(move || {
                pump_loop(handle, &writer, &shared);
            })
            .expect("spawn event pump");
        *self.pump_join.lock() = Some(pump);
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
        Ok(())
    }

    /// 等待僵尸泵线程退出；宽限期内仍活着则报错（本次 connect 拒绝执行）。
    fn reap_zombie_pumps(&self) -> Result<(), String> {
        let mut zombies = self.zombie_pumps.lock();
        let deadline = std::time::Instant::now() + ZOMBIE_REAP_TIMEOUT;
        while !zombies.is_empty() {
            // 已退出的就地 join 收割（引擎 panic 已被 catch_unwind，join 不会炸）；
            // JoinHandle::join 按值消费，用 partition 而非 retain
            let (finished, running): (Vec<_>, Vec<_>) = zombies.drain(..).partition(|h| h.is_finished());
            for h in finished {
                let _ = h.join();
            }
            *zombies = running;
            if zombies.is_empty() {
                break;
            }
            if std::time::Instant::now() >= deadline {
                return Err(
                    "旧监视引擎尚未退出（正被阻塞操作占用，探针未释放），本次连接已取消；请稍后重试"
                        .to_string(),
                );
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        Ok(())
    }

    pub fn shutdown_engine(&self) {
        if let Some(cmd_tx) = self.cmd_slot.lock().take() {
            let _ = cmd_tx.send(Command::Shutdown);
        }
        // 关键：等泵线程退出。泵线程 Drop MonitorHandle 时会 join 引擎线程，
        // 引擎 run() 末尾才会清 DWT 比较器/断点并 resume 目标——若不等它，
        // 进程立即退出会把清理路径整段杀掉，目标板被遗留断点/停机态污染
        let join = self.pump_join.lock().take();
        if let Some(handle) = join {
            let deadline = std::time::Instant::now() + ENGINE_SHUTDOWN_TIMEOUT;
            while !handle.is_finished() && std::time::Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(10));
            }
            if handle.is_finished() {
                let _ = handle.join();
            } else {
                // 超时不丢弃句柄：挂入僵尸表，engine_connect 拉起新引擎前再等一程，
                // 仍不退出则拒绝新连接（探针还在旧引擎手里，新引擎开 probe 必失败）
                eprintln!(
                    "[agent] 引擎收尾等待超时（{:?}），转入僵尸表继续等待（可能遗留 DWT/断点配置）",
                    ENGINE_SHUTDOWN_TIMEOUT
                );
                self.zombie_pumps.lock().push(handle);
            }
        }
    }

    /// 服务一个会话直到断开；返回值 = 握手是否成功（false = 未授权/误连，
    /// 调用方应回到 accept 继续等待真正的客户端而不是退出进程）。
    pub fn run(&self, mut reader: BufReader<TcpStream>) -> bool {
        // 握手：第一条消息必须是携带正确 token 的 hello（防本机其它进程扫端口操控硬件）
        let mut first = String::new();
        match read_line_limited(&mut reader, &mut first) {
            Ok(0) => return false,
            Ok(_) => {}
            Err(e) => {
                eprintln!("[agent] 握手读取失败: {e}");
                return false;
            }
        }
        let parsed = parse_request(first.trim()).ok();
        let hello_ok = parsed.as_ref().and_then(|req| {
            if req.method != "hello" {
                return None;
            }
            req.params.get("token").and_then(Value::as_str).map(|t| t == self.token)
        }) == Some(true);
        if !hello_ok {
            eprintln!("[agent] 握手失败（缺少 hello 或 token 不匹配），断开连接");
            // 回显请求 id，让调用方能把错误关联回 hello
            let id = parsed.as_ref().map(|r| r.id).unwrap_or(0);
            let _ = self
                .writer
                .send(response_err(id, "握手失败：第一条消息必须是携带正确 token 的 hello（插件与 agent 版本需一致）"));
            return false;
        }
        // hello 是请求：回标准响应（含协议版本），供调用方同步确认握手成功
        let _ = self.writer.send(response_ok(
            parsed.as_ref().map(|r| r.id).unwrap_or(0),
            json!({"proto": crate::PROTOCOL_VERSION, "name": "embedded-clion-agent", "version": env!("CARGO_PKG_VERSION")}),
        ));

        loop {
            let mut line = String::new();
            match read_line_limited(&mut reader, &mut line) {
                Ok(0) => break,
                Ok(_) => {}
                Err(e) => {
                    eprintln!("[agent] 读取失败（{e}），断开连接");
                    break;
                }
            }
            eprintln!("[agent] recv: {}", line.trim().chars().take(80).collect::<String>());
            let line = line.trim().to_string();
            if line.is_empty() {
                continue;
            }
            let req = match parse_request(&line) {
                Ok(r) => r,
                Err(e) => {
                    // 尽力提取 id，让插件能把错误关联回请求
                    let id = serde_json::from_str::<Value>(&line)
                        .ok()
                        .and_then(|v| v.get("id").and_then(Value::as_u64))
                        .unwrap_or(0);
                    let _ = self.writer.send(response_err(id, e));
                    continue;
                }
            };
            if let Err(e) = self.dispatch(&req) {
                // 同步失败（参数/前置条件）：回错误响应
                let _ = self.writer.send(response_err(req.id, e));
            }
        }
        true
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
                self.engine_connect(params)?;
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
                for t in &targets {
                    validate_mem_range(t.addr, t.size as u64)?;
                }
                *self.watch_targets.lock() = Some(targets.clone());
                let _ = self.send_cmd(Command::UpdateWatchTargets(targets));
                self.send(response_ok(id, Value::Null));
            }
            "set_scope_targets" => {
                let targets: Vec<ScopeTarget> = serde_json::from_value(
                    p.get("targets").cloned().unwrap_or(Value::Array(vec![])),
                )
                .map_err(|e| format!("targets 解析失败: {e}"))?;
                for t in &targets {
                    validate_mem_range(t.addr, t.size as u64)?;
                }
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
                validate_mem_range(addr, size as u64)?;
                self.read_mem_sync(id, addr, size)?;
            }
            "write_mem" => {
                let addr = p.get("addr").and_then(Value::as_u64).ok_or("缺少 addr")?;
                let data: Vec<u8> =
                    serde_json::from_value(p.get("data").cloned().ok_or("缺少 data")?)
                        .map_err(|e| format!("data 解析失败: {e}"))?;
                if data.len() > READ_MEM_MAX {
                    return Err(format!("data 超出上限 {READ_MEM_MAX} 字节"));
                }
                validate_mem_range(addr, data.len() as u64)?;
                // 请求-应答语义：写失败（探针未连接/地址不可写）必须回错误——
                // 此前发完命令立即回 ok，写入结果在引擎侧被丢弃，插件端误报成功
                let cmd_tx = self
                    .cmd_slot
                    .lock()
                    .clone()
                    .ok_or("引擎未启动，先调用 connect")?;
                let (reply_tx, reply_rx) = bounded(1);
                let _ = cmd_tx.send(Command::WriteMemSync {
                    addr,
                    data,
                    reply: reply_tx,
                });
                match reply_rx.recv_timeout(WRITE_MEM_TIMEOUT) {
                    Ok(Ok(())) => self.send(response_ok(id, Value::Null)),
                    Ok(Err(e)) => return Err(e),
                    Err(_) => return Err("写内存超时（引擎忙或未连接）".to_string()),
                }
            }
            "check_bandwidth" => {
                let targets: Vec<(u64, u64)> = serde_json::from_value(
                    p.get("targets").cloned().ok_or("缺少 targets")?,
                )
                .map_err(|e| format!("targets 解析失败（需 [[addr,size],..]）: {e}"))?;
                for (addr, size) in &targets {
                    validate_mem_range(*addr, *size)?;
                }
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
                let expr = p
                    .get("expr")
                    .and_then(Value::as_str)
                    .ok_or("缺少 expr")?
                    .to_string();
                let path = self
                    .shared
                    .elf_cache
                    .loaded_path()
                    .ok_or("尚未加载 ELF，先调用 elf_load")?;
                // 缓存未命中时 ElfIndex::load 需数百毫秒，异步执行避免读循环停摆
                self.elf_query_async(id, path, move |index| {
                    let node = index.resolve_member_chain(&expr);
                    serde_json::to_value(node).map_err(|e| format!("序列化失败: {e}"))
                });
            }
            "elf_type_at_addr" => {
                let addr = p.get("addr").and_then(Value::as_u64).ok_or("缺少 addr")?;
                let path = self
                    .shared
                    .elf_cache
                    .loaded_path()
                    .ok_or("尚未加载 ELF，先调用 elf_load")?;
                self.elf_query_async(id, path, move |index| {
                    let t = index.type_at_addr(addr).map(|(type_name, size, encoding)| {
                        json!({"typeName": type_name, "size": size, "encoding": encoding})
                    });
                    serde_json::to_value(t).map_err(|e| format!("序列化失败: {e}"))
                });
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

    /// ELF 查询（elf_resolve / elf_type_at_addr）异步化：缓存未命中时
    /// ElfIndex::load 需数百毫秒，此前在读循环线程同步执行，期间所有请求
    /// （含 ping / read_mem）无响应，客户端超时误判 agent 死亡。
    fn elf_query_async<F>(&self, id: u64, path: PathBuf, f: F)
    where
        F: FnOnce(&elf_info::ElfIndex) -> Result<Value, String> + Send + 'static,
    {
        let writer = self.writer.clone();
        let shared = self.shared.clone();
        let spawned = std::thread::Builder::new()
            .name("agent-elf-query".into())
            .spawn(move || {
                let resp = match shared.elf_cache.get(&path) {
                    Ok(index) => match f(&index) {
                        Ok(v) => response_ok(id, v),
                        Err(e) => response_err(id, e),
                    },
                    Err(e) => response_err(id, e),
                };
                let _ = writer.send(resp);
            });
        if spawned.is_err() {
            self.send(response_err(id, "elf 查询线程创建失败"));
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
        // 静默截断会把 70000kHz 之类参数悄悄变成另一个值，超范围直接报错
        speed_hz: u32::try_from(p.get("speedHz").and_then(Value::as_u64).unwrap_or(4_000_000))
            .map_err(|_| "speedHz 超出 u32 范围")?,
        attach_only: p.get("attachOnly").and_then(Value::as_bool).unwrap_or(false),
        tcl_port: u16::try_from(p.get("tclPort").and_then(Value::as_u64).unwrap_or(6666))
            .map_err(|_| "tclPort 超出 u16 范围")?,
        probe_serial: p.get("probeSerial").and_then(Value::as_str).map(str::to_string),
    })
}

/// 所有来自 JSON 的 addr/size 统一在协议入口校验：Cortex-M 为 32 位地址空间，
/// 超范围请求在下游无 checked 算术处会溢出（debug panic 杀引擎线程 / release
/// 回绕产生巨型读块导致巨量分配 abort）
fn validate_mem_range(addr: u64, size: u64) -> Result<(), String> {
    if size == 0 {
        return Err("size 必须为正".into());
    }
    match addr.checked_add(size) {
        Some(end) if end <= 0x1_0000_0000 => Ok(()),
        _ => Err(format!(
            "地址范围 0x{addr:x}+0x{size:x} 超出 32 位地址空间"
        )),
    }
}

/// 连接令牌：RandomState 每实例带 OS 熵种子，双 hasher 拼 128 位，
/// 足以抵御本机其它用户的进程对临时端口的盲扫（无加密需求）
pub fn generate_token() -> String {
    use std::hash::{BuildHasher, Hasher};
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut h1 = std::collections::hash_map::RandomState::new().build_hasher();
    let mut h2 = std::collections::hash_map::RandomState::new().build_hasher();
    h1.write_u64(nanos as u64);
    h1.write_u64((nanos >> 64) as u64);
    h1.write_u64(std::process::id() as u64);
    h2.write_u64((nanos as u64).rotate_left(17));
    h2.write_u64((std::process::id() as u64).rotate_left(13));
    format!("{:016x}{:016x}", h1.finish(), h2.finish())
}

/// 读一行（\n 结尾）。BufRead::lines()/read_line 会为超长行无上限扩容缓冲，
/// 这里用 take() 封顶，超限返回错误由调用方断开连接。
fn read_line_limited(reader: &mut impl BufRead, out: &mut String) -> std::io::Result<usize> {
    out.clear();
    let mut limited = reader.take((MAX_LINE_LEN + 1) as u64);
    let n = limited.read_line(out)?;
    if out.len() > MAX_LINE_LEN {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "line too long",
        ));
    }
    Ok(n)
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

/// 服务一个客户端直到断开；成功会话结束后由调用方收尾退出。
/// 握手失败的连接（未授权扫描/误连）回到 accept 继续等待真正的客户端
/// （此时插件的连接已在 backlog 排队，失败会话结束后立刻能被接上），
/// 不再直接退出让插件拿到"已死"的 agent；accept 自身失败重试至多 10s。
pub fn serve_one(listener: &TcpListener, token: String) {
    let io_retry_deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        let (stream, _peer) = match listener.accept() {
            Ok(pair) => pair,
            Err(e) => {
                eprintln!("[agent] accept 失败（{e}），重试中…");
                if std::time::Instant::now() >= io_retry_deadline {
                    return;
                }
                std::thread::sleep(Duration::from_millis(100));
                continue;
            }
        };
        let _ = stream.set_nodelay(true);
        let reader = BufReader::new(match stream.try_clone() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[agent] try_clone 失败: {e}");
                continue;
            }
        });
        let (writer_tx, writer_rx) = unbounded::<String>();
        let session = Arc::new(Session::new(writer_tx.clone(), token.clone()));

        // writer 线程：独占 socket 写端
        let wstream = stream;
        let writer_thread = std::thread::Builder::new()
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
        let handshake_ok = session.run(reader);
        if handshake_ok {
            eprintln!("[agent] 客户端断开，关闭引擎");
            session.shutdown_engine();
        }
        // 关键：给 writer 一个有界冲刷窗口。若不等待，进程退出会杀掉 writer 线程，
        // 排队中的最后一串响应/事件（如握手拒绝错误、Disconnected 事件）可能整体丢失，
        // 插件侧只会看到连接关闭而无错误信息
        drop(session);
        drop(writer_tx);
        let deadline = std::time::Instant::now() + Duration::from_millis(500);
        while !writer_thread.is_finished() && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        if handshake_ok {
            return;
        }
        eprintln!("[agent] 握手失败的连接已断开，继续等待真实客户端");
    }
}
