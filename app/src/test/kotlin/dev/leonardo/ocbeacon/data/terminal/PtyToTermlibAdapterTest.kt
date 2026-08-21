package dev.leonardo.ocbeacon.data.terminal

import app.cash.turbine.test
import dev.leonardo.ocbeacon.data.dto.common.PtySocket
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PtyToTermlibAdapterTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `writeInput bytes are forwarded when socket emits text`() = runTest {
        val received = java.io.ByteArrayOutputStream()
        val adapter = PtyToTermlibAdapter(
            scope = this,
            onPtyOutput = { data, offset, length -> received.write(data, offset, length) },
        )

        val socket = FakePtySocket(frames = listOf("hello"))
        adapter.bind(socket)

        socket.completion.await()
        adapter.release()

        assertEquals("hello", String(received.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun `keyboard output from the emulator is forwarded to the socket`() = runTest {
        val adapter = PtyToTermlibAdapter(
            scope = this,
            onPtyOutput = { _, _, _ -> },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        // 以 termlib 的方式驱动键盘回调。
        adapter.sendInput("ls\r\n")

        // 给 dispatchKeyboardOutput 中的 launch{} 一个运行的机会。
        socket.completion.await()
        adapter.release()

        assertEquals("ls\r\n", socket.sent.joinToString(""))
    }

    @Test
    fun `onKeyboardInput callback never calls emulator methods (reentrancy guard)`() = runTest {
        // 适配器的 onKeyboardInput 路径（dispatchKeyboardOutput）只
        // 接触 socket。通过跟踪 writeInput lambda 的调用次数，
        // 验证键盘分发期间不会调用 writeInput。
        var writeInputCallCount = 0
        val adapter = PtyToTermlibAdapter(
            scope = this,
            onPtyOutput = { _, _, _ -> writeInputCallCount++ },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        val before = writeInputCallCount
        adapter.sendInput("x")
        socket.completion.await()
        adapter.release()

        assertEquals(
            "writeInput must NOT be invoked from dispatchKeyboardOutput",
            before,
            writeInputCallCount,
        )
    }

    @Test
    fun `version bumps on every writeInput`() = runTest {
        val adapter = PtyToTermlibAdapter(
            scope = this,
            onPtyOutput = { _, _, _ -> },
        )

        adapter.version.test {
            assertEquals(0L, awaitItem())
            adapter.notifyWriteInputComplete()
            assertEquals(1L, awaitItem())
            adapter.notifyWriteInputComplete()
            assertEquals(2L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        adapter.release()
    }

    @Test
    fun `release is idempotent and closes the socket`() = runTest {
        val adapter = PtyToTermlibAdapter(
            scope = this,
            onPtyOutput = { _, _, _ -> },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        adapter.release()
        adapter.release() // 第二次调用不得抛异常
        // release() 会在 test scope 上异步启动 socket.close()；
        // 断言前先排空待处理的协程。
        advanceUntilIdle()

        assertTrue(socket.closed)
    }
}

/**
 * 最小化的内存版 PtySocket。真实的 PtySocket 委托给 Ktor
 * ClientWebSocketSession；我们只需要 readLoop + send + close 语义。
 *
 * PtySocket 是 `open class`（P1-6 修复），因此该 fake 可以重写其方法，
 * 而无需底层 WebSocket 会话。
 */
private class FakePtySocket(
    private val frames: List<String>,
) : PtySocket(session = mockk(relaxed = true)) {
    val sent = mutableListOf<String>()
    var closed = false
    val completion = CompletableDeferred<Unit>()

    override suspend fun send(input: String) {
        sent.add(input)
    }
    override suspend fun close() {
        closed = true
    }
    override suspend fun readLoop(onText: suspend (String) -> Unit) {
        for (frame in frames) onText(frame)
        completion.complete(Unit)
        // 阻塞直到被取消，使 reader 协程像真实协程一样保持存活
        // （真实实现会阻塞在 WebSocket 接收通道上）。
        try { delay(Long.MAX_VALUE) } catch (_: Exception) {}
    }
}
