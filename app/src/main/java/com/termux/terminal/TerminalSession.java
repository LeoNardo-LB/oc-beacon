package com.termux.terminal;

/**
 * Bridge between a terminal view and a terminal emulator backend.
 *
 * Originally Termux's concrete TerminalSession (local PTY subprocess model).
 * Vendored here as an interface: OC Beacon drives the emulator from a remote
 * PTY (WebSocket transport to the OpenCode server), so the local-process
 * implementation (JNI.createSubprocess / waitFor thread) was dropped and
 * replaced by {@code RemoteTerminalSession} which implements this interface.
 *
 * API surface = exactly the methods TerminalView calls on a session
 * (audited: write, writeCodePoint, getEmulator, updateSize).
 */
public interface TerminalSession {

    /** Keyboard input: send text to the PTY (remote echo model - no local echo). */
    void write(String data);

    /** Keyboard input: send a single code point, optionally requiring ctrl modifier. */
    void writeCodePoint(boolean requireControl, int codePoint);

    /** The emulator whose screen buffer this session renders. */
    TerminalEmulator getEmulator();

    /**
     * Called by the view when its pixel size changes. Implementations must create
     * (lazy) or resize the emulator and propagate the new size to the PTY.
     */
    void updateSize(int columns, int rows, int fontWidthPx, int fontLineSpacingPx);
}
