package org.embedded.monitor.cmake

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

/**
 * 目标芯片型号自动探测器。
 *
 * 优先级：
 * 1. STM32CubeMX .ioc 文件（解析 Mcu.CPN / Mcu.UserName / Mcu.Name，映射到 probe-rs 芯片型号）
 * 2. 汇编启动文件 startup_stm32*.s
 * 3. 链接脚本 *.ld（如 STM32G431xx_FLASH.ld）
 */
object ChipAutoResolver {
    private val log = Logger.getInstance(ChipAutoResolver::class.java)

    // STM32 规格映射正则：STM32 + 系列4位 + 引脚1位 + 容量代码1位（如 STM32G431CB）
    private val STM32_TARGET_REGEX = Regex("""^(STM32[A-Z0-9]{5}[468BCDEFGIZ])""", RegexOption.IGNORE_CASE)
    private val STM32_FAMILY_REGEX = Regex("""startup_(stm32[a-z0-9]+)xx""", RegexOption.IGNORE_CASE)
    private val LD_FAMILY_REGEX = Regex("""(stm32[a-z0-9]+)xx""", RegexOption.IGNORE_CASE)

    /** 自动识别当前工程的目标芯片型号（大写，可直接用于 probe-rs target）。 */
    fun detectChip(project: Project): String {
        val roots = contentRoots(project)
        for (root in roots) {
            val fromIoc = detectFromIoc(root)
            if (fromIoc.isNotBlank()) return fromIoc

            val fromStartup = detectFromStartup(root)
            if (fromStartup.isNotBlank()) return fromStartup

            val fromLd = detectFromLinkerScript(root)
            if (fromLd.isNotBlank()) return fromLd
        }
        return ""
    }

    private fun detectFromIoc(root: File): String {
        val iocFiles = root.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.equals("ioc", ignoreCase = true) }
        for (f in iocFiles) {
            try {
                var cpn = ""
                var name = ""
                var userName = ""
                f.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("Mcu.CPN=")) {
                            cpn = trimmed.substringAfter("=").trim()
                        } else if (trimmed.startsWith("Mcu.UserName=")) {
                            userName = trimmed.substringAfter("=").trim()
                        } else if (trimmed.startsWith("Mcu.Name=")) {
                            name = trimmed.substringAfter("=").trim()
                        }
                    }
                }
                val raw = cpn.ifBlank { userName }.ifBlank { name }
                if (raw.isNotBlank()) {
                    val m = STM32_TARGET_REGEX.find(raw)
                    if (m != null) {
                        return m.groupValues[1].uppercase()
                    }
                    val clean = raw.replace(Regex("""[\(\)\-]"""), "")
                    if (clean.length >= 11 && clean.startsWith("STM32", ignoreCase = true)) {
                        return clean.take(11).uppercase()
                    }
                    return raw.uppercase()
                }
            } catch (e: Exception) {
                log.warn("解析 .ioc 失败: ${f.path}", e)
            }
        }
        return ""
    }

    private fun detectFromStartup(root: File): String {
        val asmFiles = root.walkTopDown().maxDepth(4).filter {
            it.isFile && (it.extension.equals("s", ignoreCase = true) || it.extension.equals("S", ignoreCase = true))
        }
        for (f in asmFiles) {
            val m = STM32_FAMILY_REGEX.find(f.name)
            if (m != null) {
                return m.groupValues[1].uppercase()
            }
        }
        return ""
    }

    private fun detectFromLinkerScript(root: File): String {
        val ldFiles = root.walkTopDown().maxDepth(4).filter {
            it.isFile && it.extension.equals("ld", ignoreCase = true)
        }
        for (f in ldFiles) {
            val m = LD_FAMILY_REGEX.find(f.name)
            if (m != null) {
                return m.groupValues[1].uppercase()
            }
        }
        return ""
    }

    private fun contentRoots(project: Project): List<File> {
        val roots = ArrayList<File>()
        try {
            ProjectRootManager.getInstance(project).contentRoots.forEach { roots.add(File(it.path)) }
        } catch (_: Exception) {}
        project.basePath?.let { roots.add(File(it)) }
        return roots.distinctBy { it.canonicalPath }
    }
}
