# 盘点：UI 层聊天域（ui 下 chat/dialog/message 相关文件，含 ChatScreen.kt 无论其位置）

> Phase 1 事实收集（只记事实，不做裁决）。范围：ui 下路径含 chat/dialog/message/scroll/markdown 的 .kt + ChatScreen.kt，共 158 文件。OpenCode API 权威词参照：session/message/part/event/turn/tool/provider/model/agent 等。
> 进度：158/158 已读 ✔ 完成。方法：热点文件全文逐段精读；长尾文件结构化透镜（注释全文+声明+字符串，滤 import/空行/孤括号）——两类均已通读全文件。

## 盘点统计（完成）

- 范围文件 **158**，已精读 **158**（覆盖清单全部 ✓）
- 术语观察 **167** 行（概念约 90 组）
- 失实/过期注释 **25** 条（行为过期 9、数值不符 3、幽灵引用/死代码 6、行号漂移 2、粒度/名实不符 4、KDoc 语义反转 1）
- 待裁决冲突 **27** 项
- 注释语言现状（按文件计）：中文为主 **97** · 中英混合/含英文 KDoc **45** · 基本无注释 **16**（整文件英文 KDoc 3 个：input/FileMentionSuggestions、input/ImageAttachmentRow、input/ShellModeHintBanner）
- 方法注记：ChatScreen.kt（1032 行）、ChatMessageList.kt（1479 行）等热点文件逐段全文精读；长尾 UI 文件以「注释全文+声明+字符串字面量」透镜通读全文件（仅滤 import/空行/孤括号）；失实判定均给出代码行依据。

## 覆盖清单

| # | 文件（相对 ui/） | 读 | 语言现状 | 备注 |
|---|---|---|---|---|
| 1 | components/AmoledDialogParams.kt | ✓ | 中文 | 74 行；AMOLED 对话框参数自适应；无领域术语 |
| 2 | components/ConfirmDialog.kt | ✓ | 中文 | 76 行；通用确认对话框；无领域术语 |
| 3 | components/DialogButtons.kt | ✓ | 中文 | 118 行；按钮角色 Primary/Secondary/Danger；无领域术语 |
| 4 | navigation/ShareTargetPickerDialog.kt | ✓ | 中文 | 246 行；ACTION_SEND 分享图片选会话（按服务器分组；新建会话） |
| 5 | navigation/routes/ChatNav.kt | ✓ | 中文 | 55 行；chat 路由参数（sessionId/openTerminal/directory；safeDecodeParam） |
| 6 | screens/chat/ChatFabMenu.kt | ✓ | 中文 | 226 行；FAB 菜单四入口（STACKED/TODO/AGENT/SHELL）+滚动到底部 FAB |
| 7 | screens/chat/ChatParts.kt | ✓ | 中文 | 39 行；可渲染 parts 白名单 + 原始交错顺序 |
| 8 | screens/chat/ChatRoute.kt | ✓ | 中文 | 67 行；自述死代码（NavGraph 内联注册，本入口未使用） |
| 9 | screens/chat/ChatScreen.kt | ✓ | 中文 | 1032 行（1-248 为 import）；注释全中文，含大量日期/轮次演进注释 |
| 10 | screens/chat/ChatScreenBottomBar.kt | ✓ | 中文 | 460 行；输入栏+斜杠命令+@提及+stableBusy 消抖 |
| 11 | screens/chat/ChatScreenDialogs.kt | ✓ | 中文 | 67 行；三条件对话框容器（ModelPicker/Rename/SendConfirm） |
| 12 | screens/chat/ChatScrollController.kt | ✓ | 中文 | 272 行；autoScroll/forceScroll/isAtBottom 三态 + ForceScrollExecutor；铁律注释详尽 |
| 13 | screens/chat/ChatSendDelegate.kt | ✓ | 中文 | 171 行；悲观消息模式发送管线；E8-1/RS-007 修复注释 |
| 14 | screens/chat/ChatStateAggregator.kt | ✓ | 中文 | 149 行；3 个聚合管道（uiState 已退役）；头部 KDoc 与尾部退役注记矛盾 |
| 15 | screens/chat/ChatUiState.kt | ✓ | 中文 | 128 行；5 个拆分状态数据类 + ChatMessage；isSynthetic KDoc 陈旧 |
| 16 | screens/chat/ChatViewModel.kt | ✓ | 中文 | 967 行；门面+5 状态簇 delegate；注释密集中文（含 TUI/ACP 对齐考证） |
| 17 | screens/chat/ContextDetailDelegate.kt | ✓ | 中文 | 90 行；上下文详情（token 分布/缓存命中率/provider） |
| 18 | screens/chat/DraftInputDelegate.kt | ✓ | 中文 | 241 行；草稿/@提及/失败恢复；DRAFT_DEBOUNCE_MS 注释失实（防抖已删） |
| 19 | screens/chat/JumpPrefetchStrategy.kt | ✓ | 中文 | ~140 行；仅剩滚动方向预测预组合（跳转目标预组合 2026-08-21 已移除，类名名不副实） |
| 20 | screens/chat/LinkUriHandler.kt | ✓ | 中文 | 139 行；markdown 链接拦截；「v1」歧义 |
| 21 | screens/chat/MessageDataDelegate.kt | ✓ | 中文 | 666 行；SSE 观察+分页组合器+快速导航数据源；2 处失实注释 |
| 22 | screens/chat/MessagePaginationDelegate.kt | ✓ | 中文 | 459 行；PaginationFSM 游标+防风暴；V1/V2 游标策略；loadAround 定位加载 |
| 23 | screens/chat/ModelConfigDelegate.kt | ✓ | 中文 | 377 行；12 路 combine 自反馈解析链；primary agent 回填；ACP 口径 contextWindow |
| 24 | screens/chat/PendingSheets.kt | ✓ | 中文 | 676 行；四 sheet（堆积/TODO/agent/shell）；拖拽排序+三态符号 |
| 25 | screens/chat/QuestionAnswerStore.kt | ✓ | 中文 | 58 行；应用级答案存储（parkedCustoms 保留未勾选） |
| 26 | screens/chat/SendStateStore.kt | ✓ | 中文 | 24 行；悲观模式仅存 isSending（乐观消息体系已整体移除自述） |
| 27 | screens/chat/SessionActionsDelegate.kt | ✓ | 中文 | 717 行；24 个无状态 REST 操作；权限/问题复核去留；V1/V2 压缩事件差异 |
| 28 | screens/chat/SessionLifecycleDelegate.kt | ✓ | 中文 | 157 行；会话身份/目录/延迟创建核心骨架；x-opencode-directory |
| 29 | screens/chat/SettingsStateDelegate.kt | ✓ | 中文 | 63 行；12 个 UI 设置 StateFlow |
| 30 | screens/chat/TaskDelegate.kt | ✓ | 中文 | 272 行；TaskAggregator 前台/后台 subagent + active 轮询 + 双向对账 |
| 31 | screens/chat/TerminalDelegate.kt | ✓ | 中文 | 142 行；终端簇（ServerTerminalWorkspace/Termux 桥/PTY） |
| 32 | screens/chat/ToolCacheDelegate.kt | ✓ | 中文 | 95 行；read/write/edit 快照缓存（<content> 剥离/行号剥离） |
| 33 | screens/chat/components/AlwaysConfirmDialog.kt | ✓ | 中文 | 48 行；「始终允许」二次确认（防意外永久批准） |
| 34 | screens/chat/components/BreathingCircleIndicator.kt | ✓ | 中文 | 66 行；呼吸圆圈加载指示器 |
| 35 | screens/chat/components/ChatEmptyState.kt | ✓ | 中文 | 40 行；空态；无领域术语 |
| 36 | screens/chat/components/ChatErrorState.kt | ✓ | 中文 | 77 行；错误态指数退避自动重试（5s→60s #134）；KDoc 首句英文 |
| 37 | screens/chat/components/ChatMessageList.kt | ✓ | 中文 | 1479 行（最大）；跳转状态机+分片+渲染供给桥+自动分页；真机取证口语注释多 |
| 38 | screens/chat/components/ChatTopBar.kt | ✓ | 中文 | 260 行；顶栏菜单（转后台/分享 V1/V2 门控）+上下文指示器 |
| 39 | screens/chat/components/CompactTag.kt | ✓ | 中文 | 85 行；紧凑标签/AgentTag（agent 徽章/QUEUED 徽章同款） |
| 40 | screens/chat/components/CompactionBanner.kt | ✓ | 中文 | 93 行；「正在压缩上下文」进行中气泡（V1 started 三件套/V2 本地置态） |
| 41 | screens/chat/components/CompactionCard.kt | ✓ | 中文 | 125 行；压缩完成卡片（V2 REST 有 summary 可展开；V1 SSE 无 summary 仅分割线） |
| 42 | screens/chat/components/ContextDetailDialog.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 188 行；上下文详情（进度/缓存命中率/breakdown/Token 明细复用 TokenUsageCard） |
| 43 | screens/chat/components/ContextUsageBar.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 74 行；上下文占用进度条；calculateContextUsage 已删（无调用点自述） |
| 44 | screens/chat/components/CopyButton.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 77 行；复制按钮（AnimatedContent 图标过渡）；无领域术语 |
| 45 | screens/chat/components/ErrorPayloadContent.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 141 行；错误载荷（Code/WebView 双模式，https://localhost/ 内嵌预览） |
| 46 | screens/chat/components/FileCard.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 66 行；非图片文件卡（EmbeddedCardContainer 出处）；行内英文注释 |
| 47 | screens/chat/components/JumpNavigationController.kt | ✓ | 中文 | 438 行；JumpPhase 状态机+渐进定位；头部 KDoc 稳定窗口 1.5s 已失实（现 900ms） |
| 48 | screens/chat/components/MarkdownChunking.kt | ✓ | 中文 | 257 行；assistant AST 分片+用户纯文本分片+ChatEntry；「硬切」KDoc 失实 |
| 49 | screens/chat/components/MessageBubble.kt | ✓ | 中文 | 139 行；统一气泡容器；「后台消息」=synthetic 又一叫法 |
| 50 | screens/chat/components/MessageCard.kt | ✓ | 中文 | 70 行；三角色分发（USER/ASSISTANT/SYNTHETIC） |
| 51 | screens/chat/components/MessageCardAssistant.kt | ✓ | 中文 | 669 行；智能体气泡+ChunkedAssistantMessage；ticker 300ms 注释 vs 100ms 代码 |
| 52 | screens/chat/components/MessageCardUser.kt | ✓ | 中文 | 429 行；用户气泡+QUEUED+透明门控；ChunkedUserMessage 纯文本（官方 TUI 对齐） |
| 53 | screens/chat/components/MessageMetaInfo.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 64 行；assistant 元信息（模型/时长/token）；英文 KDoc；疑似低使用 |
| 54 | screens/chat/components/PartContent.kt | ✓ | 中文 | 368 行；part 分发中心（question/todoread/工具注册表/V2 shell 卡） |
| 55 | screens/chat/components/QuestionPartContent.kt | ✓ | 中文 | 841 行；QuestionPagerView 共用；三态模型（parked）；E2E-E 可达性 |
| 56 | screens/chat/components/QuickNavigateSheet.kt | ✓ | 中文为主（FileCard/MessageMetaInfo 英文） | 312 行；快速导航抽屉（时间锚点降级定位/48ms 流式期防抖）；QuickNavigateHost B-F3 |
| 57 | screens/chat/components/ReasoningBlock.kt | ✓ | 中文 | 240 行；思考卡；0.3s ticker 注释 vs delay(100) 同款失实 |
| 58 | screens/chat/components/RejectWithMessageDialog.kt | ✓ | 中英混合 | 63 行；拒绝并留言（英文 KDoc sub-agent permission） |
| 59 | screens/chat/components/RenderReadiness.kt | ✓ | 中文 | ~135 行；渲染就绪注册表；Ready/awaitReady 死状态残留；形参 msgId 实传 partId |
| 60 | screens/chat/components/RenderSupplyCoordinator.kt | ✓ | 中文 | 311 行；CONTEXT.md 渲染供给词条源头（注释明言「术语见根目录 CONTEXT.md」） |
| 61 | screens/chat/components/RetryBanner.kt | ✓ | 中英混合 | 65 行；Retry 横幅（#120 D2-09 单占位符） |
| 62 | screens/chat/components/RevertBanner.kt | ✓ | 中英混合 | 81 行；Revert 横幅（英文 KDoc：tap=redo 恢复） |
| 63 | screens/chat/components/ScrollCompensation.kt | ✓ | 中文 | 95 行；CompensateState+反射 requestScrollToItemNoCancel（降级路径） |
| 64 | screens/chat/components/StepProgressIndicator.kt | ✓ | 中英混合 | 68 行；步骤进度（step/agent/model；英文 KDoc） |
| 65 | screens/chat/components/SyntheticNotificationCard.kt | ✓ | 中文 | 395 行；synthetic 通知解析（<task>/<subagent>/<shell>）；session.input.promoted 对齐 |
| 66 | screens/chat/components/TokenRatioRing.kt | ✓ | 中英混合 | 98 行；Token 占比圆环——KDoc 称 08-14 恢复，但消费点 08-15 已移除（死代码） |
| 67 | screens/chat/components/TokenUsageCard.kt | ✓ | 中英混合 | 98 行；Token 用量卡（英文 KDoc；成本 $ 格式） |
| 68 | screens/chat/components/ToolProgressCard.kt | ✓ | 中英混合 | 113 行；实时工具进度卡（status==started 用 Sync 图标） |
| 69 | screens/chat/dialog/ChatScreenDialogs.kt | ✓ | 中文 | 172 行；Rename/SendConfirm/RevertCompaction 对话框（与 #11 同名不同目录） |
| 70 | screens/chat/dialog/ImagePreviewDialog.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 254 行；缩略图行+全屏预览（#135 D2-L68 解码/降采样移出主线程） |
| 71 | screens/chat/dialog/MarkdownPreviewDialog.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 153 行；单条消息查看（SOURCE/RENDERED 双视图） |
| 72 | screens/chat/dialog/ModelPickerDialog.kt | ✓ | 中文 | 312 行；模型抽屉（variant accordion/星标默认模型） |
| 73 | screens/chat/dialog/PermissionRequestCard.kt | ✓ | 中文 | 165 行；权限卡（error 语义色/子 agent 来源标签） |
| 74 | screens/chat/dialog/QuestionCard.kt | ✓ | 中文 | 403 行；交互提问卡；toggleQuestionAnswer 纯函数三态模型 |
| 75 | screens/chat/input/AgentModelVariantSelector.kt | ✓ | 中文 | 172 行；agent 循环切换+幽灵文本定宽；输入行 variant pill 已移除（#187 ③） |
| 76 | screens/chat/input/BusyIndicatorSmoother.kt | ✓ | 中文 | 73 行；busy 显示侧下降沿延迟 2.5s；V2 drain 根因链详注 |
| 77 | screens/chat/input/ChatAttachmentsHandler.kt | ✓ | 中文 | 297 行；附件/SAF 导出/图片保存/共享消费；SAF 全类型（PDF/文本） |
| 78 | screens/chat/input/ChatInputBar.kt | ✓ | 中文 | 265 行；占位符轮换 4s；客户端+服务器+技能命令合并；任务工具栏 |
| 79 | screens/chat/input/ChatTextField.kt | ✓ | 中文 | 126 行；shell 等宽+提及视觉变换；无失实 |
| 80 | screens/chat/input/FileMentionSuggestions.kt | ✓ | 中文 | 112 行；@ 提及建议弹窗——英文 KDoc |
| 81 | screens/chat/input/FileMentionVisualTransformation.kt | ✓ | 中文 | 62 行；已确认 @path 高亮（防更长 token 误匹配） |
| 82 | screens/chat/input/ImageAttachmentRow.kt | ✓ | 中文 | 116 行；缩略图行——英文 KDoc（与 dialog/ImagePreviewDialog 内 ImageThumbnailRow 同名功能重复，后者为现役） |
| 83 | screens/chat/input/SendStopButton.kt | ✓ | 中文 | 325 行；发送/停止/长按 shell；busy 气泡（立即发送 mode2/堆积 mode3） |
| 84 | screens/chat/input/ShellModeHintBanner.kt | ✓ | 中文 | 76 行；shell 模式提示——英文 KDoc |
| 85 | screens/chat/input/SlashCommandSuggestions.kt | ✓ | 中文 | 98 行；斜杠建议；技能（type=skill）tertiary |
| 86 | screens/chat/input/TaskToolbar.kt | ✓ | 中文 | 81 行；任务工具栏（TUI ctrl+b；转为后台） |
| 87 | screens/chat/markdown/ClickableMarkdown.kt | ✓ | 中文 | 217 行；可点击链接+代码路径；双路定位 #120 |
| 88 | screens/chat/markdown/MarkdownContent.kt | ✓ | 中文 | 643 行；归一化链+预解析渲染+分片槽+异步 fallback；无失实注释 |
| 89 | screens/chat/markdown/MarkdownTable.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 338 行；自绘 GFM 表格（MeasureCache 三遍 subcompose 降一遍 #135 D2-L46） |
| 90 | screens/chat/markdown/NormalizeTaskListMarkers.kt | ✓ | 中文 | 40 行；Unicode 任务标记→GFM 复选框 |
| 91 | screens/chat/terminal/ChatTerminalView.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 455 行；终端模式视图（音量键虚拟 Ctrl/Fn、Ctrl+Alt+V 粘贴、snackbar 竞态根治） |
| 92 | screens/chat/terminal/TerminalDrawerActionsRow.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 64 行；抽屉底部动作行（英文 KDoc） |
| 93 | screens/chat/terminal/TerminalDrawerEdgeGesture.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 61 行；左缘手势开抽屉（英文 KDoc） |
| 94 | screens/chat/terminal/TerminalKeyboardOverlay.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 141 行；终端键盘条（DECCKM 方向键转义；Termux 附加键一致） |
| 95 | screens/chat/terminal/TerminalKeys.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 94 行；修饰键/Fn 绑定纯函数（Termux 对齐） |
| 96 | screens/chat/terminal/TerminalTabItem.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 154 行；终端 tab 项（英文 KDoc；Offline 态） |
| 97 | screens/chat/terminal/TermuxTerminalHost.kt | ✓ | 中文为主（3 个终端文件英文 KDoc） | 261 行；Termux 换件宿主（#189 替代 termlib；OSC 11 背景；阶梯缩放） |
| 98 | screens/chat/tools/ContextToolGroupCard.kt | ✓ | 中文（Resolver 英文 KDoc） | 116 行；read/glob/grep 组卡（CONTEXT_TOOLS） |
| 99 | screens/chat/tools/DefaultToolCardResolver.kt | ✓ | 中文（Resolver 英文 KDoc） | 99 行；工具名→卡映射（V1/V2 双名：shell↔bash、subagent↔task、web_fetch/web_search 下划线变体） |
| 100 | screens/chat/tools/DiffHelpers.kt | ✓ | 中文（Resolver 英文 KDoc） | 223 行；简单 diff（前缀/后缀，非 LCS）+上下文 3 行 |
| 101 | screens/chat/tools/PartGrouper.kt | ✓ | 中文（Resolver 英文 KDoc） | 43 行；PartGroup.Context/Single 分组 |
| 102 | screens/chat/tools/RenderableTurn.kt | ✓ | 中文（Resolver 英文 KDoc） | 146 行；turn 预计算渲染数据；SyntheticNotice KDoc「嵌入气泡」已过期（08-12 独立气泡） |
| 103 | screens/chat/tools/TaskOutputFetch.kt | ✓ | 中文（Resolver 英文 KDoc） | 62 行；#182 全量输出双路取长（DB 500 字符预览 #79；渲染上限 20K/片 4K） |
| 104 | screens/chat/tools/TaskStatusIcon.kt | ✓ | 中文（Resolver 英文 KDoc） | 63 行；全局统一任务状态图标（RUNNING/SUCCESS/ERROR/UNKNOWN，禁止另起） |
| 105 | screens/chat/tools/ToolCardRegistry.kt | ✓ | 中文 | 173 行；工具显示信息解析（WebUI 注册表对齐；snake_case→标题 fallback） |
| 106 | screens/chat/tools/ToolCardRenderer.kt | ✓ | 中文 | 242 行；ToolCallCard 通用工具卡（输入 k: v 展开） |
| 107 | screens/chat/tools/ToolCardResolver.kt | ✓ | 中文 | 23 行；解析器接口（工具名小写→专属卡） |
| 108 | screens/chat/tools/ToolGroupList.kt | ✓ | 中文 | 87 行；聚合卡通用行列表（迁移自 ContextToolGroupCard） |
| 109 | screens/chat/tools/ToolProgressOutputInjector.kt | ✓ | 中文 | 58 行；tool.progress 输出注入 Running 态（spec §2.5；Completed.output 服务器权威覆盖） |
| 110 | screens/chat/tools/ToolSnapshotGrouper.kt | ✓ | 中文 | 117 行；(messageId,规范化路径) 分组（规范 §5.5 B-tier；累积 diff；filediff metadata） |
| 111 | screens/chat/tools/ViewToolRequest.kt | ✓ | 中文 | 15 行；工具快照查看请求（规范 §5.1-5.4；直接携带 part） |
| 112 | screens/chat/tools/cards/ApplyPatchToolCard.kt | ✓ | 中英混合 | 83 行；apply_patch 卡（metadata.patch 优先） |
| 113 | screens/chat/tools/cards/BashToolCard.kt | ✓ | 中英混合 | 208 行；bash/shell 2 行卡（ANSI 剥离；#8 截断提示条；服务器保尾截头 shell.ts 语义） |
| 114 | screens/chat/tools/cards/EditToolCard.kt | ✓ | 中英混合 | 180 行；edit 卡（path/filePath schema 不符实测兼容 2026-08-12；filediff metadata） |
| 115 | screens/chat/tools/cards/GlobToolCard.kt | ✓ | 中英混合 | 123 行；glob 卡（英文 KDoc；匹配计数+可展开文件列表） |
| 116 | screens/chat/tools/cards/PatchCard.kt | ✓ | 中英混合 | 66 行；turn 结束文件修改摘要（LocalSessionDiffs +N/-N） |
| 117 | screens/chat/tools/cards/ReadToolCard.kt | ✓ | 中英混合 | 148 行；read 卡（英文 KDoc；[offset=N, limit=N] WebUI 对齐） |
| 118 | screens/chat/tools/cards/SearchToolCard.kt | ✓ | 中英混合 | 136 行；glob/grep 卡（英文 KDoc；Like WebUI） |
| 119 | screens/chat/tools/cards/ShellCard.kt | ✓ | 中英混合 | 161 行；V2 Shell part 2 行卡（与 TaskToolCard 对称；Exit N/Running） |
| 120 | screens/chat/tools/cards/TaskToolCard.kt | ✓ | 中英混合 | 240 行；Task（子 agent）卡；子会话 id 四键并读（sessionId/sessionID/jobId/childID）；#181/#182 |
| 121 | screens/chat/tools/cards/TodoListCard.kt | ✓ | 中英混合 | 198 行；todowrite 卡（metadata.todos 优先 fallback input） |
| 122 | screens/chat/tools/cards/ToolCardScaffold.kt | ✓ | 中英混合 | 274 行；工具卡脚手架（LocalCopyFeedback；任务状态底色蓝/绿/红 2026-08-11） |
| 123 | screens/chat/tools/cards/WebFetchToolCard.kt | ✓ | 中英混合 | 135 行；webfetch 卡（英文 KDoc；URL+prompt+摘要） |
| 124 | screens/chat/tools/cards/WebSearchToolCard.kt | ✓ | 中英混合 | 187 行；websearch 卡（metadata.results 结构化→文本回退） |
| 125 | screens/chat/tools/cards/WriteToolCard.kt | ✓ | 中英混合 | 102 行；write 卡（英文 KDoc；Like WebUI） |
| 126 | screens/chat/util/ChatColors.kt | ✓ | 中文 | 53 行；agent 色环（TUI local.tsx 对齐；agent 跨会话色稳定） |
| 127 | screens/chat/util/ChatCompositionLocals.kt | ✓ | 中文 | 53 行；13 个聊天 CompositionLocal（LocalSessionStreaming 方案 B 等） |
| 128 | screens/chat/util/ChatFormatters.kt | ✓ | 中文 | 136 行；格式化+步骤状态标签（Making edits 等）+用户命令标签推断 |
| 129 | screens/chat/util/ChatModifiers.kt | ✓ | 中文 | 41 行；触觉/代码横滚/halfScreenHeight；无领域术语 |
| 130 | screens/chat/util/ChatScrollUtils.kt | ✓ | 中文 | 36 行；smoothScrollToBottom/snapToBottom（reverseLayout 底部=第 0 项） |
| 131 | screens/chat/util/ContextStats.kt | ✓ | 中文 | 120 行；上下文角色拆分估算（对应 opencode estimateSessionContextBreakdown；chars/4） |
| 132 | screens/chat/util/JumpTargetExtractor.kt | ✓ | 中文 | 160 行；JumpTarget（Q1/Q2 label）；空壳消息过滤；三次迭代注释 |
| 133 | screens/chat/util/LocalOnViewTool.kt | ✓ | 中文 | 10 行；工具快照查看回调（spec §5.1-5.4 entry1；英文 KDoc） |
| 134 | screens/chat/util/MediaUtils.kt | ✓ | 中文 | 296 行；附件校验/压缩（vision tokens 估算；webp；base64 放大 3.2× 防御） |
| 135 | screens/chat/util/PatchVisibilityResolver.kt | ✓ | 中文 | 47 行；重复 hash patch 折叠（启发自上游 oc-remote v1.6.9 a906a72b） |
| 136 | screens/chat/util/PromptBuilder.kt | ✓ | 中文 | 96 行；@提及拆分 PromptPart（目录 mime application/x-directory；file:/// URL） |
| 137 | screens/chat/util/QuestionParser.kt | ✓ | 中文 | 166 行；question 字段三格式解析（opencode 文本/JSON/纯文本） |
| 138 | screens/chat/util/SafeFlingBehavior.kt | ✓ | 中文 | ~60 行；限速 fling（每帧≤视口/8）；三代沿革注释 |
| 139 | screens/chat/util/SlashCommandRegistry.kt | ✓ | 中文 | 37 行；客户端斜杠命令（镜像 opencode TUI；type server/client） |
| 140 | screens/chat/util/TurnGroupCalculator.kt | ✓ | 中文 | 48 行；turn=两条用户消息间连续 assistant 序列；synthetic 独立规则权威表述 |
| 141 | screens/home/components/ServerDialog.kt | ✓ | 中文 | 246 行；服务器 URL 解析校验（默认 http:// 补 scheme）；autoConnect |
| 142 | screens/sessions/SessionScrollSignal.kt | ✓ | 中文 | 32 行；发送后会话列表滚顶信号（Hilt singleton；SavedStateHandle 跨组件失效注记） |
| 143 | screens/sessions/components/DeleteSessionDialog.kt | ✓ | 中文/无注释 | 65 行；删除会话确认；无注释、无领域术语 |
| 144 | screens/sessions/components/NewSessionQuickDialog.kt | ✓ | 中文/无注释 | NaN |
| 145 | screens/sessions/components/OpenProjectDialog.kt | ✓ | 中文/无注释 | 372 行；目录浏览器（DirectoryPath 全程；V2 /api/fs/list path=/ 返回 500 注记） |
| 146 | screens/sessions/components/RenameSessionDialog.kt | ✓ | 中文/无注释 | 81 行；重命名会话；无领域术语 |
| 147 | screens/sessions/components/TagPickerDialog.kt | ✓ | 中文/无注释 | 255 行；多选标签分配（FilterChip 流式；新建 Tag 表单） |
| 148 | screens/settings/components/ImageCompressionDialog.kt | ✓ | 中文/无注释 | 97 行；图片压缩两对话框（最长边/质量）；无注释 |
| 149 | screens/settings/components/LanguagePickerDialog.kt | ✓ | 中文/无注释 | 78 行；15 语言选择；无领域术语 |
| 150 | screens/settings/components/MessageCountPickerDialog.kt | ✓ | 中文/无注释 | 57 行；初始消息数（20/50/100/200）；无注释 |
| 151 | screens/settings/components/RecentDirectoryCountDialog.kt | ✓ | 中文/无注释 | 79 行；最近目录数（5-50 滑杆）；无注释 |
| 152 | screens/settings/components/ReconnectModePickerDialog.kt | ✓ | 中文/无注释 | 61 行；重连模式（aggressive/normal/conservative）；无注释 |
| 153 | screens/settings/components/TerminalFontSizeDialog.kt | ✓ | 中文/无注释 | 79 行；终端字号（6-20sp 滑杆）；无注释 |
| 154 | screens/settings/components/ThemePickerDialog.kt | ✓ | 中文/无注释 | 64 行；主题（system/light/dark）；无注释 |
| 155 | screens/settings/sections/ChatBehaviorSection.kt | ✓ | 中文/无注释 | 177 行；聊天行为设置区（发送确认/触感/常亮/附件压缩） |
| 156 | screens/settings/sections/ChatDisplaySection.kt | ✓ | 中文/无注释 | 108 行；聊天显示设置区（密度/折叠工具/展开推理/轮次分隔线） |
| 157 | screens/viewer/AnnotationDetailDialog.kt | ✓ | 中文/无注释 | 128 行；批注详情（编辑/删除；startLine:col 范围） |
| 158 | theme/ChatDensity.kt | ✓ | 中文/无注释 | 111 行；ChatDensity 枚举+排版/间距/气泡令牌；无注释、无领域术语 |

## 术语观察

| 概念 | 观察到的变体 | 位置 文件:行 | 与 API 词一致? |
|---|---|---|---|
| 会话 | session（sessionId/sessionMeta/SessionStatus） | ChatScreen.kt:267,283,958 | ✓ session |
| 子会话 | 子会话 onNavigateToChildSession、child session、subSessionId、onOpenSubSession、主会话 isMainSession（sessionParentId==null） | ChatScreen.kt:270,576,820,841,976 | ⚠ child/sub 并存 |
| 消息 | message（messages/messageListState/loadMessages/pendingMessage） | ChatScreen.kt:282,754,963 | ✓ message |
| 堆积队列 | 堆积（STACKED/StackedSheet）、pendingQueue、待处理队列、clearPendingMessages | ChatScreen.kt:586,870,956 | ⚠ 三叫法；与 pendingQuestions/pendingPermissions 撞词根 |
| turn | turn 组、showTurnDividers、turn 气泡 | ChatScreen.kt:777,1016 | ✓ turn |
| 流式 | 流式（流式文本消息）、streaming（isStreaming/LocalSessionStreaming）、SSE token | ChatScreen.kt:251,544,582 | ✓ streaming；SSE 增量叫 token |
| 会话压缩 | 压缩 compactSession/compactionDoneEvent、SSE session.compacted | ChatScreen.kt:363,639 | ✓ session.compacted |
| fork/分享/后台会话 | forkSession、shareSession/shareUrl、backgroundSession(backgroundSessionsSupported) | ChatScreen.kt:628-683 | ✓ fork/share/background |
| 合成通知 | synthetic 系统通知、SYNTHETIC 卡片、空壳 | ChatScreen.kt:781-799 | ⚠ 自定义概念 |
| 草稿/revert | draft（restoredDraft/draftText/clearDraft）、revertedDraftEvent（「恢复到输入框的 revert 事件」） | ChatScreen.kt:289-309 | ✓ draft/revert |
| @ 文件提及 | @ 文件提及、fileSearchResults/confirmedFilePaths | ChatScreen.kt:406 | ⚠ 无 mention 原词 |
| 附件 | attachment（draftAttachmentUris/attachmentHandler/compressImageAttachments） | ChatScreen.kt:292,438 | ✓ attachment |
| 工具卡 | tool card（toggleToolExpanded/ToolCardResolver/cacheToolPart/Part.Tool/ToolState.Completed）、tool=="shell"/"bash" | ChatScreen.kt:547-560,935 | ✓ tool；shell/bash 双名 |
| part | Part.Text/Part.Tool/partId/toolPartIds/getAllPartsMap | ChatScreen.kt:546-576,928 | ✓ part |
| shell 作业 | ShellJob/ShellSheet/runningShellCount；shell 输出三级（事件输出→消息流回填→REST 拉取） | ChatScreen.kt:873,925-952 | ⚠ shell/bash |
| 智能体 | 智能体 agent（AgentSheet/selectAgent）、subagent（runningSubagentCount） | ChatScreen.kt:844-876 | ✓ agent；中英并存 |
| 模型/提供方 | provider/model/variant（selectProvider/selectedModelId/localDefaultModel/toggleDefaultModel） | ChatScreen.kt:900-912 | ✓ provider/model |
| 上下文/令牌 | contextWindow、lastContextTokens、tokenStats | ChatScreen.kt:624 | ✓ context window/tokens |
| 已读 | markSessionRead（「打开期间到达的消息也算已读」） | ChatScreen.kt:517 | ⚠ read |
| 通知抑制 | 活动焦点 onSessionFocused/SessionFocusHolder/抑制事件通知 | ChatScreen.kt:507 | ⚠ focus |
| 快速定位 | 快速定位 quick navigate（QuickNavigateSheet/showQuickNavigate） | ChatScreen.kt:584,716,842 | ⚠ =跳转概念 |
| 滚动触底 | snapToBottom（吸附）、forceScrollToBottom、forceScrollTick（「发送后等新消息增长再滚」）、isAtBottomState；jumpToBottom 已移除、reverseLayout 原生锚定底部 | ChatScreen.kt:254-258,389-404,856-866 | ⚠ 自定义滚动词族 |
| 终端 | terminal（isTerminalMode/ChatTerminalView/viewModel.terminal） | ChatScreen.kt:336,727 | ✓ terminal（本地概念） |
| 能力门控 | serverCapabilities（shareSupported/backgroundSessionsSupported）、TODO 能力探测 probeTodoCapability（V1 恒支持；V2 beta 404 按 baseUrl 记忆缺失） | ChatScreen.kt:291,378,653 | ✓ capability |
| 状态簇 | conversation（messageListState/interactionState/paginationDelegate）、composer（draft*/fileSearch*）、modelSelection、taskUiState | ChatScreen.kt:282-292 | ✓ 对应 CONTEXT.md 状态簇 |
| 渲染密度 | ChatDensity.Compact/Normal、collapseTools、expandReasoning | ChatScreen.kt:769,1013 | ⚠ 自定义 |
| 堆积消息管线 | 堆积消息/堆积队列（pendingQueue）+ PendingMessageRepository/PendingMessagePipeline + 推送中（drainingSessions/pendingDraining「发送中」）+ 入队 enqueue/drain/放行 continueNow/插队 sendOneNow | ChatViewModel.kt:88-90,148-197 | ⚠ pending 为自定义；「推送中/发送中」同指 draining |
| TODO 面板 | sessionTodos（SSE 实时 + REST hydrate 同源）、todoCapable、TODO 能力探测 | ChatViewModel.kt:200-231 | ✓ SSE TodoUpdated 事件 |
| 任务聚合 | 任务聚合 TaskAggregator（subagent + shell）、active 轮询、taskUiState（角标计数）、refreshActiveSessions、僵尸自愈 L3 校验 | ChatViewModel.kt:261-282 | ⚠ 自定义聚合概念 |
| 前台/后台 subagent | 前台 subagent 转为后台（对应 TUI ctrl+b）、backgroundSession | ChatViewModel.kt:284-292 | ✓ background（TUI 对齐注释） |
| 已读水位线 | 已读位置=红点模块自身水位线（服务器域）、markSessionRead、不再扫描消息缓存（#171 客户端终结戳混入） | ChatViewModel.kt:328-341 | ✓ 对应 CONTEXT.md 红点时钟域 |
| 能力位门控 | UI 门控只读能力位（null 版本=全开放 #172）、apiVersion、ServerCapabilities.of | ChatViewModel.kt:112-116 | ✓ 对应版本 seam |
| FSM/会话状态 | sessionStateService（类型实为 SessionStateRepository）、statusFlow、SessionStatus.Busy/Retry、markSessionIdle、onClientAbort | ChatViewModel.kt:81,343,849 | ⚠ 同物双名 Service/Repository |
| token 统计口径 | lastContextTokens = input+cache.read（ACP 口径）、消息级快照（lastOrNull output>0）直接覆盖、session 级累计（SQL 累计/压缩不下降）仅 totalCost、context% | ChatViewModel.kt:603-712 | ✓ 对齐官方 TUI/ACP（注释含源文件引用） |
| 压缩事件注入 | compactSession 发起前置 CompactionStarted（V2 无服务器 started 事件，进行中气泡唯一驱动）→ CompactionEnded；SSE session.compacted=压缩完毕确切时刻 | ChatViewModel.kt:439-481,635-656 | ✓ session.compacted |
| 跳转预组合 | 跳转预组合策略 JumpPrefetchStrategy（组合+测量不显示→零渲染过程）、滚动方向预测、原 cacheWindow（ahead/behind 1.5 屏）已被替代、prefetchJumpTargets 快速导航数据源预取 | ChatViewModel.kt:523-541,761-765 | ⚠ 预组合/预取词族 |
| 未完成消息修复 | completed==null 修复 fixIncompleteMessagesIfIdle（SSE 完成事件丢失/服务器重启）、「Thinking…」计时器 | ChatViewModel.kt:742-746 | ✓ completed 语义（=流式 turn） |
| undo/revert/redo | undoMessage / revertMessage（revert 到特定用户消息）/ redoMessage / revertSession（undoRedoUseCase）/ setRevert 过滤器（RS-008） | ChatViewModel.kt:891-937 | ⚠ undo 与 revert 两词并存 |
| 中止 | abortSession（RS-006 先取消 SSE job 再更新 FSM）、abort | ChatViewModel.kt:841-860 | ✓ abort |
| 问题/权限应答 | replyToQuestion/rejectQuestion（QuestionAnswerStore 应用级答案存储）、replyToPermission/savePermissionRule（PermissionAsked 事件） | ChatViewModel.kt:833-874 | ✓ permission/question 事件 |
| 斜杠命令 | executeCommand（斜杠命令）、runShellCommand | ChatViewModel.kt:955-959 | ✓ command |
| 状态簇（VM） | ①会话上下文簇 sessionContext ②会话数据簇 conversation ③输入簇 composer ④模型配置簇 modelSelection ⑤会话操作簇 sessionOps | ChatViewModel.kt:578-595 | ✓ 对应 CONTEXT.md 状态簇 |
| 拆分状态族 | MessageListState（消息列表与分页数据）/SessionMetaState（会话元数据）/InteractionState（用户交互状态）/TokenStatsState（token 使用统计）/ModelConfigState（模型/agent 配置） | ChatUiState.kt:13-108 | ⚠ 自定义状态分类 |
| 自动续载 | autoLoadPaused=自动续载暂停（连续失败达上限，停止自动分页等待手动触发）、hasOlderMessages/isLoadingOlder | ChatUiState.kt:21-24 | ⚠ 对应 CONTEXT.md「跳转锁=autoLoad 抑制」Avoid 注记 |
| synthetic | isSynthetic（synthetic 系统通知，后台任务/subagent 完成注入）；KDoc 称「嵌入 assistant turn 气泡内渲染」 | ChatUiState.kt:125-126 | ⚠ KDoc 行为描述已过期（见失实注释） |
| 滚动状态集群 | 滚动状态集群（autoScroll/forceScroll/isAtBottom 三组滚动状态） | ChatScrollController.kt:27-29 | ⚠ 「集群」vs CONTEXT.md「状态簇」用词不一 |
| 自动滚动/自动跟随 | autoScrollEnabled=自动滚动开关；forceScrollToBottom 注释称「强制启用自动跟随」 | ChatScrollController.kt:53,60-63 | ⚠ 同一开关两种中文叫法 |
| 强制滚底 | forceScrollTick（强制滚动触发计数）、ForceScrollExecutor=强制滚底执行器（发送后跟随）、GROWTH/FLING/VERIFY 三超时（5s/2s/1s）、atBottom 判定（index==0 且 offset<100） | ChatScrollController.kt:57-68,210-270 | ⚠ 自定义 |
| 位置锚定 | requestScrollToItem 同帧位置锚定（apply 后 layout 前）vs 旧「跟随最后一条消息的 key」 | ChatScrollController.kt:115-117 | ⚠ 锚定词族 |
| fling 跳底 | fling 跳底、fling 惯性、拖拽摩擦、等待 fling 真实停止（2s 兜底） | ChatScrollController.kt:125-166 | ⚠ 自定义（滚动稳定性域） |
| 悲观消息 | 悲观消息模式（发送后不创建乐观消息，等 SSE 回显 MessageUpdated）、悲观发送（ChatViewModel） | ChatSendDelegate.kt:24-25,134-137; ChatViewModel.kt:119 | ✓ 对齐 opencode 官方行为；「模式/发送」两叫法 |
| 消息回显 | 服务器 SSE 回显（MessageUpdated）后消息出现在列表 | ChatSendDelegate.kt:25,135 | ✓ message.updated 事件 |
| top/bottom 命名反转（已核实） | SessionScrollSignal.requestScrollToTop：发送后设置、返回时 SessionListViewModel 消费将会话**列表**滚回顶部（非聊天列表）；聊天列表触底走 ForceScrollExecutor/snapToBottom | ChatSendDelegate.kt:116; SessionScrollSignal.kt:6-28 | ⚠ 同名 top 信号跨两屏两义（列表顶部/消息流底部） |
| 发送信号分离 | sendFailureSink（AlertDialog）与 errorSink（snackbar）分离；onSendSuccess 驱动清空输入框 | ChatSendDelegate.kt:50-53 | ⚠ 自定义 |
| 置 Busy 时机 | POST 成功后才置 Busy（onClientSendParts）；execution.started 事件随后保持 Busy；isSending=上传中 | ChatSendDelegate.kt:148-153 | ✓ execution.started |
| revert 清除 | 发送前 clearRevert（message.removed SSE 事件已清理旧消息缓存） | ChatSendDelegate.kt:130-132 | ✓ message.removed |
| 标题占位刷新 | refreshSessionTitleDelayed（8s 延迟 REST 刷新；"New session - ..." 占位模式） | ChatSendDelegate.kt:77-98 | ⚠ 自定义 |
| 聚合状态管道 | sessionMetaState（7 源 combine：标题/状态/agent/streaming）、tokenStatsState、directoryState（会话目录/工作目录）；uiState Legacy 已退役 | ChatStateAggregator.kt:20-148 | ⚠ 自定义聚合层 |
| FSM 单一真相源 | sessionStateService.statusFlow（FSM 驱动）= busy/idle/activity 状态的单一真相源；activityFlow（SessionActivity.Streaming） | ChatStateAggregator.kt:50-62 | ✓ 对应 AGENTS.md 承重规则 |
| showBusy 转圈 | 输入区 showBusy 转圈、「发送后一直转圈」诊断 | ChatStateAggregator.kt:83-85 | ⚠ 口语诊断词 |
| 可渲染 parts | 可渲染（Text/Reasoning/Patch/File/Permission/Question/Abort/Retry/Tool）vs 不可渲染（StepStart/StepFinish/Snapshot/Subtask/Compaction/Agent/SessionTurn/Unknown）；原始交错顺序 | ChatParts.kt:9-38 | ✓ part 子类型均为 API 原词 |
| step parts（历史） | contentParts/stepParts 拆组乱序（已修复的历史描述） | ChatParts.kt:33-35 | ⚠ 历史术语残留于注释 |
| 链接分类 | LinkTarget.Web/RelativePath/AbsolutePath、isLikelyDirectory 启发式、「v1 中可接受」 | LinkUriHandler.kt:20-138 | ⚠ 「v1」歧义（产品迭代 vs API V1） |
| 组合器 | MessageDataDelegate=组合器（分页→paginationDelegate；自身保留 SSE 观察、消息/零件状态、工具展开、pending 问题/权限加载） | MessageDataDelegate.kt:46-52 | ⚠ 「零件」=parts 的孤立中文译名；KDoc 提及 optimisticStore 已不存在 |
| V1 前缀 | V1 消息状态、从 V1 chatRepository flow 派生、V1 源派生、通过 V1 API 刷新 | MessageDataDelegate.kt:88,138,280,348 | ⚠ V1 指数据层旧管线称呼 |
| 48ms 批处理 | 每 ~48ms 全量重建（~2000 条）、ChatMessage 实例缓存（引用稳定复用）、GC「用一会儿后滑动卡顿」 | MessageDataDelegate.kt:96-104,265 | ✓ 对应 SSE 48ms token 批处理铁律 |
| 工具进度注入 | tool.progress 内容累积到 Running 工具 output（ToolProgressOutputInjector）、callId 全局唯一、#180 子会话 id（metadata.sessionID） | MessageDataDelegate.kt:170-183,235 | ✓ tool.progress 事件 |
| revert 过滤 | OpenCode 模式：消息 ID（ULID 单调递增）字符串比较过滤，id<=revertId 含 revert 点及之前 | MessageDataDelegate.kt:195-199 | ⚠ 注释称 <=，代码为 <（见失实注释） |
| 排队消息 | queuedMessageIds 从 FSM 状态派生（Idle 强制清空）、pending assistant 检测（completed==null）、P5-1/P5-3 | MessageDataDelegate.kt:205-228 | ✓ completed 语义 |
| 重复补丁折叠 | suppressRepeatedPatchHashes（服务器对未变更 session diff 重复推送相同 hash） | MessageDataDelegate.kt:248-250 | ⚠ 自定义 |
| 快速导航数据源 | 快速导航全量列表 loadJumpTargets、Room 热表（≤1000 条）vs 内存热视图（~30 条窗口）、幽灵消息、reconcile 全量替换（服务器权威）、mergeUnrepliedUsers 合并连续无回复 user、jumpTargetsServerSync | MessageDataDelegate.kt:365-498 | ⚠ 快速导航/跳转目标词族 |
| Q（口语） | 「Q1 之上还有内容」「漏 Q」——Q=用户提问消息 | MessageDataDelegate.kt:370,487 | ⚠ 口语缩写（注释中） |
| REST 权威恢复 | REST GET /question 全量权威源（resolvePendingQuestionReplacement 不合并）、key 合成 fallback q$index、V2FormMapper、keyedAnswers | MessageDataDelegate.kt:38-44,526-589 | ✓ REST question 端点 |
| 子会话权限/问题 | 包含子会话的问题/权限（childSessionIds=parentId==sid）、sourceSessionTitle、按目标 sessionId 分组匹配 SSE 存储模式 | MessageDataDelegate.kt:538-640 | ✓ parent/child |
| SSE 观察器 | sseJob 取消/重启（abortSession/revertMessage 暂停快照更新，RS-006/RS-008）、「飞行中的 SSE 观察器」 | MessageDataDelegate.kt:58-60,322-330,644-650 | ✓ |
| 分页状态机 | PaginationFSM（游标+hasOlder+防风暴 7 合 1）、applyTransition 纯函数转移、入口互斥（synchronized） | MessagePaginationDelegate.kt:25-110,184-260 | ✓ FSM 模式词 |
| 防风暴 | 自动续载防风暴（连续失败退避 backoff、autoLoadPausedUntil、autoLoadFailures、RECOVERED） | MessagePaginationDelegate.kt:28,80-81,114-116,246-254 | ⚠ 自定义（backoff 意译「防风暴」） |
| 游标语义 | PaginationCursor：HotStart（热表最老作 beforeId）/ Archive（归档时间游标 beforeCreated）/ Network（serverCursor 透传 V2 / id+created 回落 V1） | MessagePaginationDelegate.kt:64-72,193-216 | ✓ cursor（V2 服务器窗口语义） |
| 更早/更新方向 | hasOlderMessages=更早方向 / hasNewerMessages=更新方向、loadOlderMessages/loadNewerMessages、加载更早/加载更新 | MessagePaginationDelegate.kt:76-93 | ⚠ older/newer 中英并用 |
| 定位加载 | 快速导航定位加载 loadAround（以 target 为中心双向加载）、本地优先（Room 热表命中→无网络往返）、V2 双向 cursor / V1 降级单条+before、isLoadingAround | MessagePaginationDelegate.kt:264-407 | ⚠ jumpToMessage 词族 |
| 自定义 newer 锚点游标 | {id:target, order:desc, direction:previous}（CursorCodec.encodeV2 构造）、newerAnchorCursor、cursor.previous 真实继续游标 | MessagePaginationDelegate.kt:307-344 | ✓ V2 cursor 语义 |
| V1/V2 能力差异 | V1 协议无 after/cursor 能力（更新方向固不可用→no-op）；#172 能力语义经游标策略（版本差异收编） | MessagePaginationDelegate.kt:308-344 | ✓ 对应 CONTEXT.md 版本 seam |
| 升序化防御 | #82：服务器 next/previous 响应顺序不保证升序，合并前统一升序化（mergeSortedMessages 前提）；「跨页跳转后最新消息从 UI 消失」 | MessagePaginationDelegate.kt:227-232,321-326,362-366 | ⚠ 注释口语取证 |
| 合并策略 | MergeStrategy.SSE_PRIORITY / APPEND_ONLY（归档来源只进内存不落热表→防死循环） | MessagePaginationDelegate.kt:132,226-230 | ⚠ 自定义 |
| 集群字母代号 | C 集群 delegate、MessageData 集群、跨集群回调、B↔C↔G / B↔D↔G 编排 | MessagePaginationDelegate.kt:118-124; ChatViewModel.kt:844,896 | ⚠ 字母代号体系（注释暗语） |
| 修复编号体系 | #40/#41/#44/#56/#76/#82/#171/#172/#176/#180/#182、RS-006/007/008、P5-1/P5-3、E8-1/E2E-B/E2E-C、TD-1、D-9/#11-4、F5、A/B 复评 | 全域多文件 | ⚠ 多套修复编号体系混用于注释 |
| 跳转预组合（名存实亡） | JumpPrefetchStrategy 类名=跳转预组合策略；2026-08-21 已移除跳转目标预组合（pendingIndex/maybeScheduleJump），仅剩滚动方向预测预组合（速度自适应窗口） | JumpPrefetchStrategy.kt:9-21 | ⚠ 类名与现职责不符 |
| 速度自适应预取 | PREFETCH_AHEAD fling=6/快拖=3/慢拖=0、EMA 速度、VELOCITY_FLING=6000px/s、铁律 7（防整气泡跳过）、0faa6984 块级分片（13 万字符→~5000 字符/item） | JumpPrefetchStrategy.kt:26-66 | ⚠ 自定义调参词族 |
| 主线程预取约束 | 预取在主线程（AndroidPrefetchScheduler view.post+Choreographer deadline）、120Hz 帧预算 8.33ms、「新消息临近顿一下」症状①/症状③ | JumpPrefetchStrategy.kt:29-42 | ⚠ 注释口语症状编号 |
| 转后台提示 | 转后台 synthetic 系统提示、BACKGROUND_SYNTHETIC_MARKERS 服务器模板变体（#136 D2-L55）、分割线渲染「已移至后台」 | ChatMessageList.kt:126-141,1255-1288 | ⚠ 自定义（依赖服务器文案匹配） |
| turn 组 | turnGroups（v6 缓存）、computeTurnGroups、messagesSignature、流式消息永不冻结（37d9a6ac 回归教训）、t_/u_ key 前缀 | ChatMessageList.kt:188-207,1083-1085 | ✓ turn |
| 流式判定 | streamingMsgId 仅基于 completed 时间戳（v360）；sessionMeta.isStreaming（668384e3 加入）可能 stuck false——「不要再加 takeIf(sessionMeta)」 | ChatMessageList.kt:229-239 | ✓ completed 语义（=流式 turn） |
| 渲染缓存指纹 | renderableTurns/RenderableTurn、MessageFingerprints.messageFingerprint、指纹覆盖流式追加/工具输出注入、早期「activeTools 非空整体禁用缓存」 | ChatMessageList.kt:242-285 | ⚠ 自定义优化概念 |
| 高度补偿 | CompensateState（shouldCompensate/lastHeight）、layout{} 补偿、requestScrollToItemNoCancel、漂移补偿（ToolProgressCard）、COMP-MSG/COMP-TOOL、仅流式消息（isStreamingMsg 门控） | ChatMessageList.kt:287-310,894-936,1086-1117 | ✓ 对应 SSE 滚动铁律 |
| 跳转定位状态机 | JumpNavigationController、JumpPhase（Preparing→Measuring→Settling→Displayed/Failed）、蒙版/门控/锁从状态派生（单一真相源）、jumpLockActive（异步窗口∪进行中∪终点后 300ms）、透明门控、JumpMaskOverlay、easeInOutCubic | ChatMessageList.kt:487-529,572-575,1384-1389,1442-1447 | ⚠ 跳转词族（jump） |
| autoLoad 锁 | #159 跳转定位锁定（jumpToMessage 期间抑制 autoLoad——older 批插入会把目标推下视口）、markJumpPending/clearPendingJumpLock、fire-time 复查 isJumpInProgress | ChatMessageList.kt:515-530,564-567,716-727 | ✓ 对应 CONTEXT.md「跳转锁=autoLoad 抑制」Avoid 注记 |
| 自动分页 | 自动分页/自动续载/autoLoad、距顶部 8 项（nearTop=total-topVisible<=8）、contentDoesNotFillViewport 不足一屏触发、500ms 指数退避、距视觉底部 8 项（更新方向）、ChatPaging | ChatMessageList.kt:650-770 | ⚠ 自动分页/自动续载两叫法 |
| 渲染供给 | 渲染供给协调器 RenderSupplyCoordinator（chunkPlans/recentStreamedTurnKeys）、RenderSupplyWorld 世界快照装配、RenderReadinessRegistry 渲染就绪注册表、滚动预解析驱动（preparse driver）、noteStreamTurnEnded（延迟分片防 key 裂变闪跳）、真机取证 412→16746px 暴涨/fling 瞬移 1.4 万 px「下跳」 | ChatMessageList.kt:379-420,531-563,1449 | ✓ 对应 CONTEXT.md 渲染供给（Avoid: 预解析驱动器/分片协调器） |
| 块级分片 | 块级分片（0faa6984）、分片发射表 chatEntries=displayItems 经 chunkPlans 展开（巨型 turn→N chunk item）、ChatEntry.Chunk/UserChunk/Turn、#c<i> 后缀、displayEntryStart 双向索引单一真相源、ChunkedAssistantMessage/ChunkedUserMessage、spacedBy 移除改 item 级 padding | ChatMessageList.kt:395-405,849-851,990-1078 | ⚠ 分片词族（chunk） |
| 嵌入式提问 | 嵌入式提问卡片（tool.messageId 匹配，嵌入 ReasoningBlock 内部）、unembeddedQuestions 保底独立显示、一次一个最旧优先、positionLabel 1/N | ChatMessageList.kt:324-340,948-968,1161-1192 | ✓ question 语义 |
| 定位发起卡片 | onLocateTask 定位发起卡片（task/subagent metadata.sessionId/sessionID/jobId）、V1 task 工具 vs V2 subagent 工具（jobId=nextSession.id, task.ts）、synthetic 完成通知 <task id> 匹配、3 秒高亮 highlightedTurnKey | ChatMessageList.kt:608-640,1422-1440 | ✓ task/subagent 工具名（V1/V2 键名差异） |
| 横幅族 | Revert 横幅/Compaction 横幅/Retry 横幅/工具进度卡片/步骤进度指示器、bannerCount（与 item{} 块保持一致约束） | ChatMessageList.kt:359-377,857-947 | ⚠ banner 词族 |
| 压缩触发消息 | isCompactionTrigger（Part.Compaction）、CompactionCard 可展开卡片（分割线收起态+摘要展开态）、长按=回退确认 | ChatMessageList.kt:1197-1252 | ✓ compaction part |
| 限速 fling | SafeFlingBehavior 限速 fling（每帧 ≤ 视口高/8，不冲入未组合区） | ChatMessageList.kt:833-835 | ⚠ 自定义 |
| 死代码注销 | LocalMarkdownStateRegistry（写-only 死注册表）、JumpBubbleObserve 已删除（2026-08-21 卫生清理 D-10/D-11） | ChatMessageList.kt:143-147,569,826 | ⚠ 注释记录删除史 |
| ScrollDiag 诊断 | ScrollDiag（LEAP 检测/RESIZE/gesture 起止）、RaceProbe（ENTRIES/VIEW/JUMP probe） | ChatMessageList.kt:449-486,1120-1150 | ⚠ 诊断标签词族 |
| 渲染供给（词条源头） | 渲染供给协调器（Render Supply）=「视口前方的渲染资源预备决策的唯一决策点」；注释明言术语见根目录 CONTEXT.md | RenderSupplyCoordinator.kt:13-31 | ✓ CONTEXT.md 渲染供给词条直接源头 |
| 预解析参数 | PREPARSE_AHEAD=20（8→14→20 调参史）、PREPARSE_MIN_CHARS=200、PREPARSE_LRU=48、everVisiblePartIds 冷热区分（热=曾进视口/组合缓存池，冷=从未组合裂变零成本） | RenderSupplyCoordinator.kt:51-61,270-283 | ⚠ 自定义调参词族 |
| 裂变安全边距 | FISSION_SAFE_MARGIN=6（±0 太窄：滚离滚回回归 27-69ms；±14 太宽：首滑恒单体 84-93ms 巨帧；±6 定标）、key 裂变（1→N）、F1 锚点重解析（partId 反查陈旧索引）/F2 视口防线/F3 跳转门控 | RenderSupplyCoordinator.kt:185-247,285-291 | ⚠ 裂变词族（fission） |
| 跳转稳定窗口 | 终点+2s 内不提交分片（jumpActiveOrSettling）、lastJumpEndAtMillis 阶段 2 收编（写读同模块） | RenderSupplyCoordinator.kt:63-79,207-215 | ✓ CONTEXT.md 跳转稳定窗口（2s） |
| 流式 turn 禁预解析 | 流式中途部分文本快照若被预解析 registry 永不重析 → 分片渲染部分 AST = 回复尾部永久截断（末段带统计栏「极具迷惑性」） | RenderSupplyCoordinator.kt:22-23,109-113 | ✓ CONTEXT.md 流式 turn 词条 |
| 块级分片（AST） | MdChunkPlan（AST 顶层块区间 [from,to)）、computeChunkPlan 字符预算切块、CHUNK_MIN_CHARS=3000/CHUNK_TARGET_CHARS=2500（130K≈26 片）、mikepenz 0.43.0 无块级懒加载、LazyItem 粒度切分 | MarkdownChunking.kt:8-56; RenderSupplyCoordinator.kt:293-297 | ⚠ chunk 词族 |
| 用户纯文本分片 | UserTextChunkPlan、splitUserTextChunks（行边界对齐、joinToString==原文+尾部换行）、userPlanCache（partId+长度缓存）、USER_CHUNK_*（对齐 assistant） | MarkdownChunking.kt:59-131 | ⚠ 与 assistant 分片同机制两套命名 |
| 分片发射表 | ChatEntry.Turn/Chunk/UserChunk、key t_<turnId>#c<i>/u_<msgId>#c<i>、ChatEntries 双向索引（LazyColumn↔displayItems 单一真相源）、C-R3 turn 粒度修复（原全局粒度 spec/impl 背离） | MarkdownChunking.kt:133-256 | ⚠ |
| 反射滚动 | LazyListReflection（绕过官方 scroll{} 互斥锁——只设待定位置不杀 fling）、requestPositionAndForgetLastKnownKey、降级官方 requestScrollToItem、Compose BOM 2026.05.01 私有成员依赖 | ScrollCompensation.kt:17-93 | ⚡ 反射脆弱性注释（版本升级须手测） |
| 跳转状态机相位 | JumpPhase：Idle→Preparing(蒙版:预解析+估算定位)→Measuring(透明:测量+列表同步)→Settling(收敛修正)→Displayed/Failed；showMask/gateOpen/jumpLockActive 三派生（单一真相源）；JUMP_UNLOCK_DELAY_MS=300 | JumpNavigationController.kt:14-58,239-244 | ⚠ 相位词族 |
| 代际令牌 | A-F1/D-1 Job 管理（新跳转取消旧协程，防两个位置写者互搏/写穿）、cancelPreviousJump、isActive 防写穿 | JumpNavigationController.kt:206-217,264-269 | ⚠ 自定义并发术语 |
| 目标 key 消歧 | targetKeyPrefix u_/t_（同 id 的 user 消息与 assistant turn 不再歧义）、findJumpTargetItem 前缀匹配取最小 index chunk（首 chunk 顶边=消息顶边） | JumpNavigationController.kt:147-152,67-88 | ⚠ key 约定 |
| 渐进定位 | 小步逼近（每步≤vh/2）、区域签名（全部可见 item key:size 连续 4 轮不变）、5s 超时、夹持修复（幽灵 gap：内容不足一屏 scrollBy 被夹持→接受当前位置，step=-343×7 实证）、时钟基 elapsedRealtime（currentTimeMillis 可被 NTP 倒退） | JumpNavigationController.kt:305-401,387-396 | ⚡ 取证式注释 |
| 跳转滚动 API 语义 | A-F4：跳转路径用官方挂起 scrollToItem（取消 fling=预期语义）；反射 NoCancel 仅保留给 SSE 高度补偿 | JumpNavigationController.kt:289-298 | ⚠ 两种滚动 API 分工注释 |
| 稳定窗口（跳转） | Displayed 后稳定窗口（现 900ms；用户触摸即退出；gap>8f 才修） | JumpNavigationController.kt:412-436 | ⚠ 头部 KDoc 仍写 1.5s（失实） |
| 渲染就绪 | RenderReadiness：Pending→Parsing→Parsed→Ready(finalHeight)/Failed；渲染就绪注册表=「消息级就绪信号的唯一真相源」；Mikepenz 官方 Parse-ahead 模式 | RenderReadiness.kt:13-58,82-97 | ⚠ Ready/awaitReady 已死（生产者仅 preParse） || 注册表键漂移 | preParse(msgId) 形参名 msgId，实际调用传 part.id（「key=part.id 服务器全局唯一…不能混入 msgId」） | RenderReadiness.kt:91; RenderSupplyCoordinator.kt:122-127 | ⚠ 形参名与实参语义不符 |
| 重组风暴 | 快照 Map 读依赖整 Map 级（拖动期每帧 ~10 scope 26ms 重组风暴、慢拖 1s 仅 4 帧）→ConcurrentHashMap+条目级 StateFlow | RenderReadiness.kt:59-66 | ⚠ 性能取证词 |
| 限速 fling | 每帧 ≤ 视口高/8（小屏兜底 180px）、carry 保总距离、friction=3、穿越≥8 帧给预解析/预组合完成窗口、412px→+16334px 暴涨取证 | SafeFlingBehavior.kt:12-58 | ⚠ 自定义 |
| fling 治理沿革 | e651daf1 切块 fling→cd1ae6ee v1 迭代移除（cacheWindow 替代）→JumpPrefetchStrategy→限速回归（「当年两者未同时存在」） | SafeFlingBehavior.kt:25-28 | ⚠ 「v1 迭代」第三种 v1 语义 |
| 触底双函数 | smoothScrollToBottom（动画自动滚动，发送后跟随，重试 48ms=3×16ms——**已零调用**）、snapToBottom（显式用户操作即时吸附） | ChatScrollUtils.kt:6-35 | ⚠ 跟随/吸附词族 |
| Q 编号（UI 文案） | JumpTarget.label="Q1"/"Q2"…（快速导航列表实际显示文案）；「用户问题」 | JumpTargetExtractor.kt:11-62 | ⚠ Q=question 的 UI 缩写（不仅是注释口语） |
| 空壳消息 | 空壳 user 消息（无 parts 且无 summary.body；实测 62 条中 23 条、服务器单条查询 404）、noTextPlaceholder"(无文本)"保留参数 | JumpTargetExtractor.kt:31-38 | ⚠ 自定义 |
| 可导航判定 | isNavigableUser（user 且非 synthetic、至少一个非空 Part.Text，与快速导航列表过滤一致）、hasText | JumpTargetExtractor.kt:132-140 | ⚠ 自定义 |
| 当前问题/时间锚点 | findCurrentQuestionMsgId（三次迭代：索引不一致→offset 不可靠→key u_ 前缀 index 最大）、findCurrentAnchorTimestamp（降级定位） | JumpTargetExtractor.kt:76-159 | ⚠ |
| turn 定义（权威） | 「一个 turn 是两条用户消息（或列表开头/结尾）之间的连续 assistant 消息序列」；synthetic 独立规则（2026-08-12 用户决策：独立气泡，原 isAdjacentToAssistant 嵌入规则移除） | TurnGroupCalculator.kt:5-29 | ✓ turn；佐证 ChatUiState KDoc 失实 |
| Markdown 归一化链 | normalizeMarkdown（最小化：\r\n→\n、GFM 表格前空行、用户单换行→双换行）→ normalizeTaskListMarkers → splitOversizedParagraphs = normalizeForRender（预解析与渲染必须同一归一化——否则高度 214 vs 331） | MarkdownContent.kt:99-148 | ⚠ 归一化词族 |
| 超长段落空行化 | splitOversizedParagraphs（C-F1：LLM 巨型清单「1 - one」不构成 GFM 列表→129K 单段；阈值 3000 与 CHUNK_MIN_CHARS 同量级；围栏/表格/列表/引用/缩进/标题不参与） | MarkdownContent.kt:150-237 | ⚠ 自定义 |
| 渲染路径三分 | preParsedState（预解析直渲无 loading）/ asyncParse（非流式 fallback 后台解析）/ 库 rememberMarkdownState（流式必须——snapshotFlow+conflate 增量，retainState=true） | MarkdownContent.kt:240-260,510-566 | ✓ 对应 SSE 滚动铁律（retainState） |
| 兼容性死参数 | customFontSize/immediate「保留为了调用点兼容性…有意不使用」（排版由 LocalChatDensity 驱动；Mikepenz 同步解析 immediate 无效果） | MarkdownContent.kt:245-264 | ⚠ 自述死参数 |
| 分片渲染槽 | chunkSuccessSlot（只渲染 [from,to) 顶层块；引用式链接在解析期写入 referenceLinkHandler，拆开渲染不破坏跨块引用） | MarkdownContent.kt:625-640 | ⚠ |
| HTML 载荷检测 | looksLikeHtmlPayload（doctype/html 标签启发式）、normalizeHtmlForEmbeddedPreview（注入 override CSS 的嵌入预览） | MarkdownContent.kt:55-95 | ⚠ 自定义 |
| 可点击项 | ClickableItem.Link/CodePath（行内代码看似路径→可点击）、双路定位（链接 span 精确 offset + 顺序文本搜索游标单调推进）、TalkBack semantics onClick | ClickableMarkdown.kt:25-200 | ⚠ 自定义 |
| 任务标记归一化 | ☐/☑/✅ → GFM [ ]/[x]、CommonMark 围栏跟踪语义 | NormalizeTaskListMarkers.kt:3-40 | ⚠ 自定义 |
| 消息卡角色 | MessageCardRole.USER/ASSISTANT/SYNTHETIC；isStreamingTurn（turn 级流式判定——多消息 turn 代表消息 oldest 可能已完成仅看代表会漏判）；pendingQuestion 嵌入思考卡片 | MessageCard.kt:9-36 | ✓ turn 流式语义 |
| synthetic 第 4 叫法 | 「三种角色（用户/智能体/后台消息）」——后台消息=synthetic；另有 后台通知/系统通知/合成通知/转后台提示 | MessageBubble.kt:36-44 | ⚠ 同概念多中文叫法（见冲突 25） |
| 气泡三栏 | 标签栏（[时间][labelLeading][类型标签][labelTrailing]）/正文栏/统计栏（agent/模型/时长/复制、QUEUED 徽章） | MessageBubble.kt:43-125 | ⚠ 自定义 UI 结构词 |
| 智能体气泡 | 「智能体消息气泡」label=chat_label_agent（SmartToy 图标）；renderItems（文本/推理/工具卡片/分隔线）；统计栏（agent 徽标/提供商·模型/时长/复制） | MessageCardAssistant.kt:64-68,163-233 | ✓ agent/provider/model |
| agent 徽标交互 | 点击徽标=选中该 agent 到输入栏（复用 selectAgent 链，影响下一次发送——历史消息 agent 不可改写，官方语义） | MessageCardAssistant.kt:83-85,181-186 | ✓ agent 语义 |
| 提问卡锚定 | questionAnchorPartId（优先 tool.callId 精确匹配，否则最后一个 Reasoning/Tool part；原实现每 part 渲染一张→N 张重复卡） | MessageCardAssistant.kt:123-146,307-335 | ✓ callId |
| 统计栏合并 | 「流式/完成是同一事物的两种状态（2026-08-07 合并）」；流式=实时耗时 ticker；完成=固定时长+复制 | MessageCardAssistant.kt:147-151 | ⚠ |
| 流式耗时 | StreamingElapsedText（#47：ticker 独立子 composable；注释称 300ms——代码 delay(100)，见失实注释；48ms SSE flush 叠加 ~30 次/s footer 重组史） | MessageCardAssistant.kt:357-380 | ⚠ |
| 分段渲染 | ChunkedAssistantMessage：分段 shape（首顶圆角/中直角/末底圆角）、①标签栏仅首段 ②前置 items ③分片主体 ④末段后置+统计栏+error、AMOLED 分片段不描边、ChunkStatsBar（分片恒非流式） | MessageCardAssistant.kt:382-666 | ⚠ chunk 词族 |
| 渲染项模型 | RenderItem.TurnDivider/SyntheticNotice/GroupedParts；PartGroup.Context/Single（渲染预计算——组合期间零过滤/零分组） | MessageCardAssistant.kt:236-306 | ⚠ 自定义中间表示 |
| Token 圆环移除 | 「2026-08-15 用户要求：移除 Token 占比圆环——无信息量」（TokenRatioRing 疑似遗留） | MessageCardAssistant.kt:212-213 | ⚠ |
| question 工具 V1 形态 | #131：V1 的 question 工具调用消息是 Part.Tool 而非 Part.Reasoning（原条件仅 Reasoning → 卡片消失+输入框禁用） | MessageCardAssistant.kt:310-314 | ✓ question part 形态差异 |
| QUEUED 徽章 | chat_queued；「悲观模式：无 Sending/Failed/Sent 状态（消息以服务器权威直接出现）。仅保留 QUEUED 徽章（FSM 队列状态派生）」 | MessageCardUser.kt:166-178 | ✓ queued=排队消息 |
| 撤销 vs revert | Undo 图标 contentDescription=chat_revert（撤销=回退=撤回）；ConfirmDialog 撤回确认 | MessageCardUser.kt:180-194,261-271 | ⚠ 撤销/撤回/回退/revert 四词混用 |
| 用户消息纯文本 | 「用户消息不渲染 Markdown（官方 TUI 对齐 tui index.tsx:1420 纯文本）」；v1_regression_e2e 误判根因 | MessageCardUser.kt:360; PartContent.kt:76-85 | ✓ TUI 对齐；v1 又一语义（测试套件代） |
| 命令标签 | userCommandLabel（RateReview 图标）、userFallbackText（summary.body/title 回退） | MessageCardUser.kt:100-103,232-259 | ⚠ |
| reasoning 计时三态 | part 未结束 ∨（会话流式 ∧ part 无 end）→ 续计时；start 回退链（part.time.start→组合时刻）；V2 reasoning.started 无服务器时间戳 | PartContent.kt:101-131 | ✓ reasoning.started |
| question 工具分流 | 活跃不渲染（QuestionCard 展示）/完成渲染「Asked」+答案/error 态同构渲染（"Tool execution interrupted[: question]"、"The user dismissed this question"）/历史兼容输出嗅探 | PartContent.kt:145-233 | ✓ question 工具状态语义 |
| todoread/todowrite | todoread 完全过滤（WebUI 约定）；todowrite→TodoListCard | PartContent.kt:134-144 | ✓ 工具名原词 |
| 文件工具快照查看 | Read/Write/Edit/multiedit 拦截 onOpenFile→TOOL_SNAPSHOT / TOOL_SNAPSHOT_DIFF（ViewToolRequest）；LocalToolCardResolver 注册表；回退 ToolCallCard | PartContent.kt:240-271 | ⚠ 自定义查看链 |
| 后台 shell 卡 | Part.Shell→ShellCard（「后台 shell 命令卡片（V2）——2 行布局，与 TaskToolCard 对称」） | PartContent.kt:275-284 | ✓ V2 shell part |
| 隐藏 parts | StepStart/StepFinish（WebUI 不显示）、Snapshot/Subtask/Compaction/SessionTurn/Unknown skip；Part.Agent 显示 name/source | PartContent.kt:285-360 | ✓ part 类型原词 |
| 文本提问嗅探 | part.text 含 "questions:"+"User has answered"→CollapsibleQuestionPart（opencode 可能以文本 part 发送问题和答案而非结构化 Part.Question） | PartContent.kt:69-74 | ⚠ 协议兜底启发式 |
| 思考卡 | ReasoningBlock（思考中/已完成；脉冲动画仅思考中运行 #135 D2-L45；部分复制；高度上限 240dp）；「0.3s ticker」注释 vs delay(100) 代码 | ReasoningBlock.kt:65-76 | ⚠ ticker 数值失实第二例 |
| synthetic 注入机制 | 「opencode 服务器在后台 task/subagent 完成时向主会话注入 synthetic 消息（type="synthetic"+顶层 text；客户端实时经 session.input.promoted 接收，2026-08-12 与 TUI 机制对齐）」 | SyntheticNotificationCard.kt:68-75 | ✓ session.input.promoted 事件原词 |
| synthetic 结构化格式 | <task id state><summary><task_result|task_error>（新版）/<subagent description>正文</subagent>（旧服务器）/<shell>（同 subagent）；source=agent/shell；state 决定完成/失败 | SyntheticNotificationCard.kt:324-394 | ⚠ 服务器文本协议启发式 |
| 系统通知标签行 | 「顶部『系统通知』标签行」（绿✓/红✗/蓝ℹ）；label=chat_label_tasks（"Tasks"）；「后台通知=Notifications 图标」 | SyntheticNotificationCard.kt:80,125-126,163-164 | ⚠ synthetic=系统通知第 5 处叫法 |
| 定位发起卡片（按钮） | 「有子会话 id 即显示，点击后由 ChatMessageList 查找发起卡片（TaskToolCard 的 metadata.sessionId）并滚动+高亮」 | SyntheticNotificationCard.kt:120-122,257-269 | ⚠ |
| agent/shell 通知差异 | agent 展示总结（截断 2000）；shell 展示全部内容（不截断）；图标 AccountTree vs Terminal | SyntheticNotificationCard.kt:305-313,208-219 | ⚠ |
| 会话目录 | sessionDirectory=「本会话项目的目录——作为 x-opencode-directory 发送」；directoryParam 路由参数；fillDirectoryFromRetry 离线兜底 | SessionLifecycleDelegate.kt:60-73 | ✓ x-opencode-directory header 原词 |
| 延迟创建 | ensureSession（Mutex 防并发创建、双重检查、创建后启动 SSE 观察「否则消息保持不可见」）、sessionLoaded CompletableDeferred | SessionLifecycleDelegate.kt:75-154 | ⚠ 自定义懒创建 |
| 核心骨架 | 「delegate 层的核心骨架」——sessionIdFlow 是 6 个 combine/flatMapLatest 管道的数据源 | SessionLifecycleDelegate.kt:21-28 | ⚡ 架构角色词 |
| 草稿域 | 草稿文本/附件（content:// URI）/确认文件路径；restoredDraft（发送失败恢复）vs revertedDraft（revert 恢复）两通道；#54 直写 DataStore（防抖已删）+Mutex 保序；#113 clear 与 save 同通道 | DraftInputDelegate.kt:41-235 | ✓ draft |
| @ 提及 | @ 文件提及搜索（150ms debounce；空查询立即显示最近/热门文件 limit=15；dirs="true"） | DraftInputDelegate.kt:62-110 | ⚠ mention 自定义 |
| D 集群 | 「应用 D 集群字段（文本/附件/文件路径）…跨集群」 | DraftInputDelegate.kt:200-201 | ⚠ 字母代号又一例 |
| 上下文详情 | token 分布、缓存命中率（cacheHitRate=cacheRead/input）、ContextBreakdown 估算、SessionTimestamps | ContextDetailDelegate.kt:23-86 | ✓ token/context 语义 |
| 悲观模式收敛 | 「乐观消息体系（pending 消息/parts、服务器确认对账）已整体移除；_isSending 仅用于防快速双击（RS-007）」 | SendStateStore.kt:7-13 | ✓ 悲观/乐观消息术语（体系已删） |
| 答案快照 | QuestionAnswersSnapshot（answers 提交载荷 + parkedCustoms 保留未勾选自定义）；应用级单例（同 SessionScrollSignal 模式；VM 活不过 pop/recreate 终验 9f66bacd） | QuestionAnswerStore.kt:7-56 | ⚠ 自定义 |
| 工具快照缓存 | cacheToolPart（read 剥离 <path>/<content> 包装与内嵌行号「291: text」防双重行号；write=content；edit=after/newString；metadata.filediff before/after） | ToolCacheDelegate.kt:15-80 | ⚡ 输出格式启发式 |
| 模型解析链 | 显式选择 > 会话最后模型 > 本地默认（"pid|mid|variant"）> provider default；isModelExplicitlySelected 竞态防护（Main.immediate 同步触发 combine） | ModelConfigDelegate.kt:62-68,129-156,330-345 | ✓ provider/model/variant |
| primary agent 回填 | 只回填 primary agent（官方 TUI prompt/index.tsx:323-326 "not a subagent"）；mode!="subagent" 且 !hidden——原实现会取 subagent 上下文消息 | ModelConfigDelegate.kt:157-178,230 | ✓ agent mode 原词（subagent 过滤） |
| variant 档位 | #187 二级面板 variant pill 选择——「同步思考档位」（null=默认档） | ModelConfigDelegate.kt:341-343 | ⚠ 档位=variant 的口语译名 |
| contextWindow 置零策略 | 查不到时置 0——UI（ChatTopBar/ContextDetailDialog）对 <=0 隐藏指示器（2026-08-17 ACP 口径修正） | ModelConfigDelegate.kt:212-218 | ⚠ |
| 刷新节流 | refreshIfNeeded（ON_RESUME；REFRESH_COOLDOWN_MS=5s；不重启 sseJob 避免滚动位置重置和数据闪烁）、refreshAndSync | SessionActionsDelegate.kt:78-144,715 | ⚠ |
| 状态校正 | syncSessionStatus=「查询服务器实际会话状态，纠正丢失 SSE 事件导致的 UI 状态偏移」；L3 校验、ON_RESUME 无条件 cursor 增量补漏（SSE_PRIORITY）、V2 僵尸 drain | SessionActionsDelegate.kt:106-144 | ⚠ 校正/补漏词族 |
| 权限应答值 | reply ∈ {"once","always","reject"}；V2 reply 路由需权限所属会话（子会话权限必须传子会话 id，父会话 404） | SessionActionsDelegate.kt:149-168 | ✓ once/always/reject 原词 |
| 复核去留 | removePermissionIfGoneOnServer/removeQuestionIfGoneOnServer（reply 失败后复核服务器 pending；「宁多重弹，不静默丢授权」）；2026-08-17 权限卡/问题卡重弹根治 | SessionActionsDelegate.kt:170-320 | ⚠ |
| 压缩事件 V1/V2 | V1 compaction.started 三件套（幂等覆盖）；V2 只有单个 session.compacted——本地置态是进行中气泡唯一驱动；服务器跑 LLM 摘要可达数十秒 | SessionActionsDelegate.kt:69-74,356-386 | ✓ compaction.started/session.compacted |
| 导出 | 流式导出到文件 URI（消息可达 80+MB 防 OOM；下载进度通知；channel "opencode_export"） | SessionActionsDelegate.kt:389-458 | ⚠ |
| undo/redo 语义 | undoMessage=「撤销会话中最后一条用户消息」（日志却打 "Reverted session"）；redo 日志 "Unreverted"；官方语义 TUI index.tsx:621；staged revert 连续 undo | SessionActionsDelegate.kt:460-502 | ⚠ undo/revert/unrevert 三词一义（冲突 6 佐证） |
| 服务端命令 | executeCommand（/init、/review、MCP 命令；init 空参数特判）、runShellCommand | SessionActionsDelegate.kt:619-698 | ✓ command |
| 前台/后台任务 | isForeground=「子代理运行中+主会话 busy（阻塞中）」；转后台后主会话 idle；TUI foregroundTasks/session.family 语义对齐；foregroundCount 放宽（V2 派发基本都带 background=true） | TaskDelegate.kt:32-34,180-228 | ✓ TUI 语义注释（foreground/background） |
| active 轮询 | V2 /api/session/active（「V2 不广播 session.status SSE（V1 才有）」）；指数退避 5s→10s→30s（#99）；「兜底观测，非实时依赖」 | TaskDelegate.kt:82-93,136-142 | ✓ active 端点 |
| 双向对账 | reconcileWithActiveSessions：正向（active 有 FSM 非 Busy，连续 2 轮→L3 恢复）/反向（FSM Busy 但 active 缺且事件陈旧 ≥15s→L3 僵尸自愈）/空集返回（V1 active 恒空，防 L3 风暴） | TaskDelegate.kt:122-133 | ⚡ 对账/reconcile 词 |
| V2 子代理派发 | 「V2 派发子代理走 session.create，无工具调用记录」（实测翻 1000 条 0 个 task/subagent part）；子会话 parentId 本身就是后台任务 | TaskDelegate.kt:174-179 | ✓ V1/V2 行为差异 |
| 执行时长数据源缺口 | V2 session.time.updated 不随活动更新（diff 6-13ms 恒定）；完成态时长仅 updated-created>5s 才显示；运行中 UI 走时 | TaskDelegate.kt:194-200 | ⚡ 服务器契约限制注记 |
| 终端域 | ServerTerminalWorkspace（服务器范围 Singleton）、TerminalTabUi、Termux 桥（#189）、PTY、光标键应用模式、字号同步 | TerminalDelegate.kt:22-98 | ✓ terminal 词族（本地概念） |
| 斜杠命令解析 | WHITESPACE_SPLIT_REGEX（/cmd args）；"!" 前缀自动进 shell 模式（shouldAutoShell）；命令菜单 new/compact/fork/share/unshare/undo/redo/rename/shell/review | ChatScreenBottomBar.kt:37-41,126-131,210-224,323-398 | ✓ command |
| @ 提及替换 | AT_MENTION_REGEX（光标前最后一个 @query）；@path 替换 @query | ChatScreenBottomBar.kt:37,151-156,303-312 | ⚠ mention |
| stableBusy 消抖 | 显示侧下降沿消抖（true 立即传导，false 需稳定 2.5s；覆盖 V2 drain 窗口 FSM Busy↔Idle 抖动 + isSending→isBusy 接管缝隙）；「FSM 语义与单一真相源不变」 | ChatScreenBottomBar.kt:267-276,435-445 | ⚡ 展示层消抖概念 |
| 输入禁用 | 「等待提问/权限响应时禁用输入框（用户要求 2026-08-14）」 | ChatScreenBottomBar.kt:277 | ✓ pending 语义 |
| 堆积入口 | busy 气泡「堆积」——入队并清空输入框 | ChatScreenBottomBar.kt:415-421 | ⚠ 堆积第三处 UI 入口 |
| FAB 菜单迭代轮次 | 第十八轮（M3 原版样式）/第十九轮（Secondary 变体）/第二十轮（描边+尺寸）/第二十一轮（tint 统一、双 FAB 同径） | ChatFabMenu.kt:50-113,190-199 | ⚡ 轮次编号注释体系 |
| 堆积列表 | StackedList（长按拖拽排序 + 每行[编辑·删除·发送]；推送中锁定——isHeadSending=isDraining&&队首） | PendingSheets.kt:440-453 | ⚠ 堆积/推送中词 |
| TODO 三态 | ✓（completed）/•（in_progress）/○（pending）；cancelled 删除线；key=content+"#"+status | PendingSheets.kt:554-583 | ✓ todo.status 原词 |
| shell 详情 | exit（shell_status_exit）/cwd/运行状态；formatTaskDuration（<60s 秒/<1h m:ss/≥1h h:mm:ss） | PendingSheets.kt:606-674 | ✓ shell 字段 |
| sheet 迁移史 | 「迁自 PendingTodoDrawer/TaskSheet」（第十轮五入口拆解用户定案；无 tab 隔离；无数据可打开看历史） | PendingSheets.kt:77-81,127,222,239,340 | ⚡ 迁移注释 |
| 批量转后台 | 顶部菜单入口（TUI ctrl+b 对应；工具栏显示条件苛刻；服务器无前台任务时 no-op） | ChatTopBar.kt:60-66,180-184 | ✓ background 语义 |
| V1/V2 门控注释 | V1 无正式后台系统（实验性 /experimental/session/{id}/background 需 flag，#85）；V2 当前无 share 端点（#78） | ChatTopBar.kt:64-67,184,218 | ✓ 端点级差异注释 |
| 上下文进度指示器 | percentage=lastContextTokens/contextWindow*100；contextWindow<=0 隐藏；父/子会话都显示 | ChatTopBar.kt:104-135 | ✓ |
| 同名文件 | ChatScreenDialogs.kt 同时存在于 chat/ 与 chat/dialog/（前者容器、后者具体对话框实现） | ChatScreenDialogs.kt ×2 | ⚡ 命名撞车（coverage 台账注意） |
| 问题卡三态模型 | ①已勾选 CustomAnswerRow（行点击=取消勾选入 parked）②parked 保留 ParkedCustomRow（行点击=重新勾选；✕=彻底删除）③空输入框；「保留内容，但取消勾选」（2026-08-18 用户反馈） | QuestionPartContent.kt:466-525,802-811 | ⚠ parked 自定义状态词 |
| 交互/只读共用 | QuestionPagerView（QuestionCard 交互式 + 问题历史只读共用）；QuestionExpandedOptions（历史）；customDraft 按 pageIndex 提升（#126 beyondViewportPageCount 销毁远页丢草稿） | QuestionPartContent.kt:186-397 | ⚠ |
| 页高插值/限高 | lerpCappedPageHeight（两页高度先截断再按 progress 线性插值）；QUESTION_PAGE_MAX_HEIGHT_FRACTION=0.4（E2E-E：6+选项卡片高于视口下部不可达→页内滚动补足） | QuestionPartContent.kt:236-255,282-287 | ⚡ |
| Q chips/类型标签 | QuestionCompactTabs（FilterChip 替代 SegmentedButton——「响度倒置」ΔL 0.63）；Q1/Q2 chips；类型标签（单选/多选）2026-08-17 元信息不进问题域 | QuestionPartContent.kt:205-233,400-415 | ⚡ 审计词（响度） |
| Bug #125 | 输入文本已是选项标签则不 toggle（避免已选中选项被意外取消） | QuestionPartContent.kt:512-517,787-788 | ⚡ |
| 自定义输入语义 | 「自定义输入=提交自己的回答，至多一个」；CustomAnswerInput 自绘（M3 TextField 定高裁切 font_scale=1.3 E2E 复现） | QuestionPartContent.kt:466-468,594-634 | ⚡ |
| 提问卡答案恢复优先级 | 应用级 store（跨导航/recreate）> saveable JSON > initialAnswers（历史）> 空；E2E-C：List 非 Bundle 合法类型 autoSaver 静默不保存 → JSON 序列化 | QuestionCard.kt:99-137 | ⚡ |
| toggle 纯函数 | toggleQuestionAnswer/QuestionToggleResult（selected 提交载荷 + parkedCustom 保留未勾选）；「存在即勾选」无法表达 parked | QuestionCard.kt:346-400 | ⚠ parked |
| 未回答确认 | unansweredQuestionIndexes（1-based；answers 长度可能小于题目数——Pager 懒加载未访问页） | QuestionCard.kt:333-341 | ⚡ |
| 权限卡 | 「权限请求」标题；子 agent 来源标签（权限请求来自子会话时显示）；patterns 文件模式；once/always/reject；always 打开 AlwaysConfirmDialog 后才置 submitted | PermissionRequestCard.kt:59-158 | ✓ permission 语义 |
| 模型抽屉 | 行内 accordion（variant pills 含「默认」档）；星标=设置/取消默认模型 toggle（"pid|mid"）；Free 标签（仅 providerId=="opencode"）；"opencode" 提供商前置排序；75% 屏高抽屉 | ModelPickerDialog.kt:62-110,218-250 | ✓ provider/model/variant；Free=自定义标签 |
| busy 根因链 | V2 drain 窗口：execution.succeeded→Idle 但 /active 仍 running→正向对账 streak=2（≈10s）复活 Busy→RestValidation 不刷新 lastEventAt→15s L2 stale→循环直至 zombie 判定（3min） | BusyIndicatorSmoother.kt:3-24 | ✓ execution.succeeded/active 端点 |
| 显示侧下降沿延迟 | BusyIndicatorSmoother（DEFAULT_RELEASE_DELAY_MS=2500；true 立即传导/false 稳定 2.5s；吸收 isSending→isBusy 1-2 帧缝隙；「不动 FSM 单一真相源铁律」） | BusyIndicatorSmoother.kt:16-71 | ⚡ |
| busy 气泡菜单 | 两项：立即发送=「服务端排队不中断」（mode 2 恢复）/堆积消息=「本地暂存 turn 结束后自动发」（mode 3）；#178 focusable=false 保 IME | SendStopButton.kt:48-63,133-242 | ⚠ mode 2/3 编号暗语 |
| 命令三源合并 | 客户端命令（SlashCommandRegistry.clientCommands）+服务器命令+技能（skill，type=="skill" tertiary 色）；source 默认 "server" | ChatInputBar.kt:128-141; SlashCommandSuggestions.kt:60-78 | ✓ command/skill |
| 任务工具栏文案 | 「转为后台」SuggestionChip；TUI ctrl+b 原文 "Background blocking session tools" | TaskToolbar.kt:27-35 | ✓ |
| 占位符轮换 | placeholderHintResIds 每 4s 轮换（L-8：仅聚焦且空文本时轮换）；「类似 WebUI 的提示输入」 | ChatInputBar.kt:49-113 | ⚡ |
| 附件令牌估算 | AttachmentComparison（originalBytes/optimizedBytes/originalEstimatedTokens/optimizedEstimatedTokens；chat_images_optimized_summary 模板） | ChatAttachmentsHandler.kt:136-178 | ⚡ |
| 快速导航抽屉 | QuickNavigateSheet（JumpTargetRow：Q 标签+时间戳+预览水平滚动）；打开时定位当前高亮（时间锚点降级）；「用户点击 Q 时条目正在移动→点击落空」48ms 防抖 | QuickNavigateSheet.kt:61-135,283-302 | ⚠ 快速导航=jump |
| 错误载荷双模式 | ErrorPayloadContent（HtmlErrorViewMode.Code/WebView；normalizeHtmlForEmbeddedPreview 注入 CSS；loadUrl("https://localhost/")） | ErrorPayloadContent.kt:37-117 | ⚡ |
| 死代码删除注记 | 「删除 calculateContextUsage(parts, contextLimit)——无生产调用点…系早期实现遗留」 | ContextUsageBar.kt:18-21 | ⚡ |
| 死组件三例 | TokenRatioRing（08-14 恢复→08-15 移除）、MessageMetaInfo（零调用）、smoothScrollToBottom（零调用） | TokenRatioRing.kt:16; MessageMetaInfo.kt:16; ChatScrollUtils.kt:6 | ⚠ 注释仍以现役口吻描述 |
| Retry 语义 | SessionStatus.Retry（#120 D2-09 单占位符修复——原双占位符恒 N/N） | RetryBanner.kt:49 | ✓ retry |
| 表格测量缓存 | MeasureCache（探针列宽/行高按「内容、约束、列宽」签名复用；final Placeable 不可跨 measure）；三遍 subcompose（probe/final-pass1/final） | MarkdownTable.kt:53-57,211-217 | ⚡ 自绘表格内部词 |
| 终端输入域 | 音量键虚拟 Ctrl/Fn、键盘条锁存（粘滞至下一字符，Termux ExtraKeys sticky 语义）、Ctrl+Alt+V 粘贴、DECCKM 光标键应用模式、sendTerminalChunk 十六进制取证日志 | ChatTerminalView.kt:100-312; TerminalKeyboardOverlay.kt:37-43 | ⚡ 本地终端词族 |
| Termux 换件 | #189：termlib Terminal composable → Termux TerminalView（成熟组件）；PFLAG_SKIP_DRAW 坑；OSC 11 背景色同步；字号阶梯缩放钳位 [6,20]sp | TermuxTerminalHost.kt:26-120,150-236 | ⚡ |
| 消息查看双视图 | MarkdownPreviewDialog（SOURCE 等宽/RENDERED Markdown；独立屏幕不嵌 LazyColumn 防嵌套滚动冲突） | MarkdownPreviewDialog.kt:30-51 | ⚡ |
| V1/V2 工具名映射 | V2 shell（V1 bash）→BashToolCard；V2 subagent（V1 task）→TaskToolCard；webfetch/web_fetch、websearch/web_search 双写；大小写不敏感 | DefaultToolCardResolver.kt:33-83 | ✓ 工具名原词（V1/V2 双名收口表） |
| turn 元数据取首条 | agent/model 取 turn 内首条 assistant（官方 TUI data.tsx:208-222 每条写一次永不改写；synthetic 注入 agent=deep-explore 覆盖徽标=「agent 跳变」根因）；(created,id) 双键稳定取最早 | RenderableTurn.kt:83-98 | ✓ agent/model 语义 |
| turn 时长口径 | turnStartMs=首条 created；durationMs=首 created→末 completed，仅全部 completed 才给值（流式 ticker 接管） | RenderableTurn.kt:100-110 | ✓ completed |
| 全量输出双路 | part 优先（父会话 REST 按 part id）→子会话 transcript 回退（[role] 前缀拼接）；取长者；DB 500 字符预览（#79） | TaskOutputFetch.kt:7-57 | ⚡ |
| 全局状态图标 | TaskStatus RUNNING/SUCCESS/ERROR/UNKNOWN（「禁止在别处另起状态图标」） | TaskStatusIcon.kt:17-29 | ⚡ |
| 上下文工具组 | CONTEXT_TOOLS=read/glob/grep（PartGrouper 聚合为 Context 组卡；ContextSummary read/search 计数） | PartGrouper.kt:5-41 | ✓ 工具名 |
| 工具显示解析 | resolveToolDisplay（服务器 title 优先；bash 命令截断 60 字符；list/listDirectory；MCP/未知工具 snake_case→标题；「与 WebUI 工具注册表行为一致」） | ToolCardRegistry.kt:56-164 | ✓ 工具名原词 |
| 进度输出注入 | tool.progress 累积 output 注入 Running 态 Part.Tool（callId 匹配；无匹配返回原引用保引用稳定）；#180 sessionID/sessionId 双键注入 metadata | ToolProgressOutputInjector.kt:6-44 | ✓ tool.progress/callId |
| 快照分组 | (messageId, 规范化 filePath) 分组；不要求物理相邻；cumulativeBefore=首个 before/cumulativeAfter=末个 after；filediff metadata 优先 | ToolSnapshotGrouper.kt:10-103 | ⚡ B-tier 规范引用 |
| 规范章节引用 | 注释引用「规范 §5.5 B-tier」「§5.1-5.4」「docs/archive/specs/2026-07-02-shell-streaming…design.md §2.5」 | ToolSnapshotGrouper.kt:11; ViewToolRequest.kt:6; ToolProgressOutputInjector.kt:12 | ⚡ 外部规范锚点（archive 路径） |
| bash/shell 卡 | 2 行布局（$ 命令 + 状态·摘要；Running/Exit N/Done 对齐 ShellCard）；ANSI 转义剥离；「output truncated…Full output saved to: <path>」提示条（服务器保尾截头：progress 超 30K slice(-30000)、终态超 2000 行/50KB，官方 shell.ts） | BashToolCard.kt:44-182 | ✓ 服务器截断语义注释 |
| path/filePath 不符 | 「服务器实际发送 path 字段（schema 声明 filePath，实测 input 为 {path:…}）——兼容两者」 | EditToolCard.kt:64-67 | ⚡ schema 与实测偏差记录 |
| turn 修改摘要 | PatchCard（turn 结束时已修改文件摘要；+N/-N 来自 LocalSessionDiffs；plurals chat_files_changed_plural） | PatchCard.kt:19-50 | ✓ session diff |
| 子会话 id 四键 | childSessionIdOf：sessionId/sessionID（V2Mappers 双写归一）/jobId（V2 早期实测）/childID（synthetic 同源命名） | TaskToolCard.kt:231-238 | ⚡ 历史键名并读（V2 演进化石） |
| 任务卡状态底色 | 发起=蓝/完成=绿/失败=红（2026-08-11 用户要求）；#181 chevron 与导航并存；#182 展开实时拉取全量输出 | TaskToolCard.kt:98-123,170-207; ToolCardScaffold.kt:80-83 | ⚡ |
| todo 卡 | todowrite（metadata.todos 优先，input.todos 回退；completed/in_progress/pending + priority） | TodoListCard.kt:57-90 | ✓ todo 字段 |
| 步骤状态标签 | Making edits / Running commands / Searching codebase / Thinking / Running subagent / Updating tasks / Fetching URL（chat_status_*） | ChatFormatters.kt:54-87 | ⚡ UI 状态文案族 |
| 上下文估算 | estimateContextBreakdown（对应 opencode estimateSessionContextBreakdown；无 system prompt 差额归 OTHER；estimateTokens=chars/4）；缓存命中率=cacheRead/(input+cacheRead) | ContextStats.kt:59-120 | ✓ opencode 函数名对齐 |
| 上游引用 | 「启发自上游 oc-remote v1.6.9 commit a906a72b」（PatchVisibilityResolver） | PatchVisibilityResolver.kt:19 | ⚡ upstream 溯源注释 |
| mention 构造 | @提及→type=file part（目录 mime=application/x-directory；url=file:///绝对路径）；文本段 type=text | PromptBuilder.kt:6-90 | ✓ prompt part 结构 |
| 发送后列表滚顶信号 | SessionScrollSignal（「用户发送消息时 ChatViewModel 设置；返回时 SessionListViewModel 消费以将列表滚动回顶部」；Hilt singleton 不持久化——「发送→返回典型流程从不会杀死进程」；SavedStateHandle 跨组件不同实例失效注记） | SessionScrollSignal.kt:6-28 | ⚠ 自定义跨屏信号 |
| 分享目标选择 | ShareTargetPickerDialog（ACTION_SEND 图片→选会话打开；「列出已加载 SSE 数据的服务器上的最近会话」按服务器分组） | ShareTargetPickerDialog.kt:29-64 | ⚡ |
| 服务器 URL 规范 | validateAndNormalizeUrl（缺 scheme 默认补 http://；host 必需；端口合法） | ServerDialog.kt:27-65 | ⚡ |

| 设置项↔代码键反转 | UI「自动展开工具结果」(settings_auto_expand_tools) ↔ collapseTools/setCollapseTools；LocalCollapseTools KDoc「工具卡片是否默认折叠」 | ChatDisplaySection.kt:56-70; ChatCompositionLocals.kt:11-12 | ⚠ 折叠/展开反义同键（详见失实注释与冲突 27） |
| 设置项中文文案族 | 初始消息数（initialMessageCount→currentMessageLimit 起点）/最近目录数量（快速新建会话对话框 limit）/发送前确认/触感反馈/屏幕常亮/优化图片附件/聊天字体（密度：标准·紧凑）/默认展开推理过程/显示轮次分隔线 | ChatBehaviorSection.kt:54-173; ChatDisplaySection.kt:41-104 | ⚡ 设置键中英双层命名 |
| 快速新建会话目录 | recentSessionDirectories（按目录分组、lastUsed 倒序、limit=recentDirectoryCount）；V2 根目录防御：「V2 服务器存在 location.directory 为 / 的会话（实测 ses_005890631ffe…）——根目录无法作为新建会话目标，过滤」；移植自上游 oc-remote v1.7.0 | NewSessionQuickDialog.kt:46-75 | ⚡ V1/V2 数据边界+上游引用 |
| 目录浏览器 | OpenProjectDialog：DirectoryPath 类型化路径（「不做原始字符串切片」）；初始路径优先级 initialDirectory→服务器 home→平台根（「V2 /api/fs/list 对 path=/ 返回 500，home 更友好」）；盘符边探测；#137 D2-34 创建中禁止重复触发（「按钮未随 isCreatingFolder 禁用——快速双击会双发」） | OpenProjectDialog.kt:58-129,235-236,328-331 | ⚡ V2 端点行为注记 |
| Tag/标签/分类三词 | 「多选标签分配对话框」；UI 文案混用：标题 R.string.category（分类）、新增 Tag、新建标签表单、标签列表；「视觉上与历史分类选择器保持一致」（SessionCategoryStyle） | TagPickerDialog.kt:56-67,102,153-157 | ⚡ Tag=标签=分类并存（历史迁移痕迹） |
| 建目录死代码注记 | 「原 fallback 链（currentPath.child / forPath(node.name)）为死代码」——FileNode.absolute 直接使用 | OpenProjectDialog.kt:235-236 | ⚡ 注释自述死代码（记录性，非误导） |
| 设置对话框参数族 | 初始消息数 20/50/100/200；最近目录数 5-50 滑杆；图片最长边 0/720-2560；质量 40-80；终端字号 6-20sp（与 TermuxTerminalHost 钳位一致）；主题/语言/重连模式枚举 | MessageCountPickerDialog 等 7 个设置对话框 | ⚡ 参数清单（无注释文件群） |

## 失实注释

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| ChatScreen.kt:853-855 | 「堆积/TODO 常驻抽屉（2026-08-22）：主对话流模块内覆盖式……模态 PendingTodoSheet 已退役（入口=常驻标题栏本身）」 | 实际（954-984）为 toolbarSheet 按需 ModalBottomSheet（Stacked/Todo/Agent/Shell），入口是右下 FAB 菜单（858-876）；592 行明言「第九轮常驻抽屉已于第十轮退役——改为贴底工具栏 + 四个独立 ModalBottomSheet」 | 改写为 FAB+sheet 现状；多层演进注释收敛为一条 |
| ChatScreen.kt:815-816 | 「#137（D2-L65）：此处原重复定义 onViewToolLambda（死代码——LocalOnViewTool 由外层 516 行的定义提供…）」 | 外层 onViewToolLambda 实际定义在 545-557 行（非 516）；行号引用已漂移 | 行号引用改为符号锚点 |
| ChatScreen.kt:565-567 | 「ChatScreen 本身不读取这些设置，因此设置变化不会触发 ChatScreen 重组」 | 仅对 chatDensity/collapseTools/expandReasoning/showTurnDividers 四项成立（1009-1027 下沉）；ChatScreen 顶层仍直读 6 项设置流（411-416） | 限定「这些设置」范围表述 |
| ChatScreen.kt:781-783 | 「synthetic 系统通知（2026-08-11）：紧邻 assistant 时并入 turn 气泡内渲染（isAdjacentToAssistant），不独立成行」 | 同文件 797-804 已被 2026-08-12 决策推翻（独立气泡）；代码无 isAdjacentToAssistant 判断 | 删除 08-11 过期描述 |
| ChatViewModel.kt:523-527 | 「滚动状态：使用 cache window 策略（窗口式预组合）替代默认的单 item 异步预取…」 | 528-541 行：cacheWindow 已被 JumpPrefetchStrategy 替代（534-536 明言）；且 527 行注释残缺（「+ fling」戛然而止） | 段首改为现状（prefetchStrategy=jumpPrefetch）；补全残句 |
| ChatViewModel.kt:528-530 | 「跳转预组合策略——视口外预组合跳转目标…同时承担滚动方向预测预组合」 | JumpPrefetchStrategy.kt:18-21：2026-08-21 已移除「跳转目标预组合」职责，「本策略仅保留滚动方向预测预组合」 | 删去跳转目标预组合半句 |
| ChatViewModel.kt:501-508 | 「2026-08-22：堆积/TODO 常驻抽屉显隐（顶栏菜单 toggle）」togglePendingTodoDrawer/showPendingTodoDrawer | ChatScreen.kt:585-592 注释明言常驻抽屉已于第十轮退役；本 toggle 与设置项疑似退役功能残留（VM 仍暴露） | 核实消费方后删除或改为「已退役」注记 |
| ChatUiState.kt:125 | 「synthetic 系统通知（后台任务/subagent 完成注入）。嵌入 assistant turn 气泡内渲染。」 | ChatScreen.kt:797-804 + TurnGroupCalculator.kt:11-29：2026-08-12 用户决策独立气泡，「不再邻接判断/嵌入」；displayItems 将 synthetic 独立成项 | KDoc 改「独立气泡渲染（2026-08-12 起）」 |
| ChatStateAggregator.kt:21-27 | 「管理…4 个聚合状态管道…uiState：Legacy 全量状态…（向后兼容测试）」 | 147-148 行注记：「#173 段 3：Legacy uiState 已退役——生产零消费…六源组装管道删除」；类中实际只剩 3 个管道 | 头部 KDoc 同步退役事实 |
| ChatScrollController.kt:43-44 | 「调用方（ChatScreen 819/847）原先在组合作用域读 Boolean getter」 | ChatScreen 现行 isAtBottomState 消费点在 828/862 行；行号引用漂移 | 行号改符号锚点 |
| MessageDataDelegate.kt:50-52 | 「乐观消息职责委托给 [optimisticStore]」 | 类中无 optimisticStore 成员（仅 113 paginationDelegate、126 sendStateStore）；245-246 明言「悲观消息模式：…无乐观合并逻辑」 | 删除 optimisticStore 引用 |
| MessageDataDelegate.kt:196-199 | 「消息 ID 是 ULID（单调递增），因此 id <= revertId 正确地包含 revert 点及之前的所有消息」 | 代码为 sessionMessages.filter { it.id < revertState.messageId }（严格小于，不含 revert 点本身） | 注释与代码比较符不一致；按 OpenCode 语义裁决后统一 |
| MessageDataDelegate.kt:514 | 「通过 [SessionStateService.onRestValidation] 路由」 | 注入类型为 SessionStateRepository（75 行）；SessionStateService 是文档层名称 | 统一 Service/Repository 命名（见冲突 7） |
| ChatMessageList.kt:1154-1155 | 「synthetic 通知嵌入 turn 内（2026-08-11），不阻挡统计栏」 | 同文件 1296-1297：synthetic 用独立样式 MessageCardRole.SYNTHETIC（#67 独立卡片）；行为本身正确（不阻挡统计栏），但「嵌入 turn 内」表述过期 | 改为「synthetic 不算 turn 边界」 |
| RenderableTurn.kt:38,59-60 | 「synthetic 系统通知卡片（后台任务完成，2026-08-11 嵌入气泡内渲染）」「以卡片渲染项嵌入气泡内（不独立成行截断气泡）」 | TurnGroupCalculator.kt:11-29 与 ChatScreen.kt:797 均载明 2026-08-12 决策独立气泡、不再嵌入 | KDoc 改「独立气泡（2026-08-12 起）」；synthetic 第 6 处过期「嵌入」表述 |
| JumpNavigationController.kt:26 | 「Settling(收敛修正) → Displayed(显示+稳定窗口 1.5s) / Failed(超时)」（头部 KDoc） | 412-418 行：C-R2/D-4 修正「总时长缩至 900ms」+ 代码 while(elapsedRealtime()-settleStart < 900)；416 行注释明言旧版 1.5s 已改 | 头部 KDoc 同步 900ms（注明用户触摸即退出） |
| RenderReadiness.kt:39-46 | 「Ready(finalHeight)…终止态（awaitReady 等待此状态）」 | 28-30 行注记：渲染层上报链（update/Ready）与 awaitReady 均无消费者已删除（D-11-4）——Ready 状态与 isDone 的 awaitReady 引用均为死代码 | 删除 Ready 态与 awaitReady 注释（或标注已退役） |
| RenderReadiness.kt:49-50,91 | 「渲染就绪注册表——消息级就绪信号的唯一真相源」「fun preParse(msgId: String…)」 | RenderSupplyCoordinator.kt:122-127 实际以 part.id 为键调用（「key=part.id…不能混入 msgId」）；「消息级」表述与现用粒度不符 | KDoc/形参名改 partId（或注明键=part.id） |
| MessageCardAssistant.kt:360-363 | 「内部 ticker（2026-08-15 用户要求：1s → 300ms，秒级小数进度感）」 | 代码 368-373：while(true){…delay(100)}——实际 tick 100ms | 注释数值与代码不符；裁决后统一 |
| ReasoningBlock.kt:74-75 | 「2026-08-15 用户要求：0.3s ticker（秒级小数进度感…）」 | 代码 76 行 delay(100L)——tick 实为 100ms | 与上例同款数值失实 |
| MarkdownChunking.kt:67 | 「用户长消息纯文本分片计划（段序列，行边界优先 + 超长单行硬切）」 | splitUserTextChunks 实现（76-99）：无换行的超长单行切不出多段→返回 null 保持原渲染（「保守：不插入原文没有的换行」）；不存在硬切路径 | KDoc 删「超长单行硬切」，改为「超长单行不分片」 |
| DraftInputDelegate.kt:239 | 「草稿防抖保存间隔：停止输入 500ms 后持久化。」（DRAFT_DEBOUNCE_MS 注释） | 140-143 明言 #54 已移除 500ms 防抖→直写 DataStore；updateDraftText 每次按键直接 saveDraft()；常量本体已删仅剩孤立注释 | 删除孤儿注释（或标注已废弃） |
| TokenRatioRing.kt:16-17 | 「Token 占比圆环——统计栏内展示 input/output token 比例（2026-08-14 恢复）」 | 全 ui 树 grep 仅剩定义处零调用点；MessageCardAssistant.kt:212-213「2026-08-15 用户要求：移除 Token 占比圆环」——恢复后又移除，组件成死代码而 KDoc 读起来像现役 | 标注已退役或删除组件 |
| ChatScrollUtils.kt:6-11 | 「动画自动滚动到底部。用于发送后的跟随。」（smoothScrollToBottom） | 全 ui 树 grep 零调用点（仅 snapToBottom 被使用）——所述场景已不存在 | 删除或标注未使用 |

| ChatCompositionLocals.kt:11-12 | 「LocalCollapseTools：工具卡片是否默认折叠。」（true=折叠语义） | PartContent.kt:159,165 等消费点：val autoExpand = LocalCollapseTools.current; isExpanded = toolExpandedStates[part.id] ?: autoExpand——值直接作默认展开值（true=展开）；设置页「自动展开工具结果」(ChatDisplaySection.kt:56-70) checked=collapseTools 同为此义 | KDoc「是否默认折叠」与消费语义相反；改为「是否默认展开」并注明与 collapseTools 键名的历史反转 |
## 待裁决冲突

1. 堆积队列 / 堆积 vs pendingQueue vs stacked（STACKED/StackedSheet）/ ChatScreen.kt:586,870,956; ChatViewModel.kt:88-197 / 同一队列三叫法；pending 词根另被 pendingQuestions/pendingPermissions 占用
2. 子会话 / child session vs sub-session（subSessionId/onOpenSubSession）/ ChatScreen.kt:270,576,838,976 / 均为 sessionId+parentId 派生
3. shell 作业 / shell vs bash / ChatScreen.kt:935; DefaultToolCardResolver.kt:36-70 / V1 bash 与 V2 shell 双名并存（卡渲染已收口）
4. 智能体 / 智能体 vs agent vs subagent / ChatScreen.kt:844-876; ModelConfigDelegate.kt:157-178 / 文案中文、代码 agent、子代理 subagent 三层
5. 滚动触底 / snapToBottom(吸附) vs forceScrollToBottom vs forceScrollTick / ChatScreen.kt:640,709,840,863-866 / 三路径语义不同，注释靠口语区分
6. undo/revert / undoMessage vs revertMessage vs revertSession（undoRedoUseCase）vs unrevert / ChatViewModel.kt:891-937; SessionActionsDelegate.kt:460-502 / undo 与 revert 混用；API 侧 revert 为原词；日志三词一义
7. FSM 服务名 / sessionStateService（变量名）vs SessionStateRepository（类型名）/ ChatViewModel.kt:81; MessageDataDelegate.kt:75,514 / 同一依赖双名；CONTEXT.md 承重规则用 SessionStateService
8. 堆积 drain / 推送中 vs 发送中 vs draining / ChatViewModel.kt:156-158 / 同一状态两种中文说法
9. 状态分组成员词 / 簇（状态簇，CONTEXT.md）vs 集群（滚动状态集群）/ ChatScrollController.kt:27; ChatStateAggregator / 同一「按归属分组」概念两种叫法
10. autoScroll / 自动滚动 vs 自动跟随 / ChatScrollController.kt:53,60-63 / 同一开关两叫法；SSE 铁律文本用 autoScroll
11. 悲观发送 / 悲观消息模式 vs 悲观发送 / ChatSendDelegate.kt:24 vs ChatViewModel.kt:119 / 同一模式两种缩略
12. top/bottom 语义反转 / requestScrollToTop（发送后会话列表滚顶）vs snapToBottom/forceScrollToBottom（聊天列表触底）/ ChatSendDelegate.kt:116; SessionScrollSignal.kt / 同名 top 动作作用两屏；聊天侧 reverseLayout 下 top=视觉底部
13. v1 歧义 / 「这在 v1 中可接受」vs 服务器 API V1/V2 vs「v1 迭代」「v1_regression_e2e」/ LinkUriHandler.kt:132; SafeFlingBehavior.kt:26; PartContent.kt:78 / 产品迭代、协议版本、测试代际三种 v1
14. parts 中译 / parts（原词）vs 零件 / MessageDataDelegate.kt:52 / 孤立中文译名
15. revert 过滤比较符 / 注释 id<=revertId vs 代码 id<revertId / MessageDataDelegate.kt:196-199 / 是否包含 revert 点消息本身
16. Q 口语 / Q=用户消息 vs question=问题卡片 / MessageDataDelegate.kt:370,487; JumpTargetExtractor.kt:13 / 注释口语缩写与 pendingQuestions 撞义；Q1/Q2 也是 UI 实显文案
17. 防风暴 / 防风暴 vs 退避 backoff vs 自动续载暂停 / MessagePaginationDelegate.kt:28,80 / 同一机制三叫法
18. 集群字母代号 / B/C/D/G 字母代号 vs 状态簇命名（sessionContext/conversation/composer）/ ChatViewModel.kt:844,896; MessagePaginationDelegate.kt:120-123; DraftInputDelegate.kt:200 / 注释用字母、代码用簇名，映射关系无处记载
19. JumpPrefetchStrategy 名实 / 类名「跳转预组合」vs 现职责仅滚动方向预测 / JumpPrefetchStrategy.kt:9-21 / 2026-08-21 移除跳转职责后未更名
20. 跳转词族 / 跳转 vs 快速导航 vs 定位加载（loadAround）vs 定位发起卡片（onLocateTask）vs jumpToMessage / ChatMessageList.kt:264-640 / 同一导航概念四种中文叫法，代码统一 jump 前缀
21. 渲染供给子机制名 / 滚动预解析驱动（preparse driver）vs RenderSupplyCoordinator vs 分片发射表 vs 块级分片 / ChatMessageList.kt:379-405,531-536 / 与 CONTEXT.md 渲染供给词条 Avoid 注记直接对应——代码注释仍在用机制名
22. V1/V2 子会话元数据键 / V1 task 工具 metadata.sessionId vs V2 subagent 工具 metadata.jobId（另有 sessionID/childID）/ ChatMessageList.kt:1422-1440; TaskToolCard.kt:231-238 / 工具名与键名双重差异；四键并读
23. 稳定窗口双值 / 跳转后稳定窗口 900ms（现值）vs 分片提交稳定窗口 2s vs KDoc 残留 1.5s / JumpNavigationController.kt:26,418; RenderSupplyCoordinator.kt:215 / 两个「稳定窗口」是不同概念且一个 KDoc 过期；CONTEXT.md 只收录 2s 那个
24. 注册表键粒度 / msgId（形参/KDoc「消息级」）vs part.id（实际键）/ RenderReadiness.kt:49,91 vs RenderSupplyCoordinator.kt:122-127 / 分片引入后键粒度升级，接口签名未跟
25. synthetic 中文叫法 / 系统通知 vs 合成通知 vs 后台消息 vs 后台通知 vs 转后台提示 / ChatUiState.kt:125; MessageBubble.kt:39; ChatMessageList.kt:1255; SyntheticNotificationCard.kt:80 / synthetic 是 API role 原词；中文侧 4+ 种叫法且「后台消息」易与 background session 混淆
26. 注释语言不一致（input/ 目录） / 中文为主 vs 整文件英文 KDoc / input/FileMentionSuggestions、input/ImageAttachmentRow、input/ShellModeHintBanner / 同目录中英混杂——注释统一中文的清扫点
27. collapseTools 命名反转 / 代码键 collapseTools（折叠）vs LocalCollapseTools KDoc「是否默认折叠」vs 消费语义 autoExpand（默认展开）vs UI 文案「自动展开工具结果」 / ChatDisplaySection.kt:56-70; ChatCompositionLocals.kt:11; PartContent.kt:159-165 / 四层命名三层语义（代码名与其余三层相反）；不改标识符裁决下注释与 KDoc 需统一方向
