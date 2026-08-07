# AGENTS.md — OC Beacon

Unofficial OpenCode Android client. Jetpack Compose + Kotlin + Hilt + Ktor.

## 文档索引（先查这里）

> ⚠️ **新增/修改任何规则前，先读 [`docs/agents-file-design.md`](docs/agents-file-design.md)**——它是本 AGENTS.md 的设计规范（基于论文与大厂调研：何时内联、何时外链、行数目标、写作规范）。新规则按该文档决策流程落地。

**级别说明**（RFC 2119 语义，详见 agents-file-design.md §3.5）：🔴 **MUST** = 该场景下先读再行动，跳过会出错/违规 · 🟡 **SHOULD** = 推荐，跳过需理解后果 · 🟢 **MAY** = 可选背景知识。MUST 数量受控（≤5-7 条），避免"所有规则都重要=都不重要"。

| 级别 | 文档 | 用途 | Use when |
|------|------|------|----------|
| 🔴 MUST | [`docs/release-workflow.md`](docs/release-workflow.md) | 发版唯一权威指南（版本规则/CHANGELOG/脚本用法） | 任何发版、bump、tag、Release 操作前（必读） |
| 🔴 MUST | [`docs/chatscreen-editing-protocol.md`](docs/chatscreen-editing-protocol.md) | ChatScreen.kt 编辑协议（见下方承重约束） | 编辑 ChatScreen.kt 前 |
| 🔴 MUST | [`docs/verification-requirements.md`](docs/verification-requirements.md) | 完整 4 维验证框架 | 完成开发、声称"完成"前 |
| 🟡 SHOULD | [`docs/opencode-api-reference.md`](docs/opencode-api-reference.md) | OpenCode Server 完整 API 参考（62 REST/WS 端点 + 52 SSE 事件 + JSON Schema） | 开发新功能、调试接口问题前 |
| 🟡 SHOULD | [`docs/architecture.md`](docs/architecture.md) | 架构分层、目录职责、关键模式、承重架构规则 | 理解/修改跨层结构、SessionStateService、日志、导航 |
| 🟡 SHOULD | [`docs/chat-ui-event-lifecycle.md`](docs/chat-ui-event-lifecycle.md) | 聊天 UI 事件生命周期：触摸传播、SSE 流式更新、消息状态机、竞态条件 | 修改 ChatScreen 内部机制、排查聊天交互竞态时 |
| 🟡 SHOULD | [`docs/ui-conventions.md`](docs/ui-conventions.md) | UI 约定：Material 3、Theme Tokens、表格渲染一致性 | 编写/修改 UI 组件、主题、颜色、间距 |
| 🟡 SHOULD | [`docs/agents-file-design.md`](docs/agents-file-design.md) | AGENTS.md 维护规范（本文件的设计依据） | 新增/修改 AGENTS.md 规则时 |
| 🟡 SHOULD | [`backlog.md`](backlog.md) | 待办需求/问题登记（P0-P2 优先级 + Tag + 状态流转） | 录入新条目前（避免重复）、开始新任务了解待办时 |
| 🟢 MAY | [`docs/architecture-debt.md`](docs/architecture-debt.md) | 已登记的技术债务与后续计划 | 接触相关模块时了解限制 |

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

**Gradle 超时（禁止无超时裸跑）**：
- 编译检查（`compileDevDebugKotlin`）: 120 秒 · 单元测试: 180 秒 · 完整构建（`assemble*`）: 300 秒 · 依赖解析/首次构建: 600 秒

**Windows Daemon 卡住**：已在 `gradle.properties` 设置 `org.gradle.daemon=false`（`--no-daemon` 等效）。如遇 `BUILD SUCCESSFUL` 后不返回，执行 `.\gradlew --stop` 清理。

## Product Flavors

三 flavor 体系，三个包可同时安装共存：

| Flavor | applicationId | 应用名 | 用途 |
|--------|---------------|--------|------|
| `dev` | `dev.leonardo.ocbeacon.dev` | OC Beacon Dev | 开发预览（worktree 构建） |
| `beta` | `dev.leonardo.ocbeacon.beta` | OC Beacon Beta | 公开测试版 |
| `stable` | `dev.leonardo.ocbeacon` | OC Beacon | 正式发布 |

所有 Gradle 任务都必须指定 flavor：`assembleDevRelease`、`assembleBetaRelease`、`assembleStableRelease` 等。

## 架构概览

Clean Architecture, 3 layers. **Dependency direction: UI → Domain ← Data.** 完整目录树与关键模式见 [`docs/architecture.md`](docs/architecture.md)。

承重架构规则（违反会引入回归，详见架构文档）：
- **SessionStateService 是会话状态与流式活动的单一真相源**（idle/busy/retry + Waiting/Streaming/ToolCalling）。所有 UI 读取 `statusFlow`/`activityFlow`；所有状态写入经过纯函数 FSM（`SessionStateFSM`）。**不要重新引入按 handler 维护的状态**（`SessionStatusManager` 曾被移除）。
- **新日志代码用 `AppLogger.i/w/e`**（`logging/AppLogger.kt`），不要用 `android.util.Log`（AppLogger 会出现在应用内 Diagnostics 屏幕）。
- **导航参数必须用 `NavUtils.safeDecodeParam()`**，不要裸 `URLDecoder.decode()`（畸形 `%` 序列如密码中的 `%NR` 会崩溃）。

## 关键约束

### ChatScreen.kt 编辑协议
见 [`docs/chatscreen-editing-protocol.md`](docs/chatscreen-editing-protocol.md)。规则：
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
- Release keystore 位于 `app/keystore/release.jks`，密码在 `app/keystore/signing.properties` 中
- `signing.properties` 存在时 → release 构建使用 release keystore；不存在时 → 回退 debug 签名（见 `build.gradle.kts`：`release` 块仅在 `!hasPropertiesFile` 时设 debug 签名——**禁止**无条件覆盖为 debug，否则 release keystore 永不生效）
- CI 使用 GitHub Secrets（`KEYSTORE_BASE64`、`KEYSTORE_ALIAS`、`KEYSTORE_PASSWORD`）。**Secrets 未配置时 CI 会回退 debug 签名**，且每次构建（全新 runner）生成不同 debug.keystore → 每次发版签名不同 → 用户升级报"已安装签名冲突的应用"。配置方法：`gh secret set KEYSTORE_BASE64 --body "$([Convert]::ToBase64String([IO.File]::ReadAllBytes('app/keystore/release.jks')))"`（alias/password 同理）
- 发版后必须用 `apksigner verify --print-certs` 验证产物签名为 `CN=OC Beacon, OU=Development, O=LeoNardo-LB, C=CN`（非 `CN=Android Debug`），见 `docs/release-workflow.md` §6
- **2026-08-06 keystore 更换**：release keystore 已重建（CN=OC Beacon，alias=oc-tether）。**1.2.0 起使用新签名**——1.1.1 及更早版本安装的用户升级 1.2.0 时**必须卸载重装一次**（签名不同，无法覆盖安装）
- **2026-08-06 版本体系重置**：VERSION_NAME 1.2.0 → **0.2.0**、VERSION_CODE 18 → **1**（未正式发版不配 1.x；用户明确接受卸载重装、不追求覆盖安装）。此后从 0.2.0 重新计数（0.2.0→0.3.0→…→1.0.0）
- **2026-08-07 版本体系再重置**：用户决策清理 GitHub 与本地**全部 1.x Release/Tag**（17 个；0.2.0 从未发布，无用户影响）。VERSION_NAME 0.2.0 → **0.1.0**、VERSION_CODE 保持 **1**，从 0.1.0 重新计数（0.1.0→0.1.1→…→1.0.0）
- **2026-08-07 首个发版 0.1.0-beta.1**：首个版本号即 0.1.0（beta 预发布 0.1.0-beta.1），**VERSION_CODE=1 保持**（0.x 阶段无已安装用户，不要求覆盖安装兼容；**1.0.0 起严格只增不减**）
- Debug 签名的 APK 可安装，但无法覆盖 release 签名安装（签名不同）；修复签名体系后，**旧 debug 签名安装的用户需卸载重装一次**

### Version Management（发版）

> ⚠️ **发版必读**：任何发版、版本号变更、tag 操作、GitHub Release 操作前，**必须**先读 [`docs/release-workflow.md`](docs/release-workflow.md)（唯一权威指南，含 `./scripts/release.sh` 一键脚本用法）。

核心红线（细节见 release-workflow.md）：
- **`version.properties` 是版本号唯一真相源**（`VERSION_CODE` 只增不减——**0.x 阶段豁免**（2026-08-07 用户决策：首个版本 0.1.0-beta.1 保持 code=1，无已安装用户；**1.0.0 起严格只增不减**）；禁止在 build.gradle.kts 硬编码；CI 用 grep 提取，**不要改变文件格式**）
- **严禁在 `version.properties` 修改前执行 `assemble*`**，否则 APK 内嵌版本号与 tag/release 不一致
- **每版本只发一个 APK**（命名 `oc-beacon-<VERSION>.apk`）；**不要删除历史 Release 和 Tag**（唯一例外：2026-08-07 用户决策清理全部 1.x 并重置 0.1.0，见 release-workflow §7）
- **默认发预发布版**：除非用户明确说明"正式发版"或"发 stable"，否则一律 beta/dev（`--prerelease`）
- `gh` CLI 不走代理，直接用直连（不加 `HTTP_PROXY`）
- 手动发版步骤（脚本不可用时）：`docs/release-workflow.md` §手动发版流程

### 验证与测试

**任何完成声明前必须加载 `verification-before-completion` skill**。铁律：没有新鲜的验证证据就不能声称完成。完整 4 维验证框架见 [`docs/verification-requirements.md`](docs/verification-requirements.md)。

测试基础设施：
- 单元测试：JUnit 4 + MockK + Turbine + kotlinx-coroutines-test（版本以 `app/build.gradle.kts` 为准）
- 插桩测试：`HiltTestRunner` + `createComposeRule()`（位于 `androidTest/`）
- E2E 流程：`maestro/` 目录下的 Maestro YAML
- `isReturnDefaultValues = true` — mock 返回默认值而非抛异常，可能掩盖 bug
- 每个层级要求：编译 ✅ + 单元测试 ✅ + 增强测试 ✅ + Maestro 流程（UI）+ androidTest（UI）

环境：
- opencode server 端口：4096，用户名 `opencode`，密码：环境变量 `${OPENCODE_SERVER_PASSWORD}`
- 模拟器访问宿主机：`10.0.2.2`
- **模拟器调试应使用 subagent 执行**：UI 交互（tap/input/scroll）、截图、logcat 读取等派给 `task` subagent 处理，避免主会话上下文溢出

### SSE 滚动稳定性（铁律）

SSE → UI 管线为：**48ms token 批处理** → **高度补偿** → **渲染**。违反任何一条都会重新引入闪烁、卡顿输出或视口跳底：

- **`Markdown()` 必须使用 `rememberMarkdownState(content, retainState=true)`** — 无状态 `Markdown(content=...)` 每次重组都重新解析 → 高度振荡 → 闪烁。
- **`scheduleFlush()` 不得取消进行中的定时器** — 每个 token 都取消会在速率 > 20/s 时饿死 flush → 突发式卡顿输出。
- **`layout{}` 补偿只应用于流式消息**（`if (isStreamingMsg)`）— 应用到所有 assistant 消息会让已完结消息暴露在不稳定测量下。
- **autoScroll/shouldCompensate 的 `LaunchedEffect` 必须以 `isScrollInProgress` 和 `isAtBottom` 两者作为 key** — `isAtBottom` 是自愈机制：用户通过非拖动方式（fling 惯性、SSE 内容推送）回到底部时重置标志。只以 `isScrollInProgress` 为 key 会让标志卡在陈旧状态 → 每个 SSE token 视口抖动。**不要把 `isAtBottom` 从 key 中移除。**

完整回归历史见 `docs/research/sse-scroll-stability-iron-laws.md`。

### Ktor 引擎
明确使用 **OkHttp engine** 以正确支持 SSE 流式传输。不要切换到其他引擎。

### UI 约定
**优先 Material 3 原生组件/样式/配色，禁止引入额外 UI 依赖库（如 Accompanist）**；主题令牌系统（Alpha/Spacing/Shape/Motion/Button/ListItem tokens）、Markdown 表格两端一致性规则见 [`docs/ui-conventions.md`](docs/ui-conventions.md)。

## 分支与远程仓库

| Remote | URL | 角色 |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-beacon` | Fork（有 push 权限，当前默认） |
| upstream | `github.com:crim50n/oc-remote` | Upstream（所有者: crim50n）— 需要时手动添加 |

- `master` — 稳定分支，与 upstream 同步
- 推送：`git push origin master` / `git push origin <tag>`

## Backlog 纪律

遇到以下情况，**立即登记到 [`backlog.md`](backlog.md)**（按文档内格式：优先级 P0-P2 + Tag + checkbox），**不要现场实现**：
- 当前会话忙时，优先级不高 / 非阻塞 / 非基础性的新需求
- 用户明确说"后面再做 / 以后做"的需求
- 任务中顺带发现、但与当前任务无关的 bug / 死代码 / 改进点（只登记，不跑题去修）

开始新任务前扫一眼 backlog，避免重复登记或重复实现。格式细节（优先级定义 / Tag 表 / 状态流转）以 `backlog.md` 自身为准。

## 其他

- **本地化**：15 种语言通过 `lokit.yaml` 管理。编辑字符串资源后，运行 `lokit` 同步翻译。
- **ProGuard**：Release 构建使用 R8 混淆。保留规则：`kotlinx.serialization` 注解类、Ktor 协程内部实现、Mikepenz Markdown 渲染器的状态/模型。
- **Android SDK**：`compileSdk` / `minSdk` / `targetSdk` 与 Compose BOM 等依赖版本以 `app/build.gradle.kts` 的 `defaultConfig` 与 `dependencies` 块为单一真相源，不在此重复维护。
