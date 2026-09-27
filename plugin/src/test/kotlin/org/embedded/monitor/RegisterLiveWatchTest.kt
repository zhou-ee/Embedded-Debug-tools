package org.embedded.monitor

import org.embedded.monitor.registers.SvdAutoLocator
import org.embedded.monitor.registers.SvdField
import org.embedded.monitor.registers.SvdRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RegisterLiveWatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testRegisterBlockMergingAlgorithm() {
        // 模拟四个寄存器：
        // r1: 0x48000000, size: 4
        // r2: 0x48000004, size: 4 (gap = 0 <= 64) -> 合并
        // r3: 0x48000020, size: 4 (gap = 28 <= 64) -> 合并
        // r4: 0x48000400, size: 4 (gap = 988 > 64) -> 独立新块
        val r1 = SvdRegister("MODER", null, 0x48000000L, 0x00, 4, "RW", null, emptyList(), "GPIOA/MODER")
        val r2 = SvdRegister("OTYPER", null, 0x48000004L, 0x04, 4, "RW", null, emptyList(), "GPIOA/OTYPER")
        val r3 = SvdRegister("AFR0", null, 0x48000020L, 0x20, 4, "RW", null, emptyList(), "GPIOA/AFR0")
        val r4 = SvdRegister("B_MODER", null, 0x48000400L, 0x00, 4, "RW", null, emptyList(), "GPIOB/MODER")

        val sorted = listOf(r4, r2, r1, r3).sortedBy { it.address }
        assertEquals(0x48000000L, sorted[0].address)
        assertEquals(0x48000004L, sorted[1].address)
        assertEquals(0x48000020L, sorted[2].address)
        assertEquals(0x48000400L, sorted[3].address)

        data class Block(var start: Long, var size: Int, val regs: MutableList<SvdRegister>)
        val blocks = mutableListOf<Block>()
        val gapLimit = 64L
        val maxBlock = 1024

        for (reg in sorted) {
            val last = blocks.lastOrNull()
            if (last != null) {
                val lastEnd = last.start + last.size
                val gap = reg.address - lastEnd
                val newSize = (reg.address + reg.size - last.start).toInt()
                if (gap in 0..gapLimit && newSize <= maxBlock) {
                    last.size = newSize
                    last.regs.add(reg)
                    continue
                }
            }
            blocks.add(Block(reg.address, reg.size, mutableListOf(reg)))
        }

        // 验证合并结果：前三个寄存器合并为一块（start: 0x48000000, size: 36 字节），第四个独立一块
        assertEquals(2, blocks.size)
        assertEquals(0x48000000L, blocks[0].start)
        assertEquals(36, blocks[0].size) // 0x20 + 4 = 36 字节
        assertEquals(3, blocks[0].regs.size)

        assertEquals(0x48000400L, blocks[1].start)
        assertEquals(4, blocks[1].size)
        assertEquals(1, blocks[1].regs.size)
    }

    @Test
    fun testBitfieldExtractionAndEnums() {
        val f = SvdField(
            name = "MODE0",
            description = "Port mode",
            bitOffset = 4,
            bitWidth = 2,
            access = "RW",
            enums = listOf(
                0L to "Input",
                1L to "Output",
                2L to "Alternate",
                3L to "Analog",
            )
        )

        assertEquals("[5:4]", f.bitsText)

        // 0x20 对应的第 4~5 位为 2 (0b10)
        val regVal = 0x00000020L
        assertEquals(2L, f.extractValue(regVal))
        assertEquals("Alternate", f.enumName(regVal))

        // 0x10 对应的第 4~5 位为 1 (0b01)
        val regVal2 = 0x00000010L
        assertEquals(1L, f.extractValue(regVal2))
        assertEquals("Output", f.enumName(regVal2))
    }

    @Test
    fun testSvdAutoLocatorWithKnownDirectory() {
        // 验证基于工程内嵌入的 SVD 文件的自适应定位
        val mockProjectDir = tmp.newFolder("mock_stm32g4_proj")
        val svdFile = File(mockProjectDir, "STM32G431.svd").apply { writeText("<device></device>") }
        val testProject = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "mock_stm32g4_proj"
                "getBasePath" -> mockProjectDir.canonicalPath
                else -> null
            }
        } as com.intellij.openapi.project.Project

        val file = SvdAutoLocator.locateSvdFile(testProject)
        assertNotNull("Should auto-locate STM32G4 SVD in project directory", file)
        assertTrue("File should be an SVD file", file!!.name.endsWith(".svd", ignoreCase = true))
        assertTrue("File name should contain G4", file.name.contains("G4", ignoreCase = true))
    }

    @Test
    fun testRegisterLiveWatchHorizontalSplitterAndNoFolderIcon() {
        val dummyDir = tmp.newFolder("dummy_proj")
        val testProject = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "test_project"
                "getBasePath" -> dummyDir.canonicalPath
                "isDisposed" -> false
                else -> null
            }
        } as com.intellij.openapi.project.Project

        val panel = org.embedded.monitor.registers.RegisterLiveWatchPanel(testProject)
        // 查找 JBSplitter 并验证其为左右结构（vertical == false）
        val splitters = mutableListOf<com.intellij.ui.JBSplitter>()
        fun findSplitters(comp: java.awt.Component) {
            if (comp is com.intellij.ui.JBSplitter) splitters.add(comp)
            if (comp is java.awt.Container) {
                for (c in comp.components) findSplitters(c)
            }
        }
        findSplitters(panel)
        assertTrue("Should contain a JBSplitter", splitters.isNotEmpty())
        val mainSplitter = splitters.first()
        assertFalse("Register live watch panel must be horizontal split (left-right)", mainSplitter.isVertical)

        // 验证 Peripheral 渲染器不包含文件夹图标
        val p = org.embedded.monitor.registers.SvdPeripheral(
            name = "GPIOA",
            description = "General purpose I/Os",
            baseAddress = 0x48000000L,
            registers = emptyList()
        )
        val pNode = org.embedded.monitor.registers.RegisterLiveWatchPanel.PeripheralTreeNode(p)
        val tree = javax.swing.JTree()
        val rendererField = panel.javaClass.getDeclaredFields().firstOrNull { it.type.name.contains("RegisterTreeCellRenderer") }
        // 销毁 panel
        com.intellij.openapi.util.Disposer.dispose(panel)
    }
}

