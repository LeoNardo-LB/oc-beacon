package dev.leonardo.octether.ui.screens.chat.tools

import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Groups Read/Write/Edit tool parts by (messageId, normalized filePath) — spec §5.5 "B-tier".
 *
 * - Same message + same normalized path = one group
 * - Other tool types (Bash, Glob, …) do NOT break groups
 * - Physical adjacency is NOT required
 * - Cumulative diff: [ToolSnapshotGroup.cumulativeBefore] = first part's before,
 *                    [ToolSnapshotGroup.cumulativeAfter]  = last part's after
 *
 * Path normalization converts `\` to `/` and trims trailing `/` so that
 * `app\src\X.kt` and `app/src/X.kt` are treated as the same file.
 */
object ToolSnapshotGrouper {

    private val SUPPORTED_TOOLS = setOf("read", "write", "edit")

    fun group(parts: List<Part.Tool>): List<ToolSnapshotGroup> {
        val fileTools = parts.filter { it.tool.lowercase() in SUPPORTED_TOOLS }
        if (fileTools.isEmpty()) return emptyList()

        val byMessage = fileTools.groupBy { it.messageId }

        val allGroups = mutableListOf<ToolSnapshotGroup>()
        for ((_, tools) in byMessage) {
            // LinkedHashMap preserves first-occurrence order
            val grouped = LinkedHashMap<String, MutableList<Part.Tool>>()
            for (t in tools) {
                val path = extractFilePath(t) ?: continue
                val normalized = normalizePath(path)
                grouped.getOrPut(normalized) { mutableListOf() }.add(t)
            }
            for ((normalized, groupTools) in grouped) {
                allGroups.add(buildGroup(normalized, groupTools))
            }
        }
        return allGroups
    }

    private fun buildGroup(normalizedPath: String, tools: List<Part.Tool>): ToolSnapshotGroup {
        val firstFilePath = extractFilePath(tools.first()) ?: normalizedPath
        val cumulativeBefore = extractBefore(tools.first())
        val cumulativeAfter = extractAfter(tools.last())
        return ToolSnapshotGroup(
            normalizedFilePath = normalizedPath,
            toolParts = tools,
            firstFilePath = firstFilePath,
            cumulativeBefore = cumulativeBefore,
            cumulativeAfter = cumulativeAfter
        )
    }

    private fun extractFilePath(tool: Part.Tool): String? {
        val input = tool.state.inputMap()
        return input["filePath"]?.jsonPrimitive?.contentOrNull
            ?: input["path"]?.jsonPrimitive?.contentOrNull
    }

    private fun extractBefore(tool: Part.Tool): String {
        val metadata = (tool.state as? ToolState.Completed)?.metadata
            ?: (tool.state as? ToolState.Running)?.metadata
        metadata?.get("filediff")?.let { fd ->
            (fd as? JsonObject)?.get("before")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        val input = tool.state.inputMap()
        return when (tool.tool.lowercase()) {
            "edit" -> input["oldString"]?.jsonPrimitive?.contentOrNull ?: ""
            else -> ""  // write/read have no "before"
        }
    }

    private fun extractAfter(tool: Part.Tool): String {
        val metadata = (tool.state as? ToolState.Completed)?.metadata
            ?: (tool.state as? ToolState.Running)?.metadata
        metadata?.get("filediff")?.let { fd ->
            (fd as? JsonObject)?.get("after")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        val input = tool.state.inputMap()
        return when (tool.tool.lowercase()) {
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull ?: ""
            "edit" -> input["newString"]?.jsonPrimitive?.contentOrNull ?: ""
            "read" -> (tool.state as? ToolState.Completed)?.output ?: ""
            else -> ""
        }
    }

    private fun ToolState.inputMap(): Map<String, JsonElement> = when (this) {
        is ToolState.Completed -> input
        is ToolState.Running -> input
        is ToolState.Pending -> input
        is ToolState.Error -> input
    }

    /** Normalize: `\` → `/`, trim trailing `/`. */
    fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')
}

data class ToolSnapshotGroup(
    val normalizedFilePath: String,
    val toolParts: List<Part.Tool>,
    val firstFilePath: String,
    val cumulativeBefore: String,
    val cumulativeAfter: String
) {
    /** Group size for the ③ badge (1 = no badge). */
    val size: Int get() = toolParts.size
    /** True if more than one tool in this group (show left rail + badge). */
    val isMulti: Boolean get() = size > 1
}
