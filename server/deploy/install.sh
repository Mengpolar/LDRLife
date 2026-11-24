#!/usr/bin/env bash
# LDRLife 服务器安装脚本（在服务器上执行）
# 用法：把 server/ 下的 .py 文件、requirements.txt 和 config.toml 放到当前目录后，bash install.sh
# 约束：一切只写入 /opt/LDRLife 与一个 systemd unit，不触碰其他服务。
set -euo pipefail

APP_DIR=/opt/LDRLife
PYPI_MIRROR=${PYPI_MIRROR:-https://pypi.tuna.tsinghua.edu.cn/simple}

if [ ! -f ./main.py ] || [ ! -f ./config.toml ] || [ ! -f ./requirements.txt ]; then
  echo "当前目录需包含 main.py、requirements.txt 与 config.toml"; exit 1
fi
if ! command -v python3 >/dev/null; then
  echo "需要 python3（建议 3.10+）"; exit 1
fi

mkdir -p "$APP_DIR/data"
# 全部服务端文件收敛到 /opt/LDRLife（含 venv），卸载脚本一并清除
for f in *.py requirements.txt; do
  [ -e "$f" ] && cp -r "$f" "$APP_DIR/"
done
[ -f ./config.example.toml ] && cp -f ./config.example.toml "$APP_DIR/"

python3 -m venv "$APP_DIR/venv"
"$APP_DIR/venv/bin/pip" install -q -r "$APP_DIR/requirements.txt" -i "$PYPI_MIRROR"

cat > "$APP_DIR/uninstall.sh" <<'EOF'
#!/usr/bin/env bash
# 一键卸载：停服务 + 删 unit + 删 /opt/LDRLife（不触碰其他服务）
set -euo pipefail
systemctl stop ldrlife 2>/dev/null || true
systemctl disable ldrlife 2>/dev/null || true
rm -f /etc/systemd/system/ldrlife.service
systemctl daemon-reload
rm -rf /opt/LDRLife
echo "LDRLife 已完全卸载"
EOF
chmod 0700 "$APP_DIR/uninstall.sh"

cat > /etc/systemd/system/ldrlife.service <<'EOF'
[Unit]
Description=LDRLife relay server (Python3/FastAPI)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/LDRLife
ExecStart=/opt/LDRLife/venv/bin/python /opt/LDRLife/main.py -c /opt/LDRLife/config.toml
Restart=always
RestartSec=3
StandardOutput=journal
StandardError=journal
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now ldrlife
echo "已安装并启动：systemctl status ldrlife"
