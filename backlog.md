# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#286**。

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

- [ ] **#267 服务器断连可感知：Chat/会话列表常驻条幅 + 写操作快速失败报错** `sse` `ui`
  - 现状：连接真相源已有（SseConnectionManager.connectedServerIds + 自动重连 + REST 补漏），仅 Home 圆点消费；Chat/会话列表断连零感知，写操作失败悬挂 20s+
  - 方案（2026-08-30 用户裁决：简单做）：零 UI 禁用——两界面常驻细条幅（恢复自动消失）+ REST 失败回灌一期 + mutation 快速失败报「服务器已断开」；per-serverId 键控；草稿保留；workspace 面板二期
  - → `docs/specs/2026-08-30-server-disconnect-gating-design.md`

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

- [ ] **#285 DSH 斜杠命令补全的会话龄缺口：懒建会话/首连期命令列表空 + commands/change 事件未消费** `dsh` `ui`
  - 2026-08-31 定音：commands/list typert 通道活体可用且已实现（能力位转真 + 选择填入/执行）——残留缺口：新会话懒建（sessionId 空）时 loadCommands 只跑一次 → 建会话后服务端命令不刷新；commands/change 事件（命令注册变化）未订阅；技能在 DSH 无对应域
  - 方向：sessionId 就绪后重载 commands + 订阅 commands/change 失效重取（参照官方 CommandDirectory warm/invalidate）；验证=真机输入 / 看服务端命令列表
  - → docs/journal/2026-08-31-goal-token-ring-slash.md


- [ ] **#281 androidTest 编译基线破损：fakes 缺接口成员 + ArchiveBucketDaoTest 字段漂移** `refactor`
  - 2026-09-25 发现（AgentSheet 树化批次顺带）：compileDevDebugAndroidTestKotlin 在基线 64079b57 即失败——FakeServerRepository 缺 promoteDebugBackend、FakeSessionRepository 缺 listSessionsPage、ArchiveBucketDaoTest 引用已不存在的 leastAccessed/lastAccessedAt
  - 单测 2376 基线不受影响（仅 androidTest 源集）；修复=补齐 fakes 成员 + 对齐 DAO 测试字段

- [ ] **#280 DSH agent 预设切换（#276 遗留尾）：agentPreset 仅 blank 会话可设的语义适配** `dsh` `ui`
  - 现状（2026-08-31 调研）：`agentPreset.select` 仅对 blank（零轮次）会话生效，首轮后 agent-preset-locked；`agentPreset.list` 是会话构成模式（standard/code/minimal/cordis），非 V2 每-prompt agent 切换语义；`session.prompt` 无 agent 参数
  - 方向：模型切换链已通（e8a90d67），本卡需 UI 层 blank 门控（有轮次即隐藏/禁用 agent 选择）+ 语义重设计（预设=建会话时选，非发送时切）
  - → `docs/api/dsh-openapi-notes.md`

- [ ] **#282 DSH 特性批重构群（双轴审查 Standards 轴）——4 处同形逻辑提取** refactor
  - DshApiClient settings 域四方法同形（describe/mutate 仅 ns/key 差）；SessionEventHandler 四新 handler 同形折叠可提 updateSession；SubagentTreeDelegate 双 DFS 仅行映射异；TaskDelegate 两处排序重复；DshPermissionDefault/DshAgentPresetDefault 同形双值类型
  - → docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md §9

- [ ] **#283 权限默认档动态渲染 + projection permissions 键闭合（双轴审查 Spec 轴 a1/a2）** dsh
  - PermissionDefaultRow 硬编码三档（PERMISSION_PRESET_VALUES），未按 settings.describe options/schema enum 动态渲染（输入切换器已动态，双轨不一致）；session/projection key=permissions 帧仍 Ignored——中途切档投影推送被丢（现靠 list 投影+三 knob 事件，够用未闭环）
  - → docs/research/2026-08-31-dsh-permission-sandbox-approval.md

- [ ] **#284 特性批小项集（审查判断性提示）** polish
  - SubagentTreeHolder.degraded 全局开关→逐层降级（任一子层失败现整树退回本地镜像）；JobView.status 立 enum（isRunning 用裸 running、kind==diagnostic 裸串）；DshJobsStore.jobsFor()/clear() 与 JobView.isRunning 死代码处置；14 个翻译文件新 key 同列追加破坏一行一 key 惯例（批量整理）
  - → docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md §9

- [ ] **#278 DSH 僵尸 Busy 的 L3 自愈缺失——无状态端点下的真相源设计** `infra`
  - 现状：DSH fetchSessionStatus 恒空 map + directory 空时 absent→idle 跳过；对账回放已治主径（015ed7de），本卡为无 turn/end 终态异常会话的兜底
  - 方向：对账完成后按回放终态强制收敛 FSM，或 session.list running 字段播种状态
  - → `docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md`

- [ ] **#279 导出 SAF intent MIME 按服务器类型设置（ChatScreen 解冻前置）** `ui`
  - 现状：DSH 导出 SAF 预填 .json→落盘 .zip，落盘前 renameDocument 兜底可用（终验 V6' PASS）
  - 解冻后：exportIsArchive 能力位直通 SAF intent mime，预填即正确
  - → `docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md`

- [ ] **#277 单测偶发跨类污染：UncaughtExceptionsBeforeTest（Dispatchers.Main 未设窗口泄漏）** `test`
  - 现象（2026-08-31 实证）：ChatViewModelQueuedTest 偶发 UncaughtExceptionsBeforeTest（原始异常=「Main was accessed when the platform dispatcher was absent」）；顺序/时序依赖
  - 根治尝试（2026-08-31，用户纪律）：12 连跑专用复现循环 + 此前 6 连绿 = **18 连绿未复现**（初估 ~14% 系单红后时序域漂移）；修复条件不满足（无法复现+失败 XML 已被 --rerun 覆盖）→ 按纪律留存
  - 方向：再现时**保留 XML**（勿 --rerun 覆盖）提取完整栈定位泄漏类后即修（疑 VM 协程在 resetMain 后醒）

- [ ] **#258 fling 高速段重 item 组合帧 50-130ms——预组合与渲染同线程争抢的结构性上限** `perf` `ui`
  - 现象（2026-08-29 测量矩阵，journal 二十八轮）：高速 fling（60ms 甩）下新 item 首组合帧 p95 65ms/p99 129ms；prefetch ON 亦 p90 53ms（预组合与渲染抢主线程）；中速滚动全绿（jank 0.00-0.23%）。崩溃根因（331365999 家族）已经 ComposeFoundationFlags flag=false 机制级修复，本项纯性能
  - 测量：gfxinfo 三配置矩阵 + SafeFling 出口内速日志（全部自然衰减尾，无异常终止）；atrace 需先 Tracing.enable 才有 compose 段
  - 方向：Tracing.enable + perfetto 定位组合热点 → chunk 组合瘦身（block 级惰性/SelectionContainer 开销/ clickable wrapper 精简）；新线索（2026-08-30 调研）：androidx 1.13.0-alpha02 `ComposeUiFlags.isVectorDrawCacheSharingEnabled`（VectorPainter 列表缓存共享）待试 → `docs/journal/2026-08-27-event-card-unification.md` §二十八轮


  - 待验证：用户真机确认已完成思考卡时长正常 → `docs/journal/2026-08-30-backlog-triage-closure.md`

## P3 — 观察与低价值改进

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
