package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * #442 B案 节奏收编（spec docs/specs/2026-10-02-442-b-cadence-incorporation-design.md §2.1）：
 * 流式 Text/Reasoning 的引擎域快通道——数据层每 flush 把**累积全文**（与
 * MessageEventHandler 热视图同字符串实例，零拷贝）发布于此；UI 消费端
 * （PartContent 两分支）以 `live ?: part.text` 覆盖参数，重组作用域收敛到
 * item 内部——十源 combine/ChatScreen 投影/ChatMessageList 函数体在流式稳态
 * 零重跑（根因二收口：100ms 批快照重组链）。
 *
 * 定位：**非真相源快路径**——真相源恒为 `_parts`（热视图）+ Room（持久化）；
 * bus 缺席（旗标关/回收冷启/REST resync 清除/完结清除）时消费端回退参数原路径，
 * 逐字节等价今日行为。写作线程=batchScope（Default）；MutableStateFlow CAS
 * 保证线程安全；读取=组合层 collectAsState（主线程）。
 *
 * 先例：ScrollQuiescence/StreamingShardBroker（引擎域单例）+ 终审 S4
 * （数据层消费引擎域常量，5d061df2）。回退：`STREAM_DELTA_BUS` dev-only
 * buildConfigField（beta/stable 无此键=false）。
 */
object StreamingDeltaBus {

    val enabled: Boolean = BuildConfig.STREAM_DELTA_BUS

    /** partId → 活跃流式累积全文（raw 文本；pilot 侧自行归一化）。 */
    data class Live(
        val partId: String,
        val text: String,
        val reasoning: Boolean,
    )

    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    /** [B2-bus] 埋点限频计数（每 100 次发布打一行——10Hz 通道防刷屏，与 [flush] 同粒度）。 */
    private val publishCount = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 数据层每 flush 发布：触及消息的全部 Text/Reasoning part（键=合并后
     * part.id——内容匹配合并族（#87b）下 delta 派生 id 可能不同于落位 part id，
     * 以落位 id 为准 UI 查询才命中）。空文本 part 撤销覆盖（防御）。
     */
    fun publishParts(parts: List<Part>?) {
        if (!enabled || parts.isNullOrEmpty()) return
        _live.update { current ->
            val next = current.toMutableMap()
            var changed = false
            for (p in parts) {
                when (p) {
                    is Part.Text -> {
                        if (p.text.isNotEmpty()) next[p.id] = Live(p.id, p.text, false)
                        else next.remove(p.id)
                        changed = true
                    }
                    is Part.Reasoning -> {
                        if (p.text.isNotEmpty()) next[p.id] = Live(p.id, p.text, true)
                        else next.remove(p.id)
                        changed = true
                    }
                    else -> Unit
                }
            }
            // 无变化回原实例——StateFlow 值相等去重，零发射
            if (changed) next else current
        }
        // [B2-bus] 动机埋点：流式累积直达 item（绕过 combine=根因二收口通道）
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG && publishCount.incrementAndGet() % 100L == 1L) {
            dev.leonardo.ocbeacon.logging.AppLogger.d(
                "B2-bus",
                "publish batch#$publishCount live=${_live.value.size} keys=" +
                    _live.value.keys.joinToString(",") { it } +
                    " — 流式累积全文直达 item（B3/B4 live ?: part.text 消费）",
            )
        }
    }

    /** 终态（time.end）/移除清除——structural 权威已在 `_parts` 发布，live 让位。 */
    fun clearPart(partId: String) {
        if (!enabled) return
        _live.update { it - partId }
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            dev.leonardo.ocbeacon.logging.AppLogger.d(
                "B2-bus",
                "clear part=${partId.takeLast(14)} — 终态/移除撤销覆盖（消费端回退 part.text=完结换装路径）",
            )
        }
    }

    fun clearParts(partIds: Collection<String>) {
        if (!enabled || partIds.isEmpty()) return
        _live.update { cur -> if (cur.isEmpty()) cur else cur.filterKeys { it !in partIds } }
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            dev.leonardo.ocbeacon.logging.AppLogger.d(
                "B2-bus",
                "clearParts n=${partIds.size} — REST 权威合并/会话清理族撤销覆盖（防陈旧累积遮蔽服务端真相）",
            )
        }
    }

    /** 全量替换/会话清理（REST 权威 resync、clearAll 族）。 */
    fun clearAll() {
        if (!enabled) return
        _live.value = emptyMap()
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            dev.leonardo.ocbeacon.logging.AppLogger.d(
                "B2-bus",
                "clearAll — 全量状态重置（clearAll 族；重进冷启走参数原路径）",
            )
        }
    }

    /** UI 读口：该 part 的活跃流式全文；null=无覆盖（回退参数）。distinct 防同值重启。 */
    fun liveFor(partId: String): Flow<String?> =
        _live.map { it[partId]?.text }.distinctUntilChanged()
            .onEach { text ->
                // [#513 取证] 采样打点（每 200 次发射 1 行）——确认订阅侧活着
                // 且长度在涨；全量打会 ~10 行/s/part 刷穿 DEBUG logcat。
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG && emitProbeCounter.incrementAndGet() % 200L == 1L) {
                    dev.leonardo.ocbeacon.logging.AppLogger.d(
                        "B3", "liveFor emit part=" + partId + " len=" + (text?.length ?: -1),
                    )
                }
            }

    /** [#513 取证] liveFor 发射采样计数（全局，防刷屏）。 */
    private val emitProbeCounter = java.util.concurrent.atomic.AtomicLong(0L)
}
