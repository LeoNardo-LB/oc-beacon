package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileViewerDiag"

// #106-4：Read 工具输出剥离正则——顶层预编译（原每个 tool part 现场编译，流式高频）
private val CONTENT_WRAPPER_REGEX = Regex("<content>(?:\\r?\\n)?(.*?)(?:\\r?\\n)?</content>", RegexOption.DOT_MATCHES_ALL)
private val EMBEDDED_LINE_NUMBERS_REGEX = Regex("(?m)^\\s*\\d+:\\s")

/**
 * 从 Tool parts 中提取并缓存文件快照，供文件查看器使用。
 *
 * 处理 read/write/edit 工具输出解析，包括剥离 Read 工具
 * XML 包装器和内嵌行号前缀。
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
                AppLogger.w(TAG, "cacheToolPart: no filePath in input, tool=${part.tool}, " +
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
        // 诊断：记录缓存条目详情，用于间歇性空白文件调查
        AppLogger.d(TAG, "cacheToolPart: tool=${part.tool}, state=${state::class.simpleName}, " +
            "partId=${part.id.take(12)}, file=${filePath.take(60)}, " +
            "contentLen=${content?.length ?: -1}, beforeLen=${before?.length ?: -1}, " +
            "afterLen=${after?.length ?: -1}, hasMetadata=${metadata != null}, " +
            "hasFilediff=${filediff != null}")
        if (content.isNullOrBlank() && before.isNullOrBlank() && after.isNullOrBlank()) {
            AppLogger.w(TAG, "cacheToolPart: ALL FIELDS BLANK for partId=${part.id.take(12)}, " +
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
     * 剥离 Read 工具输出包装器（<path>、<content> 标签）和内嵌
     * 行号前缀（"291: text" → "text"），避免文件查看器中出现
     * 双重行号（查看器有自己的行号槽）。
     */
    private fun cleanReadToolOutput(raw: String): String {
        var result = raw
        val contentMatch = CONTENT_WRAPPER_REGEX.find(result)
        result = if (contentMatch != null) {
            contentMatch.groupValues[1]
        } else {
            result.lines().filter { line ->
                !line.startsWith("<path>") && !line.startsWith("</path>") &&
                !line.startsWith("<type>") && !line.startsWith("</type>") &&
                !line.startsWith("<content>") && !line.startsWith("</content>")
            }.joinToString("\n")
        }
        result = result.replace(EMBEDDED_LINE_NUMBERS_REGEX, "")
        return result.trim()
    }
}
