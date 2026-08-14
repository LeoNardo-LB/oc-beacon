#!/usr/bin/env bash
# =============================================================================
# OC Beacon 一键发版脚本
#
# 用法:
#   ./scripts/release.sh <flavor> [--dry-run] [--force-bump=major|minor|patch]
#
#   flavor:      beta（默认）| stable | dev
#   --dry-run:   只打印将执行的步骤，不修改任何文件、不推送
#   --force-bump: 跳过 commit 分析，强制指定递进类型
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
# 分析基准 tag：优先当前 VERSION_NAME 对应的 tag（如 v1.0.3-beta.1 存在则用它），否则用最后一个正式版
CUR_TAG="${TAG_PREFIX}${CUR_VERSION_NAME}"
if git rev-parse "$CUR_TAG" >/dev/null 2>&1; then
  LAST_ANY="$CUR_TAG"
else
  LAST_ANY="$LAST_STABLE"
fi
log "最后一个正式版 tag: ${LAST_STABLE:-<无>}"
log "分析基准 tag: ${LAST_ANY:-<无>}"

# 决定 bump 类型
if [ -n "$FORCE_BUMP" ]; then
  BUMP="$FORCE_BUMP"
  log "强制 bump: $BUMP"
else
  if [ -n "$LAST_ANY" ]; then
    BUMP="$(derive_bump "$LAST_ANY")"
  else
    BUMP="minor"
  fi
  log "commit 推导 bump: $BUMP"
fi

# 计算新版本
read -r C_MAJOR C_MINOR C_PATCH C_LABEL <<< "$(parse_version "$CUR_VERSION_NAME")"
C_LABEL="${C_LABEL:-}"

# 预发布标签名
case "$FLAVOR" in
  beta) LABEL="beta" ;;
  dev)  LABEL="dev" ;;
  stable) LABEL="" ;;
esac

NEW_VERSION_NAME=""
if [ "$FLAVOR" = "stable" ]; then
  # 正式版：当前就是正式版则 bump；当前是预发布则去掉标签
  if [ -z "$C_LABEL" ]; then
    NEW_VERSION_NAME="$(apply_bump "$C_MAJOR" "$C_MINOR" "$C_PATCH" "$BUMP")"
  else
    NEW_VERSION_NAME="$C_MAJOR.$C_MINOR.$C_PATCH"
  fi
else
  # 预发布：若当前是同一版本的预发布 → 序号+1；否则基于当前正式部分 bump 后加 -label.1
  if [ -n "$C_LABEL" ] && [ "${C_LABEL%%.*}" = "$LABEL" ]; then
    # 同标签预发布 → 序号+1（如 beta.1 → beta.2）
    local_num="${C_LABEL##*.}"
    NEW_VERSION_NAME="$C_MAJOR.$C_MINOR.$C_PATCH-$LABEL.$((local_num+1))"
  else
    new_base="$(apply_bump "$C_MAJOR" "$C_MINOR" "$C_PATCH" "$BUMP")"
    NEW_VERSION_NAME="$new_base-$LABEL.1"
  fi
fi

NEW_VERSION_CODE=$((CUR_VERSION_CODE + 1))
NEW_TAG="${TAG_PREFIX}${NEW_VERSION_NAME}"

log "新版本: $NEW_VERSION_NAME (code=$NEW_VERSION_CODE)  tag=$NEW_TAG"

# tag 冲突检查
if git rev-parse "$NEW_TAG" >/dev/null 2>&1; then
  die "tag $NEW_TAG 已存在！请检查是否重复发版。"
fi

# =============================================================================
# 3. 执行变更
# =============================================================================
# 3.1 version.properties
run python - "$VERSION_FILE" "$NEW_VERSION_CODE" "$NEW_VERSION_NAME" <<'PYEOF'
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
      python - "$CHANGELOG_FILE" "$TMP_ENTRY" <<'PYEOF'
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

# 3.3 Release Notes 草稿（所有 flavor，范围 last tag -> HEAD）
NOTES_SINCE="$LAST_ANY"
if [ -z "$NOTES_SINCE" ]; then
  NOTES_SINCE="$(git rev-list --max-parents=0 HEAD | head -n1)"
fi
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
