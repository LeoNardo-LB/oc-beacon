# OC Beacon（Android）× DSH 后端 已实现功能面穷尽清点

> 走查日期 2026-09-01 · 只读研究 · 基线 master（含 2026-09-01 dsh-jump-cards-queuedock / dsh-refactor-closeout / disconnect-awareness / busy-dual-key-send 批次）
> 用途：与 DSH web 前端做差距分析的「Android 端有什么」基准。file:line 均相对仓库根。

---

## 1. RPC 方法消费清单

传输契约：`POST {base}/api/{method}`，JSON 信封（method 双写 URL+body）；DSH 无鉴权（authHeader 有意忽略）；错误面 = HTTP 200 + `result.error` 闭集码（39 值，DshApiError.kt:28-44）→ DshApiError。全部实现在 `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshApiClient.kt`（除注明外）。

### 1.1 session 域（12 方法中消费 10）

| 方法 | 调用处 | 驱动功能 |
|---|---|---|
| `session.list` | DshApiClient.kt:104（listSessions）、:131（getSessionRaw）、:441（fetchSessionStatus） | 会话列表全量 + 本地 directory（cwd 全等）过滤 + 本地 title 搜索过滤；cursor 忽略（P-4 未实现）；无 session.get → 全量本地查找；#278 busy/idle 播种（running 字段） |
| `session.create` | :149 | 新建会话（title/parentSessionId/cwd）；回显按 {sessionId,agentPreset} 容忍解析 + blankByDefault 补真（空白页预设卡门控依赖） |
| `session.rename` | :162 | 会话重命名 |
| `session.cancel` | :176 | 中断（interruptSession 对位） |
| `session.fork` | :217 | 会话分叉（messageId 参数不进 payload——DSH fork 无消息锚点） |
| `session.history` | :503（listMessages 分页）、:529（listMessagesRaw 诊断）、DshConnectionOrchestrator.kt:79（对账回填） | 历史分页（beforeSeq/maxMessages，数字排他游标）→ DshHistoryFolder.fold → DshMessageAssembler 装配；断线对账回填（Backfill/InitialFetch，500 页护栏） |
| `session.prompt` | :606 | 发送消息：content=[{type:text}\|{type:image,data,mime}]，**mode 恒 "queue"**（steer 模式未用，留后续 UX）；发送前可先 selectModel；/compact 压缩也走此通道（"/compact" 文本块，:202） |
| `session.selectModel` | :622 | 会话级模型切换 + reasoningEffort（思考档位 pill → variant 槽位）；失败仅告警不阻断发送 |
| `session.updateQueue` | :697 | QueueDock 三动作：edit（纯文本改写）/remove/steer；错误码 → QueueMutationResult（steer-unavailable/queue-item-not-found/agent-busy） |
| `session.attachment` | :1243 | 附件字节拉取（#287）：{sessionId,attachmentId} → base64 data → data URL 回填 Part.File（ChatRepositoryImpl.kt:351 接线） |

未消费（能力位缺口或无方法）：`session.delete`（52 方法面无 → unsupported，UI 隐藏）、revert/unrevert（无方法）、share/import（无域）、updateFields（unsupported）、backgroundSession（无域，恒 false）。

### 1.2 subagent / agentPreset / goal 域

| 方法 | 调用处 | 驱动功能 |
|---|---|---|
| `subagent.list` | :403 | AgentSheet 多级子代理树权威目录（L2 逐层懒加载，payload {parentSessionId}；失败逐层降级本地镜像，#284-a） |
| `agentPreset.list` | :276 | 预设 roster（{id,name,description,isDefault}；活体 4 档 standard/code/minimal/cordis）；失败软降级空列表隐藏卡 |
| `agentPreset.select` | :303 | 空白会话选预设（非 blank → agent-preset-locked → Busy 类 snackbar）；成功回显走 agent-preset/selected 事件 |
| `goal.create` | :322 | 目标创建（objective + maxGoalRounds；懒建会话先 ensureSession） |
| `goal.edit` | :338 | 目标编辑（CAS ref{id,revision}） |
| `goal.pause` / `goal.resume` / `goal.complete` / `goal.clear` | :341-358 | 目标状态机 mutation（complete 在 API/仓库层完整但 **UI 无入口**，见 §5） |

### 1.3 host / workspace / llm / settings / credentials / skill 域

| 方法 | 调用处 | 驱动功能 |
|---|---|---|
| `host.describe` | :791（getHealth）、:796（getServerPaths）、:921（根路径兜底） | 存活/版本探测（DSH 无 /health）、home/cwd |
| `host.listDirectory` | :866（probeDirectory）、:886（listDirectory） | 工作区目录树；条目无 type 判别 → 缺省按 directory（协议级补偿 #276 V4，UI 展开失败转 file 叶） |
| `workspace.list` | :914（根路径解析+缓存）、:945（listProjects） | 项目列表（path/cwd/directory 多键读）；目录树根解析序：调用方 directory → workspace.list 首个 → host.describe cwd |
| `llm.providers` + `llm.models` | :1035/:1040 | 模型目录（目录序优先 + 组序兜底）；reasoning.efforts → 思考档位 variants + capabilities.reasoning |
| `settings.describe` | :1195 | ns=permission（新会话默认权限档 + schema enum 档集动态解析，#283）、ns=agent-presets（默认预设） |
| `settings.mutate` | :1215 | 写 ns=permission/defaultPreset、ns=agent-presets/default（乐观并发 expectedRevision；陈旧 → settings-conflict） |

### 1.4 typert 通道（非 52 方法面）+ 回程 + 非信封

| 入口 | 调用处 | 驱动功能 |
|---|---|---|
| `commands/list`（POST /api/commands/list） | :820（callJson） | 斜杠命令面板 roster（agent-scoped：payload args.agentId=sessionId；CommandDescriptor {name,description,input.hint}）；commands/change 帧触发重载（#285） |
| `commands/execute`（POST /api/commands/execute） | :262 | 泛型斜杠命令执行（/{command} {args}）；唯一封装调用 = `setPermissionPreset`（"/permission <preset>"，:269） |
| `respond`（POST /api/respond） | DshRpcClient.kt:88 | 权限应答（outcome: allowed-once/allowed-always/rejected，rpcId 复用 requested 帧）、提问应答（answers 键控 map）、提问取消（cancelled） |
| `GET /api/session.export` | :546 | 会话导出 ZIP 流式下载（非信封入口；进度回调；.zip 后缀） |

---

## 2. WS 事件消费清单

双下行 WS：`/api/events.mux` + `/api/events.host`（DshWsEventClient.kt:120-122；只收不发、OkHttp ping 25s、双流独立重连退避 500ms×2ⁿ 封顶 10s 带抖动、状态聚合取最差）。帧处理：DshEventMapper.mapFrame（DshEventMapper.kt:73）。

### 2.1 顶层帧 method（mux/host）

| 帧型 | 映射去处 | 驱动 |
|---|---|---|
| `session/subscribed` | DshSubscribed 基线 + 空 JobsSnapshot/QueueSnapshot（:80-94） | 重连对账起点（400ms 静默窗成批落定）+ jobs/queue 清空重推 |
| `session/event` | mapSessionEvent 内层分派（:96） | 全部转录事件（见 2.2） |
| `session/jobs` | JobsSnapshot → DshJobsHandler → DshJobsStore（:165-178） | ShellSheet(DshJobSheet) + 消息流 DshJobTimelineCard（bash 后台任务） |
| `session/queue` | QueueSnapshot → DshQueueHandler → DshQueueStore（:297-310） | QueueDock 排队条 |
| `session/projection` key= | :181-293 | tokenUsage→SessionTokenUsageChanged（上下文弹窗四桶）；subagentTiming→子代理活跃时长；goal→SessionGoalChanged（GoalSheet）；contextPressure→上下文环（ChatTopBar）；contextBreakdown→占用构成图例；sessionStats→turns/steps/延迟统计；permissions→SessionPermissionsChanged（权限选择器回显）。其余键（title…）Ignored(PROJECTION) |
| `approval/requested` | PermissionAsked（:103；rpcId 入 metadata 为回程键） | 权限审批卡（once/always/reject） |
| `approval/resolved` | PermissionReplied（:129） | 审批卡消解 |
| `question/requested` / `question/resolved` | QuestionAsked/Replied/Rejected（:136-158） | 提问卡（多题单事件、自定义文本答案恒允许、cancelled→Rejected） |
| `commands/change` | CommandsChanged（:162） | 斜杠命令 roster 重拉（#285） |
| `host/session-added` / `host/session-removed` | SessionCreated / SessionDeleted（:313-336） | 会话列表实时增删（+级联清理 8 handler） |
| `host/session-status` | SessionStatus Busy/Idle（:338） | FSM 会话状态（服务器权威 running） |
| `host/agent-error` | SessionError（:347；message/error 双键回退） | 会话错误卡 + 一次性 snackbar + 通知 |
| `stream/error` | Ignored（STREAM_ERROR） | 连接层错误（仅日志） |
| 其余 `host/*`（host/workspace-*、archived-sessions-changed 等） | Ignored(HOST_WORKSPACE) | **无消费**（oc-beacon 无 Workspace 事件域） |

### 2.2 SessionEvent 内层 49 型（session/event 与历史行同路径，DshEventMapper.kt:400-516）

**映射（有转录/UI 语义）**：
- `user/message` → MessageUpdated + text/file·image Part（:527）
- `assistant/message` → 拆流式桥 + MessageUpdated（tokens from usage、interrupted→finish）+ reasoning/text/file·image part（:571）
- `tool/call` / `tool/result` → 工具卡（callId 键控宿主消息 dsh-call-{callId}；Pending→Completed/Error）（:638/:676）
- `assistant/chunk`：block-start→种子 part、text-delta/reasoning-delta→MessagePartDelta（流式管线，48ms 批处理复用）；block-end/usage/tool-call-delta/finish Ignored（:820-876）
- `turn/start`、`step/start` → Busy；`turn/end` → SessionIdle（:414-416）
- `todo/write` → TodoUpdated（整快照 last-wins，无优先级→medium）（:879）
- `session/title` → SessionUpdated（title+排序时刻）（:893）
- `compaction/end` → SessionCompacted（压缩完成 snackbar；start/summary/prune Ignored）（:426-429）
- `goal/change` → SessionGoalChanged（whole-value；clear tombstone→null）（:432）
- `agent-preset/selected` → SessionAgentPresetChanged（预设卡高亮）（:444）
- `permission/preset` / `sandbox/mode` / `approval/policy` → SessionPermissionChanged（三 knob 权限状态回显）（:457-462）
- `tool-workflow/run-start` / `run-end` → synthetic 降级卡（同 runId 原位更新 running→completed/error）（:492-493；#288 注记：当前服务器不暴露这些事件，休眠代码）

**具名忽略（已核实无转录语义）**：plan/mode、agent/inbox/spliced、step/end、llm/retry(-started)、command/run|done、request/header|context、session/end-seed、web/deepseek-search-llm-request、schedule/change、feedback/record、tool/code-dispatch(-start)、approval/asked|decided（durable 面，防历史重放重复弹窗）、llm/failover、session/title-llm-request、hook/invoked|result、team/task|member|message/delivered|message/queued、tool-workflow/agent-start|agent-end、subagent/descriptor。

**未知类型**：ignorable:true → Ignored；无旗标 → UNKNOWN_UNIGNORABLE → DshHistoryFolder 拒绝重建（残缺优于空历史反例规则）。

### 2.3 断线对账（组件 C/DshReconciler + Orchestrator）

- 排他水位契约：subscribed.lastSeq = session.seq-1；回放游标 beforeSeq = baseline+1
- 三动作：Backfill（缺口回填翻页）/ InitialFetch（新会话尾页）/ SessionVanished（清本地）
- 最小 Session 防整替换合并（defendSessionReplacement：directory/created/title/permissions/agentPreset/tokenUsage/subagentTiming/goal/contextPressure/contextBreakdown/sessionStats/blank 缺席保留缓存）
- 接线：SseConnectionManager.runDshEventLoop（service/SseConnectionManager.kt:322-402），每服务器独立帧引擎 + DshSessionSeqTracker（连接销毁即清）

---

## 3. UI 功能面清单

### 3.1 服务器管理 / 连接

| 功能 | 组件 | 交互 |
|---|---|---|
| 服务器类型选择 | ServerDialog.kt:96-157 | OpenCode/DSH 二选一 chip；DSH 隐藏用户名/密码（无鉴权），URL 示例换 DSH 端口（默认 3080） |
| DSH 徽章 | ServerCard.kt:94 | "DSH" 标识替代 API 版本徽章（不参与 V1/V2 探测，ApiVersionDetector.kt:72 跳过双探） |
| 断连感知 | ServerLinkBanner（#267）+ writeOp 快速失败 | 三态条幅（连接中/已连接/断开重连中）；断连时 send/compact/fork/rename/delete 快速失败 snackbar |
| 存活探测 | getHealth=host.describe | 版本号展示 |

### 3.2 会话列表 / 服务器设置页

| 功能 | 组件 | 交互 |
|---|---|---|
| 会话加载 | SessionListViewModel | 全量 session.list + 本地 directory 过滤（blank 会话滤除）+ 本地 title 搜索；无服务端分页 |
| 会话树 | SessionTreeList.kt | parentSessionId 本地分组树（subagent 层级）；会话详情对话框（重命名；**删除按钮隐藏**——无 session.delete） |
| 详情字段 | SessionRow.kt:323-392 | Agent 预设只读标签（id→name 解析）；created 显示 "—"（DSH 无 created 时刻） |
| 新建会话 | NewSessionQuickDialog | 快速建会话（title/directory） |
| 新会话默认权限档 | PermissionDefaultRow（ServerSettingsContent 内） | settings.describe 读 + settings.mutate 写（档集 = schema enum 动态解析 #283） |
| 新会话默认 Agent 预设 | AgentPresetDefaultRow | ns=agent-presets 读/写默认预设（roster 卡片选择） |

### 3.3 聊天屏（ChatScreen）

| 功能 | 组件/位置 | 交互 |
|---|---|---|
| 消息渲染 | ChatMessageList | user/assistant 气泡（text/reasoning/file part）、工具卡（tool/call\|result；参数 raw+input 展示、输出展平）、流式 chunk（48ms 批处理+高度补偿铁律复用）、interrupted 前缀、消息级 tokens（usage） |
| 图片附件（收） | Part.File + session.attachment 回源（#287） | url 缺席附件按 attachmentId 拉字节拼 data URL 渲染（内存态缓存——#295 跨进程丢失未决） |
| 图片附件（发） | promptAsync content image 块 | data URL → {type:image,data,mime} |
| 权限审批卡 | PermissionEventHandler + 权限卡 UI | once/always/reject 三键 → /api/respond；本地自动批准规则（PermissionAutoApprover） |
| 提问卡 | QuestionEventHandler + 表单卡 | 选项多选/单选 + 自由文本（custom 恒 true）；取消 → cancelled |
| QueueDock | QueueDock.kt（BottomBar 上方） | 排队项 preview + 编辑（纯文本）/删除/steer（仅 running+next-turn；子代理会话只读）；快照变化退出编辑 |
| 忙碌双键发送 | SendStopAreaState/SendStopButton（2026-09-01） | 忙碌且输入非空 = 停止键+发送键并存（点发送即排队 prompt mode=queue）；输入空仅停止 |
| GoalSheet | GoalSheet.kt（FAB/菜单 GOAL 入口） | 创建（objective+maxGoalRounds）/编辑/pause/resume/clear；phase 标签（active/paused/blocked）+ rounds N/M + blockedReason 内联；**无 complete 按钮**（API 已备） |
| 空白页预设卡 | ChatEmptyState.kt（UI-A） | blank 会话选 Agent 预设（agentPreset.select；locked → snackbar）；sessionIsBlank 门控可反复换档 |
| 权限预设选择器 | PermissionPresetSelector.kt（输入行首位） | 下拉切档（/permission 命令）；custom 态灰显不可改；档集动态（permissions 投影 options，回退已知三档） |
| 模型/档位选择 | AgentModelVariantSelector + ModelConfigDelegate | llm.providers+models 目录；reasoning efforts → 思考档位 pill；选择即 session.selectModel（发送前切换） |
| AgentSheet 子代理树 | SubagentTreeDelegate + TaskDelegate | subagent.list 权威 L2 懒加载 + 本地镜像逐层降级（#284-a）；activity（running/inactive）展示；行点击跳子会话 |
| 子会话跳转 | ChatMessageList（extractToolSubagentSessionId）+ NavGraph DSH_SESSION_UUID_REGEX | EventCard 前向箭头 → 子会话导航（#242 守卫 + DSH id 形态 session-<uuid>/裸 uuid） |
| 快速定位（跳转卡） | JumpTargetExtractor + DshCursorPolicy（数字 beforeSeq） | Q1..Qn 用户消息面板跳转（Room 全量 user 消息≤1000 条；DSH seq-N 游标修复 ee51015d） |
| 上下文环 | ChatTopBar.kt:112 | contextPressure 投影（分子 projectedTokens??pressureTokens / 分母 window）；缺席不渲染 |
| 上下文详情弹窗 | ContextDetailDialog + ContextDetailDelegate + ContextStats | ①-⑦ 区：tokens 四桶（uncached/output/cacheRead/cacheWrite）、子代理区（tokenUsage 累计 + subagentTiming 活跃时长）、上下文占用（used/window + system/tools/messages 构成图例）、sessionStats（turns/steps/llmMs/toolMs/ttft/decode） |
| Shell/任务面板 | PendingSheets DshJobSheet + DshJobTimelineCard | session/jobs 后台任务列表（状态色/文案/duration）+ 消息流内联时间线卡 |
| workflow 降级卡 | SyntheticNotificationCard（mapper synthetic 信封） | run-start/run-end 同卡原位更新（**当前服务器不暴露事件 → 休眠**，#288） |
| 会话错误 | SessionErrorCard + 一次性 snackbar | host/agent-error → 错误卡 + toast（Web 对位，无 dialog） |
| 会话操作 | SessionActionsDelegate | 重命名 / fork（复用卡）/ 中断（session.cancel）/ 压缩（"/compact" 经 prompt 通道，完成信号 compaction/end→snackbar）/ 导出（ZIP 流+进度） |
| 斜杠命令 | SlashCommandRegistry + ModelConfigDelegate | commands/list roster（含 input.hint 自由参数填充）→ commands/execute 执行（未知名 → kind:"error"） |
| 隐藏入口（能力位门控） | ServerCapabilities（ServerConnection.kt:106-129） | share/import、revert/undo、消息删除、shell 命令（! 前缀）、后台化、终端 PTY、MCP、VCS、文件搜索、文件内容读、配置写、凭据管理 —— DSH 下全 false 隐藏 |

### 3.4 Workspace

| 功能 | 组件 | 交互 |
|---|---|---|
| 目录树 | WorkspaceScreen + FileTreePanel + WorkspaceViewModel | host.listDirectory 懒展开；无 type 判别 → 缺省 directory（展开失败 directory-unreadable → 转 file 叶 + LRU 缓存）；根 = workspace.list 首个 path |
| 文件点击 | FileTreePanel.kt:142 | **禁用**（无文件内容读方法；host.openPath 是宿主侧特权） |
| Git 面板 / 文件搜索 | WorkspaceScreen.kt:223-237 | 隐藏（无 vcs/find 域） |

### 3.5 数据/存储层落点

- DshJobsStore / DshQueueStore（整快照 last-wirs，内存）+ EventDispatcher 路由（EventDispatcher.kt:150-156）
- SessionEventHandler 投影折叠（agentPreset/tokenUsage/subagentTiming/goal/contextPressure/contextBreakdown/sessionStats 7 处 last-wins copy 收口 updateSession；permission 三 knob 字段级合并）
- Room 消息缓存（seq-N 消息 id / dsh-t{turn}s{step} 流式宿主 / dsh-call-{callId} 工具宿主 / dsh-workflow-{runId}）
- DshSettingsRepositoryImpl（设置页两行数据源）

---

## 4. 已知未实现 / 已知差距（backlog + 调研，避免重复发现）

### backlog 未决卡（backlog.md，2026-09-01 状态）

- **#295** `dsh` `data`：附件缩略图跨进程丢失——Room 回读路径不触发 session.attachment 回源（attachmentUrlCache/patchFileUrl 均内存态）；方向=Room part 反序列化后统一回源判定或持久化 data URL
- **#294** `dsh` `infra` `ui`：回放期通知风暴 + heads-up 点按劫持（重放 SessionIdle 被误判「新完成」，冷启 7 分钟 57 条通知）；方向=对账基线落定前抑制完成通知 / 事件年龄过滤（需透传帧 time）
- **#293** `dsh` `infra`：连接期发送「挂起」——判决不成立（E2E 坐标伪影：键盘弹起后盲点落键盘），待用户裁决关闭
- **#288** `dsh` `ui`：workflow 阶段卡——四面包夹实证当前服务器**不暴露** tool-workflow 事件（mux/history/projection/jobs 四面皆无），app 侧映射链为休眠代码；升级前置=服务器暴露后再重验
- **#278**（进行中）：僵尸 Busy L3 自愈——fetchSessionStatus 已改 session.list running 播种（87238a1c）；残余=running 中强杀重开收敛场景待真机代跑
- **#289**（busy-dual-key-send 后续）：本地堆积链路（onEnqueue/PendingMessage）失去 UI 入口——拆除或恢复入口待裁决

### 调研文档定音的协议级差距（非 app 可修）

- DSH 无 REST/SSE：仅 RPC+双 WS+export ZIP（feasibility §4）
- session.list 无 directory/search/cursor 参数（本地降级过滤）；无 created 时刻（epoch0 哨兵）
- 无重连游标（mux since 被忽略）→ 重连=重开流+history 回填补偿（§1.6-5）
- 52 方法面无 delete/revert/share/import/PTY/shell/文件读/vcs/find/MCP/凭据写/配置写（能力位缺口，ServerConnection.kt 显式登记）
- skill.list 需 attached 会话（冷会话 session-not-found）→ 恒空列表；getMcpStatus 恒空
- llm 域只读；credentials/settings 特权面仅 ns=permission/agent-presets 两键读写
- listPendingPermissions/listPendingQuestions 恒空（开流即重放未决帧语义）
- 协议坑位：workspace 时间戳 ISO 字符串不进 Project；prompt mode 必填（缺席整单拒绝）；steer 仅 running+next-turn；子代理会话 prompt/queue 拒绝（agent-busy）

### 历史结论（agentPreset 调研 §6 / permission 调研）

- 官方 Web 四 surface：①General 设置默认行（**已实现** PermissionDefaultRow/AgentPresetDefaultRow）②新建会话 chip（Android 形态=ChatEmptyState 空白页卡，#280 定性收口已满足）③会话头只读标签（**已实现** SessionRow）④settings 管理区 roster 卡+copy+cordis add-card（**未实现**——read/copy/openDocument/remove 均 loopback-pinned）
- 官方 Web Full access 强制 RiskConfirmation 二次确认勾选（client.js:3359-3388）——Android PermissionPresetSelector **无二次确认**
- 官方 Web 命令面板含 permission 条目等完整 roster——Android commands/list 已接通（2026-08-31 定音）

---

## 5. 走查新发现的可疑缺失（本清点提出，无既存编号）

通用功能在 DSH 层无落地点 / 疑似遗漏：

1. **goal.complete 无 UI 入口**：DshApiClient.goalComplete(:347) + ChatRepositoryImpl.completeGoal(:402) 全链在位，但 ChatViewModel 无包装、GoalSheet 无完成按钮（仅 pause/resume/edit/clear；Web 语义 complete→回创建表单，但**用户主动标记完成**的入口缺失）。
2. **prompt mode=steer 未接**：session.prompt mode 恒 "queue"（DshApiClient.kt:604 注释「steer=注入进行中轮次，留给后续 UX」）；steer 仅经 session.updateQueue 对**已排队项**生效。Web 有直接 steer 发送形态。
3. **/compact 通道与调研结论冲突（疑似潜伏 bug）**：compactSession 把 "/compact" 文本经 session.prompt 发送（:196-204，注释称「prompt 单文本块以 / 开头=服务端命令注册表执行」）；但 2026-08-31 permission 调研 §2 活体实证 **session.prompt 不派发斜杠命令**（apiproxy 直接 followup()，leading-/ 文本变 user/message 进模型）。若该结论全局成立，DSH 压缩实际发的是普通用户消息。建议 E2E 复核（或改走 commands/execute）。
4. **附件上传仅图片**：promptContentPart 只映射 text 与 file(image data URL)；DSH 附件块有 file 类型（mime/filename/url）——发送侧非图片文件无路径。接收侧 file/image 均映射。
5. **agentPreset 管理面缺失**：预设只读选择；无 read 详情/copy 派生/openDocument/remove（Web surface ④；loopback-pinned 特权面——可能是协议限制而非遗漏，但差距清单应登记）。
6. **会话搜索仅本地 title 过滤**（listSessions :111-113 全量拉回后 contains）；无全文/模糊/时间过滤，大会话量（活体 410 条）性能与 Web 搜索体验有差距。
7. **无会话归档/存档入口**：DSH 有 archived-sessions 概念（host/workspace-* 与 archived-sessions-changed 帧 Ignored(HOST_WORKSPACE)）；Android 无任何归档面。
8. **权限审批历史重放不渲染**（approval/asked|decided Ignored，防重复弹窗裁决）；重连/回填后未决审批依赖开流重放 requested 帧——若重放窗口外到达则丢失（#276 裁决「是否补充重放语义」悬置）。
9. **subagent/descriptor 事件 Ignored**：子代理树靠 subagent.list 拉取 + 本地镜像；WS 实时子代理事件流未消费（树刷新靠轮次事件间接驱动）。
10. **llm/failover、llm/retry 无 UI 呈现**：Part.Retry 对位留后续（DshIgnoreReason 注释）；failover 提供商切换用户不可见。
11. **plan/mode 事件 Ignored(POLICY_STATE)**：DSH 计划模式状态无展示（Web 有 plan 控件，与 permission 同排）。
12. **compaction/start/summary/prune Ignored**：压缩过程无进度呈现（仅完成 snackbar）；压缩摘要不可见。
13. **host.describe 富字段未消费**：仅 version/home/cwd；其余（平台/能力描述等）丢弃。
14. **过期注释误导风险**：ChatScreenBottomBar.kt:88/237 注释仍写「DSH 无 command 执行端点」，实际 commandsSupported=true 已启用（行为正确、注释陈旧）。
15. **多 workspace 支持窄化**：workspace.list 只取首个 path 做根 + 全量做 Project 列表；DSH 会话 cwd 可属不同 workspace，目录过滤按 cwd 全等——跨 workspace 场景未建模。
16. **无 DSH 侧「新建会话选 workspace」入口**（Web 新建屏有 workspace picker + 预设 chip 并排）；Android createSession 接受 cwd 但 UI 上目录选择来自既有项目列表。

---

## 附：协议层组件全录（data/api/dsh/）

| 文件 | 职责 |
|---|---|
| DshRpcClient.kt | HTTP RPC 信封收口（call/callJson/respond/http） |
| DshApiClient.kt | 七域 API 实现（52 方法面 + typert + export），与 V1/V2 并列 *ApiImpl.pick 三分路由 |
| DshWsEventClient.kt（DshWsEventEngine/DshBackoff） | 双 WS 下行 + 独立重连 + 状态聚合 |
| DshEnvelope.kt | 信封编解码 + 39 错误码闭集（DshRpcErrorCode） |
| DshApiError.kt | 错误分类（Busy/Network/…，DshErrorCategory） |
| DshEventMapper.kt | 帧/SessionEvent → SseEvent 映射（49 型 + DshIgnoreReason 闭集） |
| DshHistoryFolder.kt | 历史行三筛折叠（header/chunk/活事件）+ 拒绝重建判据 |
| DshMessageAssembler.kt | SseEvent 序列 → MessagePage 装配 |
| DshSessionMapper.kt | session.list 条目 → Session（含 7 投影基线解析 + permissions） |
| DshSessionSeqTracker.kt | 本地已应用 seq 水位表 |
| DshReconciler.kt | 断线对账纯函数状态机（Backfill/InitialFetch/SessionVanished） |
| DshConnectionOrchestrator.kt | 连接编排（帧循环/对账执行/防整替换合并） |
