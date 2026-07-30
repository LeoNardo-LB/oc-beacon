package dev.leonardo.ocremoteplus.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogRepositoryTest {

    // ---- Credential / token redaction ----

    @Test
    fun redactsCredentialsBeforePersistence() {
        val sanitized = DiagnosticLogRepository.sanitize(
            "Authorization: Bearer secret-token password=hunter2 api_key=sk-secret https://example.test?state=oauth-state&code=oauth-code",
        )

        assertFalse(sanitized.contains("secret-token"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("sk-secret"))
        assertFalse(sanitized.contains("oauth-state"))
        assertFalse(sanitized.contains("oauth-code"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun redactsHeadersCookiesOauthAndUrlCredentials() {
        val sanitized = DiagnosticLogRepository.sanitize(
            """
            Authorization: Digest private-value
            Cookie: session=private-cookie
            client_secret=private-secret code_verifier=private-verifier
            https://user:pass@example.test/callback?state=private-state&code=private-code
            """.trimIndent(),
        )

        listOf(
            "private-value", "private-cookie", "private-secret",
            "private-verifier", "user:pass", "private-state", "private-code",
        ).forEach { secret -> assertFalse("must not contain '$secret'", sanitized.contains(secret)) }
    }

    // ---- IP address redaction ----

    @Test
    fun redactsIpv4Addresses() {
        val sanitized = DiagnosticLogRepository.sanitize("server at 192.168.10.20 port 8080")

        assertFalse(sanitized.contains("192.168.10.20"))
        assertTrue(sanitized.contains("[IP]"))
    }

    @Test
    fun redactsIpv6Addresses() {
        val sanitized = DiagnosticLogRepository.sanitize("host 2001:db8::1 ready")

        assertFalse(sanitized.contains("2001:db8::1"))
        assertTrue(sanitized.contains("[IP]"))
    }

    // ---- Local user path redaction ----

    @Test
    fun redactsUnixUserPaths() {
        val sanitized = DiagnosticLogRepository.sanitize("loaded /home/alice/private/project.kt")

        assertFalse(sanitized.contains("alice"))
        assertTrue(sanitized.contains("[PATH]"))
    }

    @Test
    fun redactsWindowsUserPaths() {
        val sanitized = DiagnosticLogRepository.sanitize("loaded C:\\Users\\carol\\secret\\config.json")

        assertFalse(sanitized.contains("carol"))
        assertTrue(sanitized.contains("[PATH]"))
    }

    // ---- Export second-pass sanitization ----

    @Test
    fun exportPerformsSecondSanitizationPass() {
        val exported = DiagnosticLogRepository.export(
            listOf(
                DiagnosticLogEntry(
                    timestamp = 0,
                    level = "ERROR",
                    category = "Auth",
                    message = "password=late-secret",
                    details = mapOf("Authorization" to "Bearer late-token"),
                ),
            ),
        )

        assertFalse(exported.contains("late-secret"))
        assertFalse(exported.contains("late-token"))
        assertTrue(exported.contains("[REDACTED]"))
    }

    // ---- Length bounding ----

    @Test
    fun sanitizerBoundsEachField() {
        assertEquals(1000, DiagnosticLogRepository.sanitize("x".repeat(2000)).length)
    }
}
