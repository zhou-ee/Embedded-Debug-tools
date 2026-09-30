# 示波坑洼与项目审查报告

审查日期：2026-09-30。代码基线：`fb27d30`，Plugin V1.2.28 / Agent v1.2.28。

> 本文记录修复前的审查与复现结果。四项问题已在 V1.2.29 修复，后续回归、真机复测及安装包见 [修复记录](2026-10-01-scope-fix.md)。

已通过 STM32G431CBTx + Flash Pro(CMSIS-DAP) 真机实测确认：**当前前端的 TornSampleFilter 会把正常下位机采样值误判为撕裂值并改写，直接产生波形坑洼。采样间隔不均匀会扩大误判。** 这个问题在 probe-rs 和 OpenOCD 两条链路的原始数据回放中均可复现。

此次没有修改插件或下位机源码，没有烧录或复位。实测结束后恢复了首次附加时看到的目标暂停状态，关闭了本次建立的 Agent 和 OpenOCD 测试进程。没有在 CLion 画布上自动操作；验证方式是采集真实 Agent RPC 字节数据，并直接调用现有 Kotlin 编译类，按 AgentService 的摄取顺序回放。

## 1. P1：正常正弦极值被重构，原始数据和 CSV 都会失真

代码位置：

- `plugin/src/main/kotlin/org/embedded/monitor/core/TornSampleFilter.kt:38–109`
- `plugin/src/main/kotlin/org/embedded/monitor/agent/AgentService.kt:1705–1713`
- `plugin/src/main/kotlin/org/embedded/monitor/scope/ScopePanel.kt:869–873`

`repairIfTorn` 只根据三个数值判断尖峰，不接收样本时间戳。正常正弦峰值的两侧本来就可能接近；不均匀采样、跨过大段时间之后，前后值接近也不能说明中间值错误。`repairF32` 尝试把相邻值的高字节或高半字拼进当前值，只要候选靠近两侧均值就接受。这样的位模式拼接并不能证明内存读取发生了撕裂。

最小复现，直接调用当前 Kotlin 编译类：

```text
正常正弦：16.18034 → 20.0 → 16.18034
修复结果：中间值 20.0 被改成 16.125
```

真机实例：

| 条件 | MCU 原始值 | 前端改写值 | 证据 |
| --- | ---: | ---: | --- |
| probe-rs，1 kHz，用户通道地址 + 诊断通道 | -19.9512043 | -18.0762043 | `pr_1000_user.csv`，零基索引 1484，MCU tick 424888 |
| probe-rs，200 Hz | -19.9655647 | -15.2952824 | `pr_200_user.csv`，零基索引 18，MCU tick 447787 |
| probe-rs，200 Hz，下一采样晚到 24.594 ms | -13.8091335 | +3.45228338 | `pr_200_user.csv`，零基索引 2126，MCU tick 458781 |
| OpenOCD，200 Hz | -19.9687653 | -15.2968826 | `ocd_200_user.csv`，零基索引 143，MCU tick 768037 |

前两行并非猜测尖峰应有的形状：原始值能匹配测试固件按 MCU 毫秒计数计算出的单精度正弦值。模型按 `main.c` 的浮点乘法顺序计算，允许 tick 前后各 2 ms，以容纳 CPU 字段更新和调试读块之间的时间差。在这个较宽容的判据下，改写后的值仍明显错误。

12 秒 probe-rs / 200 Hz 数据共 2,267 帧，当前过滤器改写了 81 个通道样本；其中 69 个正弦样本明确从正确值变成误差大于 0.05 的值。1 kHz / 15 Hz Watch 的 12 秒数据中也有 12 个已确认的新错误。OpenOCD / 200 Hz 的 10 秒数据有 67 个已确认的新错误。

摄取端先覆盖历史采样值；CSV 导出又对这份数据调用 `repairSeries`。因此 GUI 和导出都无法保留真正的原始数据。NaN 还会被 `repairIfTorn` 插值成正常数字，所以补入 NaN 后仍需防止过滤器把缺口再次抹平。

建议优先停止对原始采样做启发式位重构，保留原始字节与原始解码值。异常检测可产生标记；需要平滑时使用单独的可选显示序列，导出原始数据，并覆盖正常极值、不等间隔、合法脉冲及 NaN 缺口的回归用例。

![当前过滤器在真机原始数据中产生的坑洼](../../tmp/scope-audit-20260930/filter_notches.png)

## 2. P1：退出 attach-only 会话会清除外部观察点并恢复外部暂停的目标

代码位置：`agent/crates/monitor/src/lib.rs:443–462`。

退出引擎时无条件给全部四个 DWT FUNCTION 寄存器写 0，没有判断是否由本会话配置，也没有排除 OpenOCD attach-only。随后只要目标处于 halted 就执行 resume。复用 CLion/GDB 的 OpenOCD 时，这些观察点或暂停状态可能属于 GDB；停止监视会删除外部观察点，并让断点处的 MCU 继续运行。

用假的 Tcl 服务验证了未修改的 release Agent，未触碰真实硬件观察点：本会话没有设置任何观察点，退出仍发出四次清零写入；模拟外部暂停后，退出仍发送 `resume`。证据：`tmp/scope-audit-20260930/mock_results.json`。

建议按本会话实际拥有的比较器清理；对共享 OpenOCD 会话保留外部暂停状态。相关策略必须同时覆盖正常退出、重新连接和引擎重建。

## 3. P2：失败通道被省略，丢样计数和绘图隐藏了缺失

代码位置：

- `agent/crates/monitor/src/lib.rs:1485–1493`
- `plugin/src/main/kotlin/org/embedded/monitor/agent/AgentService.kt:1691–1704`
- `plugin/src/main/kotlin/org/embedded/monitor/core/ScopeWaveformPanel.kt:903–919`
- `plugin/src/main/kotlin/org/embedded/monitor/scope/ScopePanel.kt:903–922`

后端降级为空块后，`extract_from_blocks` 返回 None。引擎只为提取成功的目标插入键，失败通道不会以空字节或缺失标记进入 RPC。前端却仅在遍历到未知地址或空字节时递增 dropped，因此“失败目标没有键”不会计数。

协议故障注入：两块中一块持续失败，Agent 发出 40 帧，失败通道在 40 帧中全部缺失；按现有前端规则计算的丢样仍为 0。整个帧缺失及调度空档同样没有序号或遗漏计数。

画布只在 NaN 处断线，没有按长时间间隔断线；CSV 的最近邻取值没有距离限制，会用其他时刻的值填补缺失通道。这会把没有采到的数据表现为斜线、台阶或看似完整的数据。

建议为每个目标显式传递缺失状态，保留帧序号和实际时间；统计通道缺失与超期采样，绘图断开长空档，CSV 对没有对应样本的格子留空。

## 4. P2：ELF 重载后示波通道沿用旧地址

代码位置：`plugin/src/main/kotlin/org/embedded/monitor/agent/AgentService.kt:1834–1838, 1989–2003, 2594–2610`。

ELF 加载成功后只重解析 watchItems。示波通道恢复直接采用持久化的 `c.address`，已经存在的 ScopeVariable 也没有重新定位。当重新编译改变全局变量布局，或指针指向的新对象地址不同，Watch 可以显示新值，Scope 仍读取旧地址，通道名称不变而实际信号变成其他内存内容。当前真机的三个用户通道地址经 ELF 和运行态指针复核是正确的，因此这不是本轮坑洼的直接原因。

建议区分按表达式订阅与裸地址订阅；ELF 重载后重新解析表达式通道，在恢复或重连后更新动态指针通道，并清理旧序列。

## 真机采样的时序证据

使用 `g4_tool_test/cmake-build-debug-stm32/g4_tool_test.elf`。从 ELF 解析 `uwTick/sin_1hz/sin_5hz/sin_20hz/wave_phase`；用户第三通道通过运行态 `g_chassis_ptr` 加 DWARF 成员偏移解析为 `0x2000044c`，与项目持久化配置一致。诊断通道与正弦通道合并在一个连续读块，加入用户第三通道后为两个读块。Watch 对照是一个 tick 目标，不能代表任意复杂 Watch 列表的开销。

共 10 组约 110 秒采集，得到 83,185 帧。安装的 Agent 与仓库 release Agent 的 SHA-256 一致：`dcd77dc38b75d05db1692921a45a3747c708d151758842452e5f3b994ff0a9f2`。

| 后端与条件 | 缓冲时间戳口径 Hz | 间隔中位数 ms | p95 ms | 最大间隔 ms | 大于 3 个设定周期的间隔 |
| --- | ---: | ---: | ---: | ---: | ---: |
| probe-rs，1 kHz，一个合并块 | 958.6 | 0.973 | 2.205 | 24.337 | 275 |
| probe-rs，1 kHz，用户地址 + 诊断通道 | 921.5 | 0.942 | 2.530 | 35.084 | 400 |
| probe-rs，1 kHz，上述通道 + 15 Hz Watch | 834.2 | 0.995 | 2.652 | 52.443 | 410 |
| probe-rs，200 Hz，用户地址 + 诊断通道 | 191.0 | 5.042 | 9.178 | 66.190 | 22 |
| OpenOCD，1 kHz，用户地址 + 诊断通道 | 897.5 | 0.934 | 2.410 | 24.893 | 270 |
| OpenOCD，1 kHz，上述通道 + 15 Hz Watch | 842.8 | 1.001 | 2.502 | 41.875 | 277 |
| OpenOCD，200 Hz，用户地址 + 诊断通道 | 195.1 | 5.052 | 7.566 | 29.915 | 12 |

两后端的 `scope_burst` 都用 `std::thread::sleep` 等待批内截止时刻；超期后继续按同一批的旧截止时刻追赶。主循环的高精度等待没有覆盖这段批内等待。Watch、状态轮询、Core 重建和操作系统调度也占用采样时间。此次未对各段耗时做分项插桩，因此不能把每个长间隔都归到某一个操作；已经验证的结论是间隔显著不均匀，且数值过滤器没有考虑这些间隔。

本轮所有帧中请求的通道均有值，时间戳、MCU tick 和 wave_phase 没有倒退。按上述正弦模型窗口，没有原始正弦样本出现大于 0.05 的偏差；这不能排除更稀有的探针异常，但本轮没有复现既往日志中归因于 DAP 的符号翻转。当前过滤器自身反而产生了符号翻转。

## 验证和复现文件

- `cargo test --workspace --locked --offline`：46 个测试通过。
- `plugin/gradlew.bat test --offline --console=plain`：131 个测试通过。
- 最小反例与真机回放：`tmp/scope-audit-20260930/FilterAudit.java`，使用 JDK 25 和 `plugin/build/classes/kotlin/main` 中的实际类。
- 真机采集：`tmp/scope-audit-20260930/capture.py`；需先确保探针可用，OpenOCD 测试需已有监听的服务。
- 原始字节和时间戳：同目录 `pr_*.jsonl` / `ocd_*.jsonl`；CSV 及 `_filtered.csv` 为解码和实际函数回放结果。
- 模型校验：`analyze.py` / `analysis.json`。
- 采样统计：`pr_summary.json` / `ocd_summary.json`；Agent 和 ELF 文件身份在各自 `_metadata.json`。
- 无硬件协议故障注入：`mock_checks.py` / `mock_results.json`。

现有测试通过不能覆盖本次缺陷：正常峰值用例使用了邻居值恰好为整数的特殊组合，没有覆盖真实单精度正弦样本、不均匀采样及共享调试会话的退出行为。优先修复原始值改写与共享会话清理，再处理缺失样本表示和通道重新定位。
