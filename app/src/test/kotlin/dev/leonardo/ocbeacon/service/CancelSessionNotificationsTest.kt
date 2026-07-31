package dev.leonardo.ocbeacon.service

import android.app.NotificationManager
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

class CancelSessionNotificationsTest {

    private lateinit var manager: AppNotificationManager
    private val eventDispatcher: EventDispatcher = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()
    private val notificationManager: NotificationManager = mockk(relaxed = true)

    @Before
    fun setup() {
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        manager = AppNotificationManager(eventDispatcher, settingsDataStore)
    }

    @Test
    fun `cancels all 4 type offsets for the session`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val baseId = ("server1" + "session1").hashCode()
        verify(exactly = 1) { notificationManager.cancel(baseId + 0) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 1000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 2000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 3000) }
    }

    @Test
    fun `does not cancel group summary`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val summaryId = "server_summary_server1".hashCode()
        verify(exactly = 0) { notificationManager.cancel(summaryId) }
    }
}
