package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListPendingQuestionTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    private fun session(id: String) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = 2000)
    )

    private fun baseData(pendingIds: Set<String>) = SessionListDataInputs(
        sessions = listOf(session("s1"), session("s2")),
        statuses = mapOf("s1" to SessionStatus.Idle, "s2" to SessionStatus.Idle),
        serverSessionMap = mapOf("server_1" to setOf("s1", "s2")),
        lastUserMessageTime = mapOf("s1" to 1L, "s2" to 2L),
        categoryAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        justRead = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = pendingIds
    )

    private fun ui() = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet()
    )

    private fun nodeFor(state: SessionListContentState, id: String) =
        state.treeNodes.filterIsInstance<dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode.Session>()
            .first { it.id == id }

    @Test
    fun `session with pending question gets flag`() {
        val state = buildContentState(baseData(setOf("s1")), ui(), "server_1", draftRepo)
        assertTrue(nodeFor(state, "s1").session.hasPendingQuestion)
        assertFalse(nodeFor(state, "s2").session.hasPendingQuestion)
    }

    @Test
    fun `no pending questions leaves flags false`() {
        val state = buildContentState(baseData(emptySet()), ui(), "server_1", draftRepo)
        assertFalse(nodeFor(state, "s1").session.hasPendingQuestion)
    }

    @Test
    fun `pending ids from other server ignored`() {
        val state = buildContentState(baseData(setOf("s_other")), ui(), "server_1", draftRepo)
        assertFalse(nodeFor(state, "s1").session.hasPendingQuestion)
        assertFalse(nodeFor(state, "s2").session.hasPendingQuestion)
    }
}
