# Phase 0: Chat Compose UI Integration Test Infrastructure

**Date:** 2026-07-10  
**Status:** Approved  
**Parent:** Codebase optimization roadmap (5 phases)

## 1. Context & Motivation

### Problem

The SSE scroll stability bug (beta.437→beta.445) took 3 rounds to diagnose because the chat UI layer has **zero Compose integration tests**. The critical scroll logic — `streamingMsgId` derivation, `shouldCompensate` self-healing, `autoScrollEnabled` reset — is entirely untested.

### Current State

- 375 Kotlin source files, ~48K LOC
- 113 unit tests (30% ratio) — concentrated in domain/data layers
- 20 androidTest files — all test **isolated components** (CompactionBanner, TokenUsageCard, etc.)
- **Zero tests** for ChatScreen, ChatMessageList, or any scroll behavior
- ChatViewModel: 22 constructor dependencies, 1205 lines
- ChatScreen.kt: complexity 81, cognitive 198

### Goal

Build a Hilt-based Compose integration test infrastructure for ChatScreen that covers scroll stability + all chat interaction behaviors. This serves as the safety net for Phase 1-3 refactoring.

## 2. Architecture

### 2.1 Strategy: Fake at Repository Level, Keep UseCases Real

```
Test boundary
─────────────────────────────────────────
ChatScreen (real)  →  ChatViewModel (real)
                         ↓
                     UseCases (real)     ← unchanged
                         ↓
                     Repositories (FAKE) ← test-controlled
                         ↓
                     StateFlows          ← MutableStateFlow, tests set .value
─────────────────────────────────────────
```

Rationale: UseCases are thin delegates to repositories. Faking repositories gives us full data control while keeping all ViewModel + UseCase logic exercised. SessionStateService is not an interface — it's kept real because it depends on SessionRepository (already faked), so its FSM will produce correct activity states from fake data.

### 2.2 File Structure

```
app/src/androidTest/kotlin/dev/leonardo/ocremotev2/
  fakes/
    FakeChatRepository.kt
    FakeSessionRepository.kt
    FakeSettingsRepository.kt
    FakeAgentRepository.kt
    FakeDraftRepository.kt
    FakeProviderRepository.kt
    FakeFileRepository.kt
    FakeVcsRepository.kt
    FakeTerminalRepository.kt
    FakeMcpRepository.kt
  di/
    FakeDomainModule.kt
    FakeApiModule.kt
    FakeNetworkModule.kt
  builder/
    TestMessageBuilder.kt
    TestSessionBuilder.kt
    TestSettingsBuilder.kt
  chat/
    ChatScrollStabilityTest.kt
    ChatMessageRenderingTest.kt
    ChatInteractionTest.kt
    ChatInputTest.kt
```

## 3. Fake Repository Design

### 3.1 Unified Pattern

Each fake follows the same three-tier pattern:

1. **Flow methods** → expose underlying `MutableStateFlow` as public fields. Tests set `.value` directly.
2. **suspend methods** → return `Result.success(default)`. Each has a corresponding `var xxxResult` field tests can override.
3. **Sync mutation methods** → record calls in mutable lists (for assertions) and update the corresponding `MutableStateFlow`.

### 3.2 FakeChatRepository (42 methods)

Core controllable state:

| State field | Type | Drives |
|-------------|------|--------|
| `messagesState` | `MutableStateFlow<List<Message>>` | Message list rendering |
| `partsState` | `MutableStateFlow<List<Part>>` | Part content rendering |
| `allPartsMapState` | `MutableStateFlow<Map<String, List<Part>>>` | Multi-session parts |
| `permissionsState` | `MutableStateFlow<List<PermissionState>>` | Permission dialogs |
| `questionsState` | `MutableStateFlow<List<QuestionState>>` | Question dialogs |
| `toolProgressState` | `MutableStateFlow<List<ToolProgressInfo>?>` | Tool progress bars |
| `stepProgressState` | `MutableStateFlow<StepProgressInfo?>` | Step indicators |
| `compactionState` | `MutableStateFlow<CompactionStateInfo?>` | Compaction banner |
| `sessionDiffsState` | `MutableStateFlow<List<FileDiff>>` | Git diff display |

Configurable suspend results:

```kotlin
var sendMessageResult: Result<Message> = Result.success(testMessage())
var replyPermissionResult: Result<Boolean> = Result.success(true)
var replyQuestionResult: Result<Boolean> = Result.success(true)
var undoRedoResult: Result<Unit> = Result.success(Unit)
// ... one per suspend method
```

Call recording:

```kotlin
val sentMessages = mutableListOf<Pair<String, List<Part>>>()
val repliedPermissions = mutableListOf<Pair<String, String>>()
// ... one per side-effectful method
```

### 3.3 FakeSessionRepository (20 methods)

Core controllable state:

| State field | Type | Drives |
|-------------|------|--------|
| `sessionsState` | `MutableStateFlow<List<Session>>` | Session list |
| `statusesState` | `MutableStateFlow<Map<String, SessionStatus>>` | Session status badges |
| `currentAgentFlow` | `MutableStateFlow<Map<String, String>>` | Agent selector |
| `currentModelFlow` | `MutableStateFlow<Map<String, Pair<String, String>>>` | Model selector |

### 3.4 FakeSettingsRepository (3 methods)

```kotlin
val settingsState = MutableStateFlow(AppSettings())
```

Tests override individual fields:
```kotlin
(fakeSettingsRepo as FakeSettingsRepository).settingsState.value = testSettings(chatDensity = ChatDensity.Compact)
```

### 3.5 Remaining Fakes (7 repositories, 3-7 methods each)

All follow the same pattern. Flow methods return `MutableStateFlow(emptyList())`. suspend methods return `Result.success(default)`. Total ~140 lines combined.

### 3.6 Non-Repository Dependencies

| Dependency | Strategy |
|------------|----------|
| SessionStateService | Real instance — driven by fake SessionRepository data |
| TokenStatsTracker | Real instance — reads from fake ChatRepository flows |
| ToolCardResolver | Real `DefaultToolCardResolver` |
| ToolSnapshotCache | Real instance (in-memory, no side effects) |
| HttpClient | Dummy OkHttp client (no network calls in tests) |
| SseClient | Real instance with dummy HttpClient (never connects) |
| ServerTerminalRegistry | Real instance (empty by default) |
| SessionFocusHolder | Real instance |
| AppNotificationManager | Real instance with fake NotificationManager |
| SessionScrollSignal | Real instance |

## 4. DI Module Replacement

### 4.1 FakeDomainModule

```kotlin
@TestInstallIn(component = SingletonComponent::class, replaces = [DomainModule::class])
@Module
@Suppress("unused")
abstract class FakeDomainModule {
    @Binds @Singleton abstract fun bindChat(impl: FakeChatRepository): ChatRepository
    @Binds @Singleton abstract fun bindSession(impl: FakeSessionRepository): SessionRepository
    @Binds @Singleton abstract fun bindSettings(impl: FakeSettingsRepository): SettingsRepository
    @Binds @Singleton abstract fun bindDraft(impl: FakeDraftRepository): DraftRepository
    @Binds @Singleton abstract fun bindAgent(impl: FakeAgentRepository): AgentRepository
    @Binds @Singleton abstract fun bindProvider(impl: FakeProviderRepository): ProviderRepository
    @Binds @Singleton abstract fun bindFile(impl: FakeFileRepository): FileRepository
    @Binds @Singleton abstract fun bindVcs(impl: FakeVcsRepository): VcsRepository
    @Binds @Singleton abstract fun bindTerminal(impl: FakeTerminalRepository): TerminalRepository
    @Binds @Singleton abstract fun bindMcp(impl: FakeMcpRepository): McpRepository
}
```

### 4.2 FakeApiModule

Replaces ApiModule. Provides stub API implementations that never make network calls.

### 4.3 FakeNetworkModule

Replaces NetworkModule. Provides a dummy `HttpClient` with OkHttp engine and dummy `DataStore`.

### 4.4 Test Access Pattern

Tests inject fakes via interface type, cast to concrete for configuration:

```kotlin
@HiltAndroidTest
class ChatScrollStabilityTest {
    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var settingsRepo: SettingsRepository

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @get:Rule val composeRule = createAndroidComposeRule<HiltTestRunner>()

    @Before fun init() {
        hiltRule.inject()
        composeRule.setContent { ChatScreen(serverId = "server-1", sessionId = "test-session") }
    }

    private val fakeChat get() = chatRepo as FakeChatRepository
    private val fakeSession get() = sessionRepo as FakeSessionRepository
    private val fakeSettings get() = settingsRepo as FakeSettingsRepository
}
```

## 5. Test Data Builders

### 5.1 Message Builders

```kotlin
fun aUserMessage(text: String, id: String = randomId()): Message

fun anAssistantMessage(
    streaming: Boolean = false,
    id: String = randomId(),
    error: String? = null,
    block: PartListBuilder.() -> Unit = {}
): MessageWithParts
```

### 5.2 Part DSL

```kotlin
class PartListBuilder {
    fun text(content: String)
    fun reasoning(content: String)
    fun tool(name: String, state: ToolState = ToolState.Running(...))
    fun toolCompleted(name: String, output: String)
    fun permission(question: String)
    fun question(text: String, options: List<String>)
    fun patch(oldText: String, newText: String)
    fun file(name: String, content: String)
    fun stepStart()
    fun stepFinish()
    fun abort()
    fun build(): List<Part>
}
```

### 5.3 Session & Settings Builders

```kotlin
fun aSession(
    id: String = randomId(),
    title: String = "Test Session",
    status: SessionStatus = SessionStatus.Idle,
    serverId: String = "server-1"
): Session

fun testSettings(
    chatDensity: ChatDensity = ChatDensity.Comfortable,
    collapseTools: Boolean = true,
    expandReasoning: Boolean = false,
    showTurnDividers: Boolean = true
): AppSettings
```

## 6. Test Scenarios (30 tests)

### 6.1 ChatScrollStabilityTest (7 tests)

| # | Test | Verifies | Prevents |
|---|------|----------|----------|
| 1 | streaming message grows, viewport stays at bottom | Compensation fires during growth | takeIf(sessionMeta.isStreaming) regression |
| 2 | user scrolls away, streaming grows, viewport stays put | Compensation keeps position when scrolled away | shouldCompensate logic |
| 3 | user returns to bottom, shouldCompensate resets | Dual-key self-healing | Single-key regression |
| 4 | streamingMsgId tracks last uncompleted assistant | streamingMsgId correctness | L152 derivation |
| 5 | streamingMsgId null when all completed | Boundary case | Edge case |
| 6 | autoScrollEnabled resets on non-drag return to bottom | Flung/SSE-pushed return resets | ChatScreen L340 dual-key |
| 7 | completed message height does not trigger compensation | Only streaming messages get compensation | Iron Law 2 |

### 6.2 ChatMessageRenderingTest (8 tests)

1. User message renders with correct styling
2. Streaming assistant shows pulsing indicator
3. Completed assistant without pulsing
4. Reasoning part renders in collapsible block
5. Tool part renders as expandable card
6. Error message renders with error styling
7. Turn dividers between user-assistant pairs
8. Empty session shows placeholder

### 6.3 ChatInteractionTest (10 tests)

1. Send message clears input and calls sendMessage
2. Permission dialog approve calls replyPermission
3. Permission dialog deny
4. Question dialog answer submits
5. Tool card expand toggles state
6. Scroll up triggers pagination
7. Undo calls undoRedo
8. Abort session calls abort API
9. Context usage bar shows token stats
10. Model selector shows available models

### 6.4 ChatInputTest (5 tests)

1. Typing updates draft text
2. Slash command autocomplete
3. File mention search shows results
4. Attachment add/remove
5. Send button disabled when input empty

## 7. Effort Estimate

| Component | Files | Lines |
|-----------|-------|-------|
| 10 Fake Repositories | 10 | ~600 |
| 3 DI replacement modules | 3 | ~110 |
| 4 Test data builders | 3 | ~250 |
| 4 Test files (30 tests) | 4 | ~800 |
| **Total** | **20** | **~1760** |

## 8. Dependencies & Prerequisites

### build.gradle.kts additions

No new dependencies needed — existing androidTest deps already include:
- `androidx.compose.ui:ui-test-junit4`
- `com.google.dagger:hilt-android-testing`
- `androidx.test.ext:junit`

### Test runner

Already configured: `HiltTestRunner` extends `AndroidJUnitRunner`.

### Emulator requirement

All tests run under `connectedAndroidTest` — requires emulator/device.

## 9. Out of Scope

- Phase 1 (ChatViewModel decomposition) — this spec only builds the test safety net
- Phase 2 (Composable splitting) — tests verify current behavior, refactoring is separate
- Unit tests for extracted pure functions — Phase 2 will handle that as part of extraction
- Performance/stress testing
- Real SSE/network integration testing
