package dev.leonardo.ocbeacon.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #98（H-7）：ToolSnapshotCache 有界性——快照含整文件内容（MB 级），
 * 导航取消/失败时 onCleared 不触发 → 无界版本永驻。上限 200 条
 *（对齐 DirectoryManager.dirCache 标杆），插入序淘汰最旧。
 */
class ToolSnapshotCacheBoundedTest {

    private fun snap(path: String) = ToolSnapshotCache.Snapshot(
        filePath = path, content = "c", before = null, after = null, toolName = "read"
    )

    @Test
    fun `cache evicts oldest beyond 200 entries`() {
        val cache = ToolSnapshotCache()
        for (i in 0 until 205) {
            cache.put("p$i", snap("f$i"))
        }
        assertEquals("超出上限后保持 200 条", 200, cache.size())
        assertNull("最旧条目被淘汰", cache.get("p0"))
        assertNull("次旧条目被淘汰", cache.get("p4"))
        assertTrue("最新条目保留", cache.get("p204") != null)
        assertTrue("早期保留边界 p5 存在", cache.get("p5") != null)
    }

    @Test
    fun `clear by ids removes entries`() {
        val cache = ToolSnapshotCache()
        cache.put("a", snap("fa"))
        cache.put("b", snap("fb"))
        cache.clear(listOf("a"))
        assertNull(cache.get("a"))
        assertTrue(cache.get("b") != null)
    }
}
