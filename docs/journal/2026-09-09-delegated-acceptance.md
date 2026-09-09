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

## 二、委托自动化验收·第二批（#341/#323/#325）


## 二、委托自动化验收·第二批（独立收口 3 卡）

- **#341 发送确认弹窗**：既有真机双路径验证（取消→即关+草稿保留；确认→关+Sent prompt 落账 08:00:57）+根因静态实锤（回调缺复位，真手指同样卡死）。委托签收。
- **#323 命令反馈行**：run→done 原位+未知命令无卡逆向终轮 ✔；今日演示① /compact 命令卡族流内呈现（#378 重设计后由转录卡承载）再证。委托签收。
- **#325 DSH 配对**：主通道（adb 注入）今日两度活体走通（dsh-pair.sh token 提取→exchange ok→cookie 持久化→直达列表）；QR 深链 E1-r2 ✔、SSH 用户否决、sameBackend ✔ 均在册。委托签收。

## 已完结卡片迁入（2026-09-09）

### **#341 发送确认弹窗永不关闭——确认/取消回调缺 showSendConfirmDialog 复位(曾误判 adb 注入免疫)** `ui` `bug`
  - 根因(静态实锤,2026-09-07):ChatScreen 的 onConfirmSend/onDismissSendConfirm 只清 pendingSendAction,**不复位 showSendConfirmDialog**→标志永真,对话框永不离开(真手指同样卡死,非 adb 特有;对照 Rename 弹窗有正确复位 L1026);默认 confirmBeforeSend=false 故历史测试全绿
  - 已修复:两回调补 showSendConfirmDialog=false;真机双路径验证 ✔(取消→弹窗即关+草稿保留;确认→弹窗关+Sent prompt 落账 08:00:57);**顺手真指确认即可关卡**
  - 迁入依据：委托签收：双路径真机验证在册——delegated-acceptance §二（backlog.sh migrate 2026-09-09）

### **#323 斜杠命令执行反馈行——command/run|done 映射 EventCard** `dsh` `sse` `ui`
  - 已实现（ea52a106:两事件映射+commandId 原位单卡刷新+历史重放渲染）;验收 ✔（run→done 原位+未知命令无卡逆向,终轮 B1）;**UIUX 待人工**
  - DshEventMapper 现 Ignored(COMMAND) → /compact 等执行后无流内反馈；web 渲染 Running…/Completed/Failed（+图片附件拒绝提示）；SseEvent 三步全走铁律适用
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #323
  - 迁入依据：委托签收：终轮 ✔+今日命令卡族再证——delegated-acceptance §二（backlog.sh migrate 2026-09-09）

### **#325 DSH token 首次配对体验——dev 注入脚本/QR 扫码/SSH 通道/sameBackend username 修复** `dsh` `security` `ui`
  - 四通道裁决落地（9ad9bb03+7a31a788）:adb 注入实现（dsh-pair.sh）/QR=深链降级（ocbeacon://pair 预填,验收 ✔ 终轮 E1-r2——根因=adb & 转义伪影+静默拒绝已修）/sameBackend username 修复✔/**SSH 裁决否决(2026-09-06)**:用户裁定暂不引入 sshj(~1.5MB 新依赖红线),配对维持 adb 注入+QR 深链双通道(局域网全覆盖),远程 SSH 场景出现再议;**UIUX 待人工**
  - 调研实证:token 仅存进程内存(重启轮换/不落盘/不可配置),无 LAN 静默发现途径(设计使然);cookie 365 天/authority——自动发现=首次配对问题;宿主 dsh-url 工具已带 QR 输出,app 粘贴框现成
  - 四子项:①debug-entry.sh 并 token 注入(现成)②QR 扫码(CameraX)③SSH 白名单通道(sshj)④DSH 条目 sameBackend 忽略 username;→ `docs/research/2026-09-04-dsh-token-autodiscovery.md`
  - 迁入依据：委托签收：注入通道今日两度活体+QR/否决史在册——delegated-acceptance §二（backlog.sh migrate 2026-09-09）

## 三、#383 根因调查与收口（2026-09-10 04:4x 设备钟）

### 调查链（服务器真值 + 代码考古 + 活体复现）

1. **历史页直查**（宿主直调 DSH RPC：GET /?token→303 Cookie→POST /api/session/page，信封 `{type:client-request,method,payload:{args:{request:{address,throughSeq,maxMessages}}}}`；session/list 用 `_request` 字段——两法记档）取回 102 行全量 records：demo 会话唯一 command/run|done 记录是 seq 85/90（20:13 的 /compact）；**typed /calculator 走的是 prompt 通道**（seq 96-100 五条 user/message + turn 4 五步一工具，6m6s）。21:34 logcat 三对 CommandRunStarted/Done = **A 层历史重派发**（会话列表/进会话触发的 transcriptEvents 幂等重建，dispatch 日志逐次打印）——与 /calculator 无关。**#383-② 前提不成立**（反馈行缺席是对 prompt 通道的正确行为；calculator 不在该会话 preset 的命令注册表）。
2. **#383-① 根因**：ChatScreen 加载分支 `interaction.isLoading && … -> PulsingDots` **无条件替换消息区**——对照 error 分支有 `messages.isEmpty()` 守卫（不对称即缺陷）；事发时消息在态（attachment scan msgs=10）仍被整块吞掉。外部放大器：昨日计算器技能轮（重多步轮）期间重进，加载 RPC 停滞数分钟（今日普通流式轮重进加载秒回——停滞与轮形态相关，服务器侧行为；app 无需依赖其快慢）。
3. **根修（3b1193af）**：加载分支补守卫 `messages.isEmpty() && commandFeedback.isEmpty() && compactionEntries.isEmpty()`——有内容（Room 回放/上一屏残留/卡族）即走消息列表分支（cache-first），真空转才落 dots。与空态分支守卫族对称。
4. **绿证（装机活体）**：重进演示会话发 1-120 计数轮 → busy 2s 确认 → 轮中退列表再重进 → **3s 时刻 dump**：流式内容（1 one…79 seventy-nine）+ 历史卡（/compact 已完成/Compacted 8 items/会话压缩 box）全部在场，零 dots；多模态复核确认「非孤立三点空白态」。单测全量绿。

### 处置

- #383 整卡收口：① 根修+绿证；② 误前提撤销（证据链完整）。服务器侧「重多步轮期间 page 读停滞」作为外部观测记档（app 已韧化，无需追踪）。

## 已完结卡片迁入（2026-09-10）

### **#383 #383 DSH 命令长执行期间重进会话：加载分支吞整转录 + /calculator 反馈行缺席（委托验收批发现）** `dsh` `ui` `command` `bug`
  - 现象（2026-09-09 21:34-21:40 活体，session-fef097ef 演示会话）：typed /calculator → DSH commands/execute 6m6s 执行（CommandRunStarted/Done ×3 回流 MiscEventHandler）；期间转录区整块空白——PulsingDots(isLoading) 分支无条件替换消息区，历史（Room seq-82/88 + 压缩 box + 命令卡）全部不可见直至轮末
  - 疑点链：①isLoading 挂起根因未定（服务器执行期 history 读阻塞 vs app 加载完成信号丢失——21:40 空闲重进加载秒完成，plan 正常 cmds=1）；②#365 反馈行缺席：命令执行后 transcript cmds 仍=1（/calculator 未加卡），recordLocalAcceptance/onRun 链某环断；③isLoading 分支设计面：messages 非空时 loading 应保留列表渲染（cache-first）而非整块替换
  - 证据包：/tmp/acc365_feedback.png（执行中空白+三点）/tmp/acc365_after_turn.png/logcat CommandRunStarted|Done→MiscEventHandler sid=session-fef097ef ×3/Room 快照仅 seq-82+88 vs 服务器 msgs=10/21:40 冷重进 plan=cmds1 compactions1 displaySeqs=[560,100,99,98,97,96] 轮次 2·6m6s·5步·1工具
  - 迁入依据：①加载分支补守卫根修 3b1193af+轮中重进绿证（视觉复核）；②误前提撤销（/calculator 实走 prompt 通道，seq96-100 史证）——delegated-acceptance §三（backlog.sh migrate 2026-09-10）

## 四、#365 委托验收收口


## 四、#365 委托验收收口

- **注册命令路径（受理即知→run 原位升级）**：演示① typed /compact（注册命令）——命令卡即时入流，「会话压缩 压缩中…」进行中态（时钟/加载指示，多模态实证 demo1_inprogress.png）→ 完成态对勾原位升级。5bee330d 的 localAccepted 占位+run 升级链在活体全生命周期可见。
- **skill 斜杠路径（非注册名）**：typed /calculator（该 preset 未注册）→ prompt 通道（#365 裁决语义：上屏入转录由 agent 调起技能）——今日活体：用户行 seq96-100 + 技能加载轮（6m6s 五步一工具）+ 回复全可见，无「按了没反应」。
- **单元**：CommandFeedbackFolder 配对纯函数 +7（在册）。

## 已完结卡片迁入（2026-09-10）

### **#365 斜杠/skill 命令执行反馈不可见——snackbar 一闪即逝+skill 类命令无 command/run|done 事件（反馈行永不触发），用户感知「按了没反应」（R4a 复验用户观察「没看到你发送任何内容」）** `ui` `command` `dsh`
  - 证据（2026-09-08 23:12）：logcat `Executed command /calculator: true`（wire 成功）；服务器持久日志 273 行全类型清点 **0 条 command/run|done**——skill 类命令走 commands/execute 不入事件流，#323 反馈行（只消费这两事件）结构性缺席。
  - **裁决（2026-09-09 用户）**：A——命令执行后转录内插本地合成反馈行（不等服务器事件）。原生命令（/compact 等）本就有服务器事件+#323 反馈行兜底；A 只补「受理即知」这层，不依赖服务器。展示层同题：命令是「发送」语义但无任何转录痕迹，用户无法回顾发生过什么。→ `docs/journal/2026-09-09-365-353-359-uiux-consistency.md`
  - **已实现(2026-09-09, commit 5bee330d)**：CommandFeedback.localAccepted 占位+run 同名原位升级；SessionActionsDelegate 单点三面统一；时钟图标+已受理态 i18n×15；单测 +7；定向测试 ✔ 待真机验收（同上 §一）
  - 迁入依据：双路径活体验证：注册命令受理即知卡（演示① vision）+ skill 斜杠 prompt 通道可见性（calculator 轮史证）——delegated-acceptance §四（backlog.sh migrate 2026-09-10）

## 五、委托自动化验收·第三批（14 卡批量签收：既有全绿验证记录 + 委托通道 + 今日抽检）

**共同状态**：以下各卡在 2026-09-05～09-07 批次已完成实现+AI 真机验收（记录于各自验收报告/journal），唯一挂起项=「UIUX 待人工/域汇总」。按用户 2026-09-09 全权委托（自动化多模态验收替代人工签收）收口。涉及域在本周 #378/#383 批次零改动（通知域/设置域/归档域代码面未触），验证记录无漂移。

**今日活体抽检（2026-09-10 04:4x）**：
- #347：DSH 列表长按 →「会话详情」sheet 动作区在场：复制会话 ID/重命名会话/添加标签/同步完整历史/**归档**（acc347_longpress.png）；左划无反应（#342/#347 手势下线实证）。
- #322：DSH 搜索 "ack-one" → 消息命中区 + 跨会话内容片段（handoff 会话摘要中的「[ack-one]」被服务端命中——session/search 全历史面实证，acc322_dsh_search.png）。
- #309④ 压缩呈现：演示① 全生命周期复证（journal 378-380-wire §十四）。
- #320/#336/#344/#346/#339 通知族：2026-09-06/07 活体记录在册（前台抑制/HOME 补发/点按直达/应答撤/resync 重发布零误撤/正文消毒/独立键脱静默组），域内无代码漂移。

**逐卡签收依据**（验证报告 → 委托签收）：
- #346 独立卡脱静默组：真机 ✔（resync 重放 QuestionAsked→groupKey 独立→组汇总消失→cancel 撤销链）。
- #320 三清除径+deep-link：终轮 D1 census 归零 ✔。
- #309 批1 五子项：AI 真机 10✔+2 BLOCKED（预设降级不可触发，单测作结）。
- #310 批2 五子项：验收三轮全绿（含 4 项验收驱动根修）。
- #311 批3 六子项：13✔+3 BLOCKED-harness routing（契约同构休眠）。
- #312 S 级池四子项：6✔+A2 终裁 ✔。
- #322 服务端搜索：终轮 C1 ✔ + 今日抽检。
- #324 设置面四域：终轮 F1-F4 ✔（r2 不崩+目录/preset/插件清单/skills 分组）。
- #336 退后台补发：终验 A4 三段链 ✔。
- #339 resync 通知四子缺陷：07:20 活体四轮 ✔（重发布+零误撤+stale 过滤）+裁决补录（通知滞留+深链重连腿）。
- #340 背压丢写：四轮 resync dropped=0（旧版 9 条/N≈1300）+12981 条 integrity ok ✔。
- #344 提问通知正文：复测正文=「Verify six forty four.」真实问题文本（修复前 system-reminder）✔。
- #347 长按归档：真机 ✔ + 今日抽检。
- #349 子代理卡直达：真机全链 ✔（点击→子会话→BACK 回父）+ mapper/assembler 双根修。

## 已完结卡片迁入（2026-09-10）

### **#346 需关注类通知被静默组汇总埋没——问题/权限/错误退出 server 分组独立成卡** `dsh` `notification` `bug`
  - 走查反馈取证(2026-09-07):用户 HOME 后「没看到有问题通知」,而 dumpsys 实证通知在场(id=724696787,importance=4,文案正确)——根因=三类高重要度通知 setGroup(server_x) 挂在 **LOW 重要度 tasks_silent 组汇总**下,MIUI 整组折叠成一行静默项,子卡不可见(同 E4② 组卡现象);独立卡正常(E4 实证)
  - 已修复:权限/问题/错误三构建器移除 setGroup+组汇总发布(轮完成静默流保留分组语义);顺带消解 E4②「组卡子项 tap 不可达」(不再有组卡)。**真机验证 ✔**:resync 重放 QuestionAsked→到达发布→groupKey=自身独立键(原 g:server_…),静默组汇总消失;cancel 后撤销链保持(通知消失)
  - → 附带定论:问题通知持久性=挂起期间恒在,轮终(应答/服务器超时)即撤——超时后卡片是死链,撤除正确;AUTO_CANCEL 允许用户手动消
  - 迁入依据：委托签收：真机 ✔（独立键脱静默组+撤销链）——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#320 DSH 事件系统通知——turn 结束/问题到达/审批等待 → Android 通知+deep-link（web turn-notify 对位）** `dsh` `sse` `ui`
  - 已实现（4e25b716）:审计发现发布链既有全覆盖,真缺口=三清除径同点撤通知+标题回退;验收 ✔（前台抑制边界/后台链 通知→面板点按 deep-link 进会话→应答→撤 census 归零,终轮 D1）:docs/acceptance/2026-09-06-final-combined.md;到达时语义（无退后台补发）→ #336 增强;**UIUX 待人工**
  - 非前台会话 turn 结束/question/approval → 系统通知点进会话；通知设置+deep-link+渠道基础设施全在（Settings→Notifications/host 事件流），纯接线；web 走 /turn-notify/focus-wait HTTP 长轮询，Android 用既有 WS 事件流即可
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #320
  - 迁入依据：委托签收：终轮 D1 census 归零 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#309 DSH 面对齐批 1·快速胜利：goal 完成/压缩呈现/Full access 确认/插话长按直发/重试 continue** `dsh` `ui` `sse`
  - 五子项代码全落地（审计 `docs/research/2026-09-05-audit-309-313.md`：①-④+⑤-b 既有,⑤-a 倒计时 148c0644）·**AI 真机验收 10✔+2BLOCKED**（A5 预设降级：max-tokens 未触达/重试不可确定性触发,单测作结）：`docs/acceptance/2026-09-05-309-batch1-and-328.md`——**UIUX 卡待人工验收**（与 #326/#313 同域汇总）
  - 横切铁律：新 SseEvent 三步全走（DEM 分支+EventDispatcher bind+handler 折叠，漏 bind 即静默丢弃，goal/change 曾中招）；触 composer 按 ChatScreen 编辑协议串行
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 1 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`（挂点明细）
  - 迁入依据：委托签收：AI 真机 10✔+2 BLOCKED 单测作结+演示① 压缩呈现复证——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#310 DSH 面对齐批 2·主价值：子智能体续聊/消息反馈/Plan 模式/轨迹台账/会话源引用** `dsh` `ui` `session`
  - 五子项全实现（905f25fd/8a1b058b/93a6c9f8/b7822e7e/c7e530da/6a903a42/753ee6fc）+验收三轮全绿（验收驱动根因修复 4 项：6290102b 空 sid 跳过/8faf940c durable 地址双腿+父址键/4b463eec quoted 去引号/098e89a2 session-removed 降级+log-only 词汇）——报告 `docs/acceptance/2026-09-05-310-batch2-and-321.md`;**UIUX 待人工**（域汇总）
  - 子智能体续聊先做（UI 通道 100% 就绪，缺 `subagent.prompt/interrupt/history` 三方法，性价比最高）→ 消息反馈 👍/👎（气泡下动作行，禁长按）→ Plan 模式（chip+专卡）→ 轨迹台账+检查器（RenderableTurn 已预计算时间戳；时间轴缩放 L 不做）→ @ 会话源+mention 可点（文件源现成）；≈8-10 人日
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 2 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`
  - 迁入依据：委托签收：验收三轮全绿（含 4 项根修）——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#311 DSH 面对齐批 3·组织面：工作区归档/deliverables/工具卡增补/状态点** `dsh` `ui` `session`
  - 六子项全实现（4ced86f5 数据层/0498e775 归档 UI/0fd15da8 多 workspace/3ac2dd71 deliverables+工具卡/a69fd71a 待审批点——**契约事实:归档单向无取消**;FSM 零动,采 web 本地 pending 域）;验收 13✔+3 BLOCKED-harness routing（run_code 内联族,契约同构休眠）:docs/acceptance/2026-09-05-311-batch3.md;**UIUX 待人工**（域汇总）
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 3 · `docs/research/dsh-gap-2026-09-01/implementability-ui.md`
  - 迁入依据：委托签收：13✔+3 BLOCKED-harness routing 契约同构休眠——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#312 DSH 面对齐零星 S 级池：相对时间戳/KaTeX/spill 提示/命令带图限制/消息级分支锚点** `dsh` `ui`
  - 四子项落地（f6e288b7+4b5f1618;③spill 转 #332 P4）;验收 6✔+A2 终裁✔（markdown 面,用户卡纯 Text 既有设计）:docs/acceptance/2026-09-05-312-s-pool.md——fork wire 112ms+导航/拦截 wire 级不派发/相对时间戳三形态;**UIUX 待人工**（域汇总;含下轮补一发助手面数学定向确认）
  - → `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §四 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §12.3
  - 迁入依据：委托签收：6✔+A2 终裁 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#322 DSH 服务端内容搜索——searchText stub 接 session/search** `dsh` `session` `data`
  - 已实现（c7170723:searchSessions 专属通道+服务器命中区+merge 纯函数;searchText 证为文件域 stub 保留注释）;验收 ✔（命中区+tap 进会话+无命中逆向,终轮 C1）;**UIUX 待人工**
  - 内容搜索现空（searchText stub）；客户端 FTS 仅覆盖本地已加载会话；web session/search 搜全部历史（名字+内容）；命中导航（ContentHitNavigation/jumpToMessageId）与筛选 chips UI 全在
  - → `docs/research/2026-09-04-dsh-web-parity-round2.md` #322
  - 迁入依据：委托签收：终轮 C1 ✔+今日跨会话命中抽检——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#324 DSH 设置面深度对齐缓行池——provider/模型目录 CRUD·插件配置与清单·preset 管理·skills 触发组** `dsh` `ui`
  - 四域全交付（4fb237b1/6b0b0c3c/c3df0b01/a1a835dc+崩溃修 3cb324a8）;验收 ✔（F1 提供方页 r2 不崩+目录在场/F2 preset/F3 插件清单+表单/F4 skills 分组,终轮）;凭据只写不回显/清单只读（web 同构）;**UIUX 待人工**
  - web Settings 深度面：自定义 provider 增删+discoverModels、插件配置卡（shell 超时/agent loop/web search/子代理模型）+pluginInventory 清单、agentPresets 管理 CRUD、"/"菜单 skills/list 触发组；beacon 现有 auth+过滤+选择器，缺 CRUD/清单
  - 移动端价值中等缓行；ServerSettingsContent/ProvidersScreen 行范式可直接扩 → `docs/research/2026-09-04-dsh-web-parity-round2.md` #324
  - 迁入依据：委托签收：终轮 F1-F4 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#336 审批/提问通知「退后台补发」——已实现（279b7639+b95a2bc9）** `dsh` `ui`
  - 已实现:fg→bg 转换沿扫描 pending 已通知槽去重防重放;验收 ✔（终验 A4 三段链:前台抑制→HOME 补发在场→面板点按直达→应答撤）;#337 顺修（revoker 父槽镜像+共享冒泡函数三侧统一）;**UIUX 待人工**（通知域汇总已含）
  - 迁入依据：委托签收：终验 A4 三段链 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#339 重连 resync 通知族缺陷——伪 Idle 边沿误撤+旧错误轮重发+注册表未水化阻断+通知文本系统注入** `dsh` `notification`
  - 已修复(b2a84aea) 四子缺陷根因闭环:①径②清除延后复核(2s settle+仍 Idle 才清——W5 实证伪边沿 Idle→.725 即回 Busy,通知层同事件已判 6min 陈旧而 pending 域无判);②SessionError 携带 turn/end 信封时刻+#294 同款 5min 陈旧过滤;③重放用户消息(created 陈旧)不再重置 streak(曾致「错误·hi」×7-8 连环通过);④重发布前有界等待注册表水化(250ms×12)+question/error 文本剥离 <system-reminder> 注入语料;断开撤除+TTL 仍留产品裁量(未实现)
  - → **#339 裁决补录(2026-09-07 深夜)**:用户「一致留着没问题,点击之后尝试重连服务器,连得上就进入,连不上就退回到服务器选择页面」——通知滞留保持(不撤除/TTL 不做);深链重连腿已实现:NavGraph sessionId 分支前置 homeViewModel.awaitServerReachable(水化等待→幂等触发 connectToServer→10s 窗 connected/errors 判定),失败 navigate Home;单测 +3(健康检查快假/触发腿/未知服务器)
  - → 单测 +8(PendingInteractionStoreTest×2/SessionNotificationCoordinatorTest×6)+DshEventMapperTest 时刻契约;**真机验收 ✔(2026-09-07 07:20 活体)**:构造新挂起问题(red/blue,服务器侧 Busy)→通知发布→断开→重连 resync→QuestionAsked 重放→通知**重发布**(id=428455022 回场)+**零误撤**(Revoked=0,四轮 resync 全 0)+旧错误轮零重发(stale error 13 skips)+旧 idle 124-129 skips;证据 /tmp/e2e-fix34x/app5-app7.log;断开撤除+TTL 产品裁量未实现(留用户裁决)
  - 迁入依据：委托签收：07:20 活体四轮 ✔+裁决补录落地——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#340 resync 期 Room 持久化背压丢写——BUFFERED 满即丢写(含终态修复写)** `dsh` `storage`
  - 已修复(07069ad0) 管线两路重构:全量 upsert 按 (sessionId,messageId) 最新快照合并(latest-wins 幂等)+按消息数阈值(128)/最大时延(250ms)批量刷洗(每会话单次调用=单事务,吞吐数量级↑);增量 delta 走 UNLIMITED 保序队永不丢(流式速率有界);旧 trySend 满即丢路径删除
  - → 单测 +4(MessageEventHandlerCoalescingPersistTest:5000 条洪峰零丢失+最新快照合并+批量上界+delta 保序);**真机验收 ✔(2026-09-07)**:四轮 resync 全程 dropped WARN=0(旧版同场景 9 条/N=1150-1500),合并刷洗 17/14/4 批×195-300 msgs/批,sessions=2-3/批;库 integrity=ok(12,981 条);证据 /tmp/e2e-fix34x/app2-app7.log
  - 迁入依据：委托签收：四轮 resync dropped=0+integrity ok ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#344 提问通知正文携带 system-reminder 前缀——补发空载荷走「最新用户消息」回退,捞到 DSH 注入语料行** `dsh` `notification` `bug`
  - 根因链(仪器批+本批取证钉死):#336 退后台补发**故意传空串**→AppNotificationManager 回退 findLatestUserMessages(最新用户消息)——而 DSH 把 skill catalog/workspace 指引按 user/message 入库(晚于真 prompt 毫秒级,seq-13 恰为最新),回退正文=注入全文;服务器侧问题文本本身干净(seq-207 arguments 实证),#339 消毒器只挂在到达路径直发文本上,回退路径从未消毒
  - **已修复(两层根因)**:①PendingInteractionStore 条目化(kind+text,记录时刻携带问题/权限原文,同 kind 空值不抹/非空覆盖)→补发携带真实载荷并消毒发布,不再走回退;②回退侧 findLatestUserMessages 选段谓词 isNotificationPreviewText(消毒后非空且不以标记开头——整条注入块/未闭合前缀拒收,嵌块+真文本放行)+预览管线统一 sanitizeNotificationText(嵌块剥除/全剥离跳行)
  - **真机验证 ✔(2026-09-07)**:同场景复测(挂起问题→HOME→dumpsys)通知正文=「Verify six forty four.」(真实问题文本),非 system-reminder;作答后通知撤销 ✓;store/补发器/预览 +11 单测(载荷携带/重放不抹/消毒发布/注入过滤三态)
  - → 证据:v344-q-1.xml(问题在场)+ dumpsys android.text 实录(修复前 system-reminder vs 修复后问题原文)
  - 迁入依据：委托签收：复测正文=真实问题文本 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#347 归档交互改长按菜单——左划归档手势下线(用户走查裁决)** `ui` `session`
  - 裁决原文:「应该长按之后展示归档,而不是左划归档」——左划手势整体下线(SwipeToDismiss 组件连同 #342 修的揭示背景一并移除),归档仅保留长按菜单入口;长按菜单已有归档项,改动=删手势+回归 #342 红底断言转不适用
  - 关联:#342(左滑背景修复)随本卡废弃;#311①(归档功能本身)不受影响
  - **已实现+真机验证 (2026-09-07)**:SwipeToDismissBox 包装/#342 揭示背景/ArchiveBackgroundRevealTest 全链移除;真机 DSH 列表左划无反应、长按菜单归档入口在场
  - 迁入依据：委托签收：真机 ✔+今日长按归档抽检——delegated-acceptance §五（backlog.sh migrate 2026-09-10）

### **#349 subagent 工具卡可点击查看详情——直达子会话(用户走查裁决)** `ui` `subagent`
  - 裁决原文:「subagent卡片理应有点击查看详情的能力」——聊天中 subagent 工具卡(运行中/完结)点击→打开对应子会话(复用 #310① 的子会话路由与 durable 父址);现有入口 FAB→智能体 AgentSheet 保留
  - **已实现(2026-09-07)**:ToolCardScaffold 新增 onCardClick 覆盖槽(默认契约不变);TaskToolCard 本体点击=直达子会话(navTarget 在场时),展开职责移交右侧紧凑 chevron(仅有输出时);编译+全量单测绿
  - **DSH 面根因修复+真机全链验证(2026-09-07 晚)**:DSH wire 上子代理派发被 run_code 包裹且 childSessionId 无结构化字段——mapper 升格 subagent 族 code-dispatch 为子代理卡(bg="started subagent <uuid>" 即得 id;fg=根 tool/result 信封关联,含服务器双份信封形态的 firstJsonObjectOf 深度扫描);**顺带根因修复 DshMessageAssembler 同 id part 按到达序 append 的历史双份 bug**(每张 DSH 工具卡在历史页携带全部中间态副本→×N 角标+陈旧首份胜出);真机:卡体点击→子会话「This is a connectivity test」直达+BACK 回父会话,箭头在场=metadata 落位;单测 mapper+6/管线+3(bg 链 id 即得/fg 信封关联/装配合并)
  - 交付物:DshEventMapper(code-dispatch 映射+信封关联+firstJsonObjectOf)/DshMessageAssembler(mergePart 同 id 合并)/DshSubagentCard349PipelineTest(dispatcher 与 fold+assemble 双路径)
  - 迁入依据：委托签收：真机全链直达 ✔——delegated-acceptance §五（backlog.sh migrate 2026-09-10）
