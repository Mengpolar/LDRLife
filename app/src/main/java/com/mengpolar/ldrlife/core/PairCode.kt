package com.mengpolar.ldrlife.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * PairCode：由设备唯一 ID 确定性生成 7 位配对码（6 位主体 + 1 位 Luhn 校验位）。
 * 与服务器端 luhnCheckDigit 逻辑完全一致（见 server/httpx.go）。
 */
object PairCode {

    private const val SALT = "LDRLIFE-PAIR-V1"

    /** 由 deviceUUID 生成完整 7 位配对码，如 4382017 */
    fun fromUUID(uuid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((uuid + SALT).toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (digest[i].toLong() and 0xFF)
        }
        // 无符号取模：8 字节摘要拼出的 Long 可能是负数，带符号 % 会产生负数/含 '-' 的码
        val six = "%06d".format(java.lang.Long.remainderUnsigned(v, 1_000_000L))
        return luhnCheckDigit(six)
    }

    /** 显示格式：438 2017 */
    fun display(seven: String): String =
        if (seven.length == 7) "${seven.substring(0, 3)} ${seven.substring(3)}" else seven

    fun luhnCheckDigit(six: String): String {
        var sum = 0
        for (i in 5 downTo 0) {
            var d = six[i] - '0'
            if ((5 - i) % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return six + ((10 - sum % 10) % 10).toString()
    }

    fun isValid(seven: String): Boolean {
        if (seven.length != 7 || seven.any { it !in '0'..'9' }) return false
        return luhnCheckDigit(seven.substring(0, 6)) == seven
    }

    fun newUUID(): String = UUID.randomUUID().toString()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
