package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #317：token 三形态提取（URL / 宿主启动行 / 裸 token）——TokenNeeded UX 输入解析。
 */
class DshConnectionRegistryTest {

    @Test
    fun extract_fullUrlWithTokenQuery() {
        assertEquals(
            "abc123_-XYZ",
            extractDshToken("http://192.168.1.2:3080/?token=abc123_-XYZ"),
        )
    }

    @Test
    fun extract_startupLineFromWebLog() {
        assertEquals(
            "t0k3n",
            extractDshToken("dsh web: http://127.0.0.1:3080/?token=t0k3n&x=1"),
        )
    }

    @Test
    fun extract_bareToken() {
        assertEquals("a".repeat(43), extractDshToken("  " + "a".repeat(43) + " "))
    }

    @Test
    fun extract_rejectsGarbage() {
        assertNull(extractDshToken(""))
        assertNull(extractDshToken("   "))
        assertNull(extractDshToken("hello world token")) // 空白分段非单段
        assertNull(extractDshToken("short")) // 低于 20 字符下限
    }
}
