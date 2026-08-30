package dev.leonardo.ocbeacon.ui.screens.workspace

import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.FileType
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.domain.usecase.FindFilesUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetVcsStatusUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListDirectoryUseCase
import dev.leonardo.ocbeacon.ui.navigation.routes.ServerRouteParams
import dev.leonardo.ocbeacon.ui.navigation.routes.WorkspaceNav
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

    // --- 真实测试数据（D7-003）---

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

    // ===== 测试 1：init 触发根加载 + git 预取 =====
    @Test
    fun `init triggers root load + git prefetch`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        coVerify { listDirectory(serverId, directory, "") }
        coVerify { getVcsStatus(serverId, directory) }
    }

    // ===== 测试 2：loadDirectory 成功 =====
    @Test
    fun `loadDirectory success`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        val state = vm.uiState.value
        assert(!state.rootLoading) { "rootLoading should be false after success" }
        assert(state.rootError == null) { "rootError should be null on success" }
        assert(state.rootNodes.isNotEmpty()) { "rootNodes should be populated" }
        // 目录优先，然后文件按名称小写排序
        val names = state.rootNodes.map { it.node.name }
        assert(names.first() == "src") { "First node should be directory 'src', was '${names.first()}'" }
    }

    // ===== 测试 3：loadDirectory 缓存命中 =====
    @Test
    fun `loadDirectory cache hit same path twice equals one API call`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        vm.loadDirectory("") // 第二次调用 —— 应命中缓存

        coVerify(exactly = 1) { listDirectory(serverId, directory, "") }
    }

    // ===== 测试 4：loadDirectory 失败设置 rootError =====
    @Test
    fun `loadDirectory failure sets rootError`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.failure(
            RuntimeException("Connection refused: port 4096")
        )
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        val state = vm.uiState.value
        assert(!state.rootLoading) { "rootLoading should be false after failure" }
        assert(state.rootError == R.string.workspace_error_load_failed) {
            "rootError should be load failed resource, was '${state.rootError}'"
        }
    }

    // ===== 测试 5：refreshRoot 清除缓存并重新加载 =====
    @Test
    fun `refreshRoot clears cache and reloads`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        vm.refreshRoot()

        // init + refresh = 2 次调用
        coVerify(exactly = 2) { listDirectory(serverId, directory, "") }
    }

    // ===== 测试 6：未加载时 switchPanel GIT 触发 getStatus =====
    @Test
    fun `switchPanel GIT triggers getStatus if not loaded`() = runTest {
        // 预取成功但 gitChanges 列表为空（预取只设置计数）
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(emptyList())

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        // 预取设置 gitChangeCount = 0，但 switchPanel 检查 gitChanges.isEmpty()
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)

        // init 预取 + switchPanel loadGitChanges = 2 次调用
        coVerify(exactly = 2) { getVcsStatus(serverId, directory) }
    }

    // ===== 测试 7：非 git 的 switchPanel GIT 设置 isNonGit =====
    @Test
    fun `switchPanel GIT non-git sets isNonGit`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        // 预取因非 git 消息而失败
        coEvery { getVcsStatus(serverId, directory) } returns Result.failure(
            RuntimeException("fatal: not a git repository (or any parent): .git")
        )

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        // 由于 gitChanges 为空且未在加载，switchPanel 触发 loadGitChanges
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)

        val state = vm.uiState.value
        assert(state.isNonGit) { "isNonGit should be true for 'not a git' error" }
    }

    // ===== 测试 8：switchPanel FILE_TREE 不重新加载 =====
    @Test
    fun `switchPanel FILE_TREE no reload`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        // 切换到 GIT 再切回 FILE_TREE
        vm.switchPanel(WorkspacePanel.GIT_CHANGES)
        vm.switchPanel(WorkspacePanel.FILE_TREE)

        // listDirectory 仍应只被调用一次（init 调用）
        coVerify(exactly = 1) { listDirectory(serverId, directory, "") }
    }

    // ===== 测试 9：toggleShowIgnored =====
    @Test
    fun `toggleShowIgnored`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        assert(!vm.uiState.value.showIgnored) { "showIgnored should default to false" }

        vm.toggleShowIgnored()
        assert(vm.uiState.value.showIgnored) { "showIgnored should be true after toggle" }

        vm.toggleShowIgnored()
        assert(!vm.uiState.value.showIgnored) { "showIgnored should be false after second toggle" }
    }

    // ===== 测试 10：git 预取失败使计数保持 null =====
    @Test
    fun `git prefetch failure leaves count null`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.failure(
            RuntimeException("Timeout after 30s")
        )

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        val state = vm.uiState.value
        assert(state.gitChangeCount == null) {
            "gitChangeCount should be null when prefetch fails, was ${state.gitChangeCount}"
        }
    }

    // ===== 测试 11：refreshRoot 期间 loadDirectory 取消过期任务 =====
    @Test
    fun `loadDirectory during refreshRoot cancels stale`() = runTest {
        // StandardTestDispatcher 使协程在 delay 处挂起，
        // 因此 refreshRoot 取消 "src" 任务时它仍在执行中。
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)
        try {
            var srcCompletedCount = 0
            coEvery { listDirectory(serverId, directory, "src") } coAnswers {
                delay(60_000L) // 模拟慢 API —— 协程在此挂起
                srcCompletedCount++
                Result.success(sampleFileNodes)
            }
            coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
            coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

            val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

            // "src" 任务启动但在 delay(60s) 处挂起
            vm.loadDirectory("src")
            // refreshRoot 取消所有 loadJobs（包括挂起的 "src" 任务）
            // 并清空 dirCache，然后重新启动 loadDirectory("")
            vm.refreshRoot()

            advanceUntilIdle()

            // "src" 任务在完成前被 refreshRoot 取消，
            // 因此其 onSuccess 回调从未执行。
            assert(srcCompletedCount == 0) {
                "src job should have been cancelled by refreshRoot, got $srcCompletedCount completions"
            }
            // refreshRoot 的根重载应成功
            assert(vm.uiState.value.rootNodes.isNotEmpty()) {
                "rootNodes should be populated after refreshRoot"
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ===== 测试 12：快速重复 loadDirectory 去抖 =====
    @Test
    fun `rapid duplicate loadDirectory debounced`() = runTest {
        // StandardTestDispatcher 使协程在 delay 处挂起，
        // 因此重复请求到达时第一个 "src" 任务仍在执行中。
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)
        try {
            var completedCount = 0
            coEvery { listDirectory(serverId, directory, "src") } coAnswers {
                delay(60_000L) // 模拟慢 API —— 协程在此挂起
                completedCount++
                Result.success(sampleFileNodes)
            }
            coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
            coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

            val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

            // 第一次调用启动任务，在 delay 处挂起
            vm.loadDirectory("src")
            // 第二次调用：loadJobs["src"]?.cancel() 取消第一个任务，
            // 然后启动替代任务
            vm.loadDirectory("src")

            advanceUntilIdle()

            // 只有第二个（未被取消的）任务应完成。
            // 如果没有 loadJobs[path]?.cancel()，两个任务都会完成（completedCount=2）。
            assert(completedCount == 1) {
                "Expected exactly 1 completion (second job survives cancel), got $completedCount"
            }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ===== 测试 13：空白 serverId 在未调用 useCase 时设置 rootError =====
    @Test
    fun `blank serverId sets rootError without calling useCase`() = runTest {
        val vm = WorkspaceViewModel(savedStateHandle(id = ""), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        val state = vm.uiState.value
        assert(state.rootError == R.string.workspace_error_server_config_missing) {
            "rootError should be server config missing resource, was '${state.rootError}'"
        }
        assert(!state.rootLoading) { "rootLoading should be false" }

        coVerify(exactly = 0) { listDirectory(any(), any(), any()) }
        coVerify(exactly = 0) { getVcsStatus(any(), any()) }
    }

    // ===== Phase 2：搜索测试 =====

    @Test
    fun `enterSearch sets isSearchMode true and clears query`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))

        vm.enterSearch()

        assert(vm.uiState.value.isSearchMode) { "isSearchMode should be true" }
        assert(vm.uiState.value.searchQuery.isEmpty()) { "searchQuery should be cleared" }
    }

    @Test
    fun `exitSearch clears search state and keeps panel data`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)
        coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("a.kt"))

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
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

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles, io.mockk.mockk(relaxed = true))
        // loadGitChanges 从 getVcsStatus mock 填充 uiState.gitChanges
        vm.loadGitChanges()

        val filtered = vm.filterGitChanges("main")

        assert(filtered.size == 1) { "expected 1 match for 'main', got ${filtered.size}" }
        assert(filtered[0].file == "src/main/kotlin/MainActivity.kt") {
            "expected MainActivity.kt, got ${filtered[0].file}"
        }
    }
}
