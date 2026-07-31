package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import dev.leonardo.ocbeacon.domain.usecase.GetFileContentUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetFileDiffUseCase
import dev.leonardo.ocbeacon.domain.usecase.SubmitAnnotationsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val getFileContent = mockk<GetFileContentUseCase>()
    private val getFileDiff = mockk<GetFileDiffUseCase>()
    private val toolSnapshotCache = ToolSnapshotCache()
    private val submitAnnotations = mockk<SubmitAnnotationsUseCase>()

    private val serverId = "srv-abc123"
    private val directory = "/home/user/project"
    private val filePath = "src/main/kotlin/dev/minios/ocremote/MainActivity.kt"
    private val mdFilePath = "docs/README.md"

    private fun fileViewerParams(
        source: String = FileViewerSource.LIVE,
        path: String = filePath,
        dir: String = directory,
        toolPartIds: List<String> = emptyList()
    ) = FileViewerParams(
        serverId = serverId,
        sessionId = "session-123",
        filePath = path,
        directory = dir,
        source = source,
        toolPartIds = toolPartIds
    )

    // --- Realistic test data (D7-003) ---

    private val sampleKotlinSource = """
        package dev.leonardo.ocbeacon

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
            }
        }
    """.trimIndent()

    private val sampleFileContent = FileContent(
        path = filePath,
        type = ContentType.TEXT,
        content = sampleKotlinSource
    )

    private val sampleMarkdownContent = FileContent(
        path = mdFilePath,
        type = ContentType.TEXT,
        content = "# OC Remote\n\nUnofficial OpenCode Android client.\n\n## Features\n\n- File viewer\n- Markdown preview\n"
    )

    private val samplePatch = """
        @@ -10,6 +10,8 @@
         import dagger.hilt.android.lifecycle.HiltViewModel
         import dev.leonardo.ocbeacon.domain.model.Session
         import dev.leonardo.ocbeacon.domain.repository.SessionRepository
        +import kotlinx.coroutines.flow.MutableStateFlow
        +import kotlinx.coroutines.flow.StateFlow
         import javax.inject.Inject

        @@ -25,4 +27,6 @@
                 private val sessionRepository: SessionRepository
             ) : ViewModel() {

        -    val sessions = mutableStateOf(emptyList())
        +    private val _sessions = MutableStateFlow(emptyList<Session>())
        +    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
        +
                 init { loadSessions() }
    """.trimIndent()

    private val sampleDiffs = listOf(
        VcsFileDiff(
            file = filePath,
            patch = samplePatch,
            additions = 4,
            deletions = 1,
            status = dev.leonardo.ocbeacon.domain.model.VcsStatus.MODIFIED
        )
    )

    private val binaryContent = FileContent(
        path = "assets/logo.png",
        type = ContentType.BINARY,
        content = "",
        mimeType = "image/png"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. LIVE source success loads content
    @Test
    fun `LIVE source success loads content`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false after success" }
        assert(state.content == sampleKotlinSource) { "content should match" }
        assert(!state.isBinary) { "isBinary should be false for text" }
        assert(state.error == null) { "error should be null on success" }
    }

    // 2. GIT_DIFF source success parses hunks
    @Test
    fun `GIT_DIFF source success parses hunks`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            fileViewerParams(source = FileViewerSource.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.mode == FileViewerMode.DIFF) { "mode should be DIFF" }
        assert(state.hunks.isNotEmpty()) { "hunks should be parsed from patch" }
        assert(state.hunks.size == 2) { "expected 2 hunks, got ${state.hunks.size}" }
        assert(state.diff != null) { "diff should be set" }
    }

    // 3. TOOL_SNAPSHOT source without cache sets missing error
    @Test
    fun `TOOL_SNAPSHOT source without cache sets missing error`() = runTest {
        toolSnapshotCache.clear()
        val vm = FileViewerViewModel(
            fileViewerParams(source = FileViewerSource.TOOL_SNAPSHOT),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.error != null) { "error should be set for missing snapshot" }
        assert(state.error == R.string.fileviewer_error_tool_snapshot_missing) {
            "error should be tool snapshot missing resource, was: ${state.error}"
        }

        coVerify(exactly = 0) { getFileContent(any(), any(), any()) }
        coVerify(exactly = 0) { getFileDiff(any(), any(), any()) }
    }

    // 4. Load failure sets error
    @Test
    fun `load failure sets error`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.failure(
            RuntimeException("Connection refused: port 4096")
        )

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false after failure" }
        assert(state.error == R.string.workspace_error_load_failed) {
            "error should be load failed resource, was: ${state.error}"
        }
    }

    // 5. Binary file sets isBinary + mimeType
    @Test
    fun `binary file sets isBinary and mimeType`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(binaryContent)

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.isBinary) { "isBinary should be true" }
        assert(state.mimeType == "image/png") { "mimeType should be image/png" }
        assert(state.content.isEmpty()) { "content should be empty for binary" }
    }

    // 6. Empty content sets isEmpty
    @Test
    fun `empty content sets isEmpty`() = runTest {
        val emptyContent = FileContent(
            path = filePath,
            type = ContentType.TEXT,
            content = ""
        )
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(emptyContent)

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.isEmpty) { "isEmpty should be true for blank content" }
        assert(state.content.isEmpty()) { "content should be empty" }
    }

    // 7. Empty patch sets hunks empty
    @Test
    fun `empty patch sets hunks empty`() = runTest {
        val emptyPatchDiff = VcsFileDiff(
            file = filePath,
            patch = "",
            additions = 0,
            deletions = 0,
            status = dev.leonardo.ocbeacon.domain.model.VcsStatus.MODIFIED
        )
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(
            listOf(emptyPatchDiff)
        )

        val vm = FileViewerViewModel(
            fileViewerParams(source = FileViewerSource.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.hunks.isEmpty()) { "hunks should be empty for empty patch" }
        assert(state.isEmpty) { "isEmpty should be true when hunks are empty" }
    }

    // 8. nextHunk clamps at last index
    @Test
    fun `nextHunk clamps at last index`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            fileViewerParams(source = FileViewerSource.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val hunksCount = vm.uiState.value.hunks.size
        // Navigate to last hunk
        for (i in 0 until hunksCount) vm.nextHunk()
        assert(vm.uiState.value.currentHunkIndex == hunksCount - 1) {
            "currentHunkIndex should be last (${hunksCount - 1})"
        }
        // One more nextHunk should stay at last
        vm.nextHunk()
        assert(vm.uiState.value.currentHunkIndex == hunksCount - 1) {
            "currentHunkIndex should clamp at last (${hunksCount - 1})"
        }
    }

    // 9. prevHunk clamps at 0
    @Test
    fun `prevHunk clamps at 0`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            fileViewerParams(source = FileViewerSource.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        // currentHunkIndex starts at 0
        assert(vm.uiState.value.currentHunkIndex == 0) {
            "currentHunkIndex should start at 0"
        }
        // prevHunk should stay at 0
        vm.prevHunk()
        assert(vm.uiState.value.currentHunkIndex == 0) {
            "currentHunkIndex should clamp at 0, was ${vm.uiState.value.currentHunkIndex}"
        }
    }

    // ===== Phase 2: Markdown toggle tests =====

    // 10. init with md file sets isMarkdown true
    @Test
    fun `init with md file sets isMarkdown true and defaults to RENDER_PREVIEW`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            fileViewerParams(path = mdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.fileType == FileType.MARKDOWN) { "fileType should be MARKDOWN for .md" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "renderMode should default to RENDER_PREVIEW for renderable types" }
    }

    // 11. init with kt file sets isMarkdown false
    @Test
    fun `init with kt file sets isMarkdown false`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        assert(vm.uiState.value.fileType == FileType.TEXT) { "fileType should be TEXT for .kt" }
    }

    // 12. toggleRenderMode switches RENDER_PREVIEW to SOURCE (default is now RENDER_PREVIEW)
    @Test
    fun `toggleRenderMode switches RENDER_PREVIEW to SOURCE for markdown files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            fileViewerParams(path = mdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should be SOURCE after toggle from RENDER_PREVIEW"
        }
    }

    // 13. toggleRenderMode no-op for TEXT (non-renderable)
    @Test
    fun `toggleRenderMode is no-op for TEXT files`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should stay SOURCE for non-md"
        }
    }

    // 14. toggleRenderMode no-op in DIFF mode
    @Test
    fun `toggleRenderMode is no-op in DIFF mode`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            fileViewerParams(path = mdFilePath, source = FileViewerSource.GIT_DIFF),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should stay SOURCE in DIFF mode"
        }
    }

    // 15. toggleRenderMode toggles from default RENDER_PREVIEW to SOURCE
    @Test
    fun `toggleRenderMode back from RENDER_PREVIEW to SOURCE`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            fileViewerParams(path = mdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        // Default is RENDER_PREVIEW for markdown, one toggle → SOURCE
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should be SOURCE after toggle from RENDER_PREVIEW"
        }
    }

    // ===== Phase 2 Task 9: Tool snapshot tests =====

    // 16. TOOL_SNAPSHOT loads content from cache + clears on onCleared
    @Test
    fun `TOOL_SNAPSHOT source loads content from cache and clears on cleared`() = runTest {
        toolSnapshotCache.clear()
        toolSnapshotCache.put(
            "part-1",
            ToolSnapshotCache.Snapshot(
                filePath = "app/Main.kt", content = "class Main",
                before = null, after = null, toolName = "read"
            )
        )

        val vm = FileViewerViewModel(
            fileViewerParams(
                path = "app/Main.kt",
                source = FileViewerSource.TOOL_SNAPSHOT,
                toolPartIds = listOf("part-1")
            ),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.isToolSnapshot) { "isToolSnapshot should be true" }
        assert(vm.uiState.value.content == "class Main") { "content should match snapshot" }
        assert(!vm.uiState.value.isLoading) { "isLoading should be false" }

        vm.cleanupToolSnapshots()
        assert(toolSnapshotCache.get("part-1") == null) { "cache should be cleared on cleanup" }
    }

    // 17. TOOL_SNAPSHOT_DIFF fetches full file content from server (not just edited fragment)
    @Test
    fun `TOOL_SNAPSHOT_DIFF shows final edited content in SOURCE mode`() = runTest {
        toolSnapshotCache.clear()
        toolSnapshotCache.putAll(
            mapOf(
                "p1" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1\nline2\n", "line1-mod\nline2\n", "edit"),
                "p2" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1-mod\nline2\n", "line1-mod\nline2-new\n", "edit")
            )
        )

        // Edit tools now fetch the full file from server (snapshot only has the fragment)
        val fullFile = "line1-mod\nline2-new\nline3\nline4\n"
        coEvery { getFileContent(serverId, directory, "app/X.kt") } returns Result.success(
            FileContent(path = "app/X.kt", type = ContentType.TEXT, content = fullFile)
        )

        val vm = FileViewerViewModel(
            fileViewerParams(
                path = "app/X.kt",
                source = FileViewerSource.TOOL_SNAPSHOT_DIFF,
                toolPartIds = listOf("p1", "p2")
            ),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.mode == FileViewerMode.SOURCE) { "mode should be SOURCE (not DIFF)" }
        assert(vm.uiState.value.isToolSnapshot) { "isToolSnapshot should be true" }
        assert(vm.uiState.value.toolSnapshotBefore == "line1\nline2\n") {
            "toolSnapshotBefore should be first part's before"
        }
        assert(vm.uiState.value.toolSnapshotAfter == "line1-mod\nline2-new\n") {
            "toolSnapshotAfter should be last part's after"
        }
        // Content should be the full file from server (includes lines beyond the edited fragment)
        assert(vm.uiState.value.content.contains("line3")) { "content should show full file (line3)" }
        assert(vm.uiState.value.content.contains("line4")) { "content should show full file (line4)" }
    }

    // ===== Phase 3: Annotation tests =====

    @Test
    fun `addAnnotation creates annotation and updates state`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val pos = sampleKotlinSource.indexOf("import android.os.Bundle")
        vm.addAnnotation("import android.os.Bundle", pos, pos + 25, "use alias")
        assertEquals(1, vm.uiState.value.annotations.size)
        assertEquals(0, vm.uiState.value.annotations[0].index)
    }

    @Test
    fun `addAnnotation in DIFF mode is ignored`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)
        val vm = FileViewerViewModel(fileViewerParams(source = FileViewerSource.GIT_DIFF), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("text", 0, 4, "note")
        assertTrue(vm.uiState.value.annotations.isEmpty())
    }

    @Test
    fun `deleteAnnotation removes and renumbers`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        vm.addAnnotation("class", 10, 15, "n2")
        vm.addAnnotation("override", 20, 28, "n3")
        val firstId = vm.uiState.value.annotations[0].id
        vm.deleteAnnotation(firstId)
        val anns = vm.uiState.value.annotations
        assertEquals(2, anns.size)
        assertEquals(0, anns[0].index)
        assertEquals(1, anns[1].index)
    }

    @Test
    fun `submitAnnotations calls use case and clears on success`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        val result = vm.submitAnnotations("overall note")
        assertTrue(result.isSuccess)
        assertTrue(vm.uiState.value.annotations.isEmpty())
    }

    @Test
    fun `submitAnnotations failure does not clear`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("fail"))
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        vm.submitAnnotations("note")
        assertEquals(1, vm.uiState.value.annotations.size)
    }

    // ===== Phase 4: Pagination tests =====

    @Test
    fun `loadLive paginates content - initial visibleLineCount is 500 for large files`() = runTest {
        val largeContent = buildString {
            append("package dev.leonardo.ocbeacon\n\n")
            append("class LargeFile {\n")
            for (i in 1..2000) append("    fun method$i(): Int = $i\n")
            append("}\n")
        }
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            FileContent(path = filePath, type = ContentType.TEXT, content = largeContent)
        )
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val total = largeContent.count { it == '\n' } + if (largeContent.endsWith('\n')) 0 else 1
        assertEquals(500, vm.uiState.value.visibleLineCount)
        assertEquals(total, vm.uiState.value.totalLineCount)
        assertEquals(false, vm.uiState.value.isFullyLoaded)
        assertEquals(false, vm.uiState.value.isExtremelyLarge)
    }

    @Test
    fun `loadLive marks isExtremelyLarge for files over 100000 lines`() = runTest {
        val hugeContent = (1..100001).joinToString("\n") { "line $it" }
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            FileContent(path = filePath, type = ContentType.TEXT, content = hugeContent)
        )
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        assertEquals(true, vm.uiState.value.isExtremelyLarge)
        assertEquals(10000, vm.uiState.value.visibleLineCount)
    }

    @Test
    fun `loadLive for small file sets isFullyLoaded true and visibleLineCount equals total`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val total = sampleKotlinSource.count { it == '\n' } + if (sampleKotlinSource.endsWith('\n')) 0 else 1
        assertEquals(total, vm.uiState.value.visibleLineCount)
        assertEquals(true, vm.uiState.value.isFullyLoaded)
    }

    @Test
    fun `loadMoreLines increases visibleLineCount by 500 and respects totalLineCount`() = runTest {
        val largeContent = buildString {
            append("package dev.leonardo.ocbeacon\n\n")
            append("class LargeFile {\n")
            for (i in 1..2000) append("    fun method$i(): Int = $i\n")
            append("}\n")
        }
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            FileContent(path = filePath, type = ContentType.TEXT, content = largeContent)
        )
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val initialVisible = vm.uiState.value.visibleLineCount
        vm.loadMoreLines()
        assertEquals(initialVisible + 500, vm.uiState.value.visibleLineCount)
        assertEquals(false, vm.uiState.value.isFullyLoaded)
    }

    @Test
    fun `loadMoreLines clamps to totalLineCount and sets isFullyLoaded`() = runTest {
        val mediumContent = (1..600).joinToString("\n") { "line $it" }
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            FileContent(path = filePath, type = ContentType.TEXT, content = mediumContent)
        )
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.loadMoreLines()
        assertEquals(600, vm.uiState.value.visibleLineCount)
        assertEquals(true, vm.uiState.value.isFullyLoaded)
    }

    @Test
    fun `loadMoreLines is no-op when isFullyLoaded`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val before = vm.uiState.value.visibleLineCount
        vm.loadMoreLines()
        assertEquals(before, vm.uiState.value.visibleLineCount)
    }

    @Test
    fun `loadMoreLines for extremely large file starts at 10000 not 500`() = runTest {
        val hugeContent = (1..150000).joinToString("\n") { "line $it" }
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            FileContent(path = filePath, type = ContentType.TEXT, content = hugeContent)
        )
        val vm = FileViewerViewModel(fileViewerParams(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        assertEquals(10000, vm.uiState.value.visibleLineCount)
        vm.loadMoreLines()
        assertEquals(10500, vm.uiState.value.visibleLineCount)
    }

    // ===== Multi-format render tests =====

    @Test
    fun `init with json file sets fileType JSON`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "config.json",
                type = ContentType.TEXT,
                content = "{\"key\":\"value\"}"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "config.json"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.JSON) { "fileType should be JSON" }
    }

    @Test
    fun `toggleRenderMode is no-op for JSON files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "config.json",
                type = ContentType.TEXT,
                content = "{\"key\":\"value\"}"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "config.json"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "JSON should stay SOURCE (CodeWebView already has syntax highlight)"
        }
    }

    @Test
    fun `init with png binary sets fileType IMAGE and retains base64 content`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "photo.png",
                type = ContentType.BINARY,
                content = "iVBORw0KGgo=",
                mimeType = "image/png"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "photo.png"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.IMAGE) { "fileType should be IMAGE" }
        assert(!vm.uiState.value.isBinary) { "IMAGE should not be marked isBinary" }
        assert(vm.uiState.value.content == "iVBORw0KGgo=") { "base64 content should be retained" }
        assert(vm.uiState.value.mimeType == "image/png") { "mimeType should be preserved" }
    }

    @Test
    fun `toggleRenderMode works for IMAGE files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "photo.png",
                type = ContentType.BINARY,
                content = "iVBORw0KGgo=",
                mimeType = "image/png"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "photo.png"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "IMAGE should default to RENDER_PREVIEW" }
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "toggle should switch to SOURCE" }
    }

    @Test
    fun `toggleRenderMode works for CSV files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "data.csv",
                type = ContentType.TEXT,
                content = "a,b\n1,2"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "data.csv"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.CSV)
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "CSV should default to RENDER_PREVIEW" }
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "toggle should switch to SOURCE" }
    }

    // ===== Task 3: HTML + PDF tests =====

    @Test
    fun `init with html file sets fileType HTML and renderMode RENDER_PREVIEW`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "index.html",
                type = ContentType.TEXT,
                content = "<!DOCTYPE html><html><body><h1>Hello</h1></body></html>"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "index.html"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.HTML) { "fileType should be HTML" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "HTML should default to RENDER_PREVIEW (supportsRender=true)" }
    }

    @Test
    fun `toggleRenderMode works for HTML files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "index.html",
                type = ContentType.TEXT,
                content = "<!DOCTYPE html><html></html>"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "index.html"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "HTML defaults to RENDER_PREVIEW" }
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "toggle to SOURCE" }
    }

    @Test
    fun `init with pdf binary sets fileType PDF and retains base64 content`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "report.pdf",
                type = ContentType.BINARY,
                content = "JVBERi0xLjcKJeLjz9MK",
                mimeType = "application/pdf"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "report.pdf"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        assert(vm.uiState.value.fileType == FileType.PDF) { "fileType should be PDF" }
        assert(!vm.uiState.value.isBinary) { "PDF should not be marked isBinary" }
        assert(vm.uiState.value.content == "JVBERi0xLjcKJeLjz9MK") { "base64 content should be retained" }
        assert(vm.uiState.value.mimeType == "application/pdf") { "mimeType should be preserved" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) { "PDF should default to RENDER_PREVIEW" }
    }

    @Test
    fun `toggleRenderMode is no-op for PDF files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(
            dev.leonardo.ocbeacon.domain.model.FileContent(
                path = "report.pdf",
                type = ContentType.BINARY,
                content = "JVBERi0xLjcKJeLjz9MK",
                mimeType = "application/pdf"
            )
        )
        val vm = FileViewerViewModel(
            fileViewerParams(path = "report.pdf"),
            getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations
        )
        val modeBefore = vm.uiState.value.renderMode
        vm.toggleRenderMode()
        assert(vm.uiState.value.renderMode == modeBefore) { "PDF toggle should be no-op" }
    }
}
