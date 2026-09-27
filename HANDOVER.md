# 嵌入式调试插件（Embedded Debug Tools）架构设计与转交开发基线文档

## 1. 工程概况与核心架构

本项目为专为 JetBrains CLion 打造的嵌入式高速无干扰实时变量监视（Live Variable Watch）、实时示波器（Oscilloscope）与外设寄存器实时监视（Register Live Watch）调试套件。

- **架构模式**：采用 **CLion 插件前端（Kotlin） + Rust 高性能 Agent 后端（Sidecar 进程）** 的两层 Monorepo 架构。
- **通信机制**：CLion 插件与 Agent 之间通过本地 TCP（JSON-RPC 行协议，默认监听 127.0.0.1:44445 或随机就绪端口）通信；Agent 与下位机/调试器之间根据场景选用：
  1. **OpenOCD 协同后端**（主要推荐场景）：通过 OpenOCD 开放的 **Tcl RPC 端口（默认 6666）** 进行高速内存读写，与 CLion 原生 GDB 调试会话共存，不抢占物理硬件探针（Attach-Only 模式）；
  2. **probe-rs 独立后端**：在未启动 CLion 调试时，由 Agent 直接调用底层 USB 探针（DAP-Link / CMSIS-DAP / ST-Link WinUSB）进行 SWD 访问；
  3. **sim 仿真后端**：内置波形发生器与虚拟内存环境，用于脱机演示与自动化集成测试。
- **内存访问安全**：
  - 32 位原子字读取与对齐边界保护，彻底杜绝 Cortex-M 跨字节撕裂（Torn Read）与假毛刺；
  - 展开指针具有动态二级物理地址采样与空指针防御保护（`addr >= 0x1000L`）；
  - 外设寄存器支持相邻块批量合并读取（`gap <= 64`, `maxBlock <= 1024`）。

---

## 2. 目录结构与构建环境配置

### 2.1 Monorepo 目录结构

```
Embedded Debug tools/
├── plugin/               # 前端 CLion 插件工程 (Kotlin / IntelliJ Platform SDK, V1.2.14)
├── agent/                # 后端 Rust Agent 工作空间 (Rust 2021, v1.2.12)
│   └── crates/
│       ├── embedded-clion-agent/ # Sidecar TCP JSON-RPC 守护进程
│       ├── monitor/              # 高速采样引擎 (环形缓冲区, 1kHz 示波, 2~15Hz 监视)
│       ├── elf-info/             # DWARF 符号解析与结构体成员链 (gimli / object)
│       ├── debug-core/           # OpenOCD Tcl RPC 与 probe-rs 驱动
│       └── svd-info/             # CMSIS-SVD 外设模型解析
├── scripts/              # 辅助测试脚本与仿真工具
├── build.py              # 一键编译与测试脚本
├── package.py            # 官方发布与打包脚本 (生成含 Agent 的 Standalone 插件包)
└── release/              # 打包发布产物目录
```

### 2.2 构建环境准备

- **Java / JVM 运行环境**：
  - JDK 17 或 JDK 21（或 JetBrains CLion 自带的 JBR 运行时）；
  - 环境变量配置：可将 `JAVA_HOME` 指向 JDK 或 CLion 的 `jbr` 目录（例如 `C:\Program Files\JetBrains\CLion\jbr` 或 `E:\Software\JetBrains IDE\CLion\jbr`）；
- **Rust 编译环境**：
  - Rust 1.75+ Stable 工具链（MSVC 或 GNU 均可），确保 `cargo` 在系统 PATH 中；
  - 如使用独立 Rust 环境，可通过环境变量配置 `CARGO_HOME` 与 `RUSTUP_HOME`；
- **一键构建与测试命令**：
  - 运行全套测试：`python build.py --test`
  - 编译全部组件：`python build.py`
  - 打包 Standalone 插件：`python package.py`

---

## 3. 当前版本基线与状态（Plugin V1.2.14 / Agent v1.2.12）

当前官方稳定基线：
1. **`plugin`（前端插件，V1.2.14）**：
   - 彻底修复指针/结构体数组成员（如 `g_chassis_ptr._ctx.data[0].vx` 与 `data[1].vx`）物理地址计算与示波器判重冲突；
   - 示波器通道添加增加返回值校验与非法低地址防御提示；
   - 监视频率精简为 2/5/10/15Hz 四档实用档位，彻底打破 12Hz 上限天花板；
   - 单元测试全部通过。
2. **`agent`（后端 Rust Agent，v1.2.12）**：
   - DWARF 数组类型生成升级全递归步进 `shift_addresses(&mut m, elem_offset)`，彻底消除数组元素内部成员偏移缺失；
   - 活跃采样自适应高精度等待与 Windows `timeBeginPeriod(1)` 1ms 定时器注入；
   - 50 项工作区自动化单元与集成测试全部通过。
3. **分发打包产物**：
   - Standalone 发布包：`release/embedded-debug-plugin-V1.2.14-standalone.zip`（内含完整且赋予 0755 可执行权限的 `bin/embedded-clion-agent.exe`），用户在 CLion 中“Install Plugin from Disk...”安装即用。

---

## 4. 核心演进与重大缺陷修复总览 (V1.2.3 ~ V1.2.14)

### V1.2.14：彻底修复结构体数组成员偏移计算缺失与示波器添加失败缺陷
1. **后端 DWARF 数组结构体成员偏移全递归步进（`agent/crates/elf-info`）**：
   - **根因定位**：在 DWARF 调试信息解析器（`dwarf.rs`）处理数组类型（`DW_TAG_array_type`）时，对数组元素按下标克隆生成（`[0]`, `[1]`, ...）并赋值 `m.address = i * elem.size`，但遗漏了调用 `shift_addresses(&mut m, elem_offset)` 递归步进其内部子成员。导致对于结构体数组（如 `data[0]` 与 `data[1]`），其内部叶子成员（如 `vx`）仍然保持 0-based 初始相对偏移（104 / 0x68），造成 `data[0].vx` 与 `data[1].vx` 的相对偏移完全相同（均为 0x68）；
   - **全递归偏移步进**：升级为 `shift_addresses(&mut m, elem_offset)`，不仅正确设置元素基址，更完整递归步进非指针子成员与深层嵌套结构体字段，确保 `data[0].vx`（0x68）与 `data[1].vx`（0x7C）地址偏移毫厘不差；
   - **单元测试保障**：在 `elf-info` 中新增真实工程 ELF（`g4_tool_test.elf`）数组成员解析校验以及全局结构体数组成员跨步长（20字节）测试。
2. **前端物理地址解析与示波器去重/添加闭环保证（`LiveWatchPanel` / `AgentService` / `AddToScopeAction` / `ScopePanel`）**：
   - **精确物理地址映射与运行时动态指针解引用**：指针节点在实时变量监视树中展开时，`data[0].vx` 与 `data[1].vx` 分别计算出真实的非冲突目标内存物理地址（`parentPtrAddress + 104` 与 `parentPtrAddress + 124`），彻底消除同地址判重冲突；在 `AgentService` 中新增 `resolvePointerAddress`，在手动或代码右键添加指针成员时自动解析运行态物理地址；
   - **示波器通道添加严格低地址校验**：在 `AgentService.addScopeVariable` 中强制拦截 `address < 0x1000L`，防止未初始化的相对偏移被误添加为死通道；
   - **示波器添加反馈准确化与防呆**：`LiveWatchPanel` 上下文菜单“添加到示波器”对未解引用指针（`< 0x1000L`）显示禁用状态及悬浮提示，杜绝虚假添加提示；
   - **监视列表污染清理**：在 `AddToScopeAction` 与 `ScopePanel.promptAddChannel` 中，当指针未解引用导致添加示波器失败时，自动从 `watchItems` 中回退清理该非法监视项，防止相对偏移污染监视列表；
   - **符号成员链数组下标双向兼容**：优化 `AgentService.findNodeInElf`，支持兼容带方括号（`[0]`）与纯数字点号（`.0`）的数组成员查找；
   - **发布打包流程二进制新鲜度守护**：在 `package.py` 中引入自动触发 `cargo build --release -p embedded-clion-agent`，彻底杜绝打包发布携带陈旧 sidecar agent 导致修复未生效的风险。

### V1.2.13：突破 15Hz 监视 ~12Hz 瓶颈限制与精简 2/5/10/15 四档
1. **精简前端刷新率可选档位（2Hz / 5Hz / 10Hz / 15Hz）**：
   - 根据用户明确需求，全面精简实时变量监视面板（`LiveWatchPanel`）顶部下拉框与设置界面（`EmbeddedMonitorConfigurable`），仅保留 `2, 5, 10, 15` 四个档位；
   - 在设置模型中引入 `snapWatchFreq` 智能吸附与回退保护，无论历史配置、外部持久化或用户切换，均无缝就近映射吸附至合法四档之一；
2. **重构引擎主循环调度与优先级，根治示波突发头包阻塞（Head-of-Line Blocking）**：
   - 在 `agent/crates/monitor/src/lib.rs` 中，将微秒级低开销任务（实时变量监视 ~1-2ms 与目标状态探测 ~0.5ms）置于耗时较长的示波突发采样之前执行，杜绝监视采样到期后被 20~40ms 的示波阻塞窗口强行推迟；
   - 引入示波突发动态自适应配额：在开启高频监视（>=10Hz）时，将单次示波突发目标从 40ms 降至 20ms，并在下一次监视即将到期时自动将本次突发裁剪至剩余时间（`burst_target_us = time_to_watch`），为变量监视准点切入让路；
3. **活跃采样自适应高精度等待与 100ms 睡眠合并**：
   - 将主循环自适应等待扩展至全部活跃采样状态（包含变量监视与高频示波），等待上限放宽至 100ms；
   - 在剩余时间 <= 2.5ms 时采用 `spin_loop` 忙等到点，彻底消除 Windows 系统下短休眠（<2.5ms）带来的 2~15ms 线程调度与时钟中断过冲；
4. **进程级 1ms 高精度定时器注入**：
   - 在 `embedded-clion-agent` 启动入口无条件启用 `timeBeginPeriod(1)`，确保 sidecar 进程各线程享受高精度操作系统定时器支持。

### V1.2.12：无漂移锚点步进、跨会话配置持久化与精确超时衰减
1. **采样主循环无漂移锚点步进**：
   - 修复主循环基于基准锚点的累进时钟，引入 `(watch_interval / 5).max(5ms)` 冷却安全边限，2Hz/5Hz/10Hz/15Hz 实测频率误差 < 0.2%；
2. **Session 级配置持久化与连接前预设**：
   - `Session` 结构体持有 `watch_freq` / `scope_freq` / targets，未连接前接收配置正常返回 Ok，并在 `engine_connect` 启动引擎时第一时间注入。

### V1.2.8：外设寄存器实时监视窗口（Embedded Registers）
1. **CLion 原生寄存器实时查看能力扩展**：
   - 解决 CLion 原生 Registers 窗口仅支持断点暂停时静态查看、不支持运行态实时动态刷新的痛点；
   - 新增独立的“寄存器实时监视”ToolWindow（右侧边栏 `EmbeddedRegisters`，ID: `EmbeddedRegisters`）；
   - 在 GDB 调试会话激活时无缝注册调试标签页（`寄存器实时监视`，Tab ID: `11801`）；
2. **高性能 CMSIS-SVD 纯 Kotlin 解析引擎（`SvdParser` & `SvdModel`）**：
   - 全面支持外设继承（`derivedFrom`）与基地址重定位计算；
   - 支持寄存器数组扩展（`dim`, `dimIncrement`, `dimIndex`，如 `AFR[%s]` 展开为 `AFR0`, `AFR1`）；
   - 极致解析性能：实测解析 1.96MB 完整芯片 SVD（如 STM32G431）仅耗时 ~25ms。

### V1.2.7：示波器与变量监视双轨调度饥饿及指针高频闪烁根除
1. **修复示波高频采样导致变量监视（Live Watch）停止更新缺陷**；
2. **解决展开指针子成员数值在真实值与 `...` 之间高频交替闪烁缺陷**。

### V1.2.5：指针子成员实时动态监视架构与断点暂停保护
1. **指针目标动态二级采样架构**：
   - 展开指针节点时，动态向底层 Agent 注册该指针二级目标（地址 `ptrAddr`，大小 `pointeeSize`）；
   - 采集回内存后，按子成员相对于结构体的内部相对偏移精准切片填充；
   - 展开后的子成员自动计算出其绝对物理地址（`ptrAddr + offset`），支持右键“添加到示波器”。

### V1.2.4：复杂类型/嵌套类型、C++ 指针及前向声明解析引擎深度重构
1. **跨编译单元（CU）全局类型定义图纸预扫描（Global Type Def Cache）**；
2. **指针类型与子成员精准解析**；
3. **specification / abstract_origin 链式追溯（清除全部 unknown 变量）**。

### V1.2.3：跨机器安装 standalone 插件连接失败与超时缺陷彻底根治
1. **内置 Agent 可执行文件智能定位与回退查找**；
2. **消除调试自动启动初值短路与看门狗断点假死缺陷**；
3. **OpenOCD 固件烧录阻塞等待容差与多回环地址族并发探测**。

---

## 5. 开发、测试与交付规范

1. **构建与验证命令规范**：
   - 前端单元测试：`cd plugin && gradlew.bat test` 或 `python build.py --test`（所有测试 100% 通过）；
   - 后端测试：`cd agent && cargo test --workspace`（50 项测试 100% 通过）；
   - 打包发布：`python package.py`（自动生成 POSIX 规范目录结构的 standalone zip 包）；
   - 部署：解压/覆盖至 CLion 插件目录或在 IDE 中选择 `Install Plugin from Disk...`。
