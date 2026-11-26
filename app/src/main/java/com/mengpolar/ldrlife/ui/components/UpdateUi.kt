package com.mengpolar.ldrlife.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpolar.ldrlife.BuildConfig
import com.mengpolar.ldrlife.LdrApp
import com.mengpolar.ldrlife.core.Updater
import com.mengpolar.ldrlife.ui.theme.InkColors

/** 应用级单例获取：MainActivity 启动静默检查，设置页可手动检查 */
@Composable
fun rememberUpdater(): Updater =
    (LocalContext.current.applicationContext as LdrApp).updater

/** 首页横幅：信笺式一行朱批（无卡片），点击打开更新对话框 */
@Composable
fun UpdateBanner(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val ver = when (val s = state) {
        is Updater.State.Available -> s.info.versionName
        is Updater.State.Downloading -> s.info.versionName
        is Updater.State.Ready -> s.info.versionName
        else -> null
    }
    if (ver == null) return
    val label = when (val s = state) {
        is Updater.State.Downloading -> "正在下载 v$ver（${s.progress}%）"
        is Updater.State.Ready -> "v$ver 已就绪 · 去安装"
        else -> "发现新版本 v$ver"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { updater.dialogVisible.value = true }
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "查看",
            style = MaterialTheme.typography.labelMedium,
            color = InkColors.InkSoft,
        )
    }
}

/** 更新对话框：发现新版本 → 立即更新（下载进度）→ 立即安装 */
@Composable
fun UpdateDialog(updater: Updater) {
    val state by updater.state.collectAsState()
    val visible by updater.dialogVisible.collectAsState()
    val ctx = LocalContext.current
    if (!visible) return
    val s = state
    AlertDialog(
        onDismissRequest = { updater.dialogVisible.value = false },
        title = { Text("软件更新") },
        text = {
            when (s) {
                is Updater.State.Available -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("发现新版本 v${s.info.versionName}（当前 v${BuildConfig.VERSION_NAME}）")
                    if (s.info.notes.isNotBlank()) {
                        Text(
                            s.info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is Updater.State.Downloading -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("正在下载 v${s.info.versionName}（${s.progress}%）")
                    LinearProgressIndicator(
                        progress = { s.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "下载在后台进行，可关闭弹窗继续使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is Updater.State.Ready -> Text(
                    "v${s.info.versionName} 已下载并完成 SHA-256 校验，点击「立即安装」跳转系统安装器。"
                )
                is Updater.State.Failed -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is Updater.State.Checking -> Text("正在检查更新…")
                is Updater.State.UpToDate -> Text("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                is Updater.State.Idle -> Text("暂无更新信息")
            }
        },
        confirmButton = {
            when (s) {
                is Updater.State.Available -> Button(onClick = { updater.download(s.info) }) {
                    Text("立即更新")
                }
                is Updater.State.Ready -> Button(onClick = {
                    if (updater.canInstall()) {
                        updater.install(s.file)
                        updater.dialogVisible.value = false
                    } else {
                        Toast.makeText(
                            ctx,
                            "请先允许「安装未知应用」权限，再返回点击安装",
                            Toast.LENGTH_LONG,
                        ).show()
                        updater.openInstallSettings()
                    }
                }) { Text("立即安装") }
                is Updater.State.Failed -> TextButton(onClick = { updater.checkNow() }) {
                    Text("重试")
                }
                else -> TextButton(onClick = { updater.dialogVisible.value = false }) {
                    Text("关闭")
                }
            }
        },
        dismissButton = {
            when (s) {
                is Updater.State.Available -> TextButton(
                    onClick = { updater.dialogVisible.value = false }
                ) { Text("以后再说") }
                is Updater.State.Downloading -> TextButton(
                    onClick = { updater.dialogVisible.value = false }
                ) { Text("后台下载") }
                is Updater.State.Failed -> TextButton(
                    onClick = { updater.dialogVisible.value = false }
                ) { Text("关闭") }
                else -> {}
            }
        },
    )
}
