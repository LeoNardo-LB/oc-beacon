package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #310① 子会话 composer 门控纯逻辑（wire 契约：one-shot 官方禁 composer；
 * 加载中/失败保守关——防 one-shot 误发；OpenCode 子会话维持只读镜像）。
 */
class SubagentComposerGateTest {

    // ---- composerVisible ----

    @Test
    fun `main session always shows composer regardless of mode and server type`() {
        assertTrue(
            SubagentComposerGate.composerVisible(null, true, null),
        )
        assertTrue(
            SubagentComposerGate.composerVisible(null, false, "one-shot"),
        )
    }

    @Test
    fun `dsh continuable child shows composer`() {
        assertTrue(
            SubagentComposerGate.composerVisible("parent-1", true, "continuable"),
        )
    }

    @Test
    fun `dsh one-shot child hides composer`() {
        assertFalse(
            SubagentComposerGate.composerVisible("parent-1", true, "one-shot"),
        )
    }

    @Test
    fun `loading and failure modes are conservatively hidden for dsh child`() {
        // mode=null = 加载中 / 失败降级 / 目录无本行——保守隐藏（防 one-shot 误发）
        assertFalse(
            SubagentComposerGate.composerVisible("parent-1", true, null),
        )
    }

    @Test
    fun `opencode parented child stays hidden even with continuable mode`() {
        // OpenCode 子会话维持既有只读镜像（无 subagents 域——mode 不会非空，防御性断言）
        assertFalse(
            SubagentComposerGate.composerVisible("parent-1", false, "continuable"),
        )
    }

    // ---- readOnlyHintVisible ----

    @Test
    fun `one-shot dsh child shows read-only hint row`() {
        assertTrue(
            SubagentComposerGate.readOnlyHintVisible("parent-1", true, "one-shot"),
        )
    }

    @Test
    fun `hint row hidden for continuable loading failure and main session`() {
        assertFalse(
            SubagentComposerGate.readOnlyHintVisible("parent-1", true, "continuable"),
        )
        assertFalse(
            SubagentComposerGate.readOnlyHintVisible("parent-1", true, null),
        )
        assertFalse(
            SubagentComposerGate.readOnlyHintVisible(null, true, "one-shot"),
        )
        assertFalse(
            SubagentComposerGate.readOnlyHintVisible("parent-1", false, "one-shot"),
        )
    }
}
