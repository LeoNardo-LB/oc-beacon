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
    }

    // ---- #497 D3：无 subagents 能力位服务器（V1/V2）子会话只读提示行 ----

    @Test
    fun `opencode child shows read-only hint row when capability absent`() {
        // V1/V2 子会话此前整块空白（无 composer 无提示）——对齐 DSH 提示行形态
        assertTrue(
            SubagentComposerGate.readOnlyHintVisible("parent-1", false, null),
        )
    }

    @Test
    fun `opencode child hint row independent of mode value`() {
        // mode 仅在 subagentsSupported 时解析——此处为不可达态的防御性一致断言
        assertTrue(
            SubagentComposerGate.readOnlyHintVisible("parent-1", false, "continuable"),
        )
        assertTrue(
            SubagentComposerGate.readOnlyHintVisible("parent-1", false, "one-shot"),
        )
    }

    // ---- #497 D4：DSH 子会话 mode 异步解析期 composer 骨架占位 ----

    @Test
    fun `placeholder shows only while dsh child mode unresolved`() {
        // 解析中（mode=null）——占位防布局跳变 + 预热点击（焦点接力宿主）
        assertTrue(
            SubagentComposerGate.composerPlaceholderVisible("parent-1", true, null),
        )
        // 解析完成（无论形态）——占位退场
        assertFalse(
            SubagentComposerGate.composerPlaceholderVisible("parent-1", true, "continuable"),
        )
        assertFalse(
            SubagentComposerGate.composerPlaceholderVisible("parent-1", true, "one-shot"),
        )
    }

    @Test
    fun `placeholder hidden for main session and capability-absent children`() {
        // 主会话：composer 恒开，无解析期
        assertFalse(
            SubagentComposerGate.composerPlaceholderVisible(null, true, null),
        )
        // V1/V2 子会话：mode 恒 null 但能力位缺席——D3 提示行即时渲染，无占位
        assertFalse(
            SubagentComposerGate.composerPlaceholderVisible("parent-1", false, null),
        )
    }

    // ---- #497 D4 边界修正：unresolved 终态（0.2.x 目录 404/目录无本行） ----

    @Test
    fun `unresolved child shows read-only hint row not eternal placeholder`() {
        // 终态哨兵：骨架退场（防「加载中」永久化）+ 提示行接管（续聊无法建立）
        assertFalse(
            SubagentComposerGate.composerPlaceholderVisible("parent-1", true, SubagentComposerGate.MODE_UNRESOLVED),
        )
        assertTrue(
            SubagentComposerGate.readOnlyHintVisible("parent-1", true, SubagentComposerGate.MODE_UNRESOLVED),
        )
        assertFalse(
            SubagentComposerGate.composerVisible("parent-1", true, SubagentComposerGate.MODE_UNRESOLVED),
        )
    }

    // ---- composerVisibleOnError（#550 根修：error 隐藏条件收窄） ----

    @Test
    fun `no error shows composer regardless of messages`() {
        assertTrue(SubagentComposerGate.composerVisibleOnError(null, true))
        assertTrue(SubagentComposerGate.composerVisibleOnError(null, false))
    }

    @Test
    fun `error with empty messages hides composer - ChatErrorState 兜底对齐`() {
        // 真空转加载失败态：ChatErrorState 占屏，composer 隐藏（#310① 防误发语义承载）
        assertFalse(SubagentComposerGate.composerVisibleOnError("Failed to load", true))
    }

    @Test
    fun `error with cached messages keeps composer - 550 永久锁死回归锚点`() {
        // 断线期 error 恒留 + Room 缓存可看：输入栏必须保持（消息能看就该能回复）
        assertTrue(SubagentComposerGate.composerVisibleOnError("Failed to load", false))
    }
}
