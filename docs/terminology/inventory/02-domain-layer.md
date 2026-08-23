# 盘点：domain 层（app/src/main/java 下 domain 目录全部 Kotlin 文件）

> **范围勘误（重要）**：任务给定的 glob `app/src/main/java/**/domain/**/*.kt` 在本仓库匹配 **0 个文件**——Kotlin 源码实际位于 `app/src/main/kotlin/`（无 `java` 目录）。domain 层真实路径为 `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/`。本盘点以"main source set 下 domain 目录全部 Kotlin 文件"为范围口径，共 **91 个文件**（model 46 / repository 15 / usecase 28 / tracker 1 / util 1），已逐一全文精读（含大文件分段）。另发现 `app/src/test/kotlin/...` 下还有 31 个 domain 测试文件，**不在 main 范围内、未盘点**（清单见文末附录）。本文件为 Phase 1 事实收集，不做术语裁决。

## 覆盖清单

前缀省略 `app/src/main/kotlin/dev/leonardo/ocbeacon/`；语言现状四分类：中文 / 英文 / 混合 / 无注释。

### domain/model（46 文件）

| 文件 | 读 | 语言 | 备注 |
|---|---|---|---|
| model/AgentInfo.kt | ✓ | 中文 | agent 领域模型；KDoc 自称"与 data.dto.response.AgentInfo 对应" |
| model/Annotation.kt | ✓ | 中文 | 概念核心：选区"标注"（offset/line:col 转换器 OffsetConverter 同文件） |
| model/AnnotationPromptBuilder.kt | ✓ | 中文 | 注释+字符串字面量均中文（构造发给模型的提示文本）；"字符索引"措辞与实际列号不符（见失实#3） |
| model/ApiResult.kt | ✓ | 中文 | 统一结果类型；区分"认证失败(401)/授权失败(403)"、"瞬时错误" |
| model/ApiVersion.kt | ✓ | 中文 | V1/V2/UNKNOWN；isV2 定义点（版本 seam 词条相关） |
| model/AppSettings.kt | ✓ | 中文 | 设置聚合；showTurnDividers（turn 无中文注释）、"堆积/TODO 常驻抽屉" |
| model/AutoApproveRule.kt | ✓ | 中文 | 权限自动批准规则；"工具名"实际比较 event.permission（见失实#7） |
| model/CommandInfo.kt | ✓ | 中文 | 命令领域模型 |
| model/CompactionStateInfo.kt | ✓ | 中文 | "压缩状态"（compaction=压缩） |
| model/CreateSessionOpts.kt | ✓ | 无注释 | 纯数据类，无领域术语注释 |
| model/DebugProfile.kt | ✓ | 中文 | "调试通道参数（#132）"，debug intent 一键直达 |
| model/Draft.kt | ✓ | 无注释 | 草稿（text/imageUris/agent/variant） |
| model/FileContent.kt | ✓ | 无注释 | TEXT/BINARY 两态 |
| model/FileNode.kt | ✓ | 中文 | FileNode 部分无注释；ServerPaths 有中文 KDoc（home/state/config/worktree/directory） |
| model/LinkClassifier.kt | ✓ | 中文 | 链接三分类（Web/相对路径/绝对路径）；"会话工作目录"措辞 |
| model/McpServerStatus.kt | ✓ | 英文 | 行内注释为枚举值英文（"local" \| "remote"；connected \| disabled \| …） |
| model/MergeStrategy.kt | ✓ | 中文 | 三合并策略；"真相源""更长文本胜出"术语密集 |
| model/Message.kt | ✓ | 中文 | role=user/assistant（API 原词）；TimeInfo.completed 为"流式 turn"词条数据锚点 |
| model/MessagePage.kt | ✓ | 中文 | nextCursor/previousCursor；older/newer"方向"术语 |
| model/ModelSelection.kt | ✓ | 中文 | "provider/model 配对" |
| model/PaginationCursor.kt | ✓ | 中文 | 术语密度极高：热表/归档桶/服务器游标/死循环；版本 seam 词条强锚点 |
| model/Part.kt | ✓ | 中文 | 16 种 part 类型（API 原词）；"SSE 播种""Shell 卡片""压缩摘要"；Permission/Question 两子类无序列化分支（见冲突#13 备注） |
| model/PendingMessage.kt | ✓ | 中文 | "堆积消息（turn 结束后待发送的本地暂存消息）"——pending=堆积译名锚点 |
| model/PermissionState.kt | ✓ | 无注释 | 与 SseEvent.PermissionAsked 字段同构（双定义，见冲突#12 备注） |
| model/PromptPart.kt | ✓ | 中文 | "提示部分的领域模型"——part 一词第二概念 |
| model/ProviderConfig.kt | ✓ | 中文 | 文件名与主类型错位：内含 GlobalConfig/GlobalConfigPatch/ProviderAuthMethod/ProviderOauthAuthorization/ProviderConnectionStatus；显式区分 ServerConfig |
| model/ProviderInfo.kt | ✓ | 无注释 | provider 领域模型三定义之一 |
| model/ProvidersResponse.kt | ✓ | 中文 | ProviderCatalog/ModelCatalog："目录"（catalog）译法 |
| model/QuestionState.kt | ✓ | 中文 | 仅 2 条 V2 form 注释；与 SseEvent.QuestionAsked.Question 同构双定义 |
| model/ServerConfig.kt | ✓ | 中文 | "存储的服务器连接详情"；sameBackend 归一化（backlog #34）；apiVersion vs serverVersion 两"版本" |
| model/ServerConnection.kt | ✓ | 中文 | 服务器能力位（#172）——版本 seam 词条强锚点；"堆积队列"字样 |
| model/Session.kt | ✓ | 中文 | "OpenCode 对话会话"；revert/share/compacting/archived 字段 |
| model/SessionFSMState.kt | ✓ | 中文 | 两层状态 Core/Activity；"陈旧检测"；Activity 注释含 Compacting（与 SessionStateRepository 对照） |
| model/SessionNextEvent.kt | ✓ | 中文 | session.next.* 27 个事件变体（API 原词全量）；红点时钟域/turn/上下文占用注释群 |
| model/SessionStateFSM.kt | ✓ | 中文 | FSM 纯函数；"僵尸判定""L2 stale 检测""REST 校验"修复史注释 |
| model/SessionStatus.kt | ✓ | 中文 | Idle/Busy/Asking/Retry；Asking=客户端合成态（并入说明） |
| model/ShellJob.kt | ✓ | 中文 | "V2 后台 shell 命令（非交互）"vs Pty 对比；"输出分页游标（cursor）"——cursor 第二义 |
| model/SseEvent.kt | ✓ | 中文 | SSE 事件全集；"Session Next 事件——细粒度实时状态"（与 SessionNextEvent KDoc"细粒度会话事件"两叫法）；JsonElement import 注释失实（#6） |
| model/StepProgressInfo.kt | ✓ | 中文 | "步骤进度"（step=步骤） |
| model/Tag.kt | ✓ | 中文 | "内置收藏标签""收藏星标" |
| model/TerminalEvent.kt | ✓ | 中文 | "PTY 终端流事件"（WebSocket）——"终端"第二概念 |
| model/ToolProgressInfo.kt | ✓ | 中文 | "subagent Running 期子会话推断源" |
| model/ToolState.kt | ✓ | 中文 | "工具调用的生命周期"；判别字段 status（非 type）说明 |
| model/TransitionRecord.kt | ✓ | 中文 | "一次 FSM 转移的不可变记录" |
| model/Vcs.kt | ✓ | 无注释 | VcsChange/VcsStatus/VcsBranchInfo/VcsDiffMode |
| model/VcsFileDiff.kt | ✓ | 中文 | 显式区分 FileDiff（SSE 用）与 VcsFileDiff（unified patch） |

### domain/repository（15 文件）

| 文件 | 读 | 语言 | 备注 |
|---|---|---|---|
| repository/AgentRepository.kt | ✓ | 无注释 | 纯接口签名 |
| repository/ChatRepository.kt | ✓ | 中文 | 最大接口（341L）；"待处理""回退（undo）""乐观移除""权限卡片""触发后即忘"；Phase 3 残留注释（#4） |
| repository/DraftRepository.kt | ✓ | 中文 | suspend 设计说明（ANR 根治 backlog #38） |
| repository/FileRepository.kt | ✓ | 中文 | 项目（worktree）/探测目录/服务器路径，委托说明 |
| repository/McpRepository.kt | ✓ | 中文 | "单例共享可变 connection……后连接者赢" |
| repository/MessageCacheRepository.kt | ✓ | 中文 | 热表/归档桶/幽灵消息/种子化/SSE 双写/48ms 批/写放大 |
| repository/PendingMessageRepository.kt | ✓ | 中文 | "堆积消息仓库""未发条数""原子弹出""推进管线 peek→send→delete""状态补偿心跳扫描"；V2 execution.succeeded 字样 |
| repository/ProviderRepository.kt | ✓ | 中文 | provider 目录/全局配置/认证/OAuth/销毁全局实例 |
| repository/ServerConfigRepository.kt | ✓ | 中文 | 服务器 CRUD；"健康检查（连接测试）" |
| repository/ServerRepository.kt | ✓ | 中文 | 聚合接口（ISP 拆分）；resolveConnection |
| repository/SessionRepository.kt | ✓ | 中文 | 会话生命周期全集；"摘要（压缩）会话""未读提示判定""回退（fallback 义）"；Phase 3 残留（#4） |
| repository/SessionStateRepository.kt | ✓ | 中文 | 单一真相源/对账/L3 恢复/L3 僵尸自愈/补漏；activityFlow 注释列举不全（#1） |
| repository/SettingsRepository.kt | ✓ | 中文 | 标签/收藏/"一键已读""小红点"/"值域从客户端 now 变为服务器 completed"（红点时钟域锚点）；Phase 3 残留（#4） |
| repository/ToolSnapshotCache.kt | ✓ | 中文 | 进程级 LRU 快照缓存；Binder 1MB/导航序说明 |
| repository/VcsRepository.kt | ✓ | 无注释 | 纯接口签名 |

### domain/usecase（28 文件）

| 文件 | 读 | 语言 | 备注 |
|---|---|---|---|
| usecase/CreateDirectoryUseCase.kt | ✓ | 中文 | "临时会话 + shell 执行 + 探测 + finally 清理"；agent="build" 硬编码 |
| usecase/DeleteSessionUseCase.kt | ✓ | 中文 | 单行 KDoc 委托说明 |
| usecase/FindFilesUseCase.kt | ✓ | 无注释 | 纯委托 |
| usecase/GetFileContentUseCase.kt | ✓ | 中文 | 单行 KDoc |
| usecase/GetFileDiffUseCase.kt | ✓ | 中文 | "VCS 文件差异" |
| usecase/GetServerPathsUseCase.kt | ✓ | 中文 | "home/worktree 等" |
| usecase/GetSettingsFlowUseCase.kt | ✓ | 中文 | "供 Phase 4 SettingsViewModel 使用"——阶段残留（#5） |
| usecase/GetVcsStatusUseCase.kt | ✓ | 中文 | "VCS 状态" |
| usecase/ListDirectoryUseCase.kt | ✓ | 中文 | 单行 KDoc |
| usecase/ListProjectsUseCase.kt | ✓ | 中文 | "项目（worktree）"——project=worktree 等价注释 |
| usecase/ListSessionsUseCase.kt | ✓ | 中文 | "按目录/搜索/游标分页" |
| usecase/ManageAgentUseCase.kt | ✓ | 中文 | "管理 agents、命令和文件搜索" |
| usecase/ManagePermissionUseCase.kt | ✓ | 中文 | "回复、拒绝、列出待处理项"——pending=待处理 |
| usecase/ManageServerProvidersUseCase.kt | ✓ | 中文 | "供 HomeViewModel / ServerProvidersScreen 使用" |
| usecase/ManageSessionUseCase.kt | ✓ | 中文 | KDoc 职责列举过期（#2） |
| usecase/ManageTerminalUseCase.kt | ✓ | 中文 | "管理终端操作"——实际是命令执行，非 PTY（冲突#9） |
| usecase/MessagePaginationUseCase.kt | ✓ | 中文 | 修复史注释密集；"服务器窗口语义""本地锚点""缓存优先""离线可浏览" |
| usecase/PaginationCursorPolicy.kt | ✓ | 中文 | 版本 seam 词条最强锚点："单一决策点""isV2 从 domain/UI 层绝迹" |
| usecase/PaginationFSM.kt | ✓ | 中文 | "防风暴语义""自动续载""指数退避（500ms→…上限 8s）""读尽" |
| usecase/PendingMessageDrainController.kt | ✓ | 中文 | "堆积队列手动放行入口""历史伤口" |
| usecase/ProbeDirectoryUseCase.kt | ✓ | 中文 | "探测目录是否存在且可访问" |
| usecase/SearchDirectoriesUseCase.kt | ✓ | 中文 | type=directory 说明 |
| usecase/SelectModelUseCase.kt | ✓ | 中文 | "加载 provider 目录以供选择 model" |
| usecase/SendMessageUseCase.kt | ✓ | 中文 | "向会话发送消息"，方法却名 sendPrompt→promptAsync（冲突#8） |
| usecase/ShareExportUseCase.kt | ✓ | 中文 | "分享、导出和压缩会话" |
| usecase/SubmitAnnotationsUseCase.kt | ✓ | 中文 | "将备注作为结构化 prompt 提交"——annotation=备注（冲突#6） |
| usecase/UndoRedoUseCase.kt | ✓ | 中文 | "撤销和重做消息（revert/unrevert 会话）"——三组对应词 |
| usecase/UpdateSettingsUseCase.kt | ✓ | 中文 | 单行 KDoc |

### domain/tracker（1 文件）/ domain/util（1 文件）

| 文件 | 读 | 语言 | 备注 |
|---|---|---|---|
| tracker/TokenStatsTracker.kt | ✓ | 中文 | 仅 1 条行内注释（CAS 丢更新）；TokenStats 含 contextWindow/lastContextTokens |
| util/CursorCodec.kt | ✓ | 中文 | V1/V2 游标编解码；OLDER("next")/NEWER("previous") 反直觉映射的多处强调 |

**语言现状统计（91 文件）**：中文 81 · 无注释 9（Draft / ProviderInfo / PermissionState / CreateSessionOpts / Vcs / FileContent / AgentRepository / VcsRepository / FindFilesUseCase）· 英文行内 1（McpServerStatus）· 混合 0。注释语言已高度统一为中文，与"注释未来统一中文"裁决基本相容；英文残留仅 1 文件的枚举值注释。

## 术语观察

"与 API 词一致?"列以 OpenCode API 原词（session/message/event/part/tool/agent/provider/model/compaction/step/shell/cursor 等）为基准。

| 概念 | 观察到的变体 | 位置（文件:行） | 与 API 词一致? |
|---|---|---|---|
| session | 会话 / 对话会话 / 临时会话 / 子会话 / 后台会话 / 活跃会话（active） | Session.kt:7；CreateDirectoryUseCase.kt:13；ToolProgressInfo.kt:15；ServerConnection.kt:13；ShellJob.kt:58 | ✅ session/sessionID |
| message | 消息 / 堆积消息 / 幽灵消息 / 消息骨架 | PendingMessage.kt:3；MessageCacheRepository.kt:59,20 | ✅ message/messageID |
| part（消息内容块） | Message Part / 消息中不同类型的内容 / parts | Part.kt:51 | ✅ part/partID |
| part（请求提示块） | 提示部分 / PromptPart | PromptPart.kt:6；SubmitAnnotationsUseCase.kt:11 | ✅（请求侧 API 词）但与上条同词异义 |
| turn | turn（不译）/ turn 结束 / session-turn | SessionNextEvent.kt:231-234；PendingMessage.kt:3；PendingMessageRepository.kt:11；AppSettings.kt:26（showTurnDividers 无注释）；Part.kt:32 | ⚠️ session-turn 是 API 原词；"turn"整体保留英文 |
| event | SSE 事件 / 细粒度会话事件 / Session Next 事件 / Activity 事件 / Core 事件 | SseEvent.kt:9,251；SessionNextEvent.kt:53；SessionStateFSM.kt:29,37 | ✅ event |
| streaming | 流式 / 流式活动 / 文本流 / 推理流 / 流式内容 / Streaming | SessionStateRepository.kt:13；SessionNextEvent.kt:90,114；SseEvent.kt:65；SessionFSMState.kt:12 | ✅（Streaming 为派生态名） |
| compaction | 压缩 / 上下文压缩 / 压缩摘要 / 摘要（压缩）会话 | SessionNextEvent.kt:265；SessionFSMState.kt:20；Part.kt:209；SessionRepository.kt:122；ShareExportUseCase.kt:9 | ✅ compaction（中文变体多） |
| cursor（消息分页） | 翻页游标 / 分页游标 / 服务器游标 / 本地锚点游标 / 归档时间游标 / 网络分页游标 | PaginationCursor.kt:4,23-27；PaginationFSM.kt:63；PaginationCursorPolicy.kt:22 | ✅ cursor（V2） |
| cursor（shell 输出偏移） | 输出分页游标（cursor: Long 字节偏移） | ShellJob.kt:39；ShellOutput（ShellJob.kt:76-84） | ⚠️ 同名不同义 |
| pending message | 堆积消息 / 堆积队列 / 待发送 / 未发条数 / 放行（drain）/ 推进 | PendingMessage.kt:3；PendingMessageRepository.kt:7,42；PendingMessageDrainController.kt:4,9；ServerConnection.kt:13（"堆积队列"） | ⚠️ 客户端本地概念，API 无对应词 |
| pending permission/question | 待处理的权限/问题请求 / 待处理项 / 待答问题 / pending question | ChatRepository.kt:50-69,139-146；ManagePermissionUseCase.kt:9；SessionStateRepository.kt:37；SessionStatus.kt:16 | ✅（服务器概念）但与上条共用"pending/待处理"字样 |
| revert | 回退（undo）/ 取消回退（redo）/ 撤销和重做 / revert/unrevert / 回退状态 | ChatRepository.kt:115-122,262-269；UndoRedoUseCase.kt:7；Session.kt:23 | ✅ revert（V2 API） |
| fallback 义"回退" | 回退返回本地缓存 / 回退当前服务器 / V1 降级 / 回落 | MessagePaginationUseCase.kt:75,121；SessionStateRepository.kt:34；PaginationCursor.kt:11 | ❌ 与 revert 撞中文名 |
| 未读红点 | 红点误报 / 小红点 / 未读提示 / 服务器 completed 时刻 | SessionNextEvent.kt:234；SettingsRepository.kt:77-92；SessionRepository.kt:45-48 | ❌ 纯客户端域词（红点时钟域词条覆盖） |
| 归档 | 归档会话（archive/unarchive）/ 归档桶 / 归档时间游标 / 分层存储 | SessionRepository.kt:97-107；MessageCacheRepository.kt:59-71；PaginationCursor.kt:12 | ✅ archive；"归档桶"为存储层延伸词 |
| 热表 | 热表最老边界 / 热表数据 / 不在热表 | PaginationCursor.kt:11-17；MessageCacheRepository.kt:47 | ❌ 本地缓存黑话（hot table） |
| FSM/状态机 | 有限状态机 / 会话状态机 / 分页状态机 / 纯函数 / 两层架构（Core/Activity） | SessionStateFSM.kt:3-14；PaginationFSM.kt:6-13；SessionFSMState.kt:4 | ❌ 客户端架构词（CONTEXT.md 必需协作者词条） |
| stale/僵尸 | 陈旧检测 / L2 stale 检测 / 僵尸判定 / 僵尸自愈（L3） | SessionFSMState.kt:5；SessionStateFSM.kt:125-127；SessionStateRepository.kt:45 | ❌ 同一超时概念四种叫法 |
| provider | provider / 目录（catalog）/ ProviderCatalog / ProviderInfo / 连接状态 | ProvidersResponse.kt:14-27；ProviderConfig.kt:48-53；ProviderInfo.kt:3 | ✅ provider；领域模型三重定义 |
| agent | agent / 切换 Agent / sub-agent / subagent / 子会话 | AgentInfo.kt:4；SessionNextEvent.kt:63；SseEvent.kt:95；ToolProgressInfo.kt:15；ChatRepository.kt:196 | ✅ agent；sub-agent/subagent 拼写不统一 |
| tool | 工具调用 / 工具状态 / 工具执行进度 / 工具卡片 / 工具快照缓存 | ToolState.kt:27；ToolProgressInfo.kt:4；ChatRepository.kt:229-237；ToolSnapshotCache.kt:7 | ✅ tool/callID |
| shell | 后台 shell 命令（非交互）/ Shell 卡片 / shell 执行 / 输出捕获到文件 | ShellJob.kt:8-17；Part.kt:100-104；CreateDirectoryUseCase.kt:12-17 | ✅ shell/shellID |
| 终端 | "终端操作"（实为命令执行）/ PTY 交互式终端 / PTY 终端流事件 | ManageTerminalUseCase.kt:8；ShellJob.kt:14；TerminalEvent.kt:4 | ❌ Terminal 一词错位（见冲突#9） |
| permission | 权限请求 / 权限卡片 / 权限自动批准规则 / 待处理权限 | SseEvent.kt:85-104；AutoApproveRule.kt:5-8；ChatRepository.kt:139 | ✅ permission |
| question | 问题事件 / 问题请求 / 问题卡片 / 待答 | SseEvent.kt:106-147；SessionStateRepository.kt:37 | ✅ question |
| annotation/note | 标注（选区）/ 备注（文件/总体/具体）/ 修改备注 | Annotation.kt:4,19；AnnotationPromptBuilder.kt:7-16；SubmitAnnotationsUseCase.kt:10 | ❌ 客户端概念，中文名分裂 |
| 分页行为 | 翻页加载更早 / 自动续载 / 防风暴语义 / 指数退避 / 读尽 / 服务器窗口语义 / 本地锚点 | MessagePaginationUseCase.kt:99-108；PaginationFSM.kt:15-28；PaginationCursorPolicy.kt:14 | ✅（版本 seam 词条覆盖） |
| 合并策略 | SSE 优先（SSE_PRIORITY）/ REST 真相源（REST_AUTHORITY）/ 仅补充（APPEND_ONLY）/ 更长文本胜出 | MergeStrategy.kt:4-10；ChatRepository.kt:249-253 | ❌ 客户端缓存策略词 |
| 对账/校验 | REST 状态校验 / 会话状态对账 / L3 恢复 / 状态补偿心跳扫描 | SessionStateRepository.kt:39-48；PendingMessageRepository.kt:45 | ❌ 客户端一致性机制词 |
| 补漏 | SSE 断连窗口消息补漏 / cursor 增量拉取 / 回填（hydrate）缓存 | SessionStateRepository.kt:50-55；SessionRepository.kt:204 | ❌ |
| seed | SSE 播种的 parts / 种子化 | Part.kt:33-36；MessageCacheRepository.kt:10 | ❌ seed 两种中文译法 |
| usage/tokens | token 用量 / 上下文占用量 / 上下文窗口 / cache.read 历史累计 vs 单次快照 | SessionNextEvent.kt:302-331；TokenStatsTracker.kt:18 | ✅ tokens/cost/contextWindow |
| snapshot | Part.Snapshot（part 类型）/ 工具快照缓存 / 权限映射快照 | Part.kt:168-174；ToolSnapshotCache.kt:7；ChatRepository.kt:307 | ✅ snapshot（API part 类型）；多义 |
| project/worktree | 项目（worktree）/ Worktree 事件 / 服务器路径集合 | ListProjectsUseCase.kt:8；SseEvent.kt:244-249；FileNode.kt:18 | ✅ project/worktree |
| 版本/能力 | API 版本标识 / 服务器版本号 / 能力位 / 版本差异收编单一决策点 | ApiVersion.kt:6；ServerConfig.kt:18-21；ServerConnection.kt:6；PaginationCursorPolicy.kt:10-17 | ✅（版本 seam 词条覆盖） |
| 卡片（UI） | 权限卡片 / 问题卡片 / 工具卡片 / Shell 卡片 / 分割线卡片 | ChatRepository.kt:272-289；SseEvent.kt:202；Part.kt:100,210 | ❌ UI 文案词已渗入 domain 注释 |
| 停止 | 中止运行中的会话（abort）/ 强制完成（forceComplete）/ finish（stop/tool-calls/…） | SessionRepository.kt:83；SessionStateFSM.kt:22-23；SessionNextEvent.kt:231 | ✅ abort/finish |
| 发送 | 发送消息（sendMessage）/ 异步发送 prompt（触发后即忘）/ sendPrompt / 入队/队首 | ChatRepository.kt:91-112；SendMessageUseCase.kt:9,15；PendingMessageRepository.kt:18-37 | ✅ prompt/promptAsync |

## 失实注释

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| repository/SessionStateRepository.kt:24 | "每台服务器的流式活动（Waiting/Streaming/ToolCalling）" | `SessionActivity` 实为 4 值：Waiting/Streaming/**ToolCalling/Compacting**（SessionFSMState.kt:7-22，Compacting 为 data class）；SessionStateFSM.kt:48-53 亦处理 CompactionStarted/Ended | 列举补全 Compacting 或改为"见 SessionActivity" |
| usecase/ManageSessionUseCase.kt:9 | "管理会话生命周期（加载、刷新、创建、分叉、重命名）" | 同类实际方法还有 abortSession、deleteMessage、deleteMessagePart、archiveSession、unarchiveSession、importSession（本文件 L33-50）——KDoc 写于方法子集时代 | 职责列举更新为全集或删除括号列举 |
| model/AnnotationPromptBuilder.kt:14,42 | "注格式为<行号1:字符索引>-<行号2:字符索引>" | 实际输出 `positionLabel = "[$startLine:$startCol-$endLine:$endCol]"`（Annotation.kt:23-24），且 startCol 注释明确"基于 1"的**列**（Annotation.kt:15-17）；带方括号、是列号而非"字符索引" | 措辞改为"行号:列号（均基于 1）"并补方括号格式 |
| repository/ChatRepository.kt:25-26（同款：SessionRepository.kt:13-14、SettingsRepository.kt:9-10） | "与 spec §4.1.1 对齐。由 Data 层在 Phase 3 实现"（getMessagesFlow 另有"Phase 3 实现：委托给…"） | Data 层实现早已存在（MessageCacheRepository.kt:7 自述"domain 层依赖，data 层实现"）；"Phase 3/spec §4.1.1"是脚手架期计划用语，与当前代码状态无关 | 删除阶段性计划语，保留实现归属说明 |
| usecase/GetSettingsFlowUseCase.kt:10 | "供 Phase 4 SettingsViewModel 使用" | SettingsViewModel 已是现存消费方（阶段编号无当前意义）；同型残留 | 删除"Phase 4"或改为实际消费方 |
| model/SseEvent.kt:6 | "注：保留 JsonElement 导入以保持向后兼容；V2 metadata 使用 Map<String, String>" | 本文件已无任何 `JsonElement` 类型引用（全文检查：metadata 均为 Map<String,String>）；未使用的 import 不产生运行时兼容作用，注释理由不成立 | 删除未使用 import + 注释，或改为真实的保留理由 |
| model/AutoApproveRule.kt:7,22-23 | "匹配 [toolName] + [sessionId] + [directoryPattern]"、"工具名必须匹配（精确匹配或通配符）" | `PermissionAsked` 并无 toolName 字段（SseEvent.kt:87-98，只有 permission: String 与 tool: ToolRef?）；实际比较 `event.permission != toolName`（本文件 L23）——把"权限名"当"工具名" | 注释明确"以 permission 字段（权限/工具名字符串）匹配"，或说明两者在该 API 中同形 |
| model/SessionStateFSM.kt:7（同款 SessionFSMState.kt:27） | "第 1 层（Core）：Idle / Busy / Retry —— 镜像服务器的 SessionStatus" | `SessionStatus` 枚举还含 **Asking**——客户端列表层合成态（SessionStatus.kt:16-19；本文件 L109-115 也处理 Asking 并注释"服务器 SSE 不下发"）；"镜像服务器"与合成态并存有张力（文件内已自我澄清，属轻微微失实） | 表述改为"镜像服务器状态 + 客户端合成 Asking" |

## 待裁决冲突

1. **pending 一词两义（堆积 vs 待答）**｜"堆积消息/堆积队列/未发/放行/推进"（PendingMessage 家族：PendingMessage.kt、PendingMessageRepository.kt、PendingMessageDrainController.kt、ServerConnection.kt:13"堆积队列"、AppSettings.kt:27"堆积/TODO 常驻抽屉"）vs "待处理的权限/问题/待答"（ChatRepository.kt listPending*、ManagePermissionUseCase.kt:9"待处理项"、SessionStateRepository.kt:37"待答问题"）｜范围：model/repository/usecase 三目录 7+ 文件｜事实备注：前者是客户端本地暂存队列（API 无词），后者是服务器侧未答复请求（API 概念）；中文"待处理/堆积"交叉混用，UI 抽屉名"堆积/TODO"又把 pending message 与 todo 并置。
2. **cursor 一词多义**｜消息分页游标（token：PaginationCursor/CursorCodec/MessagePage/PaginationFSM）vs shell 输出分页游标（Long 字节偏移：ShellJob.kt:39-40、ShellOutput、ChatRepository.getShellOutput）vs 会话列表游标（ListSessionsUseCase cursor）｜范围：model/usecase/util/repository 8 文件｜事实备注：CONTEXT.md「版本 seam」词条的"分页游标策略"仅指第一种；三种 cursor 的语义、类型、失效条件完全不同。
3. **"回退"一词两义（revert vs fallback）**｜revert/unrevert（"回退（undo）/取消回退（redo）/撤销和重做/回退状态"：ChatRepository.kt:115-122,262-269、UndoRedoUseCase.kt:7、Session.kt:23）vs fallback（"网络失败回退返回本地缓存"：MessagePaginationUseCase.kt:75,121；"回退当前服务器"：SessionStateRepository.kt:34；"V1 降级"）｜范围：4+ 文件｜事实备注：同一汉字词承载 undo 与 fallback 两个无关概念，注释读者需靠上下文分辨。
4. **compaction 中文变体群**｜压缩（SessionNextEvent.kt:265、CompactionStateInfo）／上下文压缩（SessionFSMState.kt:20）／压缩摘要（Part.kt:209）／摘要（压缩）会话（SessionRepository.kt:122、ShareExportUseCase.kt:9）｜范围：6 文件｜事实备注：API 原词 compaction 单一；中文至少 4 种叫法，其中"摘要（压缩）"把 summarize/compact 两个动作并置。
5. **Part vs PromptPart（part 一词两概念）**｜"Message Part——消息中不同类型的内容"（Part.kt:51，16 个 API part 类型）vs "提示部分的领域模型（文本、文件、图片等）"（PromptPart.kt:6，请求体组成）｜范围：ChatRepository.sendMessage(parts: List<Part>) vs promptAsync(parts: List<PromptPart>)、SendMessageUseCase.sendPrompt｜事实备注：同为 part 后缀，数据结构、流向、生命周期不同；SendMessageUseCase KDoc 称"向会话发送消息"却走 promptAsync 路径。
6. **annotation 中文名分裂（标注 vs 备注）**｜"标注"（Annotation.kt:4"用户对代码选区的标注"）vs "备注"（AnnotationPromptBuilder.kt"文件备注/总体备注/具体备注"、SubmitAnnotationsUseCase.kt:10"将备注作为结构化 prompt 提交"、Annotation.note"用户的修改备注"）｜范围：model/usecase 3 文件｜事实备注：同一 Annotation 概念两个中文名并存；发出的提示文本里全用"备注"。
7. **归档（archive）一词两义**｜会话归档操作（SessionRepository.archive/unarchive、Session.time.archived）vs 消息归档存储层（"归档桶/归档时间游标/分层存储/prune 语义"：MessageCacheRepository.kt:59-71、PaginationCursor.kt:12）｜范围：4 文件｜事实备注：前者是 API/用户操作，后者是本地 Room 分层缓存机制，无服务器对应。
8. **发送动词群**｜sendMessage（Part 列表）/ promptAsync"异步发送 prompt（触发后即忘）" / sendPrompt（SendMessageUseCase）/ 入队 enqueue / 放行 continueFromList / 推进 dequeueHead｜范围：ChatRepository、SendMessageUseCase、PendingMessageRepository、PendingMessageDrainController｜事实备注：用户可见的"发送"动作实际可能走 prompt 直发或堆积队列延后发；注释未统一"发送"与"提交 prompt"的区分。
9. **"终端"（Terminal）错位**｜ManageTerminalUseCase"管理终端操作"（实际 executeCommand/runShellCommand 服务端命令执行）vs TerminalEvent"PTY 终端流事件"（WebSocket PTY）vs ShellJob"与 Pty 交互式终端（PtyInfo）是不同概念"｜范围：3 文件｜事实备注：Terminal 命名给了非 PTY 的命令执行 use case；PTY/终端/命令执行三层概念在注释中靠对比句维持区分。
10. **stale/僵尸/陈旧 叫法群 + L2/L3 层级编号**｜"陈旧检测"（SessionFSMState.kt:5）/"L2 stale 检测"（SessionStateFSM.kt:126，英文）/"僵尸判定"（SessionStateFSM.kt:125）/"L3 恢复/L3 僵尸自愈"（SessionStateRepository.kt:44-45）/"状态补偿心跳扫描"（PendingMessageRepository.kt:45）｜范围：3 文件｜事实备注：同一"事件超时/状态不可信"概念族 5 种叫法；L2/L3 分层编号在 domain 层引用但未在 domain 定义（源于 data 层/文档）。
11. **provider 领域模型三重定义 + "目录"译法**｜ProviderInfo（enabled/connected，ProviderRepository.loadProviders）vs ProviderCatalog/ModelCatalog（"目录视图"，loadProviderCatalog）vs ProviderConnectionStatus（"连接状态目录"）｜范围：ProviderInfo.kt、ProvidersResponse.kt、ProviderConfig.kt、两个 use case｜事实备注：catalog 统一译"目录"，与文件系统"目录（directory）"撞中文名；三模型字段重叠。
12. **同构双定义（SSE 事件类 vs 状态类）**｜PermissionState ≈ SseEvent.PermissionAsked（字段几乎相同）、QuestionState.Question ≈ SseEvent.QuestionAsked.Question（结构相同，均含 V2 form key/value 注释）、SessionNextEvent.ToolProgressInfo 之外另有 ToolProgressInfo/StepProgressInfo/CompactionStateInfo（KDoc 自称"对应 data.repository.handler.*"）｜范围：model 5 文件｜事实备注：事件形态与常驻状态形态两套类型并存，注释以"对应 data.dto/data.repository.handler.X"维系映射——跨层同名类是术语检索的主要噪声源。
13. **PartSerializer 缺失分支（定义-序列化不一致）**｜Part.Permission、Part.Question 已定义（Part.kt:238-252）但 PartSerializer 的 type when（Part.kt:17-46）无 "permission"/"question" 分支，只能靠 else 字段推断兜底（两类的字段 message/question 均不在推断键列表 → 落 Unknown）｜范围：Part.kt｜事实备注：非注释问题，属代码事实；若 API 存在这两种 part type，注释"按顶层字段推断，避免降级为 Unknown"对这两类不成立。
14. **sub-agent 拼写与译名**｜"sub-agent 来源标签"（SseEvent.kt:95）/"subagent Running 期子会话推断源"（ToolProgressInfo.kt:15）/"前台可后台化工具（subagent）"（ChatRepository.kt:196）｜范围：3 文件｜事实备注：sub-agent/subagent 两种拼写；"子会话"（child session）与 subagent 关系隐含未定义。
15. **turn 结束信号多事件家族**｜"自然成功 turn 结束（V2 execution.succeeded / V1 session.status(idle)）"（PendingMessageRepository.kt:11）vs step.ended+finish=stop（SessionNextEvent.kt:231-236）vs SseIdle/RestValidation（SessionStateFSM）｜范围：3 文件｜事实备注：domain 注释里"turn 结束"至少挂接三种服务器信号（execution.*/session.status/step.ended），execution.* 前缀未在 SessionNextEvent 事件集中出现——与 CONTEXT.md「流式 turn」词条定义"completed 时间戳为空"的判定口径需要对照裁决。
16. **domain 接口承载 UI 状态**｜ChatRepository"UI 状态"分区：getToolExpandedStates/setToolExpanded"工具卡片展开状态"（ChatRepository.kt:226-237）｜范围：ChatRepository｜事实备注：工具卡片展开是纯 UI 抽屉状态，却以 domain 接口方法暴露（违反依赖方向直觉的既有事实）；与 CONTEXT.md「状态簇」词条的"UI 读簇对象"边界相关。
17. **SessionStatus 大小写与 Asking 合成态**｜注释小写 idle/busy/retry（SessionStateRepository.kt:21、AGENTS.md 同）vs 枚举大写 Idle/Busy/Retry/Asking；Asking 为客户端合成（SessionStatus.kt:16）｜范围：3 文件｜事实备注：见失实#8；大小写两套并存无实害但影响检索一致性。

### 与 CONTEXT.md 既有 8 词条的映射（事实记录）

| CONTEXT.md 词条 | domain 层锚点（事实） | 备注 |
|---|---|---|
| 渲染供给 | 无直接锚点；相邻词：PaginationFSM"自动续载/UI 停止自动分页"（PaginationFSM.kt:33-48） | 概念本体在 UI 层 |
| 流式 turn | TimeInfo.completed（Message.kt:12-15）；SessionActivity.Streaming"正在接收文本流"（SessionFSMState.kt:12）；"turn 结束"注释群（SessionNextEvent.kt:231、PendingMessage.kt:3、PendingMessageRepository.kt:11） | 数据锚点齐全；turn 结束信号多家族见冲突#15 |
| 跳转稳定窗口 | 无直接锚点；Avoid 词"跳转锁=autoLoad 抑制"对应 PaginationFSM.autoLoadPaused（PaginationFSM.kt:47） | 冻结窗口本体在 UI 层 |
| 红点时钟域 | StepEnded.timestamp"用服务器时刻记录 turn 结束……红点误报"（SessionNextEvent.kt:233-234）；"服务器 completed 时刻"（SessionRepository.kt:45-46）；"值域从客户端 now 变为服务器 completed"迁移（SettingsRepository.kt:91） | 词条裁判的代码事实全部在场 |
| 必需协作者 | SessionStateFSM/TransitionResult/isSuspicious/forceComplete（SessionStateFSM.kt 全文）；协作者注入本体在 data 层 SessionStateService（SessionStateRepository.kt:15 引用） | domain 侧为 FSM 纯函数半边 |
| 状态簇 | domain 无直接锚点；读侧接口 ChatRepository/SessionStateRepository 为簇的供给面；冲突#16 的 UI 状态泄漏相关 | 概念本体在 UI/ViewModel 层 |
| 版本 seam | 最强锚点群：PaginationCursorPolicy"单一决策点/isV2 从 domain/UI 层绝迹"（#172）、PaginationCursor"V2 服务器窗口语义/死循环"、CursorCodec V1/V2 格式、ServerCapabilities"能力位——UI 门控只读能力不读版本"、ApiVersion.isV2 定义点 | 词条与 domain 代码逐句对得上 |
| 连接生命周期协调 | domain 仅见 ServerConnection/resolveConnection（ServerConnection.kt、ServerRepository.kt:20）；"两条 SSE 连接投递重复事件"防线（ServerConfig.kt:51） | 协调本体在 data/Service 层（词条 Avoid"Service 管连接"） |

## 附录：范围外文件（未盘点，仅备核）

`app/src/test/kotlin/dev/leonardo/ocbeacon/domain/` 下 31 个测试文件（model 9 / repository 4 / tracker 1 / usecase 16 / util 1）：AnnotationPromptBuilderTest、ApiResultTest、AutoApproveRuleTest、DraftTest、LinkClassifierTest、OffsetConverterTest、SerializationTest、SessionNextEventTest、SessionStateFSMTest、AgentRepositoryTest、ChatRepositoryTest、SessionRepositoryTest、ToolSnapshotCacheBoundedTest、TokenStatsTrackerConcurrencyTest、CreateDirectoryUseCaseTest、DeleteSessionUseCaseTest、FindFilesUseCaseTest、GetSettingsFlowUseCaseTest、ListSessionsUseCaseTest、ManagePermissionUseCaseTest、ManageServerProvidersUseCaseTest、ManageSessionUseCaseExtendedTest、ManageSessionUseCaseTest、MessagePaginationUseCaseTest、PaginationCursorPolicyTest、PaginationFSMTest、SendMessageUseCaseTest、SubmitAnnotationsUseCaseTest、UpdateSettingsUseCaseTest、WorkspaceUseCasesTest、CursorCodecTest。若后续 Phase 需要"注释修订"覆盖测试文件，需另行盘点。

---
*盘点方法：glob 三种独立 pattern 交叉验证（union=122，main/test 过滤后 91）；91 文件全文精读（read 全行，大文件单批；未使用 grep 替代阅读）。生成于术语盘点 Phase 1，只录事实，未做裁决。*
