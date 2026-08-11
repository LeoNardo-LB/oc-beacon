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
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient

/**
 * 6 个领域 API 接口的 Hilt 绑定。
 *
 * 每个 `*ApiImpl` 都是 `@Singleton` + `@Inject constructor`，因此此处的绑定是
 * 无作用域别名——作用域由实现类持有，与 [DomainModule] 保持一致。
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
    abstract fun bindShellApi(impl: dev.leonardo.ocbeacon.data.api.shell.ShellApiImpl): dev.leonardo.ocbeacon.data.api.shell.ShellApi

    @Binds
    abstract fun bindProviderApi(impl: ProviderApiImpl): ProviderApi

    @Binds
    abstract fun bindFileApi(impl: FileApiImpl): FileApi

    @Binds
    abstract fun bindSystemApi(impl: SystemApiImpl): SystemApi
}
