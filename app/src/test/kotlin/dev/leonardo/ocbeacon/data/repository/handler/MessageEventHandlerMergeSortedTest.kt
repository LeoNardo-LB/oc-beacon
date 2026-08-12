package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * `mergeSortedMessages` 的对拍测试（金标准）。
 *
 * 验证线性两路归并实现与旧算法 `(existing + incoming).distinctBy { id }.map { merge }.sortedBy { created }`
 * 在任意合法输入下逐字节等价。合法输入前提：existing 与 incoming 均按 created 升序，
 * 且同一 id 在两列表中 created 一致（服务器不变更创建时间）。
 *
 * - reference 函数（本文件内）是旧算法的精确语义实现
 * - 300 轮随机对拍覆盖 id 重叠/不重叠/重复、created 大量相同等组合
 * - 显式边界用例覆盖 Bug 1 / Bug 2 场景及退化输入
 */
class MessageEventHandlerMergeSortedTest {

    private val handler = MessageEventHandler()

    // ============ reference：旧算法精确语义 ============

    /**
     * 旧算法 reference：`(existing + incoming).distinctBy { id }.map { merge }.sortedBy { created }`。
     * - distinctBy 保留首个（existing 优先于 incoming；列表内部首个优先于后续）
     * - map 对同时存在于两列表的 id 调用 merge(existing, incoming)，独有项原样保留
     * - sortedBy 稳定排序（Kotlin 默认）
     */
    private fun reference(
        existing: List<Message>,
        incoming: List<Message>,
        merge: (Message, Message) -> Message,
    ): List<Message> {
        val existingById = LinkedHashMap<String, Message>()
        for (m in existing) if (m.id !in existingById) existingById[m.id] = m
        val incomingById = LinkedHashMap<String, Message>()
        for (m in incoming) if (m.id !in incomingById) incomingById[m.id] = m

        // (existing + incoming).distinctBy { it.id }：保留首个
        val distinct = ArrayList<Message>()
        val seen = HashSet<String>()
        for (m in existing) if (m.id !in seen) { distinct.add(m); seen.add(m.id) }
        for (m in incoming) if (m.id !in seen) { distinct.add(m); seen.add(m.id) }

        // .map { merge }：同时存在则合并
        val mapped = distinct.map { msg ->
            val e = existingById[msg.id]
            val inc = incomingById[msg.id]
            if (e != null && inc != null) merge(e, inc) else msg
        }
        // .sortedBy { it.time.created }（稳定）
        return mapped.sortedBy { it.time.created }
    }

    // ============ 测试辅助 ============

    private fun msg(id: String, created: Long, tag: String): Message.Assistant =
        Message.Assistant(
            id = id,
            sessionId = "s1",
            parentId = "p-$id",
            time = TimeInfo(created = created),
            modelId = tag,
        )

    /** merge：拼接两版本的 modelId 作为可验证的合并标记（内容指纹）。 */
    private val testMerge: (Message, Message) -> Message = { e, inc ->
        val ea = e as Message.Assistant
        val ia = inc as Message.Assistant
        ea.copy(modelId = "${ea.modelId}+${ia.modelId}")
    }

    /** 断言 `mergeSortedMessages` 输出与 reference 完全一致（id 序列 + 内容指纹）。 */
    private fun assertEquiv(existing: List<Message>, incoming: List<Message>, label: String) {
        val actual = handler.mergeSortedMessages(existing, incoming, testMerge)
        val expected = reference(existing, incoming, testMerge)
        assertEquals("[$label] size", expected.size, actual.size)
        for (k in actual.indices) {
            assertEquals("[$label] id at index $k", expected[k].id, actual[k].id)
            val ea = expected[k] as Message.Assistant
            val aa = actual[k] as Message.Assistant
            assertEquals("[$label] modelId (fingerprint) at index $k for id=${actual[k].id}", ea.modelId, aa.modelId)
        }
    }

    // ============ 显式边界用例 ============

    @Test
    fun `Bug1 - same created order reversal`() {
        // existing=[A(1),C(5)] incoming=[B(1),A(1)]
        // reference: (ex+inc)=[A,C,B,A] distinctBy=[A(ex),C,B] mapMerge=[A',C,B]
        //            sortedBy(stable,created=[1,5,1])=[A'(1),B(1),C(5)]
        val a = msg("A", 1, "eA"); val c = msg("C", 5, "eC")
        val b = msg("B", 1, "iB"); val a2 = msg("A", 1, "iA")
        assertEquiv(listOf(a, c), listOf(b, a2), "Bug1")
    }

    @Test
    fun `Bug2 - incoming duplicate id not deduped`() {
        // incoming=[X(1),X(1)] existing=[] → reference distinctBy 保留首个=[X(首个)]
        val x1 = msg("X", 1, "i1"); val x2 = msg("X", 1, "i2")
        assertEquiv(emptyList(), listOf(x1, x2), "Bug2")
    }

    @Test
    fun `Bug2 variant - existing duplicate id dedup`() {
        // existing=[Y(1),Y(1)] incoming=[] → reference 保留首个 Y
        val y1 = msg("Y", 1, "e1"); val y2 = msg("Y", 1, "e2")
        assertEquiv(listOf(y1, y2), emptyList(), "Bug2-ex")
    }

    @Test
    fun `both empty`() = assertEquiv(emptyList(), emptyList(), "both-empty")

    @Test
    fun `empty existing`() {
        assertEquiv(emptyList(), listOf(msg("A", 1, "iA"), msg("B", 2, "iB")), "empty-existing")
    }

    @Test
    fun `empty incoming`() {
        assertEquiv(listOf(msg("A", 1, "eA"), msg("B", 2, "eB")), emptyList(), "empty-incoming")
    }

    @Test
    fun `all overlap`() {
        val e = listOf(msg("A", 1, "eA"), msg("B", 2, "eB"))
        val i = listOf(msg("A", 1, "iA"), msg("B", 2, "iB"))
        assertEquiv(e, i, "all-overlap")
    }

    // ============ 双向分页去重（loadAround / loadNewer 场景） ============

    /**
     * loadAround 场景：existing 已含初始窗口（m-60..m-90）；定位加载 target=m-30，
     * older=[m-1..m-29]（无重叠），newer=[m-31..m-60]（与 existing 在 m-60 处重叠）。
     * 合并后 m-60 不可重复出现，且按 created 升序。
     */
    @Test
    fun `bidirectional loadAround - older and newer overlap with existing deduped`() {
        // existing：初始窗口 m-60(t60)..m-90(t90)
        val existing = (60..90).map { msg("m-$it", it.toLong(), "e$it") }
        // incoming = older + target + newer（按 created 升序，模拟 upsertAppendOnce 内部排序后输入）。
        // older=[m-1..m-29]（无重叠），target=m-30，newer=[m-31..m-60]（与 existing 在 m-60 处重叠）。
        val target = msg("m-30", 30L, "t30")
        val older = (1..29).map { msg("m-$it", it.toLong(), "o$it") }       // 更旧方向
        val newer = (31..60).map { msg("m-$it", it.toLong(), "n$it") }      // 更新方向（m-60 与 existing 重叠）
        val incoming = older + listOf(target) + newer  // created 升序：1..29, 30, 31..60

        val merged = handler.mergeSortedMessages(existing, incoming) { e, _ -> e }

        // 无重复 id
        val ids = merged.map { it.id }
        assertEquals("合并后无重复 id", ids.size, ids.toSet().size)
        // m-60 只出现一次（existing 与 newer 都有）
        assertEquals(1, ids.count { it == "m-60" })
        // 按 created 升序
        val createdSeq = merged.map { it.time.created }
        assertEquals(createdSeq, createdSeq.sorted())
        // m-60 保留 existing 版本（APPEND_ONLY 语义：{ e, _ -> e }）
        val m60 = merged.first { it.id == "m-60" } as Message.Assistant
        assertEquals("e60", m60.modelId)
    }

    /**
     * loadNewer + SSE 并发场景：existing=[m-30..m-60]；loadNewer 拉回 [m-61..m-90]；
     * 同时 SSE 推送了 m-70（实时新消息，已在 existing 之外）。两次合并串行执行，
     * 最终无重复。模拟 Delegate 两次 upsert APPEND_ONLY 的串行合并。
     */
    @Test
    fun `loadNewer then SSE - serial merges dedupe across directions`() {
        var current: List<Message> = (30..60).map { msg("m-$it", it.toLong(), "e$it") }
        // 第一次：loadNewer 拉回 m-61..m-90（其中 m-65 与未来 SSE 重叠）
        val newerBatch = (61..90).map { msg("m-$it", it.toLong(), "n$it") }
        current = handler.mergeSortedMessages(current, newerBatch) { e, _ -> e }
        // 第二次：SSE 推送 m-65（已在 newerBatch 中）+ m-91（全新）
        val sseBatch = listOf(msg("m-65", 65L, "sse65"), msg("m-91", 91L, "sse91"))
        current = handler.mergeSortedMessages(current, sseBatch) { e, _ -> e }

        val ids = current.map { it.id }
        assertEquals("跨方向串行合并无重复", ids.size, ids.toSet().size)
        assertEquals(1, ids.count { it == "m-65" })
        // m-65 保留首个版本（newerBatch 的 n65，因为第一次合并已加入）
        val m65 = current.first { it.id == "m-65" } as Message.Assistant
        assertEquals("n65", m65.modelId)
    }

    @Test
    fun `no overlap interleaved`() {
        val e = listOf(msg("A", 1, "eA"), msg("C", 3, "eC"))
        val i = listOf(msg("B", 2, "iB"), msg("D", 4, "iD"))
        assertEquiv(e, i, "no-overlap-interleaved")
    }

    @Test
    fun `many same created stable order`() {
        // 全部 created=1，验证稳定排序：existing 全部先于 incoming
        val e = listOf(msg("A", 1, "eA"), msg("B", 1, "eB"))
        val i = listOf(msg("C", 1, "iC"), msg("D", 1, "iD"))
        assertEquiv(e, i, "same-created-stable")
    }

    @Test
    fun `existing covered with same-created incoming unique`() {
        // e=[A(1),C(5)] i=[B(1),A(1),D(1)]：A 被覆盖，B/D 独有同 created=1
        // reference: distinctBy=[A(ex),C,B,D] mapMerge=[A',C,B,D] sortedBy=[A'(1),B(1),D(1),C(5)]
        val e = listOf(msg("A", 1, "eA"), msg("C", 5, "eC"))
        val i = listOf(msg("B", 1, "iB"), msg("A", 1, "iA"), msg("D", 1, "iD"))
        assertEquiv(e, i, "covered-with-same-created-unique")
    }

    @Test
    fun `incoming earlier than existing`() {
        // incoming 全部比 existing 早
        val e = listOf(msg("C", 5, "eC"), msg("D", 6, "eD"))
        val i = listOf(msg("A", 1, "iA"), msg("B", 2, "iB"))
        assertEquiv(e, i, "incoming-earlier")
    }

    @Test
    fun `incoming with internal duplicate plus overlap`() {
        // incoming 内 X 重复，且与 existing 重叠
        val e = listOf(msg("X", 1, "eX"), msg("Y", 2, "eY"))
        val i = listOf(msg("X", 1, "iX1"), msg("Z", 1, "iZ"), msg("X", 1, "iX2"))
        assertEquiv(e, i, "incoming-dup-plus-overlap")
    }

    // ============ 随机对拍 ============

    @Test
    fun `randomized differential test - 300 rounds`() {
        val rng = Random(20260810L) // 固定种子可复现
        // id 池：8 个 id；每个 id 固定一个 created（∈{1,2,3}）——保证前提"同 id created 一致"
        val idPool = (0 until 8).map { "m$it" }
        val createdForId = idPool.associateWith { rng.nextLong(1, 4) }

        repeat(300) { round ->
            fun genList(prefix: String): List<Message> {
                val n = rng.nextInt(0, 13) // 0-12 条（含空列表边界）
                val raw = (0 until n).map { idx ->
                    val id = idPool.random(rng)
                    msg(id, createdForId[id]!!, "$prefix$idx")
                }
                // 模拟写入路径前提：按 created 升序（Kotlin sortedBy 稳定）
                return raw.sortedBy { it.time.created }
            }
            val existing = genList("E")
            val incoming = genList("I")
            assertEquiv(existing, incoming, "round $round")
        }
    }
}
