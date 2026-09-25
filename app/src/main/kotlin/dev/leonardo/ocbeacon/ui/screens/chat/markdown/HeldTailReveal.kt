package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay

/**
 * #437 扣留尾部降亮区（spec §2 超龄通道 / §5 阶段 B）。
 *
 * 流中扣留超 [HeldTailAgingState.REVEAL_AFTER_MS] 后可见的定高降亮纯文本区：
 * - 锁高裁剪：显示高度 = min(自然高, 锁高)，锁高每
 *   [HeldTailAgingState.HEIGHT_REFRESH_MS] 刷新一次——期间文本增长被裁剪，
 *   不触布局（高度流=低频量子，与 #435 引擎配对无冲突）；
 * - alpha 0.5 降亮 + 末尾呼吸光标——扣留内容的活性指示；
 * - 毕业（gate 放行）时扣留变短 → min 立即收缩，与正文扩张同帧，
 *   净高度变化单调不减；
 * - 扣留清空（完结/全部毕业）→ 不占位。
 *
 * [tail] 是快照原始字符（含未完标记字面，如 "*bold"）——按字面显示
 * 正是「未定案原文」语义；定案后由 Markdown 正文按最终解释渲染。
 */
@Composable
internal fun HeldTailReveal(
    tail: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val aging = remember { HeldTailAgingState(now = { android.os.SystemClock.elapsedRealtime() }) }
    var visible by remember { mutableStateOf(false) }
    var lockedPx by remember { mutableIntStateOf(-1) }
    val naturalRef = remember { intArrayOf(0) }

    // 超龄轮询：48ms 步进（与批节奏一致）；tail 清空即复位
    LaunchedEffect(tail) {
        if (tail.isEmpty()) {
            aging.update("", 0)
            visible = false
        } else {
            while (!aging.visible) {
                aging.update(tail, aging.lastNaturalHeightPx)
                visible = aging.visible
                if (!aging.visible) delay(48)
            }
            if (visible) {
                // #437 阶段 D 观测：超龄揭示时长（spec §6 真机矩阵取证）
                dev.leonardo.ocbeacon.logging.AppLogger.i(
                    "MDPilot",
                    "heldTail aged reveal heldMs=" + aging.heldForMs + " chars=" + tail.length,
                )
            }
        }
    }

    if (tail.isEmpty() || !visible) return

    val dimColor = textStyle.color.copy(alpha = textStyle.color.alpha * 0.5f)

    // 呼吸光标（inline 末尾，占位 1em×1.05em 圆角块）
    val transition = rememberInfiniteTransition(label = "srCursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(640), RepeatMode.Reverse),
        label = "srCursorAlpha",
    )
    val cursorId = "sr_cursor"
    val annotated = remember(tail) {
        buildAnnotatedString {
            append(tail)
            appendInlineContent(cursorId, " ")
        }
    }
    val density = LocalDensity.current
    val inlineContent = remember(cursorId, dimColor) {
        mapOf(
            cursorId to InlineTextContent(
                Placeholder(0.85f.em, 1.05f.em, PlaceholderVerticalAlign.TextCenter),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(dimColor.copy(alpha = cursorAlpha), RoundedCornerShape(2.dp)),
                )
            },
        )
    }

    Text(
        text = annotated,
        style = textStyle.copy(color = dimColor),
        inlineContent = inlineContent,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            // 自然高度观测（Text 不受高度约束）→ 驱动锁高状态机
            val natural = result.size.height
            naturalRef[0] = natural
            aging.update(tail, natural)
            if (aging.lockedHeightPx >= 0 && aging.lockedHeightPx != lockedPx) {
                lockedPx = aging.lockedHeightPx
            }
        },
        modifier = modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                // 自然测（高度不受限）→ 显示高 = min(自然高, 锁高)——顶部对齐，
                // 尾部增长溢出被裁（内部变化不触布局）
                val p = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
                )
                val shown = if (lockedPx >= 0) lockedPx.coerceAtMost(p.height) else p.height
                layout(p.width, shown) { p.place(0, 0) }
            },
    )
}
