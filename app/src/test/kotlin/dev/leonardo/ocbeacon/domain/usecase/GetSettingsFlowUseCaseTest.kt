package dev.leonardo.ocbeacon.domain.usecase

import app.cash.turbine.test
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetSettingsFlowUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val useCase = GetSettingsFlowUseCase(settingsRepository)

    @Test
    fun `invoke emits current settings`() = runTest {
        val settings = AppSettings(appTheme = "dark")
        every { settingsRepository.getSettingsFlow() } returns flowOf(settings)

        useCase().test {
            assertEquals(settings, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits default settings`() = runTest {
        every { settingsRepository.getSettingsFlow() } returns flowOf(AppSettings())

        useCase().test {
            assertEquals(AppSettings(), awaitItem())
            awaitComplete()
        }
    }
}
