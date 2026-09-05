package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.DshPlanProjection
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #310③ Plan 模式帧映射（wire 契约 docs/research/2026-09-05-310-wire-contracts.md §③）：
 * - 状态 = session/projection key=plan，客户端裁剪视图 {active, pending}；
 * - 审阅 = user-questions waterfall 的 plan-review intent（detail=计划全文，
 *   intent.approve=批准选项 label——只改呈现不改协议）。
 */
class DshEventMapperPlan310Test {

    private val json = Json

    private fun eventsOf(mapped: List<DshMappedEvent>): List<SseEvent> =
        mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }

    private fun projectionFrame(key: String, value: String): List<DshMappedEvent> =
        DshEventMapper.mapFrame(
            "session/projection",
            json.parseToJsonElement(
                "{\"type\":\"session/projection\",\"sessionId\":\"s1\",\"key\":\"$key\",\"value\":$value,\"seq\":20}"
            ).jsonObject,
        )

    @Test
    fun `projection plan frame maps cropped active pending view`() {
        val changed = eventsOf(projectionFrame("plan", "{\"active\":true,\"pending\":false}")).single()
            as SseEvent.SessionPlanChanged
        assertEquals("s1", changed.sessionId)
        assertEquals(DshPlanProjection(active = true, pending = false), changed.plan)
    }

    @Test
    fun `projection plan frame maps pending selection`() {
        val changed = eventsOf(projectionFrame("plan", "{\"active\":false,\"pending\":true}")).single()
            as SseEvent.SessionPlanChanged
        assertEquals(DshPlanProjection(active = false, pending = true), changed.plan)
    }

    @Test
    fun `projection plan null tombstone maps to clear`() {
        val changed = eventsOf(projectionFrame("plan", "null")).single() as SseEvent.SessionPlanChanged
        assertEquals("s1", changed.sessionId)
        assertEquals(null, changed.plan)
    }

    @Test
    fun `projection plan malformed value is ignored`() {
        assertEquals(
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED)),
            projectionFrame("plan", "\"on\""),
        )
    }

    // ============ question intent/detail 透传（#310③） ============

    @Test
    fun `question requested passes detail and plan-review intent through`() {
        // wire：dsh-plan-mode EXIT_PLAN_MODE → user-questions ask() 载荷
        //（intent.approve 是批准选项 label 字符串，非布尔）
        val mapped = DshEventMapper.mapFrame(
            "question/requested",
            json.parseToJsonElement(
                "{\"type\":\"question/requested\",\"sessionId\":\"s1\",\"questionId\":\"qr-1\",\"questions\":[" +
                    "{\"id\":\"plan-review\",\"header\":\"Plan review\",\"question\":\"Approve this plan and leave plan mode?\"," +
                    "\"detail\":\"# Plan\\n1. Step one\",\"options\":[{" +
                    "\"label\":\"Approve\",\"description\":\"Leave plan mode.\"},{" +
                    "\"label\":\"Keep planning\",\"description\":\"Stay in plan mode.\"}]," +
                    "\"intent\":{\"kind\":\"plan-review\",\"approve\":\"Approve\"}}]}"
            ).jsonObject,
        )
        val asked = eventsOf(mapped).single() as SseEvent.QuestionAsked
        val q = asked.questions.single()
        // 载荷 JSON 转义 \\n 经解析为真实换行
        assertEquals("# Plan\n1. Step one", q.detail)
        assertEquals(SseEvent.QuestionAsked.Intent(kind = "plan-review", approve = "Approve"), q.intent)
    }

    @Test
    fun `question requested without intent maps generic question with nulls`() {
        val mapped = DshEventMapper.mapFrame(
            "question/requested",
            json.parseToJsonElement(
                "{\"type\":\"question/requested\",\"sessionId\":\"s1\",\"questionId\":\"qr-2\",\"questions\":[{" +
                    "\"id\":\"q1\",\"question\":\"Deploy?\",\"options\":[{\"label\":\"Yes\",\"description\":\"\"}]}]}"
            ).jsonObject,
        )
        val asked = eventsOf(mapped).single() as SseEvent.QuestionAsked
        val q = asked.questions.single()
        assertEquals(null, q.detail)
        assertEquals(null, q.intent)
    }
}