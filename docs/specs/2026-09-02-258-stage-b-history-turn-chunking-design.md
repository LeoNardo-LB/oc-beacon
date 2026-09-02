# #258 Stage B：历史长 turn 分片（TurnSegmentPlan）设计

> 状态：**已实现+真机验证**（2026-09-02；证据见 journal 2026-09-02-258-stage-b-history-chunking §四；待用户验收）
> 关联：backlog #258 · Stage A journal `docs/journal/2026-09-02-258-perfetto-stage-a.md` · Stage B journal `docs/journal/2026-09-02-258-stage-b-history-chunking.md`
> 前置：`docs/journal/2026-08-27-event-card-unification.md` §二十八轮（测量矩阵）；#246 键序/锚点语义；#265 流式试点

## 1. 问题与证据

### 1.1 Stage A 定音（perfetto + trace_processor）

fast fling 下历史长 assistant turn 首组合 165-334ms（4 个 `flng:it:turn-a` 实测），
内部结构 ≈ 55 小 text（~1.3ms/个）+ 25 tool（~1.6ms/个）+ 30 reason（~2.5ms/个）+ 6 长 text（11-33ms/个）。
**成本按 part 数量摊开，而非集中在单个巨型 part**——与流式期「一条 130K text part」形态完全不同。

### 1.2 Stage B 现场取证（2026-09-02，devDebug e3f0de61，目标会话 DSH `StreamingMarkdownState优化与自动验证`）

- 服务器事件日志全量走页（624,203 events）：该会话 2,013 个 text/reasoning part 中
  **175 个 ≥3000 字符**；头部 turn（turn 176/173/25）= 13-47 万字符 / 135-476 parts——
  **巨型 part 分片门槛（CHUNK_MIN_CHARS=3000）本可满足**，但现场 logcat：
  - `CHUNK plan` ×14（计划确实算出来了，全部 `_reasoning_ord_` 后缀的 Part.Text）
  - `CHUNK commit` ×0 / `ChunkDiag compose` ×0——**计划全部滞留 pending，无一提交**
- 失败链：视口内/裂变带内的 turn 计划只能 skip 计数（需 3 轮视口巡检才强制提交），
  fling 停止后 `onViewportChanged` 不再触发 → 永不提交；而**视口循环的解析洪峰**
  （±20 display 窗口内所有 ≥200 字符 part 全量预解析，数百个后台解析排队）进一步
  推迟关键 part 的解析完成时机，fling 早已越过。
- 会话进场只装 1 页（`total=10`）：巨帧本体 = 进场首屏 turn（可见，无法后补分片）
  **+ fling 途中 loadOlder 前置页到达的 turn（未可见，可在到达时刻抢先分片）**。

### 1.3 根因链（Stage B 修什么）

1. **计划时机错位**：分片计划依赖「视口接近 → 预解析 → pending → 下次视口巡检提交」，
   对数据到达（进场页/前置页 prepend）没有主动路径；高速 fling 下该链路结构性迟到。
2. **模型错配**：`MdChunkPlan` 只切**单个巨型 text part 的 AST 区间**，turn 内其余
   parts 原样挤进首/末片——对 part 数量主导的重 turn（334ms 样例），即便单 part
   计划成功提交，首/末片仍可能携带数十个 part ≈ 数十 ms 帧。

## 2. 设计

### 2.1 两级计划：TurnSegmentPlan

```kotlin
/** 历史长 turn 的分段计划（renderItem 边界 + 巨型 part AST 细分）。 */
data class TurnSegmentPlan(
    val turnKey: String,            // "t_<turn首消息id>"（与 ChatEntry key 同源）
    val representativeMsgId: String,// 指纹校验锚（计划 vs 现时 turn）
    val fingerprint: Int,           // MessageFingerprints.messageFingerprint（陈旧检测）
    val segments: List<Segment>,    // 文档序
) {
    val chunkCount: Int get() = segments.sumOf { it.chunkCount }

    sealed interface Segment {
        val chunkCount: Int
        /** 连续 renderItems 段（中小 part 聚合；1 chunk）。 */
        class Items(val from: Int, val to: Int) : Segment { override val chunkCount get() = 1 }
        /** 巨型 part 的 AST 区间段（1..N chunks；复用 computeChunkPlan 产物）。 */
        class Giant(
            val partId: String,
            val ranges: List<IntRange>,
            val state: State.Success,
            val anchors: List<String>,
        ) : Segment { override val chunkCount get() = ranges.size }
    }
}
```

- 切割对象 = `computeRenderableTurn(...).renderItems`（**与渲染层同一序列**——分段
  天然落在 item 边界，不拆 ContextToolGroup/RepeatingTool）。
- 权重标定（Stage A 实测 per-part 固定成本 → 字符当量）：
  | render item | 权重（字符当量） | 依据 |
  |---|---|---|
  | Single(Text) | `200 + text.length` | 长文本 ~3ms/KB；包装 ~0.2ms |
  | Single(Reasoning) | `700` | 折叠卡恒 ~2.5ms（trace max 3.2） |
  | Single(Tool)/Context 组/RepeatingTool | `550` | tool 卡 ~1.6ms |
  | SyntheticNotice | `900` | 事件卡 |
  | TurnDivider | `50` | 分割线 |
- 参数（companion 常量，可 JVM 单测）：
  - `TURN_SEGMENT_MIN_WEIGHT = 12000`（低于此不分片——短 turn 单帧可容忍）
  - `SEGMENT_TARGET_WEIGHT = 3000`（目标段权重；334ms 样例 → ~20+ 段 ≈ 每段 ~15ms）
  - `SEGMENT_MAX_ITEMS = 10`（段内 item 数上限——固定成本主导的 turn 也均匀摊开）
  - 巨型 part 门槛复用 `CHUNK_MIN_CHARS = 3000`（AST 细分只对它做）
- 算法（纯函数 `computeTurnSegments`，JVM 可测）：单趟扫描 renderItems，累计权重；
  巨型 text part（`Part.Text.length ≥ CHUNK_MIN_CHARS` 且可渲染）**独立成段**（其
  renderItems 邻位切割）；其余按 target/max-items 落刀；尾段兜底合并（避免末段过碎）。
  切不出 ≥2 段 → null。

### 2.2 计划时机：到达扫描（onWorldArrived）

`RenderSupplyCoordinator` 新增入口，由 ChatMessageList 在
`displayItems/turnGroups/renderableTurns` 变化（进场页/前置页 prepend/loadAround/进场）
时调用，携带视口快照：

- 扫描条件：assistant turn · 非流式 · `turnKey ∉ recentStreamedTurnKeys` ·
  **在 `chunkPlans`（旧路径）无已提交 part** · `turnKey ∉ segmentPlans` ·
  **display index 在裂变带（视口 ±FISSION_SAFE_MARGIN）之外**。
- 每个命中 turn：
  1. 计算 skeleton（段切割 + 巨型 part 清单）；
  2. 无巨型 part → **立即提交**（emit `segmentPlans += turnKey`，同步、零等待）；
  3. 有巨型 part → 逐个 `registry.preParse`（已 Parsed 跳过），全部解析完成后
     `computeChunkPlan` 拼 `Giant` 段再提交（提交前重解析 display index 仍在带外）。
- 排序：按距视口升序（最近的 turn 先计划先提交——fling 最先撞上的是它）。
- 有界性：每次世界变化最多提交 `ARRIVAL_PLAN_MAX_TURNS = 24` 个 turn（loadAround
  深跳批量到达时节流；剩余由下一次世界变化/视口巡检捡起）。解析排队由
  Dispatchers.Default（4 线程）天然限流。AST 常驻内存随计划数增长（~4× 文本量），
  深滚会话最坏几 MB——v1 接受，spec 留档。
- **提交门控复用既有语义**：裂变带外 + 冷（未可见）才提交；带内一律不提交
  （与 F2 一致——可见/缓存池内 turn 绝不裂变）。

### 2.3 发射与键序（buildChatEntries）

- 新条目 `ChatEntry.TurnChunk(displayIndex, key = turnKey + "#s<i>", plan, chunkIndex, chunkCount)`。
  - 前缀保持 `t_`（可见项过滤依赖）；`#s` 与旧 `#c` 区分（互斥不碰撞）。
  - **逆文档序发射**（#246 语义：reverseLayout 下尾片先入列），`displayEntryStart`
    钉回首片（含标签栏）——跳转落点 = turn 首段，与旧路径 UX 一致。
- 互斥优先级：`MdChunkPlan`（旧，流式期产物）**>** `TurnSegmentPlan`（新）——
  已有旧计划的 turn 走旧路径，新扫描也跳过它们。
- **陈旧防护**：buildChatEntries 消费时校验 `plan.fingerprint ==
  MessageFingerprints.messageFingerprint(现 turn 代表消息)`，不等 → 丢弃该计划
  （turn 内容已变——REST 刷新/分页替换；下次世界变化重算）。

### 2.4 渲染（SegmentedAssistantMessage）

新 composable（与 ChunkedAssistantMessage 平行，不改动它）：

- `isFirst` 段：标签栏（时间 + agent 徽标，同旧①）；`isLast` 段：errorText +
  ChunkStatsBar（同旧④）。
- `Items` 段 chunk：`ChunkAssistantItems(subList(from, to))`（复用旧②④的渲染循环）。
- `Giant` 段 chunk：`SelectionContainer + MarkdownContent(preParsedState, blockRange,
  blockAnchor)`（同旧③，锚点重定位语义照搬）。
- 段间 shape：首段顶圆角/末段底圆角/中段直角；AMOLED 边框简化同旧路径。
- `contentType = "assistant_segment"`（LazyColumn 复用池隔离）。

### 2.5 与既有机制的共存边界

| 机制 | Stage B 行为 |
|---|---|
| 流式 turn（C-R3） | 不分片——扫描条件排除（`isStreamingTurn`） |
| 流式刚结束（recentStreamedTurnKeys） | 不分片；滚出窗口后由旧 MdChunkPlan 视口循环或新到达扫描接管（后者在下次世界变化时自然覆盖） |
| 用户长消息分片（UserChunk） | 无交集（仅 assistant turn 参与） |
| MdChunkPlan（旧路径） | 完整保留——流式期产物与既有 #246/#265 修复不动；与 TurnSegmentPlan 互斥（旧优先） |
| 预解析 LRU（preparseSeenKeys=48） | 不动——到达扫描的 giant 解析同样走 registry（复用就绪态，重复解析被去重） |
| 跳转（displayEntryStart） | 同语义钉首片 |
| 指纹缓存（renderableCache） | 复用 `MessageFingerprints.messageFingerprint` 做计划陈旧检测 |

## 3. 测试计划

1. **`TurnSegmentTest`（新）**：短 turn → null；part 数量主导 turn → 多段 +
   max-items 上限；巨型 part 独立成段；权重累计正确；空白 turn → null；
   巨型 part 恰在首/尾的边界。
2. **`ChunkEntryOrderTest` 扩展**：TurnChunk 逆序发射、key 方案、displayEntryStart
   钉扎、与 MdChunkPlan/UserChunk 互斥（旧优先）、流式/recentStreamed 排除、
   指纹失配丢弃。
3. **`RenderSupplyCoordinatorTest` 扩展**：onWorldArrived 带外提交 / 带内不提交 /
   巨型解析门（全解析才提交）/ 旧计划 turn 跳过 / 节流上限。
4. 既有全量单测（`testDevDebugUnitTest --rerun`）零回归。
5. 真机质量门：fling-perf-probe（gfxinfo 矩阵）+ perfetto-fling-capture 复跑——
   验收 = `flng:it:turn-a` 长样例消失/转 `flng:it:seg`（单片 ≤ ~20ms）、p99 帧时长
   不高于 2026-09-02 基线（32/40/53ms）、janky% 不升、`ChunkDiag`/分段 compose 日志
   出现分段条目。

## 4. 明确不做（本批）

- 不动流式路径与 MdChunkPlan 内部逻辑（#246/#265 加固区）。
- 不做 SelectionContainer 二阶优化（Stage A 靶点 2——分片落地后收益稀释）。
- 不做计划内存淘汰（LRU for segmentPlans）——观察期后视深滚实测再定。
- `_reasoning_ord_` 后缀 Part.Text 的类型来源之谜：不阻塞本设计（无论来源，它们
  按正文渲染、按正文分片）；后续单独取证。
