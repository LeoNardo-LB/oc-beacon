package dev.leonardo.ocbeacon.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffParserTest {

    private val parser = DiffParser()

    // 1. Empty patch returns empty list
    @Test
    fun emptyPatchReturnsEmptyList() {
        val result = parser.parseUnifiedDiff("")
        assertTrue(result.isEmpty())
    }

    // 2. Single hunk parsed correctly
    @Test
    fun singleHunkParsedCorrectly() {
        val patch = """
            @@ -10,3 +10,4 @@
             existing line
            +import kotlinx.serialization.SerialName
             existing line 2
        """.trimIndent()

        val hunks = parser.parseUnifiedDiff(patch)
        assertEquals(1, hunks.size)
        val hunk = hunks[0]
        assertEquals(10, hunk.startLine)
        assertEquals(DiffHunkType.ADDED, hunk.type)
        assertTrue(hunk.patchStartLineIndex >= 0)
        assertTrue(hunk.rawPatch.contains("@@"))
    }

    // 3. Multiple hunks parsed
    @Test
    fun multipleHunksParsed() {
        val patch = """
            @@ -1,3 +1,3 @@
             package com.example
            -import android.os.Bundle
            +import androidx.appcompat.app.AppCompatActivity
             class MainActivity

            @@ -20,2 +20,3 @@
                 setContentView(R.layout.activity_main)
            +        setSupportActionBar(toolbar)
                 viewModel.observe(this) {
        """.trimIndent()

        val hunks = parser.parseUnifiedDiff(patch)
        assertEquals(2, hunks.size)
        assertEquals(1, hunks[0].startLine)
        assertEquals(20, hunks[1].startLine)
        assertEquals(DiffHunkType.MODIFIED, hunks[0].type)
        assertEquals(DiffHunkType.ADDED, hunks[1].type)
    }

    // 4. Real project git diff sample
    @Test
    fun realProjectGitDiffSample() {
        val patch = """
            diff --git a/app/src/main/java/com/app/SessionManager.kt b/app/src/main/java/com/app/SessionManager.kt
            index abc1234..def5678 100644
            --- a/app/src/main/java/com/app/SessionManager.kt
            +++ b/app/src/main/java/com/app/SessionManager.kt
            @@ -15,6 +15,7 @@
             import dagger.hilt.android.lifecycle.HiltViewModel
             import dev.leonardo.ocbeacon.domain.model.Session
             import dev.leonardo.ocbeacon.domain.repository.SessionRepository
            +import kotlinx.coroutines.flow.MutableStateFlow
             import javax.inject.Inject

            @@ -42,8 +43,10 @@
                 private val sessionRepository: SessionRepository
             ) : ViewModel() {

            -    private val _sessions = mutableStateOf<List<Session>>(emptyList())
            -    val sessions: State<List<Session>> = _sessions
            +    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
            +    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
            +
            +    private var loadJob: Job? = null

                 init {
                     loadSessions()
            @@ -55,7 +58,9 @@
                     private val repository: ChatRepository
                 ) : ViewModel() {

            -        fun sendMessage(text: String) {
            +        fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
            +            val parts = buildMessageParts(text, attachments)
            +            viewModelScope.launch { repository.send(parts) }
                 }
                 }
        """.trimIndent()

        val hunks = parser.parseUnifiedDiff(patch)
        assertEquals(3, hunks.size)
        // First hunk: single addition → ADDED
        assertEquals(DiffHunkType.ADDED, hunks[0].type)
        assertEquals(15, hunks[0].startLine)
        // Second hunk: has both add and remove → MODIFIED
        assertEquals(DiffHunkType.MODIFIED, hunks[1].type)
        assertEquals(43, hunks[1].startLine)
        // Third hunk: has both add and remove → MODIFIED
        assertEquals(DiffHunkType.MODIFIED, hunks[2].type)
        assertEquals(58, hunks[2].startLine)
    }

    // 5. Malformed patch without @@ returns empty
    @Test
    fun malformedPatchWithoutHeaderReturnsEmpty() {
        val patch = """
            some random text
            -removed line
            +added line
            more random text
        """.trimIndent()

        val hunks = parser.parseUnifiedDiff(patch)
        assertTrue(hunks.isEmpty())
    }

    // 6. Binary diff line returns empty
    @Test
    fun binaryDiffLineReturnsEmpty() {
        val patch = "Binary files a/image.png and b/image.png differ"
        val hunks = parser.parseUnifiedDiff(patch)
        assertTrue(hunks.isEmpty())
    }

    // 7. Mixed add/remove hunk → MODIFIED (D4-004 critical test)
    @Test
    fun mixedAddRemoveHunkParsedAsModified() {
        val patch = """
            @@ -25,5 +25,6 @@
                 private val context: Context,
            -    private val oldParser: LegacyParser,
            -    private val legacyAdapter: LegacyAdapter,
            +    private val newParser: ModernParser,
            +    private val modernAdapter: ModernAdapter,
                 private val repository: FileRepository
            """.trimIndent()

        val hunks = parser.parseUnifiedDiff(patch)
        assertEquals(1, hunks.size)
        // D4-004: both added and removed lines → MODIFIED, not ADDED or REMOVED
        assertEquals(DiffHunkType.MODIFIED, hunks[0].type)
    }

    // 8. CRLF patch parsed correctly
    @Test
    fun crlfPatchParsedCorrectly() {
        // Simulate CRLF line endings
        val lines = listOf(
            "@@ -5,3 +5,4 @@\r",
            " import kotlinx.coroutines.Dispatchers\r",
            "+import kotlinx.coroutines.flow.first\r",
            " class RepositoryImpl\r"
        )
        val patch = lines.joinToString("\n")

        val hunks = parser.parseUnifiedDiff(patch)
        assertEquals(1, hunks.size)
        assertEquals(5, hunks[0].startLine)
        assertEquals(DiffHunkType.ADDED, hunks[0].type)
    }
}
