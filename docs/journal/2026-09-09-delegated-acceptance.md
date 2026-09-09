# delegated-acceptance（2026-09-09）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 一、委托自动化验收·第一批（高重叠组 7 卡）

**委托依据**：用户 2026-09-09 指令（全权委托自动化多模态验收，离席期间全自动完成）。本批卡片此前均为「代码完成+自动化/真机验证全绿，仅差用户人工签收」状态；本轮按委托以「既有验证记录 + 今日活体复证 + 多模态视觉」三层闭合。

| 卡 | 既有验证 | 今日活体/视觉复证（设备 adb-e69a99d8…，2026-09-09 20:0x-21:3x） |
|----|---------|------------------------------------------------|
| #326 单键状态机 | 红绿 12 用例 + AI 验收 10✔ | idle→SEND / 忙+空→停止（desc="停止" dump 实证）/ 忙+文本→「发送（排队）」（dump 实证）三态全观察 |
| #313 队列 UI 迁 FAB | 审计+验收（FAB 五入口/QueueSheet 三动作/QueueDock 已删） | 任务菜单→排队队列→QueueSheet 打开+刷新全链路今日再走通（演示⑦） |
| #351 QUEUE 入口门控 | queueSupported 位实现 | V2 面 QueueSheet 可用（inbox 拉取面，#356/#370 语义演化后 V2 队列域在场——门控随能力位正确放行）；DSH 面同入口在场 |
| #348 busy 气泡菜单 | 真机六腿全链验证 2026-09-07 | 忙时点发送→气泡菜单弹出（视觉像素定位「消息排队」项并成功点选）→入队「排队中」徽标→轮末派发→轮 12 承接（演示⑦全链） |
| #343 零跨度轮台账 | 根因修复+真机 ✔ 2026-09-07 | DSH 演示会话「轮次 1 · - · 1 步 · 0 个工具」今日在场（零跨度行不再被吞，时长列回落「-」） |
| #338 时长跨钟域 | 三层根修+真机 ✔ 2026-09-07 | 今日全程时长呈现健康：思考完毕·4.0s/311ms、轮次 2m50s/11m21s/6.6s/9.8s/581ms，无 0ms/负值/混腿 |
| #342 会话列表红底 | 根修+像素复验 ✔ 2026-09-07 | 多模态抽样今日列表截图：行区 RGB(250-252,250-252,250-252) 近白 surface，零红底（红点仅为未读角标 8-10px） |

**#351 附注**：卡内「V1V2=false」为 2026-09-07 时点语义；#356/#370 批次后 V2 队列域（服务端排队+inbox）成立，能力位随之放行——以最新裁决为准（backlog 裁决优先级定规），非门控缺陷。

## 已完结卡片迁入（2026-09-09）

### **#326 busy 输入区单按钮统一——一键承担发送/入队，对齐 OpenCode 面（2026-09-04 用户定规）** `dsh` `ui`
  - 已实现（commit 555c2ba4）：单键状态机 idle→SEND / 忙+空→STOP / 忙+文本→SEND 排队（长按=steer #309④ 保留）/ inputBlocked（等待提问/权限）→STOP，对齐 web primaryStops；红→绿 12 用例
  - AI 真机验收全绿 10✔/0✘（四态全验/排队 vs steer wire 时序可分/blocked 恢复链/V2 回归/0 FATAL）：`docs/acceptance/2026-09-05-326-single-send-key.md`；**UIUX 卡待人工验收**（清单在该文档末节，与 #327 同域汇总提交）
  - 迁入依据：委托自动化验收：三态今日活体复证（idle-SEND/忙空-停止/忙文-排队）+既有 10✔——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#313 消息队列 UI 迁入 FAB——对齐「能力→容器」自有映射，废除 QueueDock 对 DSH Web dock 布局的照搬（2026-09-03 用户路线级裁决）** `dsh` `ui`
  - **映射原则（用户定规）**：DSH 的 goal/todo（消息框上方）、子代理/后台任务（面包屑）等面板类能力，在我们这里**一律进 FAB 菜单**（ChatFabMenu 现载 TODO/AGENT/GOAL/SHELL 四入口→自有 sheet）；QUEUE 同样处理：FAB 菜单项「队列(N)」→ QueueSheet（复用 QueueDock 行逻辑+三动作），输入条上方 dock 退役；流内内容（jobs 时间线卡/错误行/压缩分割线）不属面板、维持流内
  - 波及：#309④ steer 插话呈现面（入队展示走 FAB 入口）；#310-#312 一并遵守（Plan/deliverables/轨迹台账=自有 chip/卡片/行菜单形态）
  - → `docs/journal/2026-09-03-fix-308-dsh-respond-wire.md` §七（裁决记录 + 批 1 审计）；**主体已落地**（审计+验收：FAB 五入口/QueueSheet 三动作/QueueDock 已删/i18n ×15/映射原则遵守——`docs/research/2026-09-05-audit-309-313.md` + #327 验收报告）；最后断点（角标数据链）已由 #327 修复并真机全绿（角标 1/2/清零全生命周期）→ **UIUX 卡待人工验收**（与 #326 同域汇总）
  - 迁入依据：委托自动化验收：FAB→QueueSheet 全链今日再走通（演示⑦）+既有审计验收——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#351 FAB QUEUE 入口去留——统一审计 §三-4/§三-1 尾项** `ui` `fab` `queue`
  - **已裁决+实现(2026-09-07)**:用户「按照我之前说的做」=#313 路由裁决(队列 UI 归 FAB 能力→容器)延续——保留入口,新增 queueSupported 能力位(DSH=true/V1V2=false,同 GOAL/SHELL 先例)门控 ChatScreen FAB;chips(本地堆积两面同构)与 QueueSheet(DSH 服务端排队)语义互补。真机:DSH 面 QUEUE 在场/opencode 面 QUEUE 消失
  - 迁入依据：委托自动化验收：入口门控随能力位正确（V2 队列域演化附注）——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#348 恢复 busy 气泡菜单(立即发送/堆积消息)+本地堆积链重建——2026-09-07 用户裁决定案,两面统一** `ui` `chat` `queue`
  - **记忆确证(用户裁决 1)**:ce8cbc1e(2026-08-20) 曾实现 busy+点发送→Popup 气泡菜单(anchor 按钮上方右对齐/点外关/BackHandler):「立即发送」(服务端排队,Queued on server visible immediately)+「堆积消息」(本地轮末自动发,Kept locally sent automatically when this turn ends;附件置灰);9-01 双键并存(18ae1a3d)取代并删除菜单;#289(706d1f1e)把死 enqueue 管线(本地堆积 Room 表/管线/仓库/UI 全链)整体拆除;9-05 #326 单键(以 dsh web 校准=参照系错位)。审计初版「app 从未有选择框」结论有误,已修正
  - **定案(裁决 3/4:原 opencode 面 UIUX 不改+DSH 用后端接口实现统一 UIUX)**:busy+点发送→恢复气泡菜单两面统一——「立即发送」DSH=session.prompt mode:queue(服务器队列,QueueSheet 可见)/opencode=直接 prompt(V2 服务端自然排队);「堆积消息」=本地堆积链重建(PendingMessage 语义:轮末自动发/编辑/删除,附件置灰),两面共用同一本地实现(opencode 后端无队列=自实现;DSH 服务器队列=立即进收件箱,与轮末堆积正交并存);堆积呈现=composer 上方堆积条(chip 式,可编辑/移除)
  - 关联:修订 #326(单键点击=直排队→改为弹气泡);#289 拆除史(重建需恢复链路);长按 steer(#309④)保留为直发旁路;QUEUE FAB 入口随堆积条呈现定去留
  - **已实现+真机全链验证 (2026-09-07)**:StackedMessageStore(DataStore JSON 持久化,T1心跳/T2入队即查/T3 Idle转移三触发,at-least-once,护栏=非Idle/待处理/无归属)+chips条(编辑/移除/立即发送)+气泡菜单(i18n x15);真机六腿:忙时点发送弹菜单(两轮复现)/空闲直发无误弹/堆积清输入+chip在场/忙时滞留/轮末自动drain(消息入转录+chip消失)/轮已结束堆积即时发出;附带实证 #343 零跨度台账(轮次6·-);单测 +9(心跳虚拟时钟门 disableCompensationHeartbeat——OOM 根因=无限delay x advanceUntilIdle 时钟无限推进)
  - 迁入依据：委托自动化验收：气泡菜单像素定位点选+入队徽标+轮末派发全链今日复证（演示⑦）——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#343 DSH 单消息轮次台账缺失——完结信号与时长测量被 durationMs 单字段承载,零跨度完结轮整行被吞** `dsh` `ui` `bug`
  - 仪器批取证(2026-09-07,journal §二十四):600 词纯文本轮完结后无「轮次 N」行;Room 直查 b7b3cdc1——流式建行 dsh-t3s1 与完结行 seq-1246 各自 created==completed(DSH 整包事件零跨度模式),仅多行轮(如含工具的轮1 16m38s)能凑出正跨度;MaybeTurnLedgerRow 的 durationMs==null 即 return 门控把零跨度轮整行吞掉——#338 的「时长未知→『-』」语义只在渲染层、到不了门控
  - 反证:Host-4199(opencode V2)同 app 0 工具轮有台账(「轮次 2 · 30.4s · 1 步 · 0 个工具」)——DSH 映射层特有
  - **已修复(根因=语义解耦)**:RenderableTurn 新增 allStepsCompleted 完结信号(≥1 assistant 且全部带 completed),durationMs 只答「跨度可测与否」;MaybeTurnLedgerRow/MaybeProducedFilesRow 门控改判完结信号——零跨度完结轮照常渲染、时长列回落「-」(#338 宁缺毋谎),流式中仍缺席(SSE 铁律不变);统计栏等 durationMs 消费面仅显示用途零改动
  - **真机验证 ✔(2026-09-07)**:活体路径——新单消息轮轮末即现「轮次 3 · - · 1 步 · 0 个工具」(旧代码此场景无行);重载路径——历史 fold 双行(dsh-t3s1+seq-1246)凑出真实跨度 15.4s 正常呈现;RenderableTurnTest +4(零跨度/负跨度=完结,流式/空轮=未完结)
  - → 证据:/tmp/e2e-instr/db.db(双行零跨度)+ v343-run-10.xml(活体「-」)+ v343-chat.xml(重载 15.4s)
  - 迁入依据：委托自动化验收：零跨度台账行「轮次 1 · -」今日在场——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#338 轮次/思考时长跨钟域混腿——completed 本地钟回填污染+流式 ticker 负值+零跨度显示 0ms** `dsh` `ui`
  - 已修复(7c9fddc7+caa81fb8) 三层根因实证钉死:①W2 B4「-207ms」实为 StreamingElapsedText 无下限钳制(startMs=DSH 服务器信封,设备钟慢 207ms 窗口内为负——非台账腿);②工具宿主 completed 由 markSessionIdle 本地钟回填,Room 实证 completed=created+3.5h(resync 期回填)且 merge incoming?:existing 使污染永久残留——改域一致回填(EventDispatcher 采集 MessageUpdated.created/SessionIdle.time 同域基准,逐消息 max 钳制)+历史残留自愈消毒(事件权威完成缺席且超域水位10min→null,随落盘修复Room);③零/负跨度=时长未知→null(台账"-"/思考无时长变体,新 i18n 键×15)
  - **真机验收 ✔(2026-09-07 07:0x)**:resync 后 B4 全部 9 个工具宿主 completed 修复为域内时刻(call_17:+3.5h→轮末信封时刻),轮1台账回到 3.7s;「思考完毕」无 0ms 后缀(三 dump 全 0);轮4台账 3.2s 正常;残留 2544 条 dsh-call 存量集中在未回放旧会话(打开即自愈,渐进语义);证据 /tmp/e2e-fix34x/(db pull+dumps+logcat);**UIUX 终验待人工**(时长呈现主观确认)
  - 迁入依据：委托自动化验收：时长呈现全程健康（多轮多服抽样无 0ms/负值）——delegated-acceptance §一（backlog.sh migrate 2026-09-09）

### **#342 会话列表整列红底——SwipeToDismissBox 背景无条件常驻+行内容透明,errorContainer 恒透出** `ui` `bug`
  - 根因(M3 1.4.0 源码+字节码定音):backgroundContent 作为全尺寸 Row **无条件常驻**在内容后方,SessionRow 前景 Row 无底色(透明)→ errorContainer 透出=红列表;自 #311 Task2(0498e775,09-05)上线起恒在——XML dump 验收色盲+「左滑手感」人工项(L125)未做双漏;09-06 全量 E2E 列表截图全红(A0/A1/A2/B10/B14 等 12+ 张,像素 (128,39,31) 暗/(245,223,221) 亮)未被任何断言捕获
  - 已修复:SwipeToArchiveBackground 接 dismissState,rest/复位态不绘制(透出列表 surface),仅负向位移(左滑揭示中)绘制——shouldRevealArchiveBackground 纯函数判定(-1f 亚像素容错);单测 2 例;真机像素复验 ✔(行区 (247,250,253) surface,红仅左滑中);**UIUX 手感终验待人工**
  - → 证据:/tmp/e2e-fix34x/list-now.png 采样 + /tmp/e2e-full 历史红列表时间线 + M3 SwipeToDismissBox.kt(jsdelivr androidx-main 对照+本地 1.4.0 字节码无 offset/zIndex/alpha 门)
  - 迁入依据：委托自动化验收：多模态抽样行区近白零红底——delegated-acceptance §一（backlog.sh migrate 2026-09-09）
