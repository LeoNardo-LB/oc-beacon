# dsh-integration-and-disconnect-design（2026-08-30）

> 状态：进行中（探针 P-1..P-4 全收官 + 设计定稿 2026-08-31-dsh-integration-design.md + #274 基础层实现完毕；#275/#276 进行中；「断连设计」部分未开工）
> 关联：docs/research/2026-08-30-dsh-integration-feasibility.md（调研文档）· backlog #268
> 来源：用户反馈 / grilling（拷问轮 11-15 题裁决）/ 调研子代理产出

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 批次执行记录

### 2026-08-31（五）：定向复测 4/4 全 PASS——DSH 端到端闭环（代理 ca8f118e，证据 /tmp/dsh-e2e-retest/）

- **R1 标题 PASS**：列表全显示真实标题（前五：StreamingMarkdownState优化…/dsh 自定义系统提示词方法/DSH流式空闲超时卡死排查/案卷目录…/梳理backlog可关闭项）——裸字符串双读修复实证。
- **R2 fold PASS**：上轮空白会话（398 个 llm/failover）与 160-failover 会话均完整渲染；'refused rebuild' 0 条——收编+旗标兑现实证。
- **R3 发消息+流式 PASS（P0 修复实证）**：prompt 230ms 受理（无 zod 拒绝）→ 服务端 user/message→~150 chunk→run_code 工具块→text 回显→turn/end completed；UI 全链（回显→工具块→文本→4.5s→Idle）。
- **R4 中断 PASS（上轮 BLOCKED 补测）**：491 chunk 后停止→cancel 68ms 生效→服务端 turn/end aborted(user)→前缀持久化→UI 回 Idle。
- 全程 0 FATAL；提示词 2/2 上限内；title 投影形态复证（364/378 str）。

**DSH 接入交付定论**：探针（P-1..P-4 双源）→ 设计（双源实证文档）→ TDD 四层（#274 基础 39 测/#275 事件 45 测/#276 核心 47 测+UI）→ 真机 E2E 六段（3P+1P 修复后复测全 P）→ 四缺陷根治复绿。**#269/#276 关单迁册（本节即迁册记录）**。残留（不影响交付）：approval 卡未遇（实例 policy=never，映射已备+单测）；rename 回显未测；steer 模式、SessionUsage、queue/jobs/projection 域、ChatScreen 内 PTY 入口门控为后续增强位。
### 2026-08-31（四）：#276 UI 面（24692a9c）+ 真机 E2E 首跑（代理 f1d037e3）+ 四缺陷根治

**UI 面**（24692a9c，33 文件）：ServerDialog 类型 SegmentedButton+DSH 凭据隐藏+提示；ServerCard DSH 徽章；能力位新增 6 位（terminal/fileRead/sessionDelete/vcs/fileSearch/commands）全 UI 门控；from(config) 统一（修复漏带 serverType 致能力位错回 OpenCode）；i18n 4 keys×15。
- 代理漏网双修（主会话交叉验证抓获）：values-uk 撇号转义（mergeResources 失败，i18n 脚本盲区实证）+ WorkspaceViewModelTest 21 构造点补参；PTY 入口留数据层兜底（ChatScreen 禁编辑）。

**E2E 首跑六段**：①添加服务器 PASS ②连接列表 PASS（未读红点工作）③历史 PARTIAL（会话B 全渲染含工具卡+思考时长；会话A 因 llm/failover 拒绝重建空白）④发消息 **FAIL（P0：payload 缺 mode，zod 整单拒绝 2/2）** ⑤中断 BLOCKED（依赖④）⑥断连重连对账 **PASS**（--remove 不杀已建连接，kill-server 才真断；~14s 检测/退避曲线/恢复 ~3s；对账 1 动作=1 真实缺口无 off-by-one）。六待定音：approval 未遇/无 off-by-one/键名兼容/create 回显 OK/content 形态正确/0 FATAL。

**四缺陷根治**（全量 2326/0 复绿）：P0 session.prompt 增 mode:"queue"（源码实证 queue→send/steer→steer）；P1a llm/failover 等 12 型收编+未知型 ignorable:true 旗标权威化（曾致整会话拒绝重建）；P1b assistant/message 内 tool-call/tool-result 块=事件对冗余镜像静默（1192 告警清零）；P2a 活体 title 投影=裸字符串 364/378（fixture 误设对象）→双读；P2b chat_empty 中性化×15 语言。情报收编：chunk tool-call-delta/finish；/ 开头单文本块=斜杠命令通道；反向隧道语义。
### 2026-08-31（三）：#276 核心接线（commit 2a9e76ef，39 文件 +2391/-90，新增 47 测试，全量 2326/0 ×2+主会话复验 1m04s 绿）

- **ServerType 零迁移**：@Serializable enum + ServerConfig/ServerConnection 默认 OpenCode + ServerConnection.from(config) data 层单点（四 repo resolveConnection 换用）+ DataStore 透传。
- **能力位**：of(serverType, apiVersion) DSH 五位全 false；缺口清单（PTY/文件读/删除/vcs/文件搜索/命令——无对应位）留 UI 卡。
- **DshApiClient 七域**：DshSessionMapper（list 字段映射，created 置 0）；历史适配=session.history→Folder.fold→**DshMessageAssembler**（建壳/挂载/拆壳）→MessagePage（nextCursor=页内最小 seq）；respond 三方法→/api/respond；Unsupported/常量降级对齐 V1 先例。
- **编排**：挂点 SseConnectionManager DSH 分支（复用 preLoadSessions 基线）；DshConnectionOrchestrator 帧→Channel 串行→mapper→processEvent；subscribed 400ms 静默窗成批→Reconciler→向旧翻页回填（InitialFetch 只取尾页对齐 prefetch 语义；500 页护栏；refusedRebuild 推水位防风暴）；Vanished→SessionDeleted。SessionUpdated 防御=合并缓存（零 RPC）。onFrame rpcId 三参扩展+metadata["rpcId"] 逃生口。每服务器独立 WS 引擎，水位跨重连存活。
- **E2E 待定音六项**（均已留逃生口）：approval respond 键、reconciler off-by-one/beforeSeq 含排他、history 响应键名（entries/events 双读）、create/rename 回显、prompt part 形态、respond outcome 词汇。

### 2026-08-31（二）：#275 事件层（commit c783abfd，7 文件 +1670 行）

- **DshEventMapper**（28 测试）：mapFrame→{Sse/Subscribed/Ignored}；mapSessionEvent 历史与实况同路径；Tier1 全映射 + ~35 高频伴生型具名忽略 + 未知→UNKNOWN_UNIGNORABLE；畸形降级不抛。
- **DshHistoryFolder**（11 测试）：header/chunk/seq0 打包行三筛保序；lastSeq 含打包行（防对账误判）；HistoryEntry 解包；refusedRebuild 判据。
- **DshReconciler**（6 测试）：缺口回填/无缺口/会话消失/新会话四类确定性排序，pageSize 参数化。
- **关键裁决**：工具卡宿主 id=`dsh-call-{callId}`（跨事件唯一连接键）；实况流式宿主 `dsh-t{turn}s{step}` 由整装 MessageRemoved 桥拆除（live→history 收敛）；block-end→Ignored（空载荷终态会经 isTerminal 清空流式文本）；partId 全委托 PartIdContract（#230/#266 承重逻辑零改动复用）。
- 验证：2279/0（主会话复跑 1 红 flakes→#277 卡登记：UncaughtExceptionsBeforeTest 跨类污染 ~14%，7 跑 1 红，定向复现绿；后续 6 连绿）。
- **#276 接线注意**（代理flagged）：①DshWsEventClient.onFrame 需透传 rpcId（question 回程路由依赖）；②SessionUpdated 是整对象替换，最小 Session 会抹 directory/time——须 session.list 再基线或合并；③reconciler off-by-one（baseline==local+1）与 beforeSeq 含/排他待 E2E 定音。

### 2026-08-31：探针收官 + #274 基础层（commit b73ae5de）

**探针**（P-1..P-4 全实证，双源交叉验证，详见 docs/specs/2026-08-31-dsh-integration-design.md §1.5-§1.7/§5）：52 方法四象限信封（非 JSON-RPC 2.0）、业务错恒 200+39 码闭集、WS 纯下行（GET→426、无服务端心跳、退避 500ms×2ⁿ cap10s）、重连无游标（subscribed lastSeq 基线 + history beforeSeq 回填）、特权面只认 Host 头 loopback（adb reverse 按构造全过）、事件 49 型开放联合 + seq0 打包行、trustedHosts=client-connection 插件 Config。设计文档随探针滚动定稿（帧词汇表/七域映射/九组件 TDD 顺序/E2E 计划），fixture 三件入 app/src/test/resources/dsh/。

**#274 基础层**（实现代理 f09412f1，TDD 先红后绿）：
- ① DshEnvelope（13 测试）：四象限 sealed 信封 + DshRpcResult + DshRpcErrorCode value class（39 码+未知容错）；ServerRequest 强校验 payload.type==method。
- ② DshApiError（6 测试）：39 码表驱动 → DshErrorCategory 七类；httpStatus 搬运层映射。
- ③ DshRpcClient（9 测试 MockEngine）：method 同现 URL+body、无 Origin/auth、200→信封/非200→httpStatus/IO→Network；respond 回程。
- ④ DshWsEventClient（11 测试虚拟时钟）：双 WS 只收不发、pingInterval 25s、双流独立退避、聚合取最差、start/stop 幂等。
- 验证：全量 2234 tests / 0 failures（主会话独立复跑 BUILD SUCCESSFUL 1m05s）；零既有文件改动。
- 坑位记录：无 mockwebserver→注入缝+虚拟时钟（真实 WS 留 E2E）；Ktor3.5 MockEngine Content-Type 在 body content；OkHttp ws:// 规范化存储为 http:// 字符串。

### 2026-08-30：DSH 接入可行性调研（两个调研子代理 + 合成）

**执行内容**：

1. 调研子代理一（DSH 侧）：源码级实证 DSH 0.1.1-rc.2 对外接口面与安全控制 → 研究文档 §4-§5 定稿（JSON-RPC `POST /api/<method>` + `/api/respond` + events.mux/host 双 WS + session.export；无鉴权四道栅栏）。
2. 调研子代理二（oc-beacon 侧，代理 767645d7）：全仓源码只读盘点 → 能力—接口依赖清单 ~32KB（~70 REST 调用形态、SSE 事件→handler 映射、6 能力域、38 项 mutation），全文原样归档本文件附录 A。
3. 合成（本步骤）：研究文档 §3（能力盘点蒸馏 + 38 项 mutation 浓缩表 §3.7）、§6（差距矩阵 50 行 + 判定统计 §6.7）填充定稿；仅改研究文档与本 journal 两个文件，backlog.md 未动。

**用户裁决（拷问轮 11-15 题）**：

- **原生接入**（非 WebView）——浏览器侧 crypto polyfill 路线作废（该问题在原生接入下不存在）。
- **不分期**——先全面盘点既有功能，再对照 DSH 出差距（防止按 DSH 现有能力反推需求造成锚定）。
- **服务器类型抽象**——ServerConfig 增服务器类型 opencodeV1 / opencodeV2 / dsh（暂定名），底层各自实现、对上层透明。
- **安全让渡拓扑**——DSH 无鉴权是事实，不在客户端造鉴权幻觉；部署规范默认 USB adb reverse，LAN 直连须 trustedHosts + 文档警示（研究文档 §5）。

**产出与统计**：

- 研究文档 §6 差距矩阵 50 行，判定统计：**同构 7 / 需适配 19 / 缺失 8 / 特权面受限 3 / 待探针 13**（明细见研究文档 §6.7）。
- 最大成本项确认：DSH 历史无消息端点 → 客户端 fold 48 种 SessionEvent（研究文档 R5）。
- 后续：探针 P-1..P-4（研究文档 §8）；差距矩阵 → 需求拆卡（服务器类型抽象 = 结构级先行，DI/NetworkModule 分派）。

## 证据归档

本批次为纯调研（未改代码），无验证截图/日志类证据；过程证据以研究文档（源码级 `文件:行号` 引用）与本文件附录 A（全量清单归档）为准。

### 调研完结（2026-08-30，#268 关单轮）

- §3 能力盘点 + §6 差距矩阵由合成代理 51d9057f 填充完成（50 行判定：同构 7 / 需适配 19 / 缺失 8 / 特权面受限 3 / 待探针 13），研究文档状态行「调研主体完成」
- 卡片 #268 按交付关闭（调研 Scope 已交付）；后续实现工作立新卡 **#269**（探针 P-1..P-4 先行 → 按差距矩阵拆需求）
- handoff 时点注记：/tmp/handoff-backlog-triage-20260830.md 所记「#268 §3 占位需决策重派」已被本节取代（无需重派）


## 附录 A：oc-beacon 能力—接口依赖清单（全量）

> 源：`/tmp/ocbeacon-feature-inventory.md`（/tmp 临时文件不持久，故全文原样归档于此）。生成：调研子代理 767645d7，2026-08-30；归档动作：同日合成步骤。以下为原文，未作任何改动。

# OC Beacon 对 OpenCode 服务器的能力—接口依赖清单（DSH 接入差距分析 · 前一半）

> 调研方式：源码优先（只读，未修改仓库）；文档仅作建图。仓库 @ /home/leo-tkp/Documents/code/mine/oc-beacon。
> 路径缩写：`api/` = `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/`；`repo/` = `.../data/repository/`；`ui/` = `.../ui/`。
> 「V1」= opencode 1.x（无 /api 前缀）；「V2」= opencode 2.x（/api 前缀 + data 信封）。行号为主文件当前工作区行号。

## 0. 架构地图（30 秒版）

- 分发模式：域接口（SessionApi/MessageApi/SystemApi/FileApi/TerminalApi/ProviderApi/ShellApi）→ `*ApiImpl` 单点 `pick(conn)` 路由 → `V1ApiClient` / `V2ApiClient` 直接实现全部 7 个接口（api/session/SessionApi.kt:118-125、api/message/MessageApi.kt:122-129、api/system/SystemApi.kt:51-58）。
- 版本探测：`ApiVersionDetector` 按 knownVersion 排序双探 `GET /api/health` / `GET /global/health`，version 2.x 或 pid 字段交叉验证（api/version/ApiVersionDetector.kt:59-128）；探测结果持久化进 ServerConfig（repo/ServerDataStore.kt:138-180）。
- SSE：V1 `GET /global/event`（api/SseClient.kt:169，{directory,payload:{type,properties}} 信封）；V2 `GET /api/event`（api/v2/SseClientV2.kt:104，单行 JSON 平铺 + seq gap 检测）。双客户端共用 7 个 V1 事件解析器（SseClient.kt:148-156）+ V2 前置 mapper/parser。
- 事件分发：`EventDispatcher` 注册表 O(1) 路由 → 7 个 handler + SessionStateService FSM（repo/EventDispatcher.kt:90-145）。
- 终端：WebSocket PTY（`GET /pty/{id}/connect`）；后台 shell（V2 专属 REST + SSE）。

---

## 1. REST 端点封装全表（客户端实际调用的全部端点）

### 1.1 Session 域（SessionApi 接口 api/session/SessionApi.kt:18-110）

| 能力 | V1 端点 | V2 端点 | 证据 |
|---|---|---|---|
| 会话列表（search/cursor/limit） | GET /session?roots=true&search&cursor&limit | GET /api/session?search&cursor&limit | V1ApiClient.kt:110-119（HTML 防御 :118）；V2ApiClient.kt:149-158 |
| 会话详情（含原始 JSON 导出） | GET /session/{id} | GET /api/session/{id} | V1ApiClient.kt:123/129；V2ApiClient.kt:162/171 |
| 创建会话（title/parentID） | POST /session | POST /api/session（+location.directory） | V1ApiClient.kt:144-149；V2ApiClient.kt:192-201 |
| 删除会话 | DELETE /session/{id} | DELETE /api/session/{id}（2026-08-15 勘误：实测支持） | V1ApiClient.kt:153-157；V2ApiClient.kt:203-211 |
| 重命名 | PATCH /session/{id} {title} | POST /api/session/{id}/rename | V1ApiClient.kt:160-165；V2ApiClient.kt:213-222 |
| 任意字段 PATCH（归档等） | PATCH /session/{id} | V2：仅 title 生效，其余退化为 getSession | V1ApiClient.kt:167-177；V2ApiClient.kt:1052-1060 |
| 中断执行 | POST /session/{id}/abort | POST /api/session/{id}/interrupt | V1ApiClient.kt:179-185；V2ApiClient.kt:225-231 |
| 会话文件 diff | GET /session/{id}/diff | V2 无 → 空列表 | V1ApiClient.kt:187-191；V2ApiClient.kt:1062-1064 |
| 分享/取消分享 | POST、DELETE /session/{id}/share | V2 无 → no-op 返回原会话 | V1ApiClient.kt:193-203；V2ApiClient.kt:1066-1072 |
| 压缩（summarize/compact） | POST /session/{id}/summarize | POST /api/session/{id}/compact | V1ApiClient.kt:205-217；V2ApiClient.kt:1074-1086 |
| 撤销 revert | POST /session/{id}/revert {messageID} | POST /api/session/{id}/revert/stage（只 stage 不 commit，对齐官方） | V1ApiClient.kt:219-225；V2ApiClient.kt:1088-1103 |
| 重做 unrevert | POST /session/{id}/unrevert | POST /api/session/{id}/revert/clear | V1ApiClient.kt:227-231；V2ApiClient.kt:1105-1110 |
| fork 分叉 | POST /session/{id}/fork | POST /api/session/{id}/fork（非 2xx 抛异常防幽灵会话） | V1ApiClient.kt:233-242；V2ApiClient.kt:1112-1130 |
| 导入会话 | POST /session/import {url} | POST /api/session/import | V1ApiClient.kt:244-250；V2ApiClient.kt:1132-1141 |
| 斜杠命令执行 | POST /session/{id}/command | POST /api/session/{id}/command | V1ApiClient.kt:269-276；V2ApiClient.kt:1160-1167 |
| 子会话列表 | GET /session/{id}/children | V2 无 → listSessions 客户端过滤 parentId | V1ApiClient.kt:278-282；V2ApiClient.kt:1169-1172 |
| Todo 列表 | GET /session/{id}/todo | GET /api/session/{id}/todo（beta-17639 404 → TodoEndpointMissingException 按 baseUrl 记忆） | V1ApiClient.kt:284-288；V2ApiClient.kt:1184-1196 |
| 后台化（批量转后台） | V1 恒 false | POST /api/session/{id}/background | V1ApiClient.kt:319；V2ApiClient.kt:291-296 |
| 活跃会话查询 | V1 恒空 | GET /api/session/active | V1ApiClient.kt:322；V2ApiClient.kt:270-284 |
| 会话状态表 | GET /session/status | V2：listSessionStatus 空；fetchSessionStatus 用 /api/session/active | V1ApiClient.kt:290-316；V2ApiClient.kt:237-260/1198 |

### 1.2 Message 域（MessageApi 接口 api/message/MessageApi.kt:15-114）

| 能力 | V1 | V2 | 证据 |
|---|---|---|---|
| 消息分页 | GET /session/{id}/message?limit&before（X-Next-Cursor 响应头） | GET /api/session/{id}/message?limit&cursor（双向游标 next/previous） | V1ApiClient.kt:326-359；V2ApiClient.kt:402-439 |
| 会话导出（流式） | GET /session/{id} + /session/{id}/message（OkHttp 字节流） | 同构 + /api 前缀 | OpenCodeShared.kt:49-94（共享实现） |
| 单条消息 | GET /session/{id}/message/{mid} | 同构 | V1ApiClient.kt:380-384；V2ApiClient.kt:447-455 |
| 发送消息 | POST /session/{id}/prompt_async（204 fire-and-forget → admission=null 依赖 SSE 回显） | POST /api/session/{id}/prompt（200 Inbox；modern prompt 包裹 400 → legacy 平铺降级；files 平铺顶层；admission 双读 payload.text/prompt.text） | V1ApiClient.kt:386-411；V2ApiClient.kt:465-558 |
| （V2 发送前置）切模型 | V1 model 进 prompt body | POST /api/session/{id}/model {model:{id,providerID,variant}} | V1ApiClient.kt:399-404；V2ApiClient.kt:565-597 |
| （V2 发送前置）切 agent | V1 agent 进 prompt body | POST /api/session/{id}/agent {agent:id}（显示名→id 缓存解析） | V1ApiClient.kt:399-404；V2ApiClient.kt:609-673 |
| 删除消息 | DELETE /session/{id}/message/{mid} | 同构 | V1ApiClient.kt:413-418；V2ApiClient.kt:675-680 |
| 删除消息 part | DELETE /session/{id}/message/{mid}/part/{idx} | V2 无 → false | V1ApiClient.kt:420-425；V2ApiClient.kt:1251-1253 |
| 权限回复 | POST /permission/{id}/reply {reply,message} | POST /api/session/{会话id}/permission/{id}/reply（失败降级 legacy /api/permission/{id}/reply + effect 形态） | V1ApiClient.kt:428-447；V2ApiClient.kt:864-907 |
| 待处理权限 | GET /permission | GET /api/permission/request | V1ApiClient.kt:449-454；V2ApiClient.kt:1255-1263 |
| 问题回复 | POST /question/{id}/reply {answers:string[][]} | 优先 question.v2 POST /api/session/{sid}/question/{fid}/reply（404 按 baseUrl 记忆跳过）→ form POST /api/session/{sid}/form/{fid}/reply {answer:{key:value}} | V1ApiClient.kt:457-475；V2ApiClient.kt:923-978 |
| 问题拒绝 | POST /question/{id}/reject | POST /api/session/{sid}/question/{fid}/reject → form .../form/{fid}/cancel | V1ApiClient.kt:478-492；V2ApiClient.kt:984-1009 |
| 待处理问题 | GET /question | GET /api/form/request（kind=question 才映射） | V1ApiClient.kt:494-499；V2ApiClient.kt:1271-1278 |

### 1.3 System 域（SystemApi 接口 api/system/SystemApi.kt:11-44）

| 能力 | V1 | V2 | 证据 |
|---|---|---|---|
| 健康检查 | GET /global/health | GET /api/health | V1ApiClient.kt:503-507；V2ApiClient.kt:129-138 |
| 服务器路径 | GET /path | GET /api/location（仅 directory，降级为 home） | V1ApiClient.kt:509-513；V2ApiClient.kt:1282-1297 |
| Agent 列表 | GET /agent | GET /api/agent | V1ApiClient.kt:515-519；V2ApiClient.kt:684-699 |
| 斜杠命令列表 | GET /command | GET /api/command | V1ApiClient.kt:521-525；V2ApiClient.kt:701-714 |
| Skill 列表 | GET /skill | GET /api/skill | V1ApiClient.kt:527-532；V2ApiClient.kt:716-730 |
| MCP 状态 | GET /mcp | GET /api/mcp（{location,data:[…]} 契约） | V1ApiClient.kt:534-538；V2ApiClient.kt:1299-1315 |
| MCP 连接/断开 | POST /mcp/{name}/connect、/disconnect | 同构 + /api | V1ApiClient.kt:540-550；V2ApiClient.kt:732-744 |

### 1.4 File/VCS 域（FileApi 接口 api/file/FileApi.kt:11-54）

| 能力 | V1 | V2 | 证据 |
|---|---|---|---|
| 文件名搜索（@ 弹窗/workspace 搜索） | GET /find/file?query&type&limit&dirs；空结果回退 GET /file?path= + 客户端过滤（#248） | GET /api/fs/find?query（信封 {path,type} 对象，id 优先 path 回退 #249） | V1ApiClient.kt:712-743；V2ApiClient.kt:1453-1484 |
| 读文件 | GET /file/content?path= | GET /api/fs/read/{通配符路径}（裸文本兜底） | V1ApiClient.kt:745-751；V2ApiClient.kt:1486-1509 |
| 文本搜索 | GET /find?pattern= | GET /api/fs/find?pattern= | V1ApiClient.kt:753-758；V2ApiClient.kt:1511-1519 |
| 目录探测 | GET /file?path= 2xx 判定 | GET /api/fs/list?path= | V1ApiClient.kt:760-767；V2ApiClient.kt:1521-1528 |
| 目录列表 | GET /file?path | GET /api/fs/list?path（{path,type} → name/absolute 客户端推导） | V1ApiClient.kt:769-779；V2ApiClient.kt:1530-1563 |
| 符号搜索 | GET /find/symbol | V2 无 → 空 | V1ApiClient.kt:781-787；V2ApiClient.kt:1565-1567 |
| 文件 git 状态 | GET /file/status | GET /api/vcs/status | V1ApiClient.kt:789-794；V2ApiClient.kt:1569-1577 |
| VCS 分支 | GET /vcs（branch 对象防御） | GET /api/vcs | V1ApiClient.kt:796-801；V2ApiClient.kt:1579-1595 |
| VCS 变更 | GET /vcs/status | GET /api/vcs/status | V1ApiClient.kt:803-808；V2ApiClient.kt:1597-1605 |
| VCS diff | GET /vcs/diff?mode&context | 同构 + /api | V1ApiClient.kt:810-817；V2ApiClient.kt:1607-1617 |
| 项目列表/当前项目 | GET /project、/project/current | 同构 + /api | V1ApiClient.kt:819-829；V2ApiClient.kt:1619-1634 |

### 1.5 Terminal/PTY 域（TerminalApi 接口 api/terminal/TerminalApi.kt:12-51）

| 能力 | V1 | V2 | 证据 |
|---|---|---|---|
| 创建 PTY | POST /pty（响应三形态容错解析） | POST /api/pty | V1ApiClient.kt:833-861；V2ApiClient.kt:1638-1666；OpenCodeShared.kt:102-156 |
| 删除 PTY | DELETE /pty/{id} | 同构 + /api | V1ApiClient.kt:863-868；V2ApiClient.kt:1668-1673 |
| PTY resize | PUT /pty/{id} {size:{rows,cols}} | 同构 + /api | V1ApiClient.kt:870-893；V2ApiClient.kt:1675-1698 |
| PTY 数据流 | WS /pty/{id}/connect?cursor | WS /api/pty/{id}/connect | V1ApiClient.kt:895-913；V2ApiClient.kt:1700-1718 |
| PTY 列表 | GET /pty/shells | GET /api/pty（实测 /api/pty/shells 是错误路径） | V1ApiClient.kt:915-920；V2ApiClient.kt:1720-1732 |
| 会话级 shell 命令 | POST /session/{id}/shell（同步语义：10min 客户端超时 + 409 退避重试 3 次 #256） | POST /api/session/{id}/shell（异步后台 shell 体系） | V1ApiClient.kt:922-967；V2ApiClient.kt:1734-1755 |

### 1.6 Provider/Config 域（ProviderApi 接口 api/provider/ProviderApi.kt:11-98）

| 能力 | V1 | V2 | 证据 |
|---|---|---|---|
| Provider+模型目录 | GET /config/providers | 合成：GET /api/provider + GET /api/model（客户端按 providerID 分组、variants 数组转 map、cost 数组取首条） | V1ApiClient.kt:582-586；V2ApiClient.kt:748-860 |
| Provider 目录（含 connected） | GET /provider | 复用合成结果（connected 恒空） | V1ApiClient.kt:588-592；V2ApiClient.kt:1319-1326 |
| 认证方式 | GET /provider/auth | V2 空 map（integration 端点未适配，#84） | V1ApiClient.kt:594-598；V2ApiClient.kt:1328-1330 |
| OAuth 发起 | POST /provider/{id}/oauth/authorize | V2 null（未适配） | V1ApiClient.kt:600-624；V2ApiClient.kt:1332-1338 |
| OAuth 回调 | POST /provider/{id}/oauth/callback | POST /api/provider/{id}/oauth/callback | V1ApiClient.kt:626-645；V2ApiClient.kt:1340-1359 |
| 设置 API key | PUT /auth/{id} {type:api,key} | PATCH /api/credential/{id}（label 必填，补 "oc-beacon"） | V1ApiClient.kt:647-654；V2ApiClient.kt:1361-1372 |
| 移除凭据 | DELETE /auth/{id} | DELETE /api/credential/{id} | V1ApiClient.kt:656-666；V2ApiClient.kt:1374-1384 |
| 读取配置 | GET /config、GET /global/config | GET /api/config（裸数组 [{type:document,path,info}] 取 info） | V1ApiClient.kt:668-678；V2ApiClient.kt:1386-1398/1423-1435 |
| 写配置 | PATCH /config、PATCH /global/config | PATCH /api/config（V2 文档只读——实现仍发 PATCH；UI 侧 V2 禁用状态未确证，见 §7） | V1ApiClient.kt:680-694；V2ApiClient.kt:1400-1416 |
| 销毁全局/实例 | POST /global/dispose、POST /instance/dispose | 二者同为 POST /api/service/stop | V1ApiClient.kt:696-708；V2ApiClient.kt:1437-1449 |

### 1.7 后台 Shell 域（ShellApi 接口 api/shell/ShellApi.kt:20-34，V2 专属；V1 常量降级 api/v1/V1ApiClient.kt:552-578）

| 能力 | V2 端点 | 证据 |
|---|---|---|
| 运行中 shell 列表 | GET /api/shell | V2ApiClient.kt:302-311 |
| 单个 shell | GET /api/shell/{id} | V2ApiClient.kt:316-329 |
| 分页输出 | GET /api/shell/{id}/output?cursor&limit | V2ApiClient.kt:335-357 |
| 终止删除 | DELETE /api/shell/{id} | V2ApiClient.kt:362-372 |
| 超时更新（无 UI 消费方，dormant） | PATCH /api/shell/{id}/timeout | V2ApiClient.kt:378-394 |

### 1.8 SSE / WebSocket / 探测

| 通道 | 端点 | 证据 |
|---|---|---|
| V1 事件流 | GET /global/event（40s 心跳超时、512KB 行上限、1MB 事件上限、读超时冷却） | api/SseClient.kt:25-29/168-259/299-351 |
| V2 事件流 | GET /api/event（单行 JSON 信封 + event:/data: 兼容帧；seq 严格递增 gap 检测） | api/v2/SseClientV2.kt:43-58/99-104/270-332 |
| PTY 数据 | WS /pty/{id}/connect（V1/V2 同构） | §1.5 |
| 版本探测 | GET /api/health → GET /global/health（knownVersion 排序；content-type 必须 JSON；version 2.x 或 pid 字段判 V2；全败 UNKNOWN 保留原版本） | api/version/ApiVersionDetector.kt:59-154；repo/ServerDataStore.kt:138-180 |

---

## 2. SSE 事件消费全表

### 2.1 V1 线格式事件（7 个解析器 → 领域事件；解析器清单 api/SseClient.kt:148-156）

| 线格式事件 | 领域事件 | 注册 handler | 驱动行为 | 证据 |
|---|---|---|---|---|
| server.connected / server.heartbeat / server.instance.disposed | ServerConnected / ServerHeartbeat / ServerInstanceDisposed | SessionEventHandler | 连接确认/存活刷新/全局清理 | repo/handler/MiscEventParser.kt:22-38；repo/handler/SessionEventHandler.kt:62-65；EventDispatcher.kt:96-105 |
| session.created / updated / deleted | SessionCreated/Updated/Deleted | SessionEventHandler | 会话缓存 upsert；Deleted 级联清理 8 个 handler + 堆积队列 + 红点条目 | SessionEventHandler.kt:67-69；EventDispatcher.kt:272-287 |
| session.status / session.idle | SessionStatus / SessionIdle | SessionStateService FSM | 会话 busy/idle/retry 状态机输入 | SessionStateService.kt:362-363 |
| session.error | SessionError | SessionEventHandler + FSM + UnreadBadgeService | 错误态 + 产生未读红点（客户端时刻例外） | EventDispatcher.kt:74-79/349 |
| session.diff | SessionDiff | SessionEventHandler | 会话文件 diff 缓存（sessionDiffs） | SessionEventHandler.kt:74；EventDispatcher.kt:163 |
| session.compacted | SessionCompacted | SessionEventHandler + SessionNextHandler | 压缩完成，终结压缩横幅 | SessionEventHandler.kt:76；EventDispatcher.kt:293-295 |
| vcs.branch.updated / project.updated | VcsBranchUpdated / ProjectUpdated | SessionEventHandler | git 面板分支/项目信息 | SessionEventHandler.kt:86-87 |
| message.updated | MessageUpdated | MessageEventHandler | 消息 upsert；assistant completed → 红点水位线；user → 会话排序时间戳 | MessageEventHandler.kt:45；EventDispatcher.kt:313-322 |
| message.removed | MessageRemoved | MessageEventHandler | 消息删除（revert 配套） | MessageEventHandler.kt:46 |
| message.part.updated / part.removed | MessagePartUpdated / Removed | MessageEventHandler | part 快照 merge；updated 兼作 FSM TextStarted | MessageEventHandler.kt:47/49；SessionStateService.kt:376 |
| message.part.delta | MessagePartDelta | MessageEventHandler + FSM | 流式 token 追加（48ms 批处理渲染管线上游）；FSM TextDelta | MessageEventHandler.kt:48；SessionStateService.kt:375 |
| permission.asked / replied | PermissionAsked / Replied | PermissionEventHandler + PermissionAutoApprover | 权限卡注入/清除；自动批准规则触发（规则空=关闭） | repo/handler/PermissionEventHandler.kt:31-36；EventDispatcher.kt:252-254 |
| question.asked / replied / rejected | QuestionAsked / Replied / Rejected | QuestionEventHandler | 提问卡（V2 form 映射同构）注入/清除 | repo/handler/QuestionEventHandler.kt:24-26 |
| todo.updated | TodoUpdated | MiscEventHandler | Todo 面板数据（+REST hydrate 回填） | MiscEventHandler.kt:35；EventDispatcher.kt:154-160 |
| pty.created / updated / deleted | PtyCreated/Updated/Deleted | MiscEventHandler | 终端标签页生命周期（仅 debug 日志，UI 以本地状态为主） | MiscEventHandler.kt:36-38 |
| command.executed | CommandExecuted | MiscEventHandler + MessageEventHandler | 按 messageId 精确终结该消息（不强制会话 Idle，防闪烁） | MiscEventHandler.kt:43；EventDispatcher.kt:297-309 |
| file.edited / file.watcher.updated | FileEdited / FileWatcherUpdated | MiscEventHandler | 文件变更通知（debug 日志级） | MiscEventHandler.kt:41/48 |
| mcp.tools.changed | McpToolsChanged | MiscEventHandler | MCP 工具缓存失效信号 | MiscEventHandler.kt:42 |
| installation.updated / update_available | InstallationUpdated / UpdateAvailable | MiscEventHandler | 服务器版本更新提示（日志级） | MiscEventHandler.kt:49-50 |
| workspace.ready/failed、worktree.ready/failed | 同名 | MiscEventHandler | 实验性 workspace/worktree 生命周期（日志级） | MiscEventHandler.kt:39-40/51-52 |
| lsp.updated | LspUpdated | MiscEventHandler | 移动端无消费（显式忽略） | MiscEventHandler.kt:53 |
| session.next.*（26 个子类型） | SessionNext(SessionNextEvent) | SessionNextEventHandler + FSM | 细粒度流式状态：agent/model switched、text/reasoning started/delta/ended、tool input/called/progress/success/failed、step started/ended/failed、shell started/ended、compaction started/delta/ended、prompted、retried（重试横幅）、usage.updated（token 用量）、synthetic（后台任务合成消息）、moved（会话目录迁移） | api/sse/parsers/SessionNextEventParser.kt:17-19；domain/model/SessionNextEvent.kt:20-46；EventDispatcher.kt:175-189/256-269 |

### 2.2 V2 线格式事件（V2 前置映射后进同一管线）

| V2 线格式事件 | 映射产物 | 证据 |
|---|---|---|
| session.inbox.enqueued / session.input.admitted | 消息事件（Inbox 条目→MessageUpdated 链） | api/v2/V2SseMapper.kt:62 |
| session.step.started/ended、session.reasoning.*、session.text.*、session.tool.input.*、session.tool.called、session.tool.success/failed | 对应 V1 领域事件（delta/part/tool 卡） | V2SseMapper.kt:112-332 |
| session.execution.started/succeeded | FSM busy/idle 输入 | api/v2/V2EventParser.kt:66-75 |
| session.shell.started / shell.created；session.shell.ended / shell.exited / shell.deleted | ShellJobStarted/Ended → ShellJobsHandler（后台 shell 卡 + 通知） | V2EventParser.kt:78-108；repo/handler/ShellJobsHandler.kt:30-35 |
| session.compaction.started/failed/ended/delta、session.compacted | CompactionState + SessionCompacted（压缩横幅/失败 snackbar #219） | V2EventParser.kt:111-179 |
| session.usage.updated | SessionNextEvent.UsageUpdated（实时 token/成本环） | V2EventParser.kt:181-208；EventDispatcher.kt:184 |
| session.tool.progress | ToolProgress + 跨 handler 子会话 id 回写主消息流（#216） | V2EventParser.kt:212-227；EventDispatcher.kt:261-269 |
| form.created(kind=question) | QuestionAsked（复用提问卡） | api/v2/SseClientV2.kt:419-425 |

---

## 3. UI 能力域盘点（能力 | 用户可见功能 | REST | SSE | 本地依赖 | V1/V2 注记）

### 3.1 服务器管理

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 服务器增删改 | 添加/编辑/删除服务器卡片（URL/用户名/密码） | —（纯本地） | — | ServerDataStore（DataStore JSON 列表，ServerDataStore.kt:63-136） | 三 flavor 共存；认证均为 Basic |
| 连接/断开 | 点击连接 → 前台服务保活；测试连通 | GET /global/health 或 /api/health（探测）；GET /file（目录探测 probeDirectory） | server.connected/heartbeat（SSE 存活） | ServerConfig（apiVersion/serverVersion 持久化） | 探测双路径 + knownVersion 排序（ApiVersionDetector.kt:65-69）；UNKNOWN 不降级已存版本 |
| 连接生命周期 | FGS 通知、断线重连（指数退避 1s→30s ×2.0）、重连后断连窗口内容对账 | fetchSessionStatus（/session/status、/api/session/active） | 全量重订阅 | StreamingOwnershipRegistry | SseConnectionManager.kt:34-36/359；5 次连续读超时进入 5min 冷却（SseClient.kt:299-351） |
| 事件通知 | 会话完成/错误/权限系统通知；活跃会话抑制 | — | MessageUpdated(completed)、SessionError、PermissionAsked、ShellJobStarted/Ended | AppNotificationManager/SessionNotificationCoordinator | — |

### 3.2 会话列表

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 会话加载 | 多项目聚合列表 + 下拉刷新 + 分页 | GET /project × GET /session（V2 /api/*）；search 参数服务端过滤（SessionListViewModel.kt:591/598） | session.created/updated/deleted 实时增删 | EventDispatcher.sessions 内存缓存 | V2 无 /project/current 语义差异（实测字段 canonical） |
| 搜索 | 搜索栏（300ms 防抖，服务端 search + 客户端过滤双层） | listSessions(search) | — | — | #100 |
| 收藏/标签/分类 | 收藏星标、多标签（颜色+图标）、分类筛选（category filter） | — | — | SessionTagStore（DataStore，按 serverId 隔离，SessionTagStore.kt:46-47） | 「置顶」无独立实现（grep 0 命中）——收藏+标签+分类承担近似职能 |
| 状态显示 | idle/busy/retry/流式活动（Waiting/Streaming/ToolCalling）、子会话树 | fetchSessionStatus（REST 恢复循环） | session.status/idle/error、session.next.*（execution/tool/step） | SessionStateService FSM（单一真相源）+ 24h staleness 清扫 | V1 状态源 /session/status；V2 靠 /api/session/active + SSE 替代 |
| 未读红点 | 红点=Idle 且 maxCompleted>已读；全部已读按钮 | — | MessageUpdated(assistant completed)、SessionError | UnreadStateStore（DataStore 水位线，同步落盘）+ UnreadBadgeService | 服务器时钟域；maxCompleted 只增不减（架构铁律） |
| 目录树/项目 | 按目录分组树形列表、目录展开记忆、base 目录切换、打开项目对话框 | listProjects、listDirectory（目录浏览） | — | 展开路径内存态 | — |
| 会话操作 | 删除（含批量）、重命名、导入（分享 URL） | DELETE /session/{id}、PATCH /session/{id}、POST /session/import | SessionDeleted 级联 | 红点条目清理 | V2 删除实测支持；rename 走 /api/session/{id}/rename |
| MCP 管理 | MCP 服务器状态列表 + 连接/断开开关 | GET /mcp、POST /mcp/{name}/connect|disconnect | mcp.tools.changed | — | V2 同构（/api/mcp） |
| 堆积消息 | 断线期间发送排队 + 详情对话框手动「继续」 | prompt（恢复后重发） | — | PendingMessageEntity（Room v4） | PendingMessageRepositoryImpl.kt:21 |
| 跨服务器收藏入口 | Screen 路由 cross_favorites 存在 | — | — | — | 架构文档提及的 CrossServerSessionsScreen 已不在源码树（glob 0 命中）——文档滞后，路由为死项【推测：已移除】 |

### 3.3 聊天（核心域）

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 发送消息 | 文本+附件（图片 data:URI 内嵌、@file 引用）；发送中转圈；失败退回输入框 | V1 POST /session/{id}/prompt_async；V2 POST /api/session/{id}/prompt（modern→legacy 降级）+ 发送前 switchModel/switchAgent | 悲观渲染：等 SSE 回显 MessageUpdated 才显示 | DraftDataStore（草稿按会话持久） | V1 204 无 admission；V2 200 Inbox admission 双契约解析（V2ApiClient.kt:544-553）；V2 附件平铺顶层 files（部署版实测） |
| 流式渲染 | token 增量渲染（48ms 批处理+高度补偿铁律）、reasoning 折叠块 | — | message.part.delta / part.updated（V2 session.text/reasoning.delta、session.next.text/reasoning.*） | MessageEventHandler 内存 parts 缓存 + Room 回读种子 | 双版本同构管线 |
| 中断 | 发送按钮→停止按钮 | POST /session/{id}/abort（V2 /api/.../interrupt） | execution/session.status 事件回落 Idle | — | 端点不同已分流 |
| 撤销/重做（revert） | 消息级撤销 + RevertBanner + 撤回草稿提取 | V1 /revert+/unrevert；V2 /revert/stage（只 stage，下条消息隐式 commit）+/revert/clear | message.removed、session.updated(revert) | messageHandler.pruneRevertedMessages（清缓存防闪烁） | V2 staged 三步流程已适配（V2ApiClient.kt:1088-1110） |
| 压缩 compact | 手动压缩 + CompactionCard 横幅 + 失败 snackbar | V1 /summarize；V2 /compact | V1 SessionCompacted；V2 session.compaction.started/delta/ended/failed | SessionNextHandler.compactionState | 压缩中 FSM Compacting 态；auto-compaction 靠 SessionCompacted 收尾 |
| fork | 从当前会话分叉 | POST /session/{id}/fork | session.created | — | V2 端点存在但实测 400（服务器路由冲突）→ 明确报错（V2ApiClient.kt:1121-1127） |
| 分享/导出 | 生成分享 URL / 取消分享；导出 JSON 文件（进度通知） | POST、DELETE /session/{id}/share；导出=GET /session/{id}+message 流式 | — | — | V2 无 share → 隐藏（#78）；导出双版本同构 |
| 重试（显示） | 重试横幅 + 重试跳转卡（服务器驱动） | — | session.next.retried → retryState | — | 客户端不主动重发；纯服务器状态呈现 |
| 工具调用卡 | Bash/Edit/Write/Read/Task(子智能体)/Glob/Grep/WebFetch/WebSearch/ApplyPatch/TodoList/Shell 可展开卡、进度指示 | Task 输出经 TaskOutputFetch 拉取 | session.tool.*、session.next.tool.*、tool.progress（子会话 id 回写 #216） | ToolCacheDelegate/ToolSnapshotGrouper（文件快照） | — |
| 权限卡 | once/always/reject 三选 + 拒绝留言 + 失败复核保留 | POST /permission/{id}/reply（V2 会话级路径 + legacy 降级） | permission.asked/replied；REST hydrate GET /permission | PermissionEventHandler 内存 + PermissionAutoApprover 规则（本地） | V2 子智能体会话权限须传子会话 id（V2ApiClient.kt:872-874）；always 规则保存到本地自动批准 |
| 提问卡（question/form） | 多题多选/单选/自定义输入；跳过（reject） | V1 /question/{id}/reply、/reject；V2 question.v2 优先→form reply/cancel 降级 | question.asked/replied/rejected；V2 form.created(kind=question)；REST hydrate GET /question 或 /api/form/request | QuestionEventHandler 内存 + QuestionAnswerStore | #130/#250；答案构造两条契约（keyed map vs 有序 label 数组） |
| Todo 面板 | 会话 Todo 列表卡 | GET /session/{id}/todo（REST hydrate） | todo.updated | MiscEventHandler.todos | V2 beta 无端点（404 记忆跳过）→ 入口隐藏（#85） |
| 后台化 | 「转后台」操作 + TaskToolbar（subagent+shell 角标） | V2 POST /api/session/{id}/background；activeSessions 轮询 | session.next.synthetic（合成消息卡）、session.shell.started/ended | ShellJobsStore（内存）+ TaskDelegate 轮询 | V1 无 → isBackgroundSupported=false 隐藏（ChatScreen.kt:692） |
| 会话级 shell 命令 | shell 模式消息（产生聊天轮次卡） | POST /session/{id}/shell（V1 同步 10min 超时+409 退避；V2 异步） | 消息事件流呈现执行过程 | — | #250 ensureSession 修复 |
| 模型/agent/variant 选择 | ModelPickerDialog、AgentModelVariantSelector（variant 轮换） | V1 model/agent 进 prompt body；V2 switchModel/switchAgent 端点 + GET /api/agent 解析 | session.next.model/agent.switched | ModelConfigDelegate 本地默认值 | V2 契约为嵌套 {model:{...}}（实测 400 教训） |
| 上下文用量 | token 用量卡/上下文环/费用 | — | session.next.usage.updated（V2）；V1 快照推算 | ContextStats | V1 无实时用量事件【推测：V1 靠消息 tokens 字段】 |
| 斜杠命令 | / 命令建议（服务端 /command 列表 + 客户端命令） | GET /command；客户端命令映射 compact/fork/share 等 REST | — | SlashCommandRegistry（ui/screens/chat/util/SlashCommandRegistry.kt:27-29） | — |
| 消息删除 | 删除单条消息/part | DELETE /session/{id}/message/{mid}（part 删除 V1 专属） | message.removed | — | — |
| 历史分页 | 顶部加载更早消息（双向游标） | GET .../message?limit&before/cursor | — | MessageStore/Room 缓存（MessageCacheRepository，ChatRepositoryImpl.kt:67） | V2 cursor 需服务器格式（base64url {id,order,direction}） |
| 撤回消息草稿 | 撤销时把被撤内容提回输入框 | （随 revert 流程） | — | RevertedDraftPayload 内存 | — |

### 3.4 Workspace（文件/Git/终端）

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 文件树 | 目录懒加载树、展开/收起、刷新、忽略文件过滤、LRU 目录缓存 | GET /file?path（V2 /api/fs/list） | file.watcher.updated/file.edited（通知级） | WorkspaceViewModel LRU 缓存（WorkspaceViewModel.kt:46） | V2 {path,type} → name/absolute 推导（防 LazyColumn key 崩溃） |
| 文件查看 | 代码高亮/Markdown/PDF 渲染、大文件分片、diff/源码切换、hunk 导航、批注 | GET /file/content（V2 /api/fs/read/{通配符}）；diff GET /vcs/diff | — | AnnotationManager（本地批注）；ToolSnapshotCache（工具快照对比） | V2 读取端点形态实测修复（?path= 500 → 通配符 200） |
| 文件搜索 | workspace 搜索 overlay（文件名）；文本搜索 | GET /find/file（V2 /api/fs/find）；GET /find（V2 /api/fs/find?pattern） | — | — | V1 大目录空结果客户端回退 #248 |
| Git 面板 | 分支、变更列表（含未跟踪过滤开关）、diff 查看 | GET /vcs、/vcs/status、/vcs/diff（V2 /api/vcs*） | vcs.branch.updated | prefetchGitCount 内存 | — |
| 终端（PTY） | 多标签终端、软键盘条、字号、resize、缓冲清屏 | POST/DELETE/PUT /pty、GET /pty/shells（V2 /api/pty） | pty.created/updated/deleted | ServerTerminalRegistry（serverId→tabs）；TerminalTabState 5 态 | 数据面走 WS（非 SSE） |
| 后台 shell | 运行中命令卡（通知条）、历史/进行中切换、终止 | GET /api/shell*、DELETE /api/shell/{id} | session.shell.started/ended（shell.created/exited 旧名兼容） | ShellJobsStore | V2 专属；V1 常量降级 |

### 3.5 设置

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 服务器设置（模型/agent 默认） | 默认模型、small model、默认 agent、provider 启停 | PATCH /config 或 /global/config（V2 PATCH /api/config）；GET /config*、GET /provider/auth | — | ServerConfig（本地） | V2 配置写：官方文档只读——UI 侧是否禁用未在源码确证【推测：仍暴露，PATCH 会失败】（docs/v1-v2-differences.md #85 ⏳） |
| Provider 认证 | API key 连接、OAuth 两步流、断开（删凭据+dispose 刷新） | PUT /auth/{id}、/provider/{id}/oauth/authorize|callback、DELETE /auth/{id}、POST /global/dispose（V2 PATCH/DELETE /api/credential/{id}、/api/provider/{id}/oauth/callback、/api/service/stop） | — | — | V2 OAuth 发起未适配（authorizeProviderOauth=null，#84 ⏳）；V2 API key 带 label 实测 204 |
| 模型可见性过滤 | 按 provider/model 隐藏模型（聊天选择器过滤） | —（纯本地） | — | SettingsDataStore.hiddenModels(serverId)（ServerSettingsViewModel.kt:452-455） | ServerModelFilterScreen |
| 权限自动批准 | 规则列表（按工具名/模式）自动 once/always | （回复动作走 3.3 权限卡同一端点） | PermissionAsked → maybeAutoApprove | 规则存本地 SettingsDataStore | PermissionAutoApprover（规则空=关闭） |
| 本地偏好 | 主题/动态色/AMOLED、语言（15 语言）、字号/密度、通知开关/静音、初始消息数、最近目录数、图片压缩参数、终端字号、重连模式、保持屏幕、触感 | — | — | SettingsDataStore（键值见 SettingsDataStore.kt:33-41） | 纯客户端，无服务器依赖 |
| 更新检查 | 应用内 GitHub Release 检查（3 级回退） | GitHub API（非 opencode 服务器） | — | UpdateRepository | 与 opencode 服务器的 installation.updated 事件仅日志级 |

### 3.6 诊断

| 能力 | 用户可见功能 | REST 端点 | SSE 事件 | 本地依赖 | V1/V2 注记 |
|---|---|---|---|---|---|
| 应用内日志 | AppLogger 持久化日志查看/导出/清空/级别 | — | — | LogEntity（Room）+ Channel→SQLite | 服务器无关 |
| 错误上报 | GitHub Device Flow 授权 → 附件化上报 | github.com API（非 opencode） | — | github_report DataStore（GitHubTokenStore.kt:18） | — |
| SSE 观测 | 事件分发计数/gap 检测日志 | — | 全事件流 | gapDetected（SessionNextHandler） | V2 seq gap；V1 无 seq |

---

## 4. 本地依赖汇总（Room / DataStore / 内存）

| 存储 | 技术 | 内容 | 消费能力 | 证据 |
|---|---|---|---|---|
| ocbeacon.db v4 | Room（DatabaseModule.kt:26） | CachedMessage+CachedPart（消息缓存）、Log（诊断日志）、ArchiveBucket（冷存桶）、PendingMessage（堆积队列） | 聊天历史回读/分页、诊断、离线排队 | data/local/OcBeaconDatabase.kt:10-19；MessageStore.kt:33；PendingMessageRepositoryImpl.kt:21 |
| opencode_prefs | DataStore | 服务器配置列表、应用设置、红点水位线、会话标签、草稿 | 服务器管理/设置/红点/标签/草稿 | di/NetworkModule.kt:25；ServerDataStore.kt:32；SettingsDataStore.kt:28；UnreadStateStore.kt:34；SessionTagStore.kt:32；DraftDataStore.kt:25 |
| github_report | DataStore | GitHub 设备流 token | 错误上报 | data/github/GitHubTokenStore.kt:18 |
| 进程内存 | Singleton | EventDispatcher 七 handler 缓存（sessions/messages/parts/permissions/questions/todos/diffs/vcs/project）、SessionStateService FSM、ShellJobsStore、StreamingOwnershipRegistry、ServerTerminalRegistry、agentIdCache、404 能力记忆（question.v2/todo 端点） | 全部实时能力 | repo/EventDispatcher.kt:149-189；V2ApiClient.kt:633/641/1182 |

---

## 5. V1/V2 差异注记汇总（客户端分流点）

| 差异点 | V1 | V2 | 客户端处置 |
|---|---|---|---|
| 路径前缀/信封 | 无前缀，裸 JSON | /api + {data} 信封 | apiBase 抽象 + V2ResponseWrapper（OpenCodeShared.kt:14-22） |
| 健康探测 | /global/health（无认证） | /api/health（需认证，含 pid） | 双探 + pid/version 交叉验证 |
| SSE | /global/event {type,properties} | /api/event 平铺 JSON + seq | SseClient / SseClientV2 双实现 |
| 发消息 | prompt_async 204 | prompt 200 Inbox | admission 有无两条路径 |
| 中断 | abort | interrupt | 分流 |
| 重命名 | PATCH /session/{id} | POST .../rename | 分流 |
| 压缩 | summarize | compact | 分流 |
| revert | 一步 revert/unrevert | staged（stage→隐式 commit/clear） | 只 stage 对齐官方 |
| 权限 | 全局 /permission | 会话级 permission + /api/permission/request | 会话 id 路由 + legacy 降级 |
| 提问 | /question | question.v2 → form 服务 | 双通道 + 404 记忆 |
| 配置写 | PATCH /config 可写 | 官方只读（实现仍发 PATCH） | UI 禁用状态未确证（#85） |
| Provider 认证 | oauth/authorize+callback | integration（未适配） | V2 仅 API key（credential） |
| 会话状态 | /session/status | /api/session/active + SSE | 分流 |
| Todo | /session/{id}/todo | beta 无（dev 已回归） | 404 记忆 + 入口隐藏 |
| 后台化/shell | 实验性/同步 shell | background + 独立 /api/shell | V1 隐藏/降级 |
| share | 有 | 无（新版源码已有） | V2 隐藏 Share（#78） |
| 文件系统 | /file、/find 族 | /api/fs/read|list|find | 大目录回退 + 信封漂移补丁（#248/#249） |
| VCS+路径 | /vcs*、/path | /api/vcs*、/api/location | directory→home 降级 |
| 销毁 | /global/dispose、/instance/dispose | /api/service/stop | 分流 |

---

## 6. 写操作（mutation）全清单 —— DSH 特权方法风险集中区

> 共 38 项。括号内为 V2 对应端点。全部经 Basic Auth；V1 /global/* 无认证层（服务器侧风险）。

**会话与消息（17）**
1. POST /session —— 创建会话（V2 POST /api/session）
2. DELETE /session/{id} —— 删除会话
3. PATCH /session/{id} —— 重命名/字段更新（V2 POST /api/session/{id}/rename）
4. POST /session/{id}/abort —— 中断执行（V2 POST /api/session/{id}/interrupt）
5. POST /session/{id}/prompt_async —— 发送消息（V2 POST /api/session/{id}/prompt）
6. （V2）POST /api/session/{id}/model —— 切换会话模型
7. （V2）POST /api/session/{id}/agent —— 切换会话 agent
8. POST /session/{id}/command —— 执行斜杠命令（间接驱动 agent）
9. POST /session/{id}/shell —— 会话内 shell 命令（产生聊天轮次；V2 同路径=后台 shell 体系）
10. （V2）POST /api/session/{id}/background —— 前台工具批量转后台
11. POST /session/{id}/share —— 创建分享链接（V2 无）
12. DELETE /session/{id}/share —— 取消分享（V2 无）
13. POST /session/{id}/summarize —— 压缩会话（V2 POST /api/session/{id}/compact）
14. POST /session/{id}/revert —— 撤销到消息（V2 POST /api/session/{id}/revert/stage）
15. POST /session/{id}/unrevert —— 重做（V2 POST /api/session/{id}/revert/clear）
16. POST /session/{id}/fork —— 分叉会话
17. POST /session/import —— 导入会话；DELETE /session/{id}/message/{mid}（/part/{idx}）—— 删除消息/part

**权限与提问（3 组 6 端点）**
18. POST /permission/{id}/reply —— 权限裁决 once/always/reject（V2 POST /api/session/{sid}/permission/{id}/reply；legacy /api/permission/{id}/reply）
19. POST /question/{id}/reply —— 回答提问（V2 POST /api/session/{sid}/question/{fid}/reply → /api/session/{sid}/form/{fid}/reply）
20. POST /question/{id}/reject —— 拒绝提问（V2 .../question/{fid}/reject → .../form/{fid}/cancel）

**PTY/终端（3+1）**
21. POST /pty —— 创建 PTY
22. PUT /pty/{id} —— resize
23. DELETE /pty/{id} —— 删除 PTY
24. WS /pty/{id}/connect —— 终端交互（**等效任意 shell 执行**，DSH 侧等同最高特权）

**MCP（2）**
25. POST /mcp/{name}/connect
26. POST /mcp/{name}/disconnect

**Provider 认证/凭据（4）**
27. POST /provider/{id}/oauth/authorize（V2 未适配）
28. POST /provider/{id}/oauth/callback（V2 /api/... 同构）
29. PUT /auth/{id} —— 写入 API key（V2 PATCH /api/credential/{id}）
30. DELETE /auth/{id} —— 删除凭据（V2 DELETE /api/credential/{id}）

**配置/实例生命周期（4，副作用最强）**
31. PATCH /config —— 写项目配置（副作用：销毁当前实例；V2 PATCH /api/config，官方只读）
32. PATCH /global/config —— 写全局配置（**副作用：销毁所有实例**，含进行中会话）
33. POST /global/dispose —— 销毁所有实例（V2 POST /api/service/stop）
34. POST /instance/dispose —— 销毁当前实例（V2 POST /api/service/stop）

**后台 shell（V2，2）**
35. DELETE /api/shell/{id} —— 终止并删除后台命令
36. （V2，dormant）PATCH /api/shell/{id}/timeout —— 更新超时（无 UI 消费方）

**其他（2）**
37. GET /file / /api/fs/* 只读——无写端点（客户端无文件上传/写入 API；agent 通过工具自写）
38. 客户端不调用：/global/upgrade、/tui/*、/experimental/*（源码 grep 无引用）

---

## 7. 不确定/待确认项（推测标注）

1. V2 下「服务器设置页配置编辑」是否被 UI 禁用：ServerSettingsViewModel 仍调用 updateGlobalConfig（ServerSettingsViewModel.kt:432-441），V2ApiClient 会真实发 PATCH /api/config；UI 层 isV2 门控未在本次抽样中确认【推测：V2 会请求失败或被 #85 后续禁用】。
2. FileViewer 批注提交（submitAnnotations，FileViewerViewModel.kt:283）的目标通道未展开核实【推测：以消息形式发回会话】。
3. V1 上下文用量卡的数据源：V2 有 session.usage.updated；V1 呈现口径未逐行核实【推测：消息 tokens 字段推算】。
4. 架构文档提及的 CrossServerSessionsScreen/CrossServerSessionsAggregator 在源码树无对应文件（glob/grep 0 命中），仅存 Screen.kt:19 死路由——文档滞后于代码。
5. V1 1.18 端口 4199 为本机部署约定（AGENTS.md），非服务器默认（V1 默认 4096）。
