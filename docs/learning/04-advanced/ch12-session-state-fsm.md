# ch12 · SessionStateService 与 FSM —— 项目最承重的架构决策

> 状态：⬜ 待生成 ｜ 前置：ch05, ch09
> 本章解决：为什么"会话状态的单一真相源"如此重要；纯函数状态机设计；一次失败重构的考古。
> 填充方式：对 AI 说「填充 docs/learning 的 ch12」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- 问题域：多会话并发时 idle/busy/retry + Waiting/Streaming/ToolCalling 的组合爆炸
- 单一真相源原则：所有 UI 读 statusFlow/activityFlow，写入必经纯函数 FSM（SessionStateFSM）
- 反面教材考古：SessionStatusManager（按 handler 维护状态）为何被移除——分布式一致性思维在单进程内的投影
- 对照：Redux store / 事件溯源 / 不可变状态转移

## 本项目锚点（待填充时重新验证）
- `data/repository/SessionStateService.kt:143-146` — MutableStateFlow → statusFlow 暴露
- SessionStateFSM 纯函数转移逻辑所在文件
- 根 AGENTS.md「承重架构规则」第一条原文

## 观察任务预告
- 画出 SessionState 的完整状态转移图
- 找出 FSM 中一个非法转移被拒绝的分支，说明它防住了什么 UI bug
