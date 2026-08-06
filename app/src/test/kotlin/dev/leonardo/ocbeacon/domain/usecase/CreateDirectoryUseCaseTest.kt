package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.CreateSessionOpts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateDirectoryUseCaseTest {

    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val fileRepository: FileRepository = mockk(relaxed = true)

    private lateinit var sut: CreateDirectoryUseCase

    private val serverId = "srv1"
    private val tempSession = Session(id = "temp-1", time = Session.Time(created = 0, updated = 0))

    @Before
    fun setup() {
        sut = CreateDirectoryUseCase(sessionRepository, manageTerminalUseCase, fileRepository)
        coEvery { sessionRepository.createSession(any(), any()) } returns Result.success(tempSession)
    }

    @Test
    fun `runShellCommand success creates directory and cleans up temp session`() = runTest {
        coEvery { manageTerminalUseCase.runShellCommand(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { fileRepository.listDirectory(any(), any(), any()) } returns Result.success(emptyList())

        val result = sut(serverId, "/parent", "newdir")

        assertTrue(result.isSuccess)
        assertEquals("/parent/newdir", result.getOrThrow())
        // R6: 临时会话必须在成功路径上被删除
        coVerify { sessionRepository.deleteSession(serverId, tempSession.id) }
        // 成功路径不应调用 executeCommand 回退
        coVerify(exactly = 0) { manageTerminalUseCase.executeCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `runShellCommand failure falls back to executeCommand`() = runTest {
        coEvery { manageTerminalUseCase.runShellCommand(any(), any(), any(), any(), any(), any()) } returns false
        coEvery { manageTerminalUseCase.executeCommand(any(), any(), any(), any(), any()) } returns true
        coEvery { fileRepository.listDirectory(any(), any(), any()) } returns Result.success(emptyList())

        val result = sut(serverId, "/parent", "newdir")

        assertTrue(result.isSuccess)
        assertEquals("/parent/newdir", result.getOrThrow())
        coVerify { sessionRepository.deleteSession(serverId, tempSession.id) }
    }

    @Test
    fun `temp session is cleaned up even when both shell and execute fail`() = runTest {
        // R6: finally 清理必须在任何路径上执行
        coEvery { manageTerminalUseCase.runShellCommand(any(), any(), any(), any(), any(), any()) } returns false
        coEvery { manageTerminalUseCase.executeCommand(any(), any(), any(), any(), any()) } returns false

        val result = sut(serverId, "/parent", "newdir")

        assertFalse(result.isSuccess)
        // 即使创建失败，临时会话也必须被删除
        coVerify { sessionRepository.deleteSession(serverId, tempSession.id) }
    }

    @Test
    fun `invalid folder name returns failure without creating session`() = runTest {
        val result = sut(serverId, "/parent", "..")

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { sessionRepository.createSession(any(), any()) }
    }

    @Test
    fun `createSession is called with mkdir title and parent directory`() = runTest {
        coEvery { manageTerminalUseCase.runShellCommand(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { fileRepository.listDirectory(any(), any(), any()) } returns Result.success(emptyList())

        sut(serverId, "/parent", "newdir")

        coVerify {
            sessionRepository.createSession(
                serverId,
                match { it.title == "mkdir" && it.directory == "/parent" }
            )
        }
    }
}
