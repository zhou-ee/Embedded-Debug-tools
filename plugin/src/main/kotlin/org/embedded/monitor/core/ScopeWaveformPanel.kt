package org.embedded.monitor.core

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/** 悬停游标读数：时间戳、相对时间差、各可见变量在光标处的值，以及游标测距信息。 */
data class HoverState(
    val timeText: String,
    val deltaText: String,
    val values: List<Pair<String, Float>>,
    val cursorAMeasuring: Boolean = false,
    val cursorATimeText: String = "",
    val deltaSecText: String = "",
    val freqText: String = "",
    val deltas: List<Pair<String, Float>> = emptyList(),
)

/**
 * 多变量示波器波形画布：
 * - 绝对时间轴驱动（对照 software_ref/src/scope/plot.ts），拖拽历史时锁定绝对时间，停止波形刷新漂移；
 * - 区分实时跟随（Follow Latest）与历史查看（Manual Pan），提供醒目的“返回实时”指示与交互按钮；
 * - 自动/手动 Y 轴量程（Auto Range），支持围绕光标的值缩放；
 * - 悬停游标十字线 + 标记游标 A 测距（dt、频率 1/dt、各通道 dV）；
 * - min/max 包络降采样与暗黑主题霓虹微发光渲染；
 * - 自适应网格刻度与时间小数位数。
 */
class ScopeWaveformPanel : JPanel() {
    private var variables: List<ScopeVariable> = emptyList()
    private var data: Map<Long, List<ScopeSample>> = emptyMap()

    // 视图状态（绝对时间轴，单位：秒）
    private var timeSpanSec: Double = 5.0
    private var viewEndSec: Double = 0.0
    private var followLatest: Boolean = true
    private var autoRange: Boolean = true
    private var isPaused: Boolean = false
    private var yMin: Double = -1.0
    private var yMax: Double = 1.0

    // 自动量程防抖与滞后控制状态
    private var autoRangeNeedsImmediateFit: Boolean = true
    private var autoRangeShrinkHoldDeadline: Long = 0L

    // 交互拖拽状态
    private var isDragging: Boolean = false
    private var lastMouseX: Int = 0
    private var lastMouseY: Int = 0
    private var hoverX: Int = -1
    private var hoverY: Int = -1
    private var needsRepaint: Boolean = true

    // 游标 A（测距基准）
    private var pinnedCursorTimeSec: Double? = null

    // “返回实时” 按钮热区
    private var returnToLiveBounds: Rectangle? = null

    /** 跟随状态变化回调（通知外部工具栏同步按钮状态）。 */
    var onFollowChange: ((Boolean) -> Unit)? = null

    /** 自动量程状态变化回调（通知外部工具栏同步按钮状态）。 */
    var onAutoRangeChange: ((Boolean) -> Unit)? = null

    /** 悬停读数回调（供变量表 Cursor 列联动）。 */
    var onCursorChange: ((HoverState?) -> Unit)? = null

    /** 真实采样率（Hz），由外部更新，用于右上角状态显示。 */
    private var currentRateHz: Double = 0.0

    /** 右键菜单动作回调（由持有方处理弹窗/服务调用）。 */
    var onRequestSetTimePerDiv: (() -> Unit)? = null
    var onRequestSetValuePerDiv: (() -> Unit)? = null
    var onRequestClearData: (() -> Unit)? = null
    var onRequestExportCsv: (() -> Unit)? = null

    /** 是否在画布内部左上角绘制图例（底部已有高交互通道栏时默认关闭以节省空间）。 */
    var showInCanvasLegend: Boolean = false

    /** 是否显示网格线（默认开启，可在顶部工具栏一键切换）。 */
    var showGrid: Boolean = true
        set(value) {
            field = value
            needsRepaint = true
        }

    private val repaintTimer = Timer(33) {
        if (needsRepaint) {
            needsRepaint = false
            repaint()
        }
    }

    private val marginLeft = 68
    private val marginTop = 20
    private val marginRight = 18
    private val marginBottom = 30

    /** 网格分格数（Y 方向 8 格，X 方向 10 格，示波器标准分格）。 */
    val H_DIVS = 8
    val V_DIVS = 10

    init {
        background = JBColor.background()
        border = BorderFactory.createLineBorder(JBColor.border())
        preferredSize = Dimension(640, 360)
        isFocusable = true
        repaintTimer.start()

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    // 优先检查是否点击了“返回实时”按钮
                    if (!followLatest && returnToLiveBounds?.contains(e.point) == true) {
                        setFollowLatest(true)
                        return
                    }
                    isDragging = true
                    lastMouseX = e.x
                    lastMouseY = e.y
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else if (e.button == MouseEvent.BUTTON3) {
                    buildContextMenu(e.x, e.y).show(this@ScopeWaveformPanel, e.x, e.y)
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!isDragging) return
                val dx = e.x - lastMouseX
                val dy = e.y - lastMouseY
                val plotW = plotW()
                val plotH = plotH()

                // X 轴拖拽：平移绝对时间窗口，并立即退出跟随模式（防止波形随实时数据持续跑动）
                if (abs(dx) > 0 && plotW > 0) {
                    val dt = (dx.toDouble() / plotW) * timeSpanSec
                    if (followLatest) {
                        followLatest = false
                        viewEndSec = latestTimeSec()
                        onFollowChange?.invoke(false)
                    }
                    viewEndSec -= dt
                    val minEnd = earliestTimeSec() + timeSpanSec * 0.05
                    val maxEnd = latestTimeSec()
                    viewEndSec = viewEndSec.coerceIn(minEnd, max(minEnd, maxEnd))
                }

                // Y 轴拖拽：平移数值视窗，自动关闭 autoRange
                if (abs(dy) > 0 && plotH > 0) {
                    if (autoRange) {
                        autoRange = false
                        onAutoRangeChange?.invoke(false)
                    }
                    val rangeY = yMax - yMin
                    val dv = (dy.toDouble() / plotH) * rangeY
                    yMin += dv
                    yMax += dv
                }

                lastMouseX = e.x
                lastMouseY = e.y
                hoverX = e.x
                hoverY = e.y
                onCursorChange?.invoke(buildHoverState(e.x, e.y))
                needsRepaint = true
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = false
                    updateCursorForHover(e.x, e.y)
                    needsRepaint = true
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                hoverX = e.x
                hoverY = e.y
                updateCursorForHover(e.x, e.y)
                onCursorChange?.invoke(buildHoverState(e.x, e.y))
                needsRepaint = true
            }

            override fun mouseExited(e: MouseEvent) {
                hoverX = -1
                hoverY = -1
                cursor = Cursor.getDefaultCursor()
                onCursorChange?.invoke(null)
                needsRepaint = true
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val delta = e.preciseWheelRotation
                if (delta == 0.0) return
                val factor = if (delta > 0) 1.25 else 0.8

                // Shift+滚轮 或 鼠标位于 Y 轴标签区（左侧 marginLeft 内）：纵向缩放（围绕光标处数值）
                if (e.isShiftDown || e.x < marginLeft) {
                    val plotH = plotH()
                    if (plotH > 0) {
                        val frac = ((e.y - marginTop).toDouble() / plotH).coerceIn(0.0, 1.0)
                        val pivot = yMax - frac * (yMax - yMin)
                        if (autoRange) {
                            autoRange = false
                            onAutoRangeChange?.invoke(false)
                        }
                        yMin = pivot - (pivot - yMin) * factor
                        yMax = pivot + (yMax - pivot) * factor
                    }
                } else {
                    // 默认滚轮：横向缩放时间窗口，围绕光标所在时间点展开
                    val plotW = plotW()
                    val (t0, t1) = timeRange()
                    val frac = if (plotW > 0) ((e.x - marginLeft).toDouble() / plotW).coerceIn(0.0, 1.0) else 0.5
                    val pivotT = t0 + frac * (t1 - t0)
                    val newSpan = (timeSpanSec * factor).coerceIn(0.005, 3600.0)
                    timeSpanSec = newSpan
                    if (!followLatest) {
                        viewEndSec = pivotT + (1.0 - frac) * newSpan
                        val minEnd = earliestTimeSec() + timeSpanSec * 0.05
                        val maxEnd = latestTimeSec()
                        viewEndSec = viewEndSec.coerceIn(minEnd, max(minEnd, maxEnd))
                    }
                }

                if (hoverX >= 0) onCursorChange?.invoke(buildHoverState(hoverX, hoverY))
                needsRepaint = true
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    resetView()
                } else if (e.isAltDown) {
                    // Alt + 单击：在光标处放置/切换标记游标 A
                    val (t0, t1) = timeRange()
                    val plotW = plotW()
                    if (plotW > 0 && e.x in marginLeft..(marginLeft + plotW)) {
                        pinnedCursorTimeSec = t0 + ((e.x - marginLeft).toDouble() / plotW) * (t1 - t0)
                        needsRepaint = true
                    }
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    private fun updateCursorForHover(x: Int, y: Int) {
        cursor = if (!followLatest && returnToLiveBounds?.contains(x, y) == true) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else if (x in marginLeft..(marginLeft + plotW()) && y in marginTop..(marginTop + plotH())) {
            Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    fun setFrame(data: Map<Long, List<ScopeSample>>, variables: List<ScopeVariable>) {
        this.data = data
        this.variables = variables
        if (!isPaused) {
            needsRepaint = true
        }
    }

    fun clear() {
        data = emptyMap()
        variables = emptyList()
        viewEndSec = 0.0
        followLatest = true
        autoRange = true
        autoRangeNeedsImmediateFit = true
        autoRangeShrinkHoldDeadline = 0L
        pinnedCursorTimeSec = null
        hoverX = -1
        hoverY = -1
        returnToLiveBounds = null
        onFollowChange?.invoke(true)
        onAutoRangeChange?.invoke(true)
        onCursorChange?.invoke(null)
        needsRepaint = true
    }

    fun setFollowLatest(follow: Boolean) {
        followLatest = follow
        if (follow) {
            viewEndSec = latestTimeSec()
        }
        onFollowChange?.invoke(follow)
        needsRepaint = true
    }

    fun isFollowLatest(): Boolean = followLatest

    fun setAutoRange(auto: Boolean) {
        autoRange = auto
        if (auto) {
            autoRangeNeedsImmediateFit = true
            autoRangeShrinkHoldDeadline = 0L
        }
        onAutoRangeChange?.invoke(auto)
        needsRepaint = true
    }

    fun isAutoRange(): Boolean = autoRange

    fun setPaused(paused: Boolean) {
        isPaused = paused
        needsRepaint = true
    }

    fun isPaused(): Boolean = isPaused

    fun resetView() {
        followLatest = true
        autoRange = true
        autoRangeNeedsImmediateFit = true
        autoRangeShrinkHoldDeadline = 0L
        viewEndSec = latestTimeSec()
        onFollowChange?.invoke(true)
        onAutoRangeChange?.invoke(true)
        hoverX = -1
        hoverY = -1
        onCursorChange?.invoke(null)
        needsRepaint = true
    }

    fun setSampleRate(rate: Double) {
        currentRateHz = rate
    }

    /** 当前 X 轴每格时间（秒）。 */
    fun timePerDivision(): Double = timeSpanSec / V_DIVS

    /** 设置 X 轴每格时间（秒）。 */
    fun setTimePerDivision(seconds: Double) {
        if (seconds > 0) {
            timeSpanSec = (seconds * V_DIVS).coerceIn(0.005, 3600.0)
        }
        needsRepaint = true
    }

    /** 当前 Y 轴每格数值。 */
    fun valuePerDivision(): Double = (yMax - yMin) / H_DIVS

    /** 设置 Y 轴每格数值。 */
    fun setValuePerDivision(value: Double) {
        if (value <= 0) return
        val range = value * H_DIVS
        val center = (yMax + yMin) / 2.0
        yMin = center - range / 2.0
        yMax = center + range / 2.0
        autoRange = false
        onAutoRangeChange?.invoke(false)
        needsRepaint = true
    }

    /** 设置 Y 轴固定显示范围 [min, max]。 */
    fun setYRange(min: Double, max: Double) {
        if (min >= max) return
        yMin = min
        yMax = max
        autoRange = false
        onAutoRangeChange?.invoke(false)
        needsRepaint = true
    }

    /** 获取当前 Y 轴显示范围 (min, max)。 */
    fun yRange(): Pair<Double, Double> = Pair(yMin, yMax)

    /** 构建波形画布右键菜单。 */
    private fun buildContextMenu(clickX: Int, clickY: Int): JPopupMenu {
        val menu = JPopupMenu()

        if (!followLatest) {
            menu.add(JMenuItem("返回实时跟随最新 (Follow)").apply {
                addActionListener { setFollowLatest(true) }
            })
        }
        if (!autoRange) {
            menu.add(JMenuItem("恢复 Y 轴自动量程 (Auto Range)").apply {
                addActionListener { setAutoRange(true) }
            })
        }
        menu.addSeparator()

        val plotW = plotW()
        val (t0, t1) = timeRange()
        val clickT = if (plotW > 0 && clickX in marginLeft..(marginLeft + plotW)) {
            t0 + ((clickX - marginLeft).toDouble() / plotW) * (t1 - t0)
        } else null

        if (clickT != null) {
            menu.add(JMenuItem(String.format(Locale.ROOT, "在此放置标记游标 A (t=%.4fs)", clickT)).apply {
                addActionListener {
                    pinnedCursorTimeSec = clickT
                    needsRepaint = true
                }
            })
        }
        if (pinnedCursorTimeSec != null) {
            menu.add(JMenuItem("清除标记游标 A").apply {
                addActionListener {
                    pinnedCursorTimeSec = null
                    needsRepaint = true
                }
            })
        }
        menu.addSeparator()

        menu.add(JMenuItem("设置 X 单位长度（每格时间）...").apply {
            addActionListener { onRequestSetTimePerDiv?.invoke() }
        })
        menu.add(JMenuItem("设置 Y 单位长度（每格数值）...").apply {
            addActionListener { onRequestSetValuePerDiv?.invoke() }
        })
        menu.addSeparator()
        menu.add(JMenuItem("清除采样数据").apply {
            addActionListener { onRequestClearData?.invoke() }
        })
        menu.add(JMenuItem("导出 CSV...").apply {
            addActionListener { onRequestExportCsv?.invoke() }
        })
        return menu
    }

    private fun isDark(): Boolean {
        val bg = JBColor.background()
        return (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) < 128.0
    }

    /** 查找具有最多有效样本的参考通道。 */
    fun referenceSeries(): List<ScopeSample>? =
        data.values.maxByOrNull { it.size }

    /** 数据集中的最新时间戳（秒）。 */
    fun latestTimeSec(): Double {
        var latest = 0.0
        for (series in data.values) {
            if (series.isNotEmpty()) {
                val t = (series.maxOfOrNull { it.timestampNanos } ?: 0L) / 1e9
                if (t > latest) latest = t
            }
        }
        return latest
    }

    /** 数据集中的最早时间戳（秒）。 */
    fun earliestTimeSec(): Double {
        var earliest = Double.POSITIVE_INFINITY
        for (series in data.values) {
            if (series.isNotEmpty()) {
                val t = (series.minOfOrNull { it.timestampNanos } ?: 0L) / 1e9
                if (t < earliest) earliest = t
            }
        }
        return if (earliest.isInfinite()) 0.0 else earliest
    }

    private fun labelFont(style: Int, sizePt: Float): Font =
        JBFont.label().deriveFont(style, JBUI.scale(sizePt.toInt()).toFloat())

    private fun monoFont(style: Int, sizePt: Float): Font =
        Font("Consolas", style, JBUI.scale(sizePt.toInt()))

    /**
     * 当前可见时间区间 [t0, t1]（秒）。
     * - followLatest=true 时：右边缘为真实最新时间 latestTimeSec()；
     * - followLatest=false 时：右边缘为手动拖拽定位的绝对 viewEndSec，不会随着新采样数据注入而持续前进。
     */
    fun timeRange(): Pair<Double, Double> {
        val end = if (followLatest) latestTimeSec() else viewEndSec
        return Pair(end - timeSpanSec, end)
    }

    /** 二分查找：第一个 timestampNanos >= threshold 的索引。 */
    private fun lowerBound(series: List<ScopeSample>, thresholdNanos: Long): Int {
        var lo = 0
        var hi = series.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].timestampNanos < thresholdNanos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 查找最接近目标时间戳的样本索引。 */
    private fun nearestIndex(series: List<ScopeSample>, targetNanos: Long): Int {
        if (series.isEmpty()) return -1
        val idx = lowerBound(series, targetNanos)
        if (idx >= series.size) return series.size - 1
        if (idx > 0) {
            val d0 = abs(series[idx].timestampNanos - targetNanos)
            val d1 = abs(series[idx - 1].timestampNanos - targetNanos)
            if (d1 < d0) return idx - 1
        }
        return idx
    }

    private fun sampleValueAt(series: List<ScopeSample>, tSec: Double): Float? {
        if (series.isEmpty()) return null
        val targetNanos = (tSec * 1e9).toLong()
        val idx = nearestIndex(series, targetNanos)
        if (idx !in series.indices) return null
        val v = series[idx].value
        return if (v.isNaN()) null else v
    }

    /**
     * 计算规范 1-2-5 分度步进的 Y 轴 [min, max] 视窗范围（共 H_DIVS = 8 格）：
     * - 双极对称交流/正弦信号：0 点强制锁定在正中间第 4 格；
     * - 单极偏正信号（如 0~3.3V / 0~100）：0 点对齐底部；
     * - 单极偏负信号：0 点对齐顶部；
     * - 偏移信号：整倍数分度网格整数对齐。
     */
    internal fun calculateNiceRange(minVal: Double, maxVal: Double): Pair<Double, Double> {
        var low = minVal
        var high = maxVal
        if (low == high) {
            val span = if (abs(low) > 1e-6) abs(low) * 0.2 else 2.0
            low -= span / 2.0
            high += span / 2.0
        } else if (low > high) {
            val tmp = low
            low = high
            high = tmp
        }

        val dataSpan = high - low
        val rawUnit = dataSpan / 6.0
        val unit = niceUnit(rawUnit)

        // 1. 双极对称信号（零点在中间附近，如正弦波/交流信号）：
        if (low < 0.0 && high > 0.0) {
            val maxAbs = max(abs(low), abs(high))
            val minAbs = min(abs(low), abs(high))
            if (minAbs / maxAbs >= 0.2) {
                var symUnit = unit
                if (symUnit * 3.6 < maxAbs) {
                    symUnit = niceUnit(maxAbs / 3.6)
                }
                return Pair(-symUnit * 4.0, symUnit * 4.0)
            }
        }

        // 2. 单极性偏正信号（如 0~3.3V, 0~5V, 计数器 0~100）：
        if (low >= -unit * 0.5 && high > 0.0) {
            val targetMin = if (low < 0.0) -unit else 0.0
            var finalUnit = niceUnit((high - targetMin) / 7.0)
            while (targetMin + finalUnit * H_DIVS < high) {
                finalUnit = niceUnit((high - targetMin) / (H_DIVS - 1.0))
            }
            return Pair(targetMin, targetMin + finalUnit * H_DIVS)
        }

        // 3. 单极性偏负信号（如 -12V~0）：
        if (high <= unit * 0.5 && low < 0.0) {
            val targetMax = if (high > 0.0) unit else 0.0
            var finalUnit = niceUnit((targetMax - low) / 7.0)
            while (targetMax - finalUnit * H_DIVS > low) {
                finalUnit = niceUnit((targetMax - low) / (H_DIVS - 1.0))
            }
            return Pair(targetMax - finalUnit * H_DIVS, targetMax)
        }

        // 4. 通用带偏移信号（如 50~60 或 -100~-80）：
        val center = (low + high) / 2.0
        val centerQuantized = round(center / unit) * unit
        var targetMin = centerQuantized - 4.0 * unit
        var targetMax = centerQuantized + 4.0 * unit
        if (low < targetMin) {
            val shift = ceil((targetMin - low) / unit) * unit
            targetMin -= shift
            targetMax -= shift
        }
        if (high > targetMax) {
            val shift = ceil((high - targetMax) / unit) * unit
            targetMin += shift
            targetMax += shift
        }
        return Pair(targetMin, targetMax)
    }

    /**
     * 计算可见时间范围内的 Y 轴范围。
     * 采用规范 1-2-5 步进量程、全量采样扫描以及滞后防抖机制（Hysteresis + Hold-off），
     * 彻底解决正弦波等周期波形滚动时 Y 轴不断跳动颤抖的问题。
     */
    private fun computeAutoRange(t0: Double, t1: Double) {
        val t0Nanos = (t0 * 1e9).toLong()
        val t1Nanos = ((t1 + 1e-9) * 1e9).toLong()
        var minVal = Double.POSITIVE_INFINITY
        var maxVal = Double.NEGATIVE_INFINITY

        for (v in variables) {
            if (!v.visible) continue
            val series = data[v.address] ?: continue
            if (series.isEmpty()) continue
            val startIdx = lowerBound(series, t0Nanos)
            val endIdx = lowerBound(series, t1Nanos)
            if (startIdx >= endIdx) continue

            // 完整遍历可见窗口内的有效采样点，杜绝抽样步长跳步造成的波峰漏判与数值抖动
            for (i in startIdx until endIdx) {
                val valF = series[i].value
                if (!valF.isNaN()) {
                    val d = valF.toDouble()
                    if (d < minVal) minVal = d
                    if (d > maxVal) maxVal = d
                }
            }
        }

        if (minVal.isInfinite() || maxVal.isInfinite()) {
            if (yMin == yMax) {
                yMin = -1.0
                yMax = 1.0
            }
            return
        }

        // 首次初始化、切换模式或点击清空/复位后立即快速对齐最佳量程
        if (autoRangeNeedsImmediateFit || yMin >= yMax) {
            val (tMin, tMax) = calculateNiceRange(minVal, maxVal)
            yMin = tMin
            yMax = tMax
            autoRangeNeedsImmediateFit = false
            autoRangeShrinkHoldDeadline = 0L
            return
        }

        val currentSpan = yMax - yMin
        val dataSpan = maxVal - minVal

        // 1. 扩大量程判断（Expansion）：
        // 若波形溢出当前可视坐标系（或贴近边缘，占比超 95%），立即外扩
        val isOverflow = minVal < yMin || maxVal > yMax || (currentSpan > 0.0 && dataSpan > 0.95 * currentSpan)
        if (isOverflow) {
            val (tMin, tMax) = calculateNiceRange(minVal, maxVal)
            yMin = tMin
            yMax = tMax
            autoRangeShrinkHoldDeadline = 0L
            return
        }

        // 2. 滞后死区判断（Hysteresis Deadband）：
        // 评估波形在当前视窗内的有效显示占用比例（考虑零基准与对称基准）：
        val effectiveOccupancy = when {
            abs(yMin) < 1e-6 && minVal >= -1e-6 && yMax > 0.0 -> maxVal / yMax
            abs(yMax) < 1e-6 && maxVal <= 1e-6 && yMin < 0.0 -> abs(minVal) / abs(yMin)
            abs(yMin + yMax) < 1e-6 && yMax > 0.0 -> max(abs(minVal), abs(maxVal)) / yMax
            else -> if (currentSpan > 0.0) dataSpan / currentSpan else 0.5
        }

        // 若波形处于 30% ~ 95% 的合理可视高度，保持现有量程 100% 绝对静止，彻底消灭逐帧跳动
        if (effectiveOccupancy >= 0.30) {
            autoRangeShrinkHoldDeadline = 0L
            return
        }

        // 3. 收缩量程判断（Contraction / Hold-off）：
        // 若幅度显著降低（占比 < 30%），绝不立即缩小，必须持续低幅 1200ms 以上方允许收缩
        val now = System.currentTimeMillis()
        if (autoRangeShrinkHoldDeadline == 0L) {
            autoRangeShrinkHoldDeadline = now + 1200L
        } else if (now >= autoRangeShrinkHoldDeadline) {
            val (tMin, tMax) = calculateNiceRange(minVal, maxVal)
            yMin = tMin
            yMax = tMax
            autoRangeShrinkHoldDeadline = 0L
        }
    }

    private fun formatValue(value: Float): String {
        if (value == 0f) return "0"
        val a = abs(value)
        return when {
            a >= 1e6f || a < 1e-2f -> String.format(Locale.ROOT, "%.2e", value)
            a >= 1000f -> String.format(Locale.ROOT, "%.0f", value)
            else -> String.format(Locale.ROOT, "%.2f", value)
        }
    }

    private fun formatNum(v: Double): String {
        if (abs(v) < 1e-9) return "0"
        val a = abs(v)
        return when {
            a >= 1e6 || a < 1e-3 -> String.format(Locale.ROOT, "%.1e", v)
            a >= 1000.0 -> String.format(Locale.ROOT, "%.0f", v)
            a >= 10.0 -> String.format(Locale.ROOT, "%.1f", v)
            else -> String.format(Locale.ROOT, "%.2f", v)
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val dark = isDark()
            val bgColor = JBColor.background()
            val gridColor = if (dark) Color(255, 255, 255, 18) else Color(0xDB, 0xE2, 0xEE)
            val minorGrid = if (dark) Color(255, 255, 255, 9) else Color(0xED, 0xF1, 0xF7)
            val axisColor = if (dark) Color(0x62, 0x6A, 0x78) else Color(0x9A, 0xA4, 0xB2)
            val textColor = if (dark) Color(0xC0, 0xC7, 0xD2) else Color(0x4B, 0x55, 0x65)

            g2.color = bgColor
            g2.fillRect(0, 0, width, height)

            val plotW = plotW()
            val plotH = plotH()
            if (plotW <= 0 || plotH <= 0) return

            val ref = referenceSeries()
            val total = ref?.size ?: 0
            if (total < 2) {
                drawEmptyState(g2, dark, textColor, plotW, plotH)
                return
            }

            // 自适应/最新时间范围
            val (t0, t1) = timeRange()
            if (autoRange) {
                computeAutoRange(t0, t1)
            }
            val rangeY = if (abs(yMax - yMin) < 1e-9) 1.0 else yMax - yMin
            val dt = if (abs(t1 - t0) < 1e-9) 1.0 else (t1 - t0)

            fun xOf(t: Double): Double =
                marginLeft + ((t - t0) / dt) * plotW

            fun yOf(v: Double): Double =
                marginTop + (1.0 - (v - yMin) / rangeY) * plotH

            // --- Y 网格与刻度数值 ---
            g2.font = monoFont(Font.PLAIN, 9f)
            for (i in 0..H_DIVS) {
                val yPos = marginTop + (i * plotH / H_DIVS.toDouble())
                val value = yMax - (i * rangeY / H_DIVS)
                if (showGrid) {
                    g2.color = if (i == 0 || i == H_DIVS) gridColor else minorGrid
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(marginLeft, yPos.toInt(), width - marginRight, yPos.toInt())
                } else {
                    // 网格关闭时，绘制左侧 3px 刻度短线
                    g2.color = axisColor
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(marginLeft - 3, yPos.toInt(), marginLeft, yPos.toInt())
                }
                g2.color = textColor
                drawRightAligned(g2, formatNum(value), marginLeft - 8, yPos.toInt())
            }

            // --- X 网格与自适应时间小数位数 ---
            val stepSec = (t1 - t0) / V_DIVS
            val decimals = max(1, min(6, ceil(-log10(max(stepSec, 1e-9))).toInt() + 1))
            val timeFormat = "%.${decimals}fs"

            g2.font = monoFont(Font.PLAIN, 9f)
            for (i in 0..V_DIVS) {
                val xPos = marginLeft + (i * plotW / V_DIVS.toDouble())
                if (showGrid) {
                    g2.color = if (i == 0 || i == V_DIVS) gridColor else minorGrid
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(xPos.toInt(), marginTop, xPos.toInt(), height - marginBottom)
                } else {
                    // 网格关闭时，绘制底部 3px 刻度短线
                    g2.color = axisColor
                    g2.stroke = BasicStroke(1f)
                    g2.drawLine(xPos.toInt(), height - marginBottom, xPos.toInt(), height - marginBottom + 3)
                }
                val tVal = t0 + i * stepSec
                g2.color = textColor
                drawCentered(g2, String.format(Locale.ROOT, timeFormat, tVal), xPos.toInt(), height - marginBottom + 14)
            }

            // --- 零刻度线 ---
            if (yMin <= 0.0 && yMax >= 0.0) {
                val zeroY = yOf(0.0)
                g2.color = if (dark) Color(140, 160, 190, 110) else Color(0xBA, 0xC6, 0xD8)
                g2.stroke = BasicStroke(1.1f)
                g2.drawLine(marginLeft, zeroY.toInt(), width - marginRight, zeroY.toInt())
            }

            // --- 坐标轴 ---
            g2.color = axisColor
            g2.stroke = BasicStroke(1.2f)
            g2.drawLine(marginLeft, marginTop, marginLeft, height - marginBottom)
            g2.drawLine(marginLeft, height - marginBottom, width - marginRight, height - marginBottom)

            // --- 曲线（带裁切）---
            g2.clipRect(marginLeft, marginTop, plotW, plotH)
            val visibleVars = variables.filter { it.visible }
            for (variable in visibleVars) {
                val series = data[variable.address] ?: continue
                if (series.size < 2) continue
                drawCurve(g2, series, t0, t1, plotW, plotH, dark, variable, ::xOf, ::yOf)
            }
            g2.clip = null

            // --- 游标 A（测距标记线）---
            drawPinnedCursorA(g2, t0, t1, plotW, plotH, dark, ::xOf)

            // --- 悬停十字线与游标浮层 ---
            drawHoverOverlay(g2, t0, t1, plotW, plotH, dark, visibleVars, ::xOf, ::yOf)

            // --- 通道图例（大窗体开启时自绘；默认由底部紧凑通道芯片栏承载）---
            if (showInCanvasLegend && plotW > 450 && plotH > 240) {
                drawLegend(g2, visibleVars, dark)
            }

            // --- 状态胶囊 & “返回实时” 交互按钮 ---
            drawStatusPill(g2, dark, textColor, plotW)
            drawReturnToLiveButton(g2, dark, plotW)

        } finally {
            g2.dispose()
        }
    }

    private fun drawCurve(
        g2: Graphics2D,
        series: List<ScopeSample>,
        t0: Double,
        t1: Double,
        plotW: Int,
        plotH: Int,
        dark: Boolean,
        variable: ScopeVariable,
        xOf: (Double) -> Double,
        yOf: (Double) -> Double,
    ) {
        val t0Nanos = (t0 * 1e9).toLong()
        val t1Nanos = ((t1 + 1e-9) * 1e9).toLong()
        val startIdx = lowerBound(series, t0Nanos)
        val endIdx = lowerBound(series, t1Nanos)

        // 包含视野左右各 1 个样本，确保跨像素平滑连接到视窗边缘，避免两端出现空白缺口
        val actualStart = max(0, startIdx - 1)
        val actualEnd = min(series.size, endIdx + 1)
        val n = actualEnd - actualStart
        if (n <= 0) return

        val step = max(1, n / (plotW * 2))
        var baseColor = ScopePalette.colorForVar(variable)
        if (!dark) baseColor = baseColor.darker()

        val path = Path2D.Float()
        var first = true

        if (step <= 1) {
            var lastTs = -1L
            for (i in actualStart until actualEnd) {
                val s = series[i]
                if (s.value.isNaN()) {
                    first = true
                } else if (s.timestampNanos < lastTs) {
                    // 时间戳回退保护：防止乱序数据产生逆向反折线
                    continue
                } else {
                    lastTs = s.timestampNanos
                    val x = xOf(s.timestampNanos / 1e9)
                    val y = yOf(s.value.toDouble()).coerceIn(-5000.0, plotH + 5000.0)
                    if (first) {
                        path.moveTo(x, y)
                        first = false
                    } else {
                        path.lineTo(x, y)
                    }
                }
            }
        } else {
            // 包络降采样（Min/Max 峰值极值保留 + 严格时序顺逆序连接）：
            // 对每个分桶提取桶内有效样本的入口点、极小值点、极大值点、出口点，
            // 严格按照采样点在时间轴上的先后顺序（顺逆序）依次连线，
            // 彻底解决旧版固定连接 min -> max 导致正弦波下降沿产生假尖峰/毛刺的问题，
            // 保证鼠标悬停测得值与波形轨迹完全一致，波形平滑连续且极值不丢失。
            var i = actualStart
            var lastEmittedTs = -1L
            var lastEmittedY = Double.NaN

            while (i < actualEnd) {
                val blockEnd = min(actualEnd, i + step)
                var firstIdx = -1
                var lastIdx = -1
                var minIdx = -1
                var maxIdx = -1
                var hasGap = false

                for (j in i until blockEnd) {
                    val s = series[j]
                    if (s.value.isNaN()) {
                        hasGap = true
                        continue
                    }
                    if (s.timestampNanos < lastEmittedTs) continue // 时间戳乱序保护
                    if (firstIdx == -1) firstIdx = j
                    lastIdx = j
                    if (minIdx == -1 || s.value < series[minIdx].value) {
                        minIdx = j
                    }
                    if (maxIdx == -1 || s.value > series[maxIdx].value) {
                        maxIdx = j
                    }
                }

                if (firstIdx == -1) {
                    first = true
                } else {
                    // M4 降采样算法：在每个像素/分桶内，按时间索引顺序保留入口点、极值点与出口点。
                    // 索引天然代表采样点到达与发生的时间先后，彻底解决固定 min->max 连接或
                    // 逆序折线在下降沿产生的假尖峰毛刺，保证极值完整呈现且折线沿时间正向严格单调。
                    val orderedPoints = listOf(firstIdx, minIdx, maxIdx, lastIdx)
                        .distinct()
                        .sorted()
                        .map { series[it] }

                    for (pt in orderedPoints) {
                        if (pt.timestampNanos < lastEmittedTs) continue
                        val x = xOf(pt.timestampNanos / 1e9)
                        val y = yOf(pt.value.toDouble()).coerceIn(-5000.0, plotH + 5000.0)

                        // 避免在同一点重复连线
                        if (pt.timestampNanos == lastEmittedTs && y == lastEmittedY) continue

                        if (first) {
                            path.moveTo(x, y)
                            first = false
                        } else {
                            path.lineTo(x, y)
                        }
                        lastEmittedTs = pt.timestampNanos
                        lastEmittedY = y
                    }
                    if (hasGap) {
                        first = true
                    }
                }
                i = blockEnd
            }
        }

        if (dark && step <= 2) {
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 38)
            g2.stroke = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(path)
        }
        g2.color = baseColor
        g2.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(path)
    }

    private fun drawPinnedCursorA(
        g2: Graphics2D,
        t0: Double,
        t1: Double,
        plotW: Int,
        plotH: Int,
        dark: Boolean,
        xOf: (Double) -> Double,
    ) {
        val tA = pinnedCursorTimeSec ?: return
        if (tA < t0 || tA > t1) return
        val xA = xOf(tA)
        g2.color = if (dark) Color(0x00, 0xE5, 0xFF, 200) else Color(0x00, 0x88, 0xAA, 220)
        g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f, 3f), 0f)
        g2.drawLine(xA.toInt(), marginTop, xA.toInt(), marginTop + plotH)

        // 标签 A
        g2.font = labelFont(Font.BOLD, 10f)
        val pillText = String.format(Locale.ROOT, "Cursor A: %.4fs", tA)
        val fm = g2.fontMetrics
        val pw = fm.stringWidth(pillText) + 8
        val minX = marginLeft
        val maxX = max(marginLeft, marginLeft + plotW - pw)
        val px = (xA - pw / 2).toInt().coerceIn(minX, maxX)
        g2.color = if (dark) Color(0, 0, 0, 180) else Color(255, 255, 255, 210)
        g2.fillRoundRect(px, marginTop + 2, pw, 16, 4, 4)
        g2.color = if (dark) Color(0x00, 0xE5, 0xFF) else Color(0x00, 0x88, 0xAA)
        g2.drawString(pillText, px + 4, marginTop + 14)
    }

    private fun drawHoverOverlay(
        g2: Graphics2D,
        t0: Double,
        t1: Double,
        plotW: Int,
        plotH: Int,
        dark: Boolean,
        visibleVars: List<ScopeVariable>,
        xOf: (Double) -> Double,
        yOf: (Double) -> Double,
    ) {
        if (hoverX < marginLeft || hoverX > marginLeft + plotW || hoverY < marginTop || hoverY > marginTop + plotH) return
        val tHover = t0 + ((hoverX - marginLeft).toDouble() / plotW) * (t1 - t0)

        // 十字线
        val crossColor = if (dark) Color(255, 200, 60, 150) else Color(180, 120, 20, 160)
        g2.color = crossColor
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
        g2.drawLine(hoverX, marginTop, hoverX, marginTop + plotH)
        g2.drawLine(marginLeft, hoverY, marginLeft + plotW, hoverY)

        // 各通道取样点高亮圆点
        for (variable in visibleVars) {
            val series = data[variable.address] ?: continue
            val valF = sampleValueAt(series, tHover) ?: continue
            val sy = yOf(valF.toDouble())
            if (sy >= marginTop && sy <= marginTop + plotH) {
                g2.color = ScopePalette.colorForVar(variable)
                g2.fillOval(hoverX - 3, sy.toInt() - 3, 6, 6)
                g2.color = Color.WHITE
                g2.drawOval(hoverX - 3, sy.toInt() - 3, 6, 6)
            }
        }

        // 浮动数值 HUD 卡片（支持测距 dt、频率与各通道 dV）
        drawHoverTipCard(g2, tHover, plotW, plotH, dark, visibleVars)
    }

    private fun drawHoverTipCard(
        g2: Graphics2D,
        tHover: Double,
        plotW: Int,
        plotH: Int,
        dark: Boolean,
        visibleVars: List<ScopeVariable>,
    ) {
        val lines = mutableListOf<TipLine>()
        val tA = pinnedCursorTimeSec

        if (tA != null) {
            val dt = tHover - tA
            val freq = if (abs(dt) > 1e-9) 1.0 / abs(dt) else 0.0
            lines.add(TipLine("Cursor A: " + String.format(Locale.ROOT, "%.4fs", tA), Color(0x00, 0xE5, 0xFF)))
            lines.add(TipLine("Cursor B: " + String.format(Locale.ROOT, "%.4fs", tHover), Color(0xFF, 0xCB, 0x6B)))
            lines.add(TipLine(String.format(Locale.ROOT, "dt = %+.4fs  (f = %.1f Hz)", dt, freq), Color(0xFF, 0xFF, 0x80)))
            for (v in visibleVars) {
                val series = data[v.address] ?: continue
                val valB = sampleValueAt(series, tHover)
                val valA = sampleValueAt(series, tA)
                val color = ScopePalette.colorForVar(v)
                val valStr = if (valB != null) formatValue(valB) else "-"
                val deltaStr = if (valA != null && valB != null) String.format(Locale.ROOT, " [d%+.2f]", valB - valA) else ""
                lines.add(TipLine("${v.name}: $valStr$deltaStr", color))
            }
        } else {
            lines.add(TipLine("t = " + String.format(Locale.ROOT, "%.4fs", tHover), if (dark) Color(0xD0, 0xD7, 0xDE) else Color(0x33, 0x3D, 0x47)))
            for (v in visibleVars) {
                val series = data[v.address] ?: continue
                val valB = sampleValueAt(series, tHover)
                val color = ScopePalette.colorForVar(v)
                val valStr = if (valB != null) formatValue(valB) else "-"
                lines.add(TipLine("${v.name}: $valStr", color))
            }
        }

        g2.font = labelFont(Font.PLAIN, 11f)
        val fm = g2.fontMetrics
        val boxW = min(lines.maxOfOrNull { fm.stringWidth(it.text) }?.plus(24) ?: 130, 360)
        val lineH = fm.height + 3
        val boxH = 14 + lines.size * lineH

        var tipX = hoverX + 16
        var tipY = hoverY + 16
        if (tipX + boxW > marginLeft + plotW) tipX = hoverX - boxW - 16
        if (tipY + boxH > marginTop + plotH) tipY = hoverY - boxH - 16
        val minTipX = marginLeft + 6
        val maxTipX = max(minTipX, marginLeft + plotW - boxW - 6)
        val minTipY = marginTop + 6
        val maxTipY = max(minTipY, marginTop + plotH - boxH - 6)
        tipX = tipX.coerceIn(minTipX, maxTipX)
        tipY = tipY.coerceIn(minTipY, maxTipY)

        g2.color = if (dark) Color(22, 26, 34, 235) else Color(255, 255, 255, 240)
        g2.fillRoundRect(tipX, tipY, boxW, boxH, 8, 8)
        g2.color = if (dark) Color(255, 255, 255, 38) else Color(0xC0, 0xCA, 0xD8)
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(tipX, tipY, boxW, boxH, 8, 8)

        var y = tipY + fm.ascent + 6
        for (l in lines) {
            g2.color = l.color
            g2.drawString(l.text, tipX + 10, y)
            y += lineH
        }
    }

    private data class TipLine(val text: String, val color: Color)

    private fun drawLegend(g2: Graphics2D, visibleVars: List<ScopeVariable>, dark: Boolean) {
        var idx = 0
        g2.font = labelFont(Font.BOLD, 10f)
        val fm = g2.fontMetrics
        for (variable in visibleVars) {
            val series = data[variable.address] ?: continue
            val lastValid = series.asReversed().firstOrNull { !it.value.isNaN() }?.value
            var color = ScopePalette.colorForVar(variable)
            if (!dark) color = color.darker()
            val text = "${variable.name}: " + (if (lastValid != null) formatValue(lastValid) else "-")
            val chipW = max(90, fm.stringWidth(text) + 24)
            val rectX = marginLeft + 12
            val rectY = marginTop + 10 + idx * 20
            g2.color = if (dark) Color(0, 0, 0, 140) else Color(255, 255, 255, 170)
            g2.fillRoundRect(rectX, rectY, chipW, 17, 4, 4)
            g2.color = color
            g2.fillOval(rectX + 6, rectY + 4, 8, 8)
            g2.drawString(text, rectX + 18, rectY + 13)
            idx++
        }
    }

    private fun drawStatusPill(g2: Graphics2D, dark: Boolean, textColor: Color, plotW: Int) {
        if (plotW < 130) return
        val compact = plotW < 290
        val status = buildList {
            add(if (followLatest) (if (compact) "Follow" else "Follow Latest") else (if (compact) "History" else "Manual Pan (History)"))
            add(if (autoRange) (if (compact) "Auto" else "Auto Range") else (if (compact) "Zoom(Y)" else "Manual Zoom (Y)"))
            if (currentRateHz > 0) add(String.format(Locale.ROOT, if (compact) "%.0fHz" else "%.1f Hz", currentRateHz))
        }.joinToString(" | ")

        g2.font = labelFont(Font.PLAIN, 10f)
        val fm = g2.fontMetrics
        val pillW = fm.stringWidth(status) + 16
        val pillX = max(marginLeft, marginLeft + plotW - pillW)
        g2.color = if (dark) Color(0, 0, 0, 120) else Color(255, 255, 255, 215)
        g2.fillRoundRect(pillX, marginTop - 2, pillW, 20, 6, 6)
        g2.color = if (dark) Color(255, 255, 255, 30) else Color(0xC9, 0xD2, 0xE3)
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(pillX, marginTop - 2, pillW, 20, 6, 6)
        g2.color = if (!followLatest || !autoRange) Color(0xFF, 0xCB, 0x6B) else textColor
        g2.drawString(status, pillX + 8, marginTop + 12)
    }

    /** 绘制醒目的“返回实时”快捷操作按钮（仅在历史查看模式下出现）。 */
    private fun drawReturnToLiveButton(g2: Graphics2D, dark: Boolean, plotW: Int) {
        if (followLatest) {
            returnToLiveBounds = null
            return
        }
        val btnW = 120
        val btnH = 22
        val btnX = max(marginLeft, marginLeft + plotW - btnW)
        val btnY = marginTop + 24
        val rect = Rectangle(btnX, btnY, btnW, btnH)
        returnToLiveBounds = rect

        val hovered = hoverX in btnX..(btnX + btnW) && hoverY in btnY..(btnY + btnH)
        g2.color = if (hovered) {
            if (dark) Color(0x38, 0x8E, 0x3C) else Color(0x4C, 0xAF, 0x50)
        } else {
            if (dark) Color(0x2E, 0x7D, 0x32, 220) else Color(0xE8, 0xF5, 0xE9, 230)
        }
        g2.fillRoundRect(btnX, btnY, btnW, btnH, 6, 6)
        g2.color = if (dark) Color(0x81, 0xC7, 0x84) else Color(0x2E, 0x7D, 0x32)
        g2.stroke = BasicStroke(1.2f)
        g2.drawRoundRect(btnX, btnY, btnW, btnH, 6, 6)

        g2.font = labelFont(Font.BOLD, 10f)
        g2.color = if (hovered || dark) Color.WHITE else Color(0x1B, 0x5E, 0x20)
        val text = "<- Live (返回实时)"
        val tw = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, btnX + (btnW - tw) / 2, btnY + 15)
    }

    private fun drawEmptyState(g2: Graphics2D, dark: Boolean, textColor: Color, plotW: Int, plotH: Int) {
        val cx = marginLeft + plotW / 2
        val cy = marginTop + plotH / 2
        val title = "暂无采样数据 / No Scope Data"
        g2.color = textColor
        g2.font = labelFont(Font.BOLD, 14f)
        g2.drawString(title, cx - g2.fontMetrics.stringWidth(title) / 2, cy - 8)
        g2.font = labelFont(Font.PLAIN, 11f)
        val hint = "请在右侧表格添加通道，或编辑器右键变量 -> 添加到示波器，然后点击 [开始]"
        g2.color = if (dark) Color(150, 158, 170) else Color(130, 138, 150)
        g2.drawString(hint, cx - g2.fontMetrics.stringWidth(hint) / 2, cy + 14)
    }

    private fun buildHoverState(x: Int, y: Int): HoverState? {
        val plotW = plotW()
        if (plotW <= 0 || x !in marginLeft..(marginLeft + plotW)) return null
        val (t0, t1) = timeRange()
        val tHover = t0 + ((x - marginLeft).toDouble() / plotW) * (t1 - t0)

        val timeText = String.format(Locale.ROOT, "%.4fs", tHover)
        val deltaText = String.format(Locale.ROOT, "%+.4fs", tHover - t0)

        val values = mutableListOf<Pair<String, Float>>()
        val deltas = mutableListOf<Pair<String, Float>>()
        val tA = pinnedCursorTimeSec

        for (v in variables.filter { it.visible }) {
            val series = data[v.address] ?: continue
            val valB = sampleValueAt(series, tHover) ?: Float.NaN
            values.add(v.name to valB)
            if (tA != null) {
                val valA = sampleValueAt(series, tA)
                if (valA != null && !valB.isNaN()) {
                    deltas.add(v.name to (valB - valA))
                }
            }
        }

        val cursorAMeasuring = tA != null
        val cursorATimeText = if (tA != null) String.format(Locale.ROOT, "%.4fs", tA) else ""
        val deltaSecText = if (tA != null) String.format(Locale.ROOT, "%+.4fs", tHover - tA) else ""
        val freqText = if (tA != null && abs(tHover - tA) > 1e-9) String.format(Locale.ROOT, "%.1f Hz", 1.0 / abs(tHover - tA)) else ""

        return HoverState(
            timeText = timeText,
            deltaText = deltaText,
            values = values,
            cursorAMeasuring = cursorAMeasuring,
            cursorATimeText = cursorATimeText,
            deltaSecText = deltaSecText,
            freqText = freqText,
            deltas = deltas,
        )
    }

    private fun plotW(): Int = width - marginLeft - marginRight
    private fun plotH(): Int = height - marginTop - marginBottom

    private fun drawRightAligned(g2: Graphics2D, text: String, rightX: Int, baselineY: Int) {
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, rightX - w, baselineY + 4)
    }

    private fun drawCentered(g2: Graphics2D, text: String, centerX: Int, baselineY: Int) {
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, centerX - w / 2, baselineY)
    }

    companion object {
        /** 示波器 1-2-5 规范进位量程步进函数。 */
        fun niceUnit(raw: Double): Double {
            if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
            val exponent = floor(log10(raw))
            val fraction = raw / 10.0.pow(exponent)
            val niceFraction = when {
                fraction <= 1.0 + 1e-9 -> 1.0
                fraction <= 2.0 + 1e-9 -> 2.0
                fraction <= 5.0 + 1e-9 -> 5.0
                else -> 10.0
            }
            return niceFraction * 10.0.pow(exponent)
        }
    }
}
