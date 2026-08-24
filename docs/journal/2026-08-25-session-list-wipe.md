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
