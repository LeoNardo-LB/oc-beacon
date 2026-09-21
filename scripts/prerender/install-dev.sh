#!/bin/bash
# install-dev.sh — MIUI 无线 adb 静默装 dev 包(自动点「继续安装」弹框)
# 用法: install-dev.sh <apk路径> [serial] — 建议 export ANDROID_ADB_SERVER_PORT=5038
# 背景:MIUI 对 adb 全新安装弹「USB安装提示」确认框,无人值守时静默拒绝
#      (INSTALL_FAILED_USER_RESTRICTED)。本脚本 push+pm install 后轮询
#      uiautomator dump,出现「继续安装」即刻点击(定位器 find_install_btn.py)。
set -eu
APK=${1:?usage: install-dev.sh <apk> [serial]}
SERIAL=${2:-}
if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); else ADB=(adb); fi
DIR=$(cd "$(dirname "$0")" && pwd)
TD=$(mktemp -d); trap 'rm -rf "$TD"' EXIT
"${ADB[@]}" push "$APK" /data/local/tmp/pr_base.apk >/dev/null
("${ADB[@]}" shell pm install -r -t /data/local/tmp/pr_base.apk > "$TD/inst.log" 2>&1) &
IP=$!
TAPPED=0
for i in $(seq 1 40); do
  # 探活早退:安装进程已结束(秒装/已失败)则不再空转轮询
  if ! kill -0 $IP 2>/dev/null; then break; fi
  sleep 0.7
  "${ADB[@]}" shell uiautomator dump /sdcard/w3.xml >/dev/null 2>&1 || true
  "${ADB[@]}" pull /sdcard/w3.xml "$TD/w3.xml" >/dev/null 2>&1 || true
  if [ $TAPPED -eq 0 ] && [ -f "$TD/w3.xml" ]; then
    BTN=$(python3 "$DIR/find_install_btn.py" "$TD/w3.xml" || true)
    if [ -n "$BTN" ]; then "${ADB[@]}" shell input tap $BTN; TAPPED=1; echo "tapped dialog (poll $i)"; fi
  fi
done
wait $IP || true
cat "$TD/inst.log"
grep -q Success "$TD/inst.log" && echo INSTALL_OK || (echo INSTALL_FAIL; exit 1)
