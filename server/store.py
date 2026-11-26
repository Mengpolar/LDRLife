"""SQLite 存储（与 Go 版 schema 完全一致）。

信封送达即删 + 7 天 TTL 清扫；服务器永不存明文。
"""

import os
import sqlite3
import threading
import time

ENVELOPE_TTL = 7 * 24 * 3600       # 离线缓存最长 7 天
PAIR_REQUEST_TTL = 7 * 24 * 3600   # 未处理的配对申请最长 7 天


class CodeTaken(Exception):
    pass


class NoDevice(Exception):
    pass


class NoRequest(Exception):
    pass


class AlreadyPaired(Exception):
    pass


SCHEMA = """
CREATE TABLE IF NOT EXISTS devices(
  uuid        TEXT PRIMARY KEY,
  code        TEXT NOT NULL,
  x25519_pub  TEXT NOT NULL,
  ed25519_pub TEXT NOT NULL,
  paired_with TEXT,
  last_seen   INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_code ON devices(code);
CREATE TABLE IF NOT EXISTS pair_requests(
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  from_uuid   TEXT NOT NULL,
  to_uuid     TEXT NOT NULL,
  x25519_pub  TEXT NOT NULL,
  ed25519_pub TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pairreq_to ON pair_requests(to_uuid);
CREATE TABLE IF NOT EXISTS envelopes(
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  from_uuid  TEXT NOT NULL,
  to_uuid    TEXT NOT NULL,
  type_hint  TEXT NOT NULL,
  seq        INTEGER NOT NULL DEFAULT 0,
  ct         TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_env_to ON envelopes(to_uuid);
"""

_DEVICE_COLS = "uuid, code, x25519_pub, ed25519_pub, paired_with, last_seen"


def _device_row(row) -> dict | None:
    if row is None:
        return None
    paired = row[4]
    return {
        "uuid": row[0], "code": row[1], "x25519_pub": row[2],
        "ed25519_pub": row[3], "paired_with": paired, "last_seen": row[5],
    }


class Store:

    def __init__(self, path: str):
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.RLock()
        with self.lock:
            self.db.execute("PRAGMA busy_timeout=5000")
            self.db.execute("PRAGMA journal_mode=WAL")
            self.db.executescript(SCHEMA)

    # ---------- 设备 ----------

    def register_device(self, uuid: str, code: str, x25519: str, ed25519: str) -> None:
        now = int(time.time())
        with self.lock:
            row = self.db.execute("SELECT uuid FROM devices WHERE code=?", (code,)).fetchone()
            if row is not None and row[0] != uuid:
                raise CodeTaken()
            self.db.execute(
                """INSERT INTO devices(uuid, code, x25519_pub, ed25519_pub, paired_with, last_seen, created_at)
                   VALUES(?,?,?,?,NULL,?,?)
                   ON CONFLICT(uuid) DO UPDATE SET
                     code=excluded.code, x25519_pub=excluded.x25519_pub,
                     ed25519_pub=excluded.ed25519_pub, last_seen=excluded.last_seen""",
                (uuid, code, x25519, ed25519, now, now),
            )
            self.db.commit()

    def get_device(self, uuid: str) -> dict | None:
        with self.lock:
            row = self.db.execute(f"SELECT {_DEVICE_COLS} FROM devices WHERE uuid=?", (uuid,)).fetchone()
            return _device_row(row)

    def get_device_by_code(self, code: str) -> dict | None:
        with self.lock:
            row = self.db.execute(f"SELECT {_DEVICE_COLS} FROM devices WHERE code=?", (code,)).fetchone()
            return _device_row(row)

    def touch(self, uuid: str) -> None:
        with self.lock:
            self.db.execute("UPDATE devices SET last_seen=? WHERE uuid=?", (int(time.time()), uuid))
            self.db.commit()

    def set_paired(self, a: str, b: str) -> None:
        with self.lock:
            for u in (a, b):
                row = self.db.execute("SELECT paired_with FROM devices WHERE uuid=?", (u,)).fetchone()
                if row is None:
                    raise NoDevice()
                cur = row[0]
                if cur is not None and cur != "" and cur != a and cur != b:
                    raise AlreadyPaired()
            self.db.execute("UPDATE devices SET paired_with=? WHERE uuid=?", (b, a))
            self.db.execute("UPDATE devices SET paired_with=? WHERE uuid=?", (a, b))
            self.db.commit()

    def unpair(self, uuid: str) -> tuple[str, bool]:
        """解除绑定并删除双方之间所有信封；返回 (原伴侣, 是否解除)"""
        with self.lock:
            row = self.db.execute("SELECT paired_with FROM devices WHERE uuid=?", (uuid,)).fetchone()
            if row is None:
                raise NoDevice()
            partner = row[0]
            if not partner:
                return "", False
            self.db.execute("UPDATE devices SET paired_with=NULL WHERE uuid IN (?, ?)", (uuid, partner))
            self.db.execute(
                "DELETE FROM envelopes WHERE (from_uuid=? AND to_uuid=?) OR (from_uuid=? AND to_uuid=?)",
                (uuid, partner, partner, uuid),
            )
            self.db.commit()
            return partner, True

    # ---------- 配对申请 ----------

    def create_pair_request(self, from_uuid: str, to_uuid: str, x25519: str, ed25519: str) -> int:
        with self.lock:
            # 幂等替换：同一申请方只保留一个 pending（重复申请覆盖旧的）
            self.db.execute("DELETE FROM pair_requests WHERE from_uuid=?", (from_uuid,))
            cur = self.db.execute(
                "INSERT INTO pair_requests(from_uuid, to_uuid, x25519_pub, ed25519_pub, created_at) VALUES(?,?,?,?,?)",
                (from_uuid, to_uuid, x25519, ed25519, int(time.time())),
            )
            self.db.commit()
            return int(cur.lastrowid)

    def get_pair_request(self, req_id: int) -> dict | None:
        with self.lock:
            row = self.db.execute(
                "SELECT id, from_uuid, to_uuid, x25519_pub, ed25519_pub, created_at FROM pair_requests WHERE id=?",
                (req_id,),
            ).fetchone()
            if row is None:
                return None
            return {"id": row[0], "from_uuid": row[1], "to_uuid": row[2],
                    "x25519": row[3], "ed25519": row[4], "created_at": row[5]}

    def delete_pair_request(self, req_id: int) -> None:
        with self.lock:
            self.db.execute("DELETE FROM pair_requests WHERE id=?", (req_id,))
            self.db.commit()

    def pending_requests_for(self, uuid: str) -> list[dict]:
        with self.lock:
            rows = self.db.execute(
                "SELECT id, from_uuid, to_uuid, x25519_pub, ed25519_pub, created_at FROM pair_requests WHERE to_uuid=? ORDER BY id ASC LIMIT 100",
                (uuid,),
            ).fetchall()
            return [{"id": r[0], "from_uuid": r[1], "to_uuid": r[2],
                     "x25519": r[3], "ed25519": r[4], "created_at": r[5]} for r in rows]

    # ---------- 信封 ----------

    def insert_envelope(self, from_uuid: str, to_uuid: str, hint: str, seq: int, ct: str) -> int:
        now = int(time.time())
        with self.lock:
            cur = self.db.execute(
                "INSERT INTO envelopes(from_uuid, to_uuid, type_hint, seq, ct, created_at, expires_at) VALUES(?,?,?,?,?,?,?)",
                (from_uuid, to_uuid, hint, seq, ct, now, now + ENVELOPE_TTL),
            )
            self.db.commit()
            return int(cur.lastrowid)

    def pending_envelopes(self, uuid: str) -> list[dict]:
        with self.lock:
            rows = self.db.execute(
                "SELECT id, from_uuid, to_uuid, type_hint, seq, ct, created_at, expires_at FROM envelopes WHERE to_uuid=? ORDER BY id ASC LIMIT 500",
                (uuid,),
            ).fetchall()
            return [{"id": r[0], "from": r[1], "to": r[2], "type_hint": r[3],
                     "seq": r[4], "ct": r[5], "created_at": r[6], "expires_at": r[7]} for r in rows]

    def delete_envelopes(self, uuid: str, ids: list[int]) -> int:
        n = 0
        with self.lock:
            for env_id in ids:
                cur = self.db.execute("DELETE FROM envelopes WHERE id=? AND to_uuid=?", (env_id, uuid))
                n += cur.rowcount
            self.db.commit()
        return n

    def sweep_expired(self) -> None:
        now = int(time.time())
        with self.lock:
            self.db.execute("DELETE FROM envelopes WHERE expires_at < ?", (now,))
            self.db.execute("DELETE FROM pair_requests WHERE created_at < ?",
                            (now - PAIR_REQUEST_TTL,))
            self.db.commit()
