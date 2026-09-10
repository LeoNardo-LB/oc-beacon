package dev.leonardo.ocbeacon.data.adapter

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * 适配器注册模块（#391）——每个适配器在自己包内以 @IntoSet 贡献实例。
 *
 * 新增服务器类型时：新增一个 ServerAdapter 实现 + 在本模块（或该类型的同目录模块）
 * 加一行 @Binds @IntoSet，不改任何既有共享代码。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServerAdapterModule {

    @Binds
    @IntoSet
    abstract fun bindOpenCodeServerAdapter(impl: OpenCodeServerAdapter): ServerAdapter

    @Binds
    @IntoSet
    abstract fun bindDshServerAdapter(impl: DshServerAdapter): ServerAdapter
}
