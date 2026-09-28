package org.embedded.monitor.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * embedded-clion-agent 的 TCP JSON 行客户端。
 *
 * 生命周期：[start] 拉起 agent 进程（--port 0，从 stdout 读就绪行拿端口）→ 建立连接 →
 * 读线程分发响应/事件 → [close] 关 socket、杀进程。线程模型：
 *  - reader 线程：socket 读 + JSON 解析 + 回调分发（回调应快速返回，UI 跳转由调用方处理）
 *  - writer：所有请求经 [sendRequest] 直写（synchronized）
 */
class AgentClient(
    private val agentPath: String,
    private val extraArgs: List<String> = emptyList(),
    private val onEngineEvent: (EngineEvent) -> Unit = {},
    private val onProcessExit: (Int?) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : AutoCloseable {

    private val log = Logger.getInstance(AgentClient::class.java)
    private val gson = Gson()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()

    private var process: Process? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()
    @Volatile private var closed = false
    private val lostNotified = java.util.concurrent.atomic.AtomicBoolean(false)

    /** agent 报告的监听端口。 */
    @Volatile var agentPort: Int = -1
        private set

    fun start() {
        check(process == null) { "agent 已启动" }
        val pb = ProcessBuilder(listOf(agentPath, "--host", "127.0.0.1", "--port", "0") + extraArgs)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        process = proc

        // 读就绪行：CLION_AGENT_READY port=<n> pid=<n> proto=<n> token=<hex>
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val ready: ReadyInfo
        try {
            ready = readReadyInfo(stdout)
        } catch (e: Exception) {
            proc.destroyForcibly()
            process = null
            throw IllegalStateException("读取 agent 就绪信号失败: ${e.message}", e)
        }
        if (ready.proto != EXPECTED_PROTOCOL) {
            proc.destroyForcibly()
            process = null
            throw IllegalStateException(
                "agent 协议版本不匹配（agent proto=${ready.proto}，插件期望 proto=$EXPECTED_PROTOCOL）。" +
                    "请同步更新插件与 embedded-clion-agent 至同一版本。",
            )
        }
        agentPort = ready.port
        onLog("agent 已启动 port=${ready.port} pid=${proc.pid()} proto=${ready.proto}")

        // agent 的 stderr 直接转发到 IDE 日志（debug 用）
        Thread({
            val err = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
            err.forEachLine { line -> onLog("[agent] $line") }
        }, "agent-stderr-pump").apply { isDaemon = true }.start()

        // 连接
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress("127.0.0.1", ready.port), 5000)
            sock.tcpNoDelay = true
        } catch (e: Exception) {
            // 连接失败时必须杀掉已启动的 agent，否则成为孤儿进程占用端口/探针
            try { sock.close() } catch (_: Exception) {}
            proc.destroyForcibly()
            process = null
            throw IllegalStateException("连接 agent 端口 ${ready.port} 失败: ${e.message}", e)
        }
        socket = sock
        val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
        writer = w

        // stdout 排水：就绪行之后 agent 若再向 stdout 打日志，避免管道撑满反压卡死
        Thread({
            while (stdout.readLine() != null) { /* 丢弃 */ }
        }, "agent-stdout-drain").apply { isDaemon = true }.start()

        // 协议握手：hello 必须是第一条消息（agent 校验 token，不匹配即断开）
        try {
            synchronized(writeLock) {
                val hello = JsonObject().apply {
                    addProperty("id", 0)
                    addProperty("method", "hello")
                    add(
                        "params",
                        JsonObject().apply {
                            if (ready.token != null) addProperty("token", ready.token)
                        },
                    )
                }
                w.write(gson.toJson(hello))
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            proc.destroyForcibly()
            process = null
            try { sock.close() } catch (_: Exception) {}
            throw IllegalStateException("发送 agent 握手失败: ${e.message}", e)
        }

        Thread({
            runReader(sock)
        }, "agent-reader").apply { isDaemon = true }.start()

        // 进程退出监听
        Thread({
            val code = proc.waitFor()
            if (!closed) {
                onLog("agent 进程退出 code=$code")
                onProcessExit(code)
            }
        }, "agent-exit-watch").apply { isDaemon = true }.start()
    }

    private class ReadyInfo(val port: Int, val proto: Int, val token: String?)

    private fun readReadyInfo(stdout: BufferedReader): ReadyInfo {
        // 就绪行有 5s 宽限（agent 绑定端口应当是毫秒级）。
        // 不能直接 readLine()：agent 卡住不输出时它会**无限期阻塞**，
        // deadline 检查永远走不到，调用线程永久挂死、进程无人回收——
        // 改为有界轮询 + 缓冲区拼行，超时/EOF 立即失败
        val deadline = System.currentTimeMillis() + 5000
        val buf = CharArray(512)
        val pendingChars = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (!stdout.ready()) {
                Thread.sleep(20)
                continue
            }
            val n = stdout.read(buf)
            if (n < 0) break // EOF：agent 提前退出
            pendingChars.append(buf, 0, n)
            while (true) {
                val nl = pendingChars.indexOf('\n')
                if (nl < 0) break
                val line = pendingChars.substring(0, nl).trim()
                pendingChars.deleteRange(0, nl + 1)
                if (!line.startsWith("CLION_AGENT_READY")) continue
                val port = Regex("port=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: continue
                val proto = Regex("proto=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
                val token = Regex("token=([0-9a-zA-Z]+)").find(line)?.groupValues?.get(1)
                return ReadyInfo(port, proto, token)
            }
        }
        throw IllegalStateException("agent 未在 5 秒内报告就绪端口")
    }

    private fun runReader(sock: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                // 单行解析失败只丢该行并记日志，不判死整条连接
                val obj = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (e: Exception) {
                    log.warn("忽略无法解析的 agent 行: ${line.take(200)}", e)
                    continue
                }
                when {
                    obj.has("event") -> {
                        // 字段访问（asString/asJsonObject）也要守住"单行丢弃"的意图：
                        // 畸形行抛出的 ClassCastException 落到外层会误判死整条连接
                        try {
                            val name = obj.get("event").asString
                            val data = obj.getAsJsonObject("data") ?: JsonObject()
                            if (name == "engine") {
                                onEngineEvent(EngineEventParser.parse(data))
                            }
                        } catch (e: Exception) {
                            log.warn("忽略无法解析的 agent 事件行: ${line.take(200)}", e)
                        }
                    }
                    obj.has("id") -> {
                        try {
                            val id = obj.get("id").asLong
                            pending.remove(id)?.complete(obj)
                        } catch (e: Exception) {
                            log.warn("忽略无法解析的 agent 响应行: ${line.take(200)}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) onLog("agent 连接中断: ${e.message}")
        } finally {
            failAllPending("agent 连接已断开")
            notifyLost()
        }
    }

    private fun notifyLost() {
        if (lostNotified.compareAndSet(false, true) && !closed) {
            onProcessExit(null)
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.forEach { pending.remove(it)?.completeExceptionally(IllegalStateException(reason)) }
    }

    /** 发送请求并异步等待响应（ok=false / 超时 / 断线都会异常完成；超时后清理 pending 防泄漏）。 */
    fun request(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 10_000): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val req = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete { _, err ->
                if (err != null) pending.remove(id)
            }
        val w = writer
        if (w == null || closed) {
            pending.remove(id)
            future.completeExceptionally(IllegalStateException("agent 未连接"))
            return future
        }
        try {
            synchronized(writeLock) {
                w.write(gson.toJson(req))
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            future.completeExceptionally(e)
            // 写失败 = 会话已不可恢复：通知上层走重连
            notifyLost()
            return future
        }
        return future
    }

    /** 同步请求（后台线程使用；UI 线程禁用）。 */
    fun requestSync(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 10_000): JsonObject =
        request(method, params, timeoutMs).get(timeoutMs + 1000, TimeUnit.MILLISECONDS)

    override fun close() {
        if (closed) return
        closed = true
        failAllPending("agent 已关闭")
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        socket = null
        writer = null
    }

    companion object {
        /** 与 agent 端（main.rs PROTOCOL_VERSION）约定同步递增的行协议版本。 */
        const val EXPECTED_PROTOCOL = 1

        /** 找空闲本地端口（避免和 agent 的 --port 0 打架，这里只用于测试辅助）。 */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
