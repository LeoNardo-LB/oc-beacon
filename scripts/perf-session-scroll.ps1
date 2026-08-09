# 会话列表滑动性能测量脚本（自动化、可重复、A/B 对比友好）
#
# 用法：
#   .\scripts\perf-session-scroll.ps1 [-Flavor dev] [-Rounds 3] [-Tag baseline]
#
# 功能：
#   1. 构建并安装指定 flavor 的 debug APK
#   2. 启动应用并导航到会话列表（Maestro 流程）
#   3. 每次 round：reset gfxinfo → 跑 Maestro 固定滑动 → 采集 gfxinfo 帧统计
#   4. 输出汇总报告（janky 率 / 分位数 / 输入延迟 / UI 线程阻塞），供 A/B 对比
#
# 依赖：Android SDK（adb）、Maestro CLI、应用已配置模拟器/真机连接
# 注意：真实滑动由 Maestro 驱动（固定参数），避免人手滑动的不一致性

param(
    [string]$Flavor = "dev",
    [int]$Rounds = 3,
    [string]$Tag = "baseline"
)

$ErrorActionPreference = "Stop"
$AndroidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$Adb = Join-Path $AndroidHome "platform-tools\adb.exe"
$Maestro = "maestro"

# applicationId 映射（与 build.gradle.kts productFlavors 一致）
$PkgMap = @{
    dev    = "dev.leonardo.ocbeacon.dev"
    beta   = "dev.leonardo.ocbeacon.beta"
    stable = "dev.leonardo.ocbeacon"
}
$Pkg = $PkgMap[$Flavor]
if (-not $Pkg) { throw "Unknown flavor: $Flavor (use dev/beta/stable)" }

$Apk = "app\build\outputs\apk\$Flavor\debug\app-$Flavor-debug.apk"

Write-Host "=== 会话列表滑动性能测试 ===" -ForegroundColor Cyan
Write-Host "Flavor: $Flavor | Package: $Pkg | Rounds: $Rounds | Tag: $Tag"
Write-Host ""

# 0. 检查设备
$Devices = & $Adb devices | Select-String "device$"
if (-not $Devices) { throw "No device connected" }
Write-Host "[OK] Device connected: $($Devices.Line.Trim())"

# 1. 构建 + 安装
Write-Host "[1/5] Building APK ($Flavor debug)..."
& .\gradlew ":app:assemble${Flavor^}Debug" --console=plain 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Build failed" }
Write-Host "[OK] Build done"

Write-Host "[2/5] Installing APK..."
& $Adb install -r $Apk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Install failed" }
Write-Host "[OK] Installed"

# 2. 启动应用
Write-Host "[3/5] Launching app..."
& $Adb shell am start -n "$Pkg/dev.leonardo.ocbeacon.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 4
# 确保屏幕唤醒 + 解锁（模拟器偶尔锁屏）
& $Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $Adb shell wm dismiss-keyguard | Out-Null
Start-Sleep -Seconds 2
Write-Host "[OK] Launched"

# 3. 逐轮测量
$Results = @()
for ($i = 1; $i -le $Rounds; $i++) {
    Write-Host "[4/5] Round $i/$Rounds: reset + scroll + measure..."

    # reset gfxinfo 计数器（每轮独立窗口）
    & $Adb shell dumpsys gfxinfo $Pkg reset | Out-Null

    # 跑 Maestro 固定滑动流程（导航到会话列表 + 10 次固定滑动）
    & $Maestro test --env APP_ID=$Pkg maestro\perf-session-scroll.yaml 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning "Maestro flow failed at round $i (retrying once)"; & $Maestro test --env APP_ID=$Pkg maestro\perf-session-scroll.yaml 2>&1 | Out-Null }

    # 采集帧统计
    $Gfx = & $Adb shell dumpsys gfxinfo $Pkg 2>&1
    $Row = [PSCustomObject]@{
        Round        = $i
        TotalFrames  = 0
        JankyFrames  = 0
        JankyPct     = 0
        P50Ms        = 0
        P90Ms        = 0
        P95Ms        = 0
        P99Ms        = 0
        HighInputLat = 0
        SlowUiThread = 0
        MissedVsync  = 0
    }
    foreach ($Line in $Gfx) {
        if ($Line -match "Total frames rendered: (\d+)") { $Row.TotalFrames = [int]$Matches[1] }
        elseif ($Line -match "Janky frames: (\d+) \(([\d.]+)%\)") { $Row.JankyFrames = [int]$Matches[1]; $Row.JankyPct = [double]$Matches[2] }
        elseif ($Line -match "50th percentile: (\d+)ms") { $Row.P50Ms = [int]$Matches[1] }
        elseif ($Line -match "90th percentile: (\d+)ms") { $Row.P90Ms = [int]$Matches[1] }
        elseif ($Line -match "95th percentile: (\d+)ms") { $Row.P95Ms = [int]$Matches[1] }
        elseif ($Line -match "99th percentile: (\d+)ms") { $Row.P99Ms = [int]$Matches[1] }
        elseif ($Line -match "Number High input latency: (\d+)") { $Row.HighInputLat = [int]$Matches[1] }
        elseif ($Line -match "Number Slow UI thread: (\d+)") { $Row.SlowUiThread = [int]$Matches[1] }
        elseif ($Line -match "Number Missed Vsync: (\d+)") { $Row.MissedVsync = [int]$Matches[1] }
    }
    $Results += $Row
}

# 4. 输出汇总
Write-Host "[5/5] Results (tag=$Tag):" -ForegroundColor Cyan
Write-Host ("{0,-6} {1,-10} {2,-9} {3,-6} {4,-6} {5,-6} {6,-6} {7,-8} {8,-8} {9,-8}" -f "Round","Frames","Janky%","P50","P90","P95","P99","InpLat","SlowUI","MissVS")
foreach ($R in $Results) {
    Write-Host ("{0,-6} {1,-10} {2,-9} {3,-6} {4,-6} {5,-6} {6,-6} {7,-8} {8,-8} {9,-8}" -f $R.Round,$R.TotalFrames,$R.JankyPct,$R.P50Ms,$R.P90Ms,$R.P95Ms,$R.P99Ms,$R.HighInputLat,$R.SlowUiThread,$R.MissedVsync)
}

# 平均
if ($Results.Count -gt 1) {
    $Avg = [PSCustomObject]@{
        AvgJankyPct = [math]::Round(($Results | Measure-Object JankyPct -Average).Average, 2)
        AvgP90      = [math]::Round(($Results | Measure-Object P90Ms -Average).Average, 1)
        AvgP99      = [math]::Round(($Results | Measure-Object P99Ms -Average).Average, 1)
        AvgInpLat   = [math]::Round(($Results | Measure-Object HighInputLat -Average).Average, 0)
        AvgSlowUi   = [math]::Round(($Results | Measure-Object SlowUiThread -Average).Average, 0)
    }
    Write-Host ""
    Write-Host "=== AVERAGE (tag=$Tag) ===" -ForegroundColor Green
    Write-Host ("Janky%: {0} | P90: {1}ms | P99: {2}ms | InpLat: {3} | SlowUI: {4}" -f $Avg.AvgJankyPct, $Avg.AvgP90, $Avg.AvgP99, $Avg.AvgInpLat, $Avg.AvgSlowUi)
}

Write-Host ""
Write-Host "=== DONE (tag=$Tag) ===" -ForegroundColor Cyan
