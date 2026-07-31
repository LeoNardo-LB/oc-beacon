package dev.leonardo.octether.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChatDensity {
    Normal,
    Compact;
}

@Immutable
data class HeadingStyle(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
    val alpha: Float = 1f,
)

@Immutable
data class ChatTypographyTokens(
    val bodyFontSize: TextUnit,
    val bodyLineHeight: TextUnit,
    val codeFontSize: TextUnit,
    val codeLineHeight: TextUnit,
    val tableFontSize: TextUnit,
    val h1: HeadingStyle,
    val h2: HeadingStyle,
    val h3: HeadingStyle,
    val h4: HeadingStyle,
    val h5: HeadingStyle,
    val h6: HeadingStyle,
)

@Immutable
data class ChatSpacingTokens(
    val block: Dp,
    val listItemBottom: Dp,
    val listIndent: Dp,
    val tableCell: Dp,
    val codeBlock: Dp,
    val blockQuoteHorizontal: Dp,
)

@Immutable
data class ChatBubbleTokens(
    val paddingH: Dp,
    val paddingV: Dp,
    val itemSpacing: Dp,
)

private fun typography(
    bodySize: Float,
    bodyLH: Float,
    codeSize: Float,
    codeLH: Float,
): ChatTypographyTokens {
    val body = bodySize.sp
    val bodyLine = bodyLH.sp
    return ChatTypographyTokens(
        bodyFontSize = body,
        bodyLineHeight = bodyLine,
        codeFontSize = codeSize.sp,
        codeLineHeight = codeLH.sp,
        tableFontSize = codeSize.sp,
        h1 = HeadingStyle((bodySize + 4).sp, (bodyLH + 4).sp, FontWeight.Black),
        h2 = HeadingStyle((bodySize + 3).sp, (bodyLH + 3).sp, FontWeight.Bold),
        h3 = HeadingStyle((bodySize + 2).sp, (bodyLH + 2).sp, FontWeight.SemiBold),
        h4 = HeadingStyle((bodySize + 1).sp, (bodyLH + 1).sp, FontWeight.SemiBold),
        h5 = HeadingStyle(body, bodyLine, FontWeight.SemiBold, alpha = 1f),
        h6 = HeadingStyle(body, bodyLine, FontWeight.Medium, alpha = AlphaTokens.HIGH),
    )
}

private fun spacing(
    block: Float,
    indent: Float,
    cell: Float,
    quoteH: Float,
): ChatSpacingTokens = ChatSpacingTokens(
    block = block.dp,
    listItemBottom = 2.dp,
    listIndent = indent.dp,
    tableCell = cell.dp,
    codeBlock = cell.dp,
    blockQuoteHorizontal = quoteH.dp,
)

val ChatDensity.typography: ChatTypographyTokens
    get() = when (this) {
        ChatDensity.Normal  -> typography(14f, 22f, 13f, 20f)
        ChatDensity.Compact -> typography(13f, 18f, 12f, 18f)
    }

val ChatDensity.spacing: ChatSpacingTokens
    get() = when (this) {
        ChatDensity.Normal  -> spacing(block = 2f, indent = 16f, cell = 6f, quoteH = 16f)
        ChatDensity.Compact -> spacing(block = 2f, indent = 12f, cell = 4f, quoteH = 12f)
    }

val ChatDensity.bubble: ChatBubbleTokens
    get() = when (this) {
        ChatDensity.Normal  -> ChatBubbleTokens(16.dp, 14.dp, 10.dp)
        ChatDensity.Compact -> ChatBubbleTokens(10.dp, 8.dp, 4.dp)
    }

val LocalChatDensity = compositionLocalOf { ChatDensity.Normal }
