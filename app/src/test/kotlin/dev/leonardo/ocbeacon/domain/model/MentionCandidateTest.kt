package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #310⑤/#321 @ 引用候选纯合并测试（web mod34:113-114 并行合并先例钉死）：
 * - quoted=true（`@"` 引号形态）只文件候选（web quoted===true 跳过会话候选）；
 * - 否则会话在前、sameWorkspace 优先（types.d.ts cwd 亲和语义），
 *   组内保序（服务器已按 cwd 亲和排序——客户端稳定排序不重排服务器序）。
 */
class MentionCandidateTest {

    private val files = listOf(
        MentionCandidate.FileMention("docs/a.md"),
        MentionCandidate.FileMention("docs/sub/"),
    )

    private val sessions = listOf(
        MentionCandidate.SessionMention(
            sessionId = "s-2", label = "外区会话", cwd = "/other",
            sameWorkspace = false, mention = "@[外区会话](dsh-session:s-2)",
        ),
        MentionCandidate.SessionMention(
            sessionId = "s-1", label = "同区会话", cwd = "/w",
            sameWorkspace = true, mention = "@[同区会话](dsh-session:s-1)",
        ),
    )

    private fun ids(merged: List<MentionCandidate>): List<String> = merged.map {
        when (it) {
            is MentionCandidate.SessionMention -> it.sessionId
            is MentionCandidate.FileMention -> it.path
        }
    }

    /** quoted=true（`@"` 带空格形态）：跳过会话候选——只返回文件，保序。 */
    @Test
    fun `merge quoted keeps files only`() {
        val merged = mergeMentionCandidates(files, sessions, quoted = true)
        assertEquals(files, merged)
    }

    /** 非 quoted：会话在前（sameWorkspace=true 优先于 false——服务器返回序可乱），文件随后保序。 */
    @Test
    fun `merge unquoted puts sessions first with sameWorkspace priority`() {
        val merged = mergeMentionCandidates(files, sessions, quoted = false)
        assertEquals(listOf("s-1", "s-2", "docs/a.md", "docs/sub/"), ids(merged))
    }

    /** 组内保序（稳定排序——同组会话保持服务器序）+ 空输入恒等 + quoted 不会话补位。 */
    @Test
    fun `merge preserves order within groups and tolerates empties`() {
        val sameWorkspace = listOf(
            MentionCandidate.SessionMention("s-a", "A", "/w", true, "@[A](dsh-session:s-a)"),
            MentionCandidate.SessionMention("s-b", "B", "/w", true, "@[B](dsh-session:s-b)"),
        )
        assertEquals(
            listOf("s-a", "s-b", "docs/a.md", "docs/sub/"),
            ids(mergeMentionCandidates(files, sameWorkspace, quoted = false)),
        )
        // 双空 → 空
        assertEquals(
            emptyList<MentionCandidate>(),
            mergeMentionCandidates(emptyList(), emptyList(), quoted = false),
        )
        // quoted 且无文件 → 空（会话不补位——web 语义：引号形态无会话候选）
        assertEquals(
            emptyList<MentionCandidate>(),
            mergeMentionCandidates(emptyList(), sameWorkspace, quoted = true),
        )
    }
}
