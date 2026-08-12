package dev.leonardo.ocbeacon.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorCodecTest {

    @Test
    fun encode_roundTrips() {
        val cursor = CursorCodec.encode("msg_fdd9e0967001Swfy1V3tS3MUnk", 1786129549671L)

        val decoded = CursorCodec.decode(cursor)

        assertNotNull(decoded)
        assertEquals("msg_fdd9e0967001Swfy1V3tS3MUnk", decoded!!.first)
        assertEquals(1786129549671L, decoded.second)
    }

    @Test
    fun decode_invalidReturnsNull() {
        assertNull(CursorCodec.decode("not-base64!!!"))
        assertNull(CursorCodec.decode(""))
    }

    @Test
    fun decode_knownServerCursor() {
        // curl 实测返回的游标
        val cursor = "eyJpZCI6Im1zZ19mZGQ5ZTA5NjcwMDFTd2Z5MVYzdFMzTVVuayIsInRpbWUiOjE3ODYxMjk1NDk2NzF9"

        val decoded = CursorCodec.decode(cursor)

        assertNotNull(decoded)
        assertEquals("msg_fdd9e0967001Swfy1V3tS3MUnk", decoded!!.first)
        assertEquals(1786129549671L, decoded.second)
    }

    // ============ V2 双向游标（loadAround / loadNewer） ============

    /**
     * V2 游标结构正确性：base64url(JSON{id, order:"desc", direction})。
     * 解码后字段与服务器契约一致（curl 实测：direction="next"=更旧，"previous"=更新）。
     */
    @Test
    fun encodeV2_older_producesCorrectBase64JsonStructure() {
        val cursor = CursorCodec.encodeV2("msg_target_1", CursorCodec.V2Direction.OLDER)

        val decoded = CursorCodec.decodeV2(cursor)
        assertNotNull(decoded)
        assertEquals("msg_target_1", decoded!!.first)
        assertEquals(CursorCodec.V2Direction.OLDER, decoded.second)
        // OLDER 方向对应服务器 direction="next"
        assertEquals("next", decoded.second.value)
    }

    @Test
    fun encodeV2_newer_uses_direction_previous() {
        val cursor = CursorCodec.encodeV2("msg_target_2", CursorCodec.V2Direction.NEWER)

        val decoded = CursorCodec.decodeV2(cursor)!!
        assertEquals("msg_target_2", decoded.first)
        // NEWER 方向对应服务器 direction="previous"
        assertEquals(CursorCodec.V2Direction.NEWER, decoded.second)
        assertEquals("previous", decoded.second.value)
    }

    /** 直接验证 base64 解码后的 JSON 包含 order="desc"（服务器契约字段）。 */
    @Test
    fun encodeV2_jsonContainsOrderDesc() {
        val cursor = CursorCodec.encodeV2("msg_x", CursorCodec.V2Direction.OLDER)
        val json = String(java.util.Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)

        // 必须包含服务器要求的三个字段
        assertTrue(json.contains("\"id\":\"msg_x\""))
        assertTrue(json.contains("\"order\":\"desc\""))
        assertTrue(json.contains("\"direction\":\"next\""))
    }

    @Test
    fun decodeV2_invalidReturnsNull() {
        assertNull(CursorCodec.decodeV2("not-base64!!!"))
        assertNull(CursorCodec.decodeV2(""))
    }

    /** V1 游标（{id,time}）不是合法 V2 游标 → decodeV2 返回 null（无 direction 字段）。 */
    @Test
    fun decodeV2_v1CursorReturnsNull() {
        val v1Cursor = CursorCodec.encode("msg_a", 100L)
        assertNull(CursorCodec.decodeV2(v1Cursor))
    }
}
