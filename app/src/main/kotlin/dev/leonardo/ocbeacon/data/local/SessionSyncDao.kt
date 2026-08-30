package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionSyncDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SessionSyncEntity)

    @Query("SELECT * FROM session_sync_state WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SessionSyncEntity?

    @Query("SELECT * FROM session_sync_state")
    fun observeAll(): Flow<List<SessionSyncEntity>>

    /** 删会话级联清理（#271：与热表/冷存/FTS 同步清除）。 */
    @Query("DELETE FROM session_sync_state WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM session_sync_state")
    suspend fun clearAll()
}
