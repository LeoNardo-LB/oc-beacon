# ch05 · Flow 与 StateFlow —— UI 自动刷新的魔法来源

> 状态：⬜ 待生成 ｜ 前置：ch04
> 本章解决：冷流热流之别、StateFlow/SharedFlow 分工、collect 语义；对照 Reactor Flux/Mono。
> 填充方式：对 AI 说「填充 docs/learning 的 ch05」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- Flow 冷流 ≈ Flux；StateFlow 热流 ≈ BehaviorSubject + 当前值缓存
- MutableStateFlow 私有 / StateFlow 公开的封装惯用法（_uiState 模式）
- 操作符速览：map/filter/debounce/distinctUntilChanged vs Reactor 同名物
- stateIn/ShareIn：把冷流转成可共享热流的正确姿势
- collect 与生命周期：repeatOnLifecycle 为什么必须

## 本项目锚点（待填充时重新验证）
- `data/repository/SessionStateService.kt:143-146` — statusFlow: StateFlow<Map<String, SessionStatus>>（会话状态单一真相源）
- `service/SseConnectionManager.kt` — SSE 连接上 9 处 Flow 使用
- `data/repository/EventDispatcher.kt` — 事件分发 26 处
- `ui/screens/home/HomeViewModel.kt:61` — _uiState 模式标准范例

## 观察任务预告
- 给项目中每个 StateFlow/SharedFlow 分类：谁写谁读、冷热归属
- 找出一处没做 distinctUntilChanged 导致多余重组的点（如有）
