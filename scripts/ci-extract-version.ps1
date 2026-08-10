<#
.SYNOPSIS
    从 version.properties 提取版本号，写入输出文件（CI 传 $env:GITHUB_OUTPUT）。

⚠️ 未经运行试验，使用前请 review

.PARAMETER OutputFile
    输出文件路径（CI 传 $env:GITHUB_OUTPUT）。

.NOTES
    前提: CWD 为仓库根目录。
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'

$lines = Get-Content -LiteralPath 'version.properties'
$version     = ($lines | Where-Object { $_ -match '^VERSION_NAME=' }) -replace '^VERSION_NAME=', ''
$versionCode = ($lines | Where-Object { $_ -match '^VERSION_CODE=' }) -replace '^VERSION_CODE=', ''

Add-Content -LiteralPath $OutputFile -Value "name=$version"
Add-Content -LiteralPath $OutputFile -Value "code=$versionCode"
Write-Host "Building version: $version (code: $versionCode)"
