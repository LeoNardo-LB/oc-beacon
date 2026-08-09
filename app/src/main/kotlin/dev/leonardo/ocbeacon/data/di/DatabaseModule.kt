package dev.leonardo.ocbeacon.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.local.ArchiveBucketDao
import dev.leonardo.ocbeacon.data.local.LogDao
import dev.leonardo.ocbeacon.data.local.MessageDao
import dev.leonardo.ocbeacon.data.local.Migrations
import dev.leonardo.ocbeacon.data.local.OcBeaconDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OcBeaconDatabase =
        // WAL 模式：Room 对 targetSdk>=16 默认开启（JournalMode.WRITE_AHEAD_LOGGING）
        Room.databaseBuilder(context, OcBeaconDatabase::class.java, "ocbeacon.db")
            .addMigrations(Migrations.MIGRATION_1_2)
            .build()

    @Provides
    fun provideLogDao(database: OcBeaconDatabase): LogDao = database.logDao()

    @Provides
    fun provideMessageDao(database: OcBeaconDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideArchiveBucketDao(database: OcBeaconDatabase): ArchiveBucketDao = database.archiveBucketDao()

    /** 时钟源（归档桶时间戳用）。生产用系统时钟；测试经 MessageStore 构造参数注入固定值。 */
    @Provides
    @Singleton
    fun provideClock(): () -> Long = System::currentTimeMillis
}
