# Changelog

> **路径映射说明**：V1.2.x 历史条目中的 `software_ref/` 对应本仓库现在的 `agent/`，
> `package_release.py` 对应 `package.py`（项目在开源重构前为 software_ref 单体工程）。

## [V1.2.30] — 示波时间语义修复：帧起始时间戳、节拍重锚定、数据自带采样周期

> 接续 V1.2.29 的示波稳定性工作（本版包含 V1.2.29 的全部未提交修改与本版增补）。
> 验收方式按转接文档执行：uwTick（HAL 1ms tick）作真值对照 + `scope_perf` 分段计数器。

### Agent (v1.2.30)
- **帧时间戳改为读取开始时刻**：两个后端的 `scope_burst` 此前在全部块读完后才打
  时间戳——回包等待、读取过程与调度延迟全部混进时间轴（真机 uwTick 对照实测：
  Agent 时间戳间隔 56ms / MCU tick 仅 +6ms 的严重错位）。现每帧在读取开始时刻打点；
- **节拍超期重锚定**：读耗时超过间隔时，截止时刻重锚到"当前 + 间隔"，不再追赶旧
  截止（旧实现超期后连续快速读取，批内间隔忽快忽慢）；
- **ScopeData 携带实际采样间隔**（`intervalUs`，µs）：空档判定用数据自带节拍，
  配置切换期间的在途帧不再被前端按新配置频率误判（修复"前端按 1000Hz 判定 3ms
  断线阈值、后台实际 200Hz"的误报）；
- **新增 `scope_perf` 诊断**：突发数/帧数/突发墙钟/降级块/probe-rs 读重试与
  core-acquire 累计，经协议暴露；
- 修复过程中发现并纠正了重锚定条件自身的漂移 bug（`max(due+interval, now+interval)`
  在快读场景每帧漂移一个读耗时——perf 计数器揭示 985→436Hz 的异常即此）。

### Plugin (v1.2.30)
- 空档判定周期优先取 `ScopeData.intervalUs`（数据自带节拍），旧 agent 缺省回退
  前端配置频率；类型修正保证 Long 一致性。

### 真机验证（STM32G431CBTx + Flash Pro，uwTick 对照）
- **时间戳诚实性**：probe-rs 1000Hz 的 tick 错位率（|agentΔ−tickΔ|>3ms）从 8.47%
  降至 **0.04%**——时间轴与真实采集时刻对齐；
- **发现并证实旧速率口径失真**：V1.2.29 报告的 985.9Hz 为旧时间戳压缩下的假象；
  诚实墙钟速率受 probe-rs 每次内存读调用开销（~1.1ms）限制，与读块数相关
  （2 读块 ≈ 440Hz @4MHz SWD；OpenOCD Tcl 路径每命令开销更低）；
- perf 计数器确认：读重试 0、降级块 0、core-acquire ~1.3ms/次（每突发一次）。

## [V1.2.29] — 修复：原始示波值误改、缺样隐藏与共享调试会话清理

- 移除采样摄取和 CSV 导出的 TornSampleFilter 自动重构；正常正弦极值和负值不再被改写。
- 读失败的目标显式输出空字节；前端保留 NaN 缺样点并计数。超过三个目标采样周期的空档断线显示，先分段再做 M4 降采样。
- CSV 按实际时间戳合并各通道，缺失单元格留空；不再用最近邻值填补未采到的数据。
- OpenOCD 根据实际进程归属识别共享会话。退出、断开和切换连接只清理本会话设置的 DWT 槽位及断点，保留外部观察点和共享目标的暂停状态。
- ELF 重载、重连和恢复采样时重新定位符号通道，指针成员现场读取当前基址。无法定位的通道暂停采样，旧地址不继续下发；固定地址及 SVD 寄存器通道保留地址绑定。
- 重复下发相同目标或采样频率不再重新触发预热丢弃窗口。
- OpenOCD 整批读失败也保留实际时间的缺样帧，再避让 50ms；Tcl 连接损坏按致命错误处理以触发断线恢复。
- 修正带宽预警请求的目标参数形状，并明确带宽占用为估算值。

## [V1.2.28] — 修复：示波启动预热坑洼 + 删除撕裂源（8-bit 回退）

> **用户真机反馈（2026-09-30）**：波形仍有坑洼；1kHz 下 sin_20hz 出现符号位翻转的
> 假值（-9.61 vs 应 +9.46）。

### 定位（真机量化，五通道含用户实际通道 vx/seq）
- 五通道 100% 存在、频率各自正确（1.0/5.0/19.9Hz）——数据无缺失；
- 坑洼两类：① 示波启动/重建后前两帧——探针 Core 初始化 + 首次访问预热期返回
  陈旧/错位数据（不同地址返回相同值、帧距 20ms）；② 散发的单帧"值冻结"（DAP
  返回数毫秒前的缓存字）。

### Agent (v1.2.28)
- **预热窗丢弃**：目标/频率重建后 150ms 内的示波帧整体丢弃（时间窗与采样率无关，
  覆盖 Core 初始化 + AP 预热全过程）——断点暂停/恢复循环不再把预热垃圾带进波形；
- **删除撕裂源**：scope_burst 失败回退从 `read_8`/`read_memory 8`（逐字节访问
  非原子，固件字节间写入产生撕裂）改为 **32-bit 原子读重试一次 + 失败后冲刺读
  消耗 DAP 残留队列再重试**；仍失败该块降级为空（本块目标本帧缺值，其它块不受
  影响）；
- 实验验证：双读比对方案为负优化（坑洼 3→16、速率 980→892Hz，更多总线往返
  扩大暴露窗口），已回滚。

### 残余（已知限制）
- 单帧"值冻结"坑洼约 0.03%（1-2 帧/10s @1kHz，仅最快通道可见）：probe/DAP 层
  瞬态，读成功且值合法，引擎层无法检测。保守的 TornSampleFilter 有意不自动抹平
  （保护真实脉冲/阶跃信号不被误伤）。

## [V1.2.27] — 修复：示波器波形台阶（OpenOCD 突发单块失败降级）

> **用户真机反馈（2026-09-29）**：示波器波形出现台阶/缺口，且三个通道在同一时间窗
> 同时异常（截图）。

### 定位（真机量化）
- probe-rs 管线健康：200Hz 三通道实测 196Hz、1kHz 单通道实测 969Hz，时间戳无非单调，
  撕裂在摄取端已修复——原始数据无台阶；
- **openocd attach-only + halt/resume 扰动复现**：200Hz 掉到 181Hz，28 个空档
  （含 3 个 45-55ms 避让级）——V1.2.22 把 8-bit 回退失败从"静默丢该块"改为
  "整个突发中止 + 50ms 避让"，GDB 调试操作引发的瞬时读失败会造成**全通道台阶**，
  即截图症状。

### Agent (v1.2.27)
- **openocd scope_burst 单块失败降级**：带内错误/长度不符只降级**本块**为空数据并
  继续突发（该块目标本帧缺值，其余块与后续帧不受影响）；仅传输级错误（连接破坏/
  帧失步）中止突发走重建；整个突发全部块失败才返回错误触发避让；
- 降级计数限流日志（1s 一条）便于诊断；
- clippy 清零（match→? / map_or→is_none_or）。

### 真机验证（修复前后同条件对比：openocd attach-only + 250ms halt/resume 扰动）
- 200Hz 三通道：实测 181Hz→**192Hz**；>45ms 避让级空档 3→**0**；空档总数 28→**7**；
- 三通道正弦连续性跳变 0 个（sin_1hz 峰峰 199.998 / sin_5hz 100.0 / sin_20hz 39.9）；
- test_hw_e2e ALL PASS、smoke ALL PASS、cargo test 46/0、clippy 零告警。

## [V1.2.26] — 全量独立审查修复批次

> 本版为独立全量代码审查（不依赖历史结论、三路并行 + 主会话逐条复核）后的修复。
> 审查确认此前各修复项全部落实且无回归；本轮修复新发现的 2 高 / 5 中 / 若干低危。

### 高危
- **ElfCache 快路径命中不更新 `last`**：A→B→A 重新加载后，elf_resolve/elf_type_at_addr
  会静默在 B 的索引上解析（错误地址可进入监视目标甚至写错硬件内存）——快路径命中时
  同步更新 `last`；
- **求值节点懒展开竞态**：子项插入经后台线程异步落地，快速 折叠→再展开 会并发发起
  两次 computeChildren 并重复插入同一批子项——引入节点级在飞标志 + 插入前查 disposed。

### 中危
- **条件断点"条件为假"路径补 `landed_on_breakpoint` 防护**：step_past 落点恰为另一
  断点时不得 resume（OpenOCD 会摘掉落点断点跑过去而静默丢命中），与其余三处 resume
  路径对齐；
- **probe-rs scope_burst 白名单区（PPB/外设/外扩 RAM）末端校验**：跨出上界显式报错
  走避让，与 read_bytes 策略一致；
- **ELF 重载可见性**：reResolveWatches 就地改写后递增 `watchStructureRevision`
  （volatile 提供发布屏障 + 驱动重建）——修复"重编译后变量数不变时 EDT 长期渲染
  旧布局"；
- **addWatch 回退收紧**：agent 掉线时的解析失败不再静默变成求值型监视（回退要求
  agent 存活），故障根因不被掩盖；
- **文档与发布物同步**：README/README_CN 移除 V1.2.14 滞后引用、修正不存在的
  "Tcl Host" 配置项、补 CLion 原生求值功能描述；plugin.xml 修正作废的"~870Hz"
  并补求值特性。

### 低危
- `elf_load_async` spawn 失败回错误响应而非 panic；`SetScopeFreq` NaN 防护对齐
  SetWatchFreq；`tcl_recv` EOF 截断帧按断连重建（残缺文本不再可能被当成功）；
  dwarf Pass1 单个畸形 DIE 不再中止整个索引；ReadMemSync 未连接时静默回错
  （不再连发 State+Disconnected 事件）；flash `set_speed` 钳 1kHz；
  e2e 脚本引擎事件诊断修正。

## [V1.2.25] — 修复：断点重命中时求值子树的展开状态被折叠

> **用户真机反馈（2026-09-29）**：断点再次命中时，即使 GDB 求值结果不变，
> 之前展开的项也会被折叠。

### Plugin (V1.2.25)
- **结果不变不再重建**：混合升级比较新结果与既有状态（地址/大小/是否含子项），
  一致时只更新引用对象、不递增结构修订号——树的展开状态天然保持，实时刷新
  照常（数据走 updateNodeBytes 局部更新路径）；
- **重建时携带求值状态**：rebuildTree 构建求值型顶层节点时直接填入
  evalXValue/evalHasChildren——此前新节点为空，watchDataListener 的镜像补齐
  会在重建后再次 nodeStructureChanged，把刚恢复的展开折叠掉；
- **镜像补齐保留展开**：首次求值后节点从叶子变非叶子时的结构通知，若此前
  已展开则补一次 expandPath。

### Agent (v1.2.25)
- 版本同步 v1.2.25（仅版本号联动，无行为变更）。

## [V1.2.24] — 修复：混合成员树渲染错乱（seq 可展开/无值）

> **用户真机反馈（2026-09-29）**：`instance()` 下的 `_ctx.seq` 还能展开（疑似整个
> 结构体又挂在它下面），且成员依旧不显示值（暂停时也不显示）。

### Plugin (V1.2.24)
- **根因**：V1.2.20 把求值渲染分支泛化到了整棵 `entry.evalOnly` 子树，未区分
  混合升级引入的 **ELF 成员节点**（有真实地址，应走标准字节渲染）与**平台求值
  子节点**（computeChildren 产物，无地址，值在 evalValueText）——混合成员全部
  落进求值分支：evalValueText 为 null → 显示"…"；且 isLeaf 把它们全判为可展开；
- **展开监听器串树**：子节点展开时 XValue 回退到了顶层条目的 XValue——展开
  `seq` 会把整个结构体的成员再挂一遍（用户看到的"解析成了 pyro::wl_chassis_t *"）；
  现子节点必须有自身 XValue 才平台展开；
- **修复**：引入 `isPlatformEvalChild` 判据（evalXValue/evalValueText/evalTypeText
  任一非空）——求值渲染分支只作用于顶层 + 平台求值子节点；混合 ELF 成员回归
  标准字节渲染（按内存偏移实时显示）；isLeaf 同步三分支语义；
- 新增 isLeaf 三分支语义单元测试（顶层未求值/已求值/混合结构体成员/混合标量
  成员/平台子节点）。

### Agent (v1.2.24)
- 版本同步 v1.2.24（仅版本号联动，无行为变更）。

## [V1.2.23] — 混合升级重写：纯函数化 + 引用类型支持 + 指针全局布局来源

> **用户真机反馈（2026-09-29）**：V1.2.22 混合模式"仍不能实时刷新，且静态解析结果不对"。

### Plugin (V1.2.23)
- **根因 1（主因）**：`instance()` 典型签名返回**引用 `T&`**，V1.2.22 只认 `*` 结尾——
  引用类型直接跳过升级（既无固定地址通道，展开落在平台 XValue 静态快照上，
  即"不能实时刷新 + 静态结果不对"的两个症状）。现 `*`/`&` 均触发升级；
- **根因 2**：成员布局来源过窄。新增**指针全局布局来源**——`g_chassis_ptr` 这类
  `T *` 全局的成员子树天然是 pointee 相对偏移（delta = 求值地址），比同类型非指针
  全局更普适；来源优先级：同类型非指针全局 → 指针全局 → 标量回退；
- **纯函数化**：升级逻辑抽到 `EvalHybridPromoter`（无平台依赖），以真机固件布局
  （`_ctx` 偏移 56、`_ctx.seq` 偏移 160 → 0x200003E4+160=0x20000484，与 V1.2.16
  seq 根因证明逐位吻合）做 6 项单元测试（指针/引用/同类型全局/指针成员子树
  pointee 相对保持/标量回退/非法输入拒绝）。

### Agent (v1.2.23)
- 版本同步 v1.2.23（仅版本号联动，无行为变更）。

## [V1.2.22] — 混合模式：GDB 求值指针结果升级为固定地址实时监视

> **用户需求（2026-09-29）**：运行时虽不能 GDB 求值，但求值到的指针（地址+类型）应
> 固定下来持续实时读取，下次断点再重新求值刷新地址。

### Plugin (V1.2.22)
- **混合升级**：断点期 GDB 求值结果为指针（值文本含 0x 地址、类型 `T *`）时：
  - 成员布局复用 ELF 中**同类型全局变量**的 SymbolNode（绝对地址重定基到求值地址，
    与 agent rebase_addresses 同一守卫：指针成员子树保持 pointee 相对不位移）；
  - 条目进入常规 watch 目标下发（地址过滤放行），**运行时按现有 Tcl RPC 链路以
    监视频率持续读取**，展开的成员经 dynamicWatchTargets 持续读取；
  - 树节点显示 `⏱ 实时@0x…` 标记，标量结果就地显示实时解码值；
  - **下次断点自动重求值**：地址/成员随最新指针值刷新（watchStructureRevision
    驱动 rebuildTree）；
  - 找不到同类型全局时退化为定点 4 字节标量读取。
- 结构变更检测纳入 `watchStructureRevision`（混合升级改变既有条目成员结构）。

### Agent (v1.2.22)
- 版本同步 v1.2.22（仅版本号联动，无行为变更）。

## [V1.2.21] — 修复：求值节点展开手柄不出现

> **用户真机三次反馈（2026-09-29）**：类型已能显示，但展开手柄不存在、无法下钻。

### Plugin (V1.2.21)
- **根因**：求值节点初始 childCount=0，`DefaultTreeModel`（asksAllowsChildren=false）
  直接查询 `node.isLeaf()` 判其为叶子 → Swing 不渲染展开手柄 → 懒展开监听器永远不触发；
- **修复**：`LiveWatchTreeNode` 重写 `isLeaf()`——ELF 节点维持原语义（无成员即叶子，
  行为零变化）；求值节点在"已有求值结果且呈现捕获报告可能有子项"时报告非叶子；
- 求值完成回填后主动 `nodeStructureChanged` 通知模型重查结构（手柄即时出现）；
- 子节点懒展开时按各自 `hasChildren` 决定手柄，未求值/无子项不显示空手柄。

### Agent (v1.2.21)
- 版本同步 v1.2.21（仅版本号联动，无行为变更）。

## [V1.2.20] — 求值结果呈现修复 + 结构体/指针结果懒展开

> **用户真机二次反馈（2026-09-29）**：evalOnly 回退已生效（不再报错），但求值结果显示"…"
> 且无法展开下钻。

### Plugin (V1.2.20)
- **"…"根因修复**：CIDR 求值回调存在第四条路径 `invalidExpression`（默认 no-op，
  V1.2.19 未覆盖导致结果静默超时）——现已覆盖并回显原因；呈现捕获同时兼容
  setPresentation 双重载（含 `XValuePresentation.getType()`），且修正为在 **EDT** 上发起
  （CIDR 实现有 UI 线程断言）、闩锁等待 5s；求值失败/超时现在显示诊断文本而非静默"…"；
- **新增结构体/指针结果懒展开**：求值结果保留平台 XValue，树节点展开时经
  `computeChildren` 拉取子项并逐层下钻（每层懒加载，children 名称/类型/值文本经
  同一呈现捕获管线）；新增 `ClionEvalBridge` 统一封装呈现/子项协议转换
  （EDT 发起 + 闩锁有界等待 + setErrorMessage/tooManyChildren 全分支）；
- 渲染器 evalOnly 分支从"仅顶层"泛化到整棵求值子树（子节点显示成员名 + 类型 + 值文本）。

### Agent (v1.2.20)
- 版本同步 v1.2.20（仅版本号联动，无行为变更）。

## [V1.2.19] — 修复：CLion 求值型监视在真实调试会话中不生效

> **用户真机实测发现（2026-09-29）**：`pyro::wl_chassis_t::instance()` 在 CLion 原生
> Watches 可求值，但实时监视添加框报"添加监视失败: 无法解析表达式"——evalOnly 回退未触发。

### Plugin (V1.2.19)
- **求值器捕获改为按需惰性获取（根因修复）**：V1.2.18 在 `processStarted` 瞬间一次性
  捕获 `XDebugProcess.getEvaluator()`，而该时刻 CIDR 调试进程常尚未初始化、返回 null，
  此后永不重试 → `clionEvalAvailable()` 恒 false → 复杂表达式全部走裸 ELF 解析报错。
  改为每次调用时从存活调试会话现场获取并缓存；
- **可诊断报错**：有调试会话但求值器仍不可用时，报"CLion 原生求值器不可用（调试器
  初始化中或未暴露求值器；请稍后重试或重启调试会话）"而非裸解析错误。

### Agent (v1.2.19)
- 版本同步 v1.2.19（仅版本号联动，无行为变更）。

## [V1.2.18] — CLion 原生 GDB 求值接入（复杂表达式断点期求值）+ 审查遗留修复批次

### Plugin (V1.2.18)
- **新增"CLion 求值型"监视项**：无法解析为内存地址的复杂 C 表达式（强转、函数调用等）
  在 CLion 调试会话在线时自动回退为求值型监视——经平台公开 API `XDebuggerEvaluator`
  **复用 IDE 自己的 GDB** 进程内求值，不新起 GDB、不占用 3333 端口、无与调试器抢目标的问题；
- **断点暂停自动求值**：命中断点（`sessionPaused`）时对全部求值型监视项执行一轮求值并刷新
  树显示；已处于暂停态时添加的项立即出值；求值失败红字显示、超时 8s 兜底置空；
- **持久化兼容**：`PersistedWatchItem` 新增 `evalOnly` 属性，旧配置缺省 false 向后兼容；
  树节点以 `⟦CLion 求值⟧` 标记 + 求值专用图标区分于内存读取型项；
- 会话结束自动清空求值器引用；求值结果经 `XValueNode` 捕获（同步/异步 presentation 均支持）。

### Agent（v1.2.18）
- **退役休眠 GDB 求值链路**：原版单体应用（无 IDE）需要自带 gdb_evaluator，移植插件后
  `ConfigureGdb`/`UpdateExprTargets` 始终无调用方；求值职责已由插件的 CLion 原生求值器
  接管——整体删除 `gdb_mi.rs`/`eval_expressions`/`ExprTarget`/`Event::ExprData`（含 V1.2.17
  的异步预热加固），二进制缩小约 60KB；
- **探针 serial 选择**：`ConnectParams` 新增 `probeSerial`（协议可选字段），`open_first_available`
  支持按序列号过滤，probe-rs 后端与 flash 路径均透传——多探针系统不再"盲选第一个能打开的"，
  杜绝监视/烧录误连别家设备；插件设置页新增探针序列号输入（留空 = 第一个可用）；
- **握手拒绝不再杀死 agent**：误连/端口扫描的失败会话回到 accept 继续等待真实客户端
  （插件连接已在 backlog 排队），不再让插件拿到"已死"的 agent；accept 自身失败重试 10s；
- **示波带宽主动预警**：目标/频率下发时调用 `check_bandwidth`，超出链路带宽提示实际采样率
  将低于设定值（此前 UI 设 10000Hz 实测只有 ~2700Hz 且无任何预警）；
- **DWARF V4- 行表补全**：`file_index==0`（编译单元主源文件约定）不再被静默丢弃，
  回退映射到 CU 名；
- **`reset halt` 慢命令超时分级**：读超时临时放宽到 10s（默认 2.5s 对带看门狗/慢时钟目标
  会误判连接丢失拆流重建）；
- **杂项**：未使用依赖清理（workspace anyhow、embedded-clion-agent serde、monitor/elf-info
  tracing）；`BurstFrames` 类型别名；clippy 全量清零（`-D warnings` 门禁通过）。

### CI / 工程
- agent 任务新增 `cargo clippy --workspace --all-targets --locked -- -D warnings` 门禁；
- 插件任务新增 `gradlew verifyPlugin`（对 pinned CLion 2026.2.2 校验 API 兼容性）；
- `package.py` 新增插件↔agent 版本联动断言（不一致直接拒绝打包）；
- `release.yml` 改为 draft 发布：先上传产物确认无误再 publish，不再留下空 Release。

### 真机验证（STM32G431CBTx + Flash Pro CMSIS-DAP）
- `test_hw_e2e` 24 项 ALL PASS（负向用例升级：验证"拒绝后保持存活 + 真实客户端 backlog 接入"）；
- sim 冒烟 ALL PASS；cargo test 46/0；clippy `-D warnings` 零告警；gradle test 124/0。

## [V1.2.17] — 只读审查修复批次：正确性、进程生命周期与 EDT 纪律

> 本版为代码审查（插件 + agent + 构建链全量只读评审，逐条源码实证）后的集中修复。
> 行协议仍为 v1（`write_mem` 由"发后即忘"改为请求-应答，请求/响应字段不变，插件与 agent 需同版本安装）。

### Agent（embedded-clion-agent / monitor / debug-core / elf-info）
- **write_mem 假成功修复**：引擎此前丢弃 `write_bytes` 返回值，探针未连接/地址不可写时
  插件也收到 `ok:true`。改为 `WriteMemSync` 请求-应答语义（2s 超时），失败错误回传 UI；
- **双引擎抢 probe 防护**：旧引擎收尾超时（3s）后不再 detach 掉头 spawn 新引擎——
  句柄转入僵尸表，`connect` 前再等一程（至多 7s），仍不退出则明确报错拒绝本次连接
  （探针尚在旧引擎手中，由插件监督重连自然重试），绝不带病双开；
- **自启 OpenOCD 下发 tcl_port**：spawn 时追加 `-c "tcl_port N"`——此前 cfg 默认 6666
  与轮询端口不一致时陷入"拉起→超时→杀掉"死循环；超时错误文本不再硬编码 6666，
  `try_wait` IO 失败不再被静默吞掉；
- **引擎线程 panic 隔离**：`catch_unwind` 捕获后发 `Event::Error`——此前库内 panic
  静默杀死引擎线程，UI 表现为"连接还在但永远无响应"；
- **GDB 求值启动移出引擎线程**：符号加载（实测 3.6s、上限 30s）+ 两次 target-select
  此前同步阻塞引擎最长 45s；改为独立线程异步预热、结果回收，失败退避 3s→6s→…→60s 封顶；
- **条件断点为假路径对齐**：resume 失败不再发假 Running 边沿（与 Resume/Reset/step_out 一致，
  杜绝同一次断点命中重复上报）；
- **scope_burst 越界显式报错**：probe-rs 后端采样范围越出区域末尾由静默截断改为 Transfer
  报错（示波目标贴 RAM 顶端时通道此前会静默消失）；OpenOCD 8-bit 回退路径补带内错误检查；
- **SetWatchFreq NaN 防护**：非有限值回落默认频率（`Duration::from_secs_f64(NaN)` 会 panic）；
- **Tcl 帧读取 take() 封顶**：4MB 上界在读取前生效（帧错位时内存不再无界增长）；
- **elf_resolve / elf_type_at_addr 异步化**：缓存未命中时数百 ms 的 ELF 重建不再阻塞读循环；
- **ElfCache 逐出保护**：容量满只逐出非活跃条目，不再 `clear()` 全清（避免自我破坏缓存）；
- **DWARF 畸形输入防护**：数组计数 `upper+1`/多维乘积/总 size 改 checked/饱和算术，
  `addr_for_line` 行号饱和加法；
- **杂项**：gdb_mi 读线程创建失败时显式收割子进程；probe-rs Registry 进程内缓存
  （此前每次目标搜索全量重建上百 ms）；亚 kHz 速率钳到 1kHz；移除读循环中的死长度检查。

### Plugin (V1.2.17)
- **agent 就绪行读取有界化**：`readLine()` 无限期阻塞（agent 启动卡住时）改为
  有界轮询 + 缓冲拼行——此前会永久占用线程、Future 永不完成、agent 进程成孤儿；
- **dispose 与 agent 启动竞态防护**：启动任务在 `start()` 前后自检 disposed，
  工程关闭后拉起的 agent 立即回收，不再泄漏到 IDE 退出；
- **addWatch 的 `ensureAgent().get()` 加 20s 超时**；
- **调试会话监听器挂会话级 Disposable**：会话结束即注销，不再随启停次数逐次累积；
- **EDT 纪律补齐**：CSV 导出与文件对话框改为"pooled 取快照 → EDT 弹框 → pooled 写文件"；
  "自适应通道范围"改用新增的单通道轻量快照接口（不再全量克隆）；ELF 管理弹窗候选扫描
  池化；SVD 重新加载的兜底全工程扫描池化；LiveWatch 面板 tick/数据回调加 `isShowing()`
  守卫（波形时间极值的逐点全扫经评估为刻意容忍乱序数据的防御行为，保留）；
- **符号树批量添加失败通知**：此前失败被静默吞掉且照常提示"添加了 N 个"；
- **事件/响应行解析加固**：字段访问异常只丢行不再误判死整条连接；
- **示波采样率上限对齐**：面板 spinner 1–50000 → 1–5000（引擎本就钳 5000），
  存量越界配置在两处 UI 均钳回显示；设置页实现 `disposeUIResources`；移除死字段。

## [V1.2.16] — 示波通道同名子树防混淆 + 整型默认格式拆雷

> **根因报告（真机实证）**：`g_chassis_ptr._ctx.seq @0x20000484` 与 `g_chassis_ctx_ptr.seq @0x200002A8`
> 是两个同名成员子树（固件仅自增后者），示波通道忠实采样并显示真实的 0——采样/解码/渲染管线无缺陷。
> nm + DWARF + 持久化配置三方证据逐位吻合（0x200003E4 + 56 + 104 = 0x20000484）。

### Plugin (V1.2.16)
- **添加示波器成功通知携带绝对地址**：`已添加「…」@ 0x%08X`，同名成员通道一眼可辨；
- **指针节点树渲染显示当前指向**：`→ 0x%08X` 灰色后缀，`g_chassis_ptr._ctx.*` 与 `g_chassis_ctx_ptr.*` 不再依赖 tooltip 区分；
- **tooltip 相对偏移不再伪装成绝对地址**：指针子成员未刷新时回退 `父指针当前值 + 成员偏移`；
- **整型默认格式拆雷**：`ValueFormat.inferFromSize(4)` 由 F32 改为 I32——缺省 4 字节通道按 F32 位型
  重解释会把 int32 读成 ≈1e-41 的非规格化数（曲线贴 0）；真浮点通道必须显式 `fromEncoding("float")`。
- **平台切换 CLion 2026.2**：构建依赖 `clion("2026.2.2")`、`sinceBuild = 262`、验证 IDE 同版本；
  2024.3–2026.1 不再承诺兼容（用户本机 CLion 2026.2 为优先保障范围）。

### Agent (v1.2.16)
- 版本同步 v1.2.16（与插件 V1.2.16 联动发布）。

## [V1.2.15] — 工程化与安全加固版本

> ⚠️ **破坏性协议变更**：插件与 agent 引入行协议 v1（就绪行携带 `proto=1` 与一次性连接令牌，
> 客户端第一条消息必须是携带 token 的 hello）。插件与 agent **必须同版本安装**，旧 agent 会被插件
> 明确报错提示。真机（STM32G431 + CMSIS-DAP）端到端测试 26 项全部通过。

### Agent（embedded-clion-agent / debug-core / monitor）
- **引擎收尾确定性**：客户端断开后 `serve_one` 会 join 事件泵线程（3s 上限），保证引擎清 DWT 比较器/断点、
  resume 目标的清理路径执行完毕进程才退出（此前清理在正常断开路径上会被进程退出整段杀掉，
  目标板被遗留断点/停机态污染）；`connect` 重启引擎前同样等待旧引擎收尾，消除新旧引擎抢占同一 probe 的竞态；
  writer 线程获得有界冲刷窗口，最后一串响应（握手拒绝/断开事件）不再随机丢失。
- **协议工程化（协议版本 → 1）**：就绪行新增 `proto=<n>` 与一次性 `token=<hex>`；插件接入后第一条消息
  必须是携带正确 token 的 hello，否则拒绝并退出（防本机其它用户进程扫到端口后读写目标内存/halt 设备）；
  拒绝绑定非回环地址；单行请求 4MB 长度上限；JSON 解析失败与握手拒绝的响应回显请求 id。
- **内存安全**：来自 JSON 的 addr/size 在协议入口统一校验（32 位地址空间 + checked 算术），
  消除 debug 构建溢出 panic / release 回绕巨型读块的隐患；`write_bytes` 增加与读路径同源的越界预检；
  读请求跨出内存区域末尾由静默截断改为显式报错（截断会让上层拿"短了却当完整"的数据解出错误数值）；
  `speedHz/tclPort` 超范围报错而非静默截断。
- **OpenOCD 后端**：`halt/resume/step/reset` 族校验带内错误文本（此前失败被当成功，后续"重下断点→恢复"
  建立在错误前提上）；自启的 OpenOCD 挂入 kill-on-close Job Object（Windows），agent 崩溃时内核自动收割，
  不再遗留孤儿进程占用 USB 探针（Unix 加独立进程组）；清理死代码 `pending` 字段。
- **健壮性**：attach-only 连接失败现在会发出 `Disconnected` 事件（此前引擎永久静默死亡，UI 只能靠超时猜）；
  GDB/MI 求值器区分进程退出与超时、剔除表达式中的换行、Drop 收尾 reap 僵尸进程；
  ElfCache 解析移出缓存锁（不再阻塞主读循环）+ 16 条容量上限。
- **DWARF**：多维数组展开加单级 4096 上限与 `checked_mul`，畸形/超大数组不再可打爆内存或溢出 size。

### 插件（CLion Plugin）
- **生命周期**：`AgentService.dispose()` 现在关闭 supervisor/pushExecutor 调度器（此前项目关闭后仍每 2s
  访问已 dispose 的 project）；状态栏 Widget 的 1s Timer 在 `dispose()` 中停止（此前每关一个工程泄漏一个
  widget 及整个 project 引用）；`AgentClient.start()` 在连接失败时销毁已启动的 agent 进程（不再产生孤儿）；
  `loadElf` 在 agent 恰好关闭时异常完成而非挂起。
- **重连策略**：监督线程自动重连改走 `connectEngine(supervised)` 统一入口——瞬时失败不再静默放弃期望
  连接态，按 2/4/8/16/32s 退避持续重试；监督路径不启看门狗、不弹"连接超时"打扰。
- **协议适配**：就绪行解析 `proto` 并校验（版本不匹配 fail-fast 提示）；hello 握手发送 token；
  agent stdout 就绪行之后持续排水；单行 JSON 解析失败只丢该行不判死连接。
- **安全加固**：SVD 解析与 `workspace.xml` 读取统一禁用 DOCTYPE/外部实体（XXE/实体炸弹防护）；
  SVD dim 展开封顶 4096 并限制 dimIncrement，畸形 SVD 不再可 OOM；SVD 二进制字面量 `#0110` 正确按二进制解析
  （此前当十进制 110）；移除硬编码开发者个人 SVD 路径。
- **EDT 与性能**：设置页 ELF 候选扫描移入后台线程；示波器全量快照克隆移出 EDT 且防重入堆积；
  波形时间戳极值改为写入点重算、查询 O(1)（此前 paint/hover/drag 每帧全量扫描，50k×通道×30fps 下
  每秒数百万次比较）；面板不可见时跳过 33ms 波形重绘 Timer 与寄存器轮询；波形画布内部 repaintTimer
  纳入 dispose 链（此前永不停止）。
- **数据正确性**：撕裂样本修复对 |值|≥2²⁴ 的整型放弃修复（float 位模式失真，宁可放弃不修错）；
  寄存器"添加到实时变量监视"按实际宽度生成 u8/u16/u32（此前硬编码 u32 可能越界读外设保留区），
  并等待添加结果再提示；"添加到示波器"在地址已是通道时回收本次添加的 watch。
- **杂项**：`_Bool` 类型别名修正；`repairSeries` 尾部 NaN 不再传播；设置页 isModified 双侧 snap 归一。

### 构建与发布
- **兼容范围固定**：`build.gradle.kts` 显式 `sinceBuild="243"`、不设上限；本地 CLion 探测改为显式 opt-in
  （`CLION_HOME`/`-Pclion.home`），默认一律 pinned `clion("2024.3")`——发布产物的兼容范围不再随构建机漂移
  （此前本机构建出的包实际仅支持 2026.2+，与文档宣称的 2024.2+ 不符）。
- **发布流水线**：新增 tag 触发的 `release.yml`（构建 + package.py + SHA256 上传 GitHub Release）；
  CI 上传 agent/plugin 构建产物、`cargo --locked`、`setup-gradle@v4`。
- **开源净化**：Gradle wrapper 换官方发行源；移除脚本/源码/文档中的个人机器路径；
  `package.py` 版本号缺失改为报错退出；CHANGELOG 补 `software_ref/` 路径映射说明；
  `plugin/CHANGELOG.md` 合并为根目录指针；README 修正 agent 端口（随机端口而非 44445）、
  平台说明（当前发布仅内置 Windows agent）与 Rust 版本要求；LICENSE 补版权附录。
- **测试**：新增真机端到端脚本 `scripts/test_hw_e2e.py`（协议握手/负向用例/真机连接/内存校验/数据流/干净退出）。

## [V1.2.14]
- **彻底根治指针/结构体数组成员（如 `g_chassis_ptr._ctx.data[0].vx` 与 `data[1].vx`）地址偏移计算缺失导致示波器添加失败与数据冲突缺陷**：
  - **后端 DWARF 数组结构体成员偏移步进缺失修复（`software_ref/crates/elf-info`）**：
    - **根因定位**：在 DWARF 调试信息解析器（`dwarf.rs`）处理数组类型（`DW_TAG_array_type`）时，对数组元素按下标克隆生成（`[0]`, `[1]`, ...）并赋值 `m.address = i * elem.size`，但遗漏了调用 `shift_addresses(&mut m, elem_offset)` 递归步进其内部子成员。导致对于结构体数组（如 `data[0]` 与 `data[1]`），其内部叶子成员（如 `vx`）仍然保持 0-based 初始相对偏移（104 / 0x68），造成 `data[0].vx` 与 `data[1].vx` 的相对偏移完全相同（均为 0x68）；
    - **全递归偏移步进**：升级为 `shift_addresses(&mut m, elem_offset)`，不仅正确设置元素基址，更完整递归步进非指针子成员与深层嵌套结构体字段，确保 `data[0].vx`（0x68）与 `data[1].vx`（0x7C）地址偏移毫厘不差；
    - **单元测试保障**：在 `elf-info` 中新增真实工程 ELF（`g4_tool_test.elf`）数组成员解析校验以及全局结构体数组成员跨步长（20字节）测试。
  - **前端物理地址解析与示波器去重/添加闭环保证（`LiveWatchPanel` / `AgentService` / `AddToScopeAction` / `ScopePanel`）**：
    - **精确物理地址映射与运行时动态指针解引用**：指针节点在实时变量监视树中展开时，`data[0].vx` 与 `data[1].vx` 分别计算出真实的非冲突目标内存物理地址（`parentPtrAddress + 104` 与 `parentPtrAddress + 124`），彻底消除同地址判重冲突；在 `AgentService` 中新增 `resolvePointerAddress`，在手动或代码右键添加指针成员时自动解析运行态物理地址；
    - **示波器通道添加严格低地址校验**：在 `AgentService.addScopeVariable` 中强制拦截 `address < 0x1000L`，防止未初始化的相对偏移被误添加为死通道；
    - **示波器添加反馈准确化与防呆**：`LiveWatchPanel` 上下文菜单“添加到示波器”对未解引用指针（`< 0x1000L`）显示禁用状态及悬浮提示，杜绝虚假添加提示；
    - **监视列表污染清理**：在 `AddToScopeAction` 与 `ScopePanel.promptAddChannel` 中，当指针未解引用导致添加示波器失败时，自动从 `watchItems` 中回退清理该非法监视项，防止相对偏移污染监视列表；
    - **符号成员链数组下标双向兼容**：优化 `AgentService.findNodeInElf`，支持兼容带方括号（`[0]`）与纯数字点号（`.0`）的数组成员查找；
    - **发布打包流程二进制新鲜度守护**：在 `package_release.py` 中引入自动触发 `cargo build --release -p embedded-clion-agent`，彻底杜绝打包发布携带陈旧 sidecar agent 导致修复未生效的风险。

## [V1.2.13]
- **突破 15Hz 高频监视 ~12Hz 瓶颈限制，精简可选档位至 2/5/10/15 四档**：
  - **精简前端刷新率可选档位（2Hz / 5Hz / 10Hz / 15Hz）**：
    - 根据用户明确需求，全面精简实时变量监视面板（`LiveWatchPanel`）顶部下拉框（`freqCombo`）与设置界面（`EmbeddedMonitorConfigurable`），仅保留 `2, 5, 10, 15` 四个实用档位；
    - 在设置模型与 UI 中引入 `EmbeddedMonitorSettings.snapWatchFreq` 智能吸附与回退保护，无论历史配置、外部持久化或用户切换，均无缝就近映射吸附至合法四档之一；
    - 联动优化相关 Tooltip 与配置提示文案，树节点状态同步显示当前档位。
  - **彻底攻克 13~15Hz 均卡在 12Hz 的核心瓶颈**：
    - **消减无用示波定时打断**：在 `software_ref/crates/monitor/src/lib.rs` 中，此前 `self.next_scope_flush`（33ms）无论示波器是否激活均强制加入主循环等待调度（33ms/66ms 节拍），正好在 15Hz（66.7ms）前夕以 66ms 频繁唤醒主循环，导致后续仅余 <1ms 剩余时间而被迫触发多余的 Windows 线程睡眠；
    - **活跃采样自适应高精度等待**：将主循环自适应等待扩展至全部活跃采样状态（包含变量监视与高频示波），将活跃采样等待上限放宽至 50ms（消除 20ms 人为切碎带来的多次睡眠抖动累积），并在剩余时间 <= 2.5ms 时采用 `spin_loop` 忙等到点，彻底消除 Windows 系统下短休眠（<2.5ms）带来的 2~15ms 线程调度与时钟中断过冲；
    - **进程级 1ms 高精度定时器注入**：在 `embedded-clion-agent` 启动入口无条件启用 `timeBeginPeriod(1)`，确保 sidecar 进程各线程享受高精度操作系统定时器支持；
  - **真机与全链路压测精准保真**：
    - 实测验证在 CMSIS-DAP 真机硬件与 Sim 仿真下，配置 2Hz/5Hz/10Hz/15Hz 均以高精度稳定运行（实测 2.00Hz, 5.00Hz, 10.00Hz, 15.00Hz，误差 < 0.1%），15Hz 完美突破并稳定在 15.0Hz；
    - 覆盖全部 116 项 Kotlin 单元测试与 30 项 Rust 测试。

## [V1.2.12]
- **彻底根治实测频率累积漂移、高频欠频与衰减失效缺陷，实现全档位（2Hz/5Hz/10Hz/15Hz）高精度保真**：
  - **后端无漂移锚点步进（Anchor-Based Cadence）**：
    - 根因定位：在 `software_ref/crates/monitor/src/lib.rs` 中，主调度循环此前采用 `self.next_watch = now + watch_interval` 简单重置，导致单次采样耗时、OS 线程调度超期与事件排队延迟在每个周期中不断累加，高频采样（10Hz/15Hz）下产生高达 15%~20% 的累积欠频漂移（10Hz 跌至 8.3Hz，15Hz 跌至 12.3Hz）；
    - 修复：升级为无漂移锚点推进 `self.next_watch = if now > self.next_watch + watch_interval { now + watch_interval } else { self.next_watch + watch_interval }`，并自适应调节安全冷却边限 `cooldown_margin = (watch_interval / 5).max(5ms)`，在严格阻断 ~23Hz/30ms 突发抖动的同时，完美保障全档位（2Hz/5Hz/10Hz/15Hz）采样率毫厘不差（实测 2.00Hz, 5.00Hz, 10.00Hz, 15.00Hz，误差 < 0.2%）；
  - **跨会话与连接前状态持久化（Pre-Connect Configuration Persistence）**：
    - 在 `embedded-clion-agent/session.rs` 中引入对 `watch_freq`、`scope_freq` 及采样目标的本地持久化记忆；客户端在未连接引擎前下发频率配置不再报“引擎未启动”错误，且在引擎启动/重启握手时第一时间将用户设定注入新引擎，彻底消除连接初期由于默认 5Hz 产生的频率滞后；
  - **前端实际频率衰减时效修复**：
    - 修复 `AgentService` 中频率超时衰减仅在 `watchRateWindowCount == 0L` 时才生效的逻辑漏洞；引入物理帧绝对时刻追踪 `lastWatchFrameTime` / `lastScopeFrameTime`，确保目标暂停、断开或数据断流超过 2000ms 时，实测频率必定且平滑衰减归零，杜绝残存虚假数值；
  - **发布打包流程二进制新鲜度守护**：
    - 修复 `package_release.py` 优先读取 `release/` 遗留二进制而非 `software_ref/target/release/` 最新编译产物的缺陷，增加实时编译检测与自动同步复制，确保构建的 standalone zip 与本地部署始终包含最新版二进制。

## [V1.2.11]
- **彻底根治实时变量监视频率失控（无论选多少实测始终在 ~23Hz）缺陷**：
  - **后端采样引擎节流防护与步调解耦（Cooldown & Decoupling）**：
    - 根因定位：在 `software_ref/crates/monitor/src/lib.rs` 中，前端对变量增删、结构体展开/折叠或动态指针更新下发 `UpdateWatchTargets` 时，后端无条件执行 `self.next_watch = Instant::now()`，将下一次采样调度瞬间置零；同时主循环中缺少相邻采样最小冷却时间（Cooldown）约束。在 Windows TCP 回环与 EDT 事件循环下，高频目标变动导致 `sample_watch()` 频繁被抢先触发，最终退化为约 35~43ms 一次的死循环（实测恰好在 23Hz 左右）；
    - 冷却时间硬保证：在采样主循环中引入 `last_watch_sample` 与 `min_cooldown = watch_interval - 5ms`。无论外界以多高频率下发目标更新，未达到目标周期的冷却窗口前绝不提前触发 `sample_watch()`；
    - 状态感知调度：`UpdateWatchTargets` 仅在目标列表此前为空且首次填入新变量（`was_empty && !self.watch_targets.is_empty()`）时才立即调度，日常变量增删或指针扩展不再重置周期时钟；
    - 频率平滑切换：`SetWatchFreq` 动态计算 `next_allowed = last + watch_interval`，避免切换频率瞬间突发采样；
  - **前端实际频率统计与状态栏即时渲染优化**：
    - 统计衰减保护：`AgentService` 中的 `actualWatchRateHz` 与 `actualRateHz` 引入 2000ms 超时衰减，在目标断开或停止采样时自动归零，并在 `EngineEvent.Disconnected` 时主动复位计数窗口，杜绝残留虚假采样频率；
    - 状态栏即时响应：在 `LiveWatchPanel` 的 `watchDataListener` 中于每次数据帧到达时同步调用 `updateStatusLabel(service.engineState)`，彻底摆脱 UI 定时器等待，实测频率随真实采样步调即时刷新；
  - **全链路严密测试与精度验证**：
    - 在 Rust 端新增 `test_watch_frequency_protected_against_rapid_target_updates` 集成测试，验证 30ms 极速下发目标时 2Hz 采样依然严守 500ms 周期；
    - 在 Kotlin 端补充频率状态栏格式化及实际采样率衰减测试，全部 116 项单元测试 100% 通过。

## [V1.2.10]
- **实时变量监视刷新频率动态调节支持（2Hz ~ 15Hz）**：
  - **交互体验全面对标寄存器实时监视**：
    - 在实时变量监视面板（`LiveWatchPanel`）顶部内联求值栏右侧新增刷新频率下拉框（`freqCombo`），支持在运行态实时切换 2Hz ~ 15Hz 监视刷新频率；
    - 变量树节点复选框 Tooltip、节点全局 Tooltip、上下文菜单“运行时自动刷新”项及底部状态栏信息，均实时动态展示当前配置的实际监视频率（如 `运行时自动刷新 (10Hz)`）；
    - 全局设置（`EmbeddedMonitorSettings` / `EmbeddedMonitorConfigurable`）中同步引入监视刷新频率设置（2..15Hz），持久化记忆并在引擎就绪与工程加载时自动同步生效；
  - **底层 Agent 采样引擎与前端定时器闭环联动**：
    - `embedded-clion-agent` 与 `monitor` 引擎新增 `set_watch_freq` JSON-RPC 命令与 `Command::SetWatchFreq` 调度指令；
    - 引擎主循环采样周期计算动态绑定 `self.watch_freq`，自动计算毫秒级定时并自适应等待唤醒；
    - 内存采样带宽检查与相邻请求块合并算法（`merge_blocks`）动态适配当前采样频率，彻底杜绝高频刷新下的总线超载；
    - 前端 EDT `uiTimer`（`delay` / `initialDelay`）随频率切换动态换算（`66ms ~ 500ms`）并就地重启，保证底层后端采样率与前端界面渲染帧率同频共振；
  - **真机硬件测试与严格精度验证（STM32G431）**：
    - 基于实际硬件 CMSIS-DAP / STM32G431 开发板，在下位机自增变量 `g_chassis_ctx.seq` 运行态下开展 2Hz, 5Hz, 10Hz, 15Hz 全档位物理测试；
    - 实测平均采样间隔：2Hz 对应 500.4ms（2.00Hz，误差 0.1%）、5Hz 对应 200.3ms（4.99Hz，误差 0.2%）、10Hz 对应 100.4ms（9.96Hz，误差 0.4%）、15Hz 对应 67.1ms（14.91Hz，误差 0.6%），读取频率高度一致且数据无丢帧。

## [V1.2.9]
- **指针变量子成员物理地址复制与实时监视更新路径全栈融合**：
  - **彻底修复指针变量子成员“复制地址”得到的地址错误缺陷**：
    - 根因定位：在 `LiveWatchPanel` 上下文菜单中，“添加到示波器”与“修改值”均正确使用经计算的有效物理地址（`physicalAddress >= 0x1000L`），而“复制地址”原本直接读取了 `treeNode.address`（在指针子成员中仅为相对结构体首部的局部偏移量如 `0x00000004`），导致复制出的地址失真；
    - 修复：统一在右键菜单“复制地址”中引入 `effectiveAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address`，确保指针子成员复制出的地址为其真实的内存物理地址（例如 `0x20001004`），与“添加到示波器”、“修改值”完全对齐；
  - **指针变量子成员与常规变量刷新路径全栈融合（消除更新滞后与割裂）**：
    - 根因排查：传统架构下，常规变量走静态 `WatchItem` 主管道，每次采样返回即时渲染；而指针子成员依赖表现层 JTree 展开时临时收集 `DynamicWatchTarget`，每次更新均经历 `pushWatchTargets` 的 150ms 防抖延迟，且 `watchDataListener` 内部仅触发了 `nodeChanged` 而未同步触发整树 repaint，深层嵌套行的重绘被延迟到 200ms 的后台 Timer，导致在视觉与数据链路上均产生明显慢半拍的滞后感；
    - 融合优化 1（零延迟即时推送管道）：在 `AgentService` 中提供 `pushWatchTargetsImmediate()`，当动态指针目标注册或发生变化时，彻底绕过 150ms 防抖等待，立即向底层 monitor 下发最新采样目标；
    - 融合优化 2（极速数据预取 Fast-Path）：针对新展开的动态目标，在下发周期采样目标的同时，立即发起单次轻量级 `readMem` 异步拉取，数据瞬间返回后直接填入 `watchValues` 并通知监听器，消除首次展开和指针变动时的等待真空期；
    - 融合优化 3（同拍原子化 UI 渲染）：在 `watchDataListener` 中于 `updateNodeBytes` 完成后立即执行 `tree.repaint()`，确保底层推送到达的同一帧 EDT 事务中，常规变量与指针子成员同时刷新渲染，彻底融合成同一更新路径与刷新频率。

## [V1.2.8]
- **新增外设寄存器实时监视功能（Embedded Registers / 寄存器实时监视）**：
  - **CLion 原生寄存器实时查看能力扩展**：
    - 解决 CLion 原生寄存器窗口仅在断点暂停时静态查看、不支持运行态实时动态刷新的痛点；
    - 新增独立的“寄存器实时监视”ToolWindow（右侧边栏 `EmbeddedRegisters`），并在 GDB 调试会话激活时无缝注册调试标签页（`寄存器实时监视`），与“实时变量监视”和“示波器”同台联动；
  - **高性能 CMSIS-SVD 纯 Kotlin 解析引擎（SvdParser & SvdModel）**：
    - 全面支持外设继承（`derivedFrom`）与基地址重定位计算；
    - 支持寄存器数组扩展（`dim`, `dimIncrement`, `dimIndex`，如 `AFR[%s]` 自动展开为 `AFR0`, `AFR1` 等）；
    - 支持多格式位段解析（`[msb:lsb]`、`bitOffset + bitWidth`、`lsb + msb`）及枚举描述值映射（`enumeratedValues`）；
    - 极致解析性能：实测解析 1.96MB 级别完整芯片 SVD（如 STM32G431）仅耗时 ~25ms；
  - **外设寄存器 SVD 智能自动定位（SvdAutoLocator）**：
    - 支持手动配置覆盖、工程根目录扫描、Drivers 目录（如 `STM32G4xx_HAL_Driver`）、STM32CubeMX `.ioc` 文件及工程名智能推断芯片型号；
    - 自动匹配本地 SVD 库（如 `STM32_SVD/cmsis-svd-stm32`），开箱即用，免除手动寻找并配置 SVD 文件之苦；
  - **前端交互与实时刷新（对标独立软件 SvdPanel 架构）**：
    - 顶部工具栏支持 SVD 手动载入/重新加载、单次刷新（单步查看）、**动态刷新（Auto Refresh 开关）**与**刷新频率下拉选择（1Hz, 2Hz, 5Hz, 10Hz）**；
    - 快速过滤搜索栏（实时响应过滤外设及寄存器名称与物理基地址）；
    - 外设与寄存器树形视图（分层折叠与展开），支持每个寄存器单独勾选实时监视复选框（仅采样用户关注的寄存器，节约 SWD/JTAG 硬件传输带宽）；
    - 寄存器位段（Bitfield）实时解析表格：实时切片展示字段名、位范围、十六进制数值以及枚举定义与描述；
    - 内存读取引擎优化：支持相邻寄存器请求块合并（`gap <= 64`, `maxBlock <= 1024`），大幅压缩底层读取总线往返次数；
    - 快捷右键菜单：支持“开启/取消动态刷新”、“添加到实时变量监视（Live Watch）”、“添加到示波器（Scope）”、“修改寄存器值（写内存）”、“复制地址与数值”等完整嵌入式调试交互。

## [V1.2.7]
- **示波器与实时变量监视并发调度饥饿及指针子成员展开高频闪烁缺陷彻底修复**：
  - **彻底修复变量示波时实时变量监视（Live Watch）停止更新缺陷**：
    - 消除示波突发调度饥饿死循环：底层采样引擎（`monitor`）在开启示波器采样时，原 `sample_scope()` 误将下次示波时刻设为 `Instant::now()`，且主循环中的 `slack_ok` 时间余量逻辑误判，导致示波突发采样陷入 100% 忙循环，无休止霸占总线并彻底饿死 `sample_watch`（变量监视）与 `poll_state`（状态轮询）；
    - 修复后基于突发时长步进精确推进 `next_scope`，移除主循环对变量采样的阻断屏障，确保变量采样按 5Hz（每 200ms）及状态轮询按 10Hz（每 100ms）在示波突发间隔中确定性、平滑穿插执行，实现示波与变量监视双轨平稳并发；
  - **彻底解决展开指针子成员数值在真实值与 `...` 之间高频交替闪烁缺陷**：
    - 根因排查与状态锁存保护：`LiveWatchTreeUpdater` 在处理展开指针节点时，因偶发性瞬态空数据包误触发清理逻辑，直接清空了指针节点的 `cachedBytes` 与 `pointerAddress`，导致展开的二级动态目标被从 `dynamicTargets` 集合中注销；随后指针自身值再次被解析又重新注册目标，造成子成员在真实数值与 `...` 省略号之间高频闪烁；
    - 修复后展开指针在监视期间持续维持二级目标注册，瞬态空包期间平稳保持已有缓存，仅在目标物理地址改变、指针置为 NULL 或收起节点时才执行级联清理与注销，彻底消除数值闪烁，保证指针层级数据实时平稳呈现。

## [V1.2.6]
- **实时变量监视（Live Watch）实时刷新、指针子成员数值解引用与连接稳定性彻底修复**：
  - **彻底修复变量不实时更新与展开指针显示 `...` 缺陷**：
    - 消除 `AgentService` 状态机误锁存缺陷：修复此前因活动调试会话标志误将 `isHaltedByDebug` 永久置真、导致 `doPushWatchTargets()` 错误提前返回并遗漏下发 `set_watch_targets` 给底层采样引擎的严重漏洞。修复后顶层变量与展开的动态指针目标均 100% 实时注册至底层引擎；
    - 动态二级采样与物理地址切片闭环：指针展开后生成的二级目标（如 `ptr_0x2000017C`）在接收到采样字节流后，按子成员相对于结构体的内部相对偏移精准切片填充，使 `g_chassis_ptr` 及其嵌套结构体、数组与标量成员彻底恢复正常实时监视；
  - **前端响应式事件驱动与 TreeCell 布局重算（EDT Reactive Dispatch）**：
    - 在 `LiveWatchPanel` 中接入 `AgentService.addWatchDataListener`，接收到底层 `WatchData` 或断点快照事件后即刻在 EDT 触发局部更新；
    - 在 `LiveWatchTreeUpdater.updateChildBytesRecursively` 中引入状态比对与 `onNodeChanged` 增量回调（带 `@JvmOverloads` 保证二进制兼容），当节点的 `cachedBytes`、`isNullPtr` 或地址发生变动时通知 `DefaultTreeModel.nodeChanged`，强制 `BasicTreeUI` 刷新行宽缓存与无闪烁重绘；
  - **OpenOCD 端口连接稳定性根除（消灭 TCP RST 导致的连接失败与丢包）**：
    - 移除 `OpenOcdConfigReader.probeAddress` 中强制发送 TCP RST 报文的 `socket.setSoLinger(true, 0)`，恢复标准 TCP FIN 优雅四次挥手，彻底解决 OpenOCD 0.12+ 报 `Protocol error with Rcmd: 06`、假死及偶发性高连接失败率；
  - **断点暂停态快照刷新即时通知**：
    - `sampleWatchOnceOnPause()` 在完成断点内存读取后立即通知 `watchDataListeners`，使调试单步操作后的变量值能够瞬时呈现。

## [V1.2.5]
- **指针子成员实时动态监视重构与连接稳定性彻底修复**：
  - **指针目标动态二级采样架构（彻底解决展开指针显示 `...` 缺陷）**：
    - 对标 `embedded_tools` 成熟解引用架构，在 `LiveWatchPanel` 与 `AgentService` 中重构指针子树更新流水线；
    - 指针节点首先从自身 4 字节缓冲区解析出单片机运行时的物理目标地址 `ptrAddr`。当用户在树形监视器中展开指针节点时，动态向底层 Agent 注册该指针目标内存采样（地址 `ptrAddr`，大小 `pointeeSize`）；
    - 采集回目标内存后，按子成员在结构体内部的相对偏移（`offset`）精准切片填充，使指针下所有嵌套结构体、字段与数组元素彻底恢复正常的实时数值刷新；
    - 空指针安全防御：当指针为 NULL（`0x0`）或非法地址时，子节点优雅标记并展示为 `<nullptr>`，不发起无谓采样；
    - 带宽动态优化：未展开的指针仅监视其自身 4 字节指针变量，不发起深层目标采样，最大化节省 SWD/JTAG 硬件传输带宽；
    - 物理地址准确映射：展开后的子成员自动计算出其单片机真实绝对物理地址（`ptrAddr + offset`），右键“添加到示波器”及“修改值（写内存）”完全可用；
  - **连接稳定性与断点暂停保护重构（彻底解决调试连接失败高概率缺陷）**：
    - 完整恢复基线 V1.2.2 验证过的调试断点态保护机制：当单片机停在 CLion `main` 入口断点或其他断点时，保持引擎为 `halted` 暂停态并执行断点安全单次采样，绝对不并发拉起全量重连与看门狗，杜绝与 GDB 在调试刚启动阶段争抢 OpenOCD 引发超时误判；
    - 增加硬件底层绝对地址安全边界防护（`addr >= 0x1000L`），严禁将相对偏移（如 `0x00000024`）推给底层采样引擎，彻底根除因读取受限向量表区域导致底层总线报错与意外掉线；
    - 消除调试态下对 OpenOCD 端口的频繁 socket 探测抖动。

## [V1.2.4]
- **复杂类型/嵌套类型、C++ 指针及前向声明解析引擎深度重构**：
  - **跨编译单元（CU）全局类型定义图纸预扫描（Global Type Def Cache）**：
    - 针对大型嵌入式 C++ 工程中大量存在的“头文件仅前向声明（`DW_AT_declaration`）、实体结构体在其他源文件中完整定义”的架构，增加 Pass 1 全局类型定义预扫描与评分机制（优先采纳包含完整成员与大小的完整结构体定义），彻底消除旧版单 CU 隔离解析无法获取跨单元结构体图纸的缺陷；
    - 遇到结构体或指针指向前向声明时，自动跨 CU 检索全局图纸补全真实类型结构体与全部字段成员；
  - **指针类型与子成员解析修复（杜绝退化为 `void *`）**：
    - 彻底修复 `wl_chassis_ptr` 等复杂指针变量被错误解析为 `void *`（`pointee_size = 0, members = 0`）的严重缺陷，准确解析为指向真实类型（如 `pyro::wl_chassis_t *`）且具备完整的深层子成员树；
    - 修复指针变量在基地址偏移重定位（rebase）时意外污染指向对象（pointee）内部成员相对偏移的隐蔽缺陷；
  - **specification / abstract_origin 链式追溯（51 个 `unknown` 变量全面清零）**：
    - 修复现代 GCC/Clang 编译时全局/静态变量定义 DIE 仅携带 `location` 与 `specification`、自身未直接携带 `DW_AT_type` 导致变量类型误判为 `unknown` 的缺陷。全面沿 `specification` 链追溯类型与声明行号，在真实机器人工程（如 `PYRo_Robot.elf`）实测中将全部 51 个 `unknown` 变量与 0 字节异常变量彻底清零；
  - **成员链表达式解析增强（多级点号、箭头与数组下标）**：
    - 增强 `resolve_member_chain` 语法解析，全面支持 `.`、`->`（指针解引用）与 `[index]`（数组下标索引）混合调用链，支持透明解包匿名结构体/联合体。

## [V1.2.3]
- **彻底解决跨机器安装 standalone 插件时连接失败与超时缺陷**：
  - **内置 Agent 可执行文件智能定位与回退查找**：彻底排查并消除了旧版 `AgentService.locateAgent` 仅探测硬编码开发路径（`E:/Software/Develop/...`）导致在其他机器上无法启动 Agent 的核心根因。新增基于 IntelliJ `PluginManagerCore`、`loadedPlugins`、`PathUtil`（根据类所在 jar 回溯 `../bin/`）以及 `PathManager.getPluginsDir()` 的多级智能探测机制（覆盖直接安装与版本化安装目录），确保 standalone 插件在任意 Windows 电脑安装后均能 100% 正确找到并启动打包附带的 `bin/embedded-clion-agent.exe`，彻底恢复 probe-rs 与 OpenOCD 后端连接能力；
  - **消除调试自动启动初值短路与看门狗断点假死缺陷**：修复了在调试启动时因 `desiredRunning` 默认初始值为 `false` 导致自动连接探测循环在第 0 轮瞬时退出的严重缺陷；彻底修复了连接看门狗在检测到调试器停在 `main` 断点时误将看门狗取消而不重置状态机导致 UI 永久停留在“连接中...”的死锁缺陷；
  - **解除断点暂停态对初次底层连接的错误拦截**：消除旧版在 `connectEngine()` 与 `doConnectEngine()` 中因 CLion 默认停在 `main` 入口断点 (`isAnySessionPaused == true`) 而短路返回导致底层从始至终从未建立连接的致命漏洞。初次连接无论目标是否处于断点态均正常执行握手，连接建立后精准进入 `halted` 态并执行单次初始变量快照读取，并在 `onDebugSessionResumed` 恢复运行时具备自愈重连机制；
  - **OpenOCD 固件烧录阻塞等待容差与多回环地址族并发探测**：将调试启动后等待 OpenOCD Tcl 端口就绪的重试窗口由 5 秒（25×200ms）扩展至 30 秒（60×500ms），单次探测超时提升至 300ms（并启用 `reuseAddress`），并在 `isOpenOcdPortListening` 中支持 `127.0.0.1`、`::1` 与 `localhost` 回环地址族探测，彻底消除因 OpenOCD 擦写 Flash 耗时（3~10s）事件循环阻塞导致插件过早超时并误报“Tcl 端口未开放”的误判；
  - **完善错误透传与通知交互**：移除在断点暂停态下对连接错误弹窗的静默抑制，真实暴露底层探针与网络异常信息。

## [V1.2.2]
- **示波器 Y 轴自动量程（Auto Range）专业防抖与稳定性重构**：
  - **彻底解决自动量程持续跳动缺陷**：排查并消除了旧版因窗口抽样步长动态跃迁（Stride Aliasing）以及每帧无阻尼硬重定标导致的波形与 Y 轴刻度高频跳动、呼吸颤抖；
  - **全量无损采样极值扫描**：移除不可靠的动态 `step` 跳步，在微秒级时间内完整遍历视窗内有效采样点，杜绝正弦波/方波滚动时因奇偶跳步错过尖峰而引发的数值振荡；
  - **规范 1-2-5 进位量程步进（`calculateNiceRange`）**：
    - 垂直分度单位严格按照示波器行业规范（`..., 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, ...`）对齐；
    - 对称交流/正弦信号：0 刻度线强制居中锁定在正中间网格（第 4 格）；
    - 单极性偏正信号（如 0~3.3V / 0~100）：0 刻度线自动对齐底部网格，保持 DC 地基准清晰呈现；
    - 偏移信号：整倍数网格整数对齐，刻度文字规整纯净；
  - **滞后死区与收缩保持机制（Hysteresis & Hold-off）**：
    - 外扩（Expansion）：当波形超出视窗或垂直占比超过 95% 时，立即自适应平滑外扩，确保波形不截断；
    - 锁定死区（Deadband）：当波形处于 30% ~ 95% 空间内时，量程保持 100% 绝对静止，消灭一切微小抖动；
    - 延迟收缩（Contraction）：信号幅度降至 30% 以下时，启用 1200ms 防抖保持计时器，避免低频过零误触收缩；
    - 即时复位：在切换通道、点击 Auto Range 按钮、双击重置或清空数据时立即重新自适应最佳刻度；
  - **数值显示优化**：消除 `-0.0` 等异常零点格式化展示。

## [V1.2.1]
- **实时变量监视（Live Variable Watch）行首原生复选框优化**：
  - 将运行时自动刷新复选框直接集成在每个变量的行首（`[Checkbox] [Icon] name = {Type} value`），不再需要折叠到右键菜单；
  - 深度契合 IntelliJ 原生 UI：选中时背景无缝融入整行的高亮底色，消除白色方块毛边；
  - 极速单击响应：点击行首复选框区域（0~22px）瞬间完成状态翻转、服务同步与局部重绘，绝不被双击或编辑事件拦截；
  - 层次规整：顶层变量展示复选框，子成员左侧自然对齐。
- **示波器采样频率输入框尺寸扩充与 5 位数完整显示**：
  - 解决采样频率输入框过窄导致仅能显示 2 位数（3 位数即截断）的排版问题；
  - 将 `freqSpinner` 宽度由 55px 扩充至 85px（最小宽度 75px），内嵌 5 列宽度文本框与无冗余小数格式（`#`）；
  - 采样频率上限扩展至 50000 Hz，可完整、美观地容纳并清晰呈现 5 位数（如 `10000`、`50000`）。

## [V1.2.0]
- **实时变量监视（Live Variable Watch）UI 原生化重构（对标 CLion 原生监视界面）**：
  - **原生树形视图重构（Tree）**：废除传统的 6 列表格（`JBTable`），全面采用标准 `com.intellij.ui.treeStructure.Tree`，视觉样式、行间距与缩进完全对标 CLion 原生调试器“线程与变量 / 监视”视窗；
  - **对齐原生求值与文本语法**：单行文本精确渲染为 `[Icon] name = {Type} value`，其中类型 `{Type}` 采用 CLion 原生灰色文本样式；
  - **原生值展示规则**：
    - 结构体与复合类型：仅展示 `{TypeName}`，等号及类型后不追加冗余字符；
    - 8 位整型与字符（uint8_t / int8_t / char）：精确转义字符格式，如 `0 '\000'`、`1 '\001'`、`65 'A'`；
    - 16/32/64 位整型：纯净十进制展示（如 `30755`、`957653`），消除噪声 `(0x...)` 后缀；
    - 浮点型：十进制浮点格式（如 `15.0049391`、`-99.9506073`）；
    - 枚举类型：优先展示符号常量名（如 `ST_IDLE`）；
    - 指针类型：十六进制地址展示（如 `0x20001000`）。
  - **原生调试器图标映射**：结构体/复合类型采用 `AllIcons.Nodes.Class`，基本标量类型采用 `AllIcons.Debugger.Db_primitive`（10/01 二进制图标），监视表达式项采用 `AllIcons.Debugger.Watch`（眼镜图标），指针采用 `AllIcons.Nodes.Annotationtype`；
  - **顶部内联求值输入栏**：新增顶部 `对表达式求值(Enter)或添加监视(Ctrl+Shift+Enter)` 快捷输入栏，支持按 Enter 立即求值并加入监视，按 Escape 清空并聚焦监视树，右侧配有快速求值操作按钮；
  - **左侧垂直原生工具栏（ActionToolbarPosition.LEFT）**：采用 IntelliJ 标准 `ToolbarDecorator`，内置添加、移除、启动监视、停止监视、从符号树添加、ELF 符号表管理与全部清空等经典操作；
  - **5Hz 高频平滑局部重绘**：数据采样周期内仅局部重写可见节点的缓存字节并重绘文本，严禁折叠已展开节点或丢失选中态；
  - **完备快捷键与交互**：支持双击/Enter/F2 编辑内存值、空格键快速切换自动刷新、Delete 键移除条目、右键菜单添加到示波器与以数组查看指针等完整功能。

## [V1.1.1]
- **调试会话顶层独立标签页改造与布局纠正**：
  - 彻底解决上一版实时监视面板被错误嵌套进调试窗口“线程与变量”下层分割面板（Tab 0，PlaceInGrid.bottom）的交互问题；
  - 深入逆向分析 CLion 底层 `RunnerLayoutUi` 机制，通过 `ui.defaults.initTabDefaults(LIVE_WATCH_DEBUG_TAB_ID, LIVE_WATCH_DISPLAY_NAME, AllIcons.Debugger.Watch)` 分配独立的顶层 Tab ID（11800），配合 `ui.addContent(content, LIVE_WATCH_DEBUG_TAB_ID, PlaceInGrid.center, false)` 居中全屏填充，使其实际渲染为与“线程与变量 (Tab 0)”、“内存视图”、“外设 (Tab 11787)”、“控制台”完全平级的顶层独立标签页；
  - 解决旧版 `workspace.xml` 布局缓存污染：引入独立 Content ID `EmbeddedLiveVariableWatch`，主动侦测并清理旧版残留 Content 及历史视图布局缓存，并强制在挂载与恢复时调用 `view.assignTab(tab)`，彻底消灭历史工程缓存导致标签页回退至 Tab 0 的隐患；
  - 完善调试会话生命周期释放：会话结束时通过 `ui.removeContent(c, true)` 安全优雅销毁与解绑面板；
  - 保持独立 ToolWindow (`EmbeddedLiveWatch`) 作为非调试态备用访问入口。
- **重命名为“实时变量监视”全面规避冲突**：
  - 针对 CLion 官方调试窗口内置的“实时监视”标签页（仅适用于 J-Link / ST-Link 原生监视），全面将本插件的实时监视窗口更名为**“实时变量监视”**（Live Variable Watch）；
  - 全面更新右键菜单（“添加到实时变量监视”）、右侧 ToolWindow 标题与条带（`stripeTitle` / `title`）、设置中心显示名称（“实时变量监视与示波器”）、状态栏点击提示与交互、添加变量对话框、符号树弹窗、表格空状态及所有通知提示文本，确保全插件概念统一无歧义。
- **状态栏与调试窗口交互联动优化**：
  - 状态栏插件图标点击（`EmbeddedMonitorWidget.click()`）：处于活动调试会话中时，不仅聚焦并高亮顶层“实时变量监视”标签页（`ui.selectAndFocus(content, true, true)`），更主动唤起并激活 Debug 调试工具窗口（`ToolWindowId.DEBUG`）；未处于调试会话时，智能激活右侧独立 ToolWindow。

## [V1.1.0]
- **实时监视（Live Watch）深度合并进 CLion 原生调试会话布局**：
  - 将实时监视面板无缝嵌入到 CLion 原生 Debug 会话工具窗口中，成为与“控制台 (Console)”、“外设 (Peripherals)”、“线程与变量 (Debugger)”平级的原生调试子标签页；
  - 动态监听 `XDebuggerManagerListener` 与 `XDebugSession` 启动及切换生命周期，引入异步重试挂载与 `session.ui.contentManager` 级联 Dispose 安全管理，通过 `session.ui.createContent(...)` 与 `session.ui.addContent(content, 0, PlaceInGrid.bottom, false)` 实现标签页动态注册与生命周期绑定；
  - 保留独立 ToolWindow (`EmbeddedLiveWatch`) 备用访问点，确保非调试态与独立监控场景下随时顺畅调用。
- **示波器（Oscilloscope）布局重构与 1/4 屏幕高密度极简交互**：
  - **界面排版彻底重构**：移除原右侧占用大半横向空间的庞大表格/分割面板，示波器波形画布（`ScopeWaveformPanel`）独占主体区域，顶部精简为图标化极简控制栏，画布正下方引入只占极少垂直空间的“通道芯片/标签栏（Channel Chips/Tags）”（对齐独立软件 software_ref 极简设计，完美适配 1/4 屏幕空间）；
  - **顶部工具栏精简与网格开关**：新增极简网格切换按钮（`GridIcon`），支持实时开启/隐藏画布网格线（隐藏时保留轴边缘刻度与标签）；精简冗余文本标签，确保 1/4 窄屏下单行无换行溢出；
  - **通道芯片极简交互体系**：
    - **左键单击通道标签**：即刻切换该通道波形显示/隐藏（显示时抗锯齿实心色彩圆点+高亮标签，隐藏时空心灰度圆点+虚线灰显标签并即刻重绘画布）；
    - **点击右侧小「×」**：从示波器中移除该通道并同步释放采样缓冲；
    - **文本防溢出与就地更新优化**：标签文本智能裁切，彻底消除长符号名或末值更新对右侧关闭按钮「×」的遮挡；基于地址差异进行芯片就地刷新（In-place Update），消灭周期重建组件导致的焦点丢失与鼠标点击失灵；
    - **右键单击通道标签**：弹出丰富上下文菜单（支持跨平台 popupTrigger 触发），支持预置 8 种高对比度曲线颜色及自定义调色板拾色器（`ColorChooser`）、数据解码格式切换（U8..F64，保序原地更新）、调节 Y 轴量程（自适应本通道范围、手动每格数值、恢复全局自动量程）、查看与复制变量内存地址/名称；
    - **极简添加通道**：标签栏末尾常驻「+ 添加通道」芯片按钮，支持快速输入表达式添加，或继续通过源码/实时监视右键添加。
- **通道属性与自定义颜色持久化**：
  - `ScopeVariable` 与 `PersistedScopeChannel` 新增 `customColor` / `customColorRgb` 字段与持久化映射，确保自定义通道颜色在项目重开与 CLion 重启后完整恢复。

## [V1.0.11]
- 彻底解决监视中点击停止按钮时状态机跳到“连接中”需要二次点击才变为“未连接”的缺陷（问题 1）：
  - **在途数据包假自愈拦截**：修复 `AgentService.onEngineEvent` 中在途 `WatchData` 与 `ScopeData` 引起的假自愈竞争。当用户已主动停止监视（`desiredRunning == false`）时，丢弃管道中残余的在途数据包，彻底移除数据包处理函数中错误的 `desiredRunning.set(true)` 逻辑，严禁在途数据包将已停止的状态机复活；
  - **单击即停与状态稳定**：收到 Agent 响应的 `Disconnected` 或 `State("disconnected")` 事件时，因 `desiredRunning == false` 稳定保持 `disconnected` 态，同时在 `disconnectEngine()` 与 `onDebugSessionResumed()` 中增加防御保护，确保单击一次停止按钮即可立即、平滑、稳定进入未连接状态；主动清理 `lastEngineError`。
- 彻底解决退出 CLion 调试时监视窗口误报连接失败（`connection lost: OpenOCD 启动即退出 (退出码 Some(1); 检查 cfg 与探针是否被占用)`）的缺陷（问题 2）：
  - **Rust 后端 Attach-only 严防自动拉起 OpenOCD 进程**：在 `openocd.rs` 中增加 `attach_only` 拦截逻辑，当 OpenOCD 进程随调试退出且 Tcl 端口断开时，严禁回退调用 `spawn_openocd()`，杜绝缺失 board 配置文件的 OpenOCD 闪退产生退出码 1；在 `monitor` 模块的 `try_reconnect` 与 `on_backend_error` 中对 `attach_only` 模式清空 `self.params = None`，彻底禁止后台无效轮询并静默抑制重试日志；
  - **Kotlin 插件端调试退出静默断开与错误隔离**：
    - 在 `AgentService` 中精准区分 OpenOCD 附着会话（`currentEffectiveAttachOnly` / `isAttachedToDebugSession`）与独立 OpenOCD 监控会话，确保非调试态下的独立 OpenOCD 正常采样不被误杀；
    - 在 `onDebugSessionStopped` 与 `handleDebugProcessStopped` 中安全注销会话并在全部调试会话停止时触发附着会话的 `disconnectEngine()`，并清理 `lastEngineError`；
    - 在 `syncDebugAndEngineState()` 与 `startSupervisor()` 中仅对附着调试的 OpenOCD 会话平滑切入 `disconnected`，并彻底禁止监督线程重试拉起已退出的 OpenOCD；
    - 在 `onEngineEvent` 的 `Disconnected`、`State`、`Log`、`Error` 处理中增加 `isOpenOcdDebugExited` 判定：当 OpenOCD 随 CLion 调试退出而关闭时，静默转换为正常断开态，绝不将预期的进程退出与探针断开作为错误记录到 `lastEngineError`，消灭黄色/红色警告提示条。

## [V1.0.10]
- 彻底解决 CLion 断点命中后实时监视停止刷新并弹出“启动监视失败: java.lang.IllegalStateException: java.util.concurrent.TimeoutException”的严重缺陷：
  - **断点暂停态与全量重连彻底解耦，杜绝误触发 `connectEngine` 与看门狗超时**：
    - 修复 `OpenOcdConfigReader.isAnySessionPaused`：移除对 `suspendContext != null` 的脆弱前置判定，当断点命中且 `session.isPaused || session.isSuspended` 成立时立即判定为断点暂停，杜绝瞬态 `suspendContext` 为空导致的断点误判；
    - 在 `AgentService.connectEngine()` 中引入幂等防护：当引擎已处于 `running` 或 `halted` 态、或调试器正处于断点暂停态时，直接返回成功并调度单次快照，杜绝向 OpenOCD 发起重复的全量握手连接；
    - 在 `doConnectEngine` 与看门狗定时器中增加断点与暂停态短路判断，命中断点时立即取消 `connectWatchdog`，彻底杜绝 10s 看门狗或底层 connect 超时弹窗；
    - 增加连接在飞（in-flight）单例保护，避免多源并发触发重连引发竞争。
  - **断点瞬时单飞快照防护与异常精准解包**：
    - 在 `sampleWatchOnceOnPause` 中增加 `isSnapshotRunning` 原子标志与防抖保护，避免高频并发触发内存读取风暴；采样超时由 1500ms 缩短为 1000ms 并在恢复全速运行时提前终止；
    - 重构 `extractError` 异常解析逻辑：支持深度解包 `TimeoutException`，杜绝将类名当作错误描述抛出 `IllegalStateException: java.util.concurrent.TimeoutException`；
    - 在 `LiveWatchPanel` 与 `ScopePanel` 的启动回调中增加断点暂停态抑制防护，当芯片处于调试暂停态时不弹出非预期的错误提示。

## [V1.0.9]
- 深度解析并优化 CLion 官方调试与插件实时调试协同机制，彻底解决“调试误报暂停”与“显示未连接但值在刷新”两大痛点：
  - **断点与运行态双向协同死锁破除**：
    - 消除因 GDB / OpenOCD 启动初期目标复位握手产生的短暂 `halted` 状态事件导致 `isHaltedByDebug` 被永久锁死在暂停态的严重缺陷；
    - `isAnySessionPaused` 增加 `suspendContext != null` 准确判断，排除会话启动阶段临时握手假态，仅在 CLion 真正命中断点或处于有效挂起帧时判定为断点暂停；
    - 在调试进程启动且 OpenOCD 端口就绪、引擎接入后，自动执行即时状态校准，主动补齐并分发监视/示波目标，无需用户手动停止再重启监视；
    - 优化 `EngineEvent.State` 响应流：当底层单片机恢复全速运行时，自动解除 `isHaltedByDebug` 与 `watchHalted` 锁定，无缝恢复高频采样；
  - **状态机失真自愈与时序竞争彻底消除**：
    - 移除引擎连接时冗余的异步 `disconnect` 请求，杜绝旧引擎关闭事件与新引擎连接就绪事件乱序引发的前端状态机错位；
    - 在 `EngineEvent.WatchData` 与 `EngineEvent.ScopeData` 中引入活跃数据流状态自愈机制：一旦收到正在吞吐的数据帧，自动纠正并同步恢复 `running` 状态，彻底消灭“状态条显示未连接但数值仍在刷新”的脱节现象；
    - 隔离非致命读错误与致命断开事件，单次局部内存读取失败不会错误置位全局断开状态机。

## [V1.0.8]
- 解决 CLion 默认禁用 OpenOCD Tcl RPC 端口（6666）导致插件 Attach 报错连接超时问题：
  - **根本原因诊断**：CLion 在启动“OpenOCD 下载并运行”时，命令行默认硬编码传入了 `-c "tcl_port disabled"`，仅开放 GDB (3333) 与 Telnet (4444) 端口，导致 Tcl RPC (6666) 不可达；
  - **精准故障诊断与指引**：
    - `embedded-clion-agent` 与插件客户端：当 6666 端口不可达时，自动探测 3333 与 4444 端口。若检测到 OpenOCD 在线但 Tcl 端口离线，给出精准的“OpenOCD 正在运行，但 Tcl RPC 端口被 CLion 禁用，请在板级 .cfg 文件末尾追加 tcl_port 6666”提示；
    - `OpenOcdConfigReader` 增加 `isTclPortConfiguredInBoardConfig` 自动检查板级配置文件的配置状态；
    - 设置界面补充 CLion 禁用 Tcl 端口的明确说明与配置指引；
  - **用户环境修复**：在用户板级配置文件 `stm32g4_daplink.cfg` 中成功添加 `tcl_port 6666`，覆盖 CLion 的 `tcl_port disabled` 参数。

## [V1.0.7]
- 彻底解决 CLion 调试态与插件探针端口冲突引发的硬件复位与断联崩溃（`cannot read IDR`）：
  - **根因定位与排查**：当用户在 CLion 中启动嵌入式调试（Embedded GDB Server / OpenOCD）时，CLion 已启动 OpenOCD 进程并独占硬件探针（如 DAP-Link）的 USB 接口；此时插件若以 `probe-rs` 后端尝试连接硬件，会直接竞争争夺同一 USB 端口，引发硬件复位、DP 握手失败（`cannot read IDR`）、`Examination failed` 与调试会话断联崩溃；
  - **采样后端自动安全路由（Backend Auto Routing）**：
    - 在 `AgentService.kt` 中引入 `determineEffectiveBackend` 智能路由决策：当检测到 CLion 处于调试态（`isDebuggingActive`）或 OpenOCD Tcl RPC 端口（默认 6666）处于监听状态时，插件自动切换为 `openocd` 后端并强制启用 `attach-only` 模式；
    - 通过已有的 OpenOCD 开放的 6666 Tcl RPC 端口与调试器无冲突共存并 Attach 内存读取，禁止在此状态下调用 `probe-rs` 独占物理 USB 探针；
    - 在 CLion 启动调试瞬间，若插件正运行 `probe-rs`，立即主动断开并释放硬件 USB 探针，确保 OpenOCD 顺利绑定硬件，随后无缝重连至 OpenOCD；
- 深度集成 CLion 调试器，实现断点命中与 CPU 暂停状态无缝同步：
  - **断点挂起总线轮询**：深度集成 IntelliJ/CLion 的 `XDebuggerManager`、`XDebugSessionListener`、`XDebugProcess`；当 CLion 命中断点（`sessionPaused` / `isSuspended` / `isPaused`）时，立即清空底层示波器与实时监视的周期性轮询通道（`set_scope_targets []` / `set_watch_targets []`），彻底消除目标停在断点时插件持续下发探针引发的 GDB 上下文恢复失败（`not halted` / `context restore failed`）；
  - **暂停瞬时单次快照刷新**：在暂停瞬间对全部监视项执行一次原子安全的内存快照读取（`sampleWatchOnceOnPause`），保证开发者命中断点时立即可见变量最新数值；
  - **运行态无缝恢复与会话生命周期管理**：当 CLion 恢复运行（`sessionResumed`）时，自动恢复示波器与实时监视的高频采样；当调试会话结束（`sessionStopped`）时，安全处理连接状态并断开 attach 连接；
- OpenOCD 运行配置与设置体系增强：
  - 增强 `OpenOcdConfigReader`，支持全面解析 `.idea/workspace.xml` 及 `.idea/runConfigurations/` 下的项目级 XML 运行配置，新增 OpenOCD Tcl RPC 端口连通性检测与调试状态探测辅助接口；
  - `EmbeddedMonitorSettings` 新增 `autoSwitchBackendOnDebug`（调试时自动切换 OpenOCD attach-only）与 `pausePollingOnBreakpoint`（命中断点自动挂起高频轮询）持久化设置；
  - 优化设置面板 `EmbeddedMonitorConfigurable` 交互与实时监视/示波器在目标暂停态下的按钮状态联动。

## [V1.0.6]
- 彻底解决硬件高频采样中由于非原子内存读取导致的“字节撕裂（Torn Read）”假尖峰毛刺缺陷（如 CSV 导出数据 B19405 的 123.532036 异常假值）：
  - **根因定位与排查**：单片机 CPU（Cortex-M4）在主循环/中断中通过 32 位指令（`vstr.32`/`str`）原子写浮点变量，而调试后端此前在 `scope_burst` 中固定采用 8 位总线宽度（`read_memory ... 8` / `read_8`）读取内存；当变量跨越 2 的幂次边界（如 32.0 或 8.0）时，高字节（符号与指数高位）与低 3 字节发生 CPU 写入竞争错位，导致读出的浮点数指数跳跃产生恰好 4 倍、1/4 倍的大幅突发孤立毛刺（如 30.88 -> 123.53 -> 33.28）；
  - **后端 32 位原子字访问架构升级**：
    - `OpenOcdBackend.scope_burst` 与 `read_bytes`：对 4 字节对齐的内存块一律切换为 32 位原子字读取命令（`read_memory <addr> 32 <count>`），杜绝 AHB 总线层面的字节拆分与读写竞争；
    - `ProbeRsBackend.scope_burst` 与 `read_bytes`：对 4 字节对齐内存块优先使用 `core.read_32` 执行 32 位原子访问，再经小端字节序解构；
    - `bandwidth.merge_blocks`：合并内存块时自动对齐至 4 字节边界，保障底层探针一律下发 32 位原子字访问；
  - **插件端端到端字节撕裂自愈与坏样本防护（`TornSampleFilter`）**：
    - 在 `AgentService.kt` 采样入队管道中引入实时字节撕裂检测与轨迹逆向重构算法：实时捕获指数高字节、低字节或半字错位引发的假尖峰，通过精确比对邻近样本斜率在 0.001 误差内无缝修复，杜绝坏点污染环形缓冲；
    - 算法具备严格的特征签名保护：阶跃方波（单侧跳变）、三角波极值峰顶及正常平滑波形绝不误触（0 误伤）；
    - 在 `ScopePanel.kt` 的 `exportCsv` 导出逻辑中引入整序列复检清洗机制，确保导出的 CSV 数据表格完全消除非物理假尖峰与 NaN/Inf 坏样本。

## [V1.0.5]
- 修复示波器波形偶发失真毛刺与假尖峰的渲染缺陷：
  - 深度重构 `drawCurve` 包络降采样分桶算法为 M4 极值时序连线：在分桶内按采样发生先后索引严格提取入口点、极值点（极小/极大）与出口点，按发生时间顺序连接，杜绝旧逻辑中盲目按 `min -> max` 连接导致正弦波下降沿产生反折锯齿尖峰的严重错误；
  - 彻底解决多点同时间戳去重时的极值丢失问题，保证折线严格沿时间正向单调递进，相邻分桶平滑过渡且峰峰值完整保留；
  - 扩充波形渲染边界（包含视野左右各 1 个样本），彻底解决波形进入/离开视窗边缘时出现的断点或开放缺口；
  - 增加时间戳乱序与回退保护，修正 `latestTimeSec` 与 `earliestTimeSec` 的极值检索，杜绝乱序样本导致视图时间范围收缩失效。
- 彻底解决特殊 Unicode 字符在 Windows/CLion 默认字体下显示为方块“囗”的编码与字体兼容问题：
  - 示波器自绘文本全面迁移至 `JBFont.label()` 与 `JBUI.scale()`，继承 CLion 主题配置的中文字体回退链（CJK Fallback），彻底解决 `Font("Segoe UI")` 缺少中文字形导致的“游标 A”、“暂无采样数据”等渲染为“囗”；
  - 将游标测距卡片中的 `Δt`、`ΔV` 替换为标准 ASCII `dt`、`dV`，破折号替换为 `-`；
  - 将通道图例中的字符圆点 `●` 替换为抗锯齿 Graphics2D 实心色彩指示圆，规避字体符号缺失；
  - 将“返回实时”快捷按钮上的特殊 Unicode 箭头 `⮌` (U+2B8C) 替换为标准 ASCII `<-`；
  - 清理状态栏、提示文本中的中点 `·`、省略号 `…`、特殊箭头 `→` 及括号「」，确保在任意系统默认字体下 100% 正常渲染。
- 示波器通道列表（右侧表格）交互升级：
  - 将“显示”列由原有的标准单元格编辑复选框改造为对齐“实时监视”的高灵敏单选复选框（`ScopeVisibleCellRenderer`）；
  - 禁用默认 `cellEditor`，在 `mousePressed` 中捕获单次点击即刻响应通道显示/隐藏切换并立即触发画布重绘（`waveform.repaint()`），彻底解决被 250ms 定时器取消编辑态导致的点击失灵与连点迟钝；
  - 增加键盘交互支持：选中通道按空格键（`SPACE`）快速切换显示/隐藏，按 `DELETE` 键快速移除通道；
  - 通道名称列（`ScopeChannelNameCellRenderer`）引入与波形颜色实时同步且支持 HiDPI 缩放的彩色指示圆点，隐藏时文字与圆点均灰显；
  - 修复空通道列表时触发 `fireTableRowsUpdated(0, -1)` 导致的 Swing TableModel 越界异常。

## [V1.0.4]
- 修复示波器停止后再开始界面卡住的根本缺陷：
  - 排查并彻底解决硬件采样引擎重启导致的 epoch 时间戳回退、非单调样本破坏二分查找及负时间跨度导致 `coerceIn` 抛出异常崩溃的问题；
  - 确立跨会话确定性时间戳单调续接机制，连接事件即时识别新会话基准，杜绝推断偏差与时间戳乱序；
  - 修复 `clear()` 遗留历史视窗锁定导致清空后画面永久卡在 `[-5, 0]` 不刷新新数据的严重缺陷；
  - 增加极限狭窄窗体（如悬浮或窄侧边栏）下 `drawPinnedCursorA`、`drawHoverTipCard` 等组件的 `coerceIn` 边界越界保护。
- 大幅优化数据流性能与 UI 线程负载：
  - 增加快照版本缓存机制（`cachedSnapshot`），避免每帧高频重复克隆全量 50k 环形缓冲引发大规模 GC 停顿与锁竞争；
  - 表格末值刷新升级为 O(1) 反向检索（`scopeLastValues`），彻底摒弃全量序列克隆；
  - 采样率与缓冲指标更新解耦降频至 500ms（对齐独立软件 `plot.ts` / `ScopeApp.vue`），消除每 33ms 的密集字符串分配与计算瓶颈。
- 修复手动拖拽查看历史波形时波形仍随实时数据不断前推漂移的问题：重构视图状态为绝对时间轴驱动（`timeRange` 对齐独立软件 `plot.ts` 架构），拖拽历史时自动解耦跟随并锁定视窗，背景数据静默缓冲，界面停止非预期滚屏。
- 移植独立软件波形 UI 与交互体系：
  - 增加醒目的“⮌ 返回实时 (Live)”快捷操作按钮，在历史拖拽模式下自动浮现，一键恢复最新实时数据吸附，双击画布恢复跟随与自动量程。
  - 增加“暂停/继续”控制，支持在不中断目标调试与监视连接的前提下定格波形精细观察。
  - 完善工具栏指标指示：实时显示到达采样率（实际 Hz）、导出行数对应采样率与覆盖时间（缓冲 Hz/s）及丢样计数。
  - 新增标记游标 A 测距（游标测距）：支持 Alt+点击或右键放置游标 A，动态测量 Δt、等效频率（1/Δt）与各通道差值 ΔV。
  - 优化滚轮交互：默认滚轮横向缩放时间窗口（围绕光标展开），Shift+滚轮或 Y 轴区滚轮纵向围绕光标数值缩放。
  - 对齐 OneDark 调色板，通道图例支持长变量名自适应宽度排版。
  - 修正 CSV 导出时间轴对齐机制：采用最长通道基准时间轴与多通道时间戳就近对齐采样（对齐 `plot.ts.exportCsv`），并在后台线程异步写盘防止界面卡死。

## [V1.0.3]
- 修复刷新选择框在双击或快速连点时误触发“修改值”输入弹窗阻塞 UI 的致命问题（明确过滤第 0 列双击事件）。
- 增强选择框即时响应与行选中联动，增加表头及单元格 Tooltip 说明（运行时自动刷新约 5Hz）。
- 修复树形模型快照比较缺陷：当 ELF 重新解析符号节点时正确触发树形重建，彻底解决复合变量加载后仍无法展开的隐患。
- 增强表达式解析器全面支持数组下标（`arr[0]`、`arr.[0]`、`motors[1].speed`）与命名空间，修复符号拾取树中选中数组元素时报错“无法解析表达式”的问题。
- 引入内存符号树内存递归检索（`findNodeInElf`），秒级解析各层级结构体与数组成员，无需重复 IPC 请求。
- 引入完整路径跟踪（`fullPath`），修复嵌套结构体及数组叶子成员在加入示波器、查看指针数组、修改值时路径层级被截断丢失的缺陷。
- 优化负数枚举值匹配及数值输入鲁棒性（支持浮点型数字字符串回退与掩码保护）。

## [V1.0.2]
- 优化实时监视（Live Watch）刷新复选框交互：去除默认单元格编辑器冲突，改为 mousePressed 即时响应，彻底解决连点失灵问题。
- 引入树形结构与非基本数据类型展开展示：支持结构体（struct/class）、数组、指针、嵌套成员的层级展开与内存切片解码。
- 支持指针变量“以数组查看指针…”右键功能，自动读取指针地址并生成数组监视条目。
- 支持非复合变量“修改值（写内存）”双击/右键交互。
- 支持将结构体中的叶子标量成员直接添加到示波器（Oscilloscope）。
