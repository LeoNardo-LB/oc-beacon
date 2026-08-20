#!/usr/bin/env bash
# =============================================================================
# 登记新工作批次：创建 docs/journal/YYYY-MM-DD-<kebab名>.md（套用模板）
# 用法: ./scripts/backlog-new-batch.sh "<批次名>"   # 中文名或英文均可，自动转 kebab
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "用法: $0 <批次名>" >&2; exit 1
fi

NAME="$1"
# kebab 化：小写、空白/下划线转连字符、剔除非 [a-z0-9-]（纯中文退化为 batch）
KEBAB=$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | tr ' _' '--' | tr -cd 'a-z0-9-' | sed 's/-\{2,\}/-/g; s/^-\+//; s/-\+$//')
if [ -z "$KEBAB" ] || ! echo "$KEBAB" | grep -q '[a-z0-9]'; then KEBAB="batch"; fi
DATE=$(date +%F)
FILE="docs/journal/${DATE}-${KEBAB}.md"

if [ -e "$FILE" ]; then
  echo "已存在: $FILE（勿重复创建）" >&2; exit 1
fi

mkdir -p docs/journal
cat > "$FILE" <<EOF
# ${NAME}（${DATE}）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->
EOF

echo "已创建: $FILE"
echo "提醒："
echo "  1. backlog.md 加卡片：全局编号（见头部计数器）+ Tag + checkbox + ≤3 行摘要 + 本文件链接"
echo "  2. 完结（用户验收）后：条目当场从 backlog 迁入本文件，并更新本文件状态行"
echo "  3. 跑 ./scripts/backlog-check.sh 校验"
