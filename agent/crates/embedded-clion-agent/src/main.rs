//! embedded-clion-agent：CLion 插件 sidecar 进程。
//!
//! 监听本地 TCP，一行一个 JSON 请求，把 monitor 引擎（probe-rs / OpenOCD /
//! Sim 后端）与 elf-info DWARF 索引暴露给插件。进程生命周期由插件管理：
//! 连接断开即整体退出（引擎 Shutdown、probe 释放）。
//!
//! 就绪信号：绑定端口后向 stdout 打印 `CLION_AGENT_READY port=<n> pid=<n>`。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod elfcache;
mod protocol;
mod session;

use std::net::TcpListener;

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

    let listener = match TcpListener::bind((host.as_str(), port)) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[agent] 绑定 {host}:{port} 失败: {e}");
            std::process::exit(2);
        }
    };
    let bound = listener.local_addr().expect("local_addr");
    // 就绪信号（stdout 被插件以管道捕获；release 为 windows 子系统，仅在管道下有输出）
    println!("CLION_AGENT_READY port={} pid={}", bound.port(), std::process::id());
    use std::io::Write as _;
    let _ = std::io::stdout().flush();
    eprintln!("[agent] 监听 {bound}，等待插件接入…");

    session::serve_one(&listener);
    eprintln!("[agent] 退出");
}
