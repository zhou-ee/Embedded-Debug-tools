package org.embedded.monitor

import org.embedded.monitor.core.ScopeSample
import org.embedded.monitor.core.TornSampleFilter
import org.embedded.monitor.core.ValueFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TornSampleFilterTest {

    @Test
    fun repairB19405TornFloat() {
        // 用户导出的 scope_data.csv 中 B19405 实际异常样本：
        // 30.882961 -> 123.532036 (0x42f71067) -> 33.278750
        val prev = 30.882961f
        val curr = 123.532036f
        val next = 33.278750f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.F32)

        // 验证已从 123.53 畸变点精确修复回 30.88~33.28 连续轨迹内
        assertTrue("repaired value $repaired should be within smooth trajectory", abs(repaired - 32.08f) < 1.5f)
        assertTrue("repaired value must not remain near 123.53 glitch", abs(repaired - 123.532036f) > 80f)
    }

    @Test
    fun repairRow7745NegativeTornFloat() {
        // Line 7745: -34.129574 -> -8.2428055 -> -31.780672
        val prev = -34.129574f
        val curr = -8.2428055f
        val next = -31.780672f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.F32)

        assertTrue("repaired value $repaired should be ~ -32.97f", abs(repaired - (-32.97f)) < 0.2f)
        assertTrue("repaired value must not remain near -8.24 glitch", abs(repaired - (-8.2428055f)) > 20f)
    }

    @Test
    fun repairRow8563TornFloat() {
        // Line 8563: 7.333741 -> 29.334965 -> 8.875510
        val prev = 7.333741f
        val curr = 29.334965f
        val next = 8.875510f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.F32)

        assertTrue("repaired value $repaired should be within smooth trajectory [7.33, 8.87]", abs(repaired - 8.10f) < 1.0f)
        assertTrue("repaired value must not remain near 29.33 glitch", abs(repaired - 29.334965f) > 15f)
    }

    @Test
    fun repairRow9069TornFloat() {
        // Line 9069: -30.576492 -> -127.190575 -> -32.987747
        val prev = -30.576492f
        val curr = -127.190575f
        val next = -32.987747f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.F32)

        assertTrue("repaired value $repaired should be ~ -31.80f", abs(repaired - (-31.797644f)) < 0.2f)
        assertTrue("repaired value must not remain near -127.19 glitch", abs(repaired - (-127.190575f)) > 80f)
    }

    @Test
    fun repairRow16690TornFloatOnSin1Hz() {
        // Line 16690 on sin_1hz: 32.587242 -> 8.146811 -> 31.106113
        val prev = 32.587242f
        val curr = 8.146811f
        val next = 31.106113f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.F32)

        assertTrue("repaired value $repaired should be within smooth trajectory [31.10, 32.58]", abs(repaired - 31.84f) < 1.0f)
        assertTrue("repaired value must not remain near 8.14 glitch", abs(repaired - 8.146811f) > 20f)
    }

    @Test
    fun preserveSquareWaveStepEdges() {
        // 方波正跳变：0 -> 100 -> 100
        assertEquals(100f, TornSampleFilter.repairIfTorn(0f, 100f, 100f, ValueFormat.F32), 0f)
        // 方波负跳变：100 -> 0 -> 0
        assertEquals(0f, TornSampleFilter.repairIfTorn(100f, 0f, 0f, ValueFormat.F32), 0f)
    }

    @Test
    fun preserveNormalWaveformAndPeaks() {
        // 正常正弦波平滑点
        assertEquals(11.2f, TornSampleFilter.repairIfTorn(10.0f, 11.2f, 12.4f, ValueFormat.F32), 1e-5f)
        // 三角波/正弦波真实极值峰顶（无撕裂候选匹配，绝不削峰）
        assertEquals(50.0f, TornSampleFilter.repairIfTorn(48.0f, 50.0f, 48.0f, ValueFormat.F32), 0f)
    }

    @Test
    fun repairSeriesWholeSequence() {
        val series = listOf(
            ScopeSample(1000L, 30.882961f),
            ScopeSample(2000L, 123.532036f), // B19405 坏样本
            ScopeSample(3000L, 33.278750f),
            ScopeSample(4000L, 35.544540f),
        )
        val repaired = TornSampleFilter.repairSeries(series, ValueFormat.F32)
        assertEquals(4, repaired.size)
        assertEquals(30.882961f, repaired[0].value, 1e-5f)
        assertTrue("sample 1 must be repaired from 123.53", abs(repaired[1].value - 32.08f) < 1.5f)
        assertEquals(33.278750f, repaired[2].value, 1e-5f)
        assertEquals(35.544540f, repaired[3].value, 1e-5f)
    }

    @Test
    fun repairInt32TornRead() {
        // 计数器跨越 16 位边界：65535 -> 131070 (0x0001FFFE, 半字撕裂) -> 65537
        val prev = 65535f
        val curr = 131070f
        val next = 65537f
        val repaired = TornSampleFilter.repairIfTorn(prev, curr, next, ValueFormat.U32)
        assertEquals(65536f, repaired, 2f)
    }
}
