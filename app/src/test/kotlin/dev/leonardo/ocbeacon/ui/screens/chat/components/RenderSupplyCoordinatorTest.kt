package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.util.computeTurnGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染供给协调器单测（架构评审 #169 阶段 3——Q6-B 用例集）。
 *
 * 用例与历史竞态根因一一对应：
 * - T1 流式禁预解析（部分快照被解析 → 回复永久截断）
 * - T2 预解析供给（正控）
 * - T3 display 粒度窗口（chunk 化后 entry→display 映射）
 * - T4 LRU 联动 registry.remove（#98 防无界增长）
 * - T5 门控-相位（Preparing/Measuring/Settling 非终态不提交）
 * - T6 门控-稳定窗口（终点+2s 内不提交——注入时钟）
 * - T7 F1 partId 反查（loadAround 重建后陈旧 display index 失效）
 * - T8 F2 视口内防线（窗口内永不提交裂变）
 * - T9 C-R4c 陈旧丢弃（turn 消失 → pending 真正清空）
 * - T10 流式 turn 记录 + 窗口清理（recentStreamedTurnKeys）
 *
 * 真实 RenderReadinessRegistry + 真实 markdown 解析（Dispatchers.Default，
 * await Parsed 终态同步）；Unconfined 作用域保证相位打点即时生效。
 */
class RenderSupplyCoordinatorTest {

    private class Env {
        val registry = RenderReadinessRegistry()
        val jumpPhase = MutableStateFlow<JumpPhase>(JumpPhase.Idle)
        var now = 10_000L // 非零基数：0 会被门控当作「从未跳转」（生产 elapsedRealtime 恒非零）
        val coordinator = RenderSupplyCoordinator(
            registry,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            jumpPhase,
            clock = { now },
        )

        /** 交替 user/assistant 世界：assistant i 的 display index = 2i+1（独立 turn）。 */
        fun world(
            pairs: Int,
            streaming: String? = null,
            entriesOverride: ChatEntries? = null,
            partFor: (assistantIndex: Int) -> List<Part>,
        ): RenderSupplyWorld {
            val msgs = (0 until pairs).flatMap { i ->
                listOf(userMsg(i), assistantMsg(i, partFor(i)))
            }
            val displayItems = msgs.mapIndexed { idx, m -> idx to m }
            val groups = computeTurnGroups(msgs)
            val entries = entriesOverride
                ?: buildChatEntries(displayItems, groups, streaming, emptyMap(), emptySet())
            return RenderSupplyWorld(displayItems, groups, entries, bannerCount = 0, streamingMsgId = streaming)
        }
    }

    // ============ fixtures（文件级——Env 无外部类接收者） ============

    private fun assertNeverParsed(env: Env, key: String) = runBlocking {
        val r = withTimeoutOrNull(150) { env.registry.flow(key).first { it !is RenderReadiness.Pending } }
        assertNull("key=$key 不应被预解析（或应已被 LRU 淘汰）", r)
    }

    private fun awaitParsedBlocking(env: Env, key: String): Unit = runBlocking {
        withTimeout(5000) { env.registry.flow(key).first { it is RenderReadiness.Parsed } }
        Unit
    }

    /** 目标 part 独占目标 assistant 的世界 fixture（避免多 launch 竞态）。 */
    private fun targetPartWorld(pairs: Int, partId: String, assistantIndex: Int, chunkable: Boolean = true) =
        { i: Int ->
            if (i == assistantIndex) {
                listOf(textPart(partId, if (chunkable) chunkableText() else plainText(250)))
            } else {
                listOf(textPart("f$i", plainText(250)))
            }
        }

    /** 种入 pending 分片计划：视口盖住目标 assistant → 解析 → 计划入队。 */
    private fun seedPendingPlan(env: Env, pairs: Int, assistantIndex: Int, partId: String) = runBlocking {
        val vp = 2 * assistantIndex + 1
        val partFor = targetPartWorld(pairs, partId, assistantIndex)
        env.coordinator.onViewportChanged(vp, vp, env.world(pairs, partFor = partFor))
        awaitParsedBlocking(env, partId)
        delay(100) // preParse 回调与 flow 发射的相邻语句保险
        Unit
    }
    @Test
    fun `T1_流式turn不预解析`() = runBlocking {
        val env = Env()
        val partFor = targetPartWorld(20, "p1", 15, chunkable = false)
        env.coordinator.onViewportChanged(31, 31, env.world(20, streaming = "a15", partFor = partFor))
        assertNeverParsed(env, "p1")
    }

    @Test
    fun `T2_窗口内assistant长文本被预解析`() = runBlocking {
        val env = Env()
        val partFor = targetPartWorld(20, "p1", 15, chunkable = false)
        env.coordinator.onViewportChanged(31, 31, env.world(20, partFor = partFor))
        awaitParsedBlocking(env, "p1") // 超时即失败（未 Parsed）
    }

    @Test
    fun `T3_chunk化entries下窗口按display粒度扩展`() = runBlocking {
        val env = Env()
        // display 5（assistant a2）裂成 3 个 entry；视口 entry 5..7 全属 display 5
        val entries = mutableListOf<ChatEntry>()
        val edi = mutableListOf<Int>()
        val des = IntArray(40)
        for (d in 0 until 40) {
            des[d] = entries.size
            repeat(if (d == 5) 3 else 1) { k ->
                edi += d
                entries += ChatEntry.Turn(displayIndex = d, key = if (d == 5) "k5#c$k" else "k$d")
            }
        }
        val table = ChatEntries(entries, edi.toIntArray(), des)
        env.coordinator.onViewportChanged(
            5, 7,
            env.world(20, entriesOverride = table) { listOf(textPart("pA$it", plainText(250))) },
        )
        // 窗口 = display (5-AHEAD)..(5+AHEAD)（边界从常量推导，不再硬编码）：
        // 窗口内最远 assistant（奇数 display 吸附）预解析，紧邻窗外的不预解析
        val ahead = RenderSupplyCoordinator.PREPARSE_AHEAD
        val lastIn = (5 + ahead) / 2 - ((5 + ahead) / 2 + 1) % 2  // 最大奇数 ≤ 5+ahead
        val firstOut = lastIn + 2
        awaitParsedBlocking(env, "pA$lastIn")
        assertNeverParsed(env, "pA$firstOut")
    }

    @Test
    fun `T4_超过LRU上限淘汰最旧条目并联动registry移除`() = runBlocking {
        val env = Env()
        // pairs 动态推导：keys = 2×pairs > PREPARSE_LRU → 必然淘汰最旧
        val pairs = RenderSupplyCoordinator.PREPARSE_LRU / 2 + 2
        val parts = { i: Int ->
            listOf(textPart("x$i", plainText(250)), textPart("y$i", plainText(250)))
        }
        env.coordinator.onViewportChanged(0, 31, env.world(pairs, partFor = parts))
        awaitParsedBlocking(env, "y" + (pairs - 1))
        assertNeverParsed(env, "x0") // 已被淘汰（重新读取为 Pending 且无解析驱动）
    }

    @Test
    fun `T5_跳转进行中不提交分片计划`() = runBlocking {
        val env = Env()
        val partId = "p15"
        val partFor = targetPartWorld(20, partId, 15)
        seedPendingPlan(env, 20, 15, partId)
        env.jumpPhase.value = JumpPhase.Preparing("target")
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = partFor))
        assertTrue("Preparing 中不应提交", env.coordinator.chunkPlans.value.isEmpty())
        env.jumpPhase.value = JumpPhase.Idle
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = partFor))
        assertTrue("Idle 后应提交", env.coordinator.chunkPlans.value.containsKey(partId))
    }

    @Test
    fun `T6_跳转终点后2秒稳定窗口内不提交`() = runBlocking {
        val env = Env()
        val partId = "p15"
        val partFor = targetPartWorld(20, partId, 15)
        seedPendingPlan(env, 20, 15, partId)
        env.jumpPhase.value = JumpPhase.Displayed("target") // 终点打点（now=10000）
        env.now = 11_500 // +1500 < 2000：稳定窗口内
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = partFor))
        assertTrue("稳定窗口内不应提交", env.coordinator.chunkPlans.value.isEmpty())
        env.now = 12_100 // +2100 ≥ 2000：窗口外
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = partFor))
        assertTrue("稳定窗口过后应提交", env.coordinator.chunkPlans.value.containsKey(partId))
    }

    @Test
    fun `T7_display重建后提交按partId反查新位置`() = runBlocking {
        val env = Env()
        val partId = "p15"
        val part = targetPartWorld(20, partId, 15)
        // 种入：a15 在 display 31
        env.coordinator.onViewportChanged(31, 31, env.world(20, partFor = part))
        awaitParsedBlocking(env, partId)
        delay(100)
        // 重建：display 31 前插入 3 对消息 → a15 移到 display 37（旧 index 全部失效）
        val shifted = { i: Int ->
            when {
                i < 3 -> listOf(textPart("new$i", plainText(250)))
                else -> part(i - 3)
            }
        }
        env.coordinator.onViewportChanged(0, 0, env.world(23, partFor = shifted))
        assertTrue("F1 应按 partId 反查到新位置提交", env.coordinator.chunkPlans.value.containsKey(partId))
    }

    @Test
    fun `T8_冷part带内即提交_热part带内拦截出带提交`() = runBlocking {
        val partId = "p5"
        val part = targetPartWorld(20, partId, 5)
        val m = RenderSupplyCoordinator.FISSION_SAFE_MARGIN

        // 冷分支：从未进视口——解析完成即提交（会话打开时视口上方紧邻巨型
        // 消息首轮滑入即分片，2026-08-22 第二轮冷态首滑根治）
        run {
            val env = Env()
            // 视口 15：a5(display 11) 在窗口（±20）被解析，在带内（15±6=9..21）
            // 但从未进视口 → 冷 → 提交（提交评估在解析完成后的下一次视口变化跑）
            env.coordinator.onViewportChanged(15, 15, env.world(20, partFor = part))
            awaitParsedBlocking(env, partId)
            delay(100)
            env.coordinator.onViewportChanged(15, 15, env.world(20, partFor = part))
            assertTrue("冷 part 带内即提交（单体从未组合，裂变零成本）", env.coordinator.chunkPlans.value.containsKey(partId))
        }

        // 热分支：曾进视口——视口内拦截；带内拦截（组合缓存保护）；出带提交
        run {
            val env = Env()
            env.coordinator.onViewportChanged(11, 11, env.world(20, partFor = part)) // a5 进视口 → 热
            awaitParsedBlocking(env, partId)
            delay(100)
            assertTrue("视口内不提交", env.coordinator.chunkPlans.value.isEmpty())
            env.coordinator.onViewportChanged(11 + m, 11 + m, env.world(20, partFor = part)) // 带内 + 热 → 拦截
            assertTrue("热 part 带内不提交（缓存池保护）", env.coordinator.chunkPlans.value.isEmpty())
            env.coordinator.onViewportChanged(11 + m + 2, 11 + m + 2, env.world(20, partFor = part)) // 出带 → 提交
            assertTrue("出带即提交", env.coordinator.chunkPlans.value.containsKey(partId))
        }
    }

    @Test
    fun `T9_turn从世界消失时pending真正丢弃`() = runBlocking {
        val env = Env()
        val partId = "p15"
        val part = targetPartWorld(20, partId, 15)
        seedPendingPlan(env, 20, 15, partId)
        // 世界重建：a15 不复存在（被过滤/会话切换）
        val withoutTarget = { i: Int -> listOf(textPart("g$i", plainText(250))) }
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = withoutTarget))
        assertTrue("turn 消失不提交", env.coordinator.chunkPlans.value.isEmpty())
        // 反证 pending 已清空：恢复含 a15 的世界且其在窗口外——若 pending 残留必在此提交
        env.coordinator.onViewportChanged(0, 0, env.world(20, partFor = part))
        assertTrue("pending 应已真正丢弃（C-R4c）", env.coordinator.chunkPlans.value.isEmpty())
    }

    @Test
    fun `T10_流式结束turnKey记录并在离开窗口后清除`() = runBlocking {
        val env = Env()
        env.coordinator.noteStreamTurnEnded("t_a15")
        assertEquals(setOf("t_a15"), env.coordinator.recentStreamedTurnKeys.value)
        // 窗口盖住 a15（display 31）：保留
        env.coordinator.onViewportChanged(31, 31, env.world(20) { listOf(textPart("z$it", plainText(250))) })
        assertEquals(setOf("t_a15"), env.coordinator.recentStreamedTurnKeys.value)
        // 窗口远离 a15（display 0..8）：清除
        env.coordinator.onViewportChanged(0, 0, env.world(20) { listOf(textPart("z$it", plainText(250))) })
        assertEquals(emptySet<String>(), env.coordinator.recentStreamedTurnKeys.value)
    }
}

// ============ 文件级 fixtures ============

private fun userMsg(i: Int) = ChatMessage(
    message = Message.User(id = "u$i", sessionId = "s", time = TimeInfo(created = 1L)),
    parts = emptyList(),
)

private fun assistantMsg(i: Int, parts: List<Part>) = ChatMessage(
    message = Message.Assistant(
        id = "a$i", sessionId = "s",
        time = TimeInfo(created = 1L, completed = 2L),
        parentId = "", modelId = "m",
    ),
    parts = parts,
)

private fun textPart(id: String, text: String) =
    Part.Text(id = id, sessionId = "s", messageId = "m-$id", text = text)

private fun plainText(chars: Int) = buildString {
    repeat(chars) { append(('a' + (it % 26))) }
}

/** ≥CHUNK_MIN_CHARS 且多顶层块——解析后必然产出分片计划。 */
private fun chunkableText(): String =
    (0 until 12).joinToString("\n\n") { "# 标题 $it\n\n" + "内容段落文本。".repeat(64) }
