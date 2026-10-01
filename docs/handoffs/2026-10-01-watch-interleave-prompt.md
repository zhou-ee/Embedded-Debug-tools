# 转接：监视读插入示波突发内部 + 调试期轮询挂起（V1.2.34）

## 用户指定设计（2026-10-01）
实时变量监视（2-15Hz，时间容差数 ms）与 DWT 轮询不应独立占用总线造成示波空档：
- 监视读**插入示波突发的帧间空闲窗口**：1kHz 时每帧节拍窗 = 间隔 1ms - 块读 ~0.3ms ≈ 0.6-0.9ms，
  足够容纳一个监视块读（~0.1-0.3ms）；示波帧时间戳取读取开始、节拍为绝对截止，
  监视读不推迟示波帧（帧间隔抖动 +0.1-0.3ms 可接受）。
- **调试会话活跃期间挂起引擎轮询**：停/走状态由插件经调试会话事件即时感知；
  非调试场景保留低频轮询兜底（外部复位/看门狗停机只有探针能看见）。
- 多次示波采样分摊：监视 5Hz + 示波 1kHz → 每 200 帧执行一次监视读。

## 现状（V1.2.33，commit 4a8292c）
- 1kHz 四通道实测 984Hz（p50/p95=1.00ms）；残余 26 个 >3ms 空档/12s，
  源 = 监视采样（1-2ms/次）+ poll（已降频 600ms）+ flush/drain/OS 堆叠。
- 混合节拍/重锚定/预热窗/单块降级/intervalUs/scope_perf 均已就位。

## 实现要点
1. Backend trait `scope_burst` 增加可选参数 `watch_blocks: &[(u64, usize)]`
   （空 = 不插入）；两后端帧循环内：帧 push 后若监视到期且剩余节拍窗足够，
   读监视块并记 (帧序, bytes)；返回结构携带监视帧数据。
2. Engine `sample_scope`：按 watch_interval 计算 burst 内是否含监视触发点；
   产出后解码为 WatchData 事件（复用 sample_watch 的目标→字节逻辑），
   推进 next_watch。Watch 读取失败仅影响该次监视值，不影响示波帧。
3. 协议/插件：调试会话激活时插件发 `set_poll_suppression(true)`
   （新命令，引擎跳过 poll_state）；sessionStopped/resumed 恢复。
4. 测试：sim_integration 增加监视插帧用例（监视值到达且示波节拍不漂移）。

## 验收
1kHz + 15Hz watch：示波空档数不高于无 watch 基线；监视值正常更新；
调试会话中（GDB attached）engine 无 poll 引发的空档。


## 进度（2026-10-01 第二段）
- ✅ trait `scope_burst_watch` 已加入 debug-core/src/lib.rs（默认实现 = 回退 scope_burst 不插帧，
  Sim/未实现的调用方零影响），probe-rs/openocd **尚未实现**（当前走默认回退=现行为）。
- 实现方式（下一工作段执行）：把两后端 scope_burst 的函数体改造为 scope_burst_watch
  （签名加 watch_blocks/watch_every，帧 push 后按 `(i+1) % watch_every == 0` 插入监视块读，
  读失败仅丢该次监视值；监视读耗时由既有超期重锚定自然吸收），scope_burst 保留为
  委托包装（`self.scope_burst_watch(blocks, &[], 0, ...)`）以不破坏测试/示例。
- Engine 侧接线：sample_scope 计算 watch_every = watch_interval/interval；监视字节
  按 WatchData 事件（values 以 MemTarget.id 为键）解码发送，推进 next_watch。
- 回归基线：cargo 58/0、gradle 145/0。

## 进度二（2026-10-01 第三段）
- trait scope_burst_watch 已加入 debug-core/src/lib.rs（默认实现 = 回退 scope_burst 不插帧）。
- probe-rs 的 scope_burst 已改造为 scope_burst_watch（签名加 watch_blocks/watch_every、
  返回 (BurstFrames, Vec<Vec<Vec<u8>>>)、帧 push 后按全局帧计数插监视读、
  watch_frame_counter 字段+局部拷贝规避 core 借用冲突、内部块包装解决 Core Drop 借用延伸）。
  scope_burst 保留为委托包装。cargo 58/0。
- 待办：openocd 同名改造（其 scope_burst 含 total_blocks/degraded/last_degrade_log
  降级统计与限流，改造时保持语义；用 Read+Edit 精确编辑而非 python 字符串手术）；
  Engine sample_scope 接线（watch_every = watch_interval/scope_interval，监视字节按
  WatchData 事件解码，values 以 MemTarget.id 为键）；调试期轮询挂起
  （set_poll_suppression 协议命令 + 插件在 attachDebugSession/disconnect 时设置）。

## 下一工作段设计（2026-10-01 用户确认：维护操作分摊到帧间窗口）

用户反馈：200Hz 下硬件时间充足（5ms/帧，读 ~0.3ms）仍有空档——根因是 watch/poll/flush
堆叠在批间执行。设计：**所有维护操作成为示波突发内部的帧间操作，每帧窗口最多一个**。

### Backend trait 改造
scope_burst_watch 签名改为返回结构体并增加 poll_every：
```rust
pub struct BurstOutput {
    pub frames: BurstFrames,
    pub watch: Vec<Vec<Vec<u8>>>,     // 帧序 → 各监视块字节（仅监视触发的帧有值）
    pub halted_seen: Option<bool>,    // 突发内最后一次 is_halted 结果（None=未轮询）
}
fn scope_burst_watch(&mut self, blocks, watch_blocks, watch_every, poll_every,
                     count, interval) -> Result<BurstOutput, BackendError>;
```
帧循环内 push 后的维护调度（每窗口至多一个操作）：
```rust
let want_watch = watch_every > 0 && (counter + 1) % watch_every as u64 == 0;
let want_poll = poll_every > 0 && (counter + 1) % poll_every as u64 == 0 && !want_watch;
// want_watch: 读监视块 → watch.push；want_poll: is_halted() 记录 halted_seen
// 读耗时超窗 → 超期重锚定自然吸收（单帧延迟，不积累）
```
- probe-rs：is_halted = core.is_halted()；openocd：tcl("poll") 解析（is_halted 已封装）。
- scope_burst 保留为委托包装（watch_blocks 空 + poll_every 0）。

### Engine 接线
- poll_every = poll_suppressed ? 0 : (600ms / interval)（示波激活期；非示波保持批间 150ms poll）。
- watch_every = watch_interval / interval（既有逻辑）。
- 突发结束后：halted_seen 与 last_state 比较，变化则 emit State 事件 +
  触发既有停机上报流程；watch 帧数据解码为 WatchData（既有代码）。
- 外层循环：scope 激活期跳过批间 poll_state（改由突发内 poll_every 承担）与
  sample_watch（改由插帧承担）；非激活期行为不变。

### 验收
200Hz 与 1kHz、watch 15Hz：>3 周期空档数 ≤ 无维护基线；监视值照常更新；
停机/恢复事件不丢失。cargo 58+ / gradle 145+ 回归。
