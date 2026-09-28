# Embedded Monitor for CLion

在 CLion 原生嵌入式调试能力（断点、寄存器、外设 SVD 视图等）**之上**，附加 CLion 缺失的
**实时监视（Live Watch）** 与 **示波器（Scope）** 两个工具窗口。调试核心复用 Monorepo 中 `agent` 的 `debug-core` / `monitor` /
`elf-info` 三个 crate，以 sidecar 进程（`embedded-clion-agent`）形式被插件拉起。

## 架构

```
┌─ CLion ──────────────────────────────────────────────┐
│  原生调试器（断点/单步/寄存器/SVD 外设视图）—— 不动      │
│                                                      │
│  Embedded Monitor 插件（本工程，Kotlin）               │
│   ├─ 实时监视工具窗   LiveWatch  （右侧）               │
│   ├─ 示波器工具窗     Scope      （底部）               │
│   ├─ AgentService ←─ TCP JSON 行协议 (127.0.0.1) ──┐  │
│   └─ ElfAutoResolver（自动探测 ELF）                │  │
└─────────────────────────────────────────────────────┼──┘
                                                      ▼
                                   embedded-clion-agent.exe（Rust sidecar）
                                    ├─ monitor 引擎（Watch 2~15Hz / Scope 1kHz
                                    │   / 块合并 / 带宽模型）
                                    ├─ debug-core（probe-rs 直连 / OpenOCD Tcl RPC / Sim）
                                    └─ elf-info（DWARF：变量树/成员链/类型反查）
                                                      │
                                                      ▼ SWD / Tcl RPC :6666
                                              目标板（Cortex-M）
```

### 与 CLion 调试共存（关键设计）

- **attach-only 模式（默认）**：采样后端选 `openocd` 时，agent 只连接 **CLion 已启动的
  OpenOCD 的 Tcl RPC 端口（6666）**，不抢占 probe。断点、单步、寄存器照常用 CLion 的
  「OpenOCD 下载并运行」，监视/示波在目标运行中照常读内存（DAP 读不打断 CPU）。
- **probe-rs 模式**：适合不调试时的纯监视/示波（非复位 attach，目标保持运行）。
  与任何占用 probe 的调试器**互斥**——同时调试请用 openocd 模式。
- **sim 模式**：无硬件仿真（内置信号发生器），用于体验/自测，无需连接板子。

### 符号表路径自动读取 CLion 构建配置

按序探测，取第一个命中：

1. 设置中的「ELF 路径覆盖」（手动指定时短路自动探测）；
2. **CMake File API**：候选构建目录下 `.cmake/api/v1/reply/index-*.json`（取最新）→
   `codemodel` → `type=EXECUTABLE` 目标 → `artifacts`（兼容 CLion 的数组型 `objects`
   与文档的对象型两种形态）；
3. **构建目录扫描**：`cmake-build*`、`build`、`build/<preset>`（CMakePresets binaryDir）、
   `.idea/cmake.xml` 的 generationDir，按修改时间取最新 `.elf`/`.axf`。

ELF 变化自动重载（mtime 检测），监视项按表达式自动重解析。

## 构建

### 1. Agent（Rust sidecar）

Agent 源码位于 `../agent` 目录下：

```powershell
cd ../agent
cargo build --release -p embedded-clion-agent
# 产物：target\release\embedded-clion-agent.exe
```

### 2. 插件与完整打包

```powershell
# 编译插件：
./gradlew buildPlugin

# 推荐一键生成含 Agent 的 Standalone 完整离线发布包（在工程根目录运行）：
python ../package.py
# 产物：release\embedded-debug-plugin-<版本号>-standalone.zip
```

本地试运行：`gradlew runIde`（沙箱 CLion，从 Marketplace 安装 zip 亦可）。

### 3. Agent 路径

插件按序自动探测 agent：设置路径 → 环境变量 `EMBEDDED_CLION_AGENT` →
插件内置 `bin/embedded-clion-agent.exe`（Standalone 安装包自带） → 开发目录默认路径 → `PATH`。一般无需手动配置。

## 使用

1. 打开 CMake 嵌入式工程（如 `g4_tool_test`），插件自动加载构建目录里的 ELF。
2. 打开右侧 **EmbeddedLiveWatch** 工具窗 →「启动监视」：
   - 后端 `openocd` + attach-only：先点 CLion 的「OpenOCD 下载并运行」开始调试，
     再启动监视（与调试共存）；
   - 后端 `probe-rs`：不调试时直接启动（芯片名留空 = 自动识别）；
   - 后端 `sim`：无硬件体验。
3. 添加监视：工具窗「添加表达式」/「从符号树添加」，或编辑器右键
   *Embedded Monitor → 添加到实时监视*。表达式语法：
   - 符号名：`g_counter`（含 GCC static 后缀 `cnt.1` 自动匹配）
   - 结构体与指针成员链：`g_state.pos`、`ptr->member`、`array[0].val`
   - 指针动态解引用：监视树中直接展开指针（如 `g_chassis_ptr`），自动解析运行时物理地址并动态发起二级采样与内部字段切片映射
   - 类型 @ 地址：`float @ 0x20000000`
   - 地址 : 类型：`0x20000004:f32`；裸地址默认 u32 并用 DWARF 反查真实类型
4. 右键监视项 →「添加到示波器」，在底部 **EmbeddedScope** 窗口设置采样率（probe-rs
   最高 1kHz / OpenOCD ~870Hz，引擎自动做块合并与带宽预估，超 750KB/s 会告警）。
5. 波形：拖动平移 / 滚轮缩放 / 双击复位 / 悬停游标读数 / 右键导出 CSV。

## 协议（插件 ↔ agent）

本地 TCP JSON 行（每行一个 JSON）。请求 `{"id":1,"method":"...","params":{...}}`；
响应 `{"id":1,"ok":true,"result":...}` / `{"id":1,"ok":false,"error":"..."}`；
事件 `{"event":"engine","data":{"kind":"watchData",...}}`（转发 monitor 引擎 Event）。

| 方法 | 参数 | 说明 |
|---|---|---|
| `ping` | – | 握手 |
| `list_probes` / `list_targets` | `{filter?}` | 调试探针 / 芯片库 |
| `connect` | `{backend, target?, cfgFile?, openocdPath?, scriptsDir?, speedHz, attachOnly?, tclPort?}` | 启动引擎（attach-only 时预检 Tcl 端口） |
| `disconnect` / `status` | – | |
| `set_watch_targets` | `{targets:[{id,addr,size,autoRefresh}]}` | 实时监视目标 |
| `set_scope_targets` / `set_scope_freq` | `{targets:[{addr,size}]}` / `{freq}` | 示波通道与采样率（1..5000Hz） |
| `read_mem` / `write_mem` | `{addr,size}` / `{addr,data}` | 同步读写（≤64KiB） |
| `check_bandwidth` | `{targets:[[addr,size],..], freq}` | 带宽预估 |
| `elf_load` | `{path}` | DWARF 索引（全量变量树） |
| `elf_resolve` | `{expr}` | 符号/成员链 → SymbolNode |
| `elf_type_at_addr` | `{addr}` | 地址反查类型（成员叶子） |

事件 kind：`connected` `disconnected` `state` `watchData`（5Hz）`scopeData`（33ms 批量）
`error` `log`。

## 测试

```powershell
# 运行插件单元测试：
./gradlew test

# 或在根目录运行前后端全套测试：
python ../build.py --test
```

## 已知限制

- OpenOCD 后端的 attach 固定连接 Tcl RPC 6666（CLion 的 OpenOCD 运行配置不暴露该端口，
  默认即 6666）；`gdb-port`/`telnet-port` 等自定义不受影响。
- probe-rs 后端在部分 DAPLink（如 Flash Pro 0D28:0204）+ STM32G431 组合上**自动识别芯片
  会失败**（"Unable to load specification for chip"），在设置的「probe-rs 芯片名」里填
  `STM32G431CBTx` 即可；OpenOCD 后端不受影响（插件默认后端）。
- 函数调用与运行时堆分配不在范围内（运行中的函数求值请使用 CLion 原生调试器的 Watch）；指针动态解引用与子成员展开监视在 V1.2.5+ 已全面支持（采用运行时二级物理目标动态采样，完全不打断 CPU 运行）。
- 监视外设寄存器地址（autoRefresh 开）时注意某些外设寄存器读有副作用（如清标志位），
  可在监视表中关闭该条目的「运行中刷新」。
- Windows 下 release 版 agent 编译为 windows 子系统（无控制台窗口闪现）；stdout 仅在
  管道下输出就绪行。
