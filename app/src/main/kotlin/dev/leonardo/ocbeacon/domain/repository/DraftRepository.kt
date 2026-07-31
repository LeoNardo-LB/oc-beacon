package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.Draft

interface DraftRepository {
    fun getDraft(sessionId: String): Draft?
    fun saveDraft(sessionId: String, draft: Draft)
    fun clearDraft(sessionId: String)
    fun getDraftSessionIds(): Set<String>
}
