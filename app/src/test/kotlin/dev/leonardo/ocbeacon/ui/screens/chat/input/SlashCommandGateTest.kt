package dev.leonardo.ocbeacon.ui.screens.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #312④ 命令带图拦截判定（纯逻辑，PlanChipGate 同族）。
 *
 * 服务器契约（dsh-commands CommandInputDescriptor）：命令仅在声明
 * input.images=true 时接受图片；absent/false → executor 拒绝且
 * “capable composers refuse the submission before dispatch”——客户端应在
 * 派发前拦截。命令形态与 ChatScreenBottomBar doSend 分流同语义。
 */
class SlashCommandGateTest {

    /** 命令形态提取：前导斜杠 + 非转义 + 命令名非空。 */
    @Test
    fun `commandNameOf extracts command name`() {
        assertEquals("review", SlashCommandGate.commandNameOf("/review"))
        assertEquals("compact", SlashCommandGate.commandNameOf("/compact now please"))
        assertEquals("permission", SlashCommandGate.commandNameOf("/permission danger-full-access"))
    }

    @Test
    fun `commandNameOf returns null for non-command text`() {
        assertNull(SlashCommandGate.commandNameOf("hello world"))
        assertNull(SlashCommandGate.commandNameOf("/ spaced escape"))
        assertNull(SlashCommandGate.commandNameOf("/"))
        assertNull(SlashCommandGate.commandNameOf(""))
        assertNull(SlashCommandGate.commandNameOf("see /review later"))
    }

    /** 拦截判定：命令形态 + 图片附件非空 → 拦。 */
    @Test
    fun `blocksImages true for command with image attachments`() {
        assertTrue(SlashCommandGate.blocksImages("/review", 1))
        assertTrue(SlashCommandGate.blocksImages("/compact now please", 2))
    }

    @Test
    fun `blocksImages false without attachments`() {
        assertFalse(SlashCommandGate.blocksImages("/review", 0))
    }

    @Test
    fun `blocksImages false for non-command text with attachments`() {
        assertFalse(SlashCommandGate.blocksImages("hello", 1))
        assertFalse(SlashCommandGate.blocksImages("/ spaced escape", 1))
        assertFalse(SlashCommandGate.blocksImages("/", 1))
    }
}