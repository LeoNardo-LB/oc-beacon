package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PermissionHandlerUseCaseTest {
    private val chatRepo: ChatRepository = mockk(relaxed = true)
    private lateinit var useCase: PermissionHandlerUseCase

    @Before
    fun setup() {
        useCase = PermissionHandlerUseCase(chatRepo)
    }

    @Test
    fun `observePermissions delegates to chatRepository`() = runTest {
        val perms = listOf<PermissionState>(mockk())
        coEvery { chatRepo.getPermissionsFlow("s1") } returns flowOf(perms)
        val result = useCase.observePermissions("s1").first()
        assertEquals(perms, result)
    }

    @Test
    fun `respond with approval sends allow`() = runTest {
        coEvery { chatRepo.respondPermission("server1", "p1", "allow", null) } returns Result.success(true)
        val result = useCase.respond("server1", "p1", approved = true, message = null)
        assertTrue(result.getOrDefault(false))
    }

    @Test
    fun `respond with denial sends deny`() = runTest {
        coEvery { chatRepo.respondPermission("server1", "p1", "deny", null) } returns Result.success(false)
        val result = useCase.respond("server1", "p1", approved = false, message = null)
        assertFalse(result.getOrDefault(true))
    }
}
