package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionPermissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DSH session.list 条目 → 域模型 [Session] 映射器（backlog #276 步骤③；V2SessionMapper 先例）。
 *
 * 字段映射（§5 P-4 实测形状）：sessionId→id · updatedAt(epoch-ms)→time.updated ·
 * cwd→directory · parentSessionId→parentId · projections[title].title→title。
 *
 * 已知缺口（记录不掩盖）：
 * - **无 created 时刻**——time.created 置 0（unknown 哨兵；排序用 time.updated 不受影响，
 *   UI 展示 created 的入口待 UI 卡裁决）；
 * - running/origin 无 [Session] 对应槽位（running 走 WS host/session-status）；
 *   blank/agentPreset 已入槽（#agentPreset：空白页预设卡门控 + 当前值回显）。
 */
object DshSessionMapper {

    fun toSession(item: JsonObject): Session {
        // E2E 实证（2026-08-31）：活体投影值是**裸字符串**（364/378 为 str）；对象形态
        // 为 fixture 误设，双读防御兼容。
        val titleProjection = item.dshObj("projections")?.dshObj("values")?.get("title")
        val title = when (titleProjection) {
            null -> null
            is JsonPrimitive -> titleProjection.contentOrNull
            else -> (titleProjection as? JsonObject)?.dshStr("title")
        }
        return Session(
            id = item.dshStr("sessionId") ?: "",
            directory = item.dshStr("cwd") ?: "",
            parentId = item.dshStr("parentSessionId"),
            title = title,
            time = Session.Time(
                created = 0L,
                updated = item.dshLong("updatedAt") ?: 0L,
            ),
            permissions = parsePermissions(item),
            blank = item.dshBool("blank") ?: false,
            agentPreset = item.dshStr("agentPreset"),
            // B：tokenUsage/subagentTiming 投影基线（帧驱动 last-wins 的前置种子——
            // 打开已完成子代理会话时弹窗即可展示，无需等 projection 帧）
            tokenUsage = parseTokenUsage(item),
            subagentTiming = parseSubagentTiming(item),
            // C（backlog #286）：goal/contextPressure/contextBreakdown/sessionStats 投影基线
            // （帧驱动 last-wins 的前置种子——进入会话即展示，无需等 projection 帧）
            goal = parseGoal(item),
            contextPressure = parseContextPressure(item),
            contextBreakdown = parseContextBreakdown(item),
            sessionStats = parseSessionStats(item),
        )
    }

    /** tokenUsage 投影（projections.values.tokenUsage）→ 域模型；缺席返回 null。 */
    private fun parseTokenUsage(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshTokenUsage? {
        val v = item.dshObj("projections")?.dshObj("values")?.dshObj("tokenUsage") ?: return null
        return dev.leonardo.ocbeacon.domain.model.DshTokenUsage(
            uncachedInputTokens = v.dshLong("uncachedInputTokens") ?: 0L,
            outputTokens = v.dshLong("outputTokens") ?: 0L,
            cacheReadTokens = v.dshLong("cacheReadTokens") ?: 0L,
            cacheWriteTokens = v.dshLong("cacheWriteTokens") ?: 0L,
        )
    }

    /** subagentTiming 投影（projections.values.subagentTiming）→ 域模型；缺席返回 null。 */
    private fun parseSubagentTiming(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshSubagentTiming? {
        val v = item.dshObj("projections")?.dshObj("values")?.dshObj("subagentTiming") ?: return null
        val active = v.dshObj("active")
        return dev.leonardo.ocbeacon.domain.model.DshSubagentTiming(
            settledMs = v.dshLong("settledMs") ?: 0L,
            activeSince = active?.dshLong("since"),
            activeThrough = active?.dshLong("through"),
        )
    }


    /** goal 投影（projections.values.goal）→ 域模型；缺席/显式 null（clear/首建前）返回 null。 */
    private fun parseGoal(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshGoalProjection? {
        val values = item.dshObj("projections")?.dshObj("values") ?: return null
        val v = values["goal"] ?: return null
        val goalObj = (v as? JsonObject)?.dshObj("goal") ?: return null
        val id = goalObj.dshStr("id") ?: return null
        val blocked = goalObj.dshObj("blockedReason")
        return dev.leonardo.ocbeacon.domain.model.DshGoalProjection(
            goal = dev.leonardo.ocbeacon.domain.model.DshGoalSnapshot(
                id = id,
                revision = goalObj.dshLong("revision") ?: 0L,
                objective = goalObj.dshStr("objective") ?: "",
                phase = goalObj.dshStr("phase") ?: "active",
                blockedReason = blocked?.let {
                    dev.leonardo.ocbeacon.domain.model.DshGoalBlockReason(
                        code = it.dshStr("code") ?: "",
                        message = it.dshStr("message") ?: "",
                    )
                },
                maxGoalRounds = goalObj.dshLong("maxGoalRounds") ?: 0L,
            ),
            roundsStarted = v.dshLong("roundsStarted") ?: 0L,
            createdAt = v.dshLong("createdAt") ?: 0L,
            updatedAt = v.dshLong("updatedAt") ?: 0L,
        )
    }

    /** contextPressure 投影（projections.values.contextPressure）；缺席返回 null。 */
    private fun parseContextPressure(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshContextPressure? {
        val v = item.dshObj("projections")?.dshObj("values")?.dshObj("contextPressure") ?: return null
        return dev.leonardo.ocbeacon.domain.model.DshContextPressure(
            pressureTokens = v.dshLong("pressureTokens"),
            projectedTokens = v.dshLong("projectedTokens"),
            contextWindow = v.dshLong("contextWindow"),
        )
    }

    /** contextBreakdown 投影（projections.values.contextBreakdown）；缺席返回 null。 */
    private fun parseContextBreakdown(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshContextBreakdown? {
        val v = item.dshObj("projections")?.dshObj("values")?.dshObj("contextBreakdown") ?: return null
        return dev.leonardo.ocbeacon.domain.model.DshContextBreakdown(
            systemTokens = v.dshLong("systemTokens") ?: 0L,
            toolsTokens = v.dshLong("toolsTokens") ?: 0L,
            messageTokens = v.dshLong("messageTokens") ?: 0L,
        )
    }

    /** sessionStats 投影（projections.values.sessionStats）；缺席返回 null。 */
    private fun parseSessionStats(item: JsonObject): dev.leonardo.ocbeacon.domain.model.DshSessionStats? {
        val v = item.dshObj("projections")?.dshObj("values")?.dshObj("sessionStats") ?: return null
        return dev.leonardo.ocbeacon.domain.model.DshSessionStats(
            turns = v.dshLong("turns") ?: 0L,
            steps = v.dshLong("steps") ?: 0L,
            llmMs = v.dshLong("llmMs") ?: 0L,
            toolMs = v.dshLong("toolMs") ?: 0L,
            ttftMs = v.dshLong("ttftMs") ?: 0L,
            ttftSteps = v.dshLong("ttftSteps") ?: 0L,
            decodeMs = v.dshLong("decodeMs") ?: 0L,
            decodeTokens = v.dshLong("decodeTokens") ?: 0L,
        )
    }

    /**
     * permissions 投影 → [SessionPermissions]（§permissions 投影 = {options,currentValue}）。
     * 投影缺席返回 null（OpenCode 会话 / DSH 部署未挂 permission 插件均无此键）。
     */
    private fun parsePermissions(item: JsonObject): SessionPermissions? {
        val value = item.dshObj("projections")?.dshObj("values")?.dshObj("permissions") ?: return null
        val options = (value.dshArr("options") ?: emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val presetValue = o.dshStr("value") ?: return@mapNotNull null
            SessionPermissions.PermissionPresetOption(
                value = presetValue,
                name = o.dshStr("name") ?: presetValue,
                description = o.dshStr("description"),
            )
        }
        return SessionPermissions(
            options = options,
            currentValue = value.dshStr("currentValue"),
        )
    }

    /**
     * 目录过滤（本地降级）：DSH session.list 无 directory 参数（§5：cursor 同样
     * 未实现）——按 cwd 全等过滤；blank 会话（空壳，无用户内容）一并滤除。
     * directory=null 返回全量非 blank（V1 headerless 语义对齐）。
     */
    fun filterByDirectory(items: List<JsonObject>, directory: String?): List<JsonObject> =
        items.filter { item ->
            val blank = item.dshBool("blank") ?: false
            !blank && (directory == null || item.dshStr("cwd") == directory)
        }
}

// ============ JsonObject/JsonArray 安全取值（包内复用；畸形输入 null 容错） ============

internal fun JsonObject.dshStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.dshLong(key: String): Long? = dshStr(key)?.toLongOrNull()

internal fun JsonObject.dshInt(key: String): Int? = dshStr(key)?.toIntOrNull()

internal fun JsonObject.dshBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        ?.let { it.contentOrNull?.toBooleanStrictOrNull() }

internal fun JsonObject.dshObj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.dshArr(key: String): JsonArray? = this[key] as? JsonArray