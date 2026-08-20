# 2026-08-20/21 第六轮：四路竞态审计整合修复
> 状态：无未决条目
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 子条目「RaceProbe 复现取证」提升为卡片 #166


- **方法**：用户判断正确——『几率型 bug 一般都是竞态条件』。四路子代理并行审计（跳转链路/结构变更×滚动/测量渲染/状态并发原语，报告 /tmp/perf-round4/*.md 四份共 ~1080 行）+ 主会话自查交叉，共产出 30+ 发现，其中与本症状直接相关的根因 7 项全部修复：
  - `bf3d1cf7` 【F1/F2/F3】pendingChunkPlans 锚点化（索引陈旧根治）+ 视口内防线 + 门控回退 + RaceProbe 埋点（--ez debug_race true，release 可用，TAG RaceProbe：JUMP/ENTRIES/CHUNK/VIEW 四类事件）
  - `1924d9db` 【四路整合】① jumpTo/jumpToTask 代际管理（cancelPreviousJob——旧协程含稳定窗口立即失效，写穿防护）② resolveLazyIndex 陈旧闭包（remember 无 key 捕获首帧——rememberUpdatedState 三件套修复）③ streamingMsgId 全局条件改 turn 粒度（全表 key 双向翻转根除）④ freshDi<0 反向小 bug（注释丢弃实际提交）⑤ 门控直读 phase.value（帧滞后洞）⑥ 预解析排除流式 turn（部分 AST 永久截断防护）⑦ 稳定窗口 1.5s→900ms+用户滚动即让位+gap>8f 才修（杀滚动卡主因）⑧ RaceProbe lazy lambda 化
- **机制解释链（审计钉死）**：叠放=视口内 key 裂变撞定位修正的 remeasure 竞态；『圆角变直角+标签行不可见+正文中间露出』=落点停在中段 chunk（RoundedCornerShape(0.dp) 且无标签行——中段设计如此，库源码证实锚 key 消失时 findIndexByKey 回退裸索引会走位进 turn 中部）；语义树缺节点=被跨过的 chunk 未组合；『滚动卡』=稳定窗口 scroll{}（MutatorMutex）杀死用户手势 + 双修正循环对拉。
- **真机压力回归（修复后）**：5 轮 {连跳×2 + 立即滚动×8} 组合暴力测试——全部落点正常、无叠放/无直角异常/顶部完整；滚动质量 4190 帧 5-7ms 占 97%+、无 >31ms 帧（跳转后立即滚动顺滑——稳定窗口让位生效）。
- **遗留登记**：
  - [ ] **P3：RaceProbe 复现取证待用户执行** `race`——若叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可直接重放：JUMP entries 数 vs VIEW keys 错位即定位）
  - [x] **P3：A-F4 反射 requestPositionAndForgetLastKnownKey** `refactor` ✅ 2026-08-21（fe784374）——跳转路径两处换官方挂起 scrollToItem（互斥锁内重定位改为块外标记+块外执行）；反射 LazyListReflection 仅留 SSE 高度补偿两处调用点
  - [x] **P3：卫生群**（D 报告 #7-11）✅ 2026-08-21（ae0d079c + 07507ae7）——① mdRegistry/JumpBubbleObserve/Ready 上报链/JPS pendingIndex·onCompleted·reset/JNC reset 全删（零读者实证）；② user 跳转预解析直通 Measuring（PartContent isUser 纯 Text 渲染，预解析纯延迟——附带性能修复）；③ RenderReadiness D-7 实例置换修复（解析前捕获 flow 实例直写，remove 后不复活、旧订阅者收得到完成态）+ update/awaitReady 死 API 移除；④ jumpPhase 订阅下沉 JumpMaskOverlay 小组件（蒙版显隐不再重组 1500 行主体）+ 时钟基统一 elapsedRealtime（门控/解锁/重定位节流/稳定窗口同基）
