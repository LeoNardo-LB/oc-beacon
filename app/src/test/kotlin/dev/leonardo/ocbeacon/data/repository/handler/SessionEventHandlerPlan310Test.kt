package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.DshPlanProjection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #310③：plan 投影（session/projection key=plan）→ Session.plan last-wins 折叠。 */
class SessionEventHandlerPlan310Test {

    private lateinit var handler: SessionEventHandler

    @Before
    fun setup() {
        handler = SessionEventHandler()
    }

    private fun testSession(id: String) = Session(
        id = id,
        title = "Test",
        time = Session.Time(created = 1000L, updated = 2000L),
    )

    @Test
    fun `SessionPlanChanged folds cropped plan view into session`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        val plan = DshPlanProjection(active = true, pending = false)
        assertTrue(handler.handle(SseEvent.SessionPlanChanged("s1", plan), "server1"))

        assertEquals(plan, handler.sessions.value.single().plan)
    }

    @Test
    fun `SessionPlanChanged last-wins overwrites previous value`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionPlanChanged("s1", DshPlanProjection(active = true)), "server1")

        handler.handle(
            SseEvent.SessionPlanChanged("s1", DshPlanProjection(active = false, pending = true)),
            "server1",
        )

        assertEquals(
            DshPlanProjection(active = false, pending = true),
            handler.sessions.value.single().plan,
        )
    }

    @Test
    fun `SessionPlanChanged null tombstone clears plan`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionPlanChanged("s1", DshPlanProjection(active = true)), "server1")

        handler.handle(SseEvent.SessionPlanChanged("s1", null), "server1")

        assertNull(handler.sessions.value.single().plan)
    }

    @Test
    fun `SessionPlanChanged no-ops when session absent`() = runTest {
        handler.handle(SseEvent.SessionPlanChanged("ghost", DshPlanProjection(active = true)), "server1")

        assertTrue(handler.sessions.value.isEmpty())
    }
}