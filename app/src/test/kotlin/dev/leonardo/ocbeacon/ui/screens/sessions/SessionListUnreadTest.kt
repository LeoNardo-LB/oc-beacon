package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.data.repository.UnreadBadgeService
import dev.leonardo.ocbeacon.data.repository.UnreadEvent
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 未读判定纯函数 + 红点模块已读合并链路测试（#171 迁移后）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListUnreadTest {

    @Test
    fun `unread when last message time after read time`() {
        assertTrue(isUnread("s1", mapOf("s1" to 2000L), mapOf("s1" to 1000L), status = SessionStatus.Idle))
    }

    @Test
    fun `not unread when no message recorded`() {
        assertFalse(isUnread("s1", emptyMap(), emptyMap(), status = SessionStatus.Idle))
        assertFalse(isUnread("s1", mapOf("s2" to 2000L), emptyMap(), status = SessionStatus.Idle))
    }

    @Test
    fun `not unread when message time equals read time`() {
        assertFalse(isUnread("s1", mapOf("s1" to 1000L), mapOf("s1" to 1000L), status = SessionStatus.Idle))
    }

    @Test
    fun `not unread when message time before read time`() {
        assertFalse(isUnread("s1", mapOf("s1" to 1000L), mapOf("s1" to 2000L), status = SessionStatus.Idle))
    }

    @Test
    fun `unread when no read time recorded`() {
        assertTrue(isUnread("s1", mapOf("s1" to 1000L), emptyMap(), status = SessionStatus.Idle))
    }

    /** 模块真实链路：水位线事件 → markSessionRead（读水位线）→ 合并读（内存信号压过旧持久值）。 */
    private fun unreadServiceWith(persisted: Map<String, Long>): UnreadBadgeService {
        val ds = mockk<SettingsDataStore> {
            every { sessionReadTimes(any()) } returns flowOf(persisted)
            io.mockk.coEvery { markSessionRead(any(), any(), any()) } returns Unit
            io.mockk.coEvery { markAllSessionsRead(any(), any()) } returns Unit
        }
        return UnreadBadgeService(ds, CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()))
    }

    @Test
    fun `markSessionRead in-memory signal suppresses unread over stale persisted`() = runTest {
        // 持久化还是旧值（DataStore 写入未完成），内存信号取水位线新值 → 不未读
        val service = unreadServiceWith(persisted = mapOf("s1" to 1000L))
        service.onEvent(UnreadEvent.ServerMessageCompleted("s1", 9000L))
        service.markSessionRead("srv", "s1")
        val merged = service.mergedReadTimes("srv").first()
        assertEquals(mapOf("s1" to 9000L), merged)
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), merged, status = SessionStatus.Idle))
    }

    @Test
    fun `in-memory signal without persisted entry also works`() = runTest {
        val service = unreadServiceWith(persisted = emptyMap())
        service.onEvent(UnreadEvent.ServerMessageCompleted("s1", 9000L))
        service.markSessionRead("srv", "s1")
        val merged = service.mergedReadTimes("srv").first()
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), merged, status = SessionStatus.Idle))
        // 未在信号中的会话不受影响
        assertTrue(isUnread("s2", mapOf("s2" to 8000L), merged, status = SessionStatus.Idle))
    }

    /** #184：一键已读作用域化——别服务器会话不被广播已读（进程期错杀修复）。 */
    @Test
    fun `markAllSessionsRead scoped broadcast keeps other-server session unread`() = runTest {
        val service = unreadServiceWith(persisted = emptyMap())
        // 双服务器水位线共存：A（快钟）a1=10_000 / B（慢钟）b1=4_000
        service.onEvent(UnreadEvent.ServerMessageCompleted("a1", 10_000L))
        service.onEvent(UnreadEvent.ServerMessageCompleted("b1", 4_000L))

        // 停在 B 列表一键已读（B 的会话集只含 b1）
        service.markAllSessionsRead("srvB", setOf("b1"))

        val merged = service.mergedReadTimes("srvB").first()
        assertEquals(mapOf("b1" to 4_000L), merged) // 广播键集不溢出 a1
        // a1 不因 B 的一键已读而被判已读（修复前：_justRead 溢出 → a1 红点错杀至重启）
        assertTrue(isUnread("a1", mapOf("a1" to 8_000L), merged, status = SessionStatus.Idle))
        // b1 域内已读位压住自己的水位线
        assertFalse(isUnread("b1", mapOf("b1" to 4_000L), merged, status = SessionStatus.Idle))
    }

    @Test
    fun `mark all read suppresses all sessions`() {
        // allReadAt 覆盖所有旧回复
        assertFalse(isUnread("s1", mapOf("s1" to 8000L), emptyMap(), allReadAt = 9000L, status = SessionStatus.Idle))
        assertFalse(isUnread("s2", mapOf("s2" to 1000L), emptyMap(), allReadAt = 9000L, status = SessionStatus.Idle))
        // allReadAt 之后的新回复仍产生未读
        assertTrue(isUnread("s1", mapOf("s1" to 9500L), emptyMap(), allReadAt = 9000L, status = SessionStatus.Idle))
    }

    @Test
    fun `busy session never unread even with newer completed`() {
        assertFalse(isUnread("s1", mapOf("s1" to 10_000L), mapOf("s1" to 5_000L), allReadAt = 0L, status = SessionStatus.Busy))
        assertTrue(isUnread("s1", mapOf("s1" to 10_000L), mapOf("s1" to 5_000L), allReadAt = 0L, status = SessionStatus.Idle))
    }

    @Test
    fun `idle status required for unread`() {
        assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 0L, status = SessionStatus.Busy))
        assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 0L, status = SessionStatus.Retry(attempt = 1, message = "retry", next = 0L)))
    }

    @Test
    fun `allReadAt gating works with status`() {
        assertFalse(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 20_000L, status = SessionStatus.Idle))
        assertTrue(isUnread("s1", mapOf("s1" to 10_000L), emptyMap(), allReadAt = 5_000L, status = SessionStatus.Idle))
    }

    @Test
    fun `buildContentState 保持未读判定与过滤语义`() = runTest {
        // 等价值 fixtures（现有文件无 sessions/SERVER_ID/draftRepository，按 brief 授权自包含构造）
        val sessions = listOf(
            Session(
                id = "s1",
                time = Session.Time(created = 0L, updated = 0L),
            )
        )
        val serverId = "server-1"
        val draftRepository = object : DraftRepository {
            override suspend fun getDraft(sessionId: String) = null
            override suspend fun saveDraft(sessionId: String, draft: dev.leonardo.ocbeacon.domain.model.Draft) = Unit
            override suspend fun clearDraft(sessionId: String) = Unit
            override suspend fun getDraftSessionIds(): Set<String> = emptySet()
        }

        val data = SessionListDataInputs(
            sessions = sessions,
            statuses = emptyMap(),
            serverSessionMap = mapOf(serverId to sessions.map { it.id }.toSet()),
            lastUserMessageTime = emptyMap(),
            tagAssignments = emptyMap(),
            sessionTags = emptyList(),
            favoritesOnly = false,
            lastReplyTime = mapOf(sessions[0].id to 5000L),
            readTimes = mapOf(sessions[0].id to 1000L),
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

    private suspend fun buildFilterState(
        sessions: List<Session>,
        serverSessionMap: Map<String, Set<String>> = mapOf(testServerId to sessions.map { it.id }.toSet()),
        tagAssignments: Map<String, List<String>> = emptyMap(),
        favoritesOnly: Boolean = false,
        baseDirectory: String? = null,
        searchQuery: String? = null,
        categoryFilterIds: Set<String> = emptySet(),
    ): SessionListContentState {
        val draftRepository = object : DraftRepository {
            override suspend fun getDraft(sessionId: String) = null
            override suspend fun saveDraft(sessionId: String, draft: dev.leonardo.ocbeacon.domain.model.Draft) = Unit
            override suspend fun clearDraft(sessionId: String) = Unit
            override suspend fun getDraftSessionIds(): Set<String> = emptySet()
        }
        val data = SessionListDataInputs(
            sessions = sessions,
            statuses = emptyMap(),
            serverSessionMap = serverSessionMap,
            lastUserMessageTime = emptyMap(),
            tagAssignments = tagAssignments,
            sessionTags = emptyList(),
            favoritesOnly = favoritesOnly,
            lastReplyTime = emptyMap(),
            readTimes = emptyMap(),
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
    fun `favoritesOnly 过滤未收藏会话`() = runTest {
        // 会话未分配 FAVORITE_TAG_ID → favoritesOnly=true 时被剔除
        val state = buildFilterState(
            sessions = listOf(testSession("s1")),
            tagAssignments = emptyMap(),
            favoritesOnly = true,
        )
        assertTrue(state.treeNodes.isEmpty())
    }

    @Test
    fun `favoritesOnly 保留收藏会话`() = runTest {
        // 会话分配 FAVORITE_TAG_ID → favoritesOnly=true 时保留
        val state = buildFilterState(
            sessions = listOf(testSession("s1")),
            tagAssignments = mapOf("s1" to listOf(FAVORITE_TAG_ID)),
            favoritesOnly = true,
        )
        assertEquals(1, state.treeNodes.size)
    }

    @Test
    fun `categoryFilterIds AND 过滤 需同时匹配全部 tag`() = runTest {
        // t1/t2 分属两个会话：同时筛选 t1+t2 无人满足（AND）；只筛选 t1 命中 1 个
        val sessions = listOf(
            testSession("s1", directory = "D:/a"),
            testSession("s2", directory = "D:/b"),
        )
        val assignments = mapOf("s1" to listOf("t1"), "s2" to listOf("t2"))
        val stateAnd = buildFilterState(
            sessions = sessions,
            tagAssignments = assignments,
            categoryFilterIds = setOf("t1", "t2"),
        )
        assertTrue(stateAnd.treeNodes.isEmpty())
        val stateT1 = buildFilterState(
            sessions = sessions,
            tagAssignments = assignments,
            categoryFilterIds = setOf("t1"),
        )
        assertEquals(1, stateT1.treeNodes.size)
    }

    @Test
    fun `searchQuery 匹配目录关键词`() = runTest {
        val sessions = listOf(testSession("s1", directory = "D:/projects/beacon"))
        val miss = buildFilterState(sessions = sessions, searchQuery = "不存在的关键词")
        assertTrue(miss.treeNodes.isEmpty())
        val hit = buildFilterState(sessions = sessions, searchQuery = "beacon")
        assertEquals(1, hit.treeNodes.size)
    }

    @Test
    fun `baseDirectory 前缀不匹配过滤会话`() = runTest {
        // 会话目录 D:/a/b，baseDirectory=D:/x 前缀不匹配 → 空；D:/a 匹配 → 1
        val sessions = listOf(testSession("s1", directory = "D:/a/b"))
        val miss = buildFilterState(sessions = sessions, baseDirectory = "D:/x")
        assertTrue(miss.treeNodes.isEmpty())
        val hit = buildFilterState(sessions = sessions, baseDirectory = "D:/a")
        assertEquals(1, hit.treeNodes.size)
    }

    @Test
    fun `serverSessionMap 剔除未映射会话`() = runTest {
        // s1 不在 serverSessionMap[serverId] 中 → 会话被剔除
        val sessions = listOf(testSession("s1", directory = "D:/a"))
        val state = buildFilterState(
            sessions = sessions,
            serverSessionMap = mapOf(testServerId to emptySet()),
        )
        assertTrue(state.treeNodes.isEmpty())
    }
}
