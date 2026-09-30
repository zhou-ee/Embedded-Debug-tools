package org.embedded.monitor.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 历史启发式重构算法，保留供离线诊断对照；示波采样与 CSV 导出不使用。
 * 仅凭三个相邻值无法区分撕裂、真实峰值和采样空档，算法会误改有效样本。
 * 例如 16.18034 → 20 → 16.18034 会被削成 16.125，不能作为原始测量数据。
 */
object TornSampleFilter {

    /** int 位模式重构的适用上限：float 只有 24 位有效尾数。 */
    private const val INT24_LIMIT = 1 shl 24

    fun repairIfTorn(prev: Float, curr: Float, next: Float, format: ValueFormat): Float {
        if (curr.isNaN() || curr.isInfinite()) {
            return if (!prev.isNaN() && !prev.isInfinite() && !next.isNaN() && !next.isInfinite()) {
                (prev + next) / 2.0f
            } else curr
        }
        if (prev.isNaN() || prev.isInfinite() || next.isNaN() || next.isInfinite()) {
            return curr
        }

        val vMid = (prev + next) / 2.0f
        val step = abs(next - prev)

        // 必须同时偏离前后两侧样本达到突变门槛（单侧阶跃/方波绝不满足此条件）
        val spikeThreshold = max(step * 2.5f, 0.5f)
        val dPrev = abs(curr - prev)
        val dNext = abs(curr - next)
        if (dPrev < spikeThreshold || dNext < spikeThreshold) {
            return curr
        }

        // 尖峰必须是突发孤立点：两侧基底相对平缓，而当前点尖锐突起
        val spikeHeight = minOf(dPrev, dNext)
        if (step > spikeHeight * 0.7f) {
            return curr
        }

        val matchTol = max(step * 1.5f, 0.02f * (abs(vMid) + 1.0f))

        return when (format) {
            ValueFormat.F32 -> repairF32(prev, curr, next, vMid, matchTol)
            ValueFormat.I32, ValueFormat.U32 -> repairInt32(prev, curr, next, vMid, matchTol)
            ValueFormat.I16, ValueFormat.U16 -> repairInt16(prev, curr, next, vMid, matchTol)
            else -> {
                if (spikeHeight > max(step * 20.0f, 10.0f)) vMid else curr
            }
        }
    }

    private fun repairF32(prev: Float, curr: Float, next: Float, vMid: Float, matchTol: Float): Float {
        val currBits = java.lang.Float.floatToRawIntBits(curr)
        val prevBits = java.lang.Float.floatToRawIntBits(prev)
        val nextBits = java.lang.Float.floatToRawIntBits(next)

        val candidates = mutableListOf<Int>()

        // 模式 1：最高字节（Byte 3，符号与高位指数）被撕裂（最典型的浮点指数撕裂，如 B19405）
        if ((currBits and 0xFF000000.toInt()) != (prevBits and 0xFF000000.toInt())) {
            candidates.add((currBits and 0x00FFFFFF) or (prevBits and 0xFF000000.toInt()))
        }
        if ((currBits and 0xFF000000.toInt()) != (nextBits and 0xFF000000.toInt())) {
            candidates.add((currBits and 0x00FFFFFF) or (nextBits and 0xFF000000.toInt()))
        }

        // 模式 2：低 3 字节被撕裂，高字节属于当前
        if ((currBits and 0xFF000000.toInt()) != (prevBits and 0xFF000000.toInt())) {
            candidates.add((prevBits and 0x00FFFFFF) or (currBits and 0xFF000000.toInt()))
        }
        if ((currBits and 0xFF000000.toInt()) != (nextBits and 0xFF000000.toInt())) {
            candidates.add((nextBits and 0x00FFFFFF) or (currBits and 0xFF000000.toInt()))
        }

        // 模式 3：半字（16位）撕裂
        if ((currBits and 0xFFFF0000.toInt()) != (prevBits and 0xFFFF0000.toInt())) {
            candidates.add((currBits and 0x0000FFFF) or (prevBits and 0xFFFF0000.toInt()))
        }
        if ((currBits and 0xFFFF0000.toInt()) != (nextBits and 0xFFFF0000.toInt())) {
            candidates.add((currBits and 0x0000FFFF) or (nextBits and 0xFFFF0000.toInt()))
        }

        var bestCandidate: Float? = null
        var minErr = Float.MAX_VALUE
        for (bits in candidates) {
            val c = java.lang.Float.intBitsToFloat(bits)
            if (c.isNaN() || c.isInfinite()) continue
            // 候选值必须重构出中间值，不能简单退化等于前后采样点（避免削峰）
            if (c == prev || c == next) continue
            val err = abs(c - vMid)
            if (err <= matchTol && err < minErr) {
                minErr = err
                bestCandidate = c
            }
        }

        return bestCandidate ?: curr
    }

    private fun repairInt32(prev: Float, curr: Float, next: Float, vMid: Float, matchTol: Float): Float {
        // 位模式重构基于"整数值经 float 转换后取整"：|v| >= 2^24 时 float 已无法精确
        // 表示该值，重构出的位模式必然失真，宁可放弃修复也不修出错值
        if (abs(curr) >= INT24_LIMIT || abs(prev) >= INT24_LIMIT || abs(next) >= INT24_LIMIT) return curr
        val currBits = curr.toLong().toInt()
        val prevBits = prev.toLong().toInt()
        val nextBits = next.toLong().toInt()

        val candidates = mutableListOf<Int>()
        if ((currBits and 0xFF000000.toInt()) != (prevBits and 0xFF000000.toInt())) {
            candidates.add((currBits and 0x00FFFFFF) or (prevBits and 0xFF000000.toInt()))
        }
        if ((currBits and 0xFF000000.toInt()) != (nextBits and 0xFF000000.toInt())) {
            candidates.add((currBits and 0x00FFFFFF) or (nextBits and 0xFF000000.toInt()))
        }
        if ((currBits and 0xFFFF0000.toInt()) != (prevBits and 0xFFFF0000.toInt())) {
            candidates.add((currBits and 0x0000FFFF) or (prevBits and 0xFFFF0000.toInt()))
        }
        if ((currBits and 0xFFFF0000.toInt()) != (nextBits and 0xFFFF0000.toInt())) {
            candidates.add((currBits and 0x0000FFFF) or (nextBits and 0xFFFF0000.toInt()))
        }

        var bestCandidate: Float? = null
        var minErr = Float.MAX_VALUE
        for (bits in candidates) {
            val c = bits.toFloat()
            if (c == prev || c == next) continue
            val err = abs(c - vMid)
            if (err <= matchTol && err < minErr) {
                minErr = err
                bestCandidate = c
            }
        }
        return bestCandidate ?: curr
    }

    private fun repairInt16(prev: Float, curr: Float, next: Float, vMid: Float, matchTol: Float): Float {
        // 同 repairInt32：int16 全域可被 float 精确表示，无需边界保护
        val currBits = curr.toInt() and 0xFFFF
        val prevBits = prev.toInt() and 0xFFFF
        val nextBits = next.toInt() and 0xFFFF

        val candidates = mutableListOf<Int>()
        if ((currBits and 0xFF00) != (prevBits and 0xFF00)) {
            candidates.add((currBits and 0x00FF) or (prevBits and 0xFF00))
        }
        if ((currBits and 0xFF00) != (nextBits and 0xFF00)) {
            candidates.add((currBits and 0x00FF) or (nextBits and 0xFF00))
        }

        var bestCandidate: Float? = null
        var minErr = Float.MAX_VALUE
        for (bits in candidates) {
            val c = bits.toShort().toFloat()
            if (c == prev || c == next) continue
            val err = abs(c - vMid)
            if (err <= matchTol && err < minErr) {
                minErr = err
                bestCandidate = c
            }
        }
        return bestCandidate ?: curr
    }

    /**
     * 对完整样本序列进行批量平滑校验（供 CSV 导出及离线分析使用）。
     */
    fun repairSeries(series: List<ScopeSample>, format: ValueFormat): List<ScopeSample> {
        if (series.size < 3) return series
        val result = ArrayList<ScopeSample>(series.size)
        // 头部
        result.add(series[0])
        // 记录最近一个有效修复值：尾部 NaN 不能直接复制前一修复点——
        // 若前一点同样是 NaN 会把 NaN 继续传播出去
        var lastValid = if (series[0].value.isNaN() || series[0].value.isInfinite()) Float.NaN else series[0].value
        for (i in 1 until series.size - 1) {
            val prev = result[i - 1].value
            val curr = series[i].value
            val next = series[i + 1].value
            val repaired = repairIfTorn(prev, curr, next, format)
            result.add(if (repaired != curr) ScopeSample(series[i].timestampNanos, repaired) else series[i])
            if (!repaired.isNaN() && !repaired.isInfinite()) lastValid = repaired
        }
        // 尾部
        val lastIdx = series.size - 1
        val last = series[lastIdx]
        if (last.value.isNaN() || last.value.isInfinite()) {
            result.add(ScopeSample(last.timestampNanos, lastValid))
        } else {
            result.add(last)
        }
        return result
    }
}
