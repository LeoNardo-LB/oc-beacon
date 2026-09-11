package dev.leonardo.ocbeacon.ui.extension

import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #391 切片9：条目动作贡献注册表——能力过滤 + 排序 + 表面分组。 */
class ServerActionRegistryTest {

    private fun caps(features: Set<ServerFeature>): ServerCapabilities =
        ServerCapabilities(CoreFlags(false, false, false, false), features)

    private fun action(id: String, feature: ServerFeature? = null, order: Int = 0, surface: ServerActionSurface = ServerActionSurface.CHAT_FAB) =
        SimpleServerAction(surface, id, feature, order)

    @Test
    fun `feature gated actions appear only when the capability is present`() {
        val registry = ServerActionRegistry(
            setOf(
                action("TODO", order = 10),
                action("GOAL", ServerFeatures.GOALS, order = 30),
                action("SHELL", ServerFeatures.TERMINAL, order = 40),
            )
        )
        assertEquals(
            listOf("TODO", "SHELL"),
            registry.actions(ServerActionSurface.CHAT_FAB, caps(setOf(ServerFeatures.TERMINAL))).map { it.actionId },
        )
        assertEquals(
            listOf("TODO", "GOAL", "SHELL"),
            registry.actions(ServerActionSurface.CHAT_FAB, caps(setOf(ServerFeatures.GOALS, ServerFeatures.TERMINAL))).map { it.actionId },
        )
    }

    @Test
    fun `contributions are ordered by declared order`() {
        val registry = ServerActionRegistry(
            setOf(action("b", order = 20), action("a", order = 10), action("c", order = 30))
        )
        assertEquals(listOf("a", "b", "c"), registry.actions(ServerActionSurface.CHAT_FAB, caps(emptySet())).map { it.actionId })
    }

    @Test
    fun `surfaces are grouped independently`() {
        val registry = ServerActionRegistry(setOf(action("fab")))
        assertEquals(setOf(ServerActionSurface.CHAT_FAB), registry.registeredSurfaces())
        assertTrue(registry.actions(ServerActionSurface.CHAT_FAB, caps(emptySet())).isNotEmpty())
    }
}
