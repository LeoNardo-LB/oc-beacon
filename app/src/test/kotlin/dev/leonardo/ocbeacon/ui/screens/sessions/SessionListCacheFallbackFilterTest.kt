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
 * #306：serverSessionMap 宽容过滤——无该服务器映射（null，断连 clearForServer /
 * 冷启动）时放行兜底流会话（原硬交集会把缓存兜底数据全部过滤掉 = 白屏根因②）。
 * 映射存在（含空集）语义不变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListCacheFallbackFilterTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    private fun session(id: String) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = 2000),
    )

    private fun data(
        sessions: List<Session>,
        serverSessionMap: Map<String, Set<String>>,
    ) = SessionListDataInputs(
        sessions = sessions,
        statuses = emptyMap(),
        serverSessionMap = serverSessionMap,
        lastUserMessageTime = emptyMap(),
        tagAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = emptySet(),
    )

    private fun ui() = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet(),
    )

    private fun sessionIds(state: SessionListContentState) =
        state.treeNodes.filterIsInstance<TreeNode.Session>().map { it.id }

    @Test
    fun `null server mapping passes fallback sessions through`() = runTest {
        // 断连后：映射 key 被移除（null），sessions 来自缓存兜底流
        val state = buildContentState(
            data(listOf(session("s1"), session("s2")), serverSessionMap = emptyMap()),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s1", "s2"), sessionIds(state))
    }

    @Test
    fun `empty set mapping still filters everything out`() = runTest {
        // 映射存在但为空集 = 服务器真的零会话（内存语义）→ 显示空，不兜底
        val state = buildContentState(
            data(listOf(session("s1")), serverSessionMap = mapOf("server_1" to emptySet())),
            ui(), "server_1", draftRepo,
        )
        assertTrue(sessionIds(state).isEmpty())
    }

    @Test
    fun `present mapping keeps intersection semantics`() = runTest {
        // 回归护栏：正常态交集过滤不变（含 parent 过滤）
        val parent = session("p1").copy(title = "parent", parentId = "root")
        val state = buildContentState(
            data(
                listOf(session("s1"), session("s2"), parent),
                serverSessionMap = mapOf("server_1" to setOf("s1", "s2", "p1")),
            ),
            ui(), "server_1", draftRepo,
        )
        assertEquals(listOf("s1", "s2"), sessionIds(state))
    }
}
