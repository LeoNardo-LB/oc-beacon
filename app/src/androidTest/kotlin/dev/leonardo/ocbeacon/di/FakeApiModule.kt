package dev.leonardo.ocbeacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.file.FileApiImpl
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.message.MessageApiImpl
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApiImpl
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.session.SessionApiImpl
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.system.SystemApiImpl
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApiImpl
import javax.inject.Singleton

/**
 * Replaces ApiModule for the test environment.
 *
 * Binds real ApiImpl classes (they depend on ApiClient which receives the dummy HttpClient
 * from FakeNetworkModule). APIs are never invoked because all repositories are faked.
 * ServerTerminalRegistry depends on TerminalApi — it receives the real TerminalApiImpl
 * but never connects in tests.
 */
@TestInstallIn(components = [SingletonComponent::class], replaces = [ApiModule::class])
@Module
@Suppress("unused")
abstract class FakeApiModule {

    @Binds @Singleton abstract fun bindSessionApi(impl: SessionApiImpl): SessionApi
    @Binds @Singleton abstract fun bindMessageApi(impl: MessageApiImpl): MessageApi
    @Binds @Singleton abstract fun bindTerminalApi(impl: TerminalApiImpl): TerminalApi
    @Binds @Singleton abstract fun bindProviderApi(impl: ProviderApiImpl): ProviderApi
    @Binds @Singleton abstract fun bindFileApi(impl: FileApiImpl): FileApi
    @Binds @Singleton abstract fun bindSystemApi(impl: SystemApiImpl): SystemApi
}
