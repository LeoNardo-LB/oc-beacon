# 聊天界面信息密度增强

- **日期**: 2026-06-18
- **状态**: Draft（待用户审查）
- **主题**: 工具调用聚合 + 上下文用量弹窗增强 + 顶部栏/气泡配套调整

---

## 1. 背景与目标

参考 opencode webui（`anomalyco/opencode`，原 `sst/opencode`，TypeScript）的两项信息展示能力，增强 OC Remote 聊天界面：

1. **工具聚合**：把主对话流里连续出现的"上下文探查"类工具（read/glob/grep/list）合并成一张可展开卡片，折叠态显示"已收集上下文 · N次读取 · N次搜索"，展开看每项详情。
2. **弹窗增强**：顶部栏 token 环形进度条点击后弹出的详情对话框，补充上下文按角色拆分、消息计数、provider/model、时间戳、缓存命中率等。
3. **配套调整**：信息转移到弹窗后，顶部栏副标题改为显示当前对话目录；消息气泡 token 用 compact 格式。

**非目标（YAGNI）**：不做精确 per-role token（服务端不下发，只能估算）；不引入 domain 层分组模型；不重构现有 ToolCard 组件；不改顶部栏环形进度条本身。

---

## 2. 参考实现与现有基础

### opencode webui 源码（已分析）

| 能力 | 文件 | 关键符号 |
|------|------|---------|
| 聚合算法 | `packages/ui/src/components/message-part.tsx` | `groupParts` / `CONTEXT_GROUP_TOOLS = {read,glob,grep,list}` / `contextToolSummary` / `AnimatedCountList` |
| 弹窗计算 | `packages/app/src/components/session/session-context-breakdown.ts` | `estimateSessionContextBreakdown`（chars/4 估算） |
| 弹窗指标 | `packages/app/src/components/session/session-context-metrics.ts` | `estimateSessionContextMetrics`（取最后 assistant msg 的 tokens） |

opencode 的聚合是**跨多条 assistant message** 的：`AssistantParts` 接收一个 turn 内的所有 assistant message，`messages.flatMap(...)` 拍平 parts 后再 `groupParts`。

### OC Remote 已有的对齐基础

| 能力 | 现有实现 | 对齐 opencode |
|------|---------|--------------|
| turn 分组 | `TurnGroupCalculator.computeTurnGroups()` + `AssistantTurnBubble(messages: List<ChatMessage>)` + `MessageCard` 内 `orderedTurnMessages.flatMap { it.parts }` | 等价 `AssistantParts` 的 `messages.flatMap` |
| 工具展开机制 | `AppSettings.collapseTools` + `expandedStates: Map<String,Boolean>` + `LocalCollapseTools` / `LocalToolExpandedStates` / `LocalOnToggleToolExpanded` | 聚合组可直接复用 |
| token 数据源 | `Part.StepFinish.Tokens`(input/output/total/reasoning/cache.{read,write}) + `Message.Assistant`(providerId/modelId) + `Session`(directory/time/...) | 弹窗数据现成 |
| compact 格式 | `ChatFormatters.formatTokenCount()`（已支持 `1.2k`/`1.5M`） | 零新增函数 |

---

## 3. 功能 A：工具调用聚合

### 3.1 数据类型（新增，放 `ui/screens/chat/util/`）

```kotlin
/**
 * 把 parts 列表变成可渲染单元：单个 part 或一组连续探查工具。
 * 对应 opencode 的 PartGroup（type: "part" | "context"）。
 */
sealed class PartRenderUnit {
    data class Single(val part: Part) : PartRenderUnit()
    data class ContextGroup(val tools: List<Part.Tool>) : PartRenderUnit()
}
```

### 3.2 核心算法 `groupContextTools(parts): List<PartRenderUnit>`

纯函数，对应 opencode `groupParts` + `isContextGroupTool`：

```
CONTEXT_TOOLS = setOf("read", "glob", "grep", "list")
用 start/end 指针线性扫描：
  遇探查工具 → 若 start<0 则标记起点，继续
  遇非探查工具 → flush 上一组（start≥0 时打包成 ContextGroup），当前 part 作 Single
  扫描结束 → flush 最后一组
单个探查工具也成组（start 不为 -1 即 flush）
```

**分类计数** `contextToolSummary(tools)`（对应 opencode `contextToolSummary`）：
- `read` = tool=="read" 的数量
- `search` = tool=="glob" || tool=="grep" 的数量
- `list` = tool=="list" 的数量

### 3.3 组件 `ContextToolGroupCard`

```
┌─────────────────────────────────────┐
│ ⊙ 已收集上下文   3次读取·2次搜索  ▾ │  ← 折叠态
└─────────────────────────────────────┘
        ↓ 点击展开
┌─────────────────────────────────────┐
│ ⊙ 已收集上下文   3次读取·2次搜索  ▴ │
│   ┌─ ReadToolCard (默认折叠) ──┐    │  ← 复用现有 ToolCard
│   ├─ ReadToolCard ─────────────┤    │
│   ├─ SearchToolCard ───────────┤    │  ← 各自可独立展开
│   └─ ... ──────────────────────┘    │
└─────────────────────────────────────┘
```

- **展开态复用现有 ToolCard**：通过 `LocalToolCardResolver.current.resolve(...)` 分发到 `ReadToolCard`/`GlobToolCard`/`SearchToolCard` 等（零新工具卡组件）
- **状态文案**：组内有 `ToolState.Running` → "正在收集上下文"（+加载动画）；全部完成 → "已收集上下文"
- **默认展开**：复用 `LocalCollapseTools`（用户设置）。组展开态存入现有 `expandedStates`，key = `context:${组内首个 tool.id}`，与单工具卡完全一致的交互
- **计数文案**：`AnimatedCountList` 等价——逗号/分隔符串联"N次读取 · N次搜索 · N次列表"（中文无复数；值为 0 的类别不显示）

### 3.4 插入点

定位到 `MessageCard` / `AssistantTurnBubble` 渲染 turn 内 parts 处，在 `orderedTurnMessages.flatMap { it.parts }` **之后**、遍历调 `PartContent` **之前**：

```kotlin
val turnParts: List<Part> = orderedTurnMessages.flatMap { it.parts }
for (unit in groupContextTools(turnParts)) {
    when (unit) {
        is PartRenderUnit.Single -> PartContent(unit.part, ...)
        is PartRenderUnit.ContextGroup -> ContextToolGroupCard(unit.tools, ...)
    }
}
```

精确的渲染函数/行号在实现阶段定位（已知在 ChatScreen 消息渲染链路内，parts 经 `PartContent` 分发）。

### 3.5 范围边界

- **单条 assistant message 内聚合（per-message，实现决策）**：实际实现保留 `AssistantTurnBubble` 的 per-message `errorText` 关联，在每组 `renderableParts` 内独立聚合，而非跨 message flatten。代价：turn 内跨多条 assistant message 边界处的连续探查工具会显示为多张聚合卡（罕见场景——主对话流的 read/glob/grep/list 通常集中在单条 assistant message 内）。如需严格对齐 opencode `AssistantParts` 的跨 message flatten，后续可重构 `allContent` 迭代方式。
- 只聚合 `Part.Tool`；text/reasoning 等不影响（它们不在探查工具流里连续出现）。
- `todoread`/`todowrite` 已在 `PartContent.kt` 按 webui convention 处理（skip / TodoListCard），聚合不介入。

---

## 4. 功能 B：上下文用量弹窗增强

### 4.1 数据流

ViewModel 新增 `ContextDetailState`（**不动**现有 `tokenStatsState`，避免影响顶部栏环形进度条）：

```kotlin
data class ContextDetailState(
    // 已有 token 数据（复用 tokenStatsTracker）
    val inputTokens: Int, val outputTokens: Int, val reasoningTokens: Int,
    val cacheReadTokens: Int, val cacheWriteTokens: Int,
    val totalCost: Double,
    val contextWindow: Int, val contextTokens: Int,
    // 新增
    val messageCount: MessageCount?,        // (user, assistant)
    val providerModel: ProviderModel?,      // 最后 assistant msg 的 providerId/modelId
    val timestamps: SessionTimestamps?,     // Session.time.created/updated
    val cacheHitRate: Float?,               // cacheRead / input
    val breakdown: ContextBreakdown?        // 角色估算
)
```

`ChatViewModel` 用纯函数从 `messages` + `tokenStats` 合成，走 `StateFlow`。弹窗 UI 只渲染 state。

**数据传递**：弹窗（`ContextDetailDialog`）仍位于 `ChatTopBar` 内部，但数据从 `ContextDetailState` 取（`ChatTopBar` 接收 `contextDetail: ContextDetailState?` 参数），不再透传一长串 token 参数。这样 `ChatTopBar` 的 token 相关参数从 ~8 个收敛为 1 个 state 对象。

### 4.2 计算纯函数（放 `ui/screens/chat/util/`，配 JVM 单元测试）

**`estimateContextBreakdown(messages, realInputTokens)`** —— 对应 opencode 同名函数：

```
遍历所有 message.parts 累加字符：
  user message   → userChars    (Part.Text.text / Part.File.source / Part.Agent.source 长度)
  assistant msg  → assistantChars (Part.Text/Reasoning.text) + toolChars (Part.Tool 输出)
tokens = ceil(chars / 4.0).toInt()
percent = tokens / realInputTokens          ← 占真实 input
other  = max(0, realInputTokens - 估算总和) ← 含 system prompt，灰色段
```

OC Remote **没有 system prompt**（server 不下发），故无 system 桶，差额全归"其余"。这与 opencode 不同（opencode 自己是 server，有 system prompt）。

其余 metrics 直接取值：
- `messageCount`：按 `Message.role` 计数
- `cacheHitRate`：`cacheRead / input`
- `providerModel`：最后 assistant msg 的 `providerId/modelId`（优先友好名，回退 ID）
- `timestamps`：`Session.time.created/updated`

### 4.3 UI 布局（`ContextDetailDialog`，重构现有 `ChatTopBar` 内 `BasicAlertDialog`）

```
┌─ 上下文详情 ──────────────────┐
│ Zhipu AI · GLM-5.2            │ ← provider/model
│ 创建 06-14 09:32 · 活动 06-18 │ ← 时间戳
│                               │
│ ▓▓▓▓▓░░░░  16%   158.6k/1M   │ ← 进度条（复用现有）
│                               │
│ 消息 204（用户 34 · 助手 170）│ ← 消息计数
│ 缓存命中 97%                  │ ← 缓存命中率
│                               │
│ ── 上下文构成（估算）──       │ ← breakdown
│ ▪ 用户      1.2k    3.7% ▓▓  │
│ ▪ 助手     20.1k   64.7% ▓▓▓▓│
│ ▪ 工具调用 10.8k   31.7% ▓▓▓ │
│ ▪ 其余      8.4k       · ▓   │ ← 灰色（含 system 等）
│                               │
│ ── Token 明细 ──              │ ← 现有 TokenUsageCard 内容保留
│ 输入 4,270  输出 59  推理 161 │
│ 缓存读 154,112  缓存写 0      │
│ 成本 $0.0000                  │
│                  [关闭]       │
└───────────────────────────────┘
```

- mini 条用 **`LinearProgressIndicator`**（Material3 原生，符合 AGENTS.md "Material3 First"）
- breakdown 小数值用 `formatTokenCount()`（`1.2k` 风格）
- breakdown 标题明确标 **"估算"**，"其余"段注明"含 system prompt 等"，避免误导
- 现有 `TokenUsageCard` 的 token 明细**保留**，降级为其中一个分区
- 各项数据为 null 时优雅回退（provider 拿不到显示 ID 或省略）

---

## 5. 配套调整

> 背景：弹窗承载详细信息后，顶部栏副标题和气泡 token 的简略信息需相应调整，避免重复。

### 5.1 顶部栏副标题 → 当前对话目录

- **现状**：`ChatTopBar` title 副标题 = `消息数 · 158.6k tokens · $0.0000`（L97-118）
- **目标**：显示 `session.directory`
- **数据**：`Session.directory`（L15，现成）。空时副标题整行隐藏
- **改动**（`ChatTopBar` 参数随 Section 4.1 一并收敛）：
  - **新增** `directory: String` 参数；副标题 `Text` 渲染改为 directory（`maxLines=1` + `TextOverflow.Ellipsis`）
  - **移除** `messageCount`（仅副标题使用）
  - **移除** `totalInputTokens / totalOutputTokens / totalReasoningTokens / totalCacheReadTokens / totalCacheWriteTokens / totalCost`（这些进 `ContextDetailState`，弹窗改用 `contextDetail` 参数）
  - **保留** `contextWindow / lastContextTokens`（环形进度条本身仍需要）
  - 删除副标题里的 `messageCount / totalTokens / totalCost` 拼接逻辑
  - `ChatViewModel` 透传 `session.directory`，并暴露 `ContextDetailState` 给 `ChatTopBar` 的 `contextDetail` 参数

### 5.2 气泡 token 用 compact 格式

- **现状**：`MessageCard.kt` L494 `"↑$totalInput ↓$totalOutput"`（直接拼数字，显示 `↑4270 ↓59`）
- **目标**：`"↑${formatTokenCount(totalInput)} ↓${formatTokenCount(totalOutput)}"`（显示 `↑4.3k ↓59`）
- `formatTokenCount` 复用现有（`ChatFormatters.kt`，已支持 k/M），**与弹窗 breakdown 小数值统一**

---

## 6. 测试策略（对齐 AGENTS.md 四维度）

### 6.1 纯函数（JVM 单元测试，重点）

| 函数 | 关键用例 |
|------|---------|
| `groupContextTools(parts)` | 空列表；单个探查工具也成组；连续合并；被 bash/edit 打断则断开；read/glob/grep/list 全识别；非探查工具不聚合 |
| `contextToolSummary(tools)` | read/search(list)/list(glob+grep) 三类计数正确 |
| `estimateContextBreakdown(msgs, input)` | 各角色字符累加；chars/4 换算；`other = input - estimated`；估算超 input 时 `other=0`（`coerceAtLeast`）；百分比 |
| `messageCount` / `cacheHitRate` | 计数 / 比率 |

参考现有 `ContextUsageBarTest`、`PartRenderLogicTest` 写法。

### 6.2 Compose UI 测试（androidTest）

- `ContextToolGroupCard`：折叠态计数文案、展开态渲染 ToolCard 列表、点击切换跟 `collapseTools`
- `ContextDetailDialog`：各分区渲染、provider/model 为 null 时回退、breakdown 段为 0 时过滤

### 6.3 Maestro 流程

顶部栏环形进度条 → 点击 → 弹窗展示完整信息；长会话里多组探查工具折叠/展开。

---

## 7. 本地化（15 locales，`lokit.yaml` 同步）

新增约 10 条字符串，语义对齐 opencode i18n：

- 聚合卡：`chat_context_gathering`（正在收集上下文）/ `chat_context_gathered`（已收集上下文）/ `chat_context_count_read|search|list`（`%d 次读取/搜索/列表`，中文无复数）
- 弹窗：`chat_context_composition`（上下文构成）/ `chat_context_estimated`（估算标注）/ `chat_role_user|assistant|tool|other` / `chat_context_other_note`（含 system 等）/ `chat_context_msg_summary` / `chat_context_cache_hit`

改完跑 `lokit` 同步翻译。

---

## 8. 风险与边界

| 风险 | 缓解 |
|------|------|
| breakdown `chars/4` 粗略，中文/符号偏差大 | UI 明确标"估算"，不假装精确 |
| 大 session（几千 parts）字符统计性能 | `remember(messages)` 缓存，仅消息变化时重算；不放在每帧重组路径 |
| 跨 message 聚合依赖 `computeTurnGroups` 正确性 | 聚合不判断 turn 边界，复用现有计算；现有 turn 逻辑有测试覆盖 |
| provider/model 友好名拿不到 | 回退显示 ID，不报错 |

---

## 9. 范围总结

**做**：
1. 工具聚合（`groupContextTools` + `ContextToolGroupCard` + 插入 `MessageCard` turn parts flatMap 后）
2. 弹窗增强（`ContextDetailState` + `estimateContextBreakdown` 等纯函数 + `ContextDetailDialog`）
3. 配套：副标题改 `session.directory` + 气泡 token 套 `formatTokenCount`

**不做（YAGNI）**：
- 不引入 domain 层 `PartGroup` 模型（聚合是纯展示变形）
- 不做精确 per-role token（服务端不下发）
- 不重构现有 ToolCard 组件（复用）
- 不改顶部栏环形进度条本身（只改它点击后的弹窗内容 + 副标题）

---

## 10. 关键决策记录

| 决策点 | 结论 | 理由 |
|--------|------|------|
| Scope | 合并一份 spec，两个独立 section | 主题相关（信息密度），但落点/数据流独立 |
| 聚合规则 | 完全复刻 opencode：read/glob/grep/list 连续即合并，单个也折叠 | opencode 已验证体验 |
| 聚合展开态 | 复用现有 ToolCard | 零新组件、各 ToolCard 本身可折叠 |
| 聚合范围 | turn 内跨多条 assistant message | 对齐 opencode `AssistantParts`；OC Remote 已有 turn 分组基础 |
| 聚合默认展开 | 复用 `collapseTools` 设置 | 用户已有"保持折叠/展开"设置，零新增 |
| 弹窗内容 | breakdown + 消息计数 + provider/model + 时间戳 + 缓存命中率 | 对齐 opencode 图2 |
| 拆分图表呈现 | 纵向角色列表 + mini条 | 移动端最易读 |
| breakdown 口径 | 占真实 input + "其余（system 等）"灰色段 | OC Remote 无 system prompt，差额归"其余"，诚实展示 |
| 副标题 | 改显示 `session.directory` | token/消息信息已转移弹窗，副标题释放给更有用的目录 |
| 气泡 token | `formatTokenCount` compact 格式 | 与弹窗统一视觉 |
