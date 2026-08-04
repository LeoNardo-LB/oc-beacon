# 代码库拆分优化设计

> 日期: 2026-06-27
> 状态: 已批准，待写实施计划
> 范围: 三层全覆盖（UI + Data + Domain）

---

## 1. 概述

### 1.1 问题诊断

通过文件规模、圈复杂度、知识图谱聚类、职责域四维度分析，识别出核心问题集中在 UI 层的 God Class / God Composable：

| 文件 | 行数 | 圈复杂度 | 认知复杂度 | 核心问题 |
|------|------|---------|-----------|---------|
| ChatViewModel.kt | 2451 | — | — | 10 职责域、55 方法、13 依赖 |
| ChatScreen.kt | 1123 | 84 | **203** | God Composable，全项目最复杂 |
| OpenCodeApi.kt | 988 | — | — | 62 端点单文件 |
| PartContent.kt | 685 | — | — | question 解析逻辑混入 UI |
| ChatInputBar.kt | 561 | 32 | 57 | prompt 构建/命令逻辑混入 UI |
| MessageEventHandler.kt | 496 | — | — | 配合 SSE 重写 |
| SettingsScreen.kt | 663 | — | — | 多分区堆叠在单 Composable |
| CodeSourceView.kt | 414 | — | — | 4 个纯逻辑函数混入 UI |

底层架构（domain/data/service）聚类内聚度健康（错误处理 cohesion 0.97，Part 处理 0.74），Domain 层最大文件仅 264 行。**问题集中在 UI 层的实现膨胀，不在架构选型。**

### 1.2 关键决策

| 维度 | 决策 | 理由 |
|------|------|------|
| 覆盖范围 | 三层全覆盖 | 彻底解决，不留尾巴 |
| 实施节奏 | 方案 C 激进重写 | 一次到位，避免混合状态 |
| 测试策略 | 重写测试 | 旧测试作"行为清单"保留 reference，新测试覆盖相同行为点 |
| 架构范式 | 保持 MVVM + Clean | 问题在实现不在选型，不引入 MVI/Reducer |

---

## 2. 设计原则

### 2.1 核心理念

> **按"职责是否过载"判断，不按"行数"判断。**
>
> 行数是信号不是标准。一个 540 行职责单一的 DataStore 比拆成 4 个 135 行的 Serializer 更易读。delegate 只用于"职责域差异巨大且交互少"。

### 2.2 SOLID 映射

| 原则 | 当前违反 | 重写目标 |
|------|---------|---------|
| **S** 单一职责 | ChatViewModel 管 10 个域 | 按职责域拆 delegate |
| **O** 开闭原则 | EventHandler 大 when 分发 | Strategy + 注册表，新增 handler 不改分发器 |
| **I** 接口隔离 | OpenCodeApi 62 方法大接口 | 按域拆小接口，消费者只依赖所需域 |
| **D** 依赖倒置 | ViewModel 直接依赖 Impl | ViewModel 依赖 delegate 抽象 |

### 2.3 设计模式选型

| 模式 | 应用 |
|------|------|
| Delegate（组合优于继承） | ChatViewModel 拆域 delegate |
| Strategy + Registry | SSE EventHandler 分发 |
| State Holder | ChatScreen 状态提取 |
| Slot API | Composable 解耦内容与布局 |

### 2.4 UI 与业务逻辑分离

多个 UI 文件混入了纯逻辑函数，重写时统一提取到 `domain/util`：
- PartContent 的 question 解析 → `QuestionParser`
- ChatInputBar 的 prompt 构建/命令定义 → `PromptBuilder` / `SlashCommandRegistry`
- CodeSourceView 的高亮构建 → `HighlightBuilder`

---

## 3. 拆分方案

### 3.1 第一梯队：必须拆（6 文件）

#### 3.1.1 ChatViewModel.kt (2451L) → delegate 化

ChatViewModel 是全项目最严重的 God Class。当前 10 个职责域：

| 职责域 | 方法 | StateFlow |
|--------|------|-----------|
| 会话生命周期 | loadMessages, refreshSession, loadOlderMessages, syncSessionStatus | isLoading, isRefreshing, hasOlderMessages |
| 消息发送 | sendMessage ×2 | isSending |
| 消息编辑 | deleteMessage, undoMessage, revertMessage, redoMessage, deleteMessagePart | pendingMessageIds, restoredDraft |
| 会话操作 | forkSession, shareSession, compactSession, exportSession, abortSession, renameSession, executeCommand | — |
| 权限/问题 | replyToPermission, replyToQuestion, rejectQuestion, savePermissionRule | — |
| Agent/Model | selectAgent, cycleVariant, selectModel | allProviders, selectedProviderId, selectedModelId, agents, commands |
| 草稿 | updateDraftText, addDraftAttachment, clearDraft, consumeRestoredDraft | draftText, draftAttachmentUris |
| 文件搜索/提及 | searchFilesForMention, confirmFilePath, removeFilePath, clearFileSearch | fileSearchResults, confirmedFilePaths |
| 终端 (11 方法) | runShellCommand, createTerminalTab, switchTerminalTab, resizeTerminal... | terminalTabs, activeTerminalTabId, terminalConnected... |
| UI 状态 | toggleToolExpanded, saveScrollPosition, cacheToolPart | toolExpandedStates, scrollRestore |

**拆分目标**：ChatViewModel 变为薄编排者，组合若干 delegate。每个 delegate 持有各自域的 StateFlow 和方法。

delegate 数量按实际耦合度在实施时确定（预估 5-7 个），不预设固定数量。候选 delegate：
- `SessionLifecycleDelegate` — 会话加载/刷新/分页
- `MessagingDelegate` — 发送 + 消息编辑（撤销/重做/删除）
- `SessionOpsDelegate` — fork/share/compact/export/abort
- `PermissionDelegate` — 权限 + 问题回复
- `TerminalDelegate` — 终端管理（11 方法，独立性强）
- `DraftDelegate` — 草稿 + 文件搜索/提及
- `ModelSelectDelegate` — Agent/Model/Provider 选择

ChatViewModel 聚合各 delegate 的 StateFlow，暴露统一 uiState。ViewModel 自身只剩构造 + 编排逻辑，目标 < 300 行。

#### 3.1.2 ChatScreen.kt (1123L, 认知复杂度 203) → 子 Composable + State Holder

拆分方向：
- 状态变量聚合为 `rememberChatScreenState()` holder
- 子区域提取为独立 Composable（TopBar / MessageList / InputBar / TerminalOverlay 等）
- ChatScreen 变为纯编排（Scaffold + 组装子组件）

> 注意 ChatScreen 编辑协议（docs/chatscreen-editing-protocol.md）和 SSE 滚动稳定性规则（AGENTS.md），重写时严格遵守。

#### 3.1.3 OpenCodeApi.kt (988L) → 按高内聚域拆分

62 端点按高内聚域拆分，合并方法数少的相邻域。目标 4-6 个文件，每个 100-220 行。每个文件含 interface + impl（Kotlin 同文件风格）。

候选域分组（实施时最终确定边界）：
- **SessionApi** — 会话 CRUD + 操作（~20 方法）
- **MessageApi** — 消息 + 权限/问题（~12 方法）
- **TerminalApi** — PTY + Shell（~6 方法）
- **ProviderApi** — 提供商/认证/配置（~13 方法）
- **FileApi** — 文件/VCS/系统（~20 方法）

删除 OpenCodeApi.kt，不保留 Facade。消费者（Repository/UseCase）直接依赖所需域 interface。Hilt DI 为每个 interface 绑定 impl。

方法保持 `suspend fun xxx(conn: ServerConnection, ...)` 无状态风格。

#### 3.1.4 PartContent.kt (685L) → 提取 QuestionParser

Part 类型渲染分发（when 分支）保留在 Composable。提取的纯逻辑：
- `parseQuestionContent(raw)` → `QuestionParser`
- `parseQuestionFromToolData(...)` → `QuestionParser`

QuestionPagerView / QuestionExpandedOptions / QuestionOptionRows 提取为独立 Composable 文件。

PartContent 缩为纯渲染分发，目标 < 300 行。

#### 3.1.5 ChatInputBar.kt (561L) → 提取业务逻辑

提取的纯逻辑：
- `clientCommands()` → `SlashCommandRegistry`
- `buildPromptParts(...)` → `PromptBuilder`

Composable 可选拆分：InputField + AttachmentBar + MentionSearch（若耦合度高则保持一体）。

#### 3.1.6 MessageEventHandler.kt (496L) → 配合 SSE 注册表重写

配合 SSE EventDispatcher 从 `when(eventType)` 改为 `Map<EventType, Handler>` 注册表。MessageEventHandler 按消息子事件拆分（PartUpdated / MessageUpdated / MessageRemoved），新增事件类型 = 新增 Handler + 注册，不改分发器。

---

### 3.2 第二梯队：已评估确认拆分（2 文件）

#### 3.2.1 CodeSourceView.kt (414L) → 提取 HighlightBuilder

4 个纯逻辑函数（不依赖 Composable 上下文）提取到 `HighlightBuilder`：
- `buildHighlights`
- `buildAnnotatedStringFromHighlights`
- `rememberLanguage`
- `buildAnnotatedLineWithAnnotations`

提取后 CodeSourceView 缩为 ~200 行纯渲染，HighlightBuilder 可独立单测。

#### 3.2.2 SettingsScreen.kt (663L) → 按 Section 拆 Composable

设置分区天然独立。按 SectionHeader 边界拆为独立 Composable：
- `GeneralSection` / `AppearanceSection` / `ChatDisplaySection` / ...

SettingsScreen 变为纯编排（组装各 Section + 弹窗状态），目标 ~100 行。新增设置分区只加新文件。

---

### 3.3 不拆分清单（18 文件）

以下文件经评估保持不动。理由：职责单一、已内聚、大小可接受、或拆分成本 > 收益。

| 文件 | 行 | 不拆理由 |
|------|---|---------|
| HomeViewModel.kt | 779 | 两域共享 HomeUiState，setLocalXxx 为机械转发，delegate 成本 > 收益 |
| SessionListViewModel.kt | 702 | 会话树单一职责内聚，MCP 仅 2 方法不值得提 delegate |
| MessageCard.kt | 618 | 已分 User/Assistant，再拆两文件收益微小 |
| ChatTerminalView.kt | 584 | 状态密集但内聚，副作用无法干净提取 |
| SessionListScreen.kt | 566 | 职责单一 |
| NavGraph.kt | 555 | 导航图本质是集中定义，拆了反而增加跳转 |
| SettingsDataStore.kt | 544 | 线性读写逻辑，拆 Serializer 增加样板 |
| ChatMessageList.kt | 536 | 内聚 |
| FileViewerScreen.kt | 507 | 已拆 9 子 Composable |
| ServerProvidersScreen.kt | 505 | 职责单一 |
| HomeScreen.kt | 451 | 职责单一 |
| AppNotificationManager.kt | 442 | 渠道 + 通知内聚于通知管理 |
| ServerTerminalWorkspace.kt | 442 | 职责单一 |
| ServerSettingsViewModel.kt | 419 | 中等大小可接受 |
| OpenCodeConnectionService.kt | 393 | Service 协调者角色，连接逻辑已在 SseConnectionManager |
| ChatRepositoryImpl.kt | 388 | 配合 delegate 化自然瘦身 |
| OpenProjectDialog.kt | 344 | 单一职责对话框 |
| LocalLaunchOptionsDialog.kt | 342 | 单一职责对话框 |

---

## 4. Domain 层

健康（最大 264 行），**不拆分**。重写时仅做 UseCase 归类整理（确保命名一致、按域分包）。

---

## 5. 测试策略

### 5.1 旧测试作为行为清单

旧测试文件保留在 `_legacy/` 目录作 reference。重写时逐条核对旧测试覆盖的行为点，新测试至少覆盖相同场景。

关键行为清单（不可遗漏）：
- SSE 事件解析边界（SseEventParserTest, SessionNextEventTest）
- 权限/问题流转（ChatViewModelPermissionTest）
- 消息队列/发送（ChatViewModelQueuedTest）
- 滚动恢复逻辑
- 消息撤销/重做/恢复草稿
- 序列化往返（SerializationTest）

### 5.2 新测试金字塔

| 层 | 测试类型 | 目标 |
|----|---------|------|
| Domain/util | 纯函数单测 | QuestionParser, PromptBuilder, HighlightBuilder 等提取逻辑 |
| Delegate | 单测（Turbine Flow 测试） | 每个 delegate 独立测试状态流 |
| ViewModel | 单测 | 薄编排者，验证 delegate 聚合 |
| Composable | 仪器测试 | 关键交互流程 |
| E2E | Maestro YAML | 核心用户流程回归 |

---

## 6. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| SSE 滚动稳定性回退 | 🔴 高 | 严格遵守 AGENTS.md 的 4 条规则；重写后专项验证 |
| ChatViewModel 行为遗漏 | 🔴 高 | 旧测试行为清单逐条核对；delegate 各有独立测试 |
| ChatScreen 回归 | 🟠 中 | 遵守编辑协议；每次编译验证；Maestro E2E |
| OpenCodeApi 接口变更影响面 | 🟠 中 | 保持方法签名不变；DI 绑定确保消费者无感 |
| 重写测试期间无安全网 | 🟠 中 | 旧测试保留 _legacy/ 可随时运行；Maestro E2E 兜底 |

---

## 7. 实施阶段

"激进重写"指对每个文件**重写实现**（而非逐步 Strangler Fig 拆分）。"分阶段"指**实施顺序**（按依赖方向，底层先于上层），每阶段独立 plan + 验证。两者不矛盾：每个阶段内部是激进重写，阶段间有依赖顺序。

> 测试时序：阶段 1 保持 API 签名不变，现有测试可兼容。阶段 3 起 ChatViewModel 内部结构大改，旧 VM 测试移至 `_legacy/`（不再运行），改为 delegate 新测试 + Maestro E2E 兜底。阶段 5 逐条核对行为清单后删除 `_legacy/`。

### 阶段 1：Data 地基
- OpenCodeApi 按域拆分（方法签名不变）
- MessageEventHandler + SSE 注册表重写
- 验证：编译 + 现有 Repository 测试通过（签名兼容）

### 阶段 2：util 提取
- QuestionParser / PromptBuilder / SlashCommandRegistry / HighlightBuilder
- 验证：纯函数单测

### 阶段 3：ChatViewModel delegate 化
- 拆 delegate + 重写 ViewModel 编排
- 旧 ChatViewModel 测试移至 `_legacy/`
- 验证：delegate 单测 + ViewModel 聚合测试 + Maestro E2E

### 阶段 4：Composable 重写
- ChatScreen 拆分 + PartContent + ChatInputBar + SettingsScreen + CodeSourceView
- 验证：编译 + 仪器测试 + Maestro E2E

### 阶段 5：测试完善
- 新测试覆盖行为清单（逐条核对 `_legacy/`）
- 核对完成后删除 `_legacy/`

---

## 8. 目标产出预估

| 层 | 原文件 | 拆分后 | 最大单文件目标 |
|----|--------|--------|--------------|
| Data (OpenCodeApi + EventHandler) | 2 | ~8 | <220 行 |
| ViewModel (ChatViewModel) | 1 | ~6-8（1 VM + 5-7 delegate） | <300 行 |
| Composable (5 文件) | 5 | ~15 | <300 行 |
| 新增 util | — | ~4 | <150 行 |

**总计**：8 个大文件 → ~35 个小文件，每个职责单一、可独立测试。18 个已合理文件保持不动。
