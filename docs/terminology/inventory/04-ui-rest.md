# 盘点：UI 层非聊天域（settings/server/connection/diagnostics/main/navigation/theme 等）

> Phase 1 事实收集（只记录事实，不做术语裁决）。路径前缀 `app/src/main/kotlin/dev/leonardo/ocbeacon/`。
> 范围：`ui/**/*.kt` 中路径不含 chat/dialog/message/scroll/markdown 的全部文件（与 03 聊天域盘点互补）。
> 状态：✅ 完成（113/113 文件全部精读）。CONTEXT.md 既有词条：渲染供给、流式 turn、跳转稳定窗口、红点时钟域、必需协作者、状态簇、版本 seam、连接生命周期协调。
>
> 语言现状统计（覆盖清单 113 文件）：中文 99（其中 4 个以中文为主但混有英文小节标记如 `// Language`）· 无注释 14 · 纯英文 0。大文件（SessionListViewModel 809 行、NavGraph 476 行、ServerSettingsViewModel 543 行等 8 个 >400 行文件）全部分段读完，无只 grep 未读全文的情况。

## 覆盖清单

| 文件 | 状态 | 注释语言 | 备注 |
|---|---|---|---|
| ui/FlowDefaults.kt | ✓ | 中文 | WhileSubscribed5s 策略；提及 SSE 管线/重连风暴 |
| ui/components/AmoledCard.kt | ✓ | 中文 | AMOLED 卡片/边框族；KDoc 称 65% 实为 AlphaTokens.MEDIUM=0.70 |
| ui/components/AppPickerList.kt | ✓ | 中文 | 「选择器对话框」可复用单选列表 |
| ui/components/ConnectionErrorScreen.kt | ✓ | 中文 | 服务器不可达全屏错误 + 重试倒计时 + 切换服务器 |
| ui/components/DetailRow.kt | ✓ | 中文 | 双列详情行；提及「会话详情/目录详情对话框」 |
| ui/components/EmbeddedCardContainer.kt | ✓ | 中文 | 内嵌卡片基础容器；提及提问卡/QuestionCard/FileCard/assistant 气泡（聊天域词） |
| ui/components/ProviderIcon.kt | ✓ | 中文 | provider→图标映射（models.dev）；「提供商」=provider |
| ui/components/SessionRetryCard.kt | ✓ | 中文 | 会话重试卡片：尝试次数/进度/倒计时/80 字符截断 |
| ui/components/UpdateInstallLauncher.kt | ✓ | 无注释 | APK 安装启动器，纯代码无注释 |
| ui/components/indicators/PulsingDotsIndicator.kt | ✓ | 中文 | 脉冲点指示器；加载/连接指示 |
| ui/navigation/NavGraph.kt | ✓ | 中文 | 主导航图：原生 UI/WebView 旧版、分享选择器、调试通道(#132)、深链、子会话(#137)；useNativeUi 硬编码 true |
| ui/navigation/Screen.kt | ✓ | 中文 | 12 条路由常量（home/sessions/chat/server_settings…/cross_favorites） |
| ui/navigation/routes/AboutNav.kt | ✓ | 中文 | about 路由，无术语 |
| ui/navigation/routes/DiagnosticsNav.kt | ✓ | 中文 | diagnostics 路由 |
| ui/navigation/routes/HomeNav.kt | ✓ | 中文 | home 路由，应用入口点 |
| ui/navigation/routes/NavUtils.kt | ✓ | 中文 | safeDecodeParam 安全解码（AGENTS 规则落地） |
| ui/navigation/routes/ServerModelFilterNav.kt | ✓ | 中文 | 「服务器模型过滤页」路由，参数 serverId |
| ui/navigation/routes/ServerProvidersNav.kt | ✓ | 中文 | 「服务器提供商页」路由，参数 serverId |
| ui/navigation/routes/ServerRouteParams.kt | ✓ | 中文 | 通用服务器参数 serverId；明言密码/URL 不经 Navigation 传 |
| ui/navigation/routes/ServerSettingsNav.kt | ✓ | 中文 | 「服务器设置页」路由，参数 serverId |
| ui/navigation/routes/SessionListNav.kt | ✓ | 中文 | 「会话列表页」sessions 路由，参数 serverId |
| ui/navigation/routes/SettingsNav.kt | ✓ | 中文 | settings 路由，无术语 |
| ui/navigation/routes/WebViewNav.kt | ✓ | 中文 | 「WebView 页（旧版）」；明文凭据不经导航传，Basic Auth |
| ui/navigation/routes/WorkspaceNav.kt | ✓ | 中文 | workspace 路由，参数 serverId/sessionId/directory |
| ui/screens/about/AboutScreen.kt | ✓ | 中文 | 关于页；更新检查卡（ENABLE_AUTO_UPDATE 门控）、GitHub/OpenCode/License 链接；少量英文小节标记 |
| ui/screens/about/AboutViewModel.kt | ✓ | 无注释 | 纯代码：更新检查/安装准备/标记安装器已启动 |
| ui/screens/home/HomeRoute.kt | ✓ | 中文 | HomeScreen 路由包装，仅绑定 VM |
| ui/screens/home/HomeScreen.kt | ✓ | 中文 | 首页服务器列表（网格/列表双布局）；#115 通知权限后恢复连接；电池优化横幅（Play 合规） |
| ui/screens/home/HomeViewModel.kt | ✓ | 中文 | 多服务器连接管理：testConnection、L-16 providers 检查 TTL、#34 重复后端预检、#128 取消传播、binder 泄漏修复 |
| ui/screens/home/components/BatteryOptimizationBanner.kt | ✓ | 无注释 | 电池优化横幅，纯代码 |
| ui/screens/home/components/EmptyServersView.kt | ✓ | 无注释 | 空服务器列表视图，纯代码 |
| ui/screens/home/components/ServerCard.kt | ✓ | 中文 | 服务器卡片：连接/断开/会话按钮、API 版本徽章（#86 V1/V2）、连接中可取消 |
| ui/screens/server/ServerModelFilterScreen.kt | ✓ | 中文 | 模型可见性过滤列表（L-15 remember 优化）；provider 分组+model 开关 |
| ui/screens/server/ServerProvidersScreen.kt | ✓ | 中文 | 已连接/可用 provider 分区；API key + OAuth（device code 提取/headless 回退/浏览器流）；env 来源不可断开 |
| ui/screens/server/ServerRoute.kt | ✓ | 无注释 | 三个 Route 包装（Settings/Providers/ModelFilter 共用 VM） |
| ui/screens/server/ServerSettingsScreen.kt | ✓ | 无注释 | 服务器设置菜单（providers/models 两入口），纯代码 |
| ui/screens/server/ServerSettingsViewModel.kt | ✓ | 中文 | provider 目录/连接状态/OAuth/API key 认证流；GlobalConfig(model/smallModel/defaultAgent)；V2 只读 #85/#172；#134 六路加载计数 |
| ui/screens/server/components/ProviderRow.kt | ✓ | 无注释 | ProviderToggle 行；source 取值 env/api/config/custom/other |
| ui/screens/sessions/DirectoryManager.kt | ✓ | 中文 | 目录浏览委托：盘符并行探测/30s 目录缓存/失败冷却（D2-L38）；ServerPaths home/cwd |
| ui/screens/sessions/ServerSettingsContent.kt | ✓ | 中文 | MCP 服务器区块 + 标签管理区块 |
| ui/screens/sessions/SessionListRoute.kt | ✓ | 中文 | SessionListScreen 路由包装 |
| ui/screens/sessions/SessionListScreen.kt | ✓ | 中文 | 会话/设置双页 pager；收藏筛选、一键已读、视图切换、快速新建（最近目录）、打开项目、重命名/删除/标签分配对话框 |
| ui/screens/sessions/SessionListStateBuilder.kt | ✓ | 中文 | 内容册构建纯函数：过滤/搜索/分类/收藏/树/未读（#171、#38）；Asking 状态合并 |
| ui/screens/sessions/SessionListUiState.kt | ✓ | 中文 | 低频/高频输入 + 内容册/外壳册拆分；未读/草稿/标签/收藏字段 |
| ui/screens/sessions/SessionListViewModel.kt | ✓ | 中文 | 多项目会话拉取（project.worktree）、游标分页、红点模块（#171）、堆积队列（#176/#177）、标签/收藏、MCP、目录浏览委托 |
| ui/screens/sessions/components/DirectoryPath.kt | ✓ | 中文 | 可浏览目录路径值对象：跨平台分隔符推断、盘符根/虚拟盘符选择器哨兵 :///drives、~/home 显示 |
| ui/screens/sessions/components/DirectoryRow.kt | ✓ | 中文 | 浏览器目录行（父路径淡显+叶子加粗）；手工 lastIndexOf 切分路径，未用 PathUtils/DirectoryPath 封装 |
| ui/screens/sessions/components/DirectoryTreeNode.kt | ✓ | 中文 | 目录树节点行（展开/活动会话计数加粗/长按详情对话框/复制路径/新建会话） |
| ui/screens/sessions/components/McpServerRow.kt | ✓ | 无注释 | MCP 行：状态点/色映射（connected/disabled/failed/needs_auth/needs_client_registration 字符串字面量） |
| ui/screens/sessions/components/SessionCategoryStyle.kt | ✓ | 中文 | 「标签/分类」样式映射（颜色/图标键，JSON 持久化；folder/star 历史兼容） |
| ui/screens/sessions/components/SessionListStates.kt | ✓ | 无注释 | 加载/错误/空三态组件，纯代码 |
| ui/screens/sessions/components/SessionRow.kt | ✓ | 中文 | 会话行：状态图标（Busy/Asking/Retry）+未读 Badge+草稿+Diff 摘要+标签横排+收藏星标；详情对话框（#177 继续队列、#120 i18n 修复） |
| ui/screens/sessions/components/SessionSearchBar.kt | ✓ | 中文 | 搜索栏+分类过滤 chips；内部 debounce 300ms（与 VM 层 #100 debounce 叠加=双重防抖） |
| ui/screens/sessions/components/SessionTreeList.kt | ✓ | 中文 | 会话树列表：滚动到底-3 预加载 loadMore、返回滚动恢复、#177 计数接线 |
| ui/screens/sessions/components/SessionUiHelpers.kt | ✓ | 中文 | isAmoledTheme() 单函数助手 |
| ui/screens/sessions/components/SettingsListRow.kt | ✓ | 中文 | 设置页列表行统一样式 |
| ui/screens/sessions/components/SettingsSectionHeader.kt | ✓ | 中文 | 设置页可展开区块标题 |
| ui/screens/sessions/components/TagChip.kt | ✓ | 中文 | TagBadge（展示小徽标）vs TagChip（交互 FilterChip）；内置收藏标签持久化中文名「收藏」→展示替换本地化 Favorites |
| ui/screens/sessions/components/TagManagementSection.kt | ✓ | 中文 | 标签管理区块：列表/关联会话展开/新建/编辑/删除确认；TagEditDialog 名称 label 用 category_name（tag/category 混用实例） |
| ui/screens/sessions/components/TreeNode.kt | ✓ | 中文 | 扁平两级树（Directory/Session）；注释记载「项目感知分组→纯目录分组」变更史 |
| ui/screens/sessions/util/SessionGrouping.kt | ✓ | 中文 | 项目感知分组（ProjectSessionGroup/projectId/worktree 前缀回退/波浪号标签） |
| ui/screens/settings/DiagnosticsScreen.kt | ✓ | 中文 | 诊断屏幕：级别过滤/搜索/导出分享/L-11 稳定 key/#151 上报对话框状态机渲染；导出文本含英文头 |
| ui/screens/settings/DiagnosticsViewModel.kt | ✓ | 中文 | 诊断日志流 + #151 GitHub 错误上报状态机（device flow/预览/提交/重试）；硬编码中文用户文案 |
| ui/screens/settings/SettingsRoute.kt | ✓ | 无注释 | SettingsScreen 路由包装，纯代码 |
| ui/screens/settings/SettingsScreen.kt | ✓ | 中文+英文小节标记 | 六大区块 + 9 个选择器对话框接线；引用 ChatBehaviorSection/ChatDisplaySection（路径含 chat→归 03 路） |
| ui/screens/settings/SettingsViewModel.kt | ✓ | 中文 | AppSettings 全量便捷属性+setter；#113 D2-26 设置写串行化；权限自动批准规则；通知自检 |
| ui/screens/settings/components/PermissionRulesSection.kt | ✓ | 中文 | 权限自动批准规则列表（toolName/directoryPattern，* 隐藏） |
| ui/screens/settings/components/SectionHeader.kt | ✓ | 无注释 | SectionHeader 单文本组件 |
| ui/screens/settings/components/SettingsDisplayNames.kt | ✓ | 中文 | 主题/语言/重连模式/图片最长边显示名助手 |
| ui/screens/settings/sections/AdvancedSection.kt | ✓ | 无注释 | 诊断入口单项 |
| ui/screens/settings/sections/AppearanceSection.kt | ✓ | 中文+英文标记 | 主题/动态取色（Android 12+ 门控）/AMOLED 深色三项 |
| ui/screens/settings/sections/AutoApproveRulesSection.kt | ✓ | 中文 | 自动允许所有权限开关（PermissionAsked→always 持久规则）+ 规则列表 |
| ui/screens/settings/sections/GeneralSection.kt | ✓ | 中文+英文标记 | 语言/重连模式两项；小节标记 // Language 与 // 重连模式 混用 |
| ui/screens/settings/sections/NotificationsSection.kt | ✓ | 中文+英文标记 | 通知/静默通知/通知自检（MIUI 厂商说明）/直达系统通知设置四项 |
| ui/screens/viewer/AnnotationContextMenu.kt | ✓ | 中文 | 系统文本上下文菜单注入「批注」项：剪贴板捕获选区+剥离行号 gutter 前缀 |
| ui/screens/viewer/AnnotationInputSheet.kt | ✓ | 中文 | 批注输入底部弹层（选中预览/note 输入/删除-编辑模式）；KDoc 称 note 为「修改说明」 |
| ui/screens/viewer/AnnotationManager.kt | ✓ | 中文 | 批注内存管理：char offset→line:col 转换、删除重编号、restore（Phase 4） |
| ui/screens/viewer/CodeWebView.kt | ✓ | 中文 | 代码 WebView：ActionMode 注入批注项（按标题匹配）、JS bridge 选区回传、滚动 300px 触发加载更多、避免滚动 DOM 重建 |
| ui/screens/viewer/DiffParser.kt | ✓ | 无注释 | unified diff hunk 解析（@@ 头正则/+/- 归类 ADDED/REMOVED/MODIFIED） |
| ui/screens/viewer/DiffView.kt | ✓ | 中文 | unified diff 渲染：元数据行过滤/hunk 导航（D4-005 直接索引滚动）/行颜色派生说明（D3-005） |
| ui/screens/viewer/FileType.kt | ✓ | 中文 | 文件类型枚举（TEXT/MARKDOWN/IMAGE/SVG/CSV/JSON/HTML/PDF）+扩展名映射；supportsRender/supportsSourceView |
| ui/screens/viewer/FileViewerEntryPoint.kt | ✓ | 无注释 | Hilt EntryPoint，纯声明 |
| ui/screens/viewer/FileViewerOverlay.kt | ✓ | 中文 | viewer 对话框宿主：独立 ViewModelStoreOwner + assisted factory；批注提交 Toast/Snackbar |
| ui/screens/viewer/FileViewerParams.kt | ✓ | 中文 | viewer 参数（serverId/sessionId/filePath/directory/source/toolPartIds）；来源 live/git_diff/tool_snapshot/tool_snapshot_diff |
| ui/screens/viewer/FileViewerScreen.kt | ✓ | 中文 | 查看器主屏：双面板（源码/渲染常驻+可见性切换）、DIFF↔SOURCE 切换、换行开关、批注提交对话框（overallNote+逐条编辑）、大文件警告横幅 |
| ui/screens/viewer/FileViewerUiState.kt | ✓ | 中文 | viewer 状态：SOURCE/DIFF 模式、DiffHunk、分页（Phase 4 替代 isTruncated）、工具快照（Phase 2 任务 9）、批注（Phase 3） |
| ui/screens/viewer/FileViewerViewModel.kt | ✓ | 中文 | 四入口（LIVE/GIT_DIFF/TOOL_SNAPSHOT/TOOL_SNAPSHOT_DIFF）；分页切片（Phase 4/#101 增量）；批注进程级暂存（#115 D2-L23，30s 窗口）；PDF ISO-8859-1 重编码 |
| ui/screens/viewer/HighlightBuilder.kt | ✓ | 中文 | 语法高亮+批注高亮构建器（snipme highlights 库/扩展名→语言映射） |
| ui/screens/viewer/PdfViewer.kt | ✓ | 中文 | PDF.js WebView 查看器（base64 注入/file:// worker 权限/翻页工具栏/错误态） |
| ui/screens/viewer/RenderHtmlBuilder.kt | ✓ | 中文 | CSV 表格/JSON 高亮/SVG 渲染 HTML 构建（含 CSV 引号解析器） |
| ui/screens/viewer/RenderWebView.kt | ✓ | 中文 | 统一 WebView 渲染器（marked.js+highlight.js 的 MARKDOWN/IMAGE/SVG/CSV/HTML；单实例复用） |
| ui/screens/viewer/WebViewWarmer.kt | ✓ | 中文 | 一次性 WebView V8 预热（300-500ms 首建开销/5s 超时/5-10MB 临时）；ChatScreen LaunchedEffect 调用 |
| ui/screens/webview/WebViewScreen.kt | ✓ | 中文 | 旧版 Web UI 宿主：Basic Auth 注入、深链 navigateUrlFlow、下拉刷新、主题 JS 注入、文件选择器 |
| ui/screens/workspace/FileTreeUtils.kt | ✓ | 中文 | 文件树扁平化（L-12 buildList 优化）+子树替换；ignored 过滤/expandedDirs 下钻 |
| ui/screens/workspace/WorkspaceScreen.kt | ✓ | 中文 | 工作区屏：双面板切换（非 git 静态图标）/搜索模式（#103 M-16 过滤 remember）/fileViewerRequest→FileViewerOverlay（LIVE/GIT_DIFF） |
| ui/screens/workspace/WorkspaceUiState.kt | ✓ | 中文(行内英文标记) | 双面板（FILE_TREE/GIT_CHANGES）状态：展开/loadingDirs/gitChanges/isNonGit/搜索（Phase 2/4 标记） |
| ui/screens/workspace/WorkspaceViewModel.kt | ✓ | 中文 | 目录 LRU 缓存（#98 M-13）/git 状态 prefetch 互斥（#134 D2-L33）/non-git 判定（错误消息字符串匹配）/文件搜索 300ms 防抖 |
| ui/screens/workspace/git/GitChangeItem.kt | ✓ | 中文 | Git 变更行：状态徽标 A/D/M（绿/红/tertiary）+ +additions -deletions 统计 |
| ui/screens/workspace/git/GitChangesPanel.kt | ✓ | 中文 | Git 变更面板：状态计数行（M/A/D）/非 git/working tree clean 空态；KDoc 称「diff 查看器将在后续阶段实现」（待核实） |
| ui/screens/workspace/search/SearchOverlay.kt | ✓ | 中文 | 搜索结果主体：文件树结果（服务器 findFiles）/git 变更结果（客户端过滤）；ellipsizeMiddle 中间省略 |
| ui/screens/workspace/search/SearchTopBar.kt | ✓ | 中文 | 搜索顶栏（规范 §6.4）：透明全宽 TextField+自动聚焦；「实时搜索，无提交动作」 |
| ui/screens/workspace/tree/FileTreePanel.kt | ✓ | 中文 | 文件树面板：懒加载展开/showIgnored chip（#149 testTag）/深度缩进；加载态图标降透明度 |
| ui/theme/Alpha.kt | ✓ | 中文 | 透明度令牌刻度；提及 @提及/diff/AMOLED |
| ui/theme/ButtonTokens.kt | ✓ | 中文 | 按钮样式令牌，无领域术语冲突 |
| ui/theme/Color.kt | ✓ | 中文 | 状态色/Agent 身份色（build/plan）/Diff 色/Queued 徽章色 |
| ui/theme/ItemTokens.kt | ✓ | 中文 | 行高令牌；提及「模型/agent 选择器」 |
| ui/theme/ListItemTokens.kt | ✓ | 中文 | 内边距令牌，无领域术语 |
| ui/theme/Motion.kt | ✓ | 中文 | 动效时长常量；提及呼吸/脉冲/终端转场 |
| ui/theme/Shape.kt | ✓ | 中文 | 形状刻度令牌，无领域术语 |
| ui/theme/SheetTokens.kt | ✓ | 中文 | 「主对话抽屉」高度令牌；含 2026-08-20 决策史 |
| ui/theme/Spacing.kt | ✓ | 中文 | 间距令牌，无领域术语 |
| ui/theme/Theme.kt | ✓ | 中文 | OpenCode 主题/AMOLED/动态取色/边到边；含 D2-L18 精简记录 |
| ui/theme/Type.kt | ✓ | 中文 | M3 排版 + 等宽 CodeTypography，无领域术语 |

## 术语观察

| 概念 | 观察到的变体 | 位置 文件:行 | 与 API 词一致? |
|---|---|---|---|
| SSE 事件流/连接 | 「SSE 管线」「重连风暴」 | ui/FlowDefaults.kt:9-10 | 传输层词，非 API 实体词 |
| @提及 | 「@提及高亮」(mention) | ui/theme/Alpha.kt:9 | 未用 API 词 |
| 连接状态指示 | StatusConnected/StatusFailed/StatusWarning，注释「需要认证 / 等待中」 | ui/theme/Color.kt:5-9 | 与 provider 状态语义接近，措辞口语化 |
| Agent 身份色 | 「Agent 身份色」+ agent 名 build（蓝）/plan（紫），注释称「匹配 TUI 的 opencode 主题（local.tsx）」 | ui/theme/Color.kt:15-24 | ✅ agent/build/plan 为 OpenCode API/内置词 |
| 排队徽章 | QueuedBadgeColor「金色背景」 | ui/theme/Color.kt:27-29 | queued 与 API part 状态词同源 |
| Diff 行 | DiffAdded「新增行」/ DiffRemoved「删除行」 | ui/theme/Color.kt:11-13 | diff 为通用词，非 API 实体 |
| 模型/代理选择 | 「模型/agent 选择器」 | ui/theme/ItemTokens.kt:12 | ✅ model/agent 均为 API 词 |
| 终端 | 「终端转场时长」 | ui/theme/Motion.kt:13 | 项目 UI 域词 |
| 底部抽屉/弹层 | 「主对话抽屉（ModalBottomSheet）」/「底部弹层」 | ui/theme/SheetTokens.kt:4-14；ui/theme/Theme.kt:94 | 纯 UI 词；同物两名（抽屉 vs 弹层） |
| AMOLED 模式 | 「AMOLED 深色模式」「纯黑表面」 | ui/theme/Theme.kt:92-113；ui/theme/Shape.kt:24 | UI 域词，全库统一 |
| 动态取色 | 「动态取色（Material You）」/dynamicColor | ui/theme/Theme.kt:112,119 | UI 域词，中英并用 |
| OpenCode 视觉标识 | 品牌色 Indigo/Cyan「OpenCode 视觉标识」 | ui/theme/Theme.kt:33 | 品牌词 |
| 服务器 | server/ServerConfig（displayName/url）、「服务器不可达」「其他可用服务器」 | ui/components/ConnectionErrorScreen.kt:41-56 | server 为项目域词（非 OpenCode API 实体，OpenCode 是单 server 多 provider） |
| 重试 | retry/重试倒计时/自动重试前剩余秒数/retryCountdown | ui/components/ConnectionErrorScreen.kt:47-56,73-79 | 与 SESSION_RETRY/SSE 重连语义同域 |
| 选择器 | 「选择器对话框」picker/AppPickerList | ui/components/AppPickerList.kt:34 | 纯 UI 词 |
| 内嵌卡片 | 「内嵌卡片」EmbeddedCardContainer；提问卡（QuestionCard/CollapsibleQuestionPart）/FileCard | ui/components/EmbeddedCardContainer.kt:14-26 | 聊天域词出现在 components 公共层 |
| 提供商 | provider/providerId/「提供商图标」（来源 models.dev） | ui/components/ProviderIcon.kt:16-30 | ✅ provider 为 OpenCode API 词；中文「提供商」 |
| 会话内重试 | 「聊天会话内显示重试状态」attempt/maxAttempts/countdownSeconds | ui/components/SessionRetryCard.kt:30-41 | retry 域词；attempt 与 SSE 重连策略同域 |
| 应用更新安装 | UpdateInstaller/APK/unknown sources（未知来源） | ui/components/UpdateInstallLauncher.kt:15-61 | 应用更新域词，非 OpenCode API |
| 服务器 ID | serverId/「服务器参数」「服务器 URL」 | ui/navigation/routes/ServerRouteParams.kt:8-40 | server 为项目域词（连接对象），非 OpenCode API 实体 |
| 服务器模型过滤 | 「服务器模型过滤页」server_model_filter | ui/navigation/routes/ServerModelFilterNav.kt:5-10 | model ✅ API 词；server 为项目域词 |
| 服务器提供商 | 「服务器提供商页」server_providers | ui/navigation/routes/ServerProvidersNav.kt:5-10 | provider ✅ API 词 |
| 跨服务器收藏 | cross_favorites 路由常量 | ui/navigation/Screen.kt:19 | 项目域词（收藏概念非 API 实体） |
| 会话 | 「会话列表页」sessions/sessionId | ui/navigation/routes/SessionListNav.kt:6-10；WorkspaceNav.kt:14 | ✅ session 为 OpenCode API 词 |
| 工作区/目录 | workspace（路由名）/directory（路由参数） | ui/navigation/routes/WorkspaceNav.kt:8-15 | directory ✅ API 词（会话工作目录）；workspace 为项目 UI 概念 |
| Basic 认证 | 「Basic Auth 头」「明文用户名/密码」 | ui/navigation/routes/WebViewNav.kt:8-14 | 连接域词（HTTP） |
| 深度链接 | deepLink/SessionDeepLink/sessionPath/「深度链接事件」 | ui/navigation/NavGraph.kt:58,168-223 | 通知/连接域词，sessionId ✅ API 词 |
| 调试通道 | debugChannelFlow/「#132 调试通道」（am start --es debug_profile） | ui/navigation/NavGraph.kt:59,159-166 | 项目域词 |
| 子会话 | 「子会话」childSession/onNavigateToChildSession（#137 防重复导航） | ui/navigation/NavGraph.kt:407-425 | ✅ 对应 OpenCode session share 的 child 会话语义 |
| 终端模式 | openTerminal/startInTerminalMode「终端模式」 | ui/navigation/NavGraph.kt:344,349,459 | 项目 UI 域词（terminal 转场同源） |
| 分享（图片） | sharedImagesFlow/「分享目标选择器」ShareTargetPickerDialog/pendingShareUris | ui/navigation/NavGraph.kt:76-157 | Android share 域词；与 OpenCode session.share 的 share 撞词 |
| 应用更新状态 | UpdateState（Idle/Checking/UpToDate/Available/Downloading/ReadyToInstall/Error）、releaseNotes/apkPath | ui/screens/about/AboutScreen.kt:109-125,205-365 | 应用更新域词，非 OpenCode API |
| 服务器连接 | 「连接到指定服务器（支持同时连接多个服务器）」「断开」connectedServerIds/connectingServerIds/connectJobs | ui/screens/home/HomeViewModel.kt:41-74,290-386 | 项目连接域词（以 server 为中心） |
| 连接健康检查 | testConnection/「健康检查通过后」/「Server is not responding」 | ui/screens/home/HomeViewModel.kt:324-339 | 连接域词 |
| providers 检查 | loadProviders/「providers 检查完成后的 TTL」（L-16） | ui/screens/home/HomeViewModel.kt:38-39,70-71,174-220 | ✅ provider/models 为 API 词 |
| 重复后端连接 | 「同后端第二连接预检」findDuplicateBackend（backlog #34） | ui/screens/home/HomeViewModel.kt:300-311 | 项目连接域词（backend= url+username） |
| 前台连接服务 | OpenCodeConnectionService/startForegroundService | ui/screens/home/HomeViewModel.kt:23,222-229,341-350 | 与 CONTEXT「连接生命周期协调」词条一致：Service 为 adapter |
| 电池优化豁免 | isBatteryOptimized/「电池优化警告横幅」「Play 合规」 | ui/screens/home/HomeScreen.kt:58-70,140-163 | Android 系统域词 |
| 通知权限连接流 | 「通知权限之后恢复连接流程」pendingConnectServerId（#115 D2-L24） | ui/screens/home/HomeScreen.kt:72-93 | 连接×系统权限交叉域词 |
| 添加/编辑服务器对话框 | showAddServerDialog/editingServer/ServerDialog | ui/screens/home/HomeViewModel.kt:231-241；HomeScreen.kt:260-269 | 项目服务器管理域词 |
| API 版本徽章 | 「API 版本徽章」ApiVersion.V2/serverVersion/isV2（#86：V1 也显示版本号） | ui/screens/home/components/ServerCard.kt:90-111 | 与 CONTEXT「版本 seam」词条相关：UI 此处直接读 apiVersion 分支 |
| 服务器健康 | home_server_health_good（已连接时显示「健康」类文案） | ui/screens/home/components/ServerCard.kt:77-82 | 「健康」与 testConnection 健康检查同域 |
| Provider 切换 | ProviderToggle/providerName/providerId/onConnect/onDisconnect | ui/screens/server/components/ProviderRow.kt:26-91 | ✅ provider 为 API 词；「连接/断开」用于 provider 层（与服务器层撞词） |
| Provider 来源 | source 取值 env/api/config/custom/other | ui/screens/server/components/ProviderRow.kt:59-72 | env/config 对应 OpenCode 配置来源语义 |
| 模型可见性 | 「模型过滤」visible/setModelVisible/modelName/modelId（按 provider 分组） | ui/screens/server/ServerModelFilterScreen.kt:59-168 | ✅ model/provider 为 API 词；「可见性」为客户端概念 |
| Provider 目录/连接状态 | ProviderCatalog/loadProviderCatalog/loadProviderConnectionStatus/_providerConnected | ui/screens/server/ServerSettingsViewModel.kt:107-165 | ✅ provider 为 API 词 |
| Provider 认证 | connectProviderApi（API key）/OAuth authorize·complete·cancel/PendingOauth/fallbackFromHeadless/DELETE /auth/{id}/disposeGlobal | ui/screens/server/ServerSettingsViewModel.kt:254-412 | 认证域；headless 为 OpenCode oauth 语义 |
| 全局配置 | GlobalConfig/GlobalConfigPatch：model/smallModel/defaultAgent/disabledProviders | ui/screens/server/ServerSettingsViewModel.kt:111-235,414-450 | ✅ model/agent/small_model 为 OpenCode 配置词 |
| V2 配置只读 | 「V2 配置只读（PATCH /api/config → 404，实测确认）」configEditable/ServerCapabilities（#172、#85） | ui/screens/server/ServerSettingsViewModel.kt:103-105,227-231,264-275,433-437 | ✅ 与 CONTEXT「版本 seam」词条一致（能力位门控） |
| 子代理过滤 | `it.mode != "subagent" && !it.hidden`（agentOptions 排除 subagent/hidden） | ui/screens/server/ServerSettingsViewModel.kt:493-497 | ✅ subagent 为 OpenCode agent.mode 词 |
| 隐藏模型 | hiddenModels/setModelVisibility/modelVisible（key=providerId:modelId） | ui/screens/server/ServerSettingsViewModel.kt:113,452-455,539-541 | 客户端概念（模型可见性） |
| OAuth 设备码 | extractOAuthDeviceCode/「设备码 chip」/method=="code"/headless 回退浏览器 | ui/screens/server/ServerProvidersScreen.kt:266-412,527-532 | 认证域词（headless/device code 为 OpenCode oauth 语义） |
| 已连接/可用 provider | server_settings_providers_connected/available 分区；opencode 特例（hasPaidModels 才算已连接） | ui/screens/server/ServerProvidersScreen.kt:92-100,447-522 | ✅ provider API 词；opencode 内置 provider 特判 |
| 会话列表状态拆分 | 「内容册/外壳册」「低频数据输入/高频 UI 输入」 | ui/screens/sessions/SessionListUiState.kt:21-65 | 与 CONTEXT「状态簇」词条同族的分簇词汇 |
| 未读红点 | hasUnread「未读提示红点」/isUnread 转发 UnreadBadgeService（#171 单源） | ui/screens/sessions/SessionListUiState.kt:17-18；SessionListStateBuilder.kt:10-36 | 与 CONTEXT「红点时钟域」词条对应 |
| 草稿 | hasDraft/DraftRepository.getDraftSessionIds（#38） | ui/screens/sessions/SessionListUiState.kt:15；SessionListStateBuilder.kt:83-84 | 客户端概念 |
| 提问中状态 | pendingQuestionIds→SessionStatus.Asking（2026-08-14 替代 hasPendingQuestion 布尔） | ui/screens/sessions/SessionListUiState.kt:34-35；SessionListStateBuilder.kt:93-96 | 客户端状态枚举（agent 提问等待回答） |
| 主/子会话 | `it.parentId == null`（列表只显示主会话） | ui/screens/sessions/SessionListStateBuilder.kt:41 | ✅ parentId 对应 OpenCode session 父子语义 |
| 分类/收藏 | Tag/categoryAssignments/categoryFilterIds/FAVORITE_TAG_ID/favoritesOnly | ui/screens/sessions/SessionListUiState.kt:27-29；SessionListStateBuilder.kt:65-81 | 客户端概念：同一 Tag 概念混用 tag/category 两词（待裁决） |
| 视图模式 | SessionViewMode.FOLDER/RECENT | ui/screens/sessions/SessionListUiState.kt:10 | 客户端概念（文件夹/最近） |
| MCP 服务器 | McpServerStatus/onToggleMcp/mcp_servers_title | ui/screens/sessions/ServerSettingsContent.kt:38-107 | ✅ MCP server 为 OpenCode 配置域词 |
| 服务器路径 | ServerPaths（home/cwd）/getHomeDirectory/「服务器主目录」 | ui/screens/sessions/DirectoryManager.kt:101-131 | ✅ home/cwd 为 OpenCode /app/paths 语义 |
| Windows 盘符 | 「盘符」listWindowsDrives/DRIVE_PROBE/isWindowsServer（反斜杠检测） | ui/screens/sessions/DirectoryManager.kt:28-32,126-181 | 文件浏览域词（盘符=drive） |
| 多项目会话拉取 | listProjectsUseCase/Project.worktree/displayName/「跨项目 worktree 聚合」 | ui/screens/sessions/SessionListViewModel.kt:572-602 | ✅ project/worktree 为 OpenCode /app/project 语义 |
| 游标分页 | 「基于游标的分页」currentCursor/hasMorePages/loadMore（limit 50） | ui/screens/sessions/SessionListViewModel.kt:514-647 | ✅ cursor 为 API 分词语义（V1/V2 策略差异见 CONTEXT「版本 seam」） |
| 堆积队列 | 「堆积队列」「手动放行队首」「堆积状态补偿的显式逃生口」pendingMessageRepository/pendingCounts/continuePendingQueue（#176/#177） | ui/screens/sessions/SessionListViewModel.kt:88-114 | 客户端概念（pending message） |
| 会话状态 FSM | sessionStateService.syncFromRest/「统一的 FSM 管线」「缺失即 idle + 不完整保护」（D2-L21） | ui/screens/sessions/SessionListViewModel.kt:599-602 | SessionStateService 域（CONTEXT「必需协作者」相关） |
| 分享导入 | importSession(shareUrl)/「从分享 URL 导入会话」 | ui/screens/sessions/SessionListViewModel.kt:685-703 | ✅ 对应 OpenCode session share URL 语义 |
| 提问事件 | SseEvent.QuestionAsked/questions→pendingQuestionIds | ui/screens/sessions/SessionListViewModel.kt:256,272,329-333 | ✅ question 载荷为 OpenCode SSE 事件语义 |
| MCP 连接状态 | toggleMcpServer/`status != "connected"` | ui/screens/sessions/SessionListViewModel.kt:770-788 | ✅ connected 为 MCP server 状态词 |
| 滚动信号 | SessionScrollSignal/KEY_SCROLL_TO_TOP/consumeScrollToTopOnReturn | ui/screens/sessions/SessionListViewModel.kt:82,99-101,406-412 | 客户端导航交互概念 |
| 目录树分组 | TreeNode.Directory/Session/buildTreeNodes（baseDirectory 两级分组/完整路径分组） | ui/screens/sessions/components/TreeNode.kt:10-193 | 客户端概念（directory ✅ API 词） |
| 项目感知分组 vs 目录分组 | 「项目感知分组」ProjectSessionGroup/projectForSession vs TreeNode 注释「改回纯目录分组」 | ui/screens/sessions/util/SessionGrouping.kt:7-59；TreeNode.kt:132-137 | ✅ projectId/worktree 为 API 词；两种分组策略并存（待核对使用点） |
| 波浪号路径 | 「波浪号路径标签」tildePath/~ 相对 homeDir | ui/screens/sessions/util/SessionGrouping.kt:19,56-72 | 客户端展示概念（home ✅ API 词） |
| 盘符选择器根 | 「虚拟 Windows 盘符选择器」windowsDrivesRoot/DRIVES_ROOT_SENTINEL(`:///drives`)「绝不发送给服务器」 | ui/screens/sessions/components/DirectoryPath.kt:31-32,111-123；SessionListViewModel.kt:95-96 | 客户端哨兵概念（两处独立定义同一哨兵字符串，待裁决是否统一） |
| 目录详情 | DirectoryDetailsDialog/session_directory_details/session_path/session_count | ui/screens/sessions/components/DirectoryTreeNode.kt:151-193 | directory ✅ API 词 |
| 活动会话计数 | activeSessionCount（SessionStatus.Busy 计数，加粗显示） | ui/screens/sessions/components/DirectoryTreeNode.kt:98-118,163 | 客户端概念（busy ✅ API 状态词） |
| MCP 状态枚举 | 字符串字面量 connected/disabled/failed/needs_auth/needs_client_registration（无 sealed 枚举） | ui/screens/sessions/components/McpServerRow.kt:43-67 | ✅ 对应 OpenCode /app/mcp 状态词（UI 层裸字符串） |
| 标签=分类 | 「标签/分类的视觉样式映射」SessionCategoryStyle（同文件双词并用） | ui/screens/sessions/components/SessionCategoryStyle.kt:24-30 | tag/category 双词冲突又一实例 |
| 搜索防抖 | 「内部管理搜索输入的 debounce（300ms）」 | ui/screens/sessions/components/SessionSearchBar.kt:38-66 | 事实备注：VM 层 _searchQuery 另有 debounce(300ms)（SessionListViewModel.kt:358-359 #100），实际双重防抖 |
| 会话状态三态 | SessionStatus.Busy/Asking/Retry → sessions_working/session_pending_question/sessions_retrying | ui/screens/sessions/components/SessionRow.kt:112-117,173-203,360-366 | busy ✅ API 词；Asking/Retry 为客户端枚举（FSM 域） |
| 未读 Badge | hasUnread→Badge/session_unread_indicator（Material3 BadgedBox） | ui/screens/sessions/components/SessionRow.kt:118-136 | 红点域展示层 |
| Diff 摘要 | summary.additions/deletions/files → session_changes_additions/deletions（#120 曾硬编码英文） | ui/screens/sessions/components/SessionRow.kt:215-240,375-382 | ✅ additions/deletions 为 OpenCode session summary 载荷词 |
| 分配分类 | onAssignCategory/assign_category（按钮文案）→ 实际打开 TagPickerDialog | ui/screens/sessions/components/SessionRow.kt:83,292-295,415-426 | category(按钮)/tag(对话框) 双词并用 |
| 继续队列 | 「堆积队列非空时的手动继续入口」session_details_continue_queue | ui/screens/sessions/components/SessionRow.kt:84-86,427-440 | #177 pending message 域 |
| 收藏 | isFavorite/onToggleFavorite/favorites_title/remove_favorite | ui/screens/sessions/components/SessionRow.kt:255-272 | 客户端概念（内置收藏标签） |
| 分页触发 | 「滚动到会话列表底部附近时调用」shouldLoadMore（最后可见 index ≥ total-3） | ui/screens/sessions/components/SessionTreeList.kt:62-74 | 客户端分页交互（cursor ✅ API 词） |
| 收藏标签本地化特例 | TagType.FAVORITE→favorites_title「内置收藏标签的名称数据持久化为中文收藏」 | ui/screens/sessions/components/TagChip.kt:29-39 | 用户数据 vs 本地化文案边界（tag=用户数据） |
| Tag 徽标 vs chip | TagBadge（无框小徽标）/TagChip（FilterChip）「全应用唯一形态」 | ui/screens/sessions/components/TagChip.kt:41-78 | tag 域 UI 双形态命名 |
| 标签管理对话框 | TagEditDialog 输入框 label=R.string.category_name（「分类名称」），其余文案全用「标签」tag | ui/screens/sessions/components/TagManagementSection.kt:61-72,295-306 | tag/category 双词并用的最强 UI 文案实例 |
| 上报状态机 | ReportUiState（Idle/NeedsGitHubAppConfig/Authorizing/Preview/Submitting/Done/Failed）「spec §失败处理」 | ui/screens/settings/DiagnosticsViewModel.kt:27-36 | GitHub 上报域（#151 spec） |
| 设备流授权 | device flow/userCode/pollToken/GitHubDeviceFlowAuth | ui/screens/settings/DiagnosticsViewModel.kt:85-141 | GitHub OAuth 域词（与 provider OAuth 的 device code 撞名） |
| 诊断日志 | DiagnosticLogEntry/DiagnosticLogRepository/logLevel/droppedEntryCount/export | ui/screens/settings/DiagnosticsViewModel.kt:14-16,46-73 | AppLogger/诊断域（AGENTS 规则对应） |
| 指纹去重 | fingerprint/fingerprintForError/SuppressedDuplicate「24 小时内已上报过同一错误」 | ui/screens/settings/DiagnosticsViewModel.kt:32,152,190-193 | 上报域概念 |
| 日志级别 | LEVELS（ERROR/WARN/INFO/DEBUG）+FATAL/crashCount「crashes」 | ui/screens/settings/DiagnosticsScreen.kt:85,96-101,131,290-296 | AppLogger 域（FATAL 仅统计用，未列入过滤 chip——事实备注） |
| 稳定 key | logEntryKey「内容派生稳定键」（timestamp+category+message hash，L-11） | ui/screens/settings/DiagnosticsScreen.kt:87-93,329-343 | 客户端列表性能概念 |
| 导出脱敏声明 | 「no chat or terminal payloads」/「内容已经过脱敏管道」 | ui/screens/settings/DiagnosticsScreen.kt:148；DiagnosticsViewModel.kt:168 | 上报域隐私边界声明 |
| 应用设置项 | AppSettings：appTheme(默认 system)/dynamicColor/chatFontSize/chatDensity/notificationsEnabled/initialMessageCount(50)/recentDirectoryCount(20)/confirmBeforeSend/autoAllowPermissions/amoledDark/compactMessages/collapseTools/expandReasoning/showTurnDividers/hapticFeedback/reconnectMode(normal)/keepScreenOn/compressImageAttachments/imageAttachmentMaxLongSide(1440)/imageAttachmentWebpQuality(60)/silentNotifications/terminalFontSize | ui/screens/settings/SettingsViewModel.kt:41-62,136 | 客户端设置域（turn ✅ API 词；其余为应用概念） |
| 权限自动批准 | PermissionAutoApprover/AutoApproveRule/loadRules/removeRule | ui/screens/settings/SettingsViewModel.kt:8,64-76 | ✅ 对应 OpenCode permission 域 |
| 重连模式 | reconnectMode（normal 默认） | ui/screens/settings/SettingsViewModel.kt:56,146-148 | 连接域概念（与 CONTEXT「连接生命周期协调」相关） |
| 通知自检 | sendSelfTestNotification/「端到端投递一条真实测试通知（验收①根因收尾）」 | ui/screens/settings/SettingsViewModel.kt:158-166 | 通知域概念 |
| 设置区块名 | General/Appearance/「聊天显示」「聊天行为」/Advanced/Notifications/Permissions（中英混排小节标记） | ui/screens/settings/SettingsScreen.kt:113-152 | 设置域结构词（中英并用） |
| 密度 vs 字号 | chatDensity 选择器用 settings_chat_font（字体）文案（normal/compact） | ui/screens/settings/SettingsScreen.kt:177-215 | chatFontSize 与 chatDensity 概念在文案层合并（密度档以字体名义呈现） |
| 自动批准规则字段 | AutoApproveRule.toolName/directoryPattern（"*"=全目录，显示时隐藏） | ui/screens/settings/components/PermissionRulesSection.kt:88-99 | ✅ tool/directory 为 OpenCode permission 语义 |
| 重连模式档位 | aggressive/normal/conservative 三档 | ui/screens/settings/components/SettingsDisplayNames.kt:33-40 | 连接域客户端档位词 |
| 主题档位 | system/light/dark | ui/screens/settings/components/SettingsDisplayNames.kt:9-16 | UI 域词 |
| 自动允许权限 | autoAllowPermissions「PermissionAsked 到达即自动应答 always（服务器落持久规则）」 | ui/screens/settings/sections/AutoApproveRulesSection.kt:25-28 | ✅ PermissionAsked/always 为 OpenCode permission 事件/应答词 |
| 文件查看来源 | FileViewerSource：LIVE/GIT_DIFF/TOOL_SNAPSHOT/TOOL_SNAPSHOT_DIFF | ui/screens/viewer/FileViewerParams.kt:16-21 | 客户端概念（✅ git diff/tool part 语义同源 API） |
| Diff hunk | DiffHunk/DiffHunkType(ADDED/REMOVED/MODIFIED)/rawPatch/patchStartLineIndex | ui/screens/viewer/FileViewerUiState.kt:10-17 | ✅ hunk/patch 为 VCS/diff 域词 |
| 工具快照 | isToolSnapshot/toolSnapshotBefore/After/Content「Phase 2 任务 9：工具快照」 | ui/screens/viewer/FileViewerUiState.kt:42-46 | ✅ 对应 OpenCode tool part 快照语义 |
| 大文件分页 | totalLineCount/visibleLineCount/isFullyLoaded/isExtremelyLarge「Phase 4：分页替代 Phase 1 的 isTruncated」 | ui/screens/viewer/FileViewerUiState.kt:29-33 | 客户端性能概念（演进史注释） |
| 批注 | annotations/Annotation（Phase 3） | ui/screens/viewer/FileViewerUiState.kt:47-48 | 客户端概念（annotation 非 OpenCode API 词） |
| 源码/渲染模式 | FileViewerMode(SOURCE/DIFF)/FileViewerRenderMode(SOURCE/RENDER_PREVIEW)/isMarkdown「向后兼容访问器」 | ui/screens/viewer/FileViewerUiState.kt:6-8,52-53 | 客户端概念 |
| 行号 gutter | 「行号 gutter 前缀」stripGutterNumbers/gutter Column | ui/screens/viewer/AnnotationContextMenu.kt:53-61 | 客户端渲染概念（gutter） |
| 批注 CRUD | AnnotationManager add/delete/update/restore/renumber「删除中间的批注时重新编号为连续 0..N-1」 | ui/screens/viewer/AnnotationManager.kt:7-70 | 客户端概念 |
| 批注说明 | note「修改说明」（AnnotationInputSheet）vs overallNote/editedNotes（提交） | ui/screens/viewer/AnnotationInputSheet.kt:38-44；FileViewerOverlay.kt:107-111 | 同一 note 概念三种叫法（说明/修改说明/整体说明） |
| V8 预热 | WebViewWarmer/warmed「一次性 WebView V8 引擎预热」「保持热状态」 | ui/screens/viewer/WebViewWarmer.kt:11-27 | 客户端性能概念 |
| unified diff | parseUnifiedDiff/HUNK_HEADER(@@ -a +b @@)/hasAdded/hasRemoved | ui/screens/viewer/DiffParser.kt:6-45 | ✅ 标准 VCS diff 域词 |
| 语法高亮 | Highlights/SyntaxLanguage/rememberLanguage（18 种语言扩展名映射） | ui/screens/viewer/HighlightBuilder.kt:54-76 | 客户端渲染概念 |
| diff 元数据行过滤 | 「避免把 git 命令输出直接暴露给用户」（diff --git/index/---/+++/mode/rename/Binary 过滤） | ui/screens/viewer/DiffView.kt:43-53,98-130 | ✅ git diff 域词 |
| hunk 导航 | DiffHunkNavigator/[current/total]/a11y_icon_hunk_previous/next | ui/screens/viewer/DiffView.kt:154-192 | 客户端交互概念（hunk ✅ VCS 词） |
| JS 桥 | SelectionBridge/AndroidBridge/@JavascriptInterface onSelection/onAnnotationClick | ui/screens/viewer/CodeWebView.kt:48-65,227-239 | 客户端 WebView 域词 |
| 加载更多（滚动） | 「距底部 300px 以内时触发加载更多」（400ms 防抖 postDelayed） | ui/screens/viewer/CodeWebView.kt:83-92 | 客户端分页交互（与 SessionTreeList 的 -3 item 触发同概念异实现） |
| 扩展名→语言映射 | extToLanguage（WebView 版，20+ 语言）vs HighlightBuilder.rememberLanguage（Compose 版，18 语言） | ui/screens/viewer/CodeWebView.kt:33-46；HighlightBuilder.kt:54-76 | 双实现映射表不一致事实（如 sql/yaml/gradle 仅 WebView 版有） |
| VCS diff 模式 | VcsDiffMode.GIT/getFileDiff/diffs.find{it.file==filePath} | ui/screens/viewer/FileViewerViewModel.kt:235-246 | ✅ 对应 OpenCode /vcs/diff 域 |
| 工具快照缓存 | ToolSnapshotCache/getAll/Snapshot(content/before/after/toolName)「Edit 工具只缓存 newString 片段——不是完整文件」 | ui/screens/viewer/FileViewerViewModel.kt:349-418,424-466 | ✅ tool part 快照语义（Edit/before/after） |
| 批注提交 | SubmitAnnotationsUseCase(serverId,sessionId,anns,overallNote,filePath,directory) | ui/screens/viewer/FileViewerViewModel.kt:283-299 | 客户端→服务器批注提交（annotation 非 API 词） |
| 极大文件阈值 | EXTREMELY_LARGE_THRESHOLD(10 万行)/EXTREMELY_LARGE_INITIAL(1 万行)/INITIAL_PAGE_SIZE(500) | ui/screens/viewer/FileViewerViewModel.kt:57-61 | 客户端性能常量 |
| 服务器 PDF 文本回退 | 「OpenCode 服务器可能把 PDF 当作 TEXT 返回（type=text）」ISO-8859-1→base64 重编码 | ui/screens/viewer/FileViewerViewModel.kt:121-139 | 服务器行为事实备注（V1/V2 载荷怪癖） |
| 双面板渲染切换 | 「源码和渲染视图都常驻组合，切换=可见性开关」/比例锚点 lastSourceFraction | ui/screens/viewer/FileViewerScreen.kt:103-118,162-220 | 客户端渲染概念 |
| 批注提交对话框 | AnnotationSubmitDialog/overallNote/editedNotes/positionLabel | ui/screens/viewer/FileViewerScreen.kt:478-533 | 批注域（note 三叫法之一） |
| 大文件警告 | LargeFileWarningBanner/viewer_large_file_warning | ui/screens/viewer/FileViewerScreen.kt:460-476 | 客户端性能提示 |
| PDF 翻页 | totalPages/currentPage/prevPage()/nextPage()/pdf_previous_page | ui/screens/viewer/PdfViewer.kt:191-236 | 客户端交互概念 |
| MIME base64 换行 | 「MIME base64 每 76 字符插入 \n（RFC 2045）」移除换行符 | ui/screens/viewer/PdfViewer.kt:76-81 | 数据格式事实备注 |
| Web UI 宿主 | 「加载远程 OpenCode Web UI」「全功能 Web UI」 | ui/screens/webview/WebViewScreen.kt:29-41 | 项目旧版路线词（OpenCode Web UI 为官方前端） |
| Basic Auth 注入 | authHeader/onReceivedHttpAuthRequest「明文凭据不再经导航参数传递」 | ui/screens/webview/WebViewScreen.kt:51,90-100,225-237 | 连接域词 |
| 主题 JS 注入 | themeJs/dark class/colorScheme meta「匹配应用的深/浅色模式」 | ui/screens/webview/WebViewScreen.kt:202-223 | UI 域桥接概念 |
| 文件树 | FileTreeNode/flattenTree/withChildren/ignored「被忽略的节点」（gitignore 语义） | ui/screens/workspace/FileTreeUtils.kt:5-55 | ✅ FileNode/ignored 为 OpenCode /file 域词 |
| 工作区双面板 | WorkspacePanel.FILE_TREE/GIT_CHANGES | ui/screens/workspace/WorkspaceUiState.kt:6 | 客户端结构词 |
| VCS 状态枚举 | VcsStatus.ADDED/DELETED/MODIFIED（徽标首字母 A/D/M） | ui/screens/workspace/git/GitChangeItem.kt:51-72 | ✅ 对应 OpenCode /vcs/status 域 |
| 非 Git 目录 | isNonGit | ui/screens/workspace/WorkspaceUiState.kt:28 | 客户端判定概念 |
| git 变更预取 | prefetchGitCount/gitChangeCount「prefetch 与完整加载互斥」 | ui/screens/workspace/WorkspaceViewModel.kt:58-59,150-179 | 客户端性能概念（VCS status ✅ API 域） |
| non-git 判定 | `msg.contains("non-git")/"not a git"` 字符串匹配 | ui/screens/workspace/WorkspaceViewModel.kt:162-167 | 事实备注：靠错误文案判定非 Git 目录（脆弱依赖） |
| 文件搜索 | FindFilesUseCase/findFiles（300ms delay 防抖） | ui/screens/workspace/WorkspaceViewModel.kt:212-233 | ✅ 对应 OpenCode /file/find 语义 |
| working tree clean | workspace_git_working_tree_clean「干净工作树」空态 | ui/screens/workspace/git/GitChangesPanel.kt:100-102 | ✅ git 域词 |
| 搜索模式双域 | 「文件树结果（通过 findFiles 进行服务器搜索）或 git 变更结果（客户端过滤）」 | ui/screens/workspace/search/SearchOverlay.kt:35-40 | 双搜索域并置（服务器端 vs 客户端过滤） |
| 中间省略 | ellipsizeMiddle「app/src/.../User.kt。用于结果列表中的长文件路径」 | ui/screens/workspace/search/SearchOverlay.kt:194-199 | 客户端展示概念 |
| 显示被忽略 | showIgnored/workspace_show_ignored（#149 testTag 断言教训） | ui/screens/workspace/tree/FileTreePanel.kt:68-76 | ✅ ignored 为 API 词（gitignore 语义） |
| 通知渠道自检 | 「厂商（MIUI 等）对旁装载应用默认关悬浮通知且标准 API 查不到」 | ui/screens/settings/sections/NotificationsSection.kt:73-74 | 通知域事实备注 |
| 边界备注 | ChatBehaviorSection/ChatDisplaySection/各类 *Dialog.kt 组件路径含 chat/dialog 关键词，按机械规则归 03 路 | ui/screens/settings/SettingsScreen.kt:37-51（import 证据） | coverage 边界效应：03 若按「聊天域」语义盘点可能漏掉这些设置域组件 |
| 目录缓存 | dirCache/「已浏览目录秒开」（30s TTL/200 条上限/近似 LRU） | ui/screens/sessions/DirectoryManager.kt:63-99,183-210 | 客户端性能概念 |
| 打开项目 | OpenProjectDialog/「打开项目对话框」（目录浏览器的入口文案） | ui/screens/sessions/SessionListScreen.kt:369-381,94 | 「项目」为口语化 UI 词；OpenCode 侧对应 directory |
| 一键已读/待读 | markAllSessionsRead「消除所有小红点」/consumePendingReadSessionId（返回列表同步标记已读防一帧闪烁） | ui/screens/sessions/SessionListScreen.kt:111-118,206-216 | 红点域（与 CONTEXT「红点时钟域」相关） |
| 快速新建会话 | NewSessionQuickDialog/「最近目录」recentDirectoryCount | ui/screens/sessions/SessionListScreen.kt:157-172,383-398 | 客户端概念 |
| 分类选择器=标签选择器 | 「会话分类选择器」showCategoryPicker → TagPickerDialog/onAssignTags | ui/screens/sessions/SessionListScreen.kt:97-100,337-341,425-441 | 同一 UI：category（状态名）与 tag（组件名）双词并用（待裁决） |

## 失实注释

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| ui/components/AmoledCard.kt:20 | 「1dp outlineVariant，65% 不透明度」 | 实际取 AlphaTokens.MEDIUM = 0.70f（ui/theme/Alpha.kt:22），即 70% | 改为 70% 或直接引用令牌名 |
| ui/screens/sessions/util/SessionGrouping.kt:7-13 | KDoc 称「项目感知分组优于原始目录分组」，以现行功能口吻描述 | 全库 grep：buildProjectSessionGroups/ProjectSessionGroup/projectForSession 仅在本文件出现，无任何调用点；TreeNode.kt:132-137 已注明「改回纯目录分组」 | 标注为已废弃/死代码，或删除文件 |
| ui/screens/settings/DiagnosticsViewModel.kt:209-219 | retrySubmit KDoc「失败重试（spec：草稿保留 + 一键重试）」 | retryableBody?.let 分支先设 Preview(body, "") 后无条件 startReport()；startReport→buildPreview 会用当前日志重建全新 body，用户编辑过的草稿实际不会被复用（赋值立即被覆盖，且 fingerprint 传空串） | 注释改为「失败后重新构建上报（编辑草稿不复用）」，或修代码真正复用草稿 |
| ui/screens/settings/SettingsViewModel.kt:184-189 | KDoc「修复：单消费者 channel 队列，每次写基于上一次写的结果」 | 实际实现是 settingsWriteMutex(Mutex) + pendingSettings 快照变量（190-203 行），无任何 channel/队列构造；机制描述与代码不符（语义效果等价但名词失实） | 改为「Mutex 串行化 + 待写快照」 |
| ui/screens/webview/WebViewScreen.kt:29-41 | KDoc「用 OpenCode 服务器提供的全功能 Web UI 替代所有原生 Chat/Session 屏幕」以现行功能口吻描述 | NavGraph.kt:69-70 硬编码 `val useNativeUi = true`（注释「默认使用原生 UI（WebView 为旧版实现）」）；WebViewNav.kt:9 亦称「WebView 页（旧版）」——WebView 已非替代路线而是遗留回退 | 改为「旧版 Web UI 宿主（原生 UI 为默认路线）」 |
| ui/screens/workspace/git/GitChangesPanel.kt:40-42 | KDoc「点击查看 diff 已通过 onOpenDiff 接通，但 diff 查看器本身将在后续阶段实现」（Phase 1 范围陈述） | WorkspaceScreen.kt:71-79,86-91 onOpenGitDiff→FileViewerOverlay(source=GIT_DIFF) 已落地；FileViewerViewModel.loadGitDiff + DiffView（含 hunk 导航/DIFF↔SOURCE 切换）均已实现 | 删除「将在后续阶段实现」句，改为现状描述 |

## 待裁决冲突

1. **标签 vs 分类（tag / category）** — 冲突各方：状态名 categoryFilters/categoryAssignments（VM、UiState、StateBuilder）；组件/数据名 Tag/TagChip/TagPickerDialog/TagManagementSection；UI 文案「会话分类选择器」+ TagEditDialog 输入框 label=R.string.category_name（「分类名称」）而同屏其余文案全用「标签」；样式类名 SessionCategoryStyle 注释自述「标签/分类」。出现范围：ui/screens/sessions/**（约 8 文件）。备注：同一概念两词在同名对话框内同屏出现；identifier 不改的前提下需裁决注释与 UI 文案统一用哪个词（Tag 是数据模型名，category 只出现在筛选/分配语义处）。

2. **连接/断开（connect/disconnect）双层级撞词** — 冲突各方：服务器层 connectToServer/disconnectFromServer（HomeViewModel）；provider 层 connectProviderApi/disconnectProvider（ServerSettingsViewModel、ProviderRow、ServerProvidersScreen，ProviderRow 同一屏按钮文案 R.string.connect）。出现范围：ui/screens/home/**、ui/screens/server/**。备注：OpenCode API 无 server 连接概念（单 server 常驻），「连接」实为客户端 SSE 会话管理；provider 层的 connect 对应 API 的 credential 授权——两层共用同一对 UI 动词，注释需分层表述。

3. **项目（project）多义** — 冲突各方：OpenCode /app/project 实体（Project.projectId/worktree，SessionGrouping、SessionListViewModel 多项目拉取）；口语「打开项目」OpenProjectDialog（实为目录浏览器入口文案）；项目感知分组 ProjectSessionGroup（已死代码）。出现范围：ui/screens/sessions/**。备注：SessionGrouping.kt 整文件无调用点（见失实注释表）；「打开项目」文案指向 directory 概念而非 project 实体。

4. **分享（share）多义** — 冲突各方：Android 系统分享图片 sharedImagesFlow/ShareTargetPickerDialog（NavGraph）；OpenCode session 分享 URL 导入 importSession(shareUrl)（SessionListViewModel）；session share 子会话 childSession（NavGraph #137）。出现范围：ui/navigation/NavGraph.kt、ui/screens/sessions/SessionListViewModel.kt。备注：三个「share」分属 Android intent 域与 OpenCode session share 域，注释若统一译「分享」会互相踩。

5. **重试（retry）多域** — 冲突各方：连接重试 retryCountdown/ConnectionErrorScreen；provider/SSE 会话重试 SessionStatus.Retry/SessionRetryCard（attempt/maxAttempts）；堆积队列手动继续 continuePendingQueue（#176/#177，不用 retry 一词）；诊断上报重试 retrySubmit（DiagnosticsViewModel）。出现范围：ui/components/**、ui/screens/sessions/**、ui/screens/settings/**。备注：与 CONTEXT「红点时钟域」「会话错误显式携带客户端时刻」相关的 Retry 状态是 FSM 域词；四处「重试」机制完全不同，注释需带域前缀。

6. **会话列表状态拆分词 vs CONTEXT「状态簇」词条** — 冲突各方：「内容册/外壳册」（SessionListUiState/StateBuilder/ViewModel）、「低频数据输入/高频 UI 输入」 vs CONTEXT.md 既有词条「状态簇（State Cluster）」。出现范围：ui/screens/sessions/SessionListUiState.kt、SessionListStateBuilder.kt、SessionListViewModel.kt（#23 状态切片注释）。备注：概念同族（UI 读簇对象），但「册」是本域自造词；裁决是否并入状态簇词条或单列。

7. **模型过滤 vs 模型可见性** — 冲突各方：路由/屏幕名 server_model_filter「服务器模型过滤页」；实际机制 hiddenModels/setModelVisibility（客户端持久化，key=providerId:modelId）；GlobalConfig.model/smallModel（服务器配置）。出现范围：ui/screens/server/**。备注：「过滤」暗示即时筛选，实际是持久可见性开关；且与服务器配置的 model（默认模型）共用 model 一词——OpenCode API 中 model 既指目录条目又指配置默认值。

8. **批注说明三叫法** — 冲突各方：字段 note；AnnotationInputSheet KDoc「修改说明」；提交对话框 overallNote/editedNotes（「整体说明」）。出现范围：ui/screens/viewer/Annotation*.kt、FileViewerScreen.kt。备注：annotation 本身非 OpenCode API 词（客户端概念，经 SubmitAnnotationsUseCase 提交）；note 的中文译名未统一。

9. **MCP 状态裸字符串** — 冲突各方：McpServerRow 以字符串字面量分支 "connected"/"disabled"/"failed"/"needs_auth"/"needs_client_registration"；域模型有 McpServerStatus 但 status 字段为 String。出现范围：ui/screens/sessions/components/McpServerRow.kt、SessionListViewModel.toggleMcpServer。备注：✅ 与 OpenCode /app/mcp 状态词一致，但 UI 层无枚举收口，注释修订时字符串拼写即术语。

10. **目录 vs 文件夹（directory / folder）** — 冲突各方：路由参数 directory、SessionViewMode.FOLDER「文件夹视图」、sessions_view_folders 文案、createDirectory(folderName)（DirectoryManager）；OpenProjectDialog「打开项目」实为 directory 浏览。出现范围：ui/screens/sessions/**。备注：directory ✅ API 词为代码层规范名；「文件夹」仅存在于视图模式与口语文案，注释统一中文时应统一译名。

11. **device flow / device code 撞名** — 冲突各方：GitHub 错误上报设备流 GitHubDeviceFlowAuth/userCode（DiagnosticsViewModel）；provider OAuth 设备码 extractOAuthDeviceCode/fallbackFromHeadless（ServerProvidersScreen）。出现范围：ui/screens/settings/DiagnosticsViewModel.kt、ui/screens/server/ServerProvidersScreen.kt。备注：两套独立 OAuth 设备流机制同名，注释需冠域（GitHub 上报 / provider 授权）。

12. **盘符选择器哨兵双定义** — 冲突各方：SessionListViewModel.WINDOWS_DRIVES_ROOT = ":///drives" 与 DirectoryPath.DRIVES_ROOT_SENTINEL = ":///drives" 各自独立定义同一魔法串。出现范围：ui/screens/sessions/SessionListViewModel.kt:95-96、components/DirectoryPath.kt:112。备注：DirectoryPath 注释称哨兵「绝不发送给服务器」；两处常量无引用关系（事实），术语统一时应收口单点。

13. **令牌名与用途错位（AlphaTokens.AMOLED）** — 冲突各方：EmbeddedCardContainer 普通模式边框用 AlphaTokens.AMOLED(0.92)（非 AMOLED 场景）；PdfViewer 工具栏覆盖层用 AlphaTokens.AMOLED 当 surface 透明度（#137 D2-L49 自述「数值最接近」）。出现范围：ui/components/EmbeddedCardContainer.kt:35-39、ui/screens/viewer/PdfViewer.kt:194-195。备注：令牌以「数值」被借用而非语义；AMOLED 词承载了三种含义（模式/最大对比度值/0.92 这个数）。

14. **API 版本判定的 UI 直连 vs「版本 seam」词条** — 冲突各方：ServerCard 直接读 server.apiVersion/isV2 分支徽章配色（ui/screens/home/components/ServerCard.kt:90-111）；CONTEXT「版本 seam」词条称「UI 只读能力不读版本」。出现范围：ServerCard（展示用）vs ServerSettingsViewModel（走 ServerCapabilities 能力位，✅ 符合词条）。备注：ServerCard 是展示版本号徽章（非行为分支），与词条「能力位」规则的边界值得裁决确认；另 home 域 testConnection 后的文案「健康」与连接域健康检查同词。

15. **搜索防抖双层叠加** — 冲突各方：SessionSearchBar 内部 Job delay(300) + SessionListViewModel._searchQuery debounce(SEARCH_DEBOUNCE_MS=300)（#100 M-11）。出现范围：ui/screens/sessions/components/SessionSearchBar.kt:38-66、SessionListViewModel.kt:358-359。备注：两层防抖串联（实际可达 ~600ms）；WorkspaceViewModel.searchFiles 也有独立 delay(300)——三处 300ms 魔法数各自定义。

16. **FATAL 级别不可过滤** — 冲突各方：LEVELS 过滤 chip 仅 ERROR/WARN/INFO/DEBUG；crashCount 统计 level=="FATAL"。出现范围：ui/screens/settings/DiagnosticsScreen.kt:85,131,247-259。备注：AppLogger 域有 FATAL 级但用户无法过滤它（选中 ERROR 不含 FATAL）；属行为事实，注释修订涉及日志级别词表时需注意。

17. **「打开项目」对话框命名 vs 实际功能** — 冲突各方：OpenProjectDialog（名「打开项目」）实为完整目录浏览器（含盘符/搜索/新建目录）；NewSessionQuickDialog（快速新建=最近目录）。出现范围：ui/screens/sessions/SessionListScreen.kt:369-398。备注：与冲突 3 同源；对话框职责=选 directory 建新会话，命名带「项目」易误导。

18. **连接生命周期相关词与 CONTEXT 词条的对齐** — 冲突各方：OpenCodeConnectionService（前台服务 adapter，✅ 与「连接生命周期协调」词条一致）；但 UI 层另有 reconnectMode（设置项 aggressive/normal/conservative）、ConnectionErrorScreen 自动重试倒计时、HomeViewModel testConnection 健康检查三套连接词。出现范围：ui/screens/home/**、ui/screens/settings/**。备注：词条说「Service 不持有生命周期状态」——UI 层注释描述 Service 时需与该裁决对齐（HomeViewModel 注释已基本符合）。