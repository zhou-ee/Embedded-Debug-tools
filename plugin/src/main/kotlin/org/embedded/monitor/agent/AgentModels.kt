package org.embedded.monitor.agent

import com.google.gson.JsonObject

/**
 * Agent 协议模型。协议为本地 TCP 上的 JSON 行：
 *  - 请求  {"id":n,"method":"...","params":{...}}
 *  - 响应  {"id":n,"ok":true,"result":...} / {"id":n,"ok":false,"error":"..."}
 *  - 事件  {"event":"engine","data":{...引擎 Event（tag=kind, camelCase）...}}
 */

/** elf_load 返回的变量节点（对应 elf-info SymbolNode，camelCase）。 */
data class SymbolNode(
    val name: String = "",
    val typeName: String = "",
    val address: Long = 0,
    val size: Int = 0,
    val encoding: String = "unsigned",
    val isPointer: Boolean = false,
    val pointeeSize: Int = 0,
    val pointeeType: String = "",
    val pointeeEncoding: String = "unsigned",
    val enumValues: List<List<Any>>? = null, // [[value, name], ...]
    val members: List<SymbolNode> = emptyList(),
    val declFile: String? = null,
    val declLine: Int? = null,
)

data class ProbeInfo(
    val name: String = "",
    val serial: String? = null,
    val vid: Int = 0,
    val pid: Int = 0,
    val probeType: String = "",
)

data class TargetInfo(
    val name: String = "",
    val vendor: String = "",
    val family: String = "",
    val cores: Int = 0,
)

/** 引擎事件（agent 转发的 monitor Event）。 */
sealed class EngineEvent {
    data class Connected(val description: String) : EngineEvent()
    data class Disconnected(val reason: String) : EngineEvent()
    data class State(val state: String) : EngineEvent() // running / halted / disconnected
    data class WatchData(val values: Map<String, ByteArray>, val halted: Boolean) : EngineEvent()
    data class ScopeData(val samples: List<ScopeRawSample>, val intervalUs: Long? = null) : EngineEvent()
    data class Error(val message: String) : EngineEvent()
    data class Log(val message: String) : EngineEvent()
    data class Unknown(val kind: String) : EngineEvent()
}

/** scopeData 单帧：t 为 epoch 秒；values 为 "0x%08x" → 原始字节。 */
data class ScopeRawSample(val t: Double, val values: Map<String, ByteArray>)

/** 引擎事件解析（容错：未知 kind / 字段缺省）。 */
object EngineEventParser {
    fun parse(data: JsonObject): EngineEvent {
        val kind = data.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
            ?: data.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return EngineEvent.Unknown("?")
        return when (kind) {
            "connected" -> EngineEvent.Connected(data.get("description")?.asString ?: "")
            "disconnected" -> EngineEvent.Disconnected(data.get("reason")?.asString ?: "")
            "state" -> EngineEvent.State(data.get("state")?.asString ?: "disconnected")
            "error" -> EngineEvent.Error(data.get("message")?.asString ?: "")
            "log" -> EngineEvent.Log(data.get("message")?.asString ?: "")
            "watchData" -> {
                val values = HashMap<String, ByteArray>()
                val obj = data.getAsJsonObject("values")
                obj?.entrySet()?.forEach { (k, v) ->
                    values[k] = bytesOf(v)
                }
                EngineEvent.WatchData(values, data.get("halted")?.asBoolean ?: false)
            }
            "scopeData" -> {
                // 本批实际配置的采样间隔（µs）；旧 agent 缺省 null → 前端按配置频率推算
                val intervalUs = data.get("intervalUs")?.takeIf { it.isJsonPrimitive }?.asLong
                val samples = ArrayList<ScopeRawSample>()
                data.getAsJsonArray("samples")?.forEach { el ->
                    val o = el.asJsonObject
                    val values = HashMap<String, ByteArray>()
                    o.getAsJsonObject("values")?.entrySet()?.forEach { (k, v) ->
                        values[k] = bytesOf(v)
                    }
                    samples.add(ScopeRawSample(o.get("t")?.asDouble ?: 0.0, values))
                }
                EngineEvent.ScopeData(samples, intervalUs)
            }
            else -> EngineEvent.Unknown(kind)
        }
    }

    /** JSON 数字数组 → ByteArray（Gson 把 Vec<u8> 渲染为 [..] 数字）。 */
    private fun bytesOf(v: com.google.gson.JsonElement): ByteArray {
        if (!v.isJsonArray) return ByteArray(0)
        val arr = v.asJsonArray
        return ByteArray(arr.size()) { i -> arr[i].asNumber.toInt().and(0xFF).toByte() }
    }
}
