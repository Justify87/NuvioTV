package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.vpn.WireGuardManager
import com.nuvio.tv.core.vpn.importWireGuardProfileFromUri
import com.nuvio.tv.core.vpn.wireGuardImportErrorMessage
import com.nuvio.tv.core.vpn.VpnDisplayPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WireGuardSettingsUiState(
    val actionInProgress: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class WireGuardSettingsViewModel @Inject constructor(
    val wireGuard: WireGuardManager,
    val displayPreferences: VpnDisplayPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(WireGuardSettingsUiState())
    val uiState: StateFlow<WireGuardSettingsUiState> = _uiState.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(wireGuard.selectedId)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    init {
        // A file manager can import a profile while settings are in the background.
        viewModelScope.launch {
            wireGuard.profiles.collect { _selectedProfileId.value = wireGuard.selectedId }
        }
    }

    fun connect() = runVpnAction { wireGuard.connect() }

    fun disconnect() = runVpnAction { wireGuard.disconnect() }

    fun refreshVerification() = runVpnAction("VPN-Verbindung erfolgreich überprüft") {
        wireGuard.refreshVerification()
    }

    fun selectProfile(id: String) = runVpnAction {
        wireGuard.selectProfile(id)
        _selectedProfileId.value = wireGuard.selectedId
    }

    fun renameProfile(id: String, name: String) = runVpnAction {
        wireGuard.renameProfile(id, name)
    }

    fun deleteProfile(id: String) = runVpnAction {
        wireGuard.deleteProfile(id)
        _selectedProfileId.value = wireGuard.selectedId
    }

    fun importProfile(uri: Uri) = runVpnAction(
        successMessage = "WireGuard-Profil importiert. VPN verbinden, um es zu aktivieren.",
        errorMessage = { "Import fehlgeschlagen: ${wireGuardImportErrorMessage(it)}" }
    ) {
        val profile = importWireGuardProfileFromUri(context, wireGuard, uri)
        _selectedProfileId.value = profile.id
    }

    private fun runVpnAction(
        successMessage: String? = null,
        errorMessage: (Exception) -> String = { it.message ?: "VPN action failed" },
        action: suspend () -> Unit
    ) {
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
                    message = errorMessage(error)
                )
            } finally {
                _uiState.value = _uiState.value.copy(actionInProgress = false)
            }
        }
    }
}
