<#
.SYNOPSIS
    OC Beacon 国际化完整性检查（替代已移除的 lokit 工具）。

.DESCRIPTION
    检查 app/src/main/res 下的多语言资源：
      1. Key 完整性   — 每个语言文件（values-*）的 key 集合必须与英文源（values/）完全一致
      2. 英文源纯净   — values/strings.xml 不允许出现 CJK 汉字与全角标点（历史教训：曾混入 4 处中文导致英文系统显示中文）
      3. 占位符一致   — 每个语言每个 key 的格式占位符（%1$s / %d 等）必须与英文源一致（不一致会导致运行时崩溃或显示错误）

.EXAMPLE
    pwsh scripts/i18n-check.ps1        # 全量检查；有错误时退出码为 1（CI 用）
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$resDir = Join-Path $root 'app/src/main/res'
$sourceFile = Join-Path $resDir 'values/strings.xml'

if (-not (Test-Path -LiteralPath $sourceFile)) {
    Write-Error "未找到英文源文件: $sourceFile"
    exit 1
}

$errors = @()

# .NET API 读取（PS 5.1 的 Get-Content 默认 ANSI 会破坏 UTF-8 无 BOM 文件）
function Read-Utf8([string]$path) { return [System.IO.File]::ReadAllText($path) }

# 提取 string + plurals 的 key 集合
function Get-Keys([string]$content) {
    $keys = @{}
    foreach ($m in [regex]::Matches($content, '<string name="([^"]+)"')) { $keys[$m.Groups[1].Value] = $true }
    foreach ($m in [regex]::Matches($content, '<plurals name="([^"]+)"')) { $keys[$m.Groups[1].Value] = $true }
    return $keys
}

# 提取 string 值（不含 plurals，plurals 不参与占位符比较）
function Get-StringValues([string]$content) {
    $map = @{}
    foreach ($m in [regex]::Matches($content, '<string name="([^"]+)">([^<]*)</string>')) {
        $map[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $map
}

# 占位符集合（归一化 %1$d -> %d，二者等价；位置参数不变）
function Get-Placeholders([string]$value) {
    $list = @()
    foreach ($m in [regex]::Matches($value, '%(?:(\d+)\$)?[ds]')) {
        $list += ($m.Value -replace '%1\$', '%')
    }
    return @($list | Sort-Object)
}

$sourceContent = Read-Utf8 $sourceFile
$sourceKeys = Get-Keys $sourceContent
$sourceValues = Get-StringValues $sourceContent

# ============ 1. 英文源纯净性 ============
foreach ($m in [regex]::Matches($sourceContent, '<string name="([^"]+)">([^<]*)</string>')) {
    $name = $m.Groups[1].Value
    $value = $m.Groups[2].Value
    if ($value -match '[\u4e00-\u9fff]') {
        $errors += "[纯净性] 英文源 $name 含 CJK 字符: $value"
    }
    if ($value -match '[\uff00-\uffef]') {
        $errors += "[纯净性] 英文源 $name 含全角标点: $value"
    }
}

# ============ 2. 各语言 key 完整性 ============
$langDirs = Get-ChildItem -LiteralPath $resDir -Directory -Filter 'values-*'
foreach ($dir in $langDirs) {
    $langFile = Join-Path $dir.FullName 'strings.xml'
    if (-not (Test-Path -LiteralPath $langFile)) {
        $errors += "[完整性] $($dir.Name) 缺少 strings.xml"
        continue
    }
    $langContent = Read-Utf8 $langFile
    $langKeys = Get-Keys $langContent

    $missing = @($sourceKeys.Keys | Where-Object { -not $langKeys.ContainsKey($_) })
    $orphans = @($langKeys.Keys | Where-Object { -not $sourceKeys.ContainsKey($_) })
    if ($missing.Count -gt 0) { $errors += "[完整性] $($dir.Name) 缺失 key: $($missing -join ', ')" }
    if ($orphans.Count -gt 0) { $errors += "[完整性] $($dir.Name) 孤儿 key（英文源已无）: $($orphans -join ', ')" }

    # ============ 3. 占位符一致性 ============
    $langValues = Get-StringValues $langContent
    foreach ($name in $sourceValues.Keys) {
        if (-not $langValues.ContainsKey($name)) { continue }  # 缺失已在上方报告
        $srcPh = Get-Placeholders $sourceValues[$name]
        $langPh = Get-Placeholders $langValues[$name]
        if (($srcPh -join ',') -ne ($langPh -join ',')) {
            $errors += "[占位符] $($dir.Name) $name 与英文源不一致: en=[$($srcPh -join ',')] vs [$($langPh -join ',')]"
        }
    }
}

# ============ 输出 ============
if ($errors.Count -eq 0) {
    $langCount = $langDirs.Count
    Write-Output "i18n check PASSED: $($sourceKeys.Count) keys x $($langCount) languages, all consistent."
    exit 0
} else {
    Write-Output "i18n check FAILED ($($errors.Count) errors):"
    $errors | ForEach-Object { Write-Output "  $_" }
    exit 1
}
