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
