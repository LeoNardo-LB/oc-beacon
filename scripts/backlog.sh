#!/usr/bin/env bash
# =============================================================================
# backlog.sh — 统一需求管理入口（backlog 卡片 + journal 批次；2026-09-09 用户定规）
#
# 背景：2026-09-09 两起账本事故——journal 全量覆写丢章、#372 同号双卡+过验卡漏迁移。
# 定规：backlog 卡片区禁止手工直编，登记/明细/状态/迁移一律经本脚本；journal 追加
#       用 journal append（append-only，机械上不可能截断覆写）。真实 backlog 变更后
#       自动跑 backlog-check.sh。
#
# 用法：
#   backlog.sh add -p <P0-P4> -t "<标题>" [-g "tag1 tag2"] [-s "<摘要行>"]...
#                 [-l "<链接>"] [--premise "<前提（P4 必填）>"]
#   backlog.sh note <N> [-s "<明细行>"]...          # 追加明细（无 -s 则读 stdin）
#   backlog.sh status <N> <todo|verify>             # [ ] ↔ [~]
#   backlog.sh migrate <N> [-j <journal>] [-r "<依据>"]  # 完结迁移：卡→journal+墓碑+删卡
#   backlog.sh show [N | <模式>]                    # 列卡 / 看单卡 / 按模式筛
#   backlog.sh next                                 # 下一编号
#   backlog.sh check                                # 委托 backlog-check.sh
#   backlog.sh journal new-batch "<批次名>"         # 委托 backlog-new-batch.sh
#   backlog.sh journal append <文件> ["<节标题>"]   # stdin 内容安全追加为 journal 节
#
# 环境变量：BACKLOG_FILE（默认 backlog.md；用 /tmp 副本测试）
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
BL="${BACKLOG_FILE:-backlog.md}"
TODAY=$(date +%F)
TD=$(mktemp -d); trap 'rm -rf "$TD"' EXIT

die() { echo "✗ $*" >&2; exit 1; }
usage() { grep '^#   backlog.sh' "$0" | sed 's/^# //'; }

# 锚点模式：编号后随非数字字符或行尾（#363〔全角括号紧跟、#378 空格分隔均命中；#3630 不误配 #363）——2026-09-10 修复「紧跟全角标点卡失配」边角。
anchor() { grep -nE "^- \[.\] \*\*#$1([^0-9]|$)" "$BL" | head -1 | cut -d: -f1 || true; }
block_end() { # $1=锚行 → 块末行（锚+连续缩进）
  awk -v s="$1" 'NR<s{next} NR==s{e=NR;next} /^[[:space:]]+[^[:space:]]/{e=NR;next} {print e; f=1; exit} END{if(!f)print e}' "$BL"
}
section_header_line() { grep -n "^## $1 " "$BL" | head -1 | cut -d: -f1; }
section_of_line() { awk -v s="$1" 'NR<=s && /^## P[0-4] /{p=substr($2,1,2)} END{print p}' "$BL"; }
top_insert_point() { # $1=节头行 → 节顶插入点（跳过其后空行与（墓碑）行）
  awk -v h="$1" 'NR<=h{next} /^（/{next} /^[[:space:]]*$/{next} {print NR-1; f=1; exit} END{if(!f)print NR}' "$BL"
}
is_real_backlog() { [ "$(realpath "$BL" 2>/dev/null || echo x)" = "$(pwd)/backlog.md" ]; }
run_check() { if is_real_backlog; then ./scripts/backlog-check.sh || die "backlog-check 未通过（见上）"; fi; }
ins_after() { local k="$1" f="$2"; sed -i "${k}r ${f}" "$BL"; }

CMD="${1:-}"; if [ -z "$CMD" ]; then usage; exit 0; fi
shift

case "$CMD" in
# -------------------------------------------------------------- add
add)
  P=""; T=""; G=""; L=""; PREM=""; SUMS=()
  while [ $# -gt 0 ]; do case "$1" in
    -p) P="$2"; shift 2;; -t) T="$2"; shift 2;; -g) G="$2"; shift 2;;
    -l) L="$2"; shift 2;; --premise) PREM="$2"; shift 2;;
    -s) SUMS+=("$2"); shift 2;; *) die "add: 未知参数 $1";;
  esac; done
  echo "$P" | grep -q '^P[0-4]$' || die "add: 需要 -p P0..P4"
  [ -n "$T" ] || die "add: 需要 -t \"<标题>\""
  if [ "$P" = P4 ] && [ -z "$PREM" ]; then die "add: P4 卡必须 --premise（backlog 规则：卡内必含前提行）"; fi
  N=$(grep -oP '下一编号：\*\*#\K[0-9]+' "$BL" | head -1)
  [ -n "$N" ] || die "add: 头部缺「下一编号：**#N**」计数器"
  TAGS=""; for g in $G; do TAGS="$TAGS \`$g\`"; done
  CF="$TD/card"
  printf '%s\n' "- [ ] **#$N $T**$TAGS" > "$CF"
  for s in "${SUMS[@]}"; do printf '  - %s\n' "$s" >> "$CF"; done
  if [ -n "$PREM" ]; then printf '  - **前提**：%s\n' "$PREM" >> "$CF"; fi
  if [ -n "$L" ]; then printf '  - → %s\n' "$L" >> "$CF"; fi
  printf '\n' >> "$CF"
  H=$(section_header_line "$P"); if [ -z "$H" ]; then die "add: 找不到节 ## $P"; fi
  K=$(top_insert_point "$H")
  ins_after "$K" "$CF"
  SHORT=$(printf '%s' "$T" | head -c 48)
  CL=$(grep -n '下一编号' "$BL" | head -1 | cut -d: -f1)
  awk -v ln="$CL" -v new="#$((N+1))" -v note="$TODAY #$N $SHORT" '
    NR==ln{ sub(/下一编号：.*$/, "下一编号：**" new "**（" note "）。"); print; next }
    {print}' "$BL" > "$TD/bl2" && mv "$TD/bl2" "$BL"
  echo "已登记 #$N → $P 节顶（计数器 → #$((N+1))）"
  run_check
  ;;
# -------------------------------------------------------------- note
note)
  if [ -z "${1:-}" ]; then die "note: 用法 note <N> [-s 行]..."; fi
  N="$1"; shift
  SUMS=(); while [ $# -gt 0 ]; do case "$1" in
    -s) SUMS+=("$2"); shift 2;; *) die "note: 未知参数 $1";; esac; done
  if [ ${#SUMS[@]} -eq 0 ]; then
    if ! [ -t 0 ]; then while IFS= read -r line; do [ -n "$line" ] && SUMS+=("$line"); done
    else die "note: 需要 -s 或管道提供明细行"; fi
  fi
  A=$(anchor "$N"); if [ -z "$A" ]; then die "note: 找不到卡 #$N"; fi
  E=$(block_end "$A")
  NF_="$TD/notes"; : > "$NF_"
  for s in "${SUMS[@]}"; do printf '  - %s\n' "$s" >> "$NF_"; done
  ins_after "$E" "$NF_"
  echo "已向 #$N 追加 ${#SUMS[@]} 行明细"
  ;;
# -------------------------------------------------------------- status
status)
  if [ "$#" -lt 2 ]; then die "status: 用法 status <N> <todo|verify>"; fi
  N="$1"; S="$2"
  A=$(anchor "$N"); if [ -z "$A" ]; then die "status: 找不到卡 #$N"; fi
  case "$S" in
    todo)   sed -i "${A}s/- \[~\]/- [ ]/" "$BL" ;;
    verify) sed -i "${A}s/- \[ \]/- [~]/" "$BL" ;;
    *) die "status: 只支持 todo|verify（完结请用 migrate，不产生 [x]）" ;;
  esac
  echo "#$N 状态 → $S"
  ;;
# -------------------------------------------------------------- migrate
migrate)
  if [ -z "${1:-}" ]; then die "migrate: 用法 migrate <N> [-j 文件] [-r 依据]"; fi
  N="$1"; shift
  J=""; R=""
  while [ $# -gt 0 ]; do case "$1" in
    -j) J="$2"; shift 2;; -r) R="$2"; shift 2;; *) die "migrate: 未知参数 $1";; esac; done
  A=$(anchor "$N"); if [ -z "$A" ]; then die "migrate: 找不到卡 #$N"; fi
  TITLE=$(sed -n "${A}p" "$BL")
  if echo "$TITLE" | grep -q '^- \[ \]' && [ -z "$R" ]; then
    die "migrate: #$N 仍是 [ ]（未验待验）——完结迁移需 -r \"<迁入依据>\" 显式确认"
  fi
  if [ -z "$R" ]; then R="用户验收通过"; fi
  E=$(block_end "$A")
  P=$(section_of_line "$A")
  if [ -z "$P" ]; then die "migrate: 卡 #$N 不在任何 Pn 节"; fi
  if [ -z "$J" ]; then J=$(ls -t docs/journal/*.md 2>/dev/null | head -1); fi
  if [ -z "$J" ] || [ ! -f "$J" ]; then die "migrate: 找不到 journal 目标（-j 指定或 docs/journal/ 为空）"; fi
  # 1) 抽卡块 → journal 迁入节（append-only 追加）
  sed -n "${A},${E}p" "$BL" > "$TD/raw"
  awk -v r="$R" -v d="$TODAY" 'NR==1{sub(/^- \[.\] /,"### "); print; next}
    {print} END{printf "  - 迁入依据：%s（backlog.sh migrate %s）\n", r, d}' "$TD/raw" > "$TD/block"
  JBL=$(basename "$J")
  LASTH=$(grep -n '^## ' "$J" | tail -1 | cut -d: -f1 || true)
  NEEDH=1
  if [ -n "$LASTH" ]; then
    HL=$(sed -n "${LASTH}p" "$J")
    if [ "$HL" = "## 已完结卡片迁入（${TODAY}）" ]; then NEEDH=0; fi
  fi
  if [ -n "$(tail -c1 "$J" 2>/dev/null)" ]; then echo >> "$J"; fi
  {
    if [ "$NEEDH" = 1 ]; then printf '\n## 已完结卡片迁入（%s）\n' "$TODAY"; fi
    printf '\n'; cat "$TD/block"
  } >> "$J"
  # 2) 删卡 + 收敛残留双空行
  sed -i "${A},${E}d" "$BL"
  PREV=$((A-1))
  if [ "$PREV" -ge 1 ]; then
    L1=$(sed -n "${PREV}p" "$BL"); L2=$(sed -n "${A}p" "$BL" || true)
    if [ -z "$L1" ] && [ -z "${L2:-}" ]; then sed -i "${A}d" "$BL"; fi
  fi
  # 3) 墓碑行（并入节顶墓碑簇：置于簇末行后、卡区空行之前）
  H=$(section_header_line "$P"); K=$(top_insert_point "$H")
  KM=$((K-1)); if [ "$KM" -lt "$H" ]; then KM="$H"; fi
  printf '（#%s 已完结迁 journal：%s（%s））\n' "$N" "$JBL" "$TODAY" > "$TD/tomb"
  ins_after "$KM" "$TD/tomb"
  echo "已迁移 #$N → $JBL；$P 节加墓碑；依据：$R"
  run_check
  ;;
# -------------------------------------------------------------- show / next / check
show)
  if [ -n "${1:-}" ] && echo "${1}" | grep -qE '^[0-9]+$'; then
    A=$(anchor "$1"); if [ -z "$A" ]; then die "show: 找不到卡 #$1"; fi
    E=$(block_end "$A"); sed -n "${A},${E}p" "$BL"
  elif [ -n "${1:-}" ]; then
    grep -n "^- \[.\] \*\*#[0-9]" "$BL" | grep "$1" || echo "（无匹配）"
  else
    grep -n "^- \[.\] \*\*#[0-9]" "$BL" || true
  fi
  ;;
next) grep -oP '下一编号：\*\*#\K[0-9]+' "$BL" | head -1 ;;
check) exec ./scripts/backlog-check.sh ;;
# -------------------------------------------------------------- journal
journal)
  SUB="${1:-}"; if [ -z "$SUB" ]; then die "journal: 子命令 new-batch|append"; fi
  shift || true
  case "$SUB" in
    new-batch)
      if [ -z "${1:-}" ]; then die "journal new-batch: 需要批次名"; fi
      exec ./scripts/backlog-new-batch.sh "$1" ;;
    append)
      if [ -z "${1:-}" ]; then die "journal append: 用法 journal append <文件> [\"<节标题>\"]"; fi
      F="$1"; H2="${2:-}"
      if [ ! -f "$F" ]; then die "journal append: $F 不存在（新批次请用 journal new-batch）"; fi
      cat > "$TD/content"
      FIRST=$(head -1 "$TD/content")
      if ! printf '%s' "$FIRST" | grep -q '^#'; then
        if [ -z "$H2" ]; then die "journal append: 内容不以 # 开头时必须给节标题"; fi
        printf '## %s\n\n' "$H2" | cat - "$TD/content" > "$TD/c2" && mv "$TD/c2" "$TD/content"
      fi
      if [ -s "$F" ]; then
        if [ -n "$(tail -c1 "$F")" ]; then echo >> "$F"; fi
        printf '\n' >> "$F"
      fi
      cat "$TD/content" >> "$F"
      if [ -n "$(tail -c1 "$F")" ]; then echo >> "$F"; fi
      echo "已追加 $(wc -l < "$TD/content") 行 → $F（append-only，不触碰既有内容）" ;;
    *) die "journal: 未知子命令 $SUB（new-batch|append）" ;;
  esac
  ;;
*) usage; die "未知命令 $CMD" ;;
esac
