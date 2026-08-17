package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.components.QuestionPagerView
import dev.leonardo.ocbeacon.ui.screens.chat.components.QuestionCompactTabs
import dev.leonardo.ocbeacon.ui.screens.chat.components.QuestionTypeLabel
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.launch

/**
 * 用于回答 agent 问题的交互式卡片。
 * 支持单选/多选选项，"自行输入答案"会展开一个行内文本框。
 */
@Composable
internal fun QuestionCard(
    question: SseEvent.QuestionAsked,
    onSubmit: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit,
    positionLabel: String? = null,
    initiallySubmitted: Boolean = false,
    initialAnswers: List<List<String>> = emptyList()
) {
    val isAmoled = isAmoledTheme()
    // 注意：isSingle 仅用于"单问题场景"的整体分支（如 Submit 按钮布局）；
    // 每道题的单选/多选语义必须按题目 multiple 判断（多问题场景中
    // 每道题独立，修复 2026-08-13 用户验收发现：多问题里的单选题目也能多选）
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val scope = rememberCoroutineScope()

    // 防止多次提交——状态通过 remember(key) 按问题作用域化
    // #113（D2-L67）：rememberSaveable——旋转/进程重建后答案不丢（原 remember
    // 在配置变更后重置，用户已选答案丢失需重选）。
    var submitted by rememberSaveable(question.id) { mutableStateOf(initiallySubmitted) }

    // 多问题时将 pagerState 提升到 QuestionCard，以便"下一个"按钮控制翻页；
    // 单问题时为 null，QuestionPagerView 走单页分支（不建 pagerState）。
    val pagerState = if (question.questions.size > 1) {
        rememberPagerState(pageCount = { question.questions.size })
    } else null

    // 当前页（来自 pagerState.currentPage 回调；单问题固定 0）
    // #113（D2-L67）：旋转/重建后恢复当前页
    var currentPage by rememberSaveable(question.id) { mutableIntStateOf(0) }
    // 未回答确认弹窗
    var showUnansweredDialog by remember(question.id) { mutableStateOf(false) }

    // 按问题跟踪答案
    // #113（D2-L67）：答案列表 saveable——mutableStateListOf 无法直接保存，
    // 用序列化 List<List<String>> 兜底（旋转后重建，避免答案丢失需重选）。
    var savedAnswers by rememberSaveable(question.id) {
        mutableStateOf(emptyList<List<String>>())
    }
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            val restored = savedAnswers
            if (restored.isNotEmpty()) {
                addAll(restored)
            } else if (initiallySubmitted && initialAnswers.isNotEmpty()) {
                repeat(question.questions.size) { idx ->
                    add(if (idx < initialAnswers.size) initialAnswers[idx] else emptyList())
                }
            } else {
                repeat(question.questions.size) { add(emptyList()) }
            }
        }
    }
    // #113（D2-L67）：答案变更同步到 saveable（旋转重建后恢复）
    androidx.compose.runtime.SideEffect {
        savedAnswers = answersPerQuestion.map { it.toList() }
    }

    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    // 2026-08-17 用户决策（grilling Q5=A）：换 M3 原生 OutlinedCard + 表单头部。
    // 提问卡是等待用户操作的表单（不是被动内容）——描边 + 头部让它"不一样得
    // 有章法"，与聊天气泡的有意对比取代原自绘 Surface 容器的"外来物"拼盘感。
    // 底色保留 surfaceContainer 半透明（延续 AMOLED 兼容）；架构不变：卡片直接
    // 承担展开容器，内容 = 问题域 / 答案域 / 按钮域；回答后卡片消失。
    OutlinedCard(
        shape = ShapeTokens.small,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = AlphaTokens.MEDIUM),
            contentColor = contentColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.SM.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // 表单头部（Q5=A + 2026-08-17 用户决策：元信息入标题栏）：
            // [?] 待你回答 …… [Q1|Q2] MULTI/SINGLE——Q chips 与类型标签
            // 是"卡片的元信息"而非"问题的内容"，与标题同行；
            // 子 agent 来源（若有）降为标题行下方的小字行。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.question_awaiting_reply),
                    style = MaterialTheme.typography.titleSmall,
                    color = accentColor
                )
                Spacer(Modifier.weight(1f))
                // 元信息：Q chips（多问题）+ 当前页类型标签（SegmentedButton 原生高度）
                if (question.questions.size > 1 && pagerState != null) {
                    QuestionCompactTabs(pagerState, question.questions)
                    Spacer(Modifier.size(SpacingTokens.SM.dp))
                }
                QuestionTypeLabel(
                    isMultiple = question.questions.getOrNull(currentPage)?.multiple
                )
            }
            if (question.sourceSessionTitle != null) {
                Text(
                    text = question.sourceSessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
            // 2026-08-17 用户重设计：标题栏与正文的形式化分隔（元信息行/问题域/
            // 答案域/按钮域的分界起点）
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )

            // 问题分区（问题域 + 答案域；Q tabs 嵌入问题域行）
            QuestionPagerView(
                questions = question.questions,
                selectedAnswers = answersPerQuestion.map { it.toSet() },
                readOnly = submitted,
                pagerState = pagerState,
                onPageSelected = { currentPage = it },
                showTabs = question.questions.size > 1,
                showMetaRow = false, // 元信息在标题栏（本卡片头部）
                onOptionClick = { pageIndex, label ->
                    if (!submitted) {
                        performHaptic(hapticView, hapticOn)
                        val current = answersPerQuestion.getOrNull(pageIndex)?.toMutableList() ?: mutableListOf()
                        // 单选/多选按当前题目 multiple 判断（多问题场景每道题独立，
                        // 单选题目必须互斥——2026-08-13 用户验收 bug 修复）
                        val isSingleQuestion = question.questions.getOrNull(pageIndex)?.multiple != true
                        if (isSingleQuestion) {
                            // 单选：toggle——选中项取消则清空，否则替换为该项（不再立即提交）
                            // Bug #127: 补越界保护（与多选分支 :174 对称）
                            if (pageIndex < answersPerQuestion.size) {
                                answersPerQuestion[pageIndex] = if (current == listOf(label)) emptyList() else listOf(label)
                            }
                        } else {
                            if (label in current) current.remove(label) else current.add(label)
                            if (pageIndex < answersPerQuestion.size) answersPerQuestion[pageIndex] = current
                        }
                    }
                }
            )

                // 底部操作——在历史模式下（initiallySubmitted）隐藏
                if (!initiallySubmitted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { if (!submitted) { performHaptic(hapticView, hapticOn); submitted = true; onReject() } },
                            enabled = !submitted
                        ) {
                            Text(stringResource(R.string.chat_dismiss))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                            if (!isSingle && pagerState != null) {
                                Button(
                                    onClick = {
                                        performHaptic(hapticView, hapticOn)
                                        scope.launch {
                                            pagerState.animateScrollToPage(
                                                (currentPage + 1).coerceAtMost(question.questions.size - 1)
                                            )
                                        }
                                    },
                                    enabled = !submitted && currentPage < question.questions.size - 1
                                ) {
                                    Text(stringResource(R.string.question_next))
                                }
                            }
                            Button(
                                onClick = {
                                    if (!submitted) {
                                        performHaptic(hapticView, hapticOn)
                                        val unanswered = unansweredQuestionIndexes(
                                            answersPerQuestion.toList(),
                                            question.questions.size
                                        )
                                        if (unanswered.isNotEmpty()) {
                                            showUnansweredDialog = true
                                        } else {
                                            submitted = true
                                            onSubmit(answersPerQuestion.map { it.toList() })
                                        }
                                    }
                                },
                                enabled = !submitted && answersPerQuestion.any { it.isNotEmpty() }
                            ) {
                                Text(stringResource(R.string.question_submit))
                            }
                        }
                    }
                }
        }
        // 未回答确认弹窗（AmoledCard 内部、Column 之外）
        if (showUnansweredDialog) {
            val unanswered = unansweredQuestionIndexes(answersPerQuestion.toList(), question.questions.size)
            val separator = stringResource(R.string.question_unanswered_separator)
            val label = stringResource(
                R.string.question_unanswered_confirm,
                unanswered.joinToString(separator) { it.toString() }
            )
            AlertDialog(
                onDismissRequest = { showUnansweredDialog = false },
                title = { Text(stringResource(R.string.question_unanswered_title)) },
                text = { Text(label) },
                confirmButton = {
                    TextButton(onClick = {
                        showUnansweredDialog = false
                        submitted = true
                        onSubmit(answersPerQuestion.map { it.toList() })
                    }) { Text(stringResource(R.string.question_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = { showUnansweredDialog = false }) {
                        Text(stringResource(R.string.chat_dismiss))
                    }
                }
            )
        }
    }
}

/**
 * 返回未回答问题的问题编号列表（1-based，按问题顺序）。
 * answers 长度可能小于 questionCount（Pager 懒加载时未访问页无答案项），缺失视为未回答。
 */
internal fun unansweredQuestionIndexes(
    answers: List<List<String>>,
    questionCount: Int
): List<Int> {
    return (0 until questionCount)
        .filter { idx -> answers.getOrNull(idx).isNullOrEmpty() }
        .map { it + 1 }
}
