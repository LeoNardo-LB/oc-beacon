package dev.leonardo.octether.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.octether.data.repository.ChatRepositoryImpl
import dev.leonardo.octether.data.repository.SessionRepositoryImpl
import dev.leonardo.octether.domain.repository.ChatRepository
import dev.leonardo.octether.domain.repository.SessionRepository

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
