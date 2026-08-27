package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #251 回归：调试后端提升纯函数——「最近激活的调试后端至多一个自连」。
 * 根因背景：调试通道曾无条件 autoConnect=true，一次性测试后端永久加入
 * 开机自连集合（真机实证 Auto-connecting 2 server(s) + 4200/question 空轮询）。
 */
class ServerConfigDebugPromotionTest {

    private fun server(id: String, autoConnect: Boolean = false, debug: Boolean = false) =
        ServerConfig(id = id, url = "http://127.0.0.1:$id", name = id, autoConnect = autoConnect, fromDebugChannel = debug)

    @Test
    fun `target entry promoted with marker`() {
        val result = ServerConfig.applyDebugBackendPromotion(
            listOf(server("a"), server("b")), targetId = "b")
        val b = result.first { it.id == "b" }
        assertTrue(b.autoConnect)
        assertTrue(b.fromDebugChannel)
    }

    @Test
    fun `other debug-marked autoconnect entry demoted`() {
        val result = ServerConfig.applyDebugBackendPromotion(
            listOf(server("stale", autoConnect = true, debug = true), server("new")),
            targetId = "new")
        val stale = result.first { it.id == "stale" }
        assertFalse("陈旧调试后端应退出自连集合", stale.autoConnect)
        assertTrue("调试标记保留（仍是系统管理位）", stale.fromDebugChannel)
    }

    @Test
    fun `manual pinned entry untouched by debug promotion`() {
        val result = ServerConfig.applyDebugBackendPromotion(
            listOf(server("manual", autoConnect = true, debug = false), server("dbg")),
            targetId = "dbg")
        val manual = result.first { it.id == "manual" }
        assertTrue("手动 pin 是用户管理位，调试激活不得降级", manual.autoConnect)
        assertFalse(manual.fromDebugChannel)
    }

    @Test
    fun `promotion is idempotent`() {
        val servers = listOf(server("daily", autoConnect = true, debug = true), server("other"))
        val once = ServerConfig.applyDebugBackendPromotion(servers, targetId = "daily")
        val twice = ServerConfig.applyDebugBackendPromotion(once, targetId = "daily")
        assertEquals(once, twice)
    }

    @Test
    fun `switching target demotes previous debug backend`() {
        val promoted = ServerConfig.applyDebugBackendPromotion(
            listOf(server("v1test"), server("daily")), targetId = "v1test")
        val switched = ServerConfig.applyDebugBackendPromotion(promoted, targetId = "daily")
        assertFalse("切走后旧调试后端不再自连", switched.first { it.id == "v1test" }.autoConnect)
        assertTrue(switched.first { it.id == "daily" }.autoConnect)
    }

    @Test
    fun `empty list passes through`() {
        assertTrue(ServerConfig.applyDebugBackendPromotion(emptyList(), "x").isEmpty())
    }
}