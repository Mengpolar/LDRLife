import tomllib
from dataclasses import dataclass


@dataclass
class Config:
    gate: str
    port: int = 9443
    db_path: str = "data/ldrlife.db"
    tls_cert: str = ""
    tls_key: str = ""
    log_requests: bool = False
    max_404_per_min: int = 60
    block_minutes: int = 10
    code_daily_limit: int = 20
    max_ct_bytes: int = 256 * 1024


def load_config(path: str) -> Config:
    try:
        import tomllib
    except ImportError:  # Python < 3.11
        import tomli as tomllib

    with open(path, "rb") as f:
        data = tomllib.load(f)

    gate = data.get("gate", "")
    if not gate or len(gate) < 16:
        raise ValueError("config: gate 必填且长度需 >= 16（部署级门禁密钥）")

    return Config(
        gate=gate,
        port=int(data.get("port", 9443)),
        db_path=data.get("db_path", "data/ldrlife.db"),
        tls_cert=data.get("tls_cert", ""),
        tls_key=data.get("tls_key", ""),
        log_requests=bool(data.get("log_requests", False)),
        max_404_per_min=int(data.get("max_404_per_min", 60)),
        block_minutes=int(data.get("block_minutes", 10)),
        code_daily_limit=int(data.get("code_daily_limit", 20)),
        max_ct_bytes=int(data.get("max_ct_bytes", 256 * 1024)),
    )
