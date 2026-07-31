package dev.leonardo.octether.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRepositoryTest {

    @Test
    fun `interface defines connectTerminal, sendInput and resize`() {
        val methods = TerminalRepository::class.java.declaredMethods.map { it.name }
        assertTrue(methods.any { it.startsWith("connectTerminal") })
        assertTrue(methods.any { it.startsWith("sendInput") })
        assertTrue(methods.any { it.startsWith("resize") })
    }
}
