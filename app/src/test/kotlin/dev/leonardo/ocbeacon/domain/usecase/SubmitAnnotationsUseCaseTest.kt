package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.model.AnnotationPromptBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitAnnotationsUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = SubmitAnnotationsUseCase(chatRepository)

    private fun makeAnn(index: Int) = Annotation(
        "ann-$index", index, 0, 10, 12, 1, 13, 15, "code", "fix this", 1000L
    )

    @Test
    fun `invoke builds prompt and calls promptAsync`() = runTest {
        val anns = listOf(makeAnn(0), makeAnn(1))
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        useCase("srv-1", "sess-1", anns, "请修改", "src/App.kt", "/project")

        val expected = AnnotationPromptBuilder.build(anns, "请修改", "src/App.kt", "/project")
        coVerify {
            chatRepository.promptAsync("srv-1", "sess-1",
                listOf(PromptPart(type = "text", text = expected)),
                null, null, null, "/project")
        }
    }

    @Test
    fun `invoke propagates failure`() = runTest {
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Network error"))
        val result = useCase("srv-1", "sess-1", listOf(makeAnn(0)), "", "App.kt", "/project")
        assertTrue(result.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty annotations throws`() = runTest {
        useCase("srv-1", "sess-1", emptyList(), "n", "App.kt", "/project")
    }
}
