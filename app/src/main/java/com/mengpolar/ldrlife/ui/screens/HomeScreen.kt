package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.ldrlife.core.PairCode
import com.mengpolar.ldrlife.session.ConnState
import com.mengpolar.ldrlife.session.Phase
import com.mengpolar.ldrlife.session.Session
import com.mengpolar.ldrlife.ui.components.IncomingCard
import com.mengpolar.ldrlife.ui.components.QrImage
import com.mengpolar.ldrlife.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(session: Session, onPair: () -> Unit, onChat: () -> Unit, onSettings: () -> Unit) {
    val phase = session.phase.collectAsState().value
    val partner = session.partner.collectAsState().value
    val conn = session.conn.collectAsState().value
    val incoming = session.incoming.collectAsState().value
    val waiting = session.waiting.collectAsState().value
    val error = session.error.collectAsState().value
    val clipboard = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("倾卿") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusChip(online = conn == ConnState.ONLINE)
            val p = partner
            if (p == null) {
                Text("我的配对码", style = MaterialTheme.typography.titleMedium)
                Text(
                    PairCode.display(session.identity.pairCode),
                    style = MaterialTheme.typography.headlineLarge,
                    letterSpacing = 6.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(session.identity.pairCode)) }) {
                        Text("复制")
                    }
                    TextButton(onClick = { showQr = true }) { Text("二维码") }
                }
                Button(onClick = onPair, enabled = phase == Phase.UNPAIRED, modifier = Modifier.fillMaxWidth()) {
                    Text("输入对方配对码")
                }
                if (waiting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("已发送申请，等待对方确认…")
                }
                if (incoming.isNotEmpty()) {
                    Text(
                        "收到的配对申请",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Start),
                    )
                    LazyColumn {
                        items(incoming) { req ->
                            IncomingCard(
                                req = req,
                                onApprove = { session.decide(req.id, true) },
                                onDeny = { session.decide(req.id, false) },
                            )
                        }
                    }
                }
            } else {
                Text("已与对方绑定 💞", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onChat, modifier = Modifier.fillMaxWidth()) { Text("进入私聊") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (error != null && phase == Phase.UNCONFIGURED) {
                TextButton(onClick = { session.retryRegister() }) { Text("重试连接") }
            }
        }
    }

    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(content = "ldrlife://pair/${session.identity.pairCode}")
                    Text(
                        "扫码配对（M1 支持）；当前可通过复制/微信传码",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("关闭") } },
        )
    }
}
