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
