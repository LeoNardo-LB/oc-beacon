package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import org.junit.Assert.assertEquals
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

    // --- #23 过滤负向用例 fixtures（自包含，扩展自上方 buildContentState 用例）---

    private val testServerId = "server-1"

    private fun testSession(id: String, directory: String = "D:/a", title: String? = null): Session =
        Session(
            id = id,
            directory = directory,
            title = title,
            time = Session.Time(created = 0L, updated = 0L),
        )

    private fun buildFilterState(
        sessions: List<Session>,
        serverSessionMap: Map<String, Set<String>> = mapOf(testServerId to sessions.map { it.id }.toSet()),
        categoryAssignments: Map<String, List<String>> = emptyMap(),
        favoritesOnly: Boolean = false,
        baseDirectory: String? = null,
        searchQuery: String? = null,
        categoryFilterIds: Set<String> = emptySet(),
    ): SessionListContentState {
        val draftRepository = object : DraftRepository {
            override fun getDraft(sessionId: String) = null
            override fun saveDraft(sessionId: String, draft: dev.leonardo.ocbeacon.domain.model.Draft) = Unit
            override fun clearDraft(sessionId: String) = Unit
            override fun getDraftSessionIds(): Set<String> = emptySet()
        }
        val data = SessionListDataInputs(
            sessions = sessions,
            statuses = emptyMap(),
            serverSessionMap = serverSessionMap,
            lastUserMessageTime = emptyMap(),
            categoryAssignments = categoryAssignments,
            sessionTags = emptyList(),
            favoritesOnly = favoritesOnly,
            lastReplyTime = emptyMap(),
            readTimes = emptyMap(),
            unreadBaseline = 0L,
            justRead = emptyMap(),
            allReadAt = 0L,
        )
        val ui = SessionListUiInputs(
            expandedPaths = emptySet(),
            selectedIds = emptySet(),
            baseDirectory = baseDirectory,
            lastToggledDirectory = null,
            searchQuery = searchQuery,
            viewMode = SessionViewMode.RECENT,
            categoryFilterIds = categoryFilterIds,
        )
        return buildContentState(data, ui, testServerId, draftRepository)
    }

    @Test
    fun `favoritesOnly 过滤未收藏会话`() {
        // 会话未分配 FAVORITE_TAG_ID → favoritesOnly=true 时被剔除
        val state = buildFilterState(
            sessions = listOf(testSession("s1")),
            categoryAssignments = emptyMap(),
            favoritesOnly = true,
        )
        assertTrue(state.treeNodes.isEmpty())
    }

    @Test
    fun `favoritesOnly 保留收藏会话`() {
        // 会话分配 FAVORITE_TAG_ID → favoritesOnly=true 时保留
        val state = buildFilterState(
            sessions = listOf(testSession("s1")),
            categoryAssignments = mapOf("s1" to listOf(FAVORITE_TAG_ID)),
            favoritesOnly = true,
        )
        assertEquals(1, state.treeNodes.size)
    }

    @Test
    fun `categoryFilterIds AND 过滤 需同时匹配全部 tag`() {
        // t1/t2 分属两个会话：同时筛选 t1+t2 无人满足（AND）；只筛选 t1 命中 1 个
        val sessions = listOf(
            testSession("s1", directory = "D:/a"),
            testSession("s2", directory = "D:/b"),
        )
        val assignments = mapOf("s1" to listOf("t1"), "s2" to listOf("t2"))
        val stateAnd = buildFilterState(
            sessions = sessions,
            categoryAssignments = assignments,
            categoryFilterIds = setOf("t1", "t2"),
        )
        assertTrue(stateAnd.treeNodes.isEmpty())
        val stateT1 = buildFilterState(
            sessions = sessions,
            categoryAssignments = assignments,
            categoryFilterIds = setOf("t1"),
        )
        assertEquals(1, stateT1.treeNodes.size)
    }

    @Test
    fun `searchQuery 匹配目录关键词`() {
        val sessions = listOf(testSession("s1", directory = "D:/projects/beacon"))
        val miss = buildFilterState(sessions = sessions, searchQuery = "不存在的关键词")
        assertTrue(miss.treeNodes.isEmpty())
        val hit = buildFilterState(sessions = sessions, searchQuery = "beacon")
        assertEquals(1, hit.treeNodes.size)
    }

    @Test
    fun `baseDirectory 前缀不匹配过滤会话`() {
        // 会话目录 D:/a/b，baseDirectory=D:/x 前缀不匹配 → 空；D:/a 匹配 → 1
        val sessions = listOf(testSession("s1", directory = "D:/a/b"))
        val miss = buildFilterState(sessions = sessions, baseDirectory = "D:/x")
        assertTrue(miss.treeNodes.isEmpty())
        val hit = buildFilterState(sessions = sessions, baseDirectory = "D:/a")
        assertEquals(1, hit.treeNodes.size)
    }

    @Test
    fun `serverSessionMap 剔除未映射会话`() {
        // s1 不在 serverSessionMap[serverId] 中 → 会话被剔除
        val sessions = listOf(testSession("s1", directory = "D:/a"))
        val state = buildFilterState(
            sessions = sessions,
            serverSessionMap = mapOf(testServerId to emptySet()),
        )
        assertTrue(state.treeNodes.isEmpty())
    }
}
