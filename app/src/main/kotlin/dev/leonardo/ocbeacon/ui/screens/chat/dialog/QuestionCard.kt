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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.screens.chat.QuestionAnswerStore
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** E2E-C 修复：saveable 答案 JSON 序列化用（Bundle 可存）。 */
private val json = Json { ignoreUnknownKeys = true }

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
    initialAnswers: List<List<String>> = emptyList(),
    /** 2026-08-18 E2E-C 修复（终版）：应用级答案存储（QuestionAnswerStore 单例）。
     * VM 级缓存已被终验证伪（pop 销毁 entry/recreate 重建 VM 双路径宿主皆亡）；
     * store 跨导航条目与 recreate 存活。null（无宿主）时退回纯 saveable 行为。 */
    answersStore: QuestionAnswerStore? = null,
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
    // #113（D2-L67）→ 2026-08-18 E2E-C 根因修复：原实现直接存 List<List<String>>——
    // rememberSaveable 的 autoSaver canBeSaved 对该类型返回 false（普通 Kotlin List
    // 非 Bundle 合法类型）→ **静默不保存**：导航 pop 与 Activity recreate 双向量丢答案
    // （E2E-C 三次独立复现的根因）。改为 JSON 字符串（Bundle 原生可存）序列化。
    var savedAnswersJson by rememberSaveable(question.id) {
        mutableStateOf("")
    }
    val answersPerQuestion = remember {
        mutableStateListOf<List<String>>().apply {
            // 恢复优先级：应用级 store（跨导航/recreate，E2E-C 终版）> saveable
            // JSON > initialAnswers（历史）> 空
            val fromCache = answersStore?.get(question.id).orEmpty()
            val restored = if (fromCache.isNotEmpty()) fromCache else runCatching {
                json.decodeFromString<List<List<String>>>(savedAnswersJson)
            }.getOrNull().orEmpty()
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
    // 答案变更双写：saveable（同 entry recreate）+ 应用级 store（pop/recreate 全路径）
    androidx.compose.runtime.SideEffect {
        savedAnswersJson = json.encodeToString(answersPerQuestion.map { it.toList() })
        answersStore?.put(question.id, answersPerQuestion.map { it.toList() })
    }

    val contentColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    // 2026-08-18 美化重构（用户"不协调"反馈 + 双路审计：代码 8 维 + 像素 7 维
    // 交叉定位 TOP3 = 明度锯齿/嵌套白洞/三层框中框）：
    // - 容器：OutlinedCard → 无描边 tonal Surface（M3 Filled Card 形态），
    //   surfaceContainerHighest **实底**（alpha 拷贝是明度锯齿元凶——M3 surface
    //   阶梯本为嵌套容器设计的成套渐变，直接用）
    // - 圆角 small(8)→medium(12)：与 assistant 气泡（ShapeTokens.medium）同族
    // - 删分割线（tonal 容器内的 FAINT 线存在感弱且增加线条数）
    // - 标题降阶：titleSmall→labelMedium 小字行（弱化"表单感"，问题本体才是主角）
    // 2026-08-18 二次修正（用户反馈"应有基础容器"）：tonal 一档差在气泡内
    // 不读作独立卡片 → 换共享基础容器 EmbeddedCardContainer（surfaceContainerLow
    // + 1dp 细边框，与 FileCard 等其他内嵌卡片同一容器语言）
    // 2026-08-18 三次修正（用户澄清方向）：提问卡向**其他卡片主流语言**看齐
    // （ToolCardScaffold：smallMedium 6dp + surfaceContainer 底 + tonal 1dp，
    // 无边框，AMOLED 纯黑+边框由 AmoledSurface 处理）——不是其他卡片改跟
    // 提问卡；此前两轮方向做反（a90dbead FileCard 基准 / d9cbb252 全家迁移已回滚）。
    AmoledSurface(
        isAmoledDark = isAmoled,
        normalColor = MaterialTheme.colorScheme.surface, // 与工具卡完全同色（E2E 对比后对齐）
        shape = ShapeTokens.smallMedium,
        normalTonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            // 元信息行（降为一行小字）：待你回答 · 类型 · [Q1|Q2]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.question_awaiting_reply),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
                QuestionTypeLabel(
                    isMultiple = question.questions.getOrNull(currentPage)?.multiple
                )
                Spacer(Modifier.weight(1f))
                if (question.questions.size > 1 && pagerState != null) {
                    QuestionCompactTabs(pagerState, question.questions)
                }
            }
            if (question.sourceSessionTitle != null) {
                Text(
                    text = question.sourceSessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                )
            }

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
                        val q = question.questions.getOrNull(pageIndex)
                        // 单选/多选按当前题目 multiple 判断（多问题场景每道题独立，
                        // 单选题目必须互斥——2026-08-13 用户验收 bug 修复）
                        val isSingleQuestion = q?.multiple != true
                        val optionLabels = q?.options?.map { it.label }?.toSet() ?: emptySet()
                        // Bug #127: 越界保护
                        if (pageIndex < answersPerQuestion.size) {
                            // 2026-08-18 用户反馈修复：点选项不再清掉已保存的自定义答案
                            // （反向同理：单选卡保存自定义不再静默清掉已选选项）——
                            // 选项/自定义两槽位互不挤占，见 toggleQuestionAnswer
                            answersPerQuestion[pageIndex] = toggleQuestionAnswer(
                                answersPerQuestion[pageIndex], label, optionLabels, isSingleQuestion
                            )
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
                                // 2026-08-18 审计②补：Next 是翻页导航非主动作——
                                // Filled→FilledTonalButton（避免双深色 pill 并排，
                                // 全屏最硬边界 ΔL0.71 只留给唯一的 Submit）
                                androidx.compose.material3.FilledTonalButton(
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

/**
 * 提问卡答案 toggle 纯函数——[QuestionCard] onOptionClick 的单一真相源
 * （CustomAnswerToggleFlowTest 直接调用本函数，不再复刻镜像）。
 *
 * 核心不变量（2026-08-18 用户反馈修复）：**选项槽位与自定义槽位互不挤占**——
 * 点选项只改选项槽位（自定义条目保留），toggle 自定义条目（保存/✕删除/
 * 编辑替换）只改自定义槽位（选项选择保留）。旧实现单选分支整表替换
 * `listOf(label)`：点选项会丢已保存的自定义答案、保存自定义会静默丢已选
 * 选项——两个方向都是同一根因。
 *
 * 自定义槽位恒 ≤1：UI 三态输入框模式保证（输入框仅在没有自定义答案时
 * 显示；编辑替换 = 先 toggle off 旧值再 toggle on 新值）。
 *
 * @param isSingle true=单选（选项槽位互斥：再点已选项清空，否则替换）；
 *                 false=多选（选项槽位 toggle 追加/移除，顺序保留）
 */
internal fun toggleQuestionAnswer(
    current: List<String>,
    label: String,
    optionLabels: Set<String>,
    isSingle: Boolean
): List<String> {
    val options = current.filter { it in optionLabels }
    val customs = current.filter { it !in optionLabels }
    return if (label !in optionLabels) {
        // 自定义条目 toggle——选项槽位原样保留
        val newCustoms = if (label in customs) customs - label else customs + label
        options + newCustoms
    } else if (isSingle) {
        // 单选：选项槽位互斥（再点已选=清空），自定义槽位保留
        val newOptions = if (options == listOf(label)) emptyList() else listOf(label)
        newOptions + customs
    } else {
        // 多选：选项槽位 toggle（顺序保留），自定义槽位保留
        val newOptions = if (label in options) options - label else options + label
        newOptions + customs
    }
}
