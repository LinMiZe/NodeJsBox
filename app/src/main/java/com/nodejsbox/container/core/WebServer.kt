package com.nodejsbox.container.core

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Web 后端服务器。
 *
 * 单端口同时提供两种服务（仅监听 127.0.0.1，供本机 WebView / 调试用）：
 *  - HTTP GET /            → 返回内置 H5 前端（assets/web/index.html，内存缓存）；
 *  - WebSocket Upgrade     → JSON 消息协议（一条消息一行 JSON，UTF-8 文本帧）。
 *
 * 消息协议（请求 / 响应 / 事件）：
 *   请求  {"seq":1,"cmd":"shell.run","args":{"cmd":"ls -l"}}
 *   响应  {"seq":1,"ok":true,"data":{...}}  |  {"seq":1,"ok":false,"error":"..."}
 *   事件  {"event":"out","id":"dyn-sh-...","line":"..."}    进程输出行（需先 attach）
 *         {"event":"exit","id":"...","code":0}              进程退出
 *
 * 命令一览（args 省略 = 空对象）：
 *   shell.run   {cmd,cwd?}           → {id,pid}   远程 shell：/system/bin/sh -c 执行（cwd 相对 files/，默认 filesDir），动态实例 dyn-sh-*
 *   proc.list   {}                   → {instances:[info…]}（含 pid）
 *   proc.attach {ids:[id] 或 id, tail?=200} → {targets:[{id,lines,exited?,exitCode?}]} 订阅流式输出（可多目标，先补历史尾部；attach 时进程已退出则回 exited=true 补终结事件）
 *   proc.detach {ids:[id] 或 id}      → {ok:true}   取消订阅（未传 ids = 清空本连接全部订阅）
 *   proc.input  {id,text,newline?=true} → 写入进程 stdin
 *   proc.kill   {id}                 → SIGTERM（3s 兜底强杀），同 UI 停止
 *   proc.signal {id}                 → Ctrl+C（ETX + SIGINT）
 *   fs.list     {path?}              → {path,entries:[{name,dir,size,mtime}]}（空=沙箱根；相对=files/ 内；绝对=限沙箱内）
 *   fs.read     {path}               → {content}（≤512KB 文本）
 *   fs.write    {path,content}       → {bytes}
 *   fs.mkdir    {path}               → {ok:true}
 *   fs.delete   {path}               → {ok:true}（仅文件 / 空目录）
 *   fs.rename   {from,to}            → {ok:true}
 *   licenses.read {name}             → {name,title,content}（name: notices.txt | node-LICENSE.txt；读 assets/licenses/，内存缓存）
 *
 * 安全边界：路径一律限定在 app 沙箱（/data/data/<pkg>）内，拒绝 .. 越界；仅回环地址监听。
 * 线程模型：accept 一线程、每连接一线程；流式事件由 RuntimeManager.LineListener
 * 在 pump 线程回调，广播到各连接的订阅表（发送加锁，写失败即丢弃连接订阅）。
 */
object WebServer {

    private const val TAG = NodeRuntime.TAG
    private const val PREFERRED_PORT = 38080
    private const val MAX_FRAME_BYTES = 4 shl 20   // 4 MiB，防超大帧打爆内存
    private const val MAX_FS_TEXT_BYTES = 512 * 1024

    // RFC 6455 opcode
    private const val OP_CONT = 0x0
    private const val OP_TEXT = 0x1
    private const val OP_BIN = 0x2
    private const val OP_CLOSE = 0x8
    private const val OP_PING = 0x9
    private const val OP_PONG = 0xA

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** 进程退出的日志标记行（RuntimeManager.pump 写入）：`---- 进程退出 code=N ----` */
    private val EXIT_MARKER_REGEX = Regex("----\\s*进程退出\\s*code\\s*=\\s*(-?\\d+)")

    @Volatile private var server: ServerSocket? = null
    @Volatile private var webIndexHtml: String = ""
    /** 开源许可文本：name → 内容（首次读取时从 assets/licenses/ 进内存并缓存） */
    private val licenseTexts = ConcurrentHashMap<String, String>()
    private val running = AtomicBoolean(false)
    private val sessions = CopyOnWriteArraySet<Session>()
    private val connCounter = AtomicLong()
    private var appContext: Context? = null

    val isRunning: Boolean get() = running.get()
    val port: Int get() = server?.localPort ?: -1

    /** 内置许可条目（name → 展示标题），原生弹窗与 H5 控制台共用，避免两处硬编码漂移 */
    val LICENSE_ITEMS: List<Pair<String, String>> = listOf(
        "notices.txt" to "内置运行时 · 来源与许可告知（Termux 构建的 Node.js 及各依赖）",
        "node-LICENSE.txt" to "Node.js LICENSE（含其自带的 V8 / libuv / zlib 等第三方组件全文）",
    )

    /** 读一条许可文本（assets/licenses/，首次读取后缓存；缺失返回提示行而不抛） */
    fun licenseText(context: Context, name: String): String {
        licenseTexts[name]?.let { return it }
        val ctx = context.applicationContext
        Diag.init(ctx)
        val text = readAssetText(ctx, "licenses/$name").ifEmpty { "（未打包 licenses/$name）" }
        licenseTexts[name] = text
        return text
    }

    /** 一条 WebSocket 连接：写锁 + 已订阅流式输出的实例 id 集合 */
    internal class Session(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val writeLock = Any()
        val subscribed = ConcurrentHashMap.newKeySet<String>()
    }

    /** 幂等启动（失败只记日志不抛出，风格同 BridgeServer） */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (running.get()) return
        val ctx = context.applicationContext
        appContext = ctx
        Diag.init(ctx)
        // 前端 HTML 读进内存（升级 APK 即生效，不落盘）
        try {
            webIndexHtml = ctx.assets.open("web/index.html")
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "读取 assets/web/index.html 失败: ${e.message}")
            Diag.warn("[web] 前端 HTML 读取失败: ${e.message}")
        }
        // 流式输出钩子：进程每输出一行 → 广播给订阅了该实例的连接
        RuntimeManager.addLineListener { id, line -> onProcLine(id, line) }
        try {
            val ss = try {
                ServerSocket(PREFERRED_PORT, 8, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            }
            server = ss
            running.set(true)
            Thread({ acceptLoop(ss) }, "WebServer-accept").start()
            Log.i(TAG, "WebServer 已启动: http://127.0.0.1:${ss.localPort}")
            Diag.log("[web] 启动 port=${ss.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "WebServer 启动失败: ${e.message}")
            Diag.warn("[web] 启动失败: ${e.message}")
            running.set(false)
        }
    }

    /** assets 文本读取（失败返回空串并留痕，不抛异常——许可入口属非关键路径） */
    private fun readAssetText(ctx: Context, assetPath: String): String = try {
        ctx.assets.open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "读取 assets/$assetPath 失败: ${e.message}")
        Diag.warn("[web] assets/$assetPath 读取失败: ${e.message}")
        ""
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: IOException) {}
        server = null
        sessions.forEach { try { it.socket.close() } catch (_: IOException) {} }
        sessions.clear()
        Log.i(TAG, "WebServer 已停止")
        Diag.log("[web] 停止")
    }

    // ----------------------------- 连接处理（HTTP / WebSocket 升级） -----------------------------

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try { ss.accept() } catch (e: Exception) { break }
            Thread({ handleConnection(socket) }, "WebServer-conn-${connCounter.incrementAndGet()}").start()
        }
        try { ss.close() } catch (_: IOException) {}
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 16 * 1024)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeHttp(socket, 400, "text/plain", "Bad Request")
                return
            }
            val path = parts[1]
            var wsKey: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "sec-websocket-key") wsKey = value
            }
            // 带 WS 升级头 → WebSocket；否则按普通 HTTP 提供前端页面
            if (wsKey != null) {
                Diag.log("[web] WS 连接: ${socket.inetAddress.hostAddress}:${socket.port} path=$path")
                handleWebSocket(socket, input, wsKey)
            } else when (path) {
                "/", "/index.html" -> writeHttp(socket, 200, "text/html; charset=utf-8", webIndexHtml)
                else -> writeHttp(socket, 404, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Web 连接结束: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun writeHttp(socket: Socket, status: Int, contentType: String, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Err"}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Web HTTP 响应失败: ${e.message}")
        }
    }

    // ----------------------------- WebSocket 帧收发 -----------------------------

    private fun handleWebSocket(socket: Socket, input: InputStream, key: String) {
        val accept = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.UTF_8))
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }
        socket.getOutputStream().apply {
            write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.UTF_8))
            flush()
        }
        val session = Session(socket)
        sessions.add(session)
        val finBuf = StringBuilder() // 分片续帧缓冲（单连接内使用，无需加锁）
        try {
            val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
            while (running.get()) {
                val frame = readFrame(din) ?: break
                when (frame.opcode) {
                    OP_TEXT, OP_BIN, OP_CONT -> {
                        finBuf.append(frame.payload.toString(Charsets.UTF_8))
                        if (frame.fin) {
                            val msg = finBuf.toString()
                            finBuf.clear()
                            if (msg.isNotBlank()) handleTextMessage(session, msg)
                        }
                    }
                    OP_PING -> sendFrame(session, OP_PONG, frame.payload)
                    OP_CLOSE -> {
                        sendFrame(session, OP_CLOSE, ByteArray(0))
                        break
                    }
                    else -> { /* 其余控制帧忽略 */ }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Web WS 连接结束: ${e.message}")
        } finally {
            sessions.remove(session)
        }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** 读一个帧；客户端→服务器必须掩码；null = 对端正常关闭 */
    private fun readFrame(din: DataInputStream): Frame? {
        val b0 = din.read()
        if (b0 < 0) return null
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        val b1 = din.read()
        if (b1 < 0) return null
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        when {
            len == 126L -> len = din.readUnsignedShort().toLong()
            len == 127L -> len = din.readLong()
        }
        if (len > MAX_FRAME_BYTES) throw IOException("WS 帧超长: $len")
        val mask = if (masked) ByteArray(4).also { din.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        din.readFully(payload)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun sendFrame(session: Session, opcode: Int, payload: ByteArray) {
        try {
            synchronized(session.writeLock) {
                val out = session.out
                out.write(0x80 or opcode)
                when {
                    payload.size <= 125 -> out.write(payload.size)
                    payload.size <= 0xFFFF -> {
                        out.write(126)
                        out.write(payload.size ushr 8); out.write(payload.size and 0xFF)
                    }
                    else -> {
                        out.write(127)
                        for (i in 7 downTo 0) out.write((payload.size.toLong() ushr (8 * i)).toInt() and 0xFF)
                    }
                }
                out.write(payload)
                out.flush()
            }
        } catch (e: Exception) {
            // 发送失败交给读循环收尾（下次读会抛并清理会话）
            Log.i(TAG, "Web WS 发送失败: ${e.message}")
        }
    }

    private fun sendText(session: Session, text: String) =
        sendFrame(session, OP_TEXT, text.toByteArray(Charsets.UTF_8))

    // ----------------------------- 消息分发（命令 → 响应；错误统一转 ok:false） -----------------------------

    private fun handleTextMessage(session: Session, msg: String) {
        val obj = try { JSONObject(msg) } catch (e: Exception) {
            sendText(session, JSONObject().put("ok", false).put("error", "非法 JSON").toString())
            return
        }
        val seq = obj.optLong("seq", -1)
        val data = try {
            dispatch(session, obj.optString("cmd"), obj.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            Diag.warn("[web] 命令失败 cmd=${obj.optString("cmd")}: ${e.message}")
            JSONObject().put("seq", seq).put("ok", false).put("error", e.message ?: e.toString())
                .also { return sendText(session, it.toString()) }
        }
        sendText(session, data.put("seq", seq).put("ok", true).toString())
    }

    /** 命令路由（internal：供仪器测试直接调用）；ctx 取 appContext（启动时已注入） */
    internal fun dispatch(session: Session, cmd: String, args: JSONObject): JSONObject {
        val ctx = appContext ?: throw IOException("WebServer 未启动")
        return when (cmd) {
            "shell.run" -> shellRun(ctx, args)
            "proc.list" -> procList()
            "proc.attach" -> procAttach(session, ctx, args)
            "proc.detach" -> procDetach(session, args)
            "proc.input" -> procInput(ctx, args)
            "proc.kill" -> procKill(ctx, args)
            "proc.signal" -> procSignal(ctx, args)
            "fs.list" -> fsList(ctx, args)
            "fs.read" -> fsRead(ctx, args)
            "fs.write" -> fsWrite(ctx, args)
            "fs.mkdir" -> fsMkdir(ctx, args)
            "fs.delete" -> fsDelete(ctx, args)
            "fs.rename" -> fsRename(ctx, args)
            "licenses.read" -> licensesRead(ctx, args)
            "ping" -> JSONObject().put("pong", System.currentTimeMillis())
            else -> throw IllegalArgumentException("未知命令: $cmd")
        }
    }

    // ----------------------------- 远程 shell / 进程管理 -----------------------------

    /** 远程 shell：以动态实例 dyn-sh-* 执行（RuntimeManager 负责日志/stdin/停止）。
     *  - 命令环境 = NodeRuntime.baseEnv（LD_LIBRARY_PATH/HOME/NODE_PATH/npm 重定向）+ PATH，
     *    因此 sh 里可以直接调 node；npm/npx 改写为内置 npm CLI（APK 不自带 npm 可执行文件）。
     *  - 首 token 为 node 的命令走 config.cmd 直通 NodeRuntime 解析（直接 exec libnode.so，
     *    信号直达 node，不被 sh 吞——见文档踩坑 3）。 */
    private fun shellRun(ctx: Context, args: JSONObject): JSONObject {
        val raw = args.optString("cmd").trim()
        if (raw.isEmpty()) throw IllegalArgumentException("缺少 cmd")
        val cmd = rewriteShellCommand(ctx, raw)
        val cwd = resolveCwd(ctx, args.optString("cwd").trim())
        val id = RuntimeManager.DYNAMIC_PREFIX + "sh-" +
            System.currentTimeMillis().toString(36) + "-" +
            (100 + (Math.random() * 900)).toInt()
        val env = NodeRuntime.baseEnv(ctx).toMutableMap().apply {
            // 系统命令 + 原生库目录（libnode.so 所在，虽然 node 已被改写为绝对路径，留着无害）
            put("PATH", "/system/bin:/system/xbin:" + ctx.applicationInfo.nativeLibraryDir)
        }
        val cfg = NodeRuntime.Config(
            id = id,
            name = "web-shell",
            script = "",
            cmd = cmd,
            env = env,
            cwd = cwd,
        )
        if (!RuntimeManager.startWithConfig(ctx, cfg)) throw IOException("进程启动失败")
        // pump 线程 spawn 有极短延迟：稍等再取 pid（最多 ~200ms）
        var pid: Int? = null
        var waited = 0
        while (waited < 10 && pid == null) { pid = RuntimeManager.pidOf(id); if (pid == null) { Thread.sleep(20); waited++ } }
        return JSONObject().put("id", id).put("pid", pid ?: JSONObject.NULL)
    }

    /**
     * 终端工作目录解析（安全边界）：空串/"." = filesDir（= HOME）；
     * 其余按相对 files/ 经 resolveSandboxPath 解析（越出沙箱抛 SecurityException），且必须已存在为目录。
     */
    private fun resolveCwd(ctx: Context, cwdRel: String): File {
        if (cwdRel.isEmpty() || cwdRel == ".") return ctx.filesDir.canonicalFile
        val dir = resolveSandboxPath(ctx, cwdRel)
        if (!dir.isDirectory) throw IllegalArgumentException("工作目录不存在: files/$cwdRel")
        return dir
    }

    /**
     * shell 命令改写（纯函数，可 JVM 单测）：
     *  - npm / npx 开头 → 换成「内置 node 绝对路径 + files/lib/node_modules/npm/bin/npm-cli.js」
     *    （termux deb 不含 npm 可执行文件，npm 是纯 JS CLI，由 tool_6 装入沙箱）；
     *  - 其余命令原样（node 开头由 NodeRuntime.commandArgv 直接 exec，不经 sh）。
     */
    internal fun rewriteShellCommand(ctx: Context, cmd: String): String {
        val npmCli = File(File(ctx.filesDir, "lib/node_modules/npm"), "bin/npm-cli.js")
        val tokens = NodeRuntime.tokenizeCmd(cmd.replace(Regex("\\s+"), " ").trim())
        val head = tokens.firstOrNull() ?: return cmd
        if (head != "npm" && head != "npx") return cmd
        if (!npmCli.isFile) throw IllegalArgumentException(
            "容器内未安装 npm（先跑 tools/install-npm.cjs）: ${npmCli.absolutePath}")
        val rest = if (head == "npm") cmd.removePrefix("npm").trim() else cmd.removePrefix("npx").trim()
        return "\"${NodeRuntime.nodeBinary(ctx).absolutePath}\" \"${npmCli.absolutePath}\" $rest"
    }

    private fun procList(): JSONObject {
        val arr = JSONArray()
        for (info in RuntimeManager.allInfos()) {
            arr.put(JSONObject()
                .put("id", info.id).put("name", info.name)
                .put("cmd", info.script.ifEmpty { info.args.joinToString(" ") })
                .put("running", info.running).put("startedAt", info.startedAt)
                .put("lastExitCode", info.lastExitCode ?: JSONObject.NULL)
                .put("dynamic", info.dynamic)
                .put("pid", RuntimeManager.pidOf(info.id) ?: JSONObject.NULL))
        }
        return JSONObject().put("instances", arr)
    }

    /** 订阅：同一条连接可同时盯多个实例（常驻终端/观察终端各自订阅互不干扰）。
     *  竞态补偿：瞬间退出的命令,其 exit 事件可能在客户端订阅登记前就广播完（且非 restart
     *  实例退出后会立即从 RuntimeManager 移除）→ 前端既等不到事件、也查不到活实例。
     *  因此退出判定回退到日志尾部的退出标记（日志文件持久,每个 id 一份）,并照常回放历史行,
     *  保证「不报错 / 无输出 / 卡住后续命令」不再发生。 */
    private fun procAttach(session: Session, ctx: Context, args: JSONObject): JSONObject {
        val ids = argIds(args).ifEmpty { throw IllegalArgumentException("缺少 id/ids") }
        val tail = args.optInt("tail", 200).coerceIn(0, 2000)
        val targets = JSONArray()
        for (id in ids) {
            session.subscribed.add(id)
            val logLines = RuntimeManager.tailLog(ctx, id, tail.coerceAtLeast(1))
            val lines = JSONArray()
            logLines?.forEach { lines.put(it) }
            val target = JSONObject().put("id", id).put("lines", lines)

            // 退出判定三态：活实例且 process==null → 刚退出；活实例且进程在 → 未退出（交实时事件）；
            // 实例已移除 → 回退日志尾部退出标记
            val inst = RuntimeManager.instance(id)
            var exited = false
            var code: Int? = null
            when {
                inst != null -> if (inst.process == null) { exited = true; code = inst.lastExitCode }
                else -> {
                    val marker = logLines?.lastOrNull { EXIT_MARKER_REGEX.containsMatchIn(it) }
                    if (marker != null) { exited = true; code = EXIT_MARKER_REGEX.find(marker)?.groupValues?.get(1)?.toIntOrNull() }
                }
            }
            if (exited) target.put("exited", true).put("exitCode", code ?: -1)
            targets.put(target)
        }
        return JSONObject().put("targets", targets)
    }

    private fun procDetach(session: Session, args: JSONObject): JSONObject {
        val ids = argIds(args)
        if (ids.isEmpty()) session.subscribed.clear() else ids.forEach { session.subscribed.remove(it) }
        return JSONObject().put("ok", true)
    }

    /** 兼容单值 id 与 ids 数组两种写法 */
    private fun argIds(args: JSONObject): List<String> {
        val out = mutableListOf<String>()
        args.optJSONArray("ids")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        args.optString("id").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out.distinct()
    }

    private fun procInput(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val text = args.optString("text")
        if (text.isEmpty()) throw IllegalArgumentException("缺少 text")
        val newline = args.optBoolean("newline", true)
        if (!RuntimeManager.writeStdin(id, if (newline) "$text\n" else text))
            throw IOException("实例未在运行或 stdin 已关闭: $id")
        return JSONObject().put("written", true)
    }

    private fun procKill(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        if (!RuntimeManager.isRunning(id)) throw IllegalArgumentException("实例未在运行: $id")
        RuntimeManager.stop(ctx, id)
        return JSONObject().put("stopped", id)
    }

    private fun procSignal(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        if (!RuntimeManager.sendInterrupt(id)) throw IllegalArgumentException("实例未在运行: $id")
        return JSONObject().put("signalled", id)
    }

    /** 进程输出行 → 广播给订阅了该实例的连接；退出行转 exit 事件 */
    private fun onProcLine(id: String, line: String) {
        if (sessions.isEmpty()) return
        if (line == "__EXIT__ 0" || line.startsWith("__EXIT__ ")) {
            val ev = JSONObject().put("event", "exit").put("id", id)
                .put("code", line.removePrefix("__EXIT__ ").trim().toIntOrNull() ?: -1)
            broadcast(id, ev)
            return
        }
        broadcast(id, JSONObject().put("event", "out").put("id", id).put("line", line))
    }

    private fun broadcast(id: String, event: JSONObject) {
        val text = event.toString()
        for (s in sessions) {
            if (id in s.subscribed) sendText(s, text)
        }
    }

    // ----------------------------- 文件管理（限定 app 沙箱内） -----------------------------

    /** app 沙箱根：filesDir 去掉尾部 /files（即 /data/data/<pkg>，兼容 /data/user/0 软链） */
    private fun sandboxRoot(ctx: Context): File {
        val f = ctx.filesDir.canonicalFile
        return if (f.name == "files") f.parentFile!! else f
    }

    /**
     * 路径解析（安全边界）：空串/"." = 沙箱根；相对路径基于 filesDir；绝对路径限 app 沙箱内，
     * 越出抛 SecurityException。nativeLibraryDir 在沙箱外，默认不可见（需浏览时走沙箱内软链）。
     */
    internal fun resolveSandboxPath(ctx: Context, relPath: String): File {
        val root = sandboxRoot(ctx)
        val p = relPath.trim()
        val f = when {
            p.isEmpty() || p == "." -> root
            p.startsWith("/") -> File(p)
            else -> File(root, "files/$p")
        }
        val canon = f.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) {
            throw SecurityException("路径越出 app 沙箱: $relPath")
        }
        return canon
    }

    /** 写保护检查：沙箱根与 files/cache 本身禁止删除 */
    private fun checkWritable(ctx: Context, f: File, op: String) {
        val root = sandboxRoot(ctx)
        if (f == root) throw SecurityException("禁止${op}沙箱根目录")
        if (f == ctx.filesDir.canonicalFile) throw SecurityException("禁止${op}files 根目录")
        if (f == ctx.cacheDir.canonicalFile) throw SecurityException("禁止${op}cache 根目录")
    }

    /** 展示用路径：filesDir 显空串，其子目录显相对路径，沙箱内其他位置显绝对路径 */
    private fun displayPath(ctx: Context, f: File): String {
        val filesDir = ctx.filesDir.canonicalFile
        return when {
            f.path == filesDir.path -> ""
            f.path.startsWith(filesDir.path + "/") -> f.path.removePrefix(filesDir.path + "/")
            else -> f.path
        }
    }

    private fun fsList(ctx: Context, args: JSONObject): JSONObject {
        val rel = args.optString("path").trim()
        val dir = resolveSandboxPath(ctx, rel)
        if (!dir.isDirectory) throw IllegalArgumentException("目录不存在: ${args.optString("path")}")
        val entries = JSONArray()
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
            entries.put(JSONObject()
                .put("name", it.name).put("dir", it.isDirectory)
                .put("size", it.length()).put("mtime", it.lastModified()))
        }
        return JSONObject().put("path", displayPath(ctx, dir)).put("entries", entries)
    }

    private fun fsRead(ctx: Context, args: JSONObject): JSONObject {
        val f = resolveSandboxPath(ctx, args.optString("path"))
        if (!f.isFile) throw IllegalArgumentException("文件不存在: ${args.optString("path")}")
        if (f.length() > MAX_FS_TEXT_BYTES) throw IllegalArgumentException("文件过大(${f.length()} B > $MAX_FS_TEXT_BYTES)")
        return JSONObject().put("path", args.optString("path")).put("content", f.readText())
    }

    private fun fsWrite(ctx: Context, args: JSONObject): JSONObject {
        val content = args.optString("content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FS_TEXT_BYTES) throw IllegalArgumentException("写入内容过大(${bytes.size} B)")
        val f = resolveSandboxPath(ctx, args.optString("path"))
        checkWritable(ctx, f.parentFile ?: f, "写入")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return JSONObject().put("bytes", bytes.size)
    }

    private fun fsMkdir(ctx: Context, args: JSONObject): JSONObject {
        val f = resolveSandboxPath(ctx, args.optString("path"))
        if (!f.mkdirs() && !f.isDirectory) throw IOException("创建目录失败: ${args.optString("path")}")
        return JSONObject().put("ok", true)
    }

    private fun fsDelete(ctx: Context, args: JSONObject): JSONObject {
        val f = resolveSandboxPath(ctx, args.optString("path"))
        checkWritable(ctx, f, "删除")
        if (f.isDirectory && f.list().orEmpty().isNotEmpty()) throw IllegalArgumentException("目录非空: ${args.optString("path")}")
        if (!f.delete()) throw IOException("删除失败: ${args.optString("path")}")
        return JSONObject().put("ok", true)
    }

    private fun fsRename(ctx: Context, args: JSONObject): JSONObject {
        val from = resolveSandboxPath(ctx, args.optString("from"))
        val to = resolveSandboxPath(ctx, args.optString("to"))
        checkWritable(ctx, from, "移走")
        if (!from.exists()) throw IllegalArgumentException("源不存在: ${args.optString("from")}")
        if (to.exists()) throw IllegalArgumentException("目标已存在: ${args.optString("to")}")
        if (!from.renameTo(to)) throw IOException("重命名失败")
        return JSONObject().put("ok", true)
    }

    // ----------------------------- 开源许可（只读 assets，不走沙箱路径解析） -----------------------------

    /** licenses.read {name}：name = notices.txt | node-LICENSE.txt */
    private fun licensesRead(ctx: Context, args: JSONObject): JSONObject {
        val name = args.optString("name").trim().ifEmpty { "notices.txt" }
        val title = LICENSE_ITEMS.firstOrNull { it.first == name }?.second
            ?: throw IllegalArgumentException("未知许可条目: $name（可选 ${LICENSE_ITEMS.joinToString(" / ") { it.first }}）")
        return JSONObject().put("name", name).put("title", title).put("content", licenseText(ctx, name))
    }
}
