package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionRowMenuAction
import dev.leonardo.ocbeacon.ui.screens.sessions.components.sessionRowMenuActions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #311 Task2：会话行长按菜单显隐纯函数——归档集合内外/能力位门控。
 *
 * 契约事实：DSH workspace/archiveSession 幂等 add-only（服务端无移除路径，官方
 * web 亦无 unarchive）——归档单向，已归档行无恢复动作可用，菜单只留「详情」。
 */
class SessionRowMenuActionsTest {

    @Test
    fun `active row on archive-capable server shows details rename archive`() {
        assertEquals(
            listOf(
                SessionRowMenuAction.DETAILS,
                SessionRowMenuAction.RENAME,
                SessionRowMenuAction.ARCHIVE,
            ),
            sessionRowMenuActions(archiveSupported = true, isArchived = false),
        )
    }

    @Test
    fun `active row on non-DSH server hides archive item`() {
        // 非 DSH 后端归档面整体隐藏（快照恒空 + 能力位 false）——菜单不列归档
        assertEquals(
            listOf(SessionRowMenuAction.DETAILS, SessionRowMenuAction.RENAME),
            sessionRowMenuActions(archiveSupported = false, isArchived = false),
        )
    }

    @Test
    fun `archived row shows details only (one-way archive contract)`() {
        // 归档单向：无 wire 级恢复动词——即使服务器支持归档也不列恢复项
        assertEquals(
            listOf(SessionRowMenuAction.DETAILS),
            sessionRowMenuActions(archiveSupported = true, isArchived = true),
        )
    }

    @Test
    fun `archived row on non-DSH server still shows details only`() {
        assertEquals(
            listOf(SessionRowMenuAction.DETAILS),
            sessionRowMenuActions(archiveSupported = false, isArchived = true),
        )
    }
}
