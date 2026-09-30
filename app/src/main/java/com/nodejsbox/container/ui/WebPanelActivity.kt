package com.nodejsbox.container.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.nodejsbox.container.core.WebServer

/**
 * Web 控制台弹窗：Dialog 主题 Activity 内嵌 WebView，
 * 加载 WebServer 提供的 H5 前端（文件 / 进程 / 终端三合一），App 的唯一面板入口。
 */
class WebPanelActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebServer.ensureStarted(this) // 幂等：确保端口可用（onResume 被杀重启等场景兜底）
        val port = WebServer.port
        if (port <= 0) {
            toastClose("WebServer 启动失败，请查看 logcat")
            return
        }
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = FrameLayout(this).apply { addView(webView) }
        setContentView(root)
        // Dialog 主题下 window.decorView 尚未创建，标题必须在 setContentView 之后设置
        setTitle("Web 控制台 · 127.0.0.1:$port")
        webView.loadUrl("http://127.0.0.1:$port/")
    }

    private fun toastClose(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        // 只关本次弹窗连接的 WS 会话由服务器侧清理；这里不停 WebServer（其他入口可能仍在用）
        super.onDestroy()
    }
}
