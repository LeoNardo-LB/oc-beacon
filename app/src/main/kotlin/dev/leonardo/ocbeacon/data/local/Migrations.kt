package dev.leonardo.ocbeacon.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    /** v1 → v2：新增归档桶表（热表三表不动）。 */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `archive_buckets` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`bucketStart` INTEGER NOT NULL, " +
                    "`bucketEnd` INTEGER NOT NULL, " +
                    "`messageCount` INTEGER NOT NULL, " +
                    "`uncompressedSize` INTEGER NOT NULL, " +
                    "`payload` BLOB NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`lastAccessedAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_buckets_sessionId_bucketEnd` ON `archive_buckets` (`sessionId`, `bucketEnd`)")
        }
    }
}
