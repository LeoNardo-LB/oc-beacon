package dev.leonardo.ocbeacon.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun `interface defines promptAsync, sendMessage, revertSession, unrevertSession, respondPermission, selectModel`() {
        val methods = ChatRepository::class.java.declaredMethods.map { it.name }
        assertTrue("promptAsync missing", methods.any { it.startsWith("promptAsync") })
        assertTrue("sendMessage present (existing)", methods.any { it.startsWith("sendMessage") })
        assertTrue("revertSession missing", methods.any { it.startsWith("revertSession") })
        assertTrue("unrevertSession missing", methods.any { it.startsWith("unrevertSession") })
        assertTrue("respondPermission missing", methods.any { it.startsWith("respondPermission") })
        assertTrue("getParts missing", methods.any { it.startsWith("getParts") })
        assertTrue("listPendingPermissions missing", methods.any { it.startsWith("listPendingPermissions") })
        assertTrue("listPendingQuestions missing", methods.any { it.startsWith("listPendingQuestions") })
        assertTrue("replyToQuestion missing", methods.any { it.startsWith("replyToQuestion") })
        assertTrue("rejectQuestion missing", methods.any { it.startsWith("rejectQuestion") })
        assertTrue("executeCommand missing", methods.any { it.startsWith("executeCommand") })
        assertTrue("runShellCommand missing", methods.any { it.startsWith("runShellCommand") })
    }

    @Test
    fun `interface defines EventDispatcher flow exposure methods`() {
        val methods = ChatRepository::class.java.declaredMethods.map { it.name }
        assertTrue("getActiveToolProgress missing", methods.any { it.startsWith("getActiveToolProgress") })
        assertTrue("getStepProgress missing", methods.any { it.startsWith("getStepProgress") })
        assertTrue("getCompactionState missing", methods.any { it.startsWith("getCompactionState") })
    }
}
