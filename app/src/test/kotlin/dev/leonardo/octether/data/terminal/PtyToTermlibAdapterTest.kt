package dev.leonardo.octether.data.terminal

import app.cash.turbine.test
import dev.leonardo.octether.data.dto.common.PtySocket
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
            writeInput = { data, offset, length -> received.write(data, offset, length) },
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
            writeInput = { _, _, _ -> },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        // Drive the keyboard callback the way termlib would.
        adapter.dispatchKeyboardOutput("ls\r\n".toByteArray())

        // Give the launch{} in dispatchKeyboardOutput a tick to run.
        socket.completion.await()
        adapter.release()

        assertEquals("ls\r\n", socket.sent.joinToString(""))
    }

    @Test
    fun `onKeyboardInput callback never calls emulator methods (reentrancy guard)`() = runTest {
        // The adapter's onKeyboardInput path (dispatchKeyboardOutput) only
        // touches the socket. Verify writeInput is NOT invoked during keyboard
        // dispatch by tracking call count on the writeInput lambda.
        var writeInputCallCount = 0
        val adapter = PtyToTermlibAdapter(
            scope = this,
            writeInput = { _, _, _ -> writeInputCallCount++ },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        val before = writeInputCallCount
        adapter.dispatchKeyboardOutput("x".toByteArray())
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
            writeInput = { _, _, _ -> },
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
            writeInput = { _, _, _ -> },
        )
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        adapter.release()
        adapter.release() // second call must not throw
        // release() launches socket.close() asynchronously on the test scope;
        // drain pending coroutines before asserting.
        advanceUntilIdle()

        assertTrue(socket.closed)
    }

    @Test
    fun `cursorKeysApplicationMode tracks DECSET 1 and DECRST 1`() = runTest {
        val adapter = PtyToTermlibAdapter(
            scope = this,
            writeInput = { _, _, _ -> },
        )
        val socket = FakePtySocket(frames = listOf("\u001b[?1h"))
        adapter.bind(socket)
        socket.completion.await()
        assertTrue("DECSET 1 should set application mode", adapter.cursorKeysApplicationMode.value)

        // Reset state by binding another socket that emits DECRST 1.
        val socket2 = FakePtySocket(frames = listOf("\u001b[?1l"))
        adapter.bind(socket2)
        socket2.completion.await()
        assertFalse("DECRST 1 should clear application mode", adapter.cursorKeysApplicationMode.value)

        adapter.release()
    }

    @Test
    fun `cursorKeysApplicationMode default is false`() = runTest {
        val adapter = PtyToTermlibAdapter(
            scope = this,
            writeInput = { _, _, _ -> },
        )
        assertFalse(adapter.cursorKeysApplicationMode.value)
        adapter.release()
    }
}

/**
 * Minimal in-memory PtySocket. The real PtySocket delegates to a Ktor
 * ClientWebSocketSession; we only need readLoop + send + close semantics.
 *
 * PtySocket is `open class` (P1-6 fix) so this fake can override its methods
 * without needing the underlying WebSocket session.
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
        // Block until cancelled so the reader coroutine stays alive like the
        // real one (which blocks on the WebSocket incoming channel).
        try { delay(Long.MAX_VALUE) } catch (_: Exception) {}
    }
}
