package com.nodejsbox.container

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 运行配置：filesDir/configs.json
 *
 * 每条配置 = 一个可独立启动的 node 运行时实例：
 *   id        唯一标识（同时作为实例名和日志文件名）
 *   name      显示名
 *   script    脚本路径（相对 filesDir 或绝对路径）
 *   args      传给 node 的命令行参数
 *   env       附加环境变量（覆盖基础 LD_LIBRARY_PATH/HOME/TMPDIR）
 *   restart   进程退出后是否自动重启（指数退避 1s→60s，稳定运行 60s 后计数重置）
 *   autostart 前台服务（重新）启动时是否自动拉起
 *
 * 同一脚本可定义多条配置实现多开（id 必须不同）。
 * 修改文件后在 App 内点「重载配置」生效，无需重装。
 */
object RunConfigs {

    data class Config(
        val id: String,
        val name: String,
        val script: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val restart: Boolean = false,
        val autostart: Boolean = false,
    )

    @Volatile
    private var cached: List<Config> = emptyList()

    fun configFile(ctx: Context): File = File(ctx.filesDir, "configs.json")

    fun get(ctx: Context): List<Config> {
        if (cached.isEmpty()) reload(ctx)
        return cached
    }

    @Synchronized
    fun reload(ctx: Context): List<Config> {
        val f = configFile(ctx)
        cached = if (f.isFile) {
            try {
                parse(f.readText())
            } catch (e: Exception) {
                Log.e(NodeRuntime.TAG, "configs.json 解析失败: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        return cached
    }

    /** 首次启动：生成默认配置 + 解包内置脚本到 filesDir/scripts/ */
    fun ensureDefaults(ctx: Context) {
        val f = configFile(ctx)
        if (!f.isFile) {
            f.writeText(DEFAULT_JSON)
            Log.i(NodeRuntime.TAG, "生成默认配置: ${f.absolutePath}")
        }
        for (asset in listOf("scripts/selftest.js", "scripts/hello.js")) {
            val dest = File(ctx.filesDir, asset)
            if (dest.isFile) continue
            dest.parentFile?.mkdirs()
            try {
                ctx.assets.open(asset).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                Log.i(NodeRuntime.TAG, "解包内置脚本: $asset")
            } catch (e: Exception) {
                Log.e(NodeRuntime.TAG, "解包脚本失败 $asset: ${e.message}")
            }
        }
        reload(ctx)
    }

    private fun parse(text: String): List<Config> {
        val arr = JSONObject(text).optJSONArray("configs") ?: JSONArray()
        val out = mutableListOf<Config>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                Config(
                    id = id,
                    name = o.optString("name", id),
                    script = o.optString("script"),
                    args = o.optJSONArray("args")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList(),
                    env = o.optJSONObject("env")?.let { e ->
                        e.keys().asSequence().associateWith { e.optString(it) }
                    } ?: emptyMap(),
                    restart = o.optBoolean("restart", false),
                    autostart = o.optBoolean("autostart", false),
                )
            )
        }
        return out
    }

    private const val DEFAULT_JSON = """{
  "configs": [
    { "id": "selftest", "name": "运行时自检", "script": "scripts/selftest.js", "args": [], "env": {}, "restart": false, "autostart": false },
    { "id": "hello", "name": "心跳演示 (常驻+自动重启)", "script": "scripts/hello.js", "args": [], "env": {}, "restart": true, "autostart": false }
  ]
}
"""
}
