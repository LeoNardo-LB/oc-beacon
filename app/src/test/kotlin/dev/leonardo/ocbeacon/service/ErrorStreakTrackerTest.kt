package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #155 R3/Q10：错误 streak 状态机（通知侧与提示音侧共用语义）。 */
class ErrorStreakTrackerTest {

    @Test
    fun firstErrorNotifies() {
        val t = ErrorStreakTracker()
        assertTrue(t.onError("s1", "sess1"))
    }

    @Test
    fun consecutiveErrorsSilencedUntilReset() {
        val t = ErrorStreakTracker()
        assertTrue(t.onError("s1", "sess1"))
        assertFalse("second error in streak must be silent", t.onError("s1", "sess1"))
        assertFalse(t.onError("s1", "sess1"))
    }

    @Test
    fun successfulTurnResetsStreak() {
        val t = ErrorStreakTracker()
        t.onError("s1", "sess1")
        t.reset("s1", "sess1") // onTurnCompleted / onUserMessage 同一入口
        assertTrue("after reset next error notifies again", t.onError("s1", "sess1"))
    }

    @Test
    fun streakIsPerSession() {
        val t = ErrorStreakTracker()
        t.onError("s1", "sess1")
        assertTrue("other session unaffected", t.onError("s1", "sess2"))
        assertTrue("other server unaffected", t.onError("s2", "sess1"))
    }

    @Test
    fun sessionEnterResetsStreak() {
        val t = ErrorStreakTracker()
        t.onError("s1", "sess1")
        t.reset("s1", "sess1")
        assertTrue(t.onError("s1", "sess1"))
    }
}
