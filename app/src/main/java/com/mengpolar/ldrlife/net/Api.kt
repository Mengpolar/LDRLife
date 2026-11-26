package com.mengpolar.ldrlife.net

import com.mengpolar.ldrlife.core.B64
import com.mengpolar.ldrlife.core.Crypto
import com.mengpolar.ldrlife.core.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

data class ServerConfig(val url: String, val gate: String) {
    /** 服务器基础地址：https://host:port/{gate} */
    val base: String get() = url.trim().trimEnd('/') + "/" + gate.trim()
}

/**
 * REST 客户端。所有需要设备身份校验的接口通过请求头携带 Ed25519 签名：
 *   X-Ldr-Ts / X-Ldr-Sig（签名对象："LDRLIFE1|auth|uuid|method|path|ts"，path 为门禁剥离后的 /v1/...）
 */
class Api(private val cfgProvider: () -> ServerConfig?, private val identity: Identity) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun post(rel: String, query: String = "", body: JsonObject, auth: Boolean): JsonObject =
        withContext(Dispatchers.IO) {
            val cfg = cfgProvider() ?: throw ApiException(-1, "未配置服务器")
            val url = cfg.base + rel + query
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (auth) addAuth(req, "POST", rel)
            client.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(resp.code, text)
                json.parseToJsonElement(text).jsonObject
            }
        }

    private suspend fun get(rel: String, query: String, auth: Boolean): JsonObject =
        withContext(Dispatchers.IO) {
            val cfg = cfgProvider() ?: throw ApiException(-1, "未配置服务器")
            val url = cfg.base + rel + query
            val req = Request.Builder().url(url).get()
            if (auth) addAuth(req, "GET", rel)
            client.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(resp.code, text)
                json.parseToJsonElement(text).jsonObject
            }
        }

    private fun addAuth(b: Request.Builder, method: String, rel: String) {
        val ts = System.currentTimeMillis() / 1000
        val msg = Crypto.canonAuth(identity.uuid, method, rel, ts)
        b.header("X-Ldr-Ts", ts.toString())
        b.header("X-Ldr-Sig", B64.enc(Crypto.sign(identity.ePrivBytes, msg.toByteArray(Charsets.UTF_8))))
    }

    // ---------- 接口 ----------

    suspend fun register(): JsonObject {
        val xB64 = B64.enc(identity.xPubBytes)
        val eB64 = B64.enc(identity.ePubBytes)
        val sig = Crypto.sign(identity.ePrivBytes, Crypto.canonRegister(identity.uuid, identity.pairCode, xB64, eB64).toByteArray(Charsets.UTF_8))
        return post("/v1/register", body = buildJsonObject {
            put("uuid", identity.uuid)
            put("code", identity.pairCode)
            put("x25519", xB64)
            put("ed25519", eB64)
            put("sig", B64.enc(sig))
        }, auth = false)
    }

    suspend fun pairRequest(code: String): Long {
        val xB64 = B64.enc(identity.xPubBytes)
        val eB64 = B64.enc(identity.ePubBytes)
        val sig = Crypto.sign(identity.ePrivBytes, Crypto.canonPairReq(code, identity.uuid, xB64, eB64).toByteArray(Charsets.UTF_8))
        val o = post("/v1/pair/request", body = buildJsonObject {
            put("code", code)
            put("from_uuid", identity.uuid)
            put("x25519", xB64)
            put("ed25519", eB64)
            put("sig", B64.enc(sig))
        }, auth = false)
        return o["id"]?.jsonPrimitive?.long ?: -1L
    }

    suspend fun pairPending(): JsonArray =
        get("/v1/pair/pending", "?uuid=${identity.uuid}", auth = true)["requests"] as JsonArray

    suspend fun pairDecision(requestId: Long, approve: Boolean): JsonObject {
        val sig = Crypto.sign(identity.ePrivBytes, Crypto.canonPairDec(requestId, identity.uuid, approve).toByteArray(Charsets.UTF_8))
        return post("/v1/pair/decision", body = buildJsonObject {
            put("uuid", identity.uuid)
            put("request_id", requestId)
            put("approve", approve)
            put("sig", B64.enc(sig))
        }, auth = false)
    }

    suspend fun unbind(): JsonObject =
        post("/v1/pair/unbind", query = "?uuid=${identity.uuid}", body = buildJsonObject { }, auth = true)

    /** 撤销自己的 pending 配对申请（404 = 已被对方处理，按取消完成对待） */
    suspend fun pairCancel(requestId: Long): JsonObject =
        post("/v1/pair/cancel", query = "?uuid=${identity.uuid}", body = buildJsonObject {
            put("request_id", requestId)
        }, auth = true)

    /** 配对状态对账：paired_with 与本地 partner 不一致 → 对方已解绑 */
    suspend fun pairStatus(): JsonObject =
        get("/v1/pair/status", "?uuid=${identity.uuid}", auth = true)

    suspend fun envelopePost(to: String, hint: String, seq: Long, ctWire: String): Long {
        val o = post("/v1/envelope", body = buildJsonObject {
            put("from", identity.uuid)
            put("to", to)
            put("type_hint", hint)
            put("seq", seq)
            put("ct", ctWire)
        }, auth = true)
        return o["id"]?.jsonPrimitive?.long ?: -1L
    }

    suspend fun envelopePending(): JsonArray =
        get("/v1/envelope/pending", "?uuid=${identity.uuid}", auth = true)["envelopes"] as JsonArray

    suspend fun envelopeAck(ids: List<Long>) {
        post("/v1/envelope/ack", body = buildJsonObject {
            put("uuid", identity.uuid)
            put("ids", JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }, auth = true)
    }
}
