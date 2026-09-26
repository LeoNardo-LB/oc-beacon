package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** R4-B3 步2 终收口：泛化差量（rawMessages 承载用）。 */
class DiffListTest {
    @Test
    fun `等长零变零写`() {
        val t = mutableListOf("a", "b")
        assertEquals(0, diffListInto(t, listOf("a", "b")))
    }

    @Test
    fun `单变单写`() {
        val t = mutableListOf("a", "b1")
        assertEquals(1, diffListInto(t, listOf("a", "b2")))
        assertEquals("b2", t[1])
    }

    @Test
    fun `变长全量重置`() {
        val t = mutableListOf("a")
        assertEquals(2, diffListInto(t, listOf("a", "b")))
        assertEquals(listOf("a", "b"), t)
    }
}
