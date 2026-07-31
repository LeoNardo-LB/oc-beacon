package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Invisible left-edge gesture zone that opens the terminal session drawer
 * via long-press or horizontal swipe.
 */
@Composable
internal fun TerminalDrawerEdgeGesture(
    drawerState: DrawerState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(bottom = bottomPadding)
            .width(18.dp)
            .zIndex(0f)
            .pointerInput(drawerState) {
                detectTapGestures(
                    onLongPress = {
                        if (!drawerState.isOpen) {
                            coroutineScope.launch { drawerState.open() }
                        }
                    }
                )
            }
            .pointerInput(drawerState) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        if (drawerState.isOpen) return@detectHorizontalDragGestures
                        dragged += dragAmount
                        if (dragged > 2f) {
                            coroutineScope.launch { drawerState.open() }
                            dragged = 0f
                        }
                    },
                    onDragEnd = { dragged = 0f },
                    onDragCancel = { dragged = 0f }
                )
            }
    )
}
