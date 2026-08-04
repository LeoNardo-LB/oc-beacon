package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
        Column(modifier = Modifier.padding(4.dp).fillMaxWidth()) {
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
                                    Icons.Default.RadioButtonChecked,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
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
 * 统一的问题展示：TabRow + HorizontalPager + Checkbox/RadioButton。
 * QuestionCard（交互式）和问题历史（只读）共用。
 */
@Composable
internal fun QuestionPagerView(
    questions: List<SseEvent.QuestionAsked.Question>,
    selectedAnswers: List<Set<String>>,
    readOnly: Boolean = false,
    onOptionClick: ((pageIndex: Int, label: String) -> Unit)? = null
) {
    if (questions.size <= 1) {
        questions.firstOrNull()?.let { q ->
            QuestionOptionRows(q, selectedAnswers.firstOrNull() ?: emptySet(), readOnly) { onOptionClick?.invoke(0, it) }
        }
    } else {
        val pagerState = rememberPagerState(pageCount = { questions.size })
        val scope = rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        var maxPageHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        Column {
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent) {
                questions.indices.forEach { i ->
                    Tab(selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text("Q${i + 1}", style = MaterialTheme.typography.labelSmall) })
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().then(
                    if (maxPageHeight > 0) Modifier.height(with(density) { maxPageHeight.toDp() }) else Modifier
                ),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                Box(modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val h = coords.size.height
                        if (h > maxPageHeight) maxPageHeight = h
                    }
                    .graphicsLayer {
                        alpha = (1f - pageOffset * 0.3f).coerceIn(0.7f, 1f)
                        scaleX = 1f - pageOffset * 0.04f
                        scaleY = 1f - pageOffset * 0.04f
                    }
                ) {
                    QuestionOptionRows(questions[page], selectedAnswers.getOrNull(page) ?: emptySet(), readOnly)
                    { onOptionClick?.invoke(page, it) }
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
    onOptionClick: (String) -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    val isMultiple = question.multiple
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
        if (question.question.isNotBlank()) {
            Text(question.question, style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
        question.options.forEach { option ->
            val isSelected = option.label in selected
            Surface(onClick = { onOptionClick(option.label) }, enabled = !readOnly,
                shape = ShapeTokens.small,
                color = if (isSelected) accentColor.copy(alpha = AlphaTokens.SELECTED) else MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isMultiple) (if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank) else (if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked),
                        contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = if (isSelected) accentColor else accentColor.copy(alpha = AlphaTokens.MEDIUM))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium, color = if (isSelected) accentColor else contentColor)
                        if (option.description.isNotBlank()) {
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = AlphaTokens.MEDIUM))
                        }
                    }
                }
            }
        }
        // 自定义答案支持
        if (question.custom != false) {
            val optionLabels = question.options.map { it.label }.toSet()
            val customAnswer = selected.firstOrNull { it !in optionLabels }
            if (customAnswer != null) {
                Surface(onClick = {}, enabled = false, shape = ShapeTokens.small,
                    color = accentColor.copy(alpha = AlphaTokens.SELECTED), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isMultiple) Icons.Default.CheckBox else Icons.Default.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(16.dp), tint = accentColor)
                        Text(customAnswer, style = MaterialTheme.typography.bodyMedium, color = accentColor, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (!readOnly && customAnswer == null) {
                var isEditing by remember { mutableStateOf(false) }
                var customText by remember { mutableStateOf("") }
                if (!isEditing) {
                    Surface(onClick = { isEditing = true }, shape = ShapeTokens.small, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = accentColor.copy(alpha = AlphaTokens.MEDIUM))
                            Text(stringResource(R.string.custom_input), style = MaterialTheme.typography.bodySmall, color = accentColor.copy(alpha = AlphaTokens.MEDIUM))
                        }
                    }
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        value = customText, onValueChange = { customText = it },
                        placeholder = { Text(stringResource(R.string.input_answer), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall, shape = ShapeTokens.small,
                        trailingIcon = {
                            Row {
                                androidx.compose.material3.IconButton(onClick = {
                                    val t = customText.trim()
                                    if (t.isNotBlank()) { onOptionClick(t); isEditing = false; customText = "" }
                                }, enabled = customText.isNotBlank()) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                androidx.compose.material3.IconButton(onClick = { isEditing = false; customText = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
