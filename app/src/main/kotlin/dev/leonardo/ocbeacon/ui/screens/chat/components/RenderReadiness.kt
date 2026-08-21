package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * 2026-08-21 卫生（D-11-4）：渲染层上报链（update/Ready）与 awaitReady 挂起
 * 等待均无消费者（跳转状态机经 phase 驱动，不读 Ready）——已删除。当前
 * 唯一生产者 = preParse（滚动预解析驱动）；消费者 = flow()/current()。
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
 * - [preParse]：预加载时提前后台解析（parseMarkdownFlow 先行——消息组件
 *   组合时直接用已解析 State 渲染，内容即最终状态，无骤变）
 * - [remove]：消息组件销毁/LRU 淘汰时注销（#98 防无界增长）
 *（2026-08-21 卫生：update/awaitReady 无消费者已删——D-11-4）
 */
class RenderReadinessRegistry {
    // 2026-08-20 滚动卡顿根因修复：快照 Map（mutableStateMapOf）的读依赖是
    // 整 Map 级——每个可见消息卡片在组合中读 Map（flow()/current() 的 getOrPut），
    // 而滚动期间 Map 被持续写入（滚出视口 remove()、预解析 put、LRU 淘汰），
    // 任一次写都会让全部可见卡片失效重组（真机 trace：拖动期每帧 ~10 个 scope
    // 的 26ms 重组风暴，慢拖 1s 仅渲染 4 帧）。改为普通并发 Map + 消费端
    // collectAsState 订阅各自的 StateFlow——写 Map 不再触发任何重组，条目
    // 状态变化只重组订阅该条目的单个 scope。
    private val flows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<RenderReadiness>>()

    fun flow(msgId: String): StateFlow<RenderReadiness> =
        flows.getOrPut(msgId) { MutableStateFlow(RenderReadiness.Pending) }

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
     *
     * 2026-08-22：归一化（normalizeForRender）一并移入后台——原在调用方
     * （onViewportChanged → snapshotFlow 收集器，主线程）同步执行全文正则
     * 与切段，20K 字符 × 窗口内多条 = 帧间隙阻塞主线程 20-30ms（真机
     * framestats 实证：巨帧 vsync→input=27.8ms 而帧内各相位近 0）。
     */
    fun preParse(
        msgId: String,
        rawText: String,
        scope: CoroutineScope,
        // 2026-08-20 分片：解析完成回调（主线程——launch 上下文）。调用方
        // （滚动预解析驱动）在此计算巨型 part 的块级分片计划。
        onParsed: ((State.Success) -> Unit)? = null,
    ) {
        // 2026-08-21 卫生（D-7 实例置换/复活泄漏修复）：解析前捕获目标 flow
        // 实例，回写直接写实例而非经 getOrPut 查表——
        // ① 中途条目被 remove()（卡片滚出视口/LRU 淘汰）时不复活：复活的
        //    终态条目无订阅者、不进 preparseSeenKeys → 永不淘汰（泄漏）；
        // ② 仍持有旧实例的订阅者（collectAsState）能收到完成状态——旧实现
        //    remove 后重建新实例写入，旧订阅者永远等不到 Parsed → 回退主线程
        //    同步重解析（巨帧回归）。孤写实例在解析协程结束后无引用即可回收。
        val target = flows.getOrPut(msgId) { MutableStateFlow(RenderReadiness.Pending) }
        scope.launch {
            // 2026-08-20：解析移出主线程——库的 parseMarkdownFlow 无 flowOn，
            // 原在收集者上下文（主线程）执行，长文本（16KB+）解析阻塞 UI
            // 100ms+，滚动预解析驱动批量触发时打断拖拽/fling（ScrollDiag 实证）。
            // 2026-08-22：归一化同链后台化（flow builder 内，Default 线程）。
            kotlinx.coroutines.flow.flow {
                emit(dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(rawText, isUser = false))
            }
                .flowOn(Dispatchers.Default)
                .collect { normalized ->
                    parseMarkdownFlow(normalized).flowOn(Dispatchers.Default).collect { st ->
                        when (st) {
                            is State.Success -> {
                                target.value = RenderReadiness.Parsed(st)
                                onParsed?.invoke(st)
                            }
                            is State.Error -> target.value = RenderReadiness.Failed(st.result)
                            else -> target.value = RenderReadiness.Parsing
                        }
                    }
                }
        }
    }
}

/** 组合中访问注册表（ChatMessageList 提供）。 */
val LocalRenderReadiness = staticCompositionLocalOf<RenderReadinessRegistry> {
    error("LocalRenderReadiness not provided")
}
