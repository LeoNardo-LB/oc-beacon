package dev.leonardo.ocbeacon.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ArchiveBucketDao 插桩测试。
 *
 * 放在 androidTest/ 而非 test/：项目 test/ 为纯 JVM（junit + mockk + coroutines-test，
 * 无 Robolectric / androidx.test.core / room-testing），无法实例化 Android Context 与真实
 * Room 数据库。LogDaoTest 已确立此模式（@RunWith(AndroidJUnit4) + ApplicationProvider +
 * inMemoryDatabaseBuilder）。运行需 connectedAndroidTest（模拟器/真机）。
 */
@RunWith(AndroidJUnit4::class)
class ArchiveBucketDaoTest {

    private lateinit var database: OcBeaconDatabase
    private lateinit var dao: ArchiveBucketDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OcBeaconDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.archiveBucketDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun bucket(id: Long = 0, sessionId: String = "ses_1", bucketEnd: Long = 1000L) = ArchiveBucketEntity(
        id = id, sessionId = sessionId, bucketStart = 0L, bucketEnd = bucketEnd,
        messageCount = 10, uncompressedSize = 100, payload = ByteArray(10) { 1 },
        createdAt = 1L, lastAccessedAt = 1L,
    )

    @Test
    fun upsertAndLatestBefore_returnsDescending() = runBlocking {
        dao.upsert(bucket(bucketEnd = 3000L))
        dao.upsert(bucket(bucketEnd = 1000L))
        dao.upsert(bucket(bucketEnd = 2000L))

        val result = dao.latestBefore("ses_1", beforeEnd = 2500L, limit = 10)

        assertEquals(2, result.size)
        assertEquals(2000L, result[0].bucketEnd)
        assertEquals(1000L, result[1].bucketEnd)
    }

    @Test
    fun latestBefore_excludesEqualOrNewer() = runBlocking {
        dao.upsert(bucket(bucketEnd = 1000L))
        dao.upsert(bucket(bucketEnd = 2000L))

        val result = dao.latestBefore("ses_1", beforeEnd = 1000L, limit = 10)

        assertEquals(0, result.size)
    }

    @Test
    fun countAndLeastAccessed() = runBlocking {
        dao.upsert(bucket(bucketEnd = 3000L, id = 1L).copy(lastAccessedAt = 99L))
        dao.upsert(bucket(bucketEnd = 1000L, id = 2L).copy(lastAccessedAt = 1L))

        assertEquals(2, dao.count("ses_1"))
        val least = dao.leastAccessed("ses_1", 10)
        assertEquals(2, least.size)
        assertEquals(1L, least[0].lastAccessedAt)
    }

    @Test
    fun touchUpdatesLastAccessed() = runBlocking {
        dao.upsert(bucket(id = 1L))
        dao.touch(1L, at = 555L)
        val result = dao.latestBefore("ses_1", beforeEnd = Long.MAX_VALUE, limit = 10)
        assertEquals(555L, result[0].lastAccessedAt)
    }

    @Test
    fun clearSession_removesOnlyThatSession() = runBlocking {
        dao.upsert(bucket(sessionId = "ses_1"))
        dao.upsert(bucket(sessionId = "ses_2"))
        dao.clearSession("ses_1")
        assertEquals(0, dao.count("ses_1"))
        assertEquals(1, dao.count("ses_2"))
    }

    @Test
    fun delete_removesBucket() = runBlocking {
        dao.upsert(bucket(id = 1L))
        dao.delete(1L)
        assertEquals(0, dao.count("ses_1"))
    }
}
