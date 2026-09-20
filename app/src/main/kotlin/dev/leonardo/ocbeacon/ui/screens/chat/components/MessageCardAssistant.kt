package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
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
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ContextToolGroupCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.turnLedgerSummary
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.MessageDetailInput
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.RowCapabilities
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.TurnNumber
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.messageRowTail
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.statusBadgeFor
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.copyToClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolExpandedStates
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon

/**
 * 智能体消息（v2 两段式：正文 + 尾部统计栏）——扁平，无气泡容器、无头部标签栏。
 * 正文 = renderItems（文本 / 推理 / 工具卡片 / 分隔线）+ 错误展示；
 * 尾部 = 状态徽标 / 逐消息 agent 标签 / 提供商·模型 / 时长 / 步数·工具摘要
 *        + 复制 + 「详情」入口（时间与低频动作在详情弹窗里）。
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
    /**
     * #310② 消息反馈：脚部 👍/👎 动作位。仅 **已完结** assistant
     * 消息（!isStreaming）且 onRateMessage 非 null（DSH serverType 门）
     * 时渲染——SSE 流式铁律：流式 turn 的高度补偿不受
     * 脚部内容变化影响（图标随完结态一次性出现，与复制键同一时刻）。
     */
    messageFeedback: dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem? = null,
    onRateMessage: ((dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating) -> Unit)? = null,
    /** 2026-09-12 扁平化：第 N 轮编号（server 优先；null = 不渲染——US#28）。 */
    turnNumber: TurnNumber? = null,
    /** 2026-09-12 扁平化：「从此轮分支」尾部动作（null = 不显示——US#11）。 */
    onForkFromTurn: (() -> Unit)? = null,
    /** 2026-09-12 扁平化：删除消息（能力位就绪才传入——US#35）。 */
    onDeleteMessage: (() -> Unit)? = null,
    /** 2026-09-12 扁平化：行模型能力位（尾部字段/动作门控单源）。 */
    caps: RowCapabilities? = null,
    /** #422 历史懒加载:大组拆条目发射时尾片置 true——StepGroup 条目整体
     *  跳过(折叠行由 ChatEntry.StepGroupHead 条目渲染,内容由 Body 条目)。 */
    skipStepGroupItem: Boolean = false,
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

    MessageBubble(
            alignEnd = false,
            containerColor = Color.Transparent,
            flat = true,
            tailExtra = {
                // #410：尾部单点（主路径与分片/分段共用；产出行流式完结门控在内部）
                AssistantTurnTail(
                    renderableTurn = renderableTurn,
                    assistantMsg = assistantMsg,
                    isTurnLast = isTurnLast,
                    isStreaming = isStreaming,
                    agents = agents,
                    onAgentClick = onAgentClick,
                    onCopy = onCopy,
                    messageFeedback = messageFeedback,
                    onRateMessage = onRateMessage,
                    turnNumber = turnNumber,
                    onForkFromTurn = onForkFromTurn,
                    onDeleteMessage = onDeleteMessage,
                    onOpenFile = onOpenFile,
                    caps = caps,
                )
            },

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
                                CardExpandReveal(
                                    visible = qEntered && pendingQuestion != null &&
                                        item.group.part.id == effectiveAnchorId,
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

                    // #422:step 折叠组——流式 turn 恒平铺(跟随生成,DSH 同款时机);
                    // 非流式走 StepGroupCard 折叠(最终回答=最后消息恒平铺,装配层保证);
                    // 历史懒加载拆条目时本 item 由 Head/Body 条目承担,此处整体跳过
                    is RenderItem.StepGroup -> key(item.msgId) {
                        if (skipStepGroupItem) {
                            // 大组展开态:内容已拆为独立 LazyItem(见 buildChatEntries)
                        } else if (isStreaming) {
                            item.groups.forEach { g ->
                                val sp = (g as? PartGroup.Single)?.part
                                if (sp != null) {
                                    key(sp.id) {
                                        PartContent(
                                            part = sp,
                                            textColor = textColor,
                                            isUser = false,
                                            onViewSubSession = onViewSubSession,
                                            onOpenFile = onOpenFile,
                                            asyncParse = false,
                                        )
                                    }
                                }
                            }
                        } else {
                            StepGroupCard(
                                step = item,
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
                    }
                }
            }

            // #319（用户裁决：提问卡进主对话流）：DSH 0.1.2 waterfall 提问无
            // tool/part 锚（questionAnchorPartId=null 且无 retained 锚）——气泡尾
            // fallback 槽位：卡渲染在本 turn 内容之后、错误展示之前，与 OpenCode
            // 锚定路径同一 QuestionCard 组件/动画语言（样式统一）；随消息流滚动。
            CardExpandReveal(
                visible = qEntered && pendingQuestion != null && effectiveAnchorId == null,
            ) {
                val avQuestion = pendingQuestion ?: lastQuestion
                if (avQuestion != null) {
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
    // #410：详情弹窗装配随尾部单点（AssistantTurnTail）内移——主路径不再持有。
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
            // #338：下限钳制 0——startMs 可能是服务器信封时刻（DSH created 腿），
            // 设备钟慢于服务器时前 ~skew 窗口内差值为负（真机实证 -207ms 闪现）。
            // 与 ReasoningBlock 计时器同款钳制（跨钟域差值不显示负数）。
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
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
 * #310② 消息反馈 👍/👎 轻量图标钮对（统计栏尾部动作位）。
 *
 * 已评态（current.rating 匹配）图标亮起（primary）；点击统一走
 * onRate（未评→评／同向→撤销／换向→换向的裁决在
 * [dev.leonardo.ocbeacon.ui.screens.chat.MessageFeedbackDelegate]）。仅已完结
 * assistant 消息渲染（调用方门控）——不进入流式 turn 高度补偿。
 */
@Composable
internal fun MessageFeedbackButtons(
    current: dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem?,
    onRate: (dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(
            onClick = { onRate(dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive) },
            modifier = Modifier.size(18.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.ThumbUp,
                contentDescription = stringResource(R.string.chat_feedback_positive),
                modifier = Modifier.size(14.dp),
                tint = if (current?.rating == dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Positive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                },
            )
        }
        IconButton(
            onClick = { onRate(dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Negative) },
            modifier = Modifier.size(18.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.ThumbDown,
                contentDescription = stringResource(R.string.chat_feedback_negative),
                modifier = Modifier.size(14.dp),
                tint = if (current?.rating == dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating.Negative) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                },
            )
        }
    }
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
    /** #310②：分片 turn 恒已完结——反馈动作位直接门控于回调非 null。 */
    messageFeedback: dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem? = null,
    onRateMessage: ((dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating) -> Unit)? = null,
    turnNumber: TurnNumber? = null,
    onForkFromTurn: (() -> Unit)? = null,
    onDeleteMessage: (() -> Unit)? = null,
    caps: RowCapabilities? = null,
) {
    if (renderableTurn.isEmpty) return
    val compact = LocalChatDensity.current == ChatDensity.Compact
    val textColor = MaterialTheme.colorScheme.onSurface
    val readinessRegistry = LocalRenderReadiness.current
    val assistantMsg = currentMessage.message as? Message.Assistant

    // 巨型 part 在 renderItems 中的定位（其余 items 按位置分首/末段）
    val targetIdx = renderableTurn.renderItems.indexOfFirst { item ->
        (item as? RenderItem.GroupedParts)?.group is PartGroup.Single &&
            ((item.group as PartGroup.Single).part.id == chunk.plan.partId)
    }
    val range = chunk.plan.ranges[chunk.chunkIndex]

    // 2026-09-12 扁平化：分片路径同样走三段式（首段头部 / 末段尾部），
    // 去容器外观——三段式布局单点 = MessageSectionScaffold。
    MessageSectionScaffold(
        showTail = chunk.isLast,
        tail = {
            AssistantTurnTail(
                renderableTurn = renderableTurn,
                assistantMsg = assistantMsg,
                isTurnLast = isTurnLast,
                isStreaming = false,
                agents = agents,
                onAgentClick = onAgentClick,
                onCopy = onCopy,
                messageFeedback = messageFeedback,
                onRateMessage = onRateMessage,
                turnNumber = turnNumber,
                onForkFromTurn = onForkFromTurn,
                onDeleteMessage = onDeleteMessage,
                onOpenFile = onOpenFile,
                caps = caps,
            )
        },
    ) {
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
            }
    }
}

/** 分片场景的 renderItems 渲染（复制自 MessageCardAssistant 主循环的精简版：
 *  无 pendingQuestion / 无 question 锚定——历史已完结 turn 不含待处理提问）。 */
@Composable
internal fun ChunkAssistantItems(
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
            // #422:step 折叠组——递归复用本函数渲染 groups(不无限递归:StepGroup
            // 在此解开为 GroupedParts 序列);分片 turn 恒非流式,无流式豁免
            is RenderItem.StepGroup -> key(item.msgId) {
                StepGroupCard(
                    step = item,
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
        }
    }
}

/**
 * #258 Stage B：历史长 turn 的分段渲染（TurnSegmentPlan）——ChunkedAssistantMessage
 * 的推广形态：turn 的 renderItems 切成 N 个 LazyItem（Items 段直接渲染子序列；
 * Giant 段按 AST 区间渲染，复用 #246 锚点重定位）。首段带标签栏、末段带统计栏
 * 与 errorText；段间 shape 顶/底圆角。流式/最近流式 turn 不进入本路径（分段
 * 计划的资格审查在协调器与 buildChatEntries 双重排除）。
 */
@Composable
internal fun SegmentedAssistantMessage(
    renderableTurn: RenderableTurn,
    currentMessage: ChatMessage,
    chunk: ChatEntry.TurnChunk,
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
    /** #310②：分段 turn 恒已完结——反馈动作位直接门控于回调非 null。 */
    messageFeedback: dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem? = null,
    onRateMessage: ((dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating) -> Unit)? = null,
    turnNumber: TurnNumber? = null,
    onForkFromTurn: (() -> Unit)? = null,
    onDeleteMessage: (() -> Unit)? = null,
    caps: RowCapabilities? = null,
) {
    if (renderableTurn.isEmpty) return
    val compact = LocalChatDensity.current == ChatDensity.Compact
    val textColor = MaterialTheme.colorScheme.onSurface
    val readinessRegistry = LocalRenderReadiness.current
    val assistantMsg = currentMessage.message as? Message.Assistant

    // chunkIndex（跨段扁平序号）→ (segment, 段内区间序号)
    var segment: TurnSegmentPlan.Segment? = null
    var rangeInSegment = 0
    var acc = 0
    for (seg in chunk.plan.segments) {
        if (chunk.chunkIndex < acc + seg.chunkCount) {
            segment = seg
            rangeInSegment = chunk.chunkIndex - acc
            break
        }
        acc += seg.chunkCount
    }
    // 2026-09-12 扁平化：分段路径同样走三段式（首段头部 / 末段尾部），
    // 去容器外观——三段式布局单点 = MessageSectionScaffold。
    MessageSectionScaffold(
        showTail = chunk.isLast,
        tail = {
            AssistantTurnTail(
                renderableTurn = renderableTurn,
                assistantMsg = assistantMsg,
                isTurnLast = isTurnLast,
                isStreaming = false,
                agents = agents,
                onAgentClick = onAgentClick,
                onCopy = onCopy,
                messageFeedback = messageFeedback,
                onRateMessage = onRateMessage,
                turnNumber = turnNumber,
                onForkFromTurn = onForkFromTurn,
                onDeleteMessage = onDeleteMessage,
                onOpenFile = onOpenFile,
                caps = caps,
            )
        },
    ) {
            // ② 段主体
            when (val seg = segment) {
                is TurnSegmentPlan.Segment.Items -> {
                    val from = seg.from.coerceIn(0, renderableTurn.renderItems.size)
                    val to = seg.to.coerceIn(from, renderableTurn.renderItems.size)
                    if (to > from) {
                        ChunkAssistantItems(
                            items = renderableTurn.renderItems.subList(from, to),
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
                }
                is TurnSegmentPlan.Segment.Giant -> {
                    val range = seg.ranges.getOrNull(rangeInSegment) ?: seg.ranges.last()
                    SelectionContainer {
                        dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent(
                            markdown = "",
                            textColor = textColor,
                            isUser = false,
                            preParsedState = seg.state,
                            blockRange = range,
                            // #246 锚点重定位（与旧 chunk 路径同语义）
                            blockAnchor = seg.anchors.getOrNull(rangeInSegment),
                        )
                    }
                }
                null -> Unit
            }
            // ③ 末段：errorText + 统计栏
            if (chunk.isLast) {
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
            }
    }
}

/**
 * #410：助手消息尾部统计栏**单点**——主路径与分片 / 分段路径共用一份语义
 * （此前主路径 statsBar 与 ChunkStatsBar 约 120 行同构，评审 S1）。
 *
 * 信息簇：状态徽标（v2 尾部化）→ 逐消息 agent 标签 → provider·模型 → 耗时
 * （流式实时 ticker / 完成固定）→ 步数·工具摘要（US#14 历史轮展开）；动作簇：
 * 复制 + 「详情」入口（时间 / 低频动作在 [MessageDetailDialog]）。产出行流式
 * 完结门控（SSE 铁律）与详情弹窗装配都在本单点内。
 */
@Composable
private fun AssistantTurnTail(
    renderableTurn: RenderableTurn,
    assistantMsg: Message.Assistant?,
    isTurnLast: Boolean,
    isStreaming: Boolean,
    agents: List<AgentInfo>,
    onAgentClick: ((String) -> Unit)?,
    onCopy: (() -> Unit)?,
    messageFeedback: dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem? = null,
    onRateMessage: ((dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating) -> Unit)? = null,
    turnNumber: TurnNumber? = null,
    onForkFromTurn: (() -> Unit)? = null,
    onDeleteMessage: (() -> Unit)? = null,
    onOpenFile: ((String) -> Unit)? = null,
    caps: RowCapabilities? = null,
) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val copyText = renderableTurn.copyText
    val modelId = renderableTurn.modelId
    val durationMs = renderableTurn.durationMs
    val ledger = turnLedgerSummary(renderableTurn, turnNumber?.value ?: 0)
    val toolCallCount = ledger.toolCallCount
    val tailModel = messageRowTail(
        turn = renderableTurn,
        ledger = ledger,
        turnNumber = turnNumber,
        userMessage = null,
        caps = caps ?: RowCapabilities.NONE,
        isStreaming = isStreaming,
        hasCopy = copyText != null && onCopy != null,
        hasRevert = false,
        hasJump = onForkFromTurn != null,
        hasFeedbackSheetItem = onRateMessage != null,
        hasDeleteSheetItem = onDeleteMessage != null,
        hasMarkdownCopySheetItem = copyText != null,
        providerId = assistantMsg?.providerId,
    )
    var showDetailDialog by remember { mutableStateOf(false) }
    val moreClipboard = LocalClipboard.current
    val moreScope = rememberCoroutineScope()
    // US#14：最新轮尾部常显；历史轮默认收起、点击摘要展开（产出文件行随之显隐）。
    var tailExpandedOverride by remember { mutableStateOf<Boolean?>(null) }
    val tailExpanded = tailExpandedOverride ?: isTurnLast
    val statusBadge = statusBadgeFor(
        isStreaming = isStreaming,
        finish = assistantMsg?.finish,
        hasError = assistantMsg?.error != null,
    )
    // 耗时：流式 = 实时 ticker（独立子 composable，重组只限单个 Text，#47）；
    // 完成 = 固定时长。
    val startMs = renderableTurn.turnStartMs ?: assistantMsg?.time?.created

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ① 状态徽标（v2：从头部迁到尾部信息簇首位；完成态不渲染）
            if (statusBadge != null) {
                MessageStatusBadgeLabel(statusBadge)
            }
            // ② 逐消息 agent 标签（v2：OpenCode 逐消息 agent；DSH 恒 null → 走会话级）
            val tailAgent = tailModel.agentName
            if (!tailAgent.isNullOrBlank()) {
                AgentTag(
                    agent = tailAgent,
                    tagColor = agentColor(tailAgent, agents),
                    onClick = onAgentClick?.let { cb -> { cb(tailAgent) } },
                )
            }
            // ③ 提供商图标 + 模型名
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
            // ④ 耗时（流式 = 实时 ticker 子 composable；完成 = 固定）
            if (isStreaming && startMs != null) {
                StreamingElapsedText(startMs)
            } else if (!isStreaming && (durationMs ?: 0L) > 0) {
                Text(
                    text = formatDuration(durationMs!!),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                )
            }
            // ⑤ 步数 · 工具数摘要（US#7）+ US#14 历史轮展开入口
            if (renderableTurn.stepCount > 0 || toolCallCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (!isTurnLast) {
                        Modifier
                            .clip(ShapeTokens.small)
                            .clickable(
                                onClickLabel = stringResource(
                                    if (tailExpanded) R.string.chat_turn_ledger_collapse
                                    else R.string.chat_turn_ledger_expand,
                                ),
                            ) { tailExpandedOverride = !tailExpanded }
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        text = stringResource(
                            R.string.chat_msg_tail_summary,
                            renderableTurn.stepCount,
                            toolCallCount,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                    if (!isTurnLast) {
                        androidx.compose.material3.Icon(
                            imageVector = if (tailExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // ⑥ 复制常显（US#9）
            if (copyText != null && onCopy != null) {
                CopyButton(
                    text = copyText,
                    modifier = Modifier.size(14.dp),
                    onCopied = onCopy
                )
            }
            // ⑦ 「详情」入口（v2：取代「更多」溢出菜单；每条角色消息常驻）
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.a11y_message_detail),
                modifier = Modifier
                    .size(16.dp)
                    .clickable {
                        performHaptic(hapticView, hapticOn)
                        showDetailDialog = true
                    },
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
            )
        }
        // 产出行：SSE 铁律——仅在轮完结后挂载（流式中途写类工具完成即非空 → 脚部高度突变）
        if (tailExpanded && renderableTurn.allStepsCompleted && tailModel.producedFiles.isNotEmpty()) {
            ProducedFilesRow(
                files = renderableTurn.deliverableFiles,
                onOpenFile = onOpenFile,
            )
        }
    }

    // 消息详情弹窗（v2：US#13/#45）
    if (showDetailDialog) {
        MessageDetailDialog(
            input = MessageDetailInput(
                isUser = false,
                timeMs = assistantMsg?.time?.created ?: 0L,
                agentName = renderableTurn.agentName,
                providerId = assistantMsg?.providerId,
                modelId = renderableTurn.modelId,
                durationMs = renderableTurn.durationMs,
                stepCount = renderableTurn.stepCount,
                toolCallCount = toolCallCount,
                tokensTotal = ledger.tokensTotal,
                cost = assistantMsg?.cost,
            ),
            feedback = messageFeedback,
            onRate = onRateMessage,
            onCopyMarkdownSource = copyText?.let { src ->
                {
                    moreScope.launch {
                        moreClipboard.copyToClipboard("copy", src)
                        onCopy?.invoke()
                    }
                }
            },
            onForkFromTurn = onForkFromTurn,
            onDelete = onDeleteMessage,
            onDismiss = { showDetailDialog = false },
        )
    }
}


/**
 * #422 折叠组展开表 key(单一真相源):「step_」前缀与 part id 不冲突
 * (MessageCardAssistant/ChatMessageList 三消费方共用,防手拼漂移断链)。
 */
internal fun stepGroupStateKey(msgId: String): String = "step_" + msgId

/**
 * #422 折叠组计数行(共享组件):Layers 图标 + 「N 步 · M 个工具」,点击 toggle
 * 展开态。StepGroupCard(小组动画路径)与 ChatEntry.StepGroupHead 条目
 * (大组懒加载路径)共用——同一交互入口。
 */
@Composable
internal fun StepGroupFoldRow(
    step: RenderItem.StepGroup,
    modifier: Modifier = Modifier,
) {
    val toolExpandedStates = LocalToolExpandedStates.current
    val onToggleToolExpanded = LocalOnToggleToolExpanded.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val stateKey = stepGroupStateKey(step.msgId)
    val expanded = toolExpandedStates[stateKey] ?: false
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                performHaptic(hapticView, hapticOn)
                onToggleToolExpanded(stateKey, !expanded)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Layers,
            contentDescription = stringResource(if (expanded) R.string.a11y_icon_collapse else R.string.a11y_icon_expand),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
        )
        Text(
            text = stringResource(R.string.chat_msg_tail_summary, step.textCount.coerceAtLeast(1), step.toolCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            maxLines = 1,
        )
    }
}

/**
 * #422 step 折叠组卡：计数行(复用 chat_msg_tail_summary「N steps · M tools」)
 * + CardExpandReveal 展开体(递归调 ChunkAssistantItems 渲染 groups)。
 * 展开态复用工具展开表(key 前缀 step_ 与 part id 不冲突)。
 * 大组(≥ LARGE_STEP_GROUP_WEIGHT)由条目化路径接管,不走本卡。
 */
@Composable
private fun StepGroupCard(
    step: RenderItem.StepGroup,
    textColor: Color,
    isAmoled: Boolean,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    onLocateTask: ((String) -> Unit)?,
    eventExpandedStates: MutableMap<String, Boolean>,
    renderableTurn: RenderableTurn,
    compact: Boolean,
    readinessRegistry: RenderReadinessRegistry,
) {
    val toolExpandedStates = LocalToolExpandedStates.current
    val stateKey = stepGroupStateKey(step.msgId)
    val expanded = toolExpandedStates[stateKey] ?: false
    Column(modifier = Modifier.fillMaxWidth()) {
        StepGroupFoldRow(step = step)
        CardExpandReveal(visible = expanded) {
            // #422 二轮修复:ChunkAssistantItems 是裸 for(设计为在父 Column 内
            // 调用)——直接放进 Reveal 的 Box 会使各 part 堆叠在 (0,0) 互相叠压
            // (实测:表格/读取卡/标题三层重叠)。包裹同 SegmentedAssistantMessage
            // 的 Column(XS 间距)保持视觉一致。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
            ) {
                ChunkAssistantItems(
                    items = step.groups.map { RenderItem.GroupedParts(it) },
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
        }
    }
}
