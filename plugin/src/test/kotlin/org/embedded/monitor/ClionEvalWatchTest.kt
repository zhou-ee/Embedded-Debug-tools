package org.embedded.monitor

import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.settings.PersistedWatchItem
import org.embedded.monitor.watch.LiveWatchTreeNode
import org.embedded.monitor.watch.WatchItem
import org.embedded.monitor.watch.WatchNodeData
import org.embedded.monitor.watch.WatchValueFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLion 原生求值型监视（evalOnly）：无法解析为内存地址的 C 表达式在断点暂停期
 * 由 IDE 调试器（XDebuggerEvaluator）求值。本测试覆盖模型语义与格式化降级。
 */
class ClionEvalWatchTest {

    private fun evalItem(expr: String = "*(int*)0x200002A8") = WatchItem(
        id = "w99",
        expr = expr,
        address = 0,
        size = 0,
        encoding = "eval",
        typeName = "CLion eval",
    ).apply { evalOnly = true }

    @Test
    fun testEvalOnlyFlagDefaultsOff() {
        val normal = WatchItem("w1", "g_x", 0x20000000, 4, "unsigned", "uint32_t")
        assertFalse(normal.evalOnly)
        assertNull(normal.evalValue)
    }

    @Test
    fun testEvalOnlyItemBasicSemantics() {
        val item = evalItem()
        assertTrue(item.evalOnly)
        assertNull(item.evalValue)
        // 无地址：doPushWatchTargets 的 >=0x1000 过滤天然排除求值型项
        assertTrue(item.address < 0x1000L)
        // equals/id 语义与普通项一致（树重建与去重依赖）
        assertEquals(evalItem(), item)
    }

    @Test
    fun testFormatterDoesNotCrashOnEvalItem() {
        val item = evalItem()
        // 求值前/空字节不抛异常（渲染器永远不会为 evalOnly 项走字节格式化路径，此处防御）
        assertEquals("-", WatchValueFormatter.format(item, null))
        assertEquals("-", WatchValueFormatter.format(item, ByteArray(0)))
    }

    @Test
    fun testTreeNodeBuildsWithEvalOnlyEntry() {
        val item = evalItem()
        // rebuildTree 对 item.node==null 的占位 SymbolNode 构造路径
        val node = LiveWatchTreeNode(
            WatchNodeData(
                entryId = item.id,
                expr = item.expr,
                node = item.node ?: org.embedded.monitor.agent.SymbolNode(name = item.expr),
                fullPath = item.expr,
                isTop = true,
                autoRefresh = true,
                entry = item,
            )
        )
        assertEquals(item.expr, node.name)
        assertTrue(node.data.isTop)
        assertTrue(node.data.entry.evalOnly)
    }

    private fun treeNodeFor(
        item: WatchItem,
        node: SymbolNode,
        isTop: Boolean,
        evalXValue: Any? = null,
        evalHasChildren: Boolean = true,
    ) = LiveWatchTreeNode(
        WatchNodeData(
            entryId = item.id,
            expr = item.expr,
            node = node,
            fullPath = item.expr,
            isTop = isTop,
            autoRefresh = false,
            entry = item,
        )
    ).apply {
        this.evalXValue = evalXValue
        this.evalHasChildren = evalHasChildren
    }

    @Test
    fun testEvalNodeLeafSemantics() {
        val item = evalItem()
        // 未求值：叶子（无手柄）
        val pending = treeNodeFor(item, SymbolNode(name = item.expr), isTop = true)
        assertTrue("未求值的顶层应为叶子", pending.isLeaf)
        item.evalValue = "0x200003e4"
        item.evalXValue = Any()
        // 已求值且有子项：非叶子（手柄出现，V1.2.21）
        assertFalse("已求值的顶层应可展开", pending.isLeaf)
        // 混合升级的 ELF 成员节点：走标准语义（结构体可展开、标量是叶子）
        val structMember = SymbolNode(
            name = "_ctx", typeName = "wl_chassis_ctx_t", address = 0x20000420,
            size = 104, encoding = "composite",
            members = listOf(SymbolNode(name = "seq", typeName = "uint32_t", address = 0x20000484, size = 4, encoding = "unsigned")),
        )
        val hybridStruct = treeNodeFor(item, structMember, isTop = false)
        assertFalse("混合结构体成员应可展开", hybridStruct.isLeaf)
        val hybridScalar = treeNodeFor(item, structMember.members.single(), isTop = false)
        assertTrue("混合标量成员应为叶子（V1.2.23 修复：seq 不再显示展开手柄）", hybridScalar.isLeaf)
        // 平台求值子节点（懒展开产物）：按自身 XValue/hasChildren 判定
        val platformChild = treeNodeFor(
            item, SymbolNode(name = "child"), isTop = false,
            evalXValue = Any(), evalHasChildren = false,
        )
        assertTrue("无子项的平台子节点应为叶子", platformChild.isLeaf)
    }

    @Test
    fun testPersistedWatchItemEvalOnlyDefaultFalse() {
        // 旧配置 XML 无 evalOnly 属性 → 反序列化默认 false（向后兼容）
        val legacy = PersistedWatchItem("g_x", true)
        assertFalse(legacy.evalOnly)
        val eval = PersistedWatchItem("*(int*)0x200002A8", true, true)
        assertTrue(eval.evalOnly)
    }
}
