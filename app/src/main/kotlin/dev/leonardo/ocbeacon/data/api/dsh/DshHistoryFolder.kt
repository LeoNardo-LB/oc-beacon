package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val TAG = "DshHistoryFolder"

/**
 * DSH 历史折叠器（backlog #275 组件 B；设计文档 §1.7 fold 范围决策 + §2.3 事件层）。
 *
 * `session.history` 原始日志 → [SseEvent] 序列（复用 [DshEventMapper.mapSessionEvent]
 * 内层分派——历史重放与实况流式同一映射路径，§1.5 结论 3：整值规则下映射是逐事件
 * 无状态变换，非增量 fold 状态机）。产物喂 EventDispatcher.processEvent 重放（#276），
 * 历史加载与断连对账回填共用。
 *
 * 行形态三筛（§1.7 信封二态 + header）：
 * 1. **header 行**（type=session，每会话一条）：跳过事件产出，sessionId 供后续行
 *    路由（显式 [sessionId] 参数优先）；
 * 2. **chunk 族**：assistant/chunk 单块行 + seq0 打包行（reasoning-chunks /
 *    text-chunks / tool-call-chunks，checkpoint-policy 批量块）——一律不进历史 fold，
 *    整装文本由 assistant/message 承载（历史尾页由整装主导，§1.7）；
 * 3. **活事件行**（{type, seq, time, data}）：经 mapper 分派。
 *
 * lastSeq = 全部行（含打包行 seq0——服务端视角已应用水位）的最大 seq；漏计打包行
 * 会让 DshReconciler 对以打包行收尾的会话误判缺口 → 回填死循环。
 *
 * 拒绝重建判据（§5）：[DshFoldResult.unknownUnignorable] 非空 = 折叠中遇到未建模的
 * 事件类型（可能携带转录语义）——调用方应放弃本次重建而非展示残缺历史。
 */
object DshHistoryFolder {

    private val json = Json

    /**
     * 折叠历史行列表。[events] 为原始 SessionEvent 行；session.history RPC 的
     * HistoryEntry 包装行（{"event": {...}, "view": ...}）自动解包（view 是宿主
     * 渲染意图，不持久化——忽略）。
     */
    fun fold(events: List<JsonObject>, sessionId: String? = null): DshFoldResult {
        var resolvedSessionId = sessionId ?: ""
        var lastSeq = 0L
        val sseEvents = mutableListOf<SseEvent>()
        val unknownUnignorable = mutableListOf<String>()
        for (row in events) {
            val entry = row.obj("event") ?: row
            val type = entry.str("type") ?: continue
            // lastSeq 先行：无论行是否产出事件，服务端已应用水位都要推进
            val rowSeq = entry.long("seq") ?: entry.long("seq0")
            if (rowSeq != null && rowSeq > lastSeq) lastSeq = rowSeq
            when {
                // header 行：会话元信息，非事件
                type == "session" -> resolvedSessionId = sessionId ?: (entry.str("id") ?: resolvedSessionId)
                // chunk 族（单块行）：流式保真专用，不进历史 fold
                type == "assistant/chunk" -> Unit
                // checkpoint 打包行（按 key 区分信封二态：seq0 存在 = 批量块）
                entry.containsKey("seq0") -> Unit
                else -> DshEventMapper.mapSessionEvent(resolvedSessionId, entry).forEach { mapped ->
                    when (mapped) {
                        is DshMappedEvent.Sse -> sseEvents += mapped.event
                        // 历史行不产生订阅信号（那是 WS 帧面专属）
                        is DshMappedEvent.Subscribed -> Unit
                        is DshMappedEvent.Ignored ->
                            if (mapped.reason == DshIgnoreReason.UNKNOWN_UNIGNORABLE) {
                                unknownUnignorable += type
                            }
                    }
                }
            }
        }
        return DshFoldResult(
            sessionId = resolvedSessionId,
            sseEvents = sseEvents,
            unknownUnignorable = unknownUnignorable,
            lastSeq = lastSeq,
        )
    }

    /**
     * 便捷入口：JSONL 文本（session.history 拼接行 / session.jsonl 形态）直接折叠。
     * 空白行跳过；无法解析的行记日志跳过（§1.7 实测 0 坏行，此处仅防御）。
     */
    fun foldLines(jsonl: String, sessionId: String? = null): DshFoldResult {
        val rows = jsonl.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: run {
                    AppLogger.w(TAG, "跳过无法解析的历史行: " + line.take(120))
                    null
                }
        }.toList()
        return fold(rows, sessionId)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}

/**
 * 折叠产物：SseEvent 序列（保序）+ 拒绝重建判据 + 已应用水位 + 会话 id。
 *
 * [refusedRebuild] 为 true 时调用方不得用 [sseEvents] 重建（历史含未建模事件类型，
 * 展示将残缺——§5 fold 安全规则）；[lastSeq] 仍可安全上报（水位与事件语义无关）。
 */
data class DshFoldResult(
    val sessionId: String,
    val sseEvents: List<SseEvent>,
    val unknownUnignorable: List<String>,
    val lastSeq: Long,
) {
    val refusedRebuild: Boolean get() = unknownUnignorable.isNotEmpty()
}
