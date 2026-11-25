package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mengpolar.ldrlife.session.Phase
import com.mengpolar.ldrlife.session.Session

@Composable
fun SetupScreen(session: Session, onDone: () -> Unit) {
    val url = remember { mutableStateOf("") }
    val gate = remember { mutableStateOf("") }
    val phase = session.phase.collectAsState().value
    val error = session.error.collectAsState().value

    LaunchedEffect(phase) {
        if (phase == Phase.UNPAIRED || phase == Phase.PAIRED) onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text("倾卿", style = MaterialTheme.typography.headlineLarge)
        Text("与 TA 一对一绑定，端到端安全私聊", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = url.value,
            onValueChange = { url.value = it },
            label = { Text("服务器地址（https://host:port）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = gate.value,
            onValueChange = { gate.value = it },
            label = { Text("服务器门禁密钥") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { session.saveServer(url.value, gate.value) },
            enabled = phase != Phase.REGISTERING && url.value.isNotBlank() && gate.value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (phase == Phase.REGISTERING) "连接中…" else "连接并注册")
        }
        Text(
            "以上信息由部署者提供，仅保存在本机，不会上传任何第三方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
