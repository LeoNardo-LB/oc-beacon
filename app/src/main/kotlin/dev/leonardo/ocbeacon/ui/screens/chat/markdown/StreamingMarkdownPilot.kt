package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.mikepenz.markdown.model.StreamingMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * #265 流式 Markdown 增量解析试点开关。
 *
 * spec：docs/specs/2026-08-30-streaming-markdown-state-pilot-design.md §5——
 * dev flavor 默认开（先行 A/B），beta/stable 关闭；回退 = 对应 flavor 的
 * buildConfigField 置 false 一行，或 revert 接线 commit。
 *
 * #437 [stableReveal]：两级安全放行闸（spec
 * docs/specs/2026-09-25-437-streaming-md-stable-reveal-design.md）——
 * 前缀差分与 state.append 之间的 SafePrefixGate，只把定案内容交给库，
 * 从源头消除 L4 不稳定尾回溯重释义（跳变主源）。回退 = 置 false 一行。
 */
object StreamingMarkdownPilot {
    val enabled: Boolean = BuildConfig.STREAMING_MD_PILOT
    val stableReveal: Boolean = BuildConfig.STABLE_REVEAL_PILOT
}

/**
 * pilot 揭露状态（#437）：库状态 + 扣留尾部。
 *
 * [heldTail] = 快照中未放行部分（差终止符尾部）——去路恒两条：
 * 毕业（闭合→放行）或完结 EOF 全量 flush（完结切 preParsedState/async
 * 分支渲染整串，一字不丢）。阶段 B 降亮区（锁高+呼吸光标）消费此值。
 */
internal class PilotStreamingState(
    val state: StreamingMarkdownState,
    val heldTail: State<String>,
)

/**
 * 前缀差分 append 包装（spec §1）+ #437 安全放行闸接线。
 *
 * Part.Text.text 仍以整串快照到达（48ms flush 产物），在此与库状态内部的
 * StringBuilder 做前缀差分，仅把 delta 交给 append()——解析下沉在
 * org.jetbrains:markdown 0.7.9 的 StreamingMarkdownFile，只重解析不稳定尾部，
 * 稳定块 ASTNode 实例跨 append 复用。
 *
 * #437：stableReveal 开启时，差分出的全量 delta 先经 SafePrefixGate——
 * 只有定案前缀（空行毕业的闭合构造 + 纯文字安全后缀）进入 append；
 * 扣留尾部经 [PilotStreamingState.heldTail] 暴露给降亮区。放行流单调
 * 不回退（gate 不变量），与 #435 高度引擎「锚即意图」配对天然兼容。
 *
 * - 非前缀（重生成/编辑）→ prev 置空 + released 清零 + resetKey++ 经 key()
 *   整体重建状态实例，新实例首跑整串 append（无残留旧内容）。
 * - append 在组合协程（主线程）：与渲染同线程，StringBuilder 无跨线程竞态
 *   （库官方姿势同此；尾部小解析由 48ms flush 节奏摊平）。
 * - delta 未经 normalizeForRender（冲突①裁决）：流中放弃归一化，完结时由
 *   preParsedState 分支的既有归一化+分片路径接管——完结切换即 EOF 全量
 *   flush（扣留内容一字不丢），切换高度差由阶段 C 处理。
 */
@Composable
internal fun rememberPilotStreamingMarkdownState(markdown: String): PilotStreamingState {
    var resetKey by remember { mutableIntStateOf(0) }
    var prev by remember { mutableStateOf<String?>(null) }
    // gate 放行长度（相对快照坐标）；非前缀重建时清零
    var released by remember { mutableIntStateOf(0) }
    val state = key(resetKey) { rememberStreamingMarkdownState() }
    val held = remember { mutableStateOf("") }
    // #437 §4：非前缀风暴探测（重建限频——冻结放行，旧串回来即恢复）
    val flap = remember { FlapDetector(now = { android.os.SystemClock.elapsedRealtime() }) }
    var lastStormCount by remember { mutableIntStateOf(0) }
    val gate = StreamingMarkdownPilot.stableReveal
    LaunchedEffect(markdown, state) {
        val p = prev
        when {
            // 首跑（含重建后的新实例）：整串作为初始增量（gate 后定案前缀）
            p == null -> {
                if (markdown.isNotEmpty()) {
                    if (gate) {
                        val d = SafePrefixGate.releaseDelta(markdown, 0)
                        released = d.newReleased
                        if (d.delta.isNotEmpty()) appendAndTrace(state, d.delta)
                        logGate(markdown, 0, released)
                    } else {
                        appendAndTrace(state, markdown)
                        released = markdown.length
                    }
                }
                prev = markdown
            }
            // 非前缀（重生成/编辑）：下轮新实例走整串重建；
            // #437 §4 数据层摆动（reconciler vs live 竞态）会高频触发此分支——
            // 风暴抑制：冻结放行与重建（prev 保持旧值，旧串回来无缝恢复）
            !markdown.startsWith(p) -> {
                if (flap.onNonPrefix()) {
                    if (flap.stormCount != lastStormCount) {
                        lastStormCount = flap.stormCount
                        AppLogger.w("MDPilot", "flap suppress #" + flap.stormCount +
                            " — nonPrefix storm, rebuild frozen")
                    }
                    held.value = ""
                } else {
                    prev = null
                    released = 0
                    held.value = ""
                    resetKey++
                }
            }
            markdown.length > p.length -> {
                if (gate) {
                    val d = SafePrefixGate.releaseDelta(markdown, released)
                    if (d.newReleased > released && d.delta.isNotEmpty()) {
                        appendAndTrace(state, d.delta)
                    }
                    logGate(markdown, released, d.newReleased)
                    released = d.newReleased
                } else {
                    appendAndTrace(state, markdown.substring(p.length))
                    released = markdown.length
                }
                prev = markdown
            }
            else -> prev = markdown // 等长：无增量
        }
        if (gate && prev != null) {
            val newHeld = markdown.substring(released.coerceIn(0, markdown.length))
            // #437 崩溃批次后续修（2026-09-25 用户报「闪烁后视窗回位」）：
            // 转正（毕业放行）时降亮区收缩与 Markdown 正文扩张存在两帧错位
            // ——held 同帧缩短→item 净高先减一帧→正文下一帧长回=视口单次往返
            // 闪烁。收缩侧延一帧（等扩张帧落地），增长侧不延（降亮区晚一帧
            // 无感）。净高度变化从此单调。
            if (newHeld.length < held.value.length) {
                withFrameNanos { }
            }
            held.value = newHeld
        }
    }
    return PilotStreamingState(state, held)
}

/** gate 放行观测日志（#437 阶段 D 仪器最小版：放行量/扣留量）。 */
private fun logGate(snapshot: String, from: Int, to: Int) {
    AppLogger.i(
        "MDPilot",
        "gate release=" + (to - from) + " held=" + (snapshot.length - to) +
            " releasedTotal=" + to + " snapshotTotal=" + snapshot.length
    )
}

private suspend fun appendAndTrace(state: StreamingMarkdownState, delta: String) {
    val snap = state.append(delta)
    AppLogger.i(
        "MDPilot",
        "append " + delta.length + "ch -> stable=" + snap.stableAst.size +
            " tail=" + snap.unstableAstTail.size + " total=" + state.content.length
    )
}
