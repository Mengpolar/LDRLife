package com.mengpolar.ldrlife.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mengpolar.ldrlife.LdrApp
import com.mengpolar.ldrlife.R

/**
 * 前台保活服务：常驻低调通知 + 维持 WS 长连接（START_STICKY 被杀自动拉起）。
 * 设计文档 §5.6：国内无 FCM，WS 长连接是消息即时性的基础。
 */
class WsService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("keepalive", "加密连接", NotificationManager.IMPORTANCE_MIN)
        )
        val n = NotificationCompat.Builder(this, "keepalive")
            .setContentTitle("倾卿")
            .setContentText("与 TA 的加密连接保持中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        (application as LdrApp).session.ensureWs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
