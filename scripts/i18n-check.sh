#!/usr/bin/env bash
# =============================================================================
# OC Beacon 国际化完整性检查（替代已移除的 lokit 工具）。
#
# 检查 app/src/main/res 下的多语言资源：
#   1. Key 完整性   — 每个语言文件（values-*）的 key 集合必须与英文源（values/）完全一致
#   2. 英文源纯净   — values/strings.xml 不允许出现 CJK 汉字与全角标点
#                     （历史教训：曾混入 4 处中文导致英文系统显示中文）
#   3. 占位符一致   — 每个语言每个 key 的格式占位符（%1$s / %d 等）必须与英文源一致
#                     （不一致会导致运行时崩溃或显示错误）
#
# 用法: ./scripts/i18n-check.sh        # 全量检查；有错误时退出码为 1（CI 用）
#
# 实现：纯 bash + grep/sed/awk/sort，无 python/perl/node 依赖。
# =============================================================================
set -uo pipefail   # 注意：不启用 -e，所有错误统一收集到 errors 数组，最后决定退出码

# ---- 定位仓库根（脚本位于 scripts/ 子目录）--------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(basename "$SCRIPT_DIR")" = "scripts" ]; then
  ROOT="$(dirname "$SCRIPT_DIR")"
else
  ROOT="$SCRIPT_DIR"
fi
RES_DIR="$ROOT/app/src/main/res"
SOURCE_FILE="$RES_DIR/values/strings.xml"

errors=()

if [ ! -f "$SOURCE_FILE" ]; then
  echo "未找到英文源文件: $SOURCE_FILE" >&2
  exit 1
fi

# ---- 工具函数 -------------------------------------------------------------

# 提取 string + plurals 的 key（每行一个原始标签，如 '<string name="app_name"'）
# 用法：extract_keys <file>
extract_keys() {
  grep -oE '<string name="[^"]+"' "$1"
  grep -oE '<plurals name="[^"]+"'  "$1"
}

# 提取 string 值（不含 plurals，plurals 不参与占位符比较）
# 输出 name<TAB>value 每行一条（仅匹配 <string name="x">value</string>）
extract_string_values() {
  grep -oE '<string name="[^"]+">[^<]*</string>' "$1" \
    | sed -E 's/<string name="([^"]+)">([^<]*)<\/string>/\1\t\2/'
}

# 占位符集合（归一化 %1$d -> %d，二者等价；位置参数不变）
# 输出排序后逗号连接的归一化占位符串（与英文源逐字符比较）
get_placeholders() {
  printf '%s' "$1" \
    | grep -oE '%[0-9]+[$][ds]|%[ds]' \
    | sed 's/%1[$]/%/g' \
    | sort \
    | paste -sd, -
}

# ---- 读取英文源 -----------------------------------------------------------
declare -A source_keys=()
declare -A source_values=()

while IFS= read -r k; do
  [ -n "$k" ] && source_keys["$k"]=1
done < <(extract_keys "$SOURCE_FILE" | sed -E 's/.* name="([^"]+)".*/\1/')

while IFS=$'\t' read -r name value; do
  [ -n "$name" ] && source_values["$name"]="$value"
done < <(extract_string_values "$SOURCE_FILE")

# ============ 1. 英文源纯净性（CJK 汉字 / 全角标点） ============
# 用 (*UTF) PCRE 动词确保任何 locale（含 C）下都按 Unicode 码点匹配
for name in "${!source_values[@]}"; do
  val="${source_values[$name]}"
  if printf '%s' "$val" | grep -qP '(*UTF)[\x{4e00}-\x{9fff}]'; then
    errors+=("[纯净性] 英文源 $name 含 CJK 字符: $val")
  fi
  if printf '%s' "$val" | grep -qP '(*UTF)[\x{ff00}-\x{ffef}]'; then
    errors+=("[纯净性] 英文源 $name 含全角标点: $val")
  fi
done

# ============ 2 & 3. 各语言 key 完整性 + 占位符一致性 ============
lang_count=0
for lang_dir in "$RES_DIR"/values-*/; do
  [ -d "$lang_dir" ] || continue
  lang_count=$((lang_count + 1))
  lang_name="$(basename "$lang_dir")"
  lang_file="$lang_dir/strings.xml"
  if [ ! -f "$lang_file" ]; then
    errors+=("[完整性] $lang_name 缺少 strings.xml")
    continue
  fi

  unset lang_keys lang_values
  declare -A lang_keys=()
  declare -A lang_values=()

  while IFS= read -r k; do
    [ -n "$k" ] && lang_keys["$k"]=1
  done < <(extract_keys "$lang_file" | sed -E 's/.* name="([^"]+)".*/\1/')
  while IFS=$'\t' read -r name value; do
    [ -n "$name" ] && lang_values["$name"]="$value"
  done < <(extract_string_values "$lang_file")

  # 缺失 key（英文源有、语言文件无）—— 排序以保证输出稳定
  missing=()
  for k in $(printf '%s\n' "${!source_keys[@]}" | LC_ALL=C sort); do
    [ -n "${lang_keys[$k]:-}" ] || missing+=("$k")
  done
  # 孤儿 key（语言文件有、英文源无）
  orphans=()
  for k in $(printf '%s\n' "${!lang_keys[@]}" | LC_ALL=C sort); do
    [ -n "${source_keys[$k]:-}" ] || orphans+=("$k")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    errors+=("[完整性] $lang_name 缺失 key: $(printf '%s, ' "${missing[@]}" | sed 's/, $//')")
  fi
  if [ "${#orphans[@]}" -gt 0 ]; then
    errors+=("[完整性] $lang_name 孤儿 key（英文源已无）: $(printf '%s, ' "${orphans[@]}" | sed 's/, $//')")
  fi

  # 占位符一致性（仅比较英文源中存在的 key）
  for name in "${!source_values[@]}"; do
    [ -n "${lang_values[$name]:-}" ] || continue  # 缺失已在上方报告
    src_ph="$(get_placeholders "${source_values[$name]}")"
    lang_ph="$(get_placeholders "${lang_values[$name]}")"
    if [ "$src_ph" != "$lang_ph" ]; then
      errors+=("[占位符] $lang_name $name 与英文源不一致: en=[$src_ph] vs [$lang_ph]")
    fi
  done
done

# ============ 输出 ============
key_count="${#source_keys[@]}"
if [ "${#errors[@]}" -eq 0 ]; then
  echo "i18n check PASSED: $key_count keys x $lang_count languages, all consistent."
  exit 0
else
  echo "i18n check FAILED (${#errors[@]} errors):"
  for e in "${errors[@]}"; do
    echo "  $e"
  done
  exit 1
fi
