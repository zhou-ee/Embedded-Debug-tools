//! OpenOCD 后端：子进程管理 + Tcl RPC（端口 6666，Ctrl+Z 帧协议）。
//! 移植原版 core/backend.py 的 OpenOCDClient / OpenOCDBackend。

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use crate::{BackendError, BurstFrames, DebugBackend};

const TCL_PORT: u16 = 6666;
const FRAME: u8 = 0x1a; // Ctrl+Z

pub struct OpenOcdBackend {
    openocd_path: String,
    cfg_file: String,
    scripts_dir: Option<PathBuf>,
    speed_hz: u32,
    attach_only: bool,
    tcl_port: u16,
    child: Option<Child>,
    stream: Option<BufReader<TcpStream>>,
    /// 示波块降级日志限流（1s 一条）
    last_degrade_log: Option<Instant>,
    /// Windows 专属：kill-on-close Job Object，agent 崩溃时内核自动收割 openocd
    #[cfg(windows)]
    job: Option<crate::openocd_job::Job>,
}

impl OpenOcdBackend {
    pub fn new(
        openocd_path: String,
        cfg_file: String,
        scripts_dir: Option<PathBuf>,
        speed_hz: u32,
    ) -> Self {
        Self {
            openocd_path,
            cfg_file,
            scripts_dir,
            speed_hz,
            attach_only: false,
            tcl_port: TCL_PORT,
            child: None,
            stream: None,
            last_degrade_log: None,
            #[cfg(windows)]
            job: None,
        }
    }

    pub fn with_attach_only(mut self, attach_only: bool) -> Self {
        self.attach_only = attach_only;
        self
    }

    pub fn with_tcl_port(mut self, tcl_port: u16) -> Self {
        self.tcl_port = tcl_port;
        self
    }

    fn try_connect_tcl(&mut self) -> Result<(), BackendError> {
        let stream = TcpStream::connect_timeout(
            &([127, 0, 0, 1], self.tcl_port).into(),
            Duration::from_millis(200),
        )
        .map_err(|e| BackendError::ConnectionLost(format!("Tcl 连接失败: {e}")))?;
        stream.set_read_timeout(Some(Duration::from_millis(2500))).ok();
        stream.set_write_timeout(Some(Duration::from_millis(2000))).ok();
        stream.set_nodelay(true).ok();
        self.stream = Some(BufReader::new(stream));
        Ok(())
    }

    fn spawn_openocd(&mut self) -> Result<(), BackendError> {
        let mut cmd = Command::new(&self.openocd_path);
        cmd.arg("-f").arg(&self.cfg_file);
        if let Some(scripts) = &self.scripts_dir {
            if scripts.exists() {
                cmd.arg("-s").arg(scripts);
            }
        }
        // 必须把 tcl_port 下发给子进程：cfg 默认 6666，而等待循环按 self.tcl_port
        // 轮询——不下发时用户改过 tcl_port 就陷入"拉起→轮询超时→杀掉"死循环
        cmd.arg("-c").arg(format!("tcl_port {}", self.tcl_port));
        cmd.stdout(Stdio::null())
            .stderr(Stdio::null())
            .stdin(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        #[cfg(unix)]
        {
            // 独立进程组：agent 异常退出后可整组清理（无内核级自动收割）
            use std::os::unix::process::CommandExt;
            let _ = cmd.process_group(0);
        }
        let child = cmd
            .spawn()
            .map_err(|e| BackendError::ConnectionLost(format!("启动 OpenOCD 失败: {e}")))?;
        // kill-on-close Job Object：agent 崩溃/被强杀时 Drop 不会执行，
        // 由内核关闭 Job 句柄连带杀掉 openocd，不再遗留孤儿占用 USB 探针
        #[cfg(windows)]
        match crate::openocd_job::Job::create().and_then(|j| {
            j.assign(&child)?;
            Ok(j)
        }) {
            Ok(j) => self.job = Some(j),
            Err(e) => eprintln!("[openocd] Job Object 关联失败（孤儿保护不可用）: {e}"),
        }
        self.child = Some(child);
        // 等待 Tcl server 就绪（对照原版固定 1.5s，改为轮询更快）。
        // connect_timeout 200ms × 50 次 ≈ 3s 上限：localhost 拒绝连接是瞬时的，
        // 200ms 足够覆盖 openocd 绑定端口的窗口，且引擎无响应窗口小
        for _ in 0..50 {
            std::thread::sleep(Duration::from_millis(60));
            // openocd 若启动即退出（cfg 错误/探针被占），快速失败而不是等满轮询
            match self.child.as_mut().map(|c| c.try_wait()) {
                Some(Ok(Some(status))) => {
                    return Err(BackendError::ConnectionLost(format!(
                        "OpenOCD 启动即退出（退出码 {:?}；检查 cfg 与探针是否被占用）",
                        status.code()
                    )));
                }
                // try_wait 本身出错（如 fd 异常）继续空转 50×60ms 毫无意义，直接报错
                Some(Err(e)) => {
                    return Err(BackendError::ConnectionLost(format!(
                        "OpenOCD 状态查询失败: {e}"
                    )));
                }
                _ => {}
            }
            if self.try_connect_tcl().is_ok() {
                return Ok(());
            }
        }
        Err(BackendError::ConnectionLost(format!(
            "OpenOCD Tcl 端口 ({}) 等待超时",
            self.tcl_port
        )))
    }

    /// 临时放宽读超时执行慢命令：`reset halt` 在复位序列完成后才返回，
    /// 默认 2.5s 读超时对带看门狗/慢时钟的目标会误判连接丢失并拆流重建。
    fn tcl_slow(&mut self, cmd: &str, read_timeout: Duration) -> Result<String, BackendError> {
        if let Some(stream) = self.stream.as_ref() {
            let _ = stream.get_ref().set_read_timeout(Some(read_timeout));
        }
        let r = self.tcl(cmd);
        if let Some(stream) = self.stream.as_ref() {
            let _ = stream.get_ref().set_read_timeout(Some(Duration::from_millis(2500)));
        }
        r
    }

    /// 发送 Tcl 命令并读取响应（Ctrl+Z 结尾帧）。
    ///
    /// 关键：读超时/失败时**必须**放弃这条连接（ConnectionLost）。
    /// 若按瞬时错误处理继续复用，残留在管道里的上一条响应会与后续命令
    /// 错位配对（表现为 read_memory 收到 curstate 文本 → "期望 N 得到 0"）。
    pub fn tcl(&mut self, cmd: &str) -> Result<String, BackendError> {
        self.tcl_send(cmd)?;
        self.tcl_recv()
    }

    fn tcl_send(&mut self, cmd: &str) -> Result<(), BackendError> {
        let stream = self.stream.as_mut().ok_or(BackendError::NotConnected)?;
        let mut payload = cmd.as_bytes().to_vec();
        payload.push(FRAME);
        if let Err(e) = stream.get_mut().write_all(&payload) {
            self.stream = None;
            return Err(BackendError::ConnectionLost(format!("Tcl 写失败: {e}")));
        }
        Ok(())
    }

    fn tcl_recv(&mut self) -> Result<String, BackendError> {
        let stream = self.stream.as_mut().ok_or(BackendError::NotConnected)?;
        let mut out = Vec::new();
        // take() 封顶读入长度：read_until 本身无界，若帧错位导致数据流持续
        // 不含 0x1a，内存会无界增长到连接关闭；超限即按连接丢失重建
        let mut limited = stream.by_ref().take(4 * 1024 * 1024 + 1);
        match limited.read_until(FRAME, &mut out) {
            Ok(0) => {
                self.stream = None;
                return Err(BackendError::ConnectionLost("Tcl 连接关闭".into()));
            }
            Ok(_) => {
                if out.len() > 4 * 1024 * 1024 {
                    self.stream = None;
                    return Err(BackendError::ConnectionLost("Tcl 响应过大".into()));
                }
                if out.last() != Some(&FRAME) {
                    // EOF 截断：读到底但未见 0x1a——帧同步已破坏，残缺文本交给
                    // 下游（halt/resume 只查关键字）会被误当成功，按断连重建
                    self.stream = None;
                    return Err(BackendError::ConnectionLost(
                        "Tcl 帧未以 0x1a 终止（连接将重建）".into(),
                    ));
                }
                out.pop();
            }
            Err(e) => {
                // 超时或 IO 错误：帧同步已破坏，重建连接
                self.stream = None;
                return Err(BackendError::ConnectionLost(format!(
                    "Tcl 读失败（连接将重建）: {e}"
                )));
            }
        }
        Ok(String::from_utf8_lossy(&out).into_owned())
    }
}

/// OpenOCD 失败时错误文本就是本帧响应（帧同步完好）。halt/resume/reset 族此前
/// 不校验响应内容，失败被当成功，后续"重下断点→恢复"建立在错误前提上。
fn check_inband_error(resp: &str) -> Result<(), BackendError> {
    let lower = resp.to_lowercase();
    if lower.contains("error")
        || lower.contains("failed")
        || lower.contains("timed out")
        || lower.contains("invalid command")
    {
        Err(BackendError::Transfer(resp.to_string()))
    } else {
        Ok(())
    }
}

fn parse_number_tokens(text: &str) -> Vec<u8> {
    // read_memory 返回如 "0x12 0x34 ..." 或十进制 token
    text.split_whitespace()
        .filter_map(|tok| {
            let tok = tok.trim_matches(|c| c == '{' || c == '}');
            if let Some(hex) = tok.strip_prefix("0x").or_else(|| tok.strip_prefix("0X")) {
                u8::from_str_radix(hex, 16).ok()
            } else {
                tok.parse::<u8>().ok()
            }
        })
        .collect()
}

/// 解析 `read_memory … 32` 返回的 32 位字（形如 "0xdeadbeef"）。
/// 注意不能复用 `parse_number_tokens`：那是按 u8 解析的。
fn parse_u32_tokens(text: &str) -> Vec<u32> {
    text.split_whitespace()
        .filter_map(|tok| {
            let tok = tok.trim_matches(|c| c == '{' || c == '}');
            match tok.strip_prefix("0x").or_else(|| tok.strip_prefix("0X")) {
                Some(hex) => u32::from_str_radix(hex, 16).ok(),
                None => tok.parse::<u32>().ok(),
            }
        })
        .collect()
}

impl DebugBackend for OpenOcdBackend {
    fn connect(&mut self) -> Result<(), BackendError> {
        // 优先复用已有守护进程（防多实例 USB 冲突，对照原版）
        if let Err(err) = self.try_connect_tcl() {
            // attach-only 模式严禁自行拉起 openocd 子进程！直接报错返回
            if self.attach_only {
                return Err(BackendError::ConnectionLost(format!(
                    "OpenOCD Tcl 端口 ({}) 不可达（attach-only 模式严禁拉起 OpenOCD 子进程）: {err}",
                    self.tcl_port
                )));
            }
            self.spawn_openocd()?;
        }
        // 确认可用
        self.tcl("poll")?;
        // SWD 时钟：引擎的 speed_hz（kHz）。此前 OpenOCD 后端从不设置速度，
        // 只用 cfg 里的默认值。亚 kHz 输入整除得 0，adapter speed 0 无效——钳到 1kHz
        let _ = self.tcl(&format!("adapter speed {}", (self.speed_hz / 1000).max(1)));
        // 关键静音保护：OpenOCD 默认会将所有通过 Tcl RPC 执行的命令结果（通过 LOG_USER）
        // 打印到自身 stdout/stderr，这会导致 CLion 的 OpenOCD 控制台被周期性轮询（curstate / read_memory）
        // 疯狂刷屏（例如持续弹出 running / halted / 0x42c6fd72）。
        // 设置 debug_level -3 (LOG_LVL_SILENT) 可彻底静音这些标准输出回显，
        // 而 Tcl RPC 套接字通道依然正常传输完整的响应数据。
        let _ = self.tcl("debug_level -3");
        Ok(())
    }

    fn disconnect(&mut self) {
        // 优雅关闭：仅当持有内部启动的 child 子进程时才发送 shutdown 让 openocd 退出；
        // 若为外部复用的进程（例如 CLion 调试器中启动的 OpenOCD，self.child 为 None），
        // 必须优雅关闭 Tcl 客户端会话并保持 silent 级别，绝对不能发送 shutdown 关闭调试器的 OpenOCD 进程！
        if self.stream.is_some() {
            if self.child.is_some() {
                let _ = self.tcl_send("shutdown");
                // 给 openocd 一点时间自行退出
                std::thread::sleep(Duration::from_millis(150));
            } else {
                // 1. 确保保持 debug_level -3 静音保护，防止关闭会话时触发回显刷入 CLion 控制台
                let _ = self.tcl("debug_level -3");
                // 2. 发送 exit 退出当前 Tcl 会话并完整读取其响应帧，确保 TCP 管道被排空
                let _ = self.tcl("exit");
                // 3. 优雅半关闭 TCP socket (SHUT_RDWR)，避免 Windows 下未优雅挥手引发 TCP RST
                //    导致 OpenOCD 报 "Error: error during read: Bad file descriptor" 和 "dropped 'tcl' connection"
                if let Some(reader) = self.stream.as_mut() {
                    let _ = reader.get_ref().shutdown(std::net::Shutdown::Both);
                }
            }
        }
        self.stream = None;
        if let Some(mut child) = self.child.take() {
            match child.try_wait() {
                Ok(Some(_)) => {} // 已按 shutdown 退出
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                }
            }
        }
    }

    fn is_connected(&self) -> bool {
        self.stream.is_some()
    }

    fn is_shared(&self) -> bool {
        // attach_only=false 也可能复用了已运行的 OpenOCD，按实际进程归属判断。
        self.child.is_none()
    }

    /// 注意：**本实现不做 probe-rs 那样的 `memory_regions()` 越界预检** ——
    /// OpenOCD 侧拿不到 target 的内存映射，且读越界只会回一条带内错误文本
    /// （不会像 probe-rs 那样把访问端口留在 FAULT 态），故由目标自行报错即可。
    /// 这是两后端**有意保留的差异**，不是漏改；改动前请先确认越界不再有风险。
    fn read_bytes(&mut self, addr: u64, len: usize) -> Result<Vec<u8>, BackendError> {
        if len == 0 {
            return Ok(Vec::new());
        }
        if addr.is_multiple_of(4) && len.is_multiple_of(4) {
            let words = len / 4;
            if let Ok(resp) = self.tcl(&format!("read_memory 0x{addr:x} 32 {words}")) {
                let lower = resp.to_lowercase();
                if !lower.contains("error") && !lower.contains("failed") && !lower.contains("invalid command") {
                    let u32s = parse_u32_tokens(&resp);
                    if u32s.len() == words {
                        let mut bytes = Vec::with_capacity(len);
                        for w in u32s {
                            bytes.extend_from_slice(&w.to_le_bytes());
                        }
                        return Ok(bytes);
                    }
                }
            }
        }
        let resp = self.tcl(&format!("read_memory 0x{addr:x} 8 {len}"))?;
        let lower = resp.to_lowercase();
        if lower.contains("invalid command name") {
            // OpenOCD < 0.12 不支持 read_memory
            return Err(BackendError::ConnectionLost(
                "OpenOCD 版本过旧：需要 0.12+（read_memory 命令不可用）".into(),
            ));
        }
        // 带内错误（如 "read_memory: failed to read memory"）：命令失败但
        // 帧同步完好（错误文本就是本条命令的完整响应），按瞬时错误处理，
        // 不能拆连接——否则一个非法地址就会让会话陷入永久断线重连循环
        if lower.contains("error") || lower.contains("failed") {
            return Err(BackendError::Transfer(resp));
        }
        let bytes = parse_number_tokens(&resp);
        if bytes.len() != len {
            // 帧可能已错位（响应与命令不匹配）：重建连接以恢复同步
            self.stream = None;
            let preview: String = resp.chars().take(120).collect();
            return Err(BackendError::ConnectionLost(format!(
                "读取长度不符: 期望 {len} 得到 {}（响应片段: {preview:?}），连接将重建",
                bytes.len()
            )));
        }
        Ok(bytes)
    }

    fn write_bytes(&mut self, addr: u64, data: &[u8]) -> Result<(), BackendError> {
        if data.is_empty() {
            return Ok(());
        }
        let list = data
            .iter()
            .map(|b| format!("0x{b:02x}"))
            .collect::<Vec<_>>()
            .join(" ");
        let resp = self.tcl(&format!("write_memory 0x{addr:x} 8 {{{list}}}"))?;
        let lower = resp.to_lowercase();
        // OpenOCD 0.12 写失败文本为 "write_memory: failed to write memory"（无 "error"）
        if lower.contains("error") || lower.contains("failed") {
            return Err(BackendError::Transfer(resp));
        }
        Ok(())
    }

    /// 调试寄存器必须按字（32 位）访问 —— `read_memory <addr> 8 4` 是字节访问，
    /// 会被 CoreSight 调试寄存器静默忽略（见 trait 上的 `read_u32` 说明）。
    fn read_u32(&mut self, addr: u64) -> Result<u32, BackendError> {
        let resp = self.tcl(&format!("read_memory 0x{addr:x} 32 1"))?;
        let lower = resp.to_lowercase();
        if lower.contains("invalid command name") {
            return Err(BackendError::ConnectionLost(
                "OpenOCD 版本过旧：需要 0.12+（read_memory 命令不可用）".into(),
            ));
        }
        if lower.contains("error") || lower.contains("failed") {
            return Err(BackendError::Transfer(resp));
        }
        match parse_u32_tokens(&resp).first() {
            Some(v) => Ok(*v),
            None => {
                let preview: String = resp.chars().take(120).collect();
                Err(BackendError::ConnectionLost(format!(
                    "读取 0x{addr:08x} 无法解析 32 位字（响应片段: {preview:?}）"
                )))
            }
        }
    }

    fn write_u32(&mut self, addr: u64, value: u32) -> Result<(), BackendError> {
        let resp = self.tcl(&format!("write_memory 0x{addr:x} 32 {{0x{value:08x}}}"))?;
        let lower = resp.to_lowercase();
        if lower.contains("error") || lower.contains("failed") {
            return Err(BackendError::Transfer(resp));
        }
        Ok(())
    }

    fn halt(&mut self) -> Result<(), BackendError> {
        let resp = self.tcl("halt")?;
        check_inband_error(&resp)
    }

    fn resume(&mut self) -> Result<(), BackendError> {
        let resp = self.tcl("resume")?;
        check_inband_error(&resp)
    }

    fn step(&mut self) -> Result<(), BackendError> {
        let resp = self.tcl("step")?;
        check_inband_error(&resp)
    }

    fn reset(&mut self) -> Result<(), BackendError> {
        let resp = self.tcl_slow("reset run", Duration::from_secs(10))?;
        check_inband_error(&resp)
    }

    fn reset_and_halt(&mut self) -> Result<(), BackendError> {
        let resp = self.tcl_slow("reset halt", Duration::from_secs(10))?;
        check_inband_error(&resp)
    }

    fn is_halted(&mut self) -> Result<bool, BackendError> {
        let resp = self.tcl("set s [[target current] curstate]; set s")?;
        let lower = resp.to_lowercase();
        if lower.contains("halted") {
            return Ok(true);
        }
        if lower.contains("running") || lower.contains("reset") {
            return Ok(false);
        }
        // 备用：poll 文本
        let poll = self.tcl("poll")?.to_lowercase();
        Ok(poll.contains("halted"))
    }

    fn set_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        let addr = addr & !1;
        // 幂等：先清同地址残留断点（复用外部守护进程时可能已存在；
        // 0.12 对重复地址只走 LOG_ERROR，Tcl 结果里拿不到 "already" 文本，
        // 不先清会永远失败且无法自愈）。rbp 对不存在的断点无害。
        let _ = self.tcl(&format!("rbp 0x{addr:x}"))?;
        let resp = self.tcl(&format!("bp 0x{addr:x} 2 hw"))?;
        // 成功时 OpenOCD 输出 "breakpoint set at 0x…"（0.11/0.12/master 逐字一致）；
        // 失败（比较器用尽等）只回传错误 retval，不一定含 "error" 文本 → 以成功文本为准
        if resp.to_lowercase().contains("breakpoint set at") {
            return Ok(());
        }
        Err(BackendError::Transfer(format!("设置断点失败: {resp}")))
    }

    fn clear_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        let addr = addr & !1;
        let _ = self.tcl(&format!("rbp 0x{addr:x}"))?;
        Ok(())
    }

    fn read_core_register(&mut self, name: &str) -> Result<u64, BackendError> {
        match self.read_reg_parse(name) {
            Ok(v) => Ok(v),
            Err(e) => {
                // OpenOCD 寄存器名区分大小写：Cortex-M 的 PSR 注册为 "xPSR"，
                // 引擎统一用小写 "xpsr" 查询 → 解析失败时改用大写变体重试
                let alt = match name.to_lowercase().as_str() {
                    "xpsr" | "psr" => Some("xPSR"),
                    _ => None,
                };
                match alt {
                    Some(alt) if alt != name => self.read_reg_parse(alt),
                    _ => Err(e),
                }
            }
        }
    }

    /// 示波突发（OpenOCD 实现）：逐帧同步读 —— Tcl RPC 每命令有 ~1ms 往返
    /// （服务器主循环节拍），批量排队无法突破（实测深度 2/4/8 吞吐不变）。
    /// **真机实测该路径可达成 960–1000Hz**；早期文档写的"~870Hz 架构上限"
    /// 出自 bench 推算，与实测不符，已作废。时间戳取实际读时刻、无失真。
    fn scope_burst(
        &mut self,
        blocks: &[(u64, usize)],
        count: usize,
        interval: Duration,
    ) -> Result<BurstFrames, BackendError> {
        let start = Instant::now();
        let mut out = Vec::with_capacity(count);
        let mut total_blocks = 0usize;
        let mut degraded = 0usize;
        // 帧节拍：读耗时超过间隔时，截止时刻重锚到"当前 + 间隔"，不追赶旧截止
        // （与 probe-rs 后端一致——旧实现超期后连续快速读取，批内间隔忽快忽慢）
        let mut due = start;
        for _i in 0..count {
            // 混合节拍（同引擎外层循环）：粗睡到临近 + 末段忙等——
            // Windows thread::sleep 粒度 ~1-2ms，纯 sleep 无法覆盖亚毫秒间隔
            // （3kHz=333µs/帧在纯 sleep 下退化为 ~1ms → 只有 ~900Hz）
            let now = Instant::now();
            if now < due {
                let remain = due - now;
                if remain > Duration::from_millis(2) {
                    std::thread::sleep(remain - Duration::from_micros(1500));
                }
                while Instant::now() < due {
                    std::hint::spin_loop();
                }
            }
            // 帧时间戳 = 本帧读取开始时刻（样本窗口起点）。旧实现取全部块读完
            // 后的时刻，回包等待与调度延迟被混进时间戳（真机实测：Agent 时间戳
            // 间隔与 MCU tick 间隔严重错位 56ms/6ms）
            let frame_ts = start.elapsed();
            let mut frame = Vec::with_capacity(blocks.len());
            for (addr, len) in blocks {
                total_blocks += 1;
                let addr = *addr;
                let len = *len;
                if addr % 4 == 0 && len % 4 == 0 && len > 0 {
                    let words = len / 4;
                    // 32-bit 字读在总线层面原子（无撕裂）；带内失败多为瞬态
                    // （目标忙/GDB 抢占/复位过渡），重试一次。
                    // **不回退 read_memory 8**：逐字节访问非原子，固件在字节间写入
                    // 会产生撕裂值（真机实测 sin_20hz 坑洼：符号位翻转的假值）。
                    let mut ok = false;
                    let mut resp = String::new();
                    for _ in 0..2 {
                        if let Ok(r) = self.tcl(&format!("read_memory 0x{addr:x} 32 {words}")) {
                            let lower = r.to_lowercase();
                            let bad = lower.contains("error") || lower.contains("failed")
                                || lower.contains("invalid command");
                            let u32s = parse_u32_tokens(&r);
                            if !bad && u32s.len() == words {
                                let mut bytes = Vec::with_capacity(len);
                                for w in u32s {
                                    bytes.extend_from_slice(&w.to_le_bytes());
                                }
                                frame.push(bytes);
                                ok = true;
                                break;
                            }
                            resp = r;
                        } else {
                            // 传输级错误（连接破坏/帧失步）：中止整个突发走重建
                            return Err(BackendError::ConnectionLost(
                                "Tcl 传输失败（连接将重建）".into(),
                            ));
                        }
                    }
                    if !ok {
                        // 两次 32-bit 均失败：本块降级为空（该块目标本帧缺值，
                        // 其它块与后续帧不受影响），避免 50ms 全通道空档台阶
                        degraded += 1;
                        let _ = resp;
                        frame.push(Vec::new());
                    }
                    continue;
                }
                // 非 4 对齐块（理论不出现，示波块按 4 对齐合并）：保留 8-bit 读取
                let resp = self.tcl(&format!("read_memory 0x{addr:x} 8 {len}"))?;
                if check_inband_error(&resp).is_err() {
                    degraded += 1;
                    frame.push(Vec::new());
                    continue;
                }
                let bytes = parse_number_tokens(&resp);
                if bytes.len() != len {
                    degraded += 1;
                    frame.push(Vec::new());
                    continue;
                }
                frame.push(bytes);
            }
            out.push((frame_ts, frame));
            // 节拍推进与重锚定语义同 probe-rs 后端（见彼处注释）
            due += interval;
            let now = Instant::now();
            if now > due {
                due = now + interval;
            }
        }
        // 全部块失败也返回实际采样时刻的空帧；引擎计入缺样后再做 50ms 避让。
        if degraded > 0 {
            let now = Instant::now();
            if self.last_degrade_log.is_none_or(|t| now.duration_since(t) >= Duration::from_secs(1)) {
                self.last_degrade_log = Some(now);
                eprintln!("[openocd] scope 突发降级：{degraded}/{total_blocks} 块读取失败（该块目标本帧缺值，其它块不受影响）");
            }
        }
        Ok(out)
    }
}


impl OpenOcdBackend {
    /// `reg <name>` 响应中提取寄存器值（形如 "pc (/32): 0x08000c40"）。
    fn read_reg_parse(&mut self, name: &str) -> Result<u64, BackendError> {
        let resp = self.tcl(&format!("reg {name}"))?;
        for tok in resp.split_whitespace() {
            if let Some(hex) = tok.strip_prefix("0x").or_else(|| tok.strip_prefix("0X")) {
                if let Ok(v) = u64::from_str_radix(hex.trim_end_matches(','), 16) {
                    return Ok(v);
                }
            }
        }
        Err(BackendError::Transfer(format!("无法解析寄存器 {name}: {resp}")))
    }
}

impl Drop for OpenOcdBackend {
    fn drop(&mut self) {
        self.disconnect();
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_number_tokens, parse_u32_tokens};
    use crate::DebugBackend;
    use std::io::{BufRead, BufReader, Write};
    use std::net::{TcpListener, TcpStream};
    use std::time::Duration;

    fn failing_tcl_backend(close_on_read: bool) -> (super::OpenOcdBackend, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let worker = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            loop {
                let mut request = Vec::new();
                if reader.read_until(super::FRAME, &mut request).unwrap_or(0) == 0 { break; }
                if close_on_read { break; }
                stream.write_all(b"error deliberate read failure\x1a").unwrap();
            }
        });
        let mut backend = super::OpenOcdBackend::new("unused".into(), "".into(), None, 4_000_000);
        let stream = TcpStream::connect(address).unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        backend.stream = Some(BufReader::new(stream));
        (backend, worker)
    }

    #[test]
    fn totally_failed_scope_burst_returns_timestamped_missing_frames() {
        let (mut backend, worker) = failing_tcl_backend(false);
        assert!(backend.is_shared(), "externally supplied connection is shared even without attach_only");
        let frames = backend.scope_burst(&[(0x20000000, 4)], 2, Duration::from_millis(1)).unwrap();
        assert_eq!(frames.len(), 2);
        assert!(frames[1].0 >= frames[0].0);
        assert!(frames.iter().all(|(_, blocks)| blocks.len() == 1 && blocks[0].is_empty()));
        backend.stream = None;
        worker.join().unwrap();
    }

    #[test]
    fn scope_transport_failure_is_fatal_so_engine_reconnects() {
        let (mut backend, worker) = failing_tcl_backend(true);
        let error = backend.scope_burst(&[(0x20000000, 4)], 2, Duration::from_millis(1)).unwrap_err();
        assert!(error.is_fatal());
        assert!(!backend.is_connected());
        worker.join().unwrap();
    }

    #[test]
    fn parse_hex_tokens() {
        assert_eq!(parse_number_tokens("0x12 0x34 0xff"), vec![0x12, 0x34, 0xff]);
    }

    #[test]
    fn parse_dec_and_braces() {
        assert_eq!(parse_number_tokens("{18 52 255}"), vec![18, 52, 255]);
    }

    /// 32 位调试寄存器读取：字节解析器会把 0x410fc241 丢掉，必须用 u32 解析
    #[test]
    fn parse_u32_word() {
        assert_eq!(parse_u32_tokens("0x410fc241"), vec![0x410f_c241]);
        assert_eq!(parse_u32_tokens("{0x01000000}"), vec![0x0100_0000]);
        assert_eq!(parse_u32_tokens("0xdeadbeef 0x12345678"), vec![0xdead_beef, 0x1234_5678]);
        assert!(parse_u32_tokens("read_memory: failed").is_empty());
    }

    #[test]
    fn attach_only_does_not_spawn_openocd() {
        use crate::DebugBackend;
        let mut backend = super::OpenOcdBackend::new(
            "nonexistent_openocd_binary_for_test".into(),
            "".into(),
            None,
            4_000_000,
        )
        .with_attach_only(true)
        .with_tcl_port(65534);

        let res = backend.connect();
        assert!(res.is_err());
        let err_str = res.unwrap_err().to_string();
        assert!(err_str.contains("attach-only 模式严禁拉起 OpenOCD 子进程"));
        assert!(backend.child.is_none());
    }
}
