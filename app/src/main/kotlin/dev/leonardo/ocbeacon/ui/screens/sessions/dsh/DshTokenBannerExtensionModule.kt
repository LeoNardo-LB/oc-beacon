package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension

/** #391 切片9：会话列表头部私有横幅以集合多绑定注册（通用屏幕零类型知识）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DshTokenBannerExtensionModule {

    @Binds
    @IntoSet
    abstract fun bindDshTokenBannerExtension(impl: DshTokenBannerExtension): ServerUiExtension
}
