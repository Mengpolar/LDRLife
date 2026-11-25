package com.mengpolar.ldrlife.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 振动预设与播放（设计文档 §8）。
 * 编码：events = [(startMs, durationMs, amplitude01)]，repeat 次数。
 * Android 渲染：VibrationEffect.createWaveform（API 26+，幅度 1-255）。
 * "频率感"：短脉冲占空比近似（如 30ms 开/10ms 关 ≈ 高频嗡嗡），幅度控制强弱。
 */
object Vibe {

    data class Event(val at: Long, val dur: Long, val amp: Double)
    data class Pattern(val id: String, val name: String, val emoji: String, val events: List<Event>, val repeat: Int)

    val HEARTBEAT = Pattern("heartbeat", "心跳", "💓", listOf(
        Event(0, 120, 0.9), Event(280, 180, 0.65),
        Event(800, 120, 0.9), Event(1080, 180, 0.65),
        Event(1600, 120, 0.9), Event(1880, 180, 0.65),
        Event(2400, 120, 0.9), Event(2680, 180, 0.65),
    ), 1)

    val BUZZ = Pattern("buzz", "高频嗡嗡", "🐝", listOf(
        Event(0, 30, 1.0), Event(40, 30, 1.0), Event(80, 30, 1.0), Event(120, 30, 1.0),
        Event(160, 30, 1.0), Event(200, 30, 1.0), Event(240, 30, 1.0), Event(280, 30, 1.0),
        Event(320, 30, 1.0), Event(360, 30, 1.0), Event(400, 30, 1.0), Event(440, 30, 1.0),
        Event(480, 30, 1.0), Event(520, 30, 1.0), Event(560, 30, 1.0), Event(600, 30, 1.0),
        Event(640, 30, 1.0), Event(680, 30, 1.0), Event(720, 30, 1.0), Event(760, 30, 1.0),
        Event(800, 30, 1.0), Event(840, 30, 1.0), Event(880, 30, 1.0), Event(920, 30, 1.0),
        Event(960, 30, 1.0), Event(1000, 30, 1.0), Event(1040, 30, 1.0), Event(1080, 30, 1.0),
        Event(1120, 30, 1.0), Event(1160, 30, 1.0), Event(1200, 30, 1.0), Event(1240, 30, 1.0),
        Event(1280, 30, 1.0), Event(1320, 30, 1.0), Event(1360, 30, 1.0), Event(1400, 30, 1.0),
        Event(1440, 30, 1.0), Event(1480, 30, 1.0),
    ), 1)

    val TICKLE = Pattern("tickle", "轻挠", "🪶", listOf(
        Event(0, 40, 0.25), Event(200, 40, 0.25), Event(400, 40, 0.25),
        Event(600, 40, 0.25), Event(800, 40, 0.25), Event(1000, 40, 0.25),
    ), 1)

    val HUG = Pattern("hug", "长抱", "🤗", listOf(Event(0, 2000, 1.0)), 1)

    val PRESETS = listOf(HEARTBEAT, BUZZ, TICKLE, HUG)

    fun byId(id: String): Pattern? = PRESETS.firstOrNull { it.id == id }

    // ---------- 摩斯码（文字 → 节奏，彩蛋） ----------

    private val MORSE = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---",
        '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.",
    )

    /** 文字转摩斯振动 Pattern（仅字母/数字，其余跳过） */
    fun morseOf(text: String, dotMs: Long = 80): Pattern {
        val unit = dotMs
        val events = mutableListOf<Event>()
        var t = 0L
        text.uppercase().forEach { ch ->
            val code = MORSE[ch] ?: return@forEach
            code.forEach { sym ->
                val dur = if (sym == '.') unit else unit * 3
                events.add(Event(t, dur, 1.0))
                t += dur + unit // 符号间隔
            }
            t += unit * 2 // 字母间隔
        }
        return Pattern("morse", "摩斯", "📱", events, 1)
    }

    // ---------- 播放 ----------

    private fun vibrator(ctx: Context): Vibrator =
        // API 31+ 用 VibratorManager；鸿蒙 2/3 等旧系统（API 26-30）只有传统 VIBRATOR_SERVICE，
        // 直接拿 manager 会拿到 null 强转崩溃 → 被 catch 吞掉表现为"永远不振动"
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun toWaveform(p: Pattern): VibrationEffect {
        val timings = mutableListOf<Long>()
        val amps = mutableListOf<Int>()
        var lastEnd = 0L
        p.events.forEach { e ->
            if (e.at > lastEnd) { // 间隙 = 关
                timings.add(e.at - lastEnd); amps.add(0)
            }
            timings.add(e.dur); amps.add((e.amp * 255).toInt().coerceIn(1, 255))
            lastEnd = e.at + e.dur
        }
        return VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1)
    }

    /** 前台/任意时刻直接播放完整图案（API 26+） */
    fun play(ctx: Context, p: Pattern) {
        try {
            vibrator(ctx).vibrate(toWaveform(p))
        } catch (_: Exception) {
        }
    }

    /** 后台场景：高优先级通知（渠道自带心跳振动图案） */
    fun notifyHaptic(ctx: Context, presetId: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("haptic", "想你了", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120, 160, 180, 160, 180, 160, 180, 160, 180)
            }
        )
        val preset = byId(presetId)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "haptic")
            .setContentTitle("TA 想你了 ${preset?.emoji ?: "💓"}")
            .setContentText("打开倾卿，回一个")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setAutoCancel(true)
            .build()
        nm.notify(3001, n)
    }
}
