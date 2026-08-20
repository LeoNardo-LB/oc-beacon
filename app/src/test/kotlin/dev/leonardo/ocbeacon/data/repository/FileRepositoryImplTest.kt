package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.response.FileContentDto
import dev.leonardo.ocbeacon.data.dto.response.FileNodeDto
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileRepositoryImplTest {

    private val api: FileApi = mockk()
    private val systemApi: SystemApi = mockk()
    private val serverRepository: ServerRepository = mockk()
    private lateinit var sut: FileRepository

    private val testConn = ServerConnection.from("http://localhost:4096", "opencode", "password")
    private val serverId = "server-1"

    @Before
    fun setup() {
        coEvery { serverRepository.resolveConnection(serverId) } returns testConn
        sut = FileRepositoryImpl(api, systemApi, serverRepository)
    }

    @Test
    fun `listDirectory success maps DTOs and passes directory`() = runTest {
        val dtos = listOf(
            FileNodeDto(
                name = "OpenCodeApi.kt",
                path = "data/api/OpenCodeApi.kt",
                type = "file",
                absolute = "/abs/path/OpenCodeApi.kt",
                ignored = false
            ),
            FileNodeDto(
                name = "dto",
                path = "data/dto",
                type = "directory",
                absolute = "/abs/path/dto",
                ignored = false
            )
        )
        coEvery { api.listDirectory(testConn, "src", "project") } returns dtos

        val result = sut.listDirectory(serverId, "project", "src")

        assertTrue(result.isSuccess)
        val nodes = result.getOrThrow()
        assertEquals(2, nodes.size)
        assertEquals("OpenCodeApi.kt", nodes[0].name)
        assertEquals("data/api/OpenCodeApi.kt", nodes[0].path)
        assertEquals("dto", nodes[1].name)
    }

    @Test
    fun `listDirectory wraps exception as failure`() = runTest {
        coEvery { api.listDirectory(any(), any(), any()) } throws RuntimeException("network error")

        val result = sut.listDirectory(serverId, "project", "src")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("network error"))
    }

    @Test
    fun `getFileContent success injects path`() = runTest {
        val dto = FileContentDto(
            type = "text",
            content = "package dev.leonardo.ocbeacon...",
            encoding = "utf-8"
        )
        coEvery { api.readFile(testConn, "data/api/OpenCodeApi.kt", "project") } returns dto

        val result = sut.getFileContent(serverId, "project", "data/api/OpenCodeApi.kt")

        assertTrue(result.isSuccess)
        val content: FileContent = result.getOrThrow()
        assertEquals("data/api/OpenCodeApi.kt", content.path)
        assertEquals("package dev.leonardo.ocbeacon...", content.content)
    }

    @Test
    fun `getFileContent wraps exception as failure`() = runTest {
        coEvery { api.readFile(any(), any(), any()) } throws RuntimeException("read failed")

        val result = sut.getFileContent(serverId, "project", "data/api/OpenCodeApi.kt")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("read failed"))
    }

    @Test
    fun `findFiles success passes query limit directory and returns string list`() = runTest {
        val expectedPaths = listOf(
            "app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt",
            "app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt",
            "docs/archive/specs/2026-06-18-workspace-file-viewer-design.md"
        )
        coEvery {
            api.findFiles(testConn, query = "Screen", type = any(), directory = any(), limit = any(), dirs = any())
        } returns expectedPaths

        val result = sut.findFiles(serverId, "/home/user/oc-remote", "Screen", 50)

        assertTrue(result.isSuccess)
        assertEquals(expectedPaths, result.getOrNull())
        coVerify {
            api.findFiles(testConn, query = "Screen", type = "file", directory = "/home/user/oc-remote", limit = 50, dirs = null)
        }
    }

    @Test
    fun `findFiles wraps exception as failure`() = runTest {
        coEvery { api.findFiles(any(), any(), any(), any(), any(), any()) } throws RuntimeException("network error")

        val result = sut.findFiles(serverId, "/dir", "query", 30)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("network error"))
    }

    @Test
    fun `findFiles with empty query still delegates to api`() = runTest {
        coEvery { api.findFiles(any(), any(), any(), any(), any(), any()) } returns emptyList()

        val result = sut.findFiles(serverId, "/dir", "", 50)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isEmpty())
    }
}
