package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class SearchMatchDto(  // ⚠️ 字段与 API 不完全匹配（API 是 path:{text}, line_number），Phase 1 不用 /find 端点，保留现状加注释
    val path: String,
    val lines: String,
    val lineNumber: Int,
    val absoluteOffset: Int
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
    val name: String,
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

// ============ VCS DTOs ============

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
