package dev.leonardo.octether.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Singleton

private val Context.testDataStore: DataStore<Preferences> by preferencesDataStore(name = "test_prefs")

/**
 * Replaces NetworkModule for tests.
 * Provides a minimal HttpClient (OkHttp engine, no auth/logging/timeout plugins)
 * and a test-scoped DataStore.
 */
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
@Module
object FakeNetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.testDataStore
}
