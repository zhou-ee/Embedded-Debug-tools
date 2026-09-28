//! 统一调试后端抽象：probe-rs（进程内）与 OpenOCD（Tcl RPC）共用同一 trait。
//!
//! 设计对照原 Python 版 `core/backend.py`：
//! - `read_bytes` / `write_bytes`：监视与示波器的高频内存访问入口
//! - 断点地址一律清除 Thumb 位（`addr & !1`）
//! - 读失败区分「瞬时传输错误」（返回 Err 但连接保留）与「连接丢失」

use std::time::{Duration, Instant};

/// 示波突发帧：[(自突发开始的耗时, 块数据列表)]
pub type BurstFrames = Vec<(Duration, Vec<Vec<u8>>)>;

pub mod flash;
pub mod openocd;
#[cfg(windows)]
pub mod openocd_job;
pub mod probe;
pub mod probers_backend;
pub mod sim;
pub mod types;

pub use types::*;

/// 调试后端统一接口。实现者：`ProbeRsBackend`、`OpenOcdBackend`（后续阶段加入）。
pub trait DebugBackend: Send {
    fn connect(&mut self) -> Result<(), BackendError>;
    fn disconnect(&mut self);
    fn is_connected(&self) -> bool;

    fn read_bytes(&mut self, addr: u64, len: usize) -> Result<Vec<u8>, BackendError>;
    fn write_bytes(&mut self, addr: u64, data: &[u8]) -> Result<(), BackendError>;

    /// 读取一个 32 位调试寄存器（DEMCR / DWT_* / DFSR / CFSR / HFSR / BFAR）。
    ///
    /// **必须**走 32 位访问：ARMv7-M 的 CoreSight 调试寄存器只接受字访问，
    /// 字节访问会被静默忽略（真机实测 G431：经 `write_8` 写 DWT_COMP0
    /// 0xDEADBEEF 只落地最高字节，读回 0xDE000000；改 32 位后读回正确）。
    /// 默认实现基于 `read_bytes`，后端应覆盖为真正的字访问。
    fn read_u32(&mut self, addr: u64) -> Result<u32, BackendError> {
        let b = self.read_bytes(addr, 4)?;
        if b.len() != 4 {
            return Err(BackendError::Transfer(format!(
                "读取 0x{addr:08x} 期望 4 字节，得到 {}",
                b.len()
            )));
        }
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// 写入一个 32 位调试寄存器（理由同 `read_u32`）。
    fn write_u32(&mut self, addr: u64, value: u32) -> Result<(), BackendError> {
        self.write_bytes(addr, &value.to_le_bytes())
    }

    fn halt(&mut self) -> Result<(), BackendError>;
    fn resume(&mut self) -> Result<(), BackendError>;
    fn step(&mut self) -> Result<(), BackendError>;
    fn reset(&mut self) -> Result<(), BackendError>;
    /// 复位并停在复位向量：供"重下断点 → 恢复运行"的安全复位流程。
    /// 直接 reset(run) 后立即写 FPB 会与复位完成竞态，断点会被打丢。
    fn reset_and_halt(&mut self) -> Result<(), BackendError>;
    fn is_halted(&mut self) -> Result<bool, BackendError>;

    fn set_breakpoint(&mut self, addr: u64) -> Result<(), BackendError>;
    fn clear_breakpoint(&mut self, addr: u64) -> Result<(), BackendError>;

    /// 硬件断点配额（已用, 总数）。未知（如 OpenOCD 无法可靠查询）返回 None。
    fn hw_breakpoint_quota(&mut self) -> Option<(usize, usize)> {
        None
    }

    fn read_core_register(&mut self, name: &str) -> Result<u64, BackendError>;

    /// 示波突发采样：以 interval 为节拍连读 count 帧（节拍由实现内部精确
    /// 控制，probe-rs 覆盖后单帧的 Core 重建开销被摊薄）。返回每帧相对突发
    /// 起始时刻的偏移与数据（块顺序与 blocks 一致）。
    /// 默认实现：同步逐块读 + 节拍等待（Sim/无专门优化的后端可用）。
    /// 读失败（TransferError）会中断整批并返回 Err，由引擎限流上报。
    fn scope_burst(
        &mut self,
        blocks: &[(u64, usize)],
        count: usize,
        interval: Duration,
    ) -> Result<BurstFrames, BackendError> {
        let start = Instant::now();
        let mut out = Vec::with_capacity(count);
        for i in 0..count {
            let due = start + interval * (i as u32 + 1);
            let mut frame = Vec::with_capacity(blocks.len());
            for (addr, len) in blocks {
                frame.push(self.read_bytes(*addr, *len)?);
            }
            out.push((start.elapsed(), frame));
            let now = Instant::now();
            if now < due {
                std::thread::sleep(due - now);
            }
        }
        Ok(out)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum BackendError {
    /// 瞬时错误：本次操作失败但会话仍可用（对应 pyocd TransferError 分支）。
    #[error("transfer error: {0}")]
    Transfer(String),
    /// 连接已丢失，需要重建会话。
    #[error("connection lost: {0}")]
    ConnectionLost(String),
    #[error("not connected")]
    NotConnected,
    #[error("unsupported operation: {0}")]
    Unsupported(String),
    #[error("{0}")]
    Other(String),
}

impl BackendError {
    pub fn is_fatal(&self) -> bool {
        matches!(self, BackendError::ConnectionLost(_) | BackendError::NotConnected)
    }
}
