#!/usr/bin/env bash
# =============================================================================
# 从 version.properties 提取版本号，写入 GITHUB_OUTPUT 文件。
#
# 用法: bash scripts/ci-extract-version.sh <output_file>
#   <output_file> — CI 传 $GITHUB_OUTPUT
#
# 前提: CWD 为仓库根目录（CI 默认）。
# =============================================================================
set -euo pipefail

OUTPUT_FILE="${1:?用法: ci-extract-version.sh <output_file>}"

VERSION=$(grep 'VERSION_NAME=' version.properties | cut -d'=' -f2)
VERSION_CODE=$(grep 'VERSION_CODE=' version.properties | cut -d'=' -f2)

echo "name=$VERSION" >> "$OUTPUT_FILE"
echo "code=$VERSION_CODE" >> "$OUTPUT_FILE"
echo "Building version: $VERSION (code: $VERSION_CODE)"
