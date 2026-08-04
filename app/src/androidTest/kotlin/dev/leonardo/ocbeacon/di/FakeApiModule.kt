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
 * 测试环境下替换 ApiModule。
 *
 * 绑定真实的 ApiImpl 类（它们依赖 ApiClient，而 ApiClient 接收来自
 * FakeNetworkModule 的占位 HttpClient）。由于所有 repository 都被 fake 了，
 * 这些 API 永远不会被调用。ServerTerminalRegistry 依赖 TerminalApi ——
 * 它会拿到真实的 TerminalApiImpl，但在测试中永远不会连接。
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
