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

- [ ] **#16 ChatScreen 主函数臃肿** `refactor` `ui`
  - 问题：888 行文件，主函数约 600 行，滚动状态集群（autoScrollEnabled/isAtBottom/4 个 LaunchedEffect）内联
  - 方案：抽 rememberChatScrollController；**autoScrollEnabled/isAtBottom/双 key LaunchedEffect 必须整体搬移（SSE 铁律 4）**；编辑前读 chatscreen-editing-protocol.md
  - 工时：~0.5d + 真机验证 | 难度：高 | 涉及：ChatScreen.kt

- [ ] **#17 SessionListViewModel 分层越界** `refactor`
  - 问题：全项目唯一混用 4 种数据源的 ViewModel（Api 绕过 Repository + EventDispatcher 细节 + internal val 暴露）
  - 方案：4 个 Api 下沉 UseCase；EventDispatcher 经 Repository 接口暴露；internal → private
  - 工时：~1-1.5d | 难度：中 | 涉及：SessionListViewModel / DirectoryManager / 新增 UseCase

---

## P2 — 优化与锦上添花

- [ ] **#18 ChatMessageList 指纹函数外移** `refactor`
  - 问题：765 行因铁律 6-8 缓存逻辑膨胀
  - 方案：只外移纯函数（messageFingerprint/partsFingerprint/tailHash/messagesSignature）到 util/MessageFingerprints.kt；缓存函数高度耦合不动
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt

- [ ] **#19 Phase 历史注释清理** `refactor`
  - 问题：约 30 处"在 Phase N Task X 中提取"注释，工作已完成，纯历史噪音
  - 方案：批量删除历史标记，保留功能说明
  - 工时：~30min | 难度：低 | 涉及：ui/screens/chat/* + FileViewer* + Workspace*

- [ ] **#20 SearchMatchDto 字段对齐** `data`
  - 问题：DTO 字段与 API 不匹配（API 是 path:{text}/line_number，DTO 是 lines/lineNumber）；当前 /find 未启用未触发，启用即静默失败
  - 方案：按 API 对齐字段（@SerialName 处理 snake_case）
  - 工时：~1h | 难度：低 | 涉及：FileResponses.kt（启用 /find 前必须完成）
