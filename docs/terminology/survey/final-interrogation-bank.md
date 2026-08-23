# 终局拷问题库 v2（A2+A3 已并入；A1/A4 待入）

> 全部齐后浓缩为最后一轮 ask_user_question。重叠已合并：A2#1=P3、A2#5⊂P5/P6、A2#10=P2。

## 一、真分叉题（各自单独成题）

| 题 | 问题 | 候选 | 来源 |
|---|---|---|---|
| T1 | **中断/中止双轨 vs 单轨**：abortSession() 本地编排 V2 下委托 interrupt；CONTEXT 禁「中止」而 mapping 把本地 abort 定名「中止」（ChatViewModel:594,842 现状冲突） | A 双轨（协议=中断/本地=中止，改 CONTEXT 解禁）/ B 单轨全「中断」（废 mapping 行）/ C 本地另名「停止」 | A2#2 |
| T2 | **「子会话」84 处处置** | A 全量换「子智能体会话」/ B 「子会话」转正限定使用（改 CONTEXT，成本最低）/ C 逐句改写 | A2#3 |
| T3 | **turn 通知频道面**：notification_channel_tasks 频道名「任务通知」+描述；频道名变更在系统设置显示为新频道 | A 频道名+描述+EN "Task completed" 全 turn 化 / B 仅描述改、频道名不动 / C 连工具义一起（不建议） | A2#4 |
| T4 | **EN 源显示词同步（i18n 成本题）**：Assistant→Agent、sub-agent→subagent 6 键、folder→directory、"Task completed"→"Turn completed"、Queued 词族 | A 全同步（+14 语言翻译连锁，i18n CI 兜底）/ B 最小集（仅 subagent 拼写+Task completed）/ C EN 全不动仅 zh 改 | A2#10 + A3 P2 |
| T5 | **zh QUEUED 徽章 + 排队消息 4 键**（G-7 矛盾一并消解） | A 徽章保留英文 QUEUED、排队消息 4 键改「堆积」/ B 全「堆积」/ C 全保留 | A2#1 + A3 P3 |
| T6 | **历史产物豁免范围**：journal/archive/specs/CHANGELOG 旧条目（D 命中大头：对话 59、回退 56、归档 78 均在历史区） | A 全豁免（历史即档案，CHANGELOG 旧条不改，仅 Unreleased 起新规范）/ B CHANGELOG 旧条目也改 / C 全改 | A2#11 |
| T7 | **dialogue-e2e 两文档**：「对话」流程叙述域整册处理 | A 整册改「会话」/ B UX 叙述域豁免+首行注明 | A2#12 |
| T8 | **turn 写法**（Q1）：CHANGELOG/RN 中文条目 6 处全裸 turn，词条 zh 名=轮次，通知又裁「turn 完成」 | A 裸 turn 沿现状 / B 一律轮次（首现轮次(turn)）/ C 分域：通知态 turn、叙述域轮次 | A4 Q1 |
| T9 | **「回退」写作分域**（Q3，实测 7 义） | A 分域词表：API/代码=撤销·样式=改回·跳转=向后跳·性能=退化·fallback=降级 / B 仅禁 revert 义 / C 不管 | A4 Q3 |
| T10 | **CHANGELOG/RN 术语锚点**（Q7）：UI 实态 vs 规范名（46663ce4 曾把文案改成 Avoid 词「排队」） | A 实态优先+发版前先落 Phase 2 / B 规范名优先 / C 实态词括注规范名 | A4 Q7 |
| T11 | **commit 前缀纪律**（Q8）：近 50 条 feat:/fix: 清零 → release.sh 映射空转、下个 stable 草稿近空 | A 恢复强制 type 前缀 / B 放弃自动映射全手写 / C 仅提醒 | A4 Q8 |

## 二、打包确认题（低分歧，按推荐全收？）

- P1 drain=发送中 · P4 isStreamingMsg 注释桥接 · P6 role Assistant 豁免注记 · P7 端点双标 V2+V1 · P8 发送动词表照代码反推 · P9 「任务」Avoid 限通知域+任务面板豁免 · P10 D3-1 预告注释只挂接口一处
- A2#6 裸「游标」简称：首现限定+同文件简称放行（避免 300+ 处机械改）
- A2#9 压缩 Avoid 收「压缩上下文」语序变体；「摘要」合法边界=非压缩产物义
- A2#13 CONTEXT 补 7 词条缺口：provider=提供商/inbox=收件箱/tokens=令牌/ordinal=序数/thinking（UI 词保留注记）/编号行话保留可追溯/collapseTools 桥接注记
- A2#15 总则补写 CONTEXT 首段：Avoid 仅指名称性使用 + 标识符/键名豁免 + 定义句内描述性用词豁免
- A2#8 跳转 undo/redo 定名「跳转回退/跳转重做」（本地历史概念，与 revert 词族隔离）
- A4 Q2 commit 术语硬约束入 AGENTS.md（Avoid 速查+动词表）· Q4 backlog 首段术语句 · Q5 journal 三原则落 backlog.md §Journal 段 · Q6 release-notes 模板规则 9+新词示例
- A4 机制链事实：release.sh 草稿直通+无前缀 commit 被丢弃——下个 stable 草稿将近空（无论 Q8 选哪个，润色期术语闸门必须成文）
- A1 官方对照修正包（D1-D6）：Part 计数双口径（官方证实 vs parser 支持）· C18 修正「question.v2 官方事件 / form 端点级官方契约」· msg_ 去 ULID 化措辞 · input/inbox「过渡态」注释弱化 · 补「v2_* 命名轴 vs /api 线径轴」注 · about_opencode_url 结案

## 三、事实核查项（不需用户裁决，主会话/票内解决）

- A2#7 三窗口数值锚定（1.5s KDoc 归属 + 2s 常量定位 + G9 mapping 状态刷新）
- A2#14 mapping §6 刷新（9 项 ⏳ 闭环，防重复拷问）
- G-2 行号锚点全部 grep 复定位（SOP 已强制）

## 待入
（无——A 类 4/4 全部入库）