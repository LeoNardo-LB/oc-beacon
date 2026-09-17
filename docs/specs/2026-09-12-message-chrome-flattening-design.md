# Message-layer flattening and unified notification cards

- **Status**: implemented v2（2026-09-17 v2 修订：角色消息头部标签栏整体删除，时间 / 状态徽标 / agent / 低频动作重排；v1 门禁全绿见「验证证据」节）
- **Issue**: [#11](https://github.com/LeoNardo-LB/oc-beacon/issues/11) (`ready-for-agent`)
- **Date**: 2026-09-12（2026-09-17 v1：用户消息保留三段式气泡、扁平化仅作用智能体正文；2026-09-17 v2：头部标签栏删除 + 「更多」抽屉改「消息详情」弹窗 + 尾部重排）
- **Source**: user decisions in the 2026-09-12 dedicated topic (6 grilling rounds) + first-hand research on both reference clients (dsh web `dsh-client-ui-chat`, opencode web `session-ui`) + repository field inventory
- **Acceptance**: docs/acceptance/2026-09-17-387/（v1）与 docs/acceptance/2026-09-17-387-v2/（v2 实机走查，截图本地留存）与 docs/journal/2026-09-17-387.md
- **Evidence**: `docs/research/2026-09-12-message-chrome-field-inventory.md`
- **Related**: backlog #387 follow-up topic; **reverses** the 2026-08-12 "unified bubble for the three message roles" decision (commit `7aa9788b`); coordinates with #215 (card unification, not yet implemented)

## Problem Statement

聊天页把三类消息角色（用户 / 智能体 / 合成通知）都塞进同一个气泡容器，智能体消息还拆成三条渲染路径各自重绘容器（常规 / 分片 / 分段），chrome 重复三份。从用户视角看：

- 消息流像"一叠卡片"，长回复被容器边距挤压，屏幕利用率低；
- 重要的派生指标（token 桶、TTFT、tokens/s、成本、逐轮明细）没有安放位置——有的散在会话级弹窗、有的压根没渲染（sessionStats 接线完整却零呈现）；
- 轮次序号只是"窗口内序号"，分页/滚动就会变，无法用来定位；
- DSH 面上模型名结构性恒空（载荷里有 `message.source`，客户端没读）；
- 重复的后台通知靠 UI 层的 "×N" 合并硬扛（缓兵之计），不是根因治理；
- 状态（进行中 / 已中断 / 出错）在消息流里没有统一呈现。

## Solution

把聊天页拆成**两层语言**：

1. **消息层** → **两段式骨架**（正文栏 + 尾部统计栏；v2 起删除头部标签栏）：**智能体正文扁平化**（无背景、无边框、无圆角）；**用户消息保留气泡**（primaryContainer 底色 + AMOLED 描边 + 非对称圆角）。角色文字标签（用户 / 智能体）零信息量，整体删除；时间、agent、状态徽标与低频动作（分支 / 删除 / 评分 / 复制 Markdown 源码）由尾部常驻的**「详情」入口**打开的居中弹窗承载。
2. **通知层**（子智能体 / 后台 Shell / task / 系统通知 / 上下文注入 / 压缩 / 错误·重试 / workflow）→ **统一通知卡家族**：一个 scaffold + 每个类型一个薄适配器（v2 不动，通知卡仍保留自身头部标签栏）。

重指标与逐轮明细收进顶部导航栏的统计弹窗（可滚动的底部面板），消息流保持轻量。

## User Stories

1. As a 聊天用户, I want 智能体正文全宽直出、不再被气泡包裹, so that 长回复更好读、屏幕利用率更高
2. As a 聊天用户, I want 用户消息保留气泡（仅保持右对齐）, so that 我一眼能分清「谁说的」
3. As a 聊天用户, I want 消息本体不再出现「用户 / 智能体」标签栏, so that 无信息量的角色文字不再占据界面
4. As a 聊天用户, I want 时间戳等元信息收进「消息详情」弹窗, so that 消息本体保持干净、需要时再查
5. As a 聊天用户, I want 状态徽标（流式中 / 已中断 / 出错）落在尾部统计栏, so that 我知道这条消息当前处于什么状态
6. As a 聊天用户, I want 已完成态不显示状态徽标, so that 界面不被默认态噪音占满
7. As a 聊天用户, I want 尾部统计栏显示步数与工具数摘要, so that 我能一眼看出这一轮干了多少活
8. As a 聊天用户, I want 产出文件行留在消息尾部且可点开, so that 我不用去别处找这一轮产出的文件
9. As a 聊天用户, I want 复制按钮常显在尾部, so that 我最常用的动作不需要多点一次
10. As a 聊天用户, I want 用户消息尾部有撤销入口, so that 我能把消息撤回到该点
11. As a 聊天用户, I want assistant 尾部常驻复制入口、「从此轮分支」收进「消息详情」, so that 主界面只留下最高频动作
12. As a 聊天用户, I want 点赞点踩 / 复制 Markdown 源码 / 删除收进「消息详情」弹窗, so that 主界面保持干净
13. As a 聊天用户, I want「消息详情」是居中弹窗（内容可滚动）, so that 小屏也能操作且与主对话抽屉区分
14. As a 聊天用户, I want 最新一轮的尾部统计常显、历史轮点击展开, so that 当前轮信息随手可见、历史不占屏
15. As a 聊天用户, I want 流式期间的消息流内能看到耗时在走, so that 我知道模型还在输出
16. As a 聊天用户, I want 相邻消息之间有足够间距, so that 助手正文去掉气泡后消息不会糊在一起
17. As a 聊天用户, I want 所有后台 / 系统通知用统一形态呈现, so that 我不用为每种通知重新学习怎么读
18. As a 聊天用户, I want 通知卡折叠时一行就能读懂（状态图标 + 类型标签 + 时间；来源/命令预览落在描述行）, so that 不展开也不丢信息
19. As a 聊天用户, I want 通知卡本体整行可点展开看输出（唯一展开入口）, so that 展开入口足够大且不与跳转冲突
20. As a 聊天用户, I want 可跳转的通知卡有常驻的尾部箭头一键进入子会话, so that 跳转入口可预期且不与展开冲突
21. As a 聊天用户, I want 跳转目标旁有弱化的外链箭头, so that 我能预期这是可跳转的
22. As a 聊天用户, I want 重复的同源通知只保留一条并原位更新状态, so that 通知不刷屏
23. As a 聊天用户, I want 顶部统计入口打开的是可滚动的底部面板, so that 内容变多后不溢出
24. As a 聊天用户, I want 会话级统计（轮次数 / 步数 / LLM 时间 / 工具时间 / TTFT 均值 / 解码速度）可见, so that 我能评估整段会话的开销
25. As a 聊天用户, I want 逐轮明细列表（第 N 轮 · 耗时 · tokens 总量）, so that 我能按轮排查哪一轮最贵
26. As a 聊天用户, I want 逐轮行可展开看 tokens 桶 / TTFT / 速度 / 步骤数 / 成本 / 模型, so that 需要细节时再展开
27. As a 聊天用户, I want 逐轮列表按最新在上、长会话可滚动, so that 最近的轮次不用翻到底
28. As a 聊天用户, I want 第 N 轮编号在会话内稳定且从 1 起, so that 编号能用来定位
29. As a DSH 用户, I want 消息尾部统计栏显示模型名与 provider, so that 我知道这条回复用的是哪个模型
30. As an OpenCode 用户, I want 统计面板里看到成本与 token 桶, so that 我能评估花费
31. As a DSH 用户, I want 服务器没有成本数据时不显示成本项, so that 界面上不出现"有位置没数据"的空项
32. As an OpenCode 用户, I want 服务器没有 TTFT / 速度时不显示这些项, so that 形态不因缺少数据而变得奇怪
33. As a 聊天用户, I want 两种服务器上面板形态与交互一致, so that 我换服务器不需要重新学
34. As a 聊天用户, I want 只有最后一轮可以分支, so that 我不会误从中间轮分叉
35. As a 聊天用户, I want 删除消息只在服务器支持时出现, so that 不出现点了没反应的动作
36. As a 聊天用户, I want 复制 Markdown 源码的选项, so that 我能拿到原始文本
37. As a 聊天用户, I want 被中断的回复有明确标记, so that 我知道它不是正常完成
38. As a 聊天用户, I want 长会话滚动不卡顿, so that 试用体验不退化
39. As a 聊天用户, I want 流式输出时视口不跳、不闪, so that 阅读不被打断
40. As a 聊天用户, I want 从快速导航 / 关键词搜索跳转后高亮仍准确, so that 我知道跳到了哪里
41. As a 15 语言用户, I want 新增文案（第 N 轮 / TTFT / 已中断 / 更多 等）已本地化, so that 界面不中英混杂
42. As an AMOLED 用户, I want 去掉头部标签栏后仍能看清消息结构（用户气泡保留描边、助手靠间距与尾部统计栏区分轮次）, so that 界面层次不丢
43. As a 开发者, I want 信息架构能通过纯函数单测验证, so that 字段分配与门控不靠截图回归
44. As a 开发者, I want 通知类型都走同一 scaffold，各自只填差异, so that 新增通知类型不用再造一套容器
45. As a 聊天用户, I want 每条角色消息都有常驻「详情」入口, so that 时间等元信息移出头部后仍然随手可查
46. As an OpenCode 用户, I want 逐消息 agent 名显示在助手尾部统计栏, so that 多 agent 会话里我能分辨每条消息由谁回答
47. As a DSH 用户, I want 单 agent 会话的 agent 只在会话级别（顶部栏）显示一次, so that 消息流不重复同一个 agent 预设

## Implementation Decisions

### 总纲：两层语言

- **L1 消息层**：**两段式**（正文 + 尾部统计栏；v2 删除头部标签栏）；**智能体正文**扁平（无背景 / 无边框 / 无圆角）；**用户消息保留气泡**（去容器只作用于助手正文，2026-09-17 v1 裁决）。
- **L2 通知层**（子智能体 / 后台 Shell / task / 系统通知 / 上下文注入 / 压缩 / 错误·重试 / workflow）：统一通知卡 = 一个 scaffold + 每类型一个薄适配器。
- 判据：**助手正文 = 内容，平面；事件 = 通知，成卡；用户消息 = 带气泡的角色消息**。该判据与两端参考实现一致（dsh web 助手正文与过程行全平、用户仍带气泡；opencode 唯一保留块状的正是可跳转的 task 卡）。

### L1 消息规格（v2：两段式 + 消息详情弹窗）

- **头部标签栏（v2 删除）**：角色图标 / 「用户」「智能体」文字 / agent 标签 / 时间 / 状态徽标一律不再出现在消息头部。删除判据：角色文字零信息量，且用户气泡 + 助手平面本身已区分说话人。
- **尾部统计栏（唯一信息锚点）**：
  - 左侧信息簇：状态徽标（仅流式中 / 已中断 / 出错）+ agent 标签（仅逐消息 agent 非空时）+ provider·模型 + 耗时（流式实时 ticker）+ 步数·工具数摘要。
  - 右侧动作簇：复制（常显）+ 「详情」入口（`ⓘ`，每条角色消息常驻）。
  - 用户消息动作簇：撤销（仅主会话）+ 复制 + 「详情」。
- **消息详情弹窗**：居中 `AlertDialog`（v1 的 `MessageMoreSheet` 底部抽屉退役）。上半只读信息——用户 = 时间；助手 = 时间 / 智能体（有则）/ provider·模型（有则）/ 耗时 / 步数 / 工具数 / tokens 总量（有则）/ 成本（有则）；空值整行不渲染。下半动作列表——点赞点踩（DSH）/ 复制 Markdown 源码 / 从此轮分支（仅末轮）/ 删除消息（能力位就绪）。因为时间已移出消息本体，**该入口每条角色消息都渲染**。
- **agent 可见性（数据驱动，不看服务器类型）**：逐消息 agent（`Message.Assistant.agent`，OpenCode 有值）→ 助手尾部统计栏显示；逐消息 agent 为空（DSH 不写该字段）→ 回落到会话级 `Session.agentPreset`，在顶部栏**标题行内**以弱色小字显示一次（不新增第三行——M3 TopAppBar 固定 64dp）。
- **正文栏**：内容渲染保持现状；工具卡 / 推理块 / 提问卡**保持各自容器**（卡片层容器统一是 #215 的范围，本 spec 不碰）。
- **不放** token 桶 / TTFT / 速度 / 成本明细（进统计弹窗；逐轮明细展开行含模型，属历史记录，两处不冲突）。
- **显隐策略**：最新轮尾部常显；流式期间耗时 ticker 常显；历史轮默认收起、点击摘要展开产出文件行。
- **用户消息**：右对齐 + 气泡（primaryContainer 底色 + AMOLED 描边 + 非对称圆角）；走 `MessageBubble` flat=false 路径。
- **间距与分隔**：消息间距 16dp（紧凑密度 8dp）；**不加分隔线**（v2 裁决：头部标签删除后靠间距 + 尾部统计栏作轮次锚点）；助手正文 AMOLED 无描边，用户气泡保留描边。

### L2 通知卡规格

- **容器**：沿用现役 EventCard 语言（透明底 + 1dp 描边 + medium 圆角 + AMOLED 处理），不新造容器。
- **头部**（现役 EventCard 语言，不新造）：状态图标（运行 = 进行中指示 / 完成 / 失败 = ErrorOutline 破色）+ 类型标签 + 时间 + chevron；**来源信息落在描述行**（task 描述 / shell 命令预览 / 系统来源标签），不另立头部槽位。
- **正文**：折叠态 = 单行摘要且必须自解释；展开体承载实际输出（shell 输出 / task 摘要 / 注入正文）；**行平面、体可色块**。
- **尾部**：**现役卡无独立计数/耗时行**（时间在头部；计数类信息按需落描述行）——保持沿用、不新增（2026-09-17 实现裁决，避免为凑规格引入无数据槽位）。
- **交互**（对齐 #215 C2 既有契约，避免“一次点击两个含义”）：**本体点击 = 展开 / 收起（唯一入口）**；跳转 = 尾部弱化外链箭头钮（常驻，仅可跳转者渲染）；只有破坏性 / 次要动作才用卡内按钮。
- **去重**：撤销 UI 层"×N"合并；改在映射 / 装配层按**事件身份键**（工具 callId / 子会话 id / 消息 id）收敛为一条并**状态原位更新**。
- **与面板的分工**：Shell 面板 / 智能体面板继续承担"实时列表"；消息流只放"完成事件"。
- **混排规格**（用户第 6 轮裁决）：通知卡与消息**同列同宽、不缩进**——靠容器与间距区分层级；缩进会与卡片层展开体的内缩混淆。

### 通知卡家族归并表

| # | 类型 | 现状容器 | 折叠 | 动作 | 归并决定 |
|---|---|---|---|---|---|
| 1 | 合成通知（task / 子智能体 / 后台 Shell） | EventCard（scaffold；容器 = MessageBubble 卡语言） | 有 | 跳转 / 定位 | **已是统一卡**：SyntheticNotificationCard 直接使用 EventCard scaffold（不再内套第二层容器）；容器语言由 flat=false 的 MessageBubble 提供 |
| 2 | 系统消息（role=system：工具目录变更 / kind 标签） | EventCard | 有 | — | 同上 |
| 3 | 上下文注入（injectionKind：context / plugin / skill-catalog …） | EventCard | 有 | — | 同上 |
| 4 | 后台任务时间线（DSH jobs） | EventCard | 有 | — | 同上 |
| 5 | 压缩卡 | CompactionCard（自绘 + MessageBubble） | 有 | 撤销（V1） | 迁统一卡；**收起态"流分隔线"形态例外**（语义优先） |
| 6 | 命令反馈 | CommandFeedbackCard（自绘 Surface） | 有 | — | 迁统一卡 |
| 7 | 错误 / 重试 / 中断 / 超限 | RevertBanner、TurnMaxTokensCard、SessionErrorCard（自绘 Surface） | 部分 | 撤销 / 重试 | **例外：颜色即含义的语义警示卡**（#215 已列） |
| 8 | 状态徽标（进行中 / 已中断 / 出错） | 无 | — | — | 新增，落**消息层尾部统计栏**（v2 从头部迁移；非通知卡） |
| 9 | 台账行 + 产出文件行 | TurnLedgerRow（自绘） | 有 | 分支 | 并入消息层尾部统计栏（非通知卡） |

**留在卡片层、不并入通知卡家族**（#215 范围）：工具卡族（ToolCardScaffold，C1/C2）、推理块（ReasoningBlock）、文件卡（FileCard）、token 用量 / 进度卡（C3/C4）；**输入型交互卡**（提问卡、权限卡）按 #215 的 C5 例外保留表单语义。

### 统计弹窗

- 形态由不可滚动的对话框改为 Material 3 底部面板。
- 会话级区：保留现有内容（provider/model、时间戳、上下文进度、消息计数、缓存命中、构成 breakdown、token 明细、DSH 子代理区、DSH 占用投影）并**补齐 sessionStats 呈现**（轮次数 / 步数 / LLM 时间 / 工具时间 / TTFT 均值 / 解码速度）。
- 新增**逐轮明细列表**：倒序（最新在上）、懒加载列表；每行默认折叠 = 第 N 轮 · 耗时 · tokens 总量；展开 = tokens 桶 / TTFT / 速度 / 步骤数 / 成本 / 模型。
- **第 N 轮编号**：服务器提供时用服务器的（DSH `data.turn`），否则客户端按轮次锚点统计（V1/V2）。

### 能力位门控

- 门控一律**整项隐藏**，不因服务器类型改变形态（遵守 `docs/ui-conventions.md` 的"服务器类型只产生能力位差异"铁律）。

| 项 | V1 | V2 | DSH |
|---|---|---|---|
| 模型名 + provider | 有 | 有 | 载荷有 `message.source`、客户端未读 → 本次补读 |
| 成本 cost | 有 | 有 | 全链无 → 整项不渲染 |
| TTFT / tokens·s / 轮级 runMs | 无 | 无 | 有 |
| 点赞点踩 | 无 | 无 | 有 |
| 状态·已中断 | abort part / error | finish + aborted | `interrupted` 布尔 |
| 撤销 revert | 有 | 有 | 无 |
| 消息删除 | 有（deleteMessage 端点） | 有（deleteMessage 端点） | 无（客户端 deleteMessage 恒 false → 入口整项隐藏，US#35） |

### 数据层补全（并入本卡）

- (a) DSH 读 `data.message.source.{provider,model}` → 补齐智能体消息的模型名
- (b) 统计弹窗渲染 sessionStats + 逐轮明细
- (c) `interrupted` → 尾部统计栏状态徽标（v2 从头部迁移）
- (d) fork 入口使用「仅最后一条消息可分支」的判据（实现取**非末轮整项隐藏**——app 无 branchUnavailable 文案资源，能力位式隐藏即满足「不误分支」；DSH web 的禁用态形态未采纳）
- (e) chunk usage 全桶（reasoning / cache）接入
- (f) 清理 `Message.User.agent` 漂移；**删除死组件 MessageMetaInfo**（主代码零调用，含两个 androidTest 引用）

### 实施批次

- **批1 数据层**：(a)(e)(f) + 逐轮明细数据模型
- **批2 消息层**：去容器 + 三段式 + 流式 ticker + 「更多」面板 + (c)
- **批3 统计弹窗**：(b) + 逐轮列表 + (d)

分批验证分配（用户第 4 轮裁决）：**批1** = 单测 + 模拟器抽查；**批2** = 单测 + 模拟器 E2E（流式滚动铁律回归 / 跳转高亮 / 截图基线）+ **真机**；**批3** = 单测 + 模拟器。

**实施结果（2026-09-17）**：
- 批1 = DshAssistantSourceUsageTest + V2SyntheticAgentDriftTest + MessageRowModelTest（行模型 / 逐轮模型 seam）。
- 批2 = 助手三路径扁平化 + 状态徽标 + 「更多」面板 + 尾部吸收 + 间距 + 身份键去重 + US#14/#34；命令反馈迁统一 EventCard。
- 批3 = 底部面板化 + sessionStats 区 + 逐轮明细列表 + 稳定身份键。
- 双轴 code-review 修复：B1 产出行流式门控、US#35 删除门控、轮次序号移出消息流、US#9 复制流式常显、删除确认框、孤儿键清理、seam 生产接线。
- 真机（小米 houji）当次未连接，未执行；模拟器（前台带窗口 AVD）已完成 V3 走查。

## Testing Decisions

好的测试只断言**外部可观察行为**，不锁实现细节（例如不断言具体 Composable 的层级或私有函数名）。

  - **主 seam：消息 / 通知「行模型」纯函数**（唯一新增 seam，最高层，无 Compose / 无网络 / 无 Android 依赖）。输入的领域对象（Message / Part / RenderableTurn / ServerCapabilities）→ 输出纯数据行模型（尾部字段与动作可用性、详情弹窗字段与动作、通知卡字段、逐轮行字段）。信息架构的全部断言都落在这里：字段分配、能力位门控、第 N 轮编号来源、事件身份键去重、状态徽标派生、agent 逐消息 / 会话级分流。
  - **生产接线（2026-09-17，v2 更新）**：statusBadgeFor / turnNumberFor / buildTurnDetailRows / dedupeByEventIdentity / rowCapabilitiesFor 已被 UI / 装配层调用；messageRowTail 驱动助手尾部字段与详情入口（主路径 + 分片统计栏），用户尾部为固定三动作（撤销 / 复制 / 详情）而 `messageDetailFields` 承载详情字段分配；notificationRowModel 驱动通知卡标签 / 摘要 / 展开 / 跳转——消除两源分叉。
- **复用现有 seam**：`computeRenderableTurn`（扩展而非重写）；`DshEventMapper` / V2 映射的单测（承载数据层补全 (a)(e)(f)）；androidTest Compose（先例：提问卡相关测试；MessageMetaInfoTest 将随 (f) 删除，仅作历史形态参照）只验渲染；模拟器 E2E 只验滚动 / 跳转 / 交互。
- **不设 seam 的地方**：Compose 组件内部不承载业务判定；不让 E2E 承担信息架构断言。
- **回归重点**：SSE 流式滚动稳定性（48ms 批处理 → 高度补偿 → 渲染三铁律）、跳转高亮、分片 / 分段渲染（长轮次不退化）。

## Out of Scope

- 卡片层容器语言统一（#215 的范围）
- 工具卡 / 推理块 / 提问卡的内部结构
- 服务器侧能力补齐（DSH 的 cost、OpenCode 的 timing 族均属上游缺口）
- Shell / 智能体面板自身的重构
- 聊天页之外（会话列表、设置页）的视觉改动

## Further Notes

- **旧裁决回写**：`MessageBubble` 的头注释标注：2026-08-12「三气泡统一容器」被本设计**部分反转**——**仅智能体正文**去容器（flat 委派骨架），**用户消息保留气泡**，通知层继续使用容器；骨架保留（v2 起为两段式：正文 + 尾部）。并于 journal 与 backlog 对应条目留痕。
- **2026-09-17 用户裁决 v1（修订 US#2 / L1 规格）**：用户消息**保留原三段式气泡**（原计划「去底色」被用户否决）；扁平化范围收窄为**智能体正文**。
- **2026-09-17 用户裁决 v2（修订 US#2/#3/#4/#5/#11/#12/#13/#42，新增 US#45-47）**：角色消息头部标签栏整体删除（用户与助手都是）；时间 / 状态徽标 / 逐消息 agent 标签 / 低频动作（从此轮分支）收进尾部与「消息详情」弹窗；`MessageMoreSheet` 底部抽屉改为居中 `BasicAlertDialog`。Solution / US / L1 规格 / 间距节已按此修订；**L2 通知层不动**。
- **v2 已接受后果**：时间只在「消息详情」里可见（用户裁决接受）；消息流不再显示角色文字；AMOLED 下用户气泡描边 + 助手平面靠间距区分；结构可读性依据从「头部角色标签」改为「间距 + 尾部统计栏」（原 US#42 判据作废）。
- **通知卡家族边界**：归并表见 Implementation Decisions；DSH workflow 降级卡随批2 一并核对是否已走 EventCard 语言。
- **i18n**：新增文案（第 N 轮 / TTFT / tokens·s / 已中断 / 更多 / 复制 Markdown 源码 等）需按 i18n 工作流补 15 语言并过检查脚本。
- **与 #215 的关系**：两者相邻但不同层——#215 管卡片容器，本 spec 管消息层容器；实现顺序上本 spec 批2 与 #215 可能触碰同一批文件，需串行。
- **已知偏离 / 后续卡（2026-09-17 登记；v2 更新）**：
  - 尾部主路径与 ChunkStatsBar 约 120 行同构未抽单点（backlog P3 卡：助手消息尾部统计栏单点抽取）。
  - DSH 逐轮 TTFT / tokens·s 无数据源 → 逐轮展开体整项隐藏（US#26 部分；backlog P3 卡：DSH 逐轮 timing 数据源接入）；会话级 TTFT 均值 / 解码速度已由 sessionStats 区补齐（US#24 达成）。
  - v2 起 `MessageMoreSheet` 底部抽屉退役，改为「消息详情」居中弹窗——docs/ui-conventions.md 的 MessageMoreSheet 抽屉例外登记随之撤销。
  - androidTest 的 Hilt 代码生成缺失（kspAndroidTest(hilt-compiler)）+ FakeDomainModule 缺 ServerSettingsRepository 绑定，于本卡收尾修复。
  - androidTest 的 `ComposeNotIdleException`（常驻帧泵 `ChatMessageList.kt` `while(true){withFrameNanos{}}`，#258 遗留）未修，已登记 P2 卡（帧泵空闲信号 / 测试专用 Local）。

## 验证证据（2026-09-17）

- **V1（代码层）**：compileDevDebugKotlin、testDevDebugUnitTest --rerun（全量 0 failure）、compileDevDebugAndroidTestKotlin、lintDevDebug（0 error）、assembleDevDebug 全绿。
- **V3（模拟器实机走查）**：前台带窗口 AVD Pixel6_Android36 + debug intent；截图 docs/acceptance/2026-09-17-387/01-stats-bottom-sheet.png 与 02-user-bubble-and-assistant-flat.png。实测：用户消息三段式气泡 + 助手扁平三段式（尾部 omen-alpha · 2.4s · 1 步 · 0 个工具 + 复制 + 从此轮分支）+ 通知卡同列同宽 + 统计底部面板（会话级 + 各轮次详情倒序）。
- **i18n**：scripts/i18n-check.sh PASSED（912 keys × 14 languages）。
- **androidTest**：connectedDevDebugAndroidTest 在模拟器执行（Hilt 测试图缺口修复后）。
- **局限**：Maestro CLI 本机未安装 → maestro/e2e-message-flattening.yaml 未执行（以 adb + 截图走查替代）；真机（小米 houji）当次未连接。

## v2 修订记录（2026-09-17）

用户手动测试后提出的 7 条反馈，经 3 轮拷问定稿：

1. 统计栏中动作在 3 个以内时不设溢出菜单——入口保留，但语义从「更多」改为「详情」（图标 `ⓘ`）。
2. 助手头部时间未居中——时间整体移出消息头部，问题自然消解。
3. 用户与助手的头部标签栏都不保留，信息与动作收进「消息详情」。
4. 「更多」从底部抽屉改为「详情 + 选择」居中弹窗。
5. `从此轮分支` 从尾部移入「消息详情」；助手尾部只剩 `复制` + `详情`。
6. 原头部信息盘点：角色标签（用户 / 智能体）删除；时间与逐消息 agent 移入详情 / 尾部；会话级 agent 落顶部栏。
7. 统计栏一开始就展示、有什么显示什么——核对后助手尾部流式期本就在渲染（`visible = isStreaming || …`），用户消息迟到属服务器确认后才有气泡（正常空档），**本次不改发送路径 / 不加乐观插入**。

### v2 落点

- `MessageSectionScaffold.kt`：删除头部渲染分支（角色图标 / 标签 / 时间 / 徽标 / headerTrailing），骨架缩为正文 + 尾部。
- `MessageCardAssistant.kt`（主 / Chunked / Segmented 三路径）：尾部信息簇重排（徽标 → agent 逐消息标签 → 模型 → 耗时 → 摘要），动作簇 = 复制 + 详情。
- `MessageCardUser.kt` / `ChunkedUserMessage`：头部标签行删除；尾部 = 撤销 + 复制 + 详情。
- `MessageMoreSheet.kt` → `MessageDetailDialog.kt`：`BasicAlertDialog`，详情区 + 动作区（评分 / 复制 Markdown 源码 / 从此轮分支 / 删除）。
- `MessageRowModel.kt`：`messageRowTail` 承载尾部信息簇、动作可用性与详情入口门控；`statusBadgeFor` 落尾部；新增「逐消息 / 会话级」agent 分流。
- `ChatTopBar.kt`：会话级 agent（`sessionAgent ?: agentPresetName`）弱色小字。
- i18n：新增 `chat_msg_detail_title` / `chat_detail_time` / `chat_detail_duration` / `chat_detail_tokens` 与 `a11y_message_detail`；删除孤儿 `chat_more` / `a11y_message_more`（`chat_label_user` / `chat_label_agent` 仍被会话列表与详情弹窗使用，保留）。
- `docs/ui-conventions.md`：撤销 MessageMoreSheet 抽屉例外登记。

## 验证证据（v2，2026-09-17）

- **V1（代码层）**：compileDevDebugKotlin、testDevDebugUnitTest --rerun、lintDevDebug（0 error）、assembleDevDebug。
- **V3（模拟器实机走查）**：前台带窗口 AVD + debug intent；验证用户气泡（无头部）/ 助手平面（无头部）/ 尾部徽标 / 逐消息 agent 标签 / 详情弹窗（详情 + 动作）/ 单 agent 会话顶部栏 agent。
- **i18n**：scripts/i18n-check.sh。
