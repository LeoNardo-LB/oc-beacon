package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #267（spec §3.1 验收：三态派生矩阵）：connectedServerIds × connectingServerIds
 * → Connected / Connecting（含重连退避期）/ Disconnected（两集合均缺席）。
 */
class ServerLinkStateTest {

    @Test
    fun `derive matrix`() {
        assertEquals(ServerLinkState.Connected, ServerLinkState.derive("s1", connected = setOf("s1"), connecting = setOf("s1")))
        assertEquals(ServerLinkState.Connected, ServerLinkState.derive("s1", connected = setOf("s1"), connecting = emptySet()))
        assertEquals(ServerLinkState.Connecting, ServerLinkState.derive("s1", connected = emptySet(), connecting = setOf("s1")))
        assertEquals(ServerLinkState.Disconnected, ServerLinkState.derive("s1", connected = emptySet(), connecting = emptySet()))
        // 他服务器状态不误伤本服务器（Q7a）
        assertEquals(ServerLinkState.Disconnected, ServerLinkState.derive("s1", connected = setOf("s2"), connecting = setOf("s3")))
    }
}
