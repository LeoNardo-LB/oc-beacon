package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessagePaginationUseCaseTest {
    private val chatRepo: ChatRepository = mockk(relaxed = true)
    private val sessionRepo: SessionRepository = mockk(relaxed = true)
    private lateinit var useCase: MessagePaginationUseCase

    @Before
    fun setup() {
        useCase = MessagePaginationUseCase(chatRepo, sessionRepo)
    }

    @Test
    fun `observeMessages delegates to chatRepository`() = runTest {
        val messages = listOf<Message>(mockk())
        coEvery { chatRepo.getMessagesFlow("session1") } returns flowOf(messages)
        val result = useCase.observeMessages("session1").first()
        assertEquals(messages, result)
    }

    @Test
    fun `loadOlderMessages delegates to sessionRepository`() = runTest {
        coEvery { sessionRepo.listMessages("server1", "session1", 50) } returns Result.success(emptyList())
        val result = useCase.loadOlderMessages("server1", "session1", 50)
        assertEquals(Result.success(emptyList<MessageWithParts>()), result)
    }
}
