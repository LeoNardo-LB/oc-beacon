#!/usr/bin/env bash
# =============================================================================
# OC Beacon 版本号校验 / 推进脚本（三级递进规则，docs/release-workflow.md §2.5）
#
# 用法:
#   ./scripts/version.sh validate                 # 校验 version.properties 形态 + git tag 三级递进链
#   ./scripts/version.sh next [版本号] [选项]      # 输出下一个合法版本号（纯计算，不落盘）
#
#   next:
#     版本号省略时读 version.properties 当前 VERSION_NAME
#     --bump=major|minor|patch   正式版开新线的增量类型（默认 patch，对应 fix 类 +0.0.1）
#     --dev                      dev 版线内迭代（X.Y.Z-dev.N → X.Y.Z-dev.N+1）
#
# 三级递进规则（2026-08-24 用户定规）：
#   同一 X.Y.Z 必须依次走 dev → beta → 正式（stable），不得跳级；
#   正式 tag 存在时，同线 beta 与 dev.N tag 必须都已存在；
#   自 0.3.1 版本线起强制，更早线为历史存量（不溯及，§7 历史 Tag 不删）。
#
# 退出码: 0 = 校验通过 / 正常输出；非 0 = 校验失败或输入非法（可挂 CI）
# 只读 version.properties 与 git tag，不做任何写操作。
# =============================================================================
set -euo pipefail

# ---- 配置 ----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(basename "$SCRIPT_DIR")" = "scripts" ]; then
  ROOT="$(dirname "$SCRIPT_DIR")"
else
  ROOT="$SCRIPT_DIR"
fi
cd "$ROOT"

VERSION_FILE="version.properties"
TAG_PREFIX="v"
# 三级递进强制生效的起始版本线（含）；更早线为规则确立前的历史存量，豁免校验
ENFORCE_SINCE="0.3.1"

log()  { printf '\033[36m[version]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[version][warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[version][error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '4,18p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

# 语义版本比较：$1 >= $2 为真（含相等）
version_ge() { test "$1" = "$2" || test "$(printf '%s\n' "$@" | sort -V | head -n1)" != "$1"; }

# 读取 version.properties 的 VERSION_NAME（与 CI 提取方式一致，绝不写该文件）
read_version_name() {
  grep '^VERSION_NAME=' "$VERSION_FILE" | cut -d'=' -f2
}

# 获取最后一个正式版 tag（不含预发布标签，如 v0.2.0），无则返回空
last_stable_tag() {
  git tag --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n1 || true
}

# 校验版本号形态：X.Y.Z | X.Y.Z-beta | X.Y.Z-dev.N（N>=1）
# 注：beta.N（如 0.3.0-beta.9）是旧惯例；规则确立后 beta 每线单发无序号（§2.1）
is_valid_form() {
  local v="$1"
  if ! [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-dev\.[0-9]+|-beta)?$ ]]; then
    return 1
  fi
  if [[ "$v" == *"-dev."* ]] && [[ "${v##*-dev.}" -eq 0 ]]; then
    return 1  # dev 序号从 1 开始
  fi
  return 0
}

# 解析版本号 → "MAJOR MINOR PATCH LABEL"（label 为 dev.N / beta / 空）
parse_version() {
  local v="$1"
  local base="${v%%-*}"
  local label=""
  if [[ "$v" == *"-"* ]]; then
    label="${v#*-}"
  fi
  local major="${base%%.*}"; local rest="${base#*.}"
  local minor="${rest%%.*}"; local patch="${rest#*.}"
  echo "$major $minor $patch $label"
}

# 对版本号应用 bump
apply_bump() {
  local major="$1" minor="$2" patch="$3" bump="$4"
  case "$bump" in
    major) echo "$((major+1)).0.0" ;;
    minor) echo "$major.$((minor+1)).0" ;;
    patch) echo "$major.$minor.$((patch+1))" ;;
  esac
}

# =============================================================================
# validate：校验 version.properties 形态 + git tag 三级递进链
# =============================================================================
cmd_validate() {
  local errors=0

  # ---- 1. version.properties 形态 ----
  if [ ! -f "$VERSION_FILE" ]; then
    die "未找到 $VERSION_FILE"
  fi
  local cur
  cur="$(read_version_name)"
  [ -z "$cur" ] && die "VERSION_NAME 为空"
  if ! is_valid_form "$cur"; then
    printf '\033[31m[version][error]\033[0m %s\n' "version.properties 形态不合法: $cur（合法: X.Y.Z | X.Y.Z-beta | X.Y.Z-dev.N，N>=1）" >&2
    errors=$((errors+1))
  else
    log "version.properties 形态合法: $cur"
  fi
  local cur_base="${cur%%-*}"

  local last_stable
  last_stable="$(last_stable_tag)"

  # ---- 2. 防回退护栏（§3.3）：properties 不得落后于已发布正式版 ----
  if is_valid_form "$cur" && [ -n "$last_stable" ]; then
    local stable_base
    stable_base="${last_stable#v}"; stable_base="${stable_base%%-*}"
    if version_ge "$stable_base" "$cur_base" && [ "$stable_base" != "$cur_base" ]; then
      printf '\033[31m[version][error]\033[0m %s\n' "version.properties ($cur) 落后于已发布正式版 ($stable_base)" >&2
      errors=$((errors+1))
    fi
  fi

  # ---- 3. tag 三级递进链（仅校验 >= ENFORCE_SINCE 的版本线）----
  # 逐 tag 收集每条线的 dev/beta/stable 状态与时间戳（annotated tag 的 creatordate）
  local tag base suffix when
  declare -A LINE_DEV LINE_BETA LINE_STABLE LINE_DEV_TIME LINE_BETA_TIME LINE_STABLE_TIME
  while read -r tag when; do
    [ -z "$tag" ] && continue
    base="${tag#$TAG_PREFIX}"; base="${base%%-*}"
    suffix=""
    if [[ "${tag#$TAG_PREFIX}" == *"-"* ]]; then suffix="${tag#$TAG_PREFIX}"; suffix="${suffix#*-}"; fi
    version_ge "$base" "$ENFORCE_SINCE" || continue
    if [[ "$suffix" =~ ^beta\.[0-9]+$ ]]; then
      printf '\033[31m[version][error]\033[0m %s\n' "tag $tag 违规: $base 线处于强制范围（>= $ENFORCE_SINCE），beta 每线单发无序号（§2.1）" >&2
      errors=$((errors+1))
      continue
    fi
    case "$suffix" in
      dev.*)  LINE_DEV[$base]=1; LINE_DEV_TIME[$base]="${LINE_DEV_TIME[$base]:-} $when" ;;
      beta)   LINE_BETA[$base]=1; LINE_BETA_TIME[$base]="$when" ;;
      "")     LINE_STABLE[$base]=1; LINE_STABLE_TIME[$base]="$when" ;;
    esac
  done < <(git for-each-ref refs/tags --format='%(refname:short) %(creatordate:unix)')

  # 汇总判违规（assoc 数组去重，保证每条线只查一次；不走管道，避免子 shell 丢计数）
  local b first_dev beta_t stable_t
  declare -A REPORTED=()
  for b in "${!LINE_STABLE[@]}" "${!LINE_BETA[@]}"; do
    [ -n "${REPORTED[$b]:-}" ] && continue
    REPORTED[$b]=1
    # beta 存在 → 之前必须有 dev
    if [ -n "${LINE_BETA[$b]:-}" ] && [ -z "${LINE_DEV[$b]:-}" ]; then
      printf '\033[31m[version][error]\033[0m %s\n' "版本线 $b 跳级: 存在 v$b-beta 但没有任何 v$b-dev.N tag（必须先走开发版）" >&2
      errors=$((errors+1))
    fi
    # 正式存在 → 必须先有 beta 和 dev
    if [ -n "${LINE_STABLE[$b]:-}" ]; then
      if [ -z "${LINE_BETA[$b]:-}" ]; then
        printf '\033[31m[version][error]\033[0m %s\n' "版本线 $b 跳级: 存在正式 tag v$b 但没有 v$b-beta（必须 dev → beta → 正式）" >&2
        errors=$((errors+1))
      fi
      if [ -z "${LINE_DEV[$b]:-}" ]; then
        printf '\033[31m[version][error]\033[0m %s\n' "版本线 $b 跳级: 存在正式 tag v$b 但没有任何 v$b-dev.N tag" >&2
        errors=$((errors+1))
      fi
    fi
    # 时间顺序：首个 dev <= beta <= 正式（同刻视为满足）
    if [ -n "${LINE_DEV[$b]:-}" ] && [ -n "${LINE_BETA[$b]:-}" ]; then
      first_dev="$(echo ${LINE_DEV_TIME[$b]} | tr ' ' '\n' | sort -n | grep -v '^$' | head -n1)"
      beta_t="${LINE_BETA_TIME[$b]}"
      if [ "$first_dev" -gt "$beta_t" ]; then
        printf '\033[31m[version][error]\033[0m %s\n' "版本线 $b 顺序违规: beta tag 早于首个 dev tag" >&2
        errors=$((errors+1))
      fi
    fi
    if [ -n "${LINE_BETA[$b]:-}" ] && [ -n "${LINE_STABLE[$b]:-}" ]; then
      beta_t="${LINE_BETA_TIME[$b]}"; stable_t="${LINE_STABLE_TIME[$b]}"
      if [ "$beta_t" -gt "$stable_t" ]; then
        printf '\033[31m[version][error]\033[0m %s\n' "版本线 $b 顺序违规: 正式 tag 早于 beta tag" >&2
        errors=$((errors+1))
      fi
    fi
  done

  # ---- 4. 提示性信息（不判失败）----
  if [ -n "$last_stable" ]; then
    log "最后正式版 tag: $last_stable"
  else
    log "无正式版 tag（0.x 阶段）"
  fi
  if [ -n "${LINE_DEV[$cur_base]:-}" ] && [ -n "${LINE_BETA[$cur_base]:-}" ] && [ -z "${LINE_STABLE[$cur_base]:-}" ]; then
    log "当前线 $cur_base: dev → beta 已走完，待正式版晋升"
  fi
  if ! git rev-parse -q --verify "${TAG_PREFIX}${cur}" >/dev/null 2>&1; then
    warn "version.properties ($cur) 尚无对应 tag（手动流程 §5 中间态属正常）"
  fi

  if [ "$errors" -gt 0 ]; then
    die "校验失败：${errors} 处违规（规则见 docs/release-workflow.md §2.5）"
  fi
  log "✅ 校验通过：三级递进链无违规（强制范围 >= $ENFORCE_SINCE）"
}

# =============================================================================
# next：给定状态输出下一个合法版本号（纯计算，不落盘）
#   X.Y.Z-dev.N → X.Y.Z-beta（晋升；--dev 则 X.Y.Z-dev.N+1 线内迭代）
#   X.Y.Z-beta  → X.Y.Z（晋升正式）
#   X.Y.Z       → <bump 后的基座>-dev.1（开新线，默认 patch）
# =============================================================================
cmd_next() {
  local v="$1" bump="$2" dev_iter="$3"
  if ! is_valid_form "$v"; then
    die "版本号形态不合法: $v（合法: X.Y.Z | X.Y.Z-beta | X.Y.Z-dev.N）"
  fi
  local major minor patch label
  read -r major minor patch label <<< "$(parse_version "$v")"
  local base="$major.$minor.$patch"
  if $dev_iter; then
    if [[ "$label" != dev.* ]]; then
      die "--dev 仅适用于开发版（X.Y.Z-dev.N → X.Y.Z-dev.N+1）"
    fi
    local n="${label#dev.}"
    echo "$base-dev.$((n+1))"
    return 0
  fi
  case "$label" in
    dev.*)  echo "$base-beta" ;;
    beta)   echo "$base" ;;
    "")     echo "$(apply_bump "$major" "$minor" "$patch" "$bump")-dev.1" ;;
  esac
}

# =============================================================================
# 参数解析
# =============================================================================
CMD="${1:-}"
[ -z "$CMD" ] && usage

case "$CMD" in
  validate)
    [ $# -gt 1 ] && die "validate 不接受额外参数"
    cmd_validate
    ;;
  next)
    shift
    V="" BUMP="patch" DEV_ITER=false
    for arg in "$@"; do
      case "$arg" in
        --bump=*) BUMP="${arg#*=}" ;;
        --dev) DEV_ITER=true ;;
        -*) die "未知选项: $arg" ;;
        *) if [ -z "$V" ]; then V="$arg"; else die "多余的参数: $arg"; fi ;;
      esac
    done
    case "$BUMP" in
      major|minor|patch) ;;
      *) die "无效 --bump: $BUMP（应为 major | minor | patch）" ;;
    esac
    if [ -z "$V" ]; then
      V="$(read_version_name)"
      [ -z "$V" ] && die "未提供版本号且 $VERSION_FILE 的 VERSION_NAME 为空"
    fi
    cmd_next "$V" "$BUMP" "$DEV_ITER"
    ;;
  *)
    die "未知子命令: $CMD（应为 validate | next）"
    ;;
esac
