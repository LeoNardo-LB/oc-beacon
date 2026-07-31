package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.dto.response.FileDiffDto
import dev.leonardo.ocbeacon.data.dto.response.VcsBranchDto
import dev.leonardo.ocbeacon.data.dto.response.VcsChangeDto
import dev.leonardo.ocbeacon.domain.model.VcsBranchInfo
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VcsRepositoryImplTest {

    private val api: FileApi = mockk()
    private val serverRepository: ServerRepository = mockk()
    private lateinit var sut: VcsRepositoryImpl

    private val testConn = ServerConnection.from("http://localhost:4096", "opencode", "password")
    private val serverId = "server-1"

    @Before
    fun setup() {
        coEvery { serverRepository.resolveConnection(serverId) } returns testConn
        sut = VcsRepositoryImpl(api, serverRepository)
    }

    @Test
    fun `getBranch success returns VcsBranchInfo`() = runTest {
        val dto = VcsBranchDto(branch = "feat/workspace", defaultBranch = "master")
        coEvery { api.getVcs(testConn, "my-project") } returns dto

        val result = sut.getBranch(serverId, "my-project")

        assertTrue(result.isSuccess)
        val info: VcsBranchInfo = result.getOrThrow()
        assertEquals("feat/workspace", info.branch)
        assertEquals("master", info.defaultBranch)
    }

    @Test
    fun `getStatus success maps DTOs`() = runTest {
        val dtos = listOf(
            VcsChangeDto(file = "src/api/OpenCodeApi.kt", additions = 24, deletions = 3, status = "modified"),
            VcsChangeDto(file = "src/model/Session.kt", additions = 8, deletions = 0, status = "added"),
            VcsChangeDto(file = "src/utils/legacy.py", additions = 0, deletions = 42, status = "deleted")
        )
        coEvery { api.getVcsStatus(testConn, "my-project") } returns dtos

        val result = sut.getStatus(serverId, "my-project")

        assertTrue(result.isSuccess)
        val changes: List<VcsChange> = result.getOrThrow()
        assertEquals(3, changes.size)
        assertEquals("src/api/OpenCodeApi.kt", changes[0].file)
        assertEquals(24, changes[0].additions)
        assertEquals(VcsStatus.MODIFIED, changes[0].status)
        assertEquals("src/model/Session.kt", changes[1].file)
        assertEquals(VcsStatus.ADDED, changes[1].status)
        assertEquals("src/utils/legacy.py", changes[2].file)
        assertEquals(VcsStatus.DELETED, changes[2].status)
    }

    @Test
    fun `getDiff success passes mode apiValue and maps to VcsFileDiff`() = runTest {
        val dtos = listOf(
            FileDiffDto(
                file = "src/api/OpenCodeApi.kt",
                patch = "@@ -1,5 +1,8 @@\n+import kotlinx.coroutines",
                additions = 12,
                deletions = 3,
                status = "modified"
            ),
            FileDiffDto(
                file = "src/model/VcsFileDiff.kt",
                patch = "@@ -0,0 +1,9 @@\n+package dev.minios",
                additions = 9,
                deletions = 0,
                status = "added"
            )
        )
        coEvery { api.getVcsDiff(testConn, "git", 3, "my-project") } returns dtos

        val result = sut.getDiff(serverId, "my-project", VcsDiffMode.GIT, 3)

        assertTrue(result.isSuccess)
        val diffs: List<VcsFileDiff> = result.getOrThrow()
        assertEquals(2, diffs.size)
        assertEquals("src/api/OpenCodeApi.kt", diffs[0].file)
        assertEquals("@@ -1,5 +1,8 @@\n+import kotlinx.coroutines", diffs[0].patch)
        assertEquals(12, diffs[0].additions)
        assertEquals(3, diffs[0].deletions)
        assertEquals(VcsStatus.MODIFIED, diffs[0].status)
        assertEquals("src/model/VcsFileDiff.kt", diffs[1].file)
        assertEquals(VcsStatus.ADDED, diffs[1].status)
    }

    @Test
    fun `getStatus failure wraps exception`() = runTest {
        coEvery { api.getVcsStatus(any(), any()) } throws RuntimeException("connection refused")

        val result = sut.getStatus(serverId, "my-project")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("connection refused"))
    }
}
