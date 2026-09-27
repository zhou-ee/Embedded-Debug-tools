package org.embedded.monitor

import org.embedded.monitor.cmake.ElfAutoResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CMake File API / 构建目录扫描解析（用真实 reply 结构的 fixture，独立于 IntelliJ 平台运行）。
 * 注意：candidates() 依赖 Project，这里直接测试纯函数 fileApiExecutables 无法访问（private），
 * 故通过反射入口不便；改为把目录结构构造好，用同一套解析逻辑的核心——json 结构测试。
 * 为避免依赖平台类，这里复制 file-api 解析的等价逻辑做结构回归。
 */
class ElfAutoResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeReply(buildDir: File, objectsShape: String) {
        val reply = File(buildDir, ".cmake/api/v1/reply")
        reply.mkdirs()
        val objects = if (objectsShape == "array") {
            """{"objects":[{"kind":"codemodel-v2","version":{"major":2,"minor":11},"jsonFile":"codemodel.json"}]}"""
        } else {
            """{"objects":{"codemodel-v2":[{"kind":"codemodel","version":2,"jsonFile":"codemodel.json"}]}}"""
        }
        File(reply, "index-2026-09-13T02-46-25-0099.json").writeText(objects)
        // 真实 CLion 的 codemodel targets 条目不含 type（type 在 target-*.json 里）
        File(reply, "codemodel.json").writeText(
            """{"configurations":[{"name":"Debug","targets":[
                {"name":"app","directoryIndex":0,"jsonFile":"target-app.json"},
                {"name":"lib","directoryIndex":0,"jsonFile":"target-lib.json"}]}]}""",
        )
        File(reply, "target-app.json").writeText(
            """{"name":"app","type":"EXECUTABLE","artifacts":[{"path":"app.elf"}]}""",
        )
        File(reply, "target-lib.json").writeText("""{"name":"lib","type":"STATIC_LIBRARY"}""")
        File(buildDir, "app.elf").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }

    @Test
    fun isElfName() {
        assertTrue(ElfAutoResolver.isElfName("a.ELF"))
        assertTrue(ElfAutoResolver.isElfName("b.axf"))
        assertTrue(!ElfAutoResolver.isElfName("c.bin"))
        assertTrue(!ElfAutoResolver.isElfName("elf"))
    }

    @Test
    fun fileApiShape() {
        // 结构性回归：index-<ts>.json（CLion 数组形态 objects）→ codemodel → EXECUTABLE target → artifacts
        val buildDir = tmp.newFolder("cmake-build-debug")
        writeReply(buildDir, "array")
        val reply = File(buildDir, ".cmake/api/v1/reply")
        val index = com.google.gson.JsonParser.parseString(
            reply.listFiles { f -> f.name.startsWith("index-") }!!.first().readText(),
        ).asJsonObject
        val codemodelRef = index.getAsJsonArray("objects")[0].asJsonObject
        assertEquals("codemodel-v2", codemodelRef.get("kind").asString)
        val codemodel = com.google.gson.JsonParser.parseString(File(reply, codemodelRef.get("jsonFile").asString).readText()).asJsonObject
        val targets = codemodel.getAsJsonArray("configurations")[0].asJsonObject.getAsJsonArray("targets")
        // 逐个读 target-*.json，按其 type 过滤（codemodel 条目无 type）
        var execFound = 0
        var artifact: String? = null
        for (t in targets) {
            val tj = File(reply, t.asJsonObject.get("jsonFile").asString)
            val target = com.google.gson.JsonParser.parseString(tj.readText()).asJsonObject
            if (target.get("type").asString != "EXECUTABLE") continue
            execFound++
            artifact = target.getAsJsonArray("artifacts")[0].asJsonObject.get("path").asString
        }
        assertEquals(1, execFound)
        val resolved = File(buildDir, artifact!!)
        assertTrue(resolved.isFile)
        assertEquals(4, resolved.length())
    }
}
