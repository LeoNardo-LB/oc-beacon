package dev.leonardo.ocbeacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

/**
 * Hilt bindings for the 6 domain API interfaces.
 *
 * Each `*ApiImpl` is `@Singleton` + `@Inject constructor`, so the bindings here are
 * unscoped aliases — the scope lives on the implementation, matching [DomainModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {

    @Binds
    abstract fun bindSessionApi(impl: SessionApiImpl): SessionApi

    @Binds
    abstract fun bindMessageApi(impl: MessageApiImpl): MessageApi

    @Binds
    abstract fun bindTerminalApi(impl: TerminalApiImpl): TerminalApi

    @Binds
    abstract fun bindProviderApi(impl: ProviderApiImpl): ProviderApi

    @Binds
    abstract fun bindFileApi(impl: FileApiImpl): FileApi

    @Binds
    abstract fun bindSystemApi(impl: SystemApiImpl): SystemApi
}
