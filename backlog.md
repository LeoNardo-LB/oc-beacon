# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#350**（2026-09-07 走查反馈登记 #346-#349）。

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

## P1 — 核心功能需求
- [~] **#346 需关注类通知被静默组汇总埋没——问题/权限/错误退出 server 分组独立成卡** `dsh` `notification` `bug`
  - 走查反馈取证(2026-09-07):用户 HOME 后「没看到有问题通知」,而 dumpsys 实证通知在场(id=724696787,importance=4,文案正确)——根因=三类高重要度通知 setGroup(server_x) 挂在 **LOW 重要度 tasks_silent 组汇总**下,MIUI 整组折叠成一行静默项,子卡不可见(同 E4② 组卡现象);独立卡正常(E4 实证)
  - 已修复:权限/问题/错误三构建器移除 setGroup+组汇总发布(轮完成静默流保留分组语义);顺带消解 E4②「组卡子项 tap 不可达」(不再有组卡)。**真机验证 ✔**:resync 重放 QuestionAsked→到达发布→groupKey=自身独立键(原 g:server_…),静默组汇总消失;cancel 后撤销链保持(通知消失)
  - → 附带定论:问题通知持久性=挂起期间恒在,轮终(应答/服务器超时)即撤——超时后卡片是死链,撤除正确;AUTO_CANCEL 允许用户手动消


- [~] **#326 busy 输入区单按钮统一——一键承担发送/入队，对齐 OpenCode 面（2026-09-04 用户定规）** `dsh` `ui`
  - 已实现（commit 555c2ba4）：单键状态机 idle→SEND / 忙+空→STOP / 忙+文本→SEND 排队（长按=steer #309④ 保留）/ inputBlocked（等待提问/权限）→STOP，对齐 web primaryStops；红→绿 12 用例
  - AI 真机验收全绿 10✔/0✘（四态全验/排队 vs steer wire 时序可分/blocked 恢复链/V2 回归/0 FATAL）：`docs/acceptance/2026-09-05-326-single-send-key.md`；**UIUX 卡待人工验收**（清单在该文档末节，与 #327 同域汇总提交）

- [~] **#320 DSH 事件系统通知——turn 结束/问题到达/审批等待 → Android 通知+deep-link（web turn-notify 对位）** `dsh` `sse` `ui`
  - 已实现（4e25b716）:审计发现发布链既有全覆盖,真缺口=三清除径同点撤通知+标题回退;验收 ✔（前台抑制边界/后台链 通知→面板点按 deep-link 进会话→应答→撤 census 归零,终轮 D1）:docs/acceptance/2026-09-06-final-combined.md;到达时语义（无退后台补发）→ #336 增强;**UIUX 待人工**
  - 非前台会话 turn 结束/question/approval → 系统通知点进会话；通知设置+deep-link+渠道基础设施全在（Settings→Notifications/host 事件流），纯接线；web 走 /turn-notify/focus-wait HTTP 长轮询，Android 用既有 WS 事件流即可
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #320

- [~] **#309 DSH 面对齐批 1·快速胜利：goal 完成/压缩呈现/Full access 确认/插话长按直发/重试 continue** `dsh` `ui` `sse`
  - 五子项代码全落地（审计 `docs/research/2026-09-05-audit-309-313.md`：①-④+⑤-b 既有,⑤-a 倒计时 148c0644）·**AI 真机验收 10✔+2BLOCKED**（A5 预设降级：max-tokens 未触达/重试不可确定性触发,单测作结）：`docs/acceptance/2026-09-05-309-batch1-and-328.md`——**UIUX 卡待人工验收**（与 #326/#313 同域汇总）
  - 横切铁律：新 SseEvent 三步全走（DEM 分支+EventDispatcher bind+handler 折叠，漏 bind 即静默丢弃，goal/change 曾中招）；触 composer 按 ChatScreen 编辑协议串行
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 1 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`（挂点明细）

- [~] **#310 DSH 面对齐批 2·主价值：子智能体续聊/消息反馈/Plan 模式/轨迹台账/会话源引用** `dsh` `ui` `session`
  - 五子项全实现（905f25fd/8a1b058b/93a6c9f8/b7822e7e/c7e530da/6a903a42/753ee6fc）+验收三轮全绿（验收驱动根因修复 4 项：6290102b 空 sid 跳过/8faf940c durable 地址双腿+父址键/4b463eec quoted 去引号/098e89a2 session-removed 降级+log-only 词汇）——报告 `docs/acceptance/2026-09-05-310-batch2-and-321.md`;**UIUX 待人工**（域汇总）
  - 子智能体续聊先做（UI 通道 100% 就绪，缺 `subagent.prompt/interrupt/history` 三方法，性价比最高）→ 消息反馈 👍/👎（气泡下动作行，禁长按）→ Plan 模式（chip+专卡）→ 轨迹台账+检查器（RenderableTurn 已预计算时间戳；时间轴缩放 L 不做）→ @ 会话源+mention 可点（文件源现成）；≈8-10 人日
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 2 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`

- [~] **#313 消息队列 UI 迁入 FAB——对齐「能力→容器」自有映射，废除 QueueDock 对 DSH Web dock 布局的照搬（2026-09-03 用户路线级裁决）** `dsh` `ui`
  - **映射原则（用户定规）**：DSH 的 goal/todo（消息框上方）、子代理/后台任务（面包屑）等面板类能力，在我们这里**一律进 FAB 菜单**（ChatFabMenu 现载 TODO/AGENT/GOAL/SHELL 四入口→自有 sheet）；QUEUE 同样处理：FAB 菜单项「队列(N)」→ QueueSheet（复用 QueueDock 行逻辑+三动作），输入条上方 dock 退役；流内内容（jobs 时间线卡/错误行/压缩分割线）不属面板、维持流内
  - 波及：#309④ steer 插话呈现面（入队展示走 FAB 入口）；#310-#312 一并遵守（Plan/deliverables/轨迹台账=自有 chip/卡片/行菜单形态）
  - → `docs/journal/2026-09-03-fix-308-dsh-respond-wire.md` §七（裁决记录 + 批 1 审计）；**主体已落地**（审计+验收：FAB 五入口/QueueSheet 三动作/QueueDock 已删/i18n ×15/映射原则遵守——`docs/research/2026-09-05-audit-309-313.md` + #327 验收报告）；最后断点（角标数据链）已由 #327 修复并真机全绿（角标 1/2/清零全生命周期）→ **UIUX 卡待人工验收**（与 #326 同域汇总）

## P2 — 优化与锦上添花
- [ ] **#347 归档交互改长按菜单——移除左划归档手势(用户走查裁决)** `ui` `session`
  - 裁决原文:「应该长按之后展示归档,而不是左划归档」——左划手势整体下线(SwipeToDismiss 组件连同 #342 修的揭示背景一并移除),归档仅保留长按菜单入口;长按菜单已有归档项,改动=删手势+回归 #342 红底断言转不适用
  - 关联:#342(左滑背景修复)随本卡废弃;#311①(归档功能本身)不受影响

- [ ] **#348 忙碌中发送改气泡选择框——对齐 opencode 交互(用户走查裁决)** `ui` `chat`
  - 裁决原文:「DSH面应该跟opencode一样的逻辑,在消息输出的情况下,出现气泡选择框而不是直接归位队列消息」——busy+发送不再直入队,先弹选择气泡(候选:排队/插话/中断,以 opencode 实际选项为准——动工前先考据 opencode busy-send 的选项集)
  - 关联:修订 #326 的单发送键裁决(忙碌+文本=单键直排队 → 改选择气泡);长按 steer 直发是否保留随考据定

- [ ] **#349 subagent 工具卡可点击查看详情——直达子会话(用户走查裁决)** `ui` `subagent`
  - 裁决原文:「subagent卡片理应有点击查看详情的能力」——聊天中 subagent 工具卡(运行中/完结)点击→打开对应子会话(复用 #310① 的子会话路由与 durable 父址);现有入口 FAB→智能体 AgentSheet 保留


- [~] **#343 DSH 单消息轮次台账缺失——完结信号与时长测量被 durationMs 单字段承载,零跨度完结轮整行被吞** `dsh` `ui` `bug`
  - 仪器批取证(2026-09-07,journal §二十四):600 词纯文本轮完结后无「轮次 N」行;Room 直查 b7b3cdc1——流式建行 dsh-t3s1 与完结行 seq-1246 各自 created==completed(DSH 整包事件零跨度模式),仅多行轮(如含工具的轮1 16m38s)能凑出正跨度;MaybeTurnLedgerRow 的 durationMs==null 即 return 门控把零跨度轮整行吞掉——#338 的「时长未知→『-』」语义只在渲染层、到不了门控
  - 反证:Host-4199(opencode V2)同 app 0 工具轮有台账(「轮次 2 · 30.4s · 1 步 · 0 个工具」)——DSH 映射层特有
  - **已修复(根因=语义解耦)**:RenderableTurn 新增 allStepsCompleted 完结信号(≥1 assistant 且全部带 completed),durationMs 只答「跨度可测与否」;MaybeTurnLedgerRow/MaybeProducedFilesRow 门控改判完结信号——零跨度完结轮照常渲染、时长列回落「-」(#338 宁缺毋谎),流式中仍缺席(SSE 铁律不变);统计栏等 durationMs 消费面仅显示用途零改动
  - **真机验证 ✔(2026-09-07)**:活体路径——新单消息轮轮末即现「轮次 3 · - · 1 步 · 0 个工具」(旧代码此场景无行);重载路径——历史 fold 双行(dsh-t3s1+seq-1246)凑出真实跨度 15.4s 正常呈现;RenderableTurnTest +4(零跨度/负跨度=完结,流式/空轮=未完结)
  - → 证据:/tmp/e2e-instr/db.db(双行零跨度)+ v343-run-10.xml(活体「-」)+ v343-chat.xml(重载 15.4s)
- [~] **#344 提问通知正文携带 system-reminder 前缀——补发空载荷走「最新用户消息」回退,捞到 DSH 注入语料行** `dsh` `notification` `bug`
  - 根因链(仪器批+本批取证钉死):#336 退后台补发**故意传空串**→AppNotificationManager 回退 findLatestUserMessages(最新用户消息)——而 DSH 把 skill catalog/workspace 指引按 user/message 入库(晚于真 prompt 毫秒级,seq-13 恰为最新),回退正文=注入全文;服务器侧问题文本本身干净(seq-207 arguments 实证),#339 消毒器只挂在到达路径直发文本上,回退路径从未消毒
  - **已修复(两层根因)**:①PendingInteractionStore 条目化(kind+text,记录时刻携带问题/权限原文,同 kind 空值不抹/非空覆盖)→补发携带真实载荷并消毒发布,不再走回退;②回退侧 findLatestUserMessages 选段谓词 isNotificationPreviewText(消毒后非空且不以标记开头——整条注入块/未闭合前缀拒收,嵌块+真文本放行)+预览管线统一 sanitizeNotificationText(嵌块剥除/全剥离跳行)
  - **真机验证 ✔(2026-09-07)**:同场景复测(挂起问题→HOME→dumpsys)通知正文=「Verify six forty four.」(真实问题文本),非 system-reminder;作答后通知撤销 ✓;store/补发器/预览 +11 单测(载荷携带/重放不抹/消毒发布/注入过滤三态)
  - → 证据:v344-q-1.xml(问题在场)+ dumpsys android.text 实录(修复前 system-reminder vs 修复后问题原文)


- [~] **#325 DSH token 首次配对体验——dev 注入脚本/QR 扫码/SSH 通道/sameBackend username 修复** `dsh` `security` `ui`
  - 四通道裁决落地（9ad9bb03+7a31a788）:adb 注入实现（dsh-pair.sh）/QR=深链降级（ocbeacon://pair 预填,验收 ✔ 终轮 E1-r2——根因=adb & 转义伪影+静默拒绝已修）/sameBackend username 修复✔/**SSH 裁决否决(2026-09-06)**:用户裁定暂不引入 sshj(~1.5MB 新依赖红线),配对维持 adb 注入+QR 深链双通道(局域网全覆盖),远程 SSH 场景出现再议;**UIUX 待人工**
  - 调研实证:token 仅存进程内存(重启轮换/不落盘/不可配置),无 LAN 静默发现途径(设计使然);cookie 365 天/authority——自动发现=首次配对问题;宿主 dsh-url 工具已带 QR 输出,app 粘贴框现成
  - 四子项:①debug-entry.sh 并 token 注入(现成)②QR 扫码(CameraX)③SSH 白名单通道(sshj)④DSH 条目 sameBackend 忽略 username;→ `docs/research/2026-09-04-dsh-token-autodiscovery.md`


- [~] **#322 DSH 服务端内容搜索——searchText stub 接 session/search** `dsh` `session` `data`
  - 已实现（c7170723:searchSessions 专属通道+服务器命中区+merge 纯函数;searchText 证为文件域 stub 保留注释）;验收 ✔（命中区+tap 进会话+无命中逆向,终轮 C1）;**UIUX 待人工**
  - 内容搜索现空（searchText stub）；客户端 FTS 仅覆盖本地已加载会话；web session/search 搜全部历史（名字+内容）；命中导航（ContentHitNavigation/jumpToMessageId）与筛选 chips UI 全在
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #322

- [~] **#323 斜杠命令执行反馈行——command/run|done 映射 EventCard** `dsh` `sse` `ui`
  - 已实现（ea52a106:两事件映射+commandId 原位单卡刷新+历史重放渲染）;验收 ✔（run→done 原位+未知命令无卡逆向,终轮 B1）;**UIUX 待人工**
  - DshEventMapper 现 Ignored(COMMAND) → /compact 等执行后无流内反馈；web 渲染 Running…/Completed/Failed（+图片附件拒绝提示）；SseEvent 三步全走铁律适用
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #323

- [~] **#311 DSH 面对齐批 3·组织面：工作区归档/deliverables/工具卡增补/状态点** `dsh` `ui` `session`
  - 六子项全实现（4ced86f5 数据层/0498e775 归档 UI/0fd15da8 多 workspace/3ac2dd71 deliverables+工具卡/a69fd71a 待审批点——**契约事实:归档单向无取消**;FSM 零动,采 web 本地 pending 域）;验收 13✔+3 BLOCKED-harness routing（run_code 内联族,契约同构休眠）:docs/acceptance/2026-09-05-311-batch3.md;**UIUX 待人工**（域汇总）
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 3 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`

- [~] **#312 DSH 面对齐零星 S 级池：相对时间戳/KaTeX/spill 提示/命令带图限制/消息级分支锚点** `dsh` `ui`
  - 四子项落地（f6e288b7+4b5f1618;③spill 转 #332 P4）;验收 6✔+A2 终裁✔（markdown 面,用户卡纯 Text 既有设计）:docs/acceptance/2026-09-05-312-s-pool.md——fork wire 112ms+导航/拦截 wire 级不派发/相对时间戳三形态;**UIUX 待人工**（域汇总;含下轮补一发助手面数学定向确认）
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §12.3

## P3 — 观察与低价值改进

- [ ] **#345 adb 注入 tap 间歇丢弃观察——MIUI 平台行为定性(非 app 缺陷),真手指未复现即不处理** `env` `device`
  - 定性修正(2026-09-07 二查):原「两案全灭」重析后——**第二案翻案**:Doubang 输入法为浅色主题,screencap 下半屏与 app surface 同色族 (247,250,253),误判「无 IME」后 tap 实际全打在键盘上;7 节点 dump=输入法安全窗致盲(平台正常)。第一案(t4401 克隆任务后 composer 聚焦 tap 无响应)仍疑似 MIUI 注入丢弃家族(同 E4② shade 组卡先例);两案中键事件/焦点全程有效(`dumpsys input_method` mServedView 在场实证),app 侧无缺陷证据
  - 本批定向复现未再现(IME 抬起+乱序 tap 串轰击后交互正常);缓解纪律已沉淀 real-device-testing.md(IME 判定用 dumpsys input_method 勿用像素分析;tap 失活二分定位;冷启恢复配方)。保持观察:真手指复现才升级为 app 卡
  - → 证据:journal §二十五 #345 节 + /tmp/e2e-instr/r1-r3.xml(复现尝试全程交互正常)


- [~] **#336 审批/提问通知「退后台补发」——已实现（279b7639+b95a2bc9）** `dsh` `ui`
  - 已实现:fg→bg 转换沿扫描 pending 已通知槽去重防重放;验收 ✔（终验 A4 三段链:前台抑制→HOME 补发在场→面板点按直达→应答撤）;#337 顺修（revoker 父槽镜像+共享冒泡函数三侧统一）;**UIUX 待人工**（通知域汇总已含）

- [~] **#324 DSH 设置面深度对齐缓行池——provider/模型目录 CRUD·插件配置与清单·preset 管理·skills 触发组** `dsh` `ui`
  - 四域全交付（4fb237b1/6b0b0c3c/c3df0b01/a1a835dc+崩溃修 3cb324a8）;验收 ✔（F1 提供方页 r2 不崩+目录在场/F2 preset/F3 插件清单+表单/F4 skills 分组,终轮）;凭据只写不回显/清单只读（web 同构）;**UIUX 待人工**
  - web Settings 深度面：自定义 provider 增删+discoverModels、插件配置卡（shell 超时/agent loop/web search/子代理模型）+pluginInventory 清单、agentPresets 管理 CRUD、"/"菜单 skills/list 触发组；beacon 现有 auth+过滤+选择器，缺 CRUD/清单
  - 移动端价值中等缓行；ServerSettingsContent/ProvidersScreen 行范式可直接扩 → `docs/research/2026-09-04-dsh-web-parity-round2.md` #324

- [~] **#342 会话列表整列红底——SwipeToDismissBox 背景无条件常驻+行内容透明,errorContainer 恒透出** `ui` `bug`
  - 根因(M3 1.4.0 源码+字节码定音):backgroundContent 作为全尺寸 Row **无条件常驻**在内容后方,SessionRow 前景 Row 无底色(透明)→ errorContainer 透出=红列表;自 #311 Task2(0498e775,09-05)上线起恒在——XML dump 验收色盲+「左滑手感」人工项(L125)未做双漏;09-06 全量 E2E 列表截图全红(A0/A1/A2/B10/B14 等 12+ 张,像素 (128,39,31) 暗/(245,223,221) 亮)未被任何断言捕获
  - 已修复:SwipeToArchiveBackground 接 dismissState,rest/复位态不绘制(透出列表 surface),仅负向位移(左滑揭示中)绘制——shouldRevealArchiveBackground 纯函数判定(-1f 亚像素容错);单测 2 例;真机像素复验 ✔(行区 (247,250,253) surface,红仅左滑中);**UIUX 手感终验待人工**
  - → 证据:/tmp/e2e-fix34x/list-now.png 采样 + /tmp/e2e-full 历史红列表时间线 + M3 SwipeToDismissBox.kt(jsdelivr androidx-main 对照+本地 1.4.0 字节码无 offset/zIndex/alpha 门)

- [~] **#341 发送确认弹窗永不关闭——确认/取消回调缺 showSendConfirmDialog 复位(曾误判 adb 注入免疫)** `ui` `bug`
  - 根因(静态实锤,2026-09-07):ChatScreen 的 onConfirmSend/onDismissSendConfirm 只清 pendingSendAction,**不复位 showSendConfirmDialog**→标志永真,对话框永不离开(真手指同样卡死,非 adb 特有;对照 Rename 弹窗有正确复位 L1026);默认 confirmBeforeSend=false 故历史测试全绿
  - 已修复:两回调补 showSendConfirmDialog=false;真机双路径验证 ✔(取消→弹窗即关+草稿保留;确认→弹窗关+Sent prompt 落账 08:00:57);**顺手真指确认即可关卡**
- [~] **#338 轮次/思考时长跨钟域混腿——completed 本地钟回填污染+流式 ticker 负值+零跨度显示 0ms** `dsh` `ui`
  - 已修复(7c9fddc7+caa81fb8) 三层根因实证钉死:①W2 B4「-207ms」实为 StreamingElapsedText 无下限钳制(startMs=DSH 服务器信封,设备钟慢 207ms 窗口内为负——非台账腿);②工具宿主 completed 由 markSessionIdle 本地钟回填,Room 实证 completed=created+3.5h(resync 期回填)且 merge incoming?:existing 使污染永久残留——改域一致回填(EventDispatcher 采集 MessageUpdated.created/SessionIdle.time 同域基准,逐消息 max 钳制)+历史残留自愈消毒(事件权威完成缺席且超域水位10min→null,随落盘修复Room);③零/负跨度=时长未知→null(台账"-"/思考无时长变体,新 i18n 键×15)
  - **真机验收 ✔(2026-09-07 07:0x)**:resync 后 B4 全部 9 个工具宿主 completed 修复为域内时刻(call_17:+3.5h→轮末信封时刻),轮1台账回到 3.7s;「思考完毕」无 0ms 后缀(三 dump 全 0);轮4台账 3.2s 正常;残留 2544 条 dsh-call 存量集中在未回放旧会话(打开即自愈,渐进语义);证据 /tmp/e2e-fix34x/(db pull+dumps+logcat);**UIUX 终验待人工**(时长呈现主观确认)

- [~] **#339 重连 resync 通知族缺陷——伪 Idle 边沿误撤+旧错误轮重发+注册表未水化阻断+通知文本系统注入** `dsh` `notification`
  - 已修复(b2a84aea) 四子缺陷根因闭环:①径②清除延后复核(2s settle+仍 Idle 才清——W5 实证伪边沿 Idle→.725 即回 Busy,通知层同事件已判 6min 陈旧而 pending 域无判);②SessionError 携带 turn/end 信封时刻+#294 同款 5min 陈旧过滤;③重放用户消息(created 陈旧)不再重置 streak(曾致「错误·hi」×7-8 连环通过);④重发布前有界等待注册表水化(250ms×12)+question/error 文本剥离 <system-reminder> 注入语料;断开撤除+TTL 仍留产品裁量(未实现)
  - → 单测 +8(PendingInteractionStoreTest×2/SessionNotificationCoordinatorTest×6)+DshEventMapperTest 时刻契约;**真机验收 ✔(2026-09-07 07:20 活体)**:构造新挂起问题(red/blue,服务器侧 Busy)→通知发布→断开→重连 resync→QuestionAsked 重放→通知**重发布**(id=428455022 回场)+**零误撤**(Revoked=0,四轮 resync 全 0)+旧错误轮零重发(stale error 13 skips)+旧 idle 124-129 skips;证据 /tmp/e2e-fix34x/app5-app7.log;断开撤除+TTL 产品裁量未实现(留用户裁决)

- [~] **#340 resync 期 Room 持久化背压丢写——BUFFERED 满即丢写(含终态修复写)** `dsh` `storage`
  - 已修复(07069ad0) 管线两路重构:全量 upsert 按 (sessionId,messageId) 最新快照合并(latest-wins 幂等)+按消息数阈值(128)/最大时延(250ms)批量刷洗(每会话单次调用=单事务,吞吐数量级↑);增量 delta 走 UNLIMITED 保序队永不丢(流式速率有界);旧 trySend 满即丢路径删除
  - → 单测 +4(MessageEventHandlerCoalescingPersistTest:5000 条洪峰零丢失+最新快照合并+批量上界+delta 保序);**真机验收 ✔(2026-09-07)**:四轮 resync 全程 dropped WARN=0(旧版同场景 9 条/N=1150-1500),合并刷洗 17/14/4 批×195-300 msgs/批,sessions=2-3/批;库 integrity=ok(12,981 条);证据 /tmp/e2e-fix34x/app2-app7.log

## P4 — 外部前提阻塞

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
