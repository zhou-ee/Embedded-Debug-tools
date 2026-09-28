package org.embedded.monitor.registers

import com.intellij.openapi.project.Project
import org.embedded.monitor.settings.EmbeddedMonitorSettings
import java.io.File

/**
 * 自动定位工程最匹配的 CMSIS-SVD 外设文件。
 * 按序探测：
 * 1. 用户手动设置的 SVD 路径覆盖；
 * 2. 当前工程根目录及子目录下的 *.svd 文件；
 * 3. 常见开发环境的 SVD 库目录（环境变量 SVD_PATH 或 STM32_SVD 库）。
 */
object SvdAutoLocator {

    private val WELL_KNOWN_SVD_DIRS: List<String> by lazy {
        val userHome = System.getProperty("user.home") ?: ""
        listOfNotNull(
            if (userHome.isNotBlank()) "$userHome/.svd" else null,
            if (userHome.isNotBlank()) "$userHome/STM32_SVD/cmsis-svd-stm32" else null,
            "C:/STM32_SVD",
            "D:/STM32_SVD",
        )
    }

    fun locateSvdFile(project: Project, customPath: String? = null): File? {
        // 1. 手动指定优先
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            if (f.isFile) return f
        }

        val settings = runCatching { EmbeddedMonitorSettings.getInstance(project) }.getOrNull()
        if (settings != null && settings.svdPath.isNotBlank()) {
            val f = File(settings.svdPath)
            if (f.isFile) return f
        }

        val baseDir = project.basePath?.let { File(it) }

        // 2. 扫描工程目录（排除 .git / .idea / build）
        if (baseDir != null && baseDir.isDirectory) {
            val foundInProject = findSvdInDirectory(baseDir, maxDepth = 4)
            if (foundInProject != null) return foundInProject
        }

        // 3. 智能推断芯片型号并匹配 SVD 库
        val chipHint = detectChipHint(project)
        for (repoPath in getSvdRepositoryPaths()) {
            val repoDir = File(repoPath)
            if (!repoDir.isDirectory) continue
            val match = findMatchingSvdInRepo(repoDir, chipHint)
            if (match != null) return match
        }

        return null
    }

    private fun findSvdInDirectory(dir: File, maxDepth: Int, currentDepth: Int = 0): File? {
        if (currentDepth > maxDepth) return null
        val files = dir.listFiles() ?: return null

        // 优先检查当前目录下的 .svd
        for (f in files) {
            if (f.isFile && f.extension.equals("svd", ignoreCase = true)) {
                return f
            }
        }

        // 递归扫描子目录（跳过非代码目录）
        for (d in files) {
            if (d.isDirectory && !isIgnoredDir(d.name)) {
                val res = findSvdInDirectory(d, maxDepth, currentDepth + 1)
                if (res != null) return res
            }
        }
        return null
    }

    private fun isIgnoredDir(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".git" || lower == ".idea" || lower == ".codegraph" ||
            lower == "build" || lower.startsWith("cmake-build")
    }

    private fun detectChipHint(project: Project): String? {
        val settings = runCatching { EmbeddedMonitorSettings.getInstance(project) }.getOrNull()
        if (settings != null && settings.chipTarget.isNotBlank()) {
            return settings.chipTarget.trim()
        }

        val baseDir = project.basePath?.let { File(it) }
        if (baseDir != null && baseDir.isDirectory) {
            // 1. 扫描 Drivers/STM32xxx_HAL_Driver
            val driversDir = File(baseDir, "Drivers")
            if (driversDir.isDirectory) {
                val halDir = driversDir.listFiles()?.firstOrNull {
                    it.isDirectory && it.name.startsWith("STM32", ignoreCase = true) && it.name.contains("HAL", ignoreCase = true)
                }
                if (halDir != null) {
                    val m = Regex("""STM32([A-Za-z0-9]+?)xx""", RegexOption.IGNORE_CASE).find(halDir.name)
                    if (m != null) {
                        return m.groupValues[1].uppercase()
                    }
                }
            }

            // 2. 检查 .ioc 文件 (STM32CubeMX)
            val iocFile = baseDir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("ioc", ignoreCase = true) }
            if (iocFile != null) {
                runCatching {
                    val m = Regex("""(?:Mcu\.Name|Mcu\.UserName)\s*=\s*(?:STM32)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE).find(iocFile.readText())
                    if (m != null) return m.groupValues[1].uppercase()
                }
            }
        }

        // 3. 从工程名称提取芯片线索，如 "g4_tool_test" -> "G4", "stm32f407" -> "F407"
        val projName = project.name
        val gMatch = Regex("""(?:stm32)?([fghcluw]\d{1,3}[a-z0-9]*)""", RegexOption.IGNORE_CASE).find(projName)
        if (gMatch != null) {
            return gMatch.groupValues[1].uppercase()
        }

        // 4. 从工程根目录名提取
        if (baseDir != null) {
            val pathMatch = Regex("""(?:stm32)?([fghcluw]\d{1,3}[a-z0-9]*)""", RegexOption.IGNORE_CASE).find(baseDir.name)
            if (pathMatch != null) {
                return pathMatch.groupValues[1].uppercase()
            }
        }

        return null
    }

    private fun getSvdRepositoryPaths(): List<String> {
        val out = mutableListOf<String>()
        System.getenv("SVD_PATH")?.let { out.add(it) }
        System.getenv("CMSIS_SVD_PATH")?.let { out.add(it) }
        System.getenv("CMSIS_SVD_DIR")?.let { out.add(it) }
        out.addAll(WELL_KNOWN_SVD_DIRS)
        return out
    }

    private fun findMatchingSvdInRepo(repoDir: File, chipHint: String?): File? {
        if (!chipHint.isNullOrBlank()) {
            val lowerHint = chipHint.lowercase()
            val upperHint = chipHint.uppercase()

            // 1. 如果子目录直接匹配族系列，如 "stm32g4" / "stm32f4"
            val familySubDir = repoDir.listFiles()?.firstOrNull {
                it.isDirectory && (it.name.equals("stm32$lowerHint", ignoreCase = true) ||
                    (lowerHint.length >= 2 && it.name.equals("stm32${lowerHint.substring(0, 2)}", ignoreCase = true)))
            }
            if (familySubDir != null) {
                val familyFiles = familySubDir.listFiles()?.filter { it.isFile && it.extension.equals("svd", ignoreCase = true) } ?: emptyList()
                val exact = familyFiles.firstOrNull { it.nameWithoutExtension.uppercase().contains(upperHint) }
                if (exact != null) return exact
                if (familyFiles.isNotEmpty()) return familyFiles.first()
            }

            // 2. 全库搜索包含 chipHint 的 SVD 文件
            var matched: File? = null
            repoDir.walkTopDown().maxDepth(5).forEach { f ->
                if (f.isFile && f.extension.equals("svd", ignoreCase = true)) {
                    val nameUpper = f.nameWithoutExtension.uppercase()
                    if (nameUpper.contains(upperHint) || nameUpper.contains("STM32$upperHint")) {
                        if (matched == null) matched = f
                    }
                }
            }
            if (matched != null) return matched
        }

        return repoDir.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.extension.equals("svd", ignoreCase = true) }
    }
}
