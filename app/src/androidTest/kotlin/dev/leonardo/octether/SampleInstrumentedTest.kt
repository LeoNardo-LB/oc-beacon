package dev.leonardo.octether

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sanity check that the test infrastructure is wired up correctly.
 */
@RunWith(AndroidJUnit4::class)
class SampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.leonardo.octether.dev", appContext.packageName)
    }
}
