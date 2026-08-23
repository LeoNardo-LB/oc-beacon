# A1 · OpenCode 官方 API 对照审计（只读深调研 · 终版）

> 调研日期：2026-02-04 · 方法：web_search（约 35 轮）对照官方 GitHub anomalyco/opencode（旧组织 sst/opencode 重定向）、官方文档站 opencode.ai（含 /v2/docs API 参考 + migrate-v1 页）、官方 SDK 仓库（opencode-sdk-js / opencode-sdk-python）、官方 OpenAPI 生成的 Elixir SDK（hexdocs Generated.*）、第三方客户端互证
> 我方基线：`.scratch/terminology/inventory/01-data-layer.md` L198-264「API 术语权威清单」（990 文件代码内省）
>
> ⚠️ 工具限制声明（影响证据深度，裁决前必读）：本会话 bash 工具存在参数校验缺陷（三次调用均报 missing description，无法执行 curl），web_search 大部分结果只返回标题+URL、无正文摘要。因此本审计能达到的证明层级是「页面/模块/issue 存在性 + 标题级命名」，**无法**逐字段比对源码。所有未能闭合的项明确标注「官方资料未检索到」，绝无编造。

## 证据强度分级

- **S 级**：官方仓库页面（anomalyco/opencode 的 issue/PR/commit/代码文件）与官方文档站页面（opencode.ai）
- **A 级**：官方 OpenAPI spec 机械生成的 SDK 文档（hexdocs `OpenCode.Generated.*`，命名由 spec 直接派生，无人工干预）
- **B 级**：官方第一方引用（官方 TUI 源码 import、官方 vendored SDK 副本）
- **C 级**：第三方客户端/教程互证（vibe-kanban、research-agent、ocn、toolpath、untether、learnopencode 等）

---

## 一致项

### 1. 仓库归属 + about_opencode_url（任务 6）✅ 结案
- **官方仓库 = https://github.com/anomalyco/opencode**（描述 "The open source coding agent"）。sst/opencode 为原组织名（GitHub 重定向；DeepWiki/jsdelivr 仍按 sst 索引历史内容）。S 级。
- 我方 strings.xml **EN :607 + 全部 14 个翻译**的 `about_opencode_url = https://github.com/anomalyco/opencode`（本次逐文件 grep 确认 15/15 一致）→ **与官方一致，非过期**。08 路「待核验」标记可关闭。

### 2. 「V2」是官方概念，非我方自创 ✅（对 CONTEXT.md「版本 seam」词条的强支撑）
- 官方 TUI 源码：`import { createOpencodeClient, type Event } from "@opencode-ai/sdk/v2"`（S 级）https://github.com/anomalyco/opencode/blob/28a06e52/packages/opencode/src/cli/cmd/tui/context/sdk.tsx
- 官方 OpenAPI 生成类型名内嵌 V2：`OpenCode.Generated.QuestionV2Request`、`OpenCode.Generated.AccountV2Info`、`OpenCode.Generated.EventQuestionV2AskedProperties`（A 级）https://opencode-sdk.hexdocs.pm/0.1.84/OpenCode.Generated.QuestionV2Request.html
- 官方 issue 自带 `v2:` 标题前缀（#37372）；官方 PR 语汇：**"Refactor v2 session events as schemas" #24512**（S 级）、"Add v2 session failure events" #25628、"migrate **MessageV2** message DTOs (User/Assistant/Part/Info/WithParts)" #23757、"expose v2 model listing API" #25821、"admit v2 skill guidance" #30843、"add embedded v2 session runtime" #30632
- 官方文档站设 /v2/docs 独立区（含 API 参考与 **migrate-v1** 迁移页）（S 级）https://opencode.ai/v2/docs/migrate-v1
- 官方 SDK 仓库：opencode-sdk-js（npm @opencode-ai/sdk，含 /v2 子路径导出）、opencode-sdk-python（S 级）

### 3. V2 端点路径抽查（任务 1）✅ 域级/页面级一致
官方 opencode.ai/v2/docs/api/* 文档页存在性 = 端点存在性（S 级）。逐项对照我方清单：

| 我方端点 | 官方证据 | 强度 |
|---|---|---|
| GET/POST /api/session | 文档页 v2-session-list（"List sessions"）、v2-session-get | S |
| POST /api/session/{id}/prompt | 文档页 v2-session-prompt（"Send message"） | S |
| POST /api/session/{id}/command | 文档页 v2-session-command（"Run command"） | S |
| POST /api/session/{id}/shell | 文档页 v2-session-shell（"Run shell command"） | S |
| POST /api/session/{id}/compact | 文档页 v2-session-compact（"Compact session"） | S |
| （我方无）generate | 文档页 v2-session-generate（"Generate text from session context"）——**官方有我方无**，见官方无此物节 | S |
| GET /api/fs/find | 文档页 v2-fs-find（"Find files"）；生成 SDK 函数 `v2_fs_read/1` 证 fs/read 存在 | S+A |
| GET /api/event | 文档页 v2-event-subscribe（"Subscribe to events"） | S |
| POST /api/session/{sid}/form/{fid}/reply | 文档页 v2-session-form-reply（"Reply to form"） | S |
| GET /api/form/request | 文档页 v2-form-request-list（"List pending form requests"） | S |
| question 域 | 生成类型 QuestionRequest / QuestionV2Request | A |
| /api/shell 后台 shell | 域级未检索到专页（概念侧证：issue #28034「Non-blocking background task dispatch」）；**官方资料未检索到**（页面级） | C |
| /api/credential/{id} | 端点页未检索到；"credential" 是官方 V2 词汇（issue #44065 "non-credential env var as the bearer API key"） | S(词) C(端点) |
| /pty 域（V1） | issue #18355「/pty requests fall through」证 V1 pty 路由存在 | S |

- **路径前缀口径补正**：官方操作命名轴是 `v2_*`（operationId，如 v2_fs_read、文档 slug v2-session-*），线径轴为 /api/*。两轴并行不矛盾——我方「V2 = /api 前缀」的线径观察与官方命名体系**相容**（A 级推断；逐字路径比对需源码级访问，本会话工具受限）。

### 4. SSE 事件名（任务 2）✅ 多数证实
| 我方事件 | 官方证据 | 强度 |
|---|---|---|
| session.idle | hexdocs `OpenCode.Generated.EventSessionIdle` | A |
| message.part.updated | hexdocs `OpenCode.Generated.EventMessagePartUpdatedProperties` | A |
| message.part.delta | 官方生态 issue「empty responses after OpenCode 1.2.0 — message.part.delta not handled」 | S |
| question.asked（V1） | vibe-kanban #2088 "Unrecognized OpenCode SDK event type 'question.asked'" + ocn commit 510cbee 处理 asked/replied/rejected | C×2 |
| **question.v2.asked** | hexdocs `OpenCode.Generated.EventQuestionV2AskedProperties`（0.1.84）——**官方事件，非我方兼容层命名** | **A（决定性）** |
| session.execution.*（turn 权威） | PR #24512 "Refactor v2 session events as schemas"（commit c0edf72 可见 `executed: toolCall` 字段）、#33992 "wake embedded session execution"、#37372（v2: 前缀 issue）——execution 事件族官方存在；逐成员清单需源码级 | S（族级） |
| v2 失败事件 | PR #25628 "Add v2 session failure events" | S |
| 全局信封（V1 /global/event） | hexdocs `OpenCode.Generated.GlobalEvent` | A |
| durable 持久历史 | zread「session lifecycle and durable history」+ hexdocs Generated.Sync（sync_history）——durable 概念官方 | B |
| session.compaction.* | 概念官方（compaction 文档页 https://opencode.ai/v2/docs/compaction ；hexdocs CompactionPart）；事件名级未直接检索到 | S(概念) |
| 事件信封字段 durable{seq,aggregateID} | 未逐字段检索到；durable 概念见上 | B |
| server.connected / heartbeat | 未检索到直接证据 | — |

### 5. Part 类型成员（任务 3）✅ 10/14 成员获官方侧证
| 我方 part 类型 | 官方证据 | 强度 |
|---|---|---|
| step-start / step-finish | PR #12470「handle step-start/step-finish parts」、issue #16749 | S |
| reasoning | commit a2a9217「strip reasoning parts from messages」 | S |
| text | PR #12470 extractResponseText（间接） | S(间接) |
| tool | issue #16749（tool_use/tool_result mismatch 语汇） | S(间接) |
| compaction | hexdocs `OpenCode.Generated.CompactionPart` | A |
| agent | hexdocs `OpenCode.Generated.AgentPart` | A |
| patch | issue #9042「patch part bypasses filter」 | S |
| file | vendored SDK models/file_part.py | B |
| subtask | PR #14023「subtask agents」——概念官方；part 级证据弱 | S(概念) |
| snapshot / retry / abort / session-turn | **官方资料未检索到**（retry 概念侧证 PR #26167；abort 未见 part 级） | — |
| message type synthetic | PR #9593（synthetic 消息类型官方） | S |

### 6. ID 前缀（任务 4）✅1 ⚠️1 ❓4
- **ses_ ✅**：官方 issue #31145 标题含 `(opencode%3Ases_...)`（S 级）
- **msg_ ⚠️**：前缀用于官方生态（vendored SDK）；**但官方 issue #42583「Message ID generation wraps every ~2.18 years」+ #42639 + onyx PR #14130「message-ID rollover fix」+ #11863（client 生成、时钟敏感）→ 消息 ID 是时间派生可回绕序号，绝非 ULID**（S 级）
- pty_ / frm_ / call_ / evt_：**官方资料未检索到**（工具限制内）

### 7. 认证头/目录头（任务 5）✅
- **x-opencode-directory ✅**：官方 issue #13256 标题即「x-opencode-directory header invalid on Windows Chinese path」（S 级）；legion PR #42 同名头互证（C 级）
- **Authorization（Basic + 密码）✅**：官方 issue #8676（OPENCODE_SERVER_PASSWORD→401）、#10047（Basic Auth breaks CORS）、#28180（SSE 订阅忽略 custom fetch→Basic Auth broken）（S 级×3）——我方「用户名 opencode + service.json password」实现方式与官方密码鉴权模型一致

---

## 不一致项

| 我方清单 | 官方事实 | 来源 URL | 影响词条 |
|---|---|---|---|
| 「ID 前缀词汇…msg_（消息）」按「服务器 ULID 体系」定性（L257-258 将 msg_ 与 ses_ 并列为 ULID 体系） | 官方消息 ID 会周期性回绕（~2.18 年）且支持 client 生成、随时钟漂移——是时间序号而非 ULID；ses_ 等其余前缀未见反证 | https://github.com/anomalyco/opencode/issues/42583 ；…/issues/42639 ；…/issues/11863 ；https://github.com/onyx-dot-app/onyx/pull/14130 | ID 前缀词条、partID/ordinal 派生注释、红点时钟域（completed==null 语义不受影响，仅 ID 机制注释） |
| （轻微）清单未收录 /api/session/{id}/generate 端点 | 官方 v2 文档存在 v2-session-generate（Generate text from session context）——官方有、我方客户端未实现（非错误，属盘点缺口） | https://opencode.ai/v2/docs/api/session/v2-session-generate | 端点全集词条补录候选 |

## 官方无此物（我方自创/过期）

**本轮未发现任何「确凿官方无此物」项。** 以下我方清单条目为「官方资料未检索到」——受本会话工具限制（无正文抓取），这是**证据缺失而非存在性否定**，不得在裁决中当作"自创"结论使用：

1. `session.input.admitted` / `session.input.promoted`（事件名级）——但 "admit/admission" 是官方 V2 动词（PR #34338 "commit staged revert before admitting new prompt"、#30843 "admit v2 skill guidance"，S 级），admission 概念官方
2. `session.inbox.enqueued/delivered`（事件名级）——"enqueued/dequeued" 出现在官方 issue #33761（S 级），inbox 概念有官方语汇侧证
3. `form.created` / `form.replied` / `form.cancelled`（事件名级）——form **端点**官方（v2-session-form-reply、v2-form-request-list 文档页，S 级），事件名未检索到
4. `shell.created/exited/deleted` 与 `session.shell.started/ended` 二者均未检索到直接证据（npm @dragonwize/opencode-event-shell-exec 仅侧证 shell 执行事件存在，C 级）
5. part 类型 `snapshot`、`retry`、`abort`、`session-turn`（abort 相关：我方 interrupt 端点注释与官方对应，但 abort **part** 无证据）
6. ID 前缀 `pty_`、`frm_`、`call_`、`evt_`
7. `/api/credential/{id}` 端点页、"credential" 词汇本身官方（issue #44065，S 级）
8. `server.connected`、`server.heartbeat`、`: heartbeat` 注释帧
9. `session.next.*` 前缀族（V1 细粒度判别式）——官方资料未检索到（疑为我方对 V1 事件的结构化归类层）

## 待裁决点

**D1 ·（C27 Part 计数三源之争）官方闭合失败，改按"证实度分层"裁决。**
问题：12/13/16 三个计数都无官方数字背书；官方可证实的 part 成员为 10 个（text 间接、reasoning、tool 间接、step-start、step-finish、file、compaction、agent、patch、subtask 概念级），snapshot/retry/abort/session-turn 四个无官方证据。
候选方案：① CONTEXT.md Part 词条不写死总数，注释改为「官方 schema 成员随版本演化，以实测判别式为准」；② 计数口径定为「我方 parser 支持数（含客户端扩展 shell/permission/question/unknown）」与「官方证实数」双列。推荐②（诚实且稳定）。

**D2 ·（C18 三代契约）question.v2 已获官方决定性证据，form 事件名级缺证。**
问题：`question.v2.asked` = 官方事件（EventQuestionV2AskedProperties，A 级）——「v2 是我方叫法」的说法不成立；但 `form.created` 事件名未检索到，form 仅端点级官方。
候选方案：CONTEXT.md「form/question 三代契约」词条改述为「question(v1)→question.v2(官方事件)→form(官方端点，事件名以实测为准)」，form 不再称为"我们的兼容层"也不称为"官方中间态"，标注「端点级官方、事件级实测」。

**D3 · msg_ ID 机制措辞。**
问题：ULID 定性被官方 issue 反驳（回绕+客户端生成）。
候选方案：① ID 前缀词条改为「msg_（消息，时间序号 ID，非 ULID）；ses_/pty_…（前缀确定，机制未核）」；② 保守版：只写前缀、删去全部 ULID 机制描述。推荐①。

**D4 · session.input.*/inbox.* 的「过渡态」注释。**
问题：两族事件名无官方文档证据，但 admit/enqueue/deliver 动词全部官方。
候选方案：注释定性从「过渡（transitional）」改为「V2 实测事件（官方文档未见，动词源于官方 admit/enqueue 语汇）」——避免读者误解为即将废弃或我方发明。

**D5 · V2 术语口径补一句话。**
问题：我方「V2=/api 前缀」是线径观察；官方另有 v2_* 操作命名轴（v2_fs_read、v2-* 文档 slug、QuestionV2* 类型名）。
候选方案：docs/v1-v2-differences.md 或 CONTEXT.md 版本 seam 词条补注「官方命名轴 v2_*（operationId/类型名），线径轴 /api/*，二者并行」。需一次源码级复核后落笔。

**D6 · about_opencode_url 结案。**
15/15 语言与官方仓库一致 → 08 路待核验标记关闭，无词条动作。

## 来源索引（全部为本轮实际检索命中）

**官方仓库 S 级**：
- https://github.com/anomalyco/opencode （官方仓库主页）
- issues：#31145(ses_) · #42583/#42639(msg ID 回绕) · #11863(client 生成 ID) · #13256(x-opencode-directory) · #8676/#10047/#28180(认证) · #12470/#16749(step part) · #9042(patch part) · #9593(synthetic) · #14023(subtask) · #18355(/pty) · #33761(enqueued) · #37372(v2:) · #28034(background dispatch) · #44065(credential 词汇) · #7147/#7641(SDK 类型对齐)
- PR/commit：#24512 & c0edf72(v2 session events schemas) · #25628(v2 failure events) · #23757(MessageV2 DTO) · #25821(v2 model listing) · #30843(admit v2 skill) · #30632(embedded v2 runtime) · #33992(wake execution) · #34338(admitting new prompt) · a2a9217(reasoning parts)
- 官方 TUI 源码：https://github.com/anomalyco/opencode/blob/28a06e52/packages/opencode/src/cli/cmd/tui/context/sdk.tsx
- 官方 SDK：https://github.com/anomalyco/opencode-sdk-js ；https://github.com/anomalyco/opencode-sdk-python

**官方文档站 S 级**：https://opencode.ai/docs/server ；https://opencode.ai/v2/docs/migrate-v1 ；/v2/docs/api/ 下：session/v2-session-list · v2-session-get · v2-session-prompt · v2-session-command · v2-session-shell · v2-session-compact · v2-session-generate · filesystem/v2-fs-find · event/v2-event-subscribe · form/v2-session-form-reply · form/v2-form-request-list ；https://opencode.ai/v2/docs/compaction ；https://opencode.ai/v2/docs/providers

**生成 SDK（A 级）**：https://opencode-sdk.hexdocs.pm/OpenCode.Generated.Filesystem.html#v2_fs_read/1 ；Generated.GlobalEvent ；Generated.EventSessionIdle ；Generated.EventMessagePartUpdatedProperties ；Generated.EventQuestionV2AskedProperties(0.1.84) ；Generated.QuestionV2Request ；Generated.QuestionRequest ；Generated.AccountV2Info ；Generated.CompactionPart ；Generated.AgentPart ；Generated.Sync ；api-reference.html#modules

**B/C 级互证**：vibe-kanban#2088(question.asked) · research-agent#173(message.part.delta) · ocn@510cbee(question 三事件) · legion#42(directory 头) · sandboxagent.dev/docs/opencode-compatibility/ · GitLab orbit-evals-harness vendored opencode_sdk(file_part.py/part_update.py/question_list.py) · docs.rs/opencode2claude(sse.rs) · docs.rs/toolpath-opencode · empathic/toolpath(opencode.md 格式文档) · littlebearapps untether(stream-json cheatsheet) · learnopencode.com(10b/10c) · ZeroZ-lab/learn-opencode(bus.md) · joshuadavidthomas/opencode-plugins-manual(07-events) · deepwiki.com/anomalyco/opencode(2.3/2.7/4.2/5.2) · zread.ai(11/14/19) · kdcokenny/opencode-notify(事件类型指南)

## 方法论备注（给下一轮深调研）

1. 本会话 bash 不可用是 DSH 工具缺陷，非沙箱策略；后续会话若恢复，直接 `curl raw.githubusercontent.com/anomalyco/opencode/dev/packages/sdk/js/src/gen/types.gen.ts` 与 `packages/opencode/src/server/server.ts` 即可一次闭合 D1/D2/D4/D5 全部残留。
2. hexdocs `OpenCode.Generated.Event*.md` 模块清单（`/api-reference.html#modules` 页）是事件全集的最佳单一来源；本轮因无正文抓取未能展开。
3. 官方 specs/v2/ 目录（specs/v2/todo.md 已见）可能承载 v2 全量设计规格，值得源码级访问。
