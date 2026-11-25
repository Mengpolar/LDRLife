package com.mengpolar.ldrlife

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mengpolar.ldrlife.service.WsService
import com.mengpolar.ldrlife.ui.AppRoot
import com.mengpolar.ldrlife.ui.theme.LDRLifeTheme

class MainActivity : ComponentActivity() {

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝则收不到提醒，设置页可再开 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as LdrApp).session

        // Android 13+ 通知运行时权限（M1 前置：前台服务通知 + 消息提醒）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 前台服务：保持 WS 长连接（START_STICKY 被杀自动拉起）
        ContextCompat.startForegroundService(this, Intent(this, WsService::class.java))

        setContent {
            LDRLifeTheme {
                AppRoot(session)
            }
        }
    }
}
