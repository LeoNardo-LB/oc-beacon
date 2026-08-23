# 盘点：测试代码（src/test 与 src/androidTest 全部 Kotlin）

- 状态：**已完成**（Phase 1 事实收集；只读作业，未改任何仓库文件，未跑 gradle/git 写操作）
- 范围：app/src/test（196 个 .kt，含 1 个资源样例 sample-kotlin.kt）+ app/src/androidTest（52 个 .kt）= **248 文件，全部逐文件读毕**（大文件 offset/limit 分段读全文，禁 grep-only）
- 方法：修复版单趟分词器建索引（字符串/注释互不误吞）；**报告引用的注释原文均经真实 read 行区间核对**；扫描器产物仅作索引留存于附录
- 语言现状统计（按注释语言四分类）：中文 126 / 英文 15 / 混合 39 / 无注释 68（共 248）
- 规模：总 LOC≈38135，@Test 总数≈2016；中文测试名文件 11 个；「无注释且无领域词」文件 1 个

## 覆盖清单

- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/DtoSerializationTest.kt ✓ 混合 — session/message/part/tool/agent
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/NetworkMonitorTest.kt ✓ 英文 — sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/FileApiVcsTest.kt ✓ 无注释 — VCS diff 端点；L74 fixture 含前世包名 dev/minios/ocremote（术语残留）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/PartV2CompatTest.kt ✓ 混合 — V1/V2 tool part 契约对照；字段清单与主代码 Part.kt:89-97 完全一致（已核）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/MessageApiDeleteTest.kt ✓ 无注释 — message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/RetryPolicyTest.kt ✓ 英文 — turn/sse/retry
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApiCursorTest.kt ✓ 无注释 — session/message/turn/sse/cursor
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiImportTest.kt ✓ 无注释 — session/turn/sse/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiArchiveTest.kt ✓ 无注释 — session/turn/sse/archive
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiSearchPaginationTest.kt ✓ 英文 — session/turn/sse/cursor/paginat
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientReadTimeoutTest.kt ✓ 混合 — event/turn/sse/patch
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientSessionNextTest.kt ✓ 英文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseOOMProtectionTest.kt ✓ 中文 — event/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClientTest.kt ✓ 中文 — session/message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/sse/parsers/SseEventParserTest.kt ✓ 混合 — V1 解析器最大单文件（934 行/26 测试），英文注释为主
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2FrameTest.kt ✓ 中文 — event/turn/stream/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt ✓ 混合 — session/message/part/turn/token；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2EventParserTest.kt ✓ 中文 — session/message/event/token/compaction
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2MappersTest.kt ✓ 混合 — session/message/part/turn/token
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2SseMapperTest.kt ✓ 混合 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2FormMapperTest.kt ✓ 混合 — session/message/event/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/version/ApiVersionDetectorTest.kt ✓ 中文 — turn/sse/page/fallback
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/ErrorReportServiceTest.kt ✓ 中文 — session/turn/token/sse/retry
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/GitHubApiClientTest.kt ✓ 中文 — turn/sse/fingerprint
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/DatabaseRecoveryTest.kt ✓ 中文 — turn/sse/context
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/InjectedPartDeserializationTest.kt ✓ 中文 — session/message/part/tool/agent
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/LogStoreTest.kt ✓ 中文 — message/turn/sse/context
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt ✓ 混合 — session/message/part/turn/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ConfigMapperTest.kt ✓ 无注释 — agent/provider/config/sse/patch
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ToolOutputTruncatorTest.kt ✓ 中文 — part/tool/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodecTest.kt ✓ 中文 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/VcsMapperTest.kt ✓ 无注释 — sse/diff/patch/workspace
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/PermissionMapperTest.kt ✓ 无注释 — session/message/event/tool/permission
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ProviderMapperTest.kt ✓ 无注释 — provider/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/FileMapperTest.kt ✓ 无注释 — directory/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/QuestionMapperTest.kt ✓ 无注释 — session/event/tool/question/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/AgentRepositoryImplTest.kt ✓ 无注释 — agent/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImplTest.kt ✓ 混合 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceConcurrencyTest.kt ✓ 中文 — session/event/part/turn/directory；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogRepositoryTest.kt ✓ 中文 — session/message/token/config/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceTest.kt ✓ 混合 — session/message/event/part/turn；中文测试名；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/NaturalTurnEndListenerTest.kt ✓ 混合 — session/event/part/turn/provider
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/FileRepositoryImplTest.kt ✓ 无注释 — message/turn/directory/sse/workspace
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherTest.kt ✓ 中文 — session/message/event/part/token
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproveWiringTest.kt ✓ 中文 — session/message/event/turn/token
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PendingMessagePipelineTest.kt ✓ 混合 — 堆积消息管线（=排队/pending/queue/draining 多名根）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplDedupTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherIntegrationTest.kt ✓ 混合 — SSE 全管线集成大文件（860 行/23 测试）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateCollaboratorTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproverTest.kt ✓ 中文 — session/tool/directory/permission/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplTest.kt ✓ 混合 — session/message/event/turn/token
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerIncrementalPersistTest.kt ✓ 中文 — session/message/event/part/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt ✓ 中文 — session/turn/sse/context/migrat
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimesTest.kt ✓ 中文 — session/turn/unread/sse/context
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ShellJobsStoreTest.kt ✓ 中文 — session/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StubCollaborator.kt ✓ 中文 — 对照 CONTEXT.md 必需协作者词条（注释自引 avoid 词「回调旋钮」）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeServiceTest.kt ✓ 中文 — session/message/turn/unread/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadClockDomainTest.kt ✓ 中文 — 对照 CONTEXT.md 红点时钟域词条（用「水位线」一词）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistryTest.kt ✓ 中文 — session/stream/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsMigrationTest.kt ✓ 中文 — turn/sse/migrat
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/VcsRepositoryImplTest.kt ✓ 无注释 — session/message/turn/sse/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryTest.kt ✓ 中文 — message/tool/agent/draft/provider；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsLanguageMirrorTest.kt ✓ 中文 — sse/diff/context
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTokensPersistTest.kt ✓ 中文 — session/message/event/part/turn；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMemoryCapTest.kt ✓ 混合 — session/message/event/part/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeTest.kt ✓ 中文 — session/message/event/part/stream
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeSortedTest.kt ✓ 混合 — session/message/event/turn/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/PermissionEventHandlerTest.kt ✓ 无注释 — session/event/turn/tool/permission
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerFullTest.kt ✓ 中文 — session.next 处理器全集（851 行/55 测试，无 btFuns 命名）
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/QuestionEventHandlerTest.kt ✓ 中文 — session/message/event/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerTest.kt ✓ 英文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerV2ChainTest.kt ✓ 中文 — session/message/event/part/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/V2PartIdContractTest.kt ✓ 中文 — session/message/event/part/agent；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionEventHandlerTest.kt ✓ 混合 — session/message/event/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MiscEventHandlerTest.kt ✓ 无注释 — session/message/event/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/UpsertStrategyEquivalenceTest.kt ✓ 混合 — session/message/event/part/stream；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/PtyToTermlibAdapterTest.kt ✓ 中文 — session/event/stream/sse/idle
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/RemoteTerminalSessionEmulatorTest.kt ✓ 无注释 — session/turn/sse/patch/terminal
- app/src/test/kotlin/dev/leonardo/ocbeacon/data/update/UpdatePolicyTest.kt ✓ 英文 — part/draft/sse/diff/fallback
- app/src/test/kotlin/dev/leonardo/ocbeacon/debug/FrameStatsWindowTest.kt ✓ 中文 — sse/snapshot
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AnnotationPromptBuilderTest.kt ✓ 无注释 — directory/sse/annotation
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/AgentRepositoryTest.kt ✓ 无注释 — agent/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AutoApproveRuleTest.kt ✓ 中文 — session/message/event/tool/directory
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/OffsetConverterTest.kt ✓ 中文 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/DraftTest.kt ✓ 无注释 — turn/draft/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SerializationTest.kt ✓ 混合 — domain 序列化特征测试（1253 行/76 测试，最大文件）
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/LinkClassifierTest.kt ✓ 英文 — directory/config/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionNextEventTest.kt ✓ 英文 — session/message/event/part/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/ApiResultTest.kt ✓ 英文 — turn/sse/retry
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionStateFSMTest.kt ✓ 无注释 — session/event/part/compaction/stream
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/CreateDirectoryUseCaseTest.kt ✓ 中文 — session/turn/directory/sse/terminal
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/tracker/TokenStatsTrackerConcurrencyTest.kt ✓ 中文 — token/sse/merge
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepositoryTest.kt ✓ 无注释 — session/message/event/part/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepositoryTest.kt ✓ 无注释 — session/message/stream/sse/abort
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ToolSnapshotCacheBoundedTest.kt ✓ 中文 — tool/directory/sse/snapshot
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseTest.kt ✓ 无注释 — session/message/turn/sse/page
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ListSessionsUseCaseTest.kt ✓ 无注释 — session/turn/directory/sse/cursor
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseExtendedTest.kt ✓ 无注释 — session/message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt ✓ 中文 — session/message/part/turn/provider；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManagePermissionUseCaseTest.kt ✓ 无注释 — session/turn/permission/sse/pending
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageServerProvidersUseCaseTest.kt ✓ 无注释 — turn/provider/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/FindFilesUseCaseTest.kt ✓ 无注释 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/GetSettingsFlowUseCaseTest.kt ✓ 无注释 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/DeleteSessionUseCaseTest.kt ✓ 无注释 — session/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/AppNotificationDedupTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/logging/AppLoggerTest.kt ✓ 中文 — sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationCursorPolicyTest.kt ✓ 中文 — session/config/sse/cursor/paginat
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/WorkspaceUseCasesTest.kt ✓ 无注释 — turn/directory/sse/diff/patch
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationFSMTest.kt ✓ 混合 — session/message/event/part/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCaseTest.kt ✓ 无注释 — session/message/part/turn/agent
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/UpdateSettingsUseCaseTest.kt ✓ 无注释 — message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SubmitAnnotationsUseCaseTest.kt ✓ 无注释 — part/turn/sse/annotation
- app/src/test/kotlin/dev/leonardo/ocbeacon/domain/util/CursorCodecTest.kt ✓ 中文 — turn/sse/cursor
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/ErrorStreakTrackerTest.kt ✓ 中文 — session/message/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackDedupIsolationTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackPolicyTest.kt ✓ 混合 — sse/snapshot/feedback/notification
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/FindUserMessagesTest.kt ✓ 英文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt ✓ 无注释 — session/event/question/sse/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionServiceConnectGuardTest.kt ✓ 中文 — event/turn/config/sse/context
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinatorTest.kt ✓ 中文 — 中文测试名 C1–C10（对照 CONTEXT.md 连接生命周期词条）
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/CancelSessionNotificationsTest.kt ✓ 无注释 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/SessionFocusHolderTest.kt ✓ 中文 — session/event/turn/sse/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/service/SseConnectionManagerTest.kt ✓ 中文 — session/message/event/turn/config；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/navigation/routes/WorkspaceNavTest.kt ✓ 中文 — session/part/turn/directory/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScrollControllerTest.kt ✓ 中文 — sse/retry/snapshot/patch/notification；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelDeleteTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelContextTokensTest.kt ✓ 中文 — session/message/event/part/turn；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelQueuedTest.kt ✓ 混合 — QUEUED 徽章/子会话标识——术语变体集中地（690 行）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelPermissionTest.kt ✓ 混合 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/CustomAnswerToggleFlowTest.kt ✓ 中文 — 提问卡 parked 三态模型（真源调用，防镜像漂移）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/DraftInputDelegateTest.kt ✓ 中文 — session/turn/agent/directory/draft
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelSendTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegateTest.kt ✓ 中文 — session/message/part/turn/tool；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelStreamingTest.kt ✓ 中文 — session/message/event/part/turn
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegateTest.kt ✓ 混合 — UI 侧最大测试（1083 行/29 测试）；重复注释 5 处「V2 首翻 stub」
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt ✓ 无注释 — session/event/question/sse/snapshot
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/PartRenderLogicTest.kt ✓ 中文 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ExtractToolSubagentSessionIdTest.kt ✓ 中文 — session/message/part/turn/tool；中文测试名
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/SessionLifecycleDelegateTest.kt ✓ 无注释 — session/turn/directory/sse/idle
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPagerHeightTest.kt ✓ 中文 — turn/question/sse/page
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/RenderSupplyCoordinatorTest.kt ✓ 中文 — 中文测试名 T1–T10（对照 CONTEXT.md 渲染供给词条）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/IsBackgroundMoveSyntheticTest.kt ✓ 中文 — agent/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpNavigationControllerTest.kt ✓ 中文 — event/sse/abort/idle/jump
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/SyntheticTaskParserTest.kt ✓ 混合 — 中文测试名（<task> 结构解析）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpLockDerivationTest.kt ✓ 中文 — 中文测试名；300ms 缓冲窗口（vs 词条「跳转稳定窗口 2s」）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/FindJumpTargetItemTest.kt ✓ 中文 — message/turn/sse/jump/chunk
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ParseSyntheticTaskTest.kt ✓ 中文 — session/turn/agent/subagent/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt ✓ 无注释 — turn/question/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/UserChunkTest.kt ✓ 混合 — 中文测试名（用户长消息分片）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/ClickableMarkdownResultTest.kt ✓ 中文 — sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/BusyIndicatorSmootherTest.kt ✓ 中文 — 中文测试名（busy 指示平滑）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DefaultToolCardResolverTest.kt ✓ 无注释 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/NormalizeTaskListMarkersTest.kt ✓ 英文 — turn/sse/task
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/SplitOversizedParagraphsTest.kt ✓ 中文 — sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/terminal/TerminalRecoveryActionTest.kt ✓ 混合 — sse/terminal；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressChildSessionInjectionTest.kt ✓ 混合 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurnTest.kt ✓ 混合 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/PartGrouperTest.kt ✓ 无注释 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressOutputInjectorTest.kt ✓ 无注释 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DiffHelpersTest.kt ✓ 英文 — tool/sse/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/TaskOutputFetchTest.kt ✓ 中文 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolSnapshotGrouperTest.kt ✓ 无注释 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/AttachmentValidationTest.kt ✓ 无注释 — config/stream/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/DirectoryManagerServerPathsTest.kt ✓ 中文 — session/directory/sse/retry
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/home/HomeViewModelCancelConnectionTest.kt ✓ 中文 — turn/provider/config/sse/idle；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ChatModifiersTest.kt ✓ 中文 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/TurnGroupCalculatorTest.kt ✓ 中文 — session/message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParserTest.kt ✓ 英文 — turn/tool/question/sse/fallback
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/JumpTargetExtractorTest.kt ✓ 混合 — session/message/part/turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ContextStatsTest.kt ✓ 混合 — session/message/part/turn/token
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/PatchVisibilityResolverTest.kt ✓ 中文 — session/message/part/turn/sse；KDoc 密集
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/RecentSessionDirectoriesTest.kt ✓ 中文 — session/directory/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt ✓ 中文 — session/message/event/directory/draft
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/TreeNodeTest.kt ✓ 中文 — session/part/turn/directory/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelSearchTest.kt ✓ 无注释 — session/message/turn/directory/draft
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelPaginationTest.kt ✓ 无注释 — session/message/turn/directory/draft
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt ✓ 中文 — 中文测试名（外壳/内容状态切片）
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/util/SessionGroupingTest.kt ✓ 中文 — session/directory/sse/fallback
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt ✓ 中文 — session/message/event/turn/directory；中文测试名
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/AnnotationManagerTest.kt ✓ 英文 — turn/sse/annotation
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileTypeTest.kt ✓ 无注释 — config/sse/page/render
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileViewerViewModelTest.kt ✓ 中文 — session/part/turn/tool/directory
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilderTest.kt ✓ 无注释 — message/sse/render
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffParserTest.kt ✓ 中文 — session/message/part/turn/tool
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/HighlightBuilderTest.kt ✓ 英文 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/WorkspaceViewModelTest.kt ✓ 中文 — turn/directory/config/sse/idle
- app/src/test/kotlin/dev/leonardo/ocbeacon/ui/theme/ChatDensityTest.kt ✓ 无注释 — sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageFingerprintsTest.kt ✓ 无注释 — session/message/part/tool/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/verification/Layer1EnhancedTest.kt ✓ 混合 — turn/sse/retry/diff
- app/src/test/kotlin/dev/leonardo/ocbeacon/util/PathUtilsTest.kt ✓ 无注释 — turn/sse
- app/src/test/kotlin/dev/leonardo/ocbeacon/util/SafeCatchTest.kt ✓ 中文 — message/turn/sse/fallback
- app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageTimestampTest.kt ✓ 中文 — 中文测试名（时间戳格式）
- app/src/test/kotlin/dev/leonardo/ocbeacon/util/RunCatchingCancellableTest.kt ✓ 混合 — message/turn/sse
- app/src/test/resources/workspace-samples/sample-kotlin.kt ✓ 无注释 — 资源样例（非测试）；包名已是 dev.leonardo.ocbeacon（已核）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ComposeTestRule.kt ✓ 中文 — 
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltTestRunner.kt ✓ 无注释 — turn/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltComponentActivity.kt ✓ 中文 — 插桩基建：纯组件宿主 Activity（无注入）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltEntryActivity.kt ✓ 中文 — 插桩基建：Hilt 注入宿主 Activity（ViewModel 级）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/SampleInstrumentedTest.kt ✓ 中文 — sse/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestMessageBuilder.kt ✓ 中文 — session/message/part/turn/tool；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSettingsBuilder.kt ✓ 中文 — turn/tool
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSessionBuilder.kt ✓ 中文 — session/directory/idle
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/BaseChatTest.kt ✓ 中文 — 插桩基建：Hilt+Compose 标准搭建
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInputTest.kt ✓ 中文 — agent/draft/sse/idle/render
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionTest.kt ✓ 混合 — session/message/event/part/turn；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionIsolatedTest.kt ✓ 中文 — session/message/part/token/tool；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatMessageRenderingTest.kt ✓ 中文 — session/message/part/turn/tool；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatSmokeTest.kt ✓ 中文 — session/message/sse/idle/render
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatScrollStabilityTest.kt ✓ 中文 — session/message/part/turn/token；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDaoTest.kt ✓ 中文 — session/message/turn/provider/sse；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/MigrationTest.kt ✓ 中文 — v1→v2 手工重建迁移（Room exportSchema=false 绕行）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt ✓ 中文 — message/turn/provider/sse/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeApiModule.kt ✓ 中文 — session/message/provider/terminal；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeNetworkModule.kt ✓ 中文 — context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeDomainModule.kt ✓ 中文 — session/message/agent/draft/provider；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeAgentRepository.kt ✓ 中文 — session/turn/agent/directory
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeFileRepository.kt ✓ 无注释 — directory
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeChatRepository.kt ✓ 混合 — L28 注释称「46 个方法」，实际 override 计 49（失实）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeDraftRepository.kt ✓ 无注释 — session/draft
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMessageCacheRepository.kt ✓ 中文 — session/message/part/paginat/archive；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeServerRepository.kt ✓ 混合 — turn/agent/provider/config/patch
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMcpRepository.kt ✓ 无注释 — 无术语——测试基建
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionStateRepository.kt ✓ 中文 — session/message/event/part/sse；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionRepository.kt ✓ 混合 — session/message/part/turn/agent
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeVcsRepository.kt ✓ 无注释 — directory/diff/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSettingsRepository.kt ✓ 中文 — session/turn/provider/unread/migrat
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerBranchTest.kt ✓ 无注释 — token/compaction/sse/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/chat/TaskSheetClickTest.kt ✓ 中文 — session/agent/subagent/provider/sse；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/SessionRetryCardTest.kt ✓ 无注释 — session/message/sse/retry
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorBranchTest.kt ✓ 无注释 — agent/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorTest.kt ✓ 无注释 — agent/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CopyButtonTest.kt ✓ 无注释 — sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ConnectionErrorScreenTest.kt ✓ 无注释 — message/config/sse/retry
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoBranchTest.kt ✓ 无注释 — message/token/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoTest.kt ✓ 无注释 — message/token/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerTest.kt ✓ 无注释 — compaction/sse/context
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardBranchTest.kt ✓ 中文 — 含 4 段「幻影断言移除」自述注释（#120 半成品证据）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardTest.kt ✓ 无注释 — part/tool/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardBranchTest.kt ✓ 中文 — part/tool/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardTest.kt ✓ 中文 — token/sse
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreenTest.kt ✓ 混合 — session/sse/archive
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffViewTest.kt ✓ 中文 — sse/diff/patch/render
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt ✓ 中文 — sse/idle
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/settings/DiagnosticsScreenDuplicateTimestampTest.kt ✓ 中文 — 重复 timestamp key 崩溃回归（含 verbatim 崩溃报告）
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/tree/FileTreePanelTest.kt ✓ 中文 — message/directory/sse/retry/workspace；KDoc 密集
- app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/git/GitChangesPanelTest.kt ✓ 中文 — sse/retry/diff/workspace/render

## 术语观察

| 概念 | 观察到的变体 | 位置（文件:行） | 与 API 词一致? |
|---|---|---|---|
| 会话 | session / Session / ses_* 前缀 / 中文「会话」/ 子会话 / child session / subagent session | ChatViewModelQueuedTest.kt:59-60（sessionParentId/subSessionId）；TaskOutputFetchTest.kt:100（childID） | ✓ session 为 API 原词；「子会话」为派生概念 |
| 消息 | message / Message / msg_* / 气泡（bubble）/ synthetic 消息 / 通知卡片（SyntheticNotificationCard） | RenderableTurnTest.kt:69-78；MessageEventHandlerV2ChainTest.kt:109-113 | ✓ message；「气泡/通知卡片」为 UI 层派生词 |
| 事件 | event / SSE 事件 / session.next.* / V2 生命周期事件 / 断档（gap） | SessionNextEventHandlerFullTest.kt:586-615（断档=序号 gap） | ✓ event；「断档」为内部行话 |
| 部分 | part / Part / parts / 与「分片 chunk」「entry」混用 | UserChunkTest.kt:15-18（chunk=用户长消息分片）；RenderSupplyCoordinatorTest.kt:28（entry→display 映射） | ✓ part 为 API 原词；chunk/entry 是 UI 渲染层不同概念，词根易混 |
| 轮次 | turn / turn 结束 / 自然成功 turn 结束 / 流式 turn / turnStartMs / TurnGroupCalculator 的 turn=连续 assistant 序列 | NaturalTurnEndListenerTest.kt:18；TurnGroupCalculatorTest.kt:88-99 | ✓ turn 为 API 原词；TurnGroupCalculator 自定义分组语义与 API turn 漂移（见冲突 8） |
| 令牌 | token / tokens / 上下文占用 / 口径（input+cache.read）/ 统计栏 / 圆环数据源 | ChatViewModelContextTokensTest.kt:48-54；MessageEventHandlerTest.kt:94 | ✓ token；「口径/圆环」为 UI 行话 |
| 工具 | tool / Tool / callID / 工具卡片（ToolCard）/ 工具输出 / 工具快照（ToolSnapshot） | PartV2CompatTest.kt:20-21；ToolSnapshotGrouperTest.kt | ✓ tool / callID 与 API 一致 |
| 智能体 | agent / Agent / 子代理（subagent 译）/ 后台任务（background task） | SyntheticTaskParserTest.kt:8-17；ParseSyntheticTaskTest.kt:39-47 | ✓ agent/subagent 为 API 原词；「子代理/后台任务」中译不统一 |
| 目录 | directory / x-opencode-directory 头 / cwd / home / baseDirectory / 目录头 | FileApiVcsTest.kt:82-83；TreeNodeTest.kt:77-90 | ✓ directory；header 名与 API 一致 |
| 权限 | permission / PermissionAsked / 自动批准（auto-approve）/ 规则（AutoApproveRule） | PermissionAutoApproveWiringTest.kt:31-33；AutoApproveRuleTest.kt:62-70 | ✓ permission |
| 问题 | question / QuestionAsked / form（V2 form.created→QuestionAsked）/ question.v2.asked / 提问卡 / parked 三态 | V2FormMapperTest.kt:161-234；CustomAnswerToggleFlowTest.kt:13-15 | ⚠ question 与 form 双轨（见冲突 5）；parked 为 UI 状态词 |
| 提供方 | provider / ProviderInfo / 模型目录（catalog）/ 模型选择器 | V2ApiClientTest.kt:375；ChatInteractionIsolatedTest.kt:89-98 | ✓ provider |
| 未读/红点 | 未读 / 红点 / badge / unread / maxCompleted / lastCompletedReplyTime / 水位线（watermark）/ 读时刻（read times） | UnreadClockDomainTest.kt:39-42（水位线）；EventDispatcherUnreadTest.kt:37-39；UnreadBadgeServiceTest.kt:16-21 | ⚠ 同一概念三个名字域（见冲突 1）；与 CONTEXT.md「红点时钟域」词条对应 |
| 压缩 | compaction / 压缩 / compact（斜杠命令）/ CompactionBanner / 压缩横幅 / 细粒度压缩事件 | V2EventParserTest.kt:134-150；CompactionBannerTest.kt:23-36 | ✓ compaction；compact 命令动词同根 |
| 游标 | cursor / 翻页 / pagination / before / cursor.next / direction（"next"=更旧,"previous"=更新）/ 双向游标 | CursorCodecTest.kt:43-64；PaginationCursorPolicyTest.kt:27 | ✓ cursor；direction 语义反转是 API 契约（见冲突 7） |
| 排队消息 | 堆积消息 / pending pipeline / queue / QUEUED 徽章 / draining / 队首 | PendingMessagePipelineTest.kt:26-31；ChatViewModelQueuedTest.kt:58,96-97 | ⚠ 同一特性四个词根（见冲突 2） |
| 流式 | streaming / 流式 / SSE 增量 / delta / 播种（seed）/ 热视图 / 热表 | MessageEventHandlerMergeTest.kt:10-12；V2SseMapperTest.kt:74（播种）；MessageStoreTest.kt（热表） | ✓ streaming/delta 为 API 原词；「播种/热视图」为内部行话 |
| 归档 | archive / 归档桶（bucket）/ cached_messages / 冷启动 / seed | ArchiveBucketDaoTest.kt:56-59；MessageStoreTest.kt:334-338 | ✓ archive；bucket 为本地实现词 |
| 快照 | snapshot / ToolSnapshot / 快照 / 幽灵快照（REST 滞后） | EventDispatcherUnreadTest.kt:169-179 | ✓ snapshot |
| 跳转 | jump / 跳转定位 / 稳定窗口（终点+2s）/ 缓冲窗口（300ms）/ jumpLock / 跳转目标（JumpTarget） | RenderSupplyCoordinatorTest.kt:31（2s）；JumpLockDerivationTest.kt:20（300ms） | ⚠ 两个不同机制共享「跳转…窗口」词根（见冲突 3） |
| 渲染供给 | 预解析（preparse）/ 分片计划 / 裂变（split/commit）/ 视口防线 / LRU 联动 | RenderSupplyCoordinatorTest.kt:23-35 | 对照 CONTEXT.md「渲染供给」词条；测试用词与词条一致（avoid 词未再出现） |
| 连接 | 连接生命周期 / 同后端去重（url+username）/ registry / 轮询启停 / 四路清理 | ConnectionLifecycleCoordinatorTest.kt:29-39 | 对照 CONTEXT.md「连接生命周期协调」词条，用词一致 |
| 版本 | V1 / V2 / 版本探测 / 交叉验证 / 过渡形态（1.18.18）/ 能力位 | ApiVersionDetectorTest.kt:79-86,200-228 | ✓ V1/V2 为项目约定；对照 CONTEXT.md「版本 seam」词条 |
| 心跳 | heartbeat / 心跳（5s 队首推进）/ 「: heartbeat」注释帧 | SseClientV2FrameTest.kt:10-14,25；PendingMessagePipelineTest.kt:28 | ✓ heartbeat（SSE 规范词） |
| 合并策略 | upsert / SSE_PRIORITY / REST_AUTHORITY / APPEND_ONLY / merge / replace / 对拍（金标准） | UpsertStrategyEquivalenceTest.kt:11-15,49-106；MessageEventHandlerMergeSortedTest.kt:10-18 | ✓ upsert/merge 为通用词；三策略名项目自造（大写常量） |
| 旧项目名 | dev.minios.ocremote（前世包名）vs dev.leonardo.ocbeacon | FileApiVcsTest.kt:74（fixture 残留）；对照 sample-kotlin.kt:1（已是现名） | ✗ 残留（见冲突 9） |
| 提示音 | 提示音 / feedback / SoundPlan / 通知去重（dedup）/ 防刷（24h）/ 静音矩阵 | FeedbackPolicyTest.kt:9-10；ErrorStreakTrackerTest.kt:7；FeedbackDedupIsolationTest.kt:21-22 | 内部词；feedback 一词多义（反馈/提示音） |
| 悲观消息 | 悲观消息语义 / isSending / 乐观添加 / 回填草稿 | ChatViewModelSendTest.kt:211-249 | 内部行话（中文），无 API 对应 |
| 会话状态机 | FSM / SessionStateFSM / statusFlow / activityFlow / RS-0xx 竞态编号 / 必需协作者（collaborator） | SessionStateServiceConcurrencyTest.kt:40-47；SessionStateCollaboratorTest.kt:21-22 | 对照 CONTEXT.md「必需协作者」词条；FSM 用词一致 |
| 兜底层级 | L2/L3/L4 兜底 / REST 兜底 / 轮询 / 自然结束白名单 | NaturalTurnEndListenerTest.kt:128；MessageEventHandlerTest.kt:124-126 | 内部行话，层级编号未在任何词条定义（见冲突 15） |
| 僵尸/幽灵 | 僵尸协程（zombie）/ 孤儿 job（orphan）/ 幽灵消息（空 id）/ 幽灵快照 / 幻影断言 | SseConnectionManagerTest.kt:54-67；V2SseMapperTest.kt:74-77；TokenUsageCardBranchTest.kt:103-117 | 内部行话（形象词），集中出现于回归注释 |

## 失实注释

> 判定标准：注释与代码实际行为/数据结构不符（过期、复制残留、想当然）。逐文件精读 + 定向核证后，**现行失实仅 1 条确认 + 1 条边界**；另收录 6 处「注释自述曾失实、已随测试修正」的先例作为佐证。

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeChatRepository.kt:28 | 「Fake ChatRepository，包含 46 个方法。」 | 该文件 `override (suspend) fun` 声明实测 49 处（grep 计数）；文件内多处接线注释自证接口后续增员（#122 自动批准、2026-08-20 堆积消息管线等） | 删去硬编码数字，改为「实现 ChatRepository 全部接口成员」类不含数字的描述（接口演进时数字必过期） |
| app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/FileApiVcsTest.kt:74 | （边界条目：字符串字面量而非注释）fixture 路径 `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` | 主代码现包名为 dev.leonardo.ocbeacon（同仓 sample-kotlin.kt:1 已核为现名）；minios/ocremote 为项目前世包名，属复制残留 | fixture 路径现代化为现包名，或保留但在行内注明是历史抓包数据 |

**历史已自愈先例**（注释自述曾失实并已修正——佐证「注释审计」必要性，非现行问题）：

- LogDaoTest.kt:107-110（已 read 核对原文）：「2026-08-16 断言更新：deleteErrorBefore 现语义为删除 ERROR**与 FATAL**……旧断言对应只删 ERROR 的历史行为，androidTest 首次真正运行暴露过时。」
- ArchiveBucketDaoTest.kt:56-59（已 read 核对原文）：「latestBefore 按 bucketStart 相交判定……旧断言（2 桶）对应 bucketEnd < beforeEnd 的历史行为。」
- PaginationFSMTest.kt:175-177：注释自述原测试参数自相矛盾（nextCursor 非空却断言读尽），2026-08-18 勘误修正。
- TokenUsageCardBranchTest.kt:103-117：四段「2026-08-16 移除：幻影 context 断言」——断言对应不存在的功能（#120 半成品，androidTest 基建损坏期从未运行）。
- DiffViewTest.kt:53-54、FileTreePanelTest.kt:129-131：断言与实际渲染/文案不符，已改（后者 locale 无关化）。
- 反向核证（注释准确无失实）：PartV2CompatTest.kt:20-21 的 V1 Part.Tool 字段清单与主代码 Part.kt:89-97 完全一致（已逐字段核对）；ChatInputTest.kt:51 所称 SlashCommandRegistry.clientCommands() 在主代码 SlashCommandRegistry.kt:24 存在。

## 待裁决冲突

1. 未读概念三名：「红点/未读/badge」（UI 语）vs「水位线 watermark」（UnreadClockDomainTest:39-42）vs 字段名 maxCompleted/lastCompletedReplyTime（EventDispatcherUnreadTest:37-39、UnreadBadgeServiceTest:16-21）。与 CONTEXT.md「红点时钟域」词条相关但未用词条词。范围：data/repository 未读三文件 + SessionListUnreadTest。备注：三个名字域各自内部一致，跨文件不统一；裁决规范中文名+字段名映射。
2. 排队消息四名根：「堆积消息」（PendingMessagePipelineTest:26）= pending pipeline = queue = ChatViewModelQueuedTest 的「QUEUED 徽章」（:58），另有 draining（:142）。范围：repository 管线测试 + ChatViewModel 两侧。备注：同一特性（入队→peek→POST→delete）；「堆积」为 2026-08-20 设计文档用语，「排队/QUEUED」为 UI 徽章用语。
3. 「跳转…窗口」双机制：RenderSupplyCoordinatorTest:31「稳定窗口（终点+2s 内不提交）」与 JumpLockDerivationTest:20「终点 300ms 缓冲窗口」——前者=分片提交冻结（对照 CONTEXT.md「跳转稳定窗口」词条现值 2s，一致），后者=jumpLockActive 派生锁的解锁缓冲。两值两义共享词根。备注：建议词条或命名区分「稳定窗口（2s，分片）」与「锁缓冲（300ms，滚动）」。
4. part/chunk/entry 交叉：API 的 part 与 UI 的 chunk（UserChunk=用户长消息分片）、entry（渲染供给 display-entry）、「分片计划/裂变」（RenderSupplyCoordinator）并存。范围：ui/screens/chat 分片系测试。备注：CONTEXT.md「渲染供给」词条的 avoid 词已避免重蹈，但 chunk 一词在 UserChunkTest/SplitOversizedParagraphsTest 另有「分段」义。
5. question vs form 双轨：V2 事件 form.created 映射 QuestionAsked；question.v2.asked 主路径；测试名/注释混用 form/question（V2FormMapperTest:150,224-234「form 契约才传 option.value」「question.v2 语义就是 label」）。范围：V2FormMapperTest + QuestionMapperTest。备注：API 层面两事件名并存是服务器事实；客户端术语表需指明规范名与映射关系。
6. sessionID 大小写双写：V2 大写 sessionID/messageID/assistantMessageID vs V1/域模型小写 sessionId，另有 jobId/childID 承载子会话 id（V2MappersTest:255,267-269；TaskOutputFetchTest:100；ExtractToolSubagentSessionIdTest:45）。范围：data/api/v2 全部 + handler 契约测试。备注：SerializationTest:193-194 明确锁定大写为 API 契约；双写是兼容手段，术语表应固化「API 侧大写、域侧小写、jobId=V2 服务器别名」。
7. 游标 direction 语义反转：服务器 direction="next"=更旧、"previous"=更新（CursorCodecTest:43-64 明示 OLDER→next、NEWER→previous），与直觉相反。范围：CursorCodecTest/PaginationCursorPolicyTest/MessagePaginationDelegateTest。备注：这是 API 契约事实，建议词条收录防误读。
8. turn 的分组语义漂移：TurnGroupCalculatorTest:88-99 定义 turn=连续 assistant 序列（synthetic 独立成条）；OpenCode API 的 turn 与 CONTEXT.md「流式 turn（completed 为空的 assistant 回复轮次）」是另一口径。范围：ui/screens/chat/util。备注：测试注释已写明是 2026-08-12 用户决策；术语表需区分「API turn」与「UI 分组 turn」。
9. 前世包名残留：FileApiVcsTest:74 fixture 含 dev/minios/ocremote（项目更名前），与现包名 dev.leonardo.ocbeacon 并存。范围：仅此一处（sample-kotlin.kt 已核为现名）。备注：属 fixture 数据残留，是否清理待裁决。
10. 子会话五变体：subagent session / child session / childID / jobId / subSessionId（+中文「子会话」「子代理」）。范围：ChatViewModelQueuedTest:59-60、V2MappersTest:267-269、ExtractToolSubagentSessionIdTest、TaskOutputFetchTest:100。备注：ExtractToolSubagentSessionIdTest:45 已注明「V2 服务器 metadata 用 jobId 存子会话 ID」——服务器侧别名是根因。
11. compact 动词/名词族：compactSession（会话操作）/ /compact 斜杠命令 / compaction 事件族 / 压缩横幅。范围：SessionRepositoryTest:20、ChatInputTest:51、V2EventParserTest:134-150。备注：API 两词根并存（compact 动作、compaction 事件），中文统一「压缩」后需防歧义。
12. 播种 seed 中英混用：「播种/种入/seed」（V2SseMapperTest:74,115；MessageEventHandlerV2ChainTest:111）指经事件预植消息。范围：data/api/v2 + handler。备注：英文 seed 动词与数据存储 seed（SettingsDataStoreReadTimesTest:127「迁移标记」）同形异义。
13. 测试名语言分裂：10 文件用中文反引号测试名（ConnectionLifecycleCoordinatorTest C1-C10、RenderSupplyCoordinatorTest T1-T10、JumpLockDerivationTest、UserChunkTest、BusyIndicatorSmootherTest、SyntheticTaskParserTest、MessageTimestampTest、SessionListShellStateTest、SessionListUnreadTest（部分）、ExtractToolSubagentSessionIdTest（部分）），其余全部英文。备注：注释未来统一中文的裁决下，测试名语言是否跟随需方针。
14. feedback 一词多义：提示音语义（FeedbackPolicyTest/FeedbackDedupIsolationTest 的 SoundPlan/静音矩阵）vs 通用「用户反馈」义（CustomAnswerToggleFlowTest:14「2026-08-19 用户反馈修复」）。范围：service 层。备注：中文注释里「反馈」两义并存。
15. 内部编号行话：RS-0xx（竞态）、T1-T10/C1-C10（用例）、L2/L3/L4（兜底层级）、D2-L54/#NNN（backlog/评审编号）、R6（需求条目）大量出现在注释中且多无展开。范围：全局（SessionStateServiceConcurrencyTest、NaturalTurnEndListenerTest、MessageEventHandlerTest 等）。备注：注释统一中文修订时需决定编号行话保留（可追溯）还是展开为自足描述。

## 附录：@@RAW 逐文件索引（扫描器产物）

> 每文件一行记录：LOC/语言分类/注释计数/@Test 数/词表命中/高频词/中文注释摘录（索引用途）。引用入正文的注释原文均已另行真实 read 核对。

═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/DtoSerializationTest.kt
LOC 606 | lang 混合 (zh 11/en 17, kdoc 3) | @Test 42 | btFuns 42 | DtoSerializationTest
LEX: session,message,part,tool,agent,directory,question,permission,provider,config,sse,busy,terminal,context
FREQ: decoded×132 json×111 equals×110 serializer×68 encoded×63 info×58 model×53 request×48 decode×40 encode×30 round×24 trip×24 status×23 paths×21
C-ZH: L18 [kdoc] domain API DTO 序列化/反序列化的特征测试。 | L20 [kdoc] 这些测试锁定 SerializationTest 尚未覆盖的 DTO 的现有序列化契约。 | L22 [kdoc] Phase 0 安全网：如果这些测试失败，说明重构破坏了 API 契约。 | L127 [line]  当 encodeDefaults = true 时，null 应被输出 | L210 [line]  序列化形状：嵌套 path/lines 为 {text}，snake_case 键，submatches 数组 | L288 [line]  ============ ProviderInfo（完整字段）============ | L321 [line]  ============ ModelCost（含 CacheCost）============ | L489 [line]  ============ PromptPart（URL 类型）============ | L531 [line]  ============ PermissionRequest（含 metadata）============ | L558 [line]  ============ QuestionRequest（含 tool）============ | L585 [line]  ============ ProviderModel（含 variants）============
C-EN*: L217  ============ ProviderCatalogResponse ============ | L244  ============ ProviderAuthMethod ============ | L255  ============ ProviderOauthAuthorization ============ | L397  ============ SessionStatusInfo ============
   (+13 trivial en comments)
ENSTR*: L106 "Should contain providerID" | L108 "Should NOT contain providerId" | L116 "My Terminal" | L390 """{"context": 32000, "output": 2048}"""
TESTS-EN: `ServerPaths round-trip` | `ServerPaths with all defaults` | `ServerPaths partial deserialization` | `ShellRequest round-trip with model` | `ShellRequest round-trip without model` | `ShellRequest serializes model with providerID and modelID` | `PtyCreateRequest round-trip with all fields` | `PtyCreateRequest defaults are null` | `PtyUpdateRequest round-trip with size` | `PtyUpdateRequest with only title` | `PtySize round-trip` | `OutputFormat round-trip with schema` | `OutputFormat with null schema` | `SearchMatchDto round-trip` | (+28 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/DtoSerializationTest.kt","loc":606,"lang":"混合","zh":11,"en":17,"kdoc":3,"tests":42,"cls":"DtoSerializationTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/NetworkMonitorTest.kt
LOC 35 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 5 | btFuns 5 | NetworkMonitorTest
LEX: sse
FREQ: network×13 state×12 online×8 unavailable×5
   (+1 trivial en comments)
TESTS-EN: `NetworkState Available has isOnline true` | `NetworkState Losing has isOnline false` | `NetworkState Lost has isOnline false` | `NetworkState Unavailable has isOnline false` | `NetworkState defaults to Unavailable`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/NetworkMonitorTest.kt","loc":35,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":5,"cls":"NetworkMonitorTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/FileApiVcsTest.kt
LOC 115 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | FileApiVcsTest
LEX: turn,directory,sse,diff,patch,context,workspace
FREQ: equals×23 engine×15 status×15 client×14 file×13 json×12 response×9 headers×9 request×9 ktor×8 additions×8 deletions×8 branch×7 code×7
ENSTR*: L73 """[
            {"file":"app/src/main/kotlin/dev/minios/ocremote/data/api/OpenC | L82 "Directory header should contain workspace path"
TESTS-EN: `getVcsStatus parses 3 changes - added, modified, deleted` | `getVcsDiff passes mode and context params, directory header` | `getVcs parses branch with default_branch via SerialName`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/FileApiVcsTest.kt","loc":115,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"FileApiVcsTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/PartV2CompatTest.kt
LOC 118 | lang 混合 (zh 11/en 4, kdoc 5) | @Test 4 | btFuns 4 | PartV2CompatTest
LEX: session,message,event,part,tool,agent,subagent,sse,abort
FREQ: json×26 state×20 metadata×19 error×17 completed×13 content×9 equals×7 input×7 parser×7 updated×7 payload×7 primitive×6 serializer×6 double×6
C-ZH: L15 [kdoc] 验证 V1 PartSerializer 对 V2 tool part 结构的兼容性。 | L17 [kdoc] V2 的 tool part 结构（REST/SSE 实测）： | L20 [kdoc] V1 Part.Tool 字段：id, sessionID, messageID, callID, tool, state, metadata | L21 [kdoc] 差异：V2 用 name（V1 用 tool）、V2 用 id（V1 也用 id 但 callID 独立） | L49 [line]  通过 MessageEventParser 解析（实际 SSE 路径），而非直接 PartSerializer | L74 [line]  2026-08-12 修复：旧数据/SSE 播种的 Part.Text 序列化省略默认值 | L75 [line] （text="" 时不写 text 字段、从不写 type）→ 无 type 有 text 时按字段推断 | L84 [line]  text="" 默认值被省略 → payload 无 type 无 text → Unknown（不误判为 Tool） | L92 [line]  V2 服务器实际返回的双层嵌套 metadata：{metadata: {sessionID: ...}} | L110 [line]  双层 metadata 展平后应能提取 sessionID | L115 [line]  error 对象解析为字符串（V2 error 是 {type, message}）
C-EN*: L61  V2 state.input → ToolState.input | L65  V2 state.metadata.sessionID → metadata
   (+2 trivial en comments)
ZHSTR: L31 """
    {
      "type": "tool",
      "id": "call_00_ET_tIFDKUFnLV5WqSVn9z5H1978",
      " | L53 "MessagePartUpdated 应解析出事件" | L54 "应为 MessagePartUpdated，实际是 ${event!!::class.simpleName}" | L56 "应该是 Part.Tool，实际是 ${part::class.simpleName}" | L59 "state 应为 Completed，实际是 ${tool.state::class.simpleName}" | L62 "验证无IME环境" | L64 "output 应包含工具输出，实际: '${completed.output}'" | L64 "验证完成" | L66 "metadata 不应为 null" | L76 """{"id":"msg_x_summary","sessionID":"s1","messageID":"msg_x","text":"大致说下当前目录下有哪些内容"}""" | (+6 more)
ENSTR*: L51 """{"part": $v2ToolJson}""" | L78 "expected Text, got $part" | L87 "expected Unknown, got $part" | L101 """{"part": $v2DoubleNested}""" | L116 "Tool execution interrupted"
TESTS-EN: `V1 PartSerializer decodes V2 tool part` | `PartSerializer infers Text from payload without type field` | `PartSerializer maps textless summary payload to Unknown` | `V1 PartSerializer decodes V2 tool with double-nested metadata`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/PartV2CompatTest.kt","loc":118,"lang":"混合","zh":11,"en":4,"kdoc":5,"tests":4,"cls":"PartV2CompatTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/MessageApiDeleteTest.kt
LOC 52 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | MessageApiDeleteTest
LEX: message,part,turn,sse
FREQ: delete×15 conn×9
TESTS-EN: `deleteMessage delegates to DELETE endpoint` | `deleteMessage returns false on failure` | `deleteMessagePart delegates to DELETE endpoint` | `deleteMessagePart returns false on failure`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/MessageApiDeleteTest.kt","loc":52,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"MessageApiDeleteTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/RetryPolicyTest.kt
LOC 149 | lang 英文 (zh 0/en 4, kdoc 0) | @Test 16 | btFuns 16 | RetryPolicyTest
LEX: turn,sse,retry
FREQ: policy×42 delay×24 error×17 exception×16 transient×16 equals×15 calls×13 calculate×9 initial×8 attempt×8 ioexception×7 caught×6 attempts×5 timeout×4
C-EN*: L15  ============ RetryPolicy defaults ============ | L89  ============ retryWithPolicy success path ============
   (+2 trivial en comments)
TESTS-EN: `default policy has expected values` | `calculateDelay for first attempt returns initialDelay` | `calculateDelay for second attempt doubles` | `calculateDelay is capped at maxDelay` | `calculateDelay for attempt 0 returns initialDelay` | `IOException is transient` | `SocketTimeoutException is transient` | `ApiError ServerError is transient` | `ApiError RateLimitError is transient` | `ApiError NetworkError is transient` | `ApiError AuthError is not transient` | `RuntimeException is not transient` | `retryWithPolicy returns success on first attempt` | `retryWithPolicy retries on IOException and succeeds` | (+2 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/RetryPolicyTest.kt","loc":149,"lang":"英文","zh":0,"en":4,"kdoc":0,"tests":16,"cls":"RetryPolicyTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApiCursorTest.kt
LOC 92 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | MessageApiCursorTest
LEX: session,message,turn,sse,cursor,page
FREQ: client×16 engine×15 json×13 headers×11 ktor×10 content×8 limit×6 status×6 respond×4 conn×4 requested×4 request×4 kotlinx×3 code×3
ENSTR*: L65 "<http://test.local/session/ses_1/message?limit=50&before=$nextCursor>; rel=\"ne
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/message/MessageApiCursorTest.kt","loc":92,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"MessageApiCursorTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiImportTest.kt
LOC 45 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | SessionApiImportTest
LEX: session,turn,sse,diff
FREQ: imported×8 conn×5 share×5 equals×4 time×4 title×3
ENSTR*: L18 "Imported Session"
TESTS-EN: `importSession delegates to api and returns Session` | `importSession handles different share URLs`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiImportTest.kt","loc":45,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"SessionApiImportTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiArchiveTest.kt
LOC 42 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | SessionApiArchiveTest
LEX: session,turn,sse,archive
FREQ: time×6 update×6 conn×5 base×4 equals×3
TESTS-EN: `archiveSession calls updateSessionFields with archived true` | `unarchiveSession calls updateSessionFields with archived false`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiArchiveTest.kt","loc":42,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"SessionApiArchiveTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiSearchPaginationTest.kt
LOC 57 | lang 英文 (zh 0/en 2, kdoc 2) | @Test 3 | btFuns 3 | SessionApiSearchPaginationTest
LEX: session,turn,sse,cursor,paginat
FREQ: search×7 limit×6 conn×6 equals×5 server×4 connection×4 parameters×3 localhost×3
C-EN*: L13 Tests that listSessions correctly passes search/cursor/limit
   (+1 trivial en comments)
TESTS-EN: `listSessions passes search query parameter` | `listSessions passes cursor and limit parameters` | `listSessions default parameters remain backward compatible`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SessionApiSearchPaginationTest.kt","loc":57,"lang":"英文","zh":0,"en":2,"kdoc":2,"tests":3,"cls":"SessionApiSearchPaginationTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientReadTimeoutTest.kt
LOC 144 | lang 混合 (zh 7/en 1, kdoc 0) | @Test 13 | btFuns 13 | SseClientReadTimeoutTest
LEX: event,turn,sse,patch
FREQ: tracker×55 timeout×37 cooldown×29 channel×18 consecutive×16 timeouts×16 enter×13 byte×12 equals×10 line×9 duration×9 bytes×8 coroutines×5 client×4
C-ZH: L20 [line]  ============ #108 带超时行读取（半开 TCP 防护） ============ | L24 [line]  无数据且未关闭的 ByteChannel：readByte 永久挂起——半开 TCP | L25 [line]  （kill -9/NAT 静默断）模拟。withTimeoutOrNull 在虚拟时间推进后 | L26 [line]  取消挂起，返回 null 而非永久挂死。 | L113 [line]  2026-08-18 回归（SSE 冷却永续循环）：冷却到期后首个超时不得立即 | L114 [line]  再进冷却——enterCooldown 必须清零连续计数（代价付清重新计数）。 | L119 [line]  冷却后：一次超时不应再触发冷却（需要重新累积到阈值）
C-EN*: L62  ============ SseReadTimeoutTracker ============
TESTS-EN: `readRawLineBytesWithTimeout returns null on silent channel instead of hanging` | `readRawLineBytesWithTimeout returns line when data available` | `readRawLineBytesWithTimeout returns null when channel closed` | `readRawLineBytesWithTimeout handles CRLF` | `tracker starts with zero consecutive timeouts` | `tracker increments on recordTimeout` | `tracker resets on recordSuccess` | `tracker shouldEnterCooldown after maxConsecutiveTimeouts` | `tracker isInCooldown returns false initially` | `tracker enterCooldown sets isInCooldown true` | `tracker enterCooldown resets consecutive timeout count` | `tracker reset clears cooldown and timeouts` | `default constants are correct`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientReadTimeoutTest.kt","loc":144,"lang":"混合","zh":7,"en":1,"kdoc":0,"tests":13,"cls":"SseClientReadTimeoutTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientSessionNextTest.kt
LOC 140 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 8 | btFuns 8 | SseClientSessionNextTest
LEX: session,message,event,part,turn,tool,agent,compaction,sse,context
FREQ: json×22 client×17 started×16 equals×11 build×10 kotlinx×8 serialization×8 shell×7 code×6 delta×6 progress×6 step×6 ended×6 primitive×5
C-EN*: L23  ============ session.next.* type routing ============
ENSTR*: L111 "context full"
TESTS-EN: `parseSessionNextEvent routes agent switched` | `parseSessionNextEvent routes text delta` | `parseSessionNextEvent routes tool progress` | `parseSessionNextEvent routes step started` | `parseSessionNextEvent returns Unknown for unrecognized type` | `parseSessionNextEvent handles shell events` | `parseSessionNextEvent handles compaction events` | `parseSessionNextEvent handles retried`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseClientSessionNextTest.kt","loc":140,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":8,"cls":"SseClientSessionNextTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseOOMProtectionTest.kt
LOC 99 | lang 中文 (zh 22/en 0, kdoc 10) | @Test 8 | btFuns 8 | SseOOMProtectionTest
LEX: event,sse
FREQ: buffer×36 byte×22 append×14 line×14 equals×9 limit×9 mutable×8 payload×5 exceeds×3 boundary×3 accepted×3 zero×3
C-ZH: L8 [kdoc] 测试 [appendDataLine] —— SSE 事件级别的 OOM 保护。 | L10 [kdoc] 使用较小的 [maxEventSize] 参数（而非生产环境中的 1MB 常量）， | L11 [kdoc] 以便在不分配大缓冲区的情况下快速验证边界行为。 | L13 [kdoc] 覆盖范围： | L14 [kdoc] - 正常追加（低于上限） | L15 [kdoc] - 溢出触发清空 + 丢弃 | L16 [kdoc] - 清空后的恢复（下一帧恢复正常） | L17 [kdoc] - 多行累积与分隔符计数 | L18 [kdoc] - 边界：恰好等于上限（应被接受，防止 off-by-one） | L19 [kdoc] - 边界情况：空负载 | L35 [line]  负载大小 3 > maxEventSize 2 → 清空 + 丢弃 | L44 [line]  预计 = 3 | L45 [line]  预计 = 3 + 1 + 5 = 9 | L48 [line]  第三次追加：预计 = 3 + 5 + 1 + 3 = 12 > 10 → 全部清空 | L56 [line]  溢出 → 清空 | L57 [line]  正常恢复 | L65 [line]  第 1 行：预计 = 0 + 2 + 0 = 2（缓冲区为空，无分隔符） | L67 [line]  第 2 行：预计 = 2 + 2 + 1 = 5（缓冲区已有 1 行 → +1 分隔符） | L69 [line]  第 3 行：预计 = 4 + 2 + 2 = 8（缓冲区已有 2 行 → +2 分隔符） | L78 [line]  预计 = 0 + 3 + 0 = 3，maxEventSize = 3 → 未超过（>），边界被接受 | L88 [line]  预计 = 0 + 0 + 0 = 0 ≤ 1 → 追加 | L95 [line]  预计 = 0，maxEventSize = 0 → 0 > 0 为 false → 追加
TESTS-EN: `appends payload when under limit` | `clears buffer and drops payload when single payload exceeds limit` | `clears buffer when accumulated size exceeds limit` | `resumes normally after clear` | `accumulates multiple lines with separator accounting` | `boundary exactly at limit is accepted` | `empty payload under limit is appended` | `empty payload on empty buffer with zero limit is accepted`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/SseOOMProtectionTest.kt","loc":99,"lang":"中文","zh":22,"en":0,"kdoc":10,"tests":8,"cls":"SseOOMProtectionTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClientTest.kt
LOC 84 | lang 中文 (zh 5/en 0, kdoc 3) | @Test 3 | btFuns 3 | V1ApiClientTest
LEX: session,message,part,turn,sse,cursor,page
FREQ: client×18 json×16 engine×15 ktor×8 headers×8 content×5 error×5 server×4 respond×4 status×4 code×4 conn×4 build×4 found×4
C-ZH: L20 [kdoc] V1ApiClient 端点测试（#87 回归）： | L21 [kdoc] - listMessages 非 2xx（404 会话不存在）→ 返回空页而非 JsonConvertException | L22 [kdoc]   （旧代码把 404 JSON 错误体按 List 解析 → 压测实测 302 次异常刷日志） | L43 [line]  回归（#87）：L2 stale 轮询已删除会话 → 404 错误体 {"name":"NotFoundError",...} | L44 [line]  旧代码 body<List>() 解析对象 → JsonConvertException（每 5 秒一次，302 次/25 分钟）
ZHSTR: L55 "404 应返回空消息列表"
ENSTR*: L48 """{"name":"NotFoundError","data":{"message":"Session not found: ses_gone"}}"""
TESTS-EN: `listMessages returns empty page on 404 instead of JsonConvertException` | `listMessages returns empty page on server error` | `listMessages parses array response normally`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v1/V1ApiClientTest.kt","loc":84,"lang":"中文","zh":5,"en":0,"kdoc":3,"tests":3,"cls":"V1ApiClientTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/sse/parsers/SseEventParserTest.kt
LOC 934 | lang 混合 (zh 24/en 5, kdoc 11) | @Test 60 | btFuns 60 | SseEventParserTest,variant
LEX: session,message,event,part,turn,token,tool,agent,subagent,directory,permission,provider,sse,abort,retry,busy,idle,diff,task,context,fallback,chunk
FREQ: parser×236 json×139 equals×86 updated×55 asked×50 error×41 status×39 created×34 model×33 removed×24 info×24 build×21 delta×19 parent×13
C-ZH: L19 [kdoc] SSE 事件解析器的单元测试。 | L21 [kdoc] 覆盖三个最重要的解析器： | L24 [kdoc] - SessionEventParser：session.status、session.idle、session.created 等 | L35 [line]  ==================== 辅助函数 ==================== | L212 [line]  field 字段可选，默认值为 "text" | L230 [line]  message.removed 缺少字段 → 空字符串（通过 str() 默认值） | L313 [line]  V1：always 是非空字符串列表 → true | L330 [line]  V1：always 是空列表 → false | L364 [line]  完全空的 props —— 仍应产生事件（字段默认为空） | L365 [line]  因为 str() 对缺失键返回 "" | L366 [line]  但让我们测试一个真正损坏的场景： | L367 [line]  实际上，对于空 JsonObject，permission.asked 仍然有效（使用默认值） | L368 [line]  让我们测试一个未被处理的事件类型 | L672 [line]  当 "info" 缺失时，解析器回退为直接将 `props` 用作会话对象 | L685 [line]  ==================== V2 扁平格式 session.created（2026-08-17 Running 恒空修复） ==================== | L687 [kdoc]  2026-08-17：真实抓帧行政格式——model 为对象 {id, providerID, variant}。 | L688 [kdoc]  修复前 `?.jsonPrimitive` 读 model 抛 IllegalArgumentException → | L689 [kdoc]  parse() catch 吞掉整条事件 → 子会话永不注册（任务面板 Running 恒空）。 | L693 [line]  2026-08-17 实测抓帧（V2 next-17498，服务器派发 subagent 时广播） | L741 [kdoc]  兼容路径：model 为纯字符串（旧格式）仍可解析。 | L758 [kdoc]  防御：model 类型异常（数组）不抛异常、不丢弃整条事件。 | L776 [kdoc]  session.updated 同走 decodeSessionCompat——对象 model 也不得丢事件。 | (+2 more)
C-EN*: L22 - MessageEventParser：message.updated、message.removed、message.part.* | L23 - PermissionEventParser：permission.asked、permission.replied | L40  ==================== MessageEventParser ==================== | L239  ==================== PermissionEventParser ==================== | L390  ==================== SessionEventParser ====================
ZHSTR: L695 """{
                "sessionID": "ses_child_1",
                "slug": "proud-comet",
   | L709 "model 为对象时不得丢弃整条事件" | L713 "子代理跑 sleep 40" | L719 "startedAt 依据：created 时间戳必须 > 0" | L781 """{
                "sessionID": "ses_child_1",
                "parentID": "ses_parent_1 | L791 "更新后的标题"
ENSTR*: L66 """{
                "info": {
                    "id": "msg_1",
               | L88 """{
                "info": {
                    "id": "msg_2",
               | L110 """{"sessionID": "sess_1", "messageID": "msg_1"}""" | L124 """{
                "part": {
                    "id": "p1",
                  | L146 """{
                "sessionID": "s1",
                "messageID": "m1",
      | L169 """{
                "sessionID": "s1",
                "messageID": "m1",
      | L196 """{
                "info": {
                    "id": "msg_1",
               | L214 """{
                "sessionID": "s1",
                "messageID": "m1",
      | (+18 more)
TESTS-EN: `MessageEventParser canParse returns true for handled types` | `MessageEventParser canParse returns false for unrelated types` | `MessageEventParser parse message_updated with user role` | `MessageEventParser parse message_updated with assistant role` | `MessageEventParser parse message_removed` | `MessageEventParser parse message_part_updated with text part` | `MessageEventParser parse message_part_delta` | `MessageEventParser parse message_part_removed` | `MessageEventParser parse returns null for missing info in message_updated` | `MessageEventParser parse returns null for unknown role` | `MessageEventParser parse message_part_delta with default field` | `MessageEventParser parse handles missing optional fields gracefully` | `PermissionEventParser canParse returns true for handled types` | `PermissionEventParser canParse returns false for unrelated types` | (+46 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/sse/parsers/SseEventParserTest.kt","loc":934,"lang":"混合","zh":24,"en":5,"kdoc":11,"tests":60,"cls":"SseEventParserTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2FrameTest.kt
LOC 63 | lang 中文 (zh 10/en 0, kdoc 5) | @Test 5 | btFuns 5 | SseClientV2FrameTest
LEX: event,turn,stream,sse
FREQ: frame×21 client×11 byte×6 channel×6 heartbeat×4 equals×3 json×3
C-ZH: L10 [kdoc] 2026-08-18 回归（SSE 空闲 40s 断连循环）：readSseFrame 对纯注释帧 | L11 [kdoc] （服务器心跳 ": heartbeat" + 空行边界）必须返回空帧标记 ""—— | L12 [kdoc] 原实现在函数内 continue 吞掉永不返回，外层 withTimeoutOrNull(40s) | L13 [kdoc] 看不到进展 → 空闲期每 40s 必超时断连（beta-17595 实测每 15s 一条心跳， | L14 [kdoc] 断连→重连→recover 全量会话循环开销，且连续 5 次后进 5min 冷却）。 | L25 [line]  beta-17595 实测线格式：": heartbeat" + 空行 | L40 [line]  注释行混入数据帧（非标但防御）：data 帧正常返回 | L52 [line]  event 类型 + \u0000 + data（parseV2Event 兼容路径） | L58 [line]  EOF 无帧内容 → ""（既有语义：外层 while(!isClosedForRead) 循环条件兜底退出， | L59 [line]  与 null 等效；null 保留给「帧中途流断」场景——readRawLineBytes 返回 null）
ZHSTR: L27 "纯注释帧返回空标记（外层刷新存活计时）"
ENSTR*: L50 "event: foo\ndata: {}\n\n"
TESTS-EN: `comment heartbeat frame returns empty marker` | `data frame returns payload normally` | `comment line inside data frame does not corrupt payload` | `event and data frame uses v1 compatible separator` | `stream end without content returns empty marker`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/SseClientV2FrameTest.kt","loc":63,"lang":"中文","zh":10,"en":0,"kdoc":5,"tests":5,"cls":"SseClientV2FrameTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt
LOC 699 | lang 混合 (zh 59/en 8, kdoc 28) | @Test 37 | btFuns 37 | V2ApiClientTest
LEX: session,message,part,turn,token,agent,directory,question,permission,provider,config,stream,sse,cursor,page,busy,idle,patch,context
FREQ: engine×118 request×90 equals×88 path×78 status×69 body×57 headers×56 content×53 build×53 client×52 json×51 code×46 respond×44 encoded×42
C-ZH: L23 [kdoc] V2ApiClient 端点测试——验证 V2 API 的 URL 路径、请求方法、响应解析。 | L25 [kdoc] 使用 MockEngine 模拟 V2 服务器，真实执行 HTTP 请求/响应周期（L3 真实度）。 | L26 [kdoc] 每个测试验证： | L27 [kdoc] 1. 请求路径正确（/api 前缀） | L28 [kdoc] 2. HTTP 方法正确 | L29 [kdoc] 3. 响应正确解析为域模型 | L68 [line]  回归：opencode 1.18.18 过渡形态对不存在的 /api/* 路径返回 SPA HTML（HTTP 200） | L69 [line]  旧行为：JsonDecodingException "Unexpected JSON token at offset 11..."（用户报错） | L70 [line]  新行为：NonJsonResponseException 携带可读信息 | L137 [line]  2026-08-15 勘误：DELETE /api/session/:id 实测支持（真实会话 204 + | L138 [line]  列表确认删除）——此前用不存在 id 探测 404 误判端点不存在 | L192 [line]  真实响应体（2026-08-14 curl 实证）：{data:{id, sessionID, payload:{text}, delivery}} | L225 [kdoc] 2026-08-19（beta-17595 兼容根治）：prompt body agents 数组被部署版忽略， | L226 [kdoc] agent 切换改走专用端点 POST /api/session/{id}/agent {"agent": name} | L227 [kdoc] （curl 实证 204 + session.agent 持久变化，带/不带 directory 头均生效）。 | L243 [kdoc] #84（2026-08-19 契约实测）：beta-17595 的 PATCH /api/credential 要求 | L244 [kdoc] label 必填——缺省 400 "Missing key at [label]"（API key 连接完全不可用）。 | L245 [kdoc] 带实测 204。 | L263 [kdoc] 2026-08-19（beta-17595 根治二段）：UI 层持有 AgentInfo.name 显示名 | L264 [kdoc] （"Plan"），而服务器 /agent 端点按 id（"plan"）区分大小写匹配—— | L265 [kdoc] E2E 实证原样发送显示名 → session.execution.failed "Agent not found: Plan"。 | L266 [kdoc] resolveAgentId 先拉目录做大小写不敏感双向匹配（name/id → id）。 | (+37 more)
C-EN*: L89  ============ Session ============ | L171  ============ Message ============ | L301  ============ System / Agents ============ | L370  ============ Provider ============ | L402  ============ Permission / Question ============
   (+3 trivial en comments)
ZHSTR: L83 "应抛出 NonJsonResponseException"
ENSTR*: L175 """{"data":[{"type":"user","id":"msg_1","time":{"created":1000},"text":"Hello"}, | L193 """{"data":{"id":"msg_abc123","sessionID":"sess_1","timeCreated":1000,"type":"us | L198 "test message" | L235 "body must contain agent name: \$body" | L375 """{"data":[{"id":"gpt-4","providerID":"openai","name":"GPT-4","limit":{"context | L563 """[{"type":"document","path":"/home/.config/opencode/opencode.jsonc",
          | L581 """{"location":{"directory":"/home"},"data":[
            {"name":"agentmemory", | L604 """{"location":{"directory":"/home"},"data":[
            {"path":".agentmemory/ | (+2 more)
TESTS-EN: `getHealth parses V2 health response with version` | `listSessions throws NonJsonResponseException when server returns HTML` | `listSessions requests correct V2 path and unwraps data array` | `getSession unwraps data wrapper` | `createSession posts to V2 session endpoint` | `deleteSession sends DELETE to V2 api path` | `interruptSession sends POST to V2 interrupt path` | `renameSession posts title to V2 rename path` | `listMessages unwraps V2 data and maps messages` | `prompt posts text to V2 prompt endpoint and parses admission` | `prompt returns null on non-success status` | `switchModel posts to V2 model endpoint` | `switchAgent posts to V2 agent endpoint with agent body` | `setProviderApiKey body includes required label field` | (+23 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2ApiClientTest.kt","loc":699,"lang":"混合","zh":59,"en":8,"kdoc":28,"tests":37,"cls":"V2ApiClientTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2EventParserTest.kt
LOC 173 | lang 中文 (zh 14/en 0, kdoc 1) | @Test 11 | btFuns 11 | V2EventParserTest,cost
LEX: session,message,event,token,compaction,sse,busy,idle,context
FREQ: equals×24 shell×24 usage×19 started×17 status×15 json×15 parser×15 info×12 ended×12 updated×12 unknown×11 delta×10 maps×9 domain×7
C-ZH: L14 [kdoc] V2EventParser 专项测试——execution 生命周期与 shell 事件映射。 | L49 [line]  V2 实测：服务器广播 shell.created（旧事件名），payload {info: Shell.Info} | L78 [line]  新命名事件（兼容路径）：{shell: Shell.Info} | L91 [line]  实测（2026-08-11）：data 可能是数组（多条指令）——jsonObject 扩展会抛异常 | L104 [line]  2026-08-15：session.usage.updated 已识别（此前 Unknown 丢弃）—— | L105 [line]  实测 payload {sessionID, cost, tokens:{...}} → UsageUpdated | L106 [line]  （顶部 context 指示器实时数据源） | L121 [line]  防御：cost 为对象（历史样本形态）或 tokens 缺失时不抛异常 | L134 [line]  2026-08-19：beta-17639 细粒度压缩事件——.ended = 服务器真实完成信号， | L135 [line]  映射 SessionCompacted（驱动完成 snackbar + 消息刷新链），而非 | L136 [line]  SessionNext(CompactionEnded)（那是 HTTP 回调合成注入的类型， | L137 [line]  复用会导致"本地幂等结束"冒充"真实完成"触发 premature snackbar） | L149 [line]  2026-08-19：压缩摘要流式增量（此前落入 Unknown——Unhandled 日志噪音）。 | L150 [line]  beta-17639 E2E 实测：增量文本在 "text" 字段（V1 域事件用 "delta"）。
ZHSTR: L30 "应为 SessionStatus，实际 ${event!!::class.simpleName}" | L43 "应为 SessionIdle，实际 ${event!!::class.simpleName}" | L55 "应为 ShellJobStarted，实际 ${event!!::class.simpleName}" | L69 "应为 ShellJobEnded，实际 ${event!!::class.simpleName}" | L143 "应为 SessionCompacted，实际 ${event!!::class.simpleName}"
ENSTR*: L81 """{"sessionID":"ses_1","shell":{"id":"sh_2","status":"running","command":"npm t
TESTS-EN: `execution started maps to SessionStatus Busy` | `execution succeeded maps to SessionIdle` | `shell created maps to ShellJobStarted` | `shell exited maps to ShellJobEnded with exit code` | `session shell started maps to ShellJobStarted` | `instructions updated maps to Unknown without throwing` | `usage updated maps to UsageUpdated with tokens` | `usage updated with object cost or missing tokens does not throw` | `compaction ended maps to SessionCompacted` | `compaction delta maps to CompactionDelta with text field` | `unhandled event falls back to SessionNext Unknown`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2EventParserTest.kt","loc":173,"lang":"中文","zh":14,"en":0,"kdoc":1,"tests":11,"cls":"V2EventParserTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2MappersTest.kt
LOC 354 | lang 混合 (zh 14/en 4, kdoc 2) | @Test 25 | btFuns 25 | V2MappersTest,root
LEX: session,message,part,turn,token,tool,agent,subagent,directory,provider,sse,cursor,page,task,archive,fallback,notification
FREQ: json×86 equals×45 state×22 model×21 response×20 mapper×19 content×19 time×18 completed×17 metadata×17 created×15 html×15 assistant×14 child×13
C-ZH: L12 [kdoc] V2 JSON → 域模型映射测试。 | L13 [kdoc] 验证 V2SessionMapper 和 V2MessageMapper 的字段映射正确性。 | L121 [line]  V2 用户消息文本映射为 Part.Text | L235 [line]  V2 subagent 工具实际结构（REST 实测）：metadata.sessionID 是子会话 ID | L252 [line]  关键断言 1：metadata 必须包含子会话 ID（TaskToolCard 跳转依赖） | L255 [line]  双写兼容（V2 大写 / V1 小写） | L258 [line]  关键断言 2：input 必须包含 description（TaskToolCard 显示描述依赖） | L261 [line]  关键断言 3：output 必须包含工具输出（TaskToolCard 显示输出依赖） | L267 [line]  #180（2026-08-21 宿主机 SSE 抓帧实证）：subagent Running 期 metadata | L268 [line]  可能以 childID 命名（synthetic 消息同源 {source:"subagent", childID,...}） | L269 [line]  ——归一后 Running 态也要能拿到 sessionId/sessionID 双写（卡片跳转依赖） | L287 [line]  V1 风格 metadata 键名（sessionId 小写）也应兼容 | L303 [line]  ============ HTML 防御（SPA fallback 回归） ============ | L307 [line]  回归：1.18.18 过渡形态对不存在的 /api/* 路径返回 <!doctype html>（HTTP 200）
C-EN*: L53  ============ V2SessionMapper ============ | L109  ============ V2MessageMapper ============ | L146  3 content items → 3 parts
   (+1 trivial en comments)
ZHSTR: L236 """
            {"type":"assistant","id":"msg_a5","time":{"created":1000},
             "a | L259 "验证功能" | L262 "验证完成" | L311 "应抛出 NonJsonResponseException"
ENSTR*: L57 """
            {"id":"sess_123","projectID":"prj_1","title":"Test Session",
    | L69 "Test Session" | L84 """
            {"id":"sess_min","time":{"created":0,"updated":0},"location":{"d | L100 """
            {"id":"sess_1","time":{"created":1000,"updated":2000,"archived": | L129 """
            {"type":"assistant","id":"msg_a1","time":{"created":1000},
      | L163 """
            {"type":"system","id":"msg_s1","time":{"created":1000},"text":"S | L198 """
            {"type":"assistant","id":"msg_a2","time":{"created":1000},
      | L209 """
            {"type":"assistant","id":"msg_a3","time":{"created":1000},
      | (+4 more)
TESTS-EN: `unwrap extracts data field from V2 response` | `unwrap returns root when no data field` | `unwrapList extracts data array and cursor next` | `unwrapList handles empty data array` | `toSession maps all V2 session fields` | `toSession handles minimal session with only required fields` | `toSession maps archived time` | `toMessageWithParts maps user message with text` | `toMessageWithParts maps assistant message with content array` | `toMessageWithParts maps system message` | `toMessageWithParts maps synthetic message` | `toMessageWithParts returns null for missing type` | `toMessageWithParts returns null for missing id` | `toMessageWithParts handles assistant with empty content array` | (+11 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2MappersTest.kt","loc":354,"lang":"混合","zh":14,"en":4,"kdoc":2,"tests":25,"cls":"V2MappersTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2SseMapperTest.kt
LOC 285 | lang 混合 (zh 18/en 2, kdoc 1) | @Test 18 | btFuns 18 | V2SseMapperTest,contract
LEX: session,message,event,part,turn,token,tool,agent,subagent,provider,sse,abort,snapshot,archive,pending,queue,notification
FREQ: equals×37 assistant×34 user×33 updated×30 mapper×20 inbox×18 delta×16 asst×15 state×14 json×14 input×14 completed×11 model×10 admitted×9
C-ZH: L18 [kdoc] V2SseMapper 映射测试——用 2026-08-11 实测样本（docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §3.2）。 | L42 [line]  2026-08-14 过渡契约（官方 schema 实证，next-171xx）： | L58 [line]  2026-08-14 最新契约（实测抓帧，next-17403+）： | L74 [line]  必须有 inboxID——字段缺失时不播种（避免空 id 幽灵消息） | L84 [line]  2026-08-15 修复：subagent/后台任务完成通知同样经 inbox.enqueued 投递 | L85 [line]  （实测 item.type="synthetic"，body 含 <subagent ...>子代理输出全文， | L86 [line]  可达数 KB）。修复前播种 role 默认 "user" → 通知渲染成 user 气泡 | L87 [line]  （用户看到"多出大段用户回复"/"assistant 内容进 user 气泡"）。 | L88 [line]  修复后 role="synthetic" → SyntheticNotificationCard 通知卡片。 | L102 [line]  2026-08-15 修复：ordinal 缺失兜底 0（原实现 return null 丢弃整条 | L103 [line]  delta 事件——流式内容缺失成因之一；且原两行重复为复制粘贴错误） | L115 [line]  新契约必须有 id——字段缺失时不应播种（避免空 id 幽灵消息） | L139 [line]  2026-08-14 抓帧实证：新版 model 是 {id, providerID, variant} 对象 | L153 [line]  2026-08-14 抓帧实证：{finish, cost, tokens:{input,output,reasoning,cache:{read,write}}} | L237 [line]  双写 sessionId/sessionID（subagent 子会话跳转兼容） | L274 [line]  2026-08-15（对齐官方 TUI data.tsx:224-235）：step.ended 是消息级完成 | L275 [line]  边界——置 time.completed 与 finish（原断言 null 是旧语义：依赖 | L276 [line]  REST 兜底导致"消息永不完成/耗时缺失"窗口）
C-EN*: L43  {admittedSeq, id, sessionID, prompt:{text,files,agents}, delivery, timeCreated} | L59  session.inbox.enqueued {sessionID, inboxID, item:{type, payload:{text,agents}, delivery}}
ZHSTR: L46 """{"admittedSeq":1,"id":"msg_user_new","sessionID":"ses_1","prompt":{"text":"新契约消息","file | L53 "新契约消息" | L62 """{"sessionID":"ses_1","inboxID":"msg_inbox_1","item":{"type":"user","payload":{"text":"i | L69 "inbox消息" | L77 """{"sessionID":"ses_1","item":{"type":"user","payload":{"text":"无id"}}}""" | L91 """{"sessionID":"ses_1","inboxID":"msg_inbox_s","item":{"type":"synthetic","payload":{"tex | L118 """{"admittedSeq":1,"sessionID":"ses_1","prompt":{"text":"无id"}}"""
ENSTR*: L198 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"text":"Fu | L225 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_2",
        | L252 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_3",
        | L267 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","finish":"tool-calls",
TESTS-EN: `input admitted seeds user message` | `input admitted seeds user message with new contract (id + prompt)` | `inbox enqueued seeds user message with latest contract` | `inbox enqueued with missing inboxID returns null` | `inbox enqueued with synthetic type seeds synthetic role not user` | `text delta with missing ordinal defaults to zero` | `input admitted with new contract but missing id returns null` | `step started creates assistant message` | `step started parses new model object contract (id + providerID)` | `step ended parses tokens` | `text delta uses derived partId` | `reasoning started creates part with derived id` | `text ended overwrites with authoritative text` | `tool input started creates pending tool part` | (+4 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2SseMapperTest.kt","loc":285,"lang":"混合","zh":18,"en":2,"kdoc":1,"tests":18,"cls":"V2SseMapperTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2FormMapperTest.kt
LOC 238 | lang 混合 (zh 13/en 11, kdoc 1) | @Test 14 | btFuns 14 | V2FormMapperTest
LEX: session,message,event,turn,tool,question,permission,sse
FREQ: form×51 json×46 equals×45 label×25 asked×25 answer×23 answers×23 mapper×21 rice×17 created×16 options×14 build×14 description×13 body×13
C-ZH: L161 [line]  ============ 2026-08-17 自定义输入变 skip 根治 ============ | L165 [line]  主干契约 question.v2.asked 无 key 字段——此前 key=null 导致 buildJsonAnswerMap | L166 [line]  全跳过 → 空 answers → 服务器 QuestionTool 输出 "Unanswered"（=跳过）。 | L167 [line]  修复：按题目序号合成 key（q0/q1...），与 form 版 field key 命名一致。 | L185 [line]  question.v2.asked + 自定义输入：key 合成后 buildJsonAnswerMap 不再返回空 map | L200 [line]  官方契约（Question.Reply + TUI submit()）：answers 按题目顺序、未答题补 [] 占位， | L201 [line]  自定义文本原文作为数组项——不经 label→value 转换（question.v2 语义就是 label） | L213 [line]  多选答案必须原样数组（此前 mapNotNull { as? JsonPrimitive } 会整题丢弃 → 错位） | L224 [line]  question.v2 主路径传 label 原文（form 契约才传 option.value）—— | L225 [line]  formCreatedJson 中 value==label，构造 value≠label 的场景验证不转换 | L227 [line]  提交 label "rice"（form 版 value 恰好也是 rice）；换自定义场景更直接： | L228 [line]  预定义 label 的原样透传（value 不参与） | L234 [line]  对照：buildJsonAnswerMap（form 契约）会转 value——两者语义分离
C-EN*: L23  Real form.created frame (2026-08-14 capture, two questions: single + multi) | L150  V1 style (no key) -> no answer constructed (V1 uses /api/question old path)
   (+9 trivial en comments)
ENSTR*: L24 """{"form":{"id":"frm_00033307b001qKsOZcHoEWf9Q9","sessionID":"ses_fffccfb23ffeu | L171 """{"id":"qus_1","sessionID":"ses_1","questions":[
                    {"questio | L189 """{"id":"qus_1","sessionID":"ses_1","questions":[
                    {"questio
TESTS-EN: `form created maps to QuestionAsked with keys and option values` | `form created with non-question kind is ignored` | `form replied maps to QuestionReplied` | `form cancelled maps to QuestionRejected` | `unrelated event returns null` | `form request REST maps to QuestionRequest DTO` | `build answer body maps labels to values with keys` | `build answer body falls back to label for custom input` | `build answer body skips questions without key` | `question v2 asked assigns synthetic keys by question order` | `question v2 synthetic keys make answer map carry custom input` | `ordered label answers keeps custom input and pads unanswered with empty array` | `ordered label answers keeps multiple selections as arrays` | `ordered label answers uses raw labels not option values`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/v2/V2FormMapperTest.kt","loc":238,"lang":"混合","zh":13,"en":11,"kdoc":1,"tests":14,"cls":"V2FormMapperTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/version/ApiVersionDetectorTest.kt
LOC 334 | lang 中文 (zh 26/en 0, kdoc 4) | @Test 14 | btFuns 14 | ApiVersionDetectorTest
LEX: turn,sse,page,fallback
FREQ: version×91 engine×48 health×44 detector×43 headers×38 request×35 respond×34 status×34 code×34 json×29 equals×28 content×22 healthy×21 unknown×18
C-ZH: L19 [kdoc] ApiVersionDetector 测试——验证版本探测逻辑： | L20 [kdoc] V2 服务器 → 检测为 V2 | L21 [kdoc] V1 服务器 → 检测为 V1 | L22 [kdoc] 两者均不可达 → UNKNOWN（非 V1；checkHealth 保留原 apiVersion） | L79 [line]  2026-08-14 修复（#132 联动）：两端探测都失败 → UNKNOWN（非 V1）。 | L80 [line]  旧行为默认 V1 会让 checkHealth 把已知 V2 服务器降级为 V1 → 后续 | L81 [line]  V1 路径请求打到 V2 SPA fallback → HTML 解析错误 + SSE 假死。 | L82 [line]  UNKNOWN 语义：healthy=false + checkHealth 保留原 apiVersion。 | L86 [line]  ============ #150 方案 B（2026-08-21）：按已知版本排序探测 ============ | L105 [line]  核心断言：V1 已知时先探 /global/health 且一次即中——不再白跑 /api/health | L115 [line]  旧 V1 端点已不可用（V2 服务器对未知路径返回 SPA HTML/404） | L131 [line]  升级场景：V1 探测失败（HTML 防御拒绝）→ 回退 V2 探测 → 当次纠正 | L159 [line]  未知版本维持原 V2-first 顺序；全失败 → UNKNOWN（#132 语义） | L194 [line]  V2 healthy=false → V2 探测失败 → 回退 V1 | L200 [line]  回归测试：opencode 1.18.18 过渡形态同时暴露 /api/health 与 /global/health， | L201 [line]  /api/health 返回 {"healthy":true,"version":"1.18.18"}。 | L202 [line]  旧逻辑只看 healthy → 误判 V2 → V2ApiClient 请求不存在的 /api/* 路径 → HTML 崩溃。 | L203 [line]  新逻辑：版本交叉验证 version=1.18.18 → 不是 2.x → 回退 V1。 | L227 [line]  实测形态：opencode 1.18.18 的 /api/health 只返回 {"healthy":true}（无 version）。 | L228 [line]  无版本信息 → 不能判定为 V2 → 回退 V1。 | L251 [line]  防御：SPA fallback 返回 text/html 页面（如 <!doctype html>）。 | L252 [line]  content-type 非 JSON → V2 探测失败 → 回退 V1。 | (+4 more)
TESTS-EN: `detects V2 when api health responds` | `detects V1 when api health fails but global health works` | `falls back to UNKNOWN when both endpoints fail` | `known V1 probes global health first - single RTT for V1 servers` | `known V1 but server upgraded to V2 falls back and corrects in same connection` | `known V2 keeps api health first and unknown defaults to V2-first` | `ApiVersion fromVersionString parses major version` | `V2 health response with healthy=false triggers V1 fallback` | `1x server exposing api health responds but version is 1x → detected as V1` | `api health without version field → fallback to V1` | `api health returns HTML page → not V2, fallback to V1` | `api health returns 200 JSON but body is not parseable → fallback to V1` | `V2 prerelease with 0 0 0-next version and pid field → detected as V2` | `V2 without version but with pid field → detected as V2`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/api/version/ApiVersionDetectorTest.kt","loc":334,"lang":"中文","zh":26,"en":0,"kdoc":4,"tests":14,"cls":"ApiVersionDetectorTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/ErrorReportServiceTest.kt
LOC 115 | lang 中文 (zh 8/en 0, kdoc 2) | @Test 8 | btFuns 8 | ErrorReportServiceTest
LEX: session,turn,token,sse,retry,context,fingerprint
FREQ: report×21 service×21 client×15 issue×14 error×14 search×7 success×7 store×6 starts×6 comment×6 outcome×6 block×6 equals×5 norm×5
C-ZH: L13 [kdoc] #151 主测试缝：错误上报服务边界（GitHubApiClient 伪造）。 | L14 [kdoc] spec §Testing：指纹双轨、查重命中→评论 / 未命中→建 issue、24h 防刷、正文构建。 | L31 [line]  ---- 指纹纯函数（spec §指纹与查重） ---- | L35 [line]  数字/路径/十六进制 id 替换后，语义相同的不同实例应得同一指纹（跨版本查重前提） | L49 [line]  ---- 查重编排 ---- | L91 [line]  ---- 正文构建 ---- | L100 [line]  最后 20 错误 = i in 1..60 step 5 的后 20 个 → 全部 12 个错误（60/5=12 < 20） | L103 [line]  上下文存在且未标记
ENSTR*: L36 "Connection to 10.0.2.2:4199 failed after /api/session/ses_abc12345 retry" | L37 "Connection to 192.168.1.5:8080 failed after /api/session/ses_def99999 retry"
TESTS-EN: `error fingerprint normalizes digits paths hex ids` | `crash fingerprint isolates by version` | `search miss creates new issue with user-report prefix and label` | `search hit appends comment` | `second report within 24h is suppressed` | `search failure falls back to create - never blocks report` | `log section takes last 20 errors with 3-around context marked` | `machine block is fenced json with fingerprint`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/ErrorReportServiceTest.kt","loc":115,"lang":"中文","zh":8,"en":0,"kdoc":2,"tests":8,"cls":"ErrorReportServiceTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/GitHubApiClientTest.kt
LOC 60 | lang 中文 (zh 1/en 0, kdoc 1) | @Test 6 | btFuns 6 | GitHubApiClientTest
LEX: turn,sse,fingerprint
FREQ: client×14 status×9 code×7 body×6 issue×6 ktor×5 engine×5 json×5 search×5 equals×4 unauthorized×3 create×3
C-ZH: L13 [kdoc]  #151 测试缝 2：GitHub API 客户端（Ktor MockEngine）——三端点请求形状与错误映射。
TESTS-EN: `search parses hit from items` | `search zero results returns null` | `401 maps to Unauthorized` | `403 maps to RateLimited` | `createIssue parses number` | `addComment success on 201`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/github/GitHubApiClientTest.kt","loc":60,"lang":"中文","zh":1,"en":0,"kdoc":1,"tests":6,"cls":"GitHubApiClientTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/DatabaseRecoveryTest.kt
LOC 130 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 10 | btFuns 0 | DatabaseRecoveryTest
LEX: turn,sse,context
FREQ: exception×43 sqlite×41 database×28 recovery×23 delete×18 corrupt×14 thrown×14 corruption×10 equals×9 android×7 disk×7 catching×7 cause×6 propagates×6
C-ZH: L43 [line]  Room/框架可能在 cause 链中包装 CorruptException | L56 [line]  多层包装：IllegalState → SQLiteException → SQLiteDatabaseCorruptException | L65 [line]  外层非 SQLiteException 不被 catch 捕获，原样抛出 | L72 [line]  基类 SQLiteException（非 CorruptException 子类）不触发删库
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/DatabaseRecoveryTest.kt","loc":130,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":10,"cls":"DatabaseRecoveryTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/InjectedPartDeserializationTest.kt
LOC 34 | lang 中文 (zh 2/en 0, kdoc 2) | @Test 1 | btFuns 1 | InjectedPartDeserializationTest
LEX: session,message,part,tool,agent,subagent,sse
FREQ: json×6 state×4 payload×3 host×3
C-ZH: L10 [kdoc] 验证注入的 tool part payload 能被项目 Json 配置反序列化。 | L11 [kdoc] （定位按钮测试的假数据——之前注入失败疑为反序列化问题）
ZHSTR: L25 """
            {"id":"part_fake_host3","type":"tool","sessionID":"ses_0115b9cc8ffe9uQYuP9
ENSTR*: L32 "tool=${tool.tool} state=${tool.state::class.simpleName}"
TESTS-EN: `injected subagent tool part deserializes`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/InjectedPartDeserializationTest.kt","loc":34,"lang":"中文","zh":2,"en":0,"kdoc":2,"tests":1,"cls":"InjectedPartDeserializationTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/LogStoreTest.kt
LOC 68 | lang 中文 (zh 3/en 0, kdoc 0) | @Test 4 | btFuns 0 | LogStoreTest
LEX: message,turn,sse,context
FREQ: store×17 byte×9 equals×7 batch×7 insert×7 delete×6 database×5 retention×5 limit×5 prune×5 recovery×4 ordinary×3 error×3 entity×3
C-ZH: L15 [line]  真实恢复组件（mockk Context 即可）：保证 block 参数被执行， | L16 [line]  现有 dao 交互断言依然有效；损坏场景由 DatabaseRecoveryTest 覆盖。 | L20 [line]  ---- 常量（与旧 DiagnosticLogDatabase 语义等价）----
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/LogStoreTest.kt","loc":68,"lang":"中文","zh":3,"en":0,"kdoc":0,"tests":4,"cls":"LogStoreTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt
LOC 481 | lang 混合 (zh 49/en 1, kdoc 5) | @Test 27 | btFuns 0 | MessageStoreTest
LEX: session,message,part,turn,sse,cursor,context,archive,chunk,upsert
FREQ: store×42 created×37 bucket×34 json×30 info×24 equals×24 entity×24 limit×22 load×21 cached×20 range×20 user×18 oldest×18 buckets×17
C-ZH: L32 [line]  真实恢复组件（mockk Context 即可）：保证 block 参数被执行， | L33 [line]  现有 dao 交互断言依然有效；损坏场景由 DatabaseRecoveryTest 覆盖。 | L35 [line]  storeImpl：internal buildArchiveBuckets 直测用；store：接口类型验证 MessageStore 满足 | L36 [line]  MessageCacheRepository 契约 + 接口默认参数值（persistOldBeyondWindow=false / beforeId=null）生效。 | L43 [line]  RoomDatabase.withTransaction 是顶层 suspend 扩展（room-runtime，facade | L44 [line]  androidx.room.RoomDatabaseKt）；relaxed mock 会把其委托的实例方法桩为 no-op | L45 [line]  而不执行 block → 归档/裁剪交互无法被验证。桩扩展函数本身使其直接调用 block， | L46 [line]  事务体内的 archiveDao/dao 调用回归可验证。 | L47 [line]  注：扩展函数被 mockk 记录时 receiver(database) 也在 args 里（firstArg 是 receiver）， | L48 [line]  故按类型筛出唯一的 Function1（block），稳过按下标取。 | L87 [line]  本地已有 msg_3（created=300）为最旧 → 窗口边界 = 300 | L95 [line]  只 upsert msg_4；msg_1 被跳过（在窗口外） | L114 [line]  无 overflow（countForSession relaxed=0 → total=0 ≤ limit）→ 不裁剪。 | L115 [line]  裁剪仅与归档同事务在 overflow>0 时发生（见 upsertMessages_overflow_*）。 | L144 [line]  当前 1003 条 → overflow=3 | L155 [line]  parts：让每个 msg 的 part 可查（#59：走 chunked 委托） | L160 [line]  归档先于 prune：archiveDao.upsertAll 被调用（批量，事务内），裁剪同事务仅一次。 | L163 [line]  核心顺序不变量：归档必须先于 prune（禁止"prune 后查最老归档"——那时 payload 已删） | L183 [line]  窗口外消息全部跳过 → 不落库 → 不触发归档 | L196 [line]  预编码：构造一个真实归档桶（复用 buildArchiveBuckets 产生的 payload 格式） | L201 [line]  直接构造桶（绕过 DAO）：用 json 手动序列化压缩 | L217 [line]  读取后 touch 更新 lastAccessedAt | (+27 more)
   (+1 trivial en comments)
ZHSTR: L455 "游标之前的桶内消息必须读出（#72）"
ENSTR*: L327 "all buckets must be ≤ ${MessageStore.ARCHIVE_BUCKET_MAX_BYTES} bytes"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/MessageStoreTest.kt","loc":481,"lang":"混合","zh":49,"en":1,"kdoc":5,"tests":27,"cls":"MessageStoreTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ConfigMapperTest.kt
LOC 70 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 5 | btFuns 5 | ConfigMapperTest
LEX: agent,provider,config,sse,patch
FREQ: model×15 equals×12 disabled×10 response×9 mapper×7 small×6 server×5 global×4 code×4 domain×3
TESTS-EN: `toDisabledProviders extracts list` | `toDtoPatch builds correct patch` | `toDtoPatch with nulls preserves defaults` | `toDomain converts ServerConfigResponse to GlobalConfig` | `toDto converts GlobalConfigPatch to ServerConfigPatch`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ConfigMapperTest.kt","loc":70,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":5,"cls":"ConfigMapperTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ToolOutputTruncatorTest.kt
LOC 127 | lang 中文 (zh 8/en 0, kdoc 6) | @Test 11 | btFuns 11 | ToolOutputTruncatorTest
LEX: part,tool,sse
FREQ: payload×27 output×26 truncator×12 truncate×11 needed×11 state×10 repeat×10 input×10 truncated×9 status×7 reasoning×7 equals×6 completed×6 preserved×5
C-ZH: L8 [kdoc] #79 P0（2026-08-18）：tool part 落库截断——payload JSON 层重写 state.output。 | L56 [line]  ============ #79 P1（2026-08-19）：input/metadata 递归原语截断 ============ | L59 [kdoc] write 工具实测形态：state.input.content 为 18.8KB 文件内容——递归截断 | L60 [kdoc] 超长字符串原语，对象结构（键/其他短值）原样保留。 | L75 [kdoc]  edit 工具实测形态：metadata.oldStrings 数组内长字符串——数组结构保留逐项截断。 | L88 [kdoc]  短 input（bash 命令等常态）零触碰——快速路径不白付遍历成本。 | L97 [kdoc]  数字/布尔等非字符串原语不被误改（contentOrNullSafe 只取 isString）。 | L108 [line]  ============ #79 P1：Reasoning text 截断 ============
ZHSTR: L30 "截断后长度远小于原"
TESTS-EN: `short output passes through unchanged` | `long output truncated to preview plus marker` | `non-tool payload without state passthrough` | `malformed json passthrough unchanged` | `other state fields preserved` | `P1 long string primitive inside input truncated structure preserved` | `P1 long strings inside metadata array elements truncated` | `P1 short input untouched passthrough` | `P1 non-string primitives inside input preserved exactly` | `P1 reasoning long text truncated other fields intact` | `P1 reasoning short text passthrough`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ToolOutputTruncatorTest.kt","loc":127,"lang":"中文","zh":8,"en":0,"kdoc":6,"tests":11,"cls":"ToolOutputTruncatorTest"}










═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodecTest.kt
LOC 33 | lang 中文 (zh 3/en 0, kdoc 0) | @Test 3 | btFuns 0 | ZstdCodecTest
LEX: turn,sse
FREQ: original×16 zstd×9 codec×7 compress×5 decompress×5 equals×3 byte×3
C-ZH: L13 [line]  文本重复度高 → 压缩后显著更小 | L29 [line]  zstd-jni 仅当 originalSize 小于实际解压大小时抛异常（目标缓冲区过小）； | L30 [line]  originalSize 偏大时静默返回实际内容（zstd-jni 1.5.7-13 实测）。
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/local/ZstdCodecTest.kt","loc":33,"lang":"中文","zh":3,"en":0,"kdoc":0,"tests":3,"cls":"ZstdCodecTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/VcsMapperTest.kt
LOC 110 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 7 | btFuns 7 | VcsMapperTest
LEX: sse,diff,patch,workspace
FREQ: status×24 file×21 mapper×18 change×11 domain×11 branch×10 equals×8 modified×8 deletions×7 maps×6 additions×6 added×5 model×4 deleted×4
TESTS-EN: `VcsChangeDto status=added maps to VcsStatus ADDED` | `VcsChangeDto status=deleted maps to VcsStatus DELETED` | `VcsChangeDto status=modified maps to VcsStatus MODIFIED` | `VcsChangeDto status=null falls back to MODIFIED` | `VcsBranchDto maps to VcsBranchInfo preserving nulls` | `FileDiffDto maps to VcsFileDiff with patch preserved` | `FileDiffDto file=null maps to empty string`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/VcsMapperTest.kt","loc":110,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":7,"cls":"VcsMapperTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/PermissionMapperTest.kt
LOC 93 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | PermissionMapperTest
LEX: session,message,event,tool,permission,sse
FREQ: domain×20 equals×19 json×10 round×9 original×9 metadata×8 tripped×8 mapper×7 patterns×7 primitive×5 home×5 bash×4 request×3 kotlinx×3
TESTS-EN: `toDomain maps all fields correctly` | `toDomain maps empty always to false` | `toDto maps all fields correctly` | `round-trip toDomain then toDto preserves semantic meaning`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/PermissionMapperTest.kt","loc":93,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"PermissionMapperTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ProviderMapperTest.kt
LOC 37 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | ProviderMapperTest
LEX: provider,sse
FREQ: response×7 openai×6 anthropic×6 mapper×5 equals×4 connected×4 model×3 selection×3
TESTS-EN: `toProviderNameMap creates id-name mapping` | `toConnectedProviderIds extracts connected set` | `toModelSelection creates correct selection`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/ProviderMapperTest.kt","loc":37,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"ProviderMapperTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/FileMapperTest.kt
LOC 108 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 6 | btFuns 6 | FileMapperTest
LEX: directory,sse
FREQ: file×40 content×17 mapper×14 node×12 domain×10 code×8 path×8 absolute×8 equals×7 maps×5 home×5 opencode×5 project×5 model×4
TESTS-EN: `FileNodeDto type=file maps to FileType FILE` | `FileNodeDto type=directory maps to FileType DIRECTORY` | `FileNodeDto unknown type falls back to FILE` | `FileNodeDto absolute=null maps to empty string` | `FileContentDto type=text maps to ContentType TEXT` | `FileContentDto type=binary maps to ContentType BINARY with mimeType`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/FileMapperTest.kt","loc":108,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":6,"cls":"FileMapperTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/QuestionMapperTest.kt
LOC 104 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | QuestionMapperTest
LEX: session,event,tool,question,sse
FREQ: equals×16 domain×14 mapper×7 options×7 asked×7 round×7 original×7 option×6 multiple×6 tripped×6 model×5 header×4 response×3 request×3
ENSTR*: L77 "Pick tools"
TESTS-EN: `toDomain maps all fields correctly` | `toDto maps all fields correctly` | `round-trip preserves all data` | `empty questions list maps correctly`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/mapper/QuestionMapperTest.kt","loc":104,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"QuestionMapperTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/AgentRepositoryImplTest.kt
LOC 21 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 1 | btFuns 1 | AgentRepositoryImplTest
LEX: agent,sse
FREQ: repository×6 file×5 system×5 repo×4 impl×3 relaxed×3 server×3
TESTS-EN: `impl creates successfully`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/AgentRepositoryImplTest.kt","loc":21,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":1,"cls":"AgentRepositoryImplTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImplTest.kt
LOC 207 | lang 混合 (zh 5/en 6, kdoc 0) | @Test 9 | btFuns 9 | ChatRepositoryImplTest
LEX: session,message,event,part,turn,token,tool,question,permission,provider,config,unread,stream,sse,patch,terminal,pending,upsert,badge
FREQ: handler×37 flow×24 repo×18 repository×17 relaxed×16 equals×16 store×12 server×11 state×9 domain×7 auto×7 approver×7 kotlinx×7 coroutines×7
C-ZH: L51 [line]  单元测试不接入 Room；种子化读到空 list（不触发 upsert），保持原测试语义 | L76 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L79 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L107 [line]  冷启动场景：内存热视图空，Room 有缓存 → 种子化后消息立即可见 | L114 [line]  种子化副作用：内存热视图被填充（后续订阅不再读 Room）
C-EN*: L87  ============ getMessagesFlow ============ | L118  ============ getPermissionsFlow ============ | L147  ============ getQuestionsFlow ============ | L176  ============ getToolExpandedStates ============ | L184  ============ sendMessage ============ | L194  Set up session tracking
TESTS-EN: `getMessagesFlow returns messages from dispatcher` | `getMessagesFlow returns empty for unknown session` | `getMessagesFlow seeds memory from Room cache when empty` | `getPermissionsFlow maps events to PermissionState` | `getPermissionsFlow returns empty for unknown session` | `getQuestionsFlow maps events to QuestionState` | `getToolExpandedStates returns map and setToolExpanded works` | `sendMessage returns failure when session not tracked` | `sendMessage calls api when session tracked`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ChatRepositoryImplTest.kt","loc":207,"lang":"混合","zh":5,"en":6,"kdoc":0,"tests":9,"cls":"ChatRepositoryImplTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceConcurrencyTest.kt
LOC 402 | lang 中文 (zh 69/en 0, kdoc 37) | @Test 9 | btFuns 9 | SessionStateServiceConcurrencyTest
LEX: session,event,part,turn,directory,provider,stream,sse,cursor,paginat,busy,idle,patch,dedup,merge,history
FREQ: service×67 latch×28 status×26 scope×24 client×22 send×22 current×18 repo×16 clear×16 rest×16 transition×15 validation×14 collab×13 pool×13
C-ZH: L40 [kdoc] SessionStateService 的并发安全测试。 | L42 [kdoc] 记录了竞态条件 RS-010 到 RS-013，并验证修复在并发访问下仍保持正确性。 | L44 [kdoc] 测试策略： | L45 [kdoc]  - 顺序测试验证行为契约（无回归） | L46 [kdoc]  - 并发压力测试用真实线程压测竞态窗口 | L47 [kdoc]  - 确定性测试使用 latch/barrier 强制特定的交错顺序 | L72 [line]  ============ RS-010：applyTransition 原子性 ============ | L75 [kdoc] RS-010 回归测试：并发的 ClientSendParts 和 TextStarted 不得 | L76 [kdoc] 导致 Idle 状态（TextStarted 读取到陈旧的 Idle 时不得覆盖 | L77 [kdoc] ClientSendParts 写入的 Busy 转移）。 | L79 [kdoc] 策略：使用线程池强制产生确定性竞态，N 个线程先发 ClientSendParts | L80 [kdoc] 再发 TextStarted。全部完成后，状态必须是 Busy —— 绝不会是 Idle。 | L125 [kdoc] RS-010 回归测试：一种确定性交错 —— TextStarted 在 ClientSendParts 写入 | L126 [kdoc] Busy 之前读取到 Idle。旧代码中，TextStarted 的 `.update{}` 会用 Idle | L127 [kdoc] 覆盖 Busy（因为在 Idle 状态下的 activity 事件返回 `isSuspicious=true` | L128 [kdoc] 并保持状态不变）。 | L130 [kdoc] 修复后：`.update{}` 会重试 read-compute-write，因此 TextStarted 能看到 | L131 [kdoc] ClientSendParts 写入的 Busy 状态。 | L137 [line]  顺序基线 —— 应当总是通过 | L147 [kdoc] 压力测试：跨多个会话的大量并发转移不应破坏 FSM map 或丢失转移。 | L163 [line]  #122 D2-15 后：重复同事件在稳定态只剩时间戳变化（被短路， | L164 [line]  不记 history）——压测改用交替真实转移（Idle↔Busy），每事件 | (+47 more)
ENSTR*: L118 "After ClientSendParts, status must be Busy regardless of concurrent TextStarted | L186 "Session s$i should have $transitionsPerSession history entries" | L245 "Status should be either Busy or null (consistent), was $status" | L308 "FSM should reflect the latest validation result (Idle)"
TESTS-EN: `RS-010 concurrent ClientSendParts then TextStarted never loses Busy state` | `RS-010 sequential ClientSendParts then TextStarted produces Busy Streaming` | `RS-010 multi-session concurrent transitions are all recorded in history` | `RS-011 clearAll concurrent with applyTransition does not resurrect cleared state` | `RS-011 clearSession and clearAll together leave empty state` | `RS-012 concurrent triggerRestValidation applies only latest result` | `RS-012 dedup is per-session, not global` | `RS-012 completed validation allows subsequent validation for same session` | `RS-013 syncFromRest marks absent Busy session as Idle`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceConcurrencyTest.kt","loc":402,"lang":"中文","zh":69,"en":0,"kdoc":37,"tests":9,"cls":"SessionStateServiceConcurrencyTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogRepositoryTest.kt
LOC 106 | lang 中文 (zh 5/en 0, kdoc 0) | @Test 8 | btFuns 0 | DiagnosticLogRepositoryTest
LEX: session,message,token,config,sse
FREQ: sanitized×21 secret×13 repository×10 diagnostic×10 sanitize×7 code×7 redacts×6 state×6 oauth×5 user×4 exported×4 late×4 authorization×3 cookie×3
C-ZH: L10 [line]  ---- 凭证 / token 脱敏 ---- | L43 [line]  ---- IP 地址脱敏 ---- | L61 [line]  ---- 本地用户路径脱敏 ---- | L79 [line]  ---- 导出二次脱敏 ---- | L100 [line]  ---- 长度限制 ----
ENSTR*: L15 "Authorization: Bearer secret-token password=hunter2 api_key=sk-secret https://e | L29 """
            Authorization: Digest private-value
            Cookie: session= | L73 "loaded C:\\Users\\carol\\secret\\config.json" | L90 "Bearer late-token"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/DiagnosticLogRepositoryTest.kt","loc":106,"lang":"中文","zh":5,"en":0,"kdoc":0,"tests":8,"cls":"DiagnosticLogRepositoryTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceTest.kt
LOC 465 | lang 混合 (zh 83/en 4, kdoc 38) | @Test 19 | btFuns 15 | SessionStateServiceTest
LEX: session,message,event,part,turn,tool,directory,question,permission,provider,stream,sse,cursor,paginat,page,abort,busy,idle,patch,task,pending,fallback,merge,history
FREQ: service×116 repo×59 collab×47 flow×39 scope×39 status×34 current×31 equals×27 rest×23 state×19 repository×18 relaxed×18 collaborator×17 client×16
C-ZH: L23 [kdoc] 夹具说明（与 brief 中字面的 `runTest { this -> ... }` 形式有所偏差）： | L25 [kdoc] SessionStateService 暴露的 flow 使用 `stateIn(appScope, SharingStarted.Eagerly, …)` 构建， | L26 [kdoc] 这些 flow 会在注入的 appScope 中启动协程，且不会自行结束。将 | L27 [kdoc] `runTest` 的 `this`（默认 StandardTestDispatcher 上的 TestScope）传入会导致两个失败： | L28 [kdoc]   1. 时序问题 —— Eagerly 收集器排在测试体之后，导致 `statusFlow.value` | L29 [kdoc]      一直是 `emptyMap()`（AssertionError / NPE）。 | L30 [kdoc]   2. 收尾时的 `UncompletedCoroutinesError` —— 3 个 Eagerly 协程的生命周期超过测试体。 | L32 [kdoc] 修复方式与项目自身的 `ChatViewModelStreamingTest`（UnconfinedTestDispatcher + | L33 [kdoc] advanceUntilIdle）一致：用 UnconfinedTestDispatcher 驱动 appScope 以实现即时传播，并在 | L34 [kdoc] @After 中取消该 scope，使收尾时不再有未完成的协程。所有测试用例与 | L35 [kdoc] 断言均与 brief 保持一致。 | L38 [kdoc] Task 4 夹具修订（staleness 守卫）： | L40 [kdoc] Task 4 在 `init` 中启动了一个永续的 `while(isActive) { delay(STALENESS_CHECK_INTERVAL_MS); ... }` | L41 [kdoc] 协程。探测（见 git 历史）证实 `advanceUntilIdle()` 会在这样的协程上无限循环 | L42 [kdoc] （10 秒 JUnit 超时，虚拟时间推进到约 423 天）。`runCurrent()` | L43 [kdoc] 只运行当前虚拟时间下已排队的任务，不会推进时钟，因此 | L44 [kdoc] 守卫的第一个 delay(5_000) 永远不会到达。所有断言在 `runCurrent()` 下仍然成立，因为： | L45 [kdoc]   - `applyTransition` 是同步的，会立即写入 `_fsmStates`。 | L46 [kdoc]   - `statusFlow` 使用 `stateIn(appScope, SharingStarted.Eagerly, …)`；在 UnconfinedTestDispatcher 下 | L47 [kdoc]     操作符链同步传播，`runCurrent()` 会刷新任何已排队的调度。 | L48 [kdoc]   - `triggerRestValidation` 启动的协程在 relaxed MockK 下没有真正的挂起点 | L49 [kdoc]     （`coEvery` 的 stub 立即返回），因此它会在 `runCurrent()` 期间完成。 | (+61 more)
C-EN*: L138  Idle→Busy + →Streaming | L173  Idle→Busy, →Streaming, →ToolCalling | L303  ============ Task 5：syncFromRest ============
   (+1 trivial en comments)
ENSTR*: L122 "history should be trimmed to <= 20, was ${history.size}" | L149 "timestamp-only deltas must not append history (short-circuited)"
TESTS-ZH: `triggerRestValidation absence with fresh SSE keeps status（2026-08-16 新鲜度护栏）`
TESTS-EN: `ClientSendParts transitions Idle to Busy Waiting in statusFlow` | `SseIdle after Busy triggers forceComplete on messageForceCompleter` | `transition recorded in history` | `history trims to max 20 entries` | `D2-15 timestamp-only events within throttle window are short-circuited` | `D2-15 short-circuit does not swallow real activity transitions` | `clearSession removes state and history` | `triggerRestValidation absence with null directory stays Busy` | `triggerRestValidation zombie Busy with stale lastEventAt forces Idle` | `triggerRestValidation zombie Busy with pending user input skips interrupt` | `triggerRestValidation Busy with recent events stays Busy` | `syncFromRest aggregates multiple directories` | `syncFromRest marks absent non-idle session Idle when no incomplete` | `syncFromRest protects absent session with incomplete messages`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateServiceTest.kt","loc":465,"lang":"混合","zh":83,"en":4,"kdoc":38,"tests":19,"cls":"SessionStateServiceTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/NaturalTurnEndListenerTest.kt
LOC 162 | lang 混合 (zh 9/en 1, kdoc 4) | @Test 9 | btFuns 0 | NaturalTurnEndListenerTest
LEX: session,event,part,turn,provider,sse,cursor,paginat,abort,busy,idle,patch,fallback,dedup
FREQ: service×42 collab×29 fired×27 client×13 natural×11 collaborator×11 status×10 send×10 mutable×9 pair×9 fire×6 repository×5 scope×5 domain×4
C-ZH: L17 [kdoc] 堆积消息推进触发器（2026-08-20 设计定稿）： | L18 [kdoc] 「自然成功 turn 结束」= Busy→Idle 且触发事件 ∈ {SseIdle, SseStatus(Idle)}。 | L19 [kdoc] 其余一切到 Idle 的路径（手动 abort / 错误 / REST 兜底）不得触发； | L20 [kdoc] V1 status/idle 双发的第二发因已 Idle 天然去重。 | L72 [line]  V1 双发：session.status(idle) 先到，deprecated session.idle 后到 | L101 [line]  服务器随后补发的 idle（abortSession 先 cancelSseJob，但兜底场景仍可能到达） | L128 [line]  V2 出错 turn：无终态事件，L2/L3 兜底走 RestValidation(Idle) | L141 [line]  未经历过 Busy 的孤儿 idle 事件 | L156 [line]  推进后新 turn（队首消息发出 → execution.started Busy）
C-EN*: L45  Idle→Busy
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/NaturalTurnEndListenerTest.kt","loc":162,"lang":"混合","zh":9,"en":1,"kdoc":4,"tests":9,"cls":"NaturalTurnEndListenerTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt
LOC 220 | lang 中文 (zh 24/en 0, kdoc 3) | @Test 12 | btFuns 12 | EventDispatcherUnreadTest
LEX: session,message,event,part,turn,token,question,permission,provider,unread,stream,sse,cursor,paginat,snapshot,patch,pending,migrat,merge,upsert,badge
FREQ: completed×54 time×32 handler×28 reply×22 assistant×20 repository×19 created×18 store×16 service×14 domain×12 state×12 settings×12 push×12 scope×11
C-ZH: L37 [kdoc] 未读提示数据源（lastCompletedReplyTime / maxCompleted）测试： | L38 [kdoc] 由 assistant 消息 completed（服务器时刻）更新；用户消息/未完成消息不算； | L39 [kdoc] 增量取 max；无完成消息的会话移除条目。 | L66 [line]  #122 接线新增：自动批准（relaxed mock——shouldAutoApprove 恒 false，既有用例不受影响） | L69 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L104 [line]  runUnreadStateV2Migration 现为 SettingsDataStore 成员方法（合并自扩展文件），可被 mock 拦截记录。 | L105 [line]  EventDispatcher init 在 Dispatchers.IO 异步触发迁移；coVerify(timeout) 等待独立 scope 执行完。 | L133 [line]  更早完成 → 不覆盖 | L135 [line]  更晚完成 → 覆盖 | L142 [line]  REST 整批替换：replaceMessages 以 REST 为真相源合并（保留 SSE 已有消息）→ 重算 max | L146 [line]  整批替换后会话无完成消息 → maxCompleted 移除条目（无完成消息） | L154 [line]  构造前 stub：lastCompletedReplyTimes 返回既有 seed map（模拟重启后 DataStore 既有值）。 | L155 [line]  lastCompletedReplyTimes 现为成员方法（合并自扩展文件），对 relaxed mock 直接 every stub 即可。 | L158 [line]  init 的迁移 + seed 读取在 Dispatchers.IO 异步执行，轮询等待合并完成 | L169 [line]  根因 1 防回归：REST 快照滞后（会话流式中 completed=null）不应移除已记录的 maxCompleted | L172 [line]  模拟 REST 同步拉到流式快照（最后一条 assistant completed=null） | L179 [line]  已记录的 500L 必须保留——暂时的 null 快照不能抹掉已知完成时刻 | L185 [line]  saveLastCompletedReplyTimes 现为 SettingsDataStore 成员方法（合并自扩展文件），可被 mock 拦截记录。 | L186 [line]  processEvent → UnreadBadgeService.persist 同步调用本方法；coVerify 无需等待即可断言 | L187 [line] （同步语义由代码结构保证——非异步 collect）。 | L194 [line]  根因 3 防回归：clearForServer（stopConnection 调用，连接停止）是连接状态清理， | L195 [line]  不应抹掉红点事实数据（服务器最后完成时刻） | (+2 more)
TESTS-EN: `init triggers v2 migration once` | `assistant message with completed updates maxCompleted with server timestamp` | `assistant message without completed does NOT update` | `user message does NOT update` | `later completed overwrites with max` | `replaceMessages recomputes max for session` | `seed restores lastCompletedReplyTime on init` | `recompute with null completed snapshot keeps existing max` | `completed update triggers persist via UnreadBadgeService` | `clearForServer keeps maxCompleted (connection teardown is not deletion)` | `clearAll keeps maxCompleted` | `session deleted removes maxCompleted`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherUnreadTest.kt","loc":220,"lang":"中文","zh":24,"en":0,"kdoc":3,"tests":12,"cls":"EventDispatcherUnreadTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/FileRepositoryImplTest.kt
LOC 144 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 7 | btFuns 7 | FileRepositoryImplTest
LEX: message,turn,directory,sse,workspace,archive
FREQ: file×25 server×17 content×13 repository×12 find×10 files×10 success×9 code×9 exception×9 equals×8 path×7 conn×6 project×6 system×5
TESTS-EN: `listDirectory success maps DTOs and passes directory` | `listDirectory wraps exception as failure` | `getFileContent success injects path` | `getFileContent wraps exception as failure` | `findFiles success passes query limit directory and returns string list` | `findFiles wraps exception as failure` | `findFiles with empty query still delegates to api`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/FileRepositoryImplTest.kt","loc":144,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":7,"cls":"FileRepositoryImplTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherTest.kt
LOC 483 | lang 中文 (zh 29/en 0, kdoc 0) | @Test 32 | btFuns 32 | EventDispatcherTest
LEX: session,message,event,part,token,tool,agent,question,permission,provider,unread,stream,sse,cursor,paginat,busy,idle,patch,task,pending,merge,upsert,badge
FREQ: server×96 process×56 handler×52 equals×23 time×22 created×18 state×16 updated×16 service×14 clear×14 repository×13 scope×12 asked×12 relaxed×10
C-ZH: L64 [line]  #122 接线新增：自动批准（relaxed mock——shouldAutoApprove 恒 false，既有用例不受影响） | L67 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L82 [line]  ============ 事件分发 ============ | L121 [line]  ============ 跨 handler：SessionDeleted 级联清理 ============ | L139 [line]  现在删除会话 | L142 [line]  会话 s1 的所有状态都应被清理 | L151 [line]  ============ 跨 handler：CommandExecuted 重置会话状态 ============ | L157 [line]  通过 SSE 将状态设为 Busy（权威路径） | L164 [line]  P0-4 修复：CommandExecuted 不再强制 Idle —— 由 session.status SSE 事件控制状态 | L168 [line]  ============ 清空操作 ============ | L224 [line]  ============ 委托操作 ============ | L245 [line]  修复（2026-08-10）：APPEND_ONLY（mergeMessages）语义是"合并"——existing 保留 + 补充缺失。 | L246 [line]  原断言 size=1 固化了"替换"bug（分页加载更早消息会丢掉现有最新消息，用户实证底部消息消失）。 | L299 [line]  ============ 初始状态 ============ | L315 [line]  ============ 空操作事件 ============ | L336 [line]  ============ SessionNext 事件集成 ============ | L396 [line]  ============ 多服务器去重（同一后端）============ | L401 [line]  Server1 声明所有权 | L404 [line]  Server2 发送同一会话的更新 —— 应被跳过 | L414 [line]  Server1 声明所有权并发送 delta | L423 [line]  Server2 发送相同 delta —— 应被所有权检查跳过 | L435 [line]  文本必须是 "Hello"（应用一次），而非 "HelloHello"（重复两次） | (+7 more)
TESTS-EN: `processEvent dispatches session events to SessionHandler` | `processEvent dispatches message events to MessageHandler` | `processEvent dispatches permission events to PermissionHandler` | `processEvent dispatches question events to QuestionHandler` | `processEvent dispatches todo events to MiscHandler` | `SessionDeleted cascades cleanup to all handlers` | `CommandExecuted does NOT reset session status to Idle` | `clearAll resets all state` | `clearForServer removes only target server data` | `clearForServer removes messages and parts for server sessions` | `clearForServer with no sessions removes server entry` | `delegated setMessages works` | `delegated mergeMessages works` | `delegated removePermission works` | (+18 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherTest.kt","loc":483,"lang":"中文","zh":29,"en":0,"kdoc":0,"tests":32,"cls":"EventDispatcherTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproveWiringTest.kt
LOC 126 | lang 中文 (zh 8/en 0, kdoc 4) | @Test 3 | btFuns 3 | PermissionAutoApproveWiringTest
LEX: session,message,event,turn,token,tool,directory,question,permission,provider,unread,stream,sse,idle,patch,pending,badge
FREQ: handler×33 repository×15 auto×11 store×10 chat×9 repo×8 rule×7 kotlinx×7 coroutines×7 relaxed×7 domain×6 approve×6 approver×6 asked×6
C-ZH: L31 [kdoc] #122（2026-08-18 接线）：PermissionAutoApprover 此前全库零调用——用户保存的 | L32 [kdoc] 自动批准规则从未生效。验证 EventDispatcher 的 PermissionAsked 分发路径正确 | L33 [kdoc] 消费规则（匹配 → respondPermission；无规则 → 不回复；目录不匹配 → 不回复）。 | L64 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L76 [kdoc]  预置会话（directory 供规则匹配）。 | L95 [line]  autoApproveScope 用真 Dispatchers.IO（生产语义）——虚拟时钟等不到， | L96 [line]  coVerify timeout 真实等待异步回复落地 | L109 [line]  负向断言给真实等待窗口（否则异步分支未跑完就验证 = 假阳性）
TESTS-EN: `matched rule auto-approves permission` | `no rules means no auto reply` | `directory-scoped rule does not match other directory`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproveWiringTest.kt","loc":126,"lang":"中文","zh":8,"en":0,"kdoc":4,"tests":3,"cls":"PermissionAutoApproveWiringTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PendingMessagePipelineTest.kt
LOC 275 | lang 混合 (zh 18/en 1, kdoc 6) | @Test 14 | btFuns 0 | PendingMessagePipelineTest
LEX: session,message,part,turn,agent,directory,provider,sse,busy,idle,patch,pending,queue,dedup
FREQ: pipeline×49 send×37 repo×27 status×25 case×17 prompt×16 flow×16 head×16 peek×15 state×13 delete×12 slot×11 service×9 server×8
C-ZH: L26 [kdoc] 堆积消息推进管线（2026-08-20 设计定稿；2026-08-21 #176/#177 状态补偿扩展， | L28 [kdoc] - peek → POST → 成功才 delete（失败留队首，心跳 5s 无限重试） | L29 [kdoc] - POST 成功后 onClientSendParts 置 Busy | L30 [kdoc] - 会话级 in-flight 去重 | L31 [kdoc] - 状态补偿：T1 心跳 / T2 入队即时 / T3 Idle 观察 → FSM Idle + 队列非空即发 | L142 [line]  首次调用时 draining 集合应包含本会话 | L151 [line]  完成后集合清空 | L155 [line]  ============ #176/#177 状态补偿 ============ | L159 [line]  #176 精确场景：turn 已在入队前结束（FSM Idle），入队即时补偿发队首 | L172 [line]  Busy 会话入队：保持原语义（等 turn 结束），不抢发 | L183 [line]  #177 断点②：POST 失败不动点 → 心跳 5s 无限重试 | L192 [line]  首拍前无动作；advanceTimeBy 触发第一拍 | L197 [line]  服务器恢复：下一拍发送成功 → delete | L205 [line]  #177 断点③：RestValidation(Idle) 类语义——statusFlow 落 Idle 即 drain | L210 [line]  Busy 期间无动作 | L214 [line]  L3/L4 恢复 → Idle（非自然结束白名单路径） | L222 [line]  护栏：问题/权限待答时不 drain（防把待答状态当可推进） | L245 [line]  in-flight 去重：同会话并发触发（边沿 + 补偿）只发一条
C-EN*: L27 spec: docs/specs/2026-08-21-queue-drain-state-compensation-design.md）：
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PendingMessagePipelineTest.kt","loc":275,"lang":"混合","zh":18,"en":1,"kdoc":6,"tests":14,"cls":"PendingMessagePipelineTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplDedupTest.kt
LOC 99 | lang 中文 (zh 4/en 0, kdoc 2) | @Test 3 | btFuns 3 | SessionRepositoryImplDedupTest
LEX: session,message,event,part,turn,config,sse,cursor,page,diff,patch,pending,dedup
FREQ: server×9 repo×7 domain×6 model×6 calls×5 repository×4 kotlinx×4 coroutines×4 async×4 store×4 relaxed×4 version×3 await×3 delay×3
C-ZH: L21 [kdoc] #91（2026-08-18）：listMessages 在途去重——同 (serverId, sessionId, limit, before) | L22 [kdoc] 的并发调用共享同一在途请求（实测会话进入 22ms 内同 cursor 成对重复 8 次）。 | L33 [line]  堆积消息级联删除（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L61 [line]  模拟网络延迟，保证并发窗口重叠
ZHSTR: L69 "6 个并发同参调用只有 1 次实际请求" | L70 "全部调用方拿到相同结果（30 条）"
TESTS-EN: `concurrent identical calls share single in-flight request` | `sequential calls after completion are not cached` | `different cursors issue separate requests`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplDedupTest.kt","loc":99,"lang":"中文","zh":4,"en":0,"kdoc":2,"tests":3,"cls":"SessionRepositoryImplDedupTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherIntegrationTest.kt
LOC 860 | lang 混合 (zh 67/en 8, kdoc 4) | @Test 23 | btFuns 23 | EventDispatcherIntegrationTest
LEX: session,message,event,part,token,tool,agent,question,permission,provider,unread,compaction,stream,sse,cursor,paginat,retry,busy,diff,patch,task,context,pending,badge
FREQ: process×103 progress×58 step×49 started×47 equals×47 state×44 time×31 model×23 active×21 handler×20 current×20 created×20 service×19 updated×18
C-ZH: L23 [kdoc] 集成测试，验证完整的 SSE 事件处理管线： | L24 [kdoc] SSE Event → EventDispatcher.processEvent() → StateFlows 更新。 | L26 [kdoc] 所有 handler 都是真实实现（未 mock），以验证实际的集成行为。 | L27 [kdoc] 这些测试通过深度链路测试补充单元级的 EventDispatcherTest。 | L32 [line]  SessionStateService 的 statusFlow 使用 stateIn(scope, SharingStarted.Eagerly, …)；Eagerly | L33 [line]  传播需要 UnconfinedTestDispatcher + runCurrent()（关于 runTest 的 StandardTestDispatcher | L34 [line]  为何失效，参见 SessionStateServiceTest 的 fixture 注释）。每个测试使用全新的 scope， | L35 [line]  以免 init 中的 staleness-guard 协程在测试间泄漏。 | L63 [line]  #122 接线新增：自动批准（relaxed mock——shouldAutoApprove 恒 false，既有用例不受影响） | L66 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L81 [line]  ============ 场景 1：工具进度完整链路 ============ | L85 [line]  步骤 1：ToolInputStarted | L101 [line]  步骤 2：ToolProgress | L116 [line]  步骤 3：ToolSuccess | L166 [line]  只完成 c1 | L180 [line]  ============ 场景 2：步骤进度完整链路 ============ | L242 [line]  ============ 场景 3：压缩完整链路 ============ | L269 [line]  2026-08-19：V2 session.compaction.ended 映射为 SessionCompacted—— | L270 [line]  auto-compaction 无 HTTP 回调注入兜底，压缩横幅须由本事件终结 | L271 [line] （EventDispatcher 跨 handler 调 endCompaction）；同时进入 | L272 [line]  compactedSessions（ChatViewModel 完成 snackbar + 刷新的数据源） | L290 [line]  ============ 场景 4：Agent/Model 切换链路 ============ | (+45 more)
C-EN*: L711  1. AgentSwitched | L730  3. ToolInputStarted | L740  4. ToolProgress | L750  5. ToolSuccess | L769  7. CompactionStarted | L778  8. CompactionEnded
   (+2 trivial en comments)
ZHSTR: L284 "SessionCompacted 应终结压缩横幅" | L286 "SessionCompacted 应进入 compactedSessions"
ENSTR*: L94 "Tool progress should exist after ToolInputStarted" | L125 "Tool progress should be empty after ToolSuccess" | L140 "Permission denied" | L248 "context limit" | L263 "Compaction state should not contain s1 after ended" | L422 "Session statuses should not contain s1" | L423 "Messages should not contain s1" | L424 "Permissions should not contain s1" | (+8 more)
TESTS-EN: `tool progress full chain - started to progress to success` | `tool progress full chain - failed also clears` | `multiple concurrent tool progress tracked independently` | `step progress full chain - started then ended` | `step failed also clears step progress` | `step progress overwrites with latest step` | `compaction full chain - started then ended` | `session compacted event ends compaction banner and joins compacted set` | `agent and model switch chain` | `multi-session independence - clearing one session does not affect others` | `SessionDeleted cascade clears ALL handler state for session` | `SessionDeleted cascade does not affect other sessions` | `CommandExecuted marks incomplete assistant messages as completed` | `CommandExecuted does not modify already-completed messages` | (+9 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/EventDispatcherIntegrationTest.kt","loc":860,"lang":"混合","zh":67,"en":8,"kdoc":4,"tests":23,"cls":"EventDispatcherIntegrationTest"}











═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateCollaboratorTest.kt
LOC 115 | lang 中文 (zh 4/en 0, kdoc 2) | @Test 7 | btFuns 7 | SessionStateCollaboratorTest
LEX: session,message,event,part,turn,directory,question,permission,provider,unread,stream,sse,idle,patch,pending,merge,upsert,badge
FREQ: handler×38 assistant×12 impl×10 completed×9 strategy×7 pipeline×7 repository×5 time×5 state×5 relaxed×5 domain×4 model×4 collaborator×4 incomplete×4
C-ZH: L21 [kdoc] #174 接线完整性：SessionStateCollaboratorImpl 的 8 方法 = 原 EventDispatcher.init | L22 [kdoc] 接线块的逐条行为等价（迁移零变更的回归守卫）。 | L67 [line]  消息被终结（展示域客户端戳——红点域不读，#171） | L69 [line]  落盘兜底触发
TESTS-EN: `hasIncompleteAssistant reflects streaming state of message cache` | `resolveDirectory returns null for unknown session` | `forceCompleteSession marks idle and persists unread watermark` | `refreshMessages delegates to message cache upsert` | `latestMessageId returns newest by created` | `hasPendingUserInput false without questions or permissions` | `onNaturalTurnEnd delegates to pending pipeline`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionStateCollaboratorTest.kt","loc":115,"lang":"中文","zh":4,"en":0,"kdoc":2,"tests":7,"cls":"SessionStateCollaboratorTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproverTest.kt
LOC 36 | lang 中文 (zh 2/en 0, kdoc 0) | @Test 2 | btFuns 2 | PermissionAutoApproverTest
LEX: session,tool,directory,permission,sse
FREQ: rule×15 auto×10 json×10 approve×9 deserialized×6 serialized×4 serialization×3 created×3 encode×3 equals×3
C-ZH: L12 [line]  固定 createdAt：System.currentTimeMillis() 默认值非确定性， | L13 [line]  配合默认 encodeDefaults=false 会偶发省略该字段导致 round-trip 失败。
TESTS-EN: `AutoApproveRule serialization round-trip` | `AutoApproveRule with defaults serialization`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/PermissionAutoApproverTest.kt","loc":36,"lang":"中文","zh":2,"en":0,"kdoc":0,"tests":2,"cls":"PermissionAutoApproverTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplTest.kt
LOC 148 | lang 混合 (zh 2/en 3, kdoc 0) | @Test 7 | btFuns 7 | SessionRepositoryImplTest
LEX: session,message,event,turn,token,question,permission,provider,config,unread,stream,sse,patch,pending,badge
FREQ: server×36 handler×30 repo×19 repository×14 relaxed×13 flow×10 delete×9 create×8 state×7 store×7 service×7 domain×6 kotlinx×6 coroutines×6
C-ZH: L58 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L61 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
C-EN*: L74  ============ getSessionsFlow ============ | L94  ============ createSession ============ | L117  ============ deleteSession ============
TESTS-EN: `getSessionsFlow returns sessions for given server` | `getSessionsFlow returns empty for unknown server` | `createSession calls API and returns session` | `createSession returns failure when server not found` | `deleteSession delegates to API when server exists` | `deleteSession returns failure when server not found` | `deleteSession propagates API failure`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SessionRepositoryImplTest.kt","loc":148,"lang":"混合","zh":2,"en":3,"kdoc":0,"tests":7,"cls":"SessionRepositoryImplTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerIncrementalPersistTest.kt
LOC 74 | lang 中文 (zh 5/en 0, kdoc 2) | @Test 2 | btFuns 2 | MessageEventHandlerIncrementalPersistTest
LEX: session,message,event,part,sse
FREQ: handler×19 delta×14 updated×12 handle×9 domain×5 model×5 time×5 flush×5 assistant×5 deltas×5 info×3 equals×3 force×3
C-ZH: L13 [kdoc] #97（H-6）：SSE 增量落盘——flush 后 delta 按 part 追加（不丢文本）， | L14 [kdoc] 且增量写与全量写（handleMessageUpdated）路径一致。 | L35 [line]  第一批：2 个 delta（同一 part 聚合） | L44 [line]  第二批：追加 | L65 [line]  消息更新（completed）后文本保留
ZHSTR: L61 "流式"
TESTS-EN: `incremental flush accumulates deltas across batches` | `message updated after deltas keeps accumulated text`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerIncrementalPersistTest.kt","loc":74,"lang":"中文","zh":5,"en":0,"kdoc":2,"tests":2,"cls":"MessageEventHandlerIncrementalPersistTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt
LOC 124 | lang 中文 (zh 11/en 0, kdoc 4) | @Test 7 | btFuns 7 | InMemoryPreferencesDataStore,SettingsDataStoreTagsTest
LEX: session,turn,sse,context,migrat
FREQ: store×52 legacy×25 favorite×24 preferences×19 flow×10 tags×9 assigns×8 datastore×7 toggle×7 core×6 state×6 json×6 assignments×6 kotlinx×5
C-ZH: L22 [kdoc] 纯内存 DataStore——避免 Windows 文件系统 rename 限制（androidx.datastore FileStorage | L23 [kdoc] 在 Windows 上无法可靠地用 .tmp 覆盖已存在的目标文件）。 | L25 [kdoc] 被测扩展函数（[androidx.datastore.preferences.core.edit] 及本项目自定义扩展）只依赖 | L26 [kdoc] [DataStore.data] flow 与 [DataStore.updateData]，内存实现语义等价。 | L92 [line]  模拟旧 favorite_sessions_<serverId> stringSet 数据（SettingsDataStoreFavorites 历史格式） | L95 [line]  #137（D2-L59）：迁移显式触发（原藏在 flow map 内，已移出） | L99 [line]  迁移已写入 assignments map：再次读取时直接从 assignments 派生 | L110 [line]  #137（D2-L59）：迁移显式触发（原藏在 flow map 内，已移出） | L114 [line]  迁移成功后 legacy key 必须已被删除（否则后续取消全部收藏会让迁移条件再次满足） | L117 [line]  取消全部收藏 | L120 [line]  再读：必须为空，不应因 legacy key 残留而重新迁移"复活"
ZHSTR: L45 "前端"
ENSTR*: L116 "legacy key should be removed after migration" | L122 "unfavorited sessions must not resurrect"
TESTS-EN: `tag serialization round trip` | `removeTag clears assignments atomically` | `setSessionTags keeps favorite tag` | `favoriteSessionIds reflects toggle` | `sessionTags excludes favorite tag` | `favoriteSessionIds migrates legacy stringSet on first read` | `favoriteSessionIds migrate then unfavorite all does not resurrect`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt","loc":124,"lang":"中文","zh":11,"en":0,"kdoc":4,"tests":7,"cls":"InMemoryPreferencesDataStore"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimesTest.kt
LOC 132 | lang 中文 (zh 6/en 0, kdoc 1) | @Test 11 | btFuns 11 | InMemoryReadTimesStore,SettingsDataStoreReadTimesTest
LEX: session,turn,unread,sse,context,migrat
FREQ: store×63 mark×20 times×19 equals×14 preferences×12 state×8 flow×7 completed×7 reply×5 datastore×4 core×4 kotlinx×4 coroutines×4 settings×4
C-ZH: L18 [kdoc]  纯内存 DataStore——避免 Windows 文件系统 rename 限制（与 SettingsDataStoreTagsTest 相同模式）。 | L62 [line]  第二次标记传入更大的 completed：maxOf 单调保护取 max → 已读位置推进为 9000 | L72 [line]  双 VM 乱序写入更小的 completed：maxOf 单调保护，不回退已读位置 | L90 [line]  全量重同步旧数据/服务器时钟异常导致 globalMax 变小：maxOf 单调保护，不回退 allReadAt | L98 [line]  确保未读功能不依赖/不干扰标签体系 | L127 [line]  幂等：迁移标记存在则跳过——写入新值后再次迁移不动
TESTS-EN: `markSessionRead then read back` | `markSessionRead is server-scoped` | `empty read times by default` | `markSessionRead overwrites previous timestamp` | `markSessionRead smaller value does not overwrite` | `markAllSessionsRead then read back` | `markAllSessionsRead smaller value does not overwrite` | `favorite tag unrelated to read times` | `lastCompletedReplyTimes round-trip survives save` | `lastCompletedReplyTimes empty by default` | `v2 migration clears read times and all read once`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreReadTimesTest.kt","loc":132,"lang":"中文","zh":6,"en":0,"kdoc":1,"tests":11,"cls":"InMemoryReadTimesStore"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ShellJobsStoreTest.kt
LOC 78 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 4 | btFuns 4 | ShellJobsStoreTest
LEX: session,sse
FREQ: store×27 shell×19 jobs×14 equals×11 running×11 exited×10 status×8 exit×8 ended×8 single×7 started×6 output×5
C-ZH: L28 [line]  2026-08-12 修复：V2 shell.exited 事件 payload 无 metadata.sessionID | L29 [line]  （ShellJob.sessionId=null），旧实现按 "" 组更新找不到 job → 卡 Running。 | L34 [line]  ended 事件无 sessionId（V2 服务器实际格式） | L59 [line]  未知 id + 无 sessionId：不应补录到 "" 组（避免脏数据）
TESTS-EN: `ended with missing sessionId still updates job started in session group` | `ended with sessionId updates normally` | `ended with missing sessionId and unknown id does not add phantom job` | `multiple sessions isolated`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/ShellJobsStoreTest.kt","loc":78,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":4,"cls":"ShellJobsStoreTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StubCollaborator.kt
LOC 29 | lang 中文 (zh 2/en 0, kdoc 2) | @Test 0 | btFuns 0 | StubCollaborator
LEX: session,message,part,turn,directory,pending,merge
FREQ: impl×12 strategy×5 server×4 force×3 complete×3 natural×3 user×3 input×3 resolve×3 latest×3 incomplete×3 assistant×3 refresh×3 active×3
C-ZH: L7 [kdoc] 测试助手（#174）：可单点覆写的协作者桩——主 interface 保持全抽象无默认， | L8 [kdoc] 测试侧经 lambda 字段按需定制（迁移自原 8 个 var 回调旋钮的赋值模式）。
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StubCollaborator.kt","loc":29,"lang":"中文","zh":2,"en":0,"kdoc":2,"tests":0,"cls":"StubCollaborator"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeServiceTest.kt
LOC 91 | lang 中文 (zh 14/en 0, kdoc 8) | @Test 5 | btFuns 0 | UnreadBadgeServiceTest
LEX: session,message,turn,unread,sse,patch,merge,badge
FREQ: completed×28 service×20 reply×10 time×9 equals×7 settings×6 store×6 kotlinx×5 coroutines×5 scope×5 assistant×5 seed×4 times×4 relaxed×4
C-ZH: L16 [kdoc] UnreadBadgeService（红点时间源）单元测试。 | L18 [kdoc] 红点语义不变量（2026-08-07 历史决策）： | L19 [kdoc] - maxCompleted 只增不减（REST 快照滞后 completed=null 不移除） | L20 [kdoc] - 只有 removeSession 移除；seed 合并取 max | L21 [kdoc] - 判定只用服务器 completed | L23 [kdoc] 注：lastCompletedReplyTimes 已是 SettingsDataStore 成员方法，relaxed mock 直接 every stub。 | L24 [kdoc] saveLastCompletedReplyTimes 在 UnconfinedTestDispatcher scope 下经 relaxed mock | L25 [kdoc] 链式调用静默返回，不需 stub。 | L37 [line]  更小 → 不回退 | L46 [line]  REST 快照滞后：completed=null → 不移除已记录的 max | L72 [line]  lastCompletedReplyTimes 是 SettingsDataStore 成员方法，relaxed mock 直接 every stub | L76 [line]  内存已有较小值 | L80 [line]  seed 更大 → 覆盖 | L81 [line]  新增
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadBadgeServiceTest.kt","loc":91,"lang":"中文","zh":14,"en":0,"kdoc":8,"tests":5,"cls":"UnreadBadgeServiceTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadClockDomainTest.kt
LOC 147 | lang 中文 (zh 8/en 0, kdoc 4) | @Test 6 | btFuns 6 | UnreadClockDomainTest
LEX: session,message,event,part,token,question,permission,provider,unread,stream,sse,cursor,paginat,busy,idle,patch,pending,merge,upsert,badge
FREQ: handler×28 service×21 repository×18 scope×15 domain×14 completed×12 coroutines×10 store×10 time×9 kotlinx×8 relaxed×8 status×7 settings×7 created×7
C-ZH: L39 [kdoc] #171 时钟域纯度测试——三条铁律从注释升级为结构不变量的反例验证： | L40 [kdoc] 1. seedCachedMessages（DB 回读载荷）**不喂**水位线（客户端终结戳无从混入） | L41 [kdoc] 2. upsertMessages（服务器载荷）从**载荷本身**提取 max（不扫合并缓存） | L42 [kdoc] 3. SessionError 的客户端时刻经 [UnreadEvent.SessionErrorOccurred] 显式例外通道进水位线 | L99 [line]  反例核心：DB 回读载荷携带 markSessionIdle 的客户端终结戳（999_999 模拟本地 now） | L112 [line]  缓存被本地终结戳污染（seed 载荷 completed=999_999）后，服务器载荷（600）提取的水位线应为 600 | L113 [line]  ——若实现退化为扫缓存 max 会得到 999_999。 | L134 [line]  秒退/消息未加载：无水位线记录 → 不写内存信号、不落盘（之后红点合理）
TESTS-EN: `seedCachedMessages does not feed watermark` | `upsertMessages extracts watermark from payload` | `payload extraction ignores cache pollution` | `session error feeds watermark via explicit client-clock exception` | `markSessionRead no-op without watermark entry` | `unread judgment gates on Idle and compares watermark`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/UnreadClockDomainTest.kt","loc":147,"lang":"中文","zh":8,"en":0,"kdoc":4,"tests":6,"cls":"UnreadClockDomainTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistryTest.kt
LOC 52 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 4 | btFuns 0 | StreamingOwnershipRegistryTest
LEX: session,stream,sse
FREQ: registry×27 claim×14 release×4 server×3
C-ZH: L16 [line]  已被 srv_A 认领 | L17 [line]  同 server 重复认领 OK | L38 [line]  srv_A 释放后可被认领 | L39 [line]  srv_B 仍持有
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/StreamingOwnershipRegistryTest.kt","loc":52,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":4,"cls":"StreamingOwnershipRegistryTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsMigrationTest.kt
LOC 51 | lang 中文 (zh 4/en 0, kdoc 4) | @Test 6 | btFuns 6 | SettingsMigrationTest
LEX: turn,sse,migrat
FREQ: density×20 chat×12 normal×8 equals×7 font×7 medium×4 large×4 settings×3 small×3 without×3
C-ZH: L8 [kdoc] 验证旧版 → 新版聊天密度迁移逻辑。 | L10 [kdoc] 生产环境中的等价实现位于 [SettingsDataStore.migrateDensity] | L11 [kdoc] （返回 "normal"/"compact" 字符串）；本测试直接断言该决策表本身， | L12 [kdoc] 通过 [ChatDensity] 枚举表达以提高可读性。
TESTS-EN: `compact on with medium font migrates to Compact` | `compact on with large font migrates to Compact` | `small font without compact migrates to Compact` | `medium font without compact migrates to Normal` | `large font without compact migrates to Normal` | `null settings default to Normal`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsMigrationTest.kt","loc":51,"lang":"中文","zh":4,"en":0,"kdoc":4,"tests":6,"cls":"SettingsMigrationTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/VcsRepositoryImplTest.kt
LOC 117 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | VcsRepositoryImplTest
LEX: session,message,turn,sse,diff,patch,workspace
FREQ: status×22 file×21 equals×19 server×14 branch×11 repository×10 model×10 changes×9 domain×7 success×7 project×7 additions×7 change×6 info×6
TESTS-EN: `getBranch success returns VcsBranchInfo` | `getStatus success maps DTOs` | `getDiff success passes mode apiValue and maps to VcsFileDiff` | `getStatus failure wraps exception`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/VcsRepositoryImplTest.kt","loc":117,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"VcsRepositoryImplTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryTest.kt
LOC 193 | lang 中文 (zh 14/en 0, kdoc 5) | @Test 17 | btFuns 17 | SettingsDataStoreTest
LEX: message,tool,agent,draft,provider,config,sse,terminal,feedback,notification
FREQ: java×14 model×12 server×12 method×9 methods×9 image×8 selected×8 setter×7 expected×7 settings×6 store×6 equals×6 visibility×6 domain×5
C-ZH: L10 [kdoc] SettingsDataStore 公共 API 契约的特征测试。 | L11 [kdoc] 验证所有 Flow 属性存在且类型正确， | L12 [kdoc] 所有 setter 函数存在且签名正确。 | L14 [kdoc] 使用 Java 反射（java.lang.reflect）以避免 kotlin-reflect 依赖。 | L15 [kdoc] 这些测试确保重构期间公共 API 表面不会回退。 | L19 [line]  ============ Flow 属性契约 ============ | L27 [line]  Kotlin 属性编译为 getXxx() 方法 | L54 [line]  ============ Setter 函数契约 ============ | L84 [line]  Kotlin suspend 函数在 JVM 层面会多一个 Continuation 参数。 | L85 [line]  setModelVisibility(serverId, providerId, modelId, visible) → 4 个参数 + Continuation | L93 [line]  JVM 层面有 5 个参数（4 个值 + 1 个 continuation），但逻辑上是 4 个值参数 | L97 [line]  ============ DraftRepository 契约 ============ | L153 [line]  ============ ServerConfig 契约 ============ | L172 [line]  displayName = name ?: url → 当 name 为 null 时，返回完整 url
TESTS-EN: `all expected Flow properties exist as getter methods` | `hiddenModels is a function with correct parameter count` | `all expected setter functions exist` | `setModelVisibility has correct parameter count` | `Draft default instance is empty` | `Draft with text is not empty` | `Draft with only whitespace text is empty` | `Draft with selectedAgent is not empty` | `Draft with blank selectedAgent is empty` | `Draft with selectedVariant is not empty` | `Draft with blank selectedVariant is empty` | `Draft with imageUris is not empty` | `Draft with confirmedFilePaths is not empty` | `ServerConfig displayName uses explicit name when set` | (+3 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryTest.kt","loc":193,"lang":"中文","zh":14,"en":0,"kdoc":5,"tests":17,"cls":"SettingsDataStoreTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsLanguageMirrorTest.kt
LOC 40 | lang 中文 (zh 5/en 0, kdoc 3) | @Test 5 | btFuns 5 | SettingsLanguageMirrorTest
LEX: sse,diff,context
FREQ: mirror×15 store×11 settings×6 language×6 datastore×5 resolve×5 stored×5 equals×4
C-ZH: L8 [kdoc] #136（D2-L56）：语言镜像收敛决策（纯函数）。 | L9 [kdoc] DataStore 为真相源；镜像（SharedPreferences，attachBaseContext 同步读取） | L10 [kdoc] 不一致时以 DataStore 为准回写。 | L26 [line]  镜像仍为旧值（双写窗口崩溃），DataStore 已有新值 → 以 DataStore 收敛 | L32 [line]  旧版先写镜像的窗口：镜像新、DataStore 旧 → 收敛为 DataStore 值（真相源优先）
TESTS-EN: `mirror matches datastore - no correction needed` | `both empty - no correction needed` | `mirror stale after crash window - correct from datastore` | `mirror newer than datastore - datastore wins as source of truth` | `mirror different value - datastore wins`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsLanguageMirrorTest.kt","loc":40,"lang":"中文","zh":5,"en":0,"kdoc":3,"tests":5,"cls":"SettingsLanguageMirrorTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTokensPersistTest.kt
LOC 166 | lang 中文 (zh 20/en 0, kdoc 10) | @Test 4 | btFuns 4 | MessageEventHandlerTokensPersistTest
LEX: session,message,event,part,turn,token,stream,sse,queue,merge,upsert
FREQ: persisted×21 handler×15 assistant×11 payload×10 priority×9 cost×9 delta×9 model×8 persist×8 store×8 domain×7 strategy×7 time×7 equals×7
C-ZH: L19 [kdoc] #1657（P3）：SSE_PRIORITY 合并的 tokens/cost 增量落库。 | L21 [kdoc] V2 SSE 整 turn 不发 message.updated，REST 刷新是 tokens 唯一可靠来源； | L22 [kdoc] upsertSsePriority 原本只更新内存热视图 → cached_messages.payload 停留在 | L23 [kdoc] 流式期骨架快照（tokens=null）→ 冷启动/离线 seed 后统计图标短暂缺失。 | L24 [kdoc] 修复：合并前后 tokens/cost 对比（不在 existing = null→值 视为变更）， | L25 [kdoc] 变更行经 persistSseUpdate 增量落盘；值未变 0 写库（检测即节流）。 | L27 [kdoc] 持久化经 persistQueue 单写协程异步消费：正断言轮询等待（5s 上限）； | L28 [kdoc] 负断言在同测试内先以正路径证明管道通畅，再给宽限期验证 0 追加写。 | L35 [kdoc]  persist actor 消费到的全量 upsert payload（跨线程记录）。 | L66 [kdoc]  轮询等待 persist actor 消费 [expected] 批全量写（超时 false）。 | L78 [line]  重进会话场景：内存热视图为空，REST 刷新（SSE_PRIORITY）带回带 tokens 的行 | L89 [line]  落库 payload 必须携带 tokens/cost（cached_messages.payload = 完整 Message JSON） | L100 [line]  第二次相同 REST 刷新：内存已带同值 tokens → 合并前后无变化 → 0 追加写 | L102 [line]  负断言宽限期（上方已证明管道通畅） | L109 [line]  user 行与 tokens=null 的 assistant 行：无 tokens/cost 变化 → 不触发落库 | L123 [line]  tokens=null → 无变化 | L133 [line]  会话内流式：消息已带 tokens；delta 批处理期间的 REST 刷新不重复整行写库 | L142 [line]  流式 delta：48ms 批处理 → 增量 append（appendPartTexts 路径，非整行重写） | L151 [line]  tokens 未变的流式中间态刷新：不触发整行 upsert（节流 = 变更检测） | L163 [line]  delta 仍走增量路径（48ms 批处理管线的增量落盘不受影响）
ZHSTR: L84 "tokens 变更行应在超时前经 persistQueue 落库" | L98 "首次刷新（null→值）应落库一次" | L103 "值未变不应重复写库" | L161 "流式中间 tokens 未变：整行写库不增加"
TESTS-EN: `tokens null to value triggers persist with final payload` | `unchanged tokens on repeated refresh does not persist again` | `rows without tokens change are not persisted` | `streaming deltas with unchanged tokens keep full-row writes throttled`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTokensPersistTest.kt","loc":166,"lang":"中文","zh":20,"en":0,"kdoc":10,"tests":4,"cls":"MessageEventHandlerTokensPersistTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMemoryCapTest.kt
LOC 70 | lang 混合 (zh 4/en 1, kdoc 4) | @Test 3 | btFuns 3 | MessageEventHandlerMemoryCapTest
LEX: session,message,event,part,sse
FREQ: handler×16 updated×8 equals×6 created×6 user×5 domain×4 model×4 handle×4 time×3 memory×3 trimmed×3
C-ZH: L13 [kdoc] #95（H-4 泄漏）：消息热视图内存上限——与 Room SESSION_MESSAGE_LIMIT=1000 对齐。 | L14 [kdoc] MessageEventHandler 是 @Singleton，活跃会话的 _messages/_parts 无上限时 | L15 [kdoc] 长会话 + 多会话可达数百 MB。超出上限后保留最新 1000 条， | L16 [kdoc] 被裁剪消息的 parts 与 assistantMessageIds 同步清理。
   (+1 trivial en comments)
ENSTR*: L58 "parts of trimmed messages must not linger (#95 leak)"
TESTS-EN: `session messages capped at 1000 newest` | `trimmed messages drop their parts` | `small sessions unaffected`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMemoryCapTest.kt","loc":70,"lang":"混合","zh":4,"en":1,"kdoc":4,"tests":3,"cls":"MessageEventHandlerMemoryCapTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeTest.kt
LOC 160 | lang 中文 (zh 21/en 0, kdoc 3) | @Test 4 | btFuns 4 | MessageEventHandlerMergeTest
LEX: session,message,event,part,stream,sse,merge
FREQ: handler×34 updated×24 rest×17 delta×17 handle×16 time×11 equals×10 parent×8 assistant×7 info×5 created×5 completed×5 hello×4 world×4
C-ZH: L10 [kdoc] [MessageEventHandler] 中 SSE/REST 合并策略的测试。 | L11 [kdoc] 验证当 REST 数据以过期或空文本到达时， | L12 [kdoc] 通过 SSE 累积的流式内容仍被保留。 | L23 [line]  ============ 测试 1：setMessages 保留 SSE 流式文本而非 REST 空文本 ============ | L27 [line]  SSE：通过 MessageUpdated → PartUpdated → 2x PartDelta 累积 "Hello World" | L50 [line]  验证 SSE 累积生效 | L53 [line]  REST：setMessages 携带空文本（服务器快照尚未跟上） | L58 [line]  mergePartsList 应保留更长的文本 | L63 [line]  ============ 测试 2：setMessages 保留 SSE 未完成消息的元数据 ============ | L67 [line]  SSE：completed=null 的 Assistant 消息（仍在流式输出） | L76 [line]  REST：同一消息 completed=2000L（服务器知道它已完成） | L85 [line]  mergeMessageMeta 应将 REST 中的完成时间合并进 SSE 版本 | L90 [line]  ============ 测试 3：setMessages 不会清除不在 REST 响应中的消息的 parts ============ | L94 [line]  SSE：两条消息，各自带有 parts | L120 [line]  REST：只有 msg-1（msg-2 仍在流式输出，尚未进入 REST 快照） | L123 [line]  msg-2 应仍在 messages 中 | L129 [line]  msg-2 的 parts 应被保留（current + merged 保留现有键） | L136 [line]  ============ 测试 4：handleMessagePartUpdated 保留更长的既有文本而非更短的传入文本 ============ | L140 [line]  SSE：通过 PartUpdated + PartDelta 累积文本 = "Accumulated SSE text" | L152 [line]  SSE：传入更短文本 "Short" 的 PartUpdated | L156 [line]  mergePart 应保留更长的既有文本
ENSTR*: L131 "msg-2 parts should be preserved" | L146 " SSE text" | L150 "Accumulated SSE text"
TESTS-EN: `setMessages preserves SSE streaming text over REST empty text` | `setMessages preserves SSE incomplete message metadata` | `setMessages does not clear parts for messages not in REST response` | `handleMessagePartUpdated keeps longer existing text over shorter incoming text`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeTest.kt","loc":160,"lang":"中文","zh":21,"en":0,"kdoc":3,"tests":4,"cls":"MessageEventHandlerMergeTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeSortedTest.kt
LOC 255 | lang 混合 (zh 50/en 4, kdoc 19) | @Test 15 | btFuns 15 | MessageEventHandlerMergeSortedTest
LEX: session,message,event,turn,sse,diff,fingerprint,dedup,merge,upsert
FREQ: existing×41 incoming×35 created×32 sorted×17 newer×16 equiv×14 model×12 equals×11 distinct×11 reference×10 handler×8 assistant×8 load×8 time×7
C-ZH: L10 [kdoc] `mergeSortedMessages` 的对拍测试（金标准）。 | L12 [kdoc] 验证线性两路归并实现与旧算法 `(existing + incoming).distinctBy { id }.map { merge }.sortedBy { created }` | L13 [kdoc] 在任意合法输入下逐字节等价。合法输入前提：existing 与 incoming 均按 created 升序， | L14 [kdoc] 且同一 id 在两列表中 created 一致（服务器不变更创建时间）。 | L16 [kdoc] - reference 函数（本文件内）是旧算法的精确语义实现 | L17 [kdoc] - 300 轮随机对拍覆盖 id 重叠/不重叠/重复、created 大量相同等组合 | L18 [kdoc] - 显式边界用例覆盖 Bug 1 / Bug 2 场景及退化输入 | L24 [line]  ============ reference：旧算法精确语义 ============ | L27 [kdoc] 旧算法 reference：`(existing + incoming).distinctBy { id }.map { merge }.sortedBy { created }`。 | L28 [kdoc] - distinctBy 保留首个（existing 优先于 incoming；列表内部首个优先于后续） | L29 [kdoc] - map 对同时存在于两列表的 id 调用 merge(existing, incoming)，独有项原样保留 | L30 [kdoc] - sortedBy 稳定排序（Kotlin 默认） | L42 [line]  (existing + incoming).distinctBy { it.id }：保留首个 | L48 [line]  .map { merge }：同时存在则合并 | L54 [line]  .sortedBy { it.time.created }（稳定） | L58 [line]  ============ 测试辅助 ============ | L69 [kdoc]  merge：拼接两版本的 modelId 作为可验证的合并标记（内容指纹）。 | L76 [kdoc]  断言 `mergeSortedMessages` 输出与 reference 完全一致（id 序列 + 内容指纹）。 | L89 [line]  ============ 显式边界用例 ============ | L103 [line]  incoming=[X(1),X(1)] existing=[] → reference distinctBy 保留首个=[X(首个)] | L110 [line]  existing=[Y(1),Y(1)] incoming=[] → reference 保留首个 Y | L135 [line]  ============ 双向分页去重（loadAround / loadNewer 场景） ============ | (+28 more)
C-EN*: L94  reference: (ex+inc)=[A,C,B,A] distinctBy=[A(ex),C,B] mapMerge=[A',C,B] | L209  reference: distinctBy=[A(ex),C,B,D] mapMerge=[A',C,B,D] sortedBy=[A'(1),B(1),D(1),C(5)]
   (+2 trivial en comments)
ZHSTR: L157 "合并后无重复 id" | L184 "跨方向串行合并无重复"
ENSTR*: L85 "[$label] modelId (fingerprint) at index $k for id=${actual[k].id}"
TESTS-EN: `Bug1 - same created order reversal` | `Bug2 - incoming duplicate id not deduped` | `Bug2 variant - existing duplicate id dedup` | `both empty` | `empty existing` | `empty incoming` | `all overlap` | `bidirectional loadAround - older and newer overlap with existing deduped` | `loadNewer then SSE - serial merges dedupe across directions` | `no overlap interleaved` | `many same created stable order` | `existing covered with same-created incoming unique` | `incoming earlier than existing` | `incoming with internal duplicate plus overlap` | (+1 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerMergeSortedTest.kt","loc":255,"lang":"混合","zh":50,"en":4,"kdoc":19,"tests":15,"cls":"MessageEventHandlerMergeSortedTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTest.kt
LOC 546 | lang 中文 (zh 39/en 0, kdoc 0) | @Test 35 | btFuns 35 | MessageEventHandlerTest
LEX: session,message,event,part,turn,token,tool,agent,provider,stream,sse,idle,snapshot,diff,context,pending,merge,upsert
FREQ: handler×134 updated×108 handle×63 time×50 equals×47 rest×36 delta×32 model×22 assistant×22 user×20 created×20 info×18 hello×18 handles×17
C-ZH: L67 [line]  ============ 2026-08-15：统计栏丢模型/耗时（step.ended 整替换修复） ============ | L71 [line]  V2 场景：step.started 写入模型信息 → step.ended（契约不含 model）到达。 | L72 [line]  修复前：整替换 → modelId/agent 被抹（统计栏丢模型名）； | L73 [line]  修复后：非空合并 → 模型保留 + tokens/cost 写入。 | L83 [line]  step.ended 映射用本地时刻（晚于 started） | L90 [line]  修复点：不被 step.ended 抹掉 | L93 [line]  step.ended 携带的 cost 写入 | L94 [line]  tokens 写入（圆环数据源） | L95 [line]  created 取较早值（耗时不归零） | L100 [line]  REST 权威数据（带 model）到达时覆盖 SSE 的空值（正常覆盖语义保留） | L119 [line]  ============ 2026-08-15：REST_AUTHORITY 不抹 tokens（顶部统计回归） ============ | L123 [line]  场景：SSE step.ended 已写入 tokens（顶部 context 指示器数据源）→ | L124 [line]  重连 recoverMessages / L3 刷新以 REST_AUTHORITY 到达（V2 REST 契约 | L125 [line]  不返回 tokens）→ 原 `{ _, inc -> inc }` 纯覆盖抹掉 tokens → | L126 [line]  lastContextTokens=0 → 顶部导航栏统计消失（0.3.1-dev.1/2 回归）。 | L139 [line]  无 tokens —— V2 REST 契约不返回 | L150 [line]  REST_AUTHORITY 权威语义保留：REST 携带真实值时覆盖 existing | L153 [line]  未完成 | L167 [line]  REST 权威覆盖 | L168 [line]  REST 带值时覆盖 | L337 [line]  ============ 合并策略测试（SSE 截断修复）============ | L344 [line]  Delta 追加 " World" → 文本变为 "Hello World" | (+17 more)
ZHSTR: L144 "tokens 不应被 REST 覆盖抹掉" | L516 "空 id 快照应与 SSE part 合并而非新增" | L531 "更长文本应胜出" | L544 "内容完全不同不应合并"
ENSTR*: L372 "Hello World from SSE" | L463 "Text part time.end must be force-completed" | L464 "Reasoning part time.end must be force-completed" | L466 "Assistant message time.completed must be set" | L481 "Already-ended part keeps its original end time" | L508 "Got it. Message 1 received." | L522 "Got it. Message" | L537 "First part" | (+1 more)
TESTS-EN: `handles MessageUpdated - add new` | `handles MessageUpdated - update existing` | `handles MessageUpdated - sorts by created ascending` | `step ended update preserves model metadata from step started` | `assistant update with model overwrites existing null model` | `rest authority preserves SSE tokens when REST payload lacks them` | `rest authority still overwrites when REST carries real values` | `handles MessageRemoved` | `handles MessageRemoved also removes parts` | `handles MessagePartUpdated - add new part` | `handles MessagePartUpdated - replace existing part` | `handles MessagePartDelta - appends text` | `handles MessagePartDelta - appends to reasoning` | `handles MessagePartDelta creates synthetic part when partId missing` | (+21 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerTest.kt","loc":546,"lang":"中文","zh":39,"en":0,"kdoc":0,"tests":35,"cls":"MessageEventHandlerTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/PermissionEventHandlerTest.kt
LOC 103 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 9 | btFuns 9 | PermissionEventHandlerTest
LEX: session,event,turn,tool,permission,sse
FREQ: handler×40 server×18 handle×15 equals×6 clear×6 removes×4 target×3
TESTS-EN: `handles PermissionAsked` | `handles PermissionReplied` | `removePermission removes across all sessions` | `setPermissions replaces existing` | `setPermissions with empty list removes session entry` | `returns false for non-permission events` | `clearForSession removes permissions for single session` | `clearForServer removes permissions for session set` | `clearAll resets everything`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/PermissionEventHandlerTest.kt","loc":103,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":9,"cls":"PermissionEventHandlerTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerFullTest.kt
LOC 851 | lang 中文 (zh 34/en 0, kdoc 0) | @Test 55 | btFuns 0 | SessionNextEventHandlerFullTest
LEX: session,message,event,part,token,tool,agent,provider,compaction,sse,retry,context
FREQ: handler×236 handle×87 progress×81 equals×64 step×59 started×47 state×46 sequence×34 call×33 track×33 model×32 current×27 switched×25 active×25
C-ZH: L17 [line]  ============ Agent 切换 - 多会话与覆盖 ============ | L69 [line]  ============ 工具进度 - 完整生命周期 ============ | L103 [line]  启动并完成工具 c1 | L116 [line]  尝试更新已移除的工具 | L123 [line]  c1 已被移除，但 s1 条目仍以空列表存在 | L135 [line]  由于未启动任何工具，不应存在会话条目 | L231 [line]  仅更新第一个工具的进度 | L241 [line]  c2 保持不变 | L258 [line]  仅完成 c1 | L293 [line]  ============ 工具空操作事件 ============ | L317 [line]  ============ 步骤进度 - 边界情况 ============ | L397 [line]  不应抛异常 | L431 [line]  ============ 压缩状态 - 边界情况 ============ | L490 [line]  ============ Shell 状态 ============ | L513 [line]  ============ 重试 ============ | L543 [line]  ============ 空操作事件 ============ | L550 [line]  仅验证不崩溃且状态干净 | L571 [line]  ============ 序号跟踪 ============ | L586 [line]  跳过 3 —— 应检测到断档 | L595 [line]  断档为 1 | L598 [line]  断档 | L615 [line]  断档 | (+12 more)
ENSTR*: L95 "all passed" | L436 "context full"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerFullTest.kt","loc":851,"lang":"中文","zh":34,"en":0,"kdoc":0,"tests":55,"cls":"SessionNextEventHandlerFullTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/QuestionEventHandlerTest.kt
LOC 155 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 13 | btFuns 13 | QuestionEventHandlerTest
LEX: session,message,event,turn,tool,question,sse,merge
FREQ: handler×48 rest×23 server×18 handle×17 call×8 equals×6 domain×5 model×5 asked×5 copy×4 clear×4 handles×3 replied×3 rejected×3
C-ZH: L80 [line]  SSE 版：无 tool（V1 question.asked 事件缺 tool 字段） | L85 [line]  REST 版：带 tool.messageID | L125 [line]  并集语义：REST 空列表（轮询延迟窗口）不删除 SSE 已有条目—— | L126 [line]  删除由 SSE QuestionReplied/Rejected/removeQuestion 驱动
TESTS-EN: `handles QuestionAsked` | `handles QuestionReplied` | `handles QuestionRejected` | `removeQuestion removes across all sessions` | `setQuestions replaces existing` | `setQuestions with empty list removes session entry` | `mergeFromREST backfills tool when SSE lacks it` | `mergeFromREST keeps SSE tool when present` | `mergeFromREST adds REST-only question and keeps SSE extra` | `mergeFromREST with empty list keeps SSE entries` | `returns false for non-question events` | `clearForSession removes for single session` | `clearAll resets everything`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/QuestionEventHandlerTest.kt","loc":155,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":13,"cls":"QuestionEventHandlerTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerTest.kt
LOC 279 | lang 英文 (zh 0/en 7, kdoc 0) | @Test 18 | btFuns 18 | SessionNextEventHandlerTest
LEX: session,message,event,part,turn,token,tool,agent,provider,compaction,sse,context
FREQ: handler×60 handle×31 progress×29 step×25 model×19 state×19 started×18 equals×17 call×11 shell×11 code×10 switched×8 current×8 active×7
C-EN*: L18  ============ Agent / Model State ============ | L39  ============ Tool Progress ============ | L181  ============ Compaction State ============ | L228  ============ SseEventHandler Integration ============
   (+3 trivial en comments)
ENSTR*: L186 "context full"
TESTS-EN: `AgentSwitched updates currentAgent` | `ModelSwitched updates currentModel` | `ToolInputStarted tracks running tool` | `ToolProgress updates running tool` | `ToolProgress accumulates content into output` | `ToolSuccess removes running tool` | `ToolFailed removes running tool` | `StepStarted tracks current step` | `StepEnded clears step progress` | `StepFailed clears step progress` | `CompactionStarted sets compaction progress` | `CompactionEnded clears compaction progress` | `ShellStarted sets shell state` | `ShellEnded clears shell state` | (+4 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionNextEventHandlerTest.kt","loc":279,"lang":"英文","zh":0,"en":7,"kdoc":0,"tests":18,"cls":"SessionNextEventHandlerTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerV2ChainTest.kt
LOC 142 | lang 中文 (zh 19/en 0, kdoc 2) | @Test 2 | btFuns 2 | MessageEventHandlerV2ChainTest
LEX: session,message,event,part,tool,agent,subagent,sse,archive,notification
FREQ: assistant×23 asst×19 handler×17 emit×17 equals×15 json×13 user×13 delta×12 reasoning×11 updated×9 input×9 ordinal×9 synthetic×9 completed×8
C-ZH: L19 [kdoc] V2 事件链集成测试——v2 生命周期事件经 V2SseMapper + MessageEventHandler | L20 [kdoc] 的端到端状态（docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §3.2 实测序列）。 | L48 [line]  1. 用户消息播种 | L50 [line]  2. assistant 消息创建 | L52 [line]  3. reasoning 流 | L57 [line]  4. text 流 | L62 [line]  5. tool 生命周期 | L68 [line]  6. step 结束（成本更新） | L71 [line]  ============ 断言最终状态 ============ | L73 [line]  消息：user + assistant | L82 [line]  用户消息播种 part | L86 [line]  assistant parts：reasoning + text + tool（按 ordinal 派生 id） | L109 [line]  2026-08-12：SseClientV2 消费 session.input.promoted 后构造的 | L110 [line]  synthetic MessageUpdated（role="synthetic" + summary.body=完整标记文本） | L111 [line]  → handleMessageUpdated 播种 Part.Text → SyntheticNotificationCard 实时渲染。 | L112 [line]  对应实测服务器 payload：input.type="synthetic"，text 为 | L113 [line]  <subagent id=... state=completed description=...>结果</subagent> | L128 [line]  消息入库 + role 标记 | L135 [line]  parts 播种（summary.body → Part.Text）
ZHSTR: L42 "意外事件类型: ${e::class.simpleName}" | L49 """{"sessionID":"ses_1","inputID":"msg_user_1","input":{"type":"user","data":{"text":"你好"} | L54 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"delta":"思考"}""" | L55 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"delta":"中"}""" | L56 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"text":"思考中完整"}""" | L75 "应有 2 条消息" | L85 "你好" | L88 "应有 3 个 part（reasoning/text/tool）" | L91 "思考中完整" | L98 "tool 应为 Completed" | (+2 more)
ENSTR*: L60 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"delta":"  | L61 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"text":"He | L65 """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","text":"
TESTS-EN: `full v2 event chain produces message and parts` | `synthetic message updated seeds message and text part for realtime notification`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MessageEventHandlerV2ChainTest.kt","loc":142,"lang":"中文","zh":19,"en":0,"kdoc":2,"tests":2,"cls":"MessageEventHandlerV2ChainTest"}









═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/V2PartIdContractTest.kt
LOC 113 | lang 中文 (zh 7/en 0, kdoc 7) | @Test 4 | btFuns 4 | V2PartIdContractTest
LEX: session,message,event,part,agent,stream,sse,diff,dedup,merge,upsert
FREQ: reasoning×26 handler×15 json×14 ordinal×13 rest×10 assistant×10 time×9 emit×9 equals×8 delta×8 domain×6 model×6 filter×6 start×6
C-ZH: L20 [kdoc] #109（D2-01）：V2 part 身份契约 = (messageID, type, ordinal)。 | L22 [kdoc] 实测依据（2026-08-14 真机抓帧 next-17430 + 服务器二进制 TUI 片段键 | L23 [kdoc] k(messageID,"text",ordinal)）：ordinal 按类型独立计数——同一消息 | L24 [kdoc] reasoning[0] 与 text[0] 并存。旧 derivePartId 漏 type → id 碰撞： | L25 [kdoc] text.started 按 id 命中 Reasoning part 并替换（推理内容丢失）； | L26 [kdoc] REST（id=""）与 SSE（派生 id）契约错位 → mergePartsList 双保留（双份渲染）。 | L52 [kdoc]  真实事件序列：同消息 reasoning[ordinal=0] + text[ordinal=0]（按类型计数）。
ENSTR*: L48 "unexpected event: " | L68 "expect 2 parts (reasoning + text)" | L75 "part ids must differ by type" | L86 "no duplicate text/reasoning after REST merge (D2-01)" | L102 "blank-id legacy part dedups with derived-id part"
TESTS-EN: `reasoning and text with same ordinal do not collide` | `rest merge after sse stream does not duplicate text` | `legacy blank id rest parts dedup against derived id sse parts` | `reasoning ended part time start is plausible timestamp`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/V2PartIdContractTest.kt","loc":113,"lang":"中文","zh":7,"en":0,"kdoc":7,"tests":4,"cls":"V2PartIdContractTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionEventHandlerTest.kt
LOC 243 | lang 混合 (zh 6/en 2, kdoc 0) | @Test 22 | btFuns 22 | SessionEventHandlerTest
LEX: session,message,event,turn,sse,revert,busy,idle,diff,merge,upsert
FREQ: handler×72 server×56 handle×36 updated×27 created×19 equals×17 handles×13 handled×8 status×8 project×8 clear×8 time×7 deleted×6 copy×5
C-ZH: L204 [line]  #134（D2-L54）：revert=null 的 SessionUpdated 清除本地清除标志； | L205 [line]  副作用从 update lambda 移出后语义不变（CAS 重试不重复执行）。 | L215 [line]  用户发消息 → 本地清除 revert | L219 [line]  服务器确认 revert=null → 清除标志（副作用移出 update lambda 后仍生效） | L222 [line]  标志已清：后续新 revert 不再被抑制（陈旧抑制只保护确认前的窗口） | L235 [line]  服务器陈旧 revert 恢复尝试 → 被抑制（本地清除优先）
C-EN*: L88  SessionStatus is acknowledged (returns true) but no longer tracked locally — | L89  SessionStateService is the single source of truth for status.
ZHSTR: L75 "SessionDeleted 后 lastUserMessageTime 不得残留（#96 泄漏）" | L79 "SessionDeleted 后 sessionDiffs 不得残留"
TESTS-EN: `handles SessionCreated` | `handles SessionUpdated - update existing` | `handles SessionUpdated - upsert new` | `handles SessionDeleted` | `SessionDeleted clears lastUserMessageTime and sessionDiffs (#96)` | `handles SessionStatus - acknowledged, no local status state` | `handles SessionIdle - acknowledged, no local status state` | `handles SessionDiff` | `handles VcsBranchUpdated` | `handles ProjectUpdated` | `returns false for non-session events` | `clearForServer removes only target server sessions` | `clearAll resets everything` | `setSessions merges correctly` | (+8 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/SessionEventHandlerTest.kt","loc":243,"lang":"混合","zh":6,"en":2,"kdoc":0,"tests":22,"cls":"SessionEventHandlerTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MiscEventHandlerTest.kt
LOC 90 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 8 | btFuns 8 | MiscEventHandlerTest
LEX: session,message,event,turn,sse,task,pending
FREQ: handler×24 todo×16 updated×14 server×13 todos×11 handle×10 clear×6 handles×4 domain×3 model×3 time×3 info×3 misc×3 created×3
TESTS-EN: `handles TodoUpdated` | `handles PtyCreated` | `handles CommandExecuted` | `handles LspUpdated` | `returns false for unhandled events` | `clearForSession removes todos` | `clearForServer removes todos for session set` | `clearAll resets todos`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/MiscEventHandlerTest.kt","loc":90,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":8,"cls":"MiscEventHandlerTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/UpsertStrategyEquivalenceTest.kt
LOC 189 | lang 混合 (zh 21/en 3, kdoc 9) | @Test 8 | btFuns 8 | UpsertStrategyEquivalenceTest
LEX: session,message,event,part,stream,sse,snapshot,merge,upsert
FREQ: modern×32 rest×23 existing×22 time×19 updated×18 equals×16 legacy×15 incoming×14 strategy×10 info×10 append×10 handler×9 state×9 created×9
C-ZH: L11 [kdoc] 验证 [MessageEventHandler.upsertMessages] 三策略与原三方法（setMessages/ | L12 [kdoc] mergeMessages/replaceMessages）的输出完全等价。 | L14 [kdoc] 方法：在两个 handler 实例上用相同 fixture 分别调用旧方法与新策略， | L15 [kdoc] 断言 messages 与 parts 的 StateFlow 快照逐字段相等。 | L28 [kdoc]  在两个 handler 上播种相同的 SSE 累积状态。 | L98 [line]  REST message info 覆盖（completed 时间被 REST 设置） | L101 [line]  但 parts 仍保留更长的 SSE 文本（mergePartsList 更长文本胜出） | L110 [line]  APPEND_ONLY 的语义：仅补充缺失。先播种一个 existing 消息， | L111 [line]  再传入两条（一条 existing 一条新），验证 existing 不变 + 新增。 | L118 [line]  existing parts（不应被 APPEND_ONLY 覆盖） | L123 [line]  incoming：existing 消息（短文本）+ 新消息 | L142 [line]  mergeMessages 对 existing 的 parts 不做 mergePartsList，仅添加新 messageId 的 parts | L154 [line]  existing parts 不被合并——保留 SSE 文本 | L160 [kdoc] 回归护栏（2026-08-10）：APPEND_ONLY 用于"分页加载更早消息"——incoming 只含更早消息， | L161 [kdoc] 不含现有最新消息。原实现 `incomingMsgs.map { ... }` 把 _messages **替换**为更早消息， | L162 [kdoc] 导致最新（底部）消息从对话流中消失（用户实证：上滑分页后下滑看不到最底部消息）。 | L163 [kdoc] 正确语义：existing（含最新）+ incoming（更早）合并，existing 必须保留。 | L167 [line]  先有最新消息（底部，用户当前看到的） | L174 [line]  分页加载更早消息（incoming 只有更早的，不含 latest） | L183 [line]  最底部（最新）消息必须保留 | L186 [line]  合并后按 created 排序（oldest first —— combine 依赖写入路径有序）
C-EN*: L49  ============ SSE_PRIORITY == setMessages ============ | L80  ============ REST_AUTHORITY == replaceMessages ============ | L106  ============ APPEND_ONLY == mergeMessages ============
ENSTR*: L36 "Hello World from SSE" | L119 "old SSE text" | L184 "latest message must be preserved" | L185 "older message must be added"
TESTS-EN: `upsertMessages SSE_PRIORITY equals setMessages` | `SSE_PRIORITY preserves SSE streaming text over shorter REST snapshot` | `SSE_PRIORITY merges REST completed time into SSE incomplete message` | `upsertMessages REST_AUTHORITY equals replaceMessages` | `REST_AUTHORITY prefers incoming message info but preserves SSE-fresh longer parts` | `upsertMessages APPEND_ONLY equals mergeMessages` | `APPEND_ONLY preserves existing SSE-fresh parts and does not merge` | `APPEND_ONLY preserves existing latest messages when paging older`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/handler/UpsertStrategyEquivalenceTest.kt","loc":189,"lang":"混合","zh":21,"en":3,"kdoc":9,"tests":8,"cls":"UpsertStrategyEquivalenceTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/PtyToTermlibAdapterTest.kt
LOC 158 | lang 中文 (zh 14/en 0, kdoc 4) | @Test 5 | btFuns 5 | PtyToTermlibAdapterTest,FakePtySocket
LEX: session,event,stream,sse,idle,patch,terminal
FREQ: socket×31 adapter×26 input×17 write×12 output×10 kotlinx×8 coroutines×8 release×8 equals×7 termlib×7 scope×6 frames×6 await×6 keyboard×6
C-ZH: L59 [line]  以 termlib 的方式驱动键盘回调。 | L62 [line]  给 dispatchKeyboardOutput 中的 launch{} 一个运行的机会。 | L71 [line]  适配器的 onKeyboardInput 路径（dispatchKeyboardOutput）只 | L72 [line]  接触 socket。通过跟踪 writeInput lambda 的调用次数， | L73 [line]  验证键盘分发期间不会调用 writeInput。 | L122 [line]  第二次调用不得抛异常 | L123 [line]  release() 会在 test scope 上异步启动 socket.close()； | L124 [line]  断言前先排空待处理的协程。 | L132 [kdoc] 最小化的内存版 PtySocket。真实的 PtySocket 委托给 Ktor | L133 [kdoc] ClientWebSocketSession；我们只需要 readLoop + send + close 语义。 | L135 [kdoc] PtySocket 是 `open class`（P1-6 修复），因此该 fake 可以重写其方法， | L136 [kdoc] 而无需底层 WebSocket 会话。 | L154 [line]  阻塞直到被取消，使 reader 协程像真实协程一样保持存活 | L155 [line]  （真实实现会阻塞在 WebSocket 接收通道上）。
ENSTR*: L88 "writeInput must NOT be invoked from dispatchKeyboardOutput"
TESTS-EN: `writeInput bytes are forwarded when socket emits text` | `keyboard output from the emulator is forwarded to the socket` | `onKeyboardInput callback never calls emulator methods (reentrancy guard)` | `version bumps on every writeInput` | `release is idempotent and closes the socket`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/PtyToTermlibAdapterTest.kt","loc":158,"lang":"中文","zh":14,"en":0,"kdoc":4,"tests":5,"cls":"PtyToTermlibAdapterTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/RemoteTerminalSessionEmulatorTest.kt
LOC 52 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 0 | RemoteTerminalSessionEmulatorTest
LEX: session,turn,sse,patch,terminal
FREQ: emulator×6 bytes×6 prompt×5 remote×4 output×4 host×4 bridge×3 update×3 screen×3
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/terminal/RemoteTerminalSessionEmulatorTest.kt","loc":52,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"RemoteTerminalSessionEmulatorTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/data/update/UpdatePolicyTest.kt
LOC 177 | lang 英文 (zh 0/en 5, kdoc 0) | @Test 10 | btFuns 10 | UpdatePolicyTest
LEX: part,draft,sse,diff,fallback
FREQ: release×46 update×45 policy×29 manifest×25 repo×19 github×12 version×7 compare×7 equals×6 beacon×6 available×5 releases×5 beta×5 code×4
C-EN*: L168  parseSemVer strips the prerelease suffix, so 1.8.0 vs 1.7.0-beta.1 compares [1,8,0] vs [1,7,0] | L170  Same core version with different prerelease suffixes are considered equal (known limitation)
   (+3 trivial en comments)
TESTS-EN: `manifest transforms only a valid repository release` | `github fallback requires exact tag and release URL` | `github fallback rejects drafts and prereleases` | `version code takes precedence and github falls back to semver` | `manifest requires a positive version code` | `rich manifest requires exact package URL and checksum` | `rich manifest accepts any flavor package name` | `manifest rejects partial or malformed rich metadata but accepts legacy` | `semver comparison handles newer older and equal` | `semver comparison ignores prerelease suffix in core comparison`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/data/update/UpdatePolicyTest.kt","loc":177,"lang":"英文","zh":0,"en":5,"kdoc":0,"tests":10,"cls":"UpdatePolicyTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/debug/FrameStatsWindowTest.kt
LOC 60 | lang 中文 (zh 3/en 0, kdoc 1) | @Test 5 | btFuns 0 | FrameStatsWindowTest
LEX: sse,snapshot
FREQ: equals×14 frame×12 window×10 budget×9 stats×7 capacity×5 jank×3 frames×3
C-ZH: L9 [kdoc] FrameStatsWindow 单测（2026-08-20 第三轮性能检测系统）。 | L28 [line]  插值介于 9.2-10 | L41 [line]  窗口 = [3,4,5]
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/debug/FrameStatsWindowTest.kt","loc":60,"lang":"中文","zh":3,"en":0,"kdoc":1,"tests":5,"cls":"FrameStatsWindowTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AnnotationPromptBuilderTest.kt
LOC 72 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 8 | btFuns 8 | AnnotationPromptBuilderTest
LEX: directory,sse,annotation
FREQ: builder×11 make×9 build×8 project×7 note×5 home×5 user×5 proj×4 path×3 develop×3
ZHSTR: L17 "请按标注修改" | L18 "# 文件备注" | L19 "对于 /project/src/App.kt 文件，用户提出了下述备注" | L20 "## 总体备注" | L22 "## 具体备注" | L29 "总体备注" | L35 "无" | L42 "改为\"正确\"的值" | L51 "对于 /home/user/project/src/main/App.kt 文件" | L58 "对于 /home/user/project/src/App.kt 文件" | (+1 more)
TESTS-EN: `single annotation with overall note` | `empty overall note omitted` | `multiple annotations numbered by creation order` | `special characters preserved` | `relative path resolved with directory` | `absolute path used directly` | `empty annotation list throws` | `windows drive letter path used directly`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AnnotationPromptBuilderTest.kt","loc":72,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":8,"cls":"AnnotationPromptBuilderTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/AgentRepositoryTest.kt
LOC 15 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 1 | btFuns 1 | AgentRepositoryTest,defines
LEX: agent,sse
FREQ: methods×8 repository×3 load×3 commands×3 search×3 files×3 expected×3 starts×3
ENSTR*: L11 "Expected listAgents in $methods"
TESTS-EN: `interface defines listAgents, loadCommands, searchFiles`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/AgentRepositoryTest.kt","loc":15,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":1,"cls":"AgentRepositoryTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AutoApproveRuleTest.kt
LOC 74 | lang 中文 (zh 2/en 0, kdoc 0) | @Test 8 | btFuns 8 | AutoApproveRuleTest
LEX: session,message,event,tool,directory,permission,sse,diff
FREQ: rule×32 matches×17 auto×9 approve×9 project×9 bash×7 home×6 call×5 domain×3 model×3 user×3 pattern×3
C-ZH: L62 [line]  新增P2（2026-08-19）：历史遗留 toolName="" 规则与空名事件互相匹配是伪命中 | L70 [line]  新增P2（2026-08-19）：空名事件（如评估端点产生的 ask）不应命中任何规则
TESTS-EN: `wildcard rule matches everything` | `specific tool name matches` | `different tool name does not match` | `session-scoped rule only matches same session` | `directory pattern matches specific directory` | `permission name used when tool is null` | `blank rule tool name never matches even blank event - P2 empty-name artifact` | `blank event permission never matches even wildcard rule`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/AutoApproveRuleTest.kt","loc":74,"lang":"中文","zh":2,"en":0,"kdoc":0,"tests":8,"cls":"AutoApproveRuleTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/OffsetConverterTest.kt
LOC 95 | lang 中文 (zh 1/en 0, kdoc 0) | @Test 14 | btFuns 14 | OffsetConverterTest
LEX: turn,sse
FREQ: offset×57 line×42 char×21 equals×19 converter×19 content×8 client×5 newline×4 hello×4 ktor×3 crlf×3
C-ZH: L8 [line]  真实样本：OpenCodeApi.kt 前几行
TESTS-EN: `empty string offset 0 returns 1,1` | `single line no newline offset 2 returns 1,3` | `LF newline offset 4 returns 2,2` | `CRLF newline offset 4 returns 2,1` | `CRLF offset 3 returns 1,4` | `CRLF offset 5 returns 2,2` | `pure CR newline offset 4 returns 2,2` | `mixed line endings all handled` | `offset exceeds content length clamps` | `negative offset treated as 0` | `realistic kotlin sample multiline` | `lineColToCharOffset round-trip for single line` | `lineColToCharOffset for multiline LF` | `lineColToCharOffset for line beyond content returns end`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/OffsetConverterTest.kt","loc":95,"lang":"中文","zh":1,"en":0,"kdoc":0,"tests":14,"cls":"OffsetConverterTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/DraftTest.kt
LOC 21 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | DraftTest
LEX: turn,draft,sse
FREQ: 
TESTS-EN: `isEmpty returns true for default draft` | `isEmpty returns false when text is present` | `isEmpty returns false when imageUris is non-empty`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/DraftTest.kt","loc":21,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"DraftTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SerializationTest.kt
LOC 1253 | lang 混合 (zh 18/en 11, kdoc 3) | @Test 76 | btFuns 76 | SerializationTest
LEX: session,message,event,part,turn,token,tool,agent,directory,question,permission,provider,config,compaction,sse,revert,retry,busy,idle,snapshot,diff,patch,terminal,task,context,workspace,pending,chunk
FREQ: equals×205 json×186 decoded×160 encoded×115 serializer×108 model×81 decode×62 encode×47 assistant×46 file×43 state×43 info×41 time×39 status×34
C-ZH: L36 [kdoc] domain 模型 JSON 序列化/反序列化的特征测试。 | L38 [kdoc] 这些测试锁定现有序列化契约，使重构无法意外改变 JSON 字段名、顺序或结构。 | L40 [kdoc] Phase 0 安全网：如果这些测试失败，说明重构破坏了 API 契约。 | L589 [line]  input 是 Map<String, JsonElement>，因此值是 JsonPrimitive | L632 [line]  注意：ToolState 子类没有 `status` 属性，因此编码 | L633 [line]  ToolState.Completed 不会产生 `status` 字段用于多态反序列化。 | L634 [line]  使用手动构造的包含判别器的 JSON 进行测试。 | L747 [line]  id.take(8) 回退逻辑：需要非空 id | L755 [line]  注意：Part 子类没有 `type` 属性，因此编码 | L756 [line]  Part.Text 不会产生 `type` 字段用于多态反序列化。 | L757 [line]  我们改为使用真实的服务器 JSON 测试反序列化。 | L783 [line]  ============ SseEvent 数据类（单独序列化）============ | L918 [line]  ============ 请求/响应 DTO ============ | L1161 [line]  注意：SessionStatus 没有自定义多态序列化器。 | L1162 [line]  密封类子类各自 @Serializable，`type` 字段由 | L1163 [line]  SseClient.parseEventByType() 手动用作判别器。 | L1164 [line]  这里测试各子类的单独序列化。 | L1169 [line]  Idle 是 data object，序列化为 {}
C-EN*: L49  ============ Session ============ | L162  ============ Message ============ | L323  ============ Part ============ | L582  ============ ToolState ============ | L650  ============ ToolRef ============ | L689  ============ FileDiff ============ | L751  ============ MessageWithParts ============ | L1160  ============ SessionStatus ============
   (+3 trivial en comments)
ENSTR*: L59 "Test Session" | L128 """{
            "id": "s1",
            "time": {"created": 1000, "updated": 20 | L166 """{
            "id": "msg_1",
            "sessionID": "sess_1",
            " | L177 "Should be User message" | L193 "Should contain sessionID (uppercase D)" | L194 "Should NOT contain sessionId" | L199 """{
            "id": "msg_1",
            "sessionID": "sess_1",
            " | L217 """{
            "id": "msg_2",
            "sessionID": "sess_1",
            " | (+21 more)
TESTS-EN: `Session round-trip with SerialName fields` | `Session serializes projectID not projectId` | `Session serializes workspaceID not workspaceId` | `Session serializes parentID not parentId` | `Session with Revert deserializes correctly` | `Session with summary deserializes` | `Message User deserializes via role field` | `Message User serializes sessionID not sessionId` | `Message User with format deserializes` | `Message Assistant deserializes via role field` | `Message Assistant serializes parentID, modelID, providerID` | `Message Assistant with error deserializes` | `Message Assistant round-trip` | `Part Text deserializes via type field` | (+62 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SerializationTest.kt","loc":1253,"lang":"混合","zh":18,"en":11,"kdoc":3,"tests":76,"cls":"SerializationTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/LinkClassifierTest.kt
LOC 183 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 33 | btFuns 33 | LinkClassifierTest
LEX: directory,config,sse
FREQ: path×70 file×42 link×38 likely×38 classifier×21 target×17 classify×15 classified×14 absolute×13 relative×12 project×5 equals×4 home×4 user×4
   (+1 trivial en comments)
TESTS-EN: `http URL classified as Web` | `https URL classified as Web` | `ftp URL classified as Web` | `mailto URL classified as Web` | `Unix absolute path classified as AbsolutePath` | `Windows absolute path classified as AbsolutePath` | `Windows absolute path with forward slash classified as AbsolutePath` | `relative path classified as RelativePath` | `relative path with dots classified as RelativePath` | `relative path with subdirectory classified as RelativePath` | `bare filename classified as RelativePath` | `file URI classified as AbsolutePath` | `uppercase HTTP scheme classified as Web` | `mixed case HTTPS scheme classified as Web` | (+19 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/LinkClassifierTest.kt","loc":183,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":33,"cls":"LinkClassifierTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionNextEventTest.kt
LOC 282 | lang 英文 (zh 0/en 10, kdoc 0) | @Test 30 | btFuns 30 | SessionNextEventTest
LEX: session,message,event,part,tool,agent,provider,compaction,stream,sse,context
FREQ: json×91 equals×37 decode×29 parses×26 correctly×25 started×25 delta×23 progress×22 ended×20 step×18 reasoning×13 model×12 switched×12 call×11
C-EN*: L12  ============ Agent/Model Switching ============ | L32  ============ Text Streaming ============ | L58  ============ Reasoning Streaming ============ | L84  ============ Tool Execution ============ | L202  ============ Compaction ============ | L255  Unknown events are created by the parser, not directly deserialized
   (+4 trivial en comments)
ENSTR*: L188 """{"type":"session.next.shell.started","sessionID":"s1","messageID":"m1","partI | L206 """{"type":"session.next.compaction.started","sessionID":"s1","messageID":"m1"," | L209 "context full" | L239 """{"type":"session.next.retried","sessionID":"s1","attempt":2,"error":"rate lim
TESTS-EN: `AgentSwitched parses correctly` | `ModelSwitched parses correctly` | `TextStarted parses correctly` | `TextDelta parses correctly` | `TextEnded parses correctly` | `ReasoningStarted parses correctly` | `ReasoningDelta parses correctly` | `ReasoningEnded parses correctly` | `ToolInputStarted parses correctly` | `ToolInputDelta parses correctly` | `ToolCalled parses correctly` | `ToolProgress parses correctly` | `ToolProgress parses with content` | `ToolProgress without content defaults to empty` | (+16 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionNextEventTest.kt","loc":282,"lang":"英文","zh":0,"en":10,"kdoc":0,"tests":30,"cls":"SessionNextEventTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/ApiResultTest.kt
LOC 170 | lang 英文 (zh 0/en 2, kdoc 0) | @Test 24 | btFuns 24 | ApiResultTest
LEX: turn,sse,retry
FREQ: error×124 status×15 code×15 equals×14 server×13 success×12 rate×12 limit×12 maps×10 transient×10 found×6 auth×5 many×4 requests×4
   (+2 trivial en comments)
TESTS-EN: `Success holds data` | `Error holds ApiError` | `isSuccess returns true for Success` | `isSuccess returns false for Error` | `getOrNull returns data for Success` | `getOrNull returns null for Error` | `getOrDefault returns data for Success` | `getOrDefault returns default for Error` | `401 maps to AuthError` | `403 maps to ForbiddenError` | `404 maps to NotFoundError` | `429 without headers maps to RateLimitError with zero retry` | `429 with retry-after header maps to RateLimitError` | `429 with retry-after-ms header maps to RateLimitError` | (+10 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/ApiResultTest.kt","loc":170,"lang":"英文","zh":0,"en":2,"kdoc":0,"tests":24,"cls":"ApiResultTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionStateFSMTest.kt
LOC 76 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 8 | btFuns 8 | SessionStateFSMTest
LEX: session,event,part,compaction,stream,sse,abort,busy,idle
FREQ: state×23 activity×21 equals×11 transition×9 core×8 status×8 waiting×7 force×7 complete×7 compacting×6 client×5 ended×4 started×4 rest×3
TESTS-EN: `Idle + ClientSendParts to Busy_Waiting` | `Busy_Streaming + TextEnded to Busy_Waiting` | `Busy_Streaming + SseIdle to Idle_null + forceComplete` | `Idle + TextStarted to suspicious, unchanged` | `Busy + RestValidation_Idle to Idle_null + forceComplete` | `CompactionStarted saves activity, CompactionEnded restores` | `invariant - Idle state never holds activity` | `ClientAbort always forceComplete`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/model/SessionStateFSMTest.kt","loc":76,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":8,"cls":"SessionStateFSMTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/CreateDirectoryUseCaseTest.kt
LOC 97 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 5 | btFuns 5 | CreateDirectoryUseCaseTest
LEX: session,turn,directory,sse,terminal
FREQ: repository×19 case×13 command×11 manage×10 server×10 success×9 parent×9 create×8 file×7 shell×7 newdir×6 execute×6 domain×5 equals×3
C-ZH: L43 [line]  R6: 临时会话必须在成功路径上被删除 | L45 [line]  成功路径不应调用 executeCommand 回退 | L64 [line]  R6: finally 清理必须在任何路径上执行 | L71 [line]  即使创建失败，临时会话也必须被删除
TESTS-EN: `runShellCommand success creates directory and cleans up temp session` | `runShellCommand failure falls back to executeCommand` | `temp session is cleaned up even when both shell and execute fail` | `invalid folder name returns failure without creating session` | `createSession is called with mkdir title and parent directory`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/CreateDirectoryUseCaseTest.kt","loc":97,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":5,"cls":"CreateDirectoryUseCaseTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/tracker/TokenStatsTrackerConcurrencyTest.kt
LOC 71 | lang 中文 (zh 3/en 0, kdoc 3) | @Test 3 | btFuns 3 | TokenStatsTrackerConcurrencyTest
LEX: token,sse,merge
FREQ: tracker×19 stats×12 coroutine×10 update×10 input×8 coroutines×7 equals×6 copy×5 kotlinx×4 async×3 await×3 scope×3 output×3
C-ZH: L11 [kdoc] #134（D2-L39）：TokenStatsTracker.update 并发安全。 | L12 [kdoc] 原实现裸读-改-写（_stats.value = _stats.value.block()）——并发 update | L13 [kdoc] 丢更新；StateFlow.update CAS 循环保证原子合并。
ZHSTR: L34 "并发 update 不得丢计数"
TESTS-EN: `concurrent updates do not lose increments` | `concurrent mixed updates merge atomically` | `update preserves other fields`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/tracker/TokenStatsTrackerConcurrencyTest.kt","loc":71,"lang":"中文","zh":3,"en":0,"kdoc":3,"tests":3,"cls":"TokenStatsTrackerConcurrencyTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepositoryTest.kt
LOC 32 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | ChatRepositoryTest,defines,defines
LEX: session,message,event,part,tool,question,permission,compaction,sse,revert,patch,pending
FREQ: methods×20 starts×15 repository×4 command×4 progress×4 chat×3 prompt×3 async×3 send×3 respond×3
ENSTR*: L12 "sendMessage present (existing)" | L13 "revertSession missing" | L14 "unrevertSession missing" | L15 "respondPermission missing" | L16 "getParts missing" | L17 "listPendingPermissions missing" | L18 "listPendingQuestions missing" | L19 "replyToQuestion missing" | (+3 more)
TESTS-EN: `interface defines promptAsync, sendMessage, revertSession, unrevertSession, respondPermission, selectModel` | `interface defines EventDispatcher flow exposure methods`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ChatRepositoryTest.kt","loc":32,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"ChatRepositoryTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepositoryTest.kt
LOC 26 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 1 | btFuns 1 | SessionRepositoryTest,defines
LEX: session,message,stream,sse,abort,archive
FREQ: methods×16 starts×14 repository×3 rename×3 fork×3 export×3 statuses×3 flow×3
ENSTR*: L11 "abort missing" | L14 "exportSessionToStream missing" | L15 "getSessionStatusesFlow missing" | L16 "archive missing" | L17 "unarchive missing" | L18 "shareSession missing" | L19 "unshareSession missing" | L20 "compactSession missing" | (+4 more)
TESTS-EN: `interface defines abort, rename, fork, exportSession, getSessionStatusesFlow`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/SessionRepositoryTest.kt","loc":26,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":1,"cls":"SessionRepositoryTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ToolSnapshotCacheBoundedTest.kt
LOC 41 | lang 中文 (zh 3/en 0, kdoc 3) | @Test 2 | btFuns 2 | ToolSnapshotCacheBoundedTest
LEX: tool,directory,sse,snapshot
FREQ: cache×20 path×3
C-ZH: L9 [kdoc] #98（H-7）：ToolSnapshotCache 有界性——快照含整文件内容（MB 级）， | L10 [kdoc] 导航取消/失败时 onCleared 不触发 → 无界版本永驻。上限 200 条 | L11 [kdoc] （对齐 DirectoryManager.dirCache 标杆），插入序淘汰最旧。
ZHSTR: L25 "超出上限后保持 200 条" | L26 "最旧条目被淘汰" | L27 "次旧条目被淘汰" | L28 "最新条目保留" | L29 "早期保留边界 p5 存在"
TESTS-EN: `cache evicts oldest beyond 200 entries` | `clear by ids removes entries`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/repository/ToolSnapshotCacheBoundedTest.kt","loc":41,"lang":"中文","zh":3,"en":0,"kdoc":3,"tests":2,"cls":"ToolSnapshotCacheBoundedTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseTest.kt
LOC 36 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | ManageSessionUseCaseTest
LEX: session,message,turn,sse,page
FREQ: repository×9 case×5 domain×4 server×4
TESTS-EN: `getSession delegates to sessionRepository` | `listMessages delegates to sessionRepository`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseTest.kt","loc":36,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"ManageSessionUseCaseTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ListSessionsUseCaseTest.kt
LOC 44 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | ListSessionsUseCaseTest
LEX: session,turn,directory,sse,cursor,paginat
FREQ: repository×10 server×5 domain×3 expected×3
TESTS-EN: `passes all pagination parameters to repository` | `uses default values when optional params omitted`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ListSessionsUseCaseTest.kt","loc":44,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"ListSessionsUseCaseTest"}








═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseExtendedTest.kt
LOC 100 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 7 | btFuns 7 | ManageSessionUseCaseExtendedTest
LEX: session,message,part,turn,sse,archive
FREQ: repository×17 server×14 case×12 delete×12 time×8 success×7 imported×6 equals×5 delegates×5 base×4 domain×3 manage×3 title×3
TESTS-EN: `deleteMessage delegates to sessionRepository` | `deleteMessage returns false on failure` | `deleteMessagePart delegates to sessionRepository` | `deleteMessagePart returns false on failure` | `archiveSession delegates to sessionRepository` | `unarchiveSession delegates to sessionRepository` | `importSession delegates to sessionRepository`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageSessionUseCaseExtendedTest.kt","loc":100,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":7,"cls":"ManageSessionUseCaseExtendedTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt
LOC 199 | lang 中文 (zh 14/en 0, kdoc 5) | @Test 10 | btFuns 0 | MessagePaginationUseCaseTest
LEX: session,message,part,turn,provider,sse,cursor,paginat,page,archive,upsert
FREQ: store×31 load×29 repository×25 created×16 network×15 older×15 success×14 equals×13 case×13 expected×12 source×10 domain×9 local×9 codec×8
C-ZH: L46 [line]  返回本地 + 增量合并 | L48 [line]  增量落库 | L78 [line]  网络失败 → 回退本地缓存（缓存优先理念：有缓存不显示空） | L93 [line]  无本地缓存且网络失败 → 保持 failure（UI 显示加载失败态） | L126 [line]  网络不调用 | L128 [line]  不落热表（防死循环） | L152 [line]  归档空（坏桶等） | L174 [kdoc] 回归护栏（2026-08-10）：网络分页游标（networkBeforeCreated）非空时—— | L175 [kdoc] 跳过归档检查、直接用 CursorCodec.encode(beforeId, networkBeforeCreated) 请求网络。 | L176 [kdoc] 原实现依赖热表查询 messageCreatedAt(beforeId)——网络游标消息不在热表 | L177 [kdoc] （窗口外不落库）→ 返回 null → before 不编码 → 服务器返回最新 → 分页死循环 | L178 [kdoc] （模拟器实证：beforeId 在 A→B 间交替，每 ~100ms 拉同一批消息）。 | L184 [line]  即使热表查不到 created（游标消息不在热表），也应用网络游标时间编码 | L186 [line]  归档检查不应被触发（网络游标分支直接跳过）
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/MessagePaginationUseCaseTest.kt","loc":199,"lang":"中文","zh":14,"en":0,"kdoc":5,"tests":10,"cls":"MessagePaginationUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManagePermissionUseCaseTest.kt
LOC 47 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | ManagePermissionUseCaseTest
LEX: session,turn,permission,sse,pending
FREQ: repository×11 chat×10 case×6 server×6 equals×4 reply×4 domain×3 delegates×3 success×3
TESTS-EN: `replyToPermission delegates to chatRepository and returns true` | `replyToPermission delegates to chatRepository and returns false` | `listPendingPermissions delegates to chatRepository`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManagePermissionUseCaseTest.kt","loc":47,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"ManagePermissionUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageServerProvidersUseCaseTest.kt
LOC 38 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | ManageServerProvidersUseCaseTest
LEX: turn,provider,sse
FREQ: server×9 repository×7 load×6 case×5 domain×3 failure×3
TESTS-EN: `loadProviders returns provider list` | `loadProviders returns failure on error`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/ManageServerProvidersUseCaseTest.kt","loc":38,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"ManageServerProvidersUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/FindFilesUseCaseTest.kt
LOC 36 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | FindFilesUseCaseTest
LEX: turn,sse
FREQ: repository×10 file×8 find×6 files×6 case×5 expected×3 success×3
TESTS-EN: `invoke delegates to repository with same args` | `invoke passes through custom limit`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/FindFilesUseCaseTest.kt","loc":36,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"FindFilesUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/GetSettingsFlowUseCaseTest.kt
LOC 38 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | GetSettingsFlowUseCaseTest
LEX: turn,sse
FREQ: settings×19 flow×8 repository×7 case×5 await×4 domain×3 equals×3
TESTS-EN: `invoke emits current settings` | `invoke emits default settings`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/GetSettingsFlowUseCaseTest.kt","loc":38,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"GetSettingsFlowUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/DeleteSessionUseCaseTest.kt
LOC 47 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | DeleteSessionUseCaseTest
LEX: session,turn,sse
FREQ: repository×10 case×6 server×6 failure×6 delete×5 exception×4 invoke×3 success×3
ENSTR*: L27 "Session not found"
TESTS-EN: `invoke returns success when repository succeeds` | `invoke returns failure when repository fails` | `invoke returns failure on network error`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/DeleteSessionUseCaseTest.kt","loc":47,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"DeleteSessionUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/AppNotificationDedupTest.kt
LOC 147 | lang 中文 (zh 11/en 0, kdoc 3) | @Test 11 | btFuns 11 | AppNotificationDedupTest
LEX: session,message,event,part,turn,question,permission,sse,diff,patch,notification,dedup
FREQ: server×36 manager×31 holder×24 notify×19 write×16 focused×13 focus×10 mark×8 notified×8 confirm×7 build×6 settings×5 store×5 flow×5
C-ZH: L18 [kdoc] 通知去重键（serverId::sessionId）与抑制（shouldSuppressEvent）的纯函数测试。 | L19 [kdoc] 修复点：sessionId 是服务器内部 ID，不同服务器可能相同—— | L20 [kdoc] 去重 key 必须包含 serverId，否则跨服务器会误去重漏通知。 | L48 [line]  ============ 权限通知去重 ============ | L71 [line]  回归：修复前去重 key 只用 sessionId，服务器 A/B 同 sessionId 会互相误去重 | L81 [line]  模拟 cancelSessionNotifications 内部重置（同一 key 路径） | L86 [line]  ============ 问题通知去重 ============ | L102 [line]  ============ 事件抑制（焦点会话） ============ | L121 [line]  2026-08-16 语义修正（通知 P1）：后台不抑制——用户看不到界面， | L122 [line]  权限通知必须发出（旧行为静默吞通知，可能错过权限请求） | L140 [line]  2026-08-16：补前台状态（新语义仅前台聚焦时抑制）
TESTS-EN: `first permission notification should notify` | `same server session same permission deduplicated after mark` | `same server same session different permission both notified` | `cross server same session id not deduplicated` | `cancel resets dedup allowing re-notification` | `question notification deduplicated per server session` | `cross server question not deduplicated` | `focused session suppresses permission notification` | `focused session NOT suppressed when app in background` | `non focused session not suppressed` | `focused session question also suppressed in foreground`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/AppNotificationDedupTest.kt","loc":147,"lang":"中文","zh":11,"en":0,"kdoc":3,"tests":11,"cls":"AppNotificationDedupTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/logging/AppLoggerTest.kt
LOC 51 | lang 中文 (zh 8/en 0, kdoc 5) | @Test 2 | btFuns 0 | AppLoggerTest
LEX: sse
FREQ: timestamp×8 local×7 logger×4 monotonic×4 pool×4 java×3 concurrent×3 time×3 strictly×3 futures×3
C-ZH: L10 [kdoc] 验证 [AppLogger.nextTimestamp] 的单调性。 | L12 [kdoc] 背景：诊断页 LazyColumn 以 timestamp 参与 key 计算；若同一毫秒内产生 | L13 [kdoc] 多条日志（崩溃捕获、连续错误写入），`System.currentTimeMillis()` 可能 | L14 [kdoc] 返回相同值，导致 "Key was already used" 崩溃。nextTimestamp 通过 CAS | L15 [kdoc] 保证本进程内严格递增。 | L40 [line]  CAS 不变量：全局返回值严格唯一（单调）。线程间收集顺序 | L41 [line]  由调度决定，不能断言拼接顺序 == 排序。 | L43 [line]  每个线程内部也必须严格递增。
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/logging/AppLoggerTest.kt","loc":51,"lang":"中文","zh":8,"en":0,"kdoc":5,"tests":2,"cls":"AppLoggerTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationCursorPolicyTest.kt
LOC 80 | lang 中文 (zh 4/en 0, kdoc 2) | @Test 7 | btFuns 7 | PaginationCursorPolicyTest
LEX: session,config,sse,cursor,paginat
FREQ: newer×13 policy×11 anchor×10 codec×9 supported×9 direction×8 local×7 equals×6 capabilities×5 encode×5 supports×5 unknown×5 domain×4 version×4
C-ZH: L13 [kdoc] #172 游标策略双版本契约 + 能力位映射。 | L14 [kdoc] V2 行为规格来源：2026-08-16 cursor-400 根治注释链（窗口语义，curl 实证）。 | L27 [line]  V2 窗口语义：本地构造锚点不可靠 → 不传 cursor（服务器窗口 + id 去重） | L73 [line]  null（未知/未加载）→ 全开放（原 permissive 比较语义保持）
TESTS-EN: `V1 local anchor encodes id-time pair` | `V2 local anchor is null - fetch latest window` | `V1 around is single-direction with local anchor` | `V2 around is dual-direction` | `newer anchor - V2 encodes NEWER, V1 null` | `supportsNewerDirection capability` | `capabilities mapping per version`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationCursorPolicyTest.kt","loc":80,"lang":"中文","zh":4,"en":0,"kdoc":2,"tests":7,"cls":"PaginationCursorPolicyTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/WorkspaceUseCasesTest.kt
LOC 93 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | WorkspaceUseCasesTest
LEX: turn,directory,sse,diff,patch,context,workspace
FREQ: file×33 repository×24 server×20 case×16 path×16 content×12 status×12 expected×12 domain×11 model×8 equals×5 project×5 mode×4 delegates×4
TESTS-EN: `ListDirectoryUseCase delegates to FileRepository listDirectory with same args` | `GetFileContentUseCase delegates to FileRepository getFileContent with same args` | `GetVcsStatusUseCase delegates to VcsRepository getStatus with same args` | `GetFileDiffUseCase delegates to VcsRepository getDiff with default mode and context`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/WorkspaceUseCasesTest.kt","loc":93,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"WorkspaceUseCasesTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationFSMTest.kt
LOC 456 | lang 混合 (zh 31/en 3, kdoc 3) | @Test 22 | btFuns 22 | PaginationFSMTest
LEX: session,message,event,part,sse,cursor,paginat,page,archive
FREQ: load×59 newer×54 older×47 network×37 state×37 equals×35 created×35 server×30 auto×30 succeeded×23 oldest×22 transition×22 source×21 paused×19
C-ZH: L11 [kdoc] [PaginationFSM] 纯函数转移矩阵测试（#56 TD-1）。 | L12 [kdoc] 覆盖：游标推进（ARCHIVE/NETWORK/空页）、hasOlder 边界、防风暴退避/暂停/恢复。 | L13 [kdoc] 参照 SessionStateFSM 测试风格：给定 (state, event) → 断言新 state。 | L41 [line]  会话重载只重置游标/hasOlder，不触碰防风暴状态（由后续加载结果决定） | L81 [line]  防御：空页不推进游标（与重构前 ?: archiveCursorCreated 语义一致） | L111 [line]  V1 路径：无服务器游标 → Network(id, created)（use case 用 CursorCodec 编码） | L132 [line]  2026-08-18 回归（V2 长会话历史不可达）：null-cursor 首翻返回的重叠页—— | L133 [line]  即使页大小 < limit（服务器窗口截断），只要携带 cursor.next 就还有更早。 | L142 [line]  不足一页但有游标 | L153 [line]  2026-08-18 回归：V2 首翻 null-cursor 路径的满页重叠场景—— | L154 [line]  满页（全是已加载重复）+ cursor.next → FSM 必须进入 Network(serverCursor) | L155 [line]  态且 hasOlder=true，后续翻页才能透传服务器游标。 | L175 [line]  2026-08-18 勘误：本测试原参数 nextCursor 非空却断言读尽——自相矛盾 | L176 [line] （服务器返回游标 = 一定还有更早，见 LoadNewerSucceeded 对称语义）。 | L177 [line]  修正为真正的读尽场景：不足一页且无游标。 | L213 [line]  空页：不推进游标（读尽后 UI 停止触发，无死循环），hasOlder=false | L243 [line]  ============ LoadFailed: 退避 / 暂停 ============ | L265 [line]  第 4 次失败已超 MAX=3 → 暂停 | L282 [line]  超过 MAX_SHIFT 后退避不再增长（2^4=16 倍 = 8s） | L289 [line]  ============ AroundLoaded（loadAround 双向定位加载） ============ | L313 [line]  V1 降级：newer 不可用 | L346 [line]  olderCursor=null → 回落 HotStart | (+9 more)
C-EN*: L28  ============ SessionReloaded ============ | L46  ============ LoadSucceeded: ARCHIVE ============
   (+1 trivial en comments)
ZHSTR: L62 "ARCHIVE 来源 always hasOlder=true（归档桶未读尽）" | L106 "满页 → 服务器可能还有更早" | L148 "服务器游标非空 → 一定还有更早" | L193 "不足一页且无游标 → 已读尽" | L372 "满页 + 有游标 → 还有更新"
TESTS-EN: `sessionReloaded resets cursor to hot start and sets hasOlder` | `archive success advances archive cursor and keeps hasOlder true` | `archive empty page keeps cursor` | `network success with server cursor stores it for next page` | `network success without server cursor falls back to id plus created` | `network overlap page with server cursor keeps hasOlder true even if partial` | `network overlap full page with server cursor advances to network state` | `network partial page sets hasOlder false` | `network empty page keeps cursor and hasOlder false` | `success resets backoff and unpauses` | `first failure sets 500ms backoff without pause` | `failures backoff grows exponentially` | `pauses after max consecutive failures` | `backoff caps at 8s shift limit` | (+8 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/PaginationFSMTest.kt","loc":456,"lang":"混合","zh":31,"en":3,"kdoc":3,"tests":22,"cls":"PaginationFSMTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCaseTest.kt
LOC 56 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | SendMessageUseCaseTest
LEX: session,message,part,turn,agent,directory,sse
FREQ: prompt×10 repository×9 chat×8 model×6 send×6 case×5 server×5 domain×4 async×3 build×3 exception×3 caught×3
TESTS-EN: `sendPrompt delegates to chatRepository` | `sendPrompt propagates exception`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SendMessageUseCaseTest.kt","loc":56,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"SendMessageUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/UpdateSettingsUseCaseTest.kt
LOC 49 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | UpdateSettingsUseCaseTest
LEX: message,part,turn,sse
FREQ: settings×26 repository×10 case×6 update×5 success×5 domain×3 invoke×3 failure×3
TESTS-EN: `invoke returns success when repository succeeds` | `invoke returns failure when repository fails` | `invoke with partial settings change succeeds`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/UpdateSettingsUseCaseTest.kt","loc":49,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"UpdateSettingsUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SubmitAnnotationsUseCaseTest.kt
LOC 50 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | SubmitAnnotationsUseCaseTest
LEX: part,turn,sse,annotation
FREQ: prompt×9 repository×8 chat×7 case×6 domain×5 project×5 make×4 async×4 model×3 anns×3 expected×3 failure×3
ZHSTR: L28 "请修改"
TESTS-EN: `invoke builds prompt and calls promptAsync` | `invoke propagates failure` | `empty annotations throws`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/usecase/SubmitAnnotationsUseCaseTest.kt","loc":50,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"SubmitAnnotationsUseCaseTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/domain/util/CursorCodecTest.kt
LOC 93 | lang 中文 (zh 9/en 0, kdoc 4) | @Test 8 | btFuns 0 | CursorCodecTest
LEX: turn,sse,cursor
FREQ: codec×20 decoded×17 decode×15 direction×12 equals×11 encode×9 json×8 base×6 newer×5 older×5 desc×4 previous×4 target×4 swfy×3
C-ZH: L30 [line]  curl 实测返回的游标 | L40 [line]  ============ V2 双向游标（loadAround / loadNewer） ============ | L43 [kdoc] V2 游标结构正确性：base64url(JSON{id, order:"desc", direction})。 | L44 [kdoc] 解码后字段与服务器契约一致（curl 实测：direction="next"=更旧，"previous"=更新）。 | L54 [line]  OLDER 方向对应服务器 direction="next" | L64 [line]  NEWER 方向对应服务器 direction="previous" | L69 [kdoc]  直接验证 base64 解码后的 JSON 包含 order="desc"（服务器契约字段）。 | L75 [line]  必须包含服务器要求的三个字段 | L87 [kdoc]  V1 游标（{id,time}）不是合法 V2 游标 → decodeV2 返回 null（无 direction 字段）。
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/domain/util/CursorCodecTest.kt","loc":93,"lang":"中文","zh":9,"en":0,"kdoc":4,"tests":8,"cls":"CursorCodecTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/ErrorStreakTrackerTest.kt
LOC 47 | lang 中文 (zh 2/en 0, kdoc 1) | @Test 5 | btFuns 0 | ErrorStreakTrackerTest
LEX: session,message,turn,sse
FREQ: error×20 streak×11 tracker×6 reset×4
C-ZH: L7 [kdoc]  #155 R3/Q10：错误 streak 状态机（通知侧与提示音侧共用语义）。 | L28 [line]  onTurnCompleted / onUserMessage 同一入口
ENSTR*: L36 "other session unaffected"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/ErrorStreakTrackerTest.kt","loc":47,"lang":"中文","zh":2,"en":0,"kdoc":1,"tests":5,"cls":"ErrorStreakTrackerTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackDedupIsolationTest.kt
LOC 62 | lang 中文 (zh 5/en 0, kdoc 2) | @Test 1 | btFuns 0 | FeedbackDedupIsolationTest
LEX: session,message,event,part,turn,sse,patch,feedback,notification,dedup
FREQ: manager×7 assistant×6 flow×5 kotlinx×4 coroutines×4 mutable×4 state×4 check×4 repository×3 domain×3 model×3 time×3 scope×3 compute×3
C-ZH: L21 [kdoc] #155 Q11：提示音纯查询路径不得污染通知去重 map—— | L22 [kdoc] 场景：会话内已响过提示音（compute），用户离场后同 turn 的通知（check）不得被吞。 | L55 [line]  提示音路径：纯查询（会话内响过一声） | L57 [line]  用户离场 → 通知路径：同一 turn 不得被提示音的去重吞掉 | L59 [line]  通知自身的去重仍正常（第二次 check 返回 null）
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackDedupIsolationTest.kt","loc":62,"lang":"中文","zh":5,"en":0,"kdoc":2,"tests":1,"cls":"FeedbackDedupIsolationTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackPolicyTest.kt
LOC 112 | lang 混合 (zh 9/en 1, kdoc 3) | @Test 11 | btFuns 0 | FeedbackPolicyTest,NotificationManagerImportance
LEX: sse,snapshot,feedback,notification
FREQ: plan×39 sound×24 policy×24 channel×18 pattern×17 vibration×16 ringer×14 importance×12 vibrate×12 normal×9 equals×7 bypass×5 silent×5 const×4
C-ZH: L9 [kdoc] #155 策略镜像管线纯函数测试（spec §6 静音矩阵）。 | L10 [kdoc] Uri.parse 在 JVM 单测可用（android.net.Uri 是 android.jar stub 会抛）—— | L11 [kdoc] 改用字符串比较绕开：SoundPlan.soundUri 直接断言引用或 null。 | L15 [line]  JVM stub：以 null 代表系统默认音（管线透传语义同引用） | L32 [line]  ---- 渠道层 ---- | L43 [line]  渠道无自定义铃声 → 透传系统默认（这里 defaultSound=null → 无声；引用透传语义） | L67 [line]  ---- RingerMode 层 ---- | L83 [line]  ---- DND 层 ---- | L104 [line]  ---- 边界 ----
   (+1 trivial en comments)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/FeedbackPolicyTest.kt","loc":112,"lang":"混合","zh":9,"en":1,"kdoc":3,"tests":11,"cls":"FeedbackPolicyTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/FindUserMessagesTest.kt
LOC 145 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 7 | btFuns 7 | FindUserMessagesTest
LEX: session,message,event,part,turn,sse,patch,notification
FREQ: user×21 manager×11 equals×10 find×8 latest×7 synthetic×6 time×5 flow×5 settings×4 store×4 domain×4 model×4 kotlinx×4 coroutines×4
   (+1 trivial en comments)
ENSTR*: L99 "Real message" | L138 "Message $it" | L142 "Message 8" | L143 "Message 10"
TESTS-EN: `returns empty list when no messages` | `returns empty list when no user messages` | `extracts user message text` | `filters synthetic messages` | `skips user messages with no text parts` | `truncates long text to 100 chars` | `returns at most limit messages, most recent last`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/FindUserMessagesTest.kt","loc":145,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":7,"cls":"FindUserMessagesTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt
LOC 40 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | QuestionNotifyDiffTest
LEX: session,event,question,sse,diff
FREQ: current×6 equals×5 previous×5
TESTS-EN: `new questions are detected per session` | `known questions not re-notified` | `empty previous notifies all`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt","loc":40,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"QuestionNotifyDiffTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionServiceConnectGuardTest.kt
LOC 48 | lang 中文 (zh 6/en 0, kdoc 4) | @Test 1 | btFuns 1 | OpenCodeConnectionServiceConnectGuardTest
LEX: event,turn,config,sse,context
FREQ: service×15 scope×11 connection×10 manager×5 cancel×4 connect×4 code×4 target×4 kotlinx×3 coroutines×3 coroutine×3
C-ZH: L13 [kdoc] 泄漏修复（路径 1b）：onStartCommand 的 serviceScope.launch 挂起（DB 读取） | L14 [kdoc] 恢复后若 onDestroy 已执行（serviceScope.cancel + stopAllConnections）， | L15 [kdoc] 迟到的 connect() 必须是空操作——否则用已销毁 Service 的 ::processEvent | L16 [kdoc] 重填单例 connections map，连接永久滞留。 | L26 [line]  模拟 onDestroy 已执行：serviceScope.cancel() | L33 [line]  守卫生效：不重填单例 map、不启动任何连接
TESTS-EN: `connect is no-op when serviceScope already cancelled`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionServiceConnectGuardTest.kt","loc":48,"lang":"中文","zh":6,"en":0,"kdoc":4,"tests":1,"cls":"OpenCodeConnectionServiceConnectGuardTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinatorTest.kt
LOC 180 | lang 中文 (zh 14/en 0, kdoc 10) | @Test 10 | btFuns 10 | ConnectionLifecycleCoordinatorTest
LEX: event,question,config,sse,patch,terminal,workspace,notification
FREQ: coordinator×44 server×18 connect×18 disconnect×16 manager×15 registry×13 active×12 connection×9 kotlinx×8 coroutines×8 find×7 duplicate×7 backend×7 polling×7
C-ZH: L29 [kdoc] 连接生命周期协调器单测（#170 阶段 3——Q4-B 用例集）。 | L31 [kdoc] 用例对应历史竞态/泄漏根因： | L32 [kdoc] - C1 connect 幂等（同 serverId 重复 → SSE 只启动一次——重复事件=流式翻倍根因） | L33 [kdoc] - C2 同后端去重（url+username 归一化——backlog #34） | L34 [kdoc] - C3 disconnect 四路清理序列（轮询/SSE/终端/通知——漏一路即泄漏） | L35 [kdoc] - C4 disconnectAll ≡ 逐个 disconnect（teardown 合一等价性） | L36 [kdoc] - C5 未知 id 断开 no-op | L37 [kdoc] - C6 回调时序（active=true/false；最后断开 registry 空） | L38 [kdoc] - C7 findDuplicateBackend（UI 预检） | L39 [kdoc] - C8 轮询启停（工厂启动/断开取消） | L83 [line]  尾斜杠 + host 大小写差异 = 同一后端（#34 归一化） | L113 [line]  回调：每个服务器 active=true 一次 + active=false 一次 | L132 [line]  最后断开后 registry 空——宿主据此 stopSelf（FGS 决策数据源） | L151 [line]  重连（registry 已清）可再次启动
ZHSTR: L86 "重复后端不得入 registry" | L98 "轮询 job 应被取消" | L148 "connect 应启动轮询" | L150 "disconnect 应取消轮询"
TESTS-ZH: `C1_connect同id幂等只启动一次SSE` | `C2_connect同后端不同id去重` | `C3_disconnect四路清理完整调用` | `C4_disconnectAll等价逐个disconnect` | `C5_disconnect未知id为noOp` | `C6_回调时序与最后断开判定` | `C7_findDuplicateBackend命中与未命中` | `C8_轮询随生命周期启停` | `C9_activeServerIds流即时反映成员变化` | `C10_isManaged反映registry成员资格`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinatorTest.kt","loc":180,"lang":"中文","zh":14,"en":0,"kdoc":10,"tests":10,"cls":"ConnectionLifecycleCoordinatorTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/CancelSessionNotificationsTest.kt
LOC 66 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 2 | CancelSessionNotificationsTest
LEX: session,message,event,part,turn,patch,notification
FREQ: manager×16 cancel×9 hash×7 flow×5 base×5 settings×4 store×4 kotlinx×4 coroutines×4 mutable×4 state×4 server×4 summary×4 stable×3
TESTS-EN: `cancels all 4 type offsets for the session` | `does not cancel group summary`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/CancelSessionNotificationsTest.kt","loc":66,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"CancelSessionNotificationsTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/SessionFocusHolderTest.kt
LOC 73 | lang 中文 (zh 2/en 0, kdoc 0) | @Test 8 | btFuns 8 | SessionFocusHolderTest
LEX: session,event,turn,sse,diff
FREQ: holder×27 focus×20 active×14 server×14 suppress×13 foreground×7 equals×3
C-ZH: L20 [line]  2026-08-16 语义（通知 P1）：后台不抑制——按 Home 回桌面后用户看不到界面， | L21 [line]  权限/问题/错误通知必须发出（原 shouldSuppressEvent 旧行为会静默吞通知）
TESTS-EN: `shouldSuppress returns false when app is in background` | `shouldSuppress returns false when no active focus` | `shouldSuppress returns true when foreground and same session` | `shouldSuppress returns false when foreground but different session` | `shouldSuppress returns false when different server same session` | `setActiveFocus null clears focus` | `setActiveFocus with null serverId does not set focus` | `shouldSuppress returns false after focus cleared`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/SessionFocusHolderTest.kt","loc":73,"lang":"中文","zh":2,"en":0,"kdoc":0,"tests":8,"cls":"SessionFocusHolderTest"}







═══ app/src/test/kotlin/dev/leonardo/ocbeacon/service/SseConnectionManagerTest.kt
LOC 219 | lang 中文 (zh 34/en 0, kdoc 15) | @Test 4 | btFuns 4 | SseConnectionManagerTest
LEX: session,message,event,turn,config,stream,sse,snapshot,patch,context
FREQ: server×17 manager×15 reconnect×15 client×14 concurrent×13 connections×13 file×12 relaxed×12 repository×11 settings×11 preload×11 flow×9 java×9 kotlinx×8
C-ZH: L54 [line]  ============ 泄漏修复（路径 1a）：reconnect 孤儿 job 守卫 ============ | L57 [kdoc] 场景（泄漏路径 1a）：reconnectServer 在 cancelAndJoin 挂起期间， | L58 [kdoc] Service onDestroy → stopAllConnections() 清空 connections。恢复后 | L59 [kdoc] startSseConnection 启动的新 job 因 computeIfPresent 未命中成为孤儿—— | L60 [kdoc] 守卫必须将其取消，否则其闭包持有已销毁 Service 的 onEvent | L61 [kdoc] （::processEvent）回调，SSE 流永不退出（僵尸协程）。 | L63 [kdoc] 观测点（#150 方向② 后语义更新，2026-08-21）：SSE 先行架构下主循环不再被 | L64 [kdoc] preload 阻塞，流会立即开始收集——旧断言"两个计数恒为 0"依赖串行窗口已不成立。 | L65 [kdoc] 等价安全性质改为：**stopAllConnections 移除后**，所有 job 必须自愈终止 | L66 [kdoc] （takeWhile 在条目缺失处完成流）——观察窗口内收集计数与事件投递计数 | L67 [kdoc] 均不再增长（无僵尸持续消费、无死回调投递），connections 保持空。 | L75 [line]  job1 进入 preLoad 后在 NonCancellable 窗口内抵抗取消， | L76 [line]  制造出 reconnectServer.cancelAndJoin 的挂起窗口 | L84 [line]  无限事件流：真实 SSE 流永不自行完成——未被取消的孤儿会持续收集 | L87 [line]  真正开始收集处做取消检查：被取消的协程在此终止、不递增计数； | L88 [line]  未被取消的孤儿则开始消费事件（守卫失效时测试变红） | L113 [line]  1. 等 job1 进入 preLoad 的 NonCancellable 窗口 | L116 [line]  2. 后台触发 reconnectAll（cancelAndJoin 将挂起至窗口结束） | L123 [line]  3. 在挂起窗口内模拟 onDestroy：清空单例 connections | L127 [line]  4. 等 reconnectAll 结束（cancelAndJoin 窗口 400ms + 余量） | L132 [line]  5. 等一切尘埃落定（reconnect 的 job2 可能短暂收集后自愈退出），拍快照。 | L136 [line]  再观察一个窗口：计数必须稳定——若守卫/自愈失效，僵尸 job 会持续 | (+12 more)
ENSTR*: L114 "job1 should reach preLoadSessions" | L140 "no zombie may keep consuming the stream after removal (self-terminate)" | L144 "no events may be delivered via dead onEvent callback after removal" | L205 "connected should flip while preload still in flight (SSE-first)"
TESTS-EN: `connections map is ConcurrentHashMap` | `timeoutTrackers map is ConcurrentHashMap` | `reconnect cancels orphaned SSE job when server removed during cancelAndJoin` | `connected flips on first SSE event while preload still in flight`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/service/SseConnectionManagerTest.kt","loc":219,"lang":"中文","zh":34,"en":0,"kdoc":15,"tests":4,"cls":"SseConnectionManagerTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/navigation/routes/WorkspaceNavTest.kt
LOC 90 | lang 中文 (zh 4/en 0, kdoc 1) | @Test 4 | btFuns 4 | WorkspaceNavTest
LEX: session,part,turn,directory,sse,workspace
FREQ: route×21 pattern×19 server×14 equals×5 params×5 bundle×4 create×4 contain×4 navigation×3 stack×3 query×3
C-ZH: L13 [kdoc]  根据路由字符串构建 mock 的 NavBackStackEntry，以便 fromEntry 能解码参数。 | L15 [line]  使用 java.net.URI（JVM 可用）而非 android.net.Uri（单元测试中被 stub） | L42 [line]  特殊字符必须被编码 | L84 [line]  密码/用户名/服务器 URL 不得出现在路由模式中
ENSTR*: L44 "sessionId should be URL-encoded, got: $route" | L47 "directory should be URL-encoded, got: $route"
TESTS-EN: `createRoute URL-encodes sessionId and directory` | `routePattern matches expected format` | `fromEntry round-trips createRoute values` | `routePattern contains no credential params`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/navigation/routes/WorkspaceNavTest.kt","loc":90,"lang":"中文","zh":4,"en":0,"kdoc":1,"tests":4,"cls":"WorkspaceNavTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScrollControllerTest.kt
LOC 162 | lang 中文 (zh 26/en 0, kdoc 15) | @Test 5 | btFuns 5 | ChatScrollControllerTest,FakeGate
LEX: sse,retry,snapshot,patch,notification
FREQ: gate×42 scroll×27 logs×17 initial×16 mutable×14 equals×12 progress×12 state×10 offset×10 execute×10 executor×9 flag×8 calls×8 delay×7
C-ZH: L17 [kdoc] 2026-08-16 根治：死代码根因 —— 发送后滚底 ForceScrollExecutor 单测。 | L19 [kdoc] 覆盖三条主路径： | L20 [kdoc] ① tick 后 totalItemsCount 增长 → 执行 requestScrollToItem(0) | L21 [kdoc] ② fling 惯性中（isScrollInProgress true→false）→ 等待停止后仍滚 | L22 [kdoc] ③ 5s 超时（count 永不增长）→ 超时后仍滚 + 超时日志 | L23 [kdoc] 以及滚后校验路径：短暂未到位收敛（不重滚）与持续未到位（重滚一次）。 | L25 [kdoc] 说明：LazyListState 依赖快照/布局体系难以干净 mock，故逻辑已抽为 | L26 [kdoc] [ForceScrollExecutor] + [ScrollListGate]（抽函数优于 hack mock）， | L27 [kdoc] 本测试用 State 驱动的 Fake 门面验证 —— snapshotFlow 可真实订阅其变化。 | L32 [kdoc]  State 驱动的滚动门面 Fake：snapshotFlow 能真实订阅其属性变化。 | L43 [kdoc]  每次 requestScrollToItem 调用时 isScrollInProgress 的快照（断言时序）。 | L46 [kdoc]  滚动后落点模拟：默认立即到位（index=0/offset=0，即 reverseLayout 底部）。 | L66 [line]  JVM 单测无 MonotonicFrameClock，注入空帧等待 | L70 [kdoc] 显式通知全局快照应用。JVM 单测无 AndroidUiDispatcher/GlobalSnapshotManager， | L71 [kdoc] MutableState 全局写入不会自动触发 apply observer（snapshotFlow 依赖其重发）； | L72 [kdoc] 生产环境由帧调度保证，无需此调用。 | L76 [line]  ============ ① 消息增长 → 滚底 ============ | L82 [line]  模拟 POST 往返 + SSE 回显后消息入列 | L91 [line]  ============ ② fling 中到达 → 等待停止后仍滚 ============ | L96 [line]  用户 fling 惯性进行中 | L111 [line]  ============ ③ 5s 超时兜底 → 仍滚 + 日志 ============ | L117 [line]  不安排 count 增长：模拟发送失败/无 SSE 回显 | (+4 more)
ZHSTR: L86 "消息增长后应恰好滚动一次" | L87 "增长路径不应记超时日志" | L88 "应锚定到 index 0（底部）" | L105 "滚动必须发生在 fling 结束之后（不得在 isScrollInProgress=true 时抢滚）" | L121 "超时兜底后仍应滚动" | L122 "超时路径应记一条日志" | L141 "补偿收敛期内不应重滚（避免视口抖动）" | L159 "校验超时后应重滚一次（共两次）" | L160 "增长已发生，不应记超时日志"
TESTS-EN: `execute scrolls to bottom when totalItemsCount grows` | `execute waits for fling to finish before scrolling` | `execute scrolls anyway with log after growth timeout` | `execute skips retry when position converges after compensation` | `execute retries scroll once when never reaches bottom`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatScrollControllerTest.kt","loc":162,"lang":"中文","zh":26,"en":0,"kdoc":15,"tests":5,"cls":"ChatScrollControllerTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelDeleteTest.kt
LOC 321 | lang 中文 (zh 9/en 0, kdoc 0) | @Test 7 | btFuns 7 | ChatViewModelDeleteTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,stream,sse,paginat,snapshot,patch,terminal,task,pending,feedback,notification,badge
FREQ: case×55 repository×54 relaxed×35 manage×33 model×24 chat×20 flow×20 settings×17 state×17 domain×16 repo×16 service×15 handler×15 server×14
C-ZH: L105 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L108 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L164 [line]  将 messagePaging.observeMessages 接线为返回空消息列表 | L271 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L274 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L275 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | L291 [line]  --- Task 10：onSessionUpdated 测试 --- | L309 [line]  对不匹配的会话不应抛异常 | L318 [line]  不应抛异常
ENSTR*: L286 "Test Session"
TESTS-EN: `deleteMessage calls api and returns true on success` | `deleteMessage returns false on failure` | `deleteMessagePart calls api and returns true on success` | `deleteMessagePart returns false on failure` | `onSessionUpdated refreshes messages for matching session` | `onSessionUpdated ignores non-matching session` | `onSessionUpdated handles exception gracefully`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelDeleteTest.kt","loc":321,"lang":"中文","zh":9,"en":0,"kdoc":0,"tests":7,"cls":"ChatViewModelDeleteTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelContextTokensTest.kt
LOC 400 | lang 中文 (zh 36/en 0, kdoc 11) | @Test 4 | btFuns 4 | ChatViewModelContextTokensTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,compaction,stream,sse,paginat,idle,snapshot,patch,terminal,context,pending,queue,feedback,notification,merge,upsert,badge
FREQ: repository×53 case×43 flow×35 relaxed×34 stats×28 tracker×26 chat×24 manage×24 cache×23 state×22 model×21 repo×21 settings×17 service×16
C-ZH: L48 [kdoc] 2026-08-17 上下文占用口径修正（ACP：input+cache.read）的回归测试。 | L50 [kdoc] lastContextTokens 唯一写入源 = 消息级快照（最后一条 output>0 的 assistant | L51 [kdoc] 消息），口径 = input + cache.read。三个 session 级累计兜底（冷启动 | L52 [kdoc] bootstrap / V2 usage.updated / 压缩后 maxOf）已全部删除——它们把 SQL | L53 [kdoc] 累计 tokens（每轮累加、压缩不下降）当「当前上下文占用」，导致指示器 | L54 [kdoc] 显示超 100%（如 104%）。 | L59 [line]  === Mock 与基础设施（复用 ChatViewModelQueuedTest 模式） === | L91 [line]  VM 侧 eventDispatcher mock 的可控 flow（2026-08-17 后 sessionUsage 不再被消费） | L116 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L119 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L134 [line]  Draft 桩 | L137 [line]  Settings 桩 | L157 [line]  UseCase 桩 —— 默认值 | L166 [line]  将 messagePaging.observeMessages 接线为委托到 eventDispatcher.messages | L171 [line]  VM 侧 mock eventDispatcher 的可控 flow | L182 [line]  === 辅助方法 === | L195 [kdoc]  带 tokens + text part 的 Assistant 消息（能经受 V1→V2 桥接转换）。 | L277 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L280 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L281 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | L295 [line]  === 用例 === | L298 [kdoc] #186 根因修复：VM init 的 serverRepository.getServer 跑在真实 Dispatchers.IO， | (+14 more)
ENSTR*: L189 "Test Session"
TESTS-EN: `message snapshot context tokens equals input plus cache read` | `usage updated event does not write lastContextTokens` | `cold start session tokens do not seed lastContextTokens` | `compaction does not raise lastContextTokens and snapshot falls back`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelContextTokensTest.kt","loc":400,"lang":"中文","zh":36,"en":0,"kdoc":11,"tests":4,"cls":"ChatViewModelContextTokensTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelQueuedTest.kt
LOC 690 | lang 混合 (zh 56/en 10, kdoc 18) | @Test 20 | btFuns 0 | ChatViewModelQueuedTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,stream,sse,paginat,busy,idle,snapshot,patch,terminal,task,pending,queue,feedback,notification,merge,upsert,badge
FREQ: state×79 create×61 repository×52 case×47 created×39 relaxed×34 assistant×32 model×28 user×28 manage×27 chat×26 flow×26 parent×26 json×24
C-ZH: L57 [kdoc] 针对 4 个功能的综合测试： | L58 [kdoc] A. QUEUED 徽章 —— queuedMessageIds 计算 | L59 [kdoc] B. 子会话标识 —— sessionParentId | L60 [kdoc] C. 从 tool metadata 中提取 subSessionId 的逻辑 | L61 [kdoc] D. Part.Agent 的 source 提取逻辑 | L62 [kdoc] E. 结合多个功能的集成场景 | L67 [line]  === Mock 与基础设施 === | L74 [line]  UseCase mock 定义 | L96 [line]  P5-1：queuedMessageIds 现在由 FSM 状态派生（Idle 强制清空）。 | L97 [line]  验证 queued 逻辑的测试需要会话处于 Busy。 | L126 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L129 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L144 [line]  Draft 桩 | L147 [line]  Settings 桩 | L167 [line]  UseCase 桩 —— 默认值 | L176 [line]  将 messagePaging.observeMessages 接线为委托到 eventDispatcher.messages | L188 [line]  === 辅助方法 === | L211 [kdoc]  带 text part 的 User 消息 —— 能经受 V1→V2 桥接转换。 | L233 [kdoc]  带 text part 的 Assistant 消息 —— 能经受 V1→V2 桥接转换。 | L319 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L322 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L323 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | (+34 more)
   (+10 trivial en comments)
ENSTR*: L195 "Test Session" | L214 "test message" | L648 "Task completed"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelQueuedTest.kt","loc":690,"lang":"混合","zh":56,"en":10,"kdoc":18,"tests":20,"cls":"ChatViewModelQueuedTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelPermissionTest.kt
LOC 577 | lang 混合 (zh 25/en 9, kdoc 2) | @Test 18 | btFuns 18 | ChatViewModelPermissionTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,stream,sse,paginat,snapshot,patch,terminal,pending,feedback,notification,badge
FREQ: case×78 repository×55 manage×54 model×39 create×37 relaxed×35 reply×30 perms×26 chat×25 equals×25 view×24 state×20 flow×20 server×19
C-ZH: L59 [kdoc] ChatViewModel 权限相关逻辑的纯 JVM 单元测试。 | L61 [kdoc] 使用 [UnconfinedTestDispatcher] 使 viewModelScope 协程立即执行。 | L117 [line]  #122 接线新增：自动批准（relaxed mock——既有用例不受影响） | L120 [line]  堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L134 [line]  每个测试创建全新的 mock，避免 stub 顺序问题 | L138 [line]  创建 UseCase mock（全部 relaxed，因此不重要的方法无需 stub） | L171 [line]  init 块 stub —— 测试可覆盖的默认值 | L178 [line]  注意：此处不设置 listPendingPermissions —— 每个测试设置自己的 stub | L180 [line]  将 messagePaging.observeMessages 接线为委托给 eventDispatcher.messages | L204 [line]  ChatRepository mock：将状态操作委托给真实 EventDispatcher 以便验证 | L267 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L270 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L271 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | L306 [line]  健全性检查：验证 init 块协程已执行 | L332 [line]  测试：loadPendingPermissions | L349 [line]  直接检查 EventDispatcher（真相来源） | L423 [line]  不应抛异常 | L443 [line]  测试：replyToPermission | L495 [line]  2026-08-17 根治（权限卡重弹）：失败不再无条件清卡——复核服务器 | L496 [line]  仍 pending → 保留卡片（旧「失败也移除」正是重弹根因的一半）。 | L543 [line]  2026-08-17 根治：异常后复核服务器——仍 pending 保留卡片（用户重试）。 | L558 [line]  测试：多会话 | (+3 more)
   (+9 trivial en comments)
ENSTR*: L282 "Test Session" | L351 "EventDispatcher should have 1 permission for session, got: ${reducerPerms}" | L454 "Precondition: 1 permission loaded"
TESTS-EN: `init block executes — getSession API is called` | `init block executes — permissions API is called` | `EventDispatcher setPermissions works directly` | `loadPendingPermissions maps and stores permission` | `loadPendingPermissions filters by session ID` | `loadPendingPermissions empty result — no permissions stored` | `loadPendingPermissions maps metadata` | `loadPendingPermissions maps always field` | `loadPendingPermissions API exception does not crash` | `loadPendingPermissions maps tool ref` | `replyToPermission calls API and removes permission` | `replyToPermission with reply=always` | `replyToPermission with reply=reject` | `replyToPermission API false keeps card when server still pending` | (+4 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelPermissionTest.kt","loc":577,"lang":"混合","zh":25,"en":9,"kdoc":2,"tests":18,"cls":"ChatViewModelPermissionTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/CustomAnswerToggleFlowTest.kt
LOC 138 | lang 中文 (zh 19/en 0, kdoc 6) | @Test 14 | btFuns 11 | CustomAnswerToggleFlowTest
LEX: question,sse
FREQ: mango×43 parked×30 apple×26 option×25 equals×24 toggle×23 single×23 answer×19 selected×19 labels×17 banana×4 content×4 checked×4 multi×4
C-ZH: L9 [kdoc] 提问卡答案 toggle 流测试——直接调用生产纯函数 [toggleQuestionAnswer] | L10 [kdoc] （QuestionCard.onOptionClick 单一真相源；原版本文件是手工复刻镜像， | L11 [kdoc] 2026-08-18 重写为真源调用，杜绝逻辑漂移）。 | L13 [kdoc] 三态模型（2026-08-19 用户反馈修复）：自定义答案 = 勾选 / parked 保留 / | L14 [kdoc] 不存在。核心场景（用户原话）：单选保存自定义后再选别的选项， | L15 [kdoc] 自定义应"保留内容，但取消勾选"——内容入 parked 槽，不进提交载荷。 | L21 [line]  ---- 用户反馈主场景：单选，自定义已勾选，点其他选项 ---- | L25 [line]  Mango 已勾选 → 点 Apple：载荷只有 Apple；Mango 保留内容但未勾选 | L33 [line]  parked Mango + Apple 已选 → 点击 parked 行：Mango 勾选、Apple 让位 | L41 [line]  parked Mango + Apple 已选 → 切到 Banana：parked 原样保留 | L56 [line]  Apple 已选 → 勾选自定义 Mango：真·单选互斥，载荷恒 ≤1 | L57 [line]  （选项行仍可见可再选，非内容丢失） | L65 [line]  行点击已勾选自定义 = 取消勾选（内容保留） | L73 [line]  parked 存在 + 无选中 → 点选项：parked 不受影响 | L79 [line]  ---- 多选：选项与自定义独立 toggle；取消勾选同样入 parked ---- | L111 [line]  ---- 编辑替换 = park 旧值 + 勾选新值 ---- | L115 [line]  单选：Mango 已勾选 → 编辑为 Mango pie | L124 [line]  ---- E2E-F 时代回归：载荷正确性 ---- | L128 [line]  单选载荷恒 ≤1：勾选自定义后载荷就是单条
TESTS-EN: `single - option pick unchecks custom but parks content` | `single - parked custom recheck replaces option selection` | `single - option switch keeps parked content` | `single - re-tap selected option clears selection keeps parked` | `single - checking custom deselects option (mutual exclusion)` | `single - re-tap checked custom parks content` | `parked survives option taps when custom never checked` | `multi - custom coexists with options when checked` | `multi - re-tap checked custom parks content options untouched` | `multi - option toggle never touches custom or parked` | `multi - parked recheck adds custom back alongside options`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/CustomAnswerToggleFlowTest.kt","loc":138,"lang":"中文","zh":19,"en":0,"kdoc":6,"tests":14,"cls":"CustomAnswerToggleFlowTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/DraftInputDelegateTest.kt
LOC 159 | lang 中文 (zh 11/en 0, kdoc 0) | @Test 8 | btFuns 4 | DraftInputDelegateTest
LEX: session,turn,agent,directory,draft,provider,sse,idle,patch
FREQ: repository×20 persisted×17 delegate×11 coroutines×10 equals×10 advance×9 update×9 restore×9 kotlinx×8 user×7 clear×6 manage×5 case×5 scope×5
C-ZH: L48 [line]  #54：直写语义——输入后立即持久化（无防抖窗口，force-stop 不丢草稿） | L57 [line]  连续快速输入 → 每次输入都持久化（Mutex 串行保序，最后一次文本正确） | L82 [line]  clearDraft 清空状态；不会把清空前的文本存回去（直写已保存的是清空前的快照， | L83 [line]  clearDraft 本身只清内存 + 调 repository.clearDraft） | L97 [line]  ============ backlog #38：异步草稿恢复竞态测试 ============ | L121 [line]  竞态场景：异步恢复到达前用户已开始打字。 | L122 [line]  期望：保留用户输入，不覆盖。 | L127 [line]  用户先输入（模拟恢复未完成前的用户操作） | L129 [line]  让防抖 saveDraft 不干扰（它不会触发因为 getDraft mock 已设置） | L133 [line]  返回 Draft（用于 agent/variant 恢复），但文本不被覆盖 | L135 [line]  用户输入保留
TESTS-EN: `restorePersistedDraft applies draft when no user input` | `restorePersistedDraft skips text when user already typed - race condition guard` | `restorePersistedDraft skips attachments when user already added` | `restorePersistedDraft returns null when no draft`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/DraftInputDelegateTest.kt","loc":159,"lang":"中文","zh":11,"en":0,"kdoc":0,"tests":8,"cls":"DraftInputDelegateTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelSendTest.kt
LOC 314 | lang 中文 (zh 27/en 0, kdoc 3) | @Test 5 | btFuns 5 | ChatViewModelSendTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,sse,paginat,idle,snapshot,patch,terminal,task,pending,feedback,notification,badge
FREQ: model×52 repository×45 case×45 state×42 view×40 send×36 flow×33 relaxed×25 manage×22 coroutines×20 chat×19 kotlinx×18 interaction×17 restored×14
C-ZH: L113 [line]  将 messagePaging.observeMessages 接线为返回空消息列表 | L120 [line]  interactionState combine 依赖这三个源发射 —— relaxed mock 的 Flow 不发射会导致 | L121 [line]  stateIn(WhileSubscribed) 永不产生首发射，.value 恒为初始值 | L184 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L187 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L188 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | L195 [kdoc] restoredDraftState 由 stateIn 支撑，需要活跃订阅者才能发出更新。 | L196 [kdoc] 没有订阅者时，value 返回初始值。 | L200 [block]  保持订阅存活 | L204 [kdoc]  interactionState 是 stateIn(WhileSubscribed)，无订阅者时 value 恒为初始值。 | L207 [block]  保持订阅存活 | L211 [line]  ========== 悲观消息语义（Task 7） ========== | L215 [line]  coAnswers + delay 模拟 POST 受理中的网络窗口：isSending 保持 true 直到响应返回 | L225 [line]  发送中（POST 受理前） | L227 [line]  204 后恢复（可连续发送） | L242 [line]  2026-08-11 用户要求：失败 → AlertDialog（sendFailure 信号），不再回填草稿 | L243 [line] （输入框内容在发送期间保留，无需 restoredDraft 恢复） | L244 [line]  AlertDialog 信号 | L249 [line]  finally 复位 | L256 [line]  sendPrompt 挂起期间 isSending=true，第二次 sendMessage 应被 isSendingValue 守卫拦截 | L264 [line]  isSending 期间应被忽略 | L270 [line]  ========== 保留：草稿恢复与消费（悲观语义下） ========== | (+5 more)
ZHSTR: L246 "输入框保留语义下不应再设置 restoredDraft" | L288 "sendFailure 应携带错误信息" | L289 "输入框保留语义下不应设置 restoredDraft"
ENSTR*: L134 "Test Session" | L309 "restoredDraft should remain null after consume when already null"
TESTS-EN: `isSending flips during send and clears after REST accepted` | `send failure emits sendFailure alert and clears isSending` | `double send is ignored while sending` | `send failure emits sendFailure in V1` | `consumeRestoredDraft is safe when already null`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelSendTest.kt","loc":314,"lang":"中文","zh":27,"en":0,"kdoc":3,"tests":5,"cls":"ChatViewModelSendTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegateTest.kt
LOC 336 | lang 中文 (zh 27/en 0, kdoc 8) | @Test 7 | btFuns 7 | MessageDataDelegateTest
LEX: session,message,part,turn,tool,directory,permission,provider,sse,paginat,revert,abort,idle,snapshot,patch
FREQ: delegate×65 state×46 flow×43 running×24 scope×20 progress×19 assistant×19 repository×18 coroutines×18 kotlinx×15 advance×15 collect×15 domain×14 equals×14
C-ZH: L45 [kdoc] 聚焦验证 [MessageDataDelegate.messageListState] 的 combine 管道正确消费 | L46 [kdoc] 第 10 个源（getActiveToolProgressForSession → args[9]），工具进度输出注入 | L47 [kdoc] 到 Running 态 tool part 的 output 字段。 | L49 [kdoc] 回归背景（2026-08-10）：progressList 曾误用 args[8]（statusFlow 位）， | L50 [kdoc] 导致 progressOutputs 永远为空、ToolProgressOutputInjector.inject 永不生效。 | L62 [kdoc]  delegate 的协程作用域——独立于 TestScope，避免 combine 常驻协程触发 | L63 [kdoc]  runTest 的 "all coroutines must complete" 检查。测试结束显式 cancel。 | L143 [kdoc]  messageListState 由 stateIn(WhileSubscribed5s) 支撑，需要活跃订阅者。 | L152 [line]  给定：1 条 assistant 消息，含 Running tool part（callId="c1"） | L155 [line]  loading=false 让消息进入 visible 分支 | L158 [line]  当：第 10 个源（getActiveToolProgressForSession）吐出 progress 数据 | L164 [line]  那么：messageListState 中 tool part 的 Running.output 被注入 | L184 [line]  第 10 个源始终为空（默认值） | L188 [line]  默认 Running() 的 output 是空串 | L204 [line]  progress 的 callId 与 part 不匹配 | L218 [line]  ============ #44：sseJob 投影（messageListState 携带 rawMessages/partsByMessageId） ============ | L238 [line]  给定：user 消息 + 无 parts 的 assistant（窗口期场景） | L246 [line]  那么：state 携带原始消息与 parts 映射（#44：唯一 combine 管道提供） | L253 [line]  parts 到达后映射随之更新 | L266 [line]  给定：1 条 user + 1 条无 parts 的 assistant + 1 条有 parts 的 assistant | L276 [line]  当：启动 SSE 观察 | L280 [line]  那么：messagesList 投影过滤无 parts assistant（u-1 + a-2） | (+5 more)
ZHSTR: L327 "快照应冻结在取消时"
ENSTR*: L166 "messages should contain the assistant message"
TESTS-EN: `progress from args 9 injects output into Running tool part` | `no progress flow leaves Running tool output empty` | `progress with unmatched callId does not inject` | `messageListState carries rawMessages and partsByMessageId for sseJob projection` | `sseJob projection filters assistant without parts but keeps user and raw` | `sseJob projection reveals assistant once its part arrives` | `cancelSseJob freezes snapshot and restart resumes`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegateTest.kt","loc":336,"lang":"中文","zh":27,"en":0,"kdoc":8,"tests":7,"cls":"MessageDataDelegateTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelStreamingTest.kt
LOC 373 | lang 中文 (zh 32/en 0, kdoc 2) | @Test 5 | btFuns 5 | ChatViewModelStreamingTest
LEX: session,message,event,part,turn,token,tool,agent,directory,draft,question,permission,provider,unread,stream,sse,paginat,idle,snapshot,patch,terminal,pending,feedback,notification,upsert,badge
FREQ: case×47 repository×45 state×38 flow×37 model×31 manage×29 relaxed×24 domain×20 refresh×20 coroutines×16 settings×15 chat×14 kotlinx×14 server×12
C-ZH: L118 [line]  将 messagePaging.observeMessages 接线到可控 flow | L173 [kdoc]  用带文本 part 的用户消息 stub listMessages（可穿越 V1→V2 桥）。 | L226 [line]  堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响 | L229 [line]  drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter | L230 [line]  会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起 | L237 [kdoc] messageListState 由 stateIn(WhileSubscribed) 支撑，需要活跃订阅者。 | L241 [block]  保持订阅存活 | L245 [line]  ========== 测试 1：refreshSession 不会将 isLoading 设为 true ========== | L249 [line]  给定：已有消息的 ViewModel（经由 V1→V2 桥） | L255 [line]  验证刷新前消息已存在 | L259 [line]  当：调用 refreshSession | L263 [line]  那么：消息不应被清空（因为 refreshSession 使用 _isRefreshing 而非 _isLoading） | L273 [line]  ========== 测试 2：刷新时 V1 setMessages 替换状态 ========== | L277 [line]  给定：通过初始加载获得的消息 | L284 [line]  验证初始消息存在 | L290 [line]  当：REST 刷新返回空消息（例如服务器延迟） | L295 [line]  那么：V1 setMessages 做全量替换 —— 消息被清空 | L305 [line]  ========== 测试 3：V1 refreshIfNeeded 始终触发刷新 ========== | L309 [line]  给定：ViewModel | L314 [line]  init 后清除 mock 状态（init 调用 loadMessages → listMessages 一次） | L318 [line]  当：调用 refreshIfNeeded（V1 无冷却 —— 始终刷新） | L322 [line]  那么：应调用 listMessages（V1 委托给 refreshSession） | (+10 more)
ENSTR*: L161 "Test Session" | L257 "Messages should exist before refresh" | L266 "Messages should NOT be wiped during refresh, got ${afterRefresh.size} messages" | L286 "Initial messages should exist" | L298 "V1 setMessages replaces state, got ${state.messages.size} messages" | L353 "Messages should not be cleared when list has 1 message (isEmpty check, not size
TESTS-EN: `refreshSession does not set isLoading to true` | `messageListState matches refresh result in V1` | `refreshIfNeeded triggers refresh in V1` | `loading guard only clears truly empty message lists` | `loadSession applies initialMessageCount from settings as listMessages limit`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/ChatViewModelStreamingTest.kt","loc":373,"lang":"中文","zh":32,"en":0,"kdoc":2,"tests":5,"cls":"ChatViewModelStreamingTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegateTest.kt
LOC 1083 | lang 混合 (zh 99/en 2, kdoc 10) | @Test 29 | btFuns 29 | MessagePaginationDelegateTest
LEX: session,message,part,turn,provider,sse,cursor,paginat,page,retry,idle,archive,fallback,merge,upsert
FREQ: load×188 delegate×145 older×137 paging×96 newer×95 store×88 relaxed×80 repository×69 case×69 target×67 sink×57 policy×46 created×43 around×42
C-ZH: L40 [kdoc]  构造指定 id + created 的消息（loadAround/loadNewer 测试用）。 | L69 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L92 [line]  游标翻页：limit 不再翻倍 | L102 [line]  2026-08-18 回归（V2 长会话历史不可达）：HotStart+V2 首翻不得本地构造 | L103 [line]  encodeV2 游标（2026-08-12 补丁曾旁路 use case 2026-08-16 根治路径—— | L104 [line]  中部历史 id 在服务器窗口语义下返回空页 → hasOlder=false 误判读尽）。 | L105 [line]  必须传 networkCursor=null，让 use case 走 null-cursor 首翻拿原生 cursor.next。 | L133 [line]  首翻 networkCursor=null（null-cursor 路径）；响应携带原生游标 → FSM Network 态 | L142 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L172 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L194 [line]  游标翻页：失败时 limit 不变（不再 halve back） | L203 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L227 [line]  归档来源只进内存（APPEND_ONLY），不落热表 | L234 [line]  第一次翻页：beforeCreated=null（初始），返回 30 条归档消息（created 0..29），最老 created=0 | L237 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L240 [line]  第二次翻页：beforeCreated=0（游标推进为最老消息 created） | L262 [line]  第一次：游标推进为 0（mkMessages 的 created 是 0..29，最老 = 0） | L266 [line]  第二次翻页必须用推进后的游标（beforeCreated=0），不能重复读同一批 | L272 [line]  归档翻页推进游标后，网络来源把游标重置（下次从热表边界重新开始） | L275 [line]  2026-08-12 修复：V2 首次翻页构造 V2 游标——测试 mock 需显式 stub（非 relaxed） | L276 [line]  第一次：无游标（null） | L279 [line]  第二、三次：归档游标 0（第 6 参为 null——无网络游标） | (+77 more)
   (+2 trivial en comments)
ENSTR*: L380 "retry fail"
TESTS-EN: `initial limit is 30 and hasOlderMessages false` | `loadOlderMessages uses oldestMessageId as cursor and sets hasOlderMessages by boundary` | `loadOlderMessages v2 hot start passes null cursor - no local encodeV2 bypass` | `loadOlderMessages sets hasOlderMessages false when fewer than limit` | `loadOlderMessages keeps limit unchanged on exception` | `loadOlderMessages archive source only merges memory not store` | `loadOlderMessages archive source advances cursor for next page` | `loadOlderMessages network source resets archive cursor` | `loadMessages success drives loading sink and setMessages` | `loadMessages on non-OOM error pushes message to errorSink` | `loadMessages OOM halves limit and retries then reports retry error on second failure` | `loadMessages OOM halves limit and retry succeeds via mergeMessages` | `loadMessagesForSession applies settings initialMessageCount and sets hasOlderMessages` | `loadMessagesForSession swallows exception without throwing` | (+15 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessagePaginationDelegateTest.kt","loc":1083,"lang":"混合","zh":99,"en":2,"kdoc":10,"tests":29,"cls":"MessagePaginationDelegateTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt
LOC 31 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | QuestionReplacementTest
LEX: session,event,question,sse,snapshot,pending
FREQ: rest×7 equals×4 replacement×4 resolve×3
TESTS-EN: `rest result replaces previous snapshot entirely` | `empty rest result clears session` | `rest result drops questions no longer pending`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt","loc":31,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"QuestionReplacementTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/PartRenderLogicTest.kt
LOC 268 | lang 中文 (zh 15/en 0, kdoc 4) | @Test 24 | btFuns 24 | PartRenderLogicTest
LEX: session,message,part,turn,tool,agent,question,permission,compaction,sse,abort,retry,snapshot,patch,task,render,bubble
FREQ: filtered×34 create×25 reasoning×23 equals×16 filter×15 step×8 file×5 start×4 finish×4 preserves×3
C-ZH: L11 [kdoc] 从 ChatScreen.kt 提取的 Part 渲染逻辑测试。 | L13 [kdoc] 核心验证：该 bug 修复确保 parts 按服务器发送的原始顺序渲染 | L14 [kdoc] （例如 Text → Tool → Reasoning → Tool → Text）， | L15 [kdoc] 而不是被拆分成独立分组后乱序渲染。 | L19 [line]  === isBubbleRenderablePart —— 可渲染类型 === | L75 [line]  === isBubbleRenderablePart —— 不可渲染类型 === | L125 [line]  === filterRenderableParts —— 顺序保留（核心 bug 修复）=== | L129 [line]  这正是该 bug 修复针对的场景： | L130 [line]  服务器发送：Text → Tool → Reasoning → Tool → Text | L131 [line]  修复前：渲染为 Tool, Tool, Text, Reasoning, Text（分组） | L132 [line]  修复后：渲染为 Text, Tool, Reasoning, Tool, Text（原始顺序） | L144 [line]  验证精确顺序被保留 | L151 [line]  验证类型处于正确的交错顺序 | L161 [line]  另一种常见模式：Reasoning → Tool → Reasoning | L249 [line]  === 辅助函数 ===
TESTS-EN: `isBubbleRenderablePart returns true for Text` | `isBubbleRenderablePart returns true for Reasoning` | `isBubbleRenderablePart returns true for Patch` | `isBubbleRenderablePart returns true for File` | `isBubbleRenderablePart returns true for Permission` | `isBubbleRenderablePart returns true for Question` | `isBubbleRenderablePart returns true for Abort` | `isBubbleRenderablePart returns true for Retry` | `isBubbleRenderablePart returns true for Tool` | `isBubbleRenderablePart returns false for StepStart` | `isBubbleRenderablePart returns false for StepFinish` | `isBubbleRenderablePart returns false for Snapshot` | `isBubbleRenderablePart returns false for Subtask` | `isBubbleRenderablePart returns false for Compaction` | (+10 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/PartRenderLogicTest.kt","loc":268,"lang":"中文","zh":15,"en":0,"kdoc":4,"tests":24,"cls":"PartRenderLogicTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ExtractToolSubagentSessionIdTest.kt
LOC 85 | lang 中文 (zh 3/en 0, kdoc 2) | @Test 7 | btFuns 7 | ExtractToolSubagentSessionIdTest
LEX: session,message,part,turn,tool,agent,subagent,sse,task
FREQ: metadata×14 json×11 meta×10 extract×9 child×9 build×7 state×6 equals×6 completed×5 running×5 extracted×4 domain×3 model×3 kotlinx×3
C-ZH: L13 [kdoc] extractToolSubagentSessionId（synthetic 完成通知「定位发起卡片」的匹配键）测试。 | L14 [kdoc] 与 TaskToolCard 的子会话跳转解析一致：Completed/Running 的 metadata.sessionId。 | L45 [line]  V2 服务器 metadata 用 jobId 存子会话 ID（task.ts: jobId = nextSession.id）
TESTS-ZH: `sessionId 优先于 jobId`
TESTS-EN: `completed with sessionId extracted` | `completed with sessionID uppercase extracted` | `completed with jobId extracted (V2 server key)` | `no metadata returns null` | `running state also extracted` | `blank sessionId returns null`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ExtractToolSubagentSessionIdTest.kt","loc":85,"lang":"中文","zh":3,"en":0,"kdoc":2,"tests":7,"cls":"ExtractToolSubagentSessionIdTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/SessionLifecycleDelegateTest.kt
LOC 112 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | SessionLifecycleDelegateTest
LEX: session,turn,directory,sse,idle
FREQ: delegate×17 case×16 handle×7 coroutines×7 ensure×7 repo×7 create×7 lifecycle×6 relaxed×6 repository×5 kotlinx×5 manage×4 equals×4 chat×3
TESTS-EN: `ensureSession returns existing id without creating` | `ensureSession creates session when empty` | `ensureSession concurrent calls create only once` | `initForNewSession sets directory from param`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/SessionLifecycleDelegateTest.kt","loc":112,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"SessionLifecycleDelegateTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPagerHeightTest.kt
LOC 47 | lang 中文 (zh 9/en 0, kdoc 4) | @Test 5 | btFuns 5 | QuestionPagerHeightTest
LEX: turn,question,sse,page
FREQ: height×10 equals×9 capped×9 lerp×8
C-ZH: L7 [kdoc] 2026-08-18 E2E-E 回归：QuestionPagerView 页高插值 + 上限截断纯函数。 | L9 [kdoc] 背景：6+ 选项页（含自定义输入）内容高于视口时，卡片在消息流中整体不可达 | L10 [kdoc] （reverseLayout 锚定 + 自动回底，任何手势滚不进视口）——修复为页内容限高 | L11 [kdoc] （屏高 40%）+ 页内滚动；插值对截断后的高度进行，卡片高度恒定于上限。 | L17 [line]  原始插值语义保持：低页 300 → 高页 500，进度 0.5 → 400 | L23 [line]  两页均超上限（1200/1500 > 1000）→ 插值恒为上限，不撑爆卡片 | L31 [line]  短页 300 → 高页 1200（截为 1000）：进度 0.5 → (300+1000)/2 = 650 | L37 [line]  fromHeight=0（未测量）→ 0：调用方保持 wrap（高度 0 会塌陷） | L43 [line]  越界进度防御：progress=2 等价 1（完整目标高度）
TESTS-EN: `both pages below cap interpolate linearly` | `tall pages clamp to cap` | `mixed pages interpolate between capped values` | `unmeasured from page returns zero for wrap gate` | `progress clamped to unit range`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPagerHeightTest.kt","loc":47,"lang":"中文","zh":9,"en":0,"kdoc":4,"tests":5,"cls":"QuestionPagerHeightTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/RenderSupplyCoordinatorTest.kt
LOC 295 | lang 中文 (zh 46/en 0, kdoc 18) | @Test 10 | btFuns 10 | RenderSupplyCoordinatorTest,Env
LEX: session,message,part,turn,stream,sse,idle,patch,pending,jump,chunk,preparse,render
FREQ: coordinator×41 world×32 blocking×22 display×20 viewport×20 changed×20 assistant×16 parsed×15 target×13 chat×11 pairs×11 plans×11 phase×10 plain×10
C-ZH: L23 [kdoc] 渲染供给协调器单测（架构评审 #169 阶段 3——Q6-B 用例集）。 | L25 [kdoc] 用例与历史竞态根因一一对应： | L26 [kdoc] - T1 流式禁预解析（部分快照被解析 → 回复永久截断） | L27 [kdoc] - T2 预解析供给（正控） | L28 [kdoc] - T3 display 粒度窗口（chunk 化后 entry→display 映射） | L29 [kdoc] - T4 LRU 联动 registry.remove（#98 防无界增长） | L30 [kdoc] - T5 门控-相位（Preparing/Measuring/Settling 非终态不提交） | L31 [kdoc] - T6 门控-稳定窗口（终点+2s 内不提交——注入时钟） | L32 [kdoc] - T7 F1 partId 反查（loadAround 重建后陈旧 display index 失效） | L33 [kdoc] - T8 F2 视口内防线（窗口内永不提交裂变） | L34 [kdoc] - T9 C-R4c 陈旧丢弃（turn 消失 → pending 真正清空） | L35 [kdoc] - T10 流式 turn 记录 + 窗口清理（recentStreamedTurnKeys） | L37 [kdoc] 真实 RenderReadinessRegistry + 真实 markdown 解析（Dispatchers.Default， | L38 [kdoc] await Parsed 终态同步）；Unconfined 作用域保证相位打点即时生效。 | L45 [line]  非零基数：0 会被门控当作「从未跳转」（生产 elapsedRealtime 恒非零） | L53 [kdoc]  交替 user/assistant 世界：assistant i 的 display index = 2i+1（独立 turn）。 | L71 [line]  ============ fixtures（文件级——Env 无外部类接收者） ============ | L83 [kdoc]  目标 part 独占目标 assistant 的世界 fixture（避免多 launch 竞态）。 | L93 [kdoc]  种入 pending 分片计划：视口盖住目标 assistant → 解析 → 计划入队。 | L99 [line]  preParse 回调与 flow 发射的相邻语句保险 | L115 [line]  超时即失败（未 Parsed） | L121 [line]  display 5（assistant a2）裂成 3 个 entry；视口 entry 5..7 全属 display 5 | (+24 more)
ZHSTR: L75 "key=$key 不应被预解析（或应已被 LRU 淘汰）" | L167 "Preparing 中不应提交" | L170 "Idle 后应提交" | L182 "稳定窗口内不应提交" | L185 "稳定窗口过后应提交" | L205 "F1 应按 partId 反查到新位置提交" | L224 "冷 part 带内即提交（单体从未组合，裂变零成本）" | L233 "视口内不提交" | L235 "热 part 带内不提交（缓存池保护）" | L237 "出带即提交" | (+4 more)
TESTS-ZH: `T1_流式turn不预解析` | `T2_窗口内assistant长文本被预解析` | `T3_chunk化entries下窗口按display粒度扩展` | `T4_超过LRU上限淘汰最旧条目并联动registry移除` | `T5_跳转进行中不提交分片计划` | `T6_跳转终点后2秒稳定窗口内不提交` | `T7_display重建后提交按partId反查新位置` | `T8_冷part带内即提交_热part带内拦截出带提交` | `T9_turn从世界消失时pending真正丢弃` | `T10_流式结束turnKey记录并在离开窗口后清除`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/RenderSupplyCoordinatorTest.kt","loc":295,"lang":"中文","zh":46,"en":0,"kdoc":18,"tests":10,"cls":"RenderSupplyCoordinatorTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/IsBackgroundMoveSyntheticTest.kt
LOC 50 | lang 中文 (zh 2/en 0, kdoc 2) | @Test 6 | btFuns 6 | IsBackgroundMoveSyntheticTest
LEX: agent,sse
FREQ: background×12 synthetic×9 move×7 matches×4 user×4 active×4 blocking×4 moved×4 requested×3
C-ZH: L8 [kdoc] #136（D2-L55）：转后台 synthetic 提示识别——服务器模板变体匹配。 | L9 [kdoc] 单一硬编码模板在服务器改文案后静默失效；现为变体列表 + 可测辅助函数。
ENSTR*: L46 "User requested that active blocking work be moved to the background.\nThe agent
TESTS-EN: `exact current server template matches` | `server template with surrounding text matches` | `known wording variant matches` | `unrelated synthetic text does not match` | `empty text does not match` | `multi-line text containing marker matches`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/IsBackgroundMoveSyntheticTest.kt","loc":50,"lang":"中文","zh":2,"en":0,"kdoc":2,"tests":6,"cls":"IsBackgroundMoveSyntheticTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpNavigationControllerTest.kt
LOC 127 | lang 中文 (zh 12/en 0, kdoc 2) | @Test 15 | btFuns 0 | JumpNavigationControllerTest
LEX: event,sse,abort,idle,jump
FREQ: phase×64 transition×23 desired×12 equals×10 compute×10 offset×10 ready×8 failed×7 padding×6 preparing×6 parsed×4 measuring×4 measure×4 settling×4
C-ZH: L8 [kdoc] 跳转定位状态机单测（架构评审 Q7——状态转移 + 纯函数）。 | L9 [kdoc] 覆盖本会话反复出错的计算逻辑：desired/gap 公式、状态转移路径。 | L13 [line]  ============ 纯函数：computeDesiredOffset ============ | L17 [line]  vh=1808, item=331, paddingTop=21 → 1456（实测拟合值） | L30 [line]  paddingTop 变化（contentPadding 调整/不同密度）→ desired 跟随 | L35 [line]  ============ 纯函数：computeGap ============ | L39 [line]  offset+size = vh - paddingTop → gap=0（顶边贴视口顶） | L45 [line]  reverse 坐标：顶边滚动坐标 < 视口顶 → 顶边在视口上方（超出）→ gap < 0 | L51 [line]  顶边在视口下方（有空隙）→ gap > 0 | L55 [line]  ============ 状态转移：正常路径 ============ | L81 [line]  ============ 状态转移：失败路径 ============ | L108 [line]  ============ 状态转移：非法事件不破坏状态 ============
ZHSTR: L25 "目标越高 desired 越小" | L87 "预解析超时"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpNavigationControllerTest.kt","loc":127,"lang":"中文","zh":12,"en":0,"kdoc":2,"tests":15,"cls":"JumpNavigationControllerTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/SyntheticTaskParserTest.kt
LOC 131 | lang 混合 (zh 8/en 2, kdoc 9) | @Test 10 | btFuns 10 | SyntheticTaskParserTest
LEX: session,agent,subagent,permission,sse,task,fallback,render,notification
FREQ: equals×19 summary×18 info×18 completed×17 error×11 background×11 synthetic×10 state×10 output×8 extract×7 description×7 trim×4 indent×4 failed×4
C-ZH: L8 [kdoc] SyntheticNotificationCard 的 <task> 结构化文本解析测试。 | L10 [kdoc] 服务器（opencode task.ts renderOutput）在后台 subagent 完成时向主会话 | L11 [kdoc] 注入 synthetic 消息，text 为： | L13 [kdoc]   <summary>Background task completed: <描述></summary> | L14 [kdoc]   <task_result|task_error>…输出…</task_result|task_error> | L16 [kdoc] 客户端解析出 sessionId（子会话跳转引用）、state（完成/失败色彩）、 | L17 [kdoc] summary（标题描述）、output（展开内容）。 | L98 [line]  ============ extractTaskDescription（summary 前缀剥离）============
C-EN*: L12   <task id="ses_xxx" state="completed|error"> | L15   </task>
ZHSTR: L23 """
            <task id="ses_abc123" state="completed">
            <summary>Background t | L36 "Background task completed: 扫描项目结构" | L42 """
            <task id="ses_xyz" state="error">
            <summary>Background task fai | L55 "Background task failed: 执行脚本" | L77 "普通文本消息，不是 task 格式" | L79 "<task>没有属性</task>" | L103 "扫描项目结构" | L111 "执行部署脚本" | L112 "Background task failed: 执行部署脚本" | L118 "自定义通知文本"
ENSTR*: L56 "Error: permission denied" | L61 """
            <task state="completed">
            <summary>Background task co | L84 """
            <task id="ses_1" state="completed">
            <summary>Backgro | L129 "Background task completed:"
TESTS-ZH: `解析完整的 completed 任务文本` | `解析 error 任务使用 task_error 标签` | `无 id 时 sessionId 为 null（不崩溃）` | `非 task 格式返回 null（fallback 到纯文本）` | `输出为空时 output 为 null（无展开内容）` | `completed summary 剥离前缀得到任务描述` | `failed summary 剥离前缀` | `无前缀 summary 原样返回` | `null 或空白 summary 返回空串` | `前缀后空白内容回退到原文`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/SyntheticTaskParserTest.kt","loc":131,"lang":"混合","zh":8,"en":2,"kdoc":9,"tests":10,"cls":"SyntheticTaskParserTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpLockDerivationTest.kt
LOC 147 | lang 中文 (zh 9/en 0, kdoc 5) | @Test 8 | btFuns 8 | JumpLockDerivationTest,Env
LEX: message,sse,idle,patch,pending,jump
FREQ: phase×34 flow×20 controller×20 lock×17 active×14 scope×13 scheduler×11 kotlinx×9 cancel×9 advance×9 time×9 coroutines×8 preparing×7 state×6
C-ZH: L16 [kdoc] #159 收口（2026-08-22）：jumpLockActive 派生锁单测——替代 ChatMessageList | L17 [kdoc] 手工镜像（4 写点任一遗漏即竞态；loadAround 失败路径漏复位已实证锁永久卡死）。 | L19 [kdoc] 经 phaseFlow 注入直接驱动相位（不触发执行器——listState 全程闲置）， | L20 [kdoc] 虚拟时钟验证终点 300ms 缓冲窗口与「缓冲期内新跳转取消解锁」的 | L21 [kdoc] collectLatest 语义（等价原 ChatMessageList 解锁 effect 键重启）。 | L69 [line]  advanceTimeBy 不执行目标时刻恰好到期的任务（kotlinx 语义）——多走 1ms | L99 [line]  旧缓冲还差 50ms——新跳转插入：collectLatest 取消未完成的解锁延迟 | L101 [line]  越过旧解锁点 | L140 [line]  用户已点可跳目标
ZHSTR: L68 "终点缓冲内应锁定" | L71 "缓冲期满应解锁" | L82 "测试超时" | L97 "超时" | L102 "新跳转进行中应锁定" | L116 "异步窗口（loadAround 期间）应同步锁定" | L117 "phase 不经执行器不应变化" | L129 "失败路径必须解锁" | L142 "活跃跳转期间失败清理不得解锁"
TESTS-ZH: `初始 Idle 锁为 false` | `跳转进行中锁定（Preparing Measuring Settling 全相）` | `Displayed 终点后 300ms 内保持锁定随后解锁` | `Failed 终点同样走缓冲解锁` | `缓冲期内新跳转到来取消解锁并继续锁定` | `markJumpPending 异步窗口立即锁定且 phase 保持 Idle` | `异步定位失败解锁（回归：旧镜像此路径漏复位永久卡死）` | `失败清理与活跃跳转交错时是 no-op（锁归进行中的跳转）`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/JumpLockDerivationTest.kt","loc":147,"lang":"中文","zh":9,"en":0,"kdoc":5,"tests":8,"cls":"JumpLockDerivationTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/FindJumpTargetItemTest.kt
LOC 72 | lang 中文 (zh 3/en 0, kdoc 1) | @Test 6 | btFuns 0 | FindJumpTargetItemTest
LEX: message,turn,sse,jump,chunk
FREQ: target×30 find×9 equals×6 info×4 offset×3
C-ZH: L9 [kdoc] findJumpTargetItem 单测（2026-08-20 分片适配——任务定位到分片 turn 必失败的根因修复）。 | L46 [line]  "t_target#c..." 前缀不得误配 "t_target2#c0"（#c 后还有别的消息 id） | L59 [line]  visibleItemsInfo 通常按 index 序，但防御乱序输入
ZHSTR: L40 "首 chunk（消息顶边）"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/FindJumpTargetItemTest.kt","loc":72,"lang":"中文","zh":3,"en":0,"kdoc":1,"tests":6,"cls":"FindJumpTargetItemTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ParseSyntheticTaskTest.kt
LOC 98 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 8 | btFuns 8 | ParseSyntheticTaskTest
LEX: session,turn,agent,subagent,sse,task
FREQ: info×25 equals×20 state×13 synthetic×11 completed×9 summary×8 output×8 error×8 parses×6 shell×6 format×5 description×5 hello×4 background×3
C-ZH: L39 [line]  运行中的旧版服务器实际格式（2026-08-12 实测）： | L40 [line]  修复前 parseSyntheticTask 只认 <task> → 此格式返回 null → 降级原始 XML 文本 | L74 [line]  2026-08-12 修复：<shell> 标签正文提取（此前只 <subagent> 走正文提取， | L75 [line]  shell 通知 output null → 无展开按钮）
ZHSTR: L11 """
            <task id="ses_abc" state="completed">
            <summary>Background task | L20 "Background task completed: 总结文档" | L21 "文档列表：a.md b.md" | L26 """
            <task id="ses_err" state="error">
            <summary>Background task fai | L34 "错误信息" | L41 """<subagent id="ses_00c6a275fffeU4010j6IMLiqUF" state="completed" description="简单算术验证后台任务 | L47 "简单算术验证后台任务" | L53 """<subagent id="ses_x" state="completed" description="多行输出任务">
第一行
第二行
</subagent>""" | L58 "第一行\n第二行" | L63 """<subagent id="ses_y" state="error" description="失败任务">
任务执行失败：网络超时
</subagent>""" | (+4 more)
TESTS-EN: `parses new task format` | `parses new task format with error` | `parses subagent format from running server` | `parses subagent format with multiline output` | `parses subagent error state` | `parses shell format` | `returns null for non task text` | `returns null when state missing`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ParseSyntheticTaskTest.kt","loc":98,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":8,"cls":"ParseSyntheticTaskTest"}






═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt
LOC 31 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 4 | btFuns 4 | QuestionCardLogicTest
LEX: turn,question,sse
FREQ: unanswered×10 indexes×8 equals×5
TESTS-EN: `unansweredQuestionIndexes - empty answers returns all` | `unansweredQuestionIndexes - some answered returns only unanswered` | `unansweredQuestionIndexes - all answered returns empty` | `unansweredQuestionIndexes - short answers list pads with unanswered`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt","loc":31,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":4,"cls":"QuestionCardLogicTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/UserChunkTest.kt
LOC 171 | lang 混合 (zh 15/en 3, kdoc 6) | @Test 10 | btFuns 10 | UserChunkTest
LEX: session,message,part,turn,compaction,sse,chunk
FREQ: user×43 chat×22 chars×22 plain×13 build×12 equals×10 time×9 display×9 split×6 line×6 info×5 target×5 segs×5 assistant×5
C-ZH: L15 [kdoc] 用户长消息纯文本分片（2026-08-22 滚动巨帧根治）： | L16 [kdoc] - splitUserTextChunks：门槛/预算/行边界/超长单行硬切/重组等价 | L17 [kdoc] - buildChatEntries：UserChunk 发射条件（保守判定——synthetic/压缩/多 part 不分片） | L18 [kdoc] - key/双向索引语义（u_ 前缀保持，displayEntryStart 指向首段） | L31 [line]  40 行 × 100 字符 = 4000 字符 → 至少 2 段 | L37 [line]  每段（除末段）达到预算；行边界保留（无行内截断） | L42 [line]  重组等价（尾部多个换行可容忍） | L48 [line]  单行无换行：切不出多段（不插入原文没有的换行——零内容变异原则） | L54 [line]  恰好门槛但预算高于总长 → 单段 → null | L58 [line]  ============ buildChatEntries 发射 ============ | L71 [line]  双向索引：display 0 → 首段 entry 序号 0 | L74 [line]  不再有整 turn 条目 | L136 [line]  索引一致性：entryDisplayIndex 全程指向正确 display | L163 [kdoc]  无换行的定长行内容（行内全字母，长度可控）。 | L168 [kdoc]  多行文本（每行 100 字符 + 换行）——用户粘贴文档的真实形态。
C-EN*: L22  ============ splitUserTextChunks ============
   (+2 trivial en comments)
ZHSTR: L36 "应切出多段, got ${segs.size}" | L39 "段长 ${s.length} 应 >= 预算" | L40 "段应整行组成" | L66 "应发射 UserChunk, got ${entries.entries}"
TESTS-ZH: `短于门槛不分片` | `多行文本按预算切段且重组等价` | `无换行超长单行保守不切分` | `切不出多段返回 null` | `长单文本用户消息发射 UserChunk` | `短用户消息保持 Turn` | `多文本 part 用户消息不分片` | `synthetic 角色用户消息不分片` | `压缩触发用户消息不分片` | `长用户消息与 assistant 消息共存时索引正确`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/UserChunkTest.kt","loc":171,"lang":"混合","zh":15,"en":3,"kdoc":6,"tests":10,"cls":"UserChunkTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/ClickableMarkdownResultTest.kt
LOC 80 | lang 中文 (zh 9/en 0, kdoc 4) | @Test 4 | btFuns 4 | ClickableMarkdownResultTest
LEX: sse
FREQ: markdown×18 clickable×13 link×13 equals×11 ranges×9 compose×8 style×8 links×7 annotator×7 annotated×6 code×5 span×5 path×4 docs×4
C-ZH: L35 [line]  ============ #120（D2-08）：重复文本链接区间精确性 ============ | L38 [kdoc] D2-08 回归：两个同文本链接指向不同 URL——旧 indexOf 实现两个点击都 | L39 [kdoc] 命中第一个。修复后区间取自链接 span，各归其位。 | L40 [kdoc] 验证入口：buildClickableMarkdown 产出的 ranges 与 items 一一对应， | L41 [kdoc] 同文本两链接的区间互不重叠。 | L52 [line]  annotatorSettings() 工厂是 @Composable（读 LocalMarkdownTypography 等）， | L53 [line]  JUnit 函数内无法调用；直接构造库内实现类（referenceLinkHandler/ | L54 [line]  linkInteractionListener 默认 null，markdownAnnotator() 非 composable） | L76 [line]  区间文本即链接文本（精确性）
TESTS-EN: `ClickableItem Link has correct properties` | `ClickableItem CodePath has correct properties` | `ClickableMarkdownResult holds annotated string and items` | `D2-08 duplicate-text links get distinct non-overlapping ranges`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/ClickableMarkdownResultTest.kt","loc":80,"lang":"中文","zh":9,"en":0,"kdoc":4,"tests":4,"cls":"ClickableMarkdownResultTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/BusyIndicatorSmootherTest.kt
LOC 102 | lang 中文 (zh 23/en 0, kdoc 5) | @Test 7 | btFuns 7 | BusyIndicatorSmootherTest
LEX: sse,busy,idle
FREQ: sending×33 update×26 delay×14 indicator×9 smoother×9 equals×5 remaining×5
C-ZH: L9 [kdoc] [BusyIndicatorSmoother] 单测（2026-08-17 修复：busy 指示闪烁）。 | L11 [kdoc] 覆盖三条核心契约： | L12 [kdoc] 1. true 立即传导（busy 或 sending 任一为 true） | L13 [kdoc] 2. false 需持续稳定 releaseDelayMs 才传导 | L14 [kdoc] 3. 释放等待期间又变 true → 挂起的 false 不传导 | L23 [line]  初始 false | L25 [line]  busy 上升沿：立即 true | L27 [line]  持续 busy：保持 true | L42 [line]  下降沿 t=100：挂起释放，保持 true | L45 [line]  未到期（t=100+2499）：仍 true | L47 [line]  到期（t=100+2500）：释放为 false | L49 [line]  释放后 remaining 无挂起 | L51 [line]  已释放后保持 false | L59 [line]  下降沿 t=100，释放定于 t=2600 | L61 [line]  t=2000 又变 true（FSM 复活 Busy）：取消挂起，立即 true | L64 [line]  原定释放点已过但 busy 在保持：仍 true | L66 [line]  新下降沿 t=3000 → 释放点 t=5500；旧挂起不得提前生效 | L74 [line]  POST 完成（sending 下降）与 FSM 置 Busy（busy 上升）的组合缝隙： | L75 [line]  sending=false 先到、busy=true 未到 → 保持 true 等 busy 接管 | L78 [line]  sending 下降沿 t=100 | L80 [line]  缝隙期（t=500）busy 到达 → 持续 true | L87 [line]  冷启动/会话空闲：输入始终 false → 不应因释放延迟意外显示 | (+1 more)
TESTS-ZH: `busy true 传递立即` | `sending true 也立即传导` | `false 需稳定 delay 后才传导` | `释放等待期间又变 true 则不传导 false` | `busy 到 sending 接力无缝` | `从未置位时保持 false 不产生指示` | `发送失败回 idle 在 delay 后正常释放`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/input/BusyIndicatorSmootherTest.kt","loc":102,"lang":"中文","zh":23,"en":0,"kdoc":5,"tests":7,"cls":"BusyIndicatorSmootherTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DefaultToolCardResolverTest.kt
LOC 80 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 9 | btFuns 9 | DefaultToolCardResolverTest
LEX: session,message,part,turn,tool,sse,patch,task,pending
FREQ: resolve×18 resolver×14 resolves×7 bash×5 state×3 card×3 edit×3 glob×3 webfetch×3 fetch×3 unknown×3
ENSTR*: L48 "task should resolve" | L66 "apply_patch should resolve" | L72 "unknown tool should not resolve"
TESTS-EN: `resolves bash tool` | `resolves edit tool` | `resolves glob tool` | `resolves task tool` | `resolves webfetch tool` | `resolves web_fetch tool` | `resolves apply_patch tool` | `returns null for unknown tool` | `case insensitive matching`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DefaultToolCardResolverTest.kt","loc":80,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":9,"cls":"DefaultToolCardResolverTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/NormalizeTaskListMarkersTest.kt
LOC 86 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 14 | btFuns 14 | NormalizeTaskListMarkersTest
LEX: turn,sse,task
FREQ: markers×23 content×20 equals×15 normalize×15 normalized×6 fence×5 checkbox×4 inside×4 remain×3 unchanged×3 tilde×3 strikethrough×3 preserved×3 alongside×3
   (+1 trivial en comments)
ENSTR*: L10 "- [ ] Task" | L10 "- \u2610 Task" | L59 "Keep ~~strikethrough~~\n- \u2611 task" | L60 "Keep ~~strikethrough~~\n- [x] task" | L65 "Use ~/project\n- \u2610 a task" | L66 "Use ~/project\n- [ ] a task"
TESTS-EN: `unchecked ballot box normalized to empty checkbox` | `checked ballot box normalized to x checkbox` | `white heavy check mark normalized to x checkbox` | `plus and asterisk list markers also normalized` | `indented task markers normalized` | `ordinary list items remain unchanged` | `task markers inside code fence remain unchanged` | `task markers inside tilde fence remain unchanged` | `markers after fence closes are normalized again` | `strikethrough tildes preserved alongside task markers` | `standalone tilde preserved alongside task markers` | `email autolink preserved alongside task markers` | `empty string returns empty` | `marker without trailing space is not matched`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/NormalizeTaskListMarkersTest.kt","loc":86,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":14,"cls":"NormalizeTaskListMarkersTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/SplitOversizedParagraphsTest.kt
LOC 86 | lang 中文 (zh 7/en 0, kdoc 3) | @Test 9 | btFuns 0 | SplitOversizedParagraphsTest
LEX: sse
FREQ: split×19 lines×15 oversized×12 paragraphs×11 line×9 join×8 equals×6 code×6 plain×5 paragraph×3 ncode×3
C-ZH: L9 [kdoc] splitOversizedParagraphs（超长段落空行化）单测——2026-08-20 第二轮滚动 | L10 [kdoc] 卡顿修复 C-F1：巨型单段 PARAGRAPH（LLM 清单 "1 - one\n2 - two…"）让块级 | L11 [kdoc] 分片失效，空行化后每行独立成块 → 分片链路生效。 | L23 [line]  300 行 × 11 字符 ≈ 3600 字符 > 3000 阈值 → 空行化 | L27 [line]  内容行守恒（无丢失） | L35 [line]  100 行 × 11 字符 ≈ 1100 字符 < 3000 → 原样 | L81 [line]  行需足够长使总字符 ≥3000（短输入会被提前原样返回）
ZHSTR: L45 "围栏内容原样" | L74 "普通长段应被空行化" | L75 "代码块原样" | L76 "短列表原样"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/SplitOversizedParagraphsTest.kt","loc":86,"lang":"中文","zh":7,"en":0,"kdoc":3,"tests":9,"cls":"SplitOversizedParagraphsTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/terminal/TerminalRecoveryActionTest.kt
LOC 77 | lang 混合 (zh 4/en 7, kdoc 8) | @Test 6 | btFuns 6 | TerminalRecoveryActionTest
LEX: sse,terminal
FREQ: recovery×36 state×27 equals×12 restart×12 triple×10 starting×6 connected×6 reconnecting×6 disconnected×6 exited×6 reconnect×3 restarts×3
C-ZH: L10 [kdoc] 对 [terminalRecoveryAction] 的穷举真值表覆盖。 | L36 [line]  PTY 仍存在 → 继续等待进行中的重连。 | L38 [line]  PTY 已消失 → 仅重建 socket 的重连没有意义，必须重建。 | L57 [line]  状态、isMissingPty、期望动作
   (+7 trivial en comments)
TESTS-EN: `Starting is never interrupted even when PTY reported missing` | `Connected never needs recovery` | `Reconnecting restarts only when PTY is gone` | `Disconnected reconnects socket when PTY present, restarts when missing` | `Exited always restarts regardless of missing-PTY signal` | `every state x isMissingPty combination matches the truth table`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/terminal/TerminalRecoveryActionTest.kt","loc":77,"lang":"混合","zh":4,"en":7,"kdoc":8,"tests":6,"cls":"TerminalRecoveryActionTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressChildSessionInjectionTest.kt
LOC 75 | lang 混合 (zh 2/en 1, kdoc 3) | @Test 5 | btFuns 0 | ToolProgressChildSessionInjectionTest
LEX: session,message,part,turn,tool,agent,subagent,sse,task
FREQ: output×16 call×14 running×13 state×11 child×11 progress×9 metadata×9 equals×7 json×5 injector×5 inject×5 primitive×4 content×3
C-ZH: L12 [kdoc] #180（2026-08-21）：Running 期子会话 id 注入契约—— | L14 [kdoc] TaskToolCard 据此在 Running 期显示导航并跳转。
C-EN*: L13 tool.progress metadata.sessionID → Part.Tool(Running).metadata.sessionId/sessionID，
ENSTR*: L28 "partial output"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressChildSessionInjectionTest.kt","loc":75,"lang":"混合","zh":2,"en":1,"kdoc":3,"tests":5,"cls":"ToolProgressChildSessionInjectionTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurnTest.kt
LOC 107 | lang 混合 (zh 7/en 1, kdoc 0) | @Test 6 | btFuns 6 | RenderableTurnTest
LEX: session,message,part,turn,tool,stream,sse,task,render
FREQ: assistant×15 equals×14 synthetic×12 msgs×11 created×9 compute×8 duration×8 chat×7 time×7 completed×7 model×6 notice×6 start×5 info×4
C-ZH: L51 [line]  首条 created，而非代表消息 a2 的 2500 | L58 [line]  a2 未完成 → 交给流式 ticker | L69 [line]  ============ synthetic 嵌入气泡（2026-08-11）============ | L78 [line]  synthetic 的 <task> 原文不应作为普通文本渲染 | L81 [line]  copyText 不含 synthetic 原文 | L101 [line]  assistant 文本仍渲染 | L104 [line]  copyText 只含 assistant 文本
   (+1 trivial en comments)
ENSTR*: L35 "<task id=\"ses_x\" state=\"completed\">x</task>"
TESTS-EN: `single completed message duration equals its own span` | `multi-message turn duration spans first created to last completed` | `streaming turn has null duration but stable turnStartMs` | `single streaming message has null duration` | `synthetic message produces SyntheticNotice render item` | `synthetic only in turn keeps assistant parts`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurnTest.kt","loc":107,"lang":"混合","zh":7,"en":1,"kdoc":0,"tests":6,"cls":"RenderableTurnTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/PartGrouperTest.kt
LOC 121 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 8 | btFuns 8 | PartGrouperTest
LEX: session,message,part,turn,tool,sse,context
FREQ: groups×28 group×19 equals×11 json×7 single×6 glob×5 summary×5 grep×4 state×3 primitive×3 input×3 grouped×3
TESTS-EN: `two consecutive reads are grouped` | `single read is not grouped` | `read glob grep are grouped together` | `bash splits context groups` | `text part splits context groups` | `summary counts read glob grep correctly` | `tool names are case insensitive` | `empty list returns empty`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/PartGrouperTest.kt","loc":121,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":8,"cls":"PartGrouperTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressOutputInjectorTest.kt
LOC 62 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 6 | btFuns 6 | ToolProgressOutputInjectorTest
LEX: session,message,part,turn,tool,sse
FREQ: state×19 running×16 output×15 progress×8 injector×7 equals×6 inject×6 call×5 completed×5 stdout×4 existing×3
TESTS-EN: `empty progress map returns parts unchanged` | `injects output into Running tool by callId` | `does not touch Completed tools` | `skips Running tools with no matching callId` | `empty output string does not replace existing` | `preserves non-Tool parts`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolProgressOutputInjectorTest.kt","loc":62,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":6,"cls":"ToolProgressOutputInjectorTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DiffHelpersTest.kt
LOC 43 | lang 英文 (zh 0/en 1, kdoc 0) | @Test 5 | btFuns 5 | DiffHelpersTest
LEX: tool,sse,diff
FREQ: equals×6 compute×5 added×4 line×4 removed×4 unchanged×4
C-EN*: L38  Should have: unchanged(a), removed(b), added(c)
TESTS-EN: `empty inputs produce empty diff` | `all added when before is empty` | `all removed when after is empty` | `identical lines are unchanged` | `added and removed lines detected`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/DiffHelpersTest.kt","loc":43,"lang":"英文","zh":0,"en":1,"kdoc":0,"tests":5,"cls":"DiffHelpersTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/TaskOutputFetchTest.kt
LOC 114 | lang 中文 (zh 5/en 0, kdoc 1) | @Test 8 | btFuns 0 | TaskOutputFetchTest
LEX: session,message,part,turn,tool,agent,subagent,sse,task,chunk,render
FREQ: output×27 fetch×17 json×16 child×12 equals×11 longer×10 primitive×8 state×7 transcript×7 user×7 assistant×7 content×7 info×6 pick×6
C-ZH: L17 [kdoc] #182：Task 卡片全量输出拉取纯函数（part 优先 → 子会话 transcript 回退）。 | L61 [line]  无文本 part → 跳过 | L63 [line]  空 → 跳过 | L100 [line]  #180 契约：metadata.childID 与 sessionId/sessionID/jobId 同等可读 | L101 [line]  （V2Mappers 归一 + 卡片直读双保险）。此处验证 JSON 形状解析。
ENSTR*: L60 "start the task" | L66 "[user]\nstart the task\n\n[assistant]\ndid the work"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/TaskOutputFetchTest.kt","loc":114,"lang":"中文","zh":5,"en":0,"kdoc":1,"tests":8,"cls":"TaskOutputFetchTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolSnapshotGrouperTest.kt
LOC 203 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 14 | btFuns 14 | ToolSnapshotGrouperTest,Main
LEX: session,message,part,turn,tool,sse,snapshot,diff,fallback
FREQ: groups×38 file×32 path×32 equals×30 make×21 edit×21 json×18 grouper×17 group×17 user×13 state×12 input×11 cumulative×10 primitive×9
TESTS-EN: `empty list returns empty groups` | `single Read tool produces single group with count 1` | `three adjacent Edits same file produce single group` | `Bash tool between two Edits same file does not break grouping` | `two different files produce two groups preserving first-occurrence order` | `Write and Edit on same file in same message produce single group` | `path normalization treats backslash and forward slash as same file` | `same file across different messages produces two groups` | `cumulativeBefore is first part before cumulativeAfter is last part after` | `cumulativeBefore is empty for Write-only group` | `cumulativeBefore falls back to oldString when metadata missing` | `non Read Write Edit tools are ignored` | `Running state tool is still grouped` | `normalizePath helper trims trailing slash and converts backslash`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolSnapshotGrouperTest.kt","loc":203,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":14,"cls":"ToolSnapshotGrouperTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/AttachmentValidationTest.kt
LOC 95 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 11 | btFuns 11 | AttachmentValidationTest
LEX: config,stream,sse
FREQ: attachment×25 local×22 accepted×14 equals×12 validation×12 validate×11 application×8 limit×6 rejected×4 file×4 boundary×4 within×3 document×3 large×3
TESTS-EN: `image accepted within size limit` | `pdf accepted within document limit` | `oversized pdf rejected as too large` | `text file accepted within text limit` | `text file by extension accepted even with generic mime` | `text file rejected when exceeding text limit` | `executable file rejected as unsupported` | `unknown mime without recognized extension rejected` | `json accepted as text via application mime` | `document limit boundary accepted` | `text limit boundary accepted`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/AttachmentValidationTest.kt","loc":95,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":11,"cls":"AttachmentValidationTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/DirectoryManagerServerPathsTest.kt
LOC 88 | lang 中文 (zh 8/en 0, kdoc 3) | @Test 3 | btFuns 3 | DirectoryManagerServerPathsTest
LEX: session,directory,sse,retry
FREQ: server×25 paths×24 case×19 manager×12 home×11 failure×8 cooldown×7 domain×6 equals×6 usecase×4 relaxed×4 opencode×4 repository×3 fail×3
C-ZH: L18 [kdoc] #134（D2-L38）：getServerPaths 失败不得毒化缓存。 | L19 [kdoc] 原实现失败也缓存空 ServerPaths() 且永不失效——一次瞬时网络失败 | L20 [kdoc] 导致整个 VM 生命周期 home/cwd 全空；现失败不缓存，冷却后自动重试。 | L45 [line]  第一次失败：返回空路径，且不缓存 | L48 [line]  冷却期内（失败时间戳刚记录）不触发网络请求 | L51 [line]  网络恢复 + 冷却期外：自动重试成功 | L53 [line]  推进失败时间戳到冷却期外（模拟真实时间流逝） | L60 [line]  成功后缓存命中：不再触发请求
ZHSTR: L68 "冷却期内不得重试" | L69 "差 1ms 仍在冷却期" | L70 "到达冷却期可重试" | L71 "远超冷却期可重试" | L86 "成功结果只请求一次（缓存命中）"
TESTS-EN: `transient failure is not cached - cooldown then retry succeeds` | `cooldown decision is pure and time-based` | `successful result is cached`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/DirectoryManagerServerPathsTest.kt","loc":88,"lang":"中文","zh":8,"en":0,"kdoc":3,"tests":3,"cls":"DirectoryManagerServerPathsTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/home/HomeViewModelCancelConnectionTest.kt
LOC 121 | lang 中文 (zh 9/en 0, kdoc 6) | @Test 2 | btFuns 0 | HomeViewModelCancelConnectionTest
LEX: turn,provider,config,sse,idle,patch
FREQ: server×29 case×13 settings×11 coroutines×11 flow×10 connecting×10 kotlinx×9 health×9 state×9 model×8 repository×8 check×8 connection×7 domain×6
C-ZH: L31 [kdoc] 回归测试：连接进行中（健康检查阶段）点击取消必须立即生效。 | L33 [kdoc] 旧行为：connectToServer 乐观添加 connectingServerIds 后，在 | L34 [kdoc] testConnection 期间点取消只移除 connectedServerIds（本就不包含）， | L35 [kdoc] connectingServerIds 残留 → UI 一直显示 Connecting，直到健康检查 | L36 [kdoc] 超时失败才复位（"Server is not responding"）。且取消后健康检查 | L37 [kdoc] 协程仍会继续，若检查通过会再次启动服务连接。 | L94 [line]  健康检查尚未完成时点击取消 —— 状态必须立即复位 | L98 [line]  健康检查最终失败 —— 不得再产生任何状态变化（无错误提示） | L114 [line]  健康检查通过 —— 取消后也必须保持未连接、无错误
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/home/HomeViewModelCancelConnectionTest.kt","loc":121,"lang":"中文","zh":9,"en":0,"kdoc":6,"tests":2,"cls":"HomeViewModelCancelConnectionTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ChatModifiersTest.kt
LOC 85 | lang 中文 (zh 6/en 0, kdoc 3) | @Test 8 | btFuns 8 | ChatModifiersTest
LEX: turn,sse
FREQ: post×21 available×20 scroll×17 bottom×15 offset×10 velocity×10 decision×10 zero×10 fling×10 equals×9 scrolling×4 consumes×4 boundary×3 nested×3
C-ZH: L9 [kdoc] consumeBoundaryScroll 的 NestedScrollConnection 逻辑单元测试。 | L11 [kdoc] 由于 NestedScrollConnection 是在 @Composable 函数内部创建的， | L12 [kdoc] 我们通过将决策逻辑提取为可测试的顶层函数来直接测试边界条件。 | L16 [line]  ----- onPostScroll 逻辑 ----- | L48 [line]  ----- onPostFling 逻辑（与 onPostScroll 对应）----- | L68 [line]  ----- 镜像 NestedScrollConnection 逻辑的辅助函数 -----
TESTS-EN: `onPostScroll at top scrolling up consumes available` | `onPostScroll at bottom scrolling down consumes available` | `onPostScroll not at boundary returns zero` | `onPostScroll at top scrolling down returns zero` | `onPostScroll at bottom scrolling up returns zero` | `onPostFling at top fling up consumes velocity` | `onPostFling at bottom fling down consumes velocity` | `onPostFling not at boundary returns zero`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ChatModifiersTest.kt","loc":85,"lang":"中文","zh":6,"en":0,"kdoc":3,"tests":8,"cls":"ChatModifiersTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/TurnGroupCalculatorTest.kt
LOC 113 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 8 | btFuns 8 | TurnGroupCalculatorTest
LEX: session,message,part,turn,sse
FREQ: msgs×26 equals×20 assistant×20 user×11 synthetic×10 chat×8 compute×8 groups×8 time×7 model×4 info×4 created×3
C-ZH: L88 [line]  2026-08-12 用户决策：synthetic 独立气泡——不并入 assistant turn， | L89 [line]  也不合并两侧 assistant（turn = 连续 assistant 序列） | L99 [line]  2026-08-12 用户决策：synthetic 不再并入 assistant turn | L108 [line]  前后都无 assistant → 独立条目（不并入任何 turn）
TESTS-EN: `empty messages returns empty map` | `single assistant message returns one group` | `three consecutive assistants grouped as one turn` | `mixed user and assistant correct grouping` | `only user messages returns empty map` | `synthetic between assistants splits turns` | `synthetic after assistant stays independent` | `isolated synthetic stays independent`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/TurnGroupCalculatorTest.kt","loc":113,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":8,"cls":"TurnGroupCalculatorTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParserTest.kt
LOC 195 | lang 英文 (zh 0/en 4, kdoc 0) | @Test 16 | btFuns 16 | QuestionParserTest
LEX: turn,tool,question,sse,fallback
FREQ: json×29 build×21 equals×21 parser×17 multiple×17 answers×13 input×13 content×11 pick×8 options×8 output×8 format×7 continue×7 display×7
C-EN*: L11  ===== parseQuestionContent ===== | L67  ===== parseQuestionFromToolData ===== | L130  ===== parseQuestionContent multiple ===== | L151  ===== parseQuestionFromToolData multiple =====
ENSTR*: L15 """Asked 3 questions. questions: [{"question":"Pick a language"}]
            |U | L53 "Just a simple question" | L146 """Asked 1 question. questions: [{"question":"Pick"}]
            |User has answ | L189 """questions: [{"question":"Pick many","multiple":true,"options":[{"label":"A"}]
TESTS-EN: `opencode text format - extracts question field and quoted answers` | `opencode text format - plain answer without quotes` | `JSON format - single answer field` | `JSON format - answers array` | `plain text fallback - no markers` | `blank input returns raw as displayText` | `structured input extracts questions and options` | `answer pairs mapped from output` | `fallback plain answer after equals` | `empty input and output returns single blank item` | `JSON format - parses multiple true` | `JSON format - multiple absent defaults false` | `opencode text format - multiple defaults false` | `tool data - parses multiple from question json` | (+2 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParserTest.kt","loc":195,"lang":"英文","zh":0,"en":4,"kdoc":0,"tests":16,"cls":"QuestionParserTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/JumpTargetExtractorTest.kt
LOC 166 | lang 混合 (zh 9/en 5, kdoc 0) | @Test 10 | btFuns 10 | JumpTargetExtractorTest
LEX: session,message,part,turn,sse,fallback,jump
FREQ: user×51 targets×28 equals×21 msgs×20 nearest×17 find×16 created×16 time×15 info×13 extract×10 summary×10 assistant×9 role×8 chat×6
C-ZH: L14 [line]  ---- ChatMessage 辅助（findNearestUserIndexBefore 测试用） ---- | L37 [line]  ---- extractJumpTargets(MessageWithParts)（Room 全量数据源） ---- | L51 [line]  Room userMessages 返回降序（created DESC），验证内部升序排列 | L67 [line]  SQL 层已 role='user' 过滤；此处验证纯函数双保险 | L79 [line]  2026-08-12 空壳修复：服务器历史遗留/已删除消息（Room 有记录但无 parts | L80 [line]  且无 summary.body）——直接跳过，不显示 "(无文本)" 占位 | L91 [line]  无 Part.Text 但有 summary.body（Room payload 的 User 消息摘要）→ 回退 | L109 [line]  过滤空壳后编号连续（Q1、Q2……不跳号） | L129 [line]  ---- findNearestUserIndexBefore（不变） ----
   (+5 trivial en comments)
ZHSTR: L85 "(无文本)" | L98 "从 summary 提取的文本"
TESTS-EN: `extractJumpTargets returns user messages sorted ascending with sequential labels` | `extractJumpTargets excludes synthetic as double insurance` | `skips shell user messages with no text and no summary` | `uses summary body as preview fallback` | `labels stay sequential after filtering shells` | `extractJumpTargets empty for no user messages` | `findNearestUserIndexBefore returns self when input is user` | `findNearestUserIndexBefore walks back to nearest user` | `findNearestUserIndexBefore null when no user at or before` | `findNearestUserIndexBefore null for out of bounds`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/JumpTargetExtractorTest.kt","loc":166,"lang":"混合","zh":9,"en":5,"kdoc":0,"tests":10,"cls":"JumpTargetExtractorTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ContextStatsTest.kt
LOC 120 | lang 混合 (zh 8/en 3, kdoc 0) | @Test 9 | btFuns 9 | ContextStatsTest
LEX: session,message,part,turn,token,tool,sse,diff,context
FREQ: user×17 input×14 msgs×12 breakdown×11 role×11 cache×11 equals×9 assistant×8 real×8 estimate×7 segments×7 percent×6 rate×6 domain×5
C-ZH: L43 [line]  用户文本 40 字符 -> 10 tokens | L52 [line]  10 tokens 用户 | L63 [line]  other = max(0, 100 - 1000) = 0 -> 被过滤掉 | L77 [line]  user 4000 字符=1000 tok, assistant 3640 字符=910 tok, tool 29200 字符=7300 tok | L78 [line]  estimated = 9210, realInput = 1958 -> 分母 = estimated = 9210 | L85 [line]  没有 other 分段（estimated > input） | L88 [line]  每个分段 ≤ 1.0 | L93 [line]  所有分段之和约为 ~1.0（100%）
C-EN*: L60  1000 tokens
   (+2 trivial en comments)
TESTS-EN: `breakdown estimates tokens as chars divided by 4` | `other absorbs difference from real input` | `other is zero when estimate exceeds input` | `percent is tokens over real input` | `percents normalized when estimate exceeds input` | `countMessages splits user and assistant` | `cacheHitRate is cacheRead over total reads` | `cacheHitRate returns 0 when no cache` | `cacheHitRate returns null when both zero`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/ContextStatsTest.kt","loc":120,"lang":"混合","zh":8,"en":3,"kdoc":0,"tests":9,"cls":"ContextStatsTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/PatchVisibilityResolverTest.kt
LOC 223 | lang 中文 (zh 14/en 0, kdoc 5) | @Test 12 | btFuns 12 | PatchVisibilityResolverTest
LEX: session,message,part,turn,sse,patch,dedup
FREQ: hash×49 assistant×29 filter×22 equals×20 repeated×17 suppress×13 hashes×13 time×11 chat×10 user×7 info×6 model×5 visible×5 large×5
C-ZH: L12 [kdoc] [suppressRepeatedPatchHashes] 的测试。 | L14 [kdoc] 覆盖矩阵： | L15 [kdoc] - 基本去重：跨消息相同 hash、hash 变化、非 patch 重置、空 hash | L16 [kdoc] - 边界：空列表、单条消息、仅用户、消息内去重 | L17 [kdoc] - 极端/边界：空白修剪、混合序列、大 hash、空白/非空白交替 | L79 [line]  ── 边界情况 ────────────────────────────────────────────── | L134 [line]  ── 极端/边界情况 ──────────────────────────────────────── | L150 [line]  用户消息保持不变 | L152 [line]  第一个 assistant patch 保留，第二个被去重 | L159 [line]  1KB 的 hash | L173 [line]  blank → non-blank(X) → blank → non-blank(X)：blank 始终可见，X 被去重 | L188 [line]  空白 patch 始终可见 | L191 [line]  X1 保留，X2 被去重（中间的空白不会重置） | L196 [line]  ── 辅助函数 ─────────────────────────────────────────────────────
ENSTR*: L31 "second patch should be suppressed" | L63 "repeated patch after text should still be suppressed" | L117 "first patch kept, second deduped within same message" | L168 "large hash should still dedup correctly"
TESTS-EN: `hides repeated non-blank hash across assistant messages` | `keeps patch when hash changes` | `does not reset dedup state for non-patch assistant parts` | `keeps blank hash patch visible` | `empty messages list returns empty` | `single assistant message with patch unchanged` | `user only messages pass through unchanged` | `multiple patches same hash in single message are deduplicated` | `hash with surrounding whitespace is trimmed for comparison` | `mixed user assistant sequence dedups only in assistant messages` | `large hash value handled correctly` | `blank and non-blank hash alternating keeps blank patches visible`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/PatchVisibilityResolverTest.kt","loc":223,"lang":"中文","zh":14,"en":0,"kdoc":5,"tests":12,"cls":"PatchVisibilityResolverTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/RecentSessionDirectoriesTest.kt
LOC 70 | lang 中文 (zh 10/en 0, kdoc 3) | @Test 4 | btFuns 4 | RecentSessionDirectoriesTest
LEX: session,directory,sse
FREQ: equals×11 directories×8 recent×7 proj×7 limit×5 updated×4 home×4
C-ZH: L9 [kdoc] NewSessionQuickDialog 的 recentSessionDirectories 测试。 | L10 [kdoc] 回归：V2 服务器存在 location.directory 为空的会话（实测 ses_005890631ffe...）—— | L11 [kdoc] 空目录会产生"空目录"条目（2026-08-13 用户反馈）。 | L26 [line]  空 directory | L28 [line]  空白字符串 | L31 [line]  只有 proj-a 一组（2 个会话），空/空白目录不产生条目 | L40 [line]  回归：V2 服务器实测会话 ses_005890631ffe... 的 location.directory 为 "/"（根目录）—— | L41 [line]  trimEnd('/') 后为空 → 旧代码分组 key="" → "空目录"条目（用户反馈） | L44 [line]  根目录（V2 服务器数据） | L59 [line]  directory 保留首条原始值（点击新建会话用），分组 key 是 trim 后的 /a/b
TESTS-EN: `empty directory sessions are filtered out` | `root directory sessions are filtered out` | `normal directories grouped by trimmed path` | `respects limit`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/RecentSessionDirectoriesTest.kt","loc":70,"lang":"中文","zh":10,"en":0,"kdoc":3,"tests":4,"cls":"RecentSessionDirectoriesTest"}





═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt
LOC 74 | lang 中文 (zh 1/en 0, kdoc 0) | @Test 3 | btFuns 3 | SessionListPendingQuestionTest
LEX: session,message,event,directory,draft,question,sse,idle,pending
FREQ: status×14 state×14 node×7 server×6 asking×6 domain×5 base×5 model×4 coroutines×4 repo×4 time×4 content×4 repository×3 build×3
C-ZH: L57 [line]  2026-08-14：提问中并入状态枚举（替代 hasPendingQuestion 独立标记）
TESTS-EN: `session with pending question gets Asking status` | `no pending questions leaves statuses idle` | `pending ids from other server ignored`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt","loc":74,"lang":"中文","zh":1,"en":0,"kdoc":0,"tests":3,"cls":"SessionListPendingQuestionTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/TreeNodeTest.kt
LOC 162 | lang 中文 (zh 11/en 0, kdoc 0) | @Test 10 | btFuns 10 | TreeNodeTest
LEX: session,part,turn,directory,sse,diff
FREQ: nodes×26 tree×25 make×17 home×17 equals×16 node×15 user×15 dirs×12 filter×12 build×11 proj×11 path×7 root×7 project×6
C-ZH: L33 [line]  应有 /home、/home/user、/home/user/project-a 的目录节点 | L34 [line]  未展开任何节点，因此只有根层级 | L39 [line]  没有会话节点，因为未展开任何节点 | L51 [line]  展开所有路径 | L68 [line]  只展开 /a，不展开 /a/b | L73 [line]  只有 s1（在 /a 中），s2 因 /a/b 未展开而不可见 | L77 [line]  ============ baseDirectory==null 按完整目录路径分组（不再项目感知聚合） ============ | L90 [line]  目录节点 path 为完整目录路径 | L128 [line]  反斜杠与正斜杠归一化后应归入同一目录组 | L144 [line]  即使 basename 相同（app），不同父目录也应是独立分组 | L154 [line]  "/" 规范化后为空 → 根会话（不产生 Directory 节点）
TESTS-EN: `empty sessions returns empty list` | `single directory with sessions - collapsed` | `single directory with sessions - expanded` | `partial expand shows only expanded children` | `distinct directories produce one directory node each` | `directory displayName uses basename not full path` | `sessions with empty directory stay at root` | `windows backslash paths are normalized into one group` | `same basename different parents produce distinct nodes` | `root path session stays at root not grouped`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/TreeNodeTest.kt","loc":162,"lang":"中文","zh":11,"en":0,"kdoc":0,"tests":10,"cls":"TreeNodeTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelSearchTest.kt
LOC 121 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | SessionListViewModelSearchTest
LEX: session,message,turn,directory,draft,unread,sse,pending,merge,badge
FREQ: case×42 repository×31 flow×18 state×17 domain×16 search×15 query×12 relaxed×10 server×9 usecase×9 create×8 model×7 coroutines×7 view×6
TESTS-EN: `searchQuery state is initially empty` | `setSearchQuery updates the query state` | `clearSearchQuery resets to null`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelSearchTest.kt","loc":121,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"SessionListViewModelSearchTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelPaginationTest.kt
LOC 121 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 3 | SessionListViewModelPaginationTest
LEX: session,message,turn,directory,draft,unread,sse,cursor,paginat,page,pending,merge,badge
FREQ: case×42 repository×31 flow×18 domain×16 state×13 relaxed×10 server×9 usecase×9 create×9 model×8 coroutines×7 view×7 service×6 file×5
TESTS-EN: `hasMorePages is initially true` | `isLoadingMore is initially false` | `resetPagination clears cursor state`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelPaginationTest.kt","loc":121,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"SessionListViewModelPaginationTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt
LOC 173 | lang 中文 (zh 15/en 0, kdoc 6) | @Test 3 | btFuns 3 | SessionListShellStateTest
LEX: session,message,event,turn,directory,draft,question,unread,sse,patch,pending,merge,badge
FREQ: repository×45 case×44 state×41 flow×28 domain×20 mutable×12 model×11 settings×11 coroutines×11 shell×11 content×11 relaxed×10 server×9 usecase×9
C-ZH: L67 [line]  contentState 输入流：用 MutableStateFlow（发射初始值）而非 emptyFlow（永不发射）， | L68 [line]  否则 combine 因有空源永不产生值——测试将永远 pass 但无护栏意义。 | L79 [line]  loadSessions/refreshSessions 走成功路径，不写 _error（保持 shellState 初始 error=null） | L82 [line]  让 viewModelScope 协程可执行（UnconfinedTestDispatcher：launch 同步执行） | L97 [line]  loadSessions 成功路径不写 _error；_isRefreshing 始终为 false | L109 [line]  空数据下 buildContentState 产出空列表、无选中、无搜索 | L118 [kdoc] #23 核心收益护栏：外壳状态翻转（_isRefreshing/_error）不应触发 contentState 重算。 | L120 [kdoc] contentState 输入流 = combine(dataFlow, uiFlow)，源为 sessions/statuses/expandedPaths 等， | L121 [kdoc] 完全不含 _isLoading/_isRefreshing/_error。refreshSessions 写后三者（shellState 源）， | L122 [kdoc] 因此 contentState 不应发射新帧——若泄漏则说明切片边界被破坏。 | L124 [kdoc] 驱动路径：refreshSessions 成功执行写 _isRefreshing=true→false（全程在 shellState 输入流）。 | L125 [kdoc] UnconfinedTestDispatcher 下 launch 同步执行，refreshSessions 返回时 shell 翻转已完成。 | L131 [line]  消费首帧（stateIn 当前值，上游 combine 已稳定） | L132 [line]  驱动 shell 翻转：refreshSessions 写 _isRefreshing（shellState 源），不触碰 contentState 输入流 | L134 [line]  核心断言：shell 字段翻转不应触发 content 重算
TESTS-ZH: `shellState 暴露默认外壳字段` | `contentState 暴露默认内容字段` | `shellState 翻转不触发 contentState 重发`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt","loc":173,"lang":"中文","zh":15,"en":0,"kdoc":6,"tests":3,"cls":"SessionListShellStateTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/util/SessionGroupingTest.kt
LOC 139 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 8 | btFuns 0 | SessionGroupingTest
LEX: session,directory,sse,fallback
FREQ: project×31 groups×24 repo×17 updated×16 equals×14 group×11 projects×8 worktree×8 build×8 single×5 mobile×5 home×4 apps×3 beta×3
C-ZH: L32 [line]  会话位于嵌套 worktree 下但不携带 projectId —— 必须 | L33 [line]  回退到最长匹配前缀。 | L55 [line]  按最近活动排序：/two（updated=2）在 /one（updated=1）之前。 | L65 [line]  与 /beta 活动相同 -> 按名称决胜
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/util/SessionGroupingTest.kt","loc":139,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":8,"cls":"SessionGroupingTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt
LOC 281 | lang 中文 (zh 13/en 0, kdoc 2) | @Test 18 | btFuns 18 | SessionListUnreadTest
LEX: session,message,event,turn,directory,draft,unread,sse,retry,busy,idle,patch,merge,badge
FREQ: status×39 state×27 server×20 filter×17 time×15 category×15 service×14 build×14 repository×12 tree×12 assignments×11 favorites×10 nodes×10 coroutines×9
C-ZH: L25 [kdoc]  未读判定纯函数 + 红点模块已读合并链路测试（#171 迁移后）。 | L55 [kdoc]  模块真实链路：水位线事件 → markSessionRead（读水位线）→ 合并读（内存信号压过旧持久值）。 | L67 [line]  持久化还是旧值（DataStore 写入未完成），内存信号取水位线新值 → 不未读 | L83 [line]  未在信号中的会话不受影响 | L89 [line]  allReadAt 覆盖所有旧回复 | L92 [line]  allReadAt 之后的新回复仍产生未读 | L116 [line]  等价值 fixtures（现有文件无 sessions/SERVER_ID/draftRepository，按 brief 授权自包含构造） | L157 [line]  --- #23 过滤负向用例 fixtures（自包含，扩展自上方 buildContentState 用例）--- | L210 [line]  会话未分配 FAVORITE_TAG_ID → favoritesOnly=true 时被剔除 | L221 [line]  会话分配 FAVORITE_TAG_ID → favoritesOnly=true 时保留 | L232 [line]  t1/t2 分属两个会话：同时筛选 t1+t2 无人满足（AND）；只筛选 t1 命中 1 个 | L263 [line]  会话目录 D:/a/b，baseDirectory=D:/x 前缀不匹配 → 空；D:/a 匹配 → 1 | L273 [line]  s1 不在 serverSessionMap[serverId] 中 → 会话被剔除
ZHSTR: L255 "不存在的关键词"
TESTS-ZH: `buildContentState 保持未读判定与过滤语义` | `favoritesOnly 过滤未收藏会话` | `favoritesOnly 保留收藏会话` | `categoryFilterIds AND 过滤 需同时匹配全部 tag` | `searchQuery 匹配目录关键词` | `baseDirectory 前缀不匹配过滤会话` | `serverSessionMap 剔除未映射会话`
TESTS-EN: `unread when last message time after read time` | `not unread when no message recorded` | `not unread when message time equals read time` | `not unread when message time before read time` | `unread when no read time recorded` | `markSessionRead in-memory signal suppresses unread over stale persisted` | `in-memory signal without persisted entry also works` | `mark all read suppresses all sessions` | `busy session never unread even with newer completed` | `idle status required for unread` | `allReadAt gating works with status`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt","loc":281,"lang":"中文","zh":13,"en":0,"kdoc":2,"tests":18,"cls":"SessionListUnreadTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/AnnotationManagerTest.kt
LOC 135 | lang 英文 (zh 0/en 2, kdoc 0) | @Test 12 | btFuns 12 | AnnotationManagerTest,MainActivity,MainActivity,MainActivity
LEX: turn,sse,annotation
FREQ: manager×44 equals×16 content×12 start×10 line×10 bundle×8 activity×8 delete×8 remaining×8 android×7 note×7 updated×5 view×3 gets×3
   (+2 trivial en comments)
TESTS-EN: `add first annotation gets index 0` | `add second annotation gets index 1` | `delete middle annotation re-numbers remaining consecutively` | `delete last annotation does not re-number others` | `delete only annotation results in empty list` | `add after delete gets correct index` | `update changes note only` | `getForLine returns intersecting annotations` | `getForLine returns empty for non-intersecting` | `clear removes all` | `add computes correct line col from offsets` | `overlapping annotations both returned by getForLine`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/AnnotationManagerTest.kt","loc":135,"lang":"英文","zh":0,"en":2,"kdoc":0,"tests":12,"cls":"AnnotationManagerTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileTypeTest.kt
LOC 90 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 17 | btFuns 17 | FileTypeTest
LEX: config,sse,page,render
FREQ: file×46 extension×25 equals×16 supports×16 html×13 maps×12 markdown×8 image×8 json×6 source×6 view×6
TESTS-EN: `md extension maps to MARKDOWN` | `markdown extension maps to MARKDOWN` | `uppercase PNG maps to IMAGE` | `all image extensions map to IMAGE` | `svg extension maps to SVG` | `csv and tsv map to CSV` | `json extension maps to JSON` | `kt extension maps to TEXT` | `unknown extension maps to TEXT` | `no extension maps to TEXT` | `supportsRender is false for TEXT and JSON` | `html extension maps to HTML` | `htm extension maps to HTML` | `uppercase HTML maps to HTML` | (+3 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileTypeTest.kt","loc":90,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":17,"cls":"FileTypeTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileViewerViewModelTest.kt
LOC 733 | lang 中文 (zh 36/en 0, kdoc 1) | @Test 38 | btFuns 38 | FileViewerViewModelTest,MainActivity,Main,Main
LEX: session,part,turn,tool,directory,config,sse,paginat,idle,snapshot,diff,patch,task,annotation,workspace,render
FREQ: file×222 content×150 state×119 mode×77 viewer×74 path×67 source×56 params×44 success×42 create×41 line×30 toggle×28 model×24 equals×23
C-ZH: L33 [line]  每个测试在 @Before 重建：StandardTestDispatcher 的 scheduler 与 ToolSnapshotCache | L34 [line]  作为类字段共享时，上一测试残留的 viewModelScope 协程/缓存会泄漏到下一测试（flaky 根因）。 | L60 [kdoc]  构造 ViewModel 并 drain init 加载协程，消除构造后立即读 uiState 的调度时序 flaky。 | L67 [line]  --- 真实测试数据（D7-003）--- | L134 [line]  全新 dispatcher（空 scheduler，无跨测试协程残留）+ 全新缓存（无跨测试数据污染） | L143 [line]  #115（D2-L23）：清进程级批注暂存——静态状态跨测试残留会污染断言 | L147 [line]  1. LIVE 来源成功加载内容 | L161 [line]  2. GIT_DIFF 来源成功解析 hunks | L176 [line]  3. TOOL_SNAPSHOT 来源无缓存时设置缺失错误 | L193 [line]  4. 加载失败设置错误 | L209 [line]  5. 二进制文件设置 isBinary + mimeType | L223 [line]  6. 空内容设置 isEmpty | L241 [line]  7. 空 patch 设置 hunks 为空 | L263 [line]  8. nextHunk 在最后一个索引处钳制 | L271 [line]  导航到最后一个 hunk | L276 [line]  再次 nextHunk 应停留在最后一个 | L283 [line]  9. prevHunk 在 0 处钳制 | L290 [line]  currentHunkIndex 从 0 开始 | L294 [line]  prevHunk 应停留在 0 | L301 [line]  ===== Phase 2：Markdown 切换测试 ===== | L303 [line]  10. 使用 md 文件初始化设置 isMarkdown 为 true | L314 [line]  11. 使用 kt 文件初始化设置 isMarkdown 为 false | (+14 more)
ENSTR*: L95 """
        @@ -10,6 +10,8 @@
         import dagger.hilt.android.lifecycle.Hilt | L170 "mode should be DIFF" | L171 "hunks should be parsed from patch" | L173 "diff should be set" | L184 "error should be set for missing snapshot" | L186 "error should be tool snapshot missing resource, was: ${state.error}" | L259 "hunks should be empty for empty patch" | L311 "renderMode should default to RENDER_PREVIEW for renderable types" | (+13 more)
TESTS-EN: `LIVE source success loads content` | `GIT_DIFF source success parses hunks` | `TOOL_SNAPSHOT source without cache sets missing error` | `load failure sets error` | `binary file sets isBinary and mimeType` | `empty content sets isEmpty` | `empty patch sets hunks empty` | `nextHunk clamps at last index` | `prevHunk clamps at 0` | `init with md file sets isMarkdown true and defaults to RENDER_PREVIEW` | `init with kt file sets isMarkdown false` | `toggleRenderMode switches RENDER_PREVIEW to SOURCE for markdown files` | `toggleRenderMode is no-op for TEXT files` | `toggleRenderMode is no-op in DIFF mode` | (+24 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileViewerViewModelTest.kt","loc":733,"lang":"中文","zh":36,"en":0,"kdoc":1,"tests":38,"cls":"FileViewerViewModelTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilderTest.kt
LOC 117 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 14 | btFuns 14 | RenderHtmlBuilderTest
LEX: message,sse,render
FREQ: html×58 light×32 dark×22 build×20 file×19 contain×16 builder×15 json×12 table×9 produces×7 content×4 error×4 alice×3 width×3
TESTS-EN: `CSV build produces table with header row` | `CSV with TSV uses tab delimiter` | `CSV handles quoted fields with commas` | `CSV empty content produces empty table` | `JSON build produces pretty-printed pre` | `JSON array produces pretty-printed pre` | `JSON invalid produces error message` | `SVG build embeds svg content directly` | `dark theme produces dark background CSS` | `light theme produces light background CSS` | `all HTML contains viewport meta tag` | `CSV build wraps table in scrollable container` | `CSV build adds dynamic cell cap css based on column count` | `CSV build enables word breaking in cells`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilderTest.kt","loc":117,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":14,"cls":"RenderHtmlBuilderTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffParserTest.kt
LOC 172 | lang 中文 (zh 13/en 0, kdoc 0) | @Test 8 | btFuns 0 | DiffParserTest,MainActivity,RepositoryImpl
LEX: session,message,part,turn,tool,sse,diff,patch,context
FREQ: hunks×29 equals×21 parser×15 line×13 repository×10 modified×9 unified×8 start×8 added×7 view×6 model×6 flow×6 state×6 trim×5
C-ZH: L11 [line]  1. 空补丁返回空列表 | L18 [line]  2. 单个 hunk 解析正确 | L37 [line]  3. 多个 hunk 解析 | L61 [line]  4. 真实项目 git diff 样本 | L103 [line]  第一个 hunk：仅有新增 → ADDED | L106 [line]  第二个 hunk：同时有新增和删除 → MODIFIED | L109 [line]  第三个 hunk：同时有新增和删除 → MODIFIED | L114 [line]  5. 无 @@ 的畸形补丁返回空 | L128 [line]  6. 二进制 diff 行返回空 | L136 [line]  7. 混合增删的 hunk → MODIFIED（D4-004 关键测试） | L151 [line]  D4-004：同时包含新增与删除行 → MODIFIED，而非 ADDED 或 REMOVED | L155 [line]  8. CRLF 补丁解析正确 | L158 [line]  模拟 CRLF 行尾
ENSTR*: L40 """
            @@ -1,3 +1,3 @@
             package com.example
            -im | L64 """
            diff --git a/app/src/main/java/com/app/SessionManager.kt b/app/s | L131 "Binary files a/image.png and b/image.png differ" | L139 """
            @@ -25,5 +25,6 @@
                 private val context: Context, | L161 " import kotlinx.coroutines.Dispatchers\r"
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffParserTest.kt","loc":172,"lang":"中文","zh":13,"en":0,"kdoc":0,"tests":8,"cls":"DiffParserTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/HighlightBuilderTest.kt
LOC 76 | lang 英文 (zh 0/en 2, kdoc 0) | @Test 8 | btFuns 8 | HighlightBuilderTest
LEX: turn,sse
FREQ: language×36 syntax×19 highlight×19 builder×19 remember×16 equals×15 highlights×8 build×8 python×3 dark×3
   (+2 trivial en comments)
TESTS-EN: `kotlin extensions` | `major language extensions` | `uppercase extension normalised` | `unknown or missing extension returns DEFAULT` | `full path with directories` | `buildHighlights returns non-null result for kotlin code` | `buildHighlights works with light theme` | `buildHighlights handles empty content`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/HighlightBuilderTest.kt","loc":76,"lang":"英文","zh":0,"en":2,"kdoc":0,"tests":8,"cls":"HighlightBuilderTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/WorkspaceViewModelTest.kt
LOC 457 | lang 中文 (zh 43/en 0, kdoc 0) | @Test 21 | btFuns 21 | WorkspaceViewModelTest
LEX: turn,directory,config,sse,idle,patch,workspace
FREQ: state×67 server×57 status×55 file×51 success×48 files×44 search×43 root×36 find×33 changes×33 nodes×27 model×26 load×25 saved×24
C-ZH: L52 [line]  --- 真实测试数据（D7-003）--- | L80 [line]  ===== 测试 1：init 触发根加载 + git 预取 ===== | L92 [line]  ===== 测试 2：loadDirectory 成功 ===== | L104 [line]  目录优先，然后文件按名称小写排序 | L109 [line]  ===== 测试 3：loadDirectory 缓存命中 ===== | L117 [line]  第二次调用 —— 应命中缓存 | L122 [line]  ===== 测试 4：loadDirectory 失败设置 rootError ===== | L139 [line]  ===== 测试 5：refreshRoot 清除缓存并重新加载 ===== | L149 [line]  init + refresh = 2 次调用 | L153 [line]  ===== 测试 6：未加载时 switchPanel GIT 触发 getStatus ===== | L156 [line]  预取成功但 gitChanges 列表为空（预取只设置计数） | L162 [line]  预取设置 gitChangeCount = 0，但 switchPanel 检查 gitChanges.isEmpty() | L165 [line]  init 预取 + switchPanel loadGitChanges = 2 次调用 | L169 [line]  ===== 测试 7：非 git 的 switchPanel GIT 设置 isNonGit ===== | L173 [line]  预取因非 git 消息而失败 | L180 [line]  由于 gitChanges 为空且未在加载，switchPanel 触发 loadGitChanges | L187 [line]  ===== 测试 8：switchPanel FILE_TREE 不重新加载 ===== | L195 [line]  切换到 GIT 再切回 FILE_TREE | L199 [line]  listDirectory 仍应只被调用一次（init 调用） | L203 [line]  ===== 测试 9：toggleShowIgnored ===== | L220 [line]  ===== 测试 10：git 预取失败使计数保持 null ===== | L236 [line]  ===== 测试 11：refreshRoot 期间 loadDirectory 取消过期任务 ===== | (+21 more)
ENSTR*: L106 "First node should be directory 'src', was '${names.first()}'" | L321 "rootError should be server config missing resource, was '${state.rootError}'" | L340 "isSearchMode should be true" | L356 "isSearchMode should be false" | L359 "hasSearched should be false" | L373 "hasSearched should be false for blank query" | L404 "hasSearched should be true"
TESTS-EN: `init triggers root load + git prefetch` | `loadDirectory success` | `loadDirectory cache hit same path twice equals one API call` | `loadDirectory failure sets rootError` | `refreshRoot clears cache and reloads` | `switchPanel GIT triggers getStatus if not loaded` | `switchPanel GIT non-git sets isNonGit` | `switchPanel FILE_TREE no reload` | `toggleShowIgnored` | `git prefetch failure leaves count null` | `loadDirectory during refreshRoot cancels stale` | `rapid duplicate loadDirectory debounced` | `blank serverId sets rootError without calling useCase` | `enterSearch sets isSearchMode true and clears query` | (+7 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/WorkspaceViewModelTest.kt","loc":457,"lang":"中文","zh":43,"en":0,"kdoc":0,"tests":21,"cls":"WorkspaceViewModelTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/theme/ChatDensityTest.kt
LOC 65 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 9 | btFuns 9 | ChatDensityTest
LEX: sse
FREQ: font×21 normal×17 chat×13 density×13 equals×12 body×10 typography×10 code×4 table×4 spacing×3
TESTS-EN: `Normal body font size is 14sp` | `Normal body line height is 22sp` | `Compact body font size is 13sp` | `Normal h1 is body plus 4` | `Normal h6 equals body size` | `Compact h1 is body plus 4` | `Normal code equals table font size` | `Normal table cell equals code block spacing` | `Headings are strictly descending in Normal`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/ui/theme/ChatDensityTest.kt","loc":65,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":9,"cls":"ChatDensityTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageFingerprintsTest.kt
LOC 84 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 7 | btFuns 7 | MessageFingerprintsTest
LEX: session,message,part,tool,sse,diff,fingerprint
FREQ: signature×11 equals×9 assistant×8 chat×6 user×6 time×5 domain×4 model×4 hash×4 output×4 info×3 state×3 tail×3 short×3
TESTS-EN: `messagesSignature same input same signature` | `messagesSignature different ids different signature` | `messageFingerprint same content same fingerprint` | `messageFingerprint different text different fingerprint` | `messagesSignature empty list boundary` | `tailHash long text differs from short hash` | `toolFingerprint running output affects fingerprint`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageFingerprintsTest.kt","loc":84,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":7,"cls":"MessageFingerprintsTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/verification/Layer1EnhancedTest.kt
LOC 309 | lang 混合 (zh 10/en 9, kdoc 2) | @Test 29 | btFuns 29 | Layer1EnhancedTest,equality,inequality
LEX: turn,sse,retry,diff
FREQ: error×154 tracker×44 policy×37 equals×36 delay×27 cooldown×24 timeout×15 calls×14 consecutive×14 timeouts×14 rate×13 limit×13 client×12 server×11
C-ZH: L22 [kdoc] Layer 1（网络韧性）的增强测试 —— 边界条件、 | L23 [kdoc] 错误路径以及现有测试套件未覆盖的边界情况。 | L49 [line]  toLongOrNull 对空字符串返回 null，但 "-5" 会被解析为 -5 | L50 [line]  然后 -5 * 1000 = -5000，这正是代码实际的行为。 | L138 [line]  attempt=100 → exp=99，500 * 2^99 非常大，但应被封顶 | L165 [line]  0.5^n → 递减 | L288 [line]  使用足够大、属于 "非常长" 但不会溢出的值 | L289 [line]  System.currentTimeMillis() + duration。currentTimeMillis 约 1.7T，因此最大安全 | L290 [line]  加数是 Long.MAX_VALUE - System.currentTimeMillis()。 | L291 [line]  约 27 小时 —— 实际上相当于永久
C-EN*: L128  RetryPolicy | L233  SseReadTimeoutTracker
   (+7 trivial en comments)
TESTS-EN: `mapHttpError 429 with unparseable retryAfterSeconds defaults to 0` | `mapHttpError 429 with empty retryAfterSeconds defaults to 0` | `mapHttpError 429 with negative retryAfterSeconds defaults to 0` | `mapHttpError 429 with very large retryAfterMs parses correctly` | `ApiError data object equality works` | `ApiError data object inequality works` | `ApiError ServerError with different codes are not equal` | `ApiError ServerError with same code is equal` | `ApiError RateLimitError equality` | `ApiResult Error equality with same ApiError` | `ApiResult Success equality` | `mapHttpError 405 maps to ClientError` | `mapHttpError 408 maps to ClientError` | `mapHttpError 422 maps to ClientError` | (+15 more)
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/verification/Layer1EnhancedTest.kt","loc":309,"lang":"混合","zh":10,"en":9,"kdoc":2,"tests":29,"cls":"Layer1EnhancedTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/util/PathUtilsTest.kt
LOC 43 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 6 | btFuns 6 | PathUtilsTest
LEX: turn,sse
FREQ: path×19 join×12 project×10 home×8 user×8 equals×7 base×5 relative×4 slash×3 handles×3
TESTS-EN: `joinPath joins base and relative with slash` | `joinPath handles trailing slash on base` | `joinPath handles leading slash on relative` | `joinPath handles trailing backslash on base` | `joinPath returns relative when base is blank` | `joinPath returns base when relative is blank`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/util/PathUtilsTest.kt","loc":43,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":6,"cls":"PathUtilsTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/util/SafeCatchTest.kt
LOC 55 | lang 中文 (zh 1/en 0, kdoc 1) | @Test 3 | btFuns 3 | SafeCatchTest
LEX: message,turn,sse,fallback
FREQ: exception×6 equals×6 cancellation×5 safe×5 block×4 calls×4 called×3 caught×3
C-ZH: L10 [kdoc] SafeCatch 工具测试（#60）。
TESTS-EN: `returns block result on success` | `calls fallback on regular exception` | `rethrows CancellationException and does not call fallback`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/util/SafeCatchTest.kt","loc":55,"lang":"中文","zh":1,"en":0,"kdoc":1,"tests":3,"cls":"SafeCatchTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageTimestampTest.kt
LOC 49 | lang 中文 (zh 3/en 0, kdoc 2) | @Test 4 | btFuns 4 | MessageTimestampTest
LEX: message,sse
FREQ: calendar×12 locale×7 timestamp×6 august×6 equals×5 date×4 formatters×4
C-ZH: L9 [kdoc] messageTimestamp（消息气泡标题栏条件时间戳）单测——2026-08-16。 | L10 [kdoc] 用 Locale.US 固定格式避免环境差异；时间用 Calendar 构造边界。 | L31 [line]  跨零点：仅 30 分钟前但已是昨天（自然日语义，非 24h 滚动窗口）
TESTS-ZH: `当天消息只显示时分秒` | `昨天消息显示完整年月日时分秒` | `跨年消息显示完整日期` | `同一天零点边界`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/util/MessageTimestampTest.kt","loc":49,"lang":"中文","zh":3,"en":0,"kdoc":2,"tests":4,"cls":"MessageTimestampTest"}




═══ app/src/test/kotlin/dev/leonardo/ocbeacon/util/RunCatchingCancellableTest.kt
LOC 59 | lang 混合 (zh 7/en 1, kdoc 4) | @Test 4 | btFuns 4 | RunCatchingCancellableTest
LEX: message,turn,sse
FREQ: exception×13 cancellation×11 catching×8 cancellable×6 equals×5 failure×4 caught×3 cancelled×3
C-ZH: L12 [kdoc] runCatchingCancellable 工具测试（#128 根因修复）。 | L14 [kdoc] 反模式对照：runCatching 捕获所有 Throwable 包括 CancellationException—— | L15 [kdoc] 协程取消被吞 → 取消后继续执行 → 取消链 handler 异常 → CompletionHandlerException | L16 [kdoc] （beta 真机崩溃，2026-08-14 反混淆定位 HomeViewModel.refreshServerSettingsAvailability）。 | L36 [line]  #128 根因：runCatching 会吞掉 CancellationException（Kotlin 已知陷阱）， | L37 [line]  导致协程不响应取消继续执行。修复后必须重新抛出，取消才能正确传播。 | L50 [line]  取消不应被包装为 Result.failure（那会让调用方误以为业务失败并继续执行）
   (+1 trivial en comments)
TESTS-EN: `returns success result on normal return` | `returns failure result on regular exception` | `rethrows CancellationException instead of swallowing it` | `does not produce failure result for cancellation`
STATS {"f":"app/src/test/kotlin/dev/leonardo/ocbeacon/util/RunCatchingCancellableTest.kt","loc":59,"lang":"混合","zh":7,"en":1,"kdoc":4,"tests":4,"cls":"RunCatchingCancellableTest"}




═══ app/src/test/resources/workspace-samples/sample-kotlin.kt
LOC 26 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | OpenCodeApi
LEX: turn,directory
FREQ: client×9 ktor×5 header×4 inject×4 request×3 conn×3 path×3
STATS {"f":"app/src/test/resources/workspace-samples/sample-kotlin.kt","loc":26,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"OpenCodeApi"}




═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ComposeTestRule.kt
LOC 14 | lang 中文 (zh 2/en 0, kdoc 2) | @Test 0 | btFuns 0 | ComposeTestRule
LEX: 
FREQ: compose×9 rule×8
C-ZH: L7 [kdoc] 提供 Compose test rule 的基础 mixin。 | L8 [kdoc] 任何需要测试 Compose UI 的插桩测试都可使用此接口。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ComposeTestRule.kt","loc":14,"lang":"中文","zh":2,"en":0,"kdoc":2,"tests":0,"cls":"ComposeTestRule"}




═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltTestRunner.kt
LOC 12 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | HiltTestRunner
LEX: turn,context
FREQ: application×6 android×5 runner×4 hilt×4
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltTestRunner.kt","loc":12,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"HiltTestRunner"}




═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltComponentActivity.kt
LOC 17 | lang 中文 (zh 8/en 0, kdoc 6) | @Test 0 | btFuns 0 | HiltComponentActivity
LEX: 
FREQ: activity×8 hilt×5 component×4 android×4 compose×4 rule×4 content×3
C-ZH: L6 [kdoc] 2026-08-16（#147）：androidTest 的 Compose 宿主 Activity。 | L8 [kdoc] 背景：HiltTestRunner（HiltTestApplication）下裸 ComponentActivity 经 | L9 [kdoc] v1 createComposeRule 启动失败 → "No compose hierarchies"。本类提供稳定 | L10 [kdoc] 宿主；**不加 @AndroidEntryPoint**——纯 UI 组件测试无需注入，加注解则 | L11 [kdoc] 要求每个测试声明 HiltAndroidRule（对 20+ 组件测试是负担）。 | L12 [kdoc] 需要注入的测试（ViewModel 级）继续用 HiltAndroidRule + 专属 Test Activity。 | L15 [line]  不 setContent——内容由测试规则（composeTestRule.setContent { }）注入 | L16 [line] （Activity 预置内容会与规则冲突：has already set content）
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltComponentActivity.kt","loc":17,"lang":"中文","zh":8,"en":0,"kdoc":6,"tests":0,"cls":"HiltComponentActivity"}




═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltEntryActivity.kt
LOC 18 | lang 中文 (zh 7/en 0, kdoc 7) | @Test 0 | btFuns 0 | HiltEntryActivity
LEX: 
FREQ: hilt×8 activity×7 android×7 component×4 point×4 view×3 model×3
C-ZH: L7 [kdoc] 2026-08-16（#147）：androidTest 的 Hilt 注入宿主 Activity。 | L9 [kdoc] 用于 ViewModel 级集成测试（chat.* 族——setContent 内 ChatScreen 调 | L10 [kdoc] hiltViewModel()，需要 Hilt entry point 提供ViewModelFactory）。 | L11 [kdoc] 使用方必须同时声明 HiltAndroidRule 并在 @Before 中 inject()。 | L13 [kdoc] 与 [HiltComponentActivity]（非 entrypoint，纯组件测试）分工—— | L14 [kdoc] @AndroidEntryPoint Activity 若无 HiltAndroidRule 会报 | L15 [kdoc] "The component was not created"，纯组件测试不需要这个负担。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltEntryActivity.kt","loc":18,"lang":"中文","zh":7,"en":0,"kdoc":7,"tests":0,"cls":"HiltEntryActivity"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/SampleInstrumentedTest.kt
LOC 19 | lang 中文 (zh 1/en 0, kdoc 1) | @Test 1 | btFuns 0 | SampleInstrumentedTest
LEX: sse,context
FREQ: instrumentation×3
C-ZH: L10 [kdoc] 验证测试基础设施已正确接线的健全性检查。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/SampleInstrumentedTest.kt","loc":19,"lang":"中文","zh":1,"en":0,"kdoc":1,"tests":1,"cls":"SampleInstrumentedTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestMessageBuilder.kt
LOC 185 | lang 中文 (zh 6/en 0, kdoc 6) | @Test 0 | btFuns 0 | PartListBuilder
LEX: session,message,part,turn,tool,question,permission,stream,abort,patch
FREQ: time×12 state×9 builder×8 content×6 domain×5 model×5 info×5 system×5 current×5 millis×5 error×5 random×4 output×4 call×4
C-ZH: L10 [kdoc]  为测试数据生成随机 ID。 | L17 [kdoc] 用于构造 List<Part> 的 DSL builder，提供合理的默认值。 | L18 [kdoc] 每个方法都会创建一个 Part，带有自增 ID 和匹配的 sessionId/messageId。 | L144 [kdoc] 为测试创建一条 user Message。 | L157 [kdoc] 创建一条带 parts 的 assistant Message。 | L158 [kdoc] 返回 MessageWithParts，使调用者同时拿到 message 和它的 parts。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestMessageBuilder.kt","loc":185,"lang":"中文","zh":6,"en":0,"kdoc":6,"tests":0,"cls":"PartListBuilder"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSettingsBuilder.kt
LOC 19 | lang 中文 (zh 2/en 0, kdoc 2) | @Test 0 | btFuns 0 | 
LEX: turn,tool
FREQ: settings×5 chat×4 density×4 collapse×3 expand×3 reasoning×3 show×3 dividers×3
C-ZH: L6 [kdoc] 为测试创建 AppSettings。 | L7 [kdoc] chatDensity："normal"（舒适）或 "compact"（紧凑）。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSettingsBuilder.kt","loc":19,"lang":"中文","zh":2,"en":0,"kdoc":2,"tests":0,"cls":""}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSessionBuilder.kt
LOC 22 | lang 中文 (zh 1/en 0, kdoc 1) | @Test 0 | btFuns 0 | 
LEX: session,directory,idle
FREQ: status×4 time×4 title×3
C-ZH: L7 [kdoc] 以合理的默认值为测试创建一个 Session。
ENSTR*: L11 "Test Session"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/builder/TestSessionBuilder.kt","loc":22,"lang":"中文","zh":1,"en":0,"kdoc":1,"tests":0,"cls":""}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/BaseChatTest.kt
LOC 112 | lang 中文 (zh 13/en 0, kdoc 8) | @Test 0 | btFuns 0 | BaseChatTest
LEX: session,message,part,token,question,permission,sse,idle,render
FREQ: chat×19 rule×16 repository×15 compose×13 hilt×12 state×9 android×8 settings×7 screen×7 inject×7 repo×6 server×6 domain×5 fakes×4
C-ZH: L24 [kdoc] ChatScreen 集成测试的基类。 | L26 [kdoc] 提供经 ChatSmokeTest 验证的标准 Hilt + Compose 搭建模式。 | L27 [kdoc] 子类可获得预注入的 fakes 和 [renderChatScreen] 辅助方法。 | L55 [line]  重置所有 fake 状态 —— Hilt 单例在同一类的多个测试间持久存在 | L73 [line]  注意：TokenStatsTracker 是 @Singleton —— 其状态在测试间持久存在。 | L74 [line]  依赖特定 token 状态的测试应当在 renderChatScreen() 之后显式设置， | L75 [line]  而不是依赖 @Before 的默认值。 | L79 [kdoc] 在 theme 包装下渲染 ChatScreen。在配置完 fake 状态后调用。 | L98 [kdoc] 在聊天输入框中输入文本。 | L100 [kdoc] 使用 [hasSetTextAction] 在 BasicTextField 的 decorationBox 内部定位 | L101 [kdoc] 真正的可编辑节点 —— 由于 semantics 合并时机问题，外层的 testTag | L102 [kdoc] 节点可能没有 SetText semantics action。 | L105 [line]  等待可编辑文本节点就绪（ViewModel init 是异步的）
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/BaseChatTest.kt","loc":112,"lang":"中文","zh":13,"en":0,"kdoc":8,"tests":0,"cls":"BaseChatTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInputTest.kt
LOC 114 | lang 中文 (zh 17/en 0, kdoc 4) | @Test 5 | btFuns 0 | ChatInputTest
LEX: agent,draft,sse,idle,render
FREQ: compose×23 rule×14 chat×13 nodes×11 node×8 input×7 repository×6 semantics×6 screen×5 send×5 wait×5 content×4 description×4 model×4
C-ZH: L19 [kdoc] 聊天输入栏行为的集成测试。 | L21 [kdoc] 覆盖：文本输入、斜杠命令自动补全、@-文件提及搜索、 | L22 [kdoc] 附件按钮可见性，以及发送按钮状态管理。 | L24 [kdoc] 使用 [BaseChatTest] 进行 Hilt + Compose 搭建，fakes 已预注入。 | L36 [line]  typeInput 使用 hasSetTextAction() 在 BasicTextField+decorationBox 中 | L37 [line]  定位真正的可编辑节点，绕过 semantics 合并问题。 | L40 [line]  BasicTextField + decorationBox 不通过 semantics 暴露 EditableText。 | L41 [line]  通过副作用验证输入生效：输入非空时发送按钮存在。 | L51 [line]  SlashCommandRegistry.clientCommands() 总是提供：new、compact、fork 等。 | L60 [line]  配置 fake 为 @-mention 搜索返回文件路径。 | L61 [line]  搜索路径为 ManageAgentUseCase → AgentRepository.searchFiles。 | L68 [line]  等待 150ms 防抖 + 异步协程完成 | L79 [line]  AgentModelVariantSelector（其中包含附件按钮）仅当 | L80 [line]  modelLabel 非空或 agents.size > 1 时才渲染。 | L88 [line]  等待 ViewModel 加载 agents 并渲染选择器行 | L94 [line]  附件按钮（AttachFile 图标）应当可见 | L106 [line]  输入为空时，点击发送不应触发 promptAsync
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInputTest.kt","loc":114,"lang":"中文","zh":17,"en":0,"kdoc":4,"tests":5,"cls":"ChatInputTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionTest.kt
LOC 424 | lang 混合 (zh 102/en 5, kdoc 58) | @Test 7 | btFuns 0 | ChatInteractionTest
LEX: session,message,event,part,turn,token,directory,question,permission,provider,config,stream,sse,cursor,paginat,page,abort,retry,busy,idle,context,pending,render
FREQ: compose×46 chat×41 rule×35 state×30 model×29 wait×20 nodes×17 seed×16 repository×14 domain×13 send×13 view×12 node×11 screen×11
C-ZH: L32 [kdoc] ChatScreen 交互行为的集成测试。 | L34 [kdoc] 继承 [BaseChatTest]，复用标准的 Hilt + Compose 搭建模式。 | L35 [kdoc] 每个测试配置 fake repository 状态，渲染 ChatScreen， | L36 [kdoc] 执行 UI 交互，并断言预期结果。 | L45 [kdoc] 会话状态仓库 fake —— 经 SessionStateRepository 接口绑定（FakeDomainModule）， | L46 [kdoc] 与 ChatViewModel 注入的是同一 @Singleton 实例，二者共享 FSM 状态。 | L54 [line]  ============ 辅助方法 ============ | L57 [kdoc] 注入消息，使其出现在 UI 中。 | L59 [kdoc] messageListState 将 messagesState 中的消息与 allPartsMapState | L60 [kdoc] （以 messageId 为键）中的 parts 合并。partsState 由 startObservingMessages() | L61 [kdoc] 内部使用，但 UI 读取的是 allPartsMapState。 | L68 [kdoc]  从独立的列表注入消息 — 便捷封装。 | L74 [kdoc]  注入一轮 user + assistant 的对话（带文本 parts）。 | L87 [kdoc] 注入一个权限请求，它将以 PermissionCard 形式呈现。 | L89 [kdoc] 注意：存储键为 ""，因为在插桩测试中 ViewModel 的 sessionIdFlow 为 "" | L90 [kdoc] （没有导航参数到达 savedStateHandle）。interactionState 调用 | L91 [kdoc] getPermissionsWithChildren(sid, ...)，其中 sid = sessionIdFlow.value = ""。 | L92 [kdoc] 事件自身的 sessionId 字段保留为 TEST_SESSION 以贴近真实情况， | L93 [kdoc] 但查找键必须匹配。 | L109 [kdoc] 注入一个问题，它将以 QuestionCard 形式呈现。 | L111 [kdoc] 注意：存储键为 "" — 原因见 seedPermission()。 | L136 [kdoc] 激活 SSE 消息观察管线。 | (+80 more)
C-EN*: L160 viewModel.sendMessage(parts) → sendParts() → SendMessageUseCase.sendPrompt() → | L291  abortSession() → sessionRepository.abort(serverId, sessionId, directory) | L355  R.string.chat_question_label("Question") → R.string.question_awaiting_reply
   (+2 trivial en comments)
ENSTR*: L199 "Ctx Provider" | L297 "Abort should have been called" | L326 "Permission Required" | L383 "Pagination needs hasOlderMessages=true, which requires loadMessagesForSession() | L389 "Message $i"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionTest.kt","loc":424,"lang":"混合","zh":102,"en":5,"kdoc":58,"tests":7,"cls":"ChatInteractionTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionIsolatedTest.kt
LOC 195 | lang 中文 (zh 37/en 0, kdoc 21) | @Test 3 | btFuns 0 | ChatInteractionIsolatedTest
LEX: session,message,part,token,tool,agent,provider,config,sse,idle,context,render
FREQ: model×35 compose×20 chat×16 nodes×14 rule×14 scroll×11 catalog×10 domain×9 info×9 repository×8 state×8 wait×8 bottom×8 server×7
C-ZH: L23 [kdoc] ChatScreen 交互行为的隔离集成测试。 | L25 [kdoc] 这些测试从 [ChatInteractionTest] 拆分而来，因为它们由于 ViewModel 复用 | L26 [kdoc] 污染而失败 —— 同一类中的先前测试会修改共享的 ViewModel 状态，且该状态 | L27 [kdoc] 无法重置。这里的每个测试都通过独立成类来获得全新的 ViewModel。 | L29 [kdoc] 继承 [BaseChatTest]，复用标准的 Hilt + Compose 搭建模式。 | L40 [line]  ============ 辅助方法 ============ | L43 [kdoc] 注入消息，使其出现在 UI 中。 | L45 [kdoc] messageListState 将 messagesState 中的消息与 allPartsMapState | L46 [kdoc] （以 messageId 为键）中的 parts 合并。partsState 由 startObservingMessages() | L47 [kdoc] 内部使用，但 UI 读取的是 allPartsMapState。 | L54 [line]  ============ 测试用例 ============ | L57 [kdoc] 测试：带已完成输出的工具卡片被显示。 | L59 [kdoc] 工具卡片通过 ToolCardScaffold 渲染。ReadToolCard（由 | L60 [kdoc] DefaultToolCardResolver 为 "read" 工具名解析）从 | L61 [kdoc] R.string.tool_read = "Read" 渲染标题。 | L68 [line]  渲染后注入 —— 确保全新的 ViewModel 订阅 | L89 [kdoc] 测试：provider 数据加载后，模型选择器显示可用模型。 | L91 [kdoc] providers 通过 SelectModelUseCase → ProviderRepository.loadProviderCatalog() | L92 [kdoc] 从 FakeServerRepository.catalogResult 加载。模型标签在 providers 加载后 | L93 [kdoc] 出现在 AgentModelVariantSelector 中。 | L97 [line]  同时设置 providersResult 和 catalogResult —— ModelConfigDelegate 用 | L98 [line]  loadProviders() 获取 ProviderInfo 列表，用 loadProviderCatalog() 获取 catalog。 | (+15 more)
ENSTR*: L84 "Tool card with 'Read' should be displayed" | L102 "Test Provider" | L167 "Message number $i with enough text content to fill at least one full line"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatInteractionIsolatedTest.kt","loc":195,"lang":"中文","zh":37,"en":0,"kdoc":21,"tests":3,"cls":"ChatInteractionIsolatedTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatMessageRenderingTest.kt
LOC 203 | lang 中文 (zh 47/en 0, kdoc 37) | @Test 8 | btFuns 0 | ChatMessageRenderingTest
LEX: session,message,part,turn,tool,question,stream,sse,render
FREQ: assistant×23 chat×21 user×18 state×14 compose×12 reasoning×12 error×11 node×10 screen×9 rule×9 seed×8 builder×7 card×7 settings×5
C-ZH: L15 [kdoc] 验证 ChatScreen 中消息渲染分支的集成测试。 | L17 [kdoc] 覆盖：user 消息、流式/已完成的 assistant 消息、reasoning、 | L18 [kdoc] 工具卡片、错误展示、轮次顺序，以及空状态。 | L20 [kdoc] 使用 [BaseChatTest] 进行 Hilt + Compose 搭建。消息直接注入到 | L21 [kdoc] fake repository 的 StateFlow（messagesState + allPartsMapState）中， | L22 [kdoc] MessageDataDelegate 的 combine pipeline 从中读取。 | L27 [line]  ============ 辅助方法 ============ | L30 [kdoc] 将消息注入 fake repository 的可观察 flow。 | L32 [kdoc] FakeChatRepository.setMessages 写入的是一个内部存储，该存储并未 | L33 [kdoc] 连接到 UI 读取的 StateFlow（messagesState / allPartsMapState）。 | L34 [kdoc] 此辅助方法通过直接设置 flow 来弥合这一差异，与真实的 | L35 [kdoc] ChatRepositoryImpl 中 setMessages 更新 flow 的语义一致。 | L43 [kdoc] 构建一个包含单个文本 part 的 user MessageWithParts。 | L45 [kdoc] aUserMessage() 创建一个不带 parts 的纯 Message.User；UI 从 | L46 [kdoc] Part.Text 渲染 user 文本，因此我们必须附加一个 part。 | L56 [line]  ============ 测试用例 ============ | L59 [kdoc] 测试 1：一条 user 消息在聊天气泡内渲染其文本内容。 | L71 [kdoc] 测试 2：一条流式 assistant 消息（time.completed == null）带有一个 | L72 [kdoc] 正在运行的工具时，显示工具卡片 —— 当 isRunning 为 true 时， | L73 [kdoc] ToolCardScaffold 在工具标题旁边渲染一个 PulsingDotsIndicator。 | L75 [kdoc] 我们断言工具标题文本已显示（它与脉动指示器共存于同一卡片行）。 | L76 [kdoc] ReadToolCard 从 R.string.tool_read = "Read" 解析标题。 | (+25 more)
ENSTR*: L161 "Partial response before error" | L177 "User asks a question"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatMessageRenderingTest.kt","loc":203,"lang":"中文","zh":47,"en":0,"kdoc":37,"tests":8,"cls":"ChatMessageRenderingTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatSmokeTest.kt
LOC 62 | lang 中文 (zh 6/en 0, kdoc 3) | @Test 1 | btFuns 0 | ChatSmokeTest
LEX: session,message,sse,idle,render
FREQ: chat×16 repository×13 rule×12 hilt×12 compose×8 android×8 inject×5 screen×4 repo×4 theme×3
C-ZH: L18 [kdoc] 冒烟测试：验证 Hilt 注入 + Compose 渲染在 fake repository 基础设施上 | L19 [kdoc] 能够端到端工作。 | L21 [kdoc] 如果此测试通过，后续所有 ChatScreen 集成测试都可依赖相同的搭建模式。 | L45 [line]  默认空状态 —— 仅验证屏幕挂载不崩溃 | L58 [line]  走到这里仍未崩溃，说明 Hilt 注入 + Compose 渲染正常工作。 | L59 [line]  验证注入的确实是 fake（而非真实 repository）
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatSmokeTest.kt","loc":62,"lang":"中文","zh":6,"en":0,"kdoc":3,"tests":1,"cls":"ChatSmokeTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatScrollStabilityTest.kt
LOC 394 | lang 中文 (zh 77/en 0, kdoc 44) | @Test 7 | btFuns 0 | ChatScrollStabilityTest
LEX: session,message,part,turn,token,question,stream,sse,idle,render
FREQ: compose×46 rule×35 chat×28 node×27 user×22 pair×22 assistant×20 state×16 completed×16 current×15 substring×14 swipe×13 perform×12 repeat×12
C-ZH: L21 [kdoc] SSE 滚动视口稳定性的集成测试（修复 beta.445 中 bug 的回归测试）。 | L23 [kdoc] 验证 `docs/research/sse-scroll-stability-iron-laws.md` 中描述的 | L24 [kdoc] ChatMessageList 行为： | L25 [kdoc] - 高度补偿只跟踪流式消息 | L26 [kdoc] - shouldCompensate 在用户回到底部时重置 | L27 [kdoc] - 已完成消息不会触发补偿 | L29 [kdoc] 这些测试使用 FakeChatRepository 的 [messagesState] 和 [allPartsMapState] | L30 [kdoc] flow —— 而非 [partsState] —— 因为 [MessageDataDelegate.messageListState] | L31 [kdoc] 读取的是 `getAllPartsMap()`，而非 `getParts()`。 | L33 [kdoc] 行为断言关注用户所见（文本可见性），而非内部滚动偏移，因为 Compose UI | L34 [kdoc] 测试不直接暴露滚动偏移。 | L39 [line]  ============ 辅助方法 ============ | L42 [kdoc] 在 fake repository 中设置 messages + parts。 | L43 [kdoc] [messageListState] 读取 `messagesState` 和 `allPartsMapState`。 | L50 [kdoc]  创建一个带单个文本 part 的 user 消息。 | L62 [kdoc]  将 [MessageWithParts] 拆解为 (Message, List<Part>) 对。 | L66 [kdoc] 模拟 token 增长：将 [messageId] 的文本 part 替换为 [newText]。 | L67 [kdoc] 仅修改 [allPartsMapState]；Message info 保持不变。 | L82 [kdoc]  生成长填充字符串，以模拟大量 token 输出。 | L86 [line]  ============ 测试用例 ============ | L89 [kdoc] 测试 1：流式消息增长时，视口保持在底部。 | L91 [kdoc] 当流式消息变长（token 到达）且用户位于底部时，视口应当跟随 —— | (+55 more)
ENSTR*: L124 "Question number $i about topic $i" | L133 "Latest question here" | L152 "Question number 0" | L171 "Earlier question $i" | L179 "Current question" | L192 "Earlier question 0" | L199 "After returning to bottom the content grew significantly " | L200 "with many new tokens that should be visible now at the bottom of the screen." | (+13 more)
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/chat/ChatScrollStabilityTest.kt","loc":394,"lang":"中文","zh":77,"en":0,"kdoc":44,"tests":7,"cls":"ChatScrollStabilityTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDaoTest.kt
LOC 113 | lang 中文 (zh 12/en 0, kdoc 5) | @Test 6 | btFuns 0 | ArchiveBucketDaoTest
LEX: session,message,turn,provider,sse,context,archive,upsert
FREQ: bucket×37 equals×13 android×8 database×8 blocking×7 latest×6 room×5 least×5 application×4 start×4 limit×3
C-ZH: L15 [kdoc] ArchiveBucketDao 插桩测试。 | L17 [kdoc] 放在 androidTest/ 而非 test/：项目 test/ 为纯 JVM（junit + mockk + coroutines-test， | L18 [kdoc] 无 Robolectric / androidx.test.core / room-testing），无法实例化 Android Context 与真实 | L19 [kdoc] Room 数据库。LogDaoTest 已确立此模式（@RunWith(AndroidJUnit4) + ApplicationProvider + | L20 [kdoc] inMemoryDatabaseBuilder）。运行需 connectedAndroidTest（模拟器/真机）。 | L56 [line]  2026-08-16 断言更新（#72 根治语义）：latestBefore 按 bucketStart 相交 | L57 [line]  判定（bucketStart=0 < 2500 → 三桶全部相交）——旧断言（2 桶）对应 | L58 [line]  bucketEnd < beforeEnd 的历史行为，桶内消息级过滤已上移到 | L59 [line]  MessageStore.loadArchivedRange。androidTest 首次运行暴露过时。 | L73 [line]  2026-08-16 断言更新（#72 语义）：bucketStart=0 < 1000 → 两桶相交 | L74 [line]  均返回（桶内消息级过滤在 MessageStore）。旧断言 0 对应 | L75 [line]  bucketEnd < beforeEnd 历史行为。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/ArchiveBucketDaoTest.kt","loc":113,"lang":"中文","zh":12,"en":0,"kdoc":5,"tests":6,"cls":"ArchiveBucketDaoTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/MigrationTest.kt
LOC 135 | lang 中文 (zh 27/en 0, kdoc 13) | @Test 1 | btFuns 0 | MigrationTest
LEX: session,message,part,provider,sse,context,archive,migrat
FREQ: room×34 master×18 table×17 sqlite×16 database×15 fresh×14 hash×13 cached×8 buckets×8 query×8 move×8 select×7 android×6 expected×6
C-ZH: L16 [kdoc] 数据库迁移插桩测试（v1 → v2）。 | L18 [kdoc] **背景**：[OcBeaconDatabase] `exportSchema = false` → Room 的 [androidx.room.testing.MigrationTestHelper] | L19 [kdoc] 无法读取 v1 schema JSON（`createDatabase` / `runMigrationsAndValidate` 均依赖编译期导出的 | L20 [kdoc] `schemas/<db>.json`），两条 Room 原生迁移测试路径在此项目都不可用。 | L22 [kdoc] **采用路径**：手动 v1 重建。因 MIGRATION_1_2 为纯加表（不动 cached_messages/cached_parts/logs | L23 [kdoc] 三基表，其 DDL 在 v1/v2 一致），故从 fresh v2 库提取三基表 DDL，在空 DB 文件重建 v1（建表 + 置 | L24 [kdoc] user_version=1 + 种入校验数据），再以 v2 builder + [Migrations.MIGRATION_1_2] 重开 Room， | L25 [kdoc] Room 检测到 1→2 即执行迁移。断言： | L26 [kdoc]   1. 迁移产生的 `archive_buckets` 建表 DDL 与 Room 自动生成的完全一致（捕获"手写 SQL 略偏"—— | L27 [kdoc]      这正是迁移失败的最高风险点，IllegalStateException 崩溃全量存量用户）； | L28 [kdoc]   2. 归档索引 DDL 一致； | L29 [kdoc]   3. 三基表种子数据存活（迁移不丢数据）。 | L31 [kdoc] 放 androidTest/：依赖真实 Room/SQLite（同 [ArchiveBucketDaoTest] 模式）。运行需 connectedAndroidTest。 | L41 [line]  清理 db/-wal/-shm 残留，确保干净起点 | L51 [line]  ---- 1. 从 fresh v2 库提取基表 DDL（迁移纯加表，基表 v1/v2 一致）+ 期望归档表/索引 DDL ---- | L70 [line]  room_master_table：Room 用其 identityHash 在 onOpen 判断是否需要 onCreate。若 v1 手工库缺 | L71 [line]  此表，Room 会走 createAllTables（建全 v2 表）而非 onUpgrade（迁移）→ 测不到 MIGRATION_1_2。 | L72 [line]  这里复制 v3 的 identityHash 行，使 Room 认为已就绪（hash 匹配）→ 仅按 version 1→2 跑迁移。 | L73 [line]  2026-08-16 适配：Room 2.8 的 room_master_table 列结构可能变化——动态探测 | L74 [line]  identityHash 列名（hash 列为唯一的非 id 文本列），避免硬编码列名失效。 | L90 [line]  ---- 2. 手工构造 v1 DB 文件：建三基表 + 其索引 + room_master_table（v2 hash），置 user_version=1 ---- | L97 [line]  2026-08-16：hash 列名与上方探测一致（Room 2.8 列结构可能变化） | (+5 more)
ENSTR*: L67 "AND tbl_name IN ('cached_messages','cached_parts','logs')" | L86 "fresh v2 must define archive_buckets table" | L87 "fresh v2 must define archive index" | L101 "INSERT INTO cached_messages(id, sessionId, created, role, payload) " | L115 "SELECT sql FROM sqlite_master WHERE type='table' AND name='archive_buckets'" | L118 "SELECT sql FROM sqlite_master WHERE type='index' AND name='index_archive_bucket | L120 "archive_buckets DDL must match Room-generated" | L121 "archive index DDL must match Room-generated" | (+1 more)
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/MigrationTest.kt","loc":135,"lang":"中文","zh":27,"en":0,"kdoc":13,"tests":1,"cls":"MigrationTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt
LOC 163 | lang 中文 (zh 8/en 0, kdoc 0) | @Test 8 | btFuns 0 | LogDaoTest
LEX: message,turn,provider,sse,context
FREQ: timestamp×26 level×16 latest×16 equals×15 error×12 fatal×11 byte×10 blocking×9 insert×9 delete×9 deleted×9 database×7 android×4 info×4
C-ZH: L64 [line]  最新在前 | L91 [line]  只删 INFO | L107 [line]  2026-08-16 断言更新：deleteErrorBefore 现语义为删除 ERROR**与 FATAL** | L108 [line] （DAO 注释明确两者）——timestamp<150 的两条（100 ERROR + 100 FATAL） | L109 [line]  都删，仅剩 200 ERROR。旧断言（deleted=1/latest=2）对应只删 ERROR 的 | L110 [line]  历史行为，androidTest 首次真正运行暴露过时。 | L129 [line]  最新的 2 条保留 | L139 [line]  剩 3 条 × 1 byteSize
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/data/local/LogDaoTest.kt","loc":163,"lang":"中文","zh":8,"en":0,"kdoc":0,"tests":8,"cls":"LogDaoTest"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeApiModule.kt
LOC 41 | lang 中文 (zh 5/en 0, kdoc 5) | @Test 0 | btFuns 0 | FakeApiModule
LEX: session,message,provider,terminal
FREQ: impl×20 singleton×9 binds×7 file×7 system×7 module×6 bind×6 dagger×5 hilt×3 install×3 client×3
C-ZH: L23 [kdoc] 测试环境下替换 ApiModule。 | L25 [kdoc] 绑定真实的 ApiImpl 类（它们依赖 ApiClient，而 ApiClient 接收来自 | L26 [kdoc] FakeNetworkModule 的占位 HttpClient）。由于所有 repository 都被 fake 了， | L27 [kdoc] 这些 API 永远不会被调用。ServerTerminalRegistry 依赖 TerminalApi —— | L28 [kdoc] 它会拿到真实的 TerminalApiImpl，但在测试中永远不会连接。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeApiModule.kt","loc":41,"lang":"中文","zh":5,"en":0,"kdoc":5,"tests":0,"cls":"FakeApiModule"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeNetworkModule.kt
LOC 53 | lang 中文 (zh 3/en 0, kdoc 3) | @Test 0 | btFuns 0 | FakeNetworkModule
LEX: context
FREQ: json×10 store×9 client×8 preferences×7 dagger×6 singleton×6 module×5 install×5 ktor×5 provides×4 hilt×4 datastore×3 network×3
C-ZH: L24 [kdoc] 测试环境下替换 NetworkModule。 | L25 [kdoc] 提供一个最小化的 HttpClient（OkHttp 引擎，不含 auth/logging/timeout 插件） | L26 [kdoc] 以及一个测试作用域的 DataStore。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeNetworkModule.kt","loc":53,"lang":"中文","zh":3,"en":0,"kdoc":3,"tests":0,"cls":"FakeNetworkModule"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeDomainModule.kt
LOC 64 | lang 中文 (zh 7/en 0, kdoc 5) | @Test 0 | btFuns 0 | FakeDomainModule
LEX: session,message,agent,draft,provider,config
FREQ: repository×83 domain×17 singleton×16 binds×14 server×14 impl×14 bind×13 fakes×11 module×7 chat×6 dagger×5 file×5 state×5 settings×5
C-ZH: L33 [kdoc] 用 fake repository 绑定替换 DomainModule。 | L35 [kdoc] DomainModule（di/）绑定全部 repository 接口，包括 | L36 [kdoc] ChatRepository 和 SessionRepository（原 DataModule 已合并）。 | L38 [kdoc] ServerRepositoryImpl 实现了 3 个接口；FakeServerRepository 同样如此， | L39 [kdoc] 因此我们将同一个 fake 实例绑定为全部 3 种类型。 | L56 [line]  2026-08-16：androidTest 测试图补 MessageCacheRepository（此前源集从未编译、缺口被掩盖） | L60 [line]  ServerRepository 及其 2 个子接口 —— 全部由单个 FakeServerRepository 支撑
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/di/FakeDomainModule.kt","loc":64,"lang":"中文","zh":7,"en":0,"kdoc":5,"tests":0,"cls":"FakeDomainModule"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeAgentRepository.kt
LOC 37 | lang 中文 (zh 3/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeAgentRepository
LEX: session,turn,agent,directory
FREQ: info×6 server×5 inject×4 repository×4 success×4 domain×3 command×3 commands×3 search×3 files×3 switched×3
C-ZH: L20 [line]  2026-08-16：switchAgent 已随死代码删除（2face6d7）——override 残留导致 | L21 [line]  androidTest 源集编译失败（接口无此方法）。switchedAgents 记录保留供 | L22 [line]  历史断言迁移参考；agent 切换现走 prompt body（V2ApiClient prompt）。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeAgentRepository.kt","loc":37,"lang":"中文","zh":3,"en":0,"kdoc":0,"tests":0,"cls":"FakeAgentRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeFileRepository.kt
LOC 57 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeFileRepository
LEX: directory
FREQ: server×14 file×13 content×10 paths×7 success×7 domain×6 find×6 model×5 inject×4 repository×4 node×3 project×3 path×3 files×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeFileRepository.kt","loc":57,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"FakeFileRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeChatRepository.kt
LOC 335 | lang 混合 (zh 24/en 1, kdoc 7) | @Test 0 | btFuns 0 | FakeChatRepository
LEX: session,message,event,part,turn,tool,agent,directory,question,permission,provider,compaction,sse,cursor,revert,snapshot,diff,pending,merge,upsert
FREQ: state×63 flow×42 mutable×33 model×25 server×21 store×21 domain×20 progress×18 success×18 info×15 asked×12 command×12 shell×11 step×9
C-ZH: L28 [kdoc] Fake ChatRepository，包含 46 个方法。 | L30 [kdoc] 模式： | L31 [kdoc] - Flow 方法返回公共的 MutableStateFlow 字段（测试设置 .value） | L32 [kdoc] - suspend 方法返回可配置的 Result 字段（默认 = success） | L33 [kdoc] - 同步变更方法记录调用 + 更新状态 | L35 [kdoc] 与会话无关：所有 flow 方法不论 sessionId/serverId 都返回同一个 flow。 | L40 [line]  ============ 可控 State Flow ============ | L54 [line]  同步变更的内部后备存储 | L63 [line]  ============ 可配置 suspend Result ============ | L84 [line]  ============ 调用记录 ============ | L91 [line]  ============ 状态观察 ============ | L113 [line]  ============ 按 session 键的 Flow 观察 ============ | L123 [line]  ============ 网络操作 ============ | L162 [line]  ============ 待处理查询 ============ | L182 [line]  ============ 命令执行 ============ | L210 [line]  ============ 后台活动（V2） ============ | L232 [line]  ============ UI 状态 ============ | L240 [line]  ============ 权限自动批准 ============ | L246 [line]  ============ 写操作（状态更新） ============ | L290 [line]  必须在 allPermissionsMapState 上发射，使 ViewModel 的 combine flow 重新触发 | L301 [line]  必须在 allQuestionsMapState 上发射，使 ViewModel 的 combine flow 重新触发 | L319 [line]  ============ 原始状态读取 ============ | (+2 more)
   (+1 trivial en comments)
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeChatRepository.kt","loc":335,"lang":"混合","zh":24,"en":1,"kdoc":7,"tests":0,"cls":"FakeChatRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeDraftRepository.kt
LOC 24 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeDraftRepository
LEX: session,draft
FREQ: inject×4 repository×4
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeDraftRepository.kt","loc":24,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"FakeDraftRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMessageCacheRepository.kt
LOC 72 | lang 中文 (zh 5/en 0, kdoc 5) | @Test 0 | btFuns 0 | FakeMessageCacheRepository
LEX: session,message,part,paginat,archive,upsert
FREQ: flow×7 repository×6 limit×6 cache×5 inject×4 info×4 created×4 kotlinx×3 coroutines×3 load×3 range×3
C-ZH: L12 [kdoc] 2026-08-16：androidTest Hilt 测试图的 MessageCacheRepository Fake。 | L14 [kdoc] 背景：androidTest 源集此前从未编译通过（Fake 接口漂移积累），主图的 | L15 [kdoc] MessageStore 依赖 Room DAO（测试图不含数据库基建），MessagePaginationUseCase | L16 [kdoc] 的 MessageCacheRepository 绑定缺失 → Hilt 测试图 Dagger/MissingBinding。 | L17 [kdoc] 本 Fake 以内存 Map 支撑测试语义。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMessageCacheRepository.kt","loc":72,"lang":"中文","zh":5,"en":0,"kdoc":5,"tests":0,"cls":"FakeMessageCacheRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeServerRepository.kt
LOC 124 | lang 混合 (zh 3/en 3, kdoc 3) | @Test 0 | btFuns 0 | FakeServerRepository
LEX: turn,agent,provider,config,patch
FREQ: server×40 success×16 domain×15 model×14 repository×14 state×11 global×10 servers×10 connection×9 flow×7 inject×4 auth×4 method×4 status×4
C-ZH: L21 [kdoc] 实现全部 3 个 server 相关接口的 Fake。 | L22 [kdoc] DomainModule 将单个 ServerRepositoryImpl 绑定为全部 3 个接口； | L23 [kdoc] FakeDomainModule 以同样方式绑定此单个实例。
C-EN*: L31  ============ ServerConfigRepository ============ | L58  ============ ProviderRepository ============
   (+1 trivial en comments)
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeServerRepository.kt","loc":124,"lang":"混合","zh":3,"en":3,"kdoc":3,"tests":0,"cls":"FakeServerRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMcpRepository.kt
LOC 24 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeMcpRepository
LEX: 
FREQ: server×9 connection×8 domain×5 inject×4 model×4 repository×4 conn×4 status×3 servers×3 toggle×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeMcpRepository.kt","loc":24,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"FakeMcpRepository"}



═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionStateRepository.kt
LOC 97 | lang 中文 (zh 7/en 0, kdoc 6) | @Test 0 | btFuns 0 | FakeSessionStateRepository
LEX: session,message,event,part,sse,abort,busy,history
FREQ: flow×33 state×22 states×12 status×11 domain×10 activity×9 sync×9 model×8 transition×8 repository×7 mutable×6 update×6 client×6 derived×5
C-ZH: L20 [kdoc] 模拟真实 [dev.leonardo.ocbeacon.data.repository.SessionStateService] 状态机行为的 fake。 | L22 [kdoc] 复用纯函数 [SessionStateFSM] 维护会话 FSM 状态，使 statusFlow/activityFlow | L23 [kdoc] 能像真实实现一样对 onClientSendParts/onClientAbort 等事件作出响应。 | L24 [kdoc] 由 FakeDomainModule 绑定到 [SessionStateRepository]，与 ViewModel 共享同一 | L25 [kdoc] @Singleton 实例。 | L49 [line]  2026-08-16：接口新增（active 轮询双向对账）——Fake 空实现 | L54 [kdoc]  SSE 重连补拉——Fake 无内容可补，空实现。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionStateRepository.kt","loc":97,"lang":"中文","zh":7,"en":0,"kdoc":6,"tests":0,"cls":"FakeSessionStateRepository"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionRepository.kt
LOC 218 | lang 混合 (zh 11/en 1, kdoc 0) | @Test 0 | btFuns 0 | FakeSessionRepository
LEX: session,message,part,turn,agent,directory,provider,stream,cursor,page,abort,archive
FREQ: flow×35 time×35 server×31 current×21 success×17 system×15 millis×15 model×13 state×13 mutable×11 domain×9 title×9 created×9 delete×9
C-ZH: L95 [line]  ============ 状态观察 ============ | L113 [line]  2026-08-16（androidTest 源集修复）：接口既有成员缺失实现的补齐 | L123 [line]  ============ 会话生命周期 ============ | L137 [line]  ============ 归档 ============ | L143 [line]  ============ 分享 / 导出 ============ | L163 [line]  ============ 导入 ============ | L167 [line]  ============ 消息操作 ============ | L186 [line]  ============ 当前 Agent/Model ============ | L192 [line]  ============ 写操作 ============ | L198 [line]  ============ 会话状态同步 ============ | L203 [line]  ============ 服务器会话映射 / 最近消息时间 / 会话列表 ============
   (+1 trivial en comments)
ENSTR*: L29 "New Session" | L37 "Test Session" | L46 "Forked Session" | L82 "Imported Session"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSessionRepository.kt","loc":218,"lang":"混合","zh":11,"en":1,"kdoc":0,"tests":0,"cls":"FakeSessionRepository"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeVcsRepository.kt
LOC 28 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeVcsRepository
LEX: directory,diff,context
FREQ: branch×9 domain×5 inject×4 model×4 info×4 repository×4 change×3 mode×3 file×3 success×3 status×3 server×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeVcsRepository.kt","loc":28,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":0,"cls":"FakeVcsRepository"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSettingsRepository.kt
LOC 101 | lang 中文 (zh 4/en 0, kdoc 0) | @Test 0 | btFuns 0 | FakeSettingsRepository
LEX: session,turn,provider,unread,migrat
FREQ: state×45 flow×24 server×18 settings×16 model×10 tags×10 mutable×9 favorite×8 assignments×7 hidden×6 models×6 times×5 inject×4 repository×4
C-ZH: L38 [line]  2026-08-16（方案 A·默认模型）：接口新增成员的 Fake 实现 | L45 [line]  2026-08-16（androidTest 源集修复）：接口既有成员的缺失实现 | L84 [line]  ============ 会话已读（未读提示） ============ | L99 [line]  noop — Fake 不持久化，无需迁移
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/fakes/FakeSettingsRepository.kt","loc":101,"lang":"中文","zh":4,"en":0,"kdoc":0,"tests":0,"cls":"FakeSettingsRepository"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerBranchTest.kt
LOC 90 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 7 | btFuns 0 | CompactionBannerBranchTest
LEX: token,compaction,sse,context
FREQ: compose×22 reason×20 rule×19 state×15 active×12 banner×10 info×8 content×7 compressing×7 node×6 shows×5 equals×3 nodes×3
ENSTR*: L23 "Compacting context..." | L26 "Compressing context: Compacting context..." | L36 "Compressing context…" | L51 "Reducing 500k tokens to 100k to stay within limits" | L57 "Compressing context: $longReason" | L68 "Compressing context: $reason" | L88 "Compressing context: some reason"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerBranchTest.kt","loc":90,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":7,"cls":"CompactionBannerBranchTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/chat/TaskSheetClickTest.kt
LOC 61 | lang 中文 (zh 7/en 0, kdoc 5) | @Test 1 | btFuns 0 | TaskSheetClickTest
LEX: session,agent,subagent,provider,sse,idle,task,history
FREQ: compose×11 rule×8 click×5 state×5 chat×4 sheet×4 running×4 perform×3 shell×3 screens×3 clickable×3 clicked×3
C-ZH: L17 [kdoc] 2026-08-16：TaskSheet subagent 列表项点击跳转回归测试。 | L19 [kdoc] 背景：模拟器 uiautomator 点击 item（坐标正确、clickable 容器在场） | L20 [kdoc] 探针 0 触发——用 Compose 测试框架的语义级 performClick（不经坐标系） | L21 [kdoc] 确定性判断 clickable 是否接线。若本测试通过而真机点击仍失灵， | L22 [kdoc] 则问题在坐标/输入层（uiautomator/显示缩放），非 App 代码。 | L55 [line]  isRunning=true 使 item 直接出现在默认 Running 视图（点击语义与 | L56 [line]  History 视图完全相同——同一 ListItem clickable）
ZHSTR: L38 "写 50 字月亮故事" | L59 "onOpenSubSession 应被调用（语义级点击）"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/chat/TaskSheetClickTest.kt","loc":61,"lang":"中文","zh":7,"en":0,"kdoc":5,"tests":1,"cls":"TaskSheetClickTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/SessionRetryCardTest.kt
LOC 53 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | SessionRetryCardTest
LEX: session,message,sse,retry
FREQ: compose×12 rule×11 attempt×5 node×4 card×4 countdown×4 error×4 shows×3 content×3 attempts×3 seconds×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/SessionRetryCardTest.kt","loc":53,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"SessionRetryCardTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorBranchTest.kt
LOC 117 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 10 | btFuns 0 | StepProgressIndicatorBranchTest
LEX: agent,sse
FREQ: step×59 compose×33 rule×30 progress×23 info×21 node×15 indicator×12 model×10 content×10 shows×5 build×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorBranchTest.kt","loc":117,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":10,"cls":"StepProgressIndicatorBranchTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorTest.kt
LOC 46 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | StepProgressIndicatorTest
LEX: agent,sse
FREQ: step×17 compose×12 rule×11 progress×9 info×7 indicator×5 node×4 model×3 shows×3 content×3 code×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/StepProgressIndicatorTest.kt","loc":46,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"StepProgressIndicatorTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CopyButtonTest.kt
LOC 23 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 1 | btFuns 0 | CopyButtonTest
LEX: sse
FREQ: compose×8 rule×7 copy×5 content×3 button×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CopyButtonTest.kt","loc":23,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":1,"cls":"CopyButtonTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ConnectionErrorScreenTest.kt
LOC 64 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | ConnectionErrorScreenTest
LEX: message,config,sse,retry
FREQ: compose×14 rule×12 server×12 connection×6 countdown×6 node×5 click×5 error×4 screen×4 status×4 shows×3 content×3 servers×3 switch×3
ENSTR*: L62 "Retrying in"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ConnectionErrorScreenTest.kt","loc":64,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"ConnectionErrorScreenTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoBranchTest.kt
LOC 193 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 13 | btFuns 0 | MessageMetaInfoBranchTest
LEX: message,token,sse
FREQ: compose×40 rule×37 duration×20 model×18 node×17 meta×15 info×15 input×14 output×14 content×13 shows×10 claude×6 equals×4 nodes×4
ENSTR*: L30 "1500 tokens" | L58 "1000 tokens" | L137 "1999998 tokens"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoBranchTest.kt","loc":193,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":13,"cls":"MessageMetaInfoBranchTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoTest.kt
LOC 41 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 2 | btFuns 0 | MessageMetaInfoTest
LEX: message,token,sse
FREQ: compose×10 rule×9 meta×4 info×4 node×3 model×3
ENSTR*: L39 "1500 tokens"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/MessageMetaInfoTest.kt","loc":41,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":2,"cls":"MessageMetaInfoTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerTest.kt
LOC 48 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | CompactionBannerTest
LEX: compaction,sse,context
FREQ: compose×14 rule×11 state×7 banner×5 info×4 compressing×4 active×4 node×3 content×3
ENSTR*: L23 "context full" | L26 "Compressing context: context full" | L36 "Compressing context…"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/CompactionBannerTest.kt","loc":48,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"CompactionBannerTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardBranchTest.kt
LOC 132 | lang 中文 (zh 15/en 0, kdoc 0) | @Test 7 | btFuns 0 | TokenUsageCardBranchTest
LEX: token,sse,context,render
FREQ: compose×32 rule×29 cache×20 usage×14 node×13 card×13 cost×11 input×10 reasoning×10 write×9 output×8 content×7 android×6 equals×5
C-ZH: L43 [line]  2026-08-16（locale 无关断言）：%,d 分组符是 Locale 敏感的——模拟器 | L44 [line]  默认 locale 与测试编写时的 en_US 分组格式可能不同，硬编码 "1,700" 会 | L45 [line]  在非逗号分组 locale（如 de 的 1.700）失败。经 activity.getString 动态构造。 | L103 [line]  2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context | L104 [line]  窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品 | L105 [line]  测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。 | L107 [line]  2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context | L108 [line]  窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品 | L109 [line]  测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。 | L111 [line]  2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context | L112 [line]  窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品 | L113 [line]  测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。 | L115 [line]  2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context | L116 [line]  窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品 | L117 [line]  测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。
ENSTR*: L39 "1,000 tokens"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardBranchTest.kt","loc":132,"lang":"中文","zh":15,"en":0,"kdoc":0,"tests":7,"cls":"TokenUsageCardBranchTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardTest.kt
LOC 63 | lang 无注释 (zh 0/en 0, kdoc 0) | @Test 3 | btFuns 0 | ToolProgressCardTest
LEX: part,tool,sse
FREQ: compose×12 rule×11 progress×11 info×7 card×5 node×4 bash×4 running×4 shows×3 content×3 call×3 status×3
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardTest.kt","loc":63,"lang":"无注释","zh":0,"en":0,"kdoc":0,"tests":3,"cls":"ToolProgressCardTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardBranchTest.kt
LOC 201 | lang 中文 (zh 1/en 0, kdoc 0) | @Test 13 | btFuns 0 | ToolProgressCardBranchTest
LEX: part,tool,sse
FREQ: progress×47 compose×38 rule×35 info×29 running×17 node×16 bash×16 card×15 content×13 call×12 status×12 title×9 shows×7 started×6
C-ZH: L182 [line]  title="" 不是 null，因此显示 "" 而非 "bash"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/ToolProgressCardBranchTest.kt","loc":201,"lang":"中文","zh":1,"en":0,"kdoc":0,"tests":13,"cls":"ToolProgressCardBranchTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardTest.kt
LOC 51 | lang 中文 (zh 2/en 0, kdoc 0) | @Test 2 | btFuns 0 | TokenUsageCardTest
LEX: token,sse
FREQ: compose×12 rule×9 usage×5 card×4 cache×4 nodes×3 node×3 input×3 output×3
C-ZH: L45 [line]  即使所有值为零也应当能渲染而不崩溃。 | L46 [line]  存在多个 "0" 节点（input、output、total），因此验证至少有一个被显示。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/components/TokenUsageCardTest.kt","loc":51,"lang":"中文","zh":2,"en":0,"kdoc":0,"tests":2,"cls":"TokenUsageCardTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreenTest.kt
LOC 152 | lang 混合 (zh 5/en 2, kdoc 3) | @Test 5 | btFuns 0 | SessionListScreenTest
LEX: session,sse,archive
FREQ: compose×45 search×22 rule×18 material×17 query×16 chip×11 icon×11 content×10 click×10 filter×9 selected×9 icons×8 node×8 runtime×5
C-ZH: L37 [kdoc] L2：针对 SessionListScreen 中新增的 SearchBar 和 Archive FilterChip 的测试。 | L38 [kdoc] 这些组件内联渲染在 SessionListScreen 中；这里我们以相同配置在隔离环境下 | L39 [kdoc] 测试它们，以验证渲染与交互。 | L145 [line]  初始未选中 | L148 [line]  点击以选中
C-EN*: L111  ── Archive FilterChip ──────────────────────────────────────────────
   (+1 trivial en comments)
ENSTR*: L68 "Search sessions..." | L107 "my session"
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreenTest.kt","loc":152,"lang":"混合","zh":5,"en":2,"kdoc":3,"tests":5,"cls":"SessionListScreenTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffViewTest.kt
LOC 110 | lang 中文 (zh 5/en 0, kdoc 3) | @Test 2 | btFuns 0 | DiffViewTest,OpenCodeApi,OpenCodeApi
LEX: sse,diff,patch,render
FREQ: compose×22 rule×15 file×9 view×7 client×7 activity×6 state×6 content×6 node×6 hunks×6 start×6 line×6 viewer×5 status×5
C-ZH: L21 [kdoc] [DiffView] 的插桩测试。验证 hunk 渲染，以及在点击 next/prev 时 | L22 [kdoc] hunk 导航器计数器的推进。 | L24 [kdoc] 使用贴近真实的数据（D7-003）：一个看起来真实的 3-hunk patch。 | L53 [line]  2026-08-16 修正：@@ 元数据头由 filterPatchLines 过滤（DiffView 设计， | L54 [line]  LazyList 只渲染内容行）——改断言首条内容行存在。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/DiffViewTest.kt","loc":110,"lang":"中文","zh":5,"en":0,"kdoc":3,"tests":2,"cls":"DiffViewTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt
LOC 63 | lang 中文 (zh 6/en 0, kdoc 0) | @Test 2 | btFuns 0 | MarkdownTableWrapTest
LEX: sse,idle
FREQ: root×22 compose×15 cell×13 rule×12 width×12 nodes×11 bounds×9 markdown×8 right×8 delta×7 semantics×5 fetch×4 material×3 color×3
C-ZH: L32 [line]  SubcomposeLayout 使用 "probe" + "final" 两次组合；二者都会 | L33 [line]  创建 semantics 节点。probe 以无限最大宽度测量，因此其 URL | L34 [line]  节点会溢出 —— 选取能放入容器内的那个节点。 | L51 [line]  来自 markdown 的单元格文本带有尾随空格（"delta "），因此使用 | L52 [line]  子串匹配。SubcomposeLayout 的 probe + final 会创建多个节点 —— | L53 [line]  选取位于容器边界内、已被放置的那个节点。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt","loc":63,"lang":"中文","zh":6,"en":0,"kdoc":0,"tests":2,"cls":"MarkdownTableWrapTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/settings/DiagnosticsScreenDuplicateTimestampTest.kt
LOC 86 | lang 中文 (zh 11/en 0, kdoc 6) | @Test 1 | btFuns 0 | DiagnosticsScreenDuplicateTimestampTest
LEX: message,sse,idle,render
FREQ: rule×14 timestamp×11 compose×10 hilt×9 android×8 repository×8 duplicate×6 diagnostic×5 blocking×4 inject×4 node×3 theme×3 diagnostics×3 screen×3
C-ZH: L19 [kdoc] 回归测试：同一毫秒内产生多条日志（timestamp 相同）时， | L20 [kdoc] DiagnosticsScreen 的 LazyColumn 不得因重复 key 崩溃。 | L22 [kdoc] 复现场景：崩溃报告中 `Key "1785566688405" was already used` —— | L23 [kdoc] 该 key 正是日志条目的 timestamp（13 位 epoch 毫秒）。旧代码 | L24 [kdoc] `items(filteredEntries, key = { it.timestamp })` 在同毫秒两条日志 | L25 [kdoc] 时抛 IllegalArgumentException。修复后 key 追加列表 index 保证唯一。 | L52 [line]  直接注入两条相同 timestamp 的日志，绕过 AppLogger 的单调化， | L53 [line]  模拟修复前已写入数据库的重复数据（或极端竞态场景）。 | L54 [line]  崩溃报告中的 key | L81 [line]  修复前：渲染时抛 IllegalArgumentException（Key was already used） | L82 [line]  修复后：两条条目都应正常显示，且均可独立展开。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/settings/DiagnosticsScreenDuplicateTimestampTest.kt","loc":86,"lang":"中文","zh":11,"en":0,"kdoc":6,"tests":1,"cls":"DiagnosticsScreenDuplicateTimestampTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/tree/FileTreePanelTest.kt
LOC 180 | lang 中文 (zh 11/en 0, kdoc 5) | @Test 5 | btFuns 0 | FileTreePanelTest
LEX: message,directory,sse,retry,workspace
FREQ: file×36 compose×27 node×26 rule×22 root×21 tree×18 ignored×17 state×15 show×12 toggle×12 nodes×10 activity×7 panel×7 loading×7
C-ZH: L21 [kdoc] [FileTreePanel] 的插桩测试。验证四种 UI 状态 | L22 [kdoc] （加载中 / 错误 / 空 / 已填充）以及 showIgnored 过滤器的接线。 | L24 [kdoc] 使用贴近真实的数据（D7-003）：真实的 OpenCode 文件名和路径。 | L95 [line]  2026-08-16：子文件仅在目录展开时 flatten（FileTreeUtils 契约） | L107 [line]  showIgnored = false 时，被忽略的文件被过滤掉 | L120 [line]  2026-08-16：子文件仅在目录展开时 flatten（FileTreeUtils 契约） | L129 [line]  2026-08-18（#149）：原断言 onNodeWithText("显示隐藏") 在 en 测试环境 | L130 [line]  匹配 0 节点（资源实为"显示忽略项"/"Show ignored"，文案已改测试未跟） | L131 [line]  → 注入失败。改用 testTag（locale 无关） | L137 [kdoc] 模拟 OpenCode 项目布局的真实示例树（D7-003）。 | L138 [kdoc] 根节点包含目录 `app`（内含两个源文件）和一个被忽略的 `.gitignore`。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/tree/FileTreePanelTest.kt","loc":180,"lang":"中文","zh":11,"en":0,"kdoc":5,"tests":5,"cls":"FileTreePanelTest"}


═══ app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/git/GitChangesPanelTest.kt
LOC 110 | lang 中文 (zh 3/en 0, kdoc 3) | @Test 3 | btFuns 0 | GitChangesPanelTest
LEX: sse,retry,diff,workspace,render,badge
FREQ: compose×21 rule×20 changes×15 node×10 state×9 status×8 panel×8 activity×7 error×6 change×5 component×3 code×3 content×3 loading×3
C-ZH: L16 [kdoc] [GitChangesPanel] 的插桩测试。验证带状态徽章的变更渲染、 | L17 [kdoc] 干净工作树状态，以及错误/重试状态。 | L19 [kdoc] 使用贴近真实的数据（D7-003）：真实的 OpenCode 文件路径和数量。
STATS {"f":"app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/workspace/git/GitChangesPanelTest.kt","loc":110,"lang":"中文","zh":3,"en":0,"kdoc":3,"tests":3,"cls":"GitChangesPanelTest"}
