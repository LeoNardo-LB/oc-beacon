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
- 实测记录:(待填)

### C2 队列角标全生命周期(#327)
- 前置:会话 A(E2E06-C2)发长任务 busy 中
- 操作:busy 中同会话再发 2 条 → 角标 1→2;QueueSheet 移除 1 条 → 1;插话 1 条;等轮末消费完
- 期望:角标与队列操作实时一致;轮末消息依次消费无丢失;最终归 0;**轮末消费完成后通知状态(交叉引 E3b 断言)**
- 判定:每步截图/dump+角标数值;角标恒 0 或不更新=✘
- 实测记录:(待填)

### C3 FAB 入口+QueueSheet 三动作(#313)
- 前置:会话列表
- 操作:FAB 展开核对入口清单(对照 `docs/acceptance/2026-09-05-327-queue-badge-chain.md` 记录的入口清单逐个核对并在报告列出);进 QueueSheet 验证三动作(移除/插话/跳转)
- 期望:入口与 prior 清单一致;三动作各自生效
- 判定:截图+逐动作观测记录
- 实测记录:(待填)

### C4 steer vs 排队(#309④)
- 前置:busy 会话(长任务中)
- 操作:①普通发送文本(排队)②若有长按/steer 入口,长按发送
- 期望:排队消息轮末消费;steer 即刻注入(流式中断/转向)
- 判定:两路径 logcat 时间戳可分;无 steer UI=排队部分✔+steer 部分 BLOCKED-feature-absent
- 实测记录:(待填)

### C5 hold→drain 数据完整(#329 平价)
- 前置:C4 的 busy 会话(时序证据复用 C4①,本项只断言数据完整性)
- 操作:busy 中发送消息 M;流式期间记录 M 在转录中不可见(=web 平价,记录非失败);轮末后复查
- 期望:轮末 M 出现且获得回复;无丢失无重复
- 判定:M+回复都在且各一次;丢失/重复=✘(严重)
- 实测记录:(待填)

---

## F. SSE 流式稳定域(W3 后半)

### F1 流式渲染稳定(铁律回归,3.4★3★5)
- 前置:新会话 E2E06-F1
- 操作:录屏 ≥40s;发送「E2E06-F1:请写一篇 600 字短文,分段清楚」
- 期望:流式逐段渲染,无整页闪烁/无长时间静默后爆发
- 判定:录屏抽 ≥5 帧比对(帧间内容递增、无全页白闪重绘)
- 实测记录:(待填)

### F2 滚动自愈(铁律回归,3.8★3)
- 前置:F1 流式中
- 操作:流式中上滚远离底部 2 屏 → 停 5s → 手动滚回底部
- 期望:上滚后不被强制拉底;回底后恢复跟随新内容
- 判定:录屏;持续被拉底=✘
- 实测记录:(待填)

### F3 中断流式(3.5★1)
- 前置:新一轮长回复流式中
- 操作:tap STOP
- 期望:立即停;部分内容保留;会话回 idle;可再发新消息
- 判定:截图+再发一轮往返
- 实测记录:(待填)

---

## D. 搜索/设置/工作区域(W4)

### D1 内容搜索(#322)
- 前置:W1-W3 已创建多个含「E2E06」词的会话(必然满足)
- 操作:搜索「E2E06」→ 查看命中区 → tap 一条命中;再搜无结果词 `zzzqqx`
- 期望:服务器命中区显示(跨会话多条);tap 跳到对应会话/消息;无命中=明确空态
- 判定:截图两态;跳转失败=✘
- 实测记录:(待填)

### D2 提供方页(#324)
- 前置:prod-3080 已连接
- 操作:设置→提供方页;打开「新增」表单→**取消**
- 期望:页打开不崩溃(回归 3cb324a8);列表/目录在场;表单正常开关不落库
- 判定:截图;崩溃=✘(严重);取消后多出条目=✘
- 实测记录:(待填)

### D3 preset 管理(#324)
- 前置:同 D2
- 操作:preset 管理入口;新建表单→取消
- 期望:页/表单在场;取消无残留
- 判定:截图
- 实测记录:(待填)

### D4 插件清单+表单(#324)
- 前置:同 D2
- 操作:插件清单页(只读);任一插件配置表单打开
- 期望:清单呈现;表单字段渲染不崩溃
- 判定:截图
- 实测记录:(待填)

### D5 skills 触发组(#324)
- 前置:同 D2
- 操作:skills 触发组页
- 期望:分组呈现(只读)
- 判定:截图
- 实测记录:(待填)

### D6 归档链(#311)
- 前置:专建测试会话(首条消息「E2E06-D6:标记归档测试」)
- 操作:行菜单/左滑 → 归档 → 查主列表+折叠归档区
- 期望:行从主列表消失;归档区在场可查;**无「取消归档」入口=正确**(契约单向)
- 判定:截图;出现取消归档入口=✘(契约违背)
- 实测记录:(待填)

### D7 多工作区(#311)
- 前置:prod-3080 已连接,当前 main
- 操作:工作区管理;打开新建对话框观察(列表/输入/确认三要素);**取消**
- 期望:对话框正常呈现(现工作区列表+新建入口);取消无残留;主列表无整页闪烁
- 判定:截图
- 实测记录:(待填)

### D8 workspace remove/order(#330)
- 前置:当前=main;**只允许对自建 workspace 做 order/remove,禁触其他**
- 操作:新建工作区 `e2e06ws` → 成功 → 调整 e2e06ws 顺序(order)→ remove e2e06ws
- 期望:新增后列表含新 ws;order 后顺序持久(退出重进仍新序);remove 后消失;main 全程在场
- 判定:每步截图;顺序不持久/remove 失败/main 受影响=✘;结束断言恢复单 main
- 实测记录:(待填)

### D9 deliverables/skill 折叠卡(#311)
- 前置:任一含子代理产出的会话(A1 产物)
- 操作:观察子代理产出折叠卡;展开
- 期望:折叠卡呈现可展开;内容可见
- 判定:截图;harness run_code 内联族休眠无产出=BLOCKED-contract(注:与 web 同构,prior 2026-09-05-311 报告)
- 实测记录:(待填)

### D10 待审批琥珀点(#311)
- 前置:存在 pending 审批(依赖受限档)
- 操作:观察行/入口琥珀点
- 期望:琥珀点 #f5c344 在场
- 判定:截图像素;无 pending 场景=BLOCKED-environment(注¹,prior a69fd71a 验收)
- 实测记录:(待填)

---

## E. 配对与通知域(W5;E3c 放本 wave 最后)

### E1 深链配对预填(#325②)
- 前置:当前服务器条目=2
- 操作:宿主打印并执行(整体单引号包裹避免 & 转义坑):`adb -s 192.168.110.239:5555 shell 'am start -a android.intent.action.VIEW -d "ocbeacon://pair?endpoint=http://127.0.0.1:3080&token=e2e-dummy-token-06"' -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity`(**假 token,只测预填+取消**)→ 对话框出现 → **取消**
- 期望:预填对话框首现(endpoint/token 预填可见);取消后无新条目/现有条目无变化
- 判定:截图+服务器条目数仍=2;误新增条目=✘
- 实测记录:(待填)

### E2 sameBackend 共存(#325④)
- 前置:服务器管理页可达
- 操作:观察两 条目(Host-4199/prod-3080)各自地址与状态
- 期望:两 条目共存;prod-3080 已连接;Host-4199 可连接(G4 会真连);无相互覆盖
- 判定:截图;条目异常/地址错乱=✘
- 实测记录:(待填)

### E3 通知发布/三清除径撤除(#320)
- 前置:app 将退后台;测试会话 E2E06-E3 在设备端就绪
- 操作与期望(逐径):
  - **发布+直达**:设备端在 E2E06-E3 发送「E2E06-E3:回复一句话」→ **立即 HOME** → 等 ≤10s 通知发布(dumpsys 断言,标题正确)→ tap 通知 → 直达会话
  - **a 应答撤除**:与 E4 三段链合并互证(E4 终态断言)
  - **b 轮末消费撤除**:若发布的是队列/轮末类通知,C2 轮末消费完成后 dumpsys 断言已撤除;不可构造此类通知=BLOCKED 记录
  - **c 断开撤除**:制造一条未处理通知(如提问卡未应答时 HOME)→ 服务器页断开 prod-3080 → dumpsys 断言相关通知撤除 → **重连恢复「已连接」**(放 W5 最后执行,断开期间不得执行其他 3080 item)
  - **d 审批处置撤除**:依赖受限权限档=BLOCKED-environment(注¹)
- 判定:各径 dumpsys 前后对比摘录;标题错乱/不撤=✘
- 实测记录:(待填)

### E4 退后台补发三段链(#336)
- 前置:app 前台;设备端新建会话发送 B1 同型提示(前缀 E2E06-E4),**等待提问卡在场**(dump/截图确认卡可见=前台抑制期基线)
- 操作:①卡在场时 HOME → 等 3s → dumpsys 查通知(补发) ②tap 通知 → 回到该会话 ③tap 选项应答 → dumpsys 查通知撤除;全程录屏
- 期望:三段全过:前台无通知→后台补发→tap 直达→应答撤除
- 判定:各段 dumpsys 断言+录屏;某段失败=✘
- 实测记录:(待填)

### E5 子会话父槽撤除(#337)
- 前置:设备端会话发送「E2E06-E5:请派一个子代理,让子代理调用 ask_user_question 提问任意问题」→ 等子会话提问(冒泡父槽)
- 操作:HOME 后台 → 父槽通知在场(dumpsys)→ tap 直达 → 应答 → dumpsys 断言父槽通知撤除(无误撤他人/不漏撤)
- 判定:dumpsys 前后对比;模型不派子代理=BLOCKED-model(prior A4 三段链证据)
- 实测记录:(待填)

---

## G. 通用回归域(W6)

### G1 导航回归
- 前置:app 前台任意页
- 操作:列表↔会话↔设置↔诊断往返;BACK 键逐级
- 期望:无死路;BACK 逐级返回;无白屏
- 判定:逐跳截图
- 实测记录:(待填)

### G2 主题切换
- 前置:任页面
- 操作:深色↔浅色切换;重进会话
- 期望:渲染正常无破碎
- 判定:截图两态
- 实测记录:(待填)

### G3 i18n 抽查(3.11★1)
- 前置:无
- 操作:双路:①应用内若有语言设置则切 English 查本批新增文案(队列/反馈/归档/搜索/命令卡相关),查完切回;②宿主跑 `./scripts/i18n-check.sh` 静态检查+抽查 values-zh/values-en 对应本批新键存在
- 期望:①英译在场非 key 裸奔;②脚本 0 error
- 判定:截图+脚本输出;key 裸奔/脚本 error=✘
- 实测记录:(待填)

### G4 Host-4199 冒烟(V1V2)
- 前置:tcp:4199 reverse 在
- 操作:`./scripts/debug-entry.sh 192.168.110.239:5555` 冷启直达 → 列表加载 → 开会话发一轮
- 期望:4199 连接正常;基础收发通
- 判定:回复在场截图;连不上=BLOCKED(记录诊断输出)
- 实测记录:(待填)

### G5 诊断屏日志
- 前置:app 已使用一段时间
- 操作:进 Diagnostics;检索关键字 `queue` 与 `notification`
- 期望:各至少 1 行匹配;无异常刷屏
- 判定:截图
- 实测记录:(待填)

### G6 未读红点(3.3★4,3.10★4)
- 前置:会话 P 打开中;另一会话 Q(E2E06-G6)就绪
- 操作:在 Q 发送「E2E06-G6:回复一句话」→ **立即切到会话 P**(保持 Q 不在读)→ 等 Q 回复到达 → 列表查 Q 行未读标记 → 点进 Q → 返回列表复查
- 期望:未读标记出现;进入后清除
- 判定:截图前后;标记不出现或不清除=✘(若时序难以构造宿主注入,可用 E 组通知会话的未读态,记录构造路径)
- 实测记录:(待填)

---

## H. 安全与终局清点(W6)

### H1 崩溃清点(全程,3.1★2★5)
- 前置:各 wave 已按 §0-10 落盘 logcat
- 操作:汇总 `grep -c 'FATAL EXCEPTION' /tmp/e2e-full/W*-logcat.log /tmp/e2e-full/W*-crash.log`;`adb shell dumpsys dropbox --print | grep -cE 'crash|anr'`(限本应用)
- 期望:FATAL=0;ANR=0;dropbox 无本应用新条目
- 判定:计数输出记录;>0=✘(最高严重度)
- 实测记录:(待填)

### H2 存储直查(#335 非破坏,3.9★1)
- 前置:无
- 操作:①`adb shell run-as dev.leonardo.ocbeacon.dev ls files/datastore/`;②`./scripts/pull-app-db.sh 192.168.110.239:5555` 拉库后 sqlite3 直查 sessions 与 messages 表行数(记录数值)
- 期望:①无 `*.corrupt-*` 文件;②行数>0 与本测试会话数一致量级
- 判定:文件列表+SQL 计数记录;出现 corrupt=✘(附注:#335 恢复路径已有 3 例单测,prior 0bbe999f)
- 实测记录:(待填)

### H3 数据安全终检
- 前置:全部 wave 完成
- 操作:服务器页+工作区+会话列表终查;汇总各 wave notes 的宿主派生会话清单
- 期望:服务器条目=2;main 工作区在;非测试会话未被动;E2E06 测试会话留存清单列出;宿主派生会话(probe-* 等)留存清单列出;可选:清理 /sdcard 录屏残留
- 判定:dump/截图+清单;违任一=✘(最高严重度)
- 实测记录:(待填)
