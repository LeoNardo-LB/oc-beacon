package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #300②（`_rea` 之谜定音）：缓存 payload 无 type 判别键的回环类型保真。
 *
 * 根因链（真机 DB 实证 3,876 行 reasoning payload 全无 type）：
 * 1. JsonContentPolymorphicSerializer 只定制**反序列化**分发——序列化按具体类
 *    进行，Part 子类无 type 属性 → 落库 payload 恒无 type 判别键；
 * 2. 回读走 PartSerializer 兜底字段推断，`containsKey("text")` 排在
 *    `containsKey("reasoning")` 之前，而 **Reasoning 的内容字段名就是 text**
 *    （"reasoning" 键是任何 Part 都不会序列化出的死分支）→ Reasoning 恒误判
 *    为 Text，保住 `_reasoning_ord_` 派生 id（Stage B 观察到的 CHUNK plan ×14）。
 * 后果：缓存重进场思考内容以正文渲染（挫败 #271「重进思考卡内容完整」裁决）、
 * copyText 混入思考文本、段化权重（700 vs 200+len）漂移。
 */
class PartCacheRoundTripTest {

    /** 生产 Json 配置（NetworkModule.provideJson 同款——encodeDefaults=true）。 */
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun reasoning_cacheRoundTrip_preservesType() {
        val original = Part.Reasoning(
            id = "seq-176_reasoning_ord_2",
            sessionId = "ses_1",
            messageId = "seq-176",
            text = "thinking…",
            time = Part.Reasoning.Time(start = 100, end = 200),
        )
        val payload = json.encodeToString<Part>(original)
        val decoded = json.decodeFromString<Part>(payload)
        // 回环类型保真：修复前落到 Part.Text（同 id 同内容、类型翻转）
        assertTrue("reasoning must survive cache round-trip, got ${decoded::class.simpleName}", decoded is Part.Reasoning)
        assertEquals(original, decoded)
    }

    @Test
    fun text_cacheRoundTrip_preservesType() {
        val original = Part.Text(
            id = "seq-176_text_ord_3",
            sessionId = "ses_1",
            messageId = "seq-176",
            text = "body",
        )
        val decoded = json.decodeFromString<Part>(json.encodeToString<Part>(original))
        assertTrue(decoded is Part.Text)
        assertEquals(original, decoded)
    }

    @Test
    fun legacyPayload_noType_reasoningDerivedId_decodesReasoning() {
        // 旧归档/无 type 列可依的场景：派生 id 契约（PartIdContract）兜底判型
        val decoded = json.decodeFromString<Part>(
            """{"id":"msg_x_reasoning_ord_0","sessionID":"ses_1","messageID":"msg_x","text":"think"}"""
        )
        assertTrue("reasoning-derived id must infer Reasoning, got ${decoded::class.simpleName}", decoded is Part.Reasoning)
    }

    @Test
    fun legacyPayload_noType_textDerivedId_decodesText() {
        val decoded = json.decodeFromString<Part>(
            """{"id":"msg_x_text_ord_1","sessionID":"ses_1","messageID":"msg_x","text":"body"}"""
        )
        assertTrue(decoded is Part.Text)
    }

    @Test
    fun wirePayload_withType_dispatchUnchanged() {
        // 服务器 wire 形态（带 type）分发不受兜底调整影响
        val decoded = json.decodeFromString<Part>(
            """{"type":"reasoning","id":"r1","sessionID":"ses_1","messageID":"m1","text":"t"}"""
        )
        assertTrue(decoded is Part.Reasoning)
    }
}
