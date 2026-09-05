package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #310② 消息反馈 rating 切换纯逻辑（TDD 红→绿）。
 *
 * 三态裁决（对位 web mod37 toggle）：未评→put（ifVersion=null 断言无既有项）；
 * 已同向→delete 撤销（携带观察到的 version）；换向→put 换向（携带 version CAS）。
 */
class MessageFeedbackTest {

    private fun item(rating: MessageFeedbackRating, version: String = "v-1") = MessageFeedbackItem(
        messageId = "msg_1",
        rating = rating,
        note = null,
        version = version,
        createdAt = 100L,
        updatedAt = 200L,
    )

    @Test
    fun `unrated message toggling a rating issues put with null ifVersion`() {
        val action = MessageFeedbackToggle.decide(observed = null, desired = MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackAction.Put(MessageFeedbackRating.Positive, ifVersion = null), action)
    }

    @Test
    fun `same-direction tap withdraws feedback via delete with observed version`() {
        val observed = item(MessageFeedbackRating.Negative, version = "ver-9")

        val action = MessageFeedbackToggle.decide(observed, desired = MessageFeedbackRating.Negative)

        assertEquals(MessageFeedbackAction.Delete(ifVersion = "ver-9"), action)
    }

    @Test
    fun `opposite-direction tap switches rating via put carrying observed version`() {
        val observed = item(MessageFeedbackRating.Negative, version = "ver-7")

        val action = MessageFeedbackToggle.decide(observed, desired = MessageFeedbackRating.Positive)

        assertEquals(
            MessageFeedbackAction.Put(MessageFeedbackRating.Positive, ifVersion = "ver-7"),
            action,
        )
    }

    @Test
    fun `rating wire vocabulary only accepts positive and negative`() {
        assertEquals(MessageFeedbackRating.Positive, MessageFeedbackRating.fromWire("positive"))
        assertEquals(MessageFeedbackRating.Negative, MessageFeedbackRating.fromWire("negative"))
        assertNull(MessageFeedbackRating.fromWire("neutral"))
        assertNull(MessageFeedbackRating.fromWire(null))
    }
}
