#!/bin/bash
set -u
S=emulator-5554
D="$(pwd)/docs/acceptance/2026-09-12-390-disconnect/evidence"
: > "$D/recovery-poll.txt"
for i in $(seq 1 24); do
  sleep 15
  adb -s $S shell uiautomator dump /sdcard/rp.xml >/dev/null 2>&1
  adb -s $S exec-out cat /sdcard/rp.xml > "$D/recovery-poll-$i.xml"
  python3 - "$i" "$D/recovery-poll-$i.xml" >> "$D/recovery-poll.txt" <<'PY'
import sys,re
i=sys.argv[1]; x=open(sys.argv[2],encoding='utf-8',errors='replace').read()
texts=[s for s in re.findall(r'text="([^"]*)"',x) if s.strip()]
st='?'
if 'Host-4199' in texts:
    idx=texts.index('Host-4199')
    for s in texts[idx+1:idx+6]:
        if '连接' in s: st=s; break
print(f"poll {i}: host4199={st!r}")
PY
  if grep -aq 'host4199=已连接' "$D/recovery-poll.txt"; then
    echo "RECONNECTED at poll $i $(date '+%H:%M:%S')" >> "$D/recovery-poll.txt"
    break
  fi
done
echo "POLL_DONE $(date '+%H:%M:%S')" >> "$D/recovery-poll.txt"
echo DONE
