package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.SessionPermissions
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.RevertedDraftPayload
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommand
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommandRegistry
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import androidx.compose.ui.graphics.luminance


internal enum class ChatInputMode {
    NORMAL,
    SHELL
}

// BreathingCircleIndicator moved to components/BreathingCircleIndicator.kt
// FileMentionVisualTransformation moved to input/FileMentionVisualTransformation.kt

/** 输入栏的轮换占位符提示，类似 WebUI 的提示输入。 */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@Composable
internal fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    /** #309 批1④：忙碌长按发送键——直发插话（DSH mode=steer）；空闲长按维持 shell 切换。 */
    onSendSteer: () -> Unit = {},
    isSending: Boolean,
    isBusy: Boolean = false,
    /** 2026-08-14：等待提问/权限响应时禁用输入（用户要求"提问时输入框不可以输入"）。 */
    inputEnabled: Boolean = true,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    commands: List<CommandInfo> = emptyList(),
    // #324⑤：会话技能（DSH skills/list 触发组；非 DSH 恒空）
    skills: List<dev.leonardo.ocbeacon.domain.model.DshSkillInfo> = emptyList(),
    /** #276 能力位门控：false（DSH）时斜杠命令建议面板不出现。 */
    slashCommandsSupported: Boolean = true,
    fileSearchResults: List<String> = emptyList(),
    /** #310⑤ 会话源候选（DSH;与文件候选同弹窗,会话行在前）。 */
    sessionSearchResults: List<dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    /** #310⑤ 点选会话源——以 mention 规范串替换 trigger 词。 */
    onSessionSelected: (dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    onStop: () -> Unit = {},
    restoredDraft: RevertedDraftPayload? = null,
    onConsumeRestoredDraft: () -> Unit = {},
    onQuickNavigate: () -> Unit = {},
    showTaskToolbar: Boolean = false,
    taskToolbarText: String = "",
    onBackgroundSession: () -> Unit = {},
    // DSH 权限预设选择器（能力位门控 + 会话权限状态 + 点选/自定义点击回调）
    permissionSwitchSupported: Boolean = false,
    permissions: SessionPermissions? = null,
    onPermissionSelect: (String) -> Unit = {},
    onPermissionCustomClick: () -> Unit = {},
    // #310③ Plan 模式状态 chip（DSH-only；显隐/形态由调用方经 PlanChipGate 判定）
    planChipVisible: Boolean = false,
    planPending: Boolean = false,
    onPlanExit: () -> Unit = {},
) {
    // 发送失败时恢复草稿文本
    androidx.compose.runtime.LaunchedEffect(restoredDraft) {
        restoredDraft?.let { draft ->
            onTextFieldValueChange(TextFieldValue(draft.text, TextRange(draft.text.length)))
            onConsumeRestoredDraft()
        }
    }
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // 每 4 秒轮换占位符提示
    val hintIndex = remember { mutableIntStateOf(0) }
    var textFieldFocused by remember { mutableStateOf(false) }
    val text = textFieldValue.text
    // L-8：仅输入框聚焦且文本为空时轮换——原 4s 永久轮换即使无焦点也持续
    // 触发 state 写 + 重组；占位符仅在空文本时可见，空且无焦点时轮换无意义。
    val shouldRotateHint = textFieldFocused && text.isEmpty()
    androidx.compose.runtime.LaunchedEffect(shouldRotateHint) {
        if (!shouldRotateHint) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = if (isShellMode) {
        stringResource(R.string.chat_shell_placeholder)
    } else {
        stringResource(placeholderHintResIds[hintIndex.intValue])
    }
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy) && inputEnabled

    // 构建合并的斜杠命令（走查 #3 修复）：客户端静态表是 OpenCode 时代遗产——
    // 服务器命令面已加载（commands 非空，DSH=commands.list 实测可用）时以服务器
    // 面为准，静态表仅作未加载前的兜底。此前静态表恒并入导致 DSH 显示
    // fork/new/redo 等 DSH 不存在的命令、淹没 /goal /permission /plan。
    val clientCmds = SlashCommandRegistry.clientCommands()
    // #324⑤：skills 触发组（whenToUse 优先作描述行；modelInvocable 标识到建议行渲染）
    val skillCmds = remember(skills) {
        skills.map { skill ->
            SlashCommand(
                name = skill.name,
                description = skill.whenToUse ?: skill.description.ifBlank { null },
                type = "skill",
                modelInvocable = skill.modelInvocable,
            )
        }
    }
    val allCommands = remember(commands, clientCmds, skillCmds) {
        if (commands.isNotEmpty()) {
            commands.map { SlashCommand(it.name, it.description, it.source ?: "server", requiresInput = it.hints.isNotEmpty()) } + skillCmds
        } else {
            clientCmds + skillCmds
        }
    }

    // 斜杠命令建议（#276：DSH 无 command 域——能力位关停整块面板）
    val showSlashSuggestions = slashCommandsSupported && !isShellMode && text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // 细分隔线（暗色模式下改用更亮的 outline 提升可见度）
        HorizontalDivider(
            color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MUTED)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            },
            thickness = 0.5.dp
        )

        // 斜杠命令建议弹窗（可滚动，最高 40% 屏幕高度）
        if (!isShellMode) {
            SlashCommandSuggestions(
                commands = filteredCommands,
                onSkillClick = { cmd ->
                    val skillText = "/${cmd.name} "
                    onTextFieldValueChange(TextFieldValue(skillText, TextRange(skillText.length)))
                },
                onCommandClick = { cmd ->
                    if (cmd.type == "client") {
                        // 客户端本地动作（rename 对话框/shell 模式等）：tap 直达保持
                        onTextFieldValueChange(TextFieldValue(""))
                        onSlashCommand(cmd)
                    } else {
                        // #372（2026-09-09 用户裁决）：三面服务器命令后端均支持参数
                        //（V1/V2 /command arguments 字段、DSH commands/execute 整行
                        // line）——面板 tap 统一回填 "/name " 待补参后手动发送；
                        // 废除「无 input.hint 即直达」分叉（requiresInput 仅作提示）。
                        val cmdText = "/" + cmd.name + " "
                        onTextFieldValueChange(TextFieldValue(cmdText, TextRange(cmdText.length)))
                    }
                }
            )
        }

        // @ 文件/会话提及建议弹窗（#310⑤ 会话源候选同弹窗）
        if (!isShellMode) {
            FileMentionSuggestions(
                results = fileSearchResults,
                sessions = sessionSearchResults,
                onFileSelected = onFileSelected,
                onSessionSelected = onSessionSelected
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SpacingTokens.LG.dp, end = SpacingTokens.LG.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
        ) {
            // 任务工具栏——有前台 subagent 运行时从输入栏上方滑出（fade + expand 动画）。
            // 对应 TUI 的 ctrl+b：一键将当前所有前台 subagent 转为后台执行。
            AnimatedVisibility(
                visible = showTaskToolbar && !isShellMode,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                TaskToolbar(
                    text = taskToolbarText,
                    onBackgroundSession = onBackgroundSession,
                )
            }

            // Agent + 模型 + 变体 + 附件选择器行——小巧、低调
            AgentModelVariantSelector(
                modelLabel = modelLabel,
                selectedProviderId = selectedProviderId,
                agents = agents,
                selectedAgent = selectedAgent,
                variantNames = variantNames,
                selectedVariant = selectedVariant,
                onModelClick = onModelClick,
                onAgentSelect = onAgentSelect,
                onAttach = onAttach,
                onQuickNavigate = onQuickNavigate,
                permissionSwitchSupported = permissionSwitchSupported,
                permissions = permissions,
                onPermissionSelect = onPermissionSelect,
                onPermissionCustomClick = onPermissionCustomClick,
                planChipVisible = planChipVisible,
                planPending = planPending,
                onPlanExit = onPlanExit,
            )

            // 图片附件缩略图
            ImageAttachmentRow(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                onSaveAttachment = onSaveAttachment
            )

            ShellModeHintBanner(
                isShellMode = isShellMode,
                isAmoled = isAmoled
            )

            // 输入行
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
            ) {
                ChatTextField(
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = onTextFieldValueChange,
                    placeholder = placeholder,
                    isShellMode = isShellMode,
                    isAmoled = isAmoled,
                    confirmedFilePaths = confirmedFilePaths,
                    enabled = inputEnabled,
                    onFocusChange = { textFieldFocused = it }
                )

                // 发送/停止按钮区——单键统一（#326，2026-09-04 用户裁决，对齐 web 主按钮）：
                // idle=发送 / busy+空=停止 / busy+文本=发送(服务端排队,长按=steer #309④) /
                // 被阻塞(等待提问/权限,inputEnabled=false)=停止
                SendStopButton(
                    hasText = text.isNotBlank(),
                    isBusy = isBusy,
                    inputBlocked = !inputEnabled,
                    canSend = canSend,
                    isSending = isSending,
                    isShellMode = isShellMode,
                    isAmoled = isAmoled,
                    onStop = onStop,
                    onSend = onSend,
                    onSendSteer = onSendSteer,
                    onInputModeChange = onInputModeChange
                )
            }
        }
    }
}