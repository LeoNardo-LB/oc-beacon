# B — 数据层 / 分页 / 归档 / 数据库性能审计报告（v0.2.0..HEAD）

> 审计时间：2026-08-10 · 审计 Agent：deep-explore（只读）· 基线：v0.2.0 (e7b84fe6) → HEAD (2df4a08a)
> 状态：报告由父会话落盘（Agent 无 Write 权限）

## 1. 数据流全景图

```
SSE 事件流（token delta, 每 ~20/s）
MessagePartDelta → MessageEventHandler.pendingDeltas
      ↓ 48ms scheduleFlush
flushPendingDeltas():
  ① _parts StateFlow 更新（内存，O(batch×parts/msg)）
  ② persistSseUpdate → batchScope.launch{ store.upsertMessages }
         ↓ IO 线程，fire-and-forget
    MessageStore.upsertMessages:
      ├ dao.oldestMessageId    (query)
      ├ dao.messageCreatedAt   (query)
      ├ filter (>= oldestCreated 窗口裁剪)
      ├ dao.upsertMessages/parts (write, REPLACE)
      ├ dao.countForSession    (query)
      └ overflow>0 → archiveOverflow:
           ├ dao.oldestMessages(overflow) + parts (chunked IN≤900)
           ├ buildArchiveBuckets (zstd 压缩, 事务外)
           └ withTransaction{ archiveDao.upsertAll + dao.pruneToLimit }
                       ↓ 内存热视图订阅
ChatRepository.getMessagesFlow (种子化):
  observeMessages(sessionId).first()  ← 仅一次！非持续订阅
  → eventDispatcher.upsertMessages(APPEND_ONLY) → 内存热视图
  → emitAll(eventDispatcher.messages)  ← 持续订阅内存 StateFlow
                       ↓
MessageDataDelegate.messageListState:
  combine(10 flows) → { visible.map{ChatMessage} → suppressPatches }
  注：b07b7ccc 已移除 combine 内 O(n log n) 排序；依赖写入路径有序
                       ↓
翻页 loadOlderMessages（3 游标状态机）:
  Delegate → UseCase.loadOlderMessages
    ├ networkCursor 非空 → 网络（跳过归档）
    ├ archiveCursor 非空 → MessageStore.loadArchivedRange
    │     while(need>0): latestBefore(limit=1) → 解压整桶 → touch(写)
    │     ↑ N+1 查询模式；凑满 limit 退出
    └ 归档读尽 → 网络 listMessages(before=CursorCodec.encode)
         → upsertMessages(persistOldBeyondWindow=false)
         → APPEND_ONLY 合并（O(n) + O(n log n) 排序）
```

## 2. 各环节代码证据 + 风险分级

### P0-1：DatabaseRecovery 捕获范围过宽 → 误删整个数据库（数据静默丢失）
- 证据：DatabaseRecovery.kt:29-38（catch SQLiteException → context.deleteDatabase(DATABASE_NAME) → null）
- 调用点：MessageStore.kt:47, 226, 237, 242, 247, 269, 296（7 处全包）
- 根因：SQLiteException 是基类——SQLiteDatabaseLockedException（非损坏）/ SQLiteConstraintException（非损坏）/ SQLiteDiskIOException / SQLiteFullException（磁盘满）都会触发删库
- 唯一应触发：SQLiteDatabaseCorruptException
- 影响：所有用户；偶发但灾难性（缓存消息+归档+日志全清）

### P1-1：loadOlderMessages 缺乏并发保护 → 竞态重复加载
- 证据：MessagePaginationDelegate.kt:194-260（line 197 _isLoadingOlder.value=true 在 scope.launch 内，无入口 guard）
- 触发链：ChatMessageList.kt:361-385（snapshotFlow collect 无去抖，layoutInfo 持续变化 → 多次 collect → 多个并发 launch）
- 后果：相同 archiveCursorCreated 拉相同消息（APPEND_ONLY distinctBy 去重兜底，但网络/DB 资源浪费）

### P1-2：upsertSsePriority/RestAuthority/AppendOnly 写入路径 O(n log n) 排序
- 证据：MessageEventHandler.kt:408（upsertSsePriority）、453（upsertRestAuthority）、508（upsertAppendOnly distinctBy+sortedBy）、151（handleMessageUpdated msgs.sortBy）
- 现状：b07b7ccc 移除 combine 内排序（MessageDataDelegate.kt:182-185 注释明确），但写入路径排序仍在
- 影响：1000-2000 条会话每次变更 ~10000-40000 次比较；SSE 新消息粒度触发 + 分页加载触发

### P2-1：loadArchivedRange N+1 查询 + 写模式
- 证据：MessageStore.kt:264-292（while(need>0) { latestBefore(limit=1) + touch() }）
- 问题：每桶 1 查询 + 1 写；桶被字节上限切小时多次循环
- 缓解：通常桶 200 条 1 次循环；latestBefore 走 (sessionId, bucketEnd) 索引（ArchiveBucketEntity.kt:16）

### P2-2：loadArchivedRange 解压整桶浪费
- 证据：MessageStore.kt:302-307（decodeBucket 解压整个桶最多 200 条/512KB + 桶内排序），只需 30 条
- 线程：withContext(Dispatchers.IO)（line 268）✅ 不阻塞主线程

### P2-3：MessageDao.messagesForSession 的 OR 子句
- 证据：MessageDao.kt:19-24（(:beforeId IS NULL OR id < :beforeId) → 可能放弃复合索引）；索引 CachedMessageEntity.kt:13 (sessionId, created)
- 次要：ORDER BY created DESC, id DESC 与索引不完全匹配
- 缓解：热表限 1000 条（MessageStore.kt:366），全表扫描也快

### P2-4：SSE 双写高频落盘
- 证据：MessageEventHandler.kt:86-129, 194-204（每 48ms flush → upsertMessages 3 查询 + 写 + 可能归档）
- 频率：活跃流式 ~20 次/s 落盘；WAL 缓解（DatabaseModule.kt:24）

### P3-1：归档双游标复杂度债务
- 证据：MessagePaginationDelegate.kt:70-79（archiveCursorCreated/networkCursorId/networkCursorCreated 三个独立 var + 注释描述关系）
- 未持久化：ViewModel 销毁后丢失，进入会话重置（line 144-146）

### P3-2：pruneToLimit 嵌套子查询
- 证据：MessageDao.kt:47-52（DELETE ... NOT IN (SELECT ... LIMIT)），热表 1000 条可接受

### P3-3：坏桶跳过可能导致静默数据丢失
- 证据：MessageStore.kt:277-280（坏桶跳过有 AppLogger.e 日志）+ 131-133（archive 逐条容错）
- UI 无提示，用户看到"消息少了"不知原因

## 3. 补丁 vs 根因判定表

| Commit | 判定 | 理由 | 技术债残留 |
|--------|------|------|-----------|
| c5e0ea56 双游标 | 根因修复 + 复杂度债 | 真正解决游标死循环（MessagePaginationUseCase.kt:90-101 networkBeforeCreated 分支正确） | 3 游标状态分散、无持久化、无单一抽象 |
| d30a0d57 归档游标推进 | 根因修复 | MessageStore.kt:287 bucket.bucketStart 推进 | 跨桶 ULID 毫秒精度边界（line 262 注释接受） |
| ff192fd5 APPEND_ONLY 合并 | 根因修复 | MessageEventHandler.kt:508 替换→合并 | 写入路径 O(n log n) 排序仍在（P1-2） |
| b07b7ccc 滑动卡顿 7 项 | 根因（混合） | IN 分块 ✓ / O(n) 移除 ✓ / combine 排序移除 ✓ | upsert* 排序仍在；OR 子句未优化 |
| 69df372b 归档逐条容错 | 根因修复 | 单条 payload 解码失败只跳过该条 | 坏桶跳过静默丢数据（P3-3，有日志） |
| a7aec358 L3 limit=50 | 针对性补丁 | 限制拉取量治标 | L3 触发频率未根治 |
| 6fdff190 DatabaseRecovery | 有缺陷的根因修复 | 解决"DB 损坏无法使用" | **catch SQLiteException 基类过宽**（P0-1） |
| 477a308d 坏桶 continue | 根因修复 | 坏桶跳过且游标推进，不死循环 | — |

## 4. 游标状态机图

```
进入会话 loadMessagesForSession
        │
        ▼
┌─────────────────────────────┐
│ INITIAL                     │
│ archiveCursorCreated=null   │
│ networkCursorId=null        │
│ networkCursorCreated=null   │
│ hasOlder=(size>=limit)      │
└─────────────┬───────────────┘
              │ loadOlderMessages (首次翻页)
              ▼
┌─────────────────────────────┐
│ HOT_TABLE_BOUNDARY          │
│ beforeId = hotOldestId      │
│ 查 hasArchivedMessages?     │
└─────┬───────────────┬───────┘
 有归档│               │无归档
      ▼               ▼
┌──────────────────┐  ┌────────────────┐
│ ARCHIVE_PAGING   │  │ NETWORK_PAGING │
│ archiveCursorCreated│ networkCursorId│
│ hasOlder=true    │  │ networkCursorCreated│
│ (无条件 line224) │  │ hasOlder=size>=limit│
└────────┬─────────┘  └────────┬───────┘
         │ 归档读尽 fall-through │ 网络读尽
         ▼                      ▼
    ┌─────────────┐      hasOlder=false (停止)
    │ NETWORK     │
    │ (跳过归档)  │
    └─────────────┘
         │ 网络失败
         ▼
┌──────────────────────────────┐
│ BACKOFF / PAUSED             │
│ autoLoadFailures++           │
│ 500ms→1s→2s→4s→8s            │
│ >=3次 → autoLoadPaused       │
│ 成功 → 清零恢复               │
└──────────────────────────────┘

一致性边界：
• ARCHIVE→NETWORK 转换时 archiveCursorCreated 重置为 null（line 231），networkCursor 记住边界
• ViewModel 销毁 → 三游标全丢失 → 重进入会话从 INITIAL 重来
• ARCHIVE 来源 hasOlder 无条件 true（line 224）——由 use case fall-through 到网络兜底
```

## 5. 风险清单摘要

- **P0：1 个**（DatabaseRecovery 删库范围过宽）
- **P1：2 个**（loadOlderMessages 无并发保护；upsert* 写入路径 O(n log n) 排序）
- **P2：4 个**（loadArchivedRange N+1；整桶解压浪费；messagesForSession OR 子句；SSE 双写高频）
- **P3：3 个**（游标复杂度债；pruneToLimit 嵌套；坏桶静默跳过）
