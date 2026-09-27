package org.embedded.monitor

import org.embedded.monitor.core.SampleDecoder
import org.embedded.monitor.core.ValueFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValueFormatTest {
    @Test
    fun decodeLittleEndian() {
        val f32 = byteArrayOf(0x00, 0x00, 0x20, 0x41) // 10.0f
        assertEquals(10.0f, ValueFormat.F32.decode(f32), 1e-6f)

        val u32 = byteArrayOf(0x0A.toByte(), 0x00, 0x00, 0x00)
        assertEquals(10.0f, ValueFormat.U32.decode(u32), 0f)

        val i8 = byteArrayOf(0xFF.toByte())
        assertEquals(-1.0f, ValueFormat.I8.decode(i8), 0f)
        assertEquals(255.0f, ValueFormat.U8.decode(i8), 0f)

        val f64 = byteArrayOf(0, 0, 0, 0, 0, 0, 0x24, 0x40) // 10.0
        assertEquals(10.0f, ValueFormat.F64.decode(f64), 1e-6f)
    }

    @Test
    fun fromEncodingMaps() {
        assertEquals(ValueFormat.F32, ValueFormat.fromEncoding("float", 4))
        assertEquals(ValueFormat.F64, ValueFormat.fromEncoding("float", 8))
        assertEquals(ValueFormat.I32, ValueFormat.fromEncoding("signed", 4))
        assertEquals(ValueFormat.I8, ValueFormat.fromEncoding("signed", 1))
        assertEquals(ValueFormat.U8, ValueFormat.fromEncoding("bool", 1))
        assertEquals(ValueFormat.U32, ValueFormat.fromEncoding("pointer", 4))
        assertEquals(ValueFormat.U16, ValueFormat.fromEncoding("composite", 2))
        assertEquals(ValueFormat.U32, ValueFormat.fromEncoding(null, 4))
    }

    @Test
    fun typeAliases() {
        assertEquals(ValueFormat.F32 to 4, ValueFormat.fromTypeAlias("float"))
        assertEquals(ValueFormat.U32 to 4, ValueFormat.fromTypeAlias("uint32_t"))
        assertEquals(ValueFormat.I8 to 1, ValueFormat.fromTypeAlias("int8_t"))
        assertEquals(null, ValueFormat.fromTypeAlias("my_struct"))
    }

    @Test
    fun byteSize() {
        assertEquals(1, ValueFormat.U8.byteSize)
        assertEquals(4, ValueFormat.F32.byteSize)
        assertEquals(8, ValueFormat.U64.byteSize)
    }

    @Test
    fun sampleDecoderConsistent() {
        // decoder 直接使用应与 enum 方法一致
        val b = byteArrayOf(1, 2, 3, 4)
        assertEquals(ValueFormat.U32.decode(b), SampleDecoder.decode(ValueFormat.U32, b), 0f)
    }

    @Test
    fun watchValueFormatterPointer() {
        val item = org.embedded.monitor.watch.WatchItem(
            id = "w1",
            expr = "ptr",
            address = 0x20000000L,
            size = 4,
            encoding = "pointer",
            typeName = "void*",
        )
        // 0x20000000 in little endian
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20)
        val formatted = org.embedded.monitor.watch.WatchValueFormatter.format(item, bytes)
        assertEquals("0x20000000", formatted)

        // 64-bit pointer
        val item64 = org.embedded.monitor.watch.WatchItem(
            id = "w2",
            expr = "ptr64",
            address = 0x20000000L,
            size = 8,
            encoding = "pointer",
            typeName = "void*",
        )
        val bytes64 = byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20)
        val formatted64 = org.embedded.monitor.watch.WatchValueFormatter.format(item64, bytes64)
        assertEquals("0x2000000000000010", formatted64)
    }

    @Test
    fun decoderUnderflowSafety() {
        // Truncated bytes must not throw BufferUnderflowException and must pad with zeros
        val truncatedI32 = byteArrayOf(0x2A) // 42 as single byte
        val decoded = ValueFormat.I32.decode(truncatedI32)
        assertEquals(42.0f, decoded, 0f)

        val item = org.embedded.monitor.watch.WatchItem(
            id = "w_short",
            expr = "short_ptr",
            address = 0x20000000L,
            size = 4,
            encoding = "pointer",
            typeName = "void*",
        )
        val formatted = org.embedded.monitor.watch.WatchValueFormatter.format(item, byteArrayOf(0x12, 0x34))
        assertEquals("0x00003412", formatted)
    }
}
