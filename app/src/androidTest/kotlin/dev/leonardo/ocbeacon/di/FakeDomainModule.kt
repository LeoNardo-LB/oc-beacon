package dev.leonardo.ocbeacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.leonardo.ocbeacon.data.di.DataModule
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.LocalServerRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConnectionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.repository.TerminalRepository
import dev.leonardo.ocbeacon.domain.repository.VcsRepository
import dev.leonardo.ocbeacon.fakes.FakeAgentRepository
import dev.leonardo.ocbeacon.fakes.FakeChatRepository
import dev.leonardo.ocbeacon.fakes.FakeDraftRepository
import dev.leonardo.ocbeacon.fakes.FakeFileRepository
import dev.leonardo.ocbeacon.fakes.FakeMcpRepository
import dev.leonardo.ocbeacon.fakes.FakeServerRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionRepository
import dev.leonardo.ocbeacon.fakes.FakeSettingsRepository
import dev.leonardo.ocbeacon.fakes.FakeTerminalRepository
import dev.leonardo.ocbeacon.fakes.FakeVcsRepository
import javax.inject.Singleton

/**
 * Replaces BOTH DomainModule and DataModule with fake repository bindings.
 *
 * DataModule (data/di/) binds ChatRepository + SessionRepository.
 * DomainModule (di/) binds all other repository interfaces.
 *
 * ServerRepositoryImpl implements 5 interfaces; FakeServerRepository does the same,
 * so we bind the single fake instance as all 5 types.
 */
@TestInstallIn(components = [SingletonComponent::class], replaces = [DomainModule::class, DataModule::class])
@Module
@Suppress("unused")
abstract class FakeDomainModule {

    // DataModule replacements
    @Binds @Singleton abstract fun bindChatRepository(impl: FakeChatRepository): ChatRepository
    @Binds @Singleton abstract fun bindSessionRepository(impl: FakeSessionRepository): SessionRepository

    // DomainModule replacements
    @Binds @Singleton abstract fun bindSettingsRepository(impl: FakeSettingsRepository): SettingsRepository
    @Binds @Singleton abstract fun bindAgentRepository(impl: FakeAgentRepository): AgentRepository
    @Binds @Singleton abstract fun bindDraftRepository(impl: FakeDraftRepository): DraftRepository
    @Binds @Singleton abstract fun bindFileRepository(impl: FakeFileRepository): FileRepository
    @Binds @Singleton abstract fun bindVcsRepository(impl: FakeVcsRepository): VcsRepository
    @Binds @Singleton abstract fun bindTerminalRepository(impl: FakeTerminalRepository): TerminalRepository
    @Binds @Singleton abstract fun bindMcpRepository(impl: FakeMcpRepository): McpRepository

    // ServerRepository and its 4 sub-interfaces — all backed by single FakeServerRepository
    @Binds @Singleton abstract fun bindServerRepository(impl: FakeServerRepository): ServerRepository
    @Binds @Singleton abstract fun bindServerConfigRepository(impl: FakeServerRepository): ServerConfigRepository
    @Binds @Singleton abstract fun bindServerConnectionRepository(impl: FakeServerRepository): ServerConnectionRepository
    @Binds @Singleton abstract fun bindLocalServerRepository(impl: FakeServerRepository): LocalServerRepository
    @Binds @Singleton abstract fun bindProviderRepository(impl: FakeServerRepository): ProviderRepository
}
