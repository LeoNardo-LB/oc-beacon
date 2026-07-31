package dev.leonardo.octether.domain.repository

import dev.leonardo.octether.domain.model.Draft

interface DraftRepository {
    fun getDraft(sessionId: String): Draft?
    fun saveDraft(sessionId: String, draft: Draft)
    fun clearDraft(sessionId: String)
    fun getDraftSessionIds(): Set<String>
}
