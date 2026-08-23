# A3 · 修订仿真 dry-run（只读作业）

> 目的：验证修订规范可操作性。从 5 层各抽 1 个代表文件做仿真修订——只读仓库原文，在本文件写「原文行 → 修订后行」对照表，不碰仓库任何文件。
> 基线：worktree terminology-overhaul（CONTEXT.md 定稿 00fc9ae5 之后）。行号均为本次 grep/read 实测（见 G-2 缺陷：台账旧行号已漂移，一律复定位）。
> 状态：✅ 完成——5 文件仿真 · 42 对照行 · 生硬点 15 · 规范缺陷 15 · 待裁决点 10。

## 0. 输入与抽样说明

**输入**：CONTEXT.md（38 词条）· chinese-mapping.md（~90 行对照）· conflicts-master.md v8（95 冲突 + F01-F14）· terminology-decisions.md（三轮裁决 G1-G9/M1-M5/D3-1..D3-5）· identifier-rename-assessment.md（Tier A/B/C）· inventory 01/02/03/07/08 路对应条目。

**抽样**（每层 1 文件，任务指定）：

| 层 | 文件 | 验证目标 |
|---|---|---|
| data | `app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/session/SessionApi.kt`（224 行） | V1/V2 命名注释 + D3-1 改名预告注释怎么写 |
| domain | `…/domain/repository/PendingMessageRepository.kt`（47 行） | 堆积消息词条落地 |
| ui-chat | `…/ui/screens/chat/ChatViewModel.kt`（967 行，只取堆积/子会话段） | 堆积/子会话注释段 |
| docs | `AGENTS.md`（144 行） | 「流式消息」违逆段 C82 →「流式 turn」 |
| strings | `res/values/strings.xml`（786 行）+ `values-zh-rCN/strings.xml` 抽查 | agent 显示词 / QUEUED 段（G7 落地样例） |

**方法**：每文件 grep Avoid 词族 + 通读目标区间 → 逐行判定「可直接改 / 需桥接 / 挂裁决 / 豁免」→ 写对照表。**仿真不等于终稿**：标〔挂 P-x〕的行依赖待裁决点，本文给出候选方案下的示例改法。

---

## 1. data 层：SessionApi.kt

背景：会话生命周期 API 门面，abort/interrupt、update/rename、summarize/compact 三组 V1/V2 双词都在此（inventory 01：C2/C3/C4）。接口方法全部**无注释**（除 updateSessionFields/backgroundSession/activeSessions）。

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| SessionApi.kt:37 | `suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session`（无注释） | 前置新增 KDoc：`/** 重命名会话（V2 POST /rename）。⚠️ D3-1：标识符将改名 updateSession → renameSession。V1 词 update 仅历史对照。 */` | 重命名词条（Avoid update）；D3-1（28 处） |
| SessionApi.kt:49 | `suspend fun abortSession(…): Boolean`（无注释） | 前置新增 KDoc：`/** 中断运行中的会话（V2 POST /api/session/{id}/interrupt）。⚠️ D3-1：标识符将改名 abortSession → interruptSession。V1 词 abort 仅历史对照。 */` | 中断词条（Avoid abort V1 端点名）；D3-1（19 处） |
| SessionApi.kt:57-62 | `suspend fun summarizeSession(…): Boolean`（无注释） | 前置新增 KDoc：`/** 压缩会话上下文（V2 POST /compact；事件族 compaction.*）。⚠️ D3-1：将与 compactSession 合并为单入口，版本分流内化到 impl。V1 词 summarize 仅历史对照。 */` | 压缩词条（Avoid summarize）；D3-1（7+13 处） |
| SessionApi.kt:142-143 | `override suspend fun updateSession(…) = if (conn.apiVersion.isV2) v2.renameSession(…) else v1.updateSession(…)` | 行尾追加：`// D3-1 预告：本方法将改名 renameSession（双词分流在此收口）` | D3-1；impl 是 V1/V2 双词唯一同屏点，预告注释的展示位（挂 P10） |
| SessionApi.kt:39-42 | `/** 用任意字段更新会话（用于归档等）。PATCH /session/{sessionId} */` | `/** 用任意字段更新会话（用于会话归档等——与本地冷存桶无关）。PATCH /session/{sessionId}（V1 路径；V2 带 /api 前缀） */` | 归档两义词条（须带限定词：会话归档 ≠ 冷存桶）；端点双标格式挂 P7 |
| SessionApi.kt:88-92 | `将当前会话所有前台可后台化工具（subagent）批量转为后台（V2）。V1 不支持（返回 false）。` | `将当前会话所有前台可后台化的子智能体（subagent）批量转为后台（V2）。V1 不支持（返回 false）。` | 子智能体词条：subagent 是「工具派生的下级 agent 会话」，原文把 subagent 说成「工具」 |
| SessionApi.kt:84 | `suspend fun listSessionChildren(…): List<Session>`（无注释） | 前置新增行注释：`// 列出子智能体会话（parentID 指向本会话的会话）` | 子智能体词条（Avoid 子会话 child session 口语）；parentID 用 API 原拼写（ID 约定） |

**本层暴露的生硬点**：① 预告注释挂几层（接口/impl/19-28 个调用点）无规范——调用点全挂=噪音；② V1 历史对照只给了词没给端点路径，注释想双标缺料（01 路 API 权威清单可补 V1 路径表）；③ ⚠️ 标记语法与票号引用格式未定（Tier A 票未建，#193 起是占位）。

---

## 2. domain 层：PendingMessageRepository.kt

背景：堆积消息词条的直接落点。全文 47 行中文注释质量高，但 KDoc 首行即用 Avoid 词族变体。

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| PendingMessageRepository.kt:7 | `堆积消息仓库（turn 结束后待发送的本地暂存队列，按会话作用域）。` | `堆积消息仓库（turn 进行中暂存本地、turn 结束后自动发出的消息队列，按会话作用域）。` | 堆积消息词条定义句式；Avoid 待发/暂存词族（原文连用「待发送」「暂存队列」两个变体） |
| PendingMessageRepository.kt:10 | `仅自然成功 turn 结束（V2 execution.succeeded / V1 session.status(idle)）推进队首 1 条` | `仅自然成功 turn 结束（V2 session.execution.succeeded＝权威信号；V1 session.status(idle)＝兼容信号）推进队首 1 条` | 流式 turn 词条（权威/兼容口径）；补全事件全名 session.execution.succeeded |
| PendingMessageRepository.kt:36 | `查看队首（不删除）。推进管线 peek→send→delete 语义用。` | `查看队首（不删除）。推进管线（PendingMessagePipeline）peek→send→delete 语义用。` | 保守修订：仅补类名锚点；「推进」本身无规范名（C08 ⏳） |
| PendingMessageRepository.kt:42 | `#176/#177：会话 → 未发条数（列表手动「继续」入口可见性）。` | `#176/#177：会话 → 堆积条数（列表手动「继续」入口可见性）。` | 堆积消息词条 Avoid 未发词族 |
| PendingMessageRepository.kt:11 | `推进方（PendingMessagePipeline）负责触发时机，本仓库只管存取。` | **不改**（登记） | C08 发送动词群 ⏳：「推进方/推进」无裁决，强行改词反而丢准 |

**本层暴露的生硬点**：① Avoid 表是词组级（待发消息/暂存消息），实际命中全是变体（待发送的/本地暂存队列/未发条数）——需「词族清除」口径；② 词条句式「turn 结束后自动发出」与代码语义「仅**自然成功** turn 才推进」有出入，照抄词条会丢失败 turn 不发这条语义——修订保留了「自然成功」限定，词条定义是否吸收该限定挂缺陷；③ #176/#177 属 D3-5 合法 #N 前缀，但 charter 未发布，过渡期免改清单缺位。

---

## 3. ui-chat 层：ChatViewModel.kt（堆积/子会话注释段）

背景：967 行，取堆积段（L85-199）+ 子会话/任务聚合段（L228-302）。C01（drain 两中文名）、C08（发送动词群）、C14/C58（子会话五变体）在此交汇。

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| ChatViewModel.kt:88 | `// 堆积消息（2026-08-20 设计定稿）：本地暂存队列 + 推进管线` | `// 堆积消息（2026-08-20 设计定稿）：本地队列 + 推进管线（PendingMessagePipeline）` | 堆积消息词条 Avoid 暂存词族；「推进」C08 ⏳ 保留 |
| ChatViewModel.kt:148 | `// ============ 堆积消息（turn 结束后待发送，2026-08-20 设计定稿） ============` | `// ============ 堆积消息（turn 进行中入队、结束后自动发出，2026-08-20 设计定稿） ============` | 同上；对齐词条定义句式 |
| ChatViewModel.kt:156 | `/** 推送中会话集合（UI 标记「发送中」并锁定编辑/删除）。 */` | `/** 发送中会话集合（drain 进行中；UI 标记「发送中」并锁定编辑/删除）。 */` | C01：一行内 drain 两中文名并存（推送中/发送中）——本行按 P1 候选 A「发送中」仿真，裁决 B 则反向 |
| ChatViewModel.kt:168-169 | `// #176：入队即时补偿——…立即 drain，不等边沿/心跳` | `// #176：入队即时补偿——…立即进入 drain（发送中），不等边沿/心跳` | C01：drain 英文机制名保留+中文状态名随 P1；机制名/状态名并存格式无规范 |
| ChatViewModel.kt:235-236 | `part 优先（父会话按 cursor 翻页找 part id——…）→ 子会话 transcript 回退，取长者。` | `part 优先（父会话按分页游标翻页找 partId——…）→ 子智能体会话 transcript 回退，取长者。` | 分页游标词条（Avoid 裸「游标」）；子智能体词条（Avoid 子会话）；ID 约定（域变量 camelCase：partId） |
| ChatViewModel.kt:261 | `// ============ 任务聚合（subagent + shell） ============` | `// ============ 任务聚合（子智能体 + 后台 Shell 任务） ============` | 子智能体词条 + G3（Shell 任务限定词）；「任务聚合」的「任务」本身 C45 ⏳ 挂 P9 |
| ChatViewModel.kt:285 | `将当前会话所有前台 subagent 转为后台（对应 TUI ctrl+b）。` | `将当前会话所有前台子智能体（subagent）转为后台（对应 TUI ctrl+b）。` | 子智能体词条；首现带英文原词（顺序格式挂 G-10） |
| ChatViewModel.kt:269-270 | `// 2026-08-16（R3 僵尸自愈）：active 轮询发现 FSM 与服务器分歧时触发 L3 校验` | `// 2026-08-16（R3 僵尸自愈）：active 轮询发现 FSM 与服务器分歧时触发 L3 校验（L2=检测层，L3=恢复层）` | 僵尸检测词条「L2/L3 分层编号首现处须注明」；R3 局部标签与 D3-5 前缀不冲突可保留 |
| ChatViewModel.kt:190 | `/** 面板「继续」：空闲会话手动放行队首 1 条。 */` | **不改**（登记） | C08：「放行」无裁决（挂 P8） |
| ChatViewModel.kt:195 | `/** 面板单条「发送」：插队立即发送指定条目。 */` | **不改**（登记） | C08+G7 ⑥：UI 按钮「发送/立即发送」与协议侧「提示」的边界未划（挂 P8） |

**本层暴露的生硬点**：① 「首现带英文原词」的排列顺序（子智能体（subagent）还是 subagent（子智能体））全库无统一；② busy/idle/retry 等 FSM 状态名在中文注释里保留英文是既有惯例（AGENTS.md:65 同款），但规范没把「FSM 状态名豁免」写明；③ L160「busy 气泡菜单」引用的是 UI 实态，avoid 清单不覆盖 UI 控件口语。

---

## 4. docs 层：AGENTS.md「流式消息」违逆段（C82）

背景：CONTEXT.md 流式 turn 词条 _Avoid_ 注明「流式消息（AGENTS.md:106,110 违逆待修）」。**实测行号已漂移**：worktree 现状 144 行，全文件仅 :110 一处「流式消息」；:106 现为「48ms token 批处理」行，无违逆词。另全文件 4 处「流式」（:27 SSE 流式更新 ✓、:65 流式活动 ✓、:110 违逆、:113 流式正确性 ✓）。

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| AGENTS.md:110 | `- **`layout{}` 补偿只应用于流式消息**（`if (isStreamingMsg)`）— 应用到所有 assistant 消息会让已完结消息暴露在不稳定测量下。` | `- **`layout{}` 补偿只应用于流式 turn**（`if (isStreamingMsg)`，标识符沿旧名）— 应用到所有 assistant 消息会让已完结消息暴露在不稳定测量下。` | 流式 turn 词条 Avoid 流式消息（C82）；isStreamingMsg 不在任何改名 Tier → 注释桥接（挂 P4） |
| AGENTS.md:65 | `**SessionStateService 是会话状态与流式活动的单一真相源**（idle/busy/retry + Waiting/Streaming/ToolCalling）。` | `**SessionStateService（实现 SessionStateRepository 接口）是会话状态与流式活动的单一真相源**（idle/busy/retry + Waiting/Streaming/ToolCalling）。` | 必需协作者词条「注释引用规范：SessionStateService（实现 SessionStateRepository 接口）」；C65 联动 |
| AGENTS.md:97 | `…必须提供人工验证清单（维度 5）并请用户验证后才能声称完成…` | `…必须提供人工验证清单（V5，原「维度 5」）并请用户验证后才能声称完成…` | D3-5（维度1-5 → V1-V5；AGENTS.md 属前瞻迁移文档）——属 numbering 独立票，此处仿真演示「一文件多裁决叠加」检查 |
| （锚点元行） | CONTEXT.md 词条记「AGENTS.md:106,110 违逆待修」 | 台账锚点失效：现状仅 :110——SOP 强制 grep 复定位，禁信旧行号 | 缺陷 G-2（本次 dry-run 实证） |

**本层暴露的生硬点**：① AGENTS.md 自身有设计规范（docs/agents-file-design.md：改规则前先读）——术语修订是否豁免该流程未说明；② 桥接括注「标识符沿旧名」在 Tier A 改名完成后会过期，需要「预告注释随票清除」机制；③ 「单一真相源」在 AGENTS.md 另指版本号文件/依赖清单（C25 ⏳），本层修订不碰那些点位——修订范围切分靠 C25 裁决，未裁前只能动有词条背书的点位。

---

## 5. strings 层：values/strings.xml + values-zh-rCN/strings.xml（G7 落地样例）

背景：G7 裁了 UI 显示词六项（agent/subagent/compact/reject/synthetic/prompt 间接）——但**只裁了 zh 侧 + reject 的 EN 统一**；EN 源词族（queued/pending/sheet 标题）无裁决。zh 现状四变体（智能体✓/代理/Sub-agent/排队）。

### 5a. EN 源（values/strings.xml）

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| strings.xml:179 | `<string name="chat_label_agent">Assistant</string>` | `<string name="chat_label_agent">Agent</string>` | G7 ① + 智能体词条（Avoid Assistant 旧显示词）；key 不改（Tier C） |
| strings.xml:173 | `Running sub-agent` | `Running subagent` | G7 ② 拼写统一无连字符 |
| strings.xml:282 | `<string name="tool_sub_agent">Sub-agent</string>` | `<string name="tool_sub_agent">Subagent</string>` | G7 ②；key 含 sub_agent 属 Tier C 保留 |
| strings.xml:176 | `<string name="chat_system_notification">Sub-agent completed</string>` | 〔挂 P5〕候选 A：`Synthetic: subagent completed`；候选 C：仅改拼写 `Subagent completed` | G7 ④（通知文案=合成通知）EN 句式未定；与 :185 双键同值（见 5c③） |
| strings.xml:584 | `<string name="chat_queued">QUEUED</string>` | **EN 不改**（徽章保留） | 堆积消息词条「UI 徽章 QUEUED 保留（用户视角）」——但 zh 侧未定（挂 P3） |
| strings.xml:101-107 | 注释 `<!-- 堆积/TODO 面板（2026-08-20 设计定稿） -->` + `pending_sheet_title=Queued` / `pending_tab_stacked=Queued %1$d` / `…_plain=Queued` | 注释 → `<!-- 堆积/待办面板（2026-08-20 设计定稿） -->`；EN 值族〔挂 P2〕不改 | todo=待办（Avoid TODO 抽屉名）；EN 堆积词族 Queued/Pending 无裁决 |
| strings.xml:95-100 | 注释 `<!-- 堆积消息（busy 发送气泡菜单…） -->` + `Send now / Queue message / …` | 注释不改；EN 值族随 P2 | 同上（chat_busy_menu_stack 键含 stack，Tier C 保留） |

### 5b. zh 翻译（values-zh-rCN/strings.xml，其余 13 语言同规则联动）

| 文件:行 | 原文 | 修订后 | 依据词条/裁决 |
|---|---|---|---|
| zh:128 | `<string name="chat_busy_menu_stack">排队消息</string>` | `堆积消息` | 堆积消息词条 Avoid 排队消息 |
| zh:132/133/135 | `pending_sheet_title=排队消息` / `pending_tab_stacked=排队 %1$d` / `…_plain=排队` | `堆积消息` / `堆积 %1$d` / `堆积` | 同上（EN 值随 P2 定后再核一致性） |
| zh:502 | `<string name="chat_queued">排队中</string>` | 〔挂 P3〕候选 A 保留英文 `QUEUED`（与 EN 徽章一字不差）；候选 B `堆积` | 词条「QUEUED 保留」与 mapping 待裁决 #8 状态矛盾；zh 徽章词未定 |
| zh:205 | `正在运行子代理` | `正在运行子智能体` | G7 ②（Avoid 子代理） |
| zh:274/701 | `tool_sub_agent=子代理` / `task_sheet_subagents_tab=子代理` | `子智能体` ×2 | 同上 |
| zh:208 | `Sub-agent 完成通知` | 〔挂 P5〕候选 A：`合成通知：子智能体已完成`；候选 B：`合成通知` | G7 ④（通知文案=合成通知）+ Avoid Sub-agent；双键同值见 5c③ |
| zh:589 | `<string name="chat_role_assistant">助手</string>` | 〔挂 P6〕候选 A 保留 `助手`（role 值显示）；候选 B `智能体` | G7 ① 边界：role=assistant 是 API 消息角色值，非 agent 概念——两概念共享同一显示词 |
| zh:134/136 | `pending_tab_todo=TODO %1$d/%2$d` / `…_plain=TODO` | `待办 %1$d/%2$d` / `待办` | todo=待办（Avoid TODO 抽屉名） |
| zh:146 | `<string name="pending_item_sending">发送中…</string>` | **不改** | 证据行：UI 已用「发送中」——支持 P1 候选 A |

### 5c. 本层暴露的生硬点

1. **EN 源是 15 语言之锚，但 G7 只裁了 zh**：EN 堆积词族（Queued/Pending/Send now）不定，14 语言联动没有落点（P2）。
2. **一次 EN 值改动三联动**：14 翻译 + maestro 34 flows 锁文案 + CI i18n 检查脚本——SOP 必须含 flow 同步核对步骤；key 一律不动（Tier C）。
3. **双键同值语义分家**：`chat_system_notification`（:176）与 `chat_subagent_completed_notification`（:185）EN 同文案 "Sub-agent completed"，但前者疑为 synthetic 消息通知、后者为子智能体完成通知——G7 ④ 只给了 zh 词「合成通知」，没说这两个键各归哪个概念、EN 各怎么写（P5 附带）。
4. **徽章跨语言口径缺失**：「QUEUED 保留」若只保 EN，zh 徽章显示什么没说清（P3）。
5. zh :211 `chat_label_agent=智能体` 已合规——证明 G7 ① 在 zh 侧可零阻力落地，阻力全在 EN 源与 role 值边界。

---

## 6. 生硬点汇总（跨样本，去重后 15 条）

| # | 生硬点 | 出处样本 |
|---|---|---|
| 1 | Avoid 表是词组级，变体形式（待发送的/本地暂存队列/未发条数/排队 zh）无词族口径，执行靠自由裁量 | domain+strings |
| 2 | 台账/词条行号锚点漂移（:106,110 → 现状 :110）——已实证 | docs |
| 3 | D3-1 预告注释无格式规范：挂接口还是 impl 还是 19-28 调用点、标记语法、票号引用（票未建）、Tier 落地后清除时机 | data |
| 4 | 词条只收录 V2 端点路径，V1 历史对照词无路径——注释双标缺料 | data |
| 5 | drain 状态两中文名（推送中/发送中）同行并存，C01 半边悬置 | ui-chat |
| 6 | 发送动词群（入队/放行/推进/插队/立即发送）无裁决，堆积面板三按钮无规范词 | domain+ui-chat |
| 7 | 「QUEUED 保留」词条与 mapping 待裁决 #8 状态矛盾；zh 徽章词未定 | strings |
| 8 | G7 未裁 EN 源显示词族；合成通知 EN 句式未定；双键同值两概念未区分 | strings |
| 9 | 标识符-规范名长期错位（isStreamingMsg vs 流式 turn）无桥接规范、不在任何改名 Tier | docs |
| 10 | 首现带英文原词的顺序/格式（子智能体（subagent） vs subagent（子智能体））无统一 | ui-chat+data |
| 11 | ID 拼写约定只覆盖注释字段引用，不覆盖端点路径模板与 UI 文案内拼写 | data |
| 12 | 词条定义句式与代码语义有出入（「turn 结束后自动发出」vs「仅自然成功 turn 才推进」）——照抄丢语义 | domain |
| 13 | docs 层修订与 agents-file-design.md 流程、D3-5 numbering 票的边界/叠票顺序未定 | docs |
| 14 | 编号行话（#176/R3/L2-L4/RS-0xx）过渡期免改清单未随 D3-5 发布 | domain+ui-chat |
| 15 | 日志字符串（AppLogger，Diagnostics 可见=UI 文案）是否纳入术语修订无方针（C51 只记双名问题） | ui-chat:167 |

---

## 7. 修订 SOP 草案（修订一个标准文件的步骤清单)

**前置输入（读什么）**：
1. CONTEXT.md 相关分组的全部词条（含 _Avoid_ 行）；
2. chinese-mapping.md 对应行（含弃用别名列=Phase 2 清除目标）；
3. conflicts-master.md 中该文件命中的 C/F 条目；
4. inventory 对应路的该文件条目（T 术语/B 失实/变体三行）。

**步骤**：
1. **复定位**：台账行号一律 grep 复定位（缺陷 2），确认文件当前语言现状与注释密度；
2. **命中扫描**：按 Avoid 词族（词组+变体，缺陷 1 口径）grep；顺带扫英文裸词（subagent 连字符、part/cursor 裸用）与旧显示词（Assistant/子代理/排队）；
3. **分类落格**：每命中点归 4 类——(a) 可直接改（有词条背书+无语义损失）；(b) 需桥接（标识符错位/词条句式丢语义，缺陷 9/12）；(c) 挂裁决（对应本文 P 号）；(d) 豁免（key/@SerialName/端点路径/日志串/合法编号行话，缺陷 14）；
4. **改写顺序**：注释术语对齐 → 失实注释修正（引用 F 号）→ D3-1 预告注释（仅涉事 4 符号，格式按 P10）→ EN 源文案（G7+P2/P5/P6 裁决后）→ 14 语言翻译 → maestro flow 文案核对；
5. **不动清单**：标识符、i18n key、@SerialName、端点路径、Room/DataStore 一切、日志字符串（除裁决）。

**自查清单（怎么自查）**：
- ① Avoid 词族复 grep 清零（豁免点位留档）；
- ② 规范名首现带英文原词（顺序按缺陷 10 裁决后统一）；
- ③ ID 拼写双轨：API 字段原拼写（sessionID/partID）/ 域变量 camelCase（sessionId/partId）；
- ④ 强制限定词词对：会话归档≠冷存桶、分页游标≠Shell 输出游标≠会话列表游标、Shell 任务/会话内 Shell 命令/PTY 终端；
- ⑤ L2/L3 与局部标签（R3/RS-0xx）首现注明、与 D3-5 前缀无冲突；
- ⑥ i18n 改动：EN 源+14 语言+检查脚本+maestro flow 四方对齐；
- ⑦ 每行修订登记「文件:行｜依据词条/裁决」进台账，供终审对账；
- ⑧ 出口条件：零未分类命中；新发现生硬点补入缺陷清单；P 号挂起项在台账标记等待裁决。
---

## 8. 规范缺陷清单（规范没覆盖/没说清的，dry-run 实证）

| # | 缺陷 | 实证位置 | 影响 |
|---|---|---|---|
| G-1 | Avoid 表按词组列举，无词族口径——变体（待发送的/本地暂存队列/未发条数/排队 zh/queued EN）是否违逆靠执行者裁量 | PendingMessageRepository.kt:7,42；zh:128-135 | 990 文件规模化修订时判定不一致 |
| G-2 | 行号锚点漂移：CONTEXT.md 记「AGENTS.md:106,110」，现状仅 :110 | AGENTS.md 实测 | 台账对账失真；SOP 已补 grep 复定位规则，但词条内嵌行号需出勘误 |
| G-3 | D3-1 预告注释零规范：放置层级（接口/impl/调用点）、标记语法、票号引用、改名后清除时机全缺 | SessionApi.kt:37,49,57 三行仿真各写一版，无法判定哪版合规 | 4 符号 × 19-28 处的注释形态不可控 |
| G-4 | 词条只收录 V2 端点路径；V1 历史对照词（update/abort/summarize/auth）无端点路径——注释想 V1/V2 双标时缺料 | SessionApi.kt:39-42 想双标写不出 V1 路径 | 需 01 路 API 权威清单补 V1 路径列 |
| G-5 | drain 状态两中文名（推送中/发送中）无裁决（C01 只裁了 pending 两义的名词半边） | ChatViewModel.kt:156 同行两名并存 | zh 注释+UI 文案（发送中…）继续分裂 |
| G-6 | 发送动词群（入队/放行/推进/插队/立即发送/继续）无裁决（C08 ⏳）——堆积词条只定义实体名不定动词 | PendingMessageRepository.kt:11,36；ChatViewModel.kt:190,195 | 堆积面板三按钮+管线注释无规范词 |
| G-7 | 「QUEUED 徽章保留」词条已写入 CONTEXT.md，chinese-mapping.md 却列待裁决 #8——两产物状态矛盾；zh 徽章词未定 | zh:502 vs EN:584 | Phase 2 无法判定 zh 改不改 |
| G-8 | G7 只裁 zh 显示词+reject；EN 源词族（Queued/Pending/sheet 标题/Send now）无裁决——15 语言联动没有 EN 锚 | strings.xml:102-107,95-100 | EN 不定则 14 语言全堵 |
| G-9 | 标识符与规范名长期错位无桥接规范：isStreamingMsg 不在 D3-1 也不在 Tier A/B/C 任何清单 | AGENTS.md:110 改「流式 turn」后与代码标识符错位 | 注释-代码对照断裂，或需补 Tier A 增项 |
| G-10 | 「首现带英文原词」格式无规范（子智能体（subagent） vs subagent（子智能体），括号/全半角） | ChatViewModel.kt:285 与 SessionApi.kt:88 两版顺序相反 | 首现标注形态随机 |
| G-11 | ID 拼写约定只覆盖注释里的字段引用，不覆盖端点路径模板（{sessionId}）与 UI 文案内 ID 拼写 | SessionApi.kt:41 | 路径模板拼写口径悬空 |
| G-12 | 词条定义句式与代码语义有出入：堆积消息「turn 结束后自动发出」vs 代码「仅自然成功 turn 结束才推进」 | PendingMessageRepository.kt:10 | 照抄词条丢「失败 turn 不发」语义；词条定义应吸收该限定 |
| G-13 | docs 层修订流程未定：AGENTS.md 改动是否须走 agents-file-design.md 设计流程；术语票与 D3-5 numbering 票在同文件的叠票顺序 | AGENTS.md:97（V5）与 :110（流式 turn）同文件不同票 | 双票并行改同一文件会冲突 |
| G-14 | 编号行话免改清单未随 D3-5 发布：#N/R3/RS-0xx/L2-L4 过渡期是否一律不动、L2/L3「首现处」的粒度（文件级/注释块级）未定 | PendingMessageRepository.kt:42；ChatViewModel.kt:269 | 注释漂移；charter 立票时需补 |
| G-15 | 日志字符串（AppLogger 输出在 Diagnostics 屏幕=UI 可见）是否纳入术语修订无方针 | ChatViewModel.kt:167 pending message enqueued | C51 只登记了双名问题，未定范围 |

---

## 9. 待裁决点（编号+问题+候选方案）

> 建议全部并入「第二批拷问」；P1-P3 阻塞堆积/G7 票，P4-P5 阻塞 AGENTS.md/strings 票，P6-P10 可并行。

| # | 问题 | 候选方案 | 本仿真默认 |
|---|---|---|---|
| P1 | drain 状态中文名（C01 半边）：推送中 vs 发送中 vs 另定 | A「发送中」（与 zh pending_item_sending「发送中…」一致，推荐）· B「推送中」· C 另定新词（如「排空中」） | A |
| P2 | EN 源堆积词族：面板/按钮/文案用哪个词 | A 全 Queued（沿用现状，改动最小）· B 全 Pending（贴合 API pending message + 代码 pendingQueue）· C 分层：面板标题 Queued、机制描述 Pending | 未定（strings 层 EN 行挂起） |
| P3 | zh QUEUED 徽章（chat_queued）：保留英文还是改 | A 保留英文 QUEUED（与 EN 一字不差，词条现文义）· B 改「堆积」· C 维持「排队中」并豁免（须同时撤 Avoid 词族口径，不推荐）；须同步消解 G-7 矛盾（改 CONTEXT.md 或撤 mapping #8） | A |
| P4 | isStreamingMsg 等标识符-规范名长期错位（不在任何改名 Tier） | A 注释桥接「流式 turn（isStreamingMsg，沿旧名）」+ 不扩票（推荐，零风险）· B 扩 Tier A 增 isStreamingMsg→isStreamingTurn（触面另测）· C 只改注释不桥接，接受错位 | A |
| P5 | 合成通知（G7 ④）EN/zh 句式 + 双键同值整理（chat_system_notification vs chat_subagent_completed_notification） | A zh「合成通知：子智能体已完成」/ EN "Synthetic: subagent completed"（推荐，保留语义）· B zh 纯「合成通知」/ EN "Synthetic notification"（最贴词条，丢完成语义）· C 语义标题保留（子智能体已完成），仅注释归类为合成通知；无论选哪个，须先厘清双键各属哪概念 | A |
| P6 | role 值显示词（chat_role_assistant：EN Assistant / zh 助手）是否被 G7 ① 覆盖 | A 保留（role=assistant 是 API 消息角色值 ≠ agent 概念，推荐——在 G7 ① 词条补豁免注记）· B 统一 Agent/智能体（显示一致但混概念） | A |
| P7 | 注释中端点标写格式 | A 一律双标：V2 权威路径 + V1 历史路径（需 G-4 补 V1 路径表，推荐——双协议都在运行）· B 只标 V2 路径，V1 仅标词不标路径（词条现状，信息少但零成本） | A |
| P8 | 发送动词群（入队/放行/插队/立即发送/继续）规范词表（C08） | A 定完整动词表（入队=enqueue、放行=drain 触发、立即发送=send now、继续=continue，照代码标识符反推中文，推荐）· B 只规范注释不动 UI 按钮文案 | A |
| P9 | 「任务聚合/任务面板」的「任务」（subagent+Shell 集合义）与 turn 词条 Avoid「任务」的关系 | A 豁免：Avoid「任务」仅限通知文案域（turn 完成通知），任务面板义保留并在 turn 词条补注（推荐）· B 改「活动聚合」等新词（触 UI 文案+键名联动，成本高） | A |
| P10 | D3-1 预告注释格式与放置 | A「⚠️ D3-1：X → Y（票 #N）」只挂接口/类声明一处，调用点不挂（推荐）· B 全调用点挂（噪音大）· C 不挂注释，只依赖改名票（丢失注释侧线索） | A |

> 统计：对照行 42（data 7 / domain 5 / ui-chat 10 / docs 4 / strings 16）· 生硬点 15 · 规范缺陷 15 · 待裁决点 10。
