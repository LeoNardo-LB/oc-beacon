package dev.leonardo.ocbeacon.data.local

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LogStoreTest {

    private val dao = mockk<LogDao>(relaxed = true)
    private val store = LogStore(dao)

    // ---- 常量（与旧 DiagnosticLogDatabase 语义等价）----

    @Test
    fun retentionConstants_matchLegacyBehavior() {
        assertEquals(1000, LogStore.VISIBLE_ENTRY_LIMIT)
        assertEquals(10L * 1024L * 1024L, LogStore.MAX_PERSISTENT_BYTES)
        assertEquals(3L * 24L * 60L * 60L * 1000L, LogStore.ORDINARY_RETENTION_MS)
        assertEquals(21L * 24L * 60L * 60L * 1000L, LogStore.ERROR_RETENTION_MS)
        assertEquals(50, LogStore.CRASH_LIMIT)
        assertEquals(100, LogStore.PRUNE_BATCH_SIZE)
    }

    @Test
    fun insert_triggersTimeBasedPrune() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returns 0L

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerifyOrder {
            dao.insertAll(any())
            dao.deleteOrdinaryBefore(now - LogStore.ORDINARY_RETENTION_MS)
            dao.deleteErrorBefore(now - LogStore.ERROR_RETENTION_MS)
            dao.deleteFatalBeyondLimit(LogStore.CRASH_LIMIT)
            dao.sumByteSize()
        }
    }

    @Test
    fun insert_byteBudgetExceeded_prunesInBatches() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returnsMany listOf(11L * 1024 * 1024, 11L * 1024 * 1024, 5L * 1024 * 1024)
        coEvery { dao.deleteOldestBatch(any()) } returns LogStore.PRUNE_BATCH_SIZE

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerify(exactly = 2) { dao.deleteOldestBatch(LogStore.PRUNE_BATCH_SIZE) }
    }

    @Test
    fun insert_byteBudgetWithinLimit_noBatchPrune() = runTest {
        val now = 1_000_000L
        coEvery { dao.sumByteSize() } returns 500L

        store.insert(listOf(LogEntity(timestamp = 0, level = "INFO", category = "c", message = "m", details = "{}", byteSize = 1)), now)

        coVerify(exactly = 0) { dao.deleteOldestBatch(any()) }
    }
}
