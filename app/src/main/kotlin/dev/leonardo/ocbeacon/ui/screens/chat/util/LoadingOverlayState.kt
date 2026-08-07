package dev.leonardo.ocbeacon.ui.screens.chat.util

/**
 * 统一加载蒙版显示条件。
 *
 * 蒙版覆盖消息区 + 输入栏，直到模型配置（provider catalog 加载完成）与
 * 消息加载同时就绪；[timeoutElapsed]（8s 超时）后强制揭开，避免网络慢/失败
 * 导致蒙版永久挂着（此后各区按自身状态渲染）。
 */
internal fun shouldShowLoadingOverlay(
    modelReady: Boolean,
    messagesReady: Boolean,
    timeoutElapsed: Boolean,
): Boolean = !(modelReady && messagesReady) && !timeoutElapsed

/** 蒙版最小展示时长（ms）：淡入 250 + 至少 150 稳定 + 淡出 200 = 600。 */
internal const val MIN_OVERLAY_DISPLAY_MS = 600L

/**
 * 蒙版是否可以开始淡出。
 * 蒙版一旦显示必须至少展示 [minDisplayMs] 才允许退场（防加载快时闪烁）；
 * 未就绪（[overlayTarget] = true）时永不隐藏。
 */
internal fun shouldHideOverlay(
    overlayTarget: Boolean,
    shownSinceMs: Long,
    nowMs: Long,
    minDisplayMs: Long = MIN_OVERLAY_DISPLAY_MS,
): Boolean = !overlayTarget && (nowMs - shownSinceMs >= minDisplayMs)
