# opencode '!' shell 命令是否持久化为会话消息 — 源码调查结论

## 仓库确认
- 权威仓库已迁移：github.com/anomalyco/opencode（sst/opencode 重定向至此）。npm @opencode-ai/cli 的 package.json repository 字段同指向该仓库。
- 用户服务器的 opencode2 beta-18414 = npm @opencode-ai/cli@0.0.0-beta-18414（发布 2026-08-27T17:23Z，bin 名就叫 opencode2），由仓库 beta 分支构建（beta HEAD fafcea4 = 2026-08-27 16:51 UTC，紧贴发布时间）。
- 三层取证：① beta 分支源码（/tmp/oc-beta-src，即 beta-18414 对应代码）② dev 分支源码（/tmp/oc-invest，2026-08-28 HEAD，V1 1.18.25 产品线）③ 下载了真实 beta-18414 linux-x64 二进制（/tmp/oc-beta-bin）grep 内嵌 bundle 逐条印证（如 /api/session/:sessionID/shell 路由与 shellID 消息 schema 均在二进制中命中）。

## 核心结论（beta-18414 / beta 分支）
Q1：TUI '!' 命令是否写入会话消息存储？—— 是，全链路持久化。数据流：
1. TUI composer 光标在行首按 '!' 进入 shell 模式：packages/tui/src/component/prompt/index.tsx:976-986
2. 提交调用 client.api.session.shell({sessionID, command})（同文件 :1282-1285）= POST /api/session/:sessionID/shell，payload {id?, command}，成功返回 204（packages/protocol/src/groups/session.ts:422-439，描述原文：Emits a shell.started event before execution and a shell.ended event with the merged output after）。HTTP 响应是命令完成的信号（TUI 注释 stream-v2.transport.ts:94），但命令在服务端 detached 运行，客户端断连不会杀掉它。
3. 服务端 handler（packages/server/src/handlers/session.ts:405-413）→ Session.shell（packages/core/src/session.ts:714-770）：等会话空闲(execution.awaitIdle) → Shell.Service.create({command, cwd:会话目录, timeout:0, metadata:{sessionID}}) 创建后台 sh_ 任务（stdout/stderr 合并写文件 {data-dir}/shell/{project}/sh_*.out，见 packages/core/src/shell.ts；退出记录保留 25 条/7 天）→ 发布持久事件 session.shell.started {sessionID, shell: Info} → shell.wait 等退出 → 读输出（上限 1MiB，session.ts:1208 SHELL_MAX_CAPTURE_BYTES）→ 发布 session.shell.ended {sessionID, shell: Info, output: Output} → execution.wake(sessionID)。
4. 事件投影为消息（packages/core/src/session/projector.ts:660-661 + message-updater.ts:173-199）：started 时 INSERT type=shell 行；ended 时 UPDATE status/exit/output/time.completed。
5. 存储 = SQLite：表 session_message（packages/core/src/session/sql.ts:79-98：id/msg_、session_id、type、seq、data JSON），DB 文件 = {global.data}/opencode.db（beta 渠道，packages/cli/src/server-process.ts:94-102）。不是 V1 的 storage/session/message/*.json 文件布局。
6. 读回：GET /api/session/:sessionID/message（packages/protocol/src/groups/message.ts:26 + packages/server/src/handlers/message.ts:28-60，游标分页默认 limit 50）返回投影后的 SessionMessage 列表 —— 即观察到的 type:shell 条目来源。

Q2：type:'shell' 消息类型存在，字段与观察完全吻合（packages/schema/src/session-message.ts:109-122）：
{ id: msg_..., metadata?, type: 'shell', shellID: sh_..., command, status: running|exited|timeout|killed, exit?: number, output?: {output, cursor, size, truncated}, time: {created, completed?} }
注意：output 是 Shell.Output 对象而非字符串 —— 印证 oc-beacon 文档 'session.shell.ended 的 output 可为对象 → 解析容错' 结论。

Q3：输出会注入 agent 上下文 —— 是。packages/core/src/session/runner/to-llm-message.ts:262-270：case 'shell' → role=user 消息 'The following shell command was executed by the user:\n\nCommand:\n{command}\n\nOutput:\n{output.output}'；每次模型请求都带（model-request.ts:99 → toLLMMessages）。上下文装载（session/history.ts:43-60）取最后已完成 compaction 之后全部消息、不过滤 type；compaction 时序列化为 '[Shell]: {command}\n{截断输出}'（session/compaction.ts:174-175）。shell 结束后 execution.wake 使进行中/排队轮次立即可见。

Q4 核心文件（beta 分支）：
- TUI：packages/tui/src/component/prompt/index.tsx（:978 '!' 绑定、:1284 提交）；packages/tui/src/mini/stream-v2.transport.ts:1057/1078；packages/tui/src/routes/session/rows.ts:233
- 端点/handler：packages/protocol/src/groups/session.ts:422-439；packages/server/src/handlers/session.ts:405-413
- 编排：packages/core/src/session.ts:714-770；后台 shell 服务：packages/core/src/shell.ts
- 事件 schema：packages/schema/src/session-event.ts:282-300；消息 schema：packages/schema/src/session-message.ts:109-122
- 投影/存储：packages/core/src/session/projector.ts:660-661、message-updater.ts:173-199、sql.ts:79-98、packages/cli/src/server-process.ts:94-102
- LLM 注入：packages/core/src/session/runner/to-llm-message.ts:262-270、session/history.ts、session/compaction.ts:174-175

## V1（opencode-ai 1.18.x，dev 分支产品线）差异
- 端点 POST /session/:id/shell（无 /api 前缀；dev 树 packages/opencode/src/server/routes/instance/httpapi/groups/session.ts root='/session' :356）→ promptSvc.shell → shellImpl（packages/opencode/src/session/prompt.ts:451-592）：同步执行，创建 user 消息（synthetic part 'The following tool was executed by the user'）+ assistant 消息（type:'tool', tool:'bash' 的 tool part，state.output 存输出）。没有 type:'shell' 消息。
- V1 存储 = JSON 文件 storage/session/message/{sessionID}/*.json + storage/session/part/...（storage/storage.ts:97-165）。
- V1 上下文注入同样存在（tool part → tool result + synthetic user part）。
- 一句话：V1 shell = 普通聊天消息对；V2 beta = 独立 type:'shell' 消息 + SQLite + 后台 sh_ 任务体系。

## 版本漂移警告
beta-18414 之后仅一天，dev HEAD 的 V2 已再度重构：/api/session/:id/shell 从 v2 protocol 组移除（SDK 回落 legacy /session/:id/shell），SessionMessage.Shell 变为 {callID, command, output: string, time}（无 shellID/status/exit）。beta 渠道迭代极快，客户端务必对 shell 消息做双形态容错（shellID/callID；output 对象/字符串）。

参考工作副本：/tmp/oc-beta-src（beta 源码）、/tmp/oc-invest（dev 源码）、/tmp/oc-beta-bin/package/bin/opencode2（beta-18414 原始二进制，可再 grep）。