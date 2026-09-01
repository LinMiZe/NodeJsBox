package com.nodejsbox.container

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 运行时实例管理器（多实例）。
 *
 *  - 每条运行配置同时最多一个实例；多条配置可并发运行（多开）。
 *  - 每个实例独立：进程 / 日志文件 filesDir/logs/<id>.log（512KB 轮转 .old）/
 *    logcat 输出（格式 "[NB:<id>] <line>"）。
 *  - restart=true 的实例进程退出后自动重启：指数退避 1s→2s→…→60s 封顶；
 *    稳定运行满 60s 后退避计数重置。手动停止不重启。
 *  - 正在运行的实例 id 集合持久化到 SharedPreferences，前台服务被系统
 *    sticky 重启后按 resume() 规则恢复（restart=true 的在跑实例 + autostart=true 的）。
 */
object RuntimeManager {

    private const val TAG = NodeRuntime.TAG
    private const val MAX_LOG_BYTES = 512L * 1024
    private const val STABLE_RUN_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val instances = java.util.Collections.synchronizedMap(HashMap<String, Instance>())

    class Instance(val config: RunConfigs.Config, val logFile: File) {
        @Volatile var process: Process? = null
        @Volatile var manualStop = false
        @Volatile var restarts = 0
        @Volatile var lastExitCode: Int? = null
    }

    // ----------------------------- 状态查询 -----------------------------

    fun runningIds(): List<String> = synchronized(instances) { instances.keys.sorted() }
    fun isRunning(id: String): Boolean = instances.containsKey(id)
    fun restartCount(id: String): Int = instances[id]?.restarts ?: 0

    // ----------------------------- 监听 -----------------------------

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChange() = mainHandler.post { listeners.forEach { it() } }

    // ----------------------------- 启停 -----------------------------

    fun start(context: Context, id: String): Boolean {
        val ctx = context.applicationContext
        val cfg = RunConfigs.get(ctx).firstOrNull { it.id == id } ?: run {
            Log.w(TAG, "启动失败: 未知配置 id=$id")
            return false
        }
        if (instances.containsKey(id)) {
            Log.w(TAG, "配置 $id 已在运行，忽略重复启动")
            return true
        }
        val logsDir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val inst = Instance(cfg, File(logsDir, "$id.log"))
        instances[id] = inst
        persistRunning(ctx)
        appendLog(inst, "---- 启动 ${cfg.script} ${cfg.args.joinToString(" ")} ----")
        Log.i(TAG, "[NB:$id] 启动 (script=${cfg.script} restart=${cfg.restart})")
        notifyChange()
        Thread { pump(ctx, inst) }.start()
        return true
    }

    fun stop(context: Context, id: String) {
        val inst = instances[id] ?: return
        inst.manualStop = true
        appendLog(inst, "---- 停止请求 ----")
        Log.i(TAG, "[NB:$id] 停止请求")
        val p = inst.process
        if (p != null) {
            p.destroy() // SIGTERM
            Thread {
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline && p.isAlive) Thread.sleep(100)
                if (p.isAlive) p.destroyForcibly()
            }.start()
        }
        // 实际移除与持久化由 pump 线程收尾
    }

    fun stopAll(context: Context) {
        for (id in runningIds()) stop(context, id)
    }

    /** 前台服务（重新）启动时调用：恢复需要保活的实例 */
    fun resume(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        val wasRunning = prefs.getStringSet("running", emptySet()) ?: emptySet()
        val toStart = RunConfigs.get(ctx)
            .filter { it.autostart || (it.id in wasRunning && it.restart) }
            .map { it.id }
        if (toStart.isNotEmpty()) {
            Log.i(TAG, "恢复运行: $toStart")
            toStart.forEach { start(ctx, it) }
        }
    }

    private fun persistRunning(ctx: Context) {
        ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .edit().putStringSet("running", runningIds().toSet()).apply()
    }

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
                break
            }
            inst.process = proc
            inst.lastExitCode = null
            notifyChange()
            val runStart = System.currentTimeMillis()
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(inst, line)
                    Log.i(TAG, "[NB:$id] $line")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[NB:$id] 读取输出异常: ${e.message}")
            }
            val code = try { proc.waitFor() } catch (e: InterruptedException) { -999 }
            inst.process = null
            inst.lastExitCode = code
            appendLog(inst, "---- 进程退出 code=$code ----")
            Log.i(TAG, "[NB:$id] 退出 code=$code")
            notifyChange()

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
            persistRunning(context)
            notifyChange()
        }
    }

    // ----------------------------- 日志 -----------------------------

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
}
