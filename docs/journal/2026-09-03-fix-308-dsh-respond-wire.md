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

## 五、踩坑记录

- **Kotlin 块注释嵌套**：doc 注释里写 `once/**always**`（想用 Markdown 加粗），其中 `/**` 开了一个**嵌套注释**（Kotlin 块注释可嵌套，与 Java 不同），把后续类体整个吞掉——症状是 :1356 Unclosed comment + 一串无关 Unresolved（readAttachment 等）。块注释内禁用含 `/*` 序列的 Markdown 强调。
- MockEngine 单测若 mock 了**错误形状的回执**（信封而非 receipt），旧实现测试照样绿——回执形状必须按服务端源码定音，不能按客户端期望。
