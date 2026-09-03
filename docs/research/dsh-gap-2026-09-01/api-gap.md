# DSH 服务器 ↔ OC Beacon (Android) 能力差距清点

研究日期：2026-09-01。方法：静态源码走查（DSH npm 全局包 cordis 插件源码）+ 活体只读 RPC 探测（127.0.0.1:3080，仅空参数存在性探测与纯读端点，零状态变更调用）。

证据路径缩写：
- **AP** = `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/index.js`（5555 行，宿主 ApiProxy：56 条 unary 路由 + 非信封端点 + 两帧流实现）
- **CC** = `.../dsh-client-connection/lib/index.js`（/api 载体 + WS upgrade）
- **AR** = `.../dsh-api-remotes/lib/index.js`（转发事件白名单 + agent 解析器）
- **TH(x)** = 各包 `lib/typert.host.js` 生成文件（typert 远程调用清单，`invocations[].id`）
- Android：`/home/leo-tkp/Documents/code/mine/oc-beacon/app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshApiClient.kt`（下称 **DAC**）、`DshEventMapper.kt`（下称 **DEM**）

传输统一为：POST `http://<host>:3080/api/<method>`，body `{"type":"client-request","rpcId":...,"method":...,"payload":{...}}`；typert（斜杠命名空间）端点 payload 用 `{"args":{...}}`（DAC:256-262 实证）。GET 版 /api/events.mux|events.host 返回 426，事件流实际走 WS upgrade（CC:539, 567-584）；AP 内另有同路径 SSE fetch 实现（AP:4895-4899，进程内/BFF 用）。

---

## 1. 服务端 RPC 方法全集

### 1a. 旧式点分路由（UNARY_ROUTES，AP:4575-4783，共 56 条）

| 方法 | 行号 | 用途 | 副作用 |
|---|---|---|---|
| session.list | AP:4577 | 全量会话摘要（running/blank/cwd/projections） | 读 |
| session.search | AP:4581 | 服务端全文搜索（本部署 openAt="never" 禁用，活体实证） | 读 |
| session.create | AP:4585 | 建会话（workspaceId/cwd 二选一） | **写** |
| session.history | AP:4589 | 事件日志分页（beforeSeq/maxMessages，含 view） | 读 |
| session.models | AP:4593 | 该会话可用模型列表 | 读 |
| session.selectModel | AP:4597 | 切会话模型（provider/model/reasoningEffort） | **写** |
| session.rename | AP:4601 | 改标题（宿主侧规整） | **写** |
| session.fork | AP:4605 | 在 atSeq 完成轮切点分叉子会话 | **写** |
| session.prompt | AP:4609 | 发消息（content 多块 + mode: queue/steer） | **写** |
| session.attachment | AP:4613 | 拉附件字节（base64 + 尺寸元数据） | 读 |
| session.updateQueue | AP:4617 | 排队项 edit/remove/steer | **写** |
| session.cancel | AP:4621 | 中断会话当前轮 | **写** |
| subagent.list | AP:4625 | 子代理树（parentSessionId 懒加载） | 读 |
| subagent.history | AP:4629 | 子代理会话事件历史 | 读 |
| subagent.prompt | AP:4633 | 向子代理会话追加指令 | **写** |
| subagent.interrupt | AP:4637 | 中断子代理 | **写** |
| host.describe | AP:4641 | 版本/home/cwd 等宿主元数据 | 读 |
| host.pickDirectory | AP:4645 | 宿主侧（桌面）原生目录选择器 | 交互 |
| host.listDirectory | AP:4649 | 列目录（需全限定路径） | 读 |
| host.createDirectory | AP:4653 | 建目录 | **写** |
| host.openPath | AP:4657 | 宿主 OS 默认应用打开路径 | **写**（宿主侧打开） |
| workspace.list | AP:4661 | 工作区列表 | 读 |
| workspace.create | AP:4665 | 建工作区 | **写** |
| workspace.rename | AP:4669 | 改工作区名 | **写** |
| workspace.delete | AP:4673 | 删工作区 | **写** |
| workspace.insertBefore | AP:4677 | 工作区排序 | **写** |
| workspace.insertSessionBefore | AP:4681 | 把会话移入工作区某位置 | **写** |
| workspace.archiveSession | AP:4685 | 归档会话 | **写** |
| skill.list | AP:4689 | 会话技能列表（需 attached 会话） | 读 |
| agentPreset.list | AP:4693 | Agent 预设清单 | 读 |
| agentPreset.select | AP:4697 | 会话选定预设（blank 期限定） | **写** |
| agentPreset.read | AP:4701 | 读预设全文档 | 读 |
| agentPreset.copy | AP:4705 | 复制预设 | **写** |
| agentPreset.openDocument | AP:4709 | 预设原始文档送编辑器 | 读（打开编辑会话） |
| agentPreset.remove | AP:4713 | 删预设 | **写** |
| goal.create/edit/pause/resume/complete/clear | AP:4717-4737 | goal 六态 mutation（sessionId+ref，CAS） | **写** ×6 |
| settings.describe | AP:4741 | 命名空间设置视图（含 revision，secret 脱敏） | 读 |
| settings.openDocument | AP:4745 | 设置原始文档 | 读 |
| settings.update | AP:4749 | 文档形设置更新 | **写** |
| settings.replace | AP:4753 | 文档整替 | **写** |
| settings.mutate | AP:4757 | ops(JSON-Path set 等)+expectedRevision 乐观并发 | **写** |
| credentials.describe | AP:4761 | 凭据状态（provider API key 是否已设） | 读 |
| credentials.set | AP:4765 | 设 API key | **写** |
| credentials.unset | AP:4769 | 清凭据 | **写** |
| llm.providers | AP:4773 | provider 目录（id/displayName/settingsNs） | 读 |
| llm.models | AP:4777 | 分组模型表（含 reasoning efforts） | 读 |
| llm.discoverModels | AP:4781 | 自定义 baseURL 探测模型列表 | 读（外呼） |

### 1b. 非信封端点

| 端点 | 证据 | 用途 | 副作用 |
|---|---|---|---|
| POST /api/respond | AP:4922-4928, 实现 AP:3727-3775 | 应答 approval（allowed-once/rejected）/ question（answers）服务器发起请求 | **写**（裁决） |
| GET/HEAD /api/session.export | AP:4902-4914, 实现 AP:3702 | 会话日志 ZIP 下载（含 descendants） | 读 |
| WS /api/events.mux | CC:16, 579 | 会话事件流（见 §2） | 订阅 |
| WS /api/events.host | CC:18, 582 | 宿主生命周期流（见 §2） | 订阅 |

### 1c. Typert 远程（斜杠命名空间，经 dsh-api-gateway 分发）

| 端点 | 证据（各包 lib/typert.host.js `invocations[].id`） | 用途 | 副作用 |
|---|---|---|---|
| commands/execute | dsh-commands TH | 执行斜杠命令（line + images） | **写** |
| commands/list | dsh-commands TH | 会话作用域命令注册表 | 读 |
| goals/create·edit·pause·resume·complete·clear | dsh-goal TH（6 条） | goal mutation 的 typert 面（wire: agentId+request；与 1a 的 goal.* 并存双轨） | **写** ×6 |
| messageFeedback/list | dsh-message-feedback TH | 消息点赞/点踩记录（request{sessionId}） | 读 |
| messageFeedback/put | 同上 | 写消息评价（positive/negative） | **写** |
| messageFeedback/delete | 同上 | 删消息评价 | **写** |
| fileReferences/list | dsh-file-reference TH | 会话引用文件清单（agentId） | 读 |
| pluginInventory/list | dsh-host-plugin-inventory TH（零参数） | 宿主插件清单快照 | 读 |
| sessionReferenceResolver/candidates | dsh-session-reference TH | `@session:` 引用候选解析（agentId） | 读 |
| dynamicCordisRunner/×12（getClientCode/inventory/invoke/runHostHalf/settleUserRun/stopFromPanel/undefineFromPanel/resolveRequestRun/resolveInspectQuery/syncInspectManifest/reportClientGuardFailure/reportRenderFailure） | dsh-cordis-host-runner TH | Web 动态 cordis 插件运行时（invoke=任意代码） | 混合，Web 专用 |

活体探测（只读/空参存在性验证，2026-09-01）：session.search（端点在、部署禁用）、pluginInventory/list（ok=true 全清单）、llm.providers（ok）、goals/create（缺参报错=存在）、messageFeedback/list（缺参=存在）、fileReferences/list（缺参=存在）、skill.list（session-not-found=存在）。

---

## 2. WS/SSE 事件全集

### 2a. events.mux 帧（zod 判别联合 AP:5012-5083；服务端组装 AP:3523-3606 + 广播 AP:1780-1935）

`session/event`（内嵌开放类型 SessionEvent + 宿主渲染 view）· `session/subscribed`（开流基线 lastSeq）· `approval/requested` · `approval/resolved` · `question/requested` · `question/resolved` · `session/queue`（排队收件箱整快照，placement=queued/steering/context）· `session/jobs`（后台任务整快照）· `session/projection`（key: tokenUsage/subagentTiming/goal/contextPressure/contextBreakdown/sessionStats/permissions/sessionListMetadata/imageLimits…）· `stream/error`。

内层 SessionEvent 开放类型：Android DEM:406-515 已识别 ~40 种（user/message、assistant/message|chunk、tool/call|result、turn|step start/end、todo/write、session/title、compaction/*、goal/change、subagent/descriptor、agent-preset/selected、permission/preset、sandbox/mode、approval/policy、plan/mode、agent/inbox/spliced、llm/retry*、command/run|done、tool-workflow/*、hook/*、team/*、approval/asked|decided 等）。

### 2b. events.host 帧（AP:5086-5128；实现 AP:3609-3700）

`host/session-added`（含 blank/cwd/agentPreset/origin）· `host/session-removed` · `host/session-status`（running）· `host/agent-error` · `host/workspace-changed` · `host/workspace-removed` · `host/workspace-order-changed` · `host/archived-sessions-changed` · `host/remote-event` · `stream/error`。

### 2c. host/remote-event 内转发的事件名（白名单 AR:19-31，全应用唯一转发控制点）

`agent-preset/selected` · `commands/change` · `credentials/reference-updated` · `cordis/request-run` · `cordis/request-run-resolved` · `cordis/dynamic-package` · `cordis/dynamic-retract` · `cordis/inspect-query` · `cordis/inspect-query-resolved` · `llm/adapters-updated` · `settings/document-updated`。

官方 Web 客户端解包方式：client-runtime client.js:10518 `host/remote-event → ctx.remote.$dispatch(frame.event, frame.args)`。

---

## 3. 对比表：服务端有 ↔ Android 消费 ↔ 官方前端 UI

### 3a. RPC 方法（✔=已消费；行号为 Android 证据）

| 方法 | Android | 消费处 | 官方前端 UI 线索 |
|---|---|---|---|
| session.list | ✔ | DAC:104,131,441 | sidebar/conversation |
| session.search | ✘ | —（本地 title 过滤替代） | 搜索框（本部署禁用） |
| session.create / rename / fork / cancel / prompt / history / attachment / selectModel / updateQueue | ✔ | DAC:149/162/217/176/606/503/1243/622/697 | conversation 全套 |
| session.models | ✘ | — | model-selection |
| subagent.list | ✔ | DAC:403 | dsh-client-ui-subagent |
| subagent.history / prompt / interrupt | ✘ | — | dsh-client-ui-subagent |
| host.describe / listDirectory | ✔ | DAC:791/866,886 | 全局 |
| host.pickDirectory / createDirectory / openPath | ✘ | — | dsh-client-ui-directory-picker-browse/native |
| workspace.list | ✔ | DAC:914,945 | sidebar |
| workspace.create/rename/delete/insertBefore/insertSessionBefore/archiveSession | ✘ | — | sidebar（分组/归档/拖排序） |
| skill.list | ✘（恒空列表 DAC:840） | — | dsh-client-ui-skill |
| agentPreset.list / select | ✔ | DAC:276/303 | dsh-client-ui-agent-preset |
| agentPreset.read / copy / openDocument / remove | ✘ | — | 同上（预设管理页） |
| goal.* ×6（旧式） | ✔ | DAC:322-355 | dsh-client-ui-goal（走 goals/*） |
| goals/* ×6（typert） | ✘（用旧式面，双轨等价，非缺口） | — | dsh-client-ui-goal |
| settings.describe / mutate | ✔ | DAC:1195/1215 | settings-general（权限默认档等） |
| settings.openDocument / update / replace | ✘ | — | dsh-client-ui-settings |
| credentials.describe / set / unset | ✘ | — | settings-models（API key 录入） |
| llm.providers / models | ✔ | DAC:1035/1040 | model-selection |
| llm.discoverModels | ✘ | — | settings-models（自定义 baseURL） |
| commands/list / execute | ✔ | DAC:820/262 | dsh-client-ui-commands |
| messageFeedback/list·put·delete | ✘ | — | dsh-client-ui-message-feedback |
| fileReferences/list | ✘ | — | dsh-client-ui-deliverables/reference（`remote.fileReferences.list` 实证调用） |
| sessionReferenceResolver/candidates | ✘ | — | 同上（`remote.sessionReferenceResolver.candidates` 实证） |
| pluginInventory/list | ✘ | — | dsh-client-ui-settings-plugins / plugin-inventory |
| dynamicCordisRunner/* ×12 | ✘ | —（Web 插件运行时，Android 不适用） | cordis 动态插件 |
| POST /api/respond | ✔ | DAC:745,773,781（rpc.respond） | approval/question 弹窗 |
| GET /api/session.export | ✔ | DAC:546 | /export 命令 |

计：Android 消费 27 方法 + 2 端点；服务端总计 56 旧式 + 26 typert（含 12 条 Web 专用 dynamicCordisRunner）+ 2 端点 + 2 事件流。

### 3b. 事件帧（Android 消费处 = DEM mapFrameInner DEM:79-366 / mapSessionEventInner DEM:406-515）

| 帧/事件 | Android | 备注 |
|---|---|---|
| session/event、session/subscribed、approval/requested、approval/resolved、question/requested、question/resolved、session/queue、session/jobs、session/projection（tokenUsage/subagentTiming/goal/contextPressure/contextBreakdown/sessionStats/permissions 七键）、stream/error | ✔ | DEM:80-311 |
| session/projection 其余键（title=sessionListMetadata、imageLimits…） | ✘ 忽略 | DEM:291 PROJECTION |
| host/session-added、session-removed、session-status、agent-error | ✔ | DEM:313-353 |
| host/workspace-changed、workspace-removed、workspace-order-changed、archived-sessions-changed | ✘ 忽略 | DEM:355-365（HOST_WORKSPACE） |
| host/remote-event 全部 11 个转发事件 | ✘ 未解包 | 落入 else→HOST_WORKSPACE 忽略；官方客户端 client.js:10518 有解包分发 |
| commands/change（agent-preset/selected、llm/adapters-updated、settings/document-updated 等同理） | ⚠ 死代码 | DEM:162 有 `"commands/change" ->` 分支，但服务端只以 host/remote-event 包装发送（AR:26 + AP:3691-3694），裸帧永不出现 → #285 CommandsChanged 刷新链路在 WS 上实际不触发（bug 级发现，见 §4-0） |

---

## 4. 差距结论（服务端提供、Android 完全未碰，按用户价值排序）

**0（先说 bug）**：`DshEventMapper.kt:162` 的 commands/change 消费分支是死代码——转发事件一律以 `host/remote-event` 包装（AR:19-31、AP:3691），Android 未解包该包装帧。影响：命令注册表变更刷新（#285）、agentPreset 选中变更（host 流双保险）、llm 适配器更新、settings 文档更新四类通知全部静默丢失。修复即加一个 `host/remote-event` 解包分支（取 payload.event 分发）。

**P1 工作区/会话组织**（workspace.create/rename/delete/insertBefore/insertSessionBefore/archiveSession + 4 个 host/workspace-* 帧 + host/archived-sessions-changed）：服务端有完整的多工作区分组、排序、归档模型，Android 只有只读 workspace.list 当"项目列表"，无归档、无分组管理。会话量大后是首要整理痛点。

**P2 消息评价**（messageFeedback/list·put·delete）：会话内点赞/点踩消息 + 服务端留存清单，官方有完整 UI（dsh-client-ui-message-feedback）。Android 零接触。实现成本低（3 个方法 + 1 个 `feedback/record` 事件已在 DEM 忽略清单里）。

**P3 子代理交互**（subagent.prompt/interrupt/history）：Android 已有子代理树浏览（subagent.list），但不能对子代理会话追加指令、中断或翻历史——官方 UI 支持。与既有 AgentSheet 树天然衔接。

**P4 凭据与模型自助**（credentials.describe/set/unset + llm.discoverModels + host 事件 credentials/reference-updated、llm/adapters-updated）：服务端可代管 provider API key、探测自定义 baseURL 模型。Android 切模型只能用服务器已配好的目录。对自托管用户价值中高。

**P5 引用/交付物**（fileReferences/list + sessionReferenceResolver/candidates）：会话引用文件清单与 `@session:` 补全候选，官方 deliverables/reference UI 实证调用。可为 Android 输入框补全与"本次会话产出文件"卡提供数据。

**P6 技能浏览**（skill.list）：Android 恒空列表（DAC:840 注释已自知）；需 attached 会话才能枚举，属已知坑位，价值取决于部署技能量。

**P7 杂项低优先**：session.search（本部署禁用，本地过滤已是替代）；session.models（llm.models 全局面已覆盖主用途）；agentPreset.read/copy/remove（预设管理进阶）；settings.openDocument/update/replace（mutate 已覆盖点改）；host.pickDirectory/createDirectory（Android 有原生选择器，openPath 是宿主桌面动作）；pluginInventory/list（插件设置页）；dynamicCordisRunner/*（Web 专用，Android 架构不适用）。

**非缺口确认**：goal 双轨（Android 用旧式 goal.*，typert goals/* 等价）；session.export、/api/respond、commands/*、approval/question 请求-应答环均已接通。

---
*探测合规声明：活体验证仅调用 session.list/search、llm.providers、pluginInventory/list（纯读）及 goals/create、messageFeedback/list、fileReferences/list、skill.list、session.export 的空参/不存在 id 探测（全部在参数校验/查找层失败即返回，零状态变更）。未调用任何 prompt/execute/create/delete/rename/compaction 类方法。*
