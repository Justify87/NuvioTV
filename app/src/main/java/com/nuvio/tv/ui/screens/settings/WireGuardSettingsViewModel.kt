package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.vpn.WireGuardManager
import com.nuvio.tv.core.vpn.VpnDisplayPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WireGuardSettingsUiState(
    val actionInProgress: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class WireGuardSettingsViewModel @Inject constructor(
    val wireGuard: WireGuardManager,
    val displayPreferences: VpnDisplayPreferences
) : ViewModel() {
    private val _uiState = MutableStateFlow(WireGuardSettingsUiState())
    val uiState: StateFlow<WireGuardSettingsUiState> = _uiState.asStateFlow()

    fun connect() = runVpnAction { wireGuard.connect() }

    fun disconnect() = runVpnAction { wireGuard.disconnect() }

    fun refreshVerification() = runVpnAction("VPN-Verbindung erfolgreich überprüft") {
        wireGuard.refreshVerification()
    }

    private fun runVpnAction(successMessage: String? = null, action: suspend () -> Unit) {
        // Mark busy before launching so even rapid successive taps cannot queue actions.
        if (_uiState.value.actionInProgress) return
        _uiState.value = WireGuardSettingsUiState(actionInProgress = true)
        viewModelScope.launch {
            try {
                action()
                _uiState.value = WireGuardSettingsUiState(message = successMessage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = WireGuardSettingsUiState(
                    message = error.message ?: "VPN action failed"
                )
            } finally {
                _uiState.value = _uiState.value.copy(actionInProgress = false)
            }
        }
    }
}
