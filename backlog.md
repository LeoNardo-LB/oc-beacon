# OC Beacon — 需求与问题总览

本文档用于记录用户在使用过程中口头反馈的问题、发现的 bug，以及计划中的功能需求。

**定位**：轻量级记录，仅忠实记录用户反馈的原始现象与需求，可以简单调研的结果，但不做主观推测或归因分析。**会话进行中产生的、优先级不足以立即处理的需求/问题也实时登记于此**（触发时机见 AGENTS.md「Backlog 纪律」）。简单可行性确认（如文件名是否存在、接口是否暴露等）可附带，深入的代码链路调研由具体开发任务承接。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |

**状态流转**：每个条目下的状态 checkbox 需全部打勾才算完结。代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才能标记已完成！

| 状态 | checkbox 标记 | 含义与流转规则 |
|------|--------------|----------------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。AGENTS.md 验证框架要求 UI/UX 时间性现象（闪烁/动画/布局）必须人工验证（维度 5）。**后续 Agent 看到 `[~]` 时**：向用户给出验证清单并请其执行；用户验收通过 → 勾选 `[x]` 转「已完成」并更新完成说明；验收发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 需求完成 + 自行验证 + **用户验收通过**。只有用户明确确认后才可打勾 |

**Tag 标签体系**：每个条目需标记相关 Tag，用于关联同类问题，便于后续批量修复或按领域排查。录入时判断条目适用的已有 Tag；若现有 Tag 不足以描述，则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

---

## P0 — 主流程阻塞

### 2026-08-06 Play 上架合规批次（已完成）
来源：Google Play 上架审计（2026-08-06），目标 2026-08-31 政策截止。

- [x] **#1 targetSdk 36 升级** `security`
  - 问题：targetSdk=35，Play 2026-08-31 起新应用必须 target Android 16 (API 36)，不达标无法上架
  - 修复：targetSdk 35 → 36（compileSdk 37 已就绪），全量编译验证
  - 工时：~1h | 难度：低 | 涉及：app/build.gradle.kts

- [x] **#2 权限清单清理** `security` `permission`
  - 问题：Termux RUN_COMMAND（代码零使用）、WRITE_EXTERNAL_STORAGE（遗留）会被 Play 审核质询；REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属高风险权限（IM 类默认不允许）
  - 修复：移除死权限；电池优化改为引导到系统设置页（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS + 回退应用详情页），SSE 保活能力保留
  - 工时：~2h | 难度：低 | 涉及：AndroidManifest.xml / HomeScreen.kt

- [x] **#3 应用内自更新 flavor 区分** `security` `data`
  - 问题：Play 政策明确禁止 REQUEST_INSTALL_PACKAGES 做应用自更新；现有 UpdateRepository 从 GitHub 下载 APK 安装
  - 修复：stable（Play）禁用自更新（UI 隐藏 + Repository 守卫 + Manifest overlay 移除权限）；dev/beta（GitHub 分发）保留
  - 工时：~2h | 难度：中 | 涉及：build.gradle.kts / src/stable/AndroidManifest.xml（新）/ AboutScreen / UpdateRepository

- [x] **#4 密码 Keystore 加密** `security` `data`
  - 问题：服务器密码明文存 DataStore，且随系统云备份上传（backup_rules 为空 = 全量备份）
  - 修复：新建 SecretCipher（AES/GCM + AndroidKeyStore）加解密落盘；备份规则排除 datastore/ 目录（云备份 + 设备迁移）
  - 工时：~3h | 难度：中 | 涉及：SecretCipher.kt（新）/ ServerDataStore / backup_rules / data_extraction_rules

- [x] **#5 导航 URLDecoder 崩溃风险** `crash`
  - 问题：13 处裸 `URLDecoder.decode`，密码含畸形 `%` 序列（如 `%NR`）抛 IllegalArgumentException 崩溃
  - 修复：全部改用 `NavUtils.safeDecodeParam`（畸形序列回退原值）
  - 工时：~1h | 难度：低 | 涉及：ChatViewModel / SessionListViewModel / WorkspaceViewModel / SessionLifecycleDelegate

- [x] **#6 selectModel 空 patch 隐患** `data`
  - 问题：ChatRepository.selectModel 零调用死代码，实现传 `ServerConfigPatch()` 空对象——PATCH /config 是全量替换语义，任何调用方接入即清空服务器 model 配置
  - 修复：删除接口 + 实现 + 相关测试/Fake
  - 工时：~1h | 难度：低 | 涉及：ChatRepository / ChatRepositoryImpl / ChatRepositoryTest

- [x] **#7 终端工作区内存泄漏** `data`
  - 问题：ServerTerminalRegistry.byServer 只 getOrPut 无 remove，每次连接不同服务器泄漏一个终端工作区（模拟器 + 协程作用域永不释放）
  - 修复：新增 removeWorkspace/removeAllWorkspaces + Workspace.dispose()（closeAll + scope.cancel），Service 断开时自动调用
  - 工时：~2h | 难度：中 | 涉及：ServerTerminalRegistry / ServerTerminalWorkspace / OpenCodeConnectionService

- [x] **#8 密码导航参数重构** `security`
  - 问题：密码明文经 query 参数在 7 个路由间传递（ServerRouteParams 5 参），暴露于日志/深链/进程重建回放；与 #4 本地加密闭环矛盾
  - 方案：导航只传 serverId；各 ViewModel 从 ServerDataStore 按 id 取 ServerConfig（suspend，需初始 loading）；WebViewScreen Basic Auth 同改
  - 工时：~2-3d | 难度：高 | 涉及：ServerRouteParams / NavGraph（25+ 处）/ Chat·SessionList·Workspace·WebView·ServerSettings ViewModel
  - **2026-08-07 完成**：路由层+消费层+入口源头全部 serverId-only（commit 681bf0fb / 2326e8b5）；编译 ✅ 全量单测 ✅（52s）凭据 grep 0 引用 ✅；模拟器验收通过（9/9 步：会话/聊天/SSE/终端/通知深链/WebView Basic Auth 全正常，logcat 0 异常，凭据零泄露）；遗留 runBlocking 债务已登记 architecture-debt §6

- [x] **#123 V2 用户消息不立即显示（session.inbox.enqueued 契约适配）** `sse` `data`
  - 问题：2026-08-14 用户反馈"发送消息后不会立刻显示，重进会话才显示；Agent 回复能立刻看到"——根因：新版 opencode（next-17403+）把 `session.input.admitted/promoted` 改为 **`session.inbox.enqueued/delivered`**（事件名 + payload 结构全变）；App V2SseMapper 只处理旧事件名 → 用户消息播种失败（悲观消息设计：无本地占位，完全依赖 SSE 回显）→ 消息只等重进会话 REST 拉取才显示。Agent 回复走 step/text 事件不受影响（所以回复正常）
  - 修复（2026-08-14，curl 抓帧实证）：
    1. V2SseMapper 新增 `session.inbox.enqueued` 分支：{sessionID, inboxID, item:{type, payload:{text,agents}, delivery}}，兼容过渡契约 {id, prompt} 与旧契约 {inputID, input}
    2. SseClientV2.handleEvent synthetic 缓存同步适配（inbox.enqueued 缓存 inboxID→item / inbox.delivered 消费；保留旧事件名分支）
    3. +4 单测（inbox 播种 / 缺失 id 防御 / 过渡契约 / 旧契约保留）→ 1587 全通过
  - 验证：模拟器实测——发送 E2E_final_verify_ok → logcat `admitted: inputID=msg_... type=user`（无 unhandled）→ **UI 用户消息立即显示 ✅**
  - 工时：~1h | 难度：中 | 涉及：V2SseMapper/SseClientV2 | 优先级：P0（主流程）

- [x] **#124 退出会话后列表状态闪烁（releaseSessionData 误清 FSM 状态）** `ui` `session`
  - 问题：2026-08-14 用户反馈"从会话退到列表，退出会话的状态突然变没、突然又恢复在输出中，不连续"——根因：#89 修复引入的 releaseSessionData 在 ChatViewModel.onCleared（退出会话）时调用 `sessionStateService.clearSession(sessionId)` 清除 FSM 状态；但服务器仍在流式（SSE 全局连接持续投递 execution.started 等）→ 状态先清（列表显示无状态）→ 事件恢复（又显示 Working）→ 闪烁
  - 修复（2026-08-14）：releaseSessionData **移除 clearSession**——FSM busy/streaming 是服务器状态镜像（与 permission/question 处理哲学一致：服务器状态退出不清理）；内存由 24h staleness 自动清扫兜底（STATE_RETENTION_MS，非 Busy 会话超时移除）
  - 验证：模拟器实测——退出"opencode版本识别"会话（服务器流式中）→ 列表持续显示 Working（多次 dump 一致无闪烁）✅
  - 工时：~10min | 难度：低 | 涉及：EventDispatcher.releaseSessionData | 优先级：P0（视觉回归）

- [ ] **#128 beta 真机崩溃：CompletionHandlerException（协程取消回调内抛异常）** `crash`
  - 问题：2026-08-14 用户真机（OnePlus PLK110, Android 16）beta 0.3.0-beta.8 崩溃——`kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCancelling@c520a61 for StandaloneCoroutine{Cancelling}`（主线程）；栈特征：`StateFlowImpl.collect → dropWhile → takeWhile → SafeCollector.emit`——协程取消回调链里执行 flow emit → 触发下游 cancel → 嵌套 handler 异常；R8 混淆（ci1/yh1/j20/mz）无法直接定位源码
  - 完整日志：docs/research/crash-2026-08-14-completion-handler-beta.md
  - 初步方向（低置信）：① 全库搜 invokeOnCompletion/invokeOnCancelling 回调内做 emit/UI 操作；② 会话退出/切换的取消链（#124 onCleared 清理相关）；③ MessageEventHandler batchScope/persistQueue（#57 actor）；④ 用 betaRelease mapping 反混淆
  - 工时：待定位 | 难度：中-高 | 涉及：协程取消链 | 优先级：P1（真机崩溃，beta 用户受影响）

### 2026-08-10 系统审计批次（F 报告 P0）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md（5 路交叉验证：A 渲染 + B 数据 + C 状态 + D 历史 + E 实测）

- [x] **#36 DatabaseRecovery catch 范围过宽 → 非损坏异常误删全库** `data` `security`
  - **2026-08-10 修复（37ef2129）**：withCorruptionRecovery 改为仅 `SQLiteDatabaseCorruptException`（含 cause 链遍历）触发删库；Full/Locked/Constraint/DiskIO/基类 SQLiteException 原样抛出不删库；DatabaseRecoveryTest 9 用例（损坏删库 / cause 链 / 5 类非损坏不删库 / 非 SQLite 传播）全通过
  - 问题：`DatabaseRecovery.kt:29-38` 捕获 `SQLiteException` 基类——`SQLiteDatabaseLockedException`（锁竞争）/`SQLiteConstraintException`（约束冲突）/`SQLiteFullException`（磁盘满）等非损坏异常都会触发 `deleteDatabase()`，缓存消息 + 归档 + 诊断日志全部清零。MessageStore 7 处调用点全包（:47, 226, 237, 242, 247, 269, 296）。唯一应触发删库的是 `SQLiteDatabaseCorruptException`
  - 修复：收窄 catch 到 `SQLiteDatabaseCorruptException`；或用 `Room.databaseBuilder().fallbackToDestructiveMigration()` 声明式；或返回 `Result<T>` 区分"损坏"（删）与"临时错误"（重试）
  - 工时：~2h | 难度：低 | 涉及：DatabaseRecovery.kt + DatabaseRecoveryTest
  - 来源：F §P0-1 + B §P0-1 + D TD-5（2 路确认）

---

## P1 — 核心功能需求

### 2026-08-06 清理与重构批次（已完成）
来源：2026-08 三份深度审计（死代码 / 架构 / 并发内存）。

- [x] **#9 死代码清理** `refactor`
  - 问题：TerminalRepository 体系（0 调用）、ServerConnectionRepository（NotImplementedError 死路径）、DraftUseCase（无脑转发层）、ChatRepository.undoRedo/replyPermission（零调用半实现）、ServerRepositoryImpl 三个空实现（静默失败）
  - 修复：全部删除；testConnection 移入 ServerConfigRepository；DraftInputDelegate/ChatViewModel 直连 DraftRepository
  - 工时：~3h | 难度：中 | 涉及：domain/repository + data/repository + di/DomainModule + fakes + 测试

- [x] **#10 分层修复（PendingPrompt / FileNode DTO 泄漏）** `refactor`
  - 问题：UI 层直接用 data.dto.FileNodeDto/ServerPaths 和 data 层 PendingPromptRepository 实现，domain 边界被绕过
  - 修复：PendingPromptRecord/PendingPromptRepository 提 domain；FileNode/FileType/ServerPaths domain 模型 + FileMapper 统一映射；DirectoryManager/SessionListViewModel/OpenProjectDialog 只依赖 domain
  - 工时：~3h | 难度：中 | 涉及：domain/model + domain/repository + FileMapper + 3 个 UI 文件

- [x] **#11 日志统一 AppLogger** `refactor`
  - 问题：72 处业务文件用 android.util.Log，诊断页不可见（AGENTS.md 规则要求 AppLogger）
  - 修复：61 文件批量替换 + import 收敛；AppLogger 加单测环境 NPE 防御（getStackTraceString null 回退）与 initialize 同步锁
  - 工时：~2h | 难度：低 | 涉及：61 个业务文件 + AppLogger.kt

- [x] **#12 缓存与状态清理** `data`
  - 问题：AppNotificationManager 去重缓存无服务器级清理（残留增长）；ToolSnapshotCache/toolExpandedStates 非线程安全；SessionStateService 状态容器无界
  - 修复：clearForServer + disconnect 时调用；ConcurrentHashMap；24h 无事件非 Busy 自动清扫
  - 工时：~2h | 难度：中 | 涉及：AppNotificationManager / ToolSnapshotCache / ChatRepositoryImpl / SessionStateService

- [x] **#13 一致性修缮（小项）** `refactor`
  - 问题：MainActivity 用 collectAsState（后台仍收集 DataStore）；DirectoryManager callbackFlow 缺 awaitClose（取消传播不规范）；AutoApproveRule round-trip 测试偶发失败（createdAt 默认值毫秒竞态）
  - 修复：collectAsState → collectAsStateWithLifecycle；callbackFlow → flow{}；测试显式传 createdAt 固定值
  - 工时：~1h | 难度：低 | 涉及：MainActivity / DirectoryManager / PermissionAutoApproverTest

- [x] **#14 Play 上架配套** `refactor`
  - 问题：上架需 AAB 产物、隐私政策、版本体系与 1.x 不符
  - 修复：bundleStableRelease 验证 + release-workflow §5.5；docs/PRIVACY_POLICY.md（中英双语）；版本重置 1.2.0→0.2.0（VERSION_CODE 18→1，接受卸载重装）
  - 工时：~2h | 难度：低 | 涉及：docs/ + version.properties + AGENTS.md

- [x] **#15 MessageDataDelegate 职责过载** `refactor`
  - 问题：730 行单类承担 8 个职责（消息/parts/SSE job/缓存/乐观消息/分页/工具展开/加载错误），改分页可能碰坏乐观消息
  - 方案：拆 MessagePaginationDelegate + OptimisticMessageStore；**chatMessageCache 与 lastCombineSessionId（铁律 8）必须留主体**
  - 工时：~0.5d | 难度：中 | 涉及：MessageDataDelegate + 相关测试
  - **2026-08-07 完成**：主体 731→520 行（-29%）；新类 MessagePaginationDelegate(139 行)/OptimisticMessageStore(168 行)；新增 24 白盒测试；编译 ✅ 全量单测 ✅（55s，0 回归）；模拟器冒烟 ✅（历史渲染/乐观 QUEUED/SSE 确认/滚动稳定，0 崩溃）；用户验收通过；遗留：分页未压力验证（需 100+ 消息长会话补测 loadOlder 路径）

- [x] **#16 ChatScreen 主函数臃肿** `refactor` `ui`
  - 问题：888 行文件，主函数约 600 行，滚动状态集群（autoScrollEnabled/isAtBottom/4 个 LaunchedEffect）内联
  - 方案：抽 rememberChatScrollController；**autoScrollEnabled/isAtBottom/双 key LaunchedEffect 必须整体搬移（SSE 铁律 4）**；编辑前读 chatscreen-editing-protocol.md
  - 工时：~0.5d + 真机验证 | 难度：高 | 涉及：ChatScreen.kt
  - **2026-08-07 完成**：新文件 ChatScrollController.kt(124 行) + ChatScreen 888→814 行（commit 1c59131e/ebfc0485）；编译 ✅ 全量单测 ✅；androidTest ChatScrollStabilityTest 7/7 ✅（滚动行为保持）；另顺带修复 androidTest DI 缺口（FakePendingPromptRepository，commit 待补）

- [x] **#17 SessionListViewModel 分层越界** `refactor`
  - 问题：全项目唯一混用 4 种数据源的 ViewModel（Api 绕过 Repository + EventDispatcher 细节 + internal val 暴露）
  - 方案：4 个 Api 下沉 UseCase；EventDispatcher 经 Repository 接口暴露；internal → private
  - 工时：~1-1.5d | 难度：中 | 涉及：SessionListViewModel / DirectoryManager / 新增 UseCase
  - **2026-08-07 完成**：4 Api+EventDispatcher 直调全部下沉（SessionRepository/FileRepository 扩 7 方法 + 6 新 UseCase）；internal 全转 private（Sessions.kt/Mcp.kt 搬回主类后删除）；DirectoryManager 注入 UseCase（缓存实例级保留）；对外 API 零变化；全量单测 1222（2 个预存在 flaky 已登记 #22）；grep 0 引用

---

## P1 — 核心功能需求

### 2026-08-08 提问组件改进批次（待验证）
来源：用户口头反馈（2026-08-08）。

- [x] **#26 提问单选/复选控件语义纠正** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：单选互斥 bug 根因=isSingle 仅覆盖单问题场景，多问题中单选题目走 toggle 多选分支 → 修复：onOptionClick 按每道题 multiple 判断；头部紧凑化（16dp 图标 + labelLarge + bodyMedium 摘要）。V1 实测：Q1 点 Red 再点 Blue → Red 自动取消（仅 1 选中）；Q2 多选 Apple+Banana 保持双选；无崩溃
  - 原问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 现状（代码已确认）：QuestionOptionRows（QuestionPartContent.kt:259）已按 `question.multiple` 分支渲染 CheckBox/RadioButton 图标；但需验证 `multiple` 字段在 SSE 事件 → QuestionParser → UI 全链路传递是否可靠（服务器未传 / 解析丢失时可能全部退化为单选样式）；历史视图 CollapsibleQuestionPart（QuestionPartContent.kt:135）答案固定用 RadioButtonChecked 图标，多选答案也应显示 CheckBox 样式
  - 调研方向：先确认真实渲染路径（活动提问走 QuestionCard + QuestionPagerView，历史走 CollapsibleQuestionPart / QuestionExpandedOptions），定位 multiple 丢失点；再按 M3 语义修正图标
  - 工时：~2h | 难度：中 | 涉及：QuestionPartContent.kt / QuestionCard.kt / QuestionParser
  - **2026-08-08 代码完成（待人工验证）**：QuestionParser 新增 `ParsedQuestion.isMultiple`（JSON multiple 3 解析点 + 7 测试，commit 04b3fb33）；CollapsibleQuestionPart 历史答案图标按 isMultiple 分支 CheckBox/RadioButtonChecked + PartContent 调试日志清理（commit a86b2e87）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：活动/历史多选显示复选框

- [x] **#27 多问题提问"下一步/提交"流程** `ui`
  - **2026-08-13 用户验收 ✅**：Next/Submit 切换正确、未答完弹窗正常（"第 X 个问题没有回答"→可继续提交）、单选点选可取消——全部通过
  - 问题：多问题场景（questions.size > 1）右下角直接是提交按钮；应改为非最后一个问题时显示"下一步"，点击跳转到下一个问题，最后一个问题才显示"提交"
  - 现状：QuestionCard.kt:207-232 底部 Row 只有 Dismiss + Submit（`!isSingle` 时显示）；QuestionPagerView 已有 TabRow + HorizontalPager（Q1/Q2/Q3 标签可点击跳转），下一步按钮可复用 `pagerState.animateScrollToPage`
  - 方案：QuestionCard 增加"当前页"状态，底部按钮随页变化：非末页 → "下一步"（跳页），末页 → "提交"（onSubmit）；Dismiss 保持
  - 工时：~1.5h | 难度：中 | 涉及：QuestionCard.kt / QuestionPagerView.kt（可能）+ i18n（下一步文案 15 语言）
  - **2026-08-08 代码完成（待人工验证）**：QuestionCard 三按钮体系（忽略/下一步/提交，末页置灰）+ 未答完提交弹窗（"第 X 个问题没有回答" → 继续提交）+ 单选点选不立即提交可取消选中；QuestionPagerView page-aware 签名；纯函数 `unansweredQuestionIndexes` + 4 测试；i18n 新增 4 键（15 语言，commit 10757799）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：三按钮流程/弹窗/单选可取消

- [~] **新增 A：会话列表"待回答"标记 + 提问通知 REST 兜底** `ui` `session` `sse`
  - 问题：有提问的会话在列表无任何提示；SSE 不推 question 事件时通知不可达（无兜底链路）
  - **2026-08-08 代码完成（待人工验证）**：SessionRow 增加 HelpOutline 图标 + "Pending answer" 标记（i18n 15 语言，commit a989890e）；OpenCodeConnectionService 新增 30s REST 轮询兜底（`notifyPendingQuestionsFromREST` + `diffNewQuestionIds` 纯函数 + 3 测试，commit 1d1b2a75）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：列表标记显示/通知弹出

- [~] **新增 B：双端同机问题状态同步修复** `data` `session`
  - 问题：设备 A 回答后，设备 B 的 `loadPendingQuestions` 旧合并逻辑（`existingSseQs + newQs`）只增不删 → 已消失问题永久残留
  - **2026-08-08 代码完成（待人工验证）**：新增 `resolvePendingQuestionReplacement` 纯函数，声明 REST GET /question 为全量权威源，`loadPendingQuestions` 全量替换（含空列表清空语义）+ 3 测试（commit 0b85ca06）；全量单测无回归；⚠️ 真机验证待用户：双端同机 A 回答后 B 问题消失

- [x] **#30 消息本地化批次（方案 C）——Plan 1/2/3 全部完成（代码），待人工验证** `data` `cache` `room`
  - **2026-08-13 验证完成 ✅（用户授权 Agent 代测）**：冷启动打开会话 1 秒内消息渲染（Room 种子化秒开）✅；杀进程重启后消息保留（Room 缓存）✅；db 2.2M（ocbeacon.db 1.82MB + WAL 524KB）✅；覆盖安装保留数据 ✅
  - 问题：消息缓存/日志存储仍用手写 SQLite（DiagnosticLogDatabase 手写 SQL，路径分隔符/大小写敏感性风险）；消息本地化（方案 C）需先落地 Room 基础设施
  - 方案：按 Plan 分阶段——Plan 1 Room 基础设施（依赖 + 数据库骨架 + LogStore + Repository 迁移）；Plan 2/3 消息缓存与本地化落地
  - 工时：Plan 1 ~4h | 难度：中 | 涉及：app/build.gradle.kts / data/local/room/* / LogStore / DiagnosticLogRepository
  - **2026-08-08 Plan 1 完成**：Room 2.8.4 依赖（199bb36f）→ 数据库骨架 cached_messages/cached_parts/logs 三表 + LogDao + 插桩测试（60345b68）→ LogStore 诊断日志 Room 存储（修剪策略等价迁移 + 单测，53562a4b）→ DiagnosticLogRepository 迁移，删除 DiagnosticLogDatabase，手写 SQL 清零（3b206574）；编译 ✅ 全量单测 ✅（--rerun 26s PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；手写 SQL grep 0 引用；⚠️ Plan 1 人工验证待用户：Diagnostics 日志显示/修剪/21 天语义
  - **2026-08-08 Plan 2 完成（代码，待人工验证）**：MessageApi 游标分页（before + X-Next-Cursor，9b3610e5）→ MessagePage 下沉 domain（5e208397）→ MessageStore Room 消息缓存（限量 1000 条/会话，81c2573e）→ 分页管线缓存优先（本地渲染 + REST 增量 + 真游标翻页，b6a6f461）→ 游标编解码 base64url JSON（3d0929dc + 595d63b2 fix）→ upsert 合并策略统一（SSE_PRIORITY/REST_AUTHORITY/APPEND_ONLY）+ SSE 双写 Room（caf8019b）→ 冷启动种子化 getMessagesFlow 从 Room 填充内存热视图（本次 Task 6）；编译 ✅ 全量单测 ✅（--rerun 46s，1305 tests PASS，含新增种子化测试）；⚠️ Plan 2 人工验证待用户（6 项，见 task-6-report）：秒开/离线浏览/翻页边界/SSE 重启保留/1000 条限量/磁盘占用
  - **2026-08-09 Plan 3 完成（代码，待人工验证）**：存储层架构清理四任务——Task 1 UnreadBadgeService 抽出（红点时间源独立，消除 EventDispatcher 的 runBlocking 落盘，3d828265）；Task 2 StreamingOwnershipRegistry 抽出（多服务器 SSE 去重独立化，c6bbd71a + 测试状态隔离 775b257e）；Task 3 SettingsDataStore 三文件合并（ReadTimes/Tags 扩展函数 → 成员方法，50b2af95）+ DraftDataStore 迁移 DataStore（含旧 File 草稿一次性迁移保数据，d4a906d7）；Task 4 DI 模块合并（DataModule 并入 DomainModule，FakeDomainModule 同步 replaces，domain 层无 data 依赖，3d674d10）；编译 ✅ 全量单测 ✅（--rerun 26s，1313 tests PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；⚠️ Plan 3 人工验证待用户（5 项，见 task-4-report）：红点恢复/双服务器流式去重/会话列表红点/草稿恢复/Diagnostics+消息缓存回归
  - **2026-08-09 遗留处理完成（代码，待人工验证）**：#31 本地库损坏自愈（DatabaseRecovery：SQLiteException → 删库重建，6fdff190）；#29 androidTest 编译修复（3 Fake 补 7 接口方法，1ae44d57，androidTest 编译恢复）；网络失败回退本地缓存（有缓存不显示空，b843f265）；P4 清理（测试名/owner 日志/MessageRefresher 命名，f770e60d）；编译 ✅ 全量单测 ✅（1313+ tests PASS）；**一期代码全部完成，待 14 项人工验证**（见下）
  - **2026-08-09 模拟器走查（10 项）**：V1 Diagnostics ✅ / V2 秒开+种子化 ✅（[seed] 有/无缓存双分支命中）/ V3 断网浏览 ⚠️ 部分（断网冷启动受服务器连接入口限制，架构使然；已连接→断网→会话内浏览待真机）/ V4 重启保留 ✅ / V5 真游标翻页 ✅（logcat 实证 before=base64 游标前进）/ V6 红点标签收藏 ✅ / V7 草稿恢复 ❌ **发现 bug 已登记 #33**（saveDraft 仅 onCleared，force-stop 丢失）/ V8 磁盘占用 ✅（ocbeacon.db 1.5MB 合理）/ V9 SSE 流式 ✅（PONG 实测 + MessagePartUpdated 84 事件）/ V10 回归 ✅（无崩溃）
  - **2026-08-09 补充验证（3 项）**：①双服务器去重 ✅ 架构层面保证（同 URL 第二连接被 app 阻止，无双投递可能；"Thought"标记仅 1 次实证；衍生 #34 连接 UX/#35 ANR）；②日志修剪 ✅ db 实证（注入 60 FATAL 22 天 + 30 INFO 3 天 + 30 ERROR 22 天 → 启动即全部清除，时间规则 + FATAL 限量 + 字节预算按源码预期）；③**限量裁剪 ✅ 第三轮实证**：注入 1100 条 → 发送真实消息触发 upsert → `[prune] removed 101 oldest msgs (limit=1000)` → db 1100→1000 数学吻合（关键发现：仅进入会话不触发裁剪——prune 只在真实新消息写入时执行，设计如此）；**一期 14 项验证：13 项通过/部分，仅 #33 草稿缺陷待修**

### 2026-08-10 系统审计批次（F 报告 P1）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md §3.2

- [~] **#37 combine 索引错位 args[8]→args[9]，工具进度 UI 永久失效** `ui` `sse`
  - 问题：`MessageDataDelegate.kt:172` 错把 `args[8]`（statuses Map）当作 `args[9]`（progressList）→ `progressList` 永远 null → `progressOutputs = emptyMap()` → 工具进度 output 注入永久失效，用户看不到工具执行中的实时 output。combine 第 8 参是 statusFlow、第 9 参是 getActiveToolProgressForSession(sid)
  - 修复：line 172 `args[8]` → `args[9]`（改一个字符）；或用类型安全 combine 变体 / data class 包装根治
  - 工时：~10min | 难度：低 | 涉及：MessageDataDelegate.kt:172
  - 来源：F §P1-7 / C S3

- [~] **#38 ChatViewModel.init / SessionListViewModel 构造期 runBlocking 主线程阻塞** `ui` `refactor`
  - 问题：ViewModel 构造在 Hilt 主线程执行，两处 runBlocking 同步阻塞：① `ChatViewModel.kt:93-96` `runBlocking(IO) { serverRepository.getServer(serverId) }`；② `ChatViewModel.kt:368-373` `draftDelegate.restorePersistedDraft()` → `DraftDataStore.ensureLoaded` → `runBlocking { dataStore.data.first() }`（DraftDataStore.kt:34-50）；`SessionListViewModel.kt:97-99` 同样问题。低端设备/磁盘忙时成 ANR（实测 99th 300ms × 3 帧，首帧贡献源之一）。0eaac6dc 仅修了 onCleared 路径，init 路径完整保留
  - 修复：serverConfig 改 StateFlow<ServerConfig?> + TerminalDelegate 派生 flow；DraftRepository 接口改 suspend fun getDraft 或 Flow<Draft>；DraftDataStore 内部 runBlocking 改 withContext(IO)
  - 工时：~1-2d | 难度：高 | 涉及：ChatViewModel / SessionListViewModel / DraftDataStore / DraftInputDelegate / DraftRepository 接口
  - 来源：F §P1-5 / C S1,S5 + D §2.3

- [~] **#39 日志风暴残留（ChatMessageList 诊断埋点无 DEBUG 门控）** `ui` `performance`
  - 问题：b07b7ccc 清理了 MessageDataDelegate 日志风暴，但 ChatMessageList 内诊断埋点遗漏——① `ChatMessageList.kt:251-267` JUMP 检测 `LaunchedEffect(Unit)` snapshotFlow 持续 collect 每帧（注释明示"诊断埋点...验证后"）；② `ChatMessageList.kt:555-557` 每 item 组合日志 `AppLogger.d` 无 BuildConfig.DEBUG 门控。直接贡献 Slow UI thread（实测 48/160 = 30%）
  - 修复：删除诊断埋点（诊断任务已完成，注释明示）；与 b07b7ccc 一致策略
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt:251-267, 555-557
  - 来源：F §P1-2 / A 环节 F + D 模式 B

- [~] **#40 StateFlow.update CAS lambda 内副作用日志（UnreadDiag/PartUpdated）** `refactor` `performance`
  - 问题：高频 SSE 场景下 `update{}` CAS 重试导致日志被多次持久化到 Room（INFO 级即使 DEBUG 关也持久化）+ 违反纯函数约定：① `MessageEventHandler.kt:567-582`（line 575）`AppLogger.i("UnreadDiag", "[markIdle]...")` 在 `_messages.update {}` 内（实测 1.6 条/s）；② `MessageEventHandler.kt:238-272`（line 250-258）`AppLogger.w("[PartUpdated]...")` 在 `_parts.update {}` 内（实测 11 条/s 活跃，CAS 重试可能 2x）。b07b7ccc 遗漏残留
  - 修复：日志移到 `.update` 外（先 update 拿结果再 log）；或彻底删除诊断日志；对所有 `_*.update {}` lambda 做 lint 禁止副作用
  - 工时：~1h | 难度：低 | 涉及：MessageEventHandler.kt:238-272, 419-428, 463-472, 567-582
  - 来源：F §P1-6 / C S2 + D 模式 B + E 实测（5 路最高置信度）
  - **2026-08-10 完成（待真机验证）**：grep 全量确认 21 处 `_messages.update`/`_parts.update` lambda 内零 AppLogger 调用（此前 DIAG 清理已移除），无需改动；R2 流式 10s 应用日志 0 条佐证

- [~] **#41 loadOlderMessages 缺乏并发保护 → 竞态重复加载** `data` `session`
  - 问题：翻页时多个并发 launch 可能用相同 archiveCursorCreated 拉相同消息。`MessagePaginationDelegate.kt:194-260` line 197 `_isLoadingOlder.value=true` 在 scope.launch 内无入口 guard；触发链 `ChatMessageList.kt:361-385` snapshotFlow collect 无去抖。`_isLoadingOlder` 仅作 UI 状态指示未作互斥锁
  - 修复：入口 guard `if (_isLoadingOlder.value) return`；或 MutableStateFlow.update CAS pattern；或 actor/Semaphore(1) 串行化
  - 工时：~1h | 难度：中 | 涉及：MessagePaginationDelegate.kt:194-260
  - 来源：F §P1-3 / B P1-1
  - **2026-08-10 完成（待真机验证）**：`synchronized(this)` 包住 check-then-set 入口 guard（StateFlow 无 CAS，synchronized 与项目现状一致）；finally 已覆盖异常路径复位；编译+全量单测 1364/0；⚠️ 模拟器上滑触发受 #64 超长消息滚动失效阻碍未完整实测，逻辑已单测覆盖

- [~] **#42 upsert 写入路径 O(n log n) 排序残留** `performance` `refactor`
  - 问题：b07b7ccc 移除了 combine 内排序，但写入路径 sortBy/distinctBy+sortedBy 仍在：`MessageEventHandler.kt:151`(handleMessageUpdated)、`408`(upsertSsePriority)、`453`(upsertRestAuthority)、`508`(upsertAppendOnly)。1000-2000 条会话每次变更 ~10000-40000 次比较；batchScope 后台线程但高频累积 CPU
  - 修复：existing 已有序时改 merge（O(n)）替代 sortedBy（O(n log n)）；或用 TreeMap/有序数据结构维护
  - 工时：~0.5d | 难度：中 | 涉及：MessageEventHandler.kt:151, 408, 453, 508
  - 来源：F §P1-4 / B P1-2 + D TD-9 + A §3 表（3 路确认）
  - **2026-08-10 完成（待真机验证）**：4 处全部改 `mergeSortedMessages` 线性两路归并（O(n+m)），同 id 冲突/相同 created/稳定排序语义与 `distinctBy+sortedBy` 逐字节等价（多组边界推演）；incomingSorted 计算移出 update lambda 避免 CAS 重试重复计算；模拟器 R2orderingtest42 3 轮发送严格按序无乱序/重复/丢失；编译+全量单测 1364/0

- [~] **#43 反射依赖 Compose internal 字段 → 升级必崩** `crash` `refactor`
  - 问题：高度补偿通过反射访问 LazyListState private 字段（scrollPosition、requestPositionAndForgetLastKnownKey、measurementScopeInvalidator）——`ScrollCompensation.kt:22-46`；调用点 `ChatMessageList.kt:318, 448, 539`（3 处）。Compose 版本升级会运行时崩溃（NoSuchFieldError/NoSuchMethodError），无编译期保护。根因：官方 requestScrollToItem 会通过 scroll{} 互斥锁杀死 fling，无"设置位置但不取消 fling"公开 API → 反射 hack 补丁
  - 修复（短期）：try-catch 包裹 + NoSuchFieldError 时降级 requestScrollToItem；Compose 升级前手动测试反射字段名。长期：向 Compose 提 feature request
  - 工时：~0.5d | 难度：中 | 涉及：ScrollCompensation.kt:22-46 + ChatMessageList.kt 3 处调用
  - 来源：F §P1-1 / A 环节 E（补丁判定）
  - **2026-08-10 完成（待真机验证）**：ScrollCompensation.kt 初始化一次性探测 3 个反射成员（失败永久降级）+ 调用 try-catch 防御（catch Throwable 降级官方 requestScrollToItem）+ 注释标明 Compose BOM 2026.05.01 与字段名；ChatMessageList.kt 3 处调用点均经封装无需改（SSE 滚动铁律零接触）；模拟器程序化滚动/补偿正常无崩溃

- [ ] **#79 本地存储精简：工具返回值截断（占用降 90%+）** `data` `refactor` `storage`
  - 需求：2026-08-12 用户系统性评估——会话全量信息本地保存是否合理。实测多会话数据库 28MB，其中 **tool parts（工具返回值）占 12.4MB（97%）**：shell 输出 5.1MB / read 2.6MB / websearch 1.1MB / edit 1.1MB / grep 667KB / webfetch 627KB；消息元数据仅 1.18MB + text 239KB（对话本体很小，纯文本合理）
  - 方案（已系统性分析，按优先级）：
    - **P0**：Room 写入时截断 tool part 的 state（返回值）——只存前 200~500 字符预览 + 总长度标记；展开时调 `getMessage(messageId)`（API 已有 V2ApiClient:409）按需拉全量。**只影响本地落库**，内存渲染不受影响（消息在内存时工具卡片完整可展开）
    - **P1**：reasoning 截断/丢弃；patch 只存统计（+N/-M）+ 文件名不存 diff 全文
    - **P2**：synthetic 通知不落库或保留最近 N 条；subagent 内容不落库（点击进入时加载）
  - 权衡：离线恢复时工具卡片显示摘要无法展开全量（可接受）；服务器始终保留全量可重拉
  - 工时：P0 ~0.5d | 难度：中 | 涉及：MessageStore.upsertParts + 工具卡片展开按需加载
  - 与 #80（快速导航全量列表）不冲突——列表基于 role=user 元数据，不受 parts 截断影响

- [ ] **#81 度量/风格/边距统一提取为 token 主题系统** `refactor` `ui`
  - 需求：2026-08-12 用户提出——将度量参数（如模型列表单行 item 高度 40dp）、风格、边距等样式统一提取为 token/主题系统
  - 现状：已有 SpacingTokens/ShapeTokens/AlphaTokens/ButtonTokens（ui/theme/），但部分组件仍硬编码数值（如 ModelPickerDialog 的 heightIn(min=40.dp)、padding 12/8dp 等散落各处）
  - 方向：新增 ItemTokens（列表项高度/密度规格：40dp 密集 / 48dp 紧凑 / 56dp 标准）、统一列表项 padding/间距引用；对照 docs/ui-conventions.md 的 token 体系扩展
  - 工时：~1d | 难度：中 | 涉及：ui/theme/* + 各列表组件（ModelPicker/QuickNavigate/后台面板等）

- [ ] **#80 快速导航全量列表（本地 Room 全量 user 消息，非仅已加载窗口）** `data` `feature`
  - 需求：2026-08-12 用户反馈"快速定位不准确"——实测根因：快速导航列表基于 rawMessages（已加载窗口）只显示 7 个 item，本地热表实际有 35 条 user 消息（多会话 3939 条中 role=user 占比 35/153）
  - 方案：JumpTargetExtractor 数据源扩展为本地 Room 全量（热表 role=user 查询）；点击未加载目标 → loadAround（c0d28535 已实现服务器版）本地优先（beforeId+afterId 双查询）→ 现有 merge 路径
  - 状态：**2026-08-12 实施中**（子代理 ses_00ac3104cffeSVtl7pteLrgWOD）

---

## P2 — 优化与锦上添花

- [x] **#71 后台系统 + V2 消息链路 D4 人工验收（时间性现象，自动化无法覆盖）** `ui` `sse`
  - **2026-08-13 验收 ✅（用户口头确认"后台没啥问题" + Agent 数据正确性确认）**：shell 生命周期（created/exited/deleted）与内联展示数据与服务器 SSE 事件一致；流式期间 Back 无 ANR。⚠️ 附注：`session.tool.progress` 事件被 SessionNextEventHandler 标记 Unhandled（工具实时进度缺口）→ 登记 #92
  - 问题：2026-08-11 后台系统（入口/工具栏/面板/Shell 卡片）与 V2 消息链路（V2SseMapper 流式）开发完成，自动化验证（编译/单测/E2E 功能走查）全部通过；但以下**时间性现象**自动化无法覆盖，需用户真机验收后才可声称完成（verification-requirements.md 维度 5）：
  - 验收清单：
    1. **转后台工具栏**滑出/消失动画（fade + expandVertically）——出现时机正确、动画顺滑无跳动
    2. **后台入口按钮**角标数字出现/消失过渡（BadgedBox）——有后台活动时数字正确、无闪烁
    3. **后台面板**（ModalBottomSheet）——上拉/拖拽关闭手感、Subagents/Shells tab 切换流畅、子会话跳转返回正常
    4. **SSE 流式节奏**——AI 回复逐字出现无闪烁/卡顿/跳底（SSE 铁律）；停止生成后状态立即恢复
    5. **消息即时显示**——发送后用户消息 3s 内出现（V2SseMapper input.admitted 播种），多轮连续发送顺序正确
  - 验证环境：模拟器 + 真实 V2 服务器（10.0.2.2:4199），**测试专用会话**（用户指定）
  - 证据：docs/research/RG-2026-08-11-v2-contract-background.md（D4 待验收项）
  - 状态：`[~]` 待验证——**用户逐项验收通过后勾选 `[x]`**；发现任何问题改回 `[ ]` 进入修复

- [x] **#67 V2 后台完成通知：synthetic 消息被过滤（PartContent Text 分支）** `sse` `ui`
  - 问题：2026-08-11 V2 契约对齐调研确认——opencode v2 后台任务/subagent 完成时向主会话注入 `POST /api/session/{id}/synthetic` 合成消息；但 oc-beacon 的 PartContent.kt Text 分支 `part.synthetic != true` 直接过滤 → 用户看不到后台完成通知
  - 方案：识别 synthetic 消息并以特殊样式（卡片/淡色+标签）渲染，或独立事件通道驱动通知
  - 来源：docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md §3（synthetic 端点）+ 后台系统调研
  - **2026-08-11 完成（实测澄清）**：Part.Text.synthetic 全项目无赋值点（过滤是死代码，消息本就能显示）；服务器 POST synthetic 后不广播 SSE 事件（仅 REST 可见）。新增 MessageCardRole.SYNTHETIC + SyntheticNotificationCard（居中淡色卡片+图标+时间），模拟器实测显示 "后台测试完成通知：subagent X 已完成" ✅

- [ ] **#68 V2 新会话创建后 get/pending 404（服务器怪癖）** `session` `data`
  - 问题：2026-08-11 实测——新建会话出现在 `GET /api/session` 列表（自动生成标题），但 `GET /api/session/{id}` / `pending` 返回 `SessionNotFoundError`（带/不带 x-opencode-directory 均 404）；服务端重启/升级（next-17132→17135）后可能自愈
  - 方案：待复现确认；若稳定复现需调研 V2 location/workspace 路由语义（列表可能跨 location 返回而 get 限定 location）
  - 来源：模拟器 E2E 实测（2026-08-11）

- [x] **#69 session.instructions.updated 事件未处理（低频 parse error）** `sse`
  - 问题：2026-08-11 回归走查发现 1 次 `session.instructions.updated` parse error（无 parser 匹配且触发异常路径）；频率极低（指令更新时）
  - 方案：V2EventParser handledPrefixes 加 `session.instructions.` 占位解析（返回 SessionNext Unknown 即可）
  - 来源：回归走查 logcat（2026-08-11）
  - **2026-08-11 完成**：SseClientV2.parseV2Event data/properties 判型防御（instructions data 是数组时 jsonObject 扩展抛异常 → 回退顶层字段）+ V2EventParser handledPrefixes 加 session.instructions.；V2EventParserTest 新增用例；实测 parse error 归零

- [ ] **#70 V2 事件体系未确认项（设计文档 §7，实施时补测）** `sse` `refactor`
  - 问题：docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md §7 列出 7 项未确认：
    1. `session.retry.scheduled` payload 结构（未触发重试未抓到）——影响 Retry 状态映射
    2. `/api/config` info 已实测无 mcp 字段——McpRepositoryImpl 的 mcp 配置来源需确认（当前 type 回退 "local"）
    3. `/api/session/active` type 完整枚举（目前仅见 "running"）
    4. listPtyShells 正确端点已实测 = `/api/pty`（✅ 已修复 #Task7）
    5. completeProviderOauth 已补 `/api` 前缀（✅ 已修复 #Task7）
    6. `session.tool.failed` 事件已实测存在（✅ mapper 已支持）
    7. 多 step 工具循环已实测同 assistantMessageID（✅ 幂等 upsert 天然处理）
  - 方案：剩余 1/2/3 项在下次触碰相关功能时补测；4-7 已闭环
  - 来源：docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md

- [~] **#29 androidTest 编译修复（#25 已读标记遗留）** `refactor` `data`
  - 问题：commit 5793957f（#25 已读标记服务器域重构）为 SessionRepository 增加 `getLastCompletedReplyTimeFlow()`、SettingsRepository 增加 5 个已读状态方法，但 FakeSessionRepository/FakeSettingsRepository 未同步实现 → `compileDevDebugAndroidTestKotlin` 从该 commit 起持续失败（2026-08-08 悲观重构验证时发现，与重构无关的预存在问题）
  - 方案：Fake 补缺失接口方法（按接口签名 + 现有 fake 语义实现），恢复 androidTest 编译
  - 工时：~30min | 难度：低 | 涉及：androidTest/fakes/FakeSessionRepository.kt、FakeSettingsRepository.kt
  - **2026-08-09 完成（待人工验证）**：3 个 Fake 补齐 7 个接口新成员（FakeChatRepository.upsertMessages 按 MergeStrategy 分支；FakeSessionRepository.listMessages 改 before+MessagePage 签名 + getLastCompletedReplyTimeFlow；FakeSettingsRepository 5 个已读方法），commit 1ae44d57；compileDevDebugAndroidTestKotlin BUILD SUCCESSFUL ✅（解锁 LogDaoTest 等全部插桩测试编译）；⚠️ 真机验证待用户：插桩测试套件实际运行（connectedDevDebugAndroidTest）

- [x] **#28 提问组件样式与高度统一优化** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：Q tab 替换 M3 大 Tab（48dp）→ 自绘 28dp 胶囊 tab（选中高亮）；问题文本/选项描述 bodySmall→bodyMedium。V1 实测：tab 高度 ~27dp 吻合、字体可读性良好
  - 原问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 现状：QuestionCard 用 AmoledCard + padding SpacingTokens.MD，内部 spacedBy SM；选项行 Surface padding 12/8dp、图标 16dp、文本 bodyMedium/bodySmall；QuestionPagerView 多问题用 TabRow + HorizontalPager
  - 调研方向：M3 官方无 Stepper/提问组件，但可参考官方组件规范——RadioButton/Checkbox 触摸目标 48dp、SegmentedButton（SingleChoice/MultiChoiceSegmentedButtonRow）选项场景、AlertDialog 内容间距规范、LinearProgressIndicator 作步骤指示；对比各组件理论高度与当前实现的差距；检查外边距/间距 tokens（SpacingTokens）是否按 M3 规范使用；评估是否适合改用官方组件
  - 工时：~2-3h | 难度：中 | 涉及：QuestionCard.kt / QuestionPartContent.kt / theme tokens
  - **2026-08-08 代码完成（待人工验证）**：选项行 48dp 触摸目标（defaultMinSize）、图标 16→24dp、间距统一（SpacingTokens.XS/SM/MD）+ QuestionPagerView 多问题分支 spacedBy SM（消除"缩在一起"，commit 83f4ea31）；编译 ✅；⚠️ 真机验证待用户：行高统一/图标大小/间距舒展（维度 5 视觉目测）

- [x] **#18 ChatMessageList 指纹函数外移** `refactor`
  - 问题：765 行因铁律 6-8 缓存逻辑膨胀
  - 方案：只外移纯函数（messageFingerprint/partsFingerprint/tailHash/messagesSignature）到 util/MessageFingerprints.kt；缓存函数高度耦合不动
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt

- [x] **#19 Phase 历史注释清理** `refactor`
  - 问题：约 30 处"在 Phase N Task X 中提取"注释，工作已完成，纯历史噪音
  - 方案：批量删除历史标记，保留功能说明
  - 工时：~30min | 难度：低 | 涉及：ui/screens/chat/* + FileViewer* + Workspace*

- [x] **#20 SearchMatchDto 字段对齐** `data`
  - 问题：DTO 字段与 API 不匹配（API 是 path:{text}/line_number，DTO 是 lines/lineNumber）；当前 /find 未启用未触发，启用即静默失败
  - 方案：按 API 对齐字段（@SerialName 处理 snake_case）
  - 工时：~1h | 难度：低 | 涉及：FileResponses.kt（启用 /find 前必须完成）

- [x] **#21 androidTest 4 个 flaky 用例** `ui` `data`
  - 问题：2026-08-07 #16 androidTest 回归发现 4 个 flaky（与重构无关）：`ChatInteractionIsolatedTest.scrollToBottomFab_appearsWhenScrolledAway`（swipeDown 手势未越阈值）、`ChatInteractionTest.abortSession_callsAbortApi`（等 Stop 按钮超时）、`ChatInteractionTest.permissionDialog_appears_whenPermissionRequested` 与 `questionDialog_appears_whenQuestionAsked`（interactionState 7 路 combine 时序）
  - 方案：失败时重试/等待策略；scrollToBottomFab 用更可靠手势（多段 swipe）；其余 3 个检查 isBusy/interactionState 传播时序
  - 工时：~2h | 难度：中 | 涉及：app/src/androidTest/chat/*
  - **2026-08-07 完成（系统性调试）**：4 用例实为稳定失败非 flaky，根因 3 类——(1) abortSession：测试注入真实 SessionStateService 但 VM 经接口注入 FakeSessionStateRepository（fake 状态机缺失）→ Fake 增强 FSM 模拟 + 测试改操作 Fake；(2) permission/question：测试未 seed 消息走 ChatEmptyState 分支致 ChatMessageList 未渲染 → 补 seedConversation；(3) scrollToBottomFab：全屏 swipeDown(0.05-0.95) 无效改默认；questionDialog 断言歧义（问题文本 2 处）改 onAllNodes().onFirst()；两轮验证 4/4 通过

- [x] **#22 单测 2 个 flaky 用例** `data`
  - 问题：2026-08-07 #17 全量单测（1222）发现 2 个预存在 flaky：`PermissionAutoApproverTest`（序列化相关，此前 round-trip 修复后仍有残留）、`FileViewerViewModelTest`（协程异常时序）；均已三重验证（HEAD 通过/单独跑通过/不引用改动类）与重构无关
  - 方案：PermissionAutoApproverTest 检查 createdAt 等时间字段的序列化竞态；FileViewerViewModelTest 检查协程异常处理时序
  - 工时：~1-2h | 难度：中 | 涉及：app/src/test/...
  - **2026-08-07 完成**：PermissionAutoApproverTest 固定 createdAt 消除毫秒默认值竞态；真根因在 SessionListViewModel finally 块空流 first() 抛 NoSuchElementException 泄漏全局线程池污染无关测试（改 firstOrNull）；FileViewerViewModelTest 防御性加固；全量单测连续 3 次全过

- [x] **#23 SessionList combine 魔法索引 tuple 化重构** 
efactor
  - 问题：SessionListViewModel.combine 22 个源 + SessionListStateBuilder 的 alues[18..22] 魔法索引——加源/删源时索引错位（2026-08-07 已踩坑多次：多选/未读/基线/一键已读各加一次源，每次索引偏移导致编译错误多轮修复）
  - 方案：combine lambda 内构造具名数据类（如 SessionListInputs），StateBuilder 接收它而非裸 Array<Any?>；或 combine 嵌套分组
  - 风险：中（改动 combine 签名 + StateBuilder + 相关测试）；收益：消除索引错位类 bug
  - 备注：索引语义注释已加（StateBuilder 顶部），缓解了短期风险
  - 备注：2026-08-07 已落地——状态切片方案（spec: docs/superpowers/specs/2026-08-07-session-list-state-slicing-design.md）

- [x] **#24 长 turn（多步工具调用）期间未读红点延迟** ui session
  - 问题：agent 长回复（多 step 循环）期间，红点要等整个 turn 结束（服务器发 SessionStatus=idle）才出现——用户在列表等待时迟迟看不到红点（2026-08-07 subagent 实测：后台 13 分钟长 turn 无红点）
  - 现状：列表有 Working/busy 状态指示可缓解
  - 待决策：是否需要"进行中即红点"（每条 assistant 消息 completed 即算）？权衡：与"turn 结束绑定"的用户需求冲突
  - **2026-08-07 关闭（用户决策）**：红点绑定 turn 完全结束是**设计意图**（用户确认），不实施"进行中即红点"。idle 丢失兜底已确认无需做——L3/L4 REST 校验 → FSM forceComplete 链路覆盖 SSE 丢失。衍生项：#25 时钟一致性（当前实施中）；FOLDER 折叠未读计数 / busy 图标强化 / 未读置顶排序 暂不实施

- [x] **#25 红点时间戳时钟一致性** `data` `session`
  - 问题：红点判定混用服务器时刻与客户端时刻——`MessageUpdated` 的 `time.completed`（服务器时钟）与 `markSessionIdle` 覆盖的 `System.currentTimeMillis()`（客户端时钟，MessageEventHandler.kt:520）都流入 `_turnEndTime`；已读时间（readTimes/allReadAt）是客户端 now。本机部署（模拟器连本机 serve）时钟一致无实害；**连远端服务器时时钟偏差 → 红点误报/漏报**
  - 前因：2026-08-07 用服务器时刻（StepEnded.timestamp / completed）是为了避免"退出后事件才到达 → 客户端接收时刻偏晚 → 红点误报"；markSessionIdle 的客户端 now 是 SSE 丢失时 REST 回退补标记的权宜
  - 状态：**调研中**（2026-08-07 起）——需先完成时间戳来源全图谱 + 历史演进梳理，再定优化重构 or 根治方案
  - 工时：调研后再估 | 难度：中-高 | 涉及：EventDispatcher.kt / MessageEventHandler.kt / SessionStateService.kt / SettingsDataStore
  - **2026-08-07 完成**：红点改派生状态模型——maxCompleted（服务器 completed 时刻）+ isUnread 加 status==Idle 门控（turn 结束才红点）+ 已读标记/一键已读改服务器域（markRead 传 completedTs、全局 max）+ v2 迁移（EventDispatcher init 触发，清旧客户端域值）。全量单测（39s）+ 构建安装 + 真机回归 6 场景通过 5/6。**含重启未读恢复**：maxCompleted 持久化（82fc2493）——杀进程重启后未读回复红点恢复，spec §5.7 原"遗留 concern"已解决
- [~] **#31 本地库损坏自愈（Room 版）** `data` `room`
  - 问题：Plan 1 迁移后删除了旧 withDatabaseRecovery（catch SQLiteException → deleteDatabase 重建），Room 版无等价兜底；ocbeacon.db 损坏时 recordBatch 异常会传播至 AppLogger。Room+WAL 较旧实现健壮，诊断日志非用户资产，属低风险
  - **2026-08-09 完成（待人工验证）**：新建 DatabaseRecovery（@Singleton，`withCorruptionRecovery` 捕获 SQLiteException → deleteDatabase → Room 自动重建空库，commit 6fdff190）；LogStore/MessageStore 读写路径接入；DatabaseRecoveryTest 3 用例（成功/SQLiteException 触发删除/非 SQLite 传播）+ 全量单测 PASS；⚠️ 真机验证待用户：模拟 db 损坏后 App 自愈（可选，低优先级）

- [x] **#32 归档压缩（二期：热/冷分层 + 整桶 zstd + TLRU 淘汰）** `data` `room` `cache`
  - 背景：消息本地化批次（#30）Plan 1/2/3 已代码完成（待人工验证）；归档为本需求二期，用户决策：一期（归档之外）全部开发完并人工验证后再开发二期
  - 方案（spec §9 批次 2 + 调研结论）：热表（近期可查）→ 归档表按 (session, 时间桶) 整桶序列化 zstd 压缩（单桶 ≤512KB，CursorWindow 2MB 限制）；压缩触发 = TLRU（now − last_accessed > TTL 如 14 天 或 会话超阈值）；解压触发 = 用户向上滚动到归档边界异步解压整桶入热表（UI loading）；重压缩 = 后台 sweep 超 TTL 未访问桶
  - 技术选型：zstd-jni（Maven Central AAR，~1MB ABI）| 单条消息压缩在 Android 是负优化（<10KB 收益 < 开销，Discord 实测）→ 必须整桶压缩
  - 工时：~1-2d | 难度：中-高 | 涉及：MessageDao/MessageStore 扩展 + ArchiveBucket 表 + sweep 协程 + zstd-jni 依赖 + 翻页管线解压分支
  - **2026-08-09 二期启动**：一期（#30）模拟器验证已全覆盖（14 项 + 补充 3 项，仅剩真机最终确认，用户暂不在附近）；用户决策"先开发二期，注意提交隔离"→ **开发分支 `feature/archive-compression`**（基于 master 954e3c89，未合回）；zstd-jni 最新稳定版 **1.5.7-13**（2026-08-08，Maven Central，Android AAR 支持 API 21+）；二期完成并经验证后合回 master
  - **2026-08-09 二期代码完成**：SDD 6 任务 + 最终 whole-branch review + fix wave（I1 原子事务/I2 Migration 测试/I3 512KB 字节切分 + M2-M9），全量单测 **1342 通过/0 失败**（最终修复含归档游标推进后新增 2 测试）；MigrationTest 编译验证（运行时待模拟器）
  - **2026-08-09 模拟器验证全部通过（日志+db 实证）**：①DB v1→v2 Migration ✅（user_version=2）；②归档写入 ✅（[archive] 161 msgs→1 bucket，热表精确回 1000，压缩率 21x）；③归档读取 ✅（[dearchive] 逐桶 + [paging] from archive + source=ARCHIVE）；④断网离线浏览 ✅（飞行模式仍可读归档）；⑤数据完整性 ✅（解压大小精确匹配、161 条消息完整可解析、热表+归档**零重复**——I1 原子事务有效）；⑥无崩溃 ✅
  - **2026-08-09 完整场景矩阵验证（22 项全过）**：512KB 字节切分 ✅（13条/桶 ≤512KB，0 超限）；跨天分桶 ✅（10/11 天窗口）；TLRU 淘汰 ✅（251 桶→evicted 61 保持 200，leastAccessed 优先）；归档失败降级 ✅（坏 payload → prune-only fallback 实测触发）；**坏桶 skip-continue ✅（bucket=267 decode failed, skipping，跳过继续读后续桶）**；多会话归档隔离 ✅（db 实证仅目标会话有归档）；冷启动种子化 ✅；UI 消息渲染 ✅（uiautomator 抓到消息文本）；迁移旧数据保留 ✅；touch lastAccessedAt ✅
  - **2026-08-09 归档逐条容错（69df372b）**：模拟器实测发现——单条 payload 解码失败（测试注入的 path 字段格式错误）导致**整批归档失败**（500 条全丢，降级为 prune-only）。修复：逐条 runCatching 跳过坏消息（`skip undecodable msg` 日志），好的仍归档。补单测 `upsertMessages_archiveSkipsUndecodableMessage_keepsBatch`
  - **2026-08-09 #35 ANR 复现**：验证过程中 Back 触发 ANR（Input dispatching timed out，wait queue 2）——**根因：SSE 高频流量（每分钟数百事件）占满主线程**，Back 键输入事件排队超时。与二期归档无直接关系（归档在 IO 线程）。backlog #35 已登记，待专项排查（SSE 事件处理主线程负载优化）
  - **2026-08-09 修复（d30a0d57）**：模拟器实证发现**归档翻页死循环**——loadOlderMessages 的 before 始终取热表最老（归档只进内存不落热表 → 热表最老不变 → 每次翻页读同一批归档桶）。修复：Delegate 维护**归档时间游标**（ARCHIVE 来源用返回最老消息 created 推进；NETWORK 来源重置），use case 增加 beforeCreated 参数优先用它查归档。修复后验证：before 正确前进 → 归档读尽 → 网络回退 ✅

- [x] **#33 草稿在进程被杀时丢失（saveDraft 仅 onCleared 触发）** `data` `session`
  - **2026-08-13 验证 ✅（Agent 代测）**：输入草稿 → force-stop → 重启重进会话 → 草稿完整恢复（截图 v33_draft.png）
  - 问题：2026-08-09 模拟器走查（V7）发现——ChatViewModel.kt:430 的 draftDelegate.saveDraft() 仅在 onCleared() 调用，am force-stop / 系统低内存杀进程不触发 onCleared → 草稿丢失（输入框重置为 placeholder）。预存问题（触发时机一直如此，非 #30 的 DataStore 迁移引入；迁移只改存储机制）
  - 影响：用户按 Home 后台 + 系统杀进程 → 草稿丢失；正常返回（ViewModel onCleared 触发）不受影响
  - 方案：updateDraftText 加防抖定期 saveDraft()（如 1-2s 无输入即存），或 Activity onStop/onSaveInstanceState 触发；需评估写频率与 DataStore 成本
  - 工时：~1h | 难度：低 | 涉及：DraftInputDelegate / ChatViewModel
  - 来源：2026-08-09 模拟器走查 V7
  - **2026-08-09 完成（待人工验证）**：DraftInputDelegate.updateDraftText 加 500ms 防抖自动持久化（DRAFT_SAVE_DEBOUNCE_MS，scope.launch + delay，每次输入取消重启）；clearDraft 取消挂起 job（防清空后又被存回）；onCleared 兜底保留；新增 DraftInputDelegateTest 4 用例（防抖窗口内不存/快速输入只存最后一次/clear 取消/即时状态更新，commit e3ffeae7）；编译 ✅ 全量单测 ✅；⚠️ 真机验证待用户：输入草稿 → force-stop → 重启 → 草稿仍在

- [x] **#34 同 URL 第二服务器连接 UX（永久 Connecting 无提示）** `ui` `sse`
  - **2026-08-13 验证 ✅（Agent 代测）**：允许添加同 URL 服务器；连接时提示 "Already connected to this server"，未卡 Connecting（日志：HomeViewModel shares backend with already-connected）（截图 v34_dup_server.png）
  - 问题：2026-08-09 双服务器验证发现——同 URL 第二个配置点 Connect 后永久卡 'Connecting...'（>60s 无握手/无错误/无日志），手动 Cancel 才能退出。架构上 app 限制同 URL 单一活跃 SSE 连接（防双投递），但 UX 无反馈
  - 方案：检测到同 URL 已有活跃连接时直接拒绝并提示'该后端已连接'，或复用现有连接；或加超时/错误提示
  - 工时：~1h | 难度：低 | 涉及：连接管理 UI + SseConnectionManager
  - 来源：2026-08-09 双服务器去重验证走查
  - **2026-08-10 完成（待真机验证）**：根因 = OpenCodeConnectionService.connect 已有 url+username 去重但静默 return，HomeViewModel 乐观 connecting 状态无回传 → 永久 Connecting。修复：HomeViewModel connectToServer 经 serviceBinder.findDuplicateBackend 预检（ServerConfig.sameBackend 归一化：协议/host 小写、默认端口、尾斜杠）→ 命中写 connectionErrors 红字提示"该服务器已连接"（home_error_already_connected，15 语言）；Service 内去重保留为纵深防御。编译 ✅ i18n-check ✅（583 keys × 14 语言一致）

- [x] **#35 会话内 Back 触发一次 ANR（待复现）** `crash` `ui`
  - **2026-08-13 验证 ✅（Agent 代测）**：流式输出期间按返回键——无 ANR、无 crash、无 "not responding"（crash buffer 空）；SSE 高频负载下 Back 正常（截图 v35_back.png）
  - 问题：2026-08-09 走查——首次启动后会话内按 Back 触发 ANR（'OC Beacon Dev isn't responding'），force-stop 重启后恢复。可能与 SSE 长连接 + 主线程阻塞有关。仅一次未复现
  - 方案：待复现——logcat 抓 ANR trace；检查 Back 导航路径是否有主线程阻塞（会话关闭时的同步操作）
  - **2026-08-10 模拟器高强度复现未复现**（D35）：55+ 轮（标准循环 20 / 加载中 Back 10 / 双击 Back 10 / 后台切换 5 / 300ms 高强度 20）零 ANR 零崩溃；最大 GC pause 91.69ms、最长帧 ~4.9s 均未触发阈值；52 条 ERROR 全为 JobCancellationException（Back 取消分页加载的预期行为）。结论：模拟器无法复现，疑似偶发/低端真机内存压力场景；保持 P2 低优先，真机复现后再查。证据：docs/research/audit-2026-08-10/D35-investigation.md + metrics/D35-*（45 份 logcat）
  - **副发现（新条目 #65）**：Back 退出时 JobCancellationException 被记为 ERROR 级（日志噪声），建议降级 INFO/DEBUG
  - 工时：待复现后再估 | 难度：中 | 涉及：会话导航/生命周期
  - 来源：2026-08-09 双服务器验证走查
  - **2026-08-09 根因确认并修复**：真机 ANR trace 抓取——退出会话 → ChatViewModel.onCleared → draftDelegate.saveDraft → DraftDataStore.persist → **runBlocking 阻塞主线程**（草稿 DataStore IO 在主线程同步执行）。修复（0eaac6dc）：onCleared 改 `viewModelScope.launch { withContext(NonCancellable) { saveDraft() } }`（异步不阻塞主线程）+ DraftInputDelegate.saveDraft/clearDraft 移 Dispatchers.IO；编译 ✅ 全量单测 ✅（1343 PASS）；⚠️ 真机验证：用户确认闪退/卡死已修复（2026-08-10 用户实测 ✅）

- [x] **新增 C：会话列表点击进入会话的过渡动画丢失** `ui`
  - 问题：2026-08-10 真机排查滑动卡顿期间发现——点击会话进入会话界面时，如果会话内容未加载完毕，原应有过渡动画（loading 过渡）；现在过渡动画也没有了，进入会话直接无过渡/直接显示
  - 影响：进入会话体验突兀（无加载过渡反馈）；可能与导航/加载状态显示逻辑近期改动有关（#23 状态切片或翻页管线改动后）
  - 方案：对比 ChatScreen 进入时的 loading 状态显示逻辑（SessionLifecycleDelegate.loadSession 加载编排 + ChatScreen 加载态）；确认过渡动画缺失点（加载指示器/淡入过渡）
  - 工时：~1h | 难度：中 | 涉及：ChatScreen 加载态 / 导航过渡 / SessionLifecycleDelegate
  - 来源：2026-08-10 真机排查滑动卡顿（用户口头反馈，明确"记一下，后面修复"）
  - **2026-08-10 根因并修复**：加载动画逻辑存在（`isLoading && messages.isEmpty()` 显示 PulsingDots），但**消息本地化（一期）后缓存秒开** → 加载太快 → PulsingDots 一闪而过不可见 → 用户感知"过渡没了"。修复（ec875ff7）：ChatScreen 加 `showLoadingTransition` 状态 + `MIN_LOADING_VISIBLE_MS=400`——即使消息立即到达，PulsingDots 也至少显示 400ms 再消失（仅首次进入且内容未加载完时显示；返回已有会话不显示；不遮挡内容/不拦截触摸，区别于已移除的"加载蒙版"）。验证：模拟器实证——进入"系统优化"/"生成对话标题"会话，150-250ms 加载指示器清晰可见 ✅；全量单测 1343 PASS ✅；i18n PASS ✅；⚠️ 真机复测待用户

- [ ] **新增 D：会话列表滑动卡顿/掉帧（8 项根因已修复，待真机复测）** `ui` `performance`
  - 问题：2026-08-10 真机排查——会话列表滑动"卡手"（拉伸动画中无法反向滑动）+ 掉帧（SSE 活跃时 90th 17ms / 99th 30ms+ / slowUI 40-50 次）
  - **根因 1（卡手）**：Android 12+ 默认 Stretch overscroll 拉伸动画拦截输入 → 全局禁用（`LocalOverscrollFactory provides null`，MainActivity）
  - **根因 2（掉帧）**：日志风暴——MessageDataDelegate combine 每 48ms 打 4 条 MsgDiag（每秒 ~80 条 logcat 写入）→ 彻底删除
  - **根因 3**：MessageEventHandler.handleMessageUpdated 每次 O(n) 全量 filter（1896 条消息仅用于诊断日志）→ 删除
  - **根因 4**：combine 每 48ms 冗余 O(n log n) 排序（数据源已有序）→ 移除
  - **根因 5**：SQLite IN 999 变量上限——大会话（1896 条）partsForMessages 抛 SQLiteException → 分块查询（≤900/块，新增回归测试）
  - **根因 6**：L3 REST 校验 limit=0 全量拉取（1989 条）→ 最新 50 条补漏
  - **根因 7**：上滑分页失效——reverseLayout 下 lastVisibleItemIndex 语义错误（恒等于底部 → 无限翻页/不触发）→ firstVisibleItemIndex + isScrollInProgress
  - **根因 8**：ANR——onCleared 主线程 runBlocking（已在 #35 修复）
  - 验证：模拟器实证——上滑翻页归档加载 ✅（`Loaded older: 20 msgs source=ARCHIVE`）；SQLite 错误 0 ✅；L3 refresh 50 msgs ✅；slowUI 26→0 ✅；全量单测 1343 PASS ✅；i18n PASS ✅
  - ⚠️ **待真机复测**：用户拿回手机后验证——滑动跟手度（无拉伸）/上滑翻页/掉帧（SSE 活跃时）

- [ ] **新增 E：上滑分页后底部最新消息消失（已修复，待真机复测）** `data` `session`
  - 问题：2026-08-10 用户实测——进入主对话界面后，上滑（加载更早消息）再下滑，**无法回到最底部**；"最底部的消息像是直接从整个主对话流中没有了一样"
  - **根因**：`MessageEventHandler.upsertAppendOnly`（APPEND_ONLY 合并策略）的 `_messages.update` 用 `incomingMsgs.map { existingById[newMsg.id] ?: newMsg }`——**把整个 _messages 替换为分页加载的"更早消息"**（incoming 只含更早，不含现有最新）→ **现有最新消息（底部）全部丢失**。二期 caf8019b（upsert 合并策略统一）引入；注释语义"仅补充缺失"与实现不符
  - **修复（ff192fd5）**：改为 `(existing + incomingMsgs).distinctBy { it.id }.sortedBy { it.time.created }`——existing 保留 + incoming 补充缺失 + 按 created 排序（combine 依赖写入路径有序）。同时修正 EventDispatcherTest 旧断言（固化 bug 的 size=1 → size=2），新增 2 回归测试（APPEND_ONLY 保留最新 + 分页场景）
  - 验证：模拟器实证——上滑分页 18 次（540 条更早消息）后下滑，底部最新消息仍保留 ✅；全量单测 1345 PASS ✅
  - ⚠️ **待真机复测**：上滑加载更早后下滑能回到最底部，最新消息不消失

- [ ] **新增 F：上滑自动加载更多失效（已修复，模拟器实证，待真机复测）** `ui` `session`
  - 问题：2026-08-10 用户实测——主对话界面上滑"看似滑到顶"但不再加载更多（有更多内容却加载不出来）
  - **根因 1（不触发）**：`shouldPaginate` 依赖 `listState.isScrollInProgress`——用户滑到顶**停住**时 =false → 不触发。修复：改 `LaunchedEffect(hasOlderMessages, isLoadingOlder, autoLoadPaused)` + `snapshotFlow { listState.layoutInfo }` 持续监听——距顶 ≤8 即触发（无论是否滚动中）；`isLoadingOlder` 作 key → 加载完成重启监听 → 停在阈值内自动续载
  - **根因 2（死循环）**：NETWORK 分页游标不前进——热表最老不变（窗口外消息不落热表）+ use case 的 before 编码依赖 `messageCreatedAt(beforeId)`（游标消息不在热表 → null → before 不编码 → 服务器返回最新 → 游标 A→B 交替循环，模拟器实证每 ~100ms 拉同一批）。修复：Delegate 新增 `networkCursorId/Created` 独立游标 + use case 新增 `networkBeforeCreated` 参数（跳过归档直接 `CursorCodec.encode(id, created)`）
  - **根因 3（防风暴）**：自动续载无保护——连续失败会无限重试。修复：失败指数退避（500ms→8s）+ 3 次失败暂停（autoLoadPaused，UI 停止自动续载）+ 成功恢复清零
  - 日志：ChatPaging（auto-load triggered/backoff wait）+ loadOlder START/END/NETWORK/ARCHIVE/退避/暂停/恢复全链路
  - 验证：模拟器实证——停在顶部 8s 自动续载、游标 fe0c5862→fe0b9e6e→fe0b4438 前进、读尽 hasOlder=false 自动停止 ✅；全量单测 1350 PASS ✅（新增 5 回归测试：游标前进/网络游标跳过归档/退避/暂停/恢复）；i18n PASS ✅
  - ⚠️ **待真机复测**：上滑到顶停住 → 自动加载更早直到读尽，不重复加载、不风暴

### 2026-08-10 系统审计批次（F 报告 P2 + 补丁债 + 模式）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md §3.3 + §6.2 补丁债根因修复 + §6.3 模式发现

- [ ] **#44 sseJob + messageListState 双订阅同源（2x combine 重组）** `performance` `refactor`
  - 问题：每个 SSE 事件触发两个独立 combine 同时重组，CPU 翻倍。`MessageDataDelegate.kt:142-143`（messageListState）vs `319-333`（sseJob）观察相同 getMessagesFlow + getParts；1896 条消息场景每 48ms 2x O(n) 扫描
  - 修复：让 messageListState 同时暴露 rawMessages 字段，消除 sseJob 独立 combine
  - 工时：~0.5d | 难度：中 | 涉及：MessageDataDelegate.kt:142-143, 319-333
  - 来源：F §P2-1 / C S4 + A 间接 + E janky 贡献（3 路确认）

- [x] **#45 AppLogger 字符串拼接未门控** `refactor` `performance`
  - 问题：高频路径调用方未加 `if (BuildConfig.DEBUG)` 门控，字符串模板在传参前已拼接，即使 shouldPersist 返回 false 也已付出成本。`AppLogger.kt:154-175`；EventDispatcher:249、MessageEventHandler:575/255 无门控（对比 :157 有门控）
  - 修复：高频路径调用方强制 BuildConfig.DEBUG 门控；或 AppLogger 内部 lazy 拼接
  - 工时：~1h | 难度：低 | 涉及：AppLogger.kt + EventDispatcher/MessageEventHandler 调用点
  - 来源：F §P2-2 / C S8 + A 环节 F + D 模式 B（3 路确认）
  - **2026-08-11 完成**：EventDispatcher CommandExecuted（每命令事件）加 DEBUG 门控；扫描确认其余每事件级日志均已门控（219/231、SseClientV2 V2 event、MessageEventHandler 235）；#40 已清理 update lambda 内日志

- [x] **#46 combine 上游无 distinctUntilChanged 兜底** `refactor`
  - 问题：派生 flow 无 distinctUntilChanged 兜底，每次上游 emission（即使内容相同）触发 combine 重组。`ChatRepositoryImpl.kt:92-98, 461-462`
  - 修复：派生 flow 加 distinctUntilChanged
  - 工时：~30min | 难度：低 | 涉及：ChatRepositoryImpl.kt:92-98, 461-462
  - 来源：F §P2-3 / C S9
  - **2026-08-11 完成**：10 处派生 flow 加 distinctUntilChanged（getParts/getPermissionsFlow/getQuestionsFlow/getActiveToolProgress/getStepProgress/getCompactionState + 4×ForSession）；getMessagesFlow 不加（List equals O(n) 反效果）；全量单测通过

- [x] **#47 100ms ticker 叠加 48ms flush（流式 footer 重组 ~30 次/s）** `ui` `performance`
  - 问题：流式消息 footer 重组约 30 次/s（48ms flush ~20 + ticker ~10）。`MessageCardAssistant.kt:155-163`
  - 修复：移除 ticker 或与 flush 对齐单一更新源
  - 工时：~1h | 难度：中 | 涉及：MessageCardAssistant.kt:155-163
  - 来源：F §P2-4 / A 环节 G
  - **2026-08-11 完成**：ticker state 抽为 StreamingElapsedText 独立子 composable（重组仅限单个 Text，保留 0.1s 精度）；footer 重组 ~30→~10 次/s；全量单测通过

- [ ] **#48 长会话无消息窗口裁剪（GC 压力 + combine 开销）** `performance` `data`
  - 问题：LazyColumn 回收视图但数据层全量驻留；长会话（>2000 条）GC 压力 + combine 开销。`MessageDataDelegate.kt:179-189`；全库无窗口化
  - 修复：数据层窗口化（保留可视区 + 缓冲区，远端裁剪）
  - 工时：~1-2d | 难度：高 | 涉及：MessageDataDelegate.kt + 翻页管线
  - 来源：F §P2-5 / A 环节 H
  - **2026-08-11 调研结论（暂缓，保留记录）**：
    - **收益现状**：combine 开销大头已由 #44（双订阅合一）+ #46（distinctUntilChanged）+ #42（O(n) 归并）+ ChatMessage 缓存解决；剩余纯内存驻留（几十 MB）在现代设备影响有限
    - **结构性约束**：列表降序 + reverseLayout（index 0=最新在底部），固定窗口裁剪要么裁掉刚加载的更早消息、要么裁掉最新消息；自动分页触发 `totalItemsCount - firstVisibleItemIndex <= 8` 依赖列表总长——简单窗口化不可行，完整方案需 UI offset 模型（ChatMessageList 承重改造）
    - **业界调研**（2026-08-11，Telegram + AI Agent 工具）：
      - Telegram Android：**滚动翻历史不释放**——`dialogMessage` 全局单例按会话缓存全部已加载 MessageObject；内存可控靠"轻量元数据驻留（媒体字节外置 ImageLoader LruCache 字节上限 memoryClass/7）+ SQLite 分页 + messages_holes 空洞表"
      - AI Agent 类（OpenCode/Cline/Continue/LobeChat）：**文本消息业界主流 = 数据层全量驻留 + 渲染层虚拟化**（LobeChat react-virtuoso 最成熟但数据仍全量）；无"滚动懒加载文本"先例
      - OpenHands：唯一明确推进"数据层双向分页 + cap 内存"的案例（RFC #12705/#12707/#12616，进行中未完全落地）——印证完整窗口化复杂度确实高
      - 折叠展开（Cline tool 输出折叠）可作将来低风险优化（省渲染内存，不省数据层）
    - **决策**：暂缓（用户确认）。将来内存吃紧时的优先级建议：折叠展开（低风险）→ 非活跃会话裁剪（#48A 保守）→ OpenHands 式双向分页（高成本）

- [x] **#49 loadArchivedRange N+1 查询 + 写模式** `data` `performance`
  - 问题：每桶 1 查询 + 1 写；桶被字节上限切小时多次循环。`MessageStore.kt:264-292`
  - 修复：批量查询 + 批量写入
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.kt:264-292
  - 来源：F §P2-6 / B P2-1
  - **2026-08-11 完成**：一次查询 limit 桶 + 按需解码（原每桶 1 查询 + 1 touch）；touch 仅对实际解码桶

- [x] **#50 loadArchivedRange 解压整桶浪费** `data` `performance`
  - 问题：解压整个桶（最多 200 条/512KB + 桶内排序），只需 30 条。`MessageStore.kt:302-307`
  - 修复：桶内索引或分页解压；或减小桶粒度
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.kt:302-307
  - 来源：F §P2-7 / B P2-2
  - **2026-08-11 完成**：每桶 takeLast(need) 只取窗口内最新 need 条；结果显式升序（SQL 保证桶全在窗口内，filter 冗余）

- [x] **#51 messagesForSession 的 OR 子句可能放弃复合索引** `data` `performance`
  - 问题：`(:beforeId IS NULL OR id < :beforeId)` 可能放弃复合索引；ORDER BY 与索引不完全匹配。`MessageDao.kt:19-24`；热表限 1000 条缓解
  - 修复：拆分为两条查询（有/无 beforeId），或调整索引覆盖
  - 工时：~2h | 难度：中 | 涉及：MessageDao.kt:19-24
  - 来源：F §P2-8 / B P2-3
  - **2026-08-11 完成**：拆两条查询（messagesForSession 无条件 / messagesBefore 带 beforeId），MessageStore.loadRange 按游标分支；测试适配

- [ ] **#52 SSE 双写高频落盘** `data` `performance`
  - 问题：每 48ms flush → upsertMessages 3 查询 + 写 + 可能归档；活跃流式 ~20 次/s 落盘。`MessageEventHandler.kt:86-129, 194-204`；WAL 缓解
  - 修复：合并写入 / 降低 flush 频率 / 批量 upsert
  - 工时：~0.5d | 难度：中 | 涉及：MessageEventHandler.kt:86-129, 194-204
  - 来源：F §P2-9 / B P2-4
  - **2026-08-11 评估结论（#57 actor 已闭环）**：写频率受 48ms flush 铁律约束（不可降）；flushPendingDeltas 已按会话聚合（单会话每 48ms 仅 1 次 upsert）；actor 单写协程无堆积——无进一步收益，保持现状

- [x] **#53 过渡动画 400ms 反模式（补丁债，故意延迟加载态）** `ui` `refactor`
  - 问题：**当前实现是补丁**。`ChatScreen.kt:255-256, 433-447, 675-683`（MIN_LOADING_VISIBLE_MS=400）故意延迟显示加载态（反模式，欺骗用户感知）；魔法常量 400 无 A/B 依据。ec875ff7 引入（"新增 C"修复过渡动画丢失）
  - 根因修复（D TD-2）：移除常量；会话路由加 enterTransition/exitTransition；loading 指示器回归"仅在真正加载时显示"
  - 工时：~0.5d | 难度：中 | 涉及：ChatScreen.kt + 导航路由
  - 来源：F §P2-10 + D §2.2/TD-2
  - **2026-08-11 完成**：实测 NavHost 全局 fadeIn(tween(AppMotion.MEDIUM)) 已提供进入过渡（双过渡叠加）→ 移除 MIN_LOADING_VISIBLE_MS + showLoadingTransition，PulsingDots 回归纯加载态；ChatScreen 按编辑协议编译+提交+全量测试

- [x] **#54 草稿持久化补丁链（补丁债，防抖窗口内杀进程仍丢）** `data` `session`
  - 问题：**当前实现是补丁链**（0eaac6dc → e3ffeae7 双补丁）。`DraftInputDelegate.kt:127-145` 每次 updateDraftText launch+cancel job（高频输入大量 Job 创建销毁）；500ms 防抖窗口内 force-stop 杀进程仍丢；onCleared 用 viewModelScope（页面销毁时 scope 取消可能不执行）
  - 根因修复（D TD-3）：DraftRepository 暴露 `draftFlow: Flow<Draft>`，UI collectAsState + onValueChange 写 DataStore（原子合并写）；移除防抖 job；onCleared 用独立 scope（NonCancellable）
  - 工时：~0.5d | 难度：中 | 涉及：DraftRepository / DraftDataStore / DraftInputDelegate
  - 来源：F §P2-11 + D §2.3/TD-3 + C S6
  - **2026-08-11 完成**：updateDraftText 去防抖 Job 直写 DataStore（DataStore.edit 内部串行合并）+ saveDraft persistMutex 串行保序（防乱序覆盖）；测试适配直写语义（立即持久化/顺序保存/clear 不恢复）

- [ ] **#55 L3 校验 limit=50 魔法常量（补丁债，长时间离线仍漏消息）** `data` `session`
  - 问题：**当前实现是补丁**。`SessionStateService.kt:34, 276-282`（REST_REFRESH_LIMIT=50）魔法常量无 A/B 依据；长时间离线陈旧窗口 >50 条仍丢消息。a7aec358 引入（limit=0→50）
  - 根因修复（D TD-4）：`lastSyncCursorPerSession` Map，L3 校验用 `before=encode(lastSyncCursor)` 增量同步；同步成功后推进游标
  - 工时：~0.5d | 难度：中 | 涉及：SessionStateService.kt
  - 来源：F §P2-12 + D §2.1/TD-4
  - **2026-08-11 调研结论（暂缓）**：~~V2 listMessages 不支持 before~~ **2026-08-11 实测修正**：V2 服务器**支持**分页（参数名 `cursor`，值=响应体 cursor.next，base64url {"id","order","direction"}；before 参数被忽略）；App 端 V2ApiClient 原缺失 cursor 参数（#56 联动已修复 dfdc116d）；增量游标方案仍待实测（#70）；现状 limit=50 + 进入会话 loadMessagesForSession 全量兜底已覆盖绝大多数场景

- [x] **#56 分页状态散落重构（TD-1，高严重度技术债）** `refactor` `session`
  - 问题：`MessagePaginationDelegate` 9 个可变状态成员（currentMessageLimit, archiveCursorCreated, networkCursorId, networkCursorCreated, _hasOlderMessages, _isLoadingOlder, autoLoadFailures, autoLoadPausedUntil, _autoLoadPaused），职责膨胀。D 报告标记"高严重度"——同一根因（游标抽象缺失）导致 3 次复发（d30a0d57/c5e0ea56）。与 AGENTS.md"SessionStateService 单一真相源"原则相悖
  - 修复（D TD-1）：抽 PaginationCursor sealed class + PaginationFSM（参照 SessionStateFSM 纯函数）；9 个状态 → ≤3 个；修复后可一并消除 #41（loadOlder 竞态）温床
  - 工时：~1-2d | 难度：高 | 涉及：MessagePaginationDelegate / MessagePaginationUseCase / MessageStore
  - 来源：F §P2-13 + D TD-1/模式 A + B §4 图
  - **2026-08-11 完成（6d3118a2）**：PaginationCursor sealed class（HotStart/Archive/Network）+ PaginationFSM 纯函数状态机；9 个散落成员 → 3 个（limit 配置 + FSM State + isLoadingOlder 互斥）；applyTransition 同步投影（synchronized 串行化）；PaginationFSMTest 12 + DelegateTest 18；⚠️ 修 stateIn(Eagerly) 常驻协程卡 runTest 问题（改同步投影）；全量单测 1465/0/0
  - **2026-08-11 模拟器实测联动**：发现 V2 网络翻页死循环（90s 250 次请求）——修复（dfdc116d）：V2ApiClient 透传服务器 cursor + PaginationCursor.Network.serverCursor + FSM.LoadSucceeded.nextCursor 透传链；回归测试 v2 network pagination passes server cursor；复测：循环终止（2 次即停）✅；另发现 #72（归档桶内分页缺陷）+ #73（首次网络 cursor 格式不兼容）

- [x] **#57 batchScope 无生命周期管理** `refactor`
  - 问题：App 级 SupervisorJob scope，App 退出时不取消；多会话同时活跃时 fire-and-forget 协程数无上限。`MessageEventHandler.kt:71, 194-204`
  - 修复：绑定生命周期（ViewModel/process scope）；或限流
  - 工时：~2h | 难度：中 | 涉及：MessageEventHandler.kt:71, 194-204
  - 来源：F §P2-14 / C S7
  - **2026-08-11 完成**：SSE 双写改 actor 模式（Channel BUFFERED 队列 + 单写协程串行处理，持久化协程数恒 1；背压排队不丢）

- [x] **#58 NetTrace 日志 hot path（删 MsgDiag 又加 NetTrace，配套补丁债）** `performance` `refactor`
  - 问题：b07b7ccc 删 MsgDiag 又加 NetTrace——hot path DEBUG 级日志模式不一致；实测 8 条/10s。D 模式 B（DIAG 残留反复）的典型案例
  - 修复（D TD-8）：采样 + 强制 BuildConfig.DEBUG 门控 + CI lint 禁止 DebugLogger 在 main 分支
  - 工时：~1h | 难度：低 | 涉及：NetTrace 日志点 + CI lint 配置
  - 来源：F §6.2 TD-8 + D 模式 B + E 实测
  - **2026-08-11 完成（确认现状已达标）**：NetTrace 2 处（SessionRepositoryImpl:221/223）均已带 BuildConfig.DEBUG 门控；8 条/10s 属 DEBUG 构建正常量。detekt CI lint 引入需新工具链，单独成项（见 #70 补充）

- [x] **#59 SQLite IN 分块下沉 DAO（TD-7，逻辑散落业务层）** `refactor` `data`
  - 问题：b07b7ccc 解决 Room IN 999 变量上限，但分块逻辑散落 MessageStore（业务层）而非 DAO 层
  - 修复（D TD-7）：下沉 DAO 层封装 @Query 内部分块
  - 工时：~0.5d | 难度：中 | 涉及：MessageDao + MessageStore
  - 来源：F §6.2 TD-7 + D §4 模式
  - **2026-08-11 完成**：MessageDao default 方法 partsForMessagesChunked（SQLITE_IN_VARIABLE_LIMIT=900 移入 DAO companion）；MessageStore 委托；分块回归测试改匿名 DAO 实现（断言 [900,600]）

- [x] **#60 catch(Exception) 吞 CancellationException 模式守护（TD-6 已修需持续守护）** `refactor`
  - 问题：协程反模式反复出现（≥2 次）。TD-6 已被 61e4107a 修复（先重抛 CancellationException），但模式需持续守护防止复发
  - 修复（D 模式 C）：safeCatch 工具函数（先重抛 CancellationException）+ detekt SwallowedException 规则
  - 工时：~2h | 难度：低 | 涉及：detekt 配置 + safeCatch 工具
  - 来源：F §6.2 TD-6 + §6.3 模式 C
  - **2026-08-11 完成（工具落地）**：SafeCatch.kt（suspend safeCatch：CancellationException 重抛传播）+ SafeCatchTest 3 用例；DraftDataStore 3 处典型模式迁移示范；剩余 41 文件 123 处逐步迁移（登记 #70）

- [ ] **#61 多 commit 打包修复（流程改进，降低可审计性）** `refactor`
  - 问题：一个 commit 打包多项修复（b07b7ccc/1beb846b/16c7a15c/c5e0ea56），降低可审计性。D §4 模式 E
  - 修复（D 模式 E）：fix commit 一事一 commit；PR review 检查打包项
  - 工时：流程改进 | 难度：低 | 涉及：提交流程规范
  - 来源：F §6.3 模式 E

- [x] **#62 Ktor Client HTTP 引擎日志量偏大（实测 90 条/10s，当前最大日志源）** `refactor` `performance`
  - 问题：2026-08-10 模拟器复测（#39 修复后）发现——应用诊断日志已降至 20 条/10s，但 Ktor Client HTTP 引擎日志仍 90 条/10s（响应头/请求元数据逐条打印），成为当前最大日志源。证据：docs/research/audit-2026-08-10/metrics/R39-stream-10s.log
  - 修复：调低 Ktor Client 日志级别（LogLevel.HEADERS → NONE/仅错误）或改 INFO 级别过滤；保留请求失败时的错误日志
  - 工时：~0.5h | 难度：低 | 涉及：Ktor HttpClient 配置
  - 来源：R-revalidation.md §发现的问题 1
  - **2026-08-11 完成**：LogLevel.HEADERS → INFO（只保留请求方法/URL + 状态行）；release 保持 NONE

- [x] **#63 SseClient 256KB 单行边界截断超长 SSE 帧** `sse`
  - 问题：2026-08-10 功能回归走查发现（预有问题，非回归）——流式期间 logcat 出现 `E SseClient: SSE line exceeds 262144 bytes, aborting read` ~14 次，单行超 256KB 即 abort 读取；实测流式均最终完成，但超长单帧（超大 code block/token 批次）存在被截断风险
  - 证据：docs/research/audit-2026-08-10/RG-regression.md
  - 修复：评估提高上限（512KB/1MB）或改分片读取（按事件边界重组）；需验证内存影响
  - **2026-08-10 完成**：readRawLineBytes 超长行行为改为**丢弃该行并继续读下一行**（不再 abort 读循环触发断连重连——原实现超大 payload 批次会造成无谓断连与丢帧窗口）；单行上限 256KB→512KB（与事件级 1MB 上限配合）；内部循环跳过，调用方零感知；catch 保留部分行容错语义。R4 验证：流式完整 ✅、E 级 abort 日志 0 条 ✅、crash 空 ✅

- [x] **#64 超长消息会话手动滚动失效（fling/swipe/PAGE_UP 全无效）** `ui` `performance`
  - 问题：2026-08-10 第二批回归（R2）发现——进入"最后一条消息为超长内容（代码块+flowchart）"的会话后，fling/swipe/PAGE_UP 滚动疑似全失效（截图哈希相同）
  - 证据：docs/research/audit-2026-08-10/R2-regression.md + metrics/R2-10a/b、R2-12a/b
  - **2026-08-10 关闭：误判（非 bug）**。系统性二分排查（D64-bisect：CURRENT/NO42/NO43 三 APK）+ 决定性对照（D64-conclusive：4c416fb1 完整旧版）证明：
    - 根因 = 测试方法学缺陷——进入会话 auto-scroll 到底后，在**底部边界**测"上滑看更下方"（无内容可滚）→ bounds 零变化被误读为滚动失效
    - CURRENT（含 #40-#43 全部改动）**双向滚动完全正常**：下滑 10 个历史节点滚入、上滑回底部正常，与旧版行为一致 → #41/#42/#43 全部排除
    - 教训已写入 docs/regression-guide.md §3.8：滚动类验证必须**双向测试 + 避开边界**；logcat 抓取须按 PID/tag 过滤（D64 首次 logcat 全为 input 噪音属无效采集）
  - 证据（排查）：docs/research/audit-2026-08-10/D64-investigation.md、D64-bisect.md、D64-conclusive.md + metrics/D64-*

- [x] **#65 Back 退出时 JobCancellationException 被记为 ERROR 级（日志噪声）** `logging`
  - 问题：2026-08-10 #35 复现排查（D35）发现——Back 退出会话取消分页加载时，JobCancellationException 以 ERROR 级写入（52 条/55 轮），属预期异步行为非错误，污染诊断日志（应用内 Diagnostics + logcat）
  - 修复：取消异常（CancellationException 类）统一降级 INFO/DEBUG 或过滤；需确认 catch 点（分页加载协程取消处理）
  - 工时：~0.5h | 难度：低 | 涉及：MessagePaginationDelegate / 日志写入点
  - 证据：docs/research/audit-2026-08-10/D35-investigation.md + metrics/D35-log-*
  - **2026-08-10 完成**：8 文件 35 处 catch + 1 onFailure 统一修复——协程上下文取消异常重新抛出（throw e）、非协程/onFailure 过滤不记录；实测源头 MessageDataDelegate 3 处 + ChatViewModel 7 处等；编译 ✅ 相关单测 ✅；无行为变更

- [x] **#66 其他屏幕同类取消异常日志模式（未触发 Back 路径）** `logging`
  - 问题：2026-08-10 #65 修复时扫描发现——SessionListViewModel(9 处)、ServerSettingsViewModel(10 处)、ServerTerminalWorkspace(11 处)、FileViewerViewModel、WorkspaceViewModel、PtyToTermlibAdapter 存在同类 `catch (e: Exception) { AppLogger.e }` 模式，退出**对应屏幕**时同样会喷取消异常 ERROR（当前场景未触发）
  - 修复：同 #65 模式统一处理（协程上下文 throw、非协程过滤）
  - 工时：~1h | 难度：低 | 涉及：上述 6 文件
  - **2026-08-10 完成**：5 文件 23 处统一修复（SessionListViewModel 7 / ServerSettingsViewModel 10 / ServerTerminalWorkspace 2 / PtyToTermlibAdapter 3 / FileViewerViewModel 1 onFailure）；WorkspaceViewModel 无需修改（onFailure 无 AppLogger.e）；顺带修正 PtyToTermlibAdapter line 187 注释与代码不一致（注释声明取消异常传播但 catch 吞掉 → 按注释意图补 throw）；未动 AppLogger.w 级与无日志 onFailure；编译 ✅ 全量单测 ✅

### 2026-08-11 模拟器实测批次（#56 联动发现）

- [ ] **#72 归档桶内分页缺陷（桶级游标 vs 消息级游标，桶内剩余消息永久读不出）** `data` `performance`
  - 问题：2026-08-11 #56 复测（模拟器，归档 88 条/1 桶 + 热表 30 条）发现——`MessageStore.loadArchivedRange`（MessageStore.kt:269-300）按 `bucketEnd < beforeCreated` 查桶（桶级比较），但游标推进到**消息级** created；第 2 次翻页用消息级 created 查桶 → 桶 bucketEnd > 游标 → 判读尽 → **桶内剩余 58 条永久读不出**（数据证据：翻页只释放了 30/88 条归档）
  - 修复：游标推进到**桶边界**（bucketEnd）而非桶内消息 created；或 loadArchivedRange 支持桶内消息级游标（beforeCreated 内再过滤桶内消息）
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.loadArchivedRange + MessagePaginationDelegate 游标推进（PaginationFSM.Archive）
  - 来源：模拟器实测（#56 复测报告）

- [ ] **#73 首次网络翻页 cursor 格式不兼容（CursorCodec {"id","time"} vs 服务器 {"id","order","direction"}）** `data` `sse`
  - 问题：2026-08-11 #56 复测发现——首次网络翻页（无 serverCursor）回落 CursorCodec 格式，V2 服务器**返回 0 条**（非注释预期的"忽略返回最新"）；服务器 195 条消息中更早的 ~77 条未被加载（热表 30 + 归档 88 = 118，服务器 195 → 差 77 条读不到）
  - 修复：进入会话时保存服务器首次响应的 cursor.next（loadMessagesForSession 的 MessagePage.nextCursor）作为首翻游标；或首次网络翻页不带 cursor（拿最新 30 条 + cursor.next）建立边界后再透传
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationUseCase + MessagePaginationDelegate
  - 来源：模拟器实测（#56 复测报告）

- [ ] **#74 V2 SSE 连接不稳定（Software caused connection abort 反复断连）** `sse` `stability`
  - 问题：2026-08-11 Diagnostics 持久化日志（logs 表）显示 16:21-16:33 期间 3 次 `[TestServer] SSE connection failed: Software caused connection abort` + `SSE stream error`——App SSE 连接反复断连；断连窗口内的 admitted/step 事件丢失 → 用户消息/流式更新延迟（"刷新才显示"的深层关联因素之一）；本次启动（17:03，新 APK）后未复发，但断连重连机制无日志记录断连原因/重连间隔
  - 修复方向：SseConnectionManager 记录断连原因 + 重连间隔日志；区分服务器主动断开（正常）/网络异常；断连期间消息播种兜底（REST 增量）
  - 工时：~0.5d | 难度：中 | 涉及：SseConnectionManager / SseClientV2

- [ ] **#75 V2 session.instructions.updated 解析失败（data 为数组的事件类型解析缺口）** `sse` `compat`
  - 问题：2026-08-11 Diagnostics 日志 5 次 `V2 parse error: session.instructions.updated`（15:42-16:07）——parseV2Event 对 `data` 为数组的事件回退顶层字段路径，但 instructions.updated 顶层只有 metadata（无 type 所需字段）→ 后续解析抛异常被记为 ERROR；同时 `session.created` 解析失败（16:03，Kotlin reflection 序列化异常）
  - 修复方向：instructions.updated 显式处理（metadata 提取或忽略）；session.created 序列化调查（Kotlin reflection 异常——可能与 Json 配置/多态有关）
  - 工时：~0.5d | 难度：低 | 涉及：V2SseMapper / SseClientV2.parseV2Event

- [ ] **#82 跨页跳转 loadAround 后最新消息丢失（UI 与服务器不同步）** `data` `sse`
  - 问题：2026-08-13 跳转定位全面验证（模拟器）发现——发送消息（11:13 hello，服务器端确认存在：`GET /api/session/{id}/message` 最后一条 assistant 回复 11:15，会话 updated=11:13）后执行跨页跳转（Q5 → loadAround older=30 newer=30）→ 跳转完成后滚回列表最新位置，UI 仅显示 10:55 的消息（hello 及回复消失）——客户端内存/数据库窗口与服务器不一致；SSE 连接正常（V2 event 持续收到，含其他会话事件）
  - 影响：跨页跳转（loadAround 重载窗口）后最新消息可能丢失显示——用户看不到刚发的消息/回复（重启应用或重新进入会话可能恢复）；与 #76 冷启动 seed 顺序问题同属"窗口/归并"类
  - **2026-08-13 修复（根因 + 代码完成）**：与 #76 同类——`loadAroundFromLocal` 的 older（`messagesBefore` 查询 `ORDER BY created DESC`——降序）与 newer（ASC）混合后破坏 `mergeSortedMessages` 升序前提（MessageEventHandlerMergeSortedTest 声明的合法输入约束）→ 归并游标错乱 → 内存热视图丢消息。修复：loadAround 两分支（本地/服务器）合并前统一 `sortedBy { it.info.time.created }` 升序化（commit 3cb55ad8）。**验证状态**：assembleDevDebug 编译通过；单测受 replicant 环境 flavor 歧义限制未本地跑；模拟器（无 DISPLAY）无法行为复测——待环境恢复补跑单测 + 模拟器复现（跨页跳转 → 滚到底 → 最新消息在）
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationDelegate（loadAround 两分支）
  - 来源：2026-08-13 跳转定位全面验证（模拟器，dev 最新代码）

- [x] **#76 冷启动 seed 消息顺序降序 vs mergeSortedMessages 升序前提（REST refresh 丢本地独有消息）** `data` `bug`
  - 问题：2026-08-11 synthetic 卡片实测发现——`MessageDao.observeMessages` 返回 `ORDER BY created DESC`（降序），而 `ChatRepositoryImpl.getMessagesFlow` 冷启动 seed 直接喂给 `upsertMessages(APPEND_ONLY)` → `mergeSortedMessages` 两路归并**前提要求升序**（MessageEventHandler.kt:408-410）→ 合并结果乱序/异常；随后 L3 REST refresh（REST_AUTHORITY）再次用降序 existing 归并 → **服务器上不存在的本地独有消息（如本地注入/服务器已删除）被丢弃**（实测：seed 14 条 → REST refresh 后 UI 仅 12 条，2 条注入 synthetic 消失）
  - 影响：低概率但真实——本地缓存与服务器不一致（服务器删除/回滚、本地注入）时消息丢失；日常场景（服务器权威数据）被掩盖
  - 修复：seed 前 `sortedBy { it.time.created }` 升序化（或 MessageDao 提供升序查询）；合并后断言有序
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl.getMessagesFlow（seed 路径）
  - 来源：2026-08-11 synthetic 卡片实测
  - **2026-08-11 完成**：ChatRepositoryImpl.getMessagesFlow seed 前升序化（2e326ff1）；实测数据库 completed 全量持久化、UI 正常

### 2026-08-12 菜单走查批次（fork/share 实测发现）

- [x] **#77 fork 请求 400 被吞 → 空 id 幽灵会话（客户端已修，服务器待上游）** `session` `bug`
  - 问题：2026-08-12 菜单走查（模拟器）发现——点 Fork session 后服务器实际返回 **400 Bad Request**，但 `V2ApiClient.forkSession` 不检查响应状态 → 错误体被 `flexibleObject` 解析为空对象 → `Session.id=""` → 导航进空 id"幽灵会话"，后续操作（Share 等）打到 `/api/session/` 列表端点 → unwrap 崩溃（"Failed to share session"）
  - 服务器侧：fork 端点 `handle("fork")` + `handleRaw("fork")` 同路径注册冲突——curl 实测任何请求方式（JSON `{}` / 空 body / text/plain / multipart）均 400/415（"Missing key at [\"boundary\"]" / "Expected object, got undefined"）
  - 客户端修复（3211e95c 之后补丁）：forkSession 检查 `response.status.isSuccess()`，非 2xx 抛 `IllegalStateException` → UI 显示 "Failed to fork session" Snackbar，不再进入幽灵会话（模拟器验证 PASS）
  - 待办：服务器修复 fork 端点后（handle/handleRaw 冲突），App fork 即可正常；**1.0.0 前应复测 fork 全流程**
  - 工时：~0.5h | 难度：低 | 涉及：V2ApiClient.forkSession

- [x] **#78 V2 下 Share session 永远失败（服务器无 share 端点，UI 提示"Failed to share session"）** `session` `compat`
  - 问题：2026-08-12 菜单走查（模拟器）发现——V2 服务器**无 share 端点**（V2ApiClient.shareSession 注释 no-op getSession），且 `V2SessionMapper.toSession` 不映射 share 字段 → `session.share?.url` 恒为 null → Snackbar "Failed to share session"
  - 修复方向：V2 连接下隐藏 Share 菜单项（需将 apiVersion 传入 ChatTopBar）；或服务器提供 share 功能后适配
  - 工时：~0.5h | 难度：低 | 涉及：ChatTopBar / SessionActionsDelegate
  - **2026-08-12 完成**：V2 下隐藏 Share/Unshare 菜单项——ChatViewModel 暴露 serverApiVersion StateFlow；ChatTopBar 加 isShareSupported 参数包裹 Share 菜单组；ChatScreen 按 `serverApiVersion != ApiVersion.V2` 传参（V1 保留 Share）。注意：运行中的 V2 服务器（旧版）share 端点 404；新版 opencode 源码已有 `POST/DELETE /api/session/:id/share` 端点，且新版 Session.Info **无 share 字段**（分享链接由服务器内部维护）——服务器升级后需重新适配 share 协议再恢复菜单

---

### 2026-08-14 问题模块分支审计批次（question-module-audit-2026-08-14.md）
来源：docs/research/question-module-audit-2026-08-14.md（46 分支：43 ✅ / 1 ❌ / 2 ⚠️，静态审查 + 单测审查）

- [ ] **#125 多选模式下自定义答案提交后无法取消（唯一中等问题）** `ui` `question`
  - 问题：多选（multiple=true）问题中，用户通过输入框提交自定义答案后无法直接取消/删除——③态（行+Edit+✔）无取消按钮；修改态清空输入框后飞机按钮 disabled（`editText.isNotBlank()` 为 false）无法提交空值清除；**间接副作用**：修改态输入已有选项标签（如 "A"）会触发 onOptionClick("C") + onOptionClick("A")，后者因 "A" 已在 selected 中而 toggle off → 选项 A 被意外取消
  - 根因：QuestionPartContent.kt:415-487（③态和修改态均缺"删除自定义答案"入口；修改态提交逻辑 :477-478 先 toggle off 旧值再 toggle on 新值，新值是已有选项时产生副作用）
  - 方案：③态或修改态增加"删除自定义答案"入口（如 ✕ 按钮 → 清除自定义答案 + 若单选恢复空选）；修改态提交前检查新值是否已有选项（避免双 toggle 副作用）
  - 工时：~1h | 难度：中 | 涉及：QuestionPartContent.kt | 优先级：P1
  - 来源：question-module-audit-2026-08-14.md Bug #1（静态审查，E2E 因 agent 超时未实测）

- [ ] **#126 4+ 问题时远页自定义草稿丢失** `ui` `question`
  - 问题：多问题（4+）场景，Q1 输入未提交草稿后翻到 Q4 再翻回 Q1——草稿丢失（customDraft 重置为空）
  - 根因：QuestionPartContent.kt:326——customDraft 用无 key remember，状态绑定页面级 composition；HorizontalPager beyondViewportPageCount=1，距离超 1 页的页面销毁后重新组合
  - 方案：customDraft 按 pageIndex 提升到 QuestionCard 顶层（如 Map<Int, String>）；或增大 beyondViewportPageCount（内存换体验）
  - 工时：~0.5h | 难度：低 | 涉及：QuestionPartContent.kt/QuestionCard.kt | 优先级：P2

- [ ] **#127 单选/多选 toggle 边界保护不对称** `ui` `question`
  - 问题：onOptionClick 单选分支（QuestionCard.kt:171）无 pageIndex 越界保护，多选分支（:174）有 `pageIndex < size` 保护——代码健壮性不一致（实际不触发，pageIndex 来自 pager）
  - 方案：单选分支补同款越界保护
  - 工时：~5min | 难度：低 | 涉及：QuestionCard.kt | 优先级：P2

---

### 2026-08-13 V1/V2 版本探测修复批次（反馈者复现 + 系统性调研）

- [x] **#83 V1 1.18.18 过渡形态被误判 V2 → 会话界面 JSON 解析崩溃（HTML fallback）** `data` `bug`
  - 问题：反馈者 opencode V1 1.18.18（V1/V2 双套端点过渡形态）——`GET /api/health` 返回 200 `{"healthy":true}`（无 version 字段）→ `ApiVersionDetector.tryV2` 只看 healthy → **误判 V2** → App 用 V2ApiClient 请求不存在的 `/api/*` 路径（rename/shell/todo/mcp/config/vcs/project/fork/import 等实测 16+ 端点）→ 服务器 SPA fallback 返回 `<!doctype html>`（HTTP 200）→ `parseToJsonElement` 崩溃：`Unexpected JSON token at offset 11: Expected EOF after parsing, but had h instead`（与反馈者截图完全一致，offset 11 = `<!doctype html>` 的 `h`）
  - 实测证据（本机 1.18.18 隔离运行）：V2 路径正常 JSON 的仅 16 个（session CRUD/message/active/provider/model/agent/command/skill/permission/question/location/fs/pty），**返回 HTML 的 16+ 个**（background/rename/shell/command/children/todo/mcp/config/vcs×3/project×2/service/stop/fork/import）
  - 修复（三层防御）：
    1. **根因**：ApiVersionDetector.tryV2 增加**版本交叉验证**——`ApiVersion.fromVersionString(version) == V2` 才判 V2（1.18.18 version 缺失或 1.x → 回退 tryV1 → `/global/health` 返回 version=1.18.18 → 正确判 V1；V1 路径在 1.18.18 上全部存在，实测通过）
    2. **content-type 防御**：tryV2/tryV1 校验响应 content-type 必须为 JSON（HTML 页面不算健康）
    3. **解析层防御**：V2ApiClient.parseRoot + V2ResponseWrapper.flexibleList/flexibleObject 检测 HTML 特征 → 抛 `NonJsonResponseException`（可读信息 + AppLogger.e），不再裸抛 JsonDecodingException
  - **2026-08-13 补充修复（反向回归）**：真实 V2 服务器版本号为 `0.0.0-next-17403`（npm next 预发布，major=0）→ `fromVersionString` 解析为 V1 → 修复 1 会把真 V2 误判 V1！补充判定规则：**version 解析为 2.x 或响应含 pid 字段（V2 特征，实测必有）→ V2**；version 缺失且无 pid → 过渡形态 → V1。新增测试 2 个（V2 预发布 pid 识别、V2 无 version 有 pid），全量 11 个探测测试通过
  - 测试：ApiVersionDetectorTest +5（版本矛盾/无 version/HTML/content-type/不可解析）；V2MappersTest +6（HTML 防御×2 + flexible 正常×4）；V2ApiClientTest +1（HTML → NonJsonResponseException）——全量 1562 单测通过
  - 工时：~0.5d | 难度：中 | 涉及：ApiVersionDetector、V2ApiClient、V2Mappers、NonJsonResponseException（新建）
  - 来源：反馈者复现 + 本机 1.18.18 隔离实测 + 双 deep-explore 调研
  - **验证状态**：编译 ✅ 单测 ✅；模拟器走查待执行（V1 连接 → 会话界面无报错）

- [ ] **#84 V1/V2 功能差异适配清单（调研产出，需逐项评估）** `compat` `refactor`
  - 问题：深度调研确认 V1(1.18.x) 与 V2(2.x) 是**三重断裂**（路径前缀 / 核心机制 / SSE 格式），客户端需按 apiVersion 区别处理以下功能（详见 docs/v1-v2-differences.md）：
    - **发送消息**：V1 `POST /session/{id}/prompt_async`（204 fire-and-forget）vs V2 `POST /api/session/{id}/prompt`（200 返回 Inbox 条目）——App 已适配 [确认]
    - **中断**：V1 `abort`（boolean）vs V2 `interrupt`（204 + `?continue=true`）——App 已适配 [确认]
    - **后台任务**：V1 仅实验性 `/experimental/session/{id}/background`（需 flag）vs V2 正式 `/api/session/{id}/background`（204）——**V1 下后台化入口应隐藏或降级** [待办]
    - **配置**：V1 `GET/PATCH /config` 可写 vs V2 `GET /api/config` **只读**（无 PATCH）——App 配置编辑在 V2 应禁用 [待办]
    - **Todo**：V1 `GET /session/{id}/todo` vs V2 **移除**（form/question 替代）——V2 下 Todo 入口应隐藏 [待办]
    - **Provider 认证**：V1 oauth authorize/callback 两步 vs V2 integration connect 多步异步——设置页认证流程 [待办]
    - **Revert**：V1 直接 revert/unrevert vs V2 staged（stage/commit/clear）——App 回退功能 [待办]
    - **SSE 格式**：V1 `{id,type,properties}` vs V2 `{id,event,data}`（data 二次 JSON）——App 已适配 [确认]
    - **TUI 控制**：V1 13 个 `/tui/*` 端点 V2 移除——App 无依赖 [确认]
    - **session/status**：V1 `GET /session/status` vs V2 无直接等价（active 替代）——App V2 用 activeSessions [确认]
    - **配置格式**：V1 `config.json` 可读 vs V2 只读 `opencode.json(c)`；mcp 配置 `mcp.{name}` vs `mcp.servers.{name}`；权限模型工具分组 vs 有序数组——服务端侧差异，客户端只读展示 [评估中]
  - 工时：需逐项评估 | 难度：中 | 涉及：多处 UI + API 客户端
  - 来源：2026-08-13 网络 deep-explore（92% 充分度）+ 本地 1.18.18 实测

- [x] **#85 V1 连接下应隐藏/降级的功能 UI（根据 #84 清单落地）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 下任务面板入口/Running/History 隐藏正常；V2 Todo/配置编辑降级确认
  - 问题：#84 调研结论中部分功能在 V1 下不可用/无意义，但当前 UI 未按 apiVersion 区分（参考 #78 已实现的 V2 隐藏 Share 模式）
  - 待落地清单（V1 下）：任务面板入口（V1 无正式后台系统）[评估中]；V2 下：Todo 入口（V2 移除 todo）、配置编辑（V2 只读）
  - 工时：~0.5d | 难度：低 | 涉及：ChatTopBar / 工具栏 / 设置页
  - 来源：#84 调研产出
  - **2026-08-13 完成**：
    1. **Background 菜单 V1 隐藏** ✅——ChatTopBar 新增 `isBackgroundSupported` 参数，Background 菜单项包条件；ChatScreen 传 `serverApiVersion != V1`；模拟器验证：V1 菜单 6 项无 Background、V2 菜单 6 项有 Background、无崩溃
    2. **配置编辑 V2 只读 guard** ✅——ServerSettingsViewModel 新增 `serverApiVersion` 字段（init 读取）；`setProviderEnabled`/`updateConfigPatch` V2 下直接提示失败（实测 V2 PATCH /api/config → 404）；`connectProviderApi`/`completeProviderOauth` 成功后 V2 跳过 disabledProviders PATCH（本地乐观更新，Provider 连接主操作不受影响）
    3. **Todo 无需处理** ✅——补充走查确认 Todo 无独立 UI 入口（SSE 事件驱动渲染，`SseEvent.TodoUpdated`），非用户可触发
  - 单测 1564 全通过；待用户验收

- [x] **#86 V1 连接下抽屉不显示 API 版本号（V2 显示 API v2 · 版本，V1 仅 Connected）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 抽屉显示 API v1 · 1.18.18，观感确认通过
  - 问题：2026-08-13 三轮走查发现——V2 服务器抽屉显示 `API v2 · 0.0.0-next-17403`，V1 服务器仅显示 `Connected` 无版本号。版本检测实际正确（logcat 证实 1.18.18），但用户无法从 UI 直观看到 V1 版本
  - 建议：抽屉中对 V1 也显示 `API v1 · 1.18.18`（数据已有：ServerConfig.serverVersion）
  - 工时：~0.5h | 难度：低 | 涉及：ServerCard/抽屉组件
  - 来源：2026-08-13 补充走查（B7 项观察）
  - **2026-08-13 完成**：ServerCard.kt 移除 `apiVersion != V1` 显示条件，V1/UNKNOWN 均显示版本徽章（颜色沿用 else 分支）；模拟器验证：V1 卡片显示 `API v1 · 1.18.18`、V2 显示 `API v2 · 0.0.0-next-17403`、logcat 判定正确、无崩溃——待用户验收

- [x] **#83 补充验证记录（2026-08-13 三轮模拟器走查全部通过）**
  - V1 走查（旧 APK）：`Detected V1 API (version=1.18.18)`；会话界面无 JSON 报错；发送/接收链路正常
  - V2 走查（新 APK）：`Detected V2 API (version=0.0.0-next-17403, pid 特征识别)`；200 会话/4 项目加载；发送→SSE 回复；Share 菜单隐藏（#78 生效）；无崩溃
  - 补充走查：V1 菜单含 Share（与 V2 隐藏对比成立）、Fork 成功无幽灵会话、重命名生效、新建会话成功、模型列表加载、设置页 logcat 证实 1.18.18、全程零 FATAL
  - 走查清单：docs/simulator-walkthrough-v1v2.md（执行记录已填）

- [x] **#87 V1 长会话压测发现：/message 轮询 JsonConvertException ×302（非致命）+ 回复偶发重复渲染** `data` `sse`
  - **2026-08-13 模拟器复验 ✅（Agent 代测）**：长会话无 JsonConvertException、无重复渲染（每条消息单气泡）；附注：listMessages 打开会话 2 秒内冗余调用 ~7 次 + V2 分页 before 游标返回 400 后回退重头拉取（不崩溃、浪费网络）→ 登记 #91
  - 问题：2026-08-13 V1 长会话 40 条消息压测（全部通过、零崩溃）发现两个非阻塞观察项：
    1. **JsonConvertException ×302（已修复）**：logcat 显示 App 以 **5 秒周期轮询** `GET /session/ses_0051ddbbdffed3UmOqzX8SamAC/message?limit=50`（该会话为压测 subagent 的服务器会话，**不存在于本地 V1 1.18.18 服务器**）→ 404 → 错误体 `{"name":"NotFoundError",...}`（对象）被按 `List` 解析 → JsonConvertException。根因：`V1ApiClient.listMessages`/`V2ApiClient.listMessages` **无状态码检查**（404 错误体直接当数组解析）。**修复（2026-08-13）**：两处 listMessages 非 2xx 返回空页 + AppLogger.w；新增 V1ApiClientTest 3 个（404/5xx/正常）；L2 stale 轮询源为压测环境外部会话（已删除会话的遗留轮询，非 App 常规路径）
    2. **回复内容偶发重复渲染（已修复）**：部分回复出现重复文本（如 "Got it. Message 1 received.Got it. Message 1 received."）——根因：**REST 快照 text part `id=""` vs SSE part `id="prt_xxx"`**（part ID 契约差异）→ `handleMessagePartUpdated` 按 id 找不到 → 新增第二条 part → 同消息两条文本 part。**修复（2026-08-13）**：空 id 的 Text part 按**内容级匹配**（相等/前缀）合并而非新增；新增 MessageEventHandlerTest 3 个（内容合并/更长替换/内容不同仍新增）
  - 验证：单测 1575 全通过；模拟器复测待执行（长会话重复渲染观察 + logcat 无 JsonConvertException）
  - 工时：~0.5d | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、MessageEventHandler

- [x] **#88 目录浏览性能：每次导航 >500ms（V1/V2）+ V2 大目录 53 秒 ANR** `perf` `data`
  - **2026-08-13 用户验收 ✅**：目录浏览流畅（缓存秒开），.opencode ANR 消除（234ms），性能复测全通过
  - 问题：2026-08-13 用户反馈"各类目录点击卡卡的"→ 性能测试确认：OpenProjectDialog 目录浏览**每次前进导航 >500ms**（V1 一致 SLOW 506-763ms；V2 537-799ms + 极端 .opencode 目录 53 秒 ANR"not responding"）。会话列表目录树 toggle 正常（<50ms）
  - 根因（两处）：
    1. **ANR**：`FileRepositoryImpl.listDirectory` 无 `withContext(IO)`，OpenProjectDialog 的 LaunchedEffect 在 Main 调度器 → V2 大目录（node_modules）的 JSON decode + map 在主线程 → 阻塞 → ANR
    2. **500ms 感知延迟**：每次目录导航无缓存，模拟器→宿主机网络往返 ~500ms 固有延迟（items 0-1 个也 >500ms）
  - 修复（2026-08-13）：
    1. FileRepositoryImpl.listDirectory 包 `withContext(Dispatchers.IO)`（网络+解析移出主线程）
    2. DirectoryManager 增加 **30s 目录列表缓存**（ConcurrentHashMap：路径→{items, at}）——已浏览目录返回/重复浏览秒开（CACHE HIT <100ms）
    3. 保留性能监控日志（listDirectories >500ms warn、buildTreeNodes >50ms warn、CACHE HIT debug）
  - 验证：单测 1575 全通过；模拟器复测待执行（V1/V2 缓存命中 + .opencode ANR 消除）
  - 工时：~0.5d | 难度：中 | 涉及：FileRepositoryImpl、DirectoryManager、OpenProjectDialog 链路
  - 来源：2026-08-13 用户反馈 + 性能测试（V1/V2 全量数据）

- [x] **#89 内存泄漏修复批次：Singleton keyed 状态会话切换后不清理** `data` `refactor`
  - **2026-08-13 确认完成 ✅（Agent 代确认，用户授权）**：①目录窗口 30 轮开关内存增长减速趋平（5.3→4.1MB/10轮，GC 回收 14MB）；②缓存 LRU 生效（CACHE HIT 39/fetch 15）；③会话退出清理链路 logcat 铁证（releaseSessionData + clearForSession 精确清理 50/90 条）；④1575 单测全通过
  - 问题：2026-08-13 用户反馈模拟器长时间运行后系统卡死（宿主机 swap 15Gi 满）→ 排查发现 App 内多处 **@Singleton 持有按 sessionId/serverId keyed 的可变集合**，正常切换会话（非 SessionDeleted/SSE 断开）不触发清理 → 数据永驻内存：
    1. **DirectoryManager.dirCache**（目录浏览缓存）：只 put 不清理，浏览大量目录（含 node_modules 大列表）条目永驻 → 已修：上限 200 + 过期清理（近似 LRU）
    2. **MessageEventHandler._messages/_parts**（按 sessionId）：ChatViewModel.onCleared 不清理 → 已修：EventDispatcher.releaseSessionData + ChatViewModel.onCleared 调用
    3. **SessionEventHandler._sessionDiffs/_lastUserMessageTime**：无 clearForSession → 已补
    4. **ShellJobsStore._jobsBySession**：有 clearForSession 但无调用点 → 已接入 releaseSessionData（经 ShellJobsHandler 委托）
    5. **StreamingOwnershipRegistry.owners**：仅 SessionDeleted 释放 → 已接入 releaseSessionData
    6. **AppNotificationManager 去重缓存 ×3**（(server, session) keyed）：仅断开/用户取消通知清理 → 已补 clearForSession（ChatViewModel.onCleared 直调，避免 EventDispatcher↔AppNotificationManager Dagger 循环）
    7. **SessionEventHandler.locallyClearedReverts**：已补 clearForSession 清理（防御）
    8. **ChatRepositoryImpl.toolExpandedStates**（toolId keyed，仅 UI 展开状态）→ 登记低优先级（#90）
  - 修复（2026-08-13）：
    - EventDispatcher 新增 `releaseSessionData(sessionId)`：级联清理 sessionHandler/messageHandler/permissionHandler/questionHandler/miscHandler/sessionNextHandler/sessionStateService/ownershipRegistry/shellJobsHandler
    - ChatViewModel.onCleared 调用 releaseSessionData（runCatching 防异常）
    - SessionEventHandler 新增 clearForSession；ShellJobsHandler 新增 clearForSession 委托
    - DirectoryManager.dirCache 上限 200 + 过期清理
    - 测试构造更新：5 个 ChatViewModel 测试加 mockk eventDispatcher
  - 验证：编译 ✅ 单测 1575 全通过 ✅；模拟器长时间运行内存曲线待测（dumpsys meminfo）
  - 工时：~0.5d | 难度：中 | 涉及：EventDispatcher、ChatViewModel、SessionEventHandler、ShellJobsHandler、DirectoryManager
  - 来源：2026-08-13 用户反馈系统卡死 + 全局 Singleton keyed 状态扫描

- [ ] **#90 ChatRepositoryImpl.toolExpandedStates 无上限（低优先级）** `refactor`
  - 问题：2026-08-13 全局 keyed 状态扫描发现——`ChatRepositoryImpl.toolExpandedStates`（ConcurrentHashMap<toolId, Boolean>）只增不减（工具卡片展开状态记忆），toolId 随消息/工具调用增长 → 长期使用后无界
  - 影响：低（单条 Boolean 值，千条工具调用才 KB 级）；且 UI 展开状态跨会话记忆有产品价值
  - 方案：定期清理已结束消息的 toolId（需按消息关联）或 LRU 上限（如 1000 条）
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl
  - 来源：2026-08-13 全局 Singleton keyed 状态扫描（#89 附属）

- [ ] **#91 listMessages 冗余调用 + V2 分页游标 400（#87 复验附注）** `data` `performance`
  - 问题：2026-08-13 #87 模拟器复验发现——打开会话后 2 秒内 listMessages 冗余调用 ~7 次；V2 分页 `before=eyJp...` 游标返回 400 Bad Request 后回退重头拉取。不崩溃但浪费网络（长会话/慢网络下明显）
  - 关联：可能与本条目 #73（V2 cursor 格式 {"id","order","direction"} vs 本地 CursorCodec {"id","time"}）同源——需先核对游标编解码
  - 工时：~1-2h | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、分页管线
  - 来源：2026-08-13 综合验收（#87 复验附注）

- [ ] **#92 session.tool.progress 事件未处理（工具实时进度缺口）** `sse` `ui`
  - 问题：2026-08-13 #71 数据正确性确认发现——日志反复 `W SessionNextEventHandler: Unhandled session.next event: session.tool.progress`；shell 生命周期（created/exited/deleted）与内联展示数据正确，但工具实时进度事件被忽略 → Tasks 面板无法显示进行中工具进度
  - 影响：中（工具调用长任务时用户看不到实时进度；任务完成仍正常显示）
  - 方案：SessionNextEventHandler 处理 tool.progress 事件 → 进度流接入 Task 面板/消息内联展示
  - 工时：~2h | 难度：中 | 涉及：SessionNextEventHandler、TaskDelegate/TaskSheet
  - 来源：2026-08-13 综合验收（#71 附注）

- [x] **#93 WebView 销毁三件套（C-1+H-1+H-2，审计 Critical+High 泄漏）** `crash` `leak`
  - 来源：docs/research/audit-2026-08-13-memory-perf/REPORT.md §4.1-4.2（基线 3bdd7990，2026-08-13 静态审计）
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：WebViewScreen 加 DisposableEffect onDispose 完整销毁（stopLoading→about:blank→clearHistory→removeView→destroy）；ErrorPayloadContent 加 AndroidView onRelease（滚出视口即销毁）；RenderWebView 加 DisposableEffect 销毁 + lastHtml/lastJsCommand 去重（消除无条件整文档重载）。grep 验证三处销毁齐全 ✅
  - 问题（✅ 2026-08-13 Agent 代码验证确认）：
    1. `ui/screens/webview/WebViewScreen.kt:149-292` 全屏 WebView **从不 destroy()**——无 onRelease/DisposableEffect，每次进出导航累积一个渲染进程（10-100MB）+ Activity 引用；Basic Auth 明文凭据随闭包驻留（91-99 行）
    2. `ui/screens/chat/components/ErrorPayloadContent.kt:79-101` HTML 错误气泡 WebView **无 onRelease**——滚出 LazyColumn 视口不销毁
    3. `ui/screens/viewer/RenderWebView.kt:55-99` 渲染面板 WebView **永不销毁**——切 SOURCE↔RENDER 反复累积
  - 对比：同项目 CodeWebView.kt:202-215 / PdfViewer.kt:83-94 均有完整销毁序列，此三处是遗漏
  - 方案：`AndroidView(onRelease = { wv -> wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() })` 或 DisposableEffect 销毁（照抄 CodeWebView 模式）；考虑 LeakCanary 集成（debug）
  - 工时：~0.5d | 难度：低 | 涉及：WebViewScreen/ErrorPayloadContent/RenderWebView
  - 优先级：**P0**（每次操作累积，OOM/LMK 风险）

- [x] **#94 图片解码降采样（H-3+M-9，审计 High/Medium 性能）** `performance` `crash`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-3 + §4.3 M-9
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：ImagePreviewDialog 加 inJustDecodeBounds + inSampleSize 降采样（缩略图 256px ~750KB / 预览 2048px ~12MB）；MediaUtils 压缩前降采样解码 + JPEG RGB_565（省 50%）+ token 估算用原始尺寸保证准确。grep 验证降采样齐全 ✅
  - 问题（✅ Agent 代码验证确认）：
    - `ImagePreviewDialog.kt:64-75,110-113` 主线程 `BitmapFactory.decodeByteArray` **全分辨率解码**（4000×3000 ≈ 48MB）只为 80dp 缩略图——滚入视口即掉帧/ANR；多图瞬时数百 MB → OOM
    - `MediaUtils.kt:174-211` 发送压缩前同样全分辨率解码（无 inSampleSize 预降采样）；非压缩路径原始字节 base64 dataUrl 常驻（1.33× 膨胀）
  - 方案：inJustDecodeBounds → 按目标尺寸算 inSampleSize → 再解码；inPreferredConfig=RGB_565；解码移 Dispatchers.IO；或改用 Coil3 AsyncImage（项目已引入）
  - 工时：~0.5d | 难度：低 | 涉及：ImagePreviewDialog/MediaUtils
  - 优先级：**P0**

- [ ] **#95 消息热视图活跃会话无上限（H-4，审计 High 泄漏——#89 增量）** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-4
  - ✅ **2026-08-13 代码验证确认**：清理链路已修（#89），活跃会话热视图无 LRU 仍存在（Agent 复核）
  - 问题（✅ 部分确认）：MessageEventHandler `_messages/_parts`（@Singleton）清理链路已在 #89 修复（onCleared/SessionDeleted 清理 + clearForServer 已清 assistantMessageIds）✅；但**活跃会话期间热视图无 LRU/上限**——Room 侧有 1000 条/会话上限，内存侧没有；重连时 recoverMessages 为所有活跃会话批量拉消息；长会话单条消息（工具输出/大 diff）可达 MB 级
  - 方案：① 内存侧按会话保留最近 N 条（与 Room 1000 对齐）；② 单 Part 文本长度上限（如 512KB）截断/懒加载
  - 工时：~1d | 难度：中 | 涉及：MessageEventHandler.kt:42-58
  - 优先级：P1（长期运行 + 多活跃会话可达数百 MB）

- [ ] **#96 SessionDeleted 漏清 _lastUserMessageTime/locallyClearedReverts（L-2，审计确认——#89 漏网）** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4 L-2
  - ✅ **2026-08-13 代码验证确认**：handleSessionDeleted:119-123 仅清 _sessions/_sessionDiffs（Agent 复核）
  - 问题（✅ **2026-08-13 Agent 代码验证确认**）：`SessionEventHandler.handleSessionDeleted`（:119-123）只清 `_sessions/_sessionDiffs`，**漏清 `_lastUserMessageTime` 与 `locallyClearedReverts`**——#89 修复的 clearForSession 只在 onCleared 调用，**服务器端 SessionDeleted 事件路径未接入** → 删除会话后条目残留
  - 方案：handleSessionDeleted 内补 `_lastUserMessageTime.update { it - sessionId }` + `locallyClearedReverts.remove(sessionId)`（或直接调 clearForSession）
  - 工时：~0.5h | 难度：低 | 涉及：SessionEventHandler.kt:119-123
  - 优先级：P1（#89 验收后发现的补漏）

- [ ] **#97 SSE 热路径优化批次（H-5+H-6+M-6+M-15，审计 High/Medium 性能）** `performance` `sse`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-5/H-6 + §4.3 M-6/M-15
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-5 三子项全确认（SseClient:44-51 逐字节装箱 / SessionNextEventParser:34-35 多遍 / SseClientV2:171,181 双重转换）；H-6 全量重写确认（MessageEventHandler:235-240 + MessageStore:69）；M-6 prettyPrint 确认（NetworkModule:34 且被 MessageStore 共用）；M-15 O(N×M) 确认（:147 Map.plus 每 delta 拷贝）
  - 问题（✅ 部分确认）：
    1. **H-5 解析层分配风暴**：`SseClient.kt:42-72` readRawLineBytes 逐字节装箱 + `SessionNextEventParser.kt:34-35` V1 树→toString→decodeFromString 三遍 + `SseClientV2.kt:171-181` 双重 ByteArray 转换——流式 20-60 事件/s 持续制造 KB-MB 垃圾
    2. **H-6 双写写放大**：flush 后对整条增长中消息全量 JSON 编码 + Room 全行重写（~20 次/s）——**#52 2026-08-11 已评估"频率不可降、无进一步收益"，但 H-6 是新角度：单次写入量（全量重写）+ prettyPrint 放大 + trySend 静默丢写（N-1）**——需增量写（append delta）或节流合并（500ms/1s）
    3. **M-6 prettyPrint=true**（✅ NetworkModule.kt:34 确认）：全局 Json 带缩进——所有序列化 +30-50% 体积与编码 CPU，与 H-6 叠加
    4. **M-15 flushPendingDeltas O(N×M)**：批内每 delta 整份 Map 拷贝（`updated + (messageId to ...)`）——单次 toMutableMap 可消除
  - 方案：增长型 ByteArray 分块读；decodeFromJsonElement 单遍解析；双写增量/节流；prettyPrint=false；M-15 单次拷贝
  - 工时：2-3d | 难度：中-高 | 涉及：SseClient/SseClientV2/SessionNextEventParser/MessageEventHandler/NetworkModule
  - 优先级：P1（流式体验卡顿主要嫌疑）

- [ ] **#98 无界容器治理批次 2（H-7+M-1+M-7+M-13，审计 High/Medium 泄漏）** `leak` `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-7 + §4.3 M-1/M-7/M-13
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-7 ToolSnapshotCache:23 无上限无 TTL；M-1 pendingInputs:77 无 clear 且仅 promoted 消费；M-7 mdRegistry:395/RenderReadiness:63 无 remove（grep 0 匹配）；M-13 dirCache:43 无 LRU + loadJobs:44 无 finally remove
  - 问题（✅ Agent 代码验证确认，全部无上限/LRU/TTL）：
    1. **H-7 ToolSnapshotCache**（domain/repository/ToolSnapshotCache.kt:23）：ConcurrentHashMap 无界，写入（ChatViewModel put）与清理（FileViewerViewModel.onCleared）生命周期分离——导航取消/失败条目（含整文件内容数 MB）永驻
    2. **M-1 SseClientV2.pendingInputs**（:77,296,300）：HashMap 无界，仅 promoted 时消费；admitted 后断连丢失 → 条目永驻
    3. **M-7 mdRegistry/RenderReadinessRegistry**（ChatMessageList.kt:129,395 / RenderReadiness.kt:63-67）：组合级注册表无 remove——滚出视口条目保留 MarkdownState（AST 为原文数倍）
    4. **M-13 WorkspaceViewModel dirCache/loadJobs**（:43-44）：dirCache 无 LRU（仅 refreshRoot 清）；loadJobs 完成 Job 引用永不清理
  - 方案：参照 DirectoryManager.dirCache 200 条 LRU 标杆统一治理；mdRegistry 加 DisposableEffect onDispose remove
  - 工时：~2d | 难度：中 | 涉及：ToolSnapshotCache/SseClientV2/ChatMessageList/RenderReadiness/WorkspaceViewModel
  - 优先级：P1

- [ ] **#99 TaskDelegate 每 5s 无条件轮询（M-10，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-10
  - ✅ **2026-08-13 代码验证确认**：TaskDelegate:88-90 while(true) delay(5_000)（Agent 复核）
  - 问题（✅ Agent 代码验证确认）：`TaskDelegate.kt:88-93` while(true) { refreshActiveSessions(); delay(5_000) }——ChatScreen 打开期间即使完全空闲也每 5s 一次 HTTP `/api/session/active`（12 次网络唤醒/分钟）
  - 方案：空闲降频（无子会话且全 idle 退避 30s+）；V1 走 SSE 事件驱动，仅 V2 轮询兜底
  - 工时：~0.5d | 难度：低 | 涉及：TaskDelegate.kt:84-93
  - 优先级：P2

- [ ] **#100 SessionListViewModel 主线程全量状态重建 + 搜索无防抖（M-11，审计 Medium 性能）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-11
  - ✅ **2026-08-13 代码验证确认**：combine:350 无 flowOn；上游 5 Flow 无 distinctUntilChanged；搜索逐键 loadSessions 网络重取（Agent 复核）
  - 问题：combine 在主线程 buildContentState（过滤+排序+搜索+分类+树构建+未读判定全量）；上游 6 源无 distinctUntilChanged；搜索逐键全量网络重取
  - 方案：上游 distinctUntilChanged；_searchQuery.debounce(300)；buildContentState 移 Dispatchers.Default；搜索改纯客户端过滤
  - 工时：~1d | 难度：中 | 涉及：SessionListViewModel/SessionListStateBuilder
  - 优先级：P2

- [ ] **#101 FileViewer/RenderWebView 性能批次（M-12+M-14，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-12/M-14
  - ✅ **2026-08-13 代码验证确认**：FileViewerViewModel:45,167-178 整文件驻留 + 逐字符重扫 + AnnotationManager:17 额外拷贝 + PDF Base64；RenderWebView:91-98 update 无条件重载无 last* 比较（Agent 复核）
  - 问题：FileViewerViewModel 大文件整读多份拷贝 + 分页 O(k·n) 逐字符重扫（20 万行翻 10 页 = 10 次全扫）+ \r\n 归一化拷贝 + PDF Base64 整段塞 JS；RenderWebView update 每次重组无条件 loadDataWithBaseURL 整文档重载（丢滚动位置/图片重解码）
  - 方案：lineOffsets 索引切片；remember 比较"上次已应用"值跳过
  - 工时：~1d | 难度：中 | 涉及：FileViewerViewModel/AnnotationManager/RenderWebView
  - 优先级：P2

- [ ] **#102 日志系统性能批次（M-2+M-3+M-4，审计 Medium 性能）** `performance` `logging`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-2/M-3/M-4
  - ✅ **2026-08-13 代码验证确认**：M-2 DebugLogger:33 无界 StringBuilder + reset 0 调用 + 同步全量写 + 无线程同步；M-3 sanitize:155-171 内联 10 Regex + recordBatch 每批 refresh 1000 条；M-4 **部分确认**：rawJson 副本存在（V2EventParser:114-118），但日志为 AppLogger.d（DEBUG-only）非报告所称 WARN——影响降级（Agent 复核）
  - 问题：DebugLogger 无界 StringBuilder + 主线程同步全量写文件 + O(n²) 累计 I/O + 无线程同步（WebView JavaBridge 并发）；DiagnosticLogRepository.sanitize 每字段新建 ~10 Regex + 每批全量 refresh；V2 未识别事件每事件构造整 JSON 副本 + WARN 持久化（叠加 M-3）
  - 方案：append 增量写 + 锁 + 512KB 限容；Regex companion 预编译 + refresh 1s debounce；rawJson 截断/降级 DEBUG
  - 工时：~1d | 难度：中 | 涉及：DebugLogger/DiagnosticLogRepository/V2EventParser
  - 优先级：P2

- [ ] **#103 审计 Medium 其余（M-5+M-8+M-16）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3
  - ✅ **2026-08-13 代码验证确认**：M-5 ChatRepositoryImpl:79-92 sortedBy+upsertMessages 在 IO 块外（Main）；M-8 ChatMessageList:769-770 "t_head" key 确认；M-16 WorkspaceScreen:138-142 组合体直接 filter + VM filterGitChanges 无调用方（Agent 复核）
  - M-5：ChatRepositoryImpl.getMessagesFlow 种子合并在主线程（sortedBy+upsertMessages 移入 withContext(Default)）
  - M-8：ChatMessageList 最新 turn 的 LazyColumn key 不稳定（"t_head"）——每轮边界整气泡销毁重建（含 rememberMarkdownState 重解析）→ key 改 turn 组首条消息 id
  - M-16：WorkspaceScreen git 过滤每次重组全量执行（无 remember/derivedStateOf；与 VM 逻辑重复）
  - 工时：~0.5d | 难度：低 | 涉及：ChatRepositoryImpl/ChatMessageList/WorkspaceScreen
  - 优先级：P2

- [ ] **#104 审计 Low 批量（L-3~L-18，审计 Low——除 L-1=#90、L-2=#96）** `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：L-3 persistJob?.cancel 模式（:101）；L-4 V1:331-334/V2:846-849 新建 client；L-5 getParts flatten + 生产 0 调用方；L-6 PdfViewer:120 addInterface 无 remove（对比 CodeWebView:207 有）；L-7 :126 onValueChange 内 Regex；L-8 :105-109 4s 永久轮换；L-9 :203 无 remember；L-10 :68-74 delay(100)；L-11 :310 timestamp_index key；L-12 FileTreeUtils:22-31 + 递归拼接；L-13 DiffView:119 现场 Regex；L-14 NavGraph:424-429 整文件下载判非空；L-15 :60-68 无 remember + :143 forEach 非虚拟化；L-16 :154-189 无 TTL/去重；L-17 :37 只增不减无 sweep；L-18 ChatViewModel:428-458 主线程全量扫描无 distinctUntilChanged
  - L-3 UnreadBadgeService.persistAsync 每次取消上一个写 → 改合并写（Mutex/Channel 单消费者）
  - L-4 exportSessionToStream 每次新建 OkHttpClient（线程池/连接池泄漏）→ 复用共享 client
  - L-5 ChatRepositoryImpl.getParts 全量 flatten（当前无调用方）→ 接入前改索引或删除
  - L-6 PdfViewer JS 桥未 removeJavascriptInterface（CodeWebView 有）
  - L-7 ChatScreenBottomBar 每按键编译新 Regex → companion 预编译
  - L-8 ChatInputBar 占位符 4s 永久轮换 → 仅焦点+空文本时轮换
  - L-9 ChatMessageList getActiveToolProgressForSession 每次重组新建 Flow → remember 提升
  - L-10 ReasoningBlock 100ms ticker 常驻重组 → 降 1000ms
  - L-11 DiagnosticsScreen key 用 timestamp_index 拼接 → 队列头淘汰全 key 失效 → 内容派生稳定键
  - L-12 FileTreeUtils.flattenTree 用 + 递归拼接 O(n²) → buildList 累积
  - L-13 DiffView 每候选行现场编译正则 → companion 预编译
  - L-14 NavGraph.checkFileExists 整文件下载只为判非空 → HEAD/大小
  - L-15 ServerModelFilterScreen 过滤无 remember + 组内非虚拟化渲染
  - L-16 HomeViewModel 连接状态变化重启全部 providers 网络检查 → 进行中去重 + TTL
  - L-17 UnreadBadgeService._lastCompletedReplyTime 只增不减无 sweep → 复用 staleness 循环清理
  - L-18 ChatViewModel token 统计主线程全量扫描（2000 条×20 次/s）→ map 派生 + distinctUntilChanged
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3（顺手修复）

- [ ] **#105 审计备注批量（N-1~N-15 重点项）** `refactor` `security`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.5
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：N-1 trySend 返回值未检查（:240）；N-2 rawSseEvents 全工程仅 3 匹配零订阅；N-3 JumpBubbleObserve settled 0 读写；N-4 ScrollCompensation:50 反射（有 try-catch 降级）；N-5 WebViewScreen:91-92 闭包捕获明文凭据；N-6 CodeSourceView 2 match 无调用方；N-7 TerminalDelegate:121-123 空实现；N-9 cancelScope 0 调用；N-12 SessionTreeList:56-67 key 不变不续载；N-14 MainActivity:79 replay=1；N-15 OpenCodeApp:57 双 scope 并存。**路径修正**：N-10 QuestionParser 实际在 ui/screens/chat/util/（非 data/repository/parser/）。**N-11 修正**：SessionActionsDelegate:323,339 与 MessagePaginationDelegate:248 共 3 处 AppLogger.d 无 BuildConfig.DEBUG 门控（AppLogger.shouldPersist 层面阻止 DB 写入，影响低）
  - N-1（数据一致性）：persistQueue trySend 满时静默丢写 → 失败计数/降级
  - N-4（维护风险）：ScrollCompensation 反射访问 Compose 私有 API → BOM 升级前必须验证
  - N-5（安全）：WebViewScreen Basic Auth 明文凭据闭包驻留（叠加 #93）
  - N-2/N-3/N-6/N-9（死代码）：rawSseEvents 无订阅者、JumpBubbleObserve、CodeSourceView 无调用方、cancelScope → 清理
  - N-7：TerminalDelegate.closeTerminalSession 空实现（设计取舍，评估）
  - N-12（功能缺陷）：SessionTreeList 分页加载完成停靠底部不自动续载
  - N-14（功能隐患）：_deepLinkFlow replay=1 配置变更后重放旧 deep-link
  - N-15（架构）：OpenCodeApp 自建 appScope 与 DI @ApplicationScope 双套并存 → 统一
  - N-8/N-10/N-11/N-13（报告判定"可接受/可忽略"，仅记录备查）：SettingsViewModel 22 个 Eagerly 映射（单字段提取开销极小）；SyntheticNotificationCard/QuestionParser Regex 未预编译（低频）；SessionActionsDelegate 等 Debug 日志较多（已 DEBUG 门控，Release 无影响）；SessionRow 每行 remember SimpleDateFormat（可接受）
  - 工时：~1d | 难度：低-中 | 优先级：P3

- [ ] **#107 V2 交互式提问链路不通（question 工具调用后无 SSE 事件、REST 空）** `sse` `compat`
  - 问题：2026-08-13 构造提问验收场景时发现（Agent 实测）——V2 服务器（0.0.0-next-17403）上 agent 成功调用 question 工具（含单选+多选两个问题，state=running），但 V2 **既不发出 question.asked SSE 事件**，`GET /api/question/request` 也返回空；App 每 30s 轮询均无果，仅显示工具调用头 "Question"。V1（1.18.18）完全正常（GET /question 正确返回待处理问题）
  - 影响：V2 连接下用户无法看到/回答 agent 提问（功能缺失；问题仍可完成但交互退化）
  - 关联：#70（V2 事件体系未确认项）——question 事件流可能是 V2 未实现/改名的部分；新增 A（提问通知 REST 兜底）评估时需考虑 V2 差异
  - 方案：调研 V2 的 question 机制（事件名/端点），按 v1-v2-differences 文档补充适配；或确认 V2 设计如此（问题直接内联）则调整 UI
  - 工时：~2-4h | 难度：中 | 涉及：V2SseMapper/SseClientV2/QuestionEventHandler
  - 优先级：P1（V2 用户提问交互缺失）

- [ ] **#106 工具链治理建议（审计 §7，未含具体代码问题）** `tooling`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §7
  - 1. **LeakCanary**（debug 构建）：项目当前无任何泄漏检测工具——3 处 WebView 泄漏（#93）正是其首捕类型；集成成本 <1h（debugImplementation）
  - 2. **StrictMode**（debug）：自动捕获主线程 IO（#102 M-2 / #103 M-5 类）与未关闭资源
  - 3. **Baseline Profile**：聊天列表/文件查看器滚动重负载，可显著降低首滚 jank（配合 #103 M-8 / #101 M-12）
  - 4. **Regex 预编译规范**：全库 5 处现场编译 Regex（#104 L-7/L-13、#102 M-3、N-10）→ lint/评审规则统一 companion/顶层常量
  - 5. **内存上限规范化**：DirectoryManager.dirCache 200 条 LRU 已是标杆（#89），同类容器（#98 各条目、#90）按此模式治理
  - 6. **CI 门禁**：Android Lint 已默认启用但未配置 failOnError；Compose compiler 稳定性报告（-P composeCompilerReports）防新引入 unstable 参数
  - 工时：~1d | 难度：低 | 优先级：P3

### 2026-08-14 跨维度审计批次（audit-2026-08-13-dimensions/REPORT.md，113 条）

- [x] **#108 SSE 心跳机制缺陷批次（D2-03 阻塞读挂死 + D2-05 V1 心跳不一致）** `sse` `network`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-03/D2-05（B 路 + 主代理双源）
  - **2026-08-14 修复完成**：① 两客户端阻塞读套 `withTimeoutOrNull(40s)` 超时防护（SseClient 两处 + SseClientV2 帧级）——半开 TCP（kill -9/NAT 静默断）下 40s 无数据强制断开走重连，不再永久挂死；② V1 心跳与 V2 对齐（任意行/事件到达即刷新 lastHeartbeat，不再仅 ServerHeartbeat）——V1 长流式不再 40s 假超时断连；③ 测试驱动发现真实缺陷：对端 FIN 关闭时 readByte 抛 EOFException（非 ClosedReadChannelException）→ 正常 EOF 被当异常 → 补捕获（readRawLineBytesWithTimeout 辅助函数 +4 测试）；④ 模拟器实测：V2 SSE 连接建立 + 事件流正常 + kill 服务器后重连链路可用。单测 1587 全通过
  - 问题：① 两客户端 socketTimeout=Long.MAX_VALUE + 心跳检查仅在行间 → 半开 TCP（kill -9/NAT 静默断）连接永久挂死，重连/冷却失效；② V1 心跳只在 ServerHeartbeat 事件刷新（V2 已改任意事件刷新）→ V1 服务器长流式 40s 假超时断连
  - 方案：读循环套 withTimeoutOrNull(40s)；V1 心跳与 V2 对齐（任意事件/空帧刷新）；加日志观测命中率
  - 工时：~0.5d | 难度：低-中 | 涉及：SseClient/SseClientV2/SseConnectionManager | 优先级：P0

- [ ] **#109 V2 REST/SSE part id 契约错位（D2-01，可能双份渲染）** `compat` `sse`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-01（A 路，主代理回读确认）
  - 问题：V2Mappers 空 part id（id=""）与 SSE derivePartId（msg_ord_N）契约不一致 → mergePartsList preserved 双份保留 → 已完结消息文本双份渲染
  - 方案：V2Mappers 统一 derivePartId；或 mergePartsList 空 id 内容匹配合并；先模拟器实测复现
  - 工时：~0.5-1d | 难度：中 | 涉及：V2Mappers/MessageEventHandler | 优先级：P0（先实测）

- [ ] **#110 多服务器共享状态批次（D2-02/D2-11/D2-12/D2-13/D2-24）** `race` `multi-server`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：pendingInputs HashMap 跨服务器并发（D2-02）；状态容器 sessionId 单键无 serverId 维度（D2-11）；currentServerId 单值被覆盖 → L3 校验打错服务器（D2-12）；isConnected 语义 = job 活跃非连接（D2-13）；McpRepositoryImpl 共享 connection（D2-24）
  - 方案：ConcurrentHashMap/按 serverId 隔离；复合键 (serverId, sessionId)；去掉 currentServerId 单值；isConnected 返回真实标志
  - 工时：~1-2d | 难度：中 | 涉及：SseClientV2/各 handler/SessionStateService/SseConnectionManager/McpRepositoryImpl | 优先级：P1

- [x] **#111 dataSync 前台服务 6h 时限（D2-04，Android 15+）** `android` `service`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-04（B 路）
  - **2026-08-14 修复完成**：OpenCodeConnectionService 覆盖 `onTimeout(startId, fgsType)`——可观测日志（时限 + 当前活跃服务器）→ super 默认 stopSelf → 有活跃连接时延迟 2s 重启服务（新 6h 周期），已配置自动连接的服务器由 onCreate → autoConnectConfiguredServers 自动恢复。权限齐全（FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC）。编译 + 单测通过；6h 时限无法加速验证，需真机长时间运行确认（可观测日志 "FGS dataSync timeout"）
  - 问题：targetSdk 36 + foregroundServiceType=dataSync + 0 处 onTimeout → Android 15+ 每 6h 系统终止服务，手动连接静默丢失
  - 方案：覆盖 onTimeout（快速重连/通知用户）；评估 FGS 类型；纳入可观测性日志；真机验证
  - 工时：~0.5d | 难度：低 | 涉及：OpenCodeConnectionService/Manifest | 优先级：P0

- [ ] **#112 通知链路竞态批次（D2-14 mark-before-show + D2-18 轮询门控 + D2-L30 250ms 启发式）** `notification`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-14/D2-18/D2-L30（B 路）
  - 问题：任务完成通知先标记去重后查抑制 → 抑制场景通知静默丢失；提问轮询 30s 无门控（通知关闭仍打 REST）；SessionIdle 通知依赖 250ms 固定延迟
  - 方案：先预检抑制再标记；轮询退避/门控；事件驱动或多次轮询
  - 工时：~0.5d | 难度：低-中 | 涉及：OpenCodeConnectionService/AppNotificationManager | 优先级：P2

- [ ] **#113 UI 状态竞态批次（D2-06 草稿恢复 + D2-26 设置读改写 + D2-L66 clearDraft + D2-L67 答案 saveable）** `ui` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/D 路）
  - 问题：冷启动草稿不回填（视觉丢失）；快速连切设置丢修改；clearDraft 与 saveDraft 并发；QuestionCard 答案旋转丢
  - 方案：LaunchedEffect(draftText) 初始化；设置写串行化（Mutex/单消费者）；clearDraft 走同一写通道；rememberSaveable
  - 工时：~0.5d | 难度：低 | 涉及：ChatScreen/ChatViewModel/SettingsViewModel/DraftInputDelegate/QuestionCard | 优先级：P1

- [ ] **#114 认证头统一（D2-27，147 处内联）** `network` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-27（E+A 路，grep 实测 147 处）
  - 问题：Authorization 逐请求内联 + Auth 插件空 install → 认证演进改 147+ 处，新端点易漏挂头 401
  - 方案：配置 Auth provider 或抽 auth(conn) 扩展统一替换；V1/V2 双轨同步
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/NetworkModule | 优先级：P1

- [ ] **#115 移动端生命周期批次（D2-16 onTrimMemory + D2-17 崩溃退避 + D2-L23~L25 rememberSaveable）** `android`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-16/D2-17/D2-L23~L25
  - 问题：无低内存回调；崩溃无条件重启（死循环风险）；手动连接进程死亡不恢复；20+ 处对话框 remember 非 saveable；FileViewerOverlay VM 重建丢批注
  - 方案：onTrimMemory 分级清理；重启退避（10min 内最多 1 次）；记录 lastConnected 恢复；rememberSaveable 批量迁移（触发条件注意：旋转由 configChanges 处理，主要覆盖 recreate 场景）
  - 工时：~1d | 难度：低-中 | 涉及：OpenCodeApp/OpenCodeConnectionService/各 Screen | 优先级：P1-P2

- [ ] **#116 终端批次（D2-20 输入乱序 + D2-21 dispose 取消清理协程）** `terminal` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-20/D2-21（A 路）
  - 问题：socket.send fire-and-forget 多线程乱序；dispose() 在清理协程完成前 scope.cancel() → 服务端 PTY 残留
  - 方案：单发送 actor/Mutex；dispose 先 await 清理完成再 cancel
  - 工时：~0.5d | 难度：中 | 涉及：ServerTerminalWorkspace/PtyToTermlibAdapter | 优先级：P2

- [ ] **#117 死代码/弃用/重复代码清理批次（D2-L1~L22 + D2-L15 日期统一 + D2-L16 剪贴板 + D2-L52 死参数）** `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md 簇 A/B/F（多路命中）
  - 问题：@Deprecated 委托链 ×9、桩方法 ×4、无调用方 API ×6、WebView 死分支 ~15KB（useNativeUi=true）、SimpleDateFormat 14 处、剪贴板 9 处、rejectHtmlResponse 复制、exportSessionToStream 整方法复制、ChatTerminalView snackbar 参数遮蔽、异常传播三套并存（D2-33，getOrThrow/Result/裸 List + ApiError 双重语义）等
  - 方案：清理日集中删除（先 grep 测试引用）；抽 DateFormatters/copyToClipboard/WebView 工厂；WebViewScreen 死分支删除需先确认无入口
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3

- [ ] **#118 构建/安全批次（D2-28 cleartext + D2-29 R8 keep-all + D2-L64 版本倾斜/测试默认值 + D2-L28 备份密钥）** `build` `security`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-28/D2-29/D2-L28/D2-L64
  - 问题：明文流量全局放行无白名单；R8 keep-all 整库保留；Kotlin 2.3.21 + force metadata 2.4.0；isReturnDefaultValues；备份恢复后 Keystore 密钥缺失
  - 方案：networkSecurityConfig 白名单化；R8 收窄；升级 Kotlin 后移除 force；备份规则排除凭据文件
  - 工时：~1d | 难度：中 | 涉及：Manifest/proguard/build.gradle.kts/SecretCipher | 优先级：P2

- [x] **#119 第一期报告状态回写（C-1/H-1/H-2/H-3/M-9 已修复）** `docs`
  - 来源：audit-2026-08-13-dimensions/REPORT.md §6.1（c0c74a4c 实证）
  - **2026-08-14 完成**：REPORT.md 五处条目（C-1/H-1/H-2/H-3/M-9）标题加"✅ 已修复（2026-08-13 c0c74a4c）"标记；backlog #93/#94 状态转正 [x]（grep 实证三处 WebView 销毁齐全 + 降采样齐全）；WebViewScreen 不可达（useNativeUi=true）确认删除项另登记
  - 问题：第一期 REPORT.md 的 C-1/H-1/H-2/H-3/M-9 仍标记未修复，实际已由提交 c0c74a4c 落地（2026-08-13 23:39）；backlog #93/#94 状态需转正
  - 方案：回写第一期报告状态 + 同步 backlog；WebViewScreen 已不可达（useNativeUi=true）需另行确认删除
  - 工时：~0.5h | 难度：低 | 优先级：P0（文档准确性）

- [ ] **#120 Markdown/文案一致性批次（D2-07/D2-08/D2-09/D2-10/D2-32）** `markdown` `i18n` `ui`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/E 路）
  - 问题：① 跳转预渲染 fallback 用未归一化原始文本（MessageCardUser.kt:136 vs ChatMessageList.kt:442）→ 跳转目标首帧排版突变；② ClickableMarkdown 用 indexOf 定位可点击项（:95/:135）→ 重复文本段落点击/下划线错位；③ RetryBanner 双占位符恒显示 N/N（:49）；④ CompactionBanner 硬编码英文（:79）；⑤ SessionRow 硬编码英文 Diff 文案（:367-368）
  - 方案：jumpMdState 前 normalizeForRender；AST offset/span range 映射点击；文案改单占位符；提取资源补齐 14 语言
  - 工时：~0.5d | 难度：低-中 | 涉及：MessageCardUser/ClickableMarkdown/MarkdownTable/RetryBanner/CompactionBanner/SessionRow | 优先级：P2

- [ ] **#121 V1/V2 双客户端一致性批次（D2-22/D2-23/D2-30/D2-31）** `consistency` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/E 路）
  - 问题：① rejectHtmlResponse 两处复制且 V1ApiClient 无 HTML 防御（V2ApiClient.kt:113/V2Mappers.kt:124）；② V2SseMapper 把 ordinal 当时间戳（:125/:151）；③ 6 处 WebView 初始化样板不统一（销毁策略各异）；④ V2 fs.list 路径推导绕过 PathUtils（V2ApiClient.kt:1157-1166，Windows 服务器必错）
  - 方案：rejectHtmlResponse 提公共 + V1 接入；SSE 时间取服务器字段；抽 WebView 工厂；改 PathUtils.fileName/joinPath
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/V2SseMapper/WebView 各文件 | 优先级：P2

- [ ] **#122 状态性能与 AI Agent 功能批次（D2-15/D2-19/D2-25）** `perf` `sse` `ai-agent`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：① SessionStateService 每 SSE 事件对 _fsmStates/_histories 整张 Map 拷贝 + mapValues 全量派生（:184-190/:212-216）→ 流式 GC 压力；② SSE id: 帧被忽略、无 Last-Event-ID 续传（SseClientV2.kt:182-184）→ 断连窗口事件可能永久缺失；③ PermissionAutoApprover.shouldAutoApprove 全库无调用方 → 自动批准规则从未生效（功能失效）
  - 方案：toMutableMap 单次拷贝 + history 定长 + mapValues distinctUntilChanged；重连带 Last-Event-ID/游标循环补漏；在 PermissionAsked 路径接入自动 reply 或移除 UI 入口
  - 工时：~1d | 难度：中 | 涉及：SessionStateService/SseClientV2/PermissionAutoApprover | 优先级：P2

