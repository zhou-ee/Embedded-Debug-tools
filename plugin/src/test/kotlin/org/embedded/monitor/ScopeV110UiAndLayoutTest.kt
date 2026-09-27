package org.embedded.monitor

import org.embedded.monitor.core.ScopePalette
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ScopeWaveformPanel
import org.embedded.monitor.core.ValueFormat
import org.embedded.monitor.scope.ScopePanel
import org.embedded.monitor.settings.PersistedScopeChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class ScopeV110UiAndLayoutTest {

    @Test
    fun testScopeVariableCustomColorAndPalette() {
        val defaultVar = ScopeVariable(
            name = "default_var",
            address = 0x20000000L,
            size = 4,
            format = ValueFormat.F32,
            colorIndex = 2,
        )
        assertNull(defaultVar.customColor)
        assertEquals(ScopePalette.colorFor(2), ScopePalette.colorForVar(defaultVar))

        val customColor = Color(0x12, 0x34, 0x56)
        val customVar = ScopeVariable(
            name = "custom_var",
            address = 0x20000004L,
            size = 4,
            format = ValueFormat.F32,
            colorIndex = 2,
            customColor = customColor,
        )
        assertEquals(customColor, customVar.customColor)
        assertEquals(customColor, ScopePalette.colorForVar(customVar))

        defaultVar.customColor = Color(0xAA, 0xBB, 0xCC)
        assertEquals(Color(0xAA, 0xBB, 0xCC), ScopePalette.colorForVar(defaultVar))
    }

    @Test
    fun testWaveformSetYRange() {
        val panel = ScopeWaveformPanel()
        assertTrue(panel.isAutoRange())

        var autoRangeChanged: Boolean? = null
        panel.onAutoRangeChange = { autoRangeChanged = it }

        panel.setYRange(-50.0, 150.0)
        assertFalse(panel.isAutoRange())
        assertEquals(false, autoRangeChanged)

        val (yMin, yMax) = panel.yRange()
        assertEquals(-50.0, yMin, 1e-6)
        assertEquals(150.0, yMax, 1e-6)

        // Invalid range min >= max should be ignored
        panel.setYRange(200.0, 100.0)
        val (yMin2, yMax2) = panel.yRange()
        assertEquals(-50.0, yMin2, 1e-6)
        assertEquals(150.0, yMax2, 1e-6)
    }

    @Test
    fun testPersistedScopeChannelCustomColor() {
        val persistedDefault = PersistedScopeChannel(
            name = "test",
            address = 0x20000000L,
            size = 4,
            encoding = "float",
            format = "F32",
            colorIndex = 1,
            visible = true,
        )
        assertEquals(-1, persistedDefault.customColorRgb)

        val customRgb = Color(0x33, 0x66, 0x99).rgb
        val persistedCustom = PersistedScopeChannel(
            name = "test_custom",
            address = 0x20000000L,
            size = 4,
            encoding = "float",
            format = "F32",
            colorIndex = 1,
            visible = true,
            customColorRgb = customRgb,
        )
        assertEquals(customRgb, persistedCustom.customColorRgb)
    }

    @Test
    fun testFormatValueOutput() {
        assertEquals("0", ScopePanel.formatValue(0.0f))
        assertEquals("42.00", ScopePanel.formatValue(42.0f))
        assertEquals("1235", ScopePanel.formatValue(1234.5f))
        assertTrue(ScopePanel.formatValue(1e7f).contains("e"))
    }

    @Test
    fun testWaveformGridToggleAndGraticuleConstants() {
        val panel = ScopeWaveformPanel()
        assertTrue(panel.showGrid)

        panel.showGrid = false
        assertFalse(panel.showGrid)

        panel.showGrid = true
        assertTrue(panel.showGrid)

        assertEquals(8, panel.H_DIVS)
        assertEquals(10, panel.V_DIVS)
    }
}
