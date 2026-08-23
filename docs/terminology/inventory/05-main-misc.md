# 盘点：src/main 其余（data/domain/ui 之外的 Kotlin：util/di/logging/service/debug/app 根等 + res 资源 xml，strings.xml 除外）

> Phase 1 事实收集（只读盘点，不做术语裁决）。范围：app/src/main 下 kotlin 源树中不属于 data/domain/ui 的全部 .kt（27 个）+ res 下 values*/strings.xml 之外的 XML（86 个），共 113 文件，全部精读完毕。
> CONTEXT.md 已读，既有 8 词条：渲染供给(Render Supply)/流式 turn(Streaming Turn)/跳转稳定窗口(Jump Settling Window)/红点时钟域(Unread Clock Domain)/必需协作者(Required Collaborator)/状态簇(State Cluster)/版本 seam(Version Seam)/连接生命周期协调(Connection Lifecycle)。
> 进度：**113/113 完成**（27 Kotlin + 86 res XML）。

## 覆盖清单

### Kotlin（27）

#### app 根（2）
- app/src/main/kotlin/dev/leonardo/ocbeacon/MainActivity.kt ✓ 中文（KDoc+行内全中文；日志文案英文、偶带中文片段）— 会话 deep-link / #132 调试通道 / debug_perf 性能监测 / 终端按键拦截 / 图片分享→附件；注释与行为核对基本一致，另发现 1 处日志字面量缺陷（见失实注释表）。
- app/src/main/kotlin/dev/leonardo/ocbeacon/OpenCodeApp.kt ✓ 中文 — 崩溃日志/重启退避、StrictMode、诊断日志初始化、语言镜像校验、通知抑制前台跟踪、onTrimMemory 清 ToolSnapshotCache；注释与行为一致（onTrimMemory 阈值条件第二子句冗余，见失实注释表备注）。

#### debug/（5）
- app/src/main/kotlin/dev/leonardo/ocbeacon/debug/ChatPerfMonitor.kt ✓ 中文（日志英文：JANK/STEADY/PerfMon）— 常驻性能监测器；帧预算/jank/稳态采样/相位分解术语密集；注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/debug/FrameStatsWindow.kt ✓ 中文 — 帧统计滚动窗口（纯 Kotlin）；L6 用「性能检测」与 ChatPerfMonitor「性能监测」构成变体；注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/debug/PerfHud.kt ✓ 中文 — 同窗口 Compose 性能 HUD；色阶阈值注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/debug/PerfHudOverlay.kt ✓ 中文（仅 KDoc，函数体无注释）— 独立悬浮窗 HUD（观察者效应隔离）；注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/debug/RaceProbe.kt ✓ 中文 — 竞态取证埋点（叠放 bug）；注释与代码一致。

#### di/（5）
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/ApiModule.kt ✓ 中文（仅 KDoc）— **失实**：KDoc 称「6 个领域 API 接口的 Hilt 绑定」，实际 7 个 @Binds（Session/Message/Terminal/Shell/Provider/File/System）。
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/CoroutinesModule.kt ✓ 无注释 — 仅 ApplicationScope 限定符与协程 Scope 提供；日志英文一句。
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/DomainModule.kt ✓ 无注释 — 15 个仓库绑定纯声明；标识符承载术语（SessionStateService→SessionStateRepository、MessageStore→MessageCacheRepository）。
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/NetworkModule.kt ✓ 中文（行内）— Json/Ktor HttpClient/DataStore 提供；**失实**：L73「禁用响应体缓冲以支持流式传输」对应的实参是 retryOnConnectionFailure(true)，与缓冲无关。
- app/src/main/kotlin/dev/leonardo/ocbeacon/di/ToolCardModule.kt ✓ 无注释 — ToolCardResolver 单绑定。

#### logging/（1）
- app/src/main/kotlin/dev/leonardo/ocbeacon/logging/AppLogger.kt ✓ 中文（日志英文）— 诊断日志桥接器（logcat+诊断库，Channel 500/DROP_OLDEST，批 50 条 flush）；注释与代码逐条一致（容量/丢弃策略/单次初始化/单调时间戳 CAS 均核实）。

#### service/（6）
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/AppNotificationManager.kt ✓ 中文（日志英文）— 通知渠道/持久通知/四类事件通知（TaskComplete/Permission/Question/Error）/去重与抑制/会话内提示音联动；**失实**：findLatestUserMessages KDoc 称「用于 MessagingStyle 显示」但全文件无 MessagingStyle 用法（仅 setContentText/InboxStyle）。
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinator.kt ✓ 中文 — CONTEXT.md「连接生命周期协调」词条的实现本体（注释明引 CONTEXT.md）；connect 幂等/同后端去重/四路清理/registry 全部与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/InSessionFeedbackPlayer.kt ✓ 中文 — #155 会话内提示音：策略镜像管线（DND→渠道→铃声档）、错误 streak、独立去重；注释与代码一致（1 处 ERROR 型去重语义细微偏差见失实表）。
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionService.kt ✓ 中文 — FGS 宿主适配（#170 后为 adapter）：事件路由/question 轮询 REST 兜底/wakeLock 续期/FGS 6h 超时重启；**失实**：startQuestionPolling KDoc 仍描述「断连时停止」旧行为（2026-08-18 已移除 isConnected 检查，KDoc 与其行内修复注释自相矛盾）；类 KDoc 通知职责漏列 question/error；L461 日志 `${'$'}` 转义缺陷。
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/SessionFocusHolder.kt ✓ 中文 — 前台+焦点双条件抑制（#175 合并双方法、#137 微竞态备注）；注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/service/SseConnectionManager.kt ✓ 中文 — SSE 连接主循环（退避重连/超时冷却/预加载并行/断连恢复补漏/durable.seq gap 检测）；注释与代码逐条一致；V1/V2 客户端选择、REST_AUTHORITY/SSE_PRIORITY 策略词落位。

#### util/（8）
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/ClipboardUtils.kt ✓ 中文（仅 KDoc 两行）— 系统剪贴板双入口（Compose/Android ClipboardManager，D2-L16 统一入口）；注释与代码一致；无领域术语。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/DateFormatters.kt ✓ 中文 — 统一日期格式化入口（audit D2-L15：14 处 SimpleDateFormat、8 种格式收敛）；各格式注明用途（会话行/消息气泡/任务卡片/诊断日志行/崩溃文件名）；注释与代码逐条一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/DebugLogger.kt ✓ 中文 — 调试日志器（logcat + Downloads/annotate_debug.log，512KB 限容丢最旧）；KDoc「采集会话」与 OpenCode session 撞词（见冲突表）；文件位置双分支与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/LocaleUtils.kt ✓ 中文 — BCP 47 解析/语言镜像同步读（StrictMode 设计读）/applyAppLanguage；注释与代码一致。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/MessageFingerprints.kt ✓ 中文 — 消息列表指纹/签名纯函数（结构签名= id 序列；内容指纹= 流式变异字段尾部哈希）；2026-08-15 追加 modelId/providerId/agent 注释与代码一致；无失实。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/PathUtils.kt ✓ 中文 — **承重工具**：跨平台路径（/ 与 \\ 双分隔符）；fileName/parentDir/relativePath/joinPath 四函数注释与实现逐条核对一致（含「prefix 不匹配返回原始路径」「base 空白原样返回」边界）；**无失实注释**。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/RunCatchingCancellable.kt ✓ 中文 — 协程安全 runCatching（#128 根因：吞 CancellationException）；语义三支与代码一致；「与 safeCatch 一致」的捕获范围差异（Throwable vs Exception）见失实表。
- app/src/main/kotlin/dev/leonardo/ocbeacon/util/SafeCatch.kt ✓ 中文 — 协程安全 catch 包装（#60 防 TD-6）；语义三支与代码一致。

### res XML（86）

#### 非 drawable（7）
- app/src/main/res/values/themes.xml ✓ 无注释 — 单样式 Theme.OpenCode（Manifest 主题基座）；无领域术语。
- app/src/main/res/xml/backup_rules.xml ✓ 中文（1 条行内注释）— 排除 datastore/ 云备份；注释「服务器配置（DataStore，含服务器密码）」与规则一致。
- app/src/main/res/xml/data_extraction_rules.xml ✓ 中文（1 条行内注释）— 同上规则的 Android 12+ 版（cloud-backup + device-transfer 双分支）；注释与规则一致。
- app/src/main/res/xml/file_paths.xml ✓ 中文（1 条行内注释）— FileProvider 路径：updates/ + diagnostics/；注释引用 DiagnosticsScreen.shareAsFile（诊断分享），与 cache-path 一致。
- app/src/main/res/xml/network_security_config.xml ✓ 中文（块注释）— cleartext 全局放行决策记录（#118→2026-08-15：Tailscale/LAN 自建服务器场景）；「opencode 服务器」「自建服务器客户端」术语；注释与 base-config 一致。
- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml ✓ 无注释 — 自适应图标（background/foreground 两层）；无领域术语。
- app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml ✓ 无注释 — 同上（round 变体）；无领域术语。

#### drawable（79）
- app/src/main/res/drawable/ic_launcher_background.xml ✓ 英文（1 条行内注释）— 启动器背景纯色 #131010；注释「identical to foreground's background, zero seam」与前景填充一致。
- app/src/main/res/drawable/ic_launcher_foreground.xml ✓ 英文 — 官方 favicon-v3.svg（anomalyco/opencode, MIT）转 Vector；O-ring + 灰块 60% 缩放（安全区说明）；注释与 path/group 参数一致。
- app/src/main/res/drawable/ic_notification.xml ✓ 英文 — 单色通知图标（OpenCode O-ring 轮廓，512→24 缩放说明）；注释与 pathData 一致。
- app/src/main/res/drawable/text_select_handle_left_material.xml ✓ 无注释 — 文本选择左把手（Material）；无领域术语。
- app/src/main/res/drawable/text_select_handle_right_material.xml ✓ 无注释 — 同上（右把手）；无领域术语。
- app/src/main/res/drawable/ic_provider_*.xml（74 个）✓ 全部无注释、无硬编码文案 — 纯 vector path 数据，文件名即 provider id（snake_case）。清单：abacus, aihubmix, alibaba, alibaba_cn, amazon_bedrock, anthropic, azure, azure_cognitive_services, bailing, baseten, cerebras, chutes, cloudflare_ai_gateway, cloudflare_workers_ai, cohere, cortecs, deepinfra, deepseek, fastrouter, fireworks_ai, friendli, github_copilot, github_models, google, google_vertex, google_vertex_anthropic, groq, helicone, huggingface, iflowcn, inception, inference, io_net, kimi_for_coding, llama, lmstudio, minimax, minimax_cn, mistral, modelscope, moonshotai, moonshotai_cn, morph, nano_gpt, nebius, nvidia, ollama_cloud, openai, opencode, openrouter, ovhcloud, perplexity, poe, requesty, sap_ai_core, scaleway, siliconflow, siliconflow_cn, submodel, synthetic, togetherai, upstage, v0, venice, vercel, vultr, wandb, xai, xiaomi, zai, zai_coding_plan, zenmux, zhipuai, zhipuai_coding_plan。无失实注释可能；命名变体见冲突表 14。

## 术语观察

| 概念 | 观察到的变体 | 位置 文件:行 | 与 API 词一致? |
|---|---|---|---|
| 会话 | 会话 URL、原始会话 ID、目标会话、会话列表、sessionId/sessionPath（标识符） | MainActivity.kt:57,62,85,92,356 | session ✓ API 原词；sessionPath 为本地扩展（URL 形态） |
| 会话（中文注释内英文化） | 「子 session 权限冒泡到父 session 通知」vs「子会话」「子/子代理会话」 | OpenCodeConnectionService.kt:543,631,658,689；AppNotificationManager.kt:490 | session ✓；同文件内「会话/session」混用 |
| 子代理会话 | 「子/子代理会话（已设置 parentID）」、parentId | AppNotificationManager.kt:490,407,633 | parentID ✓ API 会话字段；「子代理」对应 subagent |
| 服务器 | 服务器、serverId/ServerConfig/同后端（url+username 归一化） | MainActivity.kt:356ff；ConnectionLifecycleCoordinator.kt:27,89-99 | server ✓ API 原词 |
| 服务器配置（本地存储） | 「服务器配置（DataStore，含服务器密码）」 | res/xml/backup_rules.xml:2；res/xml/data_extraction_rules.xml:2 | server ✓；指本地 ServerConfig 存储（与远程实体相关但不同） |
| 自建服务器 | 「自建 opencode 服务器」「自建服务器客户端」「明文 HTTP 是核心场景」「Tailscale/LAN IP」 | res/xml/network_security_config.xml:3-9 | server ✓；「自建」= self-hosted 的中文化 |
| 深链 | deep-link 信息/事件、"Session deep-link:"（日志）、SessionDeepLink、深链处理 | MainActivity.kt:56,84,281,59；AppNotificationManager.kt:156 | 非 API 词（Android 导航概念） |
| 调试通道（#132） | 调试通道、"Debug channel"（日志）、debug_profile/debug_url extras | MainActivity.kt:91,163,270,347,373,368,423,425 | 非 API 词；中英双名并存 |
| 性能监测 | 开发用性能监测、性能监测 HUD、"PerfMon"（日志/TAG）、ChatPerfMonitor | MainActivity.kt:111,173,182,188 | 非 API 词；日志用 PerfMon 缩写 |
| 性能检测（=性能监测?） | “开发用性能检测系统核心” | FrameStatsWindow.kt:6 | 非 API 词；监测/检测 同义变体 |
| 帧预算/jank | 帧预算、超预算%、jank 事件/判定、frameBudgetMs | ChatPerfMonitor.kt:26,133,135；FrameStatsWindow.kt:9,27 | 非 API 词（渲染观测域）；jank 平台术语 |
| 稳态采样 | 稳态采样、STEADY（日志） | ChatPerfMonitor.kt:48,140,148 | 非 API 词 |
| 分片提交 | “滚动起止/分片提交/页面切换” | ChatPerfMonitor.kt:72 | 与 CONTEXT.md 渲染供给“分片”机制词呼应 |
| 崩溃日志 | 崩溃日志目录、crash_${timestamp}.txt | OpenCodeApp.kt:41,136；DateFormatters.kt:46-53 | 非 API 词 |
| 诊断日志/诊断页/诊断屏 | 持久化诊断日志、诊断数据库、诊断页、Diagnostics 屏、诊断日志行时间、诊断分享（DiagnosticsScreen.shareAsFile） | OpenCodeApp.kt:108,128；ChatPerfMonitor.kt:23；AppLogger.kt:181；DateFormatters.kt:42；res/xml/file_paths.xml:6 | 非 API 词；「页/屏」两种叫法；res 与 Kotlin 共享 Diagnostics 屏指称 |
| 语言镜像 | 语言镜像、reconcileLanguageMirror、语言漂移收敛、BCP 47 标签 | OpenCodeApp.kt:67,117,121,287；LocaleUtils.kt:8,12 | 非 API 词（i18n 域） |
| 通知抑制 | 通知抑制、抑制、shouldSuppress、被抑制的×××通知 | OpenCodeApp.kt:236；SessionFocusHolder.kt:40-49；OpenCodeConnectionService.kt:539,639,666,697 | 与 in-session audio feedback spec 用词一致 |
| 会话内提示音/会话内反馈 | 会话内提示音、会话内反馈（FeedbackTypes: TURN_COMPLETE/PERMISSION/QUESTION/ERROR） | OpenCodeConnectionService.kt:539,577；InSessionFeedbackPlayer.kt:20,131 | 非 API 词（#155 spec 域）；turn/question/permission/error ✓ |
| 任务完成通知 | 任务完成通知、TaskComplete 通知、showTaskCompleteNotification、TURN_COMPLETE、notification_tag_ready | OpenCodeConnectionService.kt:59,543,593；SessionFocusHolder.kt:45；InSessionFeedbackPlayer.kt:21；AppNotificationManager.kt:571 | 非 API 词；同一概念多种叫法 |
| 权限/问题/错误通知 | 权限请求、PermissionAsked、问题通知、QuestionAsked、SessionError、错误通知 | OpenCodeConnectionService.kt:598-718；AppNotificationManager.kt:314-485 | permission.asked/question.asked/session.error ✓ SSE 事件名 |
| 工具快照 | 工具快照、ToolSnapshotCache | OpenCodeApp.kt:254-255,295 | tool ✓ API 原词 |
| 工具卡片/任务卡片 | ToolCardResolver/DefaultToolCardResolver；DateFormatters.kt:15「任务卡片的时刻」 | ToolCardModule.kt:7-8,17；DateFormatters.kt:15 | tool ✓；「任务卡片」为中文变体（待裁决） |
| 终端 | 终端屏幕、终端模式、terminalKeyInterceptor、TerminalApi、终端工作区 | MainActivity.kt:108,123；ApiModule.kt:17-18,38；ConnectionLifecycleCoordinator.kt:131 | terminal ✓ API 原词 + 应用内终端功能 |
| 图片分享→附件 | 接收图片、预填附件、图片分享、sharedImagesFlow | MainActivity.kt:98,99,161,268 | 非 API 词；attachment 与 API part 相邻 |
| 版本探测/V1V2 | 版本探测、apiVersion、isV2、「V2 连接使用 V2 SSE 客户端，V1 使用原始 V1 客户端」 | MainActivity.kt:356,410-412；SseConnectionManager.kt:323-329 | 与 CONTEXT.md 版本 seam 词条同域；v1/v2 ✓ |
| SSE/REST 双路 | SSE 兜底/REST 兜底、REST 轮询、SSE 路径、REST_AUTHORITY/SSE_PRIORITY、REST 快照补漏、cursor 增量补漏对账 | AppNotificationManager.kt:394,742；OpenCodeConnectionService.kt:374-443；SseConnectionManager.kt:308,355-359,464 | SSE/REST ✓ API 传输词；补漏/对账为本地词 |
| durable.seq/gap | durable.seq gap 检测、每事件 seq 严格递增、连接代内 gap、/api/session/:id/event?after= | SseConnectionManager.kt:91-94 | seq/durable ✓ API 概念 |
| worktree/项目目录 | project.worktree、项目目录、跨项目 worktree 聚合、/api/project、canonical 兜底 | SseConnectionManager.kt:428,441；OpenCodeConnectionService.kt:475-481 | worktree/canonical ✓ API 项目字段 |
| location/directory | location（global/项目 location）、directory（x-opencode-directory 头）、会话 directory | OpenCodeConnectionService.kt:405-412,608-612 | directory ✓ API 参数/头；location 为服务器侧概念 |
| form/问题表单 | form/request、pending form、form id 去重、keyedAnswers、replyToForm、q$index key 合成 | OpenCodeConnectionService.kt:399-418,489-513 | form/question ✓ V2 API 词 |
| 流式 turn / turn | 成功完成的 turn、turn 完成、SessionIdle 且有输出、Streaming turn（CONTEXT.md）、RenderableTurn | InSessionFeedbackPlayer.kt:101,160；OpenCodeConnectionService.kt:543,577,591；MessageFingerprints.kt:30 | turn ✓；SessionIdle=session.idle ✓ |
| 流式（一词多义） | ①SSE 流式（输出流式传输）②“流式 ~20 次/s 全量编码”③“流式日志 50-90 条/s”④「SSE 流式/工具输出注入/完成替换变异」 | NetworkModule.kt:34-35；AppLogger.kt:197；MessageFingerprints.kt:25 | streaming 语境重叠；需裁决是否区分 |
| 流式输出翻倍 | “重复的后端连接会投递两份相同的 SSE 事件，导致流式输出翻倍”、MessagePartDelta 追加语义（backlog #34） | OpenCodeConnectionService.kt:285-288；ConnectionLifecycleCoordinator.kt:80-82 | message.part.delta ✓ 事件名 |
| 前台服务/FGS | 前台服务、FGS、dataSync FGS 6h 时限、Android FGS adapter | OpenCodeConnectionService.kt:50,53,153,233；ConnectionLifecycleCoordinator.kt:40 | 非 API 词（Android 概念）；缩写与中文全称并用 |
| WakeLock | partial WakeLock、wakeLock 周期续期、持锁/释放 | OpenCodeConnectionService.kt:41-44,60,762-802 | 非 API 词（Android 概念） |
| 重连/退避/冷却 | 自动重连、退避重连、重连守卫、SSE 读取超时冷却、aggressive/conservative/normal 重连模式 | SseConnectionManager.kt:34-37,100-104,274,295,508-515 | 非 API 词；三档英文配置值 |
| 预加载 | 会话预加载、preLoadSessions、项目间并发拉取 | SseConnectionManager.kt:39-40,55,303-320,409-447 | 非 API 词（性能优化域） |
| FSM/状态机 | 统一的 FSM 管线、SessionStateService、缺失=idle、不完整保护、reducer | SseConnectionManager.kt:441-443,472；OpenCodeConnectionService.kt:548 | idle ✓ API 会话状态；FSM/reducer 为架构词 |
| 深模块/宿主适配 | 协作深模块、宿主适配（Android FGS adapter）、单一决策点、真相源 | ConnectionLifecycleCoordinator.kt:26-44,69,151 | 与 CONTEXT.md 连接生命周期词条直接对应 |
| 纵深防御 | 纵深防御（serviceScope/lifecycle scope/SSE scope 三处同款注释） | OpenCodeConnectionService.kt:112-117；ConnectionLifecycleCoordinator.kt:63-65；SseConnectionManager.kt:74-80 | 非 API 词（工程习惯语） |
| 叠放 bug | 竞态取证埋点、叠放、ENTRIES 重建 | RaceProbe.kt:6,12,13 | 非 API 词（UI 列表层 bug 域） |
| 悬浮窗 HUD | 独立 overlay window、悬浮窗、测量污染、帧流 | PerfHudOverlay.kt:16,20,23；MainActivity.kt:114,190-197 | 非 API 词 |
| synthetic 双信号 | Message.User 的 role == "synthetic"（消息角色）与 Part.Text.synthetic（part 标志）两个不同信号 | OpenCodeConnectionService.kt:722-726；AppNotificationManager.kt:544,557 | synthetic ✓ API 字段；同词两处语义位置不同 |
| 指纹/签名 | 消息列表指纹/签名、结构签名（id 序列）、轻量内容指纹、尾部哈希 | MessageFingerprints.kt:9,15,25 | 非 API 词（缓存失效域）；指纹/签名同义并用 |
| 工具输出注入/完成替换 | 「SSE 流式/工具输出注入/完成替换变异」「step.ended 事件不含模型信息会触发字段变异」「REST 兜底也会补值」 | MessageFingerprints.kt:25,28-30 | step.ended ✓ API 事件名；modelId/providerId/agent ✓ |
| 统计栏 | 「统计栏丢模型不恢复」 | MessageFingerprints.kt:30 | 非 API 词（UI 概念，指 turn 信息栏） |
| 采集会话（调试） | 「在会话开始时调用 [reset]」「开始新的采集会话时调用」 | DebugLogger.kt:27,48 | 非 API 词；与聊天 session 撞词 |
| 仓库绑定（标识符术语） | SessionStateService→SessionStateRepository、MessageStore→MessageCacheRepository、DraftDataStore→DraftRepository | DomainModule.kt:45,63,75 | 同一实现类绑定多接口；Service/Store/Repository 后缀并存 |
| 待发消息 | PendingMessageRepository（标识符） | DomainModule.kt:16,25,78 | message ✓；pending 为本地状态概念 |
| Vcs/Mcp/Agent | VcsRepository、McpRepository、AgentRepository（标识符） | DomainModule.kt:12,19,66,72 | vcs/mcp/agent ✓ API 原词 |
| 协程取消语义 | 「取消必须传播，绝不吞」「取消后还在工作」、runCatchingCancellable/safeCatch | RunCatchingCancellable.kt:8-24；SafeCatch.kt:8-25 | 非 API 词（Kotlin 协程域）；两工具语义平行 |
| 剪贴板 | 系统剪贴板、copyToClipboard（双入口） | ClipboardUtils.kt:8,13 | 非 API 词（平台概念） |
| Provider 图标命名 | 74 个 ic_provider_<id>.xml，文件名= provider id snake_case（zai/zhipuai、moonshotai/kimi_for_coding、alibaba_cn 等 _cn 区域变体、*_coding_plan 套餐变体） | res/drawable/ic_provider_*.xml（74 个） | provider ✓ API 词；文件名是本地映射层 |
| 品牌图形词 | OpenCode O-ring silhouette、official favicon-v3.svg（anomalyco/opencode, MIT）、launcher safe-zone（~1.5x）、zero seam | ic_launcher_foreground.xml:6-8；ic_notification.xml:6-7；ic_launcher_background.xml:6 | 非 API 词（品牌/资源域，英文注释） |

## 失实注释

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| MainActivity.kt:341 | （非注释—日志字面量缺陷）`Received \${uris.size} shared image(s)` | `\$` 转义使日志恒打印字面量 `${uris.size}` 而非图片数量 | Phase 2 顺带修：去掉转义；不属注释修订但为事实缺陷 |
| di/ApiModule.kt:22 | 「6 个领域 API 接口的 Hilt 绑定」 | 类体内实际 7 个 @Binds：Session/Message/Terminal/**Shell**/Provider/File/System（L31-50）；Shell 为全限定名内联（L41），疑后加未更新计数 | 改「7 个」或去具体数字 |
| di/NetworkModule.kt:73 | 「OkHttp 专用：禁用响应体缓冲以支持流式传输」 | 其下代码为 `retryOnConnectionFailure(true)`（L74）——重连策略配置，与响应体缓冲/流式无关；疑模板/机翻残留 | 按实际语义重写或删除 |
| OpenCodeApp.kt:260-261 | （非失实—冗余条件备注）`level >= RUNNING_LOW || level >= UI_HIDDEN` | UI_HIDDEN(20) > RUNNING_LOW(10)，第二子句恒被覆盖，条件等价于 `level >= 10` | 顺带简化；注释本身不失实 |
| AppNotificationManager.kt:543-545 | 「提取最新的 N 条用户消息（非合成）用于 **MessagingStyle 显示**」 | findLatestUserMessages 全部调用点用于 setContentText 单行文本（L287,328,369,465）；本文件通知只用 InboxStyle（L213）与纯文本，无任何 MessagingStyle 用法 | 删「用于 MessagingStyle 显示」或改述为“通知正文预览”；疑早期实现残留 |
| OpenCodeConnectionService.kt:381-382 | startQuestionPolling KDoc：「当服务器断连（[connectionManager.isConnected] 返回 false）…时停止」 | 行内 2026-08-18 修复注释（L396-403）明确已移除 `if (!isConnected) break`：轮询只随用户连接意图停止（disconnect() 取消 pollingJobs），KDoc 描述修复前旧行为，与代码及其行内注释自相矛盾 | KDoc 改为“disconnect 取消 pollingJobs 即停；不检查 isConnected（2026-08-18）” |
| OpenCodeConnectionService.kt:53-64 | 类 KDoc：「显示任务完成和权限请求的通知」 | processEvent 实际路由四类通知：TaskComplete/Permission/**Question**/**Error**（L538-719）；KDoc 漏列后两类 | 补全四类或改泛述“事件通知” |
| OpenCodeConnectionService.kt:461 | （非注释—日志字面量缺陷）`(dir=${'$'}dir) failed: ${'$'}{it.message}` | `${'$'}` 转义使两处插值打印字面量模板而非值 | 顺带修：去掉 ${'$'} 写法 |
| InSessionFeedbackPlayer.kt:145-146,194-196 | 「独立事件去重（Q11）：per (server, session, type) 最近一次内容键」「同事件重放只响一次」 | L196 对 ERROR 型跳过去重比较（`type != FeedbackType.ERROR`），ERROR 只写键从不读键——其“只响一次”由 streak 门控（L192）承担，去重 map 对 ERROR 实际不起判重作用 | 注释补注“ERROR 型由 streak 门控，不参与内容去重比较” |
| util/RunCatchingCancellable.kt:18 | 「语义（与 safeCatch 一致）」 | safeCatch 捕获 Exception 交 fallback；本函数捕获 **Throwable** 包装 Result.failure——catch 范围不同（Exception vs Throwable），列出的三支语义一致但捕获宽度有差 | 补注捕获范围差异，或弱化「一致」为「语义平行」 |

## 待裁决冲突

1. 性能监测 / 性能检测 — 同指 debug 性能观测系统；ChatPerfMonitor.kt 用「监测」、FrameStatsWindow.kt:6 用「检测」。范围：debug/ 与 MainActivity。备注：中文同义异形，统一术语需选一。
2. 调试通道中英双名 — 注释用「调试通道」，日志用 "Debug channel"。范围：MainActivity.kt。备注：日志在 Diagnostics 屏可见，属 UI 文案。
3. 诊断页 / 诊断屏 / Diagnostics 屏幕 / DiagnosticsScreen — 同一界面多种叫法（中文名×2 + 类名）。范围：AppLogger.kt:181、ChatPerfMonitor.kt:23、res/xml/file_paths.xml:6、AGENTS.md。
4. 「流式」一词多义 — ①SSE 消息流式输出 ②流式高频日志/编码 ③指纹注释中的流式变异。范围：NetworkModule.kt:34、AppLogger.kt:197、MessageFingerprints.kt:25 与全部 SSE 域文件。
5. Service/Store/Repository 后缀并存 — 同一实现类绑定多接口（ServerRepositoryImpl→3 接口；SessionStateService→SessionStateRepository；MessageStore→MessageCacheRepository；DraftDataStore→DraftRepository）。范围：di/DomainModule.kt。备注：命名分层约定，术语表可登记。
6. 任务完成通知多叫法 — 「任务完成通知」/「TaskComplete 通知」/showTaskCompleteNotification/FeedbackType.TURN_COMPLETE/notification_tag_ready。范围：service/ 三文件 + strings 资源键。备注：turn ✓ API 词，「任务」为口语转写；turn 完成与“任务完成”是否同一概念需裁决。
7. 「会话/session」中文注释混用 — 「子会话」与「子 session/父 session」在同文件并存。范围：OpenCodeConnectionService.kt。备注：中文注释统一时的用词基准问题。
8. synthetic 双信号 — Message.User.role == "synthetic" 与 Part.Text.synthetic 是两个不同字段却共用一词。范围：OpenCodeConnectionService.kt:722-726、AppNotificationManager.kt:557。备注：均为 API 原词但语义位置不同，注释需指明。
9. location / directory / 项目目录 — 三词交叉指 OpenCode 位置概念（x-opencode-directory 头、form 的 location、项目 directory/canonical/worktree）。范围：OpenCodeConnectionService.kt:405-481、SseConnectionManager.kt:428。备注：directory/worktree/canonical ✓ API 字段；location 为服务器侧 form 概念；中文「项目目录」覆盖两者。
10. 「会话」一词多义 — 聊天会话（OpenCode session）vs DebugLogger「采集会话」（调试日志采集期，DebugLogger.kt:27,48）。范围：util/DebugLogger.kt。备注：与 session 无关，中文注释统一时需消歧。
11. 「任务」一词多义 — ①任务完成通知的“任务”（= turn 完成）②DateFormatters.kt:15「任务卡片」（疑指 tool 执行卡片=ToolCard）③ToolCardModule「工具卡片」。范围：service/、util/、di/。备注：「任务卡片」与「工具卡片」是否同一概念需裁决。
12. 指纹/签名同义 — 「消息列表指纹/签名工具」标题并列，正文「结构签名」与「内容指纹」分工。范围：MessageFingerprints.kt:9,15,25。备注：已有隐式分工（签名=结构、指纹=内容），术语表可选是否固化。
13. 「服务器」双指 — 远程 OpenCode server（连接对象）vs 本地「服务器配置」（DataStore 中的 ServerConfig 存储）。范围：res/xml/backup_rules.xml:2、data_extraction_rules.xml:2、network_security_config.xml。备注：中文注释中两者都可简称“服务器”，需语境限定。
14. Provider 命名双品牌/区域/套餐变体 — 同一公司双品牌（zai↔zhipuai、moonshotai↔kimi_for_coding）、区域变体（alibaba_cn、minimax_cn、siliconflow_cn、moonshotai_cn）、套餐变体（zai_coding_plan、zhipuai_coding_plan）在 74 个图标文件名并存。范围：res/drawable/ic_provider_*.xml。备注：文件名必须与 provider id 字符串对应（本地映射层），非注释问题；若 UI 层显示 provider 名，需裁决显示名规范（不在本范围）。

## 语言现状统计

- Kotlin 27：中文注释 24（其中 5 个日志文案为英文/中英混合：MainActivity、ChatPerfMonitor、AppNotificationManager、OpenCodeConnectionService、AppLogger）· 无注释 3（CoroutinesModule、DomainModule、ToolCardModule）· 纯英文/混合 0。
- res 86：中文注释 4（xml/ 配置 4 个）· 英文注释 3（ic_launcher_background/foreground、ic_notification）· 无注释 79（74 provider 图标 + 2 mipmap + 2 text_select_handle + themes.xml）。
- 合计 113：中文 28 · 英文 3 · 无注释 82。
