package dev.leonardo.ocbeacon.service

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #155 策略镜像管线纯函数测试（spec §6 静音矩阵）。
 * Uri.parse 在 JVM 单测可用（android.net.Uri 是 android.jar stub 会抛）——
 * 改用字符串比较绕开：SoundPlan.soundUri 直接断言引用或 null。
 */
class FeedbackPolicyTest {

    private val defaultSound: Uri? = null // JVM stub：以 null 代表系统默认音（管线透传语义同引用）

    private fun channel(
        importance: Int = NotificationManagerImportance.DEFAULT,
        sound: Uri? = null,
        vibrate: Boolean = false,
        pattern: LongArray? = null,
        bypassDnd: Boolean = false,
    ) = ChannelSnapshot("ch", importance, sound, vibrate, pattern, bypassDnd)

    private object NotificationManagerImportance {
        const val NONE = 0
        const val LOW = 2
        const val DEFAULT = 3
        const val HIGH = 4
    }

    // ---- 渠道层 ----

    @Test
    fun lowImportanceChannelIsSilent() {
        val plan = FeedbackPolicy.plan(channel(importance = 2), FeedbackPolicy.RINGER_NORMAL, 1, defaultSound)
        assertNull(plan.soundUri)
        assertNull(plan.vibrationPattern)
    }

    @Test
    fun defaultImportanceWithNoChannelSoundFallsBackToSystemDefault() {
        // 渠道无自定义铃声 → 透传系统默认（这里 defaultSound=null → 无声；引用透传语义）
        val plan = FeedbackPolicy.plan(channel(importance = 3), FeedbackPolicy.RINGER_NORMAL, 1, null)
        assertNull(plan.soundUri)
    }

    @Test
    fun channelVibrationWithoutPatternUsesDefaultSingleBuzz() {
        val plan = FeedbackPolicy.plan(channel(vibrate = true, pattern = null), FeedbackPolicy.RINGER_NORMAL, 1, defaultSound)
        assertEquals(longArrayOf(0, 50).toList(), plan.vibrationPattern?.toList())
    }

    @Test
    fun channelCustomVibrationPatternMirrored() {
        val custom = longArrayOf(0, 300, 100, 300)
        val plan = FeedbackPolicy.plan(channel(vibrate = true, pattern = custom), FeedbackPolicy.RINGER_NORMAL, 1, defaultSound)
        assertEquals(custom.toList(), plan.vibrationPattern?.toList())
    }

    @Test
    fun vibrationDisabledByUserMeansNoVibration() {
        val plan = FeedbackPolicy.plan(channel(vibrate = false, pattern = longArrayOf(0, 500)), FeedbackPolicy.RINGER_NORMAL, 1, defaultSound)
        assertNull(plan.vibrationPattern)
    }

    // ---- RingerMode 层 ----

    @Test
    fun ringerSilentKeepsVibrationOnly() {
        val plan = FeedbackPolicy.plan(channel(vibrate = true), FeedbackPolicy.RINGER_SILENT, 1, defaultSound)
        assertNull(plan.soundUri)
        assertEquals(longArrayOf(0, 50).toList(), plan.vibrationPattern?.toList())
    }

    @Test
    fun ringerVibrateSuppressesSoundKeepsVibration() {
        val plan = FeedbackPolicy.plan(channel(importance = 3, vibrate = true), FeedbackPolicy.RINGER_VIBRATE, 1, defaultSound)
        assertNull(plan.soundUri)
        assertEquals(longArrayOf(0, 50).toList(), plan.vibrationPattern?.toList())
    }

    // ---- DND 层 ----

    @Test
    fun dndActiveWithoutBypassFullySilent() {
        val plan = FeedbackPolicy.plan(channel(importance = 4, vibrate = true), FeedbackPolicy.RINGER_NORMAL, 3 /* PRIORITY */, defaultSound)
        assertNull(plan.soundUri)
        assertNull(plan.vibrationPattern)
    }

    @Test
    fun dndBypassChannelPlaysNormally() {
        val plan = FeedbackPolicy.plan(channel(importance = 4, vibrate = true, bypassDnd = true), FeedbackPolicy.RINGER_NORMAL, 3, defaultSound)
        assertEquals(longArrayOf(0, 50).toList(), plan.vibrationPattern?.toList())
    }

    @Test
    fun filterAllAllowsPlayback() {
        val plan = FeedbackPolicy.plan(channel(importance = 4, vibrate = true), FeedbackPolicy.RINGER_NORMAL, FeedbackPolicy.FILTER_ALL, defaultSound)
        assertEquals(longArrayOf(0, 50).toList(), plan.vibrationPattern?.toList())
    }

    // ---- 边界 ----

    @Test
    fun missingChannelFullySilent() {
        val plan = FeedbackPolicy.plan(null, FeedbackPolicy.RINGER_NORMAL, 1, defaultSound)
        assertNull(plan.soundUri)
        assertNull(plan.vibrationPattern)
    }
}
