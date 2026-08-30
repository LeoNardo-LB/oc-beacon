# AI 时代 Android 消息渲染技术栈深度调研（SSE 流式输出 / 组合卡片 / Markdown）

> **日期**：2026-08-30 · **调研员**：OC Beacon research agent · **数据采集日**：2026-08-30（GitHub / Maven / YouTrack 数据均为当日 API 快照）
>
> **方法约定**：每条论断标注一手信源（官方 release notes / Maven metadata / 仓库源码 / GitHub API / YouTrack）。
> 标记 【实证】= 直接抓取了一手来源原文；【推测】= 由一手证据推导，推导过程写明。
> 版本数字一律来自 Google Maven / Maven Central 的 `maven-metadata.xml` 与官方 release notes 页面，**不凭印象**。
>
> **对照基准**：OC Beacon 技术栈 = Kotlin 2.4.10 + Compose BOM 2026.08.00（ui 1.12.0）+ M3 1.4.0 + Hilt 2.60.1 + Ktor 3.5.2（OkHttp engine，SSE 手建）+ Room 2.8.4 + mikepenz multiplatform-markdown-renderer 0.45.0 + Coil3 3.6.0；自研：48ms token 批处理、高度补偿、MarkdownChunking.kt、12 种工具卡体系。

---

## TL;DR（先看这里）

| # | 结论 | 对 OC Beacon 的含义 |
|---|------|---------------------|
| 1 | androidx **没有**官方流式/增量文本渲染 API（1.12.0 stable、1.13.0-alpha02 release notes 全文核查）【实证】 | 自研管线仍无官方替代，继续自持 |
| 2 | M3 1.4.0 stable **无任何 AI 专属组件**；LoadingIndicator（Expressive）在 1.4 被移出 stable，1.5.0-alpha19 晋升后又被回退【实证】 | 不追 alpha，M3 1.4.0 是正确落点 |
| 3 | mikepenz 0.42+（2026-06）官方引入 **`StreamingMarkdownState`**：append-only，只重解析不稳定尾部【实证】 | **本次调研最高价值发现**——与我们 MarkdownChunking 同思路的官方实现，值得试点 |
| 4 | Ktor 官方 SSE client 插件已存在且支持 OkHttp engine（KTOR-505），但 OkHttp 路径 bug 尾巴长（重连重复事件/取消不关连接/解析器性能未决）【实证】 | 手建 SSE 继续保留，不迁移 |
| 5 | 同品类最接近的同类（gpt_mobile）也是「Ktor + 手建 provider SSE 聚合器 + mikepenz」；最重的同类（Operit 7378⭐）干脆自研整条 markdown 渲染器【实证】 | 我们的技术路线在同品类中属主流偏先进 |
| 6 | Stream 官方 AI 组件库给出 **`StreamingText`**（30ms 逐词 typewriter + 续写检测）【实证】 | 可借鉴为"揭示动画"表现层，勿动传输层 |
| 7 | #258（fling 首组合 p95 65ms）没有任何业界库解决；androidx 1.13 在列表性能上持续投入（VectorPainter 缓存共享）【实证】 | 维持自研优化路线 + 跟踪 androidx flag |

---

## Q1 官方层：Jetpack Compose / Material 3 是否有面向流式文本的 API？

### 1.1 版本基线核实【实证】

- Google Maven `androidx/compose/ui/ui/maven-metadata.xml`（抓取于 2026-08-30）：stable 最高 **1.12.0**，其后 **1.13.0-alpha01 / alpha02**。
- `compose-bom:2026.08.00` 的 POM（<https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.08.00/compose-bom-2026.08.00.pom>）映射 animation 1.12.0 —— 与我们 BOM 版本一致，ui 1.12.0 即当前 stable。
- 官方 release notes：<https://developer.android.com/jetpack/androidx/releases/compose-ui>（1.12.0 条目日期 2026-08-12；1.13.0-alpha02 日期 2026-08-26）。

### 1.2 compose-ui 1.12.0 / 1.13.0-alpha01+02 release notes 全文核查：无流式文本 API【实证】

对 release notes 页面全文（去 HTML 后）按 `streaming` / `increment` 关键词扫描：**零命中**。逐条阅读主要变更：

- **1.12.0（2026-08-12）**：Google Fonts 可变字体零配置接入、宽色域（WCG）色彩保真、`MeshGradientPainter` 硬件加速网格渐变、若干 value-class 初始化优化——**没有任何文本流式/增量渲染 API**。
- **1.13.0-alpha01（2026-08-12）/ alpha02（2026-08-26）**：测试框架重构（ComposeUiTestConfig）、Dialog/Popup 背景模糊、semantics 增强、`VectorPainter` 对相同 `ImageVector` 的跨 composable 图形资源缓存共享（列表场景性能优化，`ComposeUiFlags.isVectorDrawCacheSharingEnabled`）——同样**没有** TextMeasurer 增量测量、animateText、流式 Text 渲染路径之类 API。
- compose-foundation 1.12 / 1.13 release notes 同法扫描 `streaming|increment`：**零命中**（<https://developer.android.com/jetpack/androidx/releases/compose-foundation>）。

**结论**：截至 2026-08-30，androidx 官方层不存在面向流式/增量文本渲染的公开 API。【实证】
（【推测】androidx 对"AI 消息场景"的投入仍集中在底层列表/图形性能，而非高层流式文本组件；1.13.0-alpha02 的 VectorPainter 缓存共享是离我们 #258 痛点最近的官方动作。）

### 1.3 官方唯一相关姿势：quick guide「逐字符动画显示文本」【实证】

- **《Animate character-by-character the appearance of text》**：<https://developer.android.com/develop/ui/compose/quick-guides/content/animate-text>（页面标题实证；内容基于 `LaunchedEffect` 的 typewriter 模式，属"表现层打字机动画"配方，**不涉及**流式数据的增量解析/渲染）。
- 这是官方对"AI 打字机效果"的全部官方姿势——一个 quick guide 配方，不是组件、不是状态 API。

### 1.4 Material 3 1.4.x：无 AI 组件；Expressive LoadingIndicator 仍在 alpha 漂移【实证】

- `material3` maven-metadata（2026-08-30 快照）：stable 线只有 **1.4.0**（2025-09-24 发布），主线已推进到 **1.5.0-alpha27**。
- 官方 release notes 1.4.0 段（<https://developer.android.com/jetpack/androidx/releases/compose-material3>）：新组件为 `HorizontalCenteredHeroCarousel`、`VerticalDragHandle`、`SecureTextField`/`OutlinedSecureTextField`、**`Text` autoSize**、`TimePickerDialog`、新 SearchBar 状态 API；**没有任何 AI 相关组件**（无 AI 响应占位、无聊天专用组件）。
- 关键证据链（同页）：1.4.0-beta01 明示 **"All public APIs tagged with ExperimentalMaterial3ExpressiveApi … have been removed, please switch to 1.5.0-alpha"**——即 Expressive 组件（含 LoadingIndicator）在 1.4 stable 线不存在；随后 **1.5.0-alpha19 段出现 "Revert MaterialShapes and LoadingIndicator promotions to stable"（I30e69，b/497876695，b/497877850）**——曾一度晋升 stable 又被回退。LoadingIndicator 至今不是 stable API。
- 另外 1.4.0 起 `material-icons` 库停止更新并被移出默认依赖（Material Symbols 成为推荐路径）——我们如用 icon 库需显式声明依赖（行为变更，顺带记录）。

**结论**：M3 1.4.x 无 AI 消息组件可依赖；我们钉在 1.4.0 stable 是当前唯一稳妥选择，LoadingIndicator 等 Expressive 组件在 1.5 出 stable 前不应引入。【实证】

### 1.5 Google 是否发布过「AI chat UI on Compose」官方指南？【实证-检索性否定】

- developer.android.com 的 AI 板块（"Build AI experiences"导航）内容为 Gemini API / Firebase AI Logic 等**模型接入**指南，未见任何聊天 UI 组件/布局/流式渲染指南（多次定向检索 + 官方文档站页面扫描，2026-08-30）。
- 检索到最接近的官方物料即 1.3 的 animate-text quick guide；其余高排名结果全部是第三方（Stream 的指南与示例、社区项目）。
- **标记**：devsite 页面正文部分由 JS 注入，以上为「标题/导航/多次检索」级证据，置信度高但非逐字全文核查——如需更强证据可在浏览器人工复核一遍。

---

## Q2 mikepenz multiplatform-markdown-renderer：官方流式姿势

### 2.1 库状态【实证】

- 仓库：<https://github.com/mikepenz/multiplatform-markdown-renderer>，⭐ 1053，pushed **2026-08-28**（GitHub API 快照 2026-08-30），未归档。
- Maven Central `com/mikepenz/multiplatform-markdown-renderer/maven-metadata.xml`：最新 **0.45.0**（与我们一致）。发布节奏：0.42.0（2026-06-20）→ 0.43.0（2026-06-22）→ 0.44.0（2026-08-18）→ **0.45.0（2026-08-28）**，两个多月 4 个 release，**非常活跃**。

### 2.2 官方流式 API：`StreamingMarkdownState`（0.42.0 引入）【实证】

- **Release notes 原文**（GitHub Releases API，2026-08-30 抓取）：
  - v0.42.0（2026-06-20）：*"feat: render `StreamingMarkdownState`"*（PR #575）+ *"Fix and improvements to `Flow<String>.asMarkdownState()`"*（PR #564）
  - v0.43.0（2026-06-22）：*"add synchronous parseMarkdown returning a parsed State"*（PR #591）
  - v0.45.0（2026-08-28）：修复 bullet list 双倍底 padding、GFM math span 渲染（PR #626/#627）
- **源码**：`multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/model/StreamingMarkdownState.kt`（develop 分支文件树实证）。
- **README 官方用法**（develop 分支 README §Streaming，2026-08-30 原文摘录）：

  > "**Built for streaming** — `rememberStreamingMarkdownState()` appends chunks and re-parses only the unstable tail."

  ```kotlin
  val streamingMarkdownState = rememberStreamingMarkdownState()
  LaunchedEffect(chunkFlow) {
      chunkFlow.collect { chunk -> streamingMarkdownState.append(chunk) }
  }
  Markdown(streamingMarkdownState = streamingMarkdownState)
  // 或一步到位：
  val s = chunkFlow.collectAsStreamingMarkdownState()
  ```

  即官方定位：**append-only 增量，`append` 只重新解析文档的不稳定尾部，而非整串重解析**。
- 官方 **README 内嵌代码即官方姿势**；仓库文件树中未见独立的 streaming sample app 模块（截至 2026-08-30）——官方样例 = README §Streaming 代码块本身。
- Issues 检索（repo 内 `streaming` 关键词）未能取回有效结果（GitHub search API 在本环境不稳定），此子项标记为**证据缺口**，不影响 release notes/README/源码三重实证。

### 2.3 与我们「防闪烁铁律」的关系【推测，基于上述实证】

- 我们的铁律：`Markdown()` 必须 `rememberMarkdownState(content, retainState=true)`，否则整串重解析 → 高度振荡闪烁。官方 0.42+ 的 `StreamingMarkdownState` 在机制上是同一思想的**官方化**：把「保留已解析前缀、只解析不稳定尾部」下沉进库内状态。
- 【推测】升级路径 = 保持 0.45.0 不变，把流式消息的渲染从 `rememberMarkdownState` 切到 `rememberStreamingMarkdownState()`（非流式/历史消息不变），以 A/B 对照闪烁指标与高度补偿的相互作用。详见 Q6。

---

## Q3 SSE 传输层：Ktor 3.5 官方 client SSE vs 手建 OkHttp SSE

### 3.1 Ktor 官方 client SSE 插件：存在且已演进多年【实证】

- **官方文档**（<https://ktor.io/docs/client-server-sent-events.html>，标注 "Ktor 3.5.2"，2026-08-30 抓取）：客户端通过 `install(SSE)`（`io.ktor.client.plugins.sse`）启用；`client.sse { ... }` 获得 `ClientSSESession`，`incoming` 为事件 Flow；支持自动重连（`maxReconnectionAttempts` / `reconnectionTime`）、事件过滤（`showCommentEvents` / `showRetryEvents`）、**类型安全反序列化**（`TypedServerSentEvent` + `deserialize` 参数）、**响应缓冲策略**（`SSEBufferPolicy`：`Off`(默认)/`LastLines(n)`/`LastEvent`/`LastEvents(n)`/`All`，用于失败时诊断取回已处理数据）。
- **artifact 澄清**：`io.ktor:ktor-sse`（Maven Central metadata 最高 3.5.2）是**服务端** SSE 插件；**客户端** SSE 能力在 `ktor-client-core` 内（`io.ktor.client.plugins.sse` 包，见 <https://api.ktor.io/3.3.x/ktor-client-core/io.ktor.client.plugins.sse/index.html>）。文档原话："SSE only requires the ktor-client-core artifact"。
- **引擎能力位**：Ktor 3.5.2 源码 `SSE.kt`（<https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/sse/SSE.kt>）定义 `SSECapability : HttpClientEngineCapability<Unit>`，请求时 `request.setCapability(SSECapability, Unit)`（L29-33、L97）——引擎必须声明支持 SSE。OkHttp engine 的支持由 **KTOR-505**（"Add Server-sent events (SSE) plugin for client and support for OkHttp engine"，已 resolved）落地。

### 3.2 但 OkHttp 路径的 bug 尾巴长【实证，YouTrack API 2026-08-30】

YouTrack 检索 `project:KTOR SSE OkHttp`（时间戳已换算）：

| Issue | 摘要 | 状态/时间 |
|---|---|---|
| KTOR-9023 | SSE+OkHttp：`maxReconnectionAttempts>0` 时**收到重复事件** | resolved 2025-11-04 |
| KTOR-8708 | SSE+OkHttp：**logging interceptor 导致 SSE 会话挂起** | resolved 2025-09-22 |
| KTOR-8752 | SSE **不应受 HTTP 超时影响却受了** | resolved 2025-10-13 |
| KTOR-8409 / KTOR-8244 | **取消 SSE 请求/flow 不关闭底层连接** | resolved 2025-04-30 / 2025-02-26 |
| KTOR-8679 | OkHttpSSESession.onFailure 间歇性 NPE | resolved 2025-10 |
| KTOR-9397 | **SSE 解析器 StringBuilder 分配开销（性能）** | **未决** |
| KTOR-9057 | **"Improve documentation for SSE, OkHttp"** | **未决**（官方自己承认 SSE×OkHttp 文档不足） |
| KTOR-8652 | Ktor 升级其 OkHttp 依赖到 5.0.0 | resolved 2025-09 |

**解读**【推测，基于上表】：Ktor 官方 SSE×OkHttp 通道在 2025-2026 间集中修了一批严重问题（重复事件、挂起、连接泄漏、超时互扰），说明该组合此前并不稳；残留 1 个解析器性能未决项与 1 个文档未决项。对我们这种 89 种 SSE 事件、长连接、需 POST 流式的 agent 场景，迁移 = 用我们最重的路径去当别人新功能的试验田。

### 3.3 okhttp-sse 现状【实证】

- Maven Central `com/squareup/okhttp3/okhttp-sse/maven-metadata.xml`（2026-08-30）：**5.x 已是正式线，最新 5.5.0**（5.0.0 → 5.1.0 → … → 5.5.0）。
- OkHttp CHANGELOG（<https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md>）：5.5.0 发布于 **2026-08-16**（ECH/DNS API 大版本），项目活跃；`okhttp-sse` 模块随 5.x 持续随行（`okhttp3.internal.sse.RealEventSource` 源码实证存在）。
- okhttp-sse 定位：极简（`EventSource` / `EventSource.Factory` / `Event`），**故意不带重连**——重连语义留给我们自管（我们确实自管了，含 SSE→UI 管线）。

### 3.4 取舍结论【推测，基于 3.1-3.3】

| 维度 | Ktor 官方 SSE 插件 | 我们手建（OkHttp engine 上） |
|---|---|---|
| 事件语义控制 | 通用 SSE（data/event/id/retry） | 完全可控（OpenCode 89 事件、auth、POST 流式） |
| 重连 | 内建但曾致重复事件（KTOR-9023） | 自建、行为自证 |
| 调试友好 | 与 logging 插件组合曾挂起（KTOR-8708） | 自建管线直连我们的观测体系 |
| 维护成本 | 交给 JetBrains，但 bug 尾巴在 OkHttp 路径更长 | 成本自担，但已稳定运行 |
| 迁移风险 | —— | 迁移 = 重验铁律级稳定性 |

**维持手建**。复核触发条件：KTOR-9397（解析器性能）与 KTOR-9057（文档）关闭、且 Ktor 出 3.6+ 稳定版后再评估。

---

## Q4 同品类开源 Android AI 聊天客户端实证

> 选样标准：GitHub 活跃（近 2 月有 push）、非 demo toy、Kotlin/Compose 技术栈、与「流式 markdown + 消息卡片」相关。数据均为 GitHub API 2026-08-30 快照。

### 4.1 Taewan-P/gpt_mobile —— 与我们架构最接近的同类【实证】

- ⭐ **1210**，pushed **2026-08-27**（极活跃），Kotlin/Compose，FOSS，多 LLM 供应商（OpenAI/Anthropic/Google 等 BYOK）。
- 依赖（`gradle/libs.versions.toml`）：**ktor 3.5.1**（ktor-client-cio **和** okhttp 两个 engine 并备）、**mikepenz multiplatform-markdown-renderer 0.41.0**（+`-m3`+`-code` 模块）。
- 流式实现：**手建 provider 级 SSE 聚合器**——`data/agent/provider/ProviderEventAssemblers.kt` 中按供应商写 `OpenAIResponsesEventAssembler` / Anthropic / Google 装配器，把原始流事件（`OutputTextDeltaEvent`、`ReasoningSummaryTextDeltaEvent`、`FunctionCallArgumentsDeltaEvent`…）归一为自有 `ProviderEvent`（`ThinkingDelta`/`TextDelta`/工具调用增量）。
- **与我们对照**：同样的「Ktor + 手建 SSE→语义事件归一」管线（等价于我们的 SSE→UI 管线），同样的 mikepenz 渲染（版本略旧，0.41 < 流式 API 的 0.42）。它也在 assets 里带了 **MathJax（tex-svg.js）**——数学公式渲染生态缺位的又一旁证，WebView 回退是业界常态。

### 4.2 AAswordman/Operit —— 自研派天花板【实证】

- ⭐ **7378**（本次候选中最高），pushed **2026-08-28**，Kotlin，Android AI agent + chat。
- 渲染路线：**整条 markdown 渲染自研**，`ui/common/markdown/` 下有 `CanvasMarkdownNodeRenderer`、`CanvasMonospaceCodeBlockBody`、`EnhancedTableBlock`、`MarkdownNodeGrouper`、`MarkdownInlineSpannable`、`MarkdownImageRenderer`、`MarkdownAudioRenderer` 等（文件树实证）；流式侧有 `util/stream/plugins/` 的 **StreamMarkdownPlugin / StreamJsonPlugin / StreamXmlPlugin**（带单元测试）——对「流中未闭合 markdown/json/xml」做了显式的流级插件处理。
- **与我们对照**：证明了「重度 agent 客户端最终都走向自研渲染」这一路线成立（且其体量更大）；其 StreamMarkdownPlugin 与我们 MarkdownChunking.kt 解决同一问题（流式内容的不完整性），值得对照设计。

### 4.3 skydoves/chatgpt-android（3869⭐）与 GetStream/gemini-android（387⭐）—— Stream SDK 派【实证】

- chatgpt-android：⭐3869，pushed **2026-01-03**（半活跃，示范项目属性）；gemini-android：⭐387，pushed **2026-07-09**。
- 两者依赖（`gradle/libs.versions.toml` 实证）：`io.getstream:stream-chat-android-client / -offline / -compose` + Retrofit/OkHttp。消息 UI（消息卡片/列表/输入区）**整体交给 Stream Chat Compose SDK**，自研部分只在数据层接 OpenAI/Gemini API。
- **解读**【推测】：这是「买卡片体系」路线——但前提是接受 Stream 的消息模型（聊天平台语义）；对 OC Beacon 这种 agent 事件流（12 种工具卡、思考卡、压缩卡）不适配，SDK 化不可行，仅作参照。

### 4.4 mhss1/MyBrain —— KMP + mikepenz 0.43 + Koog【实证】

- ⭐ **2197**，pushed **2026-08-20**（活跃），Kotlin Multiplatform（Android/iOS/Desktop）。
- 依赖：**ktor 3.5.1**（CIO+OkHttp 并备）、**mikepenz 0.43.0**（+coil2）、**JetBrains Koog**（`ai.koog:http-client-ktor`，JetBrains 官方 agent 框架）。
- 渲染：`MessageCard.kt` 直接用 mikepenz `Markdown()`（自定义 typography）+ `AnimatedVisibility` + 自定义 `messageCardAnimatedPlacement()` 位置动画——**未用** 0.43 已有的 StreamingMarkdownState（流式侧平淡，按整串渲染）。
- **对照**：中等复杂度客户端的典型水位；我们防闪烁管线明显在其之上。

### 4.5 GetStream/stream-chat-android-ai —— 官方 AI 消息组件库【实证】（同时是 Q5 主角）

- ⭐ **25**（小），pushed **2026-08-18**（活跃），Stream 官方出品，定位 "UI components for AI-first Android apps with Jetpack Compose"。
- 核心组件 `StreamingText.kt`（源码已读，develop 分支）：**逐词 + 逐空白块的 typewriter 揭示动画**，`chunkDelayMs = 30`；关键设计：
  - **续写检测**：新文本以旧文本为前缀 → 从当前位置继续揭示（适配流式追加）；非前缀（新问题/重置）→ 从头动画；
  - `animate=true→false` 时先播完当前动画再显示全文（防闪跳）；非动画态初始帧直接显示全文（避免空帧闪烁）——与我们「防闪烁铁律」同一关注点；
  - 内容层默认接内部 `RichText`（= mikepenz 渲染器，其 libs.versions.toml 钉 mikepenz **0.38.1**，早于流式 API）。
- **解读**【推测】：Stream 把「token 动画」做成了**纯表现层组件**（输入是完整 text 字符串），传输/解析完全不管——与我们的「管线层批处理」正交，可拆借。

---

## Q5 2025-2026 新兴技术盘点

### 5.1 有真实采用度/生命力的【实证】

| 技术 | 证据 | 判断 |
|---|---|---|
| **mikepenz `StreamingMarkdownState`**（0.42+，2026-06） | release notes PR #575/#564 + README §Streaming + 源码文件；主库 ⭐1053、4 release/2 月 | **主流化最强信号**：KMP markdown 渲染器官方长出流式一等公民。已知消费者：MyBrain 用 0.43（暂未用该 API）、stream-ai 用 0.38（早于该 API）——采用尚在早期，但库本身活跃度无虞 |
| **OkHttp 5.x 正式线**（5.0.0→5.5.0，最新 2026-08-16） | Maven metadata + CHANGELOG | okhttp-sse 随行到 5.5.0；Ktor 官方也已升级其 OkHttp 依赖到 5.0.0（KTOR-8652）——我们整个传输底座处于上升通道 |
| **Stream AI 组件**（stream-chat-android-ai，2026-08 仍活跃） | 官方仓库 + `StreamingText.kt` 源码；主库 stream-chat-android ⭐1661、pushed 2026-08-28 | 组件本身生产级代码质量，但独立采用度低（⭐25），价值在**设计参照**而非引入依赖 |
| **JetBrains Koog**（agent 框架，非 UI） | MyBrain 依赖 `ai.koog:http-client-ktor` 实证 | 与本题 UI 无关，仅记录生态动向 |

### 5.2 死亡/休眠名单（本调研主动核查）【实证】

| 项目 | 核查结果（2026-08-30） |
|---|---|
| **halilibo/compose-richtext** | **仓库已删**：GitHub API 返回 404（full_name/archived/pushed 均为 null）——再次验证我们此前教训，任何迁移提案中此库为禁区 |
| **noties/Markwon** | 未归档但 **pushed=2024-04-17**（GitHub API）——休眠 2 年 4 个月，View 体系而非 Compose，双重不推荐 |
| lambiengcode/compose-chatgpt-kotlin-android-chatbot（⭐261） | pushed 2024-11-23，停滞近 2 年 |
| 其余搜索命中（AI-Pocket-Chat ⭐22、PocketTavern ⭐29、openchat ⭐3 等） | 采用度不足；另 cogwheel0/conduit（⭐2037，Open WebUI 客户端）为 **Flutter**，超出 Compose 调研范围，仅说明「OpenAI 兼容客户端」需求真实存在 |

### 5.3 明确不存在的「新兴方案」【实证-检索性否定】

- 未发现任何**专做 Compose 流式 markdown** 的独立新库（除 mikepenz 官方内置外）。2025-2026 的创新收敛在两条线：渲染器内置流式状态（mikepenz）+ 表现层揭示动画（Stream StreamingText / 官方 animate-text quick guide）。
- token 动画没有事实标准：Stream 的 30ms/词是**唯一**可引用的具体参数实现（源码级实证）。

---

## Q6 综合判断：对 OC Beacon 逐项对照

### 6.1 值得吸收/评估的（按优先级）

**P1 · 试点 mikepenz `StreamingMarkdownState`（升级路径已有，库无需换）**
- 现状：我们 0.45.0 已含该 API（0.42.0 引入），等于**功能已在我们 classpath 里躺着**。
- 动作：流式 turn 的 assistant 消息渲染从 `rememberMarkdownState(content, retainState=true)` 换轨 `rememberStreamingMarkdownState()` + `append()`（SSE 批处理 flush 后 append），历史/非流式消息不动。验收 = 防闪烁铁律回归清单 + 高度补偿交互 + 长代码块/表格/数学场景闪烁对比。
- 预期收益【推测】：省掉 MarkdownChunking.kt 的分块启发式维护面，把「只解析不稳定尾部」交给库方持续演进（0.44/0.45 还在修渲染细节）；若库内尾部判定比分块更细，闪烁窗口应更小。
- 风险：库内重解析粒度与我们 48ms flush 的耦合方式未知，需实测；不达标则维持现状（无沉没成本，API 是纯增）。

**P2 · 借鉴 `StreamingText` 的「续写检测 + 动画收尾」设计语义（不引依赖，抄设计）**
- 三条可直接吸收的细节（源码级）：① 追加流用前缀检测续写、重置流从头；② `animate=true→false` 先播完再定格，防闪跳；③ 非动画首帧直接全文，杜绝空帧。适用于「turn 完成时的定格」与「重生成」场景的表现层。
- 注意【推测】：我们已有 48ms 批处理节流，若再叠 30ms typewriter 会双重动画——只建议用于 turn 完成后的收尾揭示，不用于流中。

**P3 · 数学公式：维持 WebView 回退定位，不新增依赖**
- 生态证据：gpt_mobile 带 MathJax assets、mikepenz 0.45 才修 GFM math span（且只是「渲染为文本不丢弃」）。原生数学渲染在 Compose 生态仍是空白，我们的遗留 WebView 回退即业界常态。

### 6.2 明确不动的（业界证据支持维持）

| 我们的自研 | 业界对照 | 判定 |
|---|---|---|
| **手建 SSE（Ktor OkHttp engine）** | Ktor 官方插件存在但 OkHttp 路径 2025 年密集修复重复事件/挂起/连接泄漏，解析器性能仍未决（KTOR-9397）；最像我们的 gpt_mobile 也手建聚合器 | **保持**；以 KTOR-9397/9057 关闭 + 3.6 stable 为复核触发器 |
| **48ms token 批处理** | 无任何官方/第三方等价物；Stream 走表现层 30ms 动画，管线层节流仍是空白 | **保持**，属业界先进水平（管线层节流无先例可抄） |
| **高度补偿 + reverseLayout 自愈双 key** | 无先例；androidx 未提供流式高度稳定机制 | **保持** |
| **MarkdownChunking.kt** | 同思路已被 mikepenz 官方化（尾部重解析）；Operit 用流级插件解决同类问题 | **进入 P1 试点对照**；试点失败则保留，成功则降维护面 |
| **12 种组合卡片 + AnimatedVisibility 顶边揭幕** | Stream SDK 证明卡片体系可 SDK 化但绑定其平台语义；Operit 同我们一样全自研 | **保持**；我们路线与最重同类一致 |
| **M3 1.4.0 钉版** | 1.4.0 是唯一 stable；Expressive LoadingIndicator 晋升又回退（1.5.0-alpha19） | **保持**；1.5 出 stable（LoadingIndicator 稳定落地）再评估引入 loading 表现 |

### 6.3 #258（fling 首组合 p95 65ms/p99 129ms）的行业坐标【实证+推测】

- 没有任何受调研库/应用公布针对性方案（各仓库无相关 issue/文档命中）——该问题是**行业共有且无人解决**的。
- androidx 正向列表性能持续投入：1.13.0-alpha02 的 `VectorPainter` 图形资源缓存共享（明确点名 lists 场景重复 icon 收益，`ComposeUiFlags.isVectorDrawCacheSharingEnabled`）——【推测】我们的消息卡若含重复 icon/ImageVector，可实验开启该 flag 测 p95/p99 变化；同时值得把 #258 的 profiler 证据链提报 Google issue tracker（b/ 反查无同类报告时收益最大）。

---

## 补充（2026-08-30 同日，P1 试点设计的代码级细化）

> 成文后复核了 `MarkdownContent.kt` / `MarkdownChunking.kt` 实际代码，P1 试点的真实范围比初稿更窄——存在两个必须先回答的设计冲突：

1. **追溯式归一化 vs append-only（硬冲突）**：我们的 `splitOversizedParagraphs`（3000 字符阈值，2026-08-20 C-F1）与 `TABLE_AFTER_TEXT_REGEX`（表格前补空行）都是**事后改写已流出前缀**的归一化；而 `append()` 只能追加、不能改写已 append 的前缀。段落流式中途越过 3000 字符阈值时，纯 append 路径无法补空行化 → 129K 单段 worst case 在流中途回潮。试点需设计「越界时 reset+rebuild 状态」或「流中不切片、完结时一次归一化重建」。
2. **blockRange LazyItem 切片与单状态渲染（范围问题）**：fling 巨帧修复依赖把顶层块区间切成 LazyItem（`blockRange`/`blockAnchor` #246）；`StreamingMarkdownState` 是单 `Markdown()` 单文档渲染，README 未提供区间切片 API。试点需核实其状态对象是否暴露解析树以供我们继续做 chunk plan，否则只适用于中短消息的流式阶段。

**修正后的 P1 范围**：仅替换流式阶段的「增量解析状态」（`snapshotFlow+conflate` 路径 → 库状态）；`normalizeForRender`、`blockRange`/`blockAnchor` 切片、非流式 `rememberAsyncMarkdownState` fallback 全部保留。验收新增：超长段落（>3000 字符）流中滚动性能对照。

## 附录 A · 数据采集记录（2026-08-30）

- GitHub API（repos/search/releases/issues）：mikepenz/multiplatform-markdown-renderer ⭐1053 pushed 2026-08-28；ktorio/ktor ⭐14504 pushed 2026-08-29；Taewan-P/gpt_mobile ⭐1210 pushed 2026-08-27；AAswordman/Operit ⭐7378 pushed 2026-08-28；skydoves/chatgpt-android ⭐3869 pushed 2026-01-03；GetStream/gemini-android ⭐387 pushed 2026-07-09；mhss1/MyBrain ⭐2197 pushed 2026-08-20；GetStream/stream-chat-android-ai ⭐25 pushed 2026-08-18；GetStream/stream-chat-android ⭐1661 pushed 2026-08-28；halilibo/compose-richtext → 404；noties/Markwon pushed 2024-04-17。
- Maven metadata：`androidx/compose/ui/ui`（stable 1.12.0）、`androidx/compose/material3/material3`（stable 1.4.0；最高 1.5.0-alpha27）、`io/ktor/ktor-sse`（3.5.2）、`com/squareup/okhttp3/okhttp-sse`（5.5.0）、`com/mikepenz/multiplatform-markdown-renderer`（0.45.0）、`compose-bom:2026.08.00` POM。
- 官方 release notes：compose-ui / compose-foundation / compose-material3（developer.android.com，2026-08-30 抓取）。
- YouTrack：KTOR-505/8708/9023/8409/8244/8752/8679/9397/9057/8652（API 快照）。
- 源码：Ktor `SSE.kt`（3.5.2 tag）、Stream `StreamingText.kt`（develop）、gpt_mobile `ProviderEventAssemblers.kt`（main）、mikepenz README §Streaming（develop）。
- 环境备注：本环境 api.github.com 经镜像代理返回 301（需 `-L` 跟随），GitHub search/issues 接口间歇性空结果（已在正文标注证据缺口）；developer.android.com 部分页面正文 JS 注入，1.5 节指南以标题/导航级证据为准。
