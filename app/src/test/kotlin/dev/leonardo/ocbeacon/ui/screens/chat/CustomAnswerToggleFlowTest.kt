package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.ui.screens.chat.dialog.toggleQuestionAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提问卡答案 toggle 流测试——直接调用生产纯函数 [toggleQuestionAnswer]
 * （QuestionCard.onOptionClick 单一真相源；原版本文件是手工复刻镜像，
 * 2026-08-18 重写为真源调用，杜绝逻辑漂移）。
 *
 * 三态模型（2026-08-19 用户反馈修复）：自定义答案 = 勾选 / parked 保留 /
 * 不存在。核心场景（用户原话）：单选保存自定义后再选别的选项，
 * 自定义应"保留内容，但取消勾选"——内容入 parked 槽，不进提交载荷。
 */
class CustomAnswerToggleFlowTest {

    private val optionLabels = setOf("Apple", "Banana", "Cherry")

    // ---- 用户反馈主场景：单选，自定义已勾选，点其他选项 ----

    @Test
    fun `single - option pick unchecks custom but parks content`() {
        // Mango 已勾选 → 点 Apple：载荷只有 Apple；Mango 保留内容但未勾选
        val r = toggleQuestionAnswer(listOf("Mango"), null, "Apple", optionLabels, isSingle = true)
        assertEquals(listOf("Apple"), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    @Test
    fun `single - parked custom recheck replaces option selection`() {
        // parked Mango + Apple 已选 → 点击 parked 行：Mango 勾选、Apple 让位
        val r = toggleQuestionAnswer(listOf("Apple"), "Mango", "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Mango"), r.selected)
        assertNull(r.parkedCustom)
    }

    @Test
    fun `single - option switch keeps parked content`() {
        // parked Mango + Apple 已选 → 切到 Banana：parked 原样保留
        val r = toggleQuestionAnswer(listOf("Apple"), "Mango", "Banana", optionLabels, isSingle = true)
        assertEquals(listOf("Banana"), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    @Test
    fun `single - re-tap selected option clears selection keeps parked`() {
        val r = toggleQuestionAnswer(listOf("Apple"), "Mango", "Apple", optionLabels, isSingle = true)
        assertEquals(emptyList<String>(), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    @Test
    fun `single - checking custom deselects option (mutual exclusion)`() {
        // Apple 已选 → 勾选自定义 Mango：真·单选互斥，载荷恒 ≤1
        // （选项行仍可见可再选，非内容丢失）
        val r = toggleQuestionAnswer(listOf("Apple"), null, "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Mango"), r.selected)
        assertNull(r.parkedCustom)
    }

    @Test
    fun `single - re-tap checked custom parks content`() {
        // 行点击已勾选自定义 = 取消勾选（内容保留）
        val r = toggleQuestionAnswer(listOf("Mango"), null, "Mango", optionLabels, isSingle = true)
        assertEquals(emptyList<String>(), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    @Test
    fun `parked survives option taps when custom never checked`() {
        // parked 存在 + 无选中 → 点选项：parked 不受影响
        val r = toggleQuestionAnswer(emptyList(), "Mango", "Apple", optionLabels, isSingle = true)
        assertEquals(listOf("Apple"), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    // ---- 多选：选项与自定义独立 toggle；取消勾选同样入 parked ----

    @Test
    fun `multi - custom coexists with options when checked`() {
        val r = toggleQuestionAnswer(listOf("Apple"), null, "Mango", optionLabels, isSingle = false)
        assertEquals(listOf("Apple", "Mango"), r.selected)
        assertNull(r.parkedCustom)
    }

    @Test
    fun `multi - re-tap checked custom parks content options untouched`() {
        val r = toggleQuestionAnswer(listOf("Apple", "Mango"), null, "Mango", optionLabels, isSingle = false)
        assertEquals(listOf("Apple"), r.selected)
        assertEquals("Mango", r.parkedCustom)
    }

    @Test
    fun `multi - option toggle never touches custom or parked`() {
        val off = toggleQuestionAnswer(listOf("Apple", "Mango"), null, "Apple", optionLabels, isSingle = false)
        assertEquals(listOf("Mango"), off.selected)
        val on = toggleQuestionAnswer(off.selected, off.parkedCustom, "Apple", optionLabels, isSingle = false)
        assertEquals(listOf("Apple", "Mango"), on.selected)
        assertNull(on.parkedCustom)
    }

    @Test
    fun `multi - parked recheck adds custom back alongside options`() {
        val r = toggleQuestionAnswer(listOf("Apple"), "Mango", "Mango", optionLabels, isSingle = false)
        assertEquals(listOf("Apple", "Mango"), r.selected)
        assertNull(r.parkedCustom)
    }

    // ---- 编辑替换 = park 旧值 + 勾选新值 ----

    @Test
    fun editReplace_customSwapped_endStateClean() {
        // 单选：Mango 已勾选 → 编辑为 Mango pie
        var r = toggleQuestionAnswer(listOf("Mango"), null, "Mango", optionLabels, isSingle = true)
        assertEquals(emptyList<String>(), r.selected)
        assertEquals("Mango", r.parkedCustom)
        r = toggleQuestionAnswer(r.selected, r.parkedCustom, "Mango pie", optionLabels, isSingle = true)
        assertEquals(listOf("Mango pie"), r.selected)
        assertNull(r.parkedCustom)
    }

    // ---- E2E-F 时代回归：载荷正确性 ----

    @Test
    fun ep2_single_submitPayloadNeverExceedsOne() {
        // 单选载荷恒 ≤1：勾选自定义后载荷就是单条
        val r = toggleQuestionAnswer(emptyList(), null, "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Mango"), r.selected)
    }

    @Test
    fun cherryNeverAppearsWithoutExplicitToggle() {
        val r = toggleQuestionAnswer(emptyList(), null, "Mango", optionLabels, isSingle = true)
        assertEquals(false, r.selected.contains("Cherry"))
    }
}
