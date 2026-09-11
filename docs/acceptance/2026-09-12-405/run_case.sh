#!/bin/bash
# usage: run_case.sh <tag> swipe <x1> <y1> <x2> <y2> <dur> | tap <x> <y>
set -u
SER=emulator-5554
DIR=/home/leo-tkp/Documents/code/mine/oc-beacon/docs/acceptance/2026-09-12-405
PKG=dev.leonardo.ocbeacon.dev
TAG=$1; MODE=$2; shift 2

dump() { adb -s $SER shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; adb -s $SER shell cat /sdcard/window_dump.xml > "$1"; }
check() { python3 $DIR/check.py "$1" 2>/dev/null | sed 's/.*-> //'; }
ensure_closed() {
  dump /tmp/_state.xml
  if [ "$(check /tmp/_state.xml)" = "SHEET_VISIBLE" ]; then
    adb -s $SER shell input tap 975 617 >/dev/null   # Close button
    sleep 1.6
  fi
}
open_sheet() {
  adb -s $SER shell input tap 912 2152 >/dev/null
  sleep 2.6
}

ensure_closed
open_sheet
dump "$DIR/${TAG}_before.xml"
if [ "$MODE" = swipe ]; then
  adb -s $SER shell input swipe $1 $2 $3 $4 $5 >/dev/null
else
  adb -s $SER shell input tap $1 $2 >/dev/null
fi
sleep 1.6
dump "$DIR/${TAG}_after.xml"
echo "$TAG mode=$MODE args=$* | BEFORE=$(check "$DIR/${TAG}_before.xml") AFTER=$(check "$DIR/${TAG}_after.xml")"
