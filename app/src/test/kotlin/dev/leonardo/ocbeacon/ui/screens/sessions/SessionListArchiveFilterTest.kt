package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #311 Task2：主列表归档滤除纯函数——workspace 快照归档集合
 * （V012 follow baseline+增量单源）驱动主列表/搜索/快照滤除 + 已归档列表构建。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListArchiveFilterTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    /** updated 递增，验证排序沿 lastUserMessageTime ?: updated 兜底。 */
    private fun session(id: String, updated: Long) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = updated),
    )

    private fun data(
        sessions: List<Session>,
        archivedSessionIds: Set<String>,
    ) = SessionListDataInputs(
        sessions = sessions,
        statuses = emptyMap(),
        serverSessionMap = emptyMap(),
        lastUserMessageTime = emptyMap(),
        tagAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = emptySet(),
        archivedSessionIds = archivedSessionIds,
    )

    private fun ui(searchQuery: String? = null) = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = searchQuery,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet(),
    )

    private fun mainIds(state: SessionListContentState) =
        state.treeNodes.filterIsInstance<TreeNode.Session>().map { it.id }

    @Test
    fun `main list excludes archived sessions`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(session("s1", 3000), session("s2", 2000), session("s3", 1000)),
                archivedSessionIds = setOf("s2"),
            ),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s1", "s3"), mainIds(state))
    }

    @Test
    fun `content sessions snapshot excludes archived`() = runTest {
        // sessions 快照供快速新建/设置页标签管理消费——归档会话应出干净列表
        val state = buildContentState(
            data(
                sessions = listOf(session("s1", 3000), session("s2", 2000)),
                archivedSessionIds = setOf("s2"),
            ),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s1"), state.sessions.map { it.id })
    }

    @Test
    fun `archived sessions surface in dedicated list sorted by recency`() = runTest {
        val state = buildContentState(
            data(
                sessions = listOf(session("s1", 3000), session("s2", 2000), session("s3", 1000)),
                archivedSessionIds = setOf("s2", "s3"),
            ),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s2", "s3"), state.archivedSessions.map { it.session.id })
    }

    @Test
    fun `archived sessions stay out of search results`() = runTest {
        // 官方 web 同形：archived 集合在搜索面一律排除（sessionVisible 过滤）
        val archived = session("s_archived", 5000).copy(title = "shared keyword")
        val active = session("s_active", 4000).copy(title = "shared keyword")
        val state = buildContentState(
            data(sessions = listOf(archived, active), archivedSessionIds = setOf("s_archived")),
            ui(searchQuery = "shared"), "server_1", draftRepo,
        )
        assertEquals(listOf("s_active"), mainIds(state))
        assertEquals(listOf("s_archived"), state.archivedSessions.map { it.session.id })
    }

    @Test
    fun `empty archive set keeps list semantics unchanged`() = runTest {
        // 回归护栏：非 DSH 恒空快照 = 现行为零变化
        val state = buildContentState(
            data(
                sessions = listOf(session("s1", 3000), session("s2", 2000)),
                archivedSessionIds = emptySet(),
            ),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s1", "s2"), mainIds(state))
        assertTrue(state.archivedSessions.isEmpty())
    }

    @Test
    fun `archived ids without cached session are dropped from archived list`() = runTest {
        // 快照集合可能引用未入缓存的会话（分页窗口外）——只呈现已知会话
        val state = buildContentState(
            data(
                sessions = listOf(session("s1", 3000)),
                archivedSessionIds = setOf("s1", "s_ghost"),
            ),
            ui(), "server_1", draftRepo,
        )
        assertTrue(mainIds(state).isEmpty())
        assertEquals(listOf("s1"), state.archivedSessions.map { it.session.id })
    }
}
