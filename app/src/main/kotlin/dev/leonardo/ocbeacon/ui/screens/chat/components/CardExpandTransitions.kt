package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment

/**
 * 卡片展开/收起统一过渡（2026-08-30 用户裁决：统一顶边垂直揭幕）。
 *
 * 出厂默认的 spring（StiffnessMediumLow 无弹跳）+ 淡入淡出**原样保留**，仅把
 * 揭幕/滑移锚点钉在顶边、纯垂直（expandIn 出厂默认是 BottomEnd 对角揭幕，
 * 观感随内容宽高比漂移——宽扁卡读作左右、长条卡读作上下；Top 锚定后所有
 * 卡片一致「从上到下」）。
 *
 * 所有卡片展开面共用；调方向/时长只改本文件，不动调用点。
 */
val CardExpandEnterTransition: EnterTransition =
    fadeIn() + expandVertically(expandFrom = Alignment.Top)

val CardExpandExitTransition: ExitTransition =
    shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
