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
