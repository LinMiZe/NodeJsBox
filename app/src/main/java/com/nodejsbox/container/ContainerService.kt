package com.nodejsbox.container

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 运行时容器前台服务。
 *
 *  - 有实例运行时保持前台（汇总通知：实例数 + id 列表），实例全部停止即自灭。
 *  - START_STICKY：进程被系统回收后服务重建，按 RuntimeManager.resume()
 *    恢复 restart=true 的在跑实例与 autostart=true 的实例。
 *  - 动作：
 *      ACTION_START   (extra id)  启动一个实例
 *      ACTION_STOP    (extra id)  停止一个实例
 *      ACTION_STOP_ALL            停止全部
 */
class ContainerService : Service() {

    companion object {
        const val ACTION_START = "com.nodejsbox.container.action.START"
        const val ACTION_STOP = "com.nodejsbox.container.action.STOP"
        const val ACTION_STOP_ALL = "com.nodejsbox.container.action.STOP_ALL"
        const val EXTRA_ID = "id"
        private const val CHANNEL_ID = "runtime"
        private const val NOTIF_ID = 1000
    }

    private var notifMgr: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifMgr = getSystemService(NotificationManager::class.java)
        notifMgr?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "运行时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Node.js 运行时容器状态"
            }
        )
        RuntimeManager.addListener { refreshNotification() }
        // sticky 重建 / 服务首次创建：恢复需要保活的实例
        RuntimeManager.resume(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即满足前台服务义务（startForegroundService 调用后 5s 内必须 startForeground）
        startInForeground()
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { RuntimeManager.start(this, it) }
            ACTION_STOP -> intent.getStringExtra(EXTRA_ID)?.let { RuntimeManager.stop(this, it) }
            ACTION_STOP_ALL -> RuntimeManager.stopAll(this)
        }
        refreshNotification()
        return START_STICKY
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
