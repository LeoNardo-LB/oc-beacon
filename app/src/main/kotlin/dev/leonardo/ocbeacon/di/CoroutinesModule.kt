package dev.leonardo.ocbeacon.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import android.util.Log
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, exception ->
            Log.e("ApplicationScope", "Unhandled coroutine exception", exception)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
}
