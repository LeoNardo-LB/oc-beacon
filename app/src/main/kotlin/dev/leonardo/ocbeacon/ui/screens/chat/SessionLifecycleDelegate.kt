package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.ui.navigation.routes.ChatNav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder

private const val TAG = "SessionLifecycleDelegate"

/**
 * 管理会话身份、目录、延迟创建和会话加载信号
 *（此前内联在 [ChatViewModel] 中）—— delegate 层的**核心骨架**。
 *
 * 在 Phase 3 Task 3（C 集群）中提取。
 *
 * [sessionIdFlow] 是 [ChatViewModel] 中 6 个 `combine`/`flatMapLatest` 管道的
 * 数据源（messageListState、modelConfigState、sessionMetaState、
 * interactionState、directoryState、contextDetailState）。其他 delegate
 *（Terminal、DraftInput）通过其构造器 provider 消费 [sessionDirectory] 和 [sessionLoaded]。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的
 * 运行时上下文（SavedStateHandle 路由参数、ViewModel 的协程
 * 作用域和消息加载/观察的跨集群回调），Hilt 无法提供这些。
 * ChatViewModel 直接构造它并将每个成员作为门面重新暴露，
 * 因此 UI 文件无需改动。
 *
 * 跨集群副作用（加载消息、启动 SSE 观察）作为回调注入，
 * 使 [ensureSession] 的 mutex 仍包裹完整的
 * 临界区，保留原始的单次执行语义。
 */
internal class SessionLifecycleDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val sessionRepository: SessionRepository,
    private val serverId: String,
    savedStateHandle: SavedStateHandle,
    private val scope: CoroutineScope,
    private val onMessagesNeedLoading: suspend () -> Unit,
    private val onStartObservingMessages: () -> Unit,
) {
    private val directoryParam: String = URLDecoder.decode(
        savedStateHandle.get<String>(ChatNav.PARAM_DIRECTORY) ?: "", "UTF-8"
    )
    private val _sessionId = MutableStateFlow(
        URLDecoder.decode(savedStateHandle.get<String>("sessionId") ?: "", "UTF-8")
    )

    /** 稳定身份 flow —— 6 个 combine/flatMapLatest 管道的数据源。 */
    val sessionIdFlow: StateFlow<String> = _sessionId

    /** 同步身份读取。 */
    val sessionId: String get() = _sessionId.value

    /**
     * 本会话项目的目录 —— 作为 x-opencode-directory 发送，
     * 使服务器解析正确的项目上下文。许多 REST 调用将其
     * 作为 `directory` 参数。
     */
    var sessionDirectory: String? = null
        private set

    /** Mutex 防止并发会话创建。 */
    private val sessionCreateMutex = Mutex()

    /**
     * 标记 [loadSession] 何时完成（成功或出错），使
     * 终端创建可以等待 [sessionDirectory] 被填充。
     */
    val sessionLoaded: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * 为全新会话设置状态（尚无服务器会话）：应用
     * 路由目录参数并标记会话为已加载，使等待者继续执行。
     */
    fun initForNewSession() {
        if (directoryParam.isNotEmpty()) {
            sessionDirectory = directoryParam
        }
        if (!sessionLoaded.isCompleted) {
            sessionLoaded.complete(Unit)
        }
    }

    /**
     * 通过 V1 API 加载会话信息，然后通过注入的回调触发
     * 跨集群消息加载和 SSE 观察。
     *
     * 仅对已有会话（非空 [sessionId]）安全调用。
     */
    suspend fun loadSession() {
        try {
            // 1. 加载目录 / 会话元数据的会话信息
            val session = manageSessionUseCase.getSession(serverId, sessionId)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
            sessionRepository.setSessions(serverId, listOf(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
        }

        // 2. 跨集群：通过 V1 API 加载消息（currentMessageLimit + listMessages）
        runCatching { onMessagesNeedLoading() }
            .onFailure { Log.e(TAG, "Failed to load messages", it) }

        // 3. 跨集群：开始观察 chatRepository flow（由 SSE EventDispatcher 驱动）
        runCatching { onStartObservingMessages() }
            .onFailure { Log.e(TAG, "Failed to start observing messages", it) }
    }

    /**
     * 在发送消息前确保会话存在。
     * 如果 sessionId 为空（新会话），通过 API 创建一个。
     * 通过 Mutex 保证线程安全，防止重复创建。
     * 创建后开始观察 SSE 驱动的 flow，使消息显示。
     */
    suspend fun ensureSession(): String {
        if (sessionId.isNotEmpty()) return sessionId
        return sessionCreateMutex.withLock {
            // 获取锁后双重检查
            if (sessionId.isNotEmpty()) return sessionId
            val dir = if (directoryParam.isNotEmpty()) directoryParam else sessionDirectory
            val session = manageSessionUseCase.createSession(serverId, directory = dir)
            sessionRepository.setSessions(serverId, listOf(session))
            _sessionId.value = session.id
            sessionDirectory = session.directory.ifBlank { dir }
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
            // 开始观察新会话的 SSE 驱动消息/part flow。
            // 没有这个，SSE 事件到达 EventDispatcher 但 ChatViewModel
            // 从不收集它们 —— 消息保持不可见。
            runCatching { onStartObservingMessages() }
                .onFailure { Log.e(TAG, "Failed to start observing after session creation", it) }
            sessionId
        }
    }
}
