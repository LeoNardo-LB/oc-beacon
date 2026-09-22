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
  ./scripts/debug-entry.sh "$S" dev.leonardo.ocbeacon.dev >/dev/null 2>&1
  sleep 2.5
  $A shell input tap 480 620
  sleep 4
}

echo '########## A: 中位-步骤组 x3 ##########'
enter_session
bash "$DIR/pin_matrix.sh" "$S" '1 步 · 1 个工具' A-group 3

echo '########## B: 组内-Step3 思考卡 x3(先展开组) ##########'
$A shell input tap 220 1113
sleep 4
bash "$DIR/pin_matrix.sh" "$S" 'Step 3: one-sentence summary' B-inGroupThink 3
$A shell input tap 220 1113
sleep 3

echo '########## C: 中位-1+1 思考卡 x3 ##########'
bash "$DIR/pin_matrix.sh" "$S" 'Simple question' C-midThink 3

echo '########## D: 贴底重进-步骤组 x3 ##########'
enter_session
bash "$DIR/pin_matrix.sh" "$S" '1 步 · 1 个工具' D-bottom-entry-group 3