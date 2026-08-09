# 会话列表滑动性能快速测量（模拟器专用，adb 直驱，无 Maestro 依赖）
# 用法: .\scripts\perf-quick.ps1 [-Rounds 3] [-Tag name] [-Pkg dev.leonardo.ocbeacon.dev]
# 前提: 应用已启动并在目标页面（会话列表/聊天页）
param(
    [int]$Rounds = 3,
    [string]$Tag = "baseline",
    [string]$Pkg = "dev.leonardo.ocbeacon.dev"
)
$ErrorActionPreference = "Stop"
$AndroidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$Adb = Join-Path $AndroidHome "platform-tools\adb.exe"

Write-Host "=== perf-quick (tag=$Tag, rounds=$Rounds) ===" -ForegroundColor Cyan

$Rows = @()
for ($i = 1; $i -le $Rounds; $i++) {
    & $Adb shell dumpsys gfxinfo $Pkg reset | Out-Null
    # 5 次上滑 + 5 次下滑（固定参数，与真机脚本一致）
    & $Adb shell "for j in 1 2 3 4 5; do input swipe 540 1800 540 700 150; sleep 0.3; done" | Out-Null
    & $Adb shell "for j in 1 2 3 4 5; do input swipe 540 700 540 1800 150; sleep 0.3; done" | Out-Null
    Start-Sleep 1
    $Gfx = & $Adb shell dumpsys gfxinfo $Pkg 2>&1
    $Row = [PSCustomObject]@{ Round=$i; Frames=0; JankyPct=0; P50=0; P90=0; P95=0; P99=0; InpLat=0; SlowUI=0; MissVS=0 }
    foreach ($L in $Gfx) {
        if ($L -match "Total frames rendered: (\d+)") { $Row.Frames = [int]$Matches[1] }
        elseif ($L -match "Janky frames: (\d+) \(([\d.]+)%\)") { $Row.JankyPct = [double]$Matches[2] }
        elseif ($L -match "50th percentile: (\d+)ms") { $Row.P50 = [int]$Matches[1] }
        elseif ($L -match "90th percentile: (\d+)ms") { $Row.P90 = [int]$Matches[1] }
        elseif ($L -match "95th percentile: (\d+)ms") { $Row.P95 = [int]$Matches[1] }
        elseif ($L -match "99th percentile: (\d+)ms") { $Row.P99 = [int]$Matches[1] }
        elseif ($L -match "Number High input latency: (\d+)") { $Row.InpLat = [int]$Matches[1] }
        elseif ($L -match "Number Slow UI thread: (\d+)") { $Row.SlowUI = [int]$Matches[1] }
        elseif ($L -match "Number Missed Vsync: (\d+)") { $Row.MissVS = [int]$Matches[1] }
    }
    $Rows += $Row
    Write-Host ("  R{0}: frames={1} janky={2}% p50={3} p90={4} p99={5} inpLat={6} slowUI={7}" -f $i,$Row.Frames,$Row.JankyPct,$Row.P50,$Row.P90,$Row.P99,$Row.InpLat,$Row.SlowUI)
}
if ($Rows.Count -gt 1) {
    $A = [PSCustomObject]@{
        Janky = [math]::Round(($Rows | Measure-Object JankyPct -Average).Average, 2)
        P90 = [math]::Round(($Rows | Measure-Object P90 -Average).Average, 1)
        P99 = [math]::Round(($Rows | Measure-Object P99 -Average).Average, 1)
        InpLat = [math]::Round(($Rows | Measure-Object InpLat -Average).Average, 0)
        SlowUI = [math]::Round(($Rows | Measure-Object SlowUI -Average).Average, 0)
    }
    Write-Host ("=== AVG (tag=$Tag): janky={0}% p90={1}ms p99={2}ms inpLat={3} slowUI={4} ===" -f $A.Janky,$A.P90,$A.P99,$A.InpLat,$A.SlowUI) -ForegroundColor Green
}
