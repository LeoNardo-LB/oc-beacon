# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#293**。

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

## P1 — 核心功能需求

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - 2026-08-23 评估（#151 两轮 E2E 全绿触发）：用户定规**两半均继续缓**——崩溃提示基建已齐（recordCrash→FATAL 持久化）只差启动提示 UI；gist 需 App 加 Gists 权限+重新授权，正文 20+3 上下文实证够分诊
  - 复评时机：beta 线上跑出真实报告后再看（崩溃提示优先级高于 gist）
  - → `docs/journal/2026-08-21-error-report-github.md` · `docs/journal/2026-08-23-beta-readiness-review.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`

## P2 — 优化与锦上添花

- [~] **#287 DSH 附件字节拉取（session.attachment → Part.File url/图片缩略图接线）** `dsh` `ui`
  - d252eab4 全链落地（readAttachment → data URL → patchFileUrl 回填）+ 705d889c 三态专测补齐；真机代跑（2026-09-01）：Test Lab 会话 2 处缩略图渲染通过
  - 待用户验收：真机 DSH 带图会话缩略图目验（代跑未区分 Room 缓存路径）
  - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：当前服务器对客户端**不暴露** tool-workflow 进度事件——events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因。**升级前置**：待服务器在任何客户端面暴露 tool-workflow 事件后重验，再启聚合器

- [~] **#285 DSH 斜杠命令补全的会话龄缺口：懒建会话/首连期命令列表空 + commands/change 事件未消费** `dsh` `ui`
  - 0a925220 双缺口闭合（sessionIdFlow 就绪重载 + commands/change 全链消费）+ 测试；真机代跑（2026-09-01）：DSH 会话输入 / 弹出服务端命令（/permission /sandbox /approval /model）
  - 待用户验收：真机输入 / 目验；懒建首连全链因 DSH 连接洪泛未代跑（逻辑有单测覆盖）
  - → docs/journal/2026-09-01-backlog-adjudication-closeout.md


- [~] **#283 权限默认档动态渲染 + projection permissions 键闭合（双轴审查 Spec 轴 a1/a2）** dsh
  - a1=7d6761ef（settings.describe schema enum 动态档集，空回退三档）；a2=5c2d44cc（permissions 投影帧整值替换 Session.permissions，JsonNull tombstone）；全量 255 套件/2519 用例 0 失败
  - 待用户验收：设置页档集动态渲染目验 + 中途切档后 app 内权限态刷新
  - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

- [~] **#278 DSH 僵尸 Busy 的 L3 自愈缺失——无状态端点下的真相源设计** `infra`
  - 87238a1c 方向二落地：fetchSessionStatus 由恒空 map 改 session.list running 字段播种 busy/idle（DshApiClientTest 专测）；对账回放主径 015ed7de 在前
  - 待用户验收：running 中强杀 app 重开会话状态收敛（代跑受阻：470 会话连接洪泛挡住发送通道，无法造 busy 现场，如实注记）
  - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

- [~] **#279 导出 SAF intent MIME 按服务器类型设置（ChatScreen 解冻前置）** `ui`
  - 6cb3c8a7 落地：CreateDocument MIME 与建议名扩展名按 exportIsArchive 切换 + renameDocument 落盘后兜底；真机代跑（2026-09-01）：DSH 导出 SAF 预填 test-lab-initialization-20260901.zip 通过
  - 待用户验收：真机导出落盘 .zip 可正常打开
  - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

- [ ] **#277 单测偶发跨类污染：UncaughtExceptionsBeforeTest（Dispatchers.Main 未设窗口泄漏）** `test`
  - 现象（2026-08-31 实证）：ChatViewModelQueuedTest 偶发 UncaughtExceptionsBeforeTest（原始异常=「Main was accessed when the platform dispatcher was absent」）；顺序/时序依赖
  - 根治尝试（2026-08-31，用户纪律）：12 连跑专用复现循环 + 此前 6 连绿 = **18 连绿未复现**（初估 ~14% 系单红后时序域漂移）；修复条件不满足（无法复现+失败 XML 已被 --rerun 覆盖）→ 按纪律留存
  - 方向：再现时**保留 XML**（勿 --rerun 覆盖）提取完整栈定位泄漏类后即修（疑 VM 协程在 resetMain 后醒）

- [ ] **#258 fling 高速段重 item 组合帧 50-130ms——预组合与渲染同线程争抢的结构性上限** `perf` `ui`
  - 现象（2026-08-29 测量矩阵，journal 二十八轮）：高速 fling（60ms 甩）下新 item 首组合帧 p95 65ms/p99 129ms；prefetch ON 亦 p90 53ms（预组合与渲染抢主线程）；中速滚动全绿（jank 0.00-0.23%）。崩溃根因（331365999 家族）已经 ComposeFoundationFlags flag=false 机制级修复，本项纯性能
  - 测量：gfxinfo 三配置矩阵 + SafeFling 出口内速日志（全部自然衰减尾，无异常终止）；atrace 需先 Tracing.enable 才有 compose 段
  - 方向：Tracing.enable + perfetto 定位组合热点 → chunk 组合瘦身（block 级惰性/SelectionContainer 开销/ clickable wrapper 精简）；新线索（2026-08-30 调研）：androidx 1.13.0-alpha02 `ComposeUiFlags.isVectorDrawCacheSharingEnabled`（VectorPainter 列表缓存共享）待试 → `docs/journal/2026-08-27-event-card-unification.md` §二十八轮

## P3 — 观察与低价值改进

- [ ] **#292 goal mutations 批 broken WIP 归档分支裁决（archive/goal-mutations-20260901）** `refactor`
  - 2026-09-01 收尾裁决批 stash 清理：原 stash@{1}（diag agent 标注 broken，基座 e5581a36 落后 54 commits，含 DshProjection/ContextDetailDialog/ChatViewModel 瘦身等未落地工作）转归档分支保留；stash@{0}（堆积链路拆除 WIP）已被 706d1f1e 完整超越故径弃
  - 方向：需要 goal/上下文详情域重构时先掂量该分支可 salvage 部分否则删
  - → docs/journal/2026-09-01-291281-stash-v6.md

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - 八轮复核：向前导航箭头=会话切换路由（EventCard.kt:139/SyntheticNotificationCard.kt:128）**不经过 JumpMaskOverlay**（蒙版仅服务快速定位/定位卡跳转）；箭头路径 15/15 即时 dump 满内容未复现——历史 8% 样本若来自蒙版路径，后续探测应改走「快速定位」抽屉跳转；采样功效不足断言已修
  - → `docs/journal/2026-08-20-queue-todo.md` · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`

- [ ] **#254 RenderSupplyCoordinatorTest.T12 负载敏感偶发——skip 早期提交竞态（测试基建）** `refactor`
  - 现象（2026-08-28 两轮全量复现）：T12「前两次 skip 不应提交」满载挂、隔离运行恒绿（12/12）；与本轮改动代码零交集（协调器未 import 被改文件）
  - 锐化诊断：Env 假时钟已注入（冻结 now）→ 稳定窗口门控非机制；早提交路径 = skip1 时 `inViewportNow || hotNearBand` 均假——锁定 `everVisiblePartIds` 标记/异步 pending 管线的负载竞态假设，需深挖 pending 入队与视口标记的先后序
  - 候选：视口标记同步性审计 / pending 入队-消费序单测化；实施前先确认偶发率（连续观察全量轮次）
  - → `docs/journal/2026-08-27-event-card-unification.md` §十五轮

- [ ] **#245 巨型消息区下滑翻旧偶发「拖不动」——方向不对称滚动死帧** `ui` `sse`
  - 手势阶梯实验（e234g-REPORT）+ 六轮两次现场同帧复现：数屏长单项区域下滑帧字节级静止（方向不对称、moveCount 完整送达）；四轮 T2 一度判全档失效后更正为测量假象嫌疑——维持「嫌疑+未确证」；#246 自愈装机后仍观察一次，疑独立机制
  - 2026-08-27 八轮巨帧取证（PtrDiag 探针链）：冷启动进场窗口拖动全灭；平台把 2.5s 拖动合并成 2-3 巨帧（travel 完整）送达、列表认领却零消耗（consumed=0）；锚点战争/闩锁/输入缺失三族排除；v1 连接器形态机制性空转（勘误入档）、v2 Initial 隧道分块无效——下一步=守卫内打点看 dispatchRawDelta 返回值定界 app/框架
  - 八轮复核（research，6 冷启动全「冻」）：**判词修正**——自动化样本全部是「贴底 + 朝更新方向拖」= 范围尽头语义（本不该滚，无回弹反馈加剧死感），离底同手势即恢复（1399-1421px 全通）——即边缘语义而非 #245 本体；自动化未能复现「历史区中段死帧」；下一步=真人现场复现时记录列表位置（是否贴底）+ 录屏，再决定是否需要守卫内打点
  - → `docs/journal/2026-08-27-event-card-unification.md` §手势阶梯 · §八轮/#245 · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`
