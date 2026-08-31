# DSH（DeepSeek Harness）原生接入设计（backlog #269）

> 状态：**架构骨架定稿，探针数据回填中**（2026-08-31 起）。
> 基线：`docs/research/2026-08-30-dsh-integration-feasibility.md`（#268 调研，差距矩阵 50 行）。
> 用户裁决（2026-08-31）：BM25 关单后立即启动；开发/调研/探针三线并行；TDD；真机能测的全测，自动化证据即验收。

## 1. 已实测事实（2026-08-31 主会话探针，先于探针代理）

| 事实 | 证据 |
|---|---|
| 活实例可达（loopback） | `curl http://127.0.0.1:3080/` → 200 |
| **信封非标准 JSON-RPC 2.0**：请求 `{"type":"client-request","rpcId":str,"method":str,"payload":{}}`；响应 `{"type":"server-response","rpcId":...,"result":{ok/error}}` | POST /api/session.list 带标准 2.0 body → 200 + `bad-request` + zod issues（期望 type/rpcId/method/payload） |
| 错误恒 HTTP 200 + result.error 闭集错误码 | 同上（`code:"bad-request"`） |
| `ws` Node 库可用（探针脚本依赖） | dsh checkout node_modules/ws 存在 |

> 旧调研文档中「JSON-RPC 信封」表述以此为准修正；其余探针结论见 §5 占位表（回填自 /tmp/dsh-probes/）。

## 1.5 帧与投影契约（2026-08-31 源码实证：dsh-host-apiproxy/lib/types/api/events.d.ts + dsh-session-projection）

### WS 帧词汇表（完整）

| 流 | 帧 | 载荷要点 | → SseEvent 映射（草案） |
|---|---|---|---|
| mux | `session/event` | 原始 SessionEvent 透传 + 可选 ToolEventView（宿主算的渲染意图，不持久化） | 按 SessionEvent 类型分派（消息族→MessageUpdated/PartUpdated） |
| mux | `session/subscribed` | 每已附会话一个，含 `lastSeq`（开流基线） | 连接层信号（对账起点） |
| mux | `approval/requested` / `resolved` | 可应答 server-request（rpcId 回程 /api/respond）| PermissionAsked / PermissionReplied |
| mux | `question/requested` / `resolved` | AskUserQuestionItem[]；resolved 带 answered/cancelled | QuestionAsked / QuestionReplied/Rejected |
| mux | `session/queue` | **瞬态收件箱整快照**（queued/steering/context 放置；未 claim 不持久） | 对齐 V2 Inbox admission 语义 → 堆积消息域 |
| mux | `session/jobs` | 后台任务整快照（注册/停止/结算/清理后全量推；空集发 `[]`） | ShellJob* 近似（语义待对表） |
| mux | `session/projection` | `{key, value, seq}` 投影单元活推；higher-seq-wins；由 history 尾页 projections 块播种 | goal/权限预设等辅助态 → Misc/SessionNext |
| mux | `stream/error` | RpcError | 连接层错误信号 |
| host | `host/session-added` | lineage/origin(subagent)/cwd/agentPreset/blank | SessionCreated |
| host | `host/session-removed` | — | SessionDeleted |
| host | `host/session-status` | running 翻转（无 turn 位置） | SessionStatus(busy/idle) |
| host | `host/agent-error` 等 | 无 turn 位置的活失败出口 | SessionError |
| host | `host/workspace-*` / `archived-sessions-changed` | 整快照姿态；重连以 workspace.list 再基线 | Workspace 域（oc-beacon 无直接对应→能力位隐藏或后续） |

### SessionEvent 词汇普查（2026-08-31 源码侧：dsh-session/lib/types/types.d.ts + 19 个扩展包 declaration merging）

**核心（dsh-session，13 型）**：`turn/start|end`(reason) · `step/start|end` · `user/message`(UserMessage，source 区分人类/注入/goal 轮) · `assistant/chunk`(StreamChunk，token 级重放保真) · `assistant/message`(整装+usage+interrupted 前缀标记，**derived history 用它**) · `tool/call`(callId/name/原始参数串) · `tool/result`(ToolResultMessage+error+meta) · `todo/write`(整快照 last-wins) · `request/header|context`(log-only) · `session/end-seed`(种子边界，log-only)。

**扩展包**：session/title(+llm-request) · compaction/start|end|summary|prune · goal/change · approval/asked|decided|policy · plan/mode · sandbox/mode · permission/preset · agent-preset/selected · agent/inbox/spliced · command/run|done · llm/retry(-started) · schedule/change · subagent/descriptor · tool/code-dispatch(-start) · tool-workflow/run-start|agent-start|agent-end|run-end · feedback/record · web/deepseek-search-llm-request。合计 ≈38 键（实测分布待 P-4 活样本普查交叉验证）。

**oc-beacon 映射分级**：
- **Tier 1（transcript，v1 必须）**：user/message→MessageUpdated(user)；assistant/chunk→MessagePartDelta；assistant/message→MessageUpdated(assistant 整装)；tool/call|result→工具卡 Part；turn/end(reason)→SessionIdle；step/start→活动态（Waiting/ToolCalling）；todo/write→TodoUpdated（整快照直配）。
- **Tier 2（会话元数据）**：session/title→SessionUpdated；compaction/*→压缩状态；subagent/descriptor→会话子节点；approval/asked|decided（durable 面）→PermissionAsked/Replied。
- **Tier 3（log-only/忽略）**：request/header|context、session/end-seed、*-llm-request、schedule/change 等——映射器未知类型容错（记日志不崩）。

### 活体交叉验证（2026-08-31，主会话独立解析 37,392 帧 WS 监听日志）

帧 method 分布：`session/event` 21086 · `session/projection` 16291 · `session/subscribed` 5 · `host/session-status` 4 · `session/queue` 4 · `session/jobs` 2——**与源码词汇表 100% 吻合，零未知帧型**。SessionEvent 分布：assistant/chunk 20881 · step/end|start 各 98 · agent/inbox/spliced 4 · turn/start|end · llm/retry-started——全部在源码普查清单内。

**StreamChunk 子类型协议（实测新增细节）**：`chunk.type ∈ {reasoning-delta(18080), text-delta(2501), block-start(194), usage(98), block-end(8)}`，均带 `index` 定位。→ 映射定稿：`block-start`→MessagePartUpdated(新 part)；`text-delta`/`reasoning-delta`→MessagePartDelta(field 按 chunk.type)；`block-end`→MessagePartUpdated(part 定稿)；`usage`→SessionUsage（SessionNext UsageUpdated 对位）。

**WS 下行信封统一性（实测）**：帧亦走 `server-request` 信封 `{type:'server-request', rpcId, method, payload}`——与 RPC 响应同族，`method` 即帧型。每个 SessionEvent 携带 `seq`+`time`（如 `assistant/chunk seq=220592 time=1788109101278`）。

### 关键契约结论（修正旧调研）

1. **`since` 重连游标 v1 未实现**（mux 签名存在但被忽略）——重连=重开流+重拉 history（R3 处置路径被源码注释直接证实："reconnection = reopen the stream + refetch history"）。
2. **投影面只覆盖辅助态**（goal/sessionListMetadata/imageLimits/权限预设/plan-mode）；**消息转录不在投影面**——transcript 由 history 窗口原始事件承载。
3. **整值规则（load-bearing）**：state-carrying 日志事件必带完整后态（last-wins）→ 我们的映射是**逐事件无状态变换**，非增量 fold 状态机；R5「客户端 fold 48 事件」成本大幅缩水。
4. **分页按 append-origin 消息对齐**（"page boundaries align to append-origin message"）→ history 页 → 消息列表段映射干净。
5. approval/question 开流即重放未决帧（rpcId 原样复用）→ 客户端刷新恢复基线免费获得。
6. HistoryEntry = `{event, view?}`；history 尾页附 `SessionProjectionsBlock{asOfSeq, values}`。

## 1.6 传输层契约（P-2/P-3/P-1 探针回填，证据 /tmp/dsh-probes/P2P3-ws-fences.md）

**Android 客户端硬约束**：
1. **只能走 WS**：`dsh web` 部署把 events 路径 GET 拦为 426（SSE 变体仅纯 fetch 部署存在）。双流 events.mux + events.host 同时开（官方 WebRuntimeLoop 参考实现：双 WS + host.describe RPC 三就绪才算 connected）。
2. **WS 纯下行**：客户端发任何数据帧 = `close(1008, "downlink only")`。上行全部走 HTTP（RPC POST + /api/respond）。协议层 PING/PONG 不受限（ws 库自动应答）。
3. **服务端零心跳、无空闲踢线**（190s 实测 + 源码零计时器）→ **OkHttp `pingInterval(20-30s)` 客户端自证活性必配**；doze 冻结 socket 靠重连兜底。
4. **握手**：Host 栅栏对 WS 升级生效（loopback ∪ trustedHosts）；非浏览器客户端**无 Origin 要求**（存在才校验一致性）；无子协议、无 permessage-deflate。OkHttp 按 URL 自动出 `Host: 127.0.0.1:3080` 即过。
5. **重连**（官方参考）：任一流断 → 全新代重开；退避 500ms×2ⁿ 封顶 10s 带抖动；**无 since 游标**。补偿 = mux 重连后自动重推**全部会话** `session/subscribed{lastSeq}` 基线 → 客户端比对本地已应用 seq 发现缺口 → `session.history{beforeSeq, maxMessages}` 向前翻页回填（消息对齐分页）。
6. **帧信封**：与 RPC 响应同族 `server-request`（method=帧型，payload=帧体）；纯推送帧 rpcId 每帧新铸无意义；approval/question requested 帧 rpcId 稳定（pending 注册表）供 /api/respond 回程。
7. **SessionEvent 已知 49 型开放联合**（known-event-types.js）：未知 type 按 data 宽透传——**映射器必须容错默认分支**（记日志不崩）。含 team/*、hook/* 等 npm d.ts 普查外型。

**P-1 特权面结论**：栅栏只看 **Host 头**（不看 socket 远端/XFF）。adb reverse（Host=127.0.0.1 字面量 + 无 Origin）→ 全部栅栏（含特权方法 15 个：settings/credentials/agentPreset 读写、host.openPath、llm.discoverModels）**按构造通过**，无需服务端改动。反面实证：本部署 LAN 直连虽过栅栏 2（NIC 派生信任）但特权面仍 403。该 fence 是 DNS-rebinding 防线**不是鉴权**——Android 侧不得当鉴权依赖。

**P-3 trustedHosts**：插件 `client-connection` 的 `Config.trustedHosts: string[]`；CLI `dsh web --trusted-host`；持久化 `$DSH_HOME/profiles/web/cordis.patch.yml`；裸 authority（host 或 host:port，WHATWG 规范形）；bind 0.0.0.0 时 resolveLanTrust 自动加全部非 internal IPv4 NIC（含 Tailscale 100.x）。

## 1.7 存储事件普查（P-4 中间数据，366 会话 / 2,032,467 事件 / 40 型 / 4 坏行）

**checkpoint 批量块格式（重大发现）**：`reasoning-chunks`(1,089,179 · 53.6%) / `text-chunks`(128,834) / `tool-call-chunks`(57,180)——存储层把单 chunk 压成批量事件：信封 `seq0/time0`（批起点），data `{turn, step, index, dt:[逐 token 间隔ms], texts:[...]}`（tool-call-chunks 另带 id/name/args 逐 token）。不在 known-49 清单内（checkpoint-policy 插件开放联合写入）。

**分布**：assistant/chunk 389,395 · tool/code-dispatch(-start) 各 ~66,690 · step/start|end 各 ~41,6xx · assistant/message 41,332 · tool/result 41,061 · tool/call 41,051 · agent/inbox/spliced 6,732 · user/message 4,595 · llm/retry 3,566 · turn/start 1,661 / turn/end 1,641 · …尾部：goal/change 120 · command/run|done 79 · compaction/* 70/62/11 · approval/asked|decided 2/2。

**fold 范围决策（据此定稿）**：
- **历史转录**：以 `user/message` + `assistant/message`（整装，含 reasoning content）+ `tool/call|result` + `todo/write` + `session/title` + `compaction/summary` 为主——chunk 族（单/批量）**不进历史 fold**（流式保真专用），历史成本进一步坍缩。
- **实况流式**：只处理 `assistant/chunk`（含 block-start/block-end/text-delta/reasoning-delta/usage 五子型）；重连回填用 `session.history{beforeSeq}` 的**整装形态**（history 尾页由 assistant/message 主导）。
- **信封二态**：活事件 `{type,seq,time,data}`；checkpoint 批 `{type,seq0,time0,data}`——解析器按 key 区分（`seq0` 存在=批量块，按 texts/dt 展开或不展开视用途）。

## 2. 架构定稿（探针无关部分，2026-08-31）

### 2.1 服务器类型维度（结构级先行）

- `ServerConfig` 增 `serverType: ServerType = ServerType.OpenCode`（@Serializable 全默认值 → DataStore 旧 JSON 零迁移反序列化）。`ApiVersion` 语义不变（OpenCode 内部 V1/V2 探测）；DSH 条目 `serverType=Dsh` 时跳过 ApiVersionDetector 的 health 双探。
- `ServerConnection` 携带 serverType；各域 `*ApiImpl.pick(conn)` 从二分改三分：`DshApiClient` 与 V1ApiClient/V2ApiClient 并列实现同一批域接口。
- 降级先例对齐：V1ApiClient 已有 `backgroundSession=false` / `activeSessions=emptyMap` 的方法级降级；DshApiClient 对缺失域（PTY/shell/git/mcp/share/todo/project…）按同模式返回空/ false /抛 `UnsupportedServerCapability`。

### 2.2 能力位（UI 自动隐藏，复用 #78/#85/#692 先例）

`ServerCapabilities.of(apiVersion)` 扩为 `of(serverType, apiVersion)`；DSH 下大量能力位置 false（share/todo/background/configEditable…），UI 入口按能力位隐藏，不写服务器类型特判散弹。

### 2.3 事件层：SseEvent 是内部通货（本次接入的架构基石）

`EventDispatcher.processEvent(event: SseEvent, serverId: String)` 是唯一事件注入口（注册表路由 + 所有权去重 + FSM 桥接）。DSH 不新建平行事件体系，只加**两个生产者**：

1. **DshHistoryFolder**（R5 最大成本项）：`session.history` 原始日志 → fold 成 `SseEvent` 序列（MessageUpdated/MessagePartUpdated/SessionIdle…）→ 经 processEvent 重放。历史加载、断连对账（R3 无游标 → 全量 fold 对账）共用。
2. **DshWsEventClient**：`/api/events.mux` + `/api/events.host` 双下行 WS → 帧映射为 `SseEvent` → processEvent。重连/keepalive 策略按探针 P-2 回填。

收益：FSM（SessionStateService）、红点水位线（UnreadStateStore maxCompleted 只增不减）、通知、SessionNext 全量复用。

**红点重放安全性（2026-08-31 读码实证，UnreadBadgeService.kt:208-218）**：`isUnread` 是水位线状态的纯函数（`last > max(readTimes[sid], allReadAt)`），非事件计数。fold 重放旧历史只会把 maxCompleted 抬升到真实服务器时刻：已读会话天然无红点；离线期间完成的会话正确亮红点（对齐 opencode 离线补漏语义）；幂等重放结果稳定。TDD 用例：fold 重放已读会话 → isUnread=false；重放离线完成会话 → true；同一历史二次 fold → 状态不变。

### 2.4 错误层

`DshApiError`：HTTP 200 + `result.error.code` 闭集 → 映射到既有 `ApiErrorTranslator` 领域错误（网络/服务器/认证区分语义在 DSH 下重定义：无 401/4xx 判别面）。TDD 先行：闭集错误码 → 领域错误映射表单测。

### 2.5 连接/版本探测

- 存活信号：首个 RPC（session.list）成功即存活（DSH 无 /health）。
- 版本锁定（R1）：`serverVersion` 持久化 + 变更检测；pre-release 无兼容承诺 → 不匹配时 UI 明示。

### 2.6 RPC 方法面 → oc-beacon 七域接口映射（2026-08-31 源码提取 52 方法，形状待 P-4 回填）

方法域：`session.`×12（list/create/rename/fork/search/cancel/prompt/history/models/selectModel/attachment/updateQueue）· `subagent.`×4（list/prompt/history/interrupt）· `workspace.`×7（list/create/rename/delete/insertBefore/insertSessionBefore/archiveSession）· `host.`×5（describe/listDirectory/createDirectory/openPath/pickDirectory）· `llm.`×3（providers/models/discoverModels）· `agentPreset.`×6 · `goal.`×6 · `credentials.`×3 · `settings.`×5 · `skill.list`。

| oc-beacon 域接口 | DSH 方法集 | 备注 |
|---|---|---|
| SessionApi | session.list/create/rename/fork/search/cancel + workspace.archiveSession + subagent.list | **无 session.delete**（52 方法终局确认；存档≠删除，删除能力位置 false）；search=服务端搜索直配（部署可关→本地降级） |
| MessageApi | session.prompt/history/updateQueue + subagent.prompt | updateQueue=收件箱编辑/删除/取消（对齐 V2 Inbox）；分页 beforeSeq/maxMessages |
| SystemApi | host.describe（就绪握手/版本）+ settings.*（特权） | 存活=describe 成功；dispose 无对应 |
| FileApi | host.listDirectory/createDirectory + workspace.list | **文件内容读取无方法**（host.openPath 是宿主侧打开，特权）→ 文件查看/搜索能力位置 false |
| TerminalApi | — | PTY 域整体缺失（终局确认） |
| ProviderApi | llm.providers/models + agentPreset.list/select + credentials.*（特权） | 模型选择= session.selectModel + session.models |
| ShellApi | — | shell 域缺失；后台任务= session/jobs 帧 + goal/subagent 域呈现 |

**组件拆卡清单（TDD 顺序）**：① DshEnvelope 编解码（RPC+WS 双面共用）② DshApiError 映射 ③ DshRpcClient（HTTP POST 面 + respond 回程）④ DshWsEventClient（双 WS + pingInterval + 官方退避 500ms×2ⁿ 封顶 10s）⑤ DshEventMapper（帧→SseEvent，49 型开放联合容错）⑥ DshHistoryFolder（整装事件族→SseEvent 重放；二态信封 seq/seq0）⑦ ServerType 三分路由 + ServerCapabilities(DSH) ⑧ 各域 *ApiImpl 三分化 ⑨ 断连对账（subscribed 基线 → seq 缺口 → history 回填）。

## 3. 部署拓扑（安全让渡部署，§5 调研结论）

- **首选 USB adb reverse**（真机 127.0.0.1:3080 → 宿主 loopback，栅栏全按构造通过——P-1 源码侧已证；真机实证待 E2E）。
- LAN 直连：须 trustedHosts（P-3 回填精确键）+ 特权面缺口明示。

## 4. TDD 计划（先测后码）

| 组件 | 首批测试 |
|---|---|
| DshEnvelope | 编解码往返、错误闭集解析、Content-Type/Host 头契约 |
| DshApiError 映射 | 闭集错误码 → 领域错误表驱动用例 |
| DshHistoryFolder | **黄金样本**：真实 session.jsonl fixture（探针 P-4 提供）→ 期望 SseEvent 序列快照断言；49 事件类型逐类覆盖 + 未知类型无 ignorable 拒绝重建用例 |
| DshWsEventMapper | WS 帧 → SseEvent 映射表驱动；未知事件类型容错（不崩、记日志） |
| ServerCapabilities | DSH 能力位矩阵快照 |
| ServerConfig 序列化 | 旧 JSON（无 serverType）→ 默认 OpenCode 往返 |

## 5. 探针回填区（P-4 已回填 2026-08-31，详版 /tmp/dsh-probes/P4-rpc-surface.md）

- [x] P-4：**52 方法**（session12/workspace7/agentPreset6/goal6/host5/settings5/subagent4/credentials3/llm3/skill1；13 纯读+4 特权读+35 mutation）+ 4 非信封入口（/api/respond→RpcReceipt、双 WS GET→426、session.export zip）。与主会话源码独立提取的 52 方法**逐一吻合**（三重交叉验证）。
- [x] P-4：**信封**——method=URL 路径段=body.method 三者必须相等（不等→200+bad-request）；业务结果恒 HTTP 200 `result.{ok,value|error}`，error.code 闭集 **39 值**；HTTP 状态只表搬运层（415 非 JSON/400 非 JSON body/404 未知方法/403 Host/426 需 WS/500 崩溃）；多余字段 zod 忽略；payload:null→bad-request。
- [x] P-4：**session.list**：`{items:[{sessionId, updatedAt(epoch-ms), running, blank, parentSessionId?, origin?, cwd?, agentPreset?, projections?{asOfSeq, values}}]}`；projections 实测 **13 key**（title/sessionStats/tokenUsage/contextPressure/contextBreakdown/goal/permissions/todos/plan/subagent/subagentTiming/sessionListMetadata/imageLimits），key 缺席=插件未挂载；**cursor 未实现**（传入仍返全量 366）。
- [x] P-4：**session.history**：raw 事件流（chunk 逐 token 在列，客户端 fold）；`{sessionId, beforeSeq?, maxMessages?}`；页边界消息对齐；HistoryEntry={event,view?}；**仅尾页带 projections**；maxMessages=2→499 events+hasMore。
- [x] P-4：**存储**：`~/.dsh/sessions/<cwd 消毒名>/<id>/session.jsonl.zstd`（zstd JSONL）；2,032,467 行 = 真事件 757,607 + 打包行 1,276,016 + 366 header（type:session）+ **0 坏行**；源码目录 **49 型**，本机实测 36 型全在目录内（13 未现=插件未挂载：team/*、tool-workflow/*、hook/*、feedback/record、plan/mode、schedule/change）。
- [x] P-4：**信封细节**——surface 三类（user/message、assistant/message、tool/result）独有 `sourceEventSeqs`+`surfaceOp`；仅 llm/failover 带 `ignorable:true`；**未知类型无 ignorable 必须拒绝重建**（fold 安全规则入 TDD）。
- [x] P-4：**版本探测**——host.describe.version="0.0.1" 硬编码字面量（不可用于锁定）；无版本协商（"client and host ship together"）→ 客户端做**能力探测**：方法存在性（404）+ describe 字段集 + projections key 集。
- [x] P-4：**坑位**——skill.list 需 attached 会话（冷会话→session-not-found）；session.models 遇 subagent-origin→agent-busy；session.search 部署索引可关（internal 错误）→ 客户端需本地降级路径；**时间戳单位双态**（workspace ISO 字符串 vs session epoch-ms）。
- [x] P-4：**会话开头惯例**：subagent/descriptor(seq0)→sandbox/mode→approval/policy→permission/preset→agent/inbox/spliced→turn/start。
- [x] 主会话补遗（2026-08-31 源码提取）：**39 错误码闭集全清单**（RpcErrorDetailsMap keys，rpc.d.ts:26-175）——bad-request/cancelled/session-not-found/model-unavailable/session-conflict/invalid-time-zone/workspace-attach-failed|not-found|invalid-path|name-conflict|move-invalid/directory-unreadable|exists|create-failed|picker-unavailable/agent-preset-read-only|locked|conflict|not-found|invalid/agent-busy/attachment-error/queue-item-not-found/steer-unavailable/command-error/unknown-command/settings-rejected|conflict/credential-rejected/model-discovery-failed/title-invalid/fork-unavailable/subagent-parent-unavailable|not-found|catalog-diagnostic|not-resumable|unauthorized|delivery-unavailable/internal。
## 6. E2E 计划（真机 e69a99d8）

adb reverse tcp:3080 tcp:3080 → 应用内添加 DSH 服务器（127.0.0.1:3080）→ 会话列表加载（fold）→ 发送消息（session.prompt，微小提示词）→ 流式渲染（WS delta）→ 中断（session.cancel）→ 断连重连对账。全程 logcat + UI dump 取证；截图识图委派 subagent（glm-5.3-flash）。

## 7. 交付定义

对齐目标文本：单测全绿（含 DSH 新组件）+ i18n 检查通过 + 真机 E2E 全项通过 + journal 证据入册 + backlog 关单迁册。

## 8. 交付后记（2026-08-31）

- **六层交付收官**：调研→探针 P-1..P-4→本设计→TDD 四层实现→真机 E2E 六段→终验五项矩阵；终验结论一行：V4'/V6'/V7/V8 PASS，V5' PARTIAL（客户端链就绪，阻塞在部署面）+ 徽章滞留根因修复（015ed7de）+ 工作区选择器偏移根治（f3125d80），详证 journal §（八）。
- **接口权威文档**：`docs/api/dsh-openapi.yaml`（双源交叉验证，56 path）——后续接口问题以它为准。
- **两处部署面差异**：①compact 命令按部署注册（本部署 command.list 404 无 compact → /compact 为纯文本；装 dsh-command-compact 插件即有效）；②listDirectory 活体实证永不返文件条目（demote 分支防御性保留）。
- **遗留增强位**：backlog #278（僵尸 Busy L3 自愈真相源）+ #279（导出 SAF intent MIME）；ChatScreen 内 PTY 入口管线已门控。
