package org.embedded.monitor

import org.embedded.monitor.cmake.OpenOcdConfigReader
import org.jdom.input.SAXBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

class OpenOcdConfigReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testPortListeningDetection() {
        assertFalse(OpenOcdConfigReader.isOpenOcdPortListening(-1))
        assertFalse(OpenOcdConfigReader.isOpenOcdPortListening(0))
        assertFalse(OpenOcdConfigReader.isOpenOcdPortListening(70000))

        // 本地临时 ServerSocket 验证真实连接检测
        ServerSocket(0).use { server ->
            val port = server.localPort
            assertTrue(OpenOcdConfigReader.isOpenOcdPortListening(port, 500))
        }

        // 显式绑定 127.0.0.1 验证回环检测
        ServerSocket().use { server ->
            server.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            val port = server.localPort
            assertTrue("127.0.0.1 绑定的端口必须被成功检测", OpenOcdConfigReader.isOpenOcdPortListening(port, 500))
        }
    }

    @Test
    fun testWorkspaceAndRunConfigurationsXmlParsing() {
        val projectDir = tmp.newFolder("test-clion-project")
        val ideaDir = File(projectDir, ".idea").apply { mkdirs() }
        val rcDir = File(ideaDir, "runConfigurations").apply { mkdirs() }

        // 1. 模拟 workspace.xml 中的 OpenOCD 运行配置
        File(ideaDir, "workspace.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="RunManager">
                <configuration name="OCD STM32G4" type="com.jetbrains.cidr.embedded.openocd.conf.type">
                  <openocd board-config="board/stm32g4discovery.cfg" gdb-port="3333" telnet-port="4444"/>
                </configuration>
                <configuration name="Other Target" type="CMakeRunConfigurationType" />
              </component>
            </project>
            """.trimIndent(),
        )

        // 2. 模拟 runConfigurations/ 中的项目级 OpenOCD 配置
        File(rcDir, "OpenOCD_DAPLink.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration name="OpenOCD DAPLink" type="com.jetbrains.cidr.embedded.openocd.conf.type">
                <openocd board-config="target/stm32g4x.cfg" gdb-port="3334" telnet-port="4445"/>
              </configuration>
            </component>
            """.trimIndent(),
        )

        // 测试直接解析两处 XML
        val saxBuilder = SAXBuilder()
        val wsRoot = saxBuilder.build(File(ideaDir, "workspace.xml")).rootElement
        var foundWorkspace = false
        for (comp in wsRoot.getChildren("component")) {
            if (comp.getAttributeValue("name") != "RunManager") continue
            for (cfg in comp.getChildren("configuration")) {
                if (cfg.getAttributeValue("type") == "com.jetbrains.cidr.embedded.openocd.conf.type") {
                    val ocd = cfg.getChild("openocd")
                    assertEquals("board/stm32g4discovery.cfg", ocd.getAttributeValue("board-config"))
                    assertEquals("3333", ocd.getAttributeValue("gdb-port"))
                    foundWorkspace = true
                }
            }
        }
        assertTrue("应成功解析 workspace.xml 中的 OpenOCD 配置", foundWorkspace)

        val rcRoot = saxBuilder.build(File(rcDir, "OpenOCD_DAPLink.xml")).rootElement
        var foundRc = false
        for (cfg in rcRoot.getChildren("configuration")) {
            if (cfg.getAttributeValue("type") == "com.jetbrains.cidr.embedded.openocd.conf.type") {
                val ocd = cfg.getChild("openocd")
                assertEquals("target/stm32g4x.cfg", ocd.getAttributeValue("board-config"))
                assertEquals("3334", ocd.getAttributeValue("gdb-port"))
                foundRc = true
            }
        }
        assertTrue("应成功解析 runConfigurations 中的 OpenOCD 配置", foundRc)
    }
}
