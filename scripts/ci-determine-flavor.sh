#!/usr/bin/env bash
# =============================================================================
# 从 tag 或手动输入推导构建 flavor（beta/stable/dev）。
#
# 用法: bash scripts/ci-determine-flavor.sh <flavor_input> <github_ref> <output_file>
#   <flavor_input> — 手动指定的 flavor（workflow_dispatch 输入，可为空）
#   <github_ref>   — GitHub ref（如 refs/tags/v1.0.3）
#   <output_file>  — CI 传 $GITHUB_OUTPUT
#
# 推导规则:
#   v1.0.3         -> stable（无后缀）
#   v1.0.4-beta.1  -> beta
#   v1.0.4-dev.1   -> dev
#   非 tag 触发     -> beta（默认）
# =============================================================================
set -euo pipefail

FLAVOR_INPUT="${1:-}"
GITHUB_REF="${2:-}"
OUTPUT_FILE="${3:?用法: ci-determine-flavor.sh <flavor_input> <github_ref> <output_file>}"

if [ -n "$FLAVOR_INPUT" ]; then
  FLAVOR="$FLAVOR_INPUT"
elif [[ "$GITHUB_REF" == refs/tags/* ]]; then
  TAG="${GITHUB_REF#refs/tags/}"
  if [[ "$TAG" == *"-beta."* ]]; then
    FLAVOR="beta"
  elif [[ "$TAG" == *"-dev."* ]]; then
    FLAVOR="dev"
  else
    FLAVOR="stable"
  fi
else
  FLAVOR="beta"
fi

echo "flavor=$FLAVOR" >> "$OUTPUT_FILE"
echo "Flavor: $FLAVOR"
