# opencode V1（opencode-ai 1.18.18）shell 命令接口 — 源码系统性调研

- 日期：2026-08-28
- 调研对象：`opencode-ai@1.18.18`（npm，V1 产品线）。所有源码行号（除特别注明外）基于 GitHub `anomalyco/opencode` **tag `v1.18.18`**（commit `31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d`，2026-08-13，"release: v1.18.18"），本地工作副本 `/tmp/oc-v1-18-18`，路径相对 `packages/`。
- 关联文档：V2 方言报告 [2026-08-28-v2-shell-message-storage.md](2026-08-28-v2-shell-message-storage.md)；API 全集 [opencode-api-reference-v1.md](../opencode-api-reference-v1.md)；旧线索报告 [opencode-shell-message-persistence.md](opencode-shell-message-persistence.md)（**其 V1 存储结论有误，本报告 §Q2 勘误**）。

## 0. 结论先行

1. **路由**：`POST /session/{sessionID}/shell`，**无 /api 前缀**（V1 `root="/session"`；V2 beta 是 `/api/session/...`）。路由定义 `opencode/src/server/routes/instance/httpapi/groups/session.ts:29,98,356-368`；本地二进制内嵌 SDK 路由表 `yn="/session"` + `shell: ${yn}/:sessionID/shell` 同样命中。
2. **同步语义**：HTTP 请求**阻塞到 shell 命令执行完毕**才返回 200，响应体 = `{ info: assistant消息, parts: [bash tool part] }`（`SessionV1.WithParts`）。服务端对命令**没有超时**（长命令 = 无限挂起的 HTTP 请求）；会话忙时返回 **409 SessionBusyError**（不排队）。agent 正在跑的会话上 POST shell 也是直接 409。
3. **消息数据模型**：产生**消息对**——① user 消息（带 `synthetic: true` 的 text part `"The following tool was executed by the user"`）；② assistant 消息（`cost:0`、tokens 全 0、`parentID`=user 消息 id），其下挂 `type:"tool"`、`tool:"bash"` 的 tool part（`callID` 为裸 ULID；`state.status: running → completed`；`state.metadata.output` 流式增长；`state.output` = 全量输出；`title:""`）。**无独立 type:'shell' 消息类型**（那是 V2）。
4. **存储**：1.18.18 的消息/part 存 **SQLite**（`{XDG_DATA_HOME}/opencode/opencode.db`，WAL 模式，`message`/`part` 表 + JSON data 列），由持久事件投影写入。旧版 `storage/session/message/*.json` 布局只剩**迁移代码**（`opencode/src/storage/storage.ts:81-165`）。
5. **SSE**：shell 执行期间客户端在 `GET /event`（无 /api 前缀）上收到：`session.status(busy)` → `message.updated`(user) → `message.part.updated`(synthetic) → `message.updated`(assistant) → `message.part.updated`(tool running) → **`message.part.updated` × N（输出流式，`state.metadata.output` 全量替换）** → `message.updated`(completed) → `message.part.updated`(completed) → `session.status(idle)` + `session.idle`。
6. **LLM 上下文注入**：完成态 tool part 转成 AI SDK `tool-bash / output-available`（`toolCallId=callID`，`output=state.output`），synthetic user text 作为普通 user 文本注入——**每次模型请求都会带上**；running/pending 的 part 注入为 `output-error "[Tool execution was interrupted]"` 防悬挂 tool_use。
7. **与 agent 自身 bash 工具的关系**：二者落**完全相同的 part 形态**（`tool:"bash"`），客户端可用同一渲染管线；但 shell 端点路径**无超时、无输出截断、不记录 exit code、不做权限询问**。可观测区分：shell 端点的 `callID` 是裸 ULID，agent 工具调用的 `callID` 形如 `call_xxx`；且 shell 端点的 part `title:""`、metadata 只有 `{output}`（无 exit/truncated）。
8. **TUI 呈现**：composer 行首 `!` 进 shell 模式（`tui/src/component/prompt/index.tsx:831`），提交走同一 SDK 端点（同文件 :1059-1069）；时间线中渲染为 BlockTool 命令卡片——running 显示 Spinner、completed 显示 `$ <command>` + 输出（默认折叠 10 行）。

## 1. 证据基线

| 取证对象 | 位置/方式 | 版本核对 |
|---|---|---|
| 本地安装包 | `/home/linuxbrew/.linuxbrew/lib/node_modules/opencode-ai/`，package.json `"version": "1.18.18"`；本体是 wrapper + `bin/opencode.exe`（183MB Bun ELF，来自 optionalDependencies `opencode-linux-x64@1.18.18`） | package.json 与实测服务一致 |
| 二进制内嵌 bundle | `grep -a` 明文命中：`yn="/session"`、shell 路由模板、`text:"The following tool was executed by the user",synthetic:!0`、`storage/session/message/*/*.json` glob、`message.updated`/`message.part.updated`/`message.removed`/`session.idle` 等事件名 | 与 tag 源码逐条对应 |
| GitHub tag 源码 | `git clone --depth 1 --branch v1.18.18 https://github.com/anomalyco/opencode` → `/tmp/oc-v1-18-18` | commit `31406ccc`，git log 标题 `release: v1.18.18` |
| 运行中服务器 | `127.0.0.1:4200`（`opencode serve --port 4200 --hostname 127.0.0.1`，PID 226232），Basic auth。**只做了只读 GET 与只读 sqlite 查询** | `GET /session` 返回 `"version":"1.18.18"` |
| 服务器数据目录 | 进程 env `XDG_DATA_HOME=/tmp/v1xdg` → 实际打开 `/tmp/v1xdg/opencode/opencode.db`（/proc/226232/fd 取证） | `sqlite3 file:...?mode=ro` 查得 message/part 表 + 真实 bash part 数据 |

注意：默认 `XDG_DATA_HOME`（`~/.local/share/opencode`）下的 opencode.db 属于 beta（opencode2）产品线，表结构是 `session_message`/`session_v2` 等 V2 表——**同机双产品线共享数据目录时 DB 文件按 channel 分名**（`core/src/database/database.ts:43-55`）。

## 2. 按问题展开

### Q1 shell 接口行为（同步 or 异步？返回什么？超时？）

**路由与 payload**

- `opencode/src/server/routes/instance/httpapi/groups/session.ts:29` `const root = "/session"`；:98 shell 路径注册；:356-368 端点定义：
  - success：`described(SessionV1.WithParts, "Created message")`（:360）——端点描述原文 "Execute a shell command within the session context and return the AI's response."（:366）
  - error：`HttpApiError.BadRequest, ApiNotFoundError, SessionBusyError`（:361）
- payload：`ShellPayload = SessionPrompt.ShellInput 去掉 sessionID`（:72）；`ShellInput`（`opencode/src/session/prompt.ts:1527-1533`）= `{ sessionID, messageID?, agent: String（必填）, model?: {providerID, modelID}, command: String }`。
- handler：`opencode/src/server/routes/instance/httpapi/handlers/session.ts:341-347` → `promptSvc.shell({...payload, sessionID})`，经 `SessionError.mapBusy` 包装。
- 忙错误：`opencode/src/server/routes/instance/httpapi/errors.ts:116-123` `SessionBusyError`，`httpApiStatus: 409`（生成 SDK 类型中亦为 `409: SessionBusyError`）。

**同步执行与状态机**

- 服务入口：`opencode/src/session/prompt.ts:1349-1354` —— `state.startShell(sessionID, lastAssistant(...), shellImpl(input, ready), ready)`。
- 会话级 Runner 状态机：`opencode/src/effect/runner.ts:33-37` `Idle | Running | Shell | ShellThenRun`；`startShell`（:140-169）：
  - 非 Idle → 立即 `fail(new Busy())`（:144-147）→ 上层转 SessionBusyError(409)。**shell 与 agent 轮次互斥，shell 之间也互斥，忙时不排队**；
  - Idle → fork shellImpl 为 fiber，状态 Shell，HTTP handler `Fiber.await(fiber)`（:154-156）——**响应在命令结束后才返回**（同步 HTTP 语义）；
  - 反向情形：Shell 状态下来了普通 prompt → `ensureRunning`（:115-138）转成 ShellThenRun，**prompt 排队**，shell 结束后自动开跑（`finishShell` :93-106）。
- abort 端点（`POST /session/{id}/abort`，路径表 groups/session.ts:91）对 Shell 态：`stopShell`（runner.ts:108-113）先 await ready latch（等消息对落库，见 Q2 `Effect.ensuring(markReady)` prompt.ts:520）再 interrupt fiber → 子进程随 scope 关闭被杀（`forceKillAfter: "3 seconds"` prompt.ts:564）。

**超时：无**

- `shellImpl`（prompt.ts:552-578）直接 `Stream.runForEach` 消费合并输出流并 `yield* handle.exitCode`——**没有任何 timeout race**。`forceKillAfter: "3 seconds"`（:564）仅在收到终止信号后的强杀宽限，不是超时。
- 对比 agent 侧 bash 工具（`opencode/src/tool/shell.ts:540-555`）有 exit / abort / timeout 三方 race，默认超时 2 分钟（:347 `flags.bashDefaultTimeoutMs ?? 2*60*1000`，工具参数 timeout 可覆盖）。
- 结论：客户端 POST shell 必须自行设计客户端侧超时/取消 UI；请求可被 `POST /session/{id}/abort` 中断（产生 `<metadata>User aborted the command</metadata>` 追加输出，prompt.ts:530-532）。

**返回值**

- `{ info: msg, parts: [part] }`（prompt.ts:589）= assistant 消息 + bash tool part 的 WithParts。**user 消息不在响应里**（客户端从 SSE `message.updated` 拿）。
- 环境细节：cwd = 实例目录（:519）、`TERM=dumb`、`stdin:"ignore"`、插件 `shell.env` 钩子（:554-558）、stdout+stderr 合并（`Stream.decodeText(handle.all)` :567）。

### Q2 消息数据模型与存储

**shellImpl 写入序列**（`opencode/src/session/prompt.ts:451-592`）

1. user 消息（:470-477）：`{ id: input.messageID ?? ascending(), role:"user", agent, model, time.created }`；
2. synthetic text part（:479-487）：`text:"The following tool was executed by the user", synthetic:true`；
3. assistant 消息（:489-503）：`parentID=userMsg.id, cost:0, tokens 全 0`；
4. tool part（:505-518）：`{ type:"tool", tool: ShellID.ToolID, callID: ulid(), state:{ status:"running", time:{start}, input:{command} } }`；`ShellID.ToolID = "bash"`（`opencode/src/tool/shell/id.ts:16`）；
5. **流式更新**（:567-575）：每个输出 chunk 都 `part.state.metadata = { output }` + `updatePart` —— 这就是实时更新的数据通道；
6. 收尾（:528-550）：`msg.time.completed` 落库；part → `{ status:"completed", time:{start,end}, input, title:"", metadata:{output}, output }`。

**part schema**（`schema/src/v1/session.ts`）：`ToolPart` :315-322（`{type:"tool", callID, tool, state, metadata?}`）；`ToolState` 四态 union :259-313：pending{input,raw} / running{input,title?,metadata?,time.start} / completed{input,output,title,metadata,time{start,end,compacted?},attachments?} / error{input,error,metadata?,time}。

**实测数据**（4200 服务器 DB，只读查询；用户手动 `!echo hi` 场景）

```json
// part（shell 端点产物）
{ "id":"prt_0444...", "messageID":"msg_0444...", "type":"tool", "tool":"bash",
  "callID":"01M1241X421BKXXXPRM335Q3DN",
  "state":{ "status":"completed", "time":{"start":1787851502722,"end":1787851502772},
            "input":{"command":"echo hi"}, "title":"",
            "metadata":{"output":"hi\n"},
            "output":"hi\n" } }
// 配对 assistant 消息：role=assistant, cost=0, tokens 全 0, parentID=user msg id
// 配对 user 消息 part：{ "type":"text", "text":"The following tool was executed by the user", "synthetic":true }
```

对照组（agent 自己调 bash 工具的 part）：`callID:"call_c18db1522b5146328f98cd39"`（`call_` 前缀形态），title 为完整命令，`metadata:{output, exit:0, truncated:false}`。

**存储位置与勘误**

- 写路径是**事件投影**：`updateMessage`/`updatePart` 只发布事件（`opencode/src/session/session.ts:631-645`）；`core/src/session/projector.ts:262-275`（message.updated → upsert MessageTable）与 :312-330（message.part.updated → upsert PartTable）负责落库。
- DB：drizzle-orm SQLite，`core/src/session/sql.ts:68-98`（message/part 表，data JSON 列，索引 message_session_time_created_id_idx / part_message_id_id_idx）；文件路径 `core/src/database/database.ts:43-55`（默认 `{XDG_DATA_HOME}/opencode/opencode.db`，WAL + busy_timeout 5000，:27-32）。
- **勘误**（对 opencode-shell-message-persistence.md "V1 存储 = JSON 文件" 条目）：该报告引用的 storage/storage.ts:97-165 实为**旧版 JSON → SQLite 迁移代码**（`MIGRATIONS` 数组，`opencode/src/storage/storage.ts:81` 起；glob `storage/session/message/*/*.json` 是迁移扫描输入）。1.18.x 现行存储是 SQLite，实测 DB 表清单亦证实（message/part 表存在且有真实数据）。
- 读回：`GET /session/{id}/message`（无 limit → 全量，**升序** oldest-first，`opencode/src/session/session.ts:830-853`；`?limit=N` → 游标分页，**降序** newest-first，`opencode/src/session/message-v2.ts:433-440`）。响应均为 `WithParts[]`（`{info, parts}`，`schema/src/v1/session.ts:493-500`）。实测 `?limit=3` 返回 list，元素 keys 为 info/parts。

### Q3 SSE 事件全集与 shell 事件序列

**传输端点**：`GET /event`（`opencode/src/server/routes/instance/httpapi/groups/event.ts:8,14`，无 /api 前缀），text/event-stream。线格式（handlers/event.ts:12-19,40）：每条 `data: {"id":"evt_...", "type":"message.updated", "properties":{...}}`；首条 server.connected，每 10s server.heartbeat（:63-70）；**按实例 directory 过滤**（:35-39）。

**事件目录**（`schema/src/v1/session.ts:571-641` + `schema/src/session-status-event.ts`）：

| 事件 | properties | 说明 |
|---|---|---|
| session.created / updated / deleted | `{sessionID, info}` | 会话生命周期 |
| message.updated | `{sessionID, info}` | 消息整体 upsert（user/assistant） |
| message.removed | `{sessionID, messageID}` | |
| message.part.updated | `{sessionID, part, time}` | **part upsert，shell 输出的实时通道** |
| message.part.removed | `{sessionID, messageID, partID}` | |
| message.part.delta | `{sessionID, messageID, partID, field, delta}` | 文本增量（agent 流式输出；shell 路径不用） |
| session.diff | `{sessionID, diff}` | |
| session.error | `{sessionID?, error}` | |
| session.status | `{sessionID, status: idle|retry|busy}` | 会话忙闲（`opencode/src/session/status.ts:39-48`） |
| session.idle（deprecated） | `{sessionID}` | idle 时与 status 并发（status.ts:42-45） |

**shell 执行期间的完整事件序列**（发布点逐一核对）：

```
POST /session/{id}/shell
  1. session.status        {status:"busy"}          ← runner onBusy（run-state.ts:64 → status.ts:41）
  2. message.updated       {info: user}             ← prompt.ts:478
  3. message.part.updated  {part: synthetic text}   ← prompt.ts:487
  4. message.updated       {info: assistant}        ← prompt.ts:503
  5. message.part.updated  {part: tool bash, running}                        ← prompt.ts:518
  6. message.part.updated  × N（同 part，state.metadata.output 逐 chunk 增长） ← prompt.ts:572
  7. message.updated       {info: assistant, time.completed}                 ← prompt.ts:536
  8. message.part.updated  {part: tool bash, completed, output 全量}         ← prompt.ts:547
  9. session.status        {status:"idle"} + session.idle                    ← finishShell → onIdle（run-state.ts:60-63）
  HTTP 200 {info, parts}   （与 8 同期返回）
```

- 中止路径：6 之后收到含 `<metadata>...User aborted...</metadata>` 的 completed（prompt.ts:530-548），busy→idle 正常发布。
- 注意：**事件与 HTTP 响应是双通道**；第三方客户端应以事件流为准做时间线渲染，HTTP 响应仅作完成信号/回退。
- message.updated / message.part.updated 的 properties 即消息/part 全量快照（幂等 upsert），客户端无需自己做 delta 合并（shell 场景 metadata.output 是全量替换，不是追加）。

### Q4 LLM 上下文注入

- 转换入口：`MessageV2.toModelMessagesEffect`（`opencode/src/session/message-v2.ts:131`），每次模型请求都从存储取全量 WithParts 历史（prompt.ts:1262）。
- 完成态 tool part（message-v2.ts:315-323）：→ UIMessage part `{ type:"tool-bash", state:"output-available", toolCallId: part.callID, input: part.state.input, output: part.state.output }`，再经 AI SDK `convertToModelMessages`（:406-414）变成 assistant tool-call + user tool-result。
- error 态（:325-348）：有 metadata.interrupted 输出则仍 output-available，否则 output-error / errorText。
- **pending/running 态**（:349-360）：注入 `output-error "[Tool execution was interrupted]"` —— 防止 Anthropic 等严格 API 的悬挂 tool_use。
- synthetic user text 随 user 消息注入（:206-210 只过滤 ignored/空文本，synthetic 照常带上）——模型由此知道"用户执行了一条命令"及其输出。
- 即：用户 shell 的命令与输出**永久进入**会话上下文，后续每轮都携带（直到 compaction/截断；compaction 对 tool part 的序列化细节本轮未单独取证）。

### Q5 TUI 渲染

- 进入 shell 模式：composer 光标在行首按 `!`（`tui/src/component/prompt/index.tsx:830-838`，mode:"shell"；esc/backspace 退出 :843-860）。
- 提交（同文件 :1059-1069）：`sdk.client.session.shell({ sessionID, agent: agent.name, model, command })` —— 与第三方客户端完全相同的端点与 payload（TUI 不等待返回，void 调用，靠 SSE 渲染过程）。
- 时间线渲染：ToolPart 组件按 `toolDisplay("bash")` 分发到 Shell 组件（`tui/src/routes/session/index.tsx:1717-1751,2054-2111`）：
  - `state.metadata.output` 存在 → **BlockTool 卡片**：running 时 Spinner 包裹命令，completed 时 `$ {command}`；输出取 metadata.output（stripAnsi），默认折叠 10 行、点击展开（:2061-2067）；
  - 否则 → InlineTool 一行形态。
- 与 V2 `type:'shell'` 行的差异：V1 没有 shell 消息类型，TUI 把它渲染成**普通 assistant 工具轮次卡片**（与 agent bash 调用同形态，仅数据来源不同）；V2 是一等 shell 消息行。

## 3. 对 oc-beacon V1 方言的适配含义

1. **时间线 + 实时更新管线**：POST 发出后立即监听事件——用 message.updated(user, assistant) 建立两条消息节点；message.part.updated(running) 渲染"执行中"卡片；后续同 partID 的 message.part.updated **全量替换** `state.metadata.output`（是替换不是追加，48ms 批处理管线可直接套用）。status:"completed" 事件为准终态；HTTP 200 响应体可作校验/回填。
2. **请求阻塞**：POST 在命令结束前不返回（等价长轮询）。Ktor 客户端必须配足够大的 request timeout，或干脆不等响应（fire-and-forget + SSE 驱动 UI，与 TUI 的 void 调用一致），并自行实现客户端侧超时与"取消"按钮（取消走 `POST /session/{id}/abort`）。
3. **payload 必填 agent**：传当前会话的 agent 名（默认 build）；未知 agent 触发 session.error 事件 + 请求失败（prompt.ts:461-468）。model 可省略（回落 agent.model / 会话当前模型，:469）。
4. **409 处理**：会话忙（agent 轮次进行中或另一 shell 执行中）返回 `409 {sessionID, message}`，UI 应提示"会话忙"并支持重试，不可假设排队。
5. **消息对渲染**：synthetic user 消息建议隐藏或折为"用户命令"标签；assistant 消息的 bash part 是唯一渲染体。无 exit 字段 → 失败态不可知；可用输出尾部的 `<metadata>\nUser aborted the command\n</metadata>` 识别中止。
6. **与 agent bash 调用统一渲染**：part 形态相同，同一 Compose 组件可复用；如需区分来源，用 callID 形态（裸 ULID vs call_ 前缀）与 title/metadata.exit 缺失做启发式判断（非契约，勿硬依赖）。
7. **分页语义**：全量拉取升序、limit 拉取降序——刷新与历史加载逻辑要区分。
8. **多产品线同机**：beta 与 1.18.x 共享 `~/.local/share/opencode` 时 DB 按 channel 分文件；oc-beacon 按 /event + /session（无 /api）即可稳定识别 V1 方言，无需关心存储差异。

## 4. 与 V2 的差异对照表

依据 V2 报告（beta-18414，fafcea42）与本报告（v1.18.18，31406ccc）：

| 维度 | V1（1.18.18） | V2（beta-18414） |
|---|---|---|
| 端点 | `POST /session/{id}/shell`（无 /api） | `POST /api/session/{id}/shell` |
| payload | `{agent（必填）, command, model?, messageID?}` | `{id?（自定义事件 id）, command}` |
| 同步性 | **同步**：响应阻塞至命令结束，body = `{info, parts}` | **异步**：立即 204 NoContent |
| 忙语义 | 409 SessionBusyError（不排队；prompt 反而可在 shell 后排队） | awaitIdle 等待 agent 空闲后执行 |
| 消息类型 | 无新类型：user+assistant 消息对，assistant 带 tool:"bash" part | 一等 type:"shell" 消息（shellID/command/status/exit/output{...}） |
| part 内输出 | state.output + state.metadata.output（字符串，无 exit） | output:{output,cursor,size,truncated} 对象 |
| 执行载体 | 服务端子进程，事件投影落库 | 后台 sh_ 任务体系 + 输出文件 `{data}/shell/{project}/sh_*.out` |
| 超时 | **无**（命令可无限运行） | timeout:0 **无**（一致） |
| 输出上限 | **无截断**（part 存全量输出） | 捕获上限 1MiB（SHELL_MAX_CAPTURE_BYTES） |
| exit code | 不记录 | status: exited\|timeout\|killed + exit 数值 |
| 事件序列 | message.updated / message.part.updated × N + session.status | session.shell.started → session.shell.ended（持久事件） |
| 存储 | SQLite message/part 表（V1 schema） | SQLite session_message 表（V2 schema，同目录不同 channel 文件） |
| LLM 注入 | tool part → tool result + synthetic user text（每轮携带） | shell 消息 → role=user 文本 "The following shell command was executed by the user..." |
| TUI 呈现 | 普通 bash 工具轮次卡片（Spinner / $ command + 输出） | 独立 shell 消息行（rows.ts 专门分支） |
| 客户端要点 | 等价于渲染一次 agent bash 工具轮次 | 渲染独立 shell 消息类型 + 解析 output 对象/字符串双形态 |

**版本漂移提示**：V1 结论锚定 1.18.18 tag 与本机二进制双重验证；V2 beta 迭代极快（V2 报告 §6 已记录 beta-18414 次日 dev 树的 schema 重构）。oc-beacon 对两侧都应做"未知字段容错 + 双形态解析"。
