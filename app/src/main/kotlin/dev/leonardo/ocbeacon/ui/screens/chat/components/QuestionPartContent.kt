package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.ui.screens.chat.util.QHistItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.QuestionParser

/**
 * 历史 Part.Question 的可折叠卡片。
 * 头部：[?] "提问" + 问题摘要。
 * 展开：完整问题文本 + 用户已选答案（若有）。
 *
 * opencode 的 question 字段可能是纯文本，也可能包含带 question + answers
 * 的结构化 JSON。此 composable 同时处理这两种情况。
 */
@Composable
internal fun CollapsibleQuestionPart(question: String) {
    var expanded by remember { mutableStateOf(false) }
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    // 解析问题：纯文本或包含答案信息的嵌入 JSON
    val parsed = remember(question) {
        QuestionParser.parseQuestionContent(question)
    }

    androidx.compose.material3.Surface(
        shape = dev.leonardo.ocbeacon.ui.theme.ShapeTokens.smallMedium,
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.XS.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.question),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = parsed.displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = AlphaTokens.FAINT)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 20.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
                    Text(
                        text = parsed.displayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                    // 若用户有答案则显示
                    parsed.answers.forEach { answer ->
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = accentColor.copy(alpha = AlphaTokens.SELECTED),
                            shape = androidx.compose.material3.MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (parsed.isMultiple) Icons.Default.CheckBox else Icons.Default.RadioButtonChecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = accentColor
                                )
                                Text(
                                    text = answer,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accentColor
                                )
                            }
                        }
                    }
                    // 若 JSON 解析发现额外字段则显示原始内容
                    if (parsed.rawExtra.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = parsed.rawExtra,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = AlphaTokens.MUTED)
                        )
                    }
                }
            }
        }
    }
}

/** 通过共享的 QuestionPagerView 渲染问题历史（只读）。 */
@Composable
internal fun QuestionExpandedOptions(items: List<QHistItem>) {
    val questions = items.map { item ->
        SseEvent.QuestionAsked.Question(
            header = "",
            question = item.text,
            multiple = item.isMultiple,
            options = item.options.map { SseEvent.QuestionAsked.Option(it.label, it.description) }
        )
    }
    val selected = items.map { it.answers.toSet() }
    QuestionPagerView(
        questions = questions,
        selectedAnswers = selected,
        readOnly = true
    )
}

/**
 * 紧凑 Q tab 行（2026-08-13：从 QuestionPagerView 抽出，供交互卡片标题行共用）。
 * 2026-08-14 用户决策：换 M3 SingleChoiceSegmentedButtonRow（官方组件语义/
 * 无障碍/主题一致性）；压缩高度 32dp 避免"太大太高"回潮。
 */
@Composable
internal fun QuestionCompactTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    questions: List<SseEvent.QuestionAsked.Question>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // M3 SegmentedButton 默认最小交互尺寸 40dp（用户反馈过大）→ 压缩到 32dp
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 32.dp
    ) {
        SingleChoiceSegmentedButtonRow(modifier = modifier) {
            questions.indices.forEach { i ->
                SegmentedButton(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = questions.size),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "Q${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 统一的问题展示：TabRow + HorizontalPager + Checkbox/RadioButton。
 * QuestionCard（交互式）和问题历史（只读）共用。
 */
@Composable
internal fun QuestionPagerView(
    questions: List<SseEvent.QuestionAsked.Question>,
    selectedAnswers: List<Set<String>>,
    readOnly: Boolean = false,
    onOptionClick: ((pageIndex: Int, label: String) -> Unit)? = null,
    pagerState: androidx.compose.foundation.pager.PagerState? = null,
    onPageSelected: (Int) -> Unit = {},
    showTabs: Boolean = true,
) {
    // Bug #126: customDraft 提升到 pager 层按 pageIndex 存——
    // HorizontalPager beyondViewportPageCount=1 时远页 composition 被销毁，
    // 页内 remember 会丢失草稿；提升后翻回时草稿保留
    val customDrafts = remember { mutableStateMapOf<Int, String>() }

    if (questions.size <= 1) {
        questions.firstOrNull()?.let { q ->
            QuestionOptionRows(
                question = q,
                selected = selectedAnswers.firstOrNull() ?: emptySet(),
                readOnly = readOnly,
                onOptionClick = { onOptionClick?.invoke(0, it) },
                customDraft = customDrafts[0] ?: "",
                onCustomDraftChange = { customDrafts[0] = it },
            )
        }
    } else {
        val state = pagerState ?: rememberPagerState(pageCount = { questions.size })
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.LaunchedEffect(state.currentPage) {
            onPageSelected(state.currentPage)
        }
        // 2026-08-14：高度随切换进度线性插值（一元一次方程）——
        // h = h_当前页 + (h_目标页 − h_当前页) × |滑动进度|
        // 各页内容高度由 onGloballyPositioned 记录（含预组合相邻页）
        val pageHeights = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
        val interpolatedHeightPx by androidx.compose.runtime.remember {
            androidx.compose.runtime.derivedStateOf {
                val from = state.currentPage
                val offset = state.currentPageOffsetFraction
                val progress = kotlin.math.abs(offset).coerceIn(0f, 1f)
                val h1 = pageHeights[from] ?: 0
                val target = if (offset > 0f) from + 1 else from - 1
                val h2 = pageHeights[target] ?: h1
                if (h1 == 0) 0 else (h1 + (h2 - h1) * progress).roundToInt()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
            HorizontalPager(
                state = state,
                modifier = Modifier.fillMaxWidth().then(
                    // 未测量前 wrap（高度 0 会塌陷）；测量后按插值高度
                    if (interpolatedHeightPx > 0) {
                        Modifier.height(with(density) { interpolatedHeightPx.toDp() })
                    } else Modifier
                ),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
            ) { page ->
                val pageOffset = ((state.currentPage - page) + state.currentPageOffsetFraction).absoluteValue
                Box(modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val h = coords.size.height
                        if (pageHeights[page] != h) pageHeights[page] = h
                    }
                    .graphicsLayer {
                        alpha = (1f - pageOffset * 0.3f).coerceIn(0.7f, 1f)
                        scaleX = 1f - pageOffset * 0.04f
                        scaleY = 1f - pageOffset * 0.04f
                    }
                ) {
                    // 2026-08-14：Q tabs 嵌入问题域行（headerTabs slot），
                    // 不再在 pager 上方独立渲染
                    QuestionOptionRows(
                        questions[page],
                        selectedAnswers.getOrNull(page) ?: emptySet(),
                        readOnly,
                        { onOptionClick?.invoke(page, it) },
                        customDraft = customDrafts[page] ?: "",
                        onCustomDraftChange = { customDrafts[page] = it },
                        headerTabs = if (showTabs) {
                            { QuestionCompactTabs(state, questions) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuestionOptionRows(
    question: SseEvent.QuestionAsked.Question,
    selected: Set<String>,
    readOnly: Boolean,
    onOptionClick: (String) -> Unit,
    // Bug #126: customDraft 由调用方（QuestionPagerView）按 pageIndex 管理，
    // 避免 HorizontalPager beyondViewportPageCount=1 销毁远页 composition 时丢失草稿
    customDraft: String,
    onCustomDraftChange: (String) -> Unit,
    /** 2026-08-14：Q tabs 嵌入问题域行（用户架构：问题域 → 答案域 → 按钮域，
     *  无独立标题行；tabs 与问题描述同级别）。 */
    headerTabs: (@Composable () -> Unit)? = null,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val isMultiple = question.multiple
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        if (question.question.isNotBlank()) {
            // 2026-08-14：问题域用背景色卡片包裹（用户方案：卡片形成视觉分隔，
            // 不动高度；色用 surfaceVariant 与卡片容器 surfaceContainer 区分）
            Surface(
                shape = ShapeTokens.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                    modifier = Modifier.padding(
                        horizontal = SpacingTokens.MD.dp,
                        vertical = SpacingTokens.SM.dp
                    )
                ) {                    // 类型徽标（2026-08-14 用户要求：嵌入问题域的行内轻量 tag——
                    // 非固定高度独立块；高度跟随文本行，与问题描述自然同排）
                    Surface(
                        shape = ShapeTokens.small,
                        color = if (isMultiple) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.SELECTED)
                        } else {
                            accentColor.copy(alpha = AlphaTokens.SELECTED)
                        }
                    ) {
                        Text(
                            text = stringResource(
                                if (isMultiple) R.string.question_multi_choice else R.string.question_single_choice
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            ),
                            color = if (isMultiple) MaterialTheme.colorScheme.tertiary else accentColor,
                            modifier = Modifier.padding(horizontal = SpacingTokens.SM.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                    Text(question.question, style = MaterialTheme.typography.bodyMedium, color = contentColor)
                    // 2026-08-14：Q tabs 嵌入问题域行（右对齐，多问题时）
                    if (headerTabs != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        headerTabs()
                    }
                }
            }
        }
        question.options.forEach { option ->
            val isSelected = option.label in selected
            Surface(onClick = { onOptionClick(option.label) }, enabled = !readOnly,
                shape = ShapeTokens.small,
                color = if (isSelected) accentColor.copy(alpha = AlphaTokens.SELECTED) else MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                // 2026-08-14：所有选项默认边框（选中时 accent 边框）
                border = BorderStroke(
                    1.dp,
                    if (isSelected) accentColor.copy(alpha = AlphaTokens.MEDIUM)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                ),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium, color = if (isSelected) accentColor else contentColor)
                        if (option.description.isNotBlank()) {
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = AlphaTokens.MEDIUM))
                        }
                    }
                    // 2026-08-14 新设计：选中项右对齐小 ✔（替代左侧单选/多选图标）
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = accentColor)
                    }
                }
            }
        }
        // 自定义答案支持
        if (question.custom != false) {
            val optionLabels = question.options.map { it.label }.toSet()
            val customAnswer = selected.firstOrNull { it !in optionLabels }
            if (customAnswer != null && readOnly) {
                // 历史只读视图：高亮答案行 + ✔（无交互）
                Surface(onClick = {}, enabled = false, shape = ShapeTokens.small,
                    color = accentColor.copy(alpha = AlphaTokens.SELECTED),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = AlphaTokens.MEDIUM)),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(customAnswer, style = MaterialTheme.typography.bodyMedium, color = accentColor, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = accentColor)
                    }
                }
            } else if (customAnswer != null) {
                // 2026-08-14 用户决策：③ 输入完毕态 = ②编辑态样式（输入框外观），
                // 图标换 Edit；点击 Edit 进入修改（预填已有答案，修改后重新提交替换）
                var editing by remember(customAnswer) { mutableStateOf(false) }
                var editText by remember(customAnswer) { mutableStateOf(customAnswer) }
                if (!editing) {
                    // ③ 输入完毕态：与普通答案 item 完全相同的行结构（右对齐一致），
                    // Edit 与 ✔ 同样式（16dp、统一间距）——2026-08-14 用户要求
                    Surface(
                        onClick = {},
                        enabled = false,
                        shape = ShapeTokens.small,
                        color = accentColor.copy(alpha = AlphaTokens.SELECTED),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = AlphaTokens.MEDIUM)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.defaultMinSize(minHeight = 44.dp)
                                .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(customAnswer, style = MaterialTheme.typography.bodyMedium, color = accentColor, modifier = Modifier.weight(1f))
                            // 编辑（左）与 ✔（右）：同样式同边距
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(ShapeTokens.small)
                                    .clickable { editing = true; editText = customAnswer },
                                tint = accentColor
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = accentColor)
                            // Bug #125: ✕ 删除自定义答案——toggle off 该自定义值
                            // （多选 remove；单选若自定义是唯一选中则清空）
                            Spacer(modifier = Modifier.size(4.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(ShapeTokens.small)
                                    .clickable { onOptionClick(customAnswer) },
                                tint = accentColor
                            )
                        }
                    }
                } else {
                    // 修改编辑态：输入框 + 小飞机（2026-08-14 用户要求：不要 X）
                    androidx.compose.material3.OutlinedTextField(
                        value = editText, onValueChange = { editText = it },
                        placeholder = { Text(stringResource(R.string.input_answer), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall, shape = ShapeTokens.small,
                        // 背景与其他答案 item 统一（偏白 surface）；边框未选中态淡色
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                            focusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        ),
                        trailingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(ShapeTokens.small)
                                    .clickable(enabled = editText.isNotBlank() && editText != customAnswer) {
                                        val t = editText.trim()
                                        if (t.isNotBlank()) {
                                            // 修改 = 替换旧自定义：先移除旧值（toggle off）
                                            onOptionClick(customAnswer)
                                            // Bug #125: 若新值是已有选项标签则不再 toggle on——
                                            // 避免已选中选项被意外取消（toggle 语义为切换而非仅选中）
                                            if (t !in optionLabels) {
                                                onOptionClick(t)
                                            }
                                            editing = false
                                        }
                                    },
                                tint = if (editText.isNotBlank() && editText != customAnswer) accentColor
                                    else accentColor.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                    )
                }
            } else if (!readOnly) {
                // ② 默认编辑态（2026-08-14 用户决策：无入口态，直接显示输入框）
                androidx.compose.material3.OutlinedTextField(
                    value = customDraft, onValueChange = onCustomDraftChange,
                    placeholder = { Text(stringResource(R.string.input_answer), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall, shape = ShapeTokens.small,
                    // 背景与其他答案 item 统一（偏白 surface）；边框未选中态淡色
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                        focusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    ),
                    trailingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(ShapeTokens.small)
                                .clickable(enabled = customDraft.isNotBlank()) {
                                    val t = customDraft.trim()
                                    if (t.isNotBlank()) {
                                        // Bug #125: 若输入文本已是选项标签则不 toggle——
                                        // 避免已选中选项被意外取消。
                                        // 2026-08-14 走查修复：匹配已有选项时保留草稿
                                        // （原实现清空草稿 = 用户输入无声丢失，无任何反馈）；
                                        // 用户可看到输入仍在，自行点选对应选项。
                                        if (t !in optionLabels) {
                                            onOptionClick(t)
                                            onCustomDraftChange("")
                                        }
                                    }
                                },
                            tint = if (customDraft.isNotBlank()) accentColor else accentColor.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                )
            }
        }
    }
}
