package dev.leonardo.ocremoteplus.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeTaskListMarkersTest {

    @Test
    fun `unchecked ballot box normalized to empty checkbox`() {
        assertEquals("- [ ] Task", normalizeTaskListMarkers("- \u2610 Task"))
    }

    @Test
    fun `checked ballot box normalized to x checkbox`() {
        assertEquals("- [x] Done", normalizeTaskListMarkers("- \u2611 Done"))
    }

    @Test
    fun `white heavy check mark normalized to x checkbox`() {
        assertEquals("- [x] Verified", normalizeTaskListMarkers("- \u2705 Verified"))
    }

    @Test
    fun `plus and asterisk list markers also normalized`() {
        assertEquals("+ [ ] A\n* [x] B", normalizeTaskListMarkers("+ \u2610 A\n* \u2611 B"))
    }

    @Test
    fun `indented task markers normalized`() {
        assertEquals("  - [x] Nested", normalizeTaskListMarkers("  - \u2611 Nested"))
    }

    @Test
    fun `ordinary list items remain unchanged`() {
        val content = "- Regular item\n* Another\n+ Third"
        assertEquals(content, normalizeTaskListMarkers(content))
    }

    @Test
    fun `task markers inside code fence remain unchanged`() {
        val content = "```text\n- \u2611 Inside fence\n```"
        assertEquals(content, normalizeTaskListMarkers(content))
    }

    @Test
    fun `task markers inside tilde fence remain unchanged`() {
        val content = "~~~\n- \u2610 Inside tilde fence\n~~~"
        assertEquals(content, normalizeTaskListMarkers(content))
    }

    @Test
    fun `markers after fence closes are normalized again`() {
        val content = "```\ncode\n```\n- \u2611 After"
        assertEquals("```\ncode\n```\n- [x] After", normalizeTaskListMarkers(content))
    }

    @Test
    fun `strikethrough tildes preserved alongside task markers`() {
        val content = "Keep ~~strikethrough~~\n- \u2611 task"
        assertEquals("Keep ~~strikethrough~~\n- [x] task", normalizeTaskListMarkers(content))
    }

    @Test
    fun `standalone tilde preserved alongside task markers`() {
        val content = "Use ~/project\n- \u2610 a task"
        assertEquals("Use ~/project\n- [ ] a task", normalizeTaskListMarkers(content))
    }

    @Test
    fun `email autolink preserved alongside task markers`() {
        val content = "Contact <test@example.com>\n- \u2611 reply"
        assertEquals("Contact <test@example.com>\n- [x] reply", normalizeTaskListMarkers(content))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", normalizeTaskListMarkers(""))
    }

    @Test
    fun `marker without trailing space is not matched`() {
        // The regex requires at least one space/tab after the checkbox glyph.
        val content = "- \u2610NoSpace"
        assertEquals(content, normalizeTaskListMarkers(content))
    }
}
