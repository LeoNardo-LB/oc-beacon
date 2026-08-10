#!/usr/bin/env bash
# =============================================================================
# 删除 gradle.properties 中的本地专用设置（CI 环境不需要）。
#
# 清理项:
#   - systemProp.*          （本地代理设置）
#   - org.gradle.java.home  （本地 JDK 路径）
#
# 用法: bash scripts/ci-clean-local-settings.sh
# 前提: CWD 为仓库根目录。
# =============================================================================
set -euo pipefail

sed -i '/^systemProp\./d' gradle.properties
sed -i '/^org.gradle.java.home/d' gradle.properties
