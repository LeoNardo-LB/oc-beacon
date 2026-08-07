package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 未读判定纯函数测试。 */
class SessionListUnreadTest {

    @Test
    fun `unread when last message time after read time`() {
        assertTrue(isUnread("s1", mapOf("s1" to 2000L), mapOf("s1" to 1000L)))
    }

    @Test
    fun `not unread when no message recorded`() {
        assertFalse(isUnread("s1", emptyMap(), emptyMap()))
        assertFalse(isUnread("s1", mapOf("s2" to 2000L), emptyMap()))
    }

    @Test
    fun `not unread when message time equals read time`() {
        assertFalse(isUnread("s1", mapOf("s1" to 1000L), mapOf("s1" to 1000L)))
    }

    @Test
    fun `not unread when message time before read time`() {
        assertFalse(isUnread("s1", mapOf("s1" to 1000L), mapOf("s1" to 2000L)))
    }

    @Test
    fun `unread when no read time recorded`() {
        assertTrue(isUnread("s1", mapOf("s1" to 1000L), emptyMap()))
    }

    @Test
    fun `baseline suppresses messages before unread feature enablement`() {
        // 基线=5000：更早的回复不算未读（历史会话不显示红点）
        assertFalse(isUnread("s1", mapOf("s1" to 1000L), emptyMap(), unreadBaseline = 5000L))
        // 基线后的新回复算未读
        assertTrue(isUnread("s1", mapOf("s1" to 6000L), emptyMap(), unreadBaseline = 5000L))
    }

    @Test
    fun `read time takes precedence over baseline`() {
        // 已读时间晚于基线：已读优先
        assertFalse(isUnread("s1", mapOf("s1" to 6000L), mapOf("s1" to 7000L), unreadBaseline = 5000L))
    }

    @Test
    fun `in-memory read signal suppresses unread immediately`() {
        // 持久化还是旧值（DataStore 写入未完成），内存信号已更新 → 不未读
        val merged = mergeReadTimes(
            persisted = mapOf("s1" to 1000L),
            inMemory = mapOf("s1" to 9000L),
        )
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), merged))
    }

    @Test
    fun `in-memory signal without persisted entry also works`() {
        val merged = mergeReadTimes(persisted = emptyMap(), inMemory = mapOf("s1" to 9000L))
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), merged))
        // 未在信号中的会话不受影响
        assertTrue(isUnread("s2", mapOf("s2" to 8000L), merged))
    }

    @Test
    fun `mark all read suppresses all sessions`() {
        // allReadAt 覆盖所有旧回复
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), emptyMap(), allReadAt = 9000L))
        assertFalse(isUnread("s2", mapOf("s2" to 1000L), emptyMap(), allReadAt = 9000L))
        // allReadAt 之后的新回复仍产生未读
        assertTrue(isUnread("s1", mapOf("s1" to 9500L), emptyMap(), allReadAt = 9000L))
    }

    @Test
    fun `buildContentState 保持未读判定与过滤语义`() {
        // 等价值 fixtures（现有文件无 sessions/SERVER_ID/draftRepository，按 brief 授权自包含构造）
        val sessions = listOf(
            Session(
                id = "s1",
                time = Session.Time(created = 0L, updated = 0L),
            )
        )
        val serverId = "server-1"
        val draftRepository = object : DraftRepository {
            override fun getDraft(sessionId: String) = null
            override fun saveDraft(sessionId: String, draft: dev.leonardo.ocbeacon.domain.model.Draft) = Unit
            override fun clearDraft(sessionId: String) = Unit
            override fun getDraftSessionIds(): Set<String> = emptySet()
        }

        val data = SessionListDataInputs(
            sessions = sessions,
            statuses = emptyMap(),
            serverSessionMap = mapOf(serverId to sessions.map { it.id }.toSet()),
            lastUserMessageTime = emptyMap(),
            categoryAssignments = emptyMap(),
            sessionTags = emptyList(),
            favoritesOnly = false,
            lastReplyTime = mapOf(sessions[0].id to 5000L),
            readTimes = mapOf(sessions[0].id to 1000L),
            unreadBaseline = 0L,
            justRead = emptyMap(),
            allReadAt = 0L,
        )
        val ui = SessionListUiInputs(
            expandedPaths = emptySet(),
            selectedIds = emptySet(),
            baseDirectory = null,
            lastToggledDirectory = null,
            searchQuery = null,
            viewMode = SessionViewMode.RECENT,
            categoryFilterIds = emptySet(),
        )
        val state = buildContentState(data, ui, serverId, draftRepository)
        val node = state.treeNodes.singleOrNull()
        assertTrue(node is TreeNode.Session && node.session.hasUnread)
    }
}
