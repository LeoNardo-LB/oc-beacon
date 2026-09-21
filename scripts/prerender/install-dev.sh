#!/bin/bash
# install-dev.sh — MIUI 无线 adb 静默装 dev 包(自动点「继续安装」弹框)
# 用法: install-dev.sh <apk路径> [serial] — 需 ANDROID_ADB_SERVER_PORT=5038
# 背景:MIUI 对 adb 全新安装弹确认框且静默拒绝(INSTALL_FAILED_USER_RESTRICTED),
# 本脚本 push+pm install 后轮询 uiautomator dump,出现「继续安装」即刻点击。
set -eu
APK=${1:?usage: install-dev.sh <apk> [serial]}
ADB="adb
if [ -n "${2:-}" ]; then ADB="$ADB -s $2"; fi
ADB="$ADB"
echo "$ADB shell true" >/dev/null
$ADB push "$APK" /data/local/tmp/pr_base.apk >/dev/null
($ADB shell pm install -r -t /data/local/tmp/pr_base.apk > /tmp/inst.log 2>&1) &
IP=$!
TAPPED=0
for i in $(seq 1 40); do
  sleep 0.7
  $ADB shell uiautomator dump /sdcard/w3.xml >/dev/null 2>&1 || true
  $ADB pull /sdcard/w3.xml /tmp/w3.xml >/dev/null 2>&1 || true
  if [ $TAPPED -eq 0 ] && grep -q "继续安装" /tmp/w3.xml 2>/dev/null; then
    BTN=$(python3 -c "import re;m=re.search(r'text=\"继续安装\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',open('/tmp/w3.xml',encoding='utf-8').read());print((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2) if m else ''")
    if [ -n "$BTN" ]; then $ADB shell input tap $BTN; TAPPED=1; echo "tapped dialog (poll $i)"; fi
  fi
done
wait $IP || true
cat /tmp/inst.log
grep -q Success /tmp/inst.log && echo INSTALL_OK || (echo INSTALL_FAIL; exit 1)
