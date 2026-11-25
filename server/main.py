"""LDRLife 中继服务器（Python3 / FastAPI）。

原则：
- 无账号体系（uuid + 公钥即身份），信封送达即删，7 天 TTL 清扫
- 门禁：所有路径必须以 /{gate} 开头，否则一律 404（见 gate.py）
- 服务器只见密文与路由信息，日志不记录信封内容
"""

import argparse
import asyncio
import base64
import json
import os
import threading
import time
from contextlib import asynccontextmanager

import uvicorn
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

import auth
from config import Config, load_config
from gate import GateMiddleware
from ratelimit import RateLimiter
from store import (AlreadyPaired, CodeTaken, NoDevice, NoRequest, Store)


# ---------- 请求模型 ----------

class RegisterReq(BaseModel):
    uuid: str
    code: str
    x25519: str
    ed25519: str
    sig: str


class PairReqReq(BaseModel):
    code: str
    from_uuid: str
    x25519: str
    ed25519: str
    sig: str


class PairDecReq(BaseModel):
    uuid: str
    request_id: int
    approve: bool
    sig: str


class EnvBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    from_uuid: str = Field(alias="from")
    to: str
    type_hint: str
    seq: int = 0
    ct: str


class AckBody(BaseModel):
    uuid: str
    ids: list[int]


# ---------- 在线连接 Hub ----------

class Hub:
    """uuid -> 推送队列；仅推送结构化 JSON（配对申请 / 密文信封 / 解绑通知）"""

    def __init__(self):
        self.conns: dict[str, asyncio.Queue] = {}

    def register(self, uuid: str) -> asyncio.Queue:
        old = self.conns.get(uuid)
        if old is not None:
            self.conns.pop(uuid, None)
            try:
                old.put_nowait(None)  # 通知旧连接的推送协程退出
            except Exception:
                pass
        q: asyncio.Queue = asyncio.Queue(maxsize=64)
        self.conns[uuid] = q
        return q

    def remove(self, uuid: str) -> None:
        self.conns.pop(uuid, None)

    def send_to(self, uuid: str, payload: dict) -> bool:
        q = self.conns.get(uuid)
        if q is None:
            return False
        try:
            q.put_nowait(json.dumps(payload, ensure_ascii=False))
            return True
        except asyncio.QueueFull:
            return False  # 队列满则丢弃；客户端通过 pending 接口兜底拉取


# ---------- 应用工厂 ----------

def create_app(cfg: Config):
    store = Store(cfg.db_path)
    hub = Hub()
    limiter = RateLimiter(cfg)
    bg_tasks: list = []

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        threading.Thread(target=limiter.janitor_loop, daemon=True).start()
        sweep = asyncio.create_task(_sweep_loop(store))
        bg_tasks.append(sweep)
        yield
        sweep.cancel()

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    # ---------- 内部工具 ----------

    def device_by_uuid(uuid: str) -> dict:
        d = store.get_device(uuid)
        if d is None:
            raise HTTPException(401, "unknown device")
        return d

    def check_auth(request: Request, uuid: str, path: str) -> dict:
        """请求头签名校验：X-Ldr-Ts / X-Ldr-Sig，签名对象 canon_auth(uuid, method, path, ts)"""
        if not auth.valid_uuid(uuid):
            raise HTTPException(400, "bad uuid")
        ts = request.headers.get("x-ldr-ts")
        sig = request.headers.get("x-ldr-sig")
        if not ts or not sig:
            raise HTTPException(401, "missing auth headers")
        try:
            ts_i = int(ts)
        except ValueError:
            raise HTTPException(401, "bad ts")
        if abs(time.time() - ts_i) > 120:
            raise HTTPException(401, "bad ts")
        d = device_by_uuid(uuid)
        if not auth.verify_sig(d["ed25519_pub"], auth.canon_auth(uuid, request.method, path, ts_i), sig):
            raise HTTPException(401, "bad signature")
        store.touch(uuid)
        return d

    def require_paired(a: dict, b_uuid: str) -> dict:
        b = store.get_device(b_uuid)
        if b is None:
            raise HTTPException(404, "unknown recipient")
        if a["paired_with"] != b_uuid or b["paired_with"] != a["uuid"]:
            raise HTTPException(403, "not paired")
        return b

    # ---------- register ----------

    @app.post("/v1/register")
    async def register(req: RegisterReq):
        # 字段校验失败时返回具体字段名（仅字段名，不含值，便于联调排查）
        bad = []
        if not auth.valid_uuid(req.uuid):
            bad.append("uuid")
        if not auth.valid_code(req.code):
            bad.append("code")
        if not auth.valid_key_b64(req.x25519):
            bad.append("x25519")
        if not auth.valid_key_b64(req.ed25519):
            bad.append("ed25519")
        if not auth.valid_sig_b64(req.sig):
            bad.append("sig")
        if bad:
            raise HTTPException(400, "invalid fields: " + ",".join(bad))
        msg = auth.canon_register(req.uuid, req.code, req.x25519, req.ed25519)
        if not auth.verify_sig(req.ed25519, msg, req.sig):
            raise HTTPException(401, "bad signature")
        try:
            store.register_device(req.uuid, req.code, req.x25519, req.ed25519)
        except CodeTaken:
            raise HTTPException(409, "code taken")
        return {"ok": True}

    # ---------- pair ----------

    @app.post("/v1/pair/request")
    async def pair_request(req: PairReqReq):
        if not (auth.valid_code(req.code) and auth.valid_uuid(req.from_uuid)
                and auth.valid_key_b64(req.x25519) and auth.valid_key_b64(req.ed25519)
                and auth.valid_sig_b64(req.sig)):
            raise HTTPException(400, "invalid fields")
        msg = auth.canon_pairreq(req.code, req.from_uuid, req.x25519, req.ed25519)
        if not auth.verify_sig(req.ed25519, msg, req.sig):
            raise HTTPException(401, "bad signature")
        # 配对码查询限流（防枚举）
        if not limiter.check_code(req.code):
            raise HTTPException(429, "code rate limited")
        target = store.get_device_by_code(req.code)
        if target is None:
            raise HTTPException(404, "unknown code")
        if target["uuid"] == req.from_uuid:
            raise HTTPException(400, "cannot pair with self")
        if target["paired_with"]:
            raise HTTPException(409, "target already paired")
        from_dev = store.get_device(req.from_uuid)
        if from_dev is None:
            raise HTTPException(400, "register first")
        if from_dev["x25519_pub"] != req.x25519 or from_dev["ed25519_pub"] != req.ed25519:
            raise HTTPException(409, "pub mismatch, re-register first")
        req_id = store.create_pair_request(req.from_uuid, target["uuid"], req.x25519, req.ed25519)
        hub.send_to(target["uuid"], {
            "t": "pair_request", "id": req_id,
            "from_uuid": req.from_uuid, "x25519": req.x25519, "ed25519": req.ed25519,
        })
        return JSONResponse(status_code=202, content={"id": req_id})

    @app.get("/v1/pair/pending")
    async def pair_pending(request: Request, uuid: str):
        d = check_auth(request, uuid, "/v1/pair/pending")
        return {"requests": store.pending_requests_for(d["uuid"])}

    @app.post("/v1/pair/decision")
    async def pair_decision(req: PairDecReq):
        if not (auth.valid_uuid(req.uuid) and auth.valid_sig_b64(req.sig)):
            raise HTTPException(400, "invalid fields")
        d = device_by_uuid(req.uuid)
        msg = auth.canon_pairdec(req.request_id, req.uuid, req.approve)
        if not auth.verify_sig(d["ed25519_pub"], msg, req.sig):
            raise HTTPException(401, "bad signature")
        pr = store.get_pair_request(req.request_id)
        if pr is None:
            raise HTTPException(404, "request not found")
        if pr["to_uuid"] != req.uuid:
            raise HTTPException(403, "not your request")
        if not req.approve:
            store.delete_pair_request(pr["id"])
            return {"ok": True}
        if d["paired_with"]:
            raise HTTPException(409, "already paired")
        from_dev = store.get_device(pr["from_uuid"])
        if from_dev is None:
            store.delete_pair_request(pr["id"])
            raise HTTPException(404, "requester gone")
        try:
            store.set_paired(pr["from_uuid"], req.uuid)
        except AlreadyPaired:
            raise HTTPException(409, "already paired")
        store.delete_pair_request(pr["id"])

        # 向申请方投递 pair_approved 信封（双方公钥属公开信息，服务器可暂存转发；
        # 申请方收到后回发自己的临时公钥完成 E2E 密钥交换）
        approved = json.dumps({
            "t": "pair_approved",
            "peer_uuid": req.uuid,
            "peer_x25519": d["x25519_pub"],
            "peer_ed25519": d["ed25519_pub"],
        }, ensure_ascii=False)
        env_id = store.insert_envelope(req.uuid, pr["from_uuid"], "pair", 0,
                                       base64.b64encode(approved.encode("utf-8")).decode())
        hub.send_to(pr["from_uuid"], {
            "t": "envelope", "id": env_id, "from": req.uuid, "type_hint": "pair",
            "seq": 0, "ct": base64.b64encode(approved.encode("utf-8")).decode(),
            "created_at": int(time.time()),
        })
        return {"ok": True, "peer": {"uuid": from_dev["uuid"], "x25519": from_dev["x25519_pub"],
                                     "ed25519": from_dev["ed25519_pub"]}}

    @app.post("/v1/pair/unbind")
    async def unbind(request: Request, uuid: str):
        d = check_auth(request, uuid, "/v1/pair/unbind")
        partner, did = store.unpair(d["uuid"])
        if not did:
            raise HTTPException(400, "not paired")
        hub.send_to(partner, {"t": "unpaired"})
        return {"ok": True}

    # ---------- envelope ----------

    @app.post("/v1/envelope")
    async def envelope(req: EnvBody, request: Request):
        if not (auth.valid_uuid(req.from_uuid) and auth.valid_uuid(req.to)
                and auth.valid_type_hint(req.type_hint)):
            raise HTTPException(400, "invalid fields")
        try:
            ct_raw = base64.b64decode(req.ct)
        except Exception:
            raise HTTPException(400, "bad ct encoding")
        if len(ct_raw) > cfg.max_ct_bytes:
            raise HTTPException(413, "ct too large")
        from_dev = device_by_uuid(req.from_uuid)
        if not auth.verify_sig(from_dev["ed25519_pub"],
                               auth.canon_auth(req.from_uuid, "POST", "/v1/envelope",
                                               int(request.headers.get("x-ldr-ts", "0") or 0)),
                               request.headers.get("x-ldr-sig", "")):
            raise HTTPException(401, "bad signature")
        store.touch(req.from_uuid)
        require_paired(from_dev, req.to)
        env_id = store.insert_envelope(req.from_uuid, req.to, req.type_hint, req.seq, req.ct)
        hub.send_to(req.to, {
            "t": "envelope", "id": env_id, "from": req.from_uuid,
            "type_hint": req.type_hint, "seq": req.seq, "ct": req.ct,
            "created_at": int(time.time()),
        })
        return {"id": env_id}

    @app.get("/v1/envelope/pending")
    async def envelope_pending(request: Request, uuid: str):
        d = check_auth(request, uuid, "/v1/envelope/pending")
        return {"envelopes": store.pending_envelopes(d["uuid"])}

    @app.post("/v1/envelope/ack")
    async def envelope_ack(req: AckBody, request: Request):
        if not auth.valid_uuid(req.uuid):
            raise HTTPException(400, "bad uuid")
        d = device_by_uuid(req.uuid)
        ts = request.headers.get("x-ldr-ts")
        sig = request.headers.get("x-ldr-sig")
        if not ts or not sig:
            raise HTTPException(401, "missing auth headers")
        try:
            ts_i = int(ts)
        except ValueError:
            raise HTTPException(401, "bad ts")
        if abs(time.time() - ts_i) > 120:
            raise HTTPException(401, "bad ts")
        if not auth.verify_sig(d["ed25519_pub"],
                               auth.canon_auth(req.uuid, "POST", "/v1/envelope/ack", ts_i), sig):
            raise HTTPException(401, "bad signature")
        n = store.delete_envelopes(req.uuid, req.ids)
        return {"deleted": n}

    # ---------- WebSocket ----------

    @app.websocket("/v1/ws")
    async def ws_endpoint(ws: WebSocket):
        uuid = ws.query_params.get("uuid", "")
        if not auth.valid_uuid(uuid):
            await ws.close(code=1008)
            return
        d = store.get_device(uuid)
        if d is None:
            await ws.close(code=1008)
            return
        ts = ws.headers.get("x-ldr-ts")
        sig = ws.headers.get("x-ldr-sig")
        try:
            ts_i = int(ts) if ts else 0
        except ValueError:
            ts_i = 0
        ok = (ts and sig and abs(time.time() - ts_i) <= 120
              and auth.verify_sig(d["ed25519_pub"], auth.canon_auth(uuid, "GET", "/v1/ws", ts_i), sig))
        if not ok:
            await ws.close(code=1008)
            return
        store.touch(uuid)
        await ws.accept()
        q = hub.register(uuid)

        async def pusher():
            try:
                while True:
                    msg = await q.get()
                    if msg is None:  # 被新连接替换
                        await ws.close()
                        return
                    await ws.send_text(msg)
            except Exception:
                return

        task = asyncio.create_task(pusher())
        try:
            while True:
                raw = await ws.receive_text()
                try:
                    m = json.loads(raw)
                except Exception:
                    continue
                if m.get("t") == "ack" and isinstance(m.get("ids"), list):
                    try:
                        store.delete_envelopes(uuid, [int(i) for i in m["ids"]])
                    except Exception:
                        pass
                # 其他上行一律忽略：服务器不接收任何内容型数据
        except WebSocketDisconnect:
            pass
        finally:
            task.cancel()
            hub.remove(uuid)

    # ---------- 门禁内的未知路径：与门禁外一致的空 404 ----------

    @app.api_route("/{rest:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"])
    async def catch_all(rest: str):
        return Response(status_code=404)

    return GateMiddleware(app, cfg, limiter)


async def _sweep_loop(store: Store):
    while True:
        await asyncio.sleep(600)
        try:
            store.sweep_expired()
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description="LDRLife relay server")
    parser.add_argument("-c", "--config", default="config.toml")
    args = parser.parse_args()

    cfg = load_config(args.config)
    os.makedirs(os.path.dirname(cfg.db_path) or ".", exist_ok=True)
    app = create_app(cfg)

    # 日志只提示配置摘要，绝不打印 gate 值与信封内容
    print(f"LDRLife relay listening on :{cfg.port} (gate: {len(cfg.gate)} chars)")
    if not (cfg.tls_cert and cfg.tls_key):
        print("WARNING: TLS 未配置，仅限本地开发/测试")
    uvicorn.run(app, host=cfg.host, port=cfg.port,
                ssl_certfile=cfg.tls_cert or None,
                ssl_keyfile=cfg.tls_key or None)


if __name__ == "__main__":
    main()
