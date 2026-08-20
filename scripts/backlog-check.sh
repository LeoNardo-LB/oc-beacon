#!/usr/bin/env bash
# =============================================================================
# backlog.md 机械不变量校验（agent 改动 backlog 后必跑；neat-freak 例行）
# 检查：完结条目零残留 / 编号计数器 / 本地链接存在 / archive 引用零残留 /
#       P0-P3 节标题有序唯一 / 行数警告（>250 仅警告）
# 退出码：0=通过（可含警告）；1=有硬性违规
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."
BL=backlog.md
fail=0; warn=0

[ -f "$BL" ] || { echo "✗ 缺 $BL"; exit 1; }

# 1. 顶层 [x] 完结条目必须为零（完结即迁移）
nx=$(grep -c '^- \[x\]' "$BL" || true)
if [ "$nx" -gt 0 ]; then
  echo "✗ 发现 $nx 个顶层 [x] 条目——完结条目必须迁入 docs/journal/，backlog 不保留"
  grep -n '^- \[x\]' "$BL" | head -5
  fail=1
else echo "✓ 无完结条目残留"; fi

# 2. 编号计数器 > 全库最大编号（backlog + journal + specs）
next=$(grep -oP '下一编号：\*\*#\K[0-9]+' "$BL" | head -1)
if [ -z "$next" ]; then
  echo "✗ 头部缺「下一编号：**#N**」计数器"; fail=1
else
  max=$(cat "$BL" docs/journal/*.md docs/specs/*.md 2>/dev/null \
        | grep -v '下一编号' \
        | grep -oP '(^|\*\*|>)#\K[0-9]{2,4}(?=[\s：:*）])' \
        | sort -n | tail -1)
  if [ -z "$max" ]; then max=0; fi
  if [ "$next" -le "$max" ]; then
    echo "✗ 计数器 #$next ≤ 全库最大编号 #$max——请更新计数器"; fail=1
  else echo "✓ 编号计数器 #$next > 最大编号 #$max"; fi
fi

# 3. 卡片中的本地路径必须存在（md 链接与反引号代码域路径；含 < 或 YYYY 的占位符除外）
dangling=$( { grep -oP '\]\(\K(docs/[^)#]+|backlog\.md)' "$BL" 2>/dev/null; \
             grep -oP '\x60\Kdocs/(specs|journal|research)/[^\x60]+' "$BL" 2>/dev/null; } \
           | sort -u | grep -v '[<>]\|YYYY' \
           | while read -r p; do [ -e "$p" ] || echo "$p"; done)
if [ -n "$dangling" ]; then
  echo "✗ 悬空链接："; echo "$dangling"; fail=1
else echo "✓ 本地链接全部存在"; fi

# 4. backlog 内零 docs/archive/ 引用（归档引用只允许出现在 journal/spec）
na=$(grep -oP '\]\(\Kdocs/archive/[^)]*' "$BL" 2>/dev/null | wc -l)
if [ "$na" -gt 0 ]; then
  echo "✗ backlog 含 $na 处 docs/archive/ 引用（归档引用只允许在 journal/spec）"; fail=1
else echo "✓ 无 archive 引用"; fi

# 5. P0-P3 四节标题各恰好一次且按序
sections=$(grep '^## P[0-3] ' "$BL" | awk '{print substr($2,1,2)}' | tr -d '\n')
if [ "$sections" = "P0P1P2P3" ]; then echo "✓ P0-P3 节标题有序唯一"
else echo "✗ P 节标题异常：'$sections'（期望 P0P1P2P3）"; fail=1; fi

# 6. 行数警告（阈值 250，仅警告）
lines=$(wc -l < "$BL")
if [ "$lines" -gt 250 ]; then
  echo "⚠ 行数 $lines > 250——考虑是否有完结内容未迁移"; warn=1
else echo "✓ 行数 $lines ≤ 250"; fi

echo "---"
if [ "$fail" -eq 0 ]; then
  echo "结果：通过"
  [ "$warn" -eq 1 ] && echo "（含警告）"
else
  echo "结果：不通过"
fi
exit $fail
