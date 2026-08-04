# 通知系统优化设计

> 日期：2026-06-17
> 状态：已确认，待实现
> 涉及模块：service / ui-screens-chat / data-notification

## 1. 概述

优化 OC Remote 的事件通知系统，解决 4 个用户体验问题：前台仍弹通知、进入会话后通知残留、通知内容不对、同一会话消息不聚合。

### 现状问题

| # | 问题 | 现状根因 |
|---|------|---------|
| ① | 正在看某会话时，Agent 完成回复仍弹通知 | `processEvent()` 无前台/当前会话判断 |
| ② | 点击通知进入会话后，其他类型通知残留 | 代码中零处 `cancel()` 调用 |
| ③ | 通知标题是固定文案"响应就绪"，内容是 session.title | `showTaskCompleteNotification` 硬编码 |
| ④ | 同一会话消息不聚合，各通知独立散开 | 按服务器 Group 分组，无 MessagingStyle |

## 2. 需求

1. **前台抑制**：App 在前台且正在查看会话 X 时，会话 X 的 TaskComplete 通知被抑制。其他会话照常通知。
2. **进入取消**：进入某会话时，取消该会话的所有类型通知（TaskComplete/Permission/Question/Error）。
3. **内容修正**：TaskComplete 通知标题改为会话名称，内容改为用户发送的消息（非 Agent 回复）。
4. **消息聚合**：同一会话的多条消息聚合到一条通知内（MessagingStyle）。

## 3. 设计决策（已确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 抑制范围 | 仅当前会话页面抑制 | 与微信/Telegram 一致，其他会话仍通知 |
| 抑制类型 | 仅 TaskComplete | Permission/Question/Error 需要用户操作，不可抑制 |
| 取消范围 | 进入会话取消所有类型 | 清理通知栏残留 |
| 新策略适用类型 | 仅 TaskComplete | 其他类型是操作类通知，不适合 MessagingStyle |
| 前台感知方案 | SessionFocusHolder (新增 Singleton) | 职责分离，可测试，符合现有架构 |
| MessagingStyle 显示 | 最近 5 条用户消息 | 简单可靠，无需持久化状态 |
| serverId 维度 | activeFocus 包含 (serverId, sessionId) pair | 避免不同服务器同 sessionId 误判 |
| TaskComplete 类型标签 | conversationTitle 加前缀："响应就绪 · 会话名" | MessagingStyle 不丢失类型语义，折叠态一眼可见 |

## 4. 架构设计

### 4.1 新增组件：SessionFocusHolder

```kotlin
data class SessionFocus(
    val serverId: String,
    val sessionId: String
)

@Singleton
class SessionFocusHolder @Inject constructor() {
    /** 当前正在查看的会话（serverId + sessionId），null = 不在任何会话页 */
    val activeFocus = MutableStateFlow<SessionFocus?>(null)

    /** App 是否在前台 */
    val isAppInForeground = MutableStateFlow(false)

    fun setActiveFocus(serverId: String?, sessionId: String?) {
        activeFocus.value = if (serverId != null && sessionId != null) {
            SessionFocus(serverId, sessionId)
        } else null
    }

    fun setAppInForeground(foreground: Boolean) {
        isAppInForeground.value = foreground
    }

    /** 是否应抑制该会话的 TaskComplete 通知 */
    fun shouldSuppress(serverId: String, sessionId: String): Boolean {
        val focus = activeFocus.value ?: return false
        return isAppInForeground.value &&
               focus.serverId == serverId &&
               focus.sessionId == sessionId
    }
}
```

**依赖注入**：通过 Hilt `@Singleton` + `@Inject` 注入到 `OpenCodeConnectionService` 和 `ChatViewModel`/`ChatScreen`。

### 4.2 需求 ① — 前台抑制流程

```
ChatScreen 进入 → SessionFocusHolder.setActiveFocus(serverId, sessionId)
ChatScreen 离开 → SessionFocusHolder.setActiveFocus(null, null)

App onResume → SessionFocusHolder.setAppInForeground(true)   ← ProcessLifecycleOwner
App onPause  → SessionFocusHolder.setAppInForeground(false)

Service.processEvent(SessionIdle):
    if (sessionFocusHolder.shouldSuppress(server.id, sessionId)) return
    ...原有 checkNewAssistantMessage + showTaskCompleteNotification 逻辑
```

**ProcessLifecycleOwner 注册**：在 `App.kt`（Application）的 `onCreate` 中注册 `DefaultLifecycleObserver`，`ON_START` → foreground=true，`ON_STOP` → foreground=false。

**仅抑制 TaskComplete**：`processEvent` 中只有 SessionIdle 分支加入 shouldSuppress 检查。PermissionAsked/QuestionAsked/SessionError 分支不变。

### 4.3 需求 ② — 进入会话取消通知

```kotlin
// ChatScreen LaunchedEffect (进入时)
LaunchedEffect(sessionId) {
    appNotificationManager.cancelSessionNotifications(serverId, sessionId)
    sessionFocusHolder.setActiveFocus(serverId, sessionId)
}

// ChatScreen DisposableEffect (离开时)
DisposableEffect(sessionId) {
    onDispose {
        sessionFocusHolder.setActiveFocus(null, null)
    }
}
```

**cancelSessionNotifications 实现**：

```kotlin
fun cancelSessionNotifications(
    notificationManager: NotificationManager,
    serverId: String,
    sessionId: String
) {
    // 取消 4 种 typeOffset 的通知
    for (offset in listOf(0, 1000, 2000, 3000)) {
        notificationManager.cancel(eventNotificationId(serverId, sessionId, offset))
    }
    // 不取消 server group summary（可能还有其他会话的通知）
}
```

**触发时机**：无论通过通知点击 deep-link 进入，还是从会话列表手动进入，ChatScreen 的 LaunchedEffect 都会执行。与 setActiveFocus 同步。

### 4.4 需求 ③④ — TaskComplete 改用 MessagingStyle

#### 通知结构变化

```
当前：                          改为 MessagingStyle：
┌──────────────────┐           ┌────────────────────────────┐
│ 响应就绪          │           │ 响应就绪 · 会话名称          │
│ session.title    │           │ ────────────────────────── │
│                  │           │ 你: 用户消息1               │
│                  │           │ 你: 用户消息2               │
│                  │           │ 你: 用户消息3 (最新)         │
└──────────────────┘           └────────────────────────────┘
```

#### showTaskCompleteNotification 重构

```kotlin
suspend fun showTaskCompleteNotification(
    context: Context,
    notificationManager: NotificationManager,
    server: ServerConfig,
    sessionId: String
) {
    val (sessionTitle, _) = getSessionInfo(sessionId)
    val displayName = sessionTitle?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.notification_new_session)

    // 类型前缀 + 会话名，保留"响应就绪"语义
    val typeLabel = context.getString(R.string.notification_response_ready)
    val conversationTitle = "$typeLabel · $displayName"

    val userMessages = findLatestUserMessages(sessionId, limit = 5)

    val style = NotificationCompat.MessagingStyle("你").apply {
        this.conversationTitle = conversationTitle
        for (msg in userMessages) {
            addMessage(msg.text, msg.timestamp, "你" as CharSequence)
        }
    }
    // 如果没有用户消息，fallback 到 BigTextStyle 显示会话名
    val effectiveStyle = if (userMessages.isEmpty()) {
        NotificationCompat.BigTextStyle()
            .setBigContentTitle(conversationTitle)
            .bigText(context.getString(R.string.notification_new_message))
    } else style

    val pendingIntent = createSessionPendingIntent(context, server, sessionId, sessionId.hashCode())
    val silent = settingsRepository.silentNotifications.first()
    val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
    val notifId = eventNotificationId(server.id, sessionId, 0)

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setStyle(effectiveStyle)
        .setSubText(server.displayName)
        .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        .setGroup("server_${server.id}")

    if (!silent) {
        builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 500))
    }

    notificationManager.notify(notifId, builder.build())
    showServerGroupSummary(context, notificationManager, server)
}
```

#### 用户消息提取算法

```kotlin
data class UserMessagePreview(
    val text: String,      // 截断后的文本
    val timestamp: Long    // 消息创建时间（毫秒）
)

private fun findLatestUserMessages(sessionId: String, limit: Int): List<UserMessagePreview> {
    val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return emptyList()
    val partsMap = eventDispatcher.parts.value

    // 筛选 User 消息，提取非 synthetic 文本
    val previews = sessionMessages
        .filterIsInstance<Message.User>()
        .mapNotNull { userMsg ->
            val parts = partsMap[userMsg.id] ?: return@mapNotNull null
            val text = parts
                .filterIsInstance<Part.Text>()
                .firstOrNull { it.synthetic != true && it.ignored != true && it.text.isNotBlank() }
                ?.text
                ?: return@mapNotNull null  // 跳过无有效文本的消息
            UserMessagePreview(
                text = text.take(100) + if (text.length > 100) "…" else "",
                timestamp = userMsg.time.created
            )
        }

    // 取最近 limit 条
    return previews.takeLast(limit)
}
```

**关键点**：
- tool result 不是 `Message.User`，无需额外排除
- `Part.Text.synthetic == true` 的系统注入被过滤
- `Part.Text.ignored == true` 的被过滤
- 每条消息截断到 100 字符
- `takeLast(5)` 取最近 5 条

## 5. 文件改动清单

| 文件 | 操作 | 改动内容 |
|------|------|---------|
| `service/SessionFocusHolder.kt` | **新增** | SessionFocus data class + SessionFocusHolder Singleton |
| `service/AppNotificationManager.kt` | **修改** | showTaskComplete → MessagingStyle；新增 findLatestUserMessages() + cancelSessionNotifications()；新增 UserMessagePreview data class |
| `service/OpenCodeConnectionService.kt` | **修改** | 注入 SessionFocusHolder；processEvent SessionIdle 分支加 shouldSuppress 检查 |
| `ui/screens/chat/ChatScreen.kt` | **修改** | LaunchedEffect: cancelNotifications + setActiveFocus；DisposableEffect: 清除 focus |
| `App.kt` (Application) | **修改** | 注册 ProcessLifecycleOwner → setAppInForeground |
| `di/NetworkModule.kt` 或相关 DI Module | **修改** | SessionFocusHolder 绑定（如需） |

## 6. 边界情况

| 情况 | 处理 |
|------|------|
| session.title 为 null | MessagingStyle conversationTitle fallback "新会话" |
| 用户消息为空（图片/文件引用） | fallback BigTextStyle "新消息" |
| 通知点击→ChatScreen 创建竞态（几毫秒） | 不处理，影响极小 |
| 多服务器同 sessionId | activeFocus 使用 (serverId, sessionId) pair 区分 |
| App 重启后 SessionFocusHolder 状态 | 正常重置（默认后台+无活跃会话） |
| 用户消息超过 100 字符 | 截断 + "…" |
| MessagingStyle 超过 5 条 | takeLast(5) 只保留最近 5 条 |

## 7. 不变的部分

以下明确不在本次改动范围：
- **前台常驻通知**（PERSISTENT_NOTIFICATION_ID = 1001）：完全不变
- **Permission/Question/Error 通知**：内容、channel、振动模式不变
- **Notification Watchdog**（5 秒轮询恢复前台通知）：不变
- **子会话冒泡机制**：不变
- **checkNewAssistantMessage 去重逻辑**：不变
- **Notification Channel 配置**（4 个 channel）：不变
- **server group summary**：不变（跨会话折叠保留）

## 8. 测试策略

### 单元测试

| 测试目标 | 测试内容 |
|---------|---------|
| `SessionFocusHolder.shouldSuppress` | 前台+同会话→true；前台+异会话→false；后台→false；无活跃→false |
| `SessionFocusHolder.setActiveFocus` | 设置/清除 activeFocus 状态 |
| `findLatestUserMessages` | 正常提取；synthetic 过滤；空消息跳过；截断；takeLast(5) |
| `cancelSessionNotifications` | 4 个 typeOffset 全部 cancel 调用 |
| MessagingStyle 构建 | 有消息→MessagingStyle；无消息→BigTextStyle fallback |

### 测试基础设施
- JUnit 4 + MockK（Mock EventDispatcher 的 messages/parts StateFlow）
- Turbine（测试 StateFlow）

### 手动验证
- 模拟器场景 1：在会话 A 页面，会话 A 的 Agent 完成 → 无通知
- 模拟器场景 2：在会话 A 页面，会话 B 的 Agent 完成 → 有通知
- 模拟器场景 3：从通知点击进入会话 → 通知栏该会话通知消失
- 模拟器场景 4：多轮对话后查看通知 → MessagingStyle 显示最近 5 条用户消息

## 9. 数据流总结

```
                          ┌─────────────────────┐
                          │  SessionFocusHolder  │
                          │  (新增 Singleton)    │
                          │  activeFocus         │
                          │  isAppInForeground   │
                          └──────┬──────────────┘
                                 │ shouldSuppress()
    ┌────────────────────────────┼────────────────────────┐
    │                            │                        │
    ▼                            ▼                        ▼
ChatScreen               App.kt (Lifecycle)      Service.processEvent()
进入: setActiveFocus      ON_START: fg=true       SessionIdle:
      cancelNotifs        ON_STOP:  fg=false        if shouldSuppress → return
离开: setActiveFocus(null)                         else → showTaskCompleteNotif
                                                     ↓
                                          ┌──────────────────────┐
                                          │ AppNotificationManager│
                                          │ showTaskComplete:     │
                                          │   MessagingStyle      │
                                          │   findLatestUserMsgs  │
                                          │ cancelSessionNotifs   │
                                          └──────────────────────┘
```
