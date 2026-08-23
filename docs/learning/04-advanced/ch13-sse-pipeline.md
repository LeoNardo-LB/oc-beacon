# ch13 · SSE 流式管线 —— token 从网络到像素的旅程

> 状态：⬜ 待生成 ｜ 前置：ch05, ch11
> 本章解决：SSE 事件流全链路（网络→分发→状态→UI→滚动）；48ms 批处理等铁律背后的性能因果链。
> 填充方式：对 AI 说「填充 docs/learning 的 ch13」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- SSE 协议本身（对照 WebSocket：单向 vs 双向、自动重连语义）
- 全链路走读：OkHttp engine → SseConnectionManager → EventDispatcher → SessionStateService → ChatScreen 渲染
- 48ms token 批处理：为什么 >20 token/s 时"每 token 取消定时器"会饿死 flush
- 高度补偿只作用于流式消息的原因；isAtBottom 自愈机制
- Ktor 明确使用 OkHttp engine 的历史原因（SSE 流式正确性）

## 本项目锚点（待填充时重新验证）
- `service/SseConnectionManager.kt` — 连接生命周期与 Flow 桥接
- `data/repository/EventDispatcher.kt` — 事件分发表
- `docs/research/sse-scroll-stability-iron-laws.md` — 四条铁律回归历史
- 根 AGENTS.md「SSE 滚动稳定性」小节原文

## 观察任务预告
- 画一条 message.delta 事件从 socket 到屏幕的完整时序图
- 从铁律文档里挑一次回归 bug，复述根因与修复
