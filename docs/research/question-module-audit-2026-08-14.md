# 问题模块分支逻辑审计报告

> 日期：2026-08-14 · 方法：静态代码审查 + 已有单测审查 + 模拟器 E2E（agent 超时未完成）
> 范围：QuestionCard.kt / QuestionPartContent.kt / PartContent.kt / QuestionEventHandler.kt + 相关链路

## 1. 测试覆盖矩阵

| # | 分支场景 | 结论 | 证据来源 |
|---|---------|------|---------|
| **A. 单问题 vs 多问题** | | | |
| 1 | questions.size==1 → pagerState=null，走单页分支 | ✅ | QuestionCard.kt:106-108, QuestionPartContent.kt:240-248 |
| 2 | questions.size>1 → pagerState 创建，HorizontalPager | ✅ | QuestionCard.kt:106-107, QuestionPartContent.kt:249-308 |
| 3 | pagerState null 传递 → QuestionPagerView 单页路径 | ✅ | QuestionPartContent.kt:240-248 |
| 4 | showTabs = size>1 → Q tabs 嵌入 | ✅ | QuestionCard.kt:161, QuestionPartContent.kt:301-303 |
| **B. 单选 vs 多选** | | | |
| 5 | isSingle 判定（size==1 && !multiple） | ✅ | QuestionCard.kt:95 |
| 6 | isSingleQuestion 按题目 multiple 逐题判定 | ✅ | QuestionCard.kt:168 |
| 7 | 单选 toggle 选中→取消（current==listOf(label)） | ✅ | QuestionCard.kt:171 |
| 8 | 单选 toggle 未选→选中（替换） | ✅ | QuestionCard.kt:171 |
| 9 | 单选互斥（选B替换A） | ✅ | QuestionCard.kt:171 |
| 10 | 多选 toggle 已选→取消（remove） | ✅ | QuestionCard.kt:173 |
| 11 | 多选 toggle 未选→选中（add） | ✅ | QuestionCard.kt:173 |
| **C. 自定义输入三态** | | | |
| 12 | ②默认编辑态（无自定义答案，非只读） | ✅ | QuestionPartContent.kt:488-517 |
| 13 | ③已提交态（有自定义答案，非只读） | ✅ | QuestionPartContent.kt:415-451 |
| 14 | 修改态（Edit → 输入框预填 + 飞机） | ✅ | QuestionPartContent.kt:452-487 |
| 15 | 草稿保留（customDraft 提升到组件顶层） | ✅ | QuestionPartContent.kt:326 |
| 16 | 修改提交（单选：toggle off 旧 + toggle on 新） | ✅ | QuestionPartContent.kt:477-478 + QuestionCard.kt:169-171 |
| 17 | 修改提交（多选：toggle off 旧 + toggle on 新） | ✅ | QuestionPartContent.kt:477-478 + QuestionCard.kt:173-174 |
| 18 | 单选自定义+选项互斥（自定义替换选项） | ✅ | QuestionCard.kt:171 listOf(label) |
| 19 | 多选自定义+选项共存（add 语义） | ✅ | QuestionCard.kt:173 |
| 20 | **多选自定义答案取消** | ❌ **BUG** | 见 Bug #1 |
| 21 | 只读自定义行（历史视图高亮） | ✅ | QuestionPartContent.kt:404-414 |
| **D. 按钮域** | | | |
| 22 | 忽略按钮（submitted=true + onReject） | ✅ | QuestionCard.kt:188 |
| 23 | 下一步 enabled（非末页） | ✅ | QuestionCard.kt:204 |
| 24 | 下一步末页置灰 | ✅ | QuestionCard.kt:204 |
| 25 | Submit enabled（至少一题有答案） | ✅ | QuestionCard.kt:225 |
| 26 | Submit 禁用（全部未答） | ✅ | QuestionCard.kt:225 |
| 27 | 未回答弹窗 unansweredQuestionIndexes | ✅ | QuestionCard.kt:266-273（有 4 个单测） |
| 28 | 弹窗"继续"提交 | ✅ | QuestionCard.kt:246-250 |
| 29 | 弹窗"忽略"关闭 | ✅ | QuestionCard.kt:252-254 |
| 30 | initiallySubmitted 隐藏按钮域 | ✅ | QuestionCard.kt:181（但未被调用，见 §3） |
| **E. 回答后卡片消失** | | | |
| 31 | 提交链路（onSubmit→replyToQuestion→removeQuestion） | ✅ | ChatMessageList.kt:747, SessionActionsDelegate.kt:201 |
| 32 | 拒绝链路（onReject→rejectQuestion→removeQuestion） | ✅ | ChatMessageList.kt:751, SessionActionsDelegate.kt:226 |
| 33 | API 失败乐观删除（catch 块 removeQuestion） | ✅ | SessionActionsDelegate.kt:206 |
| **F. 只读态** | | | |
| 34 | submitted=true 选项不可点（readOnly→enabled=false） | ✅ | QuestionPartContent.kt:376 |
| 35 | 输入框隐藏（readOnly 不走 ②态） | ✅ | QuestionPartContent.kt:488 |
| 36 | 按钮禁用 | ✅ | QuestionCard.kt:189,225 |
| **G. 历史视图** | | | |
| 37 | CollapsibleQuestionPart isMultiple 分支 | ⚠️ | QuestionPartContent.kt:144（文本格式不提取 multiple） |
| 38 | QuestionExpandedOptions 只读渲染 | ✅ | QuestionPartContent.kt:174-189 |
| 39 | Part.Tool question 分流（活跃不渲染/完成渲染历史） | ✅ | PartContent.kt:121-152 |
| **H. Q tabs** | | | |
| 40 | Q tabs 与 currentPage 同步 | ✅ | QuestionPartContent.kt:210 |
| 41 | Q tabs 点击翻页 | ✅ | QuestionPartContent.kt:211 |
| 42 | 下一步 animateScrollToPage 边界（coerceAtMost） | ✅ | QuestionCard.kt:200 |
| **I. 高度插值** | | | |
| 43 | pageHeights 记录（onGloballyPositioned） | ✅ | QuestionPartContent.kt:284-286 |
| 44 | offset 方向判定（>0→from+1, <0→from-1） | ✅ | QuestionPartContent.kt:265 |
| 45 | 单页回退（h1==0→返回0→wrap content） | ✅ | QuestionPartContent.kt:267,275-277 |
| 46 | 边界页（第一页向左/末页向右→h2=h1） | ✅ | QuestionPartContent.kt:266 |

## 2. Bug 清单

### Bug #1 [中] 多选模式下自定义答案提交后无法取消

- **场景**：多选（multiple=true）问题中，用户通过输入框提交自定义答案后，该答案无法被直接取消/删除
- **预期行为**：用户应能取消已提交的自定义答案（类似选项的 toggle off）
- **实际行为**：
  1. 提交自定义答案 "C" 后进入 ③态（行+Edit+✔），输入框消失
  2. ③态无取消按钮；✔ 仅装饰不可点
  3. 进入修改态（Edit），清空输入框 → 飞机按钮 disabled（`editText.isNotBlank()` 为 false）
  4. 无法提交空值来清除自定义答案
  5. 间接操作（修改态输入已有选项标签如 "A"）会触发 `onOptionClick("C")` + `onOptionClick("A")`，后者因 "A" 已在 selected 中而 toggle off，**导致选项 A 被意外取消**
- **根因**：`QuestionPartContent.kt:415-487`（③态和修改态均缺少"删除自定义答案"操作入口）；修改态提交逻辑（:477-478）先 toggle off 旧值再 toggle on 新值，若新值是已有选项则产生副作用
- **严重度**：**中** — 不会崩溃或数据错误，但多选+自定义组合下用户体验明显受损；间接操作有意外副作用（其他选项被取消）
- **静态审查结论**（E2E 因 agent 超时未覆盖）

### Bug #2 [低] 4+ 问题时远页自定义草稿丢失

- **场景**：多问题（4+）场景，用户在 Q1 输入未提交草稿后翻到 Q4，再翻回 Q1
- **预期行为**：草稿应保留
- **实际行为**：草稿丢失（`customDraft` 重置为空字符串）
- **根因**：`QuestionPartContent.kt:326` — `customDraft` 使用无 key 的 `remember`，状态绑定在页面级 composition。HorizontalPager `beyondViewportPageCount=1`（:279），距离当前页超过 1 页的页面 composition 被销毁，翻回时重新组合
- **严重度**：**低** — 仅 4+ 问题时触发，且仅影响未提交草稿

### Bug #3 [低] 单选/多选 toggle 边界保护不对称

- **场景**：`onOptionClick` 中 pageIndex 越界
- **预期行为**：两个分支都应有越界保护
- **实际行为**：单选分支（:171）无保护直接赋值，多选分支（:174）有 `pageIndex < size` 保护
- **根因**：`QuestionCard.kt:171` vs `:174`
- **严重度**：**低** — 实际不触发（pageIndex 来自 pager，在范围内），仅代码健壮性不一致

## 3. 未覆盖分支清单

| 分支 | 原因 | 建议 |
|------|------|------|
| **E2E 全场景** | Agent 运行超时（>70s 未完成），无法构造提问场景 | 需 agent 空闲时重测：单/多问题、单选互斥、多选、自定义三态、未回答弹窗、回答后消失 |
| **QuestionCard Compose UI 测试** | 无 createComposeRule 测试覆盖 QuestionCard | 补充：单选 toggle、多选 toggle、自定义三态流转、未回答弹窗、提交/拒绝的 Compose 测试 |
| **initiallySubmitted/initialAnswers 参数** | 未被任何调用方传递（ChatMessageList:743, MessageCardAssistant:232 均用默认值） | 确认是否为预留/废弃代码；若废弃可清理 |
| **CollapsibleQuestionPart 多选文本格式** | opencode 文本格式（parseQuestionContent 格式 1）不提取 multiple 字段 → 历史视图图标可能不匹配 | 若文本格式数据含 multiple 信息，补充解析 |
| **高度插值多页实测** | 需 3+ 问题的实际渲染验证 | 补充 Compose 测试或 Maestro 流程 |

## 4. 已有单测覆盖评估

| 测试文件 | 用例数 | 覆盖范围 | 评价 |
|---------|--------|---------|------|
| QuestionCardLogicTest | 4 | unansweredQuestionIndexes 纯函数（空/部分/全答/短列表） | ✅ 充分 |
| QuestionEventHandlerTest | 15 | asked/replied/rejected/remove/set/mergeFromREST/clear | ✅ 充分 |
| QuestionParserTest | 20 | parseQuestionContent（3 格式）/ parseQuestionFromToolData / multiple 字段 | ✅ 充分 |
| ChatInteractionTest (androidTest) | 含 QuestionCard 场景 | 插桩级交互 | ⚠️ 覆盖基本渲染，未覆盖多选/自定义/弹窗 |

## 5. 结论

**问题模块整体健康度：良好（85/100）**

核心逻辑（单选/多选 toggle、未回答检测、卡片消失链路、SSE 事件处理、历史解析）均正确且有单测覆盖。2026-08-13 的多问题单选 bug 修复（isSingleQuestion 按题目独立判定）已到位。

**主要风险**集中在**自定义输入与多选的组合交互**：
- Bug #1（多选自定义无法取消）是唯一中等严重度问题，影响范围明确（multiple=true + 自定义输入），建议优先修复——在 ③态或修改态增加"删除自定义答案"入口
- Bug #2/#3 为低风险，可纳入 backlog

E2E 因 agent 运行超时未能完成，上述结论为**静态审查结论**，建议 agent 空闲后对 Bug #1 做模拟器实测确认。
