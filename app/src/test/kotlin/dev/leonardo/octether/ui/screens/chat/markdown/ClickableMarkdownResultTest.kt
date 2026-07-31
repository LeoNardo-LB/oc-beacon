package dev.leonardo.octether.ui.screens.chat.markdown

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class ClickableMarkdownResultTest {

    @Test
    fun `ClickableItem Link has correct properties`() {
        val item = ClickableItem.Link("click here", "https://example.com")
        assertEquals("click here", item.text)
        assertEquals("https://example.com", (item as ClickableItem.Link).url)
    }

    @Test
    fun `ClickableItem CodePath has correct properties`() {
        val item = ClickableItem.CodePath("src/Main.kt")
        assertEquals("src/Main.kt", item.text)
    }

    @Test
    fun `ClickableMarkdownResult holds annotated string and items`() {
        val result = ClickableMarkdownResult(
            annotatedString = androidx.compose.ui.text.AnnotatedString("test"),
            items = listOf(ClickableItem.CodePath("Foo.kt")),
        )
        assertNotNull(result.annotatedString)
        assertEquals(1, result.items.size)
        assertTrue(result.items[0] is ClickableItem.CodePath)
    }
}
