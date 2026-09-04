# DSH Web 对齐二轮审计 · 净新增差距(2026-09-04)

> 方法:直接拆解生产 :3080 dsh web 客户端全部 46 个插件 bundle(`/plugins/??…` 批量拉取 3.8MB,按 `__ModuleLoader__.load` 切分为 47 模块),提取 i18n 字典(1468 条 zh/en)、`slots.inject` 挂载点、`ctx.remote.*` RPC 调用与事件订阅;与 OC Beacon 现状(代码级盘点 + `DshEventMapper` 忽略表 + `DshApiClient` stub 清单)交叉比对。一轮审计(2026-09-01,`dsh-gap-2026-09-01/`)覆盖的 14 项不再重复,本文只记**本轮新发现且尚无卡片**的差距。证据文件存 `/tmp/dshweb/`(mods/mod01-47 + i18n-all.txt)。

## 结论速览

OC Beacon 对 dsh web 功能面覆盖约 85%,且多处为超集(问答卡多题分页/GoalSheet complete/审批 always 档/嵌入式终端/15 语言/FTS+标签+收藏)。一轮 14 项中 5 项已随 #309/#313 落地,其余在 #310-#312 跟踪。本轮净新增 **5 项可立卡差距 + 若干缓行项**。

## 净新增差距(已立卡 #320-#324)

### #320 DSH 事件系统通知(web: dsh-turn-notify 对位)
- web 形态:非聚焦会话 turn 结束 → 长轮询 `/turn-notify/focus-wait?client=&since=` → 聚焦/打开该会话;i18n 含 questionChars/skipGoalRounds/goalQuietMs 节流语义(插件加载行已实证)。
- Android 形态:**系统通知**(非聚焦会话 turn 结束/问题到达/审批等待 → 通知 + deep-link 进会话)。移动端核心价值:发起任务→锁屏→完成通知。
- 已有基础:Settings→Notifications 全套(开关/静音/测试/deep-link)、SseConnectionManager/host/session-status 事件流、通知渠道 `opencode_export` 先例。纯接线,无新 UI。
- 注意:web 用 HTTP 长轮询侧信道;Android 走既有 WS 事件流即可,不需要该 HTTP 端点。

### #321 DSH @文件引用补全为空(web: ui-reference)
- 现状:`DshApiClient.findFiles` 返回 `emptyList()`(stub)→ DSH 服务器上输入 `@` 补全弹层永远空;@ 文件 mention 是 composer 核心功能,属静默失效。
- web 数据源:`fileReferences/list`(workspace-rooted、可下钻、面包屑)+ `sessionReferenceResolver/candidates`(会话源,#310 已跟踪)。fileReferences 仅补全数据源,不涉特权读取(与 file.read 能力位缺口无关)。
- 修复面:数据层一个方法接入(FileApi.findFiles 的 DSH 分支),UI(FileMentionSuggestions/VisualTransformation/草稿持久化)零改动。

### #322 DSH 服务端内容搜索(web: session/search)
- 现状:会话列表标题搜索=客户端过滤(`DshApiClient.listSessions` 本地 contains);内容搜索 `searchText` stub 空。客户端 FTS(messageFtsIndex)仅覆盖本地已加载会话。
- web:`session/search` RPC 按名字+内容搜全部历史("Searching session history…",名字命中兜底)。
- 修复面:searchText 的 DSH 分支接 session/search;命中导航(ContentHitNavigation/jumpToMessageId)与筛选 chips UI 全在。

### #323 斜杠命令执行反馈行(web: command/run|done)
- 现状:`DshEventMapper` 将 `command/run|done` Ignored(COMMAND)→ 用户执行 /compact 等后无任何流内反馈,只能靠结果间接感知。
- web:流内命令行(Running…/Completed/Failed,+ 图片不支持附件的拒绝提示 command.imagesUnsupported)。
- 修复面:mapper 两分支 → EventCard(通用事件卡,severity/expandable/actions 现成)。SseEvent 三步全走铁律适用。

### #324 DSH 设置面深度对齐(合并卡,缓行池)
- Settings→Models:自定义 provider 增删(ID/protocol/baseURL)、模型目录编辑(显示名/上下文窗/max tokens/能力位)、`llm/discoverModels` 拉取(web `llm/listConfigurableProviders|discoverModels`);beacon 现有 auth 管理+模型过滤,缺 CRUD。
- Settings→Plugins:插件配置卡(shell 命令超时/输出上限、agent loop 并行工具、web search key/端点/上限、子代理模型选择)+ 插件清单(`pluginInventory/list`,Enabled/Disabled/运行态,per-preset 开关)。
- Agent preset 管理:`agentPresets/read|copy|deletePreset` 浏览/复制/删除/看组成/设默认(beacon 只有选择器+默认行)。
- "/" 菜单 Skills 触发组:`skills/list`(beacon 只有客户端斜杠+服务端 commands/list)。
- UI 范式:ServerSettingsContent/ServerProvidersScreen 行样式可直接扩;移动端价值中等,故 P3 合并缓行。

## 缓行/不立卡项(记录备查)
- **user/message 上下文注入/召回块**(系统提示词更新、快照、"n kept · m omitted" 召回、被引用会话 chip):EventCard 可承载,价值低频,随 #310 @session 源一并考虑。
- **approval/asked|decided 历史重放**:web 转录可见历史审批记录,beacon 有意 Ignored(#276 曾裁决是否补重放语义)——维持悬置,不动。
- **Cordis 动态插件面板**(dynamicCordisRunner/inventory 等 9 RPC + @pluginId 触发):插件开发者向,移动端缓行;#288(workflow 卡)仍是 P4(服务器不发事件)。
- **keepalive 监控页**(`/plugins/dsh-keepalive/status` 轮询):运维向,缓行。
- **settings 文档编辑**(settings/replace|update/openSettingsDocument):桌面概念,移动端用结构化设置(#324)覆盖即可。
- **图片模型支持预检**(image.modelUnsupported 文案)与 **llm/failover 呈现**:低频边角,随相关批次顺手。
- **`?fixture=` 离线演示模式**:web 隐藏 QA 面——对 beacon 真机 E2E 有借鉴价值(容器内已用真实服务器,暂不需要)。

## 附:RPC 面完整清单(本轮实证,web 客户端实际调用)
`session/`:list|create|rename|prompt|cancel|control|follow|fork|search|page|selectModel|modelCatalog|updateQueue|attachment|openWorkspacePath|canOpenWorkspacePath|end-seed(错误码:title-invalid|agent-busy|conflict|not-found|queue-item-not-found|steer-unavailable|fork-unavailable|attachment-invalid|workspace-attach-failed)
`workspace/`:create|rename|delete|archiveSession|insertBefore|insertSessionBefore|follow · `commands/`:list|execute · `skills/list` · `goals/`:create|edit|pause|resume|clear · `messageFeedback/`:list|put|delete · `subagents/`:list|prompt|interruptByParent · `agentPresets/`:list|select|read|copy|deletePreset · `dynamicCordisRunner/*`(9) · `pluginInventory/list` · `fileReferences/list` · `sessionReferenceResolver/candidates` · `directoryPicker/`:list|createDirectory|pick · `settings/`:describe|mutate|replace|update|openSettingsDocument|openAgentPresetDirectory|canOpenAgentPresetDirectory · `llm/`:listProviders|listConfigurableProviders|discoverModels · `credentials/`:describe|set|unset · `approval/`:policy|request · `permission/preset` · `plan/mode` · HTTP:`/api/remote.mux`(WS)、`/api/session.export`、`/turn-notify/focus(-wait)`、`/plugins/dsh-keepalive/status`
(对齐一轮:`docs/research/dsh-gap-2026-09-01/api-gap.md`)
