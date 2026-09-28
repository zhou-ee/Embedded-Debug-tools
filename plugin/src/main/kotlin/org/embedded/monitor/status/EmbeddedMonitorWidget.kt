package org.embedded.monitor.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import org.embedded.monitor.agent.AgentService
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * 状态栏 Widget（参考 JetBrains Serial Monitor 的模式）：
 * 常驻显示监视状态，点击打开实时变量监视窗。无感：不用找工具窗就知道连接状态。
 */
class EmbeddedMonitorWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "EmbeddedMonitorWidget"
    }

    private var statusBar: StatusBar? = null
    private val timer = javax.swing.Timer(1000) { update() }
    private var lastText: String? = null

    init {
        // 生命周期：工厂 disposeWidget → dispose() 停表。widget 不进 Disposer 树，
        // 在这里挂 Disposer.register(this) 永远不会触发（会导致 project 泄漏）。
        timer.start()
    }

    private fun update() {
        val t = text()
        if (t != lastText) {
            lastText = t
            statusBar?.updateWidget(ID)
        }
    }

    private fun text(): String = when (AgentService.getInstance(project).engineState) {
        "running" -> "EM [ON] 监视中"
        "halted" -> "EM [PAUSE] 已暂停"
        "connecting" -> "EM [...] 连接中"
        else -> "EM [OFF] 未连接"
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getTooltipText(): String {
        val s = AgentService.getInstance(project)
        return buildString {
            append("Embedded Monitor\n")
            append(when (s.engineState) {
                "running" -> "监视中 | ${s.engineDescription}"
                "halted" -> "目标已暂停"
                "connecting" -> "连接中..."
                else -> "未连接"
            })
            s.elfPath?.let { append("\nELF: ").append(java.io.File(it).name) }
            append("\n点击打开实时变量监视")
        }
    }

    override fun getText(): String = text()

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    /** 点击：优先激活当前调试会话中的「实时变量监视」标签页；若不在调试中，则打开右侧工具窗口。 */
    fun click(): Runnable? = Runnable {
        val currentSession = com.intellij.xdebugger.XDebuggerManager.getInstance(project)?.currentSession
        val ui = currentSession?.ui
        val content = ui?.findContent(org.embedded.monitor.agent.AgentService.LIVE_WATCH_DEBUG_CONTENT_ID)
            ?: ui?.findContent(org.embedded.monitor.agent.AgentService.LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID)
        if (ui != null && content != null) {
            val twDebug = ToolWindowManager.getInstance(project).getToolWindow(com.intellij.openapi.wm.ToolWindowId.DEBUG)
            twDebug?.activate {
                ui.selectAndFocus(content, true, true)
            } ?: run {
                ui.selectAndFocus(content, true, true)
            }
            return@Runnable
        }
        val tw = ToolWindowManager.getInstance(project).getToolWindow("EmbeddedLiveWatch") ?: return@Runnable
        tw.activate(null)
    }

    override fun getClickConsumer(): Consumer<java.awt.event.MouseEvent> = Consumer { click()?.run() }

    override fun dispose() {
        timer.stop()
    }
}
