package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #311 Task1 mapper 分支钉死：workspace/follow 合成帧（workspace/baseline ·
 * workspace/archived——DshMuxSynthesizer 产物）→ SseEvent。
 *
 * 三步接线之一（mapper 产事件；EventDispatcher bind 与 DshWorkspaceHandler 折叠
 * 见 EventDispatcherWorkspace311Test——goal 漏 bind 前车之鉴 #310③ 同款纪律）。
 */
class DshEventMapperWorkspace311Test {

    private fun frame(method: String, payloadJson: String): List<DshMappedEvent> =
        DshEventMapper.mapFrame(method, Json.parseToJsonElement(payloadJson) as JsonObject)

    @Test
    fun `workspace baseline frame maps to WorkspaceSnapshotChanged`() {
        val events = frame(
            "workspace/baseline",
            """{"items":[
                {"workspaceId":"ws-1","path":"/home/leo/proj","title":"Beacon","sessionIds":["s-1","s-2"]},
                {"workspaceId":"ws-2","path":"/srv/ops","title":"Ops","sessionIds":[]}
               ],"archivedSessionIds":["s-9"]}""",
        )
        assertEquals(1, events.size)
        val event = (events[0] as DshMappedEvent.Sse).event as SseEvent.WorkspaceSnapshotChanged
        assertEquals(2, event.workspaces.size)
        // 名字键=title（契约 ①-d）；归属=sessionIds 显式数组
        assertEquals("Beacon", event.workspaces[0].title)
        assertEquals(listOf("s-1", "s-2"), event.workspaces[0].sessionIds)
        assertEquals(listOf("s-9"), event.archivedSessionIds)
    }

    @Test
    fun `workspace archived frame maps to WorkspaceArchivedChanged`() {
        val events = frame(
            "workspace/archived",
            """{"archivedSessionIds":["s-2","s-9"]}""",
        )
        assertEquals(1, events.size)
        val event = (events[0] as DshMappedEvent.Sse).event as SseEvent.WorkspaceArchivedChanged
        // 集合替换式：帧即完整新集合（非增量合并）
        assertEquals(listOf("s-2", "s-9"), event.archivedSessionIds)
    }

    /** #311 Task3：workspace/upsert（engine 合成帧）→ WorkspaceUpserted——
     * 对话框消费 title/sessionIds 实时性的单一事件载体。 */
    @Test
    fun `workspace upsert frame maps to WorkspaceUpserted`() {
        val events = frame(
            "workspace/upsert",
            """{"workspace":{"workspaceId":"ws-1","path":"/home/leo/proj","title":"Renamed","sessionIds":["s-1","s-2","s-3"]}}""",
        )
        assertEquals(1, events.size)
        val event = (events[0] as DshMappedEvent.Sse).event as SseEvent.WorkspaceUpserted
        assertEquals("ws-1", event.workspace.workspaceId)
        assertEquals("Renamed", event.workspace.title)
        assertEquals(listOf("s-1", "s-2", "s-3"), event.workspace.sessionIds)
    }

    @Test
    fun `workspace frames with malformed payload are ignored`() {
        // baseline 缺 items/archivedSessionIds 键 → MALFORMED
        val baseline = frame("workspace/baseline", """{}""")
        assertTrue(baseline.single() is DshMappedEvent.Ignored)
        // archived 缺 archivedSessionIds → MALFORMED
        val archived = frame("workspace/archived", """{"other":1}""")
        assertTrue(archived.single() is DshMappedEvent.Ignored)
        // upsert 缺 workspace 键 / 非对象 → MALFORMED（#311 Task3）
        assertTrue(frame("workspace/upsert", """{}""").single() is DshMappedEvent.Ignored)
        assertTrue(
            frame("workspace/upsert", """{"workspace":"not-an-object"}""").single() is DshMappedEvent.Ignored,
        )
    }

    /** 畸形 item 行（缺 workspaceId）丢弃不崩——行级容错（对齐 jobs/queue mapper）。 */
    @Test
    fun `workspace baseline drops malformed item rows`() {
        val events = frame(
            "workspace/baseline",
            """{"items":[
                {"path":"/no-id","title":"X"},
                {"workspaceId":"ws-ok","path":"/ok","title":"Ok"}
               ],"archivedSessionIds":[]}""",
        )
        val event = (events[0] as DshMappedEvent.Sse).event as SseEvent.WorkspaceSnapshotChanged
        assertEquals(listOf("ws-ok"), event.workspaces.map { it.workspaceId })
    }
}
