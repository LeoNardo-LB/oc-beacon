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

    // ============ shouldSuppressEvent（事件通知抑制：不要求前台） ============

    @Test
    fun `shouldSuppressEvent does NOT suppress in background for focused session`() {
        // 2026-08-16 语义修正（通知 P1）：后台不再抑制——按 Home 键回桌面后
        // 用户看不到界面，权限/问题/错误通知必须发出（原旧行为会静默吞通知）
        holder.setAppInForeground(false)
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppressEvent("server1", "session1"))
    }

    @Test
    fun `shouldSuppressEvent suppresses in foreground for focused session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertTrue(holder.shouldSuppressEvent("server1", "session1"))
    }

    @Test
    fun `shouldSuppressEvent returns false when no active focus`() {
        holder.setAppInForeground(true)
        assertFalse(holder.shouldSuppressEvent("server1", "session1"))
    }

    @Test
    fun `shouldSuppressEvent returns false for different session`() {
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppressEvent("server1", "session2"))
    }

    @Test
    fun `shouldSuppressEvent returns false for different server`() {
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppressEvent("server2", "session1"))
    }

    @Test
    fun `shouldSuppressEvent returns false after focus cleared`() {
        holder.setActiveFocus("server1", "session1")
        holder.setActiveFocus(null, null)
        assertFalse(holder.shouldSuppressEvent("server1", "session1"))
    }
}
