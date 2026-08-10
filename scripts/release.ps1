<#
.SYNOPSIS
    OC Beacon 一键发版脚本（PowerShell 版）。

.DESCRIPTION
    ⚠️⚠️⚠️ 警告 ⚠️⚠️⚠️
    本脚本由 scripts/release.sh（bash 版）翻译而来，
    **未经运行试验**（Windows PowerShell 环境未验证）。
    使用前请仔细 review，重点检查：
      - 外部命令调用（git / gh）的参数与退出码处理
      - 正则分类逻辑（conventional commits 匹配）
      - UTF-8 无 BOM 文件读写（version.properties / CHANGELOG.md）
      - 版本号计算（预发布序号递增）
    推荐先用 --dry-run 在真实仓库上验证输出，再正式发版。

.NOTES
    用法（与 bash 版保持一致的 CLI 接口）:
      .\scripts\release.ps1 <flavor> [--dry-run] [--force-bump=major|minor|patch]

      flavor:       beta（默认）| stable | dev
      --dry-run:    只打印将执行的步骤，不修改任何文件、不推送
      --force-bump: 跳过 commit 分析，强制指定递进类型

    功能:
      1. 检查 git 工作树干净
      2. 分析 commits（last tag -> HEAD）推导 bump 类型
      3. 计算新版本号（含 VERSION_CODE 递增）
      4. 更新 version.properties
      5. 生成 RELEASE_NOTES.md 草稿（所有 flavor）+ 更新 CHANGELOG.md（仅 stable）
      6. commit + tag + push（触发 CI 构建与 Release）

    详见 docs\release-workflow.md（发版前必读）

    兼容性: 目标 PowerShell 5.1（避免 PS7 专属语法）。
#>

# =============================================================================
# set -euo pipefail 的 PowerShell 近似：遇 cmdlet 错误即终止。
# 注意：外部命令（git/gh）的非零退出码不会自动抛异常（PS5.1），需手动检查 $LASTEXITCODE。
# =============================================================================
$ErrorActionPreference = 'Stop'

# ---- 配置 ----------------------------------------------------------------
# 定位仓库根：$PSScriptRoot 为本脚本所在目录（scripts\）
$ScriptDir = $PSScriptRoot
if ((Split-Path -Leaf $ScriptDir) -eq 'scripts') {
    $Root = Split-Path -Parent $ScriptDir
} else {
    $Root = $ScriptDir
}
Set-Location $Root

# ---- 参数解析（手动遍历 $args 以保持与 release.sh 一致的 CLI 接口）------
$Flavor = 'beta'
$DryRun = $false
$ForceBump = ''

# 第一个非 -- 开头的参数视为 flavor
$firstPositional = $true
foreach ($a in $args) {
    if ($firstPositional -and ($a -notlike '--*')) {
        $Flavor = $a
        $firstPositional = $false
        continue
    }
    $firstPositional = $false
    switch -Exact ($a) {
        '--dry-run' { $DryRun = $true }
        default {
            if ($a -like '--force-bump=*') {
                $ForceBump = ($a -split '=', 2)[1]
            }
        }
    }
}

# flavor 校验
if ($Flavor -notin @('beta', 'stable', 'dev')) {
    Write-Host "[release][error] ❌ 未知 flavor: $Flavor（应为 beta | stable | dev）" -ForegroundColor Red
    exit 1
}

# force-bump 校验
if ($ForceBump -ne '' -and $ForceBump -notin @('major', 'minor', 'patch')) {
    Write-Host "[release][error] ❌ 无效 --force-bump: $ForceBump（应为 major | minor | patch）" -ForegroundColor Red
    exit 1
}

$TagPrefix = 'v'
$VersionFile = 'version.properties'
$ChangelogFile = 'CHANGELOG.md'
$ReleaseNotesFile = 'RELEASE_NOTES.md'
$Remote = 'origin'
$Branch = 'master'

# ---- 工具函数 ------------------------------------------------------------
function Write-Log([string]$Message) {
    Write-Host "[release] $Message" -ForegroundColor Cyan
}

function Write-WarnMsg([string]$Message) {
    Write-Host "[release][warn] $Message" -ForegroundColor Yellow
}

function Die([string]$Message) {
    # 对应 bash die()：输出到 stderr 后 exit 1
    Write-Host "[release][error] $Message" -ForegroundColor Red
    exit 1
}

# UTF-8 无 BOM 文件读写（PS 5.1 的 Get-Content/Set-Content 默认编码不可靠）
function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path)
}

function Write-Utf8([string]$Path, [string]$Content) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $enc)
}

# run：dry-run 时只打印，否则执行（对应 bash run()）
function Invoke-Run {
    if ($DryRun) {
        Write-Log "[dry-run] $($args -join ' ')"
    } else {
        Write-Log "$($args -join ' ')"
        # 使用 & 调用第一个参数为命令，其余为参数
        $cmd = $args[0]
        $cmdArgs = @($args[1..($args.Count - 1)])
        & $cmd @cmdArgs
    }
}

# 检查 git ref 是否存在（对应 git rev-parse ... >/dev/null 2>&1）
function Test-GitRefExists([string]$Ref) {
    & git rev-parse "$Ref" 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# 检查暂存区是否有变更（git diff --cached --quiet）
function Test-CachedDiff {
    & git diff --cached --quiet 2>$null
    return ($LASTEXITCODE -ne 0)
}

# 语义版本比较（支持 MAJOR.MINOR.PATCH 及预发布标签）
# 返回 $true 若 $a 大于 $b。
# 注意：此函数为 bash 版保留翻译，当前脚本未调用（与 bash version_gt 一致）。
function Test-VersionGt([string]$a, [string]$b) {
    $pa = ConvertFrom-Version $a
    $pb = ConvertFrom-Version $b
    $va = New-Object Version([int]$pa.Major, [int]$pa.Minor, [int]$pa.Patch)
    $vb = New-Object Version([int]$pb.Major, [int]$pb.Minor, [int]$pb.Patch)
    $cmp = $va.CompareTo($vb)
    if ($cmp -ne 0) { return ($cmp -gt 0) }
    # base 相同：有预发布标签 < 无标签（正式版）
    if ($pa.Label -and -not $pb.Label) { return $false }
    if (-not $pa.Label -and $pb.Label) { return $true }
    if ($pa.Label -and $pb.Label) { return ($pa.Label.CompareTo($pb.Label) -gt 0) }
    return $false
}

# 获取最后一个正式版 tag（不含预发布标签，如 v1.0.3），无则返回空
function Get-LastStableTag {
    $tags = & git tag --sort=-v:refname 2>$null
    foreach ($t in @($tags)) {
        if ($t -match '^v\d+\.\d+\.\d+$') { return $t }
    }
    return $null
}

# 从 commit 信息推导 bump 类型
function Get-DeriveBump([string]$Since) {
    $commits = & git log --no-merges --format='%s' "${Since}..HEAD" 2>$null
    if ($null -eq $commits -or @($commits).Count -eq 0) {
        return 'patch'  # 无 commits 时最小递进
    }
    $text = (@($commits) -join "`n")
    $mOpt = [System.Text.RegularExpressions.RegexOptions]::Multiline
    $miOpt = $mOpt -bor [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    # BREAKING CHANGE footer 或 type!:/type(scope)!: 标记
    if ([regex]::IsMatch($text, 'BREAKING CHANGE|^[a-z]+\(?.*\)?!:', $miOpt)) {
        return 'major'
    }
    # feat: 或 feat(scope): → minor
    if ([regex]::IsMatch($text, '^feat(\(|:)', $mOpt)) {
        return 'minor'
    }
    return 'patch'
}

# 解析版本号（对应 bash parse_version）
# 输入 "1.0.3-beta.1" → 返回 @{Major='1'; Minor='0'; Patch='3'; Label='beta.1'}
function ConvertFrom-Version([string]$V) {
    $base = $V
    $label = ''
    if ($V.Contains('-')) {
        $idx = $V.IndexOf('-')
        $base = $V.Substring(0, $idx)
        $label = $V.Substring($idx + 1)
    }
    $parts = $base.Split('.')
    return @{
        Major = $parts[0]
        Minor = $parts[1]
        Patch = $parts[2]
        Label = $label
    }
}

# 对版本号应用 bump（对应 bash apply_bump）
function Get-BumpedBase([string]$Major, [string]$Minor, [string]$Patch, [string]$Bump) {
    switch -Exact ($Bump) {
        'major' { return "$([int]$Major + 1).0.0" }
        'minor' { return "$Major.$([int]$Minor + 1).0" }
        'patch' { return "$Major.$Minor.$([int]$Patch + 1)" }
    }
}

# 分类 commits（排除内部维护类，按 conventional commit type 分类）
# 内部辅助：返回 @{Added=...; Fixed=...; Changed=...; Removed=...}
# $BreakingPrefix: breaking 项的描述前缀（CHANGELOG 用 ''，Release Notes 用 '**BREAKING:** '）
function Classify-Commits([string]$Since, [string]$BreakingPrefix) {
    $added = ''
    $fixed = ''
    $changed = ''
    $removed = ''

    # 排除内部维护类 commit（test/ci/docs/chore 等，含 scope 变体）
    $excludePattern = '^fix\(test\):|^fix\(ci\):|^fix\(docs\):|^chore|^docs:|^docs\([^)]*\):|^test:|^test\([^)]*\):|^style:|^style\([^)]*\):|^build:|^build\([^)]*\):|^ci:|^ci\([^)]*\):'

    $breakingPattern = '^feat!|^feat\([^)]*\)!|^BREAKING'
    $featPattern = '^feat:|^feat\([^)]*\):'
    $fixPattern = '^fix:|^fix\([^)]*\):'
    $perfRefactorPattern = '^perf:|^perf\([^)]*\):|^refactor:|^refactor\([^)]*\):'

    $commits = & git log --no-merges --format='%s' "${Since}..HEAD" 2>$null
    foreach ($line in @($commits)) {
        if ($line -cmatch $excludePattern) { continue }
        # 去掉 commit type 前缀，保留描述
        $desc = $line -creplace '^[a-z]+(\([^)]*\))?!?:\s?', ''
        if ($line -cmatch $breakingPattern) {
            $removed += "- $BreakingPrefix$desc`n"
        } elseif ($line -cmatch $featPattern) {
            $added += "- $desc`n"
        } elseif ($line -cmatch $fixPattern) {
            $fixed += "- $desc`n"
        } elseif ($line -cmatch $perfRefactorPattern) {
            $changed += "- $desc`n"
        }
        # 其余（docs/chore/test/style/build/ci）不写入
    }

    return @{ Added = $added; Fixed = $fixed; Changed = $changed; Removed = $removed }
}

# 生成 CHANGELOG 条目（last stable tag -> HEAD）
function New-ChangelogEntry([string]$Since, [string]$Version) {
    $c = Classify-Commits $Since ''
    $dateStr = Get-Date -Format 'yyyy-MM-dd'

    $entry = "## [$Version] - $dateStr`n`n"
    if ($c.Removed) { $entry += "### Removed`n`n$($c.Removed)`n" }
    if ($c.Added) { $entry += "### Added`n`n$($c.Added)`n" }
    if ($c.Changed) { $entry += "### Changed`n`n$($c.Changed)`n" }
    if ($c.Fixed) { $entry += "### Fixed`n`n$($c.Fixed)`n" }
    if (-not ($c.Added + $c.Fixed + $c.Changed + $c.Removed)) {
        $entry += "_No user-facing changes._`n"
    }
    return $entry
}

# 生成 Release Notes 草稿（last tag -> HEAD，所有 flavor）
# 输出为 GitHub Release 说明模板（docs/release-notes-template.md），发布者润色后随发版 commit 提交
function New-ReleaseNotes([string]$Since, [string]$Version) {
    $c = Classify-Commits $Since '**BREAKING:** '
    $dateStr = Get-Date -Format 'yyyy-MM-dd'

    $entry = "## OC Beacon $Version — $dateStr`n`n"
    $entry += "> 版本摘要：（待填写——本版主题一句话）`n`n"
    if ($c.Removed) { $entry += "### Removed`n`n$($c.Removed)`n" }
    if ($c.Added) { $entry += "### Added`n`n$($c.Added)`n" }
    if ($c.Changed) { $entry += "### Changed`n`n$($c.Changed)`n" }
    if ($c.Fixed) { $entry += "### Fixed`n`n$($c.Fixed)`n" }
    if (-not ($c.Added + $c.Fixed + $c.Changed + $c.Removed)) {
        $entry += "_No user-facing changes._`n"
    }
    if ($Since) {
        $entry += "`n---`n"
        $entry += "完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/${Since}...${TagPrefix}${Version})`n"
    }
    return $entry
}

# =============================================================================
# 1. 前置检查
# =============================================================================
$forceBumpDisplay = if ($ForceBump) { $ForceBump } else { 'auto' }
Write-Log "flavor=$Flavor  dry_run=$DryRun  force_bump=$forceBumpDisplay"

if (-not $DryRun) {
    $status = & git status --porcelain 2>$null
    if ($status) {
        $preview = (@($status) | Select-Object -First 5) -join "`n"
        Die "git 工作树不干净，请先提交或 stash：$preview"
    }
    if (Test-CachedDiff) {
        Die "有暂存未提交的变更，请先提交。"
    }
}

if (-not (Test-Path -LiteralPath $VersionFile)) {
    Die "未找到 $VersionFile"
}

# =============================================================================
# 2. 读取当前版本 + 推导新版本
# =============================================================================
# 读取 version.properties（KEY=VALUE 格式）
$props = @{}
foreach ($line in (Read-Utf8 $VersionFile) -split "`r?`n") {
    if ($line -match '^([A-Z_]+)=(.*)$') {
        $props[$Matches[1]] = $Matches[2]
    }
}
$CurVersionCode = $props['VERSION_CODE']
$CurVersionName = $props['VERSION_NAME']
if (-not $CurVersionCode) { Die 'VERSION_CODE 为空' }
if (-not $CurVersionName) { Die 'VERSION_NAME 为空' }
Write-Log "当前版本: $CurVersionName (code=$CurVersionCode)"

$LastStable = Get-LastStableTag
# 分析基准 tag：优先当前 VERSION_NAME 对应的 tag（如 v1.0.3-beta.1 存在则用它），否则用最后一个正式版
$CurTag = "${TagPrefix}${CurVersionName}"
if (Test-GitRefExists $CurTag) {
    $LastAny = $CurTag
} else {
    $LastAny = $LastStable
}
$lastStableDisplay = if ($LastStable) { $LastStable } else { '<无>' }
$lastAnyDisplay = if ($LastAny) { $LastAny } else { '<无>' }
Write-Log "最后一个正式版 tag: $lastStableDisplay"
Write-Log "分析基准 tag: $lastAnyDisplay"

# 决定 bump 类型
if ($ForceBump) {
    $Bump = $ForceBump
    Write-Log "强制 bump: $Bump"
} else {
    if ($LastAny) {
        $Bump = Get-DeriveBump $LastAny
    } else {
        $Bump = 'minor'
    }
    Write-Log "commit 推导 bump: $Bump"
}

# 计算新版本
$Cur = ConvertFrom-Version $CurVersionName

# 预发布标签名
switch -Exact ($Flavor) {
    'beta'   { $Label = 'beta' }
    'dev'    { $Label = 'dev' }
    'stable' { $Label = '' }
}

$NewVersionName = ''
if ($Flavor -eq 'stable') {
    # 正式版：当前就是正式版则 bump；当前是预发布则去掉标签
    if (-not $Cur.Label) {
        $NewVersionName = Get-BumpedBase $Cur.Major $Cur.Minor $Cur.Patch $Bump
    } else {
        $NewVersionName = "$($Cur.Major).$($Cur.Minor).$($Cur.Patch)"
    }
} else {
    # 预发布：若当前是同一版本的预发布 → 序号+1；否则基于当前正式部分 bump 后加 -label.1
    if ($Cur.Label -and ($Cur.Label.Split('.')[0] -eq $Label)) {
        # 同标签预发布 → 序号+1（如 beta.1 → beta.2）
        $localNum = $Cur.Label.Split('.')[-1]
        $NewVersionName = "$($Cur.Major).$($Cur.Minor).$($Cur.Patch)-$Label.$([int]$localNum + 1)"
    } else {
        $newBase = Get-BumpedBase $Cur.Major $Cur.Minor $Cur.Patch $Bump
        $NewVersionName = "$newBase-$Label.1"
    }
}

$NewVersionCode = [int]$CurVersionCode + 1
$NewTag = "${TagPrefix}${NewVersionName}"

Write-Log "新版本: $NewVersionName (code=$NewVersionCode)  tag=$NewTag"

# tag 冲突检查
if (Test-GitRefExists $NewTag) {
    Die "tag $NewTag 已存在！请检查是否重复发版。"
}

# =============================================================================
# 3. 执行变更
# =============================================================================
# 3.1 version.properties
# 对应 bash 版 run python - "$VERSION_FILE" ...（用 Python 写以规避 Git Bash 编码问题）。
# PowerShell 版直接用 .NET API 写 UTF-8 无 BOM，无需 Python。
if ($DryRun) {
    Write-Log "[dry-run] 将写入 $VersionFile : VERSION_CODE=$NewVersionCode VERSION_NAME=$NewVersionName"
} else {
    Write-Log "写入 $VersionFile"
    $versionContent = "VERSION_CODE=$NewVersionCode`nVERSION_NAME=$NewVersionName`n"
    Write-Utf8 $VersionFile $versionContent
}

# 3.2 CHANGELOG（仅 stable）
$ChangelogUpdated = $false
if ($Flavor -eq 'stable') {
    if ($LastStable) {
        $Since = $LastStable
    } else {
        # 首个 commit（无父提交）
        $Since = (& git rev-list --max-parents=0 HEAD 2>$null | Select-Object -First 1)
    }
    $Entry = New-ChangelogEntry $Since $NewVersionName
    if ($DryRun) {
        Write-Log "[dry-run] CHANGELOG.md 将插入条目："
        ($Entry -split "`n" | Select-Object -First 15) -join "`n" | ForEach-Object { Write-Host $_ }
    } else {
        if (Test-Path -LiteralPath $ChangelogFile) {
            # 幂等插入：目标版本已存在则不重复插入
            # 对应 bash 版用 Python 处理（规避 Git Bash 多行中文 argv 编码损坏）
            $content = Read-Utf8 $ChangelogFile
            $verMarker = ($Entry -split "`n", 2)[0].Trim()
            if ((($content -replace "`r`n", "`n")).Contains("$verMarker`n")) {
                # 已存在，跳过
                Write-WarnMsg "CHANGELOG.md 已含 $verMarker，跳过插入"
            } else {
                $idx = $content.IndexOf("## [")
                if ($idx -eq -1) {
                    $content = $content.TrimEnd() + "`n`n" + $Entry
                } else {
                    $content = $content.Substring(0, $idx) + $Entry + "`n" + $content.Substring($idx)
                }
                Write-Utf8 $ChangelogFile $content
            }
        } else {
            # 首次创建 CHANGELOG
            $header = "# Changelog`n`n本项目遵循 [Semantic Versioning](https://semver.org/) 与 [Keep a Changelog](https://keepachangelog.com/)。`n**CHANGELOG 仅在正式版（stable release）发布时更新**；beta/dev 预发布的变更在正式版发布时统一汇总。`n`n"
            Write-Utf8 $ChangelogFile ($header + $Entry)
        }
        Write-Log "CHANGELOG.md 已更新"
        $ChangelogUpdated = $true
    }
} else {
    Write-Log "预发布版不更新 CHANGELOG.md（正式版统一汇总）"
}

# 3.3 Release Notes 草稿（所有 flavor，范围 last tag -> HEAD）
$NotesSince = $LastAny
if (-not $NotesSince) {
    $NotesSince = (& git rev-list --max-parents=0 HEAD 2>$null | Select-Object -First 1)
}
$Notes = New-ReleaseNotes $NotesSince $NewVersionName
if ($DryRun) {
    Write-Log "[dry-run] RELEASE_NOTES.md 草稿："
    ($Notes -split "`n" | Select-Object -First 15) -join "`n" | ForEach-Object { Write-Host $_ }
} else {
    Write-Utf8 $ReleaseNotesFile $Notes
    Write-Log "RELEASE_NOTES.md 草稿已生成（请润色，模板见 docs\release-notes-template.md）"
}

# =============================================================================
# 4. commit + tag + push
# =============================================================================
if ($DryRun) {
    Write-Log "[dry-run] 将执行: git add version.properties [CHANGELOG.md] RELEASE_NOTES.md"
    Write-Log "[dry-run] 将执行: git commit -m `"chore: bump version to $NewVersionName`""
    Write-Log "[dry-run] 将执行: git tag -a $NewTag -m `"$NewTag`""
    Write-Log "[dry-run] 将执行: git push $Remote $Branch ; git push $Remote $NewTag"
    Write-Host "✅ dry-run 完成，未做任何修改。" -ForegroundColor Green
    exit 0
}

# 4.0 Release Notes 人工润色确认（所有 flavor）
if (Test-Path -LiteralPath $ReleaseNotesFile) {
    Write-Host ''
    Write-Host '──────────────────────────────────────────────────────────'
    Write-Host ' RELEASE_NOTES.md 草稿已生成（模板见 docs\release-notes-template.md）：'
    Write-Host '   - 必填：版本摘要（第 2 行）'
    Write-Host '   - 建议：条目改为用户视角（不粘贴 commit message）'
    Write-Host '   按回车直接继续；或先编辑 RELEASE_NOTES.md 再回来按回车'
    Write-Host '──────────────────────────────────────────────────────────'
    $null = Read-Host '按回车继续发版（Ctrl+C 取消）'
}

# 4.0 正式版 CHANGELOG 人工润色确认
if ($Flavor -eq 'stable' -and $ChangelogUpdated) {
    Write-Host ''
    Write-Host '──────────────────────────────────────────────────────────'
    Write-Host ' CHANGELOG.md 已自动更新。可以现在人工润色（可选）：'
    Write-Host '   按回车直接继续；或先编辑 CHANGELOG.md 再回来按回车'
    Write-Host '──────────────────────────────────────────────────────────'
    $null = Read-Host '按回车继续发版（Ctrl+C 取消）'
}

# 4.1 工作树更新
& git add $VersionFile
if ($ChangelogUpdated) {
    & git add $ChangelogFile
}
if (Test-Path -LiteralPath $ReleaseNotesFile) {
    & git add $ReleaseNotesFile
}

# 4.2 commit（仅当有变更）
if (Test-CachedDiff) {
    & git commit -m "chore: bump version to $NewVersionName"
    Write-Log 'committed'
} else {
    Write-WarnMsg 'version.properties 无变更，跳过 commit'
}

# 4.3 tag
& git tag -a $NewTag -m "$NewTag"
Write-Log "tagged: $NewTag"

# 4.4 push（触发 CI）
& git push $Remote $Branch
Write-Log "pushed: $Remote/$Branch"
& git push $Remote $NewTag
Write-Log "pushed: $NewTag"

# =============================================================================
# 5. 完成提示
# =============================================================================
$msg = @"

══════════════════════════════════════════════════════════════════
 ✅ 发版请求已提交，CI 正在构建
══════════════════════════════════════════════════════════════════
 版本:  $NewVersionName (code=$NewVersionCode)
 tag:   $NewTag
 flavor: $Flavor

 CI 将自动:
  - 构建 $Flavor release APK（release keystore 签名）
  - 复制为 oc-beacon-$NewVersionName.apk
  - 创建/更新 GitHub Release（说明来自 RELEASE_NOTES.md）

 验证（约 5-10 分钟后）:
  gh release list
  gh release view $NewTag --json assets

 若 CI 未触发，检查 .github/workflows/release.yml。
══════════════════════════════════════════════════════════════════
"@
Write-Host $msg
