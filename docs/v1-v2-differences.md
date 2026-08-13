# OpenCode V1 vs V2 — 功能差异与客户端适配清单

> 调研日期：2026-08-13 · 来源：官方文档（opencode.ai / open-code.ai）+ GitHub issues + 本机 1.18.18 实测 + 本地 `opencode-api-reference.md`
> 关联：backlog #83（版本误判修复）、#84（适配清单）、#85（UI 隐藏落地）

## 结论摘要

V1（1.18.x，npm `opencode-ai`）与 V2（2.x beta，npm `@opencode-ai/cli`）是**三重断裂**：

1. **路径前缀**：V1 无前缀（`/session`）→ V2 全部 `/api/` 前缀（`/api/session`）
2. **核心机制**：prompt 从 `prompt_async`(204 fire-and-forget) 变为 `prompt`(200 返回 Inbox)；abort 变 interrupt（+`continue` 参数）；revert 变 staged 三步流程
3. **SSE 格式**：V1 `{id, type, properties}` → V2 `{id, event, data}`（data 为二次 JSON 字符串）

⚠️ **V1 1.18.18 是过渡形态**：同时暴露 `/global/health` 与 `/api/health`（实测 `/api/health` 返回 `{"healthy":true}` 无 version 字段）→ 曾导致 OC Beacon 误判 V2（已修复，见 #83）。

## 功能差异清单

| 功能/能力 | V1 (1.18.x) | V2 (2.x beta) | 客户端处理 | 状态 |
|-----------|-------------|---------------|-----------|------|
| 健康检查 | `GET /global/health`（无认证）`{healthy, version}` | `GET /api/health`（需认证）`{healthy, version, pid}` | 探测按 version 交叉验证 | ✅ 已修复 |
| SSE 事件流 | `GET /event` 或 `/global/event`，`data.type` 标识 | `GET /api/event`，`data.event` + `data.data` 二次 JSON | 双客户端分流 | ✅ 已适配 |
| 发送消息 | `POST /session/{id}/prompt_async` → 204 | `POST /api/session/{id}/prompt` → 200 Inbox | 双客户端分流 | ✅ 已适配 |
| 中断执行 | `POST /session/{id}/abort` → boolean | `POST /api/session/{id}/interrupt` → 204 + `?continue` | 双客户端分流 | ✅ 已适配 |
| **任务（后台化）** | 仅实验性 `/experimental/session/{id}/background`（需 `experimentalBackgroundSubagents` flag） | 正式 `POST /api/session/{id}/background` → 204 | **V1 下隐藏/降级** | ⏳ #85 |
| 配置读写 | `GET/PATCH /config` 可写 + `GET /config/providers` | `GET /api/config` **只读**（无 PATCH）；`/api/provider` + `/api/model` 拆分 | V2 下禁用配置编辑 | ⏳ #85 |
| Provider 认证 | `GET /provider/auth` + `POST /provider/{id}/oauth/authorize` + `/callback`（两步） | 重构为 integration：`GET /api/integration` + `POST /api/integration/{id}/connect/*`（多步异步轮询） | 设置页认证流程适配 | ⏳ #84 |
| Permission | `GET /permission` + `POST /permission/{id}/reply` | `GET /api/permission/request` + 会话级 permission + saved permissions | 双客户端分流 | ✅ 已适配 |
| 回退 revert | `POST /session/{id}/revert` + `/unrevert`（直接执行） | staged：`/revert/stage` → `/revert/commit` 或 `/revert/clear` | 双客户端分流 | ✅ 已适配 |
| **Todo** | `GET /session/{id}/todo` | **移除**（form/question 替代） | **V2 下隐藏 Todo 入口** | ⏳ #85 |
| Form 系统 | 无 | `GET/POST /api/session/{id}/form` + reply/state | V2 新增能力，暂不展示 | 评估中 |
| Inbox/Steering | 无 | `/api/session/{id}/inbox` + steer + queue | V2 新增能力 | 评估中 |
| Shell | `POST /session/{id}/shell`（会话级） | 会话级 + **独立** `/api/shell` + `/api/shell/{id}/output` | 双客户端分流 | ✅ 已适配 |
| 文件系统 | `GET /file`, `/file/content`, `/find`, `/find/file`, `/find/symbol` | `GET /api/fs/read/*`, `/api/fs/list`, `/api/fs/find` | 双客户端分流 | ✅ 已适配 |
| VCS | `GET /vcs`, `/vcs/status`, `/vcs/diff` + `/path` | `GET /api/vcs*` + `GET /api/location` | 双客户端分流 | ✅ 已适配 |
| Session 状态 | `GET /session/status` | 无直接等价（`/api/session/active` + SSE 替代） | V2 用 activeSessions | ✅ 已适配 |
| 压缩 summarize | `POST /session/{id}/summarize` | `POST /api/session/{id}/compact` | 双客户端分流 | ✅ 已适配 |
| 凭据管理 | `PUT/DELETE /auth/{id}` | `PATCH/DELETE /api/credential/{id}` | 双客户端分流 | ✅ 已适配 |
| 分享 share | `POST/DELETE /session/{id}/share` | V2 无 share 字段/端点（新版源码已有 `/api/session/:id/share`） | V2 下隐藏 Share 菜单 | ✅ #78 |
| TUI 控制 | 13 个 `/tui/*` 端点 | **移除** | App 无依赖 | ✅ |
| Web 搜索 | 无 | `GET /api/websearch/provider` + `POST /api/websearch` | V2 新增能力 | 评估中 |
| Skill 激活 | 仅 `GET /skill` 列表 | `GET /api/skill` + `POST /api/session/{id}/skill` 会话内激活 | V2 新增能力 | 评估中 |
| Plugin 列表 | 无 | `GET /api/plugin` | V2 新增能力 | 评估中 |
| OpenAPI spec | `GET /doc` | 无 | — | — |
| 销毁实例 | `POST /instance/dispose` | `POST /api/service/stop` | 双客户端分流 | ✅ 已适配 |

## 认证对比

| 维度 | V1 (1.18.x) | V2 (2.x beta) |
|------|-------------|---------------|
| 方式 | HTTP Basic Auth | HTTP Basic Auth（相同机制） |
| 密码环境变量 | `OPENCODE_SERVER_PASSWORD` | `OPENCODE_SERVER_PASSWORD` |
| 用户名 | `opencode`（可用 `OPENCODE_SERVER_USERNAME` 覆盖） | [推断] 相同（置信度：中） |
| Header | `Authorization: Basic base64(opencode:password)` | 相同 |
| Global 端点 | `/global/*` 无认证 | `/api/*` 需认证 |
| 未设密码 | 无认证保护 | 输出 Warning "server is unsecured" |

## 配置差异（服务端侧，客户端只读）

| 维度 | V1 | V2 |
|------|----|----|
| 配置文件 | `opencode.json(c)` / `config.json` 可读写 | `opencode.json(c)` 只读；`config.json` 不识别 |
| TUI 配置 | 分层 `tui.json(c)` | 单一 `cli.json`（自动迁移） |
| Plugin 目录 | `.opencode/plugin/` | `.opencode/plugins/`（V1 插件不兼容） |
| 权限模型 | 按工具分组（`permission.bash.*`） | 有序数组 `permissions: [{action, resource, effect}]`；`bash`→`shell`, `task`→`subagent`, `write/patch`→`edit` |
| MCP 配置 | `mcp.{name}` 顶层 | `mcp.servers.{name}`；`enabled`→`disabled`(反转)；timeout 拆 catalog/execution |

## 任务系统（后台化，专项）

- **V2**：`POST /api/session/{sessionID}/background` → 204，将前台阻塞的可后台化工具移入后台观察（不是"创建后台任务"，是"转为后台继续运行"）；完成时向主会话注入 `POST /api/session/{id}/synthetic` 合成消息（App 已适配，见 backlog #67）
- **V1**：仅实验性端点 `/experimental/session/{id}/background`（需 flag，否则恒 false）；无正式后台系统；替代方案 = `prompt_async` 异步启动 + SSE 监听
- **客户端决策**：V1 连接下隐藏后台化入口（或降级为仅异步模式）

## serve 行为对比

| 维度 | V1 | V2 |
|------|----|----|
| 命令 | `opencode serve` | `opencode2 serve` |
| 默认端口 | 4096 | [推断] 4199（置信度：中，AGENTS.md 记载） |
| `--service` | 无 | 有（后台守护进程模式） |
| 未知路径 | **SPA fallback 返回 HTML**（实测确认，本 bug 根源） | [未确认] |
| `--hostname` | 默认 127.0.0.1，支持 `::` | [推断] 相同 |

## 参考资料

- V1 文档：https://open-code.ai/en/docs/server
- V2 迁移指南：https://opencode.ai/v2/docs/migrate-v1
- V2 API 参考：https://opencode.ai/v2/docs/api
- 本地 V2 调研：`docs/opencode-api-reference.md`（129 端点 + 52 SSE 事件）
- 本机实测：opencode-ai 1.18.18 隔离运行（XDG_DATA_HOME/XDG_CONFIG_HOME），全端点扫描
