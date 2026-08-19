package dev.leonardo.ocbeacon.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #79 P0（2026-08-18）：tool part 落库截断——payload JSON 层重写 state.output。
 */
class ToolOutputTruncatorTest {

    private val dq = '"'

    private fun toolPayload(output: String): String =
        "{" + dq + "id" + dq + ":" + dq + "p1" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "bash" + dq + 
            "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "output" + dq + ":" + dq + output + dq + "}}"

    @Test
    fun `short output passes through unchanged`() {
        val payload = toolPayload("hello")
        assertEquals(payload, ToolOutputTruncator.truncateIfNeeded(payload))
    }

    @Test
    fun `long output truncated to preview plus marker`() {
        val long = "x".repeat(5000)
        val result = ToolOutputTruncator.truncateIfNeeded(toolPayload(long))
        assertTrue(result.contains("x".repeat(500)))
        assertTrue(result.contains("[truncated, full output on server]"))
        assertTrue("截断后长度远小于原", result.length < 1000)
    }

    @Test
    fun `non-tool payload without state passthrough`() {
        val payload = "{" + dq + "id" + dq + ":" + dq + "t1" + dq + "," + dq + "type" + dq + ":" + dq + "text" + dq + "," + dq + "text" + dq + ":" + dq + "hi" + dq + "}"
        assertEquals(payload, ToolOutputTruncator.truncateIfNeeded(payload))
    }

    @Test
    fun `malformed json passthrough unchanged`() {
        val bad = "not-json-at-all"
        assertEquals(bad, ToolOutputTruncator.truncateIfNeeded(bad))
    }

    @Test
    fun `other state fields preserved`() {
        val long = "y".repeat(2000)
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p2" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "read" + dq + 
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "output" + dq + ":" + dq + long + dq + "," + 
                dq + "title" + dq + ":" + dq + "file.ts" + dq + "," + dq + "input" + dq + ":{" + dq + "path" + dq + ":" + dq + "/a/b" + dq + "}}}")
        val result = ToolOutputTruncator.truncateIfNeeded(payload)
        assertTrue(result.contains("file.ts"))
        assertTrue(result.contains("/a/b"))
    }

    // ============ #79 P1（2026-08-19）：input/metadata 递归原语截断 ============

    /**
     * write 工具实测形态：state.input.content 为 18.8KB 文件内容——递归截断
     * 超长字符串原语，对象结构（键/其他短值）原样保留。
     */
    @Test
    fun `P1 long string primitive inside input truncated structure preserved`() {
        val bigContent = "w".repeat(3000)
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p3" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "write" + dq +
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "input" + dq + ":{" + dq + "filePath" + dq + ":" + dq + "/tmp/a.txt" + dq +
                "," + dq + "content" + dq + ":" + dq + bigContent + dq + "}," + dq + "output" + dq + ":" + dq + "ok" + dq + "}}")
        val result = ToolOutputTruncator.truncateIfNeeded(payload)
        assertTrue("path preserved", result.contains("/tmp/a.txt"))
        assertTrue("truncated preview kept", result.contains("w".repeat(500)))
        assertTrue("marker present", result.contains("[truncated, full output on server]"))
        assertTrue("size collapsed", result.length < 1000)
    }

    /** edit 工具实测形态：metadata.oldStrings 数组内长字符串——数组结构保留逐项截断。 */
    @Test
    fun `P1 long strings inside metadata array elements truncated`() {
        val longOld = "o".repeat(2500)
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p4" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "edit" + dq +
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "metadata" + dq + ":{" + dq + "oldStrings" + dq + ":[" + dq + longOld + dq + "]," +
                dq + "replaceAll" + dq + ":false}," + dq + "output" + dq + ":" + dq + "done" + dq + "}}")
        val result = ToolOutputTruncator.truncateIfNeeded(payload)
        assertTrue("bool preserved", result.contains("false"))
        assertTrue("array truncated", result.contains("o".repeat(500)))
        assertTrue(result.length < 1000)
    }

    /** 短 input（bash 命令等常态）零触碰——快速路径不白付遍历成本。 */
    @Test
    fun `P1 short input untouched passthrough`() {
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p5" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "bash" + dq +
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "input" + dq + ":{" + dq + "command" + dq + ":" + dq + "git status -s" + dq + "}," +
                dq + "output" + dq + ":" + dq + "clean" + dq + "}}")
        assertEquals(payload, ToolOutputTruncator.truncateIfNeeded(payload))
    }

    /** 数字/布尔等非字符串原语不被误改（contentOrNullSafe 只取 isString）。 */
    @Test
    fun `P1 non-string primitives inside input preserved exactly`() {
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p6" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "websearch" + dq +
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "input" + dq + ":{" + dq + "query" + dq + ":" + dq + "q" + dq + "," +
                dq + "limit" + dq + ":5," + dq + "verbose" + dq + ":true}," + dq + "output" + dq + ":" + dq + "r".repeat(4000) + dq + "}}")
        val result = ToolOutputTruncator.truncateIfNeeded(payload)
        assertTrue("int kept", result.contains(":5"))
        assertTrue("bool kept", result.contains(":true"))
    }

    // ============ #79 P1：Reasoning text 截断 ============

    @Test
    fun `P1 reasoning long text truncated other fields intact`() {
        val longThought = "t".repeat(4000)
        val payload = ("{" + dq + "id" + dq + ":" + dq + "r1" + dq + "," + dq + "type" + dq + ":" + dq + "reasoning" + dq + "," + dq + "text" + dq + ":" + dq + longThought + dq +
                "," + dq + "time" + dq + ":{" + dq + "start" + dq + ":1," + dq + "end" + dq + ":2}}")
        val result = ToolOutputTruncator.truncateReasoningIfNeeded(payload)
        assertTrue(result.contains("t".repeat(500)))
        assertTrue(result.contains("[truncated, full output on server]"))
        assertTrue("time intact", result.contains("\"start\":1".replace(dq, dq)) || result.contains("start"))
        assertTrue(result.length < 1000)
    }

    @Test
    fun `P1 reasoning short text passthrough`() {
        val payload = ("{" + dq + "id" + dq + ":" + dq + "r2" + dq + "," + dq + "type" + dq + ":" + dq + "reasoning" + dq + "," + dq + "text" + dq + ":" + dq + "short thought" + dq + "}")
        assertEquals(payload, ToolOutputTruncator.truncateReasoningIfNeeded(payload))
    }
}
