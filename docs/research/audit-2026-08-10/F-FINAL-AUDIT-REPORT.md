# F — 最终系统审计报告（v0.2.0..HEAD，4 路代码审计 + 1 路实测交叉验证）

> 审计时间：2026-08-10
> 输入：A（渲染管线）+ B（数据/分页/归档）+ C（状态/事件/日志）+ D（补丁 vs 根因历史）+ E（实测 gfxinfo/logcat）
> 方法：只读证据文件交叉验证 + 去重合并 + 关联分析，未重新探索代码库
> 基线：v0.2.0 (e7b84fe6) → HEAD (2df4a08a)，约 110 commit

---

## 1. 执行摘要

### 1.1 总体结论

**v0.2.0 之后的修复质量整体良好**：33 个根因修复（80%）vs 3 个纯补丁 + 7 个混合（20%），五条 SSE 滚动铁律全部正确遵守。但仍存在 **1 个 P0 数据安全风险**、**7 个 P1 功能/性能缺陷**、**约 14 个 P2 改进项**。最关键的是：**实测 janky 44.38%（71/160 帧）、99th 帧耗时 300ms（严重超支 18 倍）**——这是真实的用户体验问题，需要本轮修复。

### 1.2 数字一览（去重合并后）

| 级别 | 数量 | 说明 |
|------|------|------|
| **P0** | **1** | 数据安全：DatabaseRecovery 删库范围过宽（B+D 双路确认） |
| **P1** | **7** | 功能 bug（combine 索引错位）+ 性能（反射/诊断残留/写入排序/CAS 副作用）+ 主线程阻塞（ChatViewModel.init）+ 并发（loadOlder 竞态）|
| **P2** | ~14 | 性能放大（双订阅同源）、UX 反模式（400ms 延迟）、补丁链（草稿）、技术债（分页状态散落 TD-1）等 |
| **P3** | ~25 | 低频/理论风险，均可接受 |

### 1.3 最严重的 3 项

1. **P0-1 DatabaseRecovery catch SQLiteException 基类 → 误删整个数据库**
   SQLiteException 是基类——`SQLiteDatabaseLockedException`（非损坏）/`SQLiteConstraintException`（非损坏）/`SQLiteFullException`（磁盘满）都会触发删库。所有用户、偶发但灾难性：缓存消息 + 归档 + 日志全清。**[B P0-1 + D TD-5 双路确认]**

2. **P1-7 combine 索引错位 args[8] vs args[9] → 工具进度 UI 永久失效**
   `MessageDataDelegate.kt:172` 错把 `args[8]`（statuses Map）当作 `args[9]`（progressList）。后果：`progressList` 永远为 null → `progressOutputs = emptyMap()` → 工具进度 output 注入永久失效。**这是功能性 bug，修复只需改一个字符。** [C S3 单路发现]

3. **P1-2 诊断代码残留 + P1-6 CAS lambda 副作用 → 实测日志风暴（576 条/s）**
   b07b7ccc 清理了 MessageDataDelegate 的日志风暴，但 MessageEventHandler（markSessionIdle/handleMessagePartUpdated/upsertSsePriority/upsertRestAuthority）+ ChatMessageList（JUMP 检测、每 item composed 日志）+ NetTrace hot path 仍是 DIAG 残留。实测：流式期间 **`PartUpdated` 110 条/10s（11条/s）、`UnreadDiag` 16 条/10s**，部分还在 `update{}` CAS lambda 内会被 CAS 重试加倍。**[A/B/C/D/E 五路全部确认——最高置信度]**

---

## 2. 交叉验证矩阵

> 标记：● 主发现 · ○ 提及/间接相关 · — 未覆盖 · **★ 多路确认项**（≥3 路）

### 2.1 发现 × 来源报告

| # | 发现 | A 渲染 | B 数据 | C 状态 | D 历史 | E 实测 | 确认路数 |
|---|------|--------|--------|--------|--------|--------|----------|
| 1 | **日志风暴残留**（DIAG 代码 + NetTrace + CAS lambda 副作用）| ● 环节 F | ● P2-4 | ● S2/S8 | ● TD-8/模式 B | ● 576 条/s | **★ 5** |
| 2 | **DatabaseRecovery 删库范围过宽** | — | ● P0-1 | — | ● TD-5/§2.7 | — | 2 |
| 3 | **upsert 写入路径 O(n log n) 排序** | ○ §3 表 | ● P1-2 | — | ● TD-9 | — | **★ 3** |
| 4 | **诊断代码残留（ChatMessageList）** | ● 环节 F | — | — | ● 模式 B | — | 2 |
| 5 | **草稿持久化补丁链** | — | — | ● S6/§3 表 | ● TD-3/§2.3 | — | 2 |
| 6 | **分页状态散落（9 个可变状态成员）** | — | ○ §4 图 | — | ● TD-1（高）/模式 A | — | 2 |
| 7 | **草稿 runBlocking 主线程阻塞** | — | — | ● S1/S5/§3 表 | ○ §2.3 | — | 2 |
| 8 | **过渡动画 400ms 反模式** | — | — | — | ● TD-2/§2.2 | — | 1 |
| 9 | **L3 limit=50 魔法常量** | — | — | — | ● TD-4/§2.1 | — | 1 |
| 10 | **反射依赖 Compose internal** | ● 环节 E | — | — | — | — | 1 |
| 11 | **combine 索引错位（args[8] vs args[9]）** | — | — | ● S3 | — | — | 1 |
| 12 | **loadOlderMessages 并发竞态** | — | ● P1-1 | — | ○ c5e0ea56 | — | 2 |
| 13 | **sseJob + messageListState 双订阅同源** | ○ 环节 C | — | ● S4 | — | ○ janky 贡献 | **★ 3** |
| 14 | **AppLogger 字符串拼接未门控** | ○ 环节 F | — | ● S8 | ○ 模式 B | ● PartUpdated 频率 | **★ 3** |
| 15 | **combine 无 distinctUntilChanged 兜底** | — | — | ● S9 | — | — | 1 |
| 16 | **长会话无消息窗口裁剪** | ● 环节 H | — | — | — | — | 1 |
| 17 | **100ms ticker 叠加 48ms flush** | ● 环节 G | — | — | — | — | 1 |
| 18 | **catch Exception 吞 CancellationException** | — | — | — | ● TD-6（已修） | — | 1 |

### 2.2 实测证据 vs 代码结论的对应关系

| 实测指标 | 数值 | 对应代码层风险（按贡献度推断） |
|---------|------|------------------------------|
| **Janky frames** | 71/160 = **44.38%** | 组合贡献：① SSE 日志风暴（P1-2/P1-6）logcat native 写入 CPU 抢占 ② 双订阅同源 2x combine（P2-S4）每 48ms O(n) 扫描 ③ 100ms ticker（P2-A）流式消息额外重组 ④ 写入路径 O(n log n) 排序（P1-4，batchScope 后台，贡献较小）|
| **99th percentile** | **300ms**（3 帧达到此值；另有 200ms×1, 150ms×1, 81ms×1）| 最可能来源：① 进入会话首帧的 ChatViewModel.init runBlocking（P1-5，冷启动 IO）② AppLogger Channel flush 同步写 Room 的批处理尖峰 ③ 反射 layout{} 补偿（P1-1）触发测量/布局重做 |
| **Number Slow UI thread** | **48**（30%）| 日志风暴（native call 在调用线程）+ CAS lambda 副作用重试是主要嫌疑——与 576 条/s 的 logcat 频率直接相关 |
| **Number Frame deadline missed** | **68**（42.5%）| 与 Janky 71 接近，说明 janky 主要源于 deadline 而非 GPU/渲染管线本身 |
| **PartUpdated 日志频率** | 110 条/10s = **11条/s**（stream1 活跃）· 70 条/44s = **1.6条/s**（stream2 低活跃）| 直接验证 P1-6（handleMessagePartUpdated line 250-258 的 `AppLogger.w("[PartUpdated] ...")`），且部分在 `update{}` CAS lambda 内——CAS 重试实际频率可能 2x |
| **UnreadDiag 日志频率** | 16 条/10s = 1.6条/s（stream1）· 9 条/44s（stream2）| 验证 P1-6（markSessionIdle line 575 的 `AppLogger.i("UnreadDiag", "[markIdle] ...")`）|
| **NetTrace 日志** | 8 条/10s · 5 条/44s | 验证 D TD-8（NetTrace hot path）+ B §3 表（"删 MsgDiag 又加 NetTrace"）|

**实测缺失（待 E 实测补充）**：
- ✗ 工具进度 UI 实际显示效果（P1-7 combine 索引错位是否用户可见）
- ✗ DatabaseRecovery 删库的实际触发条件（P0-1 在生产环境的频率）
- ✗ 长会话（>2000 条）的内存占用与 GC 压力（P2 长会话无窗口）
- ✗ 反射 hack 的实际运行时稳定性（P1-1 是否已触发过 NoSuchFieldError）
- ✗ 草稿 500ms 防抖窗口内的实际数据丢失次数（P2 TD-3）

---

## 3. 统一风险清单（P0/P1/P2/P3 分级）

### 3.1 P0（1 项）—— 必修，阻塞发版

#### **P0-1：DatabaseRecovery 捕获 SQLiteException 基类 → 误删整个数据库**

| 维度 | 内容 |
|------|------|
| **现象** | 任意 `SQLiteException`（含非损坏的临时错误）触发 `context.deleteDatabase(DATABASE_NAME)`，缓存消息 + 归档 + 日志全部清零 |
| **代码证据** | `DatabaseRecovery.kt:29-38`（catch SQLiteException → deleteDatabase → null）· 调用点 `MessageStore.kt:47, 226, 237, 242, 247, 269, 296`（7 处全包）**[VERIFY: B P0-1]** |
| **根因** | `SQLiteException` 是基类——`SQLiteDatabaseLockedException`（非损坏）/`SQLiteConstraintException`（非损坏）/`SQLiteDiskIOException`/`SQLiteFullException`（磁盘满）都会触发删库。唯一应触发的是 `SQLiteDatabaseCorruptException` |
| **修复方向** | ① 改为只 catch `SQLiteDatabaseCorruptException`；② 或用 `Room.databaseBuilder().fallbackToDestructiveMigration()` 声明式；③ 返回 `Result<T>` 区分"损坏"（删）与"临时错误"（重试）|
| **影响面** | 所有用户；偶发但灾难性（D §2.7 指出"注释'日志/内存视图不受影响'错误——LogStore 也在同一 Room"）|
| **来源** | **B P0-1** + **D TD-5**（2 路确认）· **6fdff190 混合判定** |

---

### 3.2 P1（7 项）—— 应修，功能 bug 或高频路径风险

#### **P1-1：反射依赖 Compose internal 字段（requestScrollToItemNoCancel）→ Compose 升级必崩**

| 维度 | 内容 |
|------|------|
| **现象** | 高度补偿通过反射访问 `LazyListState` private 字段（`scrollPosition`、`requestPositionAndForgetLastKnownKey`、`measurementScopeInvalidator`）|
| **代码证据** | `ScrollCompensation.kt:22-46`；调用点 `ChatMessageList.kt:318, 448, 539`（3 处）**[VERIFY: A 环节 E]** |
| **根因** | 官方 `requestScrollToItem` 会通过 `scroll{}` 互斥锁杀死 fling，无"设置位置但不取消 fling"的公开 API → 选择反射 hack 作为补丁 |
| **修复方向** | ① 升级 Compose 时加版本检测 + 降级 `requestScrollToItem`；② 向 Compose 提 feature request；③ 短期 try-catch + fallback |
| **影响面** | **Compose 版本升级会运行时崩溃**（NoSuchFieldError/NoSuchMethodError），无编译期保护 |
| **来源** | **A 环节 E / A §4 P1-1**（A 单路）· 补丁判定 |

#### **P1-2：诊断代码残留（ChatMessageList JUMP 检测 + 每 item composed 日志）**

| 维度 | 内容 |
|------|------|
| **现象** | b07b7ccc 清理了 MessageDataDelegate 的日志风暴，但 ChatMessageList 内诊断埋点遗漏 |
| **代码证据** | `ChatMessageList.kt:251-267`（JUMP 检测 `LaunchedEffect(Unit)`，snapshotFlow 持续 collect 每帧，注释明示"诊断埋点...验证 beyondBoundsItemCount 修复后"）+ `ChatMessageList.kt:555-557`（每 item 组合日志 `AppLogger.d`，**无 BuildConfig.DEBUG 门控**）**[VERIFY: A 环节 F]** |
| **根因** | b07b7ccc 清理了 MessageDataDelegate 的日志风暴，ChatMessageList 内诊断埋点遗漏。对比：分页触发处（line 378,381）有 DEBUG 门控，这两处没有 |
| **修复方向** | 删除（诊断任务已完成，注释明示）|
| **影响面** | 每帧 snapshotFlow collect + 每 item 日志，直接贡献 Slow UI thread（实测 48/160）|
| **来源** | **A 环节 F** + **D 模式 B**（2 路确认）|

#### **P1-3：loadOlderMessages 缺乏并发保护 → 竞态重复加载**

| 维度 | 内容 |
|------|------|
| **现象** | 翻页时多个并发 launch 可能用相同 archiveCursorCreated 拉相同消息 |
| **代码证据** | `MessagePaginationDelegate.kt:194-260`（line 197 `_isLoadingOlder.value=true` 在 `scope.launch` 内，无入口 guard）· 触发链 `ChatMessageList.kt:361-385`（snapshotFlow collect 无去抖）**[VERIFY: B P1-1]** |
| **根因** | `_isLoadingOlder` 仅作 UI 状态指示，未作互斥锁；snapshotFlow 多次 collect → 多个并发 launch |
| **修复方向** | ① 入口 guard：`if (_isLoadingOlder.value) return`；② 或用 `MutableStateFlow.update` + CAS pattern；③ 或用 `actor`/`Semaphore(1)` 串行化 |
| **影响面** | APPEND_ONLY distinctBy 去重兜底（不会数据错误），但网络/DB 资源浪费 |
| **来源** | **B P1-1**（B 单路）|

#### **P1-4：upsert 写入路径 O(n log n) 排序残留**

| 维度 | 内容 |
|------|------|
| **现象** | b07b7ccc 移除了 combine 内的排序，但写入路径（upsertSsePriority/upsertRestAuthority/upsertAppendOnly/handleMessageUpdated）的 `sortBy/distinctBy+sortedBy` 仍在 |
| **代码证据** | `MessageEventHandler.kt:408`（upsertSsePriority）、`453`（upsertRestAuthority）、`508`（upsertAppendOnly distinctBy+sortedBy）、`151`（handleMessageUpdated msgs.sortBy）**[VERIFY: B P1-2]** |
| **根因** | 写入路径承担了排序职责（移除 combine 排序后），但每次 SSE 新消息粒度触发 + 分页加载触发都会做全量排序 |
| **修复方向** | existing 已是排序列表时改为 merge（O(n)）而非 sortedBy（O(n log n)）；或用 TreeMap/有序数据结构维护 |
| **影响面** | 1000-2000 条会话每次变更 ~10000-40000 次比较；batchScope 后台线程，但高频时累积 CPU |
| **来源** | **B P1-2** + **D TD-9** + **A §3 表**（3 路确认）|

#### **P1-5：ChatViewModel.init 主线程 runBlocking 链 → 首次进入卡顿/ANR**

| 维度 | 内容 |
|------|------|
| **现象** | 进入会话（首次或非首次）时主线程同步阻塞两次（serverConfig + restorePersistedDraft），低端设备/磁盘忙时成 ANR |
| **代码证据** | ① `ChatViewModel.kt:93-96`：`runBlocking(Dispatchers.IO) { serverRepository.getServer(serverId) }`；② `ChatViewModel.kt:368-373`：同步调用 `draftDelegate.restorePersistedDraft()` → `DraftDataStore.ensureLoaded` → `runBlocking { dataStore.data.first() }`（`DraftDataStore.kt:34-50`）· `SessionListViewModel.kt:97-99` 同样问题 **[VERIFY: C S1/S5]** |
| **根因** | ViewModel 构造在 Hilt 主线程执行，多个属性/初始化块需要同步可空的 serverConfig、draft → 选择 runBlocking 同步派生而非异步 StateFlow。基线称"已修复 DraftDataStore runBlocking ANR"——**但仅修复了 onCleared 路径（0eaac6dc），init 路径完整保留**。DraftDataStore 用 runBlocking 是因为 DraftRepository 接口契约为同步（`getDraft(...): Draft?`），DataStore 是异步的，runBlocking 是接口适配的捷径 |
| **修复方向** | ① serverConfig 改为 `StateFlow<ServerConfig?>` + TerminalDelegate 改为派生 flow；② DraftRepository 接口改为 `suspend fun getDraft(...)` 或 `Flow<Draft>`；③ DraftDataStore 内部 runBlocking 改为 withContext(IO) 配合 suspend |
| **影响面** | 所有进入会话场景；低端设备/磁盘忙时成 ANR；SessionListViewModel 同样问题 |
| **来源** | **C S1** + **C §3 表**（0eaac6dc 补丁判定）+ **D §2.3** 相关 |

#### **P1-6：StateFlow.update CAS lambda 内的副作用 → 重复日志 + 违反纯函数约定**

| 维度 | 内容 |
|------|------|
| **现象** | 高频 SSE 场景下 `update{}` CAS 重试导致日志被多次持久化到 Room（INFO 级别即使 DEBUG 关也会持久化）+ 违反 `StateFlow.update` 的纯函数约定 + 潜在掩盖真实状态变化 |
| **代码证据** | ① `MessageEventHandler.kt:567-582`（尤其 575）：`AppLogger.i("UnreadDiag", "[markIdle] ...")` 在 `_messages.update { ... map { if (...) { AppLogger.i(...); msg.copy(...) } } }` 内；② `MessageEventHandler.kt:238-272`（尤其 250-258）：`AppLogger.w("[PartUpdated] ...")` 在 `_parts.update { ... }` 内 **[VERIFY: C S2]** |
| **根因** | `MutableStateFlow.update` 文档明确："the function may be called multiple times if the value is being concurrently updated."。开发者把诊断日志当成"无害副作用"放进 lambda。这些诊断日志属于 b07b7ccc "日志风暴修复"清理时遗漏的残留 |
| **修复方向** | ① 把日志移到 `.update` 外（先 update 拿到结果，再 log）；② 或彻底删除诊断日志（与 b07b7ccc 一致策略）；③ 对所有 `_*.update { ... }` lambda 做 lint：禁止副作用 |
| **影响面** | 高频 SSE + 并发场景下日志放大 2-N 倍；INFO 级别会持久化到 Room；CAS 重试影响测量准确性。**实测验证：PartUpdated 11 条/s（活跃）/1.6 条/s（低活跃），CAS 重试可能加倍** |
| **来源** | **C S2** + **D 模式 B**（2 路确认）+ **E 实测**（PartUpdated 频率）|

#### **P1-7：combine 索引错位 args[8] vs args[9] → 工具进度 UI 永久失效**

| 维度 | 内容 |
|------|------|
| **现象** | 工具进度输出（`tool.progress` 内容）累积注入到 `Part.Text.output` 永久失效，用户在 UI 看不到工具执行中的实时 output |
| **代码证据** | `MessageDataDelegate.kt:150, 166, 172`：combine 第 8 参 `statusFlow`、第 9 参 `getActiveToolProgressForSession(sid)`。line 166 `val statuses = args[8] as Map<String, SessionStatus>` ✅ 正确；line 172 `val progressList = args[8] as? List<ToolProgressInfo>` ❌ 应为 `args[9]` **[VERIFY: C S3]** |
| **根因** | 复制粘贴/重排 combine 参数时遗漏更新索引 |
| **修复方向** | ① 改为 `args[9]`；② 或用 combine 的类型安全变体（≤5 参有专门重载），>5 参改用嵌套 combine 或 data class 包装（结构性根治） |
| **影响面** | 工具进度 UI 功能错误（非性能问题）。无性能副作用（chatMessageCache 仍正常）。**待 E 实测补充**：用户实际可见的工具进度 output 情况 |
| **来源** | **C S3**（C 单路发现）|

---

### 3.3 P2（~14 项）—— 建议，低频或理论风险

| # | 发现 | 现象 | 代码证据 | 来源 |
|---|------|------|---------|------|
| P2-1 | **sseJob + messageListState 双订阅同源**（2x combine 重组）| 每个 SSE 事件触发两个独立 combine 同时重组，CPU 工作量翻倍 | `MessageDataDelegate.kt:142-143 vs 319-333`；1896 条消息场景下每 48ms 2x O(n) 扫描 [VERIFY: C S4] | C 主 + A 间接 + E janky 贡献 |
| P2-2 | **AppLogger 字符串拼接未门控** | 高频路径调用方未加 `if (BuildConfig.DEBUG)` 门控，字符串模板在传参前已拼接，即使 shouldPersist 返回 false 也已付出成本 | `AppLogger.kt:154-175`；EventDispatcher:249、MessageEventHandler:575/255 无门控；MessageEventHandler:157 有门控 [VERIFY: C S8] | C + A F + D 模式 B |
| P2-3 | **combine 上游无 distinctUntilChanged 兜底** | 派生 flow 没有 distinctUntilChanged 兜底，每次上游 emission（即使内容相同）触发 combine 重组 | `ChatRepositoryImpl.kt:92-98`、`461-462` [VERIFY: C S9] | C |
| P2-4 | **100ms ticker 叠加 48ms flush** | 流式消息 footer 重组 ~30 次/s（48ms flush ~20 + ticker ~10） | `MessageCardAssistant.kt:155-163` [VERIFY: A 环节 G] | A |
| P2-5 | **长会话无消息窗口裁剪** | LazyColumn 回收视图但数据层全量驻留；长会话（>2000 条）GC 压力 + combine 开销 | `MessageDataDelegate.kt:179-189`；全库无窗口化 [VERIFY: A 环节 H] | A |
| P2-6 | **loadArchivedRange N+1 查询 + 写模式** | 每桶 1 查询 + 1 写；桶被字节上限切小时多次循环 | `MessageStore.kt:264-292` [VERIFY: B P2-1] | B |
| P2-7 | **loadArchivedRange 解压整桶浪费** | 解压整个桶（最多 200 条/512KB + 桶内排序），只需 30 条 | `MessageStore.kt:302-307` [VERIFY: B P2-2] | B |
| P2-8 | **messagesForSession 的 OR 子句** | `(:beforeId IS NULL OR id < :beforeId)` 可能放弃复合索引；ORDER BY 与索引不完全匹配 | `MessageDao.kt:19-24`；热表限 1000 条缓解 [VERIFY: B P2-3] | B |
| P2-9 | **SSE 双写高频落盘** | 每 48ms flush → upsertMessages 3 查询 + 写 + 可能归档；活跃流式 ~20 次/s 落盘 | `MessageEventHandler.kt:86-129, 194-204`；WAL 缓解 [VERIFY: B P2-4] | B |
| P2-10 | **过渡动画 400ms 反模式（虚假延迟）** | **故意延迟显示加载态**（反模式，欺骗用户感知）；魔法常量 400 无 A/B 依据 | `ChatScreen.kt:255-256, 433-447, 675-683`（MIN_LOADING_VISIBLE_MS=400）[VERIFY: D §2.2] | D |
| P2-11 | **草稿持久化补丁链（双补丁）** | force-stop 在 500ms 防抖窗口内杀进程仍丢；高频输入大量 Job 创建销毁 | 0eaac6dc + e3ffeae7；`DraftInputDelegate.kt:127-145` [VERIFY: D §2.3/TD-3 + C S6] | C + D |
| P2-12 | **L3 校验魔法常量 50** | 长时间离线陈旧窗口 >50 条仍丢；魔法常量无依据 | `SessionStateService.kt:34, 276-282`（REST_REFRESH_LIMIT=50）[VERIFY: D §2.1/TD-4] | D |
| P2-13 | **分页状态散落（TD-1，高严重度技术债）** | MessagePaginationDelegate 9 个可变状态成员，职责膨胀；与 AGENTS.md "SessionStateService 单一真相源"原则相悖 | `MessagePaginationDelegate.kt`（currentMessageLimit, archiveCursorCreated, networkCursorId, networkCursorCreated, _hasOlderMessages, _isLoadingOlder, autoLoadFailures, autoLoadPausedUntil, _autoLoadPaused）[VERIFY: D TD-1] | D + B §4 图 |
| P2-14 | **batchScope 无生命周期管理** | App 级 SupervisorJob scope，App 退出时不取消；多会话同时活跃时 fire-and-forget 协程数无上限 | `MessageEventHandler.kt:71 / 194-204` [VERIFY: C S7] | C |

### 3.4 P3（~25 项）—— 提示，可接受

汇总（不展开）：
- A：flushPendingDeltas batch 内重复 Map 拷贝、tailHash 只取末 64 字符
- B：归档双游标复杂度债、pruneToLimit 嵌套子查询、坏桶跳过静默丢数据
- C：_fsmStates 与 _histories 两个独立 CAS、statusFlow 每次创建新 Map、checkStaleness 全量扫描、handleToolProgress 临时 String 创建、DraftDataStore 500ms 防抖数据丢失窗口、DraftDataStore persist runBlocking 接口契约隐患、StreamingOwnershipRegistry 所有权无自动过期、UnreadBadgeService persistAsync cancel-then-launch、ChatViewModel 构造期 11 delegate 集中、markSessionRead O(n) 扫描、token stats collect O(n) 扫描
- D：TD-7 SQLite IN 分块散落、TD-10 1beb846b 多项打包修复

---

## 4. 关联性分析（跨模块因果链）

### 4.1 主因果链图（ASCII）

```
                                【SSE 日志风暴 → janky 主链路】
SSE token 流（~20/s）
    │
    ├─► MessageEventHandler.scheduleFlush (48ms)
    │       │
    │       ▼ flushPendingDeltas
    │       │
    │       ├─► _parts.update(CAS) ◄───────┐
    │       │   ┌── P1-6 CAS lambda 内副作用│
    │       │   │   handleMessagePartUpdated│
    │       │   │   AppLogger.w("[PartUpdated]") ─► 11条/s（CAS 重试 2x）
    │       │   │                           │
    │       │   │ (b07b7ccc 遗漏残留)        │
    │       │   └── P2-2 字符串拼接未门控 ────┤
    │       │                               │
    │       ▼ 放大 2x（同源双订阅 P2-1）   │
    │       ├─► messageListState combine ──┤
    │       │   10 路，每次 O(n) 扫描      │
    │       │   └── P1-2 ChatMessageList   │
    │       │       JUMP 诊断 snapshotFlow │
    │       │       每 item composed 日志  │
    │       │                               │
    │       └─► sseJob combine ────────────┘
    │           写 _messagesList/_rawMessagesList
    │           │
    │           └─► ChatViewModel.init token stats collect
    │               每次 O(n) 扫描
    │
    ├─► markSessionIdle（forceComplete 链）
    │   └── AppLogger.i("UnreadDiag", "[markIdle]") 1.6条/s
    │       在 _messages.update CAS lambda 内 ❌
    │
    └─► AppLogger.write
        ├─► androidWrite() 同步 native call ◄── P2-2 字符串已拼接
        │   │
        │   ▼ 调用线程同步执行（如 IO 线程被占满会回到主线程）
        │   │
        │   ▼ Number Slow UI thread: 48/160 (30%) ─── 实测
        │
        └─► shouldPersist → Channel(500) → 150ms 批量 → Room 写
            │
            ▼ 高频时（576 条/s）排队
            │
            ▼ 99th 300ms × 3 帧 ─── 实测（推测贡献源之一）

                              【数据安全因果链】
偶发 SQLiteException（非损坏）
    │
    ▼ P0-1 catch 基类过宽
DatabaseRecovery.deleteDatabase
    │
    ├─► 缓存消息全清
    ├─► 归档全清（D §2.7 指出注释错误：LogStore 也在 Room）
    └─► 日志全清 → 用户"消息少了"不知原因（B P3-3 坏桶静默）

                           【功能 bug 因果链】
SSE ToolProgress 事件
    │
    ▼ SessionNextEventHandler._activeToolProgress.update
    │
    ▼ messageListState combine 上游 #9
    │
    ▼ P1-7 args[8] as? List<ToolProgressInfo>（错，应 args[9]）
    │
    ▼ progressList 永远 null
    │
    ▼ progressOutputs = emptyMap()
    │
    ▼ ToolProgressOutputInjector.inject 永不注入
    │
    ▼ 用户看不到工具执行中的实时 output

                        【进入会话卡顿因果链】
首次进入会话（Hilt 主线程构造 ChatViewModel）
    │
    ├─► runBlocking(IO) { serverRepository.getServer(serverId) } ◄── P1-5
    │   Room 冷启动 50-200ms
    │
    └─► ChatViewModel.init → restorePersistedDraft
        │
        ▼ DraftDataStore.ensureLoaded
        │
        ▼ runBlocking { dataStore.data.first() } ◄── P1-5（0eaac6dc 补丁遗漏）
        │
        ▼ 99th 300ms × 3 帧（推测贡献源：首帧）

                          【Compose 升级定时炸弹】
未来 Compose 版本升级
    │
    ▼ LazyListState private 字段名变更
    │
    ▼ P1-1 requestScrollToItemNoCancel 反射
    │
    ▼ NoSuchFieldError / NoSuchMethodError 运行时崩溃
    │
    ▼ 3 处调用点（ChatMessageList.kt:318, 448, 539）同时失效
```

### 4.2 关键关联点解读

1. **日志风暴是 janky 的核心贡献者（5 路确认）**
   - 实测 576 条/s logcat 输出，每个都走 native call（`AndroidLog.d/i/w/e`）
   - `PartUpdated` 11 条/s（活跃）→ 在 `update{}` CAS lambda 内 → CAS 重试可能 2x = 22 条/s 实际
   - `UnreadDiag` 1.6 条/s（markIdle）
   - 这些日志同时进入 AppLogger Channel → 批量化 → Room 写 → 99th 300ms 帧
   - **修复 P1-2 + P1-6 应能显著降低 janky**（待 E 实测补充验证）

2. **双订阅同源是次要贡献者（3 路确认）**
   - C S4 详细分析：`messageListState` + `sseJob` 观察完全相同的 `getMessagesFlow + getParts`
   - 每个 SSE 事件 → 2x combine 重组
   - 1896 条消息场景下每 48ms 2x O(n) 扫描
   - 与日志风暴叠加形成 99th 300ms 帧

3. **写入路径排序（后台但累积）**
   - B P1-2：1000-2000 条会话每次变更 ~10000-40000 次比较
   - 在 `batchScope`（Dispatchers.Default）后台，不直接贡献主线程 janky
   - 但与 IO 线程争用 → 间接影响 AppLogger 持久化延迟

4. **P1-7 combine 索引错位是孤立功能 bug**
   - 与性能无关，chatMessageCache 仍按引用稳定缓存
   - 但第 9 个上游实际上完全未被消费——它只是触发了 combine 重组却没影响输出
   - 性能副作用小，但功能性失败

5. **分页状态散落（TD-1）是架构债的根源**
   - D 模式 A：游标不前进类问题反复出现（d30a0d57 → c5e0ea56）
   - 同一根因（分页游标抽象缺失）导致 3 次复发
   - 与 AGENTS.md "SessionStateService 单一真相源"原则相悖
   - 修复后可一并消除 P1-3（loadOlder 竞态）的温床

---

## 5. 根因修复路线图（按收益/成本排序）

> 工作量：S = <0.5 天 · M = 0.5-2 天 · L = >2 天
> 优先级：**立即修**（本轮发版阻塞）→ **本轮修**（性能优化）→ **下轮重构**（技术债）

### 5.1 第一批：立即修（数据安全 + 功能 bug + 主线程阻塞）—— 3 项

| 序 | 修复内容 | 涉及文件 | 工作量 | 验收标准 |
|----|---------|---------|--------|---------|
| **1.1** | **P0-1 DatabaseRecovery 收窄 catch 范围** | `DatabaseRecovery.kt:29-38` | **S** | ① 单元测试：`SQLiteConstraintException`/`SQLiteDatabaseLockedException` 不触发删库；② `SQLiteDatabaseCorruptException` 触发删库；③ `fallbackToDestructiveMigration()` 声明或 `Result<T>` 返回 |
| **1.2** | **P1-7 修正 combine 索引 args[8] → args[9]** | `MessageDataDelegate.kt:172` | **S** | ① 工具进度 UI 实测显示正常；② 单元测试验证 `progressOutputs` 非空；③ **E 实测补充**：工具进度 output 实际渲染 |
| **1.3** | **P1-5 ChatViewModel.init 异步化**（含 DraftDataStore）| `ChatViewModel.kt:93-96, 368-373`、`DraftDataStore.kt:34-50, 97-107`、`DraftInputDelegate.kt:197-205`、`SessionListViewModel.kt:97-99`、`DraftRepository` 接口 | **M-L** | ① 首次进入会话不阻塞主线程（PERFETTO/Profiler 验证无主线程 runBlocking）；② DraftRepository 改为 `suspend` 或 `Flow`；③ 草稿保存/恢复正常；④ **E 实测补充**：进入会话卡顿对比 |

### 5.2 第二批：本轮修（性能优化 + 高频路径清理）—— 4 项

| 序 | 修复内容 | 涉及文件 | 工作量 | 验收标准 |
|----|---------|---------|--------|---------|
| **2.1** | **P1-2 删除 ChatMessageList 诊断残留** | `ChatMessageList.kt:251-267, 555-557` | **S** | ① 编译通过；② **E 实测补充**：janky < 30%（对比当前 44.38%）；③ logcat 频率下降 |
| **2.2** | **P1-6 移除 update CAS lambda 内的副作用日志** | `MessageEventHandler.kt:567-582, 238-272, 419-428, 463-472` | **S** | ① `update{}` lambda 内无 AppLogger 调用；② 日志移到 lambda 外或删除；③ **E 实测补充**：`PartUpdated` 频率从 11条/s → <1条/s 或归零；④ `UnreadDiag` 频率下降 |
| **2.3** | **P1-3 loadOlderMessages 入口 guard** | `MessagePaginationDelegate.kt:194-260` | **S** | ① `_isLoadingOlder.value=true` 前检查；② 单元测试：并发触发不重复加载；③ 网络请求计数验证 |
| **2.4** | **P1-1 反射 hack 加版本检测 + fallback**（短期）| `ScrollCompensation.kt:22-46` | **M** | ① try-catch 包裹，NoSuchFieldError 时降级 `requestScrollToItem`；② Compose 升级前手动测试反射字段名；③ **长期**：向 Compose 提 feature request |

### 5.3 第三批：下轮重构（技术债）—— 6 项

| 序 | 修复内容 | 涉及文件 | 工作量 | 验收标准 |
|----|---------|---------|--------|---------|
| **3.1** | **P2-13 TD-1 分页状态抽象**（PaginationCursor sealed class + PaginationFSM）| `MessagePaginationDelegate.kt`、`MessagePaginationUseCase.kt`、`MessageStore.kt` | **L** | ① 9 个可变状态 → ≤3 个；② 单元测试覆盖所有游标转换；③ 与 SessionStateFSM 风格一致 |
| **3.2** | **P1-4 upsert 写入路径排序优化**（merge 替代 sortedBy）| `MessageEventHandler.kt:151, 408, 453, 508` | **M** | ① existing 已有序时 O(n) merge；② 单元测试验证有序性；③ **E 实测补充**：2000 条会话基准对比 |
| **3.3** | **P2-1 消除 sseJob 双订阅同源** | `MessageDataDelegate.kt:142-143 vs 319-333` | **M** | ① 让 messageListState 同时暴露 rawMessages 字段；② 单元测试：单一 combine 路径；③ **E 实测补充**：janky 进一步下降 |
| **3.4** | **P2-10 移除过渡动画 400ms 反模式** | `ChatScreen.kt:255-256, 433-447, 675-683` | **M** | ① 移除 MIN_LOADING_VISIBLE_MS；② 加导航级 enter/exit transition；③ UX 验证无闪烁 |
| **3.5** | **P2-11 TD-3 草稿持久化 Flow 化** | `DraftRepository`、`DraftDataStore`、`DraftInputDelegate` | **M** | ① `draftFlow: Flow<Draft>`，UI collectAsState；② 移除防抖 job；③ onCleared 用独立 scope；④ **E 实测补充**：force-stop 数据丢失测试 |
| **3.6** | **P2-12 TD-4 L3 校验增量同步** | `SessionStateService.kt:34, 276-282` | **M** | ① `lastSyncCursorPerSession` Map；② L3 用 `before=encode(lastSyncCursor)`；③ 单元测试长时间离线场景 |

---

## 6. 补丁债根因修复方案表

> 引用 D 报告 TD 编号 · 3 个纯补丁 + 7 个混合的根因修复方案

### 6.1 纯补丁（3 个）—— 立即/本轮修复

| TD | 补丁 commit | 现状（症状层修复）| 根因修复方案 | 工作量 | 路线图批次 |
|----|------------|----------------|-------------|--------|----------|
| **TD-2** | `ec875ff7` 过渡动画 400ms | `MIN_LOADING_VISIBLE_MS=400` 故意延迟显示加载态（反模式）| 移除常量；会话路由加 `enterTransition`/`exitTransition`；loading 指示器回归"仅在真正加载时显示" | M | 第三批 3.4 |
| **TD-3** | `e3ffeae7` 草稿 500ms 防抖（补 0eaac6dc 缺口）| 每次按键 launch+cancel job；500ms 窗口内被杀仍丢；补丁链 | `DraftRepository` 暴露 `draftFlow: Flow<Draft>`，UI `collectAsState` + `onValueChange` 写 DataStore（原子合并写）；移除防抖 job；onCleared 用独立 scope | M | 第三批 3.5 |
| **TD-4** | `a7aec358` L3 校验 limit=0→50 | `REST_REFRESH_LIMIT = 50` 魔法常量无依据；长时间离线仍可能 >50 漏消息 | `lastSyncCursorPerSession` Map，L3 校验用 `before=encode(lastSyncCursor)` 增量；同步成功后推进 | M | 第三批 3.6 |

### 6.2 混合（7 个）—— 根因部分 + 技术债残留

| TD/项 | 混合 commit | 根因部分（保留）| 残留债务根因修复方案 | 工作量 | 路线图批次 |
|-------|------------|----------------|---------------------|--------|----------|
| **TD-1** | `c5e0ea56` 上滑自动加载三连（isScrollInProgress + NETWORK 游标 + 防风暴）| isScrollInProgress 改 `LaunchedEffect` + snapshotFlow 持续监听 ✅ | NETWORK 游标 var 散落 → **抽 PaginationCursor sealed class + PaginationFSM**（参照 SessionStateFSM 纯函数）| L | 第三批 3.1 |
| **TD-1 前身** | `d30a0d57` 归档翻页游标推进 | 归档不落热表识别正确 ✅ | 游标作为 UI 层 private var → 封进 domain 层 PaginationCursor（与 c5e0ea56 合并修复）| (同 3.1) | 第三批 3.1 |
| **P0-1** | `6fdff190` 数据库自愈删除重建 | 封装可复用 ✅ | catch 基类过宽 → **收窄到 `SQLiteDatabaseCorruptException`**；或 `fallbackToDestructiveMigration()` 声明式；返回 `Result<T>` 而非 nullable | S | 第一批 1.1 |
| **TD-9** | `ff192fd5` APPEND_ONLY 合并替换 bug | 把"替换"改回"合并"修复正确性 ✅ | 每次合并全量 sortedBy → **existing 已有序时 merge（O(n)）** | M | 第三批 3.2 |
| **P1-2/P1-6** | `b07b7ccc` 滑动掉帧 7 项 | 6/7 子项根因 ✅（IN 分块/O(n) 移除/combine 排序移除/日志风暴清理等）| 残留：① upsert 排序（→ P1-4）② OR 子句未优化 ③ NetTrace 日志 hot path（→ D TD-8）④ **MessageEventHandler 内 4 处 DIAG 日志遗漏**（→ P1-6）⑤ ChatMessageList 诊断残留（→ P1-2）| 多项 S | 第二批 2.1/2.2 + 第三批 |
| **TD-6** | `4797be6e` 种子化块异常降级 | 异常降级语义 ✅ | catch(Exception) 吞 CancellationException → **已被 61e4107a 修复**（先重抛 CancellationException）；模式需 detekt 规则持续守护 | (已修) | — |
| **TD-7** | `b07b7ccc` SQLite IN 999 分块 | 解决 Room IN 查询超限 ✅ | 逻辑散落 MessageStore 而非 DAO 层 → **下沉 DAO 层**封装 `@Query` 内部分块 | M | 下轮 |
| **TD-8** | `b07b7ccc` NetTrace 日志 | 无（新增）| hot path DEBUG 级日志模式不一致（删 MsgDiag 又加 NetTrace）→ **采样 + 强制 BuildConfig.DEBUG 门控 + CI lint** | S | 第二批 2.2 配套 |

### 6.3 模式发现（D §4）→ 系统性对策

| 模式 | 现象 | 对策 | 落地方式 |
|------|------|------|---------|
| **A 游标不前进反复**（3 次：d30a0d57/c5e0ea56）| 分页游标抽象缺失，散落 UseCase + Delegate + Store 三层 | PaginationCursor sealed class + PaginationFSM | 第三批 3.1 |
| **B DIAG 残留反复**（3 次：MsgDiag→PartContent→NetTrace）| 开发期 debug 日志无门控机制；清理后又新增形成循环 | (a) lint 规则禁止 DebugLogger 在 main 分支；(b) 所有 debug 日志强制 BuildConfig.DEBUG 门控 + CI 检查；(c) NetTrace 采样 | 第二批 2.2 + CI |
| **C catch(Exception) 吞 CancellationException**（≥2 次）| 协程反模式 | `safeCatch` 工具函数（先重抛 CancellationException）；detekt `SwallowedException` 规则 | 下轮 |
| **D 补丁补补丁链**（草稿：0eaac6dc → e3ffeae7）| fix commit 注释提到"补 XXX 的缺口/兜底"时大概率是补丁链 | 识别后直接根因重构（Flow 化）| 第三批 3.5 |
| **E 一个 commit 打包多项修复**（b07b7ccc/1beb846b/16c7a15c/c5e0ea56）| 降低可审计性 | fix commit 一事一 commit；PR review 检查 | 流程改进 |

---

## 7. 附录：原始报告索引

| 报告 | 文件 | P0 | P1 | P2 | P3 | 重点 |
|------|------|----|----|----|----|------|
| A | `A-rendering-pipeline.md` | 0 | 2 | 2 | 2 | 渲染管线五铁律遵守情况、反射 hack、诊断残留 |
| B | `B-data-pagination-archive.md` | **1** | 2 | 4 | 3 | **P0 数据安全**、分页游标、归档性能、写入排序 |
| C | `C-state-events-logging.md`（主体，43KB/97 VERIFY）| 0 | 3 | 6 | ~20 | 状态机、事件分发、日志系统、CAS 副作用、combine bug |
| D | `D-patch-vs-rootcause-history.md` | — | — | — | — | 33 根因/3 补丁/7 混合、5 模式发现、TD-1 高严重度 |
| E | `metrics/gfxinfo-streaming.txt` + `stream-logcat-raw.log` + `stream2-logcat-raw.log` | — | — | — | — | janky 44.38%、99th 300ms、576 条/s 日志 |

---

## 8. 报告结语

本次审计的 5 路交叉验证表明：

1. **数据安全有 1 个 P0 阻塞项**（DatabaseRecovery），应立即修复，工作量 S。
2. **功能 bug 有 1 项 P1**（combine 索引错位），修复只需改一个字符，但影响工具进度 UI。
3. **性能问题核心是日志风暴**（5 路最高置信度确认），P1-2 + P1-6 修复后预期 janky 显著下降，**待 E 实测补充验证**。
4. **架构债集中在分页状态管理**（TD-1），与 AGENTS.md "单一真相源"原则相悖，建议下轮抽 PaginationFSM 根治。
5. **v0.2.0 后修复质量整体良好**（33 根因 vs 10 补丁/混合 = 80% 根因率），补丁债集中在草稿持久化（TD-3 补丁链）和过渡动画（TD-2 反模式）。

**后续修复任务可直接以本报告 §5 路线图为输入**，每项含涉及文件、工作量、验收标准。

---

报告结束。
