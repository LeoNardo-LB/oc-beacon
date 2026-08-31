package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionPermissions 域模型测试（DSH 权限预设状态）。
 *
 * 覆盖：投影解析后 isCustom 判定（自定义态）、switchableOptions 过滤 custom 伪选项、
 * 事件早于投影基线到达（options 空）时不误判为 custom。
 */
class SessionPermissionsTest {

    private fun option(value: String, name: String = value) =
        SessionPermissions.PermissionPresetOption(value = value, name = name)

    private val threeTiers = listOf(
        option("read-only"),
        option("workspace-write"),
        option("danger-full-access"),
    )

    @Test
    fun `current value in three tiers is not custom`() {
        for (value in listOf("read-only", "workspace-write", "danger-full-access")) {
            val state = SessionPermissions(options = threeTiers, currentValue = value)
            assertFalse("value=$value", state.isCustom)
        }
    }

    @Test
    fun `explicit custom value is custom`() {
        val state = SessionPermissions(options = threeTiers, currentValue = "custom")
        assertTrue(state.isCustom)
    }

    @Test
    fun `server appended custom pseudo option still custom`() {
        // 服务端派生 custom 时会 append value="custom" 伪选项——switchableOptions 过滤后仍判定 custom
        val state = SessionPermissions(
            options = threeTiers + option("custom"),
            currentValue = "custom",
        )
        assertTrue(state.isCustom)
        // 伪选项不进入可切换列表
        assertEquals(3, state.switchableOptions.size)
    }

    @Test
    fun `unknown current value not in options is custom`() {
        val state = SessionPermissions(options = threeTiers, currentValue = "something-else")
        assertTrue(state.isCustom)
    }

    @Test
    fun `null current value is not custom`() {
        assertFalse(SessionPermissions(options = threeTiers, currentValue = null).isCustom)
    }

    @Test
    fun `empty options do not misclassify as custom before baseline arrives`() {
        // 事件（permission/preset）早于投影基线（options 未加载）——不误判 custom
        val state = SessionPermissions(options = emptyList(), currentValue = "workspace-write")
        assertFalse(state.isCustom)
        assertTrue(state.switchableOptions.isEmpty())
    }

    @Test
    fun `switchable options exclude custom but preserve presets`() {
        val state = SessionPermissions(
            options = threeTiers + option("custom", "Custom"),
            currentValue = "danger-full-access",
        )
        assertEquals(
            listOf("read-only", "workspace-write", "danger-full-access"),
            state.switchableOptions.map { it.value },
        )
    }
}