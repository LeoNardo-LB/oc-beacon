# 2026-08-20 真机滚动稳定性批次（卡顿 + fling 下跳）
> 状态：部分完结（活跃 #163）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 条目编号：滚动两问题=#163


- [~] **真机滚动两问题：①滑过气泡卡顿 ②fling 下跳（长 agent 回复稳定复现）——已修 f03a89d5** `ui` `perf`
  - 用户报告（2026-08-20 真机）：上下滑动经过消息气泡（无论类型）卡顿；fling 下跳，长回复基本稳定复现
  - **取证（ScrollDiag 插桩 + 逐帧视频模板匹配 + gfxinfo）**：根因链 = mikepenz markdown 异步解析 → 长回复初次组合仅测得占位高度（412px）→ 解析完成暴涨（412→16746px，RESIZE logcat 实证）→ LazyColumn 锚点修正 → fling 中视口瞬移 1.4 万 px（=「下跳」）；16k px 布局单帧完成 = 卡顿帧（gfxinfo 93ms 帧实证）。另实证：解析跑在主线程（parseMarkdownFlow 无 flowOn），16KB 文本阻塞 100ms+ 打断拖拽；修复前 fling 90-300ms 即被杀
  - **修复（f03a89d5，三件套）**：① 滚动预解析驱动——视口 ±8 项 assistant 长文本（≥200 字符）提前后台解析（RenderReadinessRegistry，key=part.id，LRU 32），消费端组合时取 Parsed state 直接渲染（首测即最终高度）；② SafeFlingBehavior 限速 fling——每帧 ≤ 视口高/8（carry 保总距离）；③ preParse flowOn(Default) 移出主线程
  - **真机验证（对照基线）**：RESIZE 11→0（5 次定向 fling）；fling 存活 90-300ms→自然跑满 2s（位移 6300-6800px 与 v0/friction 物理吻合）；视频逐帧 DISCONT 6 处异常（停稳后 -390px 瞬移/减速中 -458px 暴冲）→0 处异常（仅剩正常起步加速）；janky 1.18% p90=7ms p99=65ms；全量单测绿
  - ⚠️ 待用户验收：滚动手感（限速档位/预解析距离可调）
  - **基建**：ScrollDiag 插桩保留（DEBUG-only：位置 LEAP/手势/RESIZE/补偿触发）——后续滚动问题真机取证直接复用

> **✅ 结案（2026-08-22）**：滚动手感经用户连续两轮滚动反馈覆盖验收（「上下滑动…还是有一点点卡顿」→ #190 根治后「确实流畅了很多」）。限速档位/预解析距离维持默认值，无调参诉求。
