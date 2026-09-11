package dev.leonardo.ocbeacon.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 条目动作注册表的组合局部（#391 切片9）——与 [LocalServerUiSlots] 同构。
 *
 * 默认空注册表：未提供（预览 / 组件级测试）时不渲染任何条目贡献，不崩溃；
 * 生产由 MainActivity 注入 [ServerActionRegistry] 后经 CompositionLocalProvider 提供。
 */
val LocalServerActions = staticCompositionLocalOf { ServerActionRegistry(emptySet()) }
