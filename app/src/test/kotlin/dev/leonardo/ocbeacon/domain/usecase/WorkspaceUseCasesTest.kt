package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.FileType
import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.VcsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceUseCasesTest {

    private val fileRepository: FileRepository = mockk()
    private val vcsRepository: VcsRepository = mockk()

    private val listDirectoryUseCase = ListDirectoryUseCase(fileRepository)
    private val getFileContentUseCase = GetFileContentUseCase(fileRepository)
    private val getVcsStatusUseCase = GetVcsStatusUseCase(vcsRepository)
    private val getFileDiffUseCase = GetFileDiffUseCase(vcsRepository)

    @Test
    fun `ListDirectoryUseCase delegates to FileRepository listDirectory with same args`() = runTest {
        val serverId = "server-uuid-1234"
        val directory = "/path/to/project"
        val path = "src/main/kotlin"
        val expected = Result.success(listOf(
            FileNode(name = "App.kt", path = "src/main/kotlin/App.kt", absolute = "/path/to/project/src/main/kotlin/App.kt", type = FileType.FILE, ignored = false)
        ))
        coEvery { fileRepository.listDirectory(serverId, directory, path) } returns expected

        val result = listDirectoryUseCase(serverId, directory, path)

        assertEquals(expected, result)
        coVerify { fileRepository.listDirectory(serverId, directory, path) }
    }

    @Test
    fun `GetFileContentUseCase delegates to FileRepository getFileContent with same args`() = runTest {
        val serverId = "server-uuid-1234"
        val directory = "/path/to/project"
        val path = "src/main/kotlin/App.kt"
        val expected = Result.success(FileContent(
            path = path,
            type = ContentType.TEXT,
            content = "fun main() {}"
        ))
        coEvery { fileRepository.getFileContent(serverId, directory, path) } returns expected

        val result = getFileContentUseCase(serverId, directory, path)

        assertEquals(expected, result)
        coVerify { fileRepository.getFileContent(serverId, directory, path) }
    }

    @Test
    fun `GetVcsStatusUseCase delegates to VcsRepository getStatus with same args`() = runTest {
        val serverId = "server-uuid-1234"
        val directory = "/path/to/project"
        val expected = Result.success(listOf(
            VcsChange(file = "App.kt", additions = 5, deletions = 2, status = VcsStatus.MODIFIED)
        ))
        coEvery { vcsRepository.getStatus(serverId, directory) } returns expected

        val result = getVcsStatusUseCase(serverId, directory)

        assertEquals(expected, result)
        coVerify { vcsRepository.getStatus(serverId, directory) }
    }

    @Test
    fun `GetFileDiffUseCase delegates to VcsRepository getDiff with default mode and context`() = runTest {
        val serverId = "server-uuid-1234"
        val directory = "/path/to/project"
        val expected = Result.success(listOf(
            VcsFileDiff(file = "App.kt", patch = "@@ -1,3 +1,4 @@\n+import foo\n", additions = 1, deletions = 0, status = VcsStatus.MODIFIED)
        ))
        coEvery { vcsRepository.getDiff(serverId, directory, VcsDiffMode.GIT, 3) } returns expected

        val result = getFileDiffUseCase(serverId, directory)

        assertEquals(expected, result)
        coVerify { vcsRepository.getDiff(serverId, directory, VcsDiffMode.GIT, 3) }
    }
}
