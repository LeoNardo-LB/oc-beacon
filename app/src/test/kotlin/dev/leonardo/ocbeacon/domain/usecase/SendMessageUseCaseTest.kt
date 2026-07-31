package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SendMessageUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = SendMessageUseCase(chatRepository)

    @Test
    fun `sendPrompt delegates to chatRepository`() = runTest {
        val parts = listOf(PromptPart(type = "text", text = "Hello"))
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        useCase.sendPrompt(
            serverId = "server1",
            sessionId = "s1",
            parts = parts,
            model = null,
            agent = "build",
            variant = null,
            directory = null
        )

        coVerify { chatRepository.promptAsync("server1", "s1", parts, null, "build", null, null) }
    }

    @Test
    fun `sendPrompt propagates exception`() = runTest {
        val parts = listOf(PromptPart(type = "text", text = "Hello"))
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("Network error"))

        var caught = false
        try {
            useCase.sendPrompt(
                serverId = "server1",
                sessionId = "s1",
                parts = parts,
                model = ModelSelection("p1", "m1"),
                agent = "build",
                variant = null,
                directory = null
            )
        } catch (e: RuntimeException) {
            caught = true
        }
        assert(caught)
    }
}
