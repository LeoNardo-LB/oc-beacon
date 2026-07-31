# OC Tether 改名设计（Spec）

> 日期：2026-07-31
> 状态：待批准 → 待 writing-plans
> 触发：[crim50n/oc-remote#17](https://github.com/crim50n/oc-remote/issues/17) 上游作者要求衍生项目建立独立品牌身份

## 1. 背景与动机

上游 `crim50n/oc-remote` 作者在 issue #17 中明确提出：当前 fork 虽已改了 `applicationId`，但应用本身仍以「OC Remote」之名（含「OC Remote Plus」）面向用户，且沿用相同图标，会让人误以为是**官方增强/高级版**。他要求：

1. 选择一个**清晰独立的应用名**；
2. 使用**独立的图标和视觉**；
3. 在应用内/APK/Release/截图/文档中**一致**地使用新名；
4. About 页声明这是**独立维护的 fork**，不隶属原项目；
5. 欢迎提「基于 OC Remote fork」，但「OC Remote」不能作为衍生应用的主身份。

本 spec 定义从「OC Remote Plus」彻底更名为「OC Tether」的完整设计。

## 2. 决策摘要

| 维度 | 旧 | 新 |
|------|-----|-----|
| 应用名（用户可见） | OC Remote Plus | **OC Tether** |
| applicationId | `dev.leonardo.ocremoteplus` | **`dev.leonardo.octether`** |
| Kotlin 包路径 | `dev.leonardo.ocremoteplus` | **`dev.leonardo.octether`** |
| Flavor 后缀 | `.dev` / `.beta` | 不变 |
| 仓库名 | `oc-remote-plus` | **`oc-tether`** |
| 版本 | `1.0.7-beta.2` (code 104) | **`1.0.0`** (code 1，重置) |
| 图标 | 沿用上游 | **全新：连接+代码概念** |
| About 声明 | 无 | **独立 fork 声明** |

## 3. 新身份定义

### 3.1 应用名
**OC Tether**

### 3.2 定位（副标题/描述）
OC Remote 的独立社区 fork · OpenCode 服务器的非官方 Android 客户端。

> 命名理由：「tether」在移动语境即「设备绑定/连接共享」，一词同时覆盖「移动端」与「远程连接」；与上游「Remote」语义彻底区分；GitHub/Web 全网零同名。

### 3.3 独立 fork 声明（About 页 + README，crim50n 硬要求）

> **EN**: OC Tether is an independently maintained community fork of OC Remote. It is not affiliated with, endorsed by, or sponsored by the original OC Remote project or its author (@crim50n).
>
> **中文**: OC Tether 是 OC Remote 的独立维护社区 fork，不隶属于原 OC Remote 项目或其作者 @crim50n，也未获其认可或背书。

### 3.4 图标/视觉概念
**连接 + 代码**：连接线/绳 + `</>` 或 `>_` 终端符，一眼传达「编程工具 + 远程连接」双重身份。配色用新品牌色，与上游拉开。需新做全套：5 密度 `mipmap`（`ic_launcher` + `ic_launcher_round`）+ 通知栏小图标 + Play Store 特色图。

## 4. 改名范围（全量清单，基于 2026-07-31 扫描）

### 4.1 包路径与 applicationId（核心）
- **目录重命名**（4 个 source set）：
  - `app/src/main/kotlin/dev/leonardo/ocremoteplus` → `octether`
  - `app/src/test/kotlin/dev/leonardo/ocremoteplus` → `octether`
  - `app/src/androidTest/kotlin/dev/leonardo/ocremoteplus` → `octether`
  - `app/src/debug/kotlin/dev/leonardo/ocremoteplus` → `octether`
- **所有 .kt 文件**：`package` 声明 + `import` 语句全局替换（~50+ 文件，数百处，机械替换）
- **`app/build.gradle.kts`**：`namespace` 与 `applicationId` 改为 `dev.leonardo.octether`
- **`app/proguard-rules.pro`**：3 处 `dev.leonardo.ocremoteplus.**` keep 规则

### 4.2 用户可见文本 "OC Remote Plus"（21 文件）
- 16 语言 `strings.xml`：`app_name` / `home_title` / `notification_inbox_title` / `home_local_auto_start_desc`
- `README.md` / `AGENTS.md` / `backlog.md`
- `app/build.gradle.kts`（注释/resValue）
- `OpenCodeApp.kt`（启动日志）、`DiagnosticsScreen.kt`（诊断导出/邮件主题）、`ContextStats.kt`（注释）

### 4.3 proguard-rules.pro
3 处 keep 规则同步包名。

### 4.4 版本重置
`version.properties`：
```
VERSION_CODE=1
VERSION_NAME=1.0.0
```

### 4.5 更新检查器 repo 名（关键，否则应用内更新失效）
- `data/update/UpdateRepository.kt` / `UpdateModels.kt` / `test/.../UpdatePolicyTest.kt`
- 硬编码的 GitHub repo 路径 `oc-remote-plus` → `oc-tether`

### 4.6 maestro 测试 appId（顺便修复已存在 bug）
所有 `maestro/*.yaml` 的 `appId: dev.leonardo.li.dev` → `dev.leonardo.octether.dev`
> 现状异常：maestro appId 与当前 applicationId 本就不一致，改名时统一修正。

### 4.7 图标与视觉
见 3.4。全套替换。

### 4.8 文档（全部，含历史记录）
- 活跃文档：`AGENTS.md` / `README.md` / `backlog.md` 必改
- 历史文档：`docs/plans/*` / `docs/research/*` / `docs/superpowers/plans/*` / `docs/test-handbook-*.md` 等带日期记录——**全部改**（决策：保证零残留）

### 4.9 CI / Release
- `.github/workflows/release.yml`：核对 release 命名、artifact 路径是否含旧名

### 4.10 GitHub 仓库名
`LeoNardo-LB/oc-remote-plus` → `LeoNardo-LB/oc-tether`
> 仓库名用带横杠的 `oc-tether`（GitHub 惯例、可读）；包名 `dev.leonardo.octether` 不带横杠（Java/Kotlin 包名规范不允许横杠）。两者刻意区分。
> GitHub 改名后旧 URL 自动重定向，不断链。

## 5. 撞名确认结论（2026-07-31）

| 范围 | 结果 |
|------|------|
| GitHub 仓库 `oc-tether` 精确名 | 零匹配（结果均为 OCR/海洋/挖矿等无关项目） |
| GitHub 代码全文 `"OC Tether"` | 零结果 |
| GitHub 仓库 `octether` | 零结果 |
| Web 搜索 "OC Tether" app | 无同名产品（仅 USDT 钱包等「Tether」联想，非重名） |

结论：**OpenCode 生态内完全无撞名**。「Tether」一词在 app 生态有 USDT 认知联想，但非重名；本项目以 GitHub Release 分发为主，靠编程主题图标 + OpenCode 语境中和。

## 6. 不改的部分（法律要求）

- **LICENSE**：保留 crim50n 的 `Copyright (c) 2026 crims0n` 版权声明（MIT 强制要求）。可保留/追加 fork 作者的 Copyright 行，**不得删除原作者声明**。
- 上游 `upstream` remote 关系、git 历史完整保留。

## 7. 风险与代价

| 风险 | 影响 | 处理 |
|------|------|------|
| **现有用户无法覆盖升级** | applicationId 改变 → Android 视为全新 app，旧版无法覆盖安装 | 已知代价，用户确认接受。版本重置 1.0.0 配合全新身份 |
| **用户数据不迁移** | 旧版的 SharedPreferences / 数据库 / 登录信息不会带到新 app | 待定：是否需要启动时检测旧包数据并导入（writing-plans 阶段决策） |
| **USDT 认知联想** | 第一眼可能联想到加密货币 | 编程主题图标 + OpenCode 语境中和 |
| **应用内更新失效** | 若漏改 UpdateRepository 的 repo 名 | 已纳入范围 4.5 |

## 8. 验证标准（强验证）

实施完成后须全部通过：

1. **编译**：`./gradlew :app:compileDevDebugKotlin` 通过
2. **单测**：`./gradlew :app:testDevDebugUnitTest --rerun` 通过
3. **零残留**（在选定范围内）：
   - `rg -i "ocremoteplus"` 在 `app/src`、`*.gradle.kts`、`*.pro` → 零结果
   - `rg -i "OC Remote Plus"` 在 `app/src`、`README.md`、`AGENTS.md` → 零结果
   - `rg -i "oc-remote-plus"` 在 `app/src`、`maestro/`、更新检查器 → 零结果
   - 历史文档内残留同样清零
4. **APK 标识**：解包后 `applicationId` 为 `dev.leonardo.octether`
5. **运行时**：应用名显示为「OC Tether」；启动日志、诊断导出、通知栏标题均为新名
6. **图标**：launcher 图标为全新设计，与上游不同
7. **更新检查器**：About → 检查更新指向新仓库名 `oc-tether`
8. **About 页**：显示独立 fork 声明

## 9. 后续

本 spec 获用户批准后，转入 **writing-plans** 生成详细实施计划（分阶段：包路径重构 → 文本替换 → 图标 → 版本/仓库 → CI → 验证）。

实施遵循 AGENTS.md 的 ChatScreen 编辑协议、Gradle 超时规范、版本管理规范。
