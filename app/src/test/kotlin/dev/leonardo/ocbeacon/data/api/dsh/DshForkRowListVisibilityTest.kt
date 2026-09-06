package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * #331 A2 residual (2026-09-06 device acceptance): fork child row not visible within 5s
 * after returning to list - fork POST accepted, SessionCreated dispatched (logcat 09:20:09.936),
 * fork follow replay (session/title + user/message with ORIGINAL timestamps) done; row absent
 * from warm list; present at top after cold re-entry (REST baseline).
 *
 * Full-chain replay with device-captured wire shapes (mux synthesized frame ->
 * DshEventMapper -> orchestrator replacement defense -> SessionEventHandler fold ->
 * SessionListStateBuilder-shaped filter/sort). Any link dropping the row goes red.
 *
 * Timeline (ms compressed from device):
 * - parent row baseline updated=200 (09:14)
 * - fork added summary updatedAt=300 (09:20:09, real value post #330 passthrough)
 * - fork follow replay session/title + user/message original time=150 (09:13;
 *   logcat "Skip stale idle notification (7min old)" proves replay keeps original ts)
 */
class DshForkRowListVisibilityTest {

    private val sid = "srv-1"
    private val parentId = "session-parent"
    private val forkId = "session-fork-child"

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    /** SessionListStateBuilder-shaped: server-scope filter + parentId==null + sort key. */
    private fun listVisibleRows(handler: SessionEventHandler): List<Session> {
        val serverIds = handler.serverSessions.value[sid] ?: emptySet()
        return handler.sessions.value
            .filter { it.id in serverIds && it.parentId == null }
            .sortedByDescending { handler.lastUserMessageTime.value[it.id] ?: it.time.updated }
    }

    @Test
    fun forkAddedFrameKeepsRowVisibleThroughFollowReplay() {
        val handler = SessionEventHandler()
        // list baseline: parent row already present (REST setSessions landed first)
        handler.setSessions(
            sid,
            listOf(Session(id = parentId, directory = "/w", title = "hi", time = Session.Time(100L, 200L))),
        )

        // (1) api-session/added - device-captured shape: parentSessionId present, origin absent, real updatedAt
        val frames = mutableListOf<Triple<String, JsonObject, String>>()
        DshMuxSynthesizer(onFrame = { m, p, r -> frames += Triple(m, p, r) }).onItem(
            "evt",
            json(
                "{\"type\":\"emit\",\"event\":\"api-session/added\",\"args\":[" +
                    "{\"sessionId\":\"" + forkId + "\",\"updatedAt\":300,\"running\":false,\"blank\":false," +
                    "\"parentSessionId\":\"" + parentId + "\",\"cwd\":\"/w\"}]}",
            ),
            ConcurrentHashMap(),
        )
        assertEquals("host/session-added", frames.single().first)

        val orchestrator = DshConnectionOrchestrator()
        fun mapDefendDispatch(method: String, payload: JsonObject) {
            for (mapped in DshEventMapper.mapFrame(method, payload)) {
                val sse = mapped as? DshMappedEvent.Sse ?: continue
                val defended = orchestrator.defendDurableSubagentRemoval(
                    orchestrator.defendSessionReplacement(sse.event) { id ->
                        handler.sessions.value.firstOrNull { it.id == id }
                    },
                    { id -> handler.sessions.value.firstOrNull { it.id == id } },
                )
                handler.handle(defended, sid)
                // EventDispatcher side effect (EventDispatcher.kt:415): User message -> sort key
                if (defended is SseEvent.MessageUpdated && defended.info is Message.User) {
                    handler.recordUserMessage(defended.info.sessionId, defended.info.time.created)
                }
            }
        }

        // added frame (SessionCreated) lands in store
        mapDefendDispatch(frames.single().first, frames.single().second)

        // (2) fork follow replay: session/title + user/message with ORIGINAL time=150
        mapDefendDispatch(
            "session/event",
            json("{\"sessionId\":\"" + forkId + "\",\"event\":{\"type\":\"session/title\",\"seq\":2,\"time\":150,\"data\":{\"title\":\"hi\"}}}"),
        )
        mapDefendDispatch(
            "session/event",
            json("{\"sessionId\":\"" + forkId + "\",\"event\":{\"type\":\"user/message\",\"seq\":7,\"time\":150,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}}"),
        )

        // assertion (user symptom, cold-baseline parity): fork row visible within 5s
        // of returning to list AND at top - matching the cold re-entry dump where the
        // REST baseline ranks the fork row first (updated=09:20 > parent 09:14)
        val rows = listVisibleRows(handler)
        val forkIdx = rows.indexOfFirst { it.id == forkId }
        val diag = rows.map { it.id to it.time.updated }
        assertTrue("fork row missing from list-visible rows: " + diag, forkIdx >= 0)
        assertEquals(
            "fork row must rank first (parity with cold REST baseline ordering): " + diag,
            0,
            forkIdx,
        )
    }
}
