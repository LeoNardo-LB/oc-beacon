<#
.SYNOPSIS
    从 tag 或手动输入推导构建 flavor（beta/stable/dev）。

⚠️ 未经运行试验，使用前请 review

.PARAMETER FlavorInput
    手动指定的 flavor（workflow_dispatch 输入，可为空）。

.PARAMETER GithubRef
    GitHub ref（如 refs/tags/v1.0.3）。

.PARAMETER OutputFile
    输出文件路径（CI 传 $env:GITHUB_OUTPUT）。

.NOTES
    推导规则:
      v1.0.3         -> stable（无后缀）
      v1.0.4-beta.1  -> beta
      v1.0.4-dev.1   -> dev
      非 tag 触发     -> beta（默认）
    前提: CWD 为仓库根目录。
#>
param(
    [Parameter(Position = 0)]
    [string]$FlavorInput = '',

    [Parameter(Position = 1)]
    [string]$GithubRef = '',

    [Parameter(Mandatory = $true, Position = 2)]
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'

if ($FlavorInput) {
    $flavor = $FlavorInput
} elseif ($GithubRef -like 'refs/tags/*') {
    $tag = $GithubRef -replace '^refs/tags/', ''
    if ($tag -like '*-beta.*') {
        $flavor = 'beta'
    } elseif ($tag -like '*-dev.*') {
        $flavor = 'dev'
    } else {
        $flavor = 'stable'
    }
} else {
    $flavor = 'beta'
}

Add-Content -LiteralPath $OutputFile -Value "flavor=$flavor"
Write-Host "Flavor: $flavor"
