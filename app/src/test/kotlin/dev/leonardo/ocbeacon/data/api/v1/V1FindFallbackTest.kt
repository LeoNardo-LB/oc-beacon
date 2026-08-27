package dev.leonardo.ocbeacon.data.api.v1

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #248（2026-08-28）：V1 /find/file 大目录静默空 → /file 单层列表客户端过滤
 * 回退的纯函数契约。
 *
 * 活体实证（opencode 1.18.18 @4200）：home 目录 query=bash/service 均 []，
 * 同目录 /file?path= 79 条 5.6ms——回退过滤是 @ 弹窗在大目录会话的唯一通路。
 */
class V1FindFallbackTest {

    private val topLevel = listOf(
        ".agentmemory/", ".android/", ".bashrc", "Desktop/",
        "migrate-tailscale.sh", "wsdd.service"
    )

    @Test
    fun `non-empty query filters case-insensitively`() {
        val out = findFilesFallbackFilter(topLevel, "BASH", null)
        assertEquals(listOf(".bashrc"), out)
    }

    @Test
    fun `non-empty query no match returns empty`() {
        val out = findFilesFallbackFilter(topLevel, "zzz", null)
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun `empty query returns full listing as recent-files degrade`() {
        val out = findFilesFallbackFilter(topLevel, "", null)
        assertEquals(topLevel, out)
    }

    @Test
    fun `blank query trims to empty semantics`() {
        val out = findFilesFallbackFilter(topLevel, "   ", null)
        assertEquals(topLevel, out)
    }

    @Test
    fun `limit caps results after filtering`() {
        val out = findFilesFallbackFilter(topLevel, "", 2)
        assertEquals(topLevel.take(2), out)
    }

    @Test
    fun `limit applies after filter not before`() {
        val out = findFilesFallbackFilter(topLevel, "a", 1)
        // contains "a" (case-insensitive): .agentmemory/ .android/ .bashrc Desktop/ migrate-tailscale.sh
        assertEquals(listOf(".agentmemory/"), out)
    }

    @Test
    fun `null limit returns all matches`() {
        val out = findFilesFallbackFilter(listOf("AGENTS.md", "CONTEXT.md"), ".md", null)
        assertEquals(listOf("AGENTS.md", "CONTEXT.md"), out)
    }
}
