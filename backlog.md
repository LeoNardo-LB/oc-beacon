# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#230**。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |

**状态流转**：代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才算完结；完结即迁移（见首段）。

| 状态 | checkbox | 含义与流转规则 |
|------|----------|------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。后续 Agent 看到 `[~]`：向用户给出验证清单并请其执行；通过 → 转「已完成」并**当场迁移**；发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 仅迁移瞬间存在的过渡态——迁移完成后本文件不含任何 `[x]` 顶层条目（check 脚本强制） |

**Tag 标签体系**：标记相关领域便于批量排查；现有 Tag 不足以描述则新增。

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

**Spec**：满足「有非显然取舍需留档」或「跨会话实现需完整上下文」其一 → 在 `docs/specs/` 写 `YYYY-MM-DD-<名称>-design.md`（spec 是权威，卡片只留摘要+链接）；实现并用户验收后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与卡片引用路径。**归档 spec 定期清理零外部引用者**（git history 永久可找回）。简单需求不写 spec。

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。**新 journal 术语三原则**：①叙述段用 CONTEXT.md 规范名；②证据引用豁免（logcat 行、SSE 事件名、i18n key、标识符原样保留）；③规范名首现带英文原词，编号遵循 [numbering-charter](docs/numbering-charter.md)。

---

## P0 — 主流程阻塞

> （空）架构评审批次 2026-08-21–08-23 完结：六候选 + 顺手清理全部通过用户验收（2026-08-22 批次末 17 项 15 过，两问题已闭环：①MIUI 渠道默认关闭非 app bug、②升级为 #189 换件并于 08-23 验收通过）→ `docs/journal/2026-08-21-arch-review-deepening.md` · `docs/journal/2026-08-23-acceptance-closeout.md`

## P1 — 核心功能需求

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - 2026-08-23 评估（#151 两轮 E2E 全绿触发）：用户定规**两半均继续缓**——崩溃提示基建已齐（recordCrash→FATAL 持久化）只差启动提示 UI；gist 需 App 加 Gists 权限+重新授权，正文 20+3 上下文实证够分诊
  - 复评时机：beta 线上跑出真实报告后再看（崩溃提示优先级高于 gist）
  - → `docs/journal/2026-08-21-error-report-github.md` · `docs/journal/2026-08-23-beta-readiness-review.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`



## P2 — 优化与锦上添花

- [~] **#229 #228 根因补完：dedup O(N²) 算法根治 + 创建封堵正向验证** `data` `perf`
  - 用户质询「修复的是根因吗」→ 盘点：#228 修了存量/回灌/线程三层，但 pair 枚举本身仍二次方（#223 前置跳过只省文本比较）；且 #223 创建封堵未正向验证
  - 修复：isNewPartId 记忆化 + 同类 legacy-id 子集桶扫描（新版契约 p 只扫 legacy 桶，炸弹桶空即 O(1)/元素；复杂度 O(N+M×N)，M=legacy 数现实 ≤2）——语义与 #109/#223 完全一致；流式探针验证创建有界（每消息每类 ≤1，启动清扫兜底）
  - 回归：5000 非空新版契约 part 守时 <2s + legacy×新版合并语义保持；全量 1954 绿；真机 0 停顿
  - → `docs/journal/2026-08-25-session-list-wipe.md` §根因补完（#229）

- [~] **#228 V2 会话加载巨慢 + 页面乱：Room 空 part 炸弹回灌 merge 主线程 HANG** `data` `perf` `v2`
  - 用户报（2026-08-26）「点会话加载很久+页面乱」；MIUIScout 5s HANG 栈 + Room 直查定音：#223 残留炸弹行（单消息 4488 空 reasoning part，全库 5714）作为 incoming 回灌热视图 → dedup O(N²) 主线程跑数十秒（#223 过滤只作用 existing 侧）
  - 修复三层：merge 入口对称滤空（incoming+existing）+ 合并下沉 mergeDispatcher（可注入）+ 启动一次性清扫（实测 swept 5714 与计数分毫不差）；复打开原会话停顿 0 次、merge 1ms、页面 CLEAN
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#228

- [~] **#227 压缩分割线展开态滚出视口即丢——拉走再回自动收起** `ui` `compaction`
  - 用户反馈（2026-08-26）：「压缩内容展开之后，一拉到其他地方就会自动合上」；根因=expanded 用 item 内 remember 存储，LazyColumn 视口外 item 被丢弃时状态清零
  - 修复：CompactionCard 改受控组件，ChatMessageList 维护 messageId→expanded 屏幕级表（滚出视口不丢、离开会话即清，Q10 仍成立）；V1 尾部→消息流交接桥保「完成不收起」（#221 裁决）；V2 尾部/消息流同键无缝交接
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#227

- [~] **#226 压缩分割线形态大乱：V1 三元素重叠/气泡流式/完成塌缩 + V2 摘要不落盘** `ui` `compaction` `v1` `v2`
  - 用户三报「太乱了！总是闪现，动作很不连贯」+「压缩的输出怎么又在气泡中」；真机取证（录屏帧差 + Room 直查 + SSE 日志）定音：V1 进行中=触发消息错渲染静止「已压缩」线 + 摘要以 assistant 气泡流式（归一化器完结守卫放行）+ 尾部活跃线（messageId 空串认领失败）三元素同屏；完成瞬间气泡塌缩为空 turn（PartContent 跳过 Compaction）+尾部线消失=剧烈闪跳；摘要完成后 UI 不可达。V2（zhipu 构建）摘要从不落盘（REST summary.body=""、DB parts 空）→ 重进会话后完成线摘要丢失，V2Mappers 对 summary 对象解析有异常风险
  - 修复方向：「一条压缩=一条分割线」——assistant(agent=compaction) 在 UI 层认领为分割线（未完结=活跃态流式/完结=完成态摘要），触发消息隐藏，尾部线在摘要消息入列后让位；V2Mappers 兼容 summary.body
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#226

- [~] **#221 压缩展开区三连改：流式补偿 + 完成不收起 + 竖线等高（#217/#220 打磨）** `ui` `compaction`
  - 用户反馈（2026-08-25 五报三点）：①展开区流式生长要「跟正常对话一样」的 SSE 视口补偿；②展开状态下压缩完成不要自动收起；③左侧竖线是固定 240dp——必须跟内容等高（左右取舍征询用户，代理裁决仅左侧）
  - 修复：CompactionCard 文本锁存（latchedText 兜底 ended→REST 刷新空窗与失败残留，canExpand 不闪断→AnimatedVisibility 不折叠）；ExpandContent 改 matchParentSize 叠加层画 2dp 竖线（内容多高线多高，流式实时跟随；弃 IntrinsicSize 赌注）；ChatMessageList 压缩 item 接入 tool_progress 同款补偿（compactionExpandState 独立 lastHeight + 共享 shouldCompensate + layout{} 注入 COMP-CMP）
  - 调研结论（subagent，2026-08-25）：卡片/分割线高度预先获取**不值得做**——弹入已被 COMP-MSG 单遍 delta 覆盖（无时间差可弥合）、插入在 key 锚定下零位移（foundation 1.11.2 源码取证）、toggle 已被终版裁决排除 → `docs/research/2026-08-25-card-height-precompute-feasibility.md`
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#221

- [~] **#225 压缩流式输出来回跳动：延迟揭示的消费/揭示失配根治** `sse` `ui` `compaction`
  - 用户七报「内容输出来回跳动，像文字输出后做的补偿」；真机像素取证（96 帧 × 0.125s + ScrollDiag 相关）：5 次跳动集中流式早期，其中 +66px 恰等于单次注入单位——**消费遍复用 item 缓存测量**（内容/约束未变 Compose 跳过重测），消费位移生效而揭示未更新 → 下跳一单位；下次内容刷新再揭示 → 回弹
  - 修复：DeferredRevealCompensator.version（mutableStateOf）注入时自增；layout 块内读它建立快照订阅——注入使**本节点**测量失效 → 消费遍必重测本节点 → 揭示与消费严格同遍配对（构造性闭环）
  - 验证（对照实验）：修复前 5 跳/96 帧；修复后 **0 跳/96 帧 且补偿事件 168 次**（负载更重、视口像素级冻结残差 0.00）；全量单测绿
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#225

- [~] **#224 V1/V2 压缩形态统一：V1 压缩消息归一化为分割线** `compaction` `v1`
  - 用户指令「能否将 V1、V2 的形态做成一致」；V1 compact 产物是常规 assistant(agent=compaction) 消息（摘要裸泡渲染），V2 是 Part.Compaction 分割线
  - 修复：CompactionNormalizer（data/mapper 纯函数）——完结的 assistant(agent=compaction) 且 text 非空 → parts 折叠为单个 Part.Compaction（summary=全文、failed=error 存在）；接入 EventDispatcher.upsertMessages（REST/恢复）+ MessageEventHandler.handleMessageUpdated（SSE 实时）双路径；完结守卫防流式半文过早固化
  - 验证：单测 5 例（折叠/失败/未完结直通/非压缩直通/空文本直通）+ 全量绿；V1 真机 E2E：压缩完成渲染 Context compacted 分割线（气泡消失）+ 点击展开摘要全文正常
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#224

- [~] **#223 SSE 空 part 增殖 → 进会话主线程冻结（数据层）** `sse` `data` `perf`
  - 真机 E2E 发现：进含流式历史的会话永久转圈（8min+）；jdb 定音主线程栈 = mergePartsList→dedup→isNewPartId；Room 直查定音单消息 4488 part（4487 空，`_reasoning_ord_N` 递增）——服务器 REST 无此数据 = 纯客户端残留
  - 根因链：部分服务器链路（free 模型实测）每 reasoning 块发 started（ordinal 递增、空文本）而 delta 恒进 ordinal 0 → 空 started part 无限增殖（内存+DB INSERT）；REST 刷新 preserved 无限保留 → O(N²) merge 风暴
  - 修复三层：①dedup 的 isNewPartId 前置（双侧新版 id 跳过全文前缀比较）②mergePartsList preserved 滤空 Text/Reasoning 残留 ③handleMessagePartUpdated 拒绝同 kind 空 part 的重复空 started（仅派生契约 id，自定义 id 不折叠）
  - 验证：冻结会话 5.4s 进入（原 8min+）；新轮次 1 part/0 空（原百级）；回归测试 ×3 + 全量绿。遗留：DB 存量炸弹行不自删（inert，可后续清理任务）
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#223

- [~] **#222 贴底横幅不可见 + 补偿通道回写竞争（双修）** `ui` `compaction` `sse`
  - 修一（reveal）：revealBannerCount（retry/tool/step/压缩尾部分割线四类）+ autoScroll 门控 + requestScrollToItem(0) 显式锚底（msgCount 同款）；isAtBottom 门控会自我闭锁故弃用
  - 修二（通道）：§6.1 活体诊断定音 request-position 通道间歇丢注入（off 轨迹 785→933→933→1093，~30% 丢失→阅读历史缓慢上爬，#215 动画定因同源）；requestScrollShift 复活 scrollToBeConsumed 遍首无条件消费通道（a4eedab6 封存实现+dy=0 矩阵背书），四个 COMP 注入点全切换
  - 修二再强化（延迟揭示，用户六报「不是渲染前」定音）：DeferredRevealCompensator——增长遍不上报新高度（clip 裁掉未补偿几何，永不被放置）+增量预注入、下一遍遍首消费先行再揭示；连续增长链式逐遍递延。构造性渲染前（消费先于揭示），非预测高度。单测 7 例+全量绿；活体验证仍被 provider 阻断转 V6
  - 验证：诊断证据链完整（活体+源码+历史）；V1 侧已实证（2026-08-25 自建 V1 服务器：贴底触发压缩进行中分割线立即可见=reveal 生效 + 扫动动画像素级确认 + 完成链路 snackbar）；V2 侧转 V6 验收——①流式期滚离底部 1/3 屏视口应纹丝不动；②贴底触发工具调用聚合卡应立即可见
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#222

- [~] **#220 压缩进行中态视觉：标签骑线 + 两段线即进度动画（#217 打磨）** `ui` `compaction`
  - 用户反馈（2026-08-25）：「进行中态在分割线上方多出一块区域专门显示，难看；就不能显示在分割线上、分割线带进度动画吗」——#217 实现是标签行在上+全宽进度线在下，与完成态（线—标签—线骑线结构）不同构，多占一整块纵向空间
  - 修复：ActiveDividerRow 改为与 CompletedDividerRow 完全同构——左右两段 2dp LinearProgressIndicator（track=完成态同款 FAINT 静色线即分割线本体，tertiary 扫动段=进度动画），「正在压缩上下文…」标签居中骑线；无额外块、无遮罩底色；进行中→完成切换零位移（Q13 强化）
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#220

- [~] **#219 V2 压缩失败静默：无 snackbar + 失败消息伪装成功分割线 + messageId 字段名错** `sse` `ui` `compaction`
  - 用户二报「分割线一闪而过，重进才见压缩内容」定音：06:26 那次压缩实为 provider 故障失败（compaction.failed 715ms）——①失败完全静默（HTTP 秒回受理，失败只从 SSE 到达，V1 的 HTTP 失败回调在 V2 永不触发）②失败压缩消息渲染成成功「已压缩」分割线（V2Mappers 无失败标记）③started 读 messageID 但实测字段是 inputID（对位恒空）④失败零刷新（失败分割线要重进才出现）
  - 修复：CompactionEnded+error 字段→失败广播流→snackbar（带服务器原因）；Part.Compaction+failed→失败分割线（「压缩会话失败」错误色）；inputID 勘误；失败即时刷新；wire 契约同步；真机验证失败/成功分割线同屏正确标注
  - 修复二（三报「进行中分割线消失」）：inputID 勘误后骨架消息（inbox.enqueued 即插入、无 part）误抑制尾部分割线——消息流按 role+对位认领（排队期不认领防误导）；真机 34s 真实压缩帧验证 COMPRESSING 全程可见 + 原位切完成态
  - → `docs/journal/2026-08-25-session-list-wipe.md` §#219

- [~] **#218 session.deleted SSE 后会话列表全空（Empty directory）** `sse` `sessions`
  - 根因（2026-08-25 真机复现+代码定音）：SessionEventHandler.handleSessionDeleted 的 F6 泄漏清理 `values.removeAll { it.contains(sessionId) }` 谓词作用于 Set 元素本身——删除任一会话即把整台服务器的会话 id 集合整体移除 → 列表过滤 `id in serverSessionIds` 全空；任意删除（app 内删除/E2E 清理/他端删除）即触发；修复=mapValues 移除单 id + 清空集（F6 意图保留）；顺手修 loadSessions 协程取消时 _isLoading 卡 true（refreshSessions 永久被挡）；真机双场景验证通过
  - → `docs/journal/2026-08-25-session-list-wipe.md`

- [~] **#217 压缩 UI 统一：分割线包揽一切（V1/V2）** `sse` `ui` `compaction`
  - 2026-08-24 服务器探针+真机复现双定音：①V2 compact HTTP 16ms 即回（steer 异步），finally compactionNotifier(false) 秒杀 banner（59ms）后被 SSE started 复活——感知「一闪就没」；②【更强】ChatViewModel compactedSessions 累积 Set 判变：同会话第二次压缩集合不变→不刷新不通知→全程零 UI 重进才见分割线（round 3 实测）；③snackbar 4s 遮挡新入列分割线（corr 0.999997 纯遮挡）
  - 设计裁决（用户 2026-08-24）：分割线包揽一切——进行中=进度线即分割线+实时流式摘要（delta 接入）；完成=去边框+左竖线+Markdown（Q11-B）；展开不记忆（Q10）切换连续（Q13）；失败=线消失+snackbar（Q12）；CompactionBanner 删除；V1/V2 双支持（V1 HTTP 挂起即终态，V2 事件驱动）
  - 已实现+真机 E2E（三轮压缩含 R3 双连发修复实证 + 完成态 Markdown 展开验收）；单测 1931 绿；V1 真机验证留待环境；待用户日常使用验收
  - → `docs/journal/2026-08-24-compaction-divider-unification.md`

- [~] **#215 聊天流卡片体系统一：容器语言 + 交互契约** `ui` `refactor`
  - 三批完成（bcc435f1/998e32dc/6dedc566）+ 验收遗留修复完成（a4eedab6）：展开/收起两方向视口稳定（矩阵 6 格全 dy=0，动画保留；定因=倒序 LazyColumn 对 item 内高度变化零锚定修正·存量机制·非批次引入；修法=toggle 修正窗 + scrollToBeConsumed 逐帧注入，request-position 通道被测量回写丢弃的定因存档 journal）→ 待用户 V6 手感验收
  - → `docs/specs/2026-08-24-card-unification-design.md` · `docs/journal/2026-08-24-card-unification.md` §验收反馈·一

> （空）#191 已完结验收（实现 5693ddb6 + 单测 24/24 独立复跑 + 真机降幅 ≈93% + 用户关闭 2026-08-23）→ `docs/journal/2026-08-23-beta-readiness-review.md` §三
>
> （空）Tier C 五卡 #201–#205 已完结（2026-08-24 用户授权代验收官：实改 #204/#202 + 零改名裁决 #201/#203/#205，自动化全绿+真机证据链）→ `docs/journal/2026-08-24-tier-c-contract-renames.md`
>
> （空）#207 已完结验收（2026-08-24 用户验收通过：三态判定 + rememberSaveable 锚点；单测 1917 绿 + 真机活体单调不归零 + 野生实例静态化）→ `docs/journal/2026-08-24-thinking-timer-scroll.md` §完结迁移
>
> （空）#208 已证伪闭卡（2026-08-24 同日勘误：登记时滑动方向搞反致三项主张全部误判——正确方向复测 130 条历史全程可翻、loadOlder 补载正常、假 id 不渲染是服务器权威 upsert 正确行为）→ `docs/journal/2026-08-24-thinking-timer-scroll.md` §#208 证伪闭卡
>
> （空）#161 已闭卡（2026-08-24 用户裁决离线隐藏为可接受行为，不修复；调研确认机制主张全部成立，方案草图留存 journal 备查）→ `docs/journal/2026-08-24-p3-quad-research.md` §#161
>
> （空）#210 已修复转待验证（2026-08-24 jdb 取栈定音三根因：①MIUI「后台弹出界面」权限随卸载重置→DeviceGuard 拦 HiltEntryActivity 启动致 startActivitySync 永久等待=挂死本体，非代码回归；②测试 Activity 无语言覆盖，系统 locale 回 zh-CN 后英文断言漂移；③#209 test3 seed id 错。修=授权脚本化+attachBaseContext en-US+seed 一行。ChatInteractionTest 全类 6 过 + 单测 1923 绿；#209 插桩补跑同步完成）→ `docs/journal/2026-08-24-p3-quad-research.md` §#210 修复执行

> （空）#211 已完结验收（2026-08-24 用户裁决清理：HiltComponentActivity.attachBaseContext 强制 en-US 单点覆盖 19 类，零生产代码；主会话全量复验 135 测 3 败、locale 族 27 败全灭无挂死，与同事 14 类复验双证据闭环；残留三例分立 #212/#213/#214）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·二批
>
> （空）#212 #213 已完结验收（2026-08-24 主会话修复+真机 OK 9 tests：MigrationTest 补挂 MIGRATION_3_4 对齐 DB v4 / 空态断言对齐 KT10a session 术语——均为「代码先行测试未跟」机械勘误）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·二批
>
> （空）#214 已完结验收（2026-08-24 定因=测试时间炸弹：硬编码崩溃报告 ts 越 LogStore 21 天 ERROR retention 边界被内联 prune 插入即清除（hist db=0 vs fresh db=2 实验定音），非生产回归；sharedTimestamp 改取系统时钟零生产改动；真机本类 OK(1) + **全量 OK(135) 08-18 以来首次全绿**）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·三批

## P3 — 观察与低价值改进

- [~] **#216 V2 SSE 实时链路 subagent Running 期无子智能体会话箭头** `sse` `session` `ui`
  - 已修：EventDispatcher 跨 handler 回写——.next tool.progress 的 sessionID 幂等跨写进消息流 Running 态 Part.Tool（sessionId/sessionID 双写，终态不动）；V1 快照/V2 REST/V2 SSE 三路径对齐
  - 真机实证：subagent 运行中箭头出现 + 点击直达子智能体会话；单测绿 + 插桩 135 绿；待用户日常使用验收（进行中委派时点箭头进子会话）
  - → `docs/journal/2026-08-24-card-unification.md` §#216

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#166 RaceProbe 复现取证待用户执行** `race`
  - 若跳转叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可重放定位）
  - → `docs/journal/2026-08-21-race-audit-round6.md`（提升自该批子条目）

> （空）#168 已证伪闭卡（2026-08-24 devRelease 真机实测：720 帧 ≥17ms 率 0.00%、p99 8.5ms 低于 8.33 预算——devDebug 尖刺系 47% debug 构建税放大，release 无感知；原始数据归档 perf-evidence/r168-release-20260824）→ `docs/journal/2026-08-24-p3-quad-research.md` §#168

> （空）#184 已完结验收（2026-08-24 用户验收通过：四步回归确认——造未读→一键已读全灭→强杀重启不复活→新消息红点重现；修复 7bd04c11 作用域化 markAllSessionsRead，单服务器无行为变化）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移
>
> （空）#209 已完结验收（2026-08-24 用户验收通过：日常使用 context 圆环正常显示；修复 caea2b30 删死字段+恒假分支，单测 1923 绿 + test3 真机插桩 OK）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移
