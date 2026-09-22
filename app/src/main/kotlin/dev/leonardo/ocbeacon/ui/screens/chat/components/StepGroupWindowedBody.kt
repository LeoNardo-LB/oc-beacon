package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #427 P3：步组切片窗口化宿主。
 *
 * 只组合与「视口 ±1 屏」相交的片（子组合语义同 LazyColumn：未引用槽位
 * 本测量遍结束即弃）；窗口外以账本高度占位（纯放置空隙，零组合）。
 * 冷账本（任一片无实测）降级整体组合（现行 ε 沉降成本对齐——正确性
 * 不依赖预热，spec §Implementation Decisions）。
 *
 * **契约**（引擎不变量全部保持）：
 * - 宿主自报高度 = Σ片高 + 片间距——引擎 lastMeasuredH 即总高，H 来源
 *   从「整体沉降实测」变为「Σ实测/账本」对引擎透明（零引擎改动）；
 * - 纯放置窗口：不引入嵌套滚动、不拦截手势、不写滚动位；
 * - 片高实测即回报账本（宽度键控由调用方 [StepGroupWindowSpec.onMeasured] 落账）。
 *
 * **视口近似**：宿主 positionInRoot + 屏高（±1 屏余量下，视口与窗口的
 * 工具栏级偏差（≤数百 px）不改变成员判定——刻意不读 LazyListState.layoutInfo
 * 以避免滚动逐帧的测量失效风暴）。
 */

/** 切片窗口规格：账本查询/落账由调用方接线，宿主不感知账本本体。 */
internal class StepGroupWindowSpec(
    val sliceCount: Int,
    /** 片 i 的账本占位高（px）；冷=null。 */
    val heightOf: (Int) -> Int?,
    /** 账本全暖判定（决定窗口化 or 冷降级整体组合）。 */
    val isWarm: () -> Boolean,
    /** 片实测回报（index, heightPx, widthPx）——测量相逐片调用。 */
    val onMeasured: (index: Int, heightPx: Int, widthPx: Int) -> Unit,
)

/**
 * 窗口成员判定（纯函数）：与 [viewportTop-marginPx, viewportBottom+marginPx]
 * 相交的片闭区间；无相交/输入缺失返回 null。
 */
internal fun visibleSliceRange(
    sliceCount: Int,
    sliceTops: IntArray,
    hostTopY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    marginPx: Float,
): IntRange? {
    if (sliceCount <= 0 || sliceTops.size <= sliceCount) return null
    if (hostTopY.isNaN() || viewportTop.isNaN() || viewportBottom.isNaN()) return null
    val winTop = viewportTop - marginPx
    val winBottom = viewportBottom + marginPx
    var first = -1
    var last = -1
    for (i in 0 until sliceCount) {
        val top = hostTopY + sliceTops[i]
        val bottom = hostTopY + sliceTops[i + 1]
        if (bottom > winTop && top < winBottom) {
            if (first < 0) first = i
            last = i
        }
    }
    return if (first < 0) null else first..last
}

/** 首测默认窗：顶部 3 片（折叠行邻域 + ~2 屏），覆盖展开起点。 */
private fun defaultWindow(sliceCount: Int): IntRange =
    0..(sliceCount - 1).coerceAtMost(2)

@Composable
internal fun StepGroupWindowedBody(
    spec: StepGroupWindowSpec,
    modifier: Modifier = Modifier,
    sliceContent: @Composable (Int) -> Unit,
) {
    val screenHpx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    // 窗口成员（快照态——唯一失效信号：滚动跨片界/初放置时写，常态静止零开销）
    var windowRange by remember(spec.sliceCount) { mutableStateOf<IntRange?>(null) }
    // 片顶累计（纯量：账本/实测混合，测量遍后更新；供放置回调重算窗口）
    val sliceTops = remember(spec.sliceCount) { IntArray(spec.sliceCount + 1) }
    // 稳定槽位内容实例：subcompose(slot, content) 以内容实例判等——若直接
    // 捕获调用方 lambda，其每次重组的新实例会令宿主每个测量遍都 dispose+
    // 重建槽位（LaunchedEffect 活不过帧末：异步 markdown 解析永不启动，
    // 长文本恒 Loading 0px——真机 #427 取证定案）。rememberUpdatedState 固定
    // 槽位 lambda 身份，内容经 State 读取按需重组。
    val currentContent by androidx.compose.runtime.rememberUpdatedState(sliceContent)
    val slotContents = remember(spec.sliceCount) {
        // 单 measurable 包装：ChunkAssistantItems 是裸 for(每 render item 一个
        // 兄弟节点)——不包 Column 时 subcompose 返回 N 个 measurable，宿主
        // .first() 只测放首个 = 首组之后的内容(大文本/表格)整体消失(真机
        // #427 取证：slice0 只出 reasoning 52px，60 行表 0px 定罪)。
        List(spec.sliceCount) { idx ->
            // spacedBy(XS)：片内组间间距与未切片路径（外层 Column spacedBy XS）
            // 逐像素对齐——双轴审查 #427 指出的跨阈值视觉奇偶问题。
            @Composable {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                        dev.leonardo.ocbeacon.ui.theme.SpacingTokens.XS.dp,
                    ),
                ) { currentContent(idx) }
            }
        }
    }

    SubcomposeLayout(
        modifier = modifier.onGloballyPositioned { coords ->
            // 放置相回调：重算窗口成员，变化才写（量化失效——避免逐滚动帧重测）
            val fresh = visibleSliceRange(
                spec.sliceCount, sliceTops, coords.positionInRoot().y,
                viewportTop = 0f, viewportBottom = screenHpx,
                marginPx = screenHpx,
            )
            if (fresh != windowRange) windowRange = fresh
        },
    ) { constraints ->
        val n = spec.sliceCount
        if (n == 0) return@SubcomposeLayout layout(0, 0) {}
        val width = constraints.maxWidth
        val spacing = SpacingTokens.XS.dp.roundToPx()
        val warm = spec.isWarm()
        // 有效窗：冷降级=全片（ε 沉降成本对齐）；暖=快照窗（含首测默认）
        val range = if (warm) (windowRange ?: defaultWindow(n)) else 0..n - 1
        val sliceConstraints = Constraints(maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)
        val heights = IntArray(n)
        for (i in 0 until n) heights[i] = spec.heightOf(i) ?: 0
        // 子组合窗内片（槽位=片序号；窗外槽位本遍不引用即弃）
        val placeables = HashMap<Int, Placeable>(range.count())
        for (i in range) {
            val placeable = subcompose(i, slotContents[i]).first()
                .measure(sliceConstraints)
            heights[i] = placeable.height
            placeables[i] = placeable
            spec.onMeasured(i, placeable.height, width)
        }
        // 累计顶与总高
        var acc = 0
        for (i in 0 until n) {
            sliceTops[i] = acc
            acc += heights[i]
            if (i != n - 1) acc += spacing
        }
        sliceTops[n] = acc
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "SliceHost",
                "measure n=" + n + " warm=" + warm + " win=" + range.first + ".." + range.last +
                    " total=" + acc,
            )
        }
        layout(width, acc) {
            for (i in range) {
                placeables[i]?.placeRelative(0, sliceTops[i])
            }
        }
    }
}
