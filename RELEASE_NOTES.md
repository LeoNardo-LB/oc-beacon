## OC Beacon 0.3.0-beta.4 — 2026-08-11

> **支持 OpenCode V2 API——自动检测服务器版本，V1/V2 双 API 无缝切换**

### Added

- **OpenCode V2 API 全面支持**：连接服务器时自动探测 API 版本（V1 或 V2），后续所有请求自动路由到对应版本——用户无需关心服务器版本差异
- **版本徽章显示**：服务器卡片上显示检测到的 API 版本和服务器版本号（如 `API v2 · 2.0.1`）
- **V2 SSE 流式解析**：支持 V2 标准 SSE 帧格式（`event:` + `data:` 行），流式消息/工具调用/权限请求实时推送

### Changed

- **API 层架构重构**：V1 和 V2 各自独立实现（V1ApiClient + V2ApiClient），6 个 API 分发类变为纯策略选择层，上层业务代码零改动
- **V2 端点全覆盖**：72 个 API 方法 100% 覆盖 V2 路径，包括 Session（abort→interrupt）、Message（type 判别联合+content 数组）、Provider（credential 替代 auth）、File（/api/fs/*）、Terminal（/api/pty/*）等


---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.0-beta.3...v0.3.0-beta.4)
