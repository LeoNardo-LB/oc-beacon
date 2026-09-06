# 2026-09-06 全功能端到端真机回归 checklist（v2，本批 #309–#337 全量）

- **范围**:本会话交付的全部功能——原 13 卡批(#309–#328)+级联续批(#330/#331/#333/#335/#336/#337)+#334 草稿回归+#329 web 平价验证
- **构建**:`dc299e15`(HEAD,代码=010ef55a+纯 docs)dev flavor 0.3.0,已装 2026-09-06 11:14:11
- **设备**:192.168.110.239:5555(MIUI houji,1200x2670@480dpi)
- **服务器**:prod-3080(DSH harness 主测,adb reverse tcp:3080,当前已连接)+Host-4199(opencode 辅测,tcp:4199,当前未连接)
- **模型**:测试会话用 zai flash(deepseek-official 设备通道欠费)
- **分类**:UIUX 域汇总(人工清单另行);本 checklist 为 AI 端全面 E2E+回归
- **修订**:v2 按纯净审查第 1 轮 22 条意见修订(报告:docs/acceptance/e2e-2026-09-06/review-round1.md)

## 覆盖矩阵(卡→item,含豁免)

| 卡 | items / 豁免 | | 卡 | items / 豁免 |
|---|---|---|---|---|
| #308 审批/提问 wire | B1 B3;B2 正向=BLOCKED 预案¹ | | #322 内容搜索 | D1 |
| #309 批1 ①goals②compact③危险④steer | B4 B5 B6 C4 | | #323 命令反馈卡 | B9 |
| #309 ⑤ 重试 continue/max-tokens | **豁免**:prior 验收 A5 两项 BLOCKED 单测作结(2026-09-05-309 报告) | | #324 设置深度 | D2 D3 D4 D5 |
| #310 批2(subagent/反馈/plan/台账/@) | A4(含子代理续聊受端) A6 B7 B8 B13 | | #325 ①adb 注入 | **豁免**:真跑必改服务器条目违反红线③,以 prior E1-r2 验收作结(9ad9bb03+7a31a788) |
| #311 批3(归档/多ws/deliverables/点) | D6 D7 D9 D10(D10=BLOCKED 预案¹) | | #325 ②QR 深链 | E1 |
| #312 S池(时间戳/数学/带图/fork) | A5 B10 B11 B12 | | #325 ③SSH | **豁免**:2026-09-06 用户裁决否决(backlog/journal §二十一) |
| #313 FAB 队列 | C3 | | #325 ④sameBackend | E2 |
| #320 通知发布/撤除 | E3(三清除径逐项)+E4 互证 | | #326 单键 | C1 |
| #321 文件候选管线 | A7 | | #327 角标链 | C2 |
| #328 提问忽略 wire | B3 | | #329 平价(hold→drain) | C5 |
| #330 ws remove/order | D8 | | #331 行时延/前插揭示 | A1 A2 |
| #333 旧会话转录 | A3 | | #334 草稿(非缺陷回归) | B14 |
| #335 corruption(非破坏) | H2 | | #336 退后台补发 | E4 |
| #337 撤通知父槽 | E5 | | 回归域 | 见下方「回归域去留表」 |

> ¹ **受限权限档统一预案**:新会话默认档=完全访问(journal §十一环境事实③);构造受限档需改宿主 harness 权限规则=**违反红线⑥禁止**。故凡依赖受限档的正向路径(审批卡渲染/危险确认双路径/待审批琥珀点)一律 `BLOCKED-environment`,指针 `docs/acceptance/2026-09-04-308-dsh-approval-wire.md`(17✔ 含 A3 危险档双路径/审批卡全链)。本 checklist 断言的是**当前档位语义正确性**。

## 回归域去留表(regression-guide 12 域,§2 步骤1 要求)

| 域 | 去留 | 理由/item |
|---|---|---|
| 3.1 启动与崩溃 | 测 | A8 冷启+H1 崩溃清点 |
| 3.2 服务器连接 | 测 | E2 双条目共存+E3c 断开/重连(★2);SSE=3.2★5→F1-F3 |
| 3.3 会话列表 | 测 | A1/A2(★1★2)+G6 未读红点(★4);搜索本地过滤→D1 服务器搜索 |
| 3.4 聊天发送流 | 测 | C1(★1★2)+F1(★3★5)+B15 Markdown 表格(★6);草稿清除→B14/C 组观察 |
| 3.5 聊天控制 | 测 | F3 停止(★1);模型切换=会话内选择器冒烟(随 C1 走查);失败恢复豁免(本轮无网络故障注入,不人为断网——断网会干扰其他 item) |
| 3.6 草稿系统 | 测 | B14(★1);会话隔离随 B14 双会话天然覆盖 |
| 3.7 工具/问题卡片 | 测 | B1/B3/B9/B13(本批主交付面) |
| 3.8 分页与滚动 | 测 | A3 上滑加载早期历史(★1)+双向 bounds 铁律;F2(★3) |
| 3.9 消息存储 | 测 | H2 Room/DataStore 直查(★1);DB 损坏自愈豁免(破坏性,#36 既有单测覆盖;红线⑦禁改数据) |
| 3.10 状态与事件 | 测 | C1/F3 状态流转(★1);未读恢复→G6 随 A8 冷启复查 |
| 3.11 i18n/发版 | 静态 | G3(i18n-check.sh+译本抽查);签名/版本豁免(非发版轮) |
| 3.12 终端/工作区 | 部分 | workspace 域=D7/D8;终端连接豁免(本批未触终端面,无终端入口改动) |

## 0. 全局纪律与红线(每 wave 执行者必读)

1. **真机串行**:仅本 wave 一个流操作设备;禁止并行 wave。
2. **禁止**:任何 gradle 调用 / pm uninstall / pm clear / connectedAndroidTest/UTP / ask_user_question 向用户提问(测试会话内的提问卡除外——那是被测对象)。
3. **数据安全红线**:服务器条目恒=2(Host-4199+prod-3080);只归档/删除**本测试创建**的会话;workspace 只新建后删除自建的;结束时 main 工作区在场。
4. **会话命名机制(红线判定依据)**:设备端创建的每个测试会话,**首条消息提示语必须以 `E2E06-<item-id>` 开头**(如「E2E06-B1:请调用…」)——会话标题由首条消息派生,前缀自然进入标题,即可被红线③/D1/H3 识别。
5. **宿主侧派生会话管辖**:wave agent 在宿主 spawn 子代理产生的会话(A1 等)**留存不删**,wave 结束在 notes 列出清单(标题特征+创建时间),H3 终检归入「宿主派生留存」类。
6. **禁止修改宿主侧 DSH harness 配置/权限规则/凭据/服务器配置文件**(受限档类正向路径走统一 BLOCKED 预案,见覆盖矩阵注¹)。
7. **禁止直接读写设备应用数据目录内容**(豁免仅限只读观测:H2① 的 run-as ls 列目录与 H2② 的 pull-app-db.sh 只读拉库)。
8. **入口**:prod-3080 测试用桌面正常启动(禁 debug-entry.sh——它重指 4199);4199 专项(G4)用 `./scripts/debug-entry.sh 192.168.110.239:5555`。
9. **每 wave 开头**:`adb reverse --list` 确认 tcp:3080+tcp:4199(缺则重建);`pm grant dev.leonardo.ocbeacon.dev android.permission.POST_NOTIFICATIONS`(幂等);`adb shell svc power stayon usb`。
10. **logcat 落盘约定(H1 判定依据)**:每 wave 开工即起宿主侧连续采集,按 PID 过滤(regression-guide §3.8 铁律 4):`adb logcat -v time --pid=$(adb shell pidof dev.leonardo.ocbeacon.dev) > /tmp/e2e-full/W<n>-logcat.log 2>&1 &`(记录 PID 变更,app 重启后需重启采集);wave 结束 kill。崩溃缓冲另存:`adb logcat -b crash -d > /tmp/e2e-full/W<n>-crash.log`。
11. **输入坑**:清稿=全选替换法(禁 keyevent 67 连发);TextToolbar/长按菜单 dump 失明不以 dump 判存在;V2 markdown 渲染区 dump 失明→用截图;dump 失败重试一次再判。
12. **证据**:截图/录屏 `/tmp/e2e-full/`(设备侧 /sdcard/ 录完 pull);logcat 关键行+时间戳摘录进报告;时间性 item(A1/E4/F1/F2)必 screenrecord。
13. **执行纪律**:一项一项做(域内顺序可依依赖微调,注记即可);只观测记录不分析不修复;✘ 后继续无依赖项;依赖断链标 BLOCKED-by-<id>;全部到终态才收工。
14. **报告**:实测记录写入本文件对应 item 的`实测记录`区(edit 工具,只动本 wave 域);wave 摘要(环境异常/意外观测/宿主派生会话清单)写 `docs/acceptance/e2e-2026-09-06/W<n>-notes.md`。
15. **滚动断言铁律**:双向各至少一次 bounds 变化才算滚动有效;边界零变化不算失效;节点 bounds 对比优于截图哈希。

---

## A. 会话树/列表/导航域(W1;执行顺序 A1→A2→A3→A5→A4→A6→A7→A8)

### A1 前插揭示——他处新会话即时顶位(#331)
- 前置:app 在 prod-3080 会话列表,进入任一会话 ChatScreen 停留;宿主侧可 spawn 子代理
- 操作:①录屏(`screenrecord --time-limit 180`,不足分段续录);②宿主侧以提示语「probe-a1-<时间戳>:请派一个子代理数到 5 然后结束」发起会话(标题将含 probe-a1);③BACK 返回列表立即观察首行;④重复 3 次(第 2/3 次可让子代理再派子代理=fork-of-fork);④每次记录列表首行标题
- 期望:返回列表瞬间,新会话行(probe-a1 特征)已在顶部在场,无需重进/pull-refresh;3/3 通过
- 判定:录屏逐帧+每轮 dump 首行文本含 probe-a1;任一轮需重进/pull-refresh 才见=✘。fork-of-fork:尝试 ≥1 次,不可得(模型不派子子代理)=该亚项 BLOCKED 记录(主判定 3/3 普通派生通过即算)
- 实测记录:**BLOCKED-机制与列表过滤设计冲突**(2026-09-06 11:32-11:37,纯净执行会话)。3/3 轮全部执行:宿主侧 spawn probe r1(11:32:27)/r2(11:35:57)/r3(11:36:35),提示语=probe-a1-r<n>-<epoch>:请派一个子代理数到 5 然后结束;每轮 ChatScreen 停留(父编排会话)→8s→BACK→立即 dump。**probe 行 0/3 在场**:首行恒为父编排会话「根据这一分handoff继续」(11:08),r1 后补 pull-refresh+再 dump 仍不在(证据 /tmp/e2e-full/A1-r1-list.xml / A1-r1-list-later.xml / A1-r1-after-refresh.xml / A1-r2-list.xml / A1-r3-list.xml +png)。定性证据(非 app 缺陷):①SessionListStateBuilder.kt:47 .filter 含 it.parentId == null——主列表按设计滤除 subagent-origin 会话(官方同构,DSH web 同款);②DshSessionMapper.kt:43 parentId 仅 origin=="subagent" 时置位——宿主派生会话必然被滤;③logcat DshConnOrch: host/session-removed for durable subagent …(session row and transcript retained) ×6(3 probe+3 孙代,11:33:03-11:36:58)——app 已收纳行+转录,仅不入主列表;④prior #331 前插揭示验收走 app 内新建会话/in-app fork(2026-09-06-cascade-fixes A1✔/A2-r3 三连✔),非宿主派生。**fork-of-fork 亚项 ✔(宿主侧)**:3/3 轮 probe 均成功派孙代(r1→a3048811、r2→37b3d8c9、r3→ad08176e,各数到 5 结束,结题报告+logcat 在案);孙代在 app AgentSheet 树可见(A4 证据:A1 probe r1 spawn 展开→Count to five 行)。宿主派生会话留存清单见 W1-notes。录屏:/tmp/e2e-full/A1-seg1.mp4(11:32-11:35,r1+refresh)+A1-seg2.mp4(11:35-11:38,r2/r3)。树内可见性由 A4 ✔ 补偿覆盖。
  - **主 agent 补测(2026-09-06 17:45-18:03)=✔**——W1 事后根因查明:宿主 subagent/subagent_fork 派生会话在服务器侧即 origin=subagent(session.list 直查实证:11b2b9e9 origin=subagent parent=session-23a614ec;设备端 fork 7ccdfe0f 则 origin=None),被主列表过滤=**#331 origin 判别按设计正确工作**,非缺陷。改用合法向量重测:①API 直建顶层普通会话 session-e2e06a1top01(origin=None,cwd=repo)+session/prompt「e2e06-a1-api 顶位测试」(prompt accepted,blank=False);②期间 app 停留 ChatScreen(hi 会话);③BACK → **首行即新会话行「e2e06-a1-api 顶位测试,请只回复 o」**(supI-after.xml/png,时间 9月6 17:45,第二行=父编排会话)——**前插揭示即时顶位本构建实证 ✔**(与 prior A2-r3 三连✔ 一致)。附:session.list 直查通道已建立(cookie 交换+/api/session/list),供后续 wave 根因用;/tmp/e2e-full/slist.json 存证(146 会话全量)

### A2 排序单调+时间戳锚定(#331)
- 前置:列表 ≥3 行,选定一个非首位旧会话
- 操作:打开该会话发送「E2E06-A2:回复 ok」得回复 → BACK 回列表;记录 `adb shell date +%s`
- 期望:该行升为首位且 30s 后仍在;**行时间戳=当前时刻(±60s)**(锚定 updatedAt 透传,原始症状=3min 时延)
- 判定:dump 首行=该会话+行时间字段对照记录的系统时间;时间偏差>60s=✘
- 实测记录:**✘**(2026-09-06 11:44-11:51)。选非首位旧会话=「hi」/home/leo-tkp/workspace 行(列表第 5 位,行时间 10:54);切模型 deepseek-official→zai-coding-cn·GLM-5.3-Flash(前者 Insufficient Balance,环境事实③ 同款);发送 e2e06-a2,replyok(11:44:39.181 prompt,轮次 2 回复 OK 👍 — message received ( e2e06-a2 ) 于 ~11:44:44 完成,3.6s)。BACK 回列表(11:45:38-11:45:42 两次 session/list 重取):**行未升顶+时间戳仍 10:54**(11:46:05 dump,距发送 ~86s;/tmp/e2e-full/A2-list-after.xml+png)。~11:50-11:51 复查(期间无新 session/list 请求,最后=11:46:36):**行已升顶且时间戳=9月6 11:44(值精确=updatedAt 透传正确)**(A2-list-recheck.xml+png)。事件层:11:44:39-44 轮次完成后**无 9c6e SessionUpdated SSE 事件**(grep 全 logcat 仅 11:30 历史重放 2 条)→列表反映依赖后续拉取/缓存更新,滞后窗口 >86s 且 ≤~5min(与 #331 原始「3min 时延」症状同族;具体断点=服务器 session/list 响应滞后或 SSE 缺席,本次只观测未定位)。行内容与位置复验:重进该行(400,1430)转录含 e2e06-a2 轮次 2 ✓ 行=同会话(A2-verify-entered.xml)。注:输入环境限制(豆包 IME)致消息以小写 ASCII 落地(前缀 e2e06-a2),可识别性保持
  - **主 agent 根因+修复(2026-09-06 18:1x)=已修待定向重验**——三源定音:①服务器源码:api-session/activity 仅在 user/message·source=user 发射(index.js:2632,载荷=[sessionId,time]),无任何既有会话 summary 更新事件;②web 源码:mod04.js handleSessionActivity→mutation kind=activity→summary.updatedAt 单调合并(仅当 > 旧值)→列表即时重排;③app 侧:DshRemoteMuxEngine:414 收 activity 仅当补开信号丢弃时间载荷→行排序位滞留旧值至缓存更新。修复=engine 透传合成 host/session-activity 帧+mapper 映射最小 SessionUpdated+defendSessionReplacement max 合并(web 同款单调语义,历史重放旧时刻不回拉);TDD 红2→绿,全量单测绿,commit 见 git log。设备定向重验列入修复循环(全 wave 完+重装后)
  - **定向重验(2026-09-06 18:39-18:43,新构建含 60a44edf)=✔ 修复生效**——重装(18:39:55,数据保持;重装触发通知权限弹窗已重新授予)→app 停会话列表(已订阅)→宿主 API 向非首位会话 session-e2e06a1top01(第 6 位,17:45)发 prompt(18:42:39 accepted)→6s 后 dump:**该行升首位+时间戳 9月6 18:42 新鲜**(x2-before/x4-after.xml+png 对照;BEFORE 同行第 6 位 17:45)。窗口内 logcat session/list 8 次(activity 触发 onSessionActive 动态补开 follow 的连带,预期内);修复前对照=W1 同场景 86s+2 次重取仍旧。**A2 ✘→✔ 结案**

### A3 旧会话转录全渲染+上滑翻页(#333,3.8★1)
- 前置:列表存在 ≥24h 旧会话(此前批测试遗留;若无=BLOCKED 记录并以最近会话代跑渲染部分)
- 操作:打开最旧会话等 5s;向下滚到底;再上滑 ≥2 屏加载更早历史;记录 2-3 个消息节点 bounds 前后对比
- 期望:转录全渲染非空白;上滑有更早消息加载(bounds 变化/新内容);回底正常
- 判定:截图头/中/底+上滑前后 dump bounds 对比(铁律 15);空白=✘;上滑无变化且非顶边界=✘
- 实测记录:**✔**(2026-09-06 11:53-11:56 之前,11:47-11:52)。列表滚至底部,最旧行=「根据这个handoff继续：/persistent/」·外包维权工作区·**9月4 22:44(37h,轮次台账时长 37h 4m 51s)**,无未读徽章(避免清非测试会话未读态;原 prior 44h 磁盘会话今日 11:05 被更新,故取真·最旧行)。进入等 5s:**转录全渲染非空白**——assistant 正文/Quote 块/Run code 芯片/轮次 11 台账(37h 4m 51s·3 步·2 工具)全在场(A3-entered.xml+A3-head.png)。上滑①(600,700→1900):「落 P035 明确化」[84,938][713,1082]→离屏,更早内容(P046/P055/P070/P074 指示词表)入镜,bounds 全变(A3-swipe1.xml/png);上滑②:再现更早「18 处指示词全部定位完毕」+Run code 区域(A3-swipe2.xml/png)。回底(4 次 600,1900→500+滚动到底部钮):「落 P035」复位 [84,938][713,1082] 与进场时**逐位一致**(A3-bottom.xml/png)——铁律 15 双向 bounds 变化达成+回底复原 ✓。FATAL=0

### A4 子会话树挂载+子代理续聊受端(#310①,于 A5 后执行)
- 前置:A1 产生的 subagent 子会话+A5 产生的 fork 会话均在列表
- 操作:①列表查看父子关系(subagent 会话挂正确父下;fork 派生会话按普通会话呈现不误挂;普通新会话独立);②**进入一个 subagent 子会话,发送「E2E06-A4:回复收到」并等回复**(受端效果证据)
- 期望:①树形关系正确;②子会话可续聊,回复正常到达,无空态回落
- 判定:树形截图+续聊往返截图;错挂=✘;子会话无回复=✘
- 实测记录:**✔**(2026-09-06 11:57-12:08)。①树形:进父编排会话→FAB 任务菜单(打开任务菜单,角标 1)→「智能体」→AgentSheet「智能体 (63)」根层列表;滚至底部展开「W1 真机执行会话树域」节点(折叠钮+运行中 ProgressBar)→子层渲染 **A1 probe r1/r2/r3 spawn** 三行(缩进,各带「展开」+已完成)→r1 再展开→孙代「**Count to five**」行(二级缩进,已完成)——**三级树父子关系正确**(A4-agentsheet*.xml/A4-tree-expanded.png/A4-tree-grandchild.png;subagent.list RPC 11:57:48/11:58:43 各 <80ms 返回)。fork 派生会话按普通会话呈现:主列表首行「hi」9月6 11:53(fork 创建时刻)独立成行**不误挂**(A4-list.xml+png);普通新会话独立:A2 会话「hi」11:44 同为普通行。②续聊受端:AgentSheet 点 r1 行进入子会话(ChatScreen 转录=probe 结题内容「任务完成。」+Run code)→发送 e2e06-a4,replyreceived →**回复到达**:「收到标记 e2e06-a4 的回执确认(reply received)。」+轮次 2 台账(A4-sent.xml/png);**宿主侧双向印证**:probe 代理(会话 fc307231)向本 wave agent 发出结题消息确认实收该回执——app 发送→subagents/prompt→真实代理→回复全链通,无空态回落。附注:子会话内 composer 模型药丸=DeepSeek-V4-Flash 且发送成功(该会话路由可用)

### A5 fork atSeq 分支链(#312⑤)
- 前置:任一多轮会话(可用 A2 会话)
- 操作:长按中间某轮消息 → 选「从此轮分支」→ 进入新会话
- 期望:子会话创建;分支标记/台账行在场;转录从该轮起;可继续对话
- 判定:截图+一轮新对话往返
- 实测记录:**✔**(2026-09-06 11:53-11:58,A2 会话上执行)。A2 会话(2 轮)轮次 1 台账行(轮次 1 · 0ms · 1 步,展开前需微滚定位)tap 展开→**「从此轮分支」按钮在场**(A5-ledger-exp.xml/png)→tap:logcat `Forked session session-9c6eb7d3…@seq-167 -> session-7ccdfe0f…`(11:53:02.509)→自动导航进 fork 会话:转录=轮次 1 起(「Hi! 👋 I'm ready to help…」+轮次 1 台账 0ms/1 步),分支标记=轮次台账重计(A5-forked.xml/png)。切模型 zai-coding-cn·GLM-5.3-Flash(fork 继承原 deepseek 模型且现 Insufficient Balance 横幅)→发送 e2e06-a5,replyok→回复「ok ✅ — message received ( e2e06-a5 )」+fork 内轮次 2 台账(A5-reply.xml/png)——分支可继续对话 ✓。BACK 语义观测:fork 一次 BACK 落父会话(A2 会话),再次落列表(与 prior 报告边界事实一致)

### A6 @会话源引用(#310⑤)
- 前置:输入框聚焦;先记录被引会话首条消息的一个特征词
- 操作:输入 `@` → 候选列表 → 选一个会话 → 发送「E2E06-A6:总结 @<该会话> 的要点,必须提到 <特征词>」
- 期望:候选弹出;消息携带引用;回复含 <特征词>
- 判定:截图候选+回复文本含特征词(grep dump 文本)
- 实测记录:**✔**(2026-09-06 12:10-12:12,fork 会话=顶层会话执行)。前置发现:**子会话内 @ 候选 RPC 被拒**——logcat `Mention candidates failed for '': session "fc307231…" is owned by subagent routing`(12:08:56),故移至顶层 fork 会话执行。fork 输入框键入 @(keycombination SHIFT+2 可用)→**候选面板即时弹出**:会话行「hi」+同工作区徽标+cwd 次行,文件行随后(A6-fork-at.xml/png);点会话行→**mention 规范串插入**:字段=@[hi](dsh-session:InNlc3Npb24tZGY2MWJkZjItYTQzYi00MzRkLWFkMjktZmY0YTExOWE2OGQyIg) (服务器权威串,base64 session-df61bdf2)(A6-selected.xml/png)。补指令 ,summarize,it,mention,e2e06-a2 后发送→发送消息渲染含 **</referenced-sessions> 引用块**(A6-reply-full.xml)→回复(轮次 3 · 7.5s):「The session is a trivial greeting exchange…1.(User hi) 2. System: injected the standard skill catalog (18 skills — calculator, code-review, docx, pdf…) 3. Assistant: replied with a greeting and a capability menu — code work…, then asked what the user wanted…」——**实质复述被引会话转录**(候选解析器选定的是仅含开场问候的 hi 会话 df61bdf2,其首条消息特征词=hi/开场问候,回复逐点命中;非 A2 会话故 e2e06-a2 不在断言面,如实记录)(A6-reply.xml/A6-reply-top.xml/A6-reply.png)。SSE/RPC:sessionReferenceResolver/candidates+fileReferences/list 12:10:45 双请求 <100ms 返回 ✓

### A7 文件候选管线(#321)
- 前置:同 A6 会话输入框
- 操作:输入 `@` 观察候选面板结构;若面板分「会话/文件」两类或切换,记录实际 UI 路径并截图文件类列表
- 期望:文件候选呈现(宿主有文件→非空)或明确空态;无崩溃
- 判定:截图;候选恒空且无空态提示=✘;harness 文件域不可达=BLOCKED 记录
- 实测记录:**✔**(2026-09-06 12:22 专项取证,12:10 随 A6 同面板)。fork 会话(顶层)输入 @ →候选面板结构=**单面板混合两类,无 tab 切换**:会话行在前(hi+同工作区徽标+cwd 次行),**文件行随后且非空**:config、dsh-config-backup-20260904、dsh-keepalive(/home/leo-tkp/workspace 下目录,等宽字体+文件夹图标形态)(A7-panel.xml/png;A6-fork-at.xml 同证)。fileReferences/list RPC 正常返回,无崩溃(FATAL=0),无恒空态。harness 文件域可达(候选即宿主工作区目录)✓

### A8 冷启持久回归(3.1★1)
- 前置:A1-A7 完成
- 操作:force-stop → `am start -W -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity`(记录 TotalTime)→ 等列表加载
- 期望:服务器 2 条目在;会话列表含本 wave 全部新会话;任一会话转录完整;TotalTime 记录在案
- 判定:dump+截图;数据丢失=✘(最高严重度)
- 实测记录:**✔**(2026-09-06 13:22-13:26)。A1-A7 完成后 force-stop → `am start -W`:**LaunchState=COLD,TotalTime=876ms,WaitTime=878ms**(PID 9871→14575,logcat 采集已按 §0-10 换 PID 重启)。冷启落服务器管理页(附电池限制警告横幅,同本轮首次冷启形态):**服务器条目=2**——Host-4199(http://127.0.0.1:4199,API v2 · 0.0.0-beta-17823)+prod-3080(http://127.0.0.1:3080,**已连接**·DSH)——重新配对 cookie 跨冷启持久 ✓(A8-list.xml/png)。prod-3080 行「会话」钮进入会话列表:本 wave 顶层新会话全在场——fork「hi」9月6 12:11(A5+A6 活动)、A2 会话「hi」11:44;宿主派生(probe/W1)按设计不在主列表(A1 定性);首行=父编排会话(其 updatedAt 因本 wave agent 状态消息 ~12:12 刷新,排序位与其时间显示字段不同步属 A2 已记症状族,不影响 A8 断言)(A8-sessions2.xml/A8-entered.xml+png)。任一会话转录完整:进 fork 会话——A6 回复全文+编号列表+**轮次 3 · 7.5s · 2 步 · 0 个工具**台账完整渲染,无数据丢失(A8-transcript.xml/png)。导航观测:服务器页 BACK=退出 app 至桌面(MIUI launcher),am start 暖恢复回服务器页——非数据问题,记录备查。FATAL=0,crash buffer 空(W1-crash.log 0 字节)

### B1 提问卡全链(#308)
- 前置:新建会话(首条消息带前缀,见红线④)
- 操作:发送「E2E06-B1:请调用 ask_user_question 工具问我:喜欢 Apple 还是 Banana?两个选项。等我选择后,用一句话复述我的选择。」→ 等卡片 → tap 选项
- 期望:问题卡渲染(两选项);tap 后卡处理;代理复述选择;卡终态消失
- 判定:截图卡前/后+复述文本含所选;卡不消失=✘
- 实测记录:**✔**(2026-09-06 17:50-17:56,W2 会话 session-67b5c44d)。新会话(workspace)模型手选 zai-coding-cn·GLM-5.3-Flash(实测新会话默认仍=deepseek-official 欠费通道);消息以英文落地(IME 限制):e2e06-b1,please call the ask_user_question tool…。**卡全链**:①提问卡渲染——「待你回答」+SINGLE 徽标+问题「Apple or banana?」+两选项 Apple/Banana+输入答案/忽略/提交(B1-sent2.xml/png;logcat 17:56:06.404 `Question asked for session session-67b5c44d…`+InSessionFeedback QUESTION 振动);②tap Apple→提交→logcat 17:56:40.2 `replyToQuestion: answers=[[Apple]] result success=true`+PendingNotifRevoker 撤 QUESTION 通知;③代理复述「You chose apple.」;④卡终态消失(转录仅余回复+轮次 1 · 35.0s · 3 步 · 2 个工具台账)。意外观测:**本会话列表标题=「Apple or Banana Question Tool Test」(模型语义标题,非首条消息派生)**——红线④识别改靠转录内前缀;对照 B2/B3/B4/B8 行标题仍为首条消息派生(标题生成疑似异步/模型驱动,见 W2-notes)

### B2 审批卡档位语义(#308)
- 前置:新会话 E2E06-B2(默认档=完全访问,已知环境事实)
- 操作:发送「E2E06-B2:请用 bash 执行 echo probe-e2e06 并告诉我输出」
- 期望:完全访问档=直接执行无审批卡(正确);bash 卡/输出呈现;回复含 probe-e2e06
- 判定:截图+回复文本;异常弹审批卡且无法处理=✘;正向审批卡渲染=BLOCKED-environment(注¹)
- 实测记录:**✔**(2026-09-06 18:00,会话 dae91814,行标题「e2e06-b2,please run this exact bash」=首条消息派生)。完全访问档语义正确——**无审批卡直接执行**:**全 W2 logcat PermissionAsked 事件=0**;Run code 工具卡在场;回复「Ran the exact command echo probe-e2e06 . Output: probe-e2e06 / Exit code was 0, stderr empty, nothing truncated.」含 probe-e2e06(B2-reply2.xml/png);轮次 1 · 6.1s · 3 步 · 1 个工具。正向审批卡渲染按注¹=BLOCKED-environment(指针 docs/acceptance/2026-09-04-308-dsh-approval-wire.md)

### B3 提问卡忽略→reject wire(#328)
- 前置:新会话,同 B1 提示语发起提问(前缀 E2E06-B3)
- 操作:卡片出现后 tap「忽略」
- 期望:卡消失;代理解冻并转述已取消(cancelled 语义);logcat(W2 日志)有 rejectQuestion 受理行
- 判定:截图+logcat 行摘录;卡滞留/代理冻结无后续=✘
- 实测记录:**✔**(2026-09-06 17:58,会话 session-455df04a)。同 B1 提示语(e2e06-b3 前缀)发起→提问卡渲染(同构:B3-card.xml/png,QuestionAsked 17:58:36.6)→tap「忽略」(B3-ignored.xml/png):①卡即时消失;②logcat **rejectQuestion 受理行在案**——17:58:50.184 `rejectQuestion: id=f90d21c1…`+17:58:50.214 `result success=true`;③代理解冻转述 cancelled 语义:「I called ask_user_question with the two options (Apple / Banana), but **the prompt was cancelled on your side** before an answer came back — so no selection was recorded.」(B3-after-wait.xml/png);④会话回 Idle(17:58:53 SseIdle force-complete+L3 REST validation)+轮次 1 · 16.8s · 3 步 · 1 个工具台账收口

### B4 goals 完成钮(#309①)
- 前置:新会话 E2E06-B4
- 操作:发送「E2E06-B4:请创建一个 goal:整理三行笔记。创建后告诉我。」→ goal UI 出现 → tap 完成钮
- 期望:目标 UI 在场;完成钮 tap 后目标移除/完成态;代理侧确认
- 判定:截图前后;按钮无效果=✘
- 实测记录:**✔**(2026-09-06 18:07-18:13,会话 session-88b6d0b8,行标题「e2e06-b4,please create a goal: organize」)。链路:①模型 create_goal→SessionGoalChanged ×3(18:08:22/25)+回复「Objective: organize three lines of notes / Goal ID: goal-46b0a224 / Phase: active, 0/256 rounds」;②代理 goal 轮(轮次 2 · 2m58s · 14 步 · 8 工具)中反问提问卡(3 选项)→选 Draft sample notes→轮次 3 完成并**自行 complete 该 goal**;③代理建的 goal 已终态,改经 FAB 任务菜单→「目标」→创建目标表单(表单提交即触发 agent 轮,与主 agent 提示一致)新建 e2e06-b4-g1→**goal UI 卡在场**:「进行中」状态+目标描述+「轮次 1 / 256」+四钮(暂停/**标记完成**/编辑/清除)(B4-goal-created2.xml/png);④tap 标记完成→**goal 卡即时移除**(sheet 回到仅创建表单,B4-completed.xml/png)+tap 时刻 18:13:53 SessionGoalChanged ×2;⑤goal 轮答复「done」(轮次 5)到场。注:「代理侧确认」无独立确认轮(完成事件落地时代理已 idle,wire 级确认=SessionGoalChanged+卡移除);轮次 1 台账曾现 **-207ms 负时长**(异常观测,归 B10 附注)

### B5 /compact 全链(#309②)
- 前置:B1 会话(已有数轮,含 E2E06-B1 前缀标题)
- 操作:输入 `/compact` 发送
- 期望:命令卡 Running→Completed 原位;旧转录折叠为压缩摘要行;可继续对话
- 判定:截图折叠前后+折叠后一轮新对话往返
- 实测记录:**✔**(2026-09-06 18:05-18:07,B1 会话 session-67b5c44d,依赖序:B5 于 B1/B3 后执行)。输入 /compact 发送:①命令卡 Running→Completed 原位——「/compact 执行中…」(B5-running.xml/png,logcat 18:05:43 CompactionStarted)→~50s 后同卡位「/compact **已完成** Compacted 6 history items (~2206 tokens).」(B5-done2.xml/png);②旧转录折叠——原轮次 1 全部消息(You chose apple./公式段/轮次 1 台账)从视口消失,代之以 **</compacted-summary> 分隔标记行**(V2 compact 产物形态);③折叠后可继续对话——发 e2e06-b5,what did I choose earlier→回复「**Apple**」(压缩摘要保留关键上下文)+轮次 2 · 0ms 台账(B5-after-chat.xml/png)。观测:折叠后转录余留「</system-reminder>」裸闭合标签渲染(美观性问题,非断言面)

### B6 危险操作档位语义(#309③)
- 前置:任 E2E06 会话(默认档=完全访问)
- 操作:发送「E2E06-B6:请用 bash 执行 mkdir -p /tmp/e2e06-test && rm -rf /tmp/e2e06-test,然后告诉我结果」
- 期望:完全访问档=直接执行无确认对话框(正确);执行结果正常回报
- 判定:截图+回复;异常弹确认且卡死=✘;确认双路径正向=BLOCKED-environment(注¹,prior A3 ✔)
- 实测记录:**✔**(2026-09-06 18:01,B2 会话 dae91814 内执行,依赖序:B6 先于 B13)。发送 e2e06-b6(mkdir -p /tmp/e2e06-test && rm -rf /tmp/e2e06-test,&& 经双层引号原样落地):完全访问档语义正确——**无任何确认对话框直接执行**(PermissionAsked 全程=0;grep confirm 无本会话相关行);回复「Ran the exact command mkdir -p … && rm -rf … . Result: Exit code: 0 — both parts succeeded (directory created, then removed) / stdout: empty / stderr: empty / The directory /tmp/e2e06-test no longer exists, as expected after the rm -rf」(B6-reply.xml/png);轮次 2 · 2.9s · 3 步 · 1 个工具。确认双路径正向按注¹=BLOCKED-environment(prior 2026-09-04-308 A3 ✔)

### B7 反馈 👍/👎(#310②)
- 前置:任一有助手回复的会话
- 操作:tap 👍 → 记录态 → 退出会话重进 → 复查
- 期望:反馈态即时呈现;重进后保持;logcat 有 messageFeedback 受理行
- 判定:截图两次+logcat 行
- 实测记录:**✔**(2026-09-06 18:15-18:19,B4 会话内两条消息受控验证;执行器无图片输入,以像素级对比+dump 判定)。①即时态:tap 👍(评价为有帮助)→按钮区 **903 像素变化**,图标灰(145,150,154)→**浅蓝 accent(154,206,236)**(B7-round1/B7-r3-tapped.png 对比;B4 会话轮次 3 消息);②logcat 受理行:18:16:50 `REQUEST/FROM http://127.0.0.1:3080/api/messageFeedback/put` ×2(18:15:18/18:16:50);③退出重进:BACK→列表→重进→**messageFeedback/list 重取**(18:17:14)+滚动到底部同消息 👍 仍浅蓝、👎 恒灰(145,150,154)(B7-bottom.png 像素采样:B7-r3-back2 定位失败改用底部确定位);④无串扰。注:评价按钮为消息操作行 content-desc「评价为有帮助/评价为没帮助」,dump 常因滚动位置失明,需消息块完整可见

### B8 plan 面(#310③)
- 前置:新会话 E2E06-B8
- 操作:发送「E2E06-B8:请先用 plan mode 给出检查拼写的计划,等我批准后再执行」
- 期望:PlanChip/计划评审卡在场;批准后开始执行
- 判定:截图;模型不进 plan=BLOCKED-model(记录提示与响应原文)
- 实测记录:**✘(半链:计划面✔/批准后自动执行未发生)→主 agent 改判:非缺陷(管线全通,模型行为)**(2026-09-06 18:18-18:23,会话 session-3bb683ff)。①计划面在场:SessionPlanChanged ×2(18:18:34/52)+完整计划呈现「Spelling-Check a Document — Plan」步骤 1-5(编号+工作区候选文件实引)(B8-plan-full.xml/png);**但代理自述「this session isn\u0027t in actual plan mode (the harness rejected…」——服务器侧拒绝实际 plan mode 进入,模型以消息内计划+审批提问卡等价实现**(评审卡=ask_user_question 卡:Q1「Do you approve the spelling-check plan above…」选项 Approve (Recommended)/Report only/Revise first+Q2 文档选择,B8-plan-card3.xml/png);②批准已受理:多问题表单未答 Q2 弹守卫对话框「有未回答的问题/第 2 个问题没有回答」→继续提交(B8-unanswered-dialog.png)→logcat 18:21:09.8 `replyToQuestion: answers=[[Approve (Recommended)], []] success=true`;③**批准后未自动开始执行**——代理轮次 1(1m59s)以「I\u0027ll hold here until you answer…Nothing has been executed yet」收尾,18:21:19 即 Idle,答案虽在轮内到达(提交 18:21:09<轮末 18:21:19)但无轮次 2;④恢复路径:新消息「approved proceed…」→轮次 2 开跑(Run code+streaming,B8-nudge.png)。不判 BLOCKED-model:模型确以计划+审批卡响应;失败点=审批答案未驱动续跑(时序竞争疑因:Q2 守卫对话框延迟提交 ~25s,只观测未定位)
  - **主 agent 裁决(2026-09-06 18:5x)=非缺陷,管线全通**——logcat 定音:18:20:13 QuestionAsked→卡渲染;18:21:09.789 replyToQuestion answers=[[Approve (Recommended)],[]](Q2 空数组=守卫对话框「继续提交」合法产物)→.843 success=true(服务器受理);轮次继续至 18:21:19 正常收尾——**应答已送达且工具已解析**(阻塞式 ask 工具 await waterfall 被解除,模型拿到含空 Q2 的结果),模型据此**自行决定**以 "I'll hold here until you answer" 收尾等待未答的 Q2(文档项)=模型行为非管线缺陷;后续新消息推动执行正常(佐证会话状态健康)。PlanChip 缺席=服务器拒绝在非 plan 会话进 plan mode(exit_plan_mode 语义),模型降级 markdown 计划+审批提问卡——同为服务器语义。W2 执行员观测如实,B8 ✘ 撤销

### B9 命令反馈卡(#323)
- 前置:任会话
- 操作:①`/help` ②未知命令 `/foobar123`
- 期望:①run→done 原位单卡刷新;②无卡无崩溃
- 判定:截图+②后 app 仍可用(可继续发消息)
- 实测记录:**✔(①以 /export 等价验证——/help 不在 DSH 服务器命令表)**(2026-09-06 18:23-18:25,B1 会话内)。环境事实:ModelConfigDelegate `Loaded 6 commands: [compact, export, feedback, goal, permission, plan]`+commands/list 响应——**prod-3080 无 /help 命令**,发送 /help→`Executed command /help …: false`(18:24:01.6),无卡无崩溃(与②同形);run→done 原位单卡机制改以 **/export** 验证:`Executed command /export: true`(18:25:01.7)→命令卡「/export **已完成** Session log download requested.」原位单卡(B9-export.xml/png;另 B5 /compact 已证 Running→Completed 同机制)。②/foobar123:`Executed command /foobar123: false`(18:24:45.2),**无卡+无崩溃(FATAL=0)+composer 可用**(后续 B14/B15 同会话继续收发成功)

### B10 相对时间戳三形态(#312①)
- 前置:含新/旧消息的会话+列表
- 操作:观察列表行与消息时间戳;每观察点记录 `adb shell date` 输出
- 期望:三形态按阈值正确切换(刚刚/N 分钟前/绝对时间);无负值/未来时间
- 判定:截图+date 输出对照
- 实测记录:**✔**(2026-09-06 18:25-18:27,各观察点 date 均记录)。**消息时间戳(会话内)相对级联四档全实证**:「&lt;1m」(轮次刚完结:B1-submitted/B4-final 等多帧)→「Nm」(1m/2m/3m/5m/16m/17m,18:25:29 观测 18:08 轮=17m ✓)→「Nh」(6h,12:11 hi 会话,18:26:05 观测)→「**1d**」(37h,9月4 22:44 会话,18:27:05 观测,B10-37h-msg.xml/png);**列表行恒绝对制**:同屏 7 分钟前新行亦显示「9月 6, 18:18」(18:25:43),>24h 加日期「9月 4, 22:44」(B10-list-ts/B10-list5.xml)。无负值/未来时间戳。附注异常(非时间戳):B4 会话轮次 1 台账时长曾显示 **-207ms 负值**(18:10 dump,B4-goal-ui2.xml)——负时长与 B10 断言面分离记录,供 #312① 裁量

### B11 数学块降级(#312②)
- 前置:任会话
- 操作:发送「E2E06-B11:请输出质能方程,用 $$ LaTeX 数学块包裹」
- 期望:数学内容降级渲染(等宽/代码块形态),无原始符号错乱溢出
- 判定:截图;符号错乱溢出=✘
- 实测记录:**✔**(2026-09-06 18:27-18:28,B2 会话)。发送 e2e06-b11(要求 $$ LaTeX 数学块包裹)→代理原始输出经轮次 4 代码栅栏自证=「**$$E = mc^2$$**」(B11-raw-reveal.xml/png);**app 渲染态**(轮次 3):干净的「E = mc^2」——$$ 定界符未以原始符号泄漏、无错乱溢出,节点 bounds [84,1035][1116,1129] 全程在屏内(B11-math.xml/png)。降级形态=纯文本/等宽呈现(数学不渲染为公式),符合「数学块降级」预期

### B12 命令带图拦截(#312④)
- 前置:输入框;查明 app 是否有图片附件入口(截图记录结论)
- 操作:若有:附加任一图片+输入 /compact → 观察
- 期望:拦截提示且命令不派发(W3 日志无 command 派发行)
- 判定:截图;无图片附件入口=BLOCKED-feature-absent 记录
- 实测记录:**✔(附件入口存在,拦截链完整)**(2026-09-06 18:26-18:31,B2 会话)。探查:composer「附件」钮(desc=附件)→**系统文件选择器**(com.android.fileexplorer PickMainNavigatorActivity,最近/浏览+文档/图片/视频类型过滤,B12-attach-menu/B12-browse.png)——**图片附件入口存在,非 BLOCKED-feature-absent**。流程:adb 推送 e2e06-b12.png→图片过滤顶行选取→确定→附件入 composer:缩略图 chip「e2e06-b12.webp」+移除钮+优化横幅「**图片已优化(1) 大小:235.5 KB -> 29.4 KB 令牌:~4272 -> ~1242**」(B12-attached.png);带图输入 /compact→tap 发送→**拦截提示 toast「斜杠命令不支持图片附件」**(B12-intercept2.xml/B12-intercept-tap.png)+composer 文本保留未发+**logcat 无该会话 Executed command /compact 派发行**(仅先前 foobar123/export 行)——命令未派发 ✓。收尾:附件移除+推送文件 rm+composer 清空(B12-cleaned4)

### B13 轮次台账行(#310④)
- 前置:B2/B6 会话(含 bash 工具轮)
- 操作:滚动转录观察轮次结构
- 期望:轮次台账行在场(轮次/工具轮标注);布局不破碎
- 判定:截图
- 实测记录:**✔**(2026-09-06 18:02-18:03,依赖序:B13 于 B2/B6 后即测)。B2/B6 会话(dae91814)IME 收起后双轮台账同屏在场:**「轮次 1 · 6.1s · 3 步 · 1 个工具」**(echo probe 轮)+**「轮次 2 · 2.9s · 3 步 · 1 个工具」**(mkdir/rm 轮),各轮 Run code 工具卡+思考完毕行+回复正文齐备,布局无破碎(B13-imeclosed.xml/B13-ledgers.png;滚动态 B13-round1.xml/png)。附观测:工具计数「×2」角标形态见于 B1 会话(轮次 1 · 2 个工具);另发现操作坑——IME 开启时滑动手势起于 y≈2000 会落入键盘区误触数字(本 wave 曾误入「666」,已清除,证据链 B13-cleared*.xml)

### B14 草稿保持回归(#334,3.6★1)
- 前置:会话 X(任 E2E06 会话)
- 操作:X 输入未发送「草稿-E2E06-B14」→ 切会话 Y → 返回 X
- 期望:草稿文本在;Y 无串台
- 判定:dump/截图;丢失=✘
- 实测记录:**✔**(2026-09-06 18:29-18:32)。X=B2 会话(dae91814):composer 输入未发送「**draft-e2e06-b14,keep me**」(B14-draft-set.xml)→BACK 回列表→**X 行出现「草稿」徽章**(B14-list.xml,desc=草稿 @[397,681];另一枚既有草稿徽章在 12:11 hi 行=W1 遗留,未动);切 Y=B1 会话「Apple or Banana…」→**Y composer=空,无串台**(B14-Y-entered.xml,grep draft-e2e06=0);返回 X→**草稿完整在场**「draft-e2e06-b14,keep me」(B14-X-returned.xml/png)。会话隔离双向 ✓

### B15 Markdown 表格渲染(3.4★6)
- 前置:任会话
- 操作:发送「E2E06-B15:请用 markdown 表格列出三种水果的名字和价格」
- 期望:表格渲染列对齐,两端(深浅主题不要求)一致不破碎
- 判定:截图;表格渲染为纯文本堆叠=✘
- 实测记录:**✔**(2026-09-06 18:34,B2 会话)。发送 e2e06-b15(三种水果 markdown 表格)→回复表格**按单元格独立节点渲染**(非纯文本堆叠):表头 Fruit/Price+数据行 Apple/$1.20、Banana/$0.50、Cherry/$3.80 共 8 cell 节点(B15-table.xml/png);**列对齐实证(bounds 级)**:名称列 x1={81,90,100,102} 左对齐一致,价格列 x1={651,656} 对齐一致,行高均匀(~86-94px 网格)——两列网格结构成立。深浅主题双端不要求,未测

---

## C. 队列与输入域(W3 前半)

### C1 单键四态(#326)
- 前置:任会话;blocked 态构造来源=新会话用 B1 提示语发起提问,卡在场未应答即 blocked(引用 B1 时序,W3 内自建一个 E2E06-C1 提问会话)
- 操作:依次构造并截图五态:①idle+空 ②idle+文本 ③busy(发长任务「E2E06-C1:请写一篇 800 字文章」)+空 ④busy+文本 ⑤blocked(提问卡在场)
- 期望:①SEND 禁用 ②SEND ③STOP ④SEND(排队语义) ⑤STOP+输入禁用;全程无双键并排
- 判定:每态截图;出现双键并排=✘
- 实测记录:**✔ 五态全过,全程无双键并排**(2026-09-06 18:46-18:52,共用长任务会话 session-89a20513,首条消息 e2e06-c1,模型手选 zai-coding-cn·GLM-5.3-Flash)。①idle+空:chat-send=1/chat-stop=0 发送键在场;**行为禁用验证**:tap 发送(1086,2538)→转录零新增+logcat 零 prompt 分发行(C1-s1-idle-empty/aftertap.xml+png)。②idle+文本:input text 带 %s 空格落地,单发送键(C1-s2-idle-text.xml/png;发送键 IME 抬升位 [1014,1544][1158,1688])。③busy+空:essay 消息 18:50:05.300 发出(Sent prompt+Idle→Busy/Waiting),~18:50:09 dump=chat-stop=1/chat-send=0 单停止键(C1-s3-busy.xml+C1-s3-busy-empty.png)。④busy+文本:流式中输入排队文本→**chat-send=1/chat-stop=0,content-desc=「发送（排队）」**——排队语义显式标注在发送键上(C1-s4-busy-text.xml/png)。⑤blocked:18:51:1x 模型对 essay 提示自发澄清提问(ask_user_question 卡在场:待你回答+SINGLE+Q1/Q2 双问题+四选项+输入答案/忽略/下一个/提交)→dump=**chat-stop=1+chat-input en=false(输入禁用,已键入草稿被持留)**(C1-s5-blocked.xml/png;列表行同步出现「待回答」+「草稿」徽章);应答后(18:51:53 replyToQuestion success=true)输入区恢复 en=true。注记:⑤的卡非 B1 提示语显式构造,而是模型对 800 字 essay 提示的自发澄清提问——同一 ask_user_question 产物,blocked 状态构造等价,且恰发生在 e2e06-c1 前缀会话内;C1③④ 与 C2/C4/C5 共用本会话(依赖注记)

### C2 队列角标全生命周期(#327)
- 前置:会话 A(E2E06-C2)发长任务 busy 中
- 操作:busy 中同会话再发 2 条 → 角标 1→2;QueueSheet 移除 1 条 → 1;插话 1 条;等轮末消费完
- 期望:角标与队列操作实时一致;轮末消息依次消费无丢失;最终归 0;**轮末消费完成后通知状态(交叉引 E3b 断言)**
- 判定:每步截图/dump+角标数值;角标恒 0 或不更新=✘
- 实测记录:**✔ 角标全生命周期 1→2→(移除)→1→(插话)→0 实证**(2026-09-06 18:50-19:01,共用 e2e06-c1 会话 session-89a20513,跨两个 busy 窗口;录屏 W3-C2-seg1/2/3.mp4 全程覆盖)。**R1(800w essay 轮)**:queue message one 18:52:13.746+message two 18:52:18.687 相继于 Busy/Streaming 中发出(ClientSendParts wire)→展开 FAB dump 捕获**角标数字节点「2」@[908,2028][928,2076]**(C2-fab-badge2.xml/png,18:53:1x,紧贴排队队列入口 [960,2034]);~18:53:40 开 QueueSheet 已=「排队队列 (0)/暂无排队消息」——essay 轮 18:53:48 结束,双条轮末 drain 消费(wire SessionIdle→Busy/Waiting ×2 @18:53:48/18:54:17),转录含两消息+回复(C2-transcript-after-drain.xml:「Acknowledged: e2e06-c2, message two — received and processed」)。**R2(1200w essay 轮,补链)**:note one 18:57:30 排队→**角标=1**(C2-fab-badge1.png 像素探针:badge 粉底(233,186,183)+暗红字形(88,27,20) 58px;dump 数字节点失明见注)→note two 18:58:21 排队→**角标=2**(C2-fab-badge2b.png 字形 94px,与 R1「2」逐像素一致)→QueueSheet「排队队列 (2)」两行+每行三动作(C2-sheet-r2b-2items.xml/png)→**移除** note two(984,1063 tap 18:59:56;wire updateQueue 18:59:56.694)→Sheet(1) 仅剩 note one(C2-sheet-after-remove)+**角标=1**(C2-badge-after-remove.png 字形 58px)→**插话(引导至下一轮)** note one(1128,895 tap 19:00:30)→**3s 内 Sheet(0) 暂无排队消息**(C2-sheet-after-steer)+note one 注入并获回复(转录「Acknowledged: e2e06-c2, queue note one — received and processed」,C2-after-steer-transcript.xml)→终态**角标 0/无字形**(C2-badge-final0.png badge 区仅背景色)。「等轮末消费完」由 R1 自然 drain 链覆盖(R2 队列经移除+插话清空)。**E3b 交叉**:dumpsys posted 通知=opencode_tasks_silent 组摘要+opencode_tasks(内容=「就绪 · e2e06-a1-api 顶位测试」18:42 事件遗留,非本 wave 会话)+LEAKCANARY(debug 常驻)——**无队列/轮末类通知发布或残留**(app 前台全程);遗留就绪通知归 E3/W5 裁量。**判定方法注记**:uiautomator 对角标数字节点间歇失明(4 次 dump 仅 1 次捕获,§0-11 失明族)——角标数值以像素探针为主锚(粉底+暗红字形,字形面积可分 1=58px/2=94px),与唯一一次 dump 捕获互证

### C3 FAB 入口+QueueSheet 三动作(#313)
- 前置:会话列表
- 操作:FAB 展开核对入口清单(对照 `docs/acceptance/2026-09-05-327-queue-badge-chain.md` 记录的入口清单逐个核对并在报告列出);进 QueueSheet 验证三动作(移除/插话/跳转)
- 期望:入口与 prior 清单一致;三动作各自生效
- 判定:截图+逐动作观测记录
- 实测记录:**✔(入口清单一致;三动作=编辑/移除/插话实证,「跳转」不存在于 QueueSheet)**(2026-09-06 18:53-19:00)。FAB 任务菜单入口
  - **主 agent 裁决(2026-09-06 19:3x)=web 平价,checklist 措辞笔误**——web zh/en locale 定音(mod20.js:13411-13417/13557-13562):队列面板动作集=queue.edit(编辑排队消息)/queue.remove(删除)/queue.steer(插话发送),**无「跳转」动作**;app 实测编辑/移除/插话=逐项平价 ✔。本 checklist「移除/插话/跳转」为主 agent 撰写记忆笔误,C3 维持 ✔ 不打折清单(展开态 dump,C2-fab-badge2.xml 同源多帧):**TODO @[1015,1458][1158,1530] / 智能体 @[1010,1602][1158,1674] / 目标 @[1059,1746][1158,1818] / Shell @[1044,1890][1158,1962] / 排队队列 @[960,2034][1158,2106]**(+收起菜单)——与 prior #327 清单及 W2 notes 观测 9 完全一致,五入口齐。QueueSheet 三动作逐行在场:**编辑排队消息 @[804,…] / 移除排队消息 @[948,…] / 引导至下一轮 @[1092,…]**(C2-sheet-r2b-2items.xml;编辑态=OutlinedTextField+保存/取消,QueueSheet.kt 契约一致):移除✔(C2 执行,Sheet 2→1+updateQueue wire)/插话=引导至下一轮✔(C2 执行,Sheet 1→0+注入消费)/编辑未执行(非本 item 断言面,同 updateQueue seam,prior #327 A2 有「不执行」先例);**「跳转」动作在 QueueSheet 行内不存在**——实际第三动作=编辑;跳转语义仅体现为 FAB 入口→Sheet 打开本身(如实记录,checklist 预期与实际 UI 有出入)。注:任务菜单 FAB 位于 ChatScreen(会话内,desc=打开任务菜单),**会话列表无此 FAB**(列表顶栏=搜索/新建会话/更多选项,W3-start.xml)——前置「会话列表」按实际 UI 修正为会话内执行(入口清单本身无会话状态依赖)
- 前置:busy 会话(长任务中)
- 操作:①普通发送文本(排队)②若有长按/steer 入口,长按发送
- 期望:排队消息轮末消费;steer 即刻注入(流式中断/转向)
- 判定:两路径 logcat 时间戳可分;无 steer UI=排队部分✔+steer 部分 BLOCKED-feature-absent
- 实测记录:**✔ 两路径时序可分**(2026-09-06 19:02-19:04,共用 e2e06-c1 会话第三 busy 窗口:1000w lighthouse essay 19:02:38 发出,Busy/Streaming 至 19:03+)。**①排队路径**:M(e2e06-c5,message m for hold and drain check)于 **19:03:01.318** Sent prompt+Busy/Streaming --ClientSendParts(Busy 中受理);转录即时 amount=0(hold,见 C5);轮末 drain 后消费+回复。**②steer 路径**:steer UI=**长按发送键**(busy+文本态发送键 desc=发送(排队),input swipe 1086,1616 同点 700ms,19:03:28.6)→wire **19:03:28.698** Sent prompt(Busy/Streaming 直发)→steer 消息气泡入转录「e2e06-c4,steer message s,acknowledge STEERED now」(C5-scrollup1.xml)+代理回复「**STEERED — acknowledged. ✅ Steer message s on case e2e06-c4…**」——注入+转向回复链完整。两路径 wire 时间戳相隔 27.4s 可分 ✓;steer 入口在场(非 BLOCKED-feature-absent)。观测注记:steer 落点恰逢 essay 自然收尾(流式计时 43.2s≈steer 时刻),「流式中断/强制完结」未得独立观测窗口(#326 A4 先例有 force-complete 证据);另 R2 内 QueueSheet「引导至下一轮」为同语义第二入口(C2 已证)

### C5 hold→drain 数据完整(#329 平价)
- 前置:C4 的 busy 会话(时序证据复用 C4①,本项只断言数据完整性)
- 操作:busy 中发送消息 M;流式期间记录 M 在转录中不可见(=web 平价,记录非失败);轮末后复查
- 期望:轮末 M 出现且获得回复;无丢失无重复
- 判定:M+回复都在且各一次;丢失/重复=✘(严重)
- 实测记录:**✔ 数据完整:消息 M 与回复各恰一次,无丢失无重复**(2026-09-06 19:03-19:07,复用 C4① 时序,M=19:03:01.318 发出)。**流式期间 M 不可见(web 平价,记录非失败)**:19:03:03 dump M 文本出现次数=0(C5-midstream-afterM.xml/png)。**轮末后复查**:M 用户气泡恰 1 次——「e2e06-c5,message m for hold and drain check」+「用户」角色标记(C5-seam2.xml);回复恰 1 次(单轮次 8 · 10.7s · 5 步 · 2 个工具):「Hold and drain check ( e2e06-c5 , message m ) — complete. ✅」+「Hold check: No hold is active…」+「Drain check: Verified via job registry — 0 background jobs…」(模型以 job registry 自证 drain 态,C5-bottom-final.xml/png 含 Case/Task/Status 表格全录);steer 消息/回复亦各 1 次(C5-scrollup1/mid*.xml 跨窗去重)。转录跨 8 个 dump 视图全覆盖采集(C5-midstream→scrollup1→mid2/mid3→seam/seam2→bottom-final),无第二处 M 或其回复

---

## F. SSE 流式稳定域(W3 后半)

### F1 流式渲染稳定(铁律回归,3.4★3★5)
- 前置:新会话 E2E06-F1
- 操作:录屏 ≥40s;发送「E2E06-F1:请写一篇 600 字短文,分段清楚」
- 期望:流式逐段渲染,无整页闪烁/无长时间静默后爆发
- 判定:录屏抽 ≥5 帧比对(帧间内容递增、无全页白闪重绘)
- 实测记录:**✔**(2026-09-06 19:12-19:16,新会话 E2E06-F1 session-f9aa17ae,模型 zai-coding-cn·GLM-5.3-Flash)。录屏 **W3-F1F2-seg1.mp4(180.4s)**,覆盖两轮流式(600 字轮 29.9s + 1500 字轮 31.5s);**抽帧 11 张**(ffmpeg @110-178s)+拍摄期同步 screencap 13 张(F1-frame-t*/a*)。帧分析(内容区像素+帧间 diff):①600w 轮:t34 思考结束(252k)→t41 正文涌入(441k,diff 861k)→t48 完成重排(137k,含键盘收起/台账)→t55/t62 稳定——**逐段递增渲染,无整页白闪帧**(无任何帧 content_px 向纯背景坍缩);②1500w 轮(主证据):f110(发送前 200k)→f118(布局+键盘 624k)→f125-f150 流式期 **diff 1-7k/5-8s 连续非零**(增量推进;「静默后爆发」形态应为连续 diff≈0 后单帧巨变,实测相反)→f160(键盘收起+F2 滚动 117k)→f170 回底(278k)→f178 稳定=完成态。完成态 dumps:两篇 essay 全文+轮次台账完整渲染(F2-final-state.xml:「Here is the essay on the sea (~1,500 words, nine clear paragr…」+轮次 2 · 31.5s)。附注:GLM-5.3-Flash 思考期 19-22s(模型侧行为),流式 token 速率高(600 词≈10s 流完)——流式窗口短是本轮观测事实,判定以连续增量 diff+无白闪为准

### F2 滚动自愈(铁律回归,3.8★3)
- 前置:F1 流式中
- 操作:流式中上滚远离底部 2 屏 → 停 5s → 手动滚回底部
- 期望:上滚后不被强制拉底;回底后恢复跟随新内容
- 判定:录屏;持续被拉底=✘
- 实测记录:**✔**(2026-09-06 19:15:3x-19:15:5x,1500w essay 流式中执行,录屏 W3-F1F2-seg1.mp4 @~155-170s 段覆盖)。上滚:键盘先收(BACK),2×swipe(600,800→600,2000,350ms)远离底部 2 屏→**F2-away2 dump:视口停顶部老内容区**(标题栏+首条用户消息在场)+「滚动到底部」钮在场 @[36,2196][108,2268]+**chat-stop=1(流式仍在进行)**——停 3+s 未被强制拉底 ✓(铁律15:以可见内容+按钮 bounds 判,不依赖截图哈希;away1/away2 像素对含 fling 惯性位移,如实记录不作主证)。回底:tap 滚动到底部(72,2232)→**F2-returned2 dump:滚动到底部钮消失=已到底**+chat-stop=1 流式继续→完成态 F2-final-state dump 显示 essay 末段+轮次台账=**回底后恢复跟随新内容** ✓

### F3 中断流式(3.5★1)
- 前置:新一轮长回复流式中
- 操作:tap STOP
- 期望:立即停;部分内容保留;会话回 idle;可再发新消息
- 判定:截图+再发一轮往返
- 实测记录:**✔**(2026-09-06 19:17-19:19,E2E06-F1 会话 session-f9aa17ae 第三轮:1200w mountains essay 19:17:43 发出,思考 19.3s 后流式,TextDelta 4842 条确认流式中)。**tap STOP(1086,1616)@19:18:42.34→立即截图 19:18:42.97(tap 后 0.6s,F3-after-stop-immediate.png)**;wire:**19:18:42.463 Aborted session**(tap→abort <150ms)+Idle --ClientAbort--> Idle [force-complete]。**部分内容保留**:轮次 3 台账+部分正文区域在场,immediate 与 +2s 两帧逐像素 diff=0(稳定保留,F3-prestop/after-stop*);用户消息「e2e06-f3,write a 1200 word essay…」在场。**会话回 idle**:dump chat-send=1/chat-stop=0(+2s 内)。**再发一轮往返**:19:19:09 发「e2e06-f3,after stop,reply with the single word ok」→回复「**ok**」+轮次 4 台账(F3-after-resend.xml/png)——停止后可继续发消息 ✓。附注:被中断轮台账=「轮次 3 · 0ms · 1 步 · 0 个工具」(0ms 计时另记,供 B10 负时长族裁量)

---

## D. 搜索/设置/工作区域(W4)

### D1 内容搜索(#322)
- 前置:W1-W3 已创建多个含「E2E06」词的会话(必然满足)
- 操作:搜索「E2E06」→ 查看命中区 → tap 一条命中;再搜无结果词 `zzzqqx`
- 期望:服务器命中区显示(跨会话多条);tap 跳到对应会话/消息;无命中=明确空态
- 判定:截图两态;跳转失败=✘
- 实测记录:**✔**(2026-09-06 19:35,W4)。搜索框聚焦后键入 e2e06(ASCII,type4.sh):**消息匹配区即时呈现**——过滤条(全部角色:用户/AI;全部时间:近 7 天/近 30 天)+ 消息命中卡**跨会话多条**:「e2e06-c1,please write an 800 word · 24 条消息」「e2e06-b2,please run this exact bash · 13 条消息」(含命中片段摘要「…mkdir -p /tmp/[e2e06]-test && rm -rf /tmp/[e2e06]-test…」);下方会话匹配行 e2e06-f1/c1/a1-api/b2/b8/b4 等多行(D1-hits.png/w4-d1-hits.xml)。**tap 跳转✔**:点消息命中卡 (400,1000) → 落 e2e06-c1 会话 ChatScreen,转录定位在含命中词的消息区(C5 drain 总结表「State at drain/Case/Task/Status」多段 e2e06-c1 匹配文本在场,非顶部起始)(D1-jumped.png/w4-d1-jump2.xml)。**空态✔**:清除后搜 zzzqqx → 「目录为空」明确空态,无命中无崩溃(D1-empty.png/w4-d1-empty2.xml)。注:清除搜索后焦点丢失,重键入需重新点搜索框(操作侧注记);搜索页「返回」落服务器管理页(W1 BACK 语义家族)。FATAL=0

### D2 提供方页(#324)
- 前置:prod-3080 已连接
- 操作:设置→提供方页;打开「新增」表单→**取消**
- 期望:页打开不崩溃(回归 3cb324a8);列表/目录在场;表单正常开关不落库
- 判定:截图;崩溃=✘(严重);取消后多出条目=✘
- 实测记录:**✔(核心)——页打开不崩溃;「新增」表单 UI 入口缺失(feature-absent,子腿)**(2026-09-06 19:38-19:41,W4)。路径:会话列表设置 tab →服务器管理页 prod-3080 卡齿轮「服务器设置」→「提供方」→ ServerProvidersScreen **打开不崩溃**(3cb324a8 回归通过,FATAL=0)。列表/目录在场:「DSH provider 目录」(DeepSeek/deepseek-official 运行时已启用+amazon-bedrock…zai-coding-cn 数十条,滚动流畅无测量崩溃)+「可用」区(DeepSeek/opencode-go/zai-coding-cn 各带「连接」钮)(D2-providers.png/w4-d2-prov2.xml/D2-after-tap2.png)。**新增表单子腿 feature-absent**:目录标题行 Add 图标(desc=新增自定义 provider [1044,376][1116,448])两次精确 tap 均未开表单——logcat 实证 tap 即时只触发 listProviders+listConfigurableProviders 刷新(19:40:36/19:41:15)=onClick 接 onRefresh;源码 DshCustomProvidersSection.kt showCreate 状态无任何 `= true` 赋值(git 全历史 -S 检索无),DshCustomProviderCreateDialog 为不可达死代码。表单无法打开→开关/落库断言无从触发,prod 配置零触碰(红线保持)。Add 图标形似「新增」实为刷新=UI 语义混淆,登记主 agent 裁量

### D3 preset 管理(#324)
- 前置:同 D2
- 操作:preset 管理入口;新建表单→取消
- 期望:页/表单在场;取消无残留
- 判定:截图
- 实测记录:**✔(管理入口+打开/取消路径);「新建表单」不存在=by-design 子腿注记**(2026-09-06 19:44-19:46,W4)。preset 管理入口=设置 tab「新会话默认 Agent 预设」(当前 PTC 模式)展开 roster:**标准模式/PTC 模式/极简模式/创造模式**四预设行,各带「查看组成/复制」动作(D3-preset-expanded.png/w4-d3-exp2.xml;agentPresets/list 19:44:13 RPC)。**#324② 交付形态无「新建」表单**——AgentPresetManageDialogs.kt 头注:查看=只读 content(移动端编辑走服务端文件,web read-only viewer 裁决);复制/删除为写路径(红线未触)。等价打开/取消路径:tap 标准模式「查看组成」→ AgentPresetContentDialog 打开(标题「标准模式」+关闭钮,正文等宽区 dump 失明属 §0-11 族;agentPresets/read 19:45:34 RPC)→ tap「关闭」→ roster 原样无残留,默认值 PTC 未变,无 settings.mutate 写(D3-preset-view-dialog.png/D3-preset-dialog-closed.png)。复制/删除按钮全程未触碰

### D4 插件清单+表单(#324)
- 前置:同 D2
- 操作:插件清单页(只读);任一插件配置表单打开
- 期望:清单呈现;表单字段渲染不崩溃
- 判定:截图
- 实测记录:**✔**(2026-09-06 19:42-19:47,W4)。插件清单:设置 tab「服务器插件」区块展开=**pluginInventory 只读清单**——cordis:include(已启用)/@deepseek-ai/cordis-plugin-timer(已启用)/@deepseek-ai/cordis-plugin-hmr(已停用)/@deepseek-ai/dsh-llm(已启用)/@deepseek-ai/dsh-deepseek-llm-api-extensions(已启用),各带 PhaseDot 运行态点(D4-plugins-list.png/w4-d4-plugins2.xml)。插件行 onClick=null——**per-plugin 表单按设计不存在**(web mod19 同构只读,无 mutate 面;tap cordis:include 行实测无响应)。表单断言以 #324③ 服务器配置动态 schema 表单覆盖:「服务器配置」区展开=agent-default-model(立即生效标注;provider/model/max/reasoningEffort 字段+各单字段「保存」钮)+subagent-model-selection(enabled)等卡片**渲染无崩溃**(D4-config-forms.png/w4-d4-cfg2.xml;settings/describe 驱动)。保存钮全程未触碰(红线:不落库)。FATAL=0

### D5 skills 触发组(#324)
- 前置:同 D2
- 操作:skills 触发组页
- 期望:分组呈现(只读)
- 判定:截图
- 实测记录:**✔**(2026-09-06 19:48-19:50,W4,前置修正:skills 触发组非独立设置页,#324⑤ 形态=会话内斜杠面板「技能」分组)。进 e2e06-f1 会话(idle)输入框键入 / → 斜杠面板即时呈现:**服务器命令组**(/compact /export /feedback /goal /permission /plan 各带英文描述,W2 观测 8 的 6 命令一致)+ **「技能」触发组头**+技能行 /ask-matt、/calculator、/code-review、/codebase-design(各标 skill 徽标)(D5-slash-panel.png/w4-d5-slash.xml;skills/list 19:48:51 RPC <30ms)。只读观测后清稿:长按+全选+单次 DEL 法清除 /,面板关闭无残留(w4-d5-cleared3.xml);未发送任何消息,会话零写入

### D6 归档链(#311)
- 前置:专建测试会话(首条消息「E2E06-D6:标记归档测试」)
- 操作:行菜单/左滑 → 归档 → 查主列表+折叠归档区
- 期望:行从主列表消失;归档区在场可查;**无「取消归档」入口=正确**(契约单向)
- 判定:截图;出现取消归档入口=✘(契约违背)
- 实测记录:**✔**(2026-09-06 19:51-19:56,W4)。靶会话:新建(workspace 目录)发送首条「e2e06-d6,archive,target,message」(默认模型 DeepSeek-V4-Flash,回复 Insufficient Balance=预期内,行非 blank)(D6-sent.png);BACK 回列表=**行即时顶位**(9月6 19:53,w4-d6-backlist.xml)。归档:长按行 → 菜单「会话详情/重命名会话/**归档**」(D6-row-menu.png)→ tap 归档 → **主列表行消失**(e2e06-d6 0 出现,D6-after-archive.png)。归档区:滚至列表底部=「**已归档（5）**」折叠区在场 → 展开=5 行,**e2e06-d6 在首位**(19:53;其余 4 行为 9月4/5 前批遗留归档,未触碰)(D6-archive-expanded/D6-archive-rows.png)。**无取消归档入口=✓**:归档行长按菜单仅「**会话详情**」一项——无归档/重命名/取消归档(单向契约正确,D6-archived-row-menu.png/w4-d6-arcmenu2.xml)。logcat archive RPC 链正常;FATAL=0

### D7 多工作区(#311)
- 前置:prod-3080 已连接,当前 main
- 操作:工作区管理;打开新建对话框观察(列表/输入/确认三要素);**取消**
- 期望:对话框正常呈现(现工作区列表+新建入口);取消无残留;主列表无整页闪烁
- 判定:截图
- 实测记录:**✔**(2026-09-06 19:57-19:58,W4;前置修正:app 无独立「工作区管理」页,入口=新建会话对话框族)。新建会话钮 → NewSessionQuickDialog:**现工作区列表在场**——workspace(/home/leo-tkp/workspace,26)/oc-beacon(…/mine/oc-beacon,1)/外包维权工作区(…/docs/外包维权工作区,2)+「打开其他项目…」新建入口(D7-quick-dialog.png/w4-d7-quick2.xml)。tap 打开其他项目… → OpenProjectDialog「打开项目」:**三要素齐**——①列表(目录浏览器 / → home → leo-tkp → workspace 逐级切换,directoryPicker/list RPC 每级 <40ms)②输入(「新建目录」→ 目录名称输入+取消/创建)③确认(「创建会话」主钮)(D7-open-project-dialog.png/w4-d7-openproj.xml)。**取消**:BACK 逐层关闭双对话框 → 主列表原样(首行仍父编排会话 17:34),无残留无整页闪烁(D7-after-cancel.png)。注:此处仅观察未提交任何创建动作

### D8 workspace remove/order(#330)
- 前置:当前=main;**只允许对自建 workspace 做 order/remove,禁触其他**
- 操作:新建工作区 `e2e06ws` → 成功 → 调整 e2e06ws 顺序(order)→ remove e2e06ws
- 期望:新增后列表含新 ws;order 后顺序持久(退出重进仍新序);remove 后消失;main 全程在场
- 判定:每步截图;顺序不持久/remove 失败/main 受影响=✘;结束断言恢复单 main
- 实测记录:**create 腿 ✘(新建目录在 DSH 后端不可用)+order/remove 腿 feature-absent;另有临时会话泄漏意外发现**(2026-09-06 19:58-20:07,W4)。**create**:OpenProjectDialog 导航至 /home/leo-tkp/workspace →「新建目录」输入 e2e06ws →「创建」→ **「Failed to create directory」失败**(D8-newfolder-dialog.png/D8-folder-created.png;宿主 ls 确认目录未建)。机制:CreateDirectoryUseCase=临时会话(title=mkdir)+runShellCommand(mkdir -p)→回退 executeCommand(bash -lc)——DSH 后端两条执行路径均不支持(命令注册表仅 6 命令),IllegalStateException。**意外发现①(mkdir 会话泄漏)**:logcat 20:03:32 session/create+rename(title=mkdir)+commands/execute 链在案,**finally 的 deleteSession 静默失败——DSH 无 session.delete 能力位(SessionRow.kt #276 注释同源)→ 临时 mkdir 会话泄漏为正式行**(列表顶位「mkdir·workspace·20:03」,w4-d8-listafter.xml);已按红线③(本测试副产物)归档清理(20:0x,终态主列表复原)。**order**:quick dialog 无任何排序手柄(纯 clickable 行;长按=点击语义,实测长按 workspace 行直接进入新会话=意外发现② blank「无标题会话」(20:05,session/create 20:05:58 在案),同样已归档清理)→ **BLOCKED-feature-absent**(消费链 prior 42ceff63 单测钉死:workspaceIncrementalRemove/Order 合成帧用例绿;#330 在 cascade-fixes 文档分类=非 UIUX 数据/服务层,真机腿 BLOCKED-处置)。**remove**:app 全域无 workspace 移除 UI(客户端仅 workspace/archiveSession RPC;#311 wire 契约 ①-d=workspace.list+connectWorkspace 语义,无 remove 面)→ feature-absent 同类。**main 在场断言 ✔**:清理后 quick dialog 复查=workspace(26)/oc-beacon(1)/外包维权(2)三既有条目全在场,主列表复原首行=父编排会话(D8-quickdialog-entries.png/D8-cleanup-done.png)。落库检查:无 settings/workspace 类写 RPC 发出(目录创建本身失败)

### D9 deliverables/skill 折叠卡(#311)
- 前置:任一含子代理产出的会话(A1 产物)
- 操作:观察子代理产出折叠卡;展开
- 期望:折叠卡呈现可展开;内容可见
- 判定:截图;harness run_code 内联族休眠无产出=BLOCKED-contract(注:与 web 同构,prior 2026-09-05-311 报告)
- 实测记录:**BLOCKED-contract(run_code 内联族休眠,契约性不挂载;skill 卡无场景)**(2026-09-06 20:08-20:15,W4)。主靶=父编排会话 FAB→智能体→AgentSheet(67)→「W1 真机执行会话树域」→该会话内 AgentSheet(3)=**A1 probe r1/r2/r3 spawn 三行树内可见**(与 W1 记录一致)→ 进 r1 子会话:轮次 2(A4 回执)+轮次 1(7h 8m 38s·3 步·2 个工具,派孙代 runId a3048811)转录完整,**两轮末均无「产物」行/文件 chips、无 skill 工具卡**(D9-probe-r1-session/D9-probe-r1-turnend.png/w4-d9-r1.xml)——r1 全程工具=run_code/bash 内联族,无 write/edit/str_replace_editor 写类调用,TurnDeliverables 契约「产出为空不挂载」正确呈现(空态即休眠,prior 2026-09-05-311 报告同判;web mod28 同构)。**skill 折叠卡无场景**:W1-W4 全部观测会话(probe/c1/f1/编排)无 name='skill' 工具调用(斜杠面板 skills 触发组=D5 已验,但会话内 skill 工具调用卡本轮未出现)。**附试(正向渲染腿)**:e2e06-c1 会话(轮次含 essay 文件产出,W3 已删宿主文件)上滑寻「产物」行——写文件轮的可见工具 chips=Run code(bash 族)×2,可见 dump 区无「产物」行(bash 族写不计入 fold=契约正确);轮次 2/6 正文区 V2 markdown dump 失明(§0-11,4 次 dump 逐字节相同),截图存档 D9-c1-turn7/mid.png 判读受执行器无图片输入限制——正向 write 类工具卡渲染由 prior #311 验收覆盖,本 round 无新场景

### D10 待审批琥珀点(#311)
- 前置:存在 pending 审批(依赖受限档)
- 操作:观察行/入口琥珀点
- 期望:琥珀点 #f5c344 在场
- 判定:截图像素;无 pending 场景=BLOCKED-environment(注¹,prior a69fd71a 验收)
- 实测记录:**BLOCKED-environment(注¹)**(2026-09-06 20:16,W4)。当前会话默认权限档=完全访问(B2/W2 已证 PermissionAsked 全程 0;新会话对话框「完全访问」标注在场),pending 审批场景不存在——构造受限档需改宿主 harness 权限规则=红线⑥禁止。列表行扫描(w4-d9-bk2.xml 全量 dump):待批准/待回答/审批/等待类指示 **0 出现**(D10-list-no-pending.png);W3 时代唯一的「待回答」行徽章(C1⑤ blocked 窗口)已于应答后消散。琥珀点 #f5c344 断言指针:docs/acceptance/2026-09-04-308-dsh-approval-wire.md(17✔ 含 A3 危险档双路径/审批卡全链);本 checklist 断言的当前档位语义(完全访问=无审批卡直接执行)已由 B2 ✔ 覆盖

---

## E. 配对与通知域(W5;E3c 放本 wave 最后)

### E1 深链配对预填(#325②)
- 前置:当前服务器条目=2
- 操作:宿主打印并执行(整体单引号包裹避免 & 转义坑;**W5 勘误:参数名=url=(DshPairingParser 契约,endpoint= 会 MISSING_URL)且 token ≥20 字符(短于下限 BAD_TOKEN)**):`adb -s 192.168.110.239:5555 shell 'am start -a android.intent.action.VIEW -d "ocbeacon://pair?url=http://127.0.0.1:3080&token=e2e-dummy-token-06-abcd"' -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity`(**假 token,只测预填+取消**)→ 对话框出现 → **取消**
- 期望:预填对话框首现(endpoint/token 预填可见);取消后无新条目/现有条目无变化
- 判定:截图+服务器条目数仍=2;误新增条目=✘
- 实测记录:**✔(含命令模板勘误两处)**(2026-09-06 20:29-20:32,W5)。**第一跳按 checklist 模板原样执行(整体单引号包裹,URL len=71 完整到达)→ 静默拒绝**:logcat 20:29:56.823 `Pair deep-link rejected: MISSING_URL (host=pair, len=71)`——模板参数名 `endpoint=` 与 app 契约不符(DshPairingParser.kt:102 取 `params["url"]`,dsh-pair.sh 同款);且模板 token `e2e-dummy-token-06` 仅 18 字符 < BARE_TOKEN_REGEX 下限 20(`[A-Za-z0-9_-]{20,}`,DshPairingParser.kt:73)即便换成 url= 也会 BAD_TOKEN。**第二跳按契约正确格式重跑**(`url=`+假 token `e2e-dummy-token-06-abcd` 23 字符):am start → **「添加服务器」对话框首现**(DSH tab 在前,OpenCode|DSH 双 tab),**服务器 URL 字段预填 `http://127.0.0.1:3080` 可见**(E1-pair-dialog.png+dump:EditText text=http://127.0.0.1:3080 @ [192,1049][1008,1349]);**token 无表单字段=by-design**(MainActivity.kt:386-389:深链命中即后台 exchangeToken,对话框只填 URL;logcat 20:30:48.435 `token exchange rejected for http://127.0.0.1:3080: HTTP 401`——假 token 401 预期内,兜底=#317 手动横幅通道;checklist 措辞「endpoint/token 预填可见」与实际交付形态的差异如实记录)。**取消**(取消钮 639,2281)→ 落回服务器管理页,**条目仍=2**(Host-4199+prod-3080 已连接,E1-after-cancel.png)——无误新增/无覆盖 ✓。证据:E1-before-serverpage/E1-pair-dialog/E1-after-cancel.png + logcat Pair deep-link 三行

### E2 sameBackend 共存(#325④)
- 前置:服务器管理页可达
- 操作:观察两 条目(Host-4199/prod-3080)各自地址与状态
- 期望:两 条目共存;prod-3080 已连接;Host-4199 可连接(G4 会真连);无相互覆盖
- 判定:截图;条目异常/地址错乱=✘
- 实测记录:**✔**(2026-09-06 20:32,W5,与 E1 取消后同页观测)。**双条目共存无覆盖**:①Host-4199——http://127.0.0.1:4199·API v2 · 0.0.0-beta-17823·未连接态「连接」钮;②prod-3080——http://127.0.0.1:3080·**已连接**·DSH 徽标·「会话」+「断开连接」双钮;地址各正确无错乱(E2-samebackend.png+W5-serverpage.xml dump:行结构 click 区 [96,1348][591,1492]=会话/[609,1348][1104,1492]=断开)。E1 假配对取消后两者原样=相互零影响 ✓。**附注(意外观测)**:app 另有原生 opencode UI 域的独立服务器注册表——经 18:26 存量连接通知(「已连接到 192.168.110.95:4199」)deep-link 误入时发现其服务器页条目 `192.168.110.95:4199→http://192.168.110.248:4199`(已连接),与 DSH Home 页(2 条目)分属两套 UI/注册表,互不干扰(详见 W5-notes 意外观测 4)

### E3 通知发布/三清除径撤除(#320)
- 前置:app 将退后台;测试会话 E2E06-E3 在设备端就绪
- 操作与期望(逐径):
  - **发布+直达**:设备端在 E2E06-E3 发送「E2E06-E3:回复一句话」→ **立即 HOME** → 等 ≤10s 通知发布(dumpsys 断言,标题正确)→ tap 通知 → 直达会话
  - **a 应答撤除**:与 E4 三段链合并互证(E4 终态断言)
  - **b 轮末消费撤除**:若发布的是队列/轮末类通知,C2 轮末消费完成后 dumpsys 断言已撤除;不可构造此类通知=BLOCKED 记录
  - **c 断开撤除**:制造一条未处理通知(如提问卡未应答时 HOME)→ 服务器页断开 prod-3080 → dumpsys 断言相关通知撤除 → **重连恢复「已连接」**(放 W5 最后执行,断开期间不得执行其他 3080 item)
  - **d 审批处置撤除**:依赖受限权限档=BLOCKED-environment(注¹)
- 判定:各径 dumpsys 前后对比摘录;标题错乱/不撤=✘
- 实测记录:**发布+直达 ✔;b 径=观测记录(队列类通知形态不存在);c 径 ✘(见 E3c);d 径 BLOCKED-environment(注¹)**(2026-09-06 20:33-20:42,W5)。**发布+直达**:新建 e2e06-e3 会话(workspace,模型手选 zai-coding-cn·GLM-5.3-Flash,新会话默认仍 deepseek-official 欠费)发送 `e2e06-e3,reply with one short sentence`(IME 吞空格落地 replywithoneshortsentence,W1 同款)→ **立即 HOME**(20:34:49)→ 轮次 3.6s 完成(20:34:51.724 SessionIdle)→ 首轮 poll ~4.5s 内 **「就绪 · e2e06-e3,replywithoneshortsentence」通知发布**(opencode_tasks 通道,标题正确;notification text=transcript 尾片段「<system-reminder> A skill is…」——text 字段取样含系统注入消息,观测记录)。**直达**:展开 shade → 组栈内该卡在场([210,417][1122,477])→ **tap 卡片主体区 (600,700) → app 打开并直达该会话**(ChatScreen=该会话转录+回复「This appears to be a test ping — ready and standing by.」在场,E3-session-entered/E3-after-tap.png);tap 后 dumpsys **该通知撤除**。**操作坑(MIUI shade)**:tap 标题条带 (666,447)/(600,447) 均不触发 contentIntent(该卡视觉消失但 shade 不关、dumpsys 仍在、activity 不启动);仅卡体区 tap 有效(与 E4② 失败对照,详见 E4)——组子卡可点区=卡体非标题行。**a 应答撤除**:与 E4 三段链互证 ✔(E4③:replyToQuestion success+PendingNotifRevoker Revoked QUESTION+dumpsys 0)。**b 轮末消费撤除(本波补观测)**:严格意义「队列消费触发撤除」的队列类通知在当前构建不存在(W3/C2 交叉:app 前台全程无队列类通知发布)=BLOCKED-无此通知形态注记;**后台轮末完成(就绪)通知保留性时序观测**:第二轮消息 20:40:31.363 发出→HOME→通知在场→**无交互 +30s/+60s 均 count=1(保留)**→**launcher 重进 app+前台查看该会话 ≥30s 仍 count=1(不因查看撤除)**→对照第一轮 tap 后即撤=**撤除仅由通知 tap(autoCancel)触发**;遗留 18:42「就绪 · e2e06-a1-api」通知(从未被 tap)持续在场与本观测一致;终局该遗留通知在 E4 期 resync 中消失(见 W5-notes 时间线)。**操作侧注记**:第二轮消息首跳因输入框坐标盲 tap 落空未发出(logcat 无 Sent prompt),重定位后成功——非 app 缺陷。证据:E3-*.png/xml 6 项+W5-notif-poll/retain* dumpsys 快照 5 份+logcat Sent prompt/Idle 行
  - **c 断开撤除+重连(E3c,本 wave 最后执行)=撤除 ✘/重连 ✔**(2026-09-06 21:03-21:07,W5,录屏 W5-E3c-seg1.mp4)。**未处理通知制造**:e2e06-e5 会话二轮 B1 型提示(e2e06-e3c 前缀,tea/coffee)→ 卡在场(21:04:00.233 Question asked,「Which do you prefer: tea or coffee?」+Tea/Coffee)→ HOME(21:04:10.393)→「问题 · e2e06-e5」通知 count=1 ✓。**断开**:导航 服务器页(列表顶栏返回直达)→ prod-3080「断开连接」(21:05:19.232;logcat `ConnLifecycle: Disconnecting server e86084c4`+`SessionEventHandler: Clearing state(161 sessions)`;UI 已连接→连接钮)。**dumpsys 断言撤除 ✘**:断开后 +4s/+14s/+17s 三采样,**9 条通知全部仍在**(问题·e2e06-e5+错误·hi×7+错误·echo g2-once+组摘要;无任何 cancel 日志)——**源码定音:disconnect() 路径无通知撤除调用**(cancelSessionNotifications 仅 ChatViewModel:382 进会话调用;cancelInteractionNotifications 仅 PendingInteractionNotificationRevoker:111 应答/忽略调用)→ 断开撤除在当前构建缺失。附观测:断开后 ~17s 仍有 L2 stale re-confirm 的 session/list 请求发往 3080。**重连恢复 ✔**:tap 连接(21:06:99.247)→ **3s 内「已连接」恢复**(E3c-after-reconnect.png;条目=2 复原)。**重连期意外**:QuestionAsked SSE 重放 → `buildSessionPath: session 564060d3 not found`(warn,注册表未及水化)→ **Revoker 反将未处理问题通知撤除**(21:06:59.723;问题本身仍 pending)——通知撤除经「重放+路径缺失」失败路径达成而非断开设计;另 resync 新发「就绪 · This is an end-to-end test」+「错误 · e2e06-d6」(旧 Insufficient Balance 轮)。断开期间未执行任何其他 3080 item ✓。证据:W5-notif-e3c-pre/post.txt+E3c-before/after-disconnect/after-reconnect.png+logcat ConnLifecycle/Revoker 行

### E4 退后台补发三段链(#336)
- 前置:app 前台;设备端新建会话发送 B1 同型提示(前缀 E2E06-E4),**等待提问卡在场**(dump/截图确认卡可见=前台抑制期基线)
- 操作:①卡在场时 HOME → 等 3s → dumpsys 查通知(补发) ②tap 通知 → 回到该会话 ③tap 选项应答 → dumpsys 查通知撤除;全程录屏
- 期望:三段全过:前台无通知→后台补发→tap 直达→应答撤除
- 判定:各段 dumpsys 断言+录屏;某段失败=✘
- 实测记录:**①补发 ✔ ②tap 直达 ✘(MIUI shade 组子卡) ③应答撤除 ✔**(2026-09-06 20:44-20:58,W5,录屏 W5-E4-seg1~4.mp4 全程)。**前置**:新建 e2e06-e4 会话(zai flash)发送 B1 同型提示(e2e06-e4 前缀,ask_user_question 显名,下划线经 keycombination SHIFT+MINUS 落地)→ **卡 4.4s 在场**(20:44:30.261 Question asked session-6a746eb5;卡 dump:待你回答+SINGLE+「Apple or banana?」+Apple/Banana+忽略/提交,E4-card-present.xml)。**① 前台抑制期基线→后台补发**:卡前台 dumpsys **无问题通知**(仅就绪 e2e06-e3/a1-api 遗留+组摘要)→ HOME(20:45:24.902)→ +3s dumpsys **「问题 · e2e06-e4,pleasecalltheask_user_questiont」发布**(opencode_questions 通道,importance=4)✓ 前后对照在案(W5-notif-e4-fg-baseline/after-home.txt)。**② tap 通知回会话 ✘**:MIUI shade 通知组(opencode_*)子卡 **tap 不触发 contentIntent**——标题条带 (600,660)/(600,447)、卡体 (600,850)、图标区 (100,655) 多轮尝试均无 activity 启动(焦点恒 NotificationShade/dumpsys 通知仍在/无 autoCancel);**对照:独立卡 tap 有效**(OC Beacon 连接通知卡 tap → app 启动)=shade tap 本身有效,组子卡为特例;E3 成功坐标 (600,700) 在本布局命中下方淘宝广告卡([665,911] 覆盖组卡下段)→**误开淘宝**(录屏在案)。诊断性连接卡 tap 的**连锁副作用**:该通知为 18:26 存量 4199 连接通知 → app 经其 deep-link 进 4199 原生域会话列表 → 恢复触发 prod-3080 全量重同步(**错误通知 flood:「错误 · hi」×7-8+「错误 · 用 bash 执行 echo g2-once」,app 正确 Skip stale idle ×数十;原有问题/就绪通知在 resync 期全部撤除后按事件重发**)→ 冷启(force-stop+am start,COLD 873ms)复位回 DSH Home(条目=2)。**③ 应答撤除 ✔**:导航回 e2e06-e4 会话(卡仍待回答)→ tap Apple→提交 → logcat 20:57:54.192 `replyToQuestion result success=true`+20:57:54.193 `PendingNotifRevoker: Revoked QUESTION notification for session session-6a746eb5` → dumpsys 问题通知 count=0 ✓;**代理复述在场:「You chose apple.」**(E4-final-transcript.png)。注:②失败期间问题通知经 20:53:46/20:56:58/20:57:04 三次重发(resync/冷启重放),最终由 ③ 应答撤除。证据:E4-*.png/xml 8 项+dumpsys 快照 4 份+录屏 4 段(shade 交互全程可人工复盘)

### E5 子会话父槽撤除(#337)
- 前置:设备端会话发送「E2E06-E5:请派一个子代理,让子代理调用 ask_user_question 提问任意问题」→ 等子会话提问(冒泡父槽)
- 操作:HOME 后台 → 父槽通知在场(dumpsys)→ tap 直达 → 应答 → dumpsys 断言父槽通知撤除(无误撤他人/不漏撤)
- 判定:dumpsys 前后对比;模型不派子代理=BLOCKED-model(prior A4 三段链证据)
- 实测记录:**父槽发布 ✔/应答撤除 ✔/tap 直达 ✘(同 E4② shade 组卡)/模型派子代理 ✔(非 BLOCKED-model)**(2026-09-06 20:59-21:03,W5,录屏 W5-E5-seg1.mp4)。**派发**:新建 e2e06-e5 会话(zai flash)发送 `e2e06-e5,please dispatch one subagent and tell the subagent to call the ask_user_question tool to ask me any simple question` → **模型派了子代理**:转录「Dispatched ✅ — one subagent is now running (test id: e2e06-e5)」+「Subagent id: 6f39c58a-e6aa…」+轮次 1 台账(8.9s·3 步·1 个工具)。**冒泡父槽**:21:00:15.218 `Question asked for session 6f39c58a(子) (target=session-564060d3(父))`——父槽语义正确;父会话卡在场(选项 Neither/No preference / something else+输入答案/忽略/提交,E5-card-present.xml)。**HOME→父槽通知**:前台基线 dumpsys e2e06-e5 通知=0(抑制 ✓)→ HOME(21:00:52.720)→ +3s **「问题 · e2e06-e5,pleasedispatchonesubagentandtel」发布**(opencode_questions,W5-notif-e5-fg/after-home.txt 对照)。**tap 直达 ✘**:shade 组子卡 tap 同 E4② 不触发(此轮 (600,700) 命中淘宝广告卡误开淘宝、(600,635) 无效;错误通知 flood 已把组栈挤乱,详见 W5-notes)。**应答撤除 ✔**:app 暖恢复直达会话(卡仍待回答)→ tap No preference→提交 → logcat 21:03:10.583 `replyToQuestion answers=[[Neither]] success=true`+21:03:10.585 **`PendingNotifRevoker: Revoked QUESTION notification for session 6f39c58a(target=session-564060d3)`——父槽通知撤除** → dumpsys count=0 ✓;**无误撤他人**(错误·hi 等 8 条通知在场不动)/不漏撤(e5 问题通知恰撤)。证据:E5-*.png/xml+dumpsys 快照 3 份+录屏 1 段

---

## G. 通用回归域(W6)

### G1 导航回归
- 前置:app 前台任意页
- 操作:列表↔会话↔设置↔诊断往返;BACK 键逐级
- 期望:无死路;BACK 逐级返回;无白屏
- 判定:逐跳截图
- 实测记录:**✔**(2026-09-06 21:19-21:27,W6)。全链往返无死路/无白屏(BACK 逐级实测):①服务器管理页→prod-3080「会话」钮→会话列表(G1-list.xml/png);②列表→进 e2e06-f1 会话→转录完整渲染→**BACK 键回列表 ✓**(G1-session/G1-after-back);③列表底部「设置」tab→服务器域设置页(新会话默认权限/Agent 预设/服务器配置/插件/MCP/标签,G1-settings.png)→**BACK→服务器管理页**(跳过会话列表=返回栈观察记录,非死路);④服务器页顶栏「设置」图标→app 设置页(通用/外观/聊天显示/存储/高级/通知,G1-appsettings*.png);⑤「高级→诊断」→诊断屏开(G1-diagnostics.png)→**BACK→设置页 ✓**;⑥设置页「关闭」(X)→服务器管理页 ✓。导航观察:列表页底部 tab=会话/设置双 tab;W1 已记录的服务器页 BACK=退 app 行为本 wave未复测(会退出 app,非断言面)

### G2 主题切换
- 前置:任页面
- 操作:深色↔浅色切换;重进会话
- 期望:渲染正常无破碎
- 判定:截图两态
- 实测记录:**✔**(2026-09-06 21:27-21:29,W6,app 设置→外观→主题)。原态=系统默认(深色,bg 像素 (16,20,23));「选择主题」对话框(系统默认/浅色/深色三选):**切浅色**→设置页整体 bg (247,250,253) 全量翻转(G2-light.png);**重进会话**(e2e06-f1)渲染正常:用户气泡浅蓝 (198,232,253)+转录文本+轮次台账全渲染无破碎(G2-light-session.xml/png);**切深色**(反向)→bg (16,20,23) 复原(G2-dark.png,对话框「已选择」徽标随迁);**复原系统默认**→bg (16,20,23)+主题值复原(G2-restored.png)。主题设置零残留

### G3 i18n 抽查(3.11★1)
- 前置:无
- 操作:双路:①应用内若有语言设置则切 English 查本批新增文案(队列/反馈/归档/搜索/命令卡相关),查完切回;②宿主跑 `./scripts/i18n-check.sh` 静态检查+抽查 values-zh/values-en 对应本批新键存在
- 期望:①英译在场非 key 裸奔;②脚本 0 error
- 判定:截图+脚本输出;key 裸奔/脚本 error=✘
- 实测记录:**✔ 双路全过**(2026-09-06 21:28-21:33,W6)。**①应用内语言切换路径存在且生效**:app 设置→通用→语言→「选择语言」对话框(系统默认+15 语言)→English→activity 重建(locale list→[en],21:28:44)→全 UI 英文无 key 裸奔,本批新增文案英译逐项在场:Search sessions…(搜索)/Queue (0)+No queued messages(排队队列)/Rate as helpful·Rate as unhelpful(反馈)/Archive·Rename Session·Session Details(归档行菜单)/Fork from this turn(分支)/Turn N · Ns · N steps · N tools(台账)/Ask a question…(composer)(G3-english2/G3-en-list/G3-en-fabmenu/G3-en-queuesheet/G3-en-rowmenu2.png);切回系统默认→中文复原(跨 G4 冷启保持,G4 后 UI=中文)。注:切 English 后首帧 dump 仍中文=重建时滞,~4s 后复查全英文;logcat 同刻 MIUI SettingTrigger NoSuchFieldException=系统噪音非 app。**②宿主静态检查**:`./scripts/i18n-check.sh` **PASSED(867 keys × 14 languages,exit=0)**;本批新键 EN/zh-rCN 双在场:dsh_providers_section_title(values/strings.xml:950 "DSH provider directory"/values-zh-rCN:871 "DSH provider 目录",与 W4-D2 实测 UI 文案一致)、dsh_provider_add_custom(952 "Add custom provider"/873 "新增自定义 provider",=W4 实测 Add 图标 desc)、dsh_provider* 族 23 键两语言全等、queue_* 族 8 键全等(queue_edit/remove/steer/edit_hint/title/empty/steer_unavailable/action_failed,与①在 app 实测文案互证);键集合 EN=zh-rCN=pt-rBR 逐键一致(863 string+4 plurals)

### G4 Host-4199 冒烟(V1V2)
- 前置:tcp:4199 reverse 在
- 操作:`./scripts/debug-entry.sh 192.168.110.239:5555` 冷启直达 → 列表加载 → 开会话发一轮
- 期望:4199 连接正常;基础收发通
- 判定:回复在场截图;连不上=BLOCKED(记录诊断输出)
- 实测记录:**✘(连接+发送 ✔/回复 ✘=4199 服务器模型通道故障,非 app 缺陷;按「只发一轮」纪律一轮即止)**(2026-09-06 21:34-21:37,W6)。`./scripts/debug-entry.sh 192.168.110.239:5555` **成功**:"Debug channel → SessionList for server 6b1cd280"(Host-4199,PID→7568,采集器随换);4199 会话列表加载正常(leo-tkp 工作区 38 会话+旧 smoke 会话,全程未触碰)。新会话首条 `e2e06-g4,reply,with,one,short,sentence`(会话 ses_f89117f6,默认模型 Build·opencode-go·Omen Alpha)→ **发送 ✔**(21:35:36 Busy/Streaming,user 气泡渲染)→ assistant 轮**服务端失败**:session.retry.scheduled ×4(21:35:36-49)→ **session.step.failed provider.transport**(21:36:02.510,error.type=provider.transport);UI 呈现:轮次 1 · 26.5s 台账+错误态卡(dump 失明区,像素证实暗青色 (30,76,98) 错误卡面),无回复正文,会话回 idle(G4-failed.png/G4-reply*.xml)。**复位**:force-stop+am start→COLD TotalTime=870ms→**DSH Home 条目=2 ✓**(Host-4199 已连接成为活动连接/prod-3080 未连接→为 G6 手动重连,终态双已连接;G4-reset-home.png)。G4 会话留存未删(4199 域)

### G5 诊断屏日志
- 前置:app 已使用一段时间
- 操作:进 Diagnostics;检索关键字 `queue` 与 `notification`
- 期望:各至少 1 行匹配;无异常刷屏
- 判定:截图
- 实测记录:**✔**(2026-09-06 21:38-21:41,W6;G4 后新 PID 7491 缓冲)。诊断屏(设置→高级→诊断,级别 chips FATAL/ERROR/WARN/INFO/DEBUG+搜索框,缓冲 1000/1000):**检索 `queue`→9 条可见命中**,全为 WARN「persist queue full, dropped 1150→1500 write requests (Room slower than SSE production)」族(重同步期 Room 持久化背压告警,50 递增,重要观测归 notes);**检索 `notification`→首查 0/1000**(resync 洪流将 21:38:12 PendingNotifRevoker 行逐出 1000 环形缓冲)→以 app 自带「设置→通知→发送测试通知」钮确定性产生日志(AppNotificationMgr INFO 21:40:55「Self-test notification posted on channel opencode_tasks」)→**复检 1/1000 命中 ✓**(G5-search-notif4.png);queue 命中 G5-search-queue.png。测试通知事后经 shade 滑除(posted 10→9,余 9=W5 遗留错误/连接通知)。刷屏评估:重复告警族在场但受控(每屏 ≤9 条同族),无失控刷屏

### G6 未读红点(3.3★4,3.10★4)
- 前置:会话 P 打开中;另一会话 Q(E2E06-G6)就绪
- 操作:在 Q 发送「E2E06-G6:回复一句话」→ **立即切到会话 P**(保持 Q 不在读)→ 等 Q 回复到达 → 列表查 Q 行未读标记 → 点进 Q → 返回列表复查
- 期望:未读标记出现;进入后清除
- 判定:截图前后;标记不出现或不清除=✘(若时序难以构造宿主注入,可用 E 组通知会话的未读态,记录构造路径)
- 实测记录:**✔(未读点进场清除实证;W5 遗留 pending 会话素材腿如实记录)**(2026-09-06 21:42-21:45,W6)。**Leg A(W6 任务书指定素材 e2e06-e5)**:进场前列表行徽章=「待回答」在场 [461,875][564,918],**无未读点**(其 tea/coffee 问题在 W5 期间于会话内已读);进会话→pending 卡完整在场(待你回答+SINGLE+「Which do you prefer: tea or coffee?」+Tea/Coffee+忽略/提交,**全程未应答**)→返回列表→「待回答」徽章保持(问题仍 pending,**已保全供 E4② 人工复验**)(G6-e5-entered/G6-e5-after)。**Leg B(未读点清除语义)**:列表另有 3 行带「有未读消息」a11y 点(e2e06-a1-api 18:42/e2e06-b2 18:34/e2e06-b8 18:22,构造路径=真实未读事件:a1-api 为宿主 API prompt 时 app 在别处,b2/b8 为 W5 resync 洪流标记)→取 e2e06-b2(点 [126,1985][144,2003])→进会话→返回→**该行未读点消失**(a1-api/b8 两点原样不动)=进场清除 ✓(G6-list-before/G6-b2-after 对照)。附观测:e5 会话标题栏「1」数字徽标(待答计数?)在场记录

---

## H. 安全与终局清点(W6)

### H1 崩溃清点(全程,3.1★2★5)
- 前置:各 wave 已按 §0-10 落盘 logcat
- 操作:汇总 `grep -c 'FATAL EXCEPTION' /tmp/e2e-full/W*-logcat.log /tmp/e2e-full/W*-crash.log`;`adb shell dumpsys dropbox --print | grep -cE 'crash|anr'`(限本应用)
- 期望:FATAL=0;ANR=0;dropbox 无本应用新条目
- 判定:计数输出记录;>0=✘(最高严重度)
- 实测记录:**✔ FATAL=0/ANR=0 全程六波**(2026-09-06 21:46-21:48,W6 汇总)。逐文件计数:`grep -c 'FATAL EXCEPTION'` W1-logcat(501,950 行)=0 · W2(224,374)=0 · W3(235,945)=0 · W4(235,437)=0 · W5(368,172)=0 · W6(327,648)=0;ANR(grep 'ANR in |Application Not Responding')六波全 0;crash buffer 文件 W1~W6-crash.log 全部 **0 字节**;W6 AndroidRuntime E 级=0。dropbox(dumpsys dropbox --print):共 12 条,涉本应用 data_app_crash **9 条,全部在回归窗口(2026-09-06 11:14-21:5x)之前**——4 条 2026-09-03 12:41-13:03(OutOfMemoryError/Compose 布局族)+5 条 2026-09-06 04:48-05:20,后者构建 v1788639484/v1788642622 **≠** 本回归实测构建(v1788664302 11:14/v1788688910 18:39),均凌晨旧会话遗留;**窗口内 0 新增**(W6-dropbox-full.txt 存证)

### H2 存储直查(#335 非破坏,3.9★1)
- 前置:无
- 操作:①`adb shell run-as dev.leonardo.ocbeacon.dev ls files/datastore/`;②`./scripts/pull-app-db.sh 192.168.110.239:5555` 拉库后 sqlite3 直查 sessions 与 messages 表行数(记录数值)
- 期望:①无 `*.corrupt-*` 文件;②行数>0 与本测试会话数一致量级
- 判定:文件列表+SQL 计数记录;出现 corrupt=✘(附注:#335 恢复路径已有 3 例单测,prior 0bbe999f)
- 实测记录:**✔ 无 corrupt/库完好**(2026-09-06 21:49-21:51,W6)。①`run-as dev.leonardo.ocbeacon.dev ls files/datastore/`=**仅 opencode_prefs.preferences_pb,无 *.corrupt-***;databases/=ocbeacon.db+-shm+-wal(WAL 三件套正常)+leaks.db(+journal),无 corrupt 件。②`./scripts/pull-app-db.sh 192.168.110.239:5555 /tmp/e2e-full/W6-db`(WAL 三件套+integrity 循环):**首拉即 integrity=ok**;实际 schema 为冷存桶模型(checklist 措辞「sessions/messages 表」对应实际表如下):**cached_sessions=2**(热窗口:G4 的 4199 会话 ses_f89117f6+刚访问的 e2e06-b2 session-dae91814)、**cached_messages=12,764**、cached_parts=11,748、**archive_buckets=684**(设置页显示桶数 680+当日增量)、logs=44,566;cached_messages 按 sessionId 分组全量在库:父编排会话 1000(窗口上限)、全部 E2E06 会话(dae91814=17/455df04a=3/6a746eb5=2/564060d3=10/67b5c44d=7/88b6d0b8=24/89a20513=44/f9aa17ae=15/fbc96c4e=4/e2e06a1top01=3/7ccdfe0f=3/3bb683ff=27)+probe 子代理族(fc307231 系/孙代 a3048811·37b3d8c9·ad08176e 各 1)+E5 子代理 6f39c58a=6——与本测试会话量级一致 ✓

### H3 数据安全终检
- 前置:全部 wave 完成
- 操作:服务器页+工作区+会话列表终查;汇总各 wave notes 的宿主派生会话清单
- 期望:服务器条目=2;main 工作区在;非测试会话未被动;E2E06 测试会话留存清单列出;宿主派生会话(probe-* 等)留存清单列出;可选:清理 /sdcard 录屏残留
- 判定:dump/截图+清单;违任一=✘(最高严重度)
- 实测记录:**✔(含 1 条跨 wave 记录分歧移交主 agent)**(2026-09-06 21:52-22:0x,W6)。**服务器条目=2 ✓**(终态 dump:Host-4199 http://127.0.0.1:4199 已连接+prod-3080 http://127.0.0.1:3080 已连接·DSH,H3-final-serverpage.png;app 终态停服务器管理页,同 W5 惯例)。**main 工作区在场 ✓**:新建会话 quick dialog=workspace(/home/leo-tkp/workspace,29)/oc-beacon(…/mine/oc-beacon,1)/外包维权工作区(2)三既有条目,零测试新增,对话框取消零残留(H3-workspaces.png)。**非测试会话未被动 ✓**:父编排会话行「处理中」原样、9月4/9月5 旧会话全在位;归档区=**6 行全枚举**:mkdir(9月6 20:03)/e2e06-d6(19:53)/hi(9月5 22:20)/count from 1 to 50(9月5 12:38)/根据这个handoff 继续吧(9月4 11:11)/安装这几个插件(9月4 11:04)(H3-arch8/H3-archive-final.png)——**分歧记录:W4-notes 所记「无标题会话(20:05)已归档」未在归档区或主列表预期时间位复现**(W6 只观测不修复,供主 agent 裁量:W4 归档动作可能未生效或该 blank 行已被服务器侧清理)。**E2E06 会话清单+宿主派生留存清单**见 W6-notes §清单。**/sdcard 清理(可选腿,已执行)**:本轮 W1-W6 dump xml ~140 件(w1-*/w2/w3/w4-*/w5.xml/e2e_smoke.xml)全删;W3 录屏 mp4×4(163MB)宿主副本字节级核验在场后删设备副本;保留=主 agent 0906 补测 xml(a2r*/n*/sup*/v*/x*/y*/z*,部分宿主无副本,证据保全)+前批文件(2026-08-20~09-05)+Download 用户个人文件(未触碰);G5 测试通知已撤(posted 10→9,余 9=W5 遗留通知,归 W5 观测 5 族)
