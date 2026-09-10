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
