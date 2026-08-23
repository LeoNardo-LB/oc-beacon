# A2 · CONTEXT.md 38 词条可执行性审计（裁决闭合前深调研）

> 作业：逐条审计 CONTEXT.md 定稿 38 词条能否无歧义指导 Phase 2 修订。仓库只读；本文件是唯一写入产物。
> 结论速览：**14 条可直接执行，24 条需细化**（含 2 条 Avoid 词无判别力、4 组词条互斥/撞名、CONTEXT×chinese-mapping 两处正面冲突），提炼 **15 个待裁决点**。

## §0 审计口径

- **仓库**：/home/leo-tkp/Documents/code/mine/oc-beacon-terminology（worktree，只读）。
- **计数引擎**：GNU grep -P 递归，按出现次数（-o）计；热点文件按命中行数排序取前 3-5。
- **区域**：M=app/src/{main,debug,stable}（.kt/.xml，含 res/values* 全部 15 语言）；T=app/src/{test,androidTest}；D=docs/ + maestro/ + scripts/ + 根级 md（README/CHANGELOG/AGENTS/backlog/RELEASE_NOTES）。**D 含历史产物**（journal/archive/research），重词的历史份额单列；CONTEXT.md 与 .scratch/ 排除（盘点产物不计入 Phase 2 目标）。
- **两步法假阳性防线**：① 裁决原则「仅改注释+文档+文案、不改标识符」（conflicts-master 头部）——凡 Avoid 词命中标识符（类名/键名/枚举/包名）一律列为保留例外；② 「Avoid」指**名称性使用**——canonical 词条定义句内的描述性用词（如堆积消息定义里的"本地暂存"）不算违规。此防线目前只存在于台账，**未写入 CONTEXT.md**（见待裁决点 15）。

## §1 逐词条审计表（E1–E38 按 CONTEXT.md 顺序）

### 1. 会话与消息（E1–E10）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E1 会话 session | 对话(非对话框) M26/T2/D109；conversation M18/T18/D14；chat(词) M549/T108/D284 | 对话：docs/archive/specs/2026-08-04-markdown-table-wrap-design.md:6、journal/2026-08-21-arch-review-deepening.md:5、MessageDataDelegate.kt:5、values-zh(chat_empty 等)；conversation：ChatViewModelQueuedTest.kt:11、ChatMessageList.kt:9；chat：ChatScreen.kt:64 | 「对话框」(dialog) 全豁免；状态簇属性 `viewModel.conversation`（标识符）；包名 ui.screens.chat（135 文件）与屏名 Chat/ChatScreen；dialogue-e2e-* 文档的"对话"为流程叙述域 | 文案/注释中指 session 的「对话」→「会话」；chat/conversation 仅文档叙述性英文改 session，标识符与屏名不动 | **是**（zh 键逐键裁决清单缺失；dialogue-e2e 两文档全量「对话」未定改法） |
| E2 消息 message | prompt M196/T121/D220 | opencode-api-reference-v1.md:62、V2ApiClient.kt:31、PendingMessagePipelineTest.kt:16 | PromptPart/promptMessage/PendingMessage 等标识符；/prompt 端点、promptAsync（请求侧概念合法） | 仅"把请求侧 prompt 当消息讲"的注释/文案改「消息」，请求侧一律保留 prompt | **是**（语义违规无法 grep，需人工清单） |
| E3 内容块 part | 零件 M1/T0/D0；part 裸用：不可 grep | MessageDataDelegate.kt:1 | part 作为 API 原词引用（「API 原词 part」本身是词条定义） | 「零件」→「内容块」（仅 1 处）；中文句子里裸用 part 处改「内容块」 | **是**（"中文语境裸用"无判定标准） |
| E4 提示块 PromptPart | （无可 grep Avoid） | V2ApiClient.kt（/prompt 域） | PromptPart 标识符 | 注释区分「提示块（请求体）/内容块（消息体）」，混称点人工审 | **是**（无机械判据） |
| E5 轮次 turn | 任务 M105/T30/D96；任务完成 M9；回合 M2/T0/D1 | 任务：values-zh(18 行)、TaskDelegate.kt:14、api-reference:13；回合：values-zh:331-332(回合分割线)、CHANGELOG:86 | 工具任务/todo 域「任务」全合法（chat_task_completed、Tasks 标签 task_sheet、subtask=子任务）；标识符 Task* | 仅 turn/通知语境「任务」→「turn」；「回合分割线」→「轮次分割线」（改值不改键名） | **是**（通知频道「任务通知/任务完成」改法+EN "Task completed" 同步，见裁决点 4） |
| E6 流式 turn | 流式消息 M5/T9/D10 | ChatScrollStabilityTest.kt:8、ChatMessageList.kt:3、AGENTS.md SSE 铁律 2 处（词条已点名 106,110）、audit-2026-08-10/* | isStreamingMsg 标识符 | 注释/文档「流式消息」→「流式 turn」；AGENTS.md 两处列入修订清单 | 否（热点集中、无歧义） |
| E7 合成消息 synthetic | 系统通知 M16/T0/D20；后台消息 M1；后台通知 M4/T0/D3；转后台提示 M2/T0/D1 | 系统通知：audio-feedback spec:6、values-zh(:344,345,350 系统通知设置)、arch-review:5；转后台提示：ChatMessageList.kt:2、values-zh task_toolbar_action=任务转后台 | **「系统通知设置」=Android OS 设置义（zh 3 键）不得误伤**；后台 shell/后台会话（background session）的"后台"叙述 | synthetic 语境「系统通知/后台通知/转后台提示」→「合成通知」；OS 设置与后台 shell 语境豁免 | **是**（zh 键新文案候选：Sub-agent 完成通知×3 等，见裁决点 5） |
| E8 子智能体 subagent | sub-agent M52/T0/D3；子代理 M20/T4/D39；子会话 M84/T12/D36 | sub-agent：13 个非 zh locale×8 + values/strings.xml:6 + values-zh:208,217,218；子代理：values-zh:205,274,699,701、audit REPORT:14；子会话：TaskDelegate.kt:9、TaskToolCard:5、ChatScreen:5、OpenCodeConnectionService.kt:543、values-zh chat_task_output_truncated | jobId/childID 字段拼写（词条内建）；chinese-mapping share 行自身使用「子会话」（正面冲突） | EN 源 "sub-agent"→"subagent"（6 键）+13 locale 同步；zh「子代理」→「子智能体」；「子会话」先裁决代称再改 | **是**（84 处「子会话」+mapping 自冲突，见裁决点 3） |
| E9 智能体 agent | Assistant M157/T186/D49；代理(净) ≈M7/D53 | Assistant：MessageEventHandler.kt:29(role 解析)、ChatViewModelQueuedTest:21、AppNotificationManager.kt:18、values/strings.xml:179,593；代理：values-zh:312,487,554,580,699、api-reference:9 | **assistant 是 API role 值**（JSON 解析/SerializationTest/role 显示键 chat_role_assistant 保留）；「代理服务器」proxy 义 | EN 显示词 chat_label_agent="Assistant"→"Agent"；zh「代理(=agent)」→「智能体」；role 值 assistant 全保留 | **是**（chat_role_assistant 改不改+EN/zh 显示词对齐，见裁决点 5/10） |
| E10 会话细粒度事件 session.next.* | 下一代事件 0；Session Next(半英半中) 0 | —（Avoid 词零命中） | SessionNextEvent/SessionNextEventHandler 等标识符 M51/T46/D124 全保留 | 注释首现处用「会话细粒度事件（session.next.*）」 | 否（干净） |

### 2. 队列与待处理（E11–E12）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E11 堆积消息 pending message | 待发消息 0（待发 M11 另义）；未发消息 0；暂存消息 M2；排队消息 M8；STACKED M43/T0/D2；QUEUED M75/T46/D22 | 排队消息：values-zh:122,127,128,132（7 处）、ChatViewModelQueuedTest:1；暂存消息：PendingMessage.kt、PendingMessageEntity.kt KDoc；STACKED：ChatFabMenu.kt:6(枚举 ChatToolbarEntry.STACKED)、PendingSheets.kt:4、13 locale pending_tab_stacked 键；QUEUED：MessageCardUser.kt:17（注释已对齐）、values:584 chat_queued、ChatViewModelQueuedTest:28 | **QUEUED 徽章（chat_queued 键与 EN 值，词条已裁保留）**；EN 值 "Queued"（pending_sheet_title/pending_tab_stacked*）；枚举 STACKED；ServerTerminalWorkspace「待发送 resize」（终端防抖另义 M11）；FileViewerViewModel「暂存」（本地暂存另义 M4） | zh 用户可见文案「排队消息」→「堆积消息」（改值不改键名/EN 值）；KDoc「暂存消息」→「堆积消息」 | **是**（15 locale "Queued" 译名是否统一改「堆积」，见裁决点 1；canonical KDoc 定义句内"本地暂存"描述性用词的豁免边界） |
| E12 待处理权限/问题 | 待答 M4/T2/D6；堆积 M95/T19/D59（99% 为 E11 canonical 用法） | 待答：PendingMessagePipeline.kt:2、queue-drain spec:2、journal×2 | 「堆积」在堆积消息语境全合法（与 E11 互斥咬合） | 「待答」→「待处理」（权限/问题语境）；「堆积」禁令仅限权限/问题语境——现仓无违例，纯防御 | 否（干净、防御性） |

### 3. 服务器交互动词（E13–E18）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E13 中断 interrupt | abort M78/T46/D59；中止 M7/T1/D7 | abort：SessionActionsDelegate.kt:9、ChatViewModel.kt:8、api-reference:9；中止：ChatViewModel.kt:594,842-846（abortSession 本地编排注释）、api-reference:3 | V1 /abort 端点名（历史对照）；**本地 abortSession() 标识符**；**chinese-mapping 行54 把"本地 abort"定名「中止」——与本项目 Avoid「中止」正面冲突**；SendStopButton 文案已用「中断」 | 协议动作统一「中断」——但本地中止/协议中断的边界两文档打架，须先裁决再改（abortSession 注释现状按 mapping 写「中止」） | **是**（高优：CONTEXT×mapping 冲突+V2 下 abortSession 实际委托 interrupt 端点，见裁决点 2） |
| E14 重命名 rename | update M1182/T645/D397 | MessageEventHandler.kt:75、UpdateRepository.kt:66、api-reference:62 | **绝大多数 update 合法**（UI/DB/依赖更新）；仅 V1 语义对照处是目标 | 仅"把 rename 讲成 update"的历史对照注释改「重命名」 | **是**（Avoid 词无判别力，建议改写为「V1 update 端点对照语境」限定） |
| E15 压缩 compact | summarize M24/T1/D9；压缩摘要 M2/T1/D2；上下文压缩 M2/T0/D2；摘要 M32/T3/D59 | summarize：SessionApi.kt:4(V1 端点标识符)、V1ApiClient:2、api-reference:3；压缩摘要：**Part.kt:209**、V2EventParserTest:1；上下文压缩：CompactionBanner.kt:1、SessionFSMState.kt:1；另 values-zh chat_compressing_context=「正在压缩上下文」为语序变体 | V1 /summarize 端点名（历史对照）；「摘要」合法义（报告/发版摘要，D59 大部）；EN 键名 chat_summarized | compaction 语境统一「压缩」前缀；Part.kt:209「压缩摘要全文」→「压缩全文/压缩内容」；「上下文压缩/压缩上下文」语序变体需明确是否纳入 Avoid | **是**（语序变体未列 Avoid；摘要合法/违规边界，见裁决点 9） |
| E16 凭据 credential | auth M852/T108/D428 | V1ApiClient.kt:92、V2ApiClient.kt:89、api-reference:122 | Authorization 头/鉴权流程全合法；credential M13/凭据 M13 已并行使用 | 仅"凭据管理叫 auth"的叙述改「凭据」；头字段/鉴权语境豁免 | **是**（同 E14：Avoid 词无判别力，需限定「V1 /auth 端点对照」） |
| E17 撤销/取消撤销 revert/unrevert | 回退 M98/T20/D74；undo M99/T33/D3；redo M98/T37/D5 | 回退：ApiVersionDetectorTest.kt:6(**fallback 义**)、ChatRepository.kt:5、MessageEventHandler.kt:5(revert 义)、release-workflow:4(签名回退)；undo：values /undo 命令×8+13 locale、SessionActionsDelegate.kt:8；redo：**JumpNavigationControllerTest.kt:9、ChatViewModel.kt:6、ChatScreenBottomBar.kt:5（跳转历史 undo/redo——本地另一概念）** | /undo /redo 斜杠命令（词条明示保留）；**fallback/降级义「回退」**（探测回退、签名回退）；**跳转历史 undo/redo**（词条未覆盖的概念） | revert 语义「回退」→「撤销」；fallback 义与跳转历史 undo/redo 豁免并需定名 | **是**（fallback/revert 两义分拣清单+跳转 undo/redo 定名缺失，见裁决点 8） |
| E18 答复 reply | effect(权限义) 实际 2 处 | V2ApiClient.kt:855(注释)+877(put("effect")) | legacy JSON 字段 "effect" 是线上契约**必须保留**；LaunchedEffect 等 Compose API 无关 | 注释解释 once/always/reject=一次/总是/拒绝，字段名不动 | 否（干净、现仓已合规） |

### 4. 游标与分页（E19–E21）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E19 分页游标 cursor | 裸「游标」(非限定) M130/T84/D98；cursor 标识符 M517/T456/D196 | MessagePaginationDelegateTest.kt:47、MessagePaginationUseCase.kt:25、MessagePaginationDelegate.kt:23、PaginationFSM.kt:21 | Kotlin 标识符 cursor/Cursor*；分页语境内首现后的简称（现状惯例） | 首现「分页游标」+同段简称「游标」是否放行未定——缺简称豁免规则 | **是**（无简称豁免则 300+ 处误报，见裁决点 6） |
| E20 Shell 输出游标 | （无 Avoid 行） | 概念=字节偏移 Long；「输出游标」中文 0 处 | — | Phase 2 在相关注释首现处引入「Shell 输出游标」限定名 | 否 |
| E21 会话列表游标 | （无 Avoid 行） | SessionApi.kt:19,118-122 cursor 参数 | 标识符 cursor | 注释引入「会话列表游标」限定名；注意与 E19 同形裸「游标」靠语境区分 | 否（但依赖裁决点 6 的简称规则） |
### 5. 展示与渲染（E22–E24）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E22 渲染供给 Render Supply | 预解析驱动器 0；分片协调器 0；chunk coordinator 0；preparse M50/T4/D6 | preparse：RenderSupplyCoordinator.kt:17、ChatMessageList.kt:9、MarkdownContent.kt:6 | **preparse/chunk 作为机制名合法**（词条定义：它们是机制不是概念）；标识符 preparseSeenKeys 等 | 概念统称「渲染供给」（现仓 RenderSupplyCoordinator.kt:14 KDoc 已合规）；Avoid 名零命中 | 否 |
| E23 跳转稳定窗口 Jump Settling Window | 跳转锁 0；jump lock 0；滚动锚定锁 D2；canonical「跳转稳定窗口」0 处 | jumpLock M24/T17/D14：JumpNavigationController.kt:12、ChatMessageList.kt:10、JumpLockDerivationTest:17；滚动锚定锁：README.md:1、audit metrics xml:1；**KDoc 残留：JumpNavigationController.kt:26「稳定窗口 1.5s」** | **jumpLock/jumpLockActive 标识符=autoLoad 抑制另一概念**（词条明示）；300ms/900ms 数值 | 文档叙述 2s 冻结期用「跳转稳定窗口」；README「滚动锚定锁」改；KDoc:26 的 1.5s 失实值修正 | **是**（①canonical 名 0 落地且 chinese-mapping G9 仍标 ⏳ 两文档状态不同步 ②1.5s 残留到底指 2s 窗口还是 900ms 滚动稳定的旧值 ③2s 常量代码锚点未定位——SETTLING/2_000/2000L 在两文件均未命中，见裁决点 7） |
| E24 状态簇 State Cluster | 内容册 M3/D4；外壳册 M2/D2；集群 M12/T0/D3 | 内容册/外壳册：SessionListViewModel.kt、SessionListUiState.kt、SessionListStateBuilder.kt 各 1+archive spec；集群：SessionLifecycleDelegate.kt:5、MessagePaginationDelegate.kt:3、DraftInputDelegate.kt:2；canonical「状态簇」M1 | docs/archive 历史 spec 豁免待定 | 会话列表注释「内容册/外壳册」→「状态簇（会话列表侧）」；「集群」→「簇」 | **是**（「集群」12 处是否全指状态簇未逐条核） |

### 6. 时间与未读（E25）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E25 红点时钟域 | "消息时间戳都能用" 0 命中 | — | — | 防御性设计条款，无替换动作 | 否 |

### 7. 会话状态机（E26–E27）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E26 必需协作者 Required Collaborator | "回调旋钮" M0/T1/D1 | StubCollaborator.kt:1（测试注释自引） | StubCollaborator 标识符 | 定性词条；测试注释 1 处顺带核对 | 否 |
| E27 僵尸检测 | 陈旧检测(词组) 2 处；陈旧 M25/T5/D16；stale M40/T21/D38 | 陈旧：SessionStateService.kt:10（staleness 机制描述）；僵尸 M32/T11/D30 canonical 并存 | **「陈旧」作机制形容词/机制名 stale 合法**（词条明示并存）；checkStaleness 标识符 | 仅名称性「陈旧检测」→「僵尸检测」（2 处），形容词用法全豁免 | 边缘（基本可执行；2 处词组位置需人工定位） |

### 8. 连接与版本（E28–E29）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E28 版本 seam Version Seam | isV2 M86/T1/D13；api/ 门外仅 6 处 | api/ 门面内：SessionApi.kt:23、ProviderApi.kt:13、MessageApi.kt:12、FileApi.kt:12（收口合规）；门外 6 处：SseConnectionManager.kt:324、ServerCard.kt:101、PaginationCursorPolicy.kt:17,91、ApiVersion.kt:21、MessagePaginationUseCase.kt:65(注释) | 门外 6 处全部落在词条自带的例外清单内（连接对象读取/ServerCard 版本徽章展示豁免/游标策略收口模块/枚举定义） | 判定只留 api/ 门面+连接对象+版本徽章；新增 isV2 判定需走 seam | 否（词条自带例外，现状合规——是 38 条中可执行性范本） |
| E29 连接生命周期协调 | "Service 管连接" 0 命中 | — | — | 防御性条款 | 否 |

### 9. 文件与工作区（E30–E33）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E30 工作树 worktree | 工作区 M16/T0/D203 | 工作区 D 热点：opencode-api-reference-v1.md:87 行、opencode-api-deep-research/3-file-project.md:29、4-terminal-control.md:23（其中与 workspace 同行 37 行、与 worktree 同行 0 行）；工作树 M0/T1/D20；worktree -i M47/T26/D161 | workspace 义「工作区」=E31 canonical（大 maioria）；标识符 worktree | 仅 worktree 义「工作区」→「工作树」——语境判定，无机械规则 | **是**（203 处 D 需语义分拣；建议 heuristics：同行有 workspace/workspace 义保留，上下文讲检出/项目视图的改工作树） |
| E31 工作区 workspace | 工作空间 M1/T0/D17；工作区浏览器 D1 | 工作空间：values-zh:302 chat_menu_open_workspace=查看工作空间、archive file-viewer spec:17；工作区浏览器：README.md:1 | docs/archive 历史 spec 豁免待定 | zh 键值「工作空间」→「工作区」（1 处活目标）；README「工作区浏览器」→「目录浏览」 | 否（量小清晰） |
| E32 目录 directory | 文件夹 M18/T0/D30；folder M250/T1/D10 | 文件夹：values-zh:6 行、WorkspaceScreen.kt:3、e2e-testing-workflow.md:3；folder：OpenProjectDialog.kt:42（FolderNode 等标识符）、13 locale 键值×9 | **folder 标识符**（FolderNode/folder 参数）；EN 键名 pending 等 | 用户可见文案「文件夹」→「目录」（改值不改键名）；folder 标识符不动 | **是**（EN 源文案值 "folder" 是否改 "directory" 并 14 语言同步，见裁决点 10） |
| E33 目录视图 catalog | 裸「目录」与 E32 canonical 同形，不可 grep 机械判定；目录视图 M2 | 目录 D240 热点：api-reference:55 行（与 catalog 同行仅 1、与 directory 同行 13）；ProvidersResponse.kt:2 已用 canonical；catalog -i M78/T29/D17 标识符 | directory 义「目录」全合法；ProviderCatalog/ModelCatalog 标识符 | provider/model 列表义「目录」→「目录视图」，其余保留 | **是**（与 E32 撞名的语境判定，建议并入同一分拣清单） |

### 10. 标注与备注（E34–E35）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E34 标注 annotation | 备注 M19/T5/D34（**大部分是 E35 canonical 用法**） | 备注：AnnotationPromptBuilder.kt:14（E35 语境合法）、dialogue-e2e-runbook:12；标注 M11/T2/D139（zh 6 键已 canonical；archive spec:77） | **E35 canonical「备注」**（总体备注/文件备注/具体备注）；zh copy 已用「标注」 | 仅"把 annotation 选区叫备注"的用法改「标注」——现仓基本干净 | 边缘（与 E35 互咬需双向语境判定；C06 在 mapping 仍 ⏳ 与 CONTEXT 定稿不同步） |
| E35 备注 note | 批注说明 0；修改说明 M2；整体说明 0 | 修改说明：values-zh:611 annotation_input_hint、AnnotationInputSheet.kt:1 | — | 「修改说明」→「备注」（输入提示文案 1 键+注释 1 处） | 否（量小；具体 hint 措辞随裁决点 5 一并定） |

### 11. 归档两义（E36–E37）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E36 会话归档 archive | （无 Avoid 行——互补依赖 E37 禁令） | — | — | 会话操作义「归档」合法使用，须带「会话」限定 | 否 |
| E37 冷存桶 archive bucket | 归档桶 M13/T2/D4；裸「归档」大存量（归档全体 M88/T25/D108）；冷存桶 M0（canonical 未落地） | 归档桶：PaginationCursor.kt:2、MessageEventHandler.kt:2、regression-guide:1；裸归档存储义：MessagePaginationUseCase.kt:15、MessageStore.kt:15；archive_buckets/ArchiveBucketDao 标识符 M47/T31 | **会话归档操作义（E36）**；标识符 archive_buckets/ArchiveBucketDao/MigrationTest；journal 历史 78 处 | 存储层「归档桶/归档」→「冷存桶/冷存」；会话操作义保留「归档」 | **是**（两义分拣：MessageStore/PaginationUseCase 注释 30 处需人工判定义） |

### 12. 通知（E38）

| 词条 | Avoid 词全仓计数 | 热点文件 | 保留例外 | 替换规则（一句话） | 需细化? |
|---|---|---|---|---|---|
| E38 turn 完成通知 | 任务完成通知 M4/T1/D5；任务完成 M9；TaskComplete M5/T0/D1 | 任务完成：values-zh:353,355(频道描述)、:312(代理完成任务时通知)、README:1；TaskComplete：SessionFocusHolder.kt:16,44、AppNotificationManager.kt:272,571（KDoc 口语）、OpenCodeConnectionService.kt:593 | **标识符 showTaskCompleteNotification 与 FeedbackType.TURN_COMPLETE**；**chat_task_completed=任务已完成（工具任务义，不得误伤）** | 注释「TaskComplete 通知」→「turn 完成通知」；zh 频道文案「任务完成」→「turn 完成」（OpenCodeConnectionService.kt:543 注释已示范「turn 完成」写法） | **是**（频道名「任务通知」(notification_channel_tasks) 改法+EN "Task completed" 同步，见裁决点 4） |

## §2 词条互斥性检查（Avoid/规范名互含）

| 组 | 关系 | 实仓证据 | 判定 |
|---|---|---|---|
| E12 × E11 | E12 Avoid「堆积」=E11 规范名词根 | 堆积 M95 中 99% 是 E11 canonical（含 PendingMessagePipeline/queue-drain spec）；zh 注释「堆积/TODO 面板」为 E11 简称 | **意图性互斥**（防挪用），可执行但须限定"E12 禁令仅权限/问题语境"，否则按字面会错杀 E11 全部用法 |
| E30 × E31 | E30 Avoid「工作区」=E31 规范名 | 工作区 D203，api-reference 87 行中 37 行与 workspace 同行、0 行与 worktree 同行 | 意图性互斥；**无语境规则不可执行**，需 heuristics（见 E30 行） |
| E32 × E33 | E33 Avoid「裸称目录」=E32 规范名 | 目录 D240；catalog 同行仅 1 行 | 同上，靠语义分拣 |
| E34 × E35 | E34 Avoid「备注」=E35 规范名 | AnnotationPromptBuilder.kt:14 备注全是 E35 合法用法 | 双向语境判定；C06 在 mapping 仍 ⏳，两文档状态不同步 |
| E36 × E37 | E37 Avoid「裸称归档」=E36 规范名 | 归档 M88：会话操作义（合法）与存储义（→冷存）混杂 | 需两义分拣清单（MessageStore/PaginationUseCase 为主要分拣场） |
| **E13 × chinese-mapping 行53/54** | CONTEXT 禁「中止」，mapping 把"本地 abort"定名「中止」 | ChatViewModel.kt:594,842-846 注释现状按 mapping 写「中止」；abortSession 实际经 sessionRepository.abort 委托（V2 下通 interrupt 端点） | **正面冲突**，Phase 2 无法同时满足两文档（裁决点 2） |
| **E8 × chinese-mapping 行60** | CONTEXT Avoid「子会话」，mapping share 词条自身使用「子会话」限定词 | 子会话 M84 + OpenCodeConnectionService.kt:543 注释 + zh chat_task_output_truncated | **正面冲突**：要么给「子会话」发限定豁免，要么改 mapping（裁决点 3） |
| E1 × 通用词 | Avoid「对话」⊂「对话框」 | values-zh:315,672「对话框」=dialog 义 | 已用 (?!框) 排除，规则须写明 |
| E5 × 工具域 | Avoid「任务」与 tool/todo 域「任务」同形 | values-zh 18 行任务中过半是 tool/todo 义（chat_task_completed、task_sheet_title、Tasks 标签） | 须限定"turn/通知语境"（词条已部分自带：'任务（通知文案旧称）'，但工具域豁免未写明） |

## §3 中英混排规范对齐（chinese-mapping.md × CONTEXT.md）

1. **ID 拼写约定不可 grep 验证**：规则"注释引用 API 字段用 API 原拼写、域内 camelCase"，但实仓 sessionId 3069 vs sessionID 350、messageId 736/messageID 131、partId 291/partID 44、callId 215/callID 37——绝大多数 camelCase 命中是**Kotlin 属性标识符**（DTO 经 @SerialName 映射，属性名本就 camelCase，合规）。规则缺"仅约束注释/KDoc 中的字段引用"限定，按字面会全仓误报。childID 14 vs childId 5：childId 5 处均为 handler 局部变量（QuestionEventHandler/PermissionEventHandler 解构），属域变量合规，但需明确 childID 条款只管字段名与注释引用。
2. **zh 文案保留英文词无通则**：已裁「turn 完成」保留英文 turn（E5/E38），但 E9「UI 显示词统一 agent/智能体」与 zh 键 "Agent 完成"(chat_background_agent_completed)、"Sub-agent 完成通知"(:208,217,218)、"Shell 完成" 并存——zh 文案何时保留英文（Agent/Shell/turn）何时用中文（智能体）缺一条总则，15 语言无法机械执行。
3. **EN 源显示词未入裁决**：values/strings.xml chat_label_agent="Assistant"(:179)、sub-agent 6 键(:173-282)、"Task completed"(:174)、folder 系、Queued 系——EN 源是否随 zh 规范名同步改动（进而 14 语言翻译+CI 检查）没有任何词条表态（G7 只裁了 Reject）。
4. **两文档状态不同步**：CONTEXT 已定而 mapping 仍 ⏳ 的有 9 项（part/turn/worktree/workspace/catalog/C06 标注备注/C92 session.next/G9 三窗口/QUEUED 徽章/redo 别名）——mapping §6「剩余待裁决项」需要刷新，否则第二批拷问会重复提问；反向：mapping 列 ⏳ 且 **CONTEXT 完全没有词条**的有 7 项：**provider=提供商（M25 事实标准）、inbox=收件箱（0 使用）、tokens=令牌（M20）、ordinal=序数（ordinal M41/序数 M1）、C80 thinking（M51）、C63 编号行话（RS-0xx/L2-L4）、G8/C76 collapseTools**——38 词表存在覆盖缺口。
5. **「仅改注释+文档不改标识符」原则未入 CONTEXT**：STACKED、jumpLock、SessionNextEvent、showTaskCompleteNotification、chat_queued 键名等 Avoid 词命中标识符的场景全靠台账原则兜底，建议写入 CONTEXT.md 总则（否则新会话按词条字面执行会去改标识符）。

## 需细化词条清单（24/38）

**A. Avoid 词无判别力（2）**：E14 rename（update）、E16 credential（auth）——建议改写为"V1 端点对照语境"限定。
**B. 语境分拣型（11）**：E1（对话 zh 键清单）、E2（prompt 语义违规人工清单）、E3（part 裸用标准）、E5（任务两域+频道名）、E7（系统通知设置豁免+新文案）、E9（Assistant role/显示词拆分）、E11（Queued/堆积 locale 策略）、E15（压缩上下文语序变体）、E17（回退两义+跳转 undo/redo）、E30/E33（工作区/目录语义分拣）、E37（归档两义分拣）。
**C. 文档间冲突（2）**：E13（中止×mapping 本地 abort）、E8（子会话×mapping share）。
**D. 词条自身待锚定（5）**：E4（无判据）、E19（游标简称豁免）、E23（1.5s 残留归属+2s 常量锚点+G9 状态）、E24（集群 12 处核验）、E38（频道名+EN 同步）。
**E. 边缘（4）**：E27（2 处词组定位）、E34（C06 状态同步）。
**可直接执行（14）**：E6、E10、E12、E18、E20、E21、E22、E25、E26、E28、E29、E31、E35、E36。

## 待裁决点（15）

1. **QUEUED/排队 locale 策略**（E11）：zh「排队消息」4 键必改「堆积」；EN 值 "Queued"（pending_sheet_title/pending_tab_stacked*）与其余 13 locale 译名是否同改？候选：A 仅 zh 改（EN 徽章用户视角保留）/ B 全 locale 统一「堆积」/ C EN 与 zh 均保留用户视角词。
2. **中断/中止双轨 vs 单轨**（E13×mapping）：abortSession() 本地编排注释现写「中止」，V2 下它委托协议 interrupt。候选：A 接受双轨（协议=中断、本地=中止），修改 CONTEXT 措辞解除禁令 / B 全部统一「中断」，废除 mapping 行54 / C 本地动作另定名（如「停止」）。
3. **「子会话」处置**（E8×mapping）：84 处 M 命中。候选：A 新代称「子智能体会话」全量替换 / B 「子会话」转正为限定使用（改 CONTEXT，mapping share 行保留）/ C 逐处改写句子。
4. **turn 完成通知的频道面**（E5/E38）：notification_channel_tasks「任务通知/任务完成时通知」+ settings_notifications_desc「代理完成任务时通知」。候选：A 频道名+描述全 turn 化（含 EN "Task completed"）/ B 仅描述改、频道名不动（系统设置里频道名变更会显示为新频道）/ C 连 chat_task_completed（工具义）一起改——不建议。
5. **synthetic/agent zh 键新文案包**（E7/E9）：chat_system_notification/chat_subagent_*_notification（现值 "Sub-agent 完成通知"）→ 候选「合成通知（子智能体完成）」等；chat_role_assistant="Assistant" 是否改；「Agent 完成/Shell 完成」保留英文还是中文化。
6. **裸「游标」简称豁免**（E19-21）：候选：A 首现限定+同文件简称放行 / B 全限定（300+ 处全改）。
7. **三窗口数值锚定**（E23）：JumpNavigationController.kt:26 「稳定窗口 1.5s」指哪个窗口的旧值？2s 冻结常量在哪个文件（JumpNavigationController/ChatMessageList 未搜到 SETTLING/2000）？G9 在 mapping 仍 ⏳ 需同步定稿。
8. **回退两义+跳转 undo/redo**（E17）：fallback 义「回退」豁免清单（ApiVersionDetectorTest/release-workflow/ChatRepository 部分）；JumpNavigationController 的 undo/redo 是本地跳转历史概念，CONTEXT 无词条——定名候选：「跳转回退/跳转重做」或并入 E17 豁免。
9. **压缩 Avoid 边界**（E15）：「压缩上下文」（zh chat_compressing_context 值，语序变体）是否纳入 Avoid；「摘要」合法边界（报告摘要 vs 压缩产物摘要）；Part.kt:209「压缩摘要全文」改「压缩全文」还是「压缩内容」。
10. **EN 源显示词同步**（E1/E9/E32/E38）：Assistant→Agent、sub-agent→subagent（6 键）、folder→directory、"Task completed"→"Turn completed" 是否改 EN 源并触发 14 语言翻译+i18n CI（成本项）。
11. **历史产物豁免范围**：docs/journal+archive+research 与 docs/archive/specs、CHANGELOG 历史条目是否豁免 Phase 2（对话 59/96、回退 56/72、归档 78/105、任务 44/87 的 D 命中在此；CHANGELOG:86「回合分割线」改不改）。
12. **dialogue-e2e 两文档**（E1）：docs/dialogue-e2e-test-plan/runbook 的「对话」是流程叙述域（对话全生命周期）——整册改「会话」还是作为 UX 叙述域豁免。
13. **CONTEXT 覆盖缺口**（§3.4）：provider/inbox/tokens/ordinal/thinking/C63/G8 七项是否补入词表（提供商 M25 已事实标准，收件箱 0 使用）。
14. **mapping §6 刷新**：9 项已由 CONTEXT 定稿的 ⏳ 标记是否当场闭环，避免第二批拷问重复提问。
15. **总则补写**：「Avoid 仅指名称性使用+标识符/键名豁免+canonical 定义句内描述性用词豁免」是否写入 CONTEXT.md 首段（本审计 §0 的两道防线成文化）。

---
*方法备忘：计数=M/T/D 出现次数（GNU grep -P -o），D 含 journal/archive/research 历史产物；热点=命中行数 Top；所有键名/类名/枚举/包名均按「仅改注释+文档不改标识符」原则列为保留例外。*
