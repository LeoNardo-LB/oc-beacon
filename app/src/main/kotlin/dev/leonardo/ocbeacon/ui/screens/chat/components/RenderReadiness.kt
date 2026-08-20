package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 渲染就绪信号（架构根治 2026-08-13）。
 *
 * 消息内容（Markdown）异步渲染，完成时间不确定——这是"渲染骤变 / 定位偏移 /
 * 视口跳动"等问题的共同根源。本信号层把"渲染完成"建模为显式状态机：
 *
 *   Pending → Parsing → Parsed → Ready(最终高度) / Failed
 *
 * 状态语义：
 * - [Pending]：未开始
 * - [Parsing]：解析中（后台 parseMarkdownFlow 或组件 rememberMarkdownState）
 * - [Parsed]：解析完成（可用 Markdown(state) 直接渲染——内容即最终状态）
 * - [Ready]：解析完成 + 布局稳定（携带最终高度——消费方直接精确定位）
 * - [Failed]：解析失败
 *
 * 消息组件（渲染层）上报状态；消费方（定位滚动、高亮、未来逻辑）通过
 * [RenderReadinessRegistry.awaitReady] 挂起等待，拿到最终高度后直接执行——
 * 不再轮询、不再补偿。
 */
sealed interface RenderReadiness {
    data object Pending : RenderReadiness
    data object Parsing : RenderReadiness

    /** 解析完成（可用 [state] 直接渲染——内容即最终状态，无 loading） */
    data class Parsed(val state: State) : RenderReadiness

    /** 渲染完成（解析 + 布局稳定）——[finalHeight] 为最终布局高度 */
    data class Ready(val finalHeight: Int) : RenderReadiness

    data class Failed(val error: Throwable) : RenderReadiness

    /** 终止态（awaitReady 等待此状态） */
    val isDone: Boolean
        get() = this is Ready || this is Failed
}

/**
 * 渲染就绪注册表——消息级就绪信号的唯一真相源。
 *
 * - [flow]：订阅某消息的就绪信号（组合中 collectAsState 驱动门控展示）
 * - [update]：渲染层上报状态
 * - [preParse]：跳转/预加载时提前后台解析（parseMarkdownFlow 先行——
 *   消息组件组合时直接用已解析 State 渲染，内容即最终状态，无骤变）
 * - [awaitReady]：挂起等待渲染完成（返回 Ready——含最终高度），供定位消费
 */
class RenderReadinessRegistry {
    private val flows = mutableStateMapOf<String, MutableStateFlow<RenderReadiness>>()

    fun flow(msgId: String): StateFlow<RenderReadiness> =
        flows.getOrPut(msgId) { MutableStateFlow(RenderReadiness.Pending) }

    fun update(msgId: String, readiness: RenderReadiness) {
        flows.getOrPut(msgId) { MutableStateFlow(RenderReadiness.Pending) }.value = readiness
    }

    /**
     * #98（M-7）：消息组件销毁（滚出视口）时注销条目。终态（Ready/Failed）
     * 的 StateFlow 含解析产物；Pending/Parsing 占空条目——滚出视口后跳转
     * 定位不再需要旧条目（重新组合会重建），保留即无界增长。
     */
    fun remove(msgId: String) {
        flows.remove(msgId)
    }

    fun current(msgId: String): RenderReadiness = flow(msgId).value

    /**
     * 预解析（Mikepenz 官方 Parse-ahead 模式）：点击跳转瞬间后台解析目标文本。
     * 解析完成后状态为 Parsed——消息组件组合时用 Markdown(state) 直接渲染。
     */
    fun preParse(msgId: String, text: String, scope: CoroutineScope) {
        scope.launch {
            // 2026-08-20：解析移出主线程——库的 parseMarkdownFlow 无 flowOn，
            // 原在收集者上下文（主线程）执行，长文本（16KB+）解析阻塞 UI
            // 100ms+，滚动预解析驱动批量触发时打断拖拽/fling（ScrollDiag 实证）。
            // flowOn(Default) 后仅 update 回主线程（快照写）。
            parseMarkdownFlow(text).flowOn(Dispatchers.Default).collect { st ->
                when (st) {
                    is State.Success -> update(msgId, RenderReadiness.Parsed(st))
                    is State.Error -> update(msgId, RenderReadiness.Failed(st.result))
                    else -> update(msgId, RenderReadiness.Parsing)
                }
            }
        }
    }

    /** 等待渲染完成（Ready/Failed），超时返回 null。 */
    suspend fun awaitReady(msgId: String, timeoutMs: Long = 2500): RenderReadiness.Ready? =
        withTimeoutOrNull(timeoutMs) {
            flow(msgId).first { it.isDone }
        }?.let { it as? RenderReadiness.Ready }
}

/** 组合中访问注册表（ChatMessageList 提供）。 */
val LocalRenderReadiness = staticCompositionLocalOf<RenderReadinessRegistry> {
    error("LocalRenderReadiness not provided")
}
