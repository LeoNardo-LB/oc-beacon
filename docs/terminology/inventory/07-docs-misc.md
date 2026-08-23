# 盘点：项目文档 + 根 md + 构建脚本 + CI + maestro（docs/journal 与 docs/learning 仅统计不精读）

- worktree：`/home/leo-tkp/Documents/code/mine/oc-beacon-terminology`（只读盘点，Phase 1 事实收集，不做术语裁决）
- 参考基准：根 `CONTEXT.md` 术语表 8 词条（渲染供给 / 流式 turn / 跳转稳定窗口 / 红点时钟域 / 必需协作者 / 状态簇 / 版本 seam / 连接生命周期协调）及其 _Avoid_ 词。
- 事实：`docs/learning/` 目录**不存在**于本 worktree（磁盘枚举无此目录）；本 worktree AGENTS.md 文档索引亦未引用它（主 checkout 的 AGENTS.md 有引用，属范围外）。`docs/adr/` 同样不存在（domain.md 自带「不存在则静默继续」防护）。
- 覆盖清单：精读 138 文件（docs 69 + 根 md 6 + scripts 24 + gradle/proguard 4 + CI 1 + maestro 34）+ 仅统计 journal 35 文件 + 范围外命中 5 项；`app/src/main/kotlin` 下无 md 命中。

## 覆盖清单

状态图例：✓ 已精读 · ⏳ 待读 · ○ 仅统计 · ✗ 范围外。注释语言四分类：中文 / 英文 / 混合 / 无注释（文档类按正文语言记，脚本按注释语言记）。

### 精读范围（138/138 已读 ✓ 全部读完）

| 路径 | 状态 | 注释语言 | 备注 |
|---|---|---|---|
| `docs/PRIVACY_POLICY.md` | ✓ | 英文+中文双语（同文双份） | 隐私政策；SSE/streaming connection、workspace viewer、terminal output、diagnostic logs（1000 条/10MB 上限、脱敏）等术语中英对译一致；「提示词/prompts」为 message 的变体 |
| `docs/agents-file-design.md` | ✓ | 中文 | AGENTS.md 维护规范（L0/L1/L2 分层、MUST/SHOULD/MAY RFC 2119、承重规则内联、反模式清单）；流程域无业务术语；「MUST ≤5-7 条」与 AGENTS.md:9 互证 ✓ |
| `docs/agents/domain.md` | ✓ | 中文 | 术语使用规则源：要求用 CONTEXT.md 词汇表、禁漂移到 _Avoid_ 同义词；引用 docs/adr/（目录现不存在，自带静默防护） |
| `docs/agents/issue-tracker.md` | ✓ | 中文+英文术语 | 工作项双轨制（backlog 卡片 + GitHub Issues）；「gh 不走代理（AGENTS.md 约定）」在 AGENTS.md 无对应条款 |
| `docs/agents/skills.md` | ✓ | 中文 | 三约定索引页（issue-tracker/triage-labels/domain），指向文件均存在 |
| `docs/agents/triage-labels.md` | ✓ | 中文 | 五 triage 标签映射表（needs-triage/needs-info/ready-for-agent/ready-for-human/wontfix） |
| `docs/architecture-debt.md` | ✓ | 混合（中文正文+英文节标题） | 债务登记（依赖违规/Thin UseCase 25 个/God Files/测试缺口）；:36,40 把「ViewModel 委托给 UseCase」规范归属于 AGENTS.md，实际在 architecture.md:61 与 README.md:97——引用漂移 |
| `docs/architecture.md` | ✓ | 中文 | 架构权威：14 repository/10 handler/API 拆分/ConnectionLifecycleCoordinator/RenderSupplyCoordinator（显式引用 CONTEXT.md 术语）/红点三铁律；model 清单仍列 SessionCategory/FavoriteSessionSnapshot（CHANGELOG 记 1.2.0 已删）——见失实表 |
| `docs/archive/android-code-viewer-libraries.md` | ✓ | 中文 | 代码查看器三方库调研（8 候选+对比表）；「针对 oc-remote 的需求」——2026-06-23 历史文档用上游名自称；Annotate（标注）功能词 |
| `docs/archive/specs/2026-06-18-workspace-file-viewer-design.md` | ✓ | 中文 | 工作空间文件预览与标注修改设计（1114 行，FileViewer 三入口/标注修改闭环/Hunk 导航器/ToolSnapshotGrouper）；标题用「工作空间」——与 README/architecture 的「工作区」是 workspace 的两个中文变体；URLDecoder 惯例早于 safeDecodeParam 规则 |
| `docs/archive/specs/2026-07-02-shell-streaming-and-patchcard-restyle-design.md` | ✓ | 中文 | Shell 输出流式显示（session.next.tool.progress→Running.output 桥接）+PatchCard 复用聚合样式；2026-07-02 仍自称 OC Remote；callID/有界更新/ToolGroupList |
| `docs/archive/specs/2026-08-04-markdown-table-wrap-design.md` | ✓ | 中文 | 表格动态上限换行设计（cellCap 公式/MIN_CELL 120dp/SimpleMarkdownTable/两端一致性 D1-D7）；与 ui-conventions.md:50-52 互证 ✓；状态「待审阅」但已归档（归档=已实现的约定） |
| `docs/archive/specs/2026-08-07-session-list-state-slicing-design.md` | ✓ | 中文 | 会话列表状态切片设计（23 源魔法索引→具名 data class；DataInputs/UiInputs/ContentState/ShellState）；spec 字段名仍用 categoryAssignments/categoryFilterIds 而源已是 sessionTagAssignments——category→tag 改名残留 |
| `docs/archive/specs/2026-08-07-unread-derived-state-design.md` | ✓ | 中文 | 红点派生状态模型设计（CONTEXT.md「红点时钟域」词条的直接来源 spec）；maxCompleted/readTimes/allReadAt/unreadBaseline（删除论证）/一键已读/服务器域 vs 客户端 now；R1-R6 根因表 |
| `docs/archive/specs/2026-08-11-v2-contract-alignment-design.md` | ✓ | 中文 | V2 契约对齐修复设计（实测 V2 事件体系 session.input.*/session.execution.*/ordinal 定位键 ≠ 文档 session.next.* 的 textID 版本）；partId 派生规则、finish=tool-calls 不结束 turn、running ≠ 前台 turn；含明文测试密码 |
| `docs/chat-ui-event-lifecycle.md` | ✓ | 中文 | Chat UI 事件生命周期（触摸传播/嵌套滚动 consumeBoundaryScroll/SSE→UI 链路/消息状态机 Queued-Sending-Streaming-Complete-Error/5 竞态清单）；:3 仍自称「OC Remote 对话界面」——产品名残留第 2 处 |
| `docs/chatscreen-editing-protocol.md` | ✓ | 英文（唯一纯英文规则文档） | ChatScreen 编辑协议 4 条铁律 + Rationale（beta.62-64 8000+ 行时代教训）；与 AGENTS.md:72 中文转述互证 ✓；语言现状与「注释未来统一中文」方针不一致样本 |
| `docs/debug-channel.md` | ✓ | 中文 | 调试通道 #132（adb extras 传参幂等保存+直达会话列表）；术语 debug_url/幂等/版本探测（apiVersion V1/V2）/冷启动 vs 热启动；端口 4199 与 AGENTS.md:100 互证 ✓ |
| `docs/dialogue-e2e-test-plan.md` | ✓ | 中文 | 对话全生命周期 E2E 期望文档（E0-E8 阶段/DYN-STA-SCR/每用例 V1V2 双协议标注）；术语最密：FSM 转移动词（ClientSendParts/TextStarted/SseIdle/ClientAbort）、播种（V2 本地播种 vs V1 无）、cached_messages/cached_parts、hasOlder 游标 |
| `docs/dialogue-e2e-test-runbook.md` | ✓ | 中文 | 11 轮实操记录（模拟器+真机 PLK110/houji）；轮次 11 追加在文档尾注之后（结构小瑕疵）；新术语：转圈点击中断、ScrollBottomFAB、nullStreak、游标态 HotStart/Network、Pending answer 徽标、t2s.log；含视觉模型误报教训 |
| `docs/e2e-testing-workflow.md` | ✓ | 中文 | 模拟器 E2E 工作流（TC1-TC5/replicant MCP/Medium_Phone AVD/10.0.2.2:4096）；「最后执行 2026-08-05」早于 2026-08-20 真机优先方针，未回注新方针——轻度滞后 |
| `docs/i18n-guide.md` | ✓ | 中文 | i18n 工作流（英文源唯一+14 翻译+lokit 已移除）；§5 术语基准表（Tag/标签、Session/会话、Server/服务器）；:57 仍引 assign_category key——category 残留于 key 命名层 |
| `docs/observability-verification-guide.md` | ✓ | 中文 | 可观测手册（logcat PID+tag 规范/Room 直查 ocbeacon.db/关键 Tag 速查/curl 直测/ScrollDiag 插桩 LEAP·RESIZE·COMP-MSG）；§3.3 埋点要求 AppLogger 与 AGENTS.md 一致 ✓（反衬 verification-requirements 的 Log.i 措辞） |
| `docs/opencode-api-deep-research/1-session-message.md` | ✓ | 中文（API 名英文） | V1 session/permission/question 32 端点深研（源码级）；Part 12 种类型表、ToolState 4 态、Todo.Info、Question/Permission schema、分页游标 base64url({id,time})、Link/X-Next-Cursor header；发现：prompt 非流式/PATCH permission 合并语义 |
| `docs/opencode-api-deep-research/2-config-provider.md` | ✓ | 中文（API 名英文） | config/provider/MCP/global 21 端点深研；MCP.Status 5 态、OAuth 双模式、Global 组无认证警告、配置 PATCH 销毁实例语义 |
| `docs/opencode-api-deep-research/3-file-project.md` | ✓ | 中文（API 名英文） | file/project/ProjectCopy/workspace/reference 22 端点深研；文件搜索三层引擎（ripgrep/fff/LSP）、/find/symbol 与 /file/status 为桩实现（返回空数组）、workspace warp 迁移语义 |
| `docs/opencode-api-deep-research/4-terminal-control.md` | ✓ | 中文（API 名英文） | PTY/TUI/控制平面/同步/实验性/实例 53 端点深研；PTY 票据三步认证（60s TTL/一次性/三元组绑定）、2MB 环形缓冲+64KB 分块、TUI 双通道、EventV2 事件溯源（aggregateID/seq）、/tui 别名映射表；发现 openThemes 疑似源码 bug |
| `docs/opencode-api-deep-research/5-sse-events.md` | ✓ | 中文（API 名英文） | 89 SSE 事件全枚举（系统 4/Session v1 7/Message v1 5/Session.next v2 31/状态 5/Todo 1/Permission 4/Question 6/PTY 4/MCP 2/Project 3/LSP-IDE 3/Account 5/Filesystem 2/Installation 2/Workspace 5/TUI 4）；delta 瞬时不可回放、v1v2 并行发布、SSE event 字段恒为 message |
| `docs/opencode-api-reference-v1.md` | ✓ | 中文（API 名英文） | V1 API 权威参考（129 端点+89 事件，5243 行全读）；§12A Form（V2 实测 #130）、§23 Token 两层语义（Message 覆盖 vs Session 累加、ACP 口径 input+cacheRead）；§22 Part 表 13 种（含 abort）与 deep-research 1 的「12 种」计数不一致；/session/import 标注 [待确认] |
| `docs/qa-methodology.md` | ✓ | 中文 | QA 方法论（D1-D5 五维/交叉验证 ≥2 维/证据链/可复现/并行节点/双文档模式 plan+runbook/DYN-STA-SCR）；:34-35 显式维护 D1-D5↔D0-D4 双轨编号映射 |
| `docs/real-device-testing.md` | ✓ | 中文 | 真机 runbook（pm install 静默法/adb reverse/debug intent/keystore 编年史）；:103 记录 keystore alias 由 **oc-tether** 改名 oc-beacon——产品名历史变体；滚动方向教训/禁 input text 纪律 |
| `docs/regression-guide.md` | ✓ | 中文 | 回归执行指南（D0-D4 维度 + 12 能力域清单 + 完整/快速/免回归分档）；术语归档桶/热表阈值/游标推进/Working 状态；:209「VERSION_CODE 只增不减（1.0.0 起严格）」版本体系重置后已过期 |
| `docs/release-notes-template.md` | ✓ | 中文 | Release Notes 模板；:27 明文「Removed——已移除的功能；破坏性变更前置 **BREAKING:**」（C16 的约定依据）；节顺序 Added→…→Removed→Fixed 与脚本生成顺序 Removed→Added→Changed→Fixed 不同 |
| `docs/release-workflow.md` | ✓ | 中文 | 发版唯一权威指南（SemVer/脚本流程/CHANGELOG 规则/Play AAB/签名编年史/覆盖矩阵）；:4「最后更新 2026-08-05」滞后于正文内容（§9.3 记至 08-21）；§3.3/§4.2 示例仍用 v1.0.3 旧版本形态 |
| `docs/research/RG-2026-08-11-v2-contract-background.md` | ✓ | 中文 | V2 契约对齐回归报告（D0-D4/12 能力域/V2SseMapper/execution 驱动）；后台系统（入口/面板/工具栏）+ Subagents 角标；synthetic 消息过滤登记 #67 |
| `docs/research/audit-2026-08-10/A-rendering-pipeline.md` | ✓ | 中文 | 渲染管线性能审计（SSE→UI 全链路图/10 环节风险/补丁 vs 根因判定表）；反射 LazyListReflection 补丁判定；诊断残留 P1 |
| `docs/research/audit-2026-08-10/B-data-pagination-archive.md` | ✓ | 中文 | 数据层/分页/归档审计（P0 DatabaseRecovery 删库过宽/3 游标状态机图 INITIAL→ARCHIVE_PAGING→NETWORK_PAGING/BACKOFF）；归档桶 zstd/热表 1000 条术语权威 |
| `docs/research/audit-2026-08-10/C-state-events-logging.md` | ✓ | 中文 | 状态/事件/日志审计（SSE→FSM→UI 全景链路图/S1-S9 风险/combine 依赖图）；[VERIFY: 文件:行号] 证据标注体例 |
| `docs/research/audit-2026-08-10/D-patch-vs-rootcause-history.md` | ✓ | 中文 | 补丁 vs 根因历史审计（25 fix+15 refactor 判定；TD-1~TD-10 技术债；模式 A-E）；PaginationCursor/PaginationFSM 建议词 |
| `docs/research/audit-2026-08-10/D35-investigation.md` | ✓ | 中文 | #35 Back ANR 排查（55+ 轮未复现；JobCancellationException 日志噪声分类） |
| `docs/research/audit-2026-08-10/D64-bisect.md` | ✓ | 中文 | #64 二分实验（CURRENT/NO42/NO43 三 APK 对照；初始视口逐字节相同；反射 0 命中） |
| `docs/research/audit-2026-08-10/D64-conclusive.md` | ✓ | 中文 | #64 误判定性（滚动方向单向测试方法学缺陷）；auto-scroll 到底后上滑无内容=正常；双向验证铁律来源 |
| `docs/research/audit-2026-08-10/D64-investigation.md` | ✓ | 中文 | #64 Phase1 证据收集（#43 回归坐实后又D64-bisect/conclusive 推翻——三份文档构成完整误判-翻案链）；方法论修正：截图哈希不可靠改 bounds 对比 |
| `docs/research/audit-2026-08-10/E-emulator-measurements.md` | ✓ | 中文 | 模拟器性能实测（300ms 卡顿帧=GC 风暴主因/双日志 1:0.93~0.99 恒定/L3 REST 4 并发全量重拉）；SSE 事件类型分布 MessagePartDelta 817 条 |
| `docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md` | ✓ | 中文 | 最终审计报告（4+1 路交叉验证矩阵/P0-1~P3-25 统一分级/因果链 ASCII 图/三批修复路线图）；多源确认★标记体系 |
| `docs/research/audit-2026-08-10/R-revalidation.md` | ✓ | 中文 | #36-#39 复测（日志风暴 6652→110 条/60x；冷启动 3364ms；NetTrace DEBUG 门控） |
| `docs/research/audit-2026-08-10/R2-regression.md` | ✓ | 中文 | #40-#43 回归（超长消息滚动失效发现→即 #64 前身；SSE 256KB 边界表述再现） |
| `docs/research/audit-2026-08-10/R3-regression.md` | ✓ | 中文 | #34 同 URL 拒绝 + mergeSortedMessages 回归；sameBackend 归一化；SSE 20s 重连周期记录 |
| `docs/research/audit-2026-08-10/R4-sse63.md` | ✓ | 中文 | #63 SseClient 超长行验证（旧 256KB abort 已消除，新丢弃行为 >512KB 未触发） |
| `docs/research/audit-2026-08-10/RG-regression.md` | ✓ | 中文 | #36-#39 UI 功能回归报告（RG-01~13）；记录 SSE 单行 262144 bytes abort（与 R4 的 >512KB 丢弃两个阈值表述）；「OC Beacon UI 不提供删除/重试消息」产品决策记录 |
| `docs/research/audit-2026-08-10/metrics/RP-comparison-table.md` | ✓ | 中文 | 性能对照表（修复前后 4 场景；janky 88.9%→94.6% 持平但 99th -38%、GC 9→0） |
| `docs/research/audit-2026-08-13-dimensions/REPORT.md` | ✓ | 中文 | 第二期 13 维度审计（D1-D13 维度编号体系/D2-01~D2-L68+N2 备注/5 路子代理交叉验证）；High：SSE 阻塞读挂死/V2 part id 契约错位/pendingInputs 并发/dataSync 6h 时限 |
| `docs/research/audit-2026-08-13-memory-perf/REPORT.md` | ✓ | 中文 | 第一期内存/性能审计（C-1/H-1~H-7/M-1~M-16/L/N 系列）；WebView 三泄漏已修（c0c74a4c）；热视图无上限/装箱风暴/双写放大术语 |
| `docs/research/crash-2026-08-14-completion-handler-beta.md` | ✓ | 中文 | CompletionHandlerException 崩溃报告（R8 反混淆实证/runCatchingCancellable 根因修复 #128）；mapping.txt 反混淆术语 |
| `docs/research/issue-1-v1-connect-speed-2026-08-21.md` | ✓ | 中文 | Issue #1 调研：V1 连接慢的真相（beta.4 误判假象 + 串行预加载结构开销）；preLoadSessions/syncFromRest/双探测/探测结果复用术语；实测时延表 |
| `docs/research/question-module-audit-2026-08-14.md` | ✓ | 中文 | 问题模块分支逻辑审计（46 分支矩阵/3 bug）；问题卡片/自定义三态/Q tabs/pager 草稿术语；Bug#2 即 #126 远页草稿 |
| `docs/research/session-status-sync-investigation.md` | ✓ | 中文 | 会话状态同步调研（三层状态源/directory=null 根因/SessionStatusManager FSM——该类后被移除，本报告是其历史）；2026-06-30 仍自称 oc-remote；StreamingStateTracker 死代码记录 |
| `docs/research/sse-scroll-stability-iron-laws.md` | ✓ | 中文（标题英文） | SSE 滚动稳定性权威文档（8 条铁律+回归历史 v1-v6）；铁律 5=streamingMsgId 只看 completed 时间戳（CONTEXT.md「流式 turn」词条的行为依据）；指纹缓存/签名缓存/LazyLayoutCacheWindow 术语体系 |
| `docs/research/vision-mcp-survey.md` | ✓ | 中文 | 视觉 AI 工具/MCP 调研（面向 DSH 接入，13 候选）；429/滑动窗口/RPM-RPD 术语；非 OC Beacon 领域（DSH 工具选型） |
| `docs/simulator-walkthrough-v1v2.md` | ✓ | 中文 | #83 版本探测修复走查清单；ApiVersionDetector/version 交叉验证/pid 特征/NonJsonResponseException/apiVersion 分发 15+ 文件；含执行记录与截图证据链 |
| `docs/specs/2026-08-21-error-report-github-design.md` | ✓ | 中文 | GitHub 错误上报 spec（device flow 8 位码/双轨指纹 fp:err:/fp:crash:/查重评论追加/install-id 24h 防刷/R8 mapping 留存前置）；日志分级审计 583 处结论；24 条 User Stories 英文标题 |
| `docs/specs/2026-08-21-in-session-audio-feedback-design.md` | ✓ | 中文 | 会话内提示音 spec（策略完全镜像系统通知/InSessionFeedbackPlayer/SoundPlan 纯函数/错误 streak 状态机/静音矩阵 Q12）；事件域：TURN_COMPLETE/PERMISSION/QUESTION/ERROR 四类 FeedbackType |
| `docs/specs/2026-08-21-queue-drain-state-compensation-design.md` | ✓ | 中文 | 堆积队列状态补偿 drain spec（#176 TOCTOU/#177 滞留；三触发器 T1 心跳/T2 入队/T3 Idle 观察；drainIfIdle；at-least-once）；PendingMessagePipeline/pendingInputs 术语 |
| `docs/specs/2026-08-21-terminal-component-swap-design.md` | ✓ | 中文 | 终端换件 spec #189（termlib→Termux terminal-view/emulator vendored，Apache 2.0 例外条款核验/TerminalSessionBridge 4 方法耦合面/vim 试金石）；解除了 C14 的部分担忧（Termux 换件有明确法律与架构依据） |
| `docs/specs/2026-08-23-fab-swipe-hide-design.md` | ✓ | 中文 | 双 FAB 滑动隐藏 spec #192（D1-D8 决策/FabEdgeTab 拉杆/会话级不落盘/两段式收拢）；ChatScrollBottomFab/ChatFabMenu |
| `docs/ui-conventions.md` | ✓ | 中文 | UI/主题令牌规范（Alpha 7 常量/Spacing 6 档/Shape/Motion/Button/ListItem/Sheet 0.75f）；Markdown 表格两端一致性公式；与 README 令牌清单互证 ✓；无 API 域术语 |
| `docs/v1-v2-differences.md` | ✓ | 中文 | V1/V2 差异权威表（prompt_async→prompt/abort→interrupt/revert staged/summarize→compact/auth→credential/SSE 格式/端口 4096→4199）；:69 明确 background=「转为后台继续运行」非「创建后台任务」；术语密度最高文档之一 |
| `docs/verification-requirements.md` | ✓ | 中文 | 强制验证框架（维度 1/2/2b/2c/3/4/5；冒烟 10 flow 清单；维度 5 排在维度 4 之前）；:3,:316 仍自称「OC Remote 项目」——产品名残留；:170 要求 Log.i/Log.w 与 AGENTS.md AppLogger 规则措辞冲突 |
| `docs/verification-simulator-perf.md` | ✓ | 中文 | 性能量化观测方案（GC/分配量/内存/落盘写量）；术语热视图上限 1000 条、ToolSnapshotCache LRU 200、增量落盘（delta 文本）、归档存档；含执行记录 |
| `README.md` | ✓ | 中文（标题/emoji 英文混排） | fork 宣传文档；含过期功能描述（跨服务器收藏已删、会话分类已改标签）——见失实注释表 |
| `CHANGELOG.md` | ✓ | 中文（部分段落乱码） | 稳定版变更记录；1.x 历史段 112-126 行编码损坏（GBK mojibake）；turn/红点/标签迁移术语富集 |
| `AGENTS.md` | ✓ | 中文（术语/代码名英文） | 顶层规则源；「单一真相源」一词三用（SessionStateService/version.properties/build.gradle.kts）；「流式消息」是 CONTEXT.md _Avoid_ 词 |
| `RELEASE_NOTES.md` | ✓ | 中文 | dev 版发版说明（0.3.1-dev.21，2026-08-23）；GitHub 错误上报术语与 specs 一致 |
| `backlog.md` | ✓ | 中文 | 未决卡片清单 + P0-P3/Tag/状态流转约定；卡片实际使用 Tag 超出表列 8 种（文档显式允许新增）；领域词密集（pending-input/zombie guard/jumpLockActive/水位线） |
| `02-domain-layer.md` | ✗ | 中文 | 并行盘点产物（另一 agent 的 domain 层输出），非项目文档，不精读 |
| `scripts/backlog-check.sh` | ✓ | 中文 | backlog.md 六项机械不变量校验（[x] 零残留/编号计数器/悬空链接/archive 引用/P0-P3 节序/行数警告 250）；流程术语与 backlog.md 一致 |
| `scripts/backlog-new-batch.sh` | ✓ | 中文 | 创建 journal 批次文件（模板+kebab 名）；「卡片/批次/验收/迁移」流程术语与 AGENTS.md 一致 |
| `scripts/ci-clean-local-settings.ps1` | ✓ | 中文 | 上者的 PS 翻译；UTF-8 无 BOM 写回；「未经运行试验」警告 |
| `scripts/ci-clean-local-settings.sh` | ✓ | 中文 | CI 删除 gradle.properties 本地设置（systemProp.*/org.gradle.java.home）——与 AGENTS.md:51「注释 4 行 systemProp.*」互证 |
| `scripts/ci-decode-keystore.ps1` | ✓ | 中文 | 上者 PS 翻译；「未经运行试验」警告 |
| `scripts/ci-decode-keystore.sh` | ✓ | 中文 | base64 keystore→release.keystore+signing.properties；键名 keystore/keystore.alias/keystore.password 与 app/build.gradle.kts:50-56 互证 ✓ |
| `scripts/ci-determine-flavor.ps1` | ✓ | 中文 | 上者的 PS 翻译；「未经运行试验」警告；同样的 1.x 过期示例 |
| `scripts/ci-determine-flavor.sh` | ✓ | 中文 | tag→flavor 推导；注释示例 v1.0.3/v1.0.4-beta.1 仍用已清理的 1.x 版本号（与 release.yml:28-31 同款过期示例） |
| `scripts/ci-extract-version.ps1` | ✓ | 中文 | 上者的 PS 翻译；头部「⚠️ 未经运行试验」警告模式 |
| `scripts/ci-extract-version.sh` | ✓ | 中文 | 从 version.properties 提取 VERSION_NAME/CODE 写 GITHUB_OUTPUT；无领域术语 |
| `scripts/ci-release.ps1` | ✓ | 中文 | 上者 PS 翻译；「未经运行试验」警告 |
| `scripts/ci-release.sh` | ✓ | 中文 | gh release 创建/更新；RELEASE_NOTES.md 优先、--generate-notes 回退；prerelease=非 stable |
| `scripts/ci-rename-apk.ps1` | ✓ | 中文 | 上者 PS 翻译；GitHub Actions ::error:: 注解 |
| `scripts/ci-rename-apk.sh` | ✓ | 中文 | APK→release-apks/oc-beacon-<version>.apk；版本示例 0.3.0-beta.3（现代格式，与 ci-determine-flavor 的 1.x 旧例形成对照） |
| `scripts/i18n-check.ps1` | ✓ | 中文 | 上者 PS 翻译（无「未经运行试验」警告——唯一例外，仅此 ps1 未带该头部） |
| `scripts/i18n-check.sh` | ✓ | 中文 | i18n 三项检查（key 完整性/英文源纯净 CJK 检测/占位符一致）；头部注明「替代已移除的 lokit 工具」；:16 显式说明不启用 -e 的原因 |
| `scripts/miui-install.sh` | ✓ | 中文 | MIUI/HyperOS 无人值守装包（pm install + uiautomator 点确认弹窗）；按钮文案仅匹配 zh（注释自带语言限制说明） |
| `scripts/perf-quick.ps1` | ✓ | 中文 | 上者 PS 翻译（多采集 MissVS 指标） |
| `scripts/perf-quick.sh` | ✓ | 中文 | 会话列表滑动性能快速测量（adb 直驱）；指标 janky/p50-p99/InpLat/SlowUI；:49「与真机脚本一致」的滑动参数待与 maestro yaml 核对 |
| `scripts/perf-session-scroll.ps1` | ✓ | 中文 | 上者 PS 翻译 |
| `scripts/perf-session-scroll.sh` | ✓ | 中文 | Maestro 驱动完整性能流程（构建+安装+启动+逐轮）；引用 maestro/perf-session-scroll.yaml；applicationId 映射注释与 build.gradle.kts 互证 ✓ |
| `scripts/release.ps1` | ✓ | 中文 | release.sh 的 PowerShell 翻译版，头部自declared「未经运行试验」警告；Test-VersionGt 注释自认未调用；breaking 分类入 Removed 段与 sh 一致 |
| `scripts/release.sh` | ✓ | 中文 | 一键发版脚本（bump 推导→version.properties→CHANGELOG/RELEASE_NOTES→tag+push）；术语 flavor/bump/tag/正式版/预发布；含死代码残留（:131-142 首轮循环输出重定向 /dev/null；:77 version_gt 定义未调用） |
| `scripts/type.sh` | ✓ | 中文 | 真机 E2E ASCII 打字脚本（keyevent 逐键）；注释引 docs/real-device-testing.md E2E 纪律（禁 input text）；默认 serial e69a99d8 |
| `.github/workflows/release.yml` | ✓ | 中文 | CI 发版管线（i18n 检查→凭据注入→lint 门禁→构建→R8 mapping 留存→Release）；tag→flavor 示例仍用已清理的 1.x 版本号 |
| `maestro/README.md` | ✓ | 中文（命令英文） | Maestro 运行说明（l1-l5 分层/e2e 两档标准/冒烟 10 flow 清单——与 verification-requirements §2c 逐项一致 ✓）；「全部 flow（32 个）」与实际 33 个 yaml 计数不符（小漂移） |
| `maestro/e2e-chat-flow.yaml` | ✓ | 英文注释 | E2E 聊天流（真实服务器；硬编码项目路径 D:/Develop/code/app/oc-beacon 与中文会话名「系统问题分析与修复方案」；Scroll to bottom FAB 断言） |
| `maestro/e2e-file-viewer-annotation.yaml` | ✓ | 英文注释 | E2E 标注全流程（Annotate/Modification note/Confirm/Submit/Send；WebView DOM 不可达用坐标长按；panel_toggle id） |
| `maestro/e2e-full-journey.yaml` | ✓ | 英文注释 | E2E 完整旅程主流程（12 步：setup→connect→sessions→chat→settings→auto-approve） |
| `maestro/e2e-large-file-pagination.yaml` | ✓ | 英文注释 | E2E 大文件分页（500 行初始→200 行 loadMore 注释；viewer_load_more_indicator id） |
| `maestro/e2e-md-preview-toggle.yaml` | ✓ | 英文注释 | E2E Markdown 预览切换（Flow 25；Show rendered preview/Show source code；workspace_search_* id 族） |
| `maestro/e2e-phase234-combined.yaml` | ✓ | 英文注释 | Phase2-4 合并流（目录展开/MD 切换/大文件分页；状态污染防护 Cancel 先行） |
| `maestro/e2e-phase234-coord.yaml` | ✓ | 英文注释 | Phase2-4 坐标版（纯 point 点击无文本匹配——语言无关的替代方案样本） |
| `maestro/e2e-phase234-features.yaml` | ✓ | 英文注释 | Phase2-4 特性版（annotation/搜索；onFlowStart 延续状态） |
| `maestro/e2e-rotation-restoration.yaml` | ✓ | 英文注释 | E2E 旋转恢复（rememberSaveable Phase 4 验证；setOrientation；AGENTS.md 打开） |
| `maestro/e2e-server-setup.yaml` | ✓ | 英文注释 | E2E 服务器配置（Add Server 表单填写；${OPENCODE_SERVER_PASSWORD} env 注入；10.0.2.2:4096） |
| `maestro/e2e-session-list.yaml` | ✓ | 英文注释 | E2E 会话列表（quiet-island/mighty-star slug 断言——服务器数据硬编码） |
| `maestro/e2e-settings-flow.yaml` | ✓ | 英文注释 | E2E 设置流（Auto-approve rules/No saved auto-approve rules 断言） |
| `maestro/e2e-tool-card-view.yaml` | ✓ | 英文注释 | E2E 工具卡查看（Flow 24；tool_card_open_file resource-id；注明 maestro 不支持 Unicode 输入与 textRegex 误命中陷阱） |
| `maestro/e2e-workspace-file-tree.yaml` | ✓ | 英文注释 | E2E 文件树（View Workspace 菜单→AGENTS.md） |
| `maestro/e2e-workspace-git-changes.yaml` | ✓ | 英文注释 | E2E Git 变更面板（panel_toggle 切换） |
| `maestro/e2e-workspace-search.yaml` | ✓ | 英文注释 | E2E 工作区搜索（Flow 22；文件树搜索+Git 过滤双场景） |
| `maestro/l1-app-launch.yaml` | ✓ | 英文注释 | L1 启动冒烟（clearState 启动→OC Beacon/Add Server 断言）；UI 文案依赖：OC Beacon/Add Server |
| `maestro/l1-app-stability.yaml` | ✓ | 英文注释 | L1 稳定性（上下滑动渲染） |
| `maestro/l1-connection-error.yaml` | ✓ | 英文注释 | L1 无服务器渲染验证（连接错误场景弱化为空态断言） |
| `maestro/l1-crash-recovery.yaml` | ✓ | 英文注释 | L1 崩溃恢复（stopApp→重启） |
| `maestro/l1-home-screen.yaml` | ✓ | 英文注释 | L1 首页断言（No servers configured 空态） |
| `maestro/l2-session-archive-filter.yaml` | ✓ | 英文注释 | L2 归档过滤（Archived chip 切换）——archived 会话状态词的 UI 入口 |
| `maestro/l2-session-load-more.yaml` | ✓ | 英文注释 | L2 会话列表分页（下滑触发 pagination） |
| `maestro/l2-session-search.yaml` | ✓ | 英文注释 | L2 会话搜索（Search sessions... 输入 test）；大量 optional: true 防无服务器失败 |
| `maestro/l3-compaction-banner.yaml` | ✓ | 英文注释 | L3 压缩横幅（CompactionBanner 渲染不崩溃）；实际未断言 banner 本体（弱验证） |
| `maestro/l3-step-progress.yaml` | ✓ | 英文注释 | L3 步骤进度指示器（multi-step agent execution 注释）；实际仅导航稳定性检查 |
| `maestro/l3-tool-progress.yaml` | ✓ | 英文注释 | L3 工具进度卡（ToolProgressCard 注释）；实际仅到会话列表 |
| `maestro/l4-chat-ui.yaml` | ✓ | 英文注释 | L4 聊天 UI 组件（导航至会话列表即止——弱于名称暗示） |
| `maestro/l5-token-permission.yaml` | ✓ | 英文注释 | L5 token 权限设置（Auto-approve rules 滚动验证；16 次下滑 best-effort；注明 Maestro swipe 对 Compose LazyColumn 无效，由 androidTest PermissionRulesSectionTest 补） |
| `maestro/perf-session-scroll.yaml` | ✓ | 中文注释 | 会话列表滑动性能流程（APP_ID env 注入）；依赖中文 UI「会话/搜索会话」——与其余 flow 的英文依赖形成语言分裂；固定 5+5 滑动 200ms/300ms |
| `maestro/regression-unread-chain-a.yaml` | ✓ | 中文注释 | 回归 A：发送→早退→红点出现（turnEnd ts > markRead ts 断言注释；SESSION_NAME env；UnreadDiag logcat） |
| `maestro/regression-unread-chain-b.yaml` | ✓ | 中文注释 | 回归 B：消费红点→重启持久化（DataStore 不回闪）；场景 A/B 即 unread spec 验收用例 |
| `maestro/terminal-smoke.yaml` | ✓ | 英文注释 | 终端冒烟（tags 含 termlib——#189 换件 Termux 后过期；注明用文本匹配非 testTag、CI 跳过） |
| `maestro/util-has-server.yaml` | ✓ | 英文注释 | 服务器存在性检测子流程（Connect 按钮探测模板）；extendedWaitUntil 优雅降级约定来源 |
| `app/build.gradle.kts` | ✓ | 中文为主，少量英文 | 构建单一真相源；flavor=channel 维度、dev 时间戳 versionCode、GitHub App 凭据注入、lint 门禁；与 AGENTS.md/release.yml 互相印证（compileSdk 37/targetSdk 36/BOM 2026.05.01 均与 README 一致） |
| `build.gradle.kts` | ✓ | 中文（1 行注释） | 顶层构建文件；无领域术语，插件版本清单（AGP 9.3.0/Kotlin 2.3.21） |
| `settings.gradle.kts` | ✓ | 无注释 | rootProject.name="OC Beacon"；jitpack 仓库 |
| `app/proguard-rules.pro` | ✓ | 混合 | R8 保留规则；头部 2-3 行与 5-7 行完全重复；术语 SSE drift compensation/ConnectBot termlib/zstd |

### 仅统计（docs/journal 35 文件，历史档案）

docs/journal/：**35 个批次执行记录**（2026-08-06 ～ 2026-08-23 + undated-p2-assorted），按纪律仅统计文件数、不精读不改写；文件名携带 session/scroll/question/queue/audio 等领域词根，已计入 coverage 台账。

- `docs/journal/2026-08-06-cleanup-refactor.md` ○
- `docs/journal/2026-08-06-play-compliance.md` ○
- `docs/journal/2026-08-08-question-component-improvement.md` ○
- `docs/journal/2026-08-10-audit-f-p0.md` ○
- `docs/journal/2026-08-10-audit-f-p1.md` ○
- `docs/journal/2026-08-10-audit-f-p2.md` ○
- `docs/journal/2026-08-11-emulator-findings.md` ○
- `docs/journal/2026-08-12-menu-walkthrough.md` ○
- `docs/journal/2026-08-13-v1v2-detection-fix.md` ○
- `docs/journal/2026-08-14-cross-dimension-audit.md` ○
- `docs/journal/2026-08-14-question-module-audit.md` ○
- `docs/journal/2026-08-15-chat-flow-bugs.md` ○
- `docs/journal/2026-08-17-question-card-e2e-final.md` ○
- `docs/journal/2026-08-18-emulator-reverify.md` ○
- `docs/journal/2026-08-19-emulator-acceptance.md` ○
- `docs/journal/2026-08-19-final-regression.md` ○
- `docs/journal/2026-08-20-drawer-height-75.md` ○
- `docs/journal/2026-08-20-jump-overlay-round5.md` ○
- `docs/journal/2026-08-20-perf-monitoring-round3.md` ○
- `docs/journal/2026-08-20-queue-todo.md` ○
- `docs/journal/2026-08-20-quick-jump-round4.md` ○
- `docs/journal/2026-08-20-scan-round2.md` ○
- `docs/journal/2026-08-20-scroll-jank-investigation.md` ○
- `docs/journal/2026-08-20-scroll-jank-round2-120hz.md` ○
- `docs/journal/2026-08-20-scroll-stability.md` ○
- `docs/journal/2026-08-21-arch-review-deepening.md` ○
- `docs/journal/2026-08-21-error-report-github.md` ○
- `docs/journal/2026-08-21-in-session-audio-feedback.md` ○
- `docs/journal/2026-08-21-issue1-v1-speed.md` ○
- `docs/journal/2026-08-21-p1-p2-dev-batch.md` ○
- `docs/journal/2026-08-21-race-audit-round6.md` ○
- `docs/journal/2026-08-22-ui-batch.md` ○
- `docs/journal/2026-08-23-acceptance-closeout.md` ○
- `docs/journal/2026-08-23-issue-cleanup-triage.md` ○
- `docs/journal/undated-p2-assorted.md` ○

### 范围外命中（glob 命中但不在盘点范围）

| 路径 | 理由 |
|---|---|
| `CONTEXT.md` | 术语表本体，仅作参考基准，不在盘点范围 |
| `02-domain-layer.md` | 并行盘点产物（另一 agent 输出），非项目文档 |
| `.scratch/terminology/inventory/01~06-*.md` | 本次盘点工作的兄弟输出文件（5 个），非项目文档 |
| `app/src/main/java/com/termux/LICENSE-termux-app.md` | vendored 第三方（termux）许可文档 |
| `app/src/main/java/com/termux/VENDORED.md` | vendored 第三方（termux）来源说明 |

## 术语观察

| 概念 | 观察到的变体 | 位置 文件:行 | 与 API 词一致? |
|---|---|---|---|
| 会话（session） | 会话、Session（`SessionStateService`/`SessionStateFSM`）、**对话**（dialogue-e2e 文档标题）、SessionStatusManager（已移除旧名） | AGENTS.md:20-21,65 | session ✓ API 原词；「对话」为中文 UI 层变体 |
| 消息（message） | 消息、assistant 消息、**流式消息**、token 批处理 | AGENTS.md:106,110 | message/assistant ✓ API 原词；「流式消息」是 CONTEXT.md「流式 turn」词条的 _Avoid_ 词 |
| SSE 事件（event） | SSE 事件、SSE 事件流、「89 SSE 事件」 | AGENTS.md:22,25 | event ✓ API 原词 |
| 端点（endpoint） | 端点、「129 端点」、API 参考 | AGENTS.md:25 | endpoint ✓ API 原词 |
| 会话状态机（FSM） | 纯函数 FSM、`SessionStateFSM`、会话状态机、idle/busy/retry、Waiting/Streaming/ToolCalling | AGENTS.md:65 | 项目内部概念（对应 CONTEXT.md「必需协作者」词条） |
| 单一真相源 | 单一真相源 ×3：SessionStateService（会话状态）、version.properties（版本号）、app/build.gradle.kts（依赖版本） | AGENTS.md:65,92,140 | 非 API 词；修辞复用，一词多义 |
| 流式（streaming） | 流式、Streaming（活动三态之一）、SSE 流式更新、流式输出 | AGENTS.md:65,106-110 | Streaming 是项目活动态命名，非 OpenCode API 事件名 |
| 工作项（issue） | 工作项、issue、卡片、条目、sub-issue/子工作项 | AGENTS.md:129-134；docs/agents/issue-tracker.md:3-9；docs/agents/skills.md:5 | 流程域术语，非 OpenCode API |
| 术语表（glossary） | 术语表、领域术语表、词汇表、glossary、Domain docs | CONTEXT.md:3；docs/agents/domain.md:7,25 | 同物四名（规则源自身不统一） |
| 诊断（Diagnostics） | Diagnostics 屏幕、AppLogger、应用内诊断 | AGENTS.md:66 | UI 文案域；Diagnostics 为屏幕名 |
| 真机/模拟器 | 真机（real device）、模拟器（emulator）、小米 houji（serial e69a99d8） | AGENTS.md:101-102 | 测试域术语 |
| ADR | ADR、Architecture Decision Record、docs/adr/、ADR-0007（示例） | docs/agents/domain.md:8,18,31 | 流程域；目录现状为空 |
| 工作区（workspace） | 工作区浏览器、工作区、文件树、Git 变更面板、代码查看器、Diff 查看器 | README.md:24-32 | workspace ✓ API 原词（/file、/project 端点族） |
| 工具调用（tool） | 工具卡片、`ToolCardResolver`、ApplyPatch/WebSearch/WebFetch/Glob/Task、Read/Edit/Write | README.md:32,38-40 | tool ✓ API 原词（OpenCode 工具名） |
| token 用量 | Token 用量卡片、消息元数据、上下文详情对话框、缓存命中率 | README.md:42-46 | tokens ✓ API 原词（message.tokens 载荷） |
| turn | turn 级跨度、turnGroups、renderableTurns、**回合**分割线、跳转目标（jumpTargets） | CHANGELOG.md:13,74,85,86 | turn 为项目规范词（CONTEXT.md「流式 turn」）；中文变体「回合」 |
| 未读红点 | 未读红点、红点、一键已读、已读位置、水位线（globalMax）、maxCompleted、unreadBaseline（已删）、基线 | CHANGELOG.md:35-50,96-98；backlog.md:96-98 | 对应 CONTEXT.md「红点时钟域」词条；只消费服务器完成时刻语义一致 ✓ |
| 跳转稳定 | **滚动锚定锁**（README）、jumpLockActive、jumpTargets、跳转 | README.md:71；backlog.md:80-82；CHANGELOG.md:74 | CONTEXT.md canon「跳转稳定窗口」；「跳转锁/jump lock」是词条 _Avoid_ 词（指 autoLoad 抑制另一概念） |
| 会话标签 | 会话分类、标签、tag、category（代码已删）、内置收藏标签 | README.md:54-55；CHANGELOG.md:56,64-73 | tag 为客户端本地实体，非 OpenCode API 域 |
| 消息投递状态 | 乐观发送、三态徽章、发送中/已发送/失败、自愈同步 | README.md:57-63 | UI 文案域；message ✓ 但三态为客户端状态 |
| 斜杠命令 | 斜杠命令、/new、/fork、/compact、/share、/rename、/undo、/redo、/shell | README.md:124 | /new /compact /share 等为 OpenCode 命令名 ✓；部分为客户端扩展 |
| 僵尸防护 | zombie guard、僵尸防护 | backlog.md:66 | 对应 CONTEXT.md「必需协作者」词条内概念 ✓ |
| 门面/seam | 门面（facade）、7 门面 78 处 if 分发、seam、god-client | backlog.md:100-101 | 对应 CONTEXT.md「版本 seam」词条 ✓ |
| 产品名 | OC Beacon、OC Remote、oc-remote、ocbeacon、Beacon | README.md:1-11 | 非域名词；全库统一 OC Beacon |
| flavor/渠道 | flavor（dev/beta/stable）、渠道、dimension="channel"、GitHub 分发渠道 / Google Play 渠道 | app/build.gradle.kts:63-90；.github/workflows/release.yml:7,28-34 | 构建/分发域；同轴双名 flavor/channel |
| 版本号真相源 | 「版本号唯一来源」（gradle 注释）vs「版本号唯一真相源」（AGENTS.md） | app/build.gradle.kts:4；AGENTS.md:92 | 同一断言两种措辞 |
| 终端（terminal） | 终端模拟器、ConnectBot（termlib）、libvterm、Termux（vendored 路径 com/termux）、PTY | app/build.gradle.kts:197；app/proguard-rules.pro:50；README.md:117 | terminal ✓ API 原词（/terminal 端点）；实现栈多名混用 |
| 归档桶 | 归档桶（zstd 压缩）、archive | app/build.gradle.kts:227-229 | 对应 docs「B-data-pagination-archive」域；非 API 原词 |
| 漂移补偿 | SSE drift compensation、漂移补偿 | app/proguard-rules.pro:45-46；README.md:70 | 渲染域用词一致 ✓ |
| 诊断日志 | 诊断日志（Room 存储）、Diagnostics | app/build.gradle.kts:221；AGENTS.md:66 | UI/日志域一致 ✓ |
| 发版域词汇 | 发版、bump（major/minor/patch）、正式版/预发布、tag（v 前缀）、dry-run、conventional commits（feat/fix/perf/refactor…） | scripts/release.sh:6-18,76-100 | 流程域一致用词；「bump/递进」同义混用 |
| 发版产物 | RELEASE_NOTES.md 草稿、CHANGELOG.md（仅 stable）、GitHub Release、release keystore 签名 | scripts/release.sh:17,455-457；release.ps1:29,543-545 | 与 docs/release-workflow.md 域一致（待读核对） |
| PS 脚本警告模式 | 「⚠️ 未经运行试验，使用前请 review」头部声明 | scripts/ci-extract-version.ps1:5、ci-determine-flavor.ps1:5、ci-clean-local-settings.ps1:5（及其余 ps1） | 流程约定：PS 版=未验证翻译版 |
| backlog 流程词 | 卡片、批次、完结、迁移、验收、计数器、机械不变量 | scripts/backlog-check.sh:3-6；backlog-new-batch.sh:37-38 | 与 backlog.md/AGENTS.md 三方一致 ✓ |
| 性能指标词汇 | janky 率、p50/p90/p95/p99 分位数、InpLat（High input latency）、SlowUI、MissVS、gfxinfo 帧统计 | scripts/perf-quick.sh:55-93；perf-session-scroll.sh:129-173 | 测试/性能域；中英混用但缩写一致 |
| i18n 域词汇 | 英文源（values/）、语言文件（values-*）、key 完整性、孤儿 key、占位符（%1$s）、全角标点、lokit（已移除工具名） | scripts/i18n-check.sh:3-14 | 流程域一致 ✓；README:120 称 15 语言 |
| 签名域词汇 | keystore（release.keystore）、alias、signing.properties、KEYSTORE_BASE64/Alias/Password secrets、debug 签名回退 | scripts/ci-decode-keystore.sh:3-9；release.yml:64-70 | 与 AGENTS.md 签名节/real-device-testing 互证 |
| 仓库接口族 | 14 repository（Agent/Chat/Draft/File/Mcp/Provider/Server/ServerConfig/ServerConnection/Session/SessionState/Settings/Terminal/Vcs）；API 拆分 SessionApi/MessageApi/FileApi/TerminalApi/ProviderApi/SystemApi | docs/architecture.md:12,16 | session/message/file/terminal/provider ✓ API 原词；Draft/Vcs 为客户端域 |
| 事件处理器 | 10 个 handler（Session, Message×4, SessionNext, Permission, Question, Misc）、EventDispatcher、SseEvent | docs/architecture.md:20 | session/message/permission ✓ API 事件名；SessionNext 为 V2 事件变体 |
| 连接生命周期协调 | ConnectionLifecycleCoordinator（连接生命周期协调）、OpenCodeConnectionService（FGS adapter）、SseConnectionManager（指数退避）、registry 真相源 | docs/architecture.md:28-33 | 对应 CONTEXT.md「连接生命周期协调」词条 ✓；_Avoid_「Service 管连接」与本描述一致回避 |
| 渲染供给 | RenderSupplyCoordinator（渲染供给：视口预解析/分片时机决策）、显式注「术语见根目录 CONTEXT.md」 | docs/architecture.md:38-40 | 对应 CONTEXT.md「渲染供给」词条 ✓（唯一直接引用 CONTEXT.md 的文档） |
| 红点三铁律 | maxCompleted 只增不减、连接停止≠会话删除（clearForServer）、markSessionIdle 客户端 now 解耦、recomputeMaxCompleted、persistLastCompletedReplyTime 同步落盘、Message.time.completed | docs/architecture.md:80-88 | 对应 CONTEXT.md「红点时钟域」词条 ✓；completed/time 为 API 载荷词 ✓ |
| 会话状态域 | SessionStateService（idle/busy/retry + Waiting/Streaming/ToolCalling）、SessionStateFSM 穷举转移矩阵、staleness/REST 恢复循环、SessionStatusManager（已移除） | docs/architecture.md:68-70 | FSM 内部概念；与 AGENTS.md:65 一致 ✓ |
| 终端域 | TerminalTabState（5 态枚举，非 Boolean）、ServerTerminalWorkspace/Registry、TerminalEmulator、PtySocket、connectbot、WebSocket PTY | docs/architecture.md:44；architecture-debt.md:12,21 | terminal ✓ API 原词；实现栈 ConnectBot 与 README「类 Termux」并存 |
| 工具调用卡 | 工具调用可展开卡片（tools/ 子包） | docs/architecture.md:45 | tool ✓；与 README「可插拔工具卡片」同义 |
| 消息变体（隐私文档） | chat messages and prompts（聊天消息与提示词）、prompts | docs/PRIVACY_POLICY.md:12,81 | prompt 为 OpenCode 命令/消息域近义词；与 message 混用 |
| 通知域 | 前台服务（data sync）、assistant replies、pending permission requests、通知抑制（SessionNotificationCoordinator） | docs/PRIVACY_POLICY.md:36-38；architecture.md:33 | assistant/permission ✓ API 词 |
| i18n 术语基准 | Tag/标签、Session/会话、Server/服务器（en/zh-CN/ja/ko 四语固定基准）；key 命名 `<模块>_<含义>` | docs/i18n-guide.md:34,50-57 | session ✓ API 原词；表仅 3 行——其余概念未入基准 |
| 设计令牌 | Theme Token System、AlphaTokens（SELECTED/DIFF_BG/FAINT/MUTED/MEDIUM/HIGH/AMOLED）、SpacingTokens（XS-XXL）、SheetTokens.ChatSheetHeightFraction | docs/ui-conventions.md:12-40 | UI 域；与 README:81-90 互证 ✓ |
| Markdown 表格一致性 | cellCap 公式、MIN_CELL=120dp/120px、两端（WebView 文件浏览 vs Compose 主对话流） | docs/ui-conventions.md:50-52 | 渲染域；与 AGENTS.md:116 引用关系 ✓ |
| 调试通道 | 调试通道（Debug Channel）、debug extras（debug_url/username/password/name）、幂等保存、版本探测（apiVersion）、冷启动/热启动、DebugProfile | docs/debug-channel.md:1-55 | 流程域；V1/V2 apiVersion 对应 CONTEXT.md「版本 seam」门控半边 |
| 主对话抽屉 | ModalBottomSheet、TaskSheet/ModelPickerDialog/QuickNavigateSheet/PendingTodoSheet | docs/ui-conventions.md:40 | UI 组件名；「主对话」再次出现对话=chat 语境 |
| 发送消息（prompt） | 发送消息、`prompt_async`(V1 204)→`prompt`(V2 200 Inbox)、回复 reply | docs/v1-v2-differences.md:22；simulator-walkthrough:42,58 | prompt ✓ API 原词；UI/文档层叫「发送消息」——两套词汇并行 |
| 中断 | 中断执行、中断按钮、`abort`(V1)→`interrupt`(V2, +continue 参数) | docs/v1-v2-differences.md:23；simulator-walkthrough:43,59 | interrupt/abort ✓ API 原词；中文层统一「中断」 |
| 回退（revert） | 回退 revert、`/revert`+`/unrevert`(V1)、staged 三步（stage→commit/clear）(V2)、撤销、/undo /redo（斜杠命令） | docs/v1-v2-differences.md:28；README.md:124；CHANGELOG.md:72 | revert ✓ API 原词；「撤销/undo/redo」为近义变体（是否同一概念待裁决） |
| 压缩（compact） | 压缩 summarize、`/summarize`(V1)→`/compact`(V2)、compaction（maestro 用例名） | docs/v1-v2-differences.md:36；README.md:124 | compact ✓ V2 API 原词；V1 词 summarize 仍在差异表并行 |
| 后台任务 | 任务（后台化）、background、转为后台继续运行、synthetic 合成消息注入 | docs/v1-v2-differences.md:24,67-70 | background ✓ API 原词；文档明确定义防歧义 ✓ |
| 表单/提问 | Form 系统（kind=question）、QuestionAsked 提问卡片、question 工具、轮询兜底 | docs/v1-v2-differences.md:30 | question/form ✓ API 词；「提问卡片」为 UI 层 |
| 收件箱 | Inbox/Steering、steer + queue | docs/v1-v2-differences.md:31 | inbox/steer ✓ V2 API 词；无中文定名 |
| 凭据/认证 | auth(V1)→credential(V2)、integration（provider 认证重构）、OAuth 两步→connect 多步、OPENCODE_SERVER_PASSWORD | docs/v1-v2-differences.md:26,37,50-55 | credential/integration ✓ API 词 |
| 版本探测 | 版本探测、ApiVersionDetector、version 交叉验证、pid 特征、apiVersion 分发、V1 过渡形态、0.0.0-next-17403 陷阱 | docs/v1-v2-differences.md:14,20；simulator-walkthrough:11-17 | 对应 CONTEXT.md「版本 seam」门控半边 ✓ |
| 测试环境 | SimServer、V2Real/V1Real、PLK110（OnePlus 真机 serial 3B165D00SX600000）、houji、emulator-5554、Medium_Phone AVD | docs/dialogue-e2e-test-runbook.md:17,120-124,216；e2e-testing-workflow.md:22 | 真机多台（houji/PLK110），real-device-testing.md 仅登记 houji |
| FSM 转移动词 | `Idle --ClientSendParts--> Busy/Waiting`、`--TextStarted--> Busy/Streaming`、`--SseIdle--> Idle [force-complete]`、`--ClientAbort--> Idle` | docs/dialogue-e2e-test-plan.md:92-95,116；runbook:31,130 | FSM 事件名（代码级）；与 idle/busy/Waiting/Streaming 一致 ✓ |
| 本地播种 | 播种（seed/send-seed）、V2 prompt 200 返回 Inbox+本地播种 vs V1 prompt_async 204 无播种依赖 SSE 回显、[seed] session=... cached messages -> memory hot view | docs/dialogue-e2e-test-plan.md:12,88-89；runbook:104,224 | 播种=客户端乐观写入术语（非 API 词）；seed 与热视图（hot view）呼应 |
| SSE 事件名（V2 实测） | session.step.started / session.text.* / session.step.ended / session.execution.succeeded / session.execution.interrupted / session.status / session.next(V1) / session.idle(V1) / message.updated(V1) / server.connected | docs/dialogue-e2e-test-plan.md:101,115；runbook:69,195 | session/message/step ✓ API 事件词；与 v1-v2-differences 事件表互补 |
| 分页游标 | cursor（V2-cursor base64）、游标推进、hasOlder、HotStart/Network 游标态、loadOlder、before= 参数 | docs/dialogue-e2e-test-plan.md:175；runbook:155,228 | 对应 CONTEXT.md「版本 seam」分页半边 ✓ |
| 僵尸域 | 僵尸会话、zombie runner forcing Idle、zombie interrupt sent、/api/session/active running、3 分钟兜底 | docs/dialogue-e2e-test-plan.md:76-78,152-156；runbook:69 | zombie ✓ 与 backlog/CONTEXT.md 必需协作者词条一致 |
| 问题卡片域 | QuestionAsked/QuestionReplied、replyToQuestion、/api/question/request、Pending answer 徽标、多题分页（1/4）、Dismiss/Next/Submit | docs/dialogue-e2e-test-runbook.md:85-91,139 | question ✓ API 词；「问题卡片/提问卡片」两叫法（regression-guide 用提问卡片） |
| Part 类型（12 种） | text/reasoning/file/tool/step-start/step-finish/snapshot/patch/agent/subtask/retry/compaction | docs/opencode-api-deep-research/1-session-message.md:80-97 | part ✓ API 原词权威枚举（术语裁定参照系） |
| 工具状态（ToolState） | pending/running/completed/error 4 态 | docs/opencode-api-deep-research/1-session-message.md:99-103 | tool ✓ API 原词 |
| 会话状态（SessionStatus） | idle/busy/retry 3 态（retry 含 attempt/message/action/next） | docs/opencode-api-deep-research/1-session-message.md:117-125 | idle/busy/retry ✓ 与 FSM 用词一致——FSM 状态名即 API 状态名 |
| ID 前缀体系 | ses_/que_/per_/prj_/msg（MessageID）/WorkspaceID | docs/opencode-api-deep-research/1-session-message.md:30,137,146；3-file-project.md:209 | API 原词 |
| Todo 状态 | pending/in_progress/completed/cancelled + priority high/medium/low | docs/opencode-api-deep-research/1-session-message.md:127-133 | todo ✓ API 词（V2 已移除该端点——v1-v2-differences:29） |
| 分页（API 侧权威） | limit/before 游标、base64url(JSON({id,time}))、Link rel=next、X-Next-Cursor、older(cursor) 排序 | docs/opencode-api-deep-research/1-session-message.md:287-312 | 游标语义权威源（客户端 HotStart/Network 态是其上层） |
| revert 精确语义 | messageID+partID、撤销文件变更、unrevert 恢复、deleteMessage 不回滚文件 | docs/opencode-api-deep-research/1-session-message.md:554-599 | revert ✓；为 C19 提供事实：revert≠undo（undo/redo 非此端点族） |
| 工作区（workspace 权威） | 工作区=项目的「分支视图」、adapter（local/ssh/docker）、warp 迁移、ConnectionStatus | docs/opencode-api-deep-research/3-file-project.md:394,480-505 | workspace ✓；README「工作区浏览器」实为 file/project 域——同名不同物，见 C24 |
| 项目副本 | ProjectCopy、strategy（git-worktree）、实验性 /experimental 前缀 | docs/opencode-api-deep-research/3-file-project.md:297-382 | project ✓ |
| MCP 状态 | connected/disabled/failed/needs_auth/needs_client_registration | docs/opencode-api-deep-research/2-config-provider.md:236-244 | mcp ✓ API 词 |
| 全局事件 | server.connected、server.heartbeat（10s）、sync 事件、global.disposed、installation.updated | docs/opencode-api-deep-research/2-config-provider.md:422-450,520 | SSE 事件名权威源之一 |
| PTY/终端域（API 权威） | PtyID（pty_ ULID）、running/exited、票据 ticket（60s 一次性）、环形缓冲 cursor（-1 跳过历史）、控制帧 [0x00+JSON]、shells（fish/nu deny） | docs/opencode-api-deep-research/4-terminal-control.md:88-156,162-183 | pty ✓ API 原词；客户端「终端/TerminalTabState」为其上层 |
| 后台化（API 权威） | BackgroundJob、type=task、status=running、background.promote(jobID)、experimentalBackgroundSubagents 门控 | docs/opencode-api-deep-research/4-terminal-control.md:920-933 | 与 v1-v2-differences:69「转为后台继续运行」互证 ✓ |
| 事件溯源 | EventV2、aggregateID/aggregate_id（驼峰 vs 蛇形不一致——文档自记）、seq 单调递增、sync 包装 @vN 版本后缀、strictOwner | docs/opencode-api-deep-research/4-terminal-control.md:644-765；5-sse-events.md:1142-1185 | sync 事件域 API 词 |
| SSE 事件生命周期模式 | `*.started` → `*.delta`（瞬时）→ `*.ended`（权威完整值）三段式；text/reasoning/tool.input/compaction 四族 delta | docs/opencode-api-deep-research/5-sse-events.md:1087-1100 | 事件命名模式权威；客户端 MessagePartDelta 对应 message.part.delta/message.updated 双轨 |
| token 结构（API 权威） | `{input, output, reasoning, cache:{read, write}}`、cost、缓存命中率=cache.read/(input+cache.read) | docs/opencode-api-deep-research/5-sse-events.md:436-448,1104-1138 | tokens ✓；README「Token 用量卡片」四分与此一致 ✓ |
| 会话状态事件 | session.status（idle/busy/retry + attempt/action/next 倒计时）、session.idle（DEPRECATED 子集）、session.error（7 种 AssistantError） | docs/opencode-api-deep-research/5-sse-events.md:640-683 | retry.next 倒计时=README「重试跟踪倒计时」依据 ✓ |
| TUI 命令字面量 | session.new/share/interrupt/compact、session.page.up…、prompt.clear/submit、agent.cycle、help.show、model.list、theme.list + 遗留别名（session_new 等） | docs/opencode-api-deep-research/4-terminal-control.md:383-456；5-sse-events.md:1051-1056 | 斜杠命令 /new /compact /share 等的 API 侧对应物 ✓ |
| 压缩事件族 | compaction.started/delta/ended（v1+v2 双版本 schema 并存）、session.compacted 粗粒度、reason=auto/manual | docs/opencode-api-deep-research/5-sse-events.md:605-632,685-691 | compaction ✓ V2 词；maestro l3-compaction-banner 依据 |
| Form 域（V2） | form.created/replied/cancelled、Form.Info（frm_ 前缀）、metadata.kind=question、fields.type=string/multiselect/number/integer/boolean/external、custom=true、stale surface（question.v2.*） | docs/opencode-api-reference-v1.md:1938-2020,3865-3870 | form ✓ V2 API 词；与客户端 QuestionAsked 映射关系文档化 ✓ |
| Token 两层语义 | Message.tokens=覆盖（最后 step）、Message.cost=累加、Session.tokens=SQL 累加（无 total）、step-finish Part、ACP 口径 used=input+cacheRead、revert 减法 | docs/opencode-api-reference-v1.md:4750-4877 | tokens/cache ✓ API 权威语义；客户端统计栏/圆环的数据依据 |
| Part 计数不一致 | §22 数据模型列 13 种（含 abort reason）；deep-research 1:80 称「12 种」 | docs/opencode-api-reference-v1.md:4298-4314；opencode-api-deep-research/1:80 | 两文档对同一联合类型计数不同（12 vs 13） |
| V1 端点中文注解 | 压缩总结（summarize）、回退消息/撤销回退（revert/unrevert）、分叉（fork）、中止（abort）、分享（share）、导入分享会话（import） | docs/opencode-api-reference-v1.md:5062-5100 | 端点总览的中文定名——术语裁定的 API 侧对照表 |
| 认证常量 | 默认用户名 opencode、OPENCODE_SERVER_PASSWORD、?auth_token query、x-opencode-directory header | docs/opencode-api-reference-v1.md:52-64 | 与 AGENTS.md:100/debug-channel 互证（AGENTS 称密码在 service.json 非 env——对象不同：服务端 vs 客户端） |
| 表格换行域 | cellCap、MIN_CELL（120dp/120px）、自然宽、填满放大、SimpleMarkdownTable、overflow-wrap: anywhere / LineBreak.Simple | docs/archive/specs/2026-08-04-markdown-table-wrap-design.md:42-62,150-158 | 渲染域一致 ✓（ui-conventions 引用本 spec） |
| 标注功能 | Annotate（选择菜单注入项） | docs/archive/android-code-viewer-libraries.md:12,74-76 | maestro e2e-file-viewer-annotation.yaml 的功能域词 |
| 红点数据链 | maxCompleted（_lastCompletedReplyTime）、readTimes、allReadAt、一键已读（全局 max）、unreadBaseline（已删）、session_last_reply_time key、SessionDeleted 真删、快照派生 | docs/archive/specs/2026-08-07-unread-derived-state-design.md 全文 | 与 CONTEXT.md「红点时钟域」/architecture.md 三铁律完全一致 ✓ |
| V2 实测事件体系（第三代） | session.input.admitted/promoted、session.execution.started/succeeded、ordinal 定位键、tool part id=call_id、finish=tool-calls/stop、delivery=steer/queue、durable{aggregateID,seq,version} | docs/archive/specs/2026-08-11-v2-contract-alignment-design.md:46-75 | 与 api-reference 的 session.next.*（textID 版）不同代——spec:75 明示「以实测为准」 |
| 状态切片域 | 魔法索引、状态切片、DataInputs/UiInputs、ContentState/ShellState（内容册/外壳册）、WhileSubscribed5s | docs/archive/specs/2026-08-07-session-list-state-slicing-design.md | 客户端架构词；CHANGELOG:41 互证 ✓ |
| 工作空间/工作区（workspace 双译） | 「工作空间」（工作空间文件预览/查看工作空间菜单项）vs「工作区」（工作区浏览器/工作区路由） | docs/archive/specs/2026-06-18-workspace-file-viewer-design.md:1,12；README.md:24 | 同一概念两中文译名并存（+API workspace 第三义，见 C24） |
| FileViewer 数据源 | ToolSnapshot / ToolSnapshotDiff / Live / GitDiff、source 参数、toolPartIds 聚合、NormalizedFilePath | docs/archive/specs/2026-06-18-workspace-file-viewer-design.md:562-572,710-711 | 客户端域模型词 |
| 标注域 | 标注修改（Annotate）、Annotation(index/startChar/selectedText/note)、重新连续编号、结构化文本提交、CustomAnnotationToolbar | docs/archive/specs/2026-06-18-workspace-file-viewer-design.md:421-494 | maestro e2e-file-viewer-annotation 的功能本体 |
| 工具进度桥接 | session.next.tool.progress、ToolProgressInfo.output、Running.output 本地增强、callID 匹配、extractToolOutput | docs/archive/specs/2026-07-02-shell-streaming-and-patchcard-restyle-design.md:46-87 | regression-guide §3.7「实时 output 注入」互证 ✓ |
| SSE 滚动管线（权威） | 48ms delta 批处理（scheduleFlush）、高度补偿（layout{} 仅 streaming message）、双 key LaunchedEffect、requestScrollToItemNoCancel、renderableTurns 内容指纹缓存、turnGroups/jumpTargets 签名缓存、LazyLayoutCacheWindow(1.5/1.5)、GC 分配风暴 | docs/research/sse-scroll-stability-iron-laws.md 全文 | 渲染域术语权威；与 AGENTS.md「SSE 滚动稳定性」互证 ✓（AGENTS 引 4 条，本文 8 条） |
| 流式消息判定 | streamingMsgId 只依赖 message.time.completed==null、禁止 takeIf(sessionMeta.isStreaming)、displayItems turn 代表=oldest 而 streaming=newest | docs/research/sse-scroll-stability-iron-laws.md:60-82,266-268 | CONTEXT.md「流式 turn」词条的直接代码依据 ✓ |
| 三层状态源（历史） | ①eventDispatcher.sessionStatuses ②SessionStatusManager.statusFlow ③message.time.completed/part.time.end；L2/L3/L5 自愈 | docs/research/session-status-sync-investigation.md:15-23,48-52 | ②已被 SessionStateService 取代（architecture.md:70 记已移除） |
| 崩溃排查域 | CompletionHandlerException、R8 混淆/mapping.txt 反混淆、runCatchingCancellable、数据层 117 处 runCatching | docs/research/crash-2026-08-14-completion-handler-beta.md | 流程/工具域词 |
| 连接编排域 | preLoadSessions、syncFromRest、双探测（/api/health→/global/health）、探测结果复用、SPA fallback、过渡形态、activeSessions | docs/research/issue-1-v1-connect-speed-2026-08-21.md:35-44,74-80 | 版本 seam/连接生命周期词条的机制细节 ✓ |
| SSE 超长行双阈值 | 「exceeds 262144 bytes, aborting read」（RG:38 旧 abort）vs「>512KB 丢弃」（R4:21 新行为） | docs/research/audit-2026-08-10/RG-regression.md:36-40；R4-sse63.md:15-21 | 两审计文档阈值/行为表述不一 |
| 回归证据命名 | RG-<编号>-<描述>、bg_*.png、RG/D/E/F/A/B/C 系列、metrics/ 目录 | docs/research/audit-2026-08-10/* | 证据命名规范与 regression-guide §5.1 一致 ✓ |
| 审计分级体系 | P0-P3（08-10 系）vs Critical/High/Medium/Low（两期 REPORT）vs D1-D13（dimensions）；[VERIFY: 文件:行号] | docs/research/audit-2026-08-10/*；audit-2026-08-13-*/REPORT.md | 三套编号体系并存（C31） |
| SSE 心跳域 | 40s 心跳超时（HEARTBEAT_TIMEOUT_MS）、行间检查、半开 TCP、ServerHeartbeat、任意事件刷新 vs 仅 heartbeat、Last-Event-ID、id: 帧行 | docs/research/audit-2026-08-13-dimensions/REPORT.md:45-52,197-211 | SSE 域核心术语（dimensions Top 风险） |
| V2 part id 契约 | derivePartId（msg_ord_N）、REST id="" 契约错位、mergePartsList preserved 双份、ordinal 当 epoch 毫秒误用 | docs/research/audit-2026-08-13-dimensions/REPORT.md:188,215 | part id 是 V2 语义核心冲突点 |
| 状态自愈层级（扩展） | L2 staleness（5s/Busy>15s）、L3 REST validation（activeValidations 去重）、L4 syncFromRest、L5 cross-validation | docs/research/audit-2026-08-10/C-state-events-logging.md:117-125；dimensions:158-160 | L2-L5 层级术语多文档一致 ✓ |
| 性能度量域 | 分配风暴/短命对象海啸、写放大、装箱字节、GC 暂停 1.110s、Skipped 33 frames、janky%、p50-p99、PSS | docs/research/audit-2026-08-10/E-*；audit-2026-08-13-memory-perf/* | 性能审计词汇一致 ✓ |
| 归档域（权威） | 热表 1000 条上限、归档桶 200 条/512KB、zstd、buildArchiveBuckets、latestBefore、坏桶跳过、pruneToLimit | docs/research/audit-2026-08-10/B-data-pagination-archive.md:66-93 | 存储域一致 ✓ |
| 堆积队列域 | 堆积消息（pending message）、PendingMessagePipeline、drain/drainIfIdle、enqueue、TOCTOU、at-least-once、继续发送堆积消息 | docs/specs/2026-08-21-queue-drain-state-compensation-design.md 全文 | 客户端域新概念（堆积=busy 期暂存待发） |
| 提示音域 | 会话内提示音、策略镜像（渠道/RingerMode/DND）、SoundPlan、错误 streak、FeedbackType 四类、静音矩阵 | docs/specs/2026-08-21-in-session-audio-feedback-design.md | 客户端域；turn 结束=TURN_COMPLETE 对应 SessionIdle |
| 上报域 | device flow、8 位授权码、错误指纹 fp:err:/fp:crash:、查重评论追加、install-id、[user-report]、needs-triage、R8 mapping.txt | docs/specs/2026-08-21-error-report-github-design.md | 流程域；与 RELEASE_NOTES.md:5-16 互证 ✓ |
| FAB 域 | 双 FAB（ChatScrollBottomFab/ChatFabMenu）、拉杆 FabEdgeTab、两段式收拢、会话级不落盘 | docs/specs/2026-08-23-fab-swipe-hide-design.md | UI 域；与 backlog #192 互证 ✓ |
| UI 文案域（maestro 依赖面） | OC Beacon/Add Server/Connect/Connected/Sessions/Search sessions.../Archived/Ask a question/Scroll to bottom/View Workspace/Annotate/Settings/Auto-approve rules 等英文文案 | maestro/*.yaml 全部 flow | UI 文案=测试选择器（i18n 英文源）；中文文案仅 perf-session-scroll |
| resource-id 测试标签族 | tool_card_open_file、workspace_search_*、panel_toggle、viewer_render_button、viewer_load_more_indicator、back_button、more_vert | maestro/e2e-tool-card-view.yaml:48 等 | 测试基础设施命名 |
| E2E 环境绑定 | 10.0.2.2:4096、${OPENCODE_SERVER_PASSWORD}、D:/Develop/code/app/oc-beacon、「系统问题分析与修复方案」、quiet-island/mighty-star、Test Server | maestro/e2e-server-setup.yaml:45；e2e-chat-flow.yaml:47,59 | 测试数据硬编码 |
| 红点回归域 | 场景 A（发送→早退→红点出现）/场景 B（消费→重启不回闪）、turnEnd ts > markRead ts、UnreadDiag | maestro/regression-unread-chain-a/b.yaml | 与 unread spec §5 验收标准互证 ✓ |
| BREAKING 分类三方表述 | release-workflow §4.3「Removed 或 Changed 前置 BREAKING」vs 模板:27（固定 Removed）vs 脚本（恒 Removed） | docs/release-workflow.md:179；release-notes-template.md:27；release.sh:136,154 | 三源不完全一致（C16） |
| 签名域细节 | release.keystore/signing.properties 三键、KEYSTORE_* Secrets、DN=CN=OC Beacon、alias oc-beacon（旧名 oc-tether）、Play App Signing、AAB | docs/release-workflow.md:284-322 | 与 real-device-testing/build.gradle.kts 互证 ✓ |
| 可观测 Tag 体系 | ApiVersionDetector/V2Api/SseClient/SseClientV2/LogSessionLoad/ScrollDiag（LEAP/gesture/RESIZE/COMP-*）/AppLogger | docs/observability-verification-guide.md:63-71,129-148 | 日志 tag 代码级术语 |
| 数据库表 | ocbeacon.db、messages（id/created/status）、servers（apiVersion/serverVersion） | docs/observability-verification-guide.md:28-48,157 | 存储域；status/created 为 API 载荷词 |
| 消息状态机 | User: Queued→Sending→Complete；Assistant: (created)→Streaming→Complete/Error | docs/chat-ui-event-lifecycle.md:119-152 | Streaming 与 FSM 活动态同名但属消息级（两层易混） |
| SSE 事件名（UI 生命周期） | MessagePartDelta、session.update、message.create、session.status=idle、error 事件、MessagePartUpdated/StepEnded | docs/chat-ui-event-lifecycle.md:90,148-151；regression-guide.md:198 | part/message/session/step ✓ API 载荷词 |
| 滚动域 | consumeBoundaryScroll、reverseLayout=true、scrollRestoreVersion、autoScrollEnabled、isAssistantContinuation、TurnGroupCalculator | docs/chat-ui-event-lifecycle.md:58-84,164-176 | 渲染域内部术语；turnGroups 一致 ✓ |
| 传播链路 | SseConnectionManager → EventDispatcher.processEvent → MessageEventHandler → _messages → ChatViewModel.combine → ChatUiState → Recomposition | docs/chat-ui-event-lifecycle.md:89-100 | 架构链与 architecture.md 互证 ✓ |
| 验证维度编号 | 三套并行：维度 1/2/2b/2c/3/4/5（4+1 维）· D1-D5 · D0-D4 | docs/verification-requirements.md:31-249；qa-methodology.md:24-35；regression-guide.md:43-53 | 流程域三轨（C21）；qa:34 自带映射表 |
| E2E 分档 | 冒烟测试（Smoke，10 flow）/ 全面+回归（Full）、快速/完整/免回归 | docs/verification-requirements.md:116-154；regression-guide.md:20-32 | 流程域一致 ✓ |
| 12 能力域 | 启动崩溃安全/连接管理/会话列表/发送流/控制/草稿/工具问题卡片/分页滚动/存储/状态事件/i18n 发版/终端工作区 | docs/regression-guide.md:92-222 | 客户端能力域命名（「聊天主界面」再证 chat 混称） |
| 会话状态词 | Working 状态（会话列表项）、idle→busy→retry→idle、forceComplete | docs/regression-guide.md:116,197,201 | Working 与 busy 是否同义待裁决（C22） |
| SSE 事件名（回归域） | MessagePartUpdated / StepEnded、SSE read timeout / cooldown | docs/regression-guide.md:198；verification-requirements.md:164-167 | part/step ✓ API 载荷词 |
| 内存/缓存域 | 热视图上限 1000 条、热表阈值、归档桶解压、ToolSnapshotCache LRU、增量落盘、prune 到 1000、onTrimMemory | docs/verification-simulator-perf.md:34-68；regression-guide.md:180,190 | 存储域一致 ✓ |
| 双文档模式 | 期望文档（plan）+ 实操文档（runbook）、时间点驱动断言、问题归属分类、DYN/STA/SCR | docs/qa-methodology.md:136-155 | 流程域；dialogue-e2e 两文档配套 |


## 失实注释

| 文件:行 | 现注释摘录 | 代码实际行为依据 | 修订方向 |
|---|---|---|---|
| docs/agents/issue-tracker.md:28 | 「`gh` CLI 不走代理，直连使用（**AGENTS.md 约定**）」 | 该约定实际位于 docs/release-workflow.md:269（§7 红线）；AGENTS.md 全文无 gh 代理条款（仅 :51 gradle 代理） | 引注改为「release-workflow.md §7 约定」 |
| docs/agents/domain.md:8,17 | 「`docs/adr/` —— 读与当前工作区域相关的 ADR」 | docs/adr/ 目录在本 worktree 不存在（docs/**/*.md 全枚举无 ADR 文件） | 文档:10 自带静默防护；可在结构图标注「（惰性创建，当前为空）」 |
| README.md:55 | 「**跨服务器收藏**——跨服务器星标会话、统一收藏列表 + 离线快照」列为现存功能 | CHANGELOG.md:73（1.2.0）：「删除跨服务器收藏/标签入口与代码（收藏统一为内置标签）」 | 删除该功能条目或标注已移除 |
| README.md:54 | 「会话分类——自定义名称/颜色/图标标签」 | CHANGELOG.md:56,68,72：category→tag 迁移完成、SessionCategory 代码已删 | 措辞统一为「会话标签（tag）」 |
| CHANGELOG.md:112-126 | 1.0.3/1.1.1 尾部段落乱码（GBK↔UTF-8 mojibake） | 同文件其余段落正常 UTF-8 | 修复编码或加注「原文见 git history」 |
| backlog.md:28-37 | Tag 表列 8 种 | 卡片实际使用 upstream/perf/queue/a11y/jump/arch/race 等表外 Tag | :26 已声明可新增——非失实但表滞后；可补全 |
| .github/workflows/release.yml:28-31 | tag→flavor 示例「v1.0.3/v1.0.4-beta.1/v1.0.4-dev.1」 | 1.x tag 已于 2026-08-07 清理、现行为 0.3.x | 示例改用现存 tag 形态 |
| app/proguard-rules.pro:2-3,5-7 | `-keepattributes *Annotation*, InnerClasses` 等完整重复两次 | 2-3 行与 5-7 行逐字相同 | 去重（纯冗余） |
| scripts/ci-determine-flavor.sh:10-14 | 推导示例仍用 1.x 版本号 | 版本体系已重置 0.1.0 起（CHANGELOG:5-7） | 示例更新（ps1 版同款） |
| scripts/release.sh:131-142 | gen_changelog_entry 首个 while 循环输出重定向 `> /dev/null` | :144-145 注释自认需重新收集——首轮为死代码 | 删除死循环 |
| scripts/release.sh:77 | `version_gt()` 定义为语义版本比较 | 全文无调用点（last_stable_tag 用 sort -V）；ps1:148 注释自认未调用 | 删除或注明保留原因 |
| docs/verification-requirements.md:3,316 | 「本文档定义了 **OC Remote** 项目…」 | 项目已更名 OC Beacon（README:1、settings.gradle.kts:18） | 全文替换 |
| docs/verification-requirements.md:170-171 | 「新增业务逻辑必须有 `Log.i`/`Log.w` 输出」 | AGENTS.md:66：新日志用 AppLogger，不要 android.util.Log；observability §3.3 同 | 措辞改 AppLogger |
| docs/regression-guide.md:209 | 「VERSION_CODE 只增不减（**1.0.0 起严格**）」 | 版本体系已重置 0.1.0 起（CHANGELOG:5-7） | 括注改「0.1.0 起」 |
| docs/e2e-testing-workflow.md:3-5 | 通用 E2E 工作流定位，「最后执行 2026-08-05」 | 2026-08-20 起真机优先方针，本文档仍是模拟器流程未回注 | 头部补真机方针指引 |
| docs/release-workflow.md:4 | 「最后更新：2026-08-05」 | 正文记至 2026-08-21（§9.3 keystore 事件） | 更新头部日期 |
| docs/release-workflow.md:127,132-133 | 版本推导示例「现状 v1.0.3 → 1.0.4-beta.1」 | §2.3 自述 1.x 已全清、基线 0.1.0——示例与自身规则矛盾 | 示例改 0.x 形态 |
| docs/i18n-guide.md:57 | 「按钮 `assign_category` 各语言均为添加标签语义」 | UI 文案已改 Assign tag（CHANGELOG:68），key 名仍旧——半失实 | 注明 key 为历史残留 |
| docs/architecture.md:11 | model 清单列「SessionCategory, FavoriteSessionSnapshot」 | CHANGELOG:72-73 两实体已删；architecture.md 更新至 08-21 仍保留 | 移除已删类 |
| docs/architecture-debt.md:36,40 | 「破坏 AGENTS.md 声明的架构规范」 | 该断言实际在 architecture.md:61 与 README.md:97；AGENTS.md 无此条款 | 引用改为 architecture.md |
| docs/chat-ui-event-lifecycle.md:3 | 「本文档描述 **OC Remote** 对话界面…」 | 项目名 OC Beacon | 更名（C23 同族第 2 处） |
| docs/dialogue-e2e-test-runbook.md:213-216 | 尾注后又有轮次 11 | 轮次追加未同步移动尾注 | 轮次 11 移至尾注前 |
| maestro/terminal-smoke.yaml:6,9 | tags 含 `termlib`、注释「exercises termlib integration」 | #189 已换件 Termux（terminal-component-swap spec），termlib 已删 | tag 与注释更新 |
| maestro/README.md:44 | 「全部 flow（32 个）」 | 实际 33 个 yaml flow（35 文件 - README - util） | 计数更新 |
| scripts/perf-quick.sh:49-51 | 「固定参数，**与真机脚本一致**」 | perf-session-scroll.yaml 为 50%,80%→40% 屏比 200ms+500ms 等待；perf-quick 为像素坐标 150ms+300ms——三处不一致 | 改「近似」或对齐 |

## 待裁决冲突

**C1 会话/对话**：同一概念两叫法——代码与架构层用「会话/session」（AGENTS.md:65），E2E 文档族用「对话/dialogue」（AGENTS.md:20-21、docs/dialogue-e2e-*.md）。session 是 API 原词；「对话」出现在 UI 期望文档与中文文案域。
**C2 流式轮次命名**：「流式消息」（AGENTS.md:106,110）vs CONTEXT.md 词条「流式 turn」——_Avoid_ 明示回避「流式消息」。规则源自身正使用被回避词。
**C3 单一真相源一词多义**：AGENTS.md:65,92,140 三处分别指会话状态机、版本号文件、依赖版本清单。
**C4 术语表自称**：CONTEXT.md「领域术语表（glossary）」、domain.md「词汇表」、AGENTS.md 未定名——同物四名。
**C5 工作项命名**：issue-tracker.md 以「工作项」为规范名，AGENTS.md Backlog 纪律混用「卡片/条目」。
**C6 跳转冻结三名两概念**：①canon「跳转稳定窗口」②README:71「滚动锚定锁」③backlog:80-82「jumpLockActive/跳转锁」——③正是 _Avoid_ 明示的另一概念。
**C7 会话分类→标签改名未传播**：README:54-55 仍用「会话分类」并把已删除的跨服务器收藏列为功能。
**C11 turn 中文变体**：CHANGELOG「turn」与「回合分割线」混用（普通 turn 未立词条）。
**C13 flavor/channel 同轴双名**：CI/scripts 叫 flavor，gradle dimension 与注释叫 channel/渠道。
**C14 终端栈多名**：「类 Termux」/com/termux vendored 路径 vs ConnectBot/libvterm——#189 换件已定 Termux，历史名仍驻留。
**C15 「唯一真相源」措辞漂移**：AGENTS.md:92「真相源」vs build.gradle.kts:4「来源」。
**C16 BREAKING 归入 Removed 段**：脚本恒 Removed；模板:27 明文如此（有依据）；但 release-workflow §4.3 允许「Removed 或 Changed」——三源不一致；草稿节序也与模板节序不同。
**C17 状态服务 vs 仓库双名**：SessionStateRepository 接口已建，承重规则仍以 SessionStateService 指称。
**C18 prompt/message 混用**：PRIVACY_POLICY「messages, prompts」并列抹平 API 层 prompt/message 差异。
**C19 revert/撤销/undo 三名**：API revert、斜杠命令 /undo /redo、CHANGELOG「撤销操作」——undo 是否映射 revert 待裁决。
**C20 发送消息 vs prompt**：UI 层「发送消息」，API 层 prompt/prompt_async——规范名落 API 词还是 UI 词。
**C21 验证维度编号三轨**：维度 1-5（4+1 维）/ D1-D5 / D0-D4，qa:34 自带映射表。
**C22 Working vs busy**：regression-guide:116 会话列表「Working 状态」，FSM 用 busy——UI 显示名与状态名并存。
**C23 产品名残留 OC Remote**：verification-requirements:3,316 与 chat-ui-event-lifecycle:3 自称 OC Remote；另有 4 份 2026-06/07 历史文档同款（历史档案可豁免）。
**C24 workspace 一词两义**：API workspace=项目分支视图；README「工作区浏览器」=file/project 域——同名不同物。
**C25 summarize/compact 中文摇摆**：V1 端点名 summarize 实现即 compaction；「压缩总结/压缩」中文注解两文档不一。
**C26 Session.next 前缀歧义**：「下一代事件体系」易误读「下一个 session」；客户端 handler 名 SessionNext 加剧。
**C27 Part 计数 12 vs 13**：deep-research 1「12 种」vs api-reference §22 列 13 种（多 abort）。
**C28 V2 SSE 事件三代并存**：V1（message.part.*）/ 文档 v2（session.next.*+textID）/ 实测 V2（session.input.*+ordinal）——「v2」一词指向不同代。
**C29 category 标识符残留**：spec 字段 categoryAssignments、i18n key assign_category——tag 语义仍挂 category 名。
**C30 workspace 中文双译**：「工作空间」（file-viewer spec）vs「工作区」（README/architecture/i18n）。
**C31 审计条目编号三轨**：P0-P3 / Critical-High-Medium-Low+C/H/M/L/N / High-Medium-Low+D2-xx。
**C32 D 缩写三义**：dimensions D1-D13（审计维度）/ 验证 D0-D4、D1-D5 / audit D 报告。
**C33 提示音事件四分命名**：TURN_COMPLETE（FeedbackType）= SessionIdle 事件 = turn 结束——三域三名。
**C34 E2E UI 语言依赖分裂**：flow 锁英文文案、perf-session-scroll 锁中文、3 个 flow 硬编码中文会话名——i18n 15 语言与 E2E 锁定策略未成文。
**C35 l1-l5 分层与验证维度正交**：flow 层级 ≠ 验证维度 2/2b/2c，映射关系未显式说明。

## 统计汇总（盘点完成时点）

### 覆盖
- 精读 **138** 文件 = docs（非 journal）69 + 根 md 6 + scripts 24 + gradle/proguard 4 + .github 1 + maestro 34
- 仅统计 docs/journal **35** 文件（历史档案）；docs/learning 目录不存在
- 范围外命中 5 项（CONTEXT.md 参考、02-domain-layer.md、.scratch/01~06、termux vendored ×2）

### 注释语言现状（138 精读文件，口径见覆盖清单逐行）
| 分类 | 数量 | 构成 |
|---|---|---|
| 中文 | 96 | docs 63 + scripts 23 + gradle 2 + maestro 中文注释 3 + 根 md 5 |
| 英文 | 22 | maestro 英文注释 flow 21 + chatscreen-editing-protocol 1 |
| 混合 | 13 | proguard、architecture-debt、CHANGELOG（乱码段）、api-reference/deep-research 系列等 |
| 无注释/纯配置 | 7 | settings.gradle.kts、ci-extract-version.sh、部分极简 yaml/ps1 |

### 数字
- 术语观察：**121 行**（概念级约 60 个：session/message/part/turn/prompt/SSE 事件三代/红点链/游标分页/工作区双译/堆积队列/提示音/FAB/审计三轨等）
- 失实注释：**25 条**
- 待裁决冲突：**C1–C35（编号含 C8-C10/C12 保留空号）实录 30 条**

### 与 CONTEXT.md 既有词条关系（事实核对）
- 「渲染供给」「红点时钟域」「必需协作者」「状态簇」「版本 seam」「连接生命周期协调」6 词条文档层描述一致 ✓
- 「流式 turn」「跳转稳定窗口」的 _Avoid_ 词（流式消息/跳转锁）仍活跃于规则源与 README/backlog（C2/C6）

