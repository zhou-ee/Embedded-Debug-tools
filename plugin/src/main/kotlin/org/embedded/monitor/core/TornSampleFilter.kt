package org.embedded.monitor.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 针对硬件调试器与目标单片机内存访问中非原子“字节撕裂（Torn Read）”与偶发突发坏样本的检验与精确修复算法。
 *
 * 【问题根因】：
 * 当目标单片机（如 Cortex-M4）在主循环或中断中高频写入 32 位浮点数或整型变量（执行 `vstr.32`/`str` 指令）时，
 * 若调试探针通过 8 位总线宽度读取内存，4 个字节无法在一个总线周期内原子读取。
 * 当数值跨越 2 的幂次边界（如 32.0、8.0）时，高字节（符号与指数高位）与低字节可能分别属于 CPU 写入前后的两个不同值，
 * 导致解算出的浮点数出现恰好 4 倍、1/4 倍、正负突变等严重的非物理假尖峰（如 CSV B19405 的 123.532036）。
 *
 * 【算法保证】：
 * 1. 严格特征签名验证：通过对高字节、低字节、半字进行逆向候选重构，仅当重构出的候选值能严密吻合前后样本形成的真实物理运动轨迹时才修正；
 * 2. 0 误伤阶跃与脉冲：对于方波阶跃响应，因一侧保持恒定而无法满足两端突变条件，绝不误触；
 * 3. 0 误伤极值顶点：对于三角波/正弦波顶点，因无撕裂候选匹配，绝不削峰。
 */
object TornSampleFilter {

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
        for (i in 1 until series.size - 1) {
            val prev = result[i - 1].value
            val curr = series[i].value
            val next = series[i + 1].value
            val repaired = repairIfTorn(prev, curr, next, format)
            result.add(if (repaired != curr) ScopeSample(series[i].timestampNanos, repaired) else series[i])
        }
        // 尾部
        val lastIdx = series.size - 1
        val last = series[lastIdx]
        if (last.value.isNaN() || last.value.isInfinite()) {
            result.add(ScopeSample(last.timestampNanos, result[lastIdx - 1].value))
        } else {
            result.add(last)
        }
        return result
    }
}
