#!/bin/bash
# debug-entry.sh — 真机测试标准入口：debug intent 直达会话列表（第一优先级）
#
# 用法: ./scripts/debug-entry.sh [serial] [包名]
#   例: ./scripts/debug-entry.sh                # 默认 e69a99d8 + dev 包
#       ./scripts/debug-entry.sh e69a99d8 dev.leonardo.ocbeacon.dev
#
# 做什么（2026-08-25 定规，用户指令「debug 进入会话列表优先级提一级」）:
#   1. adb reverse tcp:4199（重启 adb/设备后必做，幂等）
#   2. force-stop 后用 debug intent 冷启（--es debug_url/username/password/name）
#   3. 等待并校验 logcat 标志：Debug channel activated + NavGraph: Debug channel → SessionList
#
# 为什么这是标准入口（而非手工导航）:
#   - 免手工输 URL/密码（input text 被禁用）；
#   - 免 Settings → Sessions → 行点击链——坐标易错、BACK 易把应用退到桌面、
#     uiautomator dump 陈旧文件误判，三者在 2026-08-25 #222 E2E 中连续踩坑；
#   - 一条命令得到确定的起点：已连接指定服务器 + 停在会话列表。
#
# 密码来源: /persistent/home/leo-tkp/.config/opencode/service.json 的 password 字段
#   （AGENTS.md「验证与测试」节同源）。可用 OCBEACEN_SERVICE_JSON 覆盖路径。

set -eu

SERIAL=${1:-e69a99d8}
PKG=${2:-dev.leonardo.ocbeacon.dev}
SERVICE_JSON=${OCBEACEN_SERVICE_JSON:-/persistent/home/leo-tkp/.config/opencode/service.json}

PW=$(python3 -c "import json; print(json.load(open('$SERVICE_JSON'))['password'])")
if [ -z "$PW" ]; then echo "ERROR: password empty from $SERVICE_JSON" >&2; exit 1; fi

adb -s "$SERIAL" reverse tcp:4199 tcp:4199
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop "$PKG"
sleep 1
adb -s "$SERIAL" shell am start -n "$PKG/dev.leonardo.ocbeacon.MainActivity" \
  --es debug_url http://127.0.0.1:4199 \
  --es debug_username opencode \
  --es debug_password "$PW" \
  --es debug_name Host-4199 >/dev/null

# 校验标志（最多 15s）
for i in $(seq 1 15); do
  sleep 1
  if adb -s "$SERIAL" logcat -d 2>/dev/null | grep -aq 'NavGraph: Debug channel → SessionList'; then
    echo "OK: Debug channel → SessionList (server connected, app at session list)"
    adb -s "$SERIAL" logcat -d 2>/dev/null | grep -aE 'Debug channel (requested|activated)|NavGraph: Debug channel' | tail -3
    exit 0
  fi
done
echo "FAIL: SessionList 标志未出现（检查 reverse / 服务器 / 密码）" >&2
exit 1
