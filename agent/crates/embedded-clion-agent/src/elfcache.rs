//! ELF 索引缓存：路径 → ElfIndex，按 mtime 失效重建。
//! 解析一次需数百毫秒，全部查询走缓存。

use elf_info::ElfIndex;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

struct Entry {
    index: Arc<ElfIndex>,
    mtime: Option<SystemTime>,
}

pub struct ElfCache {
    entries: Mutex<HashMap<PathBuf, Entry>>,
    /// 最近一次 elf_load 的路径（多路径加载后查询仍指向正确的索引）
    last: Mutex<Option<PathBuf>>,
}

use std::sync::Arc;

impl Default for ElfCache {
    fn default() -> Self {
        Self {
            entries: Mutex::new(HashMap::new()),
            last: Mutex::new(None),
        }
    }
}

impl ElfCache {
    /// 取（必要时加载/重建）指定路径的索引；加载在调用方线程之外发生，由调用方决定是否放线程。
    pub fn get(&self, path: &Path) -> Result<Arc<ElfIndex>, String> {
        let canonical = path
            .canonicalize()
            .map_err(|e| format!("ELF 路径不存在: {} ({e})", path.display()))?;
        let mtime = std::fs::metadata(&canonical).ok().and_then(|m| m.modified().ok());
        let mut entries = self.entries.lock();
        if let Some(entry) = entries.get(&canonical) {
            if entry.mtime == mtime {
                return Ok(entry.index.clone());
            }
        }
        let index = ElfIndex::load(&canonical)
            .map_err(|e| format!("ELF 解析失败: {}: {e:?}", canonical.display()))?;
        let index = Arc::new(index);
        entries.insert(canonical.clone(), Entry { index: index.clone(), mtime });
        *self.last.lock() = Some(canonical);
        Ok(index)
    }

    pub fn loaded_path(&self) -> Option<PathBuf> {
        self.last.lock().clone()
    }
}
