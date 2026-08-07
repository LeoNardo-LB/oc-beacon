package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRepositoryImplTest {

    private lateinit var repo: ChatRepositoryImpl
    private lateinit var messageApi: MessageApi
    private lateinit var sessionApi: SessionApi
    private lateinit var terminalApi: TerminalApi
    private lateinit var providerApi: ProviderApi
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var serverRepo: ServerDataStore
    private lateinit var permissionAutoApprover: PermissionAutoApprover
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var messageHandler: MessageEventHandler
    private lateinit var permissionHandler: PermissionEventHandler
    private lateinit var questionHandler: QuestionEventHandler

    @Before
    fun setup() {
        messageApi = mockk(relaxed = true)
        sessionApi = mockk(relaxed = true)
        terminalApi = mockk(relaxed = true)
        providerApi = mockk(relaxed = true)
        serverRepo = mockk(relaxed = true)
        permissionAutoApprover = mockk(relaxed = true)
        sessionHandler = SessionEventHandler()
        messageHandler = MessageEventHandler()
        permissionHandler = PermissionEventHandler()
        questionHandler = QuestionEventHandler()
        val miscHandler = MiscEventHandler()

        val sessionStateService = mockk<SessionStateService>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            messagePartHandler = MessagePartHandler(messageHandler),
            messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
            messageRemovedHandler = MessageRemovedHandler(messageHandler),
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler,
            sessionNextHandler = SessionNextEventHandler(),
            sessionStateService = sessionStateService,
            settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        )
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
        repo = ChatRepositoryImpl(messageApi, sessionApi, terminalApi, providerApi, eventDispatcher, serverRepo, permissionAutoApprover)
    }

    // ============ getMessagesFlow ============

    @Test
    fun `getMessagesFlow returns messages from dispatcher`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        messageHandler.setMessages("s1", listOf(MessageWithParts(msg, emptyList())))

        val messages = repo.getMessagesFlow("s1").first()
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0].id)
    }

    @Test
    fun `getMessagesFlow returns empty for unknown session`() = runTest {
        val messages = repo.getMessagesFlow("unknown").first()
        assertTrue(messages.isEmpty())
    }

    // ============ getPermissionsFlow ============

    @Test
    fun `getPermissionsFlow maps events to PermissionState`() = runTest {
        val event = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "file-write",
            patterns = listOf("/tmp/*"),
            metadata = mapOf("path" to "/tmp/test"),
            always = false,
            tool = null
        )
        permissionHandler.setPermissions("s1", listOf(event))

        val permissions = repo.getPermissionsFlow("s1").first()
        assertEquals(1, permissions.size)
        assertEquals("p1", permissions[0].id)
        assertEquals("file-write", permissions[0].permission)
        assertEquals(listOf("/tmp/*"), permissions[0].patterns)
        assertEquals(mapOf("path" to "/tmp/test"), permissions[0].metadata)
    }

    @Test
    fun `getPermissionsFlow returns empty for unknown session`() = runTest {
        val permissions = repo.getPermissionsFlow("unknown").first()
        assertTrue(permissions.isEmpty())
    }

    // ============ getQuestionsFlow ============

    @Test
    fun `getQuestionsFlow maps events to QuestionState`() = runTest {
        val event = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Action",
                    question = "Proceed?",
                    options = listOf(
                        SseEvent.QuestionAsked.Option(label = "Yes", description = "Go ahead")
                    )
                )
            ),
            tool = null
        )
        questionHandler.setQuestions("s1", listOf(event))

        val questions = repo.getQuestionsFlow("s1").first()
        assertEquals(1, questions.size)
        assertEquals("q1", questions[0].id)
        assertEquals(1, questions[0].questions.size)
        assertEquals("Proceed?", questions[0].questions[0].question)
        assertEquals(1, questions[0].questions[0].options.size)
        assertEquals("Yes", questions[0].questions[0].options[0].label)
    }

    // ============ getToolExpandedStates ============

    @Test
    fun `getToolExpandedStates returns map and setToolExpanded works`() {
        repo.setToolExpanded("tool1", true)
        assertTrue(repo.getToolExpandedStates()["tool1"] == true)
    }

    // ============ sendMessage ============

    @Test
    fun `sendMessage returns failure when session not tracked`() = runTest {
        val result = repo.sendMessage("unknown", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage calls api when session tracked`() = runTest {
        // Set up session tracking
        sessionHandler.setSessions("server1", listOf(
            Session(id = "s1", title = "Test", time = Session.Time(created = 1000L, updated = 2000L))
        ))
        coEvery { serverRepo.getServer("server1") } returns ServerConfig(
            id = "server1", url = "http://localhost:4096"
        )
        coEvery { messageApi.promptAsync(any(), "s1", any()) } returns Unit

        val textPart = Part.Text(id = "", sessionId = "s1", messageId = "", text = "hello")
        val result = repo.sendMessage("s1", listOf(textPart))
        assertTrue(result.isSuccess)
    }
}
