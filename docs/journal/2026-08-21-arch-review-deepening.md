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

### D. 补充取证（2026-08-21，grilling 等待期间）

- **#171 实现接缝读全**：SettingsRepository 已读 4 方法（sessionReadTimes/allReadAt/markAllSessionsRead/markSessionRead:80-92）· SessionListViewModel settingDataFlow(4源)/miscDataFlow(2源) 双 combine:284-304 · deleteSession→signal.remove:655 · buildTreeNodes 8 参穿线(TreeNode:54-69) · SessionReadSignal 全文（33 行，markRead/remove 两方法）。
- **#172 泄漏点行为契约就地文档核实**：MessagePaginationUseCase:85-104 完整保留 2026-08-16 cursor-400 根治注释链（V2 不传 cursor 拉最新窗口 + id 去重合并；V1 本地 {id,time} 锚点）；SessionStateService:630-674 L3 补漏含空页兜底（窗口外锚点 200+空页 → 无游标重拉最新窗口）。两处是 PaginationCursorPolicy 移植时的行为规格来源。
- **Maestro E2E 套件普查（35 文件）**：与批次相关的现成链——regression-unread-chain-a/b.yaml（#171 红点出现/消费清除/杀进程持久）、l2-session-load-more + e2e-large-file-pagination（#172 分页回归）、e2e-chat-flow/l4-chat-ui（#173）、perf-session-scroll（全批次滚动基准）。⚠️ 如实标注：chain A 依赖真实 LLM turn 完成，测试服务器 LLM 链路不可用（#170 时已实证 prompt 200 无生成）——#171 真机验证以 chain B 消费/重启半链 + 既有会话水位线 + 人工清单为主，A 链缺口记录。
- **全量单测基线（工作树 fc251f41）**：--rerun 新鲜执行 1802/1802 绿 0 跳过（XML 汇总解析）。backlog-check 通过（#184 > #183，201 行）。

## 五候选总设计定案（2026-08-21，用户 grilling 作答 21/23，Q171-2/3 已讲透推荐 A 待最终确认）

### 跨切面

- **G1 顺序**：171 → 174 → 175 → 172 → 173（依赖与风险形状：#171 独立且封真实静默破坏路径并顺带瘦身 #173 的输入；#174 小而干净先稳 SessionStateService 形状；#175 纯清理缩小表面积；#172 大 data 层翻转；#173 大 UI 重组吃前四项红利）
- **G2 验证范围**：通用协议（分段 compile+commit / JVM / 全量 --rerun / assemble+真机）之上——#171 加 DB 回读污染反例 JVM + 真机红点四态；#174 迁移 3 测试文件 ~30 处 + 真机 FSM 烟雾；#175 现有测试 + 真机烟雾；#172 策略双版本 JVM + 真机 V2 全流程（V1 缺口如实标注，模拟器走查可选）；#173 对话全生命周期 E2E + 维度 5
- **G3 验收**：每候选依次真机 E2E（自动化证据齐即转 `[~]`）+ 批次末人工统一测试（用户定，含 #170 一并）
- **G4 落地**：本章节即总设计记录；CONTEXT.md 术语随各候选实现添加

### #171 未读红点时钟域（Q1/Q4-Q8 已定，Q2/Q3 推荐 A 待确认）

- Q1=A **全吸收**：水位线 + 已读（写+读）+ 判定全收进 UnreadBadgeService；SessionReadSignal 并入（Q5=A）；markRead/markAllRead 读模块自身水位线不再扫消息缓存
- Q4=A 判定为模块方法 hasUnread(sessionId, status)，status 由调用方传入（FSM 域留外）
- Q6=A markAllSessionsRead 跨服务器 globalMax 混合**不动**，登记新 backlog 卡
- Q7=A 沿用 UnreadBadgeService 原位深化；Q8=A 三段式（①interface 事件化+泄漏封死 ②已读侧吸收+判定入模块 ③JVM 测试），每段 compile+commit
- Q2 待确认，**推荐 A 切断消费侧**：红点时间源只消费事件载荷（SSE payload/REST 响应原始数据），永不扫合并缓存或 DB 回读消息列表——markSessionIdle 客户端 now 对展示域正当，泄漏在红点域去"读"它
- Q3 待确认，**推荐 A 事件对象**：sealed UnreadEvent（ServerMessageCompleted(serverTs)/RestSnapshot(serverTs)/SessionErrorOccurred(clientNow 故意例外，research/11 定案)）——时钟域编码进类型，传错编译不过；B 命名约定与现状同级

### #174 SessionStateService 8 旋钮 → 1 必需协作者（全 A）

Q1=A 单 interface 8 方法全抽象无默认（漏接=编译错误）；Q2=A 独立 @Singleton SessionStateCollaboratorImpl（data/repository/，构造注入 messageHandler/sessionHandler/questionHandler/permissionHandler/sessionRepoProvider/pendingMessagePipelineProvider——已验证无环），EventDispatcher.init 75 行接线块整体迁入；Q3=A 命名 SessionStateCollaborator(+Impl)；Q4=A 测试 ~30 处 var 赋值改构造 fake（3 文件经 newService() 工厂单点改造）；Q5=A sessionMovedListener/onSessionError 两桥接不纳入（EventDispatcher 本职，onSessionError 归 #171 域）

### #175 顺手清理四件（Q1 全做 + Q2 照此）

①ChatMessageList 双调用点参数化合一（4 差异内移，ChatScreen 编辑协议）②删三壳 handler（MessageEventHandler 实现 SseEventHandler 5 分支 + registry 单 bind；Boolean 签名保留——5 测试文件 ~20 处识别契约）③shouldSuppressEvent 并入 shouldSuppress（9 生产调用点 + 过期测试节标题修正 + #137 重复注释收敛）④ScrollPositionDelegate 死代码删除（82 行 + 测试文件）。Q2=A 补 5+1 识别契约 JVM 测试

### #172 V1/V2 seam（Q1=A，Q2-Q4 照推荐）

- Q1 用户规则落定："B 若非彻底根治则选 A"——B 不是根治（拆的是健康组织：门面已收敛，病灶全在门外），**定 A 泄漏收编+策略抽出**：①PaginationCursorPolicy 双实现收编 6 处泄漏 + 删 isV2Server（isV2 从 domain/UI 绝迹）②ServerCapabilities 作 ServerConnection 派生属性（when(apiVersion) 纯映射，resolveConnection 每次重建=天然新鲜）收编 UI 门控。**god-client 不拆**，门面 78 if 保持，登记终局债务
- Q2 PaginationCursorPolicy（~5 方法）domain 层接口+V1/V2 实现，工厂 for(serverId) 内读一次版本；V2 实现整协议移植（不传 cursor 拉最新+cursor.next 续页+空页兜底——行为规格来源=MessagePaginationUseCase:85-104 与 SessionStateService:630-674 注释链）
- Q3 ServerCapabilities = ServerConnection 派生属性；Q4 门面测试不动+策略双版本 JVM+泄漏点测试改注入 fake 策略，V1 缺口如实标注

### #173 ChatViewModel 状态簇（Q1/Q3/Q4=A，Q2/Q5 照推荐）

Q1=A 完整重组（UI 直接消费簇对象）；Q2=4+2 簇（①SessionContext 被依赖 ②ConversationData 含 SSE 生命周期单一入口 ③Composer ④ModelConfig 自反馈环原样 + 外围 Terminal/Settings+Tasks；abort/revert 编排留薄 VM）；Q3=A uiState 退役 + 重写 6 harness；Q4=A 死代码已并入 #175；Q5=四段串行（Terminal 迁出 → 簇内部成型 → UI 按子组件串行迁移 → 测试重写），ChatScreen 编辑协议每步 compile+commit

### E. #171 泄漏入口完整地图（2026-08-21，阶段 1 实现前置——Q2 答案无关）

**水位线全部输入（4 条）**：① SSE MessageUpdated(completed≠null) 增量 → onMessageCompleted（服务器时刻，干净）② SessionError → onSessionError（客户端 now，故意例外）③ **recomputeMaxCompleted 漏斗（唯一泄漏面）** ④ seedFromStorage（自身持久值，自域）。

**漏斗形状**：EventDispatcher 4 个包装方法（upsertMessages:532 + deprecated set/merge/replace:541-557）内部都调 recomputeMaxCompleted:564，后者扫 messageHandler.messages.value[sessionId]（合并缓存）。生产调用方共 6 处：ChatRepositoryImpl:101（会话进入初始加载）+ 514/519/524/529（REST 刷新路径 ×4，deprecated 变体）+ SseConnectionManager:461（重连 backfill recoverMessages）。

**关键实现细节（Q2=A 的必要非充分条件）**：漏斗包装方法已接收载荷参数 messages（REST 响应原文）——"只消费载荷"改法 = recompute 从 messages 参数取 max 而非扫合并缓存。但**载荷来源异质**：ChatRepositoryImpl:101 离线/缓存路径的载荷来自 Room（MessageCacheRepository:31 → MessageDao），而 markSessionIdle 的客户端 now 戳经 persistSseUpdate **已落盘 DB**——DB 回读载荷同样携带本地戳。故阶段 1 还需 REST-来源门控（仅 REST 响应路径触发 recompute，或 DB 载荷过滤），单纯换数据源不够。这正是"切断消费侧连 DB 回环一起封死"的具体落点。

**旁证（分页本地路径不泄漏）**：MessagePaginationUseCase/MessagePaginationDelegate 的 messageStore.upsertMessages 直写本地 store，不经 EventDispatcher 漏斗、不触发 recompute——水位线只经漏斗 + SSE 增量两路演化，收敛面确认完整。

### F. 批次范围扩展 + 最终 2 题落定（2026-08-21，用户指令）

**用户总纲**：P0 架构批次完成后接续 P1 需求批次（#155/#151/#152/#153 等）。P1 spec 因架构代码修改可能失效——**先按架构调整后实际代码修订 spec/backlog，再按 spec 实现，最后测试**。无论架构还是 P1 需求都要真机 E2E+回归，能在真机做的全做，减少人工复核工作量；交付质量与稳定性按仓库对应文档/指南/操作手册/方法论执行。

**Q171-2/3 落定**：用户在收到白话讲解后未再作答，转而下达继续实施的总纲——按其本批次一贯"按推荐"模式落定 **Q2=A（切断消费侧）+ Q3=A（事件对象）**。至此 23/23 全部定案。

（补记：用户随后显式确认 "Q172-1 选A；Q171-2选A；Q171-3选A"——三题从按推荐落定升级为显式定案。）

## #174（候选 6）实现记录（2026-08-21 完成，真机烟雾全绿）

### 提交链

| 阶段 | commit | 内容 |
|------|--------|------|
| 主迁移 | f179ad70 | SessionStateCollaborator 接口（8 方法全抽象无默认）+ SessionStateCollaboratorImpl（EventDispatcher.init 75 行接线块整体迁入，逻辑零变更；PMP 环经 Provider 同款断法）+ @Binds 模块；SessionStateService 8 var 旋钮 + 4 typealias 删除，构造注入 collaborator（漏接=编译错误）；14 个消费点改经协作者；3 测试文件迁移 StubCollaborator（可单点覆写桩），4 个 EventDispatcher 套件构造点补默认桩 |
| 接线测试 | ab2c36c3 | SessionStateCollaboratorTest 7 条：流式检测/未知目录 null/forceComplete 终结+落盘兜底（verify persistAsync）/refresh 委托/游标锚点 newest/无 pending 输入 false/pipeline 委托 |

### 验证证据（2026-08-21 真机 houji，versionCode 1787270139）

- **自动化**：全量单测 1808/1808 一次全绿（含 7 新增）；受影响 7 测试类 110/110
- **真机烟雾**：冷启动 DI 环解出（Hilt 图 SessionStateCollaboratorImpl 绑定活）；**FSM 完整生命周期**：ClientSendParts→Busy/Waiting → TextStarted→Streaming → 提问→回答 → Busy/Streaming --SseIdle--> Idle [force-complete]（forceCompleteSession→markIdle+persist 链）+ Idle --RestValidation--> Idle [force-complete] + SUSPICIOUS 检测；L2 stale→L3 校验（directoryResolver/refreshMessages 接线）活跃
- **附带发现**：LLM 链路恢复（deepseek-chat 真实生成+提问）——#171 记录的 chain A 无 LLM 缺口已消除（后续候选真机验证可覆盖更多生成路径）
- crash buffer 0 条；hasActiveChildren/hasPendingUserInput 僵尸场景（需 3min busy）JVM 覆盖（既有 ConcurrencyTest）
- ⏳ 维度 5：FSM 状态 UI 观感（busy 计时/流式/提问卡片）待用户验收

## #172（候选 4）实现记录（2026-08-21 完成，真机 E2E 全绿）

### 提交链（三段式）

| 阶段 | commit | 内容 |
|------|--------|------|
| 1 游标策略 | 2a0bb5a6 | PaginationCursorPolicy（domain）：localAnchorCursor/aroundCursors/newerAnchorCursor/supportsNewerDirection + V1/V2 双实现 + 工厂（版本读取关进模块；与调用方同源 SessionRepository → 测试既有 getApiVersion stub 零改动继续生效）。收编 6 泄漏点：MPU loadMessages:101 / loadOlder 首次翻页 / loadAround（isV2 分支重写为 aroundCursors 能力对）；SSS backfill ×2；Delegate loadAroundFromLocal（能力语义替代 isV2Server+encodeV2 直构）。isV2Server 删除——isV2 从 domain/UI 数据路径绝迹（残留仅：ApiVersion 定义 / 数据层门面与策略工厂消费 / ServerCard 显示徽章） |
| 2 能力位 | f8521376 | ServerCapabilities（of(apiVersion?)：share/background/runningFilter/configEditable；null=全开放保 permissive 语义）+ ServerConnection.capabilities 派生属性（纯映射每次构造新鲜）。UI 门控迁移：ChatScreen 642/643/917、ChatViewModel Flow 换 capabilities、ServerSettingsViewModel 4 处（var 换 configEditable） |
| 3 测试 | 2de6889e | PaginationCursorPolicyTest 7 条：V1 锚点编码/空语义、V2 null 窗口语义、V1 单向/V2 双向 around、NEWER 锚点双版本、能力位三态映射 |

### 验证证据（2026-08-21 真机 houji，V2 全流程）

- **自动化**：全量 **1812/1812 全绿**（+7 策略测试）；受影响 5 类 76/76；每段 compile 绿
- **真机 V2 E2E**：进长会话 → 滚动加载更早（loadOlder NETWORK 24 msgs + 服务器原生 cursor 续页——FSM Network serverCursor base64 解出 order:desc,direction:next，正是 V2 策略不构造本地游标、透传服务器游标路径）→ 快速定位跳转 Q3（loadAround 双向 + 后续 loadOlder 续链）→ **能力门控**：更多菜单 V2 下分享隐藏 + 后台显示 + 压缩（版本无关）显示；crash 0
- V1 路径：无真机 V1 服务器——JVM 双实现契约测试覆盖（缺口如实标注，与 #150 的 V1 复验一并处理）
- ⏳ 维度 5：分页滚动/快速定位观感待用户验收

## #175（顺手清理 + bonus）实现记录（2026-08-21 完成，真机烟雾全绿）

### 提交链（每件独立 commit）

| 件 | commit | 内容 |
|----|--------|------|
| ③ 双胞胎合并 + ④ 死代码 | 65a51723 | shouldSuppressEvent 并入 shouldSuppress（方法体 2026-08-16 对齐后逐字相同；KDoc 双用途折叠 + P1 修复史保留 + #137 微竞态注释收敛一份）；AppNotificationManager 5 调用点改名；重复测试组删除（断言集等价核实）+ 过期节标题修正；ScrollPositionDelegate 死代码删除（82 行 + 测试，生产零引用已 grep 复核） |
| ② 删三壳 handler | 67d496f3 | MessagePartHandler/MessageUpdatedHandler/MessageRemovedHandler 三壳删除（各 ~25 行纯转发、serverId 未用）；MessageEventHandler 直接实现 SseEventHandler（handle 五分支）；registry 三 bind → 单 bind；Boolean 签名保留（5 测试文件 ~20 处识别契约消费——取证修正的诚实响应）；补识别契约测试 5+1；11 测试文件构造清理 |
| ① 双调用点合一 | 276f2850 | ChatScreen 812-866 主/子会话双 ChatMessageList 调用点 → 参数化单调用点（4 差异全为 isMainSession 投影内移；20 公共参数逐字对齐取证在案）；ChatScreen 编辑协议（Read→Edit→compile→commit） |
| bonus：deprecated trio | d757d499 | ChatRepository 接口 + Impl + EventDispatcher 三层 setMessages/mergeMessages/replaceMessages 删除（生产零调用，#171 时记录的债务）——真实现 MessageEventHandler 三方法保留；测试调用点全部迁移 upsertMessages(显式策略) |

### 验证证据（2026-08-21 真机 houji，08:12:39 构建）

- **自动化**：全量单测 **1805/1805 全绿**（-3 = ScrollPositionDelegateTest 随死代码删除；三壳识别契约 +2）；compileDevDebugKotlin 每件绿
- **真机烟雾**：冷启动事件分发正常（ServerConnected → SessionEventHandler 单 bind 路由）；主会话进入/滚动/渲染正常；**子会话 else 分支端到端**——API 造真实子会话（parentID，ses_fde54839affePjIXAb9J31bOkb）→ 列表出现 → 进入渲染正常 → **快速定位对话框被抑制**（if (isMainSession) ... else false 投影 = 原常量 false 分支行为等价）；crash buffer 0
- 测试子会话已清理（DELETE 204）；覆盖缺口：无（识别契约/断言等价/参数等价均有静态+运行时双侧证据）

## #171（候选 3）实现记录（2026-08-21 完成，真机 E2E 全绿）

### 提交链（三段式，每段独立编译 + commit）

| 阶段 | commit | 内容 |
|------|--------|------|
| 1 泄漏封死 | a048b1ea + 2231d301 | UnreadEvent sealed（ServerMessageCompleted/RestSnapshot 服务器时刻；SessionErrorOccurred 客户端时刻=签名上的显式例外）+ onEvent 事件入口（旧 3 方法 @Deprecated 转发零破坏）；EventDispatcher 漏斗 recompute 从**载荷参数**提取（不再扫合并缓存）；新 seedCachedMessages 纯缓存入口——ChatRepositoryImpl Room 种子（:101）改走此路，DB 回环（markSessionIdle 客户端戳落盘→回读）结构性封死 |
| 2 已读侧吸收 | 941f17f8 | SessionReadSignal 删除（33 行）；模块增 justRead/mergedReadTimes(持久∥内存 max 合并)/allReadAt/markSessionRead(读自身水位线，无记录 no-op)/markAllSessionsRead(globalMax #184 语义保持)；持久化走 ApplicationScope（比 VM 活得久——原 NonCancellable+viewModelScope 的语义强化）；判定 isUnread 迁模块 companion（Builder 保留转发）；SessionListViewModel settingDataFlow 4→3 源单源化；ChatViewModel.markSessionRead 26 行→3 行（泄漏入口 3 消灭）；17 文件 +162/−131 |
| 3 域纯度测试 | a33d0d27 | UnreadClockDomainTest 6 条：seedCachedMessages 不喂水位线（DB 回读污染反例）/upsert 载荷提取/缓存污染不干扰（若实现退化为扫缓存 max 会得 999_999 的回归守卫）/SessionError 客户端时刻例外通道/markSessionRead 无水位线 no-op/判定门控。测试自身 scope 泄漏修复（stateServiceScope.cancel——曾致 ContextTokensTest 同 JVM 时序失败） |

### 验证证据（2026-08-21 真机 houji e69a99d8，pm install + debug intent）

**自动化**：compileDevDebugKotlin 每段绿；全量单测 1808（含 +6 新测试）：**7 次全量 6 绿 1 败**——败者归属 XML 被覆盖未取到且不复现，模式与 #170 记录的 ChatViewModelContextTokensTest·compaction 低频时序 flake 一致（基线时代已存在；本改动后 6/7 绿，如实标注）。Unread 三套既有测试（UnreadBadgeServiceTest/EventDispatcherUnreadTest/EventDispatcherTest）阶段 1 后即零破坏全绿。

**真机 E2E（红点四态 + 双持久化）**：
1. **红点出现**：56 会话水位线 seed（persist 日志）→ 列表 8 个「有未读消息」徽标（新判定链路 UnreadBadgeService.isUnread → SessionItem.hasUnread）
2. **消费消除**：进「堆积队列消息发送延迟」→ BACK → 8→7，目标行徽标消失（markSessionRead 读模块水位线 + 内存信号即时）
3. **冷启动持久化**：force-stop 重启 → 已消费会话保持消除；恢复进入的会话退出后 7→6（ChatViewModel.markSessionRead 新链路二次实证）
4. **一键已读**：更多菜单「一键已读」→ 6→0（markAllSessionsRead 模块单点）→ force-stop 重启 → **保持 0**（allReadAt 落盘）
- crash buffer 0 条；UnreadDiag persist 活跃（7 次）；连接幂等真实触发再现（"Already connected … skipping"）

**覆盖缺口（如实标注）**：
- ⚠️ SessionErrorOccurred 红点（错误产生未读）真机无触发手段（需会话真实报错）——JVM 用例 4 覆盖（断言客户端时刻落入 [before, after] 窗口）
- ⏳ 维度 5（红点观感/列表滚动无闪烁）：待用户验收
- 测试 flake 残余：ContextTokensTest 时序脆弱为既有问题（#170 已记录），本批次未修复（非 #171 引入）

