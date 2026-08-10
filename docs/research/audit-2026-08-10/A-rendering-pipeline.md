# A — 消息渲染管线性能审计报告（v0.2.0..HEAD）

> 审计时间：2026-08-10 · 审计 Agent：deep-explore（只读）· 基线：git log v0.2.0..HEAD
> 状态：报告由父会话落盘（Agent 无 Write 权限）

## 1. 管线全景图

```
SSE token 到达 (Dispatchers.IO)
    │
    ▼
MessageEventHandler.scheduleFlush()  [data/repository/handler/MessageEventHandler.kt:76]
    │  铁律2：if (batchJob?.isActive == true) return — 不取消进行中定时器
    │  delay(48ms)
    ▼
flushPendingDeltas()  [MessageEventHandler.kt:86]
    │  ⚠ 每个 delta: toMutableList + Map"+" 拷贝 (line 97,118)
    │  → _parts StateFlow.update
    │  → _messages StateFlow.update (handleMessageUpdated)
    ▼
MessageDataDelegate.messageListState combine  [MessageDataDelegate.kt:94-330]
    │  chatMessageCache 实例复用 (line 97-101) — 消除全量重建
    │  移除 O(n log n) 重复排序 (b07b7ccc)
    │  移除 MsgDiag 日志风暴 (b07b7ccc)
    ▼
ChatViewModel → ChatUiState → collectAsStateWithLifecycle
    │
    ▼
ChatMessageList 重组  [components/ChatMessageList.kt]
    │  ├ turnGroups 缓存 (id 序列签名, line 140-150)
    │  ├ streamingMsgId (只看 completed==null, line 176-180) — 铁律5
    │  ├ renderableTurns 内容指纹缓存 (line 191-225) — 铁律6
    │  │   └ renderableCache.retainAll 择机清理 (line 217-220)
    │  ├ jumpTargets 结构签名缓存 (line 273-284)
    │  ├ shouldCompensate LaunchedEffect 双key (line 240) — 铁律4
    │  └ 诊断 LaunchedEffect(Unit) 残留 ⚠ (line 251-267, 555-557)
    ▼
LazyColumn (reverseLayout=true)  [ChatMessageList.kt:387]
    │  itemsIndexed key = turn 身份 (line 517-525)
    │  ├ tool_progress item: layout{} 补偿 (line 437-458) ⚠反射
    │  └ 流式 message item: layout{} 补偿 (line 529-551) ⚠反射
    │      └ LazyListReflection.requestScrollToItemNoCancel ⚠ (ScrollCompensation.kt:41)
    ▼
MessageCard → MessageCardAssistant  [components/MessageCardAssistant.kt]
    │  ├ 100ms ticker (nowMs) ⚠ (line 157-163)
    │  └ renderItems 遍历 (RenderableTurn @Immutable)
    ▼
PartContent → MarkdownContent  [markdown/MarkdownContent.kt]
    │  rememberMarkdownState(retainState=true) ✅ (line 368-371) — 铁律1
    ▼
渲染
```

## 2. 各环节分析与风险

### 环节 A：SSE 48ms 批处理（铁律 2）—— ✅ 正确
- 证据：MessageEventHandler.kt:76-84（batchJob?.isActive == true 不取消）
- 复杂度：O(1) 调度，flush 时 O(batch size)。无风险。

### 环节 B：flushPendingDeltas Map 拷贝 —— P3 低风险
- 证据：MessageEventHandler.kt:94-120（line 97 toMutableList，line 118 Map"+" 拷贝）
- 分析：48ms 窗口 delta 通常小；batch 大时 O(batch × mapSize) 拷贝显著
- 建议：batch 内按 messageId 聚合后再更新 Map（单次拷贝）

### 环节 C：MessageDataDelegate combine —— ✅ v0.2.0 后根因修复
- 证据：MessageDataDelegate.kt:97-101（chatMessageCache 复用）、182-185（移除 O(n log n) 排序）、252,330（移除日志风暴）
- 风险：无增量问题

### 环节 D：派生计算缓存（铁律 6）—— ✅ 正确
- 证据：ChatMessageList.kt:191-225（messageFingerprint 内容指纹 + retainAll 清理 line 217-220）；RenderableTurn.kt:18,32,34,36（@Immutable）
- 风险：tailHash 只哈希末 64 字符 + 长度（MessageFingerprints.kt:64-68）——理论碰撞，实际概率极低 P3

### 环节 E：高度补偿 layout{} —— P1 高风险（反射）
- 证据：ChatMessageList.kt:529-551（流式消息补偿，isStreamingMsg 门控 ✅ 铁律3）、437-458（工具卡片补偿）；ScrollCompensation.kt:21-46（LazyListReflection.requestScrollToItemNoCancel）
- 核心问题：反射访问 LazyListState 的 private 字段：scrollPosition、requestPositionAndForgetLastKnownKey、measurementScopeInvalidator
- 风险：① Compose 版本升级会运行时崩溃（NoSuchFieldError/NoSuchMethodError）② 无编译期保护 ③ 3 处调用点（ChatMessageList:318, 448, 539）
- 根因判定：**补丁**——官方 requestScrollToItem 会通过 scroll{} 互斥锁杀死 fling，无"设置位置但不取消 fling"的公开 API

### 环节 F：诊断代码残留 —— P1 高风险
- 证据 1：ChatMessageList.kt:251-267（JUMP 检测 LaunchedEffect(Unit)，snapshotFlow 持续 collect 每帧，注释明示"诊断埋点...验证 beyondBoundsItemCount 修复后"）
- 证据 2：ChatMessageList.kt:555-557（每 item 组合日志 AppLogger.d，无 BuildConfig.DEBUG 门控——AppLogger.kt:25-26 DEBUG 级 release 也写 logcat）
- 对比：分页触发处（line 378,381）有 DEBUG 门控，这两处没有

### 环节 G：100ms ticker 额外重组 —— P2 中风险
- 证据：MessageCardAssistant.kt:155-163（nowMs mutableStateOf + while(isStreaming) delay(100)）
- 分析：流式 ~30次/s 重组（48ms flush ~20 + ticker ~10），仅影响单个流式消息 footer
- 判定：可接受设计，可优化（derivedStateOf / delay 250ms）

### 环节 H：内存——长会话无窗口裁剪 —— P2 中风险
- 证据：MessageDataDelegate.kt:179-189（visible = 全部 sessionMessages）；grep 确认全库无窗口裁剪
- 分析：LazyColumn 回收视图但数据层全量驻留；renderableCache.retainAll 已限制缓存只含可见 assistant 消息
- 影响：长会话（>2000 条）GC 压力 + combine 开销

### 环节 I：分页触发 —— ✅ 设计良好
- 证据：ChatMessageList.kt:340-385（LaunchedEffect keys + snapshotFlow + distinctUntilChanged + filter + 防风暴退避 + DEBUG 门控日志）

### 环节 J：主线程违规 —— ✅ 无增量问题
- AppLogger Channel 异步（AppLogger.kt:40-49）；草稿持久化已移 IO（0eaac6dc）；derivedStateOf 使用合理

## 3. 补丁 vs 根因判定表

| Fix | 判定 | 理由 | 技术债残留 |
|-----|------|------|-----------|
| scheduleFlush 不取消 timer（铁律2） | ✅ 根因 | 正确的批处理模式 | 无 |
| chatMessageCache 实例复用 | ✅ 根因 | 消除每 48ms 全量重建 | 无 |
| 移除 O(n log n) 重复排序（b07b7ccc） | ✅ 根因 | 排序已在写入路径完成 | 无 |
| 移除日志风暴（b07b7ccc） | ✅ 根因 | combine 每 48ms 4 条日志 | ⚠ ChatMessageList:556 composed 日志未清理（同源残留） |
| renderableTurns 内容指纹缓存（铁律6） | ✅ 根因 | 内容感知复用 + retainAll | 无 |
| isAtBottom 双 key 自愈（铁律4） | ⚠ 补丁掩盖 | 文档自承"自愈机制"，reverseLayout 滚动状态碎片化 | Compose 固有复杂性 |
| requestScrollToItemNoCancel 反射 | ❌ 补丁 | 依赖 private 字段名 | **Compose 升级会崩**，3 处调用点 |
| 100ms ticker | ⚠ 可接受 | 实时耗时显示需求 | 轻微额外重组 |
| 移除 OptimisticMessageStore | ✅ 根因 | 简化状态机 | 无 |
| AnimatedVisibility 移除 | ✅ 根因 | requestScrollToItem 同帧锚定替代 | 无 |

## 4. 系统性问题清单（按风险排序）

### P1-1：反射依赖 Compose internal 字段（requestScrollToItemNoCancel）
- 现象：高度补偿通过反射访问 LazyListState private 字段
- 证据：ScrollCompensation.kt:22-46；调用点 ChatMessageList.kt:318,448,539
- 根因：官方 API 无"设位置不取消 fling"
- 建议：① 升级 Compose 时加版本检测 + 降级 requestScrollToItem；② 向 Compose 提 feature request；③ 短期 try-catch + fallback

### P1-2：诊断代码残留（未清理的调试埋点）
- 证据：ChatMessageList.kt:251-267（JUMP 检测 snapshotFlow 持续 collect）+ 555-557（每 item composed 日志，无 DEBUG 门控）
- 根因：b07b7ccc 清理了 MessageDataDelegate 的日志风暴，ChatMessageList 内诊断埋点遗漏
- 建议：删除（诊断任务已完成，注释明示）

### P2-1：100ms ticker 叠加 48ms flush
- 证据：MessageCardAssistant.kt:157-163
- 建议：delay 增至 250ms 或 derivedStateOf 包装

### P2-2：长会话无消息窗口裁剪
- 证据：MessageDataDelegate.kt:179-189；全库无窗口化
- 建议：数据层滑动窗口（首尾 N 条 + 视口附近）

### P3-1：flushPendingDeltas batch 内重复 Map 拷贝
### P3-2：tailHash 只取末 64 字符（可接受）

## 5. 结论
v0.2.0 后渲染管线增量改动多数是根因修复，五条铁律全部正确遵守。主要技术债：反射 hack（补丁）+ 诊断代码残留（清理遗漏），非架构性倒退。
