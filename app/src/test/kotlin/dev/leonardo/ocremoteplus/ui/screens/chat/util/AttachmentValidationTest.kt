package dev.leonardo.ocremoteplus.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentValidationTest {

    @Test
    fun `image accepted within size limit`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("image/png", "photo.png", 5 * 1024 * 1024),
        )
    }

    @Test
    fun `pdf accepted within document limit`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/pdf", "doc.pdf", 8 * 1024 * 1024),
        )
    }

    @Test
    fun `oversized pdf rejected as too large`() {
        assertEquals(
            LocalAttachmentValidation.TOO_LARGE,
            validateLocalAttachment("application/pdf", "big.pdf", (10 * 1024 * 1024) + 1),
        )
    }

    @Test
    fun `text file accepted within text limit`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("text/plain", "notes.txt", 1024),
        )
    }

    @Test
    fun `text file by extension accepted even with generic mime`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/octet-stream", "script.kt", 512),
        )
    }

    @Test
    fun `text file rejected when exceeding text limit`() {
        assertEquals(
            LocalAttachmentValidation.TOO_LARGE,
            validateLocalAttachment("text/plain", "huge.log", (2 * 1024 * 1024) + 1),
        )
    }

    @Test
    fun `executable file rejected as unsupported`() {
        assertEquals(
            LocalAttachmentValidation.UNSUPPORTED,
            validateLocalAttachment("application/x-msdownload", "setup.exe", 1024),
        )
    }

    @Test
    fun `unknown mime without recognized extension rejected`() {
        assertEquals(
            LocalAttachmentValidation.UNSUPPORTED,
            validateLocalAttachment("application/x-foo", "data.bin", 1024),
        )
    }

    @Test
    fun `json accepted as text via application mime`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/json", "config.json", 1024),
        )
    }

    @Test
    fun `document limit boundary accepted`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("application/pdf", "boundary.pdf", MAX_DOCUMENT_ATTACHMENT_BYTES),
        )
    }

    @Test
    fun `text limit boundary accepted`() {
        assertEquals(
            LocalAttachmentValidation.ACCEPTED,
            validateLocalAttachment("text/plain", "boundary.txt", MAX_TEXT_ATTACHMENT_BYTES),
        )
    }
}
