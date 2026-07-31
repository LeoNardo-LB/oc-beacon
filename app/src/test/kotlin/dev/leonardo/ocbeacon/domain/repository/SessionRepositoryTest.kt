package dev.leonardo.ocbeacon.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {

    @Test
    fun `interface defines abort, rename, fork, exportSession, getSessionStatusesFlow`() {
        val methods = SessionRepository::class.java.declaredMethods.map { it.name }
        assertTrue("abort missing", methods.any { it.startsWith("abort") })
        assertTrue("rename missing", methods.any { it.startsWith("rename") })
        assertTrue("fork missing", methods.any { it.startsWith("fork") })
        assertTrue("exportSessionToStream missing", methods.any { it.startsWith("exportSessionToStream") })
        assertTrue("getSessionStatusesFlow missing", methods.any { it.startsWith("getSessionStatusesFlow") })
        assertTrue("archive missing", methods.any { it.startsWith("archive") })
        assertTrue("unarchive missing", methods.any { it.startsWith("unarchive") })
        assertTrue("shareSession missing", methods.any { it.startsWith("shareSession") })
        assertTrue("unshareSession missing", methods.any { it.startsWith("unshareSession") })
        assertTrue("compactSession missing", methods.any { it.startsWith("compactSession") })
        assertTrue("importSession missing", methods.any { it.startsWith("importSession") })
        assertTrue("deleteMessage missing", methods.any { it.startsWith("deleteMessage") })
        assertTrue("listMessages missing", methods.any { it.startsWith("listMessages") })
        assertTrue("getSession missing", methods.any { it.startsWith("getSession") })
    }
}
