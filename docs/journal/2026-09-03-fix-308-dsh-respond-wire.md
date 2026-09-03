# fix-308-dsh-respond-wire（2026-09-03）

> 状态：进行中（代码+单测完成；真机 E2E 待做）
> 关联：backlog **#308** · 前置取证 `docs/journal/2026-09-03-dsh-gap-recheck-wire-308.md` §二（四重证据链）
> 来源：用户「设置目标继续」→ 本目标第一项（P0）

## 一、契约定音（实现前源码重读，dsh 0.1.1-rc.2 dsh-host-apiproxy/lib/index.js）

| 面 | 契约 | 源码锚点 |
|---|------|---------|
| 请求信封 | `{type:"client-response", rpcId, result}`——**rpcId = requested 帧稳定 id**（非 approvalId） | clientResponseSchema :4096；respond impl :3726（`pendingApprovals.get(message.rpcId)`） |
| 审批载荷 | 三键 `{sessionId, approvalId, outcome∈{allowed-once, rejected}}` + 与 pending 表逐键比对 | approvalResponsePayloadSchema :678-682；:3735-3739 |
| 提问载荷 | `{sessionId, answer:{answers:[{id, selected[], custom?}]}}`；matchesQuestions 硬校验：answer.id===题 id、selected⊆选项 label 无重复、custom 省略或非空、单选+custom→selected 必空 | questionResponsePayloadSchema :694-699；matchesQuestions :1306-1326 |
| 提问取消 | **唯一被接受形态 = Err 信封**（result.ok=false + error.code="cancelled"→accepted:true）；审批取消不存在（Err 回执恒 bad-response，拒绝走 outcome="rejected"） | :3745-3754 |
| 回执 | **RpcReceipt `{accepted, reason?}` 裸对象**——无 rpcId/result，信封解码必 null（#308 根因③） | HTTP 端点 :4914-4920（`Response.json({accepted,reason})`） |

## 二、实现（6 文件）

- `DshRpcClient.kt`：`respond()` 改走新增 `postRespond()`（RpcReceipt 解析：accepted=true→success；false→DshApiError(code=reason 保留原串)）；新增 `respondError()`（Err 信封，提问取消专用）。`exchange()` 信封解码不动。
- `DshApiClient.kt`：
  - replyToPermission：三键载荷 + 信封 rpcId=`metadata["rpcId"]`（mapper :115-123 已存，回退 requestId）+ **always→allowed-once**（「始终允许」=本地规则模拟，UI savePermissionRule + PermissionAutoApprover 自动 once 重答，与映射闭环）；
  - replyToQuestion：`{sessionId, answer:{answers[]}}`——每题 id=`Question.key`（mapper :431 即 wire item.id）、label 过滤进 selected、非 label 文本进 custom、**单选+custom→selected 清空**（契约内建）；
  - rejectQuestion：改 `respondError(cancelled)`。
- `MessageApi.kt`：接口 replyToPermission 增 `metadata: Map<String,String>? = null`（DSH 回程路由用；V1/V2 忽略）+ 路由透传。
- `V1ApiClient.kt` / `V2ApiClient.kt`：override 签名补第 7 参（忽略）。
- `ChatRepositoryImpl.kt` respondPermission：从 `eventDispatcher.permissions` 内存 pending 补查 metadata（重启丢内存→null→适配层回退 permissionId）。

## 三、测试（3 文件，9 用例改/增）

- `DshApiClientTest.kt`：旧「信封回执」mock 测试**正是掩盖 bug 的形状**（用 server-response 信封包 accepted）——替换为 receipt 形状；新增 8 用例：三键+帧 rpcId、always/reject 映射、receipt 拒绝+rpcId 回退、提问 answers 数组（多选 label/custom 分离）、单选+custom 清空 selected、null question 零 HTTP、cancelled Err 信封。
- `DshRpcClientTest.kt`：两 respond 用例 mock 改 receipt 形状（请求侧信封断言保留）。
- `V1V2DialectContractTest.kt`：replyToPermission mock/verify 补第 7 参。

## 四、验证记录

- `compileDevDebugKotlin`：通过（初版失败 1 次，见 §五；修复后 clean）。
- 目标单测（DshApiClientTest/DshRpcClientTest/DshEnvelopeTest/V1V2DialectContractTest）+ **全量** `testDevDebugUnitTest --rerun`：**BUILD SUCCESSFUL**（1m28s，128+ 用例 0 失败）。
- 无文案改动 → i18n 不适用。
- **真机 E2E（待做，转 [~] 前置条件）**：dev 包装真机（小米 houji `e69a99d8`，`./scripts/debug-entry.sh` 入口）→ `adb reverse` 接 DSH 服务 → 会话内触发审批（默认 preset 下 bash 工具）→ 点「仅此一次/始终允许/拒绝」→ logcat 断言 `[Permission] replyToPermission result: success=true` + 抓包/日志确认回执 `accepted:true`；提问卡同流程（含取消）。

## 五、真机 E2E 阶段记录（2026-09-03 晚，未完结——目标暂停时点）

- **三轮失败均在设备驱动层，与修复本体无关**：run1/run2 根因=点中输入框后 uiautomator 语义树死亡（a11y 树坏死，需 pause/resume 复活）；run3 树存活但 input text 打字链仍不可靠（FATAL3 dump 显示停在会话列表；列表中已存在标题「你是 OC Beacon 仓库的真机 E2E」的会话——说明至少一次 prompt 实际送达并生成了标题，卡的是驱动脚本对发送成功的判定/后续点按）。「Permission event received」全程 0 条=从未走到审批环节。
- 产物：/tmp/e2e308/（logcat×3 轮 130MB+、FATAL dumps、drive1-3.sh、REPORT*.md）。
- **替代策略（下轮执行，零手机打字）**：
  1. **提问半边**：宿主侧（本 DSH 服务器）在「手机已打开的会话」直接调 ask_user_question——由宿主会话作对端触发问题卡，手机侧只开对应会话+点选项；宿主侧直接收到答案=wire 闭环铁证（replyToQuestion 载荷+matchesQuestions 服务端校验全真）。
  2. **审批半边**：宿主 RPC 直接向手机会话 session.prompt（绕开手机输入法）下发「run: touch /tmp/dsh308-e2e.txt」——agent 的 bash 升级请求产生审批卡，手机点「仅一次」，门禁=replyToPermission result: success=true + 宿主 ls 文件存在。
  3. 会话定位：宿主 session.list 已验证可达（loopback RPC 200，见探针）；按标题定位手机侧要打开的会话行（tap_text）。
- i18n 终验（批 1 全部新 key 后）：**PASSED，775 keys × 14 languages all consistent**。

## 九、真机 E2E 终章——双门禁 PASS（round5，2026-09-03 22:24/22:37）

> 时间线勘误（subagent 终报澄清）：脱 blank 的脚手架 prompt 由 **subagent 于 21:01 注入**（先于宿主 21:03 的问题 prompt #1）——round1 八次 dump 未命中行定因于 blank 滤除，该注入是解锁「点行进入」的必要动作。服务端终态：turns=5 completed、running=false；标记文件 READY/Q-TAPPED/G1_DONE/ALL-DONE 齐备。

**G1 提问半边**（22:24:16，pid7608，logcat 原文）：
```
[Question] replyToQuestion: id=e011b934-1131-426a-8b85-3290ef67a5b4 answers=[[蓝色]] dir=/home/leo-tkp/Documents/code/mine/oc-beacon
[Question] replyToQuestion result: id=e011b934-1131-426a-8b85-3290ef67a5b4 success=true
```
活体到达路径：问题帧→卡实时组合（蓝色 chip+提交）→点选提交→40ms 回程 success=true（信封 rpcId+{sessionId,answer:{answers:[{id,selected:["蓝色"]}]}} 通过 questionResponsePayloadSchema+matchesQuestions，RpcReceipt accepted）。

**G2 审批半边**（22:37:21-28 + 服务器侧落地）：
```
Permission event received: PermissionAsked(id=4add5f44-…)
[Permission] replyToPermission: id=4add5f44-… reply=once sid=session-f625…
Permission event received: PermissionReplied(requestId=4add5f44-…)
[Permission] replyToPermission result: id=4add5f44-… success=true
```
+ 宿主 `-rw-r--r-- /home/leo-tkp/dsh308-e2e-marker.txt 44B 22:37`——批准后升级 bash 真实执行。

**触发链定音**（复现要点）：DSH 沙箱给 bash 挂私有 /tmp（mount namespace）——沙箱内写 /tmp 不触发审批（turn4 实证 exit0 无帧）；真实升级触发=写**沙箱外真实路径**（家目录非仓库区）→ workspace-write 拒绝 → 工具带更高权限重试 → approval/requested（三旋钮经 /permission 命令切换：preset+sandbox+approval/policy=ask 三帧齐落）。

**双路径行为差异（#314 证据）**：活体到达渲染 ✓ / pre-existing 不渲染 ✗（21:03 挂起三次复现无卡）。

**工具链副产物（#315/#316）**：subagent 发现 tap_text IFS='[],' 解析在 "][ 处产空段→坐标系统性偏移（修正版 /tmp/e2e308b/g1.py，e2e-acceptance-dsh.sh 同患）；WiFi adb daemon 重启会静默拆 adb reverse 隧道（长程 E2E 需每阶段探活重挂）。终版产物 /tmp/e2e308b/REPORT-final.md（18 截图 + drive1-4 脚本日志 + 181 万行连续 logcat）。

## 八、#314 立案证据（E2E 副产物，2026-09-03 晚）

- 时间线：21:03:47 问题帧活体到达（app 在列表页，全局订阅入库 QuestionEventHandler）→ 21:16 冷启重放入库 → 21:18 进会话 / 21:41 全列表滚动 / 21:43 退重进，**三次均无 QuestionCard 组合**（dump 无选项 chip/提交钮，仅消息流 run_code 参数文本含「蓝色」——round2 误点的正是这段非可点文本）。
- 旁证：DSH listPendingQuestions 恒空（stub）；重进会话时 mux 无未决帧重放（logcat 无新增 Question asked 行）——「开流即重放」只发生在冷启订阅。
- 解锁手段（实证）：宿主 session.cancel 取消卡死轮次。
- 活体到达路径待 round5 验证（宿主 22:05 重注入，手机停驻会话内）。

## 七、路线级裁决记录（2026-09-03 用户定规，「能力→容器」映射）

- **原则**：DSH 服务端能力适配到 app 时，UI 形态一律落**我们自己的既有容器**，禁照搬 DSH Web 布局。用户举例：DSH 的 goal/todolist 在消息框上方、子代理/后台任务在面包屑——我们全部进 **FAB 菜单**（ChatFabMenu #192 体系：TODO/AGENT/GOAL/SHELL 四入口→自有 sheet）。
- **批 1 审计**：①GoalSheet 第四钮 ②CompactionCard ③ConfirmDialog ④长按手势（自有 SendKey 双键体系）均为自有形态 ✓；④的呈现面（入队展示）与旧 QueueDock 属照搬 Web dock——已由 #313 纠正；⑤TurnMaxTokensCard 为自有 M3 卡片族（RetryBanner/SessionErrorCard 同族），流内内容不属面板类，维持流内。
- **#313 落地（ea5c2f06）**：QUEUE 成为 FAB 第五入口（Schedule 图标+计数角标）→ QueueSheet（QueueDock 行为全量迁移：三动作/只读/编辑态/steer 门控）；QueueDock.kt 删除；i18n queue_title/queue_empty ×15（法语撇号转义踩坑一次，已 amend）。
- **对 #310-#312 的约束**：Plan 模式=自有命令/chip 形态；deliverables=自有文件 chip；轨迹台账=自有轮次卡展开；归档/行菜单/左滑=既有列表形态——实现前逐一对照，凡「对位 Web 组件」的描述仅指能力语义，不指布局。

## 六、踩坑记录

- **Kotlin 块注释嵌套**：doc 注释里写 `once/**always**`（想用 Markdown 加粗），其中 `/**` 开了一个**嵌套注释**（Kotlin 块注释可嵌套，与 Java 不同），把后续类体整个吞掉——症状是 :1356 Unclosed comment + 一串无关 Unresolved（readAttachment 等）。块注释内禁用含 `/*` 序列的 Markdown 强调。
- MockEngine 单测若 mock 了**错误形状的回执**（信封而非 receipt），旧实现测试照样绿——回执形状必须按服务端源码定音，不能按客户端期望。
