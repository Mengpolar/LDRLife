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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpolar.ldrlife.core.Vibe
import com.mengpolar.ldrlife.session.Session

/** "想你了"：预设选择 → 本地预览（先感受再发送）→ 发送 E2E 振动信封（设计文档 §8.3） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticScreen(session: Session, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lastHaptic = session.lastHaptic.collectAsState().value
    var morseText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Vibe.Pattern?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("想你了") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("选一个感觉，一键传给 TA", style = MaterialTheme.typography.titleMedium)

            Vibe.PRESETS.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("${p.emoji} ${p.name}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.events.size} 段节奏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            OutlinedButton(onClick = {
                                preview = p
                                Vibe.play(ctx, p) // 先感受再发送
                            }) { Text("预览") }
                            Button(
                                onClick = {
                                    preview = p
                                    session.sendHaptic(p)
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text("发送") }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📱 摩斯密码（彩蛋：仅字母/数字，8 字内）", style = MaterialTheme.typography.titleMedium)
                    var morseText by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = morseText,
                        onValueChange = { if (it.length <= 8 && it.all { c -> c.isLetterOrDigit() }) morseText = it },
                        placeholder = { Text("LOVE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (morseText.isNotBlank()) {
                                preview = Vibe.morseOf(morseText)
                                Vibe.play(ctx, preview!!)
                            }
                        }) { Text("预览") }
                        Button(onClick = {
                            if (morseText.isNotBlank()) {
                                val p = Vibe.morseOf(morseText)
                                preview = p
                                session.sendHaptic(p)
                            }
                        }, enabled = morseText.isNotBlank()) { Text("发送摩斯") }
                    }
                }
            }

            lastHaptic?.let { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("刚收到 TA 的想念 ${p.emoji}", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { session.replyHaptic() },
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text("回一个（同款图案）") }
                    }
                }
            }

            preview?.let { p ->
                Text(
                    "最近播放：${p.emoji} ${p.name}（${p.events.size} 段）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
