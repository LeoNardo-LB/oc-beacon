#!/bin/bash
# dev-nav-goto.sh — 真机导航：任意屏态→目标会话聊天屏（取证前置）
# 屏态分派：服务器配置页→切 DSH「会话」入口；会话列表→顶行直进（勿 back——
# 列表页 back 会退回服务器页）；聊天屏→back 回列表重试。
# 设备标识支持 mDNS 名（IP 漂移对策）：DEV='adb-e69a99d8-yzT17Y._adb-tls-connect._tcp'
# 依赖 /tmp/jk-nav.py 行匹配窗（560-720，排除电池横幅）——或按需内联。
#!/bin/bash
# 用法: jk-goto-session.sh <行匹配词>  —— 从任意屏态进入目标会话聊天屏
ADB=/home/linuxbrew/.linuxbrew/bin/adb
export ANDROID_ADB_SERVER_PORT=5038
SERi=${DEV:-192.168.110.239:41925}
DUMP=$($ADB -s $SERi shell rm -f /sdcard/u.xml; $ADB -s $SERi shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; $ADB -s $SERi shell cat /sdcard/u.xml)
if echo "$DUMP" | grep -q 'Host-4199'; then
  $ADB -s $SERi shell input tap 461 1420; sleep 2
fi
for try in 1 2 3; do
  $ADB -s $SERi shell rm -f /sdcard/u.xml
  $ADB -s $SERi shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  $ADB -s $SERi shell cat /sdcard/u.xml > /tmp/jk-goto.xml
  if grep -q '搜索会话' /tmp/jk-goto.xml; then
    XY=$(python3 /tmp/jk-nav.py /tmp/jk-goto.xml | head -1)
    if [ -n "$XY" ]; then
      $ADB -s $SERi shell input tap $XY
      sleep 2.5
      exit 0
    fi
  else
    $ADB -s $SERi shell input tap 84 302; sleep 1.5
  fi
done
exit 1
