package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * #311 Task4 deliverables——turn 产出文件 fold（client-only，契约 ②；
 * DSH web mod28:59-172 mutationPath/producedForClosing 同构）。
 *
 * 产出判定 = **成功的写类工具调用**的 args 路径：write（file_path+content）/
 * edit（file_path+old/new_string）/str_replace_editor（create/str_replace/
 * insert）。首见序去重（精确拼写——同 basename 异路径保留两条）；读类/
 * 未知工具/参数不全调用一律不计。纯函数，JVM 可测（TurnDeliverablesTest）。
 *
 * ## 结果成功判定的映射依据（app 侧模型无 surfaceOp/isError 字段）
 *
 * web 判据「tool/result 须 surfaceOp==='append' 且 content[0].isError!==true」
 * （mod28:133-172）；app 转录（[Part.Tool]/[ToolState]）两个 wire 字段均未
 * 建模，按 transcript 已有字段取最接近语义：
 * - **isError** ≈ [ToolState.Error] vs [ToolState.Completed]——
 *   DshEventMapper.mapToolResult 以 data.error/message.error 判错（有错 →
 *   Error，无错 → Completed；DshEventMapper.kt:884-913），V2 的
 *   session.tool.success|failed 同构分流。
 * - **surfaceOp==='append'**：无对应字段。服务端唯一 tool/result 发射点恒
 *   surfaceOp:"append"（agent-loop appendToolResult :302-318、会话关闭器
 *   session-lib :540-553）——「非 append」（replacement copy）在 app 转录中
 *   不可表达。故「成功」= state is [ToolState.Completed]，与 web 判据在
 *   可表达子集上等价（偏差如实记录于 #311 任务报告）。
 */
object TurnDeliverables {
    /** web 同款上限：最多渲染 6 个文件 chip，其余折入「+N」计数（SHOWN_LIMIT=6）。 */
    const val SHOWN_LIMIT = 6
}

/**
 * 单次调用是否构成一次支持的文件产出（写类工具 + 参数完备）。
 * mod28:59-93 mutationPath 的 args 判定矩阵：
 * - write：content 必须是 string → file_path（非空串）；
 * - edit：old_string 非空 string、new_string 为 string、old≠new、
 *   replace_all 在场必须为 bool；
 * - str_replace_editor：path 非空 + command ∈ {create, str_replace, insert}
 *   各自的完备参数（view 等只读命令不计）。
 * 路径保留调用处的精确拼写（仅 trim 判空，不改写值）。
 */
internal fun mutationPath(toolName: String, input: Map<String, JsonElement>): String? = when (toolName) {
    "write" -> stringValue(input, "content")?.let { pathValue(input, "file_path") }
    "edit" -> if (isValidEditArgs(input)) pathValue(input, "file_path") else null
    "str_replace_editor" -> editorMutationPath(input)
    else -> null
}

/** edit 执行必需字段的完备性（mod28 validEditArgs）。 */
private fun isValidEditArgs(input: Map<String, JsonElement>): Boolean {
    val old = stringValue(input, "old_string") ?: return false
    if (old.isEmpty()) return false
    val new = stringValue(input, "new_string") ?: return false
    if (old == new) return false
    val replaceAll = input["replace_all"] ?: return true
    return (replaceAll as? JsonPrimitive)?.booleanOrNull != null
}

/** 仅完整变更型 editor 命令才产出路径（mod28 editorMutationPath）。 */
private fun editorMutationPath(input: Map<String, JsonElement>): String? {
    val path = pathValue(input, "path") ?: return null
    return when (stringValue(input, "command")) {
        "create" -> if (stringValue(input, "file_text") != null) path else null
        "str_replace" -> {
            val old = stringValue(input, "old_str")
            val newStrOk = input["new_str"] == null || stringValue(input, "new_str") != null
            if (!old.isNullOrEmpty() && newStrOk) path else null
        }
        "insert" -> {
            val line = (input["insert_line"] as? JsonPrimitive)?.content?.toIntOrNull()
            val newStr = stringValue(input, "new_str")
            if (line != null && line >= 0 && newStr != null) path else null
        }
        else -> null
    }
}

/**
 * 结算成功（≈ surfaceOp append 且非 error）的写类调用 → 产出路径。
 * Pending/Running（结果未到达）与 Error 一律不产出。
 */
private fun producedPathIfSettled(part: Part.Tool): String? {
    if (part.state !is ToolState.Completed) return null
    return mutationPath(part.tool, part.state.input)
}

/** 调用序列 → 产出路径列表（首见序去重，精确拼写键）。 */
internal fun producedFilesFromTools(tools: List<Part.Tool>): List<String> {
    val seen = HashSet<String>()
    val out = mutableListOf<String>()
    for (tool in tools) {
        val path = producedPathIfSettled(tool) ?: continue
        if (seen.add(path)) out.add(path)
    }
    return out
}

/**
 * turn 内产出文件（消息视觉序遍历全部 [Part.Tool] parts；含 DSH 工具卡宿主
 * 消息 dsh-call-*）。供 [computeRenderableTurn] 预计算进 [RenderableTurn]——
 * 折叠输入是原始 parts 而非 renderItems（#247 同键折叠会吞后续同键卡的
 * args，从 renderItems 折会漏产出）。
 */
fun turnProducedFiles(turnMessages: List<ChatMessage>?): List<String> =
    producedFilesFromTools(
        turnMessages.orEmpty().flatMap { it.parts.filterIsInstance<Part.Tool>() },
    )

// ---- 私有 JSON 取值助手（string 判定对齐 JS typeof === "string"） ----

/** 字符串原语取值（数字/布尔/null 原语与结构化值均不算 string）。 */
private fun stringValue(input: Map<String, JsonElement>, key: String): String? =
    (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 非空路径（trim 判空；命中则保留精确拼写不改写）。 */
private fun pathValue(input: Map<String, JsonElement>, key: String): String? =
    stringValue(input, key)?.takeIf { it.trim().isNotEmpty() }
