package dev.leonardo.ocbeacon.domain.model

/**
 * #378/#375 压缩转录实体——流内压缩 box 的卡态。
 *
 * 生命周期（wire 实录 journal 378-380-wire §五）：compaction/start 建卡 →
 * compaction/summary 贴全文 → 紧邻 user/message 绑表面载体（messageId，UI 抑制
 * 原气泡）→ compaction/end 终态（error 非空 = 失败）。进行中 = 无终态；摘要已到
 * 即可展开阅读（双态均可展开收起——用户裁决 #375）。
 *
 * 折叠纯函数见 [CompactionFolder]（handler 只做容器写）；与 [CommandFeedback]
 * 同构的 durable 语义——历史重放（MessagePage.transcriptEvents dispatch）与实况
 * SSE 同路径按 compactionId 幂等重建（#376 修复通道）。
 */
data class CompactionEntry(
    /** 配对键（wire compactionId）。 */
    val compactionId: String,
    /** 发起命令（手动压缩时的 commandId；缺席为 null）。 */
    val sourceCommandId: String? = null,
    /** start 信封 seq（流内时序位）。 */
    val seq: Long = 0L,
    /** start 信封 time（卡时间戳）。 */
    val startedAt: Long = 0L,
    /** 摘要全文（summary 事件到达前为 null；进行中 box 先 indeterminate）。 */
    val summaryText: String? = null,
    /** 表面载体消息 id（surface 绑定到达前为 null；UI 据此抑制原 user 气泡）。 */
    val messageId: String? = null,
    /** 终态时刻（null = 进行中）。 */
    val finishedAt: Long? = null,
    /** 失败原因（终态且非 null = 失败压缩）。 */
    val error: String? = null,
) {
    val isFinished: Boolean get() = finishedAt != null
    val isFailed: Boolean get() = error != null
}

/**
 * #378 配对纯函数：compactionId 配对原位更新（与 [CommandFeedbackFolder] 同款纪律）。
 *
 * - start：同 compactionId 原位替换（重放幂等），否则按 seq 升序插入；
 * - summary/finished/surfaceBound：同 compactionId 原位升级；无 start 前驱的孤儿
 *   事件自建卡（历史截断防御——不丢摘要/终态事实，同 orphan done 语义）。
 */
object CompactionFolder {

    fun onStarted(states: List<CompactionEntry>, event: SseEvent.CompactionStarted): List<CompactionEntry> {
        val entry = CompactionEntry(
            compactionId = event.compactionId,
            sourceCommandId = event.sourceCommandId,
            seq = event.seq,
            startedAt = event.time,
        )
        val index = states.indexOfFirst { it.compactionId == event.compactionId }
        if (index >= 0) {
            // 重放幂等：保序原位替换，但已到达的后续事实（摘要/绑定/终态）不回退。
            val existing = states[index]
            return states.toMutableList().apply {
                set(index, entry.copy(
                    summaryText = existing.summaryText,
                    messageId = existing.messageId,
                    finishedAt = existing.finishedAt,
                    error = existing.error,
                ))
            }
        }
        return insertBySeq(states, entry)
    }

    fun onSummary(states: List<CompactionEntry>, event: SseEvent.CompactionSummary): List<CompactionEntry> =
        upgrade(states, event.compactionId, event.seq) {
            it.copy(summaryText = event.summaryText, sourceCommandId = it.sourceCommandId ?: event.sourceCommandId)
        }

    fun onFinished(states: List<CompactionEntry>, event: SseEvent.CompactionFinished): List<CompactionEntry> =
        upgrade(states, event.compactionId, event.seq) {
            it.copy(finishedAt = event.time, error = event.error)
        }

    fun onSurfaceBound(states: List<CompactionEntry>, event: SseEvent.CompactionSurfaceBound): List<CompactionEntry> =
        upgrade(states, event.compactionId, event.seq) {
            it.copy(messageId = event.messageId)
        }

    private fun upgrade(
        states: List<CompactionEntry>,
        compactionId: String,
        seq: Long,
        transform: (CompactionEntry) -> CompactionEntry,
    ): List<CompactionEntry> {
        val index = states.indexOfFirst { it.compactionId == compactionId }
        if (index >= 0) {
            val next = transform(states[index])
            // seq 变化（孤儿自建后 start 迟到）时保位不动——插入序以首个事实为准，
            // 重放重复到达不产生位移抖动。
            return states.toMutableList().apply { set(index, next) }
        }
        return insertBySeq(states, CompactionEntry(compactionId = compactionId, seq = seq))
            .let { upgraded ->
                val i = upgraded.indexOfFirst { it.compactionId == compactionId }
                upgraded.toMutableList().apply { set(i, transform(upgraded[i])) }
            }
    }

    private fun insertBySeq(states: List<CompactionEntry>, entry: CompactionEntry): List<CompactionEntry> {
        val index = states.indexOfFirst { it.seq > entry.seq }
        return if (index < 0) states + entry else states.toMutableList().apply { add(index, entry) }
    }
}
