package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState

internal fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

internal fun formatAssistantErrorMessage(error: dev.leonardo.ocbeacon.domain.model.Message.Assistant.ErrorInfo?): String? {
    if (error == null) return null
    val raw = error.message.ifBlank { error.name }
    return raw.ifBlank { null }
}

internal fun formatFileSize(bytes: Int): String {
    val value = bytes.toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format("%.2f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "$bytes B"
    }
}

internal fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> "%.1fs".format(ms / 1000.0)
        else -> "%.1fm".format(ms / 60000.0)
    }
}

/**
 * 为一组步骤工具 parts 解析人类可读的状态标签。
 * 例如 "Making edits"、"Running commands"、"Searching codebase"、"Thinking"
 */
@Composable
internal fun resolveStepsStatus(stepParts: List<Part>): String {
    val toolParts = stepParts.filterIsInstance<Part.Tool>()
    val hasRunning = toolParts.any { it.state is ToolState.Running }
    if (!hasRunning && toolParts.all { it.state is ToolState.Completed || it.state is ToolState.Error }) {
        // 全部完成 —— 汇总
        val editCount = toolParts.count { it.tool in listOf("edit", "write", "apply_patch", "multiedit") }
        val bashCount = toolParts.count { it.tool == "bash" }
        val searchCount = toolParts.count { it.tool in listOf("glob", "grep", "read", "list", "listDirectory") }
        return when {
            editCount > 0 && bashCount == 0 && searchCount == 0 ->
                pluralStringResource(R.plurals.chat_status_edits_plural, editCount, editCount)
            bashCount > 0 && editCount == 0 && searchCount == 0 ->
                pluralStringResource(R.plurals.chat_status_commands_plural, bashCount, bashCount)
            else ->
                pluralStringResource(R.plurals.chat_status_steps_plural, toolParts.size, toolParts.size)
        }
    }
    // 正在运行 —— 描述当前正在做什么
    val runningTool = toolParts.lastOrNull { it.state is ToolState.Running }
    return when (runningTool?.tool) {
        "edit", "write", "multiedit" -> stringResource(R.string.chat_status_making_edits)
        "bash" -> stringResource(R.string.chat_status_running_commands)
        "read", "glob", "grep", "list", "listDirectory" -> stringResource(R.string.chat_status_searching)
        "webfetch" -> stringResource(R.string.chat_status_fetching_url)
        "task" -> stringResource(R.string.chat_status_running_subagent)
        "todowrite" -> stringResource(R.string.chat_status_updating_tasks)
        else -> stringResource(R.string.chat_status_thinking)
    }
}

@Composable
internal fun resolveUserCommandLabel(parts: List<Part>): String? {
    val subtaskParts = parts.filterIsInstance<Part.Subtask>()

    val commandFromSubtask = subtaskParts
        .firstNotNullOfOrNull { it.command }
        ?.removePrefix("/")
        ?.trim()
        ?.lowercase()

    val commandFromText = parts
        .filterIsInstance<Part.Text>()
        .firstNotNullOfOrNull { textPart ->
            val text = textPart.text.trim()
            if (!text.startsWith("/")) return@firstNotNullOfOrNull null
            text.removePrefix("/").substringBefore(' ').trim().lowercase().takeIf { it.isNotBlank() }
        }

    val inferredReviewFromPrompt = subtaskParts.any { subtask ->
        val prompt = subtask.prompt.lowercase()
        val description = subtask.description?.lowercase().orEmpty()
        "review changes" in prompt || "review" in description
    }

    val command = commandFromSubtask ?: commandFromText ?: if (inferredReviewFromPrompt) "review" else null

    return when (command) {
        "review" -> stringResource(R.string.menu_review_changes)
        null -> {
            // 2026-08-12 修复：Part.Unknown（Room 反序列化失败的旧数据）不算
            // "运行中"——空壳 user 消息（全部 Unknown）不再显示误导性的
            // "Running command…"（用户反馈"看起来缺少数据/对话快速访问问题"）
            val hasNonRenderableOnly = parts.any { part ->
                part !is Part.Text &&
                        part !is Part.Reasoning &&
                        part !is Part.Patch &&
                        part !is Part.File &&
                        part !is Part.Permission &&
                        part !is Part.Question &&
                        part !is Part.Abort &&
                        part !is Part.Retry &&
                        part !is Part.Unknown
            }
            if (hasNonRenderableOnly) stringResource(R.string.chat_tool_running_command) else null
        }
        else -> stringResource(R.string.chat_tool_running_command)
    }
}
