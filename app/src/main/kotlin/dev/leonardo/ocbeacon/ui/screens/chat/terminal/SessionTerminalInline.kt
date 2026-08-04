package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import org.connectbot.terminal.RightAltMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator

private const val TAG = "SessionTerminalInline"

/**
 * 使用 ConnectBot termlib 的 [Terminal] composable 渲染终端会话。
 *
 * termlib 内部处理的事项（不再手动实现）：
 *   - Canvas 字符网格渲染
 *   - 光标闪烁动画
 *   - IME 输入（BasicTextField + delta/去重）
 *   - 用于长按复制的 SelectionContainer 覆盖层
 *   - 双指缩放手势检测
 */
@Composable
internal fun SessionTerminalInline(
    emulator: TerminalEmulator,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val modifierManager = remember { TermlibModifierManager() }

    // 将字号强制到旧代码使用的相同 [6f, 20f] 范围。
    val initialFont = fontSizeSp.coerceIn(6f, 20f).sp
    val minFont = 6f.sp
    val maxFont = 20f.sp

    // 测量实际字形前进宽度以精确计算列数。
    val textMeasurer = rememberTextMeasurer()
    val sampleLayout = remember(textMeasurer, initialFont) {
        textMeasurer.measure(
            text = "X",
            style = TextStyle(fontSize = initialFont),
        )
    }
    val charWidthPx = sampleLayout.size.width.toFloat().coerceAtLeast(1f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier.fillMaxSize(),
            initialFontSize = initialFont,
            minFontSize = minFont,
            maxFontSize = maxFont,
            backgroundColor = Color.Black,
            foregroundColor = Color(0xFFD3D7CF),
            selectionBackgroundColor = Color(
                red = 0x4F, green = 0xC3, blue = 0xF7,
                alpha = (255 * AlphaTokens.FAINT).toInt(),
            ),
            selectionForegroundColor = Color(0xFF4FC3F7),
            // 终端模式始终持有键盘——不要等待
            // 连接，否则 IME 会在每次重连时闪烁。
            keyboardEnabled = true,
            showSoftKeyboard = true,
            focusRequester = focusRequester,
            modifierManager = modifierManager,
            rightAltMode = RightAltMode.CharacterModifier,
            onPasteRequest = onPaste,
            onTerminalTap = { /* handled by ChatTerminalView */ },
        )

        // 根据 constraints 计算 cols/rows 并通过 onResize 转发。
        val density = LocalDensity.current
        val cols: Int
        val rows: Int
        with(density) {
            val rowHeightPx = initialFont.toPx() * 1.2f
            cols = (maxWidth.toPx() / charWidthPx).toInt().coerceAtLeast(1)
            rows = (maxHeight.toPx() / rowHeightPx).toInt().coerceAtLeast(1)
        }

        LaunchedEffect(cols, rows) {
            if (BuildConfig.DEBUG) Log.d(TAG, "layout-driven resize: ${cols}x$rows")
            onResize(cols, rows)
        }
    }
}
