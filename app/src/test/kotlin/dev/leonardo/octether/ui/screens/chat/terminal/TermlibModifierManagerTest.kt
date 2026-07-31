package dev.leonardo.octether.ui.screens.chat.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermlibModifierManagerTest {

    @Test
    fun `toggle flips ctrl state`() {
        val m = TermlibModifierManager()
        assertFalse(m.isCtrlActive())
        m.toggleCtrl()
        assertTrue(m.isCtrlActive())
        m.toggleCtrl()
        assertFalse(m.isCtrlActive())
    }

    @Test
    fun `clearTransients resets ctrl and alt but not shift`() {
        val m = TermlibModifierManager()
        m.setCtrl(true)
        m.setAlt(true)
        m.setShift(true)

        m.clearTransients()

        assertFalse(m.isCtrlActive())
        assertFalse(m.isAltActive())
        assertTrue(m.isShiftActive())
    }

    @Test
    fun `setters are idempotent`() {
        val m = TermlibModifierManager()
        m.setAlt(true)
        m.setAlt(true)
        assertTrue(m.isAltActive())
        m.setAlt(false)
        assertFalse(m.isAltActive())
    }
}
