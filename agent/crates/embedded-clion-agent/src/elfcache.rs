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
        const MAX_ENTRIES: usize = 16;
        let canonical = path
            .canonicalize()
            .map_err(|e| format!("ELF 路径不存在: {} ({e})", path.display()))?;
        let mtime = std::fs::metadata(&canonical).ok().and_then(|m| m.modified().ok());
        // 快路径：命中且未变，直接返回
        {
            let entries = self.entries.lock();
            if let Some(entry) = entries.get(&canonical) {
                if entry.mtime == mtime {
                    return Ok(entry.index.clone());
                }
            }
        }
        // 慢路径：ElfIndex::load 需数百毫秒，必须在锁外执行——elf_resolve /
        // elf_type_at_addr 在主读循环线程上同步调 get，锁内加载会让读循环停摆
        let index = ElfIndex::load(&canonical)
            .map_err(|e| format!("ELF 解析失败: {}: {e:?}", canonical.display()))?;
        let index = Arc::new(index);
        let mut entries = self.entries.lock();
        // 双检：加载期间其他线程可能已重建同一索引，复用避免重复持有两份
        if let Some(entry) = entries.get(&canonical) {
            if entry.mtime == mtime {
                *self.last.lock() = Some(canonical);
                return Ok(entry.index.clone());
            }
        }
        // 简单容量上限：长会话加载大量不同路径时防止内存单调增长。
        // 只逐出一个非活跃条目——clear() 全清会连 last 指向的当前索引一起删，
        // 下一条查询立刻未命中、在读循环线程上重新全量解析（自我破坏缓存）
        if entries.len() >= MAX_ENTRIES && !entries.contains_key(&canonical) {
            let last_path = self.last.lock().clone();
            let victim = entries
                .keys()
                .find(|k| Some(k.as_path()) != last_path.as_deref())
                .cloned();
            match victim {
                Some(v) => {
                    entries.remove(&v);
                }
                // 理论不可达（last 只是其中之一），兜底防死循环
                None => entries.clear(),
            }
        }
        entries.insert(canonical.clone(), Entry { index: index.clone(), mtime });
        *self.last.lock() = Some(canonical);
        Ok(index)
    }

    pub fn loaded_path(&self) -> Option<PathBuf> {
        self.last.lock().clone()
    }
}
