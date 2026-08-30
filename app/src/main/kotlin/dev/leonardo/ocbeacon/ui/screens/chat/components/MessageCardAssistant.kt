package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.ui.screens.chat.components.RenderReadiness
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ContextToolGroupCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.delay

/**
 * 智能体消息气泡——统一容器（MessageBubble）：
 * 标签栏（时间 + "智能体"）+ 正文（renderItems：文本/推理/工具卡片/分隔线）+
 * 统计栏（agent 标签 / 提供商·模型 / 时长 / 复制）+ 错误展示（气泡内）。
 * 左对齐 + surfaceVariant 底色 + ShapeTokens.medium 圆角。
 */
@Composable
internal fun MessageCardAssistant(
    renderableTurn: RenderableTurn,
    currentMessage: ChatMessage,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    isAmoled: Boolean,
    isTurnLast: Boolean,
    /** turn 级流式判定（turn 内任一消息 completed == null）。多消息 turn 时
     *  代表消息（oldest）可能已完成，仅看自身会漏判流式 → 统计栏延迟到
     *  回复完毕才出现（2026-08 修复：统计栏应在气泡出现时同步出现）。 */
    isStreamingTurn: Boolean = false,
    agents: List<AgentInfo> = emptyList(),
    // 2026-08-16（agent 徽标可点击）：点击徽标=选中该 agent 到输入栏（复用
    // selectAgent 链，影响下一次发送——历史消息的 agent 不可改写，官方语义）
    onAgentClick: ((String) -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
    /** 嵌入思考卡片（ReasoningBlock）的待处理提问（2026-08-14）。 */
    pendingQuestion: SseEvent.QuestionAsked? = null,
    onQuestionSubmit: ((String, List<List<String>>) -> Unit)? = null,
    onQuestionReject: ((String) -> Unit)? = null,
    /** E2E-C 终版：应用级答案存储透传（QuestionAnswerStore 单例） */
    questionAnswersCache: dev.leonardo.ocbeacon.ui.screens.chat.QuestionAnswerStore? = null,
    /** #234：事件卡统一展开表——本函数仅在防御性 SyntheticNotice 分支使用。 */
    eventExpandedStates: MutableMap<String, Boolean>,
) {
    // D2-L22：原 if(isAmoled) 两分支相同（死条件）——直接取 onSurface
    val textColor = MaterialTheme.colorScheme.onSurface

    if (renderableTurn.isEmpty) return

    val compact = LocalChatDensity.current == ChatDensity.Compact
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val showTurnDividers = LocalShowTurnDividers.current

    // 保留供统计栏显示（agent/模型）
    val assistantMsg = currentMessage.message as? Message.Assistant

    // 2026-08-20 滚动稳定性：滚动预解析消费端——assistant 长文本 part 组合时
    // 优先取后台预解析结果（Parsed state → Markdown(state) 直接渲染，首测即
    // 最终高度）。根因（ScrollDiag 取证）：异步解析使初次组合仅测得占位高度
    // （412px），解析完成后长回复暴涨（+16334px）→ LazyColumn 锚点修正 →
    // fling 中视口瞬移。驱动端为渲染供给协调器（RenderSupplyCoordinator）。
    val readinessRegistry = LocalRenderReadiness.current
    // turn 级流式判定：turn 内任一消息仍在流式即视为流式（多消息 turn 的
    // 代表消息是 oldest 可能已完成，仅看自身会漏判 → 统计栏延迟出现）。
    val isStreaming = isStreamingTurn || (assistantMsg?.time?.completed == null)

    // 预计算的元数据
    val agentName = renderableTurn.agentName
    val copyText = renderableTurn.copyText
    val modelId = renderableTurn.modelId

    // 2026-08-17（多卡片 bug 修复）：待处理提问卡片每条消息只渲染一张。
    // 原条件（part is Reasoning || part is Tool）在每个符合条件的 part 后都
    // 渲染 → 一条消息含多个 Reasoning/Tool part（如先思考再调工具）时出现
    // N 张相同卡片（用户报告"主对话流突然多出好多卡片，提交一张后其余消失"
    // ——提交后 pendingQuestion 移除，全部重复卡一起消失）。
    // 锚定策略：优先 pendingQuestion.tool.callId 精确匹配的 Tool part；
    // 否则最后一个 Reasoning/Tool part（保持"渲染在思考流末尾"原语义）。
    val questionAnchorPartId = remember(pendingQuestion?.id, renderableTurn) {
        if (pendingQuestion == null) {
            null
        } else {
            val singles = renderableTurn.renderItems.mapNotNull { item ->
                (item as? RenderItem.GroupedParts)?.group
                    ?.let { it as? PartGroup.Single }?.part
            }
            val callId = pendingQuestion.tool?.callId?.takeIf { it.isNotBlank() }
            val toolMatch = callId?.let { cid ->
                singles.lastOrNull { it is Part.Tool && (it.callId == cid || it.id == cid) }
            }
            toolMatch?.id
                ?: singles.lastOrNull { it is Part.Reasoning || it is Part.Tool }?.id
        }
    }

    // 2026-08-30 提问卡跳变根修：提交/忽略后 pendingQuestion 立即移除 →
    // QuestionCard 被直接移出组合（-954px 两帧塌陷，ScrollDiag RESIZE 实证
    // 1300→1096→346），SSE part 完成回写后再 +120px 出现 Asked 卡——两个
    // 无动画突变即用户报告的「提问卡片往下跳」。修复 = 槽位动画化：
    // ① 锚点记忆——pendingQuestion 消失后保留最后一次锚 part id，exit 动画
    //   期间 QuestionCard 仍在锚位置组合；
    // ② 实例记忆——AV exit 期间 content 以最后一次非空 question 渲染。
    // 到达方向（null→非 null）走同一 AV 的 expandVertically enter =
    // 「向下展开」（用户 2026-08-30 裁决方向），到达时 +346px 一帧突变同治。
    var retainedAnchorId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    if (questionAnchorPartId != null) retainedAnchorId = questionAnchorPartId
    val effectiveAnchorId = questionAnchorPartId ?: retainedAnchorId
    var lastQuestion by remember { androidx.compose.runtime.mutableStateOf<SseEvent.QuestionAsked?>(null) }
    if (pendingQuestion != null) lastQuestion = pendingQuestion
    // 到达首帧动画开关：AV 首次组合即 visible=true 时不播 enter（新消息 item
    // 首帧就带问题卡 → RESIZE 0→346 一帧到位）。延迟一帧置 true 强制走
    // expandVertically enter。rememberSaveable：滑出视口重组不重播。
    var qEntered by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { qEntered = true }

    // 统一统计栏 —— 消息气泡页脚（流式/完成是同一事物的两种状态，2026-08-07 合并）。
    // 流式：显示实时耗时（ticker 每秒刷新）；完成：显示固定时长 + 复制按钮。
    // 显示条件：流式必有；完成态有统计内容（时长/模型/agent）或仅需复制按钮时显示。
    val durationMs = renderableTurn.durationMs
    val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()
    val showStatsBar = isStreaming || hasFooter || (copyText != null && isTurnLast)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        MessageBubble(
            alignEnd = false,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            border = if (isAmoled) AmoledDefaultBorder else null,
            shape = ShapeTokens.medium,
            label = stringResource(R.string.chat_label_agent),
            // 2026-08-16（标题栏规范·类型图标）：智能体=SmartToy
            labelLeading = {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                )
            },
            timeMs = currentMessage.message.time.created,
            statsBar = if (showStatsBar) {
                {
                    // 耗时显示：流式 = 实时 ticker（独立子 composable，重组只限单个 Text，
                    // 不触发整个 footer Row——#47：原 100ms ticker 在 footer 级 state，
                    // 与 48ms flush 叠加 ~30 次/s footer 重组）；完成 = 固定时长。
                    val startMs = renderableTurn.turnStartMs ?: assistantMsg?.time?.created

                    // Agent 名称标签（2026-08-12：与输入组件 agent 选择器同款紧凑标签——
                    // M3 SuggestionChip 32dp 偏大，用户确认改回紧凑样式）
                    if (!agentName.isNullOrBlank()) {
                        val tagColor = agentColor(agentName, agents)
                        AgentTag(agent = agentName, tagColor = tagColor, onClick = { onAgentClick?.invoke(agentName) })
                    }
                    // 提供商图标 + 模型名
                    val hasProviderOrModel = assistantMsg?.providerId != null || !modelId.isNullOrBlank()
                    if (hasProviderOrModel) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 10.dp,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            if (!modelId.isNullOrBlank()) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // 2026-08-15 用户要求：移除 Token 占比圆环——无信息量。
                    // 统计栏仅保留：agent 徽标 / 模型图标+模型名 / 耗时 / 右对齐复制。
                    // 耗时（流式 = 实时 ticker 子 composable；完成 = 固定）
                    if (isStreaming && startMs != null) {
                        StreamingElapsedText(startMs)
                    } else if (!isStreaming && (durationMs ?: 0L) > 0) {
                        Text(
                            text = formatDuration(durationMs!!),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 复制按钮（仅完成态）
                    if (!isStreaming && copyText != null) {
                        CopyButton(
                            text = copyText,
                            modifier = Modifier.size(14.dp),
                            onCopied = onCopy
                        )
                    }
                }
            } else null,
        ) {
            // 渲染预计算项 —— 组合期间零过滤/零分组。
            for (item in renderableTurn.renderItems) {
                when (item) {
                    is RenderItem.TurnDivider -> {
                        if (showTurnDividers) {
                            // 暗色模式下 outlineVariant 偏暗 + 半透明几乎不可见，改用更亮的 outline
                            val dividerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            }
                            HorizontalDivider(
                                // #183：turn 分割线上下留空减半（用户期望）
                                modifier = Modifier.padding(vertical = if (compact) 1.5.dp else 3.dp),
                                color = dividerColor
                            )
                        }
                    }
                    is RenderItem.RepeatingTool -> {
                        // #247（2026-08-28 用户裁决）：回合内连续同键 tool 卡折叠——
                        // 首张正常渲染 + ×N 徽标（与 #243 合成卡去重同款交互）。
                        key(item.part.id) {
                            Box {
                                PartContent(
                                    part = item.part,
                                    textColor = textColor,
                                    isUser = false,
                                    onViewSubSession = onViewSubSession,
                                    onOpenFile = onOpenFile,
                                    preParsedState = null,
                                    asyncParse = !isStreaming,
                                    turnAgentName = if (item.part.tool == "task") {
                                        renderableTurn.taskAgentName
                                    } else null,
                                )
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 5.dp, end = 7.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                                ) {
                                    Text(
                                        text = "×" + item.count,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                    is RenderItem.SyntheticNotice -> {
                        // 合成通知卡片（轮次完成）。「嵌入气泡」为 2026-08-11 旧方案：
                        // 现合成通知独立成泡（turn 分组不并入 assistant turn，见
                        // computeTurnGroups），本渲染项已无生产者——防御保留。
                        key(item.msgId) {
                            SyntheticNotificationCard(
                                currentMessage = item.message,
                                onViewSubSession = onViewSubSession,
                                onLocateTask = onLocateTask,
                                eventExpandedStates = eventExpandedStates,
                            )
                        }
                    }
                    is RenderItem.GroupedParts -> {
                        when (item.group) {
                            is PartGroup.Context -> key(item.group.parts.first().id) {
                                ContextToolGroupCard(
                                    parts = item.group.parts,
                                    onOpenFile = onOpenFile ?: {},
                                )
                            }
                            is PartGroup.Single -> key(item.group.part.id) {
                                // 滚动预解析消费：长文本 part 取 Parsed state（与驱动端
                                // key 约定：partId；阈值一致 ≥200 字符）。
                                // 2026-08-20 滚动卡顿根因修复：原 current() 走快照 Map 读
                                // （整 Map 依赖，滚动期任一 remove/put 全卡片失效重组）；
                                // 改为订阅该 part 的 StateFlow——写 Map 零重组，预解析完成
                                // 仅重组这一个 scope。
                                val longTextPart = (item.group.part as? Part.Text)
                                    ?.takeIf {
                                        it.text.length >= 200 && it.synthetic != true &&
                                            it.ignored != true && !it.text.contains("User has answered")
                                    }
                                val preParsedAssistantState = if (longTextPart != null) {
                                    val partReadiness by readinessRegistry
                                        .flow(longTextPart.id)
                                        .collectAsState()
                                    (partReadiness as? RenderReadiness.Parsed)?.state
                                } else {
                                    null
                                }
                                PartContent(
                                    part = item.group.part,
                                    textColor = textColor,
                                    isUser = false,
                                    onViewSubSession = onViewSubSession,
                                    onOpenFile = onOpenFile,
                                    preParsedState = preParsedAssistantState,
                                    // 2026-08-22：非流式降级异步解析（流式 turn 走库
                                    // rememberMarkdownState 增量路径——SSE 铁律不动）
                                    asyncParse = !isStreaming,
                                    turnAgentName = if (item.group.part is Part.Tool && item.group.part.tool == "task") {
                                        renderableTurn.taskAgentName
                                    } else null,
                                )
                                // 2026-08-14：待处理提问渲染为独立提问卡片——
                                // 位于思考卡片（ReasoningBlock）之后、气泡内；
                                // 不嵌入推理文本内部（用户反馈"嵌入到思考过程中"是 bug）。
                                // 2026-08-14 走查修复（#131）：V1 的 question 工具调用消息是
                                // Part.Tool 而非 Part.Reasoning——原条件仅 Reasoning 导致
                                // tool 消息上的问题卡片不渲染；同时 unembeddedQuestions 因
                                // 已匹配嵌入而排除 → 卡片凭空消失 + 输入框禁用（UI 卡死）。
                                // 放宽为 Reasoning 或 Tool（question/permission 工具调用）都渲染。
                                // 2026-08-17（多卡片修复）：锚定 questionAnchorPartId——
                                // 只在锚 part 后渲染一张（原条件会按 part 数量重复渲染）。
                                // 2026-08-30 用户裁决：撤销展开补偿，回归 AV 出厂默认
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = qEntered && pendingQuestion != null &&
                                        item.group.part.id == effectiveAnchorId,
                                    enter = CardExpandEnterTransition,
                                    exit = CardExpandExitTransition,
                                ) {
                                    val avQuestion = pendingQuestion ?: lastQuestion
                                    if (avQuestion != null &&
                                        item.group.part.id == effectiveAnchorId
                                    ) {
                                        QuestionCard(
                                            question = avQuestion,
                                            onSubmit = { answers ->
                                                onQuestionSubmit?.invoke(avQuestion.id, answers)
                                            },
                                            onReject = {
                                                onQuestionReject?.invoke(avQuestion.id)
                                            },
                                            answersStore = questionAnswersCache,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 错误展示（气泡内）
            if (renderableTurn.errorText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
                    shape = ShapeTokens.mediumSmall,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) AlphaTokens.HIGH else AlphaTokens.FAINT)),
                    tonalElevation = 0.dp,
                ) {
                    ErrorPayloadContent(
                        text = renderableTurn.errorText,
                        textStyle = MaterialTheme.typography.bodySmall,
                        textColor = textColor,
                        modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * 流式耗时实时显示（#47 优化）。
 *
 * 独立子 composable：内部 ticker（2026-08-15 用户要求：1s → 300ms，
 * 秒级小数进度感）更新自身 state——重组范围仅限
 * 本 Text，不触发整个 footer Row 重组（原实现 ticker state 在 footer 级，
 * 与 48ms SSE flush 叠加导致 ~30 次/s footer 重组）。
 */
@Composable
private fun StreamingElapsedText(startMs: Long) {
    var elapsedText by remember { mutableStateOf("0s") }
    LaunchedEffect(startMs) {
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            elapsedText = formatDuration(elapsedMs)
            delay(100)
        }
    }
    Text(
        text = elapsedText,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
    )
}

/**
 * 2026-08-20 fling 巨帧根治：超长 assistant turn 的块级分片渲染。
 *
 * 根因：一条长消息 = 一个 LazyItem；LazyColumn 子项滚动方向无限高约束 →
 * 首次组合必须建完整棵 Markdown 树（130K 字符 ≈ 300+ 块 = 单帧 50-80ms，
 * 真机 trace 单个 recompose scope 49.7ms；prefetch 单位是 item，巨型 item
 * 预取无效——prefetch:measure max 150ms）。分片：已完结长 turn 发射 N 个
 * chunk item（见 ChatMessageList / buildChatEntries），每 item 只组合一片。
 *
 * 本组件只处理已完结 turn：无流式补偿、无 pendingQuestion（历史消息）、
 * 无 error（错误 turn 不分片——buildChatEntries 未排除，但 errorText 非空
 * 的 turn 通常无巨型 text part；防御性在末段渲染 errorText）。
 * AMOLED 边框简化：分片段不描边（AmoledDefaultBorder 是整圈 BorderStroke，
 * 无法分段；AMOLED + 巨型历史消息的罕见组合接受无框）。
 */
@Composable
internal fun ChunkedAssistantMessage(
    renderableTurn: RenderableTurn,
    currentMessage: ChatMessage,
    chunk: ChatEntry.Chunk,
    isAmoled: Boolean,
    isTurnLast: Boolean,
    agents: List<AgentInfo>,
    onAgentClick: ((String) -> Unit)?,
    onCopy: (() -> Unit)?,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    onLocateTask: ((String) -> Unit)?,
    /** #234：事件卡统一展开表。 */
    eventExpandedStates: MutableMap<String, Boolean>,
) {
    if (renderableTurn.isEmpty) return
    val compact = LocalChatDensity.current == ChatDensity.Compact
    val textColor = MaterialTheme.colorScheme.onSurface
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val readinessRegistry = LocalRenderReadiness.current
    val assistantMsg = currentMessage.message as? Message.Assistant

    // 巨型 part 在 renderItems 中的定位（其余 items 按位置分首/末段）
    val targetIdx = renderableTurn.renderItems.indexOfFirst { item ->
        (item as? RenderItem.GroupedParts)?.group is PartGroup.Single &&
            ((item.group as PartGroup.Single).part.id == chunk.plan.partId)
    }
    val range = chunk.plan.ranges[chunk.chunkIndex]
    val horizPad = if (compact) 10.dp else SpacingTokens.LG.dp
    val vertPad = if (compact) SpacingTokens.SM.dp else 14.dp

    // 分段 shape：首段顶圆角 / 中段直角 / 末段底圆角（12dp = ShapeTokens.medium）
    val shape = when {
        chunk.isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        chunk.isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Surface(color = containerColor, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                start = horizPad, end = horizPad,
                top = if (chunk.isFirst) vertPad else 0.dp,
                bottom = if (chunk.isLast) vertPad else 0.dp,
            ),
        ) {
            // ① 标签栏（仅首段）——与 MessageBubble 标签栏视觉一致
            if (chunk.isFirst) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = if (compact) SpacingTokens.XS.dp else 10.dp),
                ) {
                    Text(
                        text = remember(currentMessage.message.time.created) {
                            dev.leonardo.ocbeacon.util.DateFormatters.messageTimestamp(currentMessage.message.time.created)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    Text(
                        text = stringResource(R.string.chat_label_agent),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            // ② 首段：巨型 part 之前的 renderItems（reasoning / 工具卡等）
            if (chunk.isFirst && targetIdx > 0) {
                ChunkAssistantItems(
                    items = renderableTurn.renderItems.subList(0, targetIdx),
                    textColor = textColor,
                    isAmoled = isAmoled,
                    onViewSubSession = onViewSubSession,
                    onOpenFile = onOpenFile,
                    onLocateTask = onLocateTask,
                    eventExpandedStates = eventExpandedStates,
                    renderableTurn = renderableTurn,
                    compact = compact,
                    readinessRegistry = readinessRegistry,
                )
            }
            // ③ Markdown 分片主体（所有段都有）
            SelectionContainer {
                // #246 插桩：chunk 组合期事实——定位头片丢失的准确环节
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "ChunkDiag",
                        "compose key=" + chunk.key + " idx=" + chunk.chunkIndex + "/" + chunk.chunkCount +
                            " range=" + range.first + ".." + range.last +
                            " anchor=" + (chunk.plan.rangeAnchors.getOrNull(chunk.chunkIndex)?.take(16) ?: "null") +
                            " kids=" + try { chunk.plan.state.node.children.size } catch (e: Exception) { -1 }
                    )
                }
                dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent(
                    markdown = "",
                    textColor = textColor,
                    isUser = false,
                    preParsedState = chunk.plan.state,
                    blockRange = range,
                    // #246 时序排序：锚点重定位（详见 MarkdownChunking.rangeAnchors）
                    blockAnchor = chunk.plan.rangeAnchors.getOrNull(chunk.chunkIndex),
                )
            }
            // ④ 末段：巨型 part 之后的 renderItems + 统计栏 + error
            if (chunk.isLast) {
                if (targetIdx in 0 until renderableTurn.renderItems.size - 1) {
                    ChunkAssistantItems(
                        items = renderableTurn.renderItems.subList(targetIdx + 1, renderableTurn.renderItems.size),
                        textColor = textColor,
                        isAmoled = isAmoled,
                        onViewSubSession = onViewSubSession,
                        onOpenFile = onOpenFile,
                        onLocateTask = onLocateTask,
                        eventExpandedStates = eventExpandedStates,
                        renderableTurn = renderableTurn,
                        compact = compact,
                        readinessRegistry = readinessRegistry,
                    )
                }
                if (renderableTurn.errorText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
                        shape = ShapeTokens.mediumSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            text = renderableTurn.errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = 10.dp),
                        )
                    }
                }
                ChunkStatsBar(
                    renderableTurn = renderableTurn,
                    assistantMsg = assistantMsg,
                    isTurnLast = isTurnLast,
                    agents = agents,
                    onAgentClick = onAgentClick,
                    onCopy = onCopy,
                )
            }
        }
    }
}

/** 分片场景的 renderItems 渲染（复制自 MessageCardAssistant 主循环的精简版：
 *  无 pendingQuestion / 无 question 锚定——历史已完结 turn 不含待处理提问）。 */
@Composable
private fun ChunkAssistantItems(
    items: List<RenderItem>,
    textColor: Color,
    isAmoled: Boolean,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    onLocateTask: ((String) -> Unit)?,
    /** #234：事件卡统一展开表。 */
    eventExpandedStates: MutableMap<String, Boolean>,
    renderableTurn: RenderableTurn,
    compact: Boolean,
    readinessRegistry: RenderReadinessRegistry,
) {
    val showTurnDividers = LocalShowTurnDividers.current
    for (item in items) {
        when (item) {
            is RenderItem.TurnDivider -> if (showTurnDividers) {
                val dividerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                }
                HorizontalDivider(
                    // #183：turn 分割线上下留空减半（与完整气泡处同改）
                    modifier = Modifier.padding(vertical = if (compact) 1.5.dp else 3.dp),
                    color = dividerColor,
                )
            }
            is RenderItem.SyntheticNotice -> key(item.msgId) {
                SyntheticNotificationCard(
                    currentMessage = item.message,
                    onViewSubSession = onViewSubSession,
                    onLocateTask = onLocateTask,
                    eventExpandedStates = eventExpandedStates,
                )
            }
            is RenderItem.RepeatingTool -> {
                // #247：分片路径同款折叠渲染（分片 turn 恒非流式）
                key(item.part.id) {
                    Box {
                        PartContent(
                            part = item.part,
                            textColor = textColor,
                            isUser = false,
                            onViewSubSession = onViewSubSession,
                            onOpenFile = onOpenFile,
                            preParsedState = null,
                            asyncParse = true,
                            turnAgentName = if (item.part.tool == "task") renderableTurn.taskAgentName else null,
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 5.dp, end = 7.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                        ) {
                            Text(
                                text = "×" + item.count,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            is RenderItem.GroupedParts -> when (item.group) {
                is PartGroup.Context -> key(item.group.parts.first().id) {
                    ContextToolGroupCard(
                        parts = item.group.parts,
                        onOpenFile = onOpenFile ?: {},
                    )
                }
                is PartGroup.Single -> key(item.group.part.id) {
                    val part = item.group.part
                    val preParsed = (part as? Part.Text)
                        ?.takeIf { it.text.length >= 200 && it.synthetic != true && it.ignored != true && !it.text.contains("User has answered") }
                        ?.let { tp ->
                            val pr by readinessRegistry.flow(tp.id).collectAsState()
                            (pr as? RenderReadiness.Parsed)?.state
                        }
                    PartContent(
                        part = part,
                        textColor = textColor,
                        isUser = false,
                        onViewSubSession = onViewSubSession,
                        onOpenFile = onOpenFile,
                        preParsedState = preParsed,
                        // 分片 turn 恒非流式（流式 turn 不分片）——降级异步解析
                        asyncParse = true,
                        turnAgentName = if (part is Part.Tool && part.tool == "task") renderableTurn.taskAgentName else null,
                    )
                }
            }
        }
    }
}

/** 分片场景统计栏（历史消息：isStreaming=false 恒成立）。 */
@Composable
private fun ChunkStatsBar(
    renderableTurn: RenderableTurn,
    assistantMsg: Message.Assistant?,
    isTurnLast: Boolean,
    agents: List<AgentInfo>,
    onAgentClick: ((String) -> Unit)?,
    onCopy: (() -> Unit)?,
) {
    val agentName = renderableTurn.agentName
    val copyText = renderableTurn.copyText
    val modelId = renderableTurn.modelId
    val durationMs = renderableTurn.durationMs
    val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()
    if (!hasFooter && !(copyText != null && isTurnLast)) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!agentName.isNullOrBlank()) {
            AgentTag(agent = agentName, tagColor = agentColor(agentName, agents), onClick = { onAgentClick?.invoke(agentName) })
        }
        val hasProviderOrModel = assistantMsg?.providerId != null || !modelId.isNullOrBlank()
        if (hasProviderOrModel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (assistantMsg?.providerId != null) {
                    ProviderIcon(
                        providerId = assistantMsg.providerId,
                        size = 10.dp,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                }
                if (!modelId.isNullOrBlank()) {
                    Text(
                        text = modelId,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if ((durationMs ?: 0L) > 0) {
            Text(
                text = formatDuration(durationMs!!),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (copyText != null) {
            CopyButton(text = copyText, modifier = Modifier.size(14.dp), onCopied = onCopy)
        }
    }
}
