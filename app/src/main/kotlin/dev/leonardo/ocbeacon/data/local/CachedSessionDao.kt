package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** #306：会话列表缓存 DAO——兜底展示数据源（见 [CachedSessionEntity]）。 */
@Dao
interface CachedSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CachedSessionEntity>)

    @Query("DELETE FROM cached_sessions WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    /**
     * 全量替换某服务器的缓存（REST 基线语义：服务器权威快照）。
     * 事务内 delete + insert——避免兜底流观察到半替换中间态。
     */
    @Transaction
    suspend fun replaceForServer(serverId: String, entities: List<CachedSessionEntity>) {
        deleteByServer(serverId)
        insertAll(entities)
    }

    /** 兜底流：按服务器观察缓存（updatedAt 降序 = 列表展示序）。 */
    @Query("SELECT * FROM cached_sessions WHERE serverId = :serverId ORDER BY updatedAt DESC")
    fun observeByServer(serverId: String): Flow<List<CachedSessionEntity>>
}
