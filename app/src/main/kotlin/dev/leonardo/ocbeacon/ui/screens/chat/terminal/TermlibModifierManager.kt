package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.ModifierManager

/**
 * Bridges the toolbar's Ctrl/Alt latch buttons to termlib's [ModifierManager].
 *
 * The toolbar in ChatTerminalView exposes latch buttons (tap to toggle on,
 * tap again to release, or auto-release after the next keystroke). termlib's
 * Terminal composable accepts a [ModifierManager] that it consults when
 * dispatching keys — so driving this object's state from the toolbar buttons
 * makes the latch semantics work without us intercepting every KeyEvent.
 *
 * "Transient" modifiers (per termlib's contract) are cleared after a single
 * key dispatch. We treat latched Ctrl/Alt as transient so a toolbar tap
 * affects exactly one keystroke, matching the old behavior.
 */
class TermlibModifierManager : ModifierManager {
    private val _ctrlActive = MutableStateFlow(false)
    private val _altActive = MutableStateFlow(false)
    private val _shiftActive = MutableStateFlow(false)

    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()
    val shiftActive: StateFlow<Boolean> = _shiftActive.asStateFlow()

    fun setCtrl(active: Boolean) { _ctrlActive.value = active }
    fun setAlt(active: Boolean) { _altActive.value = active }
    fun setShift(active: Boolean) { _shiftActive.value = active }

    fun toggleCtrl() { _ctrlActive.value = !_ctrlActive.value }
    fun toggleAlt() { _altActive.value = !_altActive.value }

    override fun isCtrlActive(): Boolean = _ctrlActive.value
    override fun isAltActive(): Boolean = _altActive.value
    override fun isShiftActive(): Boolean = _shiftActive.value

    /**
     * Called by termlib after a key dispatch. We clear latched Ctrl/Alt so
     * the toolbar visually returns to the inactive state after one keystroke.
     * Shift is preserved (it behaves like a held shift in most terminals).
     */
    override fun clearTransients() {
        _ctrlActive.value = false
        _altActive.value = false
    }
}
