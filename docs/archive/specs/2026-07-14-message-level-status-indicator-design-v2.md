# 消息级状态指示器设计 v2

> 日期：2026-07-14（v2 修订）
> 状态：已批准，实现中

## 核心变更（vs v1）

v1 的"等待回复"状态（outline 圆环）**移除**。用户消息指示器**只关心送达状态**：

| v1 | v2 |
|----|-----|
| Queued / Waiting / Completed | Sending / Sent / Failed |
| 无乐观消息 | 发送时立即插入本地乐观消息 |
| 无重试能力 | Failed 显示重试按钮 |
| QUEUED 与圆环互换 | QUEUED 独立徽章，与送达进度无关 |

## 用户消息状态

| 状态 | 触发条件 | 视觉 | 数据源 |
|------|---------|------|--------|
| Sending | `sendParts` 调用中 | 圆环 primary 旋转 | 本地 `_pendingMessages` |
| Sent | API 成功 + SSE message_updated 回传 | 无指示器 | 乐观消息被真实消息替换 |
| Failed | API 异常 | 错误图标 + 重试按钮 | 本地 `_pendingMessages` |

QUEUED 徽章独立显示（右对齐），不影响圆环。

## 乐观消息流程

```
用户点击发送
  → 创建乐观 Message.User（临时 ID），插入 _pendingMessages
  → 圆环旋转（primary）
  → 调用 promptAsync API
      ├── 成功 → 等待 SSE message_updated
      │   → 真实 user 消息出现在 sessionMessages → 移除对应乐观消息
      │   → 如果 SSE 延迟，乐观消息保留（内容可见）
      └── 失败 → 乐观消息标记为 Failed → 显示重试按钮
          → 用户点击重试 → 重新创建 sendParts（内容从 Failed 消息提取）
```

替换策略：FIFO——当 sessionMessages 中出现新的 user 消息（ID 不在已知集合中），移除最旧的 pending 乐观消息。

## assistant 消息（不变）

| 状态 | 触发条件 | 统计栏 | 圆环 |
|------|---------|--------|------|
| 回复中 | `time.completed == null` | `[时间] [Agent] [圆环]` | primary 旋转 |
| 已回复 | `time.completed != null` | `[时间] [Agent] [Provider+Model] [时长] [Copy]` | 消失 |

## SSE 管道加固

`messageListState` combine 块添加 try-catch，防止类型转换异常导致流崩溃。

## 移除项

- 顶部进度条（已完成）
- `UserMsgStatus.Waiting` 状态
- `calculateAllUserMsgStatuses` 中的 FSM 状态推导
- Assistant token 统计（已完成）
