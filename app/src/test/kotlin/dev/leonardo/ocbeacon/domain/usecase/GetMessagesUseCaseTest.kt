package dev.leonardo.ocbeacon.domain.usecase

import app.cash.turbine.test
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetMessagesUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = GetMessagesUseCase(chatRepository)

    @Test
    fun `invoke emits messages from repository`() = runTest {
        val messages = listOf<Message>(mockk())
        every { chatRepository.getMessagesFlow("s1") } returns flowOf(messages)

        useCase("s1").test {
            assertEquals(messages, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list`() = runTest {
        every { chatRepository.getMessagesFlow("s1") } returns flowOf(emptyList())

        useCase("s1").test {
            assertEquals(emptyList<Message>(), awaitItem())
            awaitComplete()
        }
    }
}
