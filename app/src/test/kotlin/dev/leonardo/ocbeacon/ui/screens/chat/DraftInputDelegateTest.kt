package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageAgentUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftInputDelegateTest {

    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val manageAgentUseCase = mockk<ManageAgentUseCase>(relaxed = true)

    private fun delegate(
        scope: kotlinx.coroutines.CoroutineScope,
        sessionId: String = "ses_1",
    ) = DraftInputDelegate(
        draftRepository = draftRepository,
        manageAgentUseCase = manageAgentUseCase,
        scope = scope,
        serverId = "srv_1",
        sessionIdProvider = { sessionId },
        sessionDirectoryProvider = { null },
        selectedAgentProvider = { "agent" to true },
        selectedVariantProvider = { null },
    )

    @Test
    fun updateDraftText_debouncedPersistsAfter500ms() = runTest {
        every { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        d.updateDraftText("hello")

        // 防抖窗口内未持久化
        advanceTimeBy(400)
        verify(exactly = 0) { draftRepository.saveDraft(any(), any()) }

        // 超过 500ms 后持久化
        advanceTimeBy(200)
        verify(exactly = 1) { draftRepository.saveDraft("ses_1", match { it.text == "hello" }) }
    }

    @Test
    fun updateDraftText_rapidInputsOnlyPersistOnce() = runTest {
        every { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        // 连续快速输入（每次都在防抖窗口内）→ 只持久化最后一次
        d.updateDraftText("h")
        advanceTimeBy(200)
        d.updateDraftText("he")
        advanceTimeBy(200)
        d.updateDraftText("hello")
        advanceTimeBy(200)

        verify(exactly = 0) { draftRepository.saveDraft(any(), any()) }

        advanceTimeBy(500)
        verify(exactly = 1) { draftRepository.saveDraft("ses_1", match { it.text == "hello" }) }
    }

    @Test
    fun clearDraft_cancelsPendingDebounce() = runTest {
        every { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        d.updateDraftText("temporary")
        advanceTimeBy(200)
        d.clearDraft()
        advanceUntilIdle()

        // clearDraft 已取消防抖 job → 不会把清空前的文本存回去
        verify(exactly = 0) { draftRepository.saveDraft(any(), match { it.text == "temporary" }) }
        verify(exactly = 1) { draftRepository.clearDraft("ses_1") }
    }

    @Test
    fun draftText_stateUpdatedImmediately() {
        every { draftRepository.getDraft(any()) } returns null
        val d = delegate(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        d.updateDraftText("immediate")

        assertEquals("immediate", d.draftText.value)
    }
}
