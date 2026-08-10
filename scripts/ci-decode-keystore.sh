#!/usr/bin/env bash
# =============================================================================
# 从 base64 编码的 keystore secret 解码签名密钥，并生成 signing.properties。
#
# 用法: bash scripts/ci-decode-keystore.sh
# 环境变量:
#   KEYSTORE_BASE64    — base64 编码的 keystore 文件内容
#   KEYSTORE_ALIAS     — keystore 别名
#   KEYSTORE_PASSWORD  — keystore 密码
#
# 前提: CWD 为仓库根目录。
# =============================================================================
set -euo pipefail

: "${KEYSTORE_BASE64:?环境变量 KEYSTORE_BASE64 未设置}"
: "${KEYSTORE_ALIAS:?环境变量 KEYSTORE_ALIAS 未设置}"
: "${KEYSTORE_PASSWORD:?环境变量 KEYSTORE_PASSWORD 未设置}"

mkdir -p app/keystore

echo "$KEYSTORE_BASE64" | base64 -d > app/keystore/release.keystore

echo "keystore=keystore/release.keystore"   >  app/keystore/signing.properties
echo "keystore.alias=$KEYSTORE_ALIAS"       >> app/keystore/signing.properties
echo "keystore.password=$KEYSTORE_PASSWORD" >> app/keystore/signing.properties
