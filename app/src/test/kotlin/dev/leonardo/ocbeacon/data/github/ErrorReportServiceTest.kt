package dev.leonardo.ocbeacon.data.github

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `issue title derives from category and message single-line capped`() {
        // 常规：category 前缀 + 折叠后的 message
        val t1 = service.issueTitleForError("SseClient", "stream closed\nunexpectedly   (code=1006)")
        assertEquals("SseClient: stream closed unexpectedly (code=1006)", t1)
        // 超长截断：总长 <= 100 且以省略号结尾
        val long = service.issueTitleForError("C", "x".repeat(300))
        assertEquals(100, long.length)
        assertTrue(long.endsWith("…"))
        // message 为空/仅空白：退化为 category 本身，不产生悬空冒号
        assertEquals("SoloCat", service.issueTitleForError("SoloCat", "  "))
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
