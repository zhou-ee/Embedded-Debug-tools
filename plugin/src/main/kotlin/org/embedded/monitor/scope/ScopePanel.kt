package org.embedded.monitor.scope

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorChooser
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.core.ScopePalette
import org.embedded.monitor.core.ScopeSample
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ScopeWaveformPanel
import org.embedded.monitor.core.ValueFormat
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import kotlin.math.abs

class ScopeToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "示波器"
        val panel = ScopePanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, null, false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 示波器面板：
 * - 紧凑高信息密度布局，适合 1/4 屏幕空间；
 * - 顶部：极简操作工具栏（开始/停止/暂停/清空/跟随/自动量程/采样率/到达率/导出 CSV）；
 * - 主体：占绝大部分面积的示波器波形画布（ScopeWaveformPanel）；
 * - 底部：正下方极简通道芯片/标签栏（Channel Chips），支持单击显隐、点击×去除、右击改颜色/改格式/调节Y轴/查地址。
 */
class ScopePanel(private val project: Project) : JBPanel<ScopePanel>(BorderLayout()), Disposable {

    private val service = AgentService.getInstance(project)
    private val settings get() = org.embedded.monitor.settings.EmbeddedMonitorSettings.getInstance(project)

    // 顶部控制条（极简紧凑图标化，适配 1/4 屏幕空间）
    // 上限 5000 与引擎侧钳制（monitor SetScopeFreq clamp 1..5000）及设置页一致；
    // 引擎对更高请求会静默钳到 5000，UI 不应展示一个达不到的范围
    private val freqSpinner = JSpinner(SpinnerNumberModel(100.0, 1.0, 5000.0, 10.0))
    private val followButton = JToggleButton("", true)
    private val autoRangeButton = JToggleButton("", true)
    private val gridButton = JToggleButton("", true)
    private val pauseButton = JToggleButton("")
    private val startButton = JButton("")
    private val stopButton = JButton("")
    private val clearButton = JButton("")
    private val exportButton = JButton("")
    private val actualRateLabel = JBLabel("0 Hz")
    private val droppedLabel = JBLabel("")

    // 主体画布
    private val waveform = ScopeWaveformPanel()

    // 底部通道芯片栏（只占极少垂直空间，水平自适应滚动）
    private val chipsContainer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply {
        isOpaque = false
    }
    private val chipsScrollPane = JBScrollPane(
        chipsContainer,
        JBScrollPane.VERTICAL_SCROLLBAR_NEVER,
        JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    ).apply {
        border = null
        isOpaque = false
        viewport.isOpaque = false
        preferredSize = Dimension(100, JBUI.scale(32))
        minimumSize = Dimension(50, JBUI.scale(28))
    }

    // 状态栏
    private val statusLabel = JBLabel("就绪").apply {
        font = JBFont.label().deriveFont(10.5f)
        foreground = JBColor.GRAY
    }

    // 表格模型（保留用于底层数据同步及单元测试兼容性）
    private val tableModel = ScopeChannelTableModel(
        onVariableVisibleChange = { addr, vis -> service.setScopeVariableVisible(addr, vis) },
        onVariableFormatChange = { addr, fmt -> service.setScopeVariableFormat(addr, fmt) },
    )
    private val table = JBTable(tableModel)

    private val refreshTimer = Timer(33) { refreshWaveform() }
    private val metricsTimer = Timer(500) { refreshMetrics() }
    private val tableTimer = Timer(250) { refreshTable() }

    init {
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        val numEditor = JSpinner.NumberEditor(freqSpinner, "#")
        freqSpinner.editor = numEditor
        numEditor.textField.columns = 5
        // 历史配置可能存有 >5000 的值（旧版上限 50000、引擎静默钳 5000）：
        // 钳回模型范围内，避免 spinner 显示越界值后无法用步进按钮回调
        freqSpinner.value = settings.scopeFreqHz.coerceIn(1.0, 5000.0)

        add(buildControls(), BorderLayout.NORTH)
        add(waveform, BorderLayout.CENTER)
        add(buildBottomBar(), BorderLayout.SOUTH)

        waveform.onCursorChange = { hover ->
            tableModel.setCursorValues(if (hover == null) emptyMap() else hover.values.toMap())
        }
        waveform.onFollowChange = { follow ->
            followButton.isSelected = follow
        }
        waveform.onAutoRangeChange = { auto ->
            autoRangeButton.isSelected = auto
        }
        waveform.onRequestSetTimePerDiv = { promptSetTimePerDiv() }
        waveform.onRequestSetValuePerDiv = { promptSetValuePerDiv() }
        waveform.onRequestClearData = {
            service.clearScopeData()
            waveform.clear()
            actualRateLabel.text = "0 Hz"
            droppedLabel.text = ""
        }
        waveform.onRequestExportCsv = { exportCsv() }

        // 支持水平鼠标滚轮平滑滚动通道栏
        chipsScrollPane.addMouseWheelListener { e: MouseWheelEvent ->
            if (e.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                val hbar = chipsScrollPane.horizontalScrollBar
                if (hbar != null && hbar.isVisible) {
                    hbar.value += e.unitsToScroll * JBUI.scale(12)
                    e.consume()
                }
            }
        }

        // 按钮动作绑定
        startButton.addActionListener { startSampling() }
        stopButton.addActionListener { service.disconnectEngine() }
        pauseButton.addActionListener {
            waveform.setPaused(pauseButton.isSelected)
            service.isScopePaused = pauseButton.isSelected
            pauseButton.icon = if (pauseButton.isSelected) AllIcons.Actions.Resume else AllIcons.Actions.Pause
            pauseButton.toolTipText = if (pauseButton.isSelected) "继续采样更新" else "暂停采样更新"
        }
        clearButton.addActionListener {
            service.clearScopeData()
            waveform.clear()
            actualRateLabel.text = "0 Hz"
            droppedLabel.text = ""
        }
        followButton.addActionListener { waveform.setFollowLatest(followButton.isSelected) }
        autoRangeButton.addActionListener { waveform.setAutoRange(autoRangeButton.isSelected) }
        gridButton.addActionListener { waveform.showGrid = gridButton.isSelected }
        exportButton.addActionListener { exportCsv() }

        freqSpinner.addChangeListener {
            val f = (freqSpinner.value as Number).toDouble()
            settings.update { it.scopeFreqHz = f }
            service.setScopeFreq(f)
        }

        stopButton.isEnabled = false
        refreshTimer.start()
        metricsTimer.start()
        tableTimer.start()
        refreshChannelChips()
    }

    private fun buildControls(): JBPanel<JBPanel<*>> {
        val bar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2)))
        bar.border = BorderFactory.createEmptyBorder(1, 2, 2, 2)

        val btnDim = Dimension(JBUI.scale(24), JBUI.scale(22))

        startButton.icon = AllIcons.Actions.Execute
        startButton.toolTipText = "开始采样"
        startButton.preferredSize = btnDim

        stopButton.icon = AllIcons.Actions.Suspend
        stopButton.toolTipText = "停止采样"
        stopButton.preferredSize = btnDim

        pauseButton.icon = AllIcons.Actions.Pause
        pauseButton.toolTipText = "暂停/继续更新"
        pauseButton.preferredSize = btnDim

        clearButton.icon = AllIcons.Actions.GC
        clearButton.toolTipText = "清空波形数据"
        clearButton.preferredSize = btnDim

        followButton.icon = AllIcons.General.ArrowRight
        followButton.toolTipText = "X 轴跟随最新数据 (Follow Latest)"
        followButton.preferredSize = btnDim

        autoRangeButton.icon = AllIcons.Actions.MoveTo2
        autoRangeButton.toolTipText = "Y 轴自动量程 (Auto Range)"
        autoRangeButton.preferredSize = btnDim

        gridButton.icon = GridIcon(JBUI.scale(14))
        gridButton.toolTipText = "显示/隐藏网格 (Grid)"
        gridButton.preferredSize = btnDim

        freqSpinner.preferredSize = Dimension(JBUI.scale(85), JBUI.scale(22))
        freqSpinner.minimumSize = Dimension(JBUI.scale(75), JBUI.scale(22))
        freqSpinner.toolTipText = "采样频率（1-5000 Hz，引擎实际上限受单帧读内存耗时约束）"

        exportButton.icon = AllIcons.ToolbarDecorator.Export
        exportButton.toolTipText = "导出 CSV"
        exportButton.preferredSize = btnDim

        actualRateLabel.font = JBFont.label().deriveFont(10.5f)
        droppedLabel.font = JBFont.label().deriveFont(10.5f)

        bar.add(startButton)
        bar.add(stopButton)
        bar.add(pauseButton)
        bar.add(clearButton)
        bar.add(separator())
        bar.add(followButton)
        bar.add(autoRangeButton)
        bar.add(gridButton)
        bar.add(separator())
        bar.add(freqSpinner)
        bar.add(JBLabel("Hz").apply { font = JBFont.label().deriveFont(10.5f); foreground = JBColor.GRAY })
        bar.add(separator())
        bar.add(actualRateLabel)
        bar.add(droppedLabel)
        bar.add(separator())
        bar.add(exportButton)

        return bar
    }

    private fun separator(): javax.swing.JSeparator =
        javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(JBUI.scale(2), JBUI.scale(16))
            foreground = com.intellij.util.ui.JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        }

    private fun buildBottomBar(): JComponent {
        val bar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(1, 4, 2, 4),
            )
            background = JBColor.PanelBackground
        }
        bar.add(chipsScrollPane, BorderLayout.CENTER)
        bar.add(statusLabel, BorderLayout.SOUTH)
        return bar
    }

    /** 刷新底部通道芯片栏。 */
    private fun refreshChannelChips() {
        val vars = service.scopeVariables.toList()
        val lastValues = service.scopeLastValues()
        val currentAddrs = vars.map { it.address }
        val existingPanels = chipsContainer.components.filterIsInstance<ChannelChipPanel>()
        val existingAddrs = existingPanels.map { it.variable.address }

        if (currentAddrs != existingAddrs) {
            chipsContainer.removeAll()
            if (vars.isEmpty()) {
                val emptyLabel = JBLabel("暂无通道 · 点击 [+] 添加").apply {
                    foreground = JBColor.GRAY
                    font = JBFont.label().deriveFont(11f)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            if (e.button == MouseEvent.BUTTON1) promptAddChannel()
                        }
                    })
                }
                chipsContainer.add(emptyLabel)
            } else {
                for (v in vars) {
                    chipsContainer.add(ChannelChipPanel(v, lastValues[v.address]))
                }
            }
            chipsContainer.add(AddChannelChip())
            chipsContainer.revalidate()
            chipsContainer.repaint()
        } else {
            // 通道结构未变，就地更新状态与末值，彻底杜绝定时器频繁重建销毁 Swing 组件与焦点丢失
            for (comp in existingPanels) {
                val v = vars.firstOrNull { it.address == comp.variable.address } ?: continue
                comp.variable = v
                comp.lastValue = lastValues[v.address]
                comp.updateTooltip()
                comp.repaint()
            }
        }
    }

    /** 单个通道标签/芯片（Channel Chip）。 */
    private inner class ChannelChipPanel(
        var variable: ScopeVariable,
        var lastValue: Float?,
    ) : JPanel() {
        private var isHovered = false
        private var isCloseHovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            updateTooltip()

            val mouseListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    isHovered = false
                    isCloseHovered = false
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    val closeBoxX = width - JBUI.scale(18)
                    val inClose = e.x >= closeBoxX
                    if (isCloseHovered != inClose) {
                        isCloseHovered = inClose
                        repaint()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleMouse(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showChannelContextMenu(variable, e)
                    }
                }

                private fun handleMouse(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                        showChannelContextMenu(variable, e)
                        return
                    }
                    if (e.button == MouseEvent.BUTTON1) {
                        val closeBoxX = width - JBUI.scale(18)
                        if (e.x >= closeBoxX) {
                            // 点击右侧小 × 移除该变量
                            service.removeScopeVariable(variable.address)
                            refreshChannelChips()
                        } else {
                            // 点击变量标签快速切换显示/隐藏
                            val newVal = !variable.visible
                            service.setScopeVariableVisible(variable.address, newVal)
                            variable.visible = newVal
                            waveform.repaint()
                            updateTooltip()
                            repaint()
                        }
                    }
                }
            }
            addMouseListener(mouseListener)
            addMouseMotionListener(mouseListener)
        }

        fun updateTooltip() {
            val valStr = lastValue?.let { formatValue(it) } ?: "-"
            val visStr = if (variable.visible) "已显示" else "已隐藏"
            val addrStr = String.format(Locale.ROOT, "0x%08X", variable.address)
            toolTipText = "<html><b>${variable.name}</b> ($visStr)<br/>" +
                "地址: $addrStr (${variable.size}B, ${variable.format})<br/>" +
                "末值: $valStr<br/>" +
                "<hr/>" +
                "• 左键单击：切换显示/隐藏<br/>" +
                "• 右击：更改颜色 / 调节 Y 轴 / 数据格式<br/>" +
                "• 点击右侧 ×：移除该通道</html>"
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(JBFont.label().deriveFont(11f))
            val text = buildText()
            val textW = fm.stringWidth(text).coerceAtMost(JBUI.scale(160))
            val w = JBUI.scale(7 + 8 + 6 + 18) + textW
            val h = JBUI.scale(22)
            return Dimension(w, h)
        }

        private fun buildText(): String {
            val valStr = lastValue?.let { ": " + formatValue(it) } ?: ""
            return "${variable.name}$valStr"
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val w = width
                val h = height
                val arc = JBUI.scale(12)
                val isDark = UIUtil.isUnderDarcula()
                val color = ScopePalette.colorForVar(variable)

                // 背景
                val bg = when {
                    !variable.visible -> if (isDark) Color(0x23, 0x25, 0x28, 140) else Color(0xf0, 0xf2, 0xf5, 140)
                    isHovered -> if (isDark) Color(0x38, 0x3d, 0x45) else Color(0xe2, 0xe7, 0xf0)
                    else -> if (isDark) Color(0x2d, 0x31, 0x38) else Color(0xec, 0xf0, 0xf6)
                }
                g2.color = bg
                g2.fillRoundRect(0, 0, w, h, arc, arc)

                // 边框（隐藏时虚线灰显，悬停时高亮通道色）
                val borderColor = when {
                    isHovered -> color
                    !variable.visible -> if (isDark) Color(0x3a, 0x3f, 0x47) else Color(0xd8, 0xde, 0xe6)
                    else -> if (isDark) Color(0x45, 0x4b, 0x55) else Color(0xc8, 0xd0, 0xdc)
                }
                g2.color = borderColor
                if (!variable.visible) {
                    val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 2f), 0f)
                    g2.stroke = dashed
                } else {
                    g2.stroke = BasicStroke(1.2f)
                }
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)

                // 1. 颜色指示点 (Dot)
                val dotSize = JBUI.scale(8)
                val dotX = JBUI.scale(7)
                val dotY = (h - dotSize) / 2
                if (variable.visible) {
                    g2.color = color
                    g2.fillOval(dotX, dotY, dotSize, dotSize)
                } else {
                    g2.stroke = BasicStroke(1.2f)
                    g2.color = JBColor.GRAY
                    g2.drawOval(dotX, dotY, dotSize, dotSize)
                }

                // 2. 文本 (Name + Value) 带防溢出裁切
                val closeBoxX = w - JBUI.scale(16)
                val textX = dotX + dotSize + JBUI.scale(6)
                val maxTextW = closeBoxX - textX - JBUI.scale(4)

                g2.font = JBFont.label().deriveFont(11f)
                val fm = g2.fontMetrics
                val textY = (h - fm.height) / 2 + fm.ascent
                g2.color = if (variable.visible) {
                    if (isHovered) JBColor.foreground() else JBColor(Color(0x24, 0x29, 0x2f), Color(0xdf, 0xe2, 0xe7))
                } else {
                    JBColor.GRAY
                }
                if (maxTextW > 0) {
                    val oldClip = g2.clip
                    g2.clipRect(textX, 0, maxTextW, h)
                    g2.drawString(buildText(), textX, textY)
                    g2.clip = oldClip
                }

                // 3. 右侧小 × (Close Button)
                val closeBoxY = (h - JBUI.scale(10)) / 2
                val closeSize = JBUI.scale(10)

                if (isCloseHovered) {
                    g2.color = if (isDark) Color(0x5c, 0x22, 0x22, 180) else Color(0xff, 0xdd, 0xdd)
                    g2.fillOval(closeBoxX - JBUI.scale(2), closeBoxY - JBUI.scale(1), closeSize + JBUI.scale(4), closeSize + JBUI.scale(4))
                    g2.color = JBColor.RED
                } else {
                    g2.color = if (isDark) Color(0x78, 0x82, 0x8f) else Color(0x9a, 0xa4, 0xb2)
                }

                g2.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val cx = closeBoxX + JBUI.scale(1)
                val cy = closeBoxY + JBUI.scale(1)
                val cs = closeSize - JBUI.scale(2)
                g2.drawLine(cx, cy, cx + cs, cy + cs)
                g2.drawLine(cx + cs, cy, cx, cy + cs)

            } finally {
                g2.dispose()
            }
        }
    }

    /** "+ 添加通道" 标签芯片。 */
    private inner class AddChannelChip : JPanel() {
        private var isHovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "添加示波通道（符号名、结构体成员 a.b.c、裸地址）"
            val mouseListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        promptAddChannel()
                    }
                }
            }
            addMouseListener(mouseListener)
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            val w = fm.stringWidth("+ 添加通道") + JBUI.scale(16)
            val h = JBUI.scale(22)
            return Dimension(w, h)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val w = width
                val h = height
                val arc = JBUI.scale(12)
                val isDark = UIUtil.isUnderDarcula()

                if (isHovered) {
                    g2.color = if (isDark) Color(0x35, 0x3d, 0x48) else Color(0xe5, 0xec, 0xf6)
                    g2.fillRoundRect(0, 0, w, h, arc, arc)
                }

                val accentColor = JBColor(Color(0x2B, 0x73, 0xEB), Color(0x58, 0x9D, 0xF6))
                g2.color = if (isHovered) accentColor else if (isDark) Color(0x45, 0x4d, 0x58) else Color(0xc8, 0xd0, 0xdc)
                val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 2f), 0f)
                g2.stroke = dashed
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)

                g2.font = JBFont.label().deriveFont(11f)
                g2.color = if (isHovered) accentColor else JBColor.GRAY
                val text = "+ 添加通道"
                val fm = g2.fontMetrics
                val textX = (w - fm.stringWidth(text)) / 2
                val textY = (h - fm.height) / 2 + fm.ascent
                g2.drawString(text, textX, textY)
            } finally {
                g2.dispose()
            }
        }
    }

    /** 通道芯片右键上下文菜单。 */
    private fun showChannelContextMenu(variable: ScopeVariable, e: MouseEvent) {
        val menu = JPopupMenu()

        // 1. 显示 / 隐藏
        val toggleText = if (variable.visible) "隐藏该通道波形" else "显示该通道波形"
        val toggleIcon = if (variable.visible) AllIcons.Actions.Show else AllIcons.Actions.CheckMulticaret
        menu.add(JMenuItem(toggleText, toggleIcon).apply {
            addActionListener {
                val newVal = !variable.visible
                service.setScopeVariableVisible(variable.address, newVal)
                variable.visible = newVal
                waveform.repaint()
                refreshChannelChips()
            }
        })

        menu.addSeparator()

        // 2. 更改通道颜色 (Preset + Custom Color)
        val colorMenu = JMenu("更改通道颜色").apply {
            icon = AllIcons.General.Modified
        }
        val presetNames = listOf(
            "天蓝 (Blue)", "浅绿 (Green)", "珊瑚红 (Red)", "金黄 (Yellow)",
            "亮紫 (Purple)", "青色 (Cyan)", "霓虹青 (Neon Cyan)", "霓虹洋红 (Neon Magenta)",
        )
        for (i in ScopePalette.COLORS.indices) {
            val c = ScopePalette.COLORS[i]
            val name = presetNames.getOrElse(i) { "颜色 #${i + 1}" }
            val item = JMenuItem(name, ColorDotIcon(c, 10)).apply {
                addActionListener {
                    service.setScopeVariableColor(variable.address, c)
                    variable.customColor = c
                    waveform.repaint()
                    refreshChannelChips()
                }
            }
            colorMenu.add(item)
        }
        colorMenu.addSeparator()
        colorMenu.add(JMenuItem("更多自定义颜色...", AllIcons.Actions.Colors).apply {
            addActionListener {
                val currentColor = ScopePalette.colorForVar(variable)
                val chosen = ColorChooser.chooseColor(
                    this@ScopePanel,
                    "选择通道颜色 - ${variable.name}",
                    currentColor,
                    true,
                )
                if (chosen != null) {
                    service.setScopeVariableColor(variable.address, chosen)
                    variable.customColor = chosen
                    waveform.repaint()
                    refreshChannelChips()
                }
            }
        })
        menu.add(colorMenu)

        // 3. 数据解码格式 (Format)
        val formatMenu = JMenu("数据解码格式 (${variable.format})").apply {
            icon = AllIcons.Actions.Edit
        }
        for (fmt in ValueFormat.values()) {
            val isCurrent = variable.format == fmt
            val mark = if (isCurrent) "  ✓" else ""
            formatMenu.add(JMenuItem("${fmt.name} (${fmt.byteSize}B)$mark").apply {
                if (isCurrent) font = font.deriveFont(Font.BOLD)
                addActionListener {
                    service.setScopeVariableFormat(variable.address, fmt)
                    waveform.repaint()
                    refreshChannelChips()
                }
            })
        }
        menu.add(formatMenu)

        // 4. 调节 Y 轴范围 (Y-Axis Range)
        val yMenu = JMenu("调节 Y 轴范围").apply {
            icon = AllIcons.Actions.MoveTo2
        }
        yMenu.add(JMenuItem("自适应此通道范围 (Fit Channel Range)").apply {
            addActionListener {
                // 单通道轻量快照：scopeSnapshot() 在版本变化时全通道整份克隆
                val series = service.scopeSeriesSnapshot(variable.address)
                val valid = series?.map { it.value }?.filter { !it.isNaN() && !it.isInfinite() }
                if (valid.isNullOrEmpty()) {
                    Messages.showInfoMessage(project, "该通道暂无有效采样数据", "自适应范围")
                    return@addActionListener
                }
                val minV = valid.minOrNull()!!.toDouble()
                val maxV = valid.maxOrNull()!!.toDouble()
                val span = if (maxV > minV) maxV - minV else if (abs(maxV) > 0) abs(maxV) * 0.2 else 1.0
                val pad = span * 0.15
                waveform.setYRange(minV - pad, maxV + pad)
                autoRangeButton.isSelected = false
            }
        })
        yMenu.add(JMenuItem("手动设置 Y 轴每格数值...").apply {
            addActionListener { promptSetValuePerDiv() }
        })
        yMenu.add(JMenuItem("恢复全局自动量程 (Auto Range)").apply {
            addActionListener {
                waveform.setAutoRange(true)
                autoRangeButton.isSelected = true
            }
        })
        menu.add(yMenu)

        menu.addSeparator()

        // 5. 通道信息与复制
        val addrStr = String.format(Locale.ROOT, "0x%08X", variable.address)
        menu.add(JMenuItem("复制地址 ($addrStr)").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(addrStr), null)
            }
        })
        menu.add(JMenuItem("复制变量名 (${variable.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(variable.name), null)
            }
        })

        menu.addSeparator()

        // 6. 移除 / 全部清空
        menu.add(JMenuItem("移除该通道", AllIcons.Actions.Close).apply {
            addActionListener {
                service.removeScopeVariable(variable.address)
                refreshChannelChips()
            }
        })
        menu.add(JMenuItem("清空所有通道", AllIcons.Actions.GC).apply {
            addActionListener {
                service.clearScopeVariables()
                refreshChannelChips()
            }
        })

        menu.show(e.component, e.x, e.y)
    }

    private fun startSampling() {
        waveform.setFollowLatest(true)
        followButton.isSelected = true
        val freq = (freqSpinner.value as Number).toDouble()
        service.checkBandwidth(freq).whenComplete { check, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err == null && !check.first) {
                    val mb = check.second / 1024.0 / 1024.0
                    val ok = Messages.showYesNoDialog(
                        project,
                        String.format(Locale.ROOT, "估计带宽 %.2f MB/s 超过 750 KB/s 上限，采样可能丢帧。仍要继续吗？", mb),
                        "带宽告警",
                        Messages.getWarningIcon(),
                    ) == Messages.YES
                    if (!ok) return@invokeLater
                }
                service.connectEngine().whenComplete { _, e2 ->
                    if (e2 != null && !service.isHaltedByDebug && service.engineState != "halted") {
                        val msg = org.embedded.monitor.agent.AgentService.extractError(e2) ?: e2.cause?.message ?: e2.message ?: "未知错误"
                        service.notify("示波启动失败: $msg")
                    }
                }
            }
        }
    }

    private fun promptAddChannel() {
        val expr = Messages.showInputDialog(
            project,
            "通道表达式：符号名 / a.b.c / float @ 0x20000000 / 0x20000000:u32",
            "添加示波通道",
            null,
        )?.trim() ?: return
        if (expr.isEmpty()) return
        service.addWatch(expr).whenComplete { item, err ->
            if (err != null) {
                service.notify("添加通道失败: ${err.message ?: "未知错误"}")
                return@whenComplete
            }
            if (item.address < 0x1000L) {
                service.removeWatch(item.id)
                service.notify(
                    "添加通道失败：表达式「${item.expr}」地址无效或指针尚未有效解引用，请在实时变量监视中展开后右键添加到示波器",
                    com.intellij.notification.NotificationType.WARNING,
                )
                return@whenComplete
            }
            ApplicationManager.getApplication().invokeLater {
                val added = service.addScopeVariable(item.expr, item.address, item.size, item.encoding)
                if (added == null) {
                    service.notify("该地址已在通道列表中。", com.intellij.notification.NotificationType.INFORMATION)
                } else {
                    refreshChannelChips()
                    service.checkBandwidth((freqSpinner.value as Number).toDouble()).whenComplete { check, _ ->
                        if (!check.first) {
                            ApplicationManager.getApplication().invokeLater {
                                service.notify(
                                    String.format(Locale.ROOT, "带宽预警：估计带宽 %.2f MB/s 超过上限", check.second / 1024.0 / 1024.0),
                                    com.intellij.notification.NotificationType.WARNING,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun promptSetTimePerDiv() {
        val current = waveform.timePerDivision()
        val input = Messages.showInputDialog(
            project,
            String.format(Locale.ROOT, "X 轴每格时间（秒），当前 %.3fs：", current),
            "设置 X 单位长度", null,
        )?.trim() ?: return
        val seconds = input.toDoubleOrNull()
        if (seconds == null || seconds <= 0) {
            Messages.showErrorDialog(project, "请输入正数（秒）。", "设置 X 单位长度")
            return
        }
        waveform.setTimePerDivision(seconds)
    }

    private fun promptSetValuePerDiv() {
        val current = waveform.valuePerDivision()
        val input = Messages.showInputDialog(
            project,
            String.format(Locale.ROOT, "Y 轴每格数值，当前 %.4g：", current),
            "设置 Y 单位长度", null,
        )?.trim() ?: return
        val value = input.toDoubleOrNull()
        if (value == null || value <= 0) {
            Messages.showErrorDialog(project, "请输入正数。", "设置 Y 单位长度")
            return
        }
        waveform.setValuePerDivision(value)
    }

    private fun exportCsv() {
        // 快照克隆必须挪出 EDT：scopeSnapshot() 在版本变化时对全通道整份克隆
        // （50k×通道数），与 refreshWaveform 的 snapshotInFlight 池化处理同理。
        // 流程：pooled 取快照 → EDT 弹文件框 → pooled 写文件
        ApplicationManager.getApplication().executeOnPooledThread {
            val vars = service.scopeVariables.toList()
            val snapshot = service.scopeSnapshot()
            if (vars.isEmpty() || snapshot.isEmpty()) {
                com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                    Messages.showInfoMessage(project, "暂无数据可导出。", "导出 CSV")
                }
                return@executeOnPooledThread
            }
            com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                val chooser = javax.swing.JFileChooser()
                chooser.dialogTitle = "导出 CSV"
                chooser.selectedFile = java.io.File("scope_data.csv")
                if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return@invokeLaterIfNeeded
                val path = chooser.selectedFile.path.let { if (it.endsWith(".csv", true)) it else "$it.csv" }
                writeCsvPooled(vars, snapshot, path)
            }
        }
    }

    private fun writeCsvPooled(
        vars: List<ScopeVariable>,
        snapshot: Map<Long, List<ScopeSample>>,
        path: String,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val span = service.scopeBufferSpanSec()
                val rate = service.scopeBufferRateHz()
                val rows = java.io.File(path).bufferedWriter(Charsets.UTF_8).use { writer ->
                    org.embedded.monitor.core.ScopeCsv.write(writer, vars, snapshot)
                }
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        project,
                        String.format(Locale.ROOT, "已导出 %d 行数据（覆盖 %.2fs，约 %.0f Hz）到：\n%s", rows, span, rate, path),
                        "导出 CSV",
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "导出失败：${e.message}", "导出 CSV")
                }
            }
        }
    }

    // 全量快照克隆的在飞标记：30Hz 拉取 × 版本变化即全量 clone（50k×通道数），
    // 必须挪出 EDT 且防重入堆积
    private val snapshotInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun refreshWaveform() {
        // 工具窗隐藏时跳过轮询（定时器仅在面板可见时有意义）
        if (!isShowing()) return
        val state = service.engineState
        waveform.setSampleRate(service.actualRateHz)
        if (snapshotInFlight.compareAndSet(false, true)) {
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val snap = service.scopeSnapshot()
                    val vars = service.scopeVariables.toList()
                    com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                        waveform.setFrame(snap, vars)
                    }
                } finally {
                    snapshotInFlight.set(false)
                }
            }
        }

        startButton.isEnabled = state != "running" && state != "connecting" && state != "halted"
        stopButton.isEnabled = state == "running" || state == "halted" || state == "connecting"
    }

    private fun refreshMetrics() {
        if (!isShowing()) return
        val state = service.engineState
        val actualHz = service.actualRateHz
        val bufSpan = service.scopeBufferSpanSec()
        val bufHz = service.scopeBufferRateHz()
        val dropped = service.scopeDroppedCount()
        val gaps = service.scopeGapCount()

        actualRateLabel.text = String.format(Locale.ROOT, "%.0f Hz", actualHz)
        if (dropped > 0 || gaps > 0) {
            droppedLabel.text = String.format(Locale.ROOT, "缺样 %d · 空档 %d", dropped, gaps)
            droppedLabel.foreground = JBColor.RED
        } else {
            droppedLabel.text = ""
        }

        val bufText = if (bufSpan > 0) String.format(Locale.ROOT, " (缓冲 %.0fHz/%.1fs)", bufHz, bufSpan) else ""
        statusLabel.text = when (state) {
            "running" -> String.format(
                Locale.ROOT,
                "采样中 | 目标 %.0f Hz | 实际 %.1f Hz%s | 样本 %d | 缺样 %d | 空档 %d | 错误 %d",
                (freqSpinner.value as Number).toDouble(),
                actualHz,
                bufText,
                service.scopeSampleCount(),
                dropped,
                gaps,
                service.scopeErrorCount(),
            ) + (service.lastEngineError?.let { " | $it" } ?: "")
            "halted" -> "目标已暂停"
            "connecting" -> "连接中..." + (service.lastEngineError?.let { " | $it" } ?: "")
            else -> "就绪（监视/调试请在实时变量监视窗口或 CLion 调试器操作）" +
                (service.lastEngineError?.let { " | $it" } ?: service.lastAgentError?.let { " | $it" } ?: "")
        }
    }

    private fun refreshTable() {
        if (!isShowing()) return
        val vars = service.scopeVariables.toList()
        val lastValues = service.scopeLastValues()
        tableModel.update(vars, lastValues)
        refreshChannelChips()
    }

    override fun dispose() {
        refreshTimer.stop()
        metricsTimer.stop()
        tableTimer.stop()
        // 波形画布内部还有自己的 33ms repaintTimer，不在此停会泄漏整个组件图
        waveform.stopRepaintTimer()
    }

    companion object {
        fun formatValue(value: Float): String {
            if (value == 0f) return "0"
            val a = abs(value)
            return when {
                a >= 1e6f || a < 1e-2f -> String.format(Locale.ROOT, "%.2e", value)
                a >= 1000f -> String.format(Locale.ROOT, "%.0f", value)
                else -> String.format(Locale.ROOT, "%.2f", value)
            }
        }
    }
}

/** 彩色指示圆点图标。 */
private class ColorDotIcon(private val color: Color, private val size: Int) : javax.swing.Icon {
    override fun getIconWidth(): Int = size + 4
    override fun getIconHeight(): Int = size + 4
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x + 2, y + 2, size, size)
        } finally {
            g2.dispose()
        }
    }
}

/** 网格切换图标（2x2 网格线）。 */
private class GridIcon(private val size: Int = 14) : javax.swing.Icon {
    override fun getIconWidth(): Int = size + 4
    override fun getIconHeight(): Int = size + 4
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val isSelected = (c as? JToggleButton)?.isSelected ?: true
            g2.color = if (isSelected) JBColor.foreground() else JBColor.GRAY
            g2.stroke = BasicStroke(1.1f)
            val pad = 2
            val ox = x + 2 + pad
            val oy = y + 2 + pad
            val w = size - pad * 2
            val h = size - pad * 2
            g2.drawRect(ox, oy, w, h)
            g2.drawLine(ox + w / 2, oy, ox + w / 2, oy + h)
            g2.drawLine(ox, oy + h / 2, ox + w, oy + h / 2)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 示波通道表格模型（保持公共 API 与单元测试完全兼容）。
 */
class ScopeChannelTableModel(
    private val onVariableVisibleChange: (Long, Boolean) -> Unit = { _, _ -> },
    private val onVariableFormatChange: (Long, ValueFormat) -> Unit = { _, _ -> },
) : AbstractTableModel() {
    private val columns = arrayOf("显示", "通道", "格式", "地址", "大小", "末值", "光标")
    private var vars: List<ScopeVariable> = emptyList()
    private var lastValues: Map<Long, Float?> = emptyMap()
    private var cursorValues: Map<String, Float> = emptyMap()

    fun update(variables: List<ScopeVariable>, last: Map<Long, Float?>) {
        val structureChanged = vars.size != variables.size || vars.indices.any { vars[it].address != variables[it].address }
        vars = variables
        lastValues = last
        if (structureChanged) {
            fireTableDataChanged()
        } else if (rowCount > 0) {
            fireTableRowsUpdated(0, rowCount - 1)
        }
    }

    fun setCursorValues(values: Map<String, Float>) {
        cursorValues = values
        if (rowCount > 0) {
            fireTableRowsUpdated(0, rowCount - 1)
        }
    }

    fun addressAt(row: Int): Long? = vars.getOrNull(row)?.address
    fun variableAt(row: Int): ScopeVariable? = vars.getOrNull(row)

    override fun getRowCount(): Int = vars.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> Boolean::class.javaObjectType
        2 -> ValueFormat::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
        columnIndex == 2 // 仅格式列可下拉编辑，显示勾选由鼠标单次点击瞬时切换

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val v = vars[rowIndex]
        return when (columnIndex) {
            0 -> v.visible
            1 -> v.name
            2 -> v.format
            3 -> if (v.resolved) String.format(Locale.ROOT, "0x%08X", v.address) else "待定位"
            4 -> "${v.size} B"
            5 -> if (v.resolved) lastValues[v.address]?.let { formatValue(it) } ?: "-" else "-"
            6 -> if (v.resolved) cursorValues[v.name]?.let { formatValue(it) } ?: "-" else "-"
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val v = vars.getOrNull(rowIndex) ?: return
        when (columnIndex) {
            0 -> onVariableVisibleChange(v.address, aValue as Boolean)
            2 -> onVariableFormatChange(v.address, aValue as ValueFormat)
        }
    }

    private fun formatValue(value: Float): String = ScopePanel.formatValue(value)
}
