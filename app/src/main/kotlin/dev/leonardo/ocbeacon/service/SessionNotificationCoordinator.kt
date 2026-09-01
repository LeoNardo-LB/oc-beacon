package dev.leonardo.ocbeacon.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionNotifCoord"

/** D2-L30（#112）：response-ready 收敛检查次数与间隔（无输出会话最坏多等 750ms）。 */
private const val RESPONSE_READY_ATTEMPTS = 3
private const val RESPONSE_READY_INTERVAL_MS = 250L

/** #294：事件陈旧阈值——超过此时龄的 idle 完成不通知（回放的历史事件时龄以小时/天计，实时事件 <1s）。 */
private const val STALE_EVENT_NOTIFY_MS = 5 * 60_000L

/**
 * 通知/提示音动作端口（C9）：[SessionNotificationCoordinator] 的纯策略输出。
 *
 * 协调器只做路由决策（系统通知 vs 会话内提示音 vs 抑制），Android 侧执行
 * （AppNotificationManager 的 Context/NotificationManager、InSessionFeedbackPlayer
 * 的提示音播放）经本端口注入——JVM 测试注入 fake 即可断言动作序列，
 * 不触 Android framework。生产实现见 [ServiceNotificationActionPort]。
 */
interface NotificationActionPort {
    /** 轮次完成系统通知（后台路径；渠道/优先级/去重键由 AppNotificationManager 决定）。 */
    suspend fun showTurnComplete(server: ServerConfig, sessionId: String)

    /** 权限请求系统通知（targetSessionId 已冒泡到父会话）。 */
    suspend fun showPermissionAsked(server: ServerConfig, sessionId: String, permission: String)

    /** 问题系统通知（targetSessionId 已冒泡到父会话）。 */
    suspend fun showQuestionAsked(server: ServerConfig, sessionId: String, questionText: String)

    /** 错误系统通知（sessionId 为 null 时实现侧不投递，与原行为一致）。 */
    suspend fun showSessionError(server: ServerConfig, sessionId: String?, error: String)

    /** #155：被抑制的系统通知转会话内提示音（类型+去重键；策略镜像见
     * InSessionFeedbackPlayer——C9-B 后通知开关/静音由播放器自读）。 */
    suspend fun playInSessionSound(
        serverId: String,
        sessionId: String,
        type: FeedbackType,
        dedupKey: String,
    )

    /** 成功完成轮次 → 重置该会话错误 streak（Q10/R4）。 */
    fun onTurnCompleted(serverId: String, sessionId: String)

    /** 用户主动发出新消息 → 重置该会话错误 streak（Q10）。 */
    fun onUserMessage(serverId: String, sessionId: String)

    /**
     * 通知侧错误 streak 门控（R4）：true=应弹通知并占用 streak（首条错误），
     * false=连发静默。与提示音侧共用同一 streak 状态（InSessionFeedbackPlayer）。
     */
    fun consumeNotificationErrorStreak(serverId: String, sessionId: String): Boolean

    /** 问题文本缺失时的本地化兜底文案（Android 侧 getString；JVM 测试返回固定串）。 */
    fun fallbackQuestionText(): String
}

/**
 * 会话通知协调器（C9，2026-08-26 架构走查）：SSE 事件 → 通知路由策略的单一决策点。
 *
 * 从 [OpenCodeConnectionService.processEvent] 外移的纯策略（约 200 行 when 分发）：
 * - 三态路由：前台活跃会话（系统通知抑制 → 会话内提示音）/ 后台（系统通知）/
 *   其他会话（不抑制，正常通知）
 * - 子智能体会话事件冒泡到父会话通知；子会话轮次完成既不通知也不响（Q3）
 * - 错误 streak 门控（R4：连发只弹第一条）
 * - 自动允许开关（autoAllowPermissions）应答 always 成功时跳过通知
 * - D2-L30（#112）：3×250ms response-ready 收敛等待
 *
 * 纯 Kotlin 可 JVM 测：Android 依赖全部在 [NotificationActionPort] 之后；
 * 协程上下文由调用方（Service.serviceScope）提供——processEvent 为 suspend，
 * 未捕获异常落入宿主 serviceScope 的 CoroutineExceptionHandler（与外移前一致）。
 *
 * 不属本类：question 轮询引擎（REST 兜底，留宿主——依赖 Context/轮询生命周期）、
 * wakelock/持久通知观察者（FGS 域）、规则式权限自动批准
 * （C7 → PermissionAutoApprover.maybeAutoApprove，EventDispatcher 分发点驱动，
 * 与本类的"自动允许开关"是两个机制）。
 */
@Singleton
class SessionNotificationCoordinator @Inject constructor(
    private val actions: NotificationActionPort,
    private val appNotificationManager: AppNotificationManager,
    private val sessionFocusHolder: SessionFocusHolder,
    private val settingsRepository: SettingsRepository,
    private val eventDispatcher: EventDispatcher,
    private val managePermissionUseCase: ManagePermissionUseCase,
) {

    /** 事件派发器的会话表快照（子会话判定/冒泡/auto-allow directory 解析的数据源）。 */
    private val sessions: List<Session> get() = eventDispatcher.sessions.value

    /**
     * SSE 事件 → 通知路由（原 OpenCodeConnectionService.processEvent，C9 迁入）。
     *
     * SSE 双日志治理（backlog #39）：无每事件通用日志——关键业务事件在下方各
     * 分支有专门日志。EventDispatcher.processEvent 已由 SseConnectionManager
     * 调用，此处仅路由到通知逻辑。
     */
    suspend fun processEvent(server: ServerConfig, event: SseEvent) {
        when (event) {
            is SseEvent.SessionIdle -> onSessionIdle(server, event)
            is SseEvent.PermissionAsked -> maybeNotify { onPermissionAsked(server, event) }
            is SseEvent.QuestionAsked -> maybeNotify { onQuestionAsked(server, event) }
            is SseEvent.SessionError -> maybeNotify { onSessionError(server, event) }
            is SseEvent.MessageUpdated -> onMessageUpdated(server, event)
            else -> { }
        }
    }

    // ============ 分支策略 ============

    private suspend fun onSessionIdle(server: ServerConfig, event: SseEvent.SessionIdle) {
        // #294（回放期通知风暴 + heads-up 劫持）：DSH 冷启回放把历史 turn/end
        // 重放给新订阅者——缓存未命中被误判「新完成」→ 7 分钟 57 条通知轰炸 +
        // MIUI heads-up 横幅劫持顶栏点按（2026-09-01 真机两次实锤）。事件携带
        // 原始时刻（DSH 透传；V1/V2 无时刻为 null 保持原行为）——陈旧完成不通知。
        event.time?.let { eventTime ->
            val ageMs = System.currentTimeMillis() - eventTime
            if (ageMs > STALE_EVENT_NOTIFY_MS) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "[${server.displayName}] Skip stale idle notification (${ageMs / 60_000}min old, ${event.sessionId})")
                }
                return
            }
        }
        // #155：正在查看该会话 → 被抑制的系统通知转为会话内提示音
        //（策略镜像系统通知：渠道/铃声档/DND/开关，见 InSessionFeedbackPlayer）
        val inSession = sessionFocusHolder.shouldSuppress(server.id, event.sessionId)
        // 子智能体会话轮次完成既不通知也不响（Q3，与通知口径一致）
        if (isChildSession(event.sessionId)) return
        if (!settingsRepository.notificationsEnabled().first()) return

        // 给 reducer 片刻时间接收后续的 message/part 事件。
        // D2-L30（#112，2026-08-19）：原固定单次 250ms 在慢设备/
        // 长末段输出下 reducer 可能仍未收敛 → 完成通知静默丢失。
        // 改为最多 3 次检查（每次间隔 250ms），首次命中即通知；
        // 无输出会话最坏多等 750ms（后台协程，成本可忽略）。
        var assistantMessageId: String? = null
        for (attempt in 0 until RESPONSE_READY_ATTEMPTS) {
            delay(RESPONSE_READY_INTERVAL_MS)
            // #155 拆分：提示音路径纯查询（不写通知去重 map，Q11）
            assistantMessageId = if (inSession) {
                appNotificationManager.computeNewAssistantMessageId(event.sessionId)
            } else {
                appNotificationManager.checkNewAssistantMessage(server.id, event.sessionId)
            }
            if (assistantMessageId != null) {
                if (attempt > 0) {
                    AppLogger.d(TAG, "[${server.displayName}] Response-ready check recovered after ${attempt + 1} attempts (${event.sessionId})")
                }
                break
            }
        }
        if (assistantMessageId == null) {
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
            }
            return
        }

        if (inSession) {
            // 成功完成的轮次 → 重置该会话错误 streak（Q10）+ 播提示音
            actions.onTurnCompleted(server.id, event.sessionId)
            actions.playInSessionSound(
                serverId = server.id,
                sessionId = event.sessionId,
                type = FeedbackType.TURN_COMPLETE,
                dedupKey = assistantMessageId!!,
            )
            return
        }

        AppLogger.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
        // 成功完成的轮次 → 重置该会话错误 streak（通知侧同语义，R4）
        actions.onTurnCompleted(server.id, event.sessionId)
        actions.showTurnComplete(server, event.sessionId)
    }

    private suspend fun onPermissionAsked(server: ServerConfig, event: SseEvent.PermissionAsked) {
        // 2026-08-16（用户需求·自动允许开关）：开关开启时自动应答
        // always（服务器落持久规则，同类请求不再询问）并跳过通知。
        // 应答失败不中断：落回原通知路径由用户手动处理。
        if (tryAutoAllowAlways(server, event)) return

        val targetSessionId = bubbleToParentSession(event.sessionId)
        AppLogger.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (session=${event.sessionId}, target=$targetSessionId)")
        if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) {
            // #155：被抑制的权限通知 → 会话内提示音（独立去重，Q11）
            actions.playInSessionSound(
                serverId = server.id,
                sessionId = targetSessionId,
                type = FeedbackType.PERMISSION,
                dedupKey = event.permission,
            )
            return
        }
        actions.showPermissionAsked(server, targetSessionId, event.permission)
    }

    private suspend fun onQuestionAsked(server: ServerConfig, event: SseEvent.QuestionAsked) {
        val targetSessionId = bubbleToParentSession(event.sessionId)
        AppLogger.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId} (target=$targetSessionId)")
        if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) {
            // #155：被抑制的问题通知 → 会话内提示音（独立去重，Q11）
            val qText = event.questions.firstOrNull()?.question
                ?: actions.fallbackQuestionText()
            actions.playInSessionSound(
                serverId = server.id,
                sessionId = targetSessionId,
                type = FeedbackType.QUESTION,
                dedupKey = qText,
            )
            return
        }
        val questionText = event.questions.firstOrNull()?.question
            ?: actions.fallbackQuestionText()
        actions.showQuestionAsked(server, targetSessionId, questionText)
    }

    private suspend fun onSessionError(server: ServerConfig, event: SseEvent.SessionError) {
        val targetSessionId = event.sessionId?.let { bubbleToParentSession(it) }
        AppLogger.i(TAG, "[${server.displayName}] Session error: ${event.error} (session=${event.sessionId}, target=$targetSessionId)")
        if (targetSessionId != null && sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) {
            // #155：被抑制的错误通知 → 会话内提示音（streak 门控在内，R3）
            actions.playInSessionSound(
                serverId = server.id,
                sessionId = targetSessionId,
                type = FeedbackType.ERROR,
                dedupKey = event.error,
            )
            return
        }
        // R4：通知侧错误 streak——连续错误只弹第一条；重置 = 成功轮次或用户新消息
        if (targetSessionId != null &&
            !actions.consumeNotificationErrorStreak(server.id, targetSessionId)
        ) {
            AppLogger.i(TAG, "[${server.displayName}] Error notification suppressed by streak (target=$targetSessionId)")
            return
        }
        actions.showSessionError(server, targetSessionId, event.error)
    }

    private fun onMessageUpdated(server: ServerConfig, event: SseEvent.MessageUpdated) {
        // #155（Q10）：用户主动发出新消息 → 重置该会话错误 streak。
        // 合成消息（synthetic，工具代发）不算用户主动。
        val info = event.info
        if (info is Message.User && info.role != "synthetic") {
            actions.onUserMessage(server.id, info.sessionId)
        }
    }

    // ============ 私有辅助 ============

    /**
     * 在通知总开关开启时才执行通知动作（原 Service.maybeNotify，C9 迁入）。
     * 修复：此前 PermissionAsked/QuestionAsked/SessionError 不检查
     * notificationsEnabled，用户关闭通知后权限/问题/错误仍会弹出。
     */
    private suspend fun maybeNotify(action: suspend () -> Unit) {
        if (!settingsRepository.notificationsEnabled().first()) return
        action()
    }

    /**
     * 自动允许开关应答：true=已应答 always（跳过通知），false=未开启或应答失败
     * （落回通知路径）。与规则式自动批准（C7 → PermissionAutoApprover，reply
     * "once"）是两个机制——本方法是设置总开关的 "always" 应答，属通知路由策略。
     */
    private suspend fun tryAutoAllowAlways(server: ServerConfig, event: SseEvent.PermissionAsked): Boolean {
        if (event.id.isBlank()) return false
        if (!runCatching { settingsRepository.autoAllowPermissions().first() }.getOrDefault(false)) return false
        // 会话 directory（V2 reply 路由 header 用）——从事件派发器的会话表查；
        // 空串归 null（服务器默认项目）。
        val sessionDirectory = sessions
            .find { it.id == event.sessionId }?.directory
            ?.takeIf { it.isNotBlank() }
        val replied = runCatching {
            managePermissionUseCase.replyToPermission(
                serverId = server.id,
                sessionId = event.sessionId,
                requestId = event.id,
                reply = "always",
                directory = sessionDirectory,
            )
        }.onFailure { e ->
            if (e is CancellationException) throw e
            AppLogger.e(TAG, "[${server.displayName}] Auto-allow failed for ${event.permission} (id=${event.id}): ${e.message}", e)
        }.isSuccess
        if (replied) {
            AppLogger.i(TAG, "[${server.displayName}] Auto-allowed permission ${event.permission} (id=${event.id}, session=${event.sessionId})")
        }
        return replied
    }

    /** 子智能体会话事件冒泡到父会话通知；非子会话/查无父时原样返回。 */
    private fun bubbleToParentSession(sessionId: String): String =
        parentSessionIdOf(sessionId) ?: sessionId

    /** 会话是否为子智能体会话（已设置 parentID，子会话不应触发面向用户的通知）。 */
    private fun isChildSession(sessionId: String): Boolean =
        parentSessionIdOf(sessionId) != null

    private fun parentSessionIdOf(sessionId: String): String? =
        sessions.firstOrNull { it.id == sessionId }?.parentId
}

/** 端口绑定（C9）：协调器依赖接口，生产实现为 ServiceNotificationActionPort。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationActionPortModule {
    @Binds
    abstract fun bindNotificationActionPort(impl: ServiceNotificationActionPort): NotificationActionPort
}

/**
 * 生产端口实现（Android 侧）：转发 [AppNotificationManager] /
 * [InSessionFeedbackPlayer]。Context 经 Hilt @ApplicationContext 注入
 * （仅兜底文案 getString；C9-B 后通知构建的 Context/NotificationManager
 * 所有权在 AppNotificationManager 构造）。
 */
@Singleton
class ServiceNotificationActionPort @Inject constructor(
    private val appNotificationManager: AppNotificationManager,
    private val feedbackPlayer: InSessionFeedbackPlayer,
    @ApplicationContext private val appContext: Context,
) : NotificationActionPort {

    override suspend fun showTurnComplete(server: ServerConfig, sessionId: String) {
        appNotificationManager.showTaskCompleteNotification(server, sessionId)
    }

    override suspend fun showPermissionAsked(server: ServerConfig, sessionId: String, permission: String) {
        appNotificationManager.showPermissionNotification(server, sessionId, permission)
    }

    override suspend fun showQuestionAsked(server: ServerConfig, sessionId: String, questionText: String) {
        appNotificationManager.showQuestionNotification(server, sessionId, questionText)
    }

    override suspend fun showSessionError(server: ServerConfig, sessionId: String?, error: String) {
        appNotificationManager.showErrorNotification(server, sessionId, error)
    }

    override suspend fun playInSessionSound(
        serverId: String,
        sessionId: String,
        type: FeedbackType,
        dedupKey: String,
    ) {
        feedbackPlayer.playIfFocused(
            serverId = serverId,
            sessionId = sessionId,
            type = type,
            dedupKey = dedupKey,
        )
    }

    override fun onTurnCompleted(serverId: String, sessionId: String) {
        feedbackPlayer.onTurnCompleted(serverId, sessionId)
    }

    override fun onUserMessage(serverId: String, sessionId: String) {
        feedbackPlayer.onUserMessage(serverId, sessionId)
    }

    override fun consumeNotificationErrorStreak(serverId: String, sessionId: String): Boolean {
        return feedbackPlayer.notificationErrorStreak.onError(serverId, sessionId)
    }

    override fun fallbackQuestionText(): String {
        return appContext.getString(
            dev.leonardo.ocbeacon.R.string.notification_has_question,
            appContext.getString(dev.leonardo.ocbeacon.R.string.notification_new_session),
        )
    }
}

/**
 * 将 [QuestionState] 转换为 [SseEvent.QuestionAsked] 以复用通知路径
 * （question 轮询引擎 → SSE 通知路由共用，C9 随协调器迁出 Service）。
 * key 合成 fallback（2026-08-18 E2E-B 真根因修复）：REST GET /question 响应
 * 无 key 字段（同 question.v2.asked SSE payload），q.key=null 直传会导致
 * replyToForm fallback 的 keyedAnswers 全跳过 → answer={} → 服务器
 * "未作答"。与 V2FormMapper.parseQuestionV2 同规则合成 q$index。
 */
internal fun QuestionState.toQuestionAsked(): SseEvent.QuestionAsked =
    SseEvent.QuestionAsked(
        id = id,
        sessionId = sessionId,
        questions = questions.mapIndexed { index, q ->
            SseEvent.QuestionAsked.Question(
                header = q.header,
                question = q.question,
                multiple = q.multiple,
                custom = q.custom,
                options = q.options.map { o ->
                    SseEvent.QuestionAsked.Option(label = o.label, description = o.description, value = o.value)
                },
                key = q.key ?: "q$index"
            )
        },
        tool = tool
    )
