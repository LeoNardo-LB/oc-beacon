#!/usr/bin/env bash
# =============================================================================
# 将构建产物 APK 重命名为发布文件名。
#
# 用法: bash scripts/ci-rename-apk.sh <version> <flavor>
#   <version> — 版本号（如 0.3.0-beta.3）
#   <flavor>  — 构建变体（beta/stable/dev）
#
# 前提: CWD 为仓库根目录；Build Release APK 步骤已完成。
# =============================================================================
set -euo pipefail

VERSION="${1:?用法: ci-rename-apk.sh <version> <flavor>}"
FLAVOR="${2:?用法: ci-rename-apk.sh <version> <flavor>}"

mkdir -p release-apks

# Find the built APK (signed or unsigned)
APK=$(find "app/build/outputs/apk/${FLAVOR}/release" -name '*.apk' | head -1)

if [ -n "$APK" ]; then
  cp "$APK" "release-apks/oc-beacon-${VERSION}.apk"
else
  echo "::error::No APK found in app/build/outputs/apk/${FLAVOR}/release/"
  exit 1
fi
