package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.ldrlife.BuildConfig
import com.mengpolar.ldrlife.core.Updater
import com.mengpolar.ldrlife.session.Session
import com.mengpolar.ldrlife.ui.components.rememberUpdater

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(session: Session, onBack: () -> Unit) {
    val safety = session.safety.collectAsState().value
    val partner = session.partner.collectAsState().value
    val cfg = remember { session.serverConfig() }
    var confirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("安全码", style = MaterialTheme.typography.titleMedium)
            Text(
                safety ?: "未配对",
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 28.sp,
            )
            Text(
                "双方设置页显示一致 = 无中间人（建议电话核对一次）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("对方设备", style = MaterialTheme.typography.titleMedium)
            Text(partner?.uuid?.take(8) ?: "未配对", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("服务器", style = MaterialTheme.typography.titleMedium)
            Text(cfg?.url ?: "未配置", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text("我的设备 ID：${session.identity.uuid.take(8)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "聊天记录仅保存在本机（SQLCipher 加密），服务器不存明文；解绑将清除双方绑定与本地记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = {
                try {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:com.mengpolar.ldrlife")
                        )
                    )
                } catch (_: Exception) {
                }
            }) {
                Text("电池优化白名单（提升后台消息即时性）")
            }
            // 通知权限：未开启时后台收不到"想你了"提醒（部分系统安装后默认关）
            val notifEnabled = remember {
                androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            }
            if (!notifEnabled) {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                }) {
                    Text("⚠ 通知权限未开启（后台收不到提醒）")
                }
            }
            Text(
                "华为/鸿蒙：系统设置 → 应用 → 倾卿 → 启动管理，开启「允许自启动/关联启动/后台活动」；" +
                    "通知保持开启。小米/OPPO/vivo 也有类似「省电策略」，选「无限制」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("应用状态共享（M3）", style = MaterialTheme.typography.titleMedium)
            var usageOn by remember { mutableStateOf(session.usageEnabled()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = usageOn,
                    onClick = {
                        usageOn = true
                        session.setUsageEnabled(true)
                    },
                    label = { Text("共享（应用名+时间）") },
                )
                FilterChip(
                    selected = !usageOn,
                    onClick = {
                        usageOn = false
                        session.setUsageEnabled(false)
                    },
                    label = { Text("关闭") },
                )
            }
            if (usageOn && !com.mengpolar.ldrlife.core.Usage.hasPermission(ctx)) {
                TextButton(onClick = { com.mengpolar.ldrlife.core.Usage.openSettings(ctx) }) {
                    Text("⚠ 授权“使用情况访问权限”")
                }
            }
            Text(
                "仅共享当前应用名与时间，不留历史；对方端 1 小时后过期。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("版本更新", style = MaterialTheme.typography.titleMedium)
            val updater = rememberUpdater()
            val updState = updater.state.collectAsState().value
            Text("当前版本 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            when (val s = updState) {
                is Updater.State.Idle, is Updater.State.UpToDate ->
                    Button(onClick = { updater.checkNow() }) { Text("检查更新") }
                is Updater.State.Checking ->
                    Text("正在检查…", style = MaterialTheme.typography.bodyMedium)
                is Updater.State.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { updater.checkNow() }) { Text("重试") }
                }
                is Updater.State.Available -> TextButton(
                    onClick = { updater.dialogVisible.value = true }
                ) { Text("发现新版本 v${s.info.versionName} · 查看") }
                is Updater.State.Downloading -> Text(
                    "正在下载 v${s.info.versionName}（${s.progress}%）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is Updater.State.Ready -> TextButton(
                    onClick = { updater.dialogVisible.value = true }
                ) { Text("v${s.info.versionName} 已就绪 · 去安装") }
            }
            HorizontalDivider()
            Button(
                onClick = { confirm = true },
                enabled = partner != null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("解除绑定") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("解除绑定？") },
            text = {
                Text("双方绑定关系与服务器暂存信封都会清除，本地聊天记录也会删除。解绑后需要重新配对。")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; session.unpair(); onBack() }) { Text("解除") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
        )
    }
}
