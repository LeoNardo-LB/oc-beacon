package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * #271：会话历史全量同步（drain）状态。一行 = 一个会话的同步元数据。
 *
 * 状态语义（仅长按菜单展示，行内零展示——用户四轮裁决）：
 * - [STATE_NONE] 未同步：从未 drain 过（或中断未完成）
 * - [STATE_SYNCING] 同步中：drain 进行中
 * - [STATE_SYNCED] 已同步：全量历史已入库（lastSyncAt 有效）
 * - [STATE_FAILED] 失败：可重试（errorMessage 记原因）
 *
 * drain 幂等：中断后重跑 = 从头分页重拉（upsert 天然幂等覆盖同内容），
 * 不做游标续传（简化；页数成本低）。
 */
@Entity(tableName = "session_sync_state")
data class SessionSyncEntity(
    @PrimaryKey val sessionId: String,
    val state: String = STATE_NONE,
    val lastSyncAt: Long? = null,
    val errorMessage: String? = null,
) {
    companion object {
        const val STATE_NONE = "none"
        const val STATE_SYNCING = "syncing"
        const val STATE_SYNCED = "synced"
        const val STATE_FAILED = "failed"
    }
}
