package com.nodejsbox.container.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 容器启动初始化：把 APK 内置 assets 解包进沙箱（幂等，MainActivity.onCreate 调一次）。
 *
 *  - scripts/  内置脚本：整目录递归解包，仅缺失时写入（用户可改，改过不覆盖；0 字节视为未解包自愈）；
 *  - modules/  桥接模块 nodejsbox.js：整目录每次升级覆盖（脚本侧 require('nodejsbox') 依赖它）。
 *
 * 新增内置脚本只需放进 assets/scripts/ 即自动解包，无需改代码；
 * H5 前端「文件查看器」直接浏览解包后的 files/scripts/ 选脚本运行。
 */
object ContainerBootstrap {

    private const val TAG = NodeRuntime.TAG

    @Volatile private var done = false

    @Synchronized
    fun init(ctx: Context) {
        if (done) return
        val app = ctx.applicationContext
        unpackTree(app, "scripts", overwrite = false)
        unpackTree(app, "modules", overwrite = true)
        done = true
    }

    /** 递归解包 assets 下整个目录；目录缺席只告警不抛（assets 与 filesDir 路径同名） */
    private fun unpackTree(ctx: Context, assetDir: String, overwrite: Boolean) {
        val entries = try {
            ctx.assets.list(assetDir) ?: emptyArray()
        } catch (e: Exception) {
            Diag.warn("[bootstrap] 列出 assets/$assetDir 失败: ${e.message}")
            return
        }
        if (entries.isEmpty()) {
            Diag.warn("[bootstrap] assets/$assetDir 为空，跳过")
            return
        }
        for (name in entries) {
            val path = "$assetDir/$name"
            // 无子扩展名的再试 list：能列出即目录（如 assets 里再分子目录）
            val sub = try { ctx.assets.list(path)?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            if (sub != null) unpackTree(ctx, path, overwrite) else unpackAsset(ctx, path, overwrite)
        }
    }

    private fun unpackAsset(ctx: Context, asset: String, overwrite: Boolean) {
        val dest = File(ctx.filesDir, asset)
        // 0 字节视为未解包（上次写入可能中途损坏/被截断），重新解包自愈
        if (dest.isFile && dest.length() > 0 && !overwrite) return
        dest.parentFile?.mkdirs()
        try {
            ctx.assets.open(asset).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "解包内置资源: $asset")
        } catch (e: Exception) {
            Log.e(TAG, "解包失败 $asset: ${e.message}")
            Diag.warn("[bootstrap] 解包失败 $asset: ${e.message}")
        }
    }
}
