package dev.leonardo.ocbeacon.fakes

import dev.leonardo.ocbeacon.domain.model.PendingPromptRecord
import dev.leonardo.ocbeacon.domain.repository.PendingPromptRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakePendingPromptRepository @Inject constructor() : PendingPromptRepository {

    private val records = mutableMapOf<String, PendingPromptRecord>()

    override fun getForSession(sessionId: String): List<PendingPromptRecord> =
        records.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    override fun loadAll(): List<PendingPromptRecord> =
        records.values.sortedBy { it.createdAt }

    override fun save(record: PendingPromptRecord) {
        records[record.messageId] = record
    }

    override fun remove(messageId: String) {
        records.remove(messageId)
    }

    override fun clear() {
        records.clear()
    }
}
