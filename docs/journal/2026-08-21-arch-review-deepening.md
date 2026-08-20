# arch-review-deepening（2026-08-21）

> 状态：进行中
> 关联：CONTEXT.md（渲染供给术语已登记）· HTML 报告 /tmp/architecture-review-2026-08-20T20-42-30.html（临时件，内容已收录于下）
> 来源：用户发起 /improve-codebase-architecture · 三路并行子代理走查 + git 热点定向

## 调研方法

- 热点定向：近 60 天 git churn（ChatMessageList 76 次/30 天居首、EventDispatcher 38、ChatViewModel 47）→ 三路子代理并行走查（A 聊天 UI 管线 / B 数据事件管线 / C 连接生命周期+UseCase）
- 词汇表：/codebase-design（module/interface/seam/adapter/depth/locality/leverage）；对每项疑似 shallow 做 deletion test
- 产出 6 候选 + 顺手清理清单；候选 1 已 grilling 定案（Q1-Q11 全按推荐）并实现

## 六候选总表（证据摘要）

| # | 候选 | 强度 | 核心证据 |
|---|------|------|---------|
| 1 | RenderSupplyCoordinator 抽出 | Strong | ~190 行 LaunchedEffect 内嵌（ChatMessageList 536-727），interface 宽度 0；五条隐含约束（流式禁预解析/双门控/partId 反查/LRU 联动/display 粒度窗口）全靠注释；buildChatEntries/computeChunkPlan 在 test/ 0 引用；四轮竞态修复（bf3d1cf7→94f7a968→8347acd0→88774278）全靠真机 RaceProbe |
| 2 | 每服务器连接生命周期 module | Strong | 状态 ≥6 module 分持；teardown 双份（OpenCodeConnectionService 315-337 vs 381-402）；1309 行 20+ 带日期竞态注释（RS-001~005、#110~#133） |
| 3 | 未读红点时钟域收进 interface | Strong | 三铁律散 3 层 6 文件；静默泄漏路径：markSessionIdle（MessageEventHandler 1087-1148）写客户端 now → recomputeMaxCompleted 扫描混入服务器域水位线 |
| 4 | V1/V2 seam 按域翻转 | Worth exploring | 79 个 if(conn.apiVersion.isV2) 每方法站点（SessionApi 23 处）；isV2 泄漏进 SessionStateService:301/642、MessagePaginationUseCase:101/201/232 |
| 5 | ChatViewModel delegate 重组 | Worth exploring | 假 seam：UI 消费 98 成员/147 调用点，40+ 行 1:1 转发，delegate 间 sink 回写+lambda 互接；MessageDataDelegate 12 构造参数 |
| 6 | SessionStateService 8 旋钮→1 协作者 | Worth exploring | 8 个可缺省 var 回调（SessionStateService 79-111）只在 EventDispatcher.init 接线；漏接静默降级（directoryResolver 默认 null→REST 打错路由） |

顺手清理（deletion test 全正）：ChatMessageList 双调用点合一（ChatScreen 812-866）；三个纯转发壳 handler（MessagePart/Updated/Removed 24-28 行）+ SseEventHandler.handle 残留 Boolean；SessionFocusHolder.shouldSuppress/shouldSuppressEvent 同体双胞胎。

正例（deep 不是不大）：RenderReadinessRegistry（4 方法藏整个就绪状态机，主动删死 interface）、ApiVersionDetector.detect()、MessageEventHandler（1150 行/~12 成员，三套合并策略+批处理+persist actor）。

UseCase 层核查结论：维持 Option B 但冻结——22/25 纯转发（~486 行），有逻辑的 3 个（MessagePagination 319 行/CreateDirectory/SubmitAnnotations）证明 seam 在有逻辑处产出杠杆；新增规则：仅在自带逻辑时新建 UseCase。

## 候选 1 设计定案（grilling Q1-Q11）

- Q1 边界=C：窗口计算+预解析+LRU+分片 pending/提交门控+recentStreamedTurnKeys 清理整段外移（窗口计算是共同前提，切开会暴露中间结果）
- Q2 输入=A：单方法推送 onViewportChanged(firstEntryIdx, lastEntryIdx, world)，world=不可变快照（displayItems/turnGroups/chatEntries/bannerCount/streamingMsgId）
- Q3 所有权=A：chunkPlans/recentStreamedTurnKeys 为模块私有+StateFlow 只读暴露；pendingChunkPlans 彻底私有；流式结束写入改经 noteStreamTurnEnded(turnKey)
- Q4 生命周期=A：remember{} 于 ChatMessageList（与 jumpController/renderReadiness 同款）
- Q5 门控=A：构造注入 phase: StateFlow<JumpPhase> + clock（默认 elapsedRealtime），模块自记跳转终点时刻——跨 effect 时间戳耦合（lastJumpEndAtMillis）消灭，B-F3 桥接滞后竞态类别整体消失
- Q6 测试=B：9 条 JVM 用例（五约束各 1 + B-F2 视口边缘裂变 + F1 陈旧索引 + C-R4c 陈旧丢弃 + 流式预解析截断/流式 turn 记录）
- Q7 迁移=B：三段式（外移壳与状态→收编门控→测试），每步编译+commit（ChatScreen 编辑协议同款循环）；真机最小走查=长消息滚动+跳转×2+130K 消息
- Q8 命名=A：RenderSupplyCoordinator（渲染供给协调器；概念名抗机制老化）；Q9 位置=A：components/（类型伙伴所在）；Q10=A：建 CONTEXT.md（已建，3 术语）；Q11=A：常量随迁 companion、RaceProbe 探针原样随迁（重构当天 logcat 前后 diff 即等价性证据）

## 候选 1 实现记录（2026-08-21 完成；同日用户验收通过——滚动手感/跳转观感无回归，卡片已迁出 backlog）

### 提交链（三段式，每段独立编译 + commit）

| 阶段 | commit | 内容 |
|------|--------|------|
| 1 外移壳与状态 | cb0143f8 | ChatMessageList ~193 行驱动 LaunchedEffect → RenderSupplyCoordinator（纯 Kotlin）；chunkPlans/recentStreamedTurnKeys 只读 StateFlow；pendingChunkPlans 私有；JNC 相位流可注入（默认自建） |
| 2 收编跳转门控 | 28ccee24 | lastJumpEndAtMillis 跨 effect 共享变量收编为模块私有（init 内 collectLatest 终态打点 + 注入 clock）；RenderSupplyWorld 瘦身 |
| 3 JVM 测试 | 6f5bb63f | RenderSupplyCoordinatorTest 10 条（真实 registry + 真实 markdown 解析 + 假时钟非零基） |

ChatMessageList 1671 → ~1500 行；五条隐含约束从注释升级为模块不变量。

### 验证证据（2026-08-21 真机 houji e69a99d8）

**自动化（新鲜执行）**：
- compileDevDebugKotlin ×3 全绿（阶段 1/2/3 各一次）
- 全量单测 --rerun：**1792 通过 / 0 失败 / 0 跳过**（含新增 10 条：流式禁预解析/供给正控/display 粒度窗口/LRU 联动淘汰/门控-相位/门控-稳定窗口（注入时钟 +2s 边界）/F1 partId 反查/F2 视口内防线/C-R4c 陈旧丢弃（反证 pending 清空）/流式 turn 记录与清理）
- assembleDevDebug 成功；pm install 静默装包成功；adb reverse + debug intent 一键配置成功（Debug channel activated）

**真机 E2E（租房会话，21 条消息）**：
- 进会话正常；RaceProbe（--ez debug_race true）全链活：**VIEW window 13+ 次实时输出**（窗口 0..6→6..10 推进），ENTRIES rebuild 正常，ScrollDiag gesture/LEAP 正常（idx 0→10 深滚 offset 13614，无卡死）
- 快速定位 sheet 正常（8 目标）；**跳转 ×2**（JUMP start 计数 2；Preparing→测量→底部定位全链 ChatPaging 日志）
- 回底滚动正常；**crash buffer 空**（31 条 AndroidRuntime 全是 uiautomator shell 工具自身启停，uid 2000）

**覆盖缺口（如实标注）**：
- ⚠️ 真机 CHUNK plan/commit 探针：本服务器 50 会话均无 ≥3000 字符 assistant text part（API 全量扫描证实；历史 130K 数据已清理；测试服务器 LLM 链路不可用——prompt 200 但 4 分钟无输出，deepseek 无凭据），无法在真机端到端触发。该路径由 T5-T9 五条 JVM 单测以**真实 markdown 解析**全链覆盖（plan→pending→双门控→F1/F2→commit/drop-stale）。
- ⚠️ 130K 消息场景：同上无数据。
- ⏳ 维度 5（滚动手感/跳转观感）：待用户真机验收。

**测试副作用**：服务器遗留空会话「分片E2E验收」（ses_fdeec5901ffe619NStxfewTCjB，无生成内容），可忽略或删除。

## 候选 2（#170）设计定案（grilling Q1-Q8）

- Q0 并行会话=已结束（deep-explore 批次只登记卡片无代码变更，#176-#183 随本批次提交入库）
- Q1 边界=B：编排+连接状态收进；终端 workspace/通知去重 map 保持深模块被调用（C 的"物理吸收一切"会翻案终端债务决策 + 拆散通知域内部 locality——"彻底"的正确形态是编排集中，不是状态全吞）
- Q2=A 新建 ConnectionLifecycleCoordinator（service/，@Singleton）；Q3=B FGS/wakeLock/stopSelf 从 activeServerIds 派生（onLifecycleChanged 回调实现同步确定性）；Q4=B 测试集（幂等/四路清理/等价性/回调时序/去重/轮询启停/流即时性/成员资格）；Q5=B 三段式
- Q6=A 命名 ConnectionLifecycleCoordinator；Q7=A service/ 包；Q8=A Service 公共 API 签名不变（调用方零改动）
- 边界要点：question 轮询体留宿主（通知域，依赖 Context/NotificationManager），启停经 QuestionPollingFactory 注入；FGS 决策读 registry，通知内容读 Manager 传输状态（两个数据源显式分层）

## 候选 2（#170）实现记录（2026-08-21 完成，待用户验收）

### 提交链（三段式）

| 阶段 | commit | 内容 |
|------|--------|------|
| 1 编排外移 | d3baf95c | connect 七步/disconnect 四路/disconnectAll 单实现收进 Coordinator；registry（serverId→config）真相源；双份 teardown 合一；FGS 经 onLifecycleChanged 派生；轮询经工厂注入 |
| 2 状态收尾 | b297e47e | terminalRegistry 注入移除；disconnectAllVisibleServers 冗余删除 |
| 3 JVM 测试 | d21a45f5 | ConnectionLifecycleCoordinatorTest 10 条（MockK 驱动三协作深模块） |

OpenCodeConnectionService 794 → ~740 行；teardown 从双份到单点。

### 验证证据（2026-08-21 真机 houji e69a99d8，debug intent + pm install）

**自动化**：compileDevDebugKotlin ×2 全绿；全量单测 --rerun 两次：第一次 1802 中 1 失败（ChatViewModelContextTokensTest·compaction——**flaky**：单独重跑通过、第二次全量通过；与本改动无关的 UI 层 tokens 测试，service 层改动不可能影响其路径，如实记录）；Coordinator 测试 10/10。

**真机 E2E 四场景**：
1. **连接**：ConnLifecycle "Connecting to server: Host-4199" → WakeLock acquired（回调派生 ✓）→ **幂等真实触发**："Already connected … skipping"（debug intent 与 autoConnect 竞争同服务器，Coordinator 挡住——C1 用例的真机版）→ Network recovered 日志读 registry ✓
2. **断开**：ConnLifecycle "Disconnecting server eb6517bf" → WakeLock released（onLastServerDisconnected ✓）
3. **重连**：Connecting → WakeLock acquired 全链
4. **飞行模式**：enable → 8s → disable → 12s 后 UI "已连接"（connectedServerIds 由 SSE 首事件驱动 = SSE 流真实重建；传输层退避自愈不经过 Coordinator——正确边界）

crash buffer 空。多服务器场景无第二台真实服务器，由 C4 teardown 等价性 + registry 语义测试覆盖（如实标注）。

⏳ 维度 5（断开/重连/飞行模式恢复的 UI 状态观感）：待用户验收。

## 五候选总设计取证（2026-08-21，#171-#175 批次 grilling 前置）

> 用户指示：五候选一次总设计，然后依次实现。三路并行子代理只读取证 + 主会话逐项复核。
> #171 取证（红点时钟域三铁律 + markSessionIdle 泄漏链）见前文候选 3 行条目与 grilling Q1-Q8（对话内），关键锚点：UnreadBadgeService.kt 全文 / SessionListStateBuilder.isUnread / ChatViewModel.markSessionRead:306 / EventDispatcher:417-421。

### A. V1/V2 seam 普查（#172）——修正了原候选假设

**结构发现（推翻"79 决策点散布"的表述）**：78 个 API 决策点已收敛在 7 个域门面内部（SessionApiImpl 23 / ProviderApi 13 / MessageApi 12 / FileApi 12 / SystemApi 8 / TerminalApi 6 / ShellApi 4，每方法一行 if(conn.apiVersion.isV2) v2.x() else v1.x() 纯分发）+ SseConnectionManager:323 SSE 分流 1 处 = 79。调用方早已看不见版本。仓库已有按版本分体的 **god-client**：V1ApiClient（72 suspend fun 全域）/ V2ApiClient（84），7 门面经 ApiModule @Binds 注入两者做逐调用分发。

**真病灶 = 门面外 6 处逻辑泄漏 + UI 门控**：
- SessionStateService:301-302 / 642-643（backfillMissedMessages + triggerRestValidation，同型：getApiVersion→isV2→CursorCodec.encodeV2(NEWER) 游标；经 SessionRepository 走 REST 不绕门面）
- MessagePaginationUseCase:101（进会话增量 isV2 不传 cursor）/ 201-202（首次翻页 V2 拿服务器 cursor.next）/ 232+236（loadAround V2 双向游标）/ 294-295（isV2Server 公开 helper 把版本查询 API 化扩散给 UI）
- MessagePaginationDelegate:336-337,349,353（loadAroundFromLocal 调 helper 决定 newerCursor）
- UI 能力门控：ChatScreen:642/643（isShareSupported/isBackgroundSupported）/ 941（showRunningFilter）/ ServerSettingsViewModel:227/267/351/433（V2 配置只读）/ ChatViewModel:331→112-113（serverApiVersion 透传）/ ServerCard:101（版本徽章，展示性）

**游标差异是行为策略非格式差异**：V2 = 服务器窗口语义（不传 cursor 拉最新 + cursor.next 续页 + 空页兜底，curl 实证注释链 #55/#56/#82/2026-08-16 cursor-400 根治）；V1 = 本地 {id,time} before 锚点。不能机械翻译成 codec。

**版本生命周期**：ApiVersionDetector.detect()（双探 /api/health + /global/health，#150 排序 + #132 UNKNOWN 不降级）→ ServerDataStore.checkHealth 持久化 → 7 处 resolveConnection 逐次重读构造 ServerConnection（方法参数传递非构造注入）。**逐调用分发天然免疫版本竞态**；缓存式 per-server 适配器需 keyed 失效重建（风险 Top1）。SSE 是唯一"连接时选定一次"正确运作的样板（流生命周期=连接生命周期）。

**测试影响**：~22 文件（ApiVersionDetectorTest 32 断言 / V1V2ApiClientTest / MessageApiCursorTest / PaginationFSM+Delegate 25 处版本引用 / SessionStateService 并发等）。

### B. ChatViewModel 假 seam 普查（#173）

**规模**：ChatViewModel 980 行 / 28 构造参数 / 128 公开成员（74 fun + 54 val）；消费面 ≈97 成员/150 调用点（ChatScreen 71 + BottomBar 26 + MessageList 10 + TerminalView 14）。1:1 纯转发 48 方法 + ~33 属性 getter。

**16 个 delegate**（VM 直接构造刻意非 Hilt）：SessionLifecycle(157)/MessageData(662,12 参)↳Pagination(458,10 含 2 sink)↳SendStateStore(24)/ChatSend(171,16)/DraftInput(241)/ModelConfig(388,9)/SessionActions(717,20)/Terminal(136)/SettingsState(60)/ChatStateAggregator(215)/ContextDetail(90)/TaskAggregator(272)/ToolCache(95)/**ScrollPosition(82)——生产死代码（仅定义+自测引用，主会话 grep 复核确认）**/QuestionAnswerStore(@Singleton)。

**Sink 回写 10 处**（密谋区）：Pagination→MessageData loading/errorSink；ChatSend 拿 sendStateStore+errorSink+sendFailureSink+draftDelegate 直引用（VM:798-822）；SessionActions 9 provider 横跨 4 delegate（VM:435-471）；Lifecycle→MessageData 观察/加载回调（VM:140-141）；Terminal↔Lifecycle 双向；Draft 读 ModelConfig 私有值；Aggregator 五方消费；abortSession 跨 4 对象（VM:843-857）/ revertMessage 跨 6 对象（VM:896-932，RS-006/RS-008 竞态修复史）。

**提案 4 簇 + 2 外围**：①SessionContext（被依赖）②ConversationData（含 SSE 生命周期单一入口）③Composer（草稿+发送+堆积队列）④ModelConfig（12 源自反馈环原样）+ 外围 Terminal（已独占消费）/Settings+Tasks；abort/revert 编排留薄 VM。风险：不可拆管道（messageListState 10 源+ChatMessage 实例缓存）、ModelConfig 自反馈、测试爆炸面（6 VM harness 28 参数 + uiState 向后兼容注释 + 5 delegate 测试）。

### C. 顺手清理三件 + 1 新发现（#175）

- **①ChatMessageList 双调用点**（ChatScreen:812/840）：20 公共参数逐字对齐，4 差异全为 isMainSession 投影（isMainSession/showQuickNavigate/onQuickNavigateDismiss/onAgentClick 默认 null）→ 条件内移参数化单调用点，低风险。注意 ChatScreen 编辑协议。
- **②三壳 handler**：MessagePartHandler(28)/MessageUpdatedHandler(25)/MessageRemovedHandler(24)，serverId 未用，全指向同一 store（MessageEventHandler 已持 5 目标方法）→ 删壳由 MessageEventHandler 实现 SseEventHandler + registry 单 bind。**修正：handle 的 Boolean 返回值生产侧 0 消费（EventDispatcher:364 丢弃）但 5 个测试文件 ~20 处断言消费它作识别契约**——签名保留，非残留。
- **③SessionFocusHolder 双胞胎**：shouldSuppress/shouldSuppressEvent 方法体逐字节相同（2026-08-16 对齐后成同体）；调用点按文件完全隔离（4 处 OpenCodeConnectionService vs 5 处 AppNotificationManager）→ 删 Event 版 + 9 生产调用改名 + 修正过期测试节标题 + #137 重复注释收敛。
- **④新发现：ScrollPositionDelegate 生产死代码**（grep 复核：仅定义处引用 + ScrollPositionDelegateTest）→ 删 82 行 + 测试文件。

