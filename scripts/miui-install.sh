#!/bin/bash
# miui-install.sh — MIUI/HyperOS 全自动装包（首装确认弹窗自动点穿）
#
# 用法: ./scripts/miui-install.sh <apk路径> [serial] [额外 pm install 参数...]
#   例: ./scripts/miui-install.sh app/build/outputs/apk/dev/debug/app-dev-debug.apk
#       ./scripts/miui-install.sh foo.apk e69a99d8 -r -d
#
# 背景（2026-08-21 实证）:
#   - 覆盖安装（同签名 -r）走 pm install 静默通道，无弹窗；
#   - 全新安装（卸载后/新包名）MIUI 必弹用户确认——pm/cmd package/session 全被拦，
#     无人点击 25s 后报 INSTALL_FAILED_USER_RESTRICTED: Install canceled by user；
#   - 本脚本后台跑 pm install，同时轮询 uiautomator 树里的确认按钮（继续安装/安装/确定）
#     自动点掉——实现无人值守首装（实测 2s 内点一次即过）。
#
# 前提: 设备**解锁亮屏**（锁屏下弹窗无法显示，会直接 USER_RESTRICTED）。
#       按钮 zh 文案匹配；其他语言系统需在 BUTTONS 数组里补对应文案。

set -u
APK=$1; shift || true
SERIAL=${1:-e69a99d8}; [ "$#" -gt 0 ] && shift || true
EXTRA_ARGS="$@"

TMP=/data/local/tmp/miui_install.apk
echo "[miui-install] pushing $APK -> $SERIAL"
adb -s $SERIAL push "$APK" $TMP >/dev/null || { echo '[miui-install] push 失败'; exit 1; }

# 按钮匹配辅助：stdin = ui.xml，stdout = "x y" 或 "0 0"
FIND_BTN=$(mktemp)
cat > "$FIND_BTN" <<'PYEOF'
import sys, re
xml = sys.stdin.read()
for pat in ('继续安装', '安装', '确定'):
    m = re.search(r'(?:text|content-desc)="' + pat + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
        break
else:
    print('0 0')
PYEOF

echo "[miui-install] pm install $EXTRA_ARGS (后台) + 弹窗轮询"
adb -s $SERIAL shell pm install $EXTRA_ARGS $TMP > /tmp/miui_install_out.txt 2>&1 &
INSTALL_PID=$!

PKG_BASE=$(basename "$APK" | sed 's/^app-//' | cut -d- -f1)  # 仅日志用
for i in $(seq 1 20); do
  sleep 2
  # 已装上就提前收工
  if ! kill -0 $INSTALL_PID 2>/dev/null; then break; fi
  adb -s $SERIAL shell uiautomator dump /sdcard/miui_ui.xml >/dev/null 2>&1
  BTN=$(adb -s $SERIAL shell cat /sdcard/miui_ui.xml | tr -d '\r' | python3 "$FIND_BTN")
  if [ "$BTN" != "0 0" ]; then
    read BX BY <<< "$BTN"
    echo "[miui-install] 点确认按钮(第${i}轮): $BX $BY"
    adb -s $SERIAL shell input tap $BX $BY
  fi
done

wait $INSTALL_PID; RC=$?
cat /tmp/miui_install_out.txt
adb -s $SERIAL shell rm -f $TMP /sdcard/miui_ui.xml; rm -f "$FIND_BTN" /tmp/miui_install_out.txt
[ $RC -eq 0 ] && echo '[miui-install] ✅ 安装成功' || echo '[miui-install] ❌ 安装失败 (exit='$RC')'
exit $RC