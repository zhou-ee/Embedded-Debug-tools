package org.embedded.monitor.watch

/**
 * 监视表达式解析（对照前端 src/lib/watchExpr.ts 的语法）：
 *  1. `float @ 0x20000000` —— 类型 @ 地址
 *  2. `0x20000000:u32`     —— 地址 : 类型
 *  3. `0x20000000`         —— 裸地址（默认 u32，needsTypeLookup = true，由 ELF 反查类型）
 *  4. `a.b.c` / `symbol`   —— 成员链 / 符号名（交给 agent 的 elf-info 解析）
 */
object WatchExpressionParser {

    enum class Kind { TYPE_AT_ADDR, ADDR_TYPE, BARE_ADDR, MEMBER_CHAIN }

    data class Parsed(
        val kind: Kind,
        val typeName: String? = null,
        val address: Long? = null,
        val size: Int? = null,
        /** ValueFormat 名（U8/I16/F32...），由类型别名映射 */
        val format: String? = null,
        /** 裸地址时 true：用 elf_type_at_addr 反查真实类型 */
        val needsTypeLookup: Boolean = false,
    )

    private val MEMBER_CHAIN = Regex("""^\*?[A-Za-z_][\w:]*(\[\d+\]|\.\w+|\.\[\d+\])*$""")
    private val HEX_ADDR = Regex("""^0[xX][0-9a-fA-F]+$""")
    private val TYPE_AT_ADDR = Regex("""^(\S+)\s*@\s*(0[xX][0-9a-fA-F]+|\d+)$""")
    private val ADDR_TYPE = Regex("""^(0[xX][0-9a-fA-F]+|\d+)\s*:\s*(\S+)$""")

    fun isMemberChain(input: String): Boolean = MEMBER_CHAIN.matchEntire(input.trim()) != null

    /**
     * 将成员链表达式拆分为分段 Token（支持 a.b.c、arr[0]、motors.[0].speed、ptr->val 等）。
     * 例如："motors[1].speed" -> ["motors", "[1]", "speed"]
     */
    fun splitMemberPath(input: String): List<String> {
        val s = input.trim().removePrefix("*").replace("->", ".")
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '.' -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                    i++
                }
                '[' -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                    val close = s.indexOf(']', i)
                    if (close > i) {
                        tokens.add(s.substring(i, close + 1))
                        i = close + 1
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /** 将表达式规范化为 agent 端 elf-info 兼容的点号分割形式（例如 arr[0] -> arr.[0]）。 */
    fun normalizeForElf(input: String): String =
        splitMemberPath(input).joinToString(".")

    fun parse(input: String): Parsed? {
        val expr = input.trim()
        if (expr.isEmpty()) return null

        // 1. TYPE @ ADDR
        TYPE_AT_ADDR.matchEntire(expr)?.let { m ->
            val (fmt, size) = ValueFormatAlias.alias(m.groupValues[1]) ?: return fallback(m.groupValues[1], m.groupValues[2])
            val addr = parseAddr(m.groupValues[2]) ?: return null
            return Parsed(Kind.TYPE_AT_ADDR, m.groupValues[1], addr, size, fmt.name)
        }

        // 2. ADDR:TYPE
        ADDR_TYPE.matchEntire(expr)?.let { m ->
            val (fmt, size) = ValueFormatAlias.alias(m.groupValues[2]) ?: return fallback(m.groupValues[2], m.groupValues[1])
            val addr = parseAddr(m.groupValues[1]) ?: return null
            return Parsed(Kind.ADDR_TYPE, m.groupValues[2], addr, size, fmt.name)
        }

        // 3. 裸地址
        if (HEX_ADDR.matchEntire(expr) != null || expr.toLongOrNull() != null) {
            val addr = parseAddr(expr) ?: return null
            return Parsed(Kind.BARE_ADDR, "uint32_t", addr, 4, "U32", needsTypeLookup = true)
        }

        // 4. 成员链 / 符号名（含数组、指针、嵌套成员）
        if (MEMBER_CHAIN.matchEntire(expr) != null) {
            return Parsed(Kind.MEMBER_CHAIN)
        }
        return null // 无法解析（含空格/运算符等）
    }

    private fun fallback(typeName: String, addrStr: String): Nothing =
        throw IllegalArgumentException("未知类型别名 '$typeName'（地址 $addrStr）")

    private fun parseAddr(s: String): Long? = runCatching {
        val trimmed = s.trim()
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
        } else {
            java.lang.Long.parseUnsignedLong(trimmed, 10)
        }
    }.getOrNull()
}

/** C 类型别名表（与前端 watchExpr.ts 对齐）。 */
object ValueFormatAlias {
    fun alias(type: String): Pair<org.embedded.monitor.core.ValueFormat, Int>? =
        org.embedded.monitor.core.ValueFormat.fromTypeAlias(type)
}
