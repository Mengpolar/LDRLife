package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.mengpolar.ldrlife.session.Session

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
