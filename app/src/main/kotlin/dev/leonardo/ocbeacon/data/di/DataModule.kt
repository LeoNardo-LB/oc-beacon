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
 * Hilt module that binds Chat and Session domain interfaces to their Data-layer implementations.
 * Server and Settings bindings live in di/DomainModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
