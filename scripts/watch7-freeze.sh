#!/bin/bash
# =============================================================================
# watch7-freeze.sh — #550/#545 冻结监视器 v7（2026-10-20 备货，复发时即用）
#
# 相比 watch6b（gfxinfo 10s 零新帧检测）的升级：
#   - 整合 #545 FreezeWatchdog 广播黑匣子：冻结嫌疑出现时自动
#     am broadcast -a dev.ocbeacon.DEBUG_FREEZE_DUMP 拉进程内快照
#     （主线程栈+activityLifecycle+contentView 树——DEBUG 包专用；release 包退化为
#     仅外部取证：dump/syscall 采样/截图/logcat）
#   - uiautomator 空树判据（<5KB=组合内容真空签名，#545 定谱）
#   - /proc syscall 采样（主线程+RenderThread 的 epoll/futex 形态）
#   - 自动取证包落 /tmp/watch7/<ts>/，含 force-stop 复位前的完整现场
#
# 用法: ./scripts/watch7-freeze.sh <serial> [包名] [间隔秒]
#   例: ./scripts/watch7-freeze.sh 192.168.1.2:35091 dev.leonardo.ocbeacon.beta 30
# 前提: 设备解锁亮屏、app 可前台；建议 svc power stayon（长时监视）
# =============================================================================
set -u
SER=${1:?用法: watch7-freeze.sh <serial> [包名] [间隔秒]}
PKG=${2:-dev.leonardo.ocbeacon.dev}
GAP=${3:-30}
ADB="${ADB:-$(command -v adb || true)}"
[ -n "$ADB" ] || for c in "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" \
                            "${ANDROID_SDK_ROOT:-/nonexistent}/platform-tools/adb" \
                            "$HOME/Android/Sdk/platform-tools/adb" \
                            "$HOME/android-sdk/platform-tools/adb"; do
  [ -x "$c" ] && ADB="$c" && break
done
ADB="${ADB:-adb}"
OUT=/tmp/watch7; mkdir -p $OUT

frames() { "$ADB" -s "$SER" shell dumpsys gfxinfo "$PKG" 2>/dev/null | grep -oE 'Total frames rendered: [0-9]+' | grep -oE '[0-9]+' || echo 0; }
dump_size() { "$ADB" -s "$SER" shell uiautomator dump /sdcard/w7.xml >/dev/null 2>&1; sleep 0.6; "$ADB" -s "$SER" shell cat /sdcard/w7.xml 2>/dev/null | wc -c; }
focus_pkg() { "$ADB" -s "$SER" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -c "$PKG"; }

TS=$(date +%H%M%S)
PREV_F=$(frames); PREV_T=$(date +%s)
echo "[watch7] start serial=$SER pkg=$PKG gap=${GAP}s baseline_frames=$PREV_F"
while true; do
  sleep "$GAP"
  [ "$(focus_pkg)" -ge 1 ] || continue   # 仅前台监视
  CUR_F=$(frames); CUR_T=$(date +%s)
  D=$(( CUR_F - PREV_F )); DT=$(( CUR_T - PREV_T ))
  if [ "$D" -eq 0 ] && [ "$DT" -ge "$GAP" ]; then
    SZ=$(dump_size)
    # 2026-10-21 判据改进（10h 浸泡 133 误报定罪后）：静止页零帧≠冻结——
    # 真冻结=零帧 **且** dump 空树（<5KB，#545 组合内容真空签名）。
    # 静止页（回复完成等待窗等合法静止）轻记跳过，不落取证包不刷屏。
    if [ "$SZ" -lt 5000 ] 2>/dev/null; then
      echo "[watch7] $(date +%H:%M:%S) FREEZE-CONFIRMED: frames=$CUR_F (+0/${DT}s) dump=${SZ}B 空树签名"
    else
      echo "[watch7] $(date +%H:%M:%S) still(idle): frames +0/${DT}s dump=${SZ}B 正常内容，跳过"
      PREV_F=$(frames); PREV_T=$(date +%s); continue
    fi
    E="$OUT/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$E"
    # a. 黑匣子拉快照（DEBUG 包；release 包此步无响应不影响后续）
    "$ADB" -s "$SER" logcat -c
    "$ADB" -s "$SER" shell am broadcast -a dev.ocbeacon.DEBUG_FREEZE_DUMP >/dev/null 2>&1
    sleep 3
    "$ADB" -s "$SER" logcat -d 2>/dev/null > "$E/logcat.log"
    grep -a "FREEZE-WATCH" "$E/logcat.log" > "$E/blackbox.txt" || true
    # b. 外部取证
    PID=$("$ADB" -s "$SER" shell pidof "$PKG" | tr -d '\r')
    if [ -n "$PID" ]; then
      "$ADB" -s "$SER" shell run-as "$PKG" cat /proc/$PID/task/$PID/syscall > "$E/main-syscall.txt" 2>&1
      for t in $("$ADB" -s "$SER" shell run-as "$PKG" ls /proc/$PID/task | tr -d '\r'); do
        echo "$t $("$ADB" -s "$SER" shell run-as "$PKG" cat /proc/$PID/task/$t/comm 2>/dev/null | tr -d '\r')" >> "$E/threads.txt"
      done
    fi
    "$ADB" -s "$SER" shell uiautomator dump /sdcard/w7f.xml >/dev/null 2>&1
    "$ADB" -s "$SER" shell cat /sdcard/w7f.xml > "$E/dump-frozen.xml" 2>/dev/null
    "$ADB" -s "$SER" shell screencap -p /sdcard/w7.png 2>/dev/null && "$ADB" -s "$SER" pull /sdcard/w7.png "$E/shot.png" >/dev/null 2>&1
    "$ADB" -s "$SER" shell dumpsys window > "$E/window.txt" 2>/dev/null
    echo "[watch7] evidence → $E（blackbox=$(wc -l < "$E/blackbox.txt" 2>/dev/null || echo 0) 行）"
    # 不 force-stop——持续观察自愈性（#545 判据：仅 force-stop 可恢复）
    # 复位由人工决定；复位后脚本自动以新基线继续
    PREV_F=$(frames); PREV_T=$(date +%s)
  else
    PREV_F=$CUR_F; PREV_T=$CUR_T
  fi
done
