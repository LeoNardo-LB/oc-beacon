package dev.leonardo.ocbeacon.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isNavigableChildSessionId]（#242 导航源头拦截判定）单测。
 *
 * 判定的本意（docs/journal/2026-08-27-event-card-unification.md #242 取证）：
 * 拦截工具调用 jobID（call_…）等**非会话 id** 混入子智能体会话导航——
 * 曾致 GET /message 400 → 空 Chat 页 + 会话列表被空 title 会话污染。
 *
 * 双服务器合法形态都必须放行（本测试类的核心回归点）：
 * - OpenCode V1：ses_ 前缀
 * - DSH：session-<uuid> 或裸 <uuid>
 */
class NavGraphChildSessionIdTest {

    // ---------- OpenCode V1 形态：放行 ----------

    @Test
    fun `V1 ses_ 前缀会话 id 放行`() {
        assertTrue(isNavigableChildSessionId("ses_fc7c18673"))
    }

    @Test
    fun `V1 ses_ 长会话 id 放行`() {
        assertTrue(isNavigableChildSessionId("ses_eaaee999750c45ba86f0386c"))
    }

    // ---------- DSH 形态：放行（2026-09 修复的误拦回归点） ----------

    @Test
    fun `DSH session-uuid 形态会话 id 放行`() {
        assertTrue(isNavigableChildSessionId("session-11111111-0000-0000-0000-000000000004"))
    }

    @Test
    fun `DSH 裸 uuid 形态会话 id 放行`() {
        assertTrue(isNavigableChildSessionId("11111111-0000-0000-0000-000000000004"))
    }

    @Test
    fun `DSH 大写 uuid 形态会话 id 放行`() {
        assertTrue(isNavigableChildSessionId("11111111-0000-0000-0000-00000000000F"))
    }

    // ---------- #242 原案拦截目标：继续拦截 ----------

    @Test
    fun `工具调用 jobID call_ 前缀拦截`() {
        // journal #242 实锤样本：shell 卡曾以此 jobID 当会话 id 导航 → 400
        assertFalse(isNavigableChildSessionId("call_eaaee999750c45ba86f0386c"))
    }

    @Test
    fun `消息 id msg_ 前缀拦截`() {
        assertFalse(isNavigableChildSessionId("msg_0198a7b2c3d4e5f6a7b8c9d0"))
    }

    @Test
    fun `空白与垃圾值拦截`() {
        assertFalse(isNavigableChildSessionId(""))
        assertFalse(isNavigableChildSessionId("   "))
        assertFalse(isNavigableChildSessionId("undefined"))
        assertFalse(isNavigableChildSessionId("null"))
    }

    @Test
    fun `裸 ses_ 前缀无主体拦截`() {
        assertFalse(isNavigableChildSessionId("ses_"))
    }
}
