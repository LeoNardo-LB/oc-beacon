package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension

/** #391 切片9：DSH 服务器设置私有区块以集合多绑定注册（通用屏幕零类型知识）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DshServerAdminExtensionModule {

    @Binds
    @IntoSet
    abstract fun bindDshServerAdminExtension(impl: DshServerAdminExtension): ServerUiExtension
}
