#!/usr/bin/env bash
# =============================================================================
# OC Beacon 测试包一键构建脚本（#548：beta 测试包版本序号自动递增——免人工判定）
#
# 用法:
#   ./scripts/testbuild.sh <flavor> [serial] [--no-install] [--dry-run]
#
#   flavor:       beta | dev（stable 拒绝——正式发版必须走 release.sh 确认门）
#   serial:       可选 adb serial；缺省自动探测第一台在线设备
#   --no-install: 只构建不装机
#   --dry-run:    只打印计划，不改任何文件、不构建、不装机
#
# 做什么（免人工判读 docs/release-workflow.md §2 定规，2026-10-10 用户裁决）:
#   beta: 同线纯序号递增（beta-N → beta-(N+1) / bare -beta → -beta-2，属「纯预发布
#         序号递增豁免」域）+ VERSION_CODE 同步 +1 落盘 → chore commit（仅含
#         version.properties）→ assembleBetaRelease → 可选无线装机
#   dev:  versionCode 走 Unix 时间戳自动递增（build.gradle.kts 2026-08-13 决策），
#         免 bump 免 commit，直接 assembleDevRelease → 装机
#   越域一律拒绝并指回 ./scripts/release.sh（任何版本线 bump / 通道晋升 / 正式
#   发布须用户确认——本脚本只做免确认域内的纯序号递增）
#
# 序号基准（与 release.sh 互补，2026-10-19 #548 设计定规）:
#   本地测试包不发 tag，version.properties 就是序号真相源（properties 领先 tag 是
#   常态：当前 tag bare v0.4.0-beta=1 号，properties 已 beta-6）；tag 序号仅作防回退
#   护栏（checkout 过时/CI 已发更高序号时取 max 并 warn）。release.sh 以 tag 为基准
#   是因为发版即发 tag——两者语义互补不冲突。
#
# 纪律继承（AGENTS.md / docs/release-workflow.md）:
#   - 先改 version.properties 再 assemble（严禁反序——版本号内嵌 APK）
#   - Gradle 同一 checkout 禁并发；完整构建超时 600s（timeout 包裹）
#   - 覆盖安装同签名：本地 app/keystore/signing.properties → release.jks（与 CI 同源）
# =============================================================================
set -euo pipefail

# ---- 定位仓库根（同 release.sh 模式）--------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(basename "$SCRIPT_DIR")" = "scripts" ]; then
  ROOT="$(dirname "$SCRIPT_DIR")"
else
  ROOT="$SCRIPT_DIR"
fi
cd "$ROOT"

# adb 解析三级回退：ADB 变量 → PATH → SDK 惯例路径（2026-10-19 #548 实测教训：
# 本机 adb 在 platform-tools 下且 PATH/ANDROID_HOME 均无——裸 adb 让装机段静默断链）
resolve_adb() {
  if [ -n "${ADB:-}" ] && command -v "${ADB}" >/dev/null 2>&1; then echo "$ADB"; return; fi
  local c
  for c in "$(command -v adb || true)" \
           "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" \
           "${ANDROID_SDK_ROOT:-/nonexistent}/platform-tools/adb" \
           "$HOME/Android/Sdk/platform-tools/adb" \
           "$HOME/android-sdk/platform-tools/adb"; do
    if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; return; fi
  done
  echo "adb"  # 最终回退：PATH 依赖（失败可见，不静默）
}
ADB="$(resolve_adb)"
VERSION_FILE="version.properties"
TAG_PREFIX="v"

log()  { printf '\033[36m[testbuild]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[testbuild][warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[testbuild][error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---- 参数解析 --------------------------------------------------------------
FLAVOR=""
SERIAL=""
NO_INSTALL=false
DRY_RUN=false

while [ $# -gt 0 ]; do
  case "$1" in
    beta|dev) FLAVOR="$1" ;;
    stable) die "stable 正式发版必须走 ./scripts/release.sh（版本提升确认门）" ;;
    --no-install) NO_INSTALL=true ;;
    --dry-run) DRY_RUN=true ;;
    -*) die "未知选项: $1（支持 --no-install / --dry-run）" ;;
    *) [ -z "$SERIAL" ] && SERIAL="$1" || die "多余参数: $1（用法: testbuild.sh <flavor> [serial] [--no-install] [--dry-run]）" ;;
  esac
  shift
done
[ -n "$FLAVOR" ] || die "用法: ./scripts/testbuild.sh <flavor> [serial] [--no-install] [--dry-run]（flavor: beta | dev）"

# flavor → 包名 / assemble 任务
case "$FLAVOR" in
  beta) PKG="dev.leonardo.ocbeacon.beta";  GRADLE_TASK=":app:assembleBetaRelease"; APK_GLOB="app/build/outputs/apk/beta/release/*.apk" ;;
  dev)  PKG="dev.leonardo.ocbeacon.dev";   GRADLE_TASK=":app:assembleDevRelease";  APK_GLOB="app/build/outputs/apk/dev/release/*.apk" ;;
esac

# ---- 读 version.properties --------------------------------------------------
[ -f "$VERSION_FILE" ] || die "未找到 $VERSION_FILE"
CUR_CODE="$(grep '^VERSION_CODE=' "$VERSION_FILE" | cut -d'=' -f2 || true)"
CUR_NAME="$(grep '^VERSION_NAME=' "$VERSION_FILE" | cut -d'=' -f2 || true)"
[ -n "$CUR_CODE" ] && [ -n "$CUR_NAME" ] || die "version.properties 解析失败（CODE='$CUR_CODE' NAME='$CUR_NAME'）"
log "当前版本: $CUR_NAME (code=$CUR_CODE)"

# ---- 版本判定：同线纯序号递增 or 越域拒绝 ----------------------------------
# tag 防回退护栏（移植 release.sh last_beta_num）：bare -beta 视为 1 号
last_beta_num() {
  local base="$1" max=0 n
  if git rev-parse -q --verify "${TAG_PREFIX}${base}-beta" >/dev/null 2>&1; then
    max=1
  fi
  n="$(git tag --list "${TAG_PREFIX}${base}-beta-*" | sed -E "s/^v${base//./\\.}-beta-//" | grep -E '^[0-9]+$' | sort -n | tail -n1 || true)"
  if [ -n "$n" ] && [ "$n" -gt "$max" ]; then max="$n"; fi
  echo "$max"
}

BUMP=false
case "$FLAVOR" in
  beta)
    if [[ "$CUR_NAME" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-beta(-([0-9]+))?$ ]]; then
      BASE="${BASH_REMATCH[1]}"
      N_PROPS="${BASH_REMATCH[3]:-1}"   # bare -beta 视为第 1 号（release-workflow §2.1）
      N_TAG="$(last_beta_num "$BASE")"
      if [ "$N_TAG" -gt "$N_PROPS" ]; then
        warn "properties 序号($N_PROPS) 落后于已发布 tag 序号($N_TAG)——本地 checkout 可能过时，以 tag 为基准递增"
        N="$N_TAG"
      else
        N="$N_PROPS"
      fi
      NEW_NAME="$BASE-beta-$((N + 1))"
      NEW_CODE=$((CUR_CODE + 1))
      BUMP=true
      log "纯序号递增（免确认域）: $CUR_NAME → $NEW_NAME (code=$NEW_CODE)"
    else
      die "越域拒绝：当前 $CUR_NAME 不是 beta 线预发布版本。
  - 若是 dev 线（-dev.N）→ beta 属通道晋升，须用户确认；
  - 若无预发布后缀 → stable 线/正式版起点，同样须确认。
  请走 ./scripts/release.sh beta（内置版本提升确认门），不要用本脚本越域。"
    fi
    ;;
  dev)
    # dev versionCode 走时间戳（build.gradle.kts 2026-08-13 决策），properties 免动
    NEW_NAME="$CUR_NAME"; NEW_CODE="$CUR_CODE"
    [[ "$CUR_NAME" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-dev\.[0-9]+$ ]] \
      || warn "当前 $CUR_NAME 非 dev 线标识——dev 包仅用包名区分共存，versionName 显示沿用 properties（无害）"
    log "dev 免 bump（versionCode 构建时取 Unix 时间戳）: $CUR_NAME"
    ;;
esac

# ---- 脏工作树提示（不阻止——测试包常在开发中途打；commit 只含 version.properties）----
if [ -n "$(git status --porcelain 2>/dev/null)" ] && ! $DRY_RUN; then
  warn "git 工作树不干净——APK 将包含未提交变更（chore commit 仅含 version.properties，不卷入其他文件）"
fi

# ---- dry-run 汇总退出 -------------------------------------------------------
if $DRY_RUN; then
  log "[dry-run] $( $BUMP && echo "将写入 $VERSION_FILE: VERSION_CODE=$NEW_CODE / VERSION_NAME=$NEW_NAME" || echo "不修改 $VERSION_FILE" )"
  $BUMP && log "[dry-run] 将执行: git add version.properties && git commit -m 'chore: bump version to $NEW_NAME'"
  log "[dry-run] 将执行: timeout 600 ./gradlew $GRADLE_TASK"
  $NO_INSTALL || log "[dry-run] 将执行: 装机到 ${SERIAL:-<自动探测的第一台设备>}（wake+解锁+miui-install 覆盖装）+ versionCode/versionName 回读校验"
  log "✅ dry-run 完成，未做任何修改。"
  exit 0
fi

# ---- 1. bump 落盘（先改 properties 再 assemble——AGENTS 红线）----------------
if $BUMP; then
  printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$NEW_CODE" "$NEW_NAME" > "$VERSION_FILE"
  log "已写入 $VERSION_FILE: $NEW_NAME (code=$NEW_CODE)"
  # chore commit：只含 version.properties（脏树不卷入）；无变更则跳过
  git add "$VERSION_FILE"
  if git diff --cached --quiet; then
    warn "version.properties 无实质变更，跳过 chore commit"
  else
    git commit -m "chore: bump version to $NEW_NAME" --quiet
    log "chore commit: $(git log -1 --format='%h %s')"
  fi
fi

# ---- 2. 构建（完整构建超时 600s；同一 checkout 禁并发 gradle——AGENTS 纪律）----
log "构建: ./gradlew $GRADLE_TASK"
if ! timeout 600 ./gradlew "$GRADLE_TASK"; then
  die "构建失败（exit=$?）。注意：$($BUMP && echo "版本号 $NEW_NAME/$NEW_CODE 已消耗并 commit（versionCode 只增不减，弃用无害）" || echo 'dev 免 bump 无消耗')"
fi

# ---- 3. 定位 APK -------------------------------------------------------------
APK="$(ls $APK_GLOB 2>/dev/null | grep -v '\-unsigned\.apk$' | head -n1 || true)"
if [ -z "$APK" ]; then
  # 全部产物里没有 signed 命名时退回第一个（部分环境命名无 -unsigned 后缀）
  APK="$(ls $APK_GLOB 2>/dev/null | head -n1 || true)"
fi
[ -n "$APK" ] || die "未找到构建产物（$APK_GLOB）"
log "APK: $APK"

if $NO_INSTALL; then
  log "✅ 构建完成（--no-install，未装机）: $NEW_NAME (code=$($BUMP && echo "$NEW_CODE" || echo "时间戳"))  →  $APK"
  exit 0
fi

# ---- 4. 装机（可选无线：wake + 解锁 + MIUI 弹窗点穿）-------------------------
# serial 自动探测：保留完整 serial（mDNS 名可内嵌空格——2026-10-10 journal §466 教训，
# 禁 awk $1 截断）；多台设备时列出全部并取第一台
if [ -z "$SERIAL" ]; then
  SERIALS="$($ADB devices | sed -n 's/^\(.*[^[:space:]]\)[[:space:]]\{1,\}device$/\1/p')"
  [ -n "$SERIALS" ] || die "无在线设备（adb devices 为空）——或用 --no-install 只构建"
  echo "$SERIALS" | sed 's/^/  在线设备: /'
  if [ "$(echo "$SERIALS" | wc -l)" -gt 1 ]; then
    SERIAL="$(echo "$SERIALS" | head -n1)"
    warn "多台设备在线，默认装到第一台（$SERIAL）——如需指定请显式传 serial"
  else
    SERIAL="$SERIALS"
  fi
fi
log "目标设备: $SERIAL"

# 唤醒 + 解锁（perf-session-scroll.sh 同款；锁屏下 MIUI 装包弹窗无法显示）
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 1

# MIUI 守卫装包（push + pm install -r 覆盖装 + 确认弹窗自动点穿）；
# 透传 ADB（miui-install.sh 缺省走 PATH，本机 adb 常在 platform-tools 下不在 PATH）
ADB="$ADB" "$SCRIPT_DIR/miui-install.sh" "$APK" "$SERIAL" -r || die "装机失败（miui-install.sh exit=$?）"

# ---- 5. 装后校验：versionCode/versionName 回读 -------------------------------
# dumpsys 输出形如「versionCode=47 minSdk=26 …」/「versionName=0.4.0-beta-7」——
# 匹配带词边界（versionCode=4 不得误配 versionCode=47）
sleep 1
INSTALLED="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" | grep -E 'versionCode=|versionName=' | head -2 | tr -d '\r' || true)"
log "装后校验:"
echo "$INSTALLED" | sed 's/^/  /'
if $BUMP; then
  echo "$INSTALLED" | grep -qE "versionCode=$NEW_CODE( |\$)" \
    || die "装后 versionCode 校验失败（期望 $NEW_CODE，实得：$INSTALLED）"
  echo "$INSTALLED" | grep -qE "versionName=$NEW_NAME( |\$)" \
    || die "装后 versionName 校验失败（期望 $NEW_NAME，实得：$INSTALLED）"
  log "✅ 测试包已装机并通过版本校验: $NEW_NAME (code=$NEW_CODE) → $SERIAL"
else
  log "✅ dev 测试包已装机（versionCode 时间戳自动递增）→ $SERIAL"
fi
