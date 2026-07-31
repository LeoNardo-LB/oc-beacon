package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.QuestionState
import dev.leonardo.octether.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuestionHandlerUseCaseTest {
    private val chatRepo: ChatRepository = mockk(relaxed = true)
    private lateinit var useCase: QuestionHandlerUseCase

    @Before
    fun setup() {
        useCase = QuestionHandlerUseCase(chatRepo)
    }

    @Test
    fun `observeQuestions delegates to chatRepository`() = runTest {
        val questions = listOf<QuestionState>(mockk())
        coEvery { chatRepo.getQuestionsFlow("s1") } returns flowOf(questions)
        val result = useCase.observeQuestions("s1").first()
        assertEquals(questions, result)
    }
}
