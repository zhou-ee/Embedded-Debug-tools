package org.embedded.monitor.watch

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.AnActionButton
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.cmake.ElfAutoResolver
import org.embedded.monitor.settings.EmbeddedMonitorSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.math.roundToInt

class LiveWatchToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "实时变量监视"
        toolWindow.title = "实时变量监视"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "实时变量监视"
        toolWindow.title = "实时变量监视"
        val panel = LiveWatchPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "实时变量监视", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 监视列表行模型数据（保留以保证历史测试与数据兼容性）。
 */
data class WatchRow(
    val key: String,
    val entryId: String,
    val depth: Int,
    val node: SymbolNode,
    val isTop: Boolean,
    val hasChildren: Boolean,
    var expanded: Boolean,
    val entry: WatchItem,
    val fullPath: String = "",
)

/**
 * 树节点数据。
 */
data class WatchNodeData(
    val entryId: String,
    val expr: String,
    val node: SymbolNode,
    val fullPath: String,
    val isTop: Boolean,
    var autoRefresh: Boolean,
    val entry: WatchItem,
)

/**
 * 实时监视树节点：支持顶层变量及嵌套结构体/数组/指针子成员。
 */
class LiveWatchTreeNode(val data: WatchNodeData) : DefaultMutableTreeNode(data) {
    val name: String get() = if (data.isTop) data.expr else data.node.name
    val typeName: String get() = data.node.typeName.ifEmpty { data.node.encoding }
    val address: Long get() = data.node.address
    val size: Int get() = data.node.size
    val encoding: String get() = data.node.encoding
    val isPointer: Boolean get() = data.node.isPointer || data.node.encoding.equals("pointer", ignoreCase = true)
    val isComposite: Boolean get() = (data.node.encoding.equals("composite", ignoreCase = true) || data.node.members.isNotEmpty()) && !isPointer

    var cachedBytes: ByteArray? = null
    var isNullPtr: Boolean = false
    var pointerAddress: Long? = null
    var physicalAddress: Long = 0L

    // CLion 求值子树专用（entry.evalOnly 为真时）：子节点的平台 XValue（嵌套展开）
    // 与呈现文本由懒展开填充；顶层节点的值文本在 entry.evalValue
    var evalXValue: Any? = null
    var evalValueText: String? = null
    var evalTypeText: String? = null
    var evalHasChildren: Boolean = true

    /**
     * 展开手柄可见性由本方法决定（DefaultTreeModel 默认 asksAllowsChildren=false
     * 直接查询 node.isLeaf）：ELF 节点维持原语义（无成员即叶子）；求值节点在
     * 可能含子项时必须报告"非叶子"，否则 childCount==0 会被判为叶子、展开手柄
     * 根本不出现（真机实测 2026-09-29：类型能显示但无法展开的根因）。
     */
    private val isPlatformEvalChild: Boolean
        get() = evalXValue != null || evalValueText != null || evalTypeText != null

    override fun isLeaf(): Boolean = when {
        // 求值型顶层：有求值结果且可能含子项 → 可展开
        data.entry.evalOnly && data.isTop -> data.entry.evalValue == null || !evalHasChildren
        // 平台求值子节点（computeChildren 懒展开产物，无内存地址，携带平台 XValue）
        data.entry.evalOnly && isPlatformEvalChild -> !evalHasChildren
        // 混合升级的 ELF 成员节点（真实地址，走标准字节渲染）：标准语义
        else -> data.node.members.isEmpty()
    }
}

/**
 * 实时变量树数据切片与动态指针解引用处理器。
 * 抽离为纯对象以支持独立单元测试与深层指针多层切片解引用。
 */
object LiveWatchTreeUpdater {
    @JvmOverloads
    fun updateChildBytesRecursively(
        node: LiveWatchTreeNode,
        currentBytes: ByteArray?,
        baseAddress: Long,
        isNullPtr: Boolean,
        parentPtrAddress: Long?,
        dynamicTargets: MutableList<AgentService.DynamicWatchTarget>,
        isExpanded: (LiveWatchTreeNode) -> Boolean,
        watchValueProvider: (String) -> ByteArray?,
        onNodeChanged: ((LiveWatchTreeNode) -> Unit)? = null,
    ) {
        val oldBytes = node.cachedBytes
        val oldIsNull = node.isNullPtr
        val oldPtrAddr = node.pointerAddress
        val oldPhysAddr = node.physicalAddress

        fun checkNotifyChanged() {
            val changed = (oldIsNull != node.isNullPtr) ||
                (oldPtrAddr != node.pointerAddress) ||
                (oldPhysAddr != node.physicalAddress) ||
                !oldBytes.contentEquals(node.cachedBytes)
            if (changed) {
                onNodeChanged?.invoke(node)
            }
        }

        node.isNullPtr = isNullPtr
        if (isNullPtr) {
            node.cachedBytes = null
            node.pointerAddress = null
            node.physicalAddress = 0L
            checkNotifyChanged()
            for (j in 0 until node.childCount) {
                val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
                updateChildBytesRecursively(sub, null, 0L, isNullPtr = true, parentPtrAddress = null, dynamicTargets = dynamicTargets, isExpanded = isExpanded, watchValueProvider = watchValueProvider, onNodeChanged = onNodeChanged)
            }
            return
        }

        // 计算当前节点的物理内存地址（用于示波器、写内存及查看数组）
        node.physicalAddress = if (node.data.isTop) {
            node.data.entry.address
        } else if (parentPtrAddress != null && parentPtrAddress >= 0x1000L) {
            parentPtrAddress + node.address
        } else {
            node.address
        }

        // 1. 获取本节点自身切片 cachedBytes（若传入数据为空，保留现有有效缓存避免闪烁）
        if (currentBytes != null && currentBytes.isNotEmpty()) {
            if (node.data.isTop) {
                node.cachedBytes = currentBytes
            } else {
                val offset = (node.address - baseAddress).toInt()
                val size = node.size
                if (offset >= 0 && size > 0 && offset + size <= currentBytes.size) {
                    node.cachedBytes = currentBytes.copyOfRange(offset, offset + size)
                } else {
                    node.cachedBytes = null
                }
            }
        }

        // 2. 指针节点处理
        if (node.isPointer) {
            val chunk = node.cachedBytes
            if (chunk != null && chunk.size >= 4) {
                val ptrAddr = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val ptrAddrChanged = (node.pointerAddress != null && node.pointerAddress != ptrAddr)
                node.pointerAddress = ptrAddr
                if (ptrAddr == 0L || ptrAddr < 0x1000L) {
                    // 空指针或非法低地址：子成员标记为 isNullPtr，不注册采样，防御非法低地址
                    checkNotifyChanged()
                    for (j in 0 until node.childCount) {
                        val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
                        updateChildBytesRecursively(sub, null, 0L, isNullPtr = true, parentPtrAddress = null, dynamicTargets = dynamicTargets, isExpanded = isExpanded, watchValueProvider = watchValueProvider, onNodeChanged = onNodeChanged)
                    }
                    return
                }

                checkNotifyChanged()

                // ptrAddr >= 0x1000L：检查树节点是否处于展开状态
                if (isExpanded(node)) {
                    val pointeeSize = if (node.data.node.pointeeSize > 0) {
                        node.data.node.pointeeSize
                    } else {
                        val maxMember = node.data.node.members.maxOfOrNull { (it.address + it.size).toInt() } ?: 0
                        maxMember.coerceAtLeast(4)
                    }.coerceIn(1, 4096)

                    val targetId = "ptr_0x%08X".format(Locale.ROOT, ptrAddr)
                    dynamicTargets.add(
                        AgentService.DynamicWatchTarget(
                            id = targetId,
                            address = ptrAddr,
                            size = pointeeSize,
                            autoRefresh = node.data.entry.autoRefresh,
                        )
                    )

                    val pointedBytes = watchValueProvider(targetId)
                    for (j in 0 until node.childCount) {
                        val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
                        // 若指针指向地址改变，子成员及深层后代旧数据必须失效清空；若未变且 pointedBytes 为空，则保持子节点缓存防闪烁
                        if (ptrAddrChanged) {
                            sub.cachedBytes = null
                            clearDescendantsCachedBytes(sub)
                        }
                        updateChildBytesRecursively(
                            node = sub,
                            currentBytes = pointedBytes,
                            baseAddress = 0L, // 指针子成员以 0L 为基准相对偏移切片
                            isNullPtr = false,
                            parentPtrAddress = ptrAddr,
                            dynamicTargets = dynamicTargets,
                            isExpanded = isExpanded,
                            watchValueProvider = watchValueProvider,
                            onNodeChanged = onNodeChanged,
                        )
                    }
                } else {
                    // 指针未展开：不发起深层采样以节省带宽，子成员清空缓存
                    for (j in 0 until node.childCount) {
                        val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
                        sub.cachedBytes = null
                        clearDescendantsCachedBytes(sub)
                        updateChildBytesRecursively(sub, null, 0L, isNullPtr = false, parentPtrAddress = ptrAddr, dynamicTargets = dynamicTargets, isExpanded = isExpanded, watchValueProvider = watchValueProvider, onNodeChanged = onNodeChanged)
                    }
                }
            } else {
                node.pointerAddress = null
                checkNotifyChanged()
                for (j in 0 until node.childCount) {
                    val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
                    sub.cachedBytes = null
                    clearDescendantsCachedBytes(sub)
                    updateChildBytesRecursively(sub, null, 0L, isNullPtr = false, parentPtrAddress = null, dynamicTargets = dynamicTargets, isExpanded = isExpanded, watchValueProvider = watchValueProvider, onNodeChanged = onNodeChanged)
                }
            }
            return
        }

        checkNotifyChanged()

        // 3. 非指针结构体/复合节点：子成员继续从当前 currentBytes 切片，基准地址仍为 baseAddress
        for (j in 0 until node.childCount) {
            val sub = node.getChildAt(j) as? LiveWatchTreeNode ?: continue
            updateChildBytesRecursively(sub, currentBytes, baseAddress, isNullPtr = false, parentPtrAddress = parentPtrAddress, dynamicTargets = dynamicTargets, isExpanded = isExpanded, watchValueProvider = watchValueProvider, onNodeChanged = onNodeChanged)
        }
    }

    private fun clearDescendantsCachedBytes(parent: LiveWatchTreeNode) {
        for (j in 0 until parent.childCount) {
            val child = parent.getChildAt(j) as? LiveWatchTreeNode ?: continue
            child.cachedBytes = null
            clearDescendantsCachedBytes(child)
        }
    }
}

/**
 * CLion 原生样式的树形渲染器：`[Checkbox] [Icon] name = {Type} value`。
 * 在每个变量的前面展示精致复选框，方便随时勾选/取消勾选，且与 UI 完美融合。
 */
class LiveWatchTreeCellRenderer @JvmOverloads constructor(
    private val service: AgentService? = null,
    private val freqProvider: () -> Int = { 5 },
) : TreeCellRenderer {
    private val panel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(1, 2, 1, 2)
    }
    private val checkBox = JBCheckBox().apply {
        isOpaque = false
        margin = JBUI.insets(0, 0, 0, 0)
    }
    private val colored = object : SimpleColoredComponent() {
        init {
            isOpaque = false
            iconTextGap = JBUI.scale(4)
            setPaintFocusBorder(false)
        }
    }

    init {
        panel.add(checkBox, BorderLayout.WEST)
        panel.add(colored, BorderLayout.CENTER)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val treeNode = value as? LiveWatchTreeNode ?: return panel
        val data = treeNode.data
        val node = data.node

        val freq = freqProvider()
        // 1. 复选框状态：仅顶层变量展示复选框，子成员隐藏（保持左侧干净自然缩进）
        if (data.isTop) {
            checkBox.isVisible = true
            checkBox.isSelected = data.autoRefresh
            checkBox.toolTipText = if (data.autoRefresh) "运行时自动刷新 (${freq}Hz) - 单击禁用" else "仅暂停时刷新 - 单击启用 ${freq}Hz 自动刷新"
        } else {
            checkBox.isVisible = false
        }

        // 2. 节点图标与彩色文本
        colored.clear()
        // CLion 求值型监视子树：无内存地址，展示 IDE 调试器求值结果。
        // 顶层显示表达式与求值文本；懒展开的子节点显示成员名与各自呈现文本
        // 平台求值子节点 = 懒展开产物（携带平台 XValue）；混合升级的 ELF 成员
        // 节点有真实地址与字节，必须走标准渲染（按内存偏移显示实时值）
        val isPlatformEvalChild = data.entry.evalOnly && !data.isTop && treeNode.evalXValue != null
        if ((data.isTop || isPlatformEvalChild) && data.entry.evalOnly) {
            colored.icon = AllIcons.Debugger.EvaluateExpression
            colored.append(treeNode.name, if (data.isTop) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (data.isTop) {
                colored.append("  ⟦CLion 求值⟧", SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            val v = if (data.isTop) data.entry.evalValue else treeNode.evalValueText
            if (!data.isTop) {
                treeNode.evalTypeText?.let {
                    colored.append(" {$it}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                }
            }
            colored.append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            when {
                v == null -> colored.append(
                    if (service?.engineState == "halted") "…" else "待断点求值",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
                v.startsWith("<") -> colored.append(v, SimpleTextAttributes.ERROR_ATTRIBUTES)
                else -> colored.append(v, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            // 混合升级：指针结果已固定地址实时监视（运行时按地址持续读取）
            if (data.isTop && data.entry.address >= 0x1000L) {
                val liveBytes = treeNode.cachedBytes
                val isComposite = data.node.members.isNotEmpty()
                if (!isComposite && liveBytes != null && liveBytes.isNotEmpty()) {
                    val live = WatchValueFormatter.formatNativeValue(data.node, liveBytes)
                    if (live.isNotEmpty()) {
                        colored.append("  [实时 $live]", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
                colored.append(
                    "  ⏱ 实时@0x%s".format(java.util.Locale.ROOT, data.entry.address),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
            panel.toolTipText = if (data.isTop) {
                "${data.expr} · 由 CLion 原生调试器求值（复用 IDE 的 GDB）；" +
                    if (data.entry.address >= 0x1000L) "已升级为固定地址实时监视，运行时持续读取 0x%s，下次断点自动重求值地址"
                        .format(java.util.Locale.ROOT, data.entry.address)
                    else "仅断点暂停时刷新；结构体/指针类结果可展开"
            } else {
                "${treeNode.name} · ${treeNode.evalTypeText ?: ""} · 由 CLion 原生调试器求值"
            }
            if (selected) {
                panel.isOpaque = true
                panel.background = UIUtil.getTreeSelectionBackground(hasFocus)
                colored.foreground = UIUtil.getTreeSelectionForeground(hasFocus)
            } else {
                panel.isOpaque = false
                colored.foreground = tree.foreground
            }
            return panel
        }
        colored.icon = when {
            treeNode.isPointer -> AllIcons.Nodes.Annotationtype
            treeNode.isComposite -> AllIcons.Nodes.Class
            data.isTop -> AllIcons.Debugger.Watch
            else -> AllIcons.Debugger.Db_primitive
        }

        colored.append(treeNode.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        colored.append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        val typeDisplay = treeNode.typeName
        colored.append("{$typeDisplay}", SimpleTextAttributes.GRAY_ATTRIBUTES)

        // 指针节点显示当前指向:g_chassis_ptr._ctx.* 与 g_chassis_ctx_ptr.* 这类同名成员子树一眼可辨
        if (treeNode.isPointer && (treeNode.pointerAddress ?: 0L) >= 0x1000L) {
            colored.append("  → 0x%08X".format(Locale.ROOT, treeNode.pointerAddress), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        if (treeNode.isNullPtr) {
            colored.append(" <nullptr>", SimpleTextAttributes.GRAY_ATTRIBUTES)
        } else if (!treeNode.isComposite || treeNode.isPointer) {
            val bytes = treeNode.cachedBytes
            val valText = if (bytes != null && bytes.isNotEmpty()) {
                WatchValueFormatter.formatNativeValue(node, bytes)
            } else {
                when (service?.engineState) {
                    "running", "halted" -> "…"
                    else -> ""
                }
            }
            if (valText.isNotEmpty()) {
                colored.append(" $valText", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        // 3. 选中背景与前景色无缝融合
        if (selected) {
            panel.isOpaque = true
            panel.background = UIUtil.getTreeSelectionBackground(hasFocus)
            colored.foreground = UIUtil.getTreeSelectionForeground(hasFocus)
        } else {
            panel.isOpaque = false
            colored.foreground = tree.foreground
        }

        val autoRefreshNote = if (data.autoRefresh) "运行时自动刷新 (${freq}Hz)" else "仅暂停时刷新"
        val effectiveAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address
        panel.toolTipText = "${data.fullPath}: ${treeNode.typeName} @ 0x%08X (${treeNode.size} B) · $autoRefreshNote"
            .format(Locale.ROOT, effectiveAddr)

        return panel
    }
}

/**
 * 实时变量监视面板：对标 CLion 原生 Watches 调试界面。
 * 包含：顶部内联求值输入栏 + 左侧垂直原生工具条 + 原生树形监视视窗 + 底部紧凑状态条。
 */
class LiveWatchPanel(private val project: Project) :
    JBPanel<LiveWatchPanel>(BorderLayout()), Disposable {

    private val service = AgentService.getInstance(project)
    private val settings get() = EmbeddedMonitorSettings.getInstance(project)

    var watchRefreshFreq: Int = runCatching {
        EmbeddedMonitorSettings.snapWatchFreq(EmbeddedMonitorSettings.getInstance(project).watchRefreshFreq)
    }.getOrDefault(5)
        private set

    val freqCombo = JComboBox(EmbeddedMonitorSettings.VALID_WATCH_FREQS.map { "$it Hz" }.toTypedArray())

    private val rootNode = DefaultMutableTreeNode("Root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val evaluateField = JBTextField()
    private val statusLabel = JBLabel("就绪")

    private val uiTimer = Timer((1000.0 / watchRefreshFreq).roundToInt()) { tick() }
    private var elfCheckCounter = 0

    private var cachedItemIds: List<String> = emptyList()
    private var cachedItemElfRevision: Long = 0
    private var cachedEvalRevision: Long = -1

    private val watchDataListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            // 面板不可见时跳过树遍历/重绘（数据仍在 service 缓冲，显示后由
            // tick 自然刷新）——这是最高频的 EDT 刷新路径，与 Scope/Register
            // 面板的 isShowing 守卫保持一致
            if (!Disposer.isDisposed(this) && isShowing()) {
                updateNodeBytes(rootNode)
                // 求值型顶层节点首次拿到结果后 isLeaf 语义变化（叶子→可展开），
                // 必须通知模型重查结构，展开手柄才会出现
                for (i in 0 until rootNode.childCount) {
                    val child = rootNode.getChildAt(i) as? LiveWatchTreeNode ?: continue
                    if (child.data.entry.evalOnly && child.evalXValue == null &&
                        child.data.entry.evalXValue != null
                    ) {
                        child.evalXValue = child.data.entry.evalXValue
                        treeModel.nodeStructureChanged(child)
                    }
                }
                tree.repaint()
                updateStatusLabel(service.engineState)
            }
        }
    }

    init {
        border = JBUI.Borders.empty(2)

        runCatching {
            watchRefreshFreq = EmbeddedMonitorSettings.snapWatchFreq(settings.watchRefreshFreq)
        }

        freqCombo.apply {
            selectedItem = "$watchRefreshFreq Hz"
            toolTipText = "实时变量刷新频率（2Hz / 5Hz / 10Hz / 15Hz）"
            isFocusable = false
            addActionListener {
                val selected = selectedItem?.toString()?.substringBefore(" ")?.toIntOrNull() ?: 5
                val snapped = EmbeddedMonitorSettings.snapWatchFreq(selected)
                if (snapped != watchRefreshFreq) {
                    setWatchRefreshFreq(snapped)
                }
            }
        }

        val initialInterval = (1000.0 / watchRefreshFreq).roundToInt().coerceIn(66, 500)
        uiTimer.delay = initialInterval
        uiTimer.initialDelay = initialInterval

        add(buildCenter(), BorderLayout.CENTER)
        add(buildBottomStatus(), BorderLayout.SOUTH)

        setupTreeInteraction()

        service.addWatchDataListener(watchDataListener)
        uiTimer.start()
        // 首次打开：自动探测 ELF 并拉起 agent
        ApplicationManager.getApplication().executeOnPooledThread {
            service.autoDetectElf()
        }
    }

    /** 动态调节监视刷新频率（仅支持 2Hz / 5Hz / 10Hz / 15Hz），同步更新后端采样与 UI 刷新定时器。 */
    fun setWatchRefreshFreq(freq: Int) {
        val snapped = EmbeddedMonitorSettings.snapWatchFreq(freq)
        watchRefreshFreq = snapped
        if (freqCombo.selectedItem != "$snapped Hz") {
            freqCombo.selectedItem = "$snapped Hz"
        }
        settings.update { s -> s.watchRefreshFreq = snapped }
        service.setWatchFreq(snapped.toDouble())
        val intervalMs = (1000.0 / snapped).roundToInt().coerceIn(66, 500)
        uiTimer.delay = intervalMs
        uiTimer.initialDelay = intervalMs
        uiTimer.restart()
        updateStatusLabel(service.engineState)
        tree.repaint()
    }

    /**
     * 顶部内联求值输入栏：支持「对表达式求值(Enter)或添加监视(Ctrl+Shift+Enter)」。
     */
    private fun buildEvaluateBar(): JComponent {
        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            background = UIUtil.getTreeBackground()
        }

        evaluateField.apply {
            emptyText.text = "对表达式求值(Enter)或添加监视(Ctrl+Shift+Enter)"
            font = JBFont.regular()
            border = JBUI.Borders.empty(4, 8)
            background = UIUtil.getTreeBackground()
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> {
                            val text = evaluateField.text.trim()
                            if (text.isNotEmpty()) {
                                evaluateField.text = ""
                                addExpression(text)
                                e.consume()
                            }
                        }
                        KeyEvent.VK_ESCAPE -> {
                            evaluateField.text = ""
                            tree.requestFocusInWindow()
                            e.consume()
                        }
                    }
                }
            })
        }
        bar.add(evaluateField, BorderLayout.CENTER)

        val evalActionBtn = JButton(AllIcons.Debugger.EvaluateExpression).apply {
            toolTipText = "对表达式求值或添加监视 (Enter / Ctrl+Shift+Enter)"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            preferredSize = Dimension(28, 24)
            addActionListener {
                val text = evaluateField.text.trim()
                if (text.isNotEmpty()) {
                    evaluateField.text = ""
                    addExpression(text)
                } else {
                    promptAddExpression()
                }
            }
        }
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(freqCombo)
            add(evalActionBtn)
        }
        bar.add(rightPanel, BorderLayout.EAST)

        return bar
    }

    /**
     * 构建核心视图：包含顶部求值栏、左侧原生垂直工具栏、中间树形视窗。
     */
    private fun buildCenter(): JComponent {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(22)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = LiveWatchTreeCellRenderer(service) { watchRefreshFreq }

        // CLion 求值型节点懒展开：展开时经平台 computeChildren 拉取子项
        // （结构体/指针类求值结果可逐层下钻），插入后通知模型刷新
        tree.addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
            override fun treeWillExpand(e: javax.swing.event.TreeExpansionEvent) {
                val node = e.path.lastPathComponent as? LiveWatchTreeNode ?: return
                if (!node.data.entry.evalOnly) return
                if (node.childCount > 0) return
                // 顶层可回退到条目的 XValue；子节点必须有自身的 XValue——
                // 否则展开标量成员会把整个结构体的子项再挂一遍（真机实测：
                // seq 下又出现 _ctx/seq，表现为"解析成了 pyro::wl_chassis_t *"）
                val xv = if (node.data.isTop) {
                    node.evalXValue ?: node.data.entry.evalXValue
                } else {
                    node.evalXValue
                } ?: return
                ApplicationManager.getApplication().executeOnPooledThread {
                    val kids = service.computeEvalChildren(xv) ?: return@executeOnPooledThread
                    val built = kids.mapNotNull { (name, childX) ->
                        val pres = org.embedded.monitor.agent.ClionEvalBridge.capturePresentation(
                            childX as com.intellij.xdebugger.frame.XValue,
                        )
                        if (pres == null) return@mapNotNull null
                        LiveWatchTreeNode(
                            WatchNodeData(
                                entryId = node.data.entryId,
                                expr = name,
                                node = org.embedded.monitor.agent.SymbolNode(
                                    name = name,
                                    typeName = pres.typeText ?: "",
                                    address = 0,
                                    size = 0,
                                    encoding = "eval",
                                ),
                                fullPath = "${node.data.fullPath}.$name",
                                isTop = false,
                                autoRefresh = false,
                                entry = node.data.entry,
                            )
                        ).apply {
                            evalXValue = childX
                            evalValueText = pres.valueText
                            evalTypeText = pres.typeText
                            evalHasChildren = pres.hasChildren
                        }
                    }
                    com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                        if (com.intellij.openapi.util.Disposer.isDisposed(this@LiveWatchPanel)) return@invokeLaterIfNeeded
                        for (c in built) node.add(c)
                        treeModel.nodeStructureChanged(node)
                    }
                }
            }

            override fun treeWillCollapse(e: javax.swing.event.TreeExpansionEvent) {}
        })

        tree.emptyText.text = "尚未添加变量监视"
        tree.emptyText.appendSecondaryText(
            "在上方输入表达式并按 Enter，或在代码中右键变量 → 添加到实时变量监视",
            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            null,
        )

        val decorator = ToolbarDecorator.createDecorator(tree)
            .setToolbarPosition(ActionToolbarPosition.LEFT)
            .setAddAction {
                evaluateField.requestFocusInWindow()
            }
            .setRemoveAction {
                removeSelectedNode()
            }
            .addExtraAction(object : AnActionButton("启动监视", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = startMonitoring()
                override fun isEnabled(): Boolean {
                    val s = service.engineState
                    return s != "running" && s != "connecting" && s != "halted"
                }
            })
            .addExtraAction(object : AnActionButton("停止监视", AllIcons.Actions.Suspend) {
                override fun actionPerformed(e: AnActionEvent) = service.disconnectEngine()
                override fun isEnabled(): Boolean {
                    val s = service.engineState
                    return s == "running" || s == "halted" || s == "connecting"
                }
            })
            .addExtraAction(object : AnActionButton("从符号树添加…", AllIcons.Actions.ListFiles) {
                override fun actionPerformed(e: AnActionEvent) = promptPickSymbols()
            })
            .addExtraAction(object : AnActionButton("ELF 符号表管理…", AllIcons.Nodes.Artifact) {
                override fun actionPerformed(e: AnActionEvent) = showElfManagementPopup(e.inputEvent?.component ?: this@LiveWatchPanel)
            })
            .addExtraAction(object : AnActionButton("全部清空", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) = service.clearWatches()
            })

        val treeDecoratedPanel = decorator.createPanel()

        val mainPanel = JPanel(BorderLayout()).apply {
            add(buildEvaluateBar(), BorderLayout.NORTH)
            add(treeDecoratedPanel, BorderLayout.CENTER)
        }

        return mainPanel
    }

    private fun buildBottomStatus(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 8)
        }
        statusLabel.font = JBFont.small()
        panel.add(statusLabel, BorderLayout.WEST)
        return panel
    }

    private fun setupTreeInteraction() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val bounds = tree.getPathBounds(path) ?: return
                val node = path.lastPathComponent as? LiveWatchTreeNode ?: return

                if (e.button == MouseEvent.BUTTON1) {
                    // 点击行首复选框判定：仅顶层变量展示复选框，有效点击区域从 bounds.x 开始的前 22 像素
                    if (node.data.isTop) {
                        val clickXInRow = e.x - bounds.x
                        if (clickXInRow in 0..JBUI.scale(22)) {
                            val newVal = !node.data.autoRefresh
                            node.data.autoRefresh = newVal
                            service.setWatchAutoRefresh(node.data.entryId, newVal)
                            tree.selectionPath = path
                            tree.repaint()
                            e.consume()
                            return
                        }
                    }
                } else if (e.button == MouseEvent.BUTTON3) {
                    tree.selectionPath = path
                    buildContextMenu(node).show(tree, e.x, e.y)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val bounds = tree.getPathBounds(path) ?: return
                    val node = path.lastPathComponent as? LiveWatchTreeNode ?: return
                    // 保护：双击行首复选框区域严禁触发修改值或展开/折叠
                    if (node.data.isTop && (e.x - bounds.x) in 0..JBUI.scale(22)) return
                    val hasChildren = node.childCount > 0 || node.data.node.members.isNotEmpty()
                    if (node.isComposite || hasChildren) {
                        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                    } else {
                        promptEditValue(node)
                    }
                }
            }
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val path = tree.selectionPath ?: return
                val node = path.lastPathComponent as? LiveWatchTreeNode ?: return
                when (e.keyCode) {
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
                        service.removeWatch(node.data.entryId)
                        e.consume()
                    }
                    KeyEvent.VK_SPACE -> {
                        val newVal = !node.data.autoRefresh
                        node.data.autoRefresh = newVal
                        service.setWatchAutoRefresh(node.data.entryId, newVal)
                        tree.repaint()
                        e.consume()
                    }
                    KeyEvent.VK_ENTER, KeyEvent.VK_F2 -> {
                        val hasChildren = node.childCount > 0 || node.data.node.members.isNotEmpty()
                        if (node.isComposite || hasChildren) {
                            if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                        } else {
                            promptEditValue(node)
                        }
                        e.consume()
                    }
                }
            }
        })

        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                updateNodeBytes(rootNode)
                tree.repaint()
            }
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                updateNodeBytes(rootNode)
                tree.repaint()
            }
        })
    }

    /**
     * 周期调度：状态同步、ELF 变动检查与数据刷新。
     */
    private fun tick() {
        // 工具窗隐藏时跳过整轮刷新（树遍历 + repaint 是 EDT 重活，ELF 检查
        // 与设置同步推迟到再次可见的首拍，与 Scope/Register 面板守卫一致）
        if (!isShowing()) return
        val settingFreq = EmbeddedMonitorSettings.snapWatchFreq(settings.watchRefreshFreq)
        if (settingFreq != watchRefreshFreq) {
            setWatchRefreshFreq(settingFreq)
        }
        service.syncDebugAndEngineState()
        val state = service.engineState
        updateStatusLabel(state)

        val currentItems = service.watchItems.toList()
        if (hasItemsStructureChanged(currentItems)) {
            rebuildTree()
        } else {
            // 结构未变：更新各节点的 cachedBytes 并触发局部 repaint
            updateNodeBytes(rootNode)
            tree.repaint()
        }

        if (++elfCheckCounter % 10 == 0) service.checkElfRefresh()
    }

    private fun updateStatusLabel(state: String) {
        val elfName = service.elfPath?.let { File(it).name } ?: "ELF 未加载"
        val elfInfo = if (service.elfLoaded) "$elfName (${service.elfVariableCount()} 变量)" else elfName
        val actualRateText = if (service.actualWatchRateHz > 0.0 && state == "running") {
            " (实测 ${"%.1f".format(Locale.ROOT, service.actualWatchRateHz)}Hz)"
        } else ""
        statusLabel.text = when (state) {
            "running" -> "● 监视中 · ${service.engineDescription} @ ${watchRefreshFreq}Hz$actualRateText · $elfInfo" +
                (service.lastEngineError?.let { " · 错误: ${it.take(80)}" } ?: "")
            "halted" -> "⏸ 目标已暂停（单步/断点请用 CLion 原生调试器） · $elfInfo"
            "connecting" -> "⏳ 连接中… · $elfInfo"
            else -> "○ 未连接 · $elfInfo" +
                (service.lastEngineError?.let { " · 错误: ${it.take(80)}" } ?: "")
        }
        statusLabel.foreground = when (state) {
            "running" -> JBColor(java.awt.Color(0x2E, 0x7D, 0x32), java.awt.Color(0x6A, 0xC4, 0x6E))
            "halted" -> JBColor(java.awt.Color(0xE6, 0x51, 0x00), java.awt.Color(0xFF, 0xB7, 0x4D))
            "connecting" -> JBColor(java.awt.Color(0x15, 0x65, 0xC0), java.awt.Color(0x64, 0xB5, 0xF6))
            else -> JBColor.GRAY
        }
    }

    private fun hasItemsStructureChanged(currentItems: List<WatchItem>): Boolean {
        if (currentItems.size != cachedItemIds.size) return true
        for (i in currentItems.indices) {
            if (currentItems[i].id != cachedItemIds[i]) return true
        }
        if (service.elfLoaded && cachedItemElfRevision != service.elfVariableCount().toLong()) return true
        // 求值型混合升级（地址重定基）会改变既有条目的成员结构
        if (cachedEvalRevision != service.watchStructureRevision) return true
        return false
    }

    /**
     * 重建树结构并保持此前已展开节点的展开状态。
     */
    fun rebuildTree() {
        val currentItems = service.watchItems.toList()
        cachedItemIds = currentItems.map { it.id }
        cachedItemElfRevision = service.elfVariableCount().toLong()
        cachedEvalRevision = service.watchStructureRevision

        // 1. 保存当前展开的路径
        val expandedPaths = mutableSetOf<String>()
        fun saveExpanded(path: TreePath) {
            val last = path.lastPathComponent as? LiveWatchTreeNode ?: return
            if (tree.isExpanded(path)) {
                expandedPaths.add(last.data.fullPath)
            }
            for (i in 0 until last.childCount) {
                val child = last.getChildAt(i)
                saveExpanded(path.pathByAddingChild(child))
            }
        }
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i)
            saveExpanded(TreePath(arrayOf(rootNode, child)))
        }

        // 2. 清空并重建
        rootNode.removeAllChildren()
        for (item in currentItems) {
            val rootSymbol = item.node ?: SymbolNode(
                name = item.expr,
                typeName = item.typeName,
                address = item.address,
                size = item.size,
                encoding = item.encoding,
            )
            val topNode = LiveWatchTreeNode(
                WatchNodeData(
                    entryId = item.id,
                    expr = item.expr,
                    node = rootSymbol,
                    fullPath = item.expr,
                    isTop = true,
                    autoRefresh = item.autoRefresh,
                    entry = item,
                )
            )
            addMembersRecursively(topNode, rootSymbol, item.expr, item.id, item.autoRefresh, item)
            rootNode.add(topNode)
        }

        treeModel.reload()

        // 3. 恢复展开状态
        fun restoreExpanded(path: TreePath) {
            val last = path.lastPathComponent as? LiveWatchTreeNode ?: return
            if (expandedPaths.contains(last.data.fullPath)) {
                tree.expandPath(path)
            }
            for (i in 0 until last.childCount) {
                val child = last.getChildAt(i)
                restoreExpanded(path.pathByAddingChild(child))
            }
        }
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i)
            restoreExpanded(TreePath(arrayOf(rootNode, child)))
        }

        // 4. 初次注入最新字节
        updateNodeBytes(rootNode)
        tree.repaint()
    }

    private fun addMembersRecursively(
        parent: DefaultMutableTreeNode,
        node: SymbolNode,
        parentPath: String,
        entryId: String,
        autoRefresh: Boolean,
        entry: WatchItem,
    ) {
        for (m in node.members) {
            val childPath = if (m.name.startsWith("[")) "$parentPath${m.name}" else "$parentPath.${m.name}"
            val childNode = LiveWatchTreeNode(
                WatchNodeData(
                    entryId = entryId,
                    expr = childPath,
                    node = m,
                    fullPath = childPath,
                    isTop = false,
                    autoRefresh = autoRefresh,
                    entry = entry,
                )
            )
            parent.add(childNode)
            if (m.members.isNotEmpty()) {
                addMembersRecursively(childNode, m, childPath, entryId, autoRefresh, entry)
            }
        }
    }

    private fun updateNodeBytes(parent: TreeNode) {
        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? LiveWatchTreeNode ?: continue
            val topBytes = service.watchValue(child.data.entryId)
            LiveWatchTreeUpdater.updateChildBytesRecursively(
                node = child,
                currentBytes = topBytes,
                baseAddress = child.data.entry.address,
                isNullPtr = false,
                parentPtrAddress = null,
                dynamicTargets = dynamicTargets,
                isExpanded = { tree.isExpanded(TreePath(it.path)) },
                watchValueProvider = service::watchValue,
                onNodeChanged = { treeModel.nodeChanged(it) },
            )
        }
        service.updateDynamicWatchTargets(dynamicTargets)
    }

    private fun startMonitoring() {
        if (service.elfPath == null) {
            ApplicationManager.getApplication().executeOnPooledThread { service.autoDetectElf() }
        }
        service.connectEngine().whenComplete { _, err ->
            if (err != null && !service.isHaltedByDebug && service.engineState != "halted") {
                val msg = AgentService.extractError(err) ?: err.cause?.message ?: err.message ?: "未知错误"
                service.notify("启动监视失败: $msg")
            }
        }
    }

    private fun promptAddExpression() {
        val expr = Messages.showInputDialog(
            project,
            "支持：符号名 / a.b.c 成员链 / float @ 0x20000000 / 0x20000000:u32\n" +
                "复杂 C 表达式（强转/函数调用）：调试会话中自动由 CLion 原生调试器在断点暂停时求值",
            "添加实时变量监视",
            null,
        )?.trim() ?: return
        if (expr.isEmpty()) return
        addExpression(expr)
    }

    fun addExpression(expr: String) {
        val trimmed = expr.trim()
        val existingIndex = service.watchItems.indexOfFirst { it.expr.equals(trimmed, ignoreCase = true) }
        if (existingIndex >= 0) {
            service.notify("变量「$trimmed」已在实时变量监视列表中", com.intellij.notification.NotificationType.INFORMATION)
            selectTopNode(service.watchItems[existingIndex].id)
            return
        }
        service.addWatch(trimmed).whenComplete { item, err ->
            if (err != null) {
                service.notify("添加监视失败: ${err.message ?: "未知错误"}")
            } else if (item != null) {
                ApplicationManager.getApplication().invokeLater {
                    rebuildTree()
                    selectTopNode(item.id)
                }
            }
        }
    }

    private fun selectTopNode(entryId: String) {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as? LiveWatchTreeNode ?: continue
            if (child.data.entryId == entryId) {
                val path = TreePath(arrayOf(rootNode, child))
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
                break
            }
        }
    }

    private fun removeSelectedNode() {
        val path = tree.selectionPath ?: return
        val node = path.lastPathComponent as? LiveWatchTreeNode ?: return
        service.removeWatch(node.data.entryId)
    }

    private fun promptPickSymbols() {
        if (!service.elfLoaded) {
            Messages.showInfoMessage(project, "ELF 尚未加载，请先确认构建路径。", "符号树")
            return
        }
        val dialog = SymbolPickDialog(project, service)
        dialog.show()
    }

    private fun showElfManagementPopup(invoker: Component) {
        val popup = JPopupMenu("ELF 符号表管理")
        val currentElf = service.elfPath
        val currentName = if (currentElf != null) File(currentElf).name else "未加载"

        val statusItem = JMenuItem("当前: $currentName (${service.elfVariableCount()} 变量)").apply {
            isEnabled = false
            icon = AllIcons.Nodes.Artifact
        }
        popup.add(statusItem)
        popup.addSeparator()

        popup.add(JMenuItem("重载当前 ELF 符号表").apply {
            icon = AllIcons.Actions.Refresh
            addActionListener { reloadElf() }
        })
        popup.add(JMenuItem("自动探测工程 ELF").apply {
            icon = AllIcons.Actions.Find
            addActionListener {
                ApplicationManager.getApplication().executeOnPooledThread {
                    service.autoDetectElf()
                }
            }
        })

        // 候选扫描（递归目录 + CMake File API JSON 解析）是磁盘 IO，不能在
        // EDT 同步做：pooled 扫描完成后回到 EDT 构建菜单再弹出（EDT 不阻塞，
        // 弹窗出现的延迟等于扫描耗时，与原实现观感一致）
        popup.addSeparator()
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = runCatching { service.listElfCandidates() }.getOrElse { emptyList() }
            // 预计算标签与选中态（canonicalPath 也是磁盘 IO）
            val entries: List<Pair<String, ElfAutoResolver.Candidate>> = candidates.map { cand ->
                val isSelected = runCatching { cand.file.canonicalPath == currentElf }.getOrDefault(false)
                (if (isSelected) "✓ " else "  ") + cand.file.name + " (" + cand.file.parentFile.name + ")" to cand
            }
            com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
                if (!isShowing()) return@invokeLaterIfNeeded
                if (entries.isNotEmpty()) {
                    for ((label, cand) in entries) {
                        popup.add(JMenuItem(label).apply {
                            icon = AllIcons.FileTypes.Archive
                            addActionListener {
                                service.loadElf(cand.file, "手动切换")
                            }
                        })
                    }
                } else {
                    popup.add(JMenuItem("未探测到候选 ELF").apply { isEnabled = false })
                }
                popup.show(invoker, 0, invoker.height)
            }
        }
    }

    private fun reloadElf() {
        val path = service.elfPath
        if (path != null) {
            service.loadElf(File(path), "手动重载")
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                service.autoDetectElf()
            }
        }
    }

    private fun promptEditValue(treeNode: LiveWatchTreeNode) {
        if (treeNode.isComposite) return
        if (service.engineState != "running" && service.engineState != "halted") {
            Messages.showInfoMessage(project, "监视未连接，请先启动监视后再写内存。", "修改值")
            return
        }
        val currentBytes = treeNode.cachedBytes
        val currentVal = if (currentBytes != null) WatchValueFormatter.formatNativeValue(treeNode.data.node, currentBytes) else ""

        val fullPath = treeNode.data.fullPath
        val input = Messages.showInputDialog(
            project,
            "请输入新值（支持十进制或 0x 前缀十六进制）：",
            "修改变量值 - $fullPath",
            null,
            currentVal,
            null,
        )?.trim() ?: return

        val encoded = WatchValueFormatter.encodeValue(input, treeNode.encoding, treeNode.size)
        if (encoded == null) {
            Messages.showErrorDialog(project, "无法解析输入值「$input」为 ${treeNode.typeName} 类型", "写入失败")
            return
        }
        val targetAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address
        if (targetAddr < 0x1000L) {
            Messages.showErrorDialog(project, "无效的内存地址 (0x%08X)，无法写入".format(Locale.ROOT, targetAddr), "写入失败")
            return
        }
        service.writeMem(targetAddr, encoded).whenComplete { _, err ->
            ApplicationManager.getApplication().invokeLater {
                if (err != null) {
                    service.notify("写内存失败: ${err.message ?: "未知错误"}")
                } else {
                    service.notify("已写入 $fullPath = $input", com.intellij.notification.NotificationType.INFORMATION)
                }
            }
        }
    }

    private fun promptViewPointerAsArray(treeNode: LiveWatchTreeNode) {
        if (!treeNode.isPointer) {
            Messages.showInfoMessage(project, "该变量不是指针类型。", "以数组查看指针")
            return
        }
        if (service.engineState != "running" && service.engineState != "halted") {
            Messages.showInfoMessage(project, "监视未连接，请先启动监视以便读取指针值。", "以数组查看指针")
            return
        }
        val countStr = Messages.showInputDialog(
            project,
            "请输入数组元素个数 (1-10000):",
            "以数组查看指针",
            null,
            "16",
            null,
        )?.trim() ?: return
        val count = countStr.toIntOrNull()
        if (count == null || count <= 0 || count > 10000) {
            Messages.showErrorDialog(project, "元素个数必须在 1..10000 之间", "以数组查看指针")
            return
        }
        val cachedTarget = if (treeNode.pointerAddress != null && treeNode.pointerAddress!! >= 0x1000L) {
            treeNode.pointerAddress!!
        } else if (treeNode.cachedBytes != null && treeNode.cachedBytes!!.size >= 4) {
            ByteBuffer.wrap(treeNode.cachedBytes!!).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        } else {
            null
        }

        val onTargetResolved: (Long) -> Unit = { targetAddr ->
            if (targetAddr == 0L || targetAddr < 0x1000L) {
                ApplicationManager.getApplication().invokeLater {
                    service.notify("指针指向无效地址 (0x%08X)".format(Locale.ROOT, targetAddr))
                }
            } else {
                val elemSize = if (treeNode.data.node.pointeeSize > 0) treeNode.data.node.pointeeSize else 1
                val elemType = treeNode.data.node.pointeeType.ifEmpty { "uint8_t" }
                val elemEncoding = when {
                    treeNode.data.node.pointeeEncoding.isNotBlank() -> treeNode.data.node.pointeeEncoding
                    Regex("""(float|double|f32|f64)""", RegexOption.IGNORE_CASE).containsMatchIn(elemType) -> "float"
                    Regex("""^(u(8|16|32|64)|unsigned)""", RegexOption.IGNORE_CASE).containsMatchIn(elemType) -> "unsigned"
                    else -> "signed"
                }
                val maxN = minOf(count, maxOf(1, 4096 / elemSize))
                val members = ArrayList<SymbolNode>(maxN)
                for (i in 0 until maxN) {
                    members.add(
                        SymbolNode(
                            name = "[$i]",
                            typeName = elemType,
                            address = targetAddr + i.toLong() * elemSize,
                            size = elemSize,
                            encoding = elemEncoding,
                        )
                    )
                }
                val label = "*${treeNode.data.fullPath}[$maxN]"
                val arrayNode = SymbolNode(
                    name = label,
                    typeName = "$elemType[$maxN]",
                    address = targetAddr,
                    size = minOf(maxN * elemSize, 4096),
                    encoding = "composite",
                    members = members,
                )
                val newItem = WatchItem(
                    id = WatchValueFormatter.nextWatchId(),
                    expr = label,
                    address = targetAddr,
                    size = arrayNode.size,
                    encoding = "composite",
                    typeName = "$elemType[$maxN]",
                    autoRefresh = treeNode.data.autoRefresh,
                    node = arrayNode,
                )
                ApplicationManager.getApplication().invokeLater {
                    service.addWatchItemDirectly(newItem)
                    rebuildTree()
                    service.notify("已添加指针数组视图 $label @ 0x%08X".format(Locale.ROOT, targetAddr), com.intellij.notification.NotificationType.INFORMATION)
                }
            }
        }

        if (cachedTarget != null) {
            onTargetResolved(cachedTarget)
            return
        }

        val ptrAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address
        if (ptrAddr < 0x1000L) {
            Messages.showErrorDialog(project, "无效的指针变量地址 (0x%08X)".format(Locale.ROOT, ptrAddr), "以数组查看指针")
            return
        }
        val ptrSize = if (treeNode.size in 4..8) treeNode.size else 4
        service.readMem(ptrAddr, ptrSize).whenComplete { bytes, err ->
            if (err != null || bytes.size < 4) {
                ApplicationManager.getApplication().invokeLater {
                    service.notify("读取指针内存失败: ${err?.message ?: "字节数不足"}")
                }
                return@whenComplete
            }
            val targetAddr = if (ptrSize == 8 && bytes.size >= 8) {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
            } else {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            }
            onTargetResolved(targetAddr)
        }
    }

    private fun buildContextMenu(treeNode: LiveWatchTreeNode): JPopupMenu {
        val menu = JPopupMenu()

        // 1. 添加到示波器（叶子标量变量支持，必须拥有合法的物理内存地址）
        val physicalAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address
        if (!treeNode.isComposite && !treeNode.isPointer) {
            menu.add(JMenuItem("添加到示波器").apply {
                icon = AllIcons.General.Filter
                if (physicalAddr < 0x1000L) {
                    isEnabled = false
                    toolTipText = "指针尚未有效解引用或地址无效（0x%08X），无法添加到示波器".format(Locale.ROOT, physicalAddr)
                }
                addActionListener {
                    if (physicalAddr < 0x1000L) {
                        service.notify(
                            "无法添加到示波器：变量「${treeNode.data.fullPath}」地址无效或指针尚未有效解引用（当前地址 0x%08X），请等待目标机连接并展开后重试".format(Locale.ROOT, physicalAddr),
                            com.intellij.notification.NotificationType.WARNING,
                        )
                        return@addActionListener
                    }
                    val label = treeNode.data.fullPath
                    val added = service.addScopeVariable(label, physicalAddr, treeNode.size, treeNode.encoding)
                    if (added == null) {
                        service.notify("该地址已在示波通道列表中", com.intellij.notification.NotificationType.INFORMATION)
                    } else {
                        // 通知必须携带解析出的绝对地址：g_chassis_ptr._ctx.seq 与
                        // g_chassis_ctx_ptr.seq 这类同名子树的通道只有靠地址才能一眼区分
                        service.notify(
                            "已添加「$label」@ 0x%08X 到示波器".format(Locale.ROOT, physicalAddr),
                            com.intellij.notification.NotificationType.INFORMATION,
                        )
                    }
                }
            })
            menu.addSeparator()
        }

        // 2. 修改值（写内存）
        if (!treeNode.isComposite && physicalAddr >= 0x1000L) {
            menu.add(JMenuItem("修改值（写内存）…").apply {
                icon = AllIcons.Actions.Edit
                addActionListener { promptEditValue(treeNode) }
            })
        }

        // 3. 以数组查看指针
        if (treeNode.isPointer) {
            menu.add(JMenuItem("以数组查看指针…").apply {
                icon = AllIcons.Actions.ListFiles
                addActionListener { promptViewPointerAsArray(treeNode) }
            })
        }

        menu.addSeparator()

        // 4. 复制系列
        menu.add(JMenuItem("复制表达式").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(treeNode.data.fullPath), null)
            }
        })
        menu.add(JMenuItem("复制变量名").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(treeNode.name), null)
            }
        })
        menu.add(JMenuItem("复制值").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val bytes = treeNode.cachedBytes
                val valStr = if (bytes != null) WatchValueFormatter.formatNativeValue(treeNode.data.node, bytes) else ""
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(valStr), null)
            }
        })
        menu.add(JMenuItem("复制地址").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val effectiveAddr = if (treeNode.physicalAddress >= 0x1000L) treeNode.physicalAddress else treeNode.address
                val s = String.format(Locale.ROOT, "0x%08X", effectiveAddr)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null)
            }
        })

        menu.addSeparator()

        // 5. 自动刷新复选
        val refreshItem = javax.swing.JCheckBoxMenuItem("运行时自动刷新 (${watchRefreshFreq}Hz)", treeNode.data.autoRefresh).apply {
            addActionListener {
                val newVal = isSelected
                treeNode.data.autoRefresh = newVal
                service.setWatchAutoRefresh(treeNode.data.entryId, newVal)
                tree.repaint()
            }
        }
        menu.add(refreshItem)

        menu.addSeparator()

        // 6. 移除与清空
        menu.add(JMenuItem("从监视移除").apply {
            icon = AllIcons.General.Remove
            addActionListener { service.removeWatch(treeNode.data.entryId) }
        })
        menu.add(JMenuItem("全部清空").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { service.clearWatches() }
        })

        return menu
    }

    override fun dispose() {
        service.removeWatchDataListener(watchDataListener)
        uiTimer.stop()
    }
}
