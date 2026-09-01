package dev.leonardo.ocbeacon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import dev.leonardo.ocbeacon.domain.repository.VcsRepository
import dev.leonardo.ocbeacon.fakes.FakeAgentRepository
import dev.leonardo.ocbeacon.fakes.FakeChatRepository
import dev.leonardo.ocbeacon.fakes.FakeDraftRepository
import dev.leonardo.ocbeacon.fakes.FakeFileRepository
import dev.leonardo.ocbeacon.fakes.FakeMcpRepository
import dev.leonardo.ocbeacon.fakes.FakeServerRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionStateRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionTagRepository
import dev.leonardo.ocbeacon.fakes.FakeSettingsRepository
import dev.leonardo.ocbeacon.fakes.FakeVcsRepository
import javax.inject.Singleton

/**
 * 用 fake repository 绑定替换 DomainModule。
 *
 * DomainModule（di/）绑定全部 repository 接口，包括
 * ChatRepository 和 SessionRepository（原 DataModule 已合并）。
 *
 * ServerRepositoryImpl 实现了 3 个接口；FakeServerRepository 同样如此，
 * 因此我们将同一个 fake 实例绑定为全部 3 种类型。
 */
@TestInstallIn(components = [SingletonComponent::class], replaces = [DomainModule::class])
@Module
@Suppress("unused")
abstract class FakeDomainModule {

    @Binds @Singleton abstract fun bindChatRepository(impl: FakeChatRepository): ChatRepository
    @Binds @Singleton abstract fun bindSessionRepository(impl: FakeSessionRepository): SessionRepository

    @Binds @Singleton abstract fun bindSettingsRepository(impl: FakeSettingsRepository): SettingsRepository

    // C5 存储归属拆分：会话标签接口自 SettingsRepository 独立，测试图同步
    @Binds @Singleton abstract fun bindSessionTagRepository(impl: FakeSessionTagRepository): SessionTagRepository
    @Binds @Singleton abstract fun bindSessionStateRepository(impl: FakeSessionStateRepository): SessionStateRepository
    @Binds @Singleton abstract fun bindAgentRepository(impl: FakeAgentRepository): AgentRepository
    @Binds @Singleton abstract fun bindDraftRepository(impl: FakeDraftRepository): DraftRepository
    @Binds @Singleton abstract fun bindFileRepository(impl: FakeFileRepository): FileRepository
    @Binds @Singleton abstract fun bindVcsRepository(impl: FakeVcsRepository): VcsRepository

    // 2026-08-16：androidTest 测试图补 MessageCacheRepository（此前源集从未编译、缺口被掩盖）
    @Binds @Singleton abstract fun bindMessageCacheRepository(impl: dev.leonardo.ocbeacon.fakes.FakeMessageCacheRepository): dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
    @Binds @Singleton abstract fun bindMcpRepository(impl: FakeMcpRepository): McpRepository

    // ServerRepository 及其 2 个子接口 —— 全部由单个 FakeServerRepository 支撑
    @Binds @Singleton abstract fun bindServerRepository(impl: FakeServerRepository): ServerRepository
    @Binds @Singleton abstract fun bindServerConfigRepository(impl: FakeServerRepository): ServerConfigRepository
    @Binds @Singleton abstract fun bindProviderRepository(impl: FakeServerRepository): ProviderRepository
}
