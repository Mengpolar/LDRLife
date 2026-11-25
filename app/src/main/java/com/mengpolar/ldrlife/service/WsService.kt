package com.mengpolar.ldrlife.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mengpolar.ldrlife.LdrApp
import com.mengpolar.ldrlife.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台保活服务：常驻低调通知 + 维持 WS 长连接（START_STICKY 被杀自动拉起）。
 * 设计文档 §5.6：国内无 FCM，WS 长连接是消息即时性的基础。
 *
 * 后台周期上报：使用状态每 60s（Session 内 5 分钟同应用节流），
 * 位置每 10 分钟（LocationProvider 有 10 分钟缓存）——否则对方看到的永远是
 * "上次你打开 App 那一刻"的状态。
 */
class WsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        val app = application as LdrApp
        app.session.ensureWs()
        scope.launch {
            while (isActive) {
                delay(60_000)
                app.session.reportUsageIfChanged()
                app.session.maybeRefreshLocation()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
