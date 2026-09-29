package org.embedded.monitor.agent

/**
 * CLion 求值型监视的混合升级：GDB 求值结果为指针/引用（值文本含 0x 地址、
 * 类型 `T *` / `T &`）时，推导该固定地址处的成员布局，供运行时按常规 watch
 * 链路持续读取（下次断点重求值后地址随之更新）。
 *
 * 纯函数、无平台依赖——可用真实固件布局做单元测试。
 * 成员地址重定基守卫与 agent rebase_addresses 一致：指针成员子树保持
 * pointee 相对不位移（其存储地址自身仍随基址位移）。
 */
object EvalHybridPromoter {

    class Result(
        val address: Long,
        val size: Int,
        val node: SymbolNode,
        val typeName: String,
        val encoding: String,
        val hasChildren: Boolean,
        /** 布局来源说明（用于日志/诊断） */
        val source: String,
    )

    /**
     * @param valueText CLion 呈现的值文本（形如 "0x200003e4 <g_chassis_ctx>"）
     * @param typeText  CLion 呈现的类型文本（形如 "wl_chassis_t *" / "wl_chassis_t &"）
     * @param variables ELF 顶层全局变量（含指针变量的 pointee 相对成员子树）
     * @return 无法解析为可监视的指针/引用时返回 null（保持纯求值语义）
     */
    fun promote(valueText: String?, typeText: String?, variables: List<SymbolNode>): Result? {
        if (valueText.isNullOrBlank() || typeText.isNullOrBlank()) return null
        val t = typeText.trim()
        val isRef = t.endsWith("&")
        if (!t.endsWith("*") && !isRef) return null
        val addr = Regex("0x([0-9a-fA-F]{6,8})").find(valueText)
            ?.groupValues?.get(1)?.toLongOrNull(16) ?: return null
        if (addr < 0x1000L || addr > 0xFFFF_F000L) return null
        val pointeeName = t.removeSuffix("*").removeSuffix("&").trim()
            .removePrefix("const ").removePrefix("volatile ").trim()
            .substringAfterLast("::")
        if (pointeeName.isEmpty()) return null

        // 来源 1：同类型非指针全局（成员为绝对地址，重定基 delta = addr - 根地址）
        val sameType = variables.firstOrNull { v ->
            !v.isPointer && v.members.isNotEmpty() && v.address >= 0x1000L && matchType(v.typeName, pointeeName)
        }
        if (sameType != null) {
            val delta = addr - sameType.address
            val size = if (sameType.size > 0) sameType.size else 4
            val node = sameType.copy(
                name = "",
                address = addr,
                members = sameType.members.map { rebase(it, delta) },
            )
            return Result(
                addr, size.coerceIn(1, 4096), node,
                sameType.typeName.ifEmpty { pointeeName }, sameType.encoding, true,
                "同类型全局 ${sameType.name}",
            )
        }

        // 来源 2：指针全局（成员为 pointee 相对偏移，delta = addr）
        // 真机实证（STM32G431 firmware）：g_chassis_ptr.pointeeType == wl_chassis_t，
        // 其 _ctx.seq 相对偏移 160 → 绝对 0x200003E4 + 160 = 0x20000484（与 seq 根因证明一致）
        val ptrVar = variables.firstOrNull { v ->
            v.isPointer && v.members.isNotEmpty() && matchType(v.pointeeType, pointeeName)
        }
        if (ptrVar != null) {
            val node = ptrVar.copy(
                name = "",
                address = addr,
                members = ptrVar.members.map { rebase(it, addr) },
            )
            val maxEnd = (node.members.maxOf { it.address + it.size } - addr).toInt()
            val size = if (ptrVar.pointeeSize > 0) ptrVar.pointeeSize else maxEnd
            return Result(
                addr, size.coerceIn(4, 4096), node,
                ptrVar.pointeeType.ifEmpty { pointeeName }, ptrVar.pointeeEncoding, true,
                "指针全局 ${ptrVar.name}",
            )
        }

        // 来源 3：标量回退（无法确定成员布局，定点读 4 字节）
        return Result(
            addr, 4,
            SymbolNode(name = "", typeName = pointeeName, address = addr, size = 4, encoding = "unsigned"),
            pointeeName, "unsigned", false,
            "标量回退（未找到同类型全局/指针）",
        )
    }

    private fun matchType(typeName: String, pointee: String): Boolean {
        val t = typeName.removePrefix("volatile ").removePrefix("const ").trim()
        return t == pointee || t.endsWith(" $pointee") || t.substringAfterLast("::") == pointee
    }

    private fun rebase(n: SymbolNode, delta: Long): SymbolNode = n.copy(
        address = n.address + delta,
        members = if (n.isPointer) n.members else n.members.map { rebase(it, delta) },
    )
}
