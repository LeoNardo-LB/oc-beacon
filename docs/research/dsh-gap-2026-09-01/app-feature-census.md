# OC Beacon（Android）用户可见功能点全量普查（分子清单）

> 日期：2026-09-02 · 只读调研，未改任何代码 · 基线 master（含 2026-09-01 dsh-jump-cards-queuedock / busy-dual-key-send 等批次）
> 证据：`app-inventory.md` §1-§5（RPC/事件/UI 清点，主源）× `implementability-ui.md`（UI 挂点审计，判定修正已吸收）× 源码直查（`ui/` 全目录 + `service/`/`MainActivity.kt`，本次逐一核证）× `2026-09-01-dsh-web-vs-android-gap.md` §1-§7（锚点）
> 用途：与 `fe-feature-census.md`（Web 端 169 点）对账的「Android 端总共有多少功能点」分子。**与 DSH 对齐的常规功能同样列内**，不做差距筛选。

## 口径

- **功能点** = 用户能感知/操作的一个独立能力。粒度对齐 Web 普查（@ 引用文件源单独一条；轨迹台账/检查器分开；审批/问答分开）。
- **ID 前缀 `A-`**（A-D1-01 …）避免与 Web 端 D1-01 混淆；标记列内「对位 W Dx-xx」= 与 `fe-feature-census.md` 该点语义对应。
- **标记**（四值，单选）：
  - `DSH对齐` = 与 DSH Web 某功能点语义对应（形态允许差异）；
  - `DSH部分` = Web 对应点的子集或显著形态差异（缺关键档位/通道）；
  - `➕独有` = Web 端没有对应功能点（Android 超集，含通知/i18n/主题/多服务器/本地全文搜索/审批 always 档/Diagnostics 等观测面）；
  - `本地` = app 壳层基础设施行为（不改变「能对 agent 做什么」，如下拉刷新/断连横幅/存储清理/发送前确认）。
- **协议门控**：OC Beacon 同时支持 OpenCode V1/V2 与 DSH 双栈（➕独有）。部分功能仅在 OpenCode 后端可见（终端 PTY、分享、revert、Git 面板、文件搜索、MCP 开关、provider OAuth 等——DSH 下能力位关闭隐藏，`ServerCapabilities` 登记面见 app-inventory §3.3）；这类点仍计入分母，标注「OpenCode 后端限定」。
- **不计入分母**：纯内部基础设施（Hilt DI、Room schema、DshReconciler 等无直接 UI）、debug 专属 intent（`debug_race`/`debug_perf`，MainActivity.kt:168-174）、`workflow` 休眠映射链（有降级卡一条，注明休眠）。
- 源码-文档矛盾处保留并注明（如 `/compact` 通道疑似 bug #297、DshJobSheet 双形态）。

---

## D1 会话管理（20）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D1-01 | 会话搜索-标题即时匹配 | 搜索栏输入即本地过滤标题（全量拉回后 contains） | ui/screens/sessions/components/SessionSearchBar.kt:49-69；app-inventory §1.1 | DSH对齐（对位 W D1-01；本部署双方同为标题匹配） |
| A-D1-02 | 会话内容全文搜索（本地 FTS5 BM25） | 搜索词命中按会话聚合展示：命中数+snippet+排序 | ui/screens/sessions/SessionListScreen.kt:342-416（#272） | ➕独有（对位 W D1-02 ⏸ 部署禁用；Android 走本地 Room FTS5 不依赖服务器） |
| A-D1-03 | 内容搜索过滤 chips | 角色（用户/助手）+ 时间范围单选过滤，切换即重查；0 命中保留本区可切回 | SessionListScreen.kt:369-383；components/ContentSearchFilterChips.kt | ➕独有 |
| A-D1-04 | 内容命中直达消息 | 命中组取 rank 最优 messageId，点击进 Chat 直接定位该消息 | SessionListScreen.kt:349-353,391；ContentHitNavigation.kt | ➕独有 |
| A-D1-05 | 会话行状态图标 | Busy 气泡（tertiary）/ Asking 高亮 / Retry 错误图标 / Idle 轮廓，四态 | components/SessionRow.kt:118-124 | DSH部分（对位 W D1-04 六态；无等待审批琥珀/子代理运行中/计划待审） |
| A-D1-06 | 未读红点 + 一键全部已读 | BadgedBox 红点；「更多」菜单 mark-all-read 消除全部 | SessionRow.kt:124-129；SessionListScreen.kt:247-256 | DSH部分（对位 W D1-05 未读绿点；Android 红点+批量消除） |
| A-D1-07 | 收藏会话 + 仅收藏筛选 | 行内星标 toggle；顶栏星形图标筛选收藏会话（选中态高亮） | SessionRow.kt:267；SessionListScreen.kt:184-196 | ➕独有 |
| A-D1-08 | 标签系统（创建/分配/管理/过滤） | 多选分配对话框（新建自动勾选）；管理区增删改（名称/颜色/图标）；搜索栏 chip 过滤 | components/TagPickerDialog.kt、TagManagementSection.kt；SessionSearchBar.kt | ➕独有 |
| A-D1-09 | 会话重命名 | 长按菜单→重命名对话框（预填当前标题） | SessionListScreen.kt:534-544；components/RenameSessionDialog.kt | DSH对齐（对位 W D1-08） |
| A-D1-10 | 会话删除 | 长按菜单→删除确认对话框（OpenCode 后端；DSH 无 session.delete 隐藏） | SessionListScreen.kt:547-556；app-inventory §1.1 | ➕独有（Web 无删除；OpenCode 后端限定） |
| A-D1-11 | 会话分叉 | Chat 顶栏菜单「分叉会话」 | ui/screens/chat/components/ChatTopBar.kt:228-237；app-inventory §1.1（session.fork） | DSH对齐（对位 W D1-09；fork 无消息锚点=末完成轮） |
| A-D1-12 | 视图模式切换 | 「更多」菜单在「最近列表 ↔ 目录树」间切换（持久化） | SessionListScreen.kt:227-245 | DSH对齐（对位 W D1-12 分组方式切换） |
| A-D1-13 | 目录树分组渲染 | parentSessionId 本地分组树（子代理层级可见）；DSH FOLDER 模式按目录分组 | components/SessionTreeList.kt、DirectoryRow.kt | DSH部分（对位 W D1-07 工作区分组；无折叠计数「展开其余 N 个」） |
| A-D1-14 | 历史同步状态与手动同步 | 长按菜单 History Sync 区：per-session 请求/取消同步 + 同步状态展示（#271 冷存桶 drain） | SessionRow.kt:470-481；SessionListScreen.kt:464-467 | ➕独有 |
| A-D1-15 | 会话长按详情对话框 | 长按行=详情（agent 预设只读标签、created「—」、同步区、复制 id） | SessionRow.kt:108-110,323-392（app-inventory §3.2） | DSH部分（对位 W D1-15 悬停卡：创建时间/复制路径/复制标题；created 协议缺席） |
| A-D1-16 | 复制会话 id | 长按菜单「复制会话 ID」剪贴板 | SessionRow.kt:501 | ➕独有 |
| A-D1-17 | 快速新建会话（最近目录） | 「+」先弹最近目录快速对话框（N 条可设），无会话时直达完整目录浏览器 | SessionListScreen.kt:197-212,516-531；components/NewSessionQuickDialog.kt:54-71 | DSH对齐（对位 W D9-06 新会话按钮；作用域=最近目录近似） |
| A-D1-18 | 下拉刷新会话列表 | PullToRefreshBox 触发重载 | SessionListScreen.kt:317-320 | 本地 |
| A-D1-19 | 断连写操作快速失败 | 写操作（重命名/删除/标签）断连时哨兵映射本地化 snackbar（#267） | SessionListScreen.kt:146-158 | ➕独有 |
| A-D1-20 | 会话级删除/重命名能力位门控 | DSH 下删除钮隐藏（无 session.delete）、重命名可用——同一 UI 按后端裁剪 | app-inventory §3.2；data/api/session/SessionApi.kt | 本地 |

## D2 工作区组织（8）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D2-01 | 应用内目录浏览器（选目录建会话） | OpenProjectDialog：LazyColumn 目录逐级下钻（host.listDirectory） | components/OpenProjectDialog.kt:230；app-inventory §3.4 | DSH部分（对位 W D2-02 Miller 双列；Android 单列） |
| A-D2-02 | 浏览器内新建文件夹 | 对话框输入名→host.createDirectory，校验+防双击+结果反馈 | OpenProjectDialog.kt:260-329 | DSH对齐（对位 W D2-03） |
| A-D2-03 | Chat 内直达 Workspace 文件树 | Chat 顶栏菜单「打开工作区」 | ChatTopBar.kt:178-188 | ➕独有（Web 无独立文件树视图，文件经 host.openPath 开本机） |
| A-D2-04 | Workspace 文件树浏览 | FileTreePanel 懒展开；无 type 判别→缺省 directory（#276 V4 补偿）；**文件点击禁用**（DSH 无读方法） | WorkspaceScreen.kt；FileTreePanel.kt:142（app-inventory §3.4） | DSH部分（对位 W D5-23 工具行开文件 🔒 双方不可达） |
| A-D2-05 | Git 变更面板 + diff 查看 | GitChangesPanel 列表+计数，点击开 DiffView（OpenCode 后端；DSH 能力位隐藏） | workspace/git/GitChangesPanel.kt:44；WorkspaceScreen.kt:61-79 | ➕独有 |
| A-D2-06 | 文件名搜索 overlay | 顶栏搜索图标→SearchTopBar+结果列表（文件树/Git 双面板过滤）（OpenCode 后端） | workspace/search/SearchOverlay.kt:42-82；WorkspaceScreen.kt:112-151 | ➕独有 |
| A-D2-07 | 服务器项目列表（workspace.list） | 项目 path/cwd/directory 多键读；目录树根解析序 directory→首个→host.describe cwd | app-inventory §1.3/§3.4 | DSH部分（对位 W D2 多工作区并行；Android 只取首个 path 做根，跨 workspace 未建模） |
| A-D2-08 | 会话工作目录呈现 | Chat 顶栏副标题显示会话 cwd | ChatTopBar.kt:94-102 | 本地 |

## D3 Composer 输入（18）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D3-01 | @ 文件/目录引用 | `@` 触发文件源（150ms debounce 服务端搜索）→ 弹层选中插 chip 高亮 token；目录以尾 `/` 区分图标；随草稿持久化 | chat/DraftInputDelegate.kt:62-125；input/FileMentionSuggestions.kt:39-112；FileMentionVisualTransformation.kt:15-19 | DSH部分（对位 W D3-01/02；无分组标题/键盘导航/`@"` 引号形态/下钻；消息渲染侧 @ 为纯文本不可点——implementability-ui P1-1） |
| A-D3-02 | 图片附件拾取 | 输入行回形针→系统 photo picker（多选） | input/ChatAttachmentsHandler.kt:285 | DSH部分（对位 W D3-06 粘贴/拖放 intake；移动端无粘贴/拖放） |
| A-D3-03 | 发送前图片压缩管线 | 设置联动：长边上限+WebP 质量压缩后发送 | ChatAttachmentsHandler.kt:86-153；ChatBehaviorSection.kt:127-163 | ➕独有 |
| A-D3-04 | 草稿图片轨 | 附件缩略图横排+单图删除 | input/ImageAttachmentRow.kt:46-105 | DSH对齐（对位 W D3-08 草稿轨；无翻页箭头/滚轮横滚） |
| A-D3-05 | 图片全屏预览 + 保存到设备 | 点缩略图开预览对话框；「保存图片」经系统 SAF 落盘 | dialog/ImagePreviewDialog.kt:141-189；ChatAttachmentsHandler.kt:204-232 | DSH部分（对位 W D3-10 lightbox；「保存到设备」为 ➕ 成分） |
| A-D3-06 | 外部分享图片接收 | 其他 app 分享图片（ACTION_SEND/SEND_MULTIPLE）→ 直入该会话附件区 | MainActivity.kt:98-103,160-162；ChatAttachmentsHandler.kt:236-280 | ➕独有 |
| A-D3-07 | 草稿持久化（文本+附件） | 每 keystroke 直写 DataStore（防 force-stop 丢草稿）；revert 后草稿回填 | DraftInputDelegate.kt:43-150,136-146 | ➕独有（Web 草稿无跨重启持久） |
| A-D3-08 | 发送/停止双键区 | busy 无输入=单停止键；busy 有输入=停止+发送并排（发送=mode:queue 排队入 QueueDock） | input/ChatInputBar.kt:132,265-279；SendStopAreaState.kt | DSH部分（对位 W D3-12 忙碌单键切换；Android 双键=排队语义，无直发 steer） |
| A-D3-09 | 排队消息 QueueDock 三动作 | BottomBar 上方排队条：预览+行内编辑（纯文本）/删除/steer 插话（仅 running+next-turn） | components/QueueDock.kt:104-164；ChatScreen.kt:779-808 | DSH对齐（对位 W D3-13/14 dock+行控件；steer 仅对已排队项，无「空草稿批量插话」） |
| A-D3-10 | 子代理会话 QueueDock 只读 | sessionParentId≠null 时排队条只读（服务器拒 agent-busy） | ChatScreen.kt:785 | DSH部分（对位 W D6-05 子代理可续聊；Android 只读） |
| A-D3-11 | 权限预设选择器 chip | 输入行首位下拉切档（动态档集 #283；custom 态灰显）；**无 Full access 二次确认** | input/PermissionPresetSelector.kt:29-60,111 | DSH部分（对位 W D4-11 chip；缺 W D4-12 风险确认 Modal） |
| A-D3-12 | 模型/思考档选择器行 | 模型座+agent 选择+variant 入口+回形针+快速导航同排；输入行 variant pill 已裁撤（#187③） | input/AgentModelVariantSelector.kt:51-156 | DSH对齐（对位 W D4-13 模型座） |
| A-D3-13 | 提问/审批接管输入 | pending 提问或审批存在时输入禁用（inputEnabled=false） | ChatScreenBottomBar.kt:309 | DSH对齐（对位 W D7-01/03 composer 接管） |
| A-D3-14 | 发送前确认 | 设置开启后发送弹 SendConfirmDialog | ChatScreenDialogs.kt:61-64；ChatBehaviorSection.kt:76-90 | ➕独有 |
| A-D3-15 | composer placeholder 轮换提示 | 多条 hint 按节奏轮换展示 | ChatInputBar.kt:51-130 | 本地 |
| A-D3-16 | 忙碌指示平滑（呼吸点） | BusyIndicatorSmoother 平滑 busy/idle 指示跳变 | input/BusyIndicatorSmoother.kt | 本地 |
| A-D3-17 | Agent 切换（OpenCode agents） | 输入行 agent 选择器（OpenCode 后端 agents 目录） | AgentModelVariantSelector.kt:51-68；app-inventory §3.3 | ➕独有（Web 无 composer 内 agent 切换对位） |
| A-D3-18 | shell 模式输入（`!` 前缀自动进入） | 检测 `!` 开头自动切 shell 模式（OpenCode 后端能力位；DSH 隐藏） | ChatScreenBottomBar.kt:145-166 | ➕独有（对位 W 无 shell 模式域） |

## D4 命令与模式（15）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D4-01 | 斜杠命令菜单 | `/` 触发候选弹层（commands/list roster：名称+描述） | input/SlashCommandSuggestions.kt:35；util/SlashCommandRegistry.kt；app-inventory §1.4 | DSH对齐（对位 W D4-01） |
| A-D4-02 | 命令参数提示填充 | CommandDescriptor input.hint 作为自由参数占位提示 | app-inventory §1.4（#285） | DSH部分（对位 W D4-01 三路分发/fuzzy；Android 前缀过滤无 fuzzy 加分） |
| A-D4-03 | 命令执行回执 snackbar | 执行成功/失败 snackbar 文案（不进会话日志） | ChatScreenBottomBar.kt:106-107 | DSH部分（对位 W D4-02 右对齐回显气泡+detached 结果节点） |
| A-D4-04 | /compact 压缩入口 | 顶栏菜单「压缩会话」；通道已修（#297，52dd8752）：commands/execute `args{agentId, line:"/compact", images:[]}`（原 prompt 文本通道坐实会进模型，已废弃） | ChatTopBar.kt:218-227；DshApiClient.kt:205-217 | DSH对齐（对位 W D4-04；#297 已修待验收） |
| A-D4-05 | 压缩完成提示 | compaction/end → snackbar「已压缩」；压缩分割线 UI 见 A-D5-08 | DshEventMapper.kt:426-429 | DSH部分（对位 W D5-09 检查点行+摘要；Android 过程/摘要事件未接） |
| A-D4-06 | /export 会话导出 | 顶栏菜单→ZIP 流式下载+进度回调 | ChatTopBar.kt:265-274；DshApiClient.kt:546（app-inventory §1.4） | DSH对齐（对位 W D4-05） |
| A-D4-07 | /permission 命令通道切换 | commands/execute（"/permission <preset>"）封装 | DshApiClient.kt:262,269 | DSH对齐（对位 W D4-11） |
| A-D4-08 | /model 模型选择对话框 | 抽屉：provider 分组+行点击快速选中+chevron 二级 variant pills（含「默认」档）+默认模型 toggle+「管理模型」入口 | dialog/ModelPickerDialog.kt:63-296（#187/#188） | DSH对齐（对位 W D4-13/14；二级 effort/variant 菜单形态对应） |
| A-D4-09 | Goal 面板全生命周期 | FAB 菜单 GOAL 入口：创建（objective+maxGoalRounds）/编辑/暂停/恢复/清除；phase 标签+rounds N/M+blockedReason；**complete 无按钮**（API 在位） | GoalSheet.kt:47-183（app-inventory §3.3、§5.1） | DSH部分（对位 W D3-16/D4-08 GoalBar 编辑/暂停/恢复/清除；complete 缺 UI） |
| A-D4-10 | FAB 目标运行角标 | goal active/blocked → FAB 运行点+菜单项 phase 色角标（blocked 警示红） | ChatFabMenu.kt:302-429（#286） | ➕独有（Web GoalBar 常驻条 vs 角标提醒形态） |
| A-D4-11 | Agent 预设空白页选卡 | blank 会话渲染 roster 卡（name+description），点卡即 select；locked→snackbar；可反复换档 | components/ChatEmptyState.kt:47-141 | DSH对齐（对位 W D4-17 预设 chip staged 选择） |
| A-D4-12 | 会话行预设只读标签 | SessionRow 解析 agentPreset id→name 只读标签 | SessionRow.kt:323-392（app-inventory §3.2） | DSH对齐（对位 W D4-18） |
| A-D4-13 | 任务批量转后台 | 顶栏菜单入口（OpenCode 后端能力位；DSH/V1 隐藏） | ChatTopBar.kt:206-217 | ➕独有（Web 无后台化域） |
| A-D4-14 | 会话分享/取消分享 | 顶栏菜单 share/unshare，链接复制 snackbar（OpenCode 后端；DSH 隐藏） | ChatTopBar.kt:240-264；ChatScreenBottomBar.kt:110-113 | ➕独有（Web 无 share 域） |
| A-D4-15 | 消息撤销/重做（revert） | user 气泡 Undo 图标+RevertBanner（redo）+撤销/重做失败 snackbar（OpenCode 后端；DSH 无方法） | components/MessageCardUser.kt:180-195；RevertBanner.kt:38；ChatScreenBottomBar.kt:114-117 | ➕独有（「回退」能力 Web 端不存在） |

## D5 会话内容渲染（22）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D5-01 | 用户气泡（复制+分块） | 复制钮；长消息分块渲染；图片 part 内嵌 | components/MessageCardUser.kt:67-206,284-405 | DSH对齐（对位 W D5-01；不可编辑无分支双方一致） |
| A-D5-02 | Markdown 渲染（GFM+表格） | 助手气泡 markdown；表格一致性规则；任务列表标记规范化 | markdown/MarkdownContent.kt、MarkdownTable.kt、NormalizeTaskListMarkers.kt | DSH部分（对位 W D5-02-04；无 KaTeX 数学、代码高亮覆盖待查） |
| A-D5-03 | Think/reasoning 块 | 折叠推理块，流式跟随渲染；设置可默认展开 | components/ReasoningBlock.kt；ChatDisplaySection.kt:74-88 | DSH对齐（对位 W D5-07） |
| A-D5-04 | 工具卡类型化（13+ 张） | 按工具名分卡：bash/shell/edit(diff)/read/write/glob/grep-search/task/webfetch/websearch/apply_patch/todo/shell/patch | tools/DefaultToolCardResolver.kt:30-95；cards/×14 | DSH对齐（对位 W D5-13-21；缺 question 行/skill 行/Code Dispatch 树） |
| A-D5-05 | 工具行生命态+展开收起 | ToolCardScaffold 统一骨架：running/成功/失败/展开输出 | tools/cards/ToolCardScaffold.kt | DSH对齐（对位 W D5-22） |
| A-D5-06 | 中断标记 | interrupted 消息前缀呈现 | app-inventory §3.3（消息渲染行） | DSH部分（对位 W D5-22 interrupted 警告点；无专门重试行内 ∞ 态） |
| A-D5-07 | 图片消息渲染+附件回源 | Part.File 渲染；url 缺席按 attachmentId 拉 session.attachment 回源拼 data URL（#287；跨进程丢失 #295 未决） | app-inventory §3.3；ChatRepositoryImpl.kt:351 | DSH部分（对位 W D3-09 历史画廊；单图内嵌无画廊裁剪形态） |
| A-D5-08 | 压缩分割线（双态 UI） | CompactionCard：进行中=进度线+可展开流式 delta；完成=引用式摘要卡（DSH 事件仅 end 接入，start/summary Ignored） | components/CompactionCard.kt:41-60；DshEventMapper.kt:473-476 | DSH部分（对位 W D5-09；UI 已完整仅缺事件接线——implementability-ui P2-11） |
| A-D5-09 | 重试横幅 | 消息流尾 RetryBanner（attempt+message；无倒计时） | components/RetryBanner.kt:44-50 | DSH部分（对位 W D5-10 倒计时+shimmer+∞ 态） |
| A-D5-10 | 轮终态错误内联卡 | SessionErrorCard 内联转录（sendMessage 成功自动清；host/agent-error→卡+一次性 snackbar） | components/SessionErrorCard.kt；ChatMessageList.kt:1092-1101 | DSH对齐（对位 W D5-11） |
| A-D5-11 | 事件卡（子代理/后台任务完成+目录变更） | 三类 SSE 事件卡+前向箭头跳转子会话 | components/EventCard.kt:47,147 | ➕独有（Web 无此类完成事件卡） |
| A-D5-12 | Todo 卡+Todo Sheet | 消息流 TodoListCard + FAB 菜单 TodoSheet（待办计数角标） | tools/cards/TodoListCard.kt；PendingSheets.kt:140-164 | DSH对齐（对位 W D5-18/D3-15；无「N 已完成·M 进行中」计数头文案） |
| A-D5-13 | 后台任务 jobs 双形态 | FAB SHELL 入口 DshJobSheet（状态色/文案/duration）+ 消息流内联时间线卡 | PendingSheets.kt:285-496（app-inventory §2.1/§3.3） | DSH对齐（对位 W D5-35 jobs 徽标+popover；流内时间线卡为超集形态） |
| A-D5-14 | workflow 运行降级卡 | synthetic 卡同 runId 原位更新（服务器不暴露事件——休眠 #288） | components/SyntheticNotificationCard.kt；DshEventMapper.kt:492-493 | DSH部分（对位 W D5-36 ⏸；双方均不可用，Android 有休眠降级卡） |
| A-D5-15 | 消息统计栏 | assistant 气泡尾部：agent/模型/时长/token 用量+复制钮（完成轮末条显） | MessageCardAssistant.kt:177,728-790 | DSH部分（对位 W D5-05/06 hover TTFT/tok/s+统计条；Android 常驻栏无 TTFT/tok/s 明细） |
| A-D5-16 | 上下文占用环 | 顶栏环：分子 projectedTokens/分母 window；<70 primary/<70-90 tertiary/≥90 error；点击开详情；投影缺席不渲染 | ChatTopBar.kt:110-152 | DSH对齐（对位 W D3-18 ContextMeter 环） |
| A-D5-17 | 上下文详情弹窗（①-⑦ 区） | provider/model+时间戳/进度条/消息数+cache 命中/构成列表/token 四桶/子代理 token+活跃时长/占用投影（~used/window+system/tools/messages） | components/ContextDetailDialog.kt:69-222 | DSH对齐（对位 W D3-18 面板；子代理区+sessionStats 为超集数据） |
| A-D5-18 | 快速导航 sheet（用户提问跳转） | 输入行入口→用户消息 Q1..Qn 列表（Room 全量 ≤1000），当前项高亮+点击锚定跳转 | components/QuickNavigateSheet.kt:75-201；util/JumpTargetExtractor.kt | ➕独有（Web 无提问跳转器） |
| A-D5-19 | 历史分页加载 | 初始条数设置+滚动临近自动加载（AutoLoadPolicy）+对账回填（500 页护栏） | MessagePaginationDelegate.kt；AutoLoadPolicy.kt；app-inventory §1.1 | DSH对齐（对位 W D5-34「加载更早」；自动+手动混合形态） |
| A-D5-20 | SSE 流式渲染稳定性 | 48ms token 批处理+高度补偿+滚动岛屿+StreamingMarkdown 前缀差分（用户感知=不闪烁不跳底） | ChatScrollController.kt、ScrollIsland.kt、markdown/StreamingMarkdownPilot.kt | 本地 |
| A-D5-21 | 会话错误横幅+错误态屏 | ChatErrorState/ConnectionErrorScreen 全屏错误+重试 | components/ChatErrorState.kt；ui/components/ConnectionErrorScreen.kt | 本地 |
| A-D5-22 | 消息文本选择复制 | 气泡 SelectionContainer 长按选择复制（移动端形态） | components/MessageBubble.kt | 本地 |

## D6 子代理（6）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D6-01 | AgentSheet 子代理树 | FAB AGENT 入口（运行计数角标）：subagent.list 权威 L2 懒加载+本地镜像逐层降级（#284-a）；行=标题+running/inactive 活跃态 | chat/SubagentTreeDelegate.kt:73-185；PendingSheets.kt:166-282 | DSH对齐（对位 W D6-02/03 目录+行信息；无 token 四桶/精确到秒时长——后者在 A-D5-17⑥） |
| A-D6-02 | 树行直达子会话 Chat | 点行导航到子会话完整 ChatRoute（历史/流式/工具卡全套复用） | PendingSheets.kt:193,266 → ChatScreen.kt:1085-1089 → ChatRoute.kt:32-56 | DSH对齐（对位 W D6-04 下钻） |
| A-D6-03 | 子会话身份感知 | sessionParentId 进 UiState：顶栏菜单隐藏（仅父会话）、QueueDock 只读 | ChatUiState.kt:52；ChatTopBar.kt:163 | DSH部分（对位 W D6-01 面包屑/续聊；Android 无面包屑、不可续聊） |
| A-D6-04 | 工具卡跳子会话 | TaskToolCard 前向箭头 onViewSubSession；EventCard 同款 | tools/cards/TaskToolCard.kt；EventCard.kt:147 | DSH对齐（对位 W D6-04；⏸ 成员跳转的近似形态） |
| A-D6-05 | 子代理 token/时长投影 | 上下文详情弹窗⑥区：tokenUsage 累计+subagentTiming 活跃时长 | ContextDetailDialog.kt:192-220 | DSH对齐（对位 W D6-03 token 合计+活跃时长） |
| A-D6-06 | 任务工具栏（前台子代理计数） | composer 上方 TaskToolbar：前台子代理 N 计数+入口 | input/TaskToolbar.kt:37-86 | ➕独有（Web 用头部目录下拉呈现运行数，形态不同） |

## D7 审批与问答（10）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D7-01 | 权限审批卡（once/always/reject） | 审批到达弹卡：允许一次（主钮）/始终允许/拒绝→/api/respond；答后消解 | dialog/PermissionRequestCard.kt:148-154；app-inventory §1.4 | DSH对齐（对位 W D7-01/02 面板+允许一次/拒绝） |
| A-D7-02 | 审批 always 档 | 「始终允许」键=服务器落持久规则（Web 无 always） | PermissionRequestCard.kt:154 | ➕独有（对位 W D7-02 仅 once——任务示例点名超集） |
| A-D7-03 | 本地自动批准规则管理 | 设置页规则列表（always 累积结果）+逐条删除 | settings/components/PermissionRulesSection.kt | ➕独有 |
| A-D7-04 | 全部权限自动允许总开关 | 开启后任何 PermissionAsked 即自动应答 always | settings/sections/AutoApproveRulesSection.kt:25-48 | ➕独有 |
| A-D7-05 | 子代理权限拒绝附理由 | 拒绝弹理由输入对话框（注明来源子会话标题） | components/RejectWithMessageDialog.kt:22-54 | ➕独有（W D7-02 拒绝无理由输入） |
| A-D7-06 | 提问卡（多题 pager） | 单事件多问题：CompactTabs 翻页+上一题/下一题语义；单/多选按题判定；自定义文本恒可填；未答提示 | dialog/QuestionCard.kt:59-191 | DSH对齐（对位 W D7-03-06；一次一题 vs pager 翻页形态差异） |
| A-D7-07 | 提问提交/取消 | 提交 answers 键控 map；取消→cancelled 整单拒收 | PendingSheets/QuestionCard；DshRpcClient.kt:88（app-inventory §1.4） | DSH对齐（对位 W D7-07/08） |
| A-D7-08 | 提问卡消息流锚定 | pendingQuestion 锚定 tool.callId part 插入消息流（全屏 dialog 两态）；提交后移除+退出动画 | MessageCardAssistant.kt:132-165 | DSH部分（对位 W D7-03 composer 接管呈现；Android 锚定消息流——形态差异） |
| A-D7-09 | 会话内音频/振动反馈 | turn 完成/权限/提问/错误按通知渠道状态与响铃模式播放声音+振动（错误连发抑制 ErrorStreakTracker） | service/InSessionFeedbackPlayer.kt:21-138 | ➕独有 |
| A-D7-10 | 权限状态回显 | permission/preset、sandbox/mode、approval/policy 三 knob 投影回显（custom 态灰显） | DshEventMapper.kt:457-462；app-inventory §2.2 | DSH部分（对位 W D4-11 chip 会话级档位回显） |

## D8 设置与管理（27）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D8-01 | App 设置屏（8 分区） | 通用/外观/聊天显示/聊天行为/存储/高级/通知/权限自动批准；会话页底部导航第二页 | settings/SettingsScreen.kt:130-175；SessionListScreen.kt:264-305 | 本地（对位 W D8-01 壳，但管理对象为 app 自身偏好） |
| A-D8-02 | 语言切换（15 语言） | LanguagePickerDialog 选语言即时切换+持久化（英文源+14 翻译） | sections/GeneralSection.kt:37-44；res/values-*（15 目录） | ➕独有（对位 W D8-02 zh/en 🔒；Android 15 语本地持久不依赖服务器） |
| A-D8-03 | 主题三档+动态取色+AMOLED | 浅色/深色/跟随系统；Android 12+ Material You 动态取色开关；AMOLED 纯黑开关 | sections/AppearanceSection.kt:40-83；theme/AmoledCard.kt | ➕独有（对位 W D8-03 三档 🔒；动态取色+AMOLED 为超集） |
| A-D8-04 | 重连模式三档 | 激进/正常/保守（backoff 策略）选择对话框 | components/ReconnectModePickerDialog.kt:51-53 | ➕独有 |
| A-D8-05 | 初始消息条数设置 | MessageCountPickerDialog 选值（历史分页初载量） | sections/ChatBehaviorSection.kt:55-62 | 本地 |
| A-D8-06 | 最近目录数量设置 | 快速新建对话框展示的最近目录数 | ChatBehaviorSection.kt:65-73 | 本地 |
| A-D8-07 | 触感反馈开关 | 全局 haptic 开关 | ChatBehaviorSection.kt:93-107 | 本地 |
| A-D8-08 | 屏幕常亮开关 | 聊天屏 FLAG_KEEP_SCREEN_ON 动态加/清 | ChatBehaviorSection.kt:110-124；ChatScreen.kt:450-456 | 本地 |
| A-D8-09 | 图片压缩设置（三件套） | 压缩开关+长边上限+WebP 质量（联动 A-D3-03） | ChatBehaviorSection.kt:127-163 | 本地 |
| A-D8-10 | 终端字号设置 | 字号选择对话框（联动终端双指缩放持久化） | ChatBehaviorSection.kt:165-174 | 本地 |
| A-D8-11 | 聊天密度（字体） | 标准/紧凑二档 | sections/ChatDisplaySection.kt:42-54 | 本地 |
| A-D8-12 | 自动展开工具结果开关 | ChatDisplaySection | ChatDisplaySection.kt:57-71 | 本地 |
| A-D8-13 | 默认展开推理开关 | ChatDisplaySection | ChatDisplaySection.kt:74-88 | 本地 |
| A-D8-14 | 轮次分隔线开关 | ChatDisplaySection | ChatDisplaySection.kt:91-105 | 本地 |
| A-D8-15 | 通知开关+静默通知 | 总开关+静默（无声）通知开关（分渠道 TASKS/TASKS_SILENT/PERMISSIONS/QUESTIONS） | sections/NotificationsSection.kt:40-71；service/NotificationChannels.kt | ➕独有 |
| A-D8-16 | 通知自检（真实投递测试） | 发真实测试通知验证厂商（MIUI）悬浮通知可达性 | NotificationsSection.kt:75-83；AppNotificationManager.kt:244-259 | ➕独有 |
| A-D8-17 | 直达系统通知设置 | 跳系统 App 通知渠道设置页 | NotificationsSection.kt:86-99 | ➕独有 |
| A-D8-18 | 存储占用统计 | 冷存桶统计：桶数/消息数/字节人性化格式 | sections/StorageSection.kt:28-75（#271） | 本地 |
| A-D8-19 | 存储清理+二次确认 | 手动清理冷存桶→确认框→snackbar | StorageSection.kt:78-88；components/ClearArchiveConfirmDialog.kt | 本地 |
| A-D8-20 | Diagnostics 日志屏 | AppLogger 日志：级别过滤/文本搜索/复制/清空/分享导出文件 | settings/DiagnosticsScreen.kt:105-267 | ➕独有（应用内观测面） |
| A-D8-21 | GitHub 错误上报 | 设备码 OAuth 授权→日志预览→提交 issue→失败重试（未配置提示） | DiagnosticsScreen.kt:395-469 | ➕独有 |
| A-D8-22 | 服务器设置 hub | ServerCard 齿轮→Providers/Models 两入口（OpenCode 后端） | server/ServerSettingsScreen.kt:29-65 | ➕独有 |
| A-D8-23 | Provider 管理（OAuth+API key） | 连接 provider：浏览器/headless OAuth 双路+手动 code 回填；API key 输入写入；启停开关；热门 provider 置顶（OpenCode 后端） | server/ServerProvidersScreen.kt:81-149 | DSH部分（对位 W D8-09/12 凭据写入+自定义 provider；对象=OpenCode 后端，DSH llm 域只读） |
| A-D8-24 | 模型清单过滤（per-model 开关） | 搜索+按 provider 分组逐模型启停 Switch（OpenCode 后端） | server/ServerModelFilterScreen.kt:51-162 | DSH部分（对位 W D8-10 provider 模型表编辑） |
| A-D8-25 | MCP 服务器开关 | MCP 列表（状态点色）+Switch 启停+预加载（OpenCode 后端） | sessions/components/McpServerRow.kt:17-62；ServerSettingsContent.kt:142 | ➕独有（Web 无 MCP 管理面） |
| A-D8-26 | 新会话默认权限档/默认预设两行 | settings.describe 读+settings.mutate 写（乐观并发）；档集动态解析（#283）；能力位门控 | sessions/ServerSettingsContent.kt:42-156；components/PermissionDefaultRow.kt、AgentPresetDefaultRow.kt | DSH部分（对位 W D8-05/06 🔒 特权行。⚠ 2026-09-02 活体勘误：settings.\* 属 PRIVILEGED_METHODS——LAN/Tailscale 连接 403、仅 adb reverse（Host=loopback）可达，登记 #298；Web 远程同样 403，可达性双端对等） |
| A-D8-27 | About 屏+应用内更新 | 版本/描述/非官方声明/GitHub·OpenCode 外链/License；更新检查→下载→APK 安装器→查看 release（Play 渠道隐藏） | about/AboutScreen.kt:60-194,206-361；components/UpdateInstallLauncher.kt:18-54 | ➕独有 |

## D9 全局壳与导航（12）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D9-01 | 多服务器主页 | 服务器卡片列表+连接状态；添加/设置/关于顶栏入口；空态引导 | home/HomeScreen.kt:47-267；components/EmptyServersView.kt | ➕独有（Web 单服务器单页壳） |
| A-D9-02 | 服务器添加/编辑对话框 | OpenCode/DSH SegmentedButton 类型切换（DSH 隐藏鉴权字段+换 URL 示例）；URL 校验规范化（补 scheme/去路径）；名称自动派生；自动连接开关 | home/components/ServerDialog.kt:39-291 | ➕独有 |
| A-D9-03 | 服务器卡片操作 | 进会话/连接/断连/取消连接中/编辑/删除/设置七动作；DSH 徽章 | components/ServerCard.kt:94,133-252 | ➕独有 |
| A-D9-04 | 连接前台服务+常驻通知 | 前台 service 常驻通知（点按深链/「断开全部」action） | service/AppNotificationManager.kt:157-216 | ➕独有 |
| A-D9-05 | 任务完成/权限/提问通知 | 渠道化通知+消息预览（首条 user 消息）+深链进会话；静默渠道分离 | AppNotificationManager.kt:279-303 | ➕独有（Web 无系统通知域） |
| A-D9-06 | 通知深链直达会话 | 通知点按→deep-link（replay=1 防冷启丢失）→导航对应会话 | MainActivity.kt:56-103 | ➕独有 |
| A-D9-07 | 断连感知横幅 | 三态条幅（连接中/已连接/断开重连中）常驻列表与聊天（#267）；双 WS 独立重连退避+状态聚合 | components/ServerLinkBanner.kt；service/DshWsEventClient（app-inventory §2） | DSH对齐（对位 W D9-11 断线自动重连；Android 可见状态横幅为超集形态） |
| A-D9-08 | 三协议自动探测适配 | OpenCode V1/V2/DSH 探测路由（DSH 跳过双探）；能力位矩阵裁剪 UI | ApiVersionDetector.kt:72；ServerCapabilities（app-inventory §3.3） | ➕独有 |
| A-D9-09 | WebView 兜底（远程 Web UI） | 内嵌 WebView 打开 OpenCode Web UI（deeplink 导航流） | webview/WebViewScreen.kt:29-31；navigation/NavGraph.kt:344 | ➕独有 |
| A-D9-10 | 会话页↔设置页双页 pager | 底部 NavigationBar 切换+横滑（page 限定入口显隐） | SessionListScreen.kt:264-313 | 本地 |
| A-D9-11 | 加载/空态/错误态体系 | SessionListLoading/Error/EmptyState 三态+聊天错误屏 | sessions/components/SessionListStates.kt | 本地 |
| A-D9-12 | 电池优化引导 | 首页 errorContainer 横幅+「修复」跳系统电池优化白名单 | home/components/BatteryOptimizationBanner.kt | 本地 |

## D10 手势与输入增强（11）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| A-D10-01 | 长按会话行→详情/菜单 | combinedClickable onLongClick（详情对话框+同步区+复制 id） | SessionRow.kt:108-110 | 本地 |
| A-D10-02 | 长按发送键切换 shell 模式 | 空闲态发送键长按 toggle shell/normal 模式+ShellModeHintBanner 提示 | input/SendStopButton.kt:84,143-144；ShellModeHintBanner.kt:33 | ➕独有 |
| A-D10-03 | 双 FAB 贴边滑动+展开溢出下移 | 两 FAB 沿屏缘垂直拖动停放（位移持久化、独立收界）；菜单高位展开整列平滑下移；点外收起 | chat/ChatFabMenu.kt:104-215,172-215 | ➕独有 |
| A-D10-04 | 滚动到底 FAB | 离底显示，贴边滑动同规格 | ChatFabMenu.kt:500-529 | 本地 |
| A-D10-05 | 终端双指缩放字号 | pinch 缩放阶梯化字号+设置持久化+resize 转发服务器 | terminal/TermuxTerminalHost.kt:44-48 | ➕独有 |
| A-D10-06 | 音量键虚拟 Ctrl/Fn | 终端内音量键映射修饰键进入 Termux 输入主路径 | TermuxTerminalHost.kt:24-26；ChatTerminalView.kt:276 | ➕独有 |
| A-D10-07 | 终端键盘覆盖层 | ESC(长按☰)/CTRL/ALT 粘滞锁存/箭头(含 application cursor)/HOME/END/PGUP/PGDN/TAB/`/`/`-|`/粘贴 | terminal/TerminalKeyboardOverlay.kt:59-84 | ➕独有 |
| A-D10-08 | 终端边缘滑动手势开抽屉 | 屏缘手势拉出会话终端抽屉（多 tab 列表） | terminal/TerminalDrawerEdgeGesture.kt:24；TerminalTabItem.kt:46 | ➕独有 |
| A-D10-09 | 远程终端多 tab | 新建/切换/重连/关闭 tab；断线态标记+重连钮（OpenCode PTY；DSH 能力位隐藏） | TerminalDelegate.kt:81-139；TerminalDrawerActionsRow.kt:28 | ➕独有（Web 无终端域） |
| A-D10-10 | 横滑 pager 切页 | 会话↔设置 HorizontalPager（fling 收敛 atMost(0)） | SessionListScreen.kt:307-313 | 本地 |
| A-D10-11 | IME 处理 | 聊天输入行 IME 跟随（窗口 inset）；终端 IME 切换与 TerminalView 接管；提问卡自定义文本输入 | input/ChatTextField.kt；TermuxTerminalHost.kt:10-14 | 本地 |

> 硬件键盘：未发现专门处理（无 Ctrl+Enter 发送等快捷键映射）；外接键盘走系统 IME 通用路径，不单列功能点。

---

## 总账

- **功能点总数：149**
- **四值分布**：DSH对齐 36 · DSH部分 29 · ➕独有 56 · 本地 28

| 域 | 数量（对齐/部分/独有/本地） | 域 | 数量（对齐/部分/独有/本地） |
|---|---|---|---|
| D1 会话管理 | 20（5/4/9/2） | D6 子代理 | 6（4/1/1/0） |
| D2 工作区组织 | 8（1/3/3/1） | D7 审批与问答 | 10（3/2/5/0） |
| D3 Composer 输入 | 18（4/6/6/2） | D8 设置与管理 | 27（1/2/11/13） |
| D4 命令与模式 | 15（7/4/4/0） | D9 全局壳与导航 | 12（1/0/8/3） |
| D5 会话内容渲染 | 22（10/7/2/3） | D10 手势与输入增强 | 11（0/0/7/4） |

### 与 Web 端（169 点）对账速览

- **Web 有而 Android 无/缺**（对账时从 Android 分母看不到的）：会话内容搜索（服务器侧，Android 用本地 FTS 等效补位 A-D1-02）、轨迹视图（台账/检查器/时间轴全缺）、@ 会话引用、`@"` 引号形态、Plan 模式域（chip/事件/plan-review 专卡）、消息反馈 👍/👎、deliverables 文件 chip 行、消息级分支、悬停卡、拖拽排序、workspace 重命名/删除/归档、jobs popover 秒表、Cordis 全域、Plugins/agentPreset 管理面（🔒 loopback 双端均不可达）、KaTeX、max-tokens 专卡+continue 钮、上下文注入折叠行、detail 面板（Web 自身不可用）。
- **Android 有而 Web 无**（56 条 ➕独有，见各域标记列）：多服务器+三协议、系统通知/前台服务/深链、QueueDock steer 三动作、草稿持久化、本地 FTS5 全文搜索+过滤+直达、标签/收藏、审批 always+自动批准规则+拒绝附理由、会话内音频反馈、FileViewer（diff/PDF/render/批注提交）、远程终端 PTY（多 tab/音量键/键盘层/缩放）、Diagnostics+GitHub 上报、应用内更新、15 语言、主题/AMOLED/动态取色、MCP 开关、provider OAuth、历史同步、图片压缩/保存/外部分享接收、快速导航、revert/分享/后台化（OpenCode 后端）等。

### FileViewer / 批注子系统（普查注记）

`ui/screens/viewer/`（17 文件）为 Android 独有整体能力，其用户可见面已折算计入 A-D2-04/05（diff）、A-D3-05（图片预览）、A-D4-15 引用链与工具卡 onOpenFile 打开链：文件查看器（代码高亮 Source/Render 双模/换行开关/DiffView/PDF），**文件批注**（划选文本加批注→列表管理→编辑/删除→汇总批注一键发送回 agent）为完整独有工作流（viewer/AnnotationManager.kt、AnnotationInputSheet.kt、AnnotationSubmitDialog 于 FileViewerScreen.kt:258-265、FileViewerScreen.kt:73-76,182-253；批注角标 Badge FileViewerScreen.kt:295-299）。若按 Web 普查粒度单独拆条，可在 D5 追加 +4 点（源/渲染双模、diff 视图、PDF、批注提交流），总数变为 163。
