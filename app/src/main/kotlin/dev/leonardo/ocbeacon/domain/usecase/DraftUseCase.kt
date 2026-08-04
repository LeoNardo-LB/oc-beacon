package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import javax.inject.Inject

/**
 * Use Case：管理消息草稿（文本 + 附件 + 文件提及）。
 * 临时壳——委托给 DraftRepository。完整实现与测试在 Phase 4。
 */
class DraftUseCase @Inject constructor(
    private val draftRepository: DraftRepository
) {
    // TODO: Phase 4 —— 将 draftRepository 调用替换为 DraftRepository 接口

    fun getDraft(sessionId: String): Draft? = draftRepository.getDraft(sessionId)

    fun saveDraft(sessionId: String, draft: Draft) = draftRepository.saveDraft(sessionId, draft)

    fun clearDraft(sessionId: String) = draftRepository.clearDraft(sessionId)
}
