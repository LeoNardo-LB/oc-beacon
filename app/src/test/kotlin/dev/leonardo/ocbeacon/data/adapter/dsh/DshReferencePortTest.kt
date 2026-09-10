package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
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

/** #391 切片9：DSH 引用候选端口的取数策略（quoted 跳过会话域 + 两域合并顺序）。 */
class DshReferencePortTest {

    private val conn = ServerConnection("http://srv", null, ApiVersion.V1, ServerType.Dsh)
    private val dsh = mockk<DshApiClient>(relaxed = true)
    private val port = DshReferencePort(dsh)

    @Test
    fun `quoted skips the session domain and keeps file hits`() = runTest {
        coEvery { dsh.fileReferencesList(any(), "s1", "q") } returns listOf("a.kt")
        val result = port.candidates(conn, "s1", "q", directory = null, quoted = true)
        assertEquals(listOf<MentionCandidate>(MentionCandidate.FileMention("a.kt")), result)
        coVerify(exactly = 0) { dsh.sessionReferenceCandidates(any(), any(), any()) }
    }

    @Test
    fun `unquoted merges session domain before files`() = runTest {
        val session = MentionCandidate.SessionMention(
            sessionId = "s-2",
            label = "label",
            sameWorkspace = true,
            mention = "@[label](dsh-session:s-2)",
        )
        coEvery { dsh.fileReferencesList(any(), "s1", "q") } returns listOf("a.kt")
        coEvery { dsh.sessionReferenceCandidates(any(), "s1", "q") } returns listOf(session)
        val result = port.candidates(conn, "s1", "q", directory = null, quoted = false)
        assertEquals(
            listOf<MentionCandidate>(session, MentionCandidate.FileMention("a.kt")),
            result,
        )
    }
}
