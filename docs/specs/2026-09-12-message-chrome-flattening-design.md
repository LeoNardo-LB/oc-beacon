# Message-layer flattening and unified notification cards

- **Status**: ready for agent
- **Issue**: [#11](https://github.com/LeoNardo-LB/oc-beacon/issues/11) (`ready-for-agent`)
- **Date**: 2026-09-12
- **Source**: user decisions in the 2026-09-12 dedicated topic (6 grilling rounds) + first-hand research on both reference clients (dsh web `dsh-client-ui-chat`, opencode web `session-ui`) + repository field inventory
- **Design doc**: `docs/specs/2026-09-12-message-chrome-flattening-design.md`
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

1. **消息层**（用户 / 智能体正文）→ **扁平三段式**：头部标题栏 / 正文栏 / 尾部统计栏，去掉气泡外观（无背景、无边框、无圆角）。
2. **通知层**（子智能体 / 后台 Shell / task / 系统通知 / 上下文注入 / 压缩 / 错误·重试 / workflow）→ **统一通知卡家族**：一个 scaffold + 每个类型一个薄适配器。

重指标与逐轮明细收进顶部导航栏的统计弹窗（由不可滚动的对话框改为可滚动的底部面板），消息流保持轻量。

## User Stories

1. As a 聊天用户, I want 智能体正文全宽直出、不再被气泡包裹, so that 长回复更好读、屏幕利用率更高
2. As a 聊天用户, I want 用户消息去掉气泡底色但保持右对齐, so that 我一眼能分清"谁说的"
3. As a 聊天用户, I want 头部标题栏显示角色标签与 agent 名, so that 我能快速识别消息来源
4. As a 聊天用户, I want 头部标题栏显示时间戳, so that 我能定位消息发生的时刻
5. As a 聊天用户, I want 头部显示状态徽标（流式中 / 已中断 / 出错）, so that 我知道这条消息当前处于什么状态
6. As a 聊天用户, I want 已完成态不显示状态徽标, so that 界面不被默认态噪音占满
7. As a 聊天用户, I want 尾部统计栏显示步数与工具数摘要, so that 我能一眼看出这一轮干了多少活
8. As a 聊天用户, I want 产出文件行留在消息尾部且可点开, so that 我不用去别处找这一轮产出的文件
9. As a 聊天用户, I want 复制按钮常显在尾部, so that 我最常用的动作不需要多点一次
10. As a 聊天用户, I want 用户消息尾部有撤销入口, so that 我能把消息撤回到该点
11. As a 聊天用户, I want assistant 尾部有跳转 / 分支入口, so that 我能定位子会话或从该轮分叉
12. As a 聊天用户, I want 点赞点踩收进「更多」弹窗, so that 主界面保持干净
13. As a 聊天用户, I want「更多」弹窗放在底部面板里且可滚动, so that 小屏也能操作
14. As a 聊天用户, I want 最新一轮的尾部统计常显、历史轮点击展开, so that 当前轮信息随手可见、历史不占屏
15. As a 聊天用户, I want 流式期间的消息流内能看到耗时在走, so that 我知道模型还在输出
16. As a 聊天用户, I want 相邻消息之间有足够间距, so that 去掉气泡后消息不会糊在一起
17. As a 聊天用户, I want 所有后台 / 系统通知用统一形态呈现, so that 我不用为每种通知重新学习怎么读
18. As a 聊天用户, I want 通知卡折叠时一行就能读懂（图标 + 类型 + 来源 + 单行摘要）, so that 不展开也不丢信息
19. As a 聊天用户, I want 通知卡整行可点展开看输出, so that 展开入口足够大
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
42. As an AMOLED 用户, I want 去掉气泡描边后仍能看清消息结构, so that 界面层次不丢
43. As a 开发者, I want 信息架构能通过纯函数单测验证, so that 字段分配与门控不靠截图回归
44. As a 开发者, I want 通知类型都走同一 scaffold，各自只填差异, so that 新增通知类型不用再造一套容器

## Implementation Decisions

### 总纲：两层语言

- **L1 消息层**（用户 / 智能体正文）：扁平三段式，无背景 / 无边框 / 无圆角。
- **L2 通知层**（子智能体 / 后台 Shell / task / 系统通知 / 上下文注入 / 压缩 / 错误·重试 / workflow）：统一通知卡 = 一个 scaffold + 每类型一个薄适配器。
- 判据：**消息 = 内容，平面；事件 = 通知，成卡**。该判据与两端参考实现一致（dsh web 正文与过程行全平；opencode 唯一保留块状的正是可跳转的 task 卡）。

### L1 三段式规格

- **头部标题栏**：左 = 角色图标 + 角色标签（用户 / 智能体 / 系统）+ agent 名（有则）；右 = 时间戳 + 状态徽标。状态徽标只覆盖"进行中 / 已中断 / 出错"，不标"已完成"。轮次序号不在消息流出现。
- **正文栏**：内容渲染保持现状；工具卡 / 推理块 / 提问卡**保持各自容器**（卡片层容器统一是 #215 的范围，本 spec 不碰）。
- **尾部统计栏**：**模型名 + provider 图标（有则——用户第 1 轮裁决「模型名称放在底部」）** + 步数·工具数摘要 + 产物文件行（吸收原先挂在气泡外的台账行与产出行）+ 常显动作（复制；用户消息另有撤销；assistant 另有跳转 / 分支）+ 「更多」入口。**不放** token 桶 / TTFT / 速度 / 成本（进统计弹窗；逐轮明细展开行同样含模型，属历史记录，两处不冲突）。
- **「更多」**：底部面板，含点赞点踩（仅 DSH）+ 复制 Markdown 源码 + 消息删除（能力位就绪才出现）。
- **显隐策略**：最新轮常显；流式期间耗时 ticker 常显并与状态徽标同屏；历史轮默认收起、点击展开。
- **用户消息**：右对齐 + 最大宽度 82%，去底色。
- **间距与分隔**：消息间距 8dp → 16dp（紧凑密度 2dp → 8dp）；仅靠间距 + 头部角色标签分隔，不加分隔线；AMOLED 下不再有气泡描边（接受）。

### L2 通知卡规格

- **容器**：沿用现役 EventCard 语言（透明底 + 1dp 描边 + medium 圆角 + AMOLED 处理），不新造容器。
- **头部**：状态图标（运行 = 进行中指示 / 完成 / 失败）+ 类型标签 + 来源（agent 或会话）+ 状态徽标 + 时间。
- **正文**：折叠态 = 单行摘要且必须自解释；展开体承载实际输出（shell 输出 / task 摘要 / 注入正文）；**行平面、体可色块**。
- **尾部**：计数 / 耗时（小号弱化色、tabular-nums）+ 动作。
- **交互**（对齐 #215 C2 既有契约，避免“一次点击两个含义”）：**本体点击 = 展开 / 收起（唯一入口）**；跳转 = 尾部弱化外链箭头钮（常驻，仅可跳转者渲染）；只有破坏性 / 次要动作才用卡内按钮。
- **去重**：撤销 UI 层"×N"合并；改在映射 / 装配层按**事件身份键**（工具 callId / 子会话 id / 消息 id）收敛为一条并**状态原位更新**。
- **与面板的分工**：Shell 面板 / 智能体面板继续承担"实时列表"；消息流只放"完成事件"。
- **混排规格**（用户第 6 轮裁决）：通知卡与消息**同列同宽、不缩进**——靠容器与间距区分层级；缩进会与卡片层展开体的内缩混淆。

### 通知卡家族归并表

| # | 类型 | 现状容器 | 折叠 | 动作 | 归并决定 |
|---|---|---|---|---|---|
| 1 | 合成通知（task / 子智能体 / 后台 Shell） | EventCard（内套 MessageBubble） | 有 | 跳转 / 定位 | **已是统一卡**：只需去掉 MessageBubble 外壳 |
| 2 | 系统消息（role=system：工具目录变更 / kind 标签） | EventCard | 有 | — | 同上 |
| 3 | 上下文注入（injectionKind：context / plugin / skill-catalog …） | EventCard | 有 | — | 同上 |
| 4 | 后台任务时间线（DSH jobs） | EventCard | 有 | — | 同上 |
| 5 | 压缩卡 | CompactionCard（自绘 + MessageBubble） | 有 | 撤销（V1） | 迁统一卡；**收起态"流分隔线"形态例外**（语义优先） |
| 6 | 命令反馈 | CommandFeedbackCard（自绘 Surface） | 有 | — | 迁统一卡 |
| 7 | 错误 / 重试 / 中断 / 超限 | RevertBanner、TurnMaxTokensCard、SessionErrorCard（自绘 Surface） | 部分 | 撤销 / 重试 | **例外：颜色即含义的语义警示卡**（#215 已列） |
| 8 | 状态徽标（进行中 / 已中断 / 出错） | 无 | — | — | 新增，落消息层头部（非通知卡） |
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

### 数据层补全（并入本卡）

- (a) DSH 读 `data.message.source.{provider,model}` → 补齐智能体消息的模型名
- (b) 统计弹窗渲染 sessionStats + 逐轮明细
- (c) `interrupted` → 头部状态徽标
- (d) fork 入口使用"仅最后一条消息可分支"的禁用判据
- (e) chunk usage 全桶（reasoning / cache）接入
- (f) 清理 `Message.User.agent` 漂移；**删除死组件 MessageMetaInfo**（主代码零调用，含两个 androidTest 引用）

### 实施批次

- **批1 数据层**：(a)(e)(f) + 逐轮明细数据模型
- **批2 消息层**：去容器 + 三段式 + 流式 ticker + 「更多」面板 + (c)
- **批3 统计弹窗**：(b) + 逐轮列表 + (d)

分批验证分配（用户第 4 轮裁决）：**批1** = 单测 + 模拟器抽查；**批2** = 单测 + 模拟器 E2E（流式滚动铁律回归 / 跳转高亮 / 截图基线）+ **真机**；**批3** = 单测 + 模拟器。

## Testing Decisions

好的测试只断言**外部可观察行为**，不锁实现细节（例如不断言具体 Composable 的层级或私有函数名）。

- **主 seam：消息 / 通知"行模型"纯函数**（唯一新增 seam，最高层，无 Compose / 无网络 / 无 Android 依赖）。输入的领域对象（Message / Part / RenderableTurn / ServerCapabilities）→ 输出纯数据行模型（头部字段、尾部字段与动作可用性、通知卡字段、逐轮行字段）。信息架构的全部断言都落在这里：字段分配、能力位门控、第 N 轮编号来源、事件身份键去重、状态徽标派生。
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

- **旧裁决回写**：`MessageBubble` 的头注释需标注"三气泡统一容器于 2026-09-12 被本设计反转（仅角色消息去容器，三段式保留）"，并在 journal 与 backlog 对应条目留痕。
- **通知卡家族边界**：归并表见 Implementation Decisions；DSH workflow 降级卡随批2 一并核对是否已走 EventCard 语言。
- **i18n**：新增文案（第 N 轮 / TTFT / tokens·s / 已中断 / 更多 / 复制 Markdown 源码 等）需按 i18n 工作流补 15 语言并过检查脚本。
- **与 #215 的关系**：两者相邻但不同层——#215 管卡片容器，本 spec 管消息层容器；实现顺序上本 spec 批2 与 #215 可能触碰同一批文件，需串行。
