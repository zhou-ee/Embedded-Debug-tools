package org.embedded.monitor

import org.embedded.monitor.registers.SvdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class SvdParserTest {

    private val sampleSvdXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <device schemaVersion="1.1" xmlns:xs="http://www.w3.org/2001/XMLSchema-instance">
          <name>STM32_TEST</name>
          <version>1.0</version>
          <description>Test Device</description>
          <peripherals>
            <peripheral>
              <name>GPIOA</name>
              <description>General-purpose I/Os</description>
              <baseAddress>0x48000000</baseAddress>
              <registers>
                <register>
                  <name>MODER</name>
                  <description>GPIO port mode register</description>
                  <addressOffset>0x00</addressOffset>
                  <size>32</size>
                  <access>read-write</access>
                  <resetValue>0xABFFFFFF</resetValue>
                  <fields>
                    <field>
                      <name>MODER0</name>
                      <description>Port x configuration bits (y = 0)</description>
                      <bitOffset>0</bitOffset>
                      <bitWidth>2</bitWidth>
                      <enumeratedValues>
                        <enumeratedValue>
                          <name>Input</name>
                          <value>0</value>
                        </enumeratedValue>
                        <enumeratedValue>
                          <name>Output</name>
                          <value>1</value>
                        </enumeratedValue>
                        <enumeratedValue>
                          <name>Alternate</name>
                          <value>2</value>
                        </enumeratedValue>
                        <enumeratedValue>
                          <name>Analog</name>
                          <value>3</value>
                        </enumeratedValue>
                      </enumeratedValues>
                    </field>
                    <field>
                      <name>MODER1</name>
                      <bitRange>[3:2]</bitRange>
                    </field>
                  </fields>
                </register>
                <register>
                  <name>AFR[%s]</name>
                  <dim>2</dim>
                  <dimIncrement>4</dimIncrement>
                  <addressOffset>0x20</addressOffset>
                  <size>32</size>
                </register>
              </registers>
            </peripheral>
            <peripheral derivedFrom="GPIOA">
              <name>GPIOB</name>
              <baseAddress>0x48000400</baseAddress>
            </peripheral>
          </peripherals>
        </device>
    """.trimIndent()

    @Test
    fun testParseSampleSvd() {
        val dev = SvdParser.parse(ByteArrayInputStream(sampleSvdXml.toByteArray(Charsets.UTF_8)))
        assertEquals("STM32_TEST", dev.name)
        assertEquals(2, dev.peripherals.size)

        // GPIOA
        val gpioa = dev.peripherals.find { it.name == "GPIOA" }
        assertNotNull(gpioa)
        assertEquals(0x48000000L, gpioa!!.baseAddress)
        // MODER + AFR0 + AFR1
        assertEquals(3, gpioa.registers.size)

        val moder = gpioa.registers.find { it.name == "MODER" }
        assertNotNull(moder)
        assertEquals(0x48000000L, moder!!.address)
        assertEquals(0xABFFFFFFL, moder.resetValue)
        assertEquals(4, moder.size)
        assertEquals(2, moder.fields.size)

        val f0 = moder.fields[0]
        assertEquals("MODER0", f0.name)
        assertEquals(0, f0.bitOffset)
        assertEquals(2, f0.bitWidth)
        assertEquals("[1:0]", f0.bitsText)
        assertEquals(4, f0.enums.size)
        // Check value extraction and enum mapping
        val regVal = 0b0010L // MODER0 = 2 (Alternate)
        assertEquals(2L, f0.extractValue(regVal))
        assertEquals("Alternate", f0.enumName(regVal))

        val f1 = moder.fields[1]
        assertEquals("MODER1", f1.name)
        assertEquals(2, f1.bitOffset)
        assertEquals(2, f1.bitWidth)
        assertEquals("[3:2]", f1.bitsText)

        // dim array expansion
        val afr0 = gpioa.registers.find { it.name == "AFR0" }
        assertNotNull(afr0)
        assertEquals(0x48000020L, afr0!!.address)

        val afr1 = gpioa.registers.find { it.name == "AFR1" }
        assertNotNull(afr1)
        assertEquals(0x48000024L, afr1!!.address)

        // derivedFrom inheritance in GPIOB
        val gpiob = dev.peripherals.find { it.name == "GPIOB" }
        assertNotNull(gpiob)
        assertEquals(0x48000400L, gpiob!!.baseAddress)
        assertEquals(3, gpiob.registers.size)

        val bModer = gpiob.registers.find { it.name == "MODER" }
        assertNotNull(bModer)
        assertEquals(0x48000400L, bModer!!.address)
        assertEquals("GPIOB/MODER", bModer.path)

        val bAfr1 = gpiob.registers.find { it.name == "AFR1" }
        assertNotNull(bAfr1)
        assertEquals(0x48000424L, bAfr1!!.address)
        assertEquals("GPIOB/AFR1", bAfr1.path)
    }

    @Test
    fun testParseRealStm32G4SvdFile() {
        val path = System.getenv("TEST_STM32G4_SVD")
            ?: "E:/Software/Develop/Embeded/STM32_SVD/cmsis-svd-stm32/stm32g4/STM32G431.svd"
        val file = File(path)
        if (!file.exists()) return

        val startTime = System.currentTimeMillis()
        val dev = SvdParser.parse(file)
        val elapsed = System.currentTimeMillis() - startTime
        println("Parsed ${file.name} in ${elapsed}ms, peripherals=${dev.peripherals.size}")

        assertTrue(dev.peripherals.isNotEmpty())
        val gpioa = dev.peripherals.find { it.name == "GPIOA" }
        assertNotNull(gpioa)
        assertTrue(gpioa!!.registers.isNotEmpty())
    }
}
