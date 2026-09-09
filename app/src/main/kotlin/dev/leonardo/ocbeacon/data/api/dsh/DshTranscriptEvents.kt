package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * #378：历史 fold 产物中的「转录卡族」事件筛选——[DshApiClient.listMessages]
 * 填充 [dev.leonardo.ocbeacon.domain.model.MessagePage.transcriptEvents] 用。
 *
 * 范围 = 命令反馈（command/run|done）+ 压缩实体（compaction/start|summary|end
 * 转录面 + 表面绑定）+ 表面区间替换（surfaceOp 折叠指令）。其余事件（消息装配
 * 域/会话元数据/banner 域）不进历史 dispatch——消息经 MessagePage 主管线，
 * 元数据各有 REST 通道，重复 dispatch 徒增状态抖动。
 */
internal object DshTranscriptEvents {

    fun cardFamily(events: List<SseEvent>): List<SseEvent> =
        if (events.isEmpty()) emptyList() else events.filter { it.isTranscriptCardFamily() }

    private fun SseEvent.isTranscriptCardFamily(): Boolean = when (this) {
        is SseEvent.CommandRunStarted,
        is SseEvent.CommandDone,
        is SseEvent.CompactionStarted,
        is SseEvent.CompactionSummary,
        is SseEvent.CompactionFinished,
        is SseEvent.CompactionSurfaceBound,
        is SseEvent.SurfaceRangeReplaced,
        -> true
        else -> false
    }
}
