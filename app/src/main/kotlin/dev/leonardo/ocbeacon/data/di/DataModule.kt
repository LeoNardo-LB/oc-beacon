package dev.leonardo.ocbeacon.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.repository.ChatRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.SessionRepositoryImpl
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository

/**
 * 将 Chat 和 Session 领域接口绑定到其 Data 层实现的 Hilt 模块。
 * Server 和 Settings 绑定位于 di/DomainModule。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
