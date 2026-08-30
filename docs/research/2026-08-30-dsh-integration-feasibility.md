# DSH（DeepSeek Harness）原生接入可行性调研（backlog #268）

> 状态：**调研主体完成**（2026-08-30 合成）——DSH 接口面（§4-§5）、oc-beacon 能力盘点（§3）、差距矩阵（§6）均已定稿；余探针 P-1..P-4 与需求拆卡（§8）。
> 用户裁决（2026-08-30 拷问轮）：**原生接入**（非 WebView）· ServerConfig 增服务器类型 opencodeV1 / opencodeV2 / dsh（暂定），底层各自实现、对上层透明 · **不分期**，先全面盘点既有功能再对照 DSH 出差距 · 调研先行，实现期用探针测试定数据结构。
> 背景：用户已从局域网访问 DSH Web（0.1.1-rc.2，绑 0.0.0.0:3080），crypto polyfill 插件收尾见 /tmp/dsh-handoff-crypto-polyfill-20260830.md（会话外文档）。

## 1. 结论速览

- **可行**，但接入形态与 opencode 完全不同构：REST+SSE → **JSON-RPC POST 信封 + 双下行 WebSocket**；历史无消息端点，需**客户端 fold 事件日志**（预估最大成本项）。
- **DSH 无任何鉴权**——安全完全靠拓扑（绑址/Host 信任栅栏/特权面 loopback 钉死）。oc-beacon 侧「带凭据接入」的类比如 basic auth **不成立**，安全让渡给部署拓扑（USB adb reverse 或 trustedHosts+组网）。
- adb reverse（真机 USB）路径下连接源即宿主 loopback，**特权面可能天然可用**（待探针）；直连 LAN 则受 Host 栅栏 + 特权面缺口约束。
- 风险集中：0.1.1-rc.2 pre-release 无兼容承诺 → **必须锁版本 + 版本探测**；WS 重连/keepalive 细节未知；无鉴权 = 可达者可在宿主执行 bash。

## 2. 调研方法与信源

- DSH 源码 checkout：`/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/`（npm 全局包，0.1.1-rc.2）。
- 运行实例只读探针：`http://127.0.0.1:3080`（GET 探测，无变更性请求）。
- oc-beacon 侧：源码 + `docs/architecture.md` / `docs/v1-v2-differences.md` / `docs/opencode-api-reference-v1.md`（§3 依据；调研代理产出已归档 journal 附录 A）。
- 证据标注：`文件:行号`；「推测」= 未实证。

## 3. oc-beacon 既有能力—接口依赖盘点（已定稿，2026-08-30）

> 由调研代理产出（feature inventory：能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 差异；附 mutation 全清单）。
> **已定稿（2026-08-30 合成）**：本节为压缩蒸馏版（源清单 ~32KB：~70 REST 调用形态、SSE 事件→handler 映射、6 能力域、38 项 mutation）。REST 端点全表与 SSE 事件全表**不在此重复**，见附录 A §1-§2；**全量清单原样归档于 `docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md` 附录 A**。证据为 oc-beacon 源码 `文件:行号`；【推测】= 未实证。

**架构要点**（接入改造的直接对象）：

- 7 个域接口（Session/Message/System/File/Terminal/Provider/Shell）→ `*ApiImpl.pick(conn)` 路由 → `V1ApiClient`/`V2ApiClient` 双实现（附录 A §0）；V1/V2 由 `ApiVersionDetector` 双探 health + knownVersion 排序判定并持久化（api/version/ApiVersionDetector.kt:59-128、repo/ServerDataStore.kt:138-180）。
- SSE 双客户端（V1 `GET /global/event` 信封帧 / V2 `GET /api/event` 平铺 + seq gap）→ `EventDispatcher` O(1) 路由 → 7 handler + `SessionStateService` FSM（repo/EventDispatcher.kt:90-145、SessionStateService.kt:362-363）。
- 终端数据面走 WebSocket PTY（`/pty/{id}/connect`），非 SSE。
- 本地三件套：Room v4（消息缓存/日志/冷存/堆积队列）、DataStore（服务器配置/设置/红点水位线/标签/草稿）、进程内存七 handler 缓存（附录 A §4）。

### 3.1 服务器管理

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 服务器增删改（URL/用户名/密码卡片） | —（纯本地） | — | ServerDataStore（DataStore JSON 列表，ServerDataStore.kt:63-136） | 认证均 Basic；DSH 接入 = 新增服务器类型 dsh（用户裁决），ServerConfig 增 type 维度 |
| 连接/连通探测 | `/global/health` 或 `/api/health` 双探 + `GET /file` 目录探测 | server.connected/heartbeat 存活 | ServerConfig（apiVersion/serverVersion 持久化） | knownVersion 排序 + pid 交叉验证（ApiVersionDetector.kt:65-69）；**DSH 无 /health**（§4）→ 探测依据须换 |
| 连接生命周期（FGS 保活/断线重连/断连对账） | fetchSessionStatus（/session/status、/api/session/active） | 重连后全量重订阅 | StreamingOwnershipRegistry | 指数退避 1s→30s×2.0（SseConnectionManager.kt:34-36/359）；5 次读超时 → 5min 冷却（SseClient.kt:299-351） |
| 事件通知（完成/错误/权限/后台任务） | — | MessageUpdated(completed)、SessionError、PermissionAsked、ShellJobStarted/Ended | AppNotificationManager/SessionNotificationCoordinator | 活跃会话抑制 |

### 3.2 会话列表

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 会话加载（多项目聚合+下拉刷新+分页） | `GET /project` × `GET /session`（search/cursor/limit，V1ApiClient.kt:110-119） | session.created/updated/deleted 实时增删 | EventDispatcher.sessions 内存缓存 | 服务端 search + 客户端过滤双层（SessionListViewModel.kt:591/598，#100） |
| 收藏/标签/分类 | —（纯本地） | — | SessionTagStore（按 serverId 隔离，SessionTagStore.kt:46-47） | 「置顶」无独立实现；跨服务器收藏为死路由（附录 A §7-4） |
| 状态显示（idle/busy/retry + Waiting/Streaming/ToolCalling） | fetchSessionStatus 恢复循环（V1 /session/status；V2 /api/session/active） | session.status/idle/error + session.next.*（execution/tool/step） | SessionStateService FSM（单一真相源）+ 24h staleness 清扫 | 状态为 REST+SSE 双源恢复；DSH 侧无状态端点时须事件派生 |
| 未读红点 | — | MessageUpdated(assistant completed)、SessionError | UnreadStateStore 水位线（同步落盘）+ UnreadBadgeService | maxCompleted 只增不减（架构铁律） |
| 目录树/项目 | listProjects、listDirectory（目录浏览） | — | 展开路径内存态 | DSH workspace 域方法面未知（§4） |
| 会话操作（删除含批量/重命名/导入） | DELETE、PATCH→rename、POST /session/import | SessionDeleted 级联清理 8 handler（EventDispatcher.kt:272-287） | 红点条目清理 | V2 删除实测支持；rename 走专用端点 |
| MCP 管理 | GET /mcp、POST /mcp/{name}/connect、disconnect（V1ApiClient.kt:534-550） | mcp.tools.changed | — | — |
| 堆积消息（断线排队+手动继续） | prompt 恢复后重发 | — | PendingMessageEntity（Room v4，PendingMessageRepositoryImpl.kt:21） | — |

### 3.3 聊天（核心域）

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 发送消息（文本+图片附件） | V1 prompt_async 204（无 admission）；V2 prompt 200 Inbox admission 双契约降级（V2ApiClient.kt:465-558） | 悲观渲染：等 SSE 回显 MessageUpdated | DraftDataStore（草稿按会话持久） | V2 附件平铺顶层 files；发送前 switchModel/switchAgent |
| 流式渲染（token/reasoning） | — | message.part.delta/part.updated（48ms 批处理+高度补偿铁律） | MessageEventHandler 内存 parts 缓存 + Room 回读种子 | 双版本同构管线；delta 语义是 DSH 同构点（§4） |
| 中断 | POST abort（V1）/interrupt（V2） | execution/session.status 回落 Idle | — | §4 确认 DSH cancel/interrupt 存在 |
| 撤销/重做（revert） | /revert、/unrevert（V2 staged 三步，V2ApiClient.kt:1088-1110） | message.removed、session.updated(revert) | pruneRevertedMessages（清缓存防闪烁）、RevertedDraftPayload | 撤回草稿提回输入框 |
| 压缩 compact | summarize（V1）/compact（V2） | V2 compaction.started/delta/ended/failed + SessionCompacted | SessionNextHandler.compactionState | FSM Compacting 态 |
| fork 分叉 | POST /session/{id}/fork（V2 实测 400 明确报错，V2ApiClient.kt:1121-1127） | session.created | — | — |
| 分享/导出 | share POST/DELETE（V2 无→隐藏 #78）；导出 = session+message 流式 JSON（OpenCodeShared.kt:49-94） | — | — | DSH 另有 session.export ZIP（§4），格式不同 |
| 重试显示 | — | session.next.retried → retryState | — | 客户端不主动重发，纯服务器状态呈现 |
| 工具调用卡 | Task 输出经 TaskOutputFetch 拉取 | session.tool.*、session.next.tool.*、tool.progress（子会话 id 回写 #216） | ToolCacheDelegate/ToolSnapshotGrouper | DSH 子会话对应 subagent 域【推测】 |
| 权限卡（once/always/reject+留言） | POST /permission/{id}/reply（V2 会话级+legacy 降级，V2ApiClient.kt:864-907）+ REST hydrate GET /permission | permission.asked/replied | PermissionEventHandler + PermissionAutoApprover（本地规则） | always 规则本地保存自动批准 |
| 提问卡（question/form） | reply/reject（V2 question.v2→form 双契约降级，V2ApiClient.kt:923-1009） | question.asked/replied/rejected；form.created(kind=question) | QuestionEventHandler + QuestionAnswerStore | #130/#250 答案双契约 |
| Todo 面板 | GET /session/{id}/todo（V2 beta 404 记忆→入口隐藏 #85） | todo.updated | MiscEventHandler.todos | — |
| 后台化+任务角标 | POST /api/session/{id}/background（V1 无→隐藏，ChatScreen.kt:692） | session.next.synthetic、session.shell.started/ended | ShellJobsStore + TaskDelegate 轮询 | DSH subagent 域对应关系未知（§4） |
| 会话级 shell 命令 | POST /session/{id}/shell（V1 同步 10min+409 退避 #256；V2 异步） | 消息事件流呈现 | — | #250 |
| 模型/agent/variant 选择 | V2 switchModel/switchAgent + GET /api/agent（嵌套契约 400 教训，V2ApiClient.kt:565-673）；V1 进 prompt body | session.next.model/agent.switched | ModelConfigDelegate 本地默认值 | DSH agentPreset ∈ 特权面（§5） |
| 上下文用量/费用 | — | session.usage.updated（V2）；V1 靠消息 tokens 推算【推测】 | ContextStats | — |
| 斜杠命令 | GET /command + 客户端命令映射 REST | — | SlashCommandRegistry（SlashCommandRegistry.kt:27-29） | DSH skill 域语义映射未知 |
| 消息删除 | DELETE /session/{id}/message/{mid}（part 删除 V1 专属） | message.removed | — | 日志派生态删除语义未知 |
| 历史分页 | message?limit&before/cursor 双向游标（V1ApiClient.kt:326-359） | — | MessageStore/Room 缓存（ChatRepositoryImpl.kt:67） | V2 cursor base64url 格式；DSH 无消息端点→fold |

### 3.4 Workspace（文件/Git/终端）

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 文件树（懒加载/过滤/LRU） | GET /file?path（V2 /api/fs/list，V2ApiClient.kt:1530-1563） | file.watcher.updated/file.edited（通知级） | WorkspaceViewModel LRU 缓存（WorkspaceViewModel.kt:46） | V2 {path,type}→name/absolute 推导防 key 崩溃 |
| 文件查看（高亮/Markdown/PDF/diff 视图） | GET /file/content（V2 /api/fs/read/{通配}，V2ApiClient.kt:1486-1509）；diff 走 /vcs/diff | — | AnnotationManager（本地批注）、ToolSnapshotCache | — |
| 文件/文本搜索 | GET /find/file、GET /find（V2 /api/fs/find，V2ApiClient.kt:1453-1519） | — | — | V1 大目录空结果客户端回退（#248） |
| Git 面板（分支/变更/diff） | GET /vcs、/vcs/status、/vcs/diff（V2ApiClient.kt:1579-1617） | vcs.branch.updated | prefetchGitCount 内存 | — |
| 终端 PTY（多标签/resize/软键盘） | POST/PUT/DELETE /pty、GET /pty/shells（TerminalApi.kt:12-51） | pty.created/updated/deleted（日志级） | ServerTerminalRegistry | 数据面走 WS /pty/{id}/connect（非 SSE） |
| 后台 shell 卡 | GET /api/shell*、DELETE /api/shell/{id}（V2ApiClient.kt:302-372） | session.shell.started/ended | ShellJobsStore | V2 专属；V1 常量降级 |

### 3.5 设置

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 服务器设置（默认模型/agent/provider 启停） | PATCH /config、/global/config（V1ApiClient.kt:680-694；V2 仍发 PATCH，官方只读 #85） | — | ServerConfig | V2 UI 禁用未确证【推测】；写配置副作用 = 销毁实例 |
| Provider 认证（API key/OAuth 两步/删凭据） | PUT/DELETE /auth/{id}、oauth authorize、callback（V1ApiClient.kt:594-666）；V2 credential + service/stop | — | — | V2 OAuth 发起未适配（#84）；DSH credentials ∈ 特权面（§5） |
| 模型可见性过滤 | —（纯本地） | — | SettingsDataStore.hiddenModels（ServerSettingsViewModel.kt:452-455） | — |
| 权限自动批准 | （回复动作复用权限卡端点） | PermissionAsked→maybeAutoApprove | 规则存本地 SettingsDataStore | 规则空 = 关闭 |
| 本地偏好（主题/语言/字号/通知/终端字号/重连…） | —（纯本地） | — | SettingsDataStore（SettingsDataStore.kt:33-41） | 15 语言 |
| 更新检查 | GitHub API（非 opencode 服务器） | installation.updated 仅日志级 | UpdateRepository | — |

### 3.6 诊断

| 能力 | REST 端点要点 | SSE 事件要点 | 本地依赖 | 判定相关注记 |
|---|---|---|---|---|
| 应用内日志（查看/导出/清空） | — | — | LogEntity（Room）+ Channel→SQLite | AppLogger |
| 错误上报 | github.com Device Flow（非 opencode） | — | github_report DataStore（GitHubTokenStore.kt:18） | — |
| 事件流观测（计数/gap 检测） | — | 全事件流 | gapDetected（SessionNextHandler） | V2 seq gap；V1 无 seq；DSH 无重连游标（R3） |

### 3.7 mutation 全清单（38 项浓缩——DSH 特权方法风险集中区）

> oc-beacon 侧全部经 Basic Auth（V1 /global/* 服务器侧无认证层）；DSH 侧预判依据 §4/§5。【推测】= 对应面未定稿。编号对应附录 A §6。

| 域 | mutation（编号） | opencode 接口 | DSH 对应面预判 | 特权风险 |
|---|---|---|---|---|
| 会话与消息 | 创建会话（1） | POST /session（V2 /api/session） | 待探针（或由 session.prompt 隐式创建【推测】） | 低 |
| 会话与消息 | 删除/重命名会话（2-3） | DELETE /session/{id}、PATCH→rename | 待探针（session 域 12 方法仅 4 个定稿命名） | 低-中（破坏性，作用域单会话） |
| 会话与消息 | 中断（4） | POST abort/interrupt | session.cancel（§4）→ 需适配 | 低 |
| 会话与消息 | 发送消息（5） | POST prompt_async/prompt | session.prompt（§4）→ 需适配 | 高（驱动 agent 在宿主执行工具/bash） |
| 会话与消息 | 切模型/切 agent（6-7，V2） | POST /api/session/{id}/model、/agent | 特权面受限【推测：agentPreset/llm 域；管理面 loopback 钉死】 | 中（模型路由/密钥邻域） |
| 会话与消息 | 斜杠命令执行（8） | POST /session/{id}/command | 待探针【推测：skill 域】 | 中 |
| 会话与消息 | 会话内 shell（9） | POST /session/{id}/shell | 缺失（§4 无 shell 域） | 高（任意命令） |
| 会话与消息 | 转后台（10，V2） | POST /api/session/{id}/background | 待探针【推测：subagent 域】 | 低 |
| 会话与消息 | share 创建/取消（11-12） | POST、DELETE /session/{id}/share | 缺失【推测：DSH 无公开分享概念】 | 低 |
| 会话与消息 | 压缩（13） | POST summarize/compact | 待探针 | 低 |
| 会话与消息 | revert/unrevert（14-15） | POST /revert、/unrevert（V2 staged） | 待探针（日志派生态回退语义未知） | 中（改写历史） |
| 会话与消息 | fork（16） | POST /session/{id}/fork | 待探针 | 低 |
| 会话与消息 | 导入+删消息/part（17） | POST /session/import；DELETE message/{mid}(/part) | 待探针 | 中 |
| 权限与提问 | 权限裁决/回答提问/拒绝（18-20，3 组 6 端点） | POST /permission、/question reply/reject（V2 会话级+降级） | **POST /api/respond**（§4 明确回程）→ 需适配 | 高（裁决 = 放行宿主操作） |
| PTY/终端 | 创建/resize/删除/WS 交互（21-24） | POST/PUT/DELETE /pty、WS /pty/{id}/connect | 缺失（§4 无 terminal 域；下行 WS 仅 events.mux/host 两条） | 最高（WS 交互 = 等效任意 shell）；DSH 无此面，但无鉴权下 agent bash 风险等价（§5） |
| MCP | 连接/断开（25-26） | POST /mcp/{name}/connect、disconnect | 缺失（§4 无 mcp 域） | 中 |
| Provider 认证/凭据 | OAuth 发起/回调（27-28） | POST /provider/{id}/oauth/authorize、callback | 待探针【推测：llm/credentials 域，形态未知】 | 密钥面（特权） |
| Provider 认证/凭据 | 写/删 API key（29-30） | PUT/DELETE /auth/{id}（V2 credential） | credentials 域（§5 栅栏 4）→ 特权面受限 | 密钥面（特权） |
| 配置/生命周期 | 写项目/全局配置（31-32） | PATCH /config、/global/config | settings 域（§5 栅栏 4）→ 特权面受限 | 高-最高（副作用销毁实例；全局版销毁所有实例） |
| 配置/生命周期 | dispose 实例（33-34） | POST /global/dispose、/instance/dispose（V2 /api/service/stop） | 缺失（§4 无生命周期域） | 高（销毁实例） |
| 后台 shell | 终止/超时（35-36） | DELETE /api/shell/{id}、PATCH timeout | 缺失（§4 无 shell 域） | 低 |
| 其他 | 文件只读声明（37）/客户端不调用端点（38） | /file、/api/fs/*（无写端点）；/global/upgrade、/tui/* 不调用 | workspace 域待探针；后者不适用 | —（只读/不适用） |

> 全量 38 项逐条端点与证据：journal 附录 A §6。

## 4. DSH 对外接口面（已定稿）

**无 REST API、无 SSE。** 对外面：

| 面 | 形态 | 说明 |
|---|---|---|
| RPC | `POST /api/<method>`，JSON-RPC 信封 | 约 45 方法：session.list/history/prompt/cancel 等 12 + subagent/host/workspace/skill/agentPreset/goal/settings/credentials/llm 域 |
| 审批/提问回程 | `POST /api/respond` | 应答审批请求/提问（opencode 无此概念，权限审批在 oc-beacon 有对应 UI 域） |
| 事件流 | **两条下行 WebSocket**：`/api/events.mux`、`/api/events.host` | 普通 GET = 426；替代 opencode 的 SSE 全局事件流 |
| 导出 | `GET /api/session.export` | 会话日志 ZIP 下载 |
| UI | `GET /`（SPA） | 原生接入不消费 |

- 无 `/health` `/docs` `/openapi.json`；业务错误**恒 HTTP 200 + result.error 闭集错误码**（与 opencode HTTP 状态码语义不同，错误映射层要专门做）。
- 请求关联：request 关联 id；prompt 支持 text+image；cancel/interrupt 存在；流式 delta 存在（同构点）。
- 证据：`dsh-host-apiproxy/lib/types/api/{rpc,sessions,events,downloads}.d.ts`、`fetch/handler.js:180-235`、`dsh-client-connection/lib/types/api-path.d.ts:7-11`、`lib/index.js:539-540`。

## 5. 安全控制分析与 oc-beacon 部署含义（用户第 10 点的答案）

**DSH 无鉴权层**（无 token/basic auth/401 路径；README:21 "No TLS, auth, or origin policy"）。安全是四道栅栏：

1. **默认绑 127.0.0.1**（CLI 拒绝 `--host 0.0.0.0`；本机实例经 cordis.patch.yml 强绑 0.0.0.0:3080）。
2. **/api Host 信任栅栏**：Host 须 loopback 或 trustedHosts，否则裸 403（实测伪造 Host → 403）。——handoff 里 tailscale 路线「页面 200 / API 403」即此栅栏，**不是鉴权**。
3. POST 须 `application/json`（否则 415）。
4. **特权面 loopback 钉死**：settings / credentials / host.openPath / agentPreset 管理仅 loopback 可调。

**对 oc-beacon 的含义**：

- **客户端无法为无鉴权服务器加鉴权**——「在 oc-beacon 解决」的答案是否定的；安全只能由部署拓扑提供。
- **USB + adb reverse 是最优部署**：设备侧 `127.0.0.1:3080` → 宿主 loopback，连接源即本机，栅栏 2/4 天然通过（Host 头客户端置 `127.0.0.1:3080` 即可）——**推测**，探针验证项 P-1。此路径下特权面完整可用，且不把 DSH 暴露给网络。
- 直连 LAN（192.168.x.x）：受栅栏 2（须 DSH 配 trustedHosts）+ 栅栏 4（特权面缺口）。功能子集 = 非特权 RPC + 会话/事件面。
- 已知事实（handoff 实证）：无鉴权 = 可达者可在宿主执行 bash；tailnet/ACL 是唯一网络闸门。
- `crypto.randomUUID`（SecureContext）是**浏览器侧**问题：原生接入不存在；仅当未来做 WebView 嵌 UI 才需 polyfill（已裁决不做 WebView）。

## 6. 差距矩阵（已定稿，2026-08-30）

> 逐能力：oc-beacon 功能 → opencode 接口（REST/SSE/WS-pty） → DSH 对应 → 判定 ∈ {同构, 需适配, 缺失, 特权面受限, 待探针}。
> 判定规则：DSH §4 无对应方法/域 → **缺失**（不发明不存在的 API）；语义相似但形态不同（SSE→WS、REST→RPC 信封、消息 REST→日志 fold）→ **需适配**；落在特权面（settings / credentials / host.openPath / agentPreset 管理，§5 栅栏 4）→ **特权面受限**；两侧均未定稿/未实证 → **待探针**。DSH 侧词汇表仅限 §4 定稿面：`session.list/history/prompt/cancel`（session 域 12 方法中已命名 4 个）、`POST /api/respond`、`/api/events.mux` + `/api/events.host` 双 WS、`GET /api/session.export`，及 subagent/host/workspace/skill/agentPreset/goal/settings/credentials/llm 域名。

**接入形态总差异**（各行判定的共同前提）：

1. 无 REST、无 SSE → 全部改走 `POST /api/<method>` JSON-RPC 信封 + 双下行 WS（events.mux/events.host；普通 GET = 426）。
2. 业务错误**恒 HTTP 200 + result.error 闭集错误码** → oc-beacon 的 HTTP 状态码错误映射层须重做。
3. 历史无消息端点 → **客户端 fold 48 种 SessionEvent**（R5，预估最大成本项）。
4. **无鉴权**（§5）→ 安全让渡部署拓扑；特权面 loopback 钉死。
5. 权限审批回程走 **`POST /api/respond`**（opencode 为 REST reply 端点）。

### 6.1 服务器管理

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 服务器增删改（卡片/URL/凭据） | —（纯本地，ServerDataStore.kt:63-136） | 不触服务器面；ServerConfig 增 dsh 类型（用户裁决） | 同构 |
| 连接/连通探测 | GET /global/health、/api/health 双探 + 目录探测（ApiVersionDetector.kt:59-128） | 无 /health（§4 明确）→ 以首个 RPC（session.list）成功为存活信号；版本锁定依据 R1 | 需适配 |
| 断连窗口对账 | SSE 全量重订阅 + fetchSessionStatus 恢复循环（SseConnectionManager.kt:34-36/359） | session.history fold 对账（R3 处置路径已定） | 需适配 |
| WS 重连/keepalive/读超时冷却 | SSE 40s 心跳 + 5 次超时 5min 冷却（SseClient.kt:299-351） | DSH 双 WS 重连/keepalive 语义未定稿（R2，探针 P-2） | 待探针 |
| 事件通知（系统通知/活跃抑制） | SSE MessageUpdated/SessionError/PermissionAsked/ShellJob* | events.mux WS 事件驱动；事件名→领域事件映射待 P-4 试样 | 需适配 |

### 6.2 会话列表

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 会话列表加载/搜索/分页 | GET /session（search/cursor/limit，V1ApiClient.kt:110-119） | session.list（§4 确认）；REST→RPC 信封，参数/分页形态未定稿 | 需适配 |
| 多项目聚合 | GET /project × 各目录会话 | §4 无 project 域；DSH 实例绑单 workspace【推测】 | 缺失 |
| 实时增删改缓存 | SSE session.created/updated/deleted（EventDispatcher.kt:272-287） | events.mux 对应事件（48 种 SessionEvent 内映射待 P-4） | 需适配 |
| 收藏/标签/分类 | —（纯本地，SessionTagStore.kt:46-47） | 不触服务器 | 同构 |
| 状态显示（FSM idle/busy/retry + 流式活动） | GET /session/status 或 /api/session/active + SSE status/idle/next.*（SessionStateService.kt:362-363） | 无状态 RPC（§4 未列）→ 状态由事件流 + history fold 派生 | 需适配 |
| 未读红点 | SSE MessageUpdated(completed)/SessionError + 水位线 | 事件面迁移；水位线逻辑纯本地保留 | 需适配 |
| 目录树/项目浏览 | GET /file?path、/project/current（V2ApiClient.kt:1530-1563） | workspace 域在 §4 有名无方法明细 | 待探针 |
| 会话操作（删/改名/导入） | DELETE、PATCH→rename、POST /session/import（V2ApiClient.kt:203-222/1132-1141） | session 域 12 方法仅 4 个定稿命名 → 覆盖与否待探针 | 待探针 |
| MCP 管理 | GET /mcp、POST /mcp/{name}/connect、disconnect（V1ApiClient.kt:534-550） | §4 域清单无 mcp | 缺失 |
| 堆积消息排队 | prompt 恢复重发 + PendingMessageEntity（Room v4） | 本地队列保留；重发走 session.prompt | 需适配 |

> 跨服务器收藏入口为死路由（附录 A §7-4，文档滞后于代码），不入矩阵。

### 6.3 聊天

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 发送消息（含图片附件） | POST prompt_async 204 / prompt 200 Inbox（V2ApiClient.kt:465-558） | session.prompt（text+image，§4 确认）；REST→RPC 信封 + 错误恒 200+result.error | 需适配 |
| 历史消息加载/分页 | GET .../message 双向游标（V1ApiClient.kt:326-359） | session.history + 客户端 fold 48 种 SessionEvent（R5，最大成本项） | 需适配 |
| 流式渲染（token/reasoning） | SSE message.part.delta/part.updated（48ms 管线） | events.mux WS delta（§4 列为同构点）；传输面 SSE→WS，48ms 批处理管线可保留 | 需适配 |
| 中断 | POST abort（V1）/interrupt（V2） | session.cancel（§4 确认） | 需适配 |
| 撤销/重做 revert | POST /revert、/unrevert（V2 staged 三步） | §4 未列 revert 类方法；日志派生态是否支持回退未定稿 | 待探针 |
| 压缩 compact | POST summarize/compact + compaction 事件 | §4 未列 compaction 方法；事件是否在 48 种内待 P-4 | 待探针 |
| fork 分叉 | POST /session/{id}/fork | §4 未列 | 待探针 |
| 分享/取消分享 | POST、DELETE /session/{id}/share（V2 无→#78 隐藏） | §4 无对应面【推测：DSH 无公开分享概念】 | 缺失 |
| 导出会话 | session+message 流式 JSON（OpenCodeShared.kt:49-94） | GET /api/session.export（ZIP，§4 确认）；格式 JSON 流→ZIP | 需适配 |
| 重试状态显示 | SSE session.next.retried | 48 种 SessionEvent 是否含 retry 语义待 P-4 | 待探针 |
| 工具调用卡 | SSE session.tool.* / session.next.tool.* + TaskOutputFetch | fold 事件派生；子会话 = subagent 域【推测】，跨流回写（#216）对应物未知 | 需适配 |
| 权限卡（once/always/reject） | POST /permission/{id}/reply + SSE permission.asked/replied（V1ApiClient.kt:428-447；V2ApiClient.kt:864-907） | 审批下发经 events WS、回程 **POST /api/respond**（§4 确认）；请求体形态待探针 | 需适配 |
| 提问卡（question/form） | POST /question/{id}/reply、/reject（V2 双契约降级） | 提问回程同走 /api/respond（§4）；问句下发事件形态待探针 | 需适配 |
| Todo 面板 | GET /session/{id}/todo + SSE todo.updated（V2 404→隐藏 #85） | §4 无 todo 域 | 缺失 |
| 后台化 + 任务角标 | POST /api/session/{id}/background + synthetic/shell 事件 | subagent 域（§4）语义对应关系未定稿 | 待探针 |
| 会话级 shell 命令 | POST /session/{id}/shell（V1 同步 10min/V2 异步） | §4 无 shell 域；bash 系 agent 内部行为而非客户端 API 面 | 缺失 |
| 模型/agent/variant 选择 | V2 switchModel/switchAgent + GET /api/agent（V2ApiClient.kt:565-673） | agentPreset 管理 ∈ 特权面（§5 栅栏 4）；llm 域覆盖度未定稿【推测】 | 特权面受限 |
| 上下文用量/费用 | SSE session.usage.updated（V2）；V1 消息 tokens 推算【推测】 | fold 用量事件派生；具体事件名待 P-4 | 需适配 |
| 斜杠命令 | GET /command + 本地命令注册（SlashCommandRegistry.kt:27-29） | skill 域（§4）；与 /command 语义映射【推测：部分对应】 | 待探针 |
| 消息/part 删除 | DELETE /session/{id}/message/{mid}（part V1 专属） | §4 未列；日志派生态删除语义未知 | 待探针 |

### 6.4 Workspace

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 文件树浏览 | GET /file?path（V2 /api/fs/list，V2ApiClient.kt:1530-1563） | workspace 域方法明细未定稿（§4 有名无方法） | 待探针 |
| 文件查看（高亮/diff 视图） | GET /file/content（V2 /api/fs/read/{通配}，V2ApiClient.kt:1486-1509） | workspace 域读方法未定稿；若无则缺失 | 待探针 |
| 文件名/文本搜索 | GET /find/file、/find（V2 /api/fs/find，V2ApiClient.kt:1453-1519） | workspace 域搜索方法未定稿 | 待探针 |
| Git 面板（分支/变更/diff） | GET /vcs、/vcs/status、/vcs/diff（V2ApiClient.kt:1579-1617） | §4 无 git/vcs 域；fold 仅能重建会话内文件变更事件，非仓库分支/工作区态【推测】 | 缺失 |
| 终端 PTY（多标签/resize） | POST/PUT/DELETE /pty + WS /pty/{id}/connect（TerminalApi.kt:12-51） | §4 无 terminal/pty 域；下行 WS 仅 events.mux/host 两条，无 PTY 数据流 | 缺失 |
| 后台 shell 卡 | GET /api/shell*、DELETE /api/shell/{id}（V2ApiClient.kt:302-372） | §4 无 shell 域 | 缺失 |

### 6.5 设置

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 服务器设置（默认模型/agent/provider 启停→写配置） | PATCH /config、/global/config（V1ApiClient.kt:680-694） | settings 域 ∈ 特权面（§5 栅栏 4，loopback 钉死）；LAN 直连不可用，adb reverse 路径待 P-1 | 特权面受限 |
| Provider 认证（API key/OAuth/删凭据） | PUT/DELETE /auth/{id}、oauth authorize、callback（V1ApiClient.kt:594-666） | credentials 域 ∈ 特权面；OAuth 两步流形态未知【推测：llm 域】 | 特权面受限 |
| 模型可见性过滤 | —（纯本地，SettingsDataStore） | 不触服务器 | 同构 |
| 权限自动批准规则 | 回复动作同权限卡 REST 端点 | 本地规则引擎保留；回复走 /api/respond | 需适配 |
| 本地偏好（主题/语言/通知…） | —（纯本地，SettingsDataStore.kt:33-41） | 不触服务器 | 同构 |
| 更新检查 | GitHub API（非 opencode 服务器） | 不触 DSH | 同构 |

### 6.6 诊断

| oc-beacon 功能 | opencode 接口 | DSH 对应 | 判定 |
|---|---|---|---|
| 应用内日志（查看/导出/清空） | —（Room LogEntity） | 不触服务器 | 同构 |
| 错误上报（GitHub Device Flow） | github.com API | 不触 DSH | 同构 |
| 事件流观测（计数/gap 检测） | 全事件流 + V2 seq gap（SseClientV2.kt:270-332） | 观测面改双 WS；DSH 无重连游标（R3）→ 客户端自管对账 | 需适配 |

### 6.7 判定统计

| 判定 | 条数 | 说明 |
|---|---|---|
| 同构 | 7 | 纯本地能力与不触 DSH 的能力 |
| 需适配 | 19 | 语义在、形态变（RPC 信封 / 双 WS / fold / 200+result.error） |
| 缺失 | 8 | §4 无对应域：project、mcp、share、todo、shell、git/vcs、PTY、后台 shell |
| 特权面受限 | 3 | settings 写、credentials、agentPreset/llm（§5 栅栏 4） |
| 待探针 | 13 | session 域未命名方法组、workspace 域明细、WS 重连语义、事件集映射 |
| **合计** | **50** | |

> 探针对应：待探针 13 条大多可由 P-2（WS 语义）与 P-4（session.history fold 试样 + session/workspace 方法面枚举）一次性收敛；特权面 3 条的可用性取决于部署拓扑（P-1 adb reverse）。全部探针见 §8。

## 7. 风险与未知项

| # | 风险/未知 | 处置 |
|---|---|---|
| R1 | 0.1.1-rc.2 pre-release，SESSION_FORMAT_VERSION=0 无迁移承诺，无 CHANGELOG | 锁版本 + 接入前版本探测（对齐 oc-beacon 既有 V1/V2 探测模式） |
| R2 | WS 重连/keepalive 语义未知（opencode SSE 重连+补漏管线不可平移） | 探针 P-2；必要时客户端自建重连+`session.history` 补漏 |
| R3 | cursor/since 重连游标未实现 → 断连期事件丢失 | 客户端 fold 全量历史对账（同 R2 处置） |
| R4 | trustedHosts 精确配置键未知 | 探针 P-3（直连 LAN 部署前） |
| R5 | 历史为日志派生态，客户端 fold 成本（48 种 SessionEvent） | 差距矩阵量化后定：fold 引擎工作量最大项 |
| R6 | 无鉴权暴露面 | 部署规范强制：默认只支持 USB adb reverse；LAN 直连须文档警示 |

## 8. 后续动作

1. ✅ §3/§6 填充（2026-08-30 合成完成；差距矩阵 50 行，判定统计见 §6.7）。
2. 探针测试清单：P-1 adb reverse 下栅栏 2/4 行为；P-2 WS 重连/keepalive；P-3 trustedHosts 配置键；P-4 session.history fold 试样（拉真实 session.jsonl 验证 48 事件覆盖）。
3. 差距矩阵 → 需求拆卡（服务器类型抽象 = 结构级先行，DI/NetworkModule 分派）。
