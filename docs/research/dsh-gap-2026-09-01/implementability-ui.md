# DSH 差距项 · UI 层可实现性审计

> 日期：2026-09-02 · 只读审计（未改任何代码）
> 方法：对 `2026-09-01-dsh-web-vs-android-gap.md` §1-§7 与 `fe-inventory.md` 的 14 个差距项，逐项在 `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/` 找挂点（file:line）→ 盘点可复用组件 → 评级工程量（S ≤半天 / M 1-3 天 / L >3 天，含 Material 3 组件建议）→ 给 UX 形态建议。
> 路径缩写：`ui/` = `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/`。**UI 源码根是 `ui/`（非 presentation/）**。

## 0. 总体结论（先读）

1. **差距文档的三项判定已过时，实现前必须刷新认知**：
   - **@ 引用（项1）**：文件/目录 @ 补全**已存在**（popup + chip 高亮 + 草稿持久化），缺的是会话源与消息渲染可点；判定应从 ❌ 改为 ◐。
   - **工具卡类型化（项12）**：`DefaultToolCardResolver` 已按 DSH 工具名分发 13+ 张类型卡；判定应从 ◐ 改为基本 ✓（余量为增卡）。
   - **压缩呈现（项11）**：`CompactionCard` 双态分割线（进行中流式 delta + 完成摘要卡）UI 完整，仅 DSH 事件未接（`DshEventMapper.kt:475-476` Ignored）。
2. **子代理续聊（项5）的 UI 通道已就绪**：AgentSheet 点行直达子会话完整 Chat（`PendingSheets.kt:266` → `ChatScreen.kt:1088`），`sessionParentId` 已进 UiState——缺的只是 `subagent.prompt/interrupt` 数据层三方法。
3. **唯一需要动会话状态 FSM 的是项14**（等待审批状态点）——SessionStatusService 铁律域，谨慎。
4. **多项改动会触碰 ChatScreen.kt / ChatMessageList.kt**（编辑协议文件）：composer 相关（项1/4/9）、消息流相关（项2/7/8/10）——按 `docs/chatscreen-editing-protocol.md` 串行循环，禁止并行编辑。
5. 所有新增文案都有 **15 语言 i18n** 成本（`docs/i18n-guide.md`），下述评级已计入。

---

## P1-1 `@` 引用系统（文件/目录/会话三源）

**(a) 挂点**：文件/目录源**已落地**——触发与查询 `DraftInputDelegate.kt:62-125`（`searchFilesForMention`，150ms debounce，走 `ManageAgentUseCase.searchFiles` → `FileApi.findFiles`，`data/api/file/FileApi.kt:74-81`，服务端搜索非本地）；文本监听在 `ChatScreenBottomBar.kt:176`；弹层 `input/FileMentionSuggestions.kt:39-112`（目录以尾 `/` 区分图标）；composer 内 chip 高亮 `input/FileMentionVisualTransformation.kt:15-19`；确认路径 `DraftInputDelegate.kt:113-119`，随草稿持久化（:188、:219-220）。**缺口**：① 会话源（`sessionReferenceResolver/candidates` + `dsh-session:` mention 发送通道）零落地；② 消息渲染侧 `@path` 是纯文本（`MessageCardUser.kt` 无 mention 识别/可点）；③ 弹层无分组标题/键盘导航/`@"` 引号形态（对照 `SlashCommandSuggestions.kt:35-43` 的既有样式补齐即可）。

**(b) 可复用**：`FileMentionSuggestions`（弹层骨架）、`SlashCommandSuggestions`（同构触发-过滤-选中管线，ChatInputBar.kt:148-189 双弹层并存先例）、`FileMentionVisualTransformation`（token 高亮机制可直接扩展会话 mention）、草稿持久化链（confirmedFilePaths）。

**(c) 工程量**：文件/目录源打磨（分组/下钻/引号）**S**；会话源 **M**（新 typert 端点接入 + mention token 序列化 + 发送通道）；消息渲染可点 **M**（`MessageCardUser` 正文改为 AnnotatedString 识别 `@path`/`dsh-session:` ref，点击走既有 `LinkUriHandler`/`FileViewerScreen` 导航）。合计 **M-L**。M3 组件：弹层沿用自绘 LazyColumn（现风格），mention chip 用 `InputChip` 视觉已由 VisualTransformation 达成，不必换。

**(d) UX 形态**：维持「键入 @ → 输入框上方弹出分组列表」（已与 Web 同构）；会话 mention 选中后插 `@标题` 彩色 token，消息流里点 token 跳转会话。

## P1-2 轨迹视图（请求级检查器 + 时间轴）

**(a) 挂点**：两种形态评估——
- **形态 A「轮次卡展开」**：数据已备——`RenderableTurn`（`tools/RenderableTurn.kt:20-31`）预计算了 `turnStartMs`/`durationMs`/`stepFinishes`（每步 StepFinish part），`PartGrouper`/`ToolSnapshotGrouper` 已把 turn 内 part 分组；挂点即 `MessageCardAssistant` 统计栏（`MessageCardAssistant.kt:728-790` ChunkStatsBar，现有 agent/模型/时长/复制行）加「轨迹」展开入口，展开区复用渲染项遍历。**风险**：动 ChatMessageList 渲染路径（SSE 滚动铁律域），展开态会改变高度补偿语义。
- **形态 B「独立检查器 sheet」**：新建（无现成 screen），容器直接套 `SheetScaffold`（`PendingSheets.kt:96-135`，75% 屏高 ModalBottomSheet），从 ChatFabMenu 工具栏注册第六入口（`ChatFabMenu.kt:71` `ChatToolbarEntry` 枚举 + :345-374 菜单项）。

**(b) 可复用**：`QuickNavigateSheet`（`components/QuickNavigateSheet.kt:56-80`——「列表+当前项高亮+点击跳转」完整范式，含 Room 异步加载态）+ `JumpNavigationController`（跳转锚定）；`StepProgressIndicator`/`ToolProgressCard`（步骤指示）；`ContextDetailDialog`（token 统计呈现先例）。时间轴缩放平移 = 自绘 `Canvas` + `transformable`，无现成件。

**(c) 工程量**：形态 A（轮卡内联步骤台账）**M**；形态 B（sheet 台账+点选检查器：token/时长/Input/Output/Timing——数据源 stepFinishes+ToolState.input/output）**M**；时间轴（缩放/平移/选区）**L** 且移动端收益存疑（Web 靠滚轮+右键，触屏手势映射不自然）。建议 A+B 组合：先 A，B 视需求。M3：sheet 内 `ListItem` 行 + `AnimatedVisibility` 展开区。

**(d) UX 形态**：Android 最自然形态是「轮次卡展开检查器」+ 底部 sheet 全屏台账二档，**不做**时间轴缩放（触屏无对应交互；若做只读静态条即可）。

## P1-3 工作区/会话组织（分组/归档/重命名/拖排序/多 workspace）

**(a) 挂点**：`sessions/SessionListScreen.kt`——已有双视图模式「最近 ↔ 目录树」（:222-256 DropdownMenu `toggleViewMode`，FOLDER 模式即按 workspace 目录分组的近似形态）；行内已注册 `combinedClickable` 长按（`SessionRow.kt:108-110`，当前长按=详情对话框）；重命名对话框已接（`SessionListScreen.kt:535` RenameSessionDialog）。**缺口**：① 归档动作（`workspace.archiveSession`）无 UI 无数据层；② 拖排序（`workspace.insertBefore/insertSessionBefore`）无（`PendingSheets.kt:548` 残留 `DragState` 死代码，曾试未竟）；③ workspace 重命名/删除（`DirectoryRow.kt` 无任何菜单，grep 零命中）；④ 多 workspace 并行建模（现只取首个 path，数据层）。左滑归档：SessionRow 加 `SwipeToDismissBox`（M3 原生）挂点在 `SessionRow.kt:108` Row 处。

**(b) 可复用**：`RenameSessionDialog`/`DeleteSessionDialog`（确认框范式 → 归档/删除工作区直接套 `ui/components/ConfirmDialog.kt`）；`DirectoryRow`/`SessionTreeList`/`TreeNode`（树渲染）；`OpenProjectDialog.kt`（372 行服务端目录浏览器——添加工作区的 browse 形态可直接复用，对齐 Web Miller 双列）；长按菜单可仿 `SessionListScreen.kt:222-256` DropdownMenu 或升级 `ModalBottomSheet` 行菜单（PendingSheets 同款）。

**(c) 工程量**：归档（长按菜单项+ConfirmDialog+一 RPC）**S**；行菜单改版（归档/重命名/复制路径聚合）**S**；左滑归档 **S**（SwipeToDismissBox 原生）；拖排序 **M**（Compose 拖拽手势 + 持久化 RPC + 树/平铺两模式语义差异）；workspace 重命名/删除 **S-M**；多 workspace 真建模（session 目录过滤从 cwd 全等改 workspace 归属）**L**（数据层主导）。UI 侧合计 **M**。

**(d) UX 形态**：长按会话行弹底部 sheet 行菜单（归档/重命名/复制路径）——Android 惯例；归档可用左滑快捷；拖排序建议**先不做**（移动端长会话列表拖拽价值低，Web 也要 Manual 模式才开）。

## P1-4 steer 插话直发（busy 时 composer 形态）

**(a) 挂点**：composer **busy 时本就可输入可发送**——`ChatInputBar.kt:132` `canSend` 在 NORMAL 模式不因 isBusy 置假（仅 shell 模式与提问/审批 pending 时禁，`ChatScreenBottomBar.kt:309` inputEnabled）；busy 无文本=单停止键、有文本=停止+发送双键（`ChatInputBar.kt:264-279`，注释明示「发送点击=服务端排队，DSH prompt mode=queue → QueueDock」）。QueueDock 已有**逐条 steer**（`ChatScreen.kt:779-808`，`QueueActionKind.STEER`，运行中才启用；子代理会话只读 :785）。**缺口**：发送时直接以 `mode=steer` 直插当前轮——`SendMessageUseCase.kt:13-23` 无 mode 参数（数据层贯穿缺失）；「空草稿批量插话」无对应；busyEnter 设置项无。

**(b) 可复用**：QueueDock 的 steer 语义与 UI（`QueueDock.kt`）；`SendStopButton`（双键区）；设置行范式（`settings/SettingsScreen.kt`）。

**(c) 工程量**：UI 侧——busy 时发送按钮加「直发插话」形态（如发送键上滑/长按直发，或 busy 时发送键旁一枚小 steer chip）**S**；busyEnter 设置行 **S**；数据层 mode 参数贯穿（UseCase→Repository→DshApiClient.promptAsync）**M**。合计 **M**。

**(d) UX 形态**：保持现有「busy 可输入、发送=排队」主路径（Android 双键设计是对 Web 的合理扩展，走查3已实锤 Web 忙碌时只有停止键）；直发插话做成**长按发送键**的隐藏加速路径 + QueueDock 逐条 steer 保留（已是 Web 超集）。

## P1-5 子代理续聊（AgentSheet 节点 → 可对话视图）

**(a) 挂点**：**UI 链路已通**——AgentSheet 树行点击直达子会话完整 Chat（`PendingSheets.kt:193,266` `onOpenSubSession` → `ChatScreen.kt:1085-1089` → `ChatRoute.kt:32-56` `onNavigateToChildSession` 导航到独立 ChatRoute）；「这是子代理会话」的判定已进 UiState（`ChatUiState.kt:52` `sessionParentId` ← `ChatStateAggregator.kt:99`），QueueDock 已按它切只读（`ChatScreen.kt:785`）。**缺口**：子会话内发送仍走 `session.prompt`（agent-busy 拒）——`subagent.prompt/interrupt/history` 三方法在 data 层零实现（grep 全仓无命中）；停止键（`SendStopButton.onStop`）未按 parentId 分流到 `subagent.interrupt`。UI 侧仅需：`ChatViewModel` 发送/停止按 `sessionParentId != null` 分流 + 若干 i18n 文案。

**(b) 可复用**：整条 AgentSheet 树（`SubagentTreeDelegate.kt:73-185` 双轨状态机）= Web「谱系导航」的等价形态；ChatRoute 参数化会话（子会话已是普通 Chat，历史加载/流式/工具卡全部免费获得）；`TaskToolCard.onViewSubSession`（消息流内跳子会话先例）。

**(c) 工程量**：UI 侧 **S**（分流判断+文案）；含数据层三方法接入 **M**（subagent.history 需与既有消息流合并策略）。**本表性价比最高项**。

**(d) UX 形态**：维持「树面板点行 → 子会话变成普通聊天屏」——与 Web 下钻形态（走查4实锤：子会话头部即正常续聊 composer）同构；Stop 键在子会话内语义自然切为 interrupt，无需新控件。

## P1-6 goal.complete 按钮

**(a) 挂点**：`GoalSheet.kt:163-183` `GoalDetail` 动作行——现有 pause(active)/resume(paused)/edit/clear 四钮，**独缺 complete**；API 已在（`DshApiClient.kt:372-373` `goalComplete`）；接线点 `ChatScreen.kt:1090-1101`（GoalSheet 调用，onCreate/…/onClear 五回调俱全，补 `onComplete`）；ViewModel 补 `completeGoal()`（与 pauseGoal/resumeGoal 同排，目标状态源 `ChatScreen.kt:635-637`）。另可考虑 `ChatFabMenu.kt:361-367` GOAL 角标（complete 后角标消失自然成立）。

**(b) 可复用**：GoalSheet 全套（SheetScaffold 容器、phase 标签、按钮行布局）；`ConfirmDialog`（complete 可加确认）。

**(c) 工程量**：**S**（一个按钮 + 一个回调 + VM 一方法 + 15 语言一个词条）。纯 UI 补齐，与差距文档「API 全链在位」一致。

**(d) UX 形态**：GoalDetail 动作行第四钮「标记完成」（filled tone 或 error 色对齐 clear 的视觉权重区分），点后回创建表单（phase=complete 既有空态语义 `GoalSheet.kt:61-63`）。

---

## P2-7 消息反馈 👍/👎 + 备注

**(a) 挂点**：assistant 气泡统计栏（`MessageCardAssistant.kt:728-790` ChunkStatsBar——复制钮所在行，:785 CopyButton，且 :177 `showStatsBar` 已含 `isTurnLast` 条件=「完成轮最后一条」语义现成）；user 气泡 meta 行（`MessageCardUser.kt:180-208` Undo/Copy 小图标行先例）。无长按菜单先例于气泡上（气泡在 `SelectionContainer` 内，长按=文本选择，**冲突**——长按方案不可取）。

**(b) 可复用**：`RejectWithMessageDialog`/`ConfirmDialog`（备注编辑对话框范式）；`CopyButton`（动作图标样式）；数据层需新增 messageFeedback 三 typert 方法（现零接触）。

**(c) 工程量**：UI **S-M**（统计栏加 👍/👎 图标 + 备注对话框 + 激活态回显；feedback/record 事件消费在 mapper）；含数据层 **M**（CAS version 和解逻辑）。

**(d) UX 形态**：**气泡下动作行**（统计栏右端加两枚小图标，仅 `isTurnLast` 完成轮显示）——长按会与文本选择手势冲突，勿用。

## P2-8 产出文件行 deliverables（轮尾 chip 行）

**(a) 挂点**：轮尾判定现成——`ChatMessageList.kt:1404-1423`（isTurnLast O(1) 索引）与 :303-308；产出文件可**纯客户端**从 turn 内 `Part.Tool`（write/edit/apply_patch 的 filePath 参数，`DefaultToolCardResolver.kt:61-63` 同款提取）聚合，无需新端点。渲染挂点二选一：MessageCardAssistant 统计栏下方（turn 尾消息内部）或 ChatMessageList turn 边界新 item（动消息流，谨慎）。

**(b) 可复用**：`onOpenFile` 回调已贯穿到 MessageCardAssistant（`MessageCardAssistant.kt:85`）→ `FileViewerScreen`/`CodeWebView` 打开文件全链现成；`SuggestionChip`（M3 原生）做文件 chip；≤6 截断 + 「+N」徽标（`ChatFabMenu.kt:412-423` BadgedBox 先例）。

**(c) 工程量**：**M**（聚合逻辑 + chip 行组件 + 展开全部；「Show in folder」🔒 loopback 不做；「结尾散文内联提及可点」并入项1的可点 mention 工作）。

**(d) UX 形态**：turn 尾助手气泡统计栏下一行横向滚动 `SuggestionChip` 文件行（点击开 FileViewer）——不占独立轮卡，视觉安静。

## P2-9 Plan 模式（composer chip + plan-review 专卡）

**(a) 挂点**：chip——composer 选择器行（`ChatInputBar.kt:219-234` AgentModelVariantSelector/权限 chip 同排，DSH 权限 chip `PermissionPresetSelector` 即同排先例）；plan/mode 事件当前 Ignored（`DshEventMapper.kt:510-511` POLICY_STATE 留痕，:1151 注释已知策略态域）；`/plan` 命令经既有 commands/execute 链（`ChatViewModel.kt:1304-1305`）即可切换。plan-review 专卡——提问卡管线现成：`QuestionCard`（`dialog/QuestionCard.kt:56-70`，pager/单选/多选/自定义/取消全有）+ 挂载点 `MessageCardAssistant` pendingQuestion 插槽（`MessageCardAssistant.kt:96-99` 提问卡锚定机制）与全屏 dialog 两态；专卡=识别 question 意图（plan-review）换头渲染 + 三决策钮（Chat about it=拒绝回 composer / Refuse / Approve）。

**(b) 可复用**：`PermissionPresetSelector`（chip 视觉与门控模式）；`QuestionCard`/`QuestionPartContent`（plan-review body 滚动 markdown 直接套）；`ConfirmDialog`（Approve 前确认可选）。

**(c) 工程量**：chip（事件映射+状态位+composer chip）**M**；plan-review 专卡 **M**（意图识别在 mapper + 专卡变体）。合计 **M**。

**(d) UX 形态**：composer 选择器行一枚黄字 `Plan ×` AssistChip（点按执行 /plan off，对齐 Web）；plan-review 用提问卡管线的**意图变体**（Approve/Refuse/继续讨论三钮），不新开 dialog 通道。

## P2-10 重试 / turn-error / max-tokens 呈现

**(a) 挂点**：重试——`RetryBanner` 已渲染于消息流尾（`ChatMessageList.kt:1085-1089`），`SessionStatus.Retry` 已带 `next` 时间戳（`domain/model/SessionStatus.kt:22-25`）但横幅未显示倒计时（`RetryBanner.kt:44-50` 只显 attempt+message）；turn-error——`SessionErrorCard` 已内联转录行（`ChatMessageList.kt:1092-1101`，组件头注释明示对齐 DSH TurnError 语义，sendMessage 成功自动清）。**缺口**：max-tokens 专卡与「发送 continue」按钮——turn-error 文本需识别 max-tokens 形态（数据层判定）后给 `SessionErrorCard` 加可选动作钮。

**(b) 可复用**：`SessionErrorCard`（加 `actionText/onAction` 参数即成）；`RetryBanner`（加倒计时用现成 `next` 字段）；发送 continue 直接调 `viewModel.sendMessage("continue")`（`ChatViewModel.kt:1158-1165`）。

**(c) 工程量**：**S-M**（重试倒计时 S；max-tokens 判定+专卡+continue 钮 M——判定逻辑依赖 turn-error payload 细节需实测取样）。

**(d) UX 形态**：错误轮末尾内联错误卡右下「发送 continue 续写」TextButton；重试横幅加每秒倒计时（shimmer 可选）——全部内联进消息流，与 DSH transcript 语义同构。

## P2-11 压缩呈现（过程指示 + 摘要卡）

**(a) 挂点**：**UI 已完整实现**——`CompactionCard.kt:41-60` 双态分割线（进行中=indeterminate 进度线+可展开流式 delta；完成=引用式 markdown 摘要卡），挂载 `CompactionDividerSlot` 三处（`ChatMessageList.kt:1062/1385/1618`），尾部兜底 `CompactionDividerPolicy.tailSpec`（:1052-1083）。**唯一缺口在 DSH 事件面**：`DshEventMapper.kt:473-476` 仅 compaction/end→snackbar，start/summary/prune 全 Ignored（V2 路径已全通，`SessionNextEventHandler` 消费 CompactionStateInfo 有先例）。

**(b) 可复用**：整套压缩 UI 零新增；DSH 侧照 V2 映射先例把三事件映射进既有状态类即可。

**(c) 工程量**：UI 侧 **S**（≈0，验证集成即可）；全栈 **M**（mapper 分支 + 状态穿透 + 流式 delta 与 DSH 事件粒度对齐实测）。

**(d) UX 形态**：维持现形态（骑线分割线原位切换、展开不跨会话记忆）——实现度已超差距文档判定。

## P2-12 工具卡类型化（terminal/diff/search 分卡）

**(a) 挂点**：**已类型化**——`tools/DefaultToolCardResolver.kt:30-95` cardMap：bash/shell→BashToolCard、edit→EditToolCard（diff）、read→ReadToolCard、write→WriteToolCard、glob→GlobToolCard、grep/search→SearchToolCard、task/subagent/subagent_fork→TaskToolCard（子会话跳转）、web_fetch/websearch→WebFetch/WebSearchCard、apply_patch→ApplyPatchToolCard；另有 `cards/` 下 TodoListCard/ShellCard/PatchCard 与 `ToolCardScaffold`（260 行统一骨架）；显示名解析 `ToolCardRegistry.kt:54-79`。差距文档「通用工具卡 ◐」判定过时。

**(b) 可复用**：`ToolCardScaffold`（生命态/展开/图标统一）——新卡只写内容体。

**(c) 工程量**：对 Web 剩余真实差距 = question 工具行、skill 工具行（指令展开）、Code Dispatch 递归树（后者休眠 ⏸#288）——每张 **S**，合计 **S-M**。低优先。

**(d) UX 形态**：维持现分卡形态；缺的工具行照 `ToolCardScaffold` 增补即可。

## P2-13 Full access 二次风险确认

**(a) 挂点**：`input/PermissionPresetSelector.kt:29-60`——下拉点选**直接** `onSelectPreset`，无确认；`danger-full-access` 档已有专属文案（:111 `permission_full_access`）。确认框插在「选中 danger-full-access → 回调前」，一处分支。

**(b) 可复用**：`ui/components/ConfirmDialog.kt`（现成确认对话框）；Web 语义「勾选才激活」在 Android 可简化为确认对话框单按钮（移动端无键盘勾选习惯），或 AlertDialog + 复选框。

**(c) 工程量**：**S**（一个条件分支 + 一个对话框 + 15 语言 2-3 词条）。最小项。

**(d) UX 形态**：AlertDialog 确认（标题「完全访问」+ 风险描述 + 取消/启用），对齐 M3 官方危险操作范式。

## P2-14 行状态点细化 + 新建会话 workspace picker

**(a) 挂点**：状态点——`SessionRow.kt:118-152` 已有四态图标（Busy 气泡/Asking 高亮气泡/Retry 错误图标）+ `BadgedBox` 未读红点；`SessionStatus.kt:9-25` 枚举含 Idle/Busy/Asking/Retry。**缺口**：「等待审批」琥珀态（审批 pending 未进会话状态——需 SessionStateFSM 增态，**铁律域**：`SessionStateService` 单一真相源，禁按 handler 另建状态）；「计划待审」随项9。workspace picker——`NewSessionQuickDialog.kt:54-71` 已按目录分组既有会话供选择（近似 picker）；`OpenProjectDialog` 提供服务端全目录浏览；真 workspace picker 需 `workspace.list` 多行建模（现取首个 path，数据层）。

**(b) 可复用**：SessionRow 状态图标槽与配色函数（`SessionUiHelpers.kt`/`SessionCategoryStyle.kt`）；`NewSessionQuickDialog`（扩展为 workspace 分组头）；`DirectoryPath`/`DirectoryRow`（路径呈现）。

**(c) 工程量**：等待审批状态点 **M**（FSM 增态 + 映射 + 图标，风险在状态机不；在 UI）；未读完成（绿点语义=完成时间>lastRead，hasUnread 已有，只差「完成」分色）**S**；workspace picker **M**（UI S，多 workspace 建模数据层主导）。

**(d) UX 形态**：状态用图标色变化（琥珀=待审批/待回答）不加新列；新建会话入口维持 QuickDialog，把目录分组头升级为「工作区」节头 + 末行「浏览全部目录…」接 OpenProjectDialog。

---

## 汇总表

| # | 项 | 挂点（核心 file:line） | 可复用 | 工程量(UI) | UX 形态 |
|---|----|------------------------|--------|-----------|---------|
| 1 | @ 引用 | DraftInputDelegate.kt:62-125 · FileMentionSuggestions.kt:39 · FileMentionVisualTransformation.kt:15 | SlashCommandSuggestions、草稿持久化链 | 文件源打磨 S；会话源+可点 M（合计 M-L） | 输入框上方分组弹层；mention 彩色 token 可点跳转 |
| 2 | 轨迹视图 | RenderableTurn.kt:20-31 · MessageCardAssistant.kt:728-790；新 sheet 走 PendingSheets.kt:96 | QuickNavigateSheet、SheetScaffold、StepProgressIndicator | 台账+检查器 M；时间轴 L（不建议） | 轮次卡展开检查器 + 底部 sheet 全屏台账；不做缩放时间轴 |
| 3 | 会话组织 | SessionListScreen.kt:222-256,535 · SessionRow.kt:108 · DirectoryRow.kt(无菜单) | RenameSessionDialog、ConfirmDialog、OpenProjectDialog、SwipeToDismissBox | 归档/行菜单/左滑 S×3；拖排序 M；多 workspace L(数据) | 长按底部 sheet 行菜单 + 左滑归档；拖排序缓行 |
| 4 | steer 直发 | ChatInputBar.kt:132,264-279 · ChatScreen.kt:779-808(QueueDock steer 已有) | QueueDock、SendStopButton | UI S + 数据层 M | busy 保持双键排队主路径；长按发送=直插话 |
| 5 | 子代理续聊 | PendingSheets.kt:266→ChatScreen.kt:1088→ChatRoute.kt:32 · ChatUiState.kt:52 | AgentSheet 全树、参数化 ChatRoute | UI S（含数据层 M）——性价比最高 | 树点行→子会话=普通聊天屏；Stop 切 interrupt |
| 6 | goal.complete | GoalSheet.kt:163-183 · ChatScreen.kt:1090-1101 · DshApiClient.kt:372 | GoalSheet 全套、ConfirmDialog | **S** | 动作行第四钮「标记完成」，回创建表单 |
| 7 | 消息反馈 | MessageCardAssistant.kt:177,728-790 · MessageCardUser.kt:180-208 | ConfirmDialog/备注对话框范式 | UI S-M；含数据层 M | 气泡下动作行（isTurnLast 才显）；禁长按（文本选择冲突） |
| 8 | deliverables | ChatMessageList.kt:1404-1423 · MessageCardAssistant.kt:85(onOpenFile) | SuggestionChip、FileViewer 链、BadgedBox | M | turn 尾横滚文件 chip 行，点击开 FileViewer |
| 9 | Plan 模式 | ChatInputBar.kt:219-234 · DshEventMapper.kt:510 · QuestionCard.kt:56 | PermissionPresetSelector、QuestionCard 管线 | M（chip+专卡各 M 减） | 选择器行黄字 Plan× chip；plan-review=提问卡意图变体三钮 |
| 10 | 重试/max-tokens | ChatMessageList.kt:1085-1101 · RetryBanner.kt:44 · SessionStatus.kt:22-25 | SessionErrorCard(+action 参数)、sendMessage("continue") | S-M | 错误卡内嵌「发送 continue」钮；重试横幅加倒计时 |
| 11 | 压缩呈现 | CompactionCard.kt:41-60 · ChatMessageList.kt:1062,1385,1618 · DshEventMapper.kt:473-476 | 整套现成 UI | UI S（全栈 M，仅 DSH 事件未接） | 维持双态骑线分割线（已超文档判定） |
| 12 | 工具卡类型化 | DefaultToolCardResolver.kt:30-95 · cards/×14 · ToolCardScaffold | ToolCardScaffold | S-M（增 question/skill 行；文档判定过时） | 维持分卡；缺行照骨架增补 |
| 13 | Full access 确认 | PermissionPresetSelector.kt:29-60,111 | ConfirmDialog | **S** | AlertDialog 确认（取消/启用） |
| 14 | 状态点+picker | SessionRow.kt:118-152 · SessionStatus.kt:9-25 · NewSessionQuickDialog.kt:54-71 | SessionUiHelpers、OpenProjectDialog | 状态 M（动 FSM，谨慎）；未读完成 S；picker M | 图标色表意（琥珀=待审批）；QuickDialog 升级工作区分组头 |

## 横切注意事项

- **ChatScreen 协议文件**：项 1/4/9（composer）与项 2/7/8/10（消息流）都要动 `ChatScreen.kt`/`ChatMessageList.kt`/`ChatScreenBottomBar.kt`——按 `docs/chatscreen-editing-protocol.md` 串行 Read→编辑→compileDevDebugKotlin→commit 循环，禁止跨 agent 并行。
- **SSE 滚动铁律**：项 2/8 在消息流内加展开区/chip 行会改变高度补偿语义——优先选择「流式 turn 之外的位置」（turn 尾完结消息统计栏下方）并沿用 `deferredRevealCompensation` 先例（CompactionCard 已示范）。
- **状态机铁律**：项 14 的「等待审批」必须走 `SessionStateFSM` 增态，不得旁路建 per-handler 状态。
- **i18n**：每项新增文案 ×15 语言并跑检查脚本；上表 S 级项的主要成本往往就是翻译而非代码。
- **数据层前置**：项 1（sessionReferenceResolver）、4（prompt mode）、5（subagent 三方法）、7（messageFeedback 三方法）、9（plan/mode 映射）各有数据层工作，UI 侧评级以「数据层已就位」为前提单列。
