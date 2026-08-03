# AGENTS.md — OC Beacon

Unofficial OpenCode Android client. Jetpack Compose + Kotlin + Hilt + Ktor.

## Build & Run

**默认只打一个包**：每次构建/发版只需运行**对应 flavor 的单个 assemble 任务**，产出该 flavor 的一个 APK（按 flavor 分目录输出到 `app/build/outputs/apk/<flavor>/<buildType>/`）。多任务命令仅用于需要同时产出多个包的场景。

```bash
# 常用：单个任务，产出对应 flavor 的一个 APK
.\gradlew :app:assembleDevDebug        # 开发调试（dev flavor）
.\gradlew :app:assembleBetaRelease     # beta 发版
.\gradlew :app:assembleStableRelease   # 正式发版

# 多任务示例：需要同时产出多个包时才用（如 dev debug + beta release）
.\gradlew :app:assembleDevDebug :app:assembleBetaRelease

# 单元测试（强制重跑，避免 UP-TO-DATE 跳过）
.\gradlew :app:testDevDebugUnitTest --rerun

# Kotlin 编译检查（快速反馈）
.\gradlew :app:compileDevDebugKotlin
```

**要求 JDK 21** — `build.gradle.kts` 设置了 `jvmToolchain(21)` 和 `JavaVersion.VERSION_21`。本地构建还在 `gradle.properties` 中设置了 `org.gradle.java.home`。

**代理警告**：`gradle.properties` 硬编码了 `127.0.0.1:7897` 的 HTTP 代理。代理不可达时构建会失败。无代理构建时注释掉 4 行 `systemProp.*` 配置。

## Product Flavors

三 flavor 体系，三个包可同时安装共存：

| Flavor | applicationId | 应用名 | 用途 |
|--------|---------------|--------|------|
| `dev` | `dev.leonardo.ocbeacon.dev` | OC Beacon Dev | 开发预览（worktree 构建） |
| `beta` | `dev.leonardo.ocbeacon.beta` | OC Beacon Beta | 公开测试版 |
| `stable` | `dev.leonardo.ocbeacon` | OC Beacon | 正式发布 |

所有 Gradle 任务都必须指定 flavor：`assembleDevRelease`、`assembleBetaRelease`、`assembleStableRelease` 等。

## Architecture

Clean Architecture, 3 layers. **Dependency direction: UI → Domain ← Data.**

```
domain/          Pure Kotlin, 无 Android 依赖
  model/         40+ 数据类与值类型（SseEvent, Message, Part, Session, AppSettings, SessionCategory, FavoriteSessionSnapshot 等）
  repository/    15 个接口（Chat, Session, Server, Settings, File, Vcs, Terminal, Provider, Draft, Agent, Mcp, LocalServer 等）
  usecase/       30 个 UseCase — ViewModel 调用它们，而非直接调 API

data/            Android 相关实现
  api/           OpenCodeApi.kt (Ktor HTTP), SseClient.kt, ServerConnection.kt
  dto/           API 数据传输对象（request/ response/ common/）
  mapper/        DTO ↔ 领域模型转换器
  repository/    Impl 类 + EventDispatcher + EventHandler 策略模式
    handler/     10 个事件处理器（Session, Message×4, SessionNext, Permission, Question, Misc）
                 + DiagnosticLogDatabase/Repository (SQLite, 自动清理, 隐私脱敏)
                 + PendingPromptRepository（基于文件的 JSON, 乐观消息持久化）
                 + CrossServerSessionsAggregator（基于 REST 的按服务器会话聚合）
  update/        应用内 GitHub Release 更新检查（UpdateRepository, 3 级回退）

logging/         AppLogger — 全局持久化日志（Channel→SQLite, 崩溃捕获, 脱敏）

service/         Android 前台服务
  OpenCodeConnectionService.kt  服务生命周期 + WakeLock
  SseConnectionManager.kt       连接/重连（指数退避）
  AppNotificationManager.kt     通知渠道与事件通知
  SessionNotificationCoordinator.kt  抑制当前活跃会话的通知

ui/
  theme/              设计令牌系统
    Alpha.kt          7 级语义透明度令牌 (SELECTED/DIFF_BG/FAINT/MUTED/MEDIUM/HIGH/AMOLED)
    Color.kt          品牌色常量 + 语义化 DiffAdded/DiffRemoved
    Motion.kt         时长令牌 + 缓动常量
    Shape.kt          AppShapes (Material) + ShapeTokens（组件级）
    Theme.kt          4 套配色方案 (light/dark/dynamic/amoled), AppTheme composable
    Type.kt           排版配置
  screens/chat/      ChatScreen（核心聊天 UI）+ 7 个子包
    components/      聊天 UI 组件
    dialog/          图片预览、markdown 预览对话框
    input/           消息输入栏
    markdown/        Markdown 渲染
    terminal/        WebSocket 上的 PTY 终端视图（TerminalTabState: 5 态枚举，非 Boolean）
    tools/           工具调用可展开卡片
    util/            聊天专用工具
  screens/home/      HomeScreen + 服务器卡片 + 本地运行时
  screens/sessions/  SessionListScreen + CrossServerSessionsScreen + 组件
  screens/settings/  SettingsScreen + 选择器对话框 + DiagnosticsScreen
  screens/server/    服务器设置/提供商/模型过滤
  screens/about/     关于页面
  screens/webview/   WebView 回退（OAuth, HTML 错误）
  navigation/        NavGraph.kt + routes/ 中的 12 个类型安全 Route 对象（URL 参数用 NavUtils.safeDecodeParam）
  components/        共享组件（PulsingDotsIndicator, ProviderIcon）

di/                Hilt 模块（NetworkModule, DomainModule）
```

**关键模式**：
- ViewModel 委托给 UseCase；UseCase 目前壳式委托给 OpenCodeApi。
- Repository 实现桥接 EventDispatcher（状态）+ API（网络）。
- DI 使用 **KSP**（非 kapt）处理 Hilt 注解。
- 终端用 WebSocket 传输 PTY 流；事件走 SSE。
- **SessionStateService 是会话状态与流式活动的单一真相源**（idle/busy/retry + Waiting/Streaming/ToolCalling）。所有 UI 读取 `statusFlow`/`activityFlow`；所有状态写入都经过其纯函数 FSM（`SessionStateFSM`），含穷举转移矩阵 + 自驱动 staleness/REST 恢复循环。**不要重新引入按 handler 维护的状态**——`SessionStatusManager` 和 `SessionEventHandler._sessionStatuses` 正是为此被移除。设计缘由见 `docs/research/session-status-sync-investigation.md`。
- **AppLogger**（`logging/AppLogger.kt`）是持久化日志入口——新代码应使用 `AppLogger.i/w/e` 而非 `android.util.Log`，这样日志会出现在应用内 Diagnostics 屏幕。存量代码正在逐步迁移。
- **导航参数**必须使用 `NavUtils.safeDecodeParam()`（不要用裸 `URLDecoder.decode()`）——裸解码遇到畸形 `%` 序列（如密码中的 `%NR`）会崩溃。

## OpenCode Server API Reference

完整接口文档见 [`docs/opencode-api-reference.md`](docs/opencode-api-reference.md)。

涵盖 62 个 REST/WebSocket 端点 + 52 种 SSE 事件类型，包括：
- Session / Message / Permission / Question 的 CRUD 与操作接口
- Provider / Auth / Config 配置接口
- PTY 终端（WebSocket）、File / Find 文件操作接口
- SSE 事件体系（含 22 种 `session.next.*` 细粒度事件）
- 所有数据模型的完整 JSON Schema
- Token / Context Usage 的语义说明和推荐计算方式

**开发新功能或调试接口问题时，务必先查阅此文档。**

## 关键约束

### ChatScreen.kt 编辑协议
见 `docs/chatscreen-editing-protocol.md`。规则：
- 禁止跨 agent 并行编辑
- 每次编辑前必须先 Read
- 每次编辑后运行 `compileDevDebugKotlin`
- 每次编译成功后提交
- 失败时：`git checkout -- <file>`，重新读取，重试

### 路径处理（跨平台远程路径）

远程文件路径可能使用 `/` 或 `\`，取决于服务器操作系统。**始终使用 `PathUtils`**（`util/PathUtils.kt`）：

| 操作 | ✅ 使用 | ❌ 不要用 |
|-----------|--------|---------|
| 文件名 | `PathUtils.fileName(path)` | `substringAfterLast('/')`, `File(path).name` |
| 父目录 | `PathUtils.parentDir(path)` | `substringBeforeLast('/')` |
| 相对路径 | `PathUtils.relativePath(path, prefix)` | 手动 `removePrefix` |

JDK API（`File.name`、`Path.of`）在 Android 上只识别 `/`——来自 Windows 服务器的 `\` 路径会出错。
### 签名
- Release keystore 位于 `app/keystore/release.jks`，密码在 `signing.properties` 中
- `signing.properties` 存在时 → release 构建使用 release keystore
- 不存在时 → release 构建回退到 debug 签名（见 `build.gradle.kts` 的 `signingConfigs` 块）
- CI 使用 GitHub Secrets（`KEYSTORE_BASE64`、`KEYSTORE_ALIAS`、`KEYSTORE_PASSWORD`）
- Debug 签名的 APK 可安装，但无法覆盖 release 签名安装（签名不同）

### Version Management

遵循 [Semantic Versioning 2.0.0](https://semver.org/) 规范，适配 Android 双 flavor 场景。

#### 版本号格式

```
MAJOR.MINOR.PATCH[-LABEL.NUMBER]
```

| 字段 | 含义 | 递进条件 |
|------|------|---------|
| MAJOR | 大版本 | 不兼容的架构变更、完整重写、品牌重塑 |
| MINOR | 功能版本 | 新功能、新屏幕、新 API 对接（向下兼容） |
| PATCH | 修复版本 | Bug 修复、性能优化、UI 调整（向下兼容） |
| LABEL | 预发布标签 | `beta`（公开测试）或 `dev`（开发预览） |
| NUMBER | 预发布序号 | 同一版本的第 N 次预发布，从 1 开始 |

#### 版本号示例

```
1.0.0              ← 正式稳定版
1.0.1-beta.1       ← 1.0.1 的第一个 beta 测试版
1.0.1-beta.2       ← 1.0.1 的第二个 beta（修复测试反馈）
1.0.1              ← 1.0.1 正式版（beta 结束后发布）
1.1.0-beta.1       ← 1.1.0 新功能 beta
1.1.0-dev.3        ← 1.1.0 的第三个开发预览（worktree 构建）
```

#### 单一真相源

- **`version.properties`** 位于项目根目录（唯一来源）:
  ```properties
  VERSION_CODE=1
  VERSION_NAME=1.0.0
  ```
- `VERSION_CODE`：整数，**永远只增不减**，每次构建 +1。Android 用此判断更新顺序。
- `VERSION_NAME`：显示字符串，遵循上述 SemVer 格式。
- `app/build.gradle.kts` 从 `version.properties` 读取 — 禁止在 build.gradle.kts 中硬编码版本号。
- CI 通过 grep `version.properties` 提取版本 — **不要改变文件格式**。

#### Git Tag 格式

Tag = `v` + VERSION_NAME：
- `v1.0.0` — 正式版
- `v1.0.1-beta.1` — beta 预发布
- `v1.1.0-dev.3` — dev 预览

#### 发版规则速查

| 类型 | 分支 | Flavor | 版本号示例 | Tag | GitHub Release |
|------|------|--------|-----------|-----|----------------|
| 正式版 | master | `assembleStableRelease` | `1.0.1` | `v1.0.1` | `gh release create`（正式） |
| Beta | master | `assembleBetaRelease` | `1.0.1-beta.1` | `v1.0.1-beta.1` | `--prerelease` |
| Dev | worktree | `assembleDevRelease` | `1.0.1-dev.1` | `v1.0.1-dev.1` | `--prerelease` |

- **dev flavor** (`dev.leonardo.ocbeacon.dev`)：开发预览，独立 applicationId，可与正式版共存。
- **beta flavor** (`dev.leonardo.ocbeacon.beta`)：公开测试版，独立 applicationId，可与正式版共存。
- **stable flavor** (`dev.leonardo.ocbeacon`)：正式包名，覆盖安装。
- **只发一个包**：每次发版只创建一个 GitHub Release，不重复发多个。发新版前**先删除旧版 Release 和 Tag**（`gh release delete <old> --yes && git push origin --delete <old>`），确保 Releases 页面只保留最新版本。
- **默认发预发布版**：除非用户明确说明"正式发版"或"发 stable"，否则一律发 beta 或 dev 预发布版（`--prerelease`）。
- `gh` CLI 不走代理，直接用直连（不加 `HTTP_PROXY`）。
- APK 路径：
  - stable → `app/build/outputs/apk/stable/release/app-stable-release.apk`
  - beta → `app/build/outputs/apk/beta/release/app-beta-release.apk`
  - dev → `app/build/outputs/apk/dev/release/app-dev-release.apk`。

#### 完整发版步骤

**步骤顺序（严禁颠倒 bump 和 build）：**

```
1. bump version → 修改 version.properties
2. commit → git commit -m "chore: bump version to vX.Y.Z"
3. build → .\gradlew --stop && .\gradlew :app:assembleBetaRelease
4. push → git push origin master
5. tag → git tag -a "vX.Y.Z" -m "vX.Y.Z — 简要说明"
6. push tag → git push origin "vX.Y.Z"
7. release → gh release create "vX.Y.Z" "APK路径" --prerelease(可选) --title --notes
```

**严禁在 `version.properties` 修改前执行 `assemble*`**，否则 APK 内嵌的版本号与 tag/release 名称不一致。

### Gradle Timeout
执行 Gradle 命令时必须设置合理的超时时间，禁止无超时裸跑：
- **Kotlin 编译检查**（`compileDevDebugKotlin`）: 120 秒
- **单元测试**（`testDevDebugUnitTest`）: 180 秒
- **完整构建**（`assembleDevRelease` 等）: 300 秒
- **依赖解析/首次构建**: 可延长至 600 秒

**Windows Daemon 卡住问题：**
Gradle Daemon 在 Windows 上间歇性不释放 stdout 管道，导致命令行工具看到 `BUILD SUCCESSFUL` 输出后永不返回。已在 `gradle.properties` 中设置 `org.gradle.daemon=false` 禁用 daemon。如遇到卡住，额外执行 `.\gradlew --stop` 清理残留 daemon。

**注意：** `--no-daemon` 和 `org.gradle.daemon=false` 效果相同——都会 fork 一次性进程，构建结束自动销毁。

### 验证与测试
**完整 4 维验证框架见 `docs/verification-requirements.md`。

**任何完成声明前必须加载 `verification-before-completion` skill**。铁律：没有新鲜的验证证据就不能声称完成。

测试基础设施：
- 单元测试：JUnit 4 + MockK + Turbine + kotlinx-coroutines-test（具体版本以 `app/build.gradle.kts` 为准）
- 插桩测试：`HiltTestRunner` + `createComposeRule()`（位于 `androidTest/`）
- E2E 流程：`maestro/` 目录下的 Maestro YAML
- `isReturnDefaultValues = true` — mock 返回默认值而非抛异常。这可能掩盖 bug（mock 数据静默返回 null/0/false）
- 每个层级要求：编译 ✅ + 单元测试 ✅ + 增强测试 ✅ + Maestro 流程（UI）+ androidTest（UI）

环境：
- opencode server 端口：4096
- opencode 用户名：opencode
- opencode 密码：保存为环境变量 ${OPENCODE_SERVER_PASSWORD}
- 模拟器访问宿主机：用 `10.0.2.2` 到达宿主机的 Android 模拟器
- **模拟器调试应使用 subagent 执行**：UI 交互（tap/input/scroll）、截图、logcat 读取等操作上下文占用大，派给 `task` subagent 处理可避免主会话上下文溢出。主 Agent 只下发测试指令、接收结果摘要。

### SSE 滚动稳定性

SSE → UI 管线为：**48ms token 批处理** → **高度补偿** → **渲染**。违反任何一条都会重新引入闪烁、卡顿输出或视口跳底：

- **`Markdown()` 必须使用 `rememberMarkdownState(content, retainState=true)`** — 无状态 `Markdown(content=...)` 每次重组都重新解析 → 高度振荡 → 闪烁。
- **`scheduleFlush()` 不得取消进行中的定时器** — 每个 token 都取消会在速率 > 20/s 时饿死 flush → 突发式卡顿输出。
- **`layout{}` 补偿只应用于流式消息**（`if (isStreamingMsg)`）— 应用到所有 assistant 消息会让已完结消息暴露在不稳定测量下。
- **autoScroll/shouldCompensate 的 `LaunchedEffect` 必须以 `isScrollInProgress` 和 `isAtBottom` 两者作为 key** — `isAtBottom` 作为 key 是自愈机制：用户通过非拖动方式（fling 惯性、SSE 内容推送）回到底部时重置 `shouldCompensate=false` / `autoScrollEnabled=true`。只以 `isScrollInProgress` 为 key 会让这些标志卡在陈旧状态 → 每个 SSE token 视口抖动。这是 beta.360 验证过的行为。**不要把 `isAtBottom` 从 key 中移除。** 完整回归历史见 `docs/research/sse-scroll-stability-iron-laws.md`。

### Ktor 引擎
明确使用 **OkHttp engine** 以正确支持 SSE 流式传输。不要切换到其他引擎。**

### Material 3 First
- **优先使用 Material 3 原生组件和原生样式**。能用 `LinearProgressIndicator`、`CircularProgressIndicator`、`IconButton` 等原生组件解决的，不要自定义 Canvas 绘制。
- **优先使用 Material 3 原生配色和动效**。颜色用 `MaterialTheme.colorScheme` 中的语义色，间距用 `dp` 常量或 Material token，不要硬编码。
- **仅在原生组件无法满足需求时才自定义**（如特殊动画效果），自定义组件也应尽量复用 Material token 系统。
- **禁止引入额外 UI 依赖库**（如 Accompanist），除非有充分的理由并经过讨论。

### Theme Token System
- **Alpha tokens** (Alpha.kt): 7 个语义透明度常量 — SELECTED(0.12) / DIFF_BG(0.10) / FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92). 用它们代替硬编码的 `.copy(alpha = Xf)`。
- **Spacing tokens** (Spacing.kt): 6 个网格常量 — XS(4) / SM(8) / MD(12) / LG(16) / XL(24) / XXL(32)。标准间距用 `SpacingTokens.LG.dp` 代替硬编码 `16.dp`。
- **Shape tokens** (Shape.kt): `AppShapes` 用于 MaterialTheme，`ShapeTokens` 对象用于组件级直接引用。
- **Motion tokens** (Motion.kt): 语义化时长常量（BREATH_CYCLE, PULSE_CYCLE, TERMINAL）。用它们代替硬编码的 `AnimationSpec` 时长。
- **Button tokens** (ButtonTokens.kt): 集中式按钮样式 — `filledColors()` / `dangerColors()` / `amoledBorder()` + `CompactPadding` / `StackSpacing` / `RowSpacing`。代替每次调用 `ButtonDefaults.colors` 和临时的 border 规格。导入：`dev.leonardo.ocbeacon.ui.theme.ButtonTokens`。
- **ListItem tokens** (ListItemTokens.kt): Material 3 `ListItem` 内容 padding 的三种密度级别 — `ContentPaddingSmall` / `ContentPaddingMedium` / `ContentPaddingLarge`。代替 ListItem 内容上的硬编码 `padding`。
- **暗色主题**: 信任 Material3 `darkColorScheme()` 默认值。只在 Theme.kt 中覆盖 6 个品牌差异化 token。
- **Colors** (Color.kt): 品牌常量 + 语义化 `DiffAdded`/`DiffRemoved`。无死代码。

## 分支与远程仓库

| Remote | URL | 角色 |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-beacon` | Fork（有 push 权限，当前默认） |
| upstream | `github.com:crim50n/oc-remote` | Upstream（所有者: crim50n）— 需要时手动添加 |

- `master` — 稳定分支，与 upstream 同步
- 推送：`git push origin master` / `git push origin <tag>`

## 本地化

15 种语言通过 `lokit.yaml` 管理。编辑字符串资源后，运行 `lokit` 同步翻译。

## ProGuard_

Release 构建使用 R8 混淆。规则保留：
- `kotlinx.serialization` 注解类
- Ktor 协程内部实现
- Mikepenz Markdown 渲染器的状态/模型（异步解析）

## Android SDK

SDK 版本(`compileSdk` / `minSdk` / `targetSdk`)与 Compose BOM 等依赖版本均以 `app/build.gradle.kts` 的 `defaultConfig` 与 `dependencies` 块为单一真相源,不在此重复维护以避免漂移。需要时直接查阅该文件。
