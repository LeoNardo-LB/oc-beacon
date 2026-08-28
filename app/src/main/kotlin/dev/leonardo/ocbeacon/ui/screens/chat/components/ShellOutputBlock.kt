package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.spacing
import dev.leonardo.ocbeacon.ui.theme.typography

/**
 * Shell 输出 verbatim 代码块（#252 V6 三轮「半截卡」根因修复，2026-08-29）。
 *
 * 为什么不走 MarkdownContent（根因链，字节码+真机双实证）：
 * - 库的 [rememberMarkdownState] 状态流初值 = State.Loading（MarkdownStateImpl
 *   构造函数字节码实证）→ 每个新组合实例首帧必然渲染空内容，解析完成在
 *   下一拍才重组出全高——「同步解析」只保证不切后台线程，不保证首帧终高；
 * - shell 卡默认展开（#252 终审）→ 该 Loading 帧落在 ExpandReveal 补偿器
 *   的首次测量上：base=232（空 body 卡高）→ 下一拍 405（有 body）→ 每张
 *   历史卡每次组合都触发一轮「增长裁剪 + PreRenderShift 注入 + 揭示」
 *   （真机一轮 fling 23 次 EV-REVEAL 实证）；
 * - 任何一轮的揭示遍丢失（滚动 disposal / 注入异常 / 插队竞态）→ 卡永久
 *   停在 held 矮高度被 clipToBounds 裁半——用户截图「第二条消息只展示一半」。
 *
 * 本组件直接渲染 verbatim 文本（等宽 + 代码底色 + 横向滚动），**首测即终高**
 * ——闪烁与补偿窗口从构造上消灭，#241 渲染前硬约束零接触。视觉对齐
 * MarkdownContent 代码块形态（surfaceContainer 底 / 8dp 圆角 / 等宽字号），
 * 对齐 #252「TUI/web verbatim」终审。
 */
@Composable
internal fun ShellOutputBlock(
    output: String,
    modifier: Modifier = Modifier,
) {
    val density: ChatDensity = LocalChatDensity.current
    val tokens = density.typography
    val codeStyle = TextStyle(
        fontFamily = CodeTypography.fontFamily,
        fontWeight = CodeTypography.fontWeight,
        fontSize = tokens.codeFontSize,
        lineHeight = tokens.codeLineHeight,
        letterSpacing = CodeTypography.letterSpacing,
    )
    val containerColor = if (isAmoledTheme()) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        color = containerColor,
        shape = ShapeTokens.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            // 去掉尾随空行（服务器输出恒以 \n 结尾，markdown 围栏同样不显示尾空行）
            text = output.removeSuffix("\n"),
            style = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            softWrap = false,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = density.spacing.block,
                    vertical = 8.dp,
                ),
        )
    }
}
