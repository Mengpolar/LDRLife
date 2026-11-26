package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.ldrlife.session.Phase
import com.mengpolar.ldrlife.session.Session
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(session: Session, onBack: () -> Unit) {
    val code = remember { mutableStateOf("") }
    val busy = session.busy.collectAsState().value
    val error = session.error.collectAsState().value
    val waiting = session.waiting.collectAsState().value
    val incoming = session.incoming.collectAsState().value
    val phase = session.phase.collectAsState().value

    // 对方同意后自动返回主页（用户要求：不停留在申请页）
    LaunchedEffect(phase) {
        if (phase == Phase.PAIRED) {
            delay(600)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配对") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("输入对方的 7 位配对码", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = code.value,
                onValueChange = { v -> if (v.length <= 7 && v.all { it in '0'..'9' }) code.value = v },
                label = { Text("如 438 2017") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { session.sendPairRequest(code.value) },
                enabled = !busy && code.value.length == 7,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("发送配对申请") }
            if (phase == Phase.PAIRED) {
                Text("配对成功！正在返回主页…", color = MaterialTheme.colorScheme.primary)
            } else if (waiting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("等待对方确认中…对方同意后会自动完成绑定")
                Button(
                    onClick = { session.cancelPairRequest() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消申请") }
            }
            if (incoming.isNotEmpty()) {
                Text("收到的配对申请", style = MaterialTheme.typography.titleMedium)
                incoming.forEach { req ->
                    com.mengpolar.ldrlife.ui.components.IncomingCard(
                        req = req,
                        onApprove = { session.decide(req.id, true) },
                        onDeny = { session.decide(req.id, false) },
                    )
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
