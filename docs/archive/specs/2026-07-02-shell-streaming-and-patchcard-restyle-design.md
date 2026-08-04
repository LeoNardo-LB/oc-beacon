# Shell 输出流式显示 + PatchCard 复用聚合样式 设计

- **日期**: 2026-07-02
- **状态**: 已确认，待实施
- **关联**: `2026-06-24-context-tool-aggregation-design.md`（聚合卡片原始设计）

## 1. 背景与问题

两个独立问题，合并在一个 spec 中处理。

### 问题 1：Shell 命令输出不完整

OpenCode TUI 端，Shell 命令运行期间持续输出 stdout/stderr。但在 OC Remote 中，命令运行期间（未结束）只显示入参（`$ command`），看不到任何输出，直到命令结束才一次性出现完整 output。

### 问题 2：PatchCard 样式应复用聚合卡片

每轮结束时的 "N 个文件变更" 摘要卡片（PatchCard）当前使用自建的 `Surface + Row` 布局，与 "读取了 N 个文件" 聚合卡片（ContextToolGroupCard）的轻量行列表样式不一致。应复用聚合卡片的底层组件，并在每行右侧保留 `+N -N` 变更行数统计。

---

## 2. 问题 1：Shell 命令输出持续显示

### 2.1 根因分析

经代码追踪，根因是 **数据通道断裂**，非 UI 渲染问题。完整链条：

1. OpenCode 服务器在工具运行期间，通过 `session.next.tool.progress` 事件持续推送 `content: ToolOutput.Content[]`（即 stdout/stderr，有界更新 cadence）。参见 `docs/opencode-api-reference.md` L3650。
2. OC Remote 的 `SessionNextEvent.ToolProgress`（`domain/model/SessionNextEvent.kt` L155-160）**只解析了 `progress: String?` 字段，丢弃了 `content` 字段**。
3. 即便 progress 被处理，它存入独立的 `_activeToolProgress` 流（`SessionNextEventHandler.kt` L70），**从不触碰 `Part.Tool`**。`ToolCalled`/`ToolSuccess` 在该 handler 中均为 "no state change"（L121/L123）。
4. `Part.Tool`（含 state）走 **message 通道**（`message.part.updated` 整体替换），与 progress 的 **session.next 通道** 分离，两者不交汇。
5. `ToolState.Running`（`domain/model/ToolState.kt` L38-47）**没有 `output` 字段**，只有 `input`/`title`/`metadata`/`time`。
6. `extractToolOutput`（`ui/screens/chat/tools/ToolCardRenderer.kt` L234-240）只从 `Completed.output` / `Error.error` 取值，`Running` 返回 `""`。

结果：Running 期间 `BashToolCard` 拿到的 output 恒为空，只显示入参。

### 2.2 方案：扩展 Running.output + ViewModel 层桥接

采用方案 A：给 `ToolState.Running` 新增 `output` 字段，通过 ViewModel 的 message combine 管道把 progress 的 content 注入到对应 callID 的 Part.Tool。

选择此方案的理由：
- **数据模型语义正确**：output 本就属于 tool 状态。
- **所有工具卡片自动受益**：不只是 BashToolCard，任何读 `extractToolOutput` 的卡片在运行期都能显示输出。
- **桥接放 ViewModel combine 层**：不破坏 handler 职责分离（SessionNextEventHandler 不碰 messages），combine 是 messages 的唯一构建入口。
- **无冲突**：`tool.success` 时服务器通过 message 通道下发权威的 `Completed.output`，自然覆盖本地注入的 Running.output。

### 2.3 数据流

```
SSE: session.next.tool.progress { callID, content[], progress?, title? }
   │
   ① SessionNextEvent.ToolProgress          [新增 content 字段解析]
   │
   ② SessionNextEventHandler.handleToolProgress
      └─ ToolProgressInfo.output 累积 content   [ToolProgressInfo 加 output 字段]
   │
   ③ EventDispatcher.activeToolProgress      [已暴露 L145，无需改]
   │
   ④ ChatViewModel message combine 管道       [桥接：按 callID 找 Part.Tool，
   │                                          若 state=Running 则 copy(output=累积值)]
   │
   ⑤ ToolState.Running                       [新增 output: String = ""]
   │
   ⑥ extractToolOutput                        [加 Running.output 分支]
      └─ BashToolCard + 所有工具卡片自动显示运行期输出
```

### 2.4 改动点

| # | 文件 | 改动 |
|---|------|------|
| ① | `domain/model/SessionNextEvent.kt` L155 | `ToolProgress` data class 新增 `content` 字段解析（结构见 §2.5 待确认项） |
| ② | `data/repository/handler/SessionNextEventHandler.kt` L17-25 | `ToolProgressInfo` 加 `output: String`；`handleToolProgress` (L169) 累积 content 到 output |
| ③ | — | `EventDispatcher.activeToolProgress` 已暴露，无需改 |
| ④ | `ui/screens/chat/ChatViewModel.kt` message combine 管道 | 注入 progress output：按 callID 匹配 Part.Tool，Running 则 copy(output=...) |
| ⑤ | `domain/model/ToolState.kt` L39-47 | `Running` 新增 `val output: String = ""`（带默认值，反序列化兼容） |
| ⑥ | `ui/screens/chat/tools/ToolCardRenderer.kt` L234-240 | `extractToolOutput` 加 `is Running -> s.output` 分支 |

### 2.5 关键决策

- **桥接位置**：ViewModel combine 层，非 handler 层。保持 `SessionNextEventHandler` 只管 session.next 状态、`MessageEventHandler` 只管 message 状态的职责边界。
- **Running.output 为本地增强**：服务器权威输出在 `Completed.output`，success 时通过 message 通道自然覆盖，无状态冲突。
- **累积策略待实测**：`content` 是增量片段（需追加）还是全量快照（需替换）API 文档未明确（仅说 "有界更新"）。实现时抓一条真实 SSE 数据确认。`ToolProgressInfo.output` 默认按增量追加实现；若实测为全量则改为替换（单行差异）。
- **ToolOutput.Content 结构待探测**：API 文档仅给出 `content: ToolOutput.Content[]` 类型名，未展开字段定义。实现第一步用探测脚本抓取真实事件 JSON，确认 `content` 元素结构（text 字段名、是否有 stderr/stdout 区分标识），再定 `SessionNextEvent.ToolProgress` 的反序列化字段。遵循 AGENTS.md "用探测脚本消除模糊" 原则。

### 2.6 截断策略

沿用现有约定：`BashToolCard` 已用 `cleanedOutput.take(5000)`（L57），`ToolCallCard` 用 `output.take(3000)`（L210）。运行期累积的 output 同样走这些截断，不额外引入滑动窗口（YAGNI；超长命令输出在 success 后由服务器 `outputPaths` 落盘机制处理）。

---

## 3. 问题 2：PatchCard 复用聚合卡片样式

### 3.1 现状

- **ContextToolGroupCard**（`tools/ContextToolGroupCard.kt`）：轻量行列表，每行 `Icon(16dp) + Text(labelMedium) + Text(subtitle, weight:1f)`，行间 `HorizontalDivider`（outlineVariant FAINT）。用于 Read/Glob/Grep 聚合。
- **PatchCard**（`tools/cards/PatchCard.kt`）：自建 `Surface(onClick) + Row`，每文件独立带背景圆角卡片，行右侧已有 `DiffChangesInline(+N -N)`（L96-99，数据来自 `LocalSessionDiffs`）。

两者样式割裂。目标：PatchCard 改用聚合卡片同源样式，保留行数统计与点击打开文件。

### 3.2 方案：抽取共享组件 ToolGroupList

从 ContextToolGroupCard 抽取行列表逻辑为通用组件，两个卡片复用。

```kotlin
// 新增文件：ui/screens/chat/tools/ToolGroupList.kt

/** 通用工具组列表行数据 */
data class ToolGroupListItem(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val trailing: @Composable (() -> Unit)? = null,
)

/**
 * 聚合卡片通用行列表：Column { forEach { Divider + Row(icon, label, subtitle weight:1f, trailing) } }
 * 布局迁移自 ContextToolGroupCard L80-119，保持像素级一致。
 */
@Composable
fun ToolGroupList(
    items: List<ToolGroupListItem>,
    onItemClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

### 3.3 改动点

| # | 文件 | 改动 |
|---|------|------|
| 1 | `ui/screens/chat/tools/ToolGroupList.kt` | **新增**：从 ContextToolGroupCard L80-119 抽取行列表 + 分隔线逻辑 |
| 2 | `ui/screens/chat/tools/ContextToolGroupCard.kt` | 改用 `ToolGroupList`，构建 `List<ToolGroupListItem>`（trailing=null）。行为不变，纯重构 |
| 3 | `ui/screens/chat/tools/cards/PatchCard.kt` | 移除自建 `Surface+Row`（L64-103），改用 `ToolGroupList`。每文件行：icon=`Description`，label=`PathUtils.fileName`，subtitle=dirPath（可选），trailing=`DiffChangesInline(additions, deletions)`。保留 `onOpenFile` 点击 |

### 3.4 行数为 0 的处理

`DiffChangesInline`（`tools/DiffHelpers.kt` L42/48）内部已有 `if (additions > 0)` / `if (deletions > 0)` 判断，0 时不渲染。保持现状，无需额外处理。

---

## 4. 测试策略

### 4.1 问题 1 测试

- **单元测试**（`SessionNextEventHandlerTest` / `SessionNextEventHandlerFullTest`）：
  - `ToolProgress` 携带 content 时，`ToolProgressInfo.output` 正确累积。
  - 连续多个 progress 事件的 output 累积正确（覆盖增量/全量两种实现路径）。
- **单元测试**（`SerializationTest`）：`ToolState.Running` 带 output 字段反序列化兼容（旧数据无 output 字段时默认 ""）。
- **单元测试**（`ChatViewModel` 相关）：combine 管道注入 progress output 后，对应 callID 的 Part.Tool.Running.output 正确更新。
- **探测脚本**（实施第一步）：抓取真实 `session.next.tool.progress` 事件 JSON，确认 content 结构，固化为本测试夹具。

### 4.2 问题 2 测试

- **单元测试**（`PatchCardTest` 若有）/ UI 快照：PatchCard 渲染的行布局与 ContextToolGroupCard 同源（Divider + Row 结构）。
- **回归**：ContextToolGroupCard 行为不变（纯重构）。

### 4.3 验证维度（遵循 docs/verification-requirements.md）

- Kotlin 编译：`.\gradlew :app:compileDevDebugKotlin`
- 单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`
- 实机验证：运行一条耗时 Shell 命令（如 `find /`），观察 Running 期间输出持续刷新；触发一次文件编辑，观察 PatchCard 行样式与聚合卡片一致且带行数。

---

## 5. 非目标 (YAGNI)

明确不做：
- stdout/stderr 颜色区分（content 结构未知，且当前无需求信号）。
- 运行期输出的滑动窗口/虚拟滚动（现有 take 截断足够）。
- progress 的 `structured` 字段解析（ToolOutput.Structured，当前无 UI 消费方）。
- Write/Edit 工具的聚合卡片（本次仅改 PatchCard 样式；Write/Edit 聚合是独立议题）。
- ContextToolGroupCard 的功能变更（仅重构为复用 ToolGroupList，行为不变）。

---

## 6. 风险

| 风险 | 缓解 |
|------|------|
| content 结构与预期不符 | 实施第一步探测真实 SSE 数据，固化夹具后再编码 |
| content 为全量非增量 | 累积逻辑单行可切换（追加 ↔ 替换） |
| combine 管道注入增加重组开销 | 按 callID 精确匹配，仅 Running 态注入；progress 有界更新不会高频 |
| Running 加字段影响序列化 | 带默认值 `""`，kotlinx.serialization 向后兼容 |
