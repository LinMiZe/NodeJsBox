package com.nodejsbox.container.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nodejsbox.container.FilePickerActivity
import com.nodejsbox.container.core.NodeRuntime
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 文件选择操作的「桥 ↔ Activity」交接站。
 *
 * Bridge 线程：pendingFor() 登记操作 → FilePickerActivity 启动 → await() 阻塞等结果；
 * FilePickerActivity（用户在系统文件管理器里操作）: complete()/cancel() 回填结果。
 * 同一时刻只允许一个未完成操作（个人工具够用，避免并发 SAF 弹窗混乱）。
 */
object FileBridge {

    const val MODE_OPEN = "open"  // 选择文件 → 拷入 filesDir/imports/
    const val MODE_SAVE = "save"  // 导出 filesDir 内文件 → 用户选择位置保存

    /** 等待用户操作的最长时间（超时返回错误，脚本可重试） */
    private const val TIMEOUT_SECONDS = 300L

    class Pending(
        val mode: String,
        val mime: String,
        val suggestedName: String,
        val sourceFile: File?,   // save 模式必填
    ) {
        val latch = CountDownLatch(1)
        @Volatile var result: PendingResult? = null
    }

    data class PendingResult(val uri: Uri?, val displayName: String?, val size: Long?)

    @Volatile private var pending: Pending? = null

    /** 登记一个待处理操作；已有未完成操作时抛错 */
    @Synchronized
    fun begin(mode: String, mime: String, suggestedName: String, sourceFile: File?): Pending {
        pending?.let {
            if (it.latch.count > 0) throw BridgeException("已有未完成的文件选择操作，请先在手机上完成")
        }
        val p = Pending(mode, mime, suggestedName, sourceFile)
        pending = p
        return p
    }

    /** FilePickerActivity 获取当前待处理操作（无或已完成返回 null） */
    @Synchronized
    fun currentPending(): Pending? = pending?.takeIf { it.latch.count > 0 }

    /** FilePickerActivity 回填成功结果 */
    fun complete(p: Pending, uri: Uri, displayName: String?, size: Long?) {
        p.result = PendingResult(uri, displayName, size)
        p.latch.countDown()
    }

    /** FilePickerActivity 回填取消 */
    fun cancel(p: Pending) {
        p.result = null
        p.latch.countDown()
    }

    /** Bridge 线程等待结果；null = 取消/超时 */
    fun await(p: Pending): PendingResult? {
        p.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return p.result
    }
}

/**
 * fs.* 命令：调起系统文件管理器（SAF）选文件/导出文件。
 *
 *   fs.pick   {mime?}                → {path,name,size}
 *      用户在系统文件管理器选一个文件，拷入 filesDir/imports/ 后返回沙箱内路径。
 *      mime 缺省为任意类型。
 *   fs.export {path, suggestedName?} → {name,size}
 *      把 filesDir 内（相对或绝对）文件交给用户选择位置导出保存。
 *   fs.listFiles {dir?}              → {files:[{name,size,dir}]}
 *      列出 filesDir 下某子目录的文件（仅一层，供脚本做脚本管理类工具）。
 *      dir 缺省为 "scripts"。
 *
 * fs.pick/fs.export 会阻塞直到用户完成操作（超时 300s）。
 */
object FileCommands {

    init {
        BridgeDispatcher.register("fs.pick", BridgeCommand { ctx, args -> pick(ctx, args) })
        BridgeDispatcher.register("fs.export", BridgeCommand { ctx, args -> export(ctx, args) })
        BridgeDispatcher.register("fs.listFiles", BridgeCommand { ctx, args -> listFiles(ctx, args) })
    }

    private fun pick(ctx: Context, args: JSONObject): JSONObject {
        val mime = args.optString("mime", "*/*").ifBlank { "*/*" }
        val p = FileBridge.begin(FileBridge.MODE_OPEN, mime, "", null)
        launchPicker(ctx, p)
        val r = FileBridge.await(p)
            ?: throw BridgeException("用户取消或操作超时")
        // 拷入 imports
        val importsDir = File(ctx.filesDir, "imports").apply { mkdirs() }
        val displayName = r.displayName ?: "import_${System.currentTimeMillis()}"
        val dest = uniqueDest(File(importsDir, displayName))
        try {
            ctx.contentResolver.openInputStream(r.uri!!)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            } ?: throw BridgeException("无法打开所选文件")
        } catch (e: BridgeException) {
            throw e
        } catch (e: Exception) {
            throw BridgeException("拷贝所选文件失败: ${e.message}")
        }
        return JSONObject()
            .put("path", dest.absolutePath)
            .put("name", dest.name)
            .put("size", dest.length())
    }

    private fun export(ctx: Context, args: JSONObject): JSONObject {
        val relPath = args.optString("path").trim()
        if (relPath.isEmpty()) throw BridgeException("缺少 path")
        val src = NodeRuntime.resolveScript(ctx, relPath)
        if (!src.isFile) throw BridgeException("文件不存在: ${src.absolutePath}")
        val suggested = args.optString("suggestedName", src.name).ifBlank { src.name }

        val p = FileBridge.begin(FileBridge.MODE_SAVE, "*/*", suggested, src)
        launchPicker(ctx, p)
        val r = FileBridge.await(p)
            ?: throw BridgeException("用户取消或操作超时")
        var size = 0L
        try {
            ctx.contentResolver.openOutputStream(r.uri!!)?.use { output ->
                src.inputStream().use { input ->
                    input.copyTo(output)
                    size = src.length()
                }
            } ?: throw BridgeException("无法打开导出目标")
        } catch (e: BridgeException) {
            throw e
        } catch (e: Exception) {
            throw BridgeException("导出失败: ${e.message}")
        }
        return JSONObject().put("name", r.displayName ?: suggested).put("size", size)
    }

    private fun listFiles(ctx: Context, args: JSONObject): JSONObject {
        val dirName = args.optString("dir", "scripts").trim().trim('/')
        // 只允许 filesDir 一层子目录，防越权浏览
        if (dirName.contains("..") || dirName.contains('/') || dirName.isEmpty()) {
            throw BridgeException("dir 仅支持 filesDir 下的一级子目录名")
        }
        val dir = File(ctx.filesDir, dirName)
        val arr = org.json.JSONArray()
        if (dir.isDirectory) {
            for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (f.isFile) arr.put(JSONObject().put("name", f.name).put("size", f.length()))
            }
        }
        return JSONObject().put("dir", dirName).put("files", arr)
    }

    // ----------------------------- 辅助 -----------------------------

    private fun launchPicker(ctx: Context, p: FileBridge.Pending) {
        val intent = Intent(ctx, FilePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FilePickerActivity.EXTRA_MODE, p.mode)
            putExtra(FilePickerActivity.EXTRA_MIME, p.mime)
            putExtra(FilePickerActivity.EXTRA_SUGGESTED_NAME, p.suggestedName)
        }
        ctx.startActivity(intent)
    }

    private fun uniqueDest(dest: File): File {
        if (!dest.exists()) return dest
        val base = dest.nameWithoutExtension
        val ext = dest.extension
        for (i in 1 until 1000) {
            val candidate = File(dest.parentFile, "$base($i).${ext}")
            if (!candidate.exists()) return candidate
        }
        throw BridgeException("imports 目录文件过多")
    }
}

/**
 * app.* 命令：容器自身信息 + 简单系统能力。
 *
 *   app.info   {}              → {version,filesDir,nodeBin,pid,scripts:[...]}
 *   app.toast  {message}       → {shown:true}
 */
object AppCommands {

    init {
        BridgeDispatcher.register("app.info", BridgeCommand { ctx, _ -> info(ctx) })
        BridgeDispatcher.register("app.toast", BridgeCommand { ctx, args -> toast(ctx, args) })
    }

    private fun info(ctx: Context): JSONObject {
        val scriptsDir = File(ctx.filesDir, "scripts")
        val scripts = org.json.JSONArray()
        for (f in scriptsDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (f.isFile && f.extension == "js") scripts.put(f.name)
        }
        return JSONObject()
            .put("nodeBin", NodeRuntime.nodeBinary(ctx).absolutePath)
            .put("filesDir", ctx.filesDir.absolutePath)
            .put("scripts", scripts)
            .put("bridgePort", com.nodejsbox.container.core.BridgeServer.port)
    }

    private fun toast(ctx: Context, args: JSONObject): JSONObject {
        val msg = args.optString("message").trim()
        if (msg.isEmpty()) throw BridgeException("缺少 message")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
        return JSONObject().put("shown", true)
    }
}
