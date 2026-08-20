# 提问组件改进设计（#26 + #27 + #28 + 新增项）

日期：2026-08-08
状态：已批准（会话中确认各节）

## 背景与目标

用户反馈提问组件多个问题，合并为一个批次处理：

- **#26** 单选/复选控件语义纠正（多选应显示复选框，历史链路 multiple 丢失）
- **#27** 多问题提问"下一步/提交"流程（三按钮体系）
- **#28** 提问组件样式与高度统一优化（M3 规范对齐）
- **新增 A** 会话列表页"待回答"状态展示 + 通知触发链路修复
- **新增 B** 双端同机问题状态不同步 bug（loadPendingQuestions 只加不删）

## 已探测事实（2026-08-08 实机探测）

1. **multiple 字段可信**：单选时省略（缺省 = false），多选时显式 `"multiple": true`，与 API 文档 Schema（`multiple: false?`）一致。`?: false` 默认值正确。
2. **SSE 不推送 question.* 事件**：`/event` 与 `/global/event` 均只收到 `session.*` / `message.*` / `server.*`，无任何 `question.asked` / `question.replied` 事件。question 数据随 tool part 写入消息，实时获取须轮询 REST `GET /question`。
   - 推论：现有通知（依赖 SSE `QuestionAsked`）与问题移除（依赖 SSE `QuestionReplied`/`QuestionRejected`）**在真实服务器上可能从未生效**——需验证并补 REST 兜底。

## 现状代码链路（已确认）

### 活动提问链路（双源）
- SSE `QuestionAsked`（QuestionEventParser → QuestionEventHandler，内存 StateFlow 按 sessionId 聚合）
- REST `GET /question`（`MessageDataDelegate.loadPendingQuestions`，进入会话 + refresh 时调用，合并去重）
- `QuestionCard` 从 `interaction.pendingQuestions` 渲染（ChatMessageList.kt:447-453）

### 历史提问链路（两个入口）
- `Part.Text`（含 "questions:" + "User has answered"）→ `CollapsibleQuestionPart`（QuestionPartContent.kt:66）——**不读 multiple，答案图标固定 RadioButtonChecked** ❌
- `Part.Tool`（question 工具）→ `parseQuestionFromToolData` → `QuestionExpandedOptions`——**QHistItem.isMultiple 永远 false** ❌
- `QuestionExpandedOptions`（QuestionPartContent.kt:165）→ `QuestionPagerView` → `QuestionOptionRows`——isMultiple 传递正确 ✅

### 通知链路（已存在但疑失效）
- SSE `QuestionAsked` → `OpenCodeConnectionService` → `AppNotificationManager.showQuestionNotification`（专属 channel，HIGH 优先级 + 振动 + 去重 + 焦点抑制 + 子会话冒泡）——依赖 SSE，疑从未触发

### 会话列表页（无提问状态展示）
- `SessionRow` 只展示 status（Busy → ChatBubble 图标 + "忙碌中"）与未读红点
- 数据源已存在：`chatRepository.getAllQuestionsFlow()`（QuestionEventHandler.questions）

### 双端同机 bug（实锤）
- 设备 A 回答 → REST `GET /question` 不再返回该问题
- 设备 B `loadPendingQuestions` 合并逻辑只加不删（`existingSseQs + newQs`）→ 问题永久残留
- SSE `QuestionReplied` 不推送 → 设备 B 无其他移除信号

---

## 节 1 — 数据链路修复（#26）

### 1.1 `QuestionParser.parseQuestionFromToolData` 解析 multiple
- 解析工具输入 JSON 的 `multiple` 字段（`qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false`）→ `QHistItem.isMultiple`
- 覆盖两个解析分支（结构化 JsonArray + JSONArray 回退）

### 1.2 `QuestionParser.parseQuestionContent` 增加 isMultiple
- `ParsedQuestion` 增加 `val isMultiple: Boolean = false`
- JSON 格式（格式 2）解析 `multiple` 字段；文本格式（格式 1/3）保持 false

### 1.3 `CollapsibleQuestionPart` 答案图标按 multiple 分支
- `parsed.isMultiple` → `Icons.Default.CheckBox`；否则 `RadioButtonChecked`（保持现状）

### 1.4 清理调试日志残留
- `PartContent.kt:121-131`：`AppLogger.e("TOOL ELSE:...")` / DebugLogger QuestionTool 调试日志删除（isQuestionTool 判断逻辑保留）

## 节 2 — 三按钮交互体系（#27）

### 2.1 按钮布局（活动提问卡片，`!initiallySubmitted` 时）
- **忽略**（TextButton，保持现状）
- **下一个**：非末页显示，点击 `pagerState.animateScrollToPage(current+1)`；**末页置灰**（不隐藏）
- **提交**：始终显示；启用条件 = 任一问题有答案（部分回答可提交，保持现状）
- Tab 标签保持可自由点击切换

### 2.2 未答完提交弹窗
- 点击提交时若存在未回答问题 → AlertDialog 确认：
  - 文案：列出未回答的具体问题编号（如"第 1、3 个问题没有回答"，可单可多）
  - 按钮：【继续提交 / 取消】；继续则照常 `onSubmit`
- 全部已回答时直接提交，不弹窗

### 2.3 单选场景统一
- 不再"点选即提交"——点击选项仅选中（单选可取消：再点已选项取消选中，可无选择）
- 单问题单选 = 忽略 + 提交（无"下一个"）
- 历史模式（initiallySubmitted）不显示按钮区（保持现状）

### 2.4 组件签名调整
- `QuestionCard` 需要感知 pagerState 页码（"下一个"跳页）——pagerState 从 `QuestionPagerView` 提升或回调方式
- 设计倾向：`QuestionPagerView` 增加 `onPageChange` 回调 / 暴露当前页，`QuestionCard` 持有提交前校验 + 弹窗状态

### 2.5 i18n
- 新增文案："下一个"、"第 {x} 个问题没有回答"（15 语言，按 i18n-guide 工作流）

## 节 3 — 样式规范对齐（#28）

仅调 token/尺寸，不改结构：

- 选项行最小高度对齐 M3 触摸目标（≥48dp）
- 图标 16dp → 24dp（M3 规范）
- 选项行/卡片外边距与内边距统一走 SpacingTokens
- 单选/多选选中态视觉统一（accent + AlphaTokens.SELECTED，保持）
- 验证各组件理论高度与当前实现的差距，消除"缩在一起"观感

## 节 4 — 会话列表"待回答"展示 + 通知修复（新增 A）

### 4.1 会话列表行内标记
- `SessionListViewModel`：sessionDataFlow 增加 1 源（`chatRepository.getAllQuestionsFlow()`）→ `SessionListDataInputs` 增加 `pendingQuestionIds: Set<String>`（或 `hasPendingQuestion: Boolean` per session）
- `SessionListStateBuilder` / `SessionItem` / `TreeNode` / `SessionRow`：状态位显示"待回答"（HelpOutline 图标 + 文案，与 Busy/idle 并列；优先级高于 idle，低于或并列 Busy）
- 仅标记，不排序（保持现有列表顺序）

### 4.2 通知 REST 兜底
- 验证步骤（真机/模拟器）：实际触发提问，观察通知是否弹出（若 SSE 事件确实不推 → 走 REST 兜底）
- 实现：`AppNotificationManager` 订阅问题数据流，发现新问题（未通知过且未被焦点抑制）→ 触发 `showQuestionNotification`
  - 触发源选择：复用 `loadPendingQuestions` 后的对比（ChatViewModel 层）或直接在 AppNotificationManager 订阅 `questions` flow 检测新增
  - 设计倾向：**在数据层统一**——`loadPendingQuestions` 发现新增问题 id 时，经一个回调/事件触发通知（避免 AppNotificationManager 直接依赖 UI 层）
- 去重：复用现有 `lastNotifiedQuestionBySession`（按 questionText）；补 REST 路径时注意与 SSE 路径共用去重 key，避免重复通知

### 4.3 子会话冒泡
- 沿用现有 `isChildSession` → 父 session 通知逻辑

## 节 5 — 双端问题状态同步修复（新增 B）

### 5.1 根因
- `loadPendingQuestions` 合并去重只加不删（`existingSseQs + newQs`）+ SSE `QuestionReplied` 不推送

### 5.2 修复
- `loadPendingQuestions` 改为**全量替换语义**：REST `GET /question` 请求成功后，以返回集合为准替换该会话的问题（`chatRepository.setQuestions(sid, sessionQuestions)`），不再拼 existingSseQs
- 竞态说明（注释）：SSE 新推送的问题在下一轮 REST 同步会出现，REST 是最终权威；全量替换消除"已消失问题永久残留"
- 注意：`setQuestions(sid, emptyList())` 当前会移除该 session 键（`if (qs.isEmpty()) current - sessionId`）——正好符合"问题清空"语义

### 5.3 验证
- 双端同机：A 回答后 B 的提问卡片消失（真机验证）

---

## 验证计划

1. **编译**：`.\gradlew :app:compileDevDebugKotlin`（120s）
2. **单测**：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s）——新增/更新 QuestionParser 与状态构建测试
3. **真机验证清单**：
   - 活动提问：多选显示复选框、单选显示单选框（含服务器省略 multiple 场景）
   - 历史消息：多选答案显示复选框（CollapsibleQuestionPart + QuestionExpandedOptions 两入口）
   - 三按钮：忽略/下一个（末页置灰）/提交；Tab 自由切换；未答完提交弹窗（第 X 个问题没有回答）→ 继续提交
   - 单选点选不立即提交，可取消选中
   - 样式：选项行高度统一、图标 24dp、外边距舒展
   - 会话列表：有提问的会话显示"待回答"标记
   - 通知：提问时通知弹出（验证 SSE 或 REST 兜底链路）
   - 双端同机：A 回答后 B 问题消失
4. **UI/UX 时间性现象**（闪烁/动画/布局跳动）：按 verification-requirements 维度 5 提供人工验证清单

## 涉及文件（预计）

- `ui/screens/chat/util/QuestionParser.kt`（multiple 解析）
- `ui/screens/chat/components/QuestionPartContent.kt`（CollapsibleQuestionPart 图标 / QuestionPagerView 页码回调 / 选项行样式）
- `ui/screens/chat/components/PartContent.kt`（调试日志清理）
- `ui/screens/chat/dialog/QuestionCard.kt`（三按钮体系 + 弹窗）
- `ui/screens/chat/MessageDataDelegate.kt`（loadPendingQuestions 全量替换）
- `ui/screens/sessions/SessionListViewModel.kt` / `SessionListStateBuilder.kt` / `components/TreeNode.kt` / `components/SessionRow.kt`（待回答标记）
- `ui/screens/sessions/SessionListUiState.kt`（SessionItem 字段）
- `service/AppNotificationManager.kt` / `service/OpenCodeConnectionService.kt`（通知 REST 兜底，视验证结果）
- `values*/strings.xml`（i18n 15 语言）
- 测试：QuestionParserTest / SessionListStateBuilderTest / 相关 UI 测试

## 非目标（明确不做）

- 不做会话列表"待回答置顶排序"
- 不做提问卡片视觉重设计（仅规范对齐）
- 不改变 opencode 服务器端行为
- 不做 PermissionRequestCard 样式改动（本次仅提问组件；如用户后续需要可另开）
