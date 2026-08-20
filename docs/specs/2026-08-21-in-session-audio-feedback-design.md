# 会话内提示音设计（严格镜像系统通知策略）

> 日期：2026-08-21
> 状态：已确认（grilling Q1–Q12 + F1–F5 全定案），待实现
> 位置约定：active spec 置于 docs/specs/；实现并验收后移至 docs/archive/specs/ 并更新本行状态
> 涉及模块：service / AppNotificationManager / OpenCodeConnectionService / AndroidManifest
> 关联 backlog：#155

## 1. 概述

现状：用户正在查看会话 X 时，会话 X 的事件**系统通知被抑制**（SessionFocusHolder 机制，见 §2 调研），但被抑制的事件**没有任何替代反馈**——用户盯着屏幕等回复，turn 结束只能靠视觉感知，全代码库零音频/震动代码（grep 实证：仅 ChatTerminalView 用 AudioManager 调音量，无任何 Ringtone/Vibrator/SoundPool）。

本设计补上对称空缺：**被抑制掉的那声通知，转为会话内提示音（+震动），策略完全镜像系统通知**。

## 2. 调研结论（2026-08-21 代码实证）

### 2.1 "处于本会话中不发系统通知"已完整实现 ✅

**核心**：`service/SessionFocusHolder.kt`（@Singleton）维护 `activeFocus`（serverId+sessionId）与 `isAppInForeground` 两个状态。`shouldSuppress` / `shouldSuppressEvent` = **应用在前台 且 焦点精确匹配该会话**。

**状态写入**：
- 进入聊天页 `ChatScreen.kt:511-514` → `ChatViewModel.onSessionFocused()`（设置焦点 + 取消该会话既有通知 + 重置去重状态）
- 离开 `ChatScreen.kt:515-521`（DisposableEffect）→ `onSessionUnfocused()` 清焦点
- 前后台 `OpenCodeApp.kt:242-250`（ProcessLifecycleOwner）

**抑制执行——双层防御，覆盖全部 4 类事件**：

| 事件 | 服务层路由 | 管理层发射前 |
|---|---|---|
| SessionIdle（turn 完成） | OpenCodeConnectionService.kt:588 | AppNotificationManager.kt:273 |
| PermissionAsked | :662 | :621 |
| QuestionAsked | :678 | :635 |
| SessionError | :696 | :422 |
| REST 兜底问题通知 | — | :373 |

**语义细节**：仅前台抑制（按 Home 回桌面后通知照发，2026-08-16 修复注释有记录）；仅精确匹配抑制（看会话 A 时会话 B 完成照常通知）；子会话（subagent）永不发完成通知（`isChildSession`）。

### 2.2 相关既有事实

- minSdk=26：NotificationChannel 全 API 可用（读渠道配置无需版本分支）
- turn 结束权威信号：SSE `SessionIdle`（服务层已路由）；完成通知有门控 `checkNewAssistantMessage`——要求该 turn 有 assistant 文本输出（错误消息除外），含 250ms×3 重试等 reducer 收敛
- app 内已有开关：`notificationsEnabled`（总开关，默认 true）、`silentNotifications`（静默通知，切换 `opencode_tasks_silent` 渠道实现，仅影响 turn 完成类）
- **通知侧现状缺口**：SessionError 通知无任何去重——连续错误（自动重试风暴）每次都弹一条
- Manifest 无 `VIBRATE` 权限（渠道震动由系统代劳不需要；本设计自调 Vibrator 需补声明）

## 3. 需求

- **R1 会话内提示音**：处于本会话（前台+焦点匹配）时，被抑制的系统通知转为提示音+震动，**策略完全镜像系统通知**（渠道配置/铃声档/DND/app 开关）
- **R2 事件范围**：turn 结束（须有输出）、权限请求、问题提问、错误
- **R3 错误 streak**：连续错误只提示第一次；重置条件 = 该会话出现一次成功完成的 turn 或用户发出新消息
- **R4 通知侧对称修复**：SessionError 通知加同款 streak 去重（行为变更：现状每次错误都弹）
- **R5 零新增设置项**：跟随现有通知体系（总开关+静默开关+系统渠道设置）

## 4. 设计决策（grilling 定案）

| # | 决策点 | 结论 |
|---|--------|------|
| Q1 | "处于本会话中"判定 | 完全复用抑制语义：前台 且 焦点精确匹配。分屏/悬浮窗误响接受；会话列表页不响（此时该弹通知就弹通知） |
| Q2 | turn 结束门控 | 镜像通知侧"有 assistant 文本输出才响"；abort/中断不响；错误结尾 turn 不放完成音（归错误 streak，见 F3） |
| Q3 | 子会话（subagent） | turn 结束永不响（与通知口径一致） |
| Q4 | 镜像层级 | 渠道配置 + RingerMode + DND 三层全要 |
| Q5 | 总开关联动 | `notificationsEnabled` 关 → 不响 |
| Q6 | 事件范围 | turn 结束 + 权限 + 问题 + 错误都要响；错误连发只响第一声 |
| Q7 | 设置项 | 不加，后续看效果 |
| Q8 | 震动 | 完全镜像渠道行为（渠道要震就震，含自定义振动模式） |
| Q9 | 声音来源 | 完全镜像：渠道自定义铃声 ?: 系统默认通知音（`RingtoneManager.getDefaultUri(TYPE_NOTIFICATION)`）；渠道无声/低重要度则无声 |
| Q10 | 错误 streak 重置 | (b) streak 语义：连续错误只提示第一声；成功完成 turn 或**用户发出新消息**重置；服务器自动重试不重置。通知侧同步加同款去重 |
| Q11 | 去重状态隔离 | 提示音走**完全独立的去重 map**（不得污染通知去重，否则"会话内响过一声→离场后补发通知被吞"）；挂载在抑制分支内部，SSE 与 REST 兜底路径自动全覆盖 |
| Q12 | 静音叠加 | 严格镜像全部静音因素（见 §6 矩阵），任一命中即按其效果静默 |

**F 系列（镜像现状的直接推论）**：

| # | 事实 |
|---|------|
| F1 | 自动允许权限开关（`autoAllowPermissions`）开启时：权限事件无提示音亦无通知 |
| F2 | 子会话冒泡：权限/问题/错误冒泡到父会话 ID，父会话正被聚焦 → 响 |
| F3 | turn 以错误消息结尾 → 只走错误 streak 逻辑，不叠加完成音（避免同一失败响两声） |
| F4 | 音量走通知流（`USAGE_NOTIFICATION` / STREAM_NOTIFICATION），不请求音频焦点 |
| F5 | 挂载 service 层（AppNotificationManager + OpenCodeConnectionService），不依赖 UI 存活；息屏后 foreground=false → 自动不响 |

## 5. 技术设计

### 5.1 新组件：InSessionFeedbackPlayer

```kotlin
@Singleton
class InSessionFeedbackPlayer @Inject constructor(
    private val sessionFocusHolder: SessionFocusHolder,
    private val settingsRepository: SettingsDataStore,
    // Ringtone / Vibrator / NotificationManager / AudioManager 通过薄壳接口注入以便测试
) {
    /** 独立去重 map（per server::session，与通知侧 map 物理隔离，Q11） */
    private val lastPlayedBySession = ConcurrentHashMap<String, String>()

    fun playIfFocused(serverId: String, sessionId: String, type: FeedbackType) { ... }
}
enum class FeedbackType { TURN_COMPLETE, PERMISSION, QUESTION, ERROR }
```

判定顺序（短路）：focus+前台（与 `shouldSuppressEvent` 同条件）→ `notificationsEnabled` → streak 去重（仅 ERROR）→ 策略镜像管线（§5.2）→ 播放。

### 5.2 策略镜像管线（纯函数化，核心测试缝）

输入：渠道快照（importance/sound/shouldVibrate/vibrationPattern/canBypassDnd）、ringerMode、currentInterruptionFilter、app 开关。输出 **SoundPlan(soundUri: Uri?, vibrationPattern: LongArray?)**。

1. **渠道选择**：TURN_COMPLETE → `silentNotifications ? tasks_silent : tasks`；PERMISSION → permissions；QUESTION → questions；ERROR → tasks
2. **DND**：`currentInterruptionFilter` ∈ {ALL, UNKNOWN} 或 `channel.canBypassDnd()` → 放行；否则**无声无震**
3. **渠道镜像**：
   - 声音：`channel.importance >= IMPORTANCE_DEFAULT` 时 → `channel.sound ?: 系统默认通知音`；importance ≤ LOW → 无声（渠道自定义铃声优先，Q9）
   - 震动：`channel.shouldVibrate()` → `channel.vibrationPattern`（null 时系统默认单次震）
   - ⚠️ 实现时验证：`NotificationCompat.Builder.setVibrate/setDefaults` 在 O+ 渠道模式下被渠道定义覆盖（现状 AppNotificationManager.kt:270/306 的 builder 震动设置可能是无效代码）——以渠道读出的值为准，不镜像 builder 参数
4. **RingerMode**：SILENT → 无声（震动保留）；VIBRATE → 无声+按渠道震；NORMAL → 按 SoundPlan
5. **播放**：Ringtone + `AudioAttributes(USAGE_NOTIFICATION)`（F4）；Vibrator + `VibrationEffect.createWaveform(pattern, -1)`

### 5.3 错误 streak 状态机（Q10b，通知侧与提示音侧共用语义）

- per `(server, session)` 维护 `errorStreakActive`
- 错误事件到达：streak 未激活 → 提示/通知（若其他条件满足）+ 置位；已激活 → 静默
- **重置**：① 该会话出现成功完成的 turn（SessionIdle 且有正常输出）② 该会话收到新用户消息（用户主动重发）
- 进入会话（`cancelSessionNotifications`）时随去重 map 一并重置
- 通知侧同款逻辑加在 `showErrorNotification` 路径（R4）

### 5.4 挂载点（Q11：抑制分支内部）

| 位置 | 挂载 |
|---|---|
| OpenCodeConnectionService.kt:588（SessionIdle 抑制 return） | playIfFocused(TURN_COMPLETE)，复用输出门控 |
| AppNotificationManager.kt:273/373/421-422（shouldSuppressEvent 分支） | playIfFocused(对应类型) |
| OpenCodeConnectionService.kt:662/678/696（shouldSuppress return） | 同上（双挂载点幂等，独立去重 map 防双响） |

**门控改造**：`checkNewAssistantMessage` 现状"查询+写通知去重 map"一体——拆为纯查询 `computeNewAssistantMessageId`（不写）+ `markNotified`。通知路径 = 查询+写通知 map；提示音路径 = 查询+写自己的 map。

### 5.5 Manifest

新增 `<uses-permission android:name="android.permission.VIBRATE" />`（normal 权限，自调 Vibrator 必需）。

## 6. 静音矩阵（Q12 汇总，任一命中按效果执行）

| 因素 | 效果 |
|---|---|
| app 通知总开关关 | 完全静默（不响不发） |
| app 静默通知开关开 | turn 完成音无声无震（镜像 tasks_silent 渠道）；权限/问题/错误不受影响 |
| 系统渠道被用户调为无声/低重要度 | 该类事件无声 |
| 渠道震动被用户关闭 | 无震 |
| 渠道自定义铃声 | 播该铃声（Q9） |
| 铃声档=静音 | 无声（震动按渠道） |
| 铃声档=震动 | 无声+按渠道震 |
| DND（渠道无豁免） | 无声无震 |
| 息屏/离开会话/别的会话 | 不响（走既有系统通知路径） |
| auto-allow 权限开启 | 权限事件无音无通知（F1） |

## 7. 测试缝

- **策略镜像管线**：SoundPlan 纯函数单测，覆盖 §6 矩阵全组合（渠道×铃声档×DND×开关）
- **streak 状态机**：纯函数单测（连发→只响一次→成功完成重置→再响；用户发消息重置）
- **去重隔离**：提示音 map 写入不影响通知补发（Q11 场景回归）
- **播放器薄壳**：Ringtone/Vibrator 注入 fake，断言调用
- **通知侧 streak**：仿 `AppNotificationDedupTest`
- **维度 5（真机 houji）人工清单**：听声/震感/静音档/震动档/DND/渠道自定义铃声真机实测——模拟器无实际音频输出，必须真机

## 8. Out of Scope（后续看效果）

- 独立"会话内提示音"设置项（Q7 暂不加）
- 不同事件不同音效（统一镜像渠道声音，不引入音频资源文件）
- UI 层触觉反馈（Compose haptic）

## 9. 实现注意事项

- OpenCodeConnectionService.kt 属 ChatScreen 编辑协议之外，但同样遵守"编辑前 Read、编辑后 compileDevDebugKotlin"纪律
- 错误 streak 通知侧去重是**用户可感知行为变更**（连续错误从多条通知变一条），release notes 需提及
- i18n：本设计零新增用户可见文案（无设置项、无通知文案变化），无 14 语言翻译工作量
