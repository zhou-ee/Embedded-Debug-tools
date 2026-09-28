package org.embedded.monitor.registers

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.settings.EmbeddedMonitorSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * 嵌入式外设寄存器实时监视面板（Register Live Watch）。
 * 架构与交互对标 software_ref 中的 SvdPanel：
 * 1. 顶部控制栏：加载/重新加载 SVD、单次刷新、动态刷新总开关、刷新频率下拉框（1Hz/2Hz/5Hz/10Hz）与状态指示；
 * 2. 左右结构主体（JBSplitter 左右分布）：
 *    - 左侧：搜索过滤输入栏 + 外设/寄存器树（移除文件夹图标，使用纯净树形折叠箭头，行首提供独立 [x] 实时监视复选框与数值）；
 *    - 右侧：选中寄存器卡片头部（名称、物理地址、复位值、当前值与便捷操作按钮） + 位域（Bitfield）切片详情表格；
 * 3. 内存访问与调度：基于相邻地址合并块（readRegisterBlocks）进行低开销无干扰实时内存读取；
 * 4. 支持右键与快捷操作：添加到实时变量监视（Watch）、添加到示波器（Scope）与直接修改寄存器数值（写内存）。
 */
class RegisterLiveWatchPanel(private val project: Project) : JBPanel<RegisterLiveWatchPanel>(BorderLayout()), Disposable {

    private val agentService get() = runCatching { AgentService.getInstance(project) }.getOrNull()
    private val settings get() = runCatching { EmbeddedMonitorSettings.getInstance(project) }.getOrNull()

    // 内存数据缓存：path -> 32/64 位原始数值
    val registerValues = ConcurrentHashMap<String, Long>()

    // 勾选了动态刷新的寄存器路径集合
    val autoRefreshPaths = ConcurrentHashMap.newKeySet<String>()

    // 全局动态刷新开关与频率
    var autoRefreshAll: Boolean = false
        private set
    var autoRefreshFreq: Int = 2
        private set

    // SVD 模型与树模型（pooled 线程写、EDT 读，需要 @Volatile 保证可见性）
    @Volatile
    private var svdDevice: SvdDevice? = null
    private val treeRoot = DefaultMutableTreeNode("Root")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    // 搜索过滤文本
    private val filterField = JBTextField()
    private var currentFilter: String = ""

    // 当前选中的寄存器
    private var selectedRegister: SvdRegister? = null

    // 右侧卡片面板（空状态 vs 位域详情）
    private val rightCardLayout = CardLayout()
    private val rightCardPanel = JPanel(rightCardLayout)

    // 右侧寄存器头部信息组件
    private val regNameLabel = JBLabel("未选择寄存器").apply {
        font = JBFont.h3().asBold()
    }
    private val metaLabel = JBLabel("").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }
    private val descLabel = JBLabel("").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }

    // 位域详情表模型与表格
    private val bitfieldModel = object : DefaultTableModel(
        arrayOf("Field", "Bits", "Value", "Enum / Description"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val bitfieldTable = JBTable(bitfieldModel)

    // 工具栏组件
    private val deviceLabel = JBLabel("未加载 SVD").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }
    private val statusLabel = JBLabel("就绪").apply {
        font = JBFont.small()
        foreground = JBColor.GRAY
    }
    private val autoRefreshBtn = JToggleButton("动态刷新", AllIcons.Actions.SyncPanels)
    private val freqCombo = JComboBox(arrayOf("1 Hz", "2 Hz", "5 Hz", "10 Hz"))

    // 动态刷新轮询定时器
    private var refreshTimer: Timer? = null

    // 状态回调注销器
    private var stateListenerRemover: (() -> Unit)? = null

    init {
        border = JBUI.Borders.empty()

        // 从持久化设置中恢复配置
        runCatching {
            val s = settings
            if (s != null) {
                autoRefreshAll = s.registerAutoRefreshAll
                autoRefreshFreq = s.registerRefreshFreq.coerceIn(1, 10)
                autoRefreshPaths.addAll(s.registerAutoRefreshPaths)
            }
        }

        buildUi()
        initListeners()
        syncTimer()
        updateStatusLabel()

        // 尝试自动加载工程匹配的 SVD 文件
        ApplicationManager.getApplication()?.executeOnPooledThread {
            val autoFile = SvdAutoLocator.locateSvdFile(project)
            if (autoFile != null) {
                loadSvdFile(autoFile)
            }
        }
    }

    private fun buildUi() {
        // 1. 顶部控制栏
        val topPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(4, 6, 4, 6)
            )
        }

        val toolbarLeft = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val loadBtn = JButton("加载 SVD", AllIcons.Actions.MenuOpen).apply {
            toolTipText = "选择 CMSIS-SVD 外设描述文件 (.svd / .xml)"
            addActionListener { openSvdFileChooser() }
        }
        val reloadBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "重新加载当前 SVD 文件"
            addActionListener { reloadSvd() }
        }
        val refreshOnceBtn = JButton("单次刷新", AllIcons.Actions.ForceRefresh).apply {
            toolTipText = "立即读取当前展开的所有可见寄存器数值"
            addActionListener { refreshVisible() }
        }

        autoRefreshBtn.apply {
            isSelected = autoRefreshAll
            toolTipText = if (autoRefreshAll) "关闭全局动态刷新（全部展开项）" else "开启全局动态刷新（全部展开项约 ${autoRefreshFreq}Hz）"
            addItemListener { e ->
                autoRefreshAll = (e.stateChange == ItemEvent.SELECTED)
                toolTipText = if (autoRefreshAll) "关闭全局动态刷新（全部展开项）" else "开启全局动态刷新（全部展开项约 ${autoRefreshFreq}Hz）"
                settings?.update { s -> s.registerAutoRefreshAll = autoRefreshAll }
                syncTimer()
                updateStatusLabel()
                tree.repaint()
            }
        }

        freqCombo.apply {
            selectedItem = "$autoRefreshFreq Hz"
            toolTipText = "动态刷新频率（1Hz ~ 10Hz）"
            addActionListener {
                val selected = selectedItem?.toString()?.substringBefore(" ")?.toIntOrNull() ?: 2
                autoRefreshFreq = selected
                settings?.update { s -> s.registerRefreshFreq = autoRefreshFreq }
                syncTimer()
                updateStatusLabel()
            }
        }

        toolbarLeft.add(loadBtn)
        toolbarLeft.add(reloadBtn)
        toolbarLeft.add(refreshOnceBtn)
        toolbarLeft.add(autoRefreshBtn)
        toolbarLeft.add(freqCombo)

        val toolbarRight = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(statusLabel)
            add(deviceLabel)
        }

        topPanel.add(toolbarLeft, BorderLayout.WEST)
        topPanel.add(toolbarRight, BorderLayout.EAST)
        add(topPanel, BorderLayout.NORTH)

        // 2. 主体左右分割面板（Horizontal Splitter, vertical = false）
        val splitter = JBSplitter(false, 0.42f, 0.15f, 0.85f).apply {
            splitterProportionKey = "EmbeddedRegisters.SplitterProportion"
        }

        // --- 左侧：搜索过滤与外设/寄存器树 ---
        val leftPanel = JPanel(BorderLayout())

        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 4, 4)
            filterField.emptyText.text = "过滤外设 / 寄存器…"
            filterField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = applyFilter(filterField.text.trim())
                override fun removeUpdate(e: DocumentEvent?) = applyFilter(filterField.text.trim())
                override fun changedUpdate(e: DocumentEvent?) = applyFilter(filterField.text.trim())
            })
            add(filterField, BorderLayout.CENTER)
        }
        leftPanel.add(searchPanel, BorderLayout.NORTH)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(22)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = RegisterTreeCellRenderer()

        val treeScroll = JBScrollPane(tree).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 1, JBColor.border())
        }
        leftPanel.add(treeScroll, BorderLayout.CENTER)
        splitter.firstComponent = leftPanel

        // --- 右侧：寄存器位域（Bitfields）详情面板 ---
        val emptyPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(40)
            val hint = JBLabel("👈 请在左侧选择寄存器以查看位域详情 (Bitfields)", SwingConstants.CENTER).apply {
                font = JBFont.medium()
                foreground = JBColor.GRAY
            }
            add(hint, BorderLayout.CENTER)
        }
        rightCardPanel.add(emptyPanel, "EMPTY")

        val detailsPanel = JPanel(BorderLayout())

        // 详情面板头部 Card
        val headerCard = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(8, 12, 8, 12)
            )
            background = UIUtil.getPanelBackground()
        }

        val headerTop = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(regNameLabel, BorderLayout.WEST)

            val actionsBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                val editBtn = JButton("修改数值", AllIcons.Actions.Edit).apply {
                    toolTipText = "写入寄存器新数值 (写内存)"
                    addActionListener {
                        val reg = selectedRegister
                        if (reg != null) promptEditRegisterValue(reg)
                    }
                }
                val refreshRegBtn = JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "立即刷新此寄存器"
                    addActionListener {
                        val reg = selectedRegister
                        if (reg != null) readRegisterBlocks(listOf(reg))
                    }
                }
                val watchBtn = JButton(AllIcons.Debugger.Watch).apply {
                    toolTipText = "添加到实时变量监视"
                    addActionListener {
                        val reg = selectedRegister
                        if (reg != null) {
                            // 按寄存器实际宽度生成类型后缀：硬编码 u32 会对 1/2 字节
                            // 寄存器越界读到外设保留区
                            val fmt = when (reg.size) { 1 -> "u8"; 2 -> "u16"; else -> "u32" }
                            val expr = "0x%08X:$fmt".format(Locale.ROOT, reg.address)
                            agentService?.addWatch(expr)?.whenComplete { _, err ->
                                val msg = if (err != null) "添加 ${reg.name} 失败: ${err.message ?: "未知错误"}"
                                else "已将 ${reg.name} (0x%08X) 添加到实时变量监视".format(Locale.ROOT, reg.address)
                                val title = if (err != null) "添加失败" else "添加成功"
                                com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                                    if (err != null) Messages.showErrorDialog(project, msg, title)
                                    else Messages.showInfoMessage(project, msg, title)
                                }
                            }
                        }
                    }
                }
                val scopeBtn = JButton(AllIcons.Toolwindows.ToolWindowPalette).apply {
                    toolTipText = "添加到示波器通道"
                    addActionListener {
                        val reg = selectedRegister
                        if (reg != null) {
                            agentService?.addScopeVariable(reg.name, reg.address, reg.size, "unsigned")
                            Messages.showInfoMessage(project, "已将 ${reg.name} (0x%08X) 添加到示波器通道".format(Locale.ROOT, reg.address), "添加成功")
                        }
                    }
                }
                add(editBtn)
                add(refreshRegBtn)
                add(watchBtn)
                add(scopeBtn)
            }
            add(actionsBar, BorderLayout.EAST)
        }

        val headerMeta = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(metaLabel, BorderLayout.WEST)
        }
        val headerDesc = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(descLabel, BorderLayout.WEST)
        }

        val headerContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerTop)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(headerMeta)
            add(headerDesc)
        }
        headerCard.add(headerContent, BorderLayout.CENTER)
        detailsPanel.add(headerCard, BorderLayout.NORTH)

        // 位域表格
        bitfieldTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        bitfieldTable.rowHeight = JBUI.scale(23)
        bitfieldTable.tableHeader.font = JBFont.small().asBold()

        bitfieldTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(120)
        bitfieldTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(70)
        bitfieldTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(85)
        bitfieldTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(320)

        // 字体与颜色渲染器
        val fieldNameRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                font = JBFont.h4().asBold()
                if (!isSelected) {
                    foreground = JBColor(0x7B1FA2, 0xBA68C8)
                }
                return c
            }
        }
        bitfieldTable.columnModel.getColumn(0).cellRenderer = fieldNameRenderer

        val bitsRenderer = object : DefaultTableCellRenderer() {
            init {
                horizontalAlignment = SwingConstants.CENTER
            }
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                font = JBFont.h4().asPlain()
                if (!isSelected) {
                    foreground = JBColor.GRAY
                }
                return c
            }
        }
        bitfieldTable.columnModel.getColumn(1).cellRenderer = bitsRenderer

        val valueRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                font = JBFont.h4().asBold()
                if (!isSelected) {
                    foreground = JBColor(0x007ACC, 0x4EC9B0)
                }
                return c
            }
        }
        bitfieldTable.columnModel.getColumn(2).cellRenderer = valueRenderer

        bitfieldTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val reg = selectedRegister
                    if (reg != null) promptEditRegisterValue(reg)
                }
            }
        })

        val bitfieldScroll = JBScrollPane(bitfieldTable).apply {
            border = BorderFactory.createEmptyBorder()
        }
        detailsPanel.add(bitfieldScroll, BorderLayout.CENTER)

        rightCardPanel.add(detailsPanel, "DETAILS")
        rightCardLayout.show(rightCardPanel, "EMPTY")

        splitter.secondComponent = rightCardPanel
        add(splitter, BorderLayout.CENTER)
    }

    private fun initListeners() {
        // 单击与复选框交互
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val rowBounds = tree.getPathBounds(path) ?: return

                if (node is PeripheralTreeNode) {
                    // 点击外设文本区域展开或折叠（句柄区域由树自身处理）
                    if (e.x >= rowBounds.x) {
                        if (tree.isExpanded(path)) {
                            tree.collapsePath(path)
                        } else {
                            tree.expandPath(path)
                        }
                    }
                    return
                }

                if (node is RegisterTreeNode) {
                    // 点击行首复选框区域（0 ~ 24px）切换单寄存器实时刷新
                    val relX = e.x - rowBounds.x
                    if (relX in 0..JBUI.scale(24)) {
                        toggleRegisterAutoRefresh(node.register.path)
                        tree.repaint(rowBounds)
                        return
                    }
                    // 选中寄存器更新位域表
                    updateBitfieldTableFor(node.register)
                }

                // 双击修改寄存器值
                if (e.clickCount == 2 && node is RegisterTreeNode) {
                    promptEditRegisterValue(node.register)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) handleContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) handleContextMenu(e)
            }
        })

        // 键盘交互
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val node = tree.lastSelectedPathComponent as? RegisterTreeNode ?: return
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    toggleRegisterAutoRefresh(node.register.path)
                    tree.repaint()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_F2 || e.keyCode == KeyEvent.VK_ENTER) {
                    promptEditRegisterValue(node.register)
                    e.consume()
                }
            }
        })

        // 树选中监听：更新位域表
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? RegisterTreeNode
            if (node != null) {
                updateBitfieldTableFor(node.register)
            }
        }

        // 监听底层引擎目标状态变化（halted 暂停时单次刷新，running 时恢复定时器）
        val listener: (String) -> Unit = { state ->
            UIUtil.invokeLaterIfNeeded {
                when (state) {
                    "halted" -> {
                        refreshVisible()
                        updateStatusLabel()
                    }
                    "running" -> {
                        syncTimer()
                        updateStatusLabel()
                    }
                    "disconnected" -> {
                        syncTimer()
                        updateStatusLabel()
                    }
                }
            }
        }
        agentService?.addStateListener(listener)
        stateListenerRemover = { agentService?.removeStateListener(listener) }
    }

    private fun updateStatusLabel() {
        UIUtil.invokeLaterIfNeeded {
            val hasAuto = autoRefreshAll || autoRefreshPaths.isNotEmpty()
            if (hasAuto) {
                val countText = if (autoRefreshAll) "全部展开项" else "${autoRefreshPaths.size} 项已勾选"
                statusLabel.text = "● 动态刷新中 ($countText @ ${autoRefreshFreq}Hz)"
                statusLabel.foreground = JBColor(0x2E7D32, 0x4EC9B0)
            } else {
                statusLabel.text = "就绪 · 运行中"
                statusLabel.foreground = JBColor.GRAY
            }
        }
    }

    private fun handleContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? RegisterTreeNode ?: return
        val reg = node.register

        val menu = JPopupMenu()
        val isAuto = isAutoRefresh(reg.path)

        menu.add(JMenuItem(if (isAuto) "关闭此寄存器实时刷新" else "开启此寄存器实时刷新").apply {
            icon = AllIcons.Actions.SyncPanels
            addActionListener { toggleRegisterAutoRefresh(reg.path) }
        })
        menu.addSeparator()

        menu.add(JMenuItem("添加到实时变量监视", AllIcons.Debugger.Watch).apply {
            addActionListener {
                val expr = "0x%08X:u32".format(Locale.ROOT, reg.address)
                agentService?.addWatch(expr)
                Messages.showInfoMessage(project, "已将 ${reg.name} (0x%08X) 添加到实时变量监视".format(Locale.ROOT, reg.address), "添加成功")
            }
        })

        menu.add(JMenuItem("添加到示波器", AllIcons.Toolwindows.ToolWindowPalette).apply {
            addActionListener {
                agentService?.addScopeVariable(reg.name, reg.address, reg.size, "unsigned")
                Messages.showInfoMessage(project, "已将 ${reg.name} (0x%08X) 添加到示波器通道".format(Locale.ROOT, reg.address), "添加成功")
            }
        })

        menu.addSeparator()

        menu.add(JMenuItem("立即刷新此寄存器", AllIcons.Actions.Refresh).apply {
            addActionListener { readRegisterBlocks(listOf(reg)) }
        })

        menu.add(JMenuItem("修改寄存器数值 (写内存)...", AllIcons.Actions.Edit).apply {
            addActionListener { promptEditRegisterValue(reg) }
        })

        menu.addSeparator()

        menu.add(JMenuItem("复制物理地址 (0x%08X)".format(Locale.ROOT, reg.address), AllIcons.Actions.Copy).apply {
            addActionListener {
                val sel = StringSelection("0x%08X".format(Locale.ROOT, reg.address))
                Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
            }
        })

        val currentVal = registerValues[reg.path]
        if (currentVal != null) {
            menu.add(JMenuItem("复制当前数值 (0x%08X)".format(Locale.ROOT, currentVal), AllIcons.Actions.Copy).apply {
                addActionListener {
                    val sel = StringSelection("0x%08X".format(Locale.ROOT, currentVal))
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        }

        menu.show(tree, e.x, e.y)
    }

    fun isAutoRefresh(path: String): Boolean {
        return autoRefreshAll || autoRefreshPaths.contains(path)
    }

    private fun toggleRegisterAutoRefresh(path: String) {
        if (autoRefreshPaths.contains(path)) {
            autoRefreshPaths.remove(path)
        } else {
            autoRefreshPaths.add(path)
        }
        settings?.update { s -> s.registerAutoRefreshPaths = autoRefreshPaths.toMutableSet() }
        syncTimer()
        updateStatusLabel()
        tree.repaint()
    }

    private fun promptEditRegisterValue(reg: SvdRegister) {
        val curVal = registerValues[reg.path] ?: reg.resetValue ?: 0L
        val initHex = "0x" + curVal.toString(16).uppercase(Locale.ROOT)
        val input = Messages.showInputDialog(
            project,
            "请输入写入 ${reg.name} (0x%08X) 的十六进制数值:".format(Locale.ROOT, reg.address),
            "修改寄存器数值",
            AllIcons.Actions.Edit,
            initHex,
            null
        ) ?: return

        val clean = input.trim().removePrefix("0x").removePrefix("0X")
        val newVal = clean.toLongOrNull(16)
        if (newVal == null) {
            Messages.showErrorDialog(project, "无效的十六进制数值: $input", "错误")
            return
        }

        val bytes = ByteArray(reg.size) { i -> ((newVal ushr (i * 8)) and 0xFF).toByte() }
        val svc = agentService ?: return
        svc.writeMem(reg.address, bytes).thenAccept {
            // 写入成功后立即重读该寄存器
            readRegisterBlocks(listOf(reg))
        }.exceptionally { err ->
            UIUtil.invokeLaterIfNeeded {
                Messages.showErrorDialog(project, "写入失败: ${err.message}", "寄存器写错误")
            }
            null
        }
    }

    private fun updateBitfieldTableFor(reg: SvdRegister) {
        selectedRegister = reg
        val curVal = registerValues[reg.path]
        val curHex = if (curVal != null) "0x%08X".format(Locale.ROOT, curVal) else "—"
        val resetHex = if (reg.resetValue != null) "0x%08X".format(Locale.ROOT, reg.resetValue) else "?"

        regNameLabel.text = "${reg.path.substringBefore("/")}  ➜  ${reg.name}"
        metaLabel.text = "物理地址: 0x%08X    大小: ${reg.size * 8} 位    访问: ${reg.access ?: "RW"}    复位值: $resetHex    当前值: $curHex".format(Locale.ROOT, reg.address)
        descLabel.text = if (!reg.description.isNullOrBlank()) "说明: ${reg.description}" else ""
        descLabel.isVisible = !reg.description.isNullOrBlank()

        bitfieldModel.rowCount = 0
        for (f in reg.fields) {
            val fValStr = if (curVal != null) "0x" + f.extractValue(curVal).toString(16).uppercase(Locale.ROOT) else "—"
            val enumName = if (curVal != null) f.enumName(curVal) else null
            val desc = listOfNotNull(enumName?.let { "[$it]" }, f.description).joinToString(" ")
            bitfieldModel.addRow(arrayOf(f.name, f.bitsText, fValStr, desc))
        }
        rightCardLayout.show(rightCardPanel, "DETAILS")
    }

    private fun updateBitfieldTable() {
        val node = tree.lastSelectedPathComponent as? RegisterTreeNode
        if (node != null) {
            updateBitfieldTableFor(node.register)
        } else {
            val reg = selectedRegister
            if (reg != null) {
                updateBitfieldTableFor(reg)
            }
        }
    }

    private fun openSvdFileChooser() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("svd").apply {
            title = "选择 CMSIS-SVD 外设文件"
            description = "选择目标芯片的 CMSIS-SVD (.svd / .xml) 描述文件"
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(chosen.path)
        settings?.update { s -> s.svdPath = file.absolutePath }
        loadSvdFile(file)
    }

    fun reloadSvd() {
        val path = settings?.svdPath ?: ""
        if (path.isNotBlank()) {
            val f = File(path)
            if (f.isFile) {
                loadSvdFile(f)
                return
            }
        }
        val autoFile = SvdAutoLocator.locateSvdFile(project)
        if (autoFile != null) {
            loadSvdFile(autoFile)
        } else {
            openSvdFileChooser()
        }
    }

    fun loadSvdFile(file: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val dev = SvdParser.parse(file)
                svdDevice = dev
                UIUtil.invokeLaterIfNeeded {
                    deviceLabel.text = "${dev.name} (${dev.peripherals.size} 外设)"
                    deviceLabel.toolTipText = "${file.absolutePath} - ${dev.description ?: ""}"
                    applyFilter(currentFilter)
                }
            } catch (e: Exception) {
                UIUtil.invokeLaterIfNeeded {
                    deviceLabel.text = "解析 SVD 失败: ${e.message}"
                    deviceLabel.toolTipText = e.message
                }
            }
        }
    }

    private fun applyFilter(filter: String) {
        currentFilter = filter.lowercase(Locale.ROOT)
        val dev = svdDevice ?: return

        treeRoot.removeAllChildren()
        for (p in dev.peripherals) {
            val pMatch = currentFilter.isEmpty() || p.name.lowercase(Locale.ROOT).contains(currentFilter)
            val pNode = PeripheralTreeNode(p)

            for (reg in p.registers) {
                val regMatch = pMatch || reg.name.lowercase(Locale.ROOT).contains(currentFilter)
                if (regMatch) {
                    pNode.add(RegisterTreeNode(reg))
                }
            }

            if (pNode.childCount > 0) {
                treeRoot.add(pNode)
            }
        }

        treeModel.reload()
        if (currentFilter.isNotEmpty()) {
            // 搜索过滤时自动展开匹配项
            for (i in 0 until tree.rowCount) {
                tree.expandRow(i)
            }
        }
    }

    /** 收集树中当前可见（父级展开）的寄存器节点 */
    fun visibleRegisters(): List<SvdRegister> {
        val out = mutableListOf<SvdRegister>()
        val root = treeRoot
        for (i in 0 until root.childCount) {
            val pNode = root.getChildAt(i) as? PeripheralTreeNode ?: continue
            val pPath = TreePath(arrayOf(root, pNode))
            if (!tree.isExpanded(pPath)) continue

            for (j in 0 until pNode.childCount) {
                val rNode = pNode.getChildAt(j) as? RegisterTreeNode ?: continue
                out.add(rNode.register)
            }
        }
        return out
    }

    /** 单次立即刷新所有可见寄存器 */
    fun refreshVisible() {
        val visible = visibleRegisters()
        if (visible.isNotEmpty()) {
            readRegisterBlocks(visible)
        }
    }

    /** 自动动态刷新（满足条件的寄存器） */
    fun refreshAuto() {
        // 工具窗不可见时暂停轮询，不再空发 readMem
        if (!isShowing()) return
        if (!autoRefreshAll && autoRefreshPaths.isEmpty()) return
        val visible = visibleRegisters()
        val targets = if (autoRefreshAll) {
            visible
        } else {
            visible.filter { autoRefreshPaths.contains(it.path) }
        }
        if (targets.isNotEmpty()) {
            readRegisterBlocks(targets)
        }
    }

    /**
     * 底层读块执行：相邻地址合并（gap <= 64 字节，max_block <= 1024 字节）。
     * 对标 software_ref/src/stores/svd.ts 中的 readRegisterBlocks 核心算法。
     */
    fun readRegisterBlocks(regs: List<SvdRegister>) {
        if (regs.isEmpty()) return
        val sorted = regs.sortedBy { it.address }

        data class Block(var start: Long, var size: Int, val regs: MutableList<SvdRegister>)
        val blocks = mutableListOf<Block>()
        val gapLimit = 64L
        val maxBlock = 1024

        for (reg in sorted) {
            val last = blocks.lastOrNull()
            if (last != null) {
                val lastEnd = last.start + last.size
                val gap = reg.address - lastEnd
                val newSize = (reg.address + reg.size - last.start).toInt()
                if (gap in 0..gapLimit && newSize <= maxBlock) {
                    last.size = newSize
                    last.regs.add(reg)
                    continue
                }
            }
            blocks.add(Block(reg.address, reg.size, mutableListOf(reg)))
        }

        val svc = agentService ?: return
        for (block in blocks) {
            svc.readMem(block.start, block.size).thenAccept { bytes ->
                if (bytes.isNotEmpty()) {
                    for (reg in block.regs) {
                        val off = (reg.address - block.start).toInt()
                        var v = 0L
                        for (i in 0 until reg.size.coerceAtMost(8)) {
                            if (off + i < bytes.size) {
                                v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
                            }
                        }
                        registerValues[reg.path] = v
                    }
                    UIUtil.invokeLaterIfNeeded {
                        tree.repaint()
                        updateBitfieldTable()
                    }
                }
            }.exceptionally {
                null
            }
        }
    }

    /** 同步/重启动态刷新定时器 */
    fun syncTimer() {
        refreshTimer?.stop()
        refreshTimer = null

        val hasTargets = autoRefreshAll || autoRefreshPaths.isNotEmpty()
        if (!hasTargets) return

        val interval = (1000 / autoRefreshFreq.coerceIn(1, 10)).coerceIn(100, 2000)
        refreshTimer = Timer(interval) {
            refreshAuto()
        }.apply {
            isRepeats = true
            start()
        }
    }

    override fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
        stateListenerRemover?.invoke()
        stateListenerRemover = null
    }

    // ---------- 树节点与渲染器 ----------

    class PeripheralTreeNode(val peripheral: SvdPeripheral) : DefaultMutableTreeNode(peripheral)
    class RegisterTreeNode(val register: SvdRegister) : DefaultMutableTreeNode(register)

    private inner class RegisterTreeCellRenderer : TreeCellRenderer {
        private val regPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        private val autoCheckBox = JBCheckBox().apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2)
        }
        private val coloredComponent = SimpleColoredComponent().apply {
            isOpaque = false
            iconTextGap = JBUI.scale(4)
        }

        init {
            regPanel.add(autoCheckBox, BorderLayout.WEST)
            regPanel.add(coloredComponent, BorderLayout.CENTER)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            when (value) {
                is PeripheralTreeNode -> {
                    autoCheckBox.isVisible = false
                    coloredComponent.clear()
                    // 彻底移除文件夹图标，使用纯净的 IDE 折叠三角 Handles
                    coloredComponent.icon = null
                    val p = value.peripheral
                    coloredComponent.append(p.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    coloredComponent.append("  0x%08X".format(Locale.ROOT, p.baseAddress), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    coloredComponent.append(" (${p.registers.size})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (!p.description.isNullOrBlank()) {
                        coloredComponent.append(" - ${p.description}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }
                    if (selected) {
                        regPanel.isOpaque = true
                        regPanel.background = UIUtil.getTreeSelectionBackground(hasFocus)
                    } else {
                        regPanel.isOpaque = false
                    }
                    return regPanel
                }
                is RegisterTreeNode -> {
                    val reg = value.register
                    val isChecked = isAutoRefresh(reg.path)
                    autoCheckBox.isVisible = true
                    autoCheckBox.isSelected = isChecked
                    autoCheckBox.toolTipText = if (isChecked) "已开启动态自动刷新" else "点击开启此寄存器动态自动刷新"

                    coloredComponent.clear()
                    // 移除冗余图标，对标独立软件：复选框 + 寄存器名 + 地址 + 实时值
                    coloredComponent.icon = null
                    coloredComponent.append(reg.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    coloredComponent.append("  0x%08X".format(Locale.ROOT, reg.address), SimpleTextAttributes.GRAYED_ATTRIBUTES)

                    val curVal = registerValues[reg.path]
                    if (curVal != null) {
                        val valColor = JBColor(0x007ACC, 0x4EC9B0)
                        val valAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, valColor)
                        coloredComponent.append(" = 0x%08X".format(Locale.ROOT, curVal), valAttr)
                    } else {
                        coloredComponent.append(" = —", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }

                    if (!reg.description.isNullOrBlank()) {
                        coloredComponent.append("  (${reg.description})", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }

                    if (selected) {
                        regPanel.isOpaque = true
                        regPanel.background = UIUtil.getTreeSelectionBackground(hasFocus)
                    } else {
                        regPanel.isOpaque = false
                    }
                    return regPanel
                }
                else -> {
                    autoCheckBox.isVisible = false
                    coloredComponent.clear()
                    coloredComponent.icon = null
                    coloredComponent.append(value.toString())
                    regPanel.isOpaque = selected
                    return regPanel
                }
            }
        }
    }
}
