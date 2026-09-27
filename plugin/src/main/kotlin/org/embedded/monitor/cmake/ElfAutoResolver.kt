package org.embedded.monitor.cmake

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * ELF 自动探测：符号表路径自动读取 CLion 配置的构建路径。
 *
 * 顺序：
 *  1. 设置中的手动覆盖（由 AgentService 处理）
 *  2. CMake File API reply（构建目录 `.cmake/api/v1/reply/`，CLion 配置生成时自动产出）：
 *     codemodel → EXECUTABLE 目标 → artifacts
 *  3. 扫描候选构建目录中最新的 `.elf` / `.axf`：
 *     - `cmake-build*`（CLion 默认命名，含 profile 名，如 cmake-build-debug-stm32）
 *     - `build` / `build/<preset>`（CMakePresets binaryDir 约定）
 *     - `.idea/cmake.xml` 里 profile 的 generationDir（若存在）
 */
object ElfAutoResolver {
    private val log = Logger.getInstance(ElfAutoResolver::class.java)

    data class Candidate(val file: File, val source: String, val mtime: Long)

    /** 返回探测到的候选 ELF（按优先级排序，去重）。 */
    fun candidates(project: Project): List<Candidate> {
        val roots = contentRoots(project)
        val result = LinkedHashMap<String, Candidate>()

        for (root in roots) {
            // 1. CMake File API
            for (dir in candidateBuildDirs(root)) {
                for (f in fileApiExecutables(dir)) {
                    put(result, f, "CMake File API: ${dir.name}")
                }
            }
            // 2. 直接扫描
            for (dir in candidateBuildDirs(root)) {
                for (f in scanElf(dir, maxDepth = 2)) {
                    put(result, f, "构建目录扫描: ${dir.name}")
                }
            }
        }
        // 按 mtime 新者优先（同源内）；File API 候选保留在前的相对顺序
        return result.values.sortedByDescending { it.mtime }
    }

    /** 单个自动探测结果（最优先候选）。 */
    fun autoDetect(project: Project): File? = candidates(project).firstOrNull()?.file

    private fun put(map: LinkedHashMap<String, Candidate>, f: File, source: String) {
        val key = f.canonicalPath.lowercase()
        if (!map.containsKey(key)) {
            val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
            map[key] = Candidate(f, source, mtime)
        }
    }

    private fun contentRoots(project: Project): List<File> {
        val roots = ArrayList<File>()
        try {
            ProjectRootManager.getInstance(project).contentRoots.forEach { roots.add(File(it.path)) }
        } catch (e: Exception) {
            log.warn("contentRoots 获取失败", e)
        }
        return roots.distinctBy { it.canonicalPath }
    }

    /** 候选构建目录（去重，包含 preset 的 build/<name> 一级子目录）。 */
    private fun candidateBuildDirs(root: File): List<File> {
        val dirs = ArrayList<File>()
        val children = root.listFiles() ?: return dirs
        for (c in children) {
            if (!c.isDirectory) continue
            val n = c.name
            if (n.startsWith("cmake-build")) {
                dirs.add(c)
            } else if (n == "build" || n == "out") {
                dirs.add(c)
                // presets binaryDir：${sourceDir}/build/<presetName>
                c.listFiles()?.filterTo(dirs) { it.isDirectory && File(it, "CMakeCache.txt").isFile }
            }
        }
        // .idea/cmake.xml 的 generationDir
        val cmakeXml = File(root, ".idea/cmake.xml")
        if (cmakeXml.isFile) {
            runCatching {
                val text = cmakeXml.readText()
                Regex("""generationDir="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                    val dir = File(m.groupValues[1])
                    if (dir.isDirectory) dirs.add(dir)
                    else {
                        val rel = File(root, m.groupValues[1])
                        if (rel.isDirectory) dirs.add(rel)
                    }
                }
            }
        }
        return dirs.distinctBy { it.canonicalPath }
    }

    /** CMake File API：codemodel 里 type=EXECUTABLE 的目标 artifacts。 */
    private fun fileApiExecutables(buildDir: File): List<File> {
        val reply = File(buildDir, ".cmake/api/v1/reply")
        if (!reply.isDirectory) return emptyList()
        // reply 目录下是 index-<时间戳>.json（每次 configure 生成一个），取最新
        val indexFile = reply.listFiles { f -> f.name.startsWith("index-") && f.name.endsWith(".json") }
            ?.maxByOrNull { runCatching { it.lastModified() }.getOrDefault(0L) }
            ?: return emptyList()
        return runCatching {
            val index = JsonParser.parseString(indexFile.readText()).asJsonObject
            // objects 兼容两种形态：文档的 {kind: [..]} 对象映射 与 CLion 实际生成的 [{kind, jsonFile}] 数组
            val codemodelRefs: List<JsonObject> = when (val o = index.get("objects")) {
                is JsonObject ->
                    o.getAsJsonArray("codemodel-v2")?.map { it.asJsonObject } ?: emptyList()
                is JsonArray ->
                    o.map { it.asJsonObject }
                        .filter { it.get("kind")?.asString in setOf("codemodel-v2", "codemodel") }
                else -> emptyList()
            }
            val out = ArrayList<File>()
            for (ref in codemodelRefs) {
                val jsonFile = ref.get("jsonFile")?.asString ?: continue
                val codemodel = JsonParser.parseString(File(reply, jsonFile).readText()).asJsonObject
                for (config in codemodel.getAsJsonArray("configurations") ?: continue) {
                    val targets = config.asJsonObject.getAsJsonArray("targets") ?: continue
                    for (t in targets) {
                        val tjson = t.asJsonObject.get("jsonFile")?.asString ?: continue
                        val tf = File(reply, tjson)
                        if (!tf.isFile) continue
                        val target = JsonParser.parseString(tf.readText()).asJsonObject
                        if (target.get("type")?.asString != "EXECUTABLE") continue
                        val artifacts = target.getAsJsonArray("artifacts") ?: continue
                        for (a in artifacts) {
                            val p = a.asJsonObject.get("path")?.asString ?: continue
                            val f = File(buildDir, p).canonicalFile
                            if (f.isFile) out.add(f)
                        }
                    }
                }
            }
            out
        }.getOrElse {
            log.warn("CMake File API 解析失败 (${buildDir}): ${it.message}")
            emptyList()
        }
    }

    /** 目录内扫描 elf/axf/out（限深），按修改时间新→旧。 */
    private fun scanElf(dir: File, maxDepth: Int): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val children = d.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) {
                    if (depth < maxDepth) walk(c, depth + 1)
                } else if (isElfName(c.name)) {
                    out.add(c)
                }
            }
        }
        walk(dir, 0)
        return out.sortedByDescending { runCatching { it.lastModified() }.getOrDefault(0L) }
    }

    fun isElfName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".elf") || n.endsWith(".axf") || n.endsWith(".out")
    }
}
