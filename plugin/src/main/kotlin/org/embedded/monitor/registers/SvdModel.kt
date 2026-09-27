package org.embedded.monitor.registers

/**
 * CMSIS-SVD 外设与寄存器模型。
 * 对齐 software_ref 中的 SvdDeviceView 与 SvdNodeView 架构。
 */
data class SvdDevice(
    val name: String,
    val description: String? = null,
    val peripherals: List<SvdPeripheral> = emptyList(),
)

data class SvdPeripheral(
    val name: String,
    val description: String? = null,
    val groupName: String? = null,
    val baseAddress: Long,
    val registers: List<SvdRegister> = emptyList(),
)

data class SvdRegister(
    val name: String,
    val description: String? = null,
    val address: Long, // 真实绝对物理内存地址 = peripheral.baseAddress + addressOffset
    val addressOffset: Long = 0,
    val size: Int = 4, // 字节数（一般为 4，即 32 位）
    val access: String? = null, // "read-only", "write-only", "read-write", "RO", "WO", "RW"
    val resetValue: Long? = null,
    val fields: List<SvdField> = emptyList(),
    val path: String, // 唯一树路径，如 "GPIOA/MODER"
)

data class SvdField(
    val name: String,
    val description: String? = null,
    val bitOffset: Int,
    val bitWidth: Int,
    val access: String? = null,
    val enums: List<Pair<Long, String>> = emptyList(), // 枚举值 -> 符号常量名
) {
    val bitsText: String
        get() = if (bitWidth <= 1) "[$bitOffset]" else "[${bitOffset + bitWidth - 1}:$bitOffset]"

    /** 从 32/64 位寄存器原始数值中提取本位域的值 */
    fun extractValue(regValue: Long): Long {
        val mask = if (bitWidth >= 64) -1L else (1L shl bitWidth) - 1L
        return (regValue ushr bitOffset) and mask
    }

    /** 匹配本位域当前数值对应的枚举符号名（若有定义） */
    fun enumName(regValue: Long): String? {
        val v = extractValue(regValue)
        return enums.firstOrNull { it.first == v }?.second
    }
}
