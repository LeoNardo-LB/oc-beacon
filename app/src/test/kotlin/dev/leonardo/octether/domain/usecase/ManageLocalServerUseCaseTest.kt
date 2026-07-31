package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.LocalServerState
import dev.leonardo.octether.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageLocalServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ManageLocalServerUseCase(serverRepository)

    @Test
    fun `getState returns local server state`() = runTest {
        val state = LocalServerState(status = "running")
        coEvery { serverRepository.getLocalServerState() } returns Result.success(state)

        val result = useCase.getState()

        assertTrue(result.isSuccess)
        assertEquals("running", result.getOrNull()?.status)
    }

    @Test
    fun `getSetupCommand returns command`() {
        every { serverRepository.getLocalSetupCommand() } returns "curl -fsSL https://example.com/setup.sh | bash"

        val cmd = useCase.getSetupCommand()

        assertTrue(cmd.contains("setup"))
    }
}
