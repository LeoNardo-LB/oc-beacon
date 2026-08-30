# 流式 Markdown 增量解析试点（StreamingMarkdownState Pilot，backlog #265）设计 Spec

> 状态：待评审
> 日期：2026-08-30
> 来源：docs/research/2026-08-30-ai-streaming-render-landscape.md（业界调研）+ 同日 §补充（我方管线代码级细化）+ 本 spec 前置的源码级深潜（mikepenz v0.45.0 tag 源码 / PR #575、#591 / 我方 MarkdownContent.kt·MarkdownChunking.kt 实读）
> 关联：backlog #265（本 spec 即其「方案」步产物）、#258、#246、防闪烁铁律（AGENTS.md §SSE 滚动稳定性铁律）

## Problem Statement

**现状流式渲染成本模型**（MarkdownContent.kt L582-591 实证）

流式 turn 的 assistant 正文路径：

```
SSE tokens → 48ms 批处理 flush → Part.Text.text（整串快照）
→ normalizeForRender(整串)（组合期 remember(markdown) 重算，主线程）
→ rememberMarkdownState(content, retainState=true)
   → snapshotFlow{input}.conflate → state.parse()
   → 【主线程 parseBlocking：对整个文档全量重解析】（0.43/0.45 字节码实证：
     parse$2 内联 parseBlocking、无 flowOn；retainState 仅保旧状态防 Loading 闪烁，
     不改变全量重解析本质）
→ Markdown(markdownState=...) 单 LazyItem 全量组合
```

每个 flush 的解析成本 = **O(全文档)**，且发生在**主线程**。文档越长尾帧越贵；与 #258（fling 期新 item 首组合 p95 65ms/p99 129ms）同域叠加。

**业界新事实**（调研 §Q2 + 本 spec 源码实证）：mikepenz 0.42+（我们已在 0.45.0）提供 `StreamingMarkdownState`——增量解析已下沉到解析器层（org.jetbrains:markdown 0.7.9 的 `StreamingMarkdownFile`，维护 `stableChildren` + `unstableTail`），`append()` 只重解析不稳定尾部；**每次 flush 的解析成本 = O(尾部)**。它同时解决「全量重解析」与「主线程」两个问题——前提是三个管线耦合问题有解（见设计）。

## Goals / Non-Goals

**Goals**

1. 流式 turn 的 text part 渲染切到 `StreamingMarkdownState`：flush 解析从「主线程全量重解析」变为「尾部分析」，客观测量流式期主线程帧成本下降。
2. 三个管线耦合（追溯归一化 / blockRange 切片 / 完结态交接）全部有明确答案，不回归任何一条防闪烁铁律。
3. 完成态渲染路径（preParsedState/blockRange/锚点）**一行不改**。
4. 特性开关可一键回退，dev flavor 先行 A/B。

**Non-Goals**

- 不动 SSE 传输层与 48ms 批处理（flush 节奏不变）。
- 不动高度补偿、reverseLayout、autoScroll 双 key 自愈。
- 不动历史消息/非流式消息的任何路径（`rememberAsyncMarkdownState` fallback 保留）。
- 不引入 token typewriter 表现层动画（调研结论：与 48ms 批处理叠加会双重动画，另行立项）。

## API 实证结论（v0.45.0 tag 源码核对）

`model/StreamingMarkdownState.kt`：

- `rememberStreamingMarkdownState(lookupLinks=true, flavour, referenceLinkHandler)`：组合工厂；`Flow<String>.collectAsStreamingMarkdownState()` 一步收集。
- `StreamingMarkdownState`：
  - `content: CharSequence`（内部 StringBuilder，append-only）
  - `snapshot: StateFlow<Snapshot>`；`Snapshot(stableAst: List<ASTNode>, unstableAstTail: List<ASTNode>)` —— **稳定块/不稳定尾分开暴露**，ASTNode 带 startOffset/endOffset（chunk plan 的原料）
  - `links` + `referenceLinkHandler`
  - `suspend fun append(chunk: String): Snapshot` —— **suspend + Mutex**，append-only，源序追加
- 解析下沉：`append()` 内部调 `org.intellij.markdown.parser.StreamingMarkdownFile.append(chunk)`（解析器 0.7.9 新能力），稳定子节点实例跨 append 复用（→ 我方组件层 `remember(model.node, ...)` 缓存对稳定块天然命中）。
- 渲染侧：`m3/Markdown.kt` L164 **存在 `Markdown(streamingMarkdownState=...)` 重载**（我们用的正是 m3 模块）；默认 success 槽按序渲染 `stableAst + unstableAstTail`；PR #575 自报基准：1K 文档分片流式，每 chunk 重渲染 ~1ms。
- `compose/LazyMarkdown.kt` 的 `LazyMarkdownSuccess(streamingMarkdownState, ...)` 为独立 LazyColumn 虚拟化渲染——**不采用**（内嵌 LazyColumn 不能进同向聊天列表；MarkdownChunking.kt L16-18 已有同结论）。我们自渲染、消费 `snapshot`。

## 设计

### 1. 接入缝：不动 SSE 管线，组合层做「前缀差分 append」

Part.Text.text 仍以整串快照到达（48ms flush 产物）。新增包装（建议放 `ui/screens/chat/markdown/StreamingMarkdownState.kt`）：

```kotlin
@Composable
fun rememberPilotStreamingMarkdownState(markdown: String): StreamingMarkdownState? {
    var resetKey by remember { mutableStateOf(0) }
    val state = key(resetKey) { rememberStreamingMarkdownState() }
    var prev: CharSequence? by remember { mutableStateOf(null) }
    LaunchedEffect(markdown, state) {
        val p = prev
        when {
            p == null || !markdown.startsWith(p) -> {
                // 首帧 / 非前缀（重生成/编辑/归一化抖动）：整体重建
                resetKey++
            }
            markdown.length > p.length -> state.append(markdown.substring(p.length))
            else -> Unit // 等长：无增量
        }
        prev = markdown
    }
    return state
}
```

要点：

- **append 在主线程**（LaunchedEffect 协程），与组合同线程 → `content: StringBuilder` 无跨线程竞态（库官方姿势同此）；解析成本 = 尾部，PR #575 基准 ~1ms 量级，48ms flush × conflate 足以摊平。**不**为挪后台而引入 StringBuilder 跨线程读写。
- 前缀差分天然覆盖「重生成/编辑」→ 非前缀即 `resetKey++` 重建（`key()` 重建 state 实例）。
- `startsWith` 主线程成本 O(prevLen) 字符比较，10 万字符 ≈ 0.1ms 量级，可接受；实现时加长度短路。
- delta 未经 `normalizeForRender`（见 §2）——append 原始增量。

### 2. 冲突①（追溯归一化 vs append-only）：流中放弃归一化，完结态接管

现状两个归一化都是「事后改写已流出前缀」，与 append-only 硬冲突。裁决：**流中不归一化（append 原始 delta），完结时由既有完结路径归一化**。

- `splitOversizedParagraphs`（3000 字符阈值）：流中最坏 = 单 MarkdownText 大段落（无分片行距）——**不劣于今天的主线程全量解析路径**；完结时归一化+chunk 接管，高度跳变由既有完结态交接 + 高度补偿吸收（列入 V6 人工验证项：巨型清单消息完结瞬间无肉眼跳变）。
- `TABLE_AFTER_TEXT_REGEX` / `normalizeTaskListMarkers`：仅视觉细节，完结即修正。Phase 2 可选优化：任务列表标记行内改写（局部安全）；表格补行需 2 行 lookahead，可用「尾端 2 行扣留缓冲」实现——本期不做。
- `retainState` 防闪烁语义由库内状态天然承担（AST 前缀保留，无 Loading 态闪跳）。

### 3. 冲突②（blockRange 切片 vs 单状态渲染）：流中本来就不切片，完结态照旧

blockRange/锚点切片**只作用于完结长消息**（`preParsedState != null` 分支，MarkdownContent.kt L547-576）；流式消息今天就是单 LazyItem 单 `Markdown()`。因此：

- 流中：单状态渲染（库默认 success 槽 Column 序渲染 stable+unstable）——组合拓扑与今天一致，仅解析成本下降。
- 完结：`isStreaming` 翻转 → readinessRegistry 预解析 → `preParsedAssistantState` → 既有 `Markdown(state=..., blockRange=...)` 路径，`StreamingMarkdownState` 实例丢弃。**零改动**。
- `snapshot.stableAst` 的 ASTNode（含 offset）为未来「流中早期切片」保留可能，本期不做。

### 4. 渲染接线（MarkdownContent.kt 新分支）

L582-591 的流式分支改为三分支：`asyncParse`（不变）/ **pilot**（`StreamingMarkdownPilot.enabled && !isUser` → `rememberPilotStreamingMarkdownState`）/ legacy（现路径：normalize + `rememberMarkdownState(retainState=true)`，保持为回退）。

渲染分流：pilot 状态走 `Markdown(streamingMarkdownState = ...)`（m3 重载，`colors/typography/components/padding/animations/imageTransformer` 全部照传——components 闭包复用 L436 的 remember）；legacy 走现 `Markdown(markdownState=...)`。

**组件层缓存审计（实施必查）**：我方 components 内 `remember(model.content, model.node, ...)` 以 content 身份为键——append-only 下 content 是**同一个 StringBuilder 实例**，键永不失效。对稳定块这是**收益**（节点实例复用 + offset 不变 → 缓存恒正确）；对不稳定尾节点需实证「每次 append 是否产生新 ASTNode 实例」（是 → 缓存正常失效；否 → 尾部文本可能停留旧缓存）。验收含专项走查：流中打字期标题/段落/行内码颜色实时性。

### 5. 开关与灰度

- 单文件常量 `StreamingMarkdownPilot.enabled`（`dev=true`，`beta/stable=false`），不开 runtime 设置项（试点期避免双路径长期共存）。
- 回退 = 置 false 一行，或 revert 该分支 commit。

## 铁律影响分析（逐条）

| 铁律 | 影响 |
|---|---|
| `Markdown()` 必须 remember 状态、禁 stateless `Markdown(content=...)` | **满足且增强**：streaming 重载同样是 remembered 状态；试点合入后本条铁律文案需补「流式路径用 StreamingMarkdownState」（文档任务） |
| `scheduleFlush()` 不得取消进行中定时器 | 不触碰（上游不动） |
| `layout{}` 高度补偿只作用于流式 turn | 不触碰；新增验证：补偿与新渲染路径共存无抖动（V6） |
| autoScroll `LaunchedEffect` 双 key | 不触碰 |

## 验收标准

**V2 性能（真机 houji，devRelease）**

1. 流式 flush 期主线程：新路径 vs 现路径 framestats 对照——长消息（≥8K 字符）流式后期，解析/重组相关切片均值下降（预期量级：全量→尾部，≥50%）；无新增 >16ms 巨帧。
2. #258 场景抽测：fling 期流式 item 首组合 p95/p99 对照（预期改善或持平，不回归）。
3. PR #575 基准复算：追加 1K 文档 10 chunk，单 chunk 端到端 <5ms（真机）。

**V1/V5 功能正确性**

4. 流式全程渲染内容与最终 DB 文本一致（完成态 vs 流式末帧 diff 无丢字/重字）。
5. 非前缀重建：流中触发重生成/编辑，渲染无残留旧内容。
6. 完结态交接：巨型清单消息（>3000 字符段落、含表格/任务列表/引用链接）完结后归一化+分片渲染正确，锚点重定位无头片丢失（#246 回归项）。

**V6 人工验证（时间性现象，必做）**

7. 流式打字期无闪烁/高度振荡（对照现路径主观无劣化）。
8. 完结瞬间（归一化+切片接管）高度跳变不可感知（高度补偿覆盖）。
9. 流中标题/行内码/链接颜色实时正确（组件缓存审计项的可视确认）。
10. 超长消息流式后期滚动、fling、贴底自愈手感无回归。

## 实施阶段

1. **P0-a**：包装 `rememberPilotStreamingMarkdownState` + MarkdownContent 三分支 + 开关（dev 默认开）。
2. **P0-b**：组件缓存审计（`remember(model.content, ...)` 键语义）与必要修正。
3. **P1**：真机 A/B（V2 全套）+ V6 人工清单。
4. **P2（达标后）**：beta/stable 放开开关；更新防闪烁铁律文案；backlog #265 关单（证据链入 journal）。
5. **P3（可选）**：Phase 2 归一化前移（行内任务标记改写、表格 2 行扣留缓冲）；评估 `snapshot.stableAst` 驱动的流中早期切片。

## 实施期待验证问题

1. `StreamingMarkdownFile` 的「稳定块」判定边界（何时从 unstableTail 移入 stableAst）——解析器源码在预期路径 404（JetBrains/intellij-markdown 仓库结构待查）；试点用日志实证：逐 append 记录 `stableAst.size / unstableAstTail.size` 增长曲线。
2. `org.jetbrains:markdown` 依赖解析须为 0.7.9（mikepenz 0.45.0 POM runtime 依赖；我方未钉版）——实施时跑 `:app:dependencyInsight --dependency org.jetbrains:markdown` 确认（本 spec 阶段禁跑 gradle）。
3. 不稳定尾 ASTNode 实例是否跨 append 复用（决定组件层缓存策略，见 §4 审计项）。
4. `lookupLinks=true` 每 append 的链接扫描成本；如显著，流式期降级为 false + 完结态接管（完结路径已写 referenceLinkHandler）。

## 参考

- 调研主文档：docs/research/2026-08-30-ai-streaming-render-landscape.md（§Q2 / §补充 / 附录 A 数据快照）
- 库源码（v0.45.0 tag）：model/StreamingMarkdownState.kt · compose/Markdown.kt（L233-260 streaming 重载、L394-419 默认槽）· compose/LazyMarkdown.kt · m3/Markdown.kt（L164）· model/MarkdownState.kt（snapshotFlow+conflate，主线程 parseBlocking）
- PR：mikepenz/multiplatform-markdown-renderer#575（StreamingMarkdownState，基准 1ms/chunk）、#591（同步 parseMarkdown）
- 我方代码：MarkdownContent.kt（L547 分支结构、L582-591 流式路径、L436 components、L616 AsyncMarkdownStateImpl）· MarkdownChunking.kt（L8-22 根因取证、L50-95 computeChunkPlan）· MessageCardAssistant.kt（L341-367 预解析消费与 isStreaming 分流）
- 官方 README §Streaming：https://github.com/mikepenz/multiplatform-markdown-renderer#streaming