use serde::{Deserialize, Serialize};

/// 后端种类（对应原版工具栏 Backend 下拉：PyOCD → probe-rs，OpenOCD 保留；
/// Sim 为无硬件仿真后端，用于全链路测试与体验）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BackendKind {
    ProbeRs,
    Openocd,
    Sim,
}

/// 目标运行状态（注意：原版 IPC 里 TARGET_STATE 发送的是 `not halted`，
/// 这里统一为明确的枚举，避免极性混淆）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TargetState {
    Running,
    Halted,
    Disconnected,
}

fn default_tcl_port() -> u16 {
    6666
}

/// 连接参数。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectParams {
    pub kind: BackendKind,
    /// probe-rs 目标名（如 "STM32G431CBTx"），空表示自动。
    #[serde(default)]
    pub target: Option<String>,
    /// OpenOCD .cfg 文件路径。
    #[serde(default)]
    pub cfg_file: Option<String>,
    /// OpenOCD 可执行文件路径。
    #[serde(default)]
    pub openocd_path: Option<String>,
    /// OpenOCD scripts 目录（-s 搜索路径；用户 cfg 里的 `find ...` 依赖它）。
    #[serde(default)]
    pub scripts_dir: Option<String>,
    /// SWD 频率（Hz），默认 4MHz，对齐原版。
    #[serde(default = "default_speed")]
    pub speed_hz: u32,
    /// attach-only 模式：仅复用外部已有 OpenOCD，连接失败时严禁自行拉起 openocd 子进程。
    #[serde(default)]
    pub attach_only: bool,
    /// OpenOCD Tcl RPC 端口，默认 6666。
    #[serde(default = "default_tcl_port")]
    pub tcl_port: u16,
}

fn default_speed() -> u32 {
    4_000_000
}

impl Default for ConnectParams {
    fn default() -> Self {
        Self {
            kind: BackendKind::Sim,
            target: None,
            cfg_file: None,
            openocd_path: None,
            scripts_dir: None,
            speed_hz: default_speed(),
            attach_only: false,
            tcl_port: default_tcl_port(),
        }
    }
}
