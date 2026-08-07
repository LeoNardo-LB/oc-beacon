package dev.leonardo.ocbeacon.ui.screens.chat.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingOverlayStateTest {

    @Test
    fun `hidden when both ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = true, messagesReady = true, timeoutElapsed = false))
    }

    @Test
    fun `shown when model config not ready`() {
        assertTrue(shouldShowLoadingOverlay(modelReady = false, messagesReady = true, timeoutElapsed = false))
    }

    @Test
    fun `shown when messages not ready`() {
        assertTrue(shouldShowLoadingOverlay(modelReady = true, messagesReady = false, timeoutElapsed = false))
    }

    @Test
    fun `hidden after timeout even if nothing ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = false, messagesReady = false, timeoutElapsed = true))
    }

    @Test
    fun `hidden after timeout when partially ready`() {
        assertFalse(shouldShowLoadingOverlay(modelReady = false, messagesReady = true, timeoutElapsed = true))
    }
}
