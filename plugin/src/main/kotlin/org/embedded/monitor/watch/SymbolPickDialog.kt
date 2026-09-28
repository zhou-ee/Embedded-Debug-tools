package org.embedded.monitor.watch

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.agent.SymbolNode
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * ELF 符号树选择对话框：全局变量（含结构体成员展开）→ 添加到实时变量监视/示波器。
 * 界面直观标记已添加到实时变量监视中的变量，并阻止重复添加。
 */
class SymbolPickDialog(
    private val project: Project,
    private val service: AgentService,
) : DialogWrapper(project, true) {

    private val filter = SearchTextField(false)
    private val tree: Tree
    private val root = DefaultMutableTreeNode("全局变量")

    init {
        title = "从符号树添加变量监视"
        setOKButtonText("添加")
        tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val treeNode = value as? DefaultMutableTreeNode ?: return
                val wrapper = treeNode.userObject as? SymbolNodeWrapper
                if (wrapper == null) {
                    append(value.toString())
                    return
                }
                append(wrapper.node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(" : ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(wrapper.node.typeName.ifEmpty { wrapper.node.encoding }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (wrapper.node.members.isNotEmpty()) {
                    append(" (${wrapper.node.members.size})", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                }
                if (wrapper.isAdded) {
                    append("  [已添加]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2E7D32, 0x81C784)))
                }
                icon = if (wrapper.node.members.isNotEmpty()) com.intellij.icons.AllIcons.Nodes.Class
                else com.intellij.icons.AllIcons.Nodes.Variable
            }
        }

        rebuild("")
        filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                rebuild(filter.text)
            }
        })
        init()
    }

    private fun rebuild(query: String) {
        root.removeAllChildren()
        val q = query.trim().lowercase()
        val vars = service.elfVariables()

        fun matches(node: SymbolNode, query: String): Boolean {
            if (node.name.lowercase().contains(query) || node.typeName.lowercase().contains(query)) return true
            return node.members.any { matches(it, query) }
        }

        val selected = if (q.isEmpty()) vars else vars.filter { matches(it, q) }

        fun addMembers(parentTreeNode: DefaultMutableTreeNode, node: SymbolNode, prefix: String) {
            for (m in node.members) {
                val fullExpr = if (prefix.isEmpty()) m.name
                    else if (m.name.startsWith("[")) "$prefix${m.name}"
                    else "$prefix.${m.name}"
                val childTreeNode = DefaultMutableTreeNode(SymbolNodeWrapper(m, fullExpr))
                addMembers(childTreeNode, m, fullExpr)
                parentTreeNode.add(childTreeNode)
            }
        }

        for (v in selected) {
            val node = DefaultMutableTreeNode(SymbolNodeWrapper(v, v.name))
            addMembers(node, v, v.name)
            root.add(node)
        }
        (tree.model as DefaultTreeModel).reload()
        if (q.isNotEmpty()) {
            for (i in 0 until tree.rowCount) {
                tree.expandRow(i)
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(java.awt.BorderLayout(0, 6))
        panel.add(filter, java.awt.BorderLayout.NORTH)
        val scroll = JBScrollPane(tree)
        scroll.preferredSize = Dimension(460, 400)
        panel.add(scroll, java.awt.BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        val exprs = LinkedHashSet<String>()
        val sel = tree.selectionPaths
        if (sel != null) {
            for (path in sel) collectExpr(path, exprs)
        } else {
            tree.selectionRows?.forEach { row ->
                val path = tree.getPathForRow(row)
                if (path != null) collectExpr(path, exprs)
            }
        }
        val (alreadyAdded, newExprs) = exprs.partition { service.isWatchItemAdded(it) }
        if (newExprs.isEmpty() && alreadyAdded.isNotEmpty()) {
            service.notify("所选的 ${alreadyAdded.size} 个变量均已在监视列表中", com.intellij.notification.NotificationType.INFORMATION)
            super.doOKAction()
            return
        }
        // addWatch 异步执行：挂 whenComplete 汇总失败项并通知——此前 future 被丢弃，
        // 全部失败也无任何反馈（甚至照常提示"添加了 N 个"）
        val futures = newExprs.map { expr -> expr to service.addWatch(expr) }
        if (futures.isNotEmpty()) {
            java.util.concurrent.CompletableFuture.allOf(*futures.map { it.second }.toTypedArray())
                .whenComplete { _, _ ->
                    val failed = futures.filter { it.second.isCompletedExceptionally }
                    if (failed.isNotEmpty()) {
                        val preview = failed.take(5).joinToString("、") { it.first } +
                            if (failed.size > 5) " 等 ${failed.size} 项" else ""
                        service.notify(
                            "有 ${failed.size} 个变量添加失败（ELF 未加载 / agent 掉线 / 符号不可读）：$preview",
                            com.intellij.notification.NotificationType.WARNING,
                        )
                    }
                }
        }
        if (alreadyAdded.isNotEmpty()) {
            service.notify("已提交 ${newExprs.size} 个新变量（解析完成后出现在列表中），跳过 ${alreadyAdded.size} 个已存在变量", com.intellij.notification.NotificationType.INFORMATION)
        }
        super.doOKAction()
    }

    private fun collectExpr(path: TreePath, out: MutableCollection<String>) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val w = node.userObject as? SymbolNodeWrapper ?: return
        out.add(w.expr)
    }

    private inner class SymbolNodeWrapper(val node: SymbolNode, val expr: String) {
        val isAdded: Boolean get() = service.isWatchItemAdded(expr)

        override fun toString(): String = buildString {
            append(node.name)
            append(" : ")
            append(node.typeName.ifEmpty { node.encoding })
            if (node.members.isNotEmpty()) append(" (${node.members.size})")
            if (isAdded) append("  [已添加]")
        }
    }
}
