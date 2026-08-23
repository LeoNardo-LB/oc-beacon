# API 术语 → 中文规范名对照表（终版，随 CONTEXT.md 46 词条对齐）

> 状态：**全部裁决闭合**（四轮 G/M/D3/T+P）。本表是 CONTEXT.md 的执行视图（含弃用别名清单，供 Phase 2 修订扫描）。
> 总则：Avoid=名称性使用；标识符/键名/枚举/包名豁免；证据引用原样；历史 journal/archive/旧 spec 豁免、**CHANGELOG 历史段不豁免**（T6）。

## 核心对照（终版）

| API 词 | 中文 | 备注/弃用别名 |
|---|---|---|
| session | 会话 | 弃：对话、conversation、chat（屏名 Chat 保留）；dialogue-e2e 已整册改 |
| message | 消息 | ID 前缀 msg_ 等官方短 ID（非 ULID） |
| part | 内容块 | 官方 10+ 类型双口径；弃：零件 |
| PromptPart | 提示块 | 与内容块严格分开 |
| turn | 轮次 | **一律轮次**（T8）：UI/CHANGELOG/RN 统一；首现「轮次（turn）」；EN 显示 Turn completed；「第 N 轮」=设计迭代豁免 |
| streaming turn | 流式 turn | completed==null 口径；execution.succeeded 权威结束信号 |
| synthetic | 合成消息 | 通知文案「合成通知（子智能体已完成）」；OS「系统通知设置」豁免 |
| subagent | 子智能体 | 无连字符；jobId=服务器别名；**子会话→子智能体会话**（84 处，T2） |
| agent | 智能体 | EN 源 Assistant→Agent（T4）；role 值 assistant 豁免 |
| reasoning | 推理 | UI 显示词 thinking 保留（13 语言） |
| session.next.* | 会话细粒度事件 | 官方体系；v2_* 命名轴≠/api 线径轴 |
| inbox | 收件箱 | V2 输入排队 |
| pending message | 堆积消息 | EN=Queued；徽章 QUEUED 保留英文；drain=发送中；zh「排队消息」4 键改堆积 |
| pending permission/question | 待处理权限/问题 | question.v2 官方事件；form 端点级官方契约 |
| interrupt | 中断 | **单轨**（T1）：本地动作也叫中断；「中止」全面退役；abortSession→interruptSession |
| rename | 重命名 | updateSession→renameSession |
| compact | 压缩 | 弃 summarize/压缩摘要/上下文压缩（含语序变体） |
| credential | 凭据 | authMethods「认证方式」豁免 |
| revert/unrevert | 撤销/取消撤销 | **「回退」全局退役**（T9 五域：撤销/改回/向后跳/退化/降级）；redo 口语别名 |
| reply | 答复 | once/always/reject=一次/总是/拒绝 |
| cursor（分页/Shell 输出/会话列表） | 三游标各带限定 | 首现限定+同文件简称放行 |
| ordinal | 序数 | 弃：序号 |
| compaction | 压缩 | 同 compact 词族 |
| provider | 提供商 | 弃：供应方、中文语境 Provider 裸用 |
| tokens | 令牌 | zh 半分裂已裁统一 |
| worktree | 工作树 | 与工作区分家 |
| workspace | 工作区 | 弃：工作空间 |
| directory | 目录 | EN 源 folder→directory（T4） |
| catalog | 目录视图 | |
| project | 项目 | 「打开项目」对话框改目录浏览器表述 |
| annotation/note | 标注/备注 | 分场景 |
| archive（会话/存储桶） | 会话归档/冷存桶 | 两义带限定 |
| archive bucket | 冷存桶 | 弃：归档桶 |
| todo | 待办 | 抽屉 TODO 标签保留（API todo 列表义） |
| pty | PTY 终端 | 命令执行不再称「终端」 |
| shell 三义 | PTY/后台 Shell 任务/会话内 Shell 命令 | 各带限定 |
| turn 完成通知 | **轮次完成通知** | 频道名+描述+EN 全 turn 化（T3）；旧「任务通知」频道作废重建 |
| 单一真相源 | 分域使用 | 会话状态/后台 shell/红点时间 |
| 渲染供给 | 渲染供给 | Avoid：预解析驱动器/分片协调器 |
| 跳转稳定窗口 | 三窗口各有其名 | 2s 分片冻结/300ms jumpLock 解锁缓冲/900ms 滚动稳定 |
| 状态簇 | 状态簇 | Avoid：内容册/外壳册/集群 |
| 红点时钟域 | 红点时钟域 | 水位线=机制名 |
| 僵尸检测 | 僵尸检测 | stale 可并行 |
| collapseTools | 自动展开工具结果 | 语义反转注释桥接 + Tier A 改名 |
| flavor | flavor | **全仓统一 flavor**（D3-4）；channel/渠道退役（dimension 标识符改名） |
| 编号 | V/A/P/S/F/# 前缀 | D3-5 charter；局部标签不得撞全局前缀 |

## 写作规范速记（A4 成果，随裁决固化）

- **CHANGELOG/RN**：术语遵循 CONTEXT.md；引用 UI 文案以发版实态为准（先落术语再发版，T10）
- **commit**：恢复 feat:/fix: 强制前缀（T11）；动词速查——中断/压缩/撤销/重命名/轮次完成；「回退」禁用
- **journal 三原则**：叙述用规范名 + 证据引用豁免 + 首现带英文
- **backlog**：卡片用词遵循术语表；「待处理」保留给权限/问题；Tag 英文/#N 豁免

## 历史裁决遗留（全部闭环）

- ~~QUEUED 徽章待裁决 #8~~ → 保留英文（已入词条，G-7 消解）
- ~~行 53/54 本地 abort=中止~~ → 废除（T1 单轨中断）
- ~~share 行「子会话」~~ → 子智能体会话（T2）
- ~~G9 三窗口 ⏳~~ → 三名定稿（2s/300ms/900ms）
- ~~§6 九项 ⏳~~ → 全部闭环（part/turn/worktree/workspace/catalog/标注备注/session.next/QUEUED/redo）
