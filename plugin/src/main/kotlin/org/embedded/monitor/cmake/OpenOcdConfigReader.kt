package org.embedded.monitor.cmake

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import org.jdom.Element
import org.jdom.input.SAXBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 读取 CLion 的 OpenOCD 运行配置（.idea/workspace.xml 及 .idea/runConfigurations 下的 XML 文件，只读，用于自动填充）：
 * 类型 com.jetbrains.cidr.embedded.openocd.conf.type 的 configuration 提供
 * board-config / gdb-port 等（OpenOCD Tcl RPC 端口固定 6666，CLion 不单独配置）。
 * 同时提供 OpenOCD Tcl 端口可达性检测与 CLion 调试会话状态判断。
 */
object OpenOcdConfigReader {

    data class OpenOcdRunConfig(
        val name: String,
        val boardConfig: String?,
        val gdbPort: Int?,
        val telnetPort: Int?,
    )

    fun readAll(project: Project): List<OpenOcdRunConfig> {
        val configs = ArrayList<OpenOcdRunConfig>()
        val saxBuilder = SAXBuilder()

        // 1. 读取 .idea/workspace.xml
        workspaceXml(project)?.let { ws ->
            runCatching {
                val root = saxBuilder.build(ws).rootElement
                for (component in root.getChildren("component")) {
                    if (component.getAttributeValue("name") != "RunManager") continue
                    for (cfg in component.getChildren("configuration")) {
                        parseConfigurationElement(cfg)?.let { configs.add(it) }
                    }
                }
            }
        }

        // 2. 读取 .idea/runConfigurations/*.xml（CLion 项目级保存的运行配置）
        runConfigurationsDir(project)?.listFiles { f -> f.extension.equals("xml", ignoreCase = true) }?.forEach { rcXml ->
            runCatching {
                val root = saxBuilder.build(rcXml).rootElement
                if (root.name == "configuration") {
                    parseConfigurationElement(root)?.let { configs.add(it) }
                } else {
                    for (cfg in root.getChildren("configuration")) {
                        parseConfigurationElement(cfg)?.let { configs.add(it) }
                    }
                }
            }
        }

        return configs
    }

    private fun parseConfigurationElement(cfg: Element): OpenOcdRunConfig? {
        val type = cfg.getAttributeValue("type") ?: ""
        if (type != OPENOCD_TYPE && !type.contains("openocd", ignoreCase = true)) return null
        val openocd = cfg.getChild("openocd") ?: return null
        return OpenOcdRunConfig(
            name = cfg.getAttributeValue("name") ?: "",
            boardConfig = openocd.getAttributeValue("board-config"),
            gdbPort = openocd.getAttributeValue("gdb-port")?.toIntOrNull(),
            telnetPort = openocd.getAttributeValue("telnet-port")?.toIntOrNull(),
        )
    }

    /** 快速检测 OpenOCD Tcl RPC 端口（默认 6666）是否正在监听并接受连接。支持 127.0.0.1、::1 与 localhost 回环检测。 */
    fun isOpenOcdPortListening(port: Int = 6666, timeoutMs: Int = 300): Boolean {
        if (port <= 0 || port > 65535) return false
        return probeAddress("127.0.0.1", port, timeoutMs) ||
            probeAddress("::1", port, timeoutMs) ||
            probeAddress("localhost", port, timeoutMs)
    }

    private fun probeAddress(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.reuseAddress = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /** 判断 CLion 当前是否有处于运行/挂起态的有效调试会话。 */
    fun isDebuggingActive(project: Project): Boolean {
        return runCatching {
            val mgr = XDebuggerManager.getInstance(project) ?: return false
            mgr.debugSessions.any { !it.isStopped } || (mgr.currentSession?.let { !it.isStopped } ?: false)
        }.getOrDefault(false)
    }

    /** 判断 CLion 当前是否有任何处于暂停（命中断点/挂起）的调试会话。 */
    fun isAnySessionPaused(project: Project): Boolean {
        return runCatching {
            val mgr = XDebuggerManager.getInstance(project) ?: return false
            mgr.debugSessions.any { !it.isStopped && (it.isPaused || it.isSuspended) } ||
                (mgr.currentSession?.let { !it.isStopped && (it.isPaused || it.isSuspended) } ?: false)
        }.getOrDefault(false)
    }

    /** 综合判断 OpenOCD Tcl RPC 或 GDB 调试端口是否至少有一个处于监听服务态。 */
    fun isOpenOcdOrGdbActive(project: Project, tclPort: Int = 6666): Boolean {
        if (isOpenOcdPortListening(tclPort, 300)) return true
        val gdbPort = detectGdbPort(project)
        return isOpenOcdPortListening(gdbPort, 300)
    }

    fun hasOpenOcdConfig(project: Project): Boolean = readAll(project).isNotEmpty()

    fun detectBoardConfig(project: Project): String? =
        readAll(project).firstOrNull { !it.boardConfig.isNullOrBlank() }?.boardConfig

    fun detectGdbPort(project: Project): Int =
        readAll(project).firstOrNull { it.gdbPort != null && it.gdbPort > 0 }?.gdbPort ?: 3333

    /** 检查当前工程配置的板级 cfg 文件是否已显式配置了 tcl_port 或 tcl port。 */
    fun isTclPortConfiguredInBoardConfig(project: Project): Boolean? {
        val cfgPath = detectBoardConfig(project) ?: return null
        val file = File(cfgPath)
        if (!file.isFile) return null
        return runCatching {
            val content = file.readText()
            content.contains("tcl_port", ignoreCase = true) || content.contains("tcl port", ignoreCase = true)
        }.getOrNull()
    }

    private const val OPENOCD_TYPE = "com.jetbrains.cidr.embedded.openocd.conf.type"

    private fun workspaceXml(project: Project): File? {
        val root = project.basePath ?: return null
        val f = File(root, ".idea/workspace.xml")
        return if (f.isFile) f else null
    }

    private fun runConfigurationsDir(project: Project): File? {
        val root = project.basePath ?: return null
        val dir = File(root, ".idea/runConfigurations")
        return if (dir.isDirectory) dir else null
    }
}

