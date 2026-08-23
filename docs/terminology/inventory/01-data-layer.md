# 盘点：data 层（app/src/main/kotlin 下 data 目录全部 Kotlin 文件）

> Phase 1 事实收集（只读盘点，不做术语裁决）。规范名权威源=OpenCode API 术语；注释未来统一中文（本文件盘点现状）；不改标识符；UI 文案纳入。
> 范围：app/src/main/kotlin/dev/leonardo/ocbeacon/data/**/*.kt = 114 文件，**全部逐文件精读完毕**（大文件 V1ApiClient 937 行 / V2ApiClient 1773 行 / MessageEventHandler 1164 行 / SseClientV2 491 行 / SessionStateService 729 行等分段读全）。
> 另核查 app/src/main/java/**/*.kt = 0 文件（无遗漏）。
> 已对照仓库根 CONTEXT.md 既有 8 词条（渲染供给/流式 turn/跳转稳定窗口/红点时钟域/必需协作者/状态簇/版本 seam/连接生命周期协调）——呼应点见待裁决冲突 C12。

## 覆盖清单（114 文件全部 ✓ 已精读；格式：路径 ✓ 语言现状 — 一句话备注）

- :data/api/ApiClient.kt ✓ 中文 — 共享 HttpClient+Json 持有者 + directoryHeader 扩展（x-opencode-directory 头）
- :data/api/AuthHeader.kt ✓ 中文 — 认证头统一扩展 auth(conn)，#114(D2-27)，替代 147 处内联
- data/api/ApiClient.kt ✓ 中文 — 共享 HttpClient+Json 持有者 + directoryHeader 扩展（x-opencode-directory 头）
- data/api/AuthHeader.kt ✓ 中文 — 认证头统一扩展 auth(conn)，#114(D2-27)，替代 147 处内联
- data/api/NetworkMonitor.kt ✓ 中文 — ConnectivityManager 封装，NetworkState 四态 + isOnline
- data/api/NonJsonResponseException.kt ✓ 中文 — HTML SPA fallback 防御 rejectHtmlResponse，D2-22/#121
- data/api/RestSessionStatusInfo.kt ✓ 中文 — REST GET /session 轮询状态快照（idle/busy/retry），校正 SSE 派生状态
- data/api/RetryPolicy.kt ✓ 中文 — 指数退避重试策略 + retryWithPolicy
- data/api/SseClient.kt ✓ 中文 — V1 SSE 客户端（/global/event），行级/事件级 OOM 防护、心跳超时、冷却跟踪
- data/api/file/FileApi.kt ✓ 中文（接口方法 KDoc 部分，多数方法无注释） — 文件/VCS/项目域 API 的 V1/V2 分发门面（isV2 判定全部收口在此类门面）
- data/api/message/MessageApi.kt ✓ 中文 — 消息/权限/问题 API 的 V1/V2 分发门面；V2 form(V1 question) 契约差异收口
- data/api/message/PromptAdmission.kt ✓ 中文 — V2 prompt 响应回执，发送即显示的本地播种源
- data/api/provider/ProviderApi.kt ✓ 中文 — 提供商/认证/配置 API 的 V1/V2 分发门面
- data/api/session/SessionApi.kt ✓ 中文 — 会话生命周期 API 门面；abort/interrupt、update/rename 的 V1/V2 词汇分裂都在此
- data/api/shell/ShellApi.kt ✓ 中文 — V2 专属后台 shell 命令 API；V1 全部空实现
- data/api/sse/parsers/MessageEventParser.kt ✓ 中文（行内注释） — message.* SSE 事件解析；V1/V2 part 字段归一化（tool/name、callID/id、双层 metadata）
- data/api/sse/parsers/MiscEventParser.kt ✓ 中文 — 杂项 SSE 事件解析（todo/workspace/file/mcp/installation/worktree/server）
- data/api/sse/parsers/ParserUtils.kt ✓ 无注释 — JsonObject.str 防御性字符串提取（message/data.message/error/type/name 兜底链）
- data/api/sse/parsers/PermissionEventParser.kt ✓ 中文 — permission.* SSE 事件解析；always 的 V2 Boolean/V1 List 兼容
- data/api/sse/parsers/PtyEventParser.kt ✓ 中文 — PTY 与命令 SSE 事件解析
- data/api/sse/parsers/QuestionEventParser.kt ✓ 中文 — question.* SSE 事件解析
- data/api/sse/parsers/SessionEventParser.kt ✓ 中文 — session.* + vcs/project/lsp SSE 事件解析；decodeSessionCompat V1(info 包装)/V2(扁平) 双格式兼容
- data/api/sse/parsers/SessionNextEventParser.kt ✓ 中文 — session.next.* 前缀匹配解析；Unknown/rawType/rawJson 兜底
- data/api/sse/parsers/SseEventParser.kt ✓ 中文 — SSE 事件解析策略接口（canParse/parse）
- data/api/system/SystemApi.kt ✓ 中文 — health/path/agent/command/skill/MCP API 门面
- data/api/terminal/TerminalApi.kt ✓ 中文 — PTY 生命周期 + 会话内 shell 命令 API 门面
- data/api/version/ApiVersionDetector.kt ✓ 中文 — V1/V2 API 版本探测（/api/health vs /global/health 双探 + 交叉验证 + knownVersion 排序）
- data/api/v1/V1ApiClient.kt ✓ 中文（少量 KDoc/行内，多数方法无注释） — V1 REST 全端点实现；V1 特征=URL 无 /api 前缀、响应无 data 包裹层（L56-59 注释✓）
- data/api/v2/SseClientV2.kt ✓ 中文 — V2 SSE 客户端（GET /api/event）；durable.seq 游标回调 + synthetic 两阶段通知缓存
- data/api/v2/V2EventParser.kt ✓ 中文 — V2 细粒度事件兜底解析器（execution/shell/compaction/usage/tool.progress）
- data/api/v2/V2FormMapper.kt ✓ 中文 — V2 提问三代契约映射（question(V1)→form→question.v2）；answer 构造
- data/api/v2/V2Mappers.kt ✓ 中文 — V2 REST 响应解包（data/cursor/location 包裹）+ Session/Message/Shell JSON→域模型转换器
- data/api/v2/V2SseMapper.kt ✓ 中文 — V2 细粒度生命周期事件→领域事件纯函数映射；derivePartId 派生规则
- data/api/v2/V2ApiClient.kt ✓ 中文 — V2 REST 全端点实现；V1/V2 端点词汇分裂的最大集合地
- data/di/DatabaseModule.kt ✓ 中文（2 条行内注释） — Room 数据库 + 4 DAO + 时钟源提供
- data/di/PendingMessageDrainModule.kt ✓ 中文 — PendingMessageDrainController domain 接口绑定（#176/#177）
- data/dto/common/ApiModels.kt ✓ 无注释 — ModelSelection/OutputFormat/PtySocket（WebSocket 帧封装）
- data/dto/request/ChatRequests.kt ✓ 无注释 — prompt_async 请求体 DTO
- data/dto/request/ConfigRequests.kt ✓ 无注释 — 配置 PATCH DTO
- data/dto/request/PtyRequests.kt ✓ 无注释 — PTY 创建/尺寸 DTO
- data/dto/request/QuestionRequests.kt ✓ 无注释 — V1 question reply DTO
- data/dto/request/ShellRequests.kt ✓ 无注释 — 会话内 shell 命令 DTO
- data/dto/response/ConfigResponses.kt ✓ 无注释 — 服务器配置响应 DTO
- data/dto/response/FileResponses.kt ✓ 中文（KDoc+行内） — /find 搜索、文件内容、VCS DTO
- data/dto/response/McpResponses.kt ✓ 无注释（行内枚举值） — MCP 状态与服务器配置 DTO
- data/dto/response/PermissionResponses.kt ✓ 中文 — 权限/问题请求 DTO；V1/V2 字段双轨
- data/dto/response/ProviderResponses.kt ✓ 无注释 — 提供商/模型目录 DTO（V1 形态；V2 由 V2ApiClient 组装回此形态）
- data/dto/response/PtyResponse.kt ✓ 无注释 — PTY 信息 DTO
- data/dto/response/ToolResponses.kt ✓ 无注释 — agent/命令/技能 DTO（文件名 ToolResponses 但内容是 agent/command/skill——命名错位，事实记录）
- data/dto/response/V2Responses.kt ✓ 无注释 — 杂项 DTO（todo/会话状态/shell/symbol/文件状态）——文件名 V2Responses 但多为 V1 形态 DTO（事实记录）
- data/github/ErrorReportService.kt ✓ 中文 — 错误上报服务（#151）：指纹双轨+查重+24h 防刷+正文构建
- data/github/GitHubApiClient.kt ✓ 中文 — GitHub Issues API 薄客户端（search/create issue/comment）
- data/github/GitHubDeviceFlowAuth.kt ✓ 中文 — GitHub App device flow 认证（#151）
- data/github/GitHubTokenStore.kt ✓ 中文 — GitHub token 加密存储（DataStore github_report）+ install-id
- data/local/ArchiveBucketDao.kt ✓ 中文 — 归档桶 DAO；#72 桶边界→消息级过滤修复
- data/local/ArchiveBucketEntity.kt ✓ 中文 — 归档桶实体（archive_buckets 表）
- data/local/ArchivedMessageDto.kt ✓ 中文 — 归档桶内单条消息 DTO
- data/local/CachedMessageEntity.kt ✓ 中文 — 消息缓存实体（cached_messages 表，BLOB 化 payload）
- data/local/CachedPartEntity.kt ✓ 中文 — 消息部件缓存实体（独立表防写放大）
- data/local/DatabaseRecovery.kt ✓ 中文 — Room 损坏自愈（仅 SQLiteDatabaseCorruptException 删库重建）
- data/local/LogDao.kt ✓ 中文 — 诊断日志 DAO（ERROR/FATAL 分级保留策略）
- data/local/LogEntity.kt ✓ 中文 — 诊断日志实体（logs 表，原 DiagnosticLogDatabase 等价迁移）
- data/local/LogStore.kt ✓ 中文 — 诊断日志存储（3 天/21 天/50 条 FATAL/10MB 预算修剪）
- data/local/MessageDao.kt ✓ 中文 — 消息缓存 DAO（增量 append UPSERT/游标分页/分块 IN/prune）
- data/local/MessageStore.kt ✓ 中文 — 消息本地缓存核心（热表 1000 条/归档桶 TLRU/delta 增量/对账替换）
- data/local/Migrations.kt ✓ 中文 — Room 迁移 v1→v4
- data/local/OcBeaconDatabase.kt ✓ 中文 — Room 数据库（v4，5 实体 4 DAO）
- data/local/PartDelta.kt ✓ 中文 — SSE delta 增量落盘 DTO（#97 H-6）
- data/local/PendingMessageDao.kt ✓ 中文 — 堆积消息 DAO（队列语义：peek/dequeue/appendToTail/applyOrder）
- data/local/PendingMessageEntity.kt ✓ 中文 — 堆积消息实体（pending_messages 表，2026-08-20 设计定稿）
- data/local/ToolOutputTruncator.kt ✓ 中文 — tool/reasoning 落库截断器（#79 P0+P1）
- data/local/ZstdCodec.kt ✓ 中文 — zstd 压缩编解码（解压需原始大小）
- data/mapper/ConfigMapper.kt ✓ 中文 — Config DTO↔领域映射
- data/mapper/FileMapper.kt ✓ 无注释（object FileMapper 整体无 KDoc） — 文件 DTO→领域映射
- data/mapper/PermissionMapper.kt ✓ 中文 — 权限 DTO↔领域映射；V2 action/resources 兜底
- data/mapper/ProviderMapper.kt ✓ 中文 — provider 目录 DTO→领域简化映射
- data/mapper/QuestionMapper.kt ✓ 中文 — 问题 DTO↔领域映射（字段名相同、类型异包）
- data/mapper/VcsMapper.kt ✓ 无注释 — VCS DTO→领域映射
- data/repository/AgentRepositoryImpl.kt ✓ 无注释 — agent/命令/文件搜索仓库（薄封装）
- data/repository/DiagnosticLogRepository.kt ✓ 中文 — 诊断日志仓库（脱敏+节流+DataStore 级别配置）
- data/repository/DraftDataStore.kt ✓ 中文 — 草稿 DataStore（旧 File 格式一次性迁移）
- data/repository/EventDispatcher.kt ✓ 中文 — SSE 事件分发器（handler 注册表替代单体 EventReducer；横切关注点）
- data/repository/FileRepositoryImpl.kt ✓ 中文 — 文件仓库（#137 全方法 IO 调度）
- data/repository/McpRepositoryImpl.kt ✓ 无注释 — MCP 仓库（状态+配置合并）
- data/repository/PendingMessagePipeline.kt ✓ 中文 — 堆积消息推进管线（边沿触发+状态补偿三触发器；at-least-once）
- data/repository/PendingMessageRepositoryImpl.kt ✓ 中文 — 堆积消息仓库实现（Room 持久化跨重启）
- data/repository/PermissionAutoApprover.kt ✓ 中文 — 权限自动批准规则（DataStore 持久化，#122 接线）
- data/repository/ServerDataStore.kt ✓ 中文 — 服务器配置 DataStore（密码加密 v1: 前缀）+ 健康检查/版本探测
- data/repository/ServerRepositoryImpl.kt ✓ 中文 — ServerRepository 实现（服务器 CRUD+提供商管理薄包装）
- data/repository/ServerTerminalRegistry.kt ✓ 中文 — 服务端终端工作区注册表（按服务器缓存 workspace）
- data/repository/SessionStateCollaborator.kt ✓ 中文 — FSM 必需协作者单一接线点（#174，与 CONTEXT.md'必需协作者'词条直接对应）
- data/repository/SettingsDataStore.kt ✓ 中文 — 应用设置 DataStore（语言镜像/已读状态/会话标签/模型可见性/默认模型）
- data/repository/SettingsRepositoryImpl.kt ✓ 中文 — SettingsRepository 薄委托
- data/repository/ChatRepositoryImpl.kt ✓ 中文 — ChatRepository 实现（桥接领域接口与 EventDispatcher/API；578 行）
- data/repository/SessionRepositoryImpl.kt ✓ 中文 — SessionRepository 实现（含 #91 listMessages 在途去重、状态同步）
- data/repository/ShellJobsStore.kt ✓ 中文 — 后台 shell 状态容器（单一真相源）
- data/repository/StreamingOwnershipRegistry.kt ✓ 中文 — 多服务器流式会话所有权注册表
- data/repository/SessionStateService.kt ✓ 中文 — 会话状态单一真相源（FSM+L2/L3/L4/L5 分层校验+SSE 断连补漏+僵尸判定，729 行）
- data/repository/UnreadBadgeService.kt ✓ 中文 — 红点时间源单一真相源（时钟域编码进事件类型 #171）
- data/repository/VcsRepositoryImpl.kt ✓ 无注释 — VCS 仓库薄封装
- data/repository/handler/MessageEventHandler.kt ✓ 中文 — 消息/part 共享状态存储+5 类消息事件 handler（1164 行；48ms delta 批处理+SSE 双写+合并策略）
- data/repository/handler/MiscEventHandler.kt ✓ 中文 — 杂项事件 handler（只管理 todos，其余日志确认）
- data/repository/handler/PermissionEventHandler.kt ✓ 中文 — 权限事件 handler（pending 管理+子会话聚合）
- data/repository/handler/QuestionEventHandler.kt ✓ 中文 — 问题事件 handler（pending 管理+REST 轮询合并+子会话聚合）
- data/repository/handler/SessionEventHandler.kt ✓ 中文 — 会话生命周期 handler（'会话 STATUS 不再在此跟踪——SessionStateService 是单一真相源'）
- data/repository/handler/SessionNextEventHandler.kt ✓ 中文 — session.next.* 实时状态跟踪（agent/model/工具进度/步骤/压缩/shell/usage）
- data/repository/handler/ShellJobsHandler.kt ✓ 中文 — 后台 shell 事件→ShellJobsStore 薄 handler
- data/repository/handler/SseEventHandler.kt ✓ 中文 — handler 策略接口
- data/terminal/PtyToTermlibAdapter.kt ✓ 中文 — PTY WebSocket→终端模拟器桥（#189 换件后通用 PTY 桥，历史名保留）
- data/terminal/RemoteTerminalSession.kt ✓ 中文 — 远程 PTY 版 Termux 终端会话（#189 换件；TerminalSession 桥接口）
- data/terminal/ServerTerminalWorkspace.kt ✓ 中文 — 服务端终端工作区（tab 生命周期/重连/resize 防抖；641 行）
- data/terminal/TerminalTabState.kt ✓ 中文 — 终端 tab 生命周期状态机+恢复策略纯函数（含真值表）
- data/security/SecretCipher.kt ✓ 中文 — Android Keystore AES/GCM 对称加密（v1: 前缀密文+解密记忆化）
- data/update/UpdateInstaller.kt ✓ 英文（行内少量） — APK 安装 Intent（unknown sources/FileProvider）
- data/update/UpdateModels.kt ✓ 中文（少量） — 更新元数据模型+校验策略（UpdatePolicy 纯函数）
- data/update/UpdateRepository.kt ✓ 中文（行内注释） — 应用内自更新（manifest/GitHub Release 双源+APK 校验）

## 术语观察（概念 × 观察到的变体 × 代表位置 × 与 API 词一致?）

| 概念 | 观察到的变体 | 位置（文件:行，代表点） | 与 API 词一致? |
|------|--------------|------------------------|----------------|
| 会话 | session / 会话 / 子会话(child session) / 主会话 | SessionApi.kt:15；SessionEventHandler.kt:120（子会话=parentID 指向） | session ✓（API 原词） |
| 会话 ID 字段 | sessionID(API) / sessionId(域) / sid(日志) / id(V1 session.id) | MessageEventParser.kt:36；V2ApiClient.kt:232；SessionStateService 日志 | sessionID ✓；id 为 V1 形态 |
| 消息 | message / 消息 / user 气泡(口语) / assistant 回复 | MessageApi.kt:34；ChatRepositoryImpl.kt:226-231 | message ✓ |
| 消息部件 | part / 部件 / 组件(个别) / content 元素(V2 REST) | MessageEventParser.kt:97-171；V2Mappers.kt:188（content 数组替代 parts） | part ✓（V2 REST 用 content，SSE 侧仍 part） |
| part 类型 | text/reasoning/tool/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent/session-turn(+本地扩展 shell/permission/question/unknown) | MessageEventParser.kt:97-171；MessageStore.kt:495-514 | ✓（本地扩展 4 个为客户端自有） |
| 流式增量 | delta / 增量 / token delta / 流式 | MessageEventParser.kt:47-59；MessageDao.kt:19 | delta ✓ |
| 轮次 | turn / 轮次 / step / execution(V2 权威信号) | PendingMessageEntity.kt:8（turn 结束）；V2EventParser.kt:60-73 | turn 为本地词；API 侧 V2=execution.started/succeeded、step |
| 提问 | question(V1/V2主干) / form(V2 中间契约) / 问题 | V2FormMapper.kt:21-24；MessageApi.kt:81-99 | question ✓；form 为 next-17430 中间契约（注释明言 stale surface 将移除） |
| 权限 | permission / 权限 / 工具权限名 | PermissionEventParser.kt:17；PermissionResponses.kt:13 | permission ✓（V1）；V2 用 action/resources 表达 |
| 权限回复 | reply once/always/reject / effect allow/deny(legacy) | MessageApi.kt:61；V2ApiClient.kt:872-877 | once/always/reject ✓；effect 为旧部署降级契约 |
| 工具 | tool / 工具 / 工具名(name V2/tool V1) / call_id / callID / id | MessageEventParser.kt:100-108；V2SseMapper.kt:270-276 | tool ✓；call_id 语义=事件字段 id（见失实#5） |
| 模型 | model / 模型 / ModelSelection{providerID,modelID} / model 对象{id,providerID,variant} | ApiModels.kt:13-16；V2ApiClient.kt:543 | modelID/providerID ✓（V2 演进为嵌套 model 对象） |
| 提供商 | provider / 提供商（中文注释全用） | ProviderApi.kt:13；V2ApiClient.kt:728 | provider ✓（中文注释'提供商'为翻译词） |
| 终端 | pty / PTY / 终端 / shell / tab | TerminalApi.kt:13-37；ShellApi.kt:12-17 | pty ✓、shell ✓（两概念并存：交互式 pty vs 后台 shell job） |
| 后台 shell 任务 | shell job / 后台 shell 命令 / ShellJob | ShellJobsStore.kt:13-28 | Shell.Info ✓（{id,status,command,cwd,shell,file,pid,exit}） |
| 事件流 | SSE / 事件流 / event stream / 全局事件流 | SseClient.kt:163-169；SseClientV2.kt:100-105 | /global/event(V1) ✓ /api/event(V2) ✓ |
| 心跳 | heartbeat / 心跳 / server.heartbeat / 注释帧 ': heartbeat' | SseClient.kt:25；SseClientV2.kt:197-217 | server.heartbeat ✓（另有注释帧形态） |
| 游标 | cursor / before(V1 参数) / next/previous(V2 双向) / NEWER/更旧方向 / X-Next-Cursor(V1 响应头) | V1ApiClient.kt:289-305；V2Mappers.kt:32-39；SessionStateService.kt:597-604 | cursor ✓（V1 before/响应头为协议差异） |
| 目录作用域 | directory / 目录 / worktree / location / project | ApiClient.kt:25-31（x-opencode-directory ✓）；V2ApiClient.kt:1258-1270（/api/location） | directory ✓；worktree/location/project 为关联但不同概念 |
| 压缩（会话历史） | compact(V2 端点) / summarize(V1 端点+域函数名) / compaction(part 类型+事件) / 压缩(中文) | V1ApiClient.kt:175-187；V2ApiClient.kt:1015-1027；V2EventParser.kt:101-165 | compact ✓（V1 summarize 为同义旧词，域函数名仍 summarizeSession） |
| 中断 | abort(V1) / interrupt(V2) / 停止 | SessionApi.kt:49,143,153-155 | 两词均为各自版本 API 原词；V2=interrupt |
| 归档（会话属性） | archived / archive(会话) | SessionApi.kt:39-47；SessionRepositoryImpl.kt:209-217 | archived ✓（PATCH 任意字段） |
| 归档（本地消息桶） | 归档桶 / archive bucket / archive_buckets 表 | ArchiveBucketEntity.kt:7-28；MessageStore.kt:24-29 | 本地概念，无 API 对应 |
| 堆积消息 | 堆积消息 / pending message / 队列 / drain / 手动放行 | PendingMessageEntity.kt:8；PendingMessagePipeline.kt:26-52 | 本地概念，无 API 对应（pending_messages 表） |
| 未读红点 | 红点 / 未读 / unread / maxCompleted 水位线 / 服务器 completed | UnreadBadgeService.kt:26-68 | 本地概念；completed ✓（API 时间字段） |
| 认证 | auth(V1 端点 /auth/{id}) / credential(V2 /api/credential) / API key / OAuth | ProviderApi.kt:51-61；V2ApiClient.kt:1335-1358 | 各自版本原词；V2=credential |
| 服务器实例 | instance / global(V1 前缀) / dispose / service/stop(V2) | ProviderApi.kt:87-97；V2ApiClient.kt:1411-1423 | /global/dispose(V1) ✓ /api/service/stop(V2) ✓ |
| agent | agent / 代理(偶见) / 显示名 name vs id / 模式选择器(SystemApi 注释) | SystemApi.kt:20-25；V2ApiClient.kt:623-653 | agent ✓（name/id 双轨为服务器契约） |
| 僵尸状态 | 僵尸 running / zombie runner / 卡死 | SessionStateService.kt:58-61,667-695 | 本地概念 |
| 流式所有权 | 所有权 / ownership / 流式会话所有权 | StreamingOwnershipRegistry.kt:7-17 | 本地概念 |
| 文件服务 | file(V1 /file/*) / fs(V2 /api/fs/*) | V1ApiClient.kt:686-728；V2ApiClient.kt:1427-1531 | 各自版本原词 |
| 合并策略 | SSE_PRIORITY / REST_AUTHORITY / APPEND_ONLY | MessageEventHandler.kt:874-885 | 本地概念（MergeStrategy） |
| 本地播种 | 播种 / seed / 本地播种 / 受理回执 admission | PromptAdmission.kt:4-12；ChatRepositoryImpl.kt:226-248 | admission 为本地 DTO 名；inbox ✓（session.inbox.enqueued） |
| synthetic 通知 | synthetic / 合成消息 / 通知卡片 | SseClientV2.kt:351-416；V2Mappers.kt:322-340 | synthetic ✓（item.type="synthetic"） |
| 序号游标 | durable.seq / aggregateID / gap 检测 | SseClientV2.kt:66-71,312-320 | durable ✓（信封字段） |

## 失实注释（注释与代码实际行为不符）

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---------|-----------|----------------|---------|
| data/repository/SessionStateService.kt:667-695 | interruptZombieRunner KDoc：'动作：调用服务器 interrupt/abort（按 apiVersion 分流：V2 POST /api/session/{id}/interrupt，V1 POST /session/{id}/abort）……interrupt 是 fire-and-forget' | 函数体仅打 DEBUG 日志（'zombie display-fix only (auto interrupt disabled per official semantics)'），无任何 HTTP 调用；且 L547-554 调用点仍保留'POST interrupt 返回 204……僵尸被解除'的停用前老注释 | KDoc 改为'僵尸解除已停用（对齐官方语义，research/05），仅本地显示修复'；L547-554 同步更新 |
| data/api/v2/SseClientV2.kt:43-59 vs 268-280 | 头部 KDoc：'V2 SSE 使用标准 SSE 帧格式（event:+data:+id:）' | parseV2Event 主路径实测注释自述'V2 真实线格式（curl 实测）——单行 JSON 打包在 data: 行 {id,type,data}'；实现兼容两种，主路径为单行 JSON | 头部 KDoc 改为'两种线格式兼容，实测以 data: 单行 JSON 为主（event: 帧为兼容路径）' |
| data/api/v2/V2EventParser.kt:28-31 | KDoc：'这些事件当前不映射到具体 UI 行为……但必须被解析为占位事件' | L64-217 已映射具体事件：execution.started/succeeded→FSM Busy/Idle、shell.*→ShellJobStarted/Ended、compaction.*→CompactionStarted/Ended、usage.updated→UsageUpdated、tool.progress→ToolProgress；且 SseClientV2 先经 V2SseMapper 映射 step/tool 系列 | KDoc 更新为'部分映射具体事件（execution/shell/compaction/usage/tool.progress），其余保活占位' |
| data/terminal/TerminalTabState.kt:6 | KDoc：'状态转移（由 dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace 驱动）' | 该类实际位于 data.terminal 包（data/terminal/ServerTerminalWorkspace.kt），ui.screens.chat 路径已过期 | 包路径改为 data.terminal.ServerTerminalWorkspace |
| data/api/v2/V2SseMapper.kt:29-31 | 'tool：call_id（v2 tool part 的稳定 id）' | 事件 payload 字段实为 id（L275 props["id"]）；call_id 是 V2SseMapper.kt:31 对官方文档措辞的转述 | 注明'事件字段为 id，语义即 call_id（对齐官方 event.ts 措辞）' |
| data/local/CachedPartEntity.kt:28 | 行内注释：'type: text / tool / code 等' | Part 类型全集无 code（text/reasoning/tool/shell/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent/permission/question/session-turn/unknown，见 MessageStore.typeName L495-514） | 按实际类型枚举修正（'code'为历史残留） |
| data/api/SseClient.kt:158,287 | 'session.next 解析器的公共访问器（供测试使用）' | sessionNextParser 属性同时被 parseSessionNextEvent（自用公共 API）消费，非纯测试用途 | 弱失实：改为'供测试与向后兼容公共 API 使用'（待 grep 全仓使用点终裁） |

## 待裁决冲突（编号：概念 / 冲突各方 / 出现范围 / 事实性备注）

- **C1 提问三代契约**：question(V1 + V2 主干 question.v2.*) vs form(V2 中间契约 next-17430：form.created/replied/cancelled + /api/form/request)。范围：V2FormMapper 全文、MessageApi.kt:81-114、V2ApiClient.kt:889-989。备注：代码注释明言'旧 question.asked//api/question/request 是 stale surface（未来移除）'（V2FormMapper.kt:24）；question.v2 优先+404 记忆降级 form。规范名候选=question。
- **C2 中断双词**：abort(V1 /session/{id}/abort) vs interrupt(V2 /api/session/{id}/interrupt)。范围：SessionApi.kt:49,143,153-155；SessionStateService.kt:674。备注：域接口统一 abort，V2 实现转 interrupt——'V2 用 interrupt 替代 V1 的 abort'（V2ApiClient.kt:209）。
- **C3 重命名双词**：update/rename——域 SessionApi.updateSession → V2 renameSession(POST /rename)（SessionApi.kt:143）。
- **C4 压缩双词**：summarize(V1 端点+域函数名 summarizeSession/compactSession 两个域入口) vs compact(V2 /compact)。范围：SessionApi.kt:57-62；SessionRepositoryImpl.kt:231-239；V2ApiClient.kt:1015-1027。
- **C5 认证端点双词**：auth(V1 PUT/DELETE /auth/{id}) vs credential(V2 PATCH/DELETE /api/credential/{id}，label 必填 #84)。范围：ProviderApi.kt:51-61；V2ApiClient.kt:1335-1358。
- **C6 权限字段双轨**：permission(V1 工具权限名) vs action/resources(V2 PermissionV2.Request)；patterns vs resources；reply once/always/reject vs legacy effect allow/deny。范围：PermissionResponses.kt:13-28；PermissionMapper.kt:30-33；V2ApiClient.kt:844-887。备注：注释明确'action 语义对应 V1 permission'。
- **C7 ID 大小写双轨**：API 大写 ID（sessionID/messageID/partID/parentID/projectID/callID/inboxID/childID）vs 域 camelCase（sessionId/messageId/...）；V1 session.id vs V2 sessionID。范围：全部 parsers/mappers。备注：ToolRef.callId 消费 API callID；metadata 三源归一双写（sessionID/sessionId/childID，V2Mappers.kt:447-457）。
- **C8 part 定位键分裂**：partID(V1) vs ordinal(V2，derivePartId 派生 msg_type_ord_N) vs call_id(工具)；三代 id 并存（''/msg_ord_N/msg_type_ord_N，#109）。范围：V2SseMapper.kt:29-47；MessageEventHandler.kt:647-684。备注：REST id='' 与 SSE 派生 id 契约错位是多次合并 bug 根因。
- **C9 '单一真相源'多点位**：SessionStateService（会话状态，AGENTS.md 承重规则）、ShellJobsStore（后台 shell）、UnreadBadgeService（红点时间源）各自称'单一真相源'。范围：SessionStateService.kt、ShellJobsStore.kt:13、UnreadBadgeService.kt:58、EventDispatcher.kt:164-165。备注：各管一域不冲突，但术语表需明确其分域语义。
- **C10 归档一词两义**：会话归档（API archived 字段，PATCH 任意字段实现）vs 本地消息归档桶（archive_buckets 表，zstd+TLRU）。范围：SessionApi.kt:39-47；MessageStore.kt:24-29。备注：两概念完全无关，中文注释都用'归档'。
- **C11 turn 词义**：本地'turn 结束后待发送'（堆积消息，PendingMessageEntity.kt:8）vs CONTEXT.md '流式 turn'（completed 为空的 assistant 轮次）vs V2 execution（服务器 turn 权威信号）。备注：三义相关但不同层——本地队列语义 vs 展示语义 vs 协议语义。
- **C12 与 CONTEXT.md 词条呼应**：红点时钟域（UnreadBadgeService/SettingsDataStore unread v2 迁移逐点实现'服务器 completed 唯一时间源+SessionError 客户端时刻唯一例外'）；必需协作者（SessionStateCollaborator.kt:20-30 '8 个可缺省 var 回调收拢为全抽象 interface'几乎为词条原文）；版本 seam（isV2 判定 79 处收口于 *ApiImpl 门面——本次清点 api/ 下 8 个门面类全部 if(conn.apiVersion.isV2) 分发；游标策略收编 PaginationCursorPolicyFactory #172）。备注：词条与 data 层实现高度一致，无冲突。
- **C13 中文'提供商' vs Provider**：全部中文注释用'提供商'，标识符 Provider。范围：ProviderApi/SystemApi/V2ApiClient 等。备注：注释统一中文时需定'提供商'为规范译名或保留 Provider。
- **C14 shell 三义**：交互式 pty（V1 /pty + WS /connect）vs 后台 shell job（V2 /api/shell CRUD）vs 会话内 shell 命令（POST /session/{id}/shell，ShellRequest）。范围：TerminalApi/ShellApi/V2ApiClient.runShellCommand。备注：三概念在 API 中即分离，注释需避免混称'shell'。
- **C15 executeCommand 死参数**：V1ApiClient.kt:222-241 与 V2ApiClient.kt:1084-1103 的 agent/model/variant/parts 参数声明后未进请求体（仅 command/arguments 发送）。备注：非注释失实，是参数级事实——注释未声明此丢弃行为，注释修订时应补充或参数应删除（ backlog 候选）。


## API 术语权威清单（OpenCode API 领域名词全集——全项目规范名候选源）

> 来源：V1ApiClient（937 行）/ V2ApiClient（1773 行）/ 8 个 SSE parser / V2 mapper 三件套 / DTO 包全部字段的逐行清点。V1=无 /api 前缀、响应无包裹；V2=/api 前缀、{data:...} 包裹 + cursor 对象。

### A. 端点路径全集

**V1（无前缀）**：
- 会话：GET /session（roots,search,cursor,limit）· POST /session{title,parentID} · GET/DELETE/PATCH /session/{id} · POST /session/{id}/abort · GET /session/{id}/diff · POST/DELETE /session/{id}/share · POST /session/{id}/summarize{providerID,modelID} · POST /session/{id}/revert{messageID} · POST /session/{id}/unrevert · POST /session/{id}/fork{messageID} · POST /session/import{url} · POST /session/{id}/command{command,arguments} · GET /session/{id}/children · GET /session/{id}/todo · GET /session/status · GET /session/{id}/message（limit,before→响应头 X-Next-Cursor） · GET /session/{id}/message/{mid} · DELETE /session/{id}/message/{mid} · DELETE /session/{id}/message/{mid}/part/{partIndex} · POST /session/{id}/prompt_async{parts,model,agent,variant} · POST /session/{id}/shell{agent,model,command}
- 权限/提问：GET /permission · POST /permission/{requestID}/reply{reply,message} · GET /question · POST /question/{requestID}/reply{answers} · POST /question/{requestID}/reject
- 系统：GET /global/health · GET /path · GET /agent · GET /command · GET /skill · GET /mcp · POST /mcp/{name}/connect · POST /mcp/{name}/disconnect
- 提供商/配置：GET /config/providers · GET /provider · GET /provider/auth · POST /provider/{providerID}/oauth/authorize{method} · POST /provider/{providerID}/oauth/callback{method,code} · PUT /auth/{providerID}{type:key} · DELETE /auth/{providerID} · GET/PATCH /config · GET/PATCH /global/config · POST /global/dispose · POST /instance/dispose
- 文件/VCS：GET /find/file{query,type,limit,dirs} · GET /file/content{path} · GET /find{pattern} · GET /file{path} · GET /find/symbol{query} · GET /file/status · GET /vcs · GET /vcs/status · GET /vcs/diff{mode,context} · GET /project · GET /project/current
- 终端：POST /pty{title,cwd} · PUT /pty/{id}{size{rows,cols}} · DELETE /pty/{id} · WS /pty/{id}/connect?cursor= · GET /pty/shells
- SSE：GET /global/event

**V2（/api 前缀）**：
- 会话：GET/POST /api/session（search,cursor,limit；location.directory） · GET/DELETE /api/session/{id} · POST /api/session/{id}/rename{title} · POST /api/session/{id}/interrupt · GET /api/session/active（{data:{sessionID:{type:running}}}） · POST /api/session/{id}/background · GET /api/session/{id}/message（cursor 双向） · POST /api/session/{id}/prompt（现代 {prompt:{text,files,agents}} 400→降级平铺） · POST /api/session/{id}/model{model:{id,providerID,variant}} · POST /api/session/{id}/agent{agent} · POST /api/session/{id}/command · GET /api/session/{id}/todo（404 记忆） · POST /api/session/{id}/compact{providerID,modelID} · POST /api/session/{id}/revert/stage{messageID} · POST /api/session/{id}/revert/clear · POST /api/session/{id}/fork · POST /api/session/import · POST /api/session/{sid}/permission/{rid}/reply（legacy /api/permission/{rid}/reply{effect}） · POST /api/session/{sid}/question/{rid}/reply{answers}（404 记忆） · POST /api/session/{sid}/question/{rid}/reject · POST /api/session/{sid}/form/{fid}/reply{answer} · POST /api/session/{sid}/form/{fid}/cancel · POST /api/session/{sid}/shell
- 后台 shell：GET /api/shell · GET /api/shell/{id} · GET /api/shell/{id}/output{cursor,limit}（响应 output/cursor/size/truncated） · DELETE /api/shell/{id} · PATCH /api/shell/{id}/timeout{timeout}
- 系统：GET /api/health{healthy,version,pid} · GET /api/agent · GET /api/command · GET /api/skill · GET /api/mcp（{name,status:{status,error}}） · POST /api/mcp/{name}/connect|disconnect · GET /api/location（仅 directory） · GET /api/permission/request · GET /api/form/request
- 提供商/配置：GET /api/provider · GET /api/model · POST /api/provider/{id}/oauth/callback · PATCH /api/credential/{id}{type,key,label} · DELETE /api/credential/{id} · GET/PATCH /api/config（裸数组 [{type:document,path,info}]） · POST /api/service/stop
- 文件/VCS：GET /api/fs/find{query,type,limit,dirs,pattern} · GET /api/fs/read/*（通配符段路径，非 ?path=） · GET /api/fs/list{path} · GET /api/vcs{branch,default_branch} · GET /api/vcs/status · GET /api/vcs/diff{mode,context} · GET /api/project（裸数组） · GET /api/project/current
- 终端：POST /api/pty · PUT/DELETE /api/pty/{id} · WS /api/pty/{id}/connect?cursor= · GET /api/pty（列表，/api/pty/shells 为错误路径）
- SSE：GET /api/event

### B. SSE 事件名全集

**V1 线格式**：data: {type, properties}（全局端点 /global/event 包 {directory, payload} 信封）
**V2 线格式**：主格式 data: {id, created, type, durable, location, event, ...字段平铺}；兼容 event:+data: 标准帧。信封元字段（EVENT_META_KEYS）= id/created/type/durable/location/event。

- 服务器：server.connected · server.heartbeat（另有注释帧 ': heartbeat' 心跳形态）
- 会话生命周期：session.status{sessionID,status{type,attempt,message,next}} · session.idle · session.created{info|平铺} · session.updated · session.deleted · session.error{sessionID,error} · session.diff{sessionID,diff[]} · session.compacted（legacy）
- 消息（V1）：message.updated{info} · message.removed{sessionID,messageID} · message.part.updated{part} · message.part.delta{sessionID,messageID,partID,field,delta} · message.part.removed{sessionID,messageID,partID}
- 权限：permission.asked{id,sessionID,permission,patterns,always,metadata,tool{messageID,callID}} · permission.replied{sessionID,requestID}
- 提问（V1）：question.asked{id,sessionID,questions[{header,question,multiple,custom,options[{label,description}]}],tool} · question.replied · question.rejected{sessionID,requestID}
- PTY/命令：pty.created{id,title,command,cwd} · pty.updated{id,title,command,status} · pty.deleted{id} · command.executed{name,sessionID,arguments,messageID}
- 杂项：todo.updated{sessionID,todos[{content,status,priority}]} · workspace.ready|failed{workspaceID,error} · file.edited{path} · file.watcher.updated{path} · mcp.tools.changed{server} · installation.updated|update_available{version} · worktree.ready|failed{path,error} · vcs.branch.updated{branch} · project.updated{info} · lsp.updated
- session.next.* 前缀族（V1 细粒度，经 SessionNextEvent 判别式解码）：AgentSwitched/ModelSwitched/Moved{location,subdirectory}/Text*/Reasoning*/ToolInput*/ToolProgress/ToolSuccess/ToolFailed/Step*/Shell*/Compaction*/Prompted/Retried/UsageUpdated/Synthetic/Unknown
- V2 细粒度（turn 权威=execution）：session.execution.started · session.execution.succeeded · session.step.started{sessionID,assistantMessageID,parentID,agent,model} · session.step.ended{finish,cost,tokens} · session.reasoning.started/delta/ended{text} · session.text.started/delta/ended{text} · session.tool.input.started/delta/ended{name,text} · session.tool.called{input} · session.tool.success{content,metadata} · session.tool.failed{error,metadata} · session.tool.progress{metadata{output 全量尾部快照,sessionID}} · session.usage.updated{cost,tokens{input,output,reasoning,cache{read,write}}} · session.compaction.started/delta/ended/failed{messageID,reason,text|delta} · session.instructions.updated · session.shell.started/ended · session.message.*（前缀注册） · session.inbox.enqueued{sessionID,inboxID,item{type,payload{text,files,agents},delivery}} · session.inbox.delivered{sessionID,inboxID} · session.input.admitted（过渡，input{type,data}）/ session.input.promoted{inputID}（过渡两阶段，与 inbox 新版并存） · shell.created/exited/deleted（旧名，与 session.shell.* 并存）
- form/question.v2（V2 提问三代）：form.created{form} · form.replied{id,sessionID} · form.cancelled{id,sessionID} · question.v2.asked{id,sessionID,questions,tool} · question.v2.replied/rejected{requestID|id,sessionID}

### C. DTO/实体字段名全集（API 原词）

- **Session**：id(V1)|sessionID(V2) · slug · projectID · parentID · title · directory · location{directory} · version · time{created,updated,archived} · agent · model{id,providerID,variant}|{providerID,modelID}|字符串（三代形态） · cost · tokens{input,output,reasoning,cache{read,write}} · revert{messageID} · archived
- **Message**：id(msg_ ULID) · sessionID · role(V1: user/assistant)|type(V2: user/assistant/system/synthetic/shell/compaction/agent-switched/model-switched/skill) · time{created,completed} · text(user) · content[](V2 assistant) · files[{data(base64),mime,name,uri,source{type:inline}}] · agent · model · payload.text / prompt.text（admission 双读） · summary{body,title} · metadata{source:subagent,childID,agent,state}
- **Part**（type 判别）：text · reasoning · tool{id,name(V2)|tool(V1),state{status,input,content[],output,error{type,message},metadata}} · step-start · step-finish · file · snapshot · patch · subtask · compaction · retry · abort · agent · session-turn（+客户端扩展 shell/permission/question/unknown） · partID(V1) | ordinal+assistantMessageID(V2 派生 msg_type_ord_N) | call_id
- **Permission**：id · sessionID · permission(V1) · patterns · always(布尔 V2|数组 V1) · action · resources · save · metadata · tool{messageID,callID|id} · reply: once/always/reject（legacy effect: allow/deny）
- **Question/Form**：questions[{question,header,multiple,custom,options[{label,description,value}],key(q0/q1...)}] · answers: string[][]（按序 label 数组） · answer: {key:value|values} · form{id,sessionID,title,metadata{kind,tool{messageID,id}},fields[{key,title,description,type(string/multiselect/number/integer/boolean/external),options,custom}]}
- **Provider/Model**：id · name · providerID · modelID · family · status(active) · enabled · variants[{id,settings}](V2 数组|V1 map) · capabilities{tools,input,output}(V2)|{temperature,reasoning,attachment,toolcall}(V1 DTO) · cost[{input,output,cache{read,write}}](V2 数组) · limit{context,input,output} · default(provider_default)
- **Todo**：id · content · status(pending) · priority(medium)
- **Shell.Info**：id · status · command · cwd · shell · file · pid · exit · metadata{sessionID} · time{start,end} · output · cursor · size · truncated
- **PtyInfo**：id(pty_ 前缀) · title · command · args · cwd · status · pid · rows · cols · cursor
- **MCP**：name · status{status: connected|disabled|failed|needs_auth|needs_client_registration, error} · type · enabled · url · environment · headers
- **Config**：disabled_providers · enabled_providers · model · small_model · default_agent · mcp{}
- **文件/VCS**：path · type(text|binary|directory|file) · content · diff · patch · encoding · mimeType · absolute · ignored · size · modified · line_number · absolute_offset · submatches{match{text},start,end} · branch · default_branch · additions · deletions · staged
- **health/location**：healthy · version · pid · home · state · config · worktree · directory
- **Agent/Command/Skill**：name · description · mode(primary) · hidden · color · source · hints · location · content · id(服务器 agent id，'plan') vs name(显示名，'Plan')
- **事件信封/游标**：durable{seq,aggregateID}（aggregateID=sessionID） · cursor{previous(更新方向),next(更旧方向)}（base64url {id,order,direction}） · X-Next-Cursor(V1 响应头) · eventID 前缀 evt_ · delivery(steer)
- **HTTP 头**：x-opencode-directory（目录作用域） · Authorization

### D. ID 前缀词汇（服务器 ULID 体系）
msg_（消息）· ses_（会话）· pty_（终端）· frm_（表单）· call_（工具调用）· evt_（事件）

### E. 与 CONTEXT.md 既有词条/Avoid 词的关系
- 红点时钟域 ✓（UnreadBadgeService UnreadEvent 类型编码即词条实现）；必需协作者 ✓（SessionStateCollaborator 接口即词条实现）；版本 seam ✓（isV2 判定全部收口于 api/ 下 8 个 *ApiImpl 门面）；流式 turn ✓（completed==null 判定遍布 MessageEventHandler/SessionStateService）。
- 本清单新增候选规范名（供全项目裁决）：session/message/part/delta/tool/call_id/provider/model/agent/permission/reply/question/cursor/ordinal/compaction/interrupt(V2)/credential(V2)/directory(fs 域 V2)。


## 附录：逐文件底稿（114/114 全量，兼逐文件证据）

<!-- LEDGER BELOW -->

### F:data/api/ApiClient.kt
- LANG:中文
- NOTE:共享 HttpClient+Json 持有者 + directoryHeader 扩展（x-opencode-directory 头）
- T:领域 API（*ApiImpl）L14；directory/directoryHeader L25-31（API 原词 x-opencode-directory ✓）；"原始单体 API 类" L27（历史称呼）
- B:无

### F:data/api/AuthHeader.kt
- LANG:中文
- NOTE:认证头统一扩展 auth(conn)，#114(D2-27)，替代 147 处内联
- T:认证头/Authorization L9,18（HTTP 原词 ✓）；"每服务器属性"（ServerConnection.authHeader）L11；全局 Auth 插件（不采用）L12
- B:无

### F:data/api/NetworkMonitor.kt
- LANG:中文
- NOTE:ConnectivityManager 封装，NetworkState 四态 + isOnline
- T:网络连接状态 NetworkState L19（Available/Losing/Lost/Unavailable L21-30，Android 原词）；captive portal/认证墙 L104；#133(D2-L41) VALIDATED 语义 L104-107
- B:无（onCapabilitiesChanged 的 !validated→Unavailable 与注释一致）

### F:data/api/NonJsonResponseException.kt
- LANG:中文
- NOTE:HTML SPA fallback 防御 rejectHtmlResponse，D2-22/#121
- T:SPA fallback L7（API 行为词）；Non-JSON (HTML) response L32（英文日志字面量）；异常用户消息中文字面量 L34（UI 可见文案）；"API 版本误判" L8,23
- B:无

### F:data/api/RestSessionStatusInfo.kt
- LANG:中文
- NOTE:REST GET /session 轮询状态快照（idle/busy/retry），校正 SSE 派生状态
- T:type="idle"|"busy"|"retry" L9,15（API 原词 ✓）；attempt L10,16（API 原词 ✓）；next（epoch ms）L12；"REST 状态校正" L7
- B:无

### F:data/api/RetryPolicy.kt
- LANG:中文
- NOTE:指数退避重试策略 + retryWithPolicy
- T:指数退避 L11；瞬时错误/transient L36,42,51（isTransient API 原词 ✓）；maxAttempts/initialDelayMs/backoffFactor L19-22
- B:无（备注：isTransientException L40-41 中 SocketTimeoutException 分支在 IOException 之后永不可达——冗余死分支，注释并列列举未失实但代码冗余）

### F:data/api/SseClient.kt
- LANG:中文
- NOTE:V1 SSE 客户端（/global/event），行级/事件级 OOM 防护、心跳超时、冷却跟踪
- T:SSE(Server-Sent Events) L138；全局事件流/global/event L163-169（端点 ✓）；data:行/payload/type/properties L263-271（SSE 结构原词 ✓）；session.next L158（事件名 ✓）；ServerHeartbeat/server.heartbeat L219,231（事件名 ✓）；心跳 heartbeat L25；半开 TCP L86；冷却 cooldown L307-345；重连 reconnect L167；x-opencode-directory L175 ✓；"全局端点/实例级端点" L263-264；"与上游 oc-remote 一致" L28；SseAuthException/SseConnectionException L354-358
- B:待核——L158"session.next 解析器的公共访问器（供测试使用）"与 L286"为向后兼容保留的公共 API（供测试使用）"：sessionNextParser 除测试外还被 SseClient.parseSessionNextEvent 自用，需 grep 全仓使用点裁定
- 其他:B 候选均与代码一致（#63/#108/冷却清零注释均有对应代码分支）

### F:data/api/file/FileApi.kt
- LANG:中文（接口方法 KDoc 部分，多数方法无注释）
- NOTE:文件/VCS/项目域 API 的 V1/V2 分发门面（isV2 判定全部收口在此类门面）
- T:findFiles/readFile/searchText/probeDirectory/listDirectory L12-31；findSymbols GET /find/symbol L34-37（端点 ✓）；getFileStatus GET /file/status L40-43（端点 ✓）；getVcs/getVcsStatus/getVcsDiff（branch/change/diff）L45-49；listProjects/getCurrentProject L51-53
- B:无

### F:data/api/message/MessageApi.kt
- LANG:中文
- NOTE:消息/权限/问题 API 的 V1/V2 分发门面；V2 form(V1 question) 契约差异收口
- T:session/message/part/sessionId/messageId/partIndex L48-56（API 原词 ✓）；permission：POST /permission/{requestID}/reply，reply="once"|"always"|"reject" L60-62（API 原词 ✓）；question(V1)/form(V2) L81-99 同概念双词；requestID(路径)/requestId(参数)/formID L61,67,83,99 大小写变体；promptAsync/prompt L36,83；answers: string[][](V1) vs answer:{key:value|[values]}(V2) L82-83；limit/before(V1) vs cursor(V2) L21,129（分页游标=CONTEXT.md 版本 seam 词条）；"待处理" pending L74,111
- B:无

### F:data/api/message/PromptAdmission.kt
- LANG:中文
- NOTE:V2 prompt 响应回执，发送即显示的本地播种源
- T:受理回执/admission L4；POST /api/session/{id}/prompt（端点 ✓）；Inbox 条目/InboxID L8,15（API 原词 ✓）；session.inbox.enqueued L8（事件名 ✓）；payload.text L8,18（✓）；prompt_async(V1,204) L12（✓）；本地播种 L9；悲观消息 L11
- B:无


### F:data/api/provider/ProviderApi.kt
- LANG:中文
- NOTE:提供商/认证/配置 API 的 V1/V2 分发门面
- T:提供商/Provider 并用（KDoc 中文"提供商"+标识符 Provider）L13-16；GET /config/providers、GET /provider、GET /provider/auth、POST /provider/{providerID}/oauth/authorize|callback、PUT|DELETE /auth/{providerID}（端点 ✓）L14-61；API key L52-55；GET|PATCH /config、/global/config（✓）L64-85；销毁 dispose：POST /global/dispose、/instance/dispose（✓）L87-97；providerID(路径)/providerId(参数) 大小写变体 L32-38
- B:无

### F:data/api/session/SessionApi.kt
- LANG:中文
- NOTE:会话生命周期 API 门面；abort/interrupt、update/rename 的 V1/V2 词汇分裂都在此
- T:session CRUD+cursor/limit 分页 L15-21（✓）；updateSession→V2 renameSession L143（同操作双词）；abortSession→V2 interruptSession L153-155（同操作双词）；updateSessionFields PATCH /session/{sessionId}（归档）L39-47；share/unshare/import(shareUrl)/revert/unrevert/fork L51-70；executeCommand(command/arguments/agent/model/variant/parts) L72-82；listSessionChildren L84（子会话）；getSessionTodos L86；backgroundSession："前台可后台化工具（subagent）批量转为后台" L88-92；activeSessions："running" L94-98
- B:无（V1 不支持注释与 impl else false/emptyMap 一致）

### F:data/api/shell/ShellApi.kt
- LANG:中文
- NOTE:V2 专属后台 shell 命令 API；V1 全部空实现
- T:后台 shell 命令 POST /api/shell L14（✓）；stdout/stderr 合并捕获 L15；生命周期 running→exited(exit code)/remove L16；"V1 的 shell 是交互式 pty / bash 工具 part" L17（术语对照）；getShellOutput cursor/limit L24-30（✓）
- B:无（"V1 返回空/不支持"与 impl 一致）

### F:data/api/sse/parsers/MessageEventParser.kt
- LANG:中文（行内注释）
- NOTE:message.* SSE 事件解析；V1/V2 part 字段归一化（tool/name、callID/id、双层 metadata）
- T:事件名 message.updated/removed、message.part.updated/delta/removed L19-22（✓）；字段 info/sessionID/messageID/partID/field(默认"text")/delta L30-59（✓）；part 类型全集 text/reasoning/tool/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent/session-turn L97-171（✓）；V2 兼容注释 L100-117：tool(V1)/name(V2)、callID(V2 为 id)、双层 metadata 展平
- B:无（截断段 L117-145 待补读核对）
- 变体:callId(ToolRef 域)/callID(API)

### F:data/api/sse/parsers/MiscEventParser.kt
- LANG:中文
- NOTE:杂项 SSE 事件解析（todo/workspace/file/mcp/installation/worktree/server）
- T:server.connected/server.heartbeat、todo.updated(content/status 默认 pending/priority 默认 medium)、workspace.ready|failed(workspaceID)、file.edited、file.watcher.updated、mcp.tools.changed(server)、installation.updated|update_available(version)、worktree.ready|failed(path) L22-30（全部事件名+字段 ✓）
- B:无

### F:data/api/sse/parsers/ParserUtils.kt
- LANG:无注释
- NOTE:JsonObject.str 防御性字符串提取（message/data.message/error/type/name 兜底链）
- T:无领域术语（纯工具）
- B:无

### F:data/api/sse/parsers/PermissionEventParser.kt
- LANG:中文
- NOTE:permission.* SSE 事件解析；always 的 V2 Boolean/V1 List 兼容
- T:permission.asked/replied L17（✓）；字段 id/sessionID/permission/patterns/always/metadata/tool{messageID,callID} L25-46（✓）；"V2：always 为 Boolean；回退到 V1 的 List<String>" L30-37（与代码一致）
- B:无

### F:data/api/sse/parsers/PtyEventParser.kt
- LANG:中文
- NOTE:PTY 与命令 SSE 事件解析
- T:pty.created/updated/deleted、command.executed L17-18（✓）；字段 id/title/command/cwd/status；name/sessionID/arguments/messageID L26-55（✓）；"PTY 和命令事件" L11
- B:无

### F:data/api/sse/parsers/QuestionEventParser.kt
- LANG:中文
- NOTE:question.* SSE 事件解析
- T:question.asked/replied/rejected L17（✓）；字段 questions[{header,question,multiple,custom 默认 true,options[{label,description}]}]、requestID L34-70（✓）
- B:无

### F:data/api/sse/parsers/SessionEventParser.kt
- LANG:中文
- NOTE:session.* + vcs/project/lsp SSE 事件解析；decodeSessionCompat V1(info 包装)/V2(扁平) 双格式兼容
- T:session.status/idle/created/updated/deleted/error/diff/compacted、vcs.branch.updated、project.updated、lsp.updated L21-25（✓）；status.type idle/busy/retry(attempt/message/next) L35-46（✓）；V1 id/time vs V2 扁平 sessionID/parentID/title/agent/model/location/version/slug/projectID L113-137；model 对象 {id,providerID,variant} vs 字符串 L150-167
- B:无（2026-08-11/08-17 修复注释均有对应代码）
- 变体:id(V1)/sessionID(V2)、parentId/projectId(域) vs parentID/projectID(API)


### F:data/api/sse/parsers/SessionNextEventParser.kt
- LANG:中文
- NOTE:session.next.* 前缀匹配解析；Unknown/rawType/rawJson 兜底
- T:session.next. 前缀 L17（事件名 ✓）；"判别器"（discriminator）L33,38；#97(H-5) 单遍解析 L35-37
- B:无（L38 注释与 L39-40 补 rawType 代码一致）

### F:data/api/sse/parsers/SseEventParser.kt
- LANG:中文
- NOTE:SSE 事件解析策略接口（canParse/parse）
- T:eventType/props（事件类型/事件属性）L12,18
- B:无

### F:data/api/system/SystemApi.kt
- LANG:中文
- NOTE:health/path/agent/command/skill/MCP API 门面
- T:GET /path（"home 目录、worktree 等"）L14-18；GET /agent（"build、plan 等"、"主要/可见 agent"、"模式选择器"=agent 别名倾向）L20-25；GET /command（"斜杠命令"）L27-31；GET /skill L33-37；MCP：getMcpStatus/connectMcpServer/disconnectMcpServer L39-43；health L12
- B:无

### F:data/api/terminal/TerminalApi.kt
- LANG:中文
- NOTE:PTY 生命周期 + 会话内 shell 命令 API 门面
- T:createPty/removePty/updatePtySize(cols,rows)/openPtySocket(cursor)/listPtyShells L13-37；POST /session/{sessionId}/shell L39-50（端点 ✓）；pty/shell 混用（listPtyShells→ShellInfo、ptyId）L20,37
- B:无

### F:data/api/version/ApiVersionDetector.kt
- LANG:中文
- NOTE:V1/V2 API 版本探测（/api/health vs /global/health 双探 + 交叉验证 + knownVersion 排序）
- T:探测端点 GET /api/health(V2)/GET /global/health(V1) L28-35（✓）；healthy/version/pid L34-35,106-109（✓）；UNKNOWN 语义 L32,82-87；SPA fallback L98,116；交叉验证 L111-117；#150 方案 B / #132 L49-52,82-87
- B:无（响应格式注释与解析代码一致）

（补：MessageEventParser L115-145 截断段已补读核对——双层 metadata 展平/双写 sessionId+sessionID/V2 error 对象转字符串/content 数组提取 output，注释与代码全部一致，B 维持"无"）


### F:data/api/v1/V1ApiClient.kt
- LANG:中文（少量 KDoc/行内，多数方法无注释）
- NOTE:V1 REST 全端点实现；V1 特征=URL 无 /api 前缀、响应无 data 包裹层（L56-59 注释✓）
- T(端点全集):/session GET(roots/search/cursor/limit L80-87)、POST(title,parentID)、/{id} GET/DELETE/PATCH、/{id}/abort、/diff、/share POST|DELETE、/summarize(providerID,modelID)、/revert(messageID)、/unrevert、/fork(messageID)、/import(url)、/command、/children、/todo、/status、/{id}/message(limit,before + 响应头 X-Next-Cursor L304)、/message/{mid}、/message/{mid}/part/{partIndex}、/prompt_async(PromptRequest parts/model/agent/variant)、/shell(ShellRequest agent/model/command)；/permission、/permission/{rid}/reply(reply,message)；/question、/{rid}/reply(answers)、/{rid}/reject；/global/health、/path、/agent、/command、/skill、/mcp、/mcp/{name}/connect|disconnect；/config/providers、/provider、/provider/auth、/oauth/authorize(method)、/oauth/callback(method,code)、/auth/{pid} PUT(type:"api",key)、/config GET|PATCH、/global/config、/global/dispose、/instance/dispose；/find/file(query,type,limit,dirs)、/file/content(path)、/find(pattern)、/file(path)、/find/symbol、/file/status、/vcs、/vcs/status、/vcs/diff(mode,context)、/project、/project/current；/pty(title,cwd)、/pty/{id} PUT(PtySize rows,cols)、/pty/{id}/connect?cursor=(WebSocket)、/pty/shells
- T(变体):X-Next-Cursor 响应头→nextCursor L304-305（V1 分页=响应头携带）；roots 参数 L83（无注释解释）；prompt_async 204 无响应体→返回 null L396-400；"pty_xxx" id 前缀 L826-828；tab 兜底 title="Tab" L816
- B:无（exportSessionToStream OkHttp 流式注释 L314-319 与实现一致；#87/#121 防御注释与代码一致）
- 备注(事实非注释):executeCommand 的 agent/model/variant/parts 参数声明后未进请求体（L222-241 仅 command/arguments）——参数静默丢弃


### F:data/api/v2/SseClientV2.kt
- LANG:中文
- NOTE:V2 SSE 客户端（GET /api/event）；durable.seq 游标回调 + synthetic 两阶段通知缓存
- T:GET /api/event L100-105（✓）；V2 帧 event:/data:/id: vs V1 data:{type,properties} L46-58；EVENT_META_KEYS {id,created,type,durable,location,event} L40-41（✓信封字段）；durable.seq/aggregateID（core/event.ts:294）L66-71,312-320；synthetic 两阶段：session.inbox.enqueued/delivered vs session.input.admitted/promoted（item{type,payload,delivery} vs input{type,data}）L84-96,351-416；delta 流 session.reasoning/text/tool.input.delta {sessionID,assistantMessageID,ordinal,delta} L442-450；"\u0000 分隔构造 V1 兼容格式" L257-265；注释帧心跳 ": heartbeat" L197-217
- B:头部 KDoc L46-52 称 V2 用"标准 SSE 帧格式（event:+data:+id:）"，而 L271-275 实测注释称"V2 真实线格式（curl 实测）= 单行 JSON 打包在 data: 行 {id,type,data}"——两段注释互相矛盾，实现兼容两种（parseV2Event 主路径为单行 JSON）。修订方向：KDoc 改为"两种线格式兼容，实测以单行 JSON 为主"

### F:data/api/v2/V2EventParser.kt
- LANG:中文
- NOTE:V2 细粒度事件兜底解析器（execution/shell/compaction/usage/tool.progress）
- T:前缀全集 session.reasoning./tool./step./usage./text./message./shell./execution./instructions./compaction. + shell. L38-51；session.execution.started/succeeded = turn 权威信号→FSM Busy/Idle L60-73；shell.created/exited/deleted(旧) vs session.shell.started/ended(新) 双命名 L76-99；session.compaction.started/delta/ended/failed + legacy session.compacted L101-165；session.usage.updated {cost,tokens} L166-195；session.tool.progress {metadata.output 全量尾部快照} L196-217
- B:KDoc L28-31"这些事件当前不映射到具体 UI 行为（…）但必须被解析为占位事件"已过期——L200-217 session.tool.progress 已映射为具体 ToolProgress 事件；且 SseClientV2.handleEvent 先经 V2SseMapper 把 step/tool 系列映射为具体 MessagePartUpdated/MessageUpdated。修订方向：KDoc 更新为"部分映射具体事件（execution/shell/compaction/usage/progress），其余保活占位"
- 备注:文件内 TAG="SseClientV2"（L15）与文件名 V2EventParser 不符——日志归属复制残留（事实记录，非注释）

### F:data/api/v2/V2FormMapper.kt
- LANG:中文
- NOTE:V2 提问三代契约映射（question(V1)→form→question.v2）；answer 构造
- T:form.created/replied/cancelled（metadata.kind=="question"）L57-71；question.v2.asked/replied/rejected（主干新一代）L72-88；"旧 question.asked //api/question/request 是 stale surface（未来移除）" L24（明确判词！）；GET /api/form/request L29,182；POST /api/session/{id}/form/{formID}/reply L23；POST /api/session/{sid}/question/{id}/reply（question.v2）L264-265；field.type string/multiselect/number/integer/boolean/external L41；Form.Value=string|number|boolean|string[] L42；metadata.tool{messageID,id}（V2 用 id 而非 callID）L142-149；label→value 映射 L251-253；官方 TUI 语义 answers=按序 label 数组、未答补 []、"Unanswered" L264-278
- B:无

### F:data/api/v2/V2Mappers.kt
- LANG:中文
- NOTE:V2 REST 响应解包（data/cursor/location 包裹）+ Session/Message/Shell JSON→域模型转换器
- T:V2ResponseWrapper {data:...}、cursor{previous,next}（base64 JSON）、location 包裹 vs 裸数组（/api/project）L26-118；UnwrappedList.nextCursor=更旧(older)/previousCursor=更新(newer) L32-39,62-68；V2SessionMapper 字段 id/title/projectID/parentID/agent/model{id,providerID,variant}/cost/tokens/time{created,updated,archived}/location.directory L122-178；V2MessageMapper type 判别 user/assistant/system/synthetic/其他(shell,compaction,agent-switched,model-switched,skill) L180-383；content 元素 type text/reasoning/tool/shell L405-529；files[{data(base64),mime,name,source{type:"inline"}}]→dataUrl L218-242；tool state status streaming/running/completed/error→Running/Completed/Error/Pending L464-488；metadata 双层展平 + sessionID/sessionId/childID(#180) 三源归一双写 L440-458；V2ShellMapper Shell.Info {id,status,command,cwd,shell,file,pid,exit,metadata,time{start,end}} L532-567
- B:无

### F:data/api/v2/V2SseMapper.kt
- LANG:中文
- NOTE:V2 细粒度生命周期事件→领域事件纯函数映射；derivePartId 派生规则
- T:事件全集 session.inbox.enqueued/input.admitted（播种）、session.step.started/ended、session.reasoning.started/delta/ended、session.text.started/delta/ended、session.tool.input.started/delta/ended、session.tool.called/success/failed L52-379（✓）；"v2 不发 message.updated/message.part.updated/session.status" L20（契约断言）；derivePartId `${msgId}_${kind}_ord_${ordinal}`（#109 id 含 type 防碰撞）L41-47；ordinal 定位键（v2 无 partID）L29-31；cost {total}|裸数字 L147-152；tokens {input,output,reasoning,cache{read,write}} L155-165；finish L180；step.ended=消息级完成边界（对齐官方 TUI data.tsx:224-235）L166-171
- B:L31"tool：call_id（v2 tool part 的稳定 id）"——实际事件字段名为 id（L275 props["id"]），注释用 call_id 指称易误导为字段名。修订方向：注明"事件字段为 id，语义即 call_id"
- 备注:TEMP-PROBE 注释 L125-129（"验证后移除"仍在——临时探针残留标记，事实记录）


### F:data/api/v2/V2ApiClient.kt
- LANG:中文
- NOTE:V2 REST 全端点实现；V1/V2 端点词汇分裂的最大集合地
- T(V2 端点全集):/api/health；/api/session GET(search,cursor,limit)|POST(title,parentID,location.directory)|DELETE|/{id} GET|/{id}/rename|/{id}/interrupt|/api/session/active({data:{sessionID:{type:"running"}}})|/{id}/background|/{id}/message GET(limit,cursor)|/{id}/prompt POST(modern {prompt:{text,files,agents}} 400→legacy 平铺)|/{id}/model POST {model:{id,providerID,variant}}|/{id}/agent POST {agent}|/{id}/command|/{id}/todo(404 记忆)|/{id}/compact(providerID,modelID)|/{id}/revert/stage|/{id}/revert/clear|/{id}/fork|/api/session/import(url)|/{id}/permission/{rid}/reply(session-scoped；legacy /api/permission/{rid}/reply {effect:"allow"|"deny"})|/{id}/question/{rid}/reply {answers}(question.v2 优先+404 记忆)|/{id}/question/{rid}/reject|/{id}/form/{fid}/reply {answer}|/{id}/form/{fid}/cancel|/{id}/shell；/api/shell|/{id}|/{id}/output(cursor,limit→output/cursor/size/truncated)|/{id}/timeout PATCH {timeout}；/api/agent|/api/command|/api/skill|/api/mcp({name,status:{status,error}})|/api/mcp/{name}/connect|disconnect；/api/provider|/api/model(id|modelID,name,family,status,enabled,variants数组[{id,settings}],capabilities{tools,input,output},cost数组,limit{context,input,output},providerID)|/api/provider/{id}/oauth/callback|/api/credential/{id} PATCH(type,key,label)|DELETE|/api/config GET|PATCH(裸数组[{type:"document",path,info}])|/api/service/stop；/api/fs/find(query,type,limit,dirs,pattern)|/api/fs/read/*(通配符段,非?path=)|/api/fs/list(path→{path,type})|/api/vcs(branch{}/default_branch)|/api/vcs/status|/api/vcs/diff(mode,context)|/api/project(裸数组)|/api/project/current|/api/pty POST|PUT|DELETE|/connect?cursor=WS|/api/pty(列表)|/api/location(仅 directory)|/api/permission/request|/api/form/request
- T(V1→V2 词汇分裂——重要):abort→interrupt L96,209-216；patch title→rename L97,198-207；summarize→compact L1015-1027(函数名仍 summarizeSession)；auth→credential L1335-1358；dispose(/global/dispose,/instance/dispose)→service/stop L1411-1423；file(/file,/find)→fs(/api/fs/*) L1427-1502；revert(V1 单端点)→revert/stage+revert/clear(只 stage 不 commit) L1029-1051；permission reply {reply:"once"|"always"|"reject"} vs legacy {effect:"allow"|"deny"} L844-887；agent 显示名(name"Plan")vs id("plan") resolveAgentId L623-653；cursor=服务器 base64url {id,order,direction}(direction next=更旧/previous=更新) L394-398；X-Next-Cursor(V1) vs cursor 对象(V2)
- T(其他):prompt admission 双读 payload.text/prompt.text L529-531；files 附件 {uri:dataUrl,name} 平铺/嵌套双契约 L451-511；model 独立切换端点(prompt 不带 model) L441-443；delivery:"steer" L521-522；activeSessions absent=后台/空闲 L249-254
- B:无（各契约注释均有代码对应；no-op/缺失端点均有行内注释佐证——getSessionDiff L1004、share/unshare L1007-1013、deleteMessagePart L1225-1227、findSymbols L1533-1535、listSessionStatus L1134-1136）
- 备注(事实非注释):executeCommand L1084-1103 与 V1 同——agent/model/variant/parts 参数未进请求体（死参数）


### F:data/di/DatabaseModule.kt
- LANG:中文（2 条行内注释）
- NOTE:Room 数据库 + 4 DAO + 时钟源提供
- T:WAL 模式(JournalMode.WRITE_AHEAD_LOGGING, targetSdk>=16 默认) L25；ocbeacon.db L26；MIGRATION_1_2/2_3/3_4 L27；"时钟源（归档桶时间戳用）" L42
- B:无

### F:data/di/PendingMessageDrainModule.kt
- LANG:中文
- NOTE:PendingMessageDrainController domain 接口绑定（#176/#177）
- T:drain=「手动放行」L12；PendingMessagePipeline(data 管线) vs PendingMessageDrainController(domain 接口) L7-8,20；Clean Architecture UI→Domain←Data L13
- B:无

### F:data/dto/common/ApiModels.kt
- LANG:无注释
- NOTE:ModelSelection/OutputFormat/PtySocket（WebSocket 帧封装）
- T:ModelSelection @SerialName providerID/modelID L14-15（API 原词 ✓）；PtySocket readLoop（Frame.Binary 首字节 0x00 跳过——termlib 协议残留防御）L35-46
- B:无

### F:data/dto/request/ChatRequests.kt
- LANG:无注释
- NOTE:prompt_async 请求体 DTO
- T:PromptRequest parts/model/agent/variant/format/system/noReply L8-16（prompt body 字段 ✓；noReply 为 camelCase 存疑待对照 API）；PromptPart type/text/path/mime/url/filename L19-26（✓）
- B:无

### F:data/dto/request/ConfigRequests.kt
- LANG:无注释
- NOTE:配置 PATCH DTO
- T:ServerConfigPatch disabled_providers/model/small_model/default_agent（@SerialName snake_case ✓）L7-11
- B:无

### F:data/dto/request/PtyRequests.kt
- LANG:无注释
- NOTE:PTY 创建/尺寸 DTO
- T:PtyCreateRequest(title,cwd)、PtyUpdateRequest(title,size)、PtySize(rows,cols)（✓）
- B:无

### F:data/dto/request/QuestionRequests.kt
- LANG:无注释
- NOTE:V1 question reply DTO
- T:QuestionReplyBody answers: List<List<String>>（V1 契约 ✓）
- B:无

### F:data/dto/request/ShellRequests.kt
- LANG:无注释
- NOTE:会话内 shell 命令 DTO
- T:ShellRequest(agent,model,command)（✓）
- B:无


### F:data/dto/response/ConfigResponses.kt
- LANG:无注释
- NOTE:服务器配置响应 DTO
- T:ServerConfigResponse disabled_providers/enabled_providers/model/small_model/default_agent/mcp（snake_case @SerialName ✓）
- B:无

### F:data/dto/response/FileResponses.kt
- LANG:中文（KDoc+行内）
- NOTE:/find 搜索、文件内容、VCS DTO
- T:SearchMatchDto path/lines(嵌套{text})/line_number/absolute_offset(snake_case ✓)/submatches{match{text},start,end}（引 docs/opencode-api-reference-v1.md）L8-30；FileContentDto type(text|binary)/content/diff/patch/encoding/mimeType（D3-003 修正）L33-40；FileNodeDto——V2 /api/fs/list 响应无 name 字段（只有 path/type）L44；ServerPaths home/state/config/worktree/directory L55-58；VcsChangeDto file/additions/deletions/status；VcsBranchDto branch/default_branch（snake_case，D3-002）L63-74；FileDiffDto file/patch/additions/deletions/status
- B:无

### F:data/dto/response/McpResponses.kt
- LANG:无注释（行内枚举值）
- NOTE:MCP 状态与服务器配置 DTO
- T:McpStatusEntry status 枚举 connected|disabled|failed|needs_auth|needs_client_registration（✓）；error（failed/needs_client_registration 携带）；McpServerConfig type/command/enabled/url/environment/headers
- B:无

### F:data/dto/response/PermissionResponses.kt
- LANG:中文
- NOTE:权限/问题请求 DTO；V1/V2 字段双轨
- T:PermissionRequest id/sessionID L10-11（✓）；V1 契约=工具权限名（如 bash）vs V2 REST PermissionV2.Request {id,sessionID,action,resources,save?,metadata?,source?}——无 permission 字段（F6 修复注释）L13-21；action（shell/edit/web...）语义对应 V1 permission L23-24；resources 语义对应 V1 patterns L25-26；save（reply=always 时服务器落规则）L27-28；QuestionInfo key（V2 form field key q0/q1...；V1 为 null）L49-50；QuestionOption value（V2 form option value 提交用；V1 为 null）L57-58
- B:无

### F:data/dto/response/ProviderResponses.kt
- LANG:无注释
- NOTE:提供商/模型目录 DTO（V1 形态；V2 由 V2ApiClient 组装回此形态）
- T:ProvidersResponse providers/default；ProviderCatalogResponse all/default/connected；ProviderInfo id/name/source/env/key/options/models；ProviderModel providerID(@SerialName ✓)/family/status/capabilities/cost/limit/variants(Map——V1 map 形态)；ModelCapabilities temperature/reasoning/attachment/toolcall（V1 命名；V2 为 tools/input/output，见 V2ApiClient L769-780 推断逻辑）；ModelCost input/output/cache{read,write}；ModelLimit context/input/output
- B:无（无注释文件）

### F:data/dto/response/PtyResponse.kt
- LANG:无注释
- NOTE:PTY 信息 DTO
- T:PtyInfo id/title/command/args/cwd/status/pid（✓）
- B:无

### F:data/dto/response/ToolResponses.kt
- LANG:无注释
- NOTE:agent/命令/技能 DTO（文件名 ToolResponses 但内容是 agent/command/skill——命名错位，事实记录）
- T:AgentInfo name/description/mode(primary)/hidden/color；CommandInfo name/description/source/hints；SkillInfo name/description/location/content
- B:无

### F:data/dto/response/V2Responses.kt
- LANG:无注释
- NOTE:杂项 DTO（todo/会话状态/shell/symbol/文件状态）——文件名 V2Responses 但多为 V1 形态 DTO（事实记录）
- T:TodoItem id/content/status(pending)/priority(medium)（✓与 todo.updated 事件字段一致）；SessionStatusInfo status:Map；ShellInfo path/name/acceptable（V1 /pty/shells 形态）；SymbolInfo name/kind/path/line/language；FileStatusInfo path/status/staged
- B:无


### F:data/github/ErrorReportService.kt
- LANG:中文
- NOTE:错误上报服务（#151）：指纹双轨+查重+24h 防刷+正文构建
- T:指纹 fingerprint 双轨 fp:err:<category>:<归一化msg>（跨版本）/ fp:crash:<VERSION_NAME>:<异常类名>（同版本）L48-55；归一化（数字→N/hex→HEX/路径→PATH/quoted→STR）L57-65；防刷 SuppressedDuplicate L72-73；install_id L116；needs-triage 标签 L102；[user-report] 前缀 L102
- B:无（24h/20+3 常量与代码一致）

### F:data/github/GitHubApiClient.kt
- LANG:中文
- NOTE:GitHub Issues API 薄客户端（search/create issue/comment）
- T:GITHUB_TARGET_REPO 固定 fork（LeoNardo-LB/oc-beacon）L22-23；端点 /search/issues?q=（GET，2026-08-23 修复：原 POST 404）、/repos/{repo}/issues、/repos/{repo}/issues/{n}/comments L60-94；错误映射 401 重新授权/403 限流/网络错 L28-34；is:issue is:open + 指纹冒号→空格短语搜索 L52-58
- B:无

### F:data/github/GitHubDeviceFlowAuth.kt
- LANG:中文
- NOTE:GitHub App device flow 认证（#151）
- T:device flow 端点 github.com/login/device/code 与 /login/oauth/access_token、api.github.com L21-26；user_code/verification_uri/device_code/interval/expires_in L28-35,84-94；authorization_pending/slow_down/access_denied/unsupported_grant_type L40-43,118-121；grant_type=urn:ietf:params:oauth:grant-type:device_code L111；强制 HTTP/1.1（h2 协商被中间层干扰）L59-74；scope=public_repo L82
- B:无

### F:data/github/GitHubTokenStore.kt
- LANG:中文
- NOTE:GitHub token 加密存储（DataStore github_report）+ install-id
- T:v1: 前缀密文（AES/GCM，与服务器密码同款）L26-27,50；降级明文（优雅降级语义）L42,50；install-id（UUID，统计独立报告者）L62-68
- B:无


### F:data/local/ArchiveBucketDao.kt
- LANG:中文
- NOTE:归档桶 DAO；#72 桶边界→消息级过滤修复
- T:归档桶 bucket（时间窗口内多条消息的压缩 BLOB）L19-24；热表/归档并存 L14；latestBefore（bucketStart 相交而非 bucketEnd）L18-25；leastAccessed（保护上限淘汰候选/最久未访问）L30-32；touch（lastAccessedAt）L40-41
- B:无（#72 注释与 SQL 一致）

### F:data/local/ArchiveBucketEntity.kt
- LANG:中文
- NOTE:归档桶实体（archive_buckets 表）
- T:bucketStart/bucketEnd/messageCount/uncompressedSize/payload(zstd 压缩 BLOB)/lastAccessedAt L18-28；ByteArray equals/hashCode 引用相等警告 L10-12
- B:无

### F:data/local/ArchivedMessageDto.kt
- LANG:中文
- NOTE:归档桶内单条消息 DTO
- T:info(Message)+parts——与 MessageWithParts 同构 L9-11
- B:无

### F:data/local/CachedMessageEntity.kt
- LANG:中文
- NOTE:消息缓存实体（cached_messages 表，BLOB 化 payload）
- T:Telegram 同款 BLOB 化 L9；msg_ ULID（单调递增，去重/游标）L23；ses_ ULID L24；time.created 毫秒排序键 L25；role user/assistant L26；v3 复合索引（sessionId+created DESC+id DESC tie-breaker）L15-19
- B:无

### F:data/local/CachedPartEntity.kt
- LANG:中文
- NOTE:消息部件缓存实体（独立表防写放大）
- T:48ms token delta/写放大 L9-11；type（text/tool/code 等——'code' 为客户端口语变体，API part 类型无 code）L28；text 流式更新热点 L29；FK CASCADE L14-21
- B:候选——L28 注释'type: text / tool / code 等'：Part 域类型全集无 code（API part 类型=text/reasoning/tool/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent/session-turn）。'code'疑为历史残留。修订方向：按实际类型枚举

### F:data/local/DatabaseRecovery.kt
- LANG:中文
- NOTE:Room 损坏自愈（仅 SQLiteDatabaseCorruptException 删库重建）
- T:删库自愈/优雅降级 L13-26；明确排除 Full/Locked/Constraint/DiskIO（误删全库=灾难性数据丢失）L19-22；ocbeacon.db L66
- B:无

### F:data/local/LogDao.kt
- LANG:中文
- NOTE:诊断日志 DAO（ERROR/FATAL 分级保留策略）
- T:level 常量 ERROR/WARN/INFO/DEBUG/FATAL；FATAL=崩溃记录 L31；deleteOrdinaryBefore/deleteErrorBefore 分级 L23-29
- B:无

### F:data/local/LogEntity.kt
- LANG:中文
- NOTE:诊断日志实体（logs 表，原 DiagnosticLogDatabase 等价迁移）
- T:details=Map<String,String> JSON 编码 L8-9,21；byteSize L22
- B:无

### F:data/local/LogStore.kt
- LANG:中文
- NOTE:诊断日志存储（3 天/21 天/50 条 FATAL/10MB 预算修剪）
- T:修剪策略常量与 KDoc 完全一致 L9-13,54-61（普通 3 天、ERROR/FATAL 21 天、FATAL 50 条、10MB、100 条/批）
- B:无


### F:data/local/MessageDao.kt
- LANG:中文
- NOTE:消息缓存 DAO（增量 append UPSERT/游标分页/分块 IN/prune）
- T:appendPartText（O(delta) 写/UPSERT 幂等去重 CASE 分支）L18-34；ULID 字典序=时间序 L49,56；游标分页向旧/向新（beforeId/afterId）L47-59；role 值域 user/assistant/synthetic/compaction/system L62-63；SQLITE_IN_VARIABLE_LIMIT=900（999 上限留余量）L94-97
- B:无

### F:data/local/MessageStore.kt
- LANG:中文
- NOTE:消息本地缓存核心（热表 1000 条/归档桶 TLRU/delta 增量/对账替换）
- T:热表/归档分层（SESSION_MESSAGE_LIMIT=1000、桶窗口 1 天、512KB/200 条分桶、ARCHIVE_BUCKET_LIMIT=200 TLRU）L24-29,525-532；persistOldBeyondWindow（窗口外不落库防写了又被裁）L24-25,98-115；48ms token delta/写放大 L42-45；#79 tool/reasoning 落库截断 500 字符预览（'…[truncated, full output on server]'）L140-151；typeName() Part 类型全集 text/reasoning/tool/shell/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent/permission/question/session-turn/unknown L495-514（注意：比 MessageEventParser 多 shell/permission/question/unknown——本地扩展类型）；role='user' 排除 synthetic L61-63；对账 replaceSessionMessages（服务器权威全量替换）L366-384
- B:无（L492-494 注释'Permission/Question 在序列化器中未映射，此处补全以穷尽 sealed'与 when 分支一致）

### F:data/local/Migrations.kt
- LANG:中文
- NOTE:Room 迁移 v1→v4
- T:v2=archive_buckets；v3=cached_messages 复合索引（sessionId,created,id）；v4=pending_messages（'turn 结束后待发送消息的本地暂存表'）L41-57；'禁止 DROP 重建'（OcBeaconDatabase KDoc）
- B:无

### F:data/local/OcBeaconDatabase.kt
- LANG:中文
- NOTE:Room 数据库（v4，5 实体 4 DAO）
- T:消息缓存+诊断日志+归档桶(v2)+堆积消息(v4) L7
- B:无

### F:data/local/PartDelta.kt
- LANG:中文
- NOTE:SSE delta 增量落盘 DTO（#97 H-6）
- T:type='text'/'reasoning'（Part.typeName 语义）L6——注意此处 type 语义为 part 类型而非 field
- B:无

### F:data/local/PendingMessageDao.kt
- LANG:中文
- NOTE:堆积消息 DAO（队列语义：peek/dequeue/appendToTail/applyOrder）
- T:堆积消息队列（position ASC,id ASC 稳定排序）L9；原子弹出行首（推进管线/继续按钮）L19-25；appendToTail（max+1 防并发重号）L33-44；拖拽排序 applyOrder L58-62；#176/#177 状态补偿（心跳=当前有堆积的会话集合）L70-76
- B:无

### F:data/local/PendingMessageEntity.kt
- LANG:中文
- NOTE:堆积消息实体（pending_messages 表，2026-08-20 设计定稿）
- T:堆积消息=turn 结束后待发送的本地暂存消息 L8；position 会话内顺序 0 起 L10；仅纯文本（v1 设计：附件消息不 enqueue）L11；本库无 sessions 表不能用外键级联 L12-13
- B:无

### F:data/local/ToolOutputTruncator.kt
- LANG:中文
- NOTE:tool/reasoning 落库截断器（#79 P0+P1）
- T:工具返回值占 DB 97%（12.4MB/28MB 实测）L12-14；截断后缀字面量 '…[truncated, full output on server]'（DB 落库内容，会进入 UI 展示）L27；递归原语重写（结构保留）L94-121；截断是优化不是正确性 L22
- B:无

### F:data/local/ZstdCodec.kt
- LANG:中文
- NOTE:zstd 压缩编解码（解压需原始大小）
- T:uncompressedSize（归档桶表存）L6-8
- B:无


### F:data/mapper/ConfigMapper.kt
- LANG:中文
- NOTE:Config DTO↔领域映射
- T:disabledProviders/model/smallModel/defaultAgent 双向映射 L23-56
- B:无

### F:data/mapper/FileMapper.kt
- LANG:无注释（object FileMapper 整体无 KDoc）
- NOTE:文件 DTO→领域映射
- T:type 'directory'/'file'→DIRECTORY/FILE；'binary'/'text'→BINARY/TEXT（API 原词 ✓）L20-42；ServerPaths 五字段直映 L47-53
- B:无

### F:data/mapper/PermissionMapper.kt
- LANG:中文
- NOTE:权限 DTO↔领域映射；V2 action/resources 兜底
- T:always 双形态（V1 字符串数组如 ["*"] 非空即 true / V2 布尔）L16,57-69；F6：'V2 REST 条目无 permission/patterns——官方 PermissionV2.Request 用 action/resources 表达同一语义，兜底映射' permission=permission?:action、patterns=patterns.ifEmpty{resources} L30-33
- B:无

### F:data/mapper/ProviderMapper.kt
- LANG:中文
- NOTE:provider 目录 DTO→领域简化映射
- T:provider/model/ModelSelection/contextWindow/variantNames L21-68；已连接 connected（/provider 目录）L31-34,41-47
- B:无

### F:data/mapper/QuestionMapper.kt
- LANG:中文
- NOTE:问题 DTO↔领域映射（字段名相同、类型异包）
- T:QuestionInfo/QuestionOption vs QuestionAsked.Question/Option L11-13；key/value V2 字段透传 L43,52,55
- B:无

### F:data/mapper/VcsMapper.kt
- LANG:无注释
- NOTE:VCS DTO→领域映射
- T:status 'added'/'deleted'/'modified'→ADDED/DELETED/MODIFIED（null/未知→MODIFIED）L33-39
- B:无


### F:data/repository/AgentRepositoryImpl.kt
- LANG:无注释
- NOTE:agent/命令/文件搜索仓库（薄封装）
- T:无领域术语注释；标识符 listAgents/loadCommands/searchFiles
- B:无

### F:data/repository/DiagnosticLogRepository.kt
- LANG:中文
- NOTE:诊断日志仓库（脱敏+节流+DataStore 级别配置）
- T:level 常量 ERROR/WARN/INFO/DEBUG/FATAL（崩溃）L26；category 例 'SSE'/'REST'/'Uncaught exception' L27；脱敏 REDACTED/IP/PATH 占位 L160-202；#102 刷新节流 1s L56-57,203-204
- B:无

### F:data/repository/DraftDataStore.kt
- LANG:中文
- NOTE:草稿 DataStore（旧 File 格式一次性迁移）
- T:草稿 draft（session_drafts key / session_drafts.json legacy）L22,31；懒加载内存缓存/Mutex 并发保护 L33-41
- B:无

### F:data/repository/EventDispatcher.kt
- LANG:中文
- NOTE:SSE 事件分发器（handler 注册表替代单体 EventReducer；横切关注点）
- T:'替代单体 EventReducer' L29；事件处理器注册表（开闭原则，bind 映射 SseEvent 子类→handler，替代广播模型）L95-101；横切：SessionDeleted 级联清理/CommandExecuted 状态重置 L33-34；unread v2 迁移（'值域从客户端 now 变为服务器 completed，旧值不可比'——与 CONTEXT.md 红点时钟域词条直接呼应）L59-64；#174 FSM 回调收进 SessionStateCollaboratorImpl L75-76；#122 PermissionAutoApprover 接线 L50-52；session.next.moved→updateSessionDirectory L77-81；error 产生未读（客户端时刻显式例外——红点时钟域词条的'唯一例外'）L82-89；堆积消息管线 Provider 打破循环 L54-56；releaseSessionData（#89 内存泄漏：permission/question/FSM 状态保留=服务器状态镜像，24h staleness STATE_RETENTION_MS 兜底）L547-569；流式所有权（ownershipRegistry，另一服务器认领）L579-581
- B:无（中段 L120-506 截断待补读核对）

### F:data/repository/FileRepositoryImpl.kt
- LANG:中文
- NOTE:文件仓库（#137 全方法 IO 调度）
- T:V2 大目录 node_modules MB 级/ANR 53 秒实测 L26-28；findFiles(type='file')/findDirectories(type='directory') L50,87（API 参数原词 ✓）
- B:无

### F:data/repository/McpRepositoryImpl.kt
- LANG:无注释
- NOTE:MCP 仓库（状态+配置合并）
- T:type 默认 'local' L36（口语默认值）；status/command/url 合并自 config
- B:无

### F:data/repository/PendingMessagePipeline.kt
- LANG:中文
- NOTE:堆积消息推进管线（边沿触发+状态补偿三触发器；at-least-once）
- T:堆积消息/队列 drain/推进管线 L26-52；'自然成功 turn 结束（Busy→Idle 且事件为 SSE 自然成功信号）' L30-31；状态补偿三触发器 T1 心跳/T2 入队/T3 Idle 观察 L34-38；护栏（待答问题/权限跳过、服务器归属未知跳过）L39-40；at-least-once（POST 成功但 delete 失败宁可重复发送）L43-44；5s 静默无限重试（用户定案）L45,200；'模型/agent/variant 均不带（用会话/服务器默认）——入队时不快照 UI 配置' L48；in-flight 去重 drainingSessions（UI'发送中'）L50-51,62-63；插队语义 sendOneNow L170-171；spec 引用 docs/specs/2026-08-21-queue-drain-state-compensation-design.md L27
- B:无（T1/T2/T3/护栏/at-least-once 均与代码一致；sendText 确实不带 model/agent/variant L210-218）

### F:data/repository/PendingMessageRepositoryImpl.kt
- LANG:中文
- NOTE:堆积消息仓库实现（Room 持久化跨重启）
- T:重启语义=状态补偿接管（不再依赖边沿触发）L15-17；手动放行（面板'继续'+会话列表详情'继续发送堆积消息'）L17,153-168（Pipeline）；reorder 防御 UI 过期 id L41-46
- B:无


### F:data/repository/PermissionAutoApprover.kt
- LANG:中文
- NOTE:权限自动批准规则（DataStore 持久化，#122 接线）
- T:AutoApproveRule（toolName/sessionId/directoryPattern 匹配，见 EventDispatcher L241-243）；'总是'=addRule L43；respondPermission('once')（EventDispatcher L255）
- B:无

### F:data/repository/ServerDataStore.kt
- LANG:中文
- NOTE:服务器配置 DataStore（密码加密 v1: 前缀）+ 健康检查/版本探测
- T:已保存的 OpenCode 服务器 L29；密码解密/加密降级语义（密钥失效降级无密码、加密失败保持明文）L192-216；checkHealth=健康检查+版本检测（V1/V2）L121-167；UNKNOWN 保留原 apiVersion（不得降级）L142-152；#150 方案 B 探测排序 L128-134
- B:无

### F:data/repository/ServerRepositoryImpl.kt
- LANG:中文
- NOTE:ServerRepository 实现（服务器 CRUD+提供商管理薄包装）
- T:提供商管理 L66；Provider 连接状态与全局配置 L132；'阶段 3：已编译但尚未接入 UseCase'（SettingsRepositoryImpl）风格注释——本文件无
- B:无

### F:data/repository/ServerTerminalRegistry.kt
- LANG:中文
- NOTE:服务端终端工作区注册表（按服务器缓存 workspace）
- T:服务端终端工作区 L12；backlog #38 占位连接回填 L34-46；工作区销毁防泄漏 L48-65
- B:无

### F:data/repository/SessionStateCollaborator.kt
- LANG:中文
- NOTE:FSM 必需协作者单一接线点（#174，与 CONTEXT.md'必需协作者'词条直接对应）
- T:'原 8 个可缺省 var 回调（漏接即静默降级：directoryResolver 默认 null → REST 打错路由）收拢为 interface：全抽象、无默认，构造期注入——漏接从静默降级变为编译错误' L22-23（≈CONTEXT.md 词条原文语义）；语义分工四域：消息域（流式保护/L3+断连补漏回写/增量游标锚点/终态兜底）/路由域（REST 按目录路由）/合法性域（僵尸判定防护）/副作用域（堆积消息推进）L25-30；SSE_PRIORITY vs REST_AUTHORITY 合并策略 L82-84；僵尸误杀防护（待答 question/permission、活跃子会话 parentID+服务器 /active 对照）L91-109
- B:无

### F:data/repository/SettingsDataStore.kt
- LANG:中文
- NOTE:应用设置 DataStore（语言镜像/已读状态/会话标签/模型可见性/默认模型）
- T:语言镜像（真相源 DataStore+SharedPreferences 镜像，落后不超前）L145-174；unread v2 迁移（'值域从客户端 now 变为服务器 completed，旧值不可比'——红点时钟域词条）L477-492；allReadAt/markSessionRead（'服务器 completed''服务器时刻'反复强调）L426-458；默认模型'🟠 妥协标记：V2 服务器 config.model 只读（PATCH 404），默认模型只能客户端本地存' L370-372；内置收藏标签（TagType.FAVORITE，中文名'收藏'字面量）L120-128；收藏迁移复活防御 L599-604
- B:无

### F:data/repository/SettingsRepositoryImpl.kt
- LANG:中文
- NOTE:SettingsRepository 薄委托
- T:'阶段 3：已编译但尚未接入 UseCase。阶段 4 将把 SettingsViewModel 的直接调用迁移' L16-17（阶段注记，非失实但为过程标记）；#134 单次 edit 原子落盘（原 21 次独立 edit）L86-88
- B:无

### F:data/repository/EventDispatcher.kt（补：中段 L119-508 已读）
- 补 T:registry bind 分组注释（'消息（updated/removed/part×3）''杂项（todo、command、pty、workspace、file、vcs、install、lsp）'）L119,138；'SessionStateService.statusFlow 的门面——会话状态的单一真相源' L164-165；多服务器去重/会话所有权（ownershipRegistry.claim，'防止流式输出翻倍'）L224-227,267-277；backfillActiveForServer（cursor 增量+SSE_PRIORITY 合并）L228-236；红点时间源解耦（'markSessionIdle（客户端 now，UI 流式终止）' vs '服务器 completed'——红点时钟域词条实现级表述）L349-355；seedCachedMessages（'不喂红点……DB 回环由此封死（#171）'）L480-487；SSE_PRIORITY/REST_AUTHORITY 策略名 L466-468；clearRevert（'已回退的消息会短暂重现……可见的闪烁'）L451-461
- B 维持:无（全部核实）


### F:data/repository/ChatRepositoryImpl.kt
- LANG:中文
- NOTE:ChatRepository 实现（桥接领域接口与 EventDispatcher/API；578 行）
- T:'阶段 3：已编译但尚未接入 UseCase。阶段 4 将把 ViewModel 的直接调用迁移' L54-55（过程阶段注记）；冷启动种子化（内存热视图为空从 Room 读最近缓存）L81-83；#76 降序/升序归并错乱修复 L93-97；#171 缓存种子走纯缓存入口（'DB 回读载荷不喂红点水位线'——红点时钟域）L101-103,226-231；本地播种/'发送后无气泡'（V2 admission 立即播种，V1 依赖 SSE 回显）L226-248；内存热视图 L91；#90 工具展开状态记忆 LRU（上限 1000）L70-76,389-392；F6 action/resources 兜底映射 L447-449
- B:无（'阶段 3/4'注记是历史过程标记非行为描述，不算失实但属可清理残留）


### F:data/repository/SessionRepositoryImpl.kt
- LANG:中文
- NOTE:SessionRepository 实现（含 #91 listMessages 在途去重、状态同步）
- T:'阶段 3/4'过程注记 L29-30；#91 在途去重（GET 幂等，'不缓存：消息流是活数据'）L42-74；hydrate 回填（SSE todo.updated 增量覆盖）L172-175；堆积消息级联删除（REST+SSE 双保险幂等）L38-39,180-182；archive=updateSessionFields({archived:true}) L209-217（归档语义靠任意字段 PATCH）；compactSession→summarizeSession L231-239（域名词 compact/summarize 并存）；abort→abortSession(内含 V2 interrupt) L192-195；fetchSessionStatuses 未知 type 跳过（'绝不把服务器说活跃翻译成 Idle'，backlog #70）L338-356
- B:无

### F:data/repository/ShellJobsStore.kt
- LANG:中文
- NOTE:后台 shell 状态容器（单一真相源）
- T:'单一真相源' L13（与 SessionStateService 同款措辞——一词多点位使用）；session.shell.started/ended + GET /api/shell 快照 L16-17；shell.exited payload {id,exit,status} 无 metadata.sessionID→全局查找修复 L54-59；REST 不返回 exited（保留已结束合并运行中）L98-107
- B:无

### F:data/repository/StreamingOwnershipRegistry.kt
- LANG:中文
- NOTE:多服务器流式会话所有权注册表
- T:流式会话所有权/'追加式事件（如 MessagePartDelta）会被应用两次，流式文本输出翻倍' L8-14；'同一 OpenCode serve 实例' L10（serve=服务器运行形态称呼）；claim/release/releaseAllForServer L23-38
- B:无


### F:data/repository/SessionStateService.kt
- LANG:中文
- NOTE:会话状态单一真相源（FSM+L2/L3/L4/L5 分层校验+SSE 断连补漏+僵尸判定，729 行）
- T:L2 staleness（15s 阈值/5s 检查）L37-38,117-141；L3 REST 校验（absence=idle 闭环/缺失语义/新鲜度护栏 ABSENT_FRESHNESS_GRACE_MS=60s）L490-582；L4 syncFromRest（跨 projects directory 聚合+本地缺失语义）L697-727；L5（Idle 但不完整）L125-128；僵尸判定 ZOMBIE_BUSY_MS=3min（'服务器 runner 卡死/僵尸 running'；pending 用户输入/活跃子会话不 interrupt；E2E-G 抖动循环修复注释）L58-61,516-560；'自动 zombie interrupt 已实证误杀……默认只修显示（本地转 Idle，不调服务器 interrupt）' interruptZombieRunner L667-695（**与 L549-554 的 interruptZombieRunner(sid,...) 调用矛盾——见 B**）；SSE_PRIORITY/REST_AUTHORITY L30-32,611；SSE 断连窗口补漏 backfillMissedMessages（cursor 增量+锚点窗口外兜底）L236-304；#172 游标策略收编（PaginationCursorPolicyFactory）L68-69,267-268,603-604；V2 无 session.status SSE/turn 权威信号 execution.started/succeeded L178-183；'#122 D2-15 仅时间戳变化短路'（LAST_EVENT_THROTTLE_MS=1s）L40-42,388-405；RS-010/RS-011/RS-012 并发防护 L372-374,478-487,505-508
- B:**L667-695 interruptZombieRunner KDoc 与实现不符**——KDoc 说'动作：调用服务器 interrupt/abort（按 apiVersion 分流：V2 POST /api/session/{id}/interrupt，V1 POST /session/{id}/abort）……interrupt 是 fire-and-forget'，但函数体已被改为'只修显示（auto interrupt disabled per official semantics）'：仅打 DEBUG 日志、**不调用任何服务器端点**（V1/V2 interrupt/abort 均未调用）。且 L553-554 僵尸路径仍在调用它并配'POST interrupt 返回 204……僵尸被解除'的老注释（L547-552）。修订方向：KDoc 改为'僵尸解除已停用（对齐官方语义），仅本地显示修复'；L547-554 的 interrupt 注释同步更新
- B 候选2:L549 '用户再发消息 POST /prompt 虽 200+admitted，但僵尸 runner 永不消费 inbox'——行为描述成立，但这是停用前根因记录；保留为历史依据

### F:data/repository/UnreadBadgeService.kt
- LANG:中文
- NOTE:红点时间源单一真相源（时钟域编码进事件类型 #171）
- T:UnreadEvent 三类型（ServerMessageCompleted/RestSnapshot=服务器时刻；SessionErrorOccurred=客户端时刻'唯一的故意例外（research/11 P1）'）L26-55（**与 CONTEXT.md 红点时钟域词条逐点对应**）；'时间戳域从注释升级为签名上的显式契约''传错编译不过' L32-33；maxCompleted 只增不减 L62-65；红点判定 isUnread（Idle+水位线>max(已读,一键已读)，'全部服务器时刻，纯函数'）L199-215；内存即时已读 justRead（'红点闪一下再消失'消除）L147-152；#184 跨服务器时钟混合为已登记债务 L184-187
- B:无

### F:data/repository/VcsRepositoryImpl.kt
- LANG:无注释
- NOTE:VCS 仓库薄封装
- T:VcsDiffMode.apiValue（域枚举→API 值）L28-31
- B:无


### F:data/repository/handler/MessageEventHandler.kt
- LANG:中文
- NOTE:消息/part 共享状态存储+5 类消息事件 handler（1164 行；48ms delta 批处理+SSE 双写+合并策略）
- T:原三壳 handler #175 删除（MessagePart/MessageUpdated/MessageRemoved 纯转发壳）L23-25,38-39；48ms 批处理/重组频率/'1 次 StateFlow 更新=1 次重组' L82-91,146-154（**SSE 铁律相关**：'不要取消进行中的定时器——那会在 token 到达速率>1/48ms 时饿死 flush' L147-148 与 AGENTS.md 铁律同源）；SSE 双写（Room 落盘恢复）L27-28；#57 持久化 actor（单写协程+Channel 背压）L101-144；#95 热视图上限 MEMORY_SESSION_MESSAGE_LIMIT=1000（与 Room 对齐）L55-61,888-907；孤儿 part 自愈 ensureAssistantSkeleton（'V2 契约不发 message.updated，assistant 唯一播种入口是 session.step.started'）L325-373,463-467；mergeAssistantMeta 非空字段合并（step.ended 不抹 step.started 模型）L375-402；#87b 空 id part 内容匹配防御（'Got it. ... Got it. ...'重复实测）L478-491；'text.ended 是官方的全量值边界（Ended is the replayable full-value boundary——session-event.ts:209）必须直接覆盖' L532-543；mergePartsList（REST id='' vs SSE 派生 id 契约错位）L627-645；#109 dedupOverlappingTextParts（id 契约三代：''、msg_ord_N、msg_type_ord_N）L647-684；mergeSortedMessages 两路归并（Bug1/Bug2 修复详注）L686-794；#1657 tokens/cost 变更检测落盘 L918-959；三策略 SSE_PRIORITY/REST_AUTHORITY/APPEND_ONLY L874-885
- B:无（核实全部对应）

### F:data/repository/handler/MiscEventHandler.kt
- LANG:中文
- NOTE:杂项事件 handler（只管理 todos，其余日志确认）
- T:'移动端不需要 LSP 事件' L53；REST hydrate（进会话补首屏 todo）L28-31
- B:无

### F:data/repository/handler/PermissionEventHandler.kt
- LANG:中文
- NOTE:权限事件 handler（pending 管理+子会话聚合）
- T:'#136：PermissionAsked 是待用户决定的 pending 请求，非 auto-approved' L46-47；'PermissionReplied 表示请求已处理（从 pending 移除），非 auto-denied' L61-62（日志语义勘误注释）；'pending permissions 是服务器状态'（releaseSessionData 不清）L83-85；sourceSessionTitle（子会话来源标注）L96-122
- B:无

### F:data/repository/handler/QuestionEventHandler.kt
- LANG:中文
- NOTE:问题事件 handler（pending 管理+REST 轮询合并+子会话聚合）
- T:mergeFromREST（'V1 SSE question.asked 可能不含 tool 字段……REST 响应含 tool.messageID 轮询按 id 合并补全'）L69-95；'pending questions 是服务器状态，退出后仍应显示 Asking' L97-101
- B:无

### F:data/repository/handler/SessionEventHandler.kt
- LANG:中文
- NOTE:会话生命周期 handler（'会话 STATUS 不再在此跟踪——SessionStateService 是单一真相源'）
- T:L15-22 单一真相源转移注释；locallyClearedReverts（'防止陈旧的 SessionUpdated SSE 恢复 revert'）L117-125,240-245；#96/#89 泄漏清理 L136-149；updateSessionDirectory（session.next.moved，'对齐官方 TUI sync.tsx:300-314'）L211-226；compactedSessions（'压缩后服务器把历史替换为 compaction 消息+摘要'）L73-79
- B:无

### F:data/repository/handler/SessionNextEventHandler.kt
- LANG:中文
- NOTE:session.next.* 实时状态跟踪（agent/model/工具进度/步骤/压缩/shell/usage）
- T:ToolProgressInfo childSessionId（#180 progress metadata.sessionID 推断）L26-27,197-209；progress '整体替换语义（非拼接）'（research/08 P0）L188-191；'只记录不写全局 tracker（跨会话污染）' L247-250；sessionUsage（V2 session.usage.updated 服务器权威累计值）L240-254；trackSequence/gap 检测（durable.seq）L283-294；SessionNextEvent 全变体分发（AgentSwitched/ModelSwitched/Moved/Text×3/Reasoning×3/ToolInput×2/ToolProgress/ToolSuccess/ToolFailed/Step×3/Shell×2/Compaction×3/Prompted/Retried/UsageUpdated/Synthetic/Unknown）L116-162
- B:无

### F:data/repository/handler/ShellJobsHandler.kt
- LANG:中文
- NOTE:后台 shell 事件→ShellJobsStore 薄 handler
- T:session.shell.started/ended L9
- B:无

### F:data/repository/handler/SseEventHandler.kt
- LANG:中文
- NOTE:handler 策略接口
- T:无领域术语（handle(event, serverId) 契约）
- B:无


### F:data/terminal/PtyToTermlibAdapter.kt
- LANG:中文
- NOTE:PTY WebSocket→终端模拟器桥（#189 换件后通用 PTY 桥，历史名保留）
- T:'#189 换件后为通用 PTY 桥（历史名保留以减小 diff）' L20（**类名 PtyToTermlibAdapter 与实际依赖 termux emulator 的错位自述**）；'远程回显模型——回显由服务器 shell 负责，无本地回显路径' L22-23,102-103；单发送 actor（#116 D2-20 保序）L37-42；DECSET 光标键模式跟踪已删除 L24-25
- B:候选——类 KDoc L24-25 'DECSET 光标键模式跟踪已删除——termux TerminalEmulator/KeyHandler 内部完整处理'：这是删除说明非行为描述，不算失实；但 L28 '（ServerTerminalWorkspace 的 IO scope）'引用成立。维持无

### F:data/terminal/RemoteTerminalSession.kt
- LANG:中文
- NOTE:远程 PTY 版 Termux 终端会话（#189 换件；TerminalSession 桥接口）
- T:'Termux 原版 TerminalSession 绑定本地子进程（JNI.createSubprocess……）；OC Beacon 的 PTY 在 OpenCode 服务器侧' L17-19；'远程回显模型' L23；DA/DSR 响应序列 L27,115；对齐 Termux 原版 MSG_NEW_INPUT 语义（主线程 append）L142-153；NoopSessionClient L168
- B:无

### F:data/terminal/ServerTerminalWorkspace.kt
- LANG:中文
- NOTE:服务端终端工作区（tab 生命周期/重连/resize 防抖；641 行）
- T:重连退避 1/2/5/10/30s L30；resize 防抖 120ms（'合并高频 PTY resize 请求（如捏合缩放）'）L34-35,294-297；RecoveryAction 三态（Reconnect 仅 socket/Restart 重建 PTY）L354-379；cleanupScope（'服务端 PTY 残留（连接泄漏）'）L69-71,415-423；'PTY 仍然存在→重连可复用（Reconnecting）；没有 ptyId→PTY 已不存在，必须重新创建（Exited）' L484-486；预热 emulator（'懒创建会把首帧输出丢在 emulator==null 上'）L139-142
- B:候选——L6 '状态转移（由 dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace 驱动）'（TerminalTabState KDoc 中）：引用路径为 **ui.screens.chat** 包，但 ServerTerminalWorkspace 实际在 **data.terminal** 包（本目录）——KDoc 引用路径过期。见 TerminalTabState 条目 B

### F:data/terminal/TerminalTabState.kt
- LANG:中文
- NOTE:终端 tab 生命周期状态机+恢复策略纯函数（含真值表）
- T:五态 Starting/Connected/Reconnecting/Disconnected/Exited L19-34；真值表注释 L51-65（与 terminalRecoveryAction 实现逐行核对一致）；'陈旧的 404 不能拆除一个进行中或活跃的连接' L62
- B:**L6 '由 [dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace] 驱动'——包路径过期**：该类已迁至 data.terminal 包（同目录 import 可证，L13 'import dev.leonardo.ocbeacon.data.terminal.ServerTerminalWorkspace' 是本包内引用无需 import；实际类在 data/terminal/ServerTerminalWorkspace.kt）。修订方向：改为 data.terminal.ServerTerminalWorkspace

### F:data/local/ToolOutputTruncator.kt 之类已录——此批无遗漏


### F:data/security/SecretCipher.kt
- LANG:中文
- NOTE:Android Keystore AES/GCM 对称加密（v1: 前缀密文+解密记忆化）
- T:密文格式 v1:<base64(iv)>:<base64(ciphertext)> L19；旧明文透明兼容 L20,70；解密记忆化（StrictMode P2：KeyStore slow-call 3 类违规/125-165 条来源）L27-38；密钥失效（恢复出厂/备份还原）抛异常由调用方降级 L66-67
- B:无

### F:data/update/UpdateInstaller.kt
- LANG:英文（行内少量）
- NOTE:APK 安装 Intent（unknown sources/FileProvider）
- T:canRequestPackageInstalls/ACTION_MANAGE_UNKNOWN_APP_SOURCES；更新缓存目录约束（APK must be a file in the update cache directory）L32-39
- B:无

### F:data/update/UpdateModels.kt
- LANG:中文（少量）
- NOTE:更新元数据模型+校验策略（UpdatePolicy 纯函数）
- T:三 flavor applicationId 集合 L10-18；SHA256/SEMVER 正则（#106-4 预编译）L20-22；GitHubReleaseDto tag_name/html_url/draft/prerelease（GitHub API 原词 ✓）L36-42；UpdateState 六态 Idle/Checking/UpToDate/Available/Downloading/ReadyToInstall/Error L55-63；hasValidRichMetadata（packageName/apkUrl/sha256 三者全有或全无）L122-129
- B:无

### F:data/update/UpdateRepository.kt
- LANG:中文（行内注释）
- NOTE:应用内自更新（manifest/GitHub Release 双源+APK 校验）
- T:'Google Play (stable) 渠道禁用应用内自更新（政策禁止 REQUEST_INSTALL_PACKAGES 自更新）' L66,78,109（ENABLE_AUTO_UPDATE 门控）；三源 URL（releases/latest/download/update.json、raw.githubusercontent master/update.json、api.github.com releases/latest）L42-44；.apk.part 残留清理（#136 D2-L58）L69-70,163-164,215-220；SHA-256 流式校验+签名证书交叉验证 L178-261；版本三重验证（packageName/versionName+versionCode/签名）L229-242
- B:无

<!-- LEDGER COMPLETE: 114/114 -->

