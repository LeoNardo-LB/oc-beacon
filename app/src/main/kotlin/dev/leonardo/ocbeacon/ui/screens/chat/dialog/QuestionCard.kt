package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.components.AmoledCard
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.components.QuestionPagerView
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

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
    val isSingle = question.questions.size == 1 && question.questions[0].multiple != true

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // 防止多次提交——状态通过 remember(key) 按问题作用域化
    var submitted by remember(question.id) { mutableStateOf(initiallySubmitted) }
    // 默认折叠——点击头部展开选项。
    // 对于历史记录（initiallySubmitted），起始展开以便用户立即看到答案。
    var expanded by remember(question.id) { mutableStateOf(true) }  // 始终展开——无折叠

    // 按问题跟踪答案
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            if (initiallySubmitted && initialAnswers.isNotEmpty()) {
                repeat(question.questions.size) { idx ->
                    add(if (idx < initialAnswers.size) initialAnswers[idx] else emptyList())
                }
            } else {
                repeat(question.questions.size) { add(emptyList()) }
            }
        }
    }

    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    AmoledCard(
        isAmoledDark = isAmoled,
        normalContainerColor = containerColor,
        shape = ShapeTokens.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // 头部行——可点击展开/折叠，显示问题摘要
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeTokens.small)
                    .clickable {
                        performHaptic(hapticView, hapticOn)
                        expanded = !expanded
                    }
            ) {
                Icon(
                    @Suppress("DEPRECATION")
                    Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.a11y_icon_question),
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.chat_question_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                // 将第一个问题文本作为摘要显示（截断）
                val summary = question.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = AlphaTokens.FAINT)
                )
            }

            // 可展开内容——点击头部切换
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
            // 子 agent 来源标签（当问题来自子会话时显示）
            if (question.sourceSessionTitle != null) {
                Text(
                    text = question.sourceSessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                )
            }

            // 问题分区
            QuestionPagerView(
                questions = question.questions,
                selectedAnswers = answersPerQuestion.map { it.toSet() },
                readOnly = submitted,
                onOptionClick = { pageIndex, label ->
                    if (!submitted) {
                        performHaptic(hapticView, hapticOn)
                        if (isSingle) {
                            submitted = true
                            onSubmit(listOf(listOf(label)))
                        } else {
                            val current = answersPerQuestion.getOrNull(pageIndex)?.toMutableList() ?: mutableListOf()
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
                        if (!isSingle) {
                            Button(
                                onClick = {
                                    if (!submitted && answersPerQuestion.any { it.isNotEmpty() }) {
                                        performHaptic(hapticView, hapticOn)
                                        submitted = true
                                        onSubmit(answersPerQuestion.map { it.toList() })
                                    }
                                },
                                enabled = !submitted && answersPerQuestion.any { it.isNotEmpty() }
                            ) {
                                Text(stringResource(R.string.question_submit))
                            }
                        }
                    }
                }
                } // 关闭内部 Column
            } // 关闭 AnimatedVisibility
        }
    }
}
