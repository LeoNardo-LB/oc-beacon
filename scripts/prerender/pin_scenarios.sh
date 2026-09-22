#!/bin/bash
# pin_scenarios.sh — 用户三场景 ×3 矩阵(diagnosing-bugs Phase 1 驱动器)
# A 中位-步骤组  B 组内-Step3 思考卡(组展开态)  C 中位-1+1 思考卡  D 贴底重进-步骤组
# 用法: pin_scenarios.sh <serial>
set -u
S=${1:?serial}
export ANDROID_ADB_SERVER_PORT=5038
A="adb -s $S"
DIR=$(cd "$(dirname "$0")" && pwd)

enter_session() {
  $A shell am force-stop dev.leonardo.ocbeacon.dev 2>/dev/null
  sleep 1
  $A shell input keyevent KEYCODE_WAKEUP 2>/dev/null
  ./scripts/debug-entry.sh "$S" dev.leonardo.ocbeacon.dev >/dev/null 2>&1
  sleep 2.5
  $A shell input tap 480 620
  sleep 4
}

# 漂移金丝雀:Grok 4.7 页脚行(结构恒定,任何 toggle 不重构;它动=整个对话被顶)
echo '########## A: 中位-步骤组 x3(金丝雀=Grok 页脚) ##########'
enter_session
bash "$DIR/pin_matrix.sh" "$S" 'Grok 4.7' A-group 3 8000 '1 步 · 1 个工具'

echo '########## B: 组内-Step3 思考卡 x3(先展开组) ##########'
# A 场景结束态不定:仅当 Step3 行不可见(组收起)时才点展开
$A exec-out uiautomator dump /dev/tty 2>/dev/null > /tmp/ui_b.xml
if ! python3 "$DIR/find_row.py" /tmp/ui_b.xml 'Step 3: one-sentence' | grep -q .; then
  $A shell input tap 220 1113
  sleep 4
fi
bash "$DIR/pin_matrix.sh" "$S" 'Grok 4.7' B-inGroupThink 3 8000 'Step 3: one-sentence'
# 复原:若组仍展开则收起
$A exec-out uiautomator dump /dev/tty 2>/dev/null > /tmp/ui_b2.xml
if python3 "$DIR/find_row.py" /tmp/ui_b2.xml 'Step 3: one-sentence' | grep -q .; then
  $A shell input tap 220 1113
  sleep 3
fi

echo '########## C: 中位-1+1 思考卡 x3(重置会话态) ##########'
enter_session
bash "$DIR/pin_matrix.sh" "$S" 'Grok 4.7' C-midThink 3 8000 'Simple question'

echo '########## D: 贴底重进-步骤组 x3 ##########'
enter_session
bash "$DIR/pin_matrix.sh" "$S" 'Grok 4.7' D-bottom-entry-group 3 8000 '1 步 · 1 个工具'