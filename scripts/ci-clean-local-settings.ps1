<#
.SYNOPSIS
    删除 gradle.properties 中的本地专用设置（系统代理、本地 JDK 路径）。

⚠️ 未经运行试验，使用前请 review

.NOTES
    清理项:
      - systemProp.*          （本地代理设置）
      - org.gradle.java.home  （本地 JDK 路径）
    写回时保留 UTF-8 无 BOM 编码。
    前提: CWD 为仓库根目录。
#>
$ErrorActionPreference = 'Stop'

$file = 'gradle.properties'
$lines = Get-Content -LiteralPath $file
$filtered = $lines | Where-Object {
    $_ -notmatch '^systemProp\.' -and $_ -notmatch '^org\.gradle\.java\.home'
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines((Resolve-Path $file).Path, $filtered, $utf8NoBom)
Write-Host "已清理 gradle.properties 中的本地设置（systemProp / java.home）"
