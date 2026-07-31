package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSettingsUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val useCase = UpdateSettingsUseCase(settingsRepository)

    @Test
    fun `invoke returns success when repository succeeds`() = runTest {
        val settings = AppSettings(appTheme = "dark", dynamicColor = false)
        coEvery { settingsRepository.updateSettings(settings) } returns Result.success(Unit)

        val result = useCase(settings)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val settings = AppSettings()
        coEvery { settingsRepository.updateSettings(settings) } returns Result.failure(
            RuntimeException("Write failed")
        )

        val result = useCase(settings)

        assertTrue(result.isFailure)
        assertEquals("Write failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with partial settings change succeeds`() = runTest {
        val settings = AppSettings(chatFontSize = "large", codeWordWrap = true)
        coEvery { settingsRepository.updateSettings(settings) } returns Result.success(Unit)

        val result = useCase(settings)

        assertTrue(result.isSuccess)
    }
}
