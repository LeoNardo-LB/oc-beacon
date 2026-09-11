package dev.leonardo.ocbeacon.ui.screens.chat

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.ui.extension.ServerActionContribution
import dev.leonardo.ocbeacon.ui.extension.ServerActionSurface
import dev.leonardo.ocbeacon.ui.extension.SimpleServerAction

/**
 * 会话 FAB 工具栏入口贡献（#391 切片9）——把原先硬编码在 ChatScreen 的能力位门控
 * 改为声明式条目动作贡献（表面 + 所需能力 + 顺序）。
 *
 * 统一审计批1 的裁决保留：TODO/AGENT 两面通用；GOAL 需 GOALS；SHELL 需 TERMINAL；
 * QUEUE 需 QUEUE（V1 无可见队列域不泄漏）。
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatFabActionsModule {

    @Provides
    @IntoSet
    fun chatFabTodo(): ServerActionContribution =
        SimpleServerAction(ServerActionSurface.CHAT_FAB, "TODO", order = 10)

    @Provides
    @IntoSet
    fun chatFabAgent(): ServerActionContribution =
        SimpleServerAction(ServerActionSurface.CHAT_FAB, "AGENT", order = 20)

    @Provides
    @IntoSet
    fun chatFabGoal(): ServerActionContribution =
        SimpleServerAction(ServerActionSurface.CHAT_FAB, "GOAL", ServerFeatures.GOALS, order = 30)

    @Provides
    @IntoSet
    fun chatFabShell(): ServerActionContribution =
        SimpleServerAction(ServerActionSurface.CHAT_FAB, "SHELL", ServerFeatures.TERMINAL, order = 40)

    @Provides
    @IntoSet
    fun chatFabQueue(): ServerActionContribution =
        SimpleServerAction(ServerActionSurface.CHAT_FAB, "QUEUE", ServerFeatures.QUEUE, order = 50)
}
