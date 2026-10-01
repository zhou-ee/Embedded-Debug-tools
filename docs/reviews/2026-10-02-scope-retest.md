# V1.2.35 示波剩余空档真机复测

日期：2026-10-02，Asia/Shanghai。源码基线：`c1f1b7ab8b0c1a1c32ca01902ad18ed798cdeb80`。

## 结论

剩余空档已经定位到两类执行区间：**同步探针/USB 收发调用内部的长耗时，以及主机等待/调度超期**。后端超期后再等待一个完整采样周期，会进一步拉长相邻点间隔。开始读取时打时间戳还会使缺口在时间轴上的位置与 MCU 实际采样间隔错位。

这轮修复确实接通了 Watch 插帧，并将整批失败的首次退避从 50 ms 降为 2 ms；但是成功的慢读取不会触发这套恢复策略。主机连续轮询变量时，一个调用停顿就意味着该期间没有读取到其他采样点。平均吞吐有余量与偶发延迟同时存在。

已经用不修改生产二进制的三通道采集，以及持续保留一个 Core 的直接 probe-rs 读取分别复现。无需 CLion 调试、OpenOCD、Watch 或状态轮询也能出现，故这些功能不是空档出现的必要条件。

## 测试条件与边界

- STM32G431CBTx + Flash Pro(CMSIS-DAP)，Windows 11 26200，i5-13500HX，20 个逻辑处理器。
- 生产版 Agent 与安装版的 SHA-256 一致：`ce105f438ea57fa32c52859a1780efd203cb2d85761c090850765d7bab319465`；测试前后均核对过。
- 固件 ELF：`E:\Software\Develop\Embeded\Pack\g4_tool_test\cmake-build-debug-stm32\g4_tool_test.elf`。
- ELF SHA-256：`b39cb121e8845fe44ed886bcf2a203417e602a684d7bc2a45a68c5295b91cbe1`。
- 真正对应界面配置的三个通道为 `sin_1hz=0x20000094`、`sin_5hz=0x20000098`、`sin_20hz=0x2000009c`，可合并为一个 12 B 读块。
- 为定位采样时刻，诊断副本另加入 `uwTick=0x20000090`、`wave_phase=0x200000a0`，形成一个 20 B 读块。这与生产版 12 B 读块不同，不能直接比较两组吞吐数值。
- 默认 4 MHz SWD；另做 1 MHz 对照，结束后用 4 MHz 的恢复程序重新连接。
- 没有烧录、复位或修改固件，没有修改生产源代码或替换插件。仅在项目 `tmp/scope-audit-20261002/` 下创建测试程序、诊断源码副本和证据；本报告为新增文档。
- 编译与重分析均在硬件采样窗口之外进行。诊断记录先写入有界内存缓冲，停止采样后导出；诊断副本存在测量开销，因此生产版与直接读取对照均单独保留。
- 初次连接发现目标为 halted。测试中临时运行目标；结束后直接使用后端恢复 halt，再断开重连验证 `target_halted_after_disconnect=true`。测试进程已退出，未终止原有 CLion/Agent 进程。

## 生产版结果：每组 30 秒

空档定义为相邻原始 `sample.t` 间隔大于三个设定周期：1 kHz 为 >3 ms，200 Hz 为 >15 ms。保持原始事件顺序，不排序时间戳、不插值、不补点。所有这些组均无时间倒退，三路返回字节均完整。

| 场景 | 实际 Hz | 最大间隔 ms | 空档数 | 批间 / 批内 |
| --- | ---: | ---: | ---: | ---: |
| 1 kHz，状态轮询开启，无 Watch | 969.1 | 39.247 | 125 | 10 / 115 |
| 1 kHz，状态轮询关闭，无 Watch | 991.5 | 51.627 | 19 | 0 / 19 |
| 1 kHz，状态轮询关闭，15 Hz Watch 插帧 | 995.1 | 35.060 | 12 | 0 / 12 |
| 1 kHz，状态轮询关闭，10 Hz GPIOC/ODR 读取 | 892.7 | 100.550 | 453 | 26 / 427 |
| 1 kHz，状态轮询、15 Hz Watch、10 Hz ODR 读取 | 791.5 | 57.444 | 856 | 71 / 785 |
| 200 Hz，状态轮询开启，无 Watch | 186.3 | 82.105 | 77 | 12 / 65 |
| 200 Hz，状态轮询、15 Hz Watch、10 Hz ODR 读取 | 184.1 | 46.608 | 96 | 19 / 77 |

这轮更长的记录中仍捕获到少量 >50 ms 事件，不能宣称它们已经完全消失。它们不一定来自旧的固定 50 ms 退避。

各场景只做了一次上述窗口，底层延迟明显随时间变化；后续诊断窗口甚至出现关闭轮询比开启轮询更慢的情况。因此不能仅凭这张表的均值，认定某个功能导致了全部差异。这里最可靠的区分是：**停用这些功能后空档仍存在，而且大多在批内。** GPIOC/ODR 读取为经同一 RPC 路径主动发出的 10 Hz 请求；真实寄存器面板只有可见时才刷新，保存的刷新配置不代表当时必然在执行。

## 已确认原因 1：成功的同步 USB 收发也会阻塞采样线程

调用路径：

`Engine::sample_scope → ProbeRsBackend::scope_burst_watch → Core::read_32 → CMSIS-DAP send_command_inner → device.write / device.read`。

生产入口：[probers_backend.rs](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:396>)。

在仅增加诊断回调的 probe-rs 0.31.0 副本中，记录了 USB 发送、接收及帧读取的开始和结束时刻。一个可逐项对照的事件为：

| 分段 | 耗时 ms |
| --- | ---: |
| 突发 76，第 9 帧 `read_32` | 20.3974 |
| 该读取内部 USB 收发累计 | 20.3902 |
| 其中最长 USB 发送（DAP_Transfer，0x05） | 15.9951 |
| 其中最长 USB 接收 | 1.9180 |
| 读取后等待下一拍 | 0.9997 |
| 第 9→10 帧原始时间戳间隔 | **21.3980** |

这组记录没有读取重试、USB 返回错误、状态轮询或命令维护。15 秒的关闭轮询诊断窗口共 5 个 >3 ms 空档，全部对应前一帧读取超过周期；加入 10 Hz ODR 的诊断窗口共 10 个空档，也全部在读取区间内。它们不是整批失败后的避让。

USB 调用耗时包含设备响应、USB 驱动及调用线程获得 CPU 的等待；当前证据定位到了调用边界，**还不能进一步断定是探针固件、驱动还是内核调度占主要比例**。不能把所有延迟直接命名为 SWD WAIT 重试。

## 已确认原因 2：读取超期后的重锚定再增加一个周期

[probers_backend.rs:466](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:466>)：

```rust
due += interval;
let now = Instant::now();
if now > due {
    due = now + interval;
}
```

读取已经迟到后，代码仍从读取结束时再等完整一拍。上面的 20.397 ms 读取因而形成约 21.398 ms 的点间隔。200 Hz 下同样的逻辑再增加约 5 ms。它避免追赶连读，但会丢失原有采样相位并扩大空档。

下一轮可以评估沿固定时间轴跳过已失拍、选择下一个未来截止时刻，保持不追赶；这只能减少附加等待，不能补回 USB 阻塞期间未读取的点。

## 已确认原因 3：主机节拍等待也会超期

直接读取程序绕过 Engine、TCP、前端、状态轮询、Watch 和批间 Core 重建，保留一个 Core，沿用当前混合等待与超期重锚定算法：

| 对照 | 20 秒帧数 | 最大间隔 ms | 证据 |
| --- | ---: | ---: | --- |
| 1 kHz，普通优先级，5 个诊断字 | 17,411 | 65.278 | 前次读取 64.278 ms，下一拍等待约 1 ms |
| 1 kHz，提高当前线程优先级，5 个诊断字 | 17,670 | 58.254 | 一次等待迟到 57.254 ms；提高优先级仍未消除空档 |
| 200 Hz，普通优先级，5 个诊断字 | 3,803 | 134.978 | 前次读取仅 0.252 ms，本次等待迟到 129.978 ms |

1 kHz 通常走忙等，200 Hz 通常先 `thread::sleep` 再忙等；这些都不是有硬性截止保证的执行环境。[批内等待代码](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:373>)、[引擎外层等待](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/monitor/src/lib.rs:456>)。

另一段 200 Hz 深层诊断捕获 22.111 ms 的批间空档：该窗口 USB 收发累计仅约 1.447 ms，主体延迟在后端突发之外。外层等待未单独加标记，故此事件不能进一步区分外层 sleep、被抢占或解包之间的调度延迟。直接 Core 对照中的等待超期则有独立时间测量。

`timeBeginPeriod(1)` 请求定时器精度，不提供采样线程按时获得 CPU 的保证；Windows 11 还存在特定不可见窗口进程的定时器分辨率处理。后者本轮没有单独验证，不能作为已确认根因。[Microsoft 文档](https://learn.microsoft.com/en-us/windows/win32/api/timeapi/nf-timeapi-timebeginperiod)。

## 已确认问题 4：主机调用起点不等于目标数据读取时刻

[probers_backend.rs:386](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:386>) 在进入读取前打时间戳。USB 出现长耗时时，样本真正从 MCU 读取的时刻会偏离这个时间。上述事件的原始数据为：

| 批内帧号 | Agent t，秒 | MCU uwTick，ms |
| ---: | ---: | ---: |
| 8 | 4.6466967 | 2,189,748 |
| 9 | 4.6476967 | 2,189,766 |
| 10 | 4.6690947 | 2,189,770 |
| 11 | 4.6700947 | 2,189,771 |

第 8→9 帧主机只标记 1 ms，MCU 实际推进 18 ms；第 9→10 帧主机标记 21.398 ms，MCU 只推进 4 ms。目标时间上的缺失与界面所标记的长间隔发生错位。**把时间戳从读结束换到读开始，不能独自解决长 I/O 下的采样时刻不确定性。**

应保留读开始/结束区间及目标端计数或时间戳，明确误差；要补回主机停顿期间的样本，需要目标端按硬件节拍采样并缓存，配套序号和溢出检测。只换另一种主机时间戳不会补回未采到的值。

## 三路 3 kHz 复核

重新使用真正的三路正弦、连续 12 B、同一个 Core 做 15 秒对照：

| 请求/时钟 | 实际 Hz | 读取中位数 ms | 最大间隔 ms | >3 周期空档 |
| --- | ---: | ---: | ---: | ---: |
| 1 kHz，4 MHz SWD | 992.8 | 0.2086 | 28.535 | 9 |
| 1 kHz，1 MHz SWD | 989.0 | 0.2265 | 9.766 | 13 |
| 3 kHz，4 MHz SWD | **2878.6** | **0.1947** | 25.550 | 218 |

三组读取全部成功。4→1 MHz 未消除空档；这只是一次短窗口对照，不能据此决定长期最优 SWD 时钟。

这支持用户所述三通道约 3 kHz 的吞吐能力。现有 `scripts/test_scope_stability.py` 用 `sin_1hz`、`sin_20hz`、`g_chassis_ctx_ptr` 作为“三通道”，可能形成两个读块；其 ~1.75 kHz 描述不能直接用来否定界面三路连续正弦的能力。[测试目标选择](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/scripts/test_scope_stability.py:119>)。

## 仍需修正的源码问题

以下问题已由源码确认，但不应全部归为本轮已捕获空档的原因。

1. **重试计数失效，逐字降级可能发送假有效值（P1）**。`SCOPE_READ32_RETRIES` 只定义，没有递增；大块读取连续失败后，逐字读取失败的槽位保留本帧失败读取已写入的部分值或初始零值，循环结束却无条件 `ok=true`。即使部分字失败也可能返回完整字节并跳过降级计数。本轮深层记录没有触发该路径，但“零重试、零降级”不能用于排除它。[源码](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:397>)。
2. **“帧耗时预算”仍只有注释（P1）**。失败后的 dummy、整块重试和逐字读之前没有检查已经消耗的时间，仍可能将延迟继续扩大。[源码](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/debug-core/src/probers_backend.rs:392>)。
3. **命令预算按 32 条计数，没有墙钟预算（P2）**。普通内存读取仍同步占用采样线程，单次大块读或慢调用即可耗尽很多采样周期。所谓 2 ms 等待上限分支只限制 `remain`，忙等仍使用原来的 `due`，并未按截断后的截止时刻退出。[命令预算](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/monitor/src/lib.rs:523>)、[等待分支](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/monitor/src/lib.rs:465>)。
4. **插帧激活后仍跟踪过期 Watch 截止，造成批间空转（P2）**。跳过独立 Watch 采样时 `next_watch` 不再推进，外层仍将它纳入最近截止。第一次诊断出现大量空命令循环，导致 400,000 条缓冲在约 48 秒耗尽；后续诊断排除空命令记录后正常覆盖全部场景。它证明存在空转，但不能将所有空档都归因于空转。[过期截止仍参与调度](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/agent/crates/monitor/src/lib.rs:446>)。

## 证据和复现

全部数据在 [tmp/scope-audit-20261002](<E:/Software/Develop/Embeded/Clion_plugin/Embedded Debug tools/tmp/scope-audit-20261002>)。

- `production_*.jsonl/.csv/.summary.json`：未修改的生产版三通道采集，合计 150,432 帧、210 秒。
- `trace_*.csv`、`trace.trace.csv`、`trace.gap-attribution.json`：首次帧级定位。原始采集 74,436 帧；trace 缓冲只记录前 41,820 个帧起点，归因文件明确列出未覆盖部分，不用它证明后续场景。
- `deeptrace_*.csv`、`deeptrace.trace.csv`、`deeptrace.gap-attribution.json`：USB 发送/接收定位，32,952 帧、45 秒，所有测量帧均成功关联；没有 USB 错误或读取重试。
- `raw_*.csv`、`raw_runs.json`：持续持有 Core 的直接读取与线程优先级对照。
- `deep_raw_*.csv`、对应 `.deep.csv`、`deep_raw_summary.json`：真正三通道的 1/3 kHz 及 1/4 MHz 对照。
- `capture_current.py`、`prepare_trace.py`、`prepare_deep_trace.py`、`analyze_trace.py`、`trace-workspace/`：测试脚本与诊断源码，记录了所有临时插桩位置。
- 各 `*.meta.json` 保存源码、Agent 与 ELF 哈希及现场符号地址。握手令牌未写入元数据。

本轮不实施生产修复。下一轮应优先处理采样时刻定义、截止推进、真实的时间预算和失败数据真实性，并用分段 USB 耗时及 MCU 计数持续验证。若要求主机偶发停顿时仍保留完整波形，目标端缓存是补回样本所需的机制。
