#!/bin/bash
# ASCII 打字脚本（纯 keyevent）：真机 E2E 打字专用。
# 背景：禁 `input text`（合成键盘事件触发预测性 back 伪影——见 docs/real-device-testing.md E2E 纪律）；
# 此前唯一副本放 /tmp 曾随重启丢失，2026-08-21 入库（同 miui-install.sh 模式）。
# 用法: ./scripts/type.sh "text" [serial]   支持 a-z 0-9 空格 逗号 句点（中文/特殊字符走 intent 传参绕过）
S=${2:-e69a99d8}
TEXT="$1"
for (( i=0; i<${#TEXT}; i++ )); do
  c="${TEXT:i:1}"
  case "$c" in
    ' ') k=KEYCODE_SPACE;;
    [a-z]) k=KEYCODE_$(printf '%s' "$c" | tr 'a-z' 'A-Z');;
    [0-9]) k=KEYCODE_$c;;
    ',') k=KEYCODE_COMMA;;
    '.') k=KEYCODE_PERIOD;;
    *) continue;;
  esac
  adb -s $S shell input keyevent $k
done