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

### 2026-08-10 系统审计批次（F 报告 P0）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md（5 路交叉验证：A 渲染 + B 数据 + C 状态 + D 历史 + E 实测）

- [~] **#36 DatabaseRecovery catch 范围过宽 → 非损坏异常误删全库** `data` `security`
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

- [~] **#26 提问单选/复选控件语义纠正** `ui`
  - 问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 现状（代码已确认）：QuestionOptionRows（QuestionPartContent.kt:259）已按 `question.multiple` 分支渲染 CheckBox/RadioButton 图标；但需验证 `multiple` 字段在 SSE 事件 → QuestionParser → UI 全链路传递是否可靠（服务器未传 / 解析丢失时可能全部退化为单选样式）；历史视图 CollapsibleQuestionPart（QuestionPartContent.kt:135）答案固定用 RadioButtonChecked 图标，多选答案也应显示 CheckBox 样式
  - 调研方向：先确认真实渲染路径（活动提问走 QuestionCard + QuestionPagerView，历史走 CollapsibleQuestionPart / QuestionExpandedOptions），定位 multiple 丢失点；再按 M3 语义修正图标
  - 工时：~2h | 难度：中 | 涉及：QuestionPartContent.kt / QuestionCard.kt / QuestionParser
  - **2026-08-08 代码完成（待人工验证）**：QuestionParser 新增 `ParsedQuestion.isMultiple`（JSON multiple 3 解析点 + 7 测试，commit 04b3fb33）；CollapsibleQuestionPart 历史答案图标按 isMultiple 分支 CheckBox/RadioButtonChecked + PartContent 调试日志清理（commit a86b2e87）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：活动/历史多选显示复选框

- [~] **#27 多问题提问"下一步/提交"流程** `ui`
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

- [ ] **#30 消息本地化批次（方案 C）——Plan 1/2/3 全部完成（代码），待人工验证** `data` `cache` `room`
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

---

## P2 — 优化与锦上添花

- [~] **#71 后台系统 + V2 消息链路 D4 人工验收（时间性现象，自动化无法覆盖）** `ui` `sse`
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

- [~] **#28 提问组件样式与高度统一优化** `ui`
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

- [~] **#33 草稿在进程被杀时丢失（saveDraft 仅 onCleared 触发）** `data` `session`
  - 问题：2026-08-09 模拟器走查（V7）发现——ChatViewModel.kt:430 的 draftDelegate.saveDraft() 仅在 onCleared() 调用，am force-stop / 系统低内存杀进程不触发 onCleared → 草稿丢失（输入框重置为 placeholder）。预存问题（触发时机一直如此，非 #30 的 DataStore 迁移引入；迁移只改存储机制）
  - 影响：用户按 Home 后台 + 系统杀进程 → 草稿丢失；正常返回（ViewModel onCleared 触发）不受影响
  - 方案：updateDraftText 加防抖定期 saveDraft()（如 1-2s 无输入即存），或 Activity onStop/onSaveInstanceState 触发；需评估写频率与 DataStore 成本
  - 工时：~1h | 难度：低 | 涉及：DraftInputDelegate / ChatViewModel
  - 来源：2026-08-09 模拟器走查 V7
  - **2026-08-09 完成（待人工验证）**：DraftInputDelegate.updateDraftText 加 500ms 防抖自动持久化（DRAFT_SAVE_DEBOUNCE_MS，scope.launch + delay，每次输入取消重启）；clearDraft 取消挂起 job（防清空后又被存回）；onCleared 兜底保留；新增 DraftInputDelegateTest 4 用例（防抖窗口内不存/快速输入只存最后一次/clear 取消/即时状态更新，commit e3ffeae7）；编译 ✅ 全量单测 ✅；⚠️ 真机验证待用户：输入草稿 → force-stop → 重启 → 草稿仍在

- [~] **#34 同 URL 第二服务器连接 UX（永久 Connecting 无提示）** `ui` `sse`
  - 问题：2026-08-09 双服务器验证发现——同 URL 第二个配置点 Connect 后永久卡 'Connecting...'（>60s 无握手/无错误/无日志），手动 Cancel 才能退出。架构上 app 限制同 URL 单一活跃 SSE 连接（防双投递），但 UX 无反馈
  - 方案：检测到同 URL 已有活跃连接时直接拒绝并提示'该后端已连接'，或复用现有连接；或加超时/错误提示
  - 工时：~1h | 难度：低 | 涉及：连接管理 UI + SseConnectionManager
  - 来源：2026-08-09 双服务器去重验证走查
  - **2026-08-10 完成（待真机验证）**：根因 = OpenCodeConnectionService.connect 已有 url+username 去重但静默 return，HomeViewModel 乐观 connecting 状态无回传 → 永久 Connecting。修复：HomeViewModel connectToServer 经 serviceBinder.findDuplicateBackend 预检（ServerConfig.sameBackend 归一化：协议/host 小写、默认端口、尾斜杠）→ 命中写 connectionErrors 红字提示"该服务器已连接"（home_error_already_connected，15 语言）；Service 内去重保留为纵深防御。编译 ✅ i18n-check ✅（583 keys × 14 语言一致）

- [~] **#35 会话内 Back 触发一次 ANR（待复现）** `crash` `ui`
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
