package dev.leonardo.ocbeacon.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * SafeCatch 工具测试（#60）。
 */
class SafeCatchTest {

    @Test
    fun `returns block result on success`() = runTest {
        val result = safeCatch(
            block = { 42 },
            fallback = { 0 }
        )
        assertEquals(42, result)
    }

    @Test
    fun `calls fallback on regular exception`() = runTest {
        val fallbackCalls = mutableListOf<String>()
        val result = safeCatch(
            block = { throw IOException("boom") },
            fallback = { e ->
                fallbackCalls.add(e.message ?: "")
                7
            }
        )
        assertEquals(7, result)
        assertEquals(listOf("boom"), fallbackCalls)
    }

    @Test
    fun `rethrows CancellationException and does not call fallback`() = runTest {
        var fallbackCalled = false
        var caught: CancellationException? = null
        try {
            safeCatch(
                block = { throw CancellationException("cancelled") },
                fallback = {
                    fallbackCalled = true
                    0
                }
            )
        } catch (e: CancellationException) {
            caught = e
        }
        assertEquals("cancelled", caught?.message)
        assertEquals(false, fallbackCalled)
    }
}
