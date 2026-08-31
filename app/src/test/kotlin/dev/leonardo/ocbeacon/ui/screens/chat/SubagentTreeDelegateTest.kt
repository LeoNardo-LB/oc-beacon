package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SubagentChild
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentSheet 多级树状态机测试（2026-09 树化：DSH subagent.list 权威域 +
 * OpenCode 本地镜像递归双轨）。
 *
 * 覆盖：本地递归展平（深度/防环）· DSH 根层拉取与逐层懒加载 · label 缺失
 * 回退本地 title 投影 · DSH 失败软降级本地递归 · OpenCode null 结果本地模式。
 */
class SubagentTreeDelegateTest {

    private fun child(
        id: String,
        label: String? = null,
        running: Boolean = false,
        hasChildren: Boolean = false,
        diagnostic: Boolean = false,
        reason: String? = null,
    ) = SubagentChild(
        sessionId = id,
        label = label,
        isRunning = running,
        hasChildren = hasChildren,
        isDiagnostic = diagnostic,
        diagnosticReason = reason,
    )

    private fun snapshot(
        root: String,
        childrenByParent: Map<String, List<SubagentChild>>,
        titles: Map<String, String> = emptyMap(),
    ) = SubagentLocalSnapshot(
        rootSessionId = root,
        childrenByParent = childrenByParent,
        titleById = titles,
    )

    // ============ OpenCode（本地镜像递归） ============

    @Test
    fun `local tree flattens recursive subtree with depth and expand state`() = runTest {
        val snapshots = MutableStateFlow(
            snapshot(
                root = "root",
                childrenByParent = mapOf(
                    "root" to listOf(child("b", label = "任务B"), child("d", label = "任务D")),
                    "b" to listOf(child("c", label = "任务C", running = true)),
                ),
            ),
        )
        val holder = SubagentTreeHolder(backgroundScope, null, snapshots)
        holder.state.value // 订阅启动（Eagerly）
        runCurrent()
        // 未展开：只见根层
        assertEquals(listOf("b", "d"), holder.state.value.rows.map { it.sessionId })
        assertEquals(listOf(0, 0), holder.state.value.rows.map { it.depth })
        assertTrue(holder.state.value.rows.all { !it.isExpanded })

        holder.toggle("b")
        runCurrent()
        // 展开 b：子层 c 深度 1 缩进出现；b 行标记展开、有箭头
        assertEquals(listOf("b", "c", "d"), holder.state.value.rows.map { it.sessionId })
        assertEquals(listOf(0, 1, 0), holder.state.value.rows.map { it.depth })
        val bRow = holder.state.value.rows.first { it.sessionId == "b" }
        assertTrue(bRow.isExpanded)
        assertTrue(bRow.hasChildren)
        assertTrue(holder.state.value.rows.first { it.sessionId == "c" }.isRunning)

        holder.toggle("b")
        runCurrent()
        assertEquals(listOf("b", "d"), holder.state.value.rows.map { it.sessionId })
    }

    @Test
    fun `local tree guards against parent id cycles`() = runTest {
        // 畸形镜像：x→y、y→x 互为父子——递归必须终止（visited 防环）
        val snapshots = MutableStateFlow(
            snapshot(
                root = "root",
                childrenByParent = mapOf(
                    "root" to listOf(child("x", label = "X")),
                    "x" to listOf(child("y", label = "Y")),
                    "y" to listOf(child("x", label = "X")),
                ),
            ),
        )
        val holder = SubagentTreeHolder(backgroundScope, null, snapshots)
        runCurrent()
        holder.toggle("x")
        runCurrent()
        holder.toggle("y")
        runCurrent()
        // x 展开出 y；y 再展开不回到 x（环截断），行数有限
        val ids = holder.state.value.rows.map { it.sessionId }
        assertEquals(listOf("x", "y"), ids)
    }

    // ============ DSH（subagent.list 权威域） ============

    @Test
    fun `dsh root refresh fetches authoritative catalog and maps rows`() = runTest {
        val fetchCalls = mutableListOf<String>()
        val snapshots = MutableStateFlow(
            snapshot(
                root = "session-root",
                childrenByParent = emptyMap(),
                titles = mapOf("c-2" to "本地标题回退"),
            ),
        )
        val holder = SubagentTreeHolder(
            backgroundScope,
            fetcher = { parent ->
                fetchCalls.add(parent)
                Result.success(
                    listOf(
                        child("c-1", label = "调研输入法", running = true, hasChildren = true),
                        child("c-2"), // one-shot 无 label → 回退本地 title 投影
                        child("c-3", diagnostic = true, reason = "corrupt"),
                    )
                )
            },
            snapshots = snapshots,
        )
        runCurrent()
        holder.refreshRoot()
        runCurrent()
        assertEquals(listOf("session-root"), fetchCalls)
        val rows = holder.state.value.rows
        assertEquals(listOf("c-1", "c-2", "c-3"), rows.map { it.sessionId })
        assertEquals("调研输入法", rows[0].label)
        assertTrue(rows[0].isRunning)
        assertTrue(rows[0].hasChildren)
        assertEquals("本地标题回退", rows[1].label) // label 缺失 → title 投影回退
        assertTrue(rows[2].isDiagnostic) // diagnostic → 灰显不可点
    }

    @Test
    fun `dsh toggle lazily fetches child layer once and shows loading id`() = runTest {
        val fetchCalls = mutableListOf<String>()
        val snapshots = MutableStateFlow(snapshot(root = "session-root", childrenByParent = emptyMap()))
        val holder = SubagentTreeHolder(
            backgroundScope,
            fetcher = { parent ->
                fetchCalls.add(parent)
                Result.success(
                    if (parent == "session-root") listOf(child("b", label = "B", hasChildren = true))
                    else listOf(child("bb", label = "BB")),
                )
            },
            snapshots = snapshots,
        )
        runCurrent()
        holder.refreshRoot()
        runCurrent()
        assertEquals(listOf("session-root"), fetchCalls)

        holder.toggle("b")
        runCurrent()
        assertEquals(listOf("session-root", "b"), fetchCalls) // 展开才拉该层
        assertEquals(listOf("b", "bb"), holder.state.value.rows.map { it.sessionId })
        assertEquals(1, holder.state.value.rows[1].depth)
        assertTrue(holder.state.value.loadingIds.isEmpty())

        holder.toggle("b")
        runCurrent()
        assertEquals(listOf("session-root", "b"), fetchCalls) // 折叠不重复拉
        assertEquals(listOf("b"), holder.state.value.rows.map { it.sessionId })

        holder.toggle("b")
        runCurrent()
        assertEquals(listOf("session-root", "b"), fetchCalls) // 已缓存层不再拉
        assertEquals(listOf("b", "bb"), holder.state.value.rows.map { it.sessionId })
    }

    @Test
    fun `dsh root failure degrades to local recursion`() = runTest {
        val snapshots = MutableStateFlow(
            snapshot(
                root = "session-root",
                childrenByParent = mapOf("session-root" to listOf(child("local-1", label = "本地降级行"))),
            ),
        )
        val holder = SubagentTreeHolder(
            backgroundScope,
            fetcher = { Result.failure(IllegalStateException("rpc down")) },
            snapshots = snapshots,
        )
        runCurrent()
        holder.refreshRoot()
        runCurrent()
        // 软降级：本地镜像递归顶上（AppLogger.w 告警在实现侧）
        assertEquals(listOf("local-1"), holder.state.value.rows.map { it.sessionId })
        assertEquals("本地降级行", holder.state.value.rows[0].label)
    }

    @Test
    fun `opencode null catalog result stays local without fetch storm`() = runTest {
        val fetchCalls = mutableListOf<String>()
        val snapshots = MutableStateFlow(
            snapshot(
                root = "ses_v2",
                childrenByParent = mapOf(
                    "ses_v2" to listOf(child("v2-c1", label = "V2 子任务")),
                    "v2-c1" to listOf(child("v2-gc", label = "V2 孙任务")),
                ),
            ),
        )
        val holder = SubagentTreeHolder(
            backgroundScope,
            fetcher = { parent ->
                fetchCalls.add(parent)
                Result.success(null) // OpenCode：无权威域
            },
            snapshots = snapshots,
        )
        runCurrent()
        holder.refreshRoot()
        runCurrent()
        assertEquals(listOf("v2-c1"), holder.state.value.rows.map { it.sessionId })
        assertEquals(1, fetchCalls.size) // 无域探测一次即止，不反复打

        holder.toggle("v2-c1")
        runCurrent()
        assertEquals(1, fetchCalls.size) // 本地模式展开不发请求
        assertEquals(listOf("v2-c1", "v2-gc"), holder.state.value.rows.map { it.sessionId })
    }
}
