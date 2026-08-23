# AGENTS.md — OC Beacon

Unofficial OpenCode Android client. Jetpack Compose + Kotlin + Hilt + Ktor.

## 文档索引（先查这里）

> ⚠️ **新增/修改任何规则前，先读 [`docs/agents-file-design.md`](docs/agents-file-design.md)**——它是本 AGENTS.md 的设计规范（何时内联、何时外链、行数目标、写作规范）。新规则按该文档决策流程落地。

**级别说明**（RFC 2119 语义，详见 agents-file-design.md §3.5）：🔴 **MUST** = 该场景下先读再行动，跳过会出错/违规 · 🟡 **SHOULD** = 推荐，跳过需理解后果 · 🟢 **MAY** = 可选背景知识。MUST 数量受控（≤5-7 条）。

| 级别 | 文档 | 用途 | Use when |
|------|------|------|----------|
| 🔴 MUST | [`docs/release-workflow.md`](docs/release-workflow.md) | 发版唯一权威指南（版本规则/CHANGELOG/脚本/签名体系） | 任何发版、bump、tag、Release 操作前 |
| 🟡 SHOULD | [`docs/release-notes-template.md`](docs/release-notes-template.md) | GitHub Release 说明模板与写作规则 | 撰写发版说明前 |
| 🔴 MUST | [`docs/chatscreen-editing-protocol.md`](docs/chatscreen-editing-protocol.md) | ChatScreen.kt 编辑协议 | 编辑 ChatScreen.kt 前 |
| 🔴 MUST | [`docs/verification-requirements.md`](docs/verification-requirements.md) | 完整 4+1 维验证框架 | 完成开发、声称"完成"前 |
| 🟡 SHOULD | [`docs/real-device-testing.md`](docs/real-device-testing.md) | 真机 runbook：pm install 静默装包、adb reverse 连通、debug intent 配置、签名备忘 | 任何真机测试/E2E/装包前（2026-08-20 方针：真机优先） |
| 🟡 SHOULD | [`docs/qa-methodology.md`](docs/qa-methodology.md) | QA 方法论：交叉验证（≥2 维互证）、证据链、并行验证委派 | 修复/功能完成前的验证设计 |
| 🟡 SHOULD | [`docs/regression-guide.md`](docs/regression-guide.md) | 回归指南：变更分类、12 能力域清单 | 重构/接口变更/存储渲染层改动前 |
| 🟡 SHOULD | [`docs/dialogue-e2e-test-plan.md`](docs/dialogue-e2e-test-plan.md) | 会话全生命周期 E2E 期望文档 | 会话相关改动/发版前的 E2E 设计 |
| 🟡 SHOULD | [`docs/dialogue-e2e-test-runbook.md`](docs/dialogue-e2e-test-runbook.md) | 会话 E2E 实操记录与差异分析 | E2E 执行中实时记录 |
| 🟡 SHOULD | [`docs/observability-verification-guide.md`](docs/observability-verification-guide.md) | Logcat 规范、Room 直查、SSE 事件流、标准观测流程 | 代码改动验证（配合 verification 维度 3） |
| 🟡 SHOULD | [`docs/v1-v2-differences.md`](docs/v1-v2-differences.md) | V1/V2 功能与 API 差异完整清单 | V1/V2 兼容开发、版本探测、功能适配前 |
| 🟡 SHOULD | [`docs/simulator-walkthrough-v1v2.md`](docs/simulator-walkthrough-v1v2.md) | 版本探测修复模拟器走查清单 | 探测/兼容类改动后的走查 |
| 🟡 SHOULD | [`docs/opencode-api-reference-v1.md`](docs/opencode-api-reference-v1.md) | OpenCode **V1** Server API 参考（129 端点 + 89 SSE 事件） | 新功能开发、接口调试前（V2 端点以实测为准） |
| 🟡 SHOULD | [`docs/architecture.md`](docs/architecture.md) | 架构分层、目录职责、关键模式、承重规则 | 理解/修改跨层结构、SessionStateService、导航前 |
| 🟡 SHOULD | [`docs/chat-ui-event-lifecycle.md`](docs/chat-ui-event-lifecycle.md) | 触摸传播、SSE 流式更新、消息状态机、竞态 | 修改 ChatScreen 内部机制、排查交互竞态时 |
| 🟡 SHOULD | [`docs/i18n-guide.md`](docs/i18n-guide.md) | 国际化工作流（15 语言 + 检查脚本 + CI） | 任何文案改动前 |
| 🟡 SHOULD | [`docs/ui-conventions.md`](docs/ui-conventions.md) | Material 3、Theme Tokens、表格一致性 | 编写/修改 UI 组件前 |
| 🟡 SHOULD | [`docs/agents-file-design.md`](docs/agents-file-design.md) | AGENTS.md 维护规范（本文件设计依据） | 新增/修改 AGENTS.md 规则时 |
| 🟡 SHOULD | [`backlog.md`](backlog.md) | **未决工作项卡片清单**（P0-P3 + Tag + 状态流转 + journal/spec 约定） | 录入新条目前（避免重复）、开始新任务了解待办时 |
| 🟢 MAY | `docs/journal/` | 批次执行记录与验证证据（完结条目归档处，历史查询） | 回溯某批次修复细节/取证/勘误链时 |
| 🟢 MAY | [`docs/learning/AGENTS.md`](docs/learning/AGENTS.md) | **个人学习专区**（Kotlin/Android 教程，无业务语义，不承接功能性文档） | 在 docs/learning 下工作、或需确认某文档是否属于业务文档时 |
| 🟡 SHOULD | [`docs/specs/2026-08-21-error-report-github-design.md`](docs/specs/2026-08-21-error-report-github-design.md) | 错误日志 GitHub 上报设计 spec | 实现错误上报、GitHub 集成前 |
| 🟡 SHOULD | [`docs/specs/2026-08-21-in-session-audio-feedback-design.md`](docs/specs/2026-08-21-in-session-audio-feedback-design.md) | 会话内提示音设计 spec | 实现提示音、通知抑制、backlog #155 前 |
| 🟢 MAY | [`docs/architecture-debt.md`](docs/architecture-debt.md) | 已登记技术债务 | 接触相关模块时了解限制 |

## Build & Run

**默认只打一个包**：运行对应 flavor 的单个 assemble 任务（输出 `app/build/outputs/apk/<flavor>/<buildType>/`）；多任务命令仅用于确需多包场景。

```bash
# Windows: .\gradlew.bat ...
./gradlew :app:assembleDevDebug        # 开发调试（dev flavor）
./gradlew :app:assembleBetaRelease     # beta 发版
./gradlew :app:assembleStableRelease   # 正式发版
./gradlew :app:testDevDebugUnitTest --rerun   # 单元测试（强制重跑防 UP-TO-DATE）
./gradlew :app:compileDevDebugKotlin   # 快速编译检查
```

- **JDK 21**（`jvmToolchain(21)`；本地构建另在 `gradle.properties` 设 `org.gradle.java.home`）
- **代理警告**：`gradle.properties` 硬编码 `127.0.0.1:7897` HTTP 代理，代理不可达即构建失败；无代理构建时注释 4 行 `systemProp.*`
- **Gradle 构建禁止并发（同一 checkout）**：并发竞写 `app/build` 中间目录 → 测试 JVM 读半写类文件 → 无辜测试报 `NoClassDefFoundError: Hilt_*`（2026-08-14 实证）。多产物用单条多任务命令或串行
- **禁止无超时裸跑**：编译 120s · 单元测试 180s · 完整构建 300s · 依赖解析/首次构建 600s
- Windows 下 `BUILD SUCCESSFUL` 不返回（[gradle#12560](https://github.com/gradle/gradle/issues/12560)）→ `gradle.properties` 取消注释 `org.gradle.daemon=false` + `./gradlew --stop`

## Product Flavors

三 flavor 体系可同时安装共存（`dev`=`dev.leonardo.ocbeacon.dev` / `beta`=`…beta` / `stable`=`dev.leonardo.ocbeacon`）。所有 Gradle 任务必须指定 flavor（`assembleDevRelease`、`assembleBetaRelease` 等）。

## 架构概览

Clean Architecture, 3 layers. **Dependency direction: UI → Domain ← Data.** 完整目录树与关键模式见 [`docs/architecture.md`](docs/architecture.md)。

承重架构规则（违反会引入回归，详见架构文档）：
- **SessionStateService 是会话状态与流式活动的单一真相源**（idle/busy/retry + Waiting/Streaming/ToolCalling）。所有 UI 读取 `statusFlow`/`activityFlow`；状态写入经过纯函数 FSM（`SessionStateFSM`）。**不要重新引入按 handler 维护的状态**（`SessionStatusManager` 曾被移除）。
- **新日志代码用 `AppLogger.i/w/e`**（`logging/AppLogger.kt`，会出现在应用内 Diagnostics 屏幕），不要用 `android.util.Log`。
- **导航参数必须用 `NavUtils.safeDecodeParam()`**，不要裸 `URLDecoder.decode()`（畸形 `%` 序列如密码中的 `%NR` 会崩溃）。

## 关键约束

### ChatScreen.kt 编辑协议
见 [`docs/chatscreen-editing-protocol.md`](docs/chatscreen-editing-protocol.md)。**禁止跨 agent 并行编辑**；循环 = 每次 **Read → 编辑 → `compileDevDebugKotlin` → 成功即 commit**；失败时 `git checkout -- <file>` 重新读取重试。

### 路径处理（跨平台远程路径）

远程文件路径可能是 `/` 或 `\`（取决服务器 OS）。**始终使用 `PathUtils`**（`util/PathUtils.kt`）：

| 操作 | ✅ 使用 | ❌ 不要用 |
|-----------|--------|---------|
| 文件名 | `PathUtils.fileName(path)` | `substringAfterLast('/')`, `File(path).name` |
| 父目录 | `PathUtils.parentDir(path)` | `substringBeforeLast('/')` |
| 相对路径 | `PathUtils.relativePath(path, prefix)` | 手工 `removePrefix` |

### 签名
Release keystore 位于 `app/keystore/`（gitignore，仅本地文件与 CI Secrets 存在）；`signing.properties` 不存在时 release 构建回退 debug 签名——**禁止**无条件覆盖为 debug，否则 release keystore 永不生效。CI Secrets 配置命令、签名编年史、签名覆盖矩阵（本地↔CI 互不覆盖，切换需卸载重装）见 [`docs/release-workflow.md`](docs/release-workflow.md) §9；真机跨签名源切换唯一例外见 [`docs/real-device-testing.md`](docs/real-device-testing.md)。

### Version Management（发版）

> ⚠️ **发版必读**：任何发版、版本号变更、tag、GitHub Release 操作前，**必须**先读 [`docs/release-workflow.md`](docs/release-workflow.md)（唯一权威指南，含 `./scripts/release.sh` 用法）。

核心红线（细节见 release-workflow.md）：
- **`version.properties` 是版本号唯一真相源**（beta/stable；禁止在 build.gradle.kts 硬编码；CI 用 grep 提取，**不要改变文件格式**）。dev flavor 例外：versionCode 用 Unix 时间戳自动递增——`adb install -r` 直接覆盖安装、**禁止卸载重装**（见 release-workflow §2.4）
- **严禁在 `version.properties` 修改前执行 `assemble*`**（beta/stable）——否则 APK 内嵌版本号与 tag/release 不一致（dev 构建不受此限）

### 验证与测试

**任何完成声明前必须加载 `verification-before-completion` skill**。铁律：没有新鲜的验证证据就不能声称完成。完整 4+1 维框架见 [`docs/verification-requirements.md`](docs/verification-requirements.md)——**UI/UX 时间性现象（闪烁/动画/计时/布局跳动）自动化无法覆盖，必须提供人工验证清单（维度 5）并请用户验证后才能声称完成**。

- 测试栈（JUnit4/MockK/Turbine/coroutines-test、HiltTestRunner、Maestro）与版本以 `app/build.gradle.kts`、`androidTest/`、`maestro/` 为准；`isReturnDefaultValues = true` 的 mock 返回默认值，可能掩盖 bug
- 环境：opencode server 端口 **4199**，用户名 `opencode`，密码在配置文件 `/persistent/home/leo-tkp/.config/opencode/service.json`（`password` 字段，**不是环境变量**）
- **真机测试优先**（2026-08-20 方针）：小米 houji serial `e69a99d8`，静默装包/服务器连通/debug intent 配置见 [`docs/real-device-testing.md`](docs/real-device-testing.md)
- 模拟器访问宿主机 `10.0.2.2`；模拟器 UI 调试（tap/截图/logcat）派 subagent 执行，避免主会话上下文溢出

### SSE 滚动稳定性（铁律）

SSE → UI 管线：**48ms token 批处理 → 高度补偿 → 渲染**。违反任何一条都会重新引入闪烁、卡顿输出或视口跳底：

- **`Markdown()` 必须使用 `rememberMarkdownState(content, retainState=true)`** — 无状态 `Markdown(content=...)` 每次重组重新解析 → 高度振荡 → 闪烁。
- **`scheduleFlush()` 不得取消进行中的定时器** — 每个 token 都取消会在速率 > 20/s 时饿死 flush → 突发式卡顿输出。
- **`layout{}` 补偿只应用于流式 turn**（`if (isStreamingMsg)`，沿旧标识符名）— 应用到所有 assistant 消息会让已完结消息暴露在不稳定测量下。
- **autoScroll/shouldCompensate 的 `LaunchedEffect` 必须以 `isScrollInProgress` 和 `isAtBottom` 两者作为 key** — `isAtBottom` 是自愈机制（fling/SSE 推送回底时重置标志）。**不要把 `isAtBottom` 从 key 中移除。**

完整回归历史见 `docs/research/sse-scroll-stability-iron-laws.md`。**Ktor 明确使用 OkHttp engine**（SSE 流式正确性），不要切换其他引擎。

### UI 约定
**优先 Material 3 原生组件/样式/配色，禁止引入额外 UI 依赖库（如 Accompanist）**；主题令牌系统与 Markdown 表格一致性规则见 [`docs/ui-conventions.md`](docs/ui-conventions.md)。

## 分支与远程仓库

| Remote | URL | 角色 |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-beacon` | Fork（有 push 权限，当前默认） |
| upstream | `github.com:crim50n/oc-remote` | Upstream（所有者 crim50n）— 需要时手动添加 |

`master` 为稳定分支；推送 `git push origin master` / `git push origin <tag>`。

### Commit 纪律

- **type 前缀强制**（用户可见变更必须）：`feat:` / `fix:`（release.sh 的 CHANGELOG 分类映射依赖此前缀；无前缀 commit 会被发版脚本丢弃）
- **术语**：subject 用词遵循 [CONTEXT.md](CONTEXT.md)；「回退」禁表 revert（用「撤销」）——动词速查：中断（interrupt）/ 压缩（compact）/ 撤销（revert）/ 重命名（rename）/ 轮次完成（turn completed）

## Backlog 纪律

遇到以下情况，**立即登记到 [`backlog.md`](backlog.md)**（卡片格式：全局编号 + Tag + 状态 checkbox + ≤3 行摘要 + 链接），**不要现场实现**：
- 当前会话忙时，优先级不高 / 非阻塞 / 非基础性的新需求
- 用户明确说"后面再做 / 以后做"的需求
- 任务中顺带发现、但与当前任务无关的 bug / 死代码 / 改进点（只登记，不跑题去修）

开始新任务前扫一眼 backlog 避免重复登记/重复实现。**批次开工用 `./scripts/backlog-new-batch.sh "<批次名>"` 创建 journal 文件**；过程中的取证/验证证据写 journal 不写卡片；条目完结（用户验收）**当场迁入 journal**。格式细节（优先级/Tag/状态流转/spec 与 journal 约定）以 `backlog.md` 首段为准；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。

## 其他

- **国际化**：15 语言直接维护（英文源 `values/` + 14 翻译，无翻译框架）。任何文案改动按 [`docs/i18n-guide.md`](docs/i18n-guide.md) 工作流：改英文源 → 翻译 14 语言 → 跑检查脚本（CI 发版自动检查）。
- **ProGuard**：Release 构建用 R8 混淆，保留规则以 `app/proguard-rules.pro` 为准（serialization/Ktor/Mikepenz）。
- **Android SDK / 依赖版本**：以 `app/build.gradle.kts` 的 `defaultConfig` 与 `dependencies` 为单一真相源，不在此重复维护。

## Agent skills

Issue tracker（backlog + GitHub Issues 双轨制）、triage 标签、domain docs 布局三条约定见 [`docs/agents/skills.md`](docs/agents/skills.md)。
