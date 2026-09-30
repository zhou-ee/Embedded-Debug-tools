package org.embedded.monitor.core

import java.io.Writer
import java.util.Locale
import java.util.PriorityQueue

/** 按实际时间戳合并通道，缺失格留空，原始值不经过显示滤波。 */
object ScopeCsv {
    private class Cursor(val channel: Int, val series: List<ScopeSample>, var index: Int = 0) {
        val sample get() = series[index]
    }

    fun write(writer: Writer, variables: List<ScopeVariable>, snapshot: Map<Long, List<ScopeSample>>): Int {
        fun quoted(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${value.replace("\"", "\"\"")}\"" else value
        writer.write((listOf("time") + variables.map { quoted(it.name) }).joinToString(",") + "\n")
        val cursors = PriorityQueue<Cursor>(compareBy { it.sample.timestampNanos })
        variables.forEachIndexed { i, variable ->
            snapshot[variable.address]?.takeIf { it.isNotEmpty() }?.let { cursors.add(Cursor(i, it)) }
        }
        var rows = 0
        while (cursors.isNotEmpty()) {
            val timestamp = cursors.peek().sample.timestampNanos
            val cells = Array(variables.size) { "" }
            while (cursors.isNotEmpty() && cursors.peek().sample.timestampNanos == timestamp) {
                val cursor = cursors.remove()
                val value = cursor.sample.value
                if (value.isFinite()) cells[cursor.channel] = value.toString()
                cursor.index++
                if (cursor.index < cursor.series.size) cursors.add(cursor)
            }
            writer.write(String.format(Locale.ROOT, "%.9f", timestamp / 1e9))
            writer.write("," + cells.joinToString(",") + "\n")
            rows++
        }
        return rows
    }
}
