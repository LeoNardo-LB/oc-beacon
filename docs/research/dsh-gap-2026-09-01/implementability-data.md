# DSH 差距项·数据/传输层可实现性审计

> 日期：2026-09-02 · 只读静态走查 · 基线 master HEAD `52dd8752`（含 #296 `host/remote-event` 解包 `77231e26`、#297 /compact 改 `commands/execute`——两项已完成，不在本清单）。
> 方法：DAC/DEM/传输层逐行比对 + 主差距文档 §0/§9 与三份证据文档（api-gap.md / app-inventory.md / fe-inventory.md）交叉引用。UI/UX 成本不在本审计范围，仅在影响评级判断处带过。
>
> 路径缩写：**DAC** = `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshApiClient.kt`、**DEM** = 同目录 `DshEventMapper.kt`、**RC** = 同目录 `DshRpcClient.kt`。file:line 相对仓库根。

---

## 0. 传输基建通用性验证（全部差距项的公共前提）

**(a1) typert 斜杠端点基建通用 —— 证实。** RC 的两个入口 `call()`（RC:44-59，value 恒对象）与 `callJson()`（RC:66-79，value 可为数组/任意 JsonElement）都把 `method` 当自由字符串直接拼 URL（`url()` RC:134-135 = `baseUrl + "/api/" + method`），payload 是任意 `JsonObject`（信封 `DshEnvelope.ClientRequest(rpcId, method, payload)`）。`{"args":{...}}` 包装**不在基建层**，由调用点手工组装：`commands/execute`（DAC:210-216、DAC:279-290）、`commands/list`（DAC:845-847）。因此新增一个 typert 端点 = **一处 `rpc.call/callJson` 调用 + 手工 args 包装**，无任何 per-method 注册表或 schema 登记。响应形状二选一的判据：数组值（如 `commands/list` 返回 `CommandDescriptor[]`，DAC:851）用 `callJson`，对象值用 `call`。

**(a2) 旧式点分路由基建通用 —— 证实。** 同一 `rpc.call(conn, "workspace.create", payload)` 形态，`session.*`/`goal.*`/`settings.*` 等 27 个已消费方法全部走它；同形 mutation 收口先例见 goal 六法的 `refMutation`（DAC:386-397）。错误面恒 HTTP 200 + `result.error` 闭集码，`DshApiError` 的 39 码分类表已含 `workspace-name-conflict`、`credential-rejected`、`model-discovery-failed` 等未消费域的码（DshApiError.kt:64-97）——新方法即便命中这些码也自动获得语义分类，无需扩表。

**(a3) 事件面。** 双 WS 下行帧统一进 `DEM.mapFrame`（DEM:73），未识别 `host/*` 帧落 `Ignored(HOST_WORKSPACE)`（DEM:369-379），未识别 SessionEvent 类型按 `ignorable` 旗标决定是否拒绝历史重建（DEM:554-560）——新增事件分支是**纯加法**，不动既有折叠判据。真正的接线成本在下游：新 `SseEvent` 类型需要 `EventDispatcher` bind（EventDispatcher.kt:141-157 模式）+ handler 状态折叠；既有反例：`goal/change` 曾因缺 bind 静默丢弃（EventDispatcher.kt:148-152 注释「一修双愈」）。

---

## 1.【P1】@ 引用系统数据面（fileReferences/list + sessionReferenceResolver/candidates）

**(a) 传输层成本：极低。** 两个只读 typert 端点，与 `commands/list` 完全同形：`fileReferences/list` payload `{"args":{"agentId":<sessionId>}}`、`sessionReferenceResolver/candidates` 同（api-gap.md:89-91；活体空参探测确认存在 api-gap.md:94）。`agentId == sessionId` 的等价关系已在 DAC:277 注释定音（dsh-commands typert 的 agent 参数 source=lookup）。两处 `callJson` 各约 20 行。

**(b) 数据/域层增量。** DAC 新增两方法（返回 `JsonArray`/`JsonObject` 容忍解析，参照 `listCommands` DAC:851-861 的 mapNotNull 容错）；`ChatRepository` 接口 + Impl 增两方法（DSH 专属方法先例：`completeGoal` ChatRepository.kt:247 / ChatRepositoryImpl.kt:402）；domain 新建 1-2 个小数据类（文件引用条目 / 候选项，含 kind=file|directory|session 判别）。DEM **无**改动——两服务均为纯拉取，无对应事件（api-gap.md §2 全事件清单无 fileReferences/references 帧）。注意：Android 现无 @-mention 补全系统可复用（`domain/model/Annotation.kt` 是 FileViewer 代码选区标注，语义无关）。

**(c) 工程量：S（数据面）。** 两次调用 + DTO + 仓库方法，半天内含单测。composer 的 @ 触发/弹层 UI 是另一量级（M），不在本评级。

**(d) 依赖：无。** 是第 8 项（deliverables）的上游。

## 2.【P1】轨迹视图数据面（session.history 解析完整度）

**(a) 传输层成本：零增量。** `session.history` 已在：分页 `listMessages`（DAC:517-550，beforeSeq/maxMessages 数字排他游标 + hasMore → nextCursor DAC:543-548）、诊断全量 `listMessagesRaw`（DAC:552-555）、断线对账回填复用（DshConnectionOrchestrator.kt:79）。

**(b) 数据/域层增量：接近零。** 响应行 `HistoryEntry={event,view?}` 的解包已实现——`historyEntryRows`（DAC:1246-1249）提取 entries/events 数组，`DshHistoryFolder.fold`（DshHistoryFolder.kt:45-82）自动解包 event 并**有意丢弃 view**（DshHistoryFolder.kt:41-44「view 是宿主渲染意图，不持久化」；DEM:96-98 实况帧同规则）。事件解析完整度：49 型内层分派（DEM:446-563）Tier-1 转录事件全解析，每行 seq/time 保留（DEM:449-450），tokenUsage/subagentTiming/sessionStats 等轨迹检查器所需投影已消费（DEM:189-273）。官方 Web 轨迹视图同样是**从事件台账自建**而非消费 view（fe-inventory.md:181-182：主台账=index/event/content，检查器=token 用量/时长/Input/Output/Timing）。缺口仅在：无逐 turn/step 聚合的台账数据结构（需新写一个从已折叠 SseEvent 序列构建台账的纯函数聚合器）。

**(c) 工程量：S（数据面）——传输零增量，唯一增量是台账聚合器；完整轨迹视图（时间轴缩放平移）M，大头全在 UI。**

**(d) 依赖：无。** 「缺的是不是只是 UI」的答案是：数据面只差一个只读聚合器，其余全是 UI。

## 3.【P1】工作区/会话组织（workspace 六方法 + 四 host 帧）

**(a) 传输层成本：低。** 六个旧式写方法（workspace.create/rename/delete/insertBefore/insertSessionBefore/archiveSession，api-gap.md:44-49），每个一处 `rpc.call`，与 goal 六法同形。四帧（host/workspace-changed、workspace-removed、workspace-order-changed、archived-sessions-changed）已随 events.host 到达，当前全部落 `Ignored(HOST_WORKSPACE)`（DEM:369-379 + DshIgnoreReason.HOST_WORKSPACE DEM:1127-1128）。

**(b) 数据/域层增量：本次清单中最大。** ① DAC 六方法（S 级）；② DEM 四个新帧分支——workspace-changed 是**整快照**姿态（api-gap.md:108），应映射为新 SseEvent（如 WorkspaceListChanged/ArchivedSessionsChanged），参照 `session/queue` 整快照先例（DEM:300-313）；③ EventDispatcher bind + 新 handler 状态存储（DshQueueStore/DshJobsStore 整快照 last-wins 先例，app-inventory.md §3.5）；④ domain 建模缺口是实质工作：现 `Project` 只有 id/worktree/name 三字段（DAC:976-984），无排序/归档/分组概念，Session 无 archived 标志；`workspace.list` 现仅被当作「项目列表 + 目录树根」消费（DAC:935-951 resolveRootPath 取首个 path；app-inventory.md §5.15 明记多 workspace 未建模）；⑤ 回执形状需 E2E 回填（对照 `mapSessionEcho` DAC:485-505 的容忍解析先例）。

**(c) 工程量：M。** 传输与方法面半天内，但四帧接线 + 多工作区/排序/归档的域建模 + handler 是 1-3 天的主体；sidebar 分组/归档/拖排序 UI 另计。

**(d) 依赖：无前置。** mutation 不回喂客户端状态（goal 同款「状态由事件/投影帧驱动」契约，DAC:333-334），所以六方法与四帧接线可并行开发、以帧驱动收口。

## 4.【P1】steer 插话直发（session.prompt mode 参数）

**(a) 传输层成本：零。** `session.prompt` 已在（DAC:600-633），`mode` 是 payload 普通字段且**必填**（缺席整单拒绝，DAC:627 注释），当前硬编码 `put("mode", "queue")`（DAC:629），注释自认「steer=注入进行中轮次，留给后续 UX」。

**(b) 数据/域层增量：极小但有一处接口签名抉择。** ① `MessageApi.promptAsync` 接口签名无 mode 参数（MessageApi.kt:36-43）——两个选项：给接口加带默认值的参数，或走 DSH 专属方法先例（`readAttachment` DAC:1259-1275 即接口外的 DshApiClient 公开方法）；② 调用链（ChatRepository.sendPrompt → ChatViewModel 发送路径）透传 mode 或按会话状态（busy→steer）判别；③ DEM **无**改动——steer 后的排队项经 `session/queue` 帧回显，`placement` 原样透传且 `QueuedInboxItem.placement` 是裸字符串（queued/steering/context，DEM:978-999），天然容忍；④ 错误面已备：`steer-unavailable`（仅 running+next-turn 有效）→ `QueueMutationResult.SteerUnavailable` 映射与错误码分类均已存在（DAC:726-728、DshApiError.kt:75、QueuedInboxItem.kt:45）。`updateQueue` 的既有 steer 动作（对已排队项）与本项（直发即 steer）语义互补不冲突（DAC:686-739）。

**(c) 工程量：S。** 单字段 + 状态判别 + 一处签名调整，半天内；真正的成本是 UX 决策（何时提供 steer 直发 vs 排队——忙碌双键发送入口已存在，app-inventory.md §3.3）。

**(d) 依赖：无。**

## 5.【P1】子代理续聊（subagent.prompt / interrupt / history）

**(a) 传输层成本：低。** 三个旧式方法（api-gap.md:34-37：history 读、prompt 写、interrupt 写），各一处 `rpc.call`。`subagent.list` 已在且其 payload/响应解析先例完整（DAC:424-445，含容错映射）。

**(b) 数据/域层增量：中等，有一个关键复用点。** ① DAC 三方法；**关键复用**：`subagent.history` 返回子会话事件日志，与 `session.history` 同构 → 直接走 `historyEntryRows` + `DshHistoryFolder.fold`（DshHistoryFolder.kt:45）+ `DshMessageAssembler.assemble` 现成管线，产出 `MessagePage`——不需要任何新映射器；② 仓库层三方法 + AgentSheet 行动作接线（AgentSheet 树已就绪，app-inventory.md §3.3：subagent.list L2 懒加载 + 行点击跳子会话 #242 守卫）；③ 域语义坑位已勘察：对子会话直接 `session.prompt` 会被拒（agent-busy，app-inventory.md:200）——这正是 `subagent.prompt` 独立方法存在的理由，发送路径必须按目标会话是否子代理分派方法；④ DEM 无强制改动（子代理树刷新现状靠轮次事件间接驱动 + subagent.list 轮询；`subagent/descriptor` 事件维持 Ignored 不阻塞本项，app-inventory.md §5.9）。

**(c) 工程量：M。** 传输 S 级；history 复用 fold 后成本可控，但 AgentSheet 交互（行内发送/中断入口）、子会话发送分派、错误降级（subagent-not-found → DshErrorCategory.NotFound 已分类 DshApiError.kt:70）合计 1-3 天。

**(d) 依赖：无硬依赖。** 与第 4 项共享「向忙碌 agent 注入指令」的 UX 语义，建议同一批次定 UX。

## 6.【P1】goal.complete UI 支撑

**(a) 传输层成本：零——全链已在。** `goalComplete` = `refMutation(conn, "goal.complete", …)`（DAC:372-373，与 pause/resume 共享形态 DAC:386-397）；仓库层 `ChatRepository.completeGoal`（ChatRepository.kt:247）→ `ChatRepositoryImpl.completeGoal`（ChatRepositoryImpl.kt:402-405）。goal.complete/clear 的数据面**均已完整**。

**(b) 数据/域层增量：零。** 缺口全在 UI 线程：ChatViewModel 有 createGoal(:767)/editGoal(:781)/pauseGoal(:791)/resumeGoal(:801)/clearGoal(:811) 五个包装，**无 completeGoal 包装**；GoalSheet 回调只有 onEdit/onPause/onResume/onClear（GoalSheet.kt:51-54），无 onComplete 按钮。CAS ref 取法照抄 `currentGoalRef()`（ChatViewModel.kt:753-757）。注意 Web 语义：complete 后 phase=="complete" 走空态创建表单（GoalSheet.kt:43、61 已按此渲染）——按钮触发后无需额外状态管理，投影刷新由 `goal/change` → `SessionGoalChanged` 既有链路（DEM:479-487）自动完成。

**(c) 工程量：S。** 一个 ViewModel 包装 + 一个按钮，2 小时级；本次清单最便宜的一项。

**(d) 依赖：无。**

## 7.【P2】消息反馈（messageFeedback 三方法 + feedback/record 事件）

**(a) 传输层成本：低。** 三个 typert（list 读 `{"args":{"sessionId"}}`、put/delete 写，api-gap.md:86-88；活体探测确认存在 api-gap.md:94）。list 走 `callJson`/`call` 视响应形状定；put/delete 照 `commands/execute` 的对象值形态。

**(b) 数据/域层增量：小。** ① DAC 三方法 + domain 小模型（messageId + positive/negative + 备注）；② 仓库方法；③ DEM 一条新分支：`feedback/record` 现落具名忽略 `Ignored(LOG_ONLY)`（DEM:519，与 request/header 等同组）——需拆出为独立分支映射新 SseEvent（反馈回显）或触发重查 `messageFeedback/list`；新 SseEvent 需 EventDispatcher bind（§0(a3) 的 goal/change 反例警示：漏 bind 即静默丢弃）；④ UI：气泡 👍/👎 + 历史加载时从 list 回填状态。wire 形态（record 载荷字段）需实况取样确认。

**(c) 工程量：S（数据面：三调用 + 一事件分支半天内）；整功能 M（气泡操作 + 状态回填 UI）。**

**(d) 依赖：无。**

## 8.【P2】产出文件行 deliverables

**(a) 传输层成本：零（复用第 1 项）。** 数据源 = `fileReferences/list`（api-gap.md:146 官方 deliverables UI 实证调用同一端点）+ 已有的 tool result 解析（`flattenToolResultOutput` DEM:841-853、file 块 → `Part.File` DEM:763-781）。

**(b) 数据/域层增量：小。** 依赖第 1 项的 DAC 方法与 DTO；增量只是一个客户端侧过滤/聚合（从引用清单提取产出类条目 + 按 turn 归组）的轻量聚合器，无新 RPC、无 DEM 改动。官方形态（轮尾 chip + 内联提及可点，主差距文档 §9-8）纯 UI。

**(c) 工程量：S（数据面，前提是第 1 项已做）。**

**(d) 依赖：第 1 项（fileReferences/list 方法 + DTO 共享）。**

## 9.【P2】Plan 模式（plan/mode 事件 + plan-review 数据）

**(a) 传输层成本：零新增 RPC。** `plan/mode` 是 SessionEvent，已到达并落具名忽略 `Ignored(POLICY_STATE)`（DEM:510-511；DshIgnoreReason.POLICY_STATE DEM:1151-1152 注释明记「plan/mode（permission/sandbox/approval 已映射）」——即三兄弟已映射、唯独 plan 未接）。plan-review 数据面**已在**：官方把 plan-review 归入 pendingInteraction 的 question 类（fe-inventory.md:232），走 mux `question/requested|resolved` 帧——Android 已全量消费该对帧（DEM:136-158）并接通 /api/respond 回程。

**(b) 数据/域层增量：小。** ① DEM：把 `plan/mode` 从三型合并分支拆出 → 新 SseEvent（如 SessionPlanModeChanged）→ SessionEventHandler 字段折叠（permission 三 knob 的 last-wins 字段合并是直接先例，DEM:504-509 + app-inventory.md §3.5）+ Session 模型加字段；② plan-review 专卡是既有 QuestionAsked 之上的 UI 特化（识别 plan-review 意图 + Chat about it/Refuse/Approve 决策行，fe-inventory.md:193）——回程键（rpcId）、answers/cancelled 语义全部现成；③ 事件 wire 载荷（enabled/mode 字段名）需实况取样。

**(c) 工程量：S（事件分支 + 投影折叠半天）；plan-review 专卡 UI M。**

**(d) 依赖：无。**

## 10.【P2】重试 / turn-error / max-tokens 呈现

**(a) 传输层成本：零新增 RPC。** 相关事件全部已到达：`llm/retry`、`llm/retry-started` 落 `Ignored(LLM_RETRY)`（DEM:514-515，实测 3,566 次）；`llm/failover` 落 `Ignored(LLM_FAILOVER)`（DEM:530）；`stream/error` 在帧面（DEM:314）与会话事件面（DEM:550）两处均 Ignored。官方在 client-runtime 维护「重试/turn-error/max-tokens 投影」（fe-inventory.md:233），max-tokens 轮有专门警告节点 + 「发送 continue 可续」（fe-inventory.md:99）。

**(b) 数据/域层增量：小，风险在 wire 取样。** ① DEM：把 `llm/retry(-started)` 从合并忽略分支拆出 → 映射 `Part.Retry`——**域模型已备**（Part.kt:228-237：attempt/error/time + errorMessage，V2 先例，UI 渲染链现成），只需发 `MessagePartUpdated(Part.Retry)`；注意 part id 契约（PartIdContract）需给 retry 定一个 kind（#230 delta kind 推断承重约定，DEM:40-42）；② max-tokens 的承载（独立事件还是 turn/end 变体还是投影键）需历史样本确认后建分支；③ `stream/error` 需会话级错误面（现仅日志）。所有新分支都是纯加法，不影响 fold 拒绝判据（这些类型已是具名忽略，非 UNKNOWN_UNIGNORABLE）。

**(c) 工程量：S–M。** llm/retry 分支 S；turn-error/max-tokens 依赖 wire 取样 + 「发送 continue」入口（复用 prompt 通道，文本协议待确认）把尾部推到 M。

**(d) 依赖：无。**（「continue」复用第 4 项的 prompt 通道但不构成硬依赖。）

## 11.【P2】压缩呈现（compaction/start、compaction/summary）

**(a) 传输层成本：零。** 事件已在到达：`compaction/end` 已映射 `SessionCompacted`（DEM:473-474，完成 snackbar 链路通）；`compaction/start`、`compaction/summary`、`compaction/prune` 三型落合并忽略 `Ignored(COMPACTION)`（DEM:475-476）。

**(b) 数据/域层增量：小。** ① DEM 拆分支：start → 进度态 SseEvent；summary → 摘要展示——**域模型已备**：`Part.Compaction` 带 summary（「分割线卡片可展开查看」）与 failed 字段（Part.kt:214-225，V2 压缩分割线先例），DSH 侧只需产出对应 part；② fold 安全：三型均已是具名忽略，拆分支不触发拒绝重建判据；③ summary 载荷字段名需取样确认；prune 维持忽略。

**(c) 工程量：S。**

**(d) 依赖：无。**

## 12.【P2】工具卡类型化（terminal/diff/search 子类型）

**(a) 传输层成本：零。** `tool/call|result` 已全量消费（DEM:685-720 / DEM:723-752）。现有解析字段：callId、name、arguments 原始串（可解析时同步展开 input map，DEM:690-692）、error、输出文本展平。

**(b) 数据/域层增量：中。** 关键事实：**服务端 tool/call|result 无子类型字段的证据**——官方 Web 的 terminal/read/diff/search/web/todo/question/code-dispatch 分卡是**按工具名客户端分类**（fe-inventory.md:165：内置卡 + generic 兜底「按工具名分类 search/read/shell/write/edit/code/generic」）。因此 Android 无需等协议，同类做法：① 工具名 → 卡类型的客户端分类器（domain 新枚举或 UI 层纯函数）；② `Part.Tool` 现有结构（callId/tool/state，ToolState.Pending.input 已是解析后的 map）足以承载分类结果，可加一个非侵入 kind 字段或纯 UI 判定；③ 逐类型的参数/输出结构化解析（diff 卡要解析 patch、search 卡要解析命中列表）是逐类型增量工作；④ 大结果 spill 提示（terminal 卡「Full grep result stored at…」文案模式，fe-inventory.md:168）需输出文本模式识别。

**(c) 工程量：M。** 单类型 S，但 terminal/diff/search 等 4-6 类的参数/输出解析 + 分卡 UI 合计 1-3 天；数据层部分约占三成。

**(d) 依赖：无。**

## 13.【P2】session.models 与 session.search

**(a) 传输层成本：低。** 两个旧式读方法，各一处 `rpc.call`。session.search 本部署禁用（openAt="never"，活体实证 api-gap.md:24；官方同样限 20 条结果，fe-inventory.md:233）。

**(b) 数据/域层增量：小。** ① session.models（会话级可用模型表，api-gap.md:26）：DAC 方法 + DTO + 接入模型选择器（现状用全局 `llm.providers+llm.models` 拼目录，DAC:1059-1115）——增量是「目录来源多一路、按会话收窄」，无域模型改动；② session.search：部署开关探测 = 连接期一次空参调用 + 错误码判别（禁用态的具体错误码需一次取样），结果写进 `ServerConnection` capabilities 块（ServerConnection.kt:106-129 的 DSH 能力位登记先例）——搜索框把本地 title 过滤（DAC:111-113）升级为「服务端可用则服务端搜索 + 降级本地」。

**(c) 工程量：S（各自半天内，含探测）。**

**(d) 依赖：无。**

## 14.【P2】loopback 疑似面数据成本（只评成本，可达性另有人验证）

**(a) 传输层成本：全部同形、极低。** credentials.describe/set/unset、llm.discoverModels、settings.openDocument/update/replace、agentPreset.read/copy/openDocument/remove 均为旧式点分（api-gap.md:53-65）；pluginInventory/list 为 typert 零参数（api-gap.md:90，活体探测 ok=true api-gap.md:94）——前者各一处 `rpc.call`，后者一处 `rpc.callJson(conn, "pluginInventory/list", {"args":{}})`。若远程可达，从「不可用」翻转为实现只涉及：`setProviderApiKey`/`removeProviderCredential`（现为 `unsupported("credentials.write")` DAC:1138-1142）与 `updateConfig`/`updateGlobalConfig`（现为 `unsupported("settings.write")` DAC:1206-1210）两个占位翻正。

**(b) 数据/域层增量：每方法组一个小 DTO + 仓库方法，无新技术难点。** 关联事件已有落点：#296 之后 `credentials/reference-updated`、`settings/document-updated`、`llm/adapters-updated` 三个转发事件已解包但落 `Ignored(REMOTE_EVENT)`（DEM:388-412）——对应功能落地时各加一条分派即可（#296 的 mapForwardedRemoteEvent 白名单结构就是为此预留的）。llm.discoverModels 有专属错误码 `model-discovery-failed` 已分类（DshApiError.kt:92）。

**(c) 工程量：数据面 S（每方法组半天级）。** 但若 Host 栅栏把远程调用钉死（fe 源码判读为 loopback-pinned，主差距文档 §0 🔍 注），全部成本作废、收益为零——**可达性结论先行，成本评估在后**。

**(d) 依赖：可达性验证（他人负责）是唯一前置。**

---

## 汇总表

| # | 项 | 传输就绪? | 域层增量 | 工程量（数据面） | 依赖 |
|---|---|---|---|---|---|
| 1 | @ 引用数据面 | ◐ 基建就绪，两方法未写 | 2 DAC 方法 + 2 仓库方法 + 1-2 小 DTO；DEM 无改动 | **S** | 无（8 的上游） |
| 2 | 轨迹视图数据面 | ✔ session.history 全通 | 仅缺台账聚合器（fold 产物之上）；view 有意丢弃无需补 | **S**（完整视图 M，UI 为主） | 无 |
| 3 | 工作区/会话组织 | ◐ 六方法一处 each；四帧到达未解 | 6 方法 + 4 帧分支 + handler/存储 + **Workspace/归档域建模（最大项）** | **M** | 无 |
| 4 | steer 直发 | ✔ mode 字段已在，硬编码 queue | 1 签名调整 + 状态判别；错误面/queue 回显全备 | **S** | 无 |
| 5 | 子代理续聊 | ◐ 三方法未写，形态同 list 先例 | 3 方法 + history 复用 fold 管线 + AgentSheet 接线 + 发送分派 | **M** | 无（UX 与 4 同批佳） |
| 6 | goal.complete | ✔ 全链在位 | **零**——缺 GoalSheet 按钮 + VM 包装 | **S**（最便宜） | 无 |
| 7 | 消息反馈 | ◐ 三 typert 未写 | 3 方法 + feedback/record 拆分支 + bind + 小模型 | **S**（整功能 M） | 无 |
| 8 | deliverables | ✔ 数据源复用 1 | 轻量聚合器；无新 RPC/DEM | **S** | **第 1 项** |
| 9 | Plan 模式 | ✔ 事件与 question 管线全在 | plan/mode 拆分支 + Session 字段折叠；plan-review=QuestionAsked 特化 | **S**（专卡 M） | 无 |
| 10 | 重试/turn-error/max-tokens | ✔ 事件全到达（三处 Ignored） | llm/retry→Part.Retry（域模型已备）；max-tokens wire 待取样 | **S–M** | 无 |
| 11 | 压缩呈现 | ✔ 事件全到达 | 拆 compaction/start/summary 分支；Part.Compaction.summary 已备 | **S** | 无 |
| 12 | 工具卡类型化 | ✔ tool/call\|result 全解析 | 客户端按名分类器 + 逐类型参数/输出解析（服务端无子类型字段，Web 同款做法） | **M** | 无 |
| 13 | session.models/search | ◐ 两读方法未写 | 各 1 方法；search 加 capabilities 探测位 | **S** | 无 |
| 14 | loopback 面 | ◐ 全同形，占位翻正即可 | 每方法组小 DTO；三转发事件分派已预留落点 | **S**（可达性未决） | 可达性验证（他人） |

**总计**：无 L 项。S×9、S–M×1、M×3（3/5/12）。清单中没有任何一项需要新传输通道、新信封形态或新映射器框架——全部增量都在「一处调用 + 一条分支 + 一份 DTO」粒度。

## 关键事实（审计最重要发现）

1. **typert 基建完全通用（任务待验证项，证实）**：`method` 是自由字符串直拼 URL（RC:134-135），`{"args":{...}}` 包装在调用点手工完成（DAC:210-216、845-847），`call`/`callJson` 二入口覆盖对象/数组值响应。新增 typert 端点成本 = 一个方法调用；旧式点分同理，且 39 码错误分类表已预登记未消费域的码（DshApiError.kt:64-97）。
2. **两个「缺的只是 UI」实锤**：goal.complete（数据面 100% 在位，DAC:372 + ChatRepositoryImpl:402，缺的只是按钮）与轨迹视图（session.history 分页/折叠/游标全通，view 字段是有意丢弃且官方 Web 轨迹同样从事件自建）。
3. **事件面最大隐患不是解析而是下游 bind**：新 SseEvent 漏注册 EventDispatcher 即静默丢弃——goal/change 曾中招（EventDispatcher.kt:148-152）。项 3/7/9/10/11 的新帧分支都要走「DEM 分支 + bind + handler 折叠」三步，不能只做第一步。
4. **域模型已有三处 V2 预留可直接复用**：`Part.Retry`（Part.kt:228）、`Part.Compaction.summary/failed`（Part.kt:214-225）、`QueuedInboxItem.placement` 裸字符串容忍（QueuedInboxItem.kt）——项 10/11/4 的域层几乎是零新建。
5. **项 12 无需等协议**：官方 Web 工具卡分卡就是按工具名客户端分类（fe-inventory.md:165），服务端 tool/call|result 无子类型字段证据。
6. **项 4 有一个非显然的接口抉择**：`MessageApi.promptAsync` 签名无 mode（MessageApi.kt:36-43），扩接口或走 DSH 专属方法先例（`readAttachment` DAC:1259）需定点裁决。
