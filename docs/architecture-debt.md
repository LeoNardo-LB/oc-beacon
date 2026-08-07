# Architecture Debt Register

Generated: 2026-07-13
Updated: 2026-08-07（密码导航重构后同步）

## 1. 依赖方向违规（已修复 vs 剩余）

### ✅ 已修复（2026-08-05）

| 违规 | 修复方式 |
|------|---------|
| `ServerTerminalRegistry`（data）→ `ServerTerminalWorkspace`（ui） | `ServerTerminalWorkspace.kt` + `TerminalTabState.kt` 迁移到 `data/terminal/` |
| `ChatViewModel` 死注入 `SseClient` | 删除（零调用点） |
| `ChatViewModel`/`SessionListViewModel`/delegates 直接注入 `SessionStateService` | 新建 `domain/repository/SessionStateRepository` 接口，`SessionStateService` implements 之；UI 路径全部面向接口 |
| `ServerSettingsViewModel` 绕过 domain（`ProviderApi`/`SystemApi` 直接注入） | 扩展 `ProviderRepository`（+8 方法）、新建 domain 模型（GlobalConfig 等），ViewModel 只依赖 domain 接口；`SettingsDataStore` → `SettingsRepository` |

### 🔴 剩余（可接受债务）

| 文件 | 违规 | 说明 |
|------|------|------|
| `ChatViewModel` | 注入 `data/repository/ServerTerminalRegistry`（终端工厂） | 终端体系与 connectbot `TerminalEmulator`/`PtySocket` 深度耦合，是 Android 平台细节。抽 domain 接口收益低、风险高，标注为可接受 |
| `ui/screens/chat/terminal/*` | UI 依赖 `data/terminal/ServerTerminalWorkspace`（含 `TerminalEmulator` 类型暴露） | 同上，终端仿真器是平台细节，维持现状 |
| `data/repository/EventDispatcher`、`handler/*`、`SseConnectionManager` | data/service 内部互用具体类 | 正常（data 层内部），非违规 |

> ✅ **2026-08-07 已修复**：`SessionListViewModel`/`DirectoryManager` 的 `FileApi`/`SessionApi`/`SystemApi`/`TerminalApi` 直调已全部下沉为 UseCase/Repository（backlog #17）——4 个 Api 注入移除，扩展文件搬回主类，internal 全转 private。

> 完整修复终端体系需要将 `ServerTerminalWorkspace` 的 tab 管理/重连/buffer 抽象为 domain 接口——预计 20+ 文件改动、测试重写，收益低于成本。**若未来做，先抽 `ServerTerminalRegistry.workspaceFor` 的薄接口**。

## 2. Thin UseCase Layer（25 个，绝大多数纯委托）

25 个 UseCase 中绝大多数是纯委托（`suspend fun invoke(...) = repo.method(...)`），仅 `SubmitAnnotationsUseCase` 含业务逻辑。2026-08-07 新增 6 个（ListSessions/ListProjects/GetServerPaths/ProbeDirectory/SearchDirectories/CreateDirectory，backlog #17）。

**2026-08-05 已删 9 个死 UseCase**（main 代码零引用，仅有测试）：ConnectServerUseCase、CreateSessionUseCase、DisconnectServerUseCase、GetMessagesUseCase、GetServerListUseCase、GetSessionListUseCase、ManageQuestionUseCase、PermissionHandlerUseCase、QuestionHandlerUseCase。

**剩余选项**：
- A) 删除剩余纯委托 UseCase，ViewModel 直接调 Repository —— 破坏 AGENTS.md 声明的架构规范，需改 20+ 测试
- B) 保持现状 —— UseCase 作为未来业务逻辑的 seam，样板成本低（9-43 行/个）✅ 当前选择
- C) KSP 代码生成

**推荐**：Option B（AGENTS.md 已声明 "ViewModel 委托给 UseCase" 是项目规范，删除会破坏架构一致性）。

## 3. God Files（>500 行，2026-08-07 实测）

| File | 行数 | 状态 |
|------|------|------|
| ChatScreen.kt | ~770 | 继续 sub-composable 抽取（#16 已抽滚动控制器） |
| ChatMessageList.kt | ~674 | #18 已外移指纹函数，继续提取滚动/缓存逻辑 |
| SessionListViewModel.kt | ~522 | #17 已分层修复（UseCase 下沉），仍偏大 |
| ServerTerminalWorkspace.kt | ~620 | 已迁移 data/terminal，逻辑内聚可接受 |

> 2026-08-07 已瘦身出表：ChatViewModel（~1100→493，#8/#15 拆分）、SessionListScreen（~700→367）、SettingsDataStore（~690→223）、NavGraph（~570→420）、MessageDataDelegate（#15 拆为 PaginationDelegate + OptimisticMessageStore）。

## 4. 测试缺口（高优先）

| 模块 | 风险 |
|------|------|
| 终端 tab 管理（ServerTerminalWorkspace） | 重连/多 tab 逻辑无单测 |
| ServerSettingsViewModel 新方法 | ProviderRepository 新 8 方法仅有 mapper 测试，ViewModel 层无覆盖 |
| SessionStateRepository 接口 | 已由 SessionStateServiceTest 覆盖（经具体类） |

## 5. 维护指引

- 新代码禁止 data → ui import；`grep -rln "import dev.leonardo.ocbeacon.ui" app/src/main/kotlin/dev/leonardo/ocbeacon/data/` 应为空
- 新代码禁止 UI 直接注入 `data.api.*`；必须经 domain repository
- 例外：终端体系（见 §1 剩余项）——改动前先讨论

## 6. 密码导航重构遗留（2026-08-07）

| 位置 | 债务 | 说明 |
|------|------|------|
| `ChatViewModel.kt` / `SessionListViewModel.kt` | `runBlocking(Dispatchers.IO)` 在 ViewModel 属性初始化中同步读 `getServer(serverId)` | 反模式（阻塞 VM 构造线程）。实际影响小（Home 页已 warm DataStore，读为内存级）；进程重建直入 Chat 页时冷读 20-80ms 理论可感知。正确形态：`StateFlow<ServerConfig?>` + 异步加载 + 下游组件（TerminalDelegate/workspaceFor 首次 conn）支持延迟绑定。**若未来改：先改 `ServerTerminalWorkspace.conn` 为 `@Volatile var` + `updateConnection()`，再异步化 VM** |

## 7. 未读红点体系遗留（2026-08-07，#25 落地后）

| 位置 | 债务 | 说明 |
|------|------|------|
| `EventDispatcher.persistLastCompletedReplyTime` | maxCompleted 每次变化全量 JSON 写 DataStore | 会话规模大+消息完成高频时写放大；当前量级无感（<100 会话，每条完成一次 ms 级）。优化方向：增量 key 或批量节流 |
| 断线期新回复缺失 | 重启/断连期间服务器完成的新回复无法得知（seed 过时） | 与旧 lastReplyTime 机制相同；SSE 重连后增量补全，进会话后 recompute 恢复。未来方向：重连时对活跃会话主动 listMessages 拉取 |
| `EventDispatcherUnreadTest` 迁移测试 | `coVerify` 无 `exactly = 1` | 字面名"once"与断言粒度不符；当前 drop(1)/同步落盘下 updateData 仅迁移触发，实际有效 |
| FOLDER 视图 status 门控 | `buildTreeNodes` 的 status 传递无行为测试（TreeNodeTest 只用前 3 参） | 代码审查确认正确；isUnread 纯函数门控已有单测。补集成测试为可选 |
| 预存在死 import | `SessionListViewModel` 的 `SharingStarted`、`EventDispatcher` 的 `flow.map` | 非 #25 引入，AGENTS.md 精准修改原则下未清理；后续顺手处理 |
