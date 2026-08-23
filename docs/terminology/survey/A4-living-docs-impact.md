# A4 · 活文档写作面调研（只读作业）

> 目的：术语统一后，**持续写作的文档形态**（CHANGELOG / Release Notes / backlog 卡片 / commit message / journal）要跟哪些约定。与 A3（存量怎么改）互补——本文只管「今后怎么写」。行号均为本次实测（A3 G-2 教训：不信旧行号）。
> 输入：CONTEXT.md（38 词条）· terminology-decisions.md（G/M/D3 三轮）· chinese-mapping.md v1 · CHANGELOG.md（126 行全读）· docs/release-notes-template.md（52 行全读）· backlog.md（102 行全读）· docs/release-workflow.md §4 · scripts/release.sh 草稿生成段 · git log -50 · journal 近期 4 件样本 · RELEASE_NOTES.md（现行版）。
> 防撞题声明：存量分拣已由 A2 承接（E5 任务/回合、E17 回退多义、裁决点 8 跳转 undo/redo、裁决点 11 历史产物豁免含 CHANGELOG:86）；A3 G-1..G-15/P1-P10 覆盖代码注释/AGENTS.md/strings。本文所有条目均为**写作面增量**，涉存量处只挂引用不重问。final-interrogation-bank.md 无活文档条目（grep 实证零命中）。
> 状态：✅ 完成——必改行 8 · 写作规范增量 5 组 · 待裁决点 8。

---

## 1. CHANGELOG 惯例审计

**形态事实**：无 Unreleased 段——§4.1 设计「仅 stable 正式版更新」，当前顶部即 `[0.2.0] 2026-08-08`；违逆点全部位于**历史版本段**。下一个 stable 段将是首个受新术语表约束的活段。

**机制链（关键）**：commit subject → release.sh:134/152 sed 剥前缀 → 草稿条目**原文直通**（`- $desc`）→ 发布者润色是**唯一术语闸门**。且 release.sh:140/158/194 的 `*) : ;;` 把无前缀 commit 直接丢弃——v0.2.0 tag 之后近 50 条 commit **零 feat:/fix: 前缀**，下个 stable 自动草稿将接近空文（`_No user-facing changes._`），条目实际靠润色期手写 → 术语规范必须成文，不能靠「草稿继承 commit」侥幸。

**违逆点（仅 2 行 + 1 处超域）**：:83 `回退 mergeMessageMeta REST completed 合并`（代码 revert 义用了禁词「回退」）；:86 `回合分割线`（CONTEXT.md turn 词条点名「回合（CHANGELOG 旧称）」）。两行均在历史段，改不改挂 A2 裁决点 11（历史条目豁免）。:112-126 为 mojibake 编码损坏（非术语，仅登记）。

**词风现状（写新段的基线）**：turn 裸用 6 处（:13/35/43/47/79/85）、全篇无「轮次」；「会话」统一 ✓；Busy/Idle 等 FSM 态英文保留（与 A3 生硬点②同款、待豁免成文）；标识符直引（maxCompleted/turnGroups/renderableTurns）合理 ✓。**结论：现状词风=裸 turn，与新词条「轮次」的关系是首要裁决（Q1）。**

## 2. release-notes 模板对齐缺口

- **零违逆**：模板示例均合规——「会话列表性能与未读红点」（:42）、「会话列表新增**未读红点**」（:44）、「杀进程后未读红点丢失」（:45）全部命中规范名。缺口不在存量在**缺规则**：写作规则 1-8（:40-48）无任何术语条款，新裁词（堆积消息/子智能体/轮次/撤销/中断/压缩/合成通知）在模板中零出现，润色者无对照锚点。
- 模板生成机制（:6）注明草稿由 release.sh 自动生成——术语闸门同 §1 机制链，模板是天然落点（增 1/增 6）。
- RELEASE_NOTES.md（活文档，随发版覆盖+按 tag 版本化）：现行版仅 :7 `诊断屏 → 举报到 GitHub` 一处贴了**改名前**的旧文案（commit f20dab8e 已把 zh 文案「举报→上报」）——暴露一条独立规范缺口：**Release Notes 引用 UI 文案须以发版时实态为准**（并入 Q7）。

## 3. backlog 卡片惯例

- **存量违逆（backlog 非档案，卡片流转中可改）**：:66/`等待提问/子会话期`、:71/`主子会话独立记忆`——「子会话」Avoid（子智能体词条）；弱建议两处：:65 `pending-input 会话` 首现标注「待处理输入（pending-input）」、:81 `2s 时窗` 用词条名「跳转稳定窗口」。
- **规范名已在用 ✓**：#184「未读水位线」（机制名合法）、#191「L2 stale」（stale 可作机制名并存）、#191「轮询」、#192「会话内」。**不算违逆的边界例**：#146:56 英文 `cursor V1 格式返回 400`——Avoid 的是中文裸「游标」，API 英文原词合法（此边界应写入规范防误伤）。
- **状态词**：进行中/待验证/已完成 三态与术语表无冲突；但「待处理」已是保留词（待处理权限/问题）——规范应注明固定词「待验证/待办/待裁决」不受影响、泛指用法（如"待处理事项"）避免。
- **Tag 体系**（crash/ui/queue/jump…）：英文短标签属标识符域，不受中文术语表约束；建议在规范中一句写死，免得 Phase 2 误扫。
- **落点**：首段卡片格式段（:5）是术语句插入位（Q4）；卡片 ≤3 行限制意味着只能加**速查句**不能加长表。

## 4. commit message 风格（近 50 条实测）

**前缀分布**：docs: 系 18（含 docs(spec) 1）· 无前缀 27（其中 #N 开头 8，如 `#151 修复…`）· chore: 系 3 · backlog: 1 · terminology: 1。**feat:/fix:/perf:/refactor: = 0**——§4.3 映射与 release.sh 分类完全空转，#151 全部修复、FAB 全部特性都进不了任何 CHANGELOG 分类（见 §1 机制链）。事实风格 = `[type(scope)?:] [#N] 中文摘要（细节括注）`。

**正文用词分布**：规范名已常见——堆积消息 ×3（446b4346/288bae06/0b91d771）、智能体 ✓、会话 ✓；违逆/待规范——`46663ce4 文案改名：堆积消息→排队消息`（**方向已被术语表反转**，堆积=规范名、排队=Avoid，见 Q7）；`e64ace76/c9f1b471 回退 M3 原版样式`+`5c8c6b6e 尺寸回调`（回退/回调混用）；`096fe219 任务面板拆解`（挂 P9 延伸）。
**「回退」在近 50 commit+journal 样本中共 7 义**：API revert / 代码回滚（CHANGELOG:83）/ 样式改回（e64ace76）/ git 回滚测试修复（queue-todo:38）/ 跳转向后（queue-todo:16,20,23「回退远跳/回退跳」）/ 性能退化（acceptance-closeout:60「p50 回退 >2ms」）/ fallback 降级（A2 E17 已裁豁免）——是全仓最多义词，Q3 必须分域。
**「轮」词形冲撞**：commit/journal 密集用「第 N 轮」表设计迭代（第十一~二十一轮）——与 turn 规范名「轮次」词形相邻但概念无关，规范需点名防误伤（挂 Q1 注）。
**commit 不可重写**（公开历史+tag 已引用）→ 术语约束只能前置到写作时，无事后补救（Q2）。

## 5. journal 写作

**现状惯例**：backlog.md:41「原文保留不压缩不删改」+ D3-5「历史 journal 不重写」——归档属性确认，本文不提议回写。但**新 journal 无术语规范**，样本实证 Avoid 词持续入场：acceptance-closeout.md 子代理 ×3（:3,127,132——注意此「子代理」指 AI 委派执行的真子智能体，恰是词条正域）+ 子会话 ×2（:123,125）；ui-batch.md「任务转后台」×2（:13,51，与 synthetic Avoid「转后台提示」词族相邻）+「堆积/TODO抽屉」（TODO→待办）。
**建议立规范（Q5）**，核心三原则：① 叙述段用规范名；② **证据引用豁免**——logcat 行、SSE 事件名、i18n key、标识符、日志串原样保留（wire 事实不可改写，与 A3 不动清单同构）；③ 规范名首现带英文原词（顺序随 A3 G-10 统一裁定）+ 编号按 D3-5 charter 新前缀。落点候选：backlog.md §Journal 段（journal 约定的现行权威位置）或 AGENTS.md。

---

## 6. 各文档必改行清单

| 文件:行 | 原文 | 应为 | 依据 |
|---|---|---|---|
| CHANGELOG.md:83 | 回退 mergeMessageMeta REST completed 合并（保留 SSE 兜底） | 撤销（revert）mergeMessageMeta REST completed 合并… | 撤销词条 Avoid 回退；历史段——挂 A2 裁决点 11 |
| CHANGELOG.md:86 | 暗色模式下回合分割线与输入框分隔线… | 暗色模式下轮次分割线与输入框分隔线… | turn 词条 Avoid 回合（点名 CHANGELOG 旧称）；挂 A2 裁决点 11 |
| CHANGELOG.md:112-126 | （1.0.3 段 mojibake 乱码） | 修复编码重写 | 非术语缺陷，仅登记超域 |
| backlog.md:66 | 等待提问/子会话期服务器恒报 busy | 等待提问/子智能体会话期服务器恒报 busy | 子智能体词条 Avoid 子会话 |
| backlog.md:71 | 主子会话独立记忆 | 主/子智能体会话独立记忆 | 同上 |
| backlog.md:65 | pending-input 会话 5s 轮询风暴 | 待处理输入（pending-input）会话 5s 轮询风暴 | 待处理权限/问题词条+首现带英文（弱建议） |
| backlog.md:81 | 带 2s 时窗语义需一并设计 | 带跳转稳定窗口（2s）语义需一并设计 | 跳转稳定窗口词条（弱建议） |
| RELEASE_NOTES.md:7 | 诊断屏 → 举报到 GitHub | 诊断屏 → 上报到 GitHub | commit f20dab8e 文案已改名；Release Notes 贴发版实态（Q7） |

> release-notes-template.md / release-workflow.md §4 / backlog 首段：无违逆行，缺口均为「缺规则」→ 见 §7。

## 7. 写作规范增量（可直接并入）

- **增 1 → release-notes-template.md 写作规则第 9 条**：「**术语用词遵循 CONTEXT.md 术语表**。高频速查：会话（非对话）· turn 完成/轮次（非任务/回合，写法随 Q1）· 堆积消息（非排队/待发）· 子智能体（非子代理/Sub-agent）· 智能体（非 Assistant）· 撤销（非回退）· 中断（协议 interrupt；本地停止=中止，两词勿混）· 压缩（非 summarize）· 合成通知（非系统通知）· 目录（非文件夹）。引用 UI 文案以发版实态为准。」
- **增 2 → release-workflow.md §4.5 润色行（:192）补一句**：「润色含**术语核对**：脚本草稿直通 commit 原文，Avoid 词在此拦截（速查见模板规则 9）。」
- **增 3 → backlog.md 首段卡片格式段（:5）补一句**：「卡片摘要与标题用词遵循 CONTEXT.md 术语表（堆积消息/子智能体/轮次/撤销…）；『待处理』保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语表约束；API 英文原词（cursor/fork）合法，Avoid 仅限中文对应词。」
- **增 4 → journal 新规范三原则**（落点 Q5）：叙述用规范名；证据引用（logcat/事件名/i18n key/标识符）原样豁免；首现带英文+编号按 D3-5 charter。历史不回写。
- **增 5 → commit subject 术语+动词速查**（落点 Q2）：动词规范——turn 结束=「turn 完成」· interrupt=「中断」· 本地停止=「中止」· compact=「压缩」· revert/unrevert=「撤销/取消撤销」· rename=「重命名」· 堆积发出暂用「自动发出」（P8 裁前）；「回退」禁表 revert（分域见 Q3）；恢复 feat:/fix: 前缀纪律（Q8）。

## 8. 待裁决点

| # | 问题 | 候选方案 | 本文默认 |
|---|---|---|---|
| Q1 | CHANGELOG/RN 中文条目里 turn 的写法（现状 6 处全裸 turn，词条 zh 名=轮次，通知文案又裁「turn 完成」） | A 裸 turn 沿现状（与「turn 完成」口径一致）· B 一律轮次（首现「轮次（turn）」）· C 分域：通知/状态域 turn、叙述域轮次 | A（B/C 落地须点名与「第 N 轮=设计迭代」的词形区分） |
| Q2 | commit 术语约束强度与落点（subject 直通草稿+commit 不可重写） | A 硬约束：AGENTS.md commit 小节列 Avoid 速查+动词表（增 5）· B 软约束：release-workflow §4.3 注一句 · C 不约束靠润色兜底 | A |
| Q3 | 「回退」今后写作分域（实测 7 义；存量豁免已由 A2 裁决点 8 承接） | A 定分域词：API/代码=撤销·样式/参数=改回·跳转=向后跳·性能=退化/劣化·fallback=降级 · B 仅禁 revert 义其余不管 · C 新写作不管 | A |
| Q4 | backlog 术语句（增 3）落点/强度 | A 首段速查句（≤3 行卡片约束下唯一可行）· B 仅放 CONTEXT.md 链接 · C 不加 | A |
| Q5 | 新 journal 术语规范（增 4）立否+落点 | A 立，落 backlog.md §Journal 段（journal 约定现行权威位）· B 立，落 AGENTS.md · C 不立（词随当日代码） | A |
| Q6 | release-notes 模板补强（增 1） | A 规则 9+示例补一条含新裁词（如堆积消息/子智能体用例）· B 仅规则 9 · C 只改 workflow 润色行 | A |
| Q7 | CHANGELOG/RN 术语锚点：发版时 UI 实态 vs 规范名（案：46663ce4 已把文案改成 Avoid 词「排队消息」，Phase 2 将改回「堆积」） | A 实态优先+发版前尽量先落 Phase 2 对齐 · B 规范名优先（与实态暂离）· C 实态词后括注规范名 | A |
| Q8 | commit 前缀纪律恢复（近 50 条 feat:/fix: 清零 → release.sh 映射空转、下个 stable 草稿近空） | A AGENTS.md 恢复强制 type 前缀（feat:/fix: 至少用于用户可见变更）· B 放弃自动映射、stable 全手写 · C 仅提醒不强求 | A |

> 统计：必改行 8（CHANGELOG 3 · backlog 4 · RELEASE_NOTES 1，其中弱建议 2）· 规范增量 5 组 · 待裁决点 8（Q3/Q7 与 A2/A3 存量裁决显式衔接，不重复）。
