package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.ModifierManager

/**
 * 将工具栏的 Ctrl/Alt 锁定按钮桥接到 termlib 的 [ModifierManager]。
 *
 * ChatTerminalView 中的工具栏暴露了锁定按钮（点击切换为开启，
 * 再次点击释放，或在下一次按键后自动释放）。termlib 的
 * Terminal composable 接受一个 [ModifierManager]，在派发按键时会查询它——
 * 因此从工具栏按钮驱动此对象的状态即可实现锁定语义，
 * 无需我们拦截每一个 KeyEvent。
 *
 * "瞬时"修饰键（按 termlib 的约定）在单次按键派发后清除。
 * 我们将锁定的 Ctrl/Alt 视为瞬时的，使一次工具栏点击
 * 恰好影响一次按键，与旧行为一致。
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
     * 由 termlib 在按键派发后调用。我们清除锁定的 Ctrl/Alt，
     * 使工具栏在一次按键后视觉上回到非激活状态。
     * Shift 保留（在大多数终端中它表现得像按住的 shift）。
     */
    override fun clearTransients() {
        _ctrlActive.value = false
        _altActive.value = false
    }
}
