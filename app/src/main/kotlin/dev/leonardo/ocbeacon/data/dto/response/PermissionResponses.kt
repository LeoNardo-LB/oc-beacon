package dev.leonardo.ocbeacon.data.dto.response

import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    /**
     * V1 契约（permission.asked 事件 / V1 REST）：工具权限名（如 "bash"）。
     *
     * 2026-08-16 修复（F6）：V2 REST GET /api/permission/request 条目是官方
     * schema PermissionV2.Request：{id, sessionID, action, resources, save?,
     * metadata?, source?}——**无 permission 字段**，原非空声明导致部署版 V2
     * 轮询恒抛 MissingFieldException('permission') → 待处理权限列表整列解析失败。
     * 可选化 + 补 V2 字段（action/resources），调用方经 [permission] ?: [action] 兜底。
     */
    val permission: String? = null,
    val patterns: List<String> = emptyList(),
    /** V2：权限动作（"shell"/"edit"/"web"...），语义对应 V1 permission。 */
    val action: String? = null,
    /** V2：资源列表（如命令/路径模式），语义对应 V1 patterns。 */
    val resources: List<String> = emptyList(),
    /** V2：always 保存的资源列表（reply="always" 时服务器落规则）。 */
    val save: List<String> = emptyList(),
    val metadata: Map<String, JsonElement>? = null,
    val always: JsonElement? = null,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
    /** V2 form field key（q0/q1...）；V1 为 null。 */
    val key: String? = null
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String,
    /** V2 form option value（提交用）；V1 为 null。 */
    val value: String? = null
)
