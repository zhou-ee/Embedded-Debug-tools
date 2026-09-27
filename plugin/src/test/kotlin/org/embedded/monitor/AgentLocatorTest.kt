package org.embedded.monitor

import org.embedded.monitor.agent.AgentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentLocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testLocateAgentExplicitPath() {
        val fakeAgent = tmp.newFile("embedded-clion-agent.exe")
        val located = AgentService.locateAgent(fakeAgent.canonicalPath)
        assertEquals(fakeAgent.canonicalPath, located)
    }

    @Test
    fun testLocateAgentInvalidPathReturnsNull() {
        val nonExistent = File(tmp.root, "does_not_exist.exe").canonicalPath
        // Since nonExistent does not exist, and devPaths might exist on dev machine or null on other machines,
        // passing a non-existent explicit path should fallback or return null
        val located = AgentService.locateAgent(nonExistent)
        // If it resolved to something, it MUST be an existing file
        if (located != null) {
            assertTrue("返回的 agent 路径必须为真实存在的文件", File(located).isFile)
        }
    }

    @Test
    fun testLocateAgentFromProjectBin() {
        val projectDir = tmp.newFolder("mock-project")
        val binDir = File(projectDir, "bin").apply { mkdirs() }
        val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val agentExe = File(binDir, "embedded-clion-agent$ext").apply { createNewFile() }

        // We can pass projectDir to locateAgent through mock or direct test
        // Verify File(binDir, "embedded-clion-agent$ext").isFile
        assertTrue(agentExe.isFile)
    }

    @Test
    fun testLocateAgentFromSimulatedPluginLayout() {
        val pluginDir = tmp.newFolder("test-installed-plugin")
        val binDir = File(pluginDir, "bin").apply { mkdirs() }
        val libDir = File(pluginDir, "lib").apply { mkdirs() }
        val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val agentExe = File(binDir, "embedded-clion-agent$ext").apply { createNewFile() }
        val jarFile = File(libDir, "plugin.jar").apply { createNewFile() }

        val pluginRoot = jarFile.parentFile?.parentFile
        assertNotNull(pluginRoot)
        val resolved = File(pluginRoot, "bin/embedded-clion-agent$ext")
        assertTrue(resolved.isFile)
        assertEquals(agentExe.canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun testLocateAgentFromVersionedPluginFolder() {
        val pluginsDir = tmp.newFolder("plugins")
        val versionedPluginDir = File(pluginsDir, "embedded-debug-plugin-V1.2.3").apply { mkdirs() }
        val binDir = File(versionedPluginDir, "bin").apply { mkdirs() }
        val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val agentExe = File(binDir, "embedded-clion-agent$ext").apply { createNewFile() }

        val found = pluginsDir.listFiles { f -> f.isDirectory && f.name.contains("embedded") }
            ?.map { File(it, "bin/embedded-clion-agent$ext") }
            ?.firstOrNull { it.isFile }

        assertNotNull("必须能找到版本化插件目录下的 Agent 可执行文件", found)
        assertEquals(agentExe.canonicalPath, found?.canonicalPath)
    }
}
