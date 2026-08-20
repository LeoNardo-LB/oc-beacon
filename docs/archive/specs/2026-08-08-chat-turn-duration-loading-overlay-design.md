# Chat：turn 级时长统计 + 统一加载蒙版

日期：2026-08-08
状态：已批准（用户确认"1、2都可以"）

## 背景

两个 UI/UX 问题：

1. **SSE 输出过程中统计栏数字重置**：一个 agent 回复气泡（turn）内出现多条 assistant 消息时，页脚统计栏的耗时从新消息的 created 重新起算，看起来"重置"。用户想要统计**整个 turn**（agent 回复气泡）的时长。
2. **进入会话闪一下 + 布局上抬**：点击进入已有会话时，只有内容区受 loading 控制（中央 PulsingDots）；输入栏的 agent/模型标签依赖 providers 网络加载 + 消息历史解析，完成后输入栏布局变化导致主对话流整体上抬。用户想要**一个统一 loading 蒙版**遮住消息区 + 输入栏，等完全加载完成后一起展示。

## 设计 1：turn 级时长统计

### 根因

- `computeRenderableTurn`（`ui/screens/chat/tools/RenderableTurn.kt:76-79`）：`durationMs` 取**当前消息**（turn 代表消息）自身的 `created→completed`——注释明确"来自当前消息自身的时间，而非 turn 跨度"。
- `MessageCardAssistant` 流式 ticker（`components/MessageCardAssistant.kt:165`）：`nowMs - assistantMsg.time.created`——同样取当前消息的 created。
- turn 分组语义（`util/TurnGroupCalculator.kt`）：turn = 两条 user 消息之间的连续 assistant 消息序列。turn 代表消息切换时（新 assistant 消息出现），计时重置。

### 改动

1. **`RenderableTurn` 新增字段 `turnStartMs: Long?`** = turn 内首条 assistant 消息的 `time.created`。

2. **`computeRenderableTurn`**（`tools/RenderableTurn.kt`）：
   - `turnStartMs = turn 内所有 assistant 消息的 minOf(created)`（turn 分组只含 assistant，用 `minOf` 比较时间戳最稳）。
   - `durationMs`（完成态）= `maxOf(completed) - turnStartMs`——**仅当 turn 内所有 assistant 消息的 completed 均非空**时才给值；任一仍流式则保持 `null`（由流式 ticker 接管）。

3. **`MessageCardAssistant`**（`components/MessageCardAssistant.kt`）：
   - 流式 ticker 起点改为 `turnStartMs ?: 当前消息 created`（fallback 保留，兼容 turnStartMs 为空的理论情况）。
   - 完成态显示 `durationMs`（turn 级）。
   - 显示位置不变：统计栏仍渲染在 turn 末条（最新）assistant 气泡页脚。

### 效果

一个 agent 回复气泡（含多轮工具调用产生的多条 assistant 消息）从第一条消息出现起连续计时，新消息出现**不重置**；完成后显示整个 turn 的总时长（含 reasoning）。

### 边界

- turn 内第一条 assistant 的 created 即为计时起点——即使首条是"空气泡"（无 parts）也如此。
- 流式判定（`isStreamingTurn`）已是 turn 级，无需改动。

## 设计 2：统一加载蒙版

### 根因

- ChatScreen 只有内容区受 `interaction.isLoading` 控制（`ChatScreen.kt:656` 中央 PulsingDots），TopBar 与输入栏（bottomBar）照常渲染。
- 输入栏的 agent/模型标签来自 `modelConfigState`（12 路 combine，`ModelConfigDelegate.kt:84`），依赖 `loadProviders()`（网络拉 provider catalog）+ 消息加载（从最后一条 user 消息解析模型/agent）。加载完成后标签出现 → 输入栏布局变化 → 主对话流整体上抬。
- `ModelConfigState` 当前**没有**加载完成信号（`ChatUiState.isLoading` 是旧聚合状态，非拆分状态）。

### 改动

1. **`ModelConfigDelegate` 新增 `isLoaded: StateFlow<Boolean>`**：
   - 初始 `false`；`loadProviders()` 完成时置 `true`（**成功或失败都置 true**——失败不阻塞蒙版揭开）。
   - 供 ChatScreen 消费。

2. **ChatScreen 蒙版**（`ChatScreen.kt`）：
   - Scaffold 的 content + bottomBar 外包一层 `Box`，蒙版覆盖在两者之上（TopBar 不盖——返回键随时可用）。
   - 蒙版 = **不透明 Surface（`MaterialTheme.colorScheme.surface`）+ 居中 `PulsingDotsIndicator`**（复用 `ui/components/indicators/PulsingDotsIndicator.kt`）。
   - 蒙版期间输入栏不可交互（被盖住即天然不可交互）。

3. **蒙版显示条件**：
   ```
   modelReady    = modelConfigDelegate.isLoaded
   messagesReady = sessionId 为空（新建会话恒就绪）|| !interaction.isLoading
   overlayVisible = !(modelReady && messagesReady) && !overlayTimeout
   ```

4. **8 秒超时兜底**：`LaunchedEffect` 一次性计时，超时置 `overlayTimeout = true` 强制揭蒙版——此后各区域按自身状态渲染（消息未好显示现有 loading/错误态；模型标签后出现），避免网络慢/失败导致蒙版永久挂着。

### 效果

进入会话 → 消息区 + 输入栏被同一蒙版盖住 → 消息与模型配置**同时就绪**后一次揭开 → 无闪烁、无布局上抬。新建空白会话同样等模型配置就绪（用户确认）。

## 测试计划

1. **单测（JUnit）**：
   - `computeRenderableTurn`：多 assistant 消息 turn 的 `durationMs` = 首条 created → 末条 completed；流式中任一未完成 → `durationMs = null` 且 `turnStartMs` = 首条 created；单消息 turn 行为不变。
   - `ModelConfigDelegate.isLoaded`：初始 false；`loadProviders()` 成功/失败后均 true。
2. **androidTest（Compose UI）**：进入已有会话时蒙版显示；双就绪后蒙版消失且输入栏标签与消息同时可见（可基于现有 `ChatMessageRenderingTest` 模式）。
3. **Maestro 流程**：现有流程回归（进入会话 → 蒙版 → 内容出现 → 发送消息），确认无新阻塞。
4. **验证命令**：`compileDevDebugKotlin`（120s）→ `testDevDebugUnitTest --rerun`（180s）→ 真机冒烟（进入会话观察蒙版 + turn 计时）。

## 影响面

- `tools/RenderableTurn.kt`（+turnStartMs、durationMs 计算）
- `components/MessageCardAssistant.kt`（ticker 起点）
- `ModelConfigDelegate.kt`（+isLoaded）
- `ChatScreen.kt`（蒙版 Box + 条件 + 超时）
- 新增/更新测试
- 无 i18n 文案变更（无新字符串；蒙版复用 PulsingDots，无文字）
- 不触碰 ChatScreen 消息列表渲染、SSE 管线、导航
