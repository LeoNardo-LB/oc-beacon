package dev.leonardo.ocbeacon.ui.util

import android.content.Context

/**
 * 事件时点文案解析（#391 切片8 / #396）。
 *
 * snackbar 等**异步事件**的本地化必须取事件发生时点的资源串；在组合期用
 * stringResource 固化的文案会与触发时点的语言不一致。Compose lint 的
 * LocalContextGetResourceValueCall 针对"组合期资源读取"，此处是有意例外——
 * 集中一处并附理由，避免逐点 @Suppress 与误改组合语义。
 */
@Suppress("LocalContextGetResourceValueCall")
fun Context.eventTimeString(resId: Int): String = getString(resId)
