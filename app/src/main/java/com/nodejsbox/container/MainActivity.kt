package com.nodejsbox.container

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nodejsbox.container.core.ContainerBootstrap
import com.nodejsbox.container.core.ContainerService
import com.nodejsbox.container.core.Diag
import com.nodejsbox.container.core.RuntimeManager
import com.nodejsbox.container.core.WebServer
import com.nodejsbox.container.ui.WebPanelActivity

/**
 * 容器主界面：提供三个入口——「启动后端服务器」「打开 Web 前端」「开源许可」。
 *
 * 全部运行能力（终端 / 进程 / 文件 / 脚本）都在 H5 前端（WebServer + assets/web/index.html）；
 * 内置脚本由 ContainerBootstrap 在启动时自动解包到 files/scripts/，前端自行浏览运行。
 * 「开源许可」直接读 assets/licenses/（运行时告知 + Node.js LICENSE 全文），不依赖后端已启动。
 *
 * adb 自动化入口：
 *   am start -n com.nodejsbox.container/.MainActivity --es run scripts/test-full.js
 *   （按脚本相对路径拉起 dyn-script-* 动态实例；前台保活由 NodeRuntime.spawn 自动触发）
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        ContainerBootstrap.init(this)
        // 前台服务保活钩子：core 层要拉起服务时回调到这里（startForegroundService）
        ContainerService.ServiceHook = { app ->
            ContextCompat.startForegroundService(
                app, Intent(app, ContainerService::class.java).setAction(ContainerService.ACTION_KEEPALIVE)
            )
        }
        buildUi()
        maybeRequestNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val run = intent?.getStringExtra("run") ?: return
        Diag.log("[intent] run=$run")
        val id = RuntimeManager.startScript(this, run)
        toast("已启动实例: $id")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "NodeJsBox — Node.js 运行时容器"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "脚本: filesDir/scripts/ · 日志: filesDir/logs/\n后端: WebSocket + HTTP (127.0.0.1) · 前端: 内置 H5 控制台"
            textSize = 12f
            setPadding(0, 8, 0, 16)
        })

        root.addView(Button(this).apply {
            text = "启动后端服务器"
            setOnClickListener {
                WebServer.ensureStarted(this@MainActivity)
                val p = WebServer.port
                toast(if (p > 0) "后端已启动: http://127.0.0.1:$p" else "后端启动失败，看 logcat")
            }
        })
        root.addView(Button(this).apply {
            text = "打开 Web 前端"
            setOnClickListener {
                WebServer.ensureStarted(this@MainActivity)
                startActivity(Intent(this@MainActivity, WebPanelActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "开源许可"
            setOnClickListener { showLicensesDialog() }
        })
    }

    /**
     * 开源许可弹窗：顶部一排条目按钮，下面一个滚动区复用展示当前条目文本。
     * 不分段平铺：Node.js LICENSE 约 150KB / 2900 行，多段 ScrollView 会造成嵌套滚动困境。
     */
    private fun showLicensesDialog() {
        val body = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        val scroller = ScrollView(this).apply { addView(body) }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 0)
        }
        WebServer.LICENSE_ITEMS.forEach { (name, title) ->
            tabs.addView(Button(this).apply {
                text = if (name == "node-LICENSE.txt") "Node.js 许可" else "运行时来源"
                isAllCaps = false
                setOnClickListener {
                    body.text = "$title\n\n${WebServer.licenseText(this@MainActivity, name)}"
                    scroller.scrollTo(0, 0)
                    // 高亮当前条目（setTypeface 的 style 不可为 null，分两步写）
                    for (i in 0 until tabs.childCount) {
                        (tabs.getChildAt(i) as Button).setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs)
            addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("开源许可")
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
            .also { dialog ->
                (tabs.getChildAt(0) as? Button)?.performClick() // 默认展示首条
                dialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.94).toInt(),
                    (resources.displayMetrics.heightPixels * 0.82).toInt()
                )
            }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
