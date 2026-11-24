# 倾卿 (LDRLife)

给异地情侣的专属小 App：一对一绑定、互相看见彼此的位置与"此刻状态"、端到端加密私聊、一键把"想你了"震进对方手心。

**隐私原则**：明文只存在于两台手机内。中继服务器只接触密文信封与路由信息，不存明文、无账号体系、信封送达即删（离线缓存最长 7 天）。

## 功能

- **配对**：设备唯一 ID 生成 7 位配对码，对方输码发起申请，本机审批后完成绑定与密钥交换，附安全码比对（防中间人）
- **地图**：双方大致位置 + 直线距离 + 自适应缩放，精度可调（精确 / 模糊 ±1.1km / 城市）
- **应用状态**：共享"对方正在用 / 最近在用哪个 App"（UsageStats，分级授权，不留历史）
- **加密消息**：X25519 三重 DH + HKDF + XChaCha20-Poly1305，本地 SQLCipher 加密存储
- **想你了**：自定义节奏/频率的振动（前台完整播放，后台经通知渠道振动图案送达）

## 平台

- 客户端：Android（Kotlin + Jetpack Compose），`minSdk 26`
- 中继服务器：Python3（FastAPI + WebSocket + SQLite）

## 仓库结构

```
/app     Android 客户端
/server  Go 中继服务器
/docs    设计文档
```

## 构建

### 客户端

用 Android Studio 打开 `app/`（或根目录），同步后直接运行 `app` 模块。

### 服务器（Python3 / FastAPI）

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt        # Windows: .venv\Scripts\pip
cp config.example.toml config.toml               # 修改 gate（门禁密钥）与 port
.venv/bin/python main.py -c config.toml          # Windows: .venv\Scripts\python
```

运行需要一份 `config.toml`（模板见 `config.example.toml`），其中 `gate` 为部署级门禁密钥（≥16 位随机串）：所有接口必须以 `/{gate}/v1/...` 前缀访问，未携带一律 404。

联调测试工具（模拟一台设备完成配对并发送加密消息）：

```bash
.venv/bin/python tools/e2e_client.py --server http://127.0.0.1:9443 \
    --gate <你的gate> --code <对方7位配对码> --text "你好" --timeout 120
```

## 更新

Release APK 附 SHA-256 校验值；国内下载可在 release 资源 URL 前加镜像前缀（如 `https://gh-proxy.org/<原始URL>`）。

## 安全说明

- 服务器地址与门禁密钥在 App 内"服务器设置"中配置，不写入代码库
- 消息加密协议文档见 `docs/design.md`
- 提交前请运行隐私核查（见 `docs/design.md` §10.1）

## License

[AGPL-3.0](LICENSE)
