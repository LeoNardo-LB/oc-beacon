package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.ui.screens.chat.util.QHistItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
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
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    // 解析问题：纯文本或包含答案信息的嵌入 JSON
    val parsed = remember(question) {
        QuestionParser.parseQuestionContent(question)
    }

    // 2026-08-17 用户决策（grilling Q5/Q8）：换 M3 OutlinedCard——与活动提问卡
    // （QuestionCard）统一容器语言——2026-08-18 美化：与活动卡同步换
    // tonal 实底 Surface（surfaceContainerHighest + 无描边），消除明度锯齿。
    // 2026-08-18 二次修正（用户反馈"应有基础容器"）：与活动卡同换
    // EmbeddedCardContainer（圆角随之统一 smallMedium→medium，样式细节见其注释）
    // 2026-08-18 三次修正：与活动卡同换工具卡语言（AmoledSurface 6dp + tonal 1dp，
    // surface 同色——与工具卡完全一致）
    AmoledSurface(
        isAmoledDark = isAmoledTheme(),
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.smallMedium,
        normalTonalElevation = 1.dp,
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
                    modifier = Modifier.size(18.dp),
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
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = AlphaTokens.FAINT)
                )
            }
            // #241 渲染前补偿（2026-08-27 用户裁决去动画）：常驻 Box 两遍精确配对
            val revealListState = LocalChatListState.current
            val expandReveal = remember { ExpandRevealCompensator() }
            Box(
                modifier = Modifier.then(
                    if (revealListState != null) {
                        Modifier
                            .clipToBounds()
                            .expandRevealCompensation(revealListState, expandReveal, "QPC-REVEAL")
                    } else {
                        Modifier
                    }
                )
            ) {
                if (expanded) {
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
 * 2026-08-18 审计①补（响度倒置）：SegmentedButton 的段描边（实测 ΔL 0.63）
 * 比已删除的卡外描边还响 4 倍——"框中框"拥挤感的真凶是内层响度盖过外层。
 * 换 FilterChip（tonal 无描边，selected=secondaryContainer 实底高亮），
 * 保持 32dp 高度与单选语义。
 */
@Composable
internal fun QuestionCompactTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    questions: List<SseEvent.QuestionAsked.Question>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        questions.indices.forEach { i ->
            FilterChip(
                selected = pagerState.currentPage == i,
                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                label = {
                    Text(
                        text = "Q${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            )
        }
    }
}

/** 页内容高度上限占屏比（2026-08-18 E2E-E）——超出部分页内滚动补足可达性。 */
private const val QUESTION_PAGE_MAX_HEIGHT_FRACTION = 0.4f

/**
 * 页高插值 + 上限截断（2026-08-18 E2E-E）：两页高度先各自按 [maxHeightPx]
 * 截断，再按 [progress] 线性插值。fromHeight 为 0（未测量）时返回 0
 * （调用方保持 wrap 不塌陷）。超高页截断后卡片高度恒定于上限，不撑爆视口。
 */
internal fun lerpCappedPageHeight(
    fromHeight: Int,
    targetHeight: Int,
    progress: Float,
    maxHeightPx: Int,
): Int {
    val h1 = fromHeight.coerceIn(0, maxHeightPx)
    val h2 = targetHeight.coerceIn(0, maxHeightPx)
    if (h1 == 0) return 0
    return (h1 + (h2 - h1) * progress.coerceIn(0f, 1f)).roundToInt()
}

/**
 * 统一的问题展示：TabRow + HorizontalPager + Checkbox/RadioButton。
 * QuestionCard（交互式）和问题历史（只读）共用。
 */
@Composable
internal fun QuestionPagerView(
    questions: List<SseEvent.QuestionAsked.Question>,
    selectedAnswers: List<Set<String>>,
    /** 每题保留未勾选的自定义内容（2026-08-18 三态模型；只读历史默认空） */
    parkedCustoms: List<String?> = emptyList(),
    readOnly: Boolean = false,
    onOptionClick: ((pageIndex: Int, label: String) -> Unit)? = null,
    /** ✕ 彻底删除该题自定义（选中槽位 + parked 一并清空） */
    onCustomDiscard: (pageIndex: Int) -> Unit = {},
    pagerState: androidx.compose.foundation.pager.PagerState? = null,
    onPageSelected: (Int) -> Unit = {},
    showTabs: Boolean = true,
    /** 2026-08-17 用户决策：元信息（Q chips + 类型标签）放卡片标题栏时置 false
     *  （QuestionCard 头部自行渲染）；历史视图保持 true（pager 上方元信息行）。 */
    showMetaRow: Boolean = true,
) {
    // Bug #126: customDraft 提升到 pager 层按 pageIndex 存——
    // HorizontalPager beyondViewportPageCount=1 时远页 composition 被销毁，
    // 页内 remember 会丢失草稿；提升后翻回时草稿保留
    val customDrafts = remember { mutableStateMapOf<Int, String>() }

    // 2026-08-18 E2E-E 修复：页内容限高 + 页内垂直滚动——
    // 超高页（6+ 选项 + 自定义输入）此前在消息流中整体不可达（卡片高于视口时
    // reverseLayout 锚定 + 自动回底使下部选项/输入框任何手势都滚不进视口，
    // E2E 实证 6 选项只见 3 + 输入框不可达）。限高后页内滚动补足可达性；
    // 历史只读视图共用本组件自动受益。比例取屏高 40%（M3 对话场景折中）。
    val maxPageHeight = (LocalConfiguration.current.screenHeightDp * QUESTION_PAGE_MAX_HEIGHT_FRACTION).dp

    if (questions.size <= 1) {
        questions.firstOrNull()?.let { q ->
            Column(
                modifier = Modifier
                    .heightIn(max = maxPageHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
            ) {
                if (showMetaRow) QuestionTypeLabel(isMultiple = q.multiple)
                QuestionOptionRows(
                    question = q,
                    selected = selectedAnswers.firstOrNull() ?: emptySet(),
                    parkedCustom = parkedCustoms.firstOrNull(),
                    readOnly = readOnly,
                    onOptionClick = { onOptionClick?.invoke(0, it) },
                    onCustomDiscard = { onCustomDiscard(0) },
                    customDraft = customDrafts[0] ?: "",
                    onCustomDraftChange = { customDrafts[0] = it },
                )
            }
        }
    } else {
        val state = pagerState ?: rememberPagerState(pageCount = { questions.size })
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.LaunchedEffect(state.currentPage) {
            onPageSelected(state.currentPage)
        }
        // 2026-08-14：高度随切换进度线性插值（一元一次方程）——
        // h = h_当前页 + (h_目标页 − h_当前页) × |滑动进度|
        // 各页内容高度由 onGloballyPositioned 记录（含预组合相邻页）。
        // 2026-08-18 E2E-E：插值前按页高上限截断（超高页不撑爆卡片）
        val maxPageHeightPx = with(density) { maxPageHeight.toPx() }.roundToInt()
        val pageHeights = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
        val interpolatedHeightPx by androidx.compose.runtime.remember {
            androidx.compose.runtime.derivedStateOf {
                val from = state.currentPage
                val offset = state.currentPageOffsetFraction
                val progress = kotlin.math.abs(offset).coerceIn(0f, 1f)
                val h1 = pageHeights[from] ?: 0
                val target = if (offset > 0f) from + 1 else from - 1
                val h2 = pageHeights[target] ?: h1
                lerpCappedPageHeight(h1, h2, progress, maxPageHeightPx)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
            // 2026-08-17 用户重设计：元信息行（Q chips + 当前页类型标签）。
            // 活动卡（QuestionCard）置 showMetaRow=false，元信息在卡片标题栏；
            // 历史视图保持此处（pager 上方元信息行）。
            if (showMetaRow) {
                // 2026-08-17 用户第四轮：类型标签左、chips 右（与活动卡标题栏同序）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuestionTypeLabel(isMultiple = questions.getOrNull(state.currentPage)?.multiple)
                    if (showTabs) {
                        Spacer(Modifier.weight(1f))
                        QuestionCompactTabs(state, questions)
                    }
                }
            }
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
                    // 2026-08-18 E2E-E：页限高 + 页内滚动（滚动状态随页 composition——
                    // beyondViewportPageCount 销毁远页时滚动位置重置，可接受：
                    // 翻回时从顶部重看，选项草稿已由 customDrafts 提升#126 保护）
                    .heightIn(max = maxPageHeight)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        alpha = (1f - pageOffset * 0.3f).coerceIn(0.7f, 1f)
                        scaleX = 1f - pageOffset * 0.04f
                        scaleY = 1f - pageOffset * 0.04f
                    }
                ) {
                    // 高度记录移至滚动内容内层——verticalScroll 内子项按无界高度
                    // 测量，记录的是完整内容高度（防键盘态/插值过渡期"测量偏小"
                    // 复发——E2E-E 原始 12px 裁剪根因）
                    Column(
                        modifier = Modifier.onGloballyPositioned { coords ->
                            val h = coords.size.height
                            if (pageHeights[page] != h) pageHeights[page] = h
                        }
                    ) {
                        QuestionOptionRows(
                            questions[page],
                            selectedAnswers.getOrNull(page) ?: emptySet(),
                            parkedCustoms.getOrNull(page),
                            readOnly,
                            { onOptionClick?.invoke(page, it) },
                            { onCustomDiscard(page) },
                            customDraft = customDrafts[page] ?: "",
                            onCustomDraftChange = { customDrafts[page] = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 类型标签（单选/多选）——2026-08-17 用户重设计：元信息不进问题域。
 * 活动卡放标题栏（QuestionCard 头部右侧）；历史放元信息行。
 */
@Composable
internal fun QuestionTypeLabel(isMultiple: Boolean?) {
    Text(
        text = stringResource(
            if (isMultiple == true) R.string.question_multi_choice else R.string.question_single_choice
        ),
        // labelMedium（12sp）：字号阶梯严格 14>12（审计 D3：11sp 谷底切断标题行重心）
        style = MaterialTheme.typography.labelMedium,
        color = if (isMultiple == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        maxLines = 1
    )
}

@Composable
internal fun QuestionOptionRows(
    question: SseEvent.QuestionAsked.Question,
    selected: Set<String>,
    /** 保留未勾选的自定义内容（null=无）：渲染为可再勾选的 parked 行 */
    parkedCustom: String? = null,
    readOnly: Boolean,
    onOptionClick: (String) -> Unit,
    /** ✕ 彻底删除自定义（选中槽位 + parked 一并清空 → 回空输入框） */
    onCustomDiscard: () -> Unit = {},
    // Bug #126: customDraft 由调用方（QuestionPagerView）按 pageIndex 管理，
    // 避免 HorizontalPager beyondViewportPageCount=1 销毁远页 composition 时丢失草稿
    customDraft: String,
    onCustomDraftChange: (String) -> Unit,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        if (question.question.isNotBlank()) {
            // 2026-08-17 用户重设计：问题域只承载问题描述——元信息（Q chips/
            // 类型标签）上移至 QuestionPagerView 元信息行；原 surfaceVariant
            // 容器与内嵌 tag 一并移除，bodyLarge 突出问题本体
            Text(
                text = question.question,
                // 2026-08-18 美化：bodyLarge→bodyMedium+Medium——与选项同号（14sp），
                // 靠字重分层（审计 D3：16sp 夹在 14/12 间破坏节奏）
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = contentColor
            )
        }
        // 2026-08-17 用户第四轮：紧凑行（settings 同款模式——clickable Row +
        // 紧凑 padding）替代 M3 ListItem——ListItem 容器高度是固定 token
        // （单行 48dp 起，无 padding 参数）压不矮，用户反馈"item 太高"；
        // 此模式行高更紧凑且与自定义输入框可对齐。选择指示保持右侧 ✔
        // （选中才显示，未选中无控件）。
        question.options.forEach { option ->
            val isSelected = option.label in selected
            CompactOptionRow(
                label = option.label,
                description = option.description.takeIf { it.isNotBlank() },
                isSelected = isSelected,
                readOnly = readOnly,
                accentColor = accentColor,
                contentColor = contentColor,
                onClick = { onOptionClick(option.label) },
            )
        }
        // 自定义答案支持
        // 2026-08-18 用户语义澄清：自定义输入 = **提交自己的回答**，至多一个
        // ——输入 → 纸飞机保存 → 成为回答（Edit 可改、✕ 可删回到空输入框）。
        // 2026-08-18 三态模型（用户反馈：单选保存自定义后再选其他选项，
        // 自定义应"保留内容，但取消勾选"）：三分支渲染——
        // ① 已勾选：CustomAnswerRow（✎/✕/✔，行点击=取消勾选入 parked）
        // ② parked 保留：ParkedCustomRow（行点击=重新勾选；✕=彻底删除）
        // ③ 不存在：CustomAnswerInput 空输入框
        if (question.custom != false) {
            val optionLabels = question.options.map { it.label }.toSet()
            val customAnswer = selected.firstOrNull { it !in optionLabels }
            var editingCustom by remember { mutableStateOf<String?>(null) }
            if (customAnswer != null) {
                CustomAnswerRow(
                    customAnswer = customAnswer,
                    readOnly = readOnly,
                    accentColor = accentColor,
                    optionLabels = optionLabels,
                    onOptionClick = onOptionClick,
                    onDiscard = onCustomDiscard,
                    isEditing = editingCustom == customAnswer,
                    onEditStart = { editingCustom = customAnswer },
                    onEditEnd = { if (editingCustom == customAnswer) editingCustom = null },
                )
            } else if (!readOnly && parkedCustom != null) {
                // 只读历史不渲染 parked 行（历史载荷只有已提交答案，无此概念）
                ParkedCustomRow(
                    text = parkedCustom,
                    contentColor = contentColor,
                    onCheck = { onOptionClick(parkedCustom) },
                    onDiscard = onCustomDiscard,
                )
            }
            // 输入框：无自定义（含 parked）时显示（输入即提交自定义回答）；
            // 有自定义时隐藏（修改走行内 Edit）；编辑态激活时隐藏（同时只有一个输入框）
            if (!readOnly && customAnswer == null && parkedCustom == null) {
                // ② 默认编辑态（2026-08-14 用户决策：无入口态，直接显示输入框）。
                // 2026-08-18 全面重构：M3 TextField + 显式 height(44.dp) →
                // 自绘 CustomAnswerInput——修复字体缩放裁切 + 字号/焦点/触达
                // 美化（见 CustomAnswerInput 注释）。提交语义不变（Bug #125 保留）。
                CustomAnswerInput(
                    value = customDraft,
                    onValueChange = onCustomDraftChange,
                    submitEnabled = customDraft.isNotBlank(),
                    accentColor = accentColor,
                    onSubmit = {
                        val t = customDraft.trim()
                        // Bug #125: 若输入文本已是选项标签则不 toggle——避免已选中
                        // 选项被意外取消；保留草稿让用户看到输入仍在（无声丢失=坏 UX）。
                        if (t.isNotBlank() && t !in optionLabels) {
                            onOptionClick(t)
                            onCustomDraftChange("")
                        }
                    },
                )
            }
        }
    }
}


/**
 * 紧凑选项行（2026-08-17 用户第四轮）——clickable Row + 紧凑 padding
 * （settings 列表同款模式，行高 ~36-40dp，替代 M3 ListItem 的固定 48dp+）。
 * 未选中：纯文本无控件；选中：accent 文字 + 右侧 ✔ + 淡染背景（圆角）。
 */
@Composable
private fun CompactOptionRow(
    label: String,
    description: String?,
    isSelected: Boolean,
    readOnly: Boolean,
    accentColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!readOnly) Modifier.clickable(onClick = onClick) else Modifier)
            .background(
                if (isSelected) accentColor.copy(alpha = AlphaTokens.SELECTED) else Color.Transparent,
                ShapeTokens.small
            )
            // 左缘（审计 D4 折中）：SM(8dp)——选项文字 +20dp，与输入框文字(+28dp,
            // M3 默认 start 16dp)差收窄到 8dp；XS 太贴边、MD 又回到 24dp 老问题
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (isSelected) accentColor else contentColor)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = AlphaTokens.MEDIUM))
            }
        }
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = accentColor)
        }
    }
}

/**
 * 已选自定义答案行（紧凑，与 [CompactOptionRow] 同视觉）；trailing 槽位
 * 供 Edit/✕/✔ 操作图标（交互态）或空（只读态）。
 */
@Composable
private fun CompactSelectedRow(
    text: String,
    accentColor: Color,
    trailing: (@Composable () -> Unit)? = null,
    /** 行点击（已勾选自定义行=取消勾选入 parked；只读态 null=不可点） */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(accentColor.copy(alpha = AlphaTokens.SELECTED), ShapeTokens.small)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = accentColor, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 自定义答案输入框（空态默认输入 / 编辑态共用，2026-08-18 全面重构）。
 *
 * 裁切修复（用户反馈"下方字母被切断"）：原 M3 TextField + 显式
 * height(44.dp)——M3 内部 MinHeight(56dp)/contentPadding 与外部定高互相
 * 挤压，E2E 复现（font_scale=1.3）：sp 随系统字体缩放放大后内容溢出
 * 44dp 定高，字形上下被硬切。改 [BasicTextField] 自绘装饰盒 +
 * heightIn(min=44dp) 内容驱动高度：任何字体缩放下高度随内容增长，不裁。
 *
 * 美化（tonal 语言，延续审计 D1-D4）：
 * - 字号统一 bodyMedium(14sp)——与选项行同号，消除 12/14 字号锯齿；
 *   占位符 FAINT（令牌语义：占位符归 FAINT）
 * - 焦点反馈无描边：聚焦 = accent 淡染 SELECTED(0.12)（与选中行同语言）
 *   animateColorAsState 过渡；失焦 = surfaceContainerHigh 实底（内嵌字段）
 * - 光标 accent；键盘 ImeAction.Send 直接提交
 * - 纸飞机触达 40dp（原 18dp 图标可点区过小）+ 语义描述（chat_send）
 * - [onCancel] 非空时显示 ✕ 取消（编辑态退出通道）
 */
@Composable
private fun CustomAnswerInput(
    value: String,
    onValueChange: (String) -> Unit,
    submitEnabled: Boolean,
    accentColor: Color,
    onSubmit: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusWash by animateColorAsState(
        targetValue = if (focused) accentColor.copy(alpha = AlphaTokens.SELECTED) else Color.Transparent,
        label = "customAnswerFocusWash"
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            // 内容驱动高度：scale=1.0 时 MD(12)×2 + bodyMedium 行高(20) = 44dp
            // 与紧凑选项行视觉对齐；字体放大时随内容增长——与原 height(44.dp)
            // 定高的本质区别（不再裁切）
            .heightIn(min = 44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, ShapeTokens.small)
            .background(focusWash, ShapeTokens.small),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(accentColor),
        singleLine = true,
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(
            onSend = { if (submitEnabled) onSubmit() }
        ),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(
                    start = SpacingTokens.LG.dp,
                    end = SpacingTokens.XS.dp,
                    top = SpacingTokens.MD.dp,
                    bottom = SpacingTokens.MD.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    innerTextField()
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.input_answer),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                }
                if (onCancel != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(ShapeTokens.small)
                            .clickable(onClick = onCancel),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.a11y_icon_dismiss),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(ShapeTokens.small)
                        .clickable(enabled = submitEnabled, onClick = onSubmit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        modifier = Modifier.size(20.dp),
                        tint = if (submitEnabled) accentColor
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                    )
                }
            }
        }
    )
}

/**
 * 单条自定义答案行（已勾选态，2026-08-17 从 QuestionOptionRows 抽出）。
 *
 * 完毕态：accent 淡染行 + trailing ✎/✕/✔；**行点击 = 取消勾选入 parked**
 * （2026-08-18 三态模型：内容保留、不再提交，与选项行 tap-toggle 同语言）；
 * ✕ = 彻底删除（选中槽位 + parked 一并清空 → 回空输入框）。
 * 编辑态：CustomAnswerInput + 纸飞机（修改 = park 旧值 + 勾选新值）。
 * editing/editText 状态按 customAnswer 作用域化（值变即重置）——
 * E2E 观察到的"预填陈旧草稿"实为并行测试污染，本组件预填恒为当前值。
 */
@Composable
private fun CustomAnswerRow(
    customAnswer: String,
    readOnly: Boolean,
    accentColor: Color,
    optionLabels: Set<String>,
    onOptionClick: (String) -> Unit,
    /** ✕ 彻底删除（不再走 toggle——toggle 现在是"取消勾选入 parked"） */
    onDiscard: () -> Unit,
    /** 编辑态由父级持有（2026-08-17 双输入框修复）：同时只有一个输入框 */
    isEditing: Boolean,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    if (readOnly) {
        // 历史只读视图：选中态行（无交互；右侧 ✔ 指示）
        CompactSelectedRow(
            text = customAnswer,
            accentColor = accentColor,
            trailing = null,
        )
        return
    }
    // ③ 完毕态 ⇄ 编辑态（2026-08-14 用户决策：Edit 进入修改，预填当前值）；
    // editing 标志提升至父级（isEditing），行内只保留草稿文本状态
    var editText by remember(customAnswer) { mutableStateOf(customAnswer) }
    if (!isEditing) {
        // 图标序（2026-08-17 用户第四轮）：✎ / ✕ / ✔——✔ 最右与普通选项行
        // 的 ✔ 位置对齐（视觉语言统一）
        CompactSelectedRow(
            text = customAnswer,
            accentColor = accentColor,
            // 行点击 = 取消勾选（内容入 parked，与选项行 tap-toggle 同语言）
            onClick = { onOptionClick(customAnswer) },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(ShapeTokens.small)
                            .clickable { editText = customAnswer; onEditStart() },
                        tint = accentColor
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.a11y_icon_dismiss),
                        modifier = Modifier
                            .size(18.dp)
                            .clip(ShapeTokens.small)
                            .clickable { onDiscard() },
                        tint = accentColor
                    )
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = accentColor)
                }
            },
        )
    } else {
        CustomAnswerInput(
            value = editText,
            onValueChange = { editText = it },
            submitEnabled = editText.isNotBlank() && editText != customAnswer,
            accentColor = accentColor,
            onSubmit = {
                val t = editText.trim()
                if (t.isNotBlank()) {
                    // 修改 = 替换旧自定义：旧值取消勾选入 parked，再勾选新值
                    // （最终 parked 被新值覆盖为 null，见 toggle 纯函数）；
                    // Bug #125: 新值是已有选项标签时不再 toggle on
                    // （避免已选中选项被意外取消；旧值此时已 parked）
                    onOptionClick(customAnswer)
                    if (t !in optionLabels) {
                        onOptionClick(t)
                    }
                    onEditEnd()
                }
            },
            // 编辑态可取消：修复"进入编辑后不改文字就退不出"的死局
            onCancel = onEditEnd,
        )
    }
}

/**
 * 保留未勾选的自定义答案行（2026-08-18 三态模型，用户反馈：单选选了
 * 其他选项时自定义应"保留内容，但取消勾选"）。
 *
 * 视觉与未选中选项行同语言：无淡染、无选中控件，文本 MEDIUM 弱化
 * （区分"已保存草稿"与"当前勾选中"）；trailing ✕ = 彻底删除回空输入框。
 * 行点击 = 重新勾选（单选语义下替换选项选择——选项行仍可见可再选）。
 */
@Composable
private fun ParkedCustomRow(
    text: String,
    contentColor: Color,
    onCheck: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheck)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = AlphaTokens.MEDIUM),
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.a11y_icon_dismiss),
            modifier = Modifier
                .size(18.dp)
                .clip(ShapeTokens.small)
                .clickable(onClick = onDiscard),
            tint = contentColor.copy(alpha = AlphaTokens.FAINT)
        )
    }
}
