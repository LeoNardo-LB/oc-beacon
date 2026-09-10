package dev.leonardo.ocbeacon.data.adapter.opencode

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.MentionCandidate
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #391 切片9：OpenCode 引用候选端口沿用 findFiles 现参数形（@ 文件补全零回归）。 */
class OpenCodeReferencePortTest {

    private val conn = ServerConnection("http://srv", null, ApiVersion.V1, ServerType.OpenCode)
    private val file = mockk<FileApi>()
    private val port = OpenCodeReferencePort(file)

    @Test
    fun `maps findFiles hits with the legacy parameter shape`() = runTest {
        coEvery {
            file.findFiles(any(), "q", directory = "/w", limit = 15, dirs = "true")
        } returns listOf("a.kt")
        val result = port.candidates(conn, "s1", "q", directory = "/w", quoted = false)
        assertEquals(listOf<MentionCandidate>(MentionCandidate.FileMention("a.kt")), result)
        coVerify(exactly = 1) {
            file.findFiles(any(), "q", directory = "/w", limit = 15, dirs = "true")
        }
    }
}
