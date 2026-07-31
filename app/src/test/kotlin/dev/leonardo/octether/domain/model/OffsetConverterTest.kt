package dev.leonardo.octether.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetConverterTest {

    // 真实样本：OpenCodeApi.kt 前几行
    private val sampleKotlin = """
        package dev.leonardo.octether

        import io.ktor.client.HttpClient
        import io.ktor.client.request.get
    """.trimIndent()

    @Test
    fun `empty string offset 0 returns 1,1`() {
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol("", 0))
    }

    @Test
    fun `single line no newline offset 2 returns 1,3`() {
        assertEquals(LineCol(1, 3), OffsetConverter.charOffsetToLineCol("hello", 2))
    }

    @Test
    fun `LF newline offset 4 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\ncd", 4))
    }

    @Test
    fun `CRLF newline offset 4 returns 2,1`() {
        assertEquals(LineCol(2, 1), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 4))
    }

    @Test
    fun `CRLF offset 3 returns 1,4`() {
        assertEquals(LineCol(1, 4), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 3))
    }

    @Test
    fun `CRLF offset 5 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 5))
    }

    @Test
    fun `pure CR newline offset 4 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\rcd", 4))
    }

    @Test
    fun `mixed line endings all handled`() {
        val content = "a\nb\r\nc\rd"
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol(content, 0))
        assertEquals(LineCol(1, 2), OffsetConverter.charOffsetToLineCol(content, 1))
        assertEquals(LineCol(2, 1), OffsetConverter.charOffsetToLineCol(content, 2))
        assertEquals(LineCol(3, 1), OffsetConverter.charOffsetToLineCol(content, 5))
        assertEquals(LineCol(4, 1), OffsetConverter.charOffsetToLineCol(content, 7))
    }

    @Test
    fun `offset exceeds content length clamps`() {
        assertEquals(LineCol(1, 6), OffsetConverter.charOffsetToLineCol("hello", 100))
    }

    @Test
    fun `negative offset treated as 0`() {
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol("hello", -5))
    }

    @Test
    fun `realistic kotlin sample multiline`() {
        val offsetOfLine3 = sampleKotlin.indexOf("import io.ktor.client.HttpClient")
        val result = OffsetConverter.charOffsetToLineCol(sampleKotlin, offsetOfLine3)
        assertEquals(LineCol(3, 1), result)
    }

    @Test
    fun `lineColToCharOffset round-trip for single line`() {
        val offset = OffsetConverter.lineColToCharOffset("hello world", 1, 6)
        assertEquals(5, offset)
    }

    @Test
    fun `lineColToCharOffset for multiline LF`() {
        val offset = OffsetConverter.lineColToCharOffset("ab\ncd\nef", 3, 1)
        assertEquals(6, offset)
    }

    @Test
    fun `lineColToCharOffset for line beyond content returns end`() {
        val offset = OffsetConverter.lineColToCharOffset("ab\ncd", 10, 1)
        assertEquals(5, offset)
    }
}
