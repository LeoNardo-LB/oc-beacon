package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Use case: update application settings.
 * Delegates to [SettingsRepository.updateSettings].
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(settings: AppSettings): Result<Unit> {
        return settingsRepository.updateSettings(settings)
    }
}
