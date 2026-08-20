# 二期设计：归档压缩（#32）——热/冷分层 + 整桶 zstd + TLRU 元数据

- 日期：2026-08-09
- 分支：`feature/archive-compression`
- 前置：`2026-08-08-message-localization-design.md`（一期，已代码完成 + 模拟器验证）
- 状态：已批准（用户授权自主决策实施）

## 1. 背景与问题

一期（#30）实现了消息本地缓存（Room 热表 `cached_messages` + `cached_parts`），但存在两个数据流失口：

1. **prune 直接删**：热表每会话限量 1000 条，`MessageDao.pruneToLimit` 把最老消息**直接 DELETE**——超限即永久丢失。会话超过 1000 条后，早期对话无法再浏览（仅服务端有，且断网时完全不可见）。
2. **翻页不落库**：`loadOlderMessages` 网络拉到的更早消息 `persistOldBeyondWindow=false` → **只进内存显示、不写 Room**（避免"写了又被裁"循环）。这导致：每次翻页都走网络、离线无法看历史、服务端不可达时翻页直接失败。

**二期目标**：把"将被 prune 的最老消息"先压缩归档到本地，翻页时优先从归档解压（离线可浏览、省网络），数据不再丢失。

## 2. 设计决策（自主决策项）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 归档时机 | **prune 副产品**：写新消息 → 超限 → 先把将被删消息归档 → 再 DELETE | 与现有写入路径天然耦合，无需额外触发点；只在真实写入时执行（一期实证 prune 仅在真实写入触发） |
| 归档粒度 | **整桶**：按 `(sessionId, 时间桶)` 聚合，桶内序列化 `List<ArchivedMessageDto>` → zstd 压缩为单个 BLOB | 单条消息 <10KB 压缩是负优化（调研结论，Discord 实测）；整桶才有收益 |
| 分桶规则 | **时间窗口 + 条数硬上限**：`bucketStart = created / WINDOW_MS * WINDOW_MS`（默认 1 天）；序列化后 > 512KB 时按 200 条切分子桶 | 时间窗口稳定可预测；512KB 对齐 CursorWindow 2MB 限制留余量（调研约束） |
| 解压触发 | 用户向上滚动到热表最老边界 → `loadArchivedRange` 查归档（时间降序取最新桶）→ 解压返回；归档读尽才走网络 | 本地归档是"更早数据"的权威来源；网络仅兜底补充 |
| 解压产物去向 | **只进 UI 内存**（ChatRepository 内存合并），**不落热表** | 落热表会触发 prune → 又归档 → 死循环（一期已知陷阱） |
| TLRU 淘汰 | **只记录 `lastAccessedAt`，不删桶**；sweep 仅在存储超保护上限时删最久未访问桶 | 消息是用户资产，压缩后体积小（千条 ≈ 1MB）；YAGNI，避免丢数据风险。保护上限保守：每会话 200 桶 ≈ 20 万条历史 |
| 归档桶生命周期 | 随 `clearSession` 级联删除 | 会话删除 = 用户主动放弃，归档一并清理 |
| 压缩库 | zstd-jni **1.5.7-13**（2026-08-08 发布，最新稳定） | Maven Central AAR，Android 5.0+，无额外依赖；README 明确 Java 类**不可混淆/重命名**（R8 keep 必须） |

## 3. 架构

```
热表（cached_messages/cached_parts）         归档表（archive_buckets）
┌─────────────────────────────┐   prune    ┌─────────────────────────────┐
│ 最近 1000 条，可写可读       │ ─────────► │ 更早历史，(session,时间桶)   │
│ 冷启动种子化 / SSE 双写      │  先归档再删 │ 整桶 zstd BLOB，只读         │
└─────────────────────────────┘            └─────────────────────────────┘
         ▲ 翻页优先读本地归档                        ▲
         │ loadArchivedRange(beforeCreated)          │ 解压 → UI 内存
         └─────────────── 归档读尽才网络 ────────────┘
```

### 3.1 新表 `archive_buckets`（DB v1 → v2）

```kotlin
@Entity(
    tableName = "archive_buckets",
    indices = [Index(value = ["sessionId", "bucketEnd"])],
)
data class ArchiveBucketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val bucketStart: Long,       // 桶内最早消息 created
    val bucketEnd: Long,         // 桶内最晚消息 created（翻页游标）
    val messageCount: Int,
    val uncompressedSize: Int,   // 解压必需（zstd 需要原始大小）
    val payload: ByteArray,      // zstd 压缩后的 JSON BLOB
    val createdAt: Long,
    val lastAccessedAt: Long,    // TLRU 元数据（记录不淘汰）
)
```

- 索引 `(sessionId, bucketEnd)`：翻页查询 `WHERE sessionId=? AND bucketEnd < ? ORDER BY bucketEnd DESC LIMIT ?`
- FK 不建（跨表 CASCADE 复杂化），`clearSession` 手动删归档桶

### 3.2 序列化格式 `ArchivedMessageDto`

```kotlin
@Serializable
data class ArchivedMessageDto(
    val info: Message,               // 完整 Message JSON
    val parts: List<Part>,           // 完整 Parts JSON（冗余存储 vs 热表分表，桶内一次性读取换取简单）
)
```

桶内 JSON：`Json.encodeToString(List<ArchivedMessageDto>)` → UTF-8 → zstd。

### 3.3 压缩编解码 `ZstdCodec`（新，data/local）

```kotlin
object ZstdCodec {
    fun compress(bytes: ByteArray): ByteArray = Zstd.compress(bytes)           // level 默认 3
    fun decompress(bytes: ByteArray, originalSize: Int): ByteArray = Zstd.decompress(bytes, originalSize)
}
```

- `uncompressedSize` 必须存表——zstd 解压 API 需要原始大小
- 异常（数据损坏/大小不符）抛 `IllegalStateException`，由调用方 `runCatching` 兜底

### 3.4 MessageDao 扩展

```kotlin
/** 待 prune 的最老消息（created ASC 前 [limit] 条）——归档前查询 */
@Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT :limit")
suspend fun oldestMessages(sessionId: String, limit: Int): List<CachedMessageEntity>

/** 按 ID 批量删（归档完成后执行，替代原 pruneToLimit 的 DELETE 语义） */
@Query("DELETE FROM cached_messages WHERE id IN (:ids)")
suspend fun deleteMessages(ids: List<String>): Int
```

**`pruneToLimit` 保留**（Part CASCADE 删除由其驱动）：MessageStore 编排改为"查 `oldestMessages` → 归档 → `pruneToLimit` 删"——`pruneToLimit` 内部 SQL 不变（删超限最老），归档在 DELETE 前完成，两步骤在同一事务。

### 3.5 ArchiveBucketDao（新）

```kotlin
@Dao
interface ArchiveBucketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(bucket: ArchiveBucketEntity)
    @Query("SELECT * FROM archive_buckets WHERE sessionId=:sessionId AND bucketEnd < :beforeEnd ORDER BY bucketEnd DESC LIMIT :limit")
    suspend fun latestBefore(sessionId: String, beforeEnd: Long, limit: Int): List<ArchiveBucketEntity>
    @Query("SELECT COUNT(*) FROM archive_buckets WHERE sessionId=:sessionId") suspend fun count(sessionId: String): Int
    @Query("SELECT * FROM archive_buckets WHERE sessionId=:sessionId ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun leastAccessed(sessionId: String, limit: Int): List<ArchiveBucketEntity>
    @Query("DELETE FROM archive_buckets WHERE sessionId=:sessionId") suspend fun clearSession(sessionId: String)
    @Query("DELETE FROM archive_buckets WHERE id=:id") suspend fun delete(id: Long)
    @Query("UPDATE archive_buckets SET lastAccessedAt=:at WHERE id=:id") suspend fun touch(id: Long, at: Long)
}
```

### 3.6 MessageCacheRepository 接口扩展（domain 层）

```kotlin
/** 归档读取：查 session 在 beforeCreated 之前的归档桶，解压返回；无归档返回 emptyList */
suspend fun loadArchivedRange(sessionId: String, limit: Int, beforeCreated: Long): List<MessageWithParts>

/** 归档是否还有数据（翻页 hasMore 判断用） */
suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean
```

实现下沉 MessageStore（内部调用 ArchiveBucketDao + ZstdCodec）。**注意**：接口扩展会波及一期 `MessageCacheRepository` 的 Fake/Test——需同步补。

### 3.7 MessageStore 归档编排

```kotlin
// upsertMessages 内，prune 步骤改为：
val candidates = dao.oldestMessages(sessionId, overflow)   // 溢出条数
if (candidates.isNotEmpty()) {
    val archived = buildArchiveBuckets(sessionId, candidates)  // 序列化+压缩+分桶
    archiveDao.upsertAll(archived)
    AppLogger.d(TAG, "[archive] session=$sessionId: ${candidates.size} msgs → ${archived.size} buckets")
}
dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)  // 归档完成后执行原 DELETE 语义
```

- `buildArchiveBuckets`：candidates 按时间窗口分组 → 每组序列化 → 若 > 512KB 按 200 条切分子桶 → zstd 压缩。**子桶仍带各自 `bucketStart`/`bucketEnd`**（子桶内最晚 created），翻页游标逐桶推进
- **事务性**：归档 + 删除在 `withTransaction` 内，失败回滚不丢数据（保持"内存视图不受影响"原则）

### 3.9 `loadArchivedRange` 跨桶拼接语义

```kotlin
suspend fun loadArchivedRange(sessionId: String, limit: Int, beforeCreated: Long): List<MessageWithParts>
```

- 从 `bucketEnd < beforeCreated` 的最新桶开始，**逐桶降序读取并解压**，跨桶拼接直到凑满 `limit` 条或归档读尽
- 每读一个桶 `touch(id, now)` 更新 `lastAccessedAt`（TLRU 元数据）
- 返回解压后的 `List<MessageWithParts>`（内存态），顺序为桶内 created 升序、桶间更早优先
- `hasArchivedMessages(sessionId, beforeCreated)`：`latestBefore(beforeCreated, 1)` 非空即 true

### 3.8 MessagePaginationUseCase 翻页改造

```kotlin
suspend fun loadOlderMessages(serverId, sessionId, limit, beforeId): Result<LoadOlderResult> {
    // 1. 本地归档优先
    val beforeCreated = messageStore.messageCreatedAt(beforeId)
    val archived = if (beforeCreated != null) messageStore.loadArchivedRange(sessionId, limit, beforeCreated) else emptyList()
    if (archived.isNotEmpty()) return Result.success(LoadOlderResult(archived, source = ARCHIVE))
    // 2. 归档读尽 → 网络
    ... 现有网络逻辑 ...
    return Result.success(LoadOlderResult(page.messages, source = NETWORK))
}
```

新增 `LoadOlderResult(source)` 标记来源 → `MessagePaginationDelegate` 据此：
- `ARCHIVE`：只 `chatRepository.upsertMessages(APPEND_ONLY)` 进内存，**不落热表**（防死循环）
- `NETWORK`：现有行为（落库由 upsert 内 persistOldBeyondWindow=false 自控）

## 4. 错误处理

| 场景 | 行为 |
|------|------|
| zstd 解压失败（桶损坏） | `runCatching` 捕获 → AppLogger.e + 跳过该桶（返回空），不崩溃；桶保留待 sweep 清理 |
| 归档序列化失败 | 捕获 + 日志；**放弃归档直接 prune**（数据按一期行为丢失，日志可查）——归档是增强不是正确性依赖 |
| Room 损坏 | 沿用 DatabaseRecovery 删除重建（归档随库删除，可接受：服务端是真相源） |
| 归档桶超保护上限 | sweep 删 `leastAccessed` 最久未访问桶直到 ≤ 上限，日志记录 |

## 5. 测试策略

### 单元测试（test/）

- **ZstdCodecTest**：roundtrip（压缩→解压==原文）、空数组、非空大小、损坏数据抛异常
- **ArchiveBucketDaoTest**：upsert/latestBefore 游标（bucketEnd < beforeEnd 降序）/count/leastAccessed/touch/clearSession
- **MessageStoreTest 扩展**：
  - prune 溢出时归档调用（oldestMessages → archiveDao.upsert → pruneCandidates 顺序）
  - 窗口外消息不触发归档（一期语义保留）
  - `loadArchivedRange` 解压返回
  - 512KB 分桶逻辑（mock 大 payload）
- **MessagePaginationUseCaseTest**：归档优先（有归档不调网络）/归档空走网络/ARCHIVE 来源标记
- **MigrationTest**：v1 → v2 建表，旧数据保留

### 验证矩阵

1. `compileDevDebugKotlin`
2. `testDevDebugUnitTest --rerun`
3. 模拟器：注入 >1000 条 → 真实写入触发归档 → logcat `[archive]` → db 实证热表 1000 + 归档桶 >0 → 断网翻页 → 归档解压显示 + `[dearchive]` 日志 → 恢复网络

## 6. 提交粒度

按依赖序拆分：zstd-jni 依赖 → ZstdCodec → ArchivedMessageDto → ArchiveBucketEntity/Dao + Migration → MessageDao 扩展 → MessageStore 归档编排 → 接口扩展 + Fake 补 → 翻页改造 → 测试全绿。

## 7. 不做什么（YAGNI）

- **不做**归档桶 TTL 自动删除（消息资产，sweep 只做保护上限淘汰）
- **不做**会话列表本地化/FTS5 搜索（批次 3+）
- **不做**压缩级别可配置（固定 level 3）
- **不做**后台定时 sweep 协程（sweep 随进入会话/翻页惰性执行；二期无独立调度器）
