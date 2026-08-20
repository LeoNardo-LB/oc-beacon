package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

/**
 * /find 文本搜索匹配结果。
 * API 形状（见 docs/opencode-api-reference-v1.md /find 响应）：
 * path/lines 是嵌套 { text } 对象，line_number/absolute_offset 为 snake_case，
 * submatches 为 match:{text} + start/end。
 */
@Serializable
data class MatchText(val text: String = "")

@Serializable
data class SubmatchDto(
    @SerialName("match") val match: MatchText = MatchText(""),
    val start: Int = 0,
    val end: Int = 0
)

@Serializable
data class SearchMatchDto(
    val path: MatchText = MatchText(""),
    val lines: MatchText = MatchText(""),
    @SerialName("line_number") val lineNumber: Int = 0,
    @SerialName("absolute_offset") val absoluteOffset: Int = 0,
    val submatches: List<SubmatchDto> = emptyList()
)

@Serializable
data class FileContentDto(
    val type: String,           // "text" | "binary"
    val content: String,
    val diff: String? = null,   // D3-003 修正：补 diff 字段
    val patch: JsonElement? = null,  // D3-003：补 patch 字段（结构化对象）
    val encoding: String? = null,
    val mimeType: String? = null
)

@Serializable
data class FileNodeDto(
    /** V2 /api/fs/list 响应无 name 字段（只有 path/type）——默认空，由调用方从 path 推导 */
    val name: String = "",
    val path: String,
    val type: String,
    val absolute: String? = null,
    val ignored: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
)

@Serializable
data class ServerPaths(
    val home: String = "", val state: String = "", val config: String = "",
    val worktree: String = "", val directory: String = ""
)

// ============ VCS DTO ============

@Serializable
data class VcsChangeDto(
    val file: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null
)

@Serializable
data class VcsBranchDto(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null  // D3-002 修正：API 返回 snake_case
)

@Serializable
data class FileDiffDto(
    val file: String? = null,
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null
)
