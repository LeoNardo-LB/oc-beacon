# 2026-08-25：#218 session.deleted 后会话列表全空——定因与修复

## 用户报告

从对话界面退到会话列表，列表无任何内容（Empty directory），疑似连接丢失。

## 排查链（排除法收敛）

| 检查 | 结果 |
|------|------|
| 服务器健康 + 会话存量 | 200 OK，50 会话仍在（连接未断） |
| adb reverse / app 进程 | 均正常 |
| 列表下拉刷新 | 不发任何 REST 请求（仅权限轮询）——列表数据源是内存态，非 REST 直读 |
| 简单进出会话 | 不复现 |
| REST 删除任一会话 | 必现：session.deleted SSE 到达即列表全空 |

## 根因

SessionEventHandler.handleSessionDeleted（2026-08-16 F6 泄漏清理引入）：

- 原代码：`_serverSessions.update { it.values.removeAll { v -> v.contains(sessionId) } }`
- values.removeAll 的谓词作用于元素（Set 本身）：只要服务器的会话 id 集合包含被删 id，整个集合被移除——而非仅移除该 id
- 下游：SessionListStateBuilder:41 过滤条件 `it.id in serverSessionIds` → 集合空 → 列表 0 项 → Empty directory
- 触发面：任意删除路径（app 内删除、E2E 清理、他端/服务器删除）都广播 session.deleted；#217 E2E 收尾 DELETE divider-e2e 即埋雷——用户随后退回列表看到空列表的时间线完全吻合
- 连带：loadSessions 的 _isLoading 无 try/finally——协程取消时卡 true → refreshSessions 永久被挡 → 下拉刷新也不发请求（观察到的刷新无效）

## 修复

1. handleSessionDeleted：mapValues 仅移除单 id + filterValues 清空集（F6 防泄漏意图保留）
2. loadSessions：try/finally 兜底 _isLoading 复位

## 验证

- 单测：新增回归 SessionDeleted keeps other sessions in serverSessions map #218（修复前必败）；全量绿
- 真机（fix build）：REST DELETE 任一会话（204 + SSE 广播实测）→ 列表完整保留；进会话→BACK→列表完整

## 关联

- #217 E2E 清理（DELETE divider-e2e）是用户命中该雷的直接触发器；bug 本身自 2026-08-16 F6 清理起就存在

## #219 V2 压缩失败静默（同日二报）

用户报告「压缩时分割线一闪而过，退出重进才看到实际压缩内容」。logcat 定音：06:26:47 CompactionStarted → 715ms 后 session.compaction.failed（provider Console Go 上游端点不可用）——该次压缩实为失败，非 UI bug；重进所见「已压缩」含旧记录与失败记录的误导性渲染。

### 四项缺陷与修复

1. 失败零反馈：V2 HTTP 秒回受理（steer），失败只从 SSE 到达；V1 的 HTTP 失败回调在 V2 永不触发 → CompactionEnded+error 字段 → SessionNextEventHandler.compactionFailures 广播流 → ChatScreen snackbar（chat_session_compact_failed + 服务器原因前 80 字符）
2. 失败消息伪装成功：V2Mappers 对 status=failed 的 compaction 消息无标记 → Part.Compaction+failed 字段 → CompactionCard 失败分割线（错误标签+错误色）；wire 契约矩阵同步（failed 字段登记）
3. started 的 messageId 读 messageID 但实测字段为 inputID（探针 payload 实证）→ 恒空 → 消息流内对位失效 → 勘误为 inputID 优先
4. 失败零刷新：失败分割线要重进才出现 → ChatViewModel init collect compactionFailedEvent → refreshMessages（与成功路径一致）

### 验证

- 单测：failed 广播（error 非空 emit / 空不 emit）×2 + wire 契约 + 全量 1933 绿
- 真机：provider 恢复后成功链路回归（进行中分割线→snackbar→新分割线会话内直接出现，无需重进）；失败记录（06:26:47）与成功记录（06:36:41）同屏——Failed to compact session（红）与 Context compacted 各自正确标注
