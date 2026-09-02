package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.local.SessionCacheStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #306：服务器删除必须清会话缓存孤儿（serverId 不复用，残留行永不可达）。 */
class ServerRepositoryImplCacheTest {

    @Test
    fun `removeServer clears session cache`() = runTest {
        val dataRepo = mockk<ServerDataStore>(relaxed = true)
        val cache = mockk<SessionCacheStore>(relaxed = true)
        val repo = ServerRepositoryImpl(dataRepo, mockk<ProviderApi>(relaxed = true), cache)

        repo.removeServer("srv1").getOrThrow()

        coVerify(exactly = 1) { cache.deleteForServer("srv1") }
    }

    @Test
    fun `cache cleanup failure does not fail removeServer`() = runTest {
        // DataStore 删除已成功，缓存清理失败不能把整个删除报成失败
        //（重试 removeServer 对已不存在 id 的行为不可靠 → 「已删却报失败」循环）
        val dataRepo = mockk<ServerDataStore>(relaxed = true)
        val cache = mockk<SessionCacheStore>()
        coEvery { cache.deleteForServer(any()) } throws IllegalStateException("db closed")
        val repo = ServerRepositoryImpl(dataRepo, mockk<ProviderApi>(relaxed = true), cache)

        val result = repo.removeServer("srv1")

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }
}
