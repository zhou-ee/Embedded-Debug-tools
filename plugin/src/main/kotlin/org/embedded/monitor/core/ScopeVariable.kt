package org.embedded.monitor.core

import java.awt.Color

/** 一个被示波器采样的目标（全局变量 / 成员 / 裸地址）。 */
data class ScopeVariable @JvmOverloads constructor(
    val name: String,
    val address: Long,
    val size: Int,
    val format: ValueFormat = ValueFormat.inferFromSize(size),
    val colorIndex: Int = 0,
    var visible: Boolean = true,
    var customColor: Color? = null,
    /** 可重新定位的符号表达式；null 表示用户指定的固定地址。 */
    val expression: String? = null,
    @Volatile var resolved: Boolean = true,
)

/** 曲线调色板（对齐独立软件 software_ref PALETTE: #61afef, #98c379, #e06c75, #e5c07b, #c678dd, #56b6c2）。 */
object ScopePalette {
    val COLORS: List<Color> = listOf(
        Color(0x61, 0xAF, 0xEF), // Blue
        Color(0x98, 0xC3, 0x79), // Green
        Color(0xE0, 0x6C, 0x75), // Red
        Color(0xE5, 0xC0, 0x7B), // Yellow/Amber
        Color(0xC6, 0x78, 0xDD), // Purple
        Color(0x56, 0xB6, 0xC2), // Cyan
        Color(0x00, 0xE5, 0xFF), // Neon Cyan
        Color(0xFF, 0x00, 0x55), // Neon Magenta
    )

    fun colorFor(index: Int): Color = COLORS[Math.floorMod(index, COLORS.size)]

    fun colorForVar(variable: ScopeVariable): Color = variable.customColor ?: colorFor(variable.colorIndex)
}

/** 单个采样点：纳秒时间戳 + 解码后的浮点值（NaN 表示读失败，画布断线）。 */
data class ScopeSample(val timestampNanos: Long, val value: Float, val gapBefore: Boolean = false)
