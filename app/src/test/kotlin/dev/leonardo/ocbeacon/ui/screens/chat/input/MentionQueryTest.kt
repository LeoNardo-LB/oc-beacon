package dev.leonardo.ocbeacon.ui.screens.chat.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [isQuotedMentionQuery] 引号形态判定（web mod34 @\" 先例）。 */
class MentionQueryTest {

    @Test
    fun `quote-prefixed query is quoted`() {
        assertTrue(isQuotedMentionQuery("\"src/ma"))
        assertTrue(isQuotedMentionQuery("\""))
    }

    @Test
    fun `plain query is not quoted`() {
        assertFalse(isQuotedMentionQuery("src/ma"))
        assertFalse(isQuotedMentionQuery(""))
        assertFalse(isQuotedMentionQuery("a\"b")) // 引号在中间不算（只有前导引号形态）
    }
}
