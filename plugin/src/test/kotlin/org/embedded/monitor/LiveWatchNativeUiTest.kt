package org.embedded.monitor

import com.intellij.icons.AllIcons
import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.watch.LiveWatchTreeNode
import org.embedded.monitor.watch.WatchItem
import org.embedded.monitor.watch.WatchNodeData
import org.embedded.monitor.watch.WatchValueFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class LiveWatchNativeUiTest {

    @Test
    fun testNativeUint8AndInt8Formatting() {
        val u8Node = SymbolNode(
            name = "state",
            typeName = "uint8_t",
            address = 0x20000000L,
            size = 1,
            encoding = "unsigned",
        )
        // 0 -> 0 '\000'
        assertEquals("0 '\\000'", WatchValueFormatter.formatNativeValue(u8Node, byteArrayOf(0)))
        // 1 -> 1 '\001'
        assertEquals("1 '\\001'", WatchValueFormatter.formatNativeValue(u8Node, byteArrayOf(1)))
        // 65 -> 65 'A'
        assertEquals("65 'A'", WatchValueFormatter.formatNativeValue(u8Node, byteArrayOf(65)))
        // 10 -> 10 '\n'
        assertEquals("10 '\\n'", WatchValueFormatter.formatNativeValue(u8Node, byteArrayOf(10)))

        val charNode = SymbolNode(
            name = "ch",
            typeName = "char",
            address = 0x20000001L,
            size = 1,
            encoding = "signed",
        )
        assertEquals("1 '\\001'", WatchValueFormatter.formatNativeValue(charNode, byteArrayOf(1)))
        assertEquals("66 'B'", WatchValueFormatter.formatNativeValue(charNode, byteArrayOf(66)))
    }

    @Test
    fun testNativeIntegerDecimalFormatting() {
        val u32Node = SymbolNode(
            name = "run_ms",
            typeName = "uint32_t",
            address = 0x20000004L,
            size = 4,
            encoding = "unsigned",
        )
        val u32Bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(30755).array()
        assertEquals("30755", WatchValueFormatter.formatNativeValue(u32Node, u32Bytes))

        val i32Node = SymbolNode(
            name = "count",
            typeName = "int32_t",
            address = 0x20000008L,
            size = 4,
            encoding = "signed",
        )
        val i32Bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(957653).array()
        assertEquals("957653", WatchValueFormatter.formatNativeValue(i32Node, i32Bytes))

        val countNode = SymbolNode(
            name = "g_pc6_toggle_count",
            typeName = "volatile uint32_t",
            address = 0x2000000CL,
            size = 4,
            encoding = "unsigned",
        )
        val countBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(61).array()
        assertEquals("61", WatchValueFormatter.formatNativeValue(countNode, countBytes))
    }

    @Test
    fun testNativeFloatFormatting() {
        val fNode = SymbolNode(
            name = "temperature",
            typeName = "float",
            address = 0x20000010L,
            size = 4,
            encoding = "float",
        )
        val fBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(15.0049391f).array()
        val formatted = WatchValueFormatter.formatNativeValue(fNode, fBytes)
        assertTrue(formatted.startsWith("15.0049"))

        val negNode = SymbolNode(
            name = "sin_1hz",
            typeName = "float",
            address = 0x20000014L,
            size = 4,
            encoding = "float",
        )
        val negBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(-99.9506073f).array()
        val negFormatted = WatchValueFormatter.formatNativeValue(negNode, negBytes)
        assertTrue(negFormatted.startsWith("-99.9506"))
    }

    @Test
    fun testNativeEnumSymbolFormatting() {
        val enumNode = SymbolNode(
            name = "g_state",
            typeName = "volatile RunState",
            address = 0x20000020L,
            size = 1,
            encoding = "unsigned",
            enumValues = listOf(listOf(0, "ST_IDLE"), listOf(1, "ST_RUN"), listOf(2, "ST_ERROR")),
        )
        val bytesIdle = byteArrayOf(0)
        val bytesRun = byteArrayOf(1)
        assertEquals("ST_IDLE", WatchValueFormatter.formatNativeValue(enumNode, bytesIdle))
        assertEquals("ST_RUN", WatchValueFormatter.formatNativeValue(enumNode, bytesRun))
    }

    @Test
    fun testNativeCompositeReturnsEmptyValue() {
        val member = SymbolNode(name = "x", typeName = "int", address = 0x20000000L, size = 4, encoding = "signed")
        val structNode = SymbolNode(
            name = "g_motor",
            typeName = "MotorState",
            address = 0x20000000L,
            size = 8,
            encoding = "composite",
            members = listOf(member),
        )
        val dummyBytes = ByteArray(8)
        assertEquals("Composite node must return empty value text in native watch style", "", WatchValueFormatter.formatNativeValue(structNode, dummyBytes))
    }

    @Test
    fun testNativeTreeNodeHierarchyAndIcons() {
        val runMsMember = SymbolNode(name = "run_ms", typeName = "uint32_t", address = 0x20000000L, size = 4, encoding = "unsigned")
        val motorNode = SymbolNode(
            name = "g_motor",
            typeName = "MotorState",
            address = 0x20000000L,
            size = 16,
            encoding = "composite",
            members = listOf(runMsMember),
        )
        val item = WatchItem("w1", "g_motor", 0x20000000L, 16, "composite", "MotorState", true, motorNode)

        val topTreeNode = LiveWatchTreeNode(
            WatchNodeData("w1", "g_motor", motorNode, "g_motor", true, true, item)
        )
        assertTrue(topTreeNode.isComposite)
        assertFalse(topTreeNode.isPointer)
        assertEquals("g_motor", topTreeNode.name)
        assertEquals("MotorState", topTreeNode.typeName)
        assertTrue("Top node must have isTop=true", topTreeNode.data.isTop)
        assertTrue("Top node has autoRefresh=true", topTreeNode.data.autoRefresh)

        val childTreeNode = LiveWatchTreeNode(
            WatchNodeData("w1", "g_motor.run_ms", runMsMember, "g_motor.run_ms", false, true, item)
        )
        assertFalse(childTreeNode.isComposite)
        assertEquals("run_ms", childTreeNode.name)
        assertEquals("uint32_t", childTreeNode.typeName)
        assertFalse("Child node must have isTop=false", childTreeNode.data.isTop)
    }

    @Test
    fun testScopeFrequencySpinnerModelAndFormat() {
        val model = javax.swing.SpinnerNumberModel(100.0, 1.0, 50000.0, 10.0)
        assertEquals(50000.0, model.maximum)
        model.value = 10000.0
        assertEquals(10000.0, model.value)

        val spinner = javax.swing.JSpinner(model)
        val editor = javax.swing.JSpinner.NumberEditor(spinner, "#")
        spinner.editor = editor
        assertEquals("10000", editor.textField.text.replace(",", ""))
    }

    @Test
    fun testLiveWatchRefreshFreqSettingsAndSnapping() {
        val s = org.embedded.monitor.settings.EmbeddedMonitorSettings.State()
        assertEquals(5, s.watchRefreshFreq)

        // Snapping within 2, 5, 10, 15
        assertEquals(2, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(1))
        assertEquals(2, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(2))
        assertEquals(2, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(3))
        assertEquals(5, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(4))
        assertEquals(5, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(5))
        assertEquals(5, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(7))
        assertEquals(10, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(8))
        assertEquals(10, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(10))
        assertEquals(10, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(12))
        assertEquals(15, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(13))
        assertEquals(15, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(14))
        assertEquals(15, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(15))
        assertEquals(15, org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(20))

        assertEquals(listOf(2, 5, 10, 15), org.embedded.monitor.settings.EmbeddedMonitorSettings.VALID_WATCH_FREQS)
    }

    @Test
    fun testLiveWatchRefreshIntervalCalculation() {
        fun calcInterval(freq: Int) = (1000.0 / org.embedded.monitor.settings.EmbeddedMonitorSettings.snapWatchFreq(freq)).roundToInt().coerceIn(66, 500)

        assertEquals(500, calcInterval(2))
        assertEquals(200, calcInterval(5))
        assertEquals(100, calcInterval(10))
        assertEquals(67, calcInterval(15))
    }

    @Test
    fun testLiveWatchTreeCellRendererDynamicTooltip() {
        var currentFreq = 5

        val u32Node = SymbolNode(
            name = "g_seq",
            typeName = "uint32_t",
            address = 0x20000100L,
            size = 4,
            encoding = "unsigned",
        )
        val item = WatchItem("w1", "g_seq", 0x20000100L, 4, "unsigned", "uint32_t", true, u32Node)
        val topTreeNode = LiveWatchTreeNode(
            WatchNodeData("w1", "g_seq", u32Node, "g_seq", true, true, item)
        )

        val renderer = org.embedded.monitor.watch.LiveWatchTreeCellRenderer(freqProvider = { currentFreq })
        val dummyTree = javax.swing.JTree()

        // Test at 5Hz
        currentFreq = 5
        val comp5 = renderer.getTreeCellRendererComponent(dummyTree, topTreeNode, false, false, true, 0, false)
        assertTrue(comp5 is javax.swing.JPanel)
        val checkBox5 = (comp5 as javax.swing.JPanel).getComponent(0) as com.intellij.ui.components.JBCheckBox
        assertEquals("运行时自动刷新 (5Hz) - 单击禁用", checkBox5.toolTipText)
        assertTrue(comp5.toolTipText.contains("@ 0x20000100 (4 B) · 运行时自动刷新 (5Hz)"))

        // Test at 2Hz
        currentFreq = 2
        val comp2 = renderer.getTreeCellRendererComponent(dummyTree, topTreeNode, false, false, true, 0, false)
        val checkBox2 = (comp2 as javax.swing.JPanel).getComponent(0) as com.intellij.ui.components.JBCheckBox
        assertEquals("运行时自动刷新 (2Hz) - 单击禁用", checkBox2.toolTipText)
        assertTrue(comp2.toolTipText.contains("运行时自动刷新 (2Hz)"))

        // Test at 15Hz
        currentFreq = 15
        val comp15 = renderer.getTreeCellRendererComponent(dummyTree, topTreeNode, false, false, true, 0, false)
        val checkBox15 = (comp15 as javax.swing.JPanel).getComponent(0) as com.intellij.ui.components.JBCheckBox
        assertEquals("运行时自动刷新 (15Hz) - 单击禁用", checkBox15.toolTipText)
        assertTrue(comp15.toolTipText.contains("运行时自动刷新 (15Hz)"))

        // Test with autoRefresh=false
        topTreeNode.data.autoRefresh = false
        val compDisabled = renderer.getTreeCellRendererComponent(dummyTree, topTreeNode, false, false, true, 0, false)
        val checkBoxDisabled = (compDisabled as javax.swing.JPanel).getComponent(0) as com.intellij.ui.components.JBCheckBox
        assertEquals("仅暂停时刷新 - 单击启用 15Hz 自动刷新", checkBoxDisabled.toolTipText)
        assertTrue(compDisabled.toolTipText.contains("仅暂停时刷新"))
    }

    @Test
    fun testLiveWatchStatusLabelFormatting() {
        fun formatStatus(state: String, engineDesc: String, freq: Int, actualRate: Double, elfInfo: String): String {
            val actualRateText = if (actualRate > 0.0 && state == "running") {
                " (实测 ${"%.1f".format(java.util.Locale.ROOT, actualRate)}Hz)"
            } else ""
            return when (state) {
                "running" -> "● 监视中 · $engineDesc @ ${freq}Hz$actualRateText · $elfInfo"
                "halted" -> "⏸ 目标已暂停（单步/断点请用 CLion 原生调试器） · $elfInfo"
                "connecting" -> "⏳ 连接中… · $elfInfo"
                else -> "○ 未连接 · $elfInfo"
            }
        }

        // Test running with 2Hz, 5Hz, 10Hz, 15Hz
        val s2 = formatStatus("running", "DAPLink", 2, 2.04, "app.elf (10 变量)")
        assertEquals("● 监视中 · DAPLink @ 2Hz (实测 2.0Hz) · app.elf (10 变量)", s2)

        val s5 = formatStatus("running", "DAPLink", 5, 4.98, "app.elf (10 变量)")
        assertEquals("● 监视中 · DAPLink @ 5Hz (实测 5.0Hz) · app.elf (10 变量)", s5)

        val s10 = formatStatus("running", "DAPLink", 10, 9.92, "app.elf (10 变量)")
        assertEquals("● 监视中 · DAPLink @ 10Hz (实测 9.9Hz) · app.elf (10 变量)", s10)

        val s15 = formatStatus("running", "DAPLink", 15, 14.89, "app.elf (10 变量)")
        assertEquals("● 监视中 · DAPLink @ 15Hz (实测 14.9Hz) · app.elf (10 变量)", s15)

        // Test running with 0.0 actual rate (e.g. before first window calculation or decayed)
        val s0 = formatStatus("running", "DAPLink", 5, 0.0, "app.elf (10 变量)")
        assertEquals("● 监视中 · DAPLink @ 5Hz · app.elf (10 变量)", s0)

        // Test halted / disconnected does not include actual rate
        val sHalted = formatStatus("halted", "DAPLink", 5, 5.0, "app.elf (10 变量)")
        assertEquals("⏸ 目标已暂停（单步/断点请用 CLion 原生调试器） · app.elf (10 变量)", sHalted)

        val sDisconnected = formatStatus("disconnected", "DAPLink", 5, 5.0, "app.elf (10 变量)")
        assertEquals("○ 未连接 · app.elf (10 变量)", sDisconnected)
    }

    @Test
    fun testActualRateDecayLogic() {
        var lastFrameTime = 1000L
        var rateWindowStart = 1000L
        var rateWindowCount = 2L
        var actualRateHz = 5.0

        fun getActualRate(currentTime: Long): Double {
            if (lastFrameTime > 0 && currentTime - lastFrameTime > 2000) {
                actualRateHz = 0.0
                rateWindowStart = 0
                rateWindowCount = 0
            }
            return actualRateHz
        }

        // Before timeout (within 2000ms of last frame), retains current rate
        assertEquals(5.0, getActualRate(2500L), 0.001)

        // After timeout (> 2000ms from last frame), decays to 0.0 even if rateWindowCount > 0
        assertEquals(0.0, getActualRate(3001L), 0.001)
        assertEquals(0L, rateWindowStart)
        assertEquals(0L, rateWindowCount)
    }
}
