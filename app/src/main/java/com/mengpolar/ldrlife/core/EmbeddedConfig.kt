package com.mengpolar.ldrlife.core

import android.util.Base64
import com.mengpolar.ldrlife.BuildConfig
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class EmbeddedConfig(val url: String, val gate: String)

/**
 * 构建时打包的加密服务器配置（AES-GCM；密钥以两个异或半片分散在 BuildConfig 中）。
 * App 启动时自动解密并连接服务器，用户无需手动输入。
 *
 * 诚实说明：该配置打包在公开 APK 内，属"混淆级"保护——防扫描脚本与字符串 grep，
 * 不防有意逆向。真正的安全层是：端到端加密 + 配对审批 + 安全码比对 + 服务器限流。
 * 门禁若泄露，更换服务器 config.toml 的 gate 并发版即可。
 */
object EmbeddedConfigLoader {

    fun load(): EmbeddedConfig? {
        val enc = BuildConfig.LDR_CONFIG_ENC
        val a = BuildConfig.LDR_KEY_A
        val b = BuildConfig.LDR_KEY_B
        if (enc.isBlank() || a.isBlank() || b.isBlank()) return null
        return try {
            val ka = hexBytes(a)
            val kb = hexBytes(b)
            val key = ByteArray(32) { i -> (ka[i].toInt() xor kb[i].toInt()).toByte() }
            val data = hexBytes(enc)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            val plain = String(c.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
            val o = Json.parseToJsonElement(plain).jsonObject
            EmbeddedConfig(
                url = o["url"]!!.jsonPrimitive.content,
                gate = o["gate"]!!.jsonPrimitive.content,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
