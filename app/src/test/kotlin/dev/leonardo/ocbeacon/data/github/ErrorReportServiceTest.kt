package dev.leonardo.ocbeacon.data.github

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #151 主测试缝：错误上报服务边界（GitHubApiClient 伪造）。
 * spec §Testing：指纹双轨、查重命中→评论 / 未命中→建 issue、24h 防刷、正文构建。
 */
class ErrorReportServiceTest {

    private lateinit var apiClient: GitHubApiClient
    private lateinit var tokenStore: GitHubTokenStore
    private lateinit var service: ErrorReportService

    @Before
    fun setup() {
        apiClient = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        coEvery { tokenStore.loadToken() } returns "fake-token"
        coEvery { tokenStore.installId() } returns "install-1"
        service = ErrorReportService(apiClient, tokenStore)
    }

    // ---- 指纹纯函数（spec §指纹与查重） ----

    @Test
    fun `error fingerprint normalizes digits paths hex ids`() {
        // 数字/路径/十六进制 id 替换后，语义相同的不同实例应得同一指纹（跨版本查重前提）
        val a = service.fingerprintForError("Sse", "Connection to 10.0.2.2:4199 failed after /api/session/ses_abc12345 retry")
        val b = service.fingerprintForError("Sse", "Connection to 192.168.1.5:8080 failed after /api/session/ses_def99999 retry")
        assertEquals(a, b)
        assertTrue(a.startsWith("fp:err:Sse:"))
    }

    @Test
    fun `crash fingerprint isolates by version`() {
        val fp = service.fingerprintForCrash("IllegalStateException")
        assertTrue(fp.startsWith("fp:crash:"))
        assertTrue(fp.endsWith(":IllegalStateException"))
    }

    @Test
    fun `issue title has category prefix flattened message and signature`() {
        // 常规：category 前缀 + 折叠后的 message + 8 位签名后缀
        val t1 = service.issueTitleForError("SseClient", "stream closed\nunexpectedly   (code=1006)", "fp:err:Sse:a")
        assertEquals("SseClient: stream closed unexpectedly (code=1006) (#" + service.titleSignature("fp:err:Sse:a") + ")", t1)
    }

    @Test
    fun `long message middle-truncated keeping head and tail`() {
        // 中段截断：头 56 + … + 尾 24 都在，签名仍在末尾（2026-08-23 区分度定规）
        val msg = "A".repeat(60) + "MIDDLE" + "B".repeat(40)
        val t = service.issueTitleForError("C", msg, "fp:err:C:x")
        assertTrue(t.startsWith("C: " + "A".repeat(56) + "…"))
        assertTrue(t.contains("B".repeat(24)))
        assertTrue(t.endsWith(" (#" + service.titleSignature("fp:err:C:x") + ")"))
    }

    @Test
    fun `blank message degrades to category without dangling colon`() {
        val t = service.issueTitleForError("SoloCat", "  ", "fp:err:SoloCat:n")
        assertEquals("SoloCat (#" + service.titleSignature("fp:err:SoloCat:n") + ")", t)
    }

    @Test
    fun `different fingerprints yield distinct signatures - titles never collide`() {
        // 标题区分度硬保证：不同错误（指纹不同）→ 签名不同 → 标题必不同
        val a = service.issueTitleForError("Same", "identical text", "fp:err:X:1")
        val b = service.issueTitleForError("Same", "identical text", "fp:err:X:2")
        assertNotEquals(a, b)
        // 同指纹（同一错误重复上报）→ 签名一致，与查重归并语义对齐
        val c = service.issueTitleForError("Same", "identical text but different noise", "fp:err:X:1")
        assertTrue(a.endsWith("(#" + service.titleSignature("fp:err:X:1") + ")"))
        assertTrue(c.endsWith("(#" + service.titleSignature("fp:err:X:1") + ")"))
    }

    // ---- 查重编排 ----

    @Test
    fun `search miss creates new issue with user-report prefix and label`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns Result.success(null)
        coEvery { apiClient.createIssue(any(), any(), any(), any()) } returns Result.success(42)
        val out = service.report("fp:err:X:norm", "boom", "body", "comment").getOrThrow()
        assertTrue(out is ErrorReportService.Outcome.IssueCreated)
        assertEquals(42, (out as ErrorReportService.Outcome.IssueCreated).number)
        io.mockk.coVerify { apiClient.createIssue(any(), match { it.startsWith("[user-report] boom") }, any(), listOf("needs-triage")) }
    }

    @Test
    fun `search hit appends comment`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns
            Result.success(GitHubIssueHit(7, "[user-report] boom", "body"))
        coEvery { apiClient.addComment(any(), any(), any()) } returns Result.success(Unit)
        val out = service.report("fp:err:X:norm", "boom", "body", "comment").getOrThrow()
        assertTrue(out is ErrorReportService.Outcome.Commented)
        assertEquals(7, (out as ErrorReportService.Outcome.Commented).number)
    }

    @Test
    fun `second report within 24h is suppressed`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns
            Result.success(GitHubIssueHit(7, "t", "b"))
        coEvery { apiClient.addComment(any(), any(), any()) } returns Result.success(Unit)
        service.report("fp:err:X:norm", "t", "b", "c").getOrThrow()
        val second = service.report("fp:err:X:norm", "t", "b", "c").getOrThrow()
        assertTrue(second is ErrorReportService.Outcome.SuppressedDuplicate)
        io.mockk.coVerify(exactly = 1) { apiClient.addComment(any(), any(), any()) }
    }

    @Test
    fun `search failure falls back to create - never blocks report`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns
            Result.failure(GitHubApiError.RateLimited(null))
        coEvery { apiClient.createIssue(any(), any(), any(), any()) } returns Result.success(1)
        val out = service.report("fp:err:X:norm", "t", "b", "c").getOrThrow()
        assertTrue(out is ErrorReportService.Outcome.IssueCreated)
    }

    // ---- #154b 全量日志 gist 附件 ----

    private fun attachment(content: String = "FULL-LOG") = GistAttachment(
        description = "OC Beacon dev 1.0 diagnostics", filename = "ocbeacon-diagnostics-1.txt", content = content,
    )

    @Test
    fun `attachment success appends gist link to issue body and outcome`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns Result.success(null)
        coEvery { apiClient.createSecretGist(any(), any(), any(), any()) } returns
            Result.success("https://gist.github.com/xyz")
        coEvery { apiClient.createIssue(any(), any(), any(), any()) } returns Result.success(9)
        val out = service.report("fp:err:X:n", "t", "body", "c", attachment()).getOrThrow() as ErrorReportService.Outcome.IssueCreated
        assertEquals("https://gist.github.com/xyz", out.gistUrl)
        io.mockk.coVerify {
            apiClient.createIssue(any(), any(), match { it.contains("https://gist.github.com/xyz") }, any())
        }
    }

    @Test
    fun `attachment failure never blocks report - degrades to no attachment`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns Result.success(null)
        coEvery { apiClient.createSecretGist(any(), any(), any(), any()) } returns
            Result.failure(GitHubApiError.RateLimited(null))
        coEvery { apiClient.createIssue(any(), any(), any(), any()) } returns Result.success(10)
        val out = service.report("fp:err:X:n", "t", "body", "c", attachment()).getOrThrow() as ErrorReportService.Outcome.IssueCreated
        assertEquals(10, out.number)
        assertEquals(null, out.gistUrl)
        io.mockk.coVerify { apiClient.createIssue(any(), any(), match { !it.contains("gist.github.com") }, any()) }
    }

    @Test
    fun `suppressed duplicate within 24h creates no orphan gist`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns
            Result.success(GitHubIssueHit(7, "t", "b"))
        coEvery { apiClient.addComment(any(), any(), any()) } returns Result.success(Unit)
        coEvery { apiClient.createSecretGist(any(), any(), any(), any()) } returns
            Result.success("https://gist.github.com/never")
        service.report("fp:err:X:same", "t", "b", "c", attachment()).getOrThrow()
        val second = service.report("fp:err:X:same", "t", "b", "c", attachment()).getOrThrow()
        assertTrue(second is ErrorReportService.Outcome.SuppressedDuplicate)
        // 防抖掉的重复上报不得留下孤儿 gist（附件在防抖判定之后才创建）
        io.mockk.coVerify(exactly = 1) { apiClient.createSecretGist(any(), any(), any(), any()) }
    }

    @Test
    fun `comment path also carries gist link`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns
            Result.success(GitHubIssueHit(7, "t", "b"))
        coEvery { apiClient.createSecretGist(any(), any(), any(), any()) } returns
            Result.success("https://gist.github.com/cmt")
        coEvery { apiClient.addComment(any(), any(), any()) } returns Result.success(Unit)
        val out = service.report("fp:err:X:c", "t", "b", "c", attachment()).getOrThrow() as ErrorReportService.Outcome.Commented
        assertEquals("https://gist.github.com/cmt", out.gistUrl)
        io.mockk.coVerify { apiClient.addComment(any(), any(), match { it.contains("https://gist.github.com/cmt") }) }
    }

    @Test
    fun `no attachment requested - no gist call`() = runTest {
        coEvery { apiClient.searchIssueByFingerprint(any(), any()) } returns Result.success(null)
        coEvery { apiClient.createIssue(any(), any(), any(), any()) } returns Result.success(11)
        val out = service.report("fp:err:X:no", "t", "b", "c").getOrThrow() as ErrorReportService.Outcome.IssueCreated
        assertEquals(null, out.gistUrl)
        io.mockk.coVerify(exactly = 0) { apiClient.createSecretGist(any(), any(), any(), any()) }
    }

    @Test
    fun `oversized gist content is tail-truncated within budget`() {
        val huge = (1..80_000).joinToString("\n") { "line-$it" } // 远超 300K 字符预算
        val truncated = service.truncateGistContent(huge)
        assertTrue(truncated.length <= ErrorReportService.MAX_GIST_CONTENT_CHARS + 200) // 标注行余量
        assertTrue(truncated.contains("（前部已截断"))
        // 保尾不保头：最新日志（尾部）必须在
        assertTrue(truncated.contains("line-80000"))
        assertTrue(!truncated.contains("line-1\n"))
    }

    @Test
    fun `small gist content passes through unchanged`() {
        assertEquals("LOG", service.truncateGistContent("LOG"))
    }

    // ---- 正文构建 ----

    @Test
    fun `log section takes last 20 errors with 3-around context marked`() {
        val entries = (1..60).map { i ->
            ReportLogEntry(i.toLong(), if (i % 5 == 0) "ERROR" else "INFO", "C", "m$i")
        }
        val section = service.buildLogSection(entries)
        val lines = section.lines().filter { it.isNotBlank() }
        // 最后 20 错误 = i in 1..60 step 5 的后 20 个 → 全部 12 个错误（60/5=12 < 20）
        val marked = lines.count { it.startsWith("▸") }
        assertEquals(12, marked)
        // 上下文存在且未标记
        assertTrue(lines.any { !it.startsWith("▸") })
    }

    @Test
    fun `machine block is fenced json with fingerprint`() {
        val env = ReportEnvironment("Xiaomi 23127PN0CC", "16", 36, "0.3.1", "dev", "zh-CN", 512)
        val block = service.machineBlock("fp:err:X:n", env, "install-1")
        assertTrue(block.trim().startsWith("```json"))
        assertTrue(block.contains("\"fingerprint\":\"fp:err:X:n\""))
        assertTrue(block.contains("\"device\""))
    }
}
