package com.mengpolar.ldrlife.net

import com.mengpolar.ldrlife.core.B64
import com.mengpolar.ldrlife.core.Crypto
import com.mengpolar.ldrlife.core.Identity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket 长连接：接收配对申请推送 / 密文信封推送；上行仅 ack。
 * 断线自动重连（指数退避，上限 60s）；OkHttp 层 20s ping 对应服务器 90s 读超时。
 */
class WsClient(
    private val cfgProvider: () -> ServerConfig?,
    private val identity: Identity,
    private val onPairRequest: (id: Long, fromUuid: String, xB64: String, eB64: String) -> Unit,
    private val onEnvelope: (id: Long, from: String, hint: String, seq: Long, ct: String) -> Unit,
    private val onUnpaired: () -> Unit,
    private val onState: (online: Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var shouldRun = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var backoffSec = 1L
    private var closed = CompletableDeferred<Unit>()

    fun start() {
        if (shouldRun) return
        shouldRun = true
        scope.launch { loop() }
    }

    fun stop() {
        shouldRun = false
        ws?.close(1000, "bye")
        scope.cancel()
    }

    private suspend fun loop() {
        while (shouldRun) {
            val cfg = cfgProvider()
            if (cfg == null) {
                delay(2000)
                continue
            }
            closed = CompletableDeferred()
            try {
                val ts = System.currentTimeMillis() / 1000
                val msg = Crypto.canonAuth(identity.uuid, "GET", "/v1/ws", ts)
                val req = Request.Builder()
                    .url(cfg.base + "/v1/ws?uuid=" + identity.uuid)
                    .header("X-Ldr-Ts", ts.toString())
                    .header("X-Ldr-Sig", B64.enc(Crypto.sign(identity.ePrivBytes, msg.toByteArray(Charsets.UTF_8))))
                    .build()
                ws = client.newWebSocket(req, listener)
                closed.await()
            } catch (_: Exception) {
                onState(false)
            }
            if (shouldRun) {
                delay(backoffSec * 1000)
                backoffSec = (backoffSec * 2).coerceAtMost(60)
            }
        }
    }

    fun sendAck(ids: List<Long>) {
        if (ids.isEmpty()) return
        ws?.send("""{"t":"ack","ids":[${ids.joinToString(",")}]}""")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffSec = 1
            onState(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = json.parseToJsonElement(text).jsonObject
                when (o["t"]?.jsonPrimitive?.content) {
                    "pair_request" -> onPairRequest(
                        o["id"]!!.jsonPrimitive.long,
                        o["from_uuid"]!!.jsonPrimitive.content,
                        o["x25519"]!!.jsonPrimitive.content,
                        o["ed25519"]!!.jsonPrimitive.content,
                    )
                    "envelope" -> onEnvelope(
                        o["id"]!!.jsonPrimitive.long,
                        o["from"]!!.jsonPrimitive.content,
                        o["type_hint"]?.jsonPrimitive?.content ?: "msg",
                        o["seq"]?.jsonPrimitive?.long ?: 0L,
                        o["ct"]!!.jsonPrimitive.content,
                    )
                    "unpaired" -> onUnpaired()
                }
            } catch (_: Exception) {
                // 服务器仅推送结构化 JSON；解析失败静默丢弃
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
            onState(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
            onState(false)
        }
    }
}
