package com.mengpolar.ldrlife.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mengpolar.ldrlife.BuildConfig
import com.mengpolar.ldrlife.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内更新（M4）：数据源 = 仓库根目录 latest.json（CI 打 tag 时自动更新）。
 * 读取经国内加速镜像（gh-proxy 等），下载 Release APK 多线路重试，
 * SHA-256 校验通过后才拉起系统安装器。
 *
 * 不走 GitHub API（匿名限流 60/h，曾烧完配额）；latest.json 是纯静态文件，
 * 由 raw.githubusercontent 经镜像访问，无限流问题。
 */
class Updater(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Int) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val dialogVisible = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 手动检查（设置页）。发现新版本时自动弹窗。 */
    fun checkNow(openDialogOnFound: Boolean = true) {
        state.value = State.Checking
        scope.launch {
            val info = fetchLatest()
            if (info == null) {
                state.value = State.Failed("检查更新失败，请稍后重试")
            } else {
                markChecked()
                state.value = if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    State.UpToDate
                } else {
                    if (openDialogOnFound) dialogVisible.value = true
                    State.Available(info)
                }
            }
        }
    }

    /** 启动静默检查：24h 节流；发现新版本 → 通知 + 首页横幅 */
    fun autoCheck() {
        val prefs = context.getSharedPreferences("ldr", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(LAST_CHECK, 0L) < 24 * 3600 * 1000L) return
        scope.launch {
            val info = fetchLatest() ?: return@launch
            markChecked()
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                state.value = State.Available(info)
                notify(
                    4002,
                    "倾卿有新版本 v${info.versionName}",
                    info.notes.ifBlank { "打开应用查看并更新" },
                )
            }
        }
    }

    /** 下载更新包（多镜像重试）→ SHA-256 校验 → Ready。失败保留最后错误信息。 */
    fun download(info: UpdateInfo) {
        if (state.value is State.Downloading) return
        scope.launch {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // 清理旧包
            val out = File(dir, "LDRLife-${info.versionName}.apk")
            var lastErr = "下载失败"
            for (m in MIRRORS) {
                try {
                    state.value = State.Downloading(info, 0)
                    progressNotification(0)
                    val req = Request.Builder()
                        .url(m + info.apkUrl)
                        .header("User-Agent", "LDRLife")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("空响应")
                        val total = body.contentLength()
                        var read = 0L
                        body.byteStream().use { ins ->
                            FileOutputStream(out).use { fos ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    fos.write(buf, 0, n)
                                    read += n
                                    if (total > 0) {
                                        val pct = (read * 100 / total).toInt()
                                        val cur = state.value
                                        if (cur is State.Downloading && cur.progress != pct) {
                                            state.value = cur.copy(progress = pct)
                                            progressNotification(pct)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (info.sha256.isNotBlank() &&
                        !sha256Of(out).equals(info.sha256, ignoreCase = true)
                    ) {
                        throw IOException("SHA-256 校验失败")
                    }
                    state.value = State.Ready(info, out)
                    dialogVisible.value = true
                    readyNotification(info)
                    return@launch
                } catch (e: Exception) {
                    out.delete()
                    lastErr = e.message ?: "下载失败"
                    // 换下一条镜像线路重试
                }
            }
            state.value = State.Failed("下载失败：$lastErr")
        }
    }

    /** 是否已授予"安装未知应用"权限（minSdk 26，恒可用） */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 拉起系统安装器（FileProvider 暴露 cacheDir 内的更新包） */
    fun install(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { openInstallSettings() }
    }

    /** 引导到"允许安装未知应用"设置页 */
    fun openInstallSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------- 内部 ----------

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (m in MIRRORS) {
            try {
                val req = Request.Builder()
                    .url(m + LATEST_URL)
                    .header("User-Agent", "LDRLife")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        parse(resp.body?.string() ?: "")?.let { return@withContext it }
                    }
                }
            } catch (_: Exception) { /* 换下一条线路 */ }
        }
        null
    }

    private fun parse(json: String): UpdateInfo? = try {
        val o = Json.parseToJsonElement(json).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.content
        val vc = s("versionCode")?.toIntOrNull() ?: return null
        val vn = s("versionName") ?: return null
        val url = s("apkUrl") ?: return null
        UpdateInfo(vc, vn, url, s("sha256") ?: "", s("notes") ?: "")
    } catch (_: Exception) {
        null
    }

    private fun markChecked() {
        context.getSharedPreferences("ldr", Context.MODE_PRIVATE)
            .edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- 通知 ----------

    private fun channel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        channel()
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
    }

    private fun progressNotification(pct: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                4001,
                baseBuilder()
                    .setContentTitle("正在下载更新")
                    .setProgress(100, pct, pct == 0)
                    .setOngoing(true)
                    .build()
            )
        }
    }

    private fun readyNotification(info: UpdateInfo) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(4001)
            nm.notify(
                4002,
                baseBuilder()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("v${info.versionName} 已就绪")
                    .setContentText("打开应用完成安装")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                id, baseBuilder().setContentTitle(title).setContentText(text)
                    .setAutoCancel(true).build()
            )
        }
    }

    companion object {
        private const val LATEST_URL =
            "https://raw.githubusercontent.com/Mengpolar/LDRLife/main/latest.json"
        private const val LAST_CHECK = "update_last_check"
        private const val CHANNEL = "update"

        // 加速镜像优先，直连兜底（空串 = 不加前缀直连 raw/github）
        private val MIRRORS = listOf(
            "https://gh-proxy.org/",
            "https://ghfast.top/",
            "",
        )
    }
}
