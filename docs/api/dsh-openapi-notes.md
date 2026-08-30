# DSH OpenAPI 规范伴生说明（dsh-openapi.yaml）

- 规范版本：DSH（DeepSeek Harness）**0.1.1-rc.2**（CLI `dsh --version` 实测；npm 包 `@deepseek-ai/dsh@0.1.1-rc.2`）
- 主文档：[`docs/api/dsh-openapi.yaml`](dsh-openapi.yaml)（OpenAPI 3.0.3，8100+ 行，215 个 schema，56 个 path）
- 实测实例：http://127.0.0.1:3080（2026-08-31，即承载本会话的 GUI 实例；**探测全程遵守红线**——未触碰当前会话，未调用任何破坏性/特权 mutation）
- 证据目录：`/tmp/dsh-openapi-cases/`（260+ 文件；每个 case = 命令 + 响应头 + 响应体三件套；索引见 `INDEX.md`；WS 探针 `ws-probe.cjs` + `ws-probe-output.txt`）

---

## 1. 建模决策（RPC-over-POST 如何映射 OpenAPI）

DSH 的 HTTP 面不是 REST 也不是 JSON-RPC 2.0，而是**四象限信封 over POST**。映射策略：

| DSH 事实 | OpenAPI 建模 |
|---|---|
| 52 个方法 = `POST /api/<method>`，路径段 = body.method（二者必须相等） | 每方法一个 path（`/api/session.list` 等），requestBody schema = `allOf: [ClientRequest, {method: enum[单个值], payload: $ref <method>.Request}]`——信封通用形状与该方法专属 payload 叠加 |
| 业务错误恒 HTTP 200（`result.ok=false + error`） | 每操作 `200` 的 schema = `allOf: [ServerResponse, {result: oneOf[ok分支(value=$ref)/err分支(error=$ref RpcError)}]`；**不使用 4xx/5xx 表达业务错误** |
| 404/415/400/403/413/500 属搬运层 | `components.responses` 六个共享响应（TransportBadRequest/NotFound/UnsupportedMediaType/Forbidden/PayloadTooLarge/Internal），每个方法引用 |
| /api/respond 收 client-response 信封、回 RpcReceipt（非 RpcMessage） | 独立 path，200 直接返回 RpcReceipt schema |
| /api/session.export GET/HEAD 出 zip（无信封） | 独立 path + GET/HEAD 两个 operation，query 参数（sessionId 必填、includeDescendants 只收 "true"/"false"），200 = binary zip，400/404/500/501 |
| /api/events.mux、events.host：GET → 426，实际是 WS 下行 | GET operation 只声明 426/403；挂 `x-websocket` 扩展（upgrade/方向/握手/心跳/重连/frames $ref/应答通道），帧 schema（`MuxStreamFrame`/`HostStreamFrame` = ServerRequest 信封 + method 枚举 + payload 帧联合）放 components |
| 15 个特权方法（loopback 钉死） | 操作级 `x-dsh-privileged: true`；读写用 `x-dsh-kind: read|mutation`；可中断（请求带 AbortSignal）用 `x-dsh-cancellable: true` |
| 必填/可选、min/max、enum 从 zod schema 忠实转录 | 每个方法一对 `<method>.Request`/`<method>.Value` schema；refine 约束（session.create 至多一个 workspaceId/cwd、goal.edit 需 objective 或 maxGoalRounds 等）写入 description |
| 出处可追溯 | **每个 schema 的 description 注明源码 文件:行**（`dsh-host-apiproxy/lib/types/api/*.d.ts` + `*.schema.js` 为权威；操作 description 另注实测证据文件名） |

WS 建模细节：OpenAPI 3.0.3 无 WS 原生支持，采用 `x-websocket` 自定义扩展承载协议语义（升级要求、纯下行、1008 违规关闭、无心跳、无 since 补漏、应答走 /api/respond），帧联合本体（MuxFrame 10 变体 / HostFrame 10 变体）作为正式 schema 放 components，供代码生成器/校验器复用。

## 2. 39 错误码闭集（code → details 形态）

闭集来源：`RpcErrorDetailsMap`（rpc.d.ts:26-174；rpc.schema.js:16-60 zod discriminatedUnion）。**details 恒为对象（必填）**；下表 ✔ = 实测触发过：

| code | details 字段 | 含义 | 实测 |
|---|---|---|---|
| bad-request | issues: ZodIssue[] | 信封/payload 校验失败（携带 zod issues） | ✔ E01/E06/E14/E15/E17 |
| cancelled | {} | 搬运层取消折叠 | ✗ |
| session-not-found | sessionId | 会话不存在；attached 面（skill.list）对冷会话带 "(not attached)" | ✔ E02/M10 |
| model-unavailable | provider, model | 路由不可用 | ✗ |
| session-conflict | sessionId, requestedCwd, existingCwd? | 预分配 sessionId 换 cwd | ✗ |
| invalid-time-zone | value | clientTimeZone 非法 | ✗ |
| workspace-attach-failed | sessionId, workspaceId | 发布后挂接失败 | ✗ |
| workspace-not-found | workspaceId | 未知 workspace | ✗ |
| workspace-invalid-path | path | create 路径不存在/非目录 | ✗ |
| workspace-name-conflict | name | 重命名撞名 | ✗ |
| workspace-move-invalid | workspaceId, sessionId, beforeSessionId? | 会话/锚点不被记账（cwd 创建不入 workspace） | ✔ W4/W4b |
| directory-unreadable | path | listDirectory 目标不可读 | ✗ |
| directory-exists | path | createDirectory 已存在 | ✗ |
| directory-create-failed | path | 其他建目录失败 | ✗ |
| directory-picker-unavailable | capability | 无 native 能力 | ✗ |
| agent-preset-read-only | agentPreset, reason | 对 shipped preset 做授权写 | ✗ |
| agent-preset-locked | sessionId, agentPreset | 非空白会话换 preset | ✔ AP2 |
| agent-preset-conflict | sessionId, requestedPreset, existingPreset? | 会话已绑他 preset | ✗ |
| agent-preset-not-found | agentPreset, available: string[] | 未知 preset（附全 roster） | ✔ AP3 |
| agent-preset-invalid | agentPreset, reason | 组合挂载失败 | ✗ |
| agent-busy | reason | 普通会话面用在 subagent 会话上 | ✔ 11 |
| attachment-error | reason | 附件查找失败（ATTACHMENT_NOT_REFERENCED） | ✔ M09 |
| queue-item-not-found | itemId | 队列项不再 pending | ✔ M07 |
| steer-unavailable | itemId | 不可严格 steer | ✗ |
| command-error | {} | 斜杠命令用法/状态错 | ✗ |
| unknown-command | {} | 未注册命令名 | ✗ |
| settings-rejected | ns | 设置写被拒（schema/只读/存储） | ✗ |
| settings-conflict | ns, expected, actual | expectedRevision 过期 | ✗ |
| credential-rejected | ref | 凭据写被拒（只读影子层） | ✗ |
| model-discovery-failed | settingsNs, baseURL? | 端点问询失败（细节永不回显 key） | ✔ E18 |
| title-invalid | sessionId | 标题归一化为空 | ✗ |
| fork-unavailable | sessionId | 锚点落在未完结 turn | ✗ |
| subagent-parent-unavailable | parentSessionId | 无活父可投递 | ✗ |
| subagent-not-found | parentSessionId, childSessionId | 非直接子/模式不符 | ✔ SA3 |
| subagent-catalog-diagnostic | parentSessionId, childSessionId, reason(corrupt/unsupported/unavailable) | 目录行为诊断行 | ✗ |
| subagent-not-resumable | childSessionId | 子不可续 | ✗ |
| subagent-unauthorized | childSessionId | 无权操作 | ✗ |
| subagent-delivery-unavailable | childSessionId | 续接 owner 不可用 | ✗ |
| internal | {} | 兜底。**实测两个 GoalError 场景**：CAS 旧 ref（G3-G6 stale）与 exhausted rounds resume（G4b） | ✔ G3/G4b |

搬运层 HTTP 状态（不是 RpcError）：404 未知路径（E03/E08）、415 非 JSON Content-Type（E04）、400 body 非 JSON（E07 / E13a）、403 栅栏（E05/E09/E20/E21/E22）、426 双事件流 GET（E12a/b）、413 超 300MiB、500 handler 崩溃。

## 3. 栅栏矩阵（实测）

四道栅栏（源码 `dsh-client-connection/lib/index.js`）：

1. **bind**：部署配置（本机 0.0.0.0 + 派生 LAN 信任）
2. **Host 栅栏**（184-198, 553-560）：Host ∈ loopback（localhost / [::1] / 127/8，端口不敏感）∪ trustedHosts ∪ 派生 LAN IP；Sec-Fetch-Site: cross-site 拒（✔ E21）；有 Origin 时必须同 host:port（✔ E22 拒 / E23 过）
3. **特权面钉 loopback**（504-520, 538）：15 方法空信任表二次检查——LAN Host + host.describe = 200（✔ E19），LAN Host + settings.describe = 403（✔ E20），TEST-NET Host + settings.describe = 403（✔ E09）
4. **WS 升级栅栏**（566-577）：坏 Host → 裸 403 "forbidden"（✔ WS4）

明确「非鉴权」：不看 socket 来源地址、不看 XFF，任何能写 `Host: 127.0.0.1:3080` 的调用方全通。

## 4. 实测 case 矩阵（概要）

完整矩阵与逐 case 命令/响应见 `/tmp/dsh-openapi-cases/INDEX.md`。

- **成功 case 41**：只读 15（session.list/history×2、host.describe/listDirectory、workspace.list、llm.providers/models、agentPreset.list/read、settings.describe、credentials.describe、skill.list、subagent.list/history）+ mutation 26（scratch 会话上 session.create×3（cwd/workspaceId/二次）、models、selectModel、rename、prompt（唯一 1 条提示词，预算内）、cancel、fork、goal 全 6 动词、workspace 全 7 动词（含幂等 create）、host.createDirectory、agentPreset.select、subagent.interrupt、export GET+HEAD）
- **错误/边界 case 30**：E01-E23 搬运层+栅栏 22 个、M07/M09/M10/SA3 业务错误 4 个、goal CAS/exhausted 2 个、agent-busy（11）与 search-internal（15）2 个
- **WS case 4**：host 流空闲零帧、mux 流 11 条 subscribed 基线+实时帧、下行违规 close 1008 "downlink only"、坏 Host 升级 403
- **跳过 14**（破坏性/特权/外呼，从源码文档化）：settings.update/replace/mutate/openDocument、credentials.set/unset、host.openPath/pickDirectory、agentPreset.copy/openDocument/remove、subagent.prompt（会向他人活会话投递消息）、llm.discoverModels 成功路径（会外呼端点；错误路径已测）、session.attachment 成功路径（无种子附件）、session.search 成功路径（本部署索引关闭）

### 本次新发现（相对既有探针 P4/P2P3）

1. **goal CAS 失败无专用错误码**：旧 ref → `internal "GoalError: stale goal ref … current is …"`；轮次耗尽后 resume → `internal "exhausted N goal rounds"`。客户端只能靠 message 前缀区分。
2. **session.create 用 cwd 不会自动记账进同路径 workspace**：insertSessionBefore 答 `workspace-move-invalid "not accounted"`；只有显式 `workspaceId` 才挂接。
3. **session.export 的 includeDescendants 把 fork 子会话也算后代**：zip 内 `subagents/<fork子>/session.jsonl`（fork 继承 parentSessionId 所致）。
4. **mux 订阅基线数（11）≠ host.describe.attachedSessions（7）**：subscribed 按 sessions 注册表全量发，attachedSessions 按 agents.list() 计——两个口径不同。
5. **信封坏但 rpcId 可读时回显原 rpcId**（E15），只有不可读才用哨兵 `invalid-request`（与 handler.js:229-235 注释一致）。
6. settings.describe revision 恒 0（未写过 user 层的 namespace）——revision 是 user section 的版本，不是全局。

### 已知坑位（与既有探针一致，已写入操作 description）

- host.describe.version = **"0.0.1" 硬编码**（apiproxy lib/index.js:3110），不可用于版本锁定
- skill.list 需 attached 会话；session.models/selectModel/rename/prompt/updateQueue/cancel/goal.* 遇 subagent-origin → agent-busy
- session.search 部署可关（本机 internal）；session.history 为 raw chunk 级事件流、仅尾页带 projections
- 时间戳双态：session 域 epoch-ms / workspace 域 ISO-8601 字符串
- WS 无心跳无补漏；重连 = 重开 + 全量重基线；官方客户端退避 500ms×2ⁿ 封顶 10s

## 5. 信源清单

**主源（权威，逐字段转录）**
- `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/types/api/*.d.ts` + 同名 `*.schema.js`（rpc/rpc-map/sessions/subagents/host/workspace/skills/agent-presets/goals/settings/credentials/llm/events/downloads/jobs/questions/approvals/session-search）
- `…/dsh-host-apiproxy/lib/types/fetch/handler.js`（POST/GET/HEAD 路由、两级解析、404/415/400/500、SSE 变体）
- `…/dsh-client-connection/lib/index.js`（栅栏 184-198、特权集 504-520、426 拦截 539-545、桥接 38-87（413）、WS 下行 334-465、升级栅栏 566-585、300MiB 默认 29）
- `…/dsh-session/lib/types/types.d.ts`（SessionEventMap/SessionHeader）+ `known-event-types.js:18-68`（49 事件型目录）
- `…/dsh-host-apiproxy/lib/index.js`（version 字面量 3110、sessionLog 导出实现 3269-3306 → 404/500/501、respond 路由 3309+）

**活实例（实测）**：http://127.0.0.1:3080（curl --noproxy '*'；信封见规范；证据 /tmp/dsh-openapi-cases/）

**交叉参考（既有探针）**：/tmp/dsh-probes/P4-rpc-surface.md（52 方法清单三重吻合）、P2P3-ws-fences.md（WS 握手矩阵/keepalive/重连语义）、event-census-result.txt（事件普查）；docs/specs/2026-08-31-dsh-integration-design.md §5（39 错误码闭集回填）

## 6. 验收自检

**52 方法全覆盖**（✔=有成功实测，△=仅错误实测，○=未实测仅源码文档化）：

session.list✔ · session.search△ · session.create✔ · session.history✔ · session.models✔ · session.selectModel✔ · session.rename✔ · session.fork✔ · session.prompt✔ · session.attachment△ · session.updateQueue△ · session.cancel✔ · subagent.list✔ · subagent.history✔ · subagent.prompt○ · subagent.interrupt✔ · host.describe✔ · host.pickDirectory○ · host.listDirectory✔ · host.createDirectory✔ · host.openPath○ · workspace.list✔ · workspace.create✔ · workspace.rename✔ · workspace.delete✔ · workspace.insertBefore✔ · workspace.insertSessionBefore✔ · workspace.archiveSession✔ · skill.list✔ · agentPreset.list✔ · agentPreset.select✔ · agentPreset.read✔ · agentPreset.copy○ · agentPreset.openDocument○ · agentPreset.remove○ · goal.create✔ · goal.edit✔ · goal.pause✔ · goal.resume✔ · goal.complete✔ · goal.clear✔ · settings.describe✔ · settings.openDocument○ · settings.update○ · settings.replace○ · settings.mutate○ · credentials.describe✔ · credentials.set○ · credentials.unset○ · llm.providers✔ · llm.models✔ · llm.discoverModels△

计数：✔ 37 · △ 3（attachment/search/discoverModels，错误路径实证）· ○ 12（全部为破坏性/特权 mutation 或向他人会话投递，源码+schema 忠实文档化并标注「NOT TESTED」）。域分布核对：session 12 / subagent 4 / host 5 / workspace 7 / skill 1 / agentPreset 6 / goal 6 / settings 5 / credentials 3 / llm 3 = **52** ✓（与 rpc-map.d.ts 与 fetch/handler.js UNARY_ROUTES 双重核对一致）

**4 非信封入口覆盖**：/api/respond✔（E11 not-pending + E11b bad-response）· /api/session.export✔（GET 64KB zip 含后代 + HEAD，E13a 400 / E13b 404）· /api/events.mux✔（E12a 426 + WS1-4）· /api/events.host✔（E12b 426 + WS）✓

**39 错误码枚举完整**：规范 `components.schemas.RpcError` oneOf 39 分支（bad-request…internal），每分支 details 字段/必填从 rpc.schema.js 转录；生成后程序化核对 codes=39 ✓

**WS 两流帧 schema 完整**：MuxFrame 10 变体（session/event、session/subscribed、approval/requested、approval/resolved、question/requested、question/resolved、session/queue、session/jobs、session/projection、stream/error）+ HostFrame 10 变体（host/session-added、session-removed、session-status、agent-error、workspace-changed、workspace-removed、workspace-order-changed、archived-sessions-changed、remote-event、stream/error）；均以 ServerRequest 信封包裹（method=帧 type），经 x-websocket.frames $ref 挂接 ✓

**实测 case 统计**：成功 41（含 WS 2 成功流）· 错误/边界 30 · WS 边界 2（1008 违规、403 升级）· 跳过 14（原因见 §4；提示词预算 1/2 条）

**规范质量**：js-yaml 往返解析通过；219 个 $ref 全部可解析（0 未解析）；57 个 operation 全部具 operationId 与 responses；特权方法 15 个与 PRIVILEGED_METHODS 逐一对应 ✓
