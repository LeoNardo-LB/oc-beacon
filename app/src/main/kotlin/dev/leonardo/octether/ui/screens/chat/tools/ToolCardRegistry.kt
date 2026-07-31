package dev.leonardo.octether.ui.screens.chat.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.ToolState
import dev.leonardo.octether.util.PathUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Display info for a tool call, resolved from tool name and input args.
 */
internal data class ToolDisplayInfo(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector = Icons.Default.Build,
    val iconTint: Color? = null
)

/**
 * Extract the file name from a cross-platform path string.
 * Delegates to [PathUtils.fileName].
 */
internal fun extractFileName(path: String): String = PathUtils.fileName(path)

/**
 * Extract the file name from a potentially path-like server title.
 * If the title contains path separators, returns only the last segment.
 * e.g. "Read /path/to/File.kt" → "File.kt", "Edit file" → "Edit file" (unchanged)
 */
private fun extractFileNameFromTitle(title: String): String {
    val lastSegment = PathUtils.fileName(title)
    return if (lastSegment.length < title.length && lastSegment.contains('.')) {
        lastSegment
    } else {
        title
    }
}

/**
 * Resolve display info for a tool call based on its type and input arguments.
 * Matches WebUI tool registry behavior with human-readable titles.
 */
@Composable
internal fun resolveToolDisplay(
    toolName: String,
    state: ToolState,
    input: Map<String, JsonElement>
): ToolDisplayInfo {
    // Use server-provided title if available
    val serverTitle = when (state) {
        is ToolState.Running -> state.title?.let { extractFileNameFromTitle(it) }
        is ToolState.Completed -> state.title?.let { extractFileNameFromTitle(it) }
        else -> null
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
        ?: input["file"]?.jsonPrimitive?.contentOrNull
    val shortPath = filePath?.let { extractFileName(it) }

    return when (toolName) {
        "read" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_read_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Description
            )
        }
        "write" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_write_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.EditNote
            )
        }
        "edit" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_edit_file),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Edit
            )
        }
        "bash" -> {
            val command = input["command"]?.jsonPrimitive?.contentOrNull
            val shortCmd = command?.let {
                if (it.length > 60) it.take(57) + "..." else it
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_terminal),
                subtitle = shortCmd,
                icon = Icons.Default.Terminal
            )
        }
        "glob" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_find_files),
                subtitle = pattern,
                icon = Icons.Default.FolderOpen
            )
        }
        "grep" -> {
            val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_search_code),
                subtitle = pattern,
                icon = Icons.Default.Search
            )
        }
        "list", "listDirectory" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_list_directory),
                subtitle = shortPath ?: filePath,
                icon = Icons.Default.Folder
            )
        }
        "webfetch" -> {
            val url = input["url"]?.jsonPrimitive?.contentOrNull
            val shortUrl = url?.let {
                try { java.net.URI(it).host } catch (_: Exception) { it.take(40) }
            }
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_fetch_url),
                subtitle = shortUrl,
                icon = Icons.Default.Language
            )
        }
        "task" -> {
            val description = input["description"]?.jsonPrimitive?.contentOrNull
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_sub_agent),
                subtitle = description,
                icon = Icons.Default.AccountTree
            )
        }
        "apply_patch" -> {
            ToolDisplayInfo(
                title = serverTitle ?: stringResource(R.string.tool_apply_patch),
                subtitle = shortPath,
                icon = Icons.Default.Compare
            )
        }
        else -> {
            // MCP tools and other unknown tools
            // Convert snake_case like "search_graph" -> "Search Graph"
            val fallbackName = toolName.ifBlank { "Tool" }
                .replace("_", " ").replace("-", " ")
                .replaceFirstChar { it.uppercase() }
            ToolDisplayInfo(
                title = serverTitle?.takeIf { it.isNotBlank() } ?: fallbackName,
                subtitle = shortPath,
                icon = Icons.Default.Build
            )
        }
    }
}
