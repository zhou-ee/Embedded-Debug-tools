package org.embedded.monitor.core

import java.util.ArrayDeque

/** 原始采样存储及有效连续段；不猜测、插值或重构目标数值。 */
object ScopeSamples {
    fun isLongGap(previousNanos: Long, currentNanos: Long, periodNanos: Long): Boolean =
        currentNanos - previousNanos > periodNanos.coerceAtLeast(1) * 3

    /** 返回是否缺少该目标的完整字节；保留缺失点和实际时间。 */
    fun append(
        series: ArrayDeque<ScopeSample>, timestampNanos: Long, bytes: ByteArray?,
        variable: ScopeVariable, periodNanos: Long, capacity: Int,
    ): Boolean {
        val missing = bytes == null || bytes.size != variable.size
        val value = if (missing) Float.NaN else variable.format.decode(bytes)
        val previous = series.peekLast()
        series.addLast(ScopeSample(timestampNanos, value,
            previous != null && isLongGap(previous.timestampNanos, timestampNanos, periodNanos)))
        while (series.size > capacity.coerceAtLeast(1)) series.removeFirst()
        return missing
    }

    /** 游标只在有效连续段内吸附最近采样点，不能用缺口边界值冒充缺失数据。 */
    fun valueAt(series: List<ScopeSample>, timestampNanos: Long): Float? {
        if (series.isEmpty() || timestampNanos < series.first().timestampNanos ||
            timestampNanos > series.last().timestampNanos) return null
        var lo = 0
        var hi = series.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].timestampNanos < timestampNanos) lo = mid + 1 else hi = mid
        }
        val next = series[lo]
        if (next.timestampNanos == timestampNanos) return next.value.takeIf { it.isFinite() }
        val previous = series.getOrNull(lo - 1) ?: return null
        if (next.gapBefore || !next.value.isFinite() || !previous.value.isFinite()) return null
        return if (timestampNanos - previous.timestampNanos < next.timestampNanos - timestampNanos)
            previous.value else next.value
    }

    /**
     * 缺样/空档"维持原值"遍历（stair-step）：NaN 与缺口区间以前一个有效值平延续，
     * 直到下一个有效采样（用户指定的渲染语义：缺样不断开，保持原值）。
     * emit(时间秒, 绘制值)——绘制值可能是维持的前值而非本样本原始值。
     */
    fun forEachHeld(series: List<ScopeSample>, start: Int, end: Int, emit: (Double, Float) -> Unit) {
        var lastFinite: Float? = null
        for (i in start until end) {
            val s = series[i]
            val tSec = s.timestampNanos / 1e9
            val v = s.value
            if (v.isFinite()) {
                if (lastFinite != null && s.gapBefore) {
                    // 空档：前值平延续到本采样时刻（维持原值直到下一次采样）
                    emit(tSec, lastFinite)
                }
                emit(tSec, v)
                lastFinite = v
            } else if (lastFinite != null) {
                // NaN 缺样：维持前值
                emit(tSec, lastFinite)
            }
        }
    }

    /** 分段发生在降采样之前，避免同一像素桶跨越缺口连接极值。 */
    fun forEachRun(series: List<ScopeSample>, start: Int, end: Int, emit: (Int, Int) -> Unit) {
        var runStart = -1
        var lastTime = Long.MIN_VALUE
        for (i in start until end) {
            val sample = series[i]
            if (!sample.value.isFinite() || sample.timestampNanos < lastTime || sample.gapBefore) {
                if (runStart >= 0) emit(runStart, i)
                runStart = -1
            }
            if (sample.value.isFinite() && sample.timestampNanos >= lastTime) {
                if (runStart < 0) runStart = i
                lastTime = sample.timestampNanos
            }
        }
        if (runStart >= 0) emit(runStart, end)
    }
}
