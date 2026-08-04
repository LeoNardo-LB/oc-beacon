package dev.leonardo.ocbeacon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证测试基础设施已正确接线的健全性检查。
 */
@RunWith(AndroidJUnit4::class)
class SampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.leonardo.ocbeacon.dev", appContext.packageName)
    }
}
