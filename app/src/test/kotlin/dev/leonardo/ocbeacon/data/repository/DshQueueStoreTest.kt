package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.QueuedInboxItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshQueueStore 状态机测试（2026-09-01 QueueDock——session/queue 整快照 last-wins）。
 *
 * 对齐官方 queueMirror（client.js:7026-7080）：整快照整替换、空集删键、
 * subscribed 清空由 DshEventMapper 发空快照承载（防替换/历史折叠 d9fc169b 同款）。
 */
class DshQueueStoreTest {

    private fun item(id: String) = QueuedInboxItem(id = id, placement = "queued", preview = "p" + id)

    @Test
    fun `applySnapshot is last-wins whole replacement not merge`() {
        val store = DshQueueStore()
        store.applySnapshot("s1", listOf(item("a"), item("b")))
        store.applySnapshot("s1", listOf(item("c")))
        assertEquals(listOf("c"), store.queueFor("s1").map { it.id })
    }

    @Test
    fun `empty snapshot deletes session key`() {
        val store = DshQueueStore()
        store.applySnapshot("s1", listOf(item("a")))
        store.applySnapshot("s1", emptyList())
        assertTrue(store.queueBySession.value["s1"] == null)
        assertTrue(store.queueFor("s1").isEmpty())
    }

    @Test
    fun `clearForSession releases queue state`() {
        val store = DshQueueStore()
        store.applySnapshot("s1", listOf(item("a")))
        store.clearForSession("s1")
        assertTrue(store.queueFor("s1").isEmpty())
        store.applySnapshot("s2", listOf(item("x")))
        store.clear()
        assertTrue(store.queueBySession.value.isEmpty())
    }
}
