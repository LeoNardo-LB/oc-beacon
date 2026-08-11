## OC Beacon 0.3.0-beta.5 — 2026-08-11

> **修复 V2 连接稳定性与 subagent 卡片跳转——全面适配 OpenCode V2 API**

### Fixed

- **subagent 卡片恢复跳转子会话**：V2 工具名（subagent）正确映射，点击已完成 subagent 卡片可跳转到对应子会话页面（之前只能展开基本信息）
- **修复退出进行中会话的报错**：V2 SSE 心跳逻辑修正——活跃会话不再每 40 秒误判超时断连重连
- **修复会话列表报错**：V2 响应格式（`{location,data}` 包裹）兼容解析，不再出现 JsonConvert 错误
- **V2 tool 数据完整映射**：工具输入/输出/元数据（含子会话 ID）正确保留，tool 卡片渲染完整
- **V2 事件流正确解析**：`session.reasoning.delta`/`session.text.delta` 等增量事件正确映射到消息流式管线


---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.0-beta.4...v0.3.0-beta.5)
