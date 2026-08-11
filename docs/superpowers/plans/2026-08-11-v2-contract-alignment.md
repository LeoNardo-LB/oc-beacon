# opencode v2 契约对齐实施计划（V2 Contract Alignment）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 opencode v2 下三个 P0 问题（MCP 状态面板崩溃 / 发送消息后不显示 / 会话状态判定失效）+ 清扫 P1/P2 解析缺陷——全部对齐真实 v2 契约（设计见 `docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md`），不打绕过症状的补丁。

**Architecture:** v2 事件体系与 v1 不同（无 `message.updated`/`session.status`；生命周期事件 `input.admitted → execution.started → step.started → part 流 → step.ended → execution.succeeded`）。修复=把 v2 事件完整翻译进既有成熟管线（MessageUpdated/MessagePartUpdated/MessagePartDelta → MessageEventHandler → combine → UI），FSM 由 execution 生命周期事件驱动，REST 只做兜底。

**Tech Stack:** Kotlin / Jetpack Compose / Hilt / Ktor / JUnit4 + MockK

**Spec:** `docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md`

## Global Constraints

- 编译：`./gradlew :app:compileDevDebugKotlin`（120s 超时）；单测：`./gradlew :app:testDevDebugUnitTest --rerun --tests "..."`（180s 超时）；完整单测：`./gradlew :app:testDevDebugUnitTest --rerun`（180s）
- **不修改 version.properties**
- Chat 相关文件（EventDispatcher/MessageEventHandler/SseClientV2/V2EventParser）遵守 ChatScreen 编辑协议精神：**改前先 Read、改后 compileDevDebugKotlin、编译成功后提交**
- **以实测服务器为准**（`http://127.0.0.1:4199`，`-u opencode:leo12321`）：opencode v2 仍在演进（上游源码 `session.next.text.started`+textID 与实测 `session.text.started`+ordinal 不同版本）——**禁止照抄上游源码，实施前若服务器升级需复测 §3 契约**
- 只读约束：实施期间对服务器的写操作仅限测试会话的创建/删除/发送（probe 会话用完即删，勿污染用户会话）
- 不引入新依赖库

---

## 阶段 1：MCP 面板恢复（①④）

### Task 1: getMcpStatus 解析对齐真实契约

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClient.kt:878-886`（getMcpStatus）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/dto/response/McpResponses.kt:5-8`（McpStatusEntry 增 `error` 字段）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt`（新增用例）

**Steps:**
- [ ] **Step 1: 读** `V2ApiClient.kt` getMcpStatus 与 `McpResponses.kt`、`V2Mappers.kt` flexibleList
- [ ] **Step 2: 改 getMcpStatus**：`flexibleObject` → `flexibleList`；逐元素取 `name` + `status.status` + `status.error?`；构造 `Map<String, McpStatusEntry>`
- [ ] **Step 3: 改 DTO**：`McpStatusEntry(status: String, error: String? = null)`
- [ ] **Step 4: 改测试**：V2ApiClientTest 新增"data 数组 + 嵌套 status 对象"格式用例（含 failed 带 error）
- [ ] **Step 5: 验证**：`compileDevDebugKotlin` + `testDevDebugUnitTest --rerun --tests "V2ApiClientTest"`
- [ ] **Step 6: 冒烟**：curl `GET /api/mcp` 确认结构未变；如可能跑 `:app:testDevDebugUnitTest --rerun --tests "McpRepositoryImplTest"`（若存在）
- [ ] **Step 7: Commit**：`fix(v2): getMcpStatus 解析对齐 /api/mcp 真实契约（data 数组 + 嵌套 status）`

### Task 2: getConfig 系列修复（含语义实测）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClient.kt:953-987`（getConfig/getGlobalConfig/updateConfig/updateGlobalConfig）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/dto/response/ConfigResponses.kt`（视实测结构调整）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt`

**Steps:**
- [ ] **Step 1: 实测确认**（必做）：`curl -u opencode:leo12321 "http://127.0.0.1:4199/api/config"` 完整输出，确认 `info` 子对象是否含 `mcp`/`disabled_providers`/`model` 字段
- [ ] **Step 2: 读** 现有 4 方法 + `ServerConfigResponse` DTO + 消费方（McpRepositoryImpl:30 `providerApi.getConfig(conn).mcp`）
- [ ] **Step 3: 改解析**：数组元素取 `info` 子对象 decode（若实测确认）；或换正确端点；4 方法同步修改
- [ ] **Step 4: 改测试**：新增裸数组格式用例
- [ ] **Step 5: 验证**：编译 + V2ApiClientTest
- [ ] **Step 6: Commit**：`fix(v2): getConfig 系列解析对齐 /api/config 数组契约`

---

## 阶段 2：会话状态判定（③）

### Task 3: fetchSessionStatus 解析修复 + 测试 mock 更新

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClient.kt:212-228`（fetchSessionStatus）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt:341-354`（mock 数组→对象）

**Steps:**
- [ ] **Step 1: 读** fetchSessionStatus + activeSessions（238-254，参照正确写法）+ `RestSessionStatusInfo` 定义 + `SessionRepositoryImpl.fetchSessionStatuses`（263-277）的 type→SessionStatus 映射
- [ ] **Step 2: 改解析**：`unwrapList` → `root["data"]?.jsonObject` 遍历 `mapValues`
- [ ] **Step 3: 改 type 映射**：服务器返回 `"running"` → `RestSessionStatusInfo(type="running")`（**删除硬编码 "busy"**）；确认 `SessionRepositoryImpl` 的 `when(type)` 含 `"running"` → `SessionStatus.Busy`，缺失则补
- [ ] **Step 4: 改测试**：更新 mock 为 `{"data":{"ses_1":{"type":"running"}}}`；新增对象格式用例 + "active 含后台会话"用例
- [ ] **Step 5: 验证**：编译 + V2ApiClientTest + SessionRepository 相关测试
- [ ] **Step 6: Commit**：`fix(v2): fetchSessionStatus 解析 /api/session/active 对象格式 + running→busy 映射`

### Task 4: FSM 由 v2 execution 生命周期事件驱动

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/SessionStateFSM.kt`（FsmEvent 扩展 + 转移矩阵）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateService.kt`（mapSseEventToFsm 新分支）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2.kt`（execution 事件 → SseEvent 变体或复用 SessionStatus 语义）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionStateFSMTest.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceTest.kt`

**Steps:**
- [ ] **Step 1: 读** SessionStateFSM（FsmEvent 全集/转移矩阵）、SessionStateService.mapSseEventToFsm（140-160）、SseClientV2.handleEvent
- [ ] **Step 2: 选型确认**：`FsmEvent` 新增 `ExecutionStarted`/`ExecutionSucceeded` 变体（更清晰）vs 复用 ClientSendParts/SseIdle（改动最小）——**推荐新增变体**（FSM 穷举转移矩阵无 else，编译期强制全 case）
- [ ] **Step 3: 改 FSM**：转移矩阵新增：Busy/Waiting ← ExecutionStarted；任意 Core → Idle ← ExecutionSucceeded（带 forceComplete 语义，参照 SseIdle）；穷举全 case 补测试
- [ ] **Step 4: 改 Service**：mapSseEventToFsm 新分支；extractSessionId 确认覆盖
- [ ] **Step 5: 改 SseClientV2**：`session.execution.started` / `session.execution.succeeded` 在 mapV2DeltaEvent 前拦截为对应 SseEvent
- [ ] **Step 6: 改测试**：SessionStateFSMTest 全转移新增用例；SessionStateServiceTest 事件链用例
- [ ] **Step 7: 验证**：编译 + FSM/Service 测试全绿
- [ ] **Step 8: Commit**：`feat(v2): FSM 由 execution.started/succeeded 生命周期事件驱动（v2 状态真相源）`

---

## 阶段 3：消息链路完整适配（②，核心）

### Task 5: V2 SSE 事件映射层（V2SseMapper）

**Files:**
- Add: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2SseMapper.kt`（新文件：v2 事件 → SseEvent 映射纯函数）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2.kt`（handleEvent 调用 mapper；mapV2DeltaEvent partId 派生修正）
- Add: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2SseMapperTest.kt`

**Steps:**
- [ ] **Step 1: 读** SseClientV2 全量 + `MessageEventParser.kt`（MessageUpdated 线格式）+ `V2EventParser.kt` + `Part.kt` + `Message.kt`（Message.User/Assistant 构造签名）
- [ ] **Step 2: 写 V2SseMapper**（纯函数，输入 eventType + props JsonObject，输出 SseEvent?）：
  - `session.input.admitted` → `MessageUpdated(Message.User(id=inputID, sessionId, time=TimeInfo(now), summary=UserSummary(body=text)))`
  - `session.step.started` → `MessageUpdated(Message.Assistant(id=assistantMessageID, sessionId, time=TimeInfo(now), agent, model?))`
  - `session.reasoning.started` → `MessagePartUpdated(Part.Reasoning(id="${msgId}_ord_${ordinal}", text=""))`
  - `session.reasoning.delta` → `MessagePartDelta(messageId, partId=派生, field="reasoning", delta)`
  - `session.reasoning.ended` → `MessagePartUpdated(Part.Reasoning(id=派生, text=完整))`
  - `session.text.started/delta/ended` 同上 field="text"
  - `session.tool.input.started` → `MessagePartUpdated(Part.Tool(id=call_id, status=pending))`
  - `session.tool.input.delta` → `MessagePartDelta(messageId, partId=call_id, field="output", delta)`
  - `session.tool.called/success/failed` → `MessagePartUpdated(Part.Tool(...))`（状态/output 更新）
  - `session.step.ended` → `MessageUpdated(Assistant.copy(cost, tokens))`（不完成）
  - `session.execution.succeeded` → 完成：`MessageUpdated(Assistant.copy(time.completed=now))`（需查当前消息——经 EventDispatcher 或消息缓存，见 Step 4 选型）
- [ ] **Step 3: 修 mapV2DeltaEvent**：partId 从 `""` 改为按 ordinal 派生（复用 mapper 的派生函数）
- [ ] **Step 4: 选型确认 execution.succeeded 的完成实现**：方案 X：mapper 产出特殊 SseEvent（如复用 MessageUpdated 但 completed 时间由 handler 填 now——需要消息查询能力，mapper 纯函数拿不到）；方案 Y：SseClientV2 在拦截时构造带 `TimeInfo(now, now)` 的 Assistant（不知道确切 created 但可接受，REST 会覆盖）。**推荐 Y 简化版**：execution.succeeded 时对已知最近 assistant 消息补 completed——若复杂，退化为"FsmEvent 驱动 + REST 兜底 completed"，在计划执行时记录决策
- [ ] **Step 5: 接线 SseClientV2**：handleEvent 中 mapper 优先于 mapV2DeltaEvent/V2EventParser；确认 shell/usage 等仍走 V2EventParser
- [ ] **Step 6: 写测试**：V2SseMapperTest 全事件映射用例（用实测样本 JSON）
- [ ] **Step 7: 验证**：编译 + V2SseMapperTest + SseClientV2 相关测试
- [ ] **Step 8: Commit**：`feat(v2): V2 SSE 事件→领域事件映射层（消息/part 创建与流式闭环）`

### Task 6: MessageEventHandler part 适配

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandler.kt`（handleMessagePartUpdated 完整实现确认 + Assistant part 播种 + partType 判定适配）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTest.kt`（若存在，否则 EventDispatcher 相关测试）

**Steps:**
- [ ] **Step 1: 读** MessageEventHandler 全量（handleMessagePartUpdated 现状、flushPendingDeltas partType 判定、handleMessageUpdated）
- [ ] **Step 2: 确认/补全 handleMessagePartUpdated**：对 `Part.Reasoning`/`Part.Tool` 的 upsert 语义（Part.Text 已有）；part 不存在时插入（id 非空后天然正确）
- [ ] **Step 3: partType 判定适配**：delta 定位改用派生 partId（Task 5 后非空）；`firstOrNull{it.id==partId}` 命中的 part 类型决定 reasoning/text
- [ ] **Step 4: Assistant part 播种检查**：handleMessageUpdated 对 Assistant 是否需要播种（设计上 part 由 started 事件创建，无需播种——验证无重复创建路径即可）
- [ ] **Step 5: 测试**：新增"v2 事件链（admitted→step.started→reasoning started/delta/ended→text started/delta/ended→step.ended→execution.succeeded）→ _messages/_parts 最终状态"集成用例
- [ ] **Step 6: 验证**：编译 + 相关测试
- [ ] **Step 7: Commit**：`feat(v2): MessageEventHandler 支持 v2 part 映射（ordinal 派生 id / reasoning 类型判定）`

---

## 阶段 4：清扫（⑤⑥⑦⑧）

### Task 7: P1/P2 解析缺陷修复

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClient.kt`（getVcs:1090-1097 / listPtyShells:1271-1279 / listProjects:1121-1128 / completeProviderOauth:920）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/SseEvent.kt`（Project DTO 加 canonical）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/dto/response/FileResponses.kt`（VcsBranchDto 防御）
- Modify: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt`

**Steps:**
- [ ] **Step 1: getVcs 防御**：`data.branch` 为 JsonObject（空）→ 视为 null；String 正常
- [ ] **Step 2: listPtyShells**：实测确认正确端点（候选 `GET /api/pty`）；修正路径 + `runCatching`
- [ ] **Step 3: listProjects**：Project DTO 加 `canonical` 字段，`V2SessionMapper`/相关映射处回退使用
- [ ] **Step 4: listCommands**：description 缺失 → 确认 UI 兼容（null 渲染）；如需展示用 template 摘要（记录决策）
- [ ] **Step 5: completeProviderOauth**：补 `/api` 前缀（与其余端点一致）
- [ ] **Step 6: 测试**：V2ApiClientTest 新用例（branch 对象/pty 错误响应/project canonical）
- [ ] **Step 7: 验证**：编译 + V2ApiClientTest 全绿
- [ ] **Step 8: Commit**：`fix(v2): 清扫解析缺陷（getVcs 防御/listPtyShells 端点/listProjects canonical/oauth 前缀）`

---

## 阶段 5：端到端验证与回归

### Task 8: 模拟器端到端 + 完整回归

**Steps:**
- [ ] **Step 1: 构建**：`./gradlew :app:assembleDevDebug`（300s 超时）
- [ ] **Step 2: 安装启动**（模拟器调试派 subagent）：连接真实 v2 服务器（`10.0.2.2:4199`）
- [ ] **Step 3: 消息链路验证**（subagent + 人工清单）：
  - 发送消息 → 用户消息**即时出现**（不再需要退出重进）
  - AI 回复流式渲染：reasoning 先出 → text 逐字 → 工具调用卡片 → 完成后 completed
  - 发送多条连续消息顺序正确
  - 重进会话消息完整（REST 合并无重复）
- [ ] **Step 4: 状态验证**：发送→顶部进度条出现（Busy）→ 回复完成→进度条消失（Idle）；多会话列表 busy 徽标正确
- [ ] **Step 5: MCP 面板验证**：设置页 MCP 服务器列表显示各服务器状态（connected/disabled），不再报错
- [ ] **Step 6: 回归**：按 `docs/regression-guide.md` 完整回归（状态机/数据流变更分类）——12 能力域清单逐项过
- [ ] **Step 7: 全量单测**：`./gradlew :app:testDevDebugUnitTest --rerun` 全绿
- [ ] **Step 8: 登记 backlog**：本计划未覆盖项（如 Activity 层细化、retry 状态映射）登记 `backlog.md`

---

## 测试影响汇总

| 测试 | 影响 |
|------|------|
| `V2ApiClientTest` | ❌ 必改：fetchSessionStatus mock（数组→对象）+ 新增 5+ 用例 |
| `SessionStateFSMTest` | 增补：ExecutionStarted/ExecutionSucceeded 全转移用例（穷举矩阵无 else，编译期强制） |
| `SessionStateServiceTest` | 增补：execution 事件链用例；现有用例不破坏（mock repo 路径） |
| `V2SseMapperTest`（新） | 新增：全事件映射（实测样本 JSON） |
| `MessageEventHandlerTest`（如存在） | 增补：v2 事件链集成用例 |
| 其余（V2MappersTest/SerializationTest 等） | 检查 McpStatusEntry 加字段后的序列化用例（`error` 可空默认 null 不破坏） |

## 风险与决策点

- **execution.succeeded 的 completed 实现**（Task 5 Step 4）：mapper 纯函数无消息查询能力 → 选型 Y（构造时间由事件时间代替）或退化为 REST 兜底；执行时记录决策
- **多 step 工具循环**：`step.started` 同 turn 内可能多次（tool-calls 循环）——确认同一 assistantMessageID（幂等 upsert 天然处理），实测确认后写入测试
- **part 派生 id 稳定性**：`"${msgId}_ord_${ordinal}"` 需与 REST 路径的 part id 一致吗？——**不需要**（REST 路径 part id 为 ""/不固定，merge 按消息级合并，part 以 SSE 流为准）
- **ChatScreen 协议**：本计划不触碰 ChatScreen.kt；EventDispatcher/MessageEventHandler 按"改前 Read、改后编译、编译成功提交"执行
