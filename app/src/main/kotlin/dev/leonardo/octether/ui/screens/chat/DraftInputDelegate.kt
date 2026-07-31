package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import dev.leonardo.octether.domain.model.Draft
import dev.leonardo.octether.domain.usecase.DraftUseCase
import dev.leonardo.octether.domain.usecase.ManageAgentUseCase
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
 * Owns draft text/attachments, @-file mention search, persisted-draft load/save,
 * and failed-send/revert draft recovery state previously inlined in [ChatViewModel].
 *
 * Extracted in Phase 3 Task 2 (D cluster).
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel runtime context
 * (the ViewModel's coroutine scope, session-id/directory providers, and agent/variant providers
 * needed to persist a complete [Draft]) that Hilt cannot supply. ChatViewModel constructs it
 * directly and re-exposes every member as a facade, so UI files are unchanged.
 */
internal class DraftInputDelegate(
    private val draftUseCase: DraftUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val sessionIdProvider: () -> String,
    private val sessionDirectoryProvider: () -> String?,
    private val selectedAgentProvider: () -> Pair<String, Boolean>,
    private val selectedVariantProvider: () -> String?,
) {
    // ============ Draft State ============
    /** Draft text for the input field — survives navigation / app restart. */
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText

    /** Draft attachment URIs (content:// URIs as strings) — survives navigation / app restart. */
    private val _draftAttachmentUris = MutableStateFlow<List<String>>(emptyList())
    val draftAttachmentUris: StateFlow<List<String>> = _draftAttachmentUris

    /** Set of file paths that have been confirmed by user selection from the popup */
    private val _confirmedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val confirmedFilePaths: StateFlow<Set<String>> = _confirmedFilePaths

    /** One-shot event: emits reverted draft payload (text + image attachments) for ChatScreen. */
    private val _revertedDraftEvent = MutableSharedFlow<RevertedDraftPayload>(extraBufferCapacity = 1)
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> = _revertedDraftEvent

    /** Draft restored after a failed send. UI consumes once and sets back to null. */
    private val _restoredDraft = MutableStateFlow<RevertedDraftPayload?>(null)
    val restoredDraftState: StateFlow<RevertedDraftPayload?> = _restoredDraft

    // ============ @ File Mention Search ============
    /** File search results for @-autocomplete */
    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults

    /** Debounce job for file search */
    private var fileSearchJob: Job? = null

    /** Search files and directories for @-mention autocomplete. Debounced by 150ms. */
    fun searchFilesForMention(query: String) {
        fileSearchJob?.cancel()
        if (query.isEmpty()) {
            // Show recent/top files immediately with no debounce
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

    /** Add a confirmed file path (user selected it from the popup) */
    fun confirmFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value + path
    }

    /** Remove a confirmed file path */
    fun removeFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value - path
    }

    /** Clear file search results (e.g. when popup is closed) */
    fun clearFileSearch() {
        fileSearchJob?.cancel()
        _fileSearchResults.value = emptyList()
    }

    /** Clear confirmed file paths (e.g. after sending a message) */
    fun clearConfirmedPaths() {
        _confirmedFilePaths.value = emptySet()
    }

    // ============ Draft Management ============

    /** Update the draft text (called on every keystroke). */
    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    /** Add an attachment URI to the draft. */
    fun addDraftAttachment(uri: String) {
        _draftAttachmentUris.value = _draftAttachmentUris.value + uri
    }

    /** Remove an attachment URI from the draft by index. */
    fun removeDraftAttachment(index: Int) {
        val current = _draftAttachmentUris.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftAttachmentUris.value = current
        }
    }

    /** Clear all draft state (called after sending a message). */
    fun clearDraft() {
        _draftText.value = ""
        _draftAttachmentUris.value = emptyList()
        draftUseCase.clearDraft(sessionIdProvider())
    }

    /** Consume the restored draft after UI has read it. */
    fun consumeRestoredDraft() {
        _restoredDraft.value = null
    }

    /** Persist current draft to disk. */
    fun saveDraft() {
        val agentPair = selectedAgentProvider()
        val draft = Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = selectedVariantProvider()
        )
        draftUseCase.saveDraft(sessionIdProvider(), draft)
    }

    /**
     * Load persisted draft from disk and apply D-cluster fields (text/attachments/filePaths).
     * Returns the full [Draft] so ChatViewModel can apply agent/variant (cross-cluster).
     */
    fun restorePersistedDraft(): Draft? {
        val draft = draftUseCase.getDraft(sessionIdProvider()) ?: return null
        _draftText.value = draft.text
        _draftAttachmentUris.value = draft.imageUris
        if (draft.confirmedFilePaths.isNotEmpty()) {
            _confirmedFilePaths.value = draft.confirmedFilePaths.toSet()
        }
        return draft
    }

    /** Set the restored-draft state after a failed send. */
    fun setRestoredDraft(payload: RevertedDraftPayload) {
        _restoredDraft.value = payload
    }

    /** Restore draft from a revert operation (called by ChatViewModel.revertMessage). */
    fun restoreRevertedDraft(payload: RevertedDraftPayload) {
        _draftText.value = payload.text
        _draftAttachmentUris.value = payload.attachmentUris
        _confirmedFilePaths.value = emptySet()
        _revertedDraftEvent.tryEmit(payload)
    }
}
