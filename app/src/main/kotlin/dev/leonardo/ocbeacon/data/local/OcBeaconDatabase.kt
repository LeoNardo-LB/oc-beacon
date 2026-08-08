package dev.leonardo.ocbeacon.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用本地数据库：消息缓存 + 诊断日志。
 * 版本 1 建三表；后续升级用 Migration 对象（禁止 DROP 重建）。
 */
@Database(
    entities = [CachedMessageEntity::class, CachedPartEntity::class, LogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OcBeaconDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}
