package dev.leonardo.octether.domain.usecase

import app.cash.turbine.test
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.domain.repository.ServerRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetServerListUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = GetServerListUseCase(serverRepository)

    @Test
    fun `invoke emits server list from repository`() = runTest {
        val servers = listOf(
            ServerConfig(id = "srv1", url = "http://192.168.1.100:4096", name = "Home Server"),
            ServerConfig(id = "srv2", url = "http://10.0.0.1:4096", name = "Work Server")
        )
        every { serverRepository.getServersFlow() } returns flowOf(servers)

        useCase().test {
            assertEquals(servers, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list when no servers configured`() = runTest {
        every { serverRepository.getServersFlow() } returns flowOf(emptyList())

        useCase().test {
            assertEquals(emptyList<ServerConfig>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke reflects server list changes`() = runTest {
        val first = listOf(ServerConfig(id = "srv1", url = "http://host1:4096"))
        val second = listOf(
            ServerConfig(id = "srv1", url = "http://host1:4096"),
            ServerConfig(id = "srv2", url = "http://host2:4096")
        )
        every { serverRepository.getServersFlow() } returns kotlinx.coroutines.flow.flow {
            emit(first)
            emit(second)
        }

        useCase().test {
            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            awaitComplete()
        }
    }
}
