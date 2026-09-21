#!/bin/bash
# capture.sh — 录屏+抽帧:红环脚本的标准帧源
# 用法: capture.sh <输出目录> [秒数=8]
# 产物: <目录>/rec.mp4 + f0001.png...(20fps)
# 依赖: adb(无线需 export ANDROID_ADB_SERVER_PORT=5038 并 -s <serial>)、ffmpeg
# 注意: 驱动交互(tap)的命令需在录屏启动后另开终端并行执行
set -eu
DIR=${1:?usage: capture.sh <out_dir> [seconds]}
SEC=${2:-8}
mkdir -p "$DIR"
DEV=/sdcard/prerender_rec.mp4
adb shell rm -f $DEV
adb shell screenrecord --time-limit "$SEC" $DEV &
RECPID=$!
sleep 1
trap 'kill $RECPID 2>/dev/null || true' EXIT
wait $RECPID || true
adb pull $DEV "$DIR/rec.mp4" >/dev/null
ffmpeg -hide_banner -loglevel error -i "$DIR/rec.mp4" -vf fps=20 "$DIR/f%04d.png"
count=$(find "$DIR" -maxdepth 1 -name "f[0-9]*.png" | wc -l)
echo "frames: $count"
