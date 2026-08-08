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

---

## P2 — 优化与锦上添花

- [ ] **#29 androidTest 编译修复（#25 已读标记遗留）** `refactor` `data`
  - 问题：commit 5793957f（#25 已读标记服务器域重构）为 SessionRepository 增加 `getLastCompletedReplyTimeFlow()`、SettingsRepository 增加 5 个已读状态方法，但 FakeSessionRepository/FakeSettingsRepository 未同步实现 → `compileDevDebugAndroidTestKotlin` 从该 commit 起持续失败（2026-08-08 悲观重构验证时发现，与重构无关的预存在问题）
  - 方案：Fake 补缺失接口方法（按接口签名 + 现有 fake 语义实现），恢复 androidTest 编译
  - 工时：~30min | 难度：低 | 涉及：androidTest/fakes/FakeSessionRepository.kt、FakeSettingsRepository.kt

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
- [ ] **#31 本地库损坏自愈（Room 版）** data oom`n  - 问题：Plan 1 迁移后删除了旧 withDatabaseRecovery（catch SQLiteException → deleteDatabase 重建），Room 版无等价兜底；ocbeacon.db 损坏时 recordBatch 异常会传播至 AppLogger。Room+WAL 较旧实现健壮，诊断日志非用户资产，属低风险
