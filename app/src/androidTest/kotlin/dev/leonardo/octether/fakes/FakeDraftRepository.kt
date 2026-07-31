package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.Draft
import dev.leonardo.octether.domain.repository.DraftRepository
import javax.inject.Singleton

@Singleton
class FakeDraftRepository @Inject constructor() : DraftRepository {

    private val drafts = mutableMapOf<String, Draft>()

    override fun getDraft(sessionId: String): Draft? = drafts[sessionId]

    override fun saveDraft(sessionId: String, draft: Draft) {
        drafts[sessionId] = draft
    }

    override fun clearDraft(sessionId: String) {
        drafts.remove(sessionId)
    }

    override fun getDraftSessionIds(): Set<String> = drafts.keys.toSet()
}
