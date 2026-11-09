package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.vpn.WireGuardManager
import com.nuvio.tv.core.vpn.VpnDisplayPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WireGuardSettingsViewModel @Inject constructor(
    val wireGuard: WireGuardManager,
    val displayPreferences: VpnDisplayPreferences
) : ViewModel()
