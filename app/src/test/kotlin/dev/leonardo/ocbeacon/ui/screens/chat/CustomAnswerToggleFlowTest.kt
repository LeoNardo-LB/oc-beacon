package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.ui.screens.chat.dialog.toggleQuestionAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提问卡答案 toggle 流测试——直接调用生产纯函数 [toggleQuestionAnswer]
 * （QuestionCard.onOptionClick 单一真相源；原版本文件是手工复刻镜像，
 * 2026-08-18 重写为真源调用，杜绝逻辑漂移）。
 *
 * 核心不变量（2026-08-18 用户反馈修复）：选项槽位与自定义槽位互不挤占——
 * 点选项不清自定义答案，保存自定义不清已选选项。
 */
class CustomAnswerToggleFlowTest {

    private val optionLabels = setOf("Apple", "Banana", "Cherry")

    // ---- E2E-F 时代回归：加/删自定义的载荷正确性 ----

    @Test
    fun ep2_single_addThenDelete_submitShouldBeEmpty() {
        var answers = toggleQuestionAnswer(emptyList(), "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Mango"), answers)
        answers = toggleQuestionAnswer(answers, "Mango", optionLabels, isSingle = true)
        assertEquals(0, answers.size) // UI 零选中 → 载荷空 → Submit 应禁用
    }

    @Test
    fun ep1_multi_addThenDelete_payloadShouldBeEmpty() {
        var answers = toggleQuestionAnswer(emptyList(), "Mango", optionLabels, isSingle = false)
        assertEquals(listOf("Mango"), answers)
        answers = toggleQuestionAnswer(answers, "Mango", optionLabels, isSingle = false)
        assertEquals(0, answers.size)
    }

    @Test
    fun cherryNeverAppearsWithoutExplicitToggle() {
        val answers = toggleQuestionAnswer(emptyList(), "Mango", optionLabels, isSingle = true)
        assertEquals(false, answers.contains("Cherry"))
    }

    // ---- 2026-08-18 用户反馈：点选项不得丢自定义答案 ----

    @Test
    fun `single - tap option keeps saved custom answer`() {
        // Mango 已保存，点 Apple：Apple 选中 + Mango 保留（旧行为：Mango 被整表替换挤掉）
        val answers = toggleQuestionAnswer(listOf("Mango"), "Apple", optionLabels, isSingle = true)
        assertEquals(listOf("Apple", "Mango"), answers)
    }

    @Test
    fun `single - saving custom keeps selected option`() {
        // 反向：Apple 已选，保存自定义 Mango：Apple 保留（旧行为：被 listOf(Mango) 挤掉）
        val answers = toggleQuestionAnswer(listOf("Apple"), "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Apple", "Mango"), answers)
    }

    @Test
    fun `single - switching option replaces option slot only`() {
        // Apple+Mango → 点 Banana：选项槽位换成 Banana，Mango 保留
        val answers = toggleQuestionAnswer(listOf("Apple", "Mango"), "Banana", optionLabels, isSingle = true)
        assertEquals(listOf("Banana", "Mango"), answers)
    }

    @Test
    fun `single - tapping selected option deselects option keeps custom`() {
        // Apple+Mango → 再点 Apple：仅取消 Apple，Mango 保留
        val answers = toggleQuestionAnswer(listOf("Apple", "Mango"), "Apple", optionLabels, isSingle = true)
        assertEquals(listOf("Mango"), answers)
    }

    @Test
    fun `multi - toggling option never touches custom slot`() {
        // Apple+Mango → 点 Apple 取消 → 仅剩 Mango；再点 Apple → 回到 Apple+Mango
        val afterOff = toggleQuestionAnswer(listOf("Apple", "Mango"), "Apple", optionLabels, isSingle = false)
        assertEquals(listOf("Mango"), afterOff)
        val afterOn = toggleQuestionAnswer(afterOff, "Apple", optionLabels, isSingle = false)
        assertEquals(listOf("Apple", "Mango"), afterOn)
    }

    // ---- 编辑替换 = 先 toggle off 旧值，再 toggle on 新值 ----

    @Test
    fun editReplace_customSwapped_optionsUntouched() {
        var answers = toggleQuestionAnswer(listOf("Apple", "Mango"), "Mango", optionLabels, isSingle = true)
        assertEquals(listOf("Apple"), answers) // 旧自定义移除 → 输入框应回归
        answers = toggleQuestionAnswer(answers, "Mango pie", optionLabels, isSingle = true)
        assertEquals(listOf("Apple", "Mango pie"), answers) // 新自定义替换，Apple 全程保留
    }
}
