package dev.leonardo.ocbeacon.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline
import dev.leonardo.ocbeacon.domain.usecase.PendingMessageDrainController
import javax.inject.Singleton

/**
 * #176/#177 走查修复：手动放行入口的 domain 接口绑定——UI 层（会话列表）
 * 依赖接口而非 data 层具体管线（Clean Architecture UI→Domain←Data）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PendingMessageDrainModule {
    @Binds
    @Singleton
    abstract fun bindDrainController(impl: PendingMessagePipeline): PendingMessageDrainController
}