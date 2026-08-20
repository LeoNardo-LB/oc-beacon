# 2026-08-14 跨维度审计批次（113 条）
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **#108 SSE 心跳机制缺陷批次（D2-03 阻塞读挂死 + D2-05 V1 心跳不一致）** `sse` `network`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-03/D2-05（B 路 + 主代理双源）
  - **2026-08-14 修复完成**：① 两客户端阻塞读套 `withTimeoutOrNull(40s)` 超时防护（SseClient 两处 + SseClientV2 帧级）——半开 TCP（kill -9/NAT 静默断）下 40s 无数据强制断开走重连，不再永久挂死；② V1 心跳与 V2 对齐（任意行/事件到达即刷新 lastHeartbeat，不再仅 ServerHeartbeat）——V1 长流式不再 40s 假超时断连；③ 测试驱动发现真实缺陷：对端 FIN 关闭时 readByte 抛 EOFException（非 ClosedReadChannelException）→ 正常 EOF 被当异常 → 补捕获（readRawLineBytesWithTimeout 辅助函数 +4 测试）；④ 模拟器实测：V2 SSE 连接建立 + 事件流正常 + kill 服务器后重连链路可用。单测 1587 全通过
  - 问题：① 两客户端 socketTimeout=Long.MAX_VALUE + 心跳检查仅在行间 → 半开 TCP（kill -9/NAT 静默断）连接永久挂死，重连/冷却失效；② V1 心跳只在 ServerHeartbeat 事件刷新（V2 已改任意事件刷新）→ V1 服务器长流式 40s 假超时断连
  - 方案：读循环套 withTimeoutOrNull(40s)；V1 心跳与 V2 对齐（任意事件/空帧刷新）；加日志观测命中率
  - 工时：~0.5d | 难度：低-中 | 涉及：SseClient/SseClientV2/SseConnectionManager | 优先级：P0

- [x] **#109 V2 REST/SSE part id 契约错位（D2-01）——已修复 5b749536（真机+模拟器 DB 双重验证）** `compat` `sse`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-01（A 路，主代理回读确认）
  - 问题：V2Mappers 空 part id（id=""）与 SSE derivePartId（msg_ord_N）契约不一致 → mergePartsList preserved 双份保留 → 已完结消息文本双份渲染
  - 方案：V2Mappers 统一 derivePartId；或 mergePartsList 空 id 内容匹配合并；先模拟器实测复现
  - 工时：~0.5-1d | 难度：中 | 涉及：V2Mappers/MessageEventHandler | 优先级：P0 ✅ 2026-08-14 完结
  - 根因实测补充（2026-08-14 真机抓帧 + 服务器二进制）：服务器 ordinal **按类型独立计数**（同消息 reasoning[0]/text[0] 并存，TUI 片段键 k(msg,"text",ordinal) 同构）——旧 derivePartId 漏 type，三缺陷：① id 碰撞 → text.started 按 id 命中并替换 Reasoning part（推理丢失）；② REST id="" vs SSE 派生 id 双保留（双份渲染）；③ Time(start=ordinal) 伪造时长（"思考完毕 · 29778524m"）
  - 修复：derivePartId 统一 `(msg, type, ordinal)` 契约（SSE started/ended/delta + REST content 按类型计数对齐）；mergePart 时间回退链（started 本地时刻→ended/REST 真实时间戳）；mergePartsList 增加 dedupOverlappingTextParts（契约演进期内容重叠去重兜底）
  - 验证：V2PartIdContractTest 4 用例（TDD 红→绿）；1610 全量单测绿；真机 Room DB part id 全部新契约无碰撞；模拟器实测 "Thought for 210ms" 时长正常 + 无重复渲染

- [x] **#110 多服务器共享状态批次（D2-02/D2-12/D2-13/D2-24；D2-11 评估）——已修复 2f0aa0cc** `race` `multi-server`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：pendingInputs HashMap 跨服务器并发（D2-02）；状态容器 sessionId 单键无 serverId 维度（D2-11）；currentServerId 单值被覆盖 → L3 校验打错服务器（D2-12）；isConnected 语义 = job 活跃非连接（D2-13）；McpRepositoryImpl 共享 connection（D2-24）
  - 方案：ConcurrentHashMap/按 serverId 隔离；复合键 (serverId, sessionId)；去掉 currentServerId 单值；isConnected 返回真实标志
  - 2026-08-14：D2-02 随 #98（pendingInputs→ConcurrentHashMap+每连接清空+有界）；D2-12（session→server 归属映射，L3 校验优先归属）；D2-13（isConnected 真实连接标志）；D2-24（McpRepository 显式 conn 参数）
  - 评估：D2-11（sessionId 单键）不改——实证（2026-08-14）：V2 sessionId 为 ses_ + 20+ hex 随机（碰撞概率 2^-80 数学上不可能）；StreamingOwnershipRegistry 已处理同后端多配置去重；复合键需改全部 handler/UI 的 Map<String,...> 键 + 破坏存档/分页键格式，防护对象不存在 → 记录为已验证不改
  - 工时：~1-2d | 难度：中 | 涉及：SseClientV2/各 handler/SessionStateService/SseConnectionManager/McpRepositoryImpl | 优先级：P1

- [x] **#111 dataSync 前台服务 6h 时限（D2-04，Android 15+）** `android` `service`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-04（B 路）
  - **2026-08-14 修复完成**：OpenCodeConnectionService 覆盖 `onTimeout(startId, fgsType)`——可观测日志（时限 + 当前活跃服务器）→ super 默认 stopSelf → 有活跃连接时延迟 2s 重启服务（新 6h 周期），已配置自动连接的服务器由 onCreate → autoConnectConfiguredServers 自动恢复。权限齐全（FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC）。编译 + 单测通过；6h 时限无法加速验证，需真机长时间运行确认（可观测日志 "FGS dataSync timeout"）
  - 问题：targetSdk 36 + foregroundServiceType=dataSync + 0 处 onTimeout → Android 15+ 每 6h 系统终止服务，手动连接静默丢失
  - 方案：覆盖 onTimeout（快速重连/通知用户）；评估 FGS 类型；纳入可观测性日志；真机验证
  - 工时：~0.5d | 难度：低 | 涉及：OpenCodeConnectionService/Manifest | 优先级：P0

- [x] **#112 通知链路竞态批次——结案（D2-L30 已修 8ba18844；D2-14 N/A；D2-18 按设计）** `notification`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-14/D2-18/D2-L30（B 路）
  - 问题：任务完成通知先标记去重后查抑制 → 抑制场景通知静默丢失；提问轮询 30s 无门控（通知关闭仍打 REST）；SessionIdle 通知依赖 250ms 固定延迟
  - 方案：先预检抑制再标记；轮询退避/门控；事件驱动或多次轮询
  - **2026-08-19 盘点 + 处置**：① **D2-14 N/A**——现行 showTaskCompleteNotification 已无任何去重标记（grep 无 markTask*），仅 shouldSuppressEvent 紧邻 notify 检查（AppNotificationManager:273），审计前提（先标记后抑制）已随后续重构消失；② **D2-18 按设计**——轮询已双职责：mergeQuestionsFromREST 供 UI 状态（提问卡 tool 补全/列表标记），通知投递本身已被 notificationsEnabled 门控（OpenCodeConnectionService:467），「关闭通知停 REST」会破坏 UI 状态链——审计前提过时；③ **D2-L30 已修（8ba18844）**——response-ready 检查改最多 3 次重试（间隔 250ms，首次命中即通知，慢设备/长末段不再静默丢通知；无输出会话最坏 750ms 后台等待）。验证层级：编译 + 全量单测绿（重试加固最坏行为与原版一致——3 次后放弃 vs 1 次后放弃，正常路径 250ms 首次命中即通知不变；通知管线 E2E 已在此前批次多次覆盖，慢 reducer 场景无法确定性构造）。**#112 结案：1 修复 + 1 N/A + 1 按设计**
  - 工时：~0.5d | 难度：低-中 | 涉及：OpenCodeConnectionService/AppNotificationManager | 优先级：P2

- [x] **#113 UI 状态竞态批次（D2-06/26/L66/L67）——已修复 58a5e0d5** `ui` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/D 路）
  - 问题：冷启动草稿不回填（视觉丢失）；快速连切设置丢修改；clearDraft 与 saveDraft 并发；QuestionCard 答案旋转丢
  - 方案：LaunchedEffect(draftText) 初始化；设置写串行化（Mutex/单消费者）；clearDraft 走同一写通道；rememberSaveable
  - 2026-08-14：D2-06（LaunchedEffect(draftText) 回填 + userHasTyped 防覆盖）；D2-26（settingsWriteMutex 写链）；D2-L66（clearDraft 走 persistMutex）；D2-L67（QuestionCard 全 saveable）
  - 工时：~0.5d | 难度：低 | 涉及：ChatScreen/ChatViewModel/SettingsViewModel/DraftInputDelegate/QuestionCard | 优先级：P1

- [x] **#114 认证头统一（D2-27，147 处内联）——已修复 89725d11** `network` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-27（E+A 路，grep 实测 147 处）
  - 问题：Authorization 逐请求内联 + Auth 插件空 install → 认证演进改 147+ 处，新端点易漏挂头 401
  - 方案：配置 Auth provider 或抽 auth(conn) 扩展统一替换；V1/V2 双轨同步
  - 2026-08-14：新增 AuthHeader.kt auth(conn) 扩展（Auth 插件空 install 不适合多服务器——认证是每服务器属性而 HttpClient 全局单例）；147 处内联全部替换（5 文件）
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/NetworkModule | 优先级：P1

- [x] **#115 移动端生命周期批次（D2-16/D2-17/D2-L23/D2-L24/D2-L25 全完成）——已修复** `android`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-16/D2-17/D2-L23~L25
  - 问题：无低内存回调；崩溃无条件重启（死循环风险）；手动连接进程死亡不恢复；20+ 处对话框 remember 非 saveable；FileViewerOverlay VM 重建丢批注
  - 方案：onTrimMemory 分级清理；重启退避（10min 内最多 1 次）；记录 lastConnected 恢复；rememberSaveable 批量迁移（触发条件注意：旋转由 configChanges 处理，主要覆盖 recreate 场景）
  - 2026-08-14：D2-16（onTrimMemory 清理 ToolSnapshotCache）；D2-17（崩溃重启退避 10min/1 次防死循环）；D2-L24（HomeScreen pendingConnectServerId → rememberSaveable）
  - ✅ 2026-08-14 补齐：D2-L23（355a707b，进程级 holder 按 (server,filePath) 暂存批注，VM 重建 restore，提交清除）；D2-L25（4ccd9ed4，6 处输入类对话框状态 → rememberSaveable：renameText/newFolderName/newCategoryName/name/selected；可见性标志保留 remember——重建后关闭为合理默认）
  - 工时：~1d | 难度：低-中 | 涉及：OpenCodeApp/OpenCodeConnectionService/各 Screen | 优先级：P1-P2

- [x] **#116 终端批次（D2-20 输入乱序 + D2-21 dispose 取消清理协程）** `terminal` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-20/D2-21（A 路）
  - 问题：socket.send fire-and-forget 多线程乱序；dispose() 在清理协程完成前 scope.cancel() → 服务端 PTY 残留
  - 方案：单发送 actor/Mutex；dispose 先 await 清理完成再 cancel
  - 工时：~0.5d | 难度：中 | 涉及：ServerTerminalWorkspace/PtyToTermlibAdapter | 优先级：P2

- [x] **#117 死代码/弃用/重复代码清理批次（D2-L1~L22 + D2-L15 日期统一 + D2-L16 剪贴板 + D2-L52 死参数）** `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md 簇 A/B/F（多路命中）
  - 问题：@Deprecated 委托链 ×9、桩方法 ×4、无调用方 API ×6、WebView 死分支 ~15KB（useNativeUi=true）、SimpleDateFormat 14 处、剪贴板 9 处、rejectHtmlResponse 复制、exportSessionToStream 整方法复制、ChatTerminalView snackbar 参数遮蔽、异常传播三套并存（D2-33，getOrThrow/Result/裸 List + ApiError 双重语义）等
  - 方案：清理日集中删除（先 grep 测试引用）；抽 DateFormatters/copyToClipboard/WebView 工厂；WebViewScreen 死分支删除需先确认无入口
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3
  - ✅ 2026-08-15 处理结果（D2-L1~L22 + D2-L15/L16/L52）：
    - ✅ D2-L4 connectToInstanceEvents 删除（~88 行，grep 无调用方）；D2-L5 AppLoadingEdge.kt 死组件整文件删除；D2-L6 TruncationBanner 删除 + isExtremelyLarge/正常分支 CodeWebView 合并单一调用点
    - ✅ D2-L10 getAllServers() 别名删除（ServerRepositoryImpl 直接用 servers）；D2-L14 7 个解析器 TAG 改为各自类名；D2-L18 AmoledSurfaceOverrides 抽取（动态取色 + 静态 AMOLED 共用 8 色）
    - ✅ D2-L20 applyAppLanguage(context) 抽取（LocaleUtils，MainActivity+OpenCodeConnectionService 复用）；D2-L21 fetchAllSessions() 提取（loadSessions/refreshSessions 去重 ~40 行）；D2-L22 textColor 死条件化简
    - ✅ D2-L52 ChatTerminalView 删除函数内 remember 遮蔽，改用传入参数（ChatScreen host 生效，terminal snackbar 不再静默丢失）
    - ✅ D2-L15 部分：DateFormatters 抽取 + 8 文件 11 处迁移（ShareTargetPickerDialog/TaskSheet/QuickNavigateSheet/MessageBubble/SyntheticNotificationCard/ContextDetailDialog/DiagnosticsScreen/OpenCodeApp）；SessionRow(#120)/DebugLogger(#102) ⚠️ 保留
    - ✅ D2-L16 部分：copyToClipboard 抽取 + 7 处迁移（CopyButton/ChatScreenBottomBar/ToolCardScaffold/ServerProvidersScreen/FileViewerOverlay/SessionListViewModel/DiagnosticsScreen）；ChatMessageList(#103)/ChatScreen(编辑协议) ⚠️ 保留
    - ⚠️ D2-L1 @Deprecated 委托链（EventDispatcher/ChatRepositoryImpl/ChatRepository/MessageEventHandler）→ #103 正在处理 ChatRepository 系文件，收敛 upsertMessages(MergeStrategy) 需协调
    - ⚠️ D2-L2 部分：switchSession/switchAgent 桩已删除（接口+实现+测试）；sendMessage 占位/replyQuestion → ChatRepositoryImpl 为 #103 文件保留
    - ⚠️ D2-L3 无调用方 API（getActiveToolProgress/getStepProgress/getCompactionState）→ ChatRepository 系为 #103 文件保留
    - ⚠️ D2-L7 WebView 死分支（useNativeUi=true，~15KB）→ WebViewScreen/WebViewNav 为 #121 涉及文件，删除需协调（#119 已登记待确认）
    - ⚠️ D2-L8 部分：NavGraph URLDecoder 死导入已删；ChatMessageList/ChatViewModel 残留 → #103 文件保留
    - ⚠️ D2-L9 deleteMessagePart 返回 false → 需产品决策（可区分异常或 UI 隐藏入口）；V2ApiClient 为 #121 文件
    - ⚠️ D2-L11 exportSessionToStream 整方法复制 → #121 正在处理 V1/V2ApiClient（顺带修 L-4），抽公共方法需协调
    - ⚠️ D2-L12 V2SseMapper partLocator / D2-L13 V2 会话映射两份 / D2-L19 扩展名→语言映射 ×3 → 分别为 #121（V2SseMapper/V2Mappers/CodeWebView）文件保留
    - ⚠️ D2-L17 directoryHeader 2 处内联 → SseClientV2 为 #122 文件保留
    - ⚠️ 异常传播三套并存（getOrThrow/Result/裸 List + ApiError 双重语义）→ 架构主题需独立设计（D2-33 的 prefetchGitCount 部分已随 #134 完结）

- [x] **#118 构建/安全批次（D2-28 cleartext + D2-29 R8 keep-all + D2-L64 版本倾斜/测试默认值 + D2-L28 备份密钥）** `build` `security`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-28/D2-29/D2-L28/D2-L64
  - 问题：明文流量全局放行无白名单；R8 keep-all 整库保留；Kotlin 2.3.21 + force metadata 2.4.0；isReturnDefaultValues；备份恢复后 Keystore 密钥缺失
  - 方案：networkSecurityConfig 白名单化；R8 收窄；升级 Kotlin 后移除 force；备份规则排除凭据文件
  - 工时：~1d | 难度：中 | 涉及：Manifest/proguard/build.gradle.kts/SecretCipher | 优先级：P2
  - **2026-08-15 修复完成**：D2-28 networkSecurityConfig 白名单化（默认禁明文 + localhost/127.0.0.1/10.0.2.2 白名单，Lint 显式 includeSubdomains）；D2-29 R8 收窄（io.ktor 全库保留 → 序列化/SSE/OkHttp/utils 子集，移除 kotlinx.coroutines 全库保留——release 构建 + 模拟器连接冒烟通过：Connected + 会话列表正常）；D2-L28 核实已覆盖（backup_rules/data_extraction_rules 已排除 datastore/ 含加密密文）；D2-L64 评估保留（isReturnDefaultValues 为 mockk 标准测试配置；kotlin-metadata force 2.4.0 为 Mikepenz 0.43.0 依赖所需，注释已说明）

- [x] **#119 第一期报告状态回写（C-1/H-1/H-2/H-3/M-9 已修复）** `docs`
  - 来源：audit-2026-08-13-dimensions/REPORT.md §6.1（c0c74a4c 实证）
  - **2026-08-14 完成**：REPORT.md 五处条目（C-1/H-1/H-2/H-3/M-9）标题加"✅ 已修复（2026-08-13 c0c74a4c）"标记；backlog #93/#94 状态转正 [x]（grep 实证三处 WebView 销毁齐全 + 降采样齐全）；WebViewScreen 不可达（useNativeUi=true）确认删除项另登记
  - 问题：第一期 REPORT.md 的 C-1/H-1/H-2/H-3/M-9 仍标记未修复，实际已由提交 c0c74a4c 落地（2026-08-13 23:39）；backlog #93/#94 状态需转正
  - 方案：回写第一期报告状态 + 同步 backlog；WebViewScreen 已不可达（useNativeUi=true）需另行确认删除
  - 工时：~0.5h | 难度：低 | 优先级：P0（文档准确性）

- [x] **#120 Markdown/文案一致性批次——全部完成（D2-08 已修 78e38e3a；盘点核实 D2-07/09/10/32 先前已修）** `markdown` `i18n` `ui`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/E 路）
  - 问题：① 跳转预渲染 fallback 用未归一化原始文本（MessageCardUser.kt:136 vs ChatMessageList.kt:442）→ 跳转目标首帧排版突变；② ClickableMarkdown 用 indexOf 定位可点击项（:95/:135）→ 重复文本段落点击/下划线错位；③ RetryBanner 双占位符恒显示 N/N（:49）；④ CompactionBanner 硬编码英文（:79）；⑤ SessionRow 硬编码英文 Diff 文案（:367-368）
  - 方案：jumpMdState 前 normalizeForRender；AST offset/span range 映射点击；文案改单占位符；提取资源补齐 14 语言
  - 工时：~0.5d | 难度：低-中 | 涉及：MessageCardUser/ClickableMarkdown/MarkdownTable/RetryBanner/CompactionBanner/SessionRow | 优先级：P2
  - **2026-08-19 D2-08 修复（78e38e3a）✅ E2E 闭环（两轮独立复验交叉确证）**：ClickableMarkdownResult 增预计算 `ranges`（items 一一对应的绝对字符区间）——Link 优先匹配链接 span（精确 offset，文档序消费 + 文本校验）；span 不可用/CodePath 走顺序文本搜索（全局游标单调推进——重复文本依次消费各自出现位置）。单测 +1（同文本双链接区间不重叠各归其位）。E2E：tap 第二 docs → example.com/b、tap 第一 docs → example.com/a（**两轮四 tap 全部差分路由正确**，uidump 地址栏 ground truth，link_02/03）；无错位/游离下划线（D2-08 回归信号缺失）；FATAL=0；会话已清理。视觉子断言勘误：像素级实证 docs 文本为深色无下划线（两轮一致）——属主题链接样式现状（深色主题下不显眼），非本修复回归，如需改进另行登记

- [x] **#121 V1/V2 双客户端一致性批次——全部闭环（D2-22/31 修 498fb643；D2-23 盘点已修 #109；D2-30 盘点已解决）** `consistency` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/E 路）
  - 问题：① rejectHtmlResponse 两处复制且 V1ApiClient 无 HTML 防御（V2ApiClient.kt:113/V2Mappers.kt:124）；② V2SseMapper 把 ordinal 当时间戳（:125/:151）；③ 6 处 WebView 初始化样板不统一（销毁策略各异）；④ V2 fs.list 路径推导绕过 PathUtils（V2ApiClient.kt:1157-1166，Windows 服务器必错）
  - 方案：rejectHtmlResponse 提公共 + V1 接入；SSE 时间取服务器字段；抽 WebView 工厂；改 PathUtils.fileName/joinPath
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/V2SseMapper/WebView 各文件 | 优先级：P2
  - **2026-08-19 盘点 + 部分修复（498fb643）**：① D2-22 ✅——rejectHtmlResponse 提公共（data/api 包级函数，带可选日志；两份私有复制删除）+ V1ApiClient.listSessions 接入（版本误判时 ContentTransformationException → 可读 NonJsonResponseException）；② **D2-23 盘点已修**——#109（5b749536）已实现时间回退链（start=本地时刻/0L+end=now，:196/:212/:225 注释在位），条目过时；④ D2-31 ✅——name 推导改 PathUtils.fileName（Windows 反斜杠 `C:\a\b` 旧 substringAfterLast('/') 返回整串）+ absolute 拼接改 joinPath；单测 +1 反斜杠回归。③ **D2-30（WebView 工厂）仍待做**
  - 验证层级：防御性数据层修复——全量单测绿（含 listDirectory/V1 listSessions MockEngine 回归）；D2-31 Windows 真实服务器分支以单测反斜杠用例覆盖（本地无 Windows 服务器）
  - **2026-08-19 D2-30 盘点：已解决，无需工厂**——审计时点（08-13）后 #93（c0c74a4c）统一了销毁：现存 6 处构造点销毁全覆盖（WebViewScreen onDispose:133 / ErrorPayloadContent onRelease:115 / RenderWebView onDispose:67 / PdfViewer onDispose:84 含 JS 桥移除 / CodeWebView onDispose:202 含 cleanup+桥移除 / WebViewWarmer 预热后自毁）。初始化差异为**按用途安全姿态**（error=JS 全禁、html=禁文件访问、pdf=file URL 供 pdf.js worker、browser=混合内容放行、code=JS 桥+自定义 UA）——各点位注释在位，抽工厂会把安全敏感配置藏进预设反而降低可审计性。**#121 四子项全部闭环，结案**

- [x] **#122 状态性能与 AI Agent 功能批次——三子项全部闭环（D2-25 e3cde191+E2E；D2-15 a7f07039；D2-19 盘点服务器不支持）** `perf` `sse` `ai-agent`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：① SessionStateService 每 SSE 事件对 _fsmStates/_histories 整张 Map 拷贝 + mapValues 全量派生（:184-190/:212-216）→ 流式 GC 压力；② SSE id: 帧被忽略、无 Last-Event-ID 续传（SseClientV2.kt:182-184）→ 断连窗口事件可能永久缺失；③ PermissionAutoApprover.shouldAutoApprove 全库无调用方 → 自动批准规则从未生效（功能失效）
  - 方案：toMutableMap 单次拷贝 + history 定长 + mapValues distinctUntilChanged；重连带 Last-Event-ID/游标循环补漏；在 PermissionAsked 路径接入自动 reply 或移除 UI 入口
  - **2026-08-18 D2-25 接线完成（e3cde191）**：EventDispatcher.processEvent 的 PermissionAsked 分支挂钩 maybeAutoApprovePermission（规则匹配→异步 respondPermission("once")；独立 IO scope 失败仅 WARN；空规则天然关闭）。WiringTest 3/3。⚠️ 待用户真机验收：设置页存规则后权限自动通过
  - **2026-08-19 模拟器代验收 ✅（全链路 E2E，用户授权）**：关闭 2026-08-16 全自动允许开关（DataStore 字节级验证 0x00）→ 权限卡正常弹出（需要权限/拒绝/仅一次/始终允许三按钮 dump 铁证）→ 点「始终允许」+ 确认对话框 → reply=always success + **本地规则落库**（DataStore `permission_auto_approve_rules` 字节实证）→ 新 PermissionAsked 到达 → **`[auto-approve] rule matched … replying once` 日志 + 7ms 后服务器 PermissionReplied 回执**——规则匹配→自动应答→服务器接受全链实证，无卡弹出。测试现场已还原（规则删除、开关复原 TRUE、服务器配置 diff=0 重启复验探针 allow）。⚠️ 方法论存档：beta-17595 需临时在 agent permissions 加 ask 规则（**last-match-wins，ask 必须放 allow 之后**）+ POST /session/{id}/permission 评估端点触发询问——默认配置全放行时无自然询问（详见新增 beta-17595 兼容发现条目）
  - **2026-08-19 D2-19 盘点结案：服务器不支持，客户端已有最优缓解**——协议实测（curl）：① 服务器 id 在 data JSON 内，**无协议级 `id:` 行**（SSE 标准前提缺失）；② 断连窗口内触发消息事件后带 `Last-Event-ID` 头重连 → **只收到新 server.connected，零事件回放**（/tmp/verify-dm/sse_replay.txt）——beta-17595 忽略该头，实现客户端发送是无用功。既有缓解已覆盖高价值路径：消息内容 = backfillActiveForServer 游标增量补漏（8bbcb216）；会话状态 = L3 REST 校验 + reconcileWithActiveSessions 双向对账（2026-08-16）。残留缺口（断连窗口完成通知丢失）归入上游候选 #146②（服务器补事件重放/快照端点）。**#122 全部结案**

- [x] **#130 V2 question 工具协议迁移——已适配 form API（真机 E2E 验证通过）** `v2` `question` `form`
  - 背景：2026-08-14 官方回复（issue #42541）——V2 question 工具由 form 服务驱动：`form.created` SSE（metadata.kind=question，fields q0/q1...，option 含 value/label）、回复 `POST /api/session/{id}/form/{formID}/reply` `{"answer":{"q0":..}}`、取消 `.../cancel`、轮询 `GET /api/form/request`；旧 question.asked + /api/question/request 是 stale surface
  - 实现（commit 5993c1a9 + 547bb204）：
    - 新增 `V2FormMapper`（data/api/v2）：form.created/replied/cancelled → QuestionAsked/QuestionReplied/QuestionRejected（仅 kind=question 映射，复用现有提问卡片管道零 UI 改动）；REST form → QuestionRequest DTO；`buildAnswerBody` 构造 answer map（label→value 映射：UI 提交 label，服务器收 value）
    - `SseClientV2.handleEvent`：form.* 事件分支（V2SseMapper 前）
    - `V2ApiClient`：listPendingQuestions 改走 GET /api/form/request；新增 replyToForm/rejectForm
    - `MessageApi`：replyToQuestion 加 question 参数（V2 form 需要 sessionId+key/value）、rejectQuestion 加 sessionId；V1 分支原样
    - 领域模型：QuestionAsked.Question 加 key、Option 加 value（V1 均为 null 兼容）
  - 验证（2026-08-14 真机 PLK110 + V2Real 4199）：
    - ✅ form.created → QuestionAsked 映射（logcat: `[recv] QuestionAsked` → `[dispatch] -> QuestionEventHandler`）+ 卡片渲染（单选/多选/Q tabs/自定义输入）
    - ✅ 提交：label→value 映射正确（UI 选"米饭"提交 `{"q0":"rice","q1":["Water","Coffee"]}`，服务器 state=answered，agent 回复确认收到答案）
    - ✅ 取消：`POST .../cancel` 204 → form.cancelled SSE → 卡片消失，服务器 state=cancelled
    - ✅ 轮询兜底：App 每 30s GET /api/form/request（logcat 实证）
    - ✅ V1 回归：V1 轮询仍走旧 /question 端点（代码未动）
    - ✅ 单测：V2FormMapperTest 10 个用例（映射/REST/answer 构造）+ V2ApiClientTest form 端点路径
  - 备注：form 字段类型仅映射 string（单选）/multiselect（多选），number/integer/boolean/external 暂不支持（question 工具不产生）；文档见 docs/opencode-api-reference-v1.md §12A（原 opencode-api-reference.md，2026-08-21 更名）

- [x] **#131 V1 协议 question 卡片嵌入渲染失败（数据到达但 UI 不显示）——已修复 eab5f964** `question` `v1`
  - 现象：V1 服务器（1.18.18）agent 调用 question 工具（4 题多选）——服务器 /question 正常返回（含 tool.messageID），App 轮询/loadPendingQuestions 均拉到（`Replaced 1 questions for session ...`），但 UI 问题卡片不渲染（goon 的 assistant 消息气泡内无 QuestionCard）
  - 对比：V1 单选卡片（首个问题）能正常显示——当时 question 经 SSE 事件到达或 tool 关联正常
  - 疑点：① tool.messageID 与消息列表 id 匹配（截断/完整 id 差异）；② 已完成语义（step-finish）下嵌入逻辑不触发；③ unembedded 独立卡片也未显示 → 更可能是 pendingQuestions 未进 UI combine 或渲染条件不满足
  - 证据：docs/dialogue-e2e-test-runbook.md（V1 question 实测记录）；logcat `Replaced 1 questions` + UI 无卡片
  - 待办：深挖嵌入/独立卡片渲染条件，V1 全生命周期 E2E 前置
  - 工时：~2h | 难度：中 | 涉及：ChatMessageList/QuestionEventHandler/MessageDataDelegate | 优先级：P1（阻塞 V1 question 功能 + #126 验证）

- [x] **#132 调试通道模块（adb 外部参数一键直达会话列表）** `devtools` `debug`
  - 来源：2026-08-14 用户需求（真机调试效率）
  - 问题：真机调试需手动输入 URL/账号/密码（每次配置易错）；调试连接无一键入口
  - 方案（最终形态）：仅 debug 构建，完全外部参数方式——adb am start --es debug_url/--es debug_username/--es debug_password/--es debug_name，App 幂等保存服务器 + 版本探测 + 连接 + 直达会话列表；无内置套餐/无 UI 入口（曾实现内置套餐后按用户要求移除，避免维护负担）
  - 待办：设计套餐数据模型（serverUrl/user/password/name/autoConnect）+ 注入方式（gradle BuildConfig 字段 / debug manifest meta-data / intent extra）；实现调试专用设置页或启动分流
  - 工时：~0.5-1d | 难度：低-中 | 涉及：ServerConfig/连接层/启动导航 | 优先级：P2
  - 验证（2026-08-14 真机 PLK110 通过）：adb am start 完整参数方式（debug_url=http://192.168.110.53:4199 + username/password/name）冷启动直达 V2 会话列表（幂等复用 a7e67a30；logcat 三连证据链；错误 0）；联动修复：版本探测失败不再降级 apiVersion（V2 被降 V1 → SPA HTML 解析错误的根因）
  - 实现：commit 20017337 + f14043a7（移除内置套餐，仅参数方式）；用法见 docs/debug-channel.md

# ============ 2026-08-14 审计遗漏补登（交叉验证：需求↔代码一致性） ============

> 背景：精确核对 audit-2026-08-13-dimensions + memory-perf 两份报告的 161 个发现，40 项未登记。
> 用 4 个并行 subagent 逐项读码交叉验证（+主会话抽查复核），结论：37 UNFIXED / 2 FIXED / 1 N_A。
> 37 项 UNFIXED 按性质分 5 批；FIXED/N_A 单独记录。

- [x] **#133 审计遗漏批次 1：连接稳定性（D2-L26/L27/L40/L41，4 项 UNFIXED）** `stability`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：4/4 UNFIXED）
  - D2-L26 OpenCodeConnectionService.kt:626 newWakeLock(PARTIAL).acquire() 无超时兜底；释放仅正常断开路径 → acquire(timeout)+周期续期
  - D2-L27 OpenCodeApp.kt:84 崩溃日志文件名秒级分辨率，同秒两次崩溃互相覆盖 → 加纳秒/序号
  - D2-L40 SseConnectionManager.kt:116 startConnection 裸 cancel() vs reconnectServer cancelAndJoin() 不一致 → 统一
  - D2-L41 NetworkMonitor.kt:91 onCapabilitiesChanged 失去 VALIDATED（captive portal）时状态卡旧值 → 补非 validated 分支
  - 工时：~0.5d | 难度：低-中 | 涉及：见各条 | 优先级：P1（连接稳定性）

- [x] **#134 审计遗漏批次 2：一致性/竞态（D2-L33/L36/L38/L39/L47/L54/L57/L62，8 项 UNFIXED）** `consistency`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：8/8 UNFIXED）
  - D2-L33 WorkspaceViewModel.kt:168 prefetchGitCount 无 in-flight 保护，切面板双发 VCS status
  - D2-L36 ServerSettingsViewModel.kt:111 init 4 路并行加载各自 rebuildUi → loading 抖动无去重
  - D2-L39 TokenStatsTracker.kt:24 update() 裸读-改-写非 CAS（并发丢更新）
  - D2-L54 SessionEventHandler.kt:109 locallyClearedReverts.remove 仍在 _sessions.update lambda 内（CAS 重试重复执行副作用）
  - D2-L57 SettingsRepositoryImpl.kt:75 updateSettings 21 次独立 DataStore edit → 单一 updateAll（半套落盘风险）
  - D2-L62 MessageEventHandler.kt:300 persistSseUpdate 分两次读 _messages/_parts 非原子快照
  - D2-L38 DirectoryManager.kt:87 getServerPaths 失败也缓存空 ServerPaths() 无 TTL → 一次瞬时失败毒化整个 VM 生命周期
  - D2-L47 ChatErrorState.kt:36 错误态固定 5s 无退避自动重试（服务器不可达时无限请求）
  - 工时：~0.5-1d | 难度：中 | 涉及：见各条 | 优先级：P1（并发一致性）

- [x] **#135 审计遗漏批次 3：性能（D2-L42/L43/L44/L45/L46/L68，6 项 UNFIXED）** `performance`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：6/6 UNFIXED）
  - D2-L42 AppLogger.kt:198 shouldPersist 每次日志现场构造 mapOf（流式 50-90 条/s → 每秒数百次分配）
  - D2-L43 BashToolCard.kt:63 ANSI 正则每次重组现场编译
  - D2-L44 MarkdownContent.kt:110,125 normalizeMarkdown 内容变化时现场编译 2 个 Regex（流式每 token）
  - D2-L45 ReasoningBlock.kt:85 rememberInfiniteTransition 无条件运行——已完成/折叠思考卡片仍 60fps 动画帧
  - D2-L46 MarkdownTable.kt:196 每次 measure 全部单元格 3 遍 subcompose 无缓存
  - D2-L68 ImagePreviewDialog.kt:69 主线程 Base64 解码全量 data URL（仅加降采样未移线程）
  - 工时：~0.5-1d | 难度：中 | 涉及：见各条 | 优先级：P2（流式/渲染性能）

- [x] **#136 审计遗漏批次 4：安全/隐私（D2-L29/L51/L53/L55/L56/L58，6 项 UNFIXED）** `security`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：6/6 UNFIXED）
  - D2-L29 ServerProvidersScreen.kt:234 API key 输入框无 PasswordVisualTransformation（明文；ServerDialog:176 有遮蔽）
  - D2-L51 MarkdownPreviewDialog.kt:88 performHaptic(view,true) 硬编码触觉反馈无视用户设置
  - D2-L53 PermissionEventHandler.kt:46,59 文案 auto-approved/auto-denied 与真实语义不符 + release INFO/WARN 级别
  - D2-L55 ChatMessageList.kt:120 硬编码服务器模板字符串匹配（服务器改文案即静默失效）
  - D2-L56 SettingsDataStore.kt:138 SharedPreferences 与 DataStore 双写镜像无启动校验（两写间崩溃 → 语言漂移）
  - D2-L58 UpdateRepository.kt:161 .apk.part 临时文件进程被杀残留（check/restore 前不清理）
  - 工时：~0.5d | 难度：低-中 | 涉及：见各条 | 优先级：P1（明文凭据 + 文案误导）


  - **2026-08-15 修复完成**（commit 见 git log）：
    - #136：D2-L29 密码遮蔽 · D2-L51 触觉设置 · D2-L53 文案/级别 · D2-L55 模板变体 · D2-L56 镜像校验 · D2-L58 残留清理
    - #134：D2-L33 in-flight · D2-L36 loading 去重 · D2-L38 失败不缓存 · D2-L39 CAS · D2-L47 退避 · D2-L54 副作用移出 · D2-L57 单次 edit · D2-L62 append 幂等
    - #133：D2-L26 wakeLock 超时+续期 · D2-L27 毫秒时间戳 · D2-L40 cancelAndJoin 统一 · D2-L41 validated 分支
    - 新增单测：IsBackgroundMoveSyntheticTest(6) · SettingsLanguageMirrorTest(5) · TokenStatsTrackerConcurrencyTest(3) · DirectoryManagerServerPathsTest(3) · SessionEventHandlerTest +2

- [x] **#137 审计遗漏批次 5：清理/样式（D2-L31/L32/L34/L48/L49/L50/L59/L60/L61/L63/L65/N-01/N-02，13 项 UNFIXED）** `refactor`
  - 来源：audit-2026-08-13-dimensions + memory-perf（交叉验证 2026-08-14：13/13 UNFIXED）
  - D2-L31 FileViewerViewModel.kt:226 nextHunk 空 hunks → 索引 -1
  - D2-L32 NavGraph.kt:405 onNavigateToChildSession 无 launchSingleTop（同文件其余 9 处均有）
  - D2-L34 OpenProjectDialog.kt:318 创建文件夹按钮未随 isCreatingFolder 禁用 → 双击双发
  - D2-L48 sessions/ 目录裸 dp 145 处 vs SpacingTokens 4 处（令牌覆盖不均）
  - D2-L49 FileTreePanel.kt:151 / PdfViewer.kt:190 硬编码 alpha 0.4f/0.9f 绕过 AlphaTokens
  - D2-L50 ToolCardScaffold.kt:187 复制反馈 Toast vs Snackbar 双通道不统一
  - D2-L59 SettingsDataStore.kt:506 favoriteSessionIds 读 flow 内执行 edit 写（隐蔽副作用迁移）
  - D2-L60 FileRepositoryImpl.kt 仅 listDirectory 有 IO，其余 6 方法裸调用
  - D2-L61 MessageStore.kt:100 runCatching 吞一切异常（约束冲突本不抛，危害面小，IO 瞬态仍需降级日志）
  - D2-L63 OpenCodeApp.kt:156 onCreate 主线程 listFiles+解析崩溃文件名（未移 IO）
  - D2-L65 ChatScreen.kt:516 vs 763 onViewToolLambda 重复定义（内层死代码）
  - N-01 SessionFocusHolder.kt:44 shouldSuppress 分两次独立读非合并快照
  - N-02 SseClient.kt:171 rawSseEventFlow 零订阅者（死代码，注释称'V2 管线消费'不实）
  - 工时：~1d | 难度：低 | 涉及：见各条 | 优先级：P2（清理/样式，L32/L34 可提前）


  - **2026-08-15 修复完成**：
    - #135：D2-L42 级别映射预构造 · D2-L43 ANSI 正则预编译 · D2-L44 Markdown 正则预编译 · D2-L45 脉冲动画条件化 · D2-L46 表格测量缓存（探针/行高复用）· D2-L68 图片解码移 IO 线程
    - #137：D2-L31 空 hunks 防护 · D2-L32 launchSingleTop · D2-L34 防双击 · D2-L49 alpha→AlphaTokens · D2-L50 复制反馈 Snackbar 通道（LocalCopyFeedback）· D2-L59 收藏迁移显式化（flow 纯读）· D2-L60 FileRepositoryImpl 全 IO · D2-L61 runCatching→runCatchingCancellable（取消传播）· D2-L63 崩溃检测移 IO · D2-L65 死代码删除 · N-01 合并快照 · N-02 rawSseEventFlow 死代码删除 · D2-L48 sessions/ 58 处 dp→SpacingTokens（subagent 执行）

- [x] **#138 审计遗漏——交叉验证 FIXED/N_A 记录（D2-L35 FIXED + N-05 FIXED + D2-L37 N_A）** `docs`
  - 2026-08-14 交叉验证结论（非新问题，编号回写）：
  - D2-L35 FIXED：SessionListViewModel.kt:172 DataStore 写 markSessionRead 已移 viewModelScope.launch 异步（组合期调用仅内存操作，注释明确设计）
  - N-05 FIXED：SseConnectionManager.kt:212 isConnected 已由 #110 D2-13（commit 2f0aa0cc）改为真实连接标志（弃用 sseJob.isActive）
  - D2-L37 N_A（审计误报）：HomeViewModel.kt:265 connectToServer guard 主线程同步更新 connectingServerIds 先于 launch，同帧双击二次调用读到更新后状态提前 return——双发 testConnection 不可复现

---
