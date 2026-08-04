package dev.leonardo.ocbeacon.ui

import kotlinx.coroutines.flow.SharingStarted

/**
 * 所有 ViewModel → UI StateFlow 的标准共享启动策略。
 *
 * 5 秒宽限期可在配置变更（旋转屏幕、切换深色模式）
 * 以及短暂退到后台期间保持上游流活跃，避免 SSE 管线
 * 出现重连风暴。
 */
val WhileSubscribed5s = SharingStarted.WhileSubscribed(5000)
