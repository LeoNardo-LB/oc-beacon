# 续篇级联修复验收 checklist（#331/#333/#336 真机核心 + #330/#335/#337 单测钉死）

- 卡片：#330/#331/#333/#335/#336/#337 | 日期：2026-09-06
- 构建：含 42ceff63/0bbe999f/279b7639/734fa702/c939355c/b95a2bc9 | 包：`dev.leonardo.ocbeacon.dev`
- 设备：一律 `adb -s 192.168.110.239:5555`；后端=DSH prod-3080（现设备两服务器:Host-4199+prod-3080）
- 分类：#330/#335/#337=非 UIUX 数据/服务层（单测钉死,AI 证据即关卡;真机项按 BLOCKED 处置:生产保护/破坏性/场景构造）;#331/#333/#336=用户可见行为（真机核心三验）。**六小卡合一报告的偏离说明**:续篇级联批为同批小修,逐卡证据分节独立,验收面合并（方针 §0 逐卡串行在实现阶段已守）
- 回归域：会话列表域（#331 顶位/fork 行/排序不回归）· 转录渲染域（#333 旧会话自愈不破坏常规加载）· 通知域（#336 补发不破坏到达时撤发链）· composer/常规收发（C1）
- 证据目录：`/tmp/accC_logs/`、`/tmp/accC_shots/`

## 执行纪律（同前：一次一项/逐项记录/禁止分析/dump 定位/%s 转义/单次 bash ≤100s;清稿用全选替换法——方针自动化输入坑节）

## 前置
### P0 设备构建核对
- 前置：主 agent 已装
- 操作：adb devices;dumpsys versionName/lastUpdateTime
- 期望：versionName=0.3.0、lastUpdateTime=2026-09-06 09:04:52（主 agent 已装六修构建并核实——不符 ✘ 停卡）
- 判定：输出记录
- 实测记录：✔ 通过（2026-09-06 验收会话）`adb devices` 显示 `192.168.110.239:5555 device`（唯一在线设备）；dumpsys：`versionName=0.3.0`、`lastUpdateTime=2026-09-06 09:04:52`（另 firstInstallTime=2026-09-06 05:13:29）——与期望完全一致,继续 P1
### P1 连接+基础
- 前置：P0
- 操作：reverse tcp:3080;冷启→prod-3080 连接→会话列表 dump 基线（记录列表首行时间戳排序）
- 期望：连接正常,列表按 updated 倒序
- 判定：dump 正常+FATAL=0
- 实测记录：✔ 通过（2026-09-06 09:11-09:13）`reverse --list` 含 `tcp:3080 tcp:3080` ✔。force-stop 冷启（monkey 误开 LeakCanary 页,am start 回主界面后在服务器页核验：**prod-3080 · http://127.0.0.1:3080 · 已连接 · DSH** ✔,未走 debug-entry.sh 避 4199 重指坑）。点「会话」进列表,dump 基线存 `/tmp/accC_shots/P1_list_baseline.xml`：首行起时间戳 `9月 6, 07:21 → 06:16 → 05:04 → 03:07 → 01:20 → 01:15 → 9月 5, 22:58 → 22:54 → 18:13` 严格倒序 ✔。FATAL=0 ✔。基线列表可见候选只读档行「用 bash 执行 echo g2-once」（9月 5, 22:54）

## A. 真机核心三验
### A1 #331 新建会话行即时顶位
- 前置：P1
- 操作：新建会话（workspace）→ 发 hi 建立会话 → 立即 BACK 回列表 → 5s 内 dump 列表
- 期望：新会话行**即时在列表顶位**（不沉底;≤5s 可见——added 帧 updatedAt 透传+echo 本地时钟）
- 判定：dump 首行=新会话（记录行位置与时间戳）
- 实测记录：✔ 通过（09:13-09:16）新建会话（workspace 弹层选 `workspace`）→标准模式→输入框打 `hi` 发送→回复轮次完成（首发曾弹「选择模型」sheet,关闭后消息已正常发出,「轮次 1 · 完结」在转录区）→BACK 回列表 4s 内 dump（`/tmp/accC_shots/A1_list_top.xml`+png）：**首行=新会话「hi」（9月 6, 09:13,行 bounds [168,573]）即时顶位** ✔,原顶位行（handoff 继续,07:21）退居第二,其余序不变 ✔。FATAL=0 ✔
### A2 #331 fork 子会话行即时可见
- 前置：A1 会话有 ≥2 完结轮——**补轮**:A1 后再发 `second%sround%sdone` 得回复
- 操作：台账行展开→「从此轮分支」→ 导航进子会话后 BACK 回列表 → 5s 内 dump
- 期望：fork 子会话行**即时在场**（origin 判别:parentId 不再误滤普通 fork 子会话行）
- 判定：dump 行在场
- 实测记录：✘ 即时性未达期望（09:14-09:26）补轮：发 `second round done` 得回复——生产 agent 工具链超长（Get goal/Job list/List agents 等 8 工具,5m+ 仍流式）,tap「停止」中断（轮次 2 · 5m13s · 7 步 · 8 工具,回复文本在场,「得回复」满足;composer 上方出现后端「Insufficient Balance」提示,记录备查）。fork：轮次 1 台账展开→「从此轮分支」→POST /api/session/fork 成功,logcat `Forked session-acad04ab→session-b0aaa1aa`+`SessionCreated→SessionEventHandler`(09:20:09.936)✔,并已导航进子会话（转录=轮次 1 分支历史,轮次 1 台账 0ms/1步）。BACK 回列表（经父会话中转,两次 BACK）5s 内 dump（`/tmp/accC_shots/A2_list_after_fork.xml`）：**fork 子会话行不在列表**（首行=父会话 hi·09:14,共 10 行无 fork 行）——距 fork 已数分钟,SessionCreated 事件已 dispatch 但列表 UI 未加行。退出列表→冷重进 dump（`A2_list_refreshed.xml`）：**fork 子会话行在场且顶位**（hi·workspace·9月 6, 09:20,首位;父会话 09:14 退居第二,排序正确）。结论:parentId 误滤已不再现（冷加载行在场,不被滤）✔,但「回列表 5s 内即时在场」未达成 ✘——即时增量未进列表 UI,需冷刷新才可见。FATAL=0
### A2-r2 #331 fork 子会话行即时可见（d1e7dbf3 重验,2026-09-06 10:15-10:20）
- 前置：A2 残余已修（d1e7dbf3 排序位单调防线——follow 重放腿拉不回排序位）;新构建已装,指纹核对通过才开跑
- 操作：同 A2（指纹→prod-3080 连接→多轮会话→台账「从此轮分支」→进子会话→BACK 回列表→5s 内 dump）
- 期望：fork 子会话行**即时在场且顶位**（排序位不再被重放回拉）
- 判定：dump 行在场+位置（记录时间戳排序）
- 实测记录：✘ 即时性仍未达（10:15-10:20,纯净重验会话）指纹 ✔ versionName=0.3.0 · lastUpdateTime=2026-09-06 10:15:10 完全一致;reverse tcp:3080 在列,冷启后服务器页 prod-3080 · http://127.0.0.1:3080 · 已连接 · DSH ✔（未走 debug-entry.sh,避 4199 重指坑）。fork：09:14 父会话「hi」（轮次 1 · 0ms/1 步 + 轮次 2 · 1h1m23s/10 步/8 工具,「second round done」在场）轮次 1 台账展开→「从此轮分支」→POST /api/session/fork 成功（10:17:50.977）,logcat Forked session-acad04ab@seq-167→session-083202a4 + SessionCreated→SessionEventHandler sid=session-0832（10:17:51.026/040）✔,已导航进子会话（转录=轮次 1 分支历史,轮次 1 台账 0ms/1 步）。BACK①落父会话（dump 门卫）,BACK②落列表（10:18:24.0）,5s 内 dump（完成 10:18:29.0,/tmp/accC_shots/a2r2_list_after_back.xml）：fork 子会话行不在列表——可见序 07:21→09:42→09:26→09:14 父→09:20 旧fork→06:16→05:04,与 fork 前基线逐行一致;+32s 复查（10:18:56,a2r2_list_late.xml）仍不在场。退出列表→冷重进 dump（10:19:34,a2r2_list_refreshed.xml+png）：fork 子会话行在场且顶位（hi·workspace·9月 6, 10:17 首位;07:21 handoff 退居第二,10:17>07:21>09:42>… 严格倒序）——冷加载排序位未被回拉 ✔。结论:d1e7dbf3 后「回列表 5s 内即时在场」仍未达成 ✘（added 帧仍未即时进列表 UI,形态同 A2 残余,需冷刷新）;冷加载行在场+顶位 ✔。FATAL=0 · E/AndroidRuntime=0 ✔。证据:/tmp/accC_logs/a2r2.log + a2r2_full.log · /tmp/accC_shots/a2r2_*（11 文件）
### A2-r3 #331 fork 子会话行即时可见（插桩终局诊修,2026-09-06 10:28-10:59）
- 前置：A2-r2 残余（数据层已绿但视口不见）;方法论=#308 先例插桩回路——四 seam 探针（[DEBUG-a2],AppLogger.i）①SessionEventHandler fold 后 ②getSessionsFlow 发射者 ③SessionListViewModel combine 输入 ④SessionListScreen contentState 行数,外加 seam0（orchestrator 防御前后/backfill 裸腿/fork 回执行）
- 探针数据（/tmp/a2r3_probe2.log,fork sid=…ca4dca89@10:34:17）:**四 seam 全绿**——seam0 register+receipt(parentId=null,echoClock)→seam1 created-fold store=128 行在场 parent=null upd=echoClock→seam2 emit=true store=true map=true（127→128）→seam3 vm-data in=true map=true→seam4 ui-rows=23 head=[ca4dca89@…] shown=true（10:34:29.082）。即数据/状态层完全在场,contentState 列头位
- 断点：屏幕实况（dump 10:34:43/10:35:28/10:37）首行=旧 fork(10:31),新行不在视口;pull-refresh 或 scroll-to-top **立即**显示新行于首位——行一直在 LazyColumn 里,位于视口锚点之上
- 根因（UI 滚动锚定竞态,非数据层）:用户在 ChatScreen 期间行被前插;LazyColumn(rememberLazyListState) 滚动位按 key 锚定在离开时的旧首行;返回时 ON_RESUME 的 scrollToItem(0) 先于 WhileSubscribed5s 重订阅新状态（实测 ~360ms）执行,随后数据变更重布局把锚点拉回旧头——新行永远在锚点之上、视口冻结。第一版修复（首可见 key==旧 head 才揭示）在真机复核中暴露**重布局中途可见键抖动**（复核日志 first=新行/idx=1 瞬态）而漏判
- 修复（终版,commit 见 git log）:SessionTreeList 前插揭示——head id 变化 + 组合期读取 firstVisibleItemIndex==0（前次布局稳定值,无竞态）→ scrollToItem(0);prev 用 remember（返回后首帧重新播种防误判）;中部浏览不拉动视口。裁决函数 shouldRevealPrependedHead 抽出单测（SessionListPrependRevealTest 6 用例,含设备场景还原/重布局竞态形态/初见/无变化/中部/空树,TDD 红→绿）
- 探针零残留:grep 'DEBUG-a2|DebugA2Probe|A2FIX' app/src → 零命中（探针文件已删,证据留档 /tmp/a2r3_probe.log·a2r3_probe2.log·a2r3_fix.log·a2r3_fix2.log）
- 复验（终版干净构建,lastUpdateTime=2026-09-06 10:57:52,探针已全撤）:**✔ 连续 3 次**——①10:54:44（fork=…f17e1fea）BACK2 后 ~2s dump 首行=10:54 行 ②10:55（fork-of-fork）同窗口首行=10:55 行 ③终验 10:58:44 fork→10:58:52 BACK2→10:58:57 dump 首行=10:58 行（5s 窗口内）。冷载顶位与排序全程正常,FATAL=0
- 附注:静态测试(DshForkRowListVisibilityTest)绿/设备 ✘ 分歧由此解释——它只覆盖 fold+filter 数据层,LazyColumn 锚定属 UI 时序现象（V6 域）,单测无法覆盖,以真机复验为准

### A3 #333 旧会话重进转录自愈
- 前置：存在旧会话（9月4 会话,35h+——P1 列表基线找）
- 操作：进该旧会话 → 30s 内分段 dump 转录区
- 期望：转录**渲染**（follow snapshot 重建,不再空白多帧;35h 窗口外会话也恢复）
- 判定：dump 消息节点 ≥1（记录条数与时间戳）
- 实测记录：✔ 通过（09:27-09:29）列表滚动定位旧会话「显示磁盘空间不足，请看看是」· /home/leo-tkp/workspace · **9月 4, 12:53（44h+,轮次台账时长标记 44h 29m 23s,远超 35h 窗口）**。tap 进入后 30s 内两段 dump（`/tmp/accC_shots/A3_transcript_t0.xml`@~6s、`_t1.xml`@~20s）：**转录完整渲染**——assistant 正文（磁盘空间分析）、Code block（bash 清理脚本）、建议 1/2、轮次台账「轮次 1 · 44h 29m 23s · 11 步 · 10 个工具」全部在场,t0/t1 各 **32 个文本/描述节点**,两段一致无空白帧。消息节点 ≥1 ✔。logcat 佐证重建链路：`ChatRepository [seed] no cache, waiting for REST`→`MsgEventHandler [skeleton] orphan part host missing -> seeded assistant skeleton（step.started 丢失自愈）×4`（follow snapshot 重建痕迹）。FATAL=0 ✔
### A4 #336 前台挂起→退后台补发通知
- 前置：只读档会话（g2-once 或新建切只读）
- 操作：该会话发 `run%sbash%stouch%s/tmp/accC_catch` → 卡挂起且 app 前台本会话 → 3s dumpsys（`adb shell dumpsys notification --noredact | grep -A4 dev.leonardo.ocbeacon.dev`——应无审批通知,到达时抑制）→ **HOME** → 3s dumpsys 同过滤 → **补发通知在场** → 面板点按进会话 → 应答 → 回 HOME dumpsys 同过滤复查
- 期望：退后台后补发通知出现（catch-up 语义）;应答后撤销
- 判定：三段 dumpsys 证据链（前台无/后台补发在场/应答后撤）
- 实测记录：✔ 通过（09:26-09:38,三段证据链完整;过程含两处环境前置修复,详述如下）
  - **只读档**：复用 g2-once（权限药丸=只读 ✔,9月5 22:54 行）。首发 `run bash touch /tmp/accC_catch`（input text+%s 转义,斜杠正常）未挂起——该会话默认 deepseek-official·DeepSeek-V4-Flash 通道「Insufficient Balance」（配额耗尽,轮次直接结束）;composer 模型药丸切 **zai-coding-cn·GLM-5.3-Flash** 后重发 → PermissionAsked 09:36:05 到达（sid=session-07ef）,审批卡在场（需要权限/拒绝/仅一次/始终允许）,composer=停止（挂起）✔
  - **⚠️ 环境前置修复 1（通知权限）**：首轮 HOME 后 dumpsys 无 dev 包通知,取证发现 `POST_NOTIFICATIONS: granted=false` + appops `POST_NOTIFICATION: ignore`（MIUI 默认拒,通知被系统静默丢弃）——已 `pm grant` + `appops set allow` 修复后重测
  - **⚠️ 边界事实**：app 侧 catch-up 补发是**每个 PermissionAsked 一次性的**（首次 re-dispatch 09:27:23 在权限缺失下消耗,post 被系统拒;后续前台↔后台循环与冷启均不再重发该 id）。为获得干净链路:g2-once 挂起审批点「拒绝」（reply=reject success=true）→ 按 A4 前置备选**新建只读会话**（药丸「完全访问」→选「只读」;模型切 GLM-5.3-Flash）→ 发同消息 → 新 PermissionAsked（id=0eaa9e56）挂起。期间一次 am start 暖恢复出现 Compose 空白页（恢复竞态）→ force-stop 冷启恢复,FATAL=0
  - **段1 前台 ✔**：app 前台本会话挂起,dumpsys（`A4v2_fg.txt`）dev 包 **opencode_permissions 频道零记录**（到达时抑制;在场仅 tasks/connection 类）
  - **段2 后台补发 ✔**：HOME→3s dumpsys（`A4v2_bg.txt`）：**`NotificationRecord … channel=opencode_permissions importance=4` 在场**,logcat `PendingBgNotifier: Background re-dispatch APPROVAL notification`（09:36:19.610）;通知面板实测可见「**权限 · run bash touch /tmp/accC_catch**」（OC Beacon Dev 分组）
  - **段3 点按+应答+撤销 ✔**：面板点按通知→直达挂起会话（审批卡在场）→「仅一次」应答（`replyToPermission reply=once success=true`）→回 HOME dumpsys（`A4v2_after_home.txt`）：**opencode_permissions 零记录（已撤）**,余 tasks×5+tasks_silent×1+connection×1
  - 截图存档：`/tmp/accC_shots/A4v2_fg.png`/`A4v2_bg.png`/`A4v2_tapnotif.png`;日志 `/tmp/accC_logs/`

## B. 单测钉死项（处置即记录）
### B1 #330/#335/#337 单测实跑取证
- 前置：A 组完成（设备操作完毕后再跑 gradle——串行安全）
- 操作：**宿主机实跑** `./gradlew :app:testDevDebugUnitTest --rerun 2>&1 | tail -3`;grep 测试报告确认三卡关键用例绿（#330 workspaceIncrementalRemove/Order_synthesizes+store applyRemove/applyOrder;#335 PreferencesCorruptionRecoveryTest 3;#337 revoker 矩阵含 child session answered/deletion cascade 两测）
- 期望：全量 0 失败+三卡用例名在报告中在场
- 判定：实跑输出+关键用例名记录在案 → ✔（BLOCKED-真机处置:#330 生产保护不删真 workspace/#335 破坏性不腐蚀设备配置/#337 子会话挂起审批场景构造依赖多客户端）
- 实测记录：✔ 通过（09:38-09:41,A 组完成后串行实跑）宿主机 `./gradlew :app:testDevDebugUnitTest --rerun` → **BUILD SUCCESSFUL in 1m 51s**（33 actionable tasks）;测试报告 XML 汇总：**314 文件 · 3108 tests · failures=0 · skipped=0**。三卡关键用例 grep 全部在场且绿：
  - **#330** `DshRemoteMuxEngineTest`（26/26 绿）含 `workspaceIncrementalRemove_synthesizesWorkspaceRemoveFrame`、`workspaceIncrementalOrder_synthesizesWorkspaceOrderFrame`;`DshWorkspaceStoreTest`（8/8 绿）含 `applyRemove deletes row by workspaceId…`、`applyOrder reorders by frame order…`（另见 `emit_apiSessionAdded_forwardsUpdatedAtAndOrigin` 在场——#331 关联锚点）
  - **#335** `PreferencesCorruptionRecoveryTest`（3/3 绿）:`recover tolerates unknown file location without crash`、`recover tolerates archive failure and still resets`、`recover archives corrupt file copy and returns empty preferences`
  - **#337** `PendingInteractionNotificationRevokerTest`（14/14 绿,revoker 矩阵）含 **`child session answered revokes parent slot notification`**、**`child session deletion cascade revokes parent slot notification`**、`session deletion cascade cancels pending kind notification`

## C. 回归
### C1 健康
- 前置：B1
- 操作：普通收发一轮（消息域+流式）;进出×2（导航域）;logcat 总检
- 期望：常规收发/导航正常,六修零回归
- 判定：FATAL=0
- 实测记录：✔ 通过（09:38-09:42）**消息域**：进 session-07ef（首行,处理中+待批准——touch 轮在「仅一次」后 agent 又发起新审批,点「拒绝」收尾）→ 轮次落定（轮次 1 · 6m28s · 10 步 · 8 工具,含 touch 三段式失败总结:read-only 只读文件系统→workspace-write 沙箱丢弃→danger-full-access 被拒）→ 发 `hello` → 回复流式渲染完成（「Otherwise, what can I help you with?」+评价行+轮次 2 台账 3.3s · 2 步,完结统计落定）✔。**导航域**：进出×2 全部落点正确（exit1/enter2/exit2 dump 在案:BACK→列表(搜索会话在场)→tap 首行→会话(标题+两轮台账在场)→BACK→列表）✔。**logcat 总检**（`/tmp/accC_logs/C1_full.log`,37921 行）:FATAL=0 ✔,ANR=0 ✔（AndroidRuntime 72 行均为 uiautomator dump 进程自身启动日志,uid 2000,非 app 崩溃）;reverse tcp:3080 全程在线。六修零回归 ✔

## 执行汇总（2026-09-06 09:11-09:42,纯净执行会话）

| 项 | 卡 | 判定 | 核心证据 |
|---|---|---|---|
| P0 | 构建 | ✔ | versionName=0.3.0 · lastUpdateTime=2026-09-06 09:04:52 完全匹配 |
| P1 | 连接 | ✔ | reverse tcp:3080 在列;服务器页 prod-3080·已连接·DSH;列表基线严格倒序;FATAL=0 |
| A1 | #331 | ✔ | 新建会话（hi）BACK 后 4s 内 dump:首行即时顶位（9月6 09:13）,原顶位退居第二 |
| A2 | #331 | **✘ 部分** | fork 创建成功+SessionCreated 已 dispatch（logcat 09:20:09）,但回列表后数分钟内行不在场;冷重进列表后行在场且顶位（09:20>09:14 父会话,排序正确）——parentId 误滤不复现,「回列表 5s 内即时在场」未达 |
| A3 | #333 | ✔ | 44.5h 旧会话（9月4 12:53）重进转录完整渲染,t0/t1 两段各 32 节点一致;logcat 有 seed→skeleton 自愈链 |
| A4 | #336 | ✔ | 三段链完整:前台挂起 dumpsys 零 permissions 通知（抑制）→HOME 3s 后 opencode_permissions 通知在场+`Background re-dispatch APPROVAL` 日志→面板点按直达会话→「仅一次」应答（reply=once success）→回 HOME 通知已撤 |
| B1 | #330/#335/#337 | ✔ | BUILD SUCCESSFUL 1m51s;3108 tests/0 失败/0 跳过;三卡关键用例名全绿（mux 26/26·store 8/8·PreferencesCorruptionRecovery 3/3·revoker 14/14 含 child session answered/deletion cascade） |
| C1 | 回归 | ✔ | hello 收发+流式完成;进出×2 落点全对;FATAL=0·ANR=0;reverse 全程在线 |

**执行备注（环境/边界事实,非产品缺陷结论）**
1. dev 包 `POST_NOTIFICATIONS` 初始 granted=false+appops ignore（MIUI 默认拒,通知被系统静默丢弃）——已 `pm grant`+`appops set allow` 修复后完成 A4 三段取证;**若复测通知类行为需先核对权限状态**
2. A4 首个 PermissionAsked 的补发机会在权限缺失下被消耗（app 侧 re-dispatch 一次性,post 被系统拒后不再重试,冷启亦不重发）——最终以新建只读会话构造新事件完成链路;g2-once 原审批点「拒绝」收尾
3. 设备上 deepseek-official 模型通道「Insufficient Balance」（配额耗尽）,A4/C1 均切 zai-coding-cn·GLM-5.3-Flash 完成
4. 两次 am start 暖恢复落在服务器页/一次 Compose 空白页（恢复竞态,force-stop 冷启恢复,FATAL=0）——BACK 语义:会话页一次 BACK 落父会话/列表不定,导航用 dump 门卫
5. A2 即时性缺陷或与「列表增量未消费 SessionCreated」相关,证据已存档（`/tmp/accC_shots/A2_list_after_fork.xml` vs `A2_list_refreshed.xml`,logcat `b0aa` 全链）,待主 agent 定性

**证据目录**:`/tmp/accC_logs/`（P1/A1/A2/A3/C1_full 等日志）·`/tmp/accC_shots/`（P1/A1/A2/A3/A4v2_*/C1_* dump+png）·测试报告 `app/build/test-results/testDevDebugUnitTest/`

**总体结论**:8 项中 7 项 ✔ 通过;A2（#331 fork 子会话行即时在场）即时性未达期望——冷刷新后行在场排序正确,parentId 误滤修复本身生效,但 added 帧未即时进列表 UI,建议主 agent 结合 `/tmp` 存档证据与 #330 mux 层 `emit_apiSessionAdded_*` 单测（绿）定位列表消费端。
