package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 echo→持久原子换装——mapUserMessage 的 rpcId 对账分支。
 *
 * 契约（dsh-api-session-controller MessageSourceMap）：RPC 提交的持久回显
 * source={kind:'user', rpcId}（'user-rpc' 面）；本地 echo 播种 id=pending-<rpcId>
 *（DshApiClient.promptAsync V012 admission）。持久到达 → 同批补发
 * MessageRemoved(pending-<rpcId>)——handleMessageRemoved 幂等（echo 不在为
 * no-op，历史/重放路径安全）。web 对位：observedRpcIds 命中即隐藏 echo。
 */
class DshQueueEcho356Test {

    private val json = Json

    private fun envelope(data: String, seq: Long = 5, time: Long = 11): JsonObject =
        json.parseToJsonElement(
            """{"type":"user/message","seq":$seq,"time":$time,"data":$data}"""
        ).jsonObject

    @Test
    fun `user message with rpc source emits pending echo removal`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":"r-1"}}"""),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        // 持久消息本体照常上屏
        assertTrue(events.any { it is SseEvent.MessageUpdated })
        // 同批拆除 pending echo（原子换装——无重复无空窗）
        val removals = events.filterIsInstance<SseEvent.MessageRemoved>()
        assertEquals(listOf("pending-r-1"), removals.map { it.messageId })
        assertEquals("s1", removals.single().sessionId)
    }

    @Test
    fun `user message without rpc source has no echo removal`() {
        // 普通（非 RPC）来源——source 缺席 / kind=user 无 rpcId：不产生拆除（回归护栏）
        val withoutSource = DshEventMapper.mapSessionEvent(
            "s1", envelope("""{"content":[{"type":"text","text":"hi"}]}"""),
        )
        assertTrue(
            withoutSource.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageRemoved }
        )

        val plainSource = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}"""),
        )
        assertTrue(
            plainSource.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageRemoved }
        )
    }

    @Test
    fun `blank rpcId is ignored defensively`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("""{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user","rpcId":""}}"""),
        )
        assertTrue(
            mapped.filterIsInstance<DshMappedEvent.Sse>()
                .map { it.event }.none { it is SseEvent.MessageRemoved }
        )
    }
}
