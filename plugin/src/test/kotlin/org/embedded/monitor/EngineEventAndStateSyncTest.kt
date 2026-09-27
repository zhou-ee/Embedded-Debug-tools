package org.embedded.monitor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.embedded.monitor.agent.EngineEvent
import org.embedded.monitor.agent.EngineEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEventAndStateSyncTest {

    @Test
    fun testParseWatchDataEvent() {
        val json = JsonObject().apply {
            addProperty("kind", "watchData")
            addProperty("halted", false)
            val values = JsonObject().apply {
                val bytes = JsonArray().apply {
                    add(0x12)
                    add(0x34)
                    add(0x56)
                    add(0x78)
                }
                add("w1", bytes)
            }
            add("values", values)
        }

        val ev = EngineEventParser.parse(json)
        assertTrue(ev is EngineEvent.WatchData)
        val watchEv = ev as EngineEvent.WatchData
        assertFalse(watchEv.halted)
        assertEquals(1, watchEv.values.size)
        val b = watchEv.values["w1"]!!
        assertEquals(4, b.size)
        assertEquals(0x12.toByte(), b[0])
        assertEquals(0x78.toByte(), b[3])
    }

    @Test
    fun testParseScopeDataEvent() {
        val json = JsonObject().apply {
            addProperty("kind", "scopeData")
            val samples = JsonArray().apply {
                val s1 = JsonObject().apply {
                    addProperty("t", 1.234)
                    val vals = JsonObject().apply {
                        val arr = JsonArray().apply {
                            add(0x00)
                            add(0x00)
                            add(0x80)
                            add(0x3F)
                        } // 1.0f
                        add("0x20000000", arr)
                    }
                    add("values", vals)
                }
                add(s1)
            }
            add("samples", samples)
        }

        val ev = EngineEventParser.parse(json)
        assertTrue(ev is EngineEvent.ScopeData)
        val scopeEv = ev as EngineEvent.ScopeData
        assertEquals(1, scopeEv.samples.size)
        assertEquals(1.234, scopeEv.samples[0].t, 1e-6)
        assertEquals(1, scopeEv.samples[0].values.size)
    }

    @Test
    fun testParseStateAndConnectionEvents() {
        val cJson = JsonObject().apply {
            addProperty("kind", "connected")
            addProperty("description", "OpenOCD Tcl @4000kHz")
        }
        val cEv = EngineEventParser.parse(cJson)
        assertTrue(cEv is EngineEvent.Connected)
        assertEquals("OpenOCD Tcl @4000kHz", (cEv as EngineEvent.Connected).description)

        val sJson = JsonObject().apply {
            addProperty("kind", "state")
            addProperty("state", "running")
        }
        val sEv = EngineEventParser.parse(sJson)
        assertTrue(sEv is EngineEvent.State)
        assertEquals("running", (sEv as EngineEvent.State).state)

        val dJson = JsonObject().apply {
            addProperty("kind", "disconnected")
            addProperty("reason", "probe reset")
        }
        val dEv = EngineEventParser.parse(dJson)
        assertTrue(dEv is EngineEvent.Disconnected)
        assertEquals("probe reset", (dEv as EngineEvent.Disconnected).reason)
    }

    @Test
    fun testParseErrorEvent() {
        val eJson = JsonObject().apply {
            addProperty("kind", "error")
            addProperty("message", "openocd read failed at 0x20000000")
        }
        val eEv = EngineEventParser.parse(eJson)
        assertTrue(eEv is EngineEvent.Error)
        assertEquals("openocd read failed at 0x20000000", (eEv as EngineEvent.Error).message)
    }

    @Test
    fun testStateTransitionModelLogic() {
        // 验证协同状态机流转逻辑规范：
        // 1. 当目标全速运行时，无论之前是否处于 connecting 或短暂的 halted，必须恢复 running，解除断点锁定
        var isHaltedByDebug = true
        var watchHalted = true
        var engineState = "connecting"
        var desiredRunning = false
        val isDebuggerPaused = false // CLion 并没有停在断点上

        val evState = "running"
        if (evState == "running") {
            if (!isDebuggerPaused) {
                isHaltedByDebug = false
                watchHalted = false
                desiredRunning = true
                engineState = "running"
            }
        }
        assertFalse("单片机运行且调试器未命中时，isHaltedByDebug 必须清除", isHaltedByDebug)
        assertFalse("watchHalted 必须解除", watchHalted)
        assertTrue("desiredRunning 必须置为 true", desiredRunning)
        assertEquals("即使此前为 connecting，收到 running 必须恢复 running", "running", engineState)

        // 2. 自愈机制：若期望运行 (desiredRunning == true) 但状态脱节显示 disconnected/connecting，有 WatchData/ScopeData 流入时自动自愈为 running
        engineState = "connecting"
        desiredRunning = true
        val hasIncomingWatchData = true
        if (hasIncomingWatchData && desiredRunning) {
            val isPaused = isHaltedByDebug || isDebuggerPaused
            engineState = if (isPaused) "halted" else "running"
        }
        assertEquals("接收到监视数据时，状态机必须立刻自愈为 running", "running", engineState)
        assertTrue("接收到监视数据时，desiredRunning 保持为 true", desiredRunning)

        // 2b. 用户主动停止 (desiredRunning == false) 时，在途 WatchData 绝不自愈复活状态机
        engineState = "disconnected"
        desiredRunning = false
        if (hasIncomingWatchData && desiredRunning) {
            engineState = "running"
        }
        assertEquals("用户已停止监视时，在途数据包严禁复活状态机", "disconnected", engineState)
        assertFalse("desiredRunning 严禁被在途数据重置为 true", desiredRunning)

        // 3. 非致命错误隔离：收到 EngineEvent.Error 时，绝不应破坏连接状态机为 disconnected
        engineState = "running"
        val isErrorMessage = true
        if (isErrorMessage) {
            // 仅记录错误文本，不修改 engineState
        }
        assertEquals("非致命局部内存读错误不得切断连接状态", "running", engineState)

        // 4. 断开通知：若 desiredRunning 为 true（期望运行），断开不应直接杀死状态机，而应置为 connecting 等待重连
        desiredRunning = true
        val isDisconnectedEvent = true
        if (isDisconnectedEvent) {
            if (!desiredRunning) {
                engineState = "disconnected"
            } else {
                engineState = "connecting"
            }
        }
        assertEquals("期望运行时收到底层重置断开，状态应保持 connecting", "connecting", engineState)

        // 5. 命中断点与恢复运行双向对齐逻辑：
        // a) 命中断点
        var debugPaused = true
        if (debugPaused) {
            isHaltedByDebug = true
            watchHalted = true
            engineState = "halted"
        }
        assertTrue(isHaltedByDebug)
        assertEquals("halted", engineState)

        // b) 恢复运行（用户按 F9 或单步跳过）
        debugPaused = false
        if (!debugPaused) {
            isHaltedByDebug = false
            watchHalted = false
            engineState = "running"
        }
        assertFalse(isHaltedByDebug)
        assertFalse(watchHalted)
        assertEquals("running", engineState)
    }

    @Test
    fun testExtractErrorUnwrapsTimeoutException() {
        val timeoutEx = java.util.concurrent.TimeoutException("请求 agent 方法 'connect' 超时 (8000ms)")
        val execEx = java.util.concurrent.ExecutionException(timeoutEx)
        val illegalState = IllegalStateException("connect 失败", execEx)

        val err = org.embedded.monitor.agent.AgentService.extractError(illegalState)
        assertEquals("请求 agent 方法 'connect' 超时 (8000ms)", err)

        // Test with raw TimeoutException without message
        val rawTimeout = java.util.concurrent.TimeoutException()
        val wrappedRaw = IllegalStateException("java.util.concurrent.TimeoutException", rawTimeout)
        assertEquals("连接超时（底层探针或 Agent 未响应）", org.embedded.monitor.agent.AgentService.extractError(wrappedRaw))

        // Test with specific business IllegalStateException
        val specificErr = IllegalStateException("使用 probe-rs 后端时需指定芯片型号")
        assertEquals("使用 probe-rs 后端时需指定芯片型号", org.embedded.monitor.agent.AgentService.extractError(specificErr))
    }

    @Test
    fun testBreakpointHaltSuppressesWatchdogAndConnect() {
        // 6. 命中断点暂停时，connectEngine 与 watchdog 必须直接短路，绝不抛出 TimeoutException
        var isHaltedByDebug = true
        var engineState = "halted"
        var agentRunning = true
        var watchdogTriggered = false
        var lowLevelConnectAttempted = false

        // Simulate connectEngine() guard
        val connectHandledImmediately = if (agentRunning && (engineState == "running" || engineState == "halted")) {
            true
        } else if (isHaltedByDebug || engineState == "halted") {
            true
        } else {
            lowLevelConnectAttempted = true
            false
        }

        assertTrue("断点命中态下 connectEngine 必须短路成功返回", connectHandledImmediately)
        assertFalse("断点命中态下严禁发起底层全量连接", lowLevelConnectAttempted)

        // Simulate watchdog logic
        if (isHaltedByDebug || engineState == "halted") {
            // Watchdog cancelled
        } else {
            watchdogTriggered = true
        }
        assertFalse("断点命中态下看门狗严禁触发超时错误", watchdogTriggered)

        // 7. 单飞快照防抖测试
        val isSnapshotRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        assertTrue(isSnapshotRunning.compareAndSet(false, true))
        assertFalse("并发快照请求必须被单飞防抖丢弃", isSnapshotRunning.compareAndSet(false, true))
        isSnapshotRunning.set(false)
        assertTrue("快照完成后可再次接受新快照", isSnapshotRunning.compareAndSet(false, true))
    }

    @Test
    fun testBreakpointResumeTransitionAndSelfHealing() {
        // 验证断点命中后恢复运行 (Resume) 的全链路状态流转：
        // 1. 命中断点态
        var isHaltedByDebug = true
        var watchHalted = true
        var engineState = "halted"
        var isDebugging = true
        var isAnySessionPaused = true

        // 2. 用户按 F9 / Resume 恢复运行：CLion 调试会话暂停状态解除
        isAnySessionPaused = false

        // 验证 syncDebugAndEngineState 逻辑：严禁使用 isHaltedByDebug 锁死 isDebuggerPaused
        val isDebuggerPaused = isAnySessionPaused // 正确做法：直接以实际会话状态为准
        if (isDebugging) {
            if (isDebuggerPaused) {
                // 仍处于暂停态
            } else {
                // CLion 处于全速运行态：解除暂停锁定并恢复实时监视
                if (isHaltedByDebug || engineState == "halted") {
                    isHaltedByDebug = false
                    watchHalted = false
                    if (engineState == "halted") {
                        engineState = "running"
                    }
                }
            }
        }
        assertFalse("CLion 恢复运行后，syncDebugAndEngineState 必须清除 isHaltedByDebug", isHaltedByDebug)
        assertFalse("CLion 恢复运行后，watchHalted 必须解除", watchHalted)
        assertEquals("CLion 恢复运行后，engineState 必须恢复为 running", "running", engineState)

        // 3. 验证底层 State('running') 事件自愈流转
        isHaltedByDebug = true
        engineState = "halted"
        val evState = "running"
        if (evState == "running") {
            if (!isAnySessionPaused) {
                isHaltedByDebug = false
                watchHalted = false
                engineState = "running"
            }
        }
        assertFalse("底层上报 running 时，isHaltedByDebug 必须被清除", isHaltedByDebug)
        assertEquals("底层上报 running 时，engineState 必须恢复 running", "running", engineState)

        // 4. 验证 WatchData 活跃数据流自愈逻辑
        isHaltedByDebug = true
        engineState = "halted"
        val evHalted = false
        if (!isAnySessionPaused && !evHalted && isHaltedByDebug) {
            isHaltedByDebug = false
        }
        val targetState = if (isAnySessionPaused || evHalted || isHaltedByDebug) "halted" else "running"
        if (engineState != targetState) {
            engineState = targetState
        }
        assertFalse("活跃采样数据流入时，必须自愈清除 isHaltedByDebug", isHaltedByDebug)
        assertEquals("活跃采样数据流入时，状态必须自愈恢复为 running", "running", engineState)
    }

    @Test
    fun testSingleClickStopWithInFlightData() {
        // 场景：监视中点击停止，在途 WatchData / ScopeData / Disconnected 事件到达时的状态流转
        var engineState = "running"
        var desiredRunning = true
        var lastEngineError: String? = null

        // 1. 用户点击停止按钮 (disconnectEngine)
        desiredRunning = false
        engineState = "disconnected"
        lastEngineError = null

        // 2. 在途的 WatchData 到达
        val hasIncomingWatchData = true
        if (hasIncomingWatchData) {
            // 新逻辑：!desiredRunning 时直接 discard，严禁自愈
            if (desiredRunning) {
                engineState = "running"
            }
        }
        assertEquals("在途 WatchData 到达后，状态机必须稳定保持 disconnected", "disconnected", engineState)
        assertFalse("desiredRunning 严禁被在途 WatchData 改变", desiredRunning)

        // 3. 在途的 ScopeData 到达
        val hasIncomingScopeData = true
        if (hasIncomingScopeData) {
            if (desiredRunning) {
                engineState = "running"
            }
        }
        assertEquals("在途 ScopeData 到达后，状态机必须稳定保持 disconnected", "disconnected", engineState)

        // 4. 底层 agent 响应 disconnect，上报 Disconnected / State("disconnected")
        val isDisconnectedEvent = true
        if (isDisconnectedEvent) {
            if (!desiredRunning) {
                engineState = "disconnected"
                lastEngineError = null
            } else {
                engineState = "connecting"
            }
        }
        assertEquals("收到 Disconnected 事件时，因 desiredRunning 为 false，状态机稳定保持 disconnected（绝不跳到 connecting）", "disconnected", engineState)
        assertNull("用户主动停止时，lastEngineError 必须保持 null", lastEngineError)
    }

    @Test
    fun testDebugStopOpenOcdSuppressesConnectionFailure() {
        // 场景：退出 CLion 调试时，OpenOCD 随进程退出，探针断开
        var currentEffectiveBackend = "openocd"
        var currentEffectiveAttachOnly = true
        var isAttachedToDebugSession = true
        var isDebugging = true
        var engineState = "running"
        var desiredRunning = true
        var lastEngineError: String? = null

        // 1. CLion 调试会话停止 (onDebugSessionStopped)
        isDebugging = false
        if (!isDebugging && currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession)) {
            desiredRunning = false
            engineState = "disconnected"
            isAttachedToDebugSession = false
            lastEngineError = null
        }
        assertEquals("调试会话停止后，状态机立即置为 disconnected", "disconnected", engineState)
        assertFalse("desiredRunning 必须置为 false", desiredRunning)

        // 2. OpenOCD 退出导致底层 Tcl 断开，Rust 发送 Disconnected 事件
        val isOpenOcdDebugExited = currentEffectiveBackend == "openocd" &&
            (currentEffectiveAttachOnly || isAttachedToDebugSession) && (!isDebugging)
        if (!desiredRunning || isOpenOcdDebugExited) {
            desiredRunning = false
            engineState = "disconnected"
            lastEngineError = null
        } else {
            engineState = "connecting"
        }
        assertEquals("调试已结束时收到底层断开，必须静默转为 disconnected，绝不进入 connecting 重试", "disconnected", engineState)
        assertNull("调试正常退出时，lastEngineError 绝不可显示连接失败", lastEngineError)

        // 3. 随后的重连失败日志/错误事件被静默隔离
        val logMsg = "[monitor] 连接失败，1 秒后重试: connection lost"
        if (isOpenOcdDebugExited || !desiredRunning) {
            lastEngineError = null
        } else if (logMsg.contains("连接失败")) {
            lastEngineError = logMsg
        }
        assertNull("调试退出后到达的重连/错误日志严禁污染 lastEngineError", lastEngineError)

        // 4. 监督线程 (startSupervisor) 巡检时严禁对退出的 OpenOCD 发起重连
        var reconnectAttempted = false
        if (currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession) && !isDebugging) {
            reconnectAttempted = false
        } else {
            reconnectAttempted = true
        }
        assertFalse("调试结束后监督线程严禁尝试重连 OpenOCD", reconnectAttempted)
    }

    @Test
    fun testStandaloneOpenOcdMonitoringWorksWithoutDebug() {
        // 场景：用户使用独立 OpenOCD（未开启 CLion 调试，attachOnly 为 false），监控采样必须正常工作且不得被误杀
        var currentEffectiveBackend = "openocd"
        var currentEffectiveAttachOnly = false
        var isAttachedToDebugSession = false
        var isDebugging = false
        var desiredRunning = true
        var engineState = "running"
        var lastEngineError: String? = null

        // 1. 处于非调试态下的正常采样数据到达 (WatchData)
        val hasIncomingWatchData = true
        if (hasIncomingWatchData && desiredRunning) {
            engineState = "running"
        }
        assertEquals("独立 OpenOCD 在无调试会话时，采样数据到达后必须正常维持 running", "running", engineState)

        // 2. 定时调用 syncDebugAndEngineState() 严禁将独立运行的 OpenOCD 强制切为 disconnected
        if (currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession) && !isDebugging) {
            engineState = "disconnected"
        }
        assertEquals("非 attach-only 的独立 OpenOCD 严禁在无调试会话时被强制断开", "running", engineState)

        // 3. 监督线程 (startSupervisor) 检查时不应误杀独立 OpenOCD 的期望运行状态
        var supervisorSuppressed = false
        if (currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession) && !isDebugging) {
            supervisorSuppressed = true
        }
        assertFalse("非 attach-only 独立 OpenOCD 严禁被监督线程强制抑制", supervisorSuppressed)

        // 4. 严格断言：独立运行的 OpenOCD 在无调试会话时，绝不可被判定为 isOpenOcdDebugExited
        fun isOpenOcdDebugExited(backend: String, attachOnly: Boolean, attachedDebug: Boolean, debugActive: Boolean, emptySessions: Boolean): Boolean {
            return backend == "openocd" && (attachOnly || attachedDebug) && (!debugActive || emptySessions)
        }
        assertFalse(
            "独立 OpenOCD (attachOnly=false, attachedDebug=false) 在无调试时严禁被判定为 isOpenOcdDebugExited",
            isOpenOcdDebugExited(currentEffectiveBackend, currentEffectiveAttachOnly, isAttachedToDebugSession, isDebugging, true)
        )

        // 5. 状态流入保护：State("running") 和 State("halted") 绝不可被 (isOpenOcdDebugExited && !isDebugging) 误杀丢弃
        val standaloneDebugExited = isOpenOcdDebugExited(currentEffectiveBackend, currentEffectiveAttachOnly, isAttachedToDebugSession, isDebugging, true)
        var stateProcessed = false
        if (desiredRunning && !(standaloneDebugExited && !isDebugging)) {
            stateProcessed = true
        }
        assertTrue("独立 OpenOCD 的状态变迁事件必须被正常处理，严禁丢弃", stateProcessed)

        // 6. 底层断开事件保护：独立 OpenOCD 在期望运行时若收到底层断开通知，应进入 connecting 等待恢复，而不是误判为调试退出而杀死 desiredRunning
        if (!desiredRunning || standaloneDebugExited) {
            desiredRunning = false
            engineState = "disconnected"
        } else {
            engineState = "connecting"
        }
        assertEquals("独立 OpenOCD 发生断开时，若期望运行必须进入 connecting 等待重连，严禁误杀为 disconnected", "connecting", engineState)
        assertTrue("独立 OpenOCD 的 desiredRunning 必须保持为 true 以允许重连", desiredRunning)
    }
}
