package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 */
object StreamingMarkdownPilot {
    val enabled: Boolean = BuildConfig.STREAMING_MD_PILOT
}

/**
 * 前缀差分 append 包装（spec §1）。
 *
 * Part.Text.text 仍以整串快照到达（48ms flush 产物），在此与库状态内部的
 * StringBuilder 做前缀差分，仅把 delta 交给 append()——解析下沉在
 * org.jetbrains:markdown 0.7.9 的 StreamingMarkdownFile，只重解析不稳定尾部，
 * 稳定块 ASTNode 实例跨 append 复用。
 *
 * - 非前缀（重生成/编辑）→ prev 置空 + resetKey++ 经 key() 整体重建状态实例，
 *   新实例首跑整串 append（无残留旧内容）。
 * - append 在组合协程（主线程）：与渲染同线程，StringBuilder 无跨线程竞态
 *  （库官方姿势同此；尾部小解析由 48ms flush 节奏摊平）。不为挪后台引入
 *   跨线程读写。
 * - delta 未经 normalizeForRender（冲突①裁决）：流中放弃归一化，完结时由
 *   preParsedState 分支的既有归一化+分片路径接管，完结跳变由高度补偿吸收
 *  （V6 人工验证项）。
 * - 每次 append 记录稳定块/不稳定尾规模（spec 实施期待验证问题 1 的增长
 *   曲线取证，兼作 P0-b 组件缓存审计的实例复用证据源）。
 */
@Composable
internal fun rememberPilotStreamingMarkdownState(markdown: String): StreamingMarkdownState {
    var resetKey by remember { mutableIntStateOf(0) }
    var prev by remember { mutableStateOf<String?>(null) }
    val state = key(resetKey) { rememberStreamingMarkdownState() }
    LaunchedEffect(markdown, state) {
        val p = prev
        when {
            // 首跑（含重建后的新实例）：整串作为初始增量
            p == null -> {
                if (markdown.isNotEmpty()) appendAndTrace(state, markdown)
                prev = markdown
            }
            // 非前缀（重生成/编辑）：下轮新实例走整串重建
            !markdown.startsWith(p) -> {
                prev = null
                resetKey++
            }
            markdown.length > p.length -> {
                appendAndTrace(state, markdown.substring(p.length))
                prev = markdown
            }
            else -> prev = markdown // 等长：无增量
        }
    }
    return state
}

private suspend fun appendAndTrace(state: StreamingMarkdownState, delta: String) {
    val snap = state.append(delta)
    AppLogger.i(
        "MDPilot",
        "append ${delta.length}ch -> stable=${snap.stableAst.size} " +
            "tail=${snap.unstableAstTail.size} total=${state.content.length}"
    )
}
