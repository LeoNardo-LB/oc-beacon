<#
.SYNOPSIS
    创建或更新 GitHub Release 并上传 APK。

⚠️ 未经运行试验，使用前请 review

.PARAMETER Version
    版本号（如 0.3.0-beta.3）。

.PARAMETER Flavor
    构建变体（beta/stable/dev）。

.NOTES
    环境变量: GH_TOKEN
    逻辑:
      1. 尝试创建并推送 tag v<Version>（已存在则忽略）
      2. flavor != stable 时标记 --prerelease
      3. Release 已存在 → gh release upload --clobber
         Release 不存在 → 优先用 RELEASE_NOTES.md，否则 --generate-notes
    前提: CWD 为仓库根目录。
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$Flavor
)
$ErrorActionPreference = 'Stop'

$tag = "v$Version"

# Create tag if it doesn't exist
git tag $tag 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "Tag $tag already exists, skipping." }
git push origin $tag 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "Tag $tag push skipped (may already exist on remote)." }

# 预发布标记：beta/dev 为 prerelease，stable 为正式版
$prereleaseFlags = @()
if ($Flavor -ne 'stable') {
    $prereleaseFlags = @('--prerelease')
}

$apkFiles = (Get-ChildItem 'release-apks/*.apk').FullName

# Check if release already exists
& gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "Release $tag already exists, uploading APK..."
    & gh release upload $tag @apkFiles --clobber
} else {
    Write-Host "Creating new release $tag..."
    # 发版说明优先使用 RELEASE_NOTES.md，缺失时回退 GitHub 自动生成
    $ghArgs = @('release', 'create', $tag, '--title', "OC Beacon $Version")
    $ghArgs += $prereleaseFlags
    if (Test-Path 'RELEASE_NOTES.md') {
        $ghArgs += '--notes-file', 'RELEASE_NOTES.md'
    } else {
        $ghArgs += '--generate-notes'
    }
    $ghArgs += $apkFiles
    & gh @ghArgs
}
