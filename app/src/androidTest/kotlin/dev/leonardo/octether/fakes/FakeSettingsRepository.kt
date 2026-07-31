package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.AppSettings
import dev.leonardo.octether.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {

    val settingsState = MutableStateFlow(AppSettings())
    val hiddenModelsState = MutableStateFlow<Set<String>>(emptySet())
    var updateSettingsResult: Result<Unit> = Result.success(Unit)

    override fun getSettingsFlow(): Flow<AppSettings> = settingsState

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> {
        settingsState.value = settings
        return updateSettingsResult
    }

    override fun hiddenModels(serverId: String): Flow<Set<String>> = hiddenModelsState
}
