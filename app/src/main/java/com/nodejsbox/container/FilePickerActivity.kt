package com.nodejsbox.container

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nodejsbox.container.bridge.FileBridge

/**
 * 透明中转 Activity：只负责调起系统文件管理器（SAF）并把结果交给 FileBridge。
 *
 * 由 Bridge 的 fs.pick / fs.export 命令启动（NEW_TASK），用户操作完成或取消后
 * finish()。Bridge 线程在 FileBridge.await() 上阻塞等待，不做任何 UI 逻辑。
 */
class FilePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"            // FileBridge.MODE_OPEN / MODE_SAVE
        const val EXTRA_MIME = "mime"
        const val EXTRA_SUGGESTED_NAME = "suggestedName"
    }

    private var pending: FileBridge.Pending? = null

    private val openLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> onResult(uri) }

    private val createLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? -> onResult(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: FileBridge.MODE_OPEN
        val mime = intent?.getStringExtra(EXTRA_MIME) ?: "*/*"
        val suggested = intent?.getStringExtra(EXTRA_SUGGESTED_NAME) ?: "export.bin"

        // 恢复场景（旋转/重建）无法对应原 Bridge 等待者：直接取消
        val p = FileBridge.currentPending()
        pending = p
        if (p == null) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            FileBridge.cancel(p)
            finish()
            return
        }
        when (mode) {
            FileBridge.MODE_OPEN -> openLauncher.launch(arrayOf(mime))
            FileBridge.MODE_SAVE -> createLauncher.launch(suggested)
            else -> { FileBridge.cancel(p); finish() }
        }
    }

    private fun onResult(uri: Uri?) {
        val p = pending ?: run { finish(); return }
        if (uri == null) {
            FileBridge.cancel(p)
        } else {
            val name = queryDisplayName(uri)
            FileBridge.complete(p, uri, name, null)
        }
        finish()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 兜底：未回填即销毁（用户按返回等）——确保 Bridge 等待者被唤醒
        val p = pending ?: return
        if (p.latch.count > 0) FileBridge.cancel(p)
    }
}
