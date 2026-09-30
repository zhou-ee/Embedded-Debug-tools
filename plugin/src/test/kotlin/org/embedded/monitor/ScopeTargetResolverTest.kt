package org.embedded.monitor

import org.embedded.monitor.agent.ScopeTargetResolver
import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.core.ScopeVariable
import org.embedded.monitor.core.ValueFormat
import org.embedded.monitor.scope.ScopeChannelTableModel
import org.junit.Assert.*
import org.junit.Test

class ScopeTargetResolverTest {
    @Test
    fun reloadUsesCurrentGlobalAndAbsoluteMemberAddresses() {
        val old = SymbolNode("state", address = 0x20000100, size = 16,
            members = listOf(SymbolNode("vx", address = 0x20000104, size = 4, encoding = "float")))
        val current = old.copy(address = 0x20000300,
            members = listOf(old.members[0].copy(address = 0x20000304)))
        assertEquals(0x20000300L, ScopeTargetResolver.resolve("state", listOf(current)) { error("not a pointer") }?.address)
        assertEquals(0x20000304L, ScopeTargetResolver.resolve("state.vx", listOf(current)) { error("not a pointer") }?.address)
        assertNull(ScopeTargetResolver.resolve("removed", listOf(current)) { error("not a pointer") })
    }

    @Test
    fun pointerAndArrayMembersUseFreshPointeeBase() {
        val vx = SymbolNode("vx", address = 0x4c, size = 4, encoding = "float")
        val element = SymbolNode("[0]", address = 0x40, size = 16, members = listOf(vx))
        val data = SymbolNode("data", address = 0x40, size = 32, members = listOf(element))
        val ctx = SymbolNode("_ctx", address = 0x20, size = 64, members = listOf(data))
        val root = SymbolNode("g_chassis_ptr", address = 0x20000100, size = 4,
            isPointer = true, members = listOf(ctx))
        val expression = "g_chassis_ptr->_ctx.data[0].vx"
        var pointer = 0x20000400L
        val reads = mutableListOf<Long>()
        fun resolve() = ScopeTargetResolver.resolve(expression, listOf(root)) { address -> reads.add(address); pointer }
        assertEquals(0x2000044cL, resolve()?.address)
        pointer = 0x20000600L
        assertEquals(0x2000064cL, resolve()?.address)
        assertEquals(listOf(0x20000100L, 0x20000100L), reads)
    }

    @Test
    fun nestedPointerReplacesBaseRatherThanAddingItTwice() {
        val value = SymbolNode("value", address = 12, size = 4)
        val child = SymbolNode("child", address = 8, size = 4, isPointer = true, members = listOf(value))
        val root = SymbolNode("root", address = 0x20000100, size = 4, isPointer = true, members = listOf(child))
        val pointers = mapOf(0x20000100L to 0x20001000L, 0x20001008L to 0x20002000L)
        assertEquals(0x2000200cL, ScopeTargetResolver.resolve("root.child.value", listOf(root)) { pointers[it] }?.address)
        assertNull(ScopeTargetResolver.resolve("root.child.value", listOf(root)) { if (it == root.address) 0x20001000 else 0 })
    }

    @Test
    fun scalarDereferenceUsesPointeeSizeAndEncoding() {
        val pointer = SymbolNode("ptr", address = 0x20000100, size = 4, isPointer = true,
            pointeeSize = 8, pointeeEncoding = "float")
        val target = ScopeTargetResolver.resolve("*ptr", listOf(pointer)) { 0x20001000L }
        assertEquals(0x20001000L, target?.address)
        assertEquals(8, target?.size)
        assertEquals("float", target?.encoding)
        assertNull(ScopeTargetResolver.resolve("*ptr", listOf(pointer)) { 0 })
    }

    @Test
    fun explicitMemoryAddressesRemainFixedBindings() {
        assertNull(ScopeTargetResolver.expression("0x20000000"))
        assertNull(ScopeTargetResolver.expression("float @ 0x20000000"))
        assertNull(ScopeTargetResolver.expression("0x20000000:f32"))
        assertEquals("motor.vx", ScopeTargetResolver.expression("motor.vx"))
        assertEquals("ptr->vx", ScopeTargetResolver.expression("ptr->vx"))
        assertNull(ScopeTargetResolver.binding("custom_name", 0x20000100, fixedAddress = true))
        assertNull(ScopeTargetResolver.binding("GPIOA.ODR", 0x48000014))
        assertNull(ScopeTargetResolver.binding("SysTick.VAL", 0xE000E018))
        assertEquals("motor.vx", ScopeTargetResolver.binding("motor.vx", 0x20000100))
    }

    @Test
    fun unresolvedChannelDoesNotShowStaleAddressOrValue() {
        val variable = ScopeVariable("removed", 0x20000100, 4, ValueFormat.F32, expression = "removed", resolved = false)
        val model = ScopeChannelTableModel()
        model.update(listOf(variable), mapOf(variable.address to 99f))
        model.setCursorValues(mapOf(variable.name to 99f))
        assertEquals("待定位", model.getValueAt(0, 3))
        assertEquals("-", model.getValueAt(0, 5))
        assertEquals("-", model.getValueAt(0, 6))
    }
}
