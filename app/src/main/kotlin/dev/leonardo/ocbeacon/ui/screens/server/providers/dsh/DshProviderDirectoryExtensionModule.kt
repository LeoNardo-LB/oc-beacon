package dev.leonardo.ocbeacon.ui.screens.server.providers.dsh

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension

/** #391 切片5：私有界面扩展在自己包内以集合多绑定注册（通用屏幕零类型知识）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DshProviderDirectoryExtensionModule {

    @Binds
    @IntoSet
    abstract fun bindDshProviderDirectoryExtension(impl: DshProviderDirectoryExtension): ServerUiExtension
}
