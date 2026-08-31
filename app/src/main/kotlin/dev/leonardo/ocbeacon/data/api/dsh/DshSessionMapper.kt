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
 * - running/blank/origin/agentPreset 无 [Session] 对应槽位（running 走 WS host/session-status）。
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
