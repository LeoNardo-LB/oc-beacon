package dev.leonardo.ocbeacon.data.terminal

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTerminalSessionEmulatorTest {

    private fun newSession(): RemoteTerminalSession {
        val bridge = PtyToTermlibAdapter(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onPtyOutput = { _, _, _ -> },
        )
        return RemoteTerminalSession(bridge = bridge)
    }

    private val ESC = Char(27)
    private val BEL = Char(7)

    @Test
    fun ptyOutputLandsInEmulatorBuffer() {
        val session = newSession()
        session.updateSize(80, 24, 8, 16)
        val prompt = ESC + "]0;title" + BEL + "[01;32mleo@host[00m:[01;34m~/code[00m$ ".replace("[", ESC + "[")
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        session.feedPtyOutputOnMain(bytes, bytes.size)
        val text = emulatorText(session)
        assertTrue("buffer should contain prompt, got: " + text, text.contains("leo@host"))
    }

    @Test
    fun productionSequenceWarmupThenResizeThenFeed() {
        val session = newSession()
        session.updateSize(80, 24, 0, 0)
        session.updateSize(52, 49, 26, 46)
        val prompt = ESC + "]0;title" + BEL + "[32mleo@host[0m:[34m~/code[0m$ ".replace("[", ESC + "[")
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        session.feedPtyOutputOnMain(bytes, bytes.size)
        val text = emulatorText(session)
        assertTrue("warmup+resize path should keep text, got: " + text, text.contains("leo@host"))
    }

    private fun emulatorText(session: RemoteTerminalSession): String {
        val emu = session.getEmulator()!!
        val sb = StringBuilder()
        val screen = emu.screen
        for (row in 0 until emu.mRows) {
            sb.append(screen.getSelectedText(0, row, emu.mColumns - 1, row).trimEnd())
            sb.append('\n')
        }
        return sb.toString()
    }
}
