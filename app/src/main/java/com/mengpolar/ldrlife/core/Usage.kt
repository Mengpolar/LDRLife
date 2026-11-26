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

    private fun excluded(pkg: String): Boolean {
        val p = pkg.lowercase()
        return EXCLUDE.any { p.startsWith(it.lowercase()) } ||
            p.contains("launcher") || p.contains("lawnchair") || p.contains("inputmethod")
    }

    private fun labelOf(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /**
     * 正在使用判定（两级）：
     * 1) 事件窗口 10 分钟：最后一个 RESUMED 且未被 PAUSED 覆盖的应用；
     *    （旧版只看 2 分钟窗口——停留在同一 App 超过 2 分钟时窗口内无事件，会误报"未在使用"）
     * 2) 兜底 queryUsageStats：lastTimeUsed 最新且距今 < 2 分钟的应用。
     * 返回 (包名, 展示名)；无/被排除返回 null
     */
    fun currentForegroundApp(ctx: Context): Pair<String, String>? {
        if (!hasPermission(ctx)) return null
        val um = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()

        // 1) 事件流：跟踪最后一个前台事件
        run {
            val events = um.queryEvents(now - 10 * 60 * 1000, now)
            var lastPkg: String? = null
            var active = false
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> { lastPkg = e.packageName; active = true }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> active = false
                }
            }
            val pkg = if (active) lastPkg else null
            if (pkg != null && !excluded(pkg)) return pkg to labelOf(ctx, pkg)
        }

        // 2) 兜底：停留同一 App 场景（窗口内无事件）看 lastTimeUsed
        run {
            val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600 * 1000, now)
            val best = stats.filter { !excluded(it.packageName) }.maxByOrNull { it.lastTimeUsed } ?: return null
            if (now - best.lastTimeUsed < 2 * 60 * 1000) return best.packageName to labelOf(ctx, best.packageName)
        }
        return null
    }
}
