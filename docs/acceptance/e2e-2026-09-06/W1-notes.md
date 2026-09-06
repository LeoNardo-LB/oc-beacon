# W1 波次摘要(会话树/列表/导航域)——2026-09-06 11:27-13:26

- 执行者:纯净上下文 W1 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(versionCode 1788664302,lastUpdateTime 2026-09-06 11:14:11)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W1-logcat.log(501,950 行;PID 6872→9871→14575 两次重启采集);crash buffer /tmp/e2e-full/W1-crash.log(0 字节);FATAL EXCEPTION=0

## A 组终态统计

| item | 卡 | 终态 | 一句话 |
|---|---|---|---|
| A1 前插揭示 | #331 | **BLOCKED**(机制×设计) | 宿主派生 probe 会话按官方同构设计被主列表过滤(SessionListStateBuilder parentId==null),0/3 在场(即时/重进/pull-refresh 均);fork-of-fork 宿主侧 3/3 成功;树内可见性由 A4 ✔ 覆盖 |
| A2 排序+时间戳 | #331 | **✘** | 11:44:39 发送+回复完成,11:45:42 重取+11:46:05 观测(86s)行未升顶/时间戳仍 10:54;~11:50 无新请求下缓存更新才升顶,时间戳值=11:44 精确;轮次完成后无 SessionUpdated SSE |
| A3 旧会话转录+翻页 | #333 | **✔** | 37h 最旧会话(9月4 22:44)转录全渲染;2 次上滑 bounds 全变+更早内容入镜;回底「落 P035」逐位复原 [84,938][713,1082] |
| A4 树挂载+续聊 | #310① | **✔** | AgentSheet 三级树(probe r1/r2/r3 挂 W1 下,孙代 Count to five 可见);fork 行普通呈现不误挂;r1 子会话续聊往返+宿主代理实收确认 |
| A5 fork atSeq | #312⑤ | **✔** | 11:53:02 fork 成功(seq-167→7ccdfe0f),转录从轮 1 起,分支后新对话往返 ✓ |
| A6 @会话源引用 | #310⑤ | **✔** | 候选面板弹(会话行+同工作区);mention 规范串插入;消息带 referenced-sessions 块;回复实质复述被引会话转录 |
| A7 文件候选 | #321 | **✔** | @ 面板文件候选非空(config/dsh-config-backup-20260904/dsh-keepalive);单面板混合两类无 tab;无崩溃 |
| A8 冷启持久 | 3.1★1 | **✔** | COLD TotalTime=876ms;服务器 2 条目+prod-3080 已连接(cookie 持久);wave 会话+转录完整无丢失 |

## 环境异常与处置

1. **prod-3080 冷启 401→重新配对(11:28-11:30)**:dsh web 服务器 11:08 重启(web.log mtime)→launch token 轮换→app 冷启探测 401 TokenNeeded「waiting for token input」。处置:按 scripts/dsh-pair.sh USB 通道等价手动执行——token 从 ~/.local/state/dsh-web/web.log 提取(只读),force-stop 后 am start 带 debug_url http://127.0.0.1:3080 + debug_server_type dsh + **debug_name "prod-3080"(保名,防 sameBackend 去重改名)** + debug_token;11:30:41 exchange ok。**服务器条目保持=2**(sameBackend 按 type+url 去重复用既有条目,MainActivity activateDebugProfile 语义)。checklist「已连接」前置自此恢复;红线⑥未触(未改任何宿主配置)。
2. **打字环境限制(全程)**:设备 IME=豆包输入法(唯一),①跨 adb 连接的按键序列被丢(每字符独立 adb shell 只落地首字符)→ 改单连接批 keyevent(type4.sh);②大写/SHIFT 组合在部分状态下触发 IME 异常(吞键)→ E2E06 前缀以小写 ASCII 落地(e2e06-xx,可识别性保持,红线④目的达成);③空格被 IME 拼音 commit 吞→消息文本无空格(逗号分隔);④@(SHIFT+2)在干净状态下单发可用。type.sh(仓库版)在本机不可用于大写/连串场景,已备档 /tmp/e2e-full/type4.sh。
3. **模型通道**:deepseek-official「Insufficient Balance」(环境事实③ 同款);全部测试会话手选 zai-coding-cn·GLM-5.3-Flash(选择器需滚 2 屏);A4 子会话沿用其 DeepSeek-V4-Flash 药丸发送成功(该会话路由可用,未深究)。
4. **每次冷启弹「电池限制已启用」警告横幅**(MIUI 优化提示,非本批功能);首次冷启亦见「正在连接…」对话框(401 期间)。
5. **执行器侧**:glm-5.3 无图片输入能力→截图仅存档,判定走 dump/logcat/像素脚本;run_code 工具调用约 1/6 概率报 invalid arguments(重试即过,无实质影响);父 agent 12:1x 进度核查介入一次(时延补记:彼时 A6 进行中,证据文件停在 12:12 属 A6 回复读屏阶段,非卡点)。

## 意外观测(非缺陷结论,供后续 wave/主 agent 裁量)

1. **A2 升顶滞后形态**:首次 BACK 观测窗(86s)行未升顶,而后续无 session/list 请求下缓存自行更新——事件来源存疑(无 9c6e SessionUpdated SSE;服务器 session/list 11:45:42 响应仍旧)。与 prior cascade A2「即时增量未进列表 UI」同族但更缓和(无需冷重进)。
2. **子会话内 @ 候选被拒**:「session … is owned by subagent routing」(12:08:56)——@ 引用仅在顶层会话可用,子会话静默无面板(warn 级日志);A6 已按顶层执行。
3. **BACK 语义**:会话页一次 BACK 可落父会话(fork→父)或列表(不定);服务器页 BACK=退出 app;launcher 多按一次 BACK 会误开桌面快捷菜单(已恢复)。
4. **冷启落点**:force-stop 后 am start 落服务器管理页(非会话列表);需经 prod-3080 行「会话」钮进入列表(该钮与「断开连接」并排——后者红线危险区,操作时已避开)。
5. **主列表排序位 vs 行时间戳可短暂不同步**(A8 冷载首行=父编排会话而其时间戳显示 11:08)——A2 症状族的另一表现,记录备查。
6. AgentSheet 根层 63 条=父编排会话全部历史子代理(含前批验收代理),W1 展开链正常;「W1 真机执行会话树域」节点运行中态=ProgressBar。

## 宿主派生会话留存清单(红线⑤,H3 归入「宿主派生留存」)

| 会话(标题特征) | 创建时间 | 身份 | 备注 |
|---|---|---|---|
| probe-a1-r1-1788665567:请派一个子代理数到 5 然后结束 | 2026-09-06 11:32:27 | 本 wave agent 子代理(fc307231-0c77-4140-a6cf-af0738eaa86c) | 树内名「A1 probe r1 spawn」;12:0x 收到 e2e06-a4 续聊并回执 |
| (孙代)Count to five | 11:32:3x | r1 派生(a3048811-0ac5-4be1-8cd2-7e2028fc49be) | 数到 5 结束 |
| probe-a1-r2-1788665757:… | 11:35:57 | 子代理(6e91ed51-8b9c-4a15-91de-55877facb705) | 树内名「A1 probe r2 spawn」 |
| (孙代)数到 5 | 11:36:2x | r2 派生(37b3d8c9-92b3-4ba6-80a9-390abba06e9a) | |
| probe-a1-r3-1788665795:… | 11:36:35 | 子代理(9b4b6d65-c59e-4539-be6a-75149881bf09) | 树内名「A1 probe r3 spawn」 |
| (孙代)数到 5 | 11:36:4x | r3 派生(ad08176e-b98e-4327-84c5-93ac6b0159d7) | |

- 另:本 wave agent 自身会话(树内「W1 真机执行会话树域」)及其父(编排会话)为 harness 常驻,非测试创建,不动。
- 全部留存未删(红线⑤);logcat durable subagent 行 6 条在案(11:33:03-11:36:58)。

## 设备端测试会话(W1 创建/参与,供 H3)

| 会话 | 首条/关键消息 | 时间 | 说明 |
|---|---|---|---|
| hi(workspace,既有 09:13 会话) | e2e06-a2,replyok(轮次 2) | 11:44 | A2 目标会话(非新建,旧会话复用) |
| hi(fork) | fork 自轮 1;续 e2e06-a5,replyok | 11:53 创建 | A5 分支会话(session-7ccdfe0f) |
| (同上 fork) | @[hi](dsh-session:…) ,summarize… | 12:11 | A6 发送现场(轮次 3) |

- 未新建任何设备端全新会话(A2/A6 均在既有/派生会话内进行,标题均「hi」);未归档/删除任何会话;未触碰工作区;服务器条目终态=2。

## 证据索引(/tmp/e2e-full/,共 142 文件)

- A1:A1-r{1,2,3}-list.xml/png · A1-r1-after-refresh.xml · A1-seg1.mp4 / A1-seg2.mp4(录屏)
- A2:A2-{typed,sent,list-after,list-recheck}.* · A2-verify-entered.xml
- A3:A3-{head,swipe1,swipe2,bottom}.png + 对应 xml
- A4:A4-{list,taskmenu*,menu4,agentsheet*,tree*,child-opened,sent}.* · A4-tree-grandchild.png
- A5:A5-{ledger-exp,forked,reply}.*
- A6:A6-{fork-at,selected,reply*,fullmsg}.*
- A7:A7-panel.xml/png
- A8:A8-{list,relaunch,sessions2,entered,transcript}.*
- 日志:W1-logcat.log(501,950 行,PID 三代)· W1-crash.log(空)
- 工具:type4.sh(单连接打字)· parse_dump.py 等取证脚本

## 遗留给主 agent 的裁量点

1. A1 的 BLOCKED 定性:期望「宿主派生会话行入主列表」与现行「官方同构过滤」设计直接冲突——若 #331 前插揭示需在「他处新建顶层会话」维度复测,机制应改用 web GUI/API 顶层会话(本轮红线内无构造通道)。
2. A2 ✘ 的滞后根因(服务器 list 响应滞后 vs SSE 缺席)需服务端/抓包定位;时间戳值本身透传正确。
3. 子会话 @ 候选拒绝是否属契约预期(SubagentCatalog 域),建议在 #321/#310⑤ 后续卡片裁决。
