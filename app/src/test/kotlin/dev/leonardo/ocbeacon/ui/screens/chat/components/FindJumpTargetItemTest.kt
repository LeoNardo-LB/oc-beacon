package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * findJumpTargetItem 单测（2026-08-20 分片适配——任务定位到分片 turn 必失败的根因修复）。
 */
class FindJumpTargetItemTest {

    private fun item(index: Int, key: String, offset: Int = 0, size: Int = 100): LazyListItemInfo =
        object : LazyListItemInfo {
            override val index: Int = index
            override val key: Any = key
            override val offset: Int = offset
            override val size: Int = size
        }

    @Test
    fun exactMatchWinsOverChunks() {
        val items = listOf(
            item(0, "t_other"),
            item(1, "t_target"),
            item(2, "t_target2#c0"),
        )
        assertEquals("t_target", findJumpTargetItem(items, "t_target")?.key)
    }

    @Test
    fun chunkedTargetMatchesFirstChunkByMinIndex() {
        val items = listOf(
            item(0, "banner"),
            item(1, "t_target#c0"),
            item(2, "t_target#c1"),
            item(3, "t_target#c2"),
        )
        val hit = findJumpTargetItem(items, "t_target")
        assertEquals("首 chunk（消息顶边）", "t_target#c0", hit?.key)
        assertEquals(1, hit?.index)
    }

    @Test
    fun chunkPrefixMustNotCrossMessages() {
        // "t_target#c..." 前缀不得误配 "t_target2#c0"（#c 后还有别的消息 id）
        val items = listOf(item(0, "t_target2#c0"), item(1, "t_x"))
        assertNull(findJumpTargetItem(items, "t_target"))
    }

    @Test
    fun userTargetUnchunkedStillWorks() {
        val items = listOf(item(0, "t_a#c0"), item(1, "u_target"), item(2, "t_b"))
        assertEquals("u_target", findJumpTargetItem(items, "u_target")?.key)
    }

    @Test
    fun visibleChunksOutOfOrderStillPicksFirst() {
        // visibleItemsInfo 通常按 index 序，但防御乱序输入
        val items = listOf(
            item(5, "t_target#c5"),
            item(2, "t_target#c2"),
        )
        assertEquals(2, findJumpTargetItem(items, "t_target")?.index)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(findJumpTargetItem(emptyList(), "u_x"))
        assertNull(findJumpTargetItem(listOf(item(0, "t_a")), "u_x"))
    }
}