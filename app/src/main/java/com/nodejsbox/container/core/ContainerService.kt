package com.nodejsbox.container.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nodejsbox.container.MainActivity
import com.nodejsbox.container.R

/**
 * 运行时容器前台服务（保活 + 汇总通知）。
 *
 *  - 有实例运行时保持前台（通知：实例数 + id 列表），实例全部停止即自灭；
 *    自灭时复位保活标记，下次有实例启动会重新拉起。
 *  - 保活入口 [ensureAlive]：由 NodeRuntime.spawn 在每次拉起进程前调用，
 *    同一进程内只发一次 startForegroundService（除非服务已自灭）。
 *    MainActivity 启动时注册 [ServiceHook]；未注册时 ensureAlive 为 no-op（仪器测试场景）。
 *  - 通知刷新直接监听 RuntimeManager.addStateListener；实例全部是动态实例（dyn-*），
 *    不跨重启恢复，由 H5 前端/脚本自行重新拉起。
 *  - 动作：
 *      ACTION_KEEPALIVE    拉起前台（无 extra，幂等）
 *      ACTION_STOP_ALL     停止全部
 */
class ContainerService : Service() {

    companion object {
        const val ACTION_KEEPALIVE = "com.nodejsbox.container.action.KEEPALIVE"
        const val ACTION_STOP_ALL = "com.nodejsbox.container.action.STOP_ALL"
        private const val CHANNEL_ID = "runtime"
        private const val NOTIF_ID = 1000

        /** 拉起前台服务的注入钩子（Activity 层注册，避免 core 层反向依赖发起方） */
        var ServiceHook: ((Context) -> Unit)? = null

        @Volatile private var serviceLaunched = false

        /** 确保前台服务已拉起（幂等；钩子未注册时静默跳过） */
        fun ensureAlive(ctx: Context) {
            if (serviceLaunched) return
            val hook = ServiceHook ?: return
            val app = ctx.applicationContext
            serviceLaunched = true
            hook(app)
        }

        /** 服务确认停止/自灭时复位，允许下次重新拉起 */
        internal fun resetAlive() { serviceLaunched = false }
    }

    private var notifMgr: NotificationManager? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val stateListener = RuntimeManager.StateListener { mainHandler.post { refreshNotification() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifMgr = getSystemService(NotificationManager::class.java)
        notifMgr?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "运行时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Node.js 运行时容器状态"
            }
        )
        RuntimeManager.addStateListener(stateListener)
        // 本地桥接服务（node 脚本 → 容器能力）；随 App 进程存活，不随服务停启——
        // 运行中的 node 进程持有端口/token，中途换端口会使其失效
        BridgeServer.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即满足前台服务义务（startForegroundService 调用后 5s 内必须 startForeground）
        startInForeground()
        when (intent?.action) {
            ACTION_STOP_ALL -> RuntimeManager.stopAll(this)
        }
        refreshNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        RuntimeManager.removeStateListener(stateListener)
        resetAlive()
        super.onDestroy()
    }

    private fun startInForeground() {
        val n = buildNotification(RuntimeManager.runningIds())
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification() {
        val running = RuntimeManager.runningIds()
        if (running.isEmpty()) {
            Log.i(NodeRuntime.TAG, "无运行实例，服务自灭")
            resetAlive()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startInForeground()
    }

    private fun buildNotification(running: List<String>): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NodeJsBox 容器运行中")
            .setContentText("${running.size} 个运行时: ${running.joinToString(", ")}")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
