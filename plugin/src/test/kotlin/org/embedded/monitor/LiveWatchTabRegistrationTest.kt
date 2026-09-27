package org.embedded.monitor

import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.settings.EmbeddedMonitorConfigurable
import org.embedded.monitor.watch.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveWatchTabRegistrationTest {

    @Test
    fun testAgentServiceDebugTabConstants() {
        assertEquals("EmbeddedLiveVariableWatch", AgentService.LIVE_WATCH_DEBUG_CONTENT_ID)
        assertEquals("EmbeddedLiveWatch", AgentService.LEGACY_LIVE_WATCH_DEBUG_CONTENT_ID)
        assertEquals(11800, AgentService.LIVE_WATCH_DEBUG_TAB_ID)
        assertEquals("实时变量监视", AgentService.LIVE_WATCH_DISPLAY_NAME)
    }

    @Test
    fun testDebugTabIdIsolationFromBuiltInTabs() {
        val tabId = AgentService.LIVE_WATCH_DEBUG_TAB_ID
        // In CLion / IntelliJ RunnerLayout:
        // Tab 0 = Debugger ("Threads & Variables" / "线程与变量")
        // Tab 10 = CLion official Live Watch ("实时监视" for J-Link/ST-Link)
        // Tab 11787 = CLion official SVD Peripherals ("外设")
        assertNotEquals("Tab ID must not be 0 to avoid being nested in 'Threads & Variables'", 0, tabId)
        assertNotEquals("Tab ID must not collide with official Live Watch (tab 10)", 10, tabId)
        assertNotEquals("Tab ID must not collide with official SVD Peripherals (tab 11787)", 11787, tabId)
        assertTrue("Tab ID must be a positive integer to form an independent top-level tab", tabId > 0)
    }

    @Test
    fun testWatchItemDeduplication() {
        val list = mutableListOf<WatchItem>()
        val item1 = WatchItem("w1", "g_counter", 0x20000000L, 4, "unsigned", "uint32_t")
        list.add(item1)

        val dupExpr = "G_COUNTER"
        val exists = list.any { it.expr.equals(dupExpr.trim(), ignoreCase = true) }
        assertTrue(exists)

        val otherExpr = "g_motor"
        val notExists = list.any { it.expr.equals(otherExpr.trim(), ignoreCase = true) }
        assertFalse(notExists)
    }

    @Test
    fun testPluginXmlActionAndDescriptionText() {
        val pluginXmlFile = File("src/main/resources/META-INF/plugin.xml")
        assertTrue("plugin.xml must exist", pluginXmlFile.exists())
        val content = pluginXmlFile.readText()
        assertTrue("Action text must be updated to 实时变量监视", content.contains("text=\"添加到实时变量监视\""))
        assertTrue("Group description must be updated to 实时变量监视", content.contains("description=\"添加到实时变量监视/示波器\""))
        assertTrue("Plugin description bullet must refer to 实时变量监视", content.contains("<b>实时变量监视</b>"))
        assertTrue("plugin.xml must contain EmbeddedRegisters toolWindow", content.contains("id=\"EmbeddedRegisters\""))
        assertTrue("plugin.xml description must contain 寄存器实时监视", content.contains("<b>寄存器实时监视</b>"))
    }

    @Test
    fun testRegisterDebugTabConstants() {
        assertEquals("EmbeddedRegisterMonitor", AgentService.REGISTER_DEBUG_CONTENT_ID)
        assertEquals(11801, AgentService.REGISTER_DEBUG_TAB_ID)
        assertEquals("寄存器实时监视", AgentService.REGISTER_DISPLAY_NAME)
        assertNotEquals(AgentService.LIVE_WATCH_DEBUG_TAB_ID, AgentService.REGISTER_DEBUG_TAB_ID)
    }

    @Test
    fun testConfigurableDisplayName() {
        val dummyProject = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, _, _ -> null } as com.intellij.openapi.project.Project
        val configurable = EmbeddedMonitorConfigurable(dummyProject)
        assertTrue(
            "Settings configurable must use 实时变量监视",
            configurable.displayName.contains("实时变量监视"),
        )
    }
}
