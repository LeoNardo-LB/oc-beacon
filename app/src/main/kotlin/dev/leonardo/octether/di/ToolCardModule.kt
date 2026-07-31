package dev.leonardo.octether.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.octether.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.octether.ui.screens.chat.tools.ToolCardResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolCardModule {

    @Binds
    @Singleton
    abstract fun bindToolCardResolver(impl: DefaultToolCardResolver): ToolCardResolver
}
