# V2 (opencode2, @opencode-ai/cli beta-18414): `POST /api/session/{id}/shell` 与 `type:'shell'` 会话消息条目

- 日期：2026-08-28
- 结论：**是。** `type:'shell'` 条目是服务器在执行 shell 时通过**持久事件投影**主动写入 session 消息存储（SQLite `session_message` 表）的一等公民消息，与 user/assistant/compaction 等同表同管道，**不是 API 临时拼凑**。
- 证据基线：源码浅克隆 `github.com/sst/opencode`，**`beta` 分支**（tip `fafcea42`，2026-08-27）。npm `@opencode-ai/cli` 的 dist-tag `beta = 0.0.0-beta-18414` 与被测服务器版本一致；该分支即 v2 服务端代码（默认分支 `dev` 仍是 v1，2.0 分支是 4 月的旧探索，均无此实现）。

## 1. 消息条目由谁写入（Q1）

调用链（全部为 v2 `beta` 分支路径，`packages/` 前缀省略）：

1. 端点定义：`protocol/src/groups/session.ts:422-439`
   ```ts
   HttpApiEndpoint.post("session.shell", "/api/session/:sessionID/shell", {
     params: { sessionID: Session.ID },
     payload: Schema.Struct({ id: Event.ID.pipe(Schema.optional), command: Schema.String }),
     success: HttpApiSchema.NoContent,  // 204
     error: SessionNotFoundError,
   })
   // description: "Emits a shell.started event before execution and a shell.ended event with the merged output after."
   ```
2. HTTP handler：`server/src/handlers/session.ts:405-413` — `yield* session.shell({sessionID, id, command})` 后返回 204。
3. 服务实现：`core/src/session.ts:714-770`（`Session.shell`）
   - 会话级 shell 锁 + `execution.awaitIdle`；
   - `Shell.Service.create({command, cwd: session目录, timeout: 0, metadata:{sessionID}})` 真正拉起进程（`core/src/shell.ts:220-384`，`status:"running"`，输出流式写入捕获文件）；
   - **发布持久事件** `bus.publish(SessionEvent.Shell.Started, {sessionID, shell: started}, {id: input.id})`（session.ts:733-740）；
   - `shell.wait()` 等待退出并取回合并输出（session.ts:741-755）；
   - **发布** `bus.publish(SessionEvent.Shell.Ended, {sessionID, shell: completed.shell, output})`（session.ts:756-760）。
4. 投影落库：`core/src/session/projector.ts:660-661`
   ```ts
   yield* bus.project(SessionEvent.Shell.Started, (event) => run(db, event))
   yield* bus.project(SessionEvent.Shell.Ended,   (event) => run(db, event))
   ```
   `run` 使用 `SessionMessageUpdater`（`core/src/session/message-updater.ts`）：
   - `session.shell.started` → `adapter.appendMessage(SessionMessage.Shell.make({ id: msg_<evtId>, type: "shell", shellID, command, status, time:{created} }))`（message-updater.ts:173-185）；
   - `appendMessage` = INSERT INTO `SessionMessageTable`（projector.ts:382-397）。
5. 存储即消息历史真源：GET 列表端点 `GET /api/session/:sessionID/message`（`protocol/src/groups/message.ts:26`，identifier `v2.message.list`）→ handler `server/src/handlers/message.ts:32-62` → core `session.messages` 读同一张 `SessionMessageTable`（`core/src/session/history.ts:43-64`，无 type 过滤）。

持久性补充：`session.shell.started/ended` 是 `Event.durable`（`schema/src/session-event.ts:282-303`），bus 在同一 DB 事务内先跑 projector 再写 `EventTable` 事件日志（`core/src/bus.ts:284-448`），可重放。消息 id 由事件 id 派生（`SessionMessage.ID.fromEvent(event.id)`，即 `msg_<evtId>`；payload 可选 `id` 字段即自定义事件 id，天然幂等）。fork 复制历史时显式排除 `status='running'` 的 shell 行（projector.ts:191）。

## 2. 生命周期（Q2）

`SessionMessage.Shell` 消息 schema（`schema/src/session-message.ts:109-122`）：`{ id, type:"shell", shellID, command, status, exit?, output?, time:{created, completed?} }`；`status ∈ running|exited|timeout|killed`（`schema/src/shell.ts:22`）；`output = {output, cursor, size, truncated}`（`schema/src/shell.ts:71-78`）。与实测 JSON 完全吻合。

时序：

| 时机 | 触发 | 存储动作 |
|---|---|---|
| POST 到达，进程 spawn 成功 | `session.shell.started` 发布（started.status="running"） | INSERT shell 消息行（status:"running"，无 output/exit） |
| 进程退出 | ShellService `finish("exited", code)`（shell.ts:318-347），另发**瞬态** `shell.exited` | （无投影；瞬态事件不入库） |
| `shell.wait` 返回 + 抓取合并输出后 | `session.shell.ended` 发布（含 terminal info + output） | UPDATE 同一行：`status/exit/output/time.completed`（message-updater.ts:186-199；按 `json_extract(data,'$.shellID')` 定位，projector.ts:331-351） |

注意：

- **POST 是同步的**：handler `yield* session.shell(...)` 内含 `shell.wait`，204 在命令退出并发布 ended **之后**才返回（`timeout: 0` 即无超时）。实测 `echo gapcheck` 秒回，看不到阻塞。
- 行 id 固定为 `msg_<started事件id>`，ended 只 UPDATE 不新开一行。
- `output.truncated` 来自 `SHELL_MAX_CAPTURE_BYTES` 捕获上限（session.ts:749-753）。

## 3. SSE 广播（Q3）

会广播，但事件名是 **v2 原生事件，不存在 `message.updated`**（后者是 v1 专属，见 `schema/src/v1/session.ts:597`，v2 SSE schema `ServerDefinitions` 不含 v1 legacy 事件）：

- SSE 端点 `GET /api/event`（`protocol/src/groups/event.ts:34`，identifier `v2.event.subscribe`）→ `server/src/handlers/event.ts` + `server/src/event-feed.ts`，直接转发 bus `notify()` 的全部事件（bus.ts:500-511，durable 与 ephemeral 都过这里）。
- SSE schema 并集包含：`SessionEvent.Definitions`（含 durable **`session.shell.started` / `session.shell.ended`**，schema/src/event-manifest.ts:39-48）+ `Shell.Event.Definitions`（ephemeral **`shell.created` / `shell.exited` / `shell.deleted`**，event-manifest.ts:62；发布点 core/src/shell.ts:161/333/373）。
- v2 OpenAPI 亦把 `session.shell.started/ended` 列为事件 schema（如 `packages/codemode/test/fixtures/opencode-v2-openapi.json` ~L12959/L13027）。

第三方客户端正确姿势：订阅 SSE 的 `session.shell.started`（本地 append type:'shell' 行，id=`msg_<event.id>`）与 `session.shell.ended`（就地更新 status/exit/output/time.completed）。

## 4. 官方客户端呈现（Q4）

- **TUI（v2）**：在消息流中作为**一等消息行**渲染。`tui/src/routes/session/rows.ts:233` 订阅 `data.on("session.shell.started", message)` 把消息插进时间轴；`tui/src/routes/session/index.tsx:1701-1702` 匹配 `message.type === "shell"`，由 `ShellMessage`（index.tsx:2264-2286）渲染：左侧竖线 box，首行 `$ {command}`，下方浅色显示 strip-Ansi 后的 output。导出时也有对应分支（index.tsx:3797）。
- **TUI 自身就是该端点的调用方**：shell 模式回车即 `client.api.session.shell({sessionID, command: inputText})`（`tui/src/component/prompt/index.tsx:1282-1284`）；prompt footer 还按 `metadata.sessionID` 统计运行中 shell 数（`tui/src/feature-plugins/prompt/footer.tsx:21-26`）。
- **Solid/web 客户端**：`client/src/solid/data.ts:706-729` —— `session.shell.started` 本地 append type:'shell' 消息，`session.shell.ended` 更新同一行，与服务器投影逻辑逐字段一致。
- 注意区分：assistant 消息里 tool name 为 `bash`/`shell` 的 **tool part**（packages/app e2e 里的 "shell" fixture）是另一条路径（shell 工具），与本议题的 `type:'shell'` 会话消息不同源。

## 对 oc-beacon 的含义

- V2 适配时 Room 消息模型需支持 `type:'shell'`（shellID/command/status/exit/output{output,cursor,size,truncated}/time.completed）。
- SSE 层需处理 `session.shell.started`/`session.shell.ended`（以及可选的瞬态 `shell.*`）；不要等 `message.updated`。
- `GET /api/session/{id}/message` 默认 limit 50、默认 desc、cursor 分页（protocol/src/groups/message.ts:7-44；handlers/message.ts:9）。
- POST shell 会阻塞到命令退出；长命令应视为长请求（无服务端超时）。
