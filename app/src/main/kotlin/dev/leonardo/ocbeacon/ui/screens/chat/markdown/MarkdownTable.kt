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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** #429 时间切片：超过此行数的表走行组装配（首组小保首屏，其余组大）。 */
internal const val TABLE_GROUPED_MIN_ROWS = 20

/**
 * #431:表格自然列宽跨回收缓存(模块级 LRU,主线程单写者)。
 * 键=fontSize+表格全文(内容变即失配重测);容量 24 张表(宽度数组 6-8 int,
 * 内存可忽略;键复用表内容字符串引用,无额外拷贝)。进程存活期有效——
 * 回收/离屏/重入零重测;进程重启一次性重付(48 次≈40ms/表)。
 */
private object NaturalWidthsLru : LinkedHashMap<String, IntArray>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean = size > 24
}

/** #429 时间切片：行组边界。JVM 可单测。 */
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

    // ===== #429 时间切片：大表行组装配 + 首组合恒跨帧分批 =====
    // 计算期组合/测量绑定主线程（Android UI 模型）——2s 级巨帧会冻结同帧排队的
    // spinner 动画（真机定罪）；改为每组一帧分批组合，帧间让出主线程给动画。
    // 组合完成后全部保留：滚动行为与单体版一致（区别于已回退的 v2 窗口化）。
    //
    // v3 勘误（2026-09-24 两轮真机定罪，信号窗口方案全灭）：
    // ① 子树 CompositionLocal 够不到表格——大表 >2048 字符走 async parse
    // （#428 机制），表格实际组合晚于展开计算窗口（引擎 settle 提前判定稳定，
    // H=4656 不含表格；parse 完成后 Signal 已清）；② 进程级信号同样错位
    // （首组合读初值的窗口依赖与 toggle→重组→effect 的帧序竞态）。
    // 改为**首组合恒分批**：grouped 表 stagedLimit 起步 1，stepper 每帧 +1 组，
    // 完成后全保留（stagedLimit 记忆在 content/tableNode 键上，同一表重组不重置；
    // movableContent 移回=reuse 状态=瞬显，与 B 方案协同）。
    // 滚动进入视口的大表同样分批（~136ms/17组@120Hz 渐进），替代 2.4s 单体冻结。
    val grouped = rowCount > TABLE_GROUPED_MIN_ROWS
    val tableGroups = remember(rowCount) {
        if (grouped) tableGroupBounds(rowCount) else emptyList()
    }
    val stagedLimit = remember(content, tableNode) {
        mutableIntStateOf(if (grouped) 1 else Int.MAX_VALUE)
    }

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
                .horizontalScroll(scrollState)
        ) {
            if (grouped) {
                // #429 时间切片:行组装配体(见文件尾);计算期由 stagedLimit 分批
                GroupedTableBody(
                    content = content,
                    rows = rows,
                    columnCount = columnCount,
                    tableGroups = tableGroups,
                    stagedLimit = stagedLimit,
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
                    onCellLongPress = { text ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyCellText = text
                    },
                )
                return@Box
            }
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
 * #429 A：单元格行渲染（行组装配路径专用；≤20 行小表仍走原整测路径）。
 * 与原路径逐像素对齐：背景/网格线/长按复制/可点击链接语义一致。
 */
@Composable
private fun GroupedRow(
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
 * #429 A：行组块——每组的独立 SubcomposeLayout（p1/fin 双槽）。
 * 组合与测量成本随组粒度落在宿主测量遍；组间零跨遍槽位别名（v1 教训）。
 */
@Composable
private fun TableGroupBlock(
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
    onCellLongPress: (String) -> Unit,
) {
    val cells: @Composable () -> Unit = {
        rowRange.forEach { ri ->
            GroupedRow(
                rowIdx = ri, rows = rows, columnCount = columnCount, content = content,
                headerStyle = headerStyle, bodyStyle = bodyStyle,
                headerBg = headerBg, rowBgOdd = rowBgOdd, dividerColor = dividerColor,
                pad = pad, linkColor = linkColor, uriHandler = uriHandler,
                onCellLongPress = onCellLongPress,
            )
        }
    }
    SubcomposeLayout { constraints ->
        val nRows = rowRange.last - rowRange.first + 1
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
 * #429 A：行组装配体——Column 装配组块。计算期由 [stagedLimit] 跨帧分批
 * （帧步进器在下方 LaunchedEffect：每帧 +1 组，帧间让出主线程给动画帧，
 * spinner 持续转动——组粒度摊销组合+测量两成本，优于官方 PausedPrecomposition
 * 只摊组合的朴素用法）；组合完成后全部保留：滚动行为与单体版一致
 * （已回退的 v2 窗口化教训——滚动时零丢弃零重组合）。
 */
@Composable
private fun GroupedTableBody(
    content: String,
    rows: List<TableRow>,
    columnCount: Int,
    tableGroups: List<IntRange>,
    stagedLimit: androidx.compose.runtime.MutableIntState,
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
    onCellLongPress: (String) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // 自然列宽:#429 v4——只测**首组代表行**(表头+前 8 行)。v3 定罪:全表
    // 1116 次单行测量(×4 并存表格 ≈950ms)是 Skipped~114 帧巨帧的主源,且它
    // 在 remember 里同步执行,帧步进器管不到。首组代表测(48 次≈40ms)后宽度
    // 恒定:后续组的超宽单元格走既有多行 wrap 语义(块高度自适应),零重排
    // 零闪烁(区别于 v2 宽度漂移);数据表列内容长度均匀,代表性足够。
    // #431:结果跨回收 LRU——条目回收/滚动离屏重入时 remember 重算,48 次测量
    // 每表重付(×4 表 ≈150ms)是滚动穿表冻结的成分之一;命中即零成本(主线程
    // 单写者,无需锁;键=fontSize+全文,内容变即失配自然重测)。
    val naturalWidths = remember(content, rows, columnCount, bodyStyle.fontSize, tableGroups) {
        val cacheKey = bodyStyle.fontSize.toString() + "\u0000" + content
        NaturalWidthsLru.get(cacheKey) ?: run {
            val w = IntArray(columnCount) { 0 }
            val probeCount = (tableGroups.firstOrNull()?.last ?: (rows.size - 1)) + 1
            val probeRows = rows.take(probeCount)
            probeRows.forEach { row ->
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
            NaturalWidthsLru.put(cacheKey, w)
            w
        }
    }
    // cap/fill(与原整测路径同语义)
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

    // #429 A 帧步进器:仅当处于分批初值(<组数)时运行;每帧前进一组,
    // withFrameNanos 等帧=让 choreographer 先渲染(spinner 旋转)再组合下一组
    androidx.compose.runtime.LaunchedEffect(tableGroups.size) {
        while (stagedLimit.intValue < tableGroups.size) {
            withFrameNanos { }
            stagedLimit.intValue += 1
        }
    }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.width(with(density) { totalWidthPx.toDp() }),
    ) {
        val limit = minOf(stagedLimit.intValue, tableGroups.size)
        for (gi in 0 until limit) {
            androidx.compose.runtime.key(gi) {
                TableGroupBlock(
                    rowRange = tableGroups[gi],
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
                    onCellLongPress = onCellLongPress,
                )
            }
        }
    }
}
