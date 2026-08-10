#!/usr/bin/env bash
# 会话列表滑动性能快速测量（模拟器专用，adb 直驱，无 Maestro 依赖）
# 用法: ./scripts/perf-quick.sh [-Rounds 3] [-Tag name] [-Pkg dev.leonardo.ocbeacon.dev]
# 前提: 应用已启动并在目标页面（会话列表/聊天页）
#
# 实现：纯 bash + awk，无 python/perl/node 依赖。
set -uo pipefail

Rounds=3
Tag="baseline"
Pkg="dev.leonardo.ocbeacon.dev"

# ---- 解析参数（兼容 -Key Value 与 -Key=Value 两种风格，对齐 PowerShell param）----
while [ $# -gt 0 ]; do
  case "$1" in
    -Rounds)    Rounds="$2"; shift 2 ;;
    -Rounds=*)  Rounds="${1#*=}"; shift ;;
    -Tag)       Tag="$2"; shift 2 ;;
    -Tag=*)     Tag="${1#*=}"; shift ;;
    -Pkg)       Pkg="$2"; shift 2 ;;
    -Pkg=*)     Pkg="${1#*=}"; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

# ---- 查找 adb（顺序：$ANDROID_HOME → $HOME/Android/Sdk → PATH）----
find_adb() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    printf '%s' "$ANDROID_HOME/platform-tools/adb"
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    printf '%s' "$HOME/Android/Sdk/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then
    command -v adb
  else
    return 1
  fi
}

Adb="$(find_adb)" || { echo "未找到 adb（检查 ANDROID_HOME / PATH）" >&2; exit 1; }

CYAN=$'\033[36m'; GREEN=$'\033[32m'; RESET=$'\033[0m'
printf '%s=== perf-quick (tag=%s, rounds=%s) ===%s\n' "$CYAN" "$Tag" "$Rounds" "$RESET"

# ---- 汇总累加器（用于多轮平均；janky 为浮点用 awk 累加）----
janky_sum=0; p90_sum=0; p99_sum=0; inp_sum=0; slowui_sum=0

for ((i = 1; i <= Rounds; i++)); do
  "$Adb" shell dumpsys gfxinfo "$Pkg" reset >/dev/null 2>&1
  # 5 次上滑 + 5 次下滑（固定参数，与真机脚本一致）
  "$Adb" shell 'for j in 1 2 3 4 5; do input swipe 540 1800 540 700 150; sleep 0.3; done' >/dev/null 2>&1
  "$Adb" shell 'for j in 1 2 3 4 5; do input swipe 540 700 540 1800 150; sleep 0.3; done' >/dev/null 2>&1
  sleep 1
  gfx="$("$Adb" shell dumpsys gfxinfo "$Pkg" 2>&1)"

  frames=0; janky=0; p50=0; p90=0; p95=0; p99=0; inp=0; slowui=0
  while IFS= read -r line; do
    if [[ $line =~ Total\ frames\ rendered:\ ([0-9]+) ]]; then
      frames="${BASH_REMATCH[1]}"
    elif [[ $line =~ Janky\ frames:\ ([0-9]+)\ \(([0-9.]+)%\) ]]; then
      janky="${BASH_REMATCH[2]}"
    elif [[ $line =~ 50th\ percentile:\ ([0-9]+)ms ]]; then
      p50="${BASH_REMATCH[1]}"
    elif [[ $line =~ 90th\ percentile:\ ([0-9]+)ms ]]; then
      p90="${BASH_REMATCH[1]}"
    elif [[ $line =~ 95th\ percentile:\ ([0-9]+)ms ]]; then
      p95="${BASH_REMATCH[1]}"
    elif [[ $line =~ 99th\ percentile:\ ([0-9]+)ms ]]; then
      p99="${BASH_REMATCH[1]}"
    elif [[ $line =~ Number\ High\ input\ latency:\ ([0-9]+) ]]; then
      inp="${BASH_REMATCH[1]}"
    elif [[ $line =~ Number\ Slow\ UI\ thread:\ ([0-9]+) ]]; then
      slowui="${BASH_REMATCH[1]}"
    fi
  done <<< "$gfx"

  janky_sum="$(awk -v a="$janky_sum" -v b="$janky" 'BEGIN{printf "%.4f", a+b}')"
  p90_sum=$((p90_sum + p90))
  p99_sum=$((p99_sum + p99))
  inp_sum=$((inp_sum + inp))
  slowui_sum=$((slowui_sum + slowui))

  printf '  R%s: frames=%s janky=%s%% p50=%s p90=%s p99=%s inpLat=%s slowUI=%s\n' \
    "$i" "$frames" "$janky" "$p50" "$p90" "$p99" "$inp" "$slowui"
done

if [ "$Rounds" -gt 1 ]; then
  avg_janky="$(awk -v s="$janky_sum" -v n="$Rounds" 'BEGIN{printf "%.2f", s/n}')"
  avg_p90="$(awk -v s="$p90_sum"   -v n="$Rounds" 'BEGIN{printf "%.1f", s/n}')"
  avg_p99="$(awk -v s="$p99_sum"   -v n="$Rounds" 'BEGIN{printf "%.1f", s/n}')"
  avg_inp="$(awk -v s="$inp_sum"   -v n="$Rounds" 'BEGIN{printf "%.0f", s/n}')"
  avg_slowui="$(awk -v s="$slowui_sum" -v n="$Rounds" 'BEGIN{printf "%.0f", s/n}')"
  printf '%s=== AVG (tag=%s): janky=%s%% p90=%sms p99=%sms inpLat=%s slowUI=%s ===%s\n' \
    "$GREEN" "$Tag" "$avg_janky" "$avg_p90" "$avg_p99" "$avg_inp" "$avg_slowui" "$RESET"
fi
