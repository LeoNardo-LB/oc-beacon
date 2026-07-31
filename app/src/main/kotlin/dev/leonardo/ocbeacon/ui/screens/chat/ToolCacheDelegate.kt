package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import android.util.Log
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileViewerDiag"

/**
 * Extracts and caches file snapshots from Tool parts for the file viewer.
 *
 * Handles read/write/edit tool output parsing, including stripping Read tool
 * XML wrappers and embedded line-number prefixes.
 */
class ToolCacheDelegate @Inject constructor(
    private val toolSnapshotCache: ToolSnapshotCache,
) {
    fun cacheToolPart(part: Part.Tool) {
        val state = part.state
        val input = when (state) {
            is ToolState.Completed -> state.input
            is ToolState.Running -> state.input
            is ToolState.Pending -> state.input
            is ToolState.Error -> state.input
        }
        val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
            ?: input["path"]?.jsonPrimitive?.contentOrNull ?: run {
                Log.w(TAG, "cacheToolPart: no filePath in input, tool=${part.tool}, " +
                    "partId=${part.id.take(12)}, state=${state::class.simpleName}")
                return
            }
        val metadata = (state as? ToolState.Completed)?.metadata
        val filediff = metadata?.get("filediff") as? JsonObject
        val before = filediff?.get("before")?.jsonPrimitive?.contentOrNull
            ?: input["oldString"]?.jsonPrimitive?.contentOrNull
        val after = filediff?.get("after")?.jsonPrimitive?.contentOrNull
            ?: input["newString"]?.jsonPrimitive?.contentOrNull
        val content = when (part.tool.lowercase()) {
            "read" -> {
                val raw = (state as? ToolState.Completed)?.output ?: ""
                cleanReadToolOutput(raw)
            }
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull
            "edit" -> after
            else -> null
        }
        // DIAG: log cache entry details for intermittent blank-file investigation
        Log.d(TAG, "cacheToolPart: tool=${part.tool}, state=${state::class.simpleName}, " +
            "partId=${part.id.take(12)}, file=${filePath.take(60)}, " +
            "contentLen=${content?.length ?: -1}, beforeLen=${before?.length ?: -1}, " +
            "afterLen=${after?.length ?: -1}, hasMetadata=${metadata != null}, " +
            "hasFilediff=${filediff != null}")
        if (content.isNullOrBlank() && before.isNullOrBlank() && after.isNullOrBlank()) {
            Log.w(TAG, "cacheToolPart: ALL FIELDS BLANK for partId=${part.id.take(12)}, " +
                "tool=${part.tool}, state=${state::class.simpleName} → will cause empty FileViewer!")
        }
        toolSnapshotCache.put(
            part.id,
            ToolSnapshotCache.Snapshot(
                filePath = filePath, content = content, before = before, after = after, toolName = part.tool
            )
        )
    }

    /**
     * Strip Read tool output wrappers (<path>, <content> tags) and embedded
     * line-number prefixes ("291: text" → "text") to avoid double line numbers
     * in the file viewer (which adds its own gutter).
     */
    private fun cleanReadToolOutput(raw: String): String {
        var result = raw
        val contentMatch = Regex("<content>(?:\\r?\\n)?(.*?)(?:\\r?\\n)?</content>", RegexOption.DOT_MATCHES_ALL).find(result)
        result = if (contentMatch != null) {
            contentMatch.groupValues[1]
        } else {
            result.lines().filter { line ->
                !line.startsWith("<path>") && !line.startsWith("</path>") &&
                !line.startsWith("<type>") && !line.startsWith("</type>") &&
                !line.startsWith("<content>") && !line.startsWith("</content>")
            }.joinToString("\n")
        }
        result = result.replace(Regex("(?m)^\\s*\\d+:\\s"), "")
        return result.trim()
    }
}
