package org.embedded.monitor.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/** 监视条目持久化（按表达式恢复）。 */
data class PersistedWatchItem(
    var expr: String = "",
    var autoRefresh: Boolean = true,
    /** CLion 求值型监视（无法解析为地址的 C 表达式，断点暂停时由 IDE 调试器求值） */
    var evalOnly: Boolean = false,
)

/** 示波通道持久化。 */
data class PersistedScopeChannel(
    var name: String = "",
    var address: Long = 0,
    var size: Int = 4,
    var encoding: String = "unsigned",
    var format: String = "U32",
    var colorIndex: Int = 0,
    var visible: Boolean = true,
    var customColorRgb: Int = -1,
)

/**
 * 工程/插件级设置。Storage 文件名沿用模板习惯（embeddedScope.xml → 独立文件名避免冲突）。
 */
@State(name = "EmbeddedMonitor", storages = [Storage("embeddedMonitor.xml")])
@Service(Service.Level.PROJECT)
class EmbeddedMonitorSettings : PersistentStateComponent<EmbeddedMonitorSettings.State> {

    data class State(
        /** agent 可执行文件路径；空 = 自动探测 */
        var agentPath: String = "",
        /** probe-rs / openocd / sim */
        var backend: String = "openocd",
        /** probe-rs 芯片名；空 = 自动探测 */
        var chipTarget: String = "",
        var openocdPath: String = "",
        var scriptsDir: String = "",
        var tclPort: Int = 6666,
        /** true = 只 attach 已运行的 OpenOCD（与 CLion 调试共存），不自行拉起 */
        var attachOnly: Boolean = true,
        /** 调试会话启动时自动开始监视（与调试器共存） */
        var autoStartWithDebug: Boolean = true,
        /** CLion 调试或 OpenOCD 运行中时，自动将后端切换为 openocd attach-only，杜绝 probe-rs 端口冲突 */
        var autoSwitchBackendOnDebug: Boolean = true,
        /** CLion 命中断点/目标暂停时，挂起示波器与实时总线轮询（防 GDB 冲突），仅执行单次快照刷新 */
        var pausePollingOnBreakpoint: Boolean = true,
        var speedHz: Int = 4_000_000,
        var scopeFreqHz: Double = 100.0,
        /** 手动指定 ELF；空 = 自动探测 */
        var elfOverride: String = "",
        var elfAuto: Boolean = true,
        /** SVD 文件路径；空 = 自动探测 */
        var svdPath: String = "",
        /** 寄存器自动刷新频率（Hz）：1, 2, 5, 10 */
        var registerRefreshFreq: Int = 2,
        /** 是否开启动态全局刷新（全部展开项） */
        var registerAutoRefreshAll: Boolean = false,
        /** 勾选了动态刷新的寄存器路径集合 */
        var registerAutoRefreshPaths: MutableSet<String> = mutableSetOf(),
        /** 实时变量监视刷新频率（Hz）：2 ~ 15，默认 5 */
        var watchRefreshFreq: Int = 5,
        var watchItems: MutableList<PersistedWatchItem> = mutableListOf(),
        var scopeChannels: MutableList<PersistedScopeChannel> = mutableListOf(),
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
    }

    val agentPath: String get() = state.agentPath
    val backend: String get() = state.backend
    val chipTarget: String get() = state.chipTarget
    val openocdPath: String get() = state.openocdPath
    val scriptsDir: String get() = state.scriptsDir
    val tclPort: Int get() = state.tclPort
    val attachOnly: Boolean get() = state.attachOnly
    val autoStartWithDebug: Boolean get() = state.autoStartWithDebug
    val autoSwitchBackendOnDebug: Boolean get() = state.autoSwitchBackendOnDebug
    val pausePollingOnBreakpoint: Boolean get() = state.pausePollingOnBreakpoint
    val speedHz: Int get() = state.speedHz
    val scopeFreqHz: Double get() = state.scopeFreqHz
    val elfOverride: String get() = state.elfOverride
    val elfAuto: Boolean get() = state.elfAuto
    val svdPath: String get() = state.svdPath
    val registerRefreshFreq: Int get() = state.registerRefreshFreq
    val registerAutoRefreshAll: Boolean get() = state.registerAutoRefreshAll
    val registerAutoRefreshPaths: MutableSet<String> get() = state.registerAutoRefreshPaths
    val watchRefreshFreq: Int get() = snapWatchFreq(state.watchRefreshFreq)
    val watchItems: MutableList<PersistedWatchItem> get() = state.watchItems
    val scopeChannels: MutableList<PersistedScopeChannel> get() = state.scopeChannels

    fun update(block: (State) -> Unit) {
        block(state)
    }

    companion object {
        val VALID_WATCH_FREQS = listOf(2, 5, 10, 15)

        @JvmStatic
        fun snapWatchFreq(freq: Int): Int {
            return VALID_WATCH_FREQS.minByOrNull { kotlin.math.abs(it - freq) } ?: 5
        }

        @JvmStatic
        fun getInstance(project: com.intellij.openapi.project.Project): EmbeddedMonitorSettings =
            project.getService(EmbeddedMonitorSettings::class.java)
    }
}
