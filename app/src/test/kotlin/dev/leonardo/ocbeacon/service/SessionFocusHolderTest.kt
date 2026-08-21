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
        // 2026-08-16 语义（通知 P1）：后台不抑制——按 Home 回桌面后用户看不到界面，
        // 权限/问题/错误通知必须发出（原 shouldSuppressEvent 旧行为会静默吞通知）
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

    @Test
    fun `shouldSuppress returns false after focus cleared`() {
        holder.setActiveFocus("server1", "session1")
        holder.setActiveFocus(null, null)
        assertFalse(holder.shouldSuppress("server1", "session1"))
    }
}
