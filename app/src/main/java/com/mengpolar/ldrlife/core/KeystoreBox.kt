package com.mengpolar.ldrlife.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeystoreBox：用 AndroidKeyStore 的 AES-GCM 密钥包裹/解包敏感数据。
 * 私钥等机密信息永不以明文落盘。
 */
object KeystoreBox {

    private const val ALIAS = "ldr_master_aes"
    private const val IV_LEN = 12

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) {
            val entry = ks.getEntry(ALIAS, null)
            if (entry is KeyStore.SecretKeyEntry) return entry.secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(spec)
        return kg.generateKey()
    }

    /** 加密并返回 base64(iv || ciphertext) */
    fun wrap(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = c.iv
        require(iv.size == IV_LEN)
        val ct = c.doFinal(plain)
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun unwrap(blob: String): ByteArray {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    fun randomToken(len: Int): ByteArray {
        val b = ByteArray(len)
        java.security.SecureRandom().nextBytes(b)
        return b
    }
}
