# 2026-08-14 问题模块分支审计批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）

来源：docs/research/question-module-audit-2026-08-14.md（46 分支：43 ✅ / 1 ❌ / 2 ⚠️，静态审查 + 单测审查）

- [x] **#125 多选模式下自定义答案提交后无法取消（唯一中等问题）** `ui` `question`
  - 问题：多选（multiple=true）问题中，用户通过输入框提交自定义答案后无法直接取消/删除——③态（行+Edit+✔）无取消按钮；修改态清空输入框后飞机按钮 disabled（`editText.isNotBlank()` 为 false）无法提交空值清除；**间接副作用**：修改态输入已有选项标签（如 "A"）会触发 onOptionClick("C") + onOptionClick("A")，后者因 "A" 已在 selected 中而 toggle off → 选项 A 被意外取消
  - 修复（2026-08-14 commit 77074c05）：③态新增 ✕ 删除按钮（toggle off 自定义值）；修改态提交防副作用（新值匹配已有选项标签时只移除旧自定义值，不 toggle on）；②态提交同样防副作用
  - 验证：代码检查（D1）✅；模拟器 E2E 实测待执行（需 agent 发多选问题，见 docs/dialogue-e2e-test-runbook.md）
  - 工时：~1h | 难度：中 | 涉及：QuestionPartContent.kt | 优先级：P1

- [x] **#126 4+ 问题时远页自定义草稿丢失** `ui` `question`
  - 修复（2026-08-14 commit 77074c05）：customDraft 提升到 QuestionPagerView 层 mutableStateMapOf<Int,String> 按页存取
  - 问题：多问题（4+）场景，Q1 输入未提交草稿后翻到 Q4 再翻回 Q1——草稿丢失（customDraft 重置为空）
  - 根因：QuestionPartContent.kt:326——customDraft 用无 key remember，状态绑定页面级 composition；HorizontalPager beyondViewportPageCount=1，距离超 1 页的页面销毁后重新组合
  - 方案：customDraft 按 pageIndex 提升到 QuestionCard 顶层（如 Map<Int, String>）；或增大 beyondViewportPageCount（内存换体验）
  - 工时：~0.5h | 难度：低 | 涉及：QuestionPartContent.kt/QuestionCard.kt | 优先级：P2

- [x] **#127 单选/多选 toggle 边界保护不对称** `ui` `question`
  - 修复（2026-08-14 commit 77074c05）：单选分支补 pageIndex 越界保护（与多选对称）
  - 问题：onOptionClick 单选分支（QuestionCard.kt:171）无 pageIndex 越界保护，多选分支（:174）有 `pageIndex < size` 保护——代码健壮性不一致（实际不触发，pageIndex 来自 pager）
  - 方案：单选分支补同款越界保护
  - 工时：~5min | 难度：低 | 涉及：QuestionCard.kt | 优先级：P2

---
