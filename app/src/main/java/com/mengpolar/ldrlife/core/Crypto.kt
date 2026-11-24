package com.mengpolar.ldrlife.core

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto：全部加密原语使用 BouncyCastle（纯 Java、经审计实现），不手搓密码学。
 *
 * 与服务器协议约定（见 docs/design.md §5）：
 *  - 签名：Ed25519，规范串前缀 "LDRLIFE1"
 *  - 密钥交换：X25519 三重 DH → HKDF-SHA256 派生双向会话密钥
 *  - AEAD：ChaCha20-Poly1305 (IETF 96-bit nonce)，nonce = 会话随机前缀 4B + 消息计数器 8B
 *    （与设计文档 §5.2 MVP 的 nonce 约定一致；升级到哈希棘轮时再换 XChaCha 随机 nonce）
 */
object Crypto {

    const val CANON = "LDRLIFE1"
    const val KEY_LEN = 32
    const val SIG_LEN = 64
    const val NONCE_LEN = 12 // 4B 随机前缀 + 8B 计数器

    // ---------- 规范串（与 server/auth.go 完全一致） ----------

    fun canonRegister(uuid: String, code: String, xB64: String, eB64: String): String =
        "$CANON|register|$uuid|$code|$xB64|$eB64"

    fun canonPairReq(code: String, fromUuid: String, xB64: String, eB64: String): String =
        "$CANON|pairreq|$code|$fromUuid|$xB64|$eB64"

    fun canonPairDec(requestId: Long, uuid: String, approve: Boolean): String =
        "$CANON|pairdec|$requestId|$uuid|${if (approve) "1" else "0"}"

    fun canonAuth(uuid: String, method: String, path: String, ts: Long): String =
        "$CANON|auth|$uuid|$method|$path|$ts"

    fun canonPairEph(uuid: String, ephB64: String): String =
        "$CANON|pair|$uuid|$ephB64"

    fun canonMsgAad(senderUuid: String, recipientUuid: String, seq: Long): ByteArray =
        "$CANON|msg|$senderUuid|$recipientUuid|$seq".toByteArray(Charsets.UTF_8)

    // ---------- Ed25519 ----------

    fun sign(ePrivBytes: ByteArray, msg: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(ePrivBytes, 0))
        signer.update(msg, 0, msg.size)
        return signer.generateSignature()
    }

    fun verify(ePubBytes: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != SIG_LEN) return false
        return try {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(ePubBytes, 0))
            v.update(msg, 0, msg.size)
            v.verifySignature(sig)
        } catch (_: Exception) {
            false
        }
    }

    // ---------- X25519 ----------

    fun x25519(privBytes: ByteArray, pubBytes: ByteArray): ByteArray {
        val agr = org.bouncycastle.crypto.agreement.X25519Agreement()
        agr.init(X25519PrivateKeyParameters(privBytes, 0))
        val out = ByteArray(KEY_LEN)
        agr.calculateAgreement(X25519PublicKeyParameters(pubBytes, 0), out, 0)
        return out
    }

    // ---------- HKDF-SHA256 (RFC 5869) ----------

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val macKey = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(macKey, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(32, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ---------- AEAD：ChaCha20-Poly1305 ----------

    fun seal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        val m = c.doFinal(out, n)
        return out.copyOf(n + m)
    }

    /** 解密失败（标签校验不过/重放）抛 org.bouncycastle.crypto.InvalidCipherTextException */
    fun open(key: ByteArray, nonce12: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        val n = c.processBytes(ct, 0, ct.size, out, 0)
        val m = c.doFinal(out, n)
        return out.copyOf(n + m)
    }

    /** wire 格式：base64( nonce12 || ciphertext||tag )；nonce = prefix4 + seq8BE */
    fun sealWire(key: ByteArray, noncePrefix4: ByteArray, seq: Long, aad: ByteArray, plaintext: ByteArray): String {
        val nonce = ByteArray(NONCE_LEN)
        System.arraycopy(noncePrefix4, 0, nonce, 0, 4)
        putLongBE(nonce, 4, seq)
        val ct = seal(key, nonce, plaintext, aad)
        return B64.enc(nonce + ct)
    }

    /** seq 必须与发送侧一致（来自信封外层字段）；不一致/重放/篡改 → 解密失败或序号校验拒绝 */
    fun openWire(key: ByteArray, wire: String, seq: Long, aad: ByteArray): ByteArray {
        val data = B64.dec(wire)
        if (data.size < NONCE_LEN + 16) throw IllegalArgumentException("ct too short")
        if (getLongBE(data, 4) != seq) throw IllegalArgumentException("nonce/seq mismatch")
        return open(key, data.copyOfRange(0, NONCE_LEN), data.copyOfRange(NONCE_LEN, data.size), aad)
    }

    // ---------- 会话密钥派生 ----------

    /**
     * 三重 DH。isA 由双方身份公钥字典序决定，保证两端拼接顺序一致：
     *   A: DH(ephA,ephB) || DH(idA,ephB) || DH(ephA,idB)
     *   B: DH(ephB,ephA) || DH(ephB,idA) || DH(idB,ephA)
     */
    fun tripleDH(isA: Boolean, idPriv: ByteArray, ephPriv: ByteArray, peerIdPub: ByteArray, peerEphPub: ByteArray): ByteArray {
        val s1 = x25519(ephPriv, peerEphPub)
        val s2: ByteArray
        val s3: ByteArray
        if (isA) {
            s2 = x25519(idPriv, peerEphPub)
            s3 = x25519(ephPriv, peerIdPub)
        } else {
            s2 = x25519(ephPriv, peerIdPub)
            s3 = x25519(idPriv, peerEphPub)
        }
        return s1 + s2 + s3
    }

    /** 由 96B 共享秘密派生 [sendKey, recvKey, sendPrefix4, recvPrefix4]；iAmA 决定方向对应 */
    fun deriveSession(shared96: ByteArray, myPub: ByteArray, peerPub: ByteArray, iAmA: Boolean): SessionKeys {
        val sorted = if (lexCompare(myPub, peerPub) <= 0) myPub + peerPub else peerPub + myPub
        val okm = hkdfSha256(shared96, ByteArray(32), sorted, 72)
        val kA2B = okm.copyOfRange(0, 32)
        val kB2A = okm.copyOfRange(32, 64)
        val prefA = okm.copyOfRange(64, 68)
        val prefB = okm.copyOfRange(68, 72)
        return if (iAmA) {
            SessionKeys(send = kA2B, recv = kB2A, sendPrefix = prefA, recvPrefix = prefB)
        } else {
            SessionKeys(send = kB2A, recv = kA2B, sendPrefix = prefB, recvPrefix = prefA)
        }
    }

    /** 字典序比较（-1/0/1） */
    fun lexCompare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return if (d < 0) -1 else 1
        }
        return when {
            a.size < b.size -> -1
            a.size > b.size -> 1
            else -> 0
        }
    }

    // ---------- 安全码 ----------

    /** 12 位 hex，分 3 组显示；双方一致 = 无中间人 */
    fun safetyCode(aPub: ByteArray, bPub: ByteArray): String {
        val (first, second) = if (lexCompare(aPub, bPub) <= 0) aPub to bPub else bPub to aPub
        val h = MessageDigest.getInstance("SHA-256").digest(first + second)
        val hex = h.joinToString("") { "%02x".format(it) }
        val s = hex.substring(0, 12)
        return "${s.substring(0, 4)} ${s.substring(4, 8)} ${s.substring(8, 12)}"
    }

    // ---------- 工具 ----------

    fun putLongBE(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) {
            dst[off + i] = (v ushr (8 * (7 - i))).toByte()
        }
    }

    fun getLongBE(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        }
        return v
    }
}

/** 会话密钥（发送/接收两个方向）；持久化前需经 KeystoreBox 包裹 */
data class SessionKeys(
    val send: ByteArray,
    val recv: ByteArray,
    val sendPrefix: ByteArray, // 4B 随机 nonce 前缀
    val recvPrefix: ByteArray,
)
