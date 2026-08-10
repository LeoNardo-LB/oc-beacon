#!/usr/bin/env bash
# =============================================================================
# 会话列表滑动性能测量脚本（自动化、可重复、A/B 对比友好）
#
# 用法：
#   ./scripts/perf-session-scroll.sh [-Flavor dev] [-Rounds 3] [-Tag baseline]
#
# 功能：
#   1. 构建并安装指定 flavor 的 debug APK
#   2. 启动应用并导航到会话列表（Maestro 流程）
#   3. 每次 round：reset gfxinfo → 跑 Maestro 固定滑动 → 采集 gfxinfo 帧统计
#   4. 输出汇总报告（janky 率 / 分位数 / 输入延迟 / UI 线程阻塞），供 A/B 对比
#
# 依赖：Android SDK（adb）、Maestro CLI、应用已配置模拟器/真机连接
# 注意：真实滑动由 Maestro 驱动（固定参数），避免人手滑动的不一致性
#
# 实现：纯 bash + awk，无 python/perl/node 依赖。
# =============================================================================
set -uo pipefail

Flavor="dev"
Rounds=3
Tag="baseline"

while [ $# -gt 0 ]; do
  case "$1" in
    -Flavor)    Flavor="$2"; shift 2 ;;
    -Flavor=*)  Flavor="${1#*=}"; shift ;;
    -Rounds)    Rounds="$2"; shift 2 ;;
    -Rounds=*)  Rounds="${1#*=}"; shift ;;
    -Tag)       Tag="$2"; shift 2 ;;
    -Tag=*)     Tag="${1#*=}"; shift ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

# ---- 定位仓库根（构建/APK/Maestro 路径均相对仓库根）----------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(basename "$SCRIPT_DIR")" = "scripts" ]; then
  ROOT="$(dirname "$SCRIPT_DIR")"
else
  ROOT="$SCRIPT_DIR"
fi
cd "$ROOT"

# ---- 查找 adb ----
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
Maestro="maestro"

# applicationId 映射（与 build.gradle.kts productFlavors 一致）
case "$Flavor" in
  dev)    Pkg="dev.leonardo.ocbeacon.dev" ;;
  beta)   Pkg="dev.leonardo.ocbeacon.beta" ;;
  stable) Pkg="dev.leonardo.ocbeacon" ;;
  *) echo "Unknown flavor: $Flavor (use dev/beta/stable)" >&2; exit 1 ;;
esac

Apk="app/build/outputs/apk/$Flavor/debug/app-$Flavor-debug.apk"
# flavor 首字母大写（dev -> Dev），用于 gradle task 名
Flavor_cap="${Flavor^}"

CYAN=$'\033[36m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
printf '%s=== 会话列表滑动性能测试 ===%s\n' "$CYAN" "$RESET"
echo "Flavor: $Flavor | Package: $Pkg | Rounds: $Rounds | Tag: $Tag"
echo

# 0. 检查设备
if ! "$Adb" devices | grep -q 'device$'; then
  echo "No device connected" >&2
  exit 1
fi
echo "[OK] Device connected: $("$Adb" devices | grep 'device$' | head -n1 | sed 's/[[:space:]]*$//')"

# 1. 构建 + 安装
echo "[1/5] Building APK ($Flavor debug)..."
if ! ./gradlew ":app:assemble${Flavor_cap}Debug" --console=plain >/dev/null 2>&1; then
  echo "Build failed" >&2
  exit 1
fi
echo "[OK] Build done"

echo "[2/5] Installing APK..."
if ! "$Adb" install -r "$Apk" >/dev/null 2>&1; then
  echo "Install failed" >&2
  exit 1
fi
echo "[OK] Installed"

# 2. 启动应用
echo "[3/5] Launching app..."
"$Adb" shell am start -n "$Pkg/dev.leonardo.ocbeacon.MainActivity" >/dev/null 2>&1
sleep 4
# 确保屏幕唤醒 + 解锁（模拟器偶尔锁屏）
"$Adb" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$Adb" shell wm dismiss-keyguard >/dev/null 2>&1
sleep 2
echo "[OK] Launched"

# 3. 逐轮测量
declare -a r_frames=() r_janky=() r_p50=() r_p90=() r_p95=() r_p99=() r_inp=() r_slowui=() r_missvs=()
janky_sum=0; p90_sum=0; p99_sum=0; inp_sum=0; slowui_sum=0

for ((i = 1; i <= Rounds; i++)); do
  echo "[4/5] Round $i/$Rounds: reset + scroll + measure..."

  # reset gfxinfo 计数器（每轮独立窗口）
  "$Adb" shell dumpsys gfxinfo "$Pkg" reset >/dev/null 2>&1

  # 跑 Maestro 固定滑动流程（导航到会话列表 + 10 次固定滑动）
  if ! "$Maestro" test --env APP_ID="$Pkg" maestro/perf-session-scroll.yaml >/dev/null 2>&1; then
    printf '%sWarning: Maestro flow failed at round %s (retrying once)%s\n' "$YELLOW" "$i" "$RESET" >&2
    "$Maestro" test --env APP_ID="$Pkg" maestro/perf-session-scroll.yaml >/dev/null 2>&1
  fi

  # 采集帧统计
  gfx="$("$Adb" shell dumpsys gfxinfo "$Pkg" 2>&1)"
  frames=0; janky=0; p50=0; p90=0; p95=0; p99=0; inp=0; slowui=0; missvs=0
  while IFS= read -r line; do
    if   [[ $line =~ Total\ frames\ rendered:\ ([0-9]+) ]];         then frames="${BASH_REMATCH[1]}"
    elif [[ $line =~ Janky\ frames:\ ([0-9]+)\ \(([0-9.]+)%\) ]];   then janky="${BASH_REMATCH[2]}"
    elif [[ $line =~ 50th\ percentile:\ ([0-9]+)ms ]];             then p50="${BASH_REMATCH[1]}"
    elif [[ $line =~ 90th\ percentile:\ ([0-9]+)ms ]];             then p90="${BASH_REMATCH[1]}"
    elif [[ $line =~ 95th\ percentile:\ ([0-9]+)ms ]];             then p95="${BASH_REMATCH[1]}"
    elif [[ $line =~ 99th\ percentile:\ ([0-9]+)ms ]];             then p99="${BASH_REMATCH[1]}"
    elif [[ $line =~ Number\ High\ input\ latency:\ ([0-9]+) ]];   then inp="${BASH_REMATCH[1]}"
    elif [[ $line =~ Number\ Slow\ UI\ thread:\ ([0-9]+) ]];       then slowui="${BASH_REMATCH[1]}"
    elif [[ $line =~ Number\ Missed\ Vsync:\ ([0-9]+) ]];          then missvs="${BASH_REMATCH[1]}"
    fi
  done <<< "$gfx"

  r_frames+=("$frames"); r_janky+=("$janky"); r_p50+=("$p50"); r_p90+=("$p90")
  r_p95+=("$p95"); r_p99+=("$p99"); r_inp+=("$inp"); r_slowui+=("$slowui"); r_missvs+=("$missvs")

  janky_sum="$(awk -v a="$janky_sum" -v b="$janky" 'BEGIN{printf "%.4f", a+b}')"
  p90_sum=$((p90_sum + p90))
  p99_sum=$((p99_sum + p99))
  inp_sum=$((inp_sum + inp))
  slowui_sum=$((slowui_sum + slowui))
done

# 4. 输出汇总
printf '%s[5/5] Results (tag=%s):%s\n' "$CYAN" "$Tag" "$RESET"
printf '%-6s %-10s %-9s %-6s %-6s %-6s %-6s %-8s %-8s %-8s\n' \
  "Round" "Frames" "Janky%" "P50" "P90" "P95" "P99" "InpLat" "SlowUI" "MissVS"
for ((i = 0; i < Rounds; i++)); do
  printf '%-6s %-10s %-9s %-6s %-6s %-6s %-6s %-8s %-8s %-8s\n' \
    "$((i + 1))" "${r_frames[$i]}" "${r_janky[$i]}" "${r_p50[$i]}" "${r_p90[$i]}" \
    "${r_p95[$i]}" "${r_p99[$i]}" "${r_inp[$i]}" "${r_slowui[$i]}" "${r_missvs[$i]}"
done

# 平均
if [ "$Rounds" -gt 1 ]; then
  avg_janky="$(awk -v s="$janky_sum"   -v n="$Rounds" 'BEGIN{printf "%.2f", s/n}')"
  avg_p90="$(awk -v s="$p90_sum"       -v n="$Rounds" 'BEGIN{printf "%.1f", s/n}')"
  avg_p99="$(awk -v s="$p99_sum"       -v n="$Rounds" 'BEGIN{printf "%.1f", s/n}')"
  avg_inp="$(awk -v s="$inp_sum"       -v n="$Rounds" 'BEGIN{printf "%.0f", s/n}')"
  avg_slowui="$(awk -v s="$slowui_sum" -v n="$Rounds" 'BEGIN{printf "%.0f", s/n}')"
  echo
  printf '%s=== AVERAGE (tag=%s) ===%s\n' "$GREEN" "$Tag" "$RESET"
  printf 'Janky%%: %s | P90: %sms | P99: %sms | InpLat: %s | SlowUI: %s\n' \
    "$avg_janky" "$avg_p90" "$avg_p99" "$avg_inp" "$avg_slowui"
fi

echo
printf '%s=== DONE (tag=%s) ===%s\n' "$CYAN" "$Tag" "$RESET"
