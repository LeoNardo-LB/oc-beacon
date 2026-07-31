package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionFocusHolderTest {

    private lateinit var holder: SessionFocusHolder

    @Before
    fun setup() {
        holder = SessionFocusHolder()
    }

    @Test
    fun `shouldSuppress returns false when app is in background`() {
        holder.setActiveFocus("server1", "session1")
        holder.setAppInForeground(false)
        assertFalse(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns false when no active focus`() {
        holder.setAppInForeground(true)
        assertFalse(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns true when foreground and same session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertTrue(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns false when foreground but different session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppress("server1", "session2"))
    }

    @Test
    fun `shouldSuppress returns false when different server same session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppress("server2", "session1"))
    }

    @Test
    fun `setActiveFocus null clears focus`() {
        holder.setActiveFocus("server1", "session1")
        holder.setActiveFocus(null, null)
        assertEquals(null, holder.activeFocus.value)
    }

    @Test
    fun `setActiveFocus with null serverId does not set focus`() {
        holder.setActiveFocus(null, "session1")
        assertEquals(null, holder.activeFocus.value)
    }
}
