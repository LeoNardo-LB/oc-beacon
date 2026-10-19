#!/usr/bin/env bash
# =============================================================================
# OC Beacon 一键发版脚本
#
# 用法:
#   ./scripts/release.sh <flavor> [--dry-run] [--force-bump=major|minor|patch]
#
#   flavor:      beta（默认）| stable | dev
#   --dry-run:   只打印将执行的步骤，不修改任何文件、不推送
#   --force-bump: 强制开新版本线并指定递进类型（正常流程开新线由脚本自动判断）
#
# 版本线模型：同一 X.Y.Z 走完 dev → beta → 正式（通道切换不 bump）；
# 当前线发布过正式版后，下一次发版自动开新线（feat→+0.1.0 / fix→+0.0.1）；
# beta 线内序号迭代（-beta-N，bare -beta 视为 1 号，2026-10-10 用户定规）；
# 任何版本号提升（新线/通道晋升/正式发布）须用户确认，纯序号递增豁免（同上定规）。
#
# 功能:
#   1. 检查 git 工作树干净
#   2. 分析 commits（last tag -> HEAD）推导 bump 类型
#   3. 计算新版本号（含 VERSION_CODE 递增）
#   4. 更新 version.properties
#   5. 生成 RELEASE_NOTES.md 草稿（所有 flavor）+ 更新 CHANGELOG.md（仅 stable）
#   6. commit + tag + push（触发 CI 构建与 Release）
#
# 详见 docs/release-workflow.md（发版前必读）
# =============================================================================
set -euo pipefail

# ---- 配置 ----------------------------------------------------------------
# 定位仓库根：支持脚本位于 scripts/ 子目录或仓库根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(basename "$SCRIPT_DIR")" = "scripts" ]; then
  ROOT="$(dirname "$SCRIPT_DIR")"
else
  ROOT="$SCRIPT_DIR"
fi
cd "$ROOT"

FLAVOR="${1:-beta}"
DRY_RUN=false
FORCE_BUMP=""

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --force-bump=*) FORCE_BUMP="${arg#*=}" ;;
  esac
done

case "$FLAVOR" in
  beta|stable|dev) ;;
  *) echo "❌ 未知 flavor: $FLAVOR（应为 beta | stable | dev）"; exit 1 ;;
esac

case "$FORCE_BUMP" in
  ""|major|minor|patch) ;;
  *) echo "❌ 无效 --force-bump: $FORCE_BUMP（应为 major | minor | patch）"; exit 1 ;;
esac

TAG_PREFIX="v"
VERSION_FILE="version.properties"
CHANGELOG_FILE="CHANGELOG.md"
RELEASE_NOTES_FILE="RELEASE_NOTES.md"
REMOTE="origin"
BRANCH="master"

# ---- 工具函数 ------------------------------------------------------------
log()  { printf '\033[36m[release]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[release][warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[release][error]\033[0m %s\n' "$*" >&2; exit 1; }

# python 派发（2026-10-02 发版实战：本机仅 python3——脚本原裸调 python 中断）
PY_BIN="$(command -v python3 || command -v python || true)"
py()  { [ -n "$PY_BIN" ] || die "python3/python 均不可用"; "$PY_BIN" "$@"; }

run() {
  if $DRY_RUN; then
    log "[dry-run] $*"
  else
    log "$*"
    "$@"
  fi
}

# 语义版本比较（支持 MAJOR.MINOR.PATCH 及预发布标签）
version_gt() { test "$(printf '%s\n' "$@" | sort -V | head -n1)" != "$1"; }

# 获取最后一个正式版 tag（不含预发布标签，如 v1.0.3），无则返回空
last_stable_tag() {
  git tag --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n1 || true
}

# 查询某版本线（base+label）已发布的最大预发布序号；无则输出空
# 例：base=0.3.1 label=dev，存在 v0.3.1-dev.22 → 输出 22
last_prerelease_num() {
  local base="$1" label="$2"
  git tag --list "v${base}-${label}.*" | sed -E "s/^v${base//./\\.}-${label}\.//" | sort -n | tail -n1
}

# 查询某版本线 beta 通道已发布的最大序号（2026-10-10 用户定规：beta 线内序号迭代）
# bare -beta（旧惯例）视为第 1 号；-beta-N 取最大 N；无 beta tag 输出 0
last_beta_num() {
  local base="$1" max=0 n
  if git rev-parse -q --verify "${TAG_PREFIX}${base}-beta" >/dev/null 2>&1; then
    max=1
  fi
  n="$(git tag --list "${TAG_PREFIX}${base}-beta-*" | sed -E "s/^v${base//./\\.}-beta-//" | grep -E '^[0-9]+$' | sort -n | tail -n1 || true)"
  if [ -n "$n" ] && [ "$n" -gt "$max" ]; then max="$n"; fi
  echo "$max"
}

# 最近一个「非当前版本线」的 tag（任何通道，含正式版）——通道切换发版的变更基准。
# 例：当前线 0.3.1 → 跳过全部 v0.3.1*，返回 v0.3.0-beta.3（上一个对外边界）。
last_external_tag() {
  local base="$1" t tb
  while read -r t; do
    tb="${t#v}"; tb="${tb%%-*}"
    if [ "$tb" != "$base" ]; then echo "$t"; return; fi
  done < <(git tag --sort=-v:refname)
}

# 从 commit 信息推导 bump 类型
derive_bump() {
  local since="$1"
  local commits
  commits=$(git log --no-merges --format='%s' "${since}..HEAD" 2>/dev/null || true)
  if [ -z "$commits" ]; then
    echo "patch"  # 无 commits 时最小递进
    return
  fi
  if echo "$commits" | grep -qiE 'BREAKING CHANGE|^[a-z]+\(?.*\)?!:'; then
    echo "major"
  elif echo "$commits" | grep -qE '^feat(\(|:)'; then
    echo "minor"
  else
    echo "patch"
  fi
}

# 解析版本号
parse_version() {
  # $1: 版本字符串，如 1.0.3-beta.1
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

# 生成 CHANGELOG 条目（last stable tag -> HEAD）
gen_changelog_entry() {
  local since="$1" version="$2"
  local added="" fixed="" changed="" removed=""
  local line
  git log --no-merges --format='%s' "${since}..HEAD" 2>/dev/null | while IFS= read -r line; do
    # 去掉 commit type 前缀，保留描述
    local desc
    desc="$(echo "$line" | sed -E 's/^[a-z]+(\([^)]*\))?!?: ?//')"
    case "$line" in
      feat!*|feat\(*\)!*|BREAKING*) removed="$removed- $desc\n" ;;
      feat:*|feat\(*\):*) added="$added- $desc\n" ;;
      fix:*|fix\(*\):*) fixed="$fixed- $desc\n" ;;
      perf:*|perf\(*\):*|refactor:*|refactor\(*\):*) changed="$changed- $desc\n" ;;
      *) : ;;  # docs/chore/test/style/build/ci 不写入
    esac
  done > /dev/null

  # 由于管道子 shell 变量不共享，重新收集
  added=""; fixed=""; changed=""; removed=""
  while IFS= read -r line; do
    # 排除内部维护类 commit（test/ci/docs/chore 等，含 scope 变体）
    case "$line" in
      fix\(test\):*|fix\(ci\):*|fix\(docs\):*|chore*|docs:*|docs\(*\):*|test:*|test\(*\):*|style:*|style\(*\):*|build:*|build\(*\):*|ci:*|ci\(*\):*) continue ;;
    esac
    local desc
    desc="$(echo "$line" | sed -E 's/^[a-z]+(\([^)]*\))?!?: ?//')"
    case "$line" in
      feat!*|feat\(*\)!*|BREAKING*) removed="$removed- $desc"$'\n' ;;
      feat:*|feat\(*\):*) added="$added- $desc"$'\n' ;;
      fix:*|fix\(*\):*) fixed="$fixed- $desc"$'\n' ;;
      perf:*|perf\(*\):*|refactor:*|refactor\(*\):*) changed="$changed- $desc"$'\n' ;;
      *) : ;;
    esac
  done < <(git log --no-merges --format='%s' "${since}..HEAD" 2>/dev/null || true)

  local date_str
  date_str="$(date +%Y-%m-%d)"

  local entry="## [$version] - $date_str"$'\n\n'
  if [ -n "$removed" ]; then entry+="### Removed"$'\n\n'"$removed"$'\n'; fi
  if [ -n "$added" ]; then entry+="### Added"$'\n\n'"$added"$'\n'; fi
  if [ -n "$changed" ]; then entry+="### Changed"$'\n\n'"$changed"$'\n'; fi
  if [ -n "$fixed" ]; then entry+="### Fixed"$'\n\n'"$fixed"$'\n'; fi
  if [ -z "$added$fixed$changed$removed" ]; then
    entry+="_No user-facing changes._"$'\n'
  fi
  printf '%s' "$entry"
}

# 生成 Release Notes 草稿（last tag -> HEAD，所有 flavor）
# 输出为 GitHub Release 说明模板（docs/release-notes-template.md），发布者润色后随发版 commit 提交
gen_release_notes() {
  local since="$1" version="$2"
  local added="" fixed="" changed="" removed=""
  local line
  while IFS= read -r line; do
    # 排除内部维护类 commit（test/ci/docs/chore 等，含 scope 变体）
    case "$line" in
      fix\(test\):*|fix\(ci\):*|fix\(docs\):*|chore*|docs:*|docs\(*\):*|test:*|test\(*\):*|style:*|style\(*\):*|build:*|build\(*\):*|ci:*|ci\(*\):*) continue ;;
    esac
    local desc
    desc="$(echo "$line" | sed -E 's/^[a-z]+(\([^)]*\))?!?: ?//')"
    case "$line" in
      feat!*|feat\(*\)!*|BREAKING*) removed="$removed- **BREAKING:** $desc"$'\n' ;;
      feat:*|feat\(*\):*) added="$added- $desc"$'\n' ;;
      fix:*|fix\(*\):*) fixed="$fixed- $desc"$'\n' ;;
      perf:*|perf\(*\):*|refactor:*|refactor\(*\):*) changed="$changed- $desc"$'\n' ;;
      *) : ;;
    esac
  done < <(git log --no-merges --format='%s' "${since}..HEAD" 2>/dev/null || true)

  local date_str
  date_str="$(date +%Y-%m-%d)"

  local entry="## OC Beacon $version — $date_str"$'\n\n'
  entry+="> 版本摘要：（待填写——本版主题一句话）"$'\n\n'
  if [ -n "$removed" ]; then entry+="### Removed"$'\n\n'"$removed"$'\n'; fi
  if [ -n "$added" ]; then entry+="### Added"$'\n\n'"$added"$'\n'; fi
  if [ -n "$changed" ]; then entry+="### Changed"$'\n\n'"$changed"$'\n'; fi
  if [ -n "$fixed" ]; then entry+="### Fixed"$'\n\n'"$fixed"$'\n'; fi
  if [ -z "$added$fixed$changed$removed" ]; then
    entry+="_No user-facing changes._"$'\n'
  fi
  if [ -n "$since" ]; then
    entry+=$'\n'"---"$'\n'
    entry+="完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/${since}...${TAG_PREFIX}${version})"$'\n'
  fi
  printf '%s' "$entry"
}

# =============================================================================
# 1. 前置检查
# =============================================================================
log "flavor=$FLAVOR  dry_run=$DRY_RUN  force_bump=${FORCE_BUMP:-auto}"

if ! $DRY_RUN; then
  if [ -n "$(git status --porcelain)" ]; then
    die "git 工作树不干净，请先提交或 stash：$(git status --porcelain | head -n5)"
  fi
  if ! git diff --cached --quiet; then
    die "有暂存未提交的变更，请先提交。"
  fi
fi

if [ ! -f "$VERSION_FILE" ]; then
  die "未找到 $VERSION_FILE"
fi

# =============================================================================
# 2. 读取当前版本 + 推导新版本
# =============================================================================
CUR_VERSION_CODE="$(grep '^VERSION_CODE=' "$VERSION_FILE" | cut -d'=' -f2)"
CUR_VERSION_NAME="$(grep '^VERSION_NAME=' "$VERSION_FILE" | cut -d'=' -f2)"
[ -z "$CUR_VERSION_CODE" ] && die "VERSION_CODE 为空"
[ -z "$CUR_VERSION_NAME" ] && die "VERSION_NAME 为空"
log "当前版本: $CUR_VERSION_NAME (code=$CUR_VERSION_CODE)"

LAST_STABLE="$(last_stable_tag)"
log "最后一个正式版 tag: ${LAST_STABLE:-<无>}"

# ---------------------------------------------------------------------------
# 版本线模型（2026-08-23 重构，用户定规）：
#   同一 X.Y.Z 依次走 dev → beta → 正式（stable），通道切换不 bump 版本号；
#   新特性/修复累积后开新线：dev 线上迭代（dev.N 递增）→ 满意后 beta → 正式。
#   开新线的唯一时机：当前线已发布过正式版（v{BASE} tag 存在），或显式 --force-bump。
#   新线版本号 = 当前线 base 按 commit 类型递进（feat→MINOR，fix→PATCH，BREAKING→MAJOR）。
# ---------------------------------------------------------------------------
read -r C_MAJOR C_MINOR C_PATCH C_LABEL <<< "$(parse_version "$CUR_VERSION_NAME")"
C_LABEL="${C_LABEL:-}"
CUR_BASE="$C_MAJOR.$C_MINOR.$C_PATCH"

# 预发布标签名
case "$FLAVOR" in
  beta) LABEL="beta" ;;
  dev)  LABEL="dev" ;;
  stable) LABEL="" ;;
esac

# 防回退护栏：version.properties 落后于已发布正式版时（历史遗留/手工误改），
# 以最后正式版为当前线起点——绝不产出比已发布正式版更小的版本号。
STABLE_BASE="${LAST_STABLE#v}"; STABLE_BASE="${STABLE_BASE%%-*}"
if [ -n "$STABLE_BASE" ] && version_gt "$STABLE_BASE" "$CUR_BASE"; then
  warn "version.properties ($CUR_VERSION_NAME) 落后于最后正式版 ($STABLE_BASE)，以 $STABLE_BASE 为当前线基准"
  CUR_BASE="$STABLE_BASE"
  read -r C_MAJOR C_MINOR C_PATCH C_LABEL <<< "$(parse_version "$CUR_BASE-")"
fi

# 通道闭合检测（该 base 在目标通道已发过 → 不能重发同名 tag）：
#   stable 通道：v{BASE} 存在即闭合（线已正式——所有通道随之闭合，后续必须开新线）
#   beta 通道：线内序号迭代（2026-10-10 用户定规），永不闭合——bare -beta 视为
#     1 号，其后 -beta-N 递增，同名 tag 冲突由下方 tag 冲突检查兜底
#   dev 通道：仅 v{BASE}（正式版）存在时闭合——dev.N 序号天然去重
CHANNEL_CLOSED=false
if git rev-parse -q --verify "${TAG_PREFIX}${CUR_BASE}" >/dev/null 2>&1; then
  CHANNEL_CLOSED=true   # 正式版已发——所有通道闭合
fi

if [ -n "$FORCE_BUMP" ]; then
  OPEN_NEW_LINE=true
elif $CHANNEL_CLOSED; then
  OPEN_NEW_LINE=true
else
  OPEN_NEW_LINE=false
fi

if $OPEN_NEW_LINE; then
  # 开新线：bump 基准 = 当前线的正式版 tag；无则本线 beta tag（beta 重发场景：
  # 0.3.1-beta → 修复 → beta → 0.3.2-beta，bump 只看 beta 之后的增量）；再无退最后正式版
  DERIVE_TAG="${TAG_PREFIX}${CUR_BASE}"
  if ! git rev-parse -q --verify "$DERIVE_TAG" >/dev/null 2>&1; then
    DERIVE_TAG="${TAG_PREFIX}${CUR_BASE}-beta"
  fi
  if ! git rev-parse -q --verify "$DERIVE_TAG" >/dev/null 2>&1; then
    DERIVE_TAG="$LAST_STABLE"
  fi
  if [ -n "$FORCE_BUMP" ]; then
    BUMP="$FORCE_BUMP"
    log "强制 bump: $BUMP（开新版本线）"
  elif [ -n "$DERIVE_TAG" ]; then
    BUMP="$(derive_bump "$DERIVE_TAG")"
    log "commit 推导 bump: $BUMP（基准 $DERIVE_TAG，开新版本线）"
  else
    BUMP="minor"
    log "无任何正式版 tag，默认 minor（开新版本线）"
  fi
  NEW_BASE="$(apply_bump "$C_MAJOR" "$C_MINOR" "$C_PATCH" "$BUMP")"
  log "版本线: $CUR_BASE -> $NEW_BASE（$FLAVOR）"
else
  NEW_BASE="$CUR_BASE"
  log "版本线: 继续 $NEW_BASE（未发布过正式版，通道切换/dev 迭代不 bump）"
fi

case "$FLAVOR" in
  stable) NEW_VERSION_NAME="$NEW_BASE" ;;
  beta)
    # 线内序号迭代（2026-10-10 用户定规，取代 2026-08-23「每线单发」）：
    # bare -beta 视为第 1 号，其后 -beta-N 递增（序号递增免用户确认）
    NUM="$(last_beta_num "$NEW_BASE")"
    NEW_VERSION_NAME="$NEW_BASE-beta-$((NUM+1))"
    ;;
  dev)
    # 线内序号以已发布 tag 为准（properties 可能滞后于 tag）
    NUM="$(last_prerelease_num "$NEW_BASE" dev)"
    if [ -z "$NUM" ]; then NUM=0; fi
    NEW_VERSION_NAME="$NEW_BASE-dev.$((NUM+1))"
    ;;
esac

NEW_VERSION_CODE=$((CUR_VERSION_CODE + 1))
NEW_TAG="${TAG_PREFIX}${NEW_VERSION_NAME}"

log "新版本: $NEW_VERSION_NAME (code=$NEW_VERSION_CODE)  tag=$NEW_TAG"

# tag 冲突检查
if git rev-parse "$NEW_TAG" >/dev/null 2>&1; then
  die "tag $NEW_TAG 已存在！请检查是否重复发版。"
fi

# =============================================================================
# 2.5 版本提升确认门（2026-10-10 用户定规）
#   任何版本号提升（版本线 bump / 通道晋升 / 正式发布）须用户确认后才能落盘；
#   纯预发布序号递增（同线同通道：dev.N→dev.N+1、-beta-N→-beta-N+1）豁免。
# =============================================================================
is_pure_sequence_bump() {
  [ "$NEW_BASE" = "$CUR_BASE" ] || return 1
  case "$FLAVOR" in
    dev)  [[ "$C_LABEL" == dev.* ]] ;;
    beta) [[ "$C_LABEL" == "beta" || "$C_LABEL" == beta-* ]] ;;
    *)    return 1 ;;
  esac
  # 2026-10-19 #548 验收定罪加固：properties 序号可领先 tag（testbuild.sh 本地
  # 测试包消耗序号写 properties 不发 tag），此时 tag 域推导的新序号可能低于
  # properties 当前序号（版本名回退，如 beta-9 → beta-2）——「纯序号递增」
  # 语义要求单调不减，回退场景必须走确认门。正常发版流程新号恒为 N+1>N，
  # 本检查零影响。
  local cur_num new_num
  if [ "$FLAVOR" = beta ]; then
    cur_num="${CUR_VERSION_NAME##*-beta}"; cur_num="${cur_num#-}"
    cur_num="${cur_num:-1}"   # bare -beta 视为第 1 号（§2.1 定规）
    new_num="${NEW_VERSION_NAME##*-beta}"; new_num="${new_num#-}"
  else
    cur_num="${CUR_VERSION_NAME##*.}"    # X.Y.Z-dev.N → N
    new_num="${NEW_VERSION_NAME##*.}"
  fi
  [[ "$cur_num" =~ ^[0-9]+$ && "$new_num" =~ ^[0-9]+$ ]] || return 1
  [ "$new_num" -ge "$cur_num" ] || return 1
}
if is_pure_sequence_bump; then
  log "纯序号递增（$CUR_VERSION_NAME → $NEW_VERSION_NAME），免确认（2026-10-10 定规）"
  NEEDS_CONFIRM=false
else
  NEEDS_CONFIRM=true
fi
if $DRY_RUN; then
  if $NEEDS_CONFIRM; then
    log "[dry-run] 正式执行时将要求人工确认版本提升（$CUR_VERSION_NAME → $NEW_VERSION_NAME）"
  fi
elif $NEEDS_CONFIRM; then
  echo ""
  echo "──────────────────────────────────────────────────────────"
  echo " ⚠️  版本提升确认（2026-10-10 用户定规：任何版本号提升须确认；仅序号递增免确认）"
  echo "   当前: $CUR_VERSION_NAME (code=$CUR_VERSION_CODE)"
  echo "   拟发: $NEW_VERSION_NAME (code=$NEW_VERSION_CODE)  [$FLAVOR]  tag=$NEW_TAG"
  echo "   拒绝即中止（不修改任何文件）；如需改递进类型可用 --force-bump=major|minor|patch"
  echo "──────────────────────────────────────────────────────────"
  read -r -p " 确认提升到 $NEW_VERSION_NAME？[y/N] " CONFIRM_ANS
  case "$CONFIRM_ANS" in
    y|Y|yes|YES) log "用户已确认版本提升" ;;
    *) die "用户未确认版本提升——已中止（未修改任何文件）" ;;
  esac
fi

# =============================================================================
# 3. 执行变更
# =============================================================================
# 3.1 version.properties
run py - "$VERSION_FILE" "$NEW_VERSION_CODE" "$NEW_VERSION_NAME" <<'PYEOF'
import sys
path, code, name = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write(f"VERSION_CODE={code}\nVERSION_NAME={name}\n")
PYEOF

# 3.2 CHANGELOG（仅 stable）
CHANGELOG_UPDATED=false
if [ "$FLAVOR" = "stable" ]; then
  if [ -n "$LAST_STABLE" ]; then
    SINCE="$LAST_STABLE"
  else
    SINCE="$(git rev-list --max-parents=0 HEAD | head -n1)"
  fi
  ENTRY="$(gen_changelog_entry "$SINCE" "$NEW_VERSION_NAME")"
  if $DRY_RUN; then
    log "[dry-run] CHANGELOG.md 将插入条目："
    printf '%s\n' "$ENTRY" | head -n 15
  else
    if [ -f "$CHANGELOG_FILE" ]; then
      # ENTRY 经临时文件传递（非 argv）：Windows Git Bash 下 argv 传多行中文会损坏编码
      TMP_ENTRY="$(mktemp)"
      printf '%s' "$ENTRY" > "$TMP_ENTRY"
      # 在第一个 "## [" 之前插入新条目；幂等：目标版本已存在则不重复插入
      py - "$CHANGELOG_FILE" "$TMP_ENTRY" <<'PYEOF'
import sys
path, entry_path = sys.argv[1], sys.argv[2]
with open(entry_path, encoding='utf-8') as f:
    entry = f.read()
with open(path, encoding='utf-8') as f:
    content = f.read()
ver_marker = entry.split('\n', 1)[0].strip()
if content.replace('\r\n', '\n').find(ver_marker + '\n') != -1:
    sys.exit(0)
idx = content.find("## [")
if idx == -1:
    content = content.rstrip() + "\n\n" + entry
else:
    content = content[:idx] + entry + "\n" + content[idx:]
with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
PYEOF
      rm -f "$TMP_ENTRY"
    else
      printf '# Changelog\n\n本项目遵循 [Semantic Versioning](https://semver.org/) 与 [Keep a Changelog](https://keepachangelog.com/)。\n**CHANGELOG 仅在正式版（stable release）发布时更新**；beta/dev 预发布的变更在正式版发布时统一汇总。\n\n%s' "$ENTRY" > "$CHANGELOG_FILE"
    fi
    log "CHANGELOG.md 已更新"
    CHANGELOG_UPDATED=true
  fi
else
  log "预发布版不更新 CHANGELOG.md（正式版统一汇总）"
fi

# 3.3 Release Notes 草稿（所有 flavor）
# 范围 = 本通道上一个已发布 tag（beta 看 beta、dev 看 dev、stable 看正式版）——
# 版本线模型下通道晋升（dev→beta）的 notes 应覆盖自上个对外 beta 以来的全部变更；
# 无本通道 tag 时退到最后正式版，再无则根提交。
case "$FLAVOR" in
  stable) NOTES_SINCE="$LAST_STABLE" ;;
  beta) NOTES_SINCE="$(git tag --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-beta([.-][0-9]+)?$' | head -n1 || true)" ;;
  dev)  NOTES_SINCE="$(git tag --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-dev\.[0-9]+$' | head -n1 || true)" ;;
esac
if [ -z "$NOTES_SINCE" ]; then
  NOTES_SINCE="$LAST_STABLE"
fi
if [ -z "$NOTES_SINCE" ]; then
  NOTES_SINCE="$(git rev-list --max-parents=0 HEAD | head -n1)"
fi
log "notes 基准 tag: $NOTES_SINCE"
NOTES="$(gen_release_notes "$NOTES_SINCE" "$NEW_VERSION_NAME")"
if $DRY_RUN; then
  log "[dry-run] RELEASE_NOTES.md 草稿："
  printf '%s\n' "$NOTES" | head -n 15
else
  printf '%s' "$NOTES" > "$RELEASE_NOTES_FILE"
  log "RELEASE_NOTES.md 草稿已生成（请润色，模板见 docs/release-notes-template.md）"
fi

# =============================================================================
# 4. commit + tag + push
# =============================================================================
if $DRY_RUN; then
  log "[dry-run] 将执行: git add version.properties [CHANGELOG.md] RELEASE_NOTES.md"
  log "[dry-run] 将执行: git commit -m \"chore: bump version to $NEW_VERSION_NAME\""
  log "[dry-run] 将执行: git tag -a $NEW_TAG -m \"$NEW_TAG\""
  log "[dry-run] 将执行: git push $REMOTE $BRANCH && git push $REMOTE $NEW_TAG"
  log "✅ dry-run 完成，未做任何修改。"
  exit 0
fi

# 4.0 Release Notes 人工润色确认（所有 flavor）
if [ -f "$RELEASE_NOTES_FILE" ]; then
  echo ""
  echo "──────────────────────────────────────────────────────────"
  echo " RELEASE_NOTES.md 草稿已生成（模板见 docs/release-notes-template.md）："
  echo "   - 必填：版本摘要（第 2 行）"
  echo "   - 建议：条目改为用户视角（不粘贴 commit message）"
  echo "   按回车直接继续；或先编辑 RELEASE_NOTES.md 再回来按回车"
  echo "──────────────────────────────────────────────────────────"
  read -r -p "按回车继续发版（Ctrl+C 取消）..." _unused
fi

# 4.0 正式版 CHANGELOG 人工润色确认
if [ "$FLAVOR" = "stable" ] && $CHANGELOG_UPDATED; then
  echo ""
  echo "──────────────────────────────────────────────────────────"
  echo " CHANGELOG.md 已自动更新。可以现在人工润色（可选）："
  echo "   按回车直接继续；或先编辑 CHANGELOG.md 再回来按回车"
  echo "──────────────────────────────────────────────────────────"
  read -r -p "按回车继续发版（Ctrl+C 取消）..." _unused
fi

# 4.1 工作树更新
git add "$VERSION_FILE"
if $CHANGELOG_UPDATED; then
  git add "$CHANGELOG_FILE"
fi
if [ -f "$RELEASE_NOTES_FILE" ]; then
  git add "$RELEASE_NOTES_FILE"
fi

# 4.2 commit（仅当有变更）
if git diff --cached --quiet; then
  warn "version.properties 无变更，跳过 commit"
else
  git commit -m "chore: bump version to $NEW_VERSION_NAME"
  log "committed"
fi

# 4.3 tag
git tag -a "$NEW_TAG" -m "$NEW_TAG"
log "tagged: $NEW_TAG"

# 4.4 push（触发 CI）
git push "$REMOTE" "$BRANCH"
log "pushed: $REMOTE/$BRANCH"
git push "$REMOTE" "$NEW_TAG"
log "pushed: $NEW_TAG"

# =============================================================================
# 5. 完成提示
# =============================================================================
cat <<EOF

══════════════════════════════════════════════════════════════════
 ✅ 发版请求已提交，CI 正在构建
══════════════════════════════════════════════════════════════════
 版本:  $NEW_VERSION_NAME (code=$NEW_VERSION_CODE)
 tag:   $NEW_TAG
 flavor: $FLAVOR

 CI 将自动:
  - 构建 ${FLAVOR} release APK（release keystore 签名）
  - 复制为 oc-beacon-${NEW_VERSION_NAME}.apk
  - 创建/更新 GitHub Release（说明来自 RELEASE_NOTES.md）

 验证（约 5-10 分钟后）:
  gh release list
  gh release view $NEW_TAG --json assets

 若 CI 未触发，检查 .github/workflows/release.yml。
══════════════════════════════════════════════════════════════════
EOF
