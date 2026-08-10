package dev.leonardo.ocbeacon.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
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
    fun sqliteCorruptException_triggersDeleteAndReturnsNull() = runTest {
        val result = recovery.withCorruptionRecovery {
            throw SQLiteDatabaseCorruptException("database disk image is malformed")
        }

        assertNull(result)
        verify(exactly = 1) { context.deleteDatabase("ocbeacon.db") }
    }

    @Test
    fun sqliteExceptionWithCorruptCause_triggersDeleteAndReturnsNull() = runTest {
        // Room/框架可能在 cause 链中包装 CorruptException
        val wrapped = SQLiteException("wrapped outer").apply {
            initCause(SQLiteDatabaseCorruptException("inner corrupt"))
        }

        val result = recovery.withCorruptionRecovery { throw wrapped }

        assertNull(result)
        verify(exactly = 1) { context.deleteDatabase("ocbeacon.db") }
    }

    @Test
    fun sqliteExceptionWithDeepCorruptCause_triggersDeleteAndReturnsNull() = runTest {
        // 多层包装：IllegalState → SQLiteException → SQLiteDatabaseCorruptException
        val corrupt = SQLiteDatabaseCorruptException("root corrupt")
        val mid = SQLiteException("mid").apply { initCause(corrupt) }
        val outer = IllegalStateException("outer").apply { initCause(mid) }

        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw outer }
        }.exceptionOrNull()

        // 外层非 SQLiteException 不被 catch 捕获，原样抛出
        assertEquals(IllegalStateException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun baseSQLiteException_doesNotDeleteAndPropagates() = runTest {
        // 基类 SQLiteException（非 CorruptException 子类）不触发删库
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw SQLiteException("generic sqlite error") }
        }.exceptionOrNull()

        assertEquals(SQLiteException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun sqliteFullException_doesNotDeleteAndPropagates() = runTest {
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw SQLiteFullException("disk full") }
        }.exceptionOrNull()

        assertEquals(SQLiteFullException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun sqliteLockedException_doesNotDeleteAndPropagates() = runTest {
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw SQLiteDatabaseLockedException("database is locked") }
        }.exceptionOrNull()

        assertEquals(SQLiteDatabaseLockedException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun sqliteConstraintException_doesNotDeleteAndPropagates() = runTest {
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw SQLiteConstraintException("constraint failed") }
        }.exceptionOrNull()

        assertEquals(SQLiteConstraintException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
    }

    @Test
    fun sqliteDiskIOException_doesNotDeleteAndPropagates() = runTest {
        val thrown = runCatching {
            recovery.withCorruptionRecovery { throw SQLiteDiskIOException("disk i/o error") }
        }.exceptionOrNull()

        assertEquals(SQLiteDiskIOException::class, thrown!!::class)
        verify(exactly = 0) { context.deleteDatabase(any()) }
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
