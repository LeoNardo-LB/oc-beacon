package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageAgentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        coEvery { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        d.updateDraftText("hello")

        // 防抖窗口内未持久化
        advanceTimeBy(400)
        coVerify(exactly = 0) { draftRepository.saveDraft(any(), any()) }

        // 超过 500ms 后持久化
        advanceTimeBy(200)
        coVerify(exactly = 1) { draftRepository.saveDraft("ses_1", match { it.text == "hello" }) }
    }

    @Test
    fun updateDraftText_rapidInputsOnlyPersistOnce() = runTest {
        coEvery { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        // 连续快速输入（每次都在防抖窗口内）→ 只持久化最后一次
        d.updateDraftText("h")
        advanceTimeBy(200)
        d.updateDraftText("he")
        advanceTimeBy(200)
        d.updateDraftText("hello")
        advanceTimeBy(200)

        coVerify(exactly = 0) { draftRepository.saveDraft(any(), any()) }

        advanceTimeBy(500)
        coVerify(exactly = 1) { draftRepository.saveDraft("ses_1", match { it.text == "hello" }) }
    }

    @Test
    fun clearDraft_cancelsPendingDebounce() = runTest {
        coEvery { draftRepository.getDraft(any()) } returns null
        val d = delegate(this)

        d.updateDraftText("temporary")
        advanceTimeBy(200)
        d.clearDraft()
        advanceUntilIdle()

        // clearDraft 已取消防抖 job → 不会把清空前的文本存回去
        coVerify(exactly = 0) { draftRepository.saveDraft(any(), match { it.text == "temporary" }) }
        coVerify(exactly = 1) { draftRepository.clearDraft("ses_1") }
    }

    @Test
    fun draftText_stateUpdatedImmediately() {
        coEvery { draftRepository.getDraft(any()) } returns null
        val d = delegate(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        d.updateDraftText("immediate")

        assertEquals("immediate", d.draftText.value)
    }

    // ============ backlog #38：异步草稿恢复竞态测试 ============

    @Test
    fun `restorePersistedDraft applies draft when no user input`() = runTest {
        val persisted = Draft(
            text = "saved text",
            imageUris = listOf("content://img1"),
            confirmedFilePaths = listOf("/path/file"),
            selectedAgent = "agent-x",
            selectedVariant = "variant-y",
        )
        coEvery { draftRepository.getDraft("ses_1") } returns persisted
        val d = delegate(this)

        val result = d.restorePersistedDraft()

        assertEquals(persisted, result)
        assertEquals("saved text", d.draftText.value)
        assertEquals(listOf("content://img1"), d.draftAttachmentUris.value)
        assertEquals(setOf("/path/file"), d.confirmedFilePaths.value)
    }

    @Test
    fun `restorePersistedDraft skips text when user already typed - race condition guard`() = runTest {
        // 竞态场景：异步恢复到达前用户已开始打字。
        // 期望：保留用户输入，不覆盖。
        val persisted = Draft(text = "saved text")
        coEvery { draftRepository.getDraft("ses_1") } returns persisted
        val d = delegate(this)

        // 用户先输入（模拟恢复未完成前的用户操作）
        d.updateDraftText("user typed")
        advanceUntilIdle() // 让防抖 saveDraft 不干扰（它不会触发因为 getDraft mock 已设置）

        val result = d.restorePersistedDraft()

        // 返回 Draft（用于 agent/variant 恢复），但文本不被覆盖
        assertEquals(persisted, result)
        assertEquals("user typed", d.draftText.value) // 用户输入保留
    }

    @Test
    fun `restorePersistedDraft skips attachments when user already added`() = runTest {
        val persisted = Draft(text = "saved", imageUris = listOf("content://old"))
        coEvery { draftRepository.getDraft("ses_1") } returns persisted
        val d = delegate(this)

        d.addDraftAttachment("content://user-added")
        val result = d.restorePersistedDraft()

        assertEquals(persisted, result)
        assertEquals(listOf("content://user-added"), d.draftAttachmentUris.value)
    }

    @Test
    fun `restorePersistedDraft returns null when no draft`() = runTest {
        coEvery { draftRepository.getDraft("ses_1") } returns null
        val d = delegate(this)

        assertNull(d.restorePersistedDraft())
        assertTrue(d.draftText.value.isBlank())
    }
}
