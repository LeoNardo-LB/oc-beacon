package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Draft
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * SettingsDataStore 公共 API 契约的特征测试。
 * 验证所有 Flow 属性存在且类型正确，
 * 所有 setter 函数存在且签名正确。
 *
 * 使用 Java 反射（java.lang.reflect）以避免 kotlin-reflect 依赖。
 * 这些测试确保重构期间公共 API 表面不会倒退。
 */
class SettingsDataStoreTest {

    // ============ Flow 属性契约 ============

    @Test
    fun `all expected Flow properties exist as getter methods`() {
        val methods = SettingsDataStore::class.java.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }.toSet()

        // Kotlin 属性编译为 getXxx() 方法
        val expectedProperties = listOf(
            "getAppLanguage", "getAppTheme", "getDynamicColor", "getChatFontSize",
            "getChatDensity",
            "getNotificationsEnabled", "getInitialMessageCount",
            "getConfirmBeforeSend", "getAmoledDark", "getCompactMessages", "getCollapseTools",
            "getExpandReasoning", "getHapticFeedback", "getReconnectMode", "getKeepScreenOn",
            "getSilentNotifications", "getCompressImageAttachments",
            "getImageAttachmentMaxLongSide", "getImageAttachmentWebpQuality",
            "getTerminalFontSize"
        )

        for (getter in expectedProperties) {
            assertTrue(
                "Missing property getter: $getter. Available: ${methods.sorted()}",
                methods.contains(getter)
            )
        }
    }

    @Test
    fun `hiddenModels is a function with correct parameter count`() {
        val method = SettingsDataStore::class.java.getDeclaredMethod("hiddenModels", String::class.java)
        assertNotNull("hiddenModels(String) should exist", method)
        assertEquals(1, method.parameterCount)
    }

    // ============ Setter 函数契约 ============

    @Test
    fun `all expected setter functions exist`() {
        val methods = SettingsDataStore::class.java.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }.toSet()

        val expectedSetters = listOf(
            "setAppLanguage", "setAppTheme", "setDynamicColor", "setChatFontSize",
            "setChatDensity",
            "setNotificationsEnabled", "setInitialMessageCount",
            "setConfirmBeforeSend", "setAmoledDark", "setCompactMessages",
            "setCollapseTools", "setExpandReasoning", "setHapticFeedback",
            "setReconnectMode", "setKeepScreenOn", "setSilentNotifications",
            "setCompressImageAttachments", "setImageAttachmentMaxLongSide",
            "setImageAttachmentWebpQuality", "setTerminalFontSize",
            "setModelVisibility"
        )

        for (setter in expectedSetters) {
            assertTrue(
                "Missing setter function: $setter. Available: ${methods.sorted()}",
                methods.contains(setter)
            )
        }
    }

    @Test
    fun `setModelVisibility has correct parameter count`() {
        // Kotlin suspend 函数在 JVM 层面会多一个 Continuation 参数。
        // setModelVisibility(serverId, providerId, modelId, visible) → 4 个参数 + Continuation
        val method = SettingsDataStore::class.java.getDeclaredMethod(
            "setModelVisibility",
            String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java
        )
        assertNotNull("setModelVisibility should exist with expected suspend signature", method)
        // JVM 层面有 5 个参数（4 个值 + 1 个 continuation），但逻辑上是 4 个值参数
        assertEquals("setModelVisibility should have 4 value params + 1 continuation", 5, method.parameterCount)
    }

    // ============ DraftRepository 契约 ============

    @Test
    fun `Draft default instance is empty`() {
        val draft = Draft()
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `Draft with text is not empty`() {
        val draft = Draft(text = "hello")
        assertFalse(draft.isEmpty)
    }

    @Test
    fun `Draft with only whitespace text is empty`() {
        val draft = Draft(text = "   ")
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `Draft with selectedAgent is not empty`() {
        val draft = Draft(selectedAgent = "build")
        assertFalse(draft.isEmpty)
    }

    @Test
    fun `Draft with blank selectedAgent is empty`() {
        val draft = Draft(selectedAgent = "   ")
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `Draft with selectedVariant is not empty`() {
        val draft = Draft(selectedVariant = "thinking")
        assertFalse(draft.isEmpty)
    }

    @Test
    fun `Draft with blank selectedVariant is empty`() {
        val draft = Draft(selectedVariant = "  ")
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `Draft with imageUris is not empty`() {
        val draft = Draft(imageUris = listOf("content://media/1"))
        assertFalse(draft.isEmpty)
    }

    @Test
    fun `Draft with confirmedFilePaths is not empty`() {
        val draft = Draft(confirmedFilePaths = listOf("/sdcard/file.txt"))
        assertFalse(draft.isEmpty)
    }

    // ============ ServerConfig 契约 ============

    @Test
    fun `ServerConfig displayName uses explicit name when set`() {
        val config = dev.leonardo.ocbeacon.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096",
            name = "My Server"
        )
        assertEquals("My Server", config.displayName)
    }

    @Test
    fun `ServerConfig displayName falls back to url when name is null`() {
        val config = dev.leonardo.ocbeacon.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096",
            name = null
        )
        // displayName = name ?: url → 当 name 为 null 时，返回完整 url
        assertEquals("http://192.168.1.100:4096", config.displayName)
    }

    @Test
    fun `ServerConfig host extracts from url`() {
        val config = dev.leonardo.ocbeacon.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096"
        )
        assertEquals("192.168.1.100", config.host)
    }

    @Test
    fun `ServerConfig port extracts from url`() {
        val config = dev.leonardo.ocbeacon.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096"
        )
        assertEquals(4096, config.port)
    }
}
