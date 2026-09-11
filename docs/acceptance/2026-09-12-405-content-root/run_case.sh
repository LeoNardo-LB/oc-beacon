#!/bin/bash
# usage: run_case.sh <tag> swipe <x1> <y1> <x2> <y2> <dur> | tap <x> <y>
# 2026-09-12-405-content-root 复验版：Close / FAB 坐标一律从 dump 实测，不硬编
set -u
SER=emulator-5554
DIR=/home/leo-tkp/Documents/code/mine/oc-beacon/docs/acceptance/2026-09-12-405-content-root
PKG=dev.leonardo.ocbeacon.dev
TAG=$1; MODE=$2; shift 2

dump() { adb -s $SER shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; adb -s $SER shell cat /sdcard/window_dump.xml > "$1"; }
check() { python3 $DIR/check.py "$1" 2>/dev/null | sed 's/.*-> //'; }
# node_center <file> <needle> [attr] -> "x y"（首个匹配节点的中心）
node_center() { python3 $DIR/bounds.py "$1" "$2" "${3:-}" 2>/dev/null | head -1 | sed -n 's/.*center=(\([0-9]*\),\([0-9]*\)).*/\1 \2/p'; }

ensure_closed() {
  dump /tmp/_state.xml
  if [ "$(check /tmp/_state.xml)" = "SHEET_VISIBLE" ]; then
    C=$(python3 $DIR/bounds.py /tmp/_state.xml 关闭 content-desc 2>/dev/null | grep -v '关闭工作表' | head -1 | sed -n 's/.*center=(\([0-9]*\),\([0-9]*\)).*/\1 \2/p')
    if [ -n "$C" ]; then
      adb -s $SER shell input tap $C >/dev/null
    else
      C=$(node_center /tmp/_state.xml 关闭工作表 content-desc)
      if [ -n "$C" ]; then adb -s $SER shell input tap $C >/dev/null; else adb -s $SER shell input keyevent 4 >/dev/null; fi
    fi
    sleep 1.6
    dump /tmp/_state.xml
    if [ "$(check /tmp/_state.xml)" = "SHEET_VISIBLE" ]; then echo "WARN: sheet still visible after close attempt" >&2; fi
  fi
}
open_sheet() {
  dump /tmp/_home.xml
  C=$(node_center /tmp/_home.xml 快速定位 content-desc)
  if [ -z "$C" ]; then echo "ERR: FAB(快速定位) not found in dump" >&2; exit 1; fi
  adb -s $SER shell input tap $C >/dev/null
  sleep 2.6
}

ensure_closed
open_sheet
dump "$DIR/${TAG}_before.xml"
BV=$(check "$DIR/${TAG}_before.xml")
if [ "$BV" != "SHEET_VISIBLE" ]; then
  echo "$TAG mode=$MODE args=$* | BEFORE=$BV (INVALID: expected SHEET_VISIBLE, case aborted)"
  exit 2
fi
if [ "$MODE" = swipe ]; then
  adb -s $SER shell input swipe $1 $2 $3 $4 $5 >/dev/null
else
  adb -s $SER shell input tap $1 $2 >/dev/null
fi
sleep 1.6
dump "$DIR/${TAG}_after.xml"
echo "$TAG mode=$MODE args=$* | BEFORE=$BV AFTER=$(check "$DIR/${TAG}_after.xml")"
