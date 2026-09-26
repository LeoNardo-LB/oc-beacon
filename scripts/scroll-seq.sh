#!/bin/bash
# scroll-seq.sh — 标准滑动取证序列（贴底跟随/卡顿对比协议用）
# 序列: 拖300px(400ms) -> 停1.2s -> fling600px(120ms) -> 停1.6s -> 拖400px(300ms) -> 停1.2s -> 回程拖300px(400ms)
#!/bin/bash
# jank-seq.sh — 非贴底滑动序列（卡顿测量标准手势，流式/非流式组复用）
# 序列: 拖300px(400ms) -> 停1.2s -> fling600px(120ms) -> 停1.6s -> 拖400px(300ms) -> 停1.2s -> 回程拖300px(400ms)
ADB=/home/linuxbrew/.linuxbrew/bin/adb
export ANDROID_ADB_SERVER_PORT=5038
SER=${DEV:-192.168.110.239:41925}
X=600; Y0=1600
$ADB -s $SER shell input swipe $X $Y0 $X 1900 400
sleep 1.2
$ADB -s $SER shell input swipe $X 1500 $X 2100 120
sleep 1.6
$ADB -s $SER shell input swipe $X 1550 $X 1950 300
sleep 1.2
$ADB -s $SER shell input swipe $X 1800 $X 1500 400
echo SEQ_DONE
