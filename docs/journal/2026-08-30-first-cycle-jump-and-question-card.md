# first-cycle-jump-and-question-card（2026-08-30）

> 状态：待用户验收
> 关联：backlog #260 同族滚动稳定性；journal 2026-08-30-event-card-divider-design.md
> 来源：用户验收反馈两项（/diagnosing-bugs 流程）

## 反馈

1. 第一次展开收起后会跳，后面就不会跳了
2. 问题卡片也需要向下展开而不是向上展开

## 问题 1 根因（key 化 trace + drain 时间线实证）

打开会话后三张默认展开卡 + RB/TC/Markdown 异步内容持续长高（600ms-数秒
不等），把 item0 渐渐顶离贴底（实测漂移 190-444px，atBot 翻 false、
「滚动到底部」FAB 浮现）。该状态下对底部卡的收起/展开不再走贴底透传，
而是 mid-list 注入路径——实测单次 toggle 注入累计 ±444px 视口位移（为
卡片实际高度变化 185px 的 2.4 倍）= 下跳；且注入链顺手把列表重锚回贴底
= 「第一次跳、后面不跳」的完整成因。

上轮 1.5s 守卫窗口短于异步内容实际沉降时间（用户打开后阅读数秒再操作，
此时窗口已关）。

根修（79a8ce0b）：守卫窗口无限期化——autoScroll 跟随模式存活多久守卫
跟多久（跟随模式本义：用户未取消跟随，内容长高就该跟随贴底）；用户
滚动立即让位。铁律（用户阅读位置优先权、autoScroll 重置语义）不变。

真机验证：打开后静置 15s，FAB 不再浮现；贴底收起/展开全程 inject=0
atBot=true 透传，逐帧平滑无跳变。

## 问题 2 根修

pendingCount 到达路径 snapToBottom 瞬跳 = 整个对话一把推上去（问题卡
「向上展开」观感）。改 animateScrollToItem(0)：平滑下滑揭示，问题卡随
视口自头部下方逐帧展开 = 向下展开。

## 附带定因链（同轮排查成果）

- **分隔线收缩地板**：分隔线作为 AV 收缩约束的直接子级时，固定尺寸
  （1dp+30dp gap）构成收缩地板——AV 高度 <33px 后 wrapper 无法再缩，
  退出完成时 33px 单帧砸掉。与缓动 spec 无关（spring/FastOutSlowIn/
  Linear 三种 spec 均复现）。根修：分隔线移入 body 滚动 Column 内部，
  滚动容器吸收任意约束。MessageBubble 内缩 16dp 勿再加 horizontal
  padding 的教训同步在案（双层内缩实测线比正文更缩 16dp）。
- 展开收起尺寸动画 spring → tween(200, FastOutSlowInEasing)：spring
  首末帧各有 ~30px 突跳，tween 均匀铺帧。
- EV-REVEAL trace key 化（EV:<eventKey 尾 10 位>）+ 全量 measure trace
  （DEBUG-only）：透传路径此前零日志，排查因无痕走弯路；多卡共标签
  歧义消除。

## 残余（如实记录，后续项）

AV 退出完成/进入开始的边界帧仍有 ~30px 单帧台阶：分隔线+间距刚性块
（1dp 线 + 10dp gap）位于 AV 内容顶部，被 Top 导向的逐帧揭示在前几帧
整体带入/带出（trace：229 平台 3 帧 → -30 单帧）。彻底消除需以补偿器
状态机直接驱动展开高度替换 AnimatedVisibility（触碰 #241 验证路径，
需独立批次评估）。

## 提交

- 79a8ce0b fix(scroll): 会话打开贴底守卫无限期化 + 问题卡向下平滑揭示 + 展开动画 tween 化
- 5240574f fix(scroll): 打开会话后短窗贴底守卫（前轮，本轮无限期化取代其窗口设定）
