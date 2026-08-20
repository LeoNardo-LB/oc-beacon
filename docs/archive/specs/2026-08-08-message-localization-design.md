# 消息本地化缓存 + 存储层重构 设计文档

日期：2026-08-08
状态：已批准（用户逐段确认）
关联：Backlog 新增条目（消息本地化批次）

## 1. 背景与目标

### 问题

当前 OC Beacon 的消息/会话数据**完全驻留内存**（`EventDispatcher` 的 StateFlow），无任何本地持久化：

- 进入会话必须等待 REST 全量拉取，大会话网络开销大、首屏慢
- "分页"是假分页：`limit *= 2` 指数重拉最新 N 条再 merge，每次翻页都重新下载已看过的消息
- 重启进程后全部数据丢失，重新全量拉取

### 用户诉求

1. 已拉取的消息保存在本地；再次加载时只拉取"本地没有的"增量，减少网络开销
2. 存储层全面重构设计，保持优雅，不留技术债
3. 后续批次再做压缩/解压归档（本设计不含实现）

### 关键前提（调研结论）

- **OpenCode Server 已支持增量游标分页**：`GET /session/{id}/message?limit=N&before={cursor}`，返回 `Link`/`X-Next-Cursor` header，游标 = `base64url(JSON({id, time}))`——客户端当前完全未使用
- **消息 ID 是 ULID**（`msg_` 前缀，单调递增）→ 天然适合 keyset 分页/去重
- 主流 IM（Telegram/WhatsApp/Signal/Matrix/微信/iMessage）共识：Local-First + SQLite + 游标增量同步 + 显式缺口建模

## 2. 已确认决策

| 决策点 | 结论 |
|--------|------|
| 存储引擎 | **Room**（并迁移现有全部手写 SQL） |
| 缓存策略 | **限量缓存：每会话最近 1000 条**，超出删除 |
| 翻页与裁剪冲突 | **严格最近 N 条**：窗口外的旧消息仅内存渲染、不落库 |
| SSE 写入时机 | **实时写库**（48ms 批处理 + IO 线程） |
| 重构方案 | **方案 C 完整版**：存储层全面重构 + EventDispatcher 拆分 + 接口统一 |
| 诊断日志迁移 | **等价迁移 + 丢旧数据**（历史上 onUpgrade 本就 DROP 重建） |
| 批次 1 范围 | **只做消息本地化**（会话列表本地化放后续批次） |

## 3. 目标架构

```
UI 层（Compose）—— 只读 Flow，永不直接写数据
        │ 只读: Flow<T>
状态层（内存真相源）
  EventDispatcher（事件分发 + 横切清理）          -- 重构保留核心
  SessionStateService（FSM 状态机）              -- 复用不动
  UnreadBadgeService（红点时间源）               -- 从 EventDispatcher 抽出
  StreamingOwnershipRegistry（多服务器去重）      -- 从 EventDispatcher 抽出
        │ 读: Flow / 写: 委托
领域层（Domain）—— 纯接口
  ChatRepository / SessionRepository / ...
  （接口收窄：只留读 Flow + 明确意图的写方法）
        │
数据层（Data）—— 重构核心
  EventHandler（SSE/REST 合并，统一 upsert）
  LocalStore（统一持久化门面）
  RepositoryImpl（API 编排 + 内存热视图切片）
        │
持久化层（2 种技术收敛）
  Room：cached_messages + cached_parts + logs
  DataStore：设置/标签/红点/草稿（合并统一）
```

### 核心原则

1. **单向数据流**：UI → 只读 Flow；写路径统一经 LocalStore + Handler
2. **本地缓存是写入目标**：SSE 实时写 + REST 快照写 → 都进 Room；内存 StateFlow 作为热视图，启动时从 Room 种子化
3. **只读接口**：Repository 对 UI 只暴露 Flow；写操作收敛到内部
4. **两套持久化技术**：Room（结构化消息/日志）+ DataStore（KV 设置），SharedPreferences 仅语言同步读特例

## 4. 持久化层设计

### 4.1 Room 数据库：单库 `ocbeacon.db`（版本 1，WAL）

**表 1：`cached_messages`（消息缓存）**

```sql
cached_messages (
    id         TEXT PRIMARY KEY,      -- msg_ ULID，去重/游标
    session_id TEXT NOT NULL,         -- 查询键
    created    INTEGER NOT NULL,      -- time.created 毫秒，排序键
    role       TEXT NOT NULL,         -- user/assistant
    payload    TEXT NOT NULL          -- 完整 Message JSON（kotlinx.serialization）
)
CREATE INDEX idx_msgs_session_created ON cached_messages(session_id, created DESC)
```

Message 是深度嵌套 sealed class（User/Assistant + cost/tokens/path 等），整条序列化 JSON 存单列，只提取索引列（Telegram 同款 BLOB 化做法）。

**表 2：`cached_parts`（消息部件，独立表）**

```sql
cached_parts (
    id         TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,         -- FK → cached_messages.id（ON DELETE CASCADE）
    session_id TEXT NOT NULL,
    type       TEXT NOT NULL,
    text       TEXT,                  -- 流式更新热点
    payload    TEXT                   -- 完整 Part JSON
)
CREATE INDEX idx_parts_message ON cached_parts(message_id)
```

parts 独立成表的原因：SSE 流式更新每 48ms 一个 token delta 高频写，独立表每次只更新单行 text，避免重写整条消息 JSON 的写放大。

**表 3：`logs`（诊断日志迁移，语义等价）**

```sql
logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp  INTEGER NOT NULL,
    level      TEXT NOT NULL,
    category   TEXT NOT NULL,
    message    TEXT NOT NULL,
    details    TEXT NOT NULL,
    byte_size  INTEGER NOT NULL
)
```

修剪策略等价迁移：3 天普通 / 21 天错误 / 最近 50 条崩溃 / 10MB 字节预算（预算循环用事务 + 分批 DELETE）。`DiagnosticLogDatabase.kt` 删除，`DiagnosticLogRepository` 接口不变（AppLogger 侧零改动）。

### 4.2 限量 1000 条/会话

写入后同事务执行：

```sql
DELETE FROM cached_messages
WHERE session_id = ? AND id NOT IN (
    SELECT id FROM cached_messages
    WHERE session_id = ? ORDER BY created DESC, id DESC LIMIT 1000
)
```

parts 靠 FK 级联自动清理。裁剪失败与写入同事务回滚。

### 4.3 DataStore 合并

| 文件 | 动作 |
|------|------|
| SettingsDataStore.kt（主） | 合并 ReadTimes + Tags 扩展文件，内部按域分组：AppPrefs / ReadTimes / Tags |
| SettingsDataStoreReadTimes.kt | 并入主类 |
| SettingsDataStoreTags.kt | 并入主类 |
| DraftDataStore.kt（File+JSON） | 迁移到 DataStore：`stringPreferencesKey("session_drafts")`，内存缓存 + 原子写保留 |
| ServerDataStore.kt | 复用不动（SecretCipher 加密成熟） |
| PermissionAutoApprover | 复用不动 |

持久化收敛：5 种技术 → 2 种（Room + DataStore）。SharedPreferences 仅保留 `locale_prefs`（attachBaseContext 同步读特例，明确排除改动）。

## 5. 数据访问层设计

### 5.1 `LocalStore` 门面（新增 @Singleton）

```
LocalStore
├── MessageStore      -- cached_messages + cached_parts 读写
│     ├── upsertMessages(sessionId, messages: List<MessageWithParts>)
│     ├── observeMessages(sessionId): Flow<List<MessageWithParts>>   -- Room Flow
│     ├── getMessageRange(sessionId, limit, beforeId): List<...>     -- 游标分页读
│     ├── clearSession(sessionId)
│     └── pruneToLimit(sessionId, limit = 1000)
├── LogStore          -- logs 表（原 DiagnosticLogDatabase 职责）
│     ├── insert(entries) / observeLatest(limit) / clear()
│     └── prune()（3天/21天/50崩溃/10MB）
└── PrefsStore        -- 合并后 SettingsDataStore + DraftDataStore
      ├── app settings / readTimes / tags / favorites
      └── drafts
```

所有写方法返回 Unit，不暴露事务细节；DAO 层是 SQL 边界。

### 5.2 Repository 接口收窄

| 现状（泄漏） | 重构后 |
|-------------|--------|
| ChatRepository.getMessagesFlow | 保留 |
| ChatRepository.setMessages/mergeMessages/replaceMessages | 移除 → 写统一走 MessageStore + Handler |
| ChatRepository.getPermissionsSnapshot() 等快照读 | 移除 → 改 Flow 或 ViewModel 本地聚合 |
| SessionRepository.setSessions | 收敛到 SessionEventHandler 内部 |
| SessionStateRepository | 不动 |

Repository = API 编排 + 内存热视图切片；持久化写入 = LocalStore；两者经 Handler 协调。

### 5.3 EventDispatcher 拆分

保留：事件分发注册表模式 + 横切清理 + 门面 StateFlow 聚合。

拆出：
- **UnreadBadgeService**：`_lastCompletedReplyTime` + 4 处增量维护点封装为独立 @Singleton（`onMessageCompleted(sid, ts)` + `observeLastCompletedReplyTime(): Flow<...>`）；**消除 runBlocking 同步写盘**（红点 KV 走 PrefsStore 异步写）
- **StreamingOwnershipRegistry**：`streamingSessionOwners` 归属表封装（claim/release/isOwnedBy）
- **接口命名统一**：xxxStore / xxxRepository / xxxHandler / xxxService 后缀规范；合并 DataModule/DomainModule 历史遗留

### 5.4 合并策略统一（MessageEventHandler）

三方法 → 单一 `upsertMessages(sessionId, incoming, strategy)`：

```
strategy = SSE_PRIORITY    -- SSE 流式数据优先（原 setMessages 语义）
         | REST_AUTHORITY  -- REST 快照优先（原 replaceMessages 语义）
         | APPEND_ONLY     -- 仅补充缺失（原 mergeMessages 语义）
```

每次 upsert 同时写内存 StateFlow + MessageStore（Room），同一路径。

## 6. 数据流

### 6.1 进入会话时序

```
① 内存快照          从 EventDispatcher StateFlow 读（热视图）
② 本地种子化        并行：MessageStore.observeMessages(sid)
                    → 有缓存 → 立即渲染（零网络等待）
                    → 空 → 等 REST（现状行为）
③ 增量拉取（后台）   GET /session/{sid}/message?limit=50&before=<本地最旧游标>
④ 合并+写库         upsertMessages(sid, incoming, REST_AUTHORITY)
⑤ 限量清理          pruneToLimit(sid, 1000)
⑥ SSE 实时          SSE 事件 → upsertMessages(sid, event, SSE_PRIORITY)
```

### 6.2 翻页（真游标分页）

```
① 本地优先：Room 查更早消息 → 有 → 直接渲染（零网络）
② 本地边界：GET ...?limit=50&before=<本地最旧游标> → 只拉更早 50 条
③ upsertMessages(sid, incoming, APPEND_ONLY)
④ 窗口外消息（早于本地最旧窗口）→ 仅内存渲染，不落库
```

### 6.3 SSE 实时写入

| 事件 | 处理 |
|------|------|
| MessageUpdated | upsertMessages(sid, [msg], SSE_PRIORITY) → 建行 + parts 行 |
| MessagePartUpdated | 只更新 cached_parts.text 单行（48ms 批处理 + IO 线程） |
| MessagePartDelta | 批处理聚合成最终文本后写一次 |
| MessageDeleted | 删消息行（级联删 parts） |

### 6.4 冷启动

启动 → LocalStore 初始化 → Repository Flow 从 Room 种子化 → 热视图立即可见。**只有用户进入的会话才触发增量拉取**（不主动全量拉所有会话）。

## 7. 错误处理

| 场景 | 行为 |
|------|------|
| Room 写入失败 | 捕获 + AppLogger 记录；内存视图不受影响；下次写入重试 |
| 本地库损坏 | 删除重建（消息可重拉，非用户资产）；AppLogger 记录 |
| 增量拉取失败 | 沿用现有重试；本地缓存可浏览（降级只读） |
| SSE 断线重连 | REST_AUTHORITY 策略补齐 |
| 翻页窗口外 | 仅内存渲染，不写 Room |
| 数据库升级 | Room Migration 对象，不 DROP 重建 |

## 8. 测试策略

### 单元测试（test/）

- **Room DAO 测试**：增删改查、游标分页、pruneToLimit 边界（1000/1001/乱序时间）、FK 级联
- **MessageStore 测试**：upsert 幂等、三策略语义、窗口外不落库、clearSession
- **upsert 合并测试**：替换现有 MessageEventHandlerMergeTest（SSE 更长文本胜出 / REST 优先 / 仅补充缺失）
- **LogStore 迁移等价测试**：修剪语义对照现有行为
- **UnreadBadgeService 测试**：红点幂等、持久化异步化
- **StreamingOwnershipRegistry 测试**：claim/release/isOwnedBy 状态机

### 插桩测试（androidTest/）

- Fake 类适配接口收窄（FakeChatRepository 46 方法裁剪；FakeDomainModule 更新；LocalStore Fake = 内存实现）
- 保留现有 Compose UI 测试

### 验证矩阵

1. compileDevDebugKotlin
2. testDevDebugUnitTest --rerun（含新增测试）
3. compileDevDebugAndroidTestKotlin（顺带覆盖 #29 遗留 Fake 编译问题）
4. Maestro 流程 + 维度 5 人工验证（进入会话秒开 / 翻页 / SSE 流畅）

### 提交粒度

按子模块拆分，每步可回退：Room 引入 → LocalStore → 接口收窄 → EventDispatcher 拆分 → DataStore 合并 → 诊断日志迁移。

## 9. 批次边界（本次不做）

| 批次 | 内容 | 状态 |
|------|------|------|
| 批次 2 | 归档压缩（整桶 zstd + TLRU 淘汰 + 解压缓存期） | 后续（进 Backlog） |
| 批次 3+ | 会话列表本地化、本地搜索（FTS5）、离线模式、SQLCipher 加密 | 后续（进 Backlog） |
| — | EventDispatcher 拆分已含本次（方案 C 完整版） | 本次 |
