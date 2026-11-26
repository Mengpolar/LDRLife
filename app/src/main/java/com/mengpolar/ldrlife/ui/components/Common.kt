package com.mengpolar.ldrlife.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mengpolar.ldrlife.ui.theme.InkColors
import com.mengpolar.ldrlife.ui.theme.LetterType
import com.mengpolar.ldrlife.data.MSG_DELIVERED
import com.mengpolar.ldrlife.data.MSG_EXPIRED
import com.mengpolar.ldrlife.data.MSG_READ
import com.mengpolar.ldrlife.data.MSG_SENT
import com.mengpolar.ldrlife.data.MessageEntity
import com.mengpolar.ldrlife.session.IncomingRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusChip(online: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            if (online) "已连接服务器" else "未连接",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun IncomingCard(req: IncomingRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    // 信笺式：左侧一根淡墨竖线 + 墨字，无卡片
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(InkColors.Rule),
        )
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                "设备 ${req.fromUuid.take(8)} 请求与你绑定",
                style = MaterialTheme.typography.bodyLarge,
                color = InkColors.Ink,
            )
            Row {
                TextButton(onClick = onDeny) {
                    Text("拒绝", style = LetterType.Footnote, color = InkColors.InkSoft)
                }
                TextButton(onClick = onApprove) {
                    Text("同意绑定", style = LetterType.Footnote, color = InkColors.Cinnabar)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(m: MessageEntity) {
    Column(
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                m.body,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val expired = m.status == MSG_EXPIRED
        val statusText = when {
            !m.outgoing -> ""
            expired -> "  ❗未送达（超过 7 天）"
            m.status == MSG_READ -> "  已读"
            m.status == MSG_DELIVERED -> "  ✓✓"
            m.status == MSG_SENT -> "  ✓"
            else -> "  ⏳"
        }
        Text(
            formatTs(m.ts) + statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
fun QrImage(content: String, size: Dp = 240.dp) {
    val bmp = remember(content) {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600, hints)
        val w = bits.width
        val h = bits.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
}
