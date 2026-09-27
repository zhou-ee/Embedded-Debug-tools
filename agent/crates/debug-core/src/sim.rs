//! 仿真调试后端：无硬件全链路测试（监视 / 断点 / 步进 / 示波 / 烧录流程）。
//!
//! 内存模型：
//! - `0x2000_0000..0x2000_0100`：动态信号区（按时间生成，见 `gen_byte`）
//!   - +0x00 u32 毫秒计数    +0x04 f32 正弦(1Hz,±100)  +0x08 f32 余弦
//!   - +0x0C i16 锯齿(±1000) +0x10 u8 方波(2Hz)        +0x14 u32 慢计数(10Hz)
//! - 其余 RAM（64KB）：静态可写内存，写入后可读回
//!
//! 断点模型：resume 后 ~400ms 自动"命中"地址最小的断点（pc 停在该地址）。

use parking_lot::Mutex;
use std::collections::BTreeSet;
use std::time::Instant;

use crate::{BackendError, DebugBackend};

pub const SIM_RAM_BASE: u64 = 0x2000_0000;
pub const SIM_RAM_SIZE: usize = 64 * 1024;
pub const SIM_SIGNAL_SIZE: u64 = 0x100;
pub const SIM_FLASH_BASE: u64 = 0x0800_0000;
const HIT_DELAY_MS: u64 = 400;

struct SimState {
    halted: bool,
    frozen_at: f64,
    pc: u64,
    breakpoints: BTreeSet<u64>,
    ram: Vec<u8>,
    pending_hit: Option<(Instant, u64)>,
}

pub struct SimBackend {
    connected: bool,
    epoch: Instant,
    state: Mutex<SimState>,
}

impl Default for SimBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl SimBackend {
    pub fn new() -> Self {
        Self {
            connected: false,
            epoch: Instant::now(),
            state: Mutex::new(SimState {
                halted: false,
                frozen_at: 0.0,
                pc: SIM_FLASH_BASE + 0x100,
                breakpoints: BTreeSet::new(),
                ram: vec![0u8; SIM_RAM_SIZE],
                pending_hit: None,
            }),
        }
    }

    fn now(&self) -> f64 {
        self.epoch.elapsed().as_secs_f64()
    }

    /// 动态信号区取值（t 秒时刻，off 为区内偏移）。
    fn gen_byte(t: f64, off: u64) -> u8 {
        let word_base = off & !3;
        let byte_idx = (off & 3) as u32;
        let w = Self::gen_word(t, word_base);
        ((w >> (8 * byte_idx)) & 0xff) as u8
    }

    fn gen_word(t: f64, word_off: u64) -> u32 {
        match word_off {
            0x00 => (t * 1000.0) as u32,
            0x04 => f32::to_bits(((t * std::f64::consts::TAU).sin() * 100.0) as f32),
            0x08 => f32::to_bits(((t * std::f64::consts::TAU).cos() * 100.0) as f32),
            0x0C => {
                // i16 锯齿 ±1000，周期 2s；高 16 位补符号
                let phase = (t / 2.0).fract();
                let v = ((phase * 2000.0) - 1000.0) as i16;
                v as u16 as u32
            }
            0x10 => {
                // u8 方波 2Hz
                if (t * 2.0).fract() < 0.5 {
                    1
                } else {
                    0
                }
            }
            0x14 => (t * 10.0) as u32,
            _ => {
                // 其余信号区字：地址相关相位的正弦（保证任意地址都有活动数据）
                let phase = word_off as f64 * 0.37;
                f32::to_bits((((t + phase) * 3.0).sin() * 50.0) as f32)
            }
        }
    }
}

impl DebugBackend for SimBackend {
    fn connect(&mut self) -> Result<(), BackendError> {
        self.connected = true;
        Ok(())
    }

    fn disconnect(&mut self) {
        self.connected = false;
    }

    fn is_connected(&self) -> bool {
        self.connected
    }

    fn read_bytes(&mut self, addr: u64, len: usize) -> Result<Vec<u8>, BackendError> {
        if !self.connected {
            return Err(BackendError::NotConnected);
        }
        let mut state = self.state.lock();
        // halt 时信号冻结在停住时刻（对照真实目标停住内存不变）
        let t = if state.halted { state.frozen_at } else { self.now() };
        let mut out = Vec::with_capacity(len);
        for i in 0..len as u64 {
            let a = addr + i;
            if (SIM_RAM_BASE..SIM_RAM_BASE + SIM_SIGNAL_SIZE).contains(&a) {
                out.push(Self::gen_byte(t, a - SIM_RAM_BASE));
            } else if (SIM_RAM_BASE..SIM_RAM_BASE + SIM_RAM_SIZE as u64).contains(&a) {
                out.push(state.ram[(a - SIM_RAM_BASE) as usize]);
            } else if a >= SIM_FLASH_BASE && a < SIM_FLASH_BASE + 0x10000 {
                // 模拟 flash：NOP 图样（0x00BF Thumb NOP）
                out.push(if a % 2 == 0 { 0xBF } else { 0x00 });
            } else {
                out.push(0);
            }
        }
        // 触发 pending 命中检查（读内存是引擎轮询的一部分）
        if let Some((when, bp)) = state.pending_hit {
            if Instant::now() >= when {
                state.halted = true;
                state.frozen_at = self.now();
                state.pc = bp;
                state.pending_hit = None;
            }
        }
        Ok(out)
    }

    fn write_bytes(&mut self, addr: u64, data: &[u8]) -> Result<(), BackendError> {
        if !self.connected {
            return Err(BackendError::NotConnected);
        }
        let mut state = self.state.lock();
        for (i, b) in data.iter().enumerate() {
            let a = addr + i as u64;
            if (SIM_RAM_BASE..SIM_RAM_BASE + SIM_RAM_SIZE as u64).contains(&a) {
                let off = (a - SIM_RAM_BASE) as usize;
                state.ram[off] = *b;
            }
        }
        Ok(())
    }

    fn halt(&mut self) -> Result<(), BackendError> {
        let now = self.now();
        let mut state = self.state.lock();
        if !state.halted {
            state.halted = true;
            state.frozen_at = now;
            // 停住位置：模拟主循环内某处
            state.pc = SIM_FLASH_BASE + 0x100 + (((now * 1000.0) as u64) % 32) * 2;
            state.pending_hit = None;
        }
        Ok(())
    }

    fn resume(&mut self) -> Result<(), BackendError> {
        let mut state = self.state.lock();
        state.halted = false;
        // 有断点则安排自动命中（最小地址）
        state.pending_hit = state
            .breakpoints
            .iter()
            .next()
            .copied()
            .map(|bp| (Instant::now() + std::time::Duration::from_millis(HIT_DELAY_MS), bp));
        Ok(())
    }

    fn step(&mut self) -> Result<(), BackendError> {
        let now = self.now();
        let mut state = self.state.lock();
        state.halted = true;
        state.frozen_at = now;
        state.pc += 2;
        Ok(())
    }

    fn reset(&mut self) -> Result<(), BackendError> {
        let mut state = self.state.lock();
        state.pc = SIM_FLASH_BASE + 0x100;
        state.halted = false;
        state.pending_hit = state
            .breakpoints
            .iter()
            .next()
            .copied()
            .map(|bp| (Instant::now() + std::time::Duration::from_millis(HIT_DELAY_MS), bp));
        Ok(())
    }

    fn reset_and_halt(&mut self) -> Result<(), BackendError> {
        let now = self.now();
        let mut state = self.state.lock();
        state.pc = SIM_FLASH_BASE + 0x100;
        state.halted = true;
        state.frozen_at = now;
        state.pending_hit = None;
        Ok(())
    }

    fn is_halted(&mut self) -> Result<bool, BackendError> {
        let now = self.now();
        let mut state = self.state.lock();
        if let Some((when, bp)) = state.pending_hit {
            if Instant::now() >= when {
                state.halted = true;
                state.frozen_at = now;
                state.pc = bp;
                state.pending_hit = None;
            }
        }
        Ok(state.halted)
    }

    fn set_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        self.state.lock().breakpoints.insert(addr & !1);
        Ok(())
    }

    fn clear_breakpoint(&mut self, addr: u64) -> Result<(), BackendError> {
        self.state.lock().breakpoints.remove(&(addr & !1));
        Ok(())
    }

    fn read_core_register(&mut self, name: &str) -> Result<u64, BackendError> {
        let now = self.now();
        let state = self.state.lock();
        let t = if state.halted { state.frozen_at } else { now };
        let tick = (t * 1000.0) as u64;
        Ok(match name.to_lowercase().as_str() {
            "pc" => state.pc,
            "sp" => SIM_RAM_BASE + SIM_RAM_SIZE as u64 - 0x20,
            "lr" => SIM_FLASH_BASE + 0x201,
            "xpsr" => 0x0100_0000,
            r if r.starts_with('r') => {
                let n: u64 = r[1..].parse().unwrap_or(0);
                n * 0x111 + (tick & 0xff)
            }
            _ => 0,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signal_area_changes_over_time() {
        let mut sim = SimBackend::new();
        sim.connect().unwrap();
        let a = sim.read_bytes(SIM_RAM_BASE, 4).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(30));
        let b = sim.read_bytes(SIM_RAM_BASE, 4).unwrap();
        assert_ne!(a, b, "毫秒计数应随时间变化");
    }

    #[test]
    fn ram_write_read_back() {
        let mut sim = SimBackend::new();
        sim.connect().unwrap();
        sim.write_bytes(SIM_RAM_BASE + 0x1000, &[1, 2, 3, 4]).unwrap();
        assert_eq!(sim.read_bytes(SIM_RAM_BASE + 0x1000, 4).unwrap(), vec![1, 2, 3, 4]);
    }

    #[test]
    fn halt_freezes_signals() {
        let mut sim = SimBackend::new();
        sim.connect().unwrap();
        sim.halt().unwrap();
        let a = sim.read_bytes(SIM_RAM_BASE, 8).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(30));
        let b = sim.read_bytes(SIM_RAM_BASE, 8).unwrap();
        assert_eq!(a, b, "halt 时信号应冻结");
        assert!(sim.is_halted().unwrap());
    }

    #[test]
    fn breakpoint_hits_after_resume() {
        let mut sim = SimBackend::new();
        sim.connect().unwrap();
        sim.set_breakpoint(0x0800_0140).unwrap();
        sim.halt().unwrap();
        sim.resume().unwrap();
        assert!(!sim.is_halted().unwrap());
        std::thread::sleep(std::time::Duration::from_millis(HIT_DELAY_MS + 150));
        assert!(sim.is_halted().unwrap(), "断点应自动命中");
        assert_eq!(sim.read_core_register("pc").unwrap(), 0x0800_0140);
    }

    #[test]
    fn step_advances_pc() {
        let mut sim = SimBackend::new();
        sim.connect().unwrap();
        sim.halt().unwrap();
        let pc0 = sim.read_core_register("pc").unwrap();
        sim.step().unwrap();
        assert_eq!(sim.read_core_register("pc").unwrap(), pc0 + 2);
    }
}
