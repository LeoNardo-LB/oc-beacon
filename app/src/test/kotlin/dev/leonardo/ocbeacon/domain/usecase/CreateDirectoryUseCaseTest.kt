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
        // W4/D8:默认桩=后端无原生建目录(V1/V2)→既有用例全部走临时会话 shell 通道
        coEvery { fileRepository.createDirectory(any(), any(), any()) } returns Result.failure(UnsupportedOperationException("file.createDirectory"))
    }

    // ---- W4/D8(2026-09-06 全量 E2E):原生建目录优先 + 回落语义 --------------------
    // DSH directoryPicker/createDirectory 直达(无临时会话→无泄漏);V1/V2 无原生
    // 端点回落旧通道;服务器明确失败原样上抛不回落。

    @Test
    fun `native createDirectory success short-circuits temp session path`() = runTest {
        coEvery { fileRepository.createDirectory(serverId, "/parent", "newdir") } returns Result.success("/parent/newdir")

        val result = sut(serverId, "/parent", "newdir")

        assertTrue(result.isSuccess)
        assertEquals("/parent/newdir", result.getOrThrow())
        coVerify(exactly = 0) { sessionRepository.createSession(any(), any()) }
    }

    @Test
    fun `native createDirectory definitive failure surfaces without temp session`() = runTest {
        coEvery { fileRepository.createDirectory(any(), any(), any()) } returns Result.failure(IllegalStateException("directory-picker/create-failed"))

        val result = sut(serverId, "/parent", "newdir")

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { sessionRepository.createSession(any(), any()) }
    }

    @Test
    fun `native createDirectory unsupported falls back to temp session shell path`() = runTest {
        coEvery { manageTerminalUseCase.runShellCommand(any(), any(), any(), any(), any(), any()) } returns true

        val result = sut(serverId, "/parent", "newdir")

        assertTrue(result.isSuccess)
        coVerify { sessionRepository.createSession(any(), any()) }
        coVerify { sessionRepository.deleteSession(serverId, tempSession.id) }
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
        // 成功路径不应调用 executeCommand 降级
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
