# Architecture Debt Register

Generated: 2026-07-13
Updated: 2026-08-05（Phase 1-2 重构后同步）

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

> 完整修复终端体系需要将 `ServerTerminalWorkspace` 的 tab 管理/重连/buffer 抽象为 domain 接口——预计 20+ 文件改动、测试重写，收益低于成本。**若未来做，先抽 `ServerTerminalRegistry.workspaceFor` 的薄接口**。

## 2. Thin UseCase Layer（29 个，绝大多数纯委托）

29 个 UseCase 中 28 个是纯委托（`suspend fun invoke(...) = repo.method(...)`），仅 `SubmitAnnotationsUseCase` 含业务逻辑。

**选项**：
- A) 删除纯委托 UseCase，ViewModel 直接调 Repository —— 破坏 AGENTS.md 声明的架构规范，需改 20+ 测试
- B) 保持现状 —— UseCase 作为未来业务逻辑的 seam，样板成本低（9-43 行/个）✅ 当前选择
- C) KSP 代码生成

**推荐**：Option B（AGENTS.md 已声明 "ViewModel 委托给 UseCase" 是项目规范，删除会破坏架构一致性）。

## 3. God Files（>500 行，2026-08-05 实测）

| File | 行数 | 状态 |
|------|------|------|
| ChatViewModel.kt | ~1100 | 已有 5 个 delegate 抽取模式（MessageData/SessionActions/Terminal 等），继续此模式 |
| ChatScreen.kt | ~890 | 继续 sub-composable 抽取 |
| SessionListScreen.kt | ~700 | 提取树节点渲染 |
| SettingsDataStore.kt | ~690 | 拆分 per-setting DataStores（P2 后已瘦身，仍偏大） |
| ChatMessageList.kt | ~685 | 提取 FlingBehavior + scroll 逻辑 |
| SessionListViewModel.kt | ~680 | 状态拆分 |
| ServerTerminalWorkspace.kt | ~620 | 已迁移 data/terminal，逻辑内聚可接受 |
| NavGraph.kt | ~570 | 路由定义集中，可接受 |

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
