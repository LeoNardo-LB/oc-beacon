# opencode v2 契约对齐修复设计（V2 Contract Alignment）

> 日期：2026-08-11 · 状态：已确认 · 类型：缺陷修复/契约对齐
> 配套实施计划：`docs/superpowers/plans/2026-08-11-v2-contract-alignment.md`

## 1. 背景与动机

用户报告三个问题：
1. **会话状态判定不准确**，伴随报错 `Field 'status' is required for type with serial name 'dev.leonardo.ocbeacon.data.dto.response.McpStatusEntry', but it was missing`
2. **发送消息后看不到自己的消息**，需要退出重进（触发 REST 拉取）才显示

经直连真实 opencode v2 服务器（`0.0.0-next-17132`，`http://127.0.0.1:4199`）实测 12+ 端点 + 60 秒 SSE 事件流 + 真实发送实验，根因是**客户端按 v1 契约假设解析 v2 接口**，v2 的实际契约（响应包裹结构、SSE 事件体系、part 模型）与 v1 存在系统性差异。

## 2. 目标与非目标

### 目标
- 修复 v2 下 3 个 P0 问题（MCP 状态面板崩溃、消息不显示、状态判定失效）——**对齐真实契约而非绕过症状**
- 建立完整的 v2 SSE 事件 → 领域事件映射（消息创建/part 流/完成闭环）
- 恢复 v2 下 FSM 的 SSE 真相源（execution 生命周期事件驱动）
- 清扫盘点发现的 P1/P2 解析缺陷

### 非目标
- 不重写 v1 路径（v1 服务器仍受支持，双轨并存）
- 不重构 MessageEventHandler 的 48ms 批处理/滚动补偿（SSE 铁律保护）
- 不改变消息渲染管线架构（MessageUpdated/MessagePartDelta 是既有成熟入口，v2 事件翻译进该管线）
- 不处理 opencode v2 服务器自身的契约漂移（版本 `next` 系列，实施时以实测为准）

## 3. v2 实测契约（唯一权威，实施前如服务器升级需复测）

> 认证：`Authorization: Basic base64(opencode:leo12321)`（或 `-u opencode:leo12321`）

### 3.1 REST 响应结构

| 端点 | 实测返回 | 与客户端假设 |
|------|---------|-------------|
| `GET /api/mcp` | `{"location":{...},"data":[{"name":"agentmemory","status":{"status":"connected"}}, ...]}` | ❌ 假设 `Record<String,{status:String}>` |
| `GET /api/session/active` | `{"data":{"ses_xxx":{"type":"running"},...}}`（data 是**对象**） | ❌ `unwrapList` 要求数组 |
| `POST /api/session/{id}/prompt` | 响应体 `{"data":{"id":"msg_...","sessionID":"...","timeCreated":1786414686334,"type":"user","data":{"text":"..."},"delivery":"steer"}}` | ❌ 只读 status，丢弃消息 |
| `GET /api/session/{id}/message` | `{"data":[{id,time:{created,completed?},type:"user"\|"assistant",content:[{type:"text"\|"reasoning"\|"tool",...}],agent?,model?}],"cursor":{...}}`——text/reasoning part **无 id**，tool part `id=call_id` | ✅ 可解析（V2MessageMapper 已适配） |
| `GET /api/config` | 裸数组 `[{type:"document",path,info:{...}}]` | ❌ `flexibleObject` 返回空 |
| `GET /api/vcs` | `{"data":{"branch":{}}}`（全局目录下 branch 是空对象） | ❌ DTO 期望 String |
| `GET /api/pty/shells` | 错误响应 `{"_tag":"InvalidRequestError",...}`（路由把 `shells` 当 ptyID） | ❌ 端点路径错误 |
| `GET /api/project` | 裸数组 `[{id,canonical,vcs,time,sandboxes}]` | ❌ DTO 无 `canonical` |
| `GET /api/command` | `{"data":[{name,template}]}`（无 description） | ⚠️ description 永远 null |

### 3.2 SSE 事件生命周期（实测确认，按序到达）

```
session.input.admitted      data:{sessionID, inputID, input:{type:"user",data:{text},delivery}}
session.execution.started   data:{sessionID}                              ← turn 开始
session.input.promoted      data:{sessionID, inputID}
session.step.started        data:{sessionID, assistantMessageID, agent, model, snapshot?}
  session.reasoning.started data:{sessionID, assistantMessageID, ordinal}
  session.reasoning.delta   data:{sessionID, assistantMessageID, ordinal, delta}
  session.reasoning.ended   data:{sessionID, assistantMessageID, ordinal, text}
  session.text.started      data:{sessionID, assistantMessageID, ordinal}
  session.text.delta        data:{sessionID, assistantMessageID, ordinal, delta}
  session.text.ended        data:{sessionID, assistantMessageID, ordinal, text}
  session.tool.input.started data:{sessionID, assistantMessageID, id, name}
  session.tool.input.delta  data:{sessionID, assistantMessageID, id, delta}
  session.tool.input.ended  data:{sessionID, assistantMessageID, id, text}
  session.tool.called       data:{sessionID, assistantMessageID, id, input, executed}
  session.tool.success      data:{sessionID, assistantMessageID, id, content, metadata}
session.step.ended          data:{sessionID, assistantMessageID, finish:"tool-calls"|"stop", cost, tokens, snapshot, files}
session.execution.succeeded data:{sessionID}                              ← turn 结束
```

**关键事实**：
- v2 **不发** `message.updated` / `message.part.updated` / `session.status` / `session.idle` / `session.error` / `session.next.*`（60s 双轮实测）
- part 定位键 = `(assistantMessageID, ordinal)`；tool part 用 `id=call_id`
- 所有生命周期事件均 durable（带 `durable:{aggregateID,seq,version}`），可回放
- 用户消息通道 = prompt 响应体 + `session.input.admitted` 双份（同 id：`inputID` == 响应体 `data.id`）
- `session.step.ended` 的 `finish="tool-calls"` 表示继续工具循环（**不结束 turn**），`finish="stop"` 表示 step 结束
- turn 结束权威信号 = `session.execution.succeeded`
- 上游 GitHub 源码（sst/opencode fork）显示事件体系仍在演进（如 `session.next.text.started` 带 `textID`）——**与用户服务器（`ordinal` 定位）不同版本**，实施必须以实测为准，不得照抄上游

## 4. 修复设计（逐项）

### 4.1 ① getMcpStatus（P0，崩溃）

**现状**：`V2ApiClient.getMcpStatus`（V2ApiClient.kt:878-886）`flexibleObject` 遇 data=数组返回根对象 → 遍历 `location`（无 status）→ decode 抛 "Field 'status' is required"。

**设计**：解析 `data` 数组，逐元素 `{name, status:{status}}`：
```kotlin
suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
    val bodyText = httpClient.get("${conn.baseUrl}/api/mcp") { ... }.bodyAsText()
    val items = V2ResponseWrapper.flexibleList(bodyText, json)
    return items.mapNotNull { obj ->
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val statusObj = obj["status"]?.jsonObject
        val status = statusObj?.get("status")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        name to McpStatusEntry(status = status, error = statusObj["error"]?.jsonPrimitive?.contentOrNull)
    }.toMap()
}
```
**DTO**：`McpStatusEntry` 增加 `error: String? = null`（failed/needs_client_registration 携带），保持 `status: String` 必填。

**消费链**（无需改动）：SystemApi → McpRepositoryImpl.getMcpServers → SessionListViewModel._mcpServers → UI（McpServerRow）。

### 4.2 ② 消息链路（P0，功能失效）——核心

**现状**：悲观消息策略依赖 v1 `message.updated` 回显；v2 无此事件。`mapV2DeltaEvent` 把 delta 的 partId 硬编码为 `""`，`flushPendingDeltas` 按 partId 精确匹配 → 每个 delta 新建 `Part.Text(id="")` → 多 part 错乱。

**设计**：在 `SseClientV2.handleEvent` 增加完整 v2 事件映射（新建 `V2SseMapper`，SseClientV2 持有）：

| v2 事件 | 映射为 | 关键字段 |
|---------|--------|---------|
| `session.input.admitted` | `SseEvent.MessageUpdated(Message.User)` | `inputID`→id；`input.data.text`→summary.body（播种）|
| `session.step.started` | `SseEvent.MessageUpdated(Message.Assistant)` | `assistantMessageID`→id；agent/model 携带 |
| `session.reasoning.started` | `SseEvent.MessagePartUpdated(Part.Reasoning)` | id 派生 `"${msgId}_ord_${ordinal}"` |
| `session.text.started` | `SseEvent.MessagePartUpdated(Part.Text)` | id 派生同上 |
| `session.reasoning.delta` | `SseEvent.MessagePartDelta` | partId=派生 id，field="reasoning" |
| `session.text.delta` | `SseEvent.MessagePartDelta` | partId=派生 id，field="text" |
| `session.reasoning.ended` | `SseEvent.MessagePartUpdated(Part.Reasoning)` | 完整 text 覆盖（权威） |
| `session.text.ended` | `SseEvent.MessagePartUpdated(Part.Text)` | 完整 text 覆盖（权威） |
| `session.tool.input.started` | `SseEvent.MessagePartUpdated(Part.Tool)` | id=call_id，status=pending |
| `session.tool.input.delta` | `SseEvent.MessagePartDelta` | partId=call_id，field="output" |
| `session.tool.called/success/failed` | `SseEvent.MessagePartUpdated(Part.Tool)` | 状态/输出更新 |
| `session.step.ended` | `SseEvent.MessageUpdated(Assistant.copy)` | cost/tokens 更新（**不完成**） |
| `session.execution.succeeded` | `SseEvent.MessageUpdated(Assistant.copy(time.completed))` | 完成 + FSM Idle |

**partId 派生规则**（修复 `mapV2DeltaEvent` 的 `""` 缺陷）：
- text/reasoning：`"${assistantMessageID}_ord_${ordinal}"`（v2 无 partID，ordinal 即定位键）
- tool：`call_id`（v2 tool part 的稳定 id）

**MessageEventHandler 配合**（Task 6）：
- `handleMessageUpdated` 对 Assistant 消息增加 part 播种支持（当前只对 User summary 播种）——started 事件创建的 part 直接经 `handleMessagePartUpdated` 插入 `_parts`
- delta 的 partType 判定改为按派生 partId 前缀区分 reasoning/text（现有 `firstOrNull{it.id==partId}` 逻辑保留，partId 非空后自然生效）

**去重/幂等**：消息 id（`msg_*`）与 REST 同一 ULID 体系；`handleMessageUpdated` 二分插入天然幂等；`mergeSortedMessages` 按 id 合并，REST 覆盖 SSE（User：REST 权威；Assistant：`mergeMessageMeta` 合并）。

### 4.3 ③ 状态判定（P0，功能失效）

**现状**：v2 不发 session.status/idle；`fetchSessionStatus` 用 `unwrapList` 解析 data=对象必然抛异常 → REST 校验静默失败；FSM 无 SSE 真相源。

**设计（两层）**：

**A. `fetchSessionStatus` 解析修复**（V2ApiClient.kt:212-228）：
- `unwrapList` → `root["data"]?.jsonObject`（参照同文件 `activeSessions` 的正确写法）
- type 映射：`"running"` → Busy（删除硬编码 `type="busy"`——服务器实际返回 `"running"`，硬编码 "busy" 与 `RestSessionStatusInfo` 的 `when(type)` 不匹配会走 else → Idle）
- **测试陷阱**：`V2ApiClientTest` 的 mock（342 行）用 `data:[...]` 数组格式——与真实服务器对象格式不符（测试假绿）。须同步更新为 `{"data":{"ses_1":{"type":"running"}}}` 并新增对象格式用例

**B. FSM v2 SSE 真相源**（SessionStateService + SessionStateFSM）：
- `FsmEvent` 新增 `ExecutionStarted(sessionId)` / `ExecutionSucceeded(sessionId)`（或映射复用 ClientSendParts/Idle 语义，实施时选型）
- `SessionStateService.mapSseEventToFsm`：`session.execution.started` → Busy；`session.execution.succeeded` → Idle（经 FSM 转移矩阵，含 forceComplete 语义）
- Activity 层恢复：execution.started → Waiting；reasoning/text started → Streaming；tool.input.started → ToolCalling（可后续细化，先保证 Core 层正确）
- `fetchSessionStatus` 修复后作为兜底（active 列表语义含后台 subagent，"running" ≠ 前台 turn——**不做主路径**）

### 4.4 ④ getConfig 系列（P1，MCP 配置丢失）

**现状**：`/api/config` 返回裸数组（配置文件文档列表），`flexibleObject` 返回空对象 → `ServerConfigResponse` 全默认 → MCP 配置（type/command/url）丢失。

**设计**：
- **实施第一步实测确认**：`GET /api/config` 的 `info` 子对象结构（是否含 `mcp`/`disabled_providers`/`model` 字段）——curl 实测后决定取哪个字段
- 若 info 含配置字段：解析数组元素 `info` → decode `ServerConfigResponse`；否则确认正确配置端点（如 `/api/config` 之外的专有端点）
- 同步修复 `getGlobalConfig`/`updateConfig`/`updateGlobalConfig` 4 个方法

### 4.5 ⑤⑥⑦⑧ 清扫（P1/P2）

| 项 | 设计 |
|----|------|
| ⑤ getVcs | `branch` 空对象防御：`data["branch"]` 为对象（无文本）时视为 null，不抛异常（`VcsBranchDto.branch: String?` 兼容） |
| ⑥ listPtyShells | 端点路径错误：实测确认正确端点（候选 `/api/pty` 列表）；包裹 `runCatching` 防止错误响应 decode 崩溃 |
| ⑦ listProjects | `Project` DTO 增加 `canonical` 字段映射（或 `worktree` 回退 canonical）；`displayName` 不再回退 hash |
| ⑧ listCommands | `description` 缺失属服务器契约：UI 用 template 摘要兜底或接受 null（不改 DTO 必填性） |
| ⑧b completeProviderOauth | 补 `/api` 前缀（需 POST 实测确认，实施时验证） |
| ⑧c getServerPaths | `/api/location` 语义不符：`ServerPaths` 字段映射调整或降级（P2 可选） |

## 5. 方案对比（为何不是打补丁）

| 方案 | 判定 | 理由 |
|------|------|------|
| getMcpStatus 解析对齐 | ✅ 根因 | 对齐真实契约；消费链完整；错误不再静默 |
| ~~catch 异常返回空 map~~ | ❌ 补丁 | MCP 面板永远空白，问题"消失"而非修复 |
| 仅拦截 started 创建消息体（探索 B 原案） | ❌ 补丁 | 消息能出现但 part 错乱（delta 无宿主/重复 part）——需 part 映射层 |
| 完整 V2 事件→领域映射（本设计 §4.2） | ✅ 根因 | 消息+part+完成闭环；partId 派生修复 `""` 设计缺陷 |
| 仅修 fetchSessionStatus 解析（方案 A） | ⚠️ 止血 | 恢复 REST 兜底，但 Activity 层失活 + active 语义误判仍在 |
| 解析修复 + SSE 驱动 FSM（本设计 §4.3） | ✅ 根因 | FSM 有 v2 真相源（execution 生命周期）；active 只做兜底 |

## 6. 影响面

- **SseClientV2.kt**：handleEvent 增加映射分支（调用 V2SseMapper）；`mapV2DeltaEvent` partId 派生修正
- **V2EventParser.kt**：reasoning/text/tool/step 事件不再落 Unknown（由 V2SseMapper 优先接管；V2EventParser 保留 shell/usage 等非消息事件）
- **SseEvent.kt / Part.kt**：无新变体（复用 MessageUpdated/MessagePartUpdated/MessagePartDelta）；Part 无需改
- **MessageEventHandler.kt**：`handleMessagePartUpdated` 已有（检查是否全量实现）；Assistant 消息 part 播种补充；partType 判定适配派生 id
- **SessionStateService.kt / SessionStateFSM.kt**：FsmEvent 扩展 + mapSseEventToFsm 新分支 + 转移矩阵用例
- **V2ApiClient.kt**：getMcpStatus / fetchSessionStatus / getVcs / listPtyShells / listProjects / getConfig 系列
- **DTO**：McpStatusEntry(+error)、Project(+canonical)、VcsBranchDto 不变
- **穷举 when 检查**：若新增 SseEvent 变体需改 `EventDispatcher.extractSessionId`（无 else）；本设计**不新增变体** → 零连锁
- **测试**：V2ApiClientTest（mock 格式更新+新用例）、V2MappersTest、SessionStateFSMTest、新增 V2SseMapperTest

## 7. 未确认项（实施时先补测，以实测为准）

1. `session.retry.scheduled` payload 结构（未触发重试未抓到）——影响 Retry 状态映射
2. `/api/config` 的 `info` 子对象结构——决定 getConfig 修复方式
3. `/api/session/active` 的 type 完整枚举（目前仅见 "running"）
4. `listPtyShells` 正确端点路径（候选 `/api/pty`）
5. `completeProviderOauth` 缺 `/api` 前缀是否真 bug（需 POST 实测）
6. `session.tool.failed` 事件是否存在（tool 失败路径）
7. 多 step 场景：`step.started` 多次触发时同一 assistantMessageID 还是新消息（影响消息创建幂等设计）——从实测样本看同一 turn 内 `step.started` 可能多次（tool-calls 循环），**同一 assistantMessageID**（processor.ts 语义），幂等 upsert 天然处理，但需实测确认

## 8. 验证策略

- 每任务：`./gradlew :app:compileDevDebugKotlin`（120s）+ 相关单测（180s）
- 阶段 3 完成后模拟器端到端：真实 v2 服务器发送消息 → 用户消息即时出现 → AI 回复流式渲染（reasoning/text/tool）→ 完成后 completed 状态 → 会话状态 busy→idle
- 回归：按 `docs/regression-guide.md` **完整回归**（状态机/数据流变更分类）
- MCP 面板：设置页 MCP 服务器列表显示 connected/disabled 状态
