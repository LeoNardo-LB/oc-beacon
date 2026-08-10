<#
.SYNOPSIS
    从 base64 编码的 keystore secret 解码签名密钥，并生成 signing.properties。

⚠️ 未经运行试验，使用前请 review

.NOTES
    环境变量:
      KEYSTORE_BASE64    — base64 编码的 keystore 文件内容
      KEYSTORE_ALIAS     — keystore 别名
      KEYSTORE_PASSWORD  — keystore 密码
    前提: CWD 为仓库根目录。
#>
$ErrorActionPreference = 'Stop'

if (-not $env:KEYSTORE_BASE64)   { throw '环境变量 KEYSTORE_BASE64 未设置' }
if (-not $env:KEYSTORE_ALIAS)    { throw '环境变量 KEYSTORE_ALIAS 未设置' }
if (-not $env:KEYSTORE_PASSWORD) { throw '环境变量 KEYSTORE_PASSWORD 未设置' }

$keystoreDir = Join-Path $PWD 'app/keystore'
New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null

# 解码 base64 并写入二进制 keystore 文件
$bytes = [Convert]::FromBase64String($env:KEYSTORE_BASE64)
[IO.File]::WriteAllBytes((Join-Path $keystoreDir 'release.keystore'), $bytes)

# 写 signing.properties（UTF-8 无 BOM）
$lines = @(
    'keystore=keystore/release.keystore',
    "keystore.alias=$env:KEYSTORE_ALIAS",
    "keystore.password=$env:KEYSTORE_PASSWORD"
)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllLines((Join-Path $keystoreDir 'signing.properties'), $lines, $utf8NoBom)
