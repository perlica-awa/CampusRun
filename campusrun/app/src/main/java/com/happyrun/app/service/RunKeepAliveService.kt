package com.happyrun.app.service

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.happyrun.app.R
import com.happyrun.app.ui.MainActivity

/**
 * 跑步期间的前台服务。
 *
 * 目的只有一个：把进程留在前台优先级上。跑步页是 WebView，页面里的计时与上传都在 JS 里跑，
 * 进程被回收就等于本次记录断在半路（进度虽然还留在 localStorage，但上传链路已经断了）。
 * 顺带持一个 partial wakelock —— 跑步通常是熄屏的几十分钟，没有它 CPU 会休眠。
 *
 * 通知是前台服务的强制副产物，不是功能：点它是回到 App，不是"重新开始"。
 */
class RunKeepAliveService : Service() {

    companion object {
        private const val TAG = "RunKeepAlive"
        private const val CHANNEL_ID = "run_keepalive"
        private const val NOTIF_ID = 1101

        /** 定位权限已经授权时才调；没有权限会在 startForeground 时抛 SecurityException */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RunKeepAliveService::class.java)) }
                .onFailure { Log.w(TAG, "前台服务启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RunKeepAliveService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type) }
            .onFailure { Log.w(TAG, "startForeground 失败：${it.message}") }
        acquireWakeLock()
        // 被系统回收后重新拉起：没有 Activity 也继续挂着，用户回来就能接着看页面
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.keepalive_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "campusrun:run").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        }.onFailure { Log.w(TAG, "wakelock 获取失败：${it.message}") }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
