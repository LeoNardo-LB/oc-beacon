package dev.leonardo.ocremoteplus.ui.screens.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremoteplus.data.update.AvailableUpdate
import dev.leonardo.ocremoteplus.data.update.UpdateRepository
import dev.leonardo.ocremoteplus.data.update.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val updateState: StateFlow<UpdateState> = updateRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdateState.Idle)

    fun checkForUpdate() {
        viewModelScope.launch {
            updateRepository.check(manual = true)
        }
    }

    fun prepareInstall(release: AvailableUpdate) {
        viewModelScope.launch {
            updateRepository.prepareInstall(release)
        }
    }

    fun markInstallerLaunched() {
        updateRepository.markInstallerLaunched()
    }
}
