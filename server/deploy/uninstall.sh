#!/usr/bin/env bash
# 一键卸载（在服务器上执行）：bash /opt/LDRLife/uninstall.sh
# 或本脚本复制过去执行。只清理本服务新增内容，绝不触碰其他服务。
set -euo pipefail
systemctl stop ldrlife 2>/dev/null || true
systemctl disable ldrlife 2>/dev/null || true
rm -f /etc/systemd/system/ldrlife.service
systemctl daemon-reload
rm -rf /opt/LDRLife
echo "LDRLife 已完全卸载"
