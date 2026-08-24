#!/bin/bash
# miui-grant-bal.sh — MIUI/HyperOS「后台弹出界面」权限自动授予（uiautomator 驱动）
#
# 用法: ./scripts/miui-grant-bal.sh <包名> [serial]
#   例: ./scripts/miui-grant-bal.sh dev.leonardo.ocbeacon.dev
#
# 背景（2026-08-24 实证，#210）:
#   - MIUI DeviceGuard 会在应用无前台窗口时拦截后台 Activity 启动：
#     logcat 标志 "MIUILOG- Permission Denied Activity" + "Abort background activity
#     starts"，startActivitySync 拿到 result code=102（START_ABORTED）后**永久等待**，
#     表现为 androidTest 静默挂死（0% CPU、无异常）；
#   - 「后台弹出界面」权限随**卸载**重置——卸载重装（跨签名切换/降级装 CI 包的必经
#     步骤）后必须重新授予，否则所有 launch Activity 的插桩测试全部挂死；
#   - adb 无法绕过：appops 无此操作名（bg_activity_start 报 Unknown operation），
#     pm set-app-locales 等 shell 命令在 HyperOS 上不可用。
#
# 路径: 应用设置 → 权限管理 → 其他权限 → 后台弹出界面 → 始终允许
# 前提: 设备解锁亮屏（设置页面导航需要）；按钮 zh 文案匹配。

set -u
PKG=$1
SERIAL=$2
[ -z "$PKG" ] && { echo "usage: miui-grant-bal.sh <包名> [serial]"; exit 2; }
[ -z "$SERIAL" ] && SERIAL=e69a99d8

dump() { # dump() <输出文件>
  for _ in 1 2 3 4; do
    adb -s $SERIAL shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb -s $SERIAL shell cat /sdcard/ui.xml > "$1" 2>/dev/null
    [ -s "$1" ] && return 0
    sleep 2
  done
  return 1
}

tap_text() { # tap_text <xml文件> <文案>
  local xy
  xy=$(python3 - "$1" "$2" <<'PYEOF'
import sys, re
xml = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'<node[^>]*text="' + re.escape(sys.argv[2]) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print(f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}" if m else "NOT_FOUND")
PYEOF
)
  echo "[miui-grant-bal] tap '$2' -> $xy"
  [ "$xy" = "NOT_FOUND" ] && return 1
  adb -s $SERIAL shell input tap $xy
  return 0
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

adb -s $SERIAL shell am start -n com.miui.securitycenter/com.miui.appmanager.ApplicationsDetailsActivity --es package_name $PKG
sleep 3
dump "$TMP/d1.xml" || { echo '[miui-grant-bal] dump d1 失败（设备解锁亮屏了吗？）'; exit 1; }
tap_text "$TMP/d1.xml" "权限管理" || { echo '[miui-grant-bal] 未找到 权限管理 入口'; exit 1; }
sleep 3
dump "$TMP/d2.xml" || exit 1
if ! grep -q "后台弹出界面" "$TMP/d2.xml"; then
  tap_text "$TMP/d2.xml" "其他权限" || { echo '[miui-grant-bal] 未找到 其他权限 分组'; exit 1; }
  sleep 3
  dump "$TMP/d3.xml" || exit 1
else
  cp "$TMP/d2.xml" "$TMP/d3.xml"
fi
grep -q "后台弹出界面" "$TMP/d3.xml" || { echo '[miui-grant-bal] 未找到 后台弹出界面 行'; exit 1; }
tap_text "$TMP/d3.xml" "后台弹出界面" || exit 1
sleep 2
dump "$TMP/d4.xml" || exit 1
tap_text "$TMP/d4.xml" "始终允许" || tap_text "$TMP/d4.xml" "允许" || { echo '[miui-grant-bal] 未找到 允许 选项'; exit 1; }
sleep 1
adb -s $SERIAL shell input keyevent 3
echo "[miui-grant-bal] 完成：$PKG 后台弹出界面 → 始终允许"
