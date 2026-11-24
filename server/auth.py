"""签名校验与协议规范串（与 Android 客户端 core/Crypto.kt 完全一致）。

本服务器只见密文与路由信息。签名仅用于证明"持有公钥对应的私钥"，
防止冒用他人 uuid 注册/操作；不涉及任何消息内容加解密。
"""

import base64
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

CANON = "LDRLIFE1"


def canon_register(uuid: str, code: str, x25519: str, ed25519: str) -> str:
    return f"{CANON}|register|{uuid}|{code}|{x25519}|{ed25519}"


def canon_pairreq(code: str, from_uuid: str, x25519: str, ed25519: str) -> str:
    return f"{CANON}|pairreq|{code}|{from_uuid}|{x25519}|{ed25519}"


def canon_pairdec(request_id: int, uuid: str, approve: bool) -> str:
    return f"{CANON}|pairdec|{request_id}|{uuid}|{'1' if approve else '0'}"


def canon_auth(uuid: str, method: str, path: str, ts: int) -> str:
    return f"{CANON}|auth|{uuid}|{method}|{path}|{ts}"


def canon_pair_eph(uuid: str, eph_b64: str) -> str:
    return f"{CANON}|pair|{uuid}|{eph_b64}"


def verify_sig(pub_b64: str, msg: str, sig_b64: str) -> bool:
    """Ed25519 验签：公钥/签名均为标准 base64"""
    try:
        pub_raw = base64.b64decode(pub_b64)
        sig = base64.b64decode(sig_b64)
        if len(pub_raw) != 32 or len(sig) != 64:
            return False
        pub = Ed25519PublicKey.from_public_bytes(pub_raw)
        pub.verify(sig, msg.encode("utf-8"))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


# ---------- 字段校验 ----------

def b64decode(s: str) -> bytes:
    return base64.b64decode(s)


def valid_key_b64(s: str) -> bool:
    try:
        return len(base64.b64decode(s)) == 32
    except Exception:
        return False


def valid_sig_b64(s: str) -> bool:
    try:
        return len(base64.b64decode(s)) == 64
    except Exception:
        return False


def valid_uuid(s: str) -> bool:
    if len(s) != 36:
        return False
    for i, c in enumerate(s):
        if i in (8, 13, 18, 23):
            if c != "-":
                return False
        elif not (c.isdigit() or c in "abcdefABCDEF"):
            return False
    return True


def _digits_only(s: str) -> bool:
    return all(c in "0123456789" for c in s)


def luhn_check_digit(six: str) -> str:
    """由 6 位主体生成完整 7 位码（客户端/测试共用同一规则）"""
    total = 0
    for i in range(5, -1, -1):
        d = int(six[i])
        if (5 - i) % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return six + str((10 - total % 10) % 10)


def luhn_valid(seven: str) -> bool:
    if len(seven) != 7 or not _digits_only(seven):
        return False
    return luhn_check_digit(seven[:6]) == seven


def valid_code(s: str) -> bool:
    return luhn_valid(s)


def valid_type_hint(s: str) -> bool:
    if not (1 <= len(s) <= 16):
        return False
    return all(c in "abcdefghijklmnopqrstuvwxyz0123456789_" for c in s)


def safe_uuid_prefix(u: str) -> str:
    return u[:8] if len(u) >= 8 else u.replace("-", "")
