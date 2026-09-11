#!/bin/bash
set -u
S=emulator-5554
D="$(pwd)/docs/acceptance/2026-09-12-390-disconnect/evidence"
mkdir -p "$D"
: > "$D/dumps-summary.txt"

adb -s $S logcat -c
adb -s $S logcat -v time > "$D/logcat-disconnect.txt" 2>&1 &
LOGPID=$!
sleep 1

START=$(date +%s.%N)
echo "start_epoch=$START" >> "$D/dumps-summary.txt"
adb -s $S reverse --remove tcp:4199
REVTOFF=$(date +%s.%N)
echo "reverse_removed_epoch=$REVTOFF" >> "$D/dumps-summary.txt"
echo "reverse_after_remove:" >> "$D/dumps-summary.txt"
adb -s $S reverse --list >> "$D/dumps-summary.txt"

for i in $(seq 0 18); do
  t=$((i*5))
  f="$D/disc-t$(printf %03d $t).xml"
  adb -s $S shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1
  adb -s $S exec-out cat /sdcard/d.xml > "$f"
  NOW=$(date +%s.%N)
  python3 - "$t" "$NOW" "$REVTOFF" "$f" >> "$D/dumps-summary.txt" <<'PY'
import sys,re
t=sys.argv[1]; now=float(sys.argv[2]); revoff=float(sys.argv[3]); path=sys.argv[4]
x=open(path,encoding='utf-8',errors='replace').read()
texts=[s for s in re.findall(r'text="([^"]*)"',x) if s.strip()]
res=list(dict.fromkeys(s for s in re.findall(r'resource-id="([^"]*)"',x) if s.strip()))
banner='服务器已断开，正在重连' in x
print(f"--- t={t}s elapsed={now-revoff:.1f}s banner={banner} resids={res}")
print("   texts=" + " || ".join(texts))
PY
  sleep 5
done
kill $LOGPID 2>/dev/null
wait $LOGPID 2>/dev/null
echo "EXPERIMENT_DONE" >> "$D/dumps-summary.txt"
echo DONE
