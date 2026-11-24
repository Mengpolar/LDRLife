"""门禁中间件（纯 ASGI）：

所有路径必须以 /{gate} 开头，否则按"路径不存在"处理（统一空 body 404）。
404 同样计入限流，超限临时封禁 IP——响应保持一致，探测者无从区分。
"""

import json


async def _send_empty_404(send):
    await send({
        "type": "http.response.start",
        "status": 404,
        "headers": [(b"content-length", b"0")],
    })
    await send({"type": "http.response.body", "body": b""})


class GateMiddleware:

    def __init__(self, app, cfg, limiter):
        self.app = app
        self.cfg = cfg
        self.limiter = limiter

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        client = scope.get("client") or ("unknown", 0)
        ip = client[0]
        prefix = "/" + self.cfg.gate

        if path != prefix and not path.startswith(prefix + "/"):
            if scope["type"] == "http":
                self.limiter.hit404(ip)
                await _send_empty_404(send)
            else:
                # WebSocket 升级请求未携带门禁 → 直接关闭（HTTP 层表现为握手失败）
                await send({"type": "websocket.close", "code": 1008})
            return

        scope = dict(scope)
        scope["path"] = path[len(prefix):]
        await self.app(scope, receive, send)


def dumps(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)
