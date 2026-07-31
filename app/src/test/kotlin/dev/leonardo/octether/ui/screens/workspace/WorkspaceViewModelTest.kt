package dev.leonardo.octether.ui.screens.workspace

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.FileNode
import dev.leonardo.octether.domain.model.FileType
import dev.leonardo.octether.domain.model.VcsChange
import dev.leonardo.octether.domain.model.VcsStatus
import dev.leonardo.octether.domain.usecase.FindFilesUseCase
import dev.leonardo.octether.domain.usecase.GetVcsStatusUseCase
import dev.leonardo.octether.domain.usecase.ListDirectoryUseCase
import dev.leonardo.octether.ui.navigation.routes.ServerRouteParams
import dev.leonardo.octether.ui.navigation.routes.WorkspaceNav
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val listDirectory = mockk<ListDirectoryUseCase>()
    private val getVcsStatus = mockk<GetVcsStatusUseCase>()
    private val findFiles = mockk<FindFilesUseCase>()

    private val serverId = "srv-abc123"
    private val directory = "/home/user/project"
    private val encodedDirectory = URLEncoder.encode(directory, "UTF-8")

    private fun savedStateHandle(id: String = serverId, dir: String = encodedDirectory) =
        SavedStateHandle(
            mapOf(
                ServerRouteParams.PARAM_SERVER_ID to id,
                WorkspaceNav.PARAM_DIRECTORY to dir
            )
        )

    // --- Realistic test data (D7-003) ---

    private val sampleFileNodes = listOf(
        FileNode("src", "src", "/home/user/project/src", FileType.DIRECTORY, false),
        FileNode("build.gradle.kts", "build.gradle.kts", "/home/user/project/build.gradle.kts", FileType.FILE, false),
        FileNode("settings.gradle.kts", "settings.gradle.kts", "/home/user/project/settings.gradle.kts", FileType.FILE, false),
        FileNode("OpenCodeApi.kt", "OpenCodeApi.kt", "/home/user/project/OpenCodeApi.kt", FileType.FILE, false),
        FileNode("README.md", "README.md", "/home/user/project/README.md", FileType.FILE, false),
        FileNode(".gitignore", ".gitignore", "/home/user/project/.gitignore", FileType.FILE, true)
    )

    private val sampleGitChanges = listOf(
        VcsChange("src/main/kotlin/MainActivity.kt", 24, 3, VcsStatus.MODIFIED),
        VcsChange("build.gradle.kts", 5, 1, VcsStatus.MODIFIED),
        VcsChange("docs/api-reference.md", 42, 0, VcsStatus.ADDED),
        VcsChange("legacy/DeprecatedUtils.kt", 0, 88, VcsStatus.DELETED)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== Test 1: init triggers root load + git prefetch =====
    @Test
    fun `init triggers root load + git prefetch`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        coVerify { listDirectory(serverId, directory, "") }
        coVerify { getVcsStatus(serverId, directory) }
    }

    // ===== Test 2: loadDirectory success =====
    @Test
    fun `loadDirectory success`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        val state = vm.uiState.value
        assert(!state.rootLoading) { "rootLoading should be false after success" }
        assert(state.rootError == null) { "rootError should be null on success" }
        assert(state.rootNodes.isNotEmpty()) { "rootNodes should be populated" }
        // Directories first, then files sorted by name lowercase
        val names = state.rootNodes.map { it.node.name }
        assert(names.first() == "src") { "First node should be directory 'src', was '${names.first()}'" }
    }

    // ===== Test 3: loadDirectory cache hit =====
    @Test
    fun `loadDirectory cache hit same path twice equals one API call`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        vm.loadDirectory("") // second call — should hit cache

        coVerify(exactly = 1) { listDirectory(serverId, directory, "") }
    }

    // ===== Test 4: loadDirectory failure sets rootError =====
    @Test
    fun `loadDirectory failure sets rootError`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.failure(
            RuntimeException("Connection refused: port 4096")
        )
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        val state = vm.uiState.value
        assert(!state.rootLoading) { "rootLoading should be false after failure" }
        assert(state.rootError == R.string.workspace_error_load_failed) {
            "rootError should be load failed resource, was '${state.rootError}'"
        }
    }

    // ===== Test 5: refreshRoot clears cache + reloads =====
    @Test
    fun `refreshRoot clears cache and reloads`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        vm.refreshRoot()

        // init + refresh = 2 calls
        coVerify(exactly = 2) { listDirectory(serverId, directory, "") }
    }

    // ===== Test 6: switchPanel GIT triggers getStatus if not loaded =====
    @Test
    fun `switchPanel GIT triggers getStatus if not loaded`() = runTest {
        // Prefetch succeeded but gitChanges list is empty (prefetch only sets count)
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(emptyList())

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        // prefetch sets gitChangeCount = 0, but switchPanel checks gitChanges.isEmpty()
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)

        // init prefetch + switchPanel loadGitChanges = 2 calls
        coVerify(exactly = 2) { getVcsStatus(serverId, directory) }
    }

    // ===== Test 7: switchPanel GIT non-git sets isNonGit =====
    @Test
    fun `switchPanel GIT non-git sets isNonGit`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        // Prefetch fails with non-git message
        coEvery { getVcsStatus(serverId, directory) } returns Result.failure(
            RuntimeException("fatal: not a git repository (or any parent): .git")
        )

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        // switchPanel triggers loadGitChanges since gitChanges is empty and not loading
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)

        val state = vm.uiState.value
        assert(state.isNonGit) { "isNonGit should be true for 'not a git' error" }
    }

    // ===== Test 8: switchPanel FILE_TREE no reload =====
    @Test
    fun `switchPanel FILE_TREE no reload`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        // Switch to GIT then back to FILE_TREE
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)
        vm.switchPanel(WorkspacePanel.FILE_TREE)

        // listDirectory should still be called only once (init call)
        coVerify(exactly = 1) { listDirectory(serverId, directory, "") }
    }

    // ===== Test 9: toggleShowIgnored =====
    @Test
    fun `toggleShowIgnored`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        assert(!vm.uiState.value.showIgnored) { "showIgnored should default to false" }

        vm.toggleShowIgnored()
        assert(vm.uiState.value.showIgnored) { "showIgnored should be true after toggle" }

        vm.toggleShowIgnored()
        assert(!vm.uiState.value.showIgnored) { "showIgnored should be false after second toggle" }
    }

    // ===== Test 10: git prefetch failure leaves count null =====
    @Test
    fun `git prefetch failure leaves count null`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.failure(
            RuntimeException("Timeout after 30s")
        )

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        val state = vm.uiState.value
        assert(state.gitChangeCount == null) {
            "gitChangeCount should be null when prefetch fails, was ${state.gitChangeCount}"
        }
    }

    // ===== Test 11: loadDirectory during refreshRoot cancels stale =====
    @Test
    fun `loadDirectory during refreshRoot cancels stale`() = runTest {
        // StandardTestDispatcher makes coroutines suspend at delay points,
        // so the "src" job is still in-flight when refreshRoot cancels it.
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)
        try {
            var srcCompletedCount = 0
            coEvery { listDirectory(serverId, directory, "src") } coAnswers {
                delay(60_000L) // simulated slow API — coroutine suspends here
                srcCompletedCount++
                Result.success(sampleFileNodes)
            }
            coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
            coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

            val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

            // "src" job launches but is suspended at delay(60s)
            vm.loadDirectory("src")
            // refreshRoot cancels all loadJobs (including the suspended "src" job)
            // and clears dirCache, then re-launches loadDirectory("")
            vm.refreshRoot()

            advanceUntilIdle()

            // The "src" job was cancelled by refreshRoot before completing,
            // so its onSuccess callback never ran.
            assert(srcCompletedCount == 0) {
                "src job should have been cancelled by refreshRoot, got $srcCompletedCount completions"
            }
            // Root reload from refreshRoot should succeed
            assert(vm.uiState.value.rootNodes.isNotEmpty()) {
                "rootNodes should be populated after refreshRoot"
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ===== Test 12: rapid duplicate loadDirectory debounced =====
    @Test
    fun `rapid duplicate loadDirectory debounced`() = runTest {
        // StandardTestDispatcher makes coroutines suspend at delay points,
        // so the first "src" job is still in-flight when the duplicate arrives.
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)
        try {
            var completedCount = 0
            coEvery { listDirectory(serverId, directory, "src") } coAnswers {
                delay(60_000L) // simulated slow API — coroutine suspends here
                completedCount++
                Result.success(sampleFileNodes)
            }
            coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
            coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

            val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

            // First call launches job, suspended at delay
            vm.loadDirectory("src")
            // Second call: loadJobs["src"]?.cancel() cancels the first job,
            // then launches a replacement job
            vm.loadDirectory("src")

            advanceUntilIdle()

            // Only the second (non-cancelled) job should have completed.
            // Without loadJobs[path]?.cancel(), both jobs would complete (completedCount=2).
            assert(completedCount == 1) {
                "Expected exactly 1 completion (second job survives cancel), got $completedCount"
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ===== Test 13: blank serverId sets rootError without calling useCase =====
    @Test
    fun `blank serverId sets rootError without calling useCase`() = runTest {
        val vm = WorkspaceViewModel(savedStateHandle(id = ""), listDirectory, getVcsStatus, findFiles)

        val state = vm.uiState.value
        assert(state.rootError == R.string.workspace_error_server_config_missing) {
            "rootError should be server config missing resource, was '${state.rootError}'"
        }
        assert(!state.rootLoading) { "rootLoading should be false" }

        coVerify(exactly = 0) { listDirectory(any(), any(), any()) }
        coVerify(exactly = 0) { getVcsStatus(any(), any()) }
    }

    // ===== Phase 2: Search tests =====

    @Test
    fun `enterSearch sets isSearchMode true and clears query`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

        vm.enterSearch()

        assert(vm.uiState.value.isSearchMode) { "isSearchMode should be true" }
        assert(vm.uiState.value.searchQuery.isEmpty()) { "searchQuery should be cleared" }
    }

    @Test
    fun `exitSearch clears search state and keeps panel data`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("a.kt"))

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("test")
        advanceTimeBy(400)
        vm.exitSearch()

        assert(!vm.uiState.value.isSearchMode) { "isSearchMode should be false" }
        assert(vm.uiState.value.searchQuery.isEmpty()) { "searchQuery should be cleared" }
        assert(vm.uiState.value.fileSearchResults.isEmpty()) { "results should be cleared" }
        assert(!vm.uiState.value.hasSearched) { "hasSearched should be false" }
    }

    @Test
    fun `searchFiles with blank query does not call useCase`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("   ")
        advanceTimeBy(400)

        coVerify(exactly = 0) { findFiles(any(), any(), any(), any()) }
        assert(!vm.uiState.value.hasSearched) { "hasSearched should be false for blank query" }
    }

    @Test
    fun `searchFiles debounces 300ms before calling useCase`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("a.kt"))

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("User")
        advanceTimeBy(200)
        coVerify(exactly = 0) { findFiles(any(), any(), any(), any()) }
        advanceTimeBy(150)
        coVerify(exactly = 1) { findFiles(any(), any(), any(), any()) }
    }

    @Test
    fun `searchFiles success updates results and hasSearched`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        val paths = listOf("app/User.kt", "docs/user.md")
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(paths)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("User")
        advanceTimeBy(400)

        assert(vm.uiState.value.fileSearchResults == paths) { "results should match" }
        assert(vm.uiState.value.hasSearched) { "hasSearched should be true" }
        assert(!vm.uiState.value.searchLoading) { "searchLoading should be false" }
        assert(vm.uiState.value.searchError == null) { "searchError should be null" }
    }

    @Test
    fun `searchFiles failure sets searchError`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.failure(RuntimeException("503"))

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("User")
        advanceTimeBy(400)

        assert(vm.uiState.value.searchError != null) { "searchError should be set" }
        assert(vm.uiState.value.fileSearchResults.isEmpty()) { "results should be empty on failure" }
    }

    @Test
    fun `rapid query changes cancel previous search job`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("b"))

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        vm.enterSearch()
        vm.searchFiles("Us")
        advanceTimeBy(200)
        vm.searchFiles("User")
        advanceTimeBy(400)

        coVerify(exactly = 1) { findFiles(any(), any(), any(), any()) }
        assert(vm.uiState.value.fileSearchResults == listOf("b")) { "should have last query results" }
    }

    @Test
    fun `filterGitChanges filters loaded gitChanges by query case insensitive`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
        // loadGitChanges fills uiState.gitChanges from getVcsStatus mock
        vm.loadGitChanges()

        val filtered = vm.filterGitChanges("main")

        assert(filtered.size == 1) { "expected 1 match for 'main', got ${filtered.size}" }
        assert(filtered[0].file == "src/main/kotlin/MainActivity.kt") {
            "expected MainActivity.kt, got ${filtered[0].file}"
        }
    }
}
