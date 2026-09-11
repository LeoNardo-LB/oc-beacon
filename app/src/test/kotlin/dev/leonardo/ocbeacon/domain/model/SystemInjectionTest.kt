package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #387：V2 上下文注入（无 source.kind 的 <system-reminder> 闭合块）识别边界。 */
class SystemInjectionTest {

    @Test
    fun `纯闭合块识别为注入`() {
        assertTrue(SystemInjection.isPureReminder("<system-reminder>ctx</system-reminder>"))
        assertTrue(
            SystemInjection.isPureReminder(
                "  \n<system-reminder>\nmulti\nline\n</system-reminder>\n ",
            ),
        )
    }

    @Test
    fun `闭合块加真问句不折叠`() {
        assertFalse(SystemInjection.isPureReminder("<system-reminder>ctx</system-reminder>真问句"))
        assertFalse(SystemInjection.isPureReminder("真问句<system-reminder>ctx</system-reminder>"))
    }

    @Test
    fun `未闭合前缀与普通消息不折叠`() {
        assertFalse(SystemInjection.isPureReminder("<system-reminder>截断未闭合"))
        assertFalse(SystemInjection.isPureReminder("普通用户消息"))
        assertFalse(SystemInjection.isPureReminder(""))
    }
}
