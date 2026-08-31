# 忙碌双键发送区（走查 #8 方案①）（2026-09-01）

> 状态：进行中（代码完成，待真机验收）
> 关联：走查发现 #8 · 用户裁决=双键并存（Web 同款）
> 来源：模拟器走查 #8

## 背景与裁决

DSH 语义：忙碌时照常发送=自动排队（prompt mode=queue 进服务端收件箱，QueueDock 呈现，下 step 边界消费）。客户端发送键忙碌时变停止键 → 无法排队 → QueueDock 无供数。用户裁决（方案①）：忙碌且输入非空时停止键+发送键并存（发送点击即排队）；输入空时仅停止。取代 2026-08-20 的 busy 气泡菜单（立即发送/堆积消息）。

## 实现

- `SendStopAreaState.kt`（新）：按钮区状态机纯函数 seam `sendStopAreaState(isBusy, hasText, isSending)` → 可见键集（`SendStopKey.STOP/SEND`）+ 转圈变体归属（stopSpinner/sendSpinner）。TDD：先写 `SendStopAreaStateTest`（8 组合）复红（Unresolved reference）再实现转绿。
- `SendStopButton.kt`：单按钮改双键 `Row`（各 44dp 等宽，间距 `SpacingTokens.XS`）。忙碌转圈（环形进度+小停止图标）由停止键承载（沿 2026-08-17「状态表示放按钮上」）；发送键 a11y 区分 `chat_send_queued`（忙碌态）/`chat_send`/`chat_send_shell`。testTag：发送键保留 `chat-send`（androidTest ChatInputTest/ChatInteractionTest 依赖），停止键新增 `chat-stop`。气泡菜单（Popup/BusyMenuItem/BackHandler）整体移除。
- `ChatInputBar.kt`：移除 `onEnqueue` 透传；`showStop = isBusy && text.isBlank()` 计算不变。
- `ChatScreenBottomBar.kt`：移除 onEnqueue lambda（唯一调用 `viewModel.enqueuePendingMessage` 的入口）。
- 发送链路零改动：`onSend` → `sendMessage` → DshApiClient `put("mode", "queue")`（DshApiClient.kt:592）→ sendSuccess 清草稿（既有链）；session/queue 帧 → QueueDock 渲染（既有链）。

## i18n

- 新增 `chat_send_queued`（a11y「发送（排队）」）×15 语言；删除孤儿 `chat_busy_menu_*` 5 key ×15 文件（75 行）。
- 校验：官方 `bash scripts/i18n-check.sh` PASSED（761 keys × 14 languages, all consistent，exit 0；本机逐 key spawn 子进程较慢，后台跑完）。

## 验证

- 单测：`SendStopAreaStateTest` 8/8 绿（tests="8" failures="0"）。
- 全量回归：`:app:testDevDebugUnitTest --rerun`（结果数字待补）。
- 待办（委派方真机）：忙碌时双键 → 点发送 → 草稿清空 → QueueDock 出现预览 + 编辑/删除/steer。

## 后续

- 本地堆积链路失去 UI 入口 → backlog #289（refactor：拆除或恢复入口，另行裁决）。
