#!/bin/bash
# pin_matrix.sh — #423 钉位回归判红环(diagnosing-bugs Phase 1)
# 同一位置重复 N 次 toggle:DOM 锚行前后位移 + episode 事实 → 红绿判定
# 用法: pin_matrix.sh <serial> <anchor_regex> <label> <repeats> [settle_ms] [tap_regex]
# tap_regex 缺省=anchor(点谁量谁);B 场景量外组头、点组内思考行(展开态文本节点
# 结构变化会使同文本 bounds 失真——测得 +28 伪影即此教训)
# 判红: 位移>12px / confirmed=false / 修正 consumed=0 且 err>=50(边缘残量)
set -u
S=${1:?serial}; ANCHOR=${2:?anchor_regex}; LABEL=${3:?label}; N=${4:-3}; SETTLE=${5:-3800}; TAPR=${6:-}
export ANDROID_ADB_SERVER_PORT=5038
A="adb -s $S"
DIR=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)

bounds() {
  $A exec-out uiautomator dump /dev/tty 2>/dev/null > $TMP/ui.xml
  python3 "$DIR/find_row.py" $TMP/ui.xml "$ANCHOR"
}

tapbounds() {
  $A exec-out uiautomator dump /dev/tty 2>/dev/null > $TMP/ui2.xml
  python3 "$DIR/find_row.py" $TMP/ui2.xml "${TAPR:-$ANCHOR}"
}

for i in $(seq 1 "$N"); do
  PID=$($A shell pidof dev.leonardo.ocbeacon.dev | tr -d '\r')
  [ -z "$PID" ] && { echo "== $LABEL #$i: NO_PROC"; exit 2; }
  $A logcat -c --pid=$PID 2>/dev/null

  B0=$(bounds)
  [ -z "$B0" ] && { echo "== $LABEL #$i: SKIP(no-anchor)"; continue; }
  IFS=, read -r x1 y1 x2 y2 <<< "$B0"
  T=$(tapbounds)
  if [ -n "$T" ]; then IFS=, read -r x1 y1 x2 y2 <<< "$T"; fi
  CX=$(( (x1+x2)/2 )); CY=$(( (y1+y2)/2 ))

  $A shell input tap "$CX" "$CY"
  sleep $(awk "BEGIN{print $SETTLE/1000}")

  B1=$(bounds)
  if [ -z "$B1" ]; then
    echo "== $LABEL #$i: after=OFFSCREEN(锚行被顶出屏——本身即RED)"
  else
    IFS=, read -r a1 b1 a2 b2 <<< "$B1"
    SHIFT=$(( b1 - y1 )); ABS=${SHIFT#-}
    VERDICT=GREEN; [ "$ABS" -gt 12 ] && VERDICT=RED
    LOG=$($A logcat -d --pid=$PID 2>/dev/null | grep -E 'CardExpand|SGB' | \
          grep -E 'CLICK|pin done|episode done|pin-ledger|pin-flush|exit-' | tail -8)
    CONF=$(echo "$LOG" | grep -o 'confirmed=[a-z]*' | tail -1)
    EDGE=$(echo "$LOG" | grep -cE 'consumed=0')
    [ "$CONF" = 'confirmed=false' ] && VERDICT=RED
    echo "== $LABEL #$i: tap($CX,$CY) y $y1->$b1 shift=$SHIFT [$VERDICT] $CONF edge0=$EDGE"
    echo "$LOG" | sed 's/^/    /'
  fi
done