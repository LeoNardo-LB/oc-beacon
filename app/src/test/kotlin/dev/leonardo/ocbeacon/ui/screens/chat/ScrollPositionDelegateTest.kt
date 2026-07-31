package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScrollPositionDelegateTest {

    private lateinit var delegate: ScrollPositionDelegate

    @Before
    fun setUp() {
        delegate = ScrollPositionDelegate()
    }

    @Test
    fun `initial state has zero defaults`() {
        assertEquals(0, delegate.savedLazyIndex)
        assertEquals(0, delegate.savedScrollOffset)
        assertEquals(0, delegate.scrollRestoreVersion)
        assertFalse(delegate.pendingScrollRestore)
    }

    @Test
    fun `saveScrollPosition sets values and marks pending`() {
        delegate.saveScrollPosition(lazyIndex = 5, offset = 200)

        assertEquals(5, delegate.savedLazyIndex)
        assertEquals(200, delegate.savedScrollOffset)
        assertEquals(1, delegate.scrollRestoreVersion)
        assertTrue(delegate.pendingScrollRestore)
    }

    @Test
    fun `bumpScrollRestoreIfPending increments version only when pending`() {
        delegate.saveScrollPosition(3, 50)
        val versionAfterSave = delegate.scrollRestoreVersion

        delegate.bumpScrollRestoreIfPending()
        assertEquals(versionAfterSave + 1, delegate.scrollRestoreVersion)
    }

    @Test
    fun `bumpScrollRestoreIfPending is no-op when no pending restore`() {
        val initialVersion = delegate.scrollRestoreVersion

        delegate.bumpScrollRestoreIfPending()
        assertEquals(initialVersion, delegate.scrollRestoreVersion)
    }

    @Test
    fun `clearPendingScrollRestore prevents subsequent bump`() {
        delegate.saveScrollPosition(1, 10)
        delegate.clearPendingScrollRestore()
        assertFalse(delegate.pendingScrollRestore)

        val versionAfterClear = delegate.scrollRestoreVersion
        delegate.bumpScrollRestoreIfPending()
        assertEquals(versionAfterClear, delegate.scrollRestoreVersion)
    }

    @Test
    fun `multiple saves increment version each time`() {
        delegate.saveScrollPosition(1, 0)
        delegate.saveScrollPosition(2, 0)
        delegate.saveScrollPosition(3, 0)

        assertEquals(3, delegate.scrollRestoreVersion)
        assertEquals(3, delegate.savedLazyIndex)
    }

    @Test
    fun `save after clear re-arms pending`() {
        delegate.saveScrollPosition(1, 0)
        delegate.clearPendingScrollRestore()
        assertFalse(delegate.pendingScrollRestore)

        delegate.saveScrollPosition(2, 0)
        assertTrue(delegate.pendingScrollRestore)
    }
}
