package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER as GFMHeader
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW as GFMRow
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL as GFMCell
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.spacing
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.util.copyToClipboard
import kotlinx.coroutines.launch

/** 表示从 AST 解析出的表格行的数据类。 */
internal data class TableRow(
    val isHeader: Boolean,
    val rowIndex: Int,
    val cells: List<ASTNode>,
)

/**
 * #429 L0-②：单元格纯文本（TSV 导出用）——AST 原文截取 + 轻量行内标记剥离
 * （加粗/斜体/行内代码/链接取显示文字）。JVM 可单测。
 */
internal fun cellPlainText(content: String, cell: ASTNode): String {
    val raw = if (cell.endOffset > cell.startOffset) {
        content.substring(
            cell.startOffset.coerceIn(0, content.length),
            cell.endOffset.coerceIn(0, content.length),
        ).trim()
    } else ""
    return raw
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .trim()
}

/** #429 L0-②：整表 TSV（可直接粘贴进表格软件）。JVM 可单测。 */
internal fun tableTsv(content: String, rows: List<TableRow>, columnCount: Int): String =
    rows.joinToString("\n") { row ->
        row.cells.take(columnCount).joinToString("\t") { cellPlainText(content, it) }
    }

/** #429 L1-v2：行组虚拟化阈值——超过此行数的表才行组窗口化（小表走原整测路径，零回归面）。 */
internal const val TABLE_VIRTUALIZE_MIN_ROWS = 20

/** #429 L1-v2：行组边界——首组小（首屏快），其余组大（降低重组粒度）。JVM 可单测。 */
internal fun tableGroupBounds(rowCount: Int): List<IntRange> {
    if (rowCount <= 0) return emptyList()
    val bounds = mutableListOf<IntRange>()
    var start = 0
    var first = true
    while (start < rowCount) {
        val size = if (first) 8 else 12
        val end = minOf(start + size, rowCount) - 1
        bounds.add(start..end)
        start = end + 1
        first = false
    }
    return bounds
}

/**
 * #135（D2-L46）：表格测量缓存——探针列宽与行高在"内容、约束、列宽"
 * 未变时复用，避免流式渲染期间每次 measure 对全部单元格重复 3 遍 subcompose。
 * 注意：final 的 [androidx.compose.ui.layout.Placeable] 依赖本次 measure 约束，
 * 不可跨 measure 复用——final 一遍仍须每遍执行。
 */
private class MeasureCache {
    /** 探针测量时的 maxWidth 约束签名（变化 → 列宽缓存失效）。 */
    var probeMaxWidth: Int = -1
    var probeWidths: IntArray? = null
    /** 行高缓存依赖的 finalColWidths 签名（变化 → 行高缓存失效）。 */
    var widthsSignature: IntArray? = null
    var heights: IntArray? = null
}

/**
 * 表格组件——由最宽单元格内容驱动的统一列宽。
 *
 * 使用带 [MeasurePolicy] 的自定义 [Layout] 在单次遍历中测量所有单元格，
 * 计算每列的最大宽度，并将它们放置在统一网格上。
 * 当表格超出父容器宽度时启用水平滚动。
 */
@Composable
internal fun SimpleMarkdownTable(
    content: String,
    tableNode: ASTNode,
    style: TextStyle,
    uriHandler: UriHandler,
    linkColor: Color,
) {
    // 暗色模式下 outlineVariant @ 35% 几乎不可见（与背景融合），改用更亮的
    // outline 并提高不透明度，确保表格轮廓与网格在暗色下清晰可辨。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(
        alpha = if (isDark) AlphaTokens.MEDIUM else AlphaTokens.MUTED
    )
    val rowBgOdd = MaterialTheme.colorScheme.surfaceContainerLow.copy(
        alpha = if (isDark) AlphaTokens.MEDIUM else AlphaTokens.MUTED
    )
    val dividerColor = if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
    }
    val pad = LocalChatDensity.current.spacing.tableCell
    val shape = ShapeTokens.smallMedium
    val border = BorderStroke(1.dp, dividerColor)
    val annotator = annotatorSettings()

    val columnCount = remember(tableNode) {
        tableNode.children.maxOfOrNull { child ->
            when (child.type) {
                GFMHeader, GFMRow -> child.children.count { it.type == GFMCell }
                else -> 0
            }
        } ?: 0
    }
    if (columnCount == 0) return

    // 从 AST 收集结构化的行数据
    val rows = remember(tableNode, content) {
        val list = mutableListOf<TableRow>()
        var rowIdx = 0
        tableNode.children.forEach { child ->
            when (child.type) {
                GFMHeader -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = true, rowIndex = -1, cells = cells))
                }
                GFMRow -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = false, rowIndex = rowIdx, cells = cells))
                    rowIdx++
                }
            }
        }
        list
    }

    val rowCount = rows.size
    val scrollState = rememberScrollState()
    val minCellWidthPx = with(LocalDensity.current) { 120.dp.toPx() }.roundToInt()

    // ===== #429 L1-v2：行组虚拟化（仅大表）=====
    // v1 教训（批次七）：单宿主多槽跨测量遍别名 + measure 内写状态 = 窗口抖动/
    // 错误高度污染账本。v2 = 每组独立 SubcomposeLayout 装进 Column，组高经
    // 放置回调入账本（measure 零状态写入），窗口仅放置回调重算。
    val virtualized = rowCount > TABLE_VIRTUALIZE_MIN_ROWS
    val tableGroups = remember(rowCount) {
        if (virtualized) tableGroupBounds(rowCount) else emptyList()
    }
    val groupHeights = remember(content, tableNode) {
        mutableStateOf<Map<Int, Int>>(emptyMap())
    }
    var estRowHeight by remember(content, tableNode) { mutableIntStateOf(-1) }
    var windowGroups by remember(content, tableNode) { mutableStateOf(0..0) }
    val screenHpx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    var hostTopY by remember { mutableFloatStateOf(Float.NaN) }

    // #429 L0-②：长按复制（菜单见文件尾 Popup）
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var copyCellText by remember { mutableStateOf<String?>(null) }

    // #429 L0-②：表格退出逐字选择——240 个可选中文本单元是单帧 2501ms 排版
    // 风暴的主要成分（MIUIScout 定罪栈）；选择能力由「长按单元格=复制此格/
    // 整表 TSV」补偿（#429 用户裁决 2026-09-23）。
    androidx.compose.foundation.text.selection.DisableSelection {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(border, shape)
            .clip(shape)
    ) {
        var containerWidth by remember { mutableIntStateOf(0) }

        val headerStyle = style.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Simple)
        val bodyStyle = style.copy(lineBreak = LineBreak.Simple)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { containerWidth = it.width }
                .onGloballyPositioned { coords ->
                    hostTopY = coords.positionInRoot().y
                    // 窗口重算（StepGroupWindowedBody 同款：仅放置回调写，measure 只读）
                    if (virtualized && !hostTopY.isNaN()) {
                        val est = if (estRowHeight > 0) estRowHeight else 80
                        val tops = IntArray(tableGroups.size + 1)
                        var acc = 0
                        tableGroups.forEachIndexed { gi, gr ->
                            tops[gi] = acc
                            acc += groupHeights.value[gi] ?: est * (gr.last - gr.first + 1)
                        }
                        tops[tableGroups.size] = acc
                        var firstW = -1
                        var lastW = -1
                        for (gi in tableGroups.indices) {
                            val top = hostTopY + tops[gi]
                            val bottom = hostTopY + tops[gi + 1]
                            // L2:首窗=视口+~1 屏(2 倍屏高自根原点,扣除表顶根坐标
                            // 偏移后≈视口下 1 屏);更深组放置后经本回调渐进入窗
                            // (折叠线下不可见域渐进组合)
                            if (bottom > -screenHpx && top < 2f * screenHpx) {
                                if (firstW < 0) firstW = gi
                                lastW = gi
                            }
                        }
                        if (firstW >= 0 && windowGroups != firstW..lastW) {
                            windowGroups = firstW..lastW
                        }
                    }
                }
                .horizontalScroll(scrollState)
        ) {
            if (!virtualized) {
            val cellContent: @Composable () -> Unit = {
                rows.forEachIndexed { rowIdx, row ->
                    val cellCount = minOf(row.cells.size, columnCount)
                    val isLastRow = rowIdx == rows.lastIndex
                    repeat(cellCount) { colIdx ->
                        val cell = row.cells[colIdx]
                        val isLastCol = colIdx == cellCount - 1
                        val cellStyle = if (row.isHeader) headerStyle else bodyStyle
                        // AnnotatedString 内嵌 style 颜色，键必须含颜色：主题切换后
                        // 颜色变化 → 重建 AnnotatedString，避免文字停留旧主题颜色。
                        val cellResult = remember(content, cell, cellStyle.color, linkColor) {
                            buildClickableMarkdown(content, cell, cellStyle, annotator, linkColor)
                        }
                        val cellText = remember(cellResult) { cellResult.annotatedString.toString() }
                        Box(
                            modifier = Modifier
                                .background(
                                    when {
                                        row.isHeader -> headerBg
                                        row.rowIndex % 2 == 1 -> rowBgOdd
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    // 完整网格：列分隔线（除最后一列）+ 行分隔线（除最后一行），
                                    // 与外层 border 组成闭合边框，与 WebView 表格渲染保持一致。
                                    Modifier.drawBehind {
                                        if (!isLastCol) {
                                            drawLine(
                                                dividerColor,
                                                Offset(size.width, 0f),
                                                Offset(size.width, size.height),
                                                strokeWidth = 1f
                                            )
                                        }
                                        if (!isLastRow) {
                                            drawLine(
                                                dividerColor,
                                                Offset(0f, size.height),
                                                Offset(size.width, size.height),
                                                strokeWidth = 1f
                                            )
                                        }
                                    }
                                )
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        copyCellText = cellText
                                    },
                                )
                                .padding(horizontal = pad, vertical = if (row.isHeader) 8.dp else 6.dp)
                        ) {
                            var cellLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                            MarkdownBasicText(
                                text = cellResult.annotatedString,
                                style = cellStyle,
                                onTextLayout = { cellLayoutResult = it },
                                modifier = Modifier.clickableMarkdown(cellResult, { cellLayoutResult }, uriHandler),
                            )
                        }
                    }
                }
            }

            // #135（D2-L46）：测量缓存——原实现每次 measure 对全部单元格执行
            // 3 遍 subcompose（probe/final-pass1/final）。流式渲染时内容变化频繁
            // 但布局参数（内容、容器宽度、约束）未变，探针列宽与行高可复用，
            // 重复测量降为 1 遍 subcompose（final 的 placeable 不可跨 measure 复用，
            // 仍须每遍执行）。
            // 缓存 key 用 content + tableNode（AST 引用，内容变化时必然变化）——
            // bodyStyle 每次重组都是新 TextStyle 对象，不能作 key（否则缓存每次失效）
            val measureCache = remember(content, tableNode, columnCount) {
                MeasureCache()
            }

            SubcomposeLayout { constraints ->
                if (rows.isEmpty()) return@SubcomposeLayout layout(0, 0) {}

                val looseConstraints = Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
                // 探针列宽：约束与内容未变时复用缓存（跳过整遍 subcompose）
                val colWidths: IntArray
                if (measureCache.probeMaxWidth == constraints.maxWidth && measureCache.probeWidths != null) {
                    colWidths = measureCache.probeWidths!!
                } else {
                    val probePlaceables = subcompose("probe", cellContent).map { it.measure(looseConstraints) }
                    colWidths = IntArray(columnCount) { 0 }
                    probePlaceables.forEachIndexed { index, placeable ->
                        val col = index % columnCount
                        colWidths[col] = maxOf(colWidths[col], placeable.width)
                    }
                    measureCache.probeMaxWidth = constraints.maxWidth
                    measureCache.probeWidths = colWidths
                }

                // 动态列宽上限：cap = max(容器宽 / 列数, MIN_CELL)
                val effectiveCap = if (containerWidth > 0) {
                    maxOf(containerWidth / columnCount, minCellWidthPx)
                } else {
                    minCellWidthPx
                }
                val cappedWidths = IntArray(columnCount) { col ->
                    minOf(colWidths[col], effectiveCap)
                }

                // 填满策略：使用 containerWidth 而非 constraints.maxWidth
                val naturalWidth = cappedWidths.sum()
                val parentWidth = containerWidth
                val finalColWidths = if (naturalWidth > 0 && parentWidth > 0 && naturalWidth < parentWidth) {
                    val scale = parentWidth.toFloat() / naturalWidth.toFloat()
                    val scaled = IntArray(columnCount) { col ->
                        (cappedWidths[col] * scale).toInt()
                    }
                    val diff = parentWidth - scaled.sum()
                    for (i in 0 until diff.coerceAtMost(columnCount)) {
                        scaled[i] += 1
                    }
                    scaled
                } else {
                    cappedWidths
                }

                val finalMeasurables = subcompose("final", cellContent)
                val actualRowCount = rows.size

                // 行高：finalColWidths 未变时复用缓存（内容/列宽不变 → 行高不变）
                val rowHeights: IntArray
                if (measureCache.heights != null && measureCache.widthsSignature?.contentEquals(finalColWidths) == true) {
                    rowHeights = measureCache.heights!!
                } else {
                    val pass1Measurables = subcompose("final-pass1", cellContent)
                    val naturalPlaceables = pass1Measurables.mapIndexed { index, measurable ->
                        val col = index % columnCount
                        measurable.measure(
                            Constraints(
                                minWidth = finalColWidths[col],
                                maxWidth = finalColWidths[col],
                                minHeight = 0,
                                maxHeight = constraints.maxHeight,
                            )
                        )
                    }
                    rowHeights = IntArray(actualRowCount) { 0 }
                    naturalPlaceables.forEachIndexed { index, placeable ->
                        val row = index / columnCount
                        rowHeights[row] = maxOf(rowHeights[row], placeable.height)
                    }
                    measureCache.widthsSignature = finalColWidths.copyOf()
                    measureCache.heights = rowHeights
                }

                // 第二遍：以行高作为 minHeight 重新测量，使每个单元格 Box
                // 高度拉伸到整行高度——单元格背景因此填满整个单元格矩形，
                // 而不是只覆盖文字区域。
                val finalPlaceables = finalMeasurables.mapIndexed { index, measurable ->
                    val col = index % columnCount
                    val row = index / columnCount
                    measurable.measure(
                        Constraints(
                            minWidth = finalColWidths[col],
                            maxWidth = finalColWidths[col],
                            minHeight = rowHeights[row],
                            maxHeight = constraints.maxHeight,
                        )
                    )
                }

                val totalWidth = finalColWidths.sum()
                val totalHeight = rowHeights.sum()

                layout(totalWidth, totalHeight) {
                    var y = 0
                    for (row in 0 until actualRowCount) {
                        var x = 0
                        for (col in 0 until columnCount) {
                            val idx = row * columnCount + col
                            if (idx < finalPlaceables.size) {
                                finalPlaceables[idx].placeRelative(x, y)
                            }
                            x += finalColWidths[col]
                        }
                        y += rowHeights[row]
                    }
                }
            }
            } else {
                // #429 L1-v2：大表行组虚拟化——窗口内组=独立子组合块实测，窗外组=
                // 冻结估高占位；组高经放置回调入账本（measure 零状态写入）。
                dev.leonardo.ocbeacon.ui.screens.chat.markdown.VirtualizedTableBody(
                    content = content,
                    rows = rows,
                    columnCount = columnCount,
                    tableGroups = tableGroups,
                    headerStyle = headerStyle,
                    bodyStyle = bodyStyle,
                    headerBg = headerBg,
                    rowBgOdd = rowBgOdd,
                    dividerColor = dividerColor,
                    pad = pad,
                    linkColor = linkColor,
                    uriHandler = uriHandler,
                    containerWidth = containerWidth,
                    minCellWidthPx = minCellWidthPx,
                    windowGroups = windowGroups,
                    estRowPx = if (estRowHeight > 0) estRowHeight else 80,
                    groupHeights = groupHeights.value,
                    onGroupMeasured = { gi, h, bodyRows, bodyHeight ->
                        if (groupHeights.value[gi] != h) {
                            groupHeights.value = groupHeights.value + (gi to h)
                        }
                        // 估高冻结（表头行剔除 + ×0.75 保守：低估=滚动渐增无幽灵空隙，
                        // 高估=尾部空白段 UX 恶）
                        if (estRowHeight < 0 && bodyRows > 0 && bodyHeight > 0) {
                            estRowHeight = maxOf((bodyHeight.toFloat() / bodyRows * 0.75f).toInt(), 24)
                        }
                    },
                    onCellLongPress = { text ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyCellText = text
                    },
                )
            }
        }

        // #429 L0-②：长按单元格复制菜单（复制此格 / 复制整表 TSV）
        copyCellText?.let { cellText ->
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { copyCellText = null },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = ShapeTokens.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.table_copy_cell),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipScope.launch { clipboard.copyToClipboard("table-cell", cellText) }
                                    copyCellText = null
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                        Text(
                            text = stringResource(R.string.table_copy_table),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipScope.launch {
                                        clipboard.copyToClipboard("table-tsv", tableTsv(content, rows, columnCount))
                                    }
                                    copyCellText = null
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * #429 L1-v2：单元格行渲染（虚拟化路径专用；原整测路径的 cellContent 保持原样）。
 * 与原路径逐像素对齐：背景/网格线/长按复制/可点击链接语义一致。
 */
@Composable
private fun VirtualizedRow(
    rowIdx: Int,
    rows: List<TableRow>,
    columnCount: Int,
    content: String,
    headerStyle: TextStyle,
    bodyStyle: TextStyle,
    headerBg: Color,
    rowBgOdd: Color,
    dividerColor: Color,
    pad: androidx.compose.ui.unit.Dp,
    linkColor: Color,
    uriHandler: UriHandler,
    onCellLongPress: (String) -> Unit,
) {
    val row = rows[rowIdx]
    val cellCount = minOf(row.cells.size, columnCount)
    val isLastRow = rowIdx == rows.lastIndex
    val annotator = annotatorSettings()
    repeat(cellCount) { colIdx ->
        val cell = row.cells[colIdx]
        val isLastCol = colIdx == cellCount - 1
        val cellStyle = if (row.isHeader) headerStyle else bodyStyle
        val cellResult = remember(content, cell, cellStyle.color, linkColor) {
            buildClickableMarkdown(content, cell, cellStyle, annotator, linkColor)
        }
        val cellText = remember(cellResult) { cellResult.annotatedString.toString() }
        Box(
            modifier = Modifier
                .background(
                    when {
                        row.isHeader -> headerBg
                        row.rowIndex % 2 == 1 -> rowBgOdd
                        else -> Color.Transparent
                    }
                )
                .then(
                    Modifier.drawBehind {
                        if (!isLastCol) {
                            drawLine(
                                dividerColor,
                                Offset(size.width, 0f),
                                Offset(size.width, size.height),
                                strokeWidth = 1f,
                            )
                        }
                        if (!isLastRow) {
                            drawLine(
                                dividerColor,
                                Offset(0f, size.height),
                                Offset(size.width, size.height),
                                strokeWidth = 1f,
                            )
                        }
                    }
                )
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { onCellLongPress(cellText) },
                )
                .padding(horizontal = pad, vertical = if (row.isHeader) 8.dp else 6.dp)
        ) {
            var cellLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
            MarkdownBasicText(
                text = cellResult.annotatedString,
                style = cellStyle,
                onTextLayout = { cellLayoutResult = it },
                modifier = Modifier.clickableMarkdown(cellResult, { cellLayoutResult }, uriHandler),
            )
        }
    }
}

/**
 * #429 L1-v2：行组块——每组的独立 SubcomposeLayout（p1/fin 双槽，组间零跨遍
 * 别名）。行高统计经普通数组寄存，由 onSizeChanged 放置回调带出（measure
 * 零状态写入）。
 */
@Composable
private fun TableRowGroup(
    gi: Int,
    rowRange: IntRange,
    rows: List<TableRow>,
    columnCount: Int,
    content: String,
    finalColWidths: IntArray,
    headerStyle: TextStyle,
    bodyStyle: TextStyle,
    headerBg: Color,
    rowBgOdd: Color,
    dividerColor: Color,
    pad: androidx.compose.ui.unit.Dp,
    linkColor: Color,
    uriHandler: UriHandler,
    onGroupMeasured: (gi: Int, heightPx: Int, bodyRows: Int, bodyHeight: Int) -> Unit,
    onCellLongPress: (String) -> Unit,
) {
    // 普通寄存（非快照）：measure 写、放置回调读——不产生失效语义
    val stats = remember { arrayOfNulls<IntArray>(1) }
    SubcomposeLayout(
        modifier = Modifier.onSizeChanged { sz ->
            stats[0]?.let { rh ->
                val bodyRows = rh.size - 1
                val bodyHeight = rh.sum() - (rh.firstOrNull() ?: 0)
                onGroupMeasured(gi, sz.height, bodyRows, bodyHeight)
            }
        }
    ) { constraints ->
        val nRows = rowRange.last - rowRange.first + 1
        val cells: @Composable () -> Unit = {
            rowRange.forEach { ri ->
                VirtualizedRow(
                    rowIdx = ri, rows = rows, columnCount = columnCount, content = content,
                    headerStyle = headerStyle, bodyStyle = bodyStyle,
                    headerBg = headerBg, rowBgOdd = rowBgOdd, dividerColor = dividerColor,
                    pad = pad, linkColor = linkColor, uriHandler = uriHandler,
                    onCellLongPress = onCellLongPress,
                )
            }
        }
        val pass1 = subcompose("p1", cells).mapIndexed { index, m ->
            val col = index % columnCount
            m.measure(
                Constraints(
                    minWidth = finalColWidths[col],
                    maxWidth = finalColWidths[col],
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            )
        }
        val rowH = IntArray(nRows) { 0 }
        pass1.forEachIndexed { index, p ->
            val r = index / columnCount
            if (r < nRows) rowH[r] = maxOf(rowH[r], p.height)
        }
        stats[0] = rowH
        val finals = subcompose("fin", cells).mapIndexed { index, m ->
            val col = index % columnCount
            val r = index / columnCount
            m.measure(
                Constraints(
                    minWidth = finalColWidths[col],
                    maxWidth = finalColWidths[col],
                    minHeight = if (r < nRows) rowH[r] else 0,
                    maxHeight = constraints.maxHeight,
                )
            )
        }
        layout(finalColWidths.sum(), rowH.sum()) {
            var y = 0
            var idx = 0
            for (r in 0 until nRows) {
                var x = 0
                for (c in 0 until columnCount) {
                    if (idx < finals.size) {
                        finals[idx].placeRelative(x, y)
                    }
                    x += finalColWidths[c]
                    idx++
                }
                y += rowH[r]
            }
        }
    }
}

/**
 * #429 L1-v2：虚拟化表体——Column 装配行组块。列宽组合期一次定死
 * （TextMeasurer 纯文本单行测量，行内样式宽度差可忽略）；窗外组=估高 Spacer。
 */
@Composable
private fun VirtualizedTableBody(
    content: String,
    rows: List<TableRow>,
    columnCount: Int,
    tableGroups: List<IntRange>,
    headerStyle: TextStyle,
    bodyStyle: TextStyle,
    headerBg: Color,
    rowBgOdd: Color,
    dividerColor: Color,
    pad: androidx.compose.ui.unit.Dp,
    linkColor: Color,
    uriHandler: UriHandler,
    containerWidth: Int,
    minCellWidthPx: Int,
    windowGroups: IntRange,
    estRowPx: Int,
    groupHeights: Map<Int, Int>,
    onGroupMeasured: (gi: Int, heightPx: Int, bodyRows: Int, bodyHeight: Int) -> Unit,
    onCellLongPress: (String) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // 自然列宽：组合期一次（remember 键=内容与列数）
    val naturalWidths = remember(content, rows, columnCount, bodyStyle.fontSize) {
        val w = IntArray(columnCount) { 0 }
        rows.forEach { row ->
            row.cells.take(columnCount).forEachIndexed { col, cell ->
                val t = cellPlainText(content, cell)
                if (t.isNotEmpty()) {
                    val m = textMeasurer.measure(
                        androidx.compose.ui.text.AnnotatedString(t),
                        bodyStyle,
                    )
                    if (m.size.width > w[col]) w[col] = m.size.width
                }
            }
        }
        w
    }
    // cap/fill（与原路径同语义）
    val effectiveCap = if (containerWidth > 0) {
        maxOf(containerWidth / columnCount, minCellWidthPx)
    } else {
        minCellWidthPx
    }
    val capped = IntArray(columnCount) { minOf(naturalWidths[it], effectiveCap) }
    val natural = capped.sum()
    val finalColWidths = if (natural > 0 && containerWidth > 0 && natural < containerWidth) {
        val scale = containerWidth.toFloat() / natural.toFloat()
        val scaled = IntArray(columnCount) { (capped[it] * scale).toInt() }
        val diff = containerWidth - scaled.sum()
        for (i in 0 until diff.coerceAtMost(columnCount)) scaled[i] += 1
        scaled
    } else {
        capped
    }
    val totalWidthPx = finalColWidths.sum()

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.width(with(density) { totalWidthPx.toDp() }),
    ) {
        tableGroups.forEachIndexed { gi, gr ->
            if (gi in windowGroups) {
                androidx.compose.runtime.key(gi) {
                    TableRowGroup(
                        gi = gi,
                        rowRange = gr,
                        rows = rows,
                        columnCount = columnCount,
                        content = content,
                        finalColWidths = finalColWidths,
                        headerStyle = headerStyle,
                        bodyStyle = bodyStyle,
                        headerBg = headerBg,
                        rowBgOdd = rowBgOdd,
                        dividerColor = dividerColor,
                        pad = pad,
                        linkColor = linkColor,
                        uriHandler = uriHandler,
                        onGroupMeasured = onGroupMeasured,
                        onCellLongPress = onCellLongPress,
                    )
                }
            } else {
                val h = estRowPx * (gr.last - gr.first + 1)
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { h.toDp() }),
                )
            }
        }
    }
}
