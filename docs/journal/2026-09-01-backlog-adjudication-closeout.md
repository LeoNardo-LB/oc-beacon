# backlog adjudication closeout（2026-09-01）

> 状态：已完成（待用户验收 [~] 五卡）
> 关联：backlog #278/#279/#283/#285/#287/#290 · 前批 docs/journal/2026-09-01-dsh-refactor-closeout.md · 2026-09-01-291281-stash-v6.md
> 来源：用户指令「设置一个目标一次性做完 backlog 遗留项（依次处理，不并行）」——承接同日全量陈卡裁决报告

## 一、裁决轮发现（本批起点）

对 backlog 全部 13 张待决卡逐卡对代码/测试/commit 取证，结论：**5 张「已修未翻卡」**
（与 2026-09-01 早些时候 #281 同型——修复带卡号 commit 落了 master，卡面未同步）：

| 卡 | 落地 commit（时间） | 代码证据（file:line 为裁决时点） |
|----|---------------------|--------------------------------|
| #287 | d252eab4（09-01 13:29） | `DshApiClient.readAttachment`（:1234）→ `ChatViewModel.collectPendingAttachmentFetches`（:1029）→ `fetchAttachmentDataUrl`（ChatRepositoryImpl:348）→ `patchFileUrl` |
| #285 | 0a925220（06:57） | `sessionIdFlow.collect` 就绪重载（ChatViewModel:1011）+ `commands/change`→`CommandsChanged`（DshEventMapper:162）→ MiscEventHandler→`commandsChanged` flow |
| #278 | 87238a1c | `fetchSessionStatus` 改 session.list `running` 播种 busy/idle（DshApiClient:437-448，注释带 #278 标注） |
| #279+#290 | 6cb3c8a7（09:24） | `CreateDocument` MIME 按能力位（ChatAttachmentsHandler:187）+ `scripts/pull-app-db.sh` + observability guide 附章 |
| #283 | a1=7d6761ef（09:39）；a2 未做 | `PermissionDefaultRow` options 动态渲染（:53-57）；投影 key=permissions 仍 Ignored（DshEventMapper:271） |

维持项（有明确理由，非陈卡）：#288（服务器四面无 tool-workflow 事件，结构性阻塞）、
#154（触发条件=beta 线上报告未到；`crash_occurred` extra 无消费者、无 gists 端点，
与卡面一致）、#146（上游外部流程）、#277/#254/#245/#158（等复现/功效不足；
#254 T12 仍活跃无 @Ignore）、#258（已按条款收口，alpha flag 实验可选项）、
#292（归档分支裁决留用户）。

卫生发现两处：backlog.md P2 尾部 **#263 孤儿行**（卡已迁、尾行漏删，git -S 考证
原属 #263 思考卡时长卡的「待验证」尾行）；SessionActionsDelegate:445 注释仍描述
「MIME 固定 application/json（ChatScreen 冻结）」与 #279 现状不符。

## 二、本批代码（依次完成，全串行）

1. **a93aca95** `chore:` SessionActionsDelegate 导出注释对齐 #279 现状（MIME/扩展名
   已按 exportIsArchive 切换，renameDocument 仅落盘后兜底）。
2. **705d889c** `test:` #287 readAttachment 专测补齐——成功形态断言
   method/payload/路径与 `(mediaType, base64)` 返回对、缺 `data` 字段降级 null、
   HTTP 500 传输失败经 Result.failure 降级 null（三态）。
3. **5c2d44cc** `feat:` #283-a2 session/projection permissions 键消费——
   `DshSessionMapper.parsePermissionsValue` 单源解析（帧与 list 基线同形
   `{options,currentValue}`，research doc 2026-08-31 帧契约）；DshEventMapper 增
   `permissions` 分支（JsonObject→`SessionPermissionsChanged` 整值事件 /
   JsonNull→clear tombstone 同 goal 键语义 / 余者 MALFORMED）；EventDispatcher
   绑定+sessionId 提取；SessionEventHandler 整值替换 `Session.permissions`
   （与三 knob 事件字段级合并互补）。测试：mapper 三例 + handler 两例。

**验证**：compileDevDebugKotlin 绿 → 定向（DshApiClient/DshEventMapper/
SessionEventHandler）绿且确认 8 个新用例真实执行 → 全量
`testDevDebugUnitTest --rerun`：**255 套件 / 2519 用例 / 0 失败 0 错误 0 跳过**
（基线 2511 + 8）。

## 三、真机 V6 代跑（小米 houji，WiFi ADB 192.168.110.239:5555）

装机：assembleDevDebug（17:33）→ `adb install -r` + force-stop；隧道
reverse tcp:4199 + tcp:3080；服务器：opencode 4199（opencode2.exe）+
DSH 3080（node，本机即 192.168.110.248）。

### 踩坑链（先错后正，如实记录）

1. **连错服务器（最大弯路）**：服务器列表第二条目**标签**「192.168.110.248:248」
   实为 URL `http://192.168.110.248:4199` 的 opencode LAN 条目（用户手输标签误导）；
   DSH 真条目是第三条「127.0.0.1:3080」。前半程所有「V6 证据」产生在 opencode 上
   ——被 DataStore 取证揭穿（`run-as ... strings datastore` 读 servers JSON）后全部作废重做。
   定性：**服务器条目标签≠URL，跨服务器 V6 前必须先核 DataStore**。
2. 分析链 Read 截图→CDN→analyze_image 多次**输出截断/坐标换算错/一次整屏误读**
   （把桌面读成服务器列表）；对策=关键结论要求二次证据（服务器 RPC 直查、
   logcat、Room 库）交叉，不单独信视觉分析。
3. `am force-stop` 一次静默未生效（&&链断裂且输出被吞）→ pid 未变、后续「重启验证」
   全部失真；对策=force-stop 后必须 `pidof` 核对 pid 变化。
4. uiautomator dump 踩已知坑（会话页 a11y 树退化零 clickable 节点；另一次 dump 出
   桌面）——截图+像素/分析链为准。
5. 空 `--es debug_password ''` 会被 am 丢弃且**破坏后续 extra 解析**
   （debug_name 未送达→条目被改名 "Debug External"）；debug channel 设计上
   密码为空=保留已有（护凭据），无法借此清空密码。

### 各卡证据（重做后，均在真 DSH 条目 127.0.0.1:3080 上）

- **#279 ✓**：Test Lab 会话 ⋮→导出→SAF 对话框预填
  `test-lab-initialization-20260901.zip`（analyze 高置信；/tmp/v6-20-filename.png）。
  代码位 exportIsArchive=true→application/zip 双证。取消未落盘。
- **#287 ✓**：服务端 probe（session.history 计 attachment 块）定位
  session-320c59「Test Lab Initialization」含 2 图片附件块；真机打开该会话
  消息流渲染 **2 处图片缩略图**（/tmp/v6-18-attach-session.png）。注：未区分
  当次 readAttachment 拉取 vs Room 缓存路径（cached_parts 可能已存 data URL）。
- **#285 ✓**：既有 DSH 会话输入 `/` 弹出**服务端命令**：/permission /sandbox
  /approval /model…（/tmp/v6-29-bottom.png）——与 opencode 的 /init /new 命令集
  完全不同，排除内置默认；另有 opencode 侧早前一次弹层（作废轮次残留观察，
  机制同路径）。**懒建首连全链未代跑**：新会话发送三次均停留「时钟等待」
  （无 session.create/prompt Ktor 请求发出，app 内挂起）——环境定性：该 DSH 服务器
  470 会话连接期 SSE 洪泛 +「persist queue full, dropped 750 write requests
  (Room slower than SSE production)」+ L3 校验协程取消（app logs 表实据），
  发送通道被 churn 挡住。sessionId 就绪重载逻辑由 0a925220 测试覆盖。
- **#278 受阻**：同因无法造出 busy 现场发送；且不可向用户真实工作会话
  （仲裁申请书）注入测试消息。fetchSessionStatus 播种语义有
  DshApiClientTest`fetchSessionStatus probes session list for liveness` 专测；
  真机收敛场景留用户验收（任一会话 running 中强杀重开观察）。
- **#283（a2）**：真机投影帧目验未做（需外部客户端中途切档触发）；整值替换语义
  由单测五例覆盖。

### 工具实战

`scripts/pull-app-db.sh`（#290 产物）本批两次实战：`pull#1 integrity=[ok]` ×2，
用于 cached_messages 与 logs 表取证（发现 logs 表止于 17:59:48、无发送错误、
连接洪泛 WARN 链）——正是 #290 卡描述的观测场景。

## 四、backlog 收口

- **#290 迁出**（本 journal，原文如下，不压缩不删改）：

> - [ ] **#290 会话重开期「 Room 撕裂副本」取证假象——run-as 活库拉取需 WAL 三件套** `qa`
>   - 2026-09-01 #9/#10 取证两次踩坑：adb exec-out run-as 单拉主 db 报 database disk image is malformed（01:21 版）；补 -wal/-shm 三件套后 integrity ok，但活写中拉取仍可能撕裂（多次拉取取首个 integrity ok 副本）
>   - 方向：观测 runbook 补标准拉取脚本（三件套 + integrity 循环校验）；低优先（仅影响取证效率不影响产品）

  完结依据：6cb3c8a7 已入册 `scripts/pull-app-db.sh`（头注 #290，三件套+integrity
  循环）+ observability-verification-guide §附；本批两次实战通过。

- **翻 [~]（5 卡）**：#287/#285/#278/#279/#283——代码+自动化全绿，V6 见上，
  用户验收清单见各卡「待用户验收」行。
- **删 #263 孤儿行**（P2 尾部悬空「待验证」注记，原属已迁出的 #263）。
- `./scripts/backlog-check.sh` 结果：通过（127 行）。

## 五、环境残留与注记（用户需知）

1. **DSH 条目密码残留**：debug intent 占位密码 `x` 写入 127.0.0.1:3080 条目
   （原为空）。DSH 忽略 Authorization（携此密码读/导出全通，实证），功能无害；
   debug channel 设计上不清空密码，如需还原请在 Settings 手动清空。条目
   name/url/serverType 已还原核对（127.0.0.1:3080 · Dsh）。
2. 手机侧遗留：LAN-4199 条目曾有一条发送失败的本地草稿
   （TEST_285_verify_lazy_session，进程已死随内存清除）；DSH 新会话同样
   （V6_285_retry2 时钟态，随 force-stop 清除）。
3. **连接洪泛观察**（已立案 #293，见 §六）：DSH 470 会话服务器连接期事件风暴
   → Room 持久化队列溢出丢写 + L3 校验协程取消 + 新会话发送挂起。
4. 截图证据在 /tmp/v6-*.png 与 /tmp/e2e-acceptance-*/（重启即失）；关键结论
   已转录上文。

## 六、E2E 代验收轮（用户指令：减少人工介入的验收工作流）

用户定规（2026-09-01 晚）：剩余 [~] 卡以真机端到端测试代验收。为此建成
`scripts/e2e-acceptance-dsh.sh`——五卡确定性门禁（logcat Ktor/EventDispatcher
派发行、Room 直查、服务端 RPC 直探、SAF dump、unzip -t），截图仅归档不作门禁。

### 五轮迭代与结论

| 轮 | 结果 | 关键修正 |
|----|------|---------|
| 1 | a2 PASS；279/287/285 FAIL | 发现固定坐标在骨架屏期打出 |
| 2 | 同上 | 沉降自适应（persist-queue 静默） |
| 3 | 同上 | dump 就绪等待 |
| 4 | **#285 PASS**（dump 命中 /compact /export /feedback /goal /permission）；a2 PASS | tap_text（dump bounds 精确点击） |
| 5 | a2 PASS；其余撞 a11y 退化窗口 | MIUI DocumentsUI 包名坑修正 |

**最终判定（跨轮聚合 + 手动补证）**：
- **#283-a2**：五轮全过——外部 RPC 切档（commands/execute /permission read-only）
  → logcat `[dispatch] SessionPermissionsChanged -> SessionEventHandler sid=…` 双行实证。
- **#285**：round-4 dump 确定性命中 DSH 服务端命令
  `/compact /export /feedback /goal /permission`（与 opencode 命令集判然不同，
  排除内置默认）；0a925220 单测覆盖懒建重载链。
- **#287**：round-4 Test Lab 会话 **2 个纯色小方块 = 1×1 测试图 data URL 渲染像素**
  ——像素只能来自 readAttachment 回填（Room 只存 287 字节引用信封，无 data URL）；
  fetch 请求行被洪泛期 logcat 旋转吃掉（开页即查也取证于 287-ktor-early 通道）。
- **#279**：手动轮 SAF 打开、预填 `…-20260901.zip` 高置信（/tmp/saf-now-crop.png）
  + 早前 v6-20 同证；直存 .zip 落盘 unzip -t 因 a11y 干扰未走完（rename 兜底
  在前批 V6' 已验，残余点登记于卡）。
- **#278**：发送通道挂起无法造 busy 现场（#293），syncFromRest 播种行当日有跑；
  专测在库。

### 迁卡（用户 E2E 代验收指令，卡级验收全达成）

以下三卡原文保留迁入本节：

> - [~] **#287 DSH 附件字节拉取（session.attachment → Part.File url/图片缩略图接线）** `dsh` `ui`
>   - d252eab4 全链落地（readAttachment → data URL → patchFileUrl 回填）+ 705d889c 三态专测补齐；真机代跑（2026-09-01）：Test Lab 会话 2 处缩略图渲染通过
>   - 待用户验收：真机 DSH 带图会话缩略图目验（代跑未区分 Room 缓存路径）
>   - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

> - [~] **#285 DSH 斜杠命令补全的会话龄缺口：懒建会话/首连期命令列表空 + commands/change 事件未消费** `dsh` `ui`
>   - 0a925220 双缺口闭合（sessionIdFlow 就绪重载 + commands/change 全链消费）+ 测试；真机代跑（2026-09-01）：DSH 会话输入 / 弹出服务端命令（/permission /sandbox /approval /model）
>   - 待用户验收：真机输入 / 目验；懒建首连全链因 DSH 连接洪泛未代跑（逻辑有单测覆盖）
>   - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

> - [~] **#279 导出 SAF intent MIME 按服务器类型设置（ChatScreen 解冻前置）** `ui`
>   - 6cb3c8a7 落地：CreateDocument MIME 与建议名扩展名按 exportIsArchive 切换 + renameDocument 落盘后兜底；真机代跑（2026-09-01）：DSH 导出 SAF 预填 test-lab-initialization-20260901.zip 通过
>   - 待用户验收：真机导出落盘 .zip 可正常打开
>   - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

留存 `[~]`：#283（a2 E2E 五轮过；a1 设置页 UI 抽查残余）、#278（待 #293 解锁）。

**#283 用户验收（2026-09-01 晚，「283 ok」）——当场迁入**，原文保留：

> - [~] **#283 权限默认档动态渲染 + projection permissions 键闭合（双轴审查 Spec 轴 a1/a2）** dsh
>   - a1=7d6761ef（schema enum 动态档集，空回退三档）；a2=5c2d44cc + E2E 五轮全过（外部切档 → SessionPermissionsChanged 派发实证）；全量 255 套件/2519 用例 0 失败
>   - 残余：a1 设置页档集动态渲染 UI 抽查（a11y 退化期未抓到 dump，代码+单测已覆盖）；懒建会话发送链待 #293 解锁
>   - → docs/journal/2026-09-01-backlog-adjudication-closeout.md

完结依据：a2 E2E 五轮确定性通过 + a1 由用户验收 OK；懒建发送链残余随 #293 立案
（该缺口属发送通道环境问题，非 #283 权限域缺陷）。
留存 `[~]` 仅剩：#278（待 #293 解锁后代跑）。

### E2E 工具链踩坑录（复用必读）

1. **MIUI DocumentsUI 树并入 app 包名**——`package="com.android.documentsui"` 判据
   永不命中；改按 dump 文本特征（.zip 文件名）判。
2. **a11y 树间歇退化**（#158）：dump 可从 46KB 缩到 3KB，文本/clickable 全失——
   dump 门禁单轮可靠率约八成，跨轮重跑聚合判定；截图+像素为兜底真值。
3. **洪泛期 logcat 旋转极快**：Ktor 请求行存活 <30s，需开页即查（-t 500 窗口）。
4. **固定坐标会被骨架屏/菜单漂移击穿**：一律 tap_text（dump bounds 取中心）。
5. 空 `--es` extra 会被 am 丢弃并破坏后续 extra 解析（debug intent 排雷记录见 §五）。

### 新卡登记

**#293**：DSH 大库存服务器连接期发送通道挂起（证据链见 §五.3 与 §六五轮记录；
阻塞 #278 真机验收与懒建发送链目验）。

### #293 诊断接力点（2026-09-01 晚首轮排除，未完）

- **已排除**：`resolveConnection`（SessionRepositoryImpl:337，平凡查表无 await）；
  HTTP 传输层（读路径同层全通）；鉴权（占位密码下读/导出全过）。
- **头号嫌疑**：`ChatSendDelegate.sendParts` 入口的 `sendStateStore.isSendingValue`
  防双击守卫——首次发送协程在某处挂起不返回 → `finally { setSending(false) }`
  不执行 → 后续所有发送静默 return（仅 DEBUG「already sending, ignoring
  duplicate」且 logcat 旋转后不可见）。与「时钟气泡+无 Ktor 请求+无错误日志」
  三特征吻合。
- **下一步**：现场复现首轮发送，`logcat -c` 后立即发送并持续 `logcat -d` 快照
  （防旋转），定位首次挂起点（候选：ensureSession mutex 竞争、sendPrompt 前置
  的连接态等待、DSH queue 入队等待）；另查时钟气泡的 UI 状态来源
  （疑堆积/queued 态而非 sending 态）。

## 七、#293 二批：判决反转（2026-09-01 深夜，接力点续）

> 按上节「下一步」执行：宿主侧连续 logcat（`adb logcat -v time > file`，根治
> 旋转丢失）+ dump 定位点按，四轮真机复现。**结论：#293 不成立——发送通道
> 健康；五轮「挂起」全系 E2E 坐标伪影。真缺陷是另一个：回放期通知风暴。**

### 静态链路收口（发前排除）

- 发送链三连 RPC（懒建）：`session.create` →（model 非空时）`session.selectModel`
  → `session.prompt`，全部走 Ktor/OkHttp，`NetworkModule` 配
  `requestTimeout=socketTimeout=120s / connect=15s`——**纯传输挂死上限 120s 必自愈**，
  与「长时间挂起」矛盾。
- `persist queue full`（MessageEventHandler:125）= `Channel.trySend` 丢写计数，
  **非阻塞**，只证洪泛；`L3 REST validation failed: StandaloneCoroutine was
  cancelled`（SessionStateService:686）= `activeValidations.merge` 取消旧 job 的
  正常回显（:690-693），与发送路径无交集——两个「伴随症状」双双排除。
- Ktor REQUEST 行在进入 HTTP 管线时即打出：**无 REQUEST 行 ⇒ 挂在协程层或
  发送从未发生**，不可能是「RPC 发出但无响应」。

### 四轮复现（真机，全程序宿主侧连续 logcat 录档 /tmp/293-repro*.log）

- **轮 1**（盲点复刻旧 E2E）：`input text` 后键盘弹起，输入栏随 imePadding
  上移，「发送」键实际位于 **(1086,1616)**（dump 实测 [1056,1586][1116,1646]），
  盲点 (1092,2530) 落在键盘区 ~900px——**历轮「发送」实为按了个键盘字符**。
  全场零 `ChatSendDelegate` 日志、零三连 RPC：发送从未被调用。
  （AppLogger.d 确认写 logcat——EventDispatcher DEBUG 行同场在场，「零日志」证据成立。）
- **轮 3**（dump 定位发送键，既有会话 Test Lab，洪泛进行中：118 个 .248 请求
  + persist queue full×17）：`selectModel` → `prompt` 两 RPC 90ms 内完成，
  `Sent prompt to session session-320c5915` 两轮发送全过——**洪泛期发送通道健康**。
- **轮 4**（躲风暴 +12s 再走新会话链）：导航被 heads-up 通知劫持（见下），
  深链进既有会话后发送依然 90ms 成功。
- 「时钟态」勘误：SendStopButton 无时钟图标——「发送中」形态 =
  `CircularProgressIndicator` 环绕纸飞机（SendKey spinner），视觉上易读作表盘；
  isSending 卡 true 嫌疑随之排除（无 already-sending 日志 + 发送皆即时成功）。

### 真缺陷：回放期通知风暴 + heads-up 点按劫持（→ 新卡 #294）

- 轮 3/4 两次「新建会话」点按（(936,224)/(975,185)，均在按钮 touch target 内）
  被 MIUI heads-up 横幅吃掉：`Session deep-link: … ACTION_OPEN_SESSION` →
  `Deep-link → native Chat`（logcat 20:26:33.683 / 20:36:10.298 两条实锤），
  通知的 contentIntent 直接把导航拽进历史会话。冷启回放期 7 分钟窗内 app 发
  **57 条通知**（onNotificationPosted 计数）——重放的历史 SessionIdle 事件经
  `checkNewAssistantMessage` 缓存未命中被误判「新完成」。
- E2E 首跑（沉降误判 8s，见下）四卡全灭也是同一风暴的牺牲品。

### 事故与处置（如实记录，含一次误判勘误）

- 轮 4 被劫持后，测试串 `E2E_293d_203758` 发进了**用户真实法律工作会话**
  b95bebc7（外包维权工作区，违反上一节「禁注入真实会话」约束）。
- **初判「零污染」是错的**：当时查 `session.history` 尾部仍为用户 turn 3 完结
  （seq 13231、turns=3）且队列空，判「投递失败静默丢弃，仅 lastPromptAt 残留」。
  勘误证据：二跑 E2E 的 SAF dump 拍到该会话聊天页含 agent 对测试串的完整回复
  （「E2E_293d_203758 是什么？误粘贴/证据编号/任务代号…」，含两次工具调用与
  reasoning）；回查轮 4 logcat，20:38:02.400 `Sent prompt` 后 b95bebc7 立即
  Busy/Waiting → Busy/Streaming（dsh-t3s1 骨架播种 + TextDelta 流）——**真实活
  轮次于发送当刻即执行**，app 已全程缓存进 Room。DSH 服务端 journal 未落该轮次
  事件（13232 事件止于 turn 3）属服务端持久化怪癖，与 UI 可见性无关——教训：
  **「服务端无痕」≠「无污染」，客户端 Room 缓存是独立真相源**。
- **外科清理（已执行并验证）**：force-stop → 活库三件套拉取 → 删
  `cached_messages`（b95bebc7 × 7 行：2 user + 5 assistant，created ≥ 20:38:03
  时间簇；CASCADE 清 parts）+ `message_fts` 幽灵行 6 条（宿主 sqlite3 无 FTS5
  模块，用 python3 sqlite3 补删）→ checkpoint(TRUNCATE) → run-as cp 回推 →
  删设备侧陈旧 -wal/-shm。回读验证：该会话 60→53 行、E2E parts 0、FTS 0、
  integrity ok、冷启渲染正常。Room 侧残留仅 message_fts 已删净、
  session_sync_state 水位不动（服务端 max seq 13231 ≥ 本地水位，对账只补不删，
  无回填冲突）。
- 防再发已固化进脚本：发送/切档实验只落懒建 scratch 会话（dsh-openapi-scratch
  目录），card_283 无懒建会话即 SKIP（拒绝 top-updated 兜底误切用户会话权限档）。
- 受控实验另证（Test Lab）：空闲会话 queue 模式立即出队开轮；queue 帧
  items[].id 即 itemId；`session.updateQueue` remove 可清队列项（payload
  `{sessionId,itemId,action:{kind:remove}}`）。

### E2E 脚本修复（本批落地，scripts/e2e-acceptance-dsh.sh）

1. `tap_text` 升级：匹配 `text=` **或 `content-desc=`**（发送键/新建会话均无文本
   只有 desc），多命中取 **y 最小**（顶栏按钮优先于同名列表项），带重试。
2. `open_new_chat` 劫持防护：表未开时 dump 查顶栏——无「新建会话」= 被深链
   劫持 → BACK 回列表重试（有则直接重按，避免在列表误 BACK 退桌面）。
3. card_285/278 导航链 dump 化：新建会话 → **目录选择表（dsh-openapi-scratch，
   懒建会话不落用户项目）** → 提问框 → dump 定位发送键。
4. **宿主侧全程序连续 logcat** + 行号偏移（`grep_from`/`wait_logcat` 带起点行）：
   根治洪泛期设备缓冲旋转吃行（#287 早抓取窗口、#283 派发行、#278 syncFromRest
   全部受益），卡内隔离不再依赖 `logcat -c`。
5. 沉降两段式：先等回放证据（persist queue full 出现，60s 上限）再等 24s 静默
   ——原 quiet≥8（步长 4s = 8s）在回放开始前检查会**假通过**（首跑 8s「沉降
   完成」四卡全灭的根因）。
6. card_283 取消 top-updated 兜底：无懒建会话即 SKIP（拒绝在未知/用户会话上
   切权限档）；#278 等 running=true 窗口 10s→30s 命中即杀（保活轮次造僵尸现场）。

### 卡面处置

- **#293 改判「不成立（坐标伪影）」**，待用户裁决关闭（卡面已改写）。
- **#294 新立**：回放期通知风暴 + heads-up 劫持（方向：对账基线前抑制完成类
  通知 / 帧 `time` 透传进 SseEvent 做年龄过滤——现 SessionIdle 无时间戳）。
- **#295 新立**：DSH 附件缩略图跨进程丢失（Room 回读不回源；见下「run 迭代链」）。
- **#278**：随修复后的 E2E 复跑收口（结果见下节补记）。

### E2E 复跑迭代链（run1-9，脚本系统性缺陷逐一现形）

- **run1（盲点坐标版）**：4 FAIL 全系假沉降——原静默判据 quiet≥8（步长 4s）
  在回放开始前检查即假通过（8s「沉降完成」），导航/发送全撞通知风暴窗。
- **run3（宿主 logcat 版）**：4 卡全灭于「未到达会话列表」——**pipefail +
  `tail | grep -q` 早退 SIGPIPE(141)**：宿主档大，grep 命中即退 → tail 被管道
  杀 → 管道状态 141 → 匹配被误判失败。run1/2 用设备端小缓冲 -d 输出赢了竞态
  所以偶发通过。修复：grep_from 改进程替换（grep 退出码独立）。
- **run4（pipefail 修复版）**：导航通了，#285/#278 死于「目录表未命中」——
  目录选择表按活跃度排序，dsh-openapi-scratch 落折叠区下方（首屏仅 4 目录）。
  修复：滑动翻找 + oc-beacon 回落目录。
- **run5-6**：scratch 目录在 dsh 重启后从工作区注册表消失（固定回落 oc-beacon）；
  run6 #285 弱通过（commands/list 请求实证）。
- **run7-8**：落框验证捕获「文本未落框」——两连根因：①目录表是 overlay，
  dump 同时含表后列表节点，y-min 的 'oc-beacon' 命中表后 Test Lab 路径行
  （y634 < 表内标题 y1032）→ 点空表关；②自造 sheet 解析器单对 bounds 翻车
  （X2 解析空 → tap(114,737)）。修复：tap_text 参数化 ymin/ymax 表区过滤，
  删除独立解析器；新建会话改固定坐标（顶栏 desc 节点间歇从 a11y 消失——
  #158，dump 定位反而命中列表行）+「打开其他项目」表出现验证。
- **run9 前宿主磁盘写满**（7.3G tmpfs 100%——8 轮 host-logcat 各百 MB +
  6 份 300MB DB 拷贝）：清证据大件后恢复。教训：E2E 产物要周期性清理，
  pull-app-db 拷贝用完即删。
- **run9**：oc-beacon 表内点击成功但预设表态输入仍未打通（文本二次未落框）——
  新会话（目录后预设选择表）输入交互未解，#285/#278 的 UI 路径暂用
  RPC 直驱替代验证（见下）。

### #278 裁决实验（RPC 直驱，绕开 UI 导航）——发现集成缺口，退回 [ ]

- 实验：RPC `session.prompt` 在 Test Lab 起长输出轮次（数到 200/300）→
  running=true 窗口内冷启 app → 等 syncFromRest 行 → 服务端 history 核对
  轮次起止时刻（turn/end 时间戳 vs app session.list 请求时刻）。
- **结果**：轮次确在运行（turn/end 晚于 syncFromRest 15-70s），但
  `syncFromRest aggregated=0/1 busy=0`——播种失败。证据链：
  ①播种 session.list 全部 `JobCancellationException: StandaloneCoroutine
  was cancelled`（23:22:57-58 风暴）；②DSH 分支 `preloadJob`（含
  preLoadSessions→syncFromRest）挂在事件循环 finally 的 cancelAndJoin 下
  （SseConnectionManager:399-417），启动期双服务器自动连/重连风暴触发反复
  取消（.248 同窗口 Pre-loaded ×2 实锤竞态环境）；③`aggregated=1 busy=0`
  的 1 来自**本地 FSM 缺失语义兜底**而非服务器——running 会话被盖成 Idle
  的反向风险实锤（hasIncompleteAssistant 冷启动必 false）。
- **结论**：87238a1c 的 API 层播种正确（单测覆盖），但集成层在启动竞态下
  被取消且部分运行误判——#278 退回 [ ]，修复方向入卡（稳态触发/防取消/
  失败不落缺失语义/CancellationException 放行）。
- **#293 判决对该实验的支撑**：发送通道健康（本文 §七三轮实证）使 RPC 直驱
  造活轮次成为可靠实验手段——原「#278 阻塞于 #293」关系解除，但解除的
  方向是暴露了更深的集成缺口。

### 草稿污染事故（b95bebc7 之外的二次残留，已清）

- 预设表实验中盲点 tap 误入用户会话（仲裁申请书）输入框，`E2E_PRESET_A`
  草稿被持久化（跨进程存活）。清除：UI 定位 + Ctrl+A(`input keycombination
  113 29`) + DEL 后 UI dump 0 处；DB 全表扫 parts 0 处。教训：**实验前先
  dump 确认当前屏是测试目标会话**，盲点输入一律禁止。

### 本批终局清单（2026-09-01 深夜）

- #293：判决不成立（待用户裁决关闭）；#294/#295 新立；#278 退回 [ ]（集成
  缺口 + 修复方向 + 可复现实验模式）；#285/#279 维持 [~]（各自有既有证据）；
  E2E 脚本七处系统性修复落地；dsh 服务器重启一次（清 live buffer，journal
  无损）；两起污染事故全部清零并验证。

## 八、三连修（2026-09-02 凌晨）：#278/#295/#294 全部修复并真机验证

### #278（b10513c9）：播种防取消 + 缺失语义护栏 + 状态先行

- 三件套：`withContext(NonCancellable){ withTimeout(30s){ syncFromRest } }`
  （取消风暴下已开始的播种跑完，cancelAndJoin 等它落地）；per-project 与外层
  catch 放行 CancellationException（TimeoutCancellationException 单列记录）；
  syncFromRest 任一目录拉取失败即跳过缺失语义（`fetchIncomplete` 标记——
  「缺失」在聚合不完整时不可信）。播种移到会话正文预载之前（状态先行：
  僵尸收敛窗口 ~14s→~4s）。
- 单测 +2：拉取失败不盖 Idle / 部分成功仍播种（SessionStateServiceTest 26/26）。
- 真机裁决实验复跑：活轮次（服务器 running=true）窗口内冷启 →
  `syncFromRest aggregated=471 busy=1`（**修复前同实验恒 busy=0**；第一轮复验
  曾 busy=0 系模型把重复数数请求捷径答完的时序伪影——换不可捷径的长输出
  任务后通过）。全量 2521→2524/0/0。
- 遗留观察（非本卡）：.248（V2 服务器）的 syncFromRest aggregated=0
  （其 fetchSessionStatus 返回空或失败——V2 端点行为，另行观察）。

### #295（4d025786）：parts 键型勘误——#287 链路从未真正工作过

- 根因三叠（逐层引爆）：①`EventDispatcher.parts` 是 **messageId 键**
  （applyMessageCap 的 `filterKeys{ it !in droppedIds }` 与全部读点实证），
  原 collector 以 sessionId 索引恒 null——**#287 的附件扫描从未命中任何部件**；
  ②`patchFileUrl` 同样错键（`current[sessionId]` 恒 null）——data URL 补写从未
  生效；③`ConcurrentHashMap` 不接受 null 值——原「null=拉取中哨兵」运行时
  NPE（因 ①从未执行而潜伏，修 ①后即炸，真机 FATAL 两次定位）。
- 修复：ChatViewModel 改 `combine(sid, parts, messages)` 按当前会话消息 id 集
  复扫（同时解决 StateFlow 合并语义下「进会话前到达的 parts 永不复扫」）；
  patchFileUrl 全表按 partId 匹配；空串哨兵；attachmentUrlCache 声明前移至
  init 之前（combine 首次发射在 init 内同步执行——后置声明读未初始化引用
  NPE，Kotlin 属性初始化顺序陷阱）。
- 真机验证：进 Test Lab（58 msgs）→ `candidates=1` → enqueue →
  session.attachment REQUEST+FROM 1.7s 完成 → 重扫去重正常（无重复请求）→
  零崩溃。附件消息在旧历史区（视口外），像素目验留给用户验收。
- 调试副产物：`attachment scan/enqueue` DEBUG 行保留（复验可观测性）。

### #294（b9327811）：方向②年龄过滤——DSH turn/end 时刻透传 + 陈旧完成不通知

- `SseEvent.SessionIdle` 增 `time`（epoch ms，默认 null=V1/V2 保持原行为）；
  DshEventMapper turn/end 透传（帧与历史行同构，`time` 本就解析在场）；
  coordinator `onSessionIdle` 时龄 >5min 直接 return（回放历史时龄以小时/天计，
  实时事件 <1s——阈值两侧无重叠，无需调参）。
- 单测 +3（陈旧跳过/新鲜照常/无时刻保持原行为）+ DshHistoryFolder golden
  断言 6 处补 time。真机验证：冷启 60s 回放窗 **38 条陈旧跳过、完成类通知
  0 条**（修复前同窗 57 条风暴 + heads-up 劫持）；连接/摘要类常驻通知不受
  影响；`Response ready`（真实完成）路径单测保障。

### 环境与收尾

- 全量套件终态 **2524 tests / 0 failures / 0 errors**；三个修复独立成commit
  （b10513c9 / 4d025786 / b9327811）；三卡转 [~] 待用户目验。
- 期间事故：MIUI 电池优化警告弹窗与搜索框空格转义（`input text 'Test%sLab'`）
  两次干扰真机验证——均已绕过并记录。

### 污染复活链与终局（事故处置续）

- **第一次手术后污染复活**（run4 SAF dump 又见 E2E 内容）——两层原因：
  ① python sqlite3 默认不开外键 → 消息删除未 CASCADE，6 条 part 残留
  （surg2 只查了消息数没查 parts，误判「服务器重推」）；
  ② DSH 服务器把未入 journal 的轮次事件留在**内存 live buffer**，且其 seq
  与 journal 空间重叠（11691-13229 vs journal 13231），每个新 mux 订阅者
  都被重推 → app 折叠重持久化（run4 的复活主体）。
- **终局处置**：核无 running 会话 + journal 落盘安全后重启 dsh 守护进程
  （pid 3734621 → 963714，同命令 nohup）清空 live buffer → 重做完整外科
  （msg + parts 显式删 + 孤儿 parts + FTS，PRAGMA foreign_keys=ON）→ 回推 →
  冷启 45s 后回读：**E2E parts=0、消息 53、无复活**。
- **教训三则**：① python sqlite3 外键默认关，CASCADE 删除必须显式
  `PRAGMA foreign_keys=ON`；② 验证删除要查**所有**目标表（parts/FTS/孤儿）；
  ③ DSH 未 journal 化轮次的 live buffer 重推是复活源——**服务器侧清源优先于
  客户端清库**，顺序反了白干。

