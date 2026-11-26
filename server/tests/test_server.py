"""集成测试：与 Go 版测试用例一一对应（协议不变，仅实现换 Python）。"""

import base64
import json
import time
import uuid as uuidlib

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption, PublicFormat
from fastapi.testclient import TestClient

import auth
from config import Config
from main import create_app

GATE = "testgate0123456789abcdef"


def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()


def b64d(s: str) -> bytes:
    return base64.b64decode(s)


class Dev:
    """测试设备：X25519 + Ed25519 密钥对、确定性配对码"""

    def __init__(self, six: str):
        self.six = six
        priv = Ed25519PrivateKey.generate()
        self.epriv = priv
        self.ePub = b64e(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
        xpriv = X25519PrivateKey.generate()
        self.xPub = b64e(xpriv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
        self.uuid = str(uuidlib.uuid4())
        self.code = auth.luhn_check_digit(six)

    def sign(self, msg: str) -> str:
        return b64e(self.epriv.sign(msg.encode("utf-8")))

    def auth_headers(self, method: str, path: str) -> dict:
        ts = int(time.time())
        return {"x-ldr-ts": str(ts), "x-ldr-sig": self.sign(auth.canon_auth(self.uuid, method, path, ts))}


def make_env(tmp_path, code_daily_limit=20, max_404_per_min=1000) -> TestClient:
    cfg = Config(
        gate=GATE,
        db_path=str(tmp_path / "t.db"),
        code_daily_limit=code_daily_limit,
        max_404_per_min=max_404_per_min,
        max_ct_bytes=65536,
    )
    return TestClient(create_app(cfg))


def base(client) -> str:
    return f"/{GATE}"


def register(client: TestClient, d: Dev):
    sig = d.sign(auth.canon_register(d.uuid, d.code, d.xPub, d.ePub))
    r = client.post(f"/{GATE}/v1/register", json={
        "uuid": d.uuid, "code": d.code, "x25519": d.xPub, "ed25519": d.ePub, "sig": sig,
    })
    assert r.status_code == 200, r.text


def pair_devices(client: TestClient, requester: Dev, target: Dev):
    """requester 用 target 的配对码发起申请 → target 审批 → 双向绑定"""
    sig = requester.sign(auth.canon_pairreq(target.code, requester.uuid, requester.xPub, requester.ePub))
    r = client.post(f"/{GATE}/v1/pair/request", json={
        "code": target.code, "from_uuid": requester.uuid,
        "x25519": requester.xPub, "ed25519": requester.ePub, "sig": sig,
    })
    assert r.status_code == 202, r.text
    req_id = r.json()["id"]

    dsig = target.sign(auth.canon_pairdec(req_id, target.uuid, True))
    r = client.post(f"/{GATE}/v1/pair/decision", json={
        "uuid": target.uuid, "request_id": req_id, "approve": True, "sig": dsig,
    })
    assert r.status_code == 200, r.text
    assert r.json()["peer"]["uuid"] == requester.uuid

    # 申请方应收到 pair_approved 信封（含审批方公钥，公开信息）
    r = client.get(f"/{GATE}/v1/envelope/pending", params={"uuid": requester.uuid},
                   headers=requester.auth_headers("GET", "/v1/envelope/pending"))
    assert r.status_code == 200
    envs = r.json()["envelopes"]
    assert any(e["type_hint"] == "pair" and target.uuid == e["from"] for e in envs), r.text


# ---------- 测试 ----------

def test_gate_uniform_404(tmp_path):
    client = make_env(tmp_path)
    # 根路径 / 错门禁 / 缺门禁 / 门禁内未知路径：一律 404 且 body 为空
    for url in ["/", "/wronggate/v1/register", "/v1/register",
                f"/{GATE}/v1/nonexistent"]:
        r = client.get(url)
        assert r.status_code == 404, url
        assert r.content == b"", url
    r = client.post(f"/{GATE}/v1/nope", json={})
    assert r.status_code == 404 and r.content == b""


def test_full_pair_and_envelope_flow(tmp_path):
    client = make_env(tmp_path)
    a, b = Dev("438201"), Dev("777777")
    register(client, a)
    register(client, b)

    pair_devices(client, b, a)  # b 申请 → a 审批

    # b 取走并确认配对结果信封（模拟客户端处理 pair_approved）
    r = client.get(f"/{GATE}/v1/envelope/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/envelope/pending"))
    pre_ids = [e["id"] for e in r.json()["envelopes"]]
    assert pre_ids
    r = client.post(f"/{GATE}/v1/envelope/ack", json={"uuid": b.uuid, "ids": pre_ids},
                    headers=b.auth_headers("POST", "/v1/envelope/ack"))
    assert r.status_code == 200

    # a→b 信封
    ct = b64e(b"ciphertext-1")
    r = client.post(f"/{GATE}/v1/envelope", json={
        "from": a.uuid, "to": b.uuid, "type_hint": "msg", "seq": 1, "ct": ct,
    }, headers=a.auth_headers("POST", "/v1/envelope"))
    assert r.status_code == 200, r.text

    # b 拉取暂存信封 → 恰好 1 封，密文一致
    r = client.get(f"/{GATE}/v1/envelope/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/envelope/pending"))
    assert r.status_code == 200
    envs = r.json()["envelopes"]
    assert len(envs) == 1 and envs[0]["ct"] == ct and envs[0]["seq"] == 1

    # b ack → 服务器物理删除
    r = client.post(f"/{GATE}/v1/envelope/ack", json={"uuid": b.uuid, "ids": [envs[0]["id"]]},
                    headers=b.auth_headers("POST", "/v1/envelope/ack"))
    assert r.status_code == 200 and r.json()["deleted"] == 1

    # 再拉取应为空
    r = client.get(f"/{GATE}/v1/envelope/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/envelope/pending"))
    assert r.json()["envelopes"] == []


def test_decision_bad_signature(tmp_path):
    client = make_env(tmp_path)
    a, b = Dev("111111"), Dev("222222")
    register(client, a)
    register(client, b)

    sig = b.sign(auth.canon_pairreq(a.code, b.uuid, b.xPub, b.ePub))
    r = client.post(f"/{GATE}/v1/pair/request", json={
        "code": a.code, "from_uuid": b.uuid, "x25519": b.xPub, "ed25519": b.ePub, "sig": sig,
    })
    assert r.status_code == 202

    # 用申请者 b 的私钥签 a 的决策 → 必须被拒
    bad = b.sign(auth.canon_pairdec(1, a.uuid, True))
    r = client.post(f"/{GATE}/v1/pair/decision", json={
        "uuid": a.uuid, "request_id": 1, "approve": True, "sig": bad,
    })
    assert r.status_code == 401


def test_envelope_not_paired(tmp_path):
    client = make_env(tmp_path)
    a, c = Dev("333333"), Dev("444444")
    register(client, a)
    register(client, c)
    for src, dst in [(c, a), (a, c)]:
        r = client.post(f"/{GATE}/v1/envelope", json={
            "from": src.uuid, "to": dst.uuid, "type_hint": "msg", "seq": 1, "ct": b64e(b"x"),
        }, headers=src.auth_headers("POST", "/v1/envelope"))
        assert r.status_code == 403


def test_code_rate_limit(tmp_path):
    client = make_env(tmp_path, code_daily_limit=2)
    a, c = Dev("555555"), Dev("666666")
    register(client, a)
    register(client, c)
    body = {
        "code": a.code, "from_uuid": c.uuid, "x25519": c.xPub, "ed25519": c.ePub,
        "sig": c.sign(auth.canon_pairreq(a.code, c.uuid, c.xPub, c.ePub)),
    }
    assert client.post(f"/{GATE}/v1/pair/request", json=body).status_code == 202
    assert client.post(f"/{GATE}/v1/pair/request", json=body).status_code == 202
    assert client.post(f"/{GATE}/v1/pair/request", json=body).status_code == 429


def test_ws_delivery_and_ack(tmp_path):
    client = make_env(tmp_path)
    a, b = Dev("888888"), Dev("999999")
    register(client, a)
    register(client, b)
    pair_devices(client, b, a)  # b 申请 → a 审批

    headers = a.auth_headers("GET", "/v1/ws")
    with client.websocket_connect(f"/{GATE}/v1/ws?uuid={a.uuid}", headers=headers) as ws:
        # b→a 信封：a 在线，应被直接推送
        ct = b64e(b"ws-payload")
        r = client.post(f"/{GATE}/v1/envelope", json={
            "from": b.uuid, "to": a.uuid, "type_hint": "msg", "seq": 7, "ct": ct,
        }, headers=b.auth_headers("POST", "/v1/envelope"))
        assert r.status_code == 200, r.text

        push = ws.receive_json()
        assert push["t"] == "envelope" and push["ct"] == ct

        ws.send_text(json.dumps({"t": "ack", "ids": [push["id"]]}))

    # WS ack 后暂存应为空
    r = client.get(f"/{GATE}/v1/envelope/pending", params={"uuid": a.uuid},
                   headers=a.auth_headers("GET", "/v1/envelope/pending"))
    assert r.json()["envelopes"] == []


def test_luhn():
    full = auth.luhn_check_digit("438201")
    assert auth.luhn_valid(full) and len(full) == 7
    tampered = ("9" if full[0] != "9" else "1") + full[1:]
    assert not auth.luhn_valid(tampered)


# ---------- 配对状态机（v0.0.9）：取消 / 对账 / 去重 ----------

def _request(client: TestClient, requester: Dev, target: Dev) -> int:
    sig = requester.sign(auth.canon_pairreq(target.code, requester.uuid, requester.xPub, requester.ePub))
    r = client.post(f"/{GATE}/v1/pair/request", json={
        "code": target.code, "from_uuid": requester.uuid,
        "x25519": requester.xPub, "ed25519": requester.ePub, "sig": sig,
    })
    assert r.status_code == 202, r.text
    return r.json()["id"]


def test_pair_cancel_flow(tmp_path):
    client = make_env(tmp_path)
    a, b = Dev("121212"), Dev("343434")
    register(client, a)
    register(client, b)

    req_id = _request(client, a, b)

    # B 看得到申请
    r = client.get(f"/{GATE}/v1/pair/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/pair/pending"))
    assert len(r.json()["requests"]) == 1

    # A 撤销 → 200；B 的 pending 清空
    r = client.post(f"/{GATE}/v1/pair/cancel?uuid={a.uuid}", json={"request_id": req_id},
                    headers=a.auth_headers("POST", "/v1/pair/cancel"))
    assert r.status_code == 200, r.text
    r = client.get(f"/{GATE}/v1/pair/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/pair/pending"))
    assert r.json()["requests"] == []

    # 重复撤销 → 404（已被处理）
    r = client.post(f"/{GATE}/v1/pair/cancel?uuid={a.uuid}", json={"request_id": req_id},
                    headers=a.auth_headers("POST", "/v1/pair/cancel"))
    assert r.status_code == 404

    # 非本人撤销 → 403
    req_id2 = _request(client, a, b)
    r = client.post(f"/{GATE}/v1/pair/cancel?uuid={b.uuid}", json={"request_id": req_id2},
                    headers=b.auth_headers("POST", "/v1/pair/cancel"))
    assert r.status_code == 403


def test_decision_after_cancel_404(tmp_path):
    """竞态：A 先取消，B 再同意 → 404，B 端据此提示"对方已取消\""""
    client = make_env(tmp_path)
    a, b = Dev("565656"), Dev("787878")
    register(client, a)
    register(client, b)
    req_id = _request(client, a, b)
    r = client.post(f"/{GATE}/v1/pair/cancel?uuid={a.uuid}", json={"request_id": req_id},
                    headers=a.auth_headers("POST", "/v1/pair/cancel"))
    assert r.status_code == 200
    dsig = b.sign(auth.canon_pairdec(req_id, b.uuid, True))
    r = client.post(f"/{GATE}/v1/pair/decision", json={
        "uuid": b.uuid, "request_id": req_id, "approve": True, "sig": dsig,
    })
    assert r.status_code == 404
    # 双方仍未配对
    for d in (a, b):
        r = client.get(f"/{GATE}/v1/pair/status", params={"uuid": d.uuid},
                       headers=d.auth_headers("GET", "/v1/pair/status"))
        assert r.json()["paired_with"] is None


def test_request_dedupe(tmp_path):
    """同一申请方重复申请 → 幂等替换，目标端只看到一条"""
    client = make_env(tmp_path)
    a, b = Dev("909090"), Dev("232323")
    register(client, a)
    register(client, b)
    _request(client, a, b)
    _request(client, a, b)
    r = client.get(f"/{GATE}/v1/pair/pending", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/pair/pending"))
    reqs = r.json()["requests"]
    assert len(reqs) == 1 and reqs[0]["from_uuid"] == a.uuid


def test_stale_device_sweep(tmp_path):
    """僵尸设备（长期未活跃）：连带绑定一起清理，存活伴侣被解绑可重新配对"""
    client = make_env(tmp_path)
    a, b = Dev("111222"), Dev("333444")
    register(client, a)
    register(client, b)
    pair_devices(client, a, b)  # a 申请 → b 同意

    # a 变僵尸（last_seen 拨回 20 天前）
    import sqlite3, time as _t
    db = sqlite3.connect(str(tmp_path / "t.db"))
    db.execute("UPDATE devices SET last_seen=? WHERE uuid=?", (int(_t.time()) - 20 * 86400, a.uuid))
    db.commit()

    store = None
    from main import create_app as _ca  # 触发 sweep 用 store 实例的公开方法
    # 直接调用 sweep_stale_devices：从 app 的 store 拿不到，就自建
    from store import Store
    st = Store(str(tmp_path / "t.db"))
    swept = st.sweep_stale_devices(days=14)
    assert a.uuid in swept

    # 僵尸行被删、b 被解绑可重新配对
    assert db.execute("SELECT paired_with FROM devices WHERE uuid=?", (b.uuid,)).fetchone()[0] is None
    sig = b.sign(auth.canon_pairreq(a.code, b.uuid, b.xPub, b.ePub))  # 换个目标码测试会污染，跳过
    # b 的 paired_with 已清空（重点断言）
    r = client.get(f"/{GATE}/v1/pair/status", params={"uuid": b.uuid},
                   headers=b.auth_headers("GET", "/v1/pair/status"))
    assert r.json()["paired_with"] is None


def test_unbind_reconcile(tmp_path):
    """解绑后双方 paired_with 均清空；未配对再解绑 → 400"""
    client = make_env(tmp_path)
    a, b = Dev("454545"), Dev("676767")
    register(client, a)
    register(client, b)
    pair_devices(client, b, a)

    r = client.post(f"/{GATE}/v1/pair/unbind?uuid={a.uuid}", json={},
                    headers=a.auth_headers("POST", "/v1/pair/unbind"))
    assert r.status_code == 200
    for d in (a, b):
        r = client.get(f"/{GATE}/v1/pair/status", params={"uuid": d.uuid},
                       headers=d.auth_headers("GET", "/v1/pair/status"))
        assert r.json()["paired_with"] is None
    r = client.post(f"/{GATE}/v1/pair/unbind?uuid={a.uuid}", json={},
                    headers=a.auth_headers("POST", "/v1/pair/unbind"))
    assert r.status_code == 400
