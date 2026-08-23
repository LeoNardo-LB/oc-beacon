# 术语冲突主台账（Phase 1 汇总 → Phase 2 裁决底稿）

> 状态：**汇总中**（01/02/04 已并入；03/05/06/07/08 待并入）。来源：8 路盘点。
> 裁决原则（用户已定）：规范名以 **OpenCode API 术语为权威源**（基准 = 01 路「API 术语权威清单」）；注释统一中文；仅改注释+文档不改标识符；UI 文案纳入。
> 编号：C<序号> 全局冲突号；出现点（01=data 02=domain 03=ui-chat 04=ui-rest 05=main-misc 06=tests 07=docs 08=strings）。

## 裁决基准：API 术语权威清单（源自 01，全文见 inventory/01 L198-264）

端点全集（V1 无前缀 / V2 /api）· SSE 事件全集 · DTO 字段 14 资源分组 · ID 前缀 msg_/ses_/pty_/frm_/call_/evt_ · 候选规范名：session/message/part/delta/tool/call_id/provider/model/agent/permission/reply/question/cursor/ordinal/compaction/interrupt(V2)/credential(V2)/directory(fs 域 V2)。

## 冲突索引

### 核心层（01+02）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C01 | **pending 两义**：堆积消息（本地暂存）vs 待处理权限/问题（服务器待答）；词根六变体（堆积/pending/queue/QUEUED/STACKED/pendingQueue）+ drain 状态两中文名（推送中/发送中） | 02,03,06 | ⏳ |
| C02 | **cursor 三义**：消息分页 token / Shell 输出字节偏移 / 会话列表游标 | 01,02 | ⏳ |
| C03 | **"回退"两义 + undo 三词一义**：revert/undoMessage/unrevert + 斜杠命令 /undo /redo + CHANGELOG"撤销操作"；fallback 义另立 | 02,03,07 | ⏳ |
| C04 | **compaction 变体群**：中文 4 叫法 × summarize/compact 双入口 × 动词/名词族 × 文档"压缩总结/压缩"摇摆 × i18n 两阵营 | 01,02,06,07,08 | ⏳ |
| C05 | **Part vs PromptPart**：消息内容块 vs 请求提示块；V2 现代 prompt{text,files,agents}；孤立译名"零件"清除 | 01,02,03 | ⏳ |
| C06 | **annotation/note 分裂**：标注（选区）vs 备注；UI 侧另有"批注说明/修改说明/整体说明"三叫法（note 字段） | 02,04 | ⏳ |
| C07 | **archive 两义**：会话归档（API）vs 本地归档桶（archive_buckets 表） | 01,02 | ⏳ |
| C08 | **发送动词群**：sendMessage/promptAsync/sendPrompt/入队/放行/推进 | 02 | ⏳ |
| C09 | **Terminal 错位**：ManageTerminalUseCase 实为命令执行非 PTY | 02 | ⏳ |
| C10 | **stale/僵尸/陈旧 五叫法 + L2/L3 编号** | 02 | ⏳ |
| C11 | **provider 三重定义 + catalog=目录 vs directory=目录** | 02 | ⏳ |
| C12 | **同构双定义 5 对**：SSE 事件类 vs 常驻状态类 | 02 | ⏳ |
| C14 | **subagent 拼写 + 子会话五变体**：API 词 subagent 无连字符；i18n 放大（pl 四拼写、5 语言通知残留英文）；文案层"智能体"三层 + V1/V2 元数据四键并读 | 01,02,03,06,08 | ⏳ |
| C15 | **turn 多口径**：堆积触发 / 流式 turn 词条 / V2 execution / UI 分组 turn / CHANGELOG"回合分割线" | 01,02,06,07 | ⏳ |
| C17 | **SessionStatus 大小写 + Asking 合成态** | 02 | ⏳ |
| C18 | **提问三代**：✅question 规范名（G1）+ 标识符侧 form 契约代码保留至服务器移除 | 01 | ✅ |
| C19 | **中断双词**：✅interrupt 规范名（G1）+ 标识符 abortSession→interruptSession（D3-1） | 01 | ✅ |
| C20 | **重命名双词**：✅rename 规范名 + updateSession→renameSession（D3-1） | 01 | ✅ |
| C21 | **认证双词**：✅credential 规范名 + removeProviderAuth→removeProviderCredential（D3-1）；authMethods 语义独立保留 | 01 | ✅ |
| C22 | **权限字段双轨**：permission/patterns vs action/resources；reply once/always/reject vs effect allow/deny | 01 | ⏳ |
| C23 | **ID 大小写双轨**：API sessionID/partID vs 域 camelCase；metadata 双写 | 01 | ⏳（注释约定） |
| C24 | **part 定位键三代**：partID / ordinal 派生 / call_id；REST id='' 契约错位 = 合并 bug 根因（#109） | 01 | ⏳ |
| C25 | **"单一真相源"多点位多义**：会话状态/后台 shell/红点时间 + AGENTS.md 三处另指版本号文件/依赖清单；措辞漂移（真相源/来源） | 01,07 | ⏳ |
| C26 | **提供商 vs Provider**：中文注释全用"提供商" | 01 | ⏳ |
| C27 | **shell 三义 + bash 双名**：交互式 pty / 后台 shell job / 会话内 shell 命令；V1 bash 与 V2 shell 端点双名（卡渲染已收口 shell） | 01,02,03 | ⏳ |

### UI 非聊天域（04 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C28 | **tag/category 分裂全链**：EN key=category vs 文案=Tag；README 仍用"会话分类"+已删功能；spec 字段 categoryAssignments；i18n key assign_category；14/14 复刻 | 04,07,08 | ⏳ |
| C29 | **connect/disconnect 双层撞词**：服务器层（客户端 SSE 会话管理，API 无此概念）vs provider 层（credential 授权） | 04 | ⏳ |
| C30 | **project 多义 + OpenProjectDialog 命名错位**：API /project 实体 vs "打开项目"实为 directory 浏览器 | 04 | ⏳ |
| C31 | **share 三义**：Android 系统分享图片 / session share URL 导入 / 子会话 | 04 | ⏳ |
| C32 | **retry 四域**：连接重试 / FSM Retry 状态 / 堆积队列继续（不用 retry）/ 上报重试 | 04 | ⏳ |
| C33 | **状态簇叫法群**：「内容册/外壳册」自造词 + "集群"（滚动状态集群）vs 词条「状态簇」+ 字母代号 B/C/D/G 无映射（→C72）；裁决并入词条或单列 | 03,04 | ⏳ |
| C34 | **模型"过滤" vs 可见性**：hiddenModels/setModelVisibility 持久开关被称"过滤"；model 双义（目录条目 vs 默认配置） | 04 | ⏳ |
| C35 | **MCP 状态裸字符串**：UI 字面量分支五状态词（与 API 一致），无枚举收口——注释修订时字符串拼写即术语 | 04 | ⏳（轻量） |
| C36 | **directory vs folder vs project**：代码 directory（API 词）/文案"文件夹视图"；i18n 串位（de/fr 译 folder、ar مجلد、ru/uk/ko 双表记） | 04,08 | ⏳ |
| C37 | **device flow 双定义**：GitHub 上报设备流 vs provider OAuth 设备码——注释冠域即可 | 04 | ⏳（轻量） |
| C38 | **AlphaTokens.AMOLED 三义借用**：模式/最大对比度值/0.92 数值被非 AMOLED 场景借用 | 04 | ⏳（轻量） |
| C39 | **ServerCard 直连 isV2 徽章 vs「版本 seam」词条**："UI 只读能力不读版本"边界确认（展示非行为分支） | 04 | ⏳（词条边界） |

### main 杂项域（05 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C40 | **性能监测/性能检测**：同指 debug 性能观测系统，两形并存（ChatPerfMonitor"监测" vs FrameStatsWindow"检测"） | 05 | ⏳（轻量） |
| C41 | **诊断界面叫法群**：诊断页/诊断屏/Diagnostics 屏幕/DiagnosticsScreen | 05 | ⏳（轻量） |
| C42 | **"流式"三义**：SSE 消息流式输出 / 流式高频日志编码 / 指纹流式变异 | 05 | ⏳ |
| C43 | **任务完成通知六叫法**：+ FeedbackType.TURN_COMPLETE = SessionIdle 事件 = turn 结束三域三名——turn 完成是否=任务完成 | 05,07 | ⏳（含概念） |
| C44 | **"会话"多义**：聊天会话（session）vs DebugLogger"采集会话"（调试采集期） | 05 | ⏳ |
| C45 | **"任务"多义**：任务完成（=turn）vs DateFormatters"任务卡片"（疑=ToolCard 工具卡片） | 05 | ⏳ |
| C46 | **location/directory/项目目录 三词交叉**：directory/worktree/canonical ✓ API 字段；location 为 form 侧概念；中文"项目目录"覆盖两者 | 05 | ⏳ |
| C47 | **synthetic 双信号**：Message.role=="synthetic" vs Part.Text.synthetic 两字段共用词 | 05 | ⏳（轻量） |
| C48 | **Service/Store/Repository 后缀并存**：同实现类绑多接口的命名分层约定 | 05 | ⏳（登记） |
| C49 | **指纹/签名分工固化**：签名=结构、指纹=内容（隐式分工已有，选是否固化入术语表） | 05 | ⏳（可选） |
| C50 | **"服务器"双指**：远程 OpenCode server vs 本地"服务器配置"（ServerConfig 存储） | 05 | ⏳ |
| C51 | **调试通道中英双名**：注释"调试通道" vs 日志"Debug channel"（日志属 UI 可见文案） | 05 | ⏳（轻量） |

### 测试域（06 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C52 | **未读三名**：红点/未读/badge vs 水位线 watermark vs maxCompleted/lastCompletedReplyTime——「红点时钟域」词条相关但未用词条词 | 06 | ⏳ |
| C53 | **排队消息四词根**：堆积消息 / pending pipeline / queue / QUEUED 徽章 / draining | 06 | ⏳（并 C01） |
| C54 | **"跳转窗口"三值三机制 + 冻结三名**：分片稳定窗口 2s / jumpLock 缓冲 300ms / 滚动稳定 900ms；文档三名（canon 稳定窗口/滚动锚定锁/跳转锁——末者正是词条 _Avoid_ 词） | 03,06,07 | ⏳（词条增补） |
| C55 | **part/chunk/entry 交叉**：API part vs UI chunk（UserChunk 用户长消息分片）vs entry（渲染供给）vs"分段"义 | 06 | ⏳ |
| C56 | **turn 分组语义漂移**：TurnGroupCalculator turn=连续 assistant 序列（2026-08-12 用户决策）vs API turn vs 流式 turn 词条——三口径 | 06 | ⏳（并 C15） |
| C57 | **游标 direction 反直觉**：服务器 next=更旧、previous=更新——API 契约事实，建议词条收录防误读 | 06 | ⏳（词条收录） |
| C58 | **子会话五变体**：subagent session/child session/childID/jobId/subSessionId + 中"子会话/子代理"——服务器 metadata 用 jobId 是根因 | 06 | ⏳（并 C14） |
| C59 | **compact 动词/名词族**：compactSession 操作 / /compact 命令 / compaction 事件族 / 压缩横幅 | 06 | ⏳（并 C04） |
| C60 | **播种 seed 中英混用 + 与迁移标记 seed 同形异义** | 06 | ⏳（轻量） |
| C61 | **测试名语言分裂**：10 文件中文反引号测试名 vs 其余英文——测试名语言方针需定 | 06 | ⏳（方针） |
| C62 | **feedback 两义**：提示音（SoundPlan/静音矩阵）vs 用户反馈 | 06 | ⏳（轻量） |
| C63 | **内部编号行话**：RS-0xx/T1-T10/L2-L4/D2-L54/#NNN/R6 大量无展开——保留（可追溯）还是展开 | 06 | ⏳（方针） |

### UI 聊天域（03 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C64 | **滚动触底三路径**：snapToBottom(吸附)/forceScrollToBottom/forceScrollTick——语义不同靠口语区分 | 03 | ⏳（轻量） |
| C65 | **FSM 依赖双名**：sessionStateService vs SessionStateRepository；AGENTS.md 承重规则亦用 SessionStateService（接口已建仍以实现类指称） | 03,07 | ⏳（注释约定） |
| C66 | **autoScroll 两叫法**：自动滚动 vs 自动跟随（SSE 铁律文本用 autoScroll） | 03 | ⏳（轻量） |
| C67 | **悲观发送两缩略**：悲观消息模式 vs 悲观发送 | 03 | ⏳（轻量） |
| C68 | **top/bottom 语义反转**：requestScrollToTop（会话列表滚顶）vs reverseLayout 聊天侧 top=视觉底部 | 03 | ⏳（注释约定） |
| C69 | **v1 三义**：产品迭代"v1"/协议 V1V2/测试代际 v1_regression_e2e | 03 | ⏳（注释约定） |
| C70 | **Q 口语缩写**：Q=用户消息 vs question=问题卡片（Q1/Q2 也是 UI 实显文案） | 03 | ⏳（轻量） |
| C71 | **防风暴三叫法**：防风暴/退避 backoff/自动续载暂停（同一分页保护机制） | 03 | ⏳（轻量） |
| C72 | **集群字母代号**：注释 B/C/D/G 字母 vs 代码簇名（sessionContext/conversation/composer），映射无处记载 | 03 | ⏳ |
| C73 | **跳转词族四叫法**：跳转/快速导航/定位加载/定位发起卡片 vs 代码统一 jump 前缀 | 03 | ⏳ |
| C74 | **渲染供给 Avoid 词违逆**：代码注释仍用"预解析驱动器 preparse driver"——CONTEXT.md 词条明确 Avoid | 03 | ⏳（词条执行） |
| C75 | **synthetic 中文 5 叫法**：系统通知/合成通知/后台消息/后台通知/转后台提示——"后台消息"另易与 background session 混淆 | 03 | ⏳ |
| C76 | **collapseTools 四层命名三层语义反转**：代码键 collapse（折叠）/KDoc"默认折叠"/消费 autoExpand/UI"自动展开工具结果" | 03 | ⏳（高价值） |

### UI 文案 / i18n 域（08 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C77 | **会话三词 + 对话双轨**：session ✓ vs conversation vs chat；代码/架构层"会话" vs E2E 文档族自名"对话（dialogue）"——UI 期望文档整体用另一叫法 | 07,08 | ⏳ |
| C78 | **agent 显示词**：EN 标签 Assistant（:179）vs agent；de/es/fr/it/pt 双词根；zh 四变体（智能体/代理/Agent/Sub-agent） | 08 | ⏳ |
| C79 | **compress 二义**：上下文压缩（EN :125）vs 图片压缩（key settings_compress_images 显示词 Optimize、a11y Compress）——图片侧统一 Optimize、上下文侧 compact | 08 | ⏳ |
| C80 | **thinking vs reasoning**：EN 分立；API part 类型为 reasoning（thinking 非 API 词）；ru 撞词、uk 生造 | 08 | ⏳ |
| C81 | **翻译策略三题**：shell 三态（全译/不译/混用）、token（借词 vs 意译 vs zh 半分裂）、英文残留（chat_title 15/15 保留等） | 08 | ⏳（方针） |

### 文档/脚本域（07 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C82 | **AGENTS.md 使用词条 _Avoid_ 词「流式消息」**（:106,110）——规则源自身违逆「流式 turn」词条 | 07 | ⏳（规则源修复） |
| C83 | **术语表自称四名**：glossary/术语表/词汇表/未定名 | 07 | ⏳（轻量） |
| C84 | **工作项命名**：issue-tracker「工作项」规范名 vs AGENTS.md「卡片/条目」混用 | 07 | ⏳（轻量） |
| C85 | **flavor/channel**：✅已裁决 D3-4——全仓统一 flavor，gradle dimension 标识符改名 | 07 | ✅ |
| C86 | **终端栈历史名残留**：#189 已定 Termux，ConnectBot/libvterm 历史名仍驻留 | 07 | ⏳（轻量） |
| C87 | **BREAKING 段落归属三源不一致**：脚本恒 Removed vs 模板明文 vs release-workflow 允许二选一 | 07 | ⏳（流程文档） |
| C88 | **编号四轨**：✅已裁决 D3-5——统一重构为 V/A/P/S/F/# 前缀体系 + numbering-charter；D 前缀退役 | 07 | ✅ |
| C89 | **Working vs busy**：regression-guide 会话列表"Working 状态"（UI 显示名）vs FSM busy | 07 | ⏳（轻量） |
| C90 | **产品名残留 OC Remote**：verification-requirements/chat-ui-event-lifecycle 仍自称（历史档案 4 份可豁免） | 07 | ⏳（直接修订） |
| C91 | **workspace 一词两义 + 中文双译**：API workspace=项目分支视图 vs README"工作区浏览器"=file 域；工作空间 vs 工作区 | 07 | ⏳ |
| C92 | **Session.next 前缀歧义**："下一代事件体系"易误读；handler 名 SessionNext 加剧 | 07 | ⏳（轻量） |
| C93 | **"V2 SSE"三代并存**：V1 message.part.* / 文档 v2 session.next.*+textID / 实测 session.input.*+ordinal——"v2"指向不同代 | 07 | ⏳（文档勘误） |
| C94 | **prompt/message 文档混用**：PRIVACY_POLICY 并列抹平 API 差异；「发送消息」vs prompt 规范名 | 07 | ⏳（并 C08） |
| C95 | **E2E 语言**：✅已裁决 D3-3——全英文锁定 + 修 4 处中文 + 约定成文 | 07 | ✅ |

## i18n 直接修订清单（Phase2b 输入，无需裁决）

- 覆盖缺口：ja 27 / ar 55 / ru 103 / uk 103 key 未译（ru/uk 列表相同，回退英文）
- 错字/格式：es/tr/ko 全角括号 9 处 · ko 토킰 错字+两句语法错 · pt sessao 缺波浪号
- uk/pl fork 误译"分割"（语义反转）；uk unshare 撞词
- 14/14 语言 about_unofficial 免责声明缩水（丢 fork+@crim50n 免责）
- EN deny key vs Reject 文案分裂（14/14 翻译已事实一致化，EN 待修）
- EN :607 opencode URL org（anomalyco）待核验
- 文档失实（07 路 25 条精选）：verification-requirements/chat-ui-event-lifecycle 自称 OC Remote · README 列已删功能（跨服务器收藏等）· architecture.md 列已删类 · 1.x 版本示例残留 4 处（release.yml/ci-determine-flavor/release-workflow/regression-guide）· release.sh 死代码 2 处 · proguard 重复块 · Part 计数 12/13/16 三源不一（api-reference 补 abort 等）

## 代码事实区（非注释问题 → backlog 登记候选，不现场修）

| 号 | 事实 | 来源 |
|---|---|---|
| F01 | PartSerializer 缺 permission/question 分支 → 落 Unknown | 02 |
| F02 | ChatRepository 承载纯 UI 状态（工具卡片展开） | 02 |
| F03 | executeCommand 的 agent/model/variant/parts 参数未进请求体（V1/V2 同） | 01 |
| F04 | SessionGrouping 死代码（全库 grep 核实无调用点） | 04 |
| F05 | 盘符哨兵 ":///drives" 双定义无引用关系（SessionListViewModel vs DirectoryPath） | 04 |
| F06 | 搜索防抖双层串联 ~600ms + 三处 300ms 魔法数各自定义 | 04 |
| F07 | FATAL 日志级别不可被过滤 chip 选中（ERROR 不含 FATAL） | 04 |
| F08 | 日志 `# 术语冲突主台账（Phase 1 汇总 → Phase 2 裁决底稿）

> 状态：**汇总中**（01/02/04 已并入；03/05/06/07/08 待并入）。来源：8 路盘点。
> 裁决原则（用户已定）：规范名以 **OpenCode API 术语为权威源**（基准 = 01 路「API 术语权威清单」）；注释统一中文；仅改注释+文档不改标识符；UI 文案纳入。
> 编号：C<序号> 全局冲突号；出现点（01=data 02=domain 03=ui-chat 04=ui-rest 05=main-misc 06=tests 07=docs 08=strings）。

## 裁决基准：API 术语权威清单（源自 01，全文见 inventory/01 L198-264）

端点全集（V1 无前缀 / V2 /api）· SSE 事件全集 · DTO 字段 14 资源分组 · ID 前缀 msg_/ses_/pty_/frm_/call_/evt_ · 候选规范名：session/message/part/delta/tool/call_id/provider/model/agent/permission/reply/question/cursor/ordinal/compaction/interrupt(V2)/credential(V2)/directory(fs 域 V2)。

## 冲突索引

### 核心层（01+02）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C01 | **pending 两义**：堆积消息（本地暂存）vs 待处理权限/问题（服务器待答）；UI 抽屉"堆积/TODO"并置 | 02 | ⏳ |
| C02 | **cursor 三义**：消息分页 token / Shell 输出字节偏移 / 会话列表游标 | 01,02 | ⏳ |
| C03 | **"回退"两义**：revert（撤销/redo）vs fallback（降级/回退缓存） | 02 | ⏳ |
| C04 | **compaction 变体群 + 动词分裂**：中文 4 叫法 × V1 summarize vs V2 compact 双入口 | 01,02 | ⏳ |
| C05 | **Part vs PromptPart**：消息内容块 vs 请求提示块；V2 另有现代 prompt{text,files,agents} | 01,02 | ⏳ |
| C06 | **annotation/note 分裂**：标注（选区）vs 备注；UI 侧另有"批注说明/修改说明/整体说明"三叫法（note 字段） | 02,04 | ⏳ |
| C07 | **archive 两义**：会话归档（API）vs 本地归档桶（archive_buckets 表） | 01,02 | ⏳ |
| C08 | **发送动词群**：sendMessage/promptAsync/sendPrompt/入队/放行/推进 | 02 | ⏳ |
| C09 | **Terminal 错位**：ManageTerminalUseCase 实为命令执行非 PTY | 02 | ⏳ |
| C10 | **stale/僵尸/陈旧 五叫法 + L2/L3 编号** | 02 | ⏳ |
| C11 | **provider 三重定义 + catalog=目录 vs directory=目录** | 02 | ⏳ |
| C12 | **同构双定义 5 对**：SSE 事件类 vs 常驻状态类 | 02 | ⏳ |
| C14 | **sub-agent/subagent 拼写 + "子会话"（childID）未定义** | 01,02 | ⏳ |
| C15 | **turn 三义**：本地堆积队列语义 vs 展示语义（流式 turn 词条）vs 协议语义（V2 execution 权威）；结束信号三家族 | 01,02 | ⏳ |
| C17 | **SessionStatus 大小写 + Asking 合成态** | 02 | ⏳ |
| C18 | **提问三代契约**：question ↔ form（stale surface）↔ question.v2；404 记忆降级 | 01 | ⏳（候选 question） |
| C19 | **中断双词**：abort(V1) vs interrupt(V2) | 01 | ⏳ |
| C20 | **重命名双词**：update(域) vs rename(V2 端点) | 01 | ⏳ |
| C21 | **认证双词**：auth(V1) vs credential(V2) | 01 | ⏳ |
| C22 | **权限字段双轨**：permission/patterns vs action/resources；reply once/always/reject vs effect allow/deny | 01 | ⏳ |
| C23 | **ID 大小写双轨**：API sessionID/partID vs 域 camelCase；metadata 双写 | 01 | ⏳（注释约定） |
| C24 | **part 定位键三代**：partID / ordinal 派生 / call_id；REST id='' 契约错位 = 合并 bug 根因（#109） | 01 | ⏳ |
| C25 | **"单一真相源"三点位**：会话状态/后台 shell/红点时间 | 01 | ⏳ |
| C26 | **提供商 vs Provider**：中文注释全用"提供商" | 01 | ⏳ |
| C27 | **shell 三义**：交互式 pty vs 后台 shell job vs 会话内 shell 命令 | 01,02 | ⏳ |

### UI 非聊天域（04 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C28 | **tag/category 同屏混用**：TagEditDialog label=category_name"分类名称"而同屏文案全用"标签"；Tag 为数据模型名 | 04 | ⏳ |
| C29 | **connect/disconnect 双层撞词**：服务器层（客户端 SSE 会话管理，API 无此概念）vs provider 层（credential 授权） | 04 | ⏳ |
| C30 | **project 多义 + OpenProjectDialog 命名错位**：API /project 实体 vs "打开项目"实为 directory 浏览器 | 04 | ⏳ |
| C31 | **share 三义**：Android 系统分享图片 / session share URL 导入 / 子会话 | 04 | ⏳ |
| C32 | **retry 四域**：连接重试 / FSM Retry 状态 / 堆积队列继续（不用 retry）/ 上报重试 | 04 | ⏳ |
| C33 | **"内容册/外壳册"自造词 vs「状态簇」词条**：概念同族（#23 状态切片），裁决并入词条或单列 | 04 | ⏳ |
| C34 | **模型"过滤" vs 可见性**：hiddenModels/setModelVisibility 持久开关被称"过滤"；model 双义（目录条目 vs 默认配置） | 04 | ⏳ |
| C35 | **MCP 状态裸字符串**：UI 字面量分支五状态词（与 API 一致），无枚举收口——注释修订时字符串拼写即术语 | 04 | ⏳（轻量） |
| C36 | **directory vs folder**：代码 directory（API 词）/ 文案"文件夹视图"（FOLDER 枚举 + sessions_view_folders） | 04 | ⏳（译名族） |
| C37 | **device flow 双定义**：GitHub 上报设备流 vs provider OAuth 设备码——注释冠域即可 | 04 | ⏳（轻量） |
| C38 | **AlphaTokens.AMOLED 三义借用**：模式/最大对比度值/0.92 数值被非 AMOLED 场景借用 | 04 | ⏳（轻量） |
| C39 | **ServerCard 直连 isV2 徽章 vs「版本 seam」词条**："UI 只读能力不读版本"边界确认（展示非行为分支） | 04 | ⏳（词条边界） |

### main 杂项域（05 路新增）

| 号 | 冲突 | 出现 | 状态 |
|---|---|---|---|
| C40 | **性能监测/性能检测**：同指 debug 性能观测系统，两形并存（ChatPerfMonitor"监测" vs FrameStatsWindow"检测"） | 05 | ⏳（轻量） |
| C41 | **诊断界面叫法群**：诊断页/诊断屏/Diagnostics 屏幕/DiagnosticsScreen | 05 | ⏳（轻量） |
| C42 | **"流式"三义**：SSE 消息流式输出 / 流式高频日志编码 / 指纹流式变异 | 05 | ⏳ |
| C43 | **任务完成通知五叫法 + 概念裁决**：任务完成通知/TaskComplete/TURN_COMPLETE/"turn 完成"——turn 完成是否=任务完成 | 05 | ⏳（含概念） |
| C44 | **"会话"多义**：聊天会话（session）vs DebugLogger"采集会话"（调试采集期） | 05 | ⏳ |
| C45 | **"任务"多义**：任务完成（=turn）vs DateFormatters"任务卡片"（疑=ToolCard 工具卡片） | 05 | ⏳ |
| C46 | **location/directory/项目目录 三词交叉**：directory/worktree/canonical ✓ API 字段；location 为 form 侧概念；中文"项目目录"覆盖两者 | 05 | ⏳ |
| C47 | **synthetic 双信号**：Message.role=="synthetic" vs Part.Text.synthetic 两字段共用词 | 05 | ⏳（轻量） |
| C48 | **Service/Store/Repository 后缀并存**：同实现类绑多接口的命名分层约定 | 05 | ⏳（登记） |
| C49 | **指纹/签名分工固化**：签名=结构、指纹=内容（隐式分工已有，选是否固化入术语表） | 05 | ⏳（可选） |
| C50 | **"服务器"双指**：远程 OpenCode server vs 本地"服务器配置"（ServerConfig 存储） | 05 | ⏳ |
| C51 | **调试通道中英双名**：注释"调试通道" vs 日志"Debug channel"（日志属 UI 可见文案） | 05 | ⏳（轻量） |

## 代码事实区（非注释问题 → backlog 登记候选，不现场修）

| 号 | 事实 | 来源 |
|---|---|---|
| F01 | PartSerializer 缺 permission/question 分支 → 落 Unknown | 02 |
| F02 | ChatRepository 承载纯 UI 状态（工具卡片展开） | 02 |
| F03 | executeCommand 的 agent/model/variant/parts 参数未进请求体（V1/V2 同） | 01 |
| F04 | SessionGrouping 死代码（全库 grep 核实无调用点） | 04 |
| F05 | 盘符哨兵 ":///drives" 双定义无引用关系（SessionListViewModel vs DirectoryPath） | 04 |
| F06 | 搜索防抖双层串联 ~600ms + 三处 300ms 魔法数各自定义 | 04 |
 转义缺陷 ×2：MainActivity shared image(s) 恒打印字面量；OpenCodeConnectionService `${'

## CONTEXT.md 词条实现确认（01/02/04 三向印证）

- 红点时钟域 / 必需协作者 / 版本 seam / 流式 turn：同前（01+02 双向印证，保持不动）
- 连接生命周期协调（04 增补）：OpenCodeConnectionService 为前台服务 adapter ✓；UI 层另有 reconnectMode 三档/ConnectionErrorScreen 倒计时/testConnection 健康检查三套连接词——注释描述 Service 时须对齐词条（HomeViewModel 已基本符合）
- 状态簇（04 增补）：见 C33「内容册/外壳册」并入裁决
- 版本 seam 边界（04 增补）：见 C39 ServerCard 展示性 isV2

## 失实注释热点（跨路汇总）

- 01 路 7 条（最重：interruptZombieRunner KDoc 声称调服务器实仅打日志）
- 02 路 8 条（SessionActivity 漏 Compacting、Phase 3/4 残留 ×4 等）
- 03 路 25 条：常驻抽屉已退役仍被描述为现状（ChatScreen:853）· JumpNavigationController 稳定窗口 KDoc 1.5s 实为 900ms · ticker 300ms 注释 vs delay(100) · ChatViewModel 描述已替代的 cacheWindow/已移除的跳转预组合 · revert 过滤 <= vs < · optimisticStore 幽灵引用 · RenderReadiness"消息级"实键 part.id · synthetic"嵌入气泡"过期 6 处 · 死代码三例（TokenRatioRing/MessageMetaInfo/smoothScrollToBottom）现役口吻 · DRAFT_DEBOUNCE_MS 孤儿注释 · LocalCollapseTools KDoc 与消费语义相反
- 04 路 6 条：AmoledCard"65%"实为 0.70 · SessionGrouping 死代码现行口吻 · retrySubmit"草稿保留"实总是重建 · SettingsViewModel"单消费者 channel"实为 Mutex+快照 · **WebViewScreen 自称"替代所有原生屏幕"实为旧版回退（useNativeUi=true 硬编码）** · GitChangesPanel"后续阶段实现"实际已接通

## 各路完成状态

| 路 | 状态 | 行数 |
|---|---|---|
| 01 data | ✅ 114/114 | 992 |
| 02 domain | ✅ 91/91 | 219 |
| 03 ui-chat | ✅ 158/158（含 coverage 边界补读 5 文件） | 509 |
| 04 ui-rest | ✅ 113/113 | 334 |
| 05 main-misc | ✅ 113/113（27 kt + 86 xml；PathUtils/AppLogger 承重核实一致） | 164 |
| 06 tests | ✅ 248/248（test 196 + androidTest 52） | 332+附录 |
| 07 docs | ✅ 138/138 精读（journal 35 仅统计） | 436 |
| 08 strings | ✅ 15/15（EN 786 行 + 14 翻译全 key；674 keys） | 494 |

## 待并入注意点

- 03 路：「渲染供给」「跳转稳定窗口」词条映射；SSE/滚动失实高发区
- 08 路：14 语言译名漂移扩充 C01/C04/C26/C28/C36 出现点
- 06 路：测试名与主代码叫法漂移}` 同款 | 05 |
| F09 | OpenCodeApp 冗余条件：UI_HIDDEN(20) 恒覆盖 RUNNING_LOW(10) | 05 |
| F10 | 74 个 provider 图标文件名 = provider id 本地映射层（双品牌/区域/套餐变体：zai↔zhipuai、_cn、_coding_plan） | 05 |
| F11 | FakeChatRepository"46 个方法"实测 49 override | 06 |
| F12 | FileApiVcsTest fixture 残留前世包名 dev/minios/ocremote（仅一处） | 06 |
| F13 | JumpPrefetchStrategy 名实不符：类名"跳转预组合"，2026-08-21 移除跳转职责后仅剩滚动方向预测 | 03 |
| F14 | RenderReadiness 键粒度错位：形参/KDoc"消息级 msgId" vs 实际键 part.id（分片引入后未跟） | 03 |

## CONTEXT.md 词条实现确认（01/02/04 三向印证）

- 红点时钟域 / 必需协作者 / 版本 seam / 流式 turn：同前（01+02 双向印证，保持不动）
- 连接生命周期协调（04 增补）：OpenCodeConnectionService 为前台服务 adapter ✓；UI 层另有 reconnectMode 三档/ConnectionErrorScreen 倒计时/testConnection 健康检查三套连接词——注释描述 Service 时须对齐词条（HomeViewModel 已基本符合）
- 状态簇（04 增补）：见 C33「内容册/外壳册」并入裁决
- 版本 seam 边界（04 增补）：见 C39 ServerCard 展示性 isV2

## 失实注释热点（跨路汇总）

- 01 路 7 条（最重：interruptZombieRunner KDoc 声称调服务器实仅打日志）
- 02 路 8 条（SessionActivity 漏 Compacting、Phase 3/4 残留 ×4 等）
- 04 路 6 条：AmoledCard"65%"实为 0.70 · SessionGrouping 死代码现行口吻 · retrySubmit"草稿保留"实总是重建 · SettingsViewModel"单消费者 channel"实为 Mutex+快照 · **WebViewScreen 自称"替代所有原生屏幕"实为旧版回退（useNativeUi=true 硬编码）** · GitChangesPanel"后续阶段实现"实际已接通

## 各路完成状态

| 路 | 状态 | 行数 |
|---|---|---|
| 01 data | ✅ 114/114 | 992 |
| 02 domain | ✅ 91/91 | 219 |
| 03 ui-chat | 🔄 运行中（已发 coverage 边界补充：*Dialog/ChatBehaviorSection 等） | 355+ |
| 04 ui-rest | ✅ 113/113 | 334 |
| 05 main-misc | 🔄 运行中（res XML 段） | 138+ |
| 06 tests | 🔄 运行中（重建批 1 → 批次 3+） | 457 |
| 07 docs | 挂起（队列） | 265 |
| 08 strings | 挂起（队列） | 108 |

## 待并入注意点

- 03 路：「渲染供给」「跳转稳定窗口」词条映射；SSE/滚动失实高发区
- 08 路：14 语言译名漂移扩充 C01/C04/C26/C28/C36 出现点
- 06 路：测试名与主代码叫法漂移