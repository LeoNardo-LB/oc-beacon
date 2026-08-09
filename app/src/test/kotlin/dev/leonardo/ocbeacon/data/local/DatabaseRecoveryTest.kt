package dev.leonardo.ocbeacon.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatabaseRecoveryTest {

    private val context = mockk<Context>(relaxed = true)
    private val recovery = DatabaseRecovery(context)

    @Test
    fun blockSuccess_returnsValue() = runTest {
        val result = recovery.withCorruptionRecovery { 42 }

        assertEquals(42, result)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun sqliteException_triggersDeleteAndReturnsNull() = runTest {
        val result = recovery.withCorruptionRecovery { throw SQLiteException("database disk image is malformed") }

        assertNull(result)
        verify(exactly = 1) { context.deleteDatabase("ocbeacon.db") }
    }

    @Test
    fun nonSqliteException_propagates() = runTest {
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw IllegalStateException("boom") }
        }.exceptionOrNull()

        assertEquals(IllegalStateException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }
}
