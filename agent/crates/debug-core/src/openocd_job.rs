//! Windows Job Object 孤儿进程保护（仅 cfg(windows) 编译）。
//!
//! agent 进程若崩溃/被强杀，`Drop` 不会执行，自启的 OpenOCD 会变成孤儿进程
//! 继续占用 USB 探针和 Tcl 端口。把 openocd 挂进 `KILL_ON_JOB_CLOSE` 的
//! Job Object 后，agent 进程退出时内核关闭 Job 句柄，连带终止 OpenOCD。

#![cfg(windows)]

use std::os::windows::io::AsRawHandle;
use windows_sys::Win32::Foundation::CloseHandle;
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};

pub(crate) struct Job(windows_sys::Win32::Foundation::HANDLE);

// 内核句柄进程内全局有效，跨线程转移安全；OpenOcdBackend 需要 Send（引擎线程独占）
unsafe impl Send for Job {}

impl Job {
    pub fn create() -> Result<Self, String> {
        unsafe {
            let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if handle.is_null() {
                return Err("CreateJobObjectW 失败".into());
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ret = SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const core::ffi::c_void,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            if ret == 0 {
                CloseHandle(handle);
                return Err("SetInformationJobObject 失败".into());
            }
            Ok(Job(handle))
        }
    }

    pub fn assign(&self, child: &std::process::Child) -> Result<(), String> {
        unsafe {
            if AssignProcessToJobObject(self.0, child.as_raw_handle() as _) == 0 {
                return Err("AssignProcessToJobObject 失败".into());
            }
            Ok(())
        }
    }
}

impl Drop for Job {
    fn drop(&mut self) {
        // 关闭句柄即触发 KILL_ON_JOB_CLOSE（openocd 已退出时为无害 no-op）
        unsafe { CloseHandle(self.0) };
    }
}
