"""内存态限流器：

- 404 防爆破：单 IP 每分钟 404 次数超限 → 临时封禁
- 配对码查询：单码每日次数上限
"""

import threading
import time


class RateLimiter:

    def __init__(self, cfg):
        self.cfg = cfg
        self._lock = threading.Lock()
        self._w404: dict[str, list] = {}    # ip -> [window_start, count]
        self._blocked: dict[str, float] = {}  # ip -> until_ts
        self._code: dict[str, list] = {}    # code -> [day, count]

    def hit404(self, ip: str) -> bool:
        now = time.time()
        with self._lock:
            until = self._blocked.get(ip)
            if until is not None:
                if until > now:
                    return False
                del self._blocked[ip]
            w = self._w404.get(ip)
            if w is None or now - w[0] >= 60:
                w = [now, 0]
                self._w404[ip] = w
            w[1] += 1
            if w[1] > self.cfg.max_404_per_min:
                self._blocked[ip] = now + self.cfg.block_minutes * 60
                self._w404.pop(ip, None)
                return False
            return True

    def blocked(self, ip: str) -> bool:
        now = time.time()
        with self._lock:
            until = self._blocked.get(ip)
            if until is None:
                return False
            if until > now:
                return True
            del self._blocked[ip]
            return False

    def check_code(self, code: str) -> bool:
        day = time.strftime("%Y%m%d", time.gmtime())
        with self._lock:
            entry = self._code.get(code)
            if entry is None or entry[0] != day:
                entry = [day, 0]
                self._code[code] = entry
            entry[1] += 1
            return entry[1] <= self.cfg.code_daily_limit

    def janitor_loop(self):
        while True:
            time.sleep(3600)
            now = time.time()
            with self._lock:
                for ip in [ip for ip, w in self._w404.items() if now - w[0] >= 120]:
                    del self._w404[ip]
                for ip in [ip for ip, until in self._blocked.items() if until <= now]:
                    del self._blocked[ip]
