"""E2E 联调测试客户端：模拟设备 B，与模拟器里的 App（设备 A）完成配对并发送加密消息。

用法：
    python tools/e2e_client.py --server http://127.0.0.1:9443 \
        --gate testgate0123456789abcdef --code 8900353 --text "你好，我是B" --timeout 180

流程：注册 → 输码申请 → （等待 A 端审批）→ 收 pair_approved → 交换临时公钥 →
三重 DH 派生会话密钥 → 发送 E2E 加密消息。
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import uuid as uuidlib

import httpx
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat, NoEncryption

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import auth  # 复用服务器的规范串/校验实现，保证两端一致


def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()


def b64d(s: str) -> bytes:
    return base64.b64decode(s)


def lexcmp(a: bytes, b: bytes) -> int:
    return -1 if a < b else (1 if a > b else 0)


class ClientB:
    def __init__(self, server: str, gate: str):
        self.gate = gate
        self.base = server.rstrip("/") + "/" + gate
        self.http = httpx.Client(timeout=10, trust_env=False)  # 直连，不走系统代理

        self.uuid = str(uuidlib.uuid4())
        self.epriv = Ed25519PrivateKey.generate()
        self.ePub = b64e(self.epriv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
        self.xpriv = X25519PrivateKey.generate()
        self.xPub = b64e(self.xpriv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

        self.partner = None          # dict(uuid/xPubB64/ePubB64/xPubBytes/ePubBytes)
        self.eph_priv = None
        self.eph_pub_b64 = None
        self.peer_eph = None
        self.keys = None             # (send_key, send_prefix, recv_key, recv_prefix)
        self.send_seq = 0
        self.ready = False
        self.sent_text = None
        self.mock_lat = None
        self.mock_lon = None
        self.loc_count = 0
        self.unread = []             # 收到但未回执的消息 seq
        self.read_later = []         # (due_ts, seq) 延迟回"已读"

    def sign(self, msg: str) -> str:
        return b64e(self.epriv.sign(msg.encode("utf-8")))

    def auth_headers(self, method: str, path: str) -> dict:
        ts = int(time.time())
        return {"x-ldr-ts": str(ts), "x-ldr-sig": self.sign(auth.canon_auth(self.uuid, method, path, ts))}

    def register(self):
        # 注册码与 App 同规则：由 uuid 确定性派生（保证重复测试互不冲突）
        digest = hashlib.sha256((self.uuid + "LDRLIFE-PAIR-V1").encode()).digest()
        six = f"{int.from_bytes(digest[:8], 'big') % 1_000_000:06d}"
        body_code = auth.luhn_check_digit(six)
        sig = self.sign(auth.canon_register(self.uuid, body_code, self.xPub, self.ePub))
        r = self.http.post(f"{self.base}/v1/register", json={
            "uuid": self.uuid, "code": body_code, "x25519": self.xPub,
            "ed25519": self.ePub, "sig": sig,
        })
        r.raise_for_status()
        print("[B] 注册成功 uuid=", self.uuid[:8])

    def pair_request(self, code: str):
        sig = self.sign(auth.canon_pairreq(code, self.uuid, self.xPub, self.ePub))
        r = self.http.post(f"{self.base}/v1/pair/request", json={
            "code": code, "from_uuid": self.uuid, "x25519": self.xPub,
            "ed25519": self.ePub, "sig": sig,
        })
        if r.status_code != 202:
            raise RuntimeError(f"pair request failed: {r.status_code} {r.text}")
        print("[B] 已向配对码", code, "发起申请 request_id=", r.json()["id"])

    def pending(self):
        r = self.http.get(f"{self.base}/v1/envelope/pending", params={"uuid": self.uuid},
                          headers=self.auth_headers("GET", "/v1/envelope/pending"))
        r.raise_for_status()
        return r.json()["envelopes"]

    def ack(self, ids):
        self.http.post(f"{self.base}/v1/envelope/ack", json={"uuid": self.uuid, "ids": ids},
                       headers=self.auth_headers("POST", "/v1/envelope/ack"))

    def send_pair_eph(self):
        eph = X25519PrivateKey.generate()
        self.eph_priv = eph
        self.eph_pub_b64 = b64e(eph.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
        sig = self.sign(auth.canon_pair_eph(self.uuid, self.eph_pub_b64))
        inner = json.dumps({"t": "pair_eph", "eph": self.eph_pub_b64, "sig": sig}, ensure_ascii=False)
        r = self.http.post(f"{self.base}/v1/envelope", json={
            "from": self.uuid, "to": self.partner["uuid"], "type_hint": "pair", "seq": 0,
            "ct": b64e(inner.encode("utf-8")),
        }, headers=self.auth_headers("POST", "/v1/envelope"))
        print("[B] 已发送临时公钥（pair_eph）", r.status_code)

    def handle_envelope(self, e):
        hint = e.get("type_hint")
        raw = b64d(e["ct"])
        if hint == "pair":
            o = json.loads(raw.decode("utf-8"))
            if o.get("t") == "pair_approved":
                self.partner = {
                    "uuid": o["peer_uuid"],
                    "xPubB64": o["peer_x25519"],
                    "ePubB64": o["peer_ed25519"],
                    "xPubBytes": b64d(o["peer_x25519"]),
                    "ePubBytes": b64d(o["peer_ed25519"]),
                }
                print("[B] 对方已同意绑定，对方设备 =", o["peer_uuid"][:8])
                if self.eph_priv is None:
                    self.send_pair_eph()
            elif o.get("t") == "pair_eph":
                if self.partner is None or e["from"] != self.partner["uuid"]:
                    return
                ok = auth.verify_sig(self.partner["ePubB64"],
                                     auth.canon_pair_eph(self.partner["uuid"], o["eph"]), o["sig"])
                if not ok:
                    print("[B] pair_eph 签名校验失败")
                    return
                self.peer_eph = b64d(o["eph"])
                self.try_derive()
        elif hint == "msg":
            seq = e.get("seq", 0)
            if self.keys is None or self.partner is None or e.get("from") != self.partner["uuid"]:
                return
            try:
                sk, sp, rk, rp = self.keys
                print(f"[B] recv attempt seq={seq} recvKey={rk[:8]} recvPrefix={rp[:8]} aad=LDRLIFE1|msg|{self.partner['uuid']}|{self.uuid}|{seq} ctLen={len(e.get('ct',''))}")
                pt = self.open_incoming(raw, seq)
                o = json.loads(pt.decode("utf-8"))
                if o.get("t") == "msg":
                    print(f'[B] 收到加密消息 seq={seq}: {o.get("body")}')
                    self.unread.append(seq)
            except Exception as ex:
                dump = (f"[B] 解密失败 type={type(ex).__name__} msg={ex}\n"
                        f"    raw({len(raw)}B)={raw.hex()}\n"
                        f"    recvKey={self.keys[2].hex()}\n"
                        f"    recvPrefix={self.keys[3].hex()}\n"
                        f"    aad={auth.CANON}|msg|{self.partner['uuid']}|{self.uuid}|{seq}\n"
                        f"    ct_b64={e.get('ct','')[:120]}")
                print(dump)

    def open_incoming(self, raw: bytes, seq: int) -> bytes:
        """解密收到的消息信封（nonce 编码 seq，AAD 绑定双方与序号）"""
        _, _, recv_key, _ = self.keys  # keys = (send, send_prefix, recv, recv_prefix)
        data = raw
        if len(data) < 12 + 16:
            raise ValueError("ct too short")
        if int.from_bytes(data[4:12], "big") != seq:
            raise ValueError("nonce/seq mismatch")
        aad = auth.CANON.encode() + f"|msg|{self.partner['uuid']}|{self.uuid}|{seq}".encode()
        print(f"[B] open_incoming 现场: recv_key={recv_key.hex()} nonce={data[:12].hex()} aad={aad.decode()} ct_len={len(data)-12}")
        aead = ChaCha20Poly1305(recv_key)
        return aead.decrypt(data[:12], data[12:], aad)

    def send_loc(self):
        """模拟 M2：以固定坐标发位置 E2E 信封（B 发给 A）"""
        send_key, send_prefix, _, _ = self.keys
        self.send_seq += 1
        seq = self.send_seq
        inner = json.dumps({"t": "loc", "lat": self.mock_lat, "lon": self.mock_lon,
                            "precision": "模糊 ±1.1km", "ts": int(time.time() * 1000)}, ensure_ascii=False)
        aead = ChaCha20Poly1305(send_key)
        nonce = send_prefix + seq.to_bytes(8, "big")
        aad = auth.CANON.encode() + f"|msg|{self.uuid}|{self.partner['uuid']}|{seq}".encode()
        ct = aead.encrypt(nonce, inner.encode("utf-8"), aad)
        wire = b64e(nonce + ct)
        r = self.http.post(f"{self.base}/v1/envelope", json={
            "from": self.uuid, "to": self.partner["uuid"], "type_hint": "loc", "seq": seq, "ct": wire,
        }, headers=self.auth_headers("POST", "/v1/envelope"))
        self.loc_count += 1
        print(f"[B] 位置信封已发送 #{self.loc_count} seq={seq} ({r.status_code})")

    def send_receipt(self, kind: str, seqs):
        send_key, send_prefix, _, _ = self.keys
        self.send_seq += 1
        seq = self.send_seq
        inner = json.dumps({"t": kind, "seqs": list(seqs)}, ensure_ascii=False)
        aead = ChaCha20Poly1305(send_key)
        nonce = send_prefix + seq.to_bytes(8, "big")
        aad = auth.CANON.encode() + f"|msg|{self.uuid}|{self.partner['uuid']}|{seq}".encode()
        ct = aead.encrypt(nonce, inner.encode("utf-8"), aad)
        wire = b64e(nonce + ct)
        r = self.http.post(f"{self.base}/v1/envelope", json={
            "from": self.uuid, "to": self.partner["uuid"], "type_hint": "read", "seq": seq, "ct": wire,
        }, headers=self.auth_headers("POST", "/v1/envelope"))
        print(f"[B] 已回传 {kind} 回执 seq={seq} ({r.status_code})")

    def flush_receipts(self):
        """收到消息后：立即回 delivered；3 秒后回 read"""
        if self.unread and self.ready:
            self.send_receipt("delivered", self.unread)
            now = time.time()
            for s in self.unread:
                self.read_later.append((now + 3.0, s))
            self.unread = []
        due = [s for (t, s) in self.read_later if time.time() >= t]
        if due:
            self.send_receipt("read", due)
            self.read_later = [x for x in self.read_later if time.time() < x[0]]

    def try_derive(self):
        if self.partner is None or self.eph_priv is None or self.peer_eph is None:
            return
        mine = b64d(self.xPub)
        theirs = self.partner["xPubBytes"]
        is_a = lexcmp(mine, theirs) <= 0
        peer_eph_pub = X25519PublicKey.from_public_bytes(self.peer_eph)
        peer_id_pub = X25519PublicKey.from_public_bytes(theirs)
        my_id_pub = X25519PublicKey.from_public_bytes(mine)
        s1 = self.eph_priv.exchange(peer_eph_pub)
        if is_a:
            s2 = self.xpriv.exchange(peer_eph_pub)
            s3 = self.eph_priv.exchange(peer_id_pub)
        else:
            s2 = self.eph_priv.exchange(peer_id_pub)
            s3 = self.xpriv.exchange(peer_eph_pub)
        shared = s1 + s2 + s3
        sorted_pubs = (mine + theirs) if is_a else (theirs + mine)
        okm = HKDF(algorithm=hashes.SHA256(), length=72, salt=b"\x00" * 32,
                   info=sorted_pubs).derive(shared)
        k_a2b, k_b2a = okm[0:32], okm[32:64]
        pref_a, pref_b = okm[64:68], okm[68:72]
        if is_a:
            self.keys = (k_a2b, pref_a, k_b2a, pref_b)
        else:
            self.keys = (k_b2a, pref_b, k_a2b, pref_a)
        self.ready = True
        print("[B] 会话密钥派生成功，E2E 就绪 (isA=", is_a, ")")

    def send_message(self, text: str):
        send_key, send_prefix, _, _ = self.keys
        self.send_seq += 1
        seq = self.send_seq
        inner = json.dumps({"t": "msg", "body": text, "ts": int(time.time() * 1000)}, ensure_ascii=False)
        aead = ChaCha20Poly1305(send_key)
        nonce = send_prefix + seq.to_bytes(8, "big")
        aad = auth.CANON.encode() + f"|msg|{self.uuid}|{self.partner['uuid']}|{seq}".encode()
        ct = aead.encrypt(nonce, inner.encode("utf-8"), aad)
        wire = b64e(nonce + ct)
        r = self.http.post(f"{self.base}/v1/envelope", json={
            "from": self.uuid, "to": self.partner["uuid"], "type_hint": "msg", "seq": seq, "ct": wire,
        }, headers=self.auth_headers("POST", "/v1/envelope"))
        if r.status_code == 200:
            self.sent_text = text
            print("[B] 加密消息已发送 seq=", seq)
        else:
            print("[B] 消息发送失败:", r.status_code, r.text)

    def run(self, code: str, text: str, timeout: int, lat: float, lon: float):
        self.mock_lat, self.mock_lon = lat, lon
        self.register()
        self.pair_request(code)
        deadline = time.time() + timeout
        text_sent = False
        acked = []
        while time.time() < deadline:
            try:
                envs = self.pending()
                for e in envs:
                    self.handle_envelope(e)
                    acked.append(e["id"])
                if acked:
                    self.ack(acked)
                    acked = []
                self.flush_receipts()
                if self.ready and not text_sent:
                    time.sleep(1)
                    self.send_message(text)
                    text_sent = True
                if self.ready and text_sent and self.loc_count < 3 and time.time() >= getattr(self, "next_loc", 0):
                    self.send_loc()
                    self.next_loc = time.time() + 10
            except Exception as ex:
                print("[B] 轮询异常:", ex)
            time.sleep(1)
        if self.sent_text is None:
            print("[B] 超时：未完成 E2E 发送")
            sys.exit(1)
        print("[B] 测试结束")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:9443")
    ap.add_argument("--gate", required=True)
    ap.add_argument("--code", required=True)
    ap.add_argument("--text", default="你好，我是测试端B 💞")
    ap.add_argument("--lat", type=float, default=31.2304)
    ap.add_argument("--lon", type=float, default=121.4737)
    ap.add_argument("--timeout", type=int, default=180)
    args = ap.parse_args()

    c = ClientB(args.server, args.gate)
    c.run(args.code, args.text, args.timeout, args.lat, args.lon)


if __name__ == "__main__":
    main()
