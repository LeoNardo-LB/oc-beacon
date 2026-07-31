package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRepositoryImplTest {
    private val systemApi: SystemApi = mockk(relaxed = true)
    private val fileApi: FileApi = mockk(relaxed = true)
    private val serverRepo: ServerDataStore = mockk(relaxed = true)

    @Test
    fun `impl creates successfully`() = runTest {
        val repo: AgentRepository = AgentRepositoryImpl(systemApi, fileApi, serverRepo)
        assertNotNull(repo)
    }
}
