package org.embedded.monitor

import org.embedded.monitor.cmake.ChipAutoResolver
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChipAutoResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testDetectFromIoc() {
        val root = tmp.newFolder("project")
        val ioc = File(root, "test.ioc")
        ioc.writeText("""
            #MicroXplorer Configuration settings - do not modify
            Mcu.CPN=STM32G431CBU6
            Mcu.Family=STM32G4
            Mcu.Name=STM32G431C(6-8-B)Ux
            Mcu.UserName=STM32G431CBUx
        """.trimIndent())

        // Test detect via root directly
        val method = ChipAutoResolver::class.java.getDeclaredMethod("detectFromIoc", File::class.java)
        method.isAccessible = true
        val result = method.invoke(ChipAutoResolver, root) as String
        assertEquals("STM32G431CB", result)
    }

    @Test
    fun testDetectFromStartup() {
        val root = tmp.newFolder("project_asm")
        File(root, "startup_stm32f407xx.s").writeText("/* asm */")

        val method = ChipAutoResolver::class.java.getDeclaredMethod("detectFromStartup", File::class.java)
        method.isAccessible = true
        val result = method.invoke(ChipAutoResolver, root) as String
        assertEquals("STM32F407", result)
    }

    @Test
    fun testDetectFromLinkerScript() {
        val root = tmp.newFolder("project_ld")
        File(root, "STM32G431xx_FLASH.ld").writeText("/* ld */")

        val method = ChipAutoResolver::class.java.getDeclaredMethod("detectFromLinkerScript", File::class.java)
        method.isAccessible = true
        val result = method.invoke(ChipAutoResolver, root) as String
        assertEquals("STM32G431", result)
    }
}
