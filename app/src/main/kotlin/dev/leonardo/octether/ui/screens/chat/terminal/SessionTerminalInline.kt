package dev.leonardo.octether.ui.screens.chat.terminal

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
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.ui.theme.AlphaTokens
import org.connectbot.terminal.RightAltMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator

private const val TAG = "SessionTerminalInline"

/**
 * Renders a terminal session using the ConnectBot termlib [Terminal] composable.
 *
 * What termlib handles internally (no longer hand-rolled):
 *   - Canvas character-grid rendering
 *   - Cursor blink animation
 *   - IME input (BasicTextField + delta/dedup)
 *   - SelectionContainer overlay for long-press copy
 *   - Pinch-to-zoom gesture detection
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

    // Coerce font size to the same [6f, 20f] range the old code used.
    val initialFont = fontSizeSp.coerceIn(6f, 20f).sp
    val minFont = 6f.sp
    val maxFont = 20f.sp

    // Measure actual glyph advance width for accurate column calculation.
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
            // Terminal mode always owns the keyboard — do not wait for
            // connection, otherwise the IME flickers on every reconnect.
            keyboardEnabled = true,
            showSoftKeyboard = true,
            focusRequester = focusRequester,
            modifierManager = modifierManager,
            rightAltMode = RightAltMode.CharacterModifier,
            onPasteRequest = onPaste,
            onTerminalTap = { /* handled by ChatTerminalView */ },
        )

        // Compute cols/rows from constraints and forward via onResize.
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
