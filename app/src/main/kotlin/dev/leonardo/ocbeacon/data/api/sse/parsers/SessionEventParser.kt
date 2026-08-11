package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * 解析会话生命周期事件：
 * - session.status, session.idle, session.created, session.updated, session.deleted
 * - session.error, session.diff, session.compacted
 * - vcs.branch.updated, project.updated, lsp.updated
 */
class SessionEventParser(private val json: Json) : SseEventParser {

    private val handledTypes = setOf(
        "session.status", "session.idle", "session.created", "session.updated",
        "session.deleted", "session.error", "session.diff", "session.compacted",
        "vcs.branch.updated", "project.updated", "lsp.updated"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "session.status" -> {
                    val sessionId = props.str("sessionID")
                    val statusObj = props["status"]?.jsonObject
                    val statusType = statusObj?.get("type")?.jsonPrimitive?.content ?: "idle"

                    val status = when (statusType) {
                        "idle" -> SessionStatus.Idle
                        "busy" -> SessionStatus.Busy
                        "retry" -> SessionStatus.Retry(
                            attempt = statusObj?.get("attempt")?.jsonPrimitive?.int ?: 0,
                            message = statusObj?.get("message")?.jsonPrimitive?.content ?: "",
                            next = statusObj?.get("next")?.jsonPrimitive?.long ?: 0
                        )
                        else -> SessionStatus.Idle
                    }

                    AppLogger.i(TAG, "Session $sessionId status -> $statusType")
                    SseEvent.SessionStatus(sessionId = sessionId, status = status)
                }

                "session.idle" -> {
                    val sessionId = props.str("sessionID")
                    AppLogger.i(TAG, "Session $sessionId idle")
                    SseEvent.SessionIdle(sessionId = sessionId)
                }

                "session.created" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = decodeSessionCompat(infoObj)
                    SseEvent.SessionCreated(info)
                }

                "session.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = decodeSessionCompat(infoObj)
                    SseEvent.SessionUpdated(info)
                }

                "session.deleted" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = decodeSessionCompat(infoObj)
                    SseEvent.SessionDeleted(info)
                }

                "session.error" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content
                    val error = props.str("error", "Unknown error")
                    SseEvent.SessionError(sessionId = sessionId, error = error)
                }

                "session.diff" -> {
                    val sessionId = props.str("sessionID")
                    val diffArr = props["diff"]?.jsonArray
                    val diffs = diffArr?.map { json.decodeFromJsonElement<FileDiff>(it) } ?: emptyList()
                    SseEvent.SessionDiff(sessionId = sessionId, diff = diffs)
                }

                "session.compacted" -> {
                    SseEvent.SessionCompacted(sessionId = props.str("sessionID"))                }

                "vcs.branch.updated" -> {
                    val branch = props.str("branch")
                    SseEvent.VcsBranchUpdated(branch = branch)
                }

                "project.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<dev.leonardo.ocbeacon.domain.model.Project>(infoObj)
                    SseEvent.ProjectUpdated(info)
                }

                "lsp.updated" -> SseEvent.LspUpdated

                else -> null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }

    /**
     * 会话事件兼容解码：
     * - V1 格式：`{info: {完整 Session}}` 或直接完整 Session（id + time 必填）
     * - V2 格式：扁平字段 `{sessionID, parentID, title, agent, model, location, version}`
     *   ——没有 info 包装、没有 id/time，需手动映射（2026-08-11 实测：V2 服务器
     *   广播的 session.created 就是扁平格式，原解码抛 MissingFieldException，
     *   导致子会话无法注册 → 后台 subagent 列表为空）。
     */
    private fun decodeSessionCompat(obj: JsonObject): Session {
        // 尝试 V1 完整 Session（id + time 存在）
        if (obj["id"] != null && obj["time"] != null) {
            return json.decodeFromJsonElement<Session>(obj)
        }
        // V2 扁平格式
        val now = System.currentTimeMillis()
        val location = obj["location"]?.jsonObject
        return Session(
            id = obj["sessionID"]?.jsonPrimitive?.contentOrNull ?: "",
            slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: "",
            projectId = obj["projectID"]?.jsonPrimitive?.contentOrNull ?: "",
            directory = location?.get("directory")?.jsonPrimitive?.contentOrNull
                ?: obj["directory"]?.jsonPrimitive?.contentOrNull ?: "",
            parentId = obj["parentID"]?.jsonPrimitive?.contentOrNull,
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "",
            time = Session.Time(created = now, updated = now),
            agent = obj["agent"]?.jsonPrimitive?.contentOrNull,
            model = obj["model"]?.jsonPrimitive?.contentOrNull?.let { modelId ->
                Session.SessionModel(id = modelId, providerId = "")
            }
        )
    }
}
