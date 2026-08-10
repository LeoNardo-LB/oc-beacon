# C 维度审计报告：状态管理 / 事件分发 / 日志系统

> 审计基线：v0.2.0..HEAD（v0.3.0-beta.2）
> 审计范围：SessionStateService、EventDispatcher、MessageDataDelegate、MessageEventHandler、SessionNextEventHandler、AppLogger、StreamingOwnershipRegistry、UnreadBadgeService、DraftDataStore、DraftInputDelegate、ChatViewModel
> 方法：只读代码审计，所有结论均给出 `[VERIFY: 文件路径:行号]`
> 风险等级：P0（必修/阻塞） · P1（应修/功能性或高频路径风险） · P2（建议/低频或理论风险） · P3（提示/可接受）

---

## 1. 状态 / 事件 / 日志全景图

### 1.1 SSE → 状态源 → UI 主链路（事件分发视角）

```
                    [SSE OkHttp 流]
                          |
                          v (Dispatchers.IO)
              SseConnectionManager.scope
              [VERIFY: service/SseConnectionManager.kt:65]
                          |
                          v processEvent(event, serverId)
                  EventDispatcher.processEvent
              [VERIFY: data/repository/EventDispatcher.kt:207]
                          |
              +-----------+-------------------------------+
              |           |                               |
              v           v                               v
   ownershipRegistry   registry[event::class]      forwardToSessionStateService
   .claim() 去重 O(1)   .handle(event)  <-单handler       |
   [VERIFY: 211]        [VERIFY: 221-223]                 |
                                                          v
                                          sessionStateService.onSseEvent
                                          [VERIFY: data/repository/SessionStateService.kt:135]
                                                          |
                                                          v applyTransition
                                          SessionStateFSM.transition（纯函数）
                                          [VERIFY: domain/model/SessionStateFSM.kt:26]
                                                          |
                              +---------------------------+--------------------------+
                              v                           v                          v
                    _fsmStates.update(CAS)      _histories.update(CAS)      副作用（forceComplete / isSuspicious）
                    [VERIFY: 172-178]            [VERIFY: 200-204]            [VERIFY: 184-185]
                              |
                              v
            statusFlow = _fsmStates.map{...}.stateIn(Eagerly)
            activityFlow = _fsmStates.map{...}.stateIn(Eagerly)
            [VERIFY: 106-112]
```

并行下游（同一 SSE 事件触发的其他 handler）：
- MessageEventHandler：handleMessageUpdated -> _messages.update + 播种 _parts + persistSseUpdate fire-and-forget [VERIFY: data/repository/handler/MessageEventHandler.kt:139-183]
- handleMessagePartDelta -> pendingDeltas.add -> scheduleFlush(48ms) -> flushPendingDeltas -> _parts.update(CAS) [VERIFY: 338-356 / 86-129]
- handleMessagePartUpdated -> _parts.update(CAS) + 诊断日志 [VERIFY: 238-272]
- SessionNextEventHandler：每个 ToolProgress/StepStarted/... -> 独立 _*Flow.update(CAS) [VERIFY: data/repository/handler/SessionNextEventHandler.kt:105+]
- UnreadBadgeService.onMessageCompleted -> _lastCompletedReplyTime.update + persistAsync(cancel-launch) [VERIFY: data/repository/UnreadBadgeService.kt:43-49]

UI 观察层（每个 StateFlow emission 触发下游 combine）：

```
MessageDataDelegate.messageListState  <- 10 路 combine
[VERIFY: ui/screens/chat/MessageDataDelegate.kt:139-264]
  上游：
    0 getSessionsFlow   -> SessionHandler.sessions
    1 observeMessages   -> chatRepository.getMessagesFlow
    2 getAllPartsMap    -> MessageEventHandler._parts
    3 _isLoading        -> MessageDataDelegate 私有
    4 hasOlderMessages  -> paginationDelegate
    5 isLoadingOlder    -> paginationDelegate
    6 autoLoadPaused    -> paginationDelegate
    7 _toolExpandedStates -> MessageDataDelegate 私有
    8 statusFlow        -> SessionStateService._fsmStates.map
    9 getActiveToolProgressForSession -> SessionNextEventHandler
  下游：UI collectAsStateWithLifecycle -> recomposition

MessageDataDelegate.sseJob  <- 2 路 combine（同源 observe）
[VERIFY: ui/screens/chat/MessageDataDelegate.kt:319-334]
  上游：getMessagesFlow + getParts（与 messageListState 共享源）
  下游：写 _rawMessagesList / _messagesList / _partsList
        （供 TokenStatsTracker / markSessionRead / fixIncomplete）
```

关键放大：1 个 MessagePartDelta 事件 -> 48ms 批处理合并 -> 1 次 _parts.update -> 同时触发 messageListState combine（上游 2）和 sseJob combine（上游 getParts），两个独立 combine 重组。1896 条消息时每次重组 O(n) 扫描（visible.map + chatMessageCache 查询 + suppressRepeatedPatchHashes）。

### 1.2 日志路径

```
调用方（任意线程）-> AppLogger.d/i/w/e
   |
   +-> androidWrite() 同步执行（AndroidLog.d/i/w/e，native call）
   |   [VERIFY: logging/AppLogger.kt:96-122 / 154-175]
   |
   +-> shouldPersist(level) 检查
       |
       +-> queue.trySend(WriterCommand.Entry)
           [Channel(capacity=500, DROP_OLDEST)]
           [VERIFY: 40-49 / 163]
                |
                v consumer 协程（Dispatchers.IO）
            receive() -> delay(150ms) -> 批量 <=50 -> persistBatch -> Room
            [VERIFY: 69-93 / 216-224]

recordCrash（崩溃专用）-> runBlocking(Dispatchers.IO) flush(750ms timeout)
   [VERIFY: 128-141]
```

---

## 2. 环节分析与风险

### 2.1 SessionStateService（状态机单一真相源）

正确的设计：
- 纯函数 FSM（SessionStateFSM.transition）+ Map<sessionId, State> 存储 [VERIFY: domain/model/SessionStateFSM.kt:26 / SessionStateService.kt:103]
- 整个 读-计算-写 在 _fsmStates.update{} CAS lambda 内完成（RS-010）[VERIFY: SessionStateService.kt:172-178]
- 防御性清理：>24h 无事件且非 Busy 的孤儿状态被清理 [VERIFY: 89-100]
- REST 校验去重（RS-012）：activeValidations.merge + invokeOnCompletion 清理 [VERIFY: 295-302]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| _fsmStates 与 _histories 是两个独立 CAS | P3 | applyTransition 中两次 .update{} 不在同一原子步——理论上 history 可能记录一个未实际生效的 transition（fsmStates CAS 失败但 history CAS 不重试）。实际影响极小：两者都在同一函数内同步执行，竞争窗口窄；history 仅诊断用途 | [VERIFY: SessionStateService.kt:172-178 vs 188-205] |
| statusFlow/activityFlow 每次 emission 创建新 Map | P3 | _fsmStates.map { it.mapValues {...} }.stateIn(...)——上游每次 emission 都新建一个 Map（mapValues 返回新实例）。会话数多时每次 transition 都分配 N 大小的 Map。N 通常 < 50，可接受 | [VERIFY: 106-112] |
| checkStaleness 每 5s 全量扫描 | P3 | 遍历 _fsmStates.value.forEach 检查所有会话。O(N) 但 N 受限且有清理机制 | [VERIFY: 77-101] |
| triggerRestValidation 中 listMessages(limit=50) 在校验协程内同步执行 | P3 | 每次可疑/陈旧触发都做一次 REST 调用 + Room upsert + 内存 upsert。但有 RS-012 去重，频率可控。注释解释了 limit=50 的选择（避免 limit=0 全量拉取大会话导致 slowUI）[VERIFY: 284-289] | OK |

### 2.2 EventDispatcher（事件分发器）

正确的设计：
- 注册表模型（O(1) 路由），替代旧的广播模型 [VERIFY: data/repository/EventDispatcher.kt:89-149]
- 多服务器所有权去重（StreamingOwnershipRegistry.claim）[VERIFY: 207-217]
- 跨 handler 级联清理（SessionDeleted）[VERIFY: 230-240]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| 横切红点/用户消息时间在每次 processEvent 同步处理 | P3 | MessageUpdated 走完后还会调用 unreadBadgeService.onMessageCompleted、sessionHandler.recordUserMessage。这些都是 O(1) 内存写，OK | [VERIFY: 255-262] |
| CommandExecuted 调用 markSessionIdle | P3 | 在 processEvent 内同步调用 messageHandler.markSessionIdle(event.sessionId, event.messageId) -> 触发 _messages.update + _parts.update + 内部 AppLogger.i。1 事件触发 >=3 个 StateFlow emission + 日志。频率低（每次命令完成），OK | [VERIFY: 248-251] |
| unreadMigrationScope 模式 | P3 | 独立 SupervisorJob+IO scope，App 启动一次性迁移 + seed。不阻塞 init，OK。但 scope 永不显式关闭（App 进程级单例），可接受 | [VERIFY: 57] |
| 构造后注入回调（4 个 fun interface） | P3 | directoryResolver/incompleteChecker/messageForceCompleter/messageRefresher 通过 var 注入。非线程安全（var 无 volatile），但只在 init 阶段写一次。OK | [VERIFY: SessionStateService.kt:48-51 / EventDispatcher.kt:63-80] |

### 2.3 MessageDataDelegate（10 路 combine 主消费方）

正确的设计：
- chatMessageCache 复用未变 ChatMessage 实例，消除每 48ms 全量重建 [VERIFY: ui/screens/chat/MessageDataDelegate.kt:103-104 / 220-234]
- 移除 combine 内的重复排序 [VERIFY: 182-185]
- DEBUG-only 异常捕获返回空态，避免 combine 崩溃 [VERIFY: 255-258]

风险点（重点）：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| **combine 索引错位 bug** | **P1** | 10 路 combine 第 8 参 statusFlow，第 9 参 getActiveToolProgressForSession。但代码同时读 args[8] as Map<String, SessionStatus>（正确）和 args[8] as? List<ToolProgressInfo>（错，应 args[9]）。后果：progressList 永远为 null -> progressOutputs = emptyMap() -> 工具进度输出注入永久失效。同时第 9 个上游实际上完全未被消费——它只是触发了 combine 重组却没影响输出。性能副作用小（chatMessageCache 仍按引用稳定缓存），但功能错误 | [VERIFY: ui/screens/chat/MessageDataDelegate.kt:150,166,172] |
| sseJob 与 messageListState 双订阅同源 | P2 | startObservingMessages() 中 combine(getMessagesFlow, getParts) 与 messageListState 中 observeMessages(=getMessagesFlow) + getAllPartsMap(=_parts) 观察同一对数据源。每个 _messages.update 或 _parts.update 触发两个独立 combine 同时重组。sseJob 仅为了写 _rawMessagesList（供 fixIncompleteMessagesIfIdle）和 _messagesList（供 TokenStats / markSessionRead）。性能放大 2x | [VERIFY: MessageDataDelegate.kt:142-143 vs 319-333] |
| 10 路 combine 无 distinctUntilChanged 兜底 | P2 | combine 的上游部分有 StateFlow（自带 distinctUntilChanged），但 getMessagesFlow/getAllPartsMap 是 Flow.map——.map { it[sessionId] ?: emptyList() } 在上游每次 emission 都产生新 List 实例（即使内容相同）。每次 SSE 影响其他会话的消息也会触发当前会话 combine 重组 | [VERIFY: data/repository/ChatRepositoryImpl.kt:92-98] |
| chatMessageCache 仅按 sessionId 切换清理 | P3 | 切换会话时 clear。但在同一会话内长会话（消息持续累积）时 cache 永不清理——内存占用线性增长。1896 条消息每条 ChatMessage（含 List<Part>），估算 < 1MB，可接受。无 TTL/上限 | [VERIFY: 220-223] |
| interactionState 7 路 combine | P3 | 较小，每次 _isLoading/_error/isSending 变化触发重组。getPermissionsWithChildren/getQuestionsWithChildren 在 lambda 内同步遍历，O(N) 但 N 小。OK | [VERIFY: 270-297] |

### 2.4 MessageEventHandler（消息域共享状态存储）

正确的设计：
- delta 48ms 批处理（scheduleFlush 不取消进行中的定时器，防止饥饿）[VERIFY: data/repository/handler/MessageEventHandler.kt:76-84]
- assistantMessageIds 用 ConcurrentHashMap.newKeySet()（RS-009）[VERIFY: 57]
- 移除了 MessageUpdated 内的 O(n) 扫描日志（DIAG 清理）[VERIFY: 144-146]
- mergePart 的"更长文本胜出"语义保护 SSE 流式文本 [VERIFY: 286-301]

风险点（重点）：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| **markSessionIdle 的日志在 _messages.update CAS lambda 内** | **P1** | MutableStateFlow.update 文档明确："the function may be called multiple times if the value is being concurrently updated."。在高频 SSE + forceComplete 并发场景下，CAS 重试会导致 AppLogger.i("UnreadDiag", "[markIdle] ...") 被多次调用，产生重复 INFO 日志条目（持久化到 Room）。更严重的是 lambda 内的副作用违反 StateFlow.update 的纯函数约定，理论上还可能掩盖真实状态变化 | [VERIFY: MessageEventHandler.kt:567-582 (尤其 575)] |
| handleMessagePartUpdated 诊断日志在 _parts.update CAS lambda 内 | P1 | 同上问题。Text part 长度不一致时 AppLogger.w("[PartUpdated] ...") 在 update lambda 内，CAS 重试会重复输出 WARN 日志。SSE 活跃期间每次 part 更新都比长度，可能每秒多次 | [VERIFY: MessageEventHandler.kt:238-272 (尤其 250-258)] |
| upsertSsePriority / upsertRestAuthority 诊断日志在 _parts.update 的 .mapValues 内 | P2 | 虽然 .mapValues 在 .update 的 lambda 内，但 .mapValues 是构造新 Map 的纯操作。但仍属于"诊断残留"代码——每次 upsert 都遍历 incoming parts 检查长度。SSE 双写路径走 upsertSsePriority 时这些日志在调用线程做字符串拼接（"t=" + thread + ...）。AndroidLog.w 同步执行 + 持久化队列写入 | [VERIFY: MessageEventHandler.kt:419-428 / 463-472] |
| handleMessagePartDelta 每次 O(parts) 找 partType | P3 | _parts.value[event.messageId]?.firstOrNull { it.id == event.partId }——每个 delta（每秒 ~20）线性扫描 parts。单消息 parts 数通常 < 10，可接受 | [VERIFY: 341-345] |
| batchScope（SupervisorJob + Dispatchers.Default）fire-and-forget SSE 双写 | P2 | persistSseUpdate 在 batchScope.launch 中调用 store.upsertMessages。该 scope 与 ViewModel 生命周期解耦（App 级），App 退出时不取消——小写入批量化后频率可控（每 48ms 一批），但理论上无上限（多会话同时活跃时）。无背压控制（launch 创建无限制协程）。Room 单写者锁会自然串行化 | [VERIFY: MessageEventHandler.kt:71 / 194-204] |
| markSessionIdle 触发 2 次 StateFlow emission | P3 | _messages.update + _parts.update——每次调用放大 2x combine 重组。频率低（Idle 转移、CommandExecuted），OK | [VERIFY: 567-618] |

### 2.5 SessionNextEventHandler

正确的设计：每个事件类型对应独立 _xFlow.update，纯增量更新 [VERIFY: data/repository/handler/SessionNextEventHandler.kt:105-148]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| handleToolProgress 在 update lambda 内做 outputDelta = event.content.joinToString + map { ... } | P3 | 每次工具进度事件（高频）创建临时 String + 新 List。O(N_tools) 但 N 小。OK | [VERIFY: 171-187] |
| 9 个 StateFlow 持久化在内存 | P3 | 即使无事件也常驻。clearForSession 一次性清理。OK | [VERIFY: 64-91 / 242-252] |

### 2.6 AppLogger

正确的设计：
- Channel(capacity=500, DROP_OLDEST) + 单消费者协程批量化（每批 <=50，delay 150ms）[VERIFY: logging/AppLogger.kt:40-49 / 69-93]
- nextTimestamp CAS 保证时间戳严格递增（避免 LazyColumn key 冲突）[VERIFY: 185-195]
- shouldPersist 基于 minimumLevel 过滤（默认 INFO）[VERIFY: 197-200]
- initialize 用 synchronized 防重复启动消费者 [VERIFY: 60-65]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| write 在调用线程同步执行 androidWrite() | P3 | AndroidLog.d/i/w/e 是 native call，开销小。但 message 字符串拼接发生在调用方——调用 AppLogger.d(TAG, "...$var...") 时字符串模板在传参前已拼接，即使 shouldPersist 返回 false 也已付出拼接成本。高频路径（每 48ms 的 token 批处理、每个 SSE 事件）的调用方需自己加 if (BuildConfig.DEBUG) 门控 | [VERIFY: 154-175] |
| nextTimestamp CAS 自旋 | P3 | 同一毫秒内多个日志线程争用，每次 CAS 失败重试。最坏情况：N 个并发线程同一毫秒内打日志，O(N) 总自旋。N 受 SSE/事件率限制（< 100/ms），可接受 | [VERIFY: 185-195] |
| recordCrash 用 runBlocking flush | P3 | 仅崩溃时调用一次。runBlocking 在崩溃线程（通常非主线程）阻塞 750ms。可接受——崩溃时确保持久化优先 | [VERIFY: 128-141] |
| DROP_OLDEST 丢失日志 | P3 | 高频日志（>500/批）会静默丢弃。droppedEntries 计数器可观测。OK | [VERIFY: 38-48] |

### 2.7 StreamingOwnershipRegistry

正确的设计：极简 ConcurrentHashMap<String, String> + putIfAbsent 原子 claim [VERIFY: data/repository/StreamingOwnershipRegistry.kt:18-43]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| 所有权永不自动过期 | P3 | 若 SSE 连接异常断开且未触发 SessionDeleted/clearForServer，owners map 中条目永驻。SessionStateService 的 24h 防御清理不涵盖此 map。需依赖 SSE 重连逻辑的 release。低风险（重启 App 即清） | [VERIFY: 全文] |

### 2.8 UnreadBadgeService

正确的设计：
- 抽出后的语义清晰：maxCompleted 只增不减 + 服务器时刻派生 [VERIFY: data/repository/UnreadBadgeService.kt:20-31]
- 异步落盘 + seed 恢复兜底（替代旧 runBlocking）[VERIFY: 92-108]
- seedFromStorage 在 EventDispatcher init 异步调用，max 合并语义幂等 [VERIFY: 76-88 / EventDispatcher.kt:174-182]

风险点：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| persistAsync cancel-then-launch 模式 | P3 | persistJob?.cancel() 后启动新 job——每次状态变化都取消上次未完成的 DataStore 写。DataStore 写本身是串行的，cancel 已开始的写可能丢失中间值（但有 seed 恢复兜底）。高频状态变化时可能延迟落盘，但内存值正确，重启后 seed 合并恢复 | [VERIFY: 100-108] |
| onMessageCompleted 在调用线程读 _lastCompletedReplyTime.value + updateAndGet | P3 | 两次 .value 读取之间无锁——理论上有并发窗口，但 max 合并语义使其幂等。OK | [VERIFY: 43-49] |
| 没有背压/批量 | P3 | 每条完成消息触发一次 persistAsync。高频完成消息（多会话同时结束）会触发多次 DataStore 写（每次 cancel 上一次）。OK | [VERIFY: 48] |

### 2.9 DraftDataStore + DraftInputDelegate（草稿系统）

风险点（重点）：

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| **DraftDataStore.ensureLoaded 主线程 runBlocking** | **P1** | ensureLoaded 首次调用时 runBlocking { dataStore.data.first() }——在调用线程同步阻塞。getDraft 由 DraftInputDelegate.restorePersistedDraft 调用，后者由 ChatViewModel.init 同步调用 -> 构造 ChatViewModel 时主线程 runBlocking。首次进入任意会话阻塞主线程（DataStore 首次读 IO + JSON 解析，估算 50-200ms）。基线称"已修复 DraftDataStore runBlocking ANR（onCleared 改异步）"——但仅修复了 onCleared 路径，init 路径仍是主线程 runBlocking。drafts 字段缓存后后续访问命中内存，但首次代价仍在 | [VERIFY: data/repository/DraftDataStore.kt:34-50 (尤其 37) / ui/screens/chat/DraftInputDelegate.kt:197-205 / ui/screens/chat/ChatViewModel.kt:368-373] |
| DraftDataStore.persist 主线程 runBlocking | P1 | 同样 runBlocking { dataStore.edit { ... } }。当前调用方 DraftInputDelegate.saveDraft 已包在 scope.launch + withContext(IO) 内 -> runBlocking 在 IO 线程，OK。但 DraftDataStore 是 @Singleton 注入的 DraftRepository 接口实现，未来若有其他调用方在主线程直接调用 saveDraft/clearDraft（如 Test、UI 直接调用），就会阻塞主线程。接口契约未声明线程约束，是隐患 | [VERIFY: DraftDataStore.kt:97-107 / DraftInputDelegate.kt:179-191] |
| onCleared 兜底草稿持久化可能被取消 | P2 | viewModelScope.launch { withContext(NonCancellable) { draftDelegate.saveDraft() } }——外层 NonCancellable 保护，但 saveDraft 内部又是 scope.launch { withContext(IO) { ... } }（scope=viewModelScope）。内层 launch 创建的协程是 viewModelScope 的 child——scope cancel 时 child 也 cancel。NonCancellable 不会让 child 不被 cancel（它只保护当前协程的 cooperative cancellation）。结果：onCleared 触发的 saveDraft 可能在外层返回后立即被取消。500ms 防抖已能覆盖正常退出场景，但最后 <500ms 内的输入可能丢失 | [VERIFY: ChatViewModel.kt:428-437 / DraftInputDelegate.kt:179-191] |
| 500ms 防抖数据丢失窗口 | P3 | 用户连续输入时每 500ms 才落盘一次。force-stop/系统杀进程时最后 <500ms 的输入丢失。注释已承认此窗口。可接受 | [VERIFY: DraftInputDelegate.kt:132-147] |
| 草稿防抖保存与 onCleared 兜底双重保存 | P3 | 500ms 防抖 + onCleared saveDraft——退出时若有未落盘输入，两者都尝试保存。幂等（同 sessionId 同 draft 内容），OK | [VERIFY: DraftInputDelegate.kt:140-147 / ChatViewModel.kt:434-436] |
| 草稿保存顺序问题 | P3 | 多个 saveDraft 调用通过 scope.launch 异步执行——顺序不保证。但每次都基于 _draftText.value 当前快照，最终一致。OK | [VERIFY: DraftInputDelegate.kt:179-191] |

### 2.10 ChatViewModel

| 风险 | 等级 | 现象 | 证据 |
|------|------|------|------|
| **构造函数 serverConfig = runBlocking(Dispatchers.IO)** | **P2** | Hilt 在主线程构造 ViewModel——构造函数中的 runBlocking 阻塞主线程。注释称"resolveConnection 是本地 Room 读取（毫秒级）"。Room 首次冷启动 + 磁盘忙时可能 > 50ms。同一问题在 SessionListViewModel:99 也存在。两处都是 ViewModel 构造期同步阻塞 | [VERIFY: ChatViewModel.kt:93-96 / SessionListViewModel.kt:97-99] |
| markSessionRead 中 messageData.messagesList.value.filterIsInstance<...>().mapNotNull {...}.maxOrNull() | P3 | O(n) 扫描消息列表。每次离开会话调用一次（DisposableEffect onDispose）。n 通常 < 2000，可接受 | [VERIFY: ChatViewModel.kt:134-137 / ChatScreen.kt:462] |
| init 中 token stats tracker collect | P3 | messageData.messagesList.collect { ... filterIsInstance + sumOf + lastOrNull }——每次 messagesList emission 都 O(n) 计算。SSE 活跃期间每 48ms 触发一次，n=1896 时每次扫描全部 assistant 消息。实际累积成本可观但不在主线程（viewModelScope.launch） | [VERIFY: ChatViewModel.kt:335-365] |
| 11 个 delegate 集中在 ViewModel | P3 | ViewModel 充当门面，所有 delegate 在构造期初始化。构造函数副作用复杂（init 块含 4 个 launch 块）。可读性问题，非性能问题 | [VERIFY: ChatViewModel.kt:79-246 / 328-397] |

---

## 3. 补丁 vs 根因判定表

| Fix（commit） | 判定 | 理由 | 技术债残留 |
|---------------|------|------|-----------|
| **b07b7ccc**: 日志风暴（combine 每 48ms 4 条日志） | 根因修复 | 直接删除了 messageListState + sseJob 内的 DIAG 日志发射，是真正的性能根因 | 无。但同类诊断日志在 MessageEventHandler（markSessionIdle L575、handleMessagePartUpdated L255、upsertSsePriority L423、upsertRestAuthority L467）仍残留——未完成清理，本次审计发现 |
| **b07b7ccc**: MessageUpdated O(n) 扫描->索引化 | 根因修复 | assistantMessageIds 用 ConcurrentHashMap.newKeySet()，handleMessageUpdated 移除全量 filter 扫描 | 无 |
| **0eaac6dc**: DraftDataStore runBlocking ANR（onCleared 异步） | 补丁 | 仅修复 onCleared 路径的 ANR，未触及 DraftDataStore 内部的 runBlocking 本身。init 路径（restorePersistedDraft -> ensureLoaded）仍是主线程 runBlocking——本次审计发现 | 未完成：DraftDataStore.ensureLoaded/persist 仍是 runBlocking；ChatViewModel.init 仍同步调用 restorePersistedDraft |
| **e3ffeae7**: 草稿 500ms 防抖 | 根因修复 | 防抖是合理设计，解决频繁落盘问题 | onCleared 兜底路径的"内层 scope.launch 在外层 NonCancellable 内被 cancel"问题——本次审计发现 |
| **3d828265**: UnreadBadgeService 抽出 | 根因修复 | 消除 runBlocking 同步落盘，改异步 + seed 恢复 | 无 |
| **c6bbd71a**: StreamingOwnershipRegistry 抽出 | 根因修复 | 多服务器去重独立化，ConcurrentHashMap.putIfAbsent 原子 | 无自动过期机制（低风险） |
| **a7aec358**: REST 校验 limit=0->50 | 根因修复 | 解决了 L3 校验全量拉取大会话的 slowUI。注释充分 | 无 |
| **b07b7ccc**: SQLite IN 999 变量超限分块 | 补丁 | 解决了 MessageStore 的 Room IN 查询超限。需要查看 MessageStore 才能确认是否真正的根因修复（分块 vs 临时绕过） | [需要探索]：未审计 MessageStore.kt |
| **ff192fd5**: APPEND_ONLY 合并策略 bug | 根因修复 | 把"替换"改回"合并"——修复了正确性 bug | 无 |
| **c5e0ea56**: 上滑自动加载更多（snapshotFlow + 独立游标 + 防风暴退避） | 根因修复 | 三连环根因修复（isScrollInProgress 阻塞、NETWORK 游标死循环、防风暴） | [需要探索]：MessagePaginationDelegate 详细审计未做 |
| **ec875ff7**: PulsingDots 最小显示时长 | 补丁 | UX 修复，非性能修复。不涉及状态/事件/日志 | 无 |

---

## 4. 系统性问题清单（按风险排序）

### S1. ChatViewModel.init 主线程 runBlocking 链（P1）

现象：进入会话（首次或非首次）时主线程同步阻塞，潜在 ANR。

代码证据：
1. ChatViewModel.<init> line 93-96：runBlocking(Dispatchers.IO) { serverRepository.getServer(serverId) } [VERIFY: ChatViewModel.kt:93-96]
2. ChatViewModel.init line 368-373：同步调用 draftDelegate.restorePersistedDraft() -> draftRepository.getDraft -> DraftDataStore.ensureLoaded -> runBlocking { dataStore.data.first() } [VERIFY: ChatViewModel.kt:368-373 / DraftDataStore.kt:34-50]

根因：
- ViewModel 构造在 Hilt 主线程执行，多个属性/初始化块需要同步可空的 serverConfig、draft——选择了 runBlocking 同步派生而非异步 StateFlow
- DraftDataStore 用 runBlocking 是因为 DraftRepository 接口契约为同步（getDraft(...): Draft?），DataStore 是异步的，runBlocking 是接口适配的捷径

建议修复方向：
- serverConfig 改为 StateFlow<ServerConfig?> + TerminalDelegate/serverConn 改为派生 flow 或 lateinit
- DraftRepository 接口改为 suspend：suspend fun getDraft(...): Draft?，调用方在 viewModelScope.launch 内 await；或改为 Flow<Draft?>
- DraftDataStore 内部 runBlocking 改为 withContext(IO) 配合 suspend

影响面：所有进入会话场景；低端设备/磁盘忙时成 ANR；SessionListViewModel 同样问题（line 97-99）。

### S2. StateFlow.update CAS lambda 内的副作用（P1）

现象：高频 SSE 场景下重复日志输出 + 违反纯函数约定 + 潜在掩盖状态变化。

代码证据：
1. MessageEventHandler.markSessionIdle line 567-582：AppLogger.i("UnreadDiag", ...) 在 _messages.update { ... map { if (...) { AppLogger.i(...); msg.copy(...) } } } 内 [VERIFY: MessageEventHandler.kt:567-582 (尤其 575)]
2. MessageEventHandler.handleMessagePartUpdated line 238-272：AppLogger.w("[PartUpdated] ...") 在 _parts.update { ... if (old is Part.Text ...) { AppLogger.w(...) } ... } 内 [VERIFY: MessageEventHandler.kt:238-272 (尤其 250-258)]

根因：
- MutableStateFlow.update 文档明确允许 CAS 重试（lambda 多次执行）。开发者把诊断日志当成"无害副作用"放进 lambda
- 这些诊断日志属于 b07b7ccc "日志风暴修复"清理时遗漏的残留——DIAG 注释清理了，但这些位置没清

建议修复方向：
- 把日志移到 .update 外（先 update 拿到结果，再 log）
- 或彻底删除诊断日志（与 b07b7ccc 一致策略）
- 对所有 _*.update { ... } lambda 做 lint：禁止副作用

影响面：高频 SSE + 并发场景下日志放大 2-N 倍；INFO 级别会持久化到 Room（即使 DEBUG 关闭）；CAS 重试影响测量准确性。

### S3. MessageDataDelegate.messageListState combine 索引错位（P1，功能性 bug）

现象：工具进度输出（tool.progress 内容）累积注入到 Part.Text.output 永久失效，用户在 UI 看不到工具执行中的实时 output。

代码证据：
- [VERIFY: ui/screens/chat/MessageDataDelegate.kt:150,166,172]
- combine 第 8 参 statusFlow、第 9 参 getActiveToolProgressForSession(sid)
- line 166 val statuses = args[8] as Map<String, SessionStatus> ✅ 正确
- line 172 val progressList = args[8] as? List<ToolProgressInfo> ❌ 应为 args[9]
- 后果：progressList 永远为 null（cast Map 失败）-> progressOutputs = emptyMap() -> ToolProgressOutputInjector.inject(rawParts, emptyMap()) 永远不注入

根因：复制粘贴/重排 combine 参数时遗漏更新索引。

建议修复方向：
- 改为 args[9]
- 或用 combine 的类型安全变体（<=5 参有专门重载），>5 参改用嵌套 combine 或 data class 包装

影响面：工具进度 UI 功能错误（非性能问题，但是审计中发现的代码缺陷）。无性能副作用（chatMessageCache 仍正常）。

### S4. sseJob 与 messageListState 双订阅同源（P2）

现象：每个 SSE 事件触发两个独立 combine 同时重组，CPU 工作量翻倍。

代码证据：
- startObservingMessages 的 sseJob 内 combine：getMessagesFlow(sid) + getParts(sid) [VERIFY: MessageDataDelegate.kt:319-334]
- messageListState 的 combine 上游 1+2：observeMessages(sid)(=getMessagesFlow) + getAllPartsMap()(=_parts) [VERIFY: MessageDataDelegate.kt:142-143]
- 两者观察完全相同的两个源（消息 + parts），但目的不同：
  - sseJob 写 _rawMessagesList（供 fixIncompleteMessagesIfIdle 用未过滤的）
  - sseJob 写 _messagesList（供 TokenStatsTracker / markSessionRead）
  - messageListState 是 UI 主消费

根因：sseJob 的产出（_messagesList）实际上 messageListState 内部也计算了——但 messageListState 没暴露"未过滤 raw messages"。sseJob 为 raw messages 而存在。

建议修复方向：
- 让 messageListState 同时暴露 rawMessages 字段（去除 sseJob 路径）
- 或将 _messagesList 改为派生自 messageListState（保留 raw 用单独轻量 flow）
- 评估 _messagesList 的两个消费者（TokenStats / markSessionRead）是否能改读 messageListState.messages

影响面：SSE 活跃期间每次 48ms 触发 2x combine 重组（每次 O(n)）。1896 条消息场景下显著。

### S5. ChatViewModel 构造期 serverConfig runBlocking（P2）

现象：ViewModel 构造（Hilt 主线程）阻塞。

代码证据：[VERIFY: ChatViewModel.kt:93-96 / SessionListViewModel.kt:97-99]

根因：serverConfig 是 val，被 serverConn（也是 val）依赖，进而被 TerminalDelegate 构造期使用。整条依赖链是 eager 的，难以异步化（注释承认"纯异步方案需重构 TerminalDelegate 的 StateFlow 暴露模式，超出本任务范围"）。

建议修复方向：将 TerminalDelegate 改为接收 StateFlow<ServerConfig?>，所有暴露改为 flow；或在后台预加载 serverConfig（进入 Chat 路由前）。

影响面：低端设备首次进入会话可见卡顿；Room 首次冷启动可能 > 50ms。

### S6. onCleared 草稿兜底的协程取消语义（P2）

现象：onCleared 触发的 saveDraft 可能在外层 NonCancellable 返回后立即被 viewModelScope cancel 取消，草稿丢失窗口扩大。

代码证据：[VERIFY: ChatViewModel.kt:428-437 / DraftInputDelegate.kt:179-191]

根因：saveDraft() 内部 scope.launch { withContext(IO) { ... } } 创建 child 协程——NonCancellable 保护外层 coroutine 的 cancellation，但新 launch 的 child 仍受 scope 状态约束。

建议修复方向：
- saveDraft 改为 suspend，由 onCleared 直接 withContext(IO) { draftRepository.saveDraft(...) }
- 或在 onCleared 用独立 scope（CoroutineScope(SupervisorJob() + IO).launch { ... }），不依赖 viewModelScope

影响面：用户在最后 <500ms 输入并立即退出会话时草稿可能丢失。

### S7. MessageEventHandler 持久化 batchScope 无生命周期管理（P2）

现象：App 级 SupervisorJob scope，App 退出时不取消；多会话同时活跃时 fire-and-forget 协程数无上限。

代码证据：[VERIFY: MessageEventHandler.kt:71 / 194-204]

根因：选择 fire-and-forget 简化 SSE 双写——SSE 频率高（每 48ms 一批），等待 Room 写会增加 SSE 处理延迟。

建议修复方向：
- 用 SharedFlow 或 actor 模式串行化写入请求
- 或限制并发（Semaphore）
- 加 ApplicationScope 注入（与 SessionStateService 一致），显式生命周期

影响面：理论上多会话同时活跃时可能产生大量协程；Room 单写者锁自然串行化；实际影响小。

### S8. AppLogger 字符串拼接未门控（P2）

现象：高频路径调用 AppLogger.d/i/w/e(TAG, "...$var...") 时字符串模板在传参前已拼接，即使 shouldPersist 返回 false 也付出成本。

代码证据：
- AppLogger.write 接收 message: String 参数——已是拼接后的字符串 [VERIFY: AppLogger.kt:154-175]
- 高频调用方未加 if (BuildConfig.DEBUG) 门控：
  - EventDispatcher:249 AppLogger.i("UnreadDiag", "[CommandExecuted] session=${event.sessionId.take(12)} ...")（每次 CommandExecuted）
  - MessageEventHandler:157 if (BuildConfig.DEBUG) AppLogger.d("UnreadDiag", "[MsgUpdated] ...") ✅ 有门控
  - MessageEventHandler:575 AppLogger.i("UnreadDiag", "[markIdle] ...") ❌ 无门控
  - MessageEventHandler:255 AppLogger.w(TAG, "[PartUpdated] t=$thread msg=$messageId ...") ❌ 无门控

根因：AppLogger 接口设计未提供 lambda 惰性求值版本（如 d(tag) { "msg" }）。

建议修复方向：
- 提供 inline fun d(tag: String, msg: () -> String) overload（inline 避免 lambda 分配）
- 高频调用方迁移到 lambda 版本
- 全局审计所有 AppLogger.x 调用，对热路径加 DEBUG 门控

影响面：SSE 活跃期间每 48ms 多次调用，每次都拼接字符串（即使 INFO 级别被过滤）。

### S9. combine 上游无 distinctUntilChanged 兜底（P2）

现象：每次上游 emission（即使内容相同）触发 combine 重组。

代码证据：
- chatRepository.getMessagesFlow 内部 emitAll(eventDispatcher.messages.map { it[sessionId] ?: emptyList() }) [VERIFY: ChatRepositoryImpl.kt:92-98]
- eventDispatcher.messages 是 MessageEventHandler._messages.asStateFlow()——自带 distinctUntilChanged
- 但 .map { it[sessionId] ?: emptyList() } 后是新的 Flow，每次 _messages.update（任意 sessionId 的变化）都 emit 一个新的 List 实例（即使当前 sessionId 的内容没变）
- getAllPartsMap 直接返回 _parts StateFlow，OK
- getActiveToolProgressForSession 内部 .map { map -> map[sessionId]?.map { it.toDomain() } }——.map { it.toDomain() } 每次创建新 List，即使内容相同 [VERIFY: ChatRepositoryImpl.kt:461-462]

根因：派生 flow 没有 distinctUntilChanged / distinctUntilChangedBy 兜底。

建议修复方向：
- getMessagesFlow 加 .distinctUntilChanged（List 引用比较 + 内容快照比较）
- getActiveToolProgressForSession 加 .distinctUntilChanged()
- 或在上游源头用 conflate() 减少重组频率

影响面：多会话场景下其他会话的消息变化触发当前会话 combine 重组。

---

## 5. combine 依赖图（emission 放大分析）

```
SSE MessagePartDelta 事件（最热路径）
  |
  v scheduleFlush (48ms 防抖，不取消进行中)
  v flushPendingDeltas
  |
  +-> _parts.update(CAS) ----------------------+
       |                                        |
       | 触发以下下游（同一 emission）：          |
       |                                        |
       +-> chatRepository.getAllPartsMap() --+  |
       |   (= _parts.asStateFlow())          |  |
       |   下游：messageListState combine    |  |
       |   上游 #2                            |  |
       |                                     v  |
       |                              [messageListState 重组]
       |                              O(n): visible.map + chatMessageCache + suppressRepeatedPatchHashes
       |                                     ^
       +-> chatRepository.getParts(sid) ----+  |
       |   (= _parts.map { flatten+filter })|  |
       |   下游：sseJob combine (2 路)        |  |
       |   上游 #2                            |  |
       |                                     v  |
       |                              [sseJob 重组]
       |                              写 _messagesList + _rawMessagesList + _partsList
       |                              （触发 TokenStats collect 重组）
       |
       +-> persistSseUpdate fire-and-forget (batchScope, 不阻塞 emission)
           +-> Room upsert（异步）

  放大倍数：
  - 1 MessagePartDelta -> 48ms 缓冲 -> 1 _parts.update
  - 1 _parts.update -> 2 个 combine 同时重组（messageListState + sseJob）
  - 每次重组 O(n) 扫描（n = visible 消息数）
  - 在 1896 条消息场景下，每 48ms 一次重组 = ~21 次/秒 x 2 = 42 次/秒 O(n) 扫描

  MessageUpdated 事件（次要热路径）
  +-> _messages.update --> messageListState combine 上游 #1 (observeMessages)
  |                    --> sseJob combine 上游 #1 (getMessagesFlow)
  |                    （同样双订阅，2x 重组）
  |
  +-> assistantMessageIds.add (并发安全)
  +-> _parts.update（若 User 消息播种）
  +-> persistSseUpdate fire-and-forget
  +-> (跨 handler) onMessageCompleted -> _lastCompletedReplyTime.update + persistAsync

  SessionNext.ToolProgress 事件
  +-> _activeToolProgress.update -> messageListState combine 上游 #9 (getActiveToolProgressForSession)
                                （只触发 1 个 combine，OK）

  session.status 事件
  +-> SessionStateService.applyTransition
      +-> _fsmStates.update -> statusFlow --> messageListState combine 上游 #8
      |                    --> activityFlow（无消费者在本审计范围）
      +-> _histories.update（仅诊断）
      +-> 副作用：forceComplete -> markSessionIdle -> _messages.update + _parts.update
                                  -> 触发更多 combine 重组（见 MessageUpdated + MessagePart 路径）
                  isSuspicious -> triggerRestValidation -> REST + memory upsert（异步）
```

关键放大点：
1. 每个 SSE 事件平均放大 2x combine 重组（sseJob + messageListState 同源双订阅）
2. markSessionIdle / forceComplete 触发链：1 次 Idle 转移 -> markSessionIdle -> _messages.update + _parts.update -> 4 个 combine 重组（2 个上游 x 2 个订阅源）
3. token stats collect 在 messageListState 之外（独立订阅 messagesList）——sseJob 写 messagesList 又触发 token stats collect 重组——额外 1x O(n) 扫描

---

## 6. 维度覆盖检查

| 维度 | 覆盖 | 关键发现 |
|------|------|----------|
| 1. combine 链 | OK | MessageDataDelegate.messageListState 是 10 路 combine，sseJob 是 2 路 combine——同源双订阅导致 2x 重组。combine 索引错位 bug（args[8] vs args[9]） |
| 2. 事件分发 | OK | 注册表 O(1) 路由 + 所有权去重——设计良好。横切处理（markSessionIdle / onMessageCompleted）频率低。无事件放大异常 |
| 3. 日志系统 | OK | Channel DROP_OLDEST 500 + 单消费者批量化——设计良好。但调用方字符串拼接未门控；update lambda 内副作用导致重复日志 |
| 4. 主线程风险 | OK | ChatViewModel.init runBlocking（serverConfig + restorePersistedDraft）；SessionListViewModel 同样问题 |
| 5. 泄漏 | OK | batchScope/unreadMigrationScope 是 App 级 SupervisorJob（设计上不释放）；ChatViewModel scope = viewModelScope（正常）；无 Activity/Context 持有泄漏 |
| 6. 补丁 vs 根因 | OK | 见 §3 判定表——大多数是根因修复，但 DraftDataStore runBlocking 仅修了 onCleared 路径（补丁），init 路径仍是主线程 runBlocking |

---

## 7. 附：本次审计新发现的代码缺陷（性能审计附带）

1. **MessageDataDelegate.kt:172 combine 索引错位** —— args[8] 应为 args[9]（工具进度输出注入失效）。功能性 P1 bug。
2. **MessageEventHandler.kt:575 / 255 / 423 / 467 残留诊断日志** —— 未完成清理（b07b7ccc 清理时遗漏）。
3. **ChatViewModel.kt:93-96 + SessionListViewModel.kt:97-99 构造期 runBlocking** —— 应改异步。
4. **DraftDataStore.kt:37 + 99 主线程 runBlocking 路径** —— onCleared 修了，init/直接调用没修。
5. **ChatViewModel.kt:434-436 onCleared 兜底协程取消语义** —— 内层 scope.launch 会被 viewModelScope cancel 取消。
6. **MessageEventHandler.batchScope 无生命周期管理** —— App 级 SupervisorJob，无上限。

---

## 8. 审计总结

### 8.1 风险清单摘要

- **P0**：0 项
- **P1**：3 项
  - S1 ChatViewModel.init 主线程 runBlocking 链（含 DraftDataStore.ensureLoaded）
  - S2 StateFlow.update CAS lambda 内的副作用（markSessionIdle + handleMessagePartUpdated）
  - S3 MessageDataDelegate.messageListState combine 索引错位（功能性 bug）
- **P2**：6 项（S4-S9）
- **P3**：~20 项（已逐项列出，均可接受）

### 8.2 最严重的 3 个问题简析

**最严重 #1：S1 - ChatViewModel.init 主线程 runBlocking 链**
首次进入任意会话时主线程同步阻塞两次（serverConfig + restorePersistedDraft）。低端设备/磁盘忙时直接 ANR。基线称"已修复 DraftDataStore runBlocking ANR"——实际仅修了 onCleared 路径，init 路径完整保留。修复涉及 DraftRepository 接口异步化 + TerminalDelegate StateFlow 化，影响面较大。

**最严重 #2：S2 - StateFlow.update CAS lambda 内的副作用**
markSessionIdle / handleMessagePartUpdated 把 AppLogger 调用放在 _*.update { } lambda 内。MutableStateFlow.update 在并发 CAS 重试时 lambda 会多次执行——导致日志被多次持久化到 Room（INFO 级别即使 DEBUG 关也会持久化），同时违反 update 的纯函数约定。属于 b07b7ccc "日志风暴修复"的遗漏清理。

**最严重 #3：S3 - combine 索引错位**
MessageDataDelegate.kt:172 错把 args[8]（statuses Map）当作 args[9]（progressList）。后果：工具进度输出注入永久失效（用户看不到 tool.progress 的累积 output）。属于功能性 bug 而非性能问题，但本次审计发现的重要代码缺陷。修复只需改一个字符（args[8] -> args[9]）。

### 8.3 系统性观察

1. **日志治理未完成**：b07b7ccc 修复了 messageListState/sseJob 的日志风暴，但 MessageEventHandler 内多处诊断日志（markSessionIdle、handleMessagePartUpdated、upsertSsePriority、upsertRestAuthority）仍是"DIAG 残留"，且部分位于 update CAS lambda 内（双重问题）。

2. **主线程 runBlocking 治理未完成**：基线称"已修复 DraftDataStore runBlocking ANR"——但仅修了 onCleared 路径。ChatViewModel 构造期 + init 块仍有 2 处 runBlocking（serverConfig + restorePersistedDraft 间接）。SessionListViewModel 同样问题。

3. **StateFlow.update lambda 纯度约束缺失**：多处 update lambda 内有副作用（日志）。需要 lint 或 code review 检查清单。

4. **双订阅同源是性能放大主因**：sseJob + messageListState 观察同一对数据源——每个 SSE 事件触发 2x combine 重组。这是 1896 条消息场景下真机掉帧的潜在放大器。

5. **DraftRepository 同步接口契约不匹配 DataStore 异步实现**：导致 DraftDataStore 内部 runBlocking 桥接。修复需接口异步化。

---

报告结束。
