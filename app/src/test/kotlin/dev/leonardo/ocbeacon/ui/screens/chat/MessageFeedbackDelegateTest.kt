package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.MessageFeedbackAction
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackDeleteResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackItem
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackPutResult
import dev.leonardo.ocbeacon.domain.model.MessageFeedbackRating
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #310② 消息反馈委托（TDD 红→绿）：
 * - 点击裁决（未评→put／同向→delete／换向→put）+ 本地状态落定；
 * - version-conflict：以服务器 current 重同步本地后重试一次（裁决随新观察重算），仍败→Conflict；
 * - 会话进入 list 拉种子（null 目录 = 端点缺席→空）。
 */
class MessageFeedbackDelegateTest {

    private fun item(
        rating: MessageFeedbackRating,
        version: String,
        messageId: String = "msg_1",
    ) = MessageFeedbackItem(
        messageId = messageId,
        rating = rating,
        note = null,
        version = version,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun `toggle unrated issues put with null ifVersion and stores committed item`() = runTest {
        val repo = mockk<ChatRepository>()
        val committed = item(MessageFeedbackRating.Positive, "v-2")
        coEvery {
            repo.messageFeedbackPut("srv", "s-1", "msg_1", MessageFeedbackRating.Positive, null, null)
        } returns Result.success(MessageFeedbackPutResult.Success(committed))
        val delegate = MessageFeedbackDelegate(repo, "srv")

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackOutcome.Done, outcome)
        assertEquals(committed, delegate.items.value["msg_1"])
        coVerify(exactly = 1) {
            repo.messageFeedbackPut("srv", "s-1", "msg_1", MessageFeedbackRating.Positive, null, null)
        }
    }

    @Test
    fun `same-direction tap withdraws via delete and clears local state`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery { repo.messageFeedbackDelete("srv", "s-1", "msg_1", "v-1") } returns
            Result.success(MessageFeedbackDeleteResult.Absent)
        val delegate = MessageFeedbackDelegate(repo, "srv")
        delegate.seedFrom(listOf(item(MessageFeedbackRating.Negative, "v-1")))

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Negative)

        assertEquals(MessageFeedbackOutcome.Removed, outcome)
        assertNull(delegate.items.value["msg_1"])
    }

    @Test
    fun `opposite-direction tap switches rating via put carrying observed version`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery {
            repo.messageFeedbackPut("srv", "s-1", "msg_1", MessageFeedbackRating.Positive, null, "v-3")
        } returns Result.success(
            MessageFeedbackPutResult.Success(item(MessageFeedbackRating.Positive, "v-4")),
        )
        val delegate = MessageFeedbackDelegate(repo, "srv")
        delegate.seedFrom(listOf(item(MessageFeedbackRating.Negative, "v-3")))

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackOutcome.Done, outcome)
        assertEquals(MessageFeedbackRating.Positive, delegate.items.value["msg_1"]?.rating)
    }

    /**
     * 冲突重试：首试 put(ifVersion=null) 被拒（服务器已有 positive
     * 项）→ 以 current 重同步后裁决变为同向 delete → 撤销成功。
     */
    @Test
    fun `version conflict resyncs from current and retries once with re-decided action`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery {
            repo.messageFeedbackPut("srv", "s-1", "msg_1", MessageFeedbackRating.Positive, null, null)
        } returns Result.success(
            MessageFeedbackPutResult.VersionConflict(item(MessageFeedbackRating.Positive, "v-9")),
        )
        coEvery { repo.messageFeedbackDelete("srv", "s-1", "msg_1", "v-9") } returns
            Result.success(MessageFeedbackDeleteResult.Absent)
        val delegate = MessageFeedbackDelegate(repo, "srv")

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackOutcome.Removed, outcome)
        assertNull(delegate.items.value["msg_1"])
        coVerify(exactly = 1) {
            repo.messageFeedbackPut("srv", "s-1", "msg_1", MessageFeedbackRating.Positive, null, null)
        }
        coVerify(exactly = 1) { repo.messageFeedbackDelete("srv", "s-1", "msg_1", "v-9") }
    }

    /** 二次冲突（重试后仍被拒）→ Conflict 交付 UI 提示；本地已同步最新 current。 */
    @Test
    fun `conflict persisting after one retry surfaces conflict outcome`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery {
            repo.messageFeedbackPut(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            MessageFeedbackPutResult.VersionConflict(item(MessageFeedbackRating.Negative, "v-x")),
        )
        val delegate = MessageFeedbackDelegate(repo, "srv")

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackOutcome.Conflict, outcome)
        // 重同步：最后一次 current 在本地可见（负向 v-x）
        assertEquals("v-x", delegate.items.value["msg_1"]?.version)
        coVerify(exactly = 2) {
            repo.messageFeedbackPut(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `business failure surfaces failed outcome without state mutation`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery {
            repo.messageFeedbackPut(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            MessageFeedbackPutResult.Failure("target-not-found", "no such message"),
        )
        val delegate = MessageFeedbackDelegate(repo, "srv")

        val outcome = delegate.toggle("s-1", "msg_1", MessageFeedbackRating.Positive)

        assertEquals(MessageFeedbackOutcome.Failed, outcome)
        assertTrue(delegate.items.value.isEmpty())
    }

    @Test
    fun `seed stores list snapshot keyed by message id`() = runTest {
        val repo = mockk<ChatRepository>()
        coEvery { repo.messageFeedbackList("srv", "s-1") } returns Result.success(
            listOf(item(MessageFeedbackRating.Positive, "v-1"), item(MessageFeedbackRating.Negative, "v-2", messageId = "msg_2")),
        )
        val delegate = MessageFeedbackDelegate(repo, "srv")

        delegate.seed("s-1")

        assertEquals(setOf("msg_1", "msg_2"), delegate.items.value.keys)
        assertEquals(MessageFeedbackRating.Negative, delegate.items.value["msg_2"]?.rating)
    }
}
