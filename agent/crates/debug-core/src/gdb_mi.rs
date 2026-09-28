//! GDB/MI2 表达式求值器（移植原版 core/gdb_evaluator.py）。
//! 仅 OpenOCD 后端使用：连接其 GDB server (:3333)，
//! 用 -data-evaluate-expression 求复杂 C/C++ 表达式。

use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{channel, Receiver};
use std::time::{Duration, Instant};

pub struct GdbEvaluator {
    child: Child,
    stdin: ChildStdin,
    lines: Receiver<String>,
    token: u64,
    connected: bool,
}

impl GdbEvaluator {
    pub fn spawn(gdb_path: &str) -> Result<Self, String> {
        let mut cmd = Command::new(gdb_path);
        cmd.args(["--interpreter=mi2", "--quiet", "--nx"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000);
        }
        let mut child = cmd.spawn().map_err(|e| format!("启动 GDB 失败: {e}"))?;
        let stdin = child.stdin.take().ok_or("GDB stdin 不可用")?;
        let stdout = child.stdout.take().ok_or("GDB stdout 不可用")?;

        let (tx, rx) = channel::<String>();
        std::thread::Builder::new()
            .name("gdb-mi-reader".into())
            .spawn(move || {
                let reader = BufReader::new(stdout);
                for line in reader.lines() {
                    match line {
                        Ok(l) => {
                            if tx.send(l).is_err() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            })
            .map_err(|e| e.to_string())?;

        Ok(Self {
            child,
            stdin,
            lines: rx,
            token: 0,
            connected: false,
        })
    }

    /// 加载符号并连接 OpenOCD GDB server。
    ///
    /// 超时预算按**实测**给（2026-09-16 真机首次验证）：
    /// - 加载符号：`g4_tool_test.elf`(1.27MB, -g3) 实测 **3.6s**，原来的 5s 时限
    ///   余量太薄，在引擎里必然偶发超时 —— 放宽到 30s（大工程 DWARF 更大）；
    /// - 连接 target：gdb 连上后要读寄存器/内存，放宽到 15s。
    pub fn connect(&mut self, elf_path: &str, gdb_port: u16) -> Result<(), String> {
        let elf = elf_path.replace('\\', "/");
        self.command(&format!("-file-exec-and-symbols \"{elf}\""), 30_000)?;
        self.command(
            &format!("-target-select extended-remote 127.0.0.1:{gdb_port}"),
            15_000,
        )
        .or_else(|_| {
            self.command(
                &format!("-target-select remote 127.0.0.1:{gdb_port}"),
                15_000,
            )
        })?;
        self.connected = true;
        Ok(())
    }

    pub fn is_connected(&self) -> bool {
        self.connected
    }

    /// 求值表达式，返回 value 字符串。
    pub fn evaluate(&mut self, expr: &str) -> Result<String, String> {
        // MI 是行协议：表达式里的换行会把后续内容当独立 MI 命令执行，
        // 必须剔除（C 表达式中的裸换行本身也不合法）
        let sanitized: String = expr.chars().map(|c| if c == '\n' || c == '\r' { ' ' } else { c }).collect();
        let escaped = sanitized.replace('\\', "\\\\").replace('"', "\\\"");
        let result = self.command(
            &format!("-data-evaluate-expression \"{escaped}\""),
            3000,
        )?;
        // ^done,value="..."
        extract_mi_value(&result).ok_or_else(|| format!("无法解析结果: {result}"))
    }

    /// 发送 MI 命令，等待对应 token 的 ^done/^error。
    fn command(&mut self, cmd: &str, timeout_ms: u64) -> Result<String, String> {
        self.token += 1;
        let token = self.token;
        let line = format!("{token}{cmd}\n");
        self.stdin
            .write_all(line.as_bytes())
            .and_then(|_| self.stdin.flush())
            .map_err(|e| format!("GDB 写入失败: {e}"))?;

        let token_str = token.to_string();
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        let prefix_error = format!("{token}^error");
        let timeout_msg = format!("GDB 响应超时（命令: {cmd}，{timeout_ms}ms）");
        loop {
            let remain = deadline.saturating_duration_since(Instant::now());
            if remain.is_zero() {
                return Err(timeout_msg);
            }
            match self.lines.recv_timeout(remain) {
                Ok(l) => {
                    if is_success_reply(&l, &token_str) {
                        return Ok(l);
                    }
                    if l.starts_with(&prefix_error) {
                        let msg = extract_mi_field(&l, "msg").unwrap_or_else(|| l.clone());
                        return Err(format!("{msg}（命令: {cmd}）"));
                    }
                    // 异步/流输出忽略
                }
                // 通道关闭 = GDB 进程已死，与超时区分开（误导诊断）
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    return Err(format!("GDB 进程已退出（命令: {cmd}）"));
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => return Err(timeout_msg),
            }
        }
    }
}

/// 该行是否属于 `token` 的**成功**应答。
///
/// 注意 `-target-select` 回的是 `^connected` 而**不是** `^done`：只认 `^done` 会让
/// 连接永远超时（2026-09-16 真机首次验证 GDB 表达式求值时踩到 —— 文档写着支持，
/// 实际**从未生效过**；单测只测了 `^done` 的解析，所以一直没暴露）。
fn is_success_reply(line: &str, token: &str) -> bool {
    match line.strip_prefix(token) {
        Some(rest) => rest.starts_with("^done") || rest.starts_with("^connected"),
        None => false,
    }
}

impl Drop for GdbEvaluator {
    fn drop(&mut self) {
        let _ = self.stdin.write_all(b"-gdb-exit\n");
        let _ = self.stdin.flush();
        std::thread::sleep(Duration::from_millis(50));
        let _ = self.child.kill();
        // reap：Unix 上不 wait 会留僵尸进程
        let _ = self.child.wait();
    }
}

fn extract_mi_value(line: &str) -> Option<String> {
    extract_mi_field(line, "value")
}

/// 从 MI 结果行提取 field="..."（处理转义）。
fn extract_mi_field(line: &str, field: &str) -> Option<String> {
    let needle = format!("{field}=\"");
    let start = line.find(&needle)? + needle.len();
    let rest = &line[start..];
    let mut out = String::new();
    let mut chars = rest.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                if let Some(next) = chars.next() {
                    match next {
                        'n' => out.push('\n'),
                        't' => out.push('\t'),
                        other => out.push(other),
                    }
                }
            }
            '"' => return Some(out),
            other => out.push(other),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::{extract_mi_field, extract_mi_value, is_success_reply};

    #[test]
    fn parse_done_value() {
        assert_eq!(
            extract_mi_value(r#"3^done,value="42""#),
            Some("42".to_string())
        );
        assert_eq!(
            extract_mi_value(r#"5^done,value="{speed = -20, target = 1000}""#),
            Some("{speed = -20, target = 1000}".to_string())
        );
    }

    #[test]
    fn parse_escaped() {
        assert_eq!(
            extract_mi_value(r#"1^done,value="\"str\"""#),
            Some("\"str\"".to_string())
        );
    }

    #[test]
    fn parse_error_msg() {
        assert_eq!(
            extract_mi_field(r#"2^error,msg="No symbol table is loaded.""#, "msg"),
            Some("No symbol table is loaded.".to_string())
        );
    }

    /// `-target-select` 回 `^connected` 而非 `^done` —— 只认后者会让 GDB 连接永远超时
    /// （真机首次验证时踩到：文档写着支持，实际从未生效）。
    #[test]
    fn success_reply_accepts_connected() {
        assert!(is_success_reply("2^connected", "2"));
        assert!(is_success_reply(r#"3^done,value="231410""#, "3"));
        // 别人的 token、错误行都不算成功
        assert!(!is_success_reply("3^done", "2"));
        assert!(!is_success_reply(r#"2^error,msg="boom""#, "2"));
        // 前缀相同的 token 不能误判（2 vs 20）
        assert!(!is_success_reply("20^done", "2"));
    }
}
