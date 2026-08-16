package dev.leonardo.ocbeacon.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogDaoTest {

    private lateinit var database: OcBeaconDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OcBeaconDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.logDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(
        id: Long = 0,
        timestamp: Long,
        level: String = "INFO",
        byteSize: Int = 10,
    ) = LogEntity(
        id = id,
        timestamp = timestamp,
        level = level,
        category = "test",
        message = "msg",
        details = "{}",
        byteSize = byteSize,
    )

    @Test
    fun insertAndLatest_returnsNewestFirst() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "INFO"),
                entry(timestamp = 300, level = "ERROR"),
                entry(timestamp = 200, level = "WARN"),
            ),
        )

        val latest = dao.latest(10)

        assertEquals(3, latest.size)
        assertEquals(300, latest[0].timestamp)  // 最新在前
        assertEquals(100, latest[2].timestamp)
    }

    @Test
    fun latest_respectsLimit() = runBlocking {
        dao.insertAll((1..5).map { entry(timestamp = it.toLong()) })

        val latest = dao.latest(2)

        assertEquals(2, latest.size)
        assertEquals(5, latest[0].timestamp)
    }

    @Test
    fun deleteOrdinaryBefore_keepsErrorAndFatal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "INFO"),
                entry(timestamp = 100, level = "ERROR"),
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "WARN"),
            ),
        )

        val deleted = dao.deleteOrdinaryBefore(150)

        assertEquals(1, deleted)  // 只删 INFO
        assertEquals(3, dao.latest(10).size)
    }

    @Test
    fun deleteErrorBefore_removesOldErrors() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "ERROR"),
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "ERROR"),
            ),
        )

        val deleted = dao.deleteErrorBefore(150)

        // 2026-08-16 断言更新：deleteErrorBefore 现语义为删除 ERROR**与 FATAL**
        //（DAO 注释明确两者）——timestamp<150 的两条（100 ERROR + 100 FATAL）
        // 都删，仅剩 200 ERROR。旧断言（deleted=1/latest=2）对应只删 ERROR 的
        // 历史行为，androidTest 首次真正运行暴露过时。
        assertEquals(2, deleted)
        assertEquals(1, dao.latest(10).size)
    }

    @Test
    fun deleteFatalBeyondLimit_keepsNewestFatal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 100, level = "FATAL"),
                entry(timestamp = 200, level = "FATAL"),
                entry(timestamp = 300, level = "FATAL"),
            ),
        )

        val deleted = dao.deleteFatalBeyondLimit(2)

        assertEquals(1, deleted)
        val remaining = dao.latest(10).map { it.timestamp }
        assertEquals(listOf(300L, 200L), remaining)  // 最新的 2 条保留
    }

    @Test
    fun deleteOldestBatch_removesOldest() = runBlocking {
        dao.insertAll((1..5).map { entry(timestamp = it.toLong(), byteSize = 1) })

        val deleted = dao.deleteOldestBatch(2)

        assertEquals(2, deleted)
        assertEquals(3L, dao.sumByteSize())  // 剩 3 条 × 1 byteSize
    }

    @Test
    fun sumByteSize_returnsTotal() = runBlocking {
        dao.insertAll(
            listOf(
                entry(timestamp = 1, byteSize = 10),
                entry(timestamp = 2, byteSize = 20),
            ),
        )

        assertEquals(30L, dao.sumByteSize())
    }

    @Test
    fun clear_emptiesTable() = runBlocking {
        dao.insertAll(listOf(entry(timestamp = 1)))

        dao.clear()

        assertTrue(dao.isEmpty())
        assertFalse(dao.latest(10).isNotEmpty())
    }
}
