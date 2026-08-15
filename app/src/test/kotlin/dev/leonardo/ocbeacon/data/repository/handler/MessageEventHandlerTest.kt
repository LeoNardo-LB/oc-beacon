package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageEventHandlerTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun testUserMessage(id: String, sessionId: String) = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = System.currentTimeMillis())
    )

    private fun testAssistantMessage(id: String, sessionId: String) = Message.Assistant(
        id = id,
        sessionId = sessionId,
        parentId = "parent-$id",
        time = TimeInfo(created = System.currentTimeMillis())
    )

    @Test
    fun `handles MessageUpdated - add new`() {
        val msg = testUserMessage("m1", "s1")
        handler.handleMessageUpdated(SseEvent.MessageUpdated(msg))

        assertEquals(listOf(msg), handler.messages.value["s1"])
    }

    @Test
    fun `handles MessageUpdated - update existing`() {
        val msg = testUserMessage("m1", "s1")
        handler.handleMessageUpdated(SseEvent.MessageUpdated(msg))

        val updated = msg.copy(time = TimeInfo(created = msg.time.created + 1000))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(updated))

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals(updated, handler.messages.value["s1"]!![0])
    }

    @Test
    fun `handles MessageUpdated - sorts by created ascending`() {
        val old = testUserMessage("m1", "s1").copy(time = TimeInfo(created = 1000L))
        val recent = testUserMessage("m2", "s1").copy(time = TimeInfo(created = 3000L))
        val mid = testUserMessage("m3", "s1").copy(time = TimeInfo(created = 2000L))

        handler.handleMessageUpdated(SseEvent.MessageUpdated(old))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(recent))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(mid))

        val msgs = handler.messages.value["s1"]!!
        assertEquals("m1", msgs[0].id)
        assertEquals("m3", msgs[1].id)
        assertEquals("m2", msgs[2].id)
    }

    // ============ 2026-08-15：统计栏丢模型/耗时（step.ended 整替换修复） ============

    @Test
    fun `step ended update preserves model metadata from step started`() {
        // V2 场景：step.started 写入模型信息 → step.ended（契约不含 model）到达。
        // 修复前：整替换 → modelId/agent 被抹（统计栏丢模型名）；
        // 修复后：非空合并 → 模型保留 + tokens/cost 写入。
        val started = Message.Assistant(
            id = "a1", sessionId = "s1", parentId = "p1",
            time = TimeInfo(created = 1000L),
            modelId = "glm-5.2", providerId = "zai", agent = "build"
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(started))

        val ended = Message.Assistant(
            id = "a1", sessionId = "s1", parentId = "",
            time = TimeInfo(created = 9000L),  // step.ended 映射用本地时刻（晚于 started）
            cost = 0.5,
            tokens = Message.Assistant.Tokens(input = 100, output = 50)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(ended))

        val result = handler.messages.value["s1"]!![0] as Message.Assistant
        assertEquals("glm-5.2", result.modelId)      // 修复点：不被 step.ended 抹掉
        assertEquals("zai", result.providerId)
        assertEquals("build", result.agent)
        assertEquals(0.5, result.cost!!, 0.001)      // step.ended 携带的 cost 写入
        assertEquals(50, result.tokens!!.output)     // tokens 写入（圆环数据源）
        assertEquals(1000L, result.time.created)     // created 取较早值（耗时不归零）
    }

    @Test
    fun `assistant update with model overwrites existing null model`() {
        // REST 权威数据（带 model）到达时覆盖 SSE 的空值（正常覆盖语义保留）
        val started = Message.Assistant(
            id = "a1", sessionId = "s1", parentId = "p1",
            time = TimeInfo(created = 1000L)
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(started))

        val withModel = Message.Assistant(
            id = "a1", sessionId = "s1", parentId = "p1",
            time = TimeInfo(created = 1000L, completed = 2000L),
            modelId = "deepseek-v4-pro", providerId = "deepseek"
        )
        handler.handleMessageUpdated(SseEvent.MessageUpdated(withModel))

        val result = handler.messages.value["s1"]!![0] as Message.Assistant
        assertEquals("deepseek-v4-pro", result.modelId)
        assertEquals(2000L, result.time.completed)
    }

    @Test
    fun `handles MessageRemoved`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m1", "s1")))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m2", "s1")))

        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"))

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals("m2", handler.messages.value["s1"]!![0].id)
    }

    @Test
    fun `handles MessageRemoved also removes parts`() {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"))

        assertFalse(handler.parts.value.containsKey("m1"))
    }

    @Test
    fun `handles MessagePartUpdated - add new part`() {
        val part = Part.Text(id = "part1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        assertEquals(listOf(part), handler.parts.value["m1"])
    }

    @Test
    fun `handles MessagePartUpdated - replace existing part`() {
        val original = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        val updated = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello World")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(original))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(updated))

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - appends text`() {
        val part = Part.Text(id = "part1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " World"
        ))
        handler.forceFlushDeltas()

        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - appends to reasoning`() {
        val part = Part.Reasoning(id = "part1", sessionId = "s1", messageId = "m1", text = "Thinking")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " more"
        ))
        handler.forceFlushDeltas()

        assertEquals("Thinking more", (handler.parts.value["m1"]!![0] as Part.Reasoning).text)
    }

    @Test
    fun `handles MessagePartDelta creates synthetic part when partId missing`() {
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "nonexistent",
            field = "text", delta = "created"
        ))
        handler.forceFlushDeltas()

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("created", (handler.parts.value["m1"]!![0] as Part.Text).text)
        assertEquals("nonexistent", handler.parts.value["m1"]!![0].id)
    }

    @Test
    fun `handles MessagePartDelta does nothing for non-text part types`() {
        val toolPart = Part.Tool(id = "p1", sessionId = "s1", messageId = "m1", callId = "c1", tool = "bash", state = ToolState.Pending())
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(toolPart))

        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1",
            field = "text", delta = "ignored"
        ))

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertTrue(handler.parts.value["m1"]!![0] is Part.Tool)
    }

    @Test
    fun `handles MessagePartRemoved`() {
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        val part2 = Part.Text(id = "p2", sessionId = "s1", messageId = "m1")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part1))
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part2))

        handler.handleMessagePartRemoved(SseEvent.MessagePartRemoved(sessionId = "s1", messageId = "m1", partId = "p1"))

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("p2", handler.parts.value["m1"]!![0].id)
    }

    @Test
    fun `clearForServer removes messages for sessions`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m1", "s1")))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m2", "s2")))

        handler.clearForServer(setOf("s1"))

        assertNull(handler.messages.value["s1"])
        assertNotNull(handler.messages.value["s2"])
    }

    @Test
    fun `clearForSession removes messages for single session`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m1", "s1")))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m2", "s2")))

        handler.clearForSession("s1")

        assertNull(handler.messages.value["s1"])
        assertNotNull(handler.messages.value["s2"])
    }

    @Test
    fun `setMessages replaces completely`() {
        val msg1 = testUserMessage("m1", "s1")
        val msg2 = testUserMessage("m2", "s1")
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")

        handler.setMessages("s1", listOf(
            MessageWithParts(info = msg1, parts = listOf(part1)),
            MessageWithParts(info = msg2, parts = emptyList())
        ))

        assertEquals(2, handler.messages.value["s1"]!!.size)
        assertEquals(listOf(part1), handler.parts.value["m1"])
    }

    @Test
    fun `mergeMessages preserves SSE-fresh parts`() {
        val existingPart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from SSE")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(existingPart))

        val msg = testUserMessage("m1", "s1")
        val stalePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from REST")
        handler.mergeMessages("s1", listOf(MessageWithParts(msg, listOf(stalePart))))

        assertEquals("from SSE", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `clearAll resets everything`() {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(testUserMessage("m1", "s1")))
        handler.clearAll()
        assertTrue(handler.messages.value.isEmpty())
        assertTrue(handler.parts.value.isEmpty())
    }

    // ============ 合并策略测试（SSE 截断修复）============

    @Test
    fun `handles MessagePartUpdated - preserves longer text from delta`() {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        // Delta 追加 " World" → 文本变为 "Hello World"
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1",
            field = "text", delta = " World"
        ))
        handler.forceFlushDeltas()

        // 服务器发送带原始文本的过期快照
        val stalePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(stalePart))

        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartUpdated - replaces with longer incoming text`() {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hi")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        val longer = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello World")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(longer))

        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `setMessages preserves SSE-fresh longer parts`() {
        // SSE 累积更长的文本
        val ssePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello World from SSE")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(ssePart))

        // REST 返回更短的文本
        val msg = testUserMessage("m1", "s1")
        val restPart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.setMessages("s1", listOf(MessageWithParts(msg, listOf(restPart))))

        assertEquals("Hello World from SSE", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `replaceMessages preserves SSE-fresh longer parts`() {
        // SSE 累积更长的文本
        val ssePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello World from SSE")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(ssePart))

        // REST 返回更短的文本
        val msg = testUserMessage("m1", "s1")
        val restPart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.replaceMessages("s1", listOf(MessageWithParts(msg, listOf(restPart))))

        assertEquals("Hello World from SSE", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - creates synthetic part when missing`() {
        // 没有先前的 updated 事件 —— delta 先到达
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1",
            field = "text", delta = "synthetic"
        ))
        handler.forceFlushDeltas()

        assertEquals(1, handler.parts.value["m1"]!!.size)
        val part = handler.parts.value["m1"]!![0] as Part.Text
        assertEquals("synthetic", part.text)
        assertEquals("p1", part.id)
    }

    @Test
    fun `handles MessagePartUpdated - replaces Reasoning with longer text`() {
        val part = Part.Reasoning(id = "p1", sessionId = "s1", messageId = "m1", text = "Thinking")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))

        // Delta 追加扩展
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1",
            field = "text", delta = " more deeply"
        ))
        handler.forceFlushDeltas()

        // 过期快照到达
        val stale = Part.Reasoning(id = "p1", sessionId = "s1", messageId = "m1", text = "Thinking")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(stale))

        assertEquals("Thinking more deeply", (handler.parts.value["m1"]!![0] as Part.Reasoning).text)
    }

    @Test
    fun `handles MessagePartUpdated - non-text parts still replaced directly`() {
        val toolPart = Part.Tool(id = "p1", sessionId = "s1", messageId = "m1", callId = "c1", tool = "bash", state = ToolState.Pending())
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(toolPart))

        val updatedTool = Part.Tool(id = "p1", sessionId = "s1", messageId = "m1", callId = "c1", tool = "bash", state = ToolState.Running())
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(updatedTool))

        assertTrue(handler.parts.value["m1"]!![0] is Part.Tool)
        assertTrue((handler.parts.value["m1"]!![0] as Part.Tool).state is ToolState.Running)
    }

    // ============ markSessionIdle（REST 回退：强制完成流式输出）============

    @Test
    fun `markSessionIdle sets time_end on incomplete Text and Reasoning parts`() {
        val msg = testAssistantMessage("m1", "s1")
        val textPart = Part.Text(
            id = "p1", sessionId = "s1", messageId = "m1", text = "streaming",
            time = Part.Text.Time(start = 100L, end = null)
        )
        val reasoningPart = Part.Reasoning(
            id = "p2", sessionId = "s1", messageId = "m1", text = "thinking",
            time = Part.Reasoning.Time(start = 100L, end = null)
        )
        handler.setMessages("s1", listOf(MessageWithParts(info = msg, parts = listOf(textPart, reasoningPart))))

        handler.markSessionIdle("s1")

        val parts = handler.parts.value["m1"]!!
        val textAfter = parts.first { it.id == "p1" } as Part.Text
        val reasoningAfter = parts.first { it.id == "p2" } as Part.Reasoning
        assertNotNull("Text part time.end must be force-completed", textAfter.time?.end)
        assertNotNull("Reasoning part time.end must be force-completed", reasoningAfter.time?.end)
        val msgAfter = handler.messages.value["s1"]!!.first() as Message.Assistant
        assertNotNull("Assistant message time.completed must be set", msgAfter.time.completed)
    }

    @Test
    fun `markSessionIdle does not overwrite already-ended part time`() {
        val msg = testAssistantMessage("m1", "s1")
        val endedText = Part.Text(
            id = "p1", sessionId = "s1", messageId = "m1", text = "done",
            time = Part.Text.Time(start = 100L, end = 999L)
        )
        handler.setMessages("s1", listOf(MessageWithParts(info = msg, parts = listOf(endedText))))

        handler.markSessionIdle("s1")

        val textAfter = handler.parts.value["m1"]!![0] as Part.Text
        assertEquals("Already-ended part keeps its original end time", 999L, textAfter.time?.end)
    }

    // ============ #87b：REST 空 id 快照与 SSE part 内容级合并 ============

    @Test
    fun `part updated with blank id merges by content instead of adding duplicate`() {
        // 回归（#87b）：REST 快照 text part id="" vs SSE part id="prt_xxx"——
        // 旧代码按 id 找不到 → 新增第二条 part → 同消息两条文本 → UI 重复渲染
        // （压测实测 "Got it. ... Got it. ..."）
        val ssePart = Part.Text(id = "prt_abc", sessionId = "s1", messageId = "m1", text = "Got it. Message 1 received.")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(ssePart))

        // REST 快照：同内容、空 id
        val restSnapshot = Part.Text(id = "", sessionId = "s1", messageId = "m1", text = "Got it. Message 1 received.")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(restSnapshot))

        val parts = handler.parts.value["m1"]!!
        assertEquals("空 id 快照应与 SSE part 合并而非新增", 1, parts.size)
    }

    @Test
    fun `part updated with blank id and longer content replaces by content match`() {
        // REST 快照比 SSE 累积更长（完整文本）→ 内容前缀匹配后替换，不新增
        val ssePart = Part.Text(id = "prt_abc", sessionId = "s1", messageId = "m1", text = "Got it. Message")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(ssePart))

        val restSnapshot = Part.Text(id = "", sessionId = "s1", messageId = "m1", text = "Got it. Message 1 received.")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(restSnapshot))

        val parts = handler.parts.value["m1"]!!
        assertEquals(1, parts.size)
        val merged = parts[0] as Part.Text
        assertEquals("更长文本应胜出", "Got it. Message 1 received.", merged.text)
    }

    @Test
    fun `part updated with blank id and distinct content still adds new part`() {
        // 空 id 但内容完全不同（真·新 part）→ 不应误合并
        val ssePart = Part.Text(id = "prt_abc", sessionId = "s1", messageId = "m1", text = "First part")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(ssePart))

        val distinct = Part.Text(id = "", sessionId = "s1", messageId = "m1", text = "Completely different content")
        handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(distinct))

        val parts = handler.parts.value["m1"]!!
        assertEquals("内容完全不同不应合并", 2, parts.size)
    }
}
