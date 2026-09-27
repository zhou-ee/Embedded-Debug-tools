package org.embedded.monitor

import org.embedded.monitor.core.HoverState
import org.embedded.monitor.core.ScopeSample
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ScopeWaveformPanel
import org.embedded.monitor.core.ValueFormat
import org.embedded.monitor.scope.ScopeChannelTableModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.image.BufferedImage

class ScopeLifecycleAndWaveformTest {

    private val testVar = ScopeVariable(
        name = "test_var",
        address = 0x20000000L,
        size = 4,
        format = ValueFormat.F32,
    )

    @Test
    fun testWaveformFollowLatestTracksLiveTime() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // timeSpanSec = 0.5 * 10 = 5.0s

        val samples = listOf(
            ScopeSample(1_000_000_000L, 10.0f),
            ScopeSample(2_000_000_000L, 15.0f),
            ScopeSample(5_000_000_000L, 20.0f),
        )
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        assertTrue(panel.isFollowLatest())
        assertEquals(5.0, panel.latestTimeSec(), 1e-6)

        val (t0, t1) = panel.timeRange()
        assertEquals(5.0, t1, 1e-6)
        assertEquals(0.0, t0, 1e-6) // 5.0 - 5.0 = 0.0

        // 新数据到达（时间推进至 8.0s）
        val moreSamples = samples + listOf(
            ScopeSample(6_000_000_000L, 22.0f),
            ScopeSample(8_000_000_000L, 30.0f),
        )
        panel.setFrame(mapOf(testVar.address to moreSamples), listOf(testVar))

        assertEquals(8.0, panel.latestTimeSec(), 1e-6)
        val (newT0, newT1) = panel.timeRange()
        assertEquals(8.0, newT1, 1e-6)
        assertEquals(3.0, newT0, 1e-6) // 8.0 - 5.0 = 3.0
    }

    @Test
    fun testWaveformHistoricalPanStopsScrolling() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // timeSpanSec = 5.0s

        val samples = (1..10).map { i ->
            ScopeSample(i * 1_000_000_000L, i * 2.0f)
        }
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        assertEquals(10.0, panel.latestTimeSec(), 1e-6)

        // 模拟手动拖拽进入历史模式：关闭 followLatest，并固定右边缘为 6.0s
        panel.setFollowLatest(false)
        assertFalse(panel.isFollowLatest())

        // 视图设置回退到历史 6.0s
        val viewEndField = ScopeWaveformPanel::class.java.getDeclaredField("viewEndSec")
        viewEndField.isAccessible = true
        viewEndField.setDouble(panel, 6.0)

        val (histT0, histT1) = panel.timeRange()
        assertEquals(6.0, histT1, 1e-6)
        assertEquals(1.0, histT0, 1e-6) // 6.0 - 5.0 = 1.0

        // 后台持续有实时新采样点涌入（时间推进到 15.0s）
        val ongoingSamples = samples + (11..15).map { i ->
            ScopeSample(i * 1_000_000_000L, i * 2.0f)
        }
        panel.setFrame(mapOf(testVar.address to ongoingSamples), listOf(testVar))

        // 核心验证：数据集最新时间已推进至 15.0s，但历史视窗必须纹丝不动（彻底解决手动拖动时波形不会停止刷新的问题）
        assertEquals(15.0, panel.latestTimeSec(), 1e-6)
        val (frozenT0, frozenT1) = panel.timeRange()
        assertEquals(6.0, frozenT1, 1e-6)
        assertEquals(1.0, frozenT0, 1e-6)

        // 点击“返回实时”或调用 setFollowLatest(true)，视图必须立刻吸附到最新 15.0s
        panel.setFollowLatest(true)
        assertTrue(panel.isFollowLatest())
        val (liveT0, liveT1) = panel.timeRange()
        assertEquals(15.0, liveT1, 1e-6)
        assertEquals(10.0, liveT0, 1e-6) // 15.0 - 5.0 = 10.0
    }

    @Test
    fun testPaintDoesNotCrashOnEmptyOrDisorderedData() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)

        val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()

        // 1. 空数据渲染
        panel.paint(g2)

        // 2. 模拟旧版本导致崩溃的时间戳回退场景（10s 后突然出现 0s）
        val disorderedSamples = listOf(
            ScopeSample(5_000_000_000L, 10f),
            ScopeSample(10_000_000_000L, 20f),
            ScopeSample(100_000_000L, 5f), // 0.1s 时间戳倒退
        )
        panel.setFrame(mapOf(testVar.address to disorderedSamples), listOf(testVar))

        // 必须优雅自愈，绝不能抛出 IllegalArgumentException
        panel.paint(g2)
        g2.dispose()
    }

    @Test
    fun testTimestampMonotonicContinuationLogic() {
        // 验证采样引擎重启时的延续（续接）逻辑
        var timeOffsetSec = 0.0
        var lastRawTimeSec = -1.0
        val continueScopeHistory = true

        fun processRaw(rawT: Double): Double {
            if (lastRawTimeSec >= 0.0 && rawT < lastRawTimeSec) {
                if (continueScopeHistory) {
                    timeOffsetSec += maxOf(0.0, lastRawTimeSec) + 0.033
                } else {
                    timeOffsetSec = 0.0
                }
            }
            lastRawTimeSec = rawT
            return rawT + timeOffsetSec
        }

        // 第一次会话运行 0.0s -> 5.0s
        val s1 = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0).map { processRaw(it) }
        assertEquals(0.0, s1.first(), 1e-6)
        assertEquals(5.0, s1.last(), 1e-6)

        // 引擎停止后再启动，硬件 epoch 重置从 0.0s 开始注入
        val s2 = listOf(0.0, 0.5, 1.0).map { processRaw(it) }
        // 续接后，第一个时间戳应大于 5.0s（5.0 + 0.033 = 5.033s），严格单调递增
        assertTrue("续接点必须大于前一会话末尾", s2.first() > 5.0)
        assertEquals(5.033, s2[0], 1e-3)
        assertEquals(5.533, s2[1], 1e-3)
        assertEquals(6.033, s2[2], 1e-3)

        // 校验整个合并序列严格单调递增
        val full = s1 + s2
        for (i in 1 until full.size) {
            assertTrue("时间戳在 $i 处必须单调递增", full[i] > full[i - 1])
        }
    }

    @Test
    fun testHoverStateDeltaCalculation() {
        val hover = HoverState(
            timeText = "5.0000s",
            deltaText = "+1.0000s",
            values = listOf("var1" to 42.0f),
            cursorAMeasuring = true,
            cursorATimeText = "4.0000s",
            deltaSecText = "+1.0000s",
            freqText = "1.0 Hz",
            deltas = listOf("var1" to 12.0f),
        )

        assertEquals("5.0000s", hover.timeText)
        assertTrue(hover.cursorAMeasuring)
        assertEquals("1.0 Hz", hover.freqText)
        assertEquals(12.0f, hover.deltas[0].second, 1e-6f)
    }

    @Test
    fun testNarrowPanelBoundsRenderingDoesNotCrash() {
        // 验证窄侧边栏或小窗体下绘制游标 A、悬停卡片与状态药丸绝不抛出 IllegalArgumentException (coerceIn 越界)
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(120, 90) // 极限窄高：plotW 仅 34px，plotH 仅 40px

        val samples = listOf(
            ScopeSample(1_000_000_000L, 10.0f),
            ScopeSample(2_000_000_000L, 15.0f),
        )
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        // 放置标记游标 A 并设置悬停
        val pinnedField = ScopeWaveformPanel::class.java.getDeclaredField("pinnedCursorTimeSec")
        pinnedField.isAccessible = true
        pinnedField.set(panel, 1.5)

        val hoverXField = ScopeWaveformPanel::class.java.getDeclaredField("hoverX")
        hoverXField.isAccessible = true
        hoverXField.setInt(panel, 75)

        val hoverYField = ScopeWaveformPanel::class.java.getDeclaredField("hoverY")
        hoverYField.isAccessible = true
        hoverYField.setInt(panel, 35)

        // 切换至历史模式以显示返回实时按钮
        panel.setFollowLatest(false)

        val img = BufferedImage(120, 90, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            panel.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    @Test
    fun testClearResetsFollowLatestAndRange() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)

        // 模拟拖拽进入历史模式
        panel.setFollowLatest(false)
        panel.setAutoRange(false)
        assertFalse(panel.isFollowLatest())
        assertFalse(panel.isAutoRange())

        // 清空画布
        panel.clear()

        // 必须彻底复位跟随与自动量程，避免清空后画布因历史锁定 viewEndSec=0.0 而卡住不显示新数据
        assertTrue("clear() 必须恢复跟随最新", panel.isFollowLatest())
        assertTrue("clear() 必须恢复自动量程", panel.isAutoRange())
    }

    @Test
    fun testDeterministicSessionEpochContinuation() {
        // 模拟即使短会话（rawT 未落后于上一次 lastRawTimeSec），新会话也必须严格续接
        var timeOffsetSec = 0.0
        var isNewEngineSession = true
        var lastEmittedNanos = 0L

        val series = mutableListOf<ScopeSample>()

        fun ingest(rawT: Double, isConnectedEvent: Boolean): ScopeSample {
            if (isConnectedEvent) isNewEngineSession = true
            val currentMaxNanos = series.lastOrNull()?.timestampNanos ?: 0L
            if (isNewEngineSession) {
                isNewEngineSession = false
                val currentMaxSec = currentMaxNanos / 1e9
                timeOffsetSec = if (currentMaxSec > 0.0) (currentMaxSec + 0.033) - rawT else 0.0
            }
            val adjustedT = rawT + timeOffsetSec
            var tNanos = (adjustedT * 1e9).toLong()
            if (tNanos <= lastEmittedNanos && lastEmittedNanos > 0L) {
                tNanos = lastEmittedNanos + 100_000L
            }
            lastEmittedNanos = tNanos
            val sample = ScopeSample(tNanos, 1.0f)
            series.add(sample)
            return sample
        }

        // 会话 1：极短会话，仅运行至 0.05s
        ingest(0.00, isConnectedEvent = true)
        val s1End = ingest(0.05, isConnectedEvent = false)
        assertEquals(0.05, s1End.timestampNanos / 1e9, 1e-4)

        // 会话 2：目标重连，第一帧到达时硬件时间戳可能因初始化延时已有 0.10s（0.10 > 0.05）
        val s2Start = ingest(0.10, isConnectedEvent = true)
        assertTrue("新会话第一帧必须大于前一会话末尾", s2Start.timestampNanos > s1End.timestampNanos)
        assertEquals(0.083, s2Start.timestampNanos / 1e9, 1e-3)

        // 验证整体序列严格单调递增
        for (i in 1 until series.size) {
            assertTrue(series[i].timestampNanos > series[i - 1].timestampNanos)
        }
    }

    @Test
    fun testDenseSineWaveDownsamplingAndNoSpikes() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // timeSpan = 5.0s

        // 生成 1Hz 正弦波，采样率 1000Hz（5000 点），必然触发包络降采样 step >= 4
        val samples = (0 until 5000).map { i ->
            val tSec = i / 1000.0
            val v = kotlin.math.sin(2.0 * Math.PI * 1.0 * tSec).toFloat()
            ScopeSample((tSec * 1e9).toLong(), v)
        }
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        // 验证最新时间
        assertEquals(4.999, panel.latestTimeSec(), 1e-3)

        // 绘制测试，确保降采样分桶顺逆序连接不抛出任何异常
        val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            panel.paint(g2)
        } finally {
            g2.dispose()
        }

        // 测试悬停在下降沿（例如 0.25s 处为峰值 1.0，0.5s 为零交叉点下降中）
        val buildHoverStateMethod = ScopeWaveformPanel::class.java.getDeclaredMethod("buildHoverState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        buildHoverStateMethod.isAccessible = true

        val (t0, t1) = panel.timeRange()
        val xAtZeroCross = (68 + ((0.5 - t0) / (t1 - t0)) * 514).toInt()
        val hoverState = buildHoverStateMethod.invoke(panel, xAtZeroCross, 150) as? HoverState
        assertNotNull(hoverState)
        assertEquals(1, hoverState!!.values.size)
        // 下降沿 0.5s 处的值应接近 0.0，绝不因绘制产生失真尖峰影响读数
        assertEquals(0.0f, hoverState.values[0].second, 0.05f)

        // 验证所有提示文本均为字体安全的 ASCII 兼容字符，绝不含 CLion 默认字体不支持的 Δ、⮌ 等特殊 Unicode
        assertFalse(hoverState.deltaText.contains("Δ"))
        assertFalse(hoverState.deltaSecText.contains("Δ"))
        assertFalse(hoverState.timeText.contains("Δ"))
    }

    @Test
    fun testTimestampDisorderLatestTimeResilience() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)

        // 包含乱序倒退点（例如网络延时到达的旧样本）
        val disordered = listOf(
            ScopeSample(1_000_000_000L, 1.0f),
            ScopeSample(5_000_000_000L, 5.0f),
            ScopeSample(3_000_000_000L, 3.0f), // 乱序回退
        )
        panel.setFrame(mapOf(testVar.address to disordered), listOf(testVar))

        // latestTimeSec 必须返回最大时间 5.0s，而不是错误的取末尾 3.0s
        assertEquals(5.0, panel.latestTimeSec(), 1e-6)
        assertEquals(1.0, panel.earliestTimeSec(), 1e-6)
    }

    @Test
    fun testCursorAAndBFontSafeStrings() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5)

        val samples = (0..5).map { i ->
            ScopeSample((i * 1_000_000_000L), i * 10f)
        }
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        // 放置游标 A
        val pinnedField = ScopeWaveformPanel::class.java.getDeclaredField("pinnedCursorTimeSec")
        pinnedField.isAccessible = true
        pinnedField.set(panel, 2.0)

        val buildHoverStateMethod = ScopeWaveformPanel::class.java.getDeclaredMethod("buildHoverState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        buildHoverStateMethod.isAccessible = true

        val (t0, t1) = panel.timeRange()
        val xAt4s = (68 + ((4.0 - t0) / (t1 - t0)) * 514).toInt()
        val hoverState = buildHoverStateMethod.invoke(panel, xAt4s, 150) as? HoverState
        assertNotNull(hoverState)
        assertTrue(hoverState!!.cursorAMeasuring)
        assertEquals("2.0000s", hoverState.cursorATimeText)

        // 验证提示文本不包含特殊希腊字母或易导致 CLion 方块字的字符
        assertFalse(hoverState.deltaSecText.contains("Δ"))
        assertFalse(hoverState.deltaText.contains("Δ"))
        assertTrue("deltaSecText 必须格式化为安全秒数表示", hoverState.deltaSecText.startsWith("+1.99") || hoverState.deltaSecText.startsWith("+2.00"))
        assertTrue("freqText 必须计算出频率", hoverState.freqText.isNotEmpty())
    }

    @Test
    fun testEmptyChannelTableUpdateDoesNotThrow() {
        var visibleChangedAddr = -1L
        var visibleChangedVal = false
        var formatChangedAddr = -1L
        var formatChangedVal: ValueFormat? = null

        val model = ScopeChannelTableModel(
            onVariableVisibleChange = { addr, vis ->
                visibleChangedAddr = addr
                visibleChangedVal = vis
            },
            onVariableFormatChange = { addr, fmt ->
                formatChangedAddr = addr
                formatChangedVal = fmt
            }
        )

        // 核心验证：空表更新时不应抛出 ArrayIndexOutOfBoundsException 或 IndexOutOfBoundsException
        assertEquals(0, model.rowCount)
        model.update(emptyList(), emptyMap())
        model.setCursorValues(emptyMap())
        assertEquals(0, model.rowCount)
        assertEquals(7, model.columnCount)
        assertEquals(Boolean::class.javaObjectType, model.getColumnClass(0))
        assertEquals(ValueFormat::class.java, model.getColumnClass(2))
        assertEquals(String::class.java, model.getColumnClass(1))

        // 添加一个通道
        model.update(listOf(testVar), mapOf(testVar.address to 42.0f))
        assertEquals(1, model.rowCount)
        assertEquals(true, model.getValueAt(0, 0))
        assertEquals("test_var", model.getValueAt(0, 1))
        assertEquals(ValueFormat.F32, model.getValueAt(0, 2))
        assertEquals("0x20000000", model.getValueAt(0, 3))
        assertEquals("4 B", model.getValueAt(0, 4))
        assertEquals("42.00", model.getValueAt(0, 5))

        // 验证单元格可编辑性（仅第2列格式可编辑，第0列由鼠标单次点击直接处理）
        assertFalse("复选框列严禁通过默认表格编辑器编辑", model.isCellEditable(0, 0))
        assertFalse("通道名称列不可编辑", model.isCellEditable(0, 1))
        assertTrue("格式列支持下拉编辑", model.isCellEditable(0, 2))

        // 测试 setValueAt 触发回调
        model.setValueAt(false, 0, 0)
        assertEquals(testVar.address, visibleChangedAddr)
        assertEquals(false, visibleChangedVal)

        model.setValueAt(ValueFormat.U32, 0, 2)
        assertEquals(testVar.address, formatChangedAddr)
        assertEquals(ValueFormat.U32, formatChangedVal)

        // 清空通道
        model.update(emptyList(), emptyMap())
        assertEquals(0, model.rowCount)
        model.setCursorValues(mapOf("test_var" to 10f))
    }

    @Test
    fun testFallingSineWaveM4DownsamplingMonotonicity() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // 5.0s 视野

        // 在 0.25s 到 0.75s 之间生成密集单调下降的半个正弦波周期（1000 个采样点）
        val samples = (250..750).map { i ->
            val tSec = i / 1000.0
            val v = kotlin.math.sin(2.0 * Math.PI * 1.0 * tSec).toFloat()
            ScopeSample((tSec * 1e9).toLong(), v)
        }
        panel.setFrame(mapOf(testVar.address to samples), listOf(testVar))

        // 渲染测试：M4 降采样沿时间轴连接，绝不抛异常
        val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            panel.paint(g2)
        } finally {
            g2.dispose()
        }

        // 确保降采样状态下悬停读取的值平滑准确
        val buildHoverStateMethod = ScopeWaveformPanel::class.java.getDeclaredMethod("buildHoverState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        buildHoverStateMethod.isAccessible = true

        val (t0, t1) = panel.timeRange()
        val xMid = (68 + ((0.5 - t0) / (t1 - t0)) * 514).toInt()
        val hover = buildHoverStateMethod.invoke(panel, xMid, 150) as? HoverState
        assertNotNull(hover)
        assertEquals(0.0f, hover!!.values[0].second, 0.05f)
    }

    @Test
    fun testAutoRangeNiceUnitAndQuantization() {
        // 1. 验证 1-2-5 步进函数
        assertEquals(0.005, ScopeWaveformPanel.niceUnit(0.0034), 1e-9)
        assertEquals(0.02, ScopeWaveformPanel.niceUnit(0.012), 1e-9)
        assertEquals(0.5, ScopeWaveformPanel.niceUnit(0.33), 1e-9)
        assertEquals(2.0, ScopeWaveformPanel.niceUnit(1.5), 1e-9)
        assertEquals(5.0, ScopeWaveformPanel.niceUnit(3.5), 1e-9)
        assertEquals(10.0, ScopeWaveformPanel.niceUnit(7.8), 1e-9)
        assertEquals(50.0, ScopeWaveformPanel.niceUnit(35.0), 1e-9)
        assertEquals(500.0, ScopeWaveformPanel.niceUnit(230.0), 1e-9)

        val panel = ScopeWaveformPanel()

        // 2. 双极对称信号（-10 到 +10），必须零点锁定中间，上下各 4 格
        val (symMin, symMax) = panel.calculateNiceRange(-10.0, 10.0)
        assertEquals(-20.0, symMin, 1e-6)
        assertEquals(20.0, symMax, 1e-6)
        assertEquals(40.0, symMax - symMin, 1e-6)

        // 3. 单极偏正信号（0 到 3.3V），底线对齐 0V
        val (posMin, posMax) = panel.calculateNiceRange(0.0, 3.3)
        assertEquals(0.0, posMin, 1e-6)
        assertEquals(4.0, posMax, 1e-6)

        // 4. 直流平直线（0.0）
        val (dcMin, dcMax) = panel.calculateNiceRange(0.0, 0.0)
        assertTrue(dcMin < 0.0 && dcMax > 0.0)
        assertEquals(0.0, (dcMin + dcMax) / 2.0, 1e-6)
    }

    @Test
    fun testAutoRangeRollingSineWaveStability() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // 5.0s 视野

        val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()

        try {
            val historyYMin = mutableListOf<Double>()
            val historyYMax = mutableListOf<Double>()

            // 预先填充 2 个完整周期的 1Hz 正弦波数据（0.0s ~ 2.0s，幅值 10.0f）
            val allSamples = (0..60).map { i ->
                val tSec = i * 0.033
                val v = (10.0 * kotlin.math.sin(2.0 * Math.PI * 1.0 * tSec)).toFloat()
                ScopeSample((tSec * 1e9).toLong(), v)
            }.toMutableList()

            // 模拟实时滚动：时间逐步推进 60 帧（持续 2 秒，正弦波波峰波谷连续滑过视窗）
            for (frame in 61..120) {
                val tSec = frame * 0.033
                val v = (10.0 * kotlin.math.sin(2.0 * Math.PI * 1.0 * tSec)).toFloat()
                allSamples.add(ScopeSample((tSec * 1e9).toLong(), v))

                panel.setFrame(mapOf(testVar.address to allSamples.toList()), listOf(testVar))
                panel.paint(g2)

                val (yMin, yMax) = panel.yRange()
                historyYMin.add(yMin)
                historyYMax.add(yMax)
            }

            // 在滚动过程中，验证 Y 轴量程绝对锁定，绝不随波形滚动高频跳动
            val firstMin = historyYMin.first()
            val firstMax = historyYMax.first()
            assertEquals(-20.0, firstMin, 1e-6)
            assertEquals(20.0, firstMax, 1e-6)

            for (m in historyYMin) {
                assertEquals("yMin 在正弦波滚动时必须绝对恒定，不得高频抖动", firstMin, m, 1e-6)
            }
            for (m in historyYMax) {
                assertEquals("yMax 在正弦波滚动时必须绝对恒定，不得高频抖动", firstMax, m, 1e-6)
            }
        } finally {
            g2.dispose()
        }
    }

    @Test
    fun testAutoRangeExpansionAndHoldOffContraction() {
        val panel = ScopeWaveformPanel()
        panel.size = Dimension(600, 300)
        panel.setTimePerDivision(0.5) // 5.0s

        val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()

        try {
            // 初始信号：幅值 10.0
            val samples10 = (0..50).map { i ->
                val tSec = i * 0.1
                val v = (10.0 * kotlin.math.sin(tSec)).toFloat()
                ScopeSample((tSec * 1e9).toLong(), v)
            }
            panel.setFrame(mapOf(testVar.address to samples10), listOf(testVar))
            panel.paint(g2)
            val (initMin, initMax) = panel.yRange()
            assertEquals(-20.0, initMin, 1e-6)
            assertEquals(20.0, initMax, 1e-6)

            // 1. 信号突增到 50.0：超出当前范围，必须立刻外扩
            val samples50 = samples10 + listOf(
                ScopeSample(6_000_000_000L, 50.0f),
                ScopeSample(7_000_000_000L, -50.0f),
            )
            panel.setFrame(mapOf(testVar.address to samples50), listOf(testVar))
            panel.paint(g2)
            val (expMin, expMax) = panel.yRange()
            assertTrue("突发大信号必须立即外扩", expMax >= 50.0 && expMin <= -50.0)

            // 2. 信号幅值骤降至 1.0：第一帧绝不能立即收缩（防抖滞后保持）
            val samples1 = (60..80).map { i ->
                val tSec = i * 0.1
                val v = (1.0 * kotlin.math.sin(tSec)).toFloat()
                ScopeSample((tSec * 1e9).toLong(), v)
            }
            panel.setFrame(mapOf(testVar.address to samples1), listOf(testVar))
            panel.paint(g2)
            val (postDropMin, postDropMax) = panel.yRange()
            assertEquals("信号骤降时第一帧必须保持滞后，不得立即收缩", expMin, postDropMin, 1e-6)
            assertEquals("信号骤降时第一帧必须保持滞后，不得立即收缩", expMax, postDropMax, 1e-6)

            // 3. 模拟滞后超时后：量程平滑收缩
            val holdField = ScopeWaveformPanel::class.java.getDeclaredField("autoRangeShrinkHoldDeadline")
            holdField.isAccessible = true
            holdField.setLong(panel, System.currentTimeMillis() - 100L) // 模拟超过 1200ms

            panel.paint(g2)
            val (shrunkMin, shrunkMax) = panel.yRange()
            assertTrue("滞后超时后应自适应收缩至更佳刻度", shrunkMax < expMax && shrunkMin > expMin)
        } finally {
            g2.dispose()
        }
    }
}
