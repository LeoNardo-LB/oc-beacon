package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #428 同族加固:[MarkdownParsedStateCache] 的机械不变量(JVM 纯逻辑)。
 *
 * 覆盖:命中/未命中、Loading 拒入、容量上界、LRU 访问序淘汰(收起闭合帧
 * 跨组合零占位帧依赖命中语义,淘汰序错误=静默退化回占位跳变)。
 */
class MarkdownParsedStateCacheTest {

    @Before
    fun setUp() {
        MarkdownParsedStateCache.clearForTest()
    }

    @After
    fun tearDown() {
        MarkdownParsedStateCache.clearForTest()
    }

    private fun parsedState(content: String): State = parseMarkdown(content)

    @Test
    fun `put 后同内容命中返回同一终态`() {
        val st = parsedState("# 标题\n正文")
        MarkdownParsedStateCache.put("k1", st)
        assertEquals(st, MarkdownParsedStateCache.get("k1"))
    }

    @Test
    fun `未命中返回 null`() {
        assertNull(MarkdownParsedStateCache.get("absent"))
    }

    @Test
    fun `Loading 非终态不入缓存`() {
        MarkdownParsedStateCache.put("k1", State.Loading())
        assertNull(MarkdownParsedStateCache.get("k1"))
        assertEquals(0, MarkdownParsedStateCache.size())
    }

    @Test
    fun `容量上界 MAX_ENTRIES 超限淘汰最旧`() {
        repeat(MarkdownParsedStateCache.MAX_ENTRIES + 1) { i ->
            MarkdownParsedStateCache.put("c$i", parsedState("内容 $i"))
        }
        assertEquals(MarkdownParsedStateCache.MAX_ENTRIES, MarkdownParsedStateCache.size())
        // c0 为最旧,应被淘汰;c1..c32 仍在
        assertNull(MarkdownParsedStateCache.get("c0"))
        assertNotNull(MarkdownParsedStateCache.get("c1"))
        assertNotNull(MarkdownParsedStateCache.get("c${MarkdownParsedStateCache.MAX_ENTRIES}"))
    }

    @Test
    fun `LRU 访问序 get 触碰后免于淘汰`() {
        repeat(MarkdownParsedStateCache.MAX_ENTRIES) { i ->
            MarkdownParsedStateCache.put("c$i", parsedState("内容 $i"))
        }
        // 触碰 c0:此后最久未访问者是 c1
        assertNotNull(MarkdownParsedStateCache.get("c0"))
        MarkdownParsedStateCache.put("new", parsedState("新条目"))
        assertEquals(MarkdownParsedStateCache.MAX_ENTRIES, MarkdownParsedStateCache.size())
        assertNotNull(MarkdownParsedStateCache.get("c0"))
        assertNull(MarkdownParsedStateCache.get("c1"))
        assertNotNull(MarkdownParsedStateCache.get("new"))
    }

    @Test
    fun `同键覆写保持单条目`() {
        val st1 = parsedState("# 一")
        val st2 = parsedState("# 二")
        MarkdownParsedStateCache.put("k", st1)
        MarkdownParsedStateCache.put("k", st2)
        assertEquals(1, MarkdownParsedStateCache.size())
        assertEquals(st2, MarkdownParsedStateCache.get("k"))
    }

    @Test
    fun `解析终态为 Success 非 Loading`() {
        // 契约锚:parseMarkdown 返回可缓存的终态(若库行为变化导致返回
        // Loading,缓存将永远 miss——此测试先红,提示重新评估分档阈值)
        assertTrue(parsedState("# hi") !is State.Loading)
    }
}
