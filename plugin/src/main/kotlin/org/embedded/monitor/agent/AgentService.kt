package org.embedded.monitor.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.embedded.monitor.cmake.ElfAutoResolver
import org.embedded.monitor.core.ScopeSample
import org.embedded.monitor.core.ScopeSamples
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ValueFormat
import org.embedded.monitor.settings.EmbeddedMonitorSettings
import org.embedded.monitor.watch.WatchExpressionParser
import org.embedded.monitor.watch.WatchItem
import org.embedded.monitor.watch.WatchValueFormatter
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.PathUtil
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 工程级 Agent 服务：持有 embedded-clion-agent 进程与连接，统一管理
 * ELF 加载（自动探测 CLion 构建路径）、实时变量监视目标、示波通道与采样缓冲。
 * 所有 agent 回调在 agent-reader 线程到达，仅更新线程安全状态；UI 通过
 * Swing 定时器拉取快照，避免 EDT 洪泛。
 */
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val settings get() = EmbeddedMonitorSettings.getInstance(project)

    // ---------- agent 连接 ----------
    private val clientRef = AtomicReference<AgentClient?>()
    @Volatile var agentRunning: Boolean = false
        private set
    @Volatile var lastAgentError: String? = null
        private set
    val agentLogs = ArrayDeque<String>() // 最近日志（UI 展示），lock: this
    private fun logLine(s: String) {
        synchronized(agentLogs) {
            agentLogs.addLast(s)
            while (agentLogs.size > 400) agentLogs.removeFirst()
        }
    }

    // ---------- 引擎状态 ----------
    @Volatile var engineState: String = "disconnected" // disconnected / connecting / running / halted
        private set
    @Volatile var currentEffectiveBackend: String = "openocd"
        private set
    @Volatile var currentEffectiveAttachOnly: Boolean = false
        private set
    @Volatile var isAttachedToDebugSession: Boolean = false
        private set
    @Volatile var isHaltedByDebug: Boolean = false
        private set
    @Volatile var engineDescription: String = ""
        private set
    @Volatile var lastEngineError: String? = null
        private set

    // ---------- 调试会话跟踪 ----------
    private val attachedSessions = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<com.intellij.xdebugger.XDebugSession, Boolean>()
    )

    // ---------- CLion 原生求值（XDebuggerEvaluator，复用 IDE 自己的 GDB） ----------
    /** 当前调试会话的求值器：仅断点暂停期有意义；会话结束即置空 */
    @Volatile private var clionEvaluator: com.intellij.xdebugger.evaluation.XDebuggerEvaluator? = null
    @Volatile private var clionEvalSession: com.intellij.xdebugger.XDebugSession? = null

    /** CLion 原生求值是否可用（调试会话激活且调试进程暴露了求值器）。 */
    fun clionEvalAvailable(): Boolean = currentClionEvaluator() != null

    /** 求值型监视的混合升级会改变既有条目的成员结构（地址重定基），递增此修订号
     *  驱动 LiveWatch 面板 rebuildTree。 */
    @Volatile var watchStructureRevision: Long = 0
        private set

    /**
     * 取当前调试会话的 CLion 原生求值器（带缓存）。
     * **必须按需重试**：processStarted 瞬间 CIDR 的 getEvaluator() 常返回 null
     * （调试进程尚在初始化），一次性捕获会让 evalOnly 回退永久失效
     * （真机实测 2026-09-29：`pyro::wl_chassis_t::instance()` 添加监视报
     * "无法解析表达式"）。改为每次调用时从存活会话现场获取并缓存。
     */
    private fun currentClionEvaluator(): com.intellij.xdebugger.evaluation.XDebuggerEvaluator? {
        clionEvaluator?.let { return it }
        val session = attachedSessions.firstOrNull() ?: return null
        return runCatching { session.debugProcess?.evaluator }.getOrNull()?.also {
            clionEvaluator = it
            clionEvalSession = session
        }
    }

    /**
     * 经 CLion 原生调试器求值表达式（复用 IDE 自己的 GDB，进程内 API 调用）。
     * 仅在目标暂停时有意义；回调保证至多触发一次：
     * 成功 → (XValue, null)——文本呈现/子项展开经 [ClionEvalBridge] 由调用方按需捕获；
     * 失败 → (null, 错误文本)；求值器不可用/超时 → (null, null)。
     */
    fun evaluateViaClion(expr: String, onResult: (value: Any?, error: String?) -> Unit) {
        val evaluator = currentClionEvaluator()
        if (evaluator == null) {
            onResult(null, null)
            return
        }
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(v: Any?, err: String?) {
            if (delivered.compareAndSet(false, true)) onResult(v, err)
        }
        // 超时兜底：会话中途退出等场景回调可能永不抵达
        val timeout = supervisor.schedule({
            deliver(null, null)
        }, clionEvalTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        ApplicationManager.getApplication().invokeLater {
            try {
                evaluator.evaluate(expr, object : com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: com.intellij.xdebugger.frame.XValue) {
                        timeout.cancel(false)
                        deliver(result, null)
                    }

                    override fun errorOccurred(errorMessage: String) {
                        timeout.cancel(false)
                        deliver(null, "求值失败: $errorMessage")
                    }

                    override fun invalidExpression(errorMessage: String) {
                        // CIDR 会对无法作为代码片段解析的表达式走此分支（默认实现是
                        // no-op，V1.2.19 里曾让结果静默超时显示"…"）
                        timeout.cancel(false)
                        deliver(null, "表达式无效: $errorMessage")
                    }
                }, null)
            } catch (t: Throwable) {
                timeout.cancel(false)
                log.warn("CLion 求值调用失败: $expr", t)
                deliver(null, null)
            }
        }
    }

    /** 对全部 evalOnly 监视项执行一轮 CLion 求值并刷新 UI（断点暂停时调用）。 */
    private fun refreshEvalOnlyItems() {
        val items = synchronized(watchItems) { watchItems.filter { it.evalOnly } }
        for (item in items) {
            evaluateViaClion(item.expr) { value, error ->
                // 闩锁等待放后台线程，结果回填后统一刷新 UI
                ApplicationManager.getApplication().executeOnPooledThread {
                    when {
                        value != null -> {
                            val pres = ClionEvalBridge.capturePresentation(
                                value as com.intellij.xdebugger.frame.XValue,
                            )
                            item.evalXValue = value
                            item.evalHasChildren = pres?.hasChildren ?: true
                            if (pres != null) {
                                promoteEvalHybrid(item, pres)
                            }
                            item.evalValue = when {
                                pres == null -> "<CLion 求值结果获取超时>"
                                else -> buildString {
                                    pres.typeText?.let { append("{$it} ") }
                                    append(pres.valueText ?: "")
                                }.trim().ifEmpty { "<空值>" }
                            }
                        }
                        error != null -> item.evalValue = "<$error>"
                        else -> item.evalValue = "<求值超时或求值器不可用>"
                    }
                    com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                        watchDataListeners.forEach { runCatching { it() } }
                    }
                }
            }
        }
    }

    /**
     * 调试会话期间挂起引擎状态轮询（停/走由 CLion 调试会话事件即时感知，
     * 引擎无需每 150-600ms 读一次 DHCSR——那是 1kHz 示波空档的主源之一）。
     * 非调试场景保持轮询（外部复位/看门狗停机只有探针能看见）。
     */
    fun setPollSuppression(enabled: Boolean) {
        val client = clientRef.get() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                client.requestSync(
                    "set_poll_suppression",
                    JsonObject().apply { addProperty("enabled", enabled) },
                    3000,
                )
            }.onFailure { log.warn("set_poll_suppression 失败: $enabled", it) }
        }
    }

    /** 展开 CLion 求值型节点的第一层子项（LiveWatch 树懒展开用）。 */
    fun computeEvalChildren(value: Any): List<Pair<String, Any>>? =
        ClionEvalBridge.computeChildren(value as com.intellij.xdebugger.frame.XValue)?.map { it.name to (it.value as Any) }

    /**
     * 混合升级：GDB 求值结果为指针/引用时，把该固定地址升级为常规内存监视通道。
     * 布局推导见 [EvalHybridPromoter]（纯函数）；升级后 item 进入 watch 目标下发，
     * 运行时按现有链路持续读取；下次断点重求值后地址随之更新。
     */
    private fun promoteEvalHybrid(item: WatchItem, pres: ClionEvalBridge.Presentation) {
        val result = EvalHybridPromoter.promote(pres.valueText, pres.typeText, elfVariables) ?: return
        // 求值结果未变（地址/布局一致）：只更新引用对象，不触发结构重建——
        // 重建会使已展开的树折叠（用户要求：结果不变保持展开状态）
        val changed = item.address != result.address ||
            item.size != result.size ||
            item.evalHasChildren != result.hasChildren ||
            item.node == null
        item.node = result.node
        item.address = result.address
        item.size = result.size
        item.encoding = result.encoding
        item.typeName = result.typeName
        item.evalHasChildren = result.hasChildren
        if (!changed) return
        watchStructureRevision++
        logLine(
            "求值型监视「${item.expr}」已升级为固定地址实时监视 @0x${"%08X".format(result.address)}" +
                "（${result.source}，类型 ${result.typeName}，${result.size}B）",
        )
        pushWatchTargets()
    }


    // ---------- ELF ----------
    @Volatile var elfPath: String? = null
        private set
    @Volatile var elfLoaded: Boolean = false
        private set
    @Volatile var elfMtime: Long = 0
    @Volatile var elfSource: String = ""
    private val elfVariables = CopyOnWriteArrayList<SymbolNode>()

    // ---------- 监视 / 示波 ----------
    val watchItems = CopyOnWriteArrayList<WatchItem>()
    val scopeVariables = CopyOnWriteArrayList<ScopeVariable>()
    private val colorSeq = AtomicInteger(0)

    data class DynamicWatchTarget(
        val id: String,
        val address: Long,
        val size: Int,
        val autoRefresh: Boolean = true,
    )
    val dynamicWatchTargets = ConcurrentHashMap<String, DynamicWatchTarget>()

    private val watchDataListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addWatchDataListener(listener: () -> Unit) {
        watchDataListeners.add(listener)
    }

    fun removeWatchDataListener(listener: () -> Unit) {
        watchDataListeners.remove(listener)
    }

    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addStateListener(listener: (String) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (String) -> Unit) {
        stateListeners.remove(listener)
    }

    fun updateDynamicWatchTargets(targets: Collection<DynamicWatchTarget>) {
        val validTargets = targets.filter { it.address >= 0x1000L }
        val newMap = validTargets.associateBy { it.id }
        var changed = false
        val newAddedTargets = mutableListOf<DynamicWatchTarget>()
        synchronized(dynamicWatchTargets) {
            if (dynamicWatchTargets != newMap) {
                val removedIds = dynamicWatchTargets.keys - newMap.keys
                for ((id, target) in newMap) {
                    if (!dynamicWatchTargets.containsKey(id)) {
                        newAddedTargets.add(target)
                    }
                }
                dynamicWatchTargets.clear()
                dynamicWatchTargets.putAll(newMap)
                removedIds.forEach { watchValues.remove(it) }
                changed = true
            }
        }
        if (changed) {
            pushWatchTargetsImmediate()
            // 极速预取：针对新加入且尚未采样的动态目标，立即发起一次内存读取填充缓存
            if (engineState == "running" || engineState == "halted") {
                for (target in newAddedTargets) {
                    if (!watchValues.containsKey(target.id) && target.address >= 0x1000L) {
                        readMem(target.address, target.size).whenComplete { bytes, err ->
                            if (err == null && bytes.isNotEmpty()) {
                                watchValues[target.id] = bytes
                                watchDataListeners.forEach { runCatching { it() } }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 采样缓冲 ----------
    private val watchValues = ConcurrentHashMap<String, ByteArray>()
    @Volatile var watchHalted: Boolean = false
        private set
    private val bufLock = Any()
    private val scopeSeries = LinkedHashMap<Long, java.util.ArrayDeque<ScopeSample>>()
    var scopeCapacity: Int = 50000
    private val scopeSampleCounter = AtomicLong(0)
    private val scopeErrorCounter = AtomicLong(0)
    private val scopeDroppedCounter = AtomicLong(0)
    private val scopeGapCounter = AtomicLong(0)
    private val scopeResolveLock = Any()

    @Volatile var isScopePaused: Boolean = false
    @Volatile var continueScopeHistory: Boolean = true
    private var timeOffsetSec = 0.0
    private var lastRawTimeSec = -1.0
    @Volatile private var isNewEngineSession: Boolean = true
    @Volatile private var scopeVersion: Long = 0
    @Volatile private var cachedSnapshot: Map<Long, List<ScopeSample>> = emptyMap()
    @Volatile private var cachedSnapshotVersion: Long = -1
    private var lastEmittedTimestampNanos: Long = 0L

    // ---------- 统计 ----------
    @Volatile private var rateWindowStart: Long = 0
    @Volatile private var rateWindowCount: Long = 0
    @Volatile private var lastScopeFrameTime: Long = 0
    @Volatile private var _actualRateHz: Double = 0.0
    val actualRateHz: Double
        get() {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (lastScopeFrameTime > 0 && now - lastScopeFrameTime > 2000) {
                    _actualRateHz = 0.0
                    rateWindowStart = 0
                    rateWindowCount = 0
                }
                return _actualRateHz
            }
        }

    @Volatile private var watchRateWindowStart: Long = 0
    @Volatile private var watchRateWindowCount: Long = 0
    @Volatile private var lastWatchFrameTime: Long = 0
    @Volatile private var _actualWatchRateHz: Double = 0.0
    val actualWatchRateHz: Double
        get() {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (lastWatchFrameTime > 0 && now - lastWatchFrameTime > 2000) {
                    _actualWatchRateHz = 0.0
                    watchRateWindowStart = 0
                    watchRateWindowCount = 0
                }
                return _actualWatchRateHz
            }
        }

    // ---------- 监督重连 ----------
    /** 用户期望的引擎状态；true 时监督线程自动拉起重连（指数退避）。 */
    private val desiredRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hasConnectedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    private val supervisor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "embedded-monitor-supervisor").apply { isDaemon = true }
    }
    private val reconnectAttempts = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var connectWatchdog: java.util.concurrent.ScheduledFuture<*>? = null

    /** CLion 原生求值的超时兜底（回调未抵达时置空结果，避免监视项永久停在"…"） */
    private val clionEvalTimeoutMs: Long get() = 8000

    private fun cancelConnectWatchdog() {
        connectWatchdog?.cancel(false)
        connectWatchdog = null
    }

    private fun startConnectWatchdog(timeoutSec: Long = 15) {
        cancelConnectWatchdog()
        connectWatchdog = supervisor.schedule({
            // 若已经处于连接成功态（running 或 halted），看门狗正常退出
            if (engineState == "running" || engineState == "halted") {
                cancelConnectWatchdog()
                return@schedule
            }
            if (engineState == "connecting") {
                logLine("连接超时（${timeoutSec}s 未收到目标连接成功事件），中止连接")
                val lastErr = lastEngineError
                engineState = "disconnected"
                desiredRunning.set(false)
                hasConnectedOnce.set(false)
                val client = clientRef.get()
                client?.let {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        runCatching { it.requestSync("disconnect", timeoutMs = 2000) }
                    }
                }
                val detail = if (!lastErr.isNullOrBlank()) "（最后信息: $lastErr）" else ""
                notify("连接超时：未能在 ${timeoutSec} 秒内连接到目标芯片$detail。若使用 probe-rs 请确认芯片型号；若使用 OpenOCD 请确认已在 CLion 启动调试。")
            }
        }, timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ---------- 推送防抖 ----------
    private val pushExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "embedded-monitor-push").apply { isDaemon = true }
    }
    private val watchPushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scopePushPending = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        const val LIVE_WATCH_DEBUG_CONTENT_ID = "EmbeddedLiveVariableWatch"
        const val LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID = "EmbeddedLiveWatch"
        const val LIVE_WATCH_DEBUG_TAB_ID = 11800
        const val LIVE_WATCH_DISPLAY_NAME = "实时变量监视"

        const val REGISTER_DEBUG_CONTENT_ID = "EmbeddedRegisterMonitor"
        const val REGISTER_DEBUG_TAB_ID = 11801
        const val REGISTER_DISPLAY_NAME = "寄存器实时监视"

        @JvmStatic
        fun getInstance(project: Project): AgentService = project.getService(AgentService::class.java)

        /** agent 可执行文件探测：设置 → 环境变量 → 插件安装目录 bin/ → 工程目录 → 开发目录 → PATH。 */
        @JvmStatic
        @JvmOverloads
        fun locateAgent(configuredPath: String = "", project: Project? = null): String? {
            // 1. 用户手动设置的路径
            if (configuredPath.isNotBlank() && File(configuredPath).isFile) return configuredPath

            // 2. 环境变量
            System.getenv("EMBEDDED_CLION_AGENT")?.let { if (File(it).isFile) return it }

            val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
            val agentExeName = "embedded-clion-agent$ext"

            // 3. 插件安装目录（standalone 发布包将 agent 置于插件目录下的 bin/）
            // 3a. 通过 IntelliJ PluginManagerCore 获取插件主目录
            runCatching {
                val pluginId = PluginId.getId("org.embedded.monitor")
                val pluginDescriptor = PluginManagerCore.getPlugin(pluginId)
                pluginDescriptor?.pluginPath?.let { path ->
                    val exe = path.resolve("bin").resolve(agentExeName).toFile()
                    if (exe.isFile) {
                        ensureExecutable(exe)
                        return exe.canonicalPath
                    }
                }
            }

            // 3b. 遍历 loadedPlugins 查找匹配插件目录
            runCatching {
                for (descriptor in PluginManagerCore.loadedPlugins) {
                    if (descriptor.pluginId.idString == "org.embedded.monitor") {
                        val exe = descriptor.pluginPath?.resolve("bin")?.resolve(agentExeName)?.toFile()
                        if (exe != null && exe.isFile) {
                            ensureExecutable(exe)
                            return exe.canonicalPath
                        }
                    }
                }
            }

            // 3c. 通过 AgentService 类所在 jar/classes 路径回溯插件根目录
            runCatching {
                val jarPath = PathUtil.getJarPathForClass(AgentService::class.java)
                val jarFile = File(jarPath)
                // 若位于 <pluginRoot>/lib/xxx.jar，则 parentFile 为 lib，其 parentFile 为 pluginRoot
                val pluginRoot = jarFile.parentFile?.parentFile
                if (pluginRoot != null && pluginRoot.isDirectory) {
                    val exe = File(pluginRoot, "bin/$agentExeName")
                    if (exe.isFile) {
                        ensureExecutable(exe)
                        return exe.canonicalPath
                    }
                }
            }

            // 3d. 通过 PathManager.getPluginsDir() 探测已安装插件目录
            runCatching {
                val pluginsDir = PathManager.getPluginsDir().toFile()
                if (pluginsDir.isDirectory) {
                    val direct = File(pluginsDir, "embedded-debug-plugin/bin/$agentExeName")
                    if (direct.isFile) {
                        ensureExecutable(direct)
                        return direct.canonicalPath
                    }
                    pluginsDir.listFiles { f -> f.isDirectory && f.name.contains("embedded") }?.forEach { dir ->
                        val exe = File(dir, "bin/$agentExeName")
                        if (exe.isFile) {
                            ensureExecutable(exe)
                            return exe.canonicalPath
                        }
                    }
                }
            }

            // 4. 当前工程 / 源码根目录下的 bin/ 目录与 monorepo 相对路径
            project?.basePath?.let { root ->
                val candidates = listOf(
                    File(root, "bin/$agentExeName"),
                    File(root, "agent/target/release/$agentExeName"),
                    File(root, "agent/target/debug/$agentExeName"),
                    File(root, "../agent/target/release/$agentExeName"),
                    File(root, "../agent/target/debug/$agentExeName"),
                )
                for (c in candidates) {
                    if (c.isFile) {
                        ensureExecutable(c)
                        return c.canonicalPath
                    }
                }
            }

            // 5. 环境变量与系统属性探测
            listOfNotNull(
                System.getenv("EMBEDDED_AGENT_PATH"),
                System.getenv("EMBEDDED_CLION_AGENT"),
                System.getProperty("embedded.agent.path")
            ).forEach { path ->
                val f = File(path)
                if (f.isFile) {
                    ensureExecutable(f)
                    return f.canonicalPath
                } else if (f.isDirectory) {
                    val direct = File(f, agentExeName)
                    if (direct.isFile) {
                        ensureExecutable(direct)
                        return direct.canonicalPath
                    }
                }
            }

            // 6. 系统 PATH
            for (dir in System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()) {
                val f = File(dir, agentExeName)
                if (f.isFile) return f.canonicalPath
            }

            return null
        }

        private fun ensureExecutable(file: File) {
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                runCatching {
                    if (!file.canExecute()) {
                        file.setExecutable(true, false)
                    }
                }
            }
        }

        fun encodingOf(format: ValueFormat): String = when (format) {
            ValueFormat.F32, ValueFormat.F64 -> "float"
            ValueFormat.I8, ValueFormat.I16, ValueFormat.I32, ValueFormat.I64 -> "signed"
            else -> "unsigned"
        }

        @JvmStatic
        fun extractError(e: Throwable): String? {
            var t: Throwable? = e
            var timeoutMsg: String? = null
            var illegalStateMsg: String? = null

            while (t != null) {
                if (t is java.util.concurrent.TimeoutException) {
                    timeoutMsg = if (!t.message.isNullOrBlank()) t.message else "连接超时（底层探针或 Agent 未响应）"
                } else if (t is IllegalStateException && !t.message.isNullOrBlank()) {
                    val m = t.message!!
                    if (!m.contains("TimeoutException") && m != "connect 失败") {
                        if (illegalStateMsg == null) illegalStateMsg = m
                    }
                }
                t = t.cause
            }
            return timeoutMsg ?: illegalStateMsg
        }
    }

    init {
        Disposer.register(this) { closeAgent() }
        startSupervisor()
        subscribeDebugSessions()
        subscribeVfsElfChanges()
    }

    /** 监督线程：期望运行但引擎掉线/agent 死亡时按退避自动重连。 */
    private fun startSupervisor() {
        supervisor.scheduleWithFixedDelay({
            try {
                syncDebugAndEngineState()
                if (!desiredRunning.get() || !hasConnectedOnce.get()) {
                    reconnectAttempts.set(0)
                    return@scheduleWithFixedDelay
                }
                if (agentRunning && (engineState == "running" || engineState == "halted")) {
                    reconnectAttempts.set(0)
                    return@scheduleWithFixedDelay
                }
                if (engineState == "connecting") {
                    return@scheduleWithFixedDelay
                }
                // 关键防护 1：断点停机态下严禁触发监督重连
                if (isHaltedByDebug || org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)) {
                    reconnectAttempts.set(0)
                    return@scheduleWithFixedDelay
                }
                // 关键防护 2：若当前后端为 openocd attach-only 且 CLion 调试已停止，严禁触发监督重连
                if (isOpenOcdDebugExited()) {
                    reconnectAttempts.set(0)
                    desiredRunning.set(false)
                    hasConnectedOnce.set(false)
                    isAttachedToDebugSession = false
                    return@scheduleWithFixedDelay
                }
                val attempt = reconnectAttempts.incrementAndGet()
                val backoffMs = (1L shl attempt.coerceAtMost(5)) * 1000 // 2,4,8,16,32s 封顶
                val sinceStateChange = System.currentTimeMillis() - lastStateChangeAt
                if (sinceStateChange < backoffMs) return@scheduleWithFixedDelay
                logLine("监督重连（第 $attempt 次）")
                // 走 connectEngine 统一入口（inFlight 去重 + 状态收口）；supervised 模式
                // 失败不清 desiredRunning，由本线程按退避继续重试
                connectEngine(supervised = true).whenComplete { _, err ->
                    if (err != null) {
                        val msg = extractError(err) ?: err.message ?: "未知错误"
                        logLine("监督重连失败: $msg")
                    }
                }
            } catch (t: Throwable) {
                log.warn("supervisor 异常", t)
            }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Volatile private var lastStateChangeAt: Long = System.currentTimeMillis()

    /** 订阅调试会话：调试启动即自动开始监视（openocd + attach-only，与调试共存），并深度监听断点与暂停态。 */
    private fun subscribeDebugSessions() {
        project.messageBus.connect(this).subscribe(
            com.intellij.xdebugger.XDebuggerManager.TOPIC,
            object : com.intellij.xdebugger.XDebuggerManagerListener {
                override fun processStarted(process: com.intellij.xdebugger.XDebugProcess) {
                    handleDebugProcessStarted(process)
                }

                override fun processStopped(process: com.intellij.xdebugger.XDebugProcess) {
                    handleDebugProcessStopped(process)
                }

                override fun currentSessionChanged(
                    previousSession: com.intellij.xdebugger.XDebugSession?,
                    currentSession: com.intellij.xdebugger.XDebugSession?,
                ) {
                    currentSession?.let {
                        attachDebugSession(it, isNewProcess = false)
                        registerLiveWatchDebugTab(it)
                        registerRegisterDebugTab(it)
                    }
                }
            },
        )

        // 启动时检查已有调试会话并挂载监听器
        runCatching {
            val mgr = com.intellij.xdebugger.XDebuggerManager.getInstance(project) ?: return
            for (s in mgr.debugSessions) {
                if (!s.isStopped) {
                    attachDebugSession(s, isNewProcess = false)
                    registerLiveWatchDebugTab(s)
                    registerRegisterDebugTab(s)
                }
            }
        }
    }

    /** 将实时变量监视面板作为独立顶层标签页嵌入到 CLion 原生 Debug 会话工具窗口中（与“线程与变量”、“内存视图”、“外设”、“控制台”平级）。 */
    fun registerLiveWatchDebugTab(session: com.intellij.xdebugger.XDebugSession, attempt: Int = 0) {
        ApplicationManager.getApplication().invokeLater {
            if (session.isStopped) return@invokeLater
            val ui = session.ui
            if (ui == null) {
                if (attempt < 10 && !project.isDisposed) {
                    com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                        registerLiveWatchDebugTab(session, attempt + 1)
                    }, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return@invokeLater
            }

            // 清理旧版可能遗留的 Content 与旧布局缓存，防止旧版 tabIndex=0 污染当前视图
            runCatching {
                ui.findContent(LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID)?.let { legacy ->
                    ui.removeContent(legacy, true)
                }
                val layoutImpl = (ui as? com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl)?.layout
                layoutImpl?.clearStateForId(LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID)
            }

            val existingContent = ui.findContent(LIVE_WATCH_DEBUG_CONTENT_ID)
            if (existingContent != null) {
                // 确保已有 Content 的 tabIndex 保持为独立顶层 Tab，防止被 CLion 异步恢复的旧布局降级到 tab 0
                runCatching {
                    val layoutImpl = ui as? com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl
                    val layout = layoutImpl?.layout
                    val view = layout?.getStateFor(existingContent)
                    if (layout != null && view != null && view.tabIndex != LIVE_WATCH_DEBUG_TAB_ID) {
                        val tab = layout.getOrCreateTab(LIVE_WATCH_DEBUG_TAB_ID)
                        view.assignTab(tab)
                        view.placeInGrid = com.intellij.execution.ui.layout.PlaceInGrid.center
                        layoutImpl.contentUI.updateTabsUI(false)
                    }
                }
                return@invokeLater
            }

            // 预先向 RunnerLayout 注册独立 Tab 的默认名称与图标，与线程与变量(0)/官方实时监视(10)/外设(11787)完全隔离
            runCatching {
                ui.defaults.initTabDefaults(
                    LIVE_WATCH_DEBUG_TAB_ID,
                    LIVE_WATCH_DISPLAY_NAME,
                    com.intellij.icons.AllIcons.Debugger.Watch,
                )
            }

            val panel = org.embedded.monitor.watch.LiveWatchPanel(project)
            val content = ui.createContent(
                LIVE_WATCH_DEBUG_CONTENT_ID,
                panel,
                LIVE_WATCH_DISPLAY_NAME,
                com.intellij.icons.AllIcons.Debugger.Watch,
                null,
            )
            content.isCloseable = false
            com.intellij.openapi.util.Disposer.register(content, panel)
            // 关键：以 LIVE_WATCH_DEBUG_TAB_ID 作为 defaultTabId，并 PlaceInGrid.center 居中填充，使之渲染为独立的顶层标签页
            ui.addContent(content, LIVE_WATCH_DEBUG_TAB_ID, com.intellij.execution.ui.layout.PlaceInGrid.center, false)

            // 强制视图绑定至专属 Tab，阻断任何可能的持久化历史 tabIndex=0 继承
            runCatching {
                val layoutImpl = ui as? com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl
                val layout = layoutImpl?.layout
                val view = layout?.getStateFor(content)
                if (layout != null && view != null && view.tabIndex != LIVE_WATCH_DEBUG_TAB_ID) {
                    val tab = layout.getOrCreateTab(LIVE_WATCH_DEBUG_TAB_ID)
                    view.assignTab(tab)
                    view.placeInGrid = com.intellij.execution.ui.layout.PlaceInGrid.center
                    layoutImpl.contentUI.updateTabsUI(false)
                }
            }
        }
    }

    /** 将寄存器实时监视面板作为独立顶层标签页嵌入到 CLion 原生 Debug 会话工具窗口中（Tab ID: 11801）。 */
    fun registerRegisterDebugTab(session: com.intellij.xdebugger.XDebugSession, attempt: Int = 0) {
        ApplicationManager.getApplication().invokeLater {
            if (session.isStopped) return@invokeLater
            val ui = session.ui
            if (ui == null) {
                if (attempt < 10 && !project.isDisposed) {
                    com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                        registerRegisterDebugTab(session, attempt + 1)
                    }, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return@invokeLater
            }

            val existingContent = ui.findContent(REGISTER_DEBUG_CONTENT_ID)
            if (existingContent != null) {
                runCatching {
                    val layoutImpl = ui as? com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl
                    val layout = layoutImpl?.layout
                    val view = layout?.getStateFor(existingContent)
                    if (layout != null && view != null && view.tabIndex != REGISTER_DEBUG_TAB_ID) {
                        val tab = layout.getOrCreateTab(REGISTER_DEBUG_TAB_ID)
                        view.assignTab(tab)
                        view.placeInGrid = com.intellij.execution.ui.layout.PlaceInGrid.center
                        layoutImpl.contentUI.updateTabsUI(false)
                    }
                }
                return@invokeLater
            }

            runCatching {
                ui.defaults.initTabDefaults(
                    REGISTER_DEBUG_TAB_ID,
                    REGISTER_DISPLAY_NAME,
                    com.intellij.icons.AllIcons.Debugger.Db_primitive,
                )
            }

            val panel = org.embedded.monitor.registers.RegisterLiveWatchPanel(project)
            val content = ui.createContent(
                REGISTER_DEBUG_CONTENT_ID,
                panel,
                REGISTER_DISPLAY_NAME,
                com.intellij.icons.AllIcons.Debugger.Db_primitive,
                null,
            )
            content.isCloseable = false
            com.intellij.openapi.util.Disposer.register(content, panel)
            ui.addContent(content, REGISTER_DEBUG_TAB_ID, com.intellij.execution.ui.layout.PlaceInGrid.center, false)

            runCatching {
                val layoutImpl = ui as? com.intellij.execution.ui.layout.impl.RunnerLayoutUiImpl
                val layout = layoutImpl?.layout
                val view = layout?.getStateFor(content)
                if (layout != null && view != null && view.tabIndex != REGISTER_DEBUG_TAB_ID) {
                    val tab = layout.getOrCreateTab(REGISTER_DEBUG_TAB_ID)
                    view.assignTab(tab)
                    view.placeInGrid = com.intellij.execution.ui.layout.PlaceInGrid.center
                    layoutImpl.contentUI.updateTabsUI(false)
                }
            }
        }
    }

    private fun attachDebugSession(session: com.intellij.xdebugger.XDebugSession, isNewProcess: Boolean = false) {
        if (!attachedSessions.add(session)) return
        registerLiveWatchDebugTab(session)
        registerRegisterDebugTab(session)
        // 捕获 CLion 原生调试器的求值器（即 IDE 自己的 GDB）：evalOnly 型监视项
        // 在断点暂停时经它求值——不新起 GDB、不占用 3333 端口、无与调试器抢目标的问题
        runCatching { session.debugProcess.evaluator }.getOrNull()?.let {
            clionEvaluator = it
            clionEvalSession = session
            logLine("CLion 原生求值器已就绪（复杂表达式断点期求值可用）")
        }
        setPollSuppression(true)
        // 监听器挂会话级 Disposable：此前 parent 为工程级 service，会话结束后
        // 注册节点（持有 session 强引用）不注销，反复启停调试会话逐次累积。
        // 会话正常结束时在 sessionStopped 主动 dispose；工程关闭时作为 service
        // 的子节点兜底释放
        val sessionDisposable = com.intellij.openapi.util.Disposer.newDisposable(
            "EmbeddedMonitor debug session listener"
        )
        com.intellij.openapi.util.Disposer.register(this, sessionDisposable)
        session.addSessionListener(object : com.intellij.xdebugger.XDebugSessionListener {
            override fun sessionPaused() {
                registerLiveWatchDebugTab(session)
                registerRegisterDebugTab(session)
                onDebugSessionPaused(session)
            }

            override fun sessionResumed() {
                registerLiveWatchDebugTab(session)
                registerRegisterDebugTab(session)
                onDebugSessionResumed(session)
            }

            override fun sessionStopped() {
                attachedSessions.remove(session)
                if (clionEvalSession === session) {
                    clionEvaluator = null
                    clionEvalSession = null
                }
                runCatching {
                    val ui = session.ui
                    ui?.findContent(LIVE_WATCH_DEBUG_CONTENT_ID)?.let { c ->
                        ui.removeContent(c, true)
                    }
                    ui?.findContent(LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID)?.let { c ->
                        ui.removeContent(c, true)
                    }
                    ui?.findContent(REGISTER_DEBUG_CONTENT_ID)?.let { c ->
                        ui.removeContent(c, true)
                    }
                }
                com.intellij.openapi.util.Disposer.dispose(sessionDisposable)
                onDebugSessionStopped(session)
            }

            override fun beforeSessionResume() {
                onDebugSessionBeforeResume(session)
            }
        }, sessionDisposable)

        // 仅当非刚启动的已有会话在挂载时已明确停在断点上时，才同步暂停态；
        // 刚启动的调试进程（isNewProcess == true）在 GDB 握手/复位阶段绝不误判为用户断点暂停！
        if (!isNewProcess && (session.isPaused || session.isSuspended)) {
            onDebugSessionPaused(session)
        }
    }

    private fun handleDebugProcessStarted(process: com.intellij.xdebugger.XDebugProcess) {
        attachDebugSession(process.session, isNewProcess = true)
        registerLiveWatchDebugTab(process.session)

        // 关键防护：若插件正通过 probe-rs 连接硬件，必须立即断开释放 USB 端口，否则导致 OpenOCD 竞争崩溃（cannot read IDR）
        if (currentEffectiveBackend == "probe-rs" && (engineState == "running" || engineState == "connecting")) {
            logLine("检测到 CLion 启动调试，立即断开 probe-rs 后端释放硬件 USB 探针")
            disconnectEngine()
        }

        if (!settings.autoStartWithDebug) return

        desiredRunning.set(true)
        logLine("检测到调试会话启动，等待 OpenOCD 端口就绪...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val targetPort = settings.tclPort
            var ready = false
            for (i in 0 until 60) {
                if (!desiredRunning.get()) {
                    logLine("监视已停止，终止等待 OpenOCD 端口")
                    return@executeOnPooledThread
                }
                if (!org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project) && attachedSessions.isEmpty()) {
                    logLine("调试会话已退出，终止等待 OpenOCD 端口")
                    return@executeOnPooledThread
                }
                if (agentRunning && (engineState == "running" || engineState == "halted")) {
                    logLine("OpenOCD 监视已处于连接态 ($engineState)")
                    return@executeOnPooledThread
                }
                if (org.embedded.monitor.cmake.OpenOcdConfigReader.isOpenOcdPortListening(targetPort, 300)) {
                    ready = true
                    break
                }
                Thread.sleep(500)
            }
            if (ready) {
                Thread.sleep(200) // 让步 200ms 等待 OpenOCD 稳定，避免 TCP 快速开关导致 OpenOCD Tcl 假死
                logLine("OpenOCD Tcl 端口 ($targetPort) 已就绪，自动启动调试监视（openocd attach-only）")
                runCatching {
                    connectEngine().get(60, java.util.concurrent.TimeUnit.SECONDS)
                    // 连接成功后，立即根据当前调试会话实际状态对齐（避免连接期间错过的 resume 事件导致一直处于暂停态）
                    syncDebugAndEngineState()
                }.onFailure {
                    if (!isHaltedByDebug && engineState != "halted") {
                        notify("调试会话自动监视启动失败: ${it.message}")
                    }
                }
            } else {
                val isGdbOrTelnetUp = org.embedded.monitor.cmake.OpenOcdConfigReader.isOpenOcdOrGdbActive(project, targetPort)
                val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
                if (isGdbOrTelnetUp || isDebugging) {
                    val cfg = org.embedded.monitor.cmake.OpenOcdConfigReader.detectBoardConfig(project)
                    val cfgHint = if (!cfg.isNullOrBlank()) "在配置文件（$cfg）末尾" else "在 OpenOCD 配置文件（.cfg）末尾"
                    val msg = "CLion 调试会话已启动，但 OpenOCD Tcl RPC 端口 ($targetPort) 未开放（CLion 默认参数禁用了 Tcl）。请${cfgHint}添加一行 'tcl_port 6666' 并重新启动调试。"
                    logLine(msg)
                    notify(msg, com.intellij.notification.NotificationType.WARNING)
                } else {
                    logLine("OpenOCD Tcl 端口 ($targetPort) 未就绪，跳过自动启动")
                }
            }
        }
    }

    private fun handleDebugProcessStopped(process: com.intellij.xdebugger.XDebugProcess) {
        attachedSessions.remove(process.session)
        if (clionEvalSession === process.session) {
            clionEvaluator = null
            clionEvalSession = null
        }
        onDebugSessionStopped(process.session)
    }

    private fun onDebugSessionPaused(session: com.intellij.xdebugger.XDebugSession) {
        if (!settings.pausePollingOnBreakpoint) return
        isHaltedByDebug = true
        watchHalted = true
        cancelConnectWatchdog()
        if (engineState != "disconnected") {
            engineState = "halted"
        }
        synchronized(watchItems) {
            watchItems.forEach { it.halted = true }
        }
        logLine("CLion 命中断点（目标已暂停），暂停示波器高频轮询，执行单次快照刷新")

        val client = clientRef.get()
        if (client != null && agentRunning) {
            ApplicationManager.getApplication().executeOnPooledThread {
                // 仅清空示波器高频通道，避免在断点处高频发探针打扰 GDB 读取寄存器/堆栈；保留 watch 监视目标以便快速恢复
                runCatching {
                    client.requestSync("set_scope_targets", JsonObject().apply { add("targets", JsonArray()) }, 2000)
                }
                // 暂停瞬间对监视列表做单次快照采样读取
                sampleWatchOnceOnPause()
            }
        }
        // evalOnly 型监视项（复杂 C 表达式）：借 CLion 原生调试器求值
        refreshEvalOnlyItems()
    }

    private fun onDebugSessionBeforeResume(session: com.intellij.xdebugger.XDebugSession) {
        // 恢复前预留
    }

    private fun onDebugSessionResumed(session: com.intellij.xdebugger.XDebugSession) {
        isHaltedByDebug = false
        watchHalted = false
        cancelConnectWatchdog()
        if (!desiredRunning.get()) return
        hasConnectedOnce.set(true)
        if (engineState == "halted" || engineState == "connecting") {
            engineState = "running"
        }
        synchronized(watchItems) {
            watchItems.forEach { it.halted = false }
        }
        logLine("CLion 恢复运行，恢复示波器与实时变量监视轮询")
        if (!agentRunning || clientRef.get() == null || currentEffectiveBackend.isBlank()) {
            connectEngine()
        } else {
            pushWatchTargets()
            pushScopeTargets()
        }
        syncDebugAndEngineState()
    }

    private fun onDebugSessionStopped(session: com.intellij.xdebugger.XDebugSession) {
        attachedSessions.remove(session)
        val remaining = runCatching {
            com.intellij.xdebugger.XDebuggerManager.getInstance(project)
                .debugSessions.filter { !it.isStopped && it != session }
        }.getOrNull() ?: emptyList()
        val isStillDebugging = remaining.isNotEmpty() || attachedSessions.any { !it.isStopped }
        if (!isStillDebugging) {
            isHaltedByDebug = false
            watchHalted = false
            cancelConnectWatchdog()
            if (currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession)) {
                logLine("CLion 调试会话已结束，安全断开 OpenOCD attach-only 监视连接")
                isAttachedToDebugSession = false
                disconnectEngine()
                lastEngineError = null
            }
        }
    }

    /**
     * 关键协同机制：深度对齐 CLion 调试会话状态与插件引擎状态机。
     * 由 UI 刷新定时器 (LiveWatchPanel.tick) 以及调试状态变迁主动周期性调用。
     * 彻底消除：
     * 1) 调试进程启动阶段因 GDB 初始化瞬态产生假暂停导致 isHaltedByDebug 锁死的问题；
     * 2) 外部/命令行 resume 恢复运行未触发 sessionResumed 事件的状态脱节；
     * 3) 调试会话结束时残留断点暂停态的问题。
     */
    fun syncDebugAndEngineState() {
        val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
        val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)

        if (!desiredRunning.get()) {
            isHaltedByDebug = false
            watchHalted = false
            return
        }

        if (isDebugging) {
            if (isDebuggerPaused) {
                // 场景 2：CLion 处于断点命中态，同步对齐插件状态为 halted
                if (!isHaltedByDebug || engineState != "halted") {
                    if (settings.pausePollingOnBreakpoint) {
                        isHaltedByDebug = true
                        watchHalted = true
                        cancelConnectWatchdog()
                        if (engineState != "disconnected") {
                            engineState = "halted"
                        }
                        synchronized(watchItems) {
                            watchItems.forEach { it.halted = true }
                        }
                        val client = clientRef.get()
                        if (client != null && agentRunning) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runCatching {
                                    client.requestSync("set_scope_targets", JsonObject().apply { add("targets", JsonArray()) }, 2000)
                                }
                                sampleWatchOnceOnPause()
                            }
                        }
                        logLine("检测到 CLion 处于断点暂停态，同步挂起示波器高频轮询并执行快照")
                    }
                }
            } else {
                // 场景 1：CLion 处于全速运行态，解除暂停锁定并恢复实时变量监视
                if (isHaltedByDebug || (engineState == "halted" && currentEffectiveBackend == "openocd")) {
                    isHaltedByDebug = false
                    watchHalted = false
                    if (engineState == "halted") {
                        engineState = "running"
                    }
                    synchronized(watchItems) {
                        watchItems.forEach { it.halted = false }
                    }
                    if (!agentRunning || clientRef.get() == null || currentEffectiveBackend.isBlank()) {
                        connectEngine()
                    } else {
                        pushWatchTargets()
                        pushScopeTargets()
                    }
                    logLine("CLion 处于全速运行中，自动解除暂停锁定并恢复实时变量监视")
                }
            }
        } else {
            // 没有活动的调试会话，绝不可保留 isHaltedByDebug
            if (isHaltedByDebug) {
                isHaltedByDebug = false
                watchHalted = false
                if (engineState == "halted") {
                    engineState = "running"
                    synchronized(watchItems) {
                        watchItems.forEach { it.halted = false }
                    }
                    pushWatchTargets()
                    pushScopeTargets()
                }
            }
            if (currentEffectiveBackend == "openocd" && (currentEffectiveAttachOnly || isAttachedToDebugSession) &&
                (engineState == "running" || engineState == "halted" || engineState == "connecting")) {
                cancelConnectWatchdog()
                desiredRunning.set(false)
                hasConnectedOnce.set(false)
                isAttachedToDebugSession = false
                engineState = "disconnected"
                lastEngineError = null
            }
        }
    }

    private val isSnapshotRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 目标暂停瞬间执行一次全监视项快照采样。包含未勾选自动刷新的条目（未勾选时仅在暂停时读取）。 */
    fun sampleWatchOnceOnPause() {
        val client = clientRef.get() ?: return
        if (!agentRunning) return
        val itemsToSample = mutableListOf<Triple<String, Long, Int>>()
        synchronized(watchItems) {
            watchItems.forEach {
                if (it.size > 0 && it.address >= 0x1000L) {
                    itemsToSample.add(Triple(it.id, it.address, it.size.coerceIn(1, 4096)))
                }
            }
        }
        synchronized(dynamicWatchTargets) {
            dynamicWatchTargets.values.forEach {
                if (it.size > 0 && it.address >= 0x1000L) {
                    itemsToSample.add(Triple(it.id, it.address, it.size.coerceIn(1, 4096)))
                }
            }
        }
        if (itemsToSample.isEmpty()) return

        // 防抖与单飞保护：避免多个并发源造成读内存请求风暴与超时堆积
        if (!isSnapshotRunning.compareAndSet(false, true)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var anyUpdated = false
                for ((id, addr, size) in itemsToSample) {
                    // 若目标已恢复全速运行，提前终止快照采样
                    if (!isHaltedByDebug && engineState == "running") break
                    runCatching {
                        val p = JsonObject()
                        p.addProperty("addr", addr)
                        p.addProperty("size", size)
                        val resp = client.requestSync("read_mem", p, 1000)
                        val ok = resp.get("ok")?.asBoolean ?: false
                        if (ok) {
                            val res = resp.get("result")
                            if (res != null && res.isJsonArray) {
                                val arr = res.asJsonArray
                                val bytes = ByteArray(arr.size()) { arr[it].asByte }
                                if (bytes.isNotEmpty()) {
                                    watchValues[id] = bytes
                                    anyUpdated = true
                                }
                            }
                        }
                    }.onFailure {
                        log.debug("断点快照采样 $id @ 0x%08X 失败: ${it.message}".format(Locale.ROOT, addr))
                    }
                }
                if (anyUpdated) {
                    watchDataListeners.forEach { runCatching { it() } }
                }
            } finally {
                isSnapshotRunning.set(false)
            }
        }
    }

    /** 订阅 VFS：ELF 构建产物一落盘就重载（替代轮询）。 */
    private fun subscribeVfsElfChanges() {
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                override fun after(events: MutableList<out com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    val current = elfPath ?: return
                    for (e in events) {
                        val vf = when (e) {
                            is com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent -> e.file
                            is com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent -> e.file
                            else -> continue
                        }
                        val n = vf.name.lowercase()
                        if (!n.endsWith(".elf") && !n.endsWith(".axf")) continue
                        // 预筛：文件名不同则不可能与当前 ELF 同路径。canonicalPath 是
                        // 磁盘 IO 且 VFS 刷新期可能回调在 EDT，仅文件名相同时才精确比较
                        val sameName = current.endsWith("/$n", true) || current.endsWith("\$n", true)
                        if (!sameName) continue
                        val same = runCatching {
                            java.io.File(vf.path).canonicalPath.equals(current, ignoreCase = true)
                        }.getOrDefault(false)
                        if (same) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runCatching { loadElf(java.io.File(vf.path), "构建更新") }
                            }
                        }
                    }
                }
            },
        )
    }

    /** 非阻塞通知（替代模态错误框）。 */
    fun notify(message: String, type: com.intellij.notification.NotificationType = com.intellij.notification.NotificationType.WARNING) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Embedded Monitor")
            .createNotification("Embedded Monitor", message.take(400), type)
            .notify(project)
    }

    // ================= agent 生命周期 =================

    private val agentInitLock = Any()
    @Volatile private var agentInitFuture: CompletableFuture<Void>? = null
    /** dispose 与 agent 启动任务存在竞态窗口（locateAgent IO + start 秒级），
     *  关闭后启动任务必须自检放弃，否则拉起的 agent 进程无人回收 */
    @Volatile private var disposed = false

    private fun closeAgent() {
        clientRef.getAndSet(null)?.close()
        agentRunning = false
        agentInitFuture = null
    }

    /** 确保 agent 进程与连接就绪（幂等且非阻塞，执行在线程池中）。 */
    fun ensureAgent(): CompletableFuture<Void> {
        val existing = clientRef.get()
        if (existing != null && agentRunning) return CompletableFuture.completedFuture(null)
        synchronized(agentInitLock) {
            val cur = clientRef.get()
            if (cur != null && agentRunning) return CompletableFuture.completedFuture(null)
            val inFlight = agentInitFuture
            if (inFlight != null && !inFlight.isDone) return inFlight

            val future = CompletableFuture<Void>()
            agentInitFuture = future
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    if (disposed) {
                        future.completeExceptionally(IllegalStateException("工程已关闭，取消 agent 启动"))
                        return@executeOnPooledThread
                    }
                    closeAgent()
                    val path = locateAgent(settings.agentPath, project)
                    if (path == null) {
                        val msg = "未找到 embedded-clion-agent 可执行文件，请在插件设置中配置路径（先 cargo build --release -p embedded-clion-agent）。"
                        lastAgentError = msg
                        future.completeExceptionally(IllegalStateException(msg))
                        return@executeOnPooledThread
                    }
                    val client = AgentClient(
                        agentPath = path,
                        onEngineEvent = ::onEngineEvent,
                        onProcessExit = { code ->
                            agentRunning = false
                            if (engineState != "disconnected") {
                                engineState = "disconnected"
                            }
                            lastAgentError = "agent 进程退出（code=$code）"
                            logLine("agent 进程退出 code=$code")
                        },
                        onLog = { logLine(it) },
                    )
                    client.start()
                    if (disposed) {
                        // start 期间工程已关闭：立即回收，避免孤儿 agent 进程
                        client.close()
                        future.completeExceptionally(IllegalStateException("工程已关闭，agent 已回收"))
                        return@executeOnPooledThread
                    }
                    clientRef.set(client)
                    agentRunning = true
                    lastAgentError = null
                    future.complete(null)
                } catch (e: Exception) {
                    lastAgentError = "启动 agent 失败: ${e.message}"
                    log.warn(lastAgentError, e)
                    future.completeExceptionally(e)
                }
            }
            return future
        }
    }

    // ================= 引擎连接 =================

    @Volatile private var inFlightConnectFuture: CompletableFuture<Void>? = null

    /** 连接目标（启动监视/示波采样）。异步非阻塞。supervised=true 表示由监督线程发起的自动重连：
     *  不重置退避计数、不启看门狗、失败后保持期望运行态交回监督线程继续重试。 */
    fun connectEngine(supervised: Boolean = false): CompletableFuture<Void> {
        desiredRunning.set(true)

        // 关键防护 1：已连接且处于 running 或 halted 态，直接判定成功并按需恢复刷新/快照，严禁触发重连
        if (agentRunning && (engineState == "running" || engineState == "halted")) {
            hasConnectedOnce.set(true)
            cancelConnectWatchdog()
            val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
            if (isDebuggerPaused) {
                sampleWatchOnceOnPause()
            } else {
                pushWatchTargets()
                pushScopeTargets()
            }
            return CompletableFuture.completedFuture(null)
        }

        // 关键防护 2：若当前调试器命中断点（目标处于暂停态），严禁向底层发起新的全量握手连接与看门狗
        if (org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)) {
            isHaltedByDebug = true
            engineState = "halted"
            watchHalted = true
            cancelConnectWatchdog()
            sampleWatchOnceOnPause()
            return CompletableFuture.completedFuture(null)
        }

        // 关键防护 3：若已有连接在飞（connecting），直接复用该 Future，严禁并发重入引发多重看门狗竞争
        val inFlight = inFlightConnectFuture
        if (engineState == "connecting" && inFlight != null && !inFlight.isDone) {
            return inFlight
        }

        if (!supervised) {
            hasConnectedOnce.set(false)
            reconnectAttempts.set(0)
        }
        lastStateChangeAt = System.currentTimeMillis()
        val future = doConnectEngine(supervised = supervised)
        inFlightConnectFuture = future
        return future
    }

    data class EffectiveBackendConfig(
        val backend: String,
        val attachOnly: Boolean,
        val reason: String = "",
    )

    /** 动态判定实际使用的采样后端：调试态或 OpenOCD 运行中时自动路由为 openocd attach-only */
    fun determineEffectiveBackend(): EffectiveBackendConfig {
        val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
        val isOcdPortListening = if (isDebugging) false else org.embedded.monitor.cmake.OpenOcdConfigReader.isOpenOcdPortListening(settings.tclPort, 300)

        if (settings.autoSwitchBackendOnDebug && (isDebugging || isOcdPortListening)) {
            val reason = if (isDebugging) {
                "检测到 CLion 处于调试态，自动切换为 openocd 后端 (attach-only)，杜绝 probe-rs 竞争硬件探针"
            } else {
                "检测到 OpenOCD (port ${settings.tclPort}) 正在监听，自动切换为 openocd 后端 (attach-only)"
            }
            return EffectiveBackendConfig(
                backend = "openocd",
                attachOnly = true,
                reason = reason,
            )
        }

        return EffectiveBackendConfig(
            backend = settings.backend,
            attachOnly = if (settings.backend == "openocd") settings.attachOnly else false,
            reason = "使用配置采样后端: ${settings.backend}",
        )
    }

    /**
     * 判断当前是否处于「附着于 CLion 调试会话的 OpenOCD 因调试结束而断开/退出」的状态。
     * 关键条件：
     * 1. 当前后端必须为 openocd；
     * 2. 该后端必须是附属于 CLion 调试会话的 (currentEffectiveAttachOnly 或 isAttachedToDebugSession)；
     * 3. CLion 调试已经不再处于活跃状态 (!isDebugging 或 attachedSessions 全部已停止)。
     * 注意：严格禁止将第 2 条与第 3 条使用 || 扁平连接，否则会导致非调试模式下的独立 OpenOCD 监控被误判为调试退出！
     */
    fun isOpenOcdDebugExited(): Boolean {
        val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
        return currentEffectiveBackend == "openocd" &&
            (currentEffectiveAttachOnly || isAttachedToDebugSession) &&
            (!isDebugging || attachedSessions.isEmpty())
    }

    private fun doConnectEngine(supervised: Boolean = false): CompletableFuture<Void> {
        // 如果调试器此时处于断点暂停，则直接保持断点态，绝不启动看门狗与底层全量重连
        if (org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)) {
            isHaltedByDebug = true
            engineState = "halted"
            watchHalted = true
            cancelConnectWatchdog()
            sampleWatchOnceOnPause()
            return CompletableFuture.completedFuture(null)
        }

        engineState = "connecting"
        lastEngineError = null
        // 监督重连不启看门狗：瞬时失败不应弹"连接超时"打扰用户，由监督线程按退避继续
        if (!supervised) startConnectWatchdog(15)
        return ensureAgent().thenComposeAsync({
            val client = clientRef.get()
                ?: return@thenComposeAsync CompletableFuture.failedFuture<Void>(IllegalStateException("agent 未启动"))
            try {
                val eff = determineEffectiveBackend()
                if (eff.reason.isNotBlank()) {
                    logLine(eff.reason)
                }
                if (eff.backend == "probe-rs" && org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)) {
                    throw IllegalStateException("CLion 正在调试中，硬件探针已被调试器占用，禁止使用 probe-rs！请切换为 openocd 后端。")
                }
                currentEffectiveBackend = eff.backend
                currentEffectiveAttachOnly = eff.attachOnly
                val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
                isAttachedToDebugSession = isDebugging || attachedSessions.isNotEmpty()

                val p = JsonObject()
                p.addProperty("backend", eff.backend)

                var targetChip = settings.chipTarget.trim()
                if (targetChip.isBlank() && eff.backend == "probe-rs") {
                    targetChip = org.embedded.monitor.cmake.ChipAutoResolver.detectChip(project)
                    if (targetChip.isNotBlank()) {
                        logLine("自动识别目标芯片: $targetChip")
                    }
                }
                if (targetChip.isNotBlank()) {
                    p.addProperty("target", targetChip)
                } else if (eff.backend == "probe-rs") {
                    throw IllegalStateException("使用 probe-rs 后端时需指定芯片型号（例如 STM32G431CB），请在插件设置中填写。")
                }

                settings.openocdPath.takeIf { it.isNotBlank() }?.let { p.addProperty("openocdPath", it) }
                settings.scriptsDir.takeIf { it.isNotBlank() }?.let { p.addProperty("scriptsDir", it) }
                p.addProperty("speedHz", settings.speedHz)
                p.addProperty("attachOnly", eff.attachOnly)
                p.addProperty("tclPort", settings.tclPort)
                settings.probeSerial.takeIf { it.isNotBlank() }?.let { p.addProperty("probeSerial", it) }
                val resp = client.requestSync("connect", p, timeoutMs = 12000)
                val ok = resp.get("ok")?.asBoolean ?: false
                if (!ok) {
                    val err = resp.get("error")?.asString ?: "connect 失败"
                    throw IllegalStateException(err)
                }
                CompletableFuture.completedFuture<Void>(null)
            } catch (t: Throwable) {
                val err = extractError(t) ?: t.message ?: "connect 失败"
                CompletableFuture.failedFuture<Void>(IllegalStateException(err, t))
            }
        }, java.util.concurrent.Executor { ApplicationManager.getApplication().executeOnPooledThread(it) })
            .whenComplete { _, err ->
                inFlightConnectFuture = null
                if (err != null) {
                    // 若当前数据流已自愈恢复为 running，或已处于断点停机保护中，则不被异步阶段失败覆盖
                    if (engineState == "connecting") {
                        cancelConnectWatchdog()
                        engineState = "disconnected"
                        val isOpenOcdDebugExited = isOpenOcdDebugExited()
                        val msg = if (isOpenOcdDebugExited) null else (extractError(err) ?: err.message ?: "连接失败")
                        lastEngineError = msg
                        lastStateChangeAt = System.currentTimeMillis()
                        // 仅用户主动发起的连接失败才放弃期望运行态；监督重连失败交回
                        // 监督线程按退避继续（否则一次瞬时失败就静默放弃重连）
                        if (!supervised) desiredRunning.set(false)
                    }
                }
            }
    }

    fun disconnectEngine() {
        inFlightConnectFuture = null
        cancelConnectWatchdog()
        desiredRunning.set(false)
        hasConnectedOnce.set(false)
        isAttachedToDebugSession = false
        lastStateChangeAt = System.currentTimeMillis()
        engineState = "disconnected"
        lastEngineError = null
        val client = clientRef.get() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.requestSync("disconnect", timeoutMs = 3000) }
        }
    }

    // ================= 事件处理（agent-reader 线程） =================

    private fun onEngineEvent(ev: EngineEvent) {
        when (ev) {
            is EngineEvent.Connected -> {
                cancelConnectWatchdog()
                if (!desiredRunning.get()) {
                    val client = clientRef.get()
                    client?.let {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            runCatching { it.requestSync("disconnect", timeoutMs = 2000) }
                        }
                    }
                    return
                }
                val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
                if (isDebuggerPaused) {
                    isHaltedByDebug = true
                    engineState = "halted"
                    watchHalted = true
                } else {
                    isHaltedByDebug = false
                    engineState = "running"
                    watchHalted = false
                }
                hasConnectedOnce.set(true)
                desiredRunning.set(true)
                engineDescription = ev.description
                lastEngineError = null
                logLine("已连接: ${ev.description}" + if (isDebuggerPaused) "（目标当前处于暂停态）" else "")
                isNewEngineSession = true
                pushWatchTargets()
                pushScopeTargets()
                setScopeFreq(settings.scopeFreqHz)
                setWatchFreq(settings.watchRefreshFreq.toDouble())
                if (isDebuggerPaused) {
                    sampleWatchOnceOnPause()
                }
                stateListeners.forEach { runCatching { it("connected") } }
            }
            is EngineEvent.Disconnected -> {
                cancelConnectWatchdog()
                val isOpenOcdDebugExited = isOpenOcdDebugExited()
                if (!desiredRunning.get() || isOpenOcdDebugExited) {
                    desiredRunning.set(false)
                    hasConnectedOnce.set(false)
                    isAttachedToDebugSession = false
                    engineState = "disconnected"
                    isHaltedByDebug = false
                    isNewEngineSession = true
                    lastEngineError = null
                    synchronized(this) {
                        _actualWatchRateHz = 0.0
                        watchRateWindowStart = 0
                        watchRateWindowCount = 0
                        lastWatchFrameTime = 0
                        _actualRateHz = 0.0
                        rateWindowStart = 0
                        rateWindowCount = 0
                        lastScopeFrameTime = 0
                    }
                    logLine("已断开: ${ev.reason}")
                    if (isOpenOcdDebugExited) {
                        val client = clientRef.get()
                        client?.let {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                runCatching { it.requestSync("disconnect", timeoutMs = 2000) }
                            }
                        }
                    }
                } else {
                    logLine("收到底层断开通知（${ev.reason}），保持期望连接态并等待恢复")
                    engineState = "connecting"
                }
                stateListeners.forEach { runCatching { it("disconnected") } }
            }
            is EngineEvent.State -> {
                val isOpenOcdDebugExited = isOpenOcdDebugExited()
                val isDebugging = org.embedded.monitor.cmake.OpenOcdConfigReader.isDebuggingActive(project)
                val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
                stateListeners.forEach { runCatching { it(ev.state) } }
                if (ev.state == "running") {
                    if (!desiredRunning.get() || (isOpenOcdDebugExited && !isDebugging)) return
                    // 底层单片机正在运行：若 CLion 调试器未命中断点，则绝不可判定为暂停态
                    if (!isDebuggerPaused) {
                        isHaltedByDebug = false
                        watchHalted = false
                        cancelConnectWatchdog()
                        hasConnectedOnce.set(true)
                        desiredRunning.set(true)
                        engineState = "running"
                        lastEngineError = null
                        synchronized(watchItems) {
                            watchItems.forEach { it.halted = false }
                        }
                        pushWatchTargets()
                        pushScopeTargets()
                    }
                } else if (ev.state == "halted") {
                    if (!desiredRunning.get() || (isOpenOcdDebugExited && !isDebugging)) return
                    // 底层单片机暂停（可能命中断点或底层暂停）
                    if (settings.pausePollingOnBreakpoint) {
                        isHaltedByDebug = isDebuggerPaused
                        watchHalted = true
                        cancelConnectWatchdog()
                        if (engineState != "disconnected") {
                            engineState = "halted"
                        }
                        synchronized(watchItems) {
                            watchItems.forEach { it.halted = true }
                        }
                        sampleWatchOnceOnPause()
                    }
                } else if (ev.state == "disconnected") {
                    if (!desiredRunning.get() || isOpenOcdDebugExited) {
                        desiredRunning.set(false)
                        hasConnectedOnce.set(false)
                        isAttachedToDebugSession = false
                        engineState = "disconnected"
                        isHaltedByDebug = false
                        lastEngineError = null
                    } else {
                        engineState = "connecting"
                    }
                }
            }
            is EngineEvent.WatchData -> {
                if (!desiredRunning.get()) return
                // 自愈机制：持续收到采样数据即证明通信与引擎正常，彻底自愈修复状态机脱节与未连接假象
                cancelConnectWatchdog()
                hasConnectedOnce.set(true)
                lastEngineError = null
                if (engineDescription.isBlank()) {
                    engineDescription = if (currentEffectiveBackend == "openocd") "OpenOCD" else "probe-rs"
                }
                val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
                if (!isDebuggerPaused && isHaltedByDebug) {
                    isHaltedByDebug = false
                }
                val targetState = if (isDebuggerPaused || ev.halted) "halted" else "running"
                if (engineState != targetState && desiredRunning.get()) {
                    engineState = targetState
                }
                watchHalted = ev.halted || isDebuggerPaused
                ev.values.forEach { (id, bytes) -> watchValues[id] = bytes }
                synchronized(watchItems) {
                    watchItems.forEach { it.halted = watchHalted }
                }
                countWatchRate(1)
                watchDataListeners.forEach { runCatching { it() } }
            }
            is EngineEvent.ScopeData -> {
                if (!desiredRunning.get()) return
                // 自愈机制：持续收到示波数据即证明通信与引擎正常，修复状态机失真错位
                cancelConnectWatchdog()
                hasConnectedOnce.set(true)
                lastEngineError = null
                if (engineDescription.isBlank()) {
                    engineDescription = if (currentEffectiveBackend == "openocd") "OpenOCD" else "probe-rs"
                }
                val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
                if (!isDebuggerPaused && isHaltedByDebug) {
                    isHaltedByDebug = false
                }
                val targetState = if (isDebuggerPaused || isHaltedByDebug) "halted" else "running"
                if (engineState != targetState && desiredRunning.get()) {
                    engineState = targetState
                }
                watchHalted = isDebuggerPaused || isHaltedByDebug
                if (!isScopePaused) {
                    synchronized(bufLock) {
                        val variables = scopeVariables.filter { it.resolved }
                        // 空档判定周期优先用本批数据自带的采样间隔——配置切换期间的
                        // 在途帧按其真实节拍判定，不按前端当前配置误判
                        val periodNanos = ((ev.intervalUs ?: (1e9 / currentScopeFreqHz.coerceIn(1.0, 5000.0)).toLong())
                            .coerceAtLeast(1)) * 1000
                        for (sample in ev.samples) {
                            val rawT = sample.t
                            val isEpochReset = isNewEngineSession || (lastRawTimeSec >= 0.0 && rawT < lastRawTimeSec)
                            if (isEpochReset) {
                                isNewEngineSession = false
                                if (continueScopeHistory) {
                                    val currentMaxNanos = scopeSeries.values.maxOfOrNull { it.lastOrNull()?.timestampNanos ?: 0L } ?: 0L
                                    val currentMaxSec = currentMaxNanos / 1e9
                                    timeOffsetSec = if (currentMaxSec > 0.0) {
                                        (currentMaxSec + 0.033) - rawT
                                    } else {
                                        0.0
                                    }
                                } else {
                                    scopeSeries.clear()
                                    timeOffsetSec = 0.0
                                    lastEmittedTimestampNanos = 0L
                                }
                            }
                            lastRawTimeSec = rawT
                            val adjustedT = rawT + timeOffsetSec
                            var tNanos = (adjustedT * 1e9).toLong()
                            if (tNanos <= lastEmittedTimestampNanos && lastEmittedTimestampNanos > 0L) {
                                tNanos = lastEmittedTimestampNanos + 100_000L // 保证至少单调递增 0.1ms
                            }
                            if (lastEmittedTimestampNanos > 0L &&
                                ScopeSamples.isLongGap(lastEmittedTimestampNanos, tNanos, periodNanos)) {
                                scopeGapCounter.incrementAndGet()
                            }
                            lastEmittedTimestampNanos = tNanos

                            val values = sample.values.mapNotNull { (addrHex, bytes) ->
                                addrHex.removePrefix("0x").toLongOrNull(16)?.let { it to bytes }
                            }.toMap()
                            for (variable in variables) {
                                val deque = scopeSeries.getOrPut(variable.address) { java.util.ArrayDeque(4096) }
                                if (ScopeSamples.append(deque, tNanos, values[variable.address], variable,
                                        periodNanos, scopeCapacity)) {
                                    scopeDroppedCounter.incrementAndGet()
                                }
                            }
                        }
                        scopeVersion++
                    }
                    scopeSampleCounter.addAndGet(ev.samples.size.toLong())
                }
                countRate(ev.samples.size)
            }
            is EngineEvent.Error -> {
                val isOpenOcdDebugExited = isOpenOcdDebugExited()
                if (!isOpenOcdDebugExited && desiredRunning.get()) {
                    lastEngineError = ev.message
                }
                scopeErrorCounter.incrementAndGet()
                logLine("引擎错误: ${ev.message}")
                // 关键修正：非致命错误（如局部变量地址非法、单次读取越界）绝不破坏主连接状态机；
                // 真正的致命断开由 Rust 引擎发送 Event::Disconnected / Event::State("disconnected") 统一驱动。
            }
            is EngineEvent.Log -> {
                logLine("[引擎] ${ev.message}")
                val isOpenOcdDebugExited = isOpenOcdDebugExited()
                if (isOpenOcdDebugExited || !desiredRunning.get()) {
                    lastEngineError = null
                } else if (ev.message.contains("连接失败") || ev.message.contains("重试") || ev.message.contains("error", ignoreCase = true)) {
                    lastEngineError = ev.message
                }
            }
            is EngineEvent.Unknown -> {}
        }
    }

    private fun countRate(n: Int) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            lastScopeFrameTime = now
            if (rateWindowStart == 0L) rateWindowStart = now
            rateWindowCount += n
            val dt = now - rateWindowStart
            if (dt >= 1000) {
                _actualRateHz = rateWindowCount * 1000.0 / dt
                rateWindowStart = now
                rateWindowCount = 0
            }
        }
    }

    private fun countWatchRate(n: Int) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            lastWatchFrameTime = now
            if (watchRateWindowStart == 0L) watchRateWindowStart = now
            watchRateWindowCount += n
            val dt = now - watchRateWindowStart
            if (dt >= 1000) {
                _actualWatchRateHz = watchRateWindowCount * 1000.0 / dt
                watchRateWindowStart = now
                watchRateWindowCount = 0
            }
        }
    }

    // ================= ELF =================

    /** 自动探测 ELF（设置 elfAuto 时调用），成功则加载。 */
    fun autoDetectElf(): CompletableFuture<File?> {
        if (!settings.elfAuto) return CompletableFuture.completedFuture(null)
        val future = CompletableFuture<File?>()
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = runCatching { ElfAutoResolver.autoDetect(project) }.getOrNull()
            if (found != null) {
                loadElf(found, "自动探测").whenComplete { _, _ -> future.complete(found) }
            } else {
                future.complete(null)
            }
        }
        return future
    }

    /** 手动/自动选择 ELF 列表（供下拉框）。 */
    fun listElfCandidates(): List<ElfAutoResolver.Candidate> =
        runCatching { ElfAutoResolver.candidates(project) }.getOrElse { emptyList() }

    /** 加载 ELF 到 agent 并缓存变量树；成功后重解析监视项、恢复持久化配置。 */
    fun loadElf(file: File, source: String): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        ensureAgent().whenComplete { _, err ->
            val client = clientRef.get()
            if (err != null || client == null) {
                // client 为 null：ensureAgent 成功后 agent 恰好被关闭。必须异常完成，
                // 否则调用方 get(60s/90s) 只能干等超时
                future.completeExceptionally(err ?: IllegalStateException("agent 已关闭，无法加载 ELF"))
                return@whenComplete
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val p = JsonObject()
                    p.addProperty("path", file.canonicalPath)
                    val resp = client.requestSync("elf_load", p, timeoutMs = 60_000)
                    if (resp.get("ok")?.asBoolean != true) {
                        throw IllegalStateException(resp.get("error")?.asString ?: "elf_load 失败")
                    }
                    // 响应体在 result 包装层：{"id","ok","result":{...,"variables":[...]}}
                    val result = resp.getAsJsonObject("result")
                        ?: throw IllegalStateException("elf_load 响应缺少 result")
                    val variables = result.getAsJsonArray("variables")
                        ?: throw IllegalStateException("elf_load 响应缺少 variables")
                    val list = ArrayList<SymbolNode>(variables.size())
                    for (v in variables) {
                        list.add(gson.fromJson(v, SymbolNode::class.java))
                    }
                    synchronized(scopeResolveLock) {
                        elfVariables.clear()
                        elfVariables.addAll(list)
                        scopeVariables.filter { it.expression != null }.forEach { it.resolved = false }
                    }
                    elfPath = file.canonicalPath
                    elfMtime = runCatching { file.lastModified() }.getOrDefault(0L)
                    elfSource = source
                    elfLoaded = true
                    logLine("ELF 已加载: ${file.name}（${list.size} 个全局变量）")
                    // 监视项重解析 + 持久化恢复
                    ApplicationManager.getApplication().executeOnPooledThread {
                        reResolveWatches()
                        restorePersisted()
                        reResolveScopeVariables()
                        pushScopeTargets()
                    }
                    future.complete(true)
                } catch (e: Exception) {
                    lastAgentError = "ELF 加载失败: ${e.message}"
                    logLine(lastAgentError!!)
                    future.completeExceptionally(e)
                }
            }
        }
        return future
    }

    /** ELF 文件 mtime 变化检测（UI 定时器调用），变了就重载。 */
    fun checkElfRefresh(): Boolean {
        val path = elfPath ?: return false
        val f = File(path)
        if (!f.isFile) return false
        val m = f.lastModified()
        if (m != elfMtime) {
            elfMtime = m
            loadElf(f, elfSource.ifEmpty { "自动重载" })
            return true
        }
        return false
    }

    private val gson = com.google.gson.Gson()

    // ================= 监视项 =================

    /** 检查某表达式是否已存在于实时变量监视列表中。 */
    fun isWatchItemAdded(expr: String): Boolean {
        val trimmed = expr.trim()
        return watchItems.any { it.expr.equals(trimmed, ignoreCase = true) }
    }

    /** 添加监视（解析表达式 → agent 解析 → 目标推送）。在后台线程完成，回调在 EDT。
     *  已存在的变量自动拦截不重复添加。ELF 未加载时先自动探测加载。 */
    fun addWatch(expr: String, autoRefresh: Boolean = true): CompletableFuture<WatchItem> {
        val trimmed = expr.trim()
        val existing = watchItems.firstOrNull { it.expr.equals(trimmed, ignoreCase = true) }
        if (existing != null) {
            notify("变量「${existing.expr}」已在实时变量监视列表中", com.intellij.notification.NotificationType.INFORMATION)
            return CompletableFuture.completedFuture(existing)
        }

        val future = CompletableFuture<WatchItem>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 无超时 get() 会在 agent 启动挂死时永久占用 pooled 线程
                ensureAgent().get(20, java.util.concurrent.TimeUnit.SECONDS)
                if (!elfLoaded) {
                    autoDetectElf().get(60, java.util.concurrent.TimeUnit.SECONDS)
                }
                if (!elfLoaded) {
                    throw IllegalStateException(
                        "未能自动加载 ELF：请打开「实时变量监视」窗口检查符号表下拉框，" +
                            "或在设置 → Embedded Monitor 中指定 ELF 路径（工程构建一次后重试）。",
                    )
                }
                val item = try {
                    resolveWatchBlocking(trimmed)
                } catch (e: Exception) {
                    // ELF 已加载仍无法解析为内存地址（强转/函数调用等复杂 C 表达式）
                    // 且 agent 与 CLion 调试会话均在线：回退为 CLion 求值型监视，
                    // 断点暂停时由 IDE 原生 GDB 求值。ELF 未加载 / agent 掉线等
                    // 环境性问题不回退，保持原始报错（否则故障根因被静默掩盖）
                    if (elfLoaded && agentRunning && clientRef.get() != null && clionEvalAvailable()) {
                        notify(
                            "「$trimmed」无法解析为内存地址，已添加为 CLion 求值型监视（断点暂停时刷新）",
                            com.intellij.notification.NotificationType.INFORMATION,
                        )
                        WatchItem(
                            id = WatchValueFormatter.nextWatchId(),
                            expr = trimmed,
                            address = 0,
                            size = 0,
                            encoding = "eval",
                            typeName = "CLion eval",
                        ).apply { evalOnly = true }
                    } else if (elfLoaded && agentRunning && clientRef.get() != null && attachedSessions.isNotEmpty()) {
                        // 有调试会话但求值器仍不可用：给出可行动的诊断而非裸解析错误
                        throw IllegalStateException(
                            "「$trimmed」无法解析为内存地址，且 CLion 原生求值器不可用" +
                                "（调试器初始化中或未暴露求值器；请稍后重试或重启调试会话）", e,
                        )
                    } else {
                        throw e
                    }
                }
                item.autoRefresh = autoRefresh
                val dup = watchItems.firstOrNull { it.expr.equals(item.expr, ignoreCase = true) }
                if (dup != null) {
                    notify("变量「${dup.expr}」已在实时变量监视列表中", com.intellij.notification.NotificationType.INFORMATION)
                    future.complete(dup)
                    return@executeOnPooledThread
                }
                watchItems.add(item)
                pushWatchTargets()
                persistWatches()
                // 已处于断点暂停态时，求值型监视项立即出值（否则要等下一次断点）
                if (item.evalOnly && isHaltedByDebug) {
                    refreshEvalOnlyItems()
                }
                future.complete(item)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun removeWatch(id: String) {
        watchItems.removeIf { it.id == id }
        watchValues.remove(id)
        pushWatchTargets()
        persistWatches()
    }

    fun setWatchAutoRefresh(id: String, value: Boolean) {
        watchItems.firstOrNull { it.id == id }?.let { item ->
            item.autoRefresh = value
            pushWatchTargets()
            persistWatches()
        }
    }

    fun clearWatches() {
        watchItems.clear()
        watchValues.clear()
        synchronized(dynamicWatchTargets) {
            dynamicWatchTargets.clear()
        }
        pushWatchTargets()
        persistWatches()
    }

    fun watchValue(id: String): ByteArray? = watchValues[id]

    fun addWatchItemDirectly(item: WatchItem) {
        val dup = watchItems.firstOrNull { it.expr.equals(item.expr, ignoreCase = true) }
        if (dup != null) {
            notify("变量「${dup.expr}」已在实时变量监视列表中", com.intellij.notification.NotificationType.INFORMATION)
            return
        }
        watchItems.add(item)
        pushWatchTargets()
        persistWatches()
    }

    /** ELF 重新加载后按表达式重解析全部监视项。 */
    private fun reResolveWatches() {
        for (item in watchItems) {
            runCatching { resolveWatchBlocking(item.expr) }.getOrNull()?.let { r ->
                item.address = r.address
                item.size = r.size
                item.encoding = r.encoding
                item.typeName = r.typeName
                item.node = r.node
            }
        }
        // 就地改写后必须发布：volatile 递增同时提供 happens-before（EDT 可见新布局）
        // 与重建触发（ELF 重载后变量数不变时 elfVariableCount 判据会漏）
        watchStructureRevision++
        pushWatchTargets()
    }

    /** 从已加载的 ELF 全局变量树中递归检索成员/数组元素（避免无谓 IPC 且全面支持嵌套结构体与数组）。 */
    fun findNodeInElf(expr: String): SymbolNode? {
        val tokens = WatchExpressionParser.splitMemberPath(expr)
        if (tokens.isEmpty()) return null
        val rootName = tokens[0]
        var current = elfVariables.firstOrNull { it.name == rootName } ?: return null
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            val next = current.members.firstOrNull {
                it.name == token ||
                    (token.startsWith("[") && token.endsWith("]") && it.name == token.removeSurrounding("[", "]")) ||
                    (!token.startsWith("[") && it.name == "[$token]")
            }
            if (next == null) return null
            current = next
        }
        return current
    }

    /** 动态解析指针变量当前在目标机中指向的基地址（优先从当前监视值缓存读取，否则若引擎在线则现场读内存）。 */
    fun resolvePointerAddress(rootNode: SymbolNode): Long? {
        val ptrItem = watchItems.firstOrNull { it.expr == rootNode.name }
        if (ptrItem != null) {
            val bytes = watchValue(ptrItem.id)
            if (bytes != null && bytes.size >= 4) {
                val addr = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (addr >= 0x1000L) return addr
            }
        }
        val client = clientRef.get()
        if (client != null && rootNode.address >= 0x1000L && engineState in listOf("running", "halted")) {
            val resp = runCatching {
                client.requestSync(
                    "read_mem",
                    JsonObject().apply {
                        addProperty("addr", rootNode.address)
                        addProperty("size", 4)
                    },
                    3000,
                )
            }.getOrNull()
            if (resp?.get("ok")?.asBoolean == true) {
                val res = resp.get("result")
                if (res != null && res.isJsonArray) {
                    val arr = res.asJsonArray
                    if (arr.size() >= 4) {
                        val bytes = ByteArray(4) { arr[it].asByte }
                        val addr = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (addr >= 0x1000L) return addr
                    }
                }
            }
        }
        return null
    }

    private fun resolveWatchBlocking(expr: String): WatchItem {
        val parsed = WatchExpressionParser.parse(expr)
            ?: throw IllegalArgumentException("无法解析表达式 '$expr'")
        val client = clientRef.get()
        return when (parsed.kind) {
            WatchExpressionParser.Kind.MEMBER_CHAIN -> {
                val foundInElf = findNodeInElf(expr)
                val node = if (foundInElf != null) {
                    foundInElf
                } else {
                    val normalized = WatchExpressionParser.normalizeForElf(expr)
                    val resp = client?.requestSync("elf_resolve", JsonObject().apply { addProperty("expr", normalized) }, 15_000)
                        ?: throw IllegalStateException("agent 未启动")
                    if (resp.get("ok")?.asBoolean != true) throw IllegalStateException(resp.get("error")?.asString ?: "解析失败")
                    resp.get("result")?.takeIf { !it.isJsonNull }?.let { gson.fromJson(it, SymbolNode::class.java) }
                        ?: throw IllegalArgumentException("未找到符号 '$expr'（ELF 是否已加载？）")
                }
                var effectiveAddress = node.address
                if (node.address < 0x1000L) {
                    val tokens = WatchExpressionParser.splitMemberPath(expr)
                    if (tokens.isNotEmpty()) {
                        val rootName = tokens[0]
                        val rootNode = elfVariables.firstOrNull { it.name == rootName }
                        if (rootNode != null && rootNode.isPointer) {
                            val ptrAddr = resolvePointerAddress(rootNode)
                            if (ptrAddr != null && ptrAddr >= 0x1000L) {
                                effectiveAddress = ptrAddr + node.address
                            }
                        }
                    }
                }
                WatchItem(
                    id = WatchValueFormatter.nextWatchId(),
                    expr = expr,
                    address = effectiveAddress,
                    size = if (node.size > 0) node.size else 4,
                    encoding = node.encoding,
                    typeName = node.typeName.ifEmpty { node.name },
                    node = node,
                )
            }
            else -> {
                var size = parsed.size ?: 4
                var encoding = encodingOf(ValueFormat.valueOf(parsed.format ?: "U32"))
                var typeName = parsed.typeName ?: "uint32_t"
                if (parsed.needsTypeLookup || parsed.format == null) {
                    // 裸地址：用 ELF 反查成员叶子类型
                    runCatching {
                        val resp = client?.requestSync(
                            "elf_type_at_addr",
                            JsonObject().apply { addProperty("addr", parsed.address!!) },
                            10_000,
                        )
                        if (resp?.get("ok")?.asBoolean == true) {
                            val o = resp.get("result")?.takeIf { !it.isJsonNull }?.asJsonObject
                            if (o != null) {
                                typeName = o.get("typeName")?.asString ?: typeName
                                size = o.get("size")?.asInt ?: size
                                encoding = o.get("encoding")?.asString ?: encoding
                            }
                        }
                    }
                }
                val bareNode = SymbolNode(
                    name = expr,
                    typeName = typeName,
                    address = parsed.address!!,
                    size = size,
                    encoding = encoding,
                )
                WatchItem(
                    id = WatchValueFormatter.nextWatchId(),
                    expr = expr,
                    address = parsed.address,
                    size = size,
                    encoding = encoding,
                    typeName = typeName,
                    node = bareNode,
                )
            }
        }
    }

    // ================= 示波通道 =================

    fun addScopeVariable(name: String, address: Long, size: Int, encoding: String, fixedAddress: Boolean = false): ScopeVariable? {
        if (address < 0x1000L) return null
        val v = ScopeVariable(
            name = name,
            address = address,
            size = size,
            format = ValueFormat.fromEncoding(encoding, size),
            colorIndex = colorSeq.getAndIncrement(),
            visible = true,
            expression = ScopeTargetResolver.binding(name, address, fixedAddress = fixedAddress),
        )
        synchronized(scopeVariables) {
            if (scopeVariables.any { it.address == address }) return null
            scopeVariables.add(v)
        }
        persistScope()
        pushScopeTargets()
        return v
    }

    fun removeScopeVariable(address: Long) {
        synchronized(scopeVariables) {
            scopeVariables.removeIf { it.address == address }
            synchronized(bufLock) {
                scopeSeries.remove(address)
                scopeVersion++
            }
        }
        persistScope()
        pushScopeTargets()
    }

    fun clearScopeVariables() {
        synchronized(scopeVariables) {
            scopeVariables.clear()
            synchronized(bufLock) {
                scopeSeries.clear()
                scopeVersion++
            }
        }
        persistScope()
        pushScopeTargets()
    }

    fun setScopeVariableVisible(address: Long, visible: Boolean) {
        scopeVariables.firstOrNull { it.address == address }?.visible = visible
        persistScope()
    }

    fun setScopeVariableFormat(address: Long, format: ValueFormat) {
        synchronized(scopeVariables) {
            val idx = scopeVariables.indexOfFirst { it.address == address }
            if (idx < 0) return
            scopeVariables[idx] = scopeVariables[idx].copy(format = format)
            synchronized(bufLock) {
                scopeSeries.remove(address)
                scopeVersion++
            }
        }
        persistScope()
        pushScopeTargets()
    }

    fun setScopeVariableColor(address: Long, color: java.awt.Color) {
        scopeVariables.firstOrNull { it.address == address }?.let { v ->
            v.customColor = color
            persistScope()
        }
    }

    /** 当前下发的示波采样频率（带宽预警用） */
    @Volatile var currentScopeFreqHz: Double = 100.0
        private set

    fun setScopeFreq(freq: Double) {
        currentScopeFreqHz = freq
        scopeBandwidthWarned.set(false)
        val client = clientRef.get() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                client.requestSync("set_scope_freq", JsonObject().apply { addProperty("freq", freq) }, 5000)
            }
        }
    }

    fun setWatchFreq(freq: Double) {
        val client = clientRef.get() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val resp = client.requestSync("set_watch_freq", JsonObject().apply { addProperty("freq", freq) }, 5000)
                val ok = resp.get("ok")?.asBoolean ?: false
                if (!ok) {
                    val err = resp.get("error")?.asString ?: "未知错误"
                    log.warn("set_watch_freq 失败: $err")
                } else {
                    log.info("set_watch_freq 成功设置采样频率为 ${freq}Hz")
                }
            }.onFailure {
                log.warn("set_watch_freq 通信异常", it)
            }
        }
    }

    fun clearScopeData() {
        synchronized(bufLock) {
            scopeSeries.clear()
            timeOffsetSec = 0.0
            lastRawTimeSec = -1.0
            isNewEngineSession = true
            lastEmittedTimestampNanos = 0L
            scopeVersion++
        }
        scopeSampleCounter.set(0)
        scopeErrorCounter.set(0)
        scopeDroppedCounter.set(0)
        scopeGapCounter.set(0)
    }

    /** 快照：地址 → 序列（画布/CSV 用）。带版本缓存，避免高频重复克隆全量序列引发 GC 与 EDT 卡顿。 */
    fun scopeSnapshot(): Map<Long, List<ScopeSample>> = synchronized(bufLock) {
        if (scopeVersion != cachedSnapshotVersion) {
            val copy = LinkedHashMap<Long, List<ScopeSample>>(scopeSeries.size)
            for ((k, v) in scopeSeries) copy[k] = ArrayList(v)
            cachedSnapshot = copy
            cachedSnapshotVersion = scopeVersion
        }
        cachedSnapshot
    }

    /** 单通道快照：只克隆该通道序列。轻量查询（如"自适应通道范围"）专用，
     *  避免 scopeSnapshot() 在版本变化时对全通道做整份克隆。 */
    fun scopeSeriesSnapshot(addr: Long): List<ScopeSample>? = synchronized(bufLock) {
        scopeSeries[addr]?.let { ArrayList(it) }
    }

    /** 获取各通道最新有效末值（O(1) 快速查询，供表格刷新，绝不克隆全量历史数据）。 */
    fun scopeLastValues(): Map<Long, Float> = synchronized(bufLock) {
        val map = LinkedHashMap<Long, Float>(scopeSeries.size)
        for ((addr, deque) in scopeSeries) {
            val it = deque.descendingIterator()
            while (it.hasNext()) {
                val s = it.next()
                if (!s.value.isNaN()) {
                    map[addr] = s.value
                    break
                }
            }
        }
        map
    }

    fun scopeSampleCount(): Long = scopeSampleCounter.get()
    fun scopeErrorCount(): Long = scopeErrorCounter.get()
    fun scopeDroppedCount(): Long = scopeDroppedCounter.get()
    fun scopeGapCount(): Long = scopeGapCounter.get()

    private fun scopeBufferSpanSecInternal(): Double {
        var minNanos = Long.MAX_VALUE
        var maxNanos = Long.MIN_VALUE
        for (deque in scopeSeries.values) {
            if (deque.isNotEmpty()) {
                val first = deque.first().timestampNanos
                val last = deque.last().timestampNanos
                if (first < minNanos) minNanos = first
                if (last > maxNanos) maxNanos = last
            }
        }
        return if (maxNanos > minNanos && minNanos != Long.MAX_VALUE) (maxNanos - minNanos) / 1e9 else 0.0
    }

    /** 缓冲覆盖的时间跨度（秒）。 */
    fun scopeBufferSpanSec(): Double = synchronized(bufLock) {
        scopeBufferSpanSecInternal()
    }

    /** 缓冲口径采样率（点数 - 1）/ 跨度（秒）。 */
    fun scopeBufferRateHz(): Double = synchronized(bufLock) {
        val maxCount = scopeSeries.values.maxOfOrNull { it.size } ?: 0
        val span = scopeBufferSpanSecInternal()
        if (span > 0.0 && maxCount > 1) (maxCount - 1) / span else 0.0
    }

    /** 带宽预检：(fits, bytesPerSecond)。 */
    fun checkBandwidth(freq: Double): CompletableFuture<Pair<Boolean, Long>> {
        val future = CompletableFuture<Pair<Boolean, Long>>()
        val targets = scopeVariables.map { listOf(it.address, it.size.toLong()) }
        val client = clientRef.get()
        if (targets.isEmpty()) {
            future.complete(true to 0L)
            return future
        }
        if (client == null) {
            future.completeExceptionally(IllegalStateException("agent 未启动"))
            return future
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val arr = JsonArray()
                targets.forEach { t ->
                    val pair = JsonArray()
                    pair.add(t[0])
                    pair.add(t[1])
                    arr.add(pair)
                }
                val p = JsonObject()
                p.add("targets", arr)
                p.addProperty("freq", freq)
                val resp = client.requestSync("check_bandwidth", p, 5000)
                val ok = resp.get("ok")?.asBoolean ?: false
                if (!ok) {
                    val err = resp.get("error")?.asString ?: "check_bandwidth 失败"
                    throw IllegalStateException(err)
                }
                val o = resp.getAsJsonObject("result")
                val fits = o?.get("fits")?.asBoolean ?: false
                val bytesPerSecond = o?.get("bytesPerSecond")?.asLong ?: 0L
                future.complete(fits to bytesPerSecond)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun readMem(addr: Long, size: Int): CompletableFuture<ByteArray> {
        if (addr < 0x1000L) {
            return CompletableFuture.completedFuture(ByteArray(0))
        }
        val future = CompletableFuture<ByteArray>()
        val client = clientRef.get()
        if (client == null) {
            future.completeExceptionally(IllegalStateException("agent 未启动"))
            return future
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val p = JsonObject()
                p.addProperty("addr", addr)
                p.addProperty("size", size)
                val resp = client.requestSync("read_mem", p, 5000)
                val ok = resp.get("ok")?.asBoolean ?: false
                if (!ok) {
                    val err = resp.get("error")?.asString ?: "read_mem 失败"
                    throw IllegalStateException(err)
                }
                val res = resp.get("result")
                val bytes = if (res != null && res.isJsonArray) {
                    val arr = res.asJsonArray
                    ByteArray(arr.size()) { arr[it].asByte }
                } else {
                    ByteArray(0)
                }
                future.complete(bytes)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    fun writeMem(addr: Long, data: ByteArray): CompletableFuture<Void> {
        if (addr < 0x1000L) {
            return CompletableFuture.failedFuture(IllegalArgumentException("拒绝写入非法低地址: 0x%08X".format(Locale.ROOT, addr)))
        }
        val future = CompletableFuture<Void>()
        val client = clientRef.get()
        if (client == null) {
            future.completeExceptionally(IllegalStateException("agent 未启动"))
            return future
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val p = JsonObject()
                p.addProperty("addr", addr)
                val arr = JsonArray()
                data.forEach { arr.add(it.toInt() and 0xFF) }
                p.add("data", arr)
                val resp = client.requestSync("write_mem", p, 5000)
                val ok = resp.get("ok")?.asBoolean ?: false
                if (!ok) {
                    val err = resp.get("error")?.asString ?: "write_mem 失败"
                    throw IllegalStateException(err)
                }
                future.complete(null)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    // ================= 目标推送（防抖合并，符号树批量添加只产生一次请求） =================

    fun pushWatchTargets() {
        if (!watchPushPending.compareAndSet(false, true)) return
        pushExecutor.schedule({
            watchPushPending.set(false)
            doPushWatchTargets()
        }, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** 立即推送监视目标（无需防抖等待，适用于动态指针目标展开与极速响应）。 */
    fun pushWatchTargetsImmediate() {
        watchPushPending.set(false)
        doPushWatchTargets()
    }

    private fun doPushWatchTargets() {
        val client = clientRef.get() ?: return
        val arr = JsonArray()
        // 1. 顶层监视项（防御非法低地址）
        watchItems.forEach { item ->
            if (item.address >= 0x1000L) {
                val o = JsonObject()
                o.addProperty("id", item.id)
                o.addProperty("addr", item.address)
                o.addProperty("size", item.size.coerceIn(1, 4096))
                o.addProperty("autoRefresh", item.autoRefresh)
                arr.add(o)
            }
        }
        // 2. 展开的动态指针目标（防御非法低地址）
        synchronized(dynamicWatchTargets) {
            dynamicWatchTargets.values.forEach { dyn ->
                if (dyn.address >= 0x1000L) {
                    val o = JsonObject()
                    o.addProperty("id", dyn.id)
                    o.addProperty("addr", dyn.address)
                    o.addProperty("size", dyn.size.coerceIn(1, 4096))
                    o.addProperty("autoRefresh", dyn.autoRefresh)
                    arr.add(o)
                }
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                client.requestSync("set_watch_targets", JsonObject().apply { add("targets", arr) }, 5000)
            }.onFailure { log.warn("set_watch_targets 失败", it) }
        }
        val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
        if (isDebuggerPaused && settings.pausePollingOnBreakpoint) {
            ApplicationManager.getApplication().executeOnPooledThread {
                sampleWatchOnceOnPause()
            }
        }
    }

    fun pushScopeTargets() {
        if (!scopePushPending.compareAndSet(false, true)) return
        pushExecutor.schedule({
            scopePushPending.set(false)
            doPushScopeTargets()
        }, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun doPushScopeTargets() {
        val client = clientRef.get() ?: return
        reResolveScopeVariables()
        val isDebuggerPaused = org.embedded.monitor.cmake.OpenOcdConfigReader.isAnySessionPaused(project)
        if (isDebuggerPaused && settings.pausePollingOnBreakpoint) {
            // 目标正处于断点暂停态：挂起示波器高频轮询（避免打扰 GDB 单步与上下文恢复）
            return
        }
        val arr = JsonArray()
        scopeVariables.forEach { v ->
            if (v.resolved && v.address >= 0x1000L) {
                val o = JsonObject()
                o.addProperty("addr", v.address)
                o.addProperty("size", v.size)
                arr.add(o)
            }
        }
        // pushExecutor 串行发布，防止多个 pooled 请求把旧地址覆盖回新地址。
        runCatching {
            client.requestSync("set_scope_targets", JsonObject().apply { add("targets", arr) }, 5000)
        }.onFailure { log.warn("set_scope_targets 失败", it) }
        checkScopeBandwidth(arr)
    }

    /** 每次 ELF 重载、重连或恢复采样时，以当前符号和现场指针重新定位。 */
    private fun reResolveScopeVariables() = synchronized(scopeResolveLock) {
        if (!elfLoaded) return@synchronized
        val variables = elfVariables.toList()
        val client = clientRef.get()
        val pointerValues = HashMap<Long, Long?>()
        var changed = false
        for (old in scopeVariables.toList()) {
            val expression = old.expression ?: continue
            val node = ScopeTargetResolver.resolve(expression, variables) { address ->
                pointerValues.getOrPut(address) {
                    if (client == null || engineState !in listOf("running", "halted")) null else {
                        val resp = runCatching {
                            client.requestSync("read_mem", JsonObject().apply {
                                addProperty("addr", address)
                                addProperty("size", 4)
                            }, 3000)
                        }.getOrNull()
                        val bytes = resp?.takeIf { it.get("ok")?.asBoolean == true }
                            ?.get("result")?.takeIf { it.isJsonArray }?.asJsonArray
                        if (bytes == null || bytes.size() != 4) null else {
                            (0..3).fold(0L) { value, i -> value or ((bytes[i].asInt.toLong() and 0xFF) shl (8 * i)) }
                        }
                    }
                }
            }
            synchronized(scopeVariables) channel@{
                val idx = scopeVariables.indexOfFirst { it === old }
                if (idx < 0) return@channel
                val relocated = node != null && (old.address != node.address || old.size != node.size)
                if (node == null || relocated) {
                    synchronized(bufLock) {
                        scopeSeries.remove(old.address)
                        if (node != null) scopeSeries.remove(node.address)
                        scopeVersion++
                    }
                }
                if (node == null) {
                    if (old.resolved) logLine("示波通道 ${old.name} 无法定位，已暂停该通道")
                    old.resolved = false
                } else {
                    scopeVariables[idx] = old.copy(address = node.address, size = node.size, resolved = true)
                    if (relocated) {
                        changed = true
                        logLine("示波通道 ${old.name} 已重新定位到 0x${node.address.toString(16)}")
                    }
                }
            }
        }
        if (changed) persistScope()
    }

    /** 带宽预警标志：目标/频率变化时重置，同类告警只提示一次 */
    private val scopeBandwidthWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 示波速率可行性预警：连接目标/频率后询问 agent 的 check_bandwidth，
     * 超出链路带宽时告知用户实际采样率达不到设定值（UI 已显示 actualRateHz，
     * 此处补一个主动提示——之前用户设 10000Hz 实测只有 ~2700Hz 且无任何预警）。
     */
    private fun checkScopeBandwidth(arr: JsonArray) {
        val client = clientRef.get() ?: return
        if (arr.size() == 0) {
            scopeBandwidthWarned.set(false)
            return
        }
        val freq = currentScopeFreqHz
        val targets = JsonArray().apply {
            arr.forEach { target ->
                add(JsonArray().apply {
                    add(target.asJsonObject.get("addr"))
                    add(target.asJsonObject.get("size"))
                })
            }
        }
        runCatching {
            val resp = client.requestSync(
                "check_bandwidth",
                JsonObject().apply {
                    add("targets", targets)
                    addProperty("freq", freq)
                },
                5000,
            )
            val o = resp.get("result")?.takeIf { !it.isJsonNull }?.asJsonObject
            val fits = o?.get("fits")?.asBoolean
            if (fits == false && scopeBandwidthWarned.compareAndSet(false, true)) {
                val bps = o.get("bytesPerSecond")?.asLong ?: -1L
                notify(
                    "示波器：当前 ${arr.size()} 通道 × ${freq.toInt()}Hz 超出探针链路带宽" +
                        (if (bps > 0) "（预计占用 ${"%.0f".format(bps / 1000.0)}KB/s）" else "") +
                        "，实际采样率将低于设定值。可降低采样频率或减少通道。",
                    com.intellij.notification.NotificationType.WARNING,
                )
            }
        }
    }

    // ================= 持久化同步 =================

    private fun persistWatches() {
        settings.update { s ->
            s.watchItems = watchItems.map {
                org.embedded.monitor.settings.PersistedWatchItem(it.expr, it.autoRefresh, it.evalOnly)
            }.toMutableList()
        }
    }

    private fun persistScope() {
        settings.update { s ->
            s.scopeChannels = scopeVariables.map {
                org.embedded.monitor.settings.PersistedScopeChannel(
                    it.name, it.address, it.size,
                    encodingOf(it.format), it.format.name, it.colorIndex, it.visible,
                    it.customColor?.rgb ?: -1,
                    it.expression ?: "", it.expression == null,
                )
            }.toMutableList()
        }
    }

    /** ELF 加载后恢复持久化的监视与示波配置。 */
    private fun restorePersisted() {
        val s = settings.state
        for (w in s.watchItems) {
            if (watchItems.none { it.expr == w.expr }) {
                if (w.evalOnly) {
                    // CLion 求值型监视：无地址，不走 ELF 解析，直接恢复
                    val item = WatchItem(
                        id = WatchValueFormatter.nextWatchId(),
                        expr = w.expr,
                        address = 0,
                        size = 0,
                        encoding = "eval",
                        typeName = "CLion eval",
                    ).apply {
                        evalOnly = true
                        autoRefresh = w.autoRefresh
                    }
                    watchItems.add(item)
                    continue
                }
                runCatching { resolveWatchBlocking(w.expr) }.getOrNull()?.let { item ->
                    item.autoRefresh = w.autoRefresh
                    watchItems.add(item)
                }
            }
        }
        pushWatchTargets()
        for (c in s.scopeChannels) {
            val expression = ScopeTargetResolver.binding(c.name, c.address, c.expression, c.fixedAddress)
            if (scopeVariables.none { it.name == c.name && it.expression == expression }) {
                val customColor = if (c.customColorRgb != -1) java.awt.Color(c.customColorRgb, true) else null
                scopeVariables.add(
                    ScopeVariable(
                        name = c.name,
                        address = c.address,
                        size = c.size,
                        format = runCatching { ValueFormat.valueOf(c.format) }.getOrDefault(ValueFormat.U32),
                        colorIndex = c.colorIndex,
                        visible = c.visible,
                        customColor = customColor,
                        expression = expression,
                        resolved = expression == null,
                    ),
                )
            }
        }
        pushScopeTargets()
    }

    fun elfVariableCount(): Int = elfVariables.size

    fun elfVariables(): List<SymbolNode> = elfVariables.toList()



    override fun dispose() {
        disposed = true
        closeAgent()
        // 监督/推送调度器若不关闭，项目关闭后仍会周期性访问已 dispose 的 project
        supervisor.shutdownNow()
        pushExecutor.shutdownNow()
    }
}
