package com.nodejsbox.container.core

import android.content.Context
import android.util.Log
import com.nodejsbox.container.bridge.BridgeDispatcher
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地桥接服务：让 node 脚本调用容器能力（进程管理 / 文件选择 / Toast 等）。
 *
 * 协议：127.0.0.1 上的 TCP + JSON-lines（一行请求、一行响应，UTF-8）：
 *   1. 客户端连上后先发握手行  {"token":"<TOKEN>"}（不匹配直接断开）；
 *   2. 之后每行一个请求  {"seq":1,"cmd":"proc.list","args":{...}}
 *      对应一行响应     {"seq":1,"ok":true,"data":{...}}
 *                      {"seq":1,"ok":false,"error":"..."}
 *
 * 接入方式（对脚本透明）：容器在 spawn node 进程时注入环境变量
 *   NODEJSBOX_BRIDGE=127.0.0.1:<port>
 *   NODEJSBOX_TOKEN=<token>
 * 脚本侧使用内置模块 require('nodejsbox')（见 assets/modules/nodejsbox.js）。
 *
 * 线程模型：accept 循环一个线程；每条连接一个线程；单连接内请求串行处理，
 * 因此 file.pick 这类需要用户交互的命令可以阻塞等待（有超时）。
 */
object BridgeServer {

    private const val TAG = NodeRuntime.TAG
    private const val MAX_LINE_CHARS = 1 shl 20 // 1 MiB，防恶意超长行

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    private val running = AtomicBoolean(false)
    private var appContext: Context? = null

    val isRunning: Boolean get() = running.get()
    val port: Int get() = server?.localPort ?: -1
    val authToken: String get() = token

    /** 幂等启动（监听失败不抛出，只记日志：桥不可用时容器照常工作） */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (running.get()) return
        BridgeDispatcher.ensureCommandsRegistered()
        val ctx = context.applicationContext
        try {
            val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            token = UUID.randomUUID().toString().replace("-", "")
            appContext = ctx
            running.set(true)
            Thread({ acceptLoop(ctx, ss) }, "BridgeServer-accept").start()
            Log.i(TAG, "Bridge 已启动: 127.0.0.1:${ss.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Bridge 启动失败: ${e.message}")
            running.set(false)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: IOException) {}
        server = null
        Log.i(TAG, "Bridge 已停止")
    }

    /** spawn node 进程时注入的桥接环境变量（桥未运行时返回空 Map） */
    fun envForSpawn(): Map<String, String> = if (running.get()) mapOf(
        "NODEJSBOX_BRIDGE" to "127.0.0.1:$port",
        "NODEJSBOX_TOKEN" to token,
    ) else emptyMap()

    // ----------------------------- 连接处理 -----------------------------

    private fun acceptLoop(ctx: Context, ss: ServerSocket) {
        while (running.get()) {
            val socket = try { ss.accept() } catch (e: Exception) { break }
            Thread({ handleConnection(ctx, socket) }, "BridgeServer-conn").start()
        }
        try { ss.close() } catch (_: IOException) {}
    }

    private fun handleConnection(ctx: Context, socket: Socket) {
        Log.i(TAG, "Bridge 连接: ${socket.inetAddress.hostAddress}:${socket.port}")
        try {
            socket.soTimeout = 0 // 阻塞读，file.pick 等待用户操作可能很久
            socket.getInputStream().use { input ->
                socket.getOutputStream().use { output ->
                    val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
                    val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 64 * 1024)

                    // 握手：第一行必须是 {"token":"..."}
                    val handshake = reader.readLine() ?: return
                    if (!BridgeDispatcher.verifyHandshake(handshake, token)) {
                        Log.w(TAG, "Bridge 握手失败（token 不匹配），断开")
                        return
                    }

                    // 命令循环：一行一请求一响应，直到客户端断开
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.length > MAX_LINE_CHARS) {
                            writeLine(writer, BridgeDispatcher.errorJson(-1, "请求行超长"))
                            continue
                        }
                        if (line.isBlank()) continue
                        writeLine(writer, BridgeDispatcher.dispatch(ctx, line))
                        writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Bridge 连接结束: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun writeLine(writer: BufferedWriter, line: String) {
        writer.write(line)
        writer.write("\n")
    }
}
