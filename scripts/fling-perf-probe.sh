#!/bin/bash
# fling-perf-probe.sh — #258 组合帧性能质量门（标准化 fast-fling 帧统计）
# 用法: fling-perf-probe.sh [趟数=3]
# 协议: 冷启 → 进测试会话 → 每趟 = logcat 清零 + gfxinfo reset + 12 记高速
#       fling（60ms 甩，穿透卡区与长文区）→ gfxinfo 采集（tr -d 回车符 防串列）。
# 红绿: p99 / legacy% 对照基线；FATAL 必须为 0。
set -e
SERIAL=e69a99d8
TRIPS=3
if [ -n "$1" ]; then TRIPS=$1; fi
PKG=dev.leonardo.ocbeacon.dev
cd "$(dirname "$0")/.."

./scripts/debug-entry.sh >/dev/null 2>&1
sleep 3
adb -s $SERIAL shell input tap 322 606
sleep 5
echo "trip,frames,janky_pct,legacy_pct,p90,p95,p99" > /tmp/perf-results.csv
FATAL_TOTAL=0
for trip in $(seq 1 $TRIPS); do
  adb -s $SERIAL logcat -c
  adb -s $SERIAL shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
  for r in $(seq 1 12); do
    adb -s $SERIAL shell input swipe 600 850 600 1980 60
    sleep 0.9
  done
  sleep 1.5
  F=$(adb -s $SERIAL logcat -d -b crash 2>/dev/null | grep -c FATAL || true)
  FATAL_TOTAL=$((FATAL_TOTAL + F))
  adb -s $SERIAL shell dumpsys gfxinfo $PKG 2>/dev/null | tr -d '\r' > /tmp/gfx.txt
  FRAMES=$(awk '/Total frames rendered:/ {print $4; exit}' /tmp/gfx.txt)
  JANKY=$(awk '/^Janky frames:/ {gsub(/[()%]/,""); print $4; exit}' /tmp/gfx.txt)
  LEGACY=$(awk '/Janky frames \(legacy\):/ {gsub(/[()%]/,""); print $5; exit}' /tmp/gfx.txt)
  P90=$(awk '/90th percentile:/ {gsub(/ms/,""); print $3; exit}' /tmp/gfx.txt)
  P95=$(awk '/95th percentile:/ {gsub(/ms/,""); print $3; exit}' /tmp/gfx.txt)
  P99=$(awk '/99th percentile:/ {gsub(/ms/,""); print $3; exit}' /tmp/gfx.txt)
  echo "$trip,${FRAMES:-NA},${JANKY:-NA},${LEGACY:-NA},${P90:-NA},${P95:-NA},${P99:-NA}" | tee -a /tmp/perf-results.csv
done
echo "FATAL total: $FATAL_TOTAL"
python3 scripts/perf-med.py
