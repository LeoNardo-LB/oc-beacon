package dev.leonardo.ocbeacon.ui.extension

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片5：插槽注册表的过滤 / 排序 / 槽位隔离契约。
 *
 * 只断言外部行为（贡献筛选结果），不渲染 Compose 内容。
 */
class ServerUiSlotRegistryTest {

    private val capsWithTerminal = ServerCapabilities(
        coreFlags = CoreFlags(false, false, false, false),
        features = setOf(ServerFeatures.TERMINAL),
    )
    private val capsWithout = ServerCapabilities(
        coreFlags = CoreFlags(false, false, false, false),
        features = emptySet(),
    )

    private fun extension(
        slot: ServerUiSlot,
        order: Int = 0,
        name: String = "ext",
        enabled: (ServerCapabilities) -> Boolean = { true },
    ): ServerUiExtension = object : ServerUiExtension {
        override val slot: ServerUiSlot = slot
        override val order: Int = order
        override fun isEnabled(caps: ServerCapabilities): Boolean = enabled(caps)
        override fun toString(): String = name
        @Composable
        override fun Content(host: ServerUiSlotHost) = Unit
    }

    @Test
    fun `contributions sort by order within a slot`() {
        val late = extension(ServerUiSlot.PROVIDER_SETTINGS, order = 200, name = "late")
        val early = extension(ServerUiSlot.PROVIDER_SETTINGS, order = 10, name = "early")
        val registry = ServerUiSlotRegistry(setOf(late, early))

        assertEquals(
            listOf("early", "late"),
            registry.contributions(ServerUiSlot.PROVIDER_SETTINGS, capsWithout).map { it.toString() },
        )
    }

    @Test
    fun `contributions are filtered by capability bits`() {
        val gated = extension(
            ServerUiSlot.PROVIDER_SETTINGS,
            name = "gated",
            enabled = { ServerFeatures.TERMINAL in it },
        )
        val always = extension(ServerUiSlot.PROVIDER_SETTINGS, name = "always")
        val registry = ServerUiSlotRegistry(setOf(gated, always))

        assertEquals(
            listOf("gated", "always"),
            registry.contributions(ServerUiSlot.PROVIDER_SETTINGS, capsWithTerminal).map { it.toString() },
        )
        assertEquals(
            listOf("always"),
            registry.contributions(ServerUiSlot.PROVIDER_SETTINGS, capsWithout).map { it.toString() },
        )
    }

    @Test
    fun `slots are isolated`() {
        val provider = extension(ServerUiSlot.PROVIDER_SETTINGS, name = "provider")
        val header = extension(ServerUiSlot.SESSION_LIST_HEADER, name = "header")
        val registry = ServerUiSlotRegistry(setOf(provider, header))

        assertEquals(listOf("provider"), registry.contributions(ServerUiSlot.PROVIDER_SETTINGS, capsWithout).map { it.toString() })
        assertEquals(listOf("header"), registry.contributions(ServerUiSlot.SESSION_LIST_HEADER, capsWithout).map { it.toString() })
        assertEquals(setOf(ServerUiSlot.PROVIDER_SETTINGS, ServerUiSlot.SESSION_LIST_HEADER), registry.registeredSlots())
    }

    @Test
    fun `empty registry yields no contributions`() {
        val registry = ServerUiSlotRegistry(emptySet())
        assertTrue(registry.registeredSlots().isEmpty())
        assertTrue(registry.contributions(ServerUiSlot.PROVIDER_SETTINGS, capsWithout).isEmpty())
    }
}
