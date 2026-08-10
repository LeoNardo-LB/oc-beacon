#!/usr/bin/env bash
# =============================================================================
# 创建或更新 GitHub Release 并上传 APK。
#
# 用法: bash scripts/ci-release.sh <version> <flavor>
#   <version> — 版本号（如 0.3.0-beta.3）
#   <flavor>  — 构建变体（beta/stable/dev）
# 环境变量: GH_TOKEN
#
# 逻辑:
#   1. 尝试创建并推送 tag v<VERSION>（已存在则忽略）
#   2. flavor != stable 时标记 --prerelease
#   3. Release 已存在 → gh release upload --clobber
#      Release 不存在 → 优先用 RELEASE_NOTES.md，否则 --generate-notes
# 前提: CWD 为仓库根目录。
# =============================================================================
set -euo pipefail

VERSION="${1:?用法: ci-release.sh <version> <flavor>}"
FLAVOR="${2:?用法: ci-release.sh <version> <flavor>}"

TAG="v${VERSION}"

# Create tag if it doesn't exist
git tag "$TAG" || true
git push origin "$TAG" || true

# 预发布标记：beta/dev 为 prerelease，stable 为正式版
PRERELEASE_FLAG=""
if [ "$FLAVOR" != "stable" ]; then
  PRERELEASE_FLAG="--prerelease"
fi

# Check if release already exists
if gh release view "$TAG" > /dev/null 2>&1; then
  echo "Release $TAG already exists, uploading APK..."
  gh release upload "$TAG" release-apks/*.apk --clobber
else
  echo "Creating new release $TAG..."
  # 发版说明优先使用 RELEASE_NOTES.md（release.sh 生成 + 发布者润色），缺失时回退 GitHub 自动生成
  if [ -f RELEASE_NOTES.md ]; then
    NOTES_ARGS="--notes-file RELEASE_NOTES.md"
  else
    NOTES_ARGS="--generate-notes"
  fi
  gh release create "$TAG" \
    --title "OC Beacon ${VERSION}" \
    $PRERELEASE_FLAG \
    $NOTES_ARGS \
    release-apks/*.apk
fi
