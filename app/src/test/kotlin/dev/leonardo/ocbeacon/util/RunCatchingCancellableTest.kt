package dev.leonardo.ocbeacon.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * runCatchingCancellable 工具测试（#128 根因修复）。
 *
 * 反模式对照：runCatching 捕获所有 Throwable 包括 CancellationException——
 * 协程取消被吞 → 取消后继续执行 → 取消链 handler 异常 → CompletionHandlerException
 * （beta 真机崩溃，2026-08-14 反混淆定位 HomeViewModel.refreshServerSettingsAvailability）。
 */
class RunCatchingCancellableTest {

    @Test
    fun `returns success result on normal return`() = runTest {
        val result = runCatchingCancellable { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `returns failure result on regular exception`() = runTest {
        val result = runCatchingCancellable { throw IOException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rethrows CancellationException instead of swallowing it`() = runTest {
        // #128 根因：runCatching 会吞掉 CancellationException（Kotlin 已知陷阱），
        // 导致协程不响应取消继续执行。修复后必须重新抛出，取消才能正确传播。
        var caught: CancellationException? = null
        try {
            runCatchingCancellable { throw CancellationException("job cancelled") }
            fail("CancellationException must be rethrown, not swallowed")
        } catch (e: CancellationException) {
            caught = e
        }
        assertEquals("job cancelled", caught?.message)
    }

    @Test
    fun `does not produce failure result for cancellation`() = runTest {
        // 取消不应被包装为 Result.failure（那会让调用方误以为业务失败并继续执行）
        var result: Result<Int>? = null
        try {
            result = runCatchingCancellable { throw CancellationException("cancelled") }
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(null, result)
    }
}
