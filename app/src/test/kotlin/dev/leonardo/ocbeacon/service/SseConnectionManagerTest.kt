package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SseConnectionManagerTest {
    @Test
    fun `connections map is ConcurrentHashMap`() {
        val field = SseConnectionManager::class.java.getDeclaredField("connections")
        assertTrue(
            "connections should be ConcurrentHashMap",
            ConcurrentHashMap::class.java.isAssignableFrom(field.type)
        )
    }

    @Test
    fun `timeoutTrackers map is ConcurrentHashMap`() {
        val field = SseConnectionManager::class.java.getDeclaredField("timeoutTrackers")
        assertTrue(
            "timeoutTrackers should be ConcurrentHashMap",
            ConcurrentHashMap::class.java.isAssignableFrom(field.type)
        )
    }
}
