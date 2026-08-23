# ch11 · 副作用与性能 —— LaunchedEffect 家族与滚动稳定铁律

> 状态：⬜ 待生成 ｜ 前置：ch09
> 本章解决：Compose 副作用 API 全家桶；为什么这个项目的 SSE 输出有一堆"铁律"。
> 填充方式：对 AI 说「填充 docs/learning 的 ch11」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- LaunchedEffect / DisposableEffect / SideEffect / rememberCoroutineScope 分工与 key 规则
- derivedStateOf / snapshotFlow：计算缓存与流桥接
- 性能直觉：不必要的重组从哪来（不稳定参数、lambda 重建）
- 案例复盘：SSE 滚动稳定性四条铁律各自的因果链（48ms 批处理 / retainState / 补偿只给流式消息 / isAtBottom 自愈）

## 本项目锚点（待填充时重新验证）
- `docs/research/sse-scroll-stability-iron-laws.md` — 完整回归历史（根 AGENTS.md 引用）
- `ChatScreen.kt` 中 LaunchedEffect 以 isScrollInProgress/isAtBottom 为 key 的现场
- rememberMarkdownState(content, retainState=true) 使用处

## 观察任务预告
- 在 ChatScreen.kt 里找出所有副作用调用并按 API 分类
- 复述四条铁律中任意一条的「违反了会发生什么」
