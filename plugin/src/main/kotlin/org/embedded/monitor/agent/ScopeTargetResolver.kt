package org.embedded.monitor.agent

import org.embedded.monitor.watch.WatchExpressionParser

/** 用当前 ELF 与当前指针值定位通道；不使用上个固件的绝对地址。 */
object ScopeTargetResolver {
    fun binding(name: String, address: Long, savedExpression: String = "", fixedAddress: Boolean = false): String? {
        // 旧配置没有绑定类型；SVD 外设/内核寄存器始终是固定 MMIO 地址。
        if (fixedAddress || (savedExpression.isBlank() &&
                (address in 0x4000_0000L..0x5FFF_FFFFL || address in 0xE000_0000L..0xE00F_FFFFL))) return null
        return savedExpression.takeIf { it.isNotBlank() } ?: expression(name)
    }

    fun expression(name: String): String? = name.takeIf {
        WatchExpressionParser.isMemberChain(it.replace("->", "."))
    }

    fun resolve(expression: String, variables: List<SymbolNode>, readPointer: (Long) -> Long?): SymbolNode? {
        val tokens = WatchExpressionParser.splitMemberPath(expression)
        var node = variables.firstOrNull { it.name == tokens.firstOrNull() } ?: return null
        var base = 0L
        for (token in tokens.drop(1)) {
            if (node.isPointer) {
                base = readPointer(base + node.address)?.takeIf { it in 0x1000L..0xFFFF_FFFFL } ?: return null
            }
            node = node.members.firstOrNull {
                it.name == token || it.name.removeSurrounding("[", "]") == token.removeSurrounding("[", "]")
            } ?: return null
        }
        if (tokens.size == 1 && node.isPointer && expression.trim().startsWith("*")) {
            base = readPointer(node.address)?.takeIf { it in 0x1000L..0xFFFF_FFFFL } ?: return null
            node = node.copy(address = 0, size = node.pointeeSize, encoding = node.pointeeEncoding)
        }
        val address = base + node.address
        if (address < 0x1000L || node.size <= 0 || address + node.size > 0x1_0000_0000L) return null
        return node.copy(address = address)
    }
}
