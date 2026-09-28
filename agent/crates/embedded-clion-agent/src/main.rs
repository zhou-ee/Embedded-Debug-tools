//! embedded-clion-agent：CLion 插件 sidecar 进程。
//!
//! 监听本地 TCP，一行一个 JSON 请求，把 monitor 引擎（probe-rs / OpenOCD /
//! Sim 后端）与 elf-info DWARF 索引暴露给插件。进程生命周期由插件管理：
//! 连接断开即整体退出（引擎 Shutdown、probe 释放）。
//!
//! 就绪信号：绑定端口后向 stdout 打印
//! `CLION_AGENT_READY port=<n> pid=<n> proto=<n> token=<hex>`。
//! 协议要求：客户端接入后第一条消息必须是携带 token 的 hello（防本机其它
//! 用户/进程扫到端口后操控硬件）；仅允许绑定回环地址。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod elfcache;
mod protocol;
mod session;

use std::net::TcpListener;

/// 与插件端 AgentClient.EXPECTED_PROTOCOL 同步递增的行协议版本。
pub const PROTOCOL_VERSION: u32 = 1;

fn main() {
    #[cfg(windows)]
    unsafe {
        windows_sys::Win32::Media::timeBeginPeriod(1);
    }

    let mut host = "127.0.0.1".to_string();
    let mut port: u16 = 0;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--host" => {
                host = args.next().unwrap_or(host);
            }
            "--port" => {
                port = args.next().and_then(|v| v.parse().ok()).unwrap_or(0);
            }
            "--version" => {
                println!("embedded-clion-agent {}", env!("CARGO_PKG_VERSION"));
                return;
            }
            other => {
                eprintln!("[agent] 未知参数 {other}（支持 --host/--port/--version）");
            }
        }
    }

    // 协议无传输层加密，仅面向本机插件：拒绝绑定非回环地址，
    // 防止调试通道（读/写目标内存、halt/resume）暴露给局域网
    let loopback = host == "localhost"
        || host == "::1"
        || host == "127.0.0.1"
        || host.starts_with("127.");
    if !loopback {
        eprintln!("[agent] 拒绝绑定非回环地址 {host}（agent 无鉴权加密，仅允许 127.0.0.1/localhost/::1）");
        std::process::exit(2);
    }

    let listener = match TcpListener::bind((host.as_str(), port)) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[agent] 绑定 {host}:{port} 失败: {e}");
            std::process::exit(2);
        }
    };
    let bound = listener.local_addr().expect("local_addr");

    // 一次性连接令牌：本机其它用户的进程即使扫到端口，没有 token 也无法下发命令
    let token = session::generate_token();
    // 就绪信号（stdout 被插件以管道捕获；release 为 windows 子系统，仅在管道下有输出）
    println!(
        "CLION_AGENT_READY port={} pid={} proto={} token={token}",
        bound.port(),
        std::process::id(),
        PROTOCOL_VERSION
    );
    use std::io::Write as _;
    let _ = std::io::stdout().flush();
    eprintln!("[agent] 监听 {bound}，等待插件接入…");

    session::serve_one(&listener, token);
    eprintln!("[agent] 退出");
}
