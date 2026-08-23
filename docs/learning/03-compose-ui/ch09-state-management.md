# ch09 · Compose 状态管理 —— _uiState 模式的完整闭环

> 状态：⬜ 待生成 ｜ 前置：ch05, ch08
> 本章解决：状态提升、remember/mutableStateOf、ViewModel 状态流对接；把 ch05 的 Flow 和 ch08 的重组接通。
> 填充方式：对 AI 说「填充 docs/learning 的 ch09」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- mutableStateOf + by delegate：值变了 UI 自动重算的原理
- 状态提升（state hoisting）：无状态组件设计（对照受控组件）
- ViewModel 暴露 StateFlow → collectAsStateWithLifecycle 收进 UI
- 单一 UiState data class 模式的利弊（对照 Redux 单 store）
- 事件流反向传递：用户操作怎么回到 VM（UiEvent/回调）

## 本项目锚点（待填充时重新验证）
- `ui/screens/home/HomeViewModel.kt:41` — HomeUiState 定义
- `ui/screens/home/HomeViewModel.kt:61` — _uiState = MutableStateFlow(...)
- `ui/screens/workspace/WorkspaceUiState.kt:15` — 另一个 UiState 样本

## 观察任务预告
- 选一个 Screen 画出它的完整状态流闭环图（VM 字段 → collect → 渲染 → 用户事件 → 回写）
- 对比 Home 与 Workspace 两处 UiState 设计的取舍
