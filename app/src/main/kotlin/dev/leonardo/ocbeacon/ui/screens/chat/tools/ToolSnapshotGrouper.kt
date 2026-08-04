package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按 (messageId, 规范化 filePath) 对 Read/Write/Edit 工具 parts 分组 —— 规范 §5.5 "B-tier"。
 *
 * - 同一条消息 + 同一个规范化路径 = 一个分组
 * - 其他工具类型（Bash、Glob 等）不会中断分组
 * - 不要求物理相邻
 * - 累积 diff：[ToolSnapshotGroup.cumulativeBefore] = 第一个 part 的 before，
 *              [ToolSnapshotGroup.cumulativeAfter]  = 最后一个 part 的 after
 *
 * 路径规范化将 `\` 转为 `/` 并去除尾部 `/`，使得
 * `app\src\X.kt` 与 `app/src/X.kt` 被视为同一文件。
 */
object ToolSnapshotGrouper {

    private val SUPPORTED_TOOLS = setOf("read", "write", "edit")

    fun group(parts: List<Part.Tool>): List<ToolSnapshotGroup> {
        val fileTools = parts.filter { it.tool.lowercase() in SUPPORTED_TOOLS }
        if (fileTools.isEmpty()) return emptyList()

        val byMessage = fileTools.groupBy { it.messageId }

        val allGroups = mutableListOf<ToolSnapshotGroup>()
        for ((_, tools) in byMessage) {
            // LinkedHashMap 保持首次出现顺序
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
            else -> ""  // write/read 没有 "before"
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

    /** 规范化：`\` → `/`，去除尾部 `/`。 */
    fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')
}

data class ToolSnapshotGroup(
    val normalizedFilePath: String,
    val toolParts: List<Part.Tool>,
    val firstFilePath: String,
    val cumulativeBefore: String,
    val cumulativeAfter: String
) {
    /** ③ 徽章的分组大小（1 = 无徽章）。 */
    val size: Int get() = toolParts.size
    /** 若分组中工具多于一个则为 true（显示左侧栏 + 徽章）。 */
    val isMulti: Boolean get() = size > 1
}
