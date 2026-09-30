package com.nodejsbox.container.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 运行时实例管理器（多实例）。
 *
 *  - 每个实例 id 唯一，同时最多一个实例；多条可并发运行（多开）。
 *  - 每个实例独立：进程 / 日志文件 filesDir/logs/<id>.log（512KB 轮转 .old）/
 *    logcat 输出（格式 "[NB:<id>] <line>"）。
 *  - restart=true 的实例进程退出后自动重启：指数退避 1s→2s→…→60s 封顶；
 *    稳定运行满 60s 后退避计数重置。手动停止不重启。
 *
 *  实例全部为动态实例（id 以 DYNAMIC_PREFIX 开头）：来自 H5 终端 shell.run、`--es run`、
 *  桥 proc.start。不持久化、不跨重启恢复，由前端/脚本自行重新拉起。
 */
object RuntimeManager {

    private const val TAG = NodeRuntime.TAG
    private const val MAX_LOG_BYTES = 512L * 1024
    private const val STABLE_RUN_MS = 60_000L

    /** 动态实例（Bridge 拉起）id 前缀 */
    const val DYNAMIC_PREFIX = "dyn-"

    private val instances = java.util.Collections.synchronizedMap(HashMap<String, Instance>())

    class Instance(val config: NodeRuntime.Config, val logFile: File) {
        @Volatile var process: Process? = null
        @Volatile var manualStop = false
        @Volatile var restarts = 0
        @Volatile var lastExitCode: Int? = null
        /** 本实例最近一次进程的启动时间戳（0 表示尚未启动过） */
        @Volatile var startedAt: Long = 0L
    }

    // ----------------------------- 状态查询 -----------------------------

    fun runningIds(): List<String> = synchronized(instances) { instances.keys.sorted() }
    fun isRunning(id: String): Boolean = instances.containsKey(id)
    fun restartCount(id: String): Int = instances[id]?.restarts ?: 0
    fun instance(id: String): Instance? = instances[id]

    /**
     * 实例集合变化监听：启动 / spawn 成功 / 退出 / 移除时回调
     * （pump 线程或调用线程，订阅方自行切主线程）。唯一订阅方是 ContainerService（前台通知刷新）；
     * WebServer 的逐行流式推送走 [LineListener]，两者互不依赖。
     */
    fun interface StateListener { fun onStateChanged() }

    private val stateListeners = java.util.concurrent.CopyOnWriteArraySet<StateListener>()

    fun addStateListener(l: StateListener) = stateListeners.add(l)
    fun removeStateListener(l: StateListener) = stateListeners.remove(l)

    private fun notifyStateChanged() {
        for (l in stateListeners) {
            try { l.onStateChanged() } catch (e: Exception) {
                Diag.warn("[state] 监听器回调异常: ${e.message}")
            }
        }
    }

    /** 实例快照（供 Bridge proc.list / UI 使用） */
    data class InstanceInfo(
        val id: String,
        val name: String,
        val script: String,
        val args: List<String>,
        val running: Boolean,
        val startedAt: Long,
        val restarts: Int,
        val lastExitCode: Int?,
        val dynamic: Boolean,
    )

    fun info(id: String): InstanceInfo? {
        val inst = instances[id] ?: return null
        val p = inst.process
        return InstanceInfo(
            id = inst.config.id,
            name = inst.config.name,
            script = inst.config.script,
            args = inst.config.args,
            running = p != null,
            startedAt = inst.startedAt,
            restarts = inst.restarts,
            lastExitCode = inst.lastExitCode,
            dynamic = id.startsWith(DYNAMIC_PREFIX),
        )
    }

    fun allInfos(): List<InstanceInfo> =
        synchronized(instances) { instances.keys.sorted().mapNotNull { info(it) } }

    // ----------------------------- 启停 -----------------------------

    /**
     * 按脚本相对路径启动动态实例（`--es run` / H5 运行脚本入口）。
     * id 固定为 dyn-script-<去扩展名文件名>，同脚本重复调用幂等（已在跑则不重拉）。
     * @return 实例 id
     */
    fun startScript(context: Context, relPath: String): String {
        val fileName = relPath.substringAfterLast('/')
        val id = DYNAMIC_PREFIX + "script-" + fileName.removeSuffix(".js")
        startWithConfig(context, NodeRuntime.Config(id = id, name = fileName, script = relPath))
        return id
    }

    /**
     * 按给定配置启动实例（唯一启动入口：H5 / 桥 / startScript 都走这里）。
     * id 必须以 DYNAMIC_PREFIX 开头；同名实例已在运行则直接返回成功（幂等）。
     */
    fun startWithConfig(context: Context, cfg: NodeRuntime.Config): Boolean {
        val ctx = context.applicationContext
        Diag.init(ctx)
        require(cfg.id.startsWith(DYNAMIC_PREFIX)) {
            "实例 id 必须以 $DYNAMIC_PREFIX 开头（只支持动态实例）：${cfg.id}"
        }
        if (instances.containsKey(cfg.id)) {
            Log.w(TAG, "实例 ${cfg.id} 已在运行，忽略重复启动")
            Diag.log("[start] 忽略重复启动 id=${cfg.id}")
            return true
        }
        Diag.log("[start] id=${cfg.id} script=${cfg.script} args=${cfg.args} cmd=${cfg.cmd ?: "-"} restart=${cfg.restart} name=${cfg.name}")
        val logsDir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val inst = Instance(cfg, File(logsDir, "${cfg.id}.log"))
        instances[cfg.id] = inst
        val what = cfg.cmd?.trim()?.takeIf { it.isNotEmpty() } ?: cfg.script
        appendLog(inst, "---- 启动 $what ${cfg.args.joinToString(" ")} ----")
        Log.i(TAG, "[NB:${cfg.id}] 启动 (script=${cfg.script} restart=${cfg.restart})")
        notifyStateChanged()
        Thread { pump(ctx, inst) }.start()
        return true
    }

    fun stop(context: Context, id: String) {
        val inst = instances[id] ?: return
        inst.manualStop = true
        appendLog(inst, "---- 停止请求 ----")
        Log.i(TAG, "[NB:$id] 停止请求")
        Diag.log("[stop] 停止请求 id=$id")
        val p = inst.process
        if (p != null) {
            // 显式发 SIGTERM(15)。不能用 p.destroy()：Android libcore 的 destroy() 发的是
            // SIGNAL_QUIT(3)，脚本注册的 SIGTERM handler 不会执行（优雅退出日志"丢失"的元凶）
            val pid = childPid(p)
            if (pid != null && pid > 0) {
                android.os.Process.sendSignal(pid, 15 /* SIGTERM */)
                Diag.log("[stop] SIGTERM 已发送 pid=$pid id=$id")
            } else {
                p.destroy()
                Diag.log("[stop] pid 不可得，回退 destroy() id=$id")
            }
            Thread {
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline && p.isAlive) Thread.sleep(100)
                if (p.isAlive) {
                    Diag.warn("[stop] 3s 未退出，SIGKILL 强杀 id=$id")
                    p.destroyForcibly()
                }
            }.start()
        } else {
            Diag.log("[stop] 进程引用为空 id=$id")
        }
        // 实际移除与持久化由 pump 线程收尾
    }

    fun stopAll(context: Context) {
        for (id in runningIds()) stop(context, id)
    }

    // ----------------------------- stdin -----------------------------

    /** 向实例进程 stdin 写入文本（追加换行由调用方决定；实例未运行返回 false） */
    fun writeStdin(id: String, text: String): Boolean {
        val p = instances[id]?.process ?: return false
        return try {
            p.outputStream.write(text.toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "[NB:$id] stdin 写入失败: ${e.message}")
            Diag.warn("[stdin] 写入失败 id=$id: ${e.message}")
            false
        }
    }

    /**
     * 向实例发送 Ctrl+C：stdin 注入 ETX(0x03) 字节，并尝试对进程发 SIGINT。
     * ETX 对读取原始字节的交互程序（REPL / readline）有效；SIGINT 是内核级中断，
     * 但 Android 的 java.lang.Process 不公开 pid（反射读取，失败则只注入 ETX）。
     * 实例未运行返回 false。
     */
    fun sendInterrupt(id: String): Boolean {
        val p = instances[id]?.process ?: run {
            Diag.log("[ctrlc] 实例未运行 id=$id")
            return false
        }
        writeStdin(id, "\u0003")
        Diag.log("[ctrlc] ETX 已注入 id=$id")
        val pid = childPid(p) ?: run {
            Diag.log("[ctrlc] 未能获取子进程 pid，仅注入 ETX id=$id")
            return true
        }
        return try {
            android.os.Process.sendSignal(pid, 2 /* SIGINT */)
            Diag.log("[ctrlc] SIGINT 已发送 pid=$pid id=$id")
            true
        } catch (e: Exception) {
            Diag.warn("[ctrlc] SIGINT 发送失败 pid=$pid id=$id: ${e.message}")
            Log.w(TAG, "[NB:$id] SIGINT 发送失败: ${e.message}")
            false
        }
    }

    /** 实例当前进程的 pid（未运行或拿不到返回 null；供 Web 后端上报） */
    fun pidOf(id: String): Int? = instances[id]?.process?.let { childPid(it) }

    /** 反射读取子进程 pid（沿继承链找 "pid" 字段）；拿不到返回 null */
    private fun childPid(p: Process): Int? {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("pid")
                f.isAccessible = true
                return f.getInt(p)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    // ----------------------------- 日志 -----------------------------

    /** 读取实例日志尾部（最多 maxLines 行；日志文件不存在返回 null） */
    fun tailLog(context: Context, id: String, maxLines: Int): List<String>? {
        val file = logFile(context, id)
        if (!file.isFile) return null
        val lines = file.readLines()
        return if (lines.size > maxLines) lines.takeLast(maxLines) else lines
    }

    /** 日志文件（App 侧 UI / Bridge 用；含已停止实例） */
    fun logFile(context: Context, id: String): File = File(File(context.filesDir, "logs"), "$id.log")

    private fun appendLog(inst: Instance, line: String) {
        try {
            val f = inst.logFile
            if (f.isFile && f.length() > MAX_LOG_BYTES) {
                val old = File(f.parentFile, f.name + ".old")
                old.delete()
                f.renameTo(old)
            }
            FileOutputStream(f, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            Log.w(TAG, "写日志失败(${inst.config.id}): ${e.message}")
        }
    }

    // ----------------------------- 输出行监听（附加式钩子，供 WebServer 流式推送） -----------------------------

    fun interface LineListener {
        /** 实例日志文件每追加一行回调一次（pump 线程上执行，不得阻塞） */
        fun onLine(id: String, line: String)
    }

    private val lineListeners = java.util.concurrent.CopyOnWriteArraySet<LineListener>()

    fun addLineListener(l: LineListener) = lineListeners.add(l)
    fun removeLineListener(l: LineListener) = lineListeners.remove(l)

    // ----------------------------- 实例泵线程 -----------------------------

    private fun pump(context: Context, inst: Instance) {
        val id = inst.config.id
        var attempt = 0
        while (instances[id] === inst) {
            val proc = try {
                NodeRuntime.spawn(context, inst.config)
            } catch (e: Exception) {
                appendLog(inst, "ERROR: 启动失败: ${e.message}")
                Log.e(TAG, "[NB:$id] 启动失败: ${e.message}")
                Diag.warn("[pump] spawn 失败 id=$id: ${e.message}")
                break
            }
            inst.process = proc
            inst.lastExitCode = null
            inst.startedAt = System.currentTimeMillis()
            Diag.log("[pump] spawn ok id=$id")
            notifyStateChanged()
            val runStart = System.currentTimeMillis()
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(inst, line)
                    Log.i(TAG, "[NB:$id] $line")
                    for (l in lineListeners) { try { l.onLine(id, line) } catch (e: Exception) {
                        Diag.warn("[listener] onLine 异常 id=$id: ${e.message}")
                    } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[NB:$id] 读取输出异常: ${e.message}")
                Diag.warn("[pump] 读流异常 id=$id: ${e.message}")
            }
            Diag.log("[pump] 读流结束 id=$id")
            val code = try { proc.waitFor() } catch (e: InterruptedException) { -999 }
            Diag.log("[pump] waitFor 返回 code=$code id=$id")
            inst.process = null
            inst.lastExitCode = code
            appendLog(inst, "---- 进程退出 code=$code ----")
            Log.i(TAG, "[NB:$id] 退出 code=$code")
            for (l in lineListeners) { try { l.onLine(id, "__EXIT__ $code") } catch (e: Exception) {
                Diag.warn("[listener] onLine 异常 id=$id: ${e.message}")
            } }
            notifyStateChanged()

            if (inst.manualStop || !inst.config.restart) break
            if (System.currentTimeMillis() - runStart >= STABLE_RUN_MS) attempt = 0
            val delay = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
            attempt++
            inst.restarts++
            appendLog(inst, "---- ${delay / 1000}s 后自动重启 (累计 ${inst.restarts} 次) ----")
            try { Thread.sleep(delay) } catch (e: InterruptedException) { break }
            if (inst.manualStop) break
        }
        if (instances[id] === inst) {
            instances.remove(id)
            Diag.log("[pump] 实例已移除 id=$id")
            notifyStateChanged()
        }
    }
}
