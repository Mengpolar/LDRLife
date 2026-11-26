package com.mengpolar.ldrlife.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.ldrlife.R
import com.mengpolar.ldrlife.core.Geo
import com.mengpolar.ldrlife.core.PairCode
import com.mengpolar.ldrlife.session.ConnState
import com.mengpolar.ldrlife.session.Session
import com.mengpolar.ldrlife.ui.components.IncomingCard
import com.mengpolar.ldrlife.ui.components.QrImage
import com.mengpolar.ldrlife.ui.components.UpdateBanner
import com.mengpolar.ldrlife.ui.components.rememberUpdater
import com.mengpolar.ldrlife.ui.theme.InkColors
import com.mengpolar.ldrlife.ui.theme.LetterType

/**
 * 主页 · 信笺版式：一封两人共同书写的信。
 * 距离写成文字（大字宋体），状态是信末落款（小字），「想你了」是盖一枚朱砂印。
 * 层级靠字号与留白 + 一根淡墨细线，不用卡片与大实心按钮。
 */
@Composable
fun HomeScreen(
    session: Session,
    onPair: () -> Unit,
    onChat: () -> Unit,
    onMap: () -> Unit,
    onHaptic: () -> Unit,
    onSettings: () -> Unit,
) {
    val partner = session.partner.collectAsState().value
    val conn = session.conn.collectAsState().value
    val incoming = session.incoming.collectAsState().value
    val waiting = session.waiting.collectAsState().value
    val error = session.error.collectAsState().value
    val partnerLoc = session.partnerLoc.collectAsState().value
    val partnerUsage = session.partnerUsage.collectAsState().value
    val lastHaptic = session.lastHaptic.collectAsState().value
    val myLoc = session.myLoc.collectAsState().value
    val myUsage = session.myUsage.collectAsState().value
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showQr by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(18.dp))

        // 顶部居中：连接状态（心形 + 文字）；右上角保留设置入口
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = if (conn == ConnState.ONLINE) InkColors.Cinnabar else InkColors.InkSoft,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (conn == ConnState.ONLINE) "已连接" else "未连接",
                    style = LetterType.Footnote,
                    color = InkColors.InkSoft,
                )
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = InkColors.InkSoft,
                )
            }
        }
        UpdateBanner(rememberUpdater())

        Spacer(Modifier.height(26.dp))

        // 信的开头：距离写成文字
        if (partner != null && myLoc != null && partnerLoc != null) {
            val d = Geo.haversineKm(myLoc.lat, myLoc.lon, partnerLoc.lat, partnerLoc.lon)
            Text(
                "与卿相隔",
                style = LetterType.Title,
                color = InkColors.Ink,
            )
            Text(
                Geo.formatKm(d),
                style = LetterType.Title.copy(fontSize = 34.sp, letterSpacing = 2.sp),
                color = InkColors.Cinnabar,
            )
        } else if (partner != null) {
            Text(
                "已与卿绑定",
                style = LetterType.Title,
                color = InkColors.Ink,
            )
        } else {
            Text(
                "静候卿音",
                style = LetterType.Title,
                color = InkColors.Ink,
            )
        }

        Spacer(Modifier.height(14.dp))
        InkRule()
        Spacer(Modifier.height(12.dp))

        // 信末落款：一行一人，避免时间词堆叠成"系统日志"
        val myPosTxt = myLoc?.let { "位置刚刚更新" } ?: "位置未定位"
        Text(
            "我 · $myPosTxt，正在用 ${myUsage?.app ?: "未知"}",
            style = LetterType.Footnote,
            color = InkColors.InkSoft,
        )
        if (partner != null) {
            val posTxt = partnerLoc?.let { "位置${freshnessOf(it.ts)}" } ?: "位置未共享"
            val useTxt = partnerUsage?.let { u ->
                if (System.currentTimeMillis() - u.ts < 60 * 60 * 1000) "方才在用 ${u.app}" else "久未有动静"
            } ?: "状态未共享"
            Text(
                "卿 · $posTxt，$useTxt",
                style = LetterType.Footnote,
                color = InkColors.InkSoft,
            )
        }

        // 朱批：卿想你了（爱心小图 + 可爱口吻，不用 emoji）
        lastHaptic?.let { h ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.heart_float),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "卿想你了",
                    style = LetterType.TitleSmall,
                    color = InkColors.Cinnabar,
                )
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = {
                    session.replyHaptic()
                    com.mengpolar.ldrlife.core.Vibe.play(ctx, h)
                }) {
                    Text("回一枚印", color = InkColors.Cinnabar, style = LetterType.Footnote)
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = LetterType.Footnote, color = InkColors.Cinnabar)
        }

        Spacer(Modifier.height(26.dp))

        if (partner == null) {
            // 未配对：配对信物
            Text(
                PairCode.display(session.identity.pairCode),
                style = LetterType.Title.copy(
                    fontSize = 40.sp,
                    letterSpacing = 10.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = InkColors.Ink,
            )
            Text("这是你的配对码，交给 TA", style = LetterType.Footnote, color = InkColors.InkSoft)
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(session.identity.pairCode))
                }) { Text("复制", color = InkColors.Ink, style = LetterType.Footnote) }
                TextButton(onClick = { showQr = true }) {
                    Text("二维码", color = InkColors.Ink, style = LetterType.Footnote)
                }
            }
            Spacer(Modifier.height(14.dp))
            LetterButton("输入对方的配对码", onPair)
            if (waiting) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "已寄出申请，等待对方确认…",
                    style = LetterType.Footnote,
                    color = InkColors.InkSoft,
                )
                TextButton(onClick = { session.cancelPairRequest() }) {
                    Text("取消申请", color = InkColors.InkSoft, style = LetterType.Footnote)
                }
            }
            if (incoming.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("收到的配对申请", style = LetterType.TitleSmall, color = InkColors.Ink)
                Spacer(Modifier.height(6.dp))
                incoming.forEach { req ->
                    IncomingCard(
                        req = req,
                        onApprove = { session.decide(req.id, true) },
                        onDeny = { session.decide(req.id, false) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            // 已配对：朱印「念」
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                HeartBubbleButton(onClick = onHaptic)
            }
            Text(
                "想念，就点一颗心",
                style = LetterType.Footnote,
                color = InkColors.InkSoft,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TextButton(onClick = onChat) {
                    Text(
                        "读信",
                        color = InkColors.Ink,
                        style = LetterType.Footnote,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                }
                TextButton(onClick = onMap) {
                    Text(
                        "看位置",
                        color = InkColors.Ink,
                        style = LetterType.Footnote,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(content = "ldrlife://pair/${session.identity.pairCode}")
                    Text(
                        "扫码配对；也可复制码发给 TA",
                        style = LetterType.Footnote,
                        color = InkColors.InkSoft,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQr = false }) { Text("关闭") }
            },
        )
    }
}

/** 一根淡墨细线：信笺的分隔 */
@Composable
private fun InkRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(InkColors.Rule),
    )
}

/** 信笺底线按钮：一根淡墨细线作底线（信纸式），不用描边框 */
@Composable
private fun LetterButton(text: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = LetterType.Footnote, color = InkColors.Ink)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(InkColors.Rule),
        )
    }
}

/** 爱心气泡按钮：白圆底 + 手绘粉心，按下轻微缩放（降级为普通点击），点击进入想你了 */
@Composable
private fun HeartBubbleButton(onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale = if (pressed) 0.88f else 1f
    Box(
        Modifier
            .size(108.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .border(3.dp, InkColors.Cinnabar, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.heart_float),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
    }
}

private fun freshnessOf(ts: Long): String {
    val mins = ((System.currentTimeMillis() - ts) / 60000).toInt()
    return when {
        mins < 1 -> "刚刚"
        mins < 60 -> "最近${mins}分钟"
        else -> "最近${mins / 60}小时"
    }
}
