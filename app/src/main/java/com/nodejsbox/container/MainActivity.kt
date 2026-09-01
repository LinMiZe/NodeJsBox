package com.nodejsbox.container

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 容器主界面：运行配置列表 + 启动/停止/日志。
 *
 * 配置来自 filesDir/configs.json（可编辑，点「重载配置」热生效）；
 * adb 自动化入口：am start -n com.nodejsbox.container/.MainActivity --es start <id>
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var listContainer: LinearLayout

    private val onStateChange: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunConfigs.ensureDefaults(this)
        buildUi()
        maybeRequestNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("start")?.let { startViaService(it) }
        intent?.getStringExtra("stop")?.let { stopViaService(it) }
    }

    // ----------------------------- UI -----------------------------

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "NodeJsBox — Node.js 运行时容器"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "配置: filesDir/configs.json（改后点「重载配置」）\n脚本: filesDir/scripts/ · 日志: filesDir/logs/"
            textSize = 12f
            setPadding(0, 8, 0, 16)
        })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = "重载配置"
            setOnClickListener { RunConfigs.reload(this@MainActivity); render() }
        })
        bar.addView(Button(this).apply {
            text = "全部停止"
            setOnClickListener { stopViaService(null) }
        })
        root.addView(bar)

        tvSummary = TextView(this).apply {
            setPadding(0, 16, 0, 8)
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvSummary)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
    }

    private fun render() {
        val configs = RunConfigs.get(this)
        val running = RuntimeManager.runningIds().toSet()
        tvSummary.text = when {
            configs.isEmpty() -> "（无配置：configs.json 为空或解析失败）"
            running.isEmpty() -> "当前没有运行中的实例"
            else -> "运行中: ${running.joinToString(", ")}"
        }
        listContainer.removeAllViews()
        for (cfg in configs) {
            listContainer.addView(rowFor(cfg, cfg.id in running))
        }
    }

    private fun rowFor(cfg: RunConfigs.Config, isRunning: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(0x11000000.toInt())
        }

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(this).apply {
            text = cfg.name.ifBlank { cfg.id }
            setTypeface(null, Typeface.BOLD)
        })
        title.addView(TextView(this).apply {
            text = if (isRunning) "  ● 运行中" else "  ○ 已停止"
            setTextColor(if (isRunning) 0xFF0F766E.toInt() else 0xFF777777.toInt())
            textSize = 12f
        })
        box.addView(title)

        val flags = buildString {
            append(cfg.script)
            if (cfg.args.isNotEmpty()) append(' ').append(cfg.args.joinToString(" "))
            if (cfg.autostart) append("  [自启]")
            if (cfg.restart) append("  [自动重启]")
            val rc = RuntimeManager.restartCount(cfg.id)
            if (rc > 0) append("  [重启×$rc]")
        }
        box.addView(TextView(this).apply {
            text = flags
            textSize = 12f
            setTextColor(0xFF444444.toInt())
        })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply {
            text = if (isRunning) "停止" else "启动"
            setOnClickListener {
                if (isRunning) stopViaService(cfg.id) else startViaService(cfg.id)
            }
        })
        btns.addView(Button(this).apply {
            text = "日志"
            setOnClickListener { showLog(cfg.id) }
        })
        box.addView(btns)
        return box
    }

    private fun showLog(id: String) {
        val f = File(filesDir, "logs/$id.log")
        val body = if (f.isFile) {
            val lines = f.readLines()
            if (lines.size > 300) lines.takeLast(300).joinToString("\n") + "\n…(仅显示最后 300 行)"
            else lines.joinToString("\n")
        } else "(暂无日志: ${f.absolutePath})"
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = body
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("日志 · $id")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ----------------------------- 服务指令 -----------------------------

    private fun startViaService(id: String) {
        val i = Intent(this, ContainerService::class.java).apply {
            action = ContainerService.ACTION_START
            putExtra(ContainerService.EXTRA_ID, id)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopViaService(id: String?) {
        val i = Intent(this, ContainerService::class.java).apply {
            action = if (id == null) ContainerService.ACTION_STOP_ALL else ContainerService.ACTION_STOP
            if (id != null) putExtra(ContainerService.EXTRA_ID, id)
        }
        startService(i)
    }

    // ----------------------------- 生命周期 -----------------------------

    override fun onResume() {
        super.onResume()
        RuntimeManager.addListener(onStateChange)
        render()
    }

    override fun onPause() {
        super.onPause()
        RuntimeManager.removeListener(onStateChange)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
