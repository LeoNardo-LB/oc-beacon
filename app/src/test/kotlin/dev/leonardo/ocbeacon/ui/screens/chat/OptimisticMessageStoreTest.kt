package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.OptimisticMessage
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.UserMsgStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticMessageStoreTest {

    private fun mkUser(pendingId: String, created: Long = 1000L): Message.User =
        Message.User(id = pendingId, sessionId = "sid-1", time = TimeInfo(created = created))

    private fun mkParts(pendingId: String, text: String = "hi"): List<Part> = listOf(
        Part.Text(id = "$pendingId-part-0", sessionId = "sid-1", messageId = pendingId, text = text)
    )

    private fun newStore(errors: MutableList<String> = mutableListOf()): Pair<OptimisticMessageStore, MutableList<String>> {
        val sink = errors
        val store = OptimisticMessageStore(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), errorSink = { sink.add(it) })
        return store to sink
    }

    @Test
    fun `initial state is empty and idle`() = runTest {
        val (store, _) = newStore()
        assertFalse(store.isSendingValue)
        assertTrue(store.pendingMessageIds.value.isEmpty())
        assertTrue(store.pendingMessages.value.isEmpty())
        assertTrue(store.pendingOptimisticSnapshot().isEmpty())
    }

    @Test
    fun `onSendStarted registers pending message and flips sending`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        assertTrue(store.isSendingValue)
        assertEquals(setOf("p-1"), store.pendingMessageIds.value)
        assertEquals(1, store.pendingMessages.value.size)
        val om = store.pendingMessages.value.single()
        assertEquals("p-1", om.pendingId)
        assertEquals(UserMsgStatus.Sending, om.status)
    }

    @Test
    fun `onSendSuccess clears sending and marks message Sent`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        store.onSendSuccess("p-1")

        assertFalse(store.isSendingValue)
        assertFalse("p-1" in store.pendingMessageIds.value)
        assertEquals(UserMsgStatus.Sent, store.pendingMessages.value.single().status)
    }

    @Test
    fun `onSendError marks Failed and pushes error to sink`() = runTest {
        val errors = mutableListOf<String>()
        val (store, sink) = newStore(errors)
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        store.onSendError("boom", "p-1")

        assertFalse(store.isSendingValue)
        assertFalse("p-1" in store.pendingMessageIds.value)
        assertEquals(UserMsgStatus.Failed, store.pendingMessages.value.single().status)
        assertEquals(listOf("boom"), sink)
    }

    @Test
    fun `onRetryStarted flips message back to Sending and re-registers id`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))
        store.onSendError("fail", "p-1")

        store.onRetryStarted("p-1")

        assertTrue(store.isSendingValue)
        assertTrue("p-1" in store.pendingMessageIds.value)
        assertEquals(UserMsgStatus.Sending, store.pendingMessages.value.single().status)
    }

    @Test
    fun `getPendingMessage returns by id`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1", "hello"))

        val pending = store.getPendingMessage("p-1")
        assertNotNull(pending)
        assertEquals("hello", (pending!!.parts.first() as Part.Text).text)
        assertNull(store.getPendingMessage("missing"))
    }

    @Test
    fun `removePendingMessage drops entry`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        store.removePendingMessage("p-1")

        assertTrue(store.pendingMessages.value.isEmpty())
    }

    @Test
    fun `restorePendingPrompts materializes records as Sending`() = runTest {
        val (store, _) = newStore()
        val record = PendingPromptRecord(
            messageId = "rec-1",
            sessionId = "sid-1",
            parts = listOf(PromptPart(type = "text", text = "restored")),
            createdAt = 5000L,
        )

        store.restorePendingPrompts(listOf(record))

        assertEquals(setOf("rec-1"), store.pendingMessageIds.value)
        val om = store.pendingMessages.value.single()
        assertEquals("rec-1", om.pendingId)
        assertEquals(UserMsgStatus.Sending, om.status)
        assertEquals("restored", (om.parts.first() as Part.Text).text)
        assertEquals(5000L, om.message.time.created)
    }

    @Test
    fun `restorePendingPrompts empty list is no-op`() = runTest {
        val (store, _) = newStore()
        store.restorePendingPrompts(emptyList())
        assertTrue(store.pendingMessages.value.isEmpty())
    }

    @Test
    fun `restorePendingPrompts deduplicates by pendingId`() = runTest {
        val (store, _) = newStore()
        val r1 = PendingPromptRecord("dup", "sid-1", listOf(PromptPart(type = "text", text = "first")), createdAt = 1L)
        store.restorePendingPrompts(listOf(r1))
        store.restorePendingPrompts(listOf(r1))

        assertEquals(1, store.pendingMessages.value.size)
    }

    @Test
    fun `pendingOptimisticSnapshot returns current value`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        val snap: List<OptimisticMessage> = store.pendingOptimisticSnapshot()
        assertEquals(1, snap.size)
        assertEquals("p-1", snap.single().pendingId)
    }

    @Test
    fun `markPendingAsFailed removes id and flips status`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))

        store.markPendingAsFailed("p-1")

        assertFalse("p-1" in store.pendingMessageIds.value)
        assertEquals(UserMsgStatus.Failed, store.pendingMessages.value.single().status)
    }

    @Test
    fun `multiple concurrent sends tracked independently`() = runTest {
        val (store, _) = newStore()
        store.onSendStarted("p-1", mkUser("p-1"), mkParts("p-1"))
        store.onSendStarted("p-2", mkUser("p-2"), mkParts("p-2"))

        assertEquals(setOf("p-1", "p-2"), store.pendingMessageIds.value)
        assertEquals(2, store.pendingMessages.value.size)

        store.onSendSuccess("p-1")
        assertFalse(store.isSendingValue)
        assertEquals(setOf("p-2"), store.pendingMessageIds.value)
        val statuses = store.pendingMessages.value.associate { it.pendingId to it.status }
        assertEquals(UserMsgStatus.Sent, statuses["p-1"])
        assertEquals(UserMsgStatus.Sending, statuses["p-2"])
    }
}
