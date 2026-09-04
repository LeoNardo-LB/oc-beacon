# dsh 0.1.2 adaptation（2026-09-04）

> 状态：进行中
> 关联：#317（鉴权+双栈探测）· #318（方法面）· #319（真机 E2E）· #314/#315/#316（收口随批）
> 来源：接续会话 91476971（goal 打断于 #317-319 登记途中）；配方来源 /home/leo-tkp/workspace/dsh-0.1.2鉴权层容器探针报告-2026-09-03.md

## 一、会话接续与环境重大变化（2026-09-04 05:2x 发现）

- **生产宿主已升级 0.1.2-rc.1**：`dsh --version` = 0.1.2-rc.1；`dsh web --no-open`（systemd dsh-web，PID 2198515，05:21 启动）；原目标「在役 3080 = 0.1.1 不动」前提失效——**当前 APK（0.1.1 协议）对 3080 已完全失联**（RPC/WS 全 401），适配从预备工作变为紧急主线
- **web.log 已收录 token 行**（探针报告 P-A6 悬置问题落定）：`~/.local/state/dsh-web/web.log` 尾行 `dsh web: http://127.0.0.1:3080/?token=…`；宿主侧回收通道 = `grep 'dsh web:'`
- 生产 patch 更新：connection.cookieMaxAgeDays=365（2026-09-04 接入）→ 每设备每年一次 token
- 上会话打断点（#317-319 登记+journal）已由本会话补齐（commit de08434c）；#314 E2E 原脚本打生产 3080 已不可行，改打 0.1.1-rc.2 容器（回归靶机）随 #319 批次执行

## 二、0.1.2 协议实证参考（实现 spec，全部活体/源码双证）

证据环境：探针容器 dsh012-a5（dsh-keepalive-e2e:0.1.2-alpha.5，0.0.0.0 patch，宿主 3081）；生产 3080（0.1.2-rc.1）waterfall 实测；源码 = 本机安装包 node_modules typert 描述器（与探针报告 git 树同源等价）。

### 2.1 版本×鉴权双形态探测（判别表实测）

| slash+args 探测 | dot+裸 探测 | 判定 |
|---|---|---|
| 401 | 401 | 0.1.2 + 未认证（auth 栅栏先于端点匹配）|
| 200*（body ok 或 args-invalid）| 404 | 0.1.2 + 已认证 |
| 404 | 200 | 0.1.1（无鉴权）|
| 200 | 200 | 异常态，按 0.1.1 处理并告警 |

- slash 探测端点：`session/list`，payload `{args:{_request:{}}}`；dot 探测：`session.list` + 裸 payload
- RPC 业务错误（含 arguments-invalid）以 **HTTP 200 + result.ok=false** 返回——版本判定只看 HTTP 状态与 result 结构

### 2.2 鉴权

- 交换：`GET /?token=<43char>` → 303 + `Set-Cookie: dsh-auth-<b64url(sha256(authority))>=v1.<payload>.<hmac>; Max-Age=2592000(默认)/31536000(本机 patch); Path=/; HttpOnly; SameSite=Strict`；伪 token 401；POST / 405
- cookie 绑定 authority（Host:Port）；跨 authority 401；跨 DSH 进程重启存活
- RPC：`Cookie` 头挂 /api/*；WS：升级请求带 Cookie（裸连 401 unexpected-response，带 cookie 101）
- 宿主 token 回收：web.log `dsh web:` 行

### 2.3 端点注册表（wire 全量，typert 描述器提取 + 活体验证）

命名空间/方法（斜杠式，args 包装，键=宿主参数名）：
- session/: list(`_request`:{cursor?}) · create(`request`:{workspaceId?,cwd?,sessionId?,agentPreset?}) · rename({sessionId,title}) · cancel({sessionId}) · fork({sessionId,atSeq?}) · page(`request`:{address:{kind:'session',sessionId},throughSeq,beforeSeq?,maxMessages?}) · prompt(`request`:{requestId,sessionId,mode:'queue'|'steer',content:[PromptContentPart],clientTimeZone?}) · selectModel(`request`:ModelSelection+sessionId) · updateQueue(`request`) · attachment(`request`) · search({query}) · modelCatalog(无参) · canOpenWorkspacePath · openWorkspacePath({path}) · follow(stream) · control(stream)
- settings/: describe(无参) · mutate({ns,ops,expectedRevision}) · update · replace · credentials→`credentials/describe|set|unset` · openSettingsDocument · openAgentPresetDirectory · canOpenAgentPresetDirectory
- commands/: list({agentId}) · execute({agentId,line,images})
- goals/: create/edit/pause/resume/complete/clear（goal.→goals/ 复数）
- subagents/: list({parentSessionId}) · prompt · interruptByParent
- agentPresets/: list · read · select · copy · deletePreset（agentPreset.→agentPresets/）
- llm/: listProviders · listConfigurableProviders · discoverModels（**llm.models 无对应** → 用 session/modelCatalog）
- workspace/: create · rename · delete · insertBefore · insertSessionBefore · archiveSession · follow(stream)（**workspace.list 无对应**）
- directoryPicker/: list({path}) · pick · createDirectory（**host.listDirectory → directoryPicker/list**）
- messageFeedback/: list · put · delete；sessionReferenceResolver/candidates；fileReferences/list；skills/list；pluginInventory/list；dynamicCordisRunner/*
- **host.describe 无对应**——版本探测由双形态探测承担；健康探活可用 session/list
- **`$events/result`**（应答通道，见 2.5）
- 0.1.1→0.1.2 语义变化：session.create 0.1.1 {title?,parentSessionId?,cwd?} → 0.1.2 {workspaceId?,cwd?,sessionId?,agentPreset?}（**无 title**；改名走 rename）；session.history → session/page（address 包装）；session.prompt 新增必填 requestId+mode；session.export **实测存活**（0.1.2 GET /api/session.export?sessionId= 200 ZIP，2026-09-04 alpha.5 二轮探针）

### 2.4 事件流（remote.mux 单 WS）

- 路径 `/api/remote.mux`（旧 events.mux/events.host 已删，裸连 socket hang up/401）；上行帧 `{type:"open",streamId,endpoint,payload:{args:{}}}` / `{type:"cancel",streamId}`；下行 `{type:"item",streamId,value}` / `{type:"error",streamId,error}` / `{type:"end",streamId}`
- **`$events` 逻辑流**（全局）：ready(`{type:"ready",clientId,host:{home}}`——**clientId 是 $events/result 应答凭据**) · emit(`{type:"emit",event,args:[…]}`) · waterfall · cancel
  - emit 事件名与 0.1.1 mux 帧方法名同源：`commands/change`（args:[]）、`api-session/added`（args:[SessionSummary 含内嵌 projections]）、`api-session/removed`、`api-session/status`(args:[sessionId,running])、`api-session/activity`(args:[sessionId,updatedAt])、`api-session/error`
- **`session/follow` 流**（按会话，`{args:{request:{address:{kind:'session',sessionId},maxMessages?}}}`）：首帧 `{type:"snapshot",header,cursor,records:[SessionHistoryRecord],hasMore,projections:SessionProjectionBaseline}`；增量帧 = SessionEventEntry `{type:"event",event:{type,seq,time,data,ignorable?,sourceEventSeqs?,surfaceOp?}}`（**SessionEvent 词汇与 0.1.1 mapSessionEventInner 输入同构——mapper SessionEvent 面可整体复用**）；records 亦含 `{type:"chunks",event:ChunkRowEvent}`（chunkrow/* 压缩行，0.1.2 新增）
- **`session/control` 流**（全局）：baseline `{type:"baseline",value:{queues:Record<sid,SessionQueuedItem[]>,jobs:Record<sid,SessionJob[]>,projections:Record<sid,SessionProjectionBaseline>}}` + 增量（SessionControlFrame 续型待 E2E 抓全）
- SessionAddress：`{kind:'session',sessionId}` | `{kind:'subagent',parentSessionId,childSessionId,mode:'one-shot'|'continuable'}`

### 2.5 waterfall（提问/审批）+ $events/result（生产 3080 实测全闭环）

- 提问帧（ask_user_question 实测）：`{type:"waterfall",event:"user-questions/request",eventId:<uuid>,agentId:<sessionId>,request:{questions:[{id,question,options:[{label,…}]}]}}`
- 应答：`POST /api/$events/result`，payload `{args:{clientId,eventId,outcome}}`，outcome=`{kind:"result",value:{answers:[{id,selected:[label]}]}}` | `{kind:"result"}` | `{kind:"next"}`（不处理放行下一客户端）| `{kind:"rejected",error}`；回 `{ok:true}`
- clientId 来自本连接 $events ready 帧——**应答与连接绑定**（0.1.1 respond 的 rpcId 关联模型废除）
- 服务端网关行为：waterfall 广播所有 $events 客户端；单个 outcome result/rejected 即 settle，全部 next 则放行下一 handler

### 2.6 信封不变项

- RPC 信封 `{type:"client-request",rpcId,method,payload}` / server-response 不变；403/401/415 语义不变；媒体栅栏 application/json

### 2.7 补充实测（2026-09-04 06:1x）

- **权限 waterfall 帧未捕获**：生产 3080 造会话跑 run_code（150s 窗口）未到 waterfall（该会话策略未触发审批）——权限事件名/outcome 形态留 E2E 批次用 /permission ask 强制触发后校准；合成器已按事件名 contains approval/permission 防御性映射
- **E2E 靶机布局**：dsh012-a5（alpha.5，:3081，鉴权）+ dsh011-rc2（0.1.1，:3082，无鉴权，四门禁回归用）+ 生产 :3080（0.1.2-rc.1，全链路主靶）；真机 192.168.110.239:5555 WiFi adb（#316 脆弱性——断连即重连）
- **debug_token extra**：MainActivity 调试通道新增 token 注入（与 TokenNeeded awaitCookie 双向汇合，先后序无关）——E2E 自动化路径

## 三、实现落点（对齐侦察报告 §1-§9）

已落地（commit beab6a3c 核心 + WS 批次）：
- **DshWireProtocol/DshWireAdapter**（#318 翻译收口）：方法名表（机械点→斜杠 + 显式改名 session.history→session/page、goal.→goals/、agentPreset.→agentPresets/、subagent.→subagents/、host.listDirectory→directoryPicker/list、llm.providers→llm/listProviders）+ payload 分风格包装表（SELF/EMPTY_ARGS/FLAT sessionId→agentId/WRAPPED _request|request）
- **DshConnectionRegistry**（#317 运行时）：双形态探测（ensureProbed 判别表）+ token 交换（GET /?token= → 303 Set-Cookie）+ cookie SecretCipher 加密持久化（DataStore 键 dsh_cookies，authority 键控）+ awaitCookie 挂起 + clientId 存取（$events ready）；DshMuxAuth 缝隙接口供引擎注入
- **DshRpcClient**：prepare() 线面翻译唯一收口（信封 method 同步 wire 名）+ Cookie 头 + 401→DshAuthRequiredException（清凭据）+ eventsResult()（$events/result 应答）
- **SseConnectionManager**：DSH 分支探测门禁（TokenNeeded→dshTokenNeededServers 状态集+awaitCookie 挂起；Unreachable→退避；Online→预加载+事件循环）
- **DshRemoteMuxEngine + DshMuxSynthesizer**（#318 WS 侧）：单 WS /api/remote.mux（Cookie 升级、401 特判等 token）；$events+session/control+session/follow(list 全量) 三流；**合成 0.1.1 帧词汇**（api-session/*→host/*、waterfall→question/requested(rpcId=eventId)、follow snapshot→subscribed 基线+session/event+projection、control baseline→jobs/queue/projection、chunk 压缩行跳过）——orchestrator/mapper/handler 零改动
- **DshFrameSourceFactory**：协议路由帧源（V012→mux 引擎，否则 0.1.1 双流引擎）
- **DshRpcHistorySource**：V012 page 翻页（address 包装 + throughSeq=asOfSeq 现查 + records 键 + 兼容 entries/events）
- 单测：DshRemoteMuxEngineTest（codec 三型/合成表 10 例）

进行中（子代理并行）：DshApiClient 调用点语义适配（create 无 title/page/prompt requestId+mediaType/goals args/agentPresets/select 回程字符串/llm 目录/workspace 降级/export cookie）。待办：replyTo* 三法 V012 分支（$events/result outcome 构造）、TokenNeeded UI + token 输入 + i18n、#319 E2E、#314-316 回归（0.1.1-rc.2 容器 3082）。

## 四、#319 生产/MITM 实证反馈修复（2026-09-04，双轴审查收口）

10bf7006 之后的验证反馈批（4 文件 + 新增 8 单测，全量 2666 绿）。提交前 Standards+Spec 双轴并行审查（code-review skill），两轴交叉定案：

### 4.1 实证锚点（补 §二缺项）

- **生产 440 会话全量 follow 拖垮服务端**（RPC 全线超时）——限界窗口依据；
- **session/list 条目字段**（0.1.2）：running（boolean）、updatedAt（epoch ms）——过滤判据（此前只有代码注释，无 journal 锚点，Spec 轴指出）；
- **DSH Web 前端 0 次 follow**：0.1.2-rc.1 dist bundle（index+vendor JS）grep session/follow 零命中——Web 按需 session/page 拉取，不批量 follow；移动端限界 follow 是推送需求的工程折中。

### 4.2 修复清单

1. **follow 限界窗口**（Orchestrator）：running || 24h 内活跃（提取 filterFollowableSessionIds 纯函数）+ **30min 时钟容差**（设备钟快偏防临界漏 follow；慢偏天然保守）；updatedAt 缺席判远古不 follow（保守）。
2. **三事件动态补开**（MuxEngine，Spec 轴核心缺口）：api-session/added / status(running=true) / activity 均触发 onSessionActive → openFollow（followed 去重 + **发送失败回滚名额**，重连全量兜底）——>24h 老会话被任意客户端再激活不丢流（原实现只挂 added，老会话 turn 流断供到重连）。
3. **token 交换裸 OkHttp**（Registry）：Ktor OkHttp engine config{followRedirects(false)} 对 303 不透传 Set-Cookie（MITM 实证：跟随到裸 index → 401）；专用裸 Builder（10s/15s 超时）+ suspendCancellableCoroutine 封装；类 KDoc 同步修订（Standards 硬违规：过时断言与新行为矛盾）。
4. **TokenNeeded 状态泄漏**（Sse）：stopConnection/stopAllConnections 补 _dshTokenNeededServers 清理——awaitCookie 挂起中取消时 markTokenNeeded(false) 不可达（CancellationException 先行），不清理则已删服务器永久残留（幽灵 token 提示）。
5. 探测门禁（Sse，原 diff 已含）：TokenNeeded 挂起等 token→回环重探；Unreachable 退避；Online 进事件循环。
6. 卫生项：activeSocket 改名（原 handleMuxMessageSocket 按消费方法命名）、动态 follow 日志 BuildConfig.DEBUG 门禁、session.list 拉取失败 w 级日志（原静默 emptyList）、引擎 KDoc 限界策略更新。

### 4.3 审查不修项（记录）

- attempt 计数在 TokenNeeded 等待期不重置（噪声级退避抬高）；
- 跨代 socket 窗口（旧代迟到帧经 onSessionActive 落到新代 socket）：followed 同代去重兜底，窗口极小，后果为幂等重开。

### 4.4 验证

compileDevDebugKotlin ✅；testDevDebugUnitTest **2666/2666 绿**（新增：filterFollowableSessionIds 4 例——running 无视年龄/窗口内保留/容差边界±31min/29min/updatedAt 缺席；onSessionActive 4 例——added 触发/status running=true 触发/false 不触发/activity 触发且零帧合成）。真机验证归入 #319 E2E 批次（含 >24h 会话恢复用例——Spec 轴建议）。

## 五、TokenNeeded UX + token 输入 UI + i18n（#317 收口，2026-09-04）

journal §三待办「TokenNeeded UI + token 输入 + i18n」落地：

- **extractDshToken**（Registry 顶层纯函数）：三形态解析——完整 URL（?token=/&token= 查询参数）、宿主启动行（"dsh web: http://…?token=…"，web.log 原样粘贴）、裸 token（base64url，≥20 字符宽松下限）；4 单测（含拒绝空白/多段/过短）。
- **DshTokenNeededBanner**（ui/components）：会话列表 TopAppBar 下沿细条幅（errorContainer+Key 图标，形态对齐 ServerLinkBanner），**优先于断连横幅**（token 需求比一般断连更具体，给出路而非干等）；右侧「输入令牌」TextButton。
- **DshTokenDialog**（M3 AlertDialog）：粘贴框（URL/启动行/裸 token）→ submitDshToken → exchangeToken；交换成功自动关窗（LaunchedEffect 观察 Exchanging→Idle），被拒留窗示错（isError + supportingText）；成功后连接循环 awaitCookie 自动续行（无需手动重连）。
- **SessionListViewModel**：dshTokenNeeded（map serverId in dshTokenNeededServers）+ DshTokenExchangeState（Idle/Exchanging/Rejected）+ submitDshToken/dismissDshTokenDialog；registry 直依赖先例同 unreadBadgeService（UI→data 注入既有惯例）。
- **i18n**：7 键（dsh_token_banner_text/action、dsh_token_dialog_title/message/hint/connect/rejected）×15 语言，i18n-check **PASSED（784 keys × 14）**；cancel 复用既有键。
- 三个既有 VM 测试补 registry mock + dshTokenNeededServers stub。

验证：compileDevDebugKotlin ✅；testDevDebugUnitTest **2670/2670 绿**（+4 token 提取例）。真机 UI 走查归 #319 E2E 批次。

## 六、#319 真机 E2E 全链路（2026-09-04 11:40-12:12，全门禁 PASS）

环境：真机 192.168.110.239:5555（WiFi adb）+ dsh012-a5（:3081，alpha.5 鉴权，凭据注入后 key 余额尽——LLM 轮次不可用）+ dsh011-rc2（:3082，0.1.1 无鉴权）+ 生产 :3080（0.1.2-rc.1，主靶场）。

### 6.1 门禁结果

- **G-A 探测判别**：3081 `slash=401 dot=401 → TokenNeeded` / 3080+cookie `slash=200 dot=404 → Online(V012)` / 3082 `slash=404 dot=200 → Online(V011)`——判别表三形态全中 ✅
- **G-B token 交换**：debug_token → `token exchange ok (cookie persisted)`（裸 OkHttp 修复生效）；无 token 重启直接 Online（cookie 持久化，每设备年一次语义成立）✅
- **G-C TokenNeeded UX**：横幅「此服务器需要访问令牌」+「输入令牌」渲染；token 后自动消失 ✅
- **G-D 动态 follow 补开**：宿主 RPC 造会话（信封包装 session/create+prompt）→ 新会话实时出现列表（标题=首条 prompt，onSessionAdded 补开证据）✅
- **G-E 限界 follow**：生产 440 会话连接正常，chunk 压缩行跳过计数上升（限界集 follow 在工作）；**发现并修复 subagent follow 拒收**（agent-busy：subagent 会话需 {kind:subagent} 地址——filterFollowableSessionIds 过滤 parentSessionId/origin 条目，修复后流错误 0）✅
- **G-F waterfall 提问卡全链路**（生产 3080，宿主 agent 亲自 ask）：卡渲染（对话流内）→ 真机点选 → `POST /api/$events/result success=true` → 会话解锁续跑 ✅
- **G-G Web 作答消除**（cancel 路径）：Web 端作答 → 服务端 finishRemoteEvent 对剩余客户端广播 cancel → subagent 观察卡 +11s 在/+20s 消失，logcat `QuestionRejected -> QuestionEventHandler` ✅
- **G-H 0.1.1 回归**：3082 probe V011 Online + 会话列表渲染，点式+裸 payload 路径无损 ✅

### 6.2 用户反馈三修（提问卡 UX，commit 7a85b5a6）

用户实测反馈：①卡不在主对话流 ②Web 作答后 app 卡不消除 ③样式与 OpenCode 面不统一。

1. **resolved 帧补 sessionId**（②根因）：synthesizer onCancel 合成的 question/approval resolved 帧原先无 sessionId → mapper 判 MALFORMED 静默丢弃（服务端 finishRemoteEvent 确实广播 cancel——dsh-api-gateway/lib/index.js 源码证实，架构可解）。修复：pendingWaterfalls 值扩为 PendingWaterfall(method, sessionId)，resolved 帧带 sessionId → QuestionRejected/PermissionReplied 正常路由。
2. **卡进主对话流**（①③）：DSH waterfall 提问无 tool/part 锚（QuestionAsked.tool=null → 永走 unembedded 保底 dock）。修复：embeddedQuestionByMsgId 扩展——无锚提问关联到最新可见 assistant 消息；MessageCardAssistant 加气泡尾 fallback 槽位（effectiveAnchorId==null 时，错误展示前）——与 OpenCode 锚定路径同 QuestionCard 组件/动画/容器（样式统一），随消息流滚动。

验证：用户确认「A: 已在对话流内+样式统一」；Web 作答消除 subagent 时间线+logcat 铁证；全量单测 2670 绿。

### 6.3 E2E 工程缝（commit 537f41b4）

- `debug_server_type` extra（dsh）：debug 通道新建/覆写 serverType（探测不推断 DSH，默认 OpenCode 走 SSE 404 循环）；
- **sweep 竞态修复**：冷启 FGS sweep 先于 debug 通道用旧配置连接 → Coordinator 同 id 幂等跳过新配置 → connect 前补发 ACTION_DISCONNECT（#253 同款）。

### 6.4 遗留（下批）

- >24h 老会话再激活真机用例（status/activity 补开已有单测，真机待自然发生）；
- #314-316 在 3082 的回归（本轮仅验证连接+列表，四门禁脚本未跑）；
- dsh012-a5 容器 LLM key 余额尽（Insufficient Balance）——提问链路验证移生产完成，容器 key 待充值或换 route。

## 七、#314-316 容器回归 + 模型目录修复（2026-09-04 12:16-14:30）

### 7.1 e2e 脚本容器化（ca133da3 + 后续参数化）

- DSH_E2E_PORT/SESS/DIR1/DIR2 四参化（默认=生产值不变）：端口（ensure_reverse/rpc URL/两处 am start）、既有会话锚、新建目录 fallback 链；am start 补 debug_server_type dsh。
- 容器播种：session.create ×3（/tmp、/e2e、/tmp/test-lab）+ rename（Test Lab/E2E 回归会话）+ prompt 种子消息（**0.1.1 prompt 必填 mode:"queue"**，app 源码 promptAsync 实证）→ 空 vs 有消息会话导出 0B vs 11.7KB。

### 7.2 五卡回归结果（3082 = dsh-keepalive-e2e:0.1.1-rc.2，r5 轮 4 PASS）

| 卡 | 结果 | 备注 |
|---|---|---|
| #279 导出 SAF | **PASS** | 预填 .zip + 落盘 unzip -t 通过（有消息会话） |
| #285 斜杠命令 | **PASS** | 懒建会话 + 弹层确定性命中——**#315 tap_text 回归实证** |
| #278 僵尸 Busy | **PASS** | 强杀重启 syncFromRest 播种（busy 计数） |
| #283-a2 投影帧 | **PASS** | 外部 /permission 切档 → SessionPermissionsChanged 派发 ×2 |
| #287 附件缩略图 | FAIL | 容器无附件历史数据（环境限制非代码问题；生产形态 #308 批已证） |

- **#316 adb reverse 回归实证**：执行中真机 WiFi adb 掉线（第五轮前），重连 + ensure_reverse 自动重建后全链恢复。
- **#314**：0.1.1 传输+mapper 段证据 = #308 批 G1/G2 真机 PASS（2026-09-03，当时生产 0.1.1-rc.2）；共享渲染段 = 本会话 §6.2 双端验证。容器完整复验（造 pre-existing 提问）需容器 LLM——见 7.4。

### 7.3 模型目录修复（47f1e05a，用户反馈：切换模型无智谱而 Web 端有）

- **定因（生产 0.1.2-rc.1 活体）**：session/modelCatalog value = {default, routableProviders, groups:[{id,name,models[]}], failures}——**组在 groups 数组**；providersV012 原按「顶层键=组 id」解析 → routableProviders/groups/failures 全无 models 键被跳过 → 组空 → 目录只剩 provider 名零模型。
- 修复：groups 数组优先解析（含 name），顶层键形态保留为 fallback（防 alpha.5）；测试改生产真实形态（含 zai-coding-cn 组 glm-5.3/glm-5.3-flash 回归锚）+ fallback 正测。
- **真机验证 PASS**：选择器三组齐全（DeepSeek/opencode-go/zai-coding-cn），智谱组 GLM-5.3/GLM-5.3-Flash 展示。

### 7.4 遗留

- 容器智谱注入（用户指示 glm-5.3-flash + 智谱套餐 key）：生产 key 在 keyring/launch-environment（environ 无明文），自动取得受阻——待用户提供 key 后注入（容器 settings.yaml llm-pi-ai 段 + credential），即可做 #314 的 0.1.1 容器完整复验（挂起提问→进会话渲染）。
