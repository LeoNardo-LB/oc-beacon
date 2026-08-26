# event-card-unification（2026-08-27）

> 状态：进行中
> 关联：docs/specs/2026-08-26-event-card-unification-design.md · backlog #234
> 来源：用户 2026-08-26「主对话流中出现新元素（SSE）的通知样式统一」→ #234 卡

## 开工快照（2026-08-27）

- **前提确认**：#233 架构重构已验收（docs/journal/2026-08-26-arch-campaign-acceptance.md，master dc98a823）；重构后代码现状复核——
  - task/shell 卡：ui/screens/chat/components/SyntheticNotificationCard.kt（composable + 解析函数同居），渲染挂点 MessageCardAssistant.kt 两处 RenderItem.SyntheticNotice（其一注明「本渲染项已无生产者——防御保留」）
  - system 单行通知（#232）：ChatMessageList.kt isUser 分支内联 ~1307–1379，展开表 systemNoticeExpandedStates（屏幕级 mutableStateMapOf，#227 模式）
- **用户开工裁决**（ask_user 实录）：
  - Q15 描述行：「如果有命令预览（实际的描述）那就也激活 shell，其他的卡片同理」→ 数据在则激活
  - Q16 批次节奏：两批连做（独立 commit），合并一次真机验收
- **实施映射（重构后现状落点）**：
  - 新组件 EventCard.kt 落 components/（与 MessageBubble 同目录——契约要求容器与其同构，直接复用 MessageBubble 作外层容器）
  - 三类展开记忆统一走单一屏幕级表 eventCardExpandedStates（替代 systemNoticeExpandedStates；compaction 表不动——另一族）
  - 解析层零改动：parseSyntheticTask/SyntheticTaskInfo/extractTaskDescription 原样保留（测试 SyntheticTaskParserTest/ParseSyntheticTaskTest 同包不迁移）
  - i18n：新增 chat_event_task_completed/task_failed/shell_completed/shell_failed/tool_catalog_changed + chat_event_generic（降级态标签，§3 清单外补一处——旧卡 fallback 行为迁入新卡的必要承载）；退役 chat_background_agent_completed/failed、chat_background_shell_completed/failed、chat_label_tasks、chat_system_notification（长期死 key 一并清）

## 批一（EventCard 组件 + system 迁入）

- **组件层**（commit e70b74d1）：
  - `MessageBubble` 增加可选 `onCardClick: (() -> Unit)? = null`——null 时行为零变化（user/assistant 气泡不受影响）；clickable 挂在内层内容 Column（padding 之内），展开区滚动/子元素点击自然消歧，无手势冲突
  - 新建 `EventCard.kt`：严格同构模子（参数表 §2）。失败态 ErrorOutline+AgentError 描边；跳转箭头经 labelTrailing 进标签行（常驻两态，#216 守恒）；chevron 仅 hasBody 时显示；展开两段式 HorizontalDivider 包夹（heightIn(max=300dp) 在 verticalScroll **之外**——#232 勘误铁律落代码注释）；动作区 End 对齐 TextButton 行
  - 设计决策：布局骨架复用 MessageBubble（契约「容器同构」的直接兑现——shadow `spacer` 死变量笔误当场修正，未入编译态）
- **迁入**：`ChatMessageList.kt` isUser 分支内联块（原 ~1307–1379）替换为 EventCard 调用——body=Text(schema 全文)（旧通知 bodySmall 样式守恒）、label=`chat_event_tool_catalog_changed`、图标 Info、描述行不激活（Q15：system 无描述数据）；`SysMsgDiag` DEBUG 取证日志保留并改标记为 `#234 event-card branch`
- **展开记忆**：`systemNoticeExpandedStates` → 更名 `eventCardExpandedStates`（单一屏幕级表服务三事件卡家族；compaction 表不动）
- **i18n**：`chat_event_tool_catalog_changed` 15 语言全插入（锚 chat_system_notification 后）；`scripts/i18n-check.sh` PASSED（672 keys × 14 languages，全程 81s——注意：此脚本是慢非死，默认短超时会静默截断输出，需 ≥120s 超时或后台跑）

## 批二（task/shell 迁入 + 旧卡退役）

- **适配器化**：`SyntheticNotificationCard.kt` 整体重写——composable 缩为 EventCard 薄适配器（解析函数/正则/SyntheticTaskInfo 原样保留在文件底部，「解析层零改动」守恒项；单测 SyntheticTaskParserTest/ParseSyntheticTaskTest 同包直引不受影响）。参数表映射：task=CheckCircle+任务描述行+定位动作钮 / shell=Terminal+命令预览行 / 解析失败降级=Info+generic 标签+原文截断作描述行；navTargetId 经显式 lambda 参数传入规避 smart-cast 问题；agent 输出截断 2000 / shell 全量守恒
- **形态翻案落档**：本组件头注声明 #67「独立气泡方案 A」翻案链指向 spec §6/§7
- **传参链**：MessageCard 增加 `eventExpandedStates: MutableMap<String, Boolean>` 必填参 → SYNTHETIC/ASSISTANT 双分支转发；MessageCardAssistant 主签名/ChunkedAssistantMessage/ChunkAssistantItems 三层贯通（防御性 SyntheticNotice 分支同步换签名）；ChatMessageList 四个调用点全部接 `eventCardExpandedStates`
- **编译教训**：一次工具调用中断吞掉后续 edit 导致 4 处连锁报错（helper 签名与其分支调用缺失）+ 一处漏网 ChunkAssistantItems 调用点（subList(0,targetIdx) 首段）——grep 参数分布后逐一修复，未用 git checkout 回滚（错误均可定位）
- **i18n**：新增 `chat_event_task_completed/task_failed/shell_completed/shell_failed/generic/locate_task` ×15 语言；退役删除 `chat_background_agent_completed/failed`、`chat_background_shell_completed/failed`、`chat_label_tasks`、`chat_system_notification` ×15（删前 grep 证零引用；`a11y_locate_task`/`tool_terminal`/`tool_sub_agent` 因 ToolCardRegistry/TaskToolCard/新卡仍引用保留）
- **自动化验证**：compileDevDebugKotlin 绿 · testDevDebugUnitTest --rerun BUILD SUCCESSFUL（1m04s，含 SyntheticTaskParserTest/ParseSyntheticTaskTest）· assembleDevDebug 出包成功（app-dev-debug.apk 03:31）
- **commit 策略说明**：Q16 用户拍板「两批连做合并验收」——批一迁入与批二改造在同文件（ChatMessageList）交织，无法机械化拆分为两个纯净 commit；实施为连续推进、验证分步全绿，最终单 commit 落地（journal 分段记录批次边界）

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->