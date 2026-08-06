package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageAgentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "DraftInputDelegate"

/**
 * 管理草稿文本/附件、@ 文件提及搜索、持久化草稿加载/保存，
 * 以及此前内联在 [ChatViewModel] 中的失败发送/revert 草稿恢复状态。
 *
 * 在 Phase 3 Task 2（D 集群）中提取。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的运行时上下文
 *（ViewModel 的协程作用域、session-id/directory provider，以及持久化完整
 * [Draft] 所需的 agent/variant provider），Hilt 无法提供这些。ChatViewModel 直接构造它
 * 并将每个成员作为门面重新暴露，因此 UI 文件无需改动。
 */
internal class DraftInputDelegate(
    private val draftRepository: DraftRepository,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val sessionIdProvider: () -> String,
    private val sessionDirectoryProvider: () -> String?,
    private val selectedAgentProvider: () -> Pair<String, Boolean>,
    private val selectedVariantProvider: () -> String?,
) {
    // ============ 草稿状态 ============
    /** 输入框的草稿文本 —— 导航/应用重启时存活。 */
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText

    /** 草稿附件 URI（content:// URI 字符串）—— 导航/应用重启时存活。 */
    private val _draftAttachmentUris = MutableStateFlow<List<String>>(emptyList())
    val draftAttachmentUris: StateFlow<List<String>> = _draftAttachmentUris

    /** 用户从弹出窗选择确认的文件路径集合 */
    private val _confirmedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val confirmedFilePaths: StateFlow<Set<String>> = _confirmedFilePaths

    /** 一次性事件：为 ChatScreen 发射 revert 草稿载荷（文本 + 图片附件）。 */
    private val _revertedDraftEvent = MutableSharedFlow<RevertedDraftPayload>(extraBufferCapacity = 1)
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> = _revertedDraftEvent

    /** 发送失败后恢复的草稿。UI 消费一次后置回 null。 */
    private val _restoredDraft = MutableStateFlow<RevertedDraftPayload?>(null)
    val restoredDraftState: StateFlow<RevertedDraftPayload?> = _restoredDraft

    // ============ @ 文件提及搜索 ============
    /** @ 自动补全的文件搜索结果 */
    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults

    /** 文件搜索的 debounce job */
    private var fileSearchJob: Job? = null

    /** 搜索文件和目录用于 @ 提及自动补全。150ms debounce。 */
    fun searchFilesForMention(query: String) {
        fileSearchJob?.cancel()
        if (query.isEmpty()) {
            // 立即显示最近/热门文件，无 debounce
            fileSearchJob = scope.launch {
                try {
                    val results = manageAgentUseCase.searchFiles(
                        serverId = serverId,
                        query = "",
                        dirs = "true",
                        directory = sessionDirectoryProvider(),
                        limit = 15
                    )
                    _fileSearchResults.value = results
                } catch (e: Exception) {
                    Log.e(TAG, "File search failed", e)
                    _fileSearchResults.value = emptyList()
                }
            }
            return
        }
        fileSearchJob = scope.launch {
            delay(150) // debounce
            try {
                val results = manageAgentUseCase.searchFiles(
                    serverId = serverId,
                    query = query,
                    dirs = "true",
                    directory = sessionDirectoryProvider(),
                    limit = 15
                )
                _fileSearchResults.value = results
            } catch (e: Exception) {
                Log.e(TAG, "File search failed for query '$query'", e)
                _fileSearchResults.value = emptyList()
            }
        }
    }

    /** 添加确认的文件路径（用户从弹出窗选择） */
    fun confirmFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value + path
    }

    /** 移除确认的文件路径 */
    fun removeFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value - path
    }

    /** 清除文件搜索结果（如弹出窗关闭时） */
    fun clearFileSearch() {
        fileSearchJob?.cancel()
        _fileSearchResults.value = emptyList()
    }

    /** 清除确认的文件路径（如发送消息后） */
    fun clearConfirmedPaths() {
        _confirmedFilePaths.value = emptySet()
    }

    // ============ 草稿管理 ============

    /** 更新草稿文本（每次按键时调用）。 */
    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    /** 向草稿添加附件 URI。 */
    fun addDraftAttachment(uri: String) {
        _draftAttachmentUris.value = _draftAttachmentUris.value + uri
    }

    /** 通过索引从草稿中移除附件 URI。 */
    fun removeDraftAttachment(index: Int) {
        val current = _draftAttachmentUris.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftAttachmentUris.value = current
        }
    }

    /** 清除所有草稿状态（发送消息后调用）。 */
    fun clearDraft() {
        _draftText.value = ""
        _draftAttachmentUris.value = emptyList()
        draftRepository.clearDraft(sessionIdProvider())
    }

    /** UI 读取恢复草稿后消费它。 */
    fun consumeRestoredDraft() {
        _restoredDraft.value = null
    }

    /** 将当前草稿持久化到磁盘。 */
    fun saveDraft() {
        val agentPair = selectedAgentProvider()
        val draft = Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = selectedVariantProvider()
        )
        draftRepository.saveDraft(sessionIdProvider(), draft)
    }

    /**
     * 从磁盘加载持久化草稿并应用 D 集群字段（文本/附件/文件路径）。
     * 返回完整 [Draft]，使 ChatViewModel 可以应用 agent/variant（跨集群）。
     */
    fun restorePersistedDraft(): Draft? {
        val draft = draftRepository.getDraft(sessionIdProvider()) ?: return null
        _draftText.value = draft.text
        _draftAttachmentUris.value = draft.imageUris
        if (draft.confirmedFilePaths.isNotEmpty()) {
            _confirmedFilePaths.value = draft.confirmedFilePaths.toSet()
        }
        return draft
    }

    /** 发送失败后设置恢复草稿状态。 */
    fun setRestoredDraft(payload: RevertedDraftPayload) {
        _restoredDraft.value = payload
    }

    /** 从 revert 操作恢复草稿（由 ChatViewModel.revertMessage 调用）。 */
    fun restoreRevertedDraft(payload: RevertedDraftPayload) {
        _draftText.value = payload.text
        _draftAttachmentUris.value = payload.attachmentUris
        _confirmedFilePaths.value = emptySet()
        _revertedDraftEvent.tryEmit(payload)
    }
}
