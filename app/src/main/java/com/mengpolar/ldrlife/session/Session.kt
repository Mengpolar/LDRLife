package com.mengpolar.ldrlife.session

import android.content.Context
import android.content.SharedPreferences
import com.mengpolar.ldrlife.core.B64
import com.mengpolar.ldrlife.core.Crypto
import com.mengpolar.ldrlife.core.EmbeddedConfigLoader
import com.mengpolar.ldrlife.core.Geo
import com.mengpolar.ldrlife.core.KeystoreBox
import com.mengpolar.ldrlife.core.Identity
import com.mengpolar.ldrlife.core.IdentityManager
import com.mengpolar.ldrlife.core.LocationProvider
import com.mengpolar.ldrlife.core.PairCode
import com.mengpolar.ldrlife.core.Usage
import com.mengpolar.ldrlife.core.Vibe
import com.mengpolar.ldrlife.data.AppDb
import com.mengpolar.ldrlife.data.DbFactory
import com.mengpolar.ldrlife.data.MSG_DELIVERED
import com.mengpolar.ldrlife.data.MSG_EXPIRED
import com.mengpolar.ldrlife.data.MSG_READ
import com.mengpolar.ldrlife.data.MSG_SENT
import com.mengpolar.ldrlife.data.MSG_SENDING
import com.mengpolar.ldrlife.data.MessageDao
import com.mengpolar.ldrlife.data.MessageEntity
import com.mengpolar.ldrlife.data.TTL_MS
import com.mengpolar.ldrlife.net.Api
import com.mengpolar.ldrlife.net.ServerConfig
import com.mengpolar.ldrlife.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

enum class Phase { UNCONFIGURED, REGISTERING, UNPAIRED, PAIRED }
enum class ConnState { OFFLINE, ONLINE }

@Serializable
data class PartnerData(
    val uuid: String,
    val xPub: String,
    val ePub: String,
) {
    val xPubBytes: ByteArray get() = B64.dec(xPub)
    val ePubBytes: ByteArray get() = B64.dec(ePub)
}

@Serializable
data class SessionKeysData(
    val send: String,
    val recv: String,
    val sendPrefix: String,
    val recvPrefix: String,
    val sendCounter: Long,
    val recvCounter: Long,
)

@Serializable
data class PartnerUsage(
    val app: String,
    val ts: Long,
)

@Serializable
data class PartnerLoc(
    val lat: Double,
    val lon: Double,
    @SerialName("precisionLabel") val precisionLabel: String,
    val ts: Long,
)

data class IncomingRequest(
    val id: Long,
    val fromUuid: String,
    val xB64: String,
    val eB64: String,
)

/**
 * 全局会话状态机：配置 → 注册 → 配对（申请/审批/临时公钥交换）→ E2E 会话 → 收发消息。
 * 服务器只见密文；一切明文状态只在两台设备本地。
 */
class Session(private val context: Context, private val prefs: SharedPreferences) {

    val identity: Identity = IdentityManager.loadOrCreate(prefs)

    /** 构建时打包的加密配置：非空则 App 自动连接服务器，用户零配置 */
    val embeddedConfig = EmbeddedConfigLoader.load()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.UNCONFIGURED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _conn = MutableStateFlow(ConnState.OFFLINE)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _partner = MutableStateFlow<PartnerData?>(null)
    val partner: StateFlow<PartnerData?> = _partner.asStateFlow()

    private val _incoming = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val incoming: StateFlow<List<IncomingRequest>> = _incoming.asStateFlow()

    private val _waiting = MutableStateFlow(false)
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _safety = MutableStateFlow<String?>(null)
    val safety: StateFlow<String?> = _safety.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _partnerLoc = MutableStateFlow<PartnerLoc?>(null)
    val partnerLoc: StateFlow<PartnerLoc?> = _partnerLoc.asStateFlow()

    private val _myLoc = MutableStateFlow<PartnerLoc?>(null)
    val myLoc: StateFlow<PartnerLoc?> = _myLoc.asStateFlow()

    private val _partnerUsage = MutableStateFlow<PartnerUsage?>(null)
    val partnerUsage: StateFlow<PartnerUsage?> = _partnerUsage.asStateFlow()

    /** 最近一次收到的"想你了"（UI 提示条 + 回一个） */
    private val _lastHaptic = MutableStateFlow<Vibe.Pattern?>(null)
    val lastHaptic: StateFlow<Vibe.Pattern?> = _lastHaptic.asStateFlow()

    private var api: Api? = null
    private var ws: WsClient? = null
    private var db: AppDb? = null

    private var keys: SessionKeysData? = null
    private var myEphPriv: ByteArray? = null
    private var myEphPub: ByteArray? = null
    private var partnerEph: ByteArray? = null

    val messagesFlow: Flow<List<MessageEntity>> by lazy { db().messages().observeAll() }

    private fun db(): AppDb {
        if (db == null) db = DbFactory.create(context, prefs)
        return db!!
    }

    private fun cfgProvider(): ServerConfig? {
        // 手动配置（兜底路径）优先；否则使用打包的加密配置
        if (prefs.getBoolean("manual_config", false)) {
            val url = prefs.getString("server_url", null) ?: return null
            val gate = prefs.getString("gate", null) ?: return null
            if (url.isNotBlank() && gate.isNotBlank()) return ServerConfig(url, gate)
            return null
        }
        embeddedConfig?.let { return ServerConfig(it.url, it.gate) }
        val url = prefs.getString("server_url", null) ?: return null
        val gate = prefs.getString("gate", null) ?: return null
        if (url.isBlank() || gate.isBlank()) return null
        return ServerConfig(url, gate)
    }

    fun serverConfig(): ServerConfig? = cfgProvider()

    fun retryRegister() {
        if (cfgProvider() != null && _phase.value == Phase.UNCONFIGURED) register()
    }

    /** 前台服务调用：确保 WS 连接存活（幂等） */
    fun ensureWs() {
        if (cfgProvider() != null) startWs()
    }

    // ---------- 启动与注册 ----------

    fun bootstrap() {
        try {
            prefs.getString("partner_loc", null)?.let {
                _partnerLoc.value = json.decodeFromString(PartnerLoc.serializer(), it)
            }
        } catch (_: Exception) {
        }
        try {
            prefs.getString("partner_wrapped", null)?.let { blob ->
                _partner.value = json.decodeFromString(
                    PartnerData.serializer(), String(KeystoreBox.unwrap(blob), Charsets.UTF_8)
                )
                _safety.value = Crypto.safetyCode(identity.xPubBytes, _partner.value!!.xPubBytes)
            }
            prefs.getString("session_wrapped", null)?.let { blob ->
                val d = json.decodeFromString(
                    SessionKeysData.serializer(), String(KeystoreBox.unwrap(blob), Charsets.UTF_8)
                )
                keys = d
                _ready.value = true
            }
        } catch (_: Exception) {
            // 包裹数据损坏 → 视为未配对，重新配对
            _partner.value = null
            keys = null
            _ready.value = false
        }
        if (cfgProvider() == null) {
            _phase.value = Phase.UNCONFIGURED
        } else {
            register()
        }
    }

    /** 仅兜底路径（无打包配置的构建）使用：手动填写服务器并记住为手动模式 */
    fun saveServer(url: String, gate: String) {
        prefs.edit()
            .putString("server_url", url.trim())
            .putString("gate", gate.trim())
            .putBoolean("manual_config", true)
            .apply()
        register()
    }

    private fun register() {
        _phase.value = Phase.REGISTERING
        _error.value = null
        scope.launch {
            try {
                val a = Api(::cfgProvider, identity)
                api = a
                a.register()
                _phase.value = if (_partner.value != null) Phase.PAIRED else Phase.UNPAIRED
                startWs()
                refreshIncoming()
            } catch (e: Exception) {
                _phase.value = Phase.UNCONFIGURED
                _error.value = "注册失败：${e.message}"
            }
        }
    }

    private fun startWs() {
        if (ws != null) return
        ws = WsClient(
            cfgProvider = ::cfgProvider,
            identity = identity,
            onPairRequest = { id, fromUuid, xB64, eB64 -> onPairRequest(id, fromUuid, xB64, eB64) },
            onEnvelope = { id, from, hint, seq, ct -> onEnvelope(id, from, hint, seq, ct) },
            onUnpaired = { onUnpaired() },
            onState = { online ->
                _conn.value = if (online) ConnState.ONLINE else ConnState.OFFLINE
                if (online) refreshIncoming()
            },
        )
        ws!!.start()
    }

    // ---------- 配对 ----------

    fun refreshIncoming() {
        sweepExpiry()
        val a = api ?: return
        scope.launch {
            try {
                val arr = a.pairPending()
                _incoming.value = arr.map { o ->
                    val obj = o.jsonObject
                    IncomingRequest(
                        id = obj["id"]!!.jsonPrimitive.long,
                        fromUuid = obj["from_uuid"]!!.jsonPrimitive.content,
                        xB64 = obj["x25519"]!!.jsonPrimitive.content,
                        eB64 = obj["ed25519"]!!.jsonPrimitive.content,
                    )
                }
                // 兜底拉取暂存信封（WS 断线期间的）；同批回传"已送达✓✓"回执
                val deliveredSeqs = mutableListOf<Long>()
                val envs = a.envelopePending()
                for (e in envs) {
                    val obj = e.jsonObject
                    val hint = obj["type_hint"]?.jsonPrimitive?.contentOrNull ?: "msg"
                    val handledSeq = onEnvelope(
                        obj["id"]!!.jsonPrimitive.long,
                        obj["from"]!!.jsonPrimitive.content,
                        hint,
                        obj["seq"]?.jsonPrimitive?.long ?: 0L,
                        obj["ct"]!!.jsonPrimitive.content,
                    )
                    if (hint == "msg" && handledSeq != null) deliveredSeqs.add(handledSeq)
                }
                if (deliveredSeqs.isNotEmpty()) sendReceipt("delivered", deliveredSeqs)
                resendPending()
            } catch (_: Exception) {
            }
        }
    }

    private fun onPairRequest(id: Long, fromUuid: String, xB64: String, eB64: String) {
        val list = _incoming.value.filterNot { it.id == id || it.fromUuid == fromUuid }
        _incoming.value = list + IncomingRequest(id, fromUuid, xB64, eB64)
    }

    fun sendPairRequest(code: String) {
        if (!PairCode.isValid(code)) {
            _error.value = "配对码格式不正确（7 位数字）"
            return
        }
        val a = api ?: run { _error.value = "尚未连接服务器"; return }
        scope.launch {
            _busy.value = true
            try {
                a.pairRequest(code)
                _error.value = null
                _waiting.value = true
            } catch (e: Exception) {
                _error.value = "发送申请失败：${e.message}"
            }
            _busy.value = false
        }
    }

    fun decide(id: Long, approve: Boolean) {
        val a = api ?: return
        scope.launch {
            try {
                val resp = a.pairDecision(id, approve)
                _incoming.value = _incoming.value.filterNot { it.id == id }
                if (approve) {
                    val peer = resp["peer"]?.jsonObject ?: return@launch
                    setPartner(
                        PartnerData(
                            uuid = peer["uuid"]!!.jsonPrimitive.content,
                            xPub = peer["x25519"]!!.jsonPrimitive.content,
                            ePub = peer["ed25519"]!!.jsonPrimitive.content,
                        )
                    )
                }
            } catch (e: Exception) {
                _error.value = "操作失败：${e.message}"
            }
        }
    }

    private fun setPartner(p: PartnerData) {
        _partner.value = p
        _safety.value = Crypto.safetyCode(identity.xPubBytes, p.xPubBytes)
        _waiting.value = false
        _phase.value = Phase.PAIRED
        _ready.value = false
        myEphPriv = null
        myEphPub = null
        partnerEph = null
        try {
            prefs.edit().putString(
                "partner_wrapped",
                KeystoreBox.wrap(json.encodeToString(PartnerData.serializer(), p).toByteArray(Charsets.UTF_8))
            ).apply()
        } catch (_: Exception) {
        }
        sendOwnEphemeral()
    }

    private fun newEphemeral() {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        myEphPriv = (kp.private as X25519PrivateKeyParameters).encoded
        myEphPub = (kp.public as X25519PublicKeyParameters).encoded
    }

    private fun sendOwnEphemeral() {
        val p = _partner.value ?: return
        val a = api ?: return
        if (myEphPriv == null) newEphemeral()
        val ephB64 = B64.enc(myEphPub!!)
        val sig = Crypto.sign(identity.ePrivBytes, Crypto.canonPairEph(identity.uuid, ephB64).toByteArray(Charsets.UTF_8))
        val inner = "{\"t\":\"pair_eph\",\"eph\":\"$ephB64\",\"sig\":\"${B64.enc(sig)}\"}"
        scope.launch {
            try {
                a.envelopePost(p.uuid, "pair", System.currentTimeMillis(), B64.enc(inner.toByteArray(Charsets.UTF_8)))
            } catch (_: Exception) {
            }
        }
    }

    private fun tryDerive() {
        val p = _partner.value ?: return
        val mePriv = myEphPriv ?: return
        val peer = partnerEph ?: return
        val iAmA = Crypto.lexCompare(identity.xPubBytes, p.xPubBytes) <= 0
        val shared = Crypto.tripleDH(iAmA, identity.xPrivBytes, mePriv, p.xPubBytes, peer)
        val k = Crypto.deriveSession(shared, identity.xPubBytes, p.xPubBytes, iAmA)
        val d = SessionKeysData(
            send = B64.enc(k.send), recv = B64.enc(k.recv),
            sendPrefix = B64.enc(k.sendPrefix), recvPrefix = B64.enc(k.recvPrefix),
            sendCounter = 0, recvCounter = 0,
        )
        keys = d
        _ready.value = true
        try {
            prefs.edit().putString(
                "session_wrapped",
                KeystoreBox.wrap(json.encodeToString(SessionKeysData.serializer(), d).toByteArray(Charsets.UTF_8))
            ).apply()
        } catch (_: Exception) {
        }
    }

    // ---------- 信封处理 ----------

    private fun onEnvelope(id: Long, from: String, hint: String, seq: Long, ct: String): Long? {
        val handled = when (hint) {
            "pair" -> { handlePairEnvelope(from, ct); null }
            "msg" -> handleMsgEnvelope(from, seq, ct)
            "read" -> handleReadEnvelope(from, seq, ct)
            "loc" -> handleLocEnvelope(from, seq, ct)
            "haptic" -> { android.util.Log.d("LDR", "收到 haptic 信封 seq=$seq"); handleHapticEnvelope(from, seq, ct); null }
            "usage" -> { android.util.Log.d("LDR", "收到 usage 信封 seq=$seq"); handleUsageEnvelope(from, seq, ct) }
            else -> null
        }
        if (hint == "msg" && handled != null) sendReceipt("delivered", listOf(handled))
        ws?.sendAck(listOf(id))
        return handled
    }

    private fun handlePairEnvelope(from: String, ct: String) {
        val o = try {
            json.parseToJsonElement(String(B64.dec(ct), Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            return
        }
        when (o["t"]?.jsonPrimitive?.contentOrNull) {
            "pair_approved" -> {
                // 解绑后重复配对：以最新为准
                setPartner(
                    PartnerData(
                        uuid = from,
                        xPub = o["peer_x25519"]!!.jsonPrimitive.content,
                        ePub = o["peer_ed25519"]!!.jsonPrimitive.content,
                    )
                )
            }
            "pair_eph" -> {
                val p = _partner.value ?: return
                if (from != p.uuid) return
                val ephB64 = o["eph"]?.jsonPrimitive?.contentOrNull ?: return
                val sigB64 = o["sig"]?.jsonPrimitive?.contentOrNull ?: return
                val ok = try {
                    Crypto.verify(p.ePubBytes, Crypto.canonPairEph(p.uuid, ephB64).toByteArray(Charsets.UTF_8), B64.dec(sigB64))
                } catch (_: Exception) {
                    false
                }
                if (!ok) return
                partnerEph = B64.dec(ephB64)
                tryDerive()
            }
        }
    }

    /** 处理普通消息；成功返回 seq（供调用方回传"已送达✓✓"），失败返回 null */
    private fun handleMsgEnvelope(from: String, seq: Long, ct: String): Long? {
        val p = _partner.value ?: return null
        val k = keys ?: return null
        if (from != p.uuid || seq <= k.recvCounter) return null
        try {
            val pt = Crypto.openWire(
                B64.dec(k.recv), ct, seq,
                Crypto.canonMsgAad(p.uuid, identity.uuid, seq)
            )
            val o = json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
            if (o["t"]?.jsonPrimitive?.contentOrNull == "msg") {
                scope.launch {
                    db().messages().insert(
                        MessageEntity(
                            seq = seq,
                            outgoing = false,
                            body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                            ts = o["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                            status = MSG_DELIVERED,
                        )
                    )
                }
            }
            updateCounters(recv = seq)
            return seq
        } catch (_: Exception) {
            // 解密失败（篡改/重放）→ 丢弃
            return null
        }
    }

    // ---------- 发送 ----------

    fun sendMessage(text: String) {
        val p = _partner.value ?: return
        val k = keys ?: run { _error.value = "安全会话尚未建立，请等待对方上线后自动完成"; return }
        if (text.isBlank()) return
        val dao: MessageDao = db().messages()
        val seq = k.sendCounter + 1
        scope.launch {
            val inner = "{\"t\":\"msg\",\"body\":${
                json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))
            },\"ts\":${System.currentTimeMillis()}}"
            val wire = Crypto.sealWire(
                B64.dec(k.send), B64.dec(k.sendPrefix), seq,
                Crypto.canonMsgAad(identity.uuid, p.uuid, seq),
                inner.toByteArray(Charsets.UTF_8),
            )
            // 密文与 seq 一并入库：失败进重发队列（重发同一密文，nonce 不复用问题不存在——是同一封信重投）
            val dbId = dao.insert(
                MessageEntity(
                    seq = seq, outgoing = true, body = text,
                    ts = System.currentTimeMillis(), status = MSG_SENDING, wire = wire,
                )
            )
            try {
                api?.envelopePost(p.uuid, "msg", seq, wire)
                updateCounters(send = seq)
                dao.updateStatus(dbId, MSG_SENT)
            } catch (e: Exception) {
                _error.value = "发送失败，已进入重发队列（恢复联网后自动补发）"
            }
        }
    }

    // ---------- 回执 / 重发 / 过期（M1） ----------

    /** 回执信封（delivered/read），与消息走同一 E2E 通道 */
    private fun sendReceipt(kind: String, seqs: List<Long>) {
        val p = _partner.value ?: return
        val k = keys ?: return
        if (seqs.isEmpty()) return
        scope.launch {
            val seq = k.sendCounter + 1
            val seqsJson = seqs.joinToString(",", "[", "]")
            val inner = "{\"t\":\"$kind\",\"seqs\":$seqsJson}"
            val wire = Crypto.sealWire(
                B64.dec(k.send), B64.dec(k.sendPrefix), seq,
                Crypto.canonMsgAad(identity.uuid, p.uuid, seq),
                inner.toByteArray(Charsets.UTF_8),
            )
            try {
                api?.envelopePost(p.uuid, "read", seq, wire)
                updateCounters(send = seq)
            } catch (_: Exception) {
            }
        }
    }

    /** 处理对方回执：delivered → ✓✓，read → 已读 */
    private fun handleReadEnvelope(from: String, seq: Long, ct: String): Long? {
        val p = _partner.value ?: return null
        val k = keys ?: return null
        if (from != p.uuid) return null
        return try {
            val pt = Crypto.openWire(
                B64.dec(k.recv), ct, seq,
                Crypto.canonMsgAad(p.uuid, identity.uuid, seq)
            )
            val o = json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
            val kind = o["t"]?.jsonPrimitive?.contentOrNull ?: return null
            val seqs = (o["seqs"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.long } ?: return null
            scope.launch {
                when (kind) {
                    "delivered" -> db().messages().upgradeBySeqs(seqs, MSG_DELIVERED)
                    "read" -> db().messages().upgradeBySeqs(seqs, MSG_READ)
                }
            }
            updateCounters(recv = seq)
            seq
        } catch (_: Exception) {
            null
        }
    }

    /** 打开私聊界面：未读消息标记已读并回传回执 */
    fun markReadOnOpen() {
        scope.launch {
            try {
                val seqs = db().messages().unreadIncomingSeqs()
                if (seqs.isNotEmpty()) sendReceipt("read", seqs)
            } catch (_: Exception) {
            }
        }
    }

    /** 重发队列：SENDING 状态的密文原样补发（同一封信重投，接收端按 seq 幂等去重） */
    fun resendPending() {
        val p = _partner.value ?: return
        val a = api ?: return
        scope.launch {
            try {
                for (m in db().messages().byStatus(MSG_SENDING)) {
                    val wire = m.wire ?: continue
                    try {
                        a.envelopePost(p.uuid, "msg", m.seq, wire)
                        db().messages().updateStatus(m.id, MSG_SENT)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 超过 7 天仍未送达的本地消息标记 ❗过期 */
    fun sweepExpiry() {
        scope.launch {
            try {
                db().messages().markExpired(System.currentTimeMillis() - TTL_MS)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateCounters(send: Long? = null, recv: Long? = null) {
        val cur = keys ?: return
        val nd = cur.copy(
            sendCounter = send ?: cur.sendCounter,
            recvCounter = recv ?: cur.recvCounter,
        )
        keys = nd
        scope.launch {
            try {
                prefs.edit().putString(
                    "session_wrapped",
                    KeystoreBox.wrap(json.encodeToString(SessionKeysData.serializer(), nd).toByteArray(Charsets.UTF_8))
                ).apply()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 位置共享（M2） ----------

    fun currentPrecision(): Geo.Precision =
        Geo.fromLabel(prefs.getString("loc_precision", null) ?: Geo.Precision.FUZZY.label)

    fun setPrecision(label: String) {
        prefs.edit().putString("loc_precision", label).apply()
        sendMyLocation(Geo.fromLabel(label))
    }

    /** 单次定位 → WGS→GCJ → 精度处理 → E2E loc 信封发送 */
    fun sendMyLocation(precision: Geo.Precision = Geo.Precision.FUZZY) {
        val p = _partner.value ?: return
        val a = api ?: return
        scope.launch {
            val got = LocationProvider.requestSingleFix(context) { lat, lon ->
                scope.launch {
                    try {
                        val (gLat, gLon) = Geo.wgs84ToGcj02(lat, lon)
                        val (sLat, sLon) = Geo.applyPrecision(gLat, gLon, precision)
                        val k = keys ?: return@launch
                        val seq = k.sendCounter + 1
                        val ts = System.currentTimeMillis()
                        val inner = "{\"t\":\"loc\",\"lat\":$sLat,\"lon\":$sLon,\"precision\":\"${precision.label}\",\"ts\":$ts}"
                        val wire = Crypto.sealWire(
                            B64.dec(k.send), B64.dec(k.sendPrefix), seq,
                            Crypto.canonMsgAad(identity.uuid, p.uuid, seq),
                            inner.toByteArray(Charsets.UTF_8),
                        )
                        a.envelopePost(p.uuid, "loc", seq, wire)
                        updateCounters(send = seq)
                        _myLoc.value = PartnerLoc(sLat, sLon, precision.label, ts)
                    } catch (_: Exception) {
                    }
                }
            }
            if (!got) _error.value = "定位失败：设备无可用定位源"
        }
    }

    private fun handleLocEnvelope(from: String, seq: Long, ct: String): Long? {
        val p = _partner.value ?: return null
        val k = keys ?: return null
        if (from != p.uuid) return null
        return try {
            val pt = Crypto.openWire(B64.dec(k.recv), ct, seq, Crypto.canonMsgAad(p.uuid, identity.uuid, seq))
            val o = json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
            if (o["t"]?.jsonPrimitive?.contentOrNull == "loc") {
                val pl = PartnerLoc(
                    lat = o["lat"]!!.jsonPrimitive.double,
                    lon = o["lon"]!!.jsonPrimitive.double,
                    precisionLabel = o["precision"]?.jsonPrimitive?.contentOrNull ?: Geo.Precision.FUZZY.label,
                    ts = o["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                )
                _partnerLoc.value = pl
                try {
                    prefs.edit().putString("partner_loc", json.encodeToString(PartnerLoc.serializer(), pl)).apply()
                } catch (_: Exception) {
                }
            }
            updateCounters(recv = seq)
            seq
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 想你了 / 应用状态（M3） ----------

    /** 发送"想你了"：预设走紧凑 id，自定义（摩斯）走完整 events JSON（设计文档 §8.1） */
    fun sendHaptic(pattern: Vibe.Pattern) {
        val p = _partner.value ?: return
        val k = keys ?: return
        scope.launch {
            val seq = k.sendCounter + 1
            val inner = if (Vibe.byId(pattern.id) != null) {
                "{\"t\":\"haptic\",\"preset\":\"${pattern.id}\"}"
            } else {
                val ev = pattern.events.joinToString(",", "[", "]") { e ->
                    "{\"at\":${e.at},\"dur\":${e.dur},\"amp\":${e.amp}}"
                }
                "{\"t\":\"haptic\",\"events\":$ev,\"name\":\"${pattern.name}\"}"
            }
            val wire = Crypto.sealWire(
                B64.dec(k.send), B64.dec(k.sendPrefix), seq,
                Crypto.canonMsgAad(identity.uuid, p.uuid, seq),
                inner.toByteArray(Charsets.UTF_8),
            )
            try {
                api?.envelopePost(p.uuid, "haptic", seq, wire)
                updateCounters(send = seq)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleHapticEnvelope(from: String, seq: Long, ct: String) {
        val p = _partner.value ?: return
        val k = keys ?: return
        if (from != p.uuid) return
        try {
            val pt = Crypto.openWire(
                B64.dec(k.recv), ct, seq,
                Crypto.canonMsgAad(p.uuid, identity.uuid, seq)
            )
            val o = json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
            if (o["t"]?.jsonPrimitive?.contentOrNull == "haptic") {
                val pattern = o["preset"]?.jsonPrimitive?.contentOrNull?.let { Vibe.byId(it) }
                    ?: o["events"]?.let { evs ->
                        val arr = evs as? kotlinx.serialization.json.JsonArray ?: return@let null
                        try {
                            Vibe.Pattern(
                                id = "custom",
                                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "自定义",
                                emoji = "💓",
                                events = arr.map { e ->
                                    val x = e.jsonObject
                                    Vibe.Event(
                                        x["at"]!!.jsonPrimitive.long,
                                        x["dur"]!!.jsonPrimitive.long,
                                        x["amp"]!!.jsonPrimitive.double,
                                    )
                                },
                                repeat = 1,
                            )
                        } catch (_: Exception) {
                            null
                        }
                    } ?: Vibe.HEARTBEAT
                _lastHaptic.value = pattern
                val app = context.applicationContext as? com.mengpolar.ldrlife.LdrApp ?: return
                if (app.isForeground) {
                    Vibe.play(context, pattern)
                } else {
                    Vibe.notifyHaptic(context, pattern.id)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun replyHaptic() {
        _lastHaptic.value?.let { sendHaptic(it) }
    }

    /** 应用状态上报：回前台触发；同应用 5 分钟节流；L0 关闭 / L1 仅在用 / L3 应用名 */
    fun reportUsageIfChanged() {
        if (_phase.value != Phase.PAIRED) return
        val level = prefs.getInt("usage_level", 3)
        if (level == 0) return
        val p = _partner.value ?: return
        val a = api ?: return
        scope.launch {
            try {
                val fg = Usage.currentForegroundApp(context)
                val name = when {
                    fg == null -> if (level >= 3) "（未在使用/锁屏）" else "未使用"
                    level >= 3 -> fg.second
                    else -> "使用手机中"
                }
                val now = System.currentTimeMillis()
                val last = _lastUsageReport
                if (last != null && last.first == name && now - last.second < 5 * 60 * 1000) return@launch
                _lastUsageReport = name to now
                val k = keys ?: return@launch
                val seq = k.sendCounter + 1
                val safeName = name.replace("\\", "\\\\").replace("\"", "\\\"")
                val inner = "{\"t\":\"usage\",\"app\":\"$safeName\",\"ts\":$now}"
                val wire = Crypto.sealWire(
                    B64.dec(k.send), B64.dec(k.sendPrefix), seq,
                    Crypto.canonMsgAad(identity.uuid, p.uuid, seq),
                    inner.toByteArray(Charsets.UTF_8),
                )
                a.envelopePost(p.uuid, "usage", seq, wire)
                updateCounters(send = seq)
            } catch (_: Exception) {
            }
        }
    }

    private var _lastUsageReport: Pair<String, Long>? = null

    private fun handleUsageEnvelope(from: String, seq: Long, ct: String): Long? {
        val p = _partner.value ?: return null
        val k = keys ?: return null
        if (from != p.uuid) return null
        return try {
            val pt = Crypto.openWire(B64.dec(k.recv), ct, seq, Crypto.canonMsgAad(p.uuid, identity.uuid, seq))
            val o = json.parseToJsonElement(String(pt, Charsets.UTF_8)).jsonObject
            if (o["t"]?.jsonPrimitive?.contentOrNull == "usage") {
                val u = PartnerUsage(
                    app = o["app"]?.jsonPrimitive?.contentOrNull ?: "",
                    ts = o["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
                )
                _partnerUsage.value = u
                android.util.Log.d("LDR", "usage 处理成功 app=${u.app}")
                try {
                    prefs.edit().putString(
                        "partner_usage",
                        json.encodeToString(PartnerUsage.serializer(), u)
                    ).apply()
                } catch (_: Exception) {
                }
            }
            updateCounters(recv = seq)
            seq
        } catch (_: Exception) {
            null
        }
    }

    fun setUsageEnabled(on: Boolean) {
        prefs.edit().putInt("usage_level", if (on) 3 else 0).apply()
        if (!on) {
            _partnerUsage.value = null
        } else {
            reportUsageIfChanged()
        }
    }

    fun usageEnabled(): Boolean = prefs.getInt("usage_level", 3) != 0

    // ---------- 解绑 ----------

    fun unpair() {
        val a = api ?: return
        scope.launch {
            try {
                a.unbind()
            } catch (_: Exception) {
            }
            clearPartnerLocal()
        }
    }

    private fun onUnpaired() {
        clearPartnerLocal()
        _error.value = "对方已解除绑定"
    }

    private fun clearPartnerLocal() {
        _partner.value = null
        _safety.value = null
        _lastHaptic.value = null
        _partnerUsage.value = null
        _partnerLoc.value = null
        _myLoc.value = null
        keys = null
        _ready.value = false
        _waiting.value = false
        myEphPriv = null
        myEphPub = null
        partnerEph = null
        prefs.edit().remove("partner_wrapped").remove("session_wrapped")
            .remove("partner_loc").remove("partner_usage").apply()
        _phase.value = if (cfgProvider() == null) Phase.UNCONFIGURED else Phase.UNPAIRED
        scope.launch {
            try {
                db().messages().clear()
            } catch (_: Exception) {
            }
        }
    }
}
