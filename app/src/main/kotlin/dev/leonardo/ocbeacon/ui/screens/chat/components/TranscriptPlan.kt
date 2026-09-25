package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.CommandFeedback
import dev.leonardo.ocbeacon.domain.model.CompactionEntry

/**
 * #378 转录卡流内归并计划（纯函数）——命令反馈卡/压缩 box 按 wire seq 与消息
 * 统一时序定位（信封 seq 是流内权威键；消息侧经 [dev.leonardo.ocbeacon.domain.model.DshMessageId.seqOf]
 * 反解）。
 *
 * **承载方式（卡内嵌）**：卡不作为独立 LazyColumn item 发射，而是内嵌渲染在锚
 * 消息条目的 Column 内——消息条目的 lazy index / key / 双向索引
 * （entryDisplayIndex·displayEntryStart·bannerCount 数学）全部保持不变，
 * 跳转/锚点/可见项反查零波及（ChatMessageList 的承重索引体系的保守性决策）。
 *
 * 锚定语义（reverseLayout：代码序 = 视觉自底向上；displayItems 为 newest→oldest）：
 * - 卡 seq 介于两消息之间 → 锚 = 发射序中第一条更旧消息；卡渲染在该消息组
 *   **视觉顶 entry** 内容之上（before）；
 * - 卡比最新消息还新（含 #365 本地受理占位 seq=0→MAX）→ 渲染在 display 0 组
 *   **视觉底 entry** 内容之下（after）＝视觉尾部（现行「新命令贴尾」语义保持）；
 * - 卡比最旧消息还老（初始窗口未含更旧消息的过渡态）→ trailing 独立 item
 *  （视觉顶部；older page 载入后自然重锚入组）。
 */
internal sealed interface TranscriptCardItem {
    /** LazyColumn item key（稳定 → 同卡原位刷新）。 */
    val planKey: String
    /** 归并排序键（seq；[Long.MAX_VALUE] = 本地占位「现在」语义）。 */
    val sortSeq: Long

    data class Command(val feedback: CommandFeedback) : TranscriptCardItem {
        override val planKey: String get() = "cmd_feedback_" + feedback.commandId
        override val sortSeq: Long get() = if (feedback.seq > 0L) feedback.seq else Long.MAX_VALUE
    }

    data class Compaction(val entry: CompactionEntry) : TranscriptCardItem {
        override val planKey: String get() = "compaction_" + entry.compactionId
        // 2026-09-25 绑定点根修：流内时序位取 shadowedRange 起点（被替代内容区间
        // 锚），summary 信封 seq 只作降级——否则卡恒落在日志尾部（堆积根因之一）。
        override val sortSeq: Long get() = entry.shadowStartSeq ?: entry.seq
    }
}

/** 单个消息条目的内嵌卡载荷：before = 内容之上（视觉），after = 内容之下。 */
internal data class TranscriptCardExtras(
    val before: List<TranscriptCardItem> = emptyList(),
    val after: List<TranscriptCardItem> = emptyList(),
) {
    val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty()
}

internal object TranscriptPlan {

    fun cards(
        commands: List<CommandFeedback>,
        compactions: List<CompactionEntry>,
    ): List<TranscriptCardItem> =
        commands.map { TranscriptCardItem.Command(it) } + compactions.map { TranscriptCardItem.Compaction(it) }

    /**
     * 归并计划。
     *
     * @param displaySeqs displayItems 各项消息 seq（newest→oldest 发射序；null =
     *   非 DSH id——按 [Long.MIN_VALUE] 处理，卡恒落其下）
     * @param visualBottomEntryKeys 各 display 组**视觉底** entry 的 key（组内
     *   发射序首个 code index——after 卡挂点）
     * @param visualTopEntryKeys 各 display 组**视觉顶** entry 的 key（组内发射序
     *   末个 code index——before 卡挂点）
     * @return (entryKey → 内嵌卡载荷) + trailing 卡（视觉顶部独立 item，发射序
     *   降序——视觉上自底向上 = 时间自新向旧）
     */
    fun build(
        displaySeqs: List<Long?>,
        visualBottomEntryKeys: List<String?>,
        visualTopEntryKeys: List<String?>,
        cards: List<TranscriptCardItem>,
    ): Pair<Map<String, TranscriptCardExtras>, List<TranscriptCardItem>> {
        if (cards.isEmpty()) return emptyMap<String, TranscriptCardExtras>() to emptyList()
        val sorted = cards.sortedBy { it.sortSeq }
        // seq 未知(null)的 display 项不参与排序比较——2026-09-25 修复「压缩卡堆积」：
        // 旧实现把 null 映射成 Long.MIN_VALUE，流式/本地消息(新est 项 id 解不出 seq)
        // 常驻 index 0，导致「卡比最新消息还新」判定对所有卡恒真 → 全部卡塞进
        // display 0 组 after 桶(extras=1 实证)。null 项视为「不可比较」，锚点查找
        // 只在可比较(已知 seq)项中进行。
        val knownSeqIdx: List<Pair<Int, Long>> =
            displaySeqs.withIndex().mapNotNull { (i, s) -> s?.let { i to it } }
        val newestKnownSeq = knownSeqIdx.firstOrNull()?.second
        val before = LinkedHashMap<String, MutableList<TranscriptCardItem>>()
        val after = LinkedHashMap<String, MutableList<TranscriptCardItem>>()
        val trailing = mutableListOf<TranscriptCardItem>()
        for (card in sorted) {
            when {
                // 无任何可比较消息：全部退化为顶部独立 item。
                knownSeqIdx.isEmpty() -> trailing += card
                // 卡比「最新已知 seq」还新：贴尾语义保持——display 0 组(无论其
                // seq 可否解析,它就是视觉最新)视觉底之下。已知 seq 全在场时与
                // 旧判定(card.sortSeq > effective[0])完全等价。
                card.sortSeq > (newestKnownSeq ?: Long.MIN_VALUE) -> {
                    val bottomKey = visualBottomEntryKeys.getOrNull(0)
                    if (bottomKey != null) {
                        after.getOrPut(bottomKey) { mutableListOf() } += card
                    } else {
                        trailing += card
                    }
                }
                // 介于两消息之间：锚 = 已知 seq 中第一条比卡更旧的消息(其组视觉顶
                // 之上=流内时序位)。旧实现 indexOfFirst 命中 MIN_VALUE 哨兵会把卡
                // 锚到 seq 未知的消息组上(同样错误),此处只在已知项中找。
                else -> {
                    val anchor = knownSeqIdx.firstOrNull { it.second < card.sortSeq }
                    val topKey = anchor?.let { visualTopEntryKeys.getOrNull(it.first) }
                    if (topKey != null) {
                        before.getOrPut(topKey) { mutableListOf() } += card
                    } else {
                        trailing += card
                    }
                }
            }
        }
        val extras = (before.keys + after.keys).associateWith { key ->
            TranscriptCardExtras(before = before[key].orEmpty(), after = after[key].orEmpty())
        }
        return extras to trailing
    }
}
