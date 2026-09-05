package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task4：待交互指示 builder 合流去重 + 行指示显隐（数据面——SessionRow
 * 渲染分支纯由 item.pendingInteraction 驱动：APPROVAL=琥珀点，question 族=
 * 提问标签，null=不呈现）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListPendingInteractionTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    private fun session(id: String) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = 2000),
    )

    private fun baseData(
        pendingIds: Set<String> = emptySet(),
        pendingInteractions: Map<String, PendingInteractionKind> = emptyMap(),
    ) = SessionListDataInputs(
        sessions = listOf(session("s1"), session("s2")),
        statuses = mapOf("s1" to SessionStatus.Idle, "s2" to SessionStatus.Idle),
        serverSessionMap = mapOf("server_1" to setOf("s1", "s2")),
        lastUserMessageTime = mapOf("s1" to 1L, "s2" to 2L),
        tagAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = pendingIds,
        pendingInteractions = pendingInteractions,
    )

    private fun ui(viewMode: SessionViewMode = SessionViewMode.RECENT) = SessionListUiInputs(
        expandedPaths = setOf("/proj"),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = viewMode,
        categoryFilterIds = emptySet(),
    )

    private fun itemFor(state: SessionListContentState, id: String) =
        state.treeNodes.filterIsInstance<dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode.Session>()
            .first { it.id == id }.session

    // ============ 行指示显隐 ============

    @Test
    fun `row shows approval indication when approval pending`() = runTest {
        val state = buildContentState(
            baseData(pendingInteractions = mapOf("s1" to PendingInteractionKind.APPROVAL)),
            ui(), "server_1", draftRepo,
        )
        assertEquals(PendingInteractionKind.APPROVAL, itemFor(state, "s1").pendingInteraction)
        assertNull(itemFor(state, "s2").pendingInteraction)
    }

    @Test
    fun `row hides indication when no pending interaction`() = runTest {
        val state = buildContentState(baseData(), ui(), "server_1", draftRepo)
        assertNull(itemFor(state, "s1").pendingInteraction)
        assertNull(itemFor(state, "s2").pendingInteraction)
    }

    @Test
    fun `pending interaction of other session does not leak`() = runTest {
        val state = buildContentState(
            baseData(pendingInteractions = mapOf("s_other" to PendingInteractionKind.APPROVAL)),
            ui(), "server_1", draftRepo,
        )
        assertNull(itemFor(state, "s1").pendingInteraction)
    }

    // ============ 合流去重（question 族 × Asking）============

    @Test
    fun `question indication merged with asking status hides duplicate`() = runTest {
        val state = buildContentState(
            baseData(
                pendingIds = setOf("s1"),
                pendingInteractions = mapOf("s1" to PendingInteractionKind.QUESTION),
            ),
            ui(), "server_1", draftRepo,
        )
        // Asking 已表达提问等待——勿双点
        assertEquals(SessionStatus.Asking, itemFor(state, "s1").status)
        assertNull(itemFor(state, "s1").pendingInteraction)
    }

    @Test
    fun `question indication without asking keeps indication`() = runTest {
        val state = buildContentState(
            baseData(pendingInteractions = mapOf("s1" to PendingInteractionKind.QUESTION)),
            ui(), "server_1", draftRepo,
        )
        assertEquals(PendingInteractionKind.QUESTION, itemFor(state, "s1").pendingInteraction)
    }

    @Test
    fun `plan review folds into question indication`() = runTest {
        val state = buildContentState(
            baseData(pendingInteractions = mapOf("s1" to PendingInteractionKind.PLAN_REVIEW)),
            ui(), "server_1", draftRepo,
        )
        // 未由 Asking 表达 → 保留（行端与 question 同点呈现）
        assertEquals(PendingInteractionKind.PLAN_REVIEW, itemFor(state, "s1").pendingInteraction)

        val merged = buildContentState(
            baseData(
                pendingIds = setOf("s1"),
                pendingInteractions = mapOf("s1" to PendingInteractionKind.PLAN_REVIEW),
            ),
            ui(), "server_1", draftRepo,
        )
        assertNull(itemFor(merged, "s1").pendingInteraction)
    }

    @Test
    fun `approval kept alongside asking`() = runTest {
        val state = buildContentState(
            baseData(
                pendingIds = setOf("s1"),
                pendingInteractions = mapOf("s1" to PendingInteractionKind.APPROVAL),
            ),
            ui(), "server_1", draftRepo,
        )
        // 审批等待与提问等待语义不同——两者各表
        assertEquals(SessionStatus.Asking, itemFor(state, "s1").status)
        assertEquals(PendingInteractionKind.APPROVAL, itemFor(state, "s1").pendingInteraction)
    }

    // ============ 树模式（FOLDER）============

    @Test
    fun `tree mode carries pending interaction`() = runTest {
        val state = buildContentState(
            baseData(pendingInteractions = mapOf("s1" to PendingInteractionKind.APPROVAL)),
            ui(SessionViewMode.FOLDER), "server_1", draftRepo,
        )
        assertEquals(PendingInteractionKind.APPROVAL, itemFor(state, "s1").pendingInteraction)
    }

    @Test
    fun `tree mode merges question with asking`() = runTest {
        val state = buildContentState(
            baseData(
                pendingIds = setOf("s1"),
                pendingInteractions = mapOf("s1" to PendingInteractionKind.QUESTION),
            ),
            ui(SessionViewMode.FOLDER), "server_1", draftRepo,
        )
        assertNull(itemFor(state, "s1").pendingInteraction)
    }
}
