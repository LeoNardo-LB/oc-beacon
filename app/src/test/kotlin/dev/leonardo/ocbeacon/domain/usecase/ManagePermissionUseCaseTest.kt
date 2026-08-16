package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagePermissionUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = ManagePermissionUseCase(chatRepository)

    @Test
    fun `replyToPermission delegates to chatRepository and returns true`() = runTest {
        coEvery { chatRepository.respondPermission("server1", "ses_x", "p1", "allow", any()) } returns Result.success(true)

        val result = useCase.replyToPermission("server1", "ses_x", "p1", "allow", null)

        assertTrue(result)
    }

    @Test
    fun `replyToPermission delegates to chatRepository and returns false`() = runTest {
        coEvery { chatRepository.respondPermission("server1", "ses_x", "p1", "deny", any()) } returns Result.success(false)

        val result = useCase.replyToPermission("server1", "ses_x", "p1", "deny", null)

        assertEquals(false, result)
    }

    @Test
    fun `listPendingPermissions delegates to chatRepository`() = runTest {
        val permissions = listOf(
            PermissionState(id = "p1", sessionId = "s1", permission = "file_read")
        )
        coEvery { chatRepository.listPendingPermissions("server1", null) } returns Result.success(permissions)

        val result = useCase.listPendingPermissions("server1", null)

        assertEquals(1, result.size)
        assertEquals("p1", result[0].id)
    }
}
