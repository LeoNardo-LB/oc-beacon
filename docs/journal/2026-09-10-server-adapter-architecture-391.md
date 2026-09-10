# server-adapter-architecture-391（2026-09-10）

> 状态：进行中
> 关联：docs/specs/2026-09-10-server-adapter-architecture-design.md · backlog #391
> 来源：grilling 收敛的架构 spec（用户裁决「架构一次到位、不留技术债」）

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 切片 1：领域契约 + 注册表 + 适配器骨架 + 路由改走注册表

**目标**：建立唯一路由 seam，行为零变化（契约冻结起点）。

**落地**
- 领域层新增：ServerFeature（开放值类 id）、ServerUiSlot（纯枚举插槽键）、CoreFlags（不可推导行为位）、ServerAdapterResolver（唯一路由 seam 的领域安全投影：supportedTypes / wireGeneration / capabilities / uiSlots）。
- 数据层新增 data/adapter/：ServerAdapter（契约）、ServerPorts（端口集合 + derivedFeatures 派生能力）、OpenCodeServerAdapter、DshServerAdapter、ServerAdapterRegistry（构造期校验重复键 + 全类型覆盖；实现领域接口）、ServerAdapterModule（@Binds @IntoSet 多绑定）。
- 端口在场性按真实能力声明：OpenCode 全 7 端口在场；DSH 终端 / shell 缺席（terminal=null、shell=null），缺席由门面统一抛 UnsupportedServerCapability（与迁移前具体客户端降级同型）。
- 7 个领域门面（SessionApiImpl / MessageApiImpl / SystemApiImpl / FileApiImpl / ProviderApiImpl / TerminalApiImpl / ShellApiImpl）删除 when (conn.serverType) 三分，改 adapters.ports(conn).<port>；构造依赖由 (v1,v2,dsh) 变为 ServerAdapterRegistry。
- OpenCodeApp 启动期强制解析一次注册表（verifyComplete），IO 线程执行（构造触达 Keystore/DataStore，不占主线程）。
- spec 增补两行决策：契约归属（ServerAdapter / ServerPorts 与域端口同层——数据层；领域只持解析器投影）、增量冻结（connectionStrategy 切片 6 加入；ServerPorts 以新增可空字段扩展）。

**验证**
- :app:compileDevDebugKotlin BUILD SUCCESSFUL。
- 新增 ServerAdapterRegistryTest（11 例：重复/缺失覆盖、按连接解析、端口在场性=派生能力、DSH 无终端/shell 端口、OpenCode 版本→端口/世代映射、OpenCode/Dsh 核心标志、DSH 未探测保守 v011）。
- 既有方言契约测试改经注册表构造：V1V2DialectContractTest（22 处）、FileApiVcsTest、MessageApiCursorTest 全绿。
- 全量 :app:testDevDebugUnitTest：3253 例，1 败 = DraftInputDelegateTest.restorePersistedDraft 的 UncaughtExceptionsBeforeTest（跨类污染已知 flake：单独跑绿；journal 2026-08-30 #277 与 2026-09-01 有同型记录）。与本切片无引用关系。

**改动**：见 commit refactor: #391 切片1（领域契约 + 注册表 + 七门面改走唯一 seam）。

## 切片 2：能力改为端口派生 + 核心标志；删集中矩阵与连接对象能力 getter

**落地**
- ServerCapabilities 改为 (coreFlags, features) 值对象，只提供成员查询（in / supports）；逐能力布尔 getter 与集中矩阵 ServerCapabilities.of 删除。
- ServerConnection 退回纯数据（删除 capabilities getter）。
- 能力 = adapter.coreFlags(conn) + ports(conn).derivedFeatures() + adapter.privateFeatures(conn)，由注册表计算；适配器不手写布尔矩阵。
- 适配器声明非端口派生能力：OpenCode（命令 / 文件读 / vcs / 文件搜索 / 会话删除 / 撤销；V2 另加后台与排队，非 V2 为分享）；DSH（命令 / 目标 / 反馈 / 权限档 / 预设 / 归档 / 排队 / 排队编辑）。
- 领域接口新增 defaultCapabilities()（未就绪连接初始态由数据层按缺省类型解析，领域不硬编码类型语义）。
- 消费点迁移：5 个 ViewModel 注入 ServerAdapterResolver；UI 门控由 xSupported 改 ServerFeatures.X in caps，覆盖 ChatScreen / ChatScreenBottomBar / ChatEmptyState / ChatMessageList / SessionListScreen / SessionTreeList / ServerSettingsViewModel / WorkspaceViewModel 共 47 处主代码站点。
- Hilt：ServerAdapterModule 增加 @Binds ServerAdapterResolver -> ServerAdapterRegistry。

**验证**
- :app:compileDevDebugKotlin 绿；:app:compileDevDebugUnitTestKotlin 绿。
- 新增 ServerCapabilitiesDerivationTest（逐位等价网：OpenCode V1/V2/UNKNOWN、DSH；端口缺席不产生能力；缺省能力；逐连接计算）。
- 新增测试替身 FakeServerAdapterResolver（test 源集）；13 个 ViewModel 测试构造点 + 23 处 WorkspaceViewModel 构造点补齐依赖；PaginationCursorPolicyTest 的能力映射断言迁入新测试。
- 定向测试（Derivation / Registry / Pagination / ChatViewModelSend / SessionListShellState / WorkspaceViewModel）全绿。
- 全量 :app:testDevDebugUnitTest BUILD SUCCESSFUL。
- :app:lintDevDebug 仍红（4 项，均为存量：HiltEntryActivity MissingClass ×1 @6c41d0a2 2026-08-16；LocalContextGetResourceValueCall ×3 @f2df106c/2e4a4d58/54cbc555 2026-08-31~09-01）。ChatScreen numstat 9/9（行数不变、命中行未改），SettingsScreen 与 debug manifest 本批次零改动 → 与本切片无关，已登记 backlog。

## 切片 3：五类私有能力端口化 + 删仓库/委托类型守卫

**落地**
- 新增通用命名端口：GoalApi（6 mutation）/ FeedbackApi（3）/ SubagentApi（3）/ MessageQueueApi（2）落 data/api/；ServerSettingsRepository 由 DshSettingsRepository 更名（domain/repository/，16 方法不变）。
- ServerPorts 增 subagents / goals / feedback / queue / serverSettings 五个可空字段；derivedFeatures 派生 SUBAGENTS / GOALS / FEEDBACK / QUEUE / SERVER_SETTINGS；适配器私有声明移除对应重复位。
- DSH 适配器挂载薄委托端口实现（data/adapter/dsh/Dsh{Goal,Feedback,Subagent,Queue}Port）；V2 客户端实现 MessageQueueApi（inbox 域：移除/插话，无编辑动词）；V1 无队列端口。
- ChatRepositoryImpl 13 处 conn.serverType 守卫改经 adapters.ports(conn)：subagentPrompt/Interrupt/Catalog、messageFeedbackPut/Delete/List、goal 六 mutation、listQueueItems。写操作端口缺席抛 UnsupportedServerCapability，读操作返回空（用户故事 17）。
- ChatSendDelegate / SessionActionsDelegate 的 DSH 类型判定改为能力位（ServerFeatures.SUBAGENTS）驱动，界面不再读服务器类型。

**验证**
- :app:compileDevDebugKotlin 绿；:app:testDevDebugUnitTest 全量 BUILD SUCCESSFUL。
- ServerCapabilitiesDerivationTest 增 5 端口断言（OpenCode 无 SUBAGENTS/SERVER_SETTINGS；DSH 全在场；V2 有 QUEUE）。
- ChatRepositoryImplTest 增注册表装配；SessionListViewModel 系列 6 个测试参数更名。

**切片内未覆盖（如实登记，非五端口面）**
- ChatRepositoryImpl 残留 3 处类型守卫：archiveSession / listSessionsIncludingBlank / mentionCandidates（无对应端口）。
- TaskDelegate Shell 面板分流、PaginationCursorPolicy.forServer 手写三分、ChatViewModel queue 数据源分流（DSH 帧推送 vs V2 拉取）——归切片4/5。
- 端口载荷仍为 DSH 域模型（DshGoalRef / MessageFeedback* 等），端口命名已中立；模型重命名不在本切片。

## 切片 4：删除七个手写路由门面，调用方经解析器取端口

**落地**
- 删除 7 个门面类：SessionApiImpl / MessageApiImpl / SystemApiImpl / FileApiImpl / ProviderApiImpl / TerminalApiImpl / ShellApiImpl（接口保留）。
- 删除 di/ApiModule.kt（7 个 @Binds）与 androidTest di/FakeApiModule.kt（@TestInstallIn replaces=ApiModule）。
- ServerPorts 增可选端口取值入口：requireFile / requireProvider / requireTerminal / requireShell（缺席抛 UnsupportedServerCapability）。
- 11 个调用方改注入 ServerAdapterRegistry 并逐站点改经端口：SessionRepositoryImpl、ChatRepositoryImpl、AgentRepositoryImpl、ServerDataStore、FileRepositoryImpl、McpRepositoryImpl、VcsRepositoryImpl、ServerRepositoryImpl、ServerTerminalRegistry、ServerTerminalWorkspace、SseConnectionManager。
  - ServerDataStore 的 SystemApi 依赖本为死代码（零调用），直接删除。
  - ServerTerminalWorkspace 保留内部 api getter（按 conn 解析终端端口），调用点形态不变。
- 测试：新增 testing/TestServerAdapters.kt（FakeServerAdapter + testAdapterRegistry）；V1V2DialectContractTest 改为按连接解析端口（路由断言不变）、MessageApiCursorTest / FileApiVcsTest 同改；8 个仓储/服务测试构造点改经注册表。
- 清理 6 处指向已删除门面的现状性注释。
- 顺带修复 androidTest 存量漂移：FakeMessageCacheRepository 缺 deleteMessage（78023ffb 接口扩展后未跟）导致 androidTest 源集无法编译——补内存实现。

**验证**
- :app:compileDevDebugKotlin / :app:compileDevDebugUnitTestKotlin / :app:compileDevDebugAndroidTestKotlin 三者 BUILD SUCCESSFUL。
- 全量 :app:testDevDebugUnitTest BUILD SUCCESSFUL（3244 例）。
- 期间一次的 V1V2DialectContractTest 8 红为测试改写缺陷（把同一 api 绑定到单一连接），已改为逐调用按连接解析后全绿。

**残留（归切片5/8）**
- ChatRepositoryImpl archiveSession / listSessionsIncludingBlank / mentionCandidates 三处类型守卫（无端口）。
- TaskDelegate Shell 面板分流、PaginationCursorPolicy.forServer、ChatViewModel queue 双数据源分流。

## 切片 5：界面插槽注册表 + 宿主契约 + 首个私有界面扩展迁移

**落地**
- 界面层新增 ui/extension/：ServerUiExtension（槽位 + 顺序 + 能力位 isEnabled + Content）、ServerUiSlotHost 标记与 ProviderSettingsSlotHost、ServerUiSlotRegistry（按槽位分组；contributions = 能力过滤 + 排序；Render 组合渲染）、LocalServerUiSlots（staticCompositionLocalOf，默认空注册表——预览/组件级测试不崩）。
- MainActivity 注入 ServerUiSlotRegistry 并经 CompositionLocalProvider 提供：通用屏幕只读组合局部，不 import 具体服务器类型组件。
- 首个私有界面扩展迁移：DshProviderDirectoryExtension（ui/screens/server/providers/dsh/，自己包内 @IntoSet 注册 + 同包 Module）取代 ServerProvidersScreen.kt 的 if (uiState.isDsh) 硬嵌块。
- 通用提供商页改 LocalServerUiSlots.current.Render(PROVIDER_SETTINGS, caps, host)；ServerSettingsViewModel 增 serverCapabilities 流，DSH 目录加载门控由 serverType 特判改 SERVER_SETTINGS 能力位。
- DshServerAdapter 声明 uiSlots = { PROVIDER_SETTINGS }（适配器只声明，不含界面代码）。
- ServerSettingsUiState.isDsh 字段删除（迁移后零消费）。

**验证**
- 新增 ServerUiSlotRegistryTest（能力过滤 / 顺序排序 / 槽位隔离 / 空注册表）绿。
- :app:testDevDebugUnitTest 全量 BUILD SUCCESSFUL；:app:compileDevDebugAndroidTestKotlin BUILD SUCCESSFUL。

**残留（归切片9 或后续）**
- 其余 DSH 私有 UI 未迁移：SessionListScreen 的 DshTokenNeededBanner 手工 if 链（SESSION_LIST_HEADER 槽位）、ServerSettingsContent 的 DshServerConfigSection / DshPluginInventorySection（SERVER_SETTINGS 槽位）。
- 条目级动作贡献（FAB 工具栏等）属切片9 的统一贡献注册表（区域插槽 + 条目动作）。

## 切片 6：连接策略抽取（SSE / 多路复用）+ 服务层去类型化

**落地**
- 契约 data/adapter/ConnectionStrategy.kt：WireKind（SSE/MUX）、ConnectionStatus（ONLINE/AUTH_REQUIRED/UNREACHABLE）、Handshake（世代 + 鉴权态 + degraded + detail）、ConnectionStrategy（wireKind + 一次握手 probe）。边界铁律：不触碰平台服务生命周期（前台服务/通知/Activity）。
- 实现：OpenCodeConnectionStrategy（SSE；握手为已持久化探测结果的纯投影——ApiVersionDetector 双探在健康检查阶段完成；UNKNOWN 回落 V1 基线并标记 degraded）、DshConnectionStrategy（MUX；双形态探测判别三态，未探测保守 V011 + degraded）。
- DshProtocolSource 增 ensureProbed（带默认实现：只读/测试替身无需探测能力即可满足契约）；DshProtocolSourceAdapter 转发注册表。
- ServerAdapter 增 connectionStrategy；OpenCode 适配器内部构造（无依赖），DSH 适配器用既有 protocolSource 构造 → 测试构造点零改动；注册表增 connectionStrategy(conn)。
- SseConnectionManager：删除最后一处 conn.serverType == Dsh 分支，改 strategy.wireKind == MUX；DSH 握手改经 strategy.probe(conn)（三态 → Handshake）。服务层不再 import ServerType。

**验证**
- 新增 ConnectionStrategyTest（OpenCode V1/V2/UNKNOWN 映射与降级；DSH 三态映射与 degraded；注册表按连接返回策略）。
- 定向测试绿；:app:testDevDebugUnitTest 全量 BUILD SUCCESSFUL；:app:compileDevDebugAndroidTestKotlin BUILD SUCCESSFUL。
- 服务层 serverType 引用归零（仅剩历史注释）。

**残留（如实登记）**
- “监督层变薄”完成的是传输分支与握手收编；前台服务/通知/生命周期仍由 OpenCodeConnectionService 直接驱动，未抽成"从回调驱动"的薄层——留待后续收敛。

## 切片 7（上）：DSH 0.1.5 / 会话格式 V3 —— P0 历史空白修复

**根因**（调研 §7 P0-1/2/4）：未知 SessionEvent 词汇 → UNKNOWN_UNIGNORABLE → DshHistoryFolder.refusedRebuild → 整页空 MessagePage。V3 的 system/message、assistant/attempt、tool/ptc-dispatch*、deliverables/presented 等真实大量存在 → 几乎每个会话命中拒绝。

**落地（容错优先）**
- 映射器未知词汇改为**具名降级** DshIgnoreReason.UNKNOWN_DEGRADED（日志遥测，不拒绝重建）；新增 STRUCTURAL_VIOLATION 作为拒绝重建的**唯一判据**（当前无发射点，留乱序/种子缺失/surfaceOp 越界）。
- DshHistoryFolder 判据改 STRUCTURAL_VIOLATION，字段 unknownUnignorable → structuralViolations；KDoc 同步。
- V3 词汇补全：tool/ptc-dispatch(-start) 复用子代理卡映射（#349 真源）；system/message、assistant/attempt、feedback/message-put|delete、subagent/catalog、deliverables/presented 具名收编 SESSION_FORMAT_V3（渲染增强留后续）。
- surfaceOp 双读：信封级 replace 读 startSeq/endSeq 优先、回落旧 start/end（compaction shadowedRange 的 start/end 不动）。

**验证**
- 新增 DshV3AdaptationTest（PTC 派发等价 legacy、V3 六词汇具名收编、未知词汇不拒绝重建、surfaceOp V3/legacy 双读）。
- 既有 DSH 测试同步（DshHistoryFolderTest 两条拒绝断言改为降级语义；DshEventMapperTest 未知类型 → UNKNOWN_DEGRADED）。
- 全量 :app:testDevDebugUnitTest + androidTest 编译 BUILD SUCCESSFUL。

**切片7 残留（下）**
- P0-3 实时流式：session/follow opt-in assistantStream:true（V012+）+ mux transient/assistant-stream 值型分派 + mapper start/chunk/end 帧 → Part delta；无此改动 0.1.5 宿主仍无实时 token 流。
- P1 渲染增强：system/message 系统节点、assistant/attempt 失败尝试、deliverables 产物卡。
- 按代 EventVocabulary 表 + V015 世代（研究 §9 建议容错优先而非硬版本门禁）。

### 切片7（下）：V3 实时流式通道（assistant-stream）

- DshFollowTarget.followArgs(assistantStream=false)：0.1.2+ 宿主显式带 assistantStream:true（V011 不得携带，旧 zod 契约不认识该键）。
- DshRemoteMuxEngine 增 assistantStream 构造参数（构造点按 registry.protocolOf(baseUrl)==V012 判定）；follow open 帧据此带开关。
- DshMuxSynthesizer：onFollowValue 增 assistant-stream 值型分派；onAssistantStream 把 start/chunk/end 帧**合成 0.1.1 assistant/chunk SessionEvent**（attemptId → (turn,step) 登记，chunk 透传原 StreamChunk），复用既有 DshEventMapper chunk 映射与 dsh-t{turn}s{step} 流式宿主 ID 契约；end 仅清登记（终态骨架由整装 assistant/message 拆除）。
- emitRecord 增 transient 记录静默（V3 client-only 记录型）。

**验证**
- DshConnectionOrchestratorTest 增 assistantStream opt-in 断言（不显式开启则 request 无该键）。
- DshV3AdaptationTest 增合成 chunk → Part delta 契约断言（messageId=dsh-t1s2）。
- 全量单测 + androidTest 编译 BUILD SUCCESSFUL。

**残留**
- assistant-stream end(abandoned) 无对应 legacy 终态事件：骨架清理依赖整装消息，abandoned 场景可能留空骨架（待后续具名事件）。
- 按代 EventVocabulary 表 / V015 世代描述符未落地（当前为容错优先 + V012 分支）。

## 切片 8（上）：收尾——门禁恢复绿 / 选择器注册表驱动 / 契约测试 / 探测器去类型化 / 架构文档

- **#396 存量 lint 红清零（4→0）**：HiltEntryActivity 从 androidTest 迁到 src/debug（与 HiltComponentActivity 同源集——debug manifest 声明的类须真实存在于 debug APK）；3 处 LocalContextGetResourceValueCall 收敛到 ui/util/EventTimeString.kt 的 eventTimeString（事件时点本地化是有意例外，集中一处附理由）。:app:lintDevDebug BUILD SUCCESSFUL。
- **服务器选择器改注册表驱动**：HomeViewModel 注入 ServerAdapterResolver 暴露 supportedServerTypes；ServerDialog 增 serverTypes 参数并按枚举渲染 SegmentedButton（注册适配器即自动出现）；类型标签映射只存于用户选择面。
- **适配器契约测试** ServerAdapterContractTest：同一套断言跑遍真实适配器 + 假适配器（类型覆盖 / 能力 = coreFlags + 端口派生 + 私有 / 世代非空 / 缺席端口不产生能力 / 插槽声明）。
- **版本探测器去类型化**：ApiVersionDetector.detect 删除 serverType 参数与 DSH 短路；ServerDataStore.checkHealth 改按新增的领域投影 ServerAdapterResolver.transportKind（领域 TransportKind）决定双探是否适用——探测管线不再认识服务器类型。
- **架构文档登记适配层**：docs/architecture.md 增「服务器适配层（ServerAdapter）」章节 + 目录树补 data/adapter、ui/extension + 承重规则（类型判断白名单）。

**验证**：:app:lintDevDebug 绿；全量单测 + androidTest 编译 BUILD SUCCESSFUL。

**残留（切片8 下 / 后续）**
- 自定义 Android Lint 规则（类型分支白名单 / UI 不 import data / 绕过令牌的硬编码）未落：需新增 lint-checks Gradle 模块，与 Out of Scope「不拆 Gradle 模块」存在取舍，需裁决；当前以架构文档承重规则 + code review 兜底。
- ServerDialog 内 isDsh 分支（用户选择面白名单内）保留。

## 切片 9（步骤 1）：队列数据源差异升格为能力位

- 新增 ServerFeatures.QUEUE_PUSH（core.queue.push）：队列由服务器帧推送 vs 客户端拉取，
  数据源差异只对上层暴露为能力位；DshServerAdapter 声明该位。
- ChatViewModel.queueItems 的服务器类型分支改按 QUEUE_PUSH 能力位选择数据源；
  refreshQueueItems 的 DSH 早退守卫同改（原本已同时判 QUEUE 能力位）。

**验证**：compile + 全量单测 + androidTest 编译 BUILD SUCCESSFUL；主代码 serverType 引用减少 2 处。

（两轴 code review：Spec 轴 778d5be9 已完成，报告 /tmp/review/spec.md；Standards 轴 47d94616 运行时被暂停点截断。）

## 切片 9（步骤 2-3）：类型分支归零（领域层 / 子智能体 / 计划 / 任务面板 / 会话列表）

**步骤 2（32b2a26c）**：PaginationCursorPolicyFactory 不再读 ServerConfig.serverType；改注入领域安全的 ServerAdapterResolver 并按 transportKind 投影判定 MUX。

**步骤 3**：
- 新增能力位 JOBS_PUSH（core.jobs.push）与 PLAN（core.plan），DshServerAdapter 声明。
- SubagentComposerGate / PlanChipGate 参数由 ServerType 改为能力布尔；SubagentModeTracker 改 subagentsSupportedFlow。
- TaskAggregator 改 capabilitiesFlow（SHELL / JOBS_PUSH 决定数据源）；TaskUiState 删除 serverType 字段（UI 零消费）。
- ChatViewModel 删除 _serverType / serverType（StateFlow）；ChatScreenBottomBar 改读能力位。
- SessionListViewModel _serverIsDsh → _usesWorkspaceProjections（SERVER_SETTINGS 能力位）。
- 测试：SubagentComposerGateTest / PlanChipGateTest / SubagentModeTrackerTest / TaskAggregatorJobsBranchingTest 改能力断言；FakeServerAdapterResolver 改为按连接类型给能力（DSH 预设）。

**验证**：compile + 全量单测 + androidTest 编译 BUILD SUCCESSFUL。

**仍余（切片9 后续）**：ChatRepositoryImpl archive/listBlank/mention 三处守卫、ServerCard 类型徽标（持久化身份展示）、uiSlots 声明消费与 seam-1 断言、STRUCTURAL_VIOLATION 无发射点、architecture.md 措辞。

## 切片 9（步骤 4-5）：uiSlots 声明消费 + 私有能力端口化收口

**步骤 4（4303bdf0）uiSlots 声明成为渲染门禁**
- ServerSettingsViewModel 暴露 uiSlots（init 时按连接解析）；ServerProvidersScreen 的 PROVIDER_SETTINGS 渲染改为两级门禁：适配器声明（uiSlots）先决 + 贡献方 isEnabled(caps) 细粒度过滤——未声明的槽位不再进入渲染路径。
- ServerAdapterContractTest 增 seam-1 断言：任一类上被 isEnabled 命中的贡献，其槽位必须在 real.uiSlots 内（声明与贡献不一致即静默丢失内容的暗坑被钉死）。

**步骤 5 仓库层三处类型守卫端口化（本步）**
- 新增端口契约：WorkspaceApi（归档 + 含 blank 全量列表）、ReferenceApi（@ 引用候选）、AttachmentApi（附件字节）；ServerPorts 以「新增可空字段 + 默认 null」增量挂载，不改既有字段语义。
- 实现：DshWorkspacePort / DshReferencePort / DshAttachmentPort（薄委托 DshApiClient）、OpenCodeReferencePort（findFiles 现参数形，v1/v2 各持一个实例）。
- 能力派生：derivedFeatures 从 workspace 端口在场派发 WORKSPACE + SESSION_ARCHIVE；DshServerAdapter.privateFeatures 不再手写 SESSION_ARCHIVE。SessionListViewModel 的 workspace 投影门禁由 SERVER_SETTINGS 改为语义更准的 WORKSPACE。
- ChatRepositoryImpl：archiveSession → requireWorkspace；listSessionsIncludingBlank → workspace?; mentionCandidates → requireReferences（DSH 两域合并策略整体移入端口）。顺带把 fetchAttachmentDataUrl 的 DshApiClient 直连改经 attachments 端口——仓库构造函数不再注入 DshApiClient，仓库层零服务器客户端直连。

**行为等价说明**：OpenCode 附件路径由「向 DSH 端点发一次注定失败的 RPC 再降级 null」改为「端口缺席直接 null」，用户可见结果仍为 null；其余路径逐位等价。

**测试**：ChatRepositoryImplTest 改端口化装配（workspace/attachments 仅 DSH 在场，断言非 DSH 走空表/unsupported/null）；新增 DshReferencePortTest / OpenCodeReferencePortTest；ServerAdapterContractTest 增端口在场与派生能力断言；ServerCapabilitiesDerivationTest 增 WORKSPACE 位；FakeServerAdapterResolver 的 DSH 预设补 WORKSPACE。

**验证**：:app:testDevDebugUnitTest（3271 例）+ :app:compileDevDebugAndroidTestKotlin + :app:lintDevDebug 均 BUILD SUCCESSFUL。

**仍余（切片9 后续）**：STRUCTURAL_VIOLATION 无发射点（拒绝路径死代码）、architecture.md 措辞与代码对齐、SessionListScreen 令牌横幅等两处类型私有界面未迁插槽。


## 切片 9（步骤 6）：激活拒绝重建判据（surfaceOp 越界）

- 问题：DshIgnoreReason.STRUCTURAL_VIOLATION 曾被声明为「拒绝重建的唯一判据」，但映射层无任何发射点——DshHistoryFolder.refusedRebuild 恒 false，属死路径（两轴 review 已标记）。
- 落点：user/message 的 surfaceOp.replace 区间判定（DshEventMapper.mapUserMessage）。原实现把「残缺（缺 start/end）」与「越界（end < start）」合并为一条日志忽略；现拆分为：残缺仍只记日志（不拒绝），越界发射 DshMappedEvent.Ignored(STRUCTURAL_VIOLATION)。
- 拒绝语义：该行进入 DshHistoryFolder 后 structuralViolations 非空 → refusedRebuild=true，调用方放弃本次残缺重建（DshConnectionOrchestrator / DshApiClient 既有消费路径不变）。
- 判据边界诚实化：mapper / folder 的 KDoc 由「乱序 / 种子缺失 / surfaceOp 越界」改为「当前唯一实发射点 = surfaceOp 越界；乱序 / 种子缺失保留判据位待取证」。
- 测试：DshEventMapperCompaction378Test 越界用例改断言 Ignored(STRUCTURAL_VIOLATION)，新增残缺用例断言不落违约；DshHistoryFolderTest 新增越界行整页拒绝用例。

**验证**：DSH 定向测试 + 全量单测 + androidTest 编译 BUILD SUCCESSFUL。


## 切片 8（下）：自定义 Android Lint 规则模块（#397 首条规则）

- 新增工具链模块 :lint-checks（java-library + kotlin jvm 插件；Kotlin 版本由 AGP 9.3.2 引入的插件 classpath 提供，缓存具 lint-api 32.3.2，离线可构建）；settings.gradle.kts 纳入，app 端 `lintChecks(project(":lint-checks"))` 接入既有 lint{baseline; abortOnError=true} 门禁（工具链模块，非应用层拆分，不进 APK）。
- 首条规则 ServerTypeWhitelist：文本级 Detector（剥离行/块注释后命中 ServerType 词元）+ 路径白名单（domain/model、domain/adapter、data/adapter、ServerDataStore、service、ui/screens/home、MainActivity、PaginationCursorPolicy）；越界报 ERROR。
- 包名落 lintrules（lint/ 目录名撞 .gitignore 既有 lint/ 规则，改名规避）。
- 实证：临时探针文件触发 "ServerTypeWhitelist from dev.leonardo.ocbeacon.lintrules" error（lint 中止并 abortOnError 失败）；删除探针后 :app:lintDevDebug 绿——规则确已加载且能拦截。
- 剩余两条规则（通用界面 import 具体服务器类型组件 / 令牌绕过：硬编码色值·间距·时长）仍挂 #397。

**验证**：:app:lintDevDebug BUILD SUCCESSFUL；:app:testDevDebugUnitTest（3273 例）+ :app:compileDevDebugAndroidTestKotlin 绿。另：RenderSupplyCoordinatorTest T11 在 --rerun-tasks 全量运行时一次性失败、隔离运行绿（既有跨类污染 flake，与本批改动无关）。

## 切片 9（步骤 7）：架构文档与代码对齐

- docs/architecture.md：目录树补 attachment/workspace/reference 扩展域端口与 opencode/ 端口实现；插槽段改为「适配器声明 + 贡献方能力过滤」两级门禁；拒绝重建判据措辞与实发射点（surfaceOp 越界）对齐。


## 切片 9（步骤 8）：剩余两处 DSH 私有界面迁插槽

- **会话列表头部**（SessionListScreen:199 的 dshTokenNeeded 硬嵌分支）→ SESSION_LIST_HEADER 槽位：新增 SessionListHeaderSlotHost（tokenNeeded + onEnterToken），DshTokenBannerExtension 按新增能力位 AUTH_TOKEN（core.auth.token）启用，DshServerAdapter 声明 AUTH_TOKEN 与三个 uiSlots。通用屏幕只提供断连上下文与出路回调。
- **服务器设置区块**（ServerSettingsContent 的 DshServerConfigSection/DshPluginInventorySection 两个硬嵌 item）→ SERVER_SETTINGS 槽位：ServerSettingsSlotHost（无上下文，贡献方经 hiltViewModel<SessionListViewModel> 读同作用域 VM）；ServerSettingsContent 删除 5 个 DSH 参数与 DSH 组件 import，只传 serverCapabilities。
- 契约测试：uiSlots 断言扩到三槽位；seam-1 断言扩展集纳入两个新扩展（启用贡献的槽位必须在适配器声明内）；derivation 测试补 AUTH_TOKEN。

**行为等价论证**（无真机/模拟器验证，按代码路径推演）：token 横幅三态（DSH+token→横幅；DSH 无 token→通用横幅；OpenCode→通用横幅）与迁移前逐态一致；SERVER_SETTINGS 区块原为「空表单/空清单自门控 + VM 仅 DSH 填充」，改插槽后贡献方 isEnabled(SERVER_SETTINGS) 承担同一门控，OpenCode 侧视觉不变。

**验证**：compile + 全量单测（3273 例）+ androidTest 编译 + lintDevDebug BUILD SUCCESSFUL。
**待人工/模拟器确认**：会话列表页断连横幅与设置页 DSH 两区块的视觉与交互（Compose UI 无 JVM 断言面）。


## 两轴 code review（193ff675..HEAD）与修正

评审范围：193ff675..HEAD（19+ 提交，160 文件 / +4251 −1855）；Standards / Spec 两轴各由独立子代理（深度推理档）产出，本代理不合并排名。

### Standards 轴（6 项 + 合规抽查）

硬违规（已修，commit 4a8c154e）：
1. Lint 白名单宽于文档白名单 → 收窄为**文件级精确白名单**（类型定义/持久化身份/解析器契约/存储/重复后端同一性比较/用户选择面/调试入口 MD + DebugProfile），适配器目录保留整目录豁免；architecture.md 同步改为点名文件并指明 lint 规则是机械真相源。
2. 插槽两级门禁只落地一处 → SessionListViewModel 增 uiSlots 投影；SessionListScreen（SESSION_LIST_HEADER）与 ServerSettingsContent（SERVER_SETTINGS）均补「适配器声明先决 + 能力过滤」；ServerSettingsContent 两个参数改为必填，消除「默认空能力位静默隐藏」失败不可见面（原判断题 6 一并解决）。

判断题处置：
3. Duplicated Code：ServerPorts 六个 requireX 收敛为单个私有 requirePort 泛型助手；WIRE_V011/V012 常量改为 DshConnectionStrategy 引用 DshServerAdapter（单一真相），测试引用同步。
5. 双实例隐患（DshServerAdapter 自建 ServerSettingsRepositoryImpl）：**接受**——无状态薄委托、与 DI 绑定同源同构；改为注入需改 10 处测试装配且收益仅为消除一个无状态实例，记此备查。
4. Feature Envy（DSH 状态留在共享 SessionListViewModel；SessionListHeaderSlotHost 焊入 tokenNeeded）：**接受为批次边界**——彻底下沉需为 DSH 引入独立 ViewModel 作用域，属切片 9「统一贡献注册表」之后的二次设计，单独立项。

合规抽查：AppLogger 全覆盖、无新增硬编码文案、无裸 URLDecoder/路径串切、提交带 type 前缀，均通过。

### Spec 轴（6 缺失 + 3 疑点）

已判定不成立/已处理：
- (c)2「ServerFeatures.QUEUE 派生位零消费」**不成立**：ChatScreen.kt:1045 / ChatScreenBottomBar.kt:633 / ChatViewModel.kt:830 三处消费。
- (c)3 Lint 白名单宽于 spec 四类 → Standards #1 已修（文件级）。
- (c)1 references/attachments 端口不派生能力位 → **判定为设计选择**：derivedFeatures 只映射「有用户可见开关」的能力，纯数据层操作端口的权威就是端口在场性本身；已在 ServerPorts.derivedFeatures KDoc 明文固化，避免为无消费者制造悬空能力位。

确认剩余（未完成，另立卡片）：
- 切片7 P1：V3 五类新事件（system/message、assistant/attempt、feedback/message-put|delete、subagent/catalog、deliverables/presented）目前仅 Ignored(SESSION_FORMAT_V3) 降级不渲染；「按代事件词汇表」仍为单体 when + protocolOf==V012 硬判 → 卡 #398。
- 切片9：条目级动作贡献注册表、令牌门禁（硬编码色值/间距/时长）、审计矩阵 BAD 项归零 → 卡 #399。
- 切片8 静态强制余两条规则（通用界面 import 具体类型组件 / 令牌绕过）→ 已挂 #397。
- 结构性违约余两判据（乱序 / 种子缺失）保留判据位无发射点（mapper/folder KDoc 已诚实标注）。
- Testing seam 4（真机 + 真实服务器端到端）未执行：机场公共 WiFi 客户端隔离致无线调试不可达（ARP FAILED），待模拟器/可达网络。

### 额外修正（评审外，自审发现）
- SseConnectionManager 的 strategy.probe 原先只在 MUX 分支调用（OpenCodeConnectionStrategy.probe 生产无调用点）→ 提到 wireKind 分支之前统一一次握手，SSE 线面消费 degraded 产物；行为不变。

**验证**：compile + 全量单测（3273 例）+ androidTest 编译 + :app:lintDevDebug（收窄白名单后仍绿）BUILD SUCCESSFUL。

