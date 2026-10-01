package org.embedded.monitor

import org.embedded.monitor.core.ScopeCsv
import org.embedded.monitor.core.ScopeSample
import org.embedded.monitor.core.ScopeSamples
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ScopeWaveformPanel
import org.embedded.monitor.core.ValueFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.awt.image.BufferedImage

class ScopeDataIntegrityTest {
    private val variable = ScopeVariable("sin_20hz", 0x2000009cL, 4, ValueFormat.F32)
    private val period = 1_000_000L

    private fun bytes(value: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()

    @Test
    fun rawPeakSurvivesStorageAndCsvWithoutBitReconstruction() {
        val values = listOf(16.18034f, 20f, 16.18034f, -19.9512043f, -13.8091335f, 3.45228338f)
        val series = ArrayDeque<ScopeSample>()
        values.forEachIndexed { i, value ->
            assertFalse(ScopeSamples.append(series, (i + 1) * period, bytes(value), variable, period, 100))
        }
        assertEquals(values.map { it.toRawBits() }, series.map { it.value.toRawBits() })
        val csv = StringWriter()
        assertEquals(values.size, ScopeCsv.write(csv, listOf(variable), mapOf(variable.address to series.toList())))
        assertEquals(values.map { it.toRawBits() }, csv.toString().lineSequence().drop(1)
            .filter { it.isNotEmpty() }.map { it.substringAfter(',').toFloat().toRawBits() }.toList())
    }

    @Test
    fun omittedEmptyAndShortValuesAreExplicitMissingPoints() {
        val series = ArrayDeque<ScopeSample>()
        for (raw in listOf(null, byteArrayOf(), byteArrayOf(1, 2))) {
            assertTrue(ScopeSamples.append(series, (series.size + 1) * period, raw, variable, period, 100))
        }
        assertEquals(3, series.size)
        assertTrue(series.all { it.value.isNaN() })
        val csv = StringWriter()
        assertEquals(3, ScopeCsv.write(csv, listOf(variable), mapOf(variable.address to series.toList())))
        assertEquals(listOf("0.001000000,", "0.002000000,", "0.003000000,"),
            csv.toString().lines().drop(1).filter { it.isNotEmpty() })
    }

    @Test
    fun longGapRetainsActualTimeAndStartsNewRun() {
        val series = ArrayDeque<ScopeSample>()
        ScopeSamples.append(series, 0, bytes(-20f), variable, period, 100)
        ScopeSamples.append(series, period, bytes(-19f), variable, period, 100)
        ScopeSamples.append(series, 30 * period, bytes(15f), variable, period, 100)
        ScopeSamples.append(series, 31 * period, bytes(16f), variable, period, 100)
        assertEquals(30 * period, series.elementAt(2).timestampNanos)
        assertTrue(series.elementAt(2).gapBefore)
        val runs = mutableListOf<Pair<Int, Int>>()
        ScopeSamples.forEachRun(series.toList(), 0, 4) { start, end -> runs.add(start to end) }
        assertEquals(listOf(0 to 2, 2 to 4), runs)
        assertFalse(ScopeSamples.isLongGap(0, 3 * period, period))
    }

    @Test
    fun nanInfinityAndOutOfOrderValuesSplitBeforeDownsampling() {
        val series = listOf(
            ScopeSample(1, 1f), ScopeSample(2, 2f), ScopeSample(3, Float.NaN),
            ScopeSample(4, 4f), ScopeSample(5, Float.POSITIVE_INFINITY),
            ScopeSample(6, 6f), ScopeSample(2, 7f), ScopeSample(8, 8f),
        )
        val runs = mutableListOf<Pair<Int, Int>>()
        ScopeSamples.forEachRun(series, 0, series.size) { start, end -> runs.add(start to end) }
        assertEquals(listOf(0 to 2, 3 to 4, 5 to 6, 7 to 8), runs)
    }

    @Test
    fun denseRenderingHoldsLastValueAcrossGap() {
        // V1.2.31 用户指定语义：缺样/空档不再断开，前值平延续到下一个有效采样
        for (missing in listOf(false, true)) {
            val samples = (0..612).map { ScopeSample(it * period, 1f) }.toMutableList()
            if (missing) samples.add(ScopeSample(613 * period, Float.NaN))
            samples.add(ScopeSample(813 * period, 1f, gapBefore = !missing))
            samples.addAll((814..1400).map { ScopeSample(it * period, 1f) })
            val panel = ScopeWaveformPanel()
            val image = BufferedImage(1450, 100, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            val xOf: (Double) -> Double = { it * 1000 }
            val yOf: (Double) -> Double = { 20.0 }
            try {
                val draw = ScopeWaveformPanel::class.java.declaredMethods.single { it.name == "drawCurve" }
                draw.isAccessible = true
                draw.invoke(panel, graphics, samples, 0.0, 1.5, 20, 100, false, variable, xOf, yOf)
                assertTrue("curve must be drawn", image.getRGB(400, 20) ushr 24 != 0)
                // 空档区间维持前值（y=20 处连续有像素），不再留白
                assertEquals("gap must hold last value when missing=$missing", 255, image.getRGB(700, 20) ushr 24)
                assertTrue("curve must resume after gap", image.getRGB(1000, 20) ushr 24 != 0)
            } finally {
                graphics.dispose()
                panel.stopRepaintTimer()
            }
        }
    }

    @Test
    fun cursorsDoNotSnapAcrossMissingSamplesOrLongGaps() {
        val series = listOf(
            ScopeSample(0, 1f), ScopeSample(10, 2f), ScopeSample(20, Float.NaN),
            ScopeSample(30, 3f), ScopeSample(100, 4f, gapBefore = true), ScopeSample(110, 5f),
        )
        assertEquals(2f, ScopeSamples.valueAt(series, 7))
        assertEquals(3f, ScopeSamples.valueAt(series, 30))
        for (time in listOf(-1L, 15L, 20L, 25L, 50L, 111L)) assertNull(ScopeSamples.valueAt(series, time))
        assertEquals(4f, ScopeSamples.valueAt(series, 100))
    }

    @Test
    fun csvMergesActualTimestampsWithoutFillingMissingCells() {
        val second = variable.copy(name = "other,\"channel\"", address = 0x20001000L)
        val snapshot = mapOf(
            variable.address to listOf(ScopeSample(1_000_000, 1f), ScopeSample(3_000_000, Float.NaN)),
            second.address to listOf(ScopeSample(2_000_000, 2f), ScopeSample(3_000_000, 3f)),
        )
        val csv = StringWriter()
        assertEquals(3, ScopeCsv.write(csv, listOf(variable, second), snapshot))
        assertEquals("time,sin_20hz,\"other,\"\"channel\"\"\"\n" +
            "0.001000000,1.0,\n0.002000000,,2.0\n0.003000000,,3.0\n", csv.toString())
    }

    @Test
    fun boundedBufferKeepsLatestRawAndMissingSamples() {
        val series = ArrayDeque<ScopeSample>()
        ScopeSamples.append(series, period, bytes(20f), variable, period, 2)
        ScopeSamples.append(series, 2 * period, null, variable, period, 2)
        ScopeSamples.append(series, 3 * period, bytes(-20f), variable, period, 2)
        assertEquals(2, series.size)
        assertTrue(series.first.value.isNaN())
        assertEquals(-20f, series.last.value, 0f)
    }
}
