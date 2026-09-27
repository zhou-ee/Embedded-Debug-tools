package org.embedded.monitor

import org.embedded.monitor.watch.WatchExpressionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchExpressionParserTest {

    @Test
    fun typeAtAddr() {
        val p = WatchExpressionParser.parse("float @ 0x20000000")!!
        assertEquals(WatchExpressionParser.Kind.TYPE_AT_ADDR, p.kind)
        assertEquals(0x20000000L, p.address)
        assertEquals(4, p.size)
        assertEquals("F32", p.format)
    }

    @Test
    fun typeAtAddrDecimal() {
        val p = WatchExpressionParser.parse("u8 @ 536870912")!!
        assertEquals(0x20000000L, p.address)
        assertEquals("U8", p.format)
        assertEquals(1, p.size)
    }

    @Test
    fun addrType() {
        val p = WatchExpressionParser.parse("0x20000004:f32")!!
        assertEquals(WatchExpressionParser.Kind.ADDR_TYPE, p.kind)
        assertEquals("F32", p.format)
    }

    @Test
    fun bareAddrNeedsLookup() {
        val p = WatchExpressionParser.parse("0x20000010")!!
        assertEquals(WatchExpressionParser.Kind.BARE_ADDR, p.kind)
        assertTrue(p.needsTypeLookup)
        assertEquals(4, p.size)
    }

    @Test
    fun memberChain() {
        val p = WatchExpressionParser.parse("g_map.root.next")!!
        assertEquals(WatchExpressionParser.Kind.MEMBER_CHAIN, p.kind)
    }

    @Test
    fun plainSymbol() {
        val p = WatchExpressionParser.parse("g_counter")!!
        assertEquals(WatchExpressionParser.Kind.MEMBER_CHAIN, p.kind)
    }

    @Test
    fun gccStaticVariable() {
        val p = WatchExpressionParser.parse("cnt.1")!!
        assertEquals(WatchExpressionParser.Kind.MEMBER_CHAIN, p.kind)
        val p2 = WatchExpressionParser.parse("motor.speed.2")!!
        assertEquals(WatchExpressionParser.Kind.MEMBER_CHAIN, p2.kind)
    }

    @Test
    fun rejectGarbage() {
        assertNull(WatchExpressionParser.parse("a + b"))
        assertNull(WatchExpressionParser.parse("foo()"))
        assertNull(WatchExpressionParser.parse("foo.bar->baz"))
        assertNull(WatchExpressionParser.parse(""))
        assertNull(WatchExpressionParser.parse("float @ xyz"))
    }

    @Test
    fun unknownAliasThrows() {
        var thrown: IllegalArgumentException? = null
        try {
            WatchExpressionParser.parse("my_struct @ 0x20000000")
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue("应抛出 IllegalArgumentException", thrown != null)
    }

    @Test
    fun unsigned64BitAddress() {
        val p = WatchExpressionParser.parse("0xFFFFFFFFFFFFFFFF")!!
        assertEquals(-1L, p.address)
        val p2 = WatchExpressionParser.parse("u32 @ 0x80000000")!!
        assertEquals(0x80000000L, p2.address)
    }
}
