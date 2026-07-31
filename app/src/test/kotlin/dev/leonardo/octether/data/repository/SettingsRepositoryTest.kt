package dev.leonardo.octether.data.repository

import dev.leonardo.octether.domain.model.Draft
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Characterization Tests for SettingsDataStore's public API contract.
 * Validates that all Flow properties exist with correct types and
 * all setter functions exist with correct signatures.
 *
 * Uses Java reflection (java.lang.reflect) to avoid kotlin-reflect dependency.
 * These tests ensure the public API surface doesn't regress during refactoring.
 */
class SettingsDataStoreTest {

    // ============ Flow Property Contracts ============

    @Test
    fun `all expected Flow properties exist as getter methods`() {
        val methods = SettingsDataStore::class.java.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }.toSet()

        // Kotlin properties compile to getXxx() methods
        val expectedProperties = listOf(
            "getAppLanguage", "getAppTheme", "getDynamicColor", "getChatFontSize",
            "getChatDensity",
            "getNotificationsEnabled", "getInitialMessageCount", "getCodeWordWrap",
            "getConfirmBeforeSend", "getAmoledDark", "getCompactMessages", "getCollapseTools",
            "getExpandReasoning", "getHapticFeedback", "getReconnectMode", "getKeepScreenOn",
            "getSilentNotifications", "getCompressImageAttachments",
            "getImageAttachmentMaxLongSide", "getImageAttachmentWebpQuality",
            "getShowLocalRuntime", "getTerminalFontSize", "getLocalSetupCompleted",
            "getLocalProxyEnabled", "getLocalProxyUrl", "getLocalProxyNoProxy",
            "getLocalServerAllowLan", "getLocalServerUsername", "getLocalServerPassword",
            "getLocalServerRunInBackground", "getLocalServerAutoStart",
            "getLocalServerStartupTimeoutSec"
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

    // ============ Setter Function Contracts ============

    @Test
    fun `all expected setter functions exist`() {
        val methods = SettingsDataStore::class.java.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }.toSet()

        val expectedSetters = listOf(
            "setAppLanguage", "setAppTheme", "setDynamicColor", "setChatFontSize",
            "setChatDensity",
            "setNotificationsEnabled", "setInitialMessageCount", "setCodeWordWrap",
            "setConfirmBeforeSend", "setAmoledDark", "setCompactMessages",
            "setCollapseTools", "setExpandReasoning", "setHapticFeedback",
            "setReconnectMode", "setKeepScreenOn", "setSilentNotifications",
            "setCompressImageAttachments", "setImageAttachmentMaxLongSide",
            "setImageAttachmentWebpQuality", "setShowLocalRuntime",
            "setTerminalFontSize", "setLocalSetupCompleted", "setLocalProxyEnabled",
            "setLocalProxyUrl", "setLocalProxyNoProxy", "setLocalServerAllowLan",
            "setLocalServerUsername", "setLocalServerPassword",
            "setLocalServerRunInBackground", "setLocalServerAutoStart",
            "setLocalServerStartupTimeoutSec", "setModelVisibility"
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
        // Kotlin suspend functions get an extra Continuation parameter at the JVM level.
        // setModelVisibility(serverId, providerId, modelId, visible) → 4 params + Continuation
        val method = SettingsDataStore::class.java.getDeclaredMethod(
            "setModelVisibility",
            String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java
        )
        assertNotNull("setModelVisibility should exist with expected suspend signature", method)
        // JVM level has 5 params (4 value + 1 continuation), but logically 4 value params
        assertEquals("setModelVisibility should have 4 value params + 1 continuation", 5, method.parameterCount)
    }

    // ============ DraftRepository Contract ============

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

    // ============ ServerConfig Contract ============

    @Test
    fun `ServerConfig displayName uses explicit name when set`() {
        val config = dev.leonardo.octether.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096",
            name = "My Server"
        )
        assertEquals("My Server", config.displayName)
    }

    @Test
    fun `ServerConfig displayName falls back to url when name is null`() {
        val config = dev.leonardo.octether.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096",
            name = null
        )
        // displayName = name ?: url → when name is null, returns full url
        assertEquals("http://192.168.1.100:4096", config.displayName)
    }

    @Test
    fun `ServerConfig host extracts from url`() {
        val config = dev.leonardo.octether.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096"
        )
        assertEquals("192.168.1.100", config.host)
    }

    @Test
    fun `ServerConfig port extracts from url`() {
        val config = dev.leonardo.octether.domain.model.ServerConfig(
            id = "test",
            url = "http://192.168.1.100:4096"
        )
        assertEquals(4096, config.port)
    }
}
