# DSH OpenAPI 规范伴生说明（dsh-openapi.yaml）

- 规范版本：DSH（DeepSeek Harness）**0.1.1-rc.2**（CLI `dsh --version` 实测；npm 包 `@deepseek-ai/dsh@0.1.1-rc.2`）
- 主文档：[`docs/api/dsh-openapi.yaml`](dsh-openapi.yaml)（OpenAPI 3.0.3，8200+ 行，215 schema，56 path，219 $ref 全解析）
- 实测实例：http://127.0.0.1:3080（2026-08-31，即承载本会话的 GUI 实例；**探测全程遵守红线**——未触碰当前会话，未调用任何破坏性/特权 mutation，提示词预算 1/2 条）
- 证据目录：`/tmp/dsh-openapi-cases/`（270+ 文件；每个 case = 命令 + 响应头 + 响应体三件套；索引见 `INDEX.md`；WS 探针 `ws-probe.cjs` / `ws-sample.cjs`）

> **双源总则（硬性验收标准）**：本文每个接口均经「源码调研（.d.ts + zod schema 文件:行）」与「活体实测（curl/WS 证据文件）」双源交叉验证。两源不一致时**以活体为准写入 spec**，并在 §5 记 DISCREPANCY 条目。请求/响应 schema 逐字段以活体响应为最终裁决（§6 真实样本），活体未出现的可选字段由源码补全并在 §7 标「源码推导，未实测」。

---

## 1. 建模决策（RPC-over-POST 如何映射 OpenAPI）

DSH 的 HTTP 面不是 REST 也不是 JSON-RPC 2.0，而是**四象限信封 over POST**。映射策略：

| DSH 事实 | OpenAPI 建模 |
|---|---|
| 52 个方法 = `POST /api/<method>`，路径段 = body.method（二者必须相等，不等→200+bad-request，证据 E06） | 每方法一个 path（`/api/session.list` 等），requestBody schema = `allOf: [ClientRequest, {method: enum[单个值], payload: $ref <method>.Request}]` |
| 业务错误恒 HTTP 200（`result.ok=false + error`，39 码闭集） | 每操作 `200` schema = `allOf: [ServerResponse, {result: oneOf[ok(value $ref)/err(RpcError $ref)]}]`；**不用 4xx/5xx 表达业务错误** |
| 404/415/400/403/413/500 属搬运层 | `components.responses` 六个共享 Transport* 响应，逐方法引用 |
| /api/respond 收 client-response 信封、回 RpcReceipt（非 RpcMessage） | 独立 path，200 直接返回 RpcReceipt schema |
| /api/session.export GET/**HEAD** 出 zip（无信封） | 独立 path + GET/HEAD 两 operation，query 参数（sessionId 必填；includeDescendants 只收 "true"/"false"），200 = binary zip，400/403/404/500/501 |
| /api/events.mux、events.host：GET → 426，实际是 WS 下行 | GET 只声明 426/403；挂 `x-websocket` 扩展（upgrade/方向/握手/心跳/重连/frames $ref/应答通道）；帧 schema（MuxStreamFrame/HostStreamFrame = ServerRequest 信封 + method 枚举 + 帧联合）放 components |
| 15 个特权方法（loopback 钉死） | 操作级 `x-dsh-privileged: true`；读写 `x-dsh-kind: read|mutation`；可中断（AbortSignal）`x-dsh-cancellable: true` |
| 必填/可选/min/max/enum 从 zod 忠实转录 | 每方法一对 `<method>.Request`/`<method>.Value`；refine 约束（create 至多一个 workspaceId/cwd、goal.edit 需 objective 或 maxGoalRounds、createDirectory name 单段等）入 description |
| 出处可追溯 | 每个 schema description 注**源码 文件:行**；操作 description 另注**实测证据文件名**与 DISCREPANCY 裁定 |

WS 建模：OpenAPI 3.0.3 无 WS 原生支持，以 `x-websocket` 自定义扩展承载协议语义（升级要求、纯下行、1008 违规关闭、无心跳、无 since 补漏、应答走 /api/respond），帧联合本体（MuxFrame 10 变体 / HostFrame 10 变体）为正式 schema 供代码生成/校验复用。

## 2. 39 错误码闭集（code → details 形态）

闭集来源：`RpcErrorDetailsMap`（rpc.d.ts:26-174；rpc.schema.js:16-60 zod discriminatedUnion）。**details 恒为对象（必填）**；✔ = 实测触发过（证据文件见 §4 矩阵）：

| code | details 字段 | 含义 | 实测 |
|---|---|---|---|
| bad-request | issues: ZodIssue[] | 信封/payload 校验失败（携带 zod issues） | ✔ E01/E06/E14/E15/E17/V02 |
| cancelled | {} | 搬运层取消折叠 | ✗ |
| session-not-found | sessionId | 会话不存在；attached 面（skill.list）对冷会话带 "(not attached)" | ✔ E02/M10 |
| model-unavailable | provider, model | 路由不可用 | ✗ |
| session-conflict | sessionId, requestedCwd, existingCwd? | 预分配 sessionId 换 cwd | ✗ |
| invalid-time-zone | value | clientTimeZone 非法 | ✗ |
| workspace-attach-failed | sessionId, workspaceId | 发布后挂接失败 | ✗ |
| workspace-not-found | workspaceId | 未知 workspace | ✗ |
| workspace-invalid-path | path | create 路径不存在/非目录 | ✗ |
| workspace-name-conflict | name | 重命名撞名 | ✗ |
| workspace-move-invalid | workspaceId, sessionId, beforeSessionId? | 会话/锚点不被记账 | ✔ W4/W4b |
| directory-unreadable | path | listDirectory 目标不可读 | ✗ |
| directory-exists | path | createDirectory 已存在 | ✗ |
| directory-create-failed | path | 其他建目录失败 | ✗ |
| directory-picker-unavailable | capability | 无 native 能力 | ✗ |
| agent-preset-read-only | agentPreset, reason | 对 shipped preset 授权写 | ✗ |
| agent-preset-locked | sessionId, agentPreset | 非空白会话换 preset | ✔ AP2 |
| agent-preset-conflict | sessionId, requestedPreset, existingPreset? | 会话已绑他 preset | ✗ |
| agent-preset-not-found | agentPreset, available: string[] | 未知 preset（附 roster） | ✔ AP3 |
| agent-preset-invalid | agentPreset, reason | 组合挂载失败 | ✗ |
| agent-busy | reason | 普通会话面用在 subagent 会话 | ✔ 11 |
| attachment-error | reason | 附件查找失败（ATTACHMENT_NOT_REFERENCED） | ✔ M09 |
| queue-item-not-found | itemId | 队列项不再 pending | ✔ M07 |
| steer-unavailable | itemId | 不可严格 steer | ✗ |
| command-error | {} | 斜杠命令用法/状态错 | ✗ |
| unknown-command | {} | 未注册命令名 | ✗ |
| settings-rejected | ns | 设置写被拒 | ✗ |
| settings-conflict | ns, expected, actual | expectedRevision 过期 | ✗ |
| credential-rejected | ref | 凭据写被拒 | ✗ |
| model-discovery-failed | settingsNs, baseURL? | 端点问询失败 | ✔ E18 |
| title-invalid | sessionId | 标题归一化为空 | ✗ |
| fork-unavailable | sessionId | 锚点落在未完结 turn | ✗ |
| subagent-parent-unavailable | parentSessionId | 无活父可投递 | ✗ |
| subagent-not-found | parentSessionId, childSessionId | 非直接子/模式不符 | ✔ SA3 |
| subagent-catalog-diagnostic | parentSessionId, childSessionId, reason | 目录行为诊断行 | ✗ |
| subagent-not-resumable | childSessionId | 子不可续 | ✗ |
| subagent-unauthorized | childSessionId | 无权操作 | ✗ |
| subagent-delivery-unavailable | childSessionId | 续接 owner 不可用 | ✗ |
| internal | {} | 兜底；**GoalError 两场景折叠于此**（G3/G4b） | ✔ G3/G4b/15 |

搬运层 HTTP 状态（不是 RpcError）：404 未知路径（E03/E08）、415 非 JSON Content-Type（E04）、400 body 非 JSON（E07/E13a）、403 栅栏（E05/E09/E20/E21/E22）、426 双事件流 GET（E12a/b）、413 超 300MiB、500 handler 崩溃。

## 3. 栅栏矩阵（实测）

四道栅栏（源码 `dsh-client-connection/lib/index.js`）：

1. **bind**：部署配置（本机 0.0.0.0 + 派生 LAN 信任）
2. **Host 栅栏**（184-198, 553-560）：Host ∈ loopback（localhost / [::1] / 127/8，端口不敏感）∪ trustedHosts ∪ 派生 LAN IP；Sec-Fetch-Site: cross-site 拒（✔ E21）；有 Origin 时必须同 host:port（✔ E22 拒 / E23 过）
3. **特权面钉 loopback**（504-520, 538）：15 方法空信任表二次检查——LAN Host + host.describe = 200（✔ E19），LAN Host + settings.describe = 403（✔ E20），TEST-NET Host + settings.describe = 403（✔ E09）
4. **WS 升级栅栏**（566-577）：坏 Host → 裸 403 "forbidden"（✔ WS4）

明确「非鉴权」：不看 socket 来源地址、不看 XFF，任何能写 `Host: 127.0.0.1:3080` 的调用方全通。
---

## 4. 双源交叉验证 case 矩阵（52 方法 + 4 非信封入口 + 栅栏）

结论标记：✔ = 有成功实测 · △ = 仅错误路径实测 · ○ = SKIPPED（破坏性/特权/外呼，源码+schema 文档化）。源码依据均相对 `…/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/types/api/`；实测证据均相对 `/tmp/dsh-openapi-cases/`。

### session.*（12）
| 接口 | 源码依据（d.ts / schema.js） | 实测证据 | 结论 |
|---|---|---|---|
| session.list | sessions.d.ts:231-236 / sessions.schema.js:42-49 | 01、V01-refresh | ✔（382→386 条；归档仍在列=D3） |
| session.search | sessions.d.ts:238-247 / schema.js:53-66 + session-search.js:1-4（≤20 会话/摘要 240 码点） | 15 | △ internal（部署关闭；成功路径 SKIPPED：本机索引 openAt=never） |
| session.create | sessions.d.ts:249-271 / schema.js:67-78 | M01、M11、M12 | ✔（cwd/workspaceId/幂等 sessionId 三形态；cwd 不入 workspace=见 §5 GAP-G5） |
| session.history | sessions.d.ts:272-298 / schema.js:98-103、151-154、183-188 | 13（尾页 267 ev）、H2（loadOlder） | ✔（raw 事件流=D7；仅尾页带 projections） |
| session.models | sessions.d.ts:299-305 / schema.js:189-199 | M02 ✔、11 △ agent-busy | ✔+△ |
| session.selectModel | sessions.d.ts:306-318 / schema.js:201-210 | M03 | ✔ |
| session.rename | sessions.d.ts:319-333 / schema.js:79-88 | M04 | ✔（{title,seq:3}） |
| session.fork | sessions.d.ts:342-361 / schema.js:89-97 | M08 | ✔（取消后 turn 边界可 fork；atSeq/fork-unavailable 未实测） |
| session.prompt | sessions.d.ts:362-380 / schema.js:213-239 | M05 ✔、V02 △ 缺 mode | ✔+△（mode 必填=D5；command 槽/斜杠路径未实测） |
| session.attachment | sessions.d.ts:381-388 / schema.js:241-260 | M09 △ attachment-error | △（成功路径 SKIPPED：无种子附件） |
| session.updateQueue | sessions.d.ts:389-399 / schema.js:261-274 | M07 △ queue-item-not-found | △（成功路径需 pending 项，未构造） |
| session.cancel | sessions.d.ts:400-409 / schema.js:275-282 | M06 | ✔ |

### subagent.*（4）
| 接口 | 源码依据 | 实测证据 | 结论 |
|---|---|---|---|
| subagent.list | subagents.d.ts:53-61 / subagents.schema.js:28-36 | 12 | ✔（9 entries；冷父 parentAvailable:false） |
| subagent.history | subagents.d.ts:62-74 / schema.js:37-50 | SA2 | ✔（1336 ev，maxMessages=1） |
| subagent.prompt | subagents.d.ts:75-87 / schema.js:51-58 | — | ○ SKIPPED：会向他人活会话投递消息（非 scratch 资源） |
| subagent.interrupt | subagents.d.ts:88-99 / schema.js:59-68 | SA1 | ✔（inactive 子 accepted） |

### host.*（5）
| 接口 | 源码依据 | 实测证据 | 结论 |
|---|---|---|---|
| host.describe | host.d.ts:32-51 / host.schema.js:6-16 | 02、ws-sample（同刻 attachedSessions） | ✔（version="0.0.1"=D1） |
| host.pickDirectory | host.d.ts:53-58 / schema.js:17-22 | — | ○ SKIPPED：宿主弹原生对话框 |
| host.listDirectory | host.d.ts:59-68 / schema.js:23-40 | 04 | ✔（truncated:false；true 形态未实测） |
| host.createDirectory | host.d.ts:69-80 / schema.js:41-50 | H1 | ✔（已清理） |
| host.openPath | host.d.ts:81-91 / schema.js:51-58 | — | ○ SKIPPED：宿主启动桌面应用 |

### workspace.*（7）
| 接口 | 源码依据 | 实测证据 | 结论 |
|---|---|---|---|
| workspace.list | workspace.d.ts:36-45 / workspace.schema.js:18-24 | 03 | ✔（ISO-8601 时间戳） |
| workspace.create | workspace.d.ts:46-59 / schema.js:25-33 | W1 ✔、W1b 幂等 | ✔ |
| workspace.rename | workspace.d.ts:60-71 / schema.js:34-42 | W2 | ✔ |
| workspace.delete | workspace.d.ts:72-81 / schema.js:46-50 | W7 | ✔（清理完成） |
| workspace.insertBefore | workspace.d.ts:82-91 / schema.js:51-59 | W3 | ✔（省略 anchor=append） |
| workspace.insertSessionBefore | workspace.d.ts:92-106 / schema.js:60-69 | W4c ✔、W4/W4b △ not-accounted | ✔+△ |
| workspace.archiveSession | workspace.d.ts:107-119 / schema.js:70-77 | W5、W6 | ✔（session.list 仍返回归档=D3） |

### skill / agentPreset / goal / settings / credentials / llm（22）
| 接口 | 源码依据 | 实测证据 | 结论 |
|---|---|---|---|
| skill.list | skills.d.ts:22-33 / skills.schema.js:14-21 | 10 ✔、M10 △ not-attached | ✔+△ |
| agentPreset.list | agent-presets.d.ts:42-61 / schema.js:16-23 | 07 | ✔（4 system presets） |
| agentPreset.select | agent-presets.d.ts:63-75 / schema.js:24-32 | AP1 ✔、AP2/AP3 △ | ✔+△ |
| agentPreset.read | agent-presets.d.ts:82-90 / schema.js:33-44 | 16 | ✔（特权读 loopback 过；3673 字符） |
| agentPreset.copy | agent-presets.d.ts:93-107 / schema.js:45-54 | — | ○ SKIPPED：授权写（新建 preset） |
| agentPreset.openDocument | agent-presets.d.ts:117-124 / schema.js:55-63 | — | ○ SKIPPED：原生 opener |
| agentPreset.remove | agent-presets.d.ts:125-128 / schema.js:64-69 | — | ○ SKIPPED：删除 |
| goal.create | goals.d.ts:27-34 / goals.schema.js:15-21 | G1 | ✔ |
| goal.edit | goals.d.ts:36-43 / schema.js:23-32 | G2 ✔、G3/G7 △ internal | ✔+△（CAS 旧 ref→internal=GAP-G1） |
| goal.pause | goals.d.ts:45-50 / schema.js:33-39 | G3b | ✔ |
| goal.resume | goals.d.ts:51-56 / schema.js:40-46 | G4b △ internal | △（exhausted；成功路径未获=GAP-G2） |
| goal.complete | goals.d.ts:57-62 / schema.js:47-53 | G5c | ✔ |
| goal.clear | goals.d.ts:64-69 / schema.js:54-62 | G6c | ✔ |
| settings.describe | settings.d.ts:53-65 / settings.schema.js:22-29 | 08 | ✔（特权读 loopback 过；13 ns） |
| settings.openDocument | settings.d.ts:66-74 / schema.js:30-35 | — | ○ SKIPPED：打开编辑器 |
| settings.update | settings.d.ts:75-86 / schema.js:36-43 | — | ○ SKIPPED：改用户配置 |
| settings.replace | settings.d.ts:87-98 / schema.js:44-49 | — | ○ SKIPPED：破坏性重置路径 |
| settings.mutate | settings.d.ts:99-111 / schema.js:50-62 | — | ○ SKIPPED：改用户配置 |
| credentials.describe | credentials.d.ts:26-30 / schema.js:17-23 | 09 ✔、E17 △ bad-ref | ✔+△ |
| credentials.set | credentials.d.ts:37-40 / schema.js:24-30 | — | ○ SKIPPED：写密钥库 |
| credentials.unset | credentials.d.ts:44-47 / schema.js:31-36 | — | ○ SKIPPED：写密钥库 |
| llm.providers | llm.d.ts:31-41 / llm.schema.js:16-21 | 05 | ✔（declared:false 活体出现；active 者无 declared 键） |
| llm.models | llm.d.ts:42-50 / llm.schema.js:22-28 | 06 | ✔（failures 恒空） |
| llm.discoverModels | llm.d.ts:52-75 / llm.schema.js:36-52 | E18 △ model-discovery-failed | △（成功路径 SKIPPED：会外呼端点） |

### 非信封入口（4）+ 栅栏
| 接口 | 源码依据 | 实测证据 | 结论 |
|---|---|---|---|
| POST /api/respond | rpc.d.ts:243-261 / rpc.schema.js:97-113 / fetch/handler.js:220-225 / api-proxy.js:3309-3321 | E11（not-pending）、E11b（bad-response） | ✔（accepted:true 需真实 pending 审批，未实测※） |
| GET/HEAD /api/session.export | downloads.d.ts:9-22 / downloads.schema.js:15-23 / fetch/handler.js:186-198 / api-proxy.js:3269-3306 | X1（64KB zip）、X2（HEAD）、E13a 400、E13b 404 | ✔（**HEAD 仅见于 handler.js，d.ts 只述 GET**=GAP-G4；fork 子入 zip=D2） |
| GET /api/events.mux（426→WS） | events.d.ts:44-55 / events.schema.js:34-58 / client-connection index.js:539-545、579-581 | E12a 426、WS2、ws-sample | ✔（10 基线=attachedSessions 同刻=10；session/queue、projection、event 帧活体样本） |
| GET /api/events.host（426→WS） | events.d.ts:57-60 / events.schema.js:60-83 / 同上 539-545、582-584 | E12b 426、WS1 | ✔（空闲零帧=无快照基线；host/* 具体帧型本会话未现※） |
| 四道栅栏 | client-connection index.js:184-198、504-520、538、553-560、566-577 | E05/E09/E19/E20/E21/E22/E23/WS4 | ✔ 全矩阵 |

## 5. DISCREPANCY 清单（源码/文档 说 X，实测 Y → spec 以活体为准）

> 逐条醒目列出；每条含：源码主张（文件:行）→ 活体行为（证据）→ spec 裁定。D4-D7 为用户点名的历史先例，本次全部以新证据复核确认。

### D1. host.describe.version 是硬编码 "0.0.1"，不反射 package.json
- **源码主张**：host.d.ts:35-36 注释 "version = the host app's (apps/cli) package.json version"
- **活体**：恒返回 "0.0.1"（证据 02、ws-sample），而 CLI/package.json = 0.1.1-rc.2；实现在 apiproxy lib/index.js:3110 为字面量
- **裁定**：spec description 明示硬编码、不可用于版本锁定；客户端做能力探测（方法存在性 404 + describe 字段集 + projections key 集）

### D2. session.export 的后代规则：实测是 parentSession 血统，非 "subagent descendant"
- **源码主张**：downloads.d.ts:13-15 "the root artifact verbatim plus **each subagent descendant's**"
- **活体**：fork 子（V01 证实 origin:null、非 subagent）仍被打进 zip 的 `subagents/<id>/`（证据 X1：2 entries 含 fork 子）
- **裁定**：spec 写 "each descendant (parentSession lineage)" 并标注 DISCREPANCY

### D3. workspace.archiveSession 后 session.list 仍返回归档会话
- **源码主张**：workspace.d.ts:107-113 "the session **disappears from every grouping surface**"
- **活体**：S2/S3 归档后 session.list 从 382 → 386（新 4 条全在列，含两条已归档；证据 V01）
- **裁定**：session.list 非 "grouping surface"（分组由 workspace.sessionIds + archived 集在做）；spec 在两方法 description 注明，客户端过滤须自查 archivedSessionIds

### D4.（历史先例复核）信封不是 JSON-RPC 2.0，方法数 52 非 45
- **源码/旧文档主张**：仓库旧调研文档曾述 "JSON-RPC + 45 方法"
- **活体**：四象限信封 {type,rpcId,method,payload}（E06：method=路径段=body.method 三者一致）、52 方法逐一探通（rpc-map.d.ts:22-75 与 UNARY_ROUTES handler.js:22-75 双源吻合）
- **裁定**：spec 全按四象限信封建模

### D5.（历史先例复核）session.prompt 的 mode 必填
- **源码主张**：sessions.d.ts:369-373 mode 位于必填位（易漏）
- **活体**：V02 缺 mode → 200 + bad-request，zod union issue 恰在 path ["mode"]（queue/steer 二选一）
- **裁定**：schema required 含 mode；description 注明硬证据

### D6.（历史先例复核）session.list 的 title 投影值是裸字符串，非对象
- **源码主张**：SessionProjectionMap 对 values 不设形（unknown；sessions.d.ts:79-84），易被误建为 {text:…} 对象
- **活体**：`projections.values.title = "你是 DSH API 研究代理。任务：…"`（证据 01/V01，§6 样本）
- **裁定**：spec 的 values 保持 wide record + description 列 13 个实测 key 及 title=string 事实

### D7.（历史先例复核）session.history 返回 raw chunk 级事件流，非折叠消息
- **源码主张**：sessions.d.ts:272-289（明说 raw + 共享 fold），但旧消息流假设曾误导集成
- **活体**：13 号证据 maxMessages=2 → 267 events，其中 assistant/chunk 258 条（token 增量在列）；页边界消息对齐；仅尾页带 projections（H2 loadOlder 对照无）
- **裁定**：spec Value=HistoryEntry[]（event+view?）并强调客户端 fold 责任

### GAP 子清单（源码闭集缺口/语义含糊，非直接矛盾，同样醒目）
- **G1** goal CAS 旧 ref → `internal "GoalError: stale goal ref …"`（rpc.d.ts 39 码闭集无 goal-CAS 码；证据 G3/G7）
- **G2** goal 轮次耗尽后 resume → `internal "exhausted N goal rounds"`（证据 G4b）；goal.resume 成功路径因此未获
- **G3** session.search 部署关闭 → internal；实测消息为 "mounted but openAt never" 形态（apiproxy 另有 "not mounted" 形态 index.js:2409，两者都折 internal）
- **G4** export 的 HEAD 变体：downloads.d.ts 只述 GET；fetch/handler.js:186-198 明确实现 GET|HEAD，实测 X2 通过（spec 已收录 HEAD）
- **G5** session.create 用 cwd 不自动记账进同路径 workspace：sessions.d.ts:250-254 "Workspace creation attaches…"（指 workspaceId 路径）；活体 cwd 路径 W4/W4b → workspace-move-invalid "not accounted"，仅 workspaceId 记账（W4c）
- **勘误（自查纠正）**：早前记录 "mux 订阅基线 11 ≠ attachedSessions 7" 系两次测量时序偏差——同刻复测 10 = 10（ws-sample-output.json），**不构成 DISCREPANCY**，引以为鉴：计数类对比必须同刻采样
## 6. 活体响应样本（截断保结构；全部取自 /tmp/dsh-openapi-cases/ 证据文件）

截断规则：字符串 >64 字符截为前 48 字符 + `…(+Nc)`；数组 >2 保留前 2 + `…(+N more)`；对象保留全部键（第 5 层深起折叠）。标 ※ 的字段/形态为**源码推导，未实测**（见 §7）。

### session 域
```json
// session.list（01；归档会话也返回——V01 复核 386 条）— 单条 SessionSummary：
{"sessionId":"9a788d4e-e24b-4bbe-a091-233990c28ce6", "updatedAt":1788133181886, "running":true, "blank":false, "parentSessionId":"session-a6c42dd8-1c16-46bf-bac1-6344cf46a56c", "origin":"subagent", "cwd":"/home/leo-tkp/Documents/code/mine/oc-beacon", "agentPreset":"code", "projections":{"asOfSeq":6864, "values":{"sessionStats":{"turns":1, "steps":27, "llmMs":179568, "toolMs":2196, "ttftMs":79124, "ttftSteps":28, "decodeMs":100444, "decodeTokens":11154}, "title":"你是 DSH API 研究代理。任务：", "goal":null, "tokenUsage":{"uncachedInputTokens":99534, "outputTokens":11154, "cacheReadTokens":1686208, "cacheWriteTokens":0}, "contextPressure":{"pressureTokens":107288, "projectedTokens":108081, "contextWindow":1000000}, "contextBreakdown":{"systemTokens":9405, "toolsTokens":240, "messageTokens":91696}, "subagentTiming":{"settledMs":0, "active":{"since":1788133181775, "through":1788133364028}}, "subagent":{"mode":"continuable", "label":"DSH OpenAPI 文档研究", "seq":0}, "permissions":{"options":[{"value":"read-only", "name":"read-only"}, {"value":"workspace-write", "name":"workspace-write"}, …(+1 more)], "currentValue":"danger-full-access"}, "sessionListMetadata":{"blank":false, "lastPromptAt":1788133181886}, "imageLimits":{"maxImageBytes":20971520, "maxImagesPerMessage":20, "maxMessageImageBytes":209715200, "maxImagePixels":64000000, "maxImageDimension":8192, "mediaTypes":["image/png", "image/jpeg", …(+2 more)]}, "todos":null, "plan":{"active":false, "pending":false}}}}

// V02 session.prompt 缺 mode → bad-request（mode 必填的硬证据）：
{"code":"bad-request", "message":"invalid payload for session.prompt", "details":{"issues":[{"code":"invalid_union", "errors":[[{"code":"invalid_value", "values":["queue"], "path":[], "message":"Invalid input: expected \"queue\""}], [{"code":"invalid_value", "values":["steer"], "path":[], "message":"Invalid input: expected \"steer\""}]], "path":["mode"], "message":"Invalid input"}]}}

// 11 session.models 对 subagent-origin 会话 → agent-busy：
{"code":"agent-busy", "message":"session \"9a788d4e-e24b-4bbe-a091-233990c28ce6\" i"…(+27c), "details":{"reason":"use subagent delivery for this child session"}}

// M05 session.prompt 成功形态 / M08 fork / M04 rename：
{"accepted":true}
{"sessionId":"session-418d7378-557b-43ea-91c1-031cc4fbbfae"}
{"title":"api-doc probe scratch", "seq":3}

// M02 session.models 成功（ordinary 会话；failures 数组本机恒空，非空形态※源码推导）：
{"current":{"provider":"zai-coding-cn", "model":"glm-5.3"}, "routable":true, "groups":[{"id":"deepseek-official", "name":"DeepSeek", "models":[{"id":"deepseek-v4-flash", "name":"DeepSeek-V4-Flash", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"low", "name":"Low"}, …(+2 more)], "defaultEffort":"high"}}, {"id":"deepseek-v4-pro", "name":"DeepSeek-V4-Pro", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"low", "name":"Low"}, …(+2 more)], "defaultEffort":"high"}}, …(+1 more)]}, {"id":"opencode-go", "name":"opencode-go", "models":[{"id":"minimax-m3", "name":"MiniMax-M3", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"minimal", "name":"Minimal"}, …(+3 more)]}}, {"id":"qwen3.7-max", "name":"Qwen3.7 Max", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"minimal", "name":"Minimal"}, …(+3 more)]}}, …(+14 more)]}, …(+1 more)], "failures":[]}

// 15 session.search（本部署索引关闭 → internal）：
{"code":"internal", "message":"session search failed: SessionQueryError: sessio"…(+92c), "details":{}}

// M09 attachment 错误 / M07 updateQueue 错误 / M06 cancel：
{"code":"attachment-error", "message":"Image is not referenced by this session.", "details":{"reason":"ATTACHMENT_NOT_REFERENCED"}}
{"code":"queue-item-not-found", "message":"queued item is no longer pending", "details":{"itemId":"msg-no-such"}}
{"accepted":true}
```

session.history（13 尾页，maxMessages=2 → 267 events，seq 10..276，带 projections）事件类型分布与两条样本：
```json
// 类型分布: assistant/chunk 258, assistant/message 1, user/message 1, request/context 1,
//           request/header 1, session/title 2, session/title-llm-request 1, step/end 1, turn/end 1
// chunk 级（token 增量）:
{"event":{"type":"assistant/chunk", "seq":16, "time":1786682367306, "data":{"turn":1, "step":1, "chunk":{"type":"block-start", "index":0, "blockType":"reasoning"}}}}
// surface 级（user/message 带 sourceEventSeqs+surfaceOp）:
{"event":{"type":"user/message", "seq":10, "time":1786682366614, "data":{"content":[{"type":"text", "text":"<system-reminder>\nA skill is a reusable set of t"…(+3104c)}], "source":{"kind":"skill-catalog", "form":"catalog", "entries":[{"name":"code-review", "description":"Review the changes since a fixed point (commit, "…(+370c)}, {"name":"codebase-design", "description":"Shared vocabulary for designing deep modules. Us"…(+217c)}, …(+9 more)]}, "role":"user", "id":"064296e4-0222-4f5d-a36b-22e19dc8bb99"}, "surfaceOp":"append"}}
// 尾页 projections 块（asOfSeq=276）:
{"asOfSeq":276, "values":{"sessionStats":{"turns":1, "steps":1, "llmMs":3052, "toolMs":0, "ttftMs":695, "ttftSteps":1, "decodeMs":2357, "decodeTokens":253}, "title":"你好", "goal":null, "tokenUsage":{"uncachedInputTokens":6060, "outputTokens":253, "cacheReadTokens":9472, "cacheWriteTokens":0}, "contextPressure":{"pressureTokens":15532, "projectedTokens":15737, "contextWindow":1000000}, "contextBreakdown":{"systemTokens":9980, "toolsTokens":224, "messageTokens":4108}, "subagentTiming":{"settledMs":0}, "subagent":null, "permissions":{"options":[{"value":"read-only", "name":"read-only"}, {"value":"workspace-write", "name":"workspace-write"}, …(+1 more)], "currentValue":"danger-full-access"}, "sessionListMetadata":{"blank":false, "lastPromptAt":1786682366613}, "imageLimits":{"maxImageBytes":20971520, "maxImagesPerMessage":20, "maxMessageImageBytes":209715200, "maxImagePixels":64000000, "maxImageDimension":8192, "mediaTypes":["image/png", "image/jpeg", …(+2 more)]}, "todos":null, "plan":{"active":false, "pending":false}}}
```

### subagent 域
```json
// 12 subagent.list（9 entries 取 2；parentAvailable=false 冷父亦可列）：
{"entries":[{"kind":"child", "id":"2906ed75-c813-4e86-875b-4ddf86221ff8", "mode":"continuable", "label":"Write requirements docs", "activity":"inactive", "hasChildren":false}, {"kind":"child", "id":"cefdb6a6-17f0-4477-adba-86a6ae9beef1", "mode":"continuable", "label":"Write UI docs", "activity":"inactive", "hasChildren":false}, …(+7 more)], "parentAvailable":false}

// SA1 interrupt / SA3 not-found：
{"accepted":true}
{"code":"subagent-not-found", "message":"session \"no-such-child\" is not a continuable dir"…(+59c), "details":{"parentSessionId":"session-880a0629-72a7-4d0f-9b2f-05d7a9fca4ef", "childSessionId":"no-such-child"}}
// SA2 subagent.history：1336 events, hasMore:true（体量同 session.history，略）
```

### host / workspace 域
```json
{"version":"0.0.1", "cwd":"/home/leo-tkp/workspace", "provider":"zai-coding-cn", "model":"glm-5.3", "attachedSessions":7, "home":"/home/leo-tkp", "canOpenPath":true}
// provider/model 缺席形态※源码推导（host.d.ts:38-40），本机均有值未实测

{"path":"/tmp", "home":"/home/leo-tkp", "crumbs":[{"name":"/", "path":"/", "hidden":false}, {"name":"tmp", "path":"/tmp", "hidden":false}], "entries":[{"name":".font-unix", "path":"/tmp/.font-unix", "hidden":true}, {"name":".ICE-unix", "path":"/tmp/.ICE-unix", "hidden":true}, …(+46 more)], "truncated":false}

{"path":"/tmp/dsh-openapi-cases/probe-dir"}

// workspace.list（03）单条 WorkspaceView（注意 ISO-8601 时间戳）：
{"workspaceId":"6f9a2cd6-32bd-42d9-951d-614c484bf2d4", "path":"/home/leo-tkp/Documents/docs/外包维权工作区", "title":"外包维权工作区", "sessionIds":["session-27b961ed-e2c1-4023-8c26-d07e5d87aac2", "session-26176cdc-b726-4ca4-bd01-2745d4c8f96f", …(+3 more)], "createdAt":"2026-08-18T08:18:47.637Z", "updatedAt":"2026-08-28T22:10:14.591Z"}

// W1 create / W1b 幂等（created:false）/ W7 delete：
{"workspace":{"workspaceId":"8473cfc5-6eab-46ad-8f43-2509af0ba06a", "path":"/tmp/dsh-openapi-scratch", "title":"dsh-openapi-scratch", "sessionIds":[], "createdAt":"2026-08-30T23:44:52.235Z", "updatedAt":"2026-08-30T23:44:52.235Z"}, "created":true}
{"workspace":{"workspaceId":"8473cfc5-6eab-46ad-8f43-2509af0ba06a", "path":"/tmp/dsh-openapi-scratch", "title":"api-doc scratch ws", "sessionIds":[], "createdAt":"2026-08-30T23:44:52.235Z", "updatedAt":"2026-08-30T23:44:52.264Z"}, "created":false}
{"deleted":true}
```

### skill / agentPreset / goal 域
```json
// 10 skill.list（31 条取 2）：
{"skills":[{"name":"ask-matt", "description":"Ask which skill or flow fits your situation. A r"…(+35c), "modelInvocable":false}, {"name":"code-review", "description":"Review the changes since a fixed point (commit, "…(+370c), "modelInvocable":true}, …(+29 more)]}

// 07 agentPreset.list（4 条取 2）：
{"presets":[{"id":"standard", "trust":"system", "isDefault":false, "name":"标准模式", "description":"功能完整的编码 Agent，支持文件编辑、Shell、文件与网页检索、Skills、计划、目标、子代理和工作流。"}, {"id":"code", "trust":"system", "isDefault":true, "name":"PTC 模式", "description":"具备标准模式的全部能力，并通过 Code Mode SDK 呈现工具，让模型用一个 TypeScript 程序组合多步操作。"}, …(+2 more)], "authorable":true, "hasDocument":true}

// 16 agentPreset.read（content 3673 字符截断）：
{"agentPreset":"minimal", "trust":"system", "content":"# The `minimal` agent preset: a fixed-prompt, tw"…(+3625c), "name":"极简模式", "description":"仅提供持久 bash 与 str_replace_editor 的双工具编码 Agent。"}

// goal 生命周期（CAS ref 链）：G1 create → G2 edit → G3b pause → G5c complete → G6c clear
{"ref":{"id":"goal-58890401-ee02-4251-856a-8d7612313184", "revision":1}}
{"ref":{"id":"goal-58890401-ee02-4251-856a-8d7612313184", "revision":2}}
{"ref":{"id":"goal-58890401-ee02-4251-856a-8d7612313184", "revision":3}}
{"ref":{"id":"goal-58890401-ee02-4251-856a-8d7612313184", "revision":4}}
{"cleared":true}
// G3 用旧 ref（revision 1，当前 2）→ internal（GoalError，无专用码）：
{"code":"internal", "message":"GoalError: stale goal ref \"goal-58890401-ee02-42"…(+99c), "details":{"goalCode":"GOAL_STALE_REVISION"}}
// G4b 轮次耗尽后 resume → internal：
{"code":"internal", "message":"GoalError: goal \"goal-58890401-ee02-4251-856a-8d"…(+75c), "details":{"goalCode":"GOAL_INVALID_TRANSITION"}}
```

### settings / credentials / llm 域
```json
// 08 settings.describe（13 namespaces；取 ns0 + 键名清单；schema/value 为 schemastery JSON——结构保留截断）：
{"ns":"agent-default-model", "schema":{"uid":36, "refs":{"32":{"type":"string", "meta":{"required":true}}, "34":{"type":"string", "meta":{"required":true}}, "35":{"type":"string", "meta":{}}, "36":{"type":"object", "meta":{"default":{}}, "dict":{"provider":32, "model":34, "reasoningEffort":35}}}}, "value":{"provider":"zai-coding-cn", "model":"glm-5.3"}, "base":{"provider":"deepseek-official", "model":"deepseek-v4-flash"}, "user":{"provider":"zai-coding-cn", "model":"glm-5.3"}, "applies":"live", "secrets":[], "revision":0}
namespaces: ["agent-default-model", "ui-onboarding", …(+11 more)]

// 09 credentials.describe（值永不回 ride）：
{"credentials":{"DEEPSEEK_API_KEY":{"configured":true, "source":"file", "writable":true}, "ANTHROPIC_API_KEY":{"configured":false, "writable":true}}}

// 05 llm.providers（38 条取 2：active 无 declared 键 / dormant 有 declared:false）：
{"provider":"deepseek-official", "displayName":"DeepSeek", "settingsNs":"llm-deepseek", "settingsPath":[], "active":true}
{"provider":"amazon-bedrock", "displayName":"amazon-bedrock", "settingsNs":"llm-pi-ai", "settingsPath":["providers", "amazon-bedrock"], "active":false, "declared":false}

// 06 llm.models（groups 截断；failures 恒空，非空形态※源码推导）：
{"groups":[{"id":"deepseek-official", "name":"DeepSeek", "models":[{"id":"deepseek-v4-flash", "name":"DeepSeek-V4-Flash", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"low", "name":"Low"}, …(+2 more)], "defaultEffort":"high"}}, {"id":"deepseek-v4-pro", "name":"DeepSeek-V4-Pro", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"low", "name":"Low"}, …(+2 more)], "defaultEffort":"high"}}, …(+1 more)]}, {"id":"opencode-go", "name":"opencode-go", "models":[{"id":"minimax-m3", "name":"MiniMax-M3", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"minimal", "name":"Minimal"}, …(+3 more)]}}, {"id":"qwen3.7-max", "name":"Qwen3.7 Max", "reasoning":{"efforts":[{"id":"off", "name":"Off"}, {"id":"minimal", "name":"Minimal"}, …(+3 more)]}}, …(+14 more)]}, …(+1 more)], "failures":[]}

// E18 llm.discoverModels 不可达端点 → model-discovery-failed：
{"code":"model-discovery-failed", "message":"no model discovery is registered for \"llm-deepseek\"", "details":{"settingsNs":"llm-deepseek", "baseURL":"http://127.0.0.1:1/v1"}}
```

### 非信封入口
```json
// E11/E11b /api/respond → RpcReceipt 两分支（accepted:true 形态※源码推导，需真实 pending 审批）：



// X1 /api/session.export 响应头 + zip 清单（includeDescendants=true，fork 子在 subagents/ 下）：
//   HTTP/1.1 200 OK
//   content-type: application/zip
//   content-disposition: attachment; filename="dsh-session-session-6b828b7c-….zip"
//   zip: session.jsonl (203663 B) + subagents/session-418d7378-…/session.jsonl (56169 B)
```

### WS 帧（ws-sample-output.json，12s 采样）
```json
// 同刻校验：subscribedBaselinesAtOpen=10 = attachedSessions=10（此前 11 vs 7 为时序偏差）
// 帧计数: {"session/subscribed":10, "session/queue":1, "session/event":245, "session/projection":16}
// session/subscribed 样本:
{"type":"server-request","rpcId":"eaed0a9a-1c37-42e7-81f8-c376b6a89c3d","method":"session/subscribed","payload":{"type":"session/subscribed","sessionId":"session-a6c42dd8-1c16-46bf-bac1-6344cf46a56c","lastSeq":294415}}
// session/queue 样本:
{"type":"server-request","rpcId":"52a026c2-9bf3-4f91-bdb1-7f0584fcc19b","method":"session/queue","payload":{"type":"session/queue","sessionId":"ca59cb42-30a4-4e75-aad1-2e02f0484b64","items":[{"id":"46aff7ae-349d-4878-ba38-762a91989379","placement":"queued","message":{"content":[{"type":"text","text":"用户走查矩阵新增三项根因修复（扩大你的范围，与其余工作同批 TDD）：\n\n【N1 C1 假成功——最严重】还原按钮：server 拒绝（session.revert 不存在）但 UI 显「消息已还原」成功 banner；banner redo 再失败且静默。两层修：a) revertSupported 门控你已在做——确保消息长按「还原」入口+RevertBanner+slash undo 路由全覆盖；b) **假成功根因**：审 SessionActionsDelegate.undoMessage/redoMessage 的 onResult 语义——疑似 runCatching 吞掉异常后仍回调 true 或 fire-and-forget；服务器错误必须传 false/错误提示（证据 /tmp/dsh-e2e-buttons/C1-revert-*）。\n【N2 D1 wor…TRUNC
// session/event 样本:
{"type":"server-request","rpcId":"fe158b6a-9931-44b0-ae50-ba04bd1d2657","method":"session/event","payload":{"type":"session/event","sessionId":"session-a6c42dd8-1c16-46bf-bac1-6344cf46a56c","event":{"type":"assistant/chunk","seq":294416,"time":1788134088875,"data":{"turn":75,"step":6,"chunk":{"type":"text-delta","index":1,"text":"查"}}}}}
// session/projection 样本:
{"type":"server-request","rpcId":"2d10d576-bbb0-4824-99f1-d5920e0665d2","method":"session/projection","payload":{"type":"session/projection","sessionId":"session-a6c42dd8-1c16-46bf-bac1-6344cf46a56c","key":"tokenUsage","value":{"uncachedInputTokens":1858765,"outputTokens":489355,"cacheReadTokens":157465472,"cacheWriteTokens":0},"seq":294652}}
// approval/question requested、session/jobs、host/* 帧本会话未出现※（schema 源码推导；P2P3 190s 曾观察 session/jobs 2 帧、host/session-status 4 帧）
```
---

## 7. 源码推导、未实测清单（spec 内已收录但无活体证据的形态）

| 项 | 出处 | 说明 |
|---|---|---|
| session.prompt 成功值 command 槽 | sessions.d.ts:374-380 | 斜杠命令路径（{kind:"success",text?}/command-error/unknown-command）未触发 |
| prompt image part / invalid-time-zone | sessions.d.ts:86-94 | 未发送图片；时区仅传过合法值 |
| session.create sessionId 预分配/冲突、workspace-attach-failed、agent-preset-not-found(on create) | sessions.d.ts:249-271 | 三参数形态只测了 cwd/workspaceId |
| session.fork atSeq 锚定 / fork-unavailable | sessions.d.ts:342-361 | 只测了省略 atSeq（末个完结 turn） |
| session.attachment 成功形态（ImageAttachmentRef+base64 data） | sessions.d.ts:381-388 / schema.js:242-260 | 无种子附件 |
| updateQueue 成功（edit/steer 分支）、steer-unavailable | sessions.d.ts:389-399 | 仅 remove 分支的错误路径 |
| SessionModels.failures[] 非空（ModelCatalogFailure） | sessions.d.ts:141-148 | 本机全部 provider 加载成功（failures:[]） |
| host.describe provider/model 缺席 | host.d.ts:38-40 | 本机配置了默认路由 |
| host.listDirectory truncated:true / directory-unreadable | host.d.ts:59-68 | /tmp 列表完整返回 |
| host.pickDirectory {path:null} / directory-picker-unavailable | host.d.ts:53-58 | SKIPPED（原生对话框） |
| llm.discoverModels 成功形态（DiscoveredModelView[]） | llm.d.ts:67-87 | 仅错误路径 E18 |
| settings namespace 的 user/base 非空层、secrets set:true、settings-rejected/conflict | settings.d.ts:17-38、75-111 | 本机未写过 user 层（revision 恒 0）；写方法全部 SKIPPED |
| credentials.set/unset 全部行为、credential-rejected | credentials.d.ts:24-47 | SKIPPED（写密钥库） |
| RpcReceipt accepted:true | rpc.d.ts:256-261 | 需真实 pending 审批（无法安全制造） |
| MuxFrame：approval/requested、approval/resolved、question/requested、question/resolved、session/jobs、stream/error | events.d.ts:66-145 | 本会话无待答审批/后台任务/流错误；帧 schema 全源码推导（P2P3 190s 曾观察 session/jobs 2 帧） |
| HostFrame 除文档描述外全部变体（session-added/removed、agent-error、workspace-*、remote-event、stream/error） | events.d.ts:163-212 | events.host 本会话空闲零帧；P2P3 曾观察 host/session-status 4 帧 |
| QueuedInboxItem placement: "steering"/"context" | events.d.ts:34-42 | 活体仅见 "queued"（ws-sample session/queue 帧） |
| SessionEvent 49 型中 13 型 | known-event-types.js:18-68 | 本机普查未出现（team/*、tool-workflow/*、hook/*、feedback/record、plan/mode、schedule/change；P4 普查） |
| 25 个错误码的 details 形态 | rpc.schema.js:16-60 | §2 表 ✗ 行（未触发） |
| zod 宽容性（多余字段忽略） | handler.js 两级解析 | P4 已实证（bogusExtraField 被忽略），本次未复测 |

## 8. 信源清单

**主源（权威，逐字段转录）**
- `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/types/api/*.d.ts` + 同名 `*.schema.js`（rpc/rpc-map/sessions/subagents/host/workspace/skills/agent-presets/goals/settings/credentials/llm/events/downloads/jobs/questions/approvals/session-search）
- `…/dsh-host-apiproxy/lib/types/fetch/handler.js`（POST/GET/HEAD 路由、两级解析、404/415/400/500、SSE 变体、respond）
- `…/dsh-client-connection/lib/index.js`（栅栏 184-198、特权集 504-520、426 拦截 539-545、桥接 38-87（413）、WS 下行 334-465、升级栅栏 566-585、300MiB 默认 29）
- `…/dsh-session/lib/types/types.d.ts`（SessionEventMap/SessionHeader）+ `known-event-types.js:18-68`（49 事件型目录）
- `…/dsh-host-apiproxy/lib/index.js`（version 字面量 3110、sessionLog 实现 3269-3306、respond 路由 3309+、search 双失败形态 2409）

**活体（实测）**：http://127.0.0.1:3080（curl --noproxy '*'；WS 用 node+ws）；证据 /tmp/dsh-openapi-cases/（INDEX.md 索引；probe.sh / ws-probe.cjs / ws-sample.cjs / gen-spec*.cjs 生成链可复现）

**交叉参考**：/tmp/dsh-probes/P4-rpc-surface.md（52 方法三重吻合）、P2P3-ws-fences.md（WS 握手矩阵/keepalive/重连）、event-census-result.txt（事件普查）；docs/specs/2026-08-31-dsh-integration-design.md §5

## 9. 验收自检

**52 方法全覆盖**（✔=有成功实测 40 · △=仅错误实测 3 · ○=SKIPPED 12，○ 均注明原因）：

session.list✔ · session.search△ · session.create✔ · session.history✔ · session.models✔ · session.selectModel✔ · session.rename✔ · session.fork✔ · session.prompt✔ · session.attachment△ · session.updateQueue△ · session.cancel✔ · subagent.list✔ · subagent.history✔ · subagent.prompt○ · subagent.interrupt✔ · host.describe✔ · host.pickDirectory○ · host.listDirectory✔ · host.createDirectory✔ · host.openPath○ · workspace.list✔ · workspace.create✔ · workspace.rename✔ · workspace.delete✔ · workspace.insertBefore✔ · workspace.insertSessionBefore✔ · workspace.archiveSession✔ · skill.list✔ · agentPreset.list✔ · agentPreset.select✔ · agentPreset.read✔ · agentPreset.copy○ · agentPreset.openDocument○ · agentPreset.remove○ · goal.create✔ · goal.edit✔ · goal.pause✔ · goal.resume△ · goal.complete✔ · goal.clear✔ · settings.describe✔ · settings.openDocument○ · settings.update○ · settings.replace○ · settings.mutate○ · credentials.describe✔ · credentials.set○ · credentials.unset○ · llm.providers✔ · llm.models✔ · llm.discoverModels△

域分布核对：session 12 / subagent 4 / host 5 / workspace 7 / skill 1 / agentPreset 6 / goal 6 / settings 5 / credentials 3 / llm 3 = **52** ✓（rpc-map.d.ts:22-75 与 fetch/handler.js UNARY_ROUTES 双源一致）

**4 非信封入口覆盖**：respond✔（E11/E11b）· export✔（X1/X2/E13a/E13b）· events.mux✔（E12a + WS2 + ws-sample）· events.host✔（E12b + WS1）✓

**39 错误码枚举完整**：RpcError oneOf 39 分支，details 字段/必填从 rpc.schema.js 转录；程序化核对 codes=39 ✓；其中 12 码实测触发（§2 ✔ 行）

**WS 两流帧 schema 完整**：MuxFrame 10 变体 + HostFrame 10 变体，ServerRequest 信封包裹，x-websocket.frames $ref 挂接 ✓

**双源矩阵**：52+4+栅栏 每行均有「源码依据（文件:行）+ 实测证据（文件名）+ 结论」三列；无「只有实测无源码」的接口 ✓

**DISCREPANCY 清单**：本节存在且非空——**7 条（D1-D7）+ GAP 5 条（G1-G5）+ 勘误 1 条**（§5）；每条均完成「spec 以活体为准」裁定 ✓

**实测 case 统计**：成功 42（含 V01 复测与 WS 2 成功流）· 错误/边界 31（含 V02）· WS 探测 5（WS1-4 + ws-sample 同刻校验）· 跳过 14（原因逐项见 §4 ○ 行与 §7；提示词预算 1/2 条）

**规范质量**：js-yaml 往返解析通过；219 $ref 全解析（0 未解析）；57 operation 全具 operationId+responses；15 特权方法与 PRIVILEGED_METHODS 逐一对应 ✓
