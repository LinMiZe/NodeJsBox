package com.nodejsbox.container.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：files/diag.log，墙钟时间戳，记录核心事件时序（启停/桥命令/Web 服务），
 * 供离线分析「H5 显示 vs 实际进程状态」不一致类问题（无 adb 时尤其有用）。
 *
 * 独立于实例日志（logs/<id>.log）；超过 2MB 清空重来。
 *
 * 分级：
 *  - [log]   常规事件时序（文件标记 D，logcat INFO）   —— adb logcat -s NodeJsBox.Diag 可实时观察
 *  - [warn]  异常/意外事件（文件标记 W，logcat WARN） —— 凡被兜住但值得人工关注的失败必须走这里，
 *             离线分析时 `grep " W "` 可直接过滤出全部异常点
 */
object Diag {

    private const val LOGCAT_TAG = "NodeJsBox.Diag"
    private const val MAX_BYTES = 2_000_000L

    @Volatile private var file: File? = null
    private val lock = Any()

    fun init(ctx: Context) {
        if (file == null) {
            synchronized(lock) {
                if (file == null) file = File(ctx.applicationContext.filesDir, "diag.log")
            }
        }
    }

    /** 常规事件 */
    fun log(msg: String) = write(msg, 'D') { tag, m -> android.util.Log.i(tag, m) }

    /** 异常/意外事件（被兜住的失败、强杀、降级路径等） */
    fun warn(msg: String) = write(msg, 'W') { tag, m -> android.util.Log.w(tag, m) }

    /** logcat 先写：文件写入失败（磁盘满等）不能连带丢掉 logcat 侧的诊断 */
    private inline fun write(msg: String, fileTag: Char, logcat: (String, String) -> Unit) {
        logcat(LOGCAT_TAG, msg)
        val f = file ?: return
        try {
            synchronized(lock) {
                if (f.length() > MAX_BYTES) f.writeText("")
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                f.appendText("$ts $fileTag $msg\n")
            }
        } catch (e: Exception) {
            android.util.Log.w(LOGCAT_TAG, "diag 文件写入失败: ${e.message}")
        }
    }
}
