# DSH 会话压缩（compaction/checkpoint）wire/存储层绑定点语义调研

> 调研对象：`@deepseek-ai/dsh` 0.1.7-rc.1（`package.json:3`），源码为打包后 ESM（`lib/*.js`）+ 完整 `.d.ts`。
> 代码根目录下文记作 `$B` = `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai`。
> 所有行号可直接定位（`.d.ts` 为声明文件，`.js` 为实现）。本文只读调研，未修改任何文件。

## 0. 一句话结论

**压缩不是"插在消息流时间点上的标记"，而是"追加在日志尾部的高 seq 替换消息 + 一个 replace 型 surfaceOp"**：摘要作为一条新的 `user/message` 追加到会话日志末尾（高 seq），通过 `surfaceOp: { op: 'replace', startSeq, endSeq }` 声明它**遮蔽（shadow）**一个更早的 surface 区间。原始历史事件**从不删除**，只是被 surface 投影遮蔽。客户端若按信封 seq 排序渲染，压缩卡天然落在流末尾——这是设计使然，不是 bug。

---

## 1. DSH 如何表示一次压缩

核心包：`$B/dsh-compaction`（类型/不变量接缝）+ `$B/dsh-compaction-basic`（后端实现）+ `$B/dsh-command-compact`（手动 `/compact`）。

### 1.1 会话事件（SessionEventMap 扩展，declaration-merged）

四个 `compaction/*` 会话事件，**全部是 log-only（无 surfaceOp，不进 surface）**：

- `compaction/start` — `$B/dsh-compaction/lib/types/types.d.ts:21-25`：payload `{ compactionId, sourceCommandId?, turn: number | null }`；"持有锁直到 compaction/end"（:16-19）。
- `compaction/summary` — 同文件 :35-68：payload `{ compactionId, sourceCommandId?, summary: ContentBlock[], shadowedRange: { start: SessionSeq; end: SessionSeq }, shadowedSeqs: SessionSeq[], shadowedTokenCount: number, provider, model, maxTokens?, usage?, rawOutput?, llmStreamCall? }`。注释明确："实际 surface 替换由紧随其后的 user/message 事件执行，该邻接关系是契约性的"（:27-33）。
- `compaction/end` — 同文件 :73-78：`{ compactionId, sourceCommandId?, turn: number | null, error? }`；`error` 记录失败尝试（:70-72）。
- `compaction/prune` — 同文件 :88-98：无摘要的纯裁剪替换的影子定价事件，`{ shadowedRange, shadowedSeqs, shadowedTokenCount }`。
- wire 层枚举确认四个事件名：`$B/dsh-api-session-controller/lib/client.js:147-150`（`compaction/end` / `compaction/prune` / `compaction/start` / `compaction/summary`）。

### 1.2 checkpoint（检查点）

- 类型 `CompactionCheckpointSource` — `$B/dsh-compaction/lib/types/checkpoint.d.ts:17-24`：`{ kind: "compact-checkpoint", compactionId: CompactionId, sourceCommandId?: CommandId }`；构造器 `compactCheckpointSource()` :31，谓词 `isCompactCheckpointSource()` :37（识别"从 surface user message 恢复的 source"）。
- `CompactionId` 为 brand 类型：`$B/dsh-compaction/lib/types/types.d.ts:12-13`。

### 1.3 存储投影

- 事件日志按 seq 追加进 surface：`$B/dsh-session/lib/types/index.d.ts:276-278`（"surface 是派生历史的单一来源，每条 message-producing append 记录自己的 surfaceOp"）。
- `foldSurface` 投影出被替换记录：`$B/dsh-session/lib/types/surface.d.ts:180`（`foldSurface`）、:98（`SurfaceFoldReplacement.seq` = "替换了先前 surface 区间的事件 seq"）。
- 摘要写库时序（`commitCompactionBody`，`$B/dsh-compaction-basic/lib/index.js:627-661`）：先 append `compaction/summary`（:633-649），**紧接着** append 带 `surfaceOp replace` 的 `user/message`（:650-661）。生命周期：`compaction/start` append 于 :468，`compaction/end` 于 :483 与失败路径 :494。

---

## 2. session.history 中的回放与分页

wire 方法是 `session.history`（`page` + `follow`），实现在 `$B/dsh-api-session-controller/lib/types/history.js`：

- `page(request)` :97-132 —— 入参含 `beforeSeq`（:104-106，校验 :324-328）与 `throughSeq`；返回 `{ records, hasMore }`（:120-123）。
- `paginate()` :395-425 —— **计数规则**：从后往前扫，只有 `MESSAGE_TYPES = new Set(['user/message','assistant/message'])`（:60）**且** `isAppendSurfaceEvent(event)`（:409）的事件才计入 `maxMessages`。**但返回的切片是原始事件日志区间** `events.slice(cut, end)`（:425），不做类型过滤。
- 因此：**落在该 seq 区间内的 `compaction/start|summary|end|prune` 事件会随页原样返回**（`pageRecords` 只是 `events.map(entryFor)`，:437-439；`entryFor` 直接透传事件 :432-435）。
- `follow()` :139-260 —— 开场 snapshot（:211-221，同样走 `paginate` + `pageRecords`），之后把 `session/event` 总线上**所有**事件逐条 yield（:158-163 订阅、:245-251 yield `entryFor(event)`），带 seq 连续性校验（:245-250）。压缩事件作为普通会话事件实时下发。
- `hasMore` 语义：`cut > 0`（:426）。
- 结论 2：压缩事件有信封 seq（同一会话日志 seq 序列），分页翻页时**会**随页返回——但前提是它们落在被计数消息切出的 seq 区间内。注意：压缩事件的 seq 不计入 `maxMessages` 配额，只随切片附带。

---

## 3. 压缩与消息的时序绑定

- **同一 seq 序列**：压缩事件与 user/assistant 消息共用同一个会话日志 seq（`SessionSeq`，`$B/dsh-compaction/lib/types/types.d.ts:11`；follow 的 seq 连续性校验 :245-250 对所有事件一视同仁），因此**可按 seq 交错排序**。
- **但压缩的"绑定"不是时间点绑定，而是区间绑定**：
  - `shadowedRange: { start: SessionSeq; end: SessionSeq }` — `$B/dsh-compaction/lib/types/types.d.ts:39-42`（事件）与 :123-128（`CompactionResult`）。**关键注释**（:117-122 原文语义）："这是 surface-**位置** span，不是数值 seq 区间——先前的 replace 在旧区间位置落下新的高 seq 摘要节点后，`start` 可以**大于** `end`；`shadowedSeqs` 才是权威的被遮蔽节点有序集合"。
  - `shadowedSeqs: SessionSeq[]` — 被遮蔽的全部 surface 节点 seq，按 surface 顺序（:43-44 / :130）。
  - 替换消息通过 `sourceEventSeqs` 反向关联来源：`[startEvent.seq, summaryEvent.seq, ...shadowedSeqs]`（`$B/dsh-compaction-basic/lib/index.js:656-660`）；分页切组时也用它把消息组钉在其来源上（history.js:411-418）。
  - 摘要卡与 compactionId 的绑定字段：替换 user message 的 `message.source = { kind: "compact-checkpoint", compactionId, sourceCommandId? }`（`$B/dsh-compaction-basic/lib/index.js:595-598`；类型 `checkpoint.d.ts:20-24`）。**不存在** `source.compactionId` 之外的 `messageId` 绑定字段。
  - `surfaceBound` 字段：DSH 源码中**不存在**名为 `compaction/surfaceBound` 的事件或字段（`grep -rn surfaceBound $B` 零命中）。这是 oc-beacon 客户端自造的术语；DSH 对应概念是 `surfaceOp.replace` 的 `{ startSeq, endSeq }`（surface **位置**绑定）。
- 区间合法性约束（选段必须是平衡边界）：`validateSurfaceRegion`（`$B/dsh-compaction-basic/lib/index.js:549-566`）——tool-call/result 对不得被切开（:561-562）。

---

## 4. 旧消息是否被裁剪？

**原历史保留，摘要是"遮蔽标记 + 尾部替换消息"，不是删除。**

- 日志只追加（append-only）：被压缩消息的原始事件仍在日志里、seq 不变；替换只是让它们在 **model-visible surface** 中被 shadow：`$B/dsh-session/lib/types/surface.d.ts:53-59`（isAppendSurfaceEvent 的 doc："model-visible surface 有意 shadow 被替换区间，因此它是人类转录的错误来源——已落地的 replacement 会抹掉用户已看过的对话。Append-origin 事件才是那份转录的持久素材；replacement 副本仅限 model 使用"）。
- 替换机制：`user/message` + `surfaceOp: { op: 'replace', startSeq, endSeq }`（`$B/dsh-compaction-basic/lib/index.js:650-655`）；`isReplacementSurfaceEvent` 判别：`surface.d.ts:71-75`。
- 分页对人类转录的取舍：history.js:409 用 `isAppendSurfaceEvent` 过滤计数，即**分页的消息计数基于 append-origin（未压缩）消息**——被 shadow 的消息依然占据日志 seq、依然随原始区间切片返回给客户端。
- 对客户端的直接推论：
  - 压缩卡**不应**渲染成"插在消息流中间的时序标记"——它在日志里就是尾部高 seq。
  - 正确渲染是**头卡/区间替换卡**：用 `compaction/summary` 的 `shadowedSeqs` 找到它替代的消息集合，把卡片放在该集合原位置（或把该集合折叠收进卡片），并保留 `message.source.kind === "compact-checkpoint"` 的替换 user message 作为卡内容/锚。
  - 用户"压缩应绑定时间点、随消息上推"的直觉对应的是 **surface 位置**语义（`shadowedRange` 是 surface 位置 span，可 `start > end`），**不是**信封 seq 顺序。客户端拿信封 seq 当渲染位置，就会得到"卡片堆在流末尾"的现状。

---

## 5. SSE 实况事件名与 payload 字段清单（逐字）

DSH wire 上事件名即会话事件 type（follow 逐条透传，history.js:251）；**没有** `compaction/started/ended/failed` 这类 ing 变体，也没有 `compaction/surfaceBound`。对应关系：oc-beacon 的 `compaction/start → DSH compaction/start`、`compaction/summary → compaction/summary`、`compaction/end → compaction/end`（`error` 字段承担 failed 语义）。

### compaction/start（types.d.ts:21-25）
```
compactionId: CompactionId
sourceCommandId?: CommandId
turn: number | null        // null = 回合间的独立手动事务
```

### compaction/summary（types.d.ts:35-68）
```
compactionId: CompactionId
sourceCommandId?: CommandId
summary: ContentBlock[]
shadowedRange: { start: SessionSeq; end: SessionSeq }
shadowedSeqs: SessionSeq[]
shadowedTokenCount: number
provider: string
model: string
maxTokens?: number
usage?: TokenUsage
rawOutput?: ContentBlock[]        // llmStreamCall=true 时必有
llmStreamCall?: true
```

### compaction/end（types.d.ts:73-78）
```
compactionId: CompactionId
sourceCommandId?: CommandId
turn: number | null
error?: string            // 失败尝试的记录
```

### compaction/prune（types.d.ts:88-98；无摘要的纯裁剪）
```
shadowedRange: { start: SessionSeq; end: SessionSeq }
shadowedSeqs: SessionSeq[]
shadowedTokenCount: number
```

### 替换 user/message（紧随 compaction/summary 之后，history.js / compaction-basic/lib/index.js:650-661）
```
type: 'user/message'
data.source: { kind: 'compact-checkpoint', compactionId, sourceCommandId? }
meta.surfaceOp: { op: 'replace', startSeq, endSeq }
meta.sourceEventSeqs: [startSeq, summarySeq, ...shadowedSeqs]
```

---

## 6. 给 oc-beacon 的修复方向（一句话）

用 `compaction/summary.shadowedSeqs / shadowedRange`（surface 位置）而非信封 seq 决定压缩卡的渲染位置；把被 shadow 的消息集合折叠到卡下；替换 user message（`source.kind === 'compact-checkpoint'`）作为卡片正文。这样卡片自然"钉"在正确的历史位置并随后续消息上推。
