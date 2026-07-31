package dev.leonardo.octether.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {

    // ============ NetworkState sealed class ============

    @Test
    fun `NetworkState Available has isOnline true`() {
        assertTrue(NetworkState.Available.isOnline)
    }

    @Test
    fun `NetworkState Losing has isOnline false`() {
        assertTrue(!NetworkState.Losing.isOnline)
    }

    @Test
    fun `NetworkState Lost has isOnline false`() {
        assertTrue(!NetworkState.Lost.isOnline)
    }

    @Test
    fun `NetworkState Unavailable has isOnline false`() {
        assertTrue(!NetworkState.Unavailable.isOnline)
    }

    @Test
    fun `NetworkState defaults to Unavailable`() {
        assertEquals(NetworkState.Unavailable, NetworkState.Unavailable)
    }
}
