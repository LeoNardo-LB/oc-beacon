package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.sse.parsers.SseEventParser
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "SseClientV2"

/**
 * V2 专属事件解析器——处理 OpenCode V2 的细粒度流式事件。
 *
 * V2 服务器在活跃会话期间高频发送这些事件（V1 没有）：
 * - session.reasoning.started/delta/ended —— 推理内容流
 * - session.tool.input.started/delta/ended —— 工具输入流
 * - session.tool.called/success/progress —— 工具调用状态
 * - session.step.started/ended —— agent 步骤
 * - shell.created/exited/deleted —— shell 生命周期
 * - session.usage.updated —— token 用量
 *
 * 这些事件当前不映射到具体 UI 行为（V2 会话信息通过 REST/SSE 的
 * message 级事件驱动渲染），但必须被解析为占位事件：
 * 1. 让 SseClientV2 能计数并重置心跳（数据流即存活证据）
 * 2. 让下游观察到会话有活动（而不是静默丢弃）
 *
 * 返回 SseEvent.SessionNext(Unknown) 包装，保持事件可观察且不破坏
 * 现有 SseEvent 密封类的结构。
 */
class V2EventParser(private val json: Json) : SseEventParser {

    private val handledPrefixes = listOf(
        "session.reasoning.",
        "session.tool.",
        "session.step.",
        "session.usage.",
        "session.text.",
        "session.message.",
        "session.shell.",
        "session.execution.",
        "session.instructions.",
        "session.compacted",
        "shell."
    )

    override fun canParse(eventType: String): Boolean =
        handledPrefixes.any { eventType.startsWith(it) }

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "V2 event: $eventType ${props.toString().take(150)}")
        }
        // V2 turn 生命周期（2026-08-11 实测：v2 不发 session.status/session.idle；
        // turn 开始/结束权威信号是 execution.started/succeeded）→ 复用既有 FSM 语义：
        // started → Busy（FSM core），succeeded → Idle（forceComplete）
        when (eventType) {
            "session.execution.started" -> {
                return SseEvent.SessionStatus(
                    sessionId = sessionIdOrNull(props) ?: "",
                    status = dev.leonardo.ocbeacon.domain.model.SessionStatus.Busy
                )
            }
            "session.execution.succeeded" -> {
                return SseEvent.SessionIdle(sessionId = sessionIdOrNull(props) ?: "")
            }
        }
        // 后台 shell 生命周期——映射为具体事件（驱动 ShellJob 状态流与消息流 Shell 卡片）
        when (eventType) {
            "session.shell.started", "shell.created" -> {                // V2 实测：服务器广播 shell.created（旧事件名），payload 为 {info: Shell.Info}；
                // session.shell.started 为 {shell: Shell.Info}（新命名，兼容两者）
                val shellObj = props["shell"]?.jsonObject ?: props["info"]?.jsonObject
                if (shellObj != null) {
                    return SseEvent.ShellJobStarted(V2ShellMapper.toShellJob(shellObj))
                }
            }
            "session.shell.ended", "shell.exited", "shell.deleted" -> {
                // shell.exited: {id, exit, status}；shell.deleted: {id}
                val shellObj = props["shell"]?.jsonObject ?: props["info"]?.jsonObject
                val info = if (shellObj != null) {
                    V2ShellMapper.toShellJob(shellObj)
                } else {
                    ShellJob(
                        id = props["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = props["status"]?.jsonPrimitive?.contentOrNull
                            ?: if (eventType == "shell.deleted") "deleted" else "exited",
                        exit = props["exit"]?.jsonPrimitive?.intOrNull,
                        sessionId = sessionIdOrNull(props)
                    )
                }
                val output = props["output"]?.jsonPrimitive?.contentOrNull
                return SseEvent.ShellJobEnded(info = info, output = output)
            }
        }
        // V2 压缩完成事件（2026-08-12 实测：V2 服务器只发单个
        // session.compacted {sessionID}，无 V1 的 started/delta/ended 三件套）
        if (eventType == "session.compacted") {
            val sid = sessionIdOrNull(props) ?: return null
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionEnded(
                    sessionId = sid,
                    messageId = ""
                )
            )
        }
        // 提取会话 ID（不同事件可能在不同字段）
        val sessionId = sessionIdOrNull(props)

        return SseEvent.SessionNext(
            dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown(
                rawType = eventType,
                rawJson = props.toString()
            )
        )
    }

    private fun sessionIdOrNull(props: JsonObject): String? =
        props["sessionID"]?.jsonPrimitive?.contentOrNull
            ?: props["sessionId"]?.jsonPrimitive?.contentOrNull
}
