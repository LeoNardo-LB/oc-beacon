package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.*
import org.junit.Test

class DraftTest {
    @Test
    fun `isEmpty returns true for default draft`() {
        assertTrue(Draft().isEmpty)
    }

    @Test
    fun `isEmpty returns false when text is present`() {
        assertFalse(Draft(text = "hello").isEmpty)
    }

    @Test
    fun `isEmpty returns false when imageUris is non-empty`() {
        assertFalse(Draft(imageUris = listOf("uri")).isEmpty)
    }
}
