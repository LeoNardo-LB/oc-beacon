package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ContextToolGroupCard(
    parts: List<Part.Tool>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pending = remember(parts) {
        parts.any { it.state is ToolState.Running || it.state is ToolState.Pending }
    }
    val summary = remember(parts) { contextToolSummary(parts) }
    val isAmoled = isAmoledTheme()

    val statusText = stringResource(
        if (pending) R.string.context_exploring else R.string.context_explored
    )
    val title = buildString {
        append(statusText)
        if (summary.read > 0) {
            append(" · ")
            append(stringResource(R.string.context_read_count, summary.read))
        }
        if (summary.search > 0) {
            append(" · ")
            append(stringResource(R.string.context_search_count, summary.search))
        }
    }

    ToolCardScaffold(
        icon = if (pending) Icons.Filled.Search else Icons.Filled.CheckCircle,
        iconTint = if (pending) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        title = title,
        copyText = title,
        isExpanded = expanded,
        isRunning = pending,
        hasContent = true,
        isAmoled = isAmoled,
        onToggleExpand = { expanded = !expanded },
        modifier = modifier,
    ) {
        // 预解析标签（@Composable，须在 Composable 体内、map 之前调用）
        val readLabel = stringResource(R.string.tool_read)
        val globLabel = stringResource(R.string.tool_glob)
        val grepLabel = stringResource(R.string.tool_grep)

        ToolGroupList(
            items = parts.map { part ->
                val label = when (part.tool.lowercase()) {
                    "read" -> readLabel
                    "glob" -> globLabel
                    "grep" -> grepLabel
                    else -> part.tool
                }
                ToolGroupListItem(
                    icon = toolIcon(part.tool),
                    label = label,
                    subtitle = toolSubtitle(part).ifEmpty { null },
                )
            },
            onItemClick = { idx -> handleToolClick(parts[idx], onOpenFile) },
        )
    }
}

private fun handleToolClick(part: Part.Tool, onOpenFile: (String) -> Unit) {
    if (part.tool.lowercase() != "read") return
    val input = extractToolInput(part)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
    if (filePath != null) onOpenFile(filePath)
}

private fun toolIcon(toolName: String) = when (toolName.lowercase()) {
    "read" -> Icons.Filled.Description
    "glob" -> Icons.Filled.FindInPage
    "grep" -> Icons.Filled.Search
    else -> Icons.Filled.Description
}

private fun toolSubtitle(part: Part.Tool): String {
    val input = extractToolInput(part)
    return when (part.tool.lowercase()) {
        "read" -> {
            val path = input["filePath"]?.jsonPrimitive?.contentOrNull
                ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
            extractFileName(path)
        }
        "glob", "grep" -> input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
        else -> ""
    }
}
