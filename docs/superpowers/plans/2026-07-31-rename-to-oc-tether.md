# OC Tether 改名实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将应用从「OC Remote Plus」彻底更名为「OC Tether」，覆盖包路径、applicationId、用户可见名、更新检查器、maestro、CI、文档、图标、版本重置、仓库名。

**Architecture:** 大规模机械重构。核心是包路径 `dev.leonardo.ocremoteplus` → `dev.leonardo.octether`（目录重命名 + 全量 package/import 替换），辅以用户可见文本、配置、文档的批量替换。采用「基线 → 分批替换 → 编译/grep 验证 → 频繁 commit」的验证驱动模式，每个 task 独立可编译可验证。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + Ktor + Gradle Kotlin DSL；Windows PowerShell 5.1 执行环境；JDK 21。

## Global Constraints

- **包名用 `dev.leonardo.octether`（无横杠）**；**仓库名用 `oc-tether`（有横杠）**。两者刻意区分，替换时勿混淆。
- **LICENSE 保留 `Copyright (c) 2026 crims0n` 原作者声明**（MIT 强制要求），不得删除；可保留 fork 作者 Copyright 行。
- **版本重置**：`version.properties` → `VERSION_CODE=1` / `VERSION_NAME=1.0.0`。
- **不实现数据迁移**：applicationId 改变后旧版用户视为全新安装，已知代价，本计划接受。
- **每步编译验证**：`./gradlew :app:compileDevDebugKotlin`（超时 120s）；完整单测 `./gradlew :app:testDevDebugUnitTest --rerun`（超时 180s）。
- **ChatScreen.kt 编辑协议**：该文件涉及 86 处 import 替换，批量替换后必须立即编译验证；禁止与其它 agent 并行编辑同一文件。
- **Gradle daemon**：`org.gradle.daemon=false`（已在 gradle.properties）；卡住时 `.\gradlew --stop`。
- **替换原则**：`dev.leonardo.ocremoteplus` 作为 token 足够独特，可安全全局子串替换；`OC Remote Plus`（含空格）同理。注意区分「OC Remote」（指代上游，注释/声明里保留）与「OC Remote Plus」（本应用旧名，必须替换）。
- **实施检索守则（强制）**：每个 task 完成后，必须用 `rg` 检索关键词 `ocremoteplus`、`oc-remote-plus`、`OC Remote Plus`、`LeoNardo-LB/oc-remote-plus`，确认本 task 范围内零残留。Task 7 做全仓库检索兜底。发现残留立即回对应 task 修补，不得放过。

---

## File Structure

按变更类别归类（非新建文件，均为修改/重命名）：

| 类别 | 文件 | 责任 |
|------|------|------|
| 包路径目录 | `app/src/{main,test,androidTest,debug}/kotlin/dev/leonardo/ocremoteplus/` | 重命名为 `octether/` |
| 包路径内容 | 所有 `.kt`（~150 文件） | `package` + `import` 声明 |
| Gradle 标识 | `app/build.gradle.kts` | namespace / applicationId / testRunner / flavor appLabel |
| ProGuard | `app/proguard-rules.pro` | 3 处 keep 规则 |
| 用户可见名 | 16 语言 `strings.xml` + `OpenCodeApp.kt` + `DiagnosticsScreen.kt` | 显示名 |
| 更新检查器 | `UpdateRepository.kt` + `UpdateModels.kt` | 仓库名 URL + 包名校验集合 + APK 名 |
| 测试配置 | `maestro/*.yaml` | appId |
| CI | `.github/workflows/release.yml` | APK 名 + artifact 名 |
| 版本 | `version.properties` | 版本重置 |
| 文档 | `README.md` / `AGENTS.md` / `backlog.md` / `docs/**` | 全量文本 |
| 图标 | `app/src/main/res/mipmap*/ic_launcher*.png` | 全套替换 |

---

## Task 0: 建立基线安全网

**Files:** 无修改，仅验证。

**目的：** 确认改名前的当前代码可编译、单测通过，作为后续重构的对照基线。若基线已红，先修红再开始。

- [ ] **Step 1: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（超时 120s）

- [ ] **Step 2: 单测验证**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（超时 180s）。记录失败数（应为 0）。

- [ ] **Step 3: 记录旧名残留基线（供最终对比）**

Run:
```powershell
(rg -ci "ocremoteplus" app/src app/build.gradle.kts app/proguard-rules.pro | Measure-Object).Count
(rg -ci "OC Remote Plus" app/src README.md AGENTS.md | Measure-Object).Count
```
Expected: 两个数字均 > 0（这是待清零的基线）。记录数值。

- [ ] **Step 4: 无 commit（基线任务不改代码）**

---

## Task 1: 包路径重构（核心）

**Files:**
- 重命名目录：`app/src/{main,test,androidTest,debug}/kotlin/dev/leonardo/ocremoteplus/` → `octether/`
- 修改：所有 `.kt`（`package` + `import` 声明）
- 修改：`app/build.gradle.kts:19,23,29`
- 修改：`app/proguard-rules.pro:15,16,19`

**风险：** 最高风险 task。包路径替换必须 100% 覆盖，遗漏任一文件即编译失败。批量脚本 + 编译验证兜底。

- [ ] **Step 1: 替换所有 .kt 文件内的包名 token**

执行脚本（PowerShell，UTF-8 无 BOM 读写，避免编码损坏）：
```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$changed = 0
Get-ChildItem -Path app/src -Recurse -Filter *.kt | ForEach-Object {
    $orig = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    $new = $orig.Replace('dev.leonardo.ocremoteplus', 'dev.leonardo.octether')
    if ($orig -ne $new) {
        [System.IO.File]::WriteAllText($_.FullName, $new, $utf8NoBom)
        $changed++
    }
}
Write-Output "Modified $changed .kt files"
```
Expected: 输出修改文件数（应 > 100）。

- [ ] **Step 2: 验证 .kt 内零残留**

Run: `rg -n "dev\.leonardo\.ocremoteplus" app/src`
Expected: 无输出（零残留）。

- [ ] **Step 3: git mv 重命名 4 个 source set 目录**

```powershell
foreach ($src in @('main','test','androidTest','debug')) {
    $base = "app/src/$src/kotlin/dev/leonardo"
    if (Test-Path "$base/ocremoteplus") {
        git mv "$base/ocremoteplus" "$base/octether"
    }
}
```
Expected: 4 个目录重命名成功（部分 source set 可能不存在，按实际跳过）。

- [ ] **Step 4: 修改 build.gradle.kts 标识**

`app/build.gradle.kts`：
- L19: `namespace = "dev.leonardo.ocremoteplus"` → `namespace = "dev.leonardo.octether"`
- L23: `applicationId = "dev.leonardo.ocremoteplus"` → `applicationId = "dev.leonardo.octether"`
- L29: `testInstrumentationRunner = "dev.leonardo.ocremoteplus.HiltTestRunner"` → `"dev.leonardo.octether.HiltTestRunner"`

- [ ] **Step 5: 修改 proguard-rules.pro**

`app/proguard-rules.pro`：3 处 `dev.leonardo.ocremoteplus.**` → `dev.leonardo.octether.**`（用 `Replace-InFile` 或手动 edit）。

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。若失败，`rg -n "ocremoteplus" app/src app/build.gradle.kts app/proguard-rules.pro` 定位遗漏并修复。

- [ ] **Step 7: 单测验证**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（与 Task 0 基线一致）。

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: rename package dev.leonardo.ocremoteplus -> dev.leonardo.octether"
```

---

## Task 2: flavor appLabel + 用户可见名替换 + About 页 URL/声明

**Files:**
- 修改：`app/build.gradle.kts:58,63`（flavor appLabel）
- 修改：16 语言 `app/src/main/res/values*/strings.xml`（`app_name`/`home_title`/`notification_inbox_title`/`home_local_auto_start_desc` + `about_github_url` 仓库 URL）
- 修改：`values/strings.xml` 与 `values-zh-rCN/strings.xml` 的 `about_unofficial`（独立 fork 声明）
- 修改：`app/src/main/kotlin/dev/leonardo/octether/OpenCodeApp.kt:55`
- 修改：`app/src/main/kotlin/dev/leonardo/octether/ui/screens/settings/DiagnosticsScreen.kt:124,147`

**注意：** 仅替换「OC Remote Plus」（本应用旧名）。注释/声明里指代上游的「OC Remote」（无 Plus）保留。

- [ ] **Step 1: 修改 build.gradle.kts flavor appLabel**

- L58: `manifestPlaceholders["appLabel"] = "OC Remote Plus Dev"` → `"OC Tether Dev"`
- L63: `manifestPlaceholders["appLabel"] = "OC Remote Plus Beta"` → `"OC Tether Beta"`

- [ ] **Step 2: 批量替换 16 语言 strings.xml（应用名 + 仓库 URL）**

```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
Get-ChildItem -Path app/src/main/res -Recurse -Filter strings.xml | ForEach-Object {
    $orig = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    $new = $orig.Replace('OC Remote Plus', 'OC Tether').Replace('LeoNardo-LB/oc-remote-plus', 'LeoNardo-LB/oc-tether')
    if ($orig -ne $new) {
        [System.IO.File]::WriteAllText($_.FullName, $new, $utf8NoBom)
        Write-Output "  updated: $($_.FullName)"
    }
}
```
> 同时替换应用名（OC Remote Plus→OC Tether）和 About 页仓库 URL（16 语言 `about_github_url` 全覆盖）。

- [ ] **Step 3: 改写 about_unofficial 为独立 fork 声明**

`values/strings.xml`（英文默认，应用 fallback）`about_unofficial`：
```xml
<string name="about_unofficial">An independent community fork of OC Remote. Not affiliated with, endorsed by, or sponsored by the original OC Remote project, its author (@crim50n), or the OpenCode team.</string>
```

`values-zh-rCN/strings.xml` `about_unofficial`：
```xml
<string name="about_unofficial">OC Remote 的独立社区 fork，不隶属于原 OC Remote 项目、其作者 @crim50n 或 OpenCode 团队。</string>
```

> 其余 14 语言 `about_unofficial` 保留现有翻译（仍传达 unofficial），URL 已在 Step 2 全改；后续可用 lokit 同步完整 fork 声明。

- [ ] **Step 4: 替换 OpenCodeApp.kt 启动日志**

`OpenCodeApp.kt:55`：`"OC Remote Plus ${BuildConfig.VERSION_NAME} ..."` → `"OC Tether ${BuildConfig.VERSION_NAME} ..."`

- [ ] **Step 5: 替换 DiagnosticsScreen.kt**

- L124: `"OC Remote Plus diagnostics"` → `"OC Tether diagnostics"`
- L147: `putExtra(Intent.EXTRA_SUBJECT, "OC Remote Plus diagnostics")` → `"OC Tether diagnostics"`

- [ ] **Step 6: 验证用户可见名 + About URL 零残留**

Run: `rg -n "OC Remote Plus|LeoNardo-LB/oc-remote-plus" app/src app/build.gradle.kts`
Expected: 无输出（零残留）。

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: replace user-visible name -> OC Tether, update About URL + fork notice"
```

---

## Task 3: 更新检查器（仓库名 + 包名校验集合 + APK 名）

**Files:**
- 修改：`app/src/main/kotlin/dev/leonardo/octether/data/update/UpdateRepository.kt:41,42,43,155`
- 修改：`app/src/main/kotlin/dev/leonardo/octether/data/update/UpdateModels.kt:8,15,16,17,129`

**关键：** 仓库名改为 `oc-tether`（带横杠）；包名集合改为新变体 `octether`（无横杠）。

- [ ] **Step 1: UpdateModels.kt — 仓库名常量**

`UpdateModels.kt:8`：
```kotlin
private const val REPOSITORY_NAME = "oc-tether"
```

- [ ] **Step 2: UpdateModels.kt — 包名校验集合**

`UpdateModels.kt:14-18`：
```kotlin
private val FLAVOR_APPLICATION_IDS = setOf(
    "dev.leonardo.octether",
    "dev.leonardo.octether.beta",
    "dev.leonardo.octether.dev",
)
```

- [ ] **Step 3: UpdateModels.kt — APK 文件名**

`UpdateModels.kt:129`：
```kotlin
"${releaseTag(versionName)}/oc-tether-$versionName.apk"
```

- [ ] **Step 4: UpdateRepository.kt — 3 个 URL + APK 文件名**

- L41: `"https://github.com/LeoNardo-LB/oc-tether/releases/latest/download/update.json"`
- L42: `"https://raw.githubusercontent.com/LeoNardo-LB/oc-tether/master/update.json"`
- L43: `"https://api.github.com/repos/LeoNardo-LB/oc-tether/releases/latest"`
- L155: `File(updatesDir, "oc-tether-${release.versionName}.apk")`

- [ ] **Step 5: 编译 + 单测验证**

Run: `.\gradlew :app:compileDevDebugKotlin; .\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 编译通过；`UpdatePolicyTest` 全部通过。若 `UpdatePolicyTest` 因 URL 断言失败，检查测试内是否有硬编码旧 URL 需同步更新。

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "refactor: point update checker to oc-tether repo + new package ids"
```

---

## Task 4: maestro appId + CI + 版本重置

**Files:**
- 修改：所有 `maestro/*.yaml`（appId）
- 修改：`.github/workflows/release.yml:63,72`
- 修改：`version.properties`

- [ ] **Step 1: 替换 maestro appId**

```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
Get-ChildItem -Path maestro -Recurse -Filter *.yaml | ForEach-Object {
    $orig = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    $new = $orig.Replace('dev.leonardo.ocremoteplus.dev', 'dev.leonardo.octether.dev')
    if ($orig -ne $new) {
        [System.IO.File]::WriteAllText($_.FullName, $new, $utf8NoBom)
        Write-Output "  updated: $($_.Name)"
    }
}
```

- [ ] **Step 2: 修改 CI release.yml**

- L63: `cp "$APK" release-apks/oc-tether-${VERSION}.apk`
- L72: `name: octether-release-${{ steps.version.outputs.name }}`

- [ ] **Step 3: 版本重置**

`version.properties` 全文：
```properties
VERSION_CODE=1
VERSION_NAME=1.0.0
```

- [ ] **Step 4: 验证 maestro + CI 零残留**

Run: `rg -n "ocremoteplus|oc-remote-plus" maestro/ .github/`
Expected: 无输出。

- [ ] **Step 5: 编译验证（确认版本重置不破坏构建）**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "chore: fix maestro appId, CI artifact names, reset version to 1.0.0"
```

---

## Task 5: 文档全量替换

**Files:**
- 修改：`README.md`、`AGENTS.md`、`backlog.md`
- 修改：`docs/**` 全部（含 `docs/plans/`、`docs/research/`、`docs/superpowers/`、`docs/test-handbook-*.md` 等历史记录）

**规则：**
- `OC Remote Plus`（本应用旧名）→ `OC Tether`
- `oc-remote-plus`（仓库名）→ `oc-tether`
- `ocremoteplus`（包名片段）→ `octether`
- `dev.leonardo.ocremoteplus` → `dev.leonardo.octether`
- **保留**：`OC Remote`（无 Plus，指代上游）、`crim50n/oc-remote`（上游仓库）、LICENSE 里的 Copyright

- [ ] **Step 1: 批量替换活跃文档 + 历史文档**

先处理 `OC Remote Plus` → `OC Tether`（最安全，独特 token）：
```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$docs = @('README.md','AGENTS.md','backlog.md') + (Get-ChildItem -Path docs -Recurse -File | Where-Object { $_.Extension -in '.md','.txt','.yaml','.yml' } | Select-Object -ExpandProperty FullName)
foreach ($f in $docs) {
    if (-not (Test-Path $f)) { continue }
    $orig = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
    $new = $orig.Replace('OC Remote Plus', 'OC Tether').Replace('oc-remote-plus', 'oc-tether').Replace('ocremoteplus', 'octether')
    if ($orig -ne $new) {
        [System.IO.File]::WriteAllText($f, $new, $utf8NoBom)
        Write-Output "  updated: $f"
    }
}
```

- [ ] **Step 2: 更新 README 的 unofficial 声明为 fork 声明**

`README.md` 现有声明：
```
> **This is an unofficial community project, not affiliated with the OpenCode team.**
```
改为：
```
> **OC Tether is an independent community fork of OC Remote, not affiliated with, endorsed by, or sponsored by the original OC Remote project, its author (@crim50n), or the OpenCode team.**
```
> 声明对象从「OpenCode team」扩展到「OC Remote 原项目 + 作者 + OpenCode team」，满足 crim50n 诉求。AGENTS.md 若有类似 unofficial 声明同步处理。

- [ ] **Step 3: 验证文档零残留（在选定 token 上）**

Run:
```powershell
rg -n "OC Remote Plus|oc-remote-plus|ocremoteplus" README.md AGENTS.md backlog.md docs/
```
Expected: 无输出。

- [ ] **Step 4: 人工抽查上游引用未被误伤**

Run: `rg -n "crim50n/oc-remote|OC Remote[^ ]" README.md AGENTS.md`
Expected: 仍有匹配（这些是指代上游，应保留）。确认无误伤。

- [ ] **Step 5: Commit**

```powershell
git add -A
git commit -m "docs: rename all references -> OC Tether, update fork notice"
```

---

## Task 6: 图标制作与替换

**Files:**
- 替换：`app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`（5 密度）
- 替换：`app/src/main/res/mipmap-{...}dpi/ic_launcher_round.png`（5 密度）
- 核对：通知栏小图标（`ic_notification` 或 `ic_stat_*`，按现有资源定）

**概念：** 连接 + 代码 —— 连接线/绳结合 `</>` 或 `>_` 终端符，传达「编程工具 + 远程连接」双重身份。配色用新品牌色，与上游拉开。

- [ ] **Step 1: 确认现有图标资源清单**

Run: `Get-ChildItem -Path app/src/main/res -Recurse -Filter 'ic_launcher*' | Select-Object FullName`
记录所有需替换的图标文件路径 + 密度尺寸（mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192）。

- [ ] **Step 2: 生成新图标素材**

按「连接 + 代码」概念，生成全套密度 PNG（含 round 变体）。可用 Android Studio Image Asset Studio、在线工具（如 romanust.net、easyappicon）、或 AI 图像生成。颜色与 `ui/theme/Color.kt` 品牌色协调但区别于上游。

- [ ] **Step 3: 放置图标文件**

用生成的新图覆盖 `app/src/main/res/mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png`。

- [ ] **Step 4: 核对通知栏图标**

Run: `rg -n "ic_notification|ic_stat|setSmallIcon" app/src/main`
确认通知小图标是否需同步更新（通知图标应为单色透明 PNG/矢量）。

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL，APK 内图标为新版。

- [ ] **Step 6: 视觉验证（派视觉子代理或人工）**

安装到模拟器，确认 launcher 图标为全新设计，与上游不同；通知栏图标正确显示。

- [ ] **Step 7: Commit**

```powershell
git add -A
git commit -m "feat: new OC Tether app icon (connect + code concept)"
```

---

## Task 7: 最终全量验证 + GitHub 仓库名改名

**Files:** 无代码修改（验证为主）+ GitHub 网页操作。

- [ ] **Step 1: 全量零残留验证**

Run:
```powershell
# 包名残留（应为 0）
rg -ci "ocremoteplus" app/src app/build.gradle.kts app/proguard-rules.pro
# 用户可见名残留（应为 0）
rg -ci "OC Remote Plus" app/src README.md AGENTS.md backlog.md
# 仓库名残留（应为 0）
rg -ci "oc-remote-plus" app/src maestro/ .github/ docs/
```
Expected: 三项均为 0 输出。任一非 0 则回对应 Task 修补。

- [ ] **Step 2: 完整编译 + 单测**

Run: `.\gradlew --stop; .\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过。

- [ ] **Step 3: 完整构建验证**

Run: `.\gradlew :app:assembleDevRelease`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: APK 标识验证**

解包检查：
```powershell
# 查看 APK 内 applicationId（用 aapt 或 unzip AndroidManifest）
.\gradlew :app:processDevReleaseManifest  # 或用 aapt dump badging
```
确认 `applicationId='dev.leonardo.octether'`（dev flavor 加 `.dev` 后缀）、`app_name` 显示 OC Tether。

- [ ] **Step 5: GitHub 仓库名改名（手动）**

GitHub → `LeoNardo-LB/oc-remote-plus` → Settings → Repository name → `oc-tether` → Rename。旧 URL 自动重定向。

- [ ] **Step 6: 更新本地 remote**

```powershell
git remote set-url origin https://github.com/LeoNardo-LB/oc-tether.git
git remote -v  # 确认
```

- [ ] **Step 7: 推送 + 打 1.0.0 tag**

```powershell
git push origin master
git tag -a "v1.0.0" -m "v1.0.0 — OC Tether: independent fork of OC Remote"
git push origin "v1.0.0"
```

- [ ] **Step 8: 最终 commit（如有 remote 配置改动）**

```powershell
git add -A
git commit -m "chore: finalize OC Tether rename" --allow-empty
```

---

## Self-Review 结论

**1. Spec 覆盖：** spec 第 4 节全部范围（4.1-4.10）已映射到 Task 1-7：
- 4.1 包路径 → Task 1
- 4.2 用户可见名 → Task 2
- 4.3 proguard → Task 1
- 4.4 版本重置 → Task 4
- 4.5 更新检查器 → Task 3
- 4.6 maestro → Task 4
- 4.7 图标 → Task 6
- 4.8 文档 → Task 5
- 4.9 CI → Task 4
- 4.10 仓库名 → Task 7
- 声明措辞（spec 3.3）→ 需补：About 页声明文字应在 Task 2 或单独处理。

**2. 缺口补丁（已修正）：** spec 3.3 的 About 页独立 fork 声明 + About 仓库 URL + README fork 声明已补入 Task 2 Step 2（URL）、Step 3（about_unofficial 改写英文+中文）、Task 5 Step 2（README 声明）。About 页通过 `R.string.about_unofficial` / `R.string.about_github_url` 引用 strings.xml，无需改 AboutScreen.kt 代码。

**3. Placeholder 扫描：** 无 TBD/TODO；所有字符串、文件、行号均为实际值。Task 6 图标生成依赖外部工具，但步骤给出了具体方法与验证，非占位。

**4. 一致性：** 包名 `dev.leonardo.octether`（无横杠）、仓库名 `oc-tether`（有横杠）贯穿全计划一致。
