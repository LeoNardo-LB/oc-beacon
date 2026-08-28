# V2 (opencode2, @opencode-ai/cli beta-18414): `POST /api/session/{id}/shell` 与 `type:'shell'` 会话消息条目

- 日期：2026-08-28（v2 重写版：以 anomalyco/opencode 克隆为唯一依据全文重新核对）
- 结论：**是。** `type:'shell'` 条目是服务器在执行 shell 时通过**持久事件投影**主动写入 session 消息存储（SQLite `session_message` 表）的一等公民消息，与 user/assistant/compaction 等同表同管道，**不是 API 临时拼凑**；重启后仍在；并会**注入后续 agent 的 LLM 上下文**（映射为 user 角色，见 §5）。
- 证据基线：源码浅克隆 `github.com/anomalyco/opencode`（npm `@opencode-ai/cli` 包 repository 字段指向的权威仓库），**`beta` 分支，tip `fafcea42`（`fafcea42e64a240628fbecf7892e5d8b7a34c9fe`，2026-08-27，"refactor(core): share patch write path (#45588)"）——克隆后实测核实，与预期一致**。npm dist-tag `beta = 0.0.0-beta-18414` 与被测服务器版本一致。克隆保留于 `/home/leo-tkp/oc-anomalyco-tmp` 供后续查证；下文路径均相对 `packages/`。
- 与旧版（sst/opencode 镜像初稿）的差异：**全部行号级引用逐条在 anomalyco 克隆核对，无一漂移**（同 commit `fafcea42`，两仓库 beta 同 hash）。实质性修正/补充共两处 + 若干增补，见「与本报告旧版的差异清单」（§7）。

## 1. 写入链：端点 → handler → 服务 → 消息落库（Q1）

调用链（全部为 `beta`=`fafcea42` 实测行号）：

1. **端点定义**：`protocol/src/groups/session.ts:422-439`
   ```ts
   HttpApiEndpoint.post("session.shell", "/api/session/:sessionID/shell", {
     params: { sessionID: Session.ID },
     payload: Schema.Struct({
       id: Event.ID.pipe(Schema.optional),
       command: Schema.String,
     }),
     success: HttpApiSchema.NoContent,  // 204
     error: SessionNotFoundError,
   })
   // :434 identifier: "v2.session.shell"
   // :437 description: "...Emits a shell.started event before execution and a shell.ended event with the merged output after."
   ```
2. **HTTP handler**：`server/src/handlers/session.ts:405-413` —— `yield* session.shell({sessionID, id, command})`（:408-410）后 `return HttpApiSchema.NoContent.make()`（:411）。
3. **服务实现**：`core/src/session.ts:714-770`（`Session.shell`）
   - 会话级 shell 互斥锁（`shellLocks` = `KeyedMutex`，:350 定义；:716 加锁）+`execution.awaitIdle`（:719，等 agent 轮次空闲）+`activeShells` 计数（:718）；
   - `plugins.flush`（:721-722）后 `Shell.Service.create({command, cwd: session.location.directory, timeout: 0, metadata:{sessionID}})` 真正拉起进程（:723-731）——**`timeout: 0` 即服务端无超时**；
   - **发布持久事件** `bus.publish(SessionEvent.Shell.Started, {sessionID, shell: started}, {id: input.id})`（**:733-740**；`input.id` 为 POST payload 可选字段，作自定义事件 id）；
   - `shell.wait(started.id)` 等待终止（:743）；若等待时 shell 记录已被移除（`Shell.NotFoundError`），用 `synthesizeTerminalShellInfo` 合成 `status:"killed"` 终态（:745-747；实现在 :1003-1011）；
   - 取回合并输出 `shell.output(started.id, { limit: SHELL_MAX_CAPTURE_BYTES })`（:749-752）；
   - **发布** `bus.publish(SessionEvent.Shell.Ended, {sessionID, shell: completed.shell, output})`（**:756-760**）；
   - `Effect.ensuring` 清理 `activeShells` 并 `execution.wake`（:762-767）。
4. **ShellService 进程层**：`core/src/shell.ts:220-384`（`create`）——经 `environment.spawner.spawn` 拉起（:265-274，detached + `forceKillAfter: 3s` :272），combined stdout/stderr 流式写入捕获文件 `<globalData>/shell/<projectID>/<sh_id>.out`（:245、:288-297，`session.size` 累计字节数）；进程内记录仅存内存 Map（:125）。
5. **投影落库**：`core/src/session/projector.ts:660-661`
   ```ts
   yield* bus.project(SessionEvent.Shell.Started, (event) => run(db, event))
   yield* bus.project(SessionEvent.Shell.Ended,   (event) => run(db, event))
   ```
   `run` 调 `SessionMessageUpdater.update`（projector.ts:378），适配器在 :244-377：
   - `session.shell.started` → `adapter.appendMessage(SessionMessage.Shell.make({ id: msg_<evtId>, type: "shell", metadata, shellID, command, status, time:{created} }))`（**`core/src/session/message-updater.ts:173-185`**）；
   - `appendMessage`（projector.ts:243）= `insertMessage`：**INSERT INTO `SessionMessageTable`** `{id, session_id, type, seq: event.durable.seq, time_created, data}`（**projector.ts:382-397**）。
6. **存储即消息历史真源**：GET 列表端点 `GET /api/session/:sessionID/message`（`protocol/src/groups/message.ts:26-44`，identifier `v2.message.list` :39）→ handler `server/src/handlers/message.ts:32-62` → **`Session.messages` `core/src/session.ts:557-589`**：直接 SELECT `SessionMessageTable`，按 `seq` 排序、**无 type 过滤** → shell 消息天然出现在列表中。
7. **持久性**：`session.shell.started/ended` 是 `Event.durable`（`schema/src/session-event.ts:282-303`）；bus 持久发布路径（`core/src/bus.ts:284-449`）**先跑 projector（:404-406）再写 `EventTable` 事件日志（:420-434）**，同 id 重复持久事件直接 die（:384-398，天然防重放冲突）。消息行落在 SQLite（`session_message` 表：`core/src/session/sql.ts:80`，含 `(session_id,type,seq)` 等索引 ：93-96；DDL 见 `core/src/database/schema.gen.ts:158`），**重启后仍在**。注意：ShellService 的进程内记录（含输出文件句柄）不跨重启，但输出**文件**按 7 天保留策略落盘（见 §3）。
8. 附属行为：fork 复制历史时显式排除 `status='running'` 的 shell 行（**projector.ts:191**，`json_extract(data,'$.status') != 'running'`）；会话 transfer 的 settled 判定同为 `status !== "running"`（`core/src/session/transfer.ts:163`）。

## 2. 消息模型（Q2）

`SessionMessage.Shell` schema（**`schema/src/session-message.ts:109-122`**），字段全集：

`{ id, metadata?, type: "shell", shellID, command, status, exit?, output?, time: {created, completed?} }`

- `status ∈ running | exited | timeout | killed`（**`schema/src/shell.ts:22`**，四值 Literal）；
- `output = { output, cursor, size, truncated }`（**`schema/src/shell.ts:71-78`**；`cursor`=本次页之后的绝对游标，`size`=已捕获总字节数）；
- 与实测返回 JSON（`{id:"msg_...", time:{created,completed}, type:"shell", shellID:"sh_...", command, status:"exited", exit:0, output:{output,cursor,size,truncated}}`）逐字段吻合。

**id 生成规则**：`SessionMessage.ID.fromEvent(eventID) = eventID.replace(/^evt_/, "msg_")`（**`schema/src/session-message.ts:22-28`**，:26）——即 `msg_<事件id>`；POST payload 的可选 `id` 即自定义事件 id（session.ts:739 透传给 bus），因此**同一 payload id 重放得到同一消息 id**；而 bus 对同 aggregate 同 seq/id 的重复持久事件会直接 die（bus.ts:384-398），双重幂等防线。`shellID` 生成：`"sh_" + ascending()`（`schema/src/shell.ts:9-19`；`identifier.ts:6-20` 为 26 位 base62 时间戳+计数可排序 id）。

## 3. 生命周期（Q3）

状态机：`running → exited | timeout | killed`（无回边；`finish` 幂等，非 running 直接 return，shell.ts:320）。

| 时机 | 触发 | 存储动作 |
|---|---|---|
| POST 到达，进程 spawn 成功 | `session.shell.started` 发布（started.status="running"，session.ts:733-740） | INSERT shell 消息行（status:"running"，无 output/exit/completed） |
| 进程退出/超时/被杀（ShellService 内存态） | `finish(status, exit)`（shell.ts:318-347）：更新 Info → resolve 等待者（:332）→ 发布**瞬态** `shell.exited`（:333-337） | （无投影；瞬态事件不入库） |
| `shell.wait` 返回 + 抓取合并输出后 | `session.shell.ended` 发布（session.ts:756-760，含 terminal info + output） | **UPDATE 同一行**：`status/exit/output/time.completed`（message-updater.ts:186-199；先按 `json_extract(data,'$.shellID')` 定位（projector.ts:331-351，取 `seq` 最大一条），再按行 id UPDATE（projector.ts:228-242、:374）） |

终止路径明细：

- **exited**：`handle.exitCode → finish("exited", code)`（shell.ts:366-371）。
- **timeout**：`session.timeout(duration)`（shell.ts:349-362）——duration 0 清除定时器；到点 `finish("timeout", undefined, handle.kill())`（:357）。**Session.shell 传 `timeout: 0`，即用户会话 shell 无服务端超时**；`PATCH /api/shell/:id/timeout` 可后续改约（`protocol/src/groups/shell.ts:61-76`）。
- **killed**：core 中**没有** `finish("killed")` 调用点；唯一来源是 `synthesizeTerminalShellInfo`（session.ts:1003-1011）——`wait()` 抛 `Shell.NotFoundError`（记录在终止前被 `removeSession` 移除：显式 `DELETE /api/shell/:id`（`protocol/src/groups/shell.ts:94-105`，shell.remove）、进程内保留淘汰、或服务器重启）时，合成 `status:"killed"` + `time.completed`，输出退化为占位文本 `missingShellOutput()`（session.ts:993-1001，"Shell command output is no longer available."）。ended 仍会发布，消息行不会悬挂在 running。

**输出捕获上限与 truncated 语义（⚠ 对旧版结论的精化）**：消息内嵌输出取 `shell.output(id, {limit: SHELL_MAX_CAPTURE_BYTES = 1024*1024 = 1 MiB})`（session.ts:751；常量 ：1208）。但 `ShellService.output` **硬编码 `truncated: false`**（shell.ts:194、:216）——即 `type:'shell'` 消息的 `output.truncated` **恒为 false**；1 MiB 只截断**写入消息的字节数**，「还有更多」由 `cursor < size` 表达，可在记录保留期内经 `GET /api/shell/:id/output`（`protocol/src/groups/shell.ts:78`，cursor/limit 分页，默认 limit 65536）续读。记录保留：内存至多 25 个 exited（`EXITED_LIMIT` shell.ts:28，超出淘汰最旧 ：338-343）；输出文件保留 7 天（`RETENTION` :29，每小时清扫 ：102-103）。assistant 消息里 shell **工具**（`core/src/tool/plugin/shell.ts`）的 `truncated` 是另一条路径自己的计算（:241，`size > maxBytes || lines > maxLines`），与此无关。

**POST 是同步的**：handler `yield* session.shell(...)` 内含 `shell.wait`，204 在命令退出并发布 ended **之后**才返回。实测 `echo gapcheck` 秒回，感知不到阻塞；但长命令 = 长请求。

## 4. SSE 事件（Q4）

会广播；**不存在 `message.updated`**（那是 v1 专属，`schema/src/v1/session.ts:597`；v2 SSE schema `ServerDefinitions` 不含它）。v2 里最接近的是 `session.message.content.updated`（仅 assistant 内容，见 `client/src/solid/data.ts:730`），与 shell 消息无关。

- SSE 端点 `GET /api/event`（`protocol/src/groups/event.ts:34`，identifier `v2.event.subscribe` :38）→ `server/src/handlers/event.ts:20`（`feed.subscribe`）+ `server/src/event-feed.ts`（订阅→queue 扇出 ：64-71）；bus `notify()` 把**全部**事件（durable + ephemeral）推入 PubSub（`core/src/bus.ts:500-511`）。
- SSE schema 并集（`schema/src/event-manifest.ts`）包含：
  - **持久**：`SessionEvent.Definitions` 中的 **`session.shell.started`** / **`session.shell.ended`**（manifest :39-48；payload 见 `schema/src/session-event.ts:283-301`——started: `{sessionID, shell: Info}`；ended: `{sessionID, shell: Info, output: Output}`，均带事件 `id`/`created`）；
  - **瞬态**：`Shell.Event.Definitions` 的 **`shell.created`** / **`shell.exited`** / **`shell.deleted`**（manifest :62；定义 `schema/src/shell.ts:52-54`；发布点 **`core/src/shell.ts:373 / 333 / 161`**）。
- v2 OpenAPI 亦将 `session.shell.started/ended` 列为事件 schema（`packages/codemode/test/fixtures/opencode-v2-openapi.json` **:12959 / :13027**，另见 :14891-14894、:23034-23037 引用）。

与消息条目的对应关系：`started.payload.shell` 即 started 消息行字段来源（`shellID/command/status`），`ended.payload.shell + output` 即 UPDATE 字段来源，`event.id` → 消息 id（`evt_`→`msg_`）。第三方客户端完全可以**只凭 SSE 做本地投影**，与服务器投影逻辑逐字段同构。

## 5. 官方客户端呈现（Q5）

- **TUI（v2）**：shell 消息在消息流中作为**一等消息行**渲染。`tui/src/routes/session/rows.ts:233` `data.on("session.shell.started", message)` 把消息插进时间轴（相邻 synthetic 分支 ：229-232 展示了同款 `evt_`→`msg_` 手工派生）；`tui/src/routes/session/index.tsx:1701-1702` 匹配 `message.type === "shell"`，由 `ShellMessage`（**index.tsx:2264-2286**）渲染：左侧竖线 box，首行 `$ {command}`（:2280），下方浅色显示 strip-Ansi 后的 output（:2266、:2281-2283）。导出 transcript 也有对应分支（index.tsx:3794-3798，`## Shell` + 代码块）。
- TUI 的 **mini transport** 同样处理这两个事件（`tui/src/mini/stream-v2.transport.ts:1057-1077` started → `shellCommit(...running...)`，:1078 起 ended）。
- **TUI 自身就是该端点的调用方**：shell 模式回车即 `client.api.session.shell({sessionID, command: inputText})`（`tui/src/component/prompt/index.tsx:1282-1284`）；prompt footer 按 `metadata.sessionID` 统计运行中 shell 数（`tui/src/feature-plugins/prompt/footer.tsx:21-26`，数据源为 `shell.list` 仅 running）。
- **Solid/web 客户端**：`client/src/solid/data.ts:706-729` —— `session.shell.started` 本地 append `type:'shell'` 消息（id=`messageIDFromEvent(event.id)`），`session.shell.ended` 按 shellID 匹配后就地更新 `status/exit/output/time.completed`——与服务器投影逻辑逐字段一致。packages/app、session-ui、desktop 未见对 `session.shell.*` 的直接订阅（全仓 grep 佐证）。
- **⚠ 新增（旧版缺失）：shell 输出会注入后续 agent 上下文**。发给 LLM 的消息转换在 `core/src/session/runner/to-llm-message.ts:262-270`：`type:'shell'` → **`role: "user"`** 消息，content 为：
  ```
  The following shell command was executed by the user:

  Command:
  {command}

  Output:
  {output?.output ?? ""}   // 即 ended 时内嵌的那份（≤1 MiB）；running 阶段为空串
  ```
  压缩序列化路径亦包含它：`core/src/session/compaction.ts:174-175` → `[Shell]: {command}\n{truncateToolOutput(output)}`。含义：**shell 时间线不只是 UI 记录，还是模型可见的对话上下文**。
- 注意区分：assistant 消息里 tool name 为 `bash`/`shell` 的 **tool part**（`core/src/tool/plugin/shell.ts`，e2e fixture 里的 "shell"）是 shell 工具路径，与本议题的 `type:'shell'` 会话消息不同源。

## 6. 对 oc-beacon 的适配含义（Q6）

推荐模式（与官方 Solid 客户端同构）：**SSE 事件驱动的本地投影 + REST 全量分页兜底**，增量更新、不做轮询。

1. **Room 模型**：需支持 `type:'shell'`（shellID / command / status / exit / output{output,cursor,size,truncated} / time.completed / metadata）。消息主键直接用 `msg_<event.id>`（SSE `event.id` 去 `evt_` 前缀派生），与 REST 拉回的 id 天然一致，SSE 与全量刷新可幂等合并（UPSERT）。
2. **SSE 处理**：`session.shell.started` → 插入行（status=running，无 output）；`session.shell.ended` → 按 shellID 匹配就地 UPDATE。**不要等 `message.updated`**（v2 不存在）。瞬态 `shell.created/exited` 可选订阅（`exited` 可作"已退出、等待输出回填"的早期提示），但权威终态一律以 `session.shell.ended` 为准。
3. **REST 兜底**：首屏/断线重连用 `GET /api/session/{id}/message`（默认 limit 50、默认 desc、cursor 分页、limit ≤200：`protocol/src/groups/message.ts:7-22`；`server/src/handlers/message.ts:9`）全量分页拉取即可覆盖 shell 行。悬挂判定：`status==='running'` 且长时间无 ended → 服务器可能已重启（进程内记录丢失但消息行仍在）；可调 `GET /api/shell/{shellID}` 验证（404 即记录已失，客户端可本地按"已终止（输出不可用）"降级呈现，等价服务器的 killed 合成路径）。
4. **大输出**：`truncated` 恒为 false，勿依赖它判断截断；用 `cursor < size` 判断还有更多，需要完整输出时在记录存活期内（内存 25 个 exited / 文件 7 天）经 `GET /api/shell/{shellID}/output?cursor=` 增量续读。UI 只渲染消息内嵌那份即可对齐 TUI。
5. **发送侧**：POST 同步阻塞到命令退出（无服务端超时）——移动端必须配长超时 + 允许后台等待；发起后不要依赖 204 才显示行，应靠 SSE `started` 即时上屏。
6. **时间线渲染**：仿 TUI 形态——`$ command` 首行 + 输出块（等宽/浅色），running 态显示占位、ended 后回填输出；与 assistant 的 bash/shell tool part 视觉区分（后者是模型工具调用）。
7. **上下文语义**：用户 shell 是模型可见上下文（§5），oc-beacon 若做"重新生成/引用上下文"类功能，需把 shell 消息计入对话历史语义（user 角色）。

## 7. 与本报告旧版的差异清单（anomalyco 重核结果）

旧版基于 sst/opencode 镜像（同 commit `fafcea42`）撰写。本次以 anomalyco 克隆（tip 实测同 hash）逐条重核：

- **行号：全部一致，无一漂移。** 包括 422-439 / 405-413 / 714-770 / 733-740 / 756-760 / 660-661 / 173-185 / 186-199 / 382-397 / 191 / 331-351 / 109-122 / shell.ts:22、71-78、161/333/373、220-384、318-347 / session-event.ts:282-303 / bus.ts:500-511 / event-manifest.ts:39-48、62 / event.ts:34 / v1/session.ts:597 / openapi 12959、13027 / rows.ts:233 / index.tsx:1701-1702、2264-2286、3797 / prompt/index.tsx:1282-1284 / footer.tsx:21-26 / solid/data.ts:706-729 / message.ts:26、7-44 / handlers/message.ts:9。
- **修正①（读取路径归属）**：旧版"GET 列表 → core `session.messages` 读同一张表（`core/src/session/history.ts:43-64`）"——实际 REST 读取实现在 **`core/src/session.ts:557-589`**（直接 SELECT、无 type 过滤）；`history.ts:43-64`（`messageEntries`）是 **runner/LLM 历史**读取路径（带 compaction seq 截断），非 REST 路径。结论不变（同一张表、都无 type 过滤），引用已更正。
- **修正②（truncated 语义）**：旧版"`output.truncated` 来自 `SHELL_MAX_CAPTURE_BYTES` 捕获上限"——不精确。`ShellService.output` **硬编码 `truncated: false`**（shell.ts:194/216）；1 MiB 上限只决定写入消息的字节数，溢出以 `cursor < size` 表达，可经 `/api/shell/:id/output` 续读。已按此改写 §3/§6。
- **增补**：`killed` 唯一来源与 shell.remove 链路（§3）；保留策略 `EXITED_LIMIT=25` / 7 天 / 每小时清扫（§3）；消息 id 幂等的 bus 侧防线（§2）；TUI mini transport（§5）；**LLM 上下文注入** `to-llm-message.ts:262-270` 与 compaction 序列化（§5，旧版完全缺失）；v2 存在 `session.message.content.updated` 但与 shell 无关（§4）；对 oc-beacon 的悬挂判定/降级与 POST 长超时建议（§6）。
