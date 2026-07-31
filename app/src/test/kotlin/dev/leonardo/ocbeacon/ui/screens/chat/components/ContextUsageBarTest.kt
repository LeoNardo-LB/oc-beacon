package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageBarTest {

    @Test
    fun `calculateContextUsage returns 0 when no tokens`() {
        val parts = emptyList<Part>()
        val contextLimit = 128000
        val usage = calculateContextUsage(parts, contextLimit)
        assertEquals(0f, usage, 0.001f)
    }

    @Test
    fun `calculateContextUsage sums StepFinish tokens input and output`() {
        val parts = listOf(
            Part.StepFinish(
                id = "sf1",
                sessionId = "s1",
                messageId = "m1",
                tokens = Part.StepFinish.Tokens(input = 5000, output = 2000)
            ),
            Part.StepFinish(
                id = "sf2",
                sessionId = "s1",
                messageId = "m2",
                tokens = Part.StepFinish.Tokens(input = 3000, output = 1000)
            )
        )
        val contextLimit = 128000
        // Total: 5000 + 2000 + 3000 + 1000 = 11000
        val usage = calculateContextUsage(parts, contextLimit)
        assertEquals(11000f / 128000f, usage, 0.001f)
    }

    @Test
    fun `calculateContextUsage uses total if available`() {
        val parts = listOf(
            Part.StepFinish(
                id = "sf1",
                sessionId = "s1",
                messageId = "m1",
                tokens = Part.StepFinish.Tokens(input = 5000, output = 2000, total = 10000)
            )
        )
        val contextLimit = 128000
        // Should use total (10000) not input+output (7000)
        val usage = calculateContextUsage(parts, contextLimit)
        assertEquals(10000f / 128000f, usage, 0.001f)
    }

    @Test
    fun `calculateContextUsage ignores parts without tokens`() {
        val parts = listOf(
            Part.Text(id = "t1", sessionId = "s1", messageId = "m1", text = "hello"),
            Part.StepFinish(
                id = "sf1",
                sessionId = "s1",
                messageId = "m2",
                tokens = Part.StepFinish.Tokens(input = 5000, output = 2000)
            )
        )
        val contextLimit = 128000
        val usage = calculateContextUsage(parts, contextLimit)
        assertEquals(7000f / 128000f, usage, 0.001f)
    }

    @Test
    fun `calculateContextUsage caps at 1f`() {
        val parts = listOf(
            Part.StepFinish(
                id = "sf1",
                sessionId = "s1",
                messageId = "m1",
                tokens = Part.StepFinish.Tokens(input = 200000, output = 50000)
            )
        )
        val contextLimit = 128000
        val usage = calculateContextUsage(parts, contextLimit)
        assertEquals(1f, usage, 0.001f)
    }

    @Test
    fun `calculateContextUsage handles zero context limit`() {
        val parts = listOf(
            Part.StepFinish(
                id = "sf1",
                sessionId = "s1",
                messageId = "m1",
                tokens = Part.StepFinish.Tokens(input = 5000, output = 2000)
            )
        )
        val usage = calculateContextUsage(parts, 0)
        assertEquals(0f, usage, 0.001f)
    }
}
