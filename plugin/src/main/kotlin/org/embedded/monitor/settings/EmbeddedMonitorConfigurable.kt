package org.embedded.monitor.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.cmake.OpenOcdConfigReader
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/** Settings → Tools → Embedded Monitor。 */
class EmbeddedMonitorConfigurable(private val project: Project) : Configurable {

    private val settings get() = EmbeddedMonitorSettings.getInstance(project)

    private val agentPathField = JBTextField(360)
    private val backendCombo = JComboBox(DefaultComboBoxModel(arrayOf("openocd", "probe-rs", "sim")))
    private val chipTargetField = JBTextField(200)
    private val openocdPathField = JBTextField(280)
    private val scriptsDirField = JBTextField(280)
    private val tclPortSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(6666, 1, 65535, 1))
    private val attachOnlyCheck = JBCheckBox("attach-only：仅连接已运行的 OpenOCD（与 CLion 调试共存）", true)
    private val autoStartCheck = JBCheckBox("调试会话启动时自动开始监视", true)
    private val autoSwitchCheck = JBCheckBox("调试中自动切换为 OpenOCD attach-only（防止 probe-rs 端口冲突断联）", true)
    private val pauseOnBpCheck = JBCheckBox("命中断点暂停时挂起高频轮询（避免干扰 GDB 单步/恢复，仅单次快照刷新）", true)
    private val speedSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(4_000_000, 100_000, 50_000_000, 100_000))
    private val freqSpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(100.0, 1.0, 5000.0, 10.0))
    private val watchFreqCombo = com.intellij.openapi.ui.ComboBox(EmbeddedMonitorSettings.VALID_WATCH_FREQS.map { "$it Hz" }.toTypedArray())
    private val elfOverrideField = JBTextField(360)
    private val elfAutoCheck = JBCheckBox("自动从 CLion 构建路径探测 ELF（CMake File API / 构建目录扫描）", true)
    private val detectedLabel = JBLabel("")

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Embedded Monitor（实时变量监视与示波器）"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Agent 可执行文件：", agentPathField)
            .addComponentToRightColumn(JBLabel("留空则自动探测（环境变量 EMBEDDED_CLION_AGENT / 开发目录 / PATH）"))
            .addLabeledComponent("采样后端：", backendCombo)
            .addLabeledComponent("probe-rs 芯片名：", chipTargetField)
            .addComponentToRightColumn(JBLabel("空 = 自动识别（仅 probe-rs 后端需要）"))
            .addLabeledComponent("OpenOCD 可执行文件：", openocdPathField)
            .addLabeledComponent("OpenOCD scripts 目录：", scriptsDirField)
            .addLabeledComponent("OpenOCD Tcl RPC 端口：", tclPortSpinner)
            .addComponentToRightColumn(attachOnlyCheck)
            .addComponentToRightColumn(autoStartCheck)
            .addComponentToRightColumn(autoSwitchCheck)
            .addComponentToRightColumn(pauseOnBpCheck)
            .addLabeledComponent("SWD 时钟 (Hz)：", speedSpinner)
            .addLabeledComponent("示波采样率 (Hz)：", freqSpinner)
            .addLabeledComponent("实时变量刷新率：", watchFreqCombo)
            .addSeparator()
            .addLabeledComponent("ELF 路径覆盖：", elfOverrideField)
            .addComponentToRightColumn(elfAutoCheck)
            .addComponent(detectedLabel)
            .addComponent(JBLabel("提示：attach-only + OpenOCD 后端时，先在 CLion 启动「OpenOCD 下载并运行」，"))
            .addComponent(JBLabel("本插件经 OpenOCD 的 Tcl RPC(6666) 读内存，不抢占调试器。断点/寄存器用 CLion 原生调试。"))
            .addComponent(JBLabel("（注意：CLion 默认禁用了 Tcl 端口，请务必在板级 .cfg 文件末尾添加一行「tcl_port 6666」）。"))
            .panel
            applyFromSettings()

        // 扫描构建目录/CMake File API 是磁盘 IO，不能在 EDT 同步做（设置页卡顿）
        refreshDetectedLabelAsync()
        backendCombo.addActionListener {
            val isOcd = backendCombo.selectedItem == "openocd"
            attachOnlyCheck.isEnabled = isOcd
            tclPortSpinner.isEnabled = isOcd
            chipTargetField.isEnabled = backendCombo.selectedItem == "probe-rs"
        }
        return panel ?: JBLabel("设置初始化失败")
    }

    private fun detectCandidatesText(): String {
        val cands = AgentService.getInstance(project).listElfCandidates()
        return if (cands.isEmpty()) "当前工程未探测到 ELF（构建后刷新）"
        else "探测到 ${cands.size} 个 ELF：${cands.take(3).joinToString { it.file.name }}${if (cands.size > 3) " …" else ""}"
    }

    private fun refreshDetectedLabelAsync() {
        detectedLabel.text = "正在扫描 ELF …"
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching { detectCandidatesText() }.getOrElse { "ELF 探测失败: ${it.message}" }
            com.intellij.util.ui.UIUtil.invokeLaterIfNeeded { detectedLabel.text = text }
        }
    }

    private fun applyFromSettings() {
        val s = settings.state
        agentPathField.text = s.agentPath
        backendCombo.selectedItem = s.backend
        chipTargetField.text = s.chipTarget
        openocdPathField.text = s.openocdPath
        scriptsDirField.text = s.scriptsDir
        tclPortSpinner.value = s.tclPort
        attachOnlyCheck.isSelected = s.attachOnly
        autoStartCheck.isSelected = s.autoStartWithDebug
        autoSwitchCheck.isSelected = s.autoSwitchBackendOnDebug
        pauseOnBpCheck.isSelected = s.pausePollingOnBreakpoint
        speedSpinner.value = s.speedHz
        // 历史配置可能存有 >5000 的值（旧示波面板上限 50000，引擎钳 5000）：钳回范围内
        freqSpinner.value = s.scopeFreqHz.coerceIn(1.0, 5000.0)
        watchFreqCombo.selectedItem = "${EmbeddedMonitorSettings.snapWatchFreq(s.watchRefreshFreq)} Hz"
        elfOverrideField.text = s.elfOverride
        elfAutoCheck.isSelected = s.elfAuto
    }

    override fun isModified(): Boolean {
        val s = settings.state
        val currentSelectedWatchFreq = watchFreqCombo.selectedItem?.toString()?.substringBefore(" ")?.toIntOrNull() ?: 5
        return agentPathField.text != s.agentPath ||
            backendCombo.selectedItem != s.backend ||
            chipTargetField.text != s.chipTarget ||
            openocdPathField.text != s.openocdPath ||
            scriptsDirField.text != s.scriptsDir ||
            (tclPortSpinner.value as Int) != s.tclPort ||
            attachOnlyCheck.isSelected != s.attachOnly ||
            autoStartCheck.isSelected != s.autoStartWithDebug ||
            autoSwitchCheck.isSelected != s.autoSwitchBackendOnDebug ||
            pauseOnBpCheck.isSelected != s.pausePollingOnBreakpoint ||
            (speedSpinner.value as Int) != s.speedHz ||
            (freqSpinner.value as Double) != s.scopeFreqHz ||
            // 两侧都过 snapWatchFreq 归一，避免存量非法值导致"应用"按钮永久点亮
            currentSelectedWatchFreq != EmbeddedMonitorSettings.snapWatchFreq(s.watchRefreshFreq) ||
            elfOverrideField.text != s.elfOverride ||
            elfAutoCheck.isSelected != s.elfAuto
    }

    override fun apply() {
        val selectedWatchFreq = watchFreqCombo.selectedItem?.toString()?.substringBefore(" ")?.toIntOrNull() ?: 5
        val snappedWatchFreq = EmbeddedMonitorSettings.snapWatchFreq(selectedWatchFreq)
        settings.update { s ->
            s.agentPath = agentPathField.text.trim()
            s.backend = backendCombo.selectedItem as String
            s.chipTarget = chipTargetField.text.trim()
            s.openocdPath = openocdPathField.text.trim()
            s.scriptsDir = scriptsDirField.text.trim()
            s.tclPort = tclPortSpinner.value as Int
            s.attachOnly = attachOnlyCheck.isSelected
            s.autoStartWithDebug = autoStartCheck.isSelected
            s.autoSwitchBackendOnDebug = autoSwitchCheck.isSelected
            s.pausePollingOnBreakpoint = pauseOnBpCheck.isSelected
            s.speedHz = speedSpinner.value as Int
            s.scopeFreqHz = freqSpinner.value as Double
            s.watchRefreshFreq = snappedWatchFreq
            s.elfOverride = elfOverrideField.text.trim()
            s.elfAuto = elfAutoCheck.isSelected
        }
        val svc = runCatching { org.embedded.monitor.agent.AgentService.getInstance(project) }.getOrNull()
        svc?.setWatchFreq(snappedWatchFreq.toDouble())
        svc?.setScopeFreq(freqSpinner.value as Double)
    }

    override fun reset() {
        applyFromSettings()
        refreshDetectedLabelAsync()
    }

    override fun disposeUIResources() {
        // 设置页关闭后释放全部 Swing 组件引用，避免持有 spinner/面板图
        panel = null
    }

    /** 从 CLion 的 OpenOCD 运行配置预填充 board-config。 */
    fun suggestFromRunConfig() {
        val cfg = OpenOcdConfigReader.readAll(project).firstOrNull() ?: return
        if (cfg.boardConfig != null && openocdPathField.text.isBlank()) {
            // board config 是 .cfg 文件路径，不是 openocd 可执行文件；这里只展示提示
            detectedLabel.text = "检测到 OpenOCD 运行配置「${cfg.name}」：board=${cfg.boardConfig} gdb=${cfg.gdbPort}"
        }
    }
}
