package org.embedded.monitor

import org.embedded.monitor.agent.EvalHybridPromoter
import org.embedded.monitor.agent.SymbolNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 求值型监视混合升级（EvalHybridPromoter）：GDB 求值指针/引用结果 → 固定地址实时监视。
 *
 * 布局数据取自真机固件（STM32G431 g4_tool_test，2026-09-29 seq 根因证明）：
 * - `pyro::wl_chassis_t::instance()` 返回指针，值 0x200003E4（CRTP 单例 s_instance）
 * - `g_chassis_ptr` 为 `wl_chassis_t *` 全局（成员 pointee 相对偏移）
 * - `_ctx` 相对偏移 56（size 104），`_ctx.seq` 相对偏移 160 → 绝对 0x200003E4+160 = 0x20000484
 * - 固件仅自增 g_chassis_ctx 侧的 seq，`_ctx.seq` 恒 0（同名子树防混淆 V1.2.16）
 */
class EvalHybridPromoterTest {

    /** 复刻真机 ELF 顶层变量：指针全局（成员 pointee 相对）。 */
    private fun pointerVar() = SymbolNode(
        name = "g_chassis_ptr",
        typeName = "wl_chassis_t *",
        address = 0x2000_0178,
        size = 4,
        encoding = "pointer",
        isPointer = true,
        pointeeSize = 216,
        pointeeType = "wl_chassis_t",
        pointeeEncoding = "composite",
        members = listOf(
            SymbolNode(
                name = "_ctx",
                typeName = "wl_chassis_ctx_t",
                address = 56,
                size = 104,
                encoding = "composite",
                members = listOf(
                    SymbolNode(name = "seq", typeName = "uint32_t", address = 160, size = 4, encoding = "unsigned"),
                    SymbolNode(name = "head", typeName = "uint32_t", address = 164, size = 4, encoding = "unsigned"),
                ),
            ),
            SymbolNode(name = "enabled", typeName = "uint8_t", address = 8, size = 1, encoding = "unsigned"),
        ),
    )

    /** 复刻同类型非指针全局（成员绝对地址）。 */
    private fun sameTypeGlobal(base: Long) = SymbolNode(
        name = "g_chassis_obj",
        typeName = "wl_chassis_t",
        address = base,
        size = 216,
        encoding = "composite",
        members = listOf(
            SymbolNode(name = "enabled", typeName = "uint8_t", address = base + 8, size = 1, encoding = "unsigned"),
            SymbolNode(name = "seq", typeName = "uint32_t", address = base + 160, size = 4, encoding = "unsigned"),
        ),
    )

    @Test
    fun testPointerSourcePromotionMatchesRealFirmwareLayout() {
        val r = EvalHybridPromoter.promote(
            valueText = "0x200003e4 <g_chassis_ctx>",
            typeText = "wl_chassis_t *",
            variables = listOf(pointerVar()),
        )!!
        assertEquals(0x2000_03E4L, r.address)
        assertEquals("wl_chassis_t", r.typeName)
        assertTrue(r.hasChildren)
        val ctx = r.node.members.first { it.name == "_ctx" }
        // 绝对地址 = 0x200003E4 + 相对偏移 56
        assertEquals(0x2000_03E4L + 56, ctx.address)
        // seq 绝对地址与根因证明逐位吻合：0x200003E4 + 160 = 0x20000484
        val seq = ctx.members.first { it.name == "seq" }
        assertEquals(0x2000_0484L, seq.address)
        // 指针成员子树保持 pointee 相对（此处无指针成员，验证 size 来源）
        assertEquals(216, r.size)
        assertEquals("指针全局 g_chassis_ptr", r.source)
    }

    @Test
    fun testReferenceTypeAlsoPromotes() {
        // CRTP instance() 常见签名返回 T&（V1.2.22 只认 * 导致升级不触发的教训）
        val r = EvalHybridPromoter.promote(
            valueText = "0x200003e4",
            typeText = "wl_chassis_t &",
            variables = listOf(pointerVar()),
        )
        assertNotNull(r)
        assertEquals(0x2000_03E4L, r!!.address)
    }

    @Test
    fun testSameTypeGlobalSourceRebasesMembers() {
        val r = EvalHybridPromoter.promote(
            valueText = "0x20000500",
            typeText = "wl_chassis_t *",
            variables = listOf(sameTypeGlobal(0x2000_0400L)),
        )!!
        assertEquals(0x2000_0500L, r.address)
        // delta = 0x100：成员绝对地址整体位移
        assertEquals(0x2000_0500L + 8, r.node.members.first { it.name == "enabled" }.address)
        assertEquals(0x2000_0500L + 160, r.node.members.first { it.name == "seq" }.address)
        assertEquals("同类型全局 g_chassis_obj", r.source)
    }

    @Test
    fun testPointerMemberSubtreeKeepsPointeeRelative() {
        // 成员中的指针节点：自身存储地址随基址位移，子树保持 pointee 相对
        val ptrMember = SymbolNode(
            name = "next",
            typeName = "wl_chassis_t *",
            address = 0x2000_0418L, // 同类型全局成员约定为绝对地址（基址 0x20000400 + 24）
            size = 4,
            encoding = "pointer",
            isPointer = true,
            members = listOf(SymbolNode(name = "deref", typeName = "uint32_t", address = 12, size = 4, encoding = "unsigned")),
        )
        val root = sameTypeGlobal(0x2000_0400L).copy(members = listOf(ptrMember))
        val r = EvalHybridPromoter.promote(
            valueText = "0x20000500", typeText = "wl_chassis_t *",
            variables = listOf(root),
        )!!
        val m = r.node.members.single()
        assertEquals(0x2000_0500L + 24, m.address) // 存储地址随基址位移
        assertEquals(12, m.members.single().address) // pointee 相对不位移
    }

    @Test
    fun testScalarFallbackWhenNoLayoutSource() {
        val r = EvalHybridPromoter.promote(
            valueText = "0x20001000", typeText = "uint16_t *",
            variables = listOf(pointerVar()), // pointeeType wl_chassis_t 不匹配 uint16_t
        )!!
        assertFalse(r.hasChildren)
        assertEquals(4, r.size)
        assertEquals("uint16_t", r.typeName)
    }

    @Test
    fun testNonPointerAndInvalidInputRejected() {
        // 标量结果（类型非指针/引用）：无可固定读取目标，不升级
        assertNull(EvalHybridPromoter.promote("42", "int", listOf(pointerVar())))
        // 无地址文本 / 非法低地址 / 空类型
        assertNull(EvalHybridPromoter.promote("42", "wl_chassis_t *", listOf(pointerVar())))
        assertNull(EvalHybridPromoter.promote("0x10", "wl_chassis_t *", listOf(pointerVar())))
        assertNull(EvalHybridPromoter.promote("0x200003e4", "", listOf(pointerVar())))
        assertNull(EvalHybridPromoter.promote(null, "wl_chassis_t *", listOf(pointerVar())))
    }
}
