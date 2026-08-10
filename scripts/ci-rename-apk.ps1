<#
.SYNOPSIS
    将构建产物 APK 重命名为发布文件名。

⚠️ 未经运行试验，使用前请 review

.PARAMETER Version
    版本号（如 0.3.0-beta.3）。

.PARAMETER Flavor
    构建变体（beta/stable/dev）。

.NOTES
    前提: CWD 为仓库根目录；Build Release APK 步骤已完成。
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$Flavor
)
$ErrorActionPreference = 'Stop'

$releaseDir = 'release-apks'
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

$apkDir = "app/build/outputs/apk/$Flavor/release"
$apk = $null
if (Test-Path $apkDir) {
    $apk = Get-ChildItem -Path $apkDir -Filter '*.apk' | Select-Object -First 1
}

if ($apk) {
    Copy-Item -LiteralPath $apk.FullName -Destination "$releaseDir/oc-beacon-$Version.apk"
} else {
    # GitHub Actions error annotation（PowerShell 等效输出）
    Write-Host "::error::No APK found in $apkDir/"
    exit 1
}
