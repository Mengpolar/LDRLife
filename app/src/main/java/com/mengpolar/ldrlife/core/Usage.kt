package com.mengpolar.ldrlife.core

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * 应用使用状态（设计文档 §7）：
 * - PACKAGE_USAGE_STATS 特殊权限（设置页手动授权，代码引导跳转）
 * - "正在使用"：最近 2 分钟 UsageEvents 最后一个 MOVE_TO_FOREGROUND
 * - 默认排除：系统 UI/输入法/桌面/本 App
 * - 隐私红线：只上报当前一条，不留历史
 */
object Usage {

    private val EXCLUDE = listOf(
        "com.mengpolar.ldrlife", "com.android.systemui", "com.android.launcher",
    )

    fun hasPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), ctx.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    private fun excluded(pkg: String): Boolean =
        EXCLUDE.any { pkg.startsWith(it) } || pkg.contains("launcher") || pkg.contains("inputmethod")

    private fun labelOf(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** 正在使用：最近 2 分钟最后一个前台事件的应用 → (包名, 展示名)；无/被排除返回 null */
    fun currentForegroundApp(ctx: Context): Pair<String, String>? {
        if (!hasPermission(ctx)) return null
        val um = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - 2 * 60 * 1000, now)
        var lastPkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPkg = e.packageName
        }
        val pkg = lastPkg ?: return null
        if (excluded(pkg)) return null
        return pkg to labelOf(ctx, pkg)
    }
}
