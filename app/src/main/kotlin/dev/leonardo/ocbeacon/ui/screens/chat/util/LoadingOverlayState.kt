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

/** 蒙版淡入期时长（ms）—— 就绪后至少等淡入完整播完才淡出（淡入期内就绪 → 直接进淡出期）。 */
internal const val OVERLAY_FADE_IN_MS = 300L

/**
 * 蒙版是否可以开始淡出。
 * 蒙版一旦显示，至少展示淡入期 [minShownMs] 才允许退场（保证淡入动画完整播完）；
 * 未就绪（[overlayTarget] = true）时永不隐藏。
 */
internal fun shouldHideOverlay(
    overlayTarget: Boolean,
    shownSinceMs: Long,
    nowMs: Long,
    minShownMs: Long = OVERLAY_FADE_IN_MS,
): Boolean = !overlayTarget && (nowMs - shownSinceMs >= minShownMs)
