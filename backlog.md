# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#386**（2026-09-10 #385 上下文注入精简卡片化）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |
| **P4** | **外部前提阻塞**：功能/工作方向明确，但实现前提在 app 之外（服务器能力缺失 / 上游未合 / 用户流程门槛），前提满足前不可动工——**卡内必含「前提」行**（前提是什么、现在为何做不了）；前提变化时重验归位 | 服务器未暴露的事件聚合、上游 PR 候选清单 |

**修复方针**（2026-09-03 用户定规）：bug 类条目**根因修复优先**——交付的修复必须消除触发链的根因层（架构 / 生命周期 / 状态机 / 协议缺陷），不以表象层兜底单独交差（「缓存兜底显示」「重试遮罩」「吞异常」等手段不得作为修复本体）。兜底类缓解仅在同时满足以下条件时接受：①对应根因修复卡已登记并被引用；②兜底卡摘要显式标注「过渡措施」并关联根因卡编号。分层不明确或拿不准时，先向用户呈现根因分析与分层方案，裁决后再落卡/动工。

**验证方针**（2026-09-03 用户定规）：**绝大多数情况（含 UIUX/交互类改动）都应通过真机端到端测试验证并关闭**，`[~]` 不按「是否 UIUX」划分，按「是否存在可注入/可观测的自动化仪器」划分。可用仪器（持续积累）：uiautomator dump/tap（导航+断言，注意 LazyColumn 视口冻结——dump 前先滚顶）、logcat/Room 日志直查、`/proc/net` socket 观测、debug 注入工具（#305 `--ez debug_simulate_timeout` 先例）、curl 模拟服务器侧事件（POST 建会话/订阅 SSE 抓帧）、网络扰动（nc 黑洞 + `adb reverse` 重定向 + `adb kill-server` 断既有连接）、`pm install` 静默装包 + `debug-entry.sh` 确定起点。**仅以下情形才需要人工介入**：①真手指连续手势/体感类——注入手势是平台批处理伪影，无法模拟真手指位移流（#245 两轮证伪）；②需用户凭据/跨设备操作（GitHub 密码/2FA、扫码等）；③数日级真实使用观察（不可加速，如挂机断链观察）；④主观体验拍板类（「好不好用」的最终确认）。判定次序：先穷举仪器→构造红回路→E2E 验证关闭；真找不到仪器才登记人工项并写明卡内为何仪器不可行。

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



（#308 已完结迁 journal：2026-09-05 AI 真机验收关卡，见 `docs/journal/2026-09-04-fix-308-always-326-327.md` §十一）
（#356/#354/#358/#357 已完结迁 journal：2026-09-08 验收演示批（A 模式五节点全过），见 `docs/journal/2026-09-08-2.md` §三-§六；演示期新卡 #360-#365 待办）

## P1 — 核心功能需求
（#378 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#375 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#376 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#377 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#374 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#380 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#363 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#326 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#313 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#383 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））




（#365 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））

（#346 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#320 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#309 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#310 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））

## P2 — 优化与锦上添花

（#372/#366/#367/#353 已完结迁 journal：2026-09-09 演示批过验，见 `docs/journal/2026-09-09-365-353-359-uiux-consistency.md` §八）
（#382 已完结迁 journal：2026-09-09-docs-consolidation.md（2026-09-09））
（#379 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））

（#355 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#351 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#348 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#343 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#323 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#325 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#311 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#312 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#322 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#344 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#347 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））



（#349 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））

- [ ] **#385 上下文注入以大段原文渲染——压缩摘要/系统提醒应参考 DSH Web 精简卡片化（演示①用户裁决）** `ui` `dsh` `command` `design`
  - 演示①观察：压缩完成后转录中摘要+保留上下文（available_skills 技能目录全文、system-reminder 标签原文）以大块文本墙呈现（约一屏半），其下才接模型回复——信息噪音大、难扫读
  - 用户指令：「参考 dsh web，做成精简卡片的形式，而不是用一大段话来注入」——DSH Web 对 compacted-summary/system-reminder=紧凑引用块（默认折叠、可展开）
  - 设计面：注入类内容（压缩摘要/system-reminder/技能目录）精简卡片——标题+摘要行+按需展开；正文不默认铺开
  - 实现注意：先钉实体来源（该文本墙是 CompactionTranscriptCard 展开态还是消息面原始 text part 渲染）再定挂点；与 #384 同域同批修

- [ ] **#384 DSH /compact 双卡并存——命令卡与压缩 box 同屏两张，违背 #374/#378 单卡承载裁决（演示①用户裁决）** `dsh` `ui` `command` `bug`
  - 演示①实况（2026-09-10 05:2X）：进行中相位=/compact 执行中...命令卡+会话压缩 压缩中...box 两张；完成相位=/compact 已完成命令卡+摘要实体仍两个
  - 根因方向：#378 Phase C 回收 tailSpec suppressByLiveCompactCommand 让位参数后，CommandFeedback（command/run|done 折叠）与 CompactionEntry（compaction/* 折叠）两族无互斥/吸收规则
  - 裁决链：#374「压缩应在同一张卡片里完成所有动作」→#375/#378「流内 box 全程承载」；终态应为 /compact 期间仅 box 一张（状态标签承载执行中/已完成），命令卡让位或并入
  - 证据：/tmp/acc1_inprogress.png、/tmp/acc1_done.png（vision 双帧确认）；头部徽标「2」待解（一次 /compact 后显示 2——修复分析时用服务器真值钉死）

## P3 — 观察与低价值改进

（#359 已完结迁 journal：2026-09-09 演示批（§八）；旧「#372 三面面板 tap 行为不一致」观察卡系 #372 裁决前登记的重复卡，随终卡一并迁出清理）
（#368 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#369 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#370 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#371 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#373 已完结迁 journal：2026-09-09-378-380-wire.md（2026-09-09））
（#338 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#342 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#341 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-09））
（#324 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#336 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#339 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））
（#340 已完结迁 journal：2026-09-09-delegated-acceptance.md（2026-09-10））

- [ ] **#345 adb 注入 tap 间歇丢弃观察——MIUI 平台行为定性(非 app 缺陷),真手指未复现即不处理** `env` `device`
  - 定性修正(2026-09-07 二查):原「两案全灭」重析后——**第二案翻案**:Doubang 输入法为浅色主题,screencap 下半屏与 app surface 同色族 (247,250,253),误判「无 IME」后 tap 实际全打在键盘上;7 节点 dump=输入法安全窗致盲(平台正常)。第一案(t4401 克隆任务后 composer 聚焦 tap 无响应)仍疑似 MIUI 注入丢弃家族(同 E4② shade 组卡先例);两案中键事件/焦点全程有效(`dumpsys input_method` mServedView 在场实证),app 侧无缺陷证据
  - 本批定向复现未再现(IME 抬起+乱序 tap 串轰击后交互正常);缓解纪律已沉淀 device-testing.md(IME 判定用 dumpsys input_method 勿用像素分析;tap 失活二分定位;冷启恢复配方)。保持观察:真手指复现才升级为 app 卡
  - → 证据:journal §二十五 #345 节 + /tmp/e2e-instr/r1-r3.xml(复现尝试全程交互正常)


## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）


- [ ] **#350 V1/V2 归档 API 接线——统一归档面收尾（统一审计批 4）** `v2` `archive`
  - 方向(若端点就位):V2ApiClient.updateSessionFields 补归档真线面(现仅 title 走 rename,归档字段 no-op 回 getSession);ServerCapabilities V1/V2 archiveSupported 翻 true→长按菜单归档项+已归档折叠区自动统一(能力位门控现成)
  - **2026-09-07 深夜探针定音(否定)**:服务器修复后实测——opencode2 beta-19086 自家 OpenAPI(/openapi.json,119 路由)**零归档端点**(无 /archive、无 home 域、PATCH /api/session/{id}=404、POST/PATCH /archive=404);审计前提「官方 web 有 archiveHomeSession」对本服务器版本不成立。V1 PATCH /session/{id} 仅 V1 面文档、本环境无 V1 服务器可证
  - **前提**：上游 opencode 服务器发布归档端点(OpenAPI 出现 archive/home 域)——届时报错即改+真机验收;环境已修复留档:坏因=postinstall 未跑完(stub 占位),本地平台包完好,`node postinstall.mjs` 离线修复,服务已恢复监听 4199
  - **裁决优先级（2026-09-09 定规）**：以节点 2 最新裁决为准——删除优先，归档仅在删除无 API 的面使用；V1/V2 已有删除 API，上游归档端点就位≠自动接线，届时须先回用户重裁
  - 前提加固（2026-09-10 端点考古）：V1-4198 无任何会话更新端点（PATCH/PUT 落 SPA 兜底 200 HTML）；V2-4199 无 PATCH /session（rename=POST /session/{id}/rename 单动词）；两服均无归档写入通道——归档接线前提（服务器暴露 time.archived 写）在两现行版本均不成立，维持 P4 待服

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
