package dev.leonardo.ocbeacon.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOwnershipRegistryTest {

    private fun newRegistry() = StreamingOwnershipRegistry()

    @Test
    fun firstClaimerWins() {
        val registry = newRegistry()
        assertTrue(registry.claim("ses_1", "srv_A"))
        assertFalse(registry.claim("ses_1", "srv_B"))  // 已被 srv_A 认领
        assertTrue(registry.claim("ses_1", "srv_A"))   // 同 server 重复认领 OK
    }

    @Test
    fun release_allowsNewClaim() {
        val registry = newRegistry()
        registry.claim("ses_1", "srv_A")
        registry.release("ses_1")

        assertTrue(registry.claim("ses_1", "srv_B"))
    }

    @Test
    fun releaseAllForServer_freesOwnedSessions() {
        val registry = newRegistry()
        registry.claim("ses_1", "srv_A")
        registry.claim("ses_2", "srv_B")
        registry.claim("ses_3", "srv_A")

        registry.releaseAllForServer("srv_A")

        assertTrue(registry.claim("ses_1", "srv_B"))  // srv_A 释放后可被认领
        assertFalse(registry.claim("ses_2", "srv_A")) // srv_B 仍持有
        assertTrue(registry.claim("ses_3", "srv_C"))
    }

    @Test
    fun clearAll_emptiesEverything() {
        val registry = newRegistry()
        registry.claim("ses_1", "srv_A")

        registry.clearAll()

        assertTrue(registry.claim("ses_1", "srv_B"))
    }
}
