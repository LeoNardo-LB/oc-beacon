package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.logging.AppLogger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import dev.leonardo.ocbeacon.domain.usecase.GetFileContentUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetFileDiffUseCase
import dev.leonardo.ocbeacon.domain.usecase.SubmitAnnotationsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "FileViewerDiag"

class FileViewerViewModel @AssistedInject constructor(
    @Assisted private val params: FileViewerParams,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase,
    private val toolSnapshotCache: ToolSnapshotCache,
    private val submitAnnotationsUseCase: SubmitAnnotationsUseCase
) : ViewModel() {
    private val serverId = params.serverId
    private val directory = params.directory
    private val filePath = params.filePath
    private val source = params.source
    private val sessionId = params.sessionId
    private val toolPartIds = params.toolPartIds
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath, directory = directory))
    val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()
    private val diffParser = DiffParser()
    private var annotationManager: AnnotationManager? = null

    // Phase 4：分页 — 缓存完整内容供 loadMore 切片使用
    private var fullContentCache: String = ""

    // #115（D2-L23）：批注进程级保存——overlay 的 ViewModelStoreOwner 无
    // SavedStateRegistry（remember 创建），语言切换/进程重建时 VM 重建丢批注。
    // 用静态 map 按 (serverId,filePath) 保存，VM 重建后 restore；
    // 提交成功或 clear 时移除。批注是纯数据类，静态持有安全（无 Activity 引用）。

    @AssistedFactory
    interface Factory {
        fun create(params: FileViewerParams): FileViewerViewModel
    }

    internal companion object {
        const val INITIAL_PAGE_SIZE = 500
        const val PAGE_SIZE = 500
        const val EXTREMELY_LARGE_THRESHOLD = 100_000
        const val EXTREMELY_LARGE_INITIAL = 10_000

        // #115（D2-L23）：进程级批注暂存（key = serverId + filePath）——
        // overlay VM 无 SavedStateRegistry，语言切换/进程重建时 VM 重建丢批注。
        // 值带时间戳：仅重建窗口（RECREATE_GRACE_MS）内恢复——防陈旧批注
        // 在"用户关闭后重新打开同一文件"或测试残留时错误恢复。
        private const val RECREATE_GRACE_MS = 30_000L
        private val annotationsHolder = java.util.concurrent.ConcurrentHashMap<String, AnnotationHolder>()

        private data class AnnotationHolder(
            val annotations: List<dev.leonardo.ocbeacon.domain.model.Annotation>,
            val savedAt: Long,
        )

        private fun holderKey(serverId: String, filePath: String): String =
            serverId + "\u0000" + filePath

        /** 测试隔离：清空进程级暂存（测试 tearDown 调用，防跨测试残留）。 */
        internal fun clearAnnotationsHolderForTest() {
            annotationsHolder.clear()
        }
    }

    init {
        AppLogger.d(TAG, "init: source=$source, file=${filePath.take(60)}, " +
            "serverId=${serverId.take(8)}, dir=${directory.take(40)}, " +
            "toolPartIds=${toolPartIds.map { it.take(12) }}")
        when (source) {
            FileViewerSource.LIVE -> loadLive()
            FileViewerSource.GIT_DIFF -> loadGitDiff()
            FileViewerSource.TOOL_SNAPSHOT -> loadToolSnapshot()
            FileViewerSource.TOOL_SNAPSHOT_DIFF -> loadToolSnapshotDiff()
        }
    }

    private fun loadLive() {
        val t0 = System.currentTimeMillis()
        AppLogger.d(TAG, "loadLive: requesting getFileContent(server=${serverId.take(8)}, " +
            "dir=${directory.take(40)}, file=${filePath.take(60)})")
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    AppLogger.d(TAG, "loadLive: SUCCESS in ${System.currentTimeMillis() - t0}ms, " +
                        "type=${c.type}, contentLen=${c.content.length}, " +
                        "mimeType=${c.mimeType}")
                    if (c.type == ContentType.BINARY) {
                        val ft = FileType.fromExtension(filePath)
                        when (ft) {
                            FileType.IMAGE -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = false, fileType = ft, content = c.content, mimeType = c.mimeType, renderMode = FileViewerRenderMode.RENDER_PREVIEW) }
                            }
                            FileType.PDF -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = false, fileType = ft, content = c.content, mimeType = c.mimeType, renderMode = FileViewerRenderMode.RENDER_PREVIEW) }
                            }
                            else -> {
                                _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                            }
                        }
                    }
                    else {
                        val ft = FileType.fromExtension(filePath)
                        // OpenCode 服务器可能把 PDF 当作 TEXT 返回（type="text"），
                        // 内容是原始 PDF 文本而非 base64。用 ISO-8859-1 无损转回字节再 base64 编码。
                        if (ft == FileType.PDF) {
                            val base64Content = android.util.Base64.encodeToString(
                                c.content.toByteArray(Charsets.ISO_8859_1),
                                android.util.Base64.NO_WRAP
                            )
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    fileType = ft,
                                    content = base64Content,
                                    mimeType = "application/pdf",
                                    renderMode = FileViewerRenderMode.RENDER_PREVIEW
                                )
                            }
                            return@launch
                        }
                        fullContentCache = c.content
                        resetSlice()
                        val totalLines = if (c.content.isEmpty()) 0
                                         else c.content.count { it == '\n' } + if (c.content.endsWith('\n')) 0 else 1
                        val extremelyLarge = totalLines > EXTREMELY_LARGE_THRESHOLD
                        val initialVisible = if (extremelyLarge) EXTREMELY_LARGE_INITIAL
                                             else minOf(totalLines, INITIAL_PAGE_SIZE)
                        val visible = takeFirstLines(c.content, initialVisible)
                        // AnnotationManager 使用完整内容，保证 loadMore 后行号正确
                        annotationManager = AnnotationManager(fullContentCache).also { manager ->
                            // #115（D2-L23）：恢复进程级暂存的批注（语言切换/重建后不丢）
                            annotationsHolder[holderKey(serverId, filePath)]?.let { h ->
                                if (System.currentTimeMillis() - h.savedAt <= RECREATE_GRACE_MS) {
                                    manager.restore(h.annotations)
                                }
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                mode = FileViewerMode.SOURCE,
                                content = visible,
                                isEmpty = visible.isBlank(),
                                fileType = FileType.fromExtension(filePath),
                                renderMode = defaultRenderMode(filePath),
                                totalLineCount = totalLines,
                                visibleLineCount = initialVisible,
                                isFullyLoaded = initialVisible >= totalLines,
                                isExtremelyLarge = extremelyLarge,
                                annotations = annotationManager?.getAll() ?: emptyList()
                            )
                        }
                    }
                }
                .onFailure { e ->
                    if (e !is CancellationException) AppLogger.e(TAG, "loadLive: FAILURE in ${System.currentTimeMillis() - t0}ms", e)
                    _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) }
                }
        }
    }

    /**
     * Phase 4：把 PAGE_SIZE 多行追加到可见内容。已全部加载时为 no-op。
     * 从 [fullContentCache] 切片 — 开销小，无网络往返。
     */
    fun loadMoreLines() {
        val current = _uiState.value
        if (current.isFullyLoaded) return
        val newSize = (current.visibleLineCount + PAGE_SIZE).coerceAtMost(current.totalLineCount)
        val newContent = takeFirstLines(fullContentCache, newSize)
        _uiState.update {
            it.copy(
                content = newContent,
                visibleLineCount = newSize,
                isFullyLoaded = newSize >= it.totalLineCount
            )
        }
    }

    /** 上次增量切片状态（#101 M-12：避免每次 loadMoreLines 从头重扫全文件 O(k·n)）。 */
    private var sliceLineCount = 0
    private var sliceEndOffset = 0

    /** 新文件/新内容加载前重置增量切片状态。 */
    private fun resetSlice() {
        sliceLineCount = 0
        sliceEndOffset = 0
    }

    /** 返回 [content] 的前 [lineCount] 行（包含最后一行的尾随换行符）。
     *  #101（M-12）：增量切片——从上次扫描位置继续（loadMoreLines 只扫新增行，
     *  原实现每次从头重扫 → 20 万行翻 10 页 = 10 次全扫）。 */
    private fun takeFirstLines(content: String, lineCount: Int): String {
        if (lineCount <= 0 || content.isEmpty()) return ""
        if (lineCount < sliceLineCount) {
            // 倒退（新内容/新文件加载）——重置后重扫
            sliceLineCount = 0
            sliceEndOffset = 0
        }
        var seen = sliceLineCount
        var idx = sliceEndOffset
        while (idx < content.length && seen < lineCount) {
            idx = content.indexOf('\n', idx)
            if (idx < 0) {
                idx = content.length
                break
            }
            idx++
            seen++
        }
        sliceLineCount = seen
        sliceEndOffset = idx
        return content.substring(0, idx)
    }

    private fun loadGitDiff() {
        viewModelScope.launch {
            getFileDiff(serverId, directory, VcsDiffMode.GIT)
                .onSuccess { diffs ->
                    val target = diffs.find { it.file == filePath || it.file.endsWith(filePath) }
                    val hunks = target?.patch?.let { diffParser.parseUnifiedDiff(it) } ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, mode = FileViewerMode.DIFF, diff = target, hunks = hunks,
                        hasDiff = target != null && hunks.isNotEmpty(), currentHunkIndex = 0, isEmpty = hunks.isEmpty()) }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    fun nextHunk() {
        // #137（D2-L31）：空 hunks 时 size-1 = -1 → coerceAtMost(-1) 把索引钳到 -1
        // （无 diff 的文件点"下一个 hunk"会得到非法索引）；空列表时保持不动。
        if (_uiState.value.hunks.isEmpty()) return
        _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex + 1).coerceAtMost(it.hunks.size - 1)) }
    }
    fun prevHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex - 1).coerceAtLeast(0)) } }

    // ============ Phase 3：批注管理 ============

    fun addAnnotation(selectedText: String, startChar: Int, endChar: Int, note: String) {
        val manager = annotationManager ?: return
        if (_uiState.value.mode != FileViewerMode.SOURCE) return
        manager.add(selectedText, startChar, endChar, note)
        val all = manager.getAll()
        annotationsHolder[holderKey(serverId, filePath)] = AnnotationHolder(all, System.currentTimeMillis())
        _uiState.update { it.copy(annotations = all) }
    }

    fun deleteAnnotation(id: String) {
        val manager = annotationManager ?: return
        manager.delete(id)
        val all = manager.getAll()
        annotationsHolder[holderKey(serverId, filePath)] = AnnotationHolder(all, System.currentTimeMillis())
        _uiState.update { it.copy(annotations = all) }
    }

    fun updateAnnotation(id: String, note: String) {
        val manager = annotationManager ?: return
        manager.update(id, note)
        val all = manager.getAll()
        annotationsHolder[holderKey(serverId, filePath)] = AnnotationHolder(all, System.currentTimeMillis())
        _uiState.update { it.copy(annotations = all) }
    }

    suspend fun submitAnnotations(overallNote: String, editedNotes: Map<String, String> = emptyMap()): Result<Unit> {
        val manager = annotationManager ?: return Result.failure(IllegalStateException("No annotation manager"))
        // 提交前应用所有已编辑的说明
        editedNotes.forEach { (id, newNote) -> manager.update(id, newNote) }
        val anns = manager.getAll()
        if (anns.isEmpty()) return Result.failure(IllegalStateException("No annotations to submit"))
        val result = submitAnnotationsUseCase(serverId, sessionId, anns, overallNote, filePath, directory)
        if (result.isSuccess) {
            manager.clear()
            annotationManager = null
            fullContentCache = ""
            // #115（D2-L23）：提交成功——移除暂存，防下次打开残留
            annotationsHolder.remove(holderKey(serverId, filePath))
            _uiState.update { it.copy(annotations = emptyList(), content = "", isEmpty = true) }
        }
        return result
    }

    // ============ Phase 2：多格式渲染切换 ============

    private fun defaultRenderMode(path: String): FileViewerRenderMode =
        if (FileType.fromExtension(path).supportsRender) FileViewerRenderMode.RENDER_PREVIEW
        else FileViewerRenderMode.SOURCE

    fun toggleRenderMode() {
        val current = _uiState.value
        if (!current.fileType.supportsRender || !current.fileType.supportsSourceView || current.mode == FileViewerMode.DIFF) return
        _uiState.update {
            it.copy(
                renderMode = if (it.renderMode == FileViewerRenderMode.SOURCE) FileViewerRenderMode.RENDER_PREVIEW
                else FileViewerRenderMode.SOURCE
            )
        }
    }

    /**
     * 从 DIFF 模式切换到 SOURCE 模式，让用户可以批注代码。
     * 如果源码内容从未加载过（例如通过 GIT_DIFF 进入），先获取它。
     */
    fun switchToSource() {
        val current = _uiState.value
        if (current.mode == FileViewerMode.SOURCE) return
        if (current.content.isBlank()) {
            // 源码内容从未加载 → 现在获取（loadLive 会设置 mode = SOURCE）
            loadLive()
        } else {
            // 内容已可用 → 仅切换模式
            _uiState.update { it.copy(mode = FileViewerMode.SOURCE) }
        }
    }

    /**
     * 从 SOURCE 模式切回 DIFF 模式。diff 数据（patch/hunks）在 switchToSource
     * 时保留在 uiState 中，直接切模式即可；hasDiff 为 true 即数据可用。
     * 仅 GIT_DIFF 入口会置 hasDiff，LIVE/工具快照入口调用此方法不会产生 diff。
     */
    fun switchToDiff() {
        val current = _uiState.value
        if (current.mode == FileViewerMode.DIFF) return
        if (current.hasDiff) {
            _uiState.update { it.copy(mode = FileViewerMode.DIFF) }
        } else {
            loadGitDiff()
        }
    }

    // ============ Phase 2 任务 9：工具快照 ============

    private fun loadToolSnapshot() {
        AppLogger.d(TAG, "loadToolSnapshot: toolPartIds=${toolPartIds.map { it.take(12) }}")
        if (toolPartIds.isEmpty()) {
            AppLogger.w(TAG, "loadToolSnapshot: toolPartIds EMPTY → error")
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            AppLogger.w(TAG, "loadToolSnapshot: cache MISS for ids=${toolPartIds.map { it.take(12) }} → error. " +
                "cacheSize=${toolSnapshotCache.size()}")
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val first = snapshots.first()
        val content = first.content ?: first.after ?: ""
        AppLogger.d(TAG, "loadToolSnapshot: cache HIT, ${snapshots.size} snapshots, " +
            "first.contentLen=${first.content?.length ?: -1}, " +
            "first.afterLen=${first.after?.length ?: -1}, " +
            "resolvedContentLen=${content.length}, toolName=${first.toolName}")
        if (content.isBlank()) {
            AppLogger.w(TAG, "loadToolSnapshot: resolved content is BLANK → will show empty file!")
        }
        setupToolSnapshotSource(content, snapshots)
    }

    private fun loadToolSnapshotDiff() {
        AppLogger.d(TAG, "loadToolSnapshotDiff: toolPartIds=${toolPartIds.map { it.take(12) }}")
        if (toolPartIds.isEmpty()) {
            AppLogger.w(TAG, "loadToolSnapshotDiff: toolPartIds EMPTY → error")
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            AppLogger.w(TAG, "loadToolSnapshotDiff: cache MISS for ids=${toolPartIds.map { it.take(12) }} → error. " +
                "cacheSize=${toolSnapshotCache.size()}")
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val lastSnap = snapshots.last()
        AppLogger.d(TAG, "loadToolSnapshotDiff: cache HIT, ${snapshots.size} snapshots, " +
            "lastSnap.afterLen=${lastSnap.after?.length ?: -1}, " +
            "lastSnap.contentLen=${lastSnap.content?.length ?: -1}, " +
            "lastSnap.beforeLen=${lastSnap.before?.length ?: -1}")
        // Edit 工具只缓存 newString 片段 — 不是完整文件。
        // 从服务器获取完整文件内容，使查看器显示
        // 整个文件（而不仅是被编辑的片段）。
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    AppLogger.d(TAG, "loadToolSnapshotDiff: getFileContent SUCCESS in " +
                        "${System.currentTimeMillis() - t0}ms, type=${c.type}, contentLen=${c.content.length}")
                    if (c.type == ContentType.BINARY) {
                        _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                    } else {
                        setupToolSnapshotSource(c.content, snapshots)
                    }
                }
                .onFailure {
                    val fallback = lastSnap.after ?: lastSnap.content ?: lastSnap.before ?: ""
                    AppLogger.w(TAG, "loadToolSnapshotDiff: getFileContent FAILED in " +
                        "${System.currentTimeMillis() - t0}ms, fallbackLen=${fallback.length}", it)
                    setupToolSnapshotSource(fallback, snapshots)
                }
        }
    }

    /**
     * TOOL_SNAPSHOT 和 TOOL_SNAPSHOT_DIFF 的共享初始化：
     * 用分页的源码内容 + 批注管理器 + 工具元数据填充 UI 状态。
     */
    private fun setupToolSnapshotSource(content: String, snapshots: List<dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache.Snapshot>) {
        val first = snapshots.first()
        val last = snapshots.last()
        fullContentCache = content
        resetSlice()
        val totalLines = if (content.isEmpty()) 0
                         else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
        val initialVisible = minOf(totalLines, INITIAL_PAGE_SIZE)
        val visible = takeFirstLines(content, initialVisible)
        annotationManager = AnnotationManager(content).also { manager ->
            annotationsHolder[holderKey(serverId, filePath)]?.let { h ->
                if (System.currentTimeMillis() - h.savedAt <= RECREATE_GRACE_MS) {
                    manager.restore(h.annotations)
                }
            }
        }
        // 对于 Edit 工具：在完整文件中找到被修改的区域，滚动到那里
        val editSnippet = last.after ?: last.content ?: last.before ?: ""
        val scrollLine = if (editSnippet.isNotBlank()) {
            val firstLine = editSnippet.lines().firstOrNull { it.isNotBlank() } ?: ""
            val offset = if (firstLine.length > 3) content.indexOf(firstLine) else -1
            if (offset >= 0) content.substring(0, offset).count { it == '\n' } else -1
        } else -1
        _uiState.update {
            it.copy(
                isLoading = false,
                mode = FileViewerMode.SOURCE,
                content = visible,
                isEmpty = content.isBlank(),
                fileType = FileType.fromExtension(filePath),
                renderMode = FileViewerRenderMode.SOURCE,
                isToolSnapshot = true,
                toolSnapshotContent = first.content,
                toolSnapshotBefore = first.before,
                toolSnapshotAfter = last.after ?: last.content,
                totalLineCount = totalLines,
                visibleLineCount = initialVisible,
                isFullyLoaded = initialVisible >= totalLines,
                initialScrollLine = scrollLine.coerceAtLeast(0),
                annotations = annotationManager?.getAll() ?: emptyList()
            )
        }
    }

    fun cleanupToolSnapshots() {
        if (toolPartIds.isNotEmpty()) toolSnapshotCache.clear(toolPartIds)
    }

    override fun onCleared() {
        super.onCleared()
        cleanupToolSnapshots()
    }
}
