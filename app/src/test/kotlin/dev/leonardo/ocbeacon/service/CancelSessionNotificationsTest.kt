package dev.leonardo.ocbeacon.service

import android.app.NotificationManager
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.model.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

class CancelSessionNotificationsTest {

    private lateinit var manager: AppNotificationManager
    private val eventDispatcher: EventDispatcher = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val notificationManager: NotificationManager = mockk(relaxed = true)

    @Before
    fun setup() {
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.sessions } returns MutableStateFlow<List<Session>>(emptyList())
        manager = AppNotificationManager(
            eventDispatcher,
            settingsRepository,
            SessionFocusHolder(),
            mockk(relaxed = true),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @Test
    fun `cancels all 4 type offsets for the session`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val baseId = stableHash("server1", "session1")
        verify(exactly = 1) { notificationManager.cancel(baseId + 0) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 1000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 2000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 3000) }
    }

    @Test
    fun `does not cancel group summary`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val summaryId = stableHash("summary", "server1")
        verify(exactly = 0) { notificationManager.cancel(summaryId) }
    }

    private fun stableHash(vararg parts: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (part in parts) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        return hash
    }
}
