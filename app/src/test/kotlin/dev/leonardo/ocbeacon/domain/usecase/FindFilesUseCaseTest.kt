package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.FileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindFilesUseCaseTest {
    private val fileRepository: FileRepository = mockk()
    private val useCase = FindFilesUseCase(fileRepository)

    @Test
    fun `invoke delegates to repository with same args`() = runTest {
        val expected = listOf("src/Main.kt", "docs/README.md")
        coEvery { fileRepository.findFiles("srv-1", "/dir", "Main", 50) } returns Result.success(expected)

        val result = useCase("srv-1", "/dir", "Main")

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify(exactly = 1) { fileRepository.findFiles("srv-1", "/dir", "Main", 50) }
    }

    @Test
    fun `invoke passes through custom limit`() = runTest {
        coEvery { fileRepository.findFiles(any(), any(), any(), any()) } returns Result.success(emptyList())

        useCase("srv", "/dir", "q", 10)

        coVerify { fileRepository.findFiles("srv", "/dir", "q", 10) }
    }
}
