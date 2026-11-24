package com.mengpolar.ldrlife.core

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

object B64 {
    fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

@Serializable
data class IdentityData(
    val uuid: String,
    val xPub: String,
    val xPriv: String,
    val ePub: String,
    val ePriv: String,
)

class Identity(val data: IdentityData) {
    val uuid: String = data.uuid
    val xPubBytes: ByteArray = B64.dec(data.xPub)
    val xPrivBytes: ByteArray = B64.dec(data.xPriv)
    val ePubBytes: ByteArray = B64.dec(data.ePub)
    val ePrivBytes: ByteArray = B64.dec(data.ePriv)

    /** 本机配对码（由设备唯一 ID 确定性生成） */
    val pairCode: String = PairCode.fromUUID(uuid)
}

/**
 * 设备身份：UUIDv4 + X25519（E2E 加密）+ Ed25519（签名防冒名）。
 * 私钥经 AndroidKeyStore AES-GCM 包裹后落盘；重装 App = 新设备。
 */
object IdentityManager {

    private const val PREF_KEY = "identity_wrapped"

    fun loadOrCreate(prefs: SharedPreferences): Identity {
        val blob = prefs.getString(PREF_KEY, null)
        if (blob != null) {
            try {
                val json = String(KeystoreBox.unwrap(blob), Charsets.UTF_8)
                return Identity(Json.decodeFromString(IdentityData.serializer(), json))
            } catch (_: Exception) {
                // 密钥损坏（如换机恢复失败）→ 重新生成，重新配对
            }
        }
        val id = generate()
        save(prefs, id)
        return id
    }

    fun save(prefs: SharedPreferences, id: Identity) {
        val json = Json.encodeToString(IdentityData.serializer(), id.data)
        prefs.edit().putString(PREF_KEY, KeystoreBox.wrap(json.toByteArray(Charsets.UTF_8))).apply()
    }

    fun generate(): Identity {
        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(SecureRandom()))
        val xkp = xGen.generateKeyPair()
        val xPub = (xkp.public as X25519PublicKeyParameters).encoded
        val xPriv = (xkp.private as X25519PrivateKeyParameters).encoded

        val eGen = Ed25519KeyPairGenerator()
        eGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val ekp = eGen.generateKeyPair()
        val ePk = (ekp.public as Ed25519PublicKeyParameters).encoded
        val eSk = (ekp.private as Ed25519PrivateKeyParameters).encoded

        return Identity(
            IdentityData(
                uuid = PairCode.newUUID(),
                xPub = B64.enc(xPub),
                xPriv = B64.enc(xPriv),
                ePub = B64.enc(ePk),
                ePriv = B64.enc(eSk),
            )
        )
    }
}
