//! probe-rs 烧录：Run / Program / Erase 三模式（对照原版 pyocd flash helper），
//! 进程内直接调用 CMSIS-Pack 烧录算法，无需二次进程。

use probe_rs::flashing::{DownloadOptions, FlashProgress, Format, ProgressEvent};
use probe_rs::{Permissions, Session};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FlashMode {
    Run,
    Program,
    Erase,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", rename_all_fields = "camelCase", tag = "kind")]
pub enum FlashEvent {
    Log { text: String },
    Progress { phase: String, percent: f64 },
    Done { success: bool, message: String },
}

/// 打开 probe-rs 会话：依序尝试可用探针（可按 serial 过滤）+ 指定目标。
pub fn open_session(target: &str, speed_hz: u32, serial: Option<&str>) -> Result<Session, String> {
    let (mut probe, _ident) = crate::probe::open_first_available(serial)?;
    let _ = probe.set_speed(speed_hz / 1000); // set_speed 单位 kHz
    let session = if target.trim().is_empty() {
        probe
            .attach(
                probe_rs::config::TargetSelector::Auto,
                // 与具名分支保持一致：擦除类操作不因权限差异在自动识别时失败
                Permissions::default().allow_erase_all(),
            )
            .map_err(|e| format!("自动附加失败（建议手动选择目标）: {e}"))?
    } else {
        probe
            .attach(target, Permissions::default().allow_erase_all())
            .map_err(|e| format!("附加目标 {target} 失败: {e}"))?
    };
    Ok(session)
}



/// 执行烧录。progress 回调在烧录线程中被调用。
pub fn flash_firmware(
    target: &str,
    speed_hz: u32,
    firmware: &Path,
    mode: FlashMode,
    serial: Option<&str>,
    on_event: impl Fn(FlashEvent) + Send + Sync + 'static,
) -> Result<(), String> {
    let mut session = open_session(target, speed_hz, serial)?;
    on_event(FlashEvent::Log {
        text: format!("已连接目标 {}", session.target().name),
    });

    match mode {
        FlashMode::Erase => {
            on_event(FlashEvent::Log { text: "整片擦除…".into() });
            let mut progress = FlashProgress::empty();
            probe_rs::flashing::erase_all(&mut session, &mut progress, false)
                .map_err(|e| format!("擦除失败: {e}"))?;
            on_event(FlashEvent::Log { text: "擦除完成".into() });
        }
        FlashMode::Program | FlashMode::Run => {
            let format = detect_format(firmware)?;
            let mut options = DownloadOptions::default();
            options.verify = true;

            use std::collections::HashMap;
            use std::sync::atomic::Ordering;
            let cb = std::sync::Arc::new(on_event);
            let cb2 = cb.clone();
            // 各阶段独立累计（fill/erase/program/verify）
            let totals = parking_lot::Mutex::new(HashMap::<String, (u64, u64)>::new());
            let last_emit = std::sync::atomic::AtomicU64::new(0);
            options.progress = FlashProgress::new(move |event| {
                match event {
                    ProgressEvent::AddProgressBar { operation, total } => {
                        let mut guard = totals.lock();
                        let entry = guard.entry(format!("{operation:?}")).or_insert((0, 0));
                        if let Some(t) = total {
                            entry.0 = t.max(1);
                        }
                    }
                    ProgressEvent::Started(operation) => {
                        // 节流基准按阶段复位：否则上一阶段到 100 后本阶段
                        // pct >= last+2 永不成立，进度条 0% 直跳 100%
                        last_emit.store(0, Ordering::Relaxed);
                        cb2(FlashEvent::Progress {
                            phase: format!("{operation:?}").to_lowercase(),
                            percent: 0.0,
                        });
                    }
                    ProgressEvent::Progress { operation, size, .. } => {
                        let mut guard = totals.lock();
                        let entry = guard.entry(format!("{operation:?}")).or_insert((0, 0));
                        entry.1 += size;
                        let percent = if entry.0 > 0 {
                            (entry.1 as f64 / entry.0 as f64 * 100.0).min(100.0)
                        } else {
                            0.0
                        };
                        // 节流：至少 2% 间隔再发
                        let pct_int = percent as u64;
                        if pct_int >= last_emit.load(Ordering::Relaxed) + 2 || pct_int >= 100 {
                            last_emit.store(pct_int, Ordering::Relaxed);
                            cb2(FlashEvent::Progress {
                                phase: format!("{operation:?}").to_lowercase(),
                                percent,
                            });
                        }
                    }
                    ProgressEvent::Finished(operation) => {
                        cb2(FlashEvent::Progress {
                            phase: format!("{operation:?}").to_lowercase(),
                            percent: 100.0,
                        });
                    }
                    ProgressEvent::Failed(operation) => {
                        cb2(FlashEvent::Log {
                            text: format!("阶段失败: {operation:?}"),
                        });
                    }
                    ProgressEvent::DiagnosticMessage { message } => {
                        let text = message.trim_end().to_string();
                        if !text.is_empty() {
                            cb2(FlashEvent::Log { text });
                        }
                    }
                    _ => {}
                }
            });

            probe_rs::flashing::download_file_with_options(
                &mut session,
                firmware,
                format,
                options,
            )
            .map_err(|e| format!("烧录失败: {e}"))?;
            cb(FlashEvent::Log { text: "烧录完成，校验通过".into() });

            if mode == FlashMode::Run {
                let mut core = session.core(0).map_err(|e| e.to_string())?;
                core.reset().map_err(|e| format!("复位失败: {e}"))?;
                cb(FlashEvent::Log { text: "目标已复位运行".into() });
            }
        }
    }
    Ok(())
}

fn detect_format(path: &Path) -> Result<Format, String> {
    match path
        .extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_lowercase())
        .as_deref()
    {
        Some("elf") | Some("axf") | None => Ok(Format::Elf(Default::default())),
        Some("hex") => Ok(Format::Hex),
        Some("bin") => Ok(Format::Bin(Default::default())),
        Some(other) => Err(format!("不支持的固件格式: .{other}")),
    }
}
