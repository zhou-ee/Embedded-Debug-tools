package org.embedded.monitor.watch

import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.core.ValueFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 一条实时变量监视项。 */
data class WatchItem(
    val id: String,
    val expr: String,
    var address: Long,
    var size: Int,
    /** elf-info ValueEncoding：unsigned/signed/float/bool/pointer/composite */
    var encoding: String,
    var typeName: String,
    var autoRefresh: Boolean = true,
    @Transient var lastBytes: ByteArray? = null,
    @Transient var halted: Boolean = false,
) {
    var node: SymbolNode? = null

    constructor(
        id: String,
        expr: String,
        address: Long,
        size: Int,
        encoding: String,
        typeName: String,
        autoRefresh: Boolean = true,
        node: SymbolNode? = null,
    ) : this(id, expr, address, size, encoding, typeName, autoRefresh, null, false) {
        this.node = node
    }

    override fun equals(other: Any?): Boolean = other is WatchItem && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** 监视值格式化：按编码解码为 十进制 + 十六进制 展示。 */
object WatchValueFormatter {

    private val idGen = AtomicLong(1)
    fun nextWatchId(): String = "w" + idGen.getAndIncrement()

    fun format(item: WatchItem, bytes: ByteArray?): String {
        val node = item.node
        if (node != null) {
            return formatNode(node, bytes ?: ByteArray(0))
        }
        if (bytes == null || bytes.isEmpty()) return "-"
        val fmt = ValueFormat.fromEncoding(item.encoding, item.size)
        val n = bytes.size
        return when (item.encoding.lowercase()) {
            "float" -> when {
                n >= 8 -> String.format(Locale.ROOT, "%.7g", decoder64(fmt, bytes))
                else -> String.format(Locale.ROOT, "%.6g", fmt.decode(bytes))
            }
            "composite" -> "0x" + bytes.take(8).joinToString("") { "%02X".format(it) } + if (n > 8) "…" else ""
            "bool" -> if ((bytes[0].toInt() and 0xFF) != 0) "true" else "false"
            "pointer" -> formatPointer(bytes, n)
            else -> scalarText(fmt, bytes)
        }
    }

    /** 对单个 SymbolNode 节点进行值格式化（支持结构体、枚举、指针、数组成员等）。 */
    fun formatNode(node: SymbolNode, bytes: ByteArray): String {
        if (node.encoding.equals("composite", ignoreCase = true)) {
            return if (node.members.isNotEmpty()) "{…}" else "{ ? }"
        }
        if (bytes.isEmpty()) return "-"
        // 枚举类型匹配
        val enums = node.enumValues
        if (!enums.isNullOrEmpty()) {
            val intVal = runCatching {
                val safeBytes = if (bytes.size < 4) {
                    val p = ByteArray(4)
                    System.arraycopy(bytes, 0, p, 0, bytes.size)
                    p
                } else bytes
                val buf = ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN)
                val isSigned = node.encoding.equals("signed", ignoreCase = true)
                when (node.size) {
                    1 -> if (isSigned) safeBytes[0].toLong() else safeBytes[0].toLong() and 0xFF
                    2 -> if (isSigned) buf.short.toLong() else buf.short.toLong() and 0xFFFF
                    4 -> if (isSigned) buf.int.toLong() else buf.int.toLong() and 0xFFFFFFFFL
                    else -> buf.long
                }
            }.getOrNull()
            if (intVal != null) {
                for (entry in enums) {
                    val evNum = (entry.getOrNull(0) as? Number)?.toLong()
                        ?: entry.getOrNull(0)?.toString()?.toLongOrNull()
                    val evName = entry.getOrNull(1)?.toString()
                    if (evNum != null && evName != null && evNum == intVal) {
                        return "$evName ($intVal)"
                    }
                }
            }
        }
        if (node.isPointer || node.encoding.equals("pointer", ignoreCase = true)) {
            return formatPointer(bytes, bytes.size)
        }
        val fmt = ValueFormat.fromEncoding(node.encoding, node.size)
        val n = bytes.size
        return when (node.encoding.lowercase()) {
            "float" -> when {
                n >= 8 -> String.format(Locale.ROOT, "%.7g", decoder64(fmt, bytes))
                else -> String.format(Locale.ROOT, "%.6g", fmt.decode(bytes))
            }
            "bool" -> if ((bytes[0].toInt() and 0xFF) != 0) "true" else "false"
            else -> scalarText(fmt, bytes)
        }
    }

    /**
     * 对标 CLion 原生监视视图格式化：
     * - 结构体/复合类型：返回空字符串（仅由 {TypeName} 展示即可，如 g_motor = {MotorState}）
     * - 8 位整型/字符：数值 + 字符转义（如 0 '\000', 1 '\001', 65 'A'）
     * - 16/32/64 位整型：纯十进制（如 30755, 957653, 61）
     * - 浮点型：十进制浮点表示（如 15.0049391, -99.9506073）
     * - 枚举：枚举符号优先展示（如 ST_IDLE）
     * - 指针：十六进制地址（如 0x20001000）
     * - 布尔：true / false
     */
    fun formatNativeValue(node: SymbolNode, bytes: ByteArray): String {
        if (node.isPointer || node.encoding.equals("pointer", ignoreCase = true)) {
            return if (bytes.isEmpty()) "" else formatPointer(bytes, bytes.size)
        }
        if (node.encoding.equals("composite", ignoreCase = true) || node.members.isNotEmpty()) {
            return ""
        }
        if (bytes.isEmpty()) return ""

        // 枚举类型匹配：直接返回符号名（如 ST_IDLE）
        val enums = node.enumValues
        if (!enums.isNullOrEmpty()) {
            val intVal = runCatching {
                val safeBytes = if (bytes.size < 4) {
                    val p = ByteArray(4)
                    System.arraycopy(bytes, 0, p, 0, bytes.size)
                    p
                } else bytes
                val buf = ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN)
                val isSigned = node.encoding.equals("signed", ignoreCase = true)
                when (node.size) {
                    1 -> if (isSigned) safeBytes[0].toLong() else safeBytes[0].toLong() and 0xFF
                    2 -> if (isSigned) buf.short.toLong() else buf.short.toLong() and 0xFFFF
                    4 -> if (isSigned) buf.int.toLong() else buf.int.toLong() and 0xFFFFFFFFL
                    else -> buf.long
                }
            }.getOrNull()
            if (intVal != null) {
                for (entry in enums) {
                    val evNum = (entry.getOrNull(0) as? Number)?.toLong()
                        ?: entry.getOrNull(0)?.toString()?.toLongOrNull()
                    val evName = entry.getOrNull(1)?.toString()
                    if (evNum != null && evName != null && evNum == intVal) {
                        return evName
                    }
                }
            }
        }

        if (node.isPointer || node.encoding.equals("pointer", ignoreCase = true)) {
            return formatPointer(bytes, bytes.size)
        }

        if (node.encoding.equals("bool", ignoreCase = true)) {
            return if ((bytes[0].toInt() and 0xFF) != 0) "true" else "false"
        }

        val fmt = ValueFormat.fromEncoding(node.encoding, node.size)
        val n = bytes.size
        if (node.encoding.equals("float", ignoreCase = true)) {
            return when {
                n >= 8 -> decoder64(fmt, bytes).toString()
                else -> fmt.decode(bytes).toString()
            }
        }

        // 8 位整数 / 字符：0 '\000', 1 '\001', 65 'A'
        val isCharOrByte = node.size == 1 || Regex("""(uint8|int8|char|byte)""", RegexOption.IGNORE_CASE).containsMatchIn(node.typeName)
        if (isCharOrByte && node.size <= 1) {
            val isSigned = node.encoding.equals("signed", ignoreCase = true)
            val num = if (isSigned) bytes[0].toInt() else (bytes[0].toInt() and 0xFF)
            val charRepr = when (num) {
                in 32..126 -> if (num == 39) "'\\''" else "'${num.toChar()}'"
                0 -> "'\\000'"
                7 -> "'\\a'"
                8 -> "'\\b'"
                9 -> "'\\t'"
                10 -> "'\\n'"
                11 -> "'\\v'"
                12 -> "'\\f'"
                13 -> "'\\r'"
                else -> "'\\%03o'".format(Locale.ROOT, num and 0xFF)
            }
            return "$num $charRepr"
        }

        // 标量纯十进制整数展示
        val safeBytes = if (bytes.size < fmt.byteSize) {
            val padded = ByteArray(fmt.byteSize)
            System.arraycopy(bytes, 0, padded, 0, bytes.size)
            padded
        } else bytes
        return when (fmt) {
            ValueFormat.I8 -> safeBytes[0].toInt().toString()
            ValueFormat.U8 -> (safeBytes[0].toInt() and 0xFF).toString()
            ValueFormat.I16 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).short.toString()
            ValueFormat.U16 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).short.let { (it.toInt() and 0xFFFF).toString() }
            ValueFormat.I32 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).int.toString()
            ValueFormat.U32 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).int.let { (it.toLong() and 0xFFFFFFFFL).toString() }
            ValueFormat.I64 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).long.toString()
            ValueFormat.U64 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).long.let { java.lang.Long.toUnsignedString(it) }
            ValueFormat.F32 -> fmt.decode(safeBytes).toString()
            ValueFormat.F64 -> decoder64(fmt, safeBytes).toString()
        }
    }

    private fun formatPointer(bytes: ByteArray, n: Int): String {
        val padded = ByteArray(if (n <= 4) 4 else 8)
        System.arraycopy(bytes, 0, padded, 0, minOf(n, padded.size))
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        return if (n <= 4) {
            val addr = buf.int.toLong() and 0xFFFFFFFFL
            "0x%08X".format(addr)
        } else {
            val addr = buf.long
            "0x%016X".format(addr)
        }
    }

    /** 解析输入文本（十进制 / 0x 十六进制 / 浮点 / 布尔），编码为指定类型的内存字节。 */
    fun encodeValue(text: String, encoding: String, size: Int): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when (encoding.lowercase()) {
                "float" -> {
                    val d = trimmed.toDouble()
                    if (size >= 8) {
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array()
                    } else {
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(d.toFloat()).array()
                    }
                }
                "bool" -> {
                    val v = when (trimmed.lowercase()) {
                        "true", "1" -> 1.toByte()
                        "false", "0" -> 0.toByte()
                        else -> return null
                    }
                    byteArrayOf(v)
                }
                "pointer" -> {
                    val addr = if (trimmed.startsWith("0x", ignoreCase = true)) {
                        java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
                    } else {
                        java.lang.Long.parseUnsignedLong(trimmed, 10)
                    }
                    val buf = ByteBuffer.allocate(if (size <= 4) 4 else 8).order(ByteOrder.LITTLE_ENDIAN)
                    if (size <= 4) buf.putInt((addr and 0xFFFFFFFFL).toInt()) else buf.putLong(addr)
                    buf.array()
                }
                else -> {
                    val isHex = trimmed.startsWith("0x", ignoreCase = true)
                    val rawVal = if (isHex) {
                        java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
                    } else {
                        trimmed.toLongOrNull()
                            ?: trimmed.toDoubleOrNull()?.toLong()
                            ?: return null
                    }
                    val byteLen = size.coerceIn(1, 8)
                    val buf = ByteBuffer.allocate(byteLen).order(ByteOrder.LITTLE_ENDIAN)
                    when (byteLen) {
                        1 -> buf.put(rawVal.toByte())
                        2 -> buf.putShort(rawVal.toShort())
                        4 -> buf.putInt(rawVal.toInt())
                        8 -> buf.putLong(rawVal)
                        else -> buf.putInt(rawVal.toInt())
                    }
                    buf.array()
                }
            }
        }.getOrNull()
    }

    /** 标量：十进制 + 十六进制。 */
    private fun scalarText(fmt: ValueFormat, bytes: ByteArray): String {
        val safeBytes = if (bytes.size < fmt.byteSize) {
            val padded = ByteArray(fmt.byteSize)
            System.arraycopy(bytes, 0, padded, 0, bytes.size)
            padded
        } else bytes
        val dec = when (fmt) {
            ValueFormat.I8 -> safeBytes[0].toInt().toString()
            ValueFormat.U8 -> (safeBytes[0].toInt() and 0xFF).toString()
            ValueFormat.I16 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).short.toString()
            ValueFormat.U16 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).short.let { (it.toInt() and 0xFFFF).toString() }
            ValueFormat.I32 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).int.toString()
            ValueFormat.U32 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).int.let { (it.toLong() and 0xFFFFFFFFL).toString() }
            ValueFormat.I64 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).long.toString()
            ValueFormat.U64 -> ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN).long.let { java.lang.Long.toUnsignedString(it) }
            ValueFormat.F32 -> fmt.decode(safeBytes).toString()
            ValueFormat.F64 -> decoder64(fmt, safeBytes).toString()
        }
        val hex = hexOf(safeBytes)
        return if (hex.length <= 8) "$dec (0x$hex)" else dec
    }

    private fun decoder64(fmt: ValueFormat, bytes: ByteArray): Double {
        val safeBytes = if (bytes.size < 8) {
            val padded = ByteArray(8)
            System.arraycopy(bytes, 0, padded, 0, bytes.size)
            padded
        } else bytes
        val buf = ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (fmt) {
            ValueFormat.F64 -> buf.double
            else -> buf.float.toDouble()
        }
    }

    fun hexOf(bytes: ByteArray): String =
        bytes.reversed().joinToString("") { "%02X".format(it) }
}
