package dev.leonardo.ocbeacon.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面插槽注册表的组合局部（#391 切片5）——通用屏幕经此读取注册表，不 import 任何
 * 具体服务器类型组件。
 *
 * 默认空注册表：未提供时（预览 / 组件级测试）不渲染任何插槽贡献，不崩溃；
 * 生产由 MainActivity 注入 [ServerUiSlotRegistry] 后经 CompositionLocalProvider 提供。
 */
val LocalServerUiSlots = staticCompositionLocalOf { ServerUiSlotRegistry(emptySet()) }
