package dev.leonardo.ocbeacon.ui.screens.chat.dsh

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension

/** #399：DSH 钉底任务卡以集合多绑定注册到通用界面插槽（通用壳零类型知识）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DshJobTimelineExtensionModule {

    @Binds
    @IntoSet
    abstract fun bindDshJobTimelineExtension(impl: DshJobTimelineExtension): ServerUiExtension
}
