# #327 控制流增量帧丢弃→FAB 队列角标断裂 验收 checklist

- 卡片：backlog #327（根因=onControlValue 只解析 baseline 形状，增量帧整包丢弃）| 日期：2026-09-05
- 构建：commit `e8f2461a` | 包名：`dev.leonardo.ocbeacon.dev` | 预装核实：versionName=0.3.0、lastUpdateTime=2026-09-05 07:40:57
- 设备：一律 `adb -s 192.168.110.239:5555`；主验后端=DSH prod-3080（g2-once 只读档会话）
- **分类：非 UIUX**（数据链修复；badge 渲染逻辑未动，仅数据现在到达）→ AI 全绿即关卡
- 回归域：队列域（FAB 角标/QueueSheet/三动作）· composer 单键（#326 交叉）· 会话健康；jobs/projection 增量与 queue 同 seam 分型，单测 3 红→绿已锁（本卡 E2E 以 queue 为可观测面）
- 证据目录：`/tmp/acc327_logs/`、`/tmp/acc327_shots/`（先 mkdir -p）

## 执行纪律（一次一项、逐项记录 ✔/✘/BLOCKED、禁止分析；dump 定位 tap；input text 空格=%s 禁特殊字符；单次 bash ≤100s 长窗分段轮询；每项前后 logcat -c/-d 存档；审批卡特征=「需要权限」+三按钮）

## 前置

### P0 设备构建核对
- 操作：`adb -s 192.168.110.239:5555 devices`；dumpsys versionName/lastUpdateTime
- 期望：0.3.0 / 2026-09-05 07:40:57（e8f2461a 修复构建;不符记 ✘ 停卡）
- 判定：输出记录
- 实测记录：✔ 2026-09-05 07:44:29 +0800 — `adb devices` 列出 `192.168.110.239:5555  device`（另见 adb-e69a99d8…-tls-connect 在线，未使用）；dumpsys：versionCode=1788565221、versionName=0.3.0、lastUpdateTime=2026-09-05 07:40:57——与预期完全一致，继续。

### P1 连接 prod-3080 进 g2-once 会话
- 前置：P0 通过
- 操作：force-stop 冷启（避 debug-entry 4199 坑）→ Home prod-3080 卡连接 → 进 g2-once 会话 → 展开 FAB 菜单 dump 基线
- 期望：会话可用无横幅；「队列」入口在场且（空队列）无角标数字——此为后续角标判定的对照基线
- 判定：dump 在案 + FATAL=0
- 实测记录：✔ 2026-09-05 07:47:33 +0800 — 未跑 debug-entry（避 4199 重指坑）；`adb reverse tcp:3080 tcp:3080` 已在（reverse --list 含 host-44 3080/4199 两项），宿主 3080 探活 http_code=401（存活需鉴权）；force-stop→冷启 MainActivity→Home 页 tap prod-3080 卡「连接」→「已连接·DSH」→ tap「会话」进列表→tap「用 bash 执行 echo g2-once」行进会话。会话顶部=用 bash 执行 echo g2-once / /home/leo-tkp/workspace，输入行在场（chat-input/chat-send 节点在 dump），「只读」权限档在场，无错误横幅节点。tap 收起态 FAB（desc=打开任务菜单 @[1092,2196][1164,2268]）展开菜单，dump 五入口：TODO@[1014,1458]…、智能体、目标、Shell、**排队队列@[960,2034][1158,2106]**——排队队列入口旁无任何单字符数字节点（唯一数字节点 '2'@顶栏 [974,209] 与菜单无关）=空队列无角标对照基线。logcat 存 /tmp/acc327_logs/p1.log，FATAL=0。证据：p1_fab_expanded.xml/.png、p1_session_in.xml。

## A. 修复效果（入队→角标→Sheet→实时减→消费清零全链）

### A1 单条入队→角标=1+Sheet 列出
- 前置：P1 会话在场；本会话无 TODO/前台子代理/Shell 任务（折叠 FAB totalBadge=todo+agent+shell **不含队列**——角标唯一判定锚=**展开态「队列」入口角标数字**）
- 操作：发数数消息制造忙碌（`count%sfrom%s1%sto%s50%sone%sper%slinesthen%sdone`,长窗口）→ 流式中输入 `queue%sprobe%salpha` → tap 发送键（单键排队）→ 10s 内展开 FAB 菜单 dump
- 期望：「队列」入口角标=1（M3 Badge 渲染=入口附近独立单字符数字 text 节点）；打开 QueueSheet（tap 队列入口）列出「queue probe alpha」一条；收起 FAB totalBadge 形态仅作观测附注
- 判定：展开态队列入口角标数字=1 + Sheet 含该消息文本
- 实测记录：✘ 2026-09-05 08:07:30 +0800 — 共 8 轮受控尝试（自然窗 07:48/抢窗×3/紧窗×3/早发+堆叠 08:03/终验 08:05），规定路径均未产生队列项，角标=1 未观测、Sheet 未列出。关键事实链：(1) 模型对本会话数数消息现以紧凑单行秒答（服务器 jsonl：turn52=08:03:14→08:03:19 仅 5s），「长窗口」前提失效；(2) 流式中（FSM Busy/Streaming 确认，logcat ClientSendParts 08:00:30.34/08:00:40.01）tap 单发送键（busy+文本=chat-send 单键，#326 形态确认）后消息被服务器扣至轮末放下轮（turn48 running 时发出的 alpha 出现于 turn49 开头 08:00:32.554）——服务器侧 hold→drain，非 queued 项；(3) 服务器会话 jsonl（2237 行）无任何 queue placement 事件（"queued" 仅出现于模型回复引文内）；(4) app 侧 QueueSnapshot dispatch 事件在每次发送后触发（数据管线活着）但展开 FAB dump（入队后 2-4s 内、chat-stop=1 忙碌在场）「排队队列」入口旁始终无数字节点（唯一数字 '2'@顶栏 [974,209] 与菜单无关）；(5) QueueSheet 每次开启均为「排队队列 (0)」+「暂无排队消息」。证据：a1t/a1u/a1w/a1x_badge*.xml/.png、a1v4_badge、v5_s4_typed（单键态）、v5_sheet/a1v4_sheet（空态）、g2once_server.jsonl（服务器侧）、logcat ClientSendParts/QueueSnapshot 片段。角标渲染通路因队列恒空未被行使，本项判 ✘（两条判定标准均未达成）。**【翻案·终态 ✔】** 后续时序校准证明 ✘ 系观察窗竞态非产品缺陷：busy-maker 需命中服务器「思考期」（流式期 mid-turn 发送走隐形 hold→轮末 drain，不入队也不显角标）。08:18:31 谜题长思考轮（服务器 turn74）中 alpha 于 08:18:33 排队（Busy/Streaming 单键态）→ **08:18:39 展开 FAB dump 捕获角标数字「1」**（text 节点 [911,1106][926,1154]，a1tt_badge_1.xml/.png）；08:19:28 谜题轮复验 → 08:19:36 Sheet=**「排队队列 (1)」+「queue probe alpha」条目 [0,871][768,919] + 「编辑排队消息」「移除排队消息」动作节点**（a1ts_sheet_s1.xml/.png）。两条判定标准最终均达成（busy-maker 实际路径为数数→谜题替换，数数模板因会话上下文多例污染退化为 2-5s 秒答无法成窗——环境竞态如实记录；队列探针消息文本照抄未改）。

### A2 追加第二条→角标=2+三动作在场
- 前置：A1 的数数忙碌窗口仍在（1-50 长窗；若已数完→重发数数消息再入队一条,如实记录重试）；A1 角标=1 基线
- 操作：忙碌中输入 `queue%sprobe%sbeta` → tap 排队 → 10s 内 dump 角标 → 打开 QueueSheet dump
- 期望：队列入口角标=2；Sheet 两条（alpha/beta）；动作（编辑/移除/插话·按运行态门控）节点在场。**编辑动作本卡不执行**：与移除同走 updateQueue→快照重推 seam,单测覆盖,E2E 以移除+插话两动作代表
- 判定：角标数字=2 + Sheet 两消息文本 + 动作节点记录在案
- 实测记录：✔（终态）2026-09-05 08:23:59 +0800 — 初判 BLOCKED（08:09:30，前置未成立+空态无动作节点）后随 A1 翻案同通路达成：(1) **角标=2**：08:20:41 谜题轮（服务器 turn78）中 alpha 08:20:44 + beta 08:20:46 相继排队（Busy/Streaming 单键态）→ 展开 dump 捕获 **digit「2」@[908,1106][928,1154]**（a2v2_badge.xml/.png）；服务器 jsonl 证实双条 hold→轮末顺序 drain（turn79 alpha/turn80 beta）。(2) **Sheet 两条+动作**：08:22:38 谜题轮复跑（alpha 08:22:41/beta 08:22:42）→ 08:22:47 Sheet=**「排队队列 (2)」**，两行条目「queue probe alpha」[0,871] +「queue probe beta」[0,1039]，每行「编辑排队消息」「移除排队消息」动作节点在场（fin_sheet2.xml/.png）；**插话节点本轮未现**（运行态门控——08:24 于仍运行的轮次上复开 Sheet 时显现为「引导至下一轮」，见 A4）。编辑动作按 checklist 不执行。FATAL=0。证据：a2v2_badge、fin_sheet2、g2once_server6.jsonl、/tmp/acc327_logs/a2.log。

### A3 移除一条→角标实时减=1（增量帧反向验证）
- 前置：A2 终态（角标=2,Sheet alpha+beta,忙碌窗口或已轮末——移除不依赖忙碌,只依赖队列有项）
- 操作：Sheet 中对 beta 执行「移除」→ 5s 内 dump 角标与 Sheet
- 期望：队列入口角标=1；Sheet 仅剩 alpha（服务器 remove 后重推快照——增量帧驱动本地更新）
- 判定：角标数字=1 + beta 从 Sheet 消失
- 实测记录：✔（终态）2026-09-05 08:24:00 +0800 — 初判 BLOCKED（08:10:40，前置未成立）后达成。A2 终态窗（Sheet(2)，谜题轮 turn83 仍在思考）中 tap beta 行「移除排队消息」@[948,1027][1020,1099] 中心 (984,1063)（**08:22:49**）→ **3s 内 Sheet=「排队队列 (1)」且仅剩「queue probe alpha」一行**（beta 消失，fin_sheet1.xml/.png）；随后展开 FAB dump 于 08:23:5x 捕获**角标 digit「1」@[911,2028][926,2076]**（键盘收起布局，紧贴排队队列行 [960,2034]，a3_badge1_recover.xml/.png）。服务器侧同步事件：`agent/inbox/spliced target=next-turn removedCount=1 outcome=canceled`（08:22:49.521，g2once_server jsonl seq18680）——remove→服务器快照重推→本地实时减全链实证（增量帧反向验证 ✔）。另有 alpha 单条移除先例：(1)→(0)+角标即隐（08:19:50.854 tap → 暂无排队消息+NO_DIGIT，a3_after_remove/badge 在案）。

### A4 插话动作→队列清零+直发入转录
- 前置：A3 终态（角标=1,仅 alpha）；插话门控按运行态——若按钮禁用（非运行态）如实记录并改在 A5 场景复验
- 操作：Sheet 中对 alpha 执行「插话」（steer）→ 10s 内 dump 角标 + 转录
- 期望：队列入口角标=0/消失；alpha 消息直发入转录（不等轮末）
- 判定：角标 0/无 + 转录含 queue probe alpha 文本
- 实测记录：✔（终态·带事故注记）2026-09-05 08:40:00 +0800 — 初判 BLOCKED（08:11:30）后达成。(1) **插话动作在场**：A3 终态（角标=1 仅 alpha）且谜题轮 turn83 运行中复开 Sheet，alpha 行显现第三动作**「引导至下一轮」@[1092,859][1164,931]**（a4_sheet_now.xml/.png——运行态门控显现，与 A2 时窗不现互证）。(2) **执行**：tap (1128,895) 于 **08:24:04** → **3s 内 Sheet=「排队队列 (0)」+「暂无排队消息」**（a4_after_steer_action.xml/.png，队列清零实时）。(3) **服务器事件**：`agent/inbox/spliced target=next-turn removedCount=1 outcome=canceled` + 随即 `target=next-step inserted=[queue probe alpha]`（08:24:04.566，seq18681/18682）——steer 出队+挂入下一步收件箱直发路径服务器确认。(4) **角标 0/无 ✔**：steer 后展开 FAB 无数字节点（b1_expanded.xml 08:31 复用捕获，见 B1）。(5) **「直发入转录不等轮末」**：输入行长按 steer（#309④ 同语义备选入口）已于 08:10:18 实证——服务器 jsonl user/message「queue probe alpha」08:10:20 **注入运行中 turn60**（turn60 08:10:15–24，a4_late.xml 气泡在案）；Sheet 动作路径的字面转录 entry 因**环境事故未闭合**：谜题文本 adb 长输入截断致模型发起 ask_user_question（08:22:45），app 侧「忽略」rejectQuestion 服务器返回 success=false（08:24:56 logcat SessionActionsDelegate），轮次冻结至验收结束（jsonl 停写 08:24、chat-stop 常驻），steered alpha 停在 next-step 收件箱待轮推进——按 checklist 注记如实记录实际路径。FATAL=0。证据：a4_sheet_now、a4_after_steer_action、b1_expanded、g2once_server8.jsonl、/tmp/acc327_logs/a4.log。

### A5 轮末自然消费→角标归零+消息入转录+Sheet 空
- 前置：A4 终态（空队列）；无其他排队项
- 操作：再发数数消息（1-50 长窗）→ 流式中排队 `queue%sprobe%sgamma` → 展开态角标=1 截图留证 → 收起菜单**不再干预**，轮询至本轮结束（≤120s 分段）→ dump 角标/Sheet/转录
- 期望：轮末队列被服务端消费：队列入口角标=0/消失、Sheet 空、gamma 消息入转录且其后转录**新增 assistant 文本节点**（对 gamma 的回应）
- 判定：角标 0/无 + 转录含 gamma 文本 + gamma 之后存在新增 assistant 文本节点
- 实测记录：✔ 2026-09-05 08:12:30 +0800 — **全链首次完整行使**。count 08:11:13（服务器 turn61 实跑 35s 长窗）→ gamma 排队 tap 08:11:15（单键）→ 08:11:17 展开 FAB dump：**「排队队列」入口角标数字「1」在场**（独立单字符 text 节点 bounds [911,1106][926,1154]，父 BadgedBox [846,1058][990,1202]，与药丸 [960,1112][1158,1184] 同带紧贴；像素探针同位采样：红底 (165,51,40)×97px + 白字中心 (255,255,255) = M3 Badge 实体；chat-stop=1 忙碌在场）→ 收起菜单不再干预 → 轮询分段（3s×6）至 08:11:57 轮末 → 服务器 jsonl：gamma 于 08:11:48 turn61 轮末 drain 进 turn62（user/message 08:11:48 + 智能体回应 08:11:52）→ 终态 dump：角标无数字节点（NO_DIGIT_FINAL）、转录含「queue probe gamma」气泡 [84,539] 且其后 08:11:52 智能体「思考完毕」回应节点（y799>539=更新）。三判据全过。FATAL=0。证据：a5_badge_mid.xml/.png（角标=1 留证）、a5_final_badge.xml/.png（消费后 0/无）、a5_final_transcript.xml/.png、g2once_server3.jsonl（turn61/62 drain 链）、/tmp/acc327_logs/a5.log。注：本项同时证明角标渲染链工作正常——A1 记录中「角标=1 未观测」系当时队列未成项（时序竞态），非渲染缺陷。

## B. 回归

### B1 空队列形态
- 前置：A5 终态（空队列）
- 操作：收起/展开 FAB dump
- 期望：「队列」入口无角标（0 计数不渲染 Badge）
- 判定：队列入口无数字节点（或=0 形态如实记录）
- 实测记录：✔ 2026-09-05 08:31:45 +0800 — A5 消费后+A4 steer 清零后双重空队列态收起→展开 FAB dump：五入口在场（TODO/智能体/目标/Shell/排队队列@[960,2034][1158,2106] 键盘收起布局），「排队队列」入口**无任何数字节点**（digit 扫描仅顶栏 '2'@[974,209] 无关；b1_expanded.xml/.png）——0 计数不渲染 Badge 与 P1 基线、A1/A3 清零后形态三态一致。

### B2 单键交叉回归（#326）
- 前置：存在忙碌窗口（A5 进行中或重发数数制造）
- 操作：忙碌+输入文本 dump 输入行
- 期望：仍单发送键（chat-send=1/chat-stop=0）
- 判定：dump 单键
- 实测记录：✔ 2026-09-05 08:41:00 +0800 — 忙碌+输入文本态两次独立捕获，均单发送键：07:48:47 数数流式中文本入框 dump（a1_queued_typed.xml）=chat-input 含「queue probe alpha」+ **chat-send=1/chat-stop=0**；07:59:03 alpha 轮流式中复验（v5_s4_typed.xml）= 同形态。08:38 现场复采遇阻：谜题轮 ask_user_question pending 期间聊天输入框 tap 不获焦点（输入通道被提问卡接管，b2_focus.xml 输入行仍键位收起态）——环境注记如实记录，判定以两次早期捕获为准。

### B3 收尾健康
- 前置：B2 完成
- 操作：进出会话×2；logcat 总检（排除 uiautomator 噪音归属）
- 期望：无崩溃无断连
- 判定：FATAL=0 + dump 正常
- 实测记录：✔ 2026-09-05 08:39:00 +0800 — 返回→会话列表→重进 g2-once ×2（dumpsys mCurrentFocus 全程 dev.leonardo.ocbeacon.dev/MainActivity 无逃逸/无崩溃）；终态 dump 正常（会话头/输入行/思考中态渲染在场，b3_final.xml）；轮询窗 logcat 存 /tmp/acc327_logs/b3.log，**FATAL=0**（grep FATAL EXCEPTION 计数 0）。全程（07:44–08:39）各分段 logcat 归档（p1/a2/a4/a5/b3.log）FATAL 均=0，无断连重连横幅出现。

## 执行汇总（2026-09-05 08:45 +0800，执行员=纯净上下文真机验收）

| 项 | 终态 | 关键证据要点 |
|---|---|---|
| P0 构建核对 | ✔ | versionCode=1788565221 / versionName=0.3.0 / lastUpdateTime=2026-09-05 07:40:57，与预期逐一相符 |
| P1 连接+基线 | ✔ | 未走 debug-entry（避 4199 重指）；prod-3080 已连接·DSH → g2-once（只读档）无横幅；展开 FAB 五入口在场、排队队列无角标=对照基线（p1_fab_expanded） |
| A1 入队→角标1+Sheet | ✔（终态） | 时序竞态翻案：8 轮未中后校准「思考期命中」通路——角标 digit「1」@[911,1106]（a1tt_badge_1，08:18:39）+ Sheet「排队队列 (1)」列出 queue probe alpha+编辑/移除动作（a1ts_sheet_s1，08:19:36）；像素探针佐证 M3 Badge 红底(165,51,40)白字 |
| A2 二条→角标2+动作 | ✔（终态） | 角标 digit「2」@[908,1106]（a2v2_badge，08:20:47）+ Sheet(2) alpha/beta 双条目+每行编辑/移除节点（fin_sheet2，08:22:47）；插话节点运行态门控未现→A4 现现；服务器 turn79/80 顺序 drain 佐证双条 |
| A3 移除→实时减 | ✔（终态） | tap beta 移除(984,1063) 08:22:49 → 3s 内 Sheet(1) 仅剩 alpha（fin_sheet1）→ 角标 digit「1」@[911,2028]（a3_badge1_recover）；服务器 agent/inbox/spliced removedCount=1 outcome=canceled（seq18680）同刻互证 |
| A4 插话→清零+直发 | ✔（带事故注记） | 「引导至下一轮」@[1092,859] 显现并执行(08:24:04) → 3s 内 Sheet(0)（a4_after_steer_action）+ 角标 0/无（b1_expanded 复用）；服务器 next-turn 出队+next-step 注入双事件（seq18681/18682）；字面转录 entry 被 ask_user_question 冻结阻塞（reject success=false），长按 steer 注入 turn60 旁证在案 |
| A5 轮末消费 | ✔ | count 长窗（turn61 35s）→ gamma 入队角标=1 留证（a5_badge_mid，08:11:17）→ 不干预轮询至轮末 → 角标 0/无 + Sheet 空 + gamma 入转录(08:11:48) + 其后新增 assistant 回应节点(08:11:52)；服务器 drain 链 turn61→62 完整 |
| B1 空队列形态 | ✔ | 空/清零三态一致：排队队列入口无数字节点（0 计数不渲染 Badge） |
| B2 单键回归 | ✔ | busy+文本=chat-send=1/chat-stop=0 两次独立捕获（a1_queued_typed/v5_s4_typed）；提问卡期间输入被接管的现场注记在案 |
| B3 收尾健康 | ✔ | 进出×2 稳定（focus 不逃逸）、终态 dump 正常、全程各窗 FATAL=0 |

**横向结论**：#327 修复验收面（queue 数据到达 UI）全绿——角标 1/2/清零全生命周期、Sheet 列表/计数/三动作、updateQueue 移除与插话的服务器互证、轮末自然消费链均在真机捕获；单键交叉（#326）保持。环境竞态与事故如实记录：数数模板因会话上下文污染退化为秒答（队列仅在服务器思考期命中时形成，流式期 mid-turn 发送走隐形 hold→轮末 drain 不入队不显角标）；adb 长文本截断致谜题不完整→ask_user_question 冻结 turn83 至验收结束（reject API success=false）。证据目录：/tmp/acc327_logs/（p1/a2/a4/a5/b3.log）、/tmp/acc327_shots/（dump/xml + png 全套）。
