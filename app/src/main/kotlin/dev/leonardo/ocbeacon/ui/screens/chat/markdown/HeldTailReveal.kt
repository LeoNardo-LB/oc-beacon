package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import kotlinx.coroutines.delay

/**
 * #437 扣留尾部呈现区（spec §2 超龄通道 / §5 阶段 B；2026-09-25 用户
 * 验收裁决：透明度正常化——废弃降亮分级）。
 *
 * 流中扣留超 [HeldTailAgingState.REVEAL_AFTER_MS] 后可见的定高纯文本区：
 * - 锁高裁剪：显示高度 = min(自然高, 锁高)，锁高每
 *   [HeldTailAgingState.HEIGHT_REFRESH_MS] 刷新一次——期间文本增长被裁剪，
 *   不触布局（高度流=低频量子，与 #435 引擎配对无冲突）；
 * - 文字以调用方基调直接呈现（普通消息全亮/思考块 MUTED 继承）+
 *   末尾呼吸光标——扣留内容的活性指示；
 * - 毕业（gate 放行）时扣留变短 → min 立即收缩，与正文扩张同帧，
 *   净高度变化单调不减；
 * - 扣留清空（完结/全部毕业）→ 不占位。
 *
 * [tail] 是快照原始字符（含未完标记字面，如 "*bold"）——按字面显示
 * 正是「未定案原文」语义；定案后由 Markdown 正文按最终解释渲染。
 */
/** 光标叠加位置（R2 根修缝：纯函数）。 */
internal data class CursorOffset(val x: Float, val y: Float)

/**
 * [R2 侦察定罪根修] 呼吸光标叠加位置：从末行布局结果计算——光标作为独立叠加层
 * （Box + offset），动画值变化只失效光标自身；不再用 Text inline content
 * （每帧动画被放大为整段 Text 重排+重绘 = 贴底跟随每帧 trav 13ms/draw 重放的根因）。
 *
 * @param lastLineRight 末行内容右端 x（layoutResult.getLineRight(lastLine)）
 * @param lastLineBaseline 末行基线 y
 * @param maxWidth 可用宽（行末溢出回绕保护）
 */
internal fun cursorOffsetFromLayout(
    lastLineRight: Float,
    lastLineBaseline: Float,
    cursorWidth: Float,
    cursorHeight: Float,
    cursorGap: Float,
    maxWidth: Float = Float.MAX_VALUE,
): CursorOffset {
    val x = if (lastLineRight + cursorGap + cursorWidth > maxWidth) {
        (maxWidth - cursorWidth).coerceAtLeast(0f)
    } else {
        lastLineRight + cursorGap
    }
    return CursorOffset(x = x, y = lastLineBaseline - cursorHeight)
}

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
    var agedLogged by remember { mutableStateOf(false) }
    LaunchedEffect(tail) {
        if (tail.isEmpty()) {
            aging.update("", 0)
            visible = false
            agedLogged = false
        } else {
            while (!aging.visible) {
                aging.update(tail, aging.lastNaturalHeightPx)
                visible = aging.visible
                if (!aging.visible) delay(48)
            }
            // #437 阶段 D 观测：只在首次超龄转变打一次（spec §6 真机矩阵取证）
            if (visible && !agedLogged) {
                agedLogged = true
                dev.leonardo.ocbeacon.logging.AppLogger.i(
                    "MDPilot",
                    "heldTail aged reveal heldMs=" + aging.heldForMs + " chars=" + tail.length,
                )
            }
        }
    }

    if (tail.isEmpty() || !visible) return

    // 用户验收裁决（2026-09-25）：扣留尾一律以调用方基调直接呈现——普通消息
    // 全亮（不降亮，不与正文形成「思考感」断层）；思考块继承 MUTED 半透明
    // （与已放行思考内容一致）。降亮分级（纯文字全亮/含标记 0.5）已废弃。

    // [R2 根修] 呼吸光标：独立叠加层（原 Text inline content 实现把每帧动画放大为
    // 整段 Text 重排+重绘——贴底跟随每帧 trav 13ms/draw 重放根因，framestats 定罪）。
    // 位置来自末行布局结果（onTextLayout 100ms 一更新）；动画失效只作用光标小 Box。
    val transition = rememberInfiniteTransition(label = "srCursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(AppMotion.BREATH_CYCLE), RepeatMode.Reverse),
        label = "srCursorAlpha",
    )
    var cursorOffset by remember { mutableStateOf<CursorOffset?>(null) }
    val density = LocalDensity.current

    Box(
        modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                // 自然测（高度不受限）→ 显示高 = min(自然高, 锁高)——顶部对齐，
                // 尾部增长溢出被裁（内部变化不触布局）。锁高在容器层：光标叠加
                // 一并受裁（尾溢出时光标随内容被裁，视觉语义与原 inline 一致）。
                val p = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
                )
                val shown = if (lockedPx >= 0) lockedPx.coerceAtMost(p.height) else p.height
                layout(p.width, shown) { p.place(0, 0) }
            },
    ) {
        Text(
            text = remember(tail) { buildAnnotatedString { append(tail) } },
            style = textStyle,
            overflow = TextOverflow.Clip,
            softWrap = true,
            onTextLayout = { result ->
                // 自然高度观测（Text 不受高度约束）→ 驱动锁高状态机
                val natural = result.size.height
                naturalRef[0] = natural
                aging.update(tail, natural)
                if (aging.lockedHeightPx >= 0 && aging.lockedHeightPx != lockedPx) {
                    lockedPx = aging.lockedHeightPx
                }
                // [R2] 光标叠加位置（纯函数缝：HeldTailCursorTest）
                val lr = result
                val lastLine = lr.lineCount - 1
                cursorOffset = cursorOffsetFromLayout(
                    lastLineRight = lr.getLineRight(lastLine),
                    lastLineBaseline = lr.getLineBaseline(lastLine),
                    cursorWidth = with(density) { 0.85f.em.toPx() },
                    cursorHeight = with(density) { 1.05f.em.toPx() },
                    cursorGap = with(density) { 1.dp.toPx() },
                    maxWidth = lr.multiParagraph.width,
                )
            },
        )
        // [R2] 独立叠加光标：动画失效只作用本 Box（每帧重绘 ~小方块）
        cursorOffset?.let { off ->
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(off.x.toInt(), off.y.toInt()) }
                    .size(
                        width = with(density) { 0.85f.em.toDp() },
                        height = with(density) { 1.05f.em.toDp() },
                    )
                    .background(textStyle.color.copy(alpha = cursorAlpha), RoundedCornerShape(2.dp)),
            )
        }
    }
}
