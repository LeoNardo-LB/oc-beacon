package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossServerSessionsTest {
    private val server = ServerConfig(id = "server", url = "https://example.test")
    private val category = SessionCategory(id = "category", name = "Work", color = "blue", icon = "label")

    @Test
    fun `buildCrossServerSessionsState emits only favorited sessions in stored order`() {
        val state = buildCrossServerSessionsState(
            sessionsByServer = mapOf(
                server.id to listOf(
                    session("favorite-a", updated = 2),
                    session("favorite-b", updated = 1),
                    session("regular", updated = 5),
                ),
            ),
            connectedIds = setOf(server.id),
            servers = listOf(server),
            preferences = mapOf(
                server.id to ServerSessionPreferences(
                    favoriteIds = setOf("favorite-a", "favorite-b"),
                    categoryAssignments = emptyMap(),
                ),
            ),
            order = listOf("server:favorite-b", "server:favorite-a"),
            snapshots = emptyMap(),
            categories = emptyList(),
        )
        // 存储顺序优先于更新时间戳：favorite-b 在 favorite-a 之前。
        assertEquals(
            listOf("favorite-b", "favorite-a"),
            state.items.map { it.sessionId },
        )
        // 普通会话未被收藏 → 不出现。
        assertEquals(2, state.items.size)
    }

    @Test
    fun `buildCrossServerSessionsState falls back to snapshot when server is offline`() {
        val snapshot = FavoriteSessionSnapshot("offline", "Offline Title", 1, 2)
        val state = buildCrossServerSessionsState(
            sessionsByServer = emptyMap(),
            connectedIds = emptySet(),
            servers = listOf(server),
            preferences = mapOf(
                server.id to ServerSessionPreferences(setOf("offline"), emptyMap()),
            ),
            order = listOf("server:offline"),
            snapshots = mapOf("server:offline" to snapshot),
            categories = emptyList(),
        )
        val item = state.items.single()
        assertEquals("offline", item.sessionId)
        // 会话为 null（离线）但快照存在。
        assertEquals(null, item.session)
        assertEquals(snapshot, item.snapshot)
        assertEquals(false, item.isConnected)
    }

    @Test
    fun `filterCrossServerFavorites keeps only items in the selected category`() {
        val included = item("included", isFavorite = true, category = category, favoriteIndex = 0)
        val uncategorized = item("uncategorized", isFavorite = true, category = null, favoriteIndex = 1)

        assertEquals(
            listOf("included"),
            filterCrossServerFavorites(listOf(uncategorized, included), category.id).map { it.sessionId },
        )
        // category 为 null = 所有收藏。
        assertEquals(
            listOf("included", "uncategorized"),
            filterCrossServerFavorites(listOf(uncategorized, included), null).map { it.sessionId },
        )
    }

    @Test
    fun `moveCrossServerFavoriteOrder preserves disconnected server positions`() {
        // 已断开的收藏项不在 visibleOrder 中，因此移动可见项时必须保持其位置不变。
        assertEquals(
            listOf("server-a:two", "disconnected:hidden", "server-a:one"),
            moveCrossServerFavoriteOrder(
                currentOrder = listOf("server-a:one", "disconnected:hidden", "server-a:two"),
                visibleOrder = listOf("server-a:one", "server-a:two"),
                itemKey = "server-a:one",
                offset = 1,
            ),
        )
    }

    @Test
    fun `moveCrossServerFavoriteOrder does nothing for zero offset`() {
        val order = listOf("s:a", "s:b")
        assertEquals(
            order,
            moveCrossServerFavoriteOrder(order, listOf("s:a", "s:b"), "s:a", 0),
        )
    }

    private fun session(id: String, updated: Long = 0): Session = Session(
        id = id,
        title = id,
        directory = "/project/$id",
        time = Session.Time(created = 0, updated = updated),
    )

    private fun item(
        id: String,
        isFavorite: Boolean,
        category: SessionCategory?,
        favoriteIndex: Int = 0,
    ) = CrossServerSessionItem(
        serverId = server.id,
        serverName = server.displayName,
        sessionId = id,
        session = session(id),
        snapshot = null,
        isConnected = true,
        category = category,
        isFavorite = isFavorite,
        favoriteIndex = favoriteIndex,
    )
}
