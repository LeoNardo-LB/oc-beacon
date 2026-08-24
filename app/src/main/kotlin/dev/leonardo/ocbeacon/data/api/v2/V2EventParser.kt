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
 * 其中部分事件已映射具体行为（execution.started/succeeded→FSM Busy/Idle、
 * shell.*→ShellJob 生命周期、compaction.*→压缩状态、usage.updated→用量、
 * tool.progress→工具进度），其余为保活占位事件：
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
        "session.compaction.",
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
        // V2 压缩完成事件。2026-08-16 重大更新（E2E 实证）：next-17430 部署版
        // **会发 session.compaction.started**（此前调研结论「V2 只发单个
        // session.compacted」基于旧版本，已过时）——现补齐解析为
        // CompactionStarted（事件驱动优先于 SessionActionsDelegate 本地置态）。
        if (eventType == "session.compaction.started") {
            val sid = sessionIdOrNull(props) ?: return null
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionStarted(
                    sessionId = sid,
                    // #219 勘误：实测 payload 字段是 inputID（compaction 消息 id），
                    // 非 messageID——此前恒空，消息流内对位（Q13 连续性）失效。
                    messageId = props["inputID"]?.jsonPrimitive?.contentOrNull
                        ?: props["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                    reason = props["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            )
        }
        // 2026-08-16（compaction.failed 补全）：压缩失败事件——映射 CompactionEnded
        // 结束进行中气泡（失败提示走 HTTP 回调路径已有 snackbar；此映射保证
        // FSM 不会停留在 Busy/Compacting——E2E 曾实测该事件到达但被 unhandled）。
        if (eventType == "session.compaction.failed") {
            val sid = sessionIdOrNull(props) ?: return null
            if (BuildConfig.DEBUG) {
                AppLogger.w(TAG, "compaction.failed: ${props["reason"]?.jsonPrimitive?.contentOrNull ?: ""} err=${props["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.take(80)}")
            }
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionEnded(
                    sessionId = sid,
                    messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                    // #219：失败原因带给 UI（此前被丢弃——V2 HTTP 秒回受理，失败只从
                    // SSE 到达；静默结束 = 用户只见分割线闪一下，无从得知失败）。
                    error = props["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Compaction failed"
                )
            )
        }
        // 2026-08-19（compaction.ended/delta 补全）：beta-17639 实测——V2 细粒度
        // 压缩事件为 started/delta/ended 三段，不再发 legacy session.compacted。
        // .ended 此前落入 Unknown（E2E 实证：服务器压缩成功但完成 snackbar 永不
        // 显示）。映射为 SessionCompacted（"压缩完毕"的既有语义信号）：驱动
        // compactedSessions → ChatViewModel 完成 snackbar + 消息刷新（含压缩
        // 分割线卡片）；压缩横幅终结由 EventDispatcher.processEvent 跨 handler
        // 处理。刻意**不**映射 SessionNext(CompactionEnded)：HTTP 回调的合成注入
        // （ChatViewModel.compactionNotifier）也用该类型——复用会把"本地幂等结束"
        // 与"服务器真实完成"混为一谈（后者才是 snackbar 的正确触发时机）。
        if (eventType == "session.compaction.ended") {
            val sid = sessionIdOrNull(props) ?: return null
            return SseEvent.SessionCompacted(sessionId = sid)
        }
        // 2026-08-19：压缩文本流式增量——映射 CompactionDelta（handler 无状态
        // 变更已跟踪；消灭 Unhandled 日志噪音，保持事件可观察 + 心跳计数）。
        // 字段契约（beta-17639 E2E 实测）：增量文本在 "text"（V1 域事件用 "delta"）。
        if (eventType == "session.compaction.delta") {
            val sid = sessionIdOrNull(props) ?: return null
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionDelta(
                    sessionId = sid,
                    messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                    delta = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: props["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            )
        }
        if (eventType == "session.compacted") {
            val sid = sessionIdOrNull(props) ?: return null
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionEnded(
                    sessionId = sid,
                    messageId = ""
                )
            )
        }
        // 2026-08-15：session 级 token 用量（实测 payload：{sessionID, cost,
        // tokens:{input,output,reasoning,cache:{read,write}}}）——服务器权威
        // 累计值，顶部 context 指示器的实时数据源（此前被 Unknown 丢弃）。
        if (eventType == "session.usage.updated") {
            val sid = sessionIdOrNull(props) ?: return null
            val tokens = props["tokens"]?.jsonObject
            // cost 兼容对象/数字/缺失（防御——历史测试样本曾出现对象形态）
            val cost = when (val c = props["cost"]) {
                is kotlinx.serialization.json.JsonPrimitive -> c.contentOrNull?.toDoubleOrNull() ?: 0.0
                is JsonObject -> c["total"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            return SseEvent.SessionNext(
                dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated(
                    sessionId = sid,
                    cost = cost,
                    tokens = tokens?.let { t ->
                        dev.leonardo.ocbeacon.domain.model.SessionNextEvent.SessionUsageTokens(
                            input = t["input"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            output = t["output"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            reasoning = t["reasoning"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            cache = dev.leonardo.ocbeacon.domain.model.SessionNextEvent.SessionUsageCache(
                                read = t["cache"]?.jsonObject?.get("read")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                                write = t["cache"]?.jsonObject?.get("write")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            )
                        )
                    } ?: dev.leonardo.ocbeacon.domain.model.SessionNextEvent.SessionUsageTokens()
                )
            )
        }
        // 2026-08-15（research/08 P0）：工具实时进度（当前部署版实测抓帧：
        // session.tool.progress {sessionID, assistantMessageID, id(=callId),
        // metadata:{output: 全量尾部快照,...}}）——此前落入 Unknown 丢弃，
        // 工具运行中输出从未显示。映射到 ToolProgress（metadata 整体替换语义）。
        if (eventType == "session.tool.progress") {
            val sid = sessionIdOrNull(props)
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull
            val callId = props["id"]?.jsonPrimitive?.contentOrNull
            if (sid != null && messageId != null && callId != null) {
                return SseEvent.SessionNext(
                    dev.leonardo.ocbeacon.domain.model.SessionNextEvent.ToolProgress(
                        sessionId = sid,
                        messageId = messageId,
                        partId = callId,
                        callId = callId,
                        progress = null,
                        title = null,
                        metadata = props["metadata"]?.jsonObject
                    )
                )
            }
        }
        // 提取会话 ID（不同事件可能在不同字段）
        val sessionId = sessionIdOrNull(props)

        return SseEvent.SessionNext(
            dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown(
                rawType = eventType,
                // #102（M-4）：rawJson 截断——未知事件完整 JSON 可能 MB 级
                // （如超大 tool 输出/附件元数据），诊断用途只保留头部
                rawJson = props.toString().take(RAW_JSON_MAX_CHARS)
            )
        )
    }

    private companion object {
        /** #102（M-4）：Unknown 事件 rawJson 截断上限（诊断用途）。 */
        const val RAW_JSON_MAX_CHARS = 2_000
    }

    private fun sessionIdOrNull(props: JsonObject): String? =
        props["sessionID"]?.jsonPrimitive?.contentOrNull
            ?: props["sessionId"]?.jsonPrimitive?.contentOrNull
}
