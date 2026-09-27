package org.embedded.monitor.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 采样值解码格式。decode 输入为该目标本次采样的原始字节（小端），返回浮点表示。
 */
enum class ValueFormat {
    I8, U8, I16, U16, I32, U32, I64, U64, F32, F64;

    val byteSize: Int
        get() = when (this) {
            I8, U8 -> 1
            I16, U16 -> 2
            I32, U32, F32 -> 4
            I64, U64, F64 -> 8
        }

    fun decode(bytes: ByteArray): Float = SampleDecoder.decode(this, bytes)

    companion object {
        /** 按字节宽推断默认格式；未知宽度回退 I32。 */
        fun inferFromSize(size: Int): ValueFormat = when {
            size <= 1 -> U8
            size == 2 -> I16
            size == 4 -> F32
            size >= 8 -> F64
            else -> I32
        }

        /**
         * 由 elf-info 的 ValueEncoding + 字节宽推断示波解码格式。
         * encoding 取值：unsigned / signed / float / bool / pointer / composite。
         */
        fun fromEncoding(encoding: String?, size: Int): ValueFormat = when (encoding?.lowercase()) {
            "float" -> if (size >= 8) F64 else F32
            "bool" -> U8
            "pointer" -> U32
            "signed" -> when {
                size <= 1 -> I8
                size == 2 -> I16
                size == 8 -> I64
                else -> I32
            }
            "composite" -> when { // 无标量编码：退回按宽度展示
                size <= 1 -> U8
                size == 2 -> U16
                size == 8 -> U64
                else -> U32
            }
            else -> when { // unsigned / 未知
                size <= 1 -> U8
                size == 2 -> U16
                size == 8 -> U64
                else -> U32
            }
        }

        /** C 类型别名 → (ValueFormat, 字节宽)，与前端 watchExpr.ts 的别名列保持一致。 */
        fun fromTypeAlias(type: String): Pair<ValueFormat, Int>? = when (type.lowercase()) {
            "u8", "uint8", "uint8_t" -> U8 to 1
            "i8", "int8", "int8_t", "s8" -> I8 to 1
            "u16", "uint16", "uint16_t" -> U16 to 2
            "i16", "int16", "int16_t", "s16" -> I16 to 2
            "u32", "uint32", "uint32_t" -> U32 to 4
            "i32", "int32", "int32_t", "s32", "int", "long" -> I32 to 4
            "u64", "uint64", "uint64_t" -> U64 to 8
            "i64", "int64", "int64_t", "s64", "long long" -> I64 to 8
            "f32", "float" -> F32 to 4
            "f64", "double" -> F64 to 8
            "bool", "_bool", "_bool" -> U8 to 1
            "ptr", "pointer" -> U32 to 4
            else -> null
        }
    }
}

internal object SampleDecoder {
    fun decode(format: ValueFormat, bytes: ByteArray): Float {
        val safeBytes = if (bytes.size < format.byteSize) {
            val padded = ByteArray(format.byteSize)
            System.arraycopy(bytes, 0, padded, 0, bytes.size)
            padded
        } else bytes
        val buf = ByteBuffer.wrap(safeBytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (format) {
            ValueFormat.I8 -> buf.get().toFloat()
            ValueFormat.U8 -> (buf.get().toInt() and 0xFF).toFloat()
            ValueFormat.I16 -> buf.getShort().toFloat()
            ValueFormat.U16 -> (buf.getShort().toInt() and 0xFFFF).toFloat()
            ValueFormat.I32 -> buf.getInt().toFloat()
            ValueFormat.U32 -> (buf.getInt().toLong() and 0xFFFFFFFFL).toFloat()
            ValueFormat.I64 -> buf.getLong().toFloat()
            ValueFormat.U64 -> buf.getLong().toULong().toFloat()
            ValueFormat.F32 -> buf.getFloat()
            ValueFormat.F64 -> buf.getDouble().toFloat()
        }
    }
}
