package dev.leonardo.ocbeacon.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
