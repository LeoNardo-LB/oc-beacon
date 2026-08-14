package dev.leonardo.ocbeacon.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRepositoryTest {

    @Test
    fun `interface defines listAgents, loadCommands, searchFiles`() {
        val methods = AgentRepository::class.java.declaredMethods.map { it.name }
        assertTrue("Expected listAgents in $methods", methods.any { it.startsWith("listAgents") })
        assertTrue("Expected loadCommands in $methods", methods.any { it.startsWith("loadCommands") })
        assertTrue("Expected searchFiles in $methods", methods.any { it.startsWith("searchFiles") })
    }
}
