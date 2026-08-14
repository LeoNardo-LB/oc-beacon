package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.usecase.CreateDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetServerPathsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ProbeDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.SearchDirectoriesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #134（D2-L38）：getServerPaths 失败不得毒化缓存。
 * 原实现失败也缓存空 ServerPaths() 且永不失效——一次瞬时网络失败
 * 导致整个 VM 生命周期 home/cwd 全空；现失败不缓存，冷却后自动重试。
 */
class DirectoryManagerServerPathsTest {

    private fun manager(
        useCase: GetServerPathsUseCase,
    ) = DirectoryManager(
        serverId = "srv1",
        getServerPathsUseCase = useCase,
        probeDirectoryUseCase = mockk(relaxed = true),
        searchDirectoriesUseCase = mockk(relaxed = true),
        createDirectoryUseCase = mockk(relaxed = true),
        fileRepository = mockk(relaxed = true),
    )

    @Test
    fun `transient failure is not cached - cooldown then retry succeeds`() = runTest {
        val useCase = mockk<GetServerPathsUseCase>()
        var fail = true
        coEvery { useCase.invoke("srv1") } answers {
            if (fail) Result.failure(RuntimeException("network down"))
            else Result.success(ServerPaths(home = "/home/opencode"))
        }
        val mgr = manager(useCase)

        // 第一次失败：返回空路径，且不缓存
        assertEquals("", mgr.getServerPaths().home)

        // 冷却期内（失败时间戳刚记录）不触发网络请求
        assertEquals("", mgr.getServerPaths().home)

        // 网络恢复 + 冷却期外：自动重试成功
        fail = false
        // 推进失败时间戳到冷却期外（模拟真实时间流逝）
        val failureAtField = DirectoryManager::class.java.getDeclaredField("serverPathsFailureAt")
        failureAtField.isAccessible = true
        failureAtField.setLong(mgr, System.currentTimeMillis() - DirectoryManager.SERVER_PATHS_FAILURE_COOLDOWN_MS - 1_000L)

        assertEquals("/home/opencode", mgr.getServerPaths().home)

        // 成功后缓存命中：不再触发请求
        assertEquals("/home/opencode", mgr.getServerPaths().home)
    }

    @Test
    fun `cooldown decision is pure and time-based`() {
        val cooldown = DirectoryManager.SERVER_PATHS_FAILURE_COOLDOWN_MS
        val now = 10_000L
        assertFalse("冷却期内不得重试", DirectoryManager.shouldRetryServerPaths(now, now))
        assertFalse("差 1ms 仍在冷却期", DirectoryManager.shouldRetryServerPaths(now, now - cooldown + 1))
        assertTrue("到达冷却期可重试", DirectoryManager.shouldRetryServerPaths(now, now - cooldown))
        assertTrue("远超冷却期可重试", DirectoryManager.shouldRetryServerPaths(now, 0L))
    }

    @Test
    fun `successful result is cached`() = runTest {
        val useCase = mockk<GetServerPathsUseCase>()
        var calls = 0
        coEvery { useCase.invoke("srv1") } answers {
            calls++
            Result.success(ServerPaths(home = "/home/opencode"))
        }
        val mgr = manager(useCase)

        mgr.getServerPaths()
        mgr.getServerPaths()
        assertEquals("成功结果只请求一次（缓存命中）", 1, calls)
    }
}
