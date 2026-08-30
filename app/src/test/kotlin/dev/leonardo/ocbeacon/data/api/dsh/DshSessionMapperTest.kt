package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DshSessionMapper 字段逐一断言测试（backlog #276 步骤③；设计 §5 session.list 形状）。
 *
 * P-4 实测载荷：{sessionId, updatedAt(epoch-ms), running, blank, parentSessionId?,
 * origin?, cwd?, agentPreset?, projections?{asOfSeq, values{title{title},...}}}。
 */
class DshSessionMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `maps full item field by field`() {
        val item = obj("""{
            "sessionId":"sess-0001","updatedAt":1788109000023,"running":true,"blank":false,
            "parentSessionId":"sess-0000","origin":"user","cwd":"/home/user/project",
            "agentPreset":"code",
            "projections":{"asOfSeq":17,"values":{
                "title":{"title":"fixture session"},
                "sessionListMetadata":{"blank":false,"lastPromptAt":1788109000011}
            }}
        }""".trimIndent())
        val session = DshSessionMapper.toSession(item)
        assertEquals("sess-0001", session.id)
        assertEquals(1788109000023L, session.time.updated)
        assertEquals("/home/user/project", session.directory)
        assertEquals("sess-0000", session.parentId)
        assertEquals("fixture session", session.title)
    }

    @Test
    fun `missing optional fields degrade without crash`() {
        val item = obj("""{"sessionId":"s-2","updatedAt":42,"running":false,"blank":false}""")
        val session = DshSessionMapper.toSession(item)
        assertEquals("s-2", session.id)
        assertEquals(42L, session.time.updated)
        assertEquals("", session.directory)
        assertNull(session.parentId)
        assertNull(session.title)
    }

    @Test
    fun `title projection absent leaves title null`() {
        val item = obj("""{"sessionId":"s-3","updatedAt":1,"running":false,"blank":false,
            "projections":{"asOfSeq":3,"values":{"sessionStats":{"turns":2}}}}""")
        assertNull(DshSessionMapper.toSession(item).title)
    }

    @Test
    fun `directory filter matches cwd and blank tolerance`() {
        val items = listOf(
            obj("""{"sessionId":"a","updatedAt":2,"running":false,"blank":false,"cwd":"/w/one"}"""),
            obj("""{"sessionId":"b","updatedAt":3,"running":false,"blank":false,"cwd":"/w/two"}"""),
            obj("""{"sessionId":"c","updatedAt":4,"running":false,"blank":true,"cwd":"/w/one"}"""),
        )
        val filtered = DshSessionMapper.filterByDirectory(items, "/w/one")
        assertEquals(listOf("a"), filtered.map { DshSessionMapper.toSession(it).id })
        // directory=null：不做目录过滤，但 blank 空壳恒滤除（与 V1 headerless 语义对齐）
        assertEquals(2, DshSessionMapper.filterByDirectory(items, null).size)
    }
}
