package dev.leonardo.ocbeacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.repository.AgentRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.ChatRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.DraftDataStore
import dev.leonardo.ocbeacon.data.repository.FileRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.ServerRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.McpRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.SessionRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.SettingsRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.SessionTagRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.PendingMessageRepositoryImpl
import dev.leonardo.ocbeacon.data.repository.VcsRepositoryImpl
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.VcsRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun bindDraftRepository(impl: DraftDataStore): DraftRepository

    @Binds
    abstract fun bindAgentRepository(impl: AgentRepositoryImpl): AgentRepository

    @Binds
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    abstract fun bindServerConfigRepository(impl: ServerRepositoryImpl): ServerConfigRepository

    @Binds
    abstract fun bindProviderRepository(impl: ServerRepositoryImpl): ProviderRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    // C5 存储归属拆分：会话标签自 SettingsRepository 独立成接口
    @Binds
    abstract fun bindSessionTagRepository(impl: SessionTagRepositoryImpl): SessionTagRepository

    @Binds
    abstract fun bindSessionStateRepository(impl: SessionStateService): SessionStateRepository

    @Binds
    abstract fun bindMcpRepository(impl: McpRepositoryImpl): McpRepository

    @Binds
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository

    @Binds
    abstract fun bindVcsRepository(impl: VcsRepositoryImpl): VcsRepository

    @Binds
    abstract fun bindMessageCacheRepository(impl: MessageStore): MessageCacheRepository

    @Binds
    abstract fun bindPendingMessageRepository(impl: PendingMessageRepositoryImpl): PendingMessageRepository

}
