# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#325**。

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

- [~] **#308 DSH 权限/提问应答 wire 不匹配（载荷缺键 + allowed-always 词不存在 + RpcReceipt 解码失败）** `dsh` `permission` `data`
  - `replyToPermission` 只发 `{outcome}` 缺必填 `sessionId`+`approvalId`，`allowed-always` 在 dsh 0.1.1-rc.2 全树零命中（枚举仅 `allowed-once|rejected`）——「始终允许」无服务端对应，A-D7-02「服务器落持久规则」系 OpenCode 语义误植；`/api/respond` 回执 RpcReceipt 非信封，`exchange()` 解码必失败 → DSH 三键应答恒 false（超时兜底掩盖）；提问应答/取消载荷同不符
  - 修复方向（根因层）：载荷补三键 + RpcReceipt 解析分支 + 提问改 `{sessionId,answer:{answers[]}}` + 取消改 Err 信封 + always 改本地规则自动 `allowed-once` 重答；验证=真机 DSH 审批三键 + logcat `accepted:true`
  - → 取证 `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md`（四重证据链 + E2E 出处勘误；§五 研究文档勘误随本卡验收后回写）· 修复批次 `docs/journal/2026-09-03-fix-308-dsh-respond-wire.md`（§九 **真机 E2E 双门禁 PASS**：G1 提问 22:24:16 result success=true + G2 审批 22:37:28 success=true + 升级 bash 真实落地 marker 文件；单测/全量绿）——待用户验收

## P1 — 核心功能需求

- [ ] **#320 DSH 事件系统通知——turn 结束/问题到达/审批等待 → Android 通知+deep-link（web turn-notify 对位）** `dsh` `sse` `ui`
  - 非前台会话 turn 结束/question/approval → 系统通知点进会话；通知设置+deep-link+渠道基础设施全在（Settings→Notifications/host 事件流），纯接线；web 走 /turn-notify/focus-wait HTTP 长轮询，Android 用既有 WS 事件流即可
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #320

- [ ] **#309 DSH 面对齐批 1·快速胜利：goal 完成/压缩呈现/Full access 确认/插话长按直发/重试 continue** `dsh` `ui` `sse`
  - 五项全第一档（UI 已就绪纯接线，≈3 人日，不动 ChatScreen 协议文件或只轻触）：goal.complete 第四钮（API 全链在位）·压缩事件接线（CompactionCard 双态 UI 完整，DshEventMapper Ignored 未接）·Full access 二次确认（PermissionPresetSelector+现成 ConfirmDialog）·steer 长按直发（wire mode 已在）·重试倒计时+max-tokens continue 钮
  - 横切铁律：新 SseEvent 三步全走（DEM 分支+EventDispatcher bind+handler 折叠，漏 bind 即静默丢弃，goal/change 曾中招）；触 composer 按 ChatScreen 编辑协议串行
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 1 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`（挂点明细）

- [ ] **#310 DSH 面对齐批 2·主价值：子智能体续聊/消息反馈/Plan 模式/轨迹台账/会话源引用** `dsh` `ui` `session`
  - 子智能体续聊先做（UI 通道 100% 就绪，缺 `subagent.prompt/interrupt/history` 三方法，性价比最高）→ 消息反馈 👍/👎（气泡下动作行，禁长按）→ Plan 模式（chip+专卡）→ 轨迹台账+检查器（RenderableTurn 已预计算时间戳；时间轴缩放 L 不做）→ @ 会话源+mention 可点（文件源现成）；≈8-10 人日
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 2 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`

- [ ] **#313 消息队列 UI 迁入 FAB——对齐「能力→容器」自有映射，废除 QueueDock 对 DSH Web dock 布局的照搬（2026-09-03 用户路线级裁决）** `dsh` `ui`
  - **映射原则（用户定规）**：DSH 的 goal/todo（消息框上方）、子代理/后台任务（面包屑）等面板类能力，在我们这里**一律进 FAB 菜单**（ChatFabMenu 现载 TODO/AGENT/GOAL/SHELL 四入口→自有 sheet）；QUEUE 同样处理：FAB 菜单项「队列(N)」→ QueueSheet（复用 QueueDock 行逻辑+三动作），输入条上方 dock 退役；流内内容（jobs 时间线卡/错误行/压缩分割线）不属面板、维持流内
  - 波及：#309④ steer 插话呈现面（入队展示走 FAB 入口）；#310-#312 一并遵守（Plan/deliverables/轨迹台账=自有 chip/卡片/行菜单形态）
  - → `docs/journal/2026-09-03-fix-308-dsh-respond-wire.md` §七（裁决记录 + 批 1 审计）

## P2 — 优化与锦上添花

- [ ] **#321 DSH @文件引用补全为空——findFiles stub 接 fileReferences/list** `dsh` `ui` `data`
  - DshApiClient.findFiles 返回 emptyList → DSH 服务器上 @ 补全弹层永远空（composer 核心功能静默失效）；web 数据源 fileReferences/list（workspace-rooted 可下钻），UI（FileMentionSuggestions/VisualTransformation/草稿链）零改动
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #321

- [ ] **#322 DSH 服务端内容搜索——searchText stub 接 session/search** `dsh` `session` `data`
  - 内容搜索现空（searchText stub）；客户端 FTS 仅覆盖本地已加载会话；web session/search 搜全部历史（名字+内容）；命中导航（ContentHitNavigation/jumpToMessageId）与筛选 chips UI 全在
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #322

- [ ] **#323 斜杠命令执行反馈行——command/run|done 映射 EventCard** `dsh` `sse` `ui`
  - DshEventMapper 现 Ignored(COMMAND) → /compact 等执行后无流内反馈；web 渲染 Running…/Completed/Failed（+图片附件拒绝提示）；SseEvent 三步全走铁律适用
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #323

- [ ] **#311 DSH 面对齐批 3·组织面：工作区归档/deliverables/工具卡增补/状态点** `dsh` `ui` `session`
  - 工作区组织（归档+行菜单+左滑先行；多 workspace 真建模 L 缓行）→ deliverables 产出文件行（**依赖 #310 @ 会话源**）→ 工具卡增补（question/skill 行）→ 等待审批/提问状态点（动 SessionStateFSM 前先读架构文档承重规则）；≈6-8 人日
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 3 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`

- [ ] **#312 DSH 面对齐零星 S 级池：相对时间戳/KaTeX/spill 提示/命令带图限制/消息级分支锚点** `dsh` `ui`
  - 五点均 S 级锦上添花，随批 1-3 顺手带或单独小批；分支锚点=补轮尾锚点 UI（session.fork atSeq API 已消费）；**批 2/3 一并遵守 #313 原则：UI 形态=自有组件（sheet/chip/卡片族），禁照搬 DSH Web 布局**
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §12.3

## P3 — 观察与低价值改进

- [ ] **#324 DSH 设置面深度对齐缓行池——provider/模型目录 CRUD·插件配置与清单·preset 管理·skills 触发组** `dsh` `ui`
  - web Settings 深度面：自定义 provider 增删+discoverModels、插件配置卡（shell 超时/agent loop/web search/子代理模型）+pluginInventory 清单、agentPresets 管理 CRUD、"/"菜单 skills/list 触发组；beacon 现有 auth+过滤+选择器，缺 CRUD/清单
  - 移动端价值中等缓行；ServerSettingsContent/ProvidersScreen 行范式可直接扩 → `docs/research/2026-09-04-dsh-web-parity-round2.md` #324

## P4 — 外部前提阻塞（暂不可实现）

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
