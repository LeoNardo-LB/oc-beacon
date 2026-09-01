#!/bin/bash
# #258 Stage A：fast-fling 期间 perfetto 采集（60s 覆盖 2 趟 12 记甩动 + 趟间回底）
set -e
SERIAL=192.168.110.239:5555
PKG=dev.leonardo.ocbeacon.dev
cd /home/leo-tkp/Documents/code/mine/oc-beacon

# 1) 冷启进会话列表 → 进第一个会话（与 fling-perf-probe.sh 同入口）
./scripts/debug-entry.sh "$SERIAL" >/dev/null 2>&1
sleep 3
adb -s $SERIAL shell input tap 322 606
sleep 6

# 2) stdin 喂配置起采集（小米 user 版 SELinux 拒读 /data/local/tmp 配置文件；
#    adb shell 挂满 60s 采集窗，与后续 fling adb 命令并行）
adb -s $SERIAL shell "rm -f /data/misc/perfetto-traces/258-fling.pftr"
adb -s $SERIAL shell "perfetto --txt -c - -o /data/misc/perfetto-traces/258-fling.pftr" < /tmp/258/perfetto-config.txt > /tmp/258/perfetto-stdout.log 2>&1 &
PERF_PID=$!
sleep 3

# 3) 趟 1：12 记同向甩（60ms 甩 = 脚本矩阵同款）
for r in $(seq 1 12); do
  adb -s $SERIAL shell input swipe 600 850 600 1980 60
  sleep 0.9
done
sleep 1.5
# 趟间回底（复用 1a03ed7e 复位段）
for r in $(seq 1 12); do
  adb -s $SERIAL shell input swipe 600 1980 600 850 60
  sleep 0.9
done
sleep 1.5
# 趟 2：再 12 记同向甩
for r in $(seq 1 12); do
  adb -s $SERIAL shell input swipe 600 850 600 1980 60
  sleep 0.9
done

# 4) 等满 60s 采集窗再拉取（甩动序列 ~41s 结束，perfetto duration=60s）
wait $PERF_PID || true
sleep 3
adb -s $SERIAL pull /data/misc/perfetto-traces/258-fling.pftr /tmp/258/trace.pftr
ls -la /tmp/258/trace.pftr
adb -s $SERIAL logcat -d -s perf-flng -t 400 > /tmp/258/logcat-perf-flng.txt 2>&1 || true
