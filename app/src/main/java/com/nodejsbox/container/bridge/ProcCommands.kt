package com.nodejsbox.container.bridge

import android.content.Context
import com.nodejsbox.container.core.NodeRuntime
import com.nodejsbox.container.core.RuntimeManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * proc.* 命令：node 脚本对容器内进程的增删查 + stdin 注入 + 日志读取。
 *
 *   proc.list   {}                       → { instances: [info...] }
 *   proc.start  {name?,script,args?,env?,restart?} → info（id 自动生成 dyn-xxxx）
 *   proc.stop   {id}                     → {stopped:true}（SIGTERM，同 UI 停止）
 *   proc.info   {id}                     → info | null
 *   proc.log    {id, tail?=200}          → {lines:[...]}
 *   proc.write  {id, text, newline?=true}→ {written:true}（stdin 注入）
 *
 * 动态实例 id 以 "dyn-" 开头：不持久化、容器重启不恢复。
 */
object ProcCommands {

    init {
        BridgeDispatcher.register("proc.list", BridgeCommand { ctx, _ -> list(ctx) })
        BridgeDispatcher.register("proc.start", BridgeCommand { ctx, args -> start(ctx, args) })
        BridgeDispatcher.register("proc.stop", BridgeCommand { ctx, args -> stop(ctx, args) })
        BridgeDispatcher.register("proc.info", BridgeCommand { _, args -> info(args) })
        BridgeDispatcher.register("proc.log", BridgeCommand { ctx, args -> log(ctx, args) })
        BridgeDispatcher.register("proc.write", BridgeCommand { _, args -> write(args) })
    }

    // ----------------------------- 实现 -----------------------------

    private fun list(ctx: Context): JSONObject {
        val arr = JSONArray()
        for (info in RuntimeManager.allInfos()) arr.put(infoJson(info))
        return JSONObject().put("instances", arr)
    }

    private fun start(ctx: Context, args: JSONObject): JSONObject {
        val script = args.optString("script").trim()
        if (script.isEmpty()) throw BridgeException("缺少 script")
        val resolved = NodeRuntime.resolveScript(ctx, script)
        if (!resolved.isFile) throw BridgeException("脚本不存在: ${resolved.absolutePath}")

        val id = RuntimeManager.DYNAMIC_PREFIX + UUID.randomUUID().toString().takeLast(8)
        val env = args.optJSONObject("env")?.let { e ->
            e.keys().asSequence().associateWith { e.optString(it) }
        } ?: emptyMap()
        val cfg = NodeRuntime.Config(
            id = id,
            name = args.optString("name", id),
            script = script,
            args = args.optJSONArray("args")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
            env = env,
            restart = args.optBoolean("restart", false),
        )
        RuntimeManager.startWithConfig(ctx, cfg)
        val info = RuntimeManager.info(id) ?: throw BridgeException("实例启动后状态缺失")
        return infoJson(info)
    }

    private fun stop(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        if (!RuntimeManager.isRunning(id)) throw BridgeException("实例未在运行: $id")
        RuntimeManager.stop(ctx, id)
        return JSONObject().put("stopped", true).put("id", id)
    }

    private fun info(args: JSONObject): JSONObject? {
        val info = RuntimeManager.info(args.optString("id").trim()) ?: return null
        return infoJson(info)
    }

    private fun log(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val tail = args.optInt("tail", 200).coerceIn(1, 5000)
        val lines = RuntimeManager.tailLog(ctx, id, tail)
            ?: throw BridgeException("暂无日志: $id")
        val arr = JSONArray()
        for (l in lines) arr.put(l)
        return JSONObject().put("id", id).put("lines", arr)
    }

    private fun write(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val text = args.optString("text")
        if (text.isEmpty()) throw BridgeException("缺少 text")
        val newline = args.optBoolean("newline", true)
        val written = RuntimeManager.writeStdin(id, if (newline) "$text\n" else text)
        if (!written) throw BridgeException("实例未在运行或 stdin 已关闭: $id")
        return JSONObject().put("written", true).put("id", id)
    }

    // ----------------------------- 序列化 -----------------------------

    /** 实例信息 → JSON（UI / 各命令域共用同构形状） */
    internal fun infoJson(info: RuntimeManager.InstanceInfo): JSONObject {
        val o = JSONObject()
        o.put("id", info.id)
        o.put("name", info.name)
        o.put("script", info.script)
        o.put("args", JSONArray(info.args))
        o.put("running", info.running)
        o.put("startedAt", info.startedAt)
        o.put("restarts", info.restarts)
        o.put("lastExitCode", info.lastExitCode ?: JSONObject.NULL)
        o.put("dynamic", info.dynamic)
        return o
    }
}
