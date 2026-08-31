package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PartIdContract
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val TAG = "DshEventMapper"

/**
 * DSH 帧映射器（backlog #275 组件 A；设计文档 §1.5 帧词汇表 + §1.7 fold 决策）。
 *
 * 纯函数 / 无状态 / 不抛异常：DSH SessionEvent 是 49 型开放联合（§1.6-7），未知
 * type 按 data 宽透传——本映射器对一切畸形/未知输入降级为 [DshMappedEvent.Ignored]
 * （AppLogger.w 记日志不崩），未知 **SessionEvent 类型** 落
 * [DshIgnoreReason.UNKNOWN_UNIGNORABLE]——DshHistoryFolder 据此拒绝重建（§5 信封
 * 细节规则：仅 llm/failover 带 ignorable:true，未知类型无 ignorable 必须拒绝重建）。
 *
 * ## ID 契约（写死，跨重放/实况稳定）
 * - 整装消息 id："seq-{event.seq}"（历史重放与实况同键——已定决策）；
 * - 实况流式宿主 id："dsh-t{turn}s{step}"（chunk 族无消息 id，按 turn/step 复合；
 *   assistant/message 整装到达时以 [SseEvent.MessageRemoved] 拆除该宿主，防止
 *   流式骨架与整装并存导致内容双份；fold 场景无骨架，removal 为幂等 no-op）；
 * - 工具卡宿主消息 id："dsh-call-{callId}"（tool/call 与 tool/result 是两个独立
 *   SessionEvent，无状态映射下唯一可共享的连接键是 callId——seq 派生 id 无法跨事件
 *   汇合；副作用：工具卡渲染为独立 assistant 气泡，位于整装文本气泡之前）；
 * - 文本 part id：委托 [PartIdContract.derive]（"{msg}_{kind}_ord_{ordinal}"）——
 *   ordinal = 整装 content 数组下标 / chunk.index（同为块序号，实况与历史对齐）；
 *   kind 编码进 id 是 #230 delta kind 推断的承重约定，不得换格式。
 *
 * ## 时间契约
 * Message.time.created/completed = 事件 time（红点水位线 UnreadStateStore.maxCompleted
 * 依赖 completed 时刻，设计 §2.3）；整装 part 的 time.start/end 同为事件 time
 * （终态标记 → #266 迟到 delta 守卫）。实况 block-start 只带 start——终态化由
 * turn/end → SessionIdle → markSessionIdle 路径承担。
 *
 * 接入（#276）：DshWsEventClient.onFrame(method, payload) → mapFrame → Sse 分支喂
 * EventDispatcher.processEvent；Subscribed 分支喂 DshReconciler 对账。本组件不接
 * dispatcher（#275 范围外）。
 */
object DshEventMapper {

    /** 整装消息 id（user/message、assistant/message）。 */
    fun messageId(seq: Long): String = "seq-" + seq

    /** 实况流式宿主消息 id（assistant/chunk 族）。 */
    fun streamingMessageId(turn: Long, step: Long): String = "dsh-t" + turn + "s" + step

    /** 工具卡宿主消息 id（tool/call 创建、tool/result 汇合）。 */
    fun toolHostMessageId(callId: String): String = "dsh-call-" + callId

    private val json = Json

    // ============ 帧面（WS server-request → DshMappedEvent） ============

    /**
     * 单帧映射。[rpcId] 可选：question 帧载荷无帧级 id，回程路由键是信封 rpcId
     * （§1.6-6 pending 注册表）——调用方（#276）持有信封时应传入。
     */
    fun mapFrame(method: String, payload: JsonObject, rpcId: String? = null): List<DshMappedEvent> =
        runCatching { mapFrameInner(method, payload, rpcId) }.getOrElse { t ->
            AppLogger.w(TAG, "帧映射容错降级: method=" + method + " – " + t.message)
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        }

    private fun mapFrameInner(method: String, payload: JsonObject, rpcId: String?): List<DshMappedEvent> = when (method) {
        "session/subscribed" -> {
            // 连接层信号：开流基线（对账起点，组件 C 输入）——不产生 SseEvent
            val sid = payload.str("sessionId")
            val lastSeq = payload.long("lastSeq")
            if (sid == null || lastSeq == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(DshMappedEvent.Subscribed(DshSubscribed(sid, lastSeq)))
        }

        "session/event" -> {
            // 原始 SessionEvent 透传；view 是宿主算的渲染意图，不持久化（§1.5）——忽略
            val event = payload.obj("event")
            if (event == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else mapSessionEvent(payload.str("sessionId") ?: "", event)
        }

        "approval/requested" -> {
            val sid = payload.str("sessionId")
            val approvalId = payload.str("approvalId")
            if (sid == null || approvalId == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.PermissionAsked(
                        id = approvalId,
                        sessionId = sid,
                        // DSH 审批对象是「执行某工具」：permission 装载 toolName，
                        // callId/reason 进 metadata（PermissionAsked 无专属槽位）
                        permission = payload.str("toolName") ?: "tool",
                        // #276 接线注意①：信封 rpcId 一并入 metadata——/api/respond
                        // 回程路由键（§1.6-6 pending 注册表）；requested/resolved 帧
                        // 载荷只带 approvalId（成对解析键），回程键若与 approvalId
                        // 不同（E2E 定音），reply 路径经 metadata["rpcId"] 取真键。
                        metadata = buildMap {
                            payload.str("callId")?.let { put("callId", it) }
                            payload.str("reason")?.let { put("reason", it) }
                            rpcId?.let { put("rpcId", it) }
                        }.takeIf { it.isNotEmpty() },
                    )
                )
            )
        }

        "approval/resolved" -> {
            val sid = payload.str("sessionId")
            val approvalId = payload.str("approvalId")
            if (sid == null || approvalId == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(DshMappedEvent.Sse(SseEvent.PermissionReplied(sessionId = sid, requestId = approvalId)))
        }

        "question/requested" -> {
            val sid = payload.str("sessionId")
            // 载荷无帧级 id：questionId/id 缺席时回退信封 rpcId；都无则无法路由回程 → 丢弃
            val id = payload.str("questionId") ?: payload.str("id") ?: rpcId
            if (sid == null || id == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else {
                val questions = (payload.arr("questions") ?: emptyList()).mapNotNull { el ->
                    (el as? JsonObject)?.let { mapQuestionItem(it) }
                }
                listOf(DshMappedEvent.Sse(SseEvent.QuestionAsked(id = id, sessionId = sid, questions = questions)))
            }
        }

        "question/resolved" -> {
            val sid = payload.str("sessionId")
            val id = payload.str("questionId") ?: payload.str("id") ?: rpcId
            when {
                sid == null || id == null -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                payload.bool("cancelled") == true ->
                    listOf(DshMappedEvent.Sse(SseEvent.QuestionRejected(sessionId = sid, requestId = id)))
                else -> listOf(DshMappedEvent.Sse(SseEvent.QuestionReplied(sessionId = sid, requestId = id)))
            }
        }

        // 显式忽略面（#276/后续承接：堆积消息域 / 后台任务 / 投影单元 / 连接层错误）
        "session/queue" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.QUEUE))
        "session/jobs" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.JOBS))
        "session/projection" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.PROJECTION))
        "stream/error" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.STREAM_ERROR))

        "host/session-added" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.SessionCreated(
                        // 最小构造（任务裁决）：cwd→directory、parentSessionId→parentId；
                        // 帧无时间字段 → time 必填以 epoch0 占位，#276 由 session.list 再基线
                        Session(
                            id = sid,
                            directory = payload.str("cwd") ?: "",
                            parentId = payload.str("parentSessionId"),
                            time = Session.Time(created = 0L, updated = 0L),
                        )
                    )
                )
            )
        }

        "host/session-removed" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(DshMappedEvent.Sse(SseEvent.SessionDeleted(Session(id = sid, time = Session.Time(0L, 0L)))))
        }

        "host/session-status" -> {
            val sid = payload.str("sessionId")
            val running = payload.bool("running")
            if (sid == null || running == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(SseEvent.SessionStatus(sid, if (running) SessionStatus.Busy else SessionStatus.Idle))
            )
        }

        "host/agent-error" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(DshMappedEvent.Sse(SseEvent.SessionError(sessionId = sid, error = payload.errorText("error"))))
        }

        // host/workspace-* 与 archived-sessions-changed：整快照姿态，oc-beacon 无 Workspace 域对应
        else -> {
            if (!method.startsWith("host/")) {
                AppLogger.w(TAG, "未知帧型（连接层容错）: method=" + method)
            }
            listOf(
                DshMappedEvent.Ignored(
                    if (method.startsWith("host/")) DshIgnoreReason.HOST_WORKSPACE else DshIgnoreReason.FRAME_METHOD
                )
            )
        }
    }

    /** AskUserQuestionItem → QuestionAsked.Question（多题单事件 questions 列表）。 */
    private fun mapQuestionItem(q: JsonObject): SseEvent.QuestionAsked.Question =
        SseEvent.QuestionAsked.Question(
            header = q.str("header") ?: "",
            question = q.str("question") ?: "",
            multiple = q.bool("multi_select") ?: false,
            // DSH 自由文本答案允许（AskUserQuestionItem 开放回答）→ custom 恒 true
            custom = true,
            options = (q.arr("options") ?: emptyList()).mapNotNull { el ->
                (el as? JsonObject)?.let {
                    SseEvent.QuestionAsked.Option(
                        label = it.str("label") ?: "",
                        description = it.str("description") ?: "",
                    )
                }
            },
            // item.id 即 answer map 键（稳定 id 回显于答案）——对位 V2 form key
            key = q.str("id"),
        )

    // ============ SessionEvent 内层分派（历史重放与实况同路径） ============

    /**
     * 单 SessionEvent 映射。[envelope] 形如 "{type, seq, time, data, ...}"（历史行
     * 与 session/event 帧 event 字段同构）。DshHistoryFolder 与 mapFrame 共用本入口。
     */
    fun mapSessionEvent(sessionId: String, envelope: JsonObject): List<DshMappedEvent> =
        runCatching { mapSessionEventInner(sessionId, envelope) }.getOrElse { t ->
            AppLogger.w(TAG, "SessionEvent 映射容错降级: " + envelope.str("type") + " – " + t.message)
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        }

    private fun mapSessionEventInner(sessionId: String, envelope: JsonObject): List<DshMappedEvent> {
        val type = envelope.str("type")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val seq = envelope.long("seq") ?: 0L
        val time = envelope.long("time") ?: 0L
        val data = envelope.obj("data") ?: JsonObject(emptyMap())
        return when (type) {
            // ---- Tier 1：transcript ----
            "user/message" -> mapUserMessage(sessionId, seq, time, data)
            "assistant/message" -> mapAssistantMessage(sessionId, seq, time, data)
            "tool/call" -> mapToolCall(sessionId, time, data)
            "tool/result" -> mapToolResult(sessionId, data)
            "assistant/chunk" -> mapChunk(sessionId, time, data)
            // turn/step start → busy（重复 busy 的节流/FSM 去重留给 #276 编排层）
            "turn/start", "step/start" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionStatus(sessionId, SessionStatus.Busy)))
            "turn/end" -> listOf(DshMappedEvent.Sse(SseEvent.SessionIdle(sessionId)))
            "todo/write" -> mapTodoWrite(sessionId, data)
            "session/title" -> mapSessionTitle(sessionId, time, data)

            // ---- Tier 2：会话元数据（具名忽略，#276/后续承接） ----
            // compaction/end → SessionCompacted（#276 后端接口补全）：压缩完成
            // 信号——DSH compact 走 /compact 命令通道受理即回，完成只由本事件
            // 通告；SessionEventHandler.compactedSessions 计数驱动 UI 刷新 +
            // 完成 snackbar（对位 V2 session.compaction.ended 映射先例，刻意不
            // 映射 SessionNext(CompactionEnded)——那类是本地幂等结束信号）。
            "compaction/end" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionCompacted(sessionId = sessionId)))
            "compaction/start", "compaction/summary", "compaction/prune" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMPACTION))
            "goal/change" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.GOAL_CHANGE))
            "subagent/descriptor" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.SUBAGENT_DESCRIPTOR))
            // agent-preset/selected {agentPreset} → SessionAgentPresetChanged：select 成功
            // 回显（非 scoped 重发），折叠进 Session.agentPreset 驱动卡片高亮。
            "agent-preset/selected" -> listOf(
                DshMappedEvent.Sse(
                    SseEvent.SessionAgentPresetChanged(
                        sessionId = sessionId,
                        agentPreset = data.str("agentPreset") ?: "",
                    )
                )
            )

            // ---- 已核实无转录语义的协议伴生事件（§1.5 普查 + §1.7 实测分布） ----
            // 会话开头惯例 preamble（§5 坑位清单）。三 knob（permission/sandbox/approval）
            // 不再 Ignored——映射为 SessionPermissionChanged 驱动权限状态 UI 回显
            // （docs/research/2026-08-31-dsh-permission-sandbox-approval.md §4）。
            "permission/preset" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionPermissionChanged(sessionId = sessionId, preset = data.str("preset"))))
            "sandbox/mode" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionPermissionChanged(sessionId = sessionId, sandboxMode = data.str("mode"))))
            "approval/policy" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionPermissionChanged(sessionId = sessionId, approvalPolicy = data.str("policy"))))
            "plan/mode" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.POLICY_STATE))
            "agent/inbox/spliced" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.INBOX))
            "step/end" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LIFECYCLE_NOISE))
            // llm/retry（实测 3,566 次）——Part.Retry 对位留给后续；不进目录会误伤真实会话
            "llm/retry", "llm/retry-started" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LLM_RETRY))
            "command/run", "command/done" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMMAND))
            // log-only（设计 Tier3 明列）
            "request/header", "request/context", "session/end-seed",
            "web/deepseek-search-llm-request", "schedule/change", "feedback/record",
                -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LOG_ONLY))
            // 工具卡由 tool/call|result 承载；code-dispatch 是渲染伴生事件（实测 ~66,690 次）
            "tool/code-dispatch", "tool/code-dispatch-start" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.CODE_DISPATCH))
            // durable 审批面：实况弹窗由 mux approval/requested|resolved 承载（本组件），
            // 历史重放 asked 会造成重复弹窗——#276 裁决是否补充重放语义
            "approval/asked", "approval/decided" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.APPROVAL_DURABLE))

            // ---- 插件域扩展（known-49 收尾；E2E 实证 llm/failover 曾致整会话拒绝重建） ----
            "llm/failover" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LLM_FAILOVER))
            "session/title-llm-request" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LOG_ONLY))
            "hook/invoked", "hook/result",
            "team/task", "team/member", "team/message/delivered", "team/message/queued",
            "tool-workflow/run-start", "tool-workflow/agent-start",
            "tool-workflow/agent-end", "tool-workflow/run-end" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.PLUGIN_DOMAIN))

            // ---- 未知类型：ignorable 旗标兑现（spec：仅 llm/failover 带，但旗标是权威信号）；
            //      无旗标才拒绝重建（folder 判据） ----
            else -> {
                if (envelope.bool("ignorable") == true) {
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.IGNORABLE_FLAG))
                } else {
                    AppLogger.w(TAG, "未知 SessionEvent 类型（潜在转录语义，拒绝重建判据）: " + type)
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.UNKNOWN_UNIGNORABLE))
                }
            }
        }
    }

    // ============ Tier 1 实现细节 ============

    /**
     * user/message → MessageUpdated + 显式 text part。
     *
     * 不走 V2 的 summary.body 播种路径（handler 会再 seed 一条 summary part，与显式
     * part 双份风险）；source.kind（人类/注入/goal 轮）统一按 user 气泡渲染——注入
     * 轮的差异化展示留给后续。
     */
    private fun mapUserMessage(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val id = messageId(seq)
        val events = mutableListOf(
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.User(id = id, sessionId = sessionId, time = TimeInfo(created = time))
                )
            )
        )
        (data.arr("content") ?: emptyList()).forEachIndexed { i, el ->
            val block = el as? JsonObject ?: return@forEachIndexed
            when (block.str("type")) {
                "text" -> events += DshMappedEvent.Sse(
                    SseEvent.MessagePartUpdated(
                        Part.Text(
                            id = PartIdContract.derive(id, "text", i.toLong()),
                            sessionId = sessionId,
                            messageId = id,
                            text = block.str("text") ?: "",
                            time = Part.Text.Time(start = time, end = time),
                        )
                    )
                )
                else -> AppLogger.w(TAG, "user/message 未支持的内容块类型: " + block.str("type"))
            }
        }
        return events
    }

    /**
     * assistant/message（整装）→ 流式桥拆除 + MessageUpdated + 各内容块 part。
     *
     * - completed = 事件 time（红点水位线，§2.3）；tokens 来自 usage（缺席为 null）；
     * - content 数组下标即块 index（与实况 chunk.index 同一编号域）——part id 实况/
     *   历史对齐；
     * - 整装 part 带终态 time（end 非空）→ mergePart isTerminal 覆盖语义 +
     *   #266 迟到 delta 守卫生效；
     * - MessageRemoved 桥：拆除同 turn/step 的实况流式宿主（live 场景防内容双份；
     *   fold 场景为幂等 no-op）。
     */
    private fun mapAssistantMessage(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val id = messageId(seq)
        val events = mutableListOf<DshMappedEvent>()
        val turn = data.long("turn")
        val step = data.long("step")
        if (turn != null && step != null) {
            events += DshMappedEvent.Sse(SseEvent.MessageRemoved(sessionId, streamingMessageId(turn, step)))
        }
        val usage = data.obj("usage")
        val tokens = usage?.let { u ->
            val input = u.long("inputTokens")?.toInt() ?: 0
            val output = u.long("outputTokens")?.toInt() ?: 0
            Message.Assistant.Tokens(input = input, output = output, total = input + output)
        }
        events += DshMappedEvent.Sse(
            SseEvent.MessageUpdated(
                Message.Assistant(
                    id = id,
                    sessionId = sessionId,
                    time = TimeInfo(created = time, completed = time),
                    parentId = "",
                    tokens = tokens,
                    // DSH interrupted 前缀标记（§1.5）→ finish 语义对位；缺席为 null
                    finish = if (data.bool("interrupted") == true) "interrupted" else null,
                )
            )
        )
        val message = data.obj("message")
        val content = message?.arr("content") ?: emptyList()
        content.forEachIndexed { i, el ->
            val block = el as? JsonObject ?: return@forEachIndexed
            when (block.str("type")) {
                "reasoning" -> events += DshMappedEvent.Sse(
                    SseEvent.MessagePartUpdated(
                        Part.Reasoning(
                            id = PartIdContract.derive(id, "reasoning", i.toLong()),
                            sessionId = sessionId,
                            messageId = id,
                            text = block.str("text") ?: "",
                            time = Part.Reasoning.Time(start = time, end = time),
                        )
                    )
                )
                "text" -> events += DshMappedEvent.Sse(
                    SseEvent.MessagePartUpdated(
                        Part.Text(
                            id = PartIdContract.derive(id, "text", i.toLong()),
                            sessionId = sessionId,
                            messageId = id,
                            text = block.str("text") ?: "",
                            time = Part.Text.Time(start = time, end = time),
                        )
                    )
                )
                // E2E 实证（1192 例）：tool-call/tool-result 块是核心 ContentBlock 的冗余镜像——
                // 工具卡真源 = tool/call|result 事件对（会话 B 实证渲染正常）。静默确认防重复卡。
                "tool-call", "tool-result" -> Unit
                else -> AppLogger.w(TAG, "assistant/message 未支持的内容块: " + block.str("type"))
            }
        }
        return events
    }

    /** tool/call → 工具卡宿主消息（call 键控）+ Pending 工具卡（原始参数串保真）。 */
    private fun mapToolCall(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val callId = data.str("callId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val hostId = toolHostMessageId(callId)
        val rawArgs = data.str("arguments") ?: ""
        // 参数是原始 JSON 串：能解析则同步展开 input map（UI 展示），raw 恒保真
        val parsedInput = runCatching { json.parseToJsonElement(rawArgs) as? JsonObject }
            .getOrNull()?.mapValues { (_, v) -> v } ?: emptyMap()
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.Assistant(
                        id = hostId,
                        sessionId = sessionId,
                        time = TimeInfo(created = time),
                        parentId = "",
                    )
                )
            ),
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Tool(
                        id = callId,
                        sessionId = sessionId,
                        messageId = hostId,
                        callId = callId,
                        tool = data.str("name") ?: "",
                        state = ToolState.Pending(
                            input = parsedInput,
                            raw = rawArgs.takeIf { it.isNotEmpty() },
                        ),
                    )
                )
            ),
        )
    }

    /** tool/result → 同 callId 工具卡终态（Completed/Error；input 由 mergePart 保留）。 */
    private fun mapToolResult(sessionId: String, data: JsonObject): List<DshMappedEvent> {
        val message = data.obj("message")
        val callId = message?.obj("source")?.str("callId")
            ?: (message?.arr("content") ?: emptyList()).firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.str("toolCallId")
            }
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val hostId = toolHostMessageId(callId)
        val errorElem = data["error"] ?: message?.get("error")
        val state = if (errorElem != null && errorElem !is JsonNull) {
            ToolState.Error(error = errorElem.errorText())
        } else {
            ToolState.Completed(output = flattenToolResultOutput(message))
        }
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Tool(
                        id = callId,
                        sessionId = sessionId,
                        messageId = hostId,
                        callId = callId,
                        // 工具名缺席：mergePart Tool 分支保留 existing 名（V2 同策略）
                        tool = "",
                        state = state,
                    )
                )
            )
        )
    }

    /** tool-result 内容块文本展平：content[].content[].text 按换行连接。 */
    private fun flattenToolResultOutput(message: JsonObject?): String {
        val parts = (message?.arr("content") ?: emptyList()).mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            if (block.str("type") != "tool-result") {
                // 容错：非 tool-result 块直接取 text 字段（若有）
                return@mapNotNull block.str("text")
            }
            (block.arr("content") ?: emptyList()).mapNotNull { inner ->
                (inner as? JsonObject)?.str("text")
            }.joinToString("\n").takeIf { it.isNotEmpty() }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * assistant/chunk（实况流式五子型，§1.5 实测分布）。
     *
     * - block-start → MessagePartUpdated（空 part 种子，kind 按 blockType）；
     * - text-delta / reasoning-delta → MessagePartDelta（field 按 chunk.type——与设计
     *   §1.5 定稿一致；kind 推断实际走 partId 契约）；
     * - block-end → Ignored：DSH block-end 不携带文本，而消费端 mergePart 的
     *   isTerminal 覆盖语义假定 incoming 是全量终值（官方 text.ended 契约）——发空
     *   文本终态 part 会清空已流式文本；终态化由 turn/end → SessionIdle →
     *   markSessionIdle 路径承担（偏离任务草案的定点裁决，见报告）；
     * - usage → Ignored（#276 SessionUsage 对位）。
     */
    private fun mapChunk(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val chunk = data.obj("chunk")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val turn = data.long("turn") ?: 0L
        val step = data.long("step") ?: 0L
        val index = chunk.long("index") ?: 0L
        val messageId = streamingMessageId(turn, step)
        return when (chunk.str("type")) {
            "block-start" -> {
                val kind = if (chunk.str("blockType") == "reasoning") "reasoning" else "text"
                val partId = PartIdContract.derive(messageId, kind, index)
                val part = if (kind == "reasoning") {
                    Part.Reasoning(
                        id = partId, sessionId = sessionId, messageId = messageId,
                        text = "", time = Part.Reasoning.Time(start = time),
                    )
                } else {
                    Part.Text(
                        id = partId, sessionId = sessionId, messageId = messageId,
                        text = "", time = Part.Text.Time(start = time),
                    )
                }
                listOf(DshMappedEvent.Sse(SseEvent.MessagePartUpdated(part)))
            }
            "text-delta" -> listOf(
                DshMappedEvent.Sse(
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = PartIdContract.derive(messageId, "text", index),
                        field = "text",
                        delta = chunk.str("text") ?: "",
                    )
                )
            )
            "reasoning-delta" -> listOf(
                DshMappedEvent.Sse(
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = PartIdContract.derive(messageId, "reasoning", index),
                        field = "reasoning",
                        delta = chunk.str("text") ?: "",
                    )
                )
            )
            "block-end" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_BLOCK_END))
            "usage" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_USAGE))
            // E2E 实况情报（spec 五子型之外）：工具调用流式增量与收尾标记——
            // 工具卡终态走 tool/call|result 事件，此处静默。
            "tool-call-delta", "finish" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_LIFECYCLE))
            else -> {
                AppLogger.w(TAG, "未知 chunk 子类型: " + chunk.str("type"))
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.CHUNK_UNKNOWN))
            }
        }
    }

    /** todo/write → TodoUpdated（整快照 last-wins 直配）；DSH 无优先级 → medium。 */
    private fun mapTodoWrite(sessionId: String, data: JsonObject): List<DshMappedEvent> {
        val todos = (data.arr("todos") ?: emptyList()).mapNotNull { el ->
            (el as? JsonObject)?.let {
                SseEvent.TodoUpdated.Todo(
                    content = it.str("content") ?: "",
                    status = it.str("status") ?: "pending",
                    priority = "medium",
                )
            }
        }
        return listOf(DshMappedEvent.Sse(SseEvent.TodoUpdated(sessionId = sessionId, todos = todos)))
    }

    /** session/title → SessionUpdated（title + 事件时刻驱动排序位；其余字段最小占位）。 */
    private fun mapSessionTitle(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> =
        listOf(
            DshMappedEvent.Sse(
                SseEvent.SessionUpdated(
                    Session(
                        id = sessionId,
                        title = data.str("title"),
                        time = Session.Time(created = 0L, updated = time),
                    )
                )
            )
        )

    // ============ JsonObject 安全取值 ============

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    /** 错误载荷转可读文本：对象优先 message，其次 code，最后整体序列化。 */
    private fun JsonObject.errorText(key: String): String {
        val elem = this[key] ?: return key
        return elem.errorText()
    }

    private fun JsonElement.errorText(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonObject -> str("message") ?: str("code") ?: toString()
        else -> toString()
    }
}

/**
 * session/subscribed 帧解码产物——重连对账基线（组件 C DshReconciler 的输入）。
 * 每个已附会话一帧，lastSeq 为开流时服务端已持久化的最高 seq（§1.5）。
 */
data class DshSubscribed(val sessionId: String, val lastSeq: Long)

/**
 * 帧映射三态输出：SseEvent（喂 EventDispatcher）/ 订阅基线（喂对账）/ 忽略（带原因）。
 * [Ignored.reason] == [DshIgnoreReason.UNKNOWN_UNIGNORABLE] 是 DshHistoryFolder
 * 拒绝重建的唯一判据——其余忽略均为已核实无转录语义的具名类型。
 */
sealed class DshMappedEvent {
    data class Sse(val event: SseEvent) : DshMappedEvent()
    data class Subscribed(val value: DshSubscribed) : DshMappedEvent()
    data class Ignored(val reason: String) : DshMappedEvent()
}

/** 忽略原因常量闭集（日志/测试断言用；folder 只认 UNKNOWN_UNIGNORABLE）。 */
object DshIgnoreReason {
    /** 未知 SessionEvent 类型——可能携带未建模的转录语义，folder 据此拒绝重建（§5）。 */
    const val UNKNOWN_UNIGNORABLE = "unknown-unignorable"

    /** 未知帧 method（连接层开放联合容错，非 SessionEvent 面）。 */
    const val FRAME_METHOD = "frame-method"

    /** 载荷缺关键字段 / 畸形——降级不崩。 */
    const val MALFORMED = "malformed-payload"

    /** session/queue 瞬态收件箱整快照（#276 堆积消息域）。 */
    const val QUEUE = "session-queue"

    /** session/jobs 后台任务整快照（#276 ShellJob 近似对表）。 */
    const val JOBS = "session-jobs"

    /** session/projection 投影单元（#276 Misc/SessionNext 辅助态）。 */
    const val PROJECTION = "session-projection"

    /** stream/error 连接层错误（#276 编排处理）。 */
    const val STREAM_ERROR = "stream-error"

    /** host/workspace-* / archived-sessions-changed（oc-beacon 无 Workspace 域）。 */
    const val HOST_WORKSPACE = "host-workspace"

    /** chunk block-end（空载荷终态 part 会清空流式文本——见 mapChunk 注释）。 */
    const val CHUNK_BLOCK_END = "chunk-block-end"

    /** chunk usage（#276 SessionUsage 对位）。 */
    const val CHUNK_USAGE = "chunk-usage"

    /** 未知 chunk 子类型。 */
    const val CHUNK_UNKNOWN = "chunk-unknown"

    /** compaction 族压缩状态（Tier2，后续）。 */
    const val COMPACTION = "compaction"

    /** goal/change（Tier2，后续）。 */
    const val GOAL_CHANGE = "goal-change"

    /** subagent/descriptor 会话子节点（Tier2，后续）。 */
    const val SUBAGENT_DESCRIPTOR = "subagent-descriptor"

    /** 会话 preamble 策略态：plan/mode（permission/sandbox/approval 已映射为权限状态事件）。 */
    const val POLICY_STATE = "policy-state"

    /** agent/inbox/spliced 收件箱拼接。 */
    const val INBOX = "agent-inbox"

    /** step/end 等无独立语义的生命周期噪声（idle 边界是 turn/end）。 */
    const val LIFECYCLE_NOISE = "lifecycle-noise"

    /** llm/retry(-started)——Part.Retry 对位留给后续。 */
    const val LLM_RETRY = "llm-retry"

    /** llm/failover 提供商切换（E2E 实证曾致拒绝重建，2026-08-31 收编）。 */
    const val LLM_FAILOVER = "llm-failover"

    /** 插件域事件（hook/team/tool-workflow——known-49 收尾）。 */
    const val PLUGIN_DOMAIN = "plugin-domain"

    /** 未知类型但信封带 ignorable:true（旗标权威，E2E 后兑现）。 */
    const val IGNORABLE_FLAG = "ignorable-flag"

    /** chunk 工具流式增量/收尾标记（tool-call-delta/finish）。 */
    const val CHUNK_LIFECYCLE = "chunk-lifecycle"

    /** command/run|done。 */
    const val COMMAND = "command"

    /** log-only 事件（设计 Tier3：request/header|context、session/end-seed 等）。 */
    const val LOG_ONLY = "log-only"

    /** tool/code-dispatch(-start) 渲染伴生事件（工具卡由 tool/call|result 承载）。 */
    const val CODE_DISPATCH = "code-dispatch"

    /** durable 审批面 asked/decided（实况面由 mux approval 帧承载）。 */
    const val APPROVAL_DURABLE = "approval-durable"
}
