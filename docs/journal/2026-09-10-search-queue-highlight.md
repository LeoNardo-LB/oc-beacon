# search-queue-highlight（2026-09-10）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §一 用户裁决⑤⑦+高亮双修实现（2026-09-10）

- **#⑤ 角色/高亮双修（SessionListScreen）**：根因实证（拉库直查）——FTS 层两角色健全（whale: assistant 153 + user 14），BM25 短文档偏置把 user 提示排前（top30 全 user），渲染层 groupBy{sessionId}.first().snippet 每会话只显首条 → AI 命中被折叠（「全部」只剩人类）。修法：逐命中行（角色标签 chat_label_user/agent + 各自 messageId 跳转）+ 会话分组头（标题+计数）+ 按 messageId 去重（FTS 行级重复 part 折叠）。高亮：FTS snippet() 的 [中括号] 标记改 AnnotatedString 背景色高亮（HighlightedHitSnippet；LIKE 降级无标记纯文本）。
- **#⑦ 排队消息不上转录（V2SseMapper 单点 + 徽章链整撤）**：根因——session.inbox.enqueued 无条件播种 MessageUpdated（事件自带 delivery 字段但未读）。修法：delivery=queue → return null 不上转录（轮末派发后经轮末刷新入转录）；steer/直发/过渡契约 delivery:{} 照常。连带拆除 FSM 启发式徽章链（MessageDataDelegate P5-1 推导 + ChatUiState.queuedMessageIds + ChatMessageList×2 传参 + MessageCardUser×2 徽章块 + MessageCard 转发 + chat_queued ×15 locale + QueuedBadge 色值 + ContentHitNavigation 死码 + 对应 7+2 测试撤/改）。三面统一：DSH 队列本就 inbox 帧、V1 无队列域。
- **#优化2 跳转终点 5s 高亮（ChatMessageList）**：highlightedTurnKey 3s→5s（用户裁决）；Displayed 相位单点派生设键（rawIndex→u_/t_ 键式沿袭），onLocateTask 手工设键块撤除——内容检索进会话定位/快速定位/onLocateTask 三链统一高亮。
- **验证**：compileDevDebugKotlin ✓ · 全量单测 3242 ✓（新增 delivery=queue 拦截测试；过渡契约 delivery:{} 回归测试由防御读取修复）· i18n-check 883 keys ✓ · assembleDevDebug ✓ · 装机冒烟：V2 搜索 story 逐命中行+角色标签渲染在场。待用户复验：⑤ 全部过滤下 AI/人类行并存、命中段高亮、点击进会话 5s 高亮；⑦ 忙时排队消息只在 QueueSheet、轮末派发后入转录。
- **伴随登记**：#391（本批前置——用户指定 GLM-5.3-Flash 演示模型）、#392（V1 sheet fling 未隔离——⑥ 自助验收顺带发现）。
