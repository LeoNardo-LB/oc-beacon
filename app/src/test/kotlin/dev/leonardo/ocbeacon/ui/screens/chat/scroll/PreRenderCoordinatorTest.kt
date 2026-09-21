package dev.leonardo.ocbeacon.ui.screens.chat.scroll

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #423 I3:视口租约计数单测——activeCount 的正确性是守卫/MSGEFFECT/PENDING/
 * 强滚锚底全部让位点的前提(租约泄漏=守卫永久哑火,重复释放=提前开战)。
 * try/finally 保证取消/异常路径必释放(LaunchedEffect 快速反向 toggle 即取消路径)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreRenderCoordinatorTest {

    @Test
    fun `lease held during episode and released after`() = runTest {
        assertFalse(PreRenderCoordinator.hasActiveTransactions)
        PreRenderCoordinator.withEpisode {
            assertTrue("episode 内租约必须持有", PreRenderCoordinator.hasActiveTransactions)
        }
        assertFalse("正常退出必须释放", PreRenderCoordinator.hasActiveTransactions)
    }

    @Test
    fun `nested episodes are counted`() = runTest {
        PreRenderCoordinator.withEpisode {
            PreRenderCoordinator.withEpisode {
                assertEquals(2, PreRenderCoordinator.activeCount)
            }
            assertEquals(1, PreRenderCoordinator.activeCount)
        }
        assertEquals(0, PreRenderCoordinator.activeCount)
    }

    @Test
    fun `lease released on exception`() = runTest {
        runCatching { PreRenderCoordinator.withEpisode { error("boom") } }
        assertFalse("异常路径 finally 必释放", PreRenderCoordinator.hasActiveTransactions)
    }

    @Test
    fun `lease released on cancellation`() = runTest {
        val job = launch { PreRenderCoordinator.withEpisode { awaitCancellation() } }
        advanceTimeBy(1)
        assertTrue("协程运行中租约持有", PreRenderCoordinator.hasActiveTransactions)
        job.cancel()
        advanceTimeBy(1)
        assertFalse("取消路径 finally 必释放(快速反向 toggle 依赖)", PreRenderCoordinator.hasActiveTransactions)
    }
}
