package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E2E-F P1 复现尝试：删除自定义答案后提交载荷污染。
 * 把 QuestionCard.onOptionClick 的 answersPerQuestion 操作逻辑原样复刻，
 * 在 JVM 单测中跑完整操作序列，验证状态是否与 UI 预期一致。
 */
class CustomAnswerToggleFlowTest {

    // 原样复刻 QuestionCard.kt:168-186 的分支逻辑
    private fun toggle(
        answers: MutableList<MutableList<String>>,
        pageIndex: Int,
        label: String,
        isMultiple: Boolean
    ) {
        val current = answers.getOrNull(pageIndex)?.toMutableList() ?: mutableListOf()
        if (!isMultiple) {
            if (pageIndex < answers.size) {
                answers[pageIndex] =
                    if (current == listOf(label)) mutableListOf() else mutableListOf(label)
            }
        } else {
            if (label in current) current.remove(label) else current.add(label)
            if (pageIndex < answers.size) answers[pageIndex] = current
        }
    }

    @Test
    fun ep2_single_addThenDelete_submitShouldBeEmpty() {
        val answers = mutableListOf(mutableListOf<String>()) // 单问题卡
        // 加 Mango（单选：current=[] != [Mango] → [Mango]）
        toggle(answers, 0, "Mango", isMultiple = false)
        assertEquals(listOf("Mango"), answers[0])
        // 删 Mango（✕ → toggle off：current==[Mango] → 清空）
        toggle(answers, 0, "Mango", isMultiple = false)
        assertEquals(0, answers[0].size) // UI 零选中 → 载荷应空 → Submit 应禁用
    }

    @Test
    fun ep1_multi_addThenDelete_payloadShouldBeEmpty() {
        val answers = mutableListOf(mutableListOf<String>())
        toggle(answers, 0, "Mango", isMultiple = true)  // add
        assertEquals(listOf("Mango"), answers[0])
        toggle(answers, 0, "Mango", isMultiple = true)  // remove
        assertEquals(0, answers[0].size)
    }

    @Test
    fun twoQuestionCard_q1CustomDelete_q2Untouched() {
        val answers = mutableListOf(mutableListOf<String>(), mutableListOf<String>())
        toggle(answers, 0, "Mango", isMultiple = false)
        toggle(answers, 0, "Mango", isMultiple = false) // delete
        assertEquals(0, answers[0].size)
        assertEquals(0, answers[1].size)
    }

    @Test
    fun cherryNeverAppearsWithoutExplicitToggle() {
        val answers = mutableListOf(mutableListOf<String>())
        toggle(answers, 0, "Mango", isMultiple = false)
        toggle(answers, 0, "Mango", isMultiple = false)
        // 无任何 Cherry toggle 的情况下，Cherry 不可能出现在载荷
        assertEquals(false, answers.any { it.contains("Cherry") })
    }
}
