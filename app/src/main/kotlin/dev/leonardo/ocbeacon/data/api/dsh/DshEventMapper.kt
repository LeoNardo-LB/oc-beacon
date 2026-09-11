package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.DshMessageId
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PartIdContract
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
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
 * （AppLogger.w 记日志不崩）。
 *
 * #391 切片7（DSH 0.1.5 / 会话格式 V3 容错优先）：未知 **SessionEvent 类型** 落
 * [DshIgnoreReason.UNKNOWN_DEGRADED] **具名降级**，不再拒绝重建——词汇演进
 * （V3 的 system/message、assistant/attempt、tool/ptc-dispatch* 等）零成本吸收；
 * 仅结构性违约落 [DshIgnoreReason.STRUCTURAL_VIOLATION] 时才由 DshHistoryFolder
 * 拒绝重建；当前实发射点唯一：user/message 的 surfaceOp.replace 区间越界（end < start）。
 * 乱序 / 种子缺失 两项保留判据位，尚无运行时判据（待信封粒度取证）。
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

    /** 整装消息 id（user/message、assistant/message）。契约唯一权威 = [DshMessageId]
     *（#378 上提 domain——UI 流内归并需反解 seq，依赖方向禁止 UI→data）。
     * #385b：携带 sessionId（DSH seq 仅会话内唯一，裸 id 跨会话碰撞——见契约注释）。 */
    fun messageId(sessionId: String, seq: Long): String = DshMessageId.id(sessionId, seq)

    /**
     * #312⑤ 反解：整装消息 id "seq-{seq}" → seq（fork 轮尾锚点上
     * wire ——session.fork atSeq 契约）；其余形态 → null（安全降级为无锚点）。
     * 委派 [DshMessageId.seqOf]（契约共源）。
     */
    fun seqOf(messageId: String?): Long? = DshMessageId.seqOf(messageId)

    /** 实况流式宿主消息 id（assistant/chunk 族）。 */
    fun streamingMessageId(turn: Long, step: Long): String = "dsh-t" + turn + "s" + step

    /** 工具卡宿主消息 id（tool/call 创建、tool/result 汇合）。 */
    fun toolHostMessageId(callId: String): String = "dsh-call-" + callId

    /**
     * PTC 子调用 id → 根工具卡 callId。`tool/ptc-dispatch-start` 的 subCallId 形如
     * `call_x:ptc:1`（实况 seq204），而其 rootCallId/parentCallId = `call_x`——根
     * `tool/call` 宿主按根 id 建（`dsh-call-call_x`）。deliverables/presented 只带
     * subCallId，故须剥 `:ptc:<n>` 才能命中既有宿主；非 PTC 调用原样返回。
     */
    fun rootCallId(callId: String): String {
        val marker = callId.indexOf(":ptc:")
        return if (marker > 0) callId.substring(0, marker) else callId
    }

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
            // 连接层信号：开流基线（对账起点，组件 C 输入）+ jobs/队列清空重推。
            // 官方 client.js:8314：subscribed 帧对 jobsBySession 删键——重连基线
            // 先行清空，服务器随后重推 session/jobs 整快照（对齐 A 状态机）；
            // queueMirror.reset()（官方 client.js:7472）同帧判脏——QueueDock 同理
            // 清空待服务器重推 session/queue 整快照。
            val sid = payload.str("sessionId")
            val lastSeq = payload.long("lastSeq")
            if (sid == null || lastSeq == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Subscribed(DshSubscribed(sid, lastSeq)),
                DshMappedEvent.Sse(SseEvent.JobsSnapshot(sessionId = sid, jobs = emptyList())),
                DshMappedEvent.Sse(SseEvent.QueueSnapshot(sessionId = sid, items = emptyList())),
            )
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

        // #285：命令注册表变更（全局未过滤通知，cordis 契约 "unfiltered registry
        // notification"）——非会话域，消费端带各自 sessionId 重取 commands/list。
        // #296 勘误：服务端对此类转发事件**只以 host/remote-event 包装发送**
        // （api-proxy.ts:3626-3632，白名单见 mapForwardedRemoteEvent）——裸帧
        // 实际不命中，保留作防御（若服务端未来直发裸帧）。
        "commands/change" -> listOf(DshMappedEvent.Sse(SseEvent.CommandsChanged))

        // 后台任务整快照（A：session/jobs → JobsSnapshot，last-wins 整替换）
        "session/jobs" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.JobsSnapshot(
                        sessionId = sid,
                        jobs = (payload.arr("jobs") ?: emptyList()).mapNotNull { el ->
                            (el as? JsonObject)?.let { mapJobView(it) }
                        },
                    )
                )
            )
        }

        // 投影单元（B：session/projection → tokenUsage / subagentTiming 帧驱动更新）
        "session/projection" -> {
            val sid = payload.str("sessionId")
            val key = payload.str("key")
            if (sid == null || key == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else when (key) {
                "tokenUsage" -> {
                    val value = payload.obj("value")
                    if (value == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    else listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionTokenUsageChanged(sessionId = sid, tokenUsage = mapTokenUsage(value))
                        )
                    )
                }
                "subagentTiming" -> {
                    val value = payload.obj("value")
                    if (value == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    else listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionSubagentTimingChanged(sessionId = sid, timing = mapSubagentTiming(value))
                        )
                    )
                }
                "goal" -> {
                    // 投影值可为 null（首建前/clear tombstone 的 whole value）——非畸形
                    when (val value = payload["value"]) {
                        is JsonNull -> listOf(
                            DshMappedEvent.Sse(SseEvent.SessionGoalChanged(sessionId = sid, goal = null))
                        )
                        is JsonObject -> listOf(
                            DshMappedEvent.Sse(
                                SseEvent.SessionGoalChanged(sessionId = sid, goal = mapGoalProjection(value))
                            )
                        )
                        else -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    }
                }
                // #354 持久修复（2026-09-08 R3 复验发现「重启后回落—」）：create 携带的
                // 预设不进会话事件日志（仅 select RPC append），但 **session/control
                // 基线 projections 每会话携带 agentPreset**（投影面）且变更实时推
                // projection 帧——此前此 key 落 Ignored → 重启后无来源。映射到既有
                // SessionAgentPresetChanged（下游 handler/store/详情页同链复用）。
                "agentPreset" -> when (val value = payload["value"]) {
                    is JsonPrimitive -> listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionAgentPresetChanged(sessionId = sid, agentPreset = value.content)
                        )
                    )
                    else -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                }
                "contextPressure" -> {
                    val value = payload.obj("value")
                    if (value == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    else listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionContextPressureChanged(
                                sessionId = sid,
                                pressure = dev.leonardo.ocbeacon.domain.model.DshContextPressure(
                                    pressureTokens = value.long("pressureTokens"),
                                    projectedTokens = value.long("projectedTokens"),
                                    contextWindow = value.long("contextWindow"),
                                ),
                            )
                        )
                    )
                }
                "contextBreakdown" -> {
                    val value = payload.obj("value")
                    if (value == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    else listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionContextBreakdownChanged(
                                sessionId = sid,
                                breakdown = dev.leonardo.ocbeacon.domain.model.DshContextBreakdown(
                                    systemTokens = value.long("systemTokens") ?: 0L,
                                    toolsTokens = value.long("toolsTokens") ?: 0L,
                                    messageTokens = value.long("messageTokens") ?: 0L,
                                ),
                            )
                        )
                    )
                }
                "sessionStats" -> {
                    val value = payload.obj("value")
                    if (value == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    else listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionStatsChanged(
                                sessionId = sid,
                                stats = dev.leonardo.ocbeacon.domain.model.DshSessionStats(
                                    turns = value.long("turns") ?: 0L,
                                    steps = value.long("steps") ?: 0L,
                                    llmMs = value.long("llmMs") ?: 0L,
                                    toolMs = value.long("toolMs") ?: 0L,
                                    ttftMs = value.long("ttftMs") ?: 0L,
                                    ttftSteps = value.long("ttftSteps") ?: 0L,
                                    decodeMs = value.long("decodeMs") ?: 0L,
                                    decodeTokens = value.long("decodeTokens") ?: 0L,
                                ),
                            )
                        )
                    )
                }
                "permissions" -> {
                    // #283-a2：整值帧（含部署预设表 options）——与 session.list 基线
                    // 同形（DshSessionMapper.parsePermissionsValue 单源解析）；
                    // JsonNull = clear tombstone（同 goal 键语义）。
                    when (val value = payload["value"]) {
                        is JsonNull -> listOf(
                            DshMappedEvent.Sse(SseEvent.SessionPermissionsChanged(sessionId = sid, permissions = null))
                        )
                        is JsonObject -> listOf(
                            DshMappedEvent.Sse(
                                SseEvent.SessionPermissionsChanged(
                                    sessionId = sid,
                                    permissions = DshSessionMapper.parsePermissionsValue(value),
                                )
                            )
                        )
                        else -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    }
                }
                "plan" -> {
                    // #310③：客户端裁剪视图 {active, pending}（dsh-plan-mode
                    // index.js:115-124 stateSchema crop——完整 state 还有 wanted/
                    // running/activeAtLastHeader，carrier 只发裁剪视图）；JsonNull
                    // 容错为 clear（同 goal/permissions 键语义）。
                    when (val value = payload["value"]) {
                        is JsonNull -> listOf(
                            DshMappedEvent.Sse(SseEvent.SessionPlanChanged(sessionId = sid, plan = null))
                        )
                        is JsonObject -> listOf(
                            DshMappedEvent.Sse(
                                SseEvent.SessionPlanChanged(
                                    sessionId = sid,
                                    plan = dev.leonardo.ocbeacon.domain.model.DshPlanProjection(
                                        active = value.bool("active") ?: false,
                                        pending = value.bool("pending") ?: false,
                                    ),
                                )
                            )
                        )
                        else -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                    }
                }
                // 其余投影键（title…）：本任务不消费
                else -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.PROJECTION))
            }
        }

        // 排队收件箱整快照（QueueDock）：{sessionId, items:[{id, placement, message:{content}}]}
        // → QueueSnapshot（last-wins 整替换；mapQueueItem 归一化 preview/text）。
        "session/queue" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.QueueSnapshot(
                        sessionId = sid,
                        items = (payload.arr("items") ?: emptyList()).mapNotNull { el ->
                            (el as? JsonObject)?.let { mapQueueItem(it) }
                        },
                    )
                )
            )
        }
        // workspace/follow 基线（0.1.2 mux 合成帧；#311 Task1）——items 逐行映射
        // Workspace（畸形行丢弃），archivedSessionIds 透传（集合替换式）。
        "workspace/baseline" -> {
            val items = payload["items"]
            val archivedIds = payload["archivedSessionIds"]
            if (items !is JsonArray || archivedIds !is JsonArray) {
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            } else {
                listOf(
                    DshMappedEvent.Sse(
                        SseEvent.WorkspaceSnapshotChanged(
                            workspaces = items.mapNotNull { el ->
                                (el as? JsonObject)?.let(::mapWorkspaceView)
                            },
                            archivedSessionIds = archivedIds.mapNotNull { it.text() },
                        )
                    )
                )
            }
        }

        // workspace/follow 归档增量（{type:'archived'} 合成帧；#311 Task1）——
        // archivedSessionIds 是完整新集合（集合替换式，契约 ①-a）。
        "workspace/archived" -> {
            val ids = payload["archivedSessionIds"]
            if (ids !is JsonArray) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(SseEvent.WorkspaceArchivedChanged(archivedSessionIds = ids.mapNotNull { it.text() }))
            )
        }

        // workspace/follow 注册表行增量（{type:'upsert'} 合成帧；#311 Task3）——
        // workspace 携带整行 WorkspaceView（title/sessionIds 实时消费面）。
        "workspace/upsert" -> {
            val ws = payload["workspace"]
            if (ws !is JsonObject) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOfNotNull(
                mapWorkspaceView(ws)?.let { mapped ->
                    DshMappedEvent.Sse(SseEvent.WorkspaceUpserted(workspace = mapped))
                } ?: DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED),
            )
        }

        // workspace/follow 注册表行删除（{type:'remove'} 合成帧；#330）——
        // workspaceId 删行（store applyRemove；其余行与 archived 集合保持）。
        "workspace/remove" -> {
            val id = payload.str("workspaceId")
            if (id == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(DshMappedEvent.Sse(SseEvent.WorkspaceRemoved(workspaceId = id)))
        }

        // workspace/follow 注册表序变更（{type:'order'} 合成帧；#330）——
        // workspaceIds 是完整新序（服务器 publish 全量数组；store applyOrder 重排）。
        "workspace/order" -> {
            val ids = payload.arr("workspaceIds")
            if (ids == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(SseEvent.WorkspaceOrderChanged(workspaceIds = ids.mapNotNull { it.text() }))
            )
        }

        "stream/error" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.STREAM_ERROR))

        "host/session-added" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.SessionCreated(
                        // 最小构造（任务裁决）：cwd→directory、parentSessionId→parentId；
                        // #331：added 摘要带 updatedAt（mux 合成帧透传）→ time.updated
                        // 采真值（缺席保持 epoch0 占位，#276 由 session.list 再基线）。
                        // #333：parentId 仅 origin=subagent 时置（app 侧 parentId=
                        // 「durable subagent 父」语义；fork 子会话 parentSessionId 在
                        // 但 origin 缺席，是普通会话——置 parentId 会被列表过滤 +
                        // 发送误路由 subagents/prompt）。
                        Session(
                            id = sid,
                            directory = payload.str("cwd") ?: "",
                            parentId = payload.str("parentSessionId")
                                ?.takeIf { payload.str("origin") == "subagent" },
                            time = Session.Time(created = 0L, updated = payload.long("updatedAt") ?: 0L),
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

        // A2(2026-09-06 全量 E2E):api-session/activity 合成帧(服务器仅在
        // user/message·source=user 时发射,时间=消息时刻,与列表 updatedAt=
        // max(createdAt,lastPromptAt) 语义一致)——web 以 updatedAt 单调合并即时
        // 重排列表;映射最小 SessionUpdated(title 缺席由 defendSessionReplacement
        // 回填缓存,updated 经 max 合并防历史重放回拉排序位)。
        "host/session-activity" -> {
            val sid = payload.str("sessionId")
            val activityAt = payload.long("updatedAt")
            if (sid == null || activityAt == null || activityAt <= 0L) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else listOf(
                DshMappedEvent.Sse(
                    SseEvent.SessionUpdated(
                        Session(id = sid, time = Session.Time(created = 0L, updated = activityAt)),
                    ),
                ),
            )
        }

        "host/agent-error" -> {
            val sid = payload.str("sessionId")
            if (sid == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            // 2026-08-31：DSH 真实现（events.schema.js:72）载荷键是 message（字符串）；
            // 旧形态是 error 对象——逐键回退防键失配丢文本（欠费/provider 拒绝等错误被吞）。
            else listOf(DshMappedEvent.Sse(SseEvent.SessionError(sessionId = sid, error = payload.errorText("message", "error"))))
        }

        // #296：转发事件解包——服务端把白名单 cordis 事件（commands/change、
        // agent-preset/selected 等 11 个，API_REMOTE_FORWARDED_EVENTS）一律包在
        // 本帧（zod: {type, event, args:[位置参数]}）经 events.host 流发送；官方
        // 客户端 client.js:10518 host/remote-event → $dispatch(frame.event, frame.args)。
        // 未解包前裸 commands/change 分支是死代码——#285 命令注册表刷新链路不触发。
        "host/remote-event" -> {
            val forwarded = payload.str("event")
            if (forwarded == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
            else mapForwardedRemoteEvent(forwarded, payload.arr("args") ?: emptyList())
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

    /**
     * 转发事件分派（#296）。[args] 是 cordis 事件的位置参数列表（Host 原样转发、
     * 无投影——README「no projection, no redaction, no renaming」）；只解包有
     * 消费端的事件，白名单其余项（credentials/updated、cordis 转发六事件、
     * llm/adapters-updated、settings/document-updated）无对应 UI/域 → Ignored 留痕。
     */
    private fun mapForwardedRemoteEvent(event: String, args: List<JsonElement>): List<DshMappedEvent> =
        when (event) {
            // 命令注册表变更（void 事件，args 恒空）——与裸帧分支同语义，
            // #285 消费端（MiscEventHandler.commandsChanged）重取 commands/list。
            "commands/change" -> listOf(DshMappedEvent.Sse(SseEvent.CommandsChanged))
            // (sessionId, agentPreset) 位置参数——与 SessionEvent 内层分支同语义
            // （host 流双保险：select 变更即使不经会话事件面也驱动卡片高亮）。
            "agent-preset/selected" -> {
                val sid = args.elementAtOrNull(0)?.text()
                val preset = args.elementAtOrNull(1)?.text()
                if (sid == null || preset == null) {
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                } else {
                    listOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionAgentPresetChanged(sessionId = sid, agentPreset = preset)
                        )
                    )
                }
            }
            else -> {
                AppLogger.d(TAG, "转发事件无消费端（#296 白名单其余项）: event=" + event)
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.REMOTE_EVENT))
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
            // #310③：detail（问题描述正文，plan-review=计划全文）与 intent（呈现
            // 意图，只改呈现不改协议）透传；均为单字键，无 camel/snake 歧义。
            detail = q.str("detail"),
            intent = q.obj("intent")?.let {
                SseEvent.QuestionAsked.Intent(
                    kind = it.str("kind"),
                    approve = it.str("approve"),
                )
            },
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
            "tool/result" -> mapToolResult(sessionId, time, data)
            "assistant/chunk" -> mapChunk(sessionId, time, data)
            // turn/step start → busy（重复 busy 的节流/FSM 去重留给 #276 编排层）
            "turn/start", "step/start" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionStatus(sessionId, SessionStatus.Busy)))
            // #309 批1⑤：llm/retry（dsh-llm-retry :100-122 载荷 {retryId,turn,step,
            // retry(次数),maxRetries?,delayMs,failure{message,code?}}）→
            // SessionStatus.Retry（next=事件时刻+delayMs；RetryBanner 全链现成）；
            // llm/retry-started（延迟到期实际重试）→ Busy（横幅退场、工作恢复）。
            // 历史重放同路径：其后必有 turn/end（SessionIdle）→ 终态不残留。
            "llm/retry" -> {
                val attempt = data.long("retry")
                if (attempt == null) listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
                else listOf(
                    DshMappedEvent.Sse(
                        SseEvent.SessionStatus(
                            sessionId,
                            SessionStatus.Retry(
                                attempt = attempt.toInt(),
                                message = data.obj("failure")?.str("message") ?: "",
                                next = time + (data.long("delayMs") ?: 0L),
                            ),
                        )
                    )
                )
            }
            "llm/retry-started" ->
                listOf(DshMappedEvent.Sse(SseEvent.SessionStatus(sessionId, SessionStatus.Busy)))
            // time 透传（#294）：重放的历史 turn/end 携带原始时刻供通知层陈旧过滤。
            // #309 批1⑤：TurnEndReason（dsh-session types.d.ts:145-165）——error →
            // SessionError（D1③ 转录内错误行+sendMessage 清卡链现成）；max-tokens →
            // TurnMaxTokens（通知卡带继续钮）；此前 reason 整体丢弃。
            "turn/end" -> {
                val idle = DshMappedEvent.Sse(SseEvent.SessionIdle(sessionId, time.takeIf { it > 0 }))
                val reason = data.obj("reason")
                when (reason?.str("kind")) {
                    "error" -> listOf(
                        idle,
                        DshMappedEvent.Sse(
                            // #339：携带原始时刻（同 #294 turn/end 透传）——回放的
                            // 历史错误轮据此被通知层陈旧过滤（「错误·hi」×7-8 重发实证）。
                            SseEvent.SessionError(
                                sessionId = sessionId,
                                error = reason.obj("error")?.str("message") ?: "turn error",
                                time = time.takeIf { it > 0 },
                            )
                        ),
                    )
                    "max-tokens" -> listOf(
                        idle,
                        DshMappedEvent.Sse(SseEvent.TurnMaxTokens(sessionId = sessionId, turn = data.long("turn") ?: 0L)),
                    )
                    else -> listOf(idle)
                }
            }
            "todo/write" -> mapTodoWrite(sessionId, data)
            "session/title" -> mapSessionTitle(sessionId, time, data)

            // ---- Tier 2：会话元数据 ----
            // compaction/end → SessionCompacted（#276 后端接口补全）：压缩完成
            // 信号——SessionEventHandler.compactedSessions 计数驱动 UI 刷新 +
            // 完成 snackbar；banner 终结走 dispatcher 跨 handler endCompaction。
            // #309 批1：失败压缩（error 非空，dsh-compaction-basic :463）加发
            // CompactionEnded(error)——对位 #219 失败 snackbar 通道。
            // #378：加发转录实体 CompactionFinished（compactionId 配对——流内
            // 压缩 box 终态化；实况/历史同事件，幂等重建）。
            "compaction/end" -> {
                val events = mutableListOf(
                    DshMappedEvent.Sse(SseEvent.SessionCompacted(sessionId = sessionId))
                )
                val err = data.str("error")?.takeIf { it.isNotBlank() }
                err?.let {
                    events += DshMappedEvent.Sse(
                        SseEvent.SessionNext(
                            SessionNextEvent.CompactionEnded(sessionId = sessionId, messageId = "", error = err)
                        )
                    )
                }
                compactionIdOf(data)?.let { cid ->
                    events += DshMappedEvent.Sse(
                        SseEvent.CompactionFinished(
                            sessionId = sessionId, compactionId = cid, error = err, seq = seq, time = time,
                        )
                    )
                }
                events
            }
            // #309 批1：压缩呈现接线——CompactionCard 进行中双态 UI 现成，此前
            // Ignored 未接。#378：加发转录实体 CompactionStarted（流内 box 建卡；
            // banner 事件保留——现状零回归，Phase C 随 box 全验后再回收）。
            "compaction/start" -> {
                val events = mutableListOf(
                    DshMappedEvent.Sse(
                        SseEvent.SessionNext(
                            SessionNextEvent.CompactionStarted(sessionId = sessionId, messageId = "", reason = "")
                        )
                    )
                )
                compactionIdOf(data)?.let { cid ->
                    events += DshMappedEvent.Sse(
                        SseEvent.CompactionStarted(
                            sessionId = sessionId,
                            compactionId = cid,
                            sourceCommandId = data.str("sourceCommandId")?.takeIf { it.isNotBlank() },
                            seq = seq,
                            time = time,
                        )
                    )
                }
                events
            }
            // #378 勘误（活体取证 journal 378-380-wire §五.2）：wire 的 summary 是
            // ContentBlock[]（[{type:"text",text:…}…]），原 data.str("summary") 恒
            // null → 静默 Ignored——摘要从未到达任何消费面（banner delta 同病已死）。
            // 文本块拼接为全文（单帧到达，非流式增量）；banner delta 与转录实体
            // CompactionSummary 双发。
            "compaction/summary" -> {
                val text = contentBlocksText(data["summary"])
                if (text == null) {
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMPACTION))
                } else {
                    val events = mutableListOf(
                        DshMappedEvent.Sse(
                            SseEvent.SessionNext(
                                SessionNextEvent.CompactionDelta(sessionId = sessionId, messageId = "", delta = text)
                            )
                        )
                    )
                    compactionIdOf(data)?.let { cid ->
                        events += DshMappedEvent.Sse(
                            SseEvent.CompactionSummary(
                                sessionId = sessionId,
                                compactionId = cid,
                                sourceCommandId = data.str("sourceCommandId")?.takeIf { it.isNotBlank() },
                                summaryText = text,
                                seq = seq,
                                time = time,
                            )
                        )
                    }
                    // 2026-09-09 三层根修（复验 A 二轮插桩+原始 journal 实证）：
                    // wire 的折叠权威区间在 **summary 事件的 data.shadowedRange**
                    // （{"start":7,"end":204973}，实录 seq-205955）——user/message
                    // 行的 surfaceOp 是信封级字符串（"append"），原 mapUserMessage 的
                    // data.obj("surfaceOp").op=="replace" 判定从未命中 →
                    // SurfaceRangeReplaced 全程休眠（台账恒空、Room 幽灵行只靠
                    // prefetch 对账兜底）。此处按 shadowedRange 派生发射——实况/
                    // 历史同路径（summary 在转录卡族内，页回放同发）。
                    shadowedRangeOf(data, sessionId, seq, time)?.let { events += it }
                    events
                }
            }
            // prune = 无摘要的纯裁剪（shadowedRange 计价事件）：同样按自身
            // data.shadowedRange 派生 SurfaceRangeReplaced（三层根修同源）；
            // 无有效区间的 prune 维持 Ignored（计价事件无转录语义）。
            "compaction/prune" ->
                shadowedRangeOf(data, sessionId, seq, time)?.let { listOf(it) }
                    ?: listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMPACTION))
            // goal/change → SessionGoalChanged（whole-value last-wins；clear tombstone → null）。
            // 历史折叠与实况共用本入口（DshHistoryFolder 可折叠）。
            "goal/change" -> {
                val operation = data.str("operation")
                val projection = if (operation == "clear") {
                    null
                } else {
                    mapGoalProjection(data)
                }
                listOf(DshMappedEvent.Sse(SseEvent.SessionGoalChanged(sessionId = sessionId, goal = projection)))
            }
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
            // #323：斜杠命令执行反馈行——转录 log-only 事件（dsh-commands 契约：run
            // {commandId,name,args?,source} 先于 handler、done {commandId,kind,text?,
            // sourceEventSeq?} 结算后；commandId 配对、直追加无轮包裹）。真实转录
            // 事件（非历史行忽略词汇）——历史重放同路径渲染（DshHistoryFolder 共用
            // 本入口）；畸形（缺 commandId）具名 MALFORMED，绝不落 STRUCTURAL_VIOLATION
            //（#327 历史行防御纪律：不得触发整会话拒绝重建）。
            "command/run" -> mapCommandRun(sessionId, seq, time, data)
            "command/done" -> mapCommandDone(sessionId, seq, time, data)
            // log-only（设计 Tier3 明列）
            "request/header", "request/context", "session/end-seed",
            "web/deepseek-search-llm-request", "schedule/change", "feedback/record",
            // #310① A8轮3（2026-09-05）：model/selection 与 subagent/model-selection-policy
            // 是服务器 known-event-types 词汇内声明的 log-only 事件（"Log-only: it
            // never enters derived model history"——子会话 journal 在首个模型请求前
            // 必写后者，普通会话常见前者）。缺席折叠词汇曾使 session/page 整页返回后
            // fold 全量拒绝重建（listMessages msgs=0）——重入子会话转录恒空、status
            // check 交换不持久（a8r2.log "history fold refused rebuild …" 135+451 次）。
            "model/selection", "subagent/model-selection-policy",
                -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LOG_ONLY))
            // #349（2026-09-07 真机实证）：subagent 族 code-dispatch 升格为子代理卡
            // 真源——DSH wire 上子代理派发被 run_code 包裹（tool/call 名恒=run_code，
            // 子会话 id 不在 tool/call|result 的结构化字段），childSessionId 仅存于
            // 两处：code-dispatch 回执（bg："started subagent <uuid>"）与根
            // tool/result 信封（fg：{kind:"foreground",runId,output}）。其余内层工具
            // （bash/read/ask_user_question…，实测 ~66,690 次）维持忽略——run_code
            // 根卡已承载，平铺会双份。
            "tool/code-dispatch-start" -> mapCodeDispatchStart(sessionId, time, data)
            "tool/code-dispatch" -> mapCodeDispatch(sessionId, time, data)
            // #391 切片7：DSH 0.1.5 / 会话格式 V3 —— 子代理派发改名 code-dispatch → ptc-dispatch
            //（#349 子代理卡真源不变，复用同一映射）
            "tool/ptc-dispatch-start" -> mapCodeDispatchStart(sessionId, time, data)
            "tool/ptc-dispatch" -> mapCodeDispatch(sessionId, time, data)
            // V3 系统/插件上下文消息：渲染为注入类精简卡（#398 步骤3；载荷见
            // docs/research/2026-09-11-dsh-v3-event-payloads.md）。
            "system/message" -> mapSystemMessage(sessionId, seq, time, data)
            // #398：present 工具的服务器权威交付载荷 → 工具卡宿主上的 Part.Deliverables
            //（TurnDeliverables fold 汇入 turn 尾产出文件行；web 同源事件语义见
            // dsh-client-ui-deliverables/lib/client.js selectDeliverables）。
            "deliverables/presented" -> mapDeliverablesPresented(sessionId, time, data)
            // 其余 V3 新增词汇：仍具名降级（不拒绝重建）。
            // - assistant/attempt：瞬态尝试记录（实测 692 例中 681 例随后
            //   llm/retry，逐条渲染会刷屏；终态失败走 turn/end 或 stream/error）。
            // - subagent/catalog：**双源裁定——不消费**。载荷仅
            //   {version,childId,childCreatedAt,mode,label}（单条，缺 activity/
            //   hasChildren/parentAvailable），而目录权威面是 subagents/list RPC
            //   整帧（SubagentApi.subagentCatalog / SubagentCatalogEntry）；
            //   app 侧 SubagentModeTracker 已按 sid+parentId 懒加载该整帧。
            //   0.1.5 全量 web 客户端插件亦无本事件消费点（client-connection 仅
            //   列词汇）。再落一处本地目录即双源，故维持具名降级。
            // - feedback/message-put|delete：本机 29 归档 0 样本，待新会话取证
            //   （App 已有 feedback 端口，事件侧补映射须先有真实载荷）。
            "assistant/attempt",
            "feedback/message-put", "feedback/message-delete",
            "subagent/catalog" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.SESSION_FORMAT_V3))
            // durable 审批面：实况弹窗由 mux approval/requested|resolved 承载（本组件），
            // 历史重放 asked 会造成重复弹窗——#276 裁决是否补充重放语义
            "approval/asked", "approval/decided" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.APPROVAL_DURABLE))

            // ---- 插件域扩展（known-49 收尾；E2E 实证 llm/failover 曾致整会话拒绝重建） ----
            "llm/failover" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LLM_FAILOVER))
            "session/title-llm-request" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.LOG_ONLY))
            "hook/invoked", "hook/result",
            "team/task", "team/member", "team/message/delivered", "team/message/queued" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.PLUGIN_DOMAIN))
            // 2026-09-01（Task 3b 卡片缺口）：workflow-run 降级卡——run-start/run-end
            // 映射为 synthetic 任务信封（同 runId 同宿主消息 id → 原位更新：running →
            // completed/error 单卡）；agent-start/end 是阶段明细（workflow 阶段卡
            // 后续增强），维持 Ignored 防逐成员刷卡。
            "tool-workflow/run-start" -> mapWorkflowRunStart(sessionId, time, data)
            "tool-workflow/run-end" -> mapWorkflowRunEnd(sessionId, time, data)
            "tool-workflow/agent-start", "tool-workflow/agent-end" ->
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.WORKFLOW_AGENT))

            // ---- Mux 帧类型混入历史行（B.4 防御）：session/projection|jobs|queue、
            //      stream/error 是 WS 帧面而非 SessionEvent——历史重放/翻页若出现
            //      这些 type 行，按已知可忽略折叠（不拒绝重建）。 ----
            "session/projection" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.PROJECTION))
            "session/jobs" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.JOBS))
            "session/queue" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.QUEUE))
            "stream/error" -> listOf(DshMappedEvent.Ignored(DshIgnoreReason.STREAM_ERROR))

            // ---- 未知类型：ignorable 旗标兑现（spec：仅 llm/failover 带，但旗标是权威信号）；
            //      无旗标才拒绝重建（folder 判据） ----
            else -> {
                if (envelope.bool("ignorable") == true) {
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.IGNORABLE_FLAG))
                } else {
                    // #391 切片7 容错优先：未知词汇**具名降级 + 日志遥测**，不拒绝重建
                    AppLogger.w(TAG, "未知 SessionEvent 类型（已具名降级，不拒绝重建）: " + type)
                    listOf(DshMappedEvent.Ignored(DshIgnoreReason.UNKNOWN_DEGRADED))
                }
            }
        }
    }

    // ============ Tier 1 实现细节 ============

    /**
     * user/message → MessageUpdated + 显式 text part。
     *
     * 不走 V2 的 summary.body 播种路径（handler 会再 seed 一条 summary part，与显式
     * part 双份风险）。#385（2026-09-10 用户裁决）：注入类消息（source.kind≠user——
     * agent-instructions/skill-catalog/plugin 等宿主上下文注入）透传 injectionKind，
     * UI 按精简折叠卡渲染（对齐 DSH Web；此前统一按 user 气泡渲染成文本墙——演示①实测）。
     */
    private fun mapUserMessage(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val id = messageId(sessionId, seq)
        val injectionKind = data.obj("source")?.str("kind")
            ?.takeIf { it.isNotBlank() && it != "user" }
        val events = mutableListOf<DshMappedEvent>(
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.User(
                        id = id,
                        sessionId = sessionId,
                        time = TimeInfo(created = time),
                        injectionKind = injectionKind,
                    )
                )
            )
        )
        // #356 echo→持久原子换装：RPC 提交的持久回显（source=user-rpc.rpccdId，
        // MessageSourceMap 契约）补发 pending-<rpcId> 拆除——本地 echo 气泡与
        // 持久消息同批到达同批折叠（handleMessageRemoved 幂等：echo 不在为 no-op，
        // 历史/重放路径天然安全）。
        data.obj("source")?.str("rpcId")?.takeIf { it.isNotBlank() }?.let { rpcId ->
            events += DshMappedEvent.Sse(SseEvent.MessageRemoved(sessionId, "pending-$rpcId"))
        }
        // #378 转录实体接线（压缩摘要表面载体，实录 seq-5392）：
        // - source.compactionId → CompactionSurfaceBound——摘要的 user/message
        //   载体与 CompactionEntry 绑定（UI 抑制原气泡、由压缩 box 承载）；
        // - surfaceOp.op="replace" → SurfaceRangeReplaced——被遮蔽旧消息折叠的
        //   权威指令（MessageEventHandler 台账消费，实况/历史同事件）。
        data.obj("source")?.str("compactionId")?.takeIf { it.isNotBlank() }?.let { cid ->
            events += DshMappedEvent.Sse(
                SseEvent.CompactionSurfaceBound(
                    sessionId = sessionId, compactionId = cid, messageId = id, seq = seq, time = time,
                )
            )
        }
        data.obj("surfaceOp")?.takeIf { it.str("op") == "replace" }?.let { op ->
            // #391 切片7：V3 信封级替换改名 {startSeq,endSeq}——双读兼容（旧 start/end
            // 优先回落）。注意 compaction shadowedRange 的 start/end 保持不变（另一处）。
            val start = op.long("startSeq") ?: op.long("start")
            val end = op.long("endSeq") ?: op.long("end")
            when {
                start == null || end == null ->
                    AppLogger.w(TAG, "user/message surfaceOp.replace 残缺（start=$start end=$end），忽略折叠指令")
                end < start -> {
                    // surfaceOp 越界（end < start）是结构性违约：历史折叠遇此放弃本次重建，
                    // 而非产出被截断的转录（#391 切片7 拒绝重建判据的实发射点）。
                    AppLogger.w(TAG, "user/message surfaceOp.replace 越界（start=$start end=$end），标记结构性违约")
                    events += DshMappedEvent.Ignored(DshIgnoreReason.STRUCTURAL_VIOLATION)
                }
                else -> events += DshMappedEvent.Sse(
                    SseEvent.SurfaceRangeReplaced(
                        sessionId = sessionId, startSeq = start, endSeq = end,
                        byMessageId = id, seq = seq, time = time,
                    )
                )
            }
        }
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
                // #398（DSH 0.1.5/V3 实况取证）：V3 user/message 可载 reasoning 块——
                // 对齐 assistant/message 映射为 Part.Reasoning（此前落 else 整块丢弃）。
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
                // tool-call/tool-result 块是工具卡真源（tool/call|result 事件对）的冗余镜像，
                // 静默确认防重复卡（同 assistant/message 先例）。
                "tool-call", "tool-result" -> Unit
                // 2026-09-01（Task 3c 卡片缺口）：file/image ContentBlock → Part.File
                //（实况日志 829 例 user/message image 块此前被整块丢弃；图片渲染走既有
                // Part.File 链——DSH attachment 字节拉取留待 session.attachment 接线）。
                "file", "image" -> events += mapFileBlock(sessionId, id, i, block)
                else -> AppLogger.w(TAG, "user/message 未支持的内容块类型: " + block.str("type"))
            }
        }
        return events
    }

    /**
     * system/message（V3）→ 注入类消息（EventCard 精简折叠卡，对齐 DSH Web）。
     *
     * 实况载荷（docs/research/2026-09-11-dsh-v3-event-payloads.md）：
     * data.message{id,role=system,source{kind,plugin},content[]}。
     * content 为空时整条不产事件（UI 对无文本注入消息本就跳过，避免噪声）；
     * injectionKind 透传 source.kind（plugin → 既有 Plugin 标签），工具/推理块不渲染。
     */
    private fun mapSystemMessage(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val message = data.obj("message") ?: return emptyList()
        val content = message.arr("content") ?: emptyList()
        if (content.isEmpty()) return emptyList()
        val wireId = message.str("id")
        val id = if (wireId.isNullOrBlank()) messageId(sessionId, seq) else "dsh-sys-" + wireId
        val sourceKind = message.obj("source")?.str("kind")
        val injectionKind = sourceKind?.takeIf { it.isNotBlank() } ?: "system"
        val events = mutableListOf<DshMappedEvent>()
        events += DshMappedEvent.Sse(
            SseEvent.MessageUpdated(
                Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = "system",
                    time = TimeInfo(created = time),
                    injectionKind = injectionKind,
                )
            )
        )
        content.forEachIndexed { i, el ->
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
                "file", "image" -> events += mapFileBlock(sessionId, id, i, block)
                else -> Unit
            }
        }
        return events
    }

    /**
     * deliverables/presented（V3）→ present 工具卡宿主消息上挂 [Part.Deliverables]。
     *
     * 实况载荷（docs/research/2026-09-11-dsh-v3-event-payloads.md）：
     * data{turn, callId, files[{path, description}]}。callId 是 present 的 **PTC 子调用**
     * id（`root:ptc:N`，实况 seq205）——[rootCallId] 剥后缀后经 [toolHostMessageId] 落位
     * 既有的根 run_code 工具卡宿主（tool/ptc-dispatch-start 的 rootCallId 同源；
     * 服务器权威，不经 args 反推）。空 files / path 空白的行丢弃；整条无有效文件时
     * 不产事件（零信息，不生成空 part，也不落 [DshIgnoreReason.STRUCTURAL_VIOLATION]）。
     */
    private fun mapDeliverablesPresented(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val callId = data.str("callId") ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val files = (data.arr("files") ?: emptyList()).mapNotNull { el ->
            val file = el as? JsonObject ?: return@mapNotNull null
            val path = file.str("path")?.takeIf { it.trim().isNotEmpty() } ?: return@mapNotNull null
            Part.Deliverables.PresentedFile(path = path, description = file.str("description"))
        }
        if (files.isEmpty()) return emptyList()
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Deliverables(
                        id = "dsh-deliverables-" + callId,
                        sessionId = sessionId,
                        messageId = toolHostMessageId(rootCallId(callId)),
                        presented = files,
                        time = Part.Deliverables.Time(start = time, end = time),
                    )
                )
            )
        )
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
        val id = messageId(sessionId, seq)
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
        val message = data.obj("message")
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
                    // #310②：服务器规范消息 id（消息反馈 CAS 地址——deriveEventMessage
                    // 投影的 data.message.id；缺席容错为 null）
                    wireId = message?.str("id"),
                )
            )
        )
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
                // 2026-09-01（Task 3c 卡片缺口）：file/image ContentBlock → Part.File
                //（与 user/message 同款；DSH attachment 字节拉取留待 session.attachment 接线）。
                "file", "image" -> events += mapFileBlock(sessionId, id, i, block)
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
    private fun mapToolResult(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val message = data.obj("message")
        val callId = message?.obj("source")?.str("callId")
            ?: (message?.arr("content") ?: emptyList()).firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.str("toolCallId")
            }
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val hostId = toolHostMessageId(callId)
        val errorElem = data["error"] ?: message?.get("error")
        val rootOutput = flattenToolResultOutput(message)
        val state = if (errorElem != null && errorElem !is JsonNull) {
            ToolState.Error(error = errorElem.errorText())
        } else {
            ToolState.Completed(output = rootOutput)
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
        ) + subAgentEnvelopeLinkEvents(sessionId, time, callId, rootOutput)
    }

    // ---- #349（2026-09-07）：subagent 族 code-dispatch 子代理卡 ----

    /**
     * subagent 卡键："{rootCallId}:subagent"——同一 run_code 根调用下唯一稳定键。
     *
     * 为什么不用 wire subCallId（"{root}:code:{n}"）：fg 派发的子会话 id 只出现在
     * 根 tool/result 信封里（信封只有 rootCallId），无状态映射下两事件要汇合到
     * 同一 part，键必须由 rootCallId 单侧可推导。副作用：一个 run_code 内多次
     * 子代理派发共享一卡（后者覆盖前者，run_code 根卡仍保全量输出）——罕见
     * 场景的取舍，注释存档。
     */
    private fun subAgentCardKey(rootCallId: String): String = rootCallId + ":subagent"

    /** 仅 subagent / subagent_fork 内层派发升格为卡；其余内层工具维持忽略。 */
    private fun isSubAgentDispatchName(name: String?): Boolean =
        name == "subagent" || name == "subagent_fork"

    /** bg 派发回执文本："started subagent <uuid>"（runId 即派发即得）。 */
    private val STARTED_SUBAGENT_RUN_ID = Regex("started subagent ([A-Za-z0-9-]+)")

    /**
     * 首个完整 JSON 对象提取：整串直试，失败则花括号深度扫描切前缀再解析。
     *
     * 动机（2026-09-07 真机实证 fb650391 seq12556）：run_code 根回执 text 可把
     * 子代理信封**连发两份**（"{…}\n{…}"，959 字符 = 2×~480）——整串 parse 恒
     * 失败致 fg 关联静默丢失。深度扫描尊重字符串/转义内的花括号，取首个
     * 完整对象（两份同源，取首即可）。
     */
    private fun firstJsonObjectOf(text: String): JsonObject? {
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { return it }
        var depth = 0
        var inString = false
        var escaped = false
        for ((i, ch) in text.withIndex()) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            json.parseToJsonElement(text.substring(0, i + 1)) as? JsonObject
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /** code-dispatch-start → 子代理卡 Running（arguments → input → 描述行）。 */
    private fun mapCodeDispatchStart(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val name = data.str("name")
        if (!isSubAgentDispatchName(name)) {
            return listOf(DshMappedEvent.Ignored(DshIgnoreReason.CODE_DISPATCH))
        }
        val root = data.str("rootCallId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val key = subAgentCardKey(root)
        val input = data.obj("arguments") ?: JsonObject(emptyMap())
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.Assistant(
                        id = toolHostMessageId(key),
                        sessionId = sessionId,
                        time = TimeInfo(created = time),
                        parentId = "",
                    )
                )
            ),
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Tool(
                        id = key,
                        sessionId = sessionId,
                        messageId = toolHostMessageId(key),
                        callId = key,
                        tool = name ?: "subagent",
                        state = ToolState.Running(
                            input = input,
                            time = ToolState.Running.Time(start = time),
                        ),
                    )
                )
            ),
        )
    }

    /**
     * code-dispatch → 子代理卡终态。
     *
     * - bg：content 首行 "started subagent <uuid>" → runId 即 metadata（导航即达，
     *   卡片完结而子代理后台续跑——DSH 语义：派发完成 ≠ 子代理完成）；
     * - fg：content = 子代理最终报告（无 id）——runId 由随后的根 tool/result 信封
     *   关联补写（[subAgentEnvelopeLinkEvents]）。
     */
    private fun mapCodeDispatch(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val name = data.str("name")
        if (!isSubAgentDispatchName(name)) {
            return listOf(DshMappedEvent.Ignored(DshIgnoreReason.CODE_DISPATCH))
        }
        val root = data.str("rootCallId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val key = subAgentCardKey(root)
        val output = (data.arr("content") ?: emptyList()).mapNotNull { el ->
            (el as? JsonObject)?.str("text")
        }.filter { it.isNotEmpty() }.joinToString("\n")
        val isError = data.bool("isError") == true
        val runId = STARTED_SUBAGENT_RUN_ID.find(output)?.groupValues?.get(1)
        val metadata = runId?.let {
            mapOf("sessionId" to JsonPrimitive(it), "sessionID" to JsonPrimitive(it))
        }
        val state = if (isError) {
            ToolState.Error(error = output, metadata = metadata)
        } else {
            ToolState.Completed(
                output = output,
                metadata = metadata,
                time = ToolState.Completed.Time(start = time, end = time),
            )
        }
        return listOf(
            // 宿主重申（幂等 upsert）：防实况/回放边界上 start 帧缺席导致孤儿 part
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.Assistant(
                        id = toolHostMessageId(key),
                        sessionId = sessionId,
                        time = TimeInfo(created = time),
                        parentId = "",
                    )
                )
            ),
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Tool(
                        id = key,
                        sessionId = sessionId,
                        messageId = toolHostMessageId(key),
                        callId = key,
                        tool = name ?: "subagent",
                        state = state,
                    )
                )
            ),
        )
    }

    /**
     * #349 fg 关联：根 tool/result 信封 {kind:"foreground", runId, output:[…]} →
     * 给 "{root}:subagent" 卡补写 metadata（bg 已在 dispatch 带上，且 bg 信封
     * kind≠foreground 不进本分支——两路互补不互踩）。非信封返回值（普通 run_code
     * 结果）静默空集。output 取信封内层 text 块展平（与 dispatch 报告同源内容）。
     */
    private fun subAgentEnvelopeLinkEvents(
        sessionId: String,
        time: Long,
        rootCallId: String,
        rootOutput: String,
    ): List<DshMappedEvent> {
        val envelope = firstJsonObjectOf(rootOutput)
        if (envelope?.str("kind") != "foreground") return emptyList()
        val runId = envelope.str("runId")?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (envelope["output"] !is JsonArray) return emptyList()
        val key = subAgentCardKey(rootCallId)
        val report = (envelope["output"] as JsonArray).mapNotNull { el ->
            (el as? JsonObject)?.str("text")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.MessageUpdated(
                    Message.Assistant(
                        id = toolHostMessageId(key),
                        sessionId = sessionId,
                        time = TimeInfo(created = time),
                        parentId = "",
                    )
                )
            ),
            DshMappedEvent.Sse(
                SseEvent.MessagePartUpdated(
                    Part.Tool(
                        id = key,
                        sessionId = sessionId,
                        messageId = toolHostMessageId(key),
                        callId = key,
                        // 工具名缺席：mergePart 保留 existing 名 + input
                        tool = "",
                        state = ToolState.Completed(
                            output = report,
                            metadata = mapOf("sessionId" to JsonPrimitive(runId), "sessionID" to JsonPrimitive(runId)),
                        ),
                    )
                )
            ),
        )
    }

    /**
     * ContentBlock file/image → Part.File（2026-09-01 Task 3c）。
     *
     * DSH 图片块形态：{type:"image", attachment:{attachmentId, mediaType, bytes,
     * width, height, name?}}（dsh-llm ImageBlock）；file 块兼容 {type:"file",
     * mime, filename, url?}。mime 回退链：块 mime → attachment.mediaType →
     * octet-stream；source 保真 attachment/原文供后续 session.attachment 接线
     *（url 为 null 时既有图片缩略图链不渲染——字节拉取 = 后续任务，数据不再丢）。
     */
    private fun mapFileBlock(sessionId: String, msgId: String, index: Int, block: JsonObject): DshMappedEvent.Sse {
        val attachment = block.obj("attachment")
        val mime = block.str("mime") ?: attachment?.str("mediaType") ?: "application/octet-stream"
        val filename = block.str("filename") ?: attachment?.str("name")
        val url = block.str("url")
        return DshMappedEvent.Sse(
            SseEvent.MessagePartUpdated(
                Part.File(
                    id = PartIdContract.derive(msgId, "file", index.toLong()),
                    sessionId = sessionId,
                    messageId = msgId,
                    mime = mime,
                    filename = filename,
                    url = url,
                    source = attachment ?: block["source"],
                )
            )
        )
    }

    /** workflow 卡宿主消息 id（runId 键控——start/end 原位更新同一卡）。 */
    private fun workflowMessageId(runId: String): String = "dsh-workflow-" + runId

    /**
     * #323：command/run {commandId, name, args?, source} → [SseEvent.CommandRunStarted]。
     *
     * args 是原始入参串（recordInput=false 的命令缺席）；source={kind} 只取 kind
     * 保真透传。缺 commandId = 畸形（配对键不可缺失）→ MALFORMED 具名降级。
     */
    private fun mapCommandRun(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val commandId = data.str("commandId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.CommandRunStarted(
                    sessionId = sessionId,
                    commandId = commandId,
                    name = data.str("name") ?: "",
                    args = data.str("args"),
                    source = data.obj("source")?.str("kind"),
                    seq = seq,
                    time = time,
                )
            )
        )
    }

    /**
     * #323：command/done {commandId, kind, text?, sourceEventSeq?} →
     * [SseEvent.CommandDone]。kind 词汇开放（success|error|…）原样透传，由
     * CommandFeedbackFolder/UI 分支呈现。
     */
    private fun mapCommandDone(sessionId: String, seq: Long, time: Long, data: JsonObject): List<DshMappedEvent> {
        val commandId = data.str("commandId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        return listOf(
            DshMappedEvent.Sse(
                SseEvent.CommandDone(
                    sessionId = sessionId,
                    commandId = commandId,
                    kind = data.str("kind") ?: "success",
                    text = data.str("text"),
                    sourceEventSeq = data.long("sourceEventSeq"),
                    seq = seq,
                    time = time,
                )
            )
        )
    }

    /**
     * tool-workflow/run-start {runId, name} → synthetic 运行中卡（降级）。
     *
     * 信封无 id 属性：runId 非会话 id，给箭头会让子会话导航误触（#242 守卫落空
     * 静默拦）。state=running → SyntheticNotificationCard 走 generic 标签（避免
     * 误标"完成"）；run-end 同宿主 id 原位替换为终态。
     */
    private fun mapWorkflowRunStart(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val runId = data.str("runId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val name = data.str("name") ?: ""
        val summary = if (name.isBlank()) "workflow: " + runId else "workflow: " + name
        val text = "<task state=\"running\"><summary>" + xmlEscape(summary) + "</summary></task>"
        return syntheticNotificationEvents(sessionId, workflowMessageId(runId), time, text)
    }

    /**
     * tool-workflow/run-end {runId, stopReason: completed|cancelled|error} →
     * synthetic 终态卡（completed → completed；cancelled/error → error 破色）。
     */
    private fun mapWorkflowRunEnd(sessionId: String, time: Long, data: JsonObject): List<DshMappedEvent> {
        val runId = data.str("runId")
            ?: return listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED))
        val stopReason = data.str("stopReason") ?: "completed"
        val state = if (stopReason == "completed") "completed" else "error"
        val text = "<task state=\"" + state + "\"><summary>workflow: " + xmlEscape(runId) + "</summary>" +
            "<task_result>" + xmlEscape(stopReason) + "</task_result></task>"
        return syntheticNotificationEvents(sessionId, workflowMessageId(runId), time, text)
    }

    /** synthetic 通知消息装配：MessageUpdated 壳 + text part（消息/part id 确定性——原位更新）。 */
    private fun syntheticNotificationEvents(sessionId: String, id: String, time: Long, text: String): List<DshMappedEvent> = listOf(
        DshMappedEvent.Sse(
            SseEvent.MessageUpdated(
                Message.User(id = id, sessionId = sessionId, role = "synthetic", time = TimeInfo(created = time))
            )
        ),
        DshMappedEvent.Sse(
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = PartIdContract.derive(id, "text", 0),
                    sessionId = sessionId,
                    messageId = id,
                    text = text,
                    time = Part.Text.Time(start = time, end = time),
                )
            )
        ),
    )

    /** synthetic 信封 XML 转义（summary/task_result 内的 & < > " 保真）。 */
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

    // ============ jobs / projection 帧解析 ============

    /** session/jobs 帧单个 JobView 项 → 域模型（wire taskViewSchema 形状）。 */
    /**
     * WorkspaceView → [Workspace]（#311 契约 ①-d）：名字键=title（缺席回退
     * basename(path)——服务器 create 默认语义）；workspaceId/path 必填，缺席
     * 整行丢弃（行级容错）；sessionIds 显式数组（归属关系）。
     */
    private fun mapWorkspaceView(w: JsonObject): dev.leonardo.ocbeacon.domain.model.Workspace? {
        val workspaceId = w.str("workspaceId") ?: return null
        val path = w.str("path") ?: return null
        val title = w.str("title")
            ?: dev.leonardo.ocbeacon.util.PathUtils.fileName(path).takeIf { it.isNotEmpty() }
            ?: path
        return dev.leonardo.ocbeacon.domain.model.Workspace(
            workspaceId = workspaceId,
            path = path,
            title = title,
            sessionIds = (w.arr("sessionIds") ?: emptyList()).mapNotNull { it.text() },
        )
    }

    private fun mapJobView(j: JsonObject): dev.leonardo.ocbeacon.domain.model.JobView =
        dev.leonardo.ocbeacon.domain.model.JobView(
            id = j.str("id") ?: "",
            kind = j.str("kind") ?: "",
            label = j.str("label") ?: "",
            status = j.str("status") ?: "",
            detail = j.str("detail"),
            startedAt = j.long("startedAt") ?: 0L,
            finishedAt = j.long("finishedAt"),
        )

    /** 排队项预览截断上限（渲染行；官方 previewOf 语义弱化为中长预览）。 */
    private const val QUEUE_PREVIEW_MAX = 200

    /**
     * session/queue 帧单 item → 域模型（官方 QueueDock 归一化）。
     *
     * wire：{id, placement, message:{content:[{type:text,text},...], role, source}}。
     * - preview = 首个 text 块截断；
     * - text = 全 text 拼接（纯文本消息可编辑）；含非文本块 → null（编辑不可用）。
     * 缺 id/placement → 丢弃（畸形行容错）。
     */
    private fun mapQueueItem(item: JsonObject): dev.leonardo.ocbeacon.domain.model.QueuedInboxItem? {
        val id = item.str("id") ?: return null
        val placement = item.str("placement") ?: return null
        val message = item.obj("message")
        val content = message?.arr("content") ?: emptyList()
        val textBlocks = content.mapNotNull { el ->
            (el as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
        }
        val allBlocksAreText = content.isNotEmpty() && content.all { el ->
            (el as? JsonObject)?.str("type") == "text"
        }
        val preview = textBlocks.firstOrNull()
            ?.let { t -> if (t.length > QUEUE_PREVIEW_MAX) t.take(QUEUE_PREVIEW_MAX) + "…" else t }
            ?: ""
        val text = if (allBlocksAreText) textBlocks.joinToString("\n") else null
        return dev.leonardo.ocbeacon.domain.model.QueuedInboxItem(
            id = id,
            placement = placement,
            preview = preview,
            text = text,
        )
    }

    /** tokenUsage 投影值 → 域模型（四桶互斥累计，缺席桶归零）。 */
    private fun mapTokenUsage(v: JsonObject): dev.leonardo.ocbeacon.domain.model.DshTokenUsage =
        dev.leonardo.ocbeacon.domain.model.DshTokenUsage(
            uncachedInputTokens = v.long("uncachedInputTokens") ?: 0L,
            outputTokens = v.long("outputTokens") ?: 0L,
            cacheReadTokens = v.long("cacheReadTokens") ?: 0L,
            cacheWriteTokens = v.long("cacheWriteTokens") ?: 0L,
        )

    /** subagentTiming 投影值 → 域模型（active 内层展平；缺席 active = 无开放 turn）。 */
    private fun mapSubagentTiming(v: JsonObject): dev.leonardo.ocbeacon.domain.model.DshSubagentTiming {
        val active = v.obj("active")
        return dev.leonardo.ocbeacon.domain.model.DshSubagentTiming(
            settledMs = v.long("settledMs") ?: 0L,
            activeSince = active?.long("since"),
            activeThrough = active?.long("through"),
        )
    }


    /**
     * goal 投影整值 → 域模型（whole-value last-wins；goal/change 事件 data 与
     * session/projection key=goal 的 value 同构：{goal:{…}, roundsStarted, createdAt,
     * updatedAt}；clear tombstone 不走本函数——上层直接置 null）。
     */
    private fun mapGoalProjection(v: JsonObject): dev.leonardo.ocbeacon.domain.model.DshGoalProjection? {
        val goal = v.obj("goal") ?: return null
        val id = goal.str("id") ?: return null
        val blocked = goal.obj("blockedReason")
        return dev.leonardo.ocbeacon.domain.model.DshGoalProjection(
            goal = dev.leonardo.ocbeacon.domain.model.DshGoalSnapshot(
                id = id,
                revision = goal.long("revision") ?: 0L,
                objective = goal.str("objective") ?: "",
                phase = goal.str("phase") ?: "active",
                blockedReason = blocked?.let {
                    dev.leonardo.ocbeacon.domain.model.DshGoalBlockReason(
                        code = it.str("code") ?: "",
                        message = it.str("message") ?: "",
                    )
                },
                maxGoalRounds = goal.long("maxGoalRounds") ?: 0L,
            ),
            roundsStarted = v.long("roundsStarted") ?: 0L,
            createdAt = v.long("createdAt") ?: 0L,
            updatedAt = v.long("updatedAt") ?: 0L,
        )
    }

    // ============ JsonObject 安全取值 ============

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    /** 位置参数取文本（#296 host/remote-event args 列表元素）。 */
    private fun JsonElement.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** #378：compaction 族配对键（wire compactionId；缺席/空白 → null 不发实体）。 */
    private fun compactionIdOf(data: JsonObject): String? =
        data.str("compactionId")?.takeIf { it.isNotBlank() }

    /**
     * 三层根修（2026-09-09）：compaction/summary|prune 的 data.shadowedRange
     * （{"start":Long,"end":Long}）→ SurfaceRangeReplaced。区间残缺/越界 → null
     * （调用方维持原行为；残缺时打点可观测）。
     */
    private fun shadowedRangeOf(
        data: JsonObject,
        sessionId: String,
        seq: Long,
        time: Long,
    ): DshMappedEvent.Sse? {
        val range = data.obj("shadowedRange") ?: return null
        val start = range.long("start")
        val end = range.long("end")
        if (start == null || end == null || end < start) {
            AppLogger.w(TAG, "compaction shadowedRange 残缺（start=$start end=$end），忽略折叠指令")
            return null
        }
        return DshMappedEvent.Sse(
            SseEvent.SurfaceRangeReplaced(
                sessionId = sessionId, startSeq = start, endSeq = end,
                byMessageId = messageId(sessionId, seq), seq = seq, time = time,
            )
        )
    }

    /**
     * #378：ContentBlock[] → 全文（text 块拼接；块间双换行）。compaction/summary
     * 的 wire 形状（types.d.ts + 实录 seq-5391）。非 text 块（图片等）跳过——
     * 摘要域当前只有文本块，出现新块型时此处显式降级而非整块丢弃。
     * 容错：字符串形态（旧源码阅读时代的载荷假设）原样透传——tolerant reader，
     * 服务器版本漂移不致整事件丢失。
     */
    private fun contentBlocksText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) return element.contentOrNull?.takeIf { it.isNotBlank() }
        val blocks = element as? JsonArray ?: return null
        val texts = blocks.filterIsInstance<JsonObject>()
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
        if (texts.isEmpty()) return null
        return texts.joinToString("\n\n")
    }

    /** 错误载荷转可读文本：对象优先 message，其次 code，最后整体序列化。 */
    /** 错误载荷转可读文本：对象优先 message，其次 code，最后整体序列化。
     *  [keys] 按序探测——2026-08-31：host/agent-error 真实现载荷键是 message，
     *  旧形态是 error 对象；逐键回退防键失配丢文本。 */
    private fun JsonObject.errorText(vararg keys: String): String {
        for (key in keys) {
            val elem = this[key] ?: continue
            return elem.errorText()
        }
        return keys.first()
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
 * [Ignored.reason] == [DshIgnoreReason.STRUCTURAL_VIOLATION] 是 DshHistoryFolder
 * 拒绝重建的唯一判据；未知词汇走 UNKNOWN_DEGRADED 具名降级，不拒绝重建。
 */
sealed class DshMappedEvent {
    data class Sse(val event: SseEvent) : DshMappedEvent()
    data class Subscribed(val value: DshSubscribed) : DshMappedEvent()
    data class Ignored(val reason: String) : DshMappedEvent()
}

/** 忽略原因常量闭集（日志/测试断言用；folder 只认 STRUCTURAL_VIOLATION）。 */
object DshIgnoreReason {
    /** 结构性违约（事件乱序 / 种子缺失 / surfaceOp 越界）——拒绝重建的唯一判据。 */
    const val STRUCTURAL_VIOLATION = "structural-violation"

    /** 未知 SessionEvent 类型——#391 切片7 具名降级（不拒绝重建，仅日志/遥测计数）。 */
    const val UNKNOWN_DEGRADED = "unknown-degraded"

    /** DSH 0.1.5 会话格式 V3 新增词汇（已具名收编，渲染增强留后续切片）。 */
    const val SESSION_FORMAT_V3 = "session-format-v3"

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

    /** host/remote-event 已解包但转发事件无消费端（#296 白名单其余项）。 */
    const val REMOTE_EVENT = "remote-event"

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

    /** 插件域事件（hook/team——known-49 收尾）。 */
    const val PLUGIN_DOMAIN = "plugin-domain"

    /** tool-workflow agent-start/end 阶段明细（阶段卡后续增强，防逐成员刷卡）。 */
    const val WORKFLOW_AGENT = "workflow-agent"

    /** 未知类型但信封带 ignorable:true（旗标权威，E2E 后兑现）。 */
    const val IGNORABLE_FLAG = "ignorable-flag"

    /** chunk 工具流式增量/收尾标记（tool-call-delta/finish）。 */
    const val CHUNK_LIFECYCLE = "chunk-lifecycle"

    /** log-only 事件（设计 Tier3：request/header|context、session/end-seed 等）。 */
    const val LOG_ONLY = "log-only"

    /** tool/code-dispatch(-start) 渲染伴生事件（工具卡由 tool/call|result 承载）。 */
    const val CODE_DISPATCH = "code-dispatch"

    /** durable 审批面 asked/decided（实况面由 mux approval 帧承载）。 */
    const val APPROVAL_DURABLE = "approval-durable"
}